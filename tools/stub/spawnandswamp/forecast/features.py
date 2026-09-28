"""State features of one of his armed creeps at tick t, toward point P (which) and ball D. Only state at t and the
history before t: positions of his creep 10/20/50 ticks ago, his and our creeps now, structures now.

feat(d, F, t, ctx, rows, r, which, D) -> dict of named values (H-free); H enters only through Tb / H in the models.
"""
import math
import fc

INF = fc.INF


def c(x, y):
    return y * 100 + x


def clip(v, lo, hi):
    return lo if v < lo else hi if v > hi else v


class TickCache:
    """Per (match, t): things every creep of that tick shares."""

    def __init__(self, d, F, t, ctx, rows):
        self.t = t
        self.rows = rows
        self.ctx = ctx
        self.his_power = sum(r['w'] for r in rows)
        self.our_power = sum(o[2] for o in ctx['our_armed'])
        self.v20 = {}
        # attractors visible at t: what a creep of his can be walking to
        them = d['them']
        pos = d.get('_spos')
        if pos is None:
            pos = d['_spos'] = {s['id']: (s['x'], s['y']) for s in d['structs'] if s['kind'] in ('container', 'energy')}
        att = []
        for kind, side, x, y, sid in ctx['structs']:   # alive at t
            if kind == 'spawn':
                att.append((x, y, 'his_spawn' if side == them else 'our_spawn'))
            elif kind in ('container', 'energy') and (ctx['energy'].get(sid) or 0) > 0:
                att.append((x, y, 'energy'))
        for o in ctx['our_armed']:
            att.append((o[0], o[1], 'our_fighter'))
        for o in ctx['our_other']:
            att.append((o[0], o[1], 'our_worker'))
        self.att = att


def heading(tc, r):
    """What his creep is walking to, from its last 20 ticks: (target (x, y, kind) or None, holding).

    Holding = moved less than 3 cells in 20 ticks; its target is then the nearest attractor within 4 (working on it) or
    None. Walking: among attractors it closed in on by at least 70 % of its own displacement, the NEAREST one (the
    immediate objective — a far one lies in the same direction as everything on the way)."""
    p = r['hist'].get(20)
    if p is None or r['free_age'] < 20:
        return None, False
    x, y = r['x'], r['y']
    disp = fc.cheb(p[0], p[1], x, y)
    if disp < 3:
        near = [(fc.cheb(ax, ay, x, y), (ax, ay, k)) for ax, ay, k in tc.att if fc.cheb(ax, ay, x, y) <= 4]
        return (min(near)[1] if near else None), True
    best = None
    for ax, ay, k in tc.att:
        closed = fc.cheb(p[0], p[1], ax, ay) - fc.cheb(x, y, ax, ay)
        if closed >= 0.7 * disp:
            dd = fc.cheb(x, y, ax, ay)
            if best is None or dd < best[0]:
                best = (dd, (ax, ay, k))
    return (best[1] if best else None), False


def speed_toward(F, r, which, D, back):
    """Progress along HIS body's time field to the ball over `back` ticks, in field-ticks per tick (1 = walking
    straight at his full speed toward P); None if the creep is younger than `back`."""
    p = r['hist'].get(back)
    if p is None or r['free_age'] < back: return None
    f = F.ball(which, D, r['pp'], r['ps'])
    a, b = f[c(*p)], f[c(r['x'], r['y'])]
    if a >= INF or b >= INF: return None
    return (a - b) / back


