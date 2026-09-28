"""Models beyond the bot's M0 and plain reachability M1.

M0b     M0 on HIS body's time field (the bot rates the approach on the swamp x5 field, then divides by it)
M2a<α>  "near is a threat, far is a threat only when it comes": M0 in body units UNION reach <= α·H
M2p<α>  M2a plus the pack: a creep within 6 of a creep M2a announces is announced too if it can reach within H
M3@<m>  logistic regression on features.vector, trained leave-one-FAMILY-out (the family under test is never in its
        training set); the tick threshold on P(any) = 1 - prod(1 - p) is chosen on the TRAINING families so that their
        tick-level miss is <= m % — the test family's miss is what is reported.
"""
import math, random, re
import fc
import features as FT
from models import Base, cell, M0, M1, reach

INF = fc.INF


class M0b(Base):
    name = 'M0b'

    def creep_eta(self, d, F, t, ctx, rows, r, which, D):
        Tb = reach(F, r, which, D)
        if Tb >= INF: return INF
        if r['free_age'] < 10: return Tb
        v = FT.speed_toward(F, r, which, D, 20 if r['free_age'] >= 20 else 10)
        if v is None or v <= 0: return INF
        return Tb / v


class M2a(Base):
    def __init__(self, alpha):
        self.alpha = alpha
        self.m0 = M0b()
        self.name = f'M2a{int(alpha * 100)}'

    def predict(self, d, F, fam, args):
        out = {}
        for t, ctx, rows in d['samples']:
            if not rows: continue
            total = sum(r['w'] for r in rows)
            for which in args.P:
                for D in args.D:
                    base = []
                    for r in rows:
                        if r[f'in_{which}_{D}']: continue
                        Tb = reach(F, r, which, D)
                        base.append((r, Tb, self.m0.creep_eta(d, F, t, ctx, rows, r, which, D)))
                    for H in args.H:
                        arr = self.announce(base, H, rows, which, D)
                        out[(t, which, D, H)] = (bool(arr), sum(r['w'] for r in arr) / total if total else 0.0)
        return out

    def announce(self, base, H, rows, which, D):
        return [r for r, Tb, eta in base if Tb <= self.alpha * H or (eta <= H and Tb <= H)]


class M2p(M2a):
    def __init__(self, alpha):
        super().__init__(alpha)
        self.name = f'M2p{int(alpha * 100)}'

    def announce(self, base, H, rows, which, D):
        ann = super().announce(base, H, rows, which, D)
        ids = {r['cid'] for r in ann}
        for r, Tb, eta in base:
            if r['cid'] in ids or Tb > H: continue
            if any(fc.cheb(q['x'], q['y'], r['x'], r['y']) <= 6 for q in ann):
                ann.append(r)
        return ann


class M1a(M2a):
    """Pure proximity: reach <= α·H (M1 on a shorter horizon; no intent at all)."""

    def __init__(self, alpha):
        super().__init__(alpha)
        self.name = f'M1a{int(alpha * 100)}'

    def announce(self, base, H, rows, which, D):
        return [r for r, Tb, eta in base if Tb <= self.alpha * H]


class M2r(Base):
    """M2a with the proximity horizon COMPUTED, not named: R = the walk home of our farthest armed creep (swamp x5
    field to our main's ball — a MOVE-half body), i.e. the warning we need to bring the wave back. His creep is a threat
    if it can reach home within max(R, 0) whatever it does, or its observed approach brings it within H. For his main
    (raiders cannot be recalled) R = H, which is M1."""
    name = 'M2r'

    def __init__(self):
        self.m0 = M0b()

    def predict(self, d, F, fam, args):
        out = {}
        for t, ctx, rows in d['samples']:
            if not rows: continue
            total = sum(r['w'] for r in rows)
            for which in args.P:
                for D in args.D:
                    fw = F.ball('us', D, 1, 5)
                    R = max([fw[cell(o[0], o[1])] for o in ctx['our_armed'] if o[3] > 0 and fw[cell(o[0], o[1])] < INF] or [0])
                    base = []
                    for r in rows:
                        if r[f'in_{which}_{D}']: continue
                        Tb = reach(F, r, which, D)
                        base.append((r, Tb, self.m0.creep_eta(d, F, t, ctx, rows, r, which, D)))
                    for H in args.H:
                        prox = min(R, H) if which == 'us' else H
                        arr = [r for r, Tb, eta in base if Tb <= prox or (eta <= H and Tb <= H)]
                        out[(t, which, D, H)] = (bool(arr), sum(r['w'] for r in arr) / total if total else 0.0)
        return out


