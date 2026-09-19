#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Покрытие гейта в строках Kotlin: какие строки пакета стенд не исполнил ни разу и какие — не целиком.

Зачем (docs/pain-and-gain-architecture.md, правило 3.3 и раздел 6.2): оракул тождества проверяет только то, что гейт
исполняет. Строка вне покрытия тождеством НЕ проверена — для неё нужен сценарий, который её достаёт, или чтение
вторым агентом. Этот прибор и говорит, какие это строки. Бота и стенд он не трогает: покрытие снимает V8.

  NOCLOCK=1 NODE_V8_COVERAGE=<каталог> zsh tools/stub/painandgain/regress.sh gate      # переменные наследуют все node
  python3 tools/stub/painandgain/reach.py <каталог> [--tag gate] [--fun creepTurn …] [Файл.kt:от-до …]
          [--save runs/reach_<версия>.json] [--report runs/reach_<версия>.txt]
  python3 tools/stub/painandgain/reach.py diff <до.json> <после.json> [--fun …]        # что потеряло покрытие

КАК СЧИТАЕТСЯ. Каждый процесс node кладёт в каталог свой JSON с диапазонами «смещение от–до, счётчик». Для каждого
процесса символы скомпилированного `.mjs` красятся «исполнено / не исполнено» (внешние диапазоны раньше вложенных),
затем раскраски процессов складываются по ИЛИ, и отрезки переводятся в строки Kotlin через `.mjs.map`. Складывать
надо именно раскраски, а не счётчики по ключу диапазона, как делал прототип из плана: V8 выбрасывает вложенный
диапазон, чей счётчик равен счётчику внешнего, поэтому у процесса, где ветка исполнялась, её диапазона в отчёте НЕТ, у
процесса, где не исполнялась, — есть с нулём, и максимум по ключу объявляет исполненную ветку мёртвой.
Строка — «исполнена», если исполнен хоть один её отрезок в своём модуле или в чужом (тело `inline fun` живёт в модуле
вызова, а отдельная копия в своём модуле не зовётся никогда); «не целиком» — исполнена, но в своём модуле остался
неисполненный отрезок (ветка `&&`, `?:`, лямбда). «Строка кода» — строка, на которую есть хоть одно отображение; верх
модуля считается исполненным; смещения V8 и колонки source map — в единицах UTF-16, так они здесь и считаются.

