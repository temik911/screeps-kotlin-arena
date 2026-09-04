#!/usr/bin/env python3
"""Play live Arena matches for one arena and bring their console logs back.

Each session starts its own matches: the payload is a zip of that arena's client script folder,
whose `node_modules` symlink points at the session's own worktree build, and the match is started
by POSTing to the API from inside the running client (see `arena_cdp.py` for why that is the only
way in). Nothing here navigates the client's UI, so several sessions can play at the same time —
the client itself runs up to three series at once and the server keeps one match slot per arena.

    tools/play.py --list                                   # arenas, ids, folders, free slots
    tools/play.py spawn-and-swamp                          # one rating match, wait, print result
    tools/play.py spawn-and-swamp -n 20 --stop-on-defeat    # a series, stopping at the first loss
    tools/play.py spawn-and-swamp -n 5 --logs runs/         # keep every match's console

Rating matches move your rating; that is the point of playing them. Landing first is NOT required
(rule 5 in CLAUDE.md): what a match needs is that the code being played is committed on your branch
and that the bot prints its version in the first log line, so the log can be tied back to a commit.
"""
import argparse, base64, io, json, os, sys, time, uuid, zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from arena_cdp import CDP

API = "https://arena.screeps.com/api"
CLIENT_ROOT = os.path.expanduser("~/ScreepsArena")
IGNORE_DIRS = {"typings"}          # what the client itself leaves out of the upload
IGNORE_ROOT_FILES = {"jsconfig.json"}
# The payload lives on the page under a name of our own: the client has ONE page for every session, and a
# shared `window.__code` is something two sessions overwrite for each other. That is not hypothetical — on
# 05.09.2026 the seventh match of a Spawn and Swamp series was played by the Escort Run bot (its greeting is
# in the match log) because the other session pushed its zip between our start calls; the match, and 14
# rating points, were lost to it.
SLOT = "__code_" + uuid.uuid4().hex[:8]


# ---------------------------------------------------------------- arenas and folders
def arenas(c):
    """The season's arenas, straight from the API — ids change every season, so never hardcode."""
    return c.json_eval(f"""(async () => {{
      const s = await (await fetch('{API}/season/current', {{credentials: 'include'}})).json();
      const id = s.season?._id || s._id;
      const a = await (await fetch('{API}/season/' + id + '/arenas', {{credentials: 'include'}})).json();
      return JSON.stringify((a.arenas || a.list || []).map(x => ({{id: x._id, name: x.name, unlocked: !!x.unlocked}})));
    }})()""")


def slug(name):
    return name.lower().replace(" ", "-")


def folder_for(name):
    """The client's script folder for an arena: ~/ScreepsArena/<season>-<mode>, matched by name."""
    want = name.lower().replace(" ", "_")
    hits = [d for d in sorted(os.listdir(CLIENT_ROOT))
            if d.endswith(want) and os.path.isdir(os.path.join(CLIENT_ROOT, d))]
    if not hits:
        raise SystemExit(f"no client folder for {name!r} under {CLIENT_ROOT}")
    return os.path.join(CLIENT_ROOT, hits[-1])


def pick(c, wanted):
    found = [a for a in arenas(c) if slug(a["name"]).startswith(wanted.lower()) and a["unlocked"]]
    if len(found) != 1:
        names = ", ".join(sorted({slug(a["name"]) for a in arenas(c) if a["unlocked"]}))
        raise SystemExit(f"{'no' if not found else len(found)} unlocked arenas match {wanted!r}; have: {names}")
    return found[0]


