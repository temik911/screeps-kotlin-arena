#!/usr/bin/env python3
"""The combat instrument of the Spawn and Swamp ADVANCED stub (README "The combat instrument").

    python3 fights.py <fights-*.jsonl.gz>... [--fights N] [--tsv out.tsv] [--side 0|1|both]

Reads the traces run.mjs writes with FIGHTS=1 (every hit and heal the engine dealt, every creep's cell and hits, every
owned structure's hits and energy, tick by tick) and the two bots' consoles the trace names, cuts each match into fights
and prints, per run and over the series, what each side brought, lost and did in them.

A FIGHT: hostile hits (a creep or a tower hitting a creep of the other side, or a creep hitting an owned structure of the
other side) linked in time and space — a hit joins an open fight if the fight saw a hit in the last GAP ticks and the hit's
shooter or target is already in it or stands within JOIN cells of one of its members; a hit that touches two open fights
merges them; a fight closes after GAP ticks without a hostile hit. Its members are every creep that hit, was hit, or healed
a member while it was open; a member's ENTRY is the tick it first did one of those, with its hits and live parts then.

Per side: entry (combat creeps, of them late — engaged more than LATE ticks after the first hit —, damage and heal a tick,
hits), energy lost (a creep's value is its body cost times hits / hitsMax, a structure's its construction cost times the
same: what it was worth at entry minus what it is worth at the end, 0 for the dead — additive over fights), the trade (his
loss over ours), the place (the nearest spawn to the fight's hits within BASE cells: whose base, or the field; whose towers
fired), where the side's creep damage went (healers / fighters / workers / structures / ramparts that took a hit meant for
the creep under them), overkill, focus (distinct creep targets a tick against shooters a tick), turns (a member in contact —
within 4 of an armed enemy — that moves away by 3+ cells within 8 ticks without stepping back in: the kinematic retreat,
whatever ordered it; its loss after the turn), lone deaths (no own combat creep within 5 at death), wounded in the front
line (a dead combat member that spent 3+ of its last 15 ticks under half hits within 3 of an armed enemy — `fresh` when a
friend at 80 %+ stood within 4 and farther from the enemy), healing (creeps' heal raw and effective — no more than the
missing hits —, the heal parts' potential, towers' heal), and the bot's own decision before the fight (the last push or
strike of that side within 300 ticks with no retreat/recall since, and its retreat/recall inside the fight) against the
outcome, plus `simAct` — the bot's own `simulate` (ported below) on the forces that actually engaged, which separates a
wrong forecast model from a wrong forecast input.
"""
import sys, gzip, json, re, os, math, argparse
from collections import defaultdict

GAP, JOIN, LATE, BASE, CONTACT = 10, 6, 5, 12, 4
COST = {'m': 50, 'w': 100, 'c': 50, 'a': 80, 'r': 150, 'h': 250, 't': 10}
SCOST = {'spawn': 1000, 'tower': 1250, 'rampart': 200, 'extension': 200}
LONG = {'m': 'move', 'w': 'work', 'c': 'carry', 'a': 'attack', 'r': 'ranged_attack', 'h': 'heal', 't': 'tough'}


def cheb(a, b):
    return max(abs(a[0] - b[0]), abs(a[1] - b[1]))


def live_parts(body, hits):
    n = len(body)
    out = defaultdict(int)
    for i, p in enumerate(body):
        if hits > 100 * (n - 1 - i):
            out[p] += 1
    return out


def dps_of(body, hits):
    lp = live_parts(body, hits)
    return lp['r'] * 10 + lp['a'] * 30


def heal_of(body, hits):
    return live_parts(body, hits)['h'] * 12


def is_combat(body):
    return any(p in body for p in 'arh')


def cls_of(o):
    if o['k'] != 'creep':
        return 'struct'
    b = o['b']
    if 'h' in b:
        return 'healer'
    if 'a' in b or 'r' in b:
        return 'fighter'
    return 'worker'


def value(o, hits=None):
    h = o['h'] if hits is None else hits
    if o['k'] == 'creep':
        return sum(COST[p] for p in o['b']) * max(0, h) / o['hm']
    return SCOST.get(o['k'], 0) * max(0, h) / max(1, o['hm'])


# ---------- the bot's simulate(), ported from SpawnAndSwampAdvanced.kt (v58) ----------
class SU:
    __slots__ = ('types', 'hits', 'fixed', 'maxh')

    def __init__(self, types, hits, fixed=0, maxh=None):
        self.types, self.hits, self.fixed = types, hits, fixed
        self.maxh = maxh if maxh is not None else len(types) * 100

    def live(self, t):
        n = len(self.types)
        return sum(1 for i in range(n) if self.types[i] == t and self.hits > 100 * (n - 1 - i))

    def dps(self):
        return self.fixed + self.live('r') * 10 + self.live('a') * 30

    def heal(self):
        return self.live('h') * 12

    @property
    def tower(self):
        return not self.types


def out_rate(u):
    o = u.dps() + u.heal()
    if o <= 0:
        return 0.0
    j = max((i for i, t in enumerate(u.types) if t in 'arh'), default=-1)
    if j < 0:
        return 0.0
    return o / max(1, u.hits - 100 * (len(u.types) - 1 - j))


def simulate(ours, theirs, limit=300):
    a = [SU(u.types, u.hits, u.fixed, u.maxh) for u in ours]
    b = [SU(u.types, u.hits, u.fixed, u.maxh) for u in theirs]
    a0 = max(1, sum(u.hits for u in a))
    t = 0

    def strike(side, dmg):
        for u in sorted(side, key=lambda u: (1 if u.tower else 0, -out_rate(u), u.hits)):
            if dmg <= 0:
                break
            take = min(dmg, u.hits)
            u.hits -= take
            dmg -= take

    def mend(side, heal):
        for u in sorted([u for u in side if not u.tower and 0 < u.hits < u.maxh], key=lambda u: -(u.maxh - u.hits)):
            if heal <= 0:
                break
            add = min(heal, u.maxh - u.hits)
            u.hits += add
            heal -= add

    while t < limit and a and b:
        t += 1
        da, db = sum(u.dps() for u in a), sum(u.dps() for u in b)
        ha, hb = sum(u.heal() for u in a), sum(u.heal() for u in b)
        strike(b, da)
        strike(a, db)
        a = [u for u in a if u.hits > 0]
        b = [u for u in b if u.hits > 0]
        mend(a, ha)
        mend(b, hb)
        if da == 0 and db == 0:
            break
    left = sum(u.hits for u in a)
    return (not b and bool(a)), left / a0, t


