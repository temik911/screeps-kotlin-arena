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

STRING = re.compile(r'"(\\.|[^"\\])*"')


def code_of_strings(code):
    """Строка кода, где от строковых литералов оставлен только КОД их шаблонов: `"a=${x.y} b=$z"` -> `"" x.y  z`. Текст литерала
    — не код (`posture=` в строке лога — не запись), а выражение шаблона — код: член объекта, прочитанный только внутри
    `"${…}"`, всё равно требует приёмника (так `declareLine` со 176 шаблонами выглядела не нуждающейся в нём)."""
    out, i, n = [], 0, len(code)
    while i < n:
        if code[i] != '"':
            out.append(code[i]); i += 1; continue
        out.append('""'); i += 1
        while i < n and code[i] != '"':
            if code[i] == '\\': i += 2; continue
            if code.startswith('${', i):
                d, j = 1, i + 2
                while j < n and d:
                    if code[j] == '"':                  # вложенный литерал: рекурсивно
                        k = j + 1
                        dd = 0
                        while k < n and (code[k] != '"' or dd):
                            if code[k] == '\\': k += 1
                            elif code.startswith('${', k): dd += 1; k += 1
                            elif code[k] == '}' and dd: dd -= 1
                            k += 1
                        out.append(' ' + code_of_strings(code[j:k + 1]) + ' '); j = k + 1; continue
                    d += (code[j] == '{') - (code[j] == '}')
                    if d: out.append(code[j])
                    j += 1
                out.append(' '); i = j; continue
            m = re.match(r'\$([A-Za-z_]\w*)', code[i:])
            if m:
                out.append(' ' + m.group(1) + ' '); i += len(m.group(0)); continue
            i += 1
        i += 1
    return ''.join(out)
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
# v456 (второй шаг архитектуры, этап 3): построители `buildStride` / `buildTurn` становятся ТЕЛАМИ КЛАССОВ-носителей — поле объявлено
# там, где вычислено; запись или счётчик между фактами стоит блоком `init { … }` на прежнем месте последовательности, а не внутри
# выражения `val … =`. Имя класса-носителя в этом списке проверяется так же, как имя функции.
PURE_INITIALIZERS = {'creepTurn', 'Turn', 'Stride', 'freeStep', 'commandFight', 'scoreCell', 'updateKeepers'}
EFFECT = re.compile(r'(?<![+\w])(\w+)(?:\.\w+)*\+\+|\+\+\w|Memory\.\w+(\[[^\]]*\]\s*=(?!=)|\.(add|remove|clear|put|addAll|retainAll|removeAll|getOrPut)\b)')
DECL = re.compile(r'^\s*(?:private |internal )?va[lr] [\w<>?:, ()]+?=(?!=)')
FUN = re.compile(r'\b(?:fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?|class\s+)(\w+)\s*\(')


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
                    kind = 'fun' if re.search(r'\b(?:fun|class)\b[^{]*$', head) else 'decl' if decl_line and '=' in head else 'blk'
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
    """Поля класса: `val` / `var` заголовка и объявления первого уровня тела. Скобки считаются, а не угадываются по переводу
    строки: до 20.09.2026 выражение ждало заголовок, закрытый на отдельной строке (`\n)`), и у `CaptureCase` — заголовок в одну
    строку, поля тела без типов — видело 5 полей из 15; остальные десять от затенения не были защищены (план 2, п. 2.8.4)."""
    for f, rows in files.items():
        text = '\n'.join(code for _, code in rows)
        m = re.search(r'\bclass %s\b[^({\n]*\(' % cls, text)
        if not m:
            continue
        i, d = m.end(), 1
        while i < len(text) and d:
            d += (text[i] == '(') - (text[i] == ')')
            i += 1
        head = text[m.end():i - 1]
        fields = set(re.findall(r'\bva[lr] (\w+)\s*:', head))
        j = i
        while j < len(text) and text[j] in ' \t':
            j += 1
        if text[j:j + 1] == ':':                        # супертипы до тела
            j = text.find('{', j) if '{' in text[j:text.find('\n', j) + 1] else j
        if text[j:j + 1] == '{':
            k, d = j + 1, 1
            while k < len(text) and d:
                if d == 1:
                    # `private` поле носителя строка таблицы (лямбда с получателем, снаружи класса) не видит — затенить им нечего
                    mm = re.match(r'[ \t]*(?:internal |override |lateinit )*va[lr] (\w+)\b', text[k:]) if text[k - 1] == '\n' else None
                    if mm:
                        fields.add(mm.group(1))
                d += (text[k] == '{') - (text[k] == '}')
                k += 1
        return f, fields
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


