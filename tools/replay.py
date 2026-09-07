#!/usr/bin/env python3
"""Read a live match's full replay — BOTH sides' positions, hits, attacks and heals per tick — and say what each army
actually did in a fight. This closes the blind spot of our own console logs: they show our intents and the enemy's
positions, never the enemy's shots, swings or heals, so "why did we lose the even fight" was a guess until 05.09.2026.

The replay comes from arukuka/screeps-arena-tools (GPL; not vendored — it lives outside the repository). One-time setup:

    git clone https://github.com/arukuka/screeps-arena-tools && cd screeps-arena-tools && npm install && npm run build

The Arena client must be running and started BY ITS ABSOLUTE PATH (tools/arena_cdp.py's launch hint does that: the
tool finds the client with `ps … | grep screeps_arena.app/Contents/MacOS/screeps_arena`, and a client started as
`./MacOS/screeps_arena` is invisible to it — "Screeps: Arena is not running" while the window is open). Then

    node dist/src/cli.js history "Pain and Gain"             # match ids, results, ratings (Result is the OPPONENT's)
    node dist/src/cli.js fetch <match-id> -o replays/<id>.replay.json.gz

sends SIGUSR1 to the client (opens its Node inspector on :9229), drives it over CDP to download every replay chunk
with the client's own session, and writes a delta-encoded `.replay.json.gz` (format: the tool's docs/FORMAT.md; the
part order in `body` is the real one — "r6m6" is six RANGED_ATTACK parts IN FRONT of six MOVE, which is why 600
damage disarms a ranged creep). Restart the client afterwards. Then:

    tools/replay.py summary <replay.json.gz> [t0 t1] [--us temik911]
        per side: shots by (kind, target role), mass attacks, adjacent vs ranged heals; melee, healer and ranged
        distance histograms; ranged creep-ticks with a target within 3 versus shots fired; melee creep-ticks adjacent
        versus swings. Positions are end-of-tick (the state the tool records), so read the percentages as ±1 cell.
    tools/replay.py silent <replay.json.gz> t0 t1 [-n 12]
        the exact question "who had a target and did not fire", judged on START-of-tick positions (the ones intents
        are resolved on), with the silent creep-ticks split into disarmed (no weapon part left) and truly silent
    tools/replay.py focus <replay.json.gz> t0 t1
        fire quality: shots at already-disarmed creeps, distinct targets per tick, focus share
    tools/replay.py trace <replay.json.gz> t0 t1 [--side them]
        per-tick positions of one side's melee against the other side, with action counters per tick
    tools/replay.py choice <replay.json.gz> [t0 t1]
        target choice: for every single-target shot, what role was hit (and whether it was still armed) against the
        best armed enemy the shooter had within 3 at that moment — an armed ranged, only armed melee, or neither.
        Judged on START-of-tick positions. The question it answers: "his ranged was in reach — what did we shoot?"
    tools/replay.py track <replay.json.gz> [t0 t1] [--step 50]
        the match without a fight: every `step` ticks both armed centroids, their distance, how far his moved over
        the step, the flag his centroid is nearest, and his DISPERSION — the largest group of his armed creeps within
        eight cells of each other and how many are alone; then a timeline of his creeps stepping onto flag cells (one
        entry per flag per thirty ticks, with the role) and the share of ticks his largest group is at most half of
        his armed. Match 119 (けろびー's farmer, lost 8404:22531 without a shot): his centroid moved 6–38 cells per
        fifty ticks, his largest group was 4–7 of nine — a melee on R3, a ranged on D5 and a ranged on A3 in the same
        tick — and the army chased the centroid of a dispersed farmer. That is v56 (USE_DETACH, FARMER_MOVE).

    tools/replay.py block <replay.json.gz> t0 t1 [--pics t1,t2]
        his BLOCK in the frame of the axis from his armed centroid to ours: depth and lateral offset per role, the share
        of his fighters with a healer adjacent, his centroid's step along the axis per tick against our nearest melee's
        distance, ASCII pictures of both blocks at the ticks asked — the measure that named Coldkimchi#1's shape
        (two wide, three deep, healers adjacent to every ranged, walking backwards with our melee at two)
    tools/replay.py dance <replay.json.gz> t0 t1
        the melee dance at two: what his creep and our melee did by the next tick, the distance after, swings at one
    tools/replay.py persist <replay.json.gz> t0 t1
        target persistence of both sides' ranged fire and whether the creep under fire had a healer adjacent
    tools/replay.py brawl <replay.json.gz> t0 t1
        his melee adjacent to ours: victims by role, our nearest armed melee's distance, whether we could swing back
    tools/replay.py bodies <replay.json.gz> --at t1,t2
        both sides' creeps at given ticks: role, hits, real body order, position (who was still armed, who could heal)
    tools/replay.py swings <replay.json.gz> t0 t1
        the melee's swing choice: what each side's melee hit against the best enemy adjacent to it (armed melee / armed ranged / neither)
`t0 t1` is the fight window in ticks (pick it from `tools/match-log.py dump` or the summary's first-action tick).
What `choice` found on the three losses to standing lines (matches 67, 53, 43): with one of his armed ranged within
three, our ranged shot it 39 / 36 / 46 % of the time and a melee standing at two or a healer the rest; his shot ours
65 / 70 / 62 % — his ranged lived armed twice as long as ours and fired twice as often. That is v41 (threatOf).
`--us` is the username prefix that marks our side (default temik911). What it found the first time it ran, on six
matches: our fire discipline and focus are as good as the winners' — every "silent with a target" ranged of ours was
already disarmed — and the losses are in geometry: the enemy line stands at exactly three from our front, its melee
walk around our front to our ranged and healers (66 adjacent creep-ticks against our 28 in match 30), while our
melee hold a line nobody attacks. See docs/pain-and-gain.md, matches 30–35, and the press (v30) that came out of it.
"""
import argparse, gzip, json, re, statistics, sys
from collections import Counter

RANGED_RANGE, HEAL_RANGE = 3, 3


def rng(a, b):
    return max(abs(a[0] - b[0]), abs(a[1] - b[1]))


def parse_body(body):
    return [(k, int(n)) for k, n in re.findall(r'([a-z])(\d+)', body)]


def role(body):
    kinds = dict(parse_body(body))
    if kinds.get('a'): return 'melee'
    if kinds.get('r'): return 'ranged'
    if kinds.get('h'): return 'healer'
    return 'scout'


def tail(body):
    """Parts behind the last weapon or heal part: the creep is disarmed once only these are left (hits <= 100 * tail)."""
    n = 0
    for k, c in reversed(parse_body(body)):
        if k in 'arh': break
        n += c
    return n


def load(path):
    doc = json.load(gzip.open(path, 'rt', encoding='utf-8'))
    meta = doc['meta']
    names = {p['side']: p['username'] for p in meta['players']}
    return doc, meta, names


def our_side(meta, us):
    for p in meta['players']:
        if p['username'].startswith(us):
            return p['side']
    sys.exit(f"no player named {us}* in {[p['username'] for p in meta['players']]}; pass --us")