def tower_shot(r):  # the runtime's constants: optimal 1, falloff range 21, falloff 1
    if r <= 1:
        return 1000.0
    if r >= 21:
        return 0.0
    return 1000.0 * (1 - (r - 1) / 20)


# ---------- the bots' own lines ----------
RX = {
    'push': re.compile(r'^push t=(\d+) wave=(\d+).* sim keep=(\d+)%'),
    'strike': re.compile(r'^strike t=(\d+) wave=(\d+) at spawn \((\d+),(\d+)\).* sim keep=(\d+)%'),
    'retreat': re.compile(r'^retreat t=(\d+) wave=(\d+)'),
    'recall': re.compile(r'^recall t=(\d+) wave=(\d+)'),
    'calib': re.compile(r'^calib (\w+) t=(\d+)\.\.(\d+) (\w+) pred keep=(-?\d+)% win=(\w+) actual keep=(\d+)% ours (\d+)/(\d+) theirs (\d+)/(\d+) theirKeep=(\d+)%'),
}


def read_log(path):
    out = {'version': None, 'dec': [], 'calib': [], 'lastcall': None}
    if not path or not os.path.exists(path):
        return out
    with open(path, errors='replace') as f:
        for line in f:
            if line.startswith('=== EVENTS ==='):
                break
            if out['version'] is None and line.startswith('hello '):
                out['version'] = line.split()[-1]
            for k, rx in RX.items():
                m = rx.match(line)
                if not m:
                    continue
                if k == 'calib':
                    g = m.groups()
                    out['calib'].append({'kind': g[0], 't0': int(g[1]), 't1': int(g[2]), 'why': g[3], 'pred': int(g[4]), 'win': g[5] == 'true',
                                         'act': int(g[6]), 'ours': (int(g[7]), int(g[8])), 'theirs': (int(g[9]), int(g[10])), 'theirKeep': int(g[11])})
                elif k in ('push', 'strike'):
                    g = m.groups()
                    if 'lastCall' in line and out['lastcall'] is None:
                        out['lastcall'] = int(g[0])
                    out['dec'].append({'k': k, 't': int(g[0]), 'wave': int(g[1]), 'keep': int(g[-1])})
                else:
                    out['dec'].append({'k': k, 't': int(m.group(1)), 'wave': int(m.group(2))})
    return out


# ---------- one run ----------
class Fight:
    _n = 0

    def __init__(self, t):
        Fight._n += 1
        self.id = Fight._n
        self.start = self.last = t
        self.mem = {}          # creep id -> {'side', 'entry', 'hits0', 'val0', 'dps0', 'heal0', 'died', 'series': [(t, d, x, y, hits)], 'late'}
        self.structs = {}      # structure id -> {'side', 'val0'}
        self.pts = []          # hit target cells
        self.dmg = [defaultdict(float), defaultdict(float)]   # by attacker side: target class -> damage
        self.tower_dmg = [0.0, 0.0]
        self.towers_fired = [set(), set()]
        self.over = [0.0, 0.0]
        self.focus = [[0, 0, 0, 0.0], [0, 0, 0, 0.0]]      # ticks with single fire, sum distinct targets, sum shooters, sum top-target share
        self.mass = [0.0, 0.0]
        self.heal_raw = [0.0, 0.0]
        self.heal_eff = [0.0, 0.0]
        self.heal_pot = [0.0, 0.0]
        self.tower_heal = [0.0, 0.0]
        self.lone = [[], []]
        self.upt = [[0, 0, 0], [0, 0, 0]]  # armed members' creep-ticks after entry: all, with an enemy creep or structure within 3, fired
        self.merged = False


class Run:
    """One run: its trace, the two consoles, its fights (load_run fills it)."""

    def join(self, open_f, t, S, T):
        pts = [(S['x'], S['y']), (T['x'], T['y'])]
        hit = []
        for F in open_f:
            if t - F.last > GAP:
                continue
            if S['id'] in F.mem or T['id'] in F.mem or S['id'] in F.structs or T['id'] in F.structs:
                hit.append(F)
                continue
            near = False
            for i, m in F.mem.items():
                if m['act'] < t - GAP:  # a member that left the fight does not drag it along its walk
                    continue
                o = self._obj(i)
                if o is not None and any(cheb(p, (o['x'], o['y'])) <= JOIN for p in pts):
                    near = True
                    break
            if near:
                hit.append(F)
        if not hit:
            F = Fight(t)  # opened by a tower's hit, it closes GAP ticks later unless a creep's hit comes
            open_f.append(F)
            return F
        F = min(hit, key=lambda F: F.start)
        for G in hit:
            if G is F:
                continue
            self.merge(F, G)
            open_f.remove(G)
        return F

    def _obj(self, i):
        return self._objs.get(i) if hasattr(self, '_objs') else None

    def merge(self, F, G):
        G.merged = True
        F.start = min(F.start, G.start)
        F.last = max(F.last, G.last)
        for i, m in G.mem.items():
            if i not in F.mem or m['entry'] < F.mem[i]['entry']:
                F.mem[i] = m
        for i, s in G.structs.items():
            F.structs.setdefault(i, s)
        F.pts += G.pts
        for side in (0, 1):
            for k, v in G.dmg[side].items():
                F.dmg[side][k] += v
            F.tower_dmg[side] += G.tower_dmg[side]
            F.towers_fired[side] |= G.towers_fired[side]
            F.over[side] += G.over[side]
            F.mass[side] += G.mass[side]
            for j in range(4):
                F.focus[side][j] += G.focus[side][j]
            F.heal_raw[side] += G.heal_raw[side]
            F.heal_eff[side] += G.heal_eff[side]
            F.heal_pot[side] += G.heal_pot[side]
            F.tower_heal[side] += G.tower_heal[side]
            F.lone[side] += G.lone[side]
            for j in range(3):
                F.upt[side][j] += G.upt[side][j]

    def enter(self, F, o, t):
        i = o['id']
        if o['k'] == 'creep':
            if i not in F.mem:
                F.mem[i] = {'side': o['o'], 'entry': t, 'hits0': o['h'], 'hm': o['hm'], 'b': o['b'], 'val0': value(o), 'dps0': dps_of(o['b'], o['h']),
                            'heal0': heal_of(o['b'], o['h']), 'died': None, 'series': [], 'valEnd': value(o), 'hitsEnd': o['h'], 'act': t,
                            'born': o.get('born', 0)}
            F.mem[i]['act'] = t
        elif o['k'] in SCOST:
            if i not in F.structs:
                F.structs[i] = {'side': o['o'], 'k': o['k'], 'val0': value(o), 'valEnd': value(o), 'x': o['x'], 'y': o['y'], 'hits0': o['h'], 'hm': o['hm']}


