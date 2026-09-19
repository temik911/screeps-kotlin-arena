#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Что известно про ИМЯ — тег строки таблицы решений, факт, функцию, константу: одно место вместо `grep` по мегабайтам.

docs/pain-and-gain-architecture-2.md, этап 7. Знание бота лежит в пяти местах — код, история версий, архив вердиктов,
причины порядка строк, счётчики прибора `reach` — и вопрос «это уже пробовали?» до сих пор решался `grep`-ом по 1,4 МБ.
Инструмент ничего не хранит: собирает при запросе.

  python3 tools/know.py healMate                 # строка лестницы, её условие, константы, версии, вердикты, reach
  python3 tools/know.py threatAt                 # чего в коде нет: обе отвергнутые попытки найдутся в истории
  python3 tools/know.py KITE_STANDOFF --brief    # только заголовки абзацев, без цитат
  python3 tools/know.py rung.kite                # с именем таблицы — счётчики только этой строки
  --arena pain-and-gain   пакет, docs и стенд арены (по умолчанию); --live 447-999 — версии живых матчей для reach

Что печатает, по порядку: (1) МЕСТО в коде — объявление (`Row("тег"`, `val`, `fun`, `const val`) и первые употребления;
(2) КОНСТАНТЫ, которые читает строка таблицы, со значениями; (3) ПОРЯДОК — записи `order.txt` с этим тегом;
(4) ВЕРСИИ, трогавшие место (`git log -L` по строке объявления); (5) АБЗАЦЫ ВЕРСИЙ, где имя стоит в бэктиках, с первой
фразой, которая его называет; (6) АРХИВ ВЕРДИКТОВ — секции, где имя встречается; (7) REACH — выиграла / условие истинно
на гейте и живьём (последняя накопительная строка каждого лога). Из корня ворктри.
"""
import argparse
import glob
import os
import re
import subprocess
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
ARENAS = {'pain-and-gain': ('starter/src/jsMain/kotlin/season4/painandgain', 'docs/pain-and-gain', 'tools/stub/painandgain')}


def sh(*cmd):
    return subprocess.run(cmd, capture_output=True, text=True, cwd=ROOT).stdout


def code_places(pkg, word):
    decl, uses = [], []
    rx_decl = re.compile(r'(?:\b(?:Row|Gate|Pass)\("%s"|\b(?:const val|val|var|fun|class|object)\s+(?:[\w.<>?, ]+\.)?%s\b)' % (re.escape(word), re.escape(word)))
    rx_use = re.compile(r'(?<![\w"])%s\b' % re.escape(word))
    for f in sorted(glob.glob(os.path.join(ROOT, pkg, '*.kt'))):
        for n, line in enumerate(open(f, encoding='utf-8').read().split('\n'), 1):
            code = line.split('//')[0]
            if rx_decl.search(code):
                decl.append((os.path.relpath(f, ROOT), n, line.strip()))
            elif rx_use.search(code) and not line.strip().startswith(('*', '/*', '//')):
                uses.append((os.path.relpath(f, ROOT), n, line.strip()))
    return decl, uses


def comment_versions(pkg, word, decl):
    """Версии (`vNNN`), названные в комментариях кода: в блоке комментариев прямо НАД объявлением и в любом комментарии
    пакета, который называет имя. У строки таблицы вердикт её места живёт именно там («ЛЕКАРЬ ПРИ МИЛИ (v235, …)»)."""
    out = []
    files = {}
    for f, n, _ in decl:
        L = files.setdefault(f, open(os.path.join(ROOT, f), encoding='utf-8').read().split('\n'))
        k = n - 2
        while k >= 0 and L[k].strip().startswith(('//', '*', '/*')):
            out += re.findall(r'\bv(\d{2,3})\b', L[k]); k -= 1
    for f in sorted(glob.glob(os.path.join(ROOT, pkg, '*.kt'))):
        for line in open(f, encoding='utf-8').read().split('\n'):
            c = line.split('//', 1)[1] if '//' in line else (line if line.strip().startswith('*') else '')
            if c and re.search(r'\b%s\b' % re.escape(word), c):
                out += re.findall(r'\bv(\d{2,3})\b', c)
    return out


def constants(pkg, text):
    names = sorted(set(re.findall(r'\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b', text)))
    out = []
    for nm in names:
        for f in sorted(glob.glob(os.path.join(ROOT, pkg, '*.kt'))):
            m = re.search(r'^\s*(?:internal |private )?(?:const )?val %s\b[^=\n]*=\s*([^\n/]+)' % re.escape(nm), open(f, encoding='utf-8').read(), re.M)
            if m:
                out.append((nm, m.group(1).strip(), os.path.basename(f)))
                break
    return out


def sections(path):
    """[(заголовок, текст)] — секции файла по заголовкам `##` / `###`."""
    out, head, buf = [], None, []
    for line in open(path, encoding='utf-8').read().split('\n'):
        if re.match(r'#{2,3} ', line):
            if head is not None:
                out.append((head, '\n'.join(buf)))
            head, buf = line.lstrip('# ').strip(), []
        elif head is not None:
            buf.append(line)
    if head is not None:
        out.append((head, '\n'.join(buf)))
    return out


def first_sentence(text, word):
    flat = re.sub(r'\s+', ' ', text)
    m = re.search(r'[^.!?]*`[^`]*\b%s\b[^`]*`[^.!?]*[.!?]' % re.escape(word), flat) or re.search(r'[^.!?]*\b%s\b[^.!?]*[.!?]' % re.escape(word), flat)
    s = (m.group(0) if m else flat[:200]).strip()
    return s if len(s) <= 320 else s[:317] + '…'


def last_reach(text):
    best = None
    for m in re.finditer(r'reach t=(\d+): ([^\n"\\]+)', text):
        if best is None or int(m.group(1)) >= best[0]:
            best = (int(m.group(1)), m.group(2))
    return best[1] if best else None


def reach_rows(line, word):
    """{таблица.тег: (won, on)} для строк с этим тегом (или ровно `таблица.тег`)."""
    out = {}
    for tbl in line.split(' '):
        if '=' not in tbl:
            continue
        name, body = tbl.split('=', 1)
        for cell in body.split(','):
            mm = re.match(r'([\w.+-]+):(\d+)/(\d+)/(\d+)$', cell)
            if mm and (mm.group(1) == word or '%s.%s' % (name, mm.group(1)) == word):
                out['%s.%s' % (name, mm.group(1))] = (int(mm.group(2)), int(mm.group(3)))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('word', help='тег строки (`healMate`, `rung.kite`), имя факта, функции или константы')
    ap.add_argument('--arena', default='pain-and-gain', choices=sorted(ARENAS))
    ap.add_argument('--brief', action='store_true', help='абзацы версий и вердикты — только заголовки')
    ap.add_argument('--live', default='447-999', help='версии бота, чьи живые матчи читать для reach')
    ap.add_argument('--no-reach', action='store_true', help='не читать логи (быстрее)')
    a = ap.parse_args()
    pkg, docs, stub = ARENAS[a.arena]
    word = a.word.split('.', 1)[1] if re.match(r'(rung|step|gate|pass|posture|mode|cmdwhy|push)\.', a.word) else a.word

    decl, uses = code_places(pkg, word)
    print('== МЕСТО В КОДЕ: %s' % ('объявлений %d, употреблений %d' % (len(decl), len(uses)) if decl or uses else 'в пакете имени НЕТ'))
    for f, n, line in decl:
        print('   %s:%d  %s' % (f, n, line[:230]))
    for f, n, line in uses[:6]:
        print('     ↳ %s:%d  %s' % (f, n, line[:200]))
    if len(uses) > 6:
        print('     ↳ … ещё %d' % (len(uses) - 6))

    rows = [d for d in decl if re.search(r'\b(?:Row|Gate|Pass)\("', d[2])]
    if rows:
        cs = constants(pkg, ' '.join(d[2] for d in rows))
        if cs:
            print('== КОНСТАНТЫ, которые читает строка: ' + '; '.join('%s = %s (%s)' % c for c in cs))

    order = os.path.join(ROOT, stub, 'order.txt')
    if os.path.exists(order):
        hits = [l.strip() for l in open(order, encoding='utf-8') if not l.startswith('#') and re.search(r'[\s:>]%s(?=\s)' % re.escape(word), l.split('#')[0] + ' ')]
        if hits:
            print('== ПОРЯДОК (order.txt):')
            for l in hits:
                print('   ' + l[:260])

    for f, n, _ in decl[:2]:
        log = sh('git', 'log', '-L%d,%d:%s' % (n, n, f), '--format=@@%h %ad %s', '--date=short', '-s')
        commits = [l[2:] for l in log.split('\n') if l.startswith('@@')]
        if commits:
            print('== ВЕРСИИ, трогавшие %s:%d (git log -L): %d' % (f, n, len(commits)))
            for c in commits[:8]:
                print('   ' + c[:200])

    hist = [os.path.join(ROOT, docs + '.md')] + sorted(glob.glob(os.path.join(ROOT, docs, 'history-*.md')))
    found = []
    for p in hist:
        if os.path.exists(p):
            for head, text in sections(p):
                if re.search(r'`[^`\n]*\b%s\b[^`\n]*`' % re.escape(word), text) or re.search(r'\b%s\b' % re.escape(word), head):
                    found.append((os.path.relpath(p, ROOT), head, text))
    print('== АБЗАЦЫ ВЕРСИЙ, где `%s` стоит в бэктиках: %d' % (word, len(found)))
    named = list(comment_versions(pkg, word, decl))
    for p, head, text in found:
        print('   [%s] %s' % (os.path.basename(p), head[:150]))
        sent = first_sentence(text, word)
        flat = re.sub(r'\s+', ' ', text)
        for m in re.finditer(r'[^.!?]*\b%s\b[^.!?]*[.!?]' % re.escape(word), flat):      # версии, названные рядом с именем
            named += re.findall(r'\bv(\d{2,3})\b', m.group(0))
        if not a.brief:
            print('       ' + sent)
    # СВЯЗАННЫЕ ВЕРСИИ: названы в комментариях кода у объявления и во фразах, называющих имя, — а их собственные абзацы имени
    # могут и не нести (v246 зовёт `threatAt` «адресной опасностью t+1»; вердикт ступени `healMate` — «лекарь при мили», v235)
    have = {h for _, h, _ in found}
    heads = {}
    for p in hist:
        if os.path.exists(p):
            for head, _ in sections(p):
                for v in re.findall(r'\bv(\d{2,3})\b', head.split('(')[0]):
                    heads.setdefault(v, []).append((os.path.basename(p), head))
    linked = [(v, x) for v in sorted(set(named), key=int) for x in heads.get(v, []) if x[1] not in have]
    if linked:
        print('== СВЯЗАННЫЕ ВЕРСИИ (названы в комментариях кода или рядом с именем в docs): %d' % len(linked))
        for v, (p, head) in linked:
            print('   [%s] %s' % (p, head[:150]))

    ver = os.path.join(ROOT, docs + '-verdicts.md')
    if os.path.exists(ver):
        blocks = re.split(r'\n(?=### )', open(ver, encoding='utf-8').read())
        hits = [b for b in blocks if re.search(r'\b%s\b' % re.escape(word), b)]
        print('== АРХИВ ВЕРДИКТОВ: секций %d' % len(hits))
        for b in hits[:12]:
            head = b.split('\n', 1)[0].lstrip('# ').strip()
            print('   ' + head[:150])
            if not a.brief:
                print('       ' + first_sentence(b.split('\n', 1)[1] if '\n' in b else b, word))
        if len(hits) > 12:
            print('   … ещё %d' % (len(hits) - 12))

    if not a.no_reach:
        lo, hi = map(int, a.live.split('-'))
        gate, live = {}, {}
        logs = sorted(glob.glob(os.path.join(ROOT, stub, 'out', 'run-land-*.log')))
        for f in logs:
            l = last_reach(open(f, errors='ignore').read())
            for k, (w, o) in (reach_rows(l, a.word) if l else {}).items():
                g = gate.setdefault(k, [0, 0, 0]); g[0] += w; g[1] += o; g[2] += w > 0
        listing = sh(sys.executable, 'tools/match-log.py', 'list', '--arena', a.arena, '--all')
        n_live = 0
        for l in listing.split('\n'):
            m = re.search(r'\s([0-9a-f]{24})\s+\S+\s+v(\d+)\s', l)
            if not m or not lo <= int(m.group(2)) <= hi:
                continue
            text = ''.join(open(f, errors='ignore').read() for f in glob.glob(os.path.expanduser('~/ScreepsArena/games/%s/log-*.json' % m.group(1))))
            lr = last_reach(text)
            if lr:
                n_live += 1
                for k, (w, o) in reach_rows(lr, a.word).items():
                    g = live.setdefault(k, [0, 0, 0]); g[0] += w; g[1] += o; g[2] += w > 0
        if gate or live:
            print('== REACH (выиграла / условие истинно; в скольких логах выиграла): гейт — %d логов, живьём v%d+ — %d матчей' % (len(logs), lo, n_live))
            for k in sorted(set(gate) | set(live)):
                g, v = gate.get(k, [0, 0, 0]), live.get(k, [0, 0, 0])
                print('   %-22s гейт %d/%d в %d   живьём %d/%d в %d' % (k, g[0], g[1], g[2], v[0], v[1], v[2]))


if __name__ == '__main__':
    main()