def ticks(doc):
    """Yields (k, start, now, acts, raw) per tick — see frames(); the raw tick itself is dropped."""
    for k, start, now, acts, raw, _ in frames(doc):
        yield k, start, now, acts, raw


def frames(doc):
    """Yields (k, start, now, acts, raw, tick) per tick: creeps before this tick's updates (what intents were judged
    on), after them, this tick's actions as {creep id: [codes]}, the raw action list, and the whole tick record —
    structure energy and hits arrive in the tick's `s`, which the creep state above never sees."""
    creeps = {}
    for tick in doc['ticks']:
        k = tick['k']
        start = {cid: dict(c) for cid, c in creeps.items()}
        for n in tick.get('n', []):
            cid, side, x, y, hits, hits_max, body, spawning = n
            creeps[cid] = dict(side=side, x=x, y=y, hits=hits, hitsMax=hits_max, body=body, role=role(body),
                               tail=tail(body), spawning=bool(spawning))
        for u in tick.get('u', []):
            cid, x, y, hits, fatigue, spawning = u
            # A CREEP STILL BEING BORN CANNOT ACT, and it stands on the spawn cell where an enemy at
            # range 3 is common — counted as "had a target and did not fire" it invented an uptime gap
            # exactly in the matches where we spawn most, which is the ones we lose.
            if cid in creeps: creeps[cid].update(x=x, y=y, hits=hits, spawning=bool(spawning))
        for b in tick.get('b', []):
            cid, body = b
            if cid in creeps: creeps[cid].update(body=body, role=role(body), tail=tail(body))
        for cid in tick.get('x', []):
            creeps.pop(cid, None)
        acts = {}
        for a in tick.get('a', []):
            acts.setdefault(a[0], []).append(a[1])
        yield k, start, creeps, acts, tick.get('a', []), tick


def header(meta, names, us_side):
    print(f"{meta.get('shortId')}: {names[0]} (side 0) vs {names[1]} (side 1), ticks={meta['ticks']}, we are side {us_side}, "
          f"winner={meta['result'].get('winnerName')}")


def cmd_summary(args):
    doc, meta, names = load(args.replay)
    us = our_side(meta, args.us)
    header(meta, names, us)
    st = {s: dict(shots=Counter(), mass=0, heal_adj=0, heal_rng=0, swings=0, melee_dist=Counter(), healer_dist=Counter(),
                  ranged_dist=Counter(), ranged_opp=Counter(), melee_adj=Counter()) for s in (0, 1)}
    first = None
    for k, start, now, acts, raw in ticks(doc):
        if k < args.t0 or k > args.t1 or not raw: continue
        if first is None: first = k
        prev_at = {(c['x'], c['y']): cid for cid, c in start.items()}
        at = {(c['x'], c['y']): cid for cid, c in now.items()}
        for a in raw:
            cid, code = a[0], a[1]
            if cid not in now or code in ('A', 'E'): continue
            s = now[cid]['side']
            tgt = None
            if len(a) >= 4:
                tid = prev_at.get((a[2], a[3])) or at.get((a[2], a[3]))
                tgt = now.get(tid) if tid else None
            if code in ('a', 'r'):
                st[s]['shots'][(code, tgt['role'] if tgt else '?')] += 1
                if code == 'a': st[s]['swings'] += 1
            elif code == 'R': st[s]['mass'] += 1
            elif code == 'h': st[s]['heal_adj'] += 1
            elif code == 'H': st[s]['heal_rng'] += 1
        for s in (0, 1):
            mine = [c for c in now.values() if c['side'] == s]
            enemy = [c for c in now.values() if c['side'] != s]
            if not enemy: continue
            for c in mine:
                d = min(rng((c['x'], c['y']), (e['x'], e['y'])) for e in enemy)
                if c['role'] == 'melee':
                    st[s]['melee_dist'][d] += 1
                    st[s]['melee_adj'][d <= 1] += 1
                elif c['role'] == 'ranged':
                    st[s]['ranged_dist'][d] += 1
                    st[s]['ranged_opp'][d <= RANGED_RANGE] += 1
            wounded = [c for c in mine if c['role'] != 'healer' and c['hits'] < c['hitsMax']]
            if wounded:
                w = max(wounded, key=lambda c: c['hitsMax'] - c['hits'])
                for c in mine:
                    if c['role'] == 'healer':
                        st[s]['healer_dist'][rng((c['x'], c['y']), (w['x'], w['y']))] += 1
    print(f"window t={args.t0}..{args.t1}, first action at t={first}")
    hist = lambda c: ' '.join(f"{d}:{n}" for d, n in sorted(c.items())[:9])
    for s in (0, 1):
        x = st[s]
        tag = 'OURS ' if s == us else 'ENEMY'
        shots = ', '.join(f"{code}->{r}:{n}" for (code, r), n in x['shots'].most_common())
        print(f"{tag} {names[s]}: shots[{shots}] mass={x['mass']} heal adj={x['heal_adj']} rng={x['heal_rng']}")
        print(f"      melee->nearest enemy dist hist {hist(x['melee_dist'])}")
        print(f"      ranged->nearest enemy dist hist {hist(x['ranged_dist'])}")
        print(f"      healer->most wounded ally dist hist {hist(x['healer_dist'])}")
        ro = x['ranged_opp']; tot = sum(ro.values()) or 1
        rshots = sum(n for (code, r), n in x['shots'].items() if code == 'r') + x['mass']
        ma = x['melee_adj']; mtot = sum(ma.values()) or 1
        print(f"      ranged creep-ticks: {tot}, with an enemy within 3: {ro[True]} ({100 * ro[True] // tot}%), shots fired: {rshots} ({100 * rshots // tot}% of creep-ticks)")
        print(f"      melee creep-ticks: {mtot}, adjacent to an enemy: {ma[True]} ({100 * ma[True] // mtot}%), swings: {x['swings']}")


def cmd_silent(args):
    doc, meta, names = load(args.replay)
    us = our_side(meta, args.us)
    header(meta, names, us)
    tot = {s: Counter() for s in (0, 1)}
    examples = []
    for k, start, now, acts, raw in ticks(doc):
        if k < args.t0 or k > args.t1 or not start: continue
        for s in (0, 1):
            mine = [(cid, c) for cid, c in start.items() if c['side'] == s]
            enemy = [c for c in start.values() if c['side'] != s]
            if not enemy: continue
            for cid, c in mine:
                if c.get('spawning'): continue   # still being born: it cannot act, so it is not silent
                d = min(rng((c['x'], c['y']), (e['x'], e['y'])) for e in enemy)
                my = acts.get(cid, [])
                disarmed = c['hits'] <= 100 * c['tail']
                if c['role'] == 'ranged' and d <= RANGED_RANGE:
                    tot[s]['ranged with a target'] += 1
                    if 'r' in my or 'R' in my: tot[s]['ranged fired'] += 1
                    elif disarmed: tot[s]['ranged silent: disarmed'] += 1
                    else:
                        tot[s]['ranged SILENT armed'] += 1
                        if s == us and len(examples) < args.n:
                            examples.append(f"t={k} ranged {cid} at ({c['x']},{c['y']}) hits={c['hits']} {c['body']} acts={my} nearest at {d}")
                if c['role'] == 'melee' and d <= 1:
                    tot[s]['melee adjacent'] += 1
                    if 'a' in my: tot[s]['melee swung'] += 1
                    elif disarmed: tot[s]['melee silent: disarmed'] += 1
                    else:
                        tot[s]['melee SILENT armed'] += 1
                        if s == us and len(examples) < args.n:
                            examples.append(f"t={k} melee {cid} at ({c['x']},{c['y']}) hits={c['hits']} {c['body']} acts={my} nearest at {d}")
    for s in (0, 1):
        tag = 'OURS ' if s == us else 'ENEMY'
        print(f"{tag} {names[s]}: " + ', '.join(f"{k}={v}" for k, v in sorted(tot[s].items())))
    for e in examples: print('   ' + e)