УСЛОВИЕ ЗАМЕРА — ЧАСЫ СТЕНДА ВЫКЛЮЧЕНЫ (`NOCLOCK=1`, см. run.mjs). Под сбором покрытия node медленнее, предохранители
CPU бота срабатывают и меняют поведение: 19.09.2026 на трёх сценариях — 255 срабатываний против нуля в гейте. Прогон,
в логах которого есть строка `guard:`, прибор считать ОТКАЗЫВАЕТСЯ; прогон с включёнными часами считает, но говорит об
этом: у двух ветвей, читающих время (ограничение поля потока и обрезка перебора командира), своей строки `guard:` нет.
"""
import argparse
import collections
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from lint import code_lines  # noqa: E402  (тот же вырезатель комментариев, что у линта)

PKG = '/season4/painandgain/'
OWN_SRC = '/src/jsMain/kotlin' + PKG          # исходник пакета, а не stdlib, попавший в карту под тем же каталогом сборки
SRC = os.path.normpath(os.path.join(HERE, '../../../starter/src/jsMain/kotlin/season4/painandgain'))
B64 = {c: i for i, c in enumerate('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/')}
T_HIT = bytes(1 if i == 1 else 0 for i in range(256))
T_FN = bytes(1 if i in (1, 2) else 0 for i in range(256))


def vlq(seg):
    out, shift, val = [], 0, 0
    for ch in seg:
        d = B64[ch]
        val |= (d & 31) << shift
        shift += 5
        if not d & 32:
            out.append(-(val >> 1) if val & 1 else val >> 1)
            shift = val = 0
    return out


class Module:
    """Один скомпилированный .mjs: раскраска по процессам складывается по ИЛИ в двух больших целых."""

    def __init__(self, path):
        self.path = path
        text = open(path, encoding='utf-8').read()
        self.starts, unit = [0], 0                       # начала строк в единицах UTF-16
        for ch in text:
            unit += 2 if ord(ch) > 0xFFFF else 1
            if ch == '\n':
                self.starts.append(unit)
        self.n = unit
        self.hit = self.fn = 0

    def add(self, ranges):
        local = bytearray(self.n + 1)
        for a, b, c in sorted(ranges, key=lambda r: (r[0], -r[1])):       # внешние диапазоны раньше вложенных
            b = min(b, self.n)
            if b > a:
                local[a:b] = (b'\x01' if c > 0 else b'\x02') * (b - a)
        self.hit |= int.from_bytes(local.translate(T_HIT), 'little')
        self.fn |= int.from_bytes(local.translate(T_FN), 'little')

    def state(self):
        """bytes по единицам UTF-16: 1 — исполнено или верх модуля, 2 — внутри функции и не исполнено ни разу."""
        hit = self.hit.to_bytes(self.n + 1, 'little')
        fn = self.fn.to_bytes(self.n + 1, 'little')
        return bytes(2 if f and not h else 1 for h, f in zip(hit, fn))


def measure(covdir):
    mods, procs = {}, 0
    for f in sorted(glob.glob(os.path.join(covdir, '*.json'))):
        try:
            result = json.load(open(f, encoding='utf-8'))['result']
        except (ValueError, KeyError):
            continue                                       # процесс, убитый на записи, — не замер
        touched = False
        for e in result:
            url = e['url']
            if PKG not in url or not url.endswith('.mjs') or url.endswith('.export.mjs'):
                continue
            path = url.replace('file://', '')
            if path not in mods:
                if not os.path.exists(path) or not os.path.exists(path + '.map'):
                    continue
                mods[path] = Module(path)
            mods[path].add([(r['startOffset'], r['endOffset'], r['count']) for fn in e['functions'] for r in fn['ranges']])
            touched = True
        procs += touched
    own_hit, own_never, foreign_hit = (collections.defaultdict(set) for _ in range(3))
    seen = collections.defaultdict(set)
    for path, m in sorted(mods.items()):
        st = m.state()
        sm = json.load(open(path + '.map', encoding='utf-8'))
        own = os.path.basename(path)[:-4] + '.kt'
        srcs = [(os.path.basename(s) if OWN_SRC in s.replace('\\', '/') else None) for s in sm['sources']]
        si = sl = 0
        for gl, line in enumerate(sm['mappings'].split(';')):
            if gl >= len(m.starts):
                break
            gc, segs = 0, []
            for seg in filter(None, line.split(',')):
                v = vlq(seg)
                gc += v[0]
                if len(v) >= 4:
                    si += v[1]
                    sl += v[2]
                    segs.append((gc, srcs[si] if si < len(srcs) else None, sl))
                else:
                    segs.append((gc, None, 0))             # отрезок без исходника закрывает предыдущий
            end = (m.starts[gl + 1] - 1) if gl + 1 < len(m.starts) else m.n
            for j, (c0, src, l) in enumerate(segs):
                if src is None:
                    continue
                a = m.starts[gl] + c0
                b = max(a + 1, (m.starts[gl] + segs[j + 1][0]) if j + 1 < len(segs) else end)
                piece = st[a:b]
                seen[src].add(l + 1)
                if src == own:
                    if b'\x01' in piece:
                        own_hit[src].add(l + 1)
                    if b'\x02' in piece:
                        own_never[src].add(l + 1)
                elif b'\x01' in piece:
                    foreign_hit[src].add(l + 1)
    lines = {}
    for f in seen:
        lines[f] = {}
        for l in seen[f]:
            hit = l in own_hit[f] or l in foreign_hit[f]
            lines[f][l] = 'N' if not hit else 'P' if (l in own_hit[f] and l in own_never[f]) else 'H'
    return lines, procs, len(mods)


def check_logs(covdir, logs, tag):
    """(проверено логов, строк guard:, логов с включёнными часами). Берутся логи того же прогона — по времени записи."""
    stamps = [os.path.getmtime(f) for f in glob.glob(os.path.join(covdir, '*.json'))]
    if not stamps:
        return 0, 0, 0
    lo, hi = min(stamps) - 1800, max(stamps) + 120
    n = guards = clocked = 0
    for f in glob.glob(os.path.join(logs, 'run-%s-*.log' % tag)):
        if not lo <= os.path.getmtime(f) <= hi:
            continue
        n += 1
        on = False
        for l in open(f, encoding='utf-8', errors='replace'):
            if l.startswith('cpu t='):
                if 'guard:' in l:
                    guards += 1
                m = re.match(r'cpu t=\d+ total=([\d.]+)ms', l)
                if m and float(m.group(1)) > 0:
                    on = True
        clocked += on
    return n, guards, clocked


def functions(src):
    """{'Файл.kt:имя': [(от, до), …]} — тела функций по балансу фигурных скобок; эвристика, годится как окно для чтения."""
    out = collections.defaultdict(list)
    for f in sorted(os.listdir(src)):
        if not f.endswith('.kt'):
            continue
        rows = code_lines(open(os.path.join(src, f), encoding='utf-8').read())
        i = 0
        while i < len(rows):
            m = re.search(r'\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)\s*\(', rows[i][1])
            if not m:
                i += 1
                continue
            depth, opened, j = 0, False, i
            while j < len(rows):
                code = rows[j][1]
                if not opened and j > i + 12:
                    break                                   # тело-выражение без скобок: окна нет
                for ch in code:
                    if ch == '{':
                        depth += 1
                        opened = True
                    elif ch == '}':
                        depth -= 1
                if opened and depth <= 0:
                    break
                j += 1
            if opened and j < len(rows):
                out['%s:%s' % (f, m.group(1))].append((rows[i][0], rows[j][0]))
            i += 1
    return out


def windows(a, funs):
    out = []
    for name in a.fun or []:
        hits = [(k, r) for k, rs in funs.items() if k.split(':')[1] == name for r in rs]
        if not hits:
            print('reach: функция %s в исходниках не найдена' % name, file=sys.stderr)
        for k, (x, y) in hits:
            out.append(('%s %d-%d' % (k, x, y), k.split(':')[0], x, y))
    for w in a.windows or []:
        f, r = w.split(':')
        x, y = map(int, r.split('-'))
        out.append((w, f, x, y))
    return out


def texts(src, f):
    p = os.path.join(src, f)
    return [''] + open(p, encoding='utf-8').read().split('\n') if os.path.exists(p) else ['']


def report(a):
    n_logs, guards, clocked = check_logs(a.cov, a.logs, a.tag)
    if n_logs == 0:
        print('reach: ОТКАЗ — в %s нет логов run-%s-* этого прогона: проверить строки guard: не на чем' % (a.logs, a.tag))
        return 2
    if guards:
        print('reach: ОТКАЗ — в %d логах прогона %d строк `guard:`: предохранители CPU меняли поведение бота, это не '
              'тот матч, который играет гейт. Сними покрытие с NOCLOCK=1' % (n_logs, guards))
        return 2
    lines, procs, n_mods = measure(a.cov)
    if not lines:
        print('reach: в %s нет покрытия модулей пакета %s' % (a.cov, PKG))
        return 2
    out = []
    out.append('покрытие: %s — процессов %d, модулей %d; логов прогона %d, строк guard: 0%s' % (
        a.cov, procs, n_mods, n_logs,
        '' if not clocked else '; ⚠️ часы стенда были ВКЛЮЧЕНЫ в %d логах — две ветви, читающие время, строки guard: '
                               'не печатают (NOCLOCK=1 снимает вопрос)' % clocked))
    N = M = P = 0
    for f in sorted(lines):
        never = sum(1 for s in lines[f].values() if s == 'N')
        part = sum(1 for s in lines[f].values() if s == 'P')
        N += len(lines[f]); M += never; P += part
        out.append('%-18s строк кода %5d  не исполнено %4d (%4.1f%%)  исполнено не целиком %4d'
                   % (f, len(lines[f]), never, 100.0 * never / len(lines[f]), part))
    out.append('ИТОГО              строк кода %5d  не исполнено %4d (%4.1f%%)  исполнено не целиком %4d (%4.1f%%)'
               % (N, M, 100.0 * M / N, P, 100.0 * P / N))
    funs = functions(a.src)
    for title, f, x, y in windows(a, funs):
        src = texts(a.src, f)
        rows = sorted((l, s) for l, s in lines.get(f, {}).items() if x <= l <= y)
        never = [l for l, s in rows if s == 'N']
        part = [l for l, s in rows if s == 'P']
        out.append('')
        out.append('== %s: строк кода %d, не исполнено %d, не целиком %d' % (title, len(rows), len(never), len(part)))
        for l, s in rows:
            if s != 'H':
                out.append('  %s %5d | %s' % ('НЕТ     ' if s == 'N' else 'частично', l, src[l].strip()[:150] if l < len(src) else ''))
    text = '\n'.join(out)
    print(text)
    if a.report:
        open(a.report, 'w', encoding='utf-8').write(text + '\n')
    if a.save:
        data = {'cov': a.cov, 'procs': procs, 'logs': n_logs, 'clocked': clocked,
                'funs': {k: v for k, v in funs.items()},
                'files': {f: {str(l): [s, (texts(a.src, f)[l].strip() if l < len(texts(a.src, f)) else '')]
                              for l, s in sorted(lines[f].items())} for f in lines}}
        json.dump(data, open(a.save, 'w', encoding='utf-8'), ensure_ascii=False)
    return 0


RANK = {'H': 0, 'P': 1, 'N': 2}


def diff(a):
    """Сравнение двух замеров ПО ТЕКСТУ строки: после переноса номера строк и файлы другие, текст — прежний."""
    A, B = (json.load(open(p, encoding='utf-8')) for p in (a.before, a.after))

    def index(d):
        ix = collections.defaultdict(list)
        for f, rows in d['files'].items():
            for l, (s, t) in rows.items():
                if len(t) >= 12:                            # `}` и `else -> {` различить по тексту нельзя
                    ix[t].append((s, f, int(l)))
        return ix
    ia, ib = index(A), index(B)
    inside = None
    if a.fun:
        inside = [(k.split(':')[0], x, y) for k, rs in B['funs'].items() if k.split(':')[1] in a.fun for x, y in rs]

    def wanted(f, l):
        return inside is None or any(f == wf and x <= l <= y for wf, x, y in inside)
    worse, new_never = [], []
    for t, rb in ib.items():
        ra = ia.get(t)
        if ra is None:
            new_never += [(f, l, t) for s, f, l in rb if s == 'N' and wanted(f, l)]
            continue
        sa, sb = sorted(RANK[s] for s, _, _ in ra), sorted(RANK[s] for s, _, _ in rb)
        if len(sa) == len(sb) and any(y > x for x, y in zip(sa, sb)) or len(sa) != len(sb) and max(sb) > max(sa):
            for s, f, l in rb:
                if RANK[s] > min(sa) and wanted(f, l):
                    worse.append((f, l, s, '/'.join(sorted(x for x, _, _ in ra)), t))
    gone = sum(1 for t, ra in ia.items() if t not in ib for s, _, _ in ra if s != 'N')
    print('строк с тем же текстом, потерявших покрытие: %d' % len(worse))
    for f, l, s, was, t in sorted(worse):
        print('  %s:%d  было %s стало %s | %s' % (f, l, was, s, t[:130]))
    print('новых строк (текста не было в «до»), не исполненных ни разу: %d' % len(new_never))
    for f, l, t in sorted(new_never):
        print('  %s:%d | %s' % (f, l, t[:130]))
    print('исполненных строк «до», текста которых в «после» нет: %d (переписаны или удалены — читать по диффу)' % gone)
    return 1 if worse else 0


def main():
    if len(sys.argv) > 1 and sys.argv[1] == 'diff':
        ap = argparse.ArgumentParser(prog='reach.py diff')
        ap.add_argument('before')
        ap.add_argument('after')
        ap.add_argument('--fun', action='append', help='только строки внутри этой функции в «после» (можно несколько)')
        return diff(ap.parse_args(sys.argv[2:]))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('cov', help='каталог NODE_V8_COVERAGE')
    ap.add_argument('--logs', default=os.path.join(HERE, 'out'), help='логи прогона (по умолчанию out/ рядом)')
    ap.add_argument('--tag', default='gate', help='тег прогона regress.sh, которым снято покрытие')
    ap.add_argument('--src', default=SRC, help='исходники пакета — для текста строк и окон функций')
    ap.add_argument('--fun', action='append', help='окно по имени функции (можно несколько)')
    ap.add_argument('--save', help='сохранить замер в JSON — вход режима diff')
    ap.add_argument('--report', help='записать отчёт в файл')
    ap.add_argument('windows', nargs='*', help='окна «Файл.kt:от-до»')
    return report(ap.parse_args())


if __name__ == '__main__':
    sys.exit(main())
