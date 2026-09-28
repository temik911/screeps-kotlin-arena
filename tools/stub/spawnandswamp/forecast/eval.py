#!/usr/bin/env python3
"""Forecast stand: "where will HIS army go" — models x point P x horizon H, measured on played replays.

    python3 eval.py --model M0 M1 M2 --replays replays.txt [--P us them] [--H 50 100 150 200 300] [--D 5 12]
                    [--by-family] [--context] [--dump fails.txt]

Truth (from the replay): his armed creep (live ATTACK/RANGED/HEAL, live MOVE) outside Chebyshev D of P at tick t
"arrives" if it is within D of P at some tick in (t, t+H] (the window is cut at the match end: after the end nothing
arrives). Sample ticks t = 100, 110, ..., 2000-H. A model sees only the state at t and the history before t.

Per (match, t): ANY = someone arrives; SHARE = arriving power / all his armed power at t (power = damage+heal per tick of
live parts, ATTACK 30, RANGED 10, HEAL 12). Reported per model x P x D x H:
  miss = FN/(TP+FN)  -- arrivals the model did not announce (home: the dangerous error)
  FOR  = FN/(FN+TN)  -- among ticks declared "nobody comes", the share where somebody came (his main: "safe" windows
                        into which his army returned)
  prec = TP/(TP+FP), decl = share of ticks declared "nobody comes", big-miss = same as miss for SHARE >= 30 %.
  shareMAE = mean |predicted share - true share|.
Context columns: home — our wave out (>= 50 % of our armed power farther than 15 from our main); his main — his army
far (< 25 % of his armed power within 12 of his main). --strike adds "his army far AND our armed within 15 of his main".

Models (models.py, models_ext.py): M0 the bot's enemyArrivalTicks; M0b the same on his body's time field; M1 reach
(his body, plain/swamp periods, + visible remaining birth); M1a<α> reach <= α·H; M2a<α> M0b ∪ reach <= α·H (the
recommended family); M2p<α> M2a + pack; M4a<α> attractors (what he walks to) ∪ reach <= α·H; M3@<m> logistic,
leave-one-family-out, threshold for training miss <= m %; LOG the bot's own `forecast t=… P=… D=… H=… any=… share=…`
console lines (to score a Kotlin port with this same stand: replays of matches the port played, listed in --replays).
Other tools here: lead.py (warning lead time per arrival event), case.py (one match tick by tick), misses.py.
Samples are cached in ~/ScreepsArena/forecast-cache/<id>.pkl.gz (built by fc.py from ~/ScreepsArena/replays/<id>.replay.json.gz).
"""
import argparse, math, os, sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fc  # noqa: E402
import models  # noqa: E402

INF = fc.INF


def fam_of(name):
    if name is None: return 'other'
    if name.startswith('けろびー'): return 'kerobii'
    if 'marlyman' in name: return 'marlyman'
    if name.startswith('Ranamar'): return 'ranamar'
    if name.startswith('●ω'): return 'omega'
    if name.startswith('ricardo'): return 'ricardo'
    if name.startswith('stachu'): return 'stachu'
    return 'other'


def read_ids(path):
    ids = []
    for line in open(path, encoding='utf-8'):
        line = line.strip()
        if line and not line.startswith('#'): ids.append(line.split()[0])
    return ids


def contexts(d, t, ctx, rows):
    Pus, Pth = d['P']['us'], d['P']['them']
    ours = ctx['our_armed']
    tot = sum(o[2] for o in ours)
    out_share = (sum(o[2] for o in ours if fc.cheb(o[0], o[1], *Pus) > 15) / tot) if tot else 0.0
    his = sum(r['w'] for r in rows)
    near = sum(r['w'] for r in rows if fc.cheb(r['x'], r['y'], *Pth) <= 12)
    ours_at_his = sum(o[2] for o in ours if fc.cheb(o[0], o[1], *Pth) <= 15)
    return dict(wave_out=tot > 0 and out_share >= 0.5, his_far=his > 0 and near < 0.25 * his,
                strike=ours_at_his > 0 and his > 0 and near < 0.25 * his)


CAL_BINS = (0.0, 0.1, 0.3, 0.6, 1.01)