def load_run(path):
    R = Run()
    R.path = path
    with gzip.open(path, 'rt') as f:
        R.hdr = json.loads(f.readline())
        R.recs = []
        R.end = None
        for line in f:
            o = json.loads(line)
            if 'end' in o:
                R.end = o
                break
            R.recs.append(o)
    base = os.path.basename(path).replace('.jsonl.gz', '')
    R.name = base[len('fights-'):] if base.startswith('fights-') else base
    R.logs = [read_log(R.end and R.end.get('log')), read_log(R.end and R.end.get('enemyLog'))]
    b2 = R.hdr.get('bot2')
    m = re.search(r'frozen-(v\d+)', b2 or '')
    R.opp = m.group(1) if m else ('self' if b2 and 'bot2-' in b2 else R.hdr.get('scenario'))
    parts = R.name.split('-', 2)  # league.sh names its traces fights-<map>-<side>-<opponent>
    if len(parts) == 3 and parts[1] in ('left', 'right'):
        R.opp = parts[2]
    R.label = [R.logs[0]['version'] or 'us', (R.logs[1]['version'] or R.opp) if b2 else R.opp]
    R.outcome = (R.end or {}).get('outcome', '?')
    R._objs = {}
    _analyse(R)
    return R


def _analyse(R):
    """One pass over the ticks: the state as it was when each tick's hits were dealt, fights opened, joined, merged and
    closed, the members' entries, series and ends."""
    objs = R._objs
    open_f, closed = [], []
    recs = {r['t']: r for r in R.recs}
    last_t = R.end['end'] if R.end else (R.recs[-1]['t'] if R.recs else 0)
    combat = [dict(), dict()]
    for t in range(1, last_t + 1):
        r = recs.get(t)
        for F in list(open_f):
            if t - F.last > GAP:
                open_f.remove(F)
                closed.append(F)
        if r:
            hits = r.get('h', [])
            inc_d, inc_h = defaultdict(float), defaultdict(float)
            for src, tg, how, amt, rp in hits:
                if amt > 0:
                    inc_d[rp or tg] += amt  # a hit a rampart took does not touch the creep under it
                else:
                    inc_h[tg] += -amt
            single = [defaultdict(lambda: defaultdict(float)), defaultdict(lambda: defaultdict(float))]
            for src, tg, how, amt, rp in hits:
                S, T = objs.get(src), objs.get(tg)
                if not S or not T:
                    continue
                if amt > 0 and S['o'] != T['o']:
                    F = R.join(open_f, t, S, T)
                    side = S['o']
                    R.enter(F, S, t)
                    R.enter(F, T, t)
                    if rp and rp in objs:
                        R.enter(F, objs[rp], t)
                    F.pts.append((T['x'], T['y']))
                    # a tower keeps shooting whatever walks within 20 every 10 ticks: its hits join a fight but do not
                    # keep one open — else a rally near his towers is one fight of 800 ticks
                    if S['k'] != 'tower':
                        F.last = t
                    if S['k'] == 'tower':
                        F.tower_dmg[side] += amt
                        F.towers_fired[side].add(src)
                    else:
                        F.dmg[side]['ramp' if rp else cls_of(T)] += amt
                        if how in ('a', 'r') and T['k'] == 'creep' and not rp:
                            single[side][tg][src] += amt
                        if how == 'R':
                            F.mass[side] += amt
                elif amt < 0 and S['o'] == T['o']:
                    F = next((F for F in open_f if tg in F.mem or src in F.mem), None)
                    if F is None:
                        continue
                    R.enter(F, S, t)
                    R.enter(F, T, t)
                    side = S['o']
                    missing = max(0.0, T['hm'] - (T['h'] - inc_d.get(tg, 0)))
                    eff = min(-amt, missing * (-amt) / max(1e-9, inc_h[tg]))
                    if S['k'] == 'tower':
                        F.tower_heal[side] += eff
                    else:
                        F.heal_raw[side] += -amt
                        F.heal_eff[side] += eff
            for side in (0, 1):
                if not single[side]:
                    continue
                tg0 = next(iter(single[side]))
                F = next((F for F in open_f if tg0 in F.mem), None)
                if F is None:
                    continue
                shooters, tot, top = set(), 0.0, 0.0
                for tg, bysrc in single[side].items():
                    shooters.update(bysrc)
                    s = sum(bysrc.values())
                    tot += s
                    top = max(top, s)
                fs = F.focus[side]
                fs[0] += 1
                fs[1] += len(single[side])
                fs[2] += len(shooters)
                fs[3] += top / tot if tot else 0
            # fire uptime, on the cells the tick's fire was dealt from: an armed member alive after its entry — was anything
            # of the other side within 3, did it fire
            if open_f:
                fired = {src for src, tg, how, amt, rp in hits if amt > 0 and src in objs and tg in objs and objs[src]['o'] != objs[tg]['o']}
                cells = [set(), set()]
                for q in objs.values():
                    if q['k'] == 'creep' or q['k'] in SCOST:
                        cells[q['o']].add((q['x'], q['y']))
                for F in open_f:
                    for i, m in F.mem.items():
                        o = objs.get(i)
                        if o is None or o['k'] != 'creep' or dps_of(o['b'], o['h']) <= 0:
                            continue
                        sd = o['o']
                        u = F.upt[sd]
                        u[0] += 1
                        other = cells[1 - sd]
                        if any((o['x'] + dx, o['y'] + dy) in other for dx in range(-3, 4) for dy in range(-3, 4)):
                            u[1] += 1
                        if i in fired:
                            u[2] += 1
            dying = set(r.get('d', []))
            for tg, dmg in inc_d.items():
                T = objs.get(tg)
                if not T or T['k'] != 'creep' or tg not in dying:
                    continue
                F = next((F for F in open_f if tg in F.mem), None)
                if F:
                    F.over[1 - T['o']] += max(0.0, dmg - (T['h'] + inc_h.get(tg, 0)))
            for e in r.get('n', []):
                i, o, k, x, y, hm, body, h = e[:8]
                objs[i] = {'id': i, 'o': o, 'k': k, 'x': x, 'y': y, 'hm': hm, 'b': body, 'h': h, 'e': e[8] if len(e) > 8 else 0, 'born': t}
                if k == 'creep' and is_combat(body):
                    combat[o][i] = objs[i]
            for i, x, y, h in r.get('c', []):
                o = objs.get(i)
                if o:
                    o['x'], o['y'], o['h'] = x, y, h
            for i, h, e in r.get('s', []):
                o = objs.get(i)
                if o:
                    o['h'], o['e'] = h, e
            for i in r.get('d', []):
                o = objs.get(i)
                if not o:
                    continue
                for F in open_f:
                    m = F.mem.get(i)
                    if m is not None:
                        m['died'] = t
                        m['valEnd'] = 0.0
                        m['hitsEnd'] = 0
                        if True:
                            friends = [q for q in combat[o['o']].values() if q['id'] != i and cheb((q['x'], q['y']), (o['x'], o['y'])) <= 5]
                            m['lone'] = not friends
                            m['deathAt'] = (o['x'], o['y'])
                    if i in F.structs:
                        F.structs[i]['died'] = t
                        F.structs[i]['valEnd'] = 0.0
                combat[o['o']].pop(i, None)
                objs.pop(i, None)
        for F in open_f:
            for i, m in F.mem.items():
                o = objs.get(i)
                if o is None or o['k'] != 'creep':
                    continue
                if is_combat(o['b']):
                    en = [q for q in combat[1 - o['o']].values() if dps_of(q['b'], q['h']) > 0]
                    d = min((cheb((o['x'], o['y']), (q['x'], q['y'])) for q in en), default=99)
                    m['series'].append((t, d, o['x'], o['y'], o['h']))
                    F.heal_pot[o['o']] += heal_of(o['b'], o['h'])
            if F.last == t:
                for i, m in F.mem.items():
                    o = objs.get(i)
                    if o is not None:
                        m['valEnd'] = value(o)
                        m['hitsEnd'] = o['h']
                for i, s in F.structs.items():
                    o = objs.get(i)
                    if o is not None:
                        s['valEnd'] = value(o)
    closed.extend(open_f)
    R.fights = sorted([F for F in closed if not F.merged], key=lambda F: F.start)


