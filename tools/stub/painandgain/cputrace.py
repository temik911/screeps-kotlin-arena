#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""След CPU стенда по логам гейта — бесплатный побочный продукт прогона (docs/pain-and-gain-architecture.md, 6.3).

Бот сам печатает каждые сто тиков `cpu t=N: max=…ms at t=M slow(>60ms)=K` (за окно в сто тиков) и разбивку по фазам
`cpu t=N total=…ms: fields=… built=… posture=… command=… moves=… shoot=… resolve=…`. Абсолютные числа стенда к живой
VM не относятся (расхождение ×8, тёплый JIT против холодного), но сдвиг от этапа к этапу на одной машине виден.
ВЕРДИКТА ЭТОТ СЛЕД НЕ ВЫНОСИТ — он пишется в абзац версии, чтобы при провале итогового живого замера было с чем
сверить разбивку по фазам. Сравнивать прогоны, снятые при сходной загрузке машины.

  python3 tools/stub/painandgain/cputrace.py [каталог логов] [--tag land]           # по умолчанию out/ рядом
  python3 tools/stub/painandgain/cputrace.py runs/gate_440 --against tools/stub/painandgain/out
"""
import argparse
import collections
import glob
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
WINDOW = re.compile(r'cpu t=(\d+): max=([\d.]+)ms at t=(\d+) slow\(>(\d+)ms\)=(\d+)')
TOTAL = re.compile(r'cpu t=(\d+) total=([\d.]+)ms: (.*)')
PHASES = ('fields', 'built', 'posture', 'command', 'moves', 'shoot', 'resolve')


def trace(d, tag):
    logs = sorted(glob.glob(os.path.join(d, 'run-%s-*.log' % tag)))
    worst = warm = (0.0, '', 0)
    slow = guards = samples = 0
    total = 0.0
    phase = collections.Counter()
    for f in logs:
        name = os.path.basename(f)[len('run-%s-' % tag):-4]
        for l in open(f, encoding='utf-8', errors='replace'):
            if not l.startswith('cpu t='):
                continue
            if 'guard:' in l:
                guards += 1
            m = WINDOW.match(l)
            if m:
                v, at = float(m.group(2)), int(m.group(3))
                slow += int(m.group(5))
                worst = max(worst, (v, name, at))
                if at > 3:
                    warm = max(warm, (v, name, at))
                continue
            m = TOTAL.match(l)
            if m and int(m.group(1)) % 100 == 0:            # сотые тики — равномерная выборка; медленные тики в неё не подмешаны
                samples += 1
                total += float(m.group(2))
                for k, v in re.findall(r'(\S+?)=([\d.]+)', m.group(3)):
                    phase[k] += float(v)
    return dict(logs=len(logs), worst=worst, warm=warm, slow=slow, guards=guards, samples=samples, total=total, phase=phase)


def show(title, t):
    n = max(t['samples'], 1)
    print('%s: логов %d, строк guard: %d' % (title, t['logs'], t['guards']))
    print('  наибольший max=%.1fмс (%s, t=%d); после тика 3 — %.1fмс (%s, t=%d); сумма slow=%d'
          % (t['worst'] + t['warm'] + (t['slow'],)))
    print('  средний тик по сотым (%d проб): total=%.2fмс | %s'
          % (t['samples'], t['total'] / n, ' '.join('%s=%.2f' % (k, t['phase'][k] / n) for k in PHASES)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('dir', nargs='?', default=os.path.join(HERE, 'out'))
    ap.add_argument('--tag', default='land')
    ap.add_argument('--against', help='второй каталог логов — напечатать оба и сдвиг среднего тика')
    a = ap.parse_args()
    x = trace(a.dir, a.tag)
    show(a.dir, x)
    if a.against:
        y = trace(a.against, a.tag)
        show(a.against, y)
        nx, ny = max(x['samples'], 1), max(y['samples'], 1)
        print('сдвиг среднего тика: %+.2fмс (%+.1f%%); по фазам: %s' % (
            y['total'] / ny - x['total'] / nx, 100.0 * (y['total'] / ny - x['total'] / nx) / max(x['total'] / nx, 1e-9),
            ' '.join('%s=%+.2f' % (k, y['phase'][k] / ny - x['phase'][k] / nx) for k in PHASES)))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
