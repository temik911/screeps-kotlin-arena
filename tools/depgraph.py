#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Граф зависимостей между файлами одного пакета Kotlin/JS — по скомпилированным модулям, а не по исходникам.

В плоском пакете Kotlin импортов между файлами нет, поэтому из исходников граф не вывести. Он читается из результата
компиляции: `kotlin.js.ir.output.granularity=per-file` даёт модуль на файл с настоящими
`import { … } from './World.mjs'`, разрешёнными компилятором, вместе с именами символов. Арена-независим.

  python3 tools/depgraph.py <каталог пакета в build/js> [--levels levels.txt] [--gate] [Откуда>Куда …]

Без `--levels`: матрица «сколько символов файл-строка берёт из файла-столбца», компоненты сильной связности,
символы на названных рёбрах. С `--levels`: ещё и рёбра ВВЕРХ (из файла уровня n в файл уровня > n) с именами
символов, и импорты файлов «вне уровней» теми, кому это не разрешено.

Файл уровней (docs/pain-and-gain-architecture.md, раздел 4.6):

    0: Rules Tuning                      # уровень: файлы (без .kt). Зависимость направлена ВНИЗ или внутри уровня
    1: Memory DistanceMap
    outside: Instruments <- PainAndGain  # вне уровней: читает всех; импортировать его может только названный после <-
    known: World>Strategist              # известное ребро вверх; список может только УБЫВАТЬ

`--gate` — строка для tools/land.sh (`PASS … | errors: 0 ` на stdout, подробности — в stderr):
  • ребро вверх, которого нет среди known, — ошибка (новое ребро);
  • known, которого в графе уже нет, — ошибка (ребро снято — сними и строку: список пустеет вместе с рёбрами);
  • known, которого нет в версии файла уровней из `main`, — ошибка (список вырос); пока файла в `main` нет,
    сверять не с чем;
  • модуль без уровня — ошибка (новый файл обязан назвать свой уровень).