def cmd_focus(args):
    doc, meta, names = load(args.replay)
    us = our_side(meta, args.us)
    header(meta, names, us)
    st = {s: Counter() for s in (0, 1)}
    targets = {s: [] for s in (0, 1)}
    share = {s: [] for s in (0, 1)}
    for k, start, now, acts, raw in ticks(doc):
        if k < args.t0 or k > args.t1 or not start: continue
        at = {(c['x'], c['y']): cid for cid, c in start.items()}
        shots = {s: Counter() for s in (0, 1)}
        for a in raw:
            cid, code = a[0], a[1]
            if cid not in start or code not in ('a', 'r') or len(a) < 4: continue
            s = start[cid]['side']
            tid = at.get((a[2], a[3]))
            if tid is None or tid not in start:
                st[s]['shots at ?'] += 1; continue
            t = start[tid]
            st[s]['shots'] += 1
            if t['hits'] <= 100 * t['tail']: st[s]['shots at disarmed'] += 1
            if t['role'] == 'scout': st[s]['shots at scout'] += 1
            shots[s][tid] += 1
        for s in (0, 1):
            n = sum(shots[s].values())
            if n >= 2:
                targets[s].append(len(shots[s]))
                share[s].append(max(shots[s].values()) / n)
    for s in (0, 1):
        tag = 'OURS ' if s == us else 'ENEMY'
        tt, fs = targets[s], share[s]
        print(f"{tag} {names[s]}: " + ', '.join(f"{k}={v}" for k, v in sorted(st[s].items())) +
              f"; ticks with 2+ shots={len(tt)}, distinct targets/tick={(sum(tt) / len(tt)) if tt else 0:.2f}, "
              f"focus share={(sum(fs) / len(fs)) if fs else 0:.2f}")


def cmd_trace(args):
    doc, meta, names = load(args.replay)
    us = our_side(meta, args.us)
    side = us if args.side == 'us' else 1 - us
    header(meta, names, us)
    print(f"tracing the melee of side {side} ({names[side]}); '>' moved this tick, '.' stood; d = nearest enemy, m = nearest enemy melee")
    for k, start, now, acts, raw in ticks(doc):
        if k < args.t0 or k > args.t1: continue
        mine = [(cid, c) for cid, c in now.items() if c['side'] == side]
        enemy = [(cid, c) for cid, c in now.items() if c['side'] != side]
        if not mine or not enemy: continue
        emel = [c for cid, c in enemy if c['role'] == 'melee']
        parts = []
        for cid, c in sorted(mine, key=lambda kv: kv[0]):
            if c['role'] != 'melee': continue
            d = min(rng((c['x'], c['y']), (e['x'], e['y'])) for _, e in enemy)
            dm = min((rng((c['x'], c['y']), (e['x'], e['y'])) for e in emel), default=99)
            moved = '>' if cid in start and (start[cid]['x'], start[cid]['y']) != (c['x'], c['y']) else '.'
            parts.append(f"{str(cid)[-3:]}({c['x']},{c['y']})h{c['hits']}d{d}/m{dm}{moved}{''.join(acts.get(cid, [])) or '-'}")
        my_acts = Counter(code for cid, c in mine for code in acts.get(cid, []))
        en_acts = Counter(code for cid, c in enemy for code in acts.get(cid, []))
        print(f"t={k} mine:{dict(my_acts)} enemy:{dict(en_acts)}")
        print(f"   melee {' '.join(parts)}")
        print(f"   my ranged={[(c['x'], c['y']) for _, c in mine if c['role'] == 'ranged']} enemy melee={[(c['x'], c['y']) for c in emel]} "
              f"enemy ranged={[(c['x'], c['y']) for _, c in enemy if c['role'] == 'ranged']}")


def cmd_choice(args):
    doc, meta, names = load(args.replay)
    us = our_side(meta, args.us)
    header(meta, names, us)
    mat = {s: Counter() for s in (0, 1)}
    shots = {s: 0 for s in (0, 1)}
    armed = lambda c: c['role'] in ('melee', 'ranged') and c['hits'] > 100 * c['tail']
    for k, start, now, acts, raw in ticks(doc):
        if k < args.t0 or k > args.t1 or not start: continue
        at = {(c['x'], c['y']): cid for cid, c in start.items()}
        for a in raw:
            cid, code = a[0], a[1]
            if cid not in start or code != 'r' or len(a) < 4: continue
            shooter = start[cid]; s = shooter['side']
            tid = at.get((a[2], a[3]))
            if tid is None or tid not in start or start[tid]['side'] == s: continue
            target = start[tid]
            reach = [c for c in start.values() if c['side'] != s and rng((c['x'], c['y']), (shooter['x'], shooter['y'])) <= RANGED_RANGE]
            avail = 'R' if any(c['role'] == 'ranged' and armed(c) for c in reach) else 'M' if any(c['role'] == 'melee' and armed(c) for c in reach) else '-'
            hit = target['role'] + ('' if armed(target) or target['role'] not in ('melee', 'ranged') else '-disarmed')
            mat[s][(hit, avail)] += 1
            shots[s] += 1
    for s in (us, 1 - us):
        tag = 'OURS ' if s == us else 'ENEMY'
        print(f"{tag} {names[s]}: single-target shots {shots[s]} — rows: what was hit; columns: best ARMED enemy within {RANGED_RANGE} of the shooter")
        print(f"   {'hit':16s} {'ranged avail':>13s} {'melee only':>11s} {'neither':>8s}")
        for h in sorted({h for h, _ in mat[s]}):
            print(f"   {h:16s} {mat[s][(h, 'R')]:13d} {mat[s][(h, 'M')]:11d} {mat[s][(h, '-')]:8d}")
        r_avail = sum(v for (h, av), v in mat[s].items() if av == 'R')
        r_hit = mat[s][('ranged', 'R')]
        if r_avail: print(f"   with an armed ranged in reach: {r_hit} of {r_avail} shots went into it ({100 * r_hit // r_avail} %)")


FLAG_CELLS = {(49, 49): 'D5', (90, 8): 'H4', (8, 90): 'H4', (67, 31): 'A3', (31, 67): 'A3', (13, 49): 'R3', (85, 49): 'R3'}


