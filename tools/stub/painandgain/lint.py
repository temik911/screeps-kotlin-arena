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
Q = r'(?:PainAndGain\.)?'  # тест, позванный через синглтон из `object` (так три написания пережили инвентарь этапа 1 в Formation.kt)
RULES = [
    # этап 1, «чистый мили»: два разных факта — А (рождён мили) и Б/В (живая ATTACK); не сводить, см. Facts.kt
    ('чистый мили А', Q + r'isMelee\(%s\) && !' % A + Q + r'hasRanged\(\1\)', {'Facts.kt'}, 'meleeOnlyBorn(x)'),
    ('чистый мили ¬А', '!' + Q + r'isMelee\(%s\) \|\| ' % A + Q + r'hasRanged\(\1\)', {'Facts.kt'}, '!meleeOnlyBorn(x)'),
    ('чистый мили Б/В', Q + r'hasMelee\(%s\) && !' % A + Q + r'hasRanged\(\1\)', {'Facts.kt'}, 'meleeOnlyLive(x)'),
    # этап 1, роли: лекарь, раздетый (у тактика звался wounded, у командира stripped), в строю
    ('лекарь', '!' + Q + r'hasWeapon\(%s\) && ' % A + Q + r'hasHeal\(\1\)', {'Facts.kt'}, 'healerOnly(x)'),
    ('лекарь', Q + r'hasHeal\(%s\) && !' % A + Q + r'hasWeapon\(\1\)', {'Facts.kt'}, 'healerOnly(x)'),
    ('не лекарь', Q + r'hasWeapon\(%s\) \|\| !' % A + Q + r'hasHeal\(\1\)|!' + Q + r'hasHeal\(%s\) \|\| ' % A + Q + r'hasWeapon\(\2\)', {'Facts.kt'}, '!healerOnly(x)'),
    ('раздетый', '!' + Q + r'hasWeapon\(%s\) && !' % A + Q + r'hasHeal\(\1\)', {'Facts.kt'}, 'stripped(x)'),
    ('в строю', Q + r'hasWeapon\(%s\) \|\| ' % A + Q + r'hasHeal\(\1\)', {'Facts.kt'}, 'combatant(x)'),
    ('стрелок', Q + r'hasWeapon\(%s\) && ' % A + Q + r'hasRanged\(\1\)|!' + Q + r'hasRanged\(%s\) \|\| !' % A + Q + r'hasWeapon\(\2\)', {'Facts.kt'},
     'hasRanged(x): живая RANGED_ATTACK уже значит «вооружён»'),
    # этап 1, ключ клетки: 184 написания `x * 100 + y` инлайном в десяти файлах
    ('ключ клетки', r'\* 100 \+', {'Facts.kt'}, 'key(x, y) или pos.key — inline, скомпилированный код тот же'),
    # этап 1, группа постур «отход»: две полярности одного множества
    ('отход', r'Posture\.RETREAT (\|\||&&) \w+ [!=]= Posture\.EVADE', {}, 'posture.withdrawing / !posture.withdrawing'),
    # этап 2, защёлка руками: гистерезис идёт через Latch (Memory.kt) — множество остаётся полем Memory, Latch — вид
    ('защёлка руками', r'if \(.*\) Memory\.\w+\.add\(.*\) else Memory\.\w+\.remove\(', {'Memory.kt'},
     'Memory.<имя>Latch.set(id, условие) / .update(id, enter, exit) — оператором на том же месте'),
    ('скан тела', r'\.body\.(any|all|none)\b', {'Facts.kt', 'InfluenceMap.kt'}, 'факт CreepFacts: bornMelee / bornArmed / bornCombatant / live*'),
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


# Этап 2: в этих функциях запись в память и счётчик прибора — ОПЕРАТОРЫ, а не часть выражения `val … = …`. Условие,
# спрятавшее запись внутрь `val x = … run { … Memory.X.add(id) … }`, нельзя ни посчитать отдельно от записи, ни вычислить
# дважды (а полный обход таблиц этапа 3 вычисляет условия всех строк). Список пополняется этапами 3 и 4; v448 (пункт Б.3
# оператора) добавил scoreCell и updateKeepers — оставшиеся два места с тем же рисунком, названные находкой этапа 2.
PURE_INITIALIZERS = {'creepTurn', 'buildTurn', 'buildStride', 'freeStep', 'commandFight', 'scoreCell', 'updateKeepers'}
EFFECT = re.compile(r'(?<![+\w])(\w+)(?:\.\w+)*\+\+|\+\+\w|Memory\.\w+(\[[^\]]*\]\s*=(?!=)|\.(add|remove|clear|put|addAll|retainAll|removeAll|getOrPut)\b)')
DECL = re.compile(r'^\s*(?:private |internal )?va[lr] [\w<>?:, ()]+?=(?!=)')
FUN = re.compile(r'\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)\s*\(')


def effect_in_initializer(files):
    """Этап 2: `++` прибора или запись в Memory внутри выражения `val … = …` в функциях PURE_INITIALIZERS. Счётчик, объявленный
    `var` внутри того же выражения, — локальная переменная алгоритма, а не прибор, и не считается."""
    out = []
    for f, rows in files.items():
        stack, scope = [], None                  # открытые скобки: ('decl'|'fun'|'blk', строка, локальные var) ; scope — имя функции
        depth_of_scope = None
        for n, code in rows:
            m = FUN.search(code)
            if m and scope is None and m.group(1) in PURE_INITIALIZERS:
                scope, depth_of_scope = m.group(1), len(stack)
            decl_line = bool(DECL.match(code))
            if scope is not None:
                decls = [x for x in stack if x[0] == 'decl']
                for x in decls:
                    x[2].update(re.findall(r'\bvar (\w+)', code))
                hit = None
                for e in EFFECT.finditer(code if decls else (code.split('=', 1)[1] if decl_line else '')):
                    name = e.group(1)
                    if name and any(name in x[2] for x in decls):
                        continue
                    hit = e
                if hit:
                    out.append((f, n, '%s: запись или счётчик внутри выражения `val … =` — %s' % (scope, code.strip()[:110])))
            for i, ch in enumerate(code):
                if ch == '{':
                    head = code[:i]
                    kind = 'fun' if re.search(r'\bfun\b[^{]*$', head) else 'decl' if decl_line and '=' in head else 'blk'
                    stack.append([kind, n, set()])
                elif ch == '}' and stack:
                    stack.pop()
                    if scope is not None and len(stack) <= depth_of_scope:
                        scope = None
        # (локальная `fun` внутри объявления остаётся «внутри выражения» намеренно: в этих функциях таких нет)
    return out


# Этап 3: классы фактов, которые читают строки таблиц решений. Строка таблицы — лямбда с получателем такого класса, внутри
# `with(PainAndGain)`; величины тика она берёт через `t.`. Поле, совпавшее по имени с полем другого класса фактов, с полем
# ArmyTick / Ctx или с членом объекта PainAndGain, МОЛЧА меняет смысл условия: компилятор возьмёт ближайшего получателя.
# Группы получателей, которые бывают в области видимости ОДНОЙ строки таблицы одновременно (последним — объект PainAndGain)
FACT_SCOPES = [
    ['Turn', 'Stride', 'ArmyTick', 'Ctx', 'PainAndGain'],      # лестница цели и цепочка шага (Tactician.kt)
    ['CaptureCase', 'PainAndGain'],                            # ворота захвата (Strategist.kt)
    ['PushCase', 'PainAndGain'],                               # решение о наступлении (Strategist.kt)
]


def _ctor_fields(files, cls):
    for f, rows in files.items():
        text = '\n'.join(code for _, code in rows)
        m = re.search(r'\bclass %s\((.*?)\n\)' % cls, text, re.S)
        if m:
            body = text[m.end():].split('\n}\n')[0] if text[m.end():].lstrip().startswith('{') else ''
            return f, set(re.findall(r'\bva[lr] (\w+)\s*:', m.group(1))) | set(re.findall(r'\n    va[lr] (\w+)\b', body))
    return None, set()


def _object_members(files, obj):
    out = set()
    for f, rows in files.items():
        depth, inside, base = 0, False, 0
        for _, code in rows:
            if re.search(r'\bobject %s\b' % obj, code):
                inside, base = True, depth
            if inside and depth == base + 1:
                m = re.match(r'\s*(?:internal |private |override |lateinit )*(?:val|var|fun) (?:[\w.<>?, ]+\.)?(\w+)', code)
                if m:
                    out.add(m.group(1))
            depth += code.count('{') - code.count('}')
            if inside and depth <= base and '}' in code:
                inside = False
    return out


def shadowed_fact_names(files):
    """Этап 3: множества имён классов фактов и членов PainAndGain попарно не пересекаются (кроме `creep`, `ctx`, `t` — это
    один и тот же объект, откуда ни читай; `army`, `combatEnemies`, `enemyCreeps` у ArmyTick и Ctx — один и тот же список)."""
    same = {'creep', 'ctx', 't', 'f', 'army', 'combatEnemies', 'enemyCreeps'}
    members = _object_members(files, 'PainAndGain')
    out = []
    for scope in FACT_SCOPES:
        sets, where = {}, {}
        for cls in scope:
            if cls == 'PainAndGain':
                sets[cls], where[cls] = members, 'PainAndGain.kt'
            else:
                where[cls], sets[cls] = _ctor_fields(files, cls)
        for i, a in enumerate(scope):
            for b in scope[i + 1:]:
                for n in sorted((sets[a] & sets[b]) - same):
                    out.append((where.get(a) or 'PainAndGain.kt', 0, 'имя `%s` есть и у %s, и у %s — строка таблицы прочтёт ближайшего получателя' % (n, a, b)))
    return out


# Проверки, которым мало одной строки: функция (исходники: {файл: [(номер, код)]}) -> [(файл, номер, текст)]
CHECKS = [
    shadowed_fact_names,
    repeated_selection,
    effect_in_initializer,
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