# ====================================================================================================================
# ВТОРОЙ ШАГ АРХИТЕКТУРЫ (20.09.2026, docs/pain-and-gain-architecture-2.md, этап 0): правила «одного места». У каждого —
# СПИСОК ИЗВЕСТНЫХ НАРУШЕНИЙ (lint-known.txt), который может только УБЫВАТЬ, — та же механика, что у known-рёбер в
# levels.txt: нарушение, которого нет в списке, — FAIL (новое); строка списка, нарушения по которой больше нет, — FAIL
# (сними строку: список пустеет вместе с нарушениями); строка, которой нет в версии списка из `main`, — FAIL (список
# вырос). Нарушение записывается АТОМОМ — строкой `правило ключ`, устойчивой к сдвигу номеров строк.
# Проверка: функция (исходники) -> [(файл, номер строки, текст для человека, атом)].
# ====================================================================================================================
KNOWN = os.path.join(HERE, 'lint-known.txt')
ORDER = os.path.join(HERE, 'order.txt')


def _numbered(atoms):
    """Одинаковые ключи в одном файле различаются порядковым номером: `Tactician.kt:creep~2` (не `#` — это комментарий списка)."""
    seen, out = {}, []
    for f, n, text, key in atoms:
        seen[key] = seen.get(key, 0) + 1
        out.append((f, n, text, key if seen[key] == 1 else '%s~%d' % (key, seen[key])))
    return out


PLUMB = re.compile(r'(?<![\w.])(\w+) = \1(?=\s*(?:[,)]|$))')


def plumbing(files):
    """САНТЕХНИКА: аргумент `x = x` — имя поля, написанное третий раз (заголовок класса, локальная построителя, аргумент).
    Носитель, у которого поле объявлено там, где вычислено (план, 4.1), такого аргумента не имеет вовсе."""
    out = []
    for f, rows in files.items():
        for n, code in rows:
            if re.match(r'\s*(?:va[lr]\s|return\b)', code) and '(' not in code:
                continue
            for m in PLUMB.finditer(code):
                out.append((f, n, 'аргумент `%s = %s` — поле объявляется там, где вычислено' % (m.group(1), m.group(1)), '%s:%s' % (f, m.group(1))))
    return [(f, n, t, 'plumbing ' + k) for f, n, t, k in _numbered(out)]


def _fun_end(L, a):
    """Номер последней строки функции, объявленной в строке a (строки — код без комментариев и с пустыми литералами).
    Скобки считаются ВСЕ — `(`, `[`, `{`: у функции-выражения тело `= rows ?: listOf(\n Row(…) { … },\n …)` кончается там,
    где закрылась круглая скобка, а не первая фигурная (прототип из приложения В плана резал такую функцию после первой
    строки таблицы и объявлял `ladder()`, `captureGates()`, `pushRules()` не нуждающимися в приёмнике)."""
    depth, b, body_seen, last = 0, a, False, ''
    while b < len(L):
        line = L[b]
        for ch in line:
            if ch in '([{':
                depth += 1
            elif ch in ')]}':
                depth -= 1
        if '{' in line or re.search(r'\)\s*(?::[^=]+)?=', line) or body_seen:
            body_seen = True
        if line.strip():
            last = line.rstrip()                        # строка-комментарий посреди выражения вырезана в пустую: конец судим по последней НЕпустой
        if body_seen and depth <= 0 and line.strip():
            k = b + 1
            while k < len(L) and not L[k].strip():
                k += 1
            nxt = L[k].strip() if k < len(L) else ''
            if not (last.endswith(('=', '&&', '||', '+', '-', '?:', ',', '(')) or nxt.startswith(('.', '?.', '?:', '&&', '||', '+ ', '- '))):
                return b
        b += 1
    return len(L) - 1