def cmd_track(args):
    doc, meta, names = load(args.replay)
    us = our_side(meta, args.us)
    header(meta, names, us)
    R = 8
    armed_roles = ('melee', 'ranged')

    def largest_group(cs):
        seen, best = set(), 0
        for i in range(len(cs)):
            if i in seen: continue
            stack, n = [i], 0
            seen.add(i)
            while stack:
                j = stack.pop(); n += 1
                for k2 in range(len(cs)):
                    if k2 not in seen and rng((cs[j]['x'], cs[j]['y']), (cs[k2]['x'], cs[k2]['y'])) <= R:
                        seen.add(k2); stack.append(k2)
            best = max(best, n)
        return best

    def alone(cs):
        return sum(1 for c in cs if not any(o is not c and rng((c['x'], c['y']), (o['x'], o['y'])) <= R for o in cs))

    def cen(cs):
        return (sum(c['x'] for c in cs) / len(cs), sum(c['y'] for c in cs) / len(cs)) if cs else None

    prev_his, visits, last_visit, tot, disp = None, [], {}, 0, 0
    for k, start, now, acts, raw in ticks(doc):
        if k < args.t0 or k > args.t1: continue
        his = [c for c in now.values() if c['side'] != us]
        his_armed = [c for c in his if c['role'] in armed_roles]
        ours_armed = [c for c in now.values() if c['side'] == us and c['role'] in armed_roles]
        for c in his:
            f = FLAG_CELLS.get((c['x'], c['y']))
            if f:
                key = (c['x'], c['y'])
                if last_visit.get(key, -99) < k - 30: visits.append((k, f, key, c['role']))
                last_visit[key] = k
        if his_armed and ours_armed:
            tot += 1
            big = largest_group(his_armed)
            if big * 2 <= len(his_armed): disp += 1
            if k % args.step == 0:
                oc, hc = cen(ours_armed), cen(his_armed)
                moved = rng(hc, prev_his) if prev_his else 0
                near = min(FLAG_CELLS.items(), key=lambda kv: rng(hc, kv[0]))
                print(f"t={k:5d} ours=({oc[0]:.0f},{oc[1]:.0f}) his=({hc[0]:.0f},{hc[1]:.0f}) dist={rng(oc, hc):.0f} his_moved={moved:.0f} "
                      f"his_near={near[1]}({near[0][0]},{near[0][1]})@{rng(hc, near[0]):.0f} his armed={len(his_armed)} largest={big} alone={alone(his_armed)} "
                      f"| ours armed={len(ours_armed)} largest={largest_group(ours_armed)}")
                prev_his = hc
    print(f"ticks with his largest armed group <= half of his armed: {disp}/{tot} ({100 * disp // max(1, tot)} %)")
    print('his creeps on flag cells (tick:flag(x,y):role):', ' '.join(f"{t}:{f}({x},{y}):{r[0]}" for t, f, (x, y), r in visits[:80]))


PART_COST = {'m': 50, 'w': 100, 'c': 50, 'a': 80, 'r': 150, 'h': 250, 't': 10}
BUILD_COST = {'tower': 1250, 'spawn': 1000, 'rampart': 200, 'extension': 200, 'container': 100,
              'constructedWall': 100, 'road': 10, 'link': 5}


def body_cost(body):
    return sum(PART_COST.get(k, 0) * n for k, n in parse_body(body))


def spawn_queue(doc, side):
    """[(tick, body, cost)] — every creep that side started, in order. `n` records a creep on the tick it
    appears, which is the tick the spawn was charged for it."""
    out = []
    for tick in doc['ticks']:
        for n in tick.get('n', []):
            if n[1] == side:
                out.append((tick['k'], n[6], body_cost(n[6])))
    return out


def initial_spawns(doc):
    """{side: id} — the spawn each side STARTED with: the earliest-seen one, ties by id.

    Needed because "first seen after tick 1" does not separate them: our own spawn first reports on
    tick 2. Everything else of kind spawn was built during the match, which is the whole けろびー#16
    story (he builds a second by t=242 and a third by t=544)."""
    seen = {}
    for tick in doc['ticks']:
        for s in tick.get('s', []):
            seen.setdefault(s[0], tick['k'])
    out = {}
    for o in doc['objects']:
        if o['kind'] != 'spawn':
            continue
        key = (seen.get(o['id'], 10 ** 9), o['id'])
        if o['side'] not in out or key < out[o['side']][0]:
            out[o['side']] = (key, o['id'])
    return {side: oid for side, (_, oid) in out.items()}


def built(doc):
    """[(id, kind, side, x, y, tick)] for structures that were not there at the start.

    A structure the tool first saw mid-match carries a synthetic id (the initial ones are numbered from
    1); the tick is the first one where it reports hits or energy, so it is the tick it became real,
    give or take the first update. Construction sites are skipped — a site is a plan, not a structure.

    SPAWNS ARE STRUCTURES HERE. They used to be skipped along with containers, on the assumption that
    the two on the map are the two you start with. けろびー#16 builds a second at t=242 and a third at
    t=544; his #17 builds six, two of them on our half (07.09.2026). Skipping them hid the one thing
    that decides those matches."""
    firstseen = {}
    for tick in doc['ticks']:
        for s in tick.get('s', []):
            firstseen.setdefault(s[0], tick['k'])
    out = []
    starting = set(initial_spawns(doc).values())
    for o in doc['objects']:
        if o['kind'] in ('constructionSite', 'container') or o['id'] in starting:
            continue
        t = firstseen.get(o['id'])
        if t is not None and t > 1:
            out.append((o['id'], o['kind'], o['side'], o['x'], o['y'], t))
    return out