# ---------------------------------------------------------------- code payload
def build_zip(folder):
    buf, n = io.BytesIO(), 0
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for dirpath, _dirs, files in os.walk(folder, followlinks=True):
            rel = os.path.relpath(dirpath, folder)
            if rel != "." and IGNORE_DIRS & set(rel.split(os.sep)):
                continue
            for f in files:
                if rel == "." and f in IGNORE_ROOT_FILES:
                    continue
                arc = f if rel == "." else os.path.join(rel, f).replace(os.sep, "/")
                try:
                    z.write(os.path.join(dirpath, f), arc)
                    n += 1
                except OSError:
                    pass
    data = buf.getvalue()
    if not any(i.filename == "main.mjs" for i in zipfile.ZipFile(io.BytesIO(data)).infolist()):
        raise SystemExit(f"{folder} has no main.mjs — the client folder is not wired to a build")
    return data, n


def push_zip(c, data):
    slot, parts = json.dumps(SLOT), json.dumps(SLOT + "_parts")
    b64 = base64.b64encode(data).decode()
    c.eval(f"window[{parts}] = []; 1")
    for i in range(0, len(b64), 400_000):
        c.eval(f"window[{parts}].push({json.dumps(b64[i:i + 400_000])}); 1")
    return c.eval(f"""(() => {{
      const bin = atob(window[{parts}].join('')); delete window[{parts}];
      const arr = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
      window[{slot}] = new Blob([arr], {{type: 'application/zip'}});
      return window[{slot}].size;
    }})()""")


def payload_size(c):
    """Our payload's size on the page, 0 if it is gone — the page was reloaded, or someone cleaned up."""
    return c.eval(f"(window[{json.dumps(SLOT)}] && window[{json.dumps(SLOT)}].size) || 0")


# ---------------------------------------------------------------- matches
def slot(c, arena_id):
    return c.json_eval(f"""(async () => {{
      const r = await fetch('{API}/arena/{arena_id}/current-game', {{credentials: 'include'}});
      const j = await r.json();
      return JSON.stringify({{game: j.game ? j.game._id : null, status: j.game ? j.game.status : null,
                              allow: !!j.allowRunGames}});
    }})()""")


def start(c, arena_id):
    return c.json_eval(f"""(async () => {{
      const fd = new FormData();
      fd.append('arena', {json.dumps(arena_id)});
      fd.append('code', window[{json.dumps(SLOT)}], 'code.zip');
      const r = await fetch('{API}/game/start', {{method: 'POST', body: fd, credentials: 'include'}});
      let id = null, err = null;
      try {{ const j = await r.json(); id = (j.game && j.game._id) || j.game || null; err = j.error || null; }}
      catch (e) {{ err = String(e); }}
      return JSON.stringify({{status: r.status, id, err}});
    }})()""")


def state(c, gid):
    return c.json_eval(f"""(async () => {{
      const r = await fetch('{API}/game/{gid}', {{credentials: 'include'}});
      const j = await r.json(); const g = j.game || {{}};
      return JSON.stringify({{status: g.game?.status, winner: g.game?.result?.winner,
                              rating: g.ratingHistory, users: (g.users || []).map(u => u.username),
                              codes: (g.codes || []).map(x => x.user), me: g.user}});
    }})()""")


def outcome(s):
    """won / lost / draw, decided by the winning code's owner — not by the rating delta."""
    if s.get("status") != "finished":
        return s.get("status") or "?"
    w = s.get("winner")
    if w == 0.5:
        return "draw"
    codes, me = s.get("codes") or [], s.get("me")
    if isinstance(w, int) and 0 <= w < len(codes):
        return "won" if codes[w] == me else "lost"
    return f"winner={w}"


def wait(c, gid, timeout=1800):
    deadline = time.time() + timeout
    while time.time() < deadline:
        s = state(c, gid)
        if s.get("status") == "finished":
            return s
        time.sleep(10)
    return {"status": "timeout"}