def feat(d, F, tc, r, which, D):
    f = F.ball(which, D, r['pp'], r['ps'])
    Tb = f[c(r['x'], r['y'])]
    if Tb < INF: Tb += max(0, -r['free_age'])
    key = (r['cid'], which, D)
    v20 = tc.v20.get(key)
    if key not in tc.v20:
        v20 = speed_toward(F, r, which, D, 20)
        tc.v20[key] = v20
    v50 = speed_toward(F, r, which, D, 50)
    p20 = r['hist'].get(20)
    disp20 = fc.cheb(p20[0], p20[1], r['x'], r['y']) if p20 is not None and r['free_age'] >= 20 else None
    # the pack: his armed within 6 cells (not counting this one)
    pack_w, pack_vw, pack_n = 0.0, 0.0, 0
    army_w, army_vw = 0.0, 0.0
    for q in tc.rows:
        k2 = (q['cid'], which, D)
        if k2 not in tc.v20:
            tc.v20[k2] = speed_toward(F, q, which, D, 20)
        vq = tc.v20[k2]
        if vq is not None:
            army_w += q['w']; army_vw += q['w'] * vq
        if q is r: continue
        if fc.cheb(q['x'], q['y'], r['x'], r['y']) <= 6:
            pack_n += 1
            if vq is not None:
                pack_w += q['w']; pack_vw += q['w'] * vq
    P = d['P'][which]
    other = d['P']['them' if which == 'us' else 'us']
    ours_near_P = sum(o[2] for o in tc.ctx['our_armed'] if fc.cheb(o[0], o[1], *P) <= 12)
    ours_near_his_main = sum(o[2] for o in tc.ctx['our_armed'] if fc.cheb(o[0], o[1], *d['P']['them']) <= 15)
    ours_near_me = sum(o[2] for o in tc.ctx['our_armed'] if fc.cheb(o[0], o[1], r['x'], r['y']) <= 8)
    our_haulers_near_P = sum(1 for o in tc.ctx['our_other'] if fc.cheb(o[0], o[1], *P) <= 12)
    near_our_hauler = min([fc.cheb(o[0], o[1], r['x'], r['y']) for o in tc.ctx['our_other']] or [99])
    pack_power = r['w'] + sum(q['w'] for q in tc.rows if q is not r and fc.cheb(q['x'], q['y'], r['x'], r['y']) <= 6)
    return dict(
        Tb=Tb,
        v20=v20, v50=v50, disp20=disp20, young=r['free_age'] < 20,
        pack_n=pack_n, pack_v=(pack_vw / pack_w) if pack_w else None,
        army_v=(army_vw / army_w) if army_w else None,
        his_power=tc.his_power, our_power=tc.our_power, pack_power=pack_power,
        ours_near_P=ours_near_P, ours_near_his_main=ours_near_his_main, ours_near_me=ours_near_me,
        haulers_near_P=our_haulers_near_P, near_our_hauler=near_our_hauler,
        tn=tc.t / 2000.0, hitsf=r['hits'] / max(1, r['hmax']),
        from_other=fc.cheb(r['x'], r['y'], *other), cheb_P=fc.cheb(r['x'], r['y'], *P),
        melee=r['melee'] > 0,
    )


def vector(fe, H, which):
    """Numeric vector for the logistic model (all features scale-free; H enters as reach ratio)."""
    Tb = fe['Tb']
    rr = 3.0 if Tb >= INF else min(Tb / H, 3.0)
    v20 = fe['v20'] if fe['v20'] is not None else 0.0
    v50 = fe['v50'] if fe['v50'] is not None else 0.0
    pv = fe['pack_v'] if fe['pack_v'] is not None else v20
    av = fe['army_v'] if fe['army_v'] is not None else 0.0
    still = 1.0 if (fe['disp20'] is not None and fe['disp20'] < 3) else 0.0
    # ETA by own observed approach, as a fraction of H (capped): the M0 signal in body units
    eta = (Tb / v20 / H) if (v20 > 0.05 and Tb < INF) else 3.0
    eta = min(eta, 3.0)
    return [
        1.0,
        rr, rr * rr,
        clip(v20, -1.5, 1.5), clip(v50, -1.5, 1.5),
        clip(pv, -1.5, 1.5), clip(av, -1.5, 1.5),
        still, 1.0 if fe['young'] else 0.0,
        eta,
        math.log((fe['his_power'] + 30) / (fe['our_power'] + 30)),
        math.log((fe['ours_near_P'] + 30) / (fe['pack_power'] + 30)),
        math.log(1 + fe['ours_near_his_main'] / 30.0),
        math.log(1 + fe['ours_near_me'] / 30.0),
        math.log(1 + fe['pack_n']),
        fe['tn'], fe['hitsf'],
        min(fe['from_other'], 100) / 100.0,
        min(fe['near_our_hauler'], 30) / 30.0,
        1.0 if fe['melee'] else 0.0,
        fe.get('toP', 0.0),
    ]


NAMES = ['bias', 'reach/H', '(reach/H)^2', 'v20', 'v50', 'pack_v', 'army_v', 'still', 'young', 'eta/H',
         'log his/our power', 'log ourNearP/pack', 'log ourNearHisMain', 'log ourNearMe', 'log pack_n', 't/2000',
         'hits', 'fromOtherSpawn', 'nearOurHauler', 'melee', 'headingToP']