def containers(doc):
    """[(x, y, energy, appears)] — the map's energy, with the tick each container showed up.

    The four corner piles and the two walled ones are there from tick 0. The rest arrive in mirrored
    pairs, two every fifty ticks, and the tool numbers them a1, a2, a3 ... in order of appearance, so
    the pair index gives the tick. That is a derivation from the ordering, not a reading: it is checked
    against the first tick each container's energy changed, which can only come after it appeared."""
    out = []
    for o in doc['objects']:
        if o['kind'] != 'container':
            continue
        appears = 0
        if o['id'].startswith('a'):
            appears = 50 * ((int(o['id'][1:]) + 1) // 2)
        out.append((o['x'], o['y'], o['energy'], appears))
    return out


def cmd_economy(args):
    """The production race: what each side picked up, what reached its spawn, and what it turned into.

    Nothing here is inferred from our own logs — the replay carries every structure's energy per tick, so
    the enemy's economy is as readable as ours, and it had never been read. A spawn's energy moves for
    three reasons: it regenerates, a hauler delivers, and a creep is charged for at the tick it starts.
    Adding the charge back gives the flow in; the regeneration is measured as the median flow (most ticks
    are quiet ones) rather than assumed."""
    doc, meta, names = load(args.replay)
    us = our_side(meta, args.us)
    header(meta, names, us)
    objs = {o['id']: o for o in doc['objects']}
    spawn_id = {o['side']: o['id'] for o in doc['objects'] if o['kind'] == 'spawn'}
    energy = {o['id']: o.get('energy') or 0 for o in doc['objects']}
    charged = {s: {} for s in (0, 1)}
    for s in (0, 1):
        for t, _, cost in spawn_queue(doc, s):
            charged[s][t] = charged[s].get(t, 0) + cost
    flow = {s: [] for s in (0, 1)}       # (tick, energy entering the spawn that tick, regen included)
    took = {s: 0.0 for s in (0, 1)}      # energy withdrawn from the piles by that side
    contested = decayed = 0.0
    for k, start, now, acts, raw, tick in frames(doc):
        for sid, hits, e in [(x[0], x[1], x[2]) for x in tick.get('s', [])]:
            o = objs.get(sid)
            if o is None:
                continue
            prev, energy[sid] = energy.get(sid, 0), e
            if o['kind'] == 'spawn':
                flow[o['side']].append((k, e - prev + charged[o['side']].pop(k, 0)))
            elif o['kind'] == 'container' and e < prev:
                drop = prev - e
                # a withdraw reaches one cell, so whoever stood next to the pile that tick took it; a pile
                # that empties with nobody beside it is the map taking it back (these piles decay)
                near = {c['side'] for c in start.values() if rng((c['x'], c['y']), (o['x'], o['y'])) <= 1}
                if len(near) == 1:
                    took[near.pop()] += drop
                elif near:
                    contested += drop
                else:
                    decayed += drop
    regen = {s: statistics.median([f for _, f in flow[s]]) if flow[s] else 0 for s in (0, 1)}
    print(f"whole match, {meta['ticks']} ticks; the map hands out 80 energy per tick (two 2000 piles every 50)\n")
    for s in (0, 1):
        tag = 'OURS ' if s == us else 'ENEMY'
        delivered = sum(max(0.0, f - regen[s]) for _, f in flow[s])
        creeps = spawn_queue(doc, s)
        structs = [b for b in built(doc) if b[2] == s]
        struct_cost = sum(BUILD_COST.get(b[1], 0) for b in structs)
        # ПО ТИКАМ МАТЧА, а не по строкам flow: flow держит запись на КАЖДЫЙ спавн в тик, и у игрока
        # с пятью спавнами делитель был впятеро больше настоящего. けろびー читался как 7.6/тик против
        # наших 9.7 — то есть «мы собираем не меньше», — при том что на деле он собирал 16 против 10
        span = meta['ticks'] or 1
        n_spawns = len({o['id'] for o in doc['objects'] if o['kind'] == 'spawn' and o['side'] == s})
        print(f"{tag} {names[s]}: picked up {took[s]:.0f} from the piles, delivered {delivered:.0f} to "
              f"{n_spawns} spawn(s) ({delivered / span:.1f}/tick over the match), never arrived "
              f"{max(0.0, took[s] - delivered):.0f}")
        print(f"      spent {sum(c for _, _, c in creeps)} on {len(creeps)} creeps and {struct_cost} on "
              f"{len(structs)} structures ({', '.join(sorted({b[1] for b in structs})) or 'none'}); "
              f"ends holding {energy.get(spawn_id.get(s), 0):.0f}, spawn regenerates {regen[s]:.0f}/tick")
    starting_ids = set(initial_spawns(doc).values())
    per = {}
    for k, start, now, acts, raw, tick in frames(doc):
        for sid, hits, e in [(x[0], x[1], x[2]) for x in tick.get('s', [])]:
            o = objs.get(sid)
            if o is None or o['kind'] != 'spawn':
                continue
            prev = per.setdefault(sid, [o.get('energy') or 0, 0.0, None])
            if e > prev[0]:
                prev[1] += e - prev[0]
            if prev[2] is None:
                prev[2] = k
            prev[0] = e
    print("\n  energy INTO each spawn (a built spawn is a delivery point — this says whether it earned its 1000):")
    for sid, (_, gained, first) in sorted(per.items(), key=lambda kv: -kv[1][1]):
        o = objs[sid]
        tag = 'OURS ' if o['side'] == us else 'ENEMY'
        when = 'from the start' if sid in starting_ids else f"built, first seen t={first}"
        print(f"    {tag} ({o['x']},{o['y']}) {when}: {gained:.0f}")
    print(f"      piles nobody was standing next to when they emptied: {decayed:.0f} "
          f"(they decay); with both sides beside them: {contested:.0f}")
    print(f"\ncumulative energy delivered to the spawn, every {args.step} ticks:")
    print(f"{'tick':>6} {names[0][:12]:>16} {names[1][:12]:>16}")
    run = {s: 0.0 for s in (0, 1)}
    idx = {s: 0 for s in (0, 1)}
    for k in range(args.step, meta['ticks'] + 1, args.step):
        for s in (0, 1):
            while idx[s] < len(flow[s]) and flow[s][idx[s]][0] <= k:
                run[s] += max(0.0, flow[s][idx[s]][1] - regen[s])
                idx[s] += 1
        print(f"{k:>6} {run[0]:>16.0f} {run[1]:>16.0f}")
    print("\nwhoever's curve is above buys more army for the same body prices — that is the whole race.")


def cmd_spawns(args):
    doc, meta, names = load(args.replay)
    us = our_side(meta, args.us)
    header(meta, names, us)
    for s in (0, 1):
        tag = 'OURS ' if s == us else 'ENEMY'
        q = spawn_queue(doc, s)
        total = 0
        print(f"\n{tag} {names[s]}: {len(q)} creeps, {sum(c for _, _, c in q)} energy")
        for t, body, cost in q:
            total += cost
            print(f"  t={t:<5} {body:<28} {role(body):<7} {cost:>5}  running {total}")


def cmd_scenario(args):
    """Write what the stub needs to replay this opponent: the map, the piles, his queue and how he fought."""
    doc, meta, names = load(args.replay)
    us = our_side(meta, args.us)
    him = 1 - us
    # только СТАРТОВЫЕ: построенные по ходу матча едут отдельно, в enemyStructures со своим тиком
    starting = set(initial_spawns(doc).values())
    spawns = [{'side': o['side'], 'x': o['x'], 'y': o['y'], 'energy': o.get('energy') or 0}
              for o in doc['objects'] if o['kind'] == 'spawn' and o['id'] in starting]
    walls = [{'x': o['x'], 'y': o['y'], 'hits': o['hits']}
             for o in doc['objects'] if o['kind'] == 'constructedWall']
    # how he fought, measured rather than assumed: does he walk into us, does he back off when a gun of
    # ours closes in, and does he move as a body or as a trickle
    kite_chances = kited = 0
    group_sizes = []
    closest = 99
    our_spawn = next(s for s in spawns if s['side'] == us)
    for k, start, now, acts, raw, tick in frames(doc):
        his = [c for c in now.values() if c['side'] == him and c['role'] in ('melee', 'ranged', 'healer')]
        ours = [c for c in now.values() if c['side'] == us and c['role'] in ('melee', 'ranged')]
        if not his or not ours:
            continue
        closest = min(closest, min(rng((c['x'], c['y']), (our_spawn['x'], our_spawn['y'])) for c in his))
        for cid, c in now.items():
            if c['side'] != him or cid not in start:
                continue
            p = start[cid]
            near = [o for o in ours if rng((p['x'], p['y']), (o['x'], o['y'])) <= 2]
            if near:
                kite_chances += 1
                d0 = min(rng((p['x'], p['y']), (o['x'], o['y'])) for o in near)
                d1 = min(rng((c['x'], c['y']), (o['x'], o['y'])) for o in near)
                if d1 > d0:
                    kited += 1
        seen, best = set(), 0
        for i, a in enumerate(his):
            if i in seen:
                continue
            stack, n = [i], 0
            seen.add(i)
            while stack:
                j = stack.pop()
                n += 1
                for k2, b in enumerate(his):
                    if k2 not in seen and rng((his[j]['x'], his[j]['y']), (b['x'], b['y'])) <= 8:
                        seen.add(k2)
                        stack.append(k2)
            best = max(best, n)
        group_sizes.append(best)
    kite = kited / kite_chances if kite_chances else 0.0
    group = statistics.fmean(group_sizes) if group_sizes else 0.0
    # the class is a lookup into behaviours the stub already has, not a new one invented per opponent
    if closest > 20:
        cls = 'farmer'
    elif kite >= 0.2:
        cls = 'healball'
    elif group >= 3:
        cls = 'pairs'
    else:
        cls = 'stream'
    scenario = {
        'source': meta.get('shortId'),
        'opponent': names[him],
        'result': 'we won' if meta['result'].get('winner') == us else (
            'draw' if meta['result'].get('draw') else 'we lost'),
        'ticks': meta['ticks'],
        'width': meta['width'], 'height': meta['height'],
        'terrain': doc['terrain'],
        'ourSide': us,
        'spawns': spawns,
        'walls': walls,
        'containers': [{'x': x, 'y': y, 'energy': e, 'appears': a} for x, y, e, a in containers(doc)],
        'enemyQueue': [{'t': t, 'body': b, 'cost': c} for t, b, c in spawn_queue(doc, him)],
        'enemyStructures': [{'kind': kind, 'x': x, 'y': y, 't': t}
                            for _, kind, side, x, y, t in built(doc) if side == him],
        'behaviour': {'class': cls, 'kite': round(kite, 3), 'group': round(group, 2),
                      'closestToOurSpawn': closest},
    }
    out = args.out or f"{meta.get('shortId')}.scenario.json"
    with open(out, 'w', encoding='utf-8') as f:
        json.dump(scenario, f)
    print(f"{out}: {names[him]}, {scenario['result']}, {meta['ticks']} ticks")
    print(f"  his queue: {len(scenario['enemyQueue'])} creeps, "
          f"{sum(c['cost'] for c in scenario['enemyQueue'])} energy; "
          f"structures: {', '.join(s['kind'] + '@' + str(s['t']) for s in scenario['enemyStructures']) or 'none'}")
    print(f"  behaviour: {cls} (kited away {100 * kite:.0f}% of the times a gun of ours came within two, "
          f"largest group {group:.1f}, came within {closest} of our spawn)")
    print(f"  play it: tools/stub/spawnandswamp/replay.sh {out}")


def crng(a, b):
    """Chebyshev range between two creep dicts (rng() takes (x, y) pairs)."""
    return max(abs(a['x'] - b['x']), abs(a['y'] - b['y']))


def armed(c):
    return c['role'] in ('melee', 'ranged') and c['hits'] > 100 * c['tail']


def cmd_block(args):
    """His block in the frame of the axis from his armed centroid to ours: depth and lateral offset per role, the share of
    his fighters with a healer adjacent, his centroid's step along the axis per tick next to our nearest melee's distance,
    and ASCII pictures of both blocks at the ticks asked for. What it measured on Coldkimchi#1 (match 405, t=180..260):
    a block two wide and three deep, healers adjacent to 71 % of melee-ticks and 100 % of ranged-ticks, the centroid
    stepping back 33 of 79 ticks — a block that walks backwards while our melee are at two of it."""
    import math
    doc, meta, names = load(args.replay)
    US = our_side(meta, args.us)
    pics = set(int(x) for x in args.pics.split(',')) if args.pics else set()
    depth = {r: [] for r in ('melee', 'ranged', 'healer')}; lat = {r: [] for r in ('melee', 'ranged', 'healer')}
    adj = Counter(); adjn = Counter(); steps = []; prevc = None; dnear = []
    width = []; height = []; dist_by_role = {r: Counter() for r in ('melee', 'ranged', 'healer')}
    for k, start, now, acts, raw in ticks(doc):
        if k < args.t0 or k > args.t1: continue
        his = [c for c in now.values() if c['side'] != US and c['role'] in ('melee', 'ranged', 'healer')]
        ours = [c for c in now.values() if c['side'] == US and c['role'] in ('melee', 'ranged', 'healer')]
        hisA = [c for c in his if armed(c)]; ourA = [c for c in ours if armed(c)]
        if not hisA or not ourA: continue
        hc = (sum(c['x'] for c in hisA) / len(hisA), sum(c['y'] for c in hisA) / len(hisA))
        oc = (sum(c['x'] for c in ourA) / len(ourA), sum(c['y'] for c in ourA) / len(ourA))
        ux, uy = oc[0] - hc[0], oc[1] - hc[1]; n = math.hypot(ux, uy) or 1; ux, uy = ux / n, uy / n
        ds = []; ls = []
        for c in his:
            d = (c['x'] - hc[0]) * ux + (c['y'] - hc[1]) * uy; l = -(c['x'] - hc[0]) * uy + (c['y'] - hc[1]) * ux
            depth[c['role']].append(d); lat[c['role']].append(l); ds.append(d); ls.append(l)
            near = min(crng(c, o) for o in ours)
            dist_by_role[c['role']][min(near, 9)] += 1
            if c['role'] != 'healer':
                adjn[c['role']] += 1
                if any(crng(c, h) <= 1 for h in his if h['role'] == 'healer' and h is not c): adj[c['role']] += 1
        width.append(max(ls) - min(ls)); height.append(max(ds) - min(ds))
        if prevc: steps.append((k, round((hc[0] - prevc[0]) * ux + (hc[1] - prevc[1]) * uy, 1)))
        prevc = hc
        om = [o for o in ourA if o['role'] == 'melee']
        if om: dnear.append((k, min(crng(o, h) for o in om for h in his)))
        if k in pics:
            cells = {}
            for c in his: cells[(c['x'], c['y'])] = {'melee': 'M', 'ranged': 'R', 'healer': 'H'}[c['role']] if armed(c) or c['role'] == 'healer' else 'x'
            for c in ours: cells[(c['x'], c['y'])] = {'melee': 'm', 'ranged': 'r', 'healer': 'h'}[c['role']] if armed(c) or c['role'] == 'healer' else '.'
            xs = [x for x, y in cells]; ys = [y for x, y in cells]
            print(f"t={k} his centroid ({hc[0]:.1f},{hc[1]:.1f}) ours ({oc[0]:.1f},{oc[1]:.1f}) — his UPPER case, ours lower")
            for y in range(min(ys), max(ys) + 1):
                print('   ' + ''.join(cells.get((x, y), '·') for x in range(min(xs), max(xs) + 1)))
    if not width:
        print(f"window t={args.t0}..{args.t1}: no tick with both sides armed"); return
    def q(v): return f"{statistics.mean(v):.1f}" if v else '-'
    print(f"window t={args.t0}..{args.t1}: his block width (across) mean {statistics.mean(width):.1f} max {max(width):.0f}, depth (along) mean {statistics.mean(height):.1f} max {max(height):.0f}")
    for r in ('melee', 'ranged', 'healer'):
        print(f"  {r:6}: depth mean {q(depth[r])} (+ = toward us), |lateral| mean {q([abs(x) for x in lat[r]])}; distance to our nearest: " + ' '.join(f"{d}:{n}" for d, n in sorted(dist_by_role[r].items())))
    print(f"  healer adjacent: melee {adj['melee']}/{adjn['melee']} ({100 * adj['melee'] // max(1, adjn['melee'])} %), ranged {adj['ranged']}/{adjn['ranged']} ({100 * adj['ranged'] // max(1, adjn['ranged'])} %)")
    back = [st for k, st in steps if st <= -0.5]; fwd = [st for k, st in steps if st >= 0.5]
    print(f"  his centroid along the axis: steps back {len(back)} (sum {sum(back):.1f}), forward {len(fwd)} (sum {sum(fwd):.1f}), still {len(steps) - len(back) - len(fwd)} of {len(steps)}")
    print("  per tick (tick:step/our-melee-to-his-nearest): " + ' '.join(f"{k}:{st:+.0f}/{dict(dnear).get(k, '-')}" for k, st in steps))


def cmd_dance(args):
    """The melee dance: for every tick where one of our armed melee has one of his creeps within two, what the pair did by
    the next tick (his creep away / toward / sideways / still relative to our melee; our melee stepped or stood), the
    distance after, and whether our melee swung that tick; then the mirror with the sides swapped. Measured on
    Coldkimchi#1 (match 405): his creep at two of our melee stepped away 60 % of the time and our melee swung at one
    only 19 % of its melee-ticks — the dance that a stand without a retreating block never shows."""
    doc, meta, names = load(args.replay)
    US = our_side(meta, args.us)
    fr = [(k, {cid: dict(c) for cid, c in now.items()}, acts) for k, start, now, acts, raw in ticks(doc) if args.t0 <= k <= args.t1 + 1]
    def side_of(c, us): return c['side'] == US if us else c['side'] != US
    for label, us_melee in (("OUR melee at HIS creeps", True), ("HIS melee at OUR creeps", False)):
        moves = Counter(); after = Counter(); swung = Counter(); n = 0; pairs = Counter()
        for i in range(len(fr) - 1):
            k, now, acts = fr[i]; k2, nxt, acts2 = fr[i + 1]
            if k2 != k + 1: continue
            for mid, m in now.items():
                if not (side_of(m, us_melee) and m['role'] == 'melee' and armed(m)): continue
                foes = [(crng(m, c), cid, c) for cid, c in now.items() if not side_of(c, us_melee) and c['role'] in ('melee', 'ranged', 'healer')]
                if not foes: continue
                d, cid, c = min(foes, key=lambda t: t[0])
                if d > 2: continue
                n += 1
                m2 = nxt.get(mid); c2 = nxt.get(cid)
                if not m2 or not c2: after['gone'] += 1; continue
                d_before = crng(m, c); d_after_his = crng(m, c2)
                his = 'still' if (c2['x'], c2['y']) == (c['x'], c['y']) else 'away' if d_after_his > d_before else 'toward' if d_after_his < d_before else 'side'
                ours = 'stepped' if (m2['x'], m2['y']) != (m['x'], m['y']) else 'stood'
                moves[(his, ours)] += 1
                after[crng(m2, c2)] += 1
                if 'a' in acts.get(mid, []): swung[d] += 1
                pairs[d] += 1
        print(f"{label}, t={args.t0}..{args.t1}: {n} melee-ticks with a target within two — at 1: {pairs[1]}, at 2: {pairs[2]}; swings at 1: {swung[1]}, at 2: {swung[2]}")
        print("  his creep / our melee by the next tick: " + ', '.join(f"{h}/{o} {v}" for (h, o), v in moves.most_common()))
        print("  distance the next tick: " + ', '.join(f"{d}:{v}" for d, v in sorted(after.items(), key=lambda t: str(t[0]))))


def cmd_persist(args):
    """Target persistence of each side's ranged fire: per tick the most-shot enemy creep (single-target shots), the share
    of firing ticks where it is the same creep as the previous firing tick's, the mean run on one creep, and for the
    creep under most fire whether a healer of its side was adjacent. Measured on Coldkimchi#1 (match 405): his fire
    stayed on one creep of ours 71 % of ticks against our 45 %, and his most-shot creep had a healer adjacent 90 % of
    the time against our 35 % — persistence is worth nothing when the target is healed, and everything when it is not."""
    stat = {}
    doc, meta, names = load(args.replay)
    US = our_side(meta, args.us)
    for side in (True, False):
        stat[side] = dict(prev=None, same=0, ticks=0, runs=[], run=0, healed_adj=0, shots=0)
    for k, start, now, acts, raw in ticks(doc):
        if k < args.t0 or k > args.t1: continue
        prev_at = {(c['x'], c['y']): cid for cid, c in start.items()}
        at = {(c['x'], c['y']): cid for cid, c in now.items()}
        for side in (True, False):
            cnt = Counter()
            for a in raw:
                cid, code = a[0], a[1]
                c = start.get(cid) or now.get(cid)
                if not c or (c['side'] == US) != side: continue
                if code == 'r' and len(a) >= 4:
                    tid = prev_at.get((a[2], a[3])) or at.get((a[2], a[3]))
                    if tid: cnt[tid] += 1
            st = stat[side]
            if not cnt: continue
            tgt, n = cnt.most_common(1)[0]
            st['ticks'] += 1; st['shots'] += sum(cnt.values())
            if st['prev'] == tgt: st['same'] += 1; st['run'] += 1
            else:
                if st['run']: st['runs'].append(st['run'])
                st['run'] = 1
            st['prev'] = tgt
            t = now.get(tgt)
            if t and any(h['side'] == t['side'] and h['role'] == 'healer' and h is not t and crng(h, t) <= 1 for h in now.values()): st['healed_adj'] += 1
    for side, label in ((True, 'OUR fire on his creeps'), (False, 'HIS fire on ours')):
        st = stat[side]
        if st['run']: st['runs'].append(st['run'])
        runs = st['runs'] or [0]
        print(f"{label}, t={args.t0}..{args.t1}: firing ticks {st['ticks']}, single-target shots {st['shots']}; the most-shot creep the same as the previous firing tick's in {st['same']}/{max(1, st['ticks'] - 1)} ({100 * st['same'] // max(1, st['ticks'] - 1)} %); runs on one creep: {len(runs)}, mean {sum(runs) / len(runs):.1f}, longest {max(runs)}; a healer of its side adjacent to the most-shot creep {st['healed_adj']}/{st['ticks']} ({100 * st['healed_adj'] // max(1, st['ticks'])} %)")


def cmd_brawl(args):
    """His melee at our creeps: for every tick where one of his armed melee is adjacent to one of ours — the victim's
    role, the distance from that melee to OUR nearest armed melee, whether our side swung that tick and whether one of
    our melee was adjacent to the attacker (a swing was possible); then the mirror. Measured on けろびー#3 (match 419):
    his melee ate our ranged and healers while our nearest melee stood five and more away in most of those ticks."""
    doc, meta, names = load(args.replay)
    US = our_side(meta, args.us)
    def side_is(c, us): return (c['side'] == US) == us
    for us_attacker, label in ((False, 'HIS melee adjacent to OURS'), (True, 'OUR melee adjacent to HIS')):
        victims = Counter(); dist = Counter(); tk = 0; ct = 0; swung = 0; could = 0; swings_all = 0
        for k, start, now, acts, raw in ticks(doc):
            if k < args.t0 or k > args.t1: continue
            att = [c for cid, c in start.items() if side_is(c, us_attacker) and c['role'] == 'melee' and armed(c)]
            vic = [c for cid, c in start.items() if not side_is(c, us_attacker) and c['role'] in ('melee', 'ranged', 'healer')]
            vic_melee = [c for c in vic if c['role'] == 'melee' and armed(c)]
            swingers = [cid for cid, codes in acts.items() if 'a' in codes and cid in start]
            my_sw = sum(1 for cid in swingers if side_is(start[cid], us_attacker))
            their_sw = sum(1 for cid in swingers if not side_is(start[cid], us_attacker))
            swings_all += my_sw
            pairs = [(a, v) for a in att for v in vic if crng(a, v) <= 1]
            if not pairs: continue
            tk += 1; ct += len(pairs)
            for a, v in pairs:
                victims[v['role']] += 1
                d = min((crng(a, m) for m in vic_melee), default=9)
                dist[min(d, 5)] += 1
            if their_sw: swung += 1
            if any(crng(a, m) <= 1 for a, v in pairs for m in vic_melee): could += 1
        print(f"{label}, t={args.t0}..{args.t1}: {tk} ticks with adjacency, {ct} adjacent pairs; victims by role: " + ', '.join(f"{r}:{n}" for r, n in victims.most_common()) +
              f"; the victim side's nearest armed melee to that melee at 1:{dist[1]} 2:{dist[2]} 3:{dist[3]} 4:{dist[4]} 5+:{dist[5]}; the victim side swung in {swung}/{tk} of those ticks, had a melee adjacent to the attacker in {could}/{tk}; attacker side's swings over the window {swings_all}")


def cmd_bodies(args):
    """Both sides' creeps at the ticks given (--at t1,t2,...): role, hits, body with the real part order, position — the
    question "who was still armed and who could heal at tick N", which the console log answers only for our side."""
    doc, meta, names = load(args.replay)
    US = our_side(meta, args.us)
    want = set(int(x) for x in args.at.split(','))
    for k, start, now, acts, raw in ticks(doc):
        if k not in want: continue
        for side, label in ((US, 'OURS'), (1 - US, 'HIS ')):
            cs = [c for c in now.values() if c['side'] == side and c['role'] != 'scout']
            heal = sum(1 for c in cs if 'h' in c['body'] and c['hits'] > 100 * c['tail'])
            print(f"t={k} {label} n={len(cs)} hits={sum(c['hits'] for c in cs)} with-heal={heal}: " + ' '.join(f"{c['role'][0]}{c['hits']}/{c['body']}@{c['x']},{c['y']}" for c in sorted(cs, key=lambda c: c['role'])))


def cmd_swings(args):
    """The melee's swing choice: for every attack of each side's melee, the victim's role and armed state against the best
    adjacent enemy (an armed melee adjacent / an armed ranged adjacent / neither). Measured on MetalicaX#2 (match 19830e,
    12 v 12 at D5): our melee with his armed melee adjacent swung at his ranged 16 of 27 times, his at our melee 17 of 30, and
    24 of his 74 swings finished disarmed creeps of ours; the strike-melee-first cut this suggested lost on the stand (v134)."""
    doc, meta, names = load(args.replay)
    US = our_side(meta, args.us)
    tab = {s: Counter() for s in (0, 1)}; n = {s: 0 for s in (0, 1)}
    for k, start, now, acts, raw in ticks(doc):
        if k < args.t0 or k > args.t1 or not start: continue
        at = {(c['x'], c['y']): cid for cid, c in start.items()}
        for a in raw:
            cid, code = a[0], a[1]
            if code != 'a' or cid not in start or len(a) < 4: continue
            m = start[cid]
            if m['role'] != 'melee': continue
            tid = at.get((a[2], a[3]))
            if tid is None or tid not in start: continue
            v = start[tid]
            adj = [c for c in start.values() if c['side'] != m['side'] and crng(m, c) <= 1 and c['role'] in ('melee', 'ranged', 'healer')]
            best = 'armed melee adj' if any(c['role'] == 'melee' and armed(c) for c in adj) else 'armed ranged adj' if any(c['role'] == 'ranged' and armed(c) for c in adj) else 'neither'
            hit = v['role'] + ('' if v['role'] == 'healer' or armed(v) else '-disarmed')
            tab[m['side']][(hit, best)] += 1; n[m['side']] += 1
    for s in (0, 1):
        label = 'OUR melee' if s == US else 'HIS melee'
        print(f"{label}, t={args.t0}..{args.t1}: {n[s]} swings")
        for (hit, best), v in sorted(tab[s].items(), key=lambda t: -t[1]): print(f"   hit {hit:16} | best adjacent: {best:17} {v}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    for name, fn, need_window in (('summary', cmd_summary, False), ('silent', cmd_silent, True), ('focus', cmd_focus, True), ('trace', cmd_trace, True),
                                  ('choice', cmd_choice, False), ('track', cmd_track, False),
                                  ('economy', cmd_economy, False), ('spawns', cmd_spawns, False),
                                  ('scenario', cmd_scenario, False),
                                  ('block', cmd_block, True), ('dance', cmd_dance, True), ('persist', cmd_persist, True),
                                  ('brawl', cmd_brawl, True), ('bodies', cmd_bodies, False), ('swings', cmd_swings, True)):
        p = sub.add_parser(name)
        p.add_argument('replay')
        if need_window:
            p.add_argument('t0', type=int); p.add_argument('t1', type=int)
        else:
            p.add_argument('t0', type=int, nargs='?', default=0); p.add_argument('t1', type=int, nargs='?', default=10 ** 9)
        p.add_argument('--us', default='temik911', help='username prefix of our side')
        if name == 'silent': p.add_argument('-n', type=int, default=12, help='examples of our own silent armed creep-ticks to print')
        if name == 'trace': p.add_argument('--side', choices=('us', 'them'), default='us')
        if name == 'track': p.add_argument('--step', type=int, default=50, help='ticks between position lines')
        if name == 'economy': p.add_argument('--step', type=int, default=200, help='ticks between curve lines')
        if name == 'scenario': p.add_argument('--out', help='where to write the scenario json')
        if name == 'block': p.add_argument('--pics', help='ticks to draw both blocks at, comma-separated')
        if name == 'bodies': p.add_argument('--at', required=True, help='ticks to list both sides at, comma-separated')
        p.set_defaults(fn=fn)
    args = ap.parse_args()
    args.fn(args)


if __name__ == '__main__':
    main()