class Cell:
    __slots__ = ('tp', 'fp', 'fn', 'tn', 'btp', 'bfn', 'mae', 'n', 'fnex', 'cal')

    def __init__(self):
        self.tp = self.fp = self.fn = self.tn = self.btp = self.bfn = 0
        self.mae = 0.0; self.n = 0; self.fnex = []
        self.cal = [[0, 0.0, 0.0] for _ in range(len(CAL_BINS) - 1)]   # per predicted-share bin: n, sum pred, sum true

    def add(self, true_any, pred_any, true_share, pred_share, ex):
        for i in range(len(CAL_BINS) - 1):
            if CAL_BINS[i] <= pred_share < CAL_BINS[i + 1]:
                c = self.cal[i]; c[0] += 1; c[1] += pred_share; c[2] += true_share
                break
        if true_any and pred_any: self.tp += 1
        elif pred_any: self.fp += 1
        elif true_any:
            self.fn += 1
            if len(self.fnex) < 400: self.fnex.append(ex)
        else: self.tn += 1
        if true_share >= 0.3:
            if pred_any: self.btp += 1
            else: self.bfn += 1
        self.mae += abs(true_share - pred_share); self.n += 1

    def stats(self):
        pos = self.tp + self.fn
        miss = self.fn / pos if pos else float('nan')
        dneg = self.fn + self.tn
        FOR = self.fn / dneg if dneg else float('nan')
        prec = self.tp / (self.tp + self.fp) if self.tp + self.fp else float('nan')
        decl = dneg / self.n if self.n else float('nan')
        bpos = self.btp + self.bfn
        bmiss = self.bfn / bpos if bpos else float('nan')
        return dict(n=self.n, pos=pos, miss=miss, FOR=FOR, prec=prec, decl=decl, bmiss=bmiss,
                    mae=self.mae / self.n if self.n else float('nan'))


def pct(x):
    return '  -  ' if x != x else f'{100 * x:5.1f}'


def run(args):
    ids = read_ids(args.replays)
    mods = {name: models.get(name) for name in args.model}
    # learned models get the whole id list and train leave-one-family-out inside
    for name, mod in mods.items():
        if hasattr(mod, 'fit'):
            mod.fit(ids, fam_of, args)
    cells = defaultdict(Cell)
    for gid in ids:
        d = fc.load_samples(gid)
        fam = fam_of(d['opp'])
        F = fc.Fields(d)
        preds = {}
        for name, mod in mods.items():
            preds[name] = mod.predict(d, F, fam, args)   # {(t, which, D, H): (pred_any, pred_share, per-creep)}
        for t, ctx, rows in d['samples']:
            if not rows: continue
            cx = contexts(d, t, ctx, rows)
            total = sum(r['w'] for r in rows)
            for which in args.P:
                for D in args.D:
                    outside = [r for r in rows if not r[f'in_{which}_{D}']]
                    if not outside: continue   # all his armed already at P: nothing to forecast
                    for H in args.H:
                        if t > 2000 - H: continue
                        arr = [r for r in outside if r[f'dt_{which}_{D}'] <= H]
                        true_any = bool(arr)
                        true_share = sum(r['w'] for r in arr) / total if total else 0.0
                        for name in mods:
                            pa, ps = preds[name][(t, which, D, H)]
                            ex = (gid, t, len(arr), round(true_share, 2), d['opp'])
                            keys = [('all', fam), ('all', 'ALL')]
                            if which == 'us' and cx['wave_out']: keys += [('ctx', fam), ('ctx', 'ALL')]
                            if which == 'them' and cx['his_far']: keys += [('ctx', fam), ('ctx', 'ALL')]
                            if which == 'them' and cx['strike']: keys += [('strike', 'ALL')]
                            for sc, fm in keys:
                                cells[(name, which, D, H, sc, fm)].add(true_any, pa, true_share, ps, ex)
    return cells


