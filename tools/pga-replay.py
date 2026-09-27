#!/usr/bin/env python3
"""Read a Pain and Gain ADVANCED replay (`~/ScreepsArena/replays/<id>.replay.json.gz`, fetched by
`tools/match-log.py replay <id>`): eleven flags, four towers, sixteen creeps a side.

The basic arena's readers (`tools/replay.py`, `tools/autopsy.py`) are built round its seven flags and fourteen creeps,
so this arena has its own — a copy of the idea, not of the code (CLAUDE.md: copy, never share).

    tools/pga-replay.py summary <id>            # sides, outcome, a line per 100 ticks: alive, hits, flags, rate, towers
    tools/pga-replay.py flags <id>              # every flag's changes of owner, with the creep that stood on it
    tools/pga-replay.py deaths <id>             # every death: tick, whose, body, where
    tools/pga-replay.py pic <id> <t> [x0 y0 x1 y1]   # the map at tick t: m/M ours light/heavy, e/E his, p/P pullers

Ours is the side whose username is ours (temik911); in self-play the first side. The replay's per-tick record: `n`
births, `u` [id, x, y, hits, fatigue, …] updates, `x` deaths, `a` [id, action, x, y] actions, `s` [id, hits, energy]
structure deltas, `w` [id, side] owner changes (flags and towers).
"""
import gzip, json, os, re, sys

REPLAYS = os.path.expanduser(os.environ.get("ARENA_REPLAYS", "~/ScreepsArena/replays"))
US = "temik911"
FLAG_SHORT = {"vulnerability": "V5", "heal_reduction": "H4", "attack_reduction": "A3", "ranged_attack_reduction": "R3",
              "hits_loss": "L4", "fatigue_multiplier": "F5"}


def load(gid):
    path = gid if os.path.isfile(gid) else os.path.join(REPLAYS, f"{gid}.replay.json.gz")
    if not os.path.isfile(path):
        hits = [f for f in os.listdir(REPLAYS) if f.startswith(gid)]
        if len(hits) != 1:
            sys.exit(f"no single replay for {gid!r} in {REPLAYS}")
        path = os.path.join(REPLAYS, hits[0])
    return json.load(gzip.open(path))


def flag_name(fid):
    m = re.match(r"pg_flag_(.+?)(?:_([ab]))?$", fid)
    if not m:
        return fid
    return FLAG_SHORT.get(m.group(1), m.group(1)) + (m.group(2) or "")


def our_side(d):
    for p in d["meta"]["players"]:
        if p["username"] == US:
            return p["side"]
    return 0


def terrain(d):
    cells = []
    for ch, n in re.findall(r"([a-z])(\d+)", d["terrain"]):
        cells += [ch] * int(n)
    return cells


def replay(d):
    """Yield (tick, creeps, owners, energy) with creeps = {id: dict(side, x, y, hits, body)} as of that tick."""
    creeps, owners, energy = {}, {}, {}
    for o in d["objects"]:
        owners[o["id"]] = o.get("side")
        energy[o["id"]] = o.get("energy", 0)
    for tk in d["ticks"]:
        for c in tk.get("n", []):
            creeps[c[0]] = dict(side=c[1], x=c[2], y=c[3], hits=c[4], hitsMax=c[5], body=c[6])
        for u in tk.get("u", []):
            s = creeps.setdefault(u[0], dict(side=None, body="?"))
            s["x"], s["y"], s["hits"] = u[1], u[2], u[3]
        for cid in tk.get("x", []):
            if cid in creeps:
                creeps[cid]["hits"] = 0
        for sid, hits, en in tk.get("s", []):
            energy[sid] = en
        for oid, side in tk.get("w", []):
            owners[oid] = side
        yield tk["k"], creeps, owners, energy, tk


def outcome(d):
    r = d["meta"].get("result") or {}
    us = our_side(d)
    if r.get("draw"):
        return "draw"
    w = r.get("winner")
    return "won" if w == us else ("lost" if w is not None else "?")


