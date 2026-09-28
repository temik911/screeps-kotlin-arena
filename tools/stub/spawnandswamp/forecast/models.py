"""Forecast models. Each reads only the state at t and the history before t (rows of fc.build_samples: position,
body, age, positions 10/20/30/50/100 ticks ago; ctx: our creeps, structures, energy at t).

predict(d, F, fam, args) -> {(t, which, D, H): (pred_any: bool, pred_share: float)}
"""
import math
import fc

INF = fc.INF


def cell(x, y):
    return y * 100 + x


class Base:
    name = '?'

    def creep_eta(self, d, F, t, ctx, rows, r, which, D):
        """ETA in ticks for creep r to be within D of P (INF = never)."""
        raise NotImplementedError

    def predict(self, d, F, fam, args):
        out = {}
        for t, ctx, rows in d['samples']:
            if not rows: continue
            total = sum(r['w'] for r in rows)
            self.prepare(d, F, t, ctx, rows)
            for which in args.P:
                for D in args.D:
                    etas = [(r, self.creep_eta(d, F, t, ctx, rows, r, which, D)) for r in rows if not r[f'in_{which}_{D}']]
                    for H in args.H:
                        arr = [r for r, e in etas if e <= H]
                        out[(t, which, D, H)] = (bool(arr), sum(r['w'] for r in arr) / total if total else 0.0)
        return out

    def prepare(self, d, F, t, ctx, rows):
        pass


class M1(Base):
    """Reachability: his body, the terrain (plain / swamp periods of its body), the shortest time to the ball."""
    name = 'M1'

    def creep_eta(self, d, F, t, ctx, rows, r, which, D):
        return reach(F, r, which, D)


def reach(F, r, which, D):
    """Earliest possible arrival: his body's time field to the ball, plus what is left of his birth."""
    Tb = F.ball(which, D, r['pp'], r['ps'])[cell(r['x'], r['y'])]
    return Tb + max(0, -r['free_age']) if Tb < INF else INF


class M0(Base):
    """The bot today (enemyArrivalTicks): approach rate along the swamp-weighted field over the last 20 ticks,
    ETA = distance / rate; not approaching = never; a creep younger than half the window walks the field with its body."""
    name = 'M0'

    def creep_eta(self, d, F, t, ctx, rows, r, which, D):
        fw = F.ball(which, D, 1, 5)
        now = fw[cell(r['x'], r['y'])]
        if now >= INF: return INF
        if r['free_age'] < 10:
            # new or still being born: its body along the field plus what is left of its birth (visible in the game)
            return F.ball(which, D, r['pp'], r['ps'])[cell(r['x'], r['y'])] + max(0, -r['free_age'])
        back = 20 if r['free_age'] >= 20 else 10
        p = r['hist'].get(back)
        if p is None: return INF
        prev = fw[cell(*p)]
        if prev >= INF: return INF
        rate = (prev - now) / back
        return now / rate if rate > 0 else INF


REGISTRY = {'M0': M0, 'M1': M1}


def get(name):
    if name in REGISTRY:
        return REGISTRY[name]()
    import models_ext  # noqa: late models live there
    return models_ext.get(name)
