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
    tools/play.py pain-and-gain --history 20                # the arena's last matches FROM THE SERVER: id, opponent, result, rating
    tools/play.py pain-and-gain --test-list                 # who can be played UNRATED: system, recent opponents, favorites
    tools/play.py pain-and-gain --test 'MetalicaX#10' -n 5  # five UNRATED games against that one bot of his
    tools/play.py pain-and-gain --ab pain-and-gain-v449 pain-and-gain-v450 --test 'MetalicaX#10' -n 8   # a live A/B, one command
    tools/play.py pain-and-gain --ab <refA> <refB> --dry    # the same machinery without the client and without a game

`--history` reads results from the server (`/api/arena/<id>/rating-history` has every rating match); the match documents themselves go into the store `tools/match-log.py` keeps (`~/ScreepsArena/games/<id>/`) as each match ends — nothing is read from the client's cache. What the server does not know is which build played
a match: that is the bot's greeting line in the console, so `--history` shows the code version the server assigned (a
counter per upload) and the console's greeting comes from `match-log.py` or the replay.

Rating matches move your rating; that is the point of playing them. Landing first is NOT required
(rule 5 in CLAUDE.md): what a match needs is that the code being played is committed on your branch
and that the bot prints its version in the first log line, so the log can be tied back to a commit.

`--test` plays the arena's TEST games instead: `/test/start` takes the same payload plus a `codeId`, so the match is
against ONE chosen bot of his and moves NO rating (measured 08.09.2026: a game against MetalicaX#10 left the rating at
1183, `ratingHistory` empty). `--test-list` prints who is available — the `system` bot, `recent` (whoever the last
rating games were against, each with the code version it played) and `favorites`, which are pinned in the client. Test
matches land in the same store, so `tools/autopsy.py`, `series.py` and `replay.py` read them like any other; they are
the way to price a change against a specific opponent without paying rating for it.

`--ab <refA> <refB>` is a live A/B in one command (20.09.2026; before it: two builds and a series by hand, three to four
hours of attention). Each ref — a tag, a branch, a commit — gets a temporary worktree (`git worktree add --detach` under
`.claude/worktrees/ab-<sha>`) and its own `./gradlew build`; the payload is the arena's client folder with its
`node_modules/screeps-kotlin-arena-starter` taken from THAT worktree's build instead of the symlink, so the session's own
worktree is not touched and can keep working. The hands alternate A, B, A, B… against ONE bot (`--test`, unrated; `-n` is
hands PER SIDE), because the only control over a drifting opponent pool and a warming client is interleaving. At the end
the games of each side go to `tools/series.py compare / shares / reach` through `--relabel` — by game id, not by the
greeting's version, so two commits of one version, or a tag against itself, can be compared. `--dry` runs the same
machinery with no client and no game: both worktrees, both builds, both payloads (their content digests are printed — two
identical refs must give identical payloads), and each side's hand is the arena's own stub gate
(`tools/stub/<arena>/regress.sh`, clock off); the two reports and, where the arena has a `logdiff.py`, the two log sets
must not differ. That is the self-test: an A/B of a ref against itself that shows a difference is measuring the machinery.
"""
import argparse, importlib.util, base64, io, json, os, sys, time, uuid, zipfile

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


# Запрос со страницы клиента срывается сам по себе: седьмой fetch подряд бросает
# `TypeError: Failed to fetch`, а тот же адрес в одиночку отвечает 200. Непойманный бросок отклоняет
# весь промис, и команда падает целиком — поэтому каждый GET здесь идёт через один повтор.
JS_GET = """
      const sleep = (ms) => new Promise(r => setTimeout(r, ms));
      const GET = async (url, init) => {
        for (let attempt = 0; attempt < 2; attempt++) {
          try { return await fetch(url, Object.assign({credentials: 'include'}, init || {})); }
          catch (e) { if (attempt) throw e; await sleep(250); }
        }
      };
