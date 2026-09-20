#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Тождество логов стенда: первое расхождение каждого лога гейта с эталоном.

Оракул механических этапов переработки (docs/pain-and-gain-rework.md, раздел 8): `compare.py` сравнивает пять полей
исхода, а этот скрипт — логи сценариев целиком. Логи детерминированы, кроме строк `cpu t=`, поля `time=` в `done:` и поля
`srch=` (хвост тика в мс); они и номер версии в приветствии — единственное, что отбрасывается и маскируется.

  python3 tools/stub/painandgain/logdiff.py [--old runs/gate_235] [--new tools/stub/painandgain/out] [подстрока…]

Печатает число логов с расхождением, гистограмму «строка-тег + первое отличающееся поле k=v» и первые расхождения
(старое/новое) — по всем логам или только по тем, чьё имя содержит одну из подстрок. Из корня ворктри.

НОВОЕ ПОЛЕ МАСКИРУЕТСЯ САМО (20.09.2026, docs/pain-and-gain-architecture-2.md, этап 0, правило 3.4). До этого каждое новое
поле прибора требовало ручной маски в списке MASK — второго списка рядом с объявлением прибора, и забытая маска выглядела
как расхождение оракула. Теперь: поле `k=…`, которого в строке эталона НЕТ, а в новой строке есть, из новой строки
вынимается и расхождением не считается; строка, чей тег (первое слово) в логе эталона не встречается вовсе, — новая
строка прибора и пропускается. И то и другое НАЗЫВАЕТСЯ в отчёте («новые поля: …», «новые строки: …») — молчаливой
маски нет. В другую сторону поблажки нет: поле, которое в эталоне есть, а в новом логе исчезло или изменилось, —
расхождение, как и было: существующие поля не маскируются никогда, это и есть оракул. Список MASK ниже — история
(поля, снятые или добавленные прежними заходами против ИХ эталонов); новыми полями он больше не пополняется.
"""
import argparse
import os
import re
from collections import Counter

MASK = [
    (re.compile(r'hello season4 pain-and-gain v\d+'), 'hello season4 pain-and-gain vX'),
    (re.compile(r' cpu=\d+/\d+$'), ' cpu=X'),
    # srch=обрезок/тиков/ХВОСТ ТИКА В МС — третья часть читает настоящее время; поле маскируется целиком
    (re.compile(r' srch=\S+'), ''),
]
# 20.09.2026 (второй шаг архитектуры, этап 2): маски полей `anchor=`, `ovw= conf=`, `disp=`, `evt=`, `lead=`, `deals= srchd=`,
# `capq= … mstrip=` СНЯТЫ, и строки `tac t=` / `reach t=` больше не отбрасываются. Каждая вводилась как «новое поле против
# ТОГДАШНЕГО эталона»; в нынешнем эталоне эти поля и строки есть, они детерминированы, и маска на них — слепое пятно оракула:
# перенос прибора `reach` в раскладку (v455) тождеством не проверялся вовсе, пока строку отбрасывали. Новое поле маскируется само.


def lines(p):
    out = []
    for l in open(p, encoding='utf-8', errors='replace').read().split('\n'):
        if l.startswith('cpu t=') or 'time=' in l:
            continue
        for rx, rep in MASK:
            l = rx.sub(rep, l)
        out.append(l)
    return out


FIELD = re.compile(r'(?<!\S)([A-Za-z][\w.]*)=')


def tag_of(line):
    """Тег строки лога — первое слово без хвоста `=…` (`t=50` -> `t`, `reach` -> `reach`, `posture:` -> `posture:`)."""
    return line.split(' ', 1)[0].split('=', 1)[0]


class Baseline:
    """Что эталон знает ВЕСЬ, а не один его лог: тег строки или поле считается новым, только если его нет НИ В ОДНОМ логе
    эталона. Иначе событие, которое в этом сценарии раньше не случалось (`cornered t=…`), или необязательное поле, которое
    раньше здесь не печаталось, сошло бы за «новый прибор» — а это расхождение поведения. Читается лениво: пока строки
    совпадают, эталон целиком не нужен."""

    def __init__(self, folder):
        self.folder, self.texts, self.memo = folder, None, {}

    def _load(self):
        if self.texts is None:
            self.texts = [open(os.path.join(self.folder, f), encoding='utf-8', errors='replace').read()
                          for f in sorted(os.listdir(self.folder)) if f.startswith('run-land-')]

    def has(self, tag, key=None):
        k = (tag, key)
        if k not in self.memo:
            self._load()
            rx = re.compile(r'^%s[ =]%s' % (re.escape(tag), '' if key is None else r'[^\n]*(?<!\S)%s=' % re.escape(key)), re.M)
            self.memo[k] = any(rx.search(t) for t in self.texts)
        return self.memo[k]


def drop_new_fields(old, new, base, seen):
    """Вынуть из новой строки слова `k=…`, ключа которых нет ни в строке эталона, ни где-либо в эталоне под тем же тегом
    строки; что вынуто — записать в seen. Слово — от пробела до пробела: поле прибора пишется без пробелов внутри.
    Ведущего пробела у поля может не быть (склейка `fmassed=0stray=0` — одно слово с ключом `fmassed`), такое слово
    целиком остаётся существующим полем."""
    have = set(FIELD.findall(old))
    tag = tag_of(old)
    out = []
    for w in new.split(' '):
        m = FIELD.match(w)
        if m and m.group(1) not in have and not base.has(tag, m.group(1)):
            seen[m.group(1)] += 1
            continue
        out.append(w)
    return ' '.join(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--old', default='runs/gate_250', help='эталон логов: посадка, с которой сверяется тождество (после поведенческой правки эталон переснимается с её тега в отдельном ворктри)')
    ap.add_argument('--new', default='tools/stub/painandgain/out')
    ap.add_argument('--show', type=int, default=6, help='сколько расхождений печатать подробно')
    ap.add_argument('only', nargs='*')
    a = ap.parse_args()
    first_fields = Counter()
    new_fields, new_lines = Counter(), Counter()
    base = Baseline(a.old)
    rows = []
    for f in sorted(os.listdir(a.old)):
        if not f.startswith('run-land-'):
            continue
        if a.only and not any(o in f for o in a.only):
            continue
        new_p = os.path.join(a.new, f)
        if not os.path.exists(new_p):
            rows.append((f, 0, '<нет лога>', [], '', '<нет нового лога>'))
            continue
        x = lines(os.path.join(a.old, f))
        y = lines(new_p)
        old_tags = {tag_of(l) for l in x}
        kept = []
        for l in y:                                     # новая строка прибора: тега нет НИГДЕ в эталоне
            if l and tag_of(l) not in old_tags and not base.has(tag_of(l)):
                new_lines[tag_of(l)] += 1
            else:
                kept.append(l)
        y = kept
        k = None
        for i in range(min(len(x), len(y))):            # новое поле: ключа нет в эталоне под этим тегом строки
            if x[i] != y[i]:
                found = Counter()
                y[i] = drop_new_fields(x[i], y[i], base, found)
                if x[i] != y[i]:                        # первое настоящее расхождение: дальше строки сдвинуты, не читаем
                    k = i
                    break
                new_fields.update(found)
        if k is None and len(x) == len(y):
            continue
        if k is None:
            k = min(len(x), len(y))
        la = x[k] if k < len(x) else '<конец>'
        lb = y[k] if k < len(y) else '<конец>'
        fa = dict(re.findall(r'(\S+?)=(\S+)', la))
        fb = dict(re.findall(r'(\S+?)=(\S+)', lb))
        diffk = [q for q in fa if q in fb and fa[q] != fb[q]]
        tag = la.split(' ')[0] if la else '?'
        first_fields[(tag, diffk[0] if diffk else '-')] += 1
        rows.append((f, k + 1, tag, diffk[:4], la[:150], lb[:150]))
    print('логов с расхождением:', len(rows))
    if new_fields:
        print('новые поля (в эталоне их нет, расхождением не считаются): %s' % ', '.join('%s= ×%d' % kv for kv in sorted(new_fields.items())))
    if new_lines:
        print('новые строки (тега нет в эталоне, пропущены): %s' % ', '.join('%s ×%d' % kv for kv in sorted(new_lines.items())))
    for key, n in first_fields.most_common(12):
        print('%3d  %s %s' % (n, key[0], key[1]))
    for r in (rows if a.only else rows[:a.show]):
        print('---', r[0], 'строка', r[1], 'поля', r[3])
        print('  старое:', r[4])
        print('  новое: ', r[5])
    return 1 if rows else 0


if __name__ == '__main__':
    raise SystemExit(main())
