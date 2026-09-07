#!/usr/bin/env python3
"""Live-match records — the server's own documents, fetched through the API and kept on disk.

Every match the server played is readable from its API: `/api/game/<id>` (the players, the code
version each side uploaded, the result, the rating change) and `/api/game/<id>/log/<tick>` (the
console in 100-tick chunks), and `/api/arena/<id>/rating-history` lists an arena's rating matches.
The API wants the client's session, and the running Arena client carries it: `tools/arena_cdp.py`
drives the client's page over CDP and a `fetch` from that page is authenticated. This module keeps
what it fetches under `~/ScreepsArena/games/<id>/` — `game.json` and `log-<tick>.json`, the
response bodies as they came — so a match is read once from the server and then from disk.

    tools/match-log.py fetch <game-id>...                    # these matches, into the store
    tools/match-log.py fetch --history 40 --arena pain-and-gain   # the arena's last 40 rating matches
    tools/match-log.py list [--arena spawn-and-swamp] [--limit 20] [--all]
    tools/match-log.py dump <game-id-prefix> [--out log.txt]

`tools/play.py` writes every match it plays into the same store as the match ends, so a series
needs no fetch afterwards; `fetch --history` is for matches played from the client's UI or on
another machine. `list` prints the bot version each match was played by, read out of the greeting
line the bot logs on tick 1 — that is the tie between a match and the code that played it — and the
opponent as name#version, the version being the server's per-user upload counter (a username is
not a bot). `tools/series.py`, `tools/ledger.py` and `tools/autopsy.py` read this module
(scan/describe/chunk_text/meta_of/full_log); keep it importable.

Until 07.09.2026 this module read Chromium's disk cache under the client instead. The operator
closed that: the cache held only what the client window had displayed, evicted by size, missed
chunks from the middle of a match, and never held a match watched elsewhere — and everything in it
was one API call away. Nothing here reads the cache any more.
"""
import argparse, gzip, importlib.util, json, os, re, sys, time
from collections import defaultdict

STORE = os.path.expanduser("~/ScreepsArena/games")
API = "https://arena.screeps.com/api"
# every season-4 bot greets as "hello <season> <arena> [v]<N>: ..." — spawn-and-swamp writes "v43",
# pain-and-gain and escort-run write a bare number
GREETING = re.compile(r"hello (\w+) ([\w-]+)(?: v?(\d+))?")


# ---------------------------------------------------------------- the store
def decompress(path):
    """A stored body as bytes (plain JSON, or gzip if someone compressed it), or b''."""
    try:
        raw = open(path, 'rb').read()
    except OSError:
        return b""
    if raw[:2] == b"\x1f\x8b":
        try:
            return gzip.decompress(raw)
        except Exception:
            return b""
    return raw


def scan(store=STORE):
    """(logs, metas): game id -> {tick: path} and game id -> path, from the store."""
    logs, metas = defaultdict(dict), {}
    if not os.path.isdir(store):
        return logs, metas
    for gid in os.listdir(store):
        d = os.path.join(store, gid)
        if not re.fullmatch(r"[0-9a-f]{24}", gid) or not os.path.isdir(d):
            continue
        for name in os.listdir(d):
            p = os.path.join(d, name)
            if name == "game.json" or name == "game.json.gz":
                metas[gid] = p
                continue
            m = re.fullmatch(r"log-(\d+)\.json(?:\.gz)?", name)
            if m:
                logs[gid][int(m.group(1))] = p
    return logs, metas


def chunk_text(path):
    """One log chunk as {tick: text}; the API returns {"<tick>": "<console output>"}."""
    body = decompress(path)
    if not body:
        return {}
    try:
        data = json.loads(body.decode('utf-8', 'replace'))
    except ValueError:
        return {}
    out = {}
    for k, v in data.items():
        if k.isdigit() and isinstance(v, str):
            out[int(k)] = v
    return out


def meta_of(path):
    """The match document — the `game` object of `/api/game/<id>`."""
    body = decompress(path)
    if not body:
        return None
    try:
        data = json.loads(body.decode('utf-8', 'replace'))
    except ValueError:
        return None
    return data.get("game") if isinstance(data, dict) and "game" in data else data


