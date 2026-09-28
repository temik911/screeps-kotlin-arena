#!/usr/bin/env python3
"""Forecast stand core: a replay -> per-tick samples of HIS armed creeps with what the state at tick t shows and what
the replay says happened in (t, t+H].

Everything a model may read is computed from the state at t and the history before t; the labels (`dt`) are the only
thing read from the future. Terrain index is y*100+x (checked: 0 of 53 580 creep-ticks on a wall that way, 29 590 the
other way).
"""
import gzip, heapq, os, pickle, re, sys
from array import array

TOOLS = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..'))
sys.path.insert(0, TOOLS)
import replay as R  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
# the samples' cache lives beside the replays, not in the repository (a sample file per match, rebuilt on demand)
CACHE = os.path.expanduser('~/ScreepsArena/forecast-cache')
REPLAYS = os.path.expanduser('~/ScreepsArena/replays')
INF = 10 ** 6
DS = (5, 12)
HS = (50, 100, 150, 200, 300)
STEP = 10
T0 = 100
POWER = {'a': 30, 'r': 10, 'h': 12}   # damage / heal per live part per tick (ATTACK, RANGED_ATTACK, HEAL)


def cheb(ax, ay, bx, by):
    return max(abs(ax - bx), abs(ay - by))


def expand(body):
    out = []
    for k, n in re.findall(r'([a-z])(\d+)', body):
        out += [k] * int(n)
    return out


def live_parts(parts, hits):
    """Parts alive at `hits`: damage eats the body front to back, so part i lives while hits > 100 * (n - 1 - i)."""
    n = len(parts)
    return [p for i, p in enumerate(parts) if hits > 100 * (n - 1 - i)]


def period(weight, moves, rate):
    """Ticks per cell (the bot's periodOn): fatigue = weight * rate after the step, -2 per live MOVE per tick."""
    if moves <= 0: return INF
    left = weight * rate - 2 * moves
    return 1 if left <= 0 else 1 + (left + 2 * moves - 1) // (2 * moves)


class Match:
    pass


def load_match(gid, us_name='temik911'):
    doc, meta, names = R.load(os.path.join(REPLAYS, gid + '.replay.json.gz'))
    m = Match()
    m.gid = gid
    m.us = R.our_side(meta, us_name)
    m.them = 1 - m.us
    m.opp = names.get(m.them)
    m.result = meta['result']
    m.T_end = int(meta['ticks'])
    cells = []
    for ch, n in re.findall(r'([a-z])(\d+)', doc['terrain']):
        cells += [ch] * int(n)
    m.terrain = cells
    objs = {o['id']: o for o in doc['objects']}
    init = R.initial_spawns(doc)
    m.P = {'us': (objs[init[m.us]]['x'], objs[init[m.us]]['y']), 'them': (objs[init[m.them]]['x'], objs[init[m.them]]['y'])}
    m.main_id = {'us': init[m.us], 'them': init[m.them]}
    blocked = bytearray(10000)
    for i, ch in enumerate(cells):
        if ch == 'w': blocked[i] = 1
    # constructed walls are NOT blocked: the map's two are single cells that are broken early (stachu's creep stood on
    # one at t=120 and read "unreachable"), and a route round a single cell costs nothing
    m.blocked = blocked
    # structures: first sighting / death from `s` (see fc docstring of built(): a mid-match object's first `s` entry
    # carries exactly its recorded first state)
    first_s, dead_at, state = {}, {}, {}
    for tick in doc['ticks']:
        k = int(tick['k'])
        for sid, hits, energy in tick.get('s', []):
            if sid not in first_s: first_s[sid] = (k, hits, energy)
            if hits == 0 and energy == 0 and sid not in dead_at: dead_at[sid] = k
    m.structs = []
    for o in doc['objects']:
        if o['kind'] not in ('spawn', 'container', 'energy', 'rampart', 'tower', 'extension'): continue
        f = first_s.get(o['id'])
        born = 0
        if f and f[0] > 1 and (f[1], f[2]) == (o['hits'], o['energy']) and (o['hits'] or o['energy']): born = f[0]
        m.structs.append(dict(id=o['id'], kind=o['kind'], side=o['side'], x=o['x'], y=o['y'], born=born,
                              dead=dead_at.get(o['id'], INF)))
    # energy per container / pile over time (sampled at STEP)
    energy = {o['id']: o['energy'] for o in doc['objects']}
    m.energy_at = {}
    m.spawning_at = {}
    creeps = {}
    last_seen = {}
    for k, start, now, acts, raw, tick in R.frames(doc):
        k = int(k)
        for sid, hits, en in tick.get('s', []):
            energy[sid] = en
        if k % STEP == 0:
            m.energy_at[k] = {sid: e for sid, e in energy.items() if e}
        for cid, c in now.items():
            cr = creeps.get(cid)
            if cr is None:
                cr = creeps[cid] = dict(side=c['side'], body=c['body'], birth=k, xs=array('b'), ys=array('b'), hs=array('i'),
                                        sp=array('b'))
            cr['xs'].append(c['x']); cr['ys'].append(c['y']); cr['hs'].append(c['hits']); cr['sp'].append(1 if c['spawning'] else 0)
            cr['body'] = c['body']
            last_seen[cid] = k
    m.creeps = creeps
    for cid, cr in creeps.items():
        cr['parts'] = expand(cr['body'])
        cr['death'] = last_seen[cid] + 1
        free = next((i for i, s in enumerate(cr['sp']) if not s), len(cr['sp']))
        cr['free'] = cr['birth'] + free
    m.fields = {}
    return m


