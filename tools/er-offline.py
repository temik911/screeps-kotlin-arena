#!/usr/bin/env python3
"""Escort Run offline league: two builds of our bot against each other in the stub, on the real terrains of stored matches.

The server league (tools/er-league.py) plays the real engine but only against our codes still in the window of the last
~20 rating games, and one hand takes 13 seconds. The stub keeps every version forever and plays a hand in under a
second, both sides of each map, many at once. What it does not model is construction and harvest-by-the-enemy economy,
so a trick built on those is measured on the server. Calibrate a finding here against the server before trusting it.

Each side runs from its OWN copy of a build (one module graph per side — the bot is a singleton object), built from
this worktree or from a git ref in a temporary worktree (tools/play.py ab_worktree). The maps are the terrains of stored
Escort Run matches (~/ScreepsArena/games/*/game.json, one per distinct terrain), each played twice with the sides
swapped; the fixed layout (spawns, ramparts, the 48 corridor walls, sources, containers, flags) is the stub's.

    tools/er-offline.py --b escort-run-v19 -n 40            # this worktree (A) against the v19 tag (B), 40 maps x 2 sides
    tools/er-offline.py --a HEAD~3 --b HEAD --persona-a choke -n 20
"""
import argparse, glob, json, os, re, shutil, subprocess, sys, tempfile
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import play  # noqa: E402

ARENA = "6a86d8c454a3948a1e35f910"
STUB = os.path.join(HERE, "stub", "escortrun")
PKG = "screeps-kotlin-arena-starter"
ENTRY = "kotlin/screeps-kotlin-arena-starter/season4/escortrun/EscortRun.export.mjs"


def node():
    hits = sorted(glob.glob(os.path.expanduser("~/.gradle/nodejs/*/bin/node")))
    if not hits:
        raise SystemExit("no Gradle-downloaded node under ~/.gradle/nodejs")
    return hits[-1]


def side_bundle(ref, label, scratch, keep):
    """A private copy of the build package for one side; returns (entry url, description, worktree to clean or None)."""
    if ref:
        try:
            wt, starter, sha = play.ab_worktree(ref, label)
        except SystemExit as e:
            # the first build of a fresh worktree fails now and then in :starter:jsNodeTest (CLAUDE.md: transient,
            # a second build clears it) — measured on v22's worktree, 27.09.2026
            print(f"offline: {ref}: {str(e).splitlines()[0][:120]} — building again", flush=True)
            wt, starter, sha = play.ab_worktree(ref, label)
        desc = f"{ref} ({sha[:10]})"
    else:
        wt, starter = None, os.path.join(HERE, "..", "build", "js", "packages", PKG)
        desc = "this worktree"
    dst = os.path.join(scratch, label)
    shutil.copytree(starter, dst, symlinks=False)
    greet = ""
    src = os.path.join(dst, "kotlin", PKG, "season4", "escortrun", "EscortRun.mjs")
    if os.path.exists(src):
        m = re.search(r"BOT_VERSION[^'\"]*['\"](v[0-9a-z]+)['\"]", open(src, encoding="utf-8").read())
        greet = m.group(1) if m else ""
    return "file://" + os.path.join(dst, ENTRY), f"{desc} {greet}".strip(), (wt if (wt and not keep) else None)


def maps(n):
    seen, out = set(), []
    for f in sorted(glob.glob(os.path.expanduser("~/ScreepsArena/games/*/game.json")), key=os.path.getmtime, reverse=True):
        try:
            g = json.load(open(f, encoding="utf-8")).get("game") or {}
        except (OSError, ValueError):
            continue
        t = (g.get("game") or {}).get("terrain")
        if g.get("arena") != ARENA or not t or len(t) != 10000 or t in seen:
            continue
        seen.add(t)
        out.append(f)
        if len(out) >= n:
            break
    return out


def hand(args):
    game, swap, a, b, pa, pb = args
    env = dict(os.environ, GAME=game, BOT=a, BOT2=b, PERSONA=pa, PERSONA2=pb,
               LOGTAG=f"off-{os.path.basename(os.path.dirname(game))[:8]}-{'top' if swap else 'bottom'}-")
    if swap:
        env["START"] = "match2"
    env.setdefault("NODE_OPTIONS", "--max-semi-space-size=2 --max-old-space-size=256")
    r = subprocess.run([node(), "--import", "./register.mjs", "run.mjs", "2000", "offline"], cwd=STUB, env=env,
                       capture_output=True, text=True, timeout=600)
    line = next((l for l in r.stdout.splitlines() if l.startswith("done:")), "")
    m = re.match(r"done: (WIN|LOSS|DRAW)", line)
    res = m.group(1) if m else "ERR"
    t = re.search(r"at t=(\d+)", line)
    tick = int(t.group(1)) if t else None
    errs = re.search(r"errors=(\d+)", line)
    return os.path.basename(os.path.dirname(game))[:8], "bottom" if not swap else "top", res, tick, line if res == "ERR" or (errs and errs.group(1) != "0") else ""


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--a", help="git ref of side A (default: this worktree's build)")
    ap.add_argument("--b", help="git ref of side B (default: this worktree's build)")
    ap.add_argument("--persona-a", default="main")
    ap.add_argument("--persona-b", default="main")
    ap.add_argument("-n", type=int, default=20, help="maps (each played on both sides)")
    ap.add_argument("-j", type=int, default=6, help="parallel stub runs")
    ap.add_argument("--keep", action="store_true", help="keep temporary worktrees")
    ap.add_argument("--losses", action="store_true", help="list A's lost and drawn hands")
    a = ap.parse_args()

    scratch = tempfile.mkdtemp(prefix="er-offline-")
    cleanup = []
    try:
        ua, da, wa = side_bundle(a.a, "A", scratch, a.keep)
        ub, db, wb = side_bundle(a.b, "B", scratch, a.keep)
        cleanup += [w for w in (wa, wb) if w]
        ms = maps(a.n)
        print(f"offline: A = {da} persona={a.persona_a} | B = {db} persona={a.persona_b} | {len(ms)} maps x 2 sides", flush=True)
        jobs = [(g, s, ua, ub, a.persona_a, a.persona_b) for g in ms for s in (False, True)]
        with ThreadPoolExecutor(max_workers=a.j) as ex:
            results = list(ex.map(hand, jobs))
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
        if cleanup:
            play.ab_cleanup(cleanup)
    tally = {}
    ticks = {"WIN": [], "LOSS": []}
    for gid, side, res, tick, bad in results:
        tally[(side, res)] = tally.get((side, res), 0) + 1
        if tick and res in ticks:
            ticks[res].append(tick)
        if bad:
            print(f"  !! {gid} {side}: {bad[:160]}")
    w = sum(v for (s, r), v in tally.items() if r == "WIN")
    l = sum(v for (s, r), v in tally.items() if r == "LOSS")
    d = sum(v for (s, r), v in tally.items() if r == "DRAW")
    e = sum(v for (s, r), v in tally.items() if r == "ERR")
    avg = lambda xs: f"{sum(xs) / len(xs):.0f}" if xs else "-"
    print(f"A {w}-{l}-{d}" + (f" ({e} errors)" if e else "") + f" of {len(results)}  |  by side: " +
          ", ".join(f"{s} {r} {v}" for (s, r), v in sorted(tally.items())) +
          f"  |  mean end tick: A wins {avg(ticks['WIN'])}, A losses {avg(ticks['LOSS'])}")
    if a.losses:
        for gid, side, res, tick, _ in results:
            if res != "WIN":
                print(f"  {res:<4} {gid} A on {side} t={tick}")


if __name__ == "__main__":
    main()