class M4(Base):
    """Attractors: what is he walking to? Targets visible at t — our spawns, our creeps (fighters, workers), his spawns,
    energy (containers, piles). The target is the NEAREST attractor his creep closed in on by >= 70 % of its own
    displacement over 20 ticks (holding: moved < 3 — the attractor it stands at, within 4). He comes to P when his target
    is at P (within D) or P lies on the way to it (Chebyshev detour <= D); announced if his body reaches P within H.
    Plus the proximity rule of M2a: reach <= α·H is a threat whatever he walks to (α = 0 switches it off)."""

    def __init__(self, alpha):
        self.alpha = alpha
        self.name = f'M4a{int(alpha * 100)}'

    def predict(self, d, F, fam, args):
        out = {}
        for t, ctx, rows in d['samples']:
            if not rows: continue
            total = sum(r['w'] for r in rows)
            tc = FT.TickCache(d, F, t, ctx, rows)
            heads = {r['cid']: FT.heading(tc, r) for r in rows}
            for which in args.P:
                P = d['P'][which]
                for D in args.D:
                    base = []
                    for r in rows:
                        if r[f'in_{which}_{D}']: continue
                        Tb = reach(F, r, which, D)
                        tgt, holding = heads[r['cid']]
                        toP = False
                        if r['free_age'] < 20 and r['free_age'] >= 0 or r['free_age'] < 0:
                            toP = None   # no history yet: unknown
                        elif tgt is not None:
                            ax, ay, k = tgt
                            if fc.cheb(ax, ay, *P) <= D: toP = True
                            elif not holding and fc.cheb(r['x'], r['y'], *P) + fc.cheb(ax, ay, *P) <= fc.cheb(r['x'], r['y'], ax, ay) + D:
                                toP = True
                        base.append((r, Tb, toP))
                    for H in args.H:
                        arr = [r for r, Tb, toP in base
                               if Tb <= self.alpha * H or (Tb <= H and toP is not False)]
                        out[(t, which, D, H)] = (bool(arr), sum(r['w'] for r in arr) / total if total else 0.0)
        return out


# ---------------------------------------------------------------------------------------------- logistic, by family

def sigmoid(z):
    if z < -30: return 1e-13
    if z > 30: return 1 - 1e-13
    return 1.0 / (1.0 + math.exp(-z))


def solve(A, b):
    n = len(b)
    M = [row[:] + [b[i]] for i, row in enumerate(A)]
    for i in range(n):
        piv = max(range(i, n), key=lambda k: abs(M[k][i]))
        M[i], M[piv] = M[piv], M[i]
        if abs(M[i][i]) < 1e-12: continue
        for k in range(i + 1, n):
            f = M[k][i] / M[i][i]
            if f:
                for j in range(i, n + 1): M[k][j] -= f * M[i][j]
    x = [0.0] * n
    for i in range(n - 1, -1, -1):
        s = M[i][n] - sum(M[i][j] * x[j] for j in range(i + 1, n))
        x[i] = s / M[i][i] if abs(M[i][i]) > 1e-12 else 0.0
    return x


def fit_logistic(X, y, lam=1.0, iters=12):
    n = len(X[0])
    w = [0.0] * n
    for _ in range(iters):
        g = [0.0] * n
        Hs = [[0.0] * n for _ in range(n)]
        for xi, yi in zip(X, y):
            z = sum(a * b for a, b in zip(w, xi))
            p = sigmoid(z)
            e = p - yi
            s = p * (1 - p)
            for j in range(n):
                xj = xi[j]
                if xj == 0.0: continue
                g[j] += e * xj
                sx = s * xj
                row = Hs[j]
                for k in range(j, n):
                    row[k] += sx * xi[k]
        for j in range(n):
            for k in range(j):
                Hs[j][k] = Hs[k][j]
            if j: g[j] += lam * w[j]; Hs[j][j] += lam
            else: Hs[j][j] += 1e-6
        step = solve(Hs, g)
        w = [a - b for a, b in zip(w, step)]
        if max(abs(s) for s in step) < 1e-4: break
    return w