def pos(cr, k):
    i = k - cr['birth']
    if i < 0 or i >= len(cr['xs']): return None
    return cr['xs'][i], cr['ys'][i]


def hits_at(cr, k):
    i = k - cr['birth']
    if i < 0 or i >= len(cr['hs']): return None
    return cr['hs'][i]


def profile(parts, hits):
    """(power, live MOVE, weight, armed) — power is damage+heal per tick of the live parts."""
    lp = live_parts(parts, hits)
    power = sum(POWER.get(p, 0) for p in lp)
    moves = sum(1 for p in lp if p == 'm')
    weight = sum(1 for p in parts if p not in ('m', 'c'))
    return power, moves, weight, power > 0


def time_field(m, key, sources, p, s):
    """Dijkstra from the source cells outward: ticks for a body with period p on plain and s on swamp to reach any
    source (entering a cell costs that cell's period). Cached per key."""
    f = m.fields.get(key)
    if f is not None: return f
    dist = array('i', [INF]) * 10000
    heap = []
    for (x, y) in sources:
        i = y * 100 + x
        if 0 <= x < 100 and 0 <= y < 100 and not m.blocked[i] and dist[i] > 0:
            dist[i] = 0
            heap.append((0, i))
    heapq.heapify(heap)
    terr = m.terrain
    blocked = m.blocked
    while heap:
        d, i = heapq.heappop(heap)
        if d > dist[i]: continue
        # a creep standing on a neighbour enters cell i: cost is i's period
        c = s if terr[i] == 's' else p
        nd = d + c
        x, y = i % 100, i // 100
        for dy in (-1, 0, 1):
            ny = y + dy
            if ny < 0 or ny > 99: continue
            for dx in (-1, 0, 1):
                if dx == 0 and dy == 0: continue
                nx = x + dx
                if nx < 0 or nx > 99: continue
                j = ny * 100 + nx
                if blocked[j]: continue
                if nd < dist[j]:
                    dist[j] = nd
                    heapq.heappush(heap, (nd, j))
    m.fields[key] = dist
    return dist


def ball(m, P, D):
    px, py = P
    return [(x, y) for x in range(px - D, px + D + 1) for y in range(py - D, py + D + 1)
            if 0 <= x < 100 and 0 <= y < 100 and (x, y) != (px, py)]


def ball_field(m, which, D, p, s):
    return time_field(m, (which, D, p, s), ball(m, m.P[which], D), p, s)


