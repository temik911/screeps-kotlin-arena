#!/usr/bin/env python3
"""Read live-match console logs out of the Arena client's HTTP cache.

The client fetches every match's console from `arena.screeps.com/api/game/<id>/log/<tick>` in
100-tick chunks and Chromium keeps those responses in its disk cache, gzipped, together with
`/api/game/<id>` (players, result, rating). Nothing else on this machine stores match logs: the
client writes no log files, and its Local Storage keeps only "<match>_viewed" flags. So this is
where a finished match's console lives — no need to copy it out of the client window by hand,
and the tail the window scrolled past is here too.

    tools/match-log.py list [--arena spawn-and-swamp] [--limit 20] [--all]
    tools/match-log.py dump <game-id-prefix> [--out log.txt]

Caveats worth knowing before you trust a line of it: the cache is a cache. Chromium evicts by
size (~1 GB here), so old matches disappear, and a chunk can be missing from the middle of a
match — `dump` marks such a gap instead of silently closing it. A match is written to the cache
only when the client actually displayed it, so a match watched on another machine is not here.
"""
import argparse, gzip, json, os, re, sys, time, zlib
from collections import defaultdict

CACHE = os.path.expanduser("~/Library/Application Support/screeps_arena/Cache/Cache_Data")
LOG_URL = re.compile(rb"https://arena\.screeps\.com/api/game/([0-9a-f]{24})/log/(\d+)")
GAME_URL = re.compile(rb"https://arena\.screeps\.com/api/game/([0-9a-f]{24})(?:[^/\x21-\x7e]|$)")
GREETING = re.compile(r"hello (\w+) ([\w-]+)")


def decompress(path):
    """The response body of a cached entry, or b'' — the entry holds headers, then a gzip stream."""
    try:
        raw = open(path, 'rb').read()
    except OSError:
        return b""
    i = raw.find(b"\x1f\x8b")
    if i < 0:
        return b""
    try:
        return gzip.decompress(raw[i:])
    except Exception:
        try:  # a truncated or trailing-garbage stream still yields its complete prefix
            return zlib.decompressobj(16 + zlib.MAX_WBITS).decompress(raw[i:])
        except Exception:
            return b""


def scan(cache=CACHE):
    """(logs, metas): game id -> {tick: path} and game id -> path, from the client's cache."""
    logs, metas = defaultdict(dict), {}
    if not os.path.isdir(cache):
        sys.exit(f"no Arena client cache at {cache}")
    for name in os.listdir(cache):
        path = os.path.join(cache, name)
        if not os.path.isfile(path):
            continue
        try:
            with open(path, 'rb') as f:
                head = f.read(1024)
        except OSError:
            continue
        m = LOG_URL.search(head)
        if m:
            logs[m.group(1).decode()][int(m.group(2))] = path
            continue
        m = GAME_URL.search(head)
        if m:
            metas[m.group(1).decode()] = path
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
    body = decompress(path)
    if not body:
        return None
    try:
        return json.loads(body.decode('utf-8', 'replace')).get("game")
    except ValueError:
        return None


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
    if win is None:
        return "draw"
    if isinstance(win, int) and 0 <= win < len(codes):
        return "won" if codes[win].get("user") == me else "lost"
    return str(win)


def describe(game, logs, metas):
    chunks = logs.get(game, {})
    first = chunk_text(chunks[min(chunks)]) if chunks else {}
    greet = ""
    if first:
        m = GREETING.search(first[min(first)][:200])
        greet = f"{m.group(1)}/{m.group(2)}" if m else ""
    meta = meta_of(metas[game]) if game in metas else None
    when = max((os.stat(p).st_mtime for p in chunks.values()), default=0)
    rating = ""
    if meta and meta.get("ratingHistory"):
        r = meta["ratingHistory"]
        rating = f"{r.get('previousRating')}->{r.get('rating')}"
    return dict(game=game, arena=greet, when=when, chunks=len(chunks),
                last=max(chunks) if chunks else 0, result=outcome(meta), rating=rating,
                users=[u.get("username") for u in (meta or {}).get("users", [])])


def cmd_list(args):
    logs, metas = scan()
    rows = [describe(g, logs, metas) for g in logs]
    if args.arena:
        rows = [r for r in rows if args.arena in r["arena"]]
    rows.sort(key=lambda r: r["when"])
    if not args.all:
        rows = rows[-args.limit:]
    print(f"{len(rows)} matches (cache holds {len(logs)})")
    for r in rows:
        when = time.strftime('%d.%m %H:%M', time.localtime(r["when"]))
        foes = ", ".join(u for u in r["users"] if u)
        print(f"{when}  {r['game']}  {r['arena']:<22} ticks={r['last']:<5} "
              f"{r['result']:<6} {r['rating']:<10} {foes}")


def cmd_dump(args):
    logs, metas = scan()
    hits = [g for g in logs if g.startswith(args.game)]
    if len(hits) != 1:
        sys.exit(f"{'no' if not hits else len(hits)} matches for id prefix {args.game!r}")
    game = hits[0]
    info = describe(game, logs, metas)
    lines = [f"# {game} {info['arena']} {time.strftime('%d.%m.%Y %H:%M', time.localtime(info['when']))} "
             f"{info['result']} rating {info['rating']} vs {', '.join(u for u in info['users'] if u)}"]
    ticks, expected = {}, sorted(logs[game])
    for t in expected:
        ticks.update(chunk_text(logs[game][t]))
    # a chunk covers the 100 ticks ending at its key; a hole means the client never fetched it
    for prev, cur in zip([0] + expected, expected):
        if cur - prev > 100:
            lines.append(f"# --- ticks {prev + 1}..{cur - 100} are not in the cache ---")
    for t in sorted(ticks):
        text = ticks[t].rstrip("\n")
        if text:
            lines.append(text)
    out = "\n".join(lines) + "\n"
    if args.out:
        open(args.out, 'w', encoding='utf-8').write(out)
        print(f"{args.out}: {len(out)} bytes, ticks {min(ticks, default=0)}..{max(ticks, default=0)}")
    else:
        sys.stdout.write(out)


ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
sub = ap.add_subparsers(dest="cmd", required=True)
p = sub.add_parser("list", help="list cached matches, newest last")
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