def dump_log(c, gid, path):
    data = c.json_eval(f"""(async () => {{
      const out = {{}};
      for (let t = 100; t <= 3000; t += 100) {{
        const r = await fetch('{API}/game/{gid}/log/' + t, {{credentials: 'include'}});
        if (!r.ok) break;
        const j = await r.json();
        const ks = Object.keys(j).filter(k => /^[0-9]+$/.test(k));
        if (!ks.length) break;
        for (const k of ks) out[k] = j[k];
      }}
      return JSON.stringify(out);
    }})()""")
    ticks = sorted(int(k) for k in data)
    with open(path, "w", encoding="utf-8") as f:
        for t in ticks:
            line = data[str(t)].rstrip("\n")
            if line:
                f.write(line + "\n")
    return len(ticks)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("arena", nargs="?", help="arena name prefix, e.g. spawn-and-swamp")
    ap.add_argument("-n", "--count", type=int, default=1, help="how many matches (default 1)")
    ap.add_argument("--stop-on-defeat", action="store_true", help="stop the series at the first loss")
    ap.add_argument("--stop-on-non-win", action="store_true", help="stop at the first loss or draw")
    ap.add_argument("--logs", metavar="DIR", help="write each match's console into this directory")
    ap.add_argument("--list", action="store_true", help="list arenas with their ids, folders and slots")
    a = ap.parse_args()

    c = CDP()
    if a.list or not a.arena:
        for arena in arenas(c):
            if not arena["unlocked"]:
                continue
            s = slot(c, arena["id"])
            try:
                folder = folder_for(arena["name"])
            except SystemExit as e:
                folder = f"({e})"
            busy = f"{s['status']} {s['game']}" if s["game"] else "idle"
            print(f"{slug(arena['name']):<18} {arena['id']}  {busy:<26} {folder}")
        return

    arena = pick(c, a.arena)
    folder = folder_for(arena["name"])
    s = slot(c, arena["id"])
    if s["game"] and s["status"] != "finished":
        raise SystemExit(f"{arena['name']}: a match is already running ({s['game']})")
    if not s["allow"]:
        raise SystemExit(f"{arena['name']}: the server is not accepting matches right now")

    data, files = build_zip(folder)
    size = push_zip(c, data)
    print(f"{arena['name']} ({arena['id']}) from {folder}: {files} files, "
          f"{len(data)/1024/1024:.2f} MB zipped, blob {size} bytes")
    if a.logs:
        os.makedirs(a.logs, exist_ok=True)

    tally = {"won": 0, "lost": 0, "draw": 0}
    for i in range(1, a.count + 1):
        if payload_size(c) != size:            # the page was reloaded, or the payload was overwritten
            print(f"{i}/{a.count}: the payload is not on the page any more, sending it again")
            size = push_zip(c, data)
        r = start(c, arena["id"])
        if r["status"] not in (200, 201) or not r.get("id"):
            print(f"{i}/{a.count}: start failed ({r['status']} {r.get('err')})")
            break
        gid = r["id"]
        st = wait(c, gid)
        res = outcome(st)
        tally[res] = tally.get(res, 0) + 1
        rating = st.get("rating") or {}
        foes = ", ".join(u for u in st.get("users", []) if u)
        line = (f"{i}/{a.count} {gid} {res:<6} "
                f"rating {rating.get('previousRating')}->{rating.get('rating')} "
                f"rank {rating.get('rank')} vs {foes}")
        if a.logs:
            path = os.path.join(a.logs, f"{time.strftime('%m%d-%H%M')}-{res}-{gid[-6:]}.txt")
            line += f" | log {dump_log(c, gid, path)} ticks -> {path}"
            # our bots name themselves on the first line; another name means the wrong payload was played
            greeting = open(path, encoding='utf-8').readline().strip()
            if greeting.startswith("hello") and slug(arena["name"]) not in greeting:
                line += f"\n  !! WRONG BOT PLAYED: {greeting[:80]}"
        print(line, flush=True)
        if (a.stop_on_defeat and res == "lost") or (a.stop_on_non_win and res != "won"):
            print(f"stopping at {res}")
            break
    print("total: " + ", ".join(f"{k} {v}" for k, v in tally.items() if v))
    c.eval(f"delete window[{json.dumps(SLOT)}]; 1")
    c.close()


if __name__ == "__main__":
    main()