def build_samples(m):
    """[(t, ctx, rows)] — rows: one dict per HIS armed creep with live MOVE alive at t (spawning included)."""
    them, us = m.them, m.us
    out = []
    his = [(cid, cr) for cid, cr in m.creeps.items() if cr['side'] == them]
    ours = [(cid, cr) for cid, cr in m.creeps.items() if cr['side'] == us]
    # first entry into each ball, per creep: next_in[which][D][cid] = array of next tick >= k inside (index by k - birth)
    nxt = {}
    for which in ('us', 'them'):
        px, py = m.P[which]
        for D in DS:
            for cid, cr in his:
                n = len(cr['xs'])
                a = array('i', [INF]) * (n + 1)
                for i in range(n - 1, -1, -1):
                    a[i] = cr['birth'] + i if cheb(cr['xs'][i], cr['ys'][i], px, py) <= D else a[i + 1]
                nxt[(which, D, cid)] = a
    last_t = min(2000 - min(HS), m.T_end - 1)
    for t in range(T0, last_t + 1, STEP):
        rows = []
        for cid, cr in his:
            if not (cr['birth'] <= t < cr['death']): continue
            h = hits_at(cr, t)
            power, moves, weight, armed = profile(cr['parts'], h)
            if not armed or moves <= 0: continue
            x, y = pos(cr, t)
            pp, ps = period(weight, moves, 2), period(weight, moves, 10)
            row = dict(cid=cid, x=x, y=y, w=power, hits=h, hmax=100 * len(cr['parts']), moves=moves, weight=weight,
                       pp=pp, ps=ps, age=t - cr['birth'], spawning=cr['sp'][t - cr['birth']],
                       # ticks since it could first move: a creep still being born stands on the spawn, and a
                       # "history" of standing there is no evidence of intent (the bot's history starts here too)
                       free_age=t - cr['free'],
                       melee=sum(1 for q in live_parts(cr['parts'], h) if q == 'a'),
                       hist={b: pos(cr, t - b) for b in (10, 20, 30, 50, 100)})
            for which in ('us', 'them'):
                px, py = m.P[which]
                for D in DS:
                    a = nxt[(which, D, cid)]
                    # first entry strictly after t
                    j = t + 1 - cr['birth']
                    first = a[j] if j < len(a) else INF
                    row[f'dt_{which}_{D}'] = (first - t) if first < INF else INF
                    row[f'in_{which}_{D}'] = cheb(x, y, px, py) <= D
            rows.append(row)
        # context: our armed / haulers, his all armed, structures alive, energy
        our_armed, our_other = [], []
        for cid, cr in ours:
            if not (cr['birth'] <= t < cr['death']): continue
            h = hits_at(cr, t)
            power, moves, weight, armed = profile(cr['parts'], h)
            x, y = pos(cr, t)
            (our_armed if armed else our_other).append((x, y, power, moves, cid))
        structs = [(s['kind'], s['side'], s['x'], s['y'], s['id']) for s in m.structs if s['born'] <= t < s['dead']]
        en = m.energy_at.get(t, {})
        ctx = dict(our_armed=our_armed, our_other=our_other, structs=structs,
                   energy={sid: e for sid, e in en.items()})
        out.append((t, ctx, rows))
    return out


def load_samples(gid):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, gid + '.pkl.gz')
    if os.path.exists(path):
        with gzip.open(path, 'rb') as f:
            return pickle.load(f)
    m = load_match(gid)
    samples = build_samples(m)
    # keep the match minus the per-creep arrays of OUR side (models only read his history and the fields)
    data = dict(gid=gid, opp=m.opp, result=m.result, T_end=m.T_end, P=m.P, us=m.us, them=m.them,
                terrain=''.join(m.terrain), blocked=bytes(m.blocked), samples=samples, structs=m.structs)
    with gzip.open(path, 'wb') as f:
        pickle.dump(data, f)
    return data


class Fields:
    """Field cache over a loaded sample file (models ask for ball fields per body class)."""

    def __init__(self, data):
        self.terrain = data['terrain']
        self.blocked = bytearray(data['blocked'])
        self.P = data['P']
        self.fields = {}

    def ball(self, which, D, p, s):
        return time_field(self, (which, D, p, s), ball(self, self.P[which], D), p, s)

    def to(self, key, sources, p, s):
        return time_field(self, key, sources, p, s)


if __name__ == '__main__':
    import time
    for gid in sys.argv[1:]:
        t0 = time.time()
        d = load_samples(gid)
        n = sum(len(r) for _, _, r in d['samples'])
        print(gid, d['opp'], d['T_end'], len(d['samples']), 'rows', n, f'{time.time() - t0:.1f}s')
