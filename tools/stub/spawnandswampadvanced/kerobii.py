#!/usr/bin/env python3
"""The measurement behind the `kerobii` / `kerobii22` personas' army (run.mjs): what けろびー#19-#23's armed creeps do, read
off every stored replay of his games against us.

  python3 kerobii.py                      # his games #19-#23 of `tools/match-log.py list --arena spawn-and-swamp-advanced`
  python3 kerobii.py --versions 22,23     # some of them
  python3 kerobii.py <replay>...          # these replays (a path, or an id prefix in ./replays/ or ~/ScreepsArena/replays/)

One pass a replay, four tables at the end (the numbers the persona's comments quote, 30.09.2026: 68 replays):
  depart   — birth (the first tick not spawning) to the first tick farther than 12 from every spawn of his; his armed within
             6 of it then (a ball waiting at home would show hundreds of ticks and company);
  standoff — the range from each armed creep of his out of home to our nearest structure (spawn, tower, rampart), split by
             a fed tower of ours (energy >= 10) standing within 20 of it or not; and whom his fire went at;
  fire     — per creep-tick of a ranged creep of his with something of ours in three (start-of-tick positions; a creep of
             ours under a rampart counts as a structure): a mass attack, a single shot or nothing, by whether anything of
             ours stood adjacent; and for his single shots and swings with two or more of our creeps in reach, which rule
             names the one he hit (the lowest share of its hits, the lowest hits, armed first then the lowest hits, the
             nearest);
  engage   — for each fighter of his (by body) with a creep of ours within 5, the range to the nearest one, by whether
             that creep is armed and whether it stands under a rampart of ours;
  strength — every armed creep of his that could step with our nearest armed creep 4-7 off: whether it stepped closer,
             stayed or stepped away, by R = the damage of his armed within 6 of it over that of ours within 6 of our one,
             and by a fed tower of ours within 20;
  target   — every 25 ticks, his largest group out of home (3+, linked within 6) standing within 25 of one of our bases: is
             that base our youngest one, the one nearest his first spawn, nearest any spawn of his, nearest the centroid of
             his spawns, our oldest one? A base: what of ours stands within 3 of a source (spawn, tower, rampart, any site)
             or inside a vault frame;
  push     — his largest group coming within 14 of our nearest fed tower and staying 10 ticks ("push") against it standing
             at 15-26 from one (a "hold" sample every 25 ticks): the state of each, to see what separates them;
  defend   — with 3+ of our armed within 12 of a spawn of his, his armed creeps farther than 20 from it that could step: the
             share stepping toward it, against the same share without such an attack.
"""
import glob
import gzip
import json
import os
import re
import statistics
import subprocess
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
US = os.environ.get('US', 'temik911')
SOURCES = [(1, 32), (32, 98), (49, 22), (50, 77), (67, 1), (98, 67)]
VAULTS = [((38, 46), (27, 36)), ((53, 61), (63, 72))]


def find(arg):
    if os.path.exists(arg):
        return arg
    for d in (os.path.join(HERE, 'replays'), os.path.expanduser('~/ScreepsArena/replays')):
        hits = glob.glob(os.path.join(d, arg + '*.replay.json.gz'))
        if hits:
            return hits[0]
    return None


def his_games(versions):
    out = subprocess.run([sys.executable, os.path.join(HERE, '..', '..', 'match-log.py'), 'list', '--arena', 'spawn-and-swamp-advanced', '--limit', '400'],
                         capture_output=True, text=True).stdout
    ids = []
    for l in out.split('\n'):
        m = re.search(r'\s([0-9a-f]{24})\s.*vs けろびー#(\d+)', l)
        if m and int(m.group(2)) in versions and find(m.group(1)):
            ids.append(m.group(1))
    return ids


def body_parts(body):
    out = []
    for k, n in re.findall(r'([a-z])(\d+)', body):
        out += [k] * int(n)
    return out


def alive(body, hits, kind):
    ps = body_parts(body)
    n = len(ps)
    return sum(1 for i, p in enumerate(ps) if p == kind and hits > 100 * (n - 1 - i))


