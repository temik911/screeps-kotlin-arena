#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ширина разреза длинной функции Kotlin: сколько локальных, объявленных ВЫШЕ границы, читается НИЖЕ неё.

Длинная последовательность режется на подстадии там, где через границу идёт меньше всего величин: каждая такая
величина станет полем выхода подстадии. Прибор называет КАНДИДАТОВ — швы с наименьшей шириной — и печатает, какие
именно локальные шов пересекают. Арена-независим: работает по тексту любого файла Kotlin.

  python3 tools/cutwidth.py starter/src/jsMain/kotlin/season4/painandgain/Strategist.kt:armyStrategy
  python3 tools/cutwidth.py <файл.kt>:<функция> [<файл.kt>:<функция> …] [--cuts 5] [--all]
  python3 tools/cutwidth.py <файл.kt>:<функция> --at 2242        # кто пересекает шов после строки 2242
  python3 tools/cutwidth.py <файл.kt>:<Класс>                    # носитель: тело класса — прежняя функция, поля — её локальные

ЧЕГО ПРИБОР НЕ ВИДИТ — и почему шов подтверждается чтением, а не им (docs/pain-and-gain-architecture-2.md, прил. Б):
  - совпадение идёт ПО ИМЕНИ, а не по разрешению символа: одноимённая локальная вложенного блока считается той же;
  - видны только локальные уровня функции (`val` / `var` / `fun` с основным отступом тела). Состояние, которое идёт
    через члены объекта, верх пакета и общую память (`Memory.x`), НЕ ВИДНО: для `armyStrategy` это дало ложно дешёвый
    шов 2242 | 2244 — по локальным восемь величин, а через члены внутри вызываемых его пересекают ещё три канала;
  - порядок побочных эффектов по обе стороны шва прибор не проверяет вовсе.
"""
import argparse
import collections
import re


def strip(s):
    s = re.sub(r'"(\\.|[^"\\])*"', '""', s)
    return s.split('//')[0]


def body(path, name):
    L = open(path, encoding='utf-8').read().split('\n')
    for i, l in enumerate(L):
        if re.match(r'^\s*(?:internal |private |override |inline )*(?:fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?|class\s+)%s\(' % re.escape(name), l):
            d, seen, j = 0, False, i
            while j < len(L):
                s = strip(L[j])
                d += s.count('{') - s.count('}')
                if '{' in s:
                    seen = True
                if seen and d <= 0:
                    break
                j += 1
            return L, i, j
    raise SystemExit('cutwidth: в %s нет функции %s' % (path, name))


def profile(path, name, cuts, show_all, at):
    L, a, b = body(path, name)
    decl = collections.Counter()
    for k in range(a + 1, b):
        m = re.match(r'^(\s+)(?:private |internal )?(val|var|fun)\s+(?:\(?)(\w+)', L[k])
        if m:
            decl[len(m.group(1))] += 1
    base = min((i for i, c in decl.items() if c >= 5), default=min(decl, default=4))
    locs = []                                           # (имя, строка объявления, вид)
    for k in range(a + 1, b):
        m = re.match(r'^(\s{%d})(?:private |internal )?(val|var|fun)\s+(\w+)' % base, L[k])
        if m:
            locs.append((m.group(3), k, m.group(2)))
        m2 = re.match(r'^(\s{%d})(?:private )?val\s+\(([^)]*)\)' % base, L[k])
        if m2:
            for n in re.findall(r'\w+', m2.group(2)):
                locs.append((n, k, 'val'))
    code = [strip(x) for x in L]
    last = {}
    for n, k, kind in locs:
        rx = re.compile(r'(?<![\w.])%s\b' % re.escape(n))
        lu = k
        for q in range(k + 1, b + 1):
            if rx.search(code[q]):
                lu = q
        last[(n, k)] = lu
    live = [sum(1 for (n, k, kind) in locs if k <= q and last[(n, k)] > q) for q in range(a + 1, b)]
    # кандидаты в швы: строка основного отступа, начинающая оператор после пустой строки или комментария
    cands = []
    for q in range(a + 2, b - 1):
        cur = L[q + 1]
        if re.match(r'^\s{%d}\S' % base, cur) and (L[q].strip() == '' or L[q].strip().startswith('//') or L[q].strip().startswith('*/')):
            cands.append(q)
    n = b - a
    print('\n== %s (%s:%d-%d, %d строк): локальных уровня функции %d (var %d, локальных fun %d); через границу: максимум %d, в среднем %.0f'
          % (name, path.split('/')[-1], a + 1, b + 1, n, len(locs), sum(1 for l in locs if l[2] == 'var'),
             sum(1 for l in locs if l[2] == 'fun'), max(live) if live else 0, sum(live) / max(1, len(live))))

    def crossing(q):
        return [nm for (nm, k, kind) in locs if k <= q and last[(nm, k)] > q]

    if at:
        for line in at:
            q = line - 1
            c = crossing(q)
            print('   шов после строки %d: пересекают %d  [%s]' % (line, len(c), ', '.join(c)))
        return
    mid = [q for q in cands if a + n * 0.1 < q < b - n * 0.1]
    if show_all:
        shown = sorted(mid)
    else:
        mid.sort(key=lambda q: live[q - a - 1])
        shown = []
        for q in mid:
            if all(abs(q - s) > n * 0.08 for s in shown):
                shown.append(q)
            if len(shown) >= cuts:
                break
    for q in sorted(shown):
        c = crossing(q)
        print('   шов после строки %d (%.0f%% функции): пересекают %d  [%s%s]'
              % (q + 1, 100.0 * (q - a) / n, len(c), ', '.join(c[:9]), ' …' if len(c) > 9 else ''))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('targets', nargs='+', help='<файл.kt>:<функция>')
    ap.add_argument('--cuts', type=int, default=5, help='сколько лучших швов показать (не ближе 8 %% длины функции друг к другу)')
    ap.add_argument('--all', action='store_true', help='все швы-кандидаты по порядку текста, а не лучшие')
    ap.add_argument('--at', type=int, action='append', help='показать, кто пересекает шов ПОСЛЕ этой строки (можно несколько раз)')
    a = ap.parse_args()
    for t in a.targets:
        path, _, name = t.rpartition(':')
        if not path:
            raise SystemExit('cutwidth: цель пишется <файл.kt>:<функция>, получено %s' % t)
        profile(path, name, a.cuts, a.all, a.at)


if __name__ == '__main__':
    main()
