"""The port against the model where the port printed: for every (t, P, D, H) with a bot forecast line, the bot's
any/share against M2a75 computed by the stand on the same replay. Usage: agree.py <replays file>"""
import sys, os, types
sys.path.insert(0, os.path.dirname(__file__))
import fc
import models
import models_ext

ids = [l.strip() for l in open(sys.argv[1]) if l.strip()]
args = types.SimpleNamespace(P=['us', 'them'], D=[5], H=[75, 150, 200])
log = models_ext.get('LOG')
m2 = models_ext.get('M2a75')
tot = {}
for gid in ids:
    d = fc.load_samples(gid)
    F = fc.Fields(d)
    import io, contextlib
    with contextlib.redirect_stderr(io.StringIO()):
        a = log.predict(d, F, None, args)
    # the port's own keys only: LOG fills the rest with (False, 0.0), so re-read which keys were printed
    doc, meta, names = fc.R.load(os.path.join(fc.REPLAYS, gid + '.replay.json.gz'))
    printed = set()
    for text in (doc.get('logs') or {}).values():
        for line in log.RXC.finditer(text):
            t = int(line.group(1))
            for m in log.RXI.finditer(line.group(2)):
                printed.add((t, m.group(1), int(m.group(2)), int(m.group(3))))
    b = m2.predict(d, F, None, args)
    for k, v in a.items():
        if k not in printed or k not in b: continue
        key = (k[1], k[3])
        s = tot.setdefault(key, [0, 0, 0, 0.0])
        s[0] += 1
        if v[0] == b[k][0]: s[1] += 1
        elif v[0] and not b[k][0]: s[2] += 1   # the port announces, the model does not
        s[3] += abs(v[1] - b[k][1])
for (P, H), (n, same, portOnly, dshare) in sorted(tot.items()):
    print(f"P={P:4} H={H:3}: samples {n}, any agrees {100.0 * same / n:.1f} %, port-only announces {portOnly}, model-only {n - same - portOnly}, mean |share diff| {dshare / n:.3f}")