def dps(c):
    return alive(c['body'], c['hits'], 'a') * 30 + alive(c['body'], c['hits'], 'r') * 10


def armed(c):
    return any(k in 'arh' for k in body_parts(c['body']))


def xy(a):
    return (a['x'], a['y']) if isinstance(a, dict) else a


def rng(a, b):
    a, b = xy(a), xy(b)
    return max(abs(a[0] - b[0]), abs(a[1] - b[1]))


def largest_group(cs, link=6):
    cs = list(cs)
    seen, best = set(), []
    for i in range(len(cs)):
        if i in seen:
            continue
        g, k = [i], 0
        seen.add(i)
        while k < len(g):
            for j in range(len(cs)):
                if j not in seen and rng(cs[g[k]], cs[j]) <= link:
                    seen.add(j)
                    g.append(j)
            k += 1
        if len(g) > len(best):
            best = g
    return [cs[x] for x in best]


def base_of(x, y):
    for vi, ((x0, x1), (y0, y1)) in enumerate(VAULTS):
        if x0 < x < x1 and y0 < y < y1:
            return 'V%d' % vi
    s = min(SOURCES, key=lambda s: rng(s, (x, y)))
    return s if rng(s, (x, y)) <= 3 else None


def centre(b):
    if isinstance(b, str):
        (x0, x1), (y0, y1) = VAULTS[int(b[1])]
        return ((x0 + x1) // 2, (y0 + y1) // 2)
    return b


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    versions = {19, 20, 21, 22, 23}
    if '--versions' in sys.argv:
        versions = {int(v) for v in sys.argv[sys.argv.index('--versions') + 1].split(',')}
        args = [a for a in args if a != sys.argv[sys.argv.index('--versions') + 1]]
    replays = [find(a) for a in args] if args else [find(i) for i in his_games(versions)]
    replays = [r for r in replays if r]
    waits, company = [], Counter()
    standoff = {True: Counter(), False: Counter()}
    fire = Counter()
    choice = Counter()
    engage = {}
    strength = {}
    rule = Counter()
    tgt = Counter()
    pushes, holds = [], []
    drift = {True: Counter(), False: Counter()}
    for path in replays:
        d = json.load(gzip.open(path, 'rt'))
        us = [p['side'] for p in d['meta']['players'] if p['username'].startswith(US)][0]
        objs = {o['id']: o for o in d['objects']}
        cr, st = {}, {}
        born_base, alive_base = {}, {}
        seen_born, left = {}, set()
        his_first = None
        state, below = None, 0
        prev = None
        for t in d['ticks']:
            k = t['k']
            start = {i: dict(c) for i, c in cr.items()}
            st0 = {i: dict(o) for i, o in st.items()}
            ramps0 = {xy(o) for o in st.values() if o['kind'] == 'rampart' and o['side'] == us}
            ours_st0 = [xy(o) for o in st.values() if o['side'] == us]
            for n in t.get('n', []):
                cr[n[0]] = dict(id=n[0], side=n[1], x=n[2], y=n[3], hits=n[4], body=n[6], sp=bool(n[7]), f=0)
            for u in t.get('u', []):
                if u[0] in cr:
                    cr[u[0]].update(x=u[1], y=u[2], hits=u[3], f=u[4], sp=bool(u[5]))
            for b in t.get('b', []):
                if b[0] in cr:
                    cr[b[0]]['body'] = b[1]
            for x in t.get('x', []):
                cr.pop(x, None)
            for sid, h, e in t.get('s', []):
                o = objs.get(sid)
                if not o:
                    continue
                if o['kind'] in ('spawn', 'tower', 'rampart'):
                    if h > 0:
                        st[sid] = dict(kind=o['kind'], side=o['side'], x=o['x'], y=o['y'], e=e)
                    else:
                        st.pop(sid, None)
                if o['side'] == us and o['kind'] in ('constructionSite', 'rampart', 'spawn', 'tower'):
                    b = base_of(o['x'], o['y'])
                    if b is not None:
                        born_base.setdefault(b, k)
                        alive_base[b] = k
            hs = [o for o in st.values() if o['kind'] == 'spawn' and o['side'] != us]
            if his_first is None and hs:
                his_first = xy(hs[0])
            ours_st = [o for o in st.values() if o['side'] == us]
            fed = [o for o in ours_st if o['kind'] == 'tower' and o['e'] >= 10]
            ha = [c for c in cr.values() if c['side'] != us and not c['sp'] and armed(c)]
            oa = [c for c in cr.values() if c['side'] == us and not c['sp'] and armed(c)]
            home = lambda c: min((rng(s, c) for s in hs), default=99)
            out = [c for c in ha if home(c) > 12]
            # depart
            for c in ha:
                seen_born.setdefault(c['id'], k)
                if c['id'] not in left and home(c) > 12:
                    left.add(c['id'])
                    waits.append(k - seen_born[c['id']])
                    m = sum(1 for q in ha if rng(q, c) <= 6)
                    company['alone' if m == 1 else 'with 1' if m == 2 else 'with 2+'] += 1
            # standoff and fire
            for c in out:
                if ours_st:
                    standoff[any(rng(tw, c) <= 20 for tw in fed)][min(min(rng(o, c) for o in ours_st), 30) // 3 * 3] += 1
            ids = {c['id'] for c in ha}
            cells_c = {xy(c) for c in cr.values() if c['side'] == us}
            cells_s = {xy(o) for o in ours_st}
            for a in t.get('a', []):
                if a[0] in ids and a[1] in ('r', 'a'):
                    fire['at our creeps' if (a[2], a[3]) in cells_c else 'at our structures' if (a[2], a[3]) in cells_s else 'elsewhere'] += 1
                elif a[0] in ids and a[1] == 'R':
                    fire['mass attacks'] += 1
            # fire choice (start-of-tick positions; a creep that died this tick has no actions in the record)
            acts = {}
            for a in t.get('a', []):
                acts.setdefault(a[0], []).append(a)
            exposed = [o for o in start.values() if o['side'] == us and not o['sp'] and xy(o) not in ramps0]
            share = lambda o: o['hits'] / (100 * len(body_parts(o['body'])))
            for cid, c in start.items():
                if c['side'] == us or c['sp'] or cid not in cr:
                    continue
                mine = acts.get(cid, [])
                if alive(c['body'], c['hits'], 'r') > 0:
                    near3 = any(rng(o, c) <= 3 for o in exposed) or any(rng(s, c) <= 3 for s in ours_st0)
                    if near3:
                        adj = any(rng(o, c) <= 1 for o in exposed) or any(rng(s, c) <= 1 for s in ours_st0)
                        codes = {a[1] for a in mine}
                        choice[('adjacent' if adj else 'none adjacent', 'mass' if 'R' in codes else 'single' if 'r' in codes else 'nothing')] += 1
                for a in mine:
                    if a[1] not in ('r', 'a') or len(a) < 4:
                        continue
                    cand = [o for o in exposed if rng(o, c) <= (3 if a[1] == 'r' else 1)]
                    hit = [o for o in cand if xy(o) == (a[2], a[3])]
                    if len(cand) < 2 or not hit:
                        continue
                    rule[(a[1], 'n')] += 1
                    for name, key in (('lowest share of hits', share), ('lowest hits', lambda o: o['hits']),
                                      ('armed first, then lowest hits', lambda o: (0 if dps(o) > 0 else 1, o['hits'])), ('nearest', lambda o: rng(o, c))):
                        m = min(key(o) for o in cand)
                        ties = [o for o in cand if key(o) == m]
                        if hit[0] in ties:
                            rule[(a[1], name)] += 1 / len(ties)
            # strength (start-of-tick positions, the step over the tick)
            fed0 = [xy(o) for o in st0.values() if o['kind'] == 'tower' and o['side'] == us and o['e'] >= 10]
            oa0 = [o for o in start.values() if o['side'] == us and not o['sp'] and dps(o) > 0]
            ha0 = [o for o in start.values() if o['side'] != us and not o['sp'] and armed(o)]
            for cid, c in start.items():
                if c['side'] == us or c['sp'] or dps(c) == 0 or c['f'] > 0 or cid not in cr or not oa0:
                    continue
                o = min(oa0, key=lambda o: rng(o, c))
                r0 = rng(o, c)
                if not 4 <= r0 <= 7:
                    continue
                R = sum(dps(q) for q in ha0 if rng(q, c) <= 6) / max(1, sum(dps(q) for q in oa0 if rng(q, o) <= 6))
                b = next(x for x in (0.5, 1, 1.5, 2, 2.5, 3, 4, 1e9) if R <= x)
                r1 = rng(o, cr[cid])
                strength.setdefault((any(rng(f, c) <= 20 for f in fed0), b), Counter())['closer' if r1 < r0 else 'same' if r1 == r0 else 'away'] += 1
            # engage (end-of-tick positions)
            ramps1 = {xy(o) for o in st.values() if o['kind'] == 'rampart' and o['side'] == us}
            ours_c = [o for o in cr.values() if o['side'] == us and not o['sp']]
            for c in ha:
                if dps(c) == 0:
                    continue
                near = [o for o in ours_c if rng(o, c) <= 5]
                if near:
                    o = min(near, key=lambda o: rng(o, c))
                    key = (c['body'], 'armed' if dps(o) > 0 else 'worker', 'under a rampart' if xy(o) in ramps1 else 'in the open')
                    engage.setdefault(key, Counter())[rng(o, c)] += 1
            # target
            G = largest_group(out) if out else []
            if k % 25 == 0 and len(G) >= 3:
                live = [b for b in born_base if k - alive_base[b] <= 50]
                if live:
                    C = (sum(c['x'] for c in G) / len(G), sum(c['y'] for c in G) / len(G))
                    near = min(live, key=lambda b: rng(centre(b), C))
                    if rng(centre(near), C) <= 25:
                        tgt['n'] += 1
                        tgt['our youngest base'] += near == max(live, key=lambda b: born_base[b])
                        tgt['our oldest base'] += near == min(live, key=lambda b: born_base[b])
                        if his_first:
                            tgt['nearest his first spawn'] += near == min(live, key=lambda b: rng(centre(b), his_first))
                        if hs:
                            tgt['nearest any spawn of his'] += near == min(live, key=lambda b: min(rng(centre(b), s) for s in hs))
                            hc = (sum(s['x'] for s in hs) / len(hs), sum(s['y'] for s in hs) / len(hs))
                            tgt['nearest the centroid of his spawns'] += near == min(live, key=lambda b: rng(centre(b), hc))
            # push / hold
            if G and fed:
                C = (round(sum(c['x'] for c in G) / len(G)), round(sum(c['y'] for c in G) / len(G)))
                dT = min(rng(tw, C) for tw in fed)
                row = dict(g=len(G), gd=sum(dps(c) for c in G), od=sum(dps(c) for c in oa if rng(c, C) <= 15), oa=len(oa))
                if state is None:
                    state = 'in' if dT <= 14 else 'out'
                if state == 'out' and dT <= 14:
                    below += 1
                    if below >= 10:
                        pushes.append(row)
                        state, below = 'in', 0
                elif state == 'out':
                    below = 0
                if state == 'in' and dT >= 20:
                    state = 'out'
                if state == 'out' and 14 < dT <= 26 and k % 25 == 0:
                    holds.append(row)
            # defend
            hpos = {c['id']: (xy(c), c['f']) for c in ha}
            if prev is not None and hs:
                phs, poa, pha = prev
                attacked = [s for s in phs if sum(1 for o in poa if rng(o, s) <= 12) >= 3]
                for cid, (p, f) in pha.items():
                    if f > 0 or cid not in hpos:
                        continue
                    S = min(attacked or phs, key=lambda s: rng(s, p))
                    r0 = rng(S, p)
                    if r0 <= 20:
                        continue
                    r1 = rng(S, hpos[cid][0])
                    drift[bool(attacked)]['toward' if r1 < r0 else 'same' if r1 == r0 else 'away'] += 1
            prev = ([xy(s) for s in hs], [xy(c) for c in oa], hpos)
    print(f"{len(replays)} replays")
    if waits:
        waits.sort()
        print(f"depart: {len(waits)} armed creeps of his; birth to 12 cells off his spawns: median {statistics.median(waits)} ticks, "
              f"p10 {waits[len(waits) // 10]}, p90 {waits[9 * len(waits) // 10]}; with his armed within 6 as they leave: {dict(company)}")
    for f in (False, True):
        h = standoff[f]
        n = max(1, sum(h.values()))
        print(f"standoff, {'a' if f else 'no'} fed tower of ours within 20 ({sum(h.values())} creep-ticks), range to our nearest structure: "
              + ' '.join(f"{b}-{b + 2}:{100 * h[b] // n}%" for b in sorted(h)))
    print('fire:', dict(fire))
    for adj in ('adjacent', 'none adjacent'):
        n = max(1, sum(v for (a, _), v in choice.items() if a == adj))
        print(f"fire, something of ours {adj:13} ({n} ranged creep-ticks): " + ', '.join(f"{w} {100 * choice[(adj, w)] // n}%" for w in ('mass', 'single', 'nothing')))
    for kind, label in (('r', 'single shots'), ('a', 'swings')):
        n = max(1, rule[(kind, 'n')])
        print(f"fire, {label} with 2+ of our creeps in reach ({rule[(kind, 'n')]}): the one hit is " + ', '.join(
            f"{name} {100 * rule[(kind, name)] / n:.0f}%" for name in ('lowest share of hits', 'lowest hits', 'armed first, then lowest hits', 'nearest')))
    for key in sorted(engage):
        c = engage[key]
        m = max(1, sum(c.values()))
        print(f"engage, {key[0]} with our {key[1]} {key[2]} the nearest within 5 ({sum(c.values())}): range " + ' '.join(f"{r}:{100 * c[r] // m}%" for r in range(1, 6)))
    for key in sorted(strength):
        c = strength[key]
        m = max(1, sum(c.values()))
        print(f"strength, {'a' if key[0] else 'no'} fed tower of ours within 20, R <= {key[1] if key[1] < 1e9 else 'inf'} ({sum(c.values())}): "
              f"closer {100 * c['closer'] // m}%, same {100 * c['same'] // m}%, away {100 * c['away'] // m}%, net {100 * (c['closer'] - c['away']) // m:+d}%")
    n = max(1, tgt['n'])
    print(f"target: {tgt['n']} samples of his largest group (3+) within 25 of one of our bases; that base is "
          + ', '.join(f"{k} {100 * v // n}%" for k, v in tgt.items() if k != 'n'))

    def dist(rows, f, bins):
        c = Counter()
        for r in rows:
            v = f(r)
            c[next((b for b in bins if v <= b), 'more')] += 1
        m = max(1, len(rows))
        return ' '.join(f"<={b}:{100 * c[b] // m}%" for b in bins) + f" more:{100 * c['more'] // m}%"
    print(f"push ({len(pushes)}) against hold ({len(holds)}):")
    for name, f, bins in [('his group', lambda r: r['g'], [2, 3, 4, 6, 9]), ('its dps', lambda r: r['gd'], [100, 150, 200, 300, 500]),
                          ('our dps within 15', lambda r: r['od'], [0, 50, 100, 200, 400]), ('our armed in all', lambda r: r['oa'], [0, 1, 2, 4, 8])]:
        print(f"  {name:18} push {dist(pushes, f, bins)}")
        print(f"  {'':18} hold {dist(holds, f, bins)}")
    for a in (True, False):
        c = drift[a]
        m = max(1, sum(c.values()))
        print(f"defend: {'3+ of ours at a spawn of his' if a else 'no attack':30} ({sum(c.values())} creep-ticks farther than 20 from it): "
              f"toward {100 * c['toward'] // m}%, same {100 * c['same'] // m}%, away {100 * c['away'] // m}%")


if __name__ == '__main__':
    main()