def _extension_bodies(files):
    """{имя расширения PainAndGain: [(файл, строка, тело)]} — перегрузки вместе."""
    funs = {}
    for f, rows in files.items():
        L = [code_of_strings(code) for _, code in rows]
        for a, l in enumerate(L):
            mm = re.match(r'^\s*(?:internal |private )?(?:inline )?fun\s+(?:<[^>]*>\s*)?PainAndGain\.(\w+)\s*\(', l)
            if not mm:
                continue
            b = _fun_end(L, a)
            funs.setdefault(mm.group(1), []).append((f, rows[a][0], '\n'.join(L[a:b + 1])))
    return funs


def _needs_receiver(funs, members):
    info = {}
    for n, bodies in funs.items():
        um, ue = set(), set()
        for _, _, body in bodies:
            locs = set(re.findall(r'\bva[lr]\s+(\w+)', body))
            for w in re.finditer(r'(?<![\w.])([a-zA-Z_]\w*)\b', body):
                k = w.group(1)
                if k in locs:
                    continue
                if k in members:
                    um.add(k)
                elif k in funs and k != n and body[w.end():w.end() + 1] == '(':
                    ue.add(k)
            if re.search(r'(?<![\w.])this\b', body):
                um.add('this')
        info[n] = (um, ue)
    need = {n for n, (um, ue) in info.items() if um}
    while True:
        more = {n for n, (um, ue) in info.items() if n not in need and ue & need}
        if not more:
            return need
        need |= more


def needless_receiver(files):
    """ЛИШНИЙ ПРИЁМНИК: функция объявлена расширением `PainAndGain`, но ни сама, ни через вызываемых состояние объекта не
    трогает. Расширение — только там, где приёмник нужен (план, 4.2): иначе имя в её теле разрешается по четырём областям
    вместо двух, и по тексту не видно, какая сработала."""
    funs = _extension_bodies(files)
    members = _object_members(files, 'PainAndGain')
    need = _needs_receiver(funs, members)
    out = []
    for n in sorted(set(funs) - need):
        f, line, _ = funs[n][0]
        out.append((f, line, 'fun PainAndGain.%s — приёмник не нужен ни ей, ни тем, кого она зовёт: обычная функция' % n, 'receiver ' + n))
    return out


def table_tags(files):
    """{имя таблицы в приборе reach: [теги по порядку]} — из исходника: блок `… = … listOf…(` со строками Row / Gate / Pass;
    имя таблицы — имя её счётчика `Tally("имя")`, с которым её обходят (`walk(ladder(), …, ladderTally)`)."""
    tally, walks, blocks = {}, {}, {}
    for f, rows in files.items():
        cur, ind = None, 0
        for n, code in rows:
            for m in re.finditer(r'\b(\w+) = Tally\("([\w.]+)"', code):
                tally[m.group(1)] = m.group(2)
            for m in re.finditer(r'\b(?:walk|pass)\((\w+)(?:\(\))?, .*, ([\w.]+)\)', code):
                walks[m.group(1)] = m.group(2).split('.')[-1]
            for m in re.finditer(r'\brunPasses\((\w+), ([\w.]+)\)', code):
                walks[m.group(1)] = m.group(2).split('.')[-1]
            m = re.match(r'(\s*).*\b(?:val|fun) (?:PainAndGain\.)?(\w+)\b.*\blistOf(?:<[^(]*>)?\($', code)
            if m:
                cur, ind = m.group(2), len(m.group(1)); blocks[cur] = []
                continue
            if cur:
                mm = re.match(r'\s{%d}(?:Row|Gate|Pass)\("([^"]+)"' % (ind + 4), code)
                if mm:
                    blocks[cur].append((mm.group(1), f, n))
                elif re.match(r'\s{0,%d}\)' % ind, code):
                    if not blocks[cur]:
                        del blocks[cur]
                    cur = None
    out = {}
    for ident, rows in blocks.items():
        name = tally.get(walks.get(ident, ''), None)
        if name:
            out[name] = rows
    return out