def outcome(meta):
    """'won' / 'lost' / 'draw' / '' — winner is an index into the match's code list."""
    if not meta:
        return ""
    inner = meta.get("game") or {}
    res = inner.get("result") or {}
    if inner.get("status") != "finished":
        return inner.get("status") or ""
    win = res.get("winner")
    codes = meta.get("codes") or []
    me = meta.get("user")
    if win is None or win == 0.5:
        return "draw"
    if isinstance(win, int) and 0 <= win < len(codes):
        return "won" if codes[win].get("user") == me else "lost"
    return str(win)


def log_ticks(game, logs):
    """{tick: console text} for one match, every chunk merged in tick order."""
    ticks = {}
    for t in sorted(logs.get(game, {})):
        ticks.update(chunk_text(logs[game][t]))
    return ticks


def when_of(meta, paths):
    """The match's time: the document's own timestamp, else the store file's."""
    for key in ("created", "createdAt", "startTime", "date"):
        v = (meta or {}).get(key) or ((meta or {}).get("game") or {}).get(key)
        if isinstance(v, str) and len(v) >= 19:
            try:
                return time.mktime(time.strptime(v[:19], '%Y-%m-%dT%H:%M:%S')) - time.timezone + (3600 if time.localtime().tm_isdst else 0)
            except ValueError:
                pass
        if isinstance(v, (int, float)) and v > 1e9:
            return v / 1000 if v > 1e11 else v
    return max((os.stat(p).st_mtime for p in paths), default=0)


def describe(game, logs, metas):
    chunks = logs.get(game, {})
    first = chunk_text(chunks[min(chunks)]) if chunks else {}
    greet, version, tuning = "", None, ""
    if first:
        # the greeting can sit behind an effects line or a map dump, and a pre-version bot greets without a number
        # ("hello season4 pain-and-gain: 4 - …" up to v27 of pain-and-gain) — read the whole first chunk, version optional
        head = "\n".join(first[t] for t in sorted(first))[:20000]
        m = GREETING.search(head)
        if m:
            greet, version = f"{m.group(1)}/{m.group(2)}", int(m.group(3)) if m.group(3) else None
        for line in head.split("\n"):
            if line.startswith("tuning:"):
                tuning = line[len("tuning:"):].strip()
                break
    meta = meta_of(metas[game]) if game in metas else None
    when = when_of(meta, list(chunks.values()) + ([metas[game]] if game in metas else []))
    rating, delta = "", None
    # the code versions the match was played with — the server's per-user upload counter, one per side. An opponent's
    # username is not a bot: けろびー played version 1 as a blob on 06.09 and version 3 on 07.09, and the two lose and win
    # differently; read results by name AND version (07.09.2026, the operator)
    our_code, opp_code = None, None
    if meta:
        for c in meta.get("codes") or []:
            if c.get("user") == meta.get("user"):
                our_code = c.get("version")
            else:
                opp_code = c.get("version")
    if meta and meta.get("ratingHistory"):
        r = meta["ratingHistory"]
        rating = f"{r.get('previousRating')}->{r.get('rating')}"
        if isinstance(r.get("rating"), (int, float)) and isinstance(r.get("previousRating"), (int, float)):
            delta = r["rating"] - r["previousRating"]
    users = [u.get("username") for u in (meta or {}).get("users", [])]
    me_name = next((u.get("username") for u in (meta or {}).get("users", []) if u.get("_id") == (meta or {}).get("user")), None)
    return dict(game=game, arena=greet, when=when, chunks=len(chunks), version=version, tuning=tuning,
                last=max(chunks) if chunks else 0, result=outcome(meta), rating=rating, delta=delta,
                users=users, our_code=our_code, opp_code=opp_code,
                opponent="/".join(f"{u}#{opp_code}" if opp_code is not None else u for u in users if u and u != me_name))


