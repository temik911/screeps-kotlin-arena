#!/usr/bin/env python3
"""Escort Run: one match's replay as a digest — who built what, whose economy ran, every creep's life.

The replay is the server's full record (`tools/match-log.py replay <id>` puts it in ~/ScreepsArena/replays/), and it
answers what our console cannot: the enemy's bodies, paths, attacks and harvests, and every structure that appeared
mid-match. Three sections, in the order a match is read:

  1. STRUCTURES — everything on the map that was not there at tick 0 (ramparts, extensions, towers, spawns,
     containers, roads, construction sites, energy piles), with its owner, cell and the tick it appeared; and every
     cell that holds TWO objects at once, because a rampart can stand on top of another structure (the operator,
     27.09.2026: "на одной клетке может стоять и структура и рампарт").
  2. ECONOMY — both spawns' energy every N ticks (the slope is the income), harvests per side and per source, the
     far containers' energy over time (2 x 2500 at the right edge — who drained them and when), dropped piles.
  3. CREEPS — every creep of both sides: body, born, died, a sample of its path with hits, and its action counts
     (a = attack, A = was attacked, r = ranged, h = heal, E = was healed, harvest, build, ...).

    tools/er-replay.py <game id | prefix | path to .replay.json.gz> [--step 25] [--us temik911]
"""
import argparse, collections, glob, gzip, json, os, sys

REPLAYS = os.path.expanduser('~/ScreepsArena/replays')
GAMES = os.path.expanduser('~/ScreepsArena/games')