def tag_outside_table(files):
    """ТЕГ ВНЕ ТАБЛИЦЫ: строковый литерал, совпадающий с тегом строки таблицы, в сравнении `==` / `!=` / `in`. Переименовал
    строку — сравнение молча перестало срабатывать; свойство строки (приоритет, признак «приказ») живёт в строке (план, 4.5)."""
    tags = {t for rows in table_tags(files).values() for t, _, _ in rows}
    out = []
    for f, rows in files.items():
        if f == 'Tables.kt':
            continue
        for n, code in rows:
            if re.match(r'\s*(?:Row|Gate|Pass)\("', code):
                continue
            hits = [m.group(1) or m.group(2) for m in re.finditer(r'[!=]= "([^"]+)"|"([^"]+)" [!=]=', code)]
            for m in re.finditer(r'\bin (?:setOf|listOf|arrayOf)\(([^)]*)\)', code):
                hits += re.findall(r'"([^"]+)"', m.group(1))
            for t in hits:
                if t in tags:
                    out.append((f, n, 'тег строки таблицы "%s" сравнивается как строка вне таблицы' % t, '%s:%s' % (f, t)))
    return [(f, n, t, 'tag ' + k) for f, n, t, k in _numbered(out)]


WRITE = r'\s*(?:=(?!=)|\+=|-=|\*=|/=|\+\+|--|\[[^\]]*\]\s*(?:=(?!=)|\+=|-=)|\.(?:add|addAll|addLast|addFirst|remove|removeAll|removeFirst|removeLast|retainAll|clear|put|putAll|getOrPut|set|update|fill)\b)'


def single_writer(files):
    """ОДИН ПИСАТЕЛЬ: поле `Memory` или член `PainAndGain` пишется более чем из одного файла. По тексту: запись — присваивание,
    `++`, запись по индексу, изменяющий метод коллекции; одноимённая локальная или параметр функции запись не считает."""
    owners = {o: _object_vars(files, o) for o in _state_owners(files)}
    # член, переехавший из объекта на ВЕРХ файла-владельца (этапы 1–2), уносит своё нарушение с собой: имена из списка известных,
    # которые сегодня объявлены на верху пакета, считаются полями условного владельца `top`
    moved = {a.split(' ', 1)[1].split('<-')[0] for a in (read_known(open(KNOWN, encoding='utf-8').read()) if os.path.exists(KNOWN) else []) if a.startswith('writer ')}
    tops = set()
    for rows in files.values():
        depth = 0
        for _, code in rows:
            if depth == 0:
                m = re.match(r'(?:internal |private )?va[lr] (\w+)\b', code)
                if m and m.group(1) in moved:
                    tops.add(m.group(1))
            depth += code.count('{') - code.count('}')
    owners['top'] = tops
    writers = {}
    for f, rows in files.items():
        text = [STRING.sub('""', code) for _, code in rows]     # `posture=` в тексте строки лога — не запись
        local = _local_names(text)
        for i, code in enumerate(text):
            for m in re.finditer(r'(?<![\w.])(?:(%s)\.)?(\w+)(?=%s)' % ('|'.join(sorted(o for o in owners if o != 'top')), WRITE), code):
                obj, name = m.group(1), m.group(2)
                if obj is None:
                    if name in local[i] or re.search(r'\bva[lr] %s\b' % name, code):
                        continue
                    if name in owners['PainAndGain']:
                        obj = 'PainAndGain'
                    elif name in owners['top']:
                        obj = 'top'
                    else:
                        continue
                elif name not in owners[obj]:
                    continue
                writers.setdefault('%s.%s' % (obj, name), {}).setdefault(f, rows[i][0])
    out = []
    for field in sorted(writers):
        if len(writers[field]) > 1:
            for f in sorted(writers[field]):
                # атом — по ИМЕНИ поля, без владельца: поле, переехавшее от одного владельца к другому (член PainAndGain ->
                # BodyMemo, Squads, Prev…), уносит своё нарушение с собой, а не теряет его вместе с прежним именем
                out.append((f, writers[field][f], '%s пишется из %d файлов: %s' % (field, len(writers[field]), ', '.join(sorted(writers[field]))),
                            'writer %s<-%s' % (field.split('.', 1)[1], f)))
    return out


