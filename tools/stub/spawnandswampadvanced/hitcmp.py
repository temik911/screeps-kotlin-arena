#!/usr/bin/env python3
"""Hits tick by tick, the stub against the replay: where a creep's hits part, and what hit it on each side.

  REPLAY=<id> HITS=a-b node --import ./register.mjs run.mjs <b> ghost > out/hits.txt
  python3 hitcmp.py <game id or prefix> out/hits.txt [--all] [--id ID]...

The stub's lines are `hitdump t=N <record id> <owner> x y hits body` (every creep after tick N) and `hitlog t=N <src> <target>
<how> <amount> ...` (every hit and heal the engine dealt on tick N; a heal is negative). The replay's side of each tick is
its creeps' hits after the tick and its actions: `r`/`a`/`h`/`H` with their target's cell at the START of the tick (World's
actionLog is written while the intents are processed, before the movement), `R` a mass attack, a tower's `a`/`h`. The damage
of a recorded action is the engine's rule applied to the recorded body and hits at the start of the tick (parts alive from
the tail, 10 a RANGED_ATTACK, 30 an ATTACK, 12/4 a HEAL, a tower by range) — so a line where the stub's and the record's
lists of hits agree but the hits differ is an engine rule, and a line where the lists differ is a fire choice.
By default prints only the creeps whose hits differ (or whose hit lists differ) on a tick; --all prints every creep hit.
"""
import gzip
import json
import os
import re
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
US = os.environ.get('US', 'temik911')
PART = {'m': 'move', 'w': 'work', 'c': 'carry', 'a': 'attack', 'r': 'ranged_attack', 'h': 'heal', 't': 'tough'}


def find_replay(arg):
    import glob
    for d in (os.path.join(HERE, 'replays'), os.path.expanduser('~/ScreepsArena/replays')):
        hits = glob.glob(os.path.join(d, arg + '*.replay.json.gz'))
        if len(hits) == 1:
            return hits[0]
    sys.exit(f'hitcmp: no single replay {arg}*')


def parts(body):
    out = []
    for k, n in re.findall(r'([a-z])(\d+)', body):
        out += [PART[k]] * int(n)
    return out


def alive(body, hits, kind):
    ps = parts(body)
    n = len(ps)
    return sum(1 for i, p in enumerate(ps) if p == kind and hits > 100 * (n - 1 - i))


def tower_power(power, r):
    # the stub's constants (game/constants.mjs): full at range 1, then -1/20 of it a cell to zero at 21
    if r <= 1:
        return power
    return round(power * (1 - (min(r, 21) - 1) / 20))


