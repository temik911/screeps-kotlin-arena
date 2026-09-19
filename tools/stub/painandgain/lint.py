#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Линт написаний пакета season4/painandgain: то, что «так больше не пишем», держит форма, а не память.

docs/pain-and-gain-architecture.md, правило 3.6: каждое сведённое написание (ролевой тест, ключ клетки, защёлка
руками, счётчик не у владельца) приходит вместе с проверкой в гейте — иначе через двадцать версий оно нарушено.

  python3 tools/stub/painandgain/lint.py            # список нарушений: файл:строка, правило, текст
  python3 tools/stub/painandgain/lint.py --gate     # одна строка PASS/FAIL для tools/land.sh, подробности в stderr

Правило — (имя, регулярное выражение, где написание разрешено, что писать вместо). Выражение примеряется к КОДУ
строки: комментарии `//`, `/* … */` и KDoc вырезаны (вердикты в комментариях цитируют старые написания законно),
строковые литералы оставлены. «Где разрешено» — имена файлов пакета, обычно один: тот, где лежит определение.
Список пополняется этапами 1, 2, 3 и 6 плана — тем же коммитом под tools/, что следует за заменой написания в коде.
"""
import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.normpath(os.path.join(HERE, '../../../starter/src/jsMain/kotlin/season4/painandgain'))

# (имя правила, выражение, {файлы, где разрешено}, что писать вместо)
RULES = [
]

# Проверки, которым мало одной строки: функция (исходники: {файл: [(номер, код)]}) -> [(файл, номер, текст)]
CHECKS = [
]


def code_lines(text):
    """[(номер строки, код без комментариев)] — строковые литералы целы, `//` внутри них комментарием не считается."""
    out, block, n = [], 0, 0
    for raw in text.split('\n'):
        n += 1
        i, buf, quote = 0, [], None
        while i < len(raw):
            two = raw[i:i + 2]
            if block:
                if two == '*/':
                    block -= 1; i += 2
                elif two == '/*':
                    block += 1; i += 2                      # в Kotlin блочные комментарии вкладываются
                else:
                    i += 1
                continue
            ch = raw[i]
            if quote:
                buf.append(ch)
                if ch == '\\' and i + 1 < len(raw):
                    buf.append(raw[i + 1]); i += 2; continue
                if ch == quote:
                    quote = None
                i += 1
                continue
            if two == '//':
                break
            if two == '/*':
                block += 1; i += 2; continue
            if ch in '"\'':
                quote = ch
            buf.append(ch); i += 1
        out.append((n, ''.join(buf)))
    return out


def load(src):
    return {f: code_lines(open(os.path.join(src, f), encoding='utf-8').read())
            for f in sorted(os.listdir(src)) if f.endswith('.kt')}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--gate', action='store_true', help='одна строка PASS/FAIL для tools/land.sh, подробности в stderr')
    ap.add_argument('--src', default=SRC, help='каталог пакета (по умолчанию — пакет этого ворктри)')
    a = ap.parse_args()
    files = load(a.src)
    hits = []
    for name, rx, allowed, instead in RULES:
        rx = re.compile(rx)
        for f, rows in files.items():
            if f in allowed:
                continue
            for n, code in rows:
                if rx.search(code):
                    hits.append((f, n, name, instead, code.strip()))
    for check in CHECKS:
        for f, n, text in check(files):
            hits.append((f, n, check.__name__, '', text))
    out = sys.stderr if a.gate else sys.stdout
    for f, n, name, instead, code in hits:
        print('%s:%d: [%s] %s%s' % (f, n, name, code[:140], ('   -> ' + instead) if instead else ''), file=out)
    if a.gate:
        what = 'rules %d files %d hits %d' % (len(RULES) + len(CHECKS), len(files), len(hits))
        print('%-4s %-22s %-40s | errors: %d ' % ('FAIL' if hits else 'PASS', 'lint', what, len(hits)))
    else:
        print('правил %d, файлов %d, нарушений %d' % (len(RULES) + len(CHECKS), len(files), len(hits)))
    return 1 if hits else 0


if __name__ == '__main__':
    sys.exit(main())
