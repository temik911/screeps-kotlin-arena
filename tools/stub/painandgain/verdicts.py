#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Знание из комментариев PainAndGain.kt → docs, и проверка, что удаление кода ничего не теряет.

Половина файла бота — комментарии с замерами (номера версий, счета гейта, живые A/B, леджеры). Переработка удаляет
тумблеры и ветки вместе с этими комментариями, поэтому ДО удаления текст переезжает в docs дословно, а КАЖДЫЙ коммит
удаления проверяется: всё, что вычеркнуто, должно найтись в docs.

  emit   [--code PATH] [--out PATH]      печатает приложение (по умолчанию docs/pain-and-gain-verdicts.md): раздел на
                                         каждый тумблер `USE_*` с его комментарием и объявлением, затем прочие блоки
                                         комментариев с номерами версий или числами-замерами (с ближайшей функцией).
                                         Идемпотентно: блок, все строки которого уже есть в docs, пропускается, так что
                                         запуск после частичного удаления дописывает только новое.
  check  --base REF [--code PATH]        берёт удалённые строки `git diff REF -- PainAndGain.kt`, из строк-комментариев
                                         вынимает (i) номера версий `vNNN`, (ii) текст строк длиннее 40 символов,
                                         (iii) числа-замеры вида `128/131`, `4-4`, `23 954:23 223`, и ищет каждое в
                                         docs/pain-and-gain*.md (сравнение по нормализованному тексту, пробелы
                                         схлопнуты). Ненулевой счёт пропаж → код возврата 1 — коммит не делается.

