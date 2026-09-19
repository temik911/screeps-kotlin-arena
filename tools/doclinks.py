#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Порванные ссылки на файлы репозитория — в docs/, CLAUDE.md, tools/ и комментариях кода.

Документ режут, файл переезжает — а ссылка на него в соседнем документе, в README стенда или в комментарии кода остаётся
и никуда не ведёт. Проверяется то, что можно проверить механически: упомянутый ПУТЬ существует.

  python3 tools/doclinks.py            # список порванных ссылок; код возврата 1, если они есть
  python3 tools/doclinks.py --all      # и сколько ссылок проверено по каждому файлу

Ссылкой считается: путь в бэктиках или markdown-ссылка вида `docs/….md`, `tools/….py|sh|mjs|txt|md`,
`starter/….kt`, `types/….kt`; относительная markdown-ссылка `[…](файл.md)` — от каталога документа. Пути с
шаблоном (`<арена>`, `*`, `…`, `N`) и каталоги со слэшем на конце проверяются как каталог до шаблона. Чего проверка НЕ
видит: ссылку словами («абзац v442») — переехал ли абзац внутри файла, ей не узнать; это дело `tools/know.py`.
"""
import argparse
import glob
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
PATH = re.compile(r'(?<![\w/.-])((?:docs|tools|starter|types|arenas)/[\w./<>*…+-]*[\w>*…])')
FOREIGN = {'docs/FORMAT.md'}                            # файл ЧУЖОГО репозитория (формат реплея arukuka), названный по его пути
MDLINK = re.compile(r'\]\(([^)#\s]+?\.md)(?:#[^)]*)?\)')


def sources():
    out = [os.path.join(ROOT, 'CLAUDE.md')]
    out += glob.glob(os.path.join(ROOT, 'docs', '**', '*.md'), recursive=True)
    for ext in ('py', 'sh', 'md', 'txt', 'mjs'):
        out += glob.glob(os.path.join(ROOT, 'tools', '**', '*.' + ext), recursive=True)
    out += glob.glob(os.path.join(ROOT, 'starter', 'src', '**', '*.kt'), recursive=True)
    return sorted(p for p in set(out) if '/node_modules/' not in p and '/out/' not in p and '/replays/' not in p)


def exists(rel):
    cut = re.split(r'[<*…]|\.\.\.|\bN\b', rel)[0]
    if cut != rel:                                      # путь с шаблоном: проверяется каталог до него
        cut = cut.rsplit('/', 1)[0] if '/' in cut else cut
        return os.path.isdir(os.path.join(ROOT, cut)) or not cut
    p = os.path.join(ROOT, rel)
    return os.path.exists(p) or bool(glob.glob(p + '*'))   # `docs/pain-and-gain` как префикс семейства файлов


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--all', action='store_true')
    a = ap.parse_args()
    bad, seen = [], 0
    for src in sources():
        text = open(src, encoding='utf-8', errors='replace').read()
        n = 0
        for ln, line in enumerate(text.split('\n'), 1):
            for m in PATH.finditer(line):
                rel = m.group(1).rstrip('.,;:')
                if rel in FOREIGN:
                    continue
                n += 1
                if not exists(rel):
                    bad.append((os.path.relpath(src, ROOT), ln, rel))
            if src.endswith('.md'):
                for m in MDLINK.finditer(line):
                    rel = m.group(1)
                    if re.match(r'https?:', rel):
                        continue
                    n += 1
                    if not os.path.exists(os.path.normpath(os.path.join(os.path.dirname(src), rel))):
                        bad.append((os.path.relpath(src, ROOT), ln, rel))
        seen += n
        if a.all and n:
            print('%5d  %s' % (n, os.path.relpath(src, ROOT)))
    for f, ln, rel in bad:
        print('%s:%d: нет файла %s' % (f, ln, rel))
    print('ссылок проверено %d, порванных %d' % (seen, len(bad)))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