def _state_owners(files):
    """Объекты общего состояния: `Memory`, `PainAndGain` и владельцы, заведённые вторым шагом архитектуры, — объекты из списка
    починки `repairAfterAbort`, объявленные в файле СТАДИИ (службы `InfluenceMap`, `DistanceMap`, `TrafficManager`, `Executor`,
    `Forecast` — приёмники, в которые по построению пишут многие; у них своё правило — уровни)."""
    services = {'InfluenceMap', 'DistanceMap', 'TrafficManager', 'Executor', 'Forecast', 'Arbiter', 'AbortRepair'}
    out = {'Memory', 'PainAndGain'}
    for rows in files.values():
        for _, code in rows:
            m = re.search(r'for \(owner in listOf<Any>\(([^)]*)\)\)', code)
            if m:
                out |= {x.strip() for x in m.group(1).split(',') if x.strip() not in ('this',)} - services
    return out


def _object_vars(files, obj):
    out = set()
    for f, rows in files.items():
        depth, inside, base = 0, False, 0
        for _, code in rows:
            if re.search(r'\bobject %s\b' % obj, code):
                inside, base = True, depth
            if inside and depth == base + 1:
                for m in re.finditer(r'(?:^|;)\s*(?:internal |private |override |lateinit )*(?:val|var) (\w+)', code):
                    out.add(m.group(1))
            depth += code.count('{') - code.count('}')
            if inside and depth <= base and '}' in code:
                inside = False
    return out


def _local_names(text):
    """По строке — имена, объявленные локально (`val` / `var` / параметр) в функции верхнего уровня, которой строка принадлежит."""
    out, cur, depth, fun_depth = [], set(), 0, None
    for code in text:
        if fun_depth is None and re.match(r'^\s{0,4}(?:internal |private |override |inline )*fun\b', code):
            fun_depth, cur = depth, set(re.findall(r'(\w+)\s*:', code.split(')')[0] if ')' in code else code))
        if fun_depth is not None:
            cur |= set(re.findall(r'\bva[lr]\s+(\w+)', code))
            cur |= set(n for grp in re.findall(r'\bva[lr]\s+\(([^)]*)\)', code) for n in re.findall(r'\w+', grp))
        out.append(set(cur) if fun_depth is not None else set())
        depth += code.count('{') - code.count('}')
        if fun_depth is not None and depth <= fun_depth and ('}' in code or '{' not in code and '=' in code):
            fun_depth, cur = None, set()
    return out


def table_order(files):
    """ПОРЯДОК: `order.txt` — записи вида `rung: kite > slot   # v135: почему`. Порядок строк таблицы — приоритет, и причина,
    по которой одна строка стоит выше другой, до сих пор жила комментарием между строками: его не проверяет ничто. Запись
    проверяется: обе строки в таблице есть, первая стоит ВЫШЕ второй. Известных нарушений у правила нет — оно обязано быть
    пустым."""
    if not os.path.exists(ORDER):
        return [('order.txt', 0, 'нет файла order.txt', 'order missing')]
    tables = table_tags(files)
    out = []
    for n, raw in enumerate(open(ORDER, encoding='utf-8').read().split('\n'), 1):
        line = raw.split('#')[0].strip()
        if not line:
            continue
        m = re.match(r'([\w.]+):\s*(\S+)\s*>\s*(\S+)$', line)
        if not m:
            out.append(('order.txt', n, 'не разобрать: %s' % raw.strip(), 'order syntax:%d' % n)); continue
        if '#' not in raw or not raw.split('#', 1)[1].strip():
            out.append(('order.txt', n, 'запись без причины: %s' % raw.strip(), 'order reason:%d' % n)); continue
        tbl, hi, lo = m.groups()
        tags = [t for t, _, _ in tables.get(tbl, [])]
        if not tags:
            out.append(('order.txt', n, 'таблицы `%s` в пакете нет (есть: %s)' % (tbl, ', '.join(sorted(tables))), 'order table:%s' % tbl)); continue
        for t in (hi, lo):
            if t not in tags:
                out.append(('order.txt', n, 'в таблице `%s` нет строки `%s` — строка переименована или снята, а причина её места осталась' % (tbl, t), 'order row:%s.%s' % (tbl, t)))
        if hi in tags and lo in tags and tags.index(hi) > tags.index(lo):
            where = [(f, ln) for t, f, ln in tables[tbl] if t == hi][0]
            out.append((where[0], where[1], '%s: `%s` обязана стоять ВЫШЕ `%s` — %s' % (tbl, hi, lo, raw.split('#', 1)[1].strip()), 'order %s:%s>%s' % (tbl, hi, lo)))
    return out


