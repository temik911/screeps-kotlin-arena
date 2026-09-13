#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Тождество логов стенда: первое расхождение каждого лога гейта с эталоном.

Оракул механических этапов переработки (docs/pain-and-gain-rework.md, раздел 8): `compare.py` сравнивает пять полей
исхода, а этот скрипт — логи сценариев целиком. Логи детерминированы, кроме строк `cpu t=` и поля `time=` в `done:`;
они отбрасываются. Поля, добавленные или снятые переработкой намеренно (`anchor=`, `ovw=`, `conf=`), и номер версии
в приветствии маскируются — список ниже дополняется вместе с приборами.

  python3 tools/stub/painandgain/logdiff.py [--old runs/gate_235] [--new tools/stub/painandgain/out] [подстрока…]

Печатает число логов с расхождением, гистограмму «строка-тег + первое отличающееся поле k=v» и первые расхождения
(старое/новое) — по всем логам или только по тем, чьё имя содержит одну из подстрок. Из корня ворктри.
"""
import argparse
import os
import re
from collections import Counter

MASK = [
    (re.compile(r'hello season4 pain-and-gain v\d+'), 'hello season4 pain-and-gain vX'),
    (re.compile(r' anchor=\S+'), ''),
    (re.compile(r' ovw=\S+ conf=\S+'), ''),
]


def lines(p):
    out = []
    for l in open(p, encoding='utf-8', errors='replace').read().split('\n'):
        if l.startswith('cpu t=') or 'time=' in l:
            continue
        for rx, rep in MASK:
            l = rx.sub(rep, l)
        out.append(l)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--old', default='runs/gate_235')
    ap.add_argument('--new', default='tools/stub/painandgain/out')
    ap.add_argument('--show', type=int, default=6, help='сколько расхождений печатать подробно')
    ap.add_argument('only', nargs='*')
    a = ap.parse_args()
    first_fields = Counter()
    rows = []
    for f in sorted(os.listdir(a.old)):
        if not f.startswith('run-land-'):
            continue
        if a.only and not any(o in f for o in a.only):
            continue
        new_p = os.path.join(a.new, f)
        if not os.path.exists(new_p):
            rows.append((f, 0, '<нет лога>', [], '', '<нет нового лога>'))
            continue
        x = lines(os.path.join(a.old, f))
        y = lines(new_p)
        k = next((i for i in range(min(len(x), len(y))) if x[i] != y[i]), None)
        if k is None and len(x) == len(y):
            continue
        if k is None:
            k = min(len(x), len(y))
        la = x[k] if k < len(x) else '<конец>'
        lb = y[k] if k < len(y) else '<конец>'
        fa = dict(re.findall(r'(\S+?)=(\S+)', la))
        fb = dict(re.findall(r'(\S+?)=(\S+)', lb))
        diffk = [q for q in fa if q in fb and fa[q] != fb[q]]
        tag = la.split(' ')[0] if la else '?'
        first_fields[(tag, diffk[0] if diffk else '-')] += 1
        rows.append((f, k + 1, tag, diffk[:4], la[:150], lb[:150]))
    print('логов с расхождением:', len(rows))
    for key, n in first_fields.most_common(12):
        print('%3d  %s %s' % (n, key[0], key[1]))
    for r in (rows if a.only else rows[:a.show]):
        print('---', r[0], 'строка', r[1], 'поля', r[3])
        print('  старое:', r[4])
        print('  новое: ', r[5])
    return 1 if rows else 0


if __name__ == '__main__':
    raise SystemExit(main())