def spawn_log(R):
    """Every spawn of each side with the ticks it stood — for the place of a fight."""
    out = {}
    for r in R.recs:
        for e in r.get('n', []):
            if e[2] == 'spawn':
                out[e[0]] = {'o': e[1], 'x': e[3], 'y': e[4], 'from': r['t'], 'to': 10 ** 9}
        for i in r.get('d', []):
            if i in out:
                out[i]['to'] = r['t']
    return out


def turn_of(series):
    """The kinematic retreat: the first tick a member in contact (d <= CONTACT) steps away and is 3+ farther within 8 ticks
    with the distance never falling on the way; returns (tick, index) or None."""
    for i in range(1, len(series)):
        t, d, x, y, h = series[i]
        tp, dp, xp, yp, hp = series[i - 1]
        if dp > CONTACT or d <= dp or (x, y) == (xp, yp) or t != tp + 1:
            continue
        run = dp
        for j in range(i, min(len(series), i + 8)):
            if series[j][1] < run:
                break
            run = series[j][1]
            if run >= dp + 3:
                return t, i
    return None


def fight_rows(R):
    sp = spawn_log(R)
    rows = []
    for F in R.fights:
        row = {'run': R.name, 'opp': R.opp, 'id': F.id, 'start': F.start, 'end': F.last, 'dur': F.last - F.start + 1}
        # the phase: a fight that starts after either side's last call (the bot's final all-out push, keep 0 % allowed)
        lc = [L['lastcall'] for L in R.logs if L['lastcall'] is not None]
        row['phase'] = 'last' if lc and F.start >= min(lc) else 'main'
        cx = sum(p[0] for p in F.pts) / max(1, len(F.pts))
        cy = sum(p[1] for p in F.pts) / max(1, len(F.pts))
        row['cell'] = (round(cx), round(cy))
        near = [(cheb((cx, cy), (s['x'], s['y'])), s['o']) for s in sp.values() if s['from'] <= F.start <= s['to']]
        near.sort()
        row['place'] = f"base{near[0][1]}" if near and near[0][0] <= BASE else 'field'
        tw = ''.join(('U' if F.towers_fired[0] else '') + ('T' if F.towers_fired[1] else ''))
        row['tw'] = tw or '-'
        for s in (0, 1):
            mem = {i: m for i, m in F.mem.items() if m['side'] == s}
            cmb = {i: m for i, m in mem.items() if is_combat(m['b'])}
            wrk = {i: m for i, m in mem.items() if not is_combat(m['b'])}
            late = {i: m for i, m in cmb.items() if m['entry'] - F.start > LATE}
            hits0 = sum(m['hits0'] for m in cmb.values())
            lost_c = sum(m['val0'] - m['valEnd'] for m in mem.values())
            lost_s = sum(v['val0'] - v['valEnd'] for v in F.structs.values() if v['side'] == s)
            dead = [i for i, m in mem.items() if m['died']]
            deadc = [i for i in dead if is_combat(mem[i]['b'])]
            lone = [i for i in dead if mem[i].get('lone')]
            # turns and wounded in the front line
            turned, turned_died, lost_after_turn = 0, 0, 0.0
            wounded_front, wounded_fresh, lost_wf = 0, 0, 0.0
            series_at = {i: {q[0]: q for q in m['series']} for i, m in cmb.items()}
            for i, m in cmb.items():
                tr = turn_of(m['series'])
                if tr:
                    turned += 1
                    tt, k = tr
                    h_at = m['series'][k - 1][4]
                    v_at = sum(COST[p] for p in m['b']) * h_at / m['hm']
                    lost_after_turn += max(0.0, v_at - m['valEnd'])
                    if m['died'] and m['died'] >= tt:
                        turned_died += 1
                if m['died']:
                    tail = [q for q in m['series'] if q[0] >= m['died'] - 15]
                    wf = [q for q in tail if q[4] < 0.5 * m['hm'] and q[1] <= 3]
                    if len(wf) >= 3:
                        wounded_front += 1
                        lost_wf += m['val0']
                        fresh = False
                        for q in wf:
                            for j, n in cmb.items():
                                if j == i:
                                    continue
                                qq = series_at[j].get(q[0])
                                if qq and qq[4] >= 0.8 * n['hm'] and cheb((qq[2], qq[3]), (q[2], q[3])) <= 4 and qq[1] > q[1]:
                                    fresh = True
                                    break
                            if fresh:
                                break
                        wounded_fresh += fresh
            # piecemeal: a member that died while a friend who joined this fight later was already out of its spawn — it
            # fought and died before the rest of its side came (the stretch's cost, whatever held the rest back)
            piece = [i for i in deadc if any(n['entry'] > mem[i]['died'] and n['born'] < mem[i]['died'] for j, n in cmb.items() if j != i)]
            # …of them, those whose friend came within 20 ticks of the death: it was on the way, not at home
            near = [i for i in piece if any(mem[i]['died'] < n['entry'] <= mem[i]['died'] + 20 and n['born'] < mem[i]['died'] for j, n in cmb.items() if j != i)]
            f = F.focus[s]
            dmg = F.dmg[s]
            dtot = sum(dmg.values())
            delays = sorted(m['entry'] - F.start for m in cmb.values())
            row[s] = {
                'n': len(cmb), 'late': len(late), 'delay': delays[len(delays) // 2] if delays else 0, 'lateHits': sum(m['hits0'] for m in late.values()) / max(1, hits0), 'wrk': len(wrk),
                'dps': sum(m['dps0'] for m in cmb.values()), 'heal': sum(m['heal0'] for m in cmb.values()), 'hits': hits0,
                'lostC': lost_c, 'lostS': lost_s, 'dead': len(dead), 'deadC': len(deadc), 'lone': len(lone),
                'loneVal': sum(mem[i]['val0'] for i in lone),
                'piece': len(piece), 'pieceVal': sum(mem[i]['val0'] for i in piece), 'pieceNear': len(near), 'pieceNearVal': sum(mem[i]['val0'] for i in near),
                'dmgCreeps': sum(v for k, v in dmg.items() if k in ('healer', 'fighter', 'worker')),
                'keep': sum(m['hitsEnd'] for m in cmb.values()) / max(1, hits0),
                'dmg': dtot, 'share': {k: dmg.get(k, 0) / dtot if dtot else 0 for k in ('healer', 'fighter', 'worker', 'struct', 'ramp')},
                'tower': F.tower_dmg[s], 'over': F.over[s], 'mass': F.mass[s],
                'tgt': f[1] / f[0] if f[0] else 0, 'sh': f[2] / f[0] if f[0] else 0, 'top': f[3] / f[0] if f[0] else 0,
                'turned': turned, 'turnedDied': turned_died, 'lostAfterTurn': lost_after_turn,
                'wf': wounded_front, 'wfFresh': wounded_fresh, 'lostWF': lost_wf,
                'upt': F.upt[s][0], 'uptReach': F.upt[s][1], 'uptFired': F.upt[s][2],
                'healRaw': F.heal_raw[s], 'healEff': F.heal_eff[s], 'healPot': F.heal_pot[s], 'towerHeal': F.tower_heal[s],
            }
            # the side's decision before the fight and its retreat inside it
            dec = R.logs[s]['dec']
            pre = None
            for d in dec:
                if d['t'] > F.start:
                    break
                if d['k'] in ('push', 'strike'):
                    pre = d
                elif pre is not None:
                    pre = None
            if pre is not None and F.start - pre['t'] > 300:
                pre = None
            if pre is not None and row['place'] == f'base{s}':
                pre = None
            row[s]['pred'] = pre
            # a retreat or recall turns a wave: it is read in the wave's fights, not in a fight at our own base
            ret = next((d for d in dec if d['k'] in ('retreat', 'recall') and F.start <= d['t'] <= F.last), None) if row['place'] != f'base{s}' else None
            row[s]['ret'] = ret
            # flips: the side's push/strike and retreat/recall lines inside the fight — a wave that strikes and retreats
            # every few ticks under fire (v58 6abc21a1 vs v52, 2580-2605: strike, retreat, strike, retreat, strike)
            row[s]['flips'] = sum(1 for d in dec if F.start <= d['t'] <= F.last) if row['place'] != f'base{s}' else 0
            if ret:
                tt = ret['t']
                lost_after, died_after = 0.0, 0
                for i, m in mem.items():
                    q = next((q for q in m['series'] if q[0] >= tt), None) if m['series'] else None
                    if m['entry'] > tt:
                        v_at = m['val0']
                    elif q is not None:
                        v_at = sum(COST[p] for p in m['b']) * q[4] / m['hm']
                    elif m['died'] and m['died'] < tt:
                        v_at = 0.0
                    else:
                        v_at = m['valEnd']
                    lost_after += max(0.0, v_at - m['valEnd'])
                    if m['died'] and m['died'] >= tt:
                        died_after += 1
                row[s]['lostAfterRet'] = lost_after
                row[s]['diedAfterRet'] = died_after
        # the bot's simulate on what actually engaged (towers that fired, with their hits; side 0 as `ours`)
        units = []
        for s in (0, 1):
            us = [SU(list(m['b']), m['hits0'], 0, m['hm']) for m in F.mem.values() if m['side'] == s and is_combat(m['b'])]
            for tid in F.towers_fired[s]:
                st = F.structs.get(tid)
                h = st['hits0'] if st else 3000
                us.append(SU([], h, int(tower_shot(4) / 10), h))
            units.append(us)
        if units[0] and units[1]:
            w, k, tt = simulate(units[0], units[1])
            w1, k1, _ = simulate(units[1], units[0])
            row['simAct'] = (w, k, w1, k1)
        else:
            row['simAct'] = None
        for s in (0, 1):
            a, b = row[s], row[1 - s]
            a['lost'] = a['lostC'] + a['lostS']
        for s in (0, 1):
            a, b = row[s], row[1 - s]
            a['trade'] = b['lost'] / a['lost'] if a['lost'] > 0 else (math.inf if b['lost'] > 0 else 1.0)
        rows.append(row)
    return rows


def f0(x):
    return f'{x:.0f}'


def pct(x):
    return f'{100 * x:.0f}%'


def trade_s(x):
    return 'inf' if x == math.inf else f'{x:.2f}'


def print_fight(row, out):
    a, b = row[0], row[1]
    sa = row['simAct']
    sim = '-' if sa is None else f"{'W' if sa[0] else 'L'}{pct(sa[1])}/{'W' if sa[2] else 'L'}{pct(sa[3])}"
    def side(s):
        x = row[s]
        pr = x['pred']
        pred = f"{pr['k']}@{pr['t']} {pr['keep']}%" if pr else '-'
        ret = f"{x['ret']['k']}@{x['ret']['t']} -{f0(x.get('lostAfterRet', 0))}/{x.get('diedAfterRet', 0)}d" if x['ret'] else '-'
        sh = x['share']
        return (f"n={x['n']}{'('+str(x['late'])+' late, median +'+str(x['delay'])+')' if x['late'] else ''}{'+'+str(x['wrk'])+'w' if x['wrk'] else ''} dps={x['dps']} heal={x['heal']} hits={x['hits']} "
                f"lost={f0(x['lostC'])}+{f0(x['lostS'])}s dead={x['dead']} lone={x['lone']} keep={pct(x['keep'])} trade={trade_s(x['trade'])} "
                f"piece={x['piece']} dmg={f0(x['dmg'])}[h{pct(sh['healer'])} f{pct(sh['fighter'])} w{pct(sh['worker'])} s{pct(sh['struct'])} r{pct(sh['ramp'])}] tw={f0(x['tower'])} over={f0(x['over'])} "
                f"focus={x['tgt']:.1f}/{x['sh']:.1f} up={pct(x['uptReach'] / max(1, x['upt']))}/{pct(x['uptFired'] / max(1, x['upt']))} turned={x['turned']}({x['turnedDied']}d,-{f0(x['lostAfterTurn'])}) wf={x['wf']}({x['wfFresh']}f) "
                f"heal={f0(x['healEff'])}/{f0(x['healRaw'])}/{f0(x['healPot'])} twH={f0(x['towerHeal'])} pred={pred} ret={ret} flips={x['flips']}")
    out.append(f"  #{row['id']:<3} t={row['start']}..{row['end']} ({row['dur']}) {row['phase']} {row['place']} tw={row['tw']} at {row['cell']} simAct={sim}")
    out.append(f"      A {side(0)}")
    out.append(f"      B {side(1)}")


def agg(rows, s):
    """Sums over fights, from side s's view."""
    A = defaultdict(float)
    for r in rows:
        x, y = r[s], r[1 - s]
        A['fights'] += 1
        A['won'] += x['trade'] > 1
        A['lostT'] += x['trade'] < 1
        A['lost'] += x['lost']
        A['hisLost'] += y['lost']
        A['lostC'] += x['lostC']
        A['lostS'] += x['lostS']
        A['n'] += x['n']
        A['late'] += x['late']
        A['dead'] += x['dead']
        A['deadC'] += x['deadC']
        A['lone'] += x['lone']
        A['loneVal'] += x['loneVal']
        A['turned'] += x['turned']
        A['turnedDied'] += x['turnedDied']
        A['lostAfterTurn'] += x['lostAfterTurn']
        A['piece'] += x['piece']
        A['upt'] += x['upt']
        A['uptReach'] += x['uptReach']
        A['uptFired'] += x['uptFired']
        A['pieceVal'] += x['pieceVal']
        A['pieceNear'] += x['pieceNear']
        A['pieceNearVal'] += x['pieceNearVal']
        A['dmgCreeps'] += x['dmgCreeps']
        A['hisHealEff'] += y['healEff'] + y['towerHeal']
        A['wf'] += x['wf']
        A['wfFresh'] += x['wfFresh']
        A['lostWF'] += x['lostWF']
        A['healEff'] += x['healEff']
        A['healRaw'] += x['healRaw']
        A['healPot'] += x['healPot']
        A['towerHeal'] += x['towerHeal']
        A['dmg'] += x['dmg']
        A['over'] += x['over']
        A['tower'] += x['tower']
        for k, v in x['share'].items():
            A['sh_' + k] += v * x['dmg']
        if x['dmg'] > 0:
            A['focusW'] += x['dmg']
            A['tgtW'] += x['tgt'] * x['dmg']
            A['shW'] += x['sh'] * x['dmg']
        # stretched: a fight with a quarter or more of its hits entering late
        if x['n'] and x['lateHits'] >= 0.25:
            A['stretchF'] += 1
            A['stretchLost'] += x['lost']
            A['stretchHis'] += y['lost']
        elif x['n']:
            A['tightLost'] += x['lost']
            A['tightHis'] += y['lost']
        # entry: a push/strike promised a win (keep >= 50) and the fight's trade was lost
        if x['wfFresh']:
            A['lostWFfresh'] += x['lostWF'] * x['wfFresh'] / max(1, x['wf'])
        if r['place'] != f'base{s}' and x['trade'] < 1:
            A['badPlaceLost'] += x['lost'] - y['lost']
        if x['pred'] is not None:
            A['predF'] += 1
            A['predLost'] += x['lost']
            A['predHis'] += y['lost']
            if x['pred']['keep'] >= 50 and x['trade'] < 1:
                A['predBad'] += 1
                A['predBadLost'] += x['lost']
        A['flips'] += x['flips']
        if x['flips'] >= 3:
            A['flipF'] += 1
            A['flipLost'] += x['lost']
            A['flipHis'] += y['lost']
        if x['ret']:
            A['retF'] += 1
            A['lostAfterRet'] += x.get('lostAfterRet', 0)
            A['diedAfterRet'] += x.get('diedAfterRet', 0)
        pl = r['place'].replace(str(s), 'OWN') if r['place'].startswith('base') else r['place']
        if pl.startswith('base') and 'OWN' not in pl:
            pl = 'baseHIS'
        A['pl_' + pl + '_n'] += 1
        A['pl_' + pl + '_lost'] += x['lost']
        A['pl_' + pl + '_his'] += y['lost']
        twk = ('own' if r['tw'].find('U' if s == 0 else 'T') >= 0 else '') + ('his' if r['tw'].find('T' if s == 0 else 'U') >= 0 else '')
        twk = twk or 'none'
        A['tw_' + twk + '_n'] += 1
        A['tw_' + twk + '_lost'] += x['lost']
        A['tw_' + twk + '_his'] += y['lost']
        if r['simAct'] is not None:
            w, k, w1, k1 = r['simAct'] if s == 0 else (r['simAct'][2], r['simAct'][3], r['simAct'][0], r['simAct'][1])
            A['simN'] += 1
            A['simSaysWin'] += w
            A['simWinTradeLost'] += w and x['trade'] < 1
            A['simLoseTradeWon'] += (not w) and x['trade'] > 1
    return A


def print_summary(title, rows, s, out):
    A = agg(rows, s)
    if not A['fights']:
        out.append(f"{title}: no fights")
        return
    L = A['lost'] or 1
    out.append(f"{title}: fights={int(A['fights'])} won(trade>1)={int(A['won'])} lost={int(A['lostT'])} energy lost {f0(A['lost'])} "
               f"(creeps {f0(A['lostC'])}, structures {f0(A['lostS'])}) his {f0(A['hisLost'])} trade={A['hisLost'] / L:.2f}")
    out.append(f"  entry: {int(A['predF'])} fights after a push/strike: lost {f0(A['predLost'])} his {f0(A['predHis'])} "
               f"(trade {A['predHis'] / max(1, A['predLost']):.2f}); promised keep>=50% and traded worse: {int(A['predBad'])} fights, {f0(A['predBadLost'])} = {pct(A['predBadLost'] / L)} of our loss")
    out.append(f"  simAct (the bot's simulate on the forces that engaged): says win {int(A['simSaysWin'])}/{int(A['simN'])}; says win, trade lost {int(A['simWinTradeLost'])}; says lose, trade won {int(A['simLoseTradeWon'])}")
    out.append(f"  retreat: {int(A['turned'])} members turned away in contact, {int(A['turnedDied'])} of them died after the turn; lost after the turn {f0(A['lostAfterTurn'])} = {pct(A['lostAfterTurn'] / L)}; "
               f"bot retreat/recall inside {int(A['retF'])} fights: lost after it {f0(A['lostAfterRet'])} = {pct(A['lostAfterRet'] / L)}, {int(A['diedAfterRet'])} died after; {int(A['flipF'])} fights with 3+ push/strike/retreat lines inside (flip-flop): lost {f0(A['flipLost'])} his {f0(A['flipHis'])}")
    out.append(f"  stretch: {int(A['late'])} of {int(A['n'])} combat entries late (> {LATE} ticks after the first hit); {int(A['stretchF'])} fights with >= 25% of the hits late: "
               f"lost {f0(A['stretchLost'])} his {f0(A['stretchHis'])} (trade {A['stretchHis'] / max(1, A['stretchLost']):.2f}) vs the rest trade {A['tightHis'] / max(1, A['tightLost']):.2f}")
    out.append(f"  piecemeal deaths (died before a friend already out of its spawn joined the same fight): {int(A['piece'])}, worth {f0(A['pieceVal'])} = {pct(A['pieceVal'] / L)}; the friend within 20 ticks of the death: {int(A['pieceNear'])}, worth {f0(A['pieceNearVal'])} = {pct(A['pieceNearVal'] / L)}")
    out.append(f"  fire uptime: armed members after their entry {int(A['upt'])} creep-ticks: something of his within 3 in {pct(A['uptReach'] / max(1, A['upt']))}, fired in {pct(A['uptFired'] / max(1, A['upt']))} "
               f"(fired when in reach {pct(A['uptFired'] / max(1, A['uptReach']))})")
    out.append(f"  lone deaths: {int(A['lone'])} of {int(A['dead'])} dead, worth {f0(A['loneVal'])} = {pct(A['loneVal'] / L)}")
    out.append(f"  wounded in the front line and dead: {int(A['wf'])} of {int(A['deadC'])} dead combat creeps ({int(A['wfFresh'])} with a fresh friend behind), worth {f0(A['lostWF'])} = {pct(A['lostWF'] / L)}")
    out.append(f"  heal: effective {f0(A['healEff'])} of raw {f0(A['healRaw'])}, potential {f0(A['healPot'])} (used {pct(A['healEff'] / max(1, A['healPot']))}); towers healed {f0(A['towerHeal'])}")
    d = A['dmg'] or 1
    out.append(f"  healed back: his creeps and towers healed {f0(A['hisHealEff'])} = {pct(A['hisHealEff'] / max(1, A['dmgCreeps'] + A['tower']))} of our damage on his creeps (creeps' and towers')")
    out.append(f"  fire: creep damage {f0(A['dmg'])} -> healers {pct(A['sh_healer'] / d)} fighters {pct(A['sh_fighter'] / d)} workers {pct(A['sh_worker'] / d)} "
               f"structures {pct(A['sh_struct'] / d)} into ramparts {pct(A['sh_ramp'] / d)}; overkill {pct(A['over'] / d)}; focus {A['tgtW'] / max(1, A['focusW']):.2f} targets a tick "
               f"for {A['shW'] / max(1, A['focusW']):.2f} shooters; towers {f0(A['tower'])}")
    pl = []
    for k in ('field', 'baseOWN', 'baseHIS'):
        n = A[f'pl_{k}_n']
        if n:
            pl.append(f"{k} {int(n)}: lost {f0(A[f'pl_{k}_lost'])} his {f0(A[f'pl_{k}_his'])} (trade {A[f'pl_{k}_his'] / max(1, A[f'pl_{k}_lost']):.2f})")
    out.append("  place: " + '; '.join(pl))
    tw = []
    for k in ('none', 'own', 'his', 'ownhis'):
        n = A[f'tw_{k}_n']
        if n:
            tw.append(f"{k} {int(n)}: lost {f0(A[f'tw_{k}_lost'])} his {f0(A[f'tw_{k}_his'])} (trade {A[f'tw_{k}_his'] / max(1, A[f'tw_{k}_lost']):.2f})")
    out.append("  towers fired: " + '; '.join(tw))
    rank = [
        ('entry: promised keep>=50%, traded worse', A['predBadLost']),
        ("retreat: lost after the bot's retreat/recall", A['lostAfterRet']),
        ('retreat: lost after a member turned away in contact', A['lostAfterTurn']),
        ('stretch: piecemeal deaths', A['pieceVal']),
        ('stretch: piecemeal deaths, the friend within 20 ticks', A['pieceNearVal']),
        ('lone deaths', A['loneVal']),
        ('flip-flop: fights with 3+ strike/retreat lines, whole loss', A['flipLost']),
        ('rotation: wounded in front with a fresh friend behind, dead', A['lostWFfresh']),
        ('positions: net loss of lost fights away from our base', A['badPlaceLost']),
    ]
    out.append("  ranked (energy of ours each failure accounts for; overlapping cuts, share of our loss): " +
               '; '.join(f"{k} {f0(v)} ({pct(v / L)})" for k, v in sorted(rank, key=lambda kv: -kv[1])))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('traces', nargs='+')
    ap.add_argument('--fights', type=int, default=6, help='per run, print the N fights with the largest loss (0: none, -1: all)')
    ap.add_argument('--min', type=float, default=150, help='a fight with less energy lost on both sides is left out of the per-run list')
    ap.add_argument('--tsv', help='write one line per fight and side here')
    args = ap.parse_args()
    out = []
    all_rows = []
    by_opp = defaultdict(list)
    runs = []
    for p in sorted(args.traces):
        R = load_run(p)
        rows = fight_rows(R)
        runs.append((R, rows))
        all_rows += rows
        by_opp[R.opp].append((R, rows))
    out.append(f"{'run':<28} {'A':<5} {'B':<12} {'outcome':<46} fights  A lost  B lost  trade(A)  A dead/lone  A calib")
    for R, rows in runs:
        A = agg(rows, 0)
        cal = R.logs[0]['calib']
        cs = ' '.join(f"{c['kind'][0]}{c['t0']}:{c['pred']}->{c['act']}" for c in cal[:4]) + (' …' if len(cal) > 4 else '')
        out.append(f"{R.name:<28} {R.label[0]:<5} {R.label[1]:<12} {R.outcome[:46]:<46} {int(A['fights']):>6} {f0(A['lost']):>7} {f0(A['hisLost']):>7} "
                   f"{A['hisLost'] / max(1, A['lost']):>8.2f}  {int(A['dead']):>4}/{int(A['lone']):<4}  {cs}")
    out.append('')
    out.append('=== side A (the build under test) against each opponent ===')
    for opp, lst in sorted(by_opp.items()):
        rows = [r for _, rs in lst for r in rs]
        print_summary(f"A vs {opp} ({len(lst)} runs)", rows, 0, out)
    print_summary(f"A vs all ({len(runs)} runs)", all_rows, 0, out)
    print_summary(f"A vs all, main phase (fights before either side's last call)", [r for r in all_rows if r['phase'] == 'main'], 0, out)
    print_summary(f"A vs all, last call (fights after it)", [r for r in all_rows if r['phase'] == 'last'], 0, out)
    for opp, lst in sorted(by_opp.items()):
        rows = [r for _, rs in lst for r in rs if r['phase'] == 'main']
        print_summary(f"A vs {opp}, main phase", rows, 0, out)
    out.append('')
    out.append('=== side B, the same cut (a frozen build is our bot too) ===')
    for opp, lst in sorted(by_opp.items()):
        rows = [r for _, rs in lst for r in rs]
        print_summary(f"B={opp} vs A ({len(lst)} runs)", rows, 1, out)
    # the forecast: the bots' own calib lines (what push/strike promised at the start of the wave, what came)
    out.append('')
    out.append('=== the forecast: calib lines of side A (pred keep at the wave\'s start -> actual keep at its end) ===')
    cal = [c for R, _ in runs for c in R.logs[0]['calib'] if c['win']]
    if cal:
        err = [c['act'] - c['pred'] for c in cal]
        bad = [c for c in cal if c['act'] < 30]
        out.append(f"  {len(cal)} waves sent on a promised win: mean actual-pred {sum(err) / len(err):+.0f} points; actual keep < 30% in {len(bad)}; "
                   f"ended by retreat {sum(c['why'] == 'retreat' for c in cal)}, wiped {sum(c['why'] == 'wiped' for c in cal)}, recall {sum(c['why'] == 'recall' for c in cal)}, "
                   f"timeout {sum(c['why'] == 'timeout' for c in cal)}, superseded {sum(c['why'] == 'superseded' for c in cal)}")
    if args.fights:
        out.append('')
        out.append(f"=== fights (per run, the {'all' if args.fights < 0 else args.fights} with the largest loss; A = side 0, B = side 1; lost=creeps+structures; "
                   f"focus=targets/shooters a tick; turned=(died after, lost after); wf=wounded in front (with a fresh friend); heal=effective/raw/potential) ===")
        for R, rows in runs:
            big = sorted([r for r in rows if r[0]['lost'] + r[1]['lost'] >= args.min], key=lambda r: -(r[0]['lost'] + r[1]['lost']))
            if args.fights > 0:
                big = big[:args.fights]
            out.append(f"{R.name} ({R.label[0]} vs {R.label[1]}): {R.outcome}")
            for r in sorted(big, key=lambda r: r['start']):
                print_fight(r, out)
    if args.tsv:
        cols = ['run', 'opp', 'id', 'start', 'end', 'dur', 'phase', 'place', 'tw']
        side_cols = ['n', 'late', 'lateHits', 'wrk', 'dps', 'heal', 'hits', 'lostC', 'lostS', 'lost', 'trade', 'dead', 'deadC', 'lone', 'loneVal', 'keep', 'dmg',
                     'tower', 'over', 'mass', 'tgt', 'sh', 'top', 'upt', 'uptReach', 'uptFired', 'piece', 'pieceNear', 'pieceVal', 'turned', 'turnedDied', 'lostAfterTurn', 'wf', 'wfFresh', 'lostWF', 'healRaw', 'healEff', 'healPot', 'towerHeal']
        with open(args.tsv, 'w') as f:
            f.write('\t'.join(cols + ['side'] + side_cols + ['share_healer', 'share_fighter', 'share_worker', 'share_struct', 'share_ramp', 'pred', 'ret', 'simAct']) + '\n')
            for r in all_rows:
                for s in (0, 1):
                    x = r[s]
                    pr = x['pred']
                    f.write('\t'.join([str(r[c]) for c in cols] + [str(s)] + [f"{x[c]:.3f}" if isinstance(x[c], float) else str(x[c]) for c in side_cols] +
                                      [f"{x['share'][k]:.3f}" for k in ('healer', 'fighter', 'worker', 'struct', 'ramp')] +
                                      [f"{pr['k']}@{pr['t']}:{pr['keep']}" if pr else '', f"{x['ret']['k']}@{x['ret']['t']}" if x['ret'] else '', str(r['simAct'])]) + '\n')
    print('\n'.join(out))


if __name__ == '__main__':
    main()
