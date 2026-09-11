#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сравнение двух отчётов гейта Pain and Gain.

    python3 tools/stub/painandgain/compare.py <до.txt> <после.txt>

ПРАВИЛА ВЕРДИКТА — они здесь, а не в голове того, кто читает.

1. РЕШАЕТ ТОЛЬКО `FAIL`. Появление хотя бы одной строки `FAIL` (наша армия уничтожена, или матч кончился при
   нашем отставании по счёту, или в строке есть ошибки) — это «сломано», а не «хуже на столько-то».
2. СУММАРНЫЙ ОТРЫВ ОПИСЫВАЕТ, А НЕ РЕШАЕТ. За сессию 11.09.2026 отрыв решал спорный вопрос дважды и оба раза
   ошибся: `USE_COMMAND_BREAKS_OFF` получил +3 699 и стоил живьём четырёх аннигиляций из четырёх срабатываний,
   `USE_COMMAND_FIGHT_WHILE_PUSHING` получил +7 строк в уничтожение и уронил живой контроль с 4-0 до 2-4 и 1-5.
   Жёсткий критерий за ту же сессию не ошибся ни разу — четыре правки, завёрнутые через `FAIL`, были завёрнуты
   по делу.
3. ИСХОД ВАЖНЕЕ СЧЁТА. Уничтожение армии врага выигрывает матч при любом счёте, поэтому строка, перешедшая в
   уничтожение, стала ЛУЧШЕ, даже если её счёт упал: матч просто кончился раньше и меньше успел набрать.
4. НЕЙТРАЛЬНЫЙ ДИФФ — ЭТО НЕ «ПРАВКА НЕ РАБОТАЕТ». Он значит, что стенд не экспонирован к тому, что правка
   трогает; сколько именно — печатает сам гейт строкой `exposure:`. Ниже порога вердикт даёт бесплатная живая
   тестовая серия, а не эти числа.
"""
import re
import sys


def load(path):
    rows = {}
    for line in open(path, errors='ignore'):
        m = re.match(r'(\w+)\s+(\S+)\s+(.*?) at t=(\d+)\s+score (\d+):(\d+)\s*\|\s*errors: (\S+)', line)
        if m:
            rows[m.group(2)] = dict(verdict=m.group(1), outcome=m.group(3).strip(), tick=int(m.group(4)),
                                    ours=int(m.group(5)), theirs=int(m.group(6)), errors=m.group(7))
    return rows


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    before, after = load(sys.argv[1]), load(sys.argv[2])

    broken = [k for k, v in after.items() if v['verdict'] != 'PASS' or v['errors'] not in ('0',)]
    to_kill = to_lead = kill_early = kill_late = lead_early = lead_late = same = 0
    gap = 0
    notes = []
    for k in sorted(set(before) | set(after)):
        x, y = before.get(k), after.get(k)
        if x is None or y is None:
            notes.append('  ТОЛЬКО В ОДНОМ ОТЧЁТЕ: %s' % k)
            continue
        xd, yd = 'destroyed' in x['outcome'], 'destroyed' in y['outcome']
        if xd != yd:
            if yd:
                to_kill += 1
                notes.append('  -> УНИЧТОЖЕНИЕ  %-30s t=%d %d:%d  ->  t=%d %d:%d'
                             % (k, x['tick'], x['ours'], x['theirs'], y['tick'], y['ours'], y['theirs']))
            else:
                to_lead += 1
                notes.append('  <- потеряно     %-30s t=%d %d:%d  ->  t=%d %d:%d'
                             % (k, x['tick'], x['ours'], x['theirs'], y['tick'], y['ours'], y['theirs']))
            continue
        if x['tick'] == y['tick'] and x['ours'] == y['ours'] and x['theirs'] == y['theirs']:
            same += 1
            continue
        if yd:
            if y['tick'] < x['tick']:
                kill_early += 1
            elif y['tick'] > x['tick']:
                kill_late += 1
        else:
            if y['tick'] < x['tick']:
                lead_early += 1
            elif y['tick'] > x['tick']:
                lead_late += 1
            gap += (y['ours'] - y['theirs']) - (x['ours'] - x['theirs'])

    print('ВЕРДИКТ: %s' % ('СЛОМАНО — %d строк не PASS или с ошибками' % len(broken) if broken
                           else 'не сломано (все строки PASS, ошибок нет)'))
    for b in broken:
        print('   FAIL: %s' % b)
    print()
    print('ОПИСАНИЕ (не вердикт):')
    print('  уничтожение армии врага: %+d строк (в него %d, из него %d); внутри уничтожений раньше %d / позже %d'
          % (to_kill - to_lead, to_kill, to_lead, kill_early, kill_late))
    print('  недостижимый отрыв: раньше %d / позже %d, суммарный отрыв %+d; без изменений %d'
          % (lead_early, lead_late, gap, same))
    if same == len(after) and not broken:
        print('  ⚠️ дифф пуст: стенд не экспонирован к этой правке — смотри строку exposure: в отчёте гейта')
    for line in notes[:30]:
        print(line)
    if len(notes) > 30:
        print('  ... ещё %d строк со сменой исхода' % (len(notes) - 30))
    return 1 if broken else 0


if __name__ == '__main__':
    sys.exit(main())