def member_vs_toplevel(files):
    """МОЛЧАЛИВОЕ ПЕРЕРАЗРЕШЕНИЕ: имя члена `object PainAndGain` совпало с именем верхнего уровня пакета. В функции-расширении
    голое имя найдёт ЧЛЕН, в обычной функции и в носителе — ВЕРХ ПАКЕТА, и компилятор не скажет ничего. Пока члены переезжают
    из объекта на верх файлов-владельцев (этапы 1–6 второго шага), пересечение обязано оставаться пустым."""
    members = _object_members(files, 'PainAndGain')
    out = []
    for f, rows in files.items():
        for n, code in rows:
            m = re.match(r'(?:internal |private |const |inline |lateinit )*(?:val|var|fun)\s+(?:<[^>]*>\s*)?(\w+)\b(?!\.)', code)
            if m and m.group(1) in members:
                out.append((f, n, 'имя `%s` есть и у object PainAndGain, и на верхнем уровне пакета' % m.group(1), 'collision ' + m.group(1)))
    return out


# Носители (v456, второй шаг архитектуры, этап 3): класс, чьё тело — прежний построитель. Список пополняется с каждым новым носителем.
CARRIERS = ['Turn', 'Stride']


def carrier_method_order(files):
    """МЕТОД НОСИТЕЛЯ НЕ ЧИТАЕТ ПОЛЕ, ОБЪЯВЛЕННОЕ НИЖЕ НЕГО. В функции-построителе компилятор запрещал обращение к локальной до её
    объявления; в классе метод может прочитать ещё не инициализированное поле и молча получить `null` / `0` / `false` — если его
    позовёт инициализатор поля, стоящего между ними. Перенос с сохранением порядка текста безопасен; правило держит этот порядок."""
    out = []
    for f, rows in files.items():
        text = [code_of_strings(code) for _, code in rows]
        for cls in CARRIERS:
            start = next((i for i, c in enumerate(text) if re.match(r'\s*(?:internal |private )?class %s\b' % cls, c)), None)
            if start is None:
                continue
            depth, i, fields, methods = 0, start, [], []
            while i < len(text):
                c = text[i]
                if depth == 1:
                    m = re.match(r'\s*(?:private |internal |override )*va[lr] (\w+)\b', c)
                    if m:
                        fields.append((m.group(1), i))
                    m = re.match(r'\s*(?:private |internal |override )*fun (\w+)\b', c)
                    if m:
                        methods.append([m.group(1), i, _fun_end(text, i)])
                depth += c.count('{') - c.count('}')
                i += 1
                if depth <= 0 and i > start + 1:
                    break
            for name, a, b in methods:
                body = '\n'.join(text[a:b + 1])
                for fld, line in fields:
                    if line > a and re.search(r'(?<![\w.])%s\b' % fld, body):
                        out.append((f, rows[a][0], 'носитель %s: метод `%s` читает поле `%s`, объявленное ниже него (строка %d)' % (cls, name, fld, rows[line][0]),
                                    'order-in-carrier %s.%s>%s' % (cls, name, fld)))
    return out


# (проверка, есть ли у неё список известных нарушений)
KNOWN_CHECKS = [(plumbing, True), (needless_receiver, True), (tag_outside_table, True), (single_writer, True), (table_order, False),
                (member_vs_toplevel, False), (carrier_method_order, False)]


def read_known(text):
    rows = [l.split('#')[0].strip() for l in text.split('\n') if l.split('#')[0].strip()]
    # до v454 атом правила одного писателя нёс владельца (`writer PainAndGain.x<-F.kt`) — читается как нынешний
    return [re.sub(r'^writer \w+\.(\w+<-)', r'writer \1', r) for r in rows]


