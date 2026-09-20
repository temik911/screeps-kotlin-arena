#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Где гейт слеп относительно живых матчей — по строкам таблиц решений (прибор `reach t=`).

docs/pain-and-gain-architecture-2.md, этап 0. `reach t=` печатается и на стенде, и живьём, поэтому слепоту гейта можно
считать ПО КАЖДОЙ СТРОКЕ таблицы: в скольких сценариях гейта строка выигрывает и в скольких живых матчах.

  python3 tools/stub/painandgain/gategap.py [--versions 447-452]      # расхождение и жадный выбор записей
  python3 tools/stub/painandgain/gategap.py --rows rung.kite,step.flee # сценарии гейта, где эти строки выигрывают
  python3 tools/stub/painandgain/gategap.py --rows rung.kite --list    # то же одними метками сценариев, по одной на
                                                                      #   строку — вход быстрого цикла identity.sh --rows
  --logs <каталог>   чьи логи гейта читать (по умолчанию out/ этого стенда; эталон — runs/gate_N)
  --check            код возврата 1, если есть строка, выигрывающая живьём и НИ РАЗУ на гейте (приёмка этапа 0)

Оговорки, без которых числа обманывают. (1) Счётчики накопительные — читается ПОСЛЕДНЯЯ строка `reach` лога. (2) У
таблиц-последовательностей (`pass`, `gate`) первое число значит другое, чем у `Row` (Tables.kt): «выигрывает» для них
читается как «выдал клетку» и «решил». (3) По одному `reach` почти все сценарии «ничего не добавляют» — это грубость
критерия (гейт судит исходы, а не строки), прореживать гейт по нему нельзя. (4) Жадный выбор записей закрывает только
строки, которых гейт не видит ВОВСЕ; добор по убыванию разрыва — руками по списку недоэкспонированных, и запись входит
в гейт по `reach` своего GHOST-лога, а не живого: призрак идёт по записи, и то, что случилось живьём, на стенде может
не случиться. Из корня ворктри или откуда угодно.
"""
import argparse
import collections
import glob
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '../../..'))


def last_reach(text):
    """Последняя по тику строка `reach t=` (счётчики накопительные)."""
    best = None
    for m in re.finditer(r'reach t=(\d+): ([^\n"\\]+)', text):
        if best is None or int(m.group(1)) >= best[0]:
            best = (int(m.group(1)), m.group(2))
    return best[1] if best else None


def won_rows(line):
    """имя=тег:won/on/третье,… -> {таблица.тег: won}"""
    out = {}
    for tbl in line.split(' '):
        if '=' not in tbl:
            continue
        name, body = tbl.split('=', 1)
        for cell in body.split(','):
            mm = re.match(r'([\w.+-]+):(\d+)/(\d+)/(\d+)$', cell)
            if mm:
                out['%s.%s' % (name, mm.group(1))] = int(mm.group(2))
    return out


def label_of(log_name):
    """run-land-match5:rush-rush.log -> match5:rush ; run-land-ghost:<id>:ghost-ghost-030b5c.log -> ghost:<id>:ghost
    (метка — то, что regress.sh печатает вторым столбцом и чем фильтрует ONLY)."""
    s = log_name[len('run-land-'):-len('.log')]
    if s.startswith('ghost:'):
        return s.split('-ghost-')[0]
    head, rest = s.split(':', 1)
    sc = rest[:(len(rest) - 1) // 2]                    # rest = <сценарий>-<сценарий>
    return '%s:%s' % (head, sc) if rest == sc + '-' + sc else s


def gate_rows(logs):
    gate = {}
    for f in sorted(glob.glob(os.path.join(logs, 'run-land-*.log'))):
        l = last_reach(open(f, errors='ignore').read())
        if l:
            gate[label_of(os.path.basename(f))] = won_rows(l)
    return gate


def live_rows(lo, hi):
    listing = subprocess.run([sys.executable, os.path.join(ROOT, 'tools/match-log.py'), 'list', '--arena', 'pain-and-gain', '--all'],
                             capture_output=True, text=True).stdout
    live = {}
    for l in listing.split('\n'):
        m = re.search(r'\s([0-9a-f]{24})\s+season4/pain-and-gain\s+v(\d+)\s.*vs (\S+)', l)
        if not m or not lo <= int(m.group(2)) <= hi:
            continue
        text = ''.join(open(f, errors='ignore').read()
                       for f in glob.glob(os.path.expanduser('~/ScreepsArena/games/%s/log-*.json' % m.group(1))))
        lr = last_reach(text)
        if lr:
            live[m.group(1)] = (won_rows(lr), m.group(3), int(m.group(2)))
    return live


def ghost_scan(ids, jobs):
    """Каждую запись сыграть призраком на ТЕКУЩЕЙ сборке (NOCLOCK=1) и прочесть reach её ghost-лога: {id: {строка: won}}.
    Запись входит в гейт по тому, что исполняет СТЕНД, а не по тому, что случилось живьём."""
    from concurrent.futures import ThreadPoolExecutor
    node = sorted(glob.glob(os.path.expanduser('~/.gradle/nodejs/node-*/bin/node')))[-1]
    rdir = os.environ.get('REPLAY_DIR', os.path.expanduser('~/ScreepsArena/replays'))

    def one(g):
        rp = os.path.join(HERE, 'replays', g + '.replay.json.gz')
        if not os.path.exists(rp):
            rp = os.path.join(rdir, g + '.replay.json.gz')
        if not os.path.exists(rp):
            return g, None
        env = dict(os.environ, NOCLOCK='1', REPLAY=rp, LOGTAG='scan-ghost:%s:ghost-' % g)
        # те же настройки кучи V8, что ставит regress.sh (там же замер и выбор 192): без них процесс с 23 МБ живых данных
        # раздувается до 360 МБ, а здесь их `jobs` штук разом. STUB_NODE_FLAGS= (пусто) выключает
        flags = os.environ.get('STUB_NODE_FLAGS', '--max-semi-space-size=2 --max-old-space-size=192')
        env['NODE_OPTIONS'] = (flags + ' ' + os.environ.get('NODE_OPTIONS', '')).strip()
        subprocess.run([node, '--import', './register.mjs', 'run.mjs', '2000', 'ghost'], cwd=HERE, env=env, capture_output=True, text=True)
        f = os.path.join(HERE, 'out', 'run-scan-ghost:%s:ghost-ghost-%s.log' % (g, g[-6:]))
        l = last_reach(open(f, errors='ignore').read()) if os.path.exists(f) else None
        return g, (won_rows(l) if l else None)

    with ThreadPoolExecutor(max_workers=jobs) as ex:
        return dict(ex.map(one, ids))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--scan', default='', help='строки через запятую: сыграть призраком записи живых матчей, где эти строки выигрывали '
                                               'ЖИВЬЁМ, и показать, в каких из них они выигрывают НА СТЕНДЕ (жадный выбор — по ghost-логам)')
    ap.add_argument('--pool', default='won', choices=['won', 'range', 'replays'],
                    help='с --scan, откуда кандидаты: won — матчи --versions, где строки выигрывали живьём; range — все матчи --versions; '
                         'replays — все записи каталога записей, новейшие первыми (стенду всё равно, что было живьём)')
    ap.add_argument('--limit', type=int, default=200, help='с --pool replays: сколько записей играть')
    ap.add_argument('--jobs', type=int, default=8)
    ap.add_argument('--versions', default='447-999', help='живые матчи каких версий бота брать (reach печатается с v444)')
    ap.add_argument('--rows', default='', help='строки таблиц через запятую: сценарии гейта, где они выигрывают')
    ap.add_argument('--list', action='store_true', help='с --rows: только метки сценариев (объединение по строкам), по одной на строку')
    ap.add_argument('--logs', default=os.path.join(HERE, 'out'), help='каталог логов гейта run-land-*.log')
    ap.add_argument('--check', action='store_true', help='код возврата 1, если есть строка, выигрывающая живьём и ни разу на гейте')
    ap.add_argument('--top', type=int, default=15, help='сколько недоэкспонированных строк печатать')
    a = ap.parse_args()
    lo, hi = map(int, a.versions.split('-'))

    gate = gate_rows(a.logs)
    if not gate:
        raise SystemExit('gategap: в %s нет логов run-land-*.log со строкой reach' % a.logs)

    if a.rows:                                          # быстрый цикл: сценарии, где строка выигрывала
        rows = [r for r in a.rows.split(',') if r]
        known = set().union(*[set(w) for w in gate.values()])
        union = []
        for r in rows:
            if r not in known:
                raise SystemExit('gategap: строки %s нет ни в одном reach гейта (имя — таблица.тег, например rung.kite)' % r)
            hit = sorted(((w[r], s) for s, w in gate.items() if w.get(r, 0) > 0), reverse=True)
            union += [s for _, s in hit if s not in union]
            if not a.list:
                print('%s: выигрывает в %d сценариях из %d; первые: %s' % (r, len(hit), len(gate), ', '.join(s for _, s in hit[:6])))
        if a.list:
            print('\n'.join(union))
        return 0

    live = live_rows(lo, hi)
    if a.scan:                                          # добор: какая ЗАПИСЬ даёт строке выиграть на стенде
        want = [r for r in a.scan.split(',') if r]
        if a.pool == 'won':
            cand = [g for g, (w, _, _) in live.items() if any(w.get(r, 0) > 0 for r in want)]
        elif a.pool == 'range':
            cand = list(live)
        else:
            rdir = os.environ.get('REPLAY_DIR', os.path.expanduser('~/ScreepsArena/replays'))
            files = sorted(glob.glob(os.path.join(rdir, '*.replay.json.gz')), key=os.path.getmtime, reverse=True)[:a.limit]
            cand = [os.path.basename(f).split('.')[0] for f in files]
            for g in cand:
                live.setdefault(g, ({}, '?', 0))
        print('кандидатов (%s) под %s: %d; играю призраком (NOCLOCK=1, %d параллельно)' % (a.pool, ', '.join(want), len(cand), a.jobs))
        got = ghost_scan(cand, a.jobs)
        miss = [g for g in cand if got[g] is None]
        if miss:
            print('нет записи или reach в ghost-логе: %s' % ', '.join(miss))
        for g in cand:
            if got[g] and (a.pool == 'won' or any(got[g].get(r, 0) > 0 for r in want)):
                print('   %s (v%d vs %s): живьём %s | на стенде %s' % (
                    g, live[g][2], live[g][1], ' '.join('%s=%d' % (r, live[g][0].get(r, 0)) for r in want),
                    ' '.join('%s=%d' % (r, got[g].get(r, 0)) for r in want)))
        left, picked = set(want), []
        while left:
            ok = [g for g in cand if got[g]]
            if not ok:
                break
            g = max(ok, key=lambda g: (len(left & {r for r in want if got[g].get(r, 0) > 0}), sum(got[g].get(r, 0) for r in left)))
            hit = left & {r for r in want if got[g].get(r, 0) > 0}
            if not hit:
                break
            picked.append((g, sorted(hit)))
            left -= hit
        for g, hit in picked:
            print('взять запись %s (v%d vs %s): на стенде закрывает %s' % (g, live[g][2], live[g][1], ', '.join(hit)))
        if left:
            print('НЕ ЗАКРЫТО ни одной записью на стенде: %s' % ', '.join(sorted(left)))
        return 1 if left else 0
    G, L = len(gate), len(live)
    gshare, lshare = collections.Counter(), collections.Counter()
    for w in gate.values():
        for r, n in w.items():
            gshare[r] += n > 0
    for w, _, _ in live.values():
        for r, n in w.items():
            lshare[r] += n > 0
    print('сценариев гейта с reach: %d, живых матчей v%d–v%d: %d' % (G, lo, hi, L))
    blind = sorted(r for r in lshare if lshare[r] and not gshare[r])
    under = sorted((r for r in lshare if gshare[r] and L and gshare[r] / G < lshare[r] / L), key=lambda r: gshare[r] / G / (lshare[r] / L))
    print('выигрывают живьём и НИ РАЗУ на гейте: %s' % (', '.join(blind) or 'нет'))
    print('на гейте в меньшей доле сценариев, чем живьём матчей (по убыванию разрыва):')
    for r in under[:a.top]:
        print('   %-22s гейт %3d/%d   живьём %2d/%d' % (r, gshare[r], G, lshare[r], L))
    left, picked = set(blind), []                       # жадное покрытие слепых строк записями живых матчей
    while left:
        g = max(live, key=lambda g: len(left & {r for r, n in live[g][0].items() if n > 0}))
        got = left & {r for r, n in live[g][0].items() if n > 0}
        if not got:
            break
        picked.append((g, live[g][1], live[g][2], sorted(got)))
        left -= got
    for g, opp, v, got in picked:
        print('взять запись %s (v%d vs %s): закрывает %s' % (g, v, opp, ', '.join(got)))
    uniq = collections.Counter()                        # сценарии без уникального вклада
    for s, w in gate.items():
        for r, n in w.items():
            if n > 0 and gshare[r] == 1:
                uniq[s] += 1
    print('сценариев гейта, без которых ни одна строка не перестаёт выигрывать: %d из %d' % (sum(1 for s in gate if not uniq[s]), G))
    return 1 if (a.check and blind) else 0


if __name__ == '__main__':
    raise SystemExit(main())
