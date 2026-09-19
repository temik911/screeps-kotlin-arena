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
A = r'([\w.]+)'          # аргумент ролевого теста: `it`, `creep`, `m.creep`
RULES = [
    # этап 1, «чистый мили»: два разных факта — А (рождён мили) и Б/В (живая ATTACK); не сводить, см. Facts.kt
    ('чистый мили А', r'isMelee\(%s\) && !hasRanged\(\1\)' % A, {'Facts.kt'}, 'meleeOnlyBorn(x)'),
    ('чистый мили ¬А', r'!isMelee\(%s\) \|\| hasRanged\(\1\)' % A, {'Facts.kt'}, '!meleeOnlyBorn(x)'),
    ('чистый мили Б/В', r'hasMelee\(%s\) && !hasRanged\(\1\)' % A, {'Facts.kt'}, 'meleeOnlyLive(x)'),
    # этап 1, роли: лекарь, раздетый (у тактика звался wounded, у командира stripped), в строю
    ('лекарь', r'!(PainAndGain\.)?hasWeapon\(%s\) && (PainAndGain\.)?hasHeal\(\2\)' % A, {'Facts.kt'}, 'healerOnly(x)'),
    ('лекарь', r'hasHeal\(%s\) && !hasWeapon\(\1\)' % A, {'Facts.kt'}, 'healerOnly(x)'),
    ('не лекарь', r'hasWeapon\(%s\) \|\| !hasHeal\(\1\)|!hasHeal\(%s\) \|\| hasWeapon\(\2\)' % (A, A), {'Facts.kt'}, '!healerOnly(x)'),
    ('раздетый', r'!hasWeapon\(%s\) && !hasHeal\(\1\)' % A, {'Facts.kt'}, 'stripped(x)'),
    ('в строю', r'hasWeapon\(%s\) \|\| hasHeal\(\1\)' % A, {'Facts.kt'}, 'combatant(x)'),
    ('стрелок', r'hasWeapon\(%s\) && hasRanged\(\1\)|!hasRanged\(%s\) \|\| !hasWeapon\(\2\)' % (A, A), {'Facts.kt'},
     'hasRanged(x): живая RANGED_ATTACK уже значит «вооружён»'),
    # ролевой тест сканом тела: определение факта одно, в Unit. InfluenceMap — объект уровня 1, таблицы фактов тика не
    # видит (она у оркестратора), его `armed` остаётся сканом до этапа 5
    # этап 1, ключ клетки: 184 написания `x * 100 + y` инлайном в десяти файлах
    ('ключ клетки', r'\* 100 \+', {'Facts.kt'}, 'key(x, y) или pos.key — inline, скомпилированный код тот же'),
    # этап 1, группа постур «отход»: две полярности одного множества
    ('отход', r'Posture\.RETREAT (\|\||&&) \w+ [!=]= Posture\.EVADE', {}, 'posture.withdrawing / !posture.withdrawing'),
    ('скан тела', r'\.body\.(any|all|none)\b', {'Facts.kt', 'InfluenceMap.kt'}, 'факт Unit: bornMelee / bornArmed / bornCombatant / live*'),
]

SELECTION = re.compile(r'(?<![\w.])((?:\w+\.)*\w+)\.(filter|filterNot)\s*\{([^{}]*)\}')


def repeated_selection(files):
    """Этап 1, выборки: `список.filter { … }`, выписанная ДОСЛОВНО второй раз где угодно в пакете. Список тика с неизменным
    за тик предикатом — поле `Ctx` (side, threats, armedArmy…); свой список или предикат по памяти, меняющейся посреди
    тика, — функция-выборка в World.kt (living, armedOf, notDetached…): определение одно, точка вычисления прежняя."""
    seen, out = {}, []
    for f, rows in files.items():
        for n, code in rows:
            for m in SELECTION.finditer(code):
                k = re.sub(r'\s+', ' ', m.group(0))
                if k in seen:
                    out.append((f, n, '%s — дословно та же выборка, что в %s:%d' % ((k,) + seen[k])))
                else:
                    seen[k] = (f, n)
    return out


# Проверки, которым мало одной строки: функция (исходники: {файл: [(номер, код)]}) -> [(файл, номер, текст)]
CHECKS = [
    repeated_selection,
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