"""


# ---------------------------------------------------------------- arenas and folders
def arenas(c):
    """The season's arenas, straight from the API — ids change every season, so never hardcode."""
    return c.json_eval(f"""(async () => {{
      {JS_GET}
      const s = await (await GET('{API}/season/current')).json();
      const id = s.season?._id || s._id;
      const a = await (await GET('{API}/season/' + id + '/arenas')).json();
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


def build_zip_from(folder, starter):
    """The client folder's payload with `node_modules/screeps-kotlin-arena-starter` taken from `starter` (a worktree's
    build) instead of whatever the folder's symlink points at — so one client folder serves both sides of an A/B."""
    pkg = "node_modules/screeps-kotlin-arena-starter"
    buf, n = io.BytesIO(), 0
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for dirpath, dirs, files in os.walk(folder, followlinks=False):
            rel = os.path.relpath(dirpath, folder)
            dirs[:] = [d for d in dirs if not (rel == "node_modules" and d == "screeps-kotlin-arena-starter")]
            if rel != "." and IGNORE_DIRS & set(rel.split(os.sep)):
                continue
            for f in files:
                if rel == "." and f in IGNORE_ROOT_FILES:
                    continue
                full = os.path.join(dirpath, f)
                if os.path.islink(full) and os.path.isdir(full):
                    continue
                z.write(full, f if rel == "." else os.path.join(rel, f).replace(os.sep, "/")); n += 1
        for dirpath, _dirs, files in os.walk(starter, followlinks=True):
            rel = os.path.relpath(dirpath, starter)
            for f in files:
                arc = pkg + "/" + (f if rel == "." else os.path.join(rel, f).replace(os.sep, "/"))
                try:
                    z.write(os.path.join(dirpath, f), arc); n += 1
                except OSError:
                    pass
    data = buf.getvalue()
    names = [i.filename for i in zipfile.ZipFile(io.BytesIO(data)).infolist()]
    if "main.mjs" not in names or not any(x.startswith(pkg + "/") for x in names):
        raise SystemExit(f"payload from {folder} + {starter} has no main.mjs or no starter package — is the worktree built?")
    return data, n


def payload_digest(data, worktree):
    """A digest of the payload's CONTENT (names and bytes, not zip timestamps): equal refs must give equal digests. The
    path of the worktree that built it is not content — `SourceMapRegistry.mjs` carries it as `sourceRoot`, the `.map`
    files as source paths — so it is replaced by a placeholder before hashing (measured on the first dry run: that one
    line was the whole difference between two builds of one commit)."""
    import hashlib
    h = hashlib.sha256()
    z = zipfile.ZipFile(io.BytesIO(data))
    root = os.path.realpath(worktree).encode()
    for name in sorted(z.namelist()):
        h.update(name.encode()); h.update(b"\0")
        h.update(z.read(name).replace(root, b"<worktree>").replace(worktree.encode(), b"<worktree>")); h.update(b"\0")
    return h.hexdigest()[:16]


# ---------------------------------------------------------------- A/B: two refs, two worktrees, alternating hands
def _git(*args, cwd=None):
    import subprocess
    r = subprocess.run(["git", *args], capture_output=True, text=True, cwd=cwd)
    if r.returncode:
        raise SystemExit(f"git {' '.join(args)}: {r.stderr.strip()}")
    return r.stdout.strip()