def main_known():
    """Список известных из `main` — чтобы он мог только убывать. None, если сверять не с чем (файла в main ещё нет)."""
    import subprocess
    try:
        top = subprocess.run(['git', 'rev-parse', '--show-toplevel'], capture_output=True, text=True, cwd=HERE).stdout.strip()
        rel = os.path.relpath(KNOWN, top)
        r = subprocess.run(['git', 'show', 'main:' + rel], capture_output=True, text=True, cwd=top)
        return read_known(r.stdout) if r.returncode == 0 else None
    except OSError:
        return None


def known_checks(files, write=False):
    """-> ([(файл, строка, правило, текст)] — то, что валит линт; {правило: (нарушений, известных)})."""
    found, stat = [], {}
    for check, has_known in KNOWN_CHECKS:
        found.append((check.__name__, has_known, check(files)))
    atoms_now = [a for _, has_known, hits in found if has_known for _, _, _, a in hits]
    if write:
        rules = sorted({a.split(' ', 1)[0] for a in atoms_now})
        with open(KNOWN, 'w', encoding='utf-8') as fh:
            fh.write('# Известные нарушения правил «одного места» (lint.py, второй шаг архитектуры). Список может только УБЫВАТЬ:\n'
                     '# новое нарушение — FAIL, строка без нарушения — FAIL (сними её), строка, которой нет в версии из main, — FAIL.\n'
                     '# Пишется `python3 tools/stub/painandgain/lint.py --write-known`; строка — `правило ключ`.\n')
            for r in rules:
                fh.write('\n'.join(sorted(a for a in atoms_now if a.split(' ', 1)[0] == r)) + '\n')
    known = read_known(open(KNOWN, encoding='utf-8').read()) if os.path.exists(KNOWN) else []
    base = main_known()
    bad = []
    for name, has_known, hits in found:
        new = [h for h in hits if not has_known or h[3] not in known]
        stat[name] = (len(hits), len(hits) - len(new))
        for f, n, text, atom in new:
            bad.append((f, n, name, ('НОВОЕ: ' if has_known else '') + text))
    now = set(atoms_now)
    for k in known:
        if k not in now:
            bad.append(('lint-known.txt', 0, 'known', '`%s` — нарушения больше нет: сними строку (список пустеет вместе с нарушениями)' % k))
        elif base is not None and k not in base:
            bad.append(('lint-known.txt', 0, 'known', '`%s` — строки нет в версии списка из main: список может только убывать' % k))
    return bad, stat


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
    ap.add_argument('--write-known', action='store_true', help='переписать lint-known.txt нынешними нарушениями (расти списку не даст сверка с main)')
    ap.add_argument('--known', action='store_true', help='показать и известные нарушения правил «одного места» — список работы этапов 1–6')
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
    bad, stat = known_checks(files, write=a.write_known)
    for f, n, name, text in bad:
        hits.append((f, n, name, '', text))
    if a.known:
        for check, has_known in KNOWN_CHECKS:
            for f, n, text, atom in check(files):
                print('%s:%d: [известное: %s] %s' % (f, n, check.__name__, text))
    out = sys.stderr if a.gate else sys.stdout
    for f, n, name, instead, code in hits:
        print('%s:%d: [%s] %s%s' % (f, n, name, code[:140], ('   -> ' + instead) if instead else ''), file=out)
    n_rules = len(RULES) + len(CHECKS) + len(KNOWN_CHECKS)
    n_known = sum(k for _, k in stat.values())
    if a.gate:
        what = 'rules %d files %d hits %d known %d' % (n_rules, len(files), len(hits), n_known)
        print('%-4s %-22s %-40s | errors: %d ' % ('FAIL' if hits else 'PASS', 'lint', what, len(hits)))
    else:
        print('правил %d, файлов %d, нарушений %d; известных нарушений «одного места» %d: %s' % (
            n_rules, len(files), len(hits), n_known, ', '.join('%s %d' % (k, v[1]) for k, v in stat.items())))
    return 1 if hits else 0


if __name__ == '__main__':
    sys.exit(main())