Запуск из корня ворктри: `python3 tools/stub/painandgain/verdicts.py emit` / `... check --base pain-and-gain-v235`.
"""
import argparse
import glob
import os
import re
import subprocess
import sys

CODE = 'starter/src/jsMain/kotlin/season4/painandgain/PainAndGain.kt'
OUT = 'docs/pain-and-gain-verdicts.md'
DOCS_GLOB = 'docs/pain-and-gain*.md'
VER_RE = re.compile(r'\bv(\d{1,3})\b')
NUM_RE = re.compile(r'\d[\d ]*[-:/]\d[\d ]*')
TOGGLE_RE = re.compile(r'^\s*private const val (USE_[A-Z0-9_]+)\s*=\s*(\S+)(.*)$')
FUN_RE = re.compile(r'^\s*(?:private |internal |override |inline )*fun\s+([A-Za-z0-9_]+)\s*\(')


def norm(s):
    return re.sub(r'\s+', ' ', s).strip()


def strip_comment(line):
    """Текст строки комментария без разметки: `/** `, ` * `, ` */`, `// `."""
    s = line.strip()
    s = re.sub(r'^/\*\*?\s{0,2}', '', s)
    s = re.sub(r'^\*\s{0,2}', '', s)
    s = re.sub(r'^//\s?', '', s)
    s = re.sub(r'\s*\*/\s*$', '', s)
    return s


def comment_blocks(lines):
    """[(start, end, text_lines)] — KDoc/блочные `/* … */` и сплошные `//` (индексы строк 0-based, end включительно)."""
    blocks = []
    i = 0
    n = len(lines)
    while i < n:
        s = lines[i].lstrip()
        if s.startswith('/*'):
            j = i
            while j < n and '*/' not in lines[j]:
                j += 1
            blocks.append((i, min(j, n - 1)))
            i = j + 1
        elif s.startswith('//'):
            j = i
            while j + 1 < n and lines[j + 1].lstrip().startswith('//'):
                j += 1
            blocks.append((i, j))
            i = j + 1
        else:
            i += 1
    return blocks


def is_comment_line(line):
    s = line.strip()
    return s.startswith('//') or s.startswith('/*') or s.startswith('*')


def interesting(text):
    return bool(VER_RE.search(text) or NUM_RE.search(text) or '%' in text or 'гейт' in text.lower())


def nearest_fun(lines, idx):
    """Имя функции: следующая `fun` в трёх строках после блока, иначе ближайшая `fun` выше."""
    for k in range(idx + 1, min(idx + 4, len(lines))):
        m = FUN_RE.match(lines[k])
        if m:
            return m.group(1)
    for k in range(idx, -1, -1):
        m = FUN_RE.match(lines[k])
        if m:
            return m.group(1)
    return 'верх файла'


def docs_text(pattern=None):
    parts = []
    for p in sorted(glob.glob(pattern or DOCS_GLOB)):
        parts.append(open(p, encoding='utf-8').read())
    return norm('\n'.join(parts))


def emit(code_path, out_path):
    lines = open(code_path, encoding='utf-8').read().split('\n')
    blocks = comment_blocks(lines)
    block_by_end = {e: (s, e) for s, e in blocks}
    used = set()
    existing = ''
    if os.path.exists(out_path):
        existing = norm(open(out_path, encoding='utf-8').read())
    known = docs_text() + ' ' + existing

    def present(text_lines):
        """Блок уже известен, только если КАЖДАЯ его содержательная строка (после нормализации, длиннее 40) есть в docs —
        проверка по первым символам пропускала блок, чьё начало цитировалось в истории версий, и теряла остальное."""
        lines_n = [norm(t) for t in text_lines if len(norm(t)) > 40]
        return bool(lines_n) and all(t in known for t in lines_n)

    toggles = []      # (names, values, decl_lines, block or None)
    i = 0
    while i < len(lines):
        m = TOGGLE_RE.match(lines[i])
        if not m:
            i += 1
            continue
        group = [i]
        j = i + 1
        while j < len(lines) and TOGGLE_RE.match(lines[j]):
            group.append(j)
            j += 1
        blk = block_by_end.get(i - 1)
        if blk:
            used.add(blk)
        toggles.append((group, blk))
        i = j

    out = []
    out.append('# Pain and Gain — знание, перенесённое из кода')
    out.append('')
    out.append('Файл пишет `tools/stub/painandgain/verdicts.py emit` (см. его шапку). Это дословные комментарии с замерами,')
    out.append('которые стояли у тумблеров `USE_*` и у правил в `PainAndGain.kt` перед переработкой (v235, 13.09.2026);')
    out.append('код их больше не несёт. Каждый коммит удаления проверяется `verdicts.py check`: номера версий, строки и')
    out.append('числа из удалённых комментариев обязаны найтись здесь или в `docs/pain-and-gain.md`.')
    out.append('')
    out.append('## Тумблеры v235 (перенесено из кода)')
    out.append('')
    skipped = 0
    for group, blk in toggles:
        names = [TOGGLE_RE.match(lines[k]).group(1) for k in group]
        text = []
        if blk:
            text = [strip_comment(l) for l in lines[blk[0]:blk[1] + 1]]
        decl = [lines[k].strip() for k in group]
        if present(text + decl):
            skipped += 1
            continue
        out.append('### ' + ', '.join(names))
        out.append('')
        out.append('```')
        out.extend(t for t in text if t != '')
        out.extend(decl)
        out.append('```')
        out.append('')
    out.append('## Замеры по месту (v235, перенесено из кода)')
    out.append('')
    for s, e in blocks:
        if (s, e) in used:
            continue
        text = [strip_comment(l) for l in lines[s:e + 1]]
        joined = ' '.join(text)
        if not interesting(joined):
            continue
        if present(text):
            skipped += 1
            continue
        out.append('### %s (строка %d)' % (nearest_fun(lines, e), s + 1))
        out.append('')
        out.append('```')
        out.extend(t for t in text if t != '')
        out.append('```')
        out.append('')
    mode = 'a' if existing else 'w'
    body = '\n'.join(out) + '\n'
    if existing:
        # дописываем только новые разделы, без повторной шапки
        body = '\n'.join(out[out.index('## Тумблеры v235 (перенесено из кода)'):]) + '\n'
        body = body.replace('## Тумблеры v235 (перенесено из кода)', '## Тумблеры (дописано позже)', 1)
        body = body.replace('## Замеры по месту (v235, перенесено из кода)', '## Замеры по месту (дописано позже)', 1)
    with open(out_path, mode, encoding='utf-8') as f:
        f.write(body)
    no_block = sum(1 for _, b in toggles if b is None)
    print('тумблеров %d (объявлений %d, без блока комментария над ними %d), блоков комментариев %d, пропущено уже '
          'известных %d, записано в %s (%s)'
          % (len(toggles), sum(len(g) for g, _ in toggles), no_block, len(blocks), skipped, out_path,
             'дописано' if existing else 'создано'))


def check(code_path, base, docs_pattern=None, append_to=None):
    r = subprocess.run(['git', 'diff', base, '--', code_path], capture_output=True, text=True)
    if r.returncode != 0:
        print('git diff не удался:', r.stderr.strip())
        return 2
    diff = r.stdout
    removed = [l[1:] for l in diff.split('\n') if l.startswith('-') and not l.startswith('---')]
    removed_comments = [strip_comment(l) for l in removed if is_comment_line(l)]
    # ...и хвостовые комментарии удалённых строк кода: `val x = …   // замер …` — знание там же
    for l in removed:
        if is_comment_line(l):
            continue
        m = re.search(r'\s//\s?(.*)$', l)
        if m and l[:m.start()].count('"') % 2 == 0:
            removed_comments.append(m.group(1).strip())
    # известно и то, что ПЕРЕЕХАЛО в другой файл пакета (разбивка на файлы — не удаление)
    pkg = os.path.dirname(code_path)
    moved = ' '.join(norm(open(os.path.join(pkg, f), encoding='utf-8').read())
                     for f in sorted(os.listdir(pkg)) if f.endswith('.kt') and os.path.join(pkg, f) != code_path)
    known = docs_text(docs_pattern) + ' ' + moved
    vers = set()
    nums = set()
    texts = []
    for t in removed_comments:
        vers.update('v' + v for v in VER_RE.findall(t))
        nums.update(norm(x) for x in NUM_RE.findall(t))
        if len(norm(t)) > 40:
            texts.append(norm(t))
    miss_v = sorted(v for v in vers if not re.search(r'\b%s\b' % re.escape(v), known))
    miss_n = sorted(x for x in nums if x not in known)
    miss_t = [t for t in texts if t not in known]
    print('удалено строк-комментариев %d; версий %d (не найдено %d); чисел %d (не найдено %d); строк >40 %d (не найдено %d)'
          % (len(removed_comments), len(vers), len(miss_v), len(nums), len(miss_n), len(texts), len(miss_t)))
    for v in miss_v:
        print('  версия не найдена:', v)
    for x in miss_n[:40]:
        print('  число не найдено:', x)
    for t in miss_t[:40]:
        print('  строка не найдена:', t[:110])
    if append_to and (miss_v or miss_n or miss_t):
        # дословно дописать пропавшее в приложение — под заголовком с базой сравнения, чтобы было видно, откуда
        with open(append_to, 'a', encoding='utf-8') as f:
            f.write('\n## Строки, снятые при удалении кода (база %s)\n\n' % base)
            f.write('Хвостовые и одиночные комментарии удалённых строк, которых не было в приложении; перенесены дословно\n')
            f.write('режимом `verdicts.py check --append`.\n\n```\n')
            for t in removed_comments:
                tn = norm(t)
                if tn and (tn in miss_t or any(x in tn for x in miss_n) or any(re.search(r'\b%s\b' % v, tn) for v in miss_v)):
                    f.write(t.strip() + '\n')
            f.write('```\n')
        print('дописано в', append_to, '— повторите check')
    return 1 if (miss_v or miss_n or miss_t) else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('mode', choices=['emit', 'check'])
    ap.add_argument('--code', default=CODE)
    ap.add_argument('--out', default=OUT)
    ap.add_argument('--base')
    ap.add_argument('--docs', help='glob файлов docs для check (по умолчанию %s)' % DOCS_GLOB)
    ap.add_argument('--append', action='store_true', help='check: дописать пропавшие строки в --out дословно')
    a = ap.parse_args()
    if a.mode == 'emit':
        emit(a.code, a.out)
        return 0
    if not a.base:
        ap.error('check требует --base REF')
    return check(a.code, a.base, a.docs, a.out if a.append else None)


if __name__ == '__main__':
    sys.exit(main())