def full_log(game, logs):
    """One match's console as text in tick order, gaps marked — what `dump` writes, for other tools to read."""
    ticks, expected = log_ticks(game, logs), sorted(logs[game])
    lines = []
    # a chunk covers the 100 ticks ending at its key; a hole means the chunk was never fetched
    for prev, cur in zip([0] + expected, expected):
        if cur - prev > 100:
            lines.append(f"# --- ticks {prev + 1}..{cur - 100} are not in the store ---")
    for t in sorted(ticks):
        text = ticks[t].rstrip("\n")
        if text:
            lines.append(text)
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------- the API, through the client
def _play():
    spec = importlib.util.spec_from_file_location('play', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'play.py'))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def fetch_game(c, gid):
    """`/api/game/<id>` and every log chunk, as the bodies came: (game_body, {tick: chunk_body}) — raw JSON text.

    Every request goes through `get()`, which retries once after a pause: a run of fetches from the
    client's page fails now and then (`TypeError: Failed to fetch` on the seventh in a row, while the
    same chunk asked for on its own returns 200 and 13 KB), and an unhandled throw rejects the whole
    call — `fetch --history 80` died on its first match having stored nothing. A chunk that still
    fails after the retry ends the loop, exactly as a 404 does; there is nothing else to tell "no more
    chunks" from "the network blinked", since the server answers 404 for a chunk that does not exist
    and does not answer at all when a request is dropped."""
    return c.json_eval(f"""(async () => {{
      const out = {{game: null, chunks: {{}}}};
      const sleep = (ms) => new Promise(r => setTimeout(r, ms));
      const get = async (url) => {{
        for (let attempt = 0; attempt < 2; attempt++) {{
          try {{ return await fetch(url, {{credentials: 'include'}}); }}
          catch (e) {{ if (attempt) return null; await sleep(250); }}
        }}
        return null;
      }};
      const g = await get('{API}/game/{gid}');
      if (g && g.ok) out.game = await g.text();
      for (let t = 100; t <= 3000; t += 100) {{
        const r = await get('{API}/game/{gid}/log/' + t);
        if (!r || !r.ok) break;
        const body = await r.text();
        let j; try {{ j = JSON.parse(body); }} catch (e) {{ break; }}
        if (!Object.keys(j).some(k => /^[0-9]+$/.test(k))) break;
        out.chunks[t] = body;
      }}
      return JSON.stringify(out);
    }})()""")


def store_game(gid, game_body, chunks, store=STORE):
    """Write one match's documents into the store; returns the number of chunks written."""
    d = os.path.join(store, gid)
    os.makedirs(d, exist_ok=True)
    if game_body:
        with open(os.path.join(d, "game.json"), "w", encoding="utf-8") as f:
            f.write(game_body)
    for t, body in chunks.items():
        with open(os.path.join(d, f"log-{int(t)}.json"), "w", encoding="utf-8") as f:
            f.write(body)
    return len(chunks)


def fetch_into_store(c, gid, store=STORE, refresh=False):
    """Fetch one match unless the store already has its document and log (or refresh); returns (chunks, skipped)."""
    d = os.path.join(store, gid)
    if not refresh and os.path.isfile(os.path.join(d, "game.json")) and any(n.startswith("log-") for n in os.listdir(d)):
        return 0, True
    doc = fetch_game(c, gid)
    if not doc.get("game") and not doc.get("chunks"):
        return 0, False
    return store_game(gid, doc.get("game"), doc.get("chunks") or {}, store), False


HISTORY_PAGE = 50  # the endpoint answers at most this many rows, whatever `limit` asks for


def history_ids(c, arena_id, limit):
    """The arena's last `limit` rating matches, newest first — ids only.

    Paged with `offset`, because the endpoint caps a page at HISTORY_PAGE however large `limit` is:
    without the paging `--history 150` silently returned fifty, and everything older simply looked as
    though it had never been played."""
    out = []
    for offset in range(0, int(limit), HISTORY_PAGE):
        page = c.json_eval(f"""(async () => {{
          const sleep = (ms) => new Promise(r => setTimeout(r, ms));
          let j = null;
          for (let attempt = 0; attempt < 2 && !j; attempt++) {{
            try {{
              const r = await fetch('{API}/arena/{arena_id}/rating-history?limit={HISTORY_PAGE}&offset={offset}', {{credentials: 'include'}});
              j = await r.json();
            }} catch (e) {{ await sleep(250); }}
          }}
          return JSON.stringify(((j || {{}}).history || []).map(h => h.game?._id || h.game || h._id));
        }})()""")
        page = [g for g in (page or []) if isinstance(g, str)]
        out += page
        if len(page) < HISTORY_PAGE:
            break
    return out[:int(limit)]