FEATS = {}      # gid -> {(t, which, D): ([(row, fe)], his power)} — shared by every M3 instance
WEIGHTS = {}    # (test_fam, which, D, H) -> weights — shared too (the target only moves the threshold)
FEATURE_VERSION = 4


def features_for(gid):
    if gid in FEATS: return FEATS[gid]
    d = fc.load_samples(gid)
    F = fc.Fields(d)
    out = {}
    for t, ctx, rows in d['samples']:
        if not rows: continue
        tc = FT.TickCache(d, F, t, ctx, rows)
        heads = {r['cid']: FT.heading(tc, r) for r in rows}
        for which in ('us', 'them'):
            P = d['P'][which]
            for D in fc.DS:
                lst = []
                for r in rows:
                    if r[f'in_{which}_{D}']: continue
                    fe = FT.feat(d, F, tc, r, which, D)
                    tgt, holding = heads[r['cid']]
                    toP = 0.0
                    if r['free_age'] >= 20:
                        toP = -1.0
                        if tgt is not None:
                            ax, ay, k = tgt
                            if fc.cheb(ax, ay, *P) <= D: toP = 1.0
                            elif not holding and fc.cheb(r['x'], r['y'], *P) + fc.cheb(ax, ay, *P) <= fc.cheb(r['x'], r['y'], ax, ay) + D:
                                toP = 1.0
                    fe['toP'] = toP
                    lst.append((r, fe))
                out[(t, which, D)] = (lst, tc.his_power)
    FEATS[gid] = out
    return out


class M3:
    """Logistic P(creep arrives within H) — trained leave-one-family-out, threshold from the training families."""

    def __init__(self, target_miss):
        self.target = target_miss / 100.0
        self.name = f'M3@{target_miss:g}'
        self.models = {}     # (fam, which, D, H) -> (weights, threshold)

    def fit(self, ids, fam_of, args):
        import os, pickle, hashlib
        cache = os.path.join(fc.CACHE, 'm3_weights.pkl')
        saved = {}
        if os.path.exists(cache):
            with open(cache, 'rb') as f: saved = pickle.load(f)
        idh = hashlib.sha1(' '.join(sorted(ids)).encode()).hexdigest()[:12]
        fams = {}
        for gid in ids:
            fams[gid] = fam_of(fc.load_samples(gid)['opp'])
            features_for(gid)
        rnd = random.Random(7)
        for which in args.P:
            for D in args.D:
                for H in args.H:
                    for test_fam in sorted(set(fams.values())):
                        X, y, groups = [], [], []
                        for gid in ids:
                            if fams[gid] == test_fam: continue
                            for (t, wh, DD), (lst, total) in FEATS[gid].items():
                                if wh != which or DD != D or t > 2000 - H or not lst: continue
                                g = []
                                for r, fe in lst:
                                    X.append(FT.vector(fe, H, which)); y.append(1.0 if r[f'dt_{which}_{D}'] <= H else 0.0)
                                    g.append(len(X) - 1)
                                groups.append(g)
                        key = (test_fam, which, D, H, idh, FEATURE_VERSION)
                        w = saved.get(key)
                        if w is None:
                            # subsample whole ticks for speed
                            if len(X) > 12000:
                                keep = rnd.sample(range(len(groups)), max(1, int(len(groups) * 12000 / len(X))))
                                idx = [i for gi in keep for i in groups[gi]]
                            else:
                                idx = list(range(len(X)))
                            w = fit_logistic([X[i] for i in idx], [y[i] for i in idx])
                            saved[key] = w
                            with open(cache, 'wb') as f: pickle.dump(saved, f)
                        WEIGHTS[(test_fam, which, D, H)] = w
                        # threshold on P(any) so that the TRAINING families' tick-level miss <= target
                        pany, tany = [], []
                        for g in groups:
                            q = 1.0
                            for i in g:
                                q *= 1 - sigmoid(sum(a * b for a, b in zip(w, X[i])))
                            pany.append(1 - q); tany.append(any(y[i] for i in g))
                        pos = sorted(p for p, tr in zip(pany, tany) if tr)
                        k = int(self.target * len(pos))
                        thr = pos[k] if pos and k < len(pos) else 0.5
                        self.models[(test_fam, which, D, H)] = (w, max(thr - 1e-9, 1e-9))

    def predict(self, d, F, fam, args):
        out = {}
        feats = features_for(d['gid'])
        for (t, which, D), (lst, total) in feats.items():
            if which not in args.P or D not in args.D: continue
            for H in args.H:
                m = self.models.get((fam, which, D, H))
                if m is None: continue
                w, thr = m
                q = 1.0; share = 0.0
                for r, fe in lst:
                    p = sigmoid(sum(a * b for a, b in zip(w, FT.vector(fe, H, which))))
                    q *= 1 - p; share += p * r['w']
                out[(t, which, D, H)] = (1 - q >= thr, share / total if total else 0.0)
        for t, ctx, rows in d['samples']:
            for which in args.P:
                for D in args.D:
                    for H in args.H:
                        out.setdefault((t, which, D, H), (False, 0.0))
        return out