def find(spec):
    if os.path.isfile(spec):
        return spec
    hits = glob.glob(os.path.join(REPLAYS, spec + '*.replay.json.gz'))
    if len(hits) != 1:
        sys.exit(f"{len(hits)} replays match {spec!r} in {REPLAYS} (fetch with tools/match-log.py replay <id>)")
    return hits[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('game')
    ap.add_argument('--step', type=int, default=25)
    ap.add_argument('--us', default='temik911')
    a = ap.parse_args()
    doc = json.load(gzip.open(find(a.game), 'rt'))
    meta = doc['meta']
    names = {p['side']: f"{p['username']}#{p.get('codeVersion')}" for p in meta['players']}
    # by name alone self-play (a league game: both sides are ours) always labelled side 0 as us; replay.our_side reads
    # the stored match document for which code started the game
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from replay import our_side
    us = our_side(meta, a.us)
    who = lambda side: 'US ' if side == us else ('THM' if side in (0, 1) else '---')
    objs = {o['id']: o for o in doc['objects']}
    flags = {o['side']: (o['x'], o['y']) for o in doc['objects'] if o['kind'] == 'flag'}
    print(f"{meta['gameId']} ticks={meta['ticks']} winner={meta['result'].get('winnerName')} us=side{us}")
    for s in (0, 1):
        print(f"  side{s} {who(s)} {names.get(s)} flag={flags.get(s)}")

    # ---------- replay walk ----------
    creeps, life = {}, {}
    first_seen = {}
    energy = collections.defaultdict(list)  # object id -> [(tick, energy)]
    harvests = collections.Counter()
    builds = collections.Counter()
    for tick in doc['ticks']:
        k = tick['k']
        for n in tick.get('n', []):
            cid, side, x, y, h, hm, body, sp = n
            creeps[cid] = dict(side=side, x=x, y=y, h=h, body=body)
            life[cid] = dict(side=side, body=body, born=k, died=None, path=[(k, x, y, h)], acts=collections.Counter())
        for u in tick.get('u', []):
            cid, x, y, h, f, sp = u
            if cid in creeps:
                creeps[cid].update(x=x, y=y, h=h)
                last = life[cid]['path'][-1]
                if k % a.step == 0 or (h != last[3] and k - last[0] >= 5):
                    life[cid]['path'].append((k, x, y, h))
        for b in tick.get('b', []):
            if b[0] in creeps:
                creeps[b[0]]['body'] = b[1]
        for cid in tick.get('x', []):
            if cid in life:
                life[cid]['died'] = k
                life[cid]['path'].append((k, creeps[cid]['x'], creeps[cid]['y'], 0))
            creeps.pop(cid, None)
        for act in tick.get('a', []):
            cid, code = act[0], act[1]
            if cid in life:
                life[cid]['acts'][code] += 1
                if code == 'harvest':
                    harvests[(life[cid]['side'], tuple(act[2:4]))] += 1
                if code in ('build', 'repair'):
                    builds[(life[cid]['side'], code, tuple(act[2:4]))] += 1
        for s in tick.get('s', []):
            first_seen.setdefault(s[0], k)
            if len(s) > 2:
                energy[s[0]].append((k, s[2]))

    # ---------- 1. structures ----------
    # the map's own objects are numbered before the first creep born in the match (the escorts carry low ids too);
    # a structure that first reports hits late (a rampart hit at t=165) is NOT new — only its first update is
    born = [int(n[0]) for t in doc['ticks'] if t['k'] >= 1 for n in t.get('n', []) if str(n[0]).isdigit()]
    first_new = min(born) if born else 10 ** 9
    initial = {o['id'] for o in doc['objects'] if str(o['id']).isdigit() and int(o['id']) < first_new}
    print("\n== STRUCTURES built or dropped during the match ==")
    new = [o for o in doc['objects'] if o['id'] not in initial and o['kind'] not in ('flag', 'source', 'energy')]
    if not new:
        print("  none")
    for o in sorted(new, key=lambda o: first_seen.get(o['id'], 0)):
        e = energy.get(o['id'])
        print(f"  t={first_seen.get(o['id'], '?'):>4} {who(o['side'])} {o['kind']:<18} ({o['x']},{o['y']}) hits={o['hits']}/{o['hitsMax']}"
              f"{' energy ' + str(e[0][1]) + '->' + str(e[-1][1]) if e else ''}")
    by_cell = collections.defaultdict(list)
    for o in doc['objects']:
        if o['kind'] not in ('flag', 'energy'):
            by_cell[(o['x'], o['y'])].append(o)
    stacked = {c: os_ for c, os_ in by_cell.items() if len(os_) > 1}
    if stacked:
        print("  cells with more than one object (a rampart over a structure):")
        for c, os_ in sorted(stacked.items()):
            print(f"    {c}: " + ", ".join(f"{who(o['side'])} {o['kind']}" for o in os_))

    # ---------- 2. economy ----------
    print(f"\n== ECONOMY (spawn energy every {a.step * 2} ticks; slope = income) ==")
    for o in doc['objects']:
        if o['kind'] == 'spawn' and o['id'] in initial:
            ser = [(t, e) for t, e in energy.get(o['id'], []) if t % (a.step * 2) == 0]
            print(f"  {who(o['side'])} spawn ({o['x']},{o['y']}): " + " ".join(f"{t}:{e}" for t, e in ser[:24]))
    for (side, cell), n in sorted(harvests.items()):
        print(f"  {who(side)} harvested {n} tick(s) at source {cell}")
    for o in doc['objects']:
        if o['kind'] == 'container':
            ser = energy.get(o['id'], [])
            if ser:
                ch = [ser[0]] + [ser[i] for i in range(1, len(ser)) if ser[i][1] != ser[i - 1][1]]
                print(f"  container ({o['x']},{o['y']}) start={o['energy']}: " + " ".join(f"{t}:{e}" for t, e in ch[:20]) + (" ..." if len(ch) > 20 else ""))
            else:
                print(f"  container ({o['x']},{o['y']}) start={o['energy']}: untouched")
    piles = collections.defaultdict(list)
    for o in doc['objects']:
        if o['kind'] == 'energy':
            ser = energy.get(o['id'], [])
            piles[(o['x'], o['y'])].append((first_seen.get(o['id'], 0), max([e for _, e in ser] or [o['energy']])))
    for cell, ps in sorted(piles.items()):
        print(f"  dropped energy at {cell}: {len(ps)} pile(s) t={ps[0][0]}..{ps[-1][0]}, largest {max(e for _, e in ps)}")
    for (side, code, cell), n in sorted(builds.items()):
        print(f"  {who(side)} {code} {n} tick(s) at {cell}")

    # ---------- 3. creeps ----------
    print("\n== CREEPS ==")
    for cid, l in sorted(life.items(), key=lambda kv: (kv[1]['side'] != us, kv[1]['born'])):
        pts = l['path'][::max(1, len(l['path']) // 12)]
        path = ' '.join(f"{k}:({x},{y})h{h}" for k, x, y, h in pts)
        acts = ', '.join(f"{c}x{n}" for c, n in l['acts'].most_common(6))
        print(f"  {who(l['side'])} {cid:>4} {l['body'][:28]:28s} born={l['born']:4d} died={l['died']} | {path} | {acts}")


if __name__ == '__main__':
    main()