def rng(a, b):
    return max(abs(a[0] - b[0]), abs(a[1] - b[1]))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    show_all = '--all' in sys.argv
    only = set()
    for i, a in enumerate(sys.argv):
        if a == '--id':
            only.add(sys.argv[i + 1])
    doc = json.load(gzip.open(find_replay(args[0]), 'rt', encoding='utf-8'))
    us = next(p['side'] for p in doc['meta']['players'] if str(p['username']).startswith(US))
    objs = {o['id']: o for o in doc['objects']}
    # the stub
    sd = defaultdict(dict)   # t -> id -> (owner, x, y, hits, body)
    sl = defaultdict(list)   # t -> [(src, tgt, how, amount, where)]
    for l in open(args[1], encoding='utf-8'):
        if l.startswith('hitdump '):
            _, t, i, o, x, y, h, b = l.split()[:8]
            sd[int(t[2:])][i] = (int(o), int(x), int(y), int(h), b)
        elif l.startswith('hitlog '):
            f = l.split()
            sl[int(f[1][2:])].append((f[2], f[3], f[4], int(f[5]), f[6] if len(f) > 6 else ''))
    if not sd:
        sys.exit('hitcmp: no hitdump lines (run with HITS=a-b)')
    t0, t1 = min(sd), max(sd)
    # the record
    creeps = {}
    towers = {o['id']: o for o in doc['objects'] if o['kind'] == 'tower'}
    ramps = set()   # cells with a standing rampart (a mass attack skips what stands under one)
    for tick in doc['ticks']:
        k = tick['k']
        start = {cid: dict(c) for cid, c in creeps.items()}
        ramps_start = set(ramps)
        for sid, sh, se in tick.get('s', []):
            o = objs.get(sid)
            if o and o['kind'] == 'rampart':
                (ramps.add if sh > 0 else ramps.discard)((o['x'], o['y']))
        for n in tick.get('n', []):
            cid, side, x, y, hits, hm, body, sp = n
            creeps[cid] = dict(side=side, x=x, y=y, hits=hits, body=body, sp=bool(sp))
        for u in tick.get('u', []):
            cid, x, y, hits, fat, sp = u
            if cid in creeps:
                creeps[cid].update(x=x, y=y, hits=hits, sp=bool(sp))
        for b in tick.get('b', []):
            if b[0] in creeps:
                creeps[b[0]]['body'] = b[1]
        gone = set(tick.get('x', []))
        if k < t0:
            for cid in gone:
                creeps.pop(cid, None)
            continue
        if k > t1:
            break
        # the record's hits on each creep this tick, by the engine's rule on the start-of-tick state
        rl = defaultdict(list)
        acts = tick.get('a', [])
        for a in acts:
            src, code = a[0], a[1]
            if code not in ('r', 'a', 'h', 'H', 'R'):
                continue
            if src in start:
                s = start[src]
                if code == 'R':
                    for cid, c in start.items():
                        if c['side'] == s['side'] or (c['x'], c['y']) in ramps_start:
                            continue
                        d = rng((s['x'], s['y']), (c['x'], c['y']))
                        if d <= 3:
                            rl[cid].append((src, 'R', round(alive(s['body'], s['hits'], 'ranged_attack') * 10 * [1, 1, 0.4, 0.1][d]), f"{s['x']},{s['y']}"))
                    continue
                tgt = [cid for cid, c in start.items() if (c['x'], c['y']) == (a[2], a[3])]
                if not tgt:
                    continue
                if code == 'r':
                    amt = alive(s['body'], s['hits'], 'ranged_attack') * 10
                elif code == 'a':
                    amt = alive(s['body'], s['hits'], 'attack') * 30
                elif code == 'h':
                    amt = -alive(s['body'], s['hits'], 'heal') * 12
                else:
                    amt = -alive(s['body'], s['hits'], 'heal') * 4
                rl[tgt[0]].append((src, code, amt, f"{s['x']},{s['y']}"))
            elif src in towers:
                tw = towers[src]
                tgt = [cid for cid, c in start.items() if (c['x'], c['y']) == (a[2], a[3])]
                if not tgt:
                    continue
                r = rng((tw['x'], tw['y']), (a[2], a[3]))
                rl[tgt[0]].append((f"tower@{tw['x']},{tw['y']}", 't' + code, -tower_power(600, r) if code == 'h' else tower_power(1000, r), ''))
        # the victims' own entries (`A` attacked from, `E` healed from — ONE per creep a tick in World's actionLog)
        vic = defaultdict(list)
        for a in acts:
            if a[1] in ('A', 'E'):
                vic[a[0]].append(f"{a[1]}{a[2]},{a[3]}")
        ids = set(sd.get(k, {})) | {cid for cid in creeps if cid in start or cid in gone}
        rows = []
        for cid in sorted(ids, key=lambda i: (str(i).startswith('s'), str(i))):
            if only and cid not in only:
                continue
            if cid in creeps and creeps[cid].get('sp') and cid not in sd.get(k, {}):
                continue
            st = sd.get(k, {}).get(cid)
            rh = creeps[cid]['hits'] if cid in creeps and cid not in gone else 0
            rb = start.get(cid, {}).get('hits')
            sh = st[3] if st else 0
            slist = sorted((s, h, a) for s, tg, h, a, w in sl.get(k, []) if tg == cid)
            rlist = sorted((s, h, a) for s, h, a, w in rl.get(cid, []))
            if not show_all and sh == rh and not slist and not rlist:
                continue
            if not show_all and sh == rh and slist == rlist:
                continue
            side = ('us' if creeps[cid]['side'] == us else 'him') if cid in creeps else ('us' if st and st[0] == 0 else 'him')
            body = st[4] if st else (creeps[cid]['body'] if cid in creeps else '?')
            cell = f"({st[1]},{st[2]})" if st else ''
            rcell = f"({creeps[cid]['x']},{creeps[cid]['y']})" if cid in creeps else ''
            rows.append(f"  {side:3} {cid:>6} {body:10} stub {sh:5} {cell:9} rec {rh:5}{'†' if cid in gone else ' '}{rcell:9} (rec start {rb})"
                        + f"\n      stub: {' '.join(f'{s}:{h}{a:+d}' for s, h, a in slist) or '-'}"
                        + f"\n      rec:  {' '.join(f'{s}:{h}{a:+d}' for s, h, a in rlist) or '-'}  {' '.join(vic.get(cid, []))}")
        if rows:
            print(f"t={k}")
            print('\n'.join(rows))
        for cid in gone:
            creeps.pop(cid, None)


if __name__ == '__main__':
    main()