def ab_worktree(ref, label):
    """A detached temporary worktree of `ref`, built. Reused when it is already there at the same commit. The side's label
    is part of the path: a ref played against itself still gets TWO worktrees and two builds — otherwise the self-test
    would compare one directory with itself."""
    import subprocess
    top = _git("rev-parse", "--show-toplevel")
    common = os.path.dirname(os.path.abspath(os.path.join(top, _git("rev-parse", "--git-common-dir"))))
    sha = _git("rev-parse", "--verify", ref + "^{commit}")
    wt = os.path.join(common, ".claude", "worktrees", f"ab-{label}-{sha[:10]}")
    if not os.path.isdir(wt):
        _git("worktree", "add", "--detach", wt, sha)
        print(f"ab: worktree {wt} at {ref} ({sha[:10]})", flush=True)
    elif _git("rev-parse", "HEAD", cwd=wt) != sha:
        raise SystemExit(f"ab: {wt} exists at another commit — remove it with `git worktree remove --force {wt}`")
    print(f"ab: building {ref} in {wt}", flush=True)
    r = subprocess.run(["./gradlew", "build", "-q"], cwd=wt, capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(f"ab: build of {ref} failed:\n{(r.stdout + r.stderr)[-2000:]}")
    starter = os.path.join(wt, "build", "js", "packages", "screeps-kotlin-arena-starter")
    if not os.path.isdir(starter):
        raise SystemExit(f"ab: {starter} is missing after the build")
    # The registry module is written by a task that FINALIZES the compile (`generateSourceMapRegistry`), and the first build
    # of a fresh worktree has come out without it (CLAUDE.md: the transient ERR_MODULE_NOT_FOUND on SourceMapRegistry.mjs;
    # 20.09.2026: both A/B payloads two files short, every hand silent). Every arena's loop() imports it through
    # runWithSourceMapSupport, so a package without it loads nothing. A second build is the documented remedy.
    registry = os.path.join(starter, "kotlin", "screeps-kotlin-arena-starter", "sourcemaps", "SourceMapRegistry.mjs")
    if not os.path.isfile(registry):
        print(f"ab: {ref}: SourceMapRegistry.mjs missing after the build — building again", flush=True)
        r = subprocess.run(["./gradlew", "build", "-q"], cwd=wt, capture_output=True, text=True)
        if r.returncode or not os.path.isfile(registry):
            raise SystemExit(f"ab: build of {ref} leaves no SourceMapRegistry.mjs — the payload would load nothing")
    exports = [f for _d, _s, fs in os.walk(starter) for f in fs if f.endswith(".export.mjs")]
    if not exports:
        raise SystemExit(f"ab: build of {ref} has no *.export.mjs — nothing for a main.mjs to import")
    return wt, starter, sha


def ab_cleanup(wts):
    for wt in wts:
        try:
            _git("worktree", "remove", "--force", wt)
            print(f"ab: removed {wt}")
        except SystemExit as e:
            print(f"ab: could not remove {wt}: {e}")


def ab_dry(arena_slug, sides):
    """The A/B without the client: each side's hand is its own stub gate with the clock off; nothing may differ between
    two identical refs. Returns the number of differences found (report lines + logdiff's verdict)."""
    import subprocess
    stub = os.path.join("tools", "stub", arena_slug.replace("-", ""))
    reports, diffs = [], 0
    for label, (wt, _starter, _sha, ref) in sides.items():
        sh = os.path.join(wt, stub, "regress.sh")
        if not os.path.isfile(sh):
            raise SystemExit(f"ab --dry: {ref} has no {stub}/regress.sh — this arena has no stub to play the dry hand on")
        print(f"ab --dry: hand {label} ({ref}) — {stub}/regress.sh land, clock off", flush=True)
        for f in os.listdir(os.path.join(wt, stub, "out")) if os.path.isdir(os.path.join(wt, stub, "out")) else []:
            if f.startswith("run-land-"):
                os.remove(os.path.join(wt, stub, "out", f))
        env = dict(os.environ, NOCLOCK="1"); env.pop("ONLY", None)
        r = subprocess.run(["zsh", sh, "land"], cwd=wt, capture_output=True, text=True, env=env)
        lines = [l for l in r.stdout.split("\n") if l.strip()]
        bad = [l for l in lines if not ("PASS" in l or "ENEMY SPAWN DESTROYED" in l) or "errors: 0 " not in l]
        print(f"ab --dry: hand {label}: {len(lines)} lines, not PASS {len(bad)}")
        reports.append(lines)
    a, b = reports
    changed = [(x, y) for x, y in zip(a, b) if x != y]
    diffs += len(changed) + abs(len(a) - len(b))
    print(f"ab --dry: gate reports — {len(a)} against {len(b)} lines, differing {len(changed)}")
    for x, y in changed[:5]:
        print(f"   A: {x}\n   B: {y}")
    (wa, *_), (wb, *_) = sides["A"], sides["B"]
    ld = os.path.join(wb, stub, "logdiff.py")
    if os.path.isfile(ld):
        r = subprocess.run([sys.executable, ld, "--old", os.path.join(wa, stub, "out"), "--new", os.path.join(wb, stub, "out")],
                           capture_output=True, text=True, cwd=wb)
        head = (r.stdout.strip().split("\n") or [""])
        print("ab --dry: instruments (logdiff A -> B) — " + head[0])
        for l in head[1:4]:
            print("   " + l)
        diffs += 0 if r.returncode == 0 else 1
    else:
        print(f"ab --dry: {stub} has no logdiff.py — the instruments are compared by the gate report only")
    return diffs


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
      {JS_GET}
      const r = await GET('{API}/arena/{arena_id}/current-game');
      const j = await r.json();
      return JSON.stringify({{game: j.game ? j.game._id : null, status: j.game ? j.game.status : null,
                              allow: !!j.allowRunGames}});
    }})()""")


def test_codes(c, arena_id):
    """Who can be played unrated here: the system bot, the recent opponents and the pinned favorites, each with the
    code id `/test/start` wants and the version that code is of his bot (a username is not a bot, the version is)."""
    return c.json_eval(f"""(async () => {{
      {JS_GET}
      const r = await GET('{API}/test/codes/{arena_id}');
      const j = await r.json();
      const pick = (rows, list) => (rows || []).map(x => ({{
        list, name: (x.user && x.user.username) || '?', version: (x.code && x.code.version) || null,
        codeId: (x.code && x.code._id) || null, note: x.note || ''}}));
      return JSON.stringify([].concat(pick(j.system, 'system'), pick(j.favorites, 'favorite'), pick(j.recent, 'recent')));
    }})()""")


def resolve_test(rows, spec):
    """`Name#version`, a bare `Name` (its newest listed code) or a raw code id -> one row of test_codes."""
    if any(r["codeId"] == spec for r in rows):
        return next(r for r in rows if r["codeId"] == spec)
    name, _, ver = spec.partition("#")
    hits = [r for r in rows if r["name"].lower().startswith(name.lower())]
    if ver:
        hits = [r for r in hits if str(r["version"]) == ver]
    if not hits:
        raise SystemExit(f"no test opponent matches {spec!r}; --test-list shows who is available")
    return sorted(hits, key=lambda r: -(r["version"] or 0))[0]


def start(c, arena_id, fame=False, code_id=None):
    """One game: a rating game through `/game/start`, or a game of today's fame session through `/fame/start` — the same
    form (the arena and the code zip); the client's FameSeriesAction adds an optional `modifierDefId`, an inventory item
    that multiplies the fame, which this never sends (spending the operator's items is the operator's click)."""
    ep = '/test/start' if code_id else '/fame/start' if fame else '/game/start'
    # a POST of the payload throws `TypeError: Failed to fetch` on the page more readily than a GET does — measured on
    # /test/start, where the first attempt failed and the second went through — so it retries like the GETs do
    return c.json_eval(f"""(async () => {{
      const fd = new FormData();
      fd.append('arena', {json.dumps(arena_id)});
      fd.append('code', window[{json.dumps(SLOT)}], 'code.zip');
      {'fd.append("codeId", ' + json.dumps(code_id) + ');' if code_id else ''}
      {JS_GET}
      let r = null, fetchErr = null;
      for (let attempt = 0; attempt < 4; attempt++) {{
        try {{ r = await fetch('{API}{ep}', {{method: 'POST', body: fd, credentials: 'include'}}); break; }}
        catch (e) {{ fetchErr = String(e); await sleep(600); }}
      }}
      if (!r) return JSON.stringify({{status: 0, id: null, err: fetchErr, fameId: null, raw: null}});
      let id = null, err = null, fameId = null, raw = null;
      try {{ const j = await r.json(); id = (j.game && j.game._id) || j.game || null; err = j.error || null;
             fameId = (j.fame && j.fame._id) || null; raw = JSON.stringify(j).slice(0, 400); }}
      catch (e) {{ err = String(e); }}
      return JSON.stringify({{status: r.status, id, err, fameId, raw}});
    }})()""")


def fame_state(c, arena_id):
    """Today's fame session of the arena as the server holds it (`/api/arena/<id>` → `fame`): `{}` before the first game,
    then the id, points, the rewards level, `finishedAt` once the operator has finished it (fame is daily: finishing
    leaves until tomorrow), and `qualifying` (fewer than five rating games — no fame yet)."""
    return c.json_eval(f"""(async () => {{
      {JS_GET}
      const r = await GET('{API}/arena/{arena_id}');
      const j = await r.json(); const a = j.arena || {{}}; const f = a.fame || {{}};
      return JSON.stringify({{id: f._id || null, points: f.points || 0, level: f.rewardsLevel || 0, finishedAt: f.finishedAt || null,
                              rewardedAt: f.rewardedAt || null, qualifying: !!a.qualifying, keys: Object.keys(f)}});
    }})()""")


def fame_post(c, arena_id, action):
    """`take` (the chest: rewards into the inventory, `rewardedAt` set) or `finish` (the session closed until tomorrow) on
    `/api/fame/<ARENA id>/<action>` — every fame endpoint is keyed by the arena, not the session (the client's fame service
    destructures `_id` from the arena; with the session id the server answers a TypeError) — the operator's two clicks at
    the end of a fame day, run only under --fame-collect."""
    return c.json_eval(f"""(async () => {{
      {JS_GET}
      const r = await GET('{API}/fame/{arena_id}/{action}', {{method: 'POST', body: '{{}}', headers: {{'Content-Type': 'application/json'}}}});
      const t = await r.text(); return JSON.stringify({{status: r.status, body: t.slice(0, 2000)}});
    }})()""")


def inventory(c):
    """The Steam inventory as the server lists it: {itemdefid: quantity}."""
    return c.json_eval(f"""(async () => {{
      {JS_GET}
      const r = await GET('{API}/inventory'); const j = await r.json(); const out = {{}};
      for (const it of (j.items || [])) out[it.itemdefid] = (out[it.itemdefid] || 0) + Number(it.quantity || 0);
      return JSON.stringify(out);
    }})()""")


def fame_games(c, arena_id):
    """Today's session's games (`/api/fame/<ARENA id>/games`), as the server lists them."""
    return c.json_eval(f"""(async () => {{
      {JS_GET}
      const r = await GET('{API}/fame/{arena_id}/games');
      const t = await r.text(); return JSON.stringify({{status: r.status, body: t.slice(0, 3000)}});
    }})()""")


def history(c, arena_id, limit, us):
    """The arena's last rating matches from the server (`/api/arena/<id>/rating-history`), newest first."""
    rows = c.json_eval(f"""(async () => {{
      {JS_GET}
      const r = await GET('{API}/arena/{arena_id}/rating-history?limit={int(limit)}&offset=0');
      const j = await r.json();
      return JSON.stringify((j.history || []).map(h => {{
        const g = h.game || {{}};
        return {{id: g._id || h._id, created: g.createdAt || h.createdAt, ticks: (g.meta && g.meta.ticks) || g.ticks || 0,
                winner: g.result ? g.result.winner : null, draw: !!(g.result && g.result.draw),
                users: (h.users || []).map(u => ({{id: u._id, name: u.username}})),
                codes: (h.codes || []).map(x => ({{user: x.user, version: x.version}})),
                rating: h.ratingHistory ? [h.ratingHistory.previousRating, h.ratingHistory.rating, h.ratingHistory.rank] : null}};
      }}));
    }})()""")
    out = []
    for h in rows:
        me = next((u["id"] for u in h["users"] if (u["name"] or "").startswith(us)), None)
        w = h["winner"]
        if h["draw"] or w is None or w == -1:
            res = "draw"
        elif isinstance(w, int) and 0 <= w < len(h["codes"]):
            res = "won" if h["codes"][w]["user"] == me else "lost"
        else:
            res = str(w)
        # the opponent's code version next to the name: a username is not a bot (07.09.2026)
        foes = ", ".join(f"{u['name']}#{next((c.get('version', '?') for c in h['codes'] if c['user'] == u['id']), '?')}" for u in h["users"] if u["id"] != me)   # a code entry without a version: the built-in System bot
        ver = next((c["version"] for c in h["codes"] if c["user"] == me), None)
        out.append(dict(id=h["id"], created=h["created"], ticks=h["ticks"], result=res, opponent=foes, rating=h["rating"], code=ver))
    return out