def cmd_summary(d):
    us = our_side(d)
    players = {p["side"]: f"{p['username']}#{p.get('codeVersion')}" for p in d["meta"]["players"]}
    print(f"{d['meta']['gameId']}  us={players.get(us)} side {us}  vs {players.get(1 - us)}  {outcome(d)}  ticks={d['meta']['ticks']}")
    flags = {o["id"]: o for o in d["objects"] if o["kind"] == "flag"}
    towers = {o["id"]: o for o in d["objects"] if o["kind"] == "tower"}
    score = [0, 0]
    rate_of = {}
    for fid in flags:
        m = re.match(r"pg_flag_(.+?)(?:_[ab])?$", fid)
        rate_of[fid] = {"vulnerability": 5, "heal_reduction": 4, "attack_reduction": 3, "ranged_attack_reduction": 3,
                        "hits_loss": 4, "fatigue_multiplier": 5}.get(m.group(1), 0)
    for k, creeps, owners, energy, tk in replay(d):
        for fid in flags:
            s = owners.get(fid)
            if s in (0, 1):
                score[s] += rate_of[fid]
        if k % 100 == 0 or k == d["meta"]["ticks"]:
            alive = [sum(1 for c in creeps.values() if c["side"] == s and c.get("hits", 0) > 0) for s in (us, 1 - us)]
            hits = [sum(c.get("hits", 0) for c in creeps.values() if c["side"] == s) for s in (us, 1 - us)]
            ours = [flag_name(f) for f in flags if owners.get(f) == us]
            his = [flag_name(f) for f in flags if owners.get(f) == 1 - us]
            rate = [sum(rate_of[f] for f in flags if owners.get(f) == s) for s in (us, 1 - us)]
            tw = " ".join(f"{t}{'o' if owners.get(t) == us else 'e' if owners.get(t) == 1 - us else 'n'}{energy.get(t, 0)}" for t in towers)
            print(f"t={k:<5} alive={alive[0]}:{alive[1]} hits={hits[0]}:{hits[1]} score={score[us]}:{score[1 - us]} "
                  f"rate={rate[0]}:{rate[1]} ours=[{' '.join(sorted(ours))}] his=[{' '.join(sorted(his))}] towers={tw}")


def cmd_flags(d):
    us = our_side(d)
    flags = {o["id"]: o for o in d["objects"] if o["kind"] == "flag"}
    for k, creeps, owners, energy, tk in replay(d):
        for oid, side in tk.get("w", []):
            if oid in flags:
                f = flags[oid]
                who = [cid.split("_", 2)[2] for cid, c in creeps.items() if c.get("x") == f["x"] and c.get("y") == f["y"] and c.get("hits", 0) > 0]
                print(f"t={k:<5} {flag_name(oid):<4} ({f['x']},{f['y']}) -> {'US' if side == us else 'HIM' if side == 1 - us else side}  by {','.join(who) or '?'}")


def cmd_deaths(d):
    us = our_side(d)
    last = {}
    for k, creeps, owners, energy, tk in replay(d):
        for cid in tk.get("x", []):
            c = last.get(cid) or creeps.get(cid, {})
            print(f"t={k:<5} {'OURS' if c.get('side') == us else 'his '} {cid.split('_', 2)[-1]:<16} {c.get('body', '?'):<8} at ({c.get('x')},{c.get('y')})")
        last = {cid: dict(c) for cid, c in creeps.items()}


def cmd_pic(d, t, box):
    us = our_side(d)
    cells = terrain(d)
    x0, y0, x1, y1 = box
    grid = {(x, y): ("#" if cells[y * 100 + x] == "w" else "~" if cells[y * 100 + x] == "s" else ".") for y in range(100) for x in range(100)}
    for k, creeps, owners, energy, tk in replay(d):
        if k < t:
            continue
        for o in d["objects"]:
            ch = {"tower": "T", "container": "c", "flag": "F"}.get(o["kind"], "*")
            if o["kind"] == "flag":
                s = owners.get(o["id"])
                ch = "F" if s == us else "f" if s == 1 - us else "+"
            grid[(o["x"], o["y"])] = ch
        for cid, c in creeps.items():
            if c.get("hits", 0) <= 0:
                continue
            heavy = "heavy" in cid
            puller = "puller" in cid
            ch = ("p" if puller else "M" if heavy else "m") if c["side"] == us else ("q" if puller else "E" if heavy else "e")
            grid[(c["x"], c["y"])] = ch
        break
    print(f"t={t}  m/M ours light/heavy, p our puller, e/E/q his; F our flag, f his, + neutral")
    for y in range(y0, y1 + 1):
        print(f"{y:02d} " + "".join(grid[(x, y)] for x in range(x0, x1 + 1)))


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    cmd, gid = sys.argv[1], sys.argv[2]
    d = load(gid)
    if cmd == "summary":
        cmd_summary(d)
    elif cmd == "flags":
        cmd_flags(d)
    elif cmd == "deaths":
        cmd_deaths(d)
    elif cmd == "pic":
        t = int(sys.argv[3])
        box = tuple(map(int, sys.argv[4:8])) if len(sys.argv) >= 8 else (0, 0, 99, 99)
        cmd_pic(d, t, box)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