class LOG:
    """The Kotlin port, scored by this same stand: reads the bot's own forecast lines from the replay's console,
        forecast t=<tick> P=<us|them> D=<5|12> H=<h> any=<0|1> share=<0..1>
    (one line per (P, D, H) every STEP ticks at least). A sample tick with no line counts as "nobody comes" and is
    reported as missing coverage on stderr — a port that does not print is not scored as a good one."""
    name = 'LOG'
    RX = re.compile(r'forecast t=(\d+) P=(us|them) D=(\d+) H=(\d+) any=([01]) share=([\d.]+)')
    RXC = re.compile(r'forecast t=(\d+)((?: (?:us|them)\d+@\d+=[01]/[\d.]+)+)')
    RXI = re.compile(r'(us|them)(\d+)@(\d+)=([01])/([\d.]+)')

    def predict(self, d, F, fam, args):
        import os, sys as _s
        doc, meta, names = fc.R.load(os.path.join(fc.REPLAYS, d['gid'] + '.replay.json.gz'))
        got = {}
        for text in (doc.get('logs') or {}).values():
            for m in self.RX.finditer(text):
                got[(int(m.group(1)), m.group(2), int(m.group(3)), int(m.group(4)))] = (m.group(5) == '1', float(m.group(6)))
            # the bot's compact line (v222): forecast t=1230 us5@200=1/0.43 them5@75=0/0.0 them5@150=1/0.12
            for line in self.RXC.finditer(text):
                t = int(line.group(1))
                for m in self.RXI.finditer(line.group(2)):
                    got[(t, m.group(1), int(m.group(2)), int(m.group(3)))] = (m.group(4) == '1', float(m.group(5)))
        out, miss = {}, 0
        for t, ctx, rows in d['samples']:
            for which in args.P:
                for D in args.D:
                    for H in args.H:
                        v = got.get((t, which, D, H))
                        if v is None: miss += 1; v = (False, 0.0)
                        out[(t, which, D, H)] = v
        if miss:
            print(f"LOG {d['gid']}: {miss} sample predictions missing from the console", file=_s.stderr)
        return out


def get(name):
    if name == 'LOG': return LOG()
    if name == 'M2r': return M2r()
    if name == 'M0b': return M0b()
    m = re.fullmatch(r'M2([ap])(\d+)', name)
    if m:
        return (M2a if m.group(1) == 'a' else M2p)(int(m.group(2)) / 100.0)
    m = re.fullmatch(r'M1a(\d+)', name)
    if m:
        return M1a(int(m.group(1)) / 100.0)
    m = re.fullmatch(r'M4a(\d+)', name)
    if m:
        return M4(int(m.group(1)) / 100.0)
    m = re.fullmatch(r'M3@([\d.]+)', name)
    if m:
        return M3(float(m.group(1)))
    raise SystemExit(f'unknown model {name}')