def cmd_fetch(args):
    play = _play()
    c = play.CDP()
    ids = list(args.games)
    if args.history:
        if not args.arena:
            sys.exit("fetch --history needs --arena <name prefix>")
        arena = play.pick(c, args.arena)
        ids += [g for g in history_ids(c, arena["id"], args.history) if isinstance(g, str)]
    if not ids:
        sys.exit("nothing to fetch: give game ids or --history N --arena <name>")
    fetched = skipped = missing = 0
    for gid in ids:
        n, was = fetch_into_store(c, gid, refresh=args.refresh)
        if was:
            skipped += 1
        elif n == 0 and not os.path.isfile(os.path.join(STORE, gid, "game.json")):
            missing += 1
            print(f"{gid}: nothing came back")
        else:
            fetched += 1
            print(f"{gid}: {n} chunks")
    print(f"fetched {fetched}, already there {skipped}, missing {missing} -> {STORE}")


# ---------------------------------------------------------------- commands
def cmd_list(args):
    logs, metas = scan()
    games = sorted(set(logs) | set(metas))
    rows = [describe(g, logs, metas) for g in games]
    if args.arena:
        rows = [r for r in rows if args.arena in r["arena"]]
    rows.sort(key=lambda r: r["when"])
    if not args.all:
        rows = rows[-args.limit:]
    print(f"{len(rows)} matches (store holds {len(games)})")
    for r in rows:
        when = time.strftime('%d.%m %H:%M', time.localtime(r["when"]))
        ver = f"v{r['version']}" if r['version'] is not None else "-"
        print(f"{when}  {r['game']}  {r['arena']:<22} {ver:<5} ticks={r['last']:<5} "
              f"{r['result']:<6} {r['rating']:<10} vs {r['opponent'] or ', '.join(u for u in r['users'] if u)}")


def cmd_dump(args):
    logs, metas = scan()
    hits = [g for g in logs if g.startswith(args.game)]
    if len(hits) != 1:
        sys.exit(f"{'no' if not hits else len(hits)} matches for id prefix {args.game!r}")
    game = hits[0]
    info = describe(game, logs, metas)
    head = (f"# {game} {info['arena']} {time.strftime('%d.%m.%Y %H:%M', time.localtime(info['when']))} "
            f"{info['result']} rating {info['rating']} vs {', '.join(u for u in info['users'] if u)}\n")
    out = head + full_log(game, logs)
    if args.out:
        open(args.out, 'w', encoding='utf-8').write(out)
        print(f"{args.out}: {len(out)} bytes, {info['chunks']} chunks, last tick {info['last']}")
    else:
        sys.stdout.write(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("fetch", help="fetch matches from the server through the running client into the store")
    p.add_argument("games", nargs="*", help="game ids (24 hex)")
    p.add_argument("--history", type=int, metavar="N", help="also the arena's last N rating matches")
    p.add_argument("--arena", help="arena name prefix for --history, e.g. pain-and-gain")
    p.add_argument("--refresh", action="store_true", help="fetch again even if the store has the match")
    p.set_defaults(func=cmd_fetch)
    p = sub.add_parser("list", help="list stored matches, newest last")
    p.add_argument("--arena", help="substring of the arena name, e.g. spawn-and-swamp")
    p.add_argument("--limit", type=int, default=20)
    p.add_argument("--all", action="store_true")
    p.set_defaults(func=cmd_list)
    p = sub.add_parser("dump", help="print one match's full console log")
    p.add_argument("game", help="game id or a unique prefix of it")
    p.add_argument("--out", help="write to this file instead of stdout")
    p.set_defaults(func=cmd_dump)
    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