def report(cells, args):
    fams = sorted({k[5] for k in cells})
    for which in args.P:
        qlabel = 'home (our main spawn)' if which == 'us' else 'his main spawn'
        ctxlabel = 'our wave out' if which == 'us' else 'his army far'
        for D in args.D:
            print(f'\n=== P = {qlabel}, D = {D}.  columns: all ticks | {ctxlabel}')
            print(f"{'model':8} {'H':>4} | {'n':>5} {'pos%':>5} {'miss':>5} {'FOR':>5} {'prec':>5} {'decl':>5} {'bigmiss':>7} {'MAE':>5} |"
                  f" {'n':>5} {'pos%':>5} {'miss':>5} {'FOR':>5} {'prec':>5} {'decl':>5} {'bigmiss':>7}")
            for name in args.model:
                for H in args.H:
                    a = cells.get((name, which, D, H, 'all', 'ALL'))
                    c = cells.get((name, which, D, H, 'ctx', 'ALL'))
                    if a is None: continue
                    s = a.stats()
                    line = (f"{name:8} {H:>4} | {s['n']:>5} {pct(s['pos'] / s['n'])} {pct(s['miss'])} {pct(s['FOR'])} {pct(s['prec'])}"
                            f" {pct(s['decl'])} {pct(s['bmiss']):>7} {s['mae']:5.2f} |")
                    if c is not None:
                        s = c.stats()
                        line += (f" {s['n']:>5} {pct(s['pos'] / s['n'])} {pct(s['miss'])} {pct(s['FOR'])} {pct(s['prec'])}"
                                 f" {pct(s['decl'])} {pct(s['bmiss']):>7}")
                    print(line)
            if args.calib:
                print('  share calibration (all ticks): predicted-share bin -> n, mean predicted, mean TRUE share')
                for name in args.model:
                    for H in args.H:
                        a = cells.get((name, which, D, H, 'all', 'ALL'))
                        if a is None: continue
                        parts = []
                        for i, (n, sp, st) in enumerate(a.cal):
                            if n: parts.append(f"[{CAL_BINS[i]:.1f},{min(CAL_BINS[i + 1], 1):.1f}) n={n} {sp / n:.2f}->{st / n:.2f}")
                        print(f'  {name:6} H={H:<4} ' + '  '.join(parts))
            if which == 'them' and args.strike:
                print(f'  strike context (our armed within 15 of his main, his army far):')
                for name in args.model:
                    for H in args.H:
                        a = cells.get((name, which, D, H, 'strike', 'ALL'))
                        if a is None or a.n == 0: continue
                        s = a.stats()
                        print(f"  {name:8} {H:>4} | {s['n']:>5} {pct(s['pos'] / s['n'])} {pct(s['miss'])} {pct(s['FOR'])} {pct(s['prec'])}"
                              f" {pct(s['decl'])} {pct(s['bmiss']):>7}")
            if args.by_family:
                print(f'  by family (all ticks), miss / FOR / prec / decl:')
                for name in args.model:
                    for H in args.H:
                        parts = []
                        for fm in fams:
                            if fm == 'ALL': continue
                            a = cells.get((name, which, D, H, 'all', fm))
                            if a is None or a.n == 0: continue
                            s = a.stats()
                            parts.append(f"{fm}:{pct(s['miss']).strip()}/{pct(s['FOR']).strip()}/{pct(s['prec']).strip()}/{pct(s['decl']).strip()}")
                        print(f'  {name:6} H={H:<4} ' + '  '.join(parts))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--model', nargs='+', default=['M0', 'M1'])
    ap.add_argument('--replays', default=os.path.join(HERE, 'replays.txt'))
    ap.add_argument('--P', nargs='+', default=['us', 'them'])
    ap.add_argument('--D', nargs='+', type=int, default=[5, 12])
    ap.add_argument('--H', nargs='+', type=int, default=list(fc.HS))
    ap.add_argument('--by-family', action='store_true')
    ap.add_argument('--strike', action='store_true', help='also the strike context for his main')
    ap.add_argument('--calib', action='store_true', help='share calibration by predicted-share bins')
    ap.add_argument('--dump', default=None, help='write the missed (match, t) of every cell here')
    ap.add_argument('--thr', type=float, default=None, help='probability threshold for learned models (default per model)')
    args = ap.parse_args()
    cells = run(args)
    report(cells, args)
    if args.dump:
        with open(args.dump, 'w', encoding='utf-8') as f:
            for k, c in sorted(cells.items(), key=lambda kv: str(kv[0])):
                if k[4] != 'all' or k[5] != 'ALL': continue
                f.write(f'{k}: ' + ' '.join(f'{g[:8]}@{t}({n},{s})' for g, t, n, s, o in c.fnex) + '\n')


if __name__ == '__main__':
    main()
