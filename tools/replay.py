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
import argparse, gzip, json, re, sys
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
    """Yields (k, start, now, acts) per tick: creeps before this tick's updates (what intents were judged on), after
    them, and this tick's actions as {creep id: [codes]} plus the raw action list."""
    creeps = {}
    for tick in doc['ticks']:
        k = tick['k']
        start = {cid: dict(c) for cid, c in creeps.items()}
        for n in tick.get('n', []):
            cid, side, x, y, hits, hits_max, body, spawning = n
            creeps[cid] = dict(side=side, x=x, y=y, hits=hits, hitsMax=hits_max, body=body, role=role(body), tail=tail(body))
        for u in tick.get('u', []):
            cid, x, y, hits, fatigue, spawning = u
            if cid in creeps: creeps[cid].update(x=x, y=y, hits=hits)
        for b in tick.get('b', []):
            cid, body = b
            if cid in creeps: creeps[cid].update(body=body, role=role(body), tail=tail(body))
        for cid in tick.get('x', []):
            creeps.pop(cid, None)
        acts = {}
        for a in tick.get('a', []):
            acts.setdefault(a[0], []).append(a[1])
        yield k, start, creeps, acts, tick.get('a', [])


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


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    for name, fn, need_window in (('summary', cmd_summary, False), ('silent', cmd_silent, True), ('focus', cmd_focus, True), ('trace', cmd_trace, True),
                                  ('choice', cmd_choice, False), ('track', cmd_track, False)):
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
        p.set_defaults(fn=fn)
    args = ap.parse_args()
    args.fn(args)


if __name__ == '__main__':
    main()
