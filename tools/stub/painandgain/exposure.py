#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Экспонированность гейта: к чему прогон вообще прикоснулся.

    python3 tools/stub/painandgain/exposure.py <тег>          # читает out/run-<тег>-*.log

Зачем. Пустой дифф отчёта значит одно из двух — «правка мертва» или «стенд не экспонирован к тому, что она
трогает», — и различить их по отчёту нельзя. За сессию 11.09.2026 это стоило дважды: командир правит боем
0,4 % тиков на стенде против 2,3 % живьём, постура отхода — 1 % против 17 %, и обе правки, которые стенд
одобрил ошибочно, меняли ровно эти подсистемы.

Правило чтения: доля ниже 5 % значит, что вердикт по этой подсистеме даёт бесплатная живая тестовая серия,
а не гейт. Гейт при этом всё равно обязан быть зелёным — он отвечает на вопрос «не сломано ли», а не «лучше ли».
"""
import glob
import os
import re
import sys


def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else 'land'
    here = os.path.dirname(os.path.abspath(__file__))
    files = glob.glob(os.path.join(here, 'out', 'run-%s-*.log' % tag))
    if not files:
        print('exposure: логов run-%s-*.log нет' % tag)
        return 0

    post = {}
    ticks = 0
    fight = cmdn = 0
    contact_like = 0
    conc = [0, 0]
    outm = [0, 0]
    for f in files:
        last = None
        for line in open(f, errors='ignore'):
            if not line.startswith('t=') or ' rate=' not in line:
                continue
            d = dict(re.findall(r'(\w+)=([^\s]+)', line))
            last = d
            ticks += 1
            p = d.get('posture', '?')
            post[p] = post.get(p, 0) + 1
            if d.get('mode') == 'FIGHT':
                contact_like += 1
        if not last:
            continue
        m = re.search(r'fight:(\d+)', last.get('cmdwhy', ''))
        tot = last.get('cmdwhy', '/0').rsplit('/', 1)[-1]
        try:
            cmdn += int(tot)
        except ValueError:
            pass
        fight += int(m.group(1)) if m else 0
        c = last.get('concall', '0/0').split('/')
        if len(c) == 2:
            conc[0] += int(c[0]); conc[1] += int(c[1])
        o = last.get('outmw', '0/0').split('/')
        if len(o) == 2:
            outm[0] += int(o[0]); outm[1] += int(o[1])

    def pct(x, n):
        return 100.0 * x / max(n, 1)

    def mark(x):
        return ' ⚠️НИЗКО' if x < 5.0 else ''

    cmd = pct(fight, cmdn)
    retr = pct(post.get('RETREAT', 0) + post.get('EVADE', 0), ticks)
    print('exposure: тиков %d | командир в бою %.1f%%%s | отход+уклонение %.1f%%%s | ANNIHILATE %.0f%% HOLD %.0f%% FLAG %.0f%% '
          '| стволов на цель %.2f | признак отхода %d тиков'
          % (ticks, cmd, mark(cmd), retr, mark(retr),
             pct(post.get('ANNIHILATE', 0), ticks), pct(post.get('HOLD', 0), ticks), pct(post.get('FLAG', 0), ticks),
             1.0 * conc[0] / max(conc[1], 1), outm[0]))
    print('exposure: ⚠️НИЗКО значит, что по этой подсистеме вердикт даёт живая тестовая серия, а не гейт '
          '(правило записано в docs/pain-and-gain.md)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