Ловушка: граф снят ПОСЛЕ инлайна. `inline fun` переносит ребро из файла-определения в файл-вызова, `const val` ребра
не даёт вовсе (поэтому файлов из одних констант среди модулей нет). Для правила уровней это верно — важно, что реально
импортируется, — но «ребро исчезло» после пометки функции `inline` — не устранение зависимости.
"""
import argparse
import collections
import os
import re
import subprocess
import sys

IMPORT = re.compile(r"import\s*\{([^}]*)\}\s*from\s*'\./([A-Za-z0-9_]+)\.mjs'", re.S)


def read_graph(d):
    files = sorted(f[:-4] for f in os.listdir(d) if f.endswith('.mjs') and not f.endswith('.export.mjs'))
    edges = collections.defaultdict(lambda: collections.defaultdict(list))        # откуда -> куда -> [символы]
    for f in files:
        for names, to in IMPORT.findall(open(os.path.join(d, f + '.mjs'), encoding='utf-8').read()):
            if to != f and to in files:
                # `get_mirrorTL2n3plgooxbyt as get_mirrorTL` — читаемое имя стоит после `as`
                edges[f][to] += [n.split(' as ')[-1].strip() for n in names.split(',') if n.strip()]
    return files, edges


def components(files, edges):
    """Тарьян, без рекурсии — пакет мал, но рекурсия в питоне не нужна и здесь."""
    index, low, on, stack, out, counter = {}, {}, set(), [], [], [0]
    for root in files:
        if root in index:
            continue
        work = [(root, iter(sorted(edges[root])))]
        index[root] = low[root] = counter[0]; counter[0] += 1; stack.append(root); on.add(root)
        while work:
            v, it = work[-1]
            for t in it:
                if t not in index:
                    index[t] = low[t] = counter[0]; counter[0] += 1; stack.append(t); on.add(t)
                    work.append((t, iter(sorted(edges[t]))))
                    break
                if t in on:
                    low[v] = min(low[v], index[t])
            else:
                work.pop()
                if work:
                    low[work[-1][0]] = min(low[work[-1][0]], low[v])
                if low[v] == index[v]:
                    comp = []
                    while True:
                        x = stack.pop(); on.discard(x); comp.append(x)
                        if x == v:
                            break
                    out.append(sorted(comp))
    return sorted((c for c in out if len(c) > 1), key=len, reverse=True)


def parse_levels(text):
    level, outside, known = {}, {}, []
    for raw in text.split('\n'):
        line = raw.split('#', 1)[0].strip()
        if not line:
            continue
        head, _, rest = line.partition(':')
        head, rest = head.strip(), rest.strip()
        if head == 'known':
            known.append(rest.replace(' ', ''))
        elif head == 'outside':
            names, _, allowed = rest.partition('<-')
            for n in names.split():
                outside[n] = set(allowed.split())
        elif head.isdigit():
            for n in rest.split():
                level[n] = int(head)
        else:
            raise SystemExit('depgraph: непонятная строка файла уровней: %r' % raw)
    return level, outside, known


def violations(files, edges, level, outside):
    """Рёбра, которых правило уровней не допускает: вверх по уровням или в файл «вне уровней» от того, кому нельзя."""
    out = []
    for a in files:
        for b in sorted(edges[a]):
            if not edges[a][b] or a in outside:                # «вне уровней» читает всех
                continue
            if b in outside:
                if a not in outside[b]:
                    out.append((a, b, 'в файл вне уровней'))
            elif a in level and b in level and level[b] > level[a]:
                out.append((a, b, 'вверх: %d -> %d' % (level[a], level[b])))
    return out


def main_known(levels_path):
    """known из версии файла уровней в main — чтобы список мог только убывать. None, если сверять не с чем."""
    try:
        top = subprocess.run(['git', 'rev-parse', '--show-toplevel'], capture_output=True, text=True, check=True,
                             cwd=os.path.dirname(os.path.abspath(levels_path)) or '.').stdout.strip()
        rel = os.path.relpath(os.path.abspath(levels_path), top)
        r = subprocess.run(['git', 'show', 'main:' + rel], capture_output=True, text=True, cwd=top)
        if r.returncode != 0:
            return None
        return set(parse_levels(r.stdout)[2])
    except (OSError, subprocess.CalledProcessError):
        return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('dir', help='каталог скомпилированного пакета: build/js/packages/<проект>/kotlin/<проект>/<пакет>')
    ap.add_argument('--levels', help='файл уровней')
    ap.add_argument('--gate', action='store_true', help='одна строка PASS/FAIL для tools/land.sh, подробности в stderr')
    ap.add_argument('edges', nargs='*', help='«Откуда>Куда» — напечатать символы на ребре')
    a = ap.parse_args()
    if a.gate and not a.levels:
        ap.error('--gate требует --levels')
    if not os.path.isdir(a.dir):
        if a.gate:
            print('FAIL %-22s %-40s | errors: 1 ' % ('graph', 'нет каталога сборки'))
            print('depgraph: нет каталога %s — сперва ./gradlew build' % a.dir, file=sys.stderr)
            return 1
        raise SystemExit('depgraph: нет каталога %s' % a.dir)
    files, edges = read_graph(a.dir)
    n_edges = sum(1 for f in files for t in edges[f] if edges[f][t])
    n_syms = sum(len(edges[f][t]) for f in files for t in edges[f])
    comps = components(files, edges)
    info = sys.stderr if a.gate else sys.stdout

    if not a.gate:
        w = max(map(len, files))
        print(' ' * w, ' '.join(f[:3] for f in files))
        for f in files:
            print(f.ljust(w), ' '.join((str(len(edges[f][t])) if edges[f].get(t) else '\\' if t == f else '').rjust(3)
                                       for t in files))
        print('модулей', len(files), 'рёбер', n_edges, 'символов', n_syms)
        into = collections.Counter()
        for f in files:
            for t in edges[f]:
                into[t] += len(edges[f][t])
        print('входящих символов:', ', '.join('%s %d' % kv for kv in into.most_common(5)))
        for comp in comps:
            print('цикл на', len(comp), ':', ', '.join(comp))
        if not comps:
            print('циклов нет')
        for pair in a.edges:
            x, y = pair.split('>')
            print(x, '->', y, len(edges[x][y]), ':', ', '.join(edges[x][y]))

    if not a.levels:
        return 0
    level, outside, known = parse_levels(open(a.levels, encoding='utf-8').read())
    errors = []
    for f in files:
        if f not in level and f not in outside:
            errors.append('модуль %s не назван в %s — новый файл обязан назвать свой уровень' % (f, a.levels))
    bad = violations(files, edges, level, outside)
    actual = {'%s>%s' % (x, y) for x, y, _ in bad}
    for x, y, why in bad:
        key = '%s>%s' % (x, y)
        mark = 'известное' if key in known else 'НОВОЕ'
        print('%-9s %-28s %-20s %3d: %s' % (mark, key, why, len(edges[x][y]), ', '.join(edges[x][y][:12])
                                           + (' …' if len(edges[x][y]) > 12 else '')), file=info)
        if key not in known:
            errors.append('новое ребро %s (%s): %s' % (key, why, ', '.join(edges[x][y][:6])))
    for k in known:
        if k not in actual:
            errors.append('known %s в графе больше нет — сними строку из %s' % (k, a.levels))
    base = main_known(a.levels)
    if base is not None:
        for k in known:
            if k not in base:
                errors.append('known %s нет в версии файла уровней из main — список может только убывать' % k)
    print('рёбер вопреки уровням %d (известных %d), циклов %d%s' % (
        len(bad), len([k for k in known if k in actual]), len(comps),
        ': наибольший на %d файлов' % len(comps[0]) if comps else ''), file=info)
    for e in errors:
        print('depgraph: ' + e, file=sys.stderr)
    if a.gate:
        what = 'up-edges %d known %d new %d' % (len(bad), len(known), len([1 for x in actual if x not in known]))
        print('%-4s %-22s %-40s | errors: %d ' % ('FAIL' if errors else 'PASS', 'graph', what, len(errors)))
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