def state(c, gid):
    return c.json_eval(f"""(async () => {{
      {JS_GET}
      const r = await GET('{API}/game/{gid}');
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


def _match_log():
    spec = importlib.util.spec_from_file_location('match_log', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'match-log.py'))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def save_match(c, gid, path=None):
    """The match's server documents into the store (tools/match-log.py: `/api/game/<id>` and every log chunk), and
    the console as text into `path` when asked; returns the number of ticks that carried console output."""
    ml = _match_log()
    doc = ml.fetch_game(c, gid)
    chunks = doc.get("chunks") or {}
    ml.store_game(gid, doc.get("game"), chunks)
    data = {}
    for body in chunks.values():
        try:
            j = json.loads(body)
        except ValueError:
            continue
        for k, v in j.items():
            if k.isdigit() and isinstance(v, str):
                data[k] = v
    ticks = sorted(int(k) for k in data)
    # ...and ZERO when no tick carried output: a payload that failed to load leaves an empty string on every tick, and
    # "100 ticks stored" read as a played match — sixteen A/B hands on 20.09.2026 were empty that way
    spoke = 0
    if path:
        with open(path, "w", encoding="utf-8") as f:
            for t in ticks:
                line = data[str(t)].rstrip("\n")
                if line:
                    f.write(line + "\n"); spoke += 1
    else:
        spoke = sum(1 for t in ticks if data[str(t)].strip())
    return len(ticks) if spoke else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("arena", nargs="?", help="arena name prefix, e.g. spawn-and-swamp")
    ap.add_argument("-n", "--count", type=int, default=1, help="how many matches (default 1)")
    ap.add_argument("--stop-on-defeat", action="store_true", help="stop the series at the first loss")
    ap.add_argument("--stop-on-non-win", action="store_true", help="stop at the first loss or draw")
    ap.add_argument("--logs", metavar="DIR", help="write each match's console into this directory")
    ap.add_argument("--list", action="store_true", help="list arenas with their ids, folders and slots")
    ap.add_argument("--fame", action="store_true", help="play games of today's fame session (/fame/start) instead of rating games; never finishes the session")
    ap.add_argument("--fame-collect", action="store_true", help="end today's fame session: take the rewards, then finish it (the operator's two clicks; nothing is played)")
    ap.add_argument("--test", metavar="BOT", help="play UNRATED test games against one bot: 'Name#version', a bare name, or a code id")
    ap.add_argument("--test-list", action="store_true", help="list the bots available for unrated test games and exit")
    ap.add_argument("--history", type=int, metavar="N", help="print the arena's last N rating matches from the server and exit")
    ap.add_argument("--us", default="temik911", help="our username prefix (for --history)")
    ap.add_argument("--ab", nargs=2, metavar=("REF_A", "REF_B"), help="a live A/B of two refs (tags, branches, commits): temporary worktrees, "
                    "alternating hands against the --test bot, -n hands PER SIDE, then series.py compare / shares / reach")
    ap.add_argument("--dry", action="store_true", help="with --ab: no client and no game — both builds, both payloads, each side's hand is the arena's stub gate")
    ap.add_argument("--keep", action="store_true", help="with --ab: leave the temporary worktrees in place")
    a = ap.parse_args()

    if a.ab:
        if not a.arena:
            raise SystemExit("--ab needs the arena")
        if not a.dry and not a.test:
            raise SystemExit("--ab plays UNRATED hands against one bot: name it with --test (or use --dry)")
        sides = {}
        for label, ref in zip("AB", a.ab):
            wt, starter, sha = ab_worktree(ref, label)
            sides[label] = (wt, starter, sha, ref)
        # The folder is the arena's own, matched the way the series matches it (folder_for: the name as a SUFFIX). A substring
        # match stood here and took the LAST hit of the sorted list — for Pain and Gain that was `season4-pain_and_gain-advanced`,
        # the client's empty-loop template folder for the advanced arena: two A/B series on 20.09.2026 (17 hands) were played
        # by `export function loop() {}`, every hand lost at t=100 with an empty console.
        folder = None
        try:
            folder = folder_for(a.arena.replace("-", " "))
        except SystemExit:
            folder = None
        payloads = {}
        if folder:
            for label, (wt, starter, sha, ref) in sides.items():
                data, files = build_zip_from(folder, starter)
                payloads[label] = data
                print(f"ab: payload {label} ({ref}, {sha[:10]}): {files} files, {len(data)/1024/1024:.2f} MB, content {payload_digest(data, wt)}")
        elif not a.dry:
            raise SystemExit(f"no client folder for {a.arena!r} under {CLIENT_ROOT}")
        if a.dry:
            arena_slug = a.arena if "-" in a.arena else a.arena
            diffs = ab_dry(arena_slug, sides)
            if payloads and sides["A"][2] == sides["B"][2]:
                same = payload_digest(payloads["A"], sides["A"][0]) == payload_digest(payloads["B"], sides["B"][0])
                print(f"ab --dry: the two refs are one commit — payloads {'identical' if same else 'DIFFER'}")
                diffs += 0 if same else 1
            print(f"ab --dry: {'no difference between the sides' if not diffs else str(diffs) + ' DIFFERENCES between the sides'}")
            if not a.keep:
                ab_cleanup({v[0] for v in sides.values()})
            raise SystemExit(1 if diffs else 0)
        c = CDP()
        arena = pick(c, a.arena)
        if folder != folder_for(arena["name"]):
            raise SystemExit(f"ab: the payload folder {folder} is not the arena's own {folder_for(arena['name'])}")
        s = slot(c, arena["id"])
        if s["game"] and s["status"] != "finished":
            raise SystemExit(f"{arena['name']}: a match is already running ({s['game']})")
        row = resolve_test(test_codes(c, arena["id"]), a.test)
        who = f"{row['name']}#{row['version']}" if row["version"] else row["name"]
        print(f"ab: {a.count} hands per side against {who} (code {row['codeId']}) — unrated, alternating A, B, A, B…")
        if a.logs:
            os.makedirs(a.logs, exist_ok=True)
        games, tally = {}, {"A": {"won": 0, "lost": 0, "draw": 0}, "B": {"won": 0, "lost": 0, "draw": 0}}
        for i in range(1, a.count + 1):
            for label in "AB":
                push_zip(c, payloads[label])            # the page holds ONE payload of ours: every hand sends its own side's
                r = start(c, arena["id"], code_id=row["codeId"])
                if r["status"] not in (200, 201) or not r.get("id"):
                    print(f"ab {i}{label}: start failed ({r['status']} {r.get('err')}) {r.get('raw') or ''}")
                    break
                gid = r["id"]
                st = wait(c, gid)
                res = outcome(st)
                tally[label][res] = tally[label].get(res, 0) + 1
                games[gid] = 1 if label == "A" else 2
                path = os.path.join(a.logs, f"{time.strftime('%m%d-%H%M')}-{label}-{res}-{gid[-6:]}.txt") if a.logs else None
                n = save_match(c, gid, path)
                print(f"ab {i}/{a.count} {label} ({sides[label][3]}) {gid} {res:<6} | {n} ticks with output", flush=True)
                if n == 0:
                    # a hand whose console is empty was not played by the bot: the payload did not load (20.09.2026 — the
                    # registry module missing from a fresh worktree build, both sides lost every hand at t=100 with nothing
                    # printed). No measurement comes out of such a hand, so the series stops here rather than burn the rest
                    c.eval(f"delete window[{json.dumps(SLOT)}]; 1")
                    c.close()
                    if not a.keep:
                        ab_cleanup({v[0] for v in sides.values()})
                    raise SystemExit(f"ab: hand {i}{label} printed nothing — the payload of {sides[label][3]} did not load; stopped")
        c.eval(f"delete window[{json.dumps(SLOT)}]; 1")
        c.close()
        for label in "AB":
            print(f"ab: {label} ({sides[label][3]}): " + ", ".join(f"{k} {v}" for k, v in tally[label].items() if v))
        os.makedirs("runs", exist_ok=True)
        relabel = os.path.join("runs", f"ab-{time.strftime('%m%d-%H%M')}.json")
        with open(relabel, "w", encoding="utf-8") as fh:
            json.dump(games, fh, indent=1)
        print(f"ab: sides by game id -> {relabel} (1 = A = {a.ab[0]}, 2 = B = {a.ab[1]})")
        import subprocess
        series = os.path.join(os.path.dirname(os.path.abspath(__file__)), "series.py")
        arena_arg = ["--arena", slug(arena["name"]), "--relabel", relabel]
        for cmd in (["compare", "1", "2"], ["shares", "--control", "1", "--final", "2", "--every"], ["reach", "--version", "1"], ["reach", "--version", "2"]):
            print(f"\n==== series.py {' '.join(cmd)}", flush=True)
            subprocess.run([sys.executable, series, *cmd, *arena_arg])
        if not a.keep:
            ab_cleanup({v[0] for v in sides.values()})
        return

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
    if a.history:
        for h in reversed(history(c, arena["id"], a.history, a.us)):
            t = h["created"] or ""
            try:
                when = time.strftime('%d.%m %H:%M', time.localtime(time.mktime(time.strptime(t[:19], '%Y-%m-%dT%H:%M:%S')) - time.timezone + (3600 if time.localtime().tm_isdst else 0)))
            except ValueError:
                when = t[:16]
            r = h["rating"]
            rating = f"{r[0]}->{r[1]} #{r[2]}" if r else "-"
            print(f"{when}  {h['id']}  {h['result']:<5} {h['ticks']:>5}t  {rating:<16} code {h['code']}  vs {h['opponent']}")
        return
    if a.test_list:
        rows = test_codes(c, arena["id"])
        if not rows:
            print(f"{arena['name']}: nothing to test against yet — play a rating game first")
        for r in rows:
            who = f"{r['name']}#{r['version']}" if r["version"] else r["name"]
            print(f"{r['list']:<9} {who:<28} {r['codeId']}  {r['note']}")
        return
    if a.fame_collect:
        f = fame_state(c, arena["id"])
        if not f["id"]:
            raise SystemExit(f"{arena['name']}: no fame session today — nothing to collect")
        s = slot(c, arena["id"])
        if s["game"] and s["status"] != "finished":
            raise SystemExit(f"{arena['name']}: a match is still running ({s['game']}) — collect after it ends")
        print(f"fame: session {f['id']} points={f['points']} level={f['level']} rewardedAt={f['rewardedAt']} finishedAt={f['finishedAt']}")
        before = inventory(c)
        if not f["rewardedAt"]:
            r = fame_post(c, arena["id"], "take")
            print(f"fame: take -> {r['status']} {r['body']}")
        else:
            print("fame: rewards already taken")
        if not f["finishedAt"]:
            r = fame_post(c, arena["id"], "finish")
            print(f"fame: finish -> {r['status']} {r['body']}")
        else:
            print("fame: already finished")
        f = fame_state(c, arena["id"])
        print(f"fame: session after: points={f['points']} level={f['level']} rewardedAt={f['rewardedAt']} finishedAt={f['finishedAt']}")
        after = inventory(c)
        gained = {k: after.get(k, 0) - before.get(k, 0) for k in set(before) | set(after) if after.get(k, 0) != before.get(k, 0)}
        print(f"fame: inventory gained {gained if gained else 'nothing'}")
        return
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
    test_code = None
    if a.test:
        row = resolve_test(test_codes(c, arena["id"]), a.test)
        test_code = row["codeId"]
        who = f"{row['name']}#{row['version']}" if row["version"] else row["name"]
        print(f"test games against {who} ({row['list']}, code {test_code}) — these move NO rating")
    if a.fame:
        f = fame_state(c, arena["id"])
        if f["qualifying"]:
            raise SystemExit(f"{arena['name']}: fewer than five rating games here — no fame session yet")
        if f["finishedAt"]:
            raise SystemExit(f"{arena['name']}: today's fame session is finished ({f['finishedAt']}) — fame returns tomorrow")
        print(f"fame: session {f['id'] or '(not started)'} points={f['points']} level={f['level']} keys={f['keys']}")
    for i in range(1, a.count + 1):
        if payload_size(c) != size:            # the page was reloaded, or the payload was overwritten
            print(f"{i}/{a.count}: the payload is not on the page any more, sending it again")
            size = push_zip(c, data)
        r = start(c, arena["id"], fame=a.fame, code_id=test_code)
        if r["status"] not in (200, 201) or not r.get("id"):
            print(f"{i}/{a.count}: start failed ({r['status']} {r.get('err')}) {r.get('raw') or ''}")
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
        path = os.path.join(a.logs, f"{time.strftime('%m%d-%H%M')}-{res}-{gid[-6:]}.txt") if a.logs else None
        n = save_match(c, gid, path)
        line += f" | log {n} ticks -> {path}" if path else f" | {n} ticks stored"
        if path:
            # our bots name themselves on the first line; another name means the wrong payload was played
            greeting = open(path, encoding='utf-8').readline().strip()
            if greeting.startswith("hello") and slug(arena["name"]) not in greeting:
                line += f"\n  !! WRONG BOT PLAYED: {greeting[:80]}"
        print(line, flush=True)
        if a.fame:
            f = fame_state(c, arena["id"])
            print(f"fame: session {f['id']} points={f['points']} level={f['level']} finishedAt={f['finishedAt']}", flush=True)
            if f["finishedAt"]:
                print("fame: the session is finished — stopping")
                break
        if (a.stop_on_defeat and res == "lost") or (a.stop_on_non_win and res != "won"):
            print(f"stopping at {res}")
            break
    print("total: " + ", ".join(f"{k} {v}" for k, v in tally.items() if v))
    c.eval(f"delete window[{json.dumps(SLOT)}]; 1")
    c.close()


if __name__ == "__main__":
    main()
