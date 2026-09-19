# Pain and Gain — карты секций трёх функций (этап 4 плана архитектуры)

Снято 19.09.2026 с коммита `1f64512` (v444) — **до первой правки** этих функций, как требует
`docs/pain-and-gain-architecture.md`, этап 4: «карта секций функции (диапазон строк → что решает → какие локальные читает и
пишет → какие счётчики и память трогает) кладётся в `docs/` до первой правки». Номера строк — якоря того коммита; на живом
дереве место ищется по имени. Карты составили три субагента-картографа (только чтение), по одному на функцию; по ним код
раскладывался на носители и таблицы, и они же — перечень ловушек, которые перенос обязан был обойти. После раскладки карты не
переписываются: что сделано — в абзацах версий `docs/pain-and-gain.md`.

Содержание: [`captureBlock`](#карта-секций-captureblock-ворота-захвата-флага) · `commandFight` · `armyStrategy`.

# Карта секций `captureBlock` (ворота захвата флага)

Файл: `starter/src/jsMain/kotlin/season4/painandgain/Strategist.kt`

Точные границы:

| Что | Строки |
|---|---|
| `captureAllowed` | 238 (однострочная) |
| `captureBlock` | 243 (сигнатура) … 469 (закрывающая `}`); тело 244–468 |
| `capCount` | 472–476 |
| `lostRaceNow` (зовётся из тела, строка 428) | 480–505 |
| `powerAfterFor` (зовётся из тела, строка 415) | 518–539 |

---

## 1. Сигнатура, вызовы, смысл возврата

```kotlin
internal fun PainAndGain.captureAllowed(ctx: Ctx, f: FlagInfo): Boolean = captureBlock(ctx, f) == null   // 238
internal fun PainAndGain.captureBlock(ctx: Ctx, f: FlagInfo): String?                                    // 243
```

* Получатель — `PainAndGain` (все счётчики и признаки читаются как поля получателя либо как top-level `internal var`).
* Возврат: `null` — «захват разрешён»; непустая строка — причина запрета (`"seventh"`, `"rush.unflagged"`, `"rush.approach"`, `"contact.mass"`, `"first.fight"`, `"enough"`, `"parity(<ours>/<порог>)"`).
* Параметра `runner` нет (снят в v216, см. KDoc 235–237).

### Кто зовёт (весь пакет `season4/painandgain`)

| Место | Функция | Частота за тик | Назначение |
|---|---|---|---|
| `Missions.kt:168` | `runRunners` (79) | до `runners × ctx.flags` (7 флагов), пропускается при `cpuGuard` | **не ворота, а оценка**: множитель `0.2` к ценности кандидата |
| `Missions.kt:288` | `runRunners` (79) | 1 на бегуна с назначенным флагом | настоящие ворота: `range = if (allowed) 0 else 1` |
| `Strategist.kt:560` | `chooseFlagObjective` (553) | `ctx.flags` на каждый вызов; сам `chooseFlagObjective` зовётся из 991 (`commandGoal`) и 2365 (`armyStrategy`) | настоящие ворота (`objDrop["gate"]`) |
| `Strategist.kt:992` | `commandGoal` (985) | **0 — мёртвый код**: строка 991 — `return chooseFlagObjective(...)`, до 992 управление не доходит | — |
| `Strategist.kt:1192` | `commandRace` (1104) | `flags` | отбор флагов для пар/отряда |
| `Strategist.kt:2360` | `armyStrategy` (1911) | 0–1 | `interceptFlag?.takeIf { ... }` |
| `Tactician.kt:107` | `submit` (92) | 1 на крип, чей шаг попадает на не наш флаг | ворота шага, `strayCapRefused++` |
| `Tactician.kt:1689` | `armyTargets` (1398) | `ctx.flags` | отбор `grabberOf` |

**Важно для переноса:** функция зовётся десятки раз за тик, «по-настоящему» и «для оценки» неразличимо, и **счётчики внутри растут на каждый вызов**, а не раз за тик. Два разных дедупликатора:

* `capOffered` (246) — растёт на каждом вызове, **пока этот флаг в этом тике ещё не записан** в `capSeen`;
* `capBlocked[why]` — растёт **один раз на пару (тик × флаг)**, через `capCount`.

Все прочие счётчики (`kvetoAll`, `kvetoHit`, `warmCap`, `warmCapAll`, `capOppSum`, `capAllSum`, `lostRaceOffers`, `lostRaceOpened`, `majOffers`, `majOpened`) **не дедуплицированы вовсе** — растут на каждый вызов, дошедший до своей строки.

### `capCount` (472–476)

```kotlin
internal fun PainAndGain.capCount(f: FlagInfo, why: String): String {
    if (capTick != getTicks()) { capTick = getTicks(); capSeen.clear() }
    if (capSeen.add(f.id)) { capBlocked[why] = (capBlocked[why] ?: 0) + 1 }
    return why
}
```

* Возвращает `why` как есть — то есть **это не чистая функция**: `capSeen.add` меняет состояние.
* Первый `capCount` на пару (тик × флаг) выигрывает: **все последующие в том же тике для того же флага ничего не считают** и молча возвращают строку.
* `capTick` — top-level `internal var capTick = -1` (`Strategist.kt:2778`); `capSeen`/`capBlocked` — поля `PainAndGain` (`PainAndGain.kt:136–137`).

---

## 2. Таблица всех точек выхода (в порядке следования) — будущая таблица ворот

Колонка «записи ДО» — то, что исполняется между предыдущим выходом и этим; порядок внутри клетки обязателен.

| # | Строка | Условие выхода (дословно) | Возврат | Читает | Счётчики/записи МЕЖДУ предыдущим выходом и этим (дословно) |
|---|---|---|---|---|---|
| 1 | 244 | `if (f.ours)` | `null` | `f.ours` | — (первая строка тела) |
| 2 | 247 | `if (ctx.combatEnemies.isEmpty())` | `null` | `ctx.combatEnemies` | `245: if (capTick != getTicks()) { capTick = getTicks(); capSeen.clear() }`<br>`246: if (f.id !in capSeen) capOffered++` |
| 3 | 249 | `if (ctx.flags.count { it.ours } + 1 >= ctx.flags.size)` | `"seventh"` — **строкой, БЕЗ `capCount`** | `ctx.flags` | — |
| 4 | 253 | `if ((behindOnScore \|\| losingAtTheEnd) && ticksLeft <= LAST_CALL_TICKS)` | `null` | `behindOnScore`, `losingAtTheEnd`(252), `ticksLeft`(251) | `251: val ticksLeft = arenaInfo.ticksLimit - getTicks()`<br>`252: val losingAtTheEnd = (ourScore - enemyScore) + (ourRate - enemyRate) * ticksLeft <= 0` (объявления, не записи) |
| 5 | 290→294 | `if (rushNow && !rushStale && !intercept && !(stalledNow))` | `capCount(f, if (unflaggedRushNow) "rush.unflagged" else "rush.approach")` | `rushNow`(289), `rushStale`(279), `intercept`(260), `stalledNow`, `unflaggedRushNow` | `260/279/289` — объявления; **внутри блока до `return`:**<br>`293: if (unflaggedRushNow) { kvetoAll++; if (kiteChaseSeen) kvetoHit++ }` |
| 6 | 332→336 | `if (!losingRace && !stalledNow && !intercept && contactArmy.any { fullSpeed(it) && hasWeapon(it) } && inContact(foes, contactArmy))` | `capCount(f, "contact.mass")` | `losingRace`(316), `stalledNow`, `intercept`, `contactArmy`(331), `foes`(329) | `296: if (fightImminentNow && rushStale) capCount(f, "rush.approach.expired")` ⚠️ **пишет `capSeen`, хотя выхода нет**<br>`316–318: val losingRace = …` (дорого)<br>`329–331` — объявления;<br>**внутри блока до `return`:** `335: warmCapAll++; if (!exchangeLiveNow) warmCap++` |
| 7 | 372→375 | `if (firstFightAhead && !behindOnScore && !stalledNow && !intercept) { … if (ourAfter > hisAfter) }` | `capCount(f, "first.fight")` | `firstFightAhead`(370–371), `behindOnScore`, `stalledNow`, `intercept`, `ourAfter`(373), `hisAfter`(374), `f.theirs` | `339–340: if (!losingRace && !stalledNow && !intercept && ctx.army.any { fullSpeed(it) && hasWeapon(it) } && inContact(foes, ctx.army))` → `capCount(f, "contact.edge.lifted")` ⚠️ **пишет `capSeen`, выхода нет**<br>`370–371` — объявление |
| 8 | 387 | `if (raceWon && enemyMassedSignal && !ctx.passiveEnemy && !stalledNow && !intercept)` | `capCount(f, "enough")` | `raceWon`(386), `enemyMassedSignal`, `ctx.passiveEnemy`, `stalledNow`, `intercept` | `386: val raceWon = …` (объявление) |
| 9 | 439 | `if (ours >= theirs * floor)` | `null` | `ours`/`theirs`(415–417), `floor`(429) | `401: val oppLocal = …`, `411: val foughtFoe = …`, `412: val opp = …` (объявления)<br>`413: capOppSum += opp.size`<br>`414: capAllSum += ctx.combatEnemies.size`<br>`415–417: val (ours, theirs) = powerAfterFor(ctx, ctx.side, opp, f)` (дорого)<br>`428/429` — объявления<br>`433–436: if (lostRace) { lostRaceOffers++; if (ours >= theirs * floor && ours < theirs * PARITY_FLOOR) lostRaceOpened++ }` |
| 10 | 448 | `if (groupSafe && !foughtFoe)` | `null` | `groupSafe`, `foughtFoe`(411) | — |
| 11 | 463 | `if (oursNow >= theirsNow * floor)` (455) **и** `if (ourFlags <= hisFlags && ourAfter > hisAfter)` (461) — внутри `run { }` 451–466 | `null` | `oursNow`(453), `theirsNow`(454), `floor`, `ctx.flags`, `f.theirs` | `452: val side = ctx.side`, `453/454` — вызовы мощи (дорого)<br>`456: majOffers++` (только при истинном 455)<br>`457–460` — объявления<br>`462: majOpened++` |
| 12 | 468 | (условий нет — падение сквозь всю таблицу) | `"parity(${ours.toInt()}/${(theirs * floor).toInt()})"` — **строка ВЫЧИСЛЯЕМАЯ и НЕ равная счётной метке** | `ours`, `theirs`, `floor` | `467: capCount(f, "parity")` — считает под ключом `"parity"`, а наружу уходит `"parity(3985/3964)"` |

Итого **12 точек выхода**: 6 «можно» (`null`: 244, 247, 253, 439, 448, 463) и 6 запретов (249, 294, 336, 375, 387, 468).
Счётных (через `capCount`) запретов — 5; `"seventh"` (249) в `capBlocked` **не попадает никогда**.
Плюс 2 «холостых» `capCount` без выхода: 296 и 340.

---

## 3. Локальные величины (в порядке объявления)

Все — `val`; ни одного `var` в теле нет.

| Строка | Имя | Вид | Что это | После каких ворот объявлена | Дорогая? |
|---|---|---|---|---|---|
| 251 | `ticksLeft` | val | `arenaInfo.ticksLimit - getTicks()` | после №3 | нет |
| 252 | `losingAtTheEnd` | val | проекция счёта на конец матча `<= 0` | после №3 | нет (4 скаляра) |
| 260 | `intercept` | val | `enemyNotFightingNow && f.id == interceptFlagId` | после №4 | нет |
| 279 | `rushStale` | val | `!unflaggedRushNow && fightImminentTicks > rushStartDist` | после №4 | нет |
| 289 | `rushNow` | val | `approachingNow \|\| (unflaggedRushNow && firstFightTick == 0)` | после №4 | нет |
| 316–318 | `losingRace` | val | `behindOnScore && enemyRate > ourRate && (ourPowerOf(ctx.army, ctx.combatEnemies) >= enemyPowerOf(ctx.combatEnemies, ctx.army) * CAPTURE_EDGE)` | после №5 | **ДА: 2 × `powerOf`** по ВСЕЙ армии и всем боевым врага (`&&` ленив — при `behindOnScore == false` мощь не считается) |
| 329 | `foes` | val | `ctx.threats` (поле `Ctx`) | после №5 | нет |
| 330 | `mass` | val | `centroidOf(ctx.army)` | после №5 | почти нет: O(n) по армии |
| 331 | `contactArmy` | val | `if (mass == null) ctx.army else ctx.army.filter { getRange(it, mass) <= MASS_RANGE }` | после №5 | нет (O(n)) |
| 370–371 | `firstFightAhead` | val | `(firstFightTick == 0 \|\| (USE_FIRST_FIGHT_UNSETTLED && exchangeLiveNow)) && enemyMassedSignal && !ctx.passiveEnemy` (`USE_FIRST_FIGHT_UNSETTLED = false`, Tuning.kt:399) | после №6 | нет |
| 373 | `ourAfter` | val | `ctx.flags.count { it.ours } + 1` — **внутри `if` 372** | внутри блока №7 | нет |
| 374 | `hisAfter` | val | `ctx.flags.count { it.theirs } - (if (f.theirs) 1 else 0)` — внутри `if` 372 | внутри блока №7 | нет |
| 386 | `raceWon` | val | `(ourScore - enemyScore) + (ourRate - enemyRate) * ticksLeft > 0` (отрицание `losingAtTheEnd` со строгим знаком) | после №7 | нет |
| 401 | `oppLocal` | val | `ctx.combatEnemies.filter { it.id in fightPackIds \|\| f.guards.any { g -> g.id == it.id } }` | после №8 | нет (O(m·k), `f.guards` уже посчитан в `collectFlags`) |
| 411 | `foughtFoe` | val | `USE_GATE_VS_FIGHTER && fightMassedSeen && !ctx.passiveEnemy` (`USE_GATE_VS_FIGHTER = true`, Tuning.kt:418) | после №8 | нет |
| 412 | `opp` | val | `if (foughtFoe) ctx.combatEnemies else oppLocal` | после №8 | нет |
| 415–417 | `ours`, `theirs` | val (деструктуризация) | `powerAfterFor(ctx, ctx.side, opp, f)` | после №8 | **ДА: 2 × `powerOf` + 8 проходов `count` по `ctx.flags`**; читает мутируемый за тик `plannedCaptures` |
| 428 | `lostRace` | val | `lostRaceNow()` (480–505) | после №8 | нет (скаляры: `ourLostWindow`, `lastHurtTick`, счёт/темп) |
| 429 | `floor` | val | `if (lostRace) PARITY_FLOOR_LOST else if (stalledNow) PARITY_FLOOR_STALLED else PARITY_FLOOR` (0.75 / 0.93 / 0.97) | после №8 | нет |
| 452 | `side` | val | `ctx.side` — **внутри `run`** | после №10 | нет |
| 453 | `oursNow` | val | `ourPowerOf(side, opp)` | после №10 | **ДА: `powerOf`** |
| 454 | `theirsNow` | val | `enemyPowerOf(opp, side)` | после №10 | **ДА: `powerOf`** |
| 457 | `ourFlags` | val | `ctx.flags.count { it.ours }` | после №10, внутри `if` 455 | нет |
| 458 | `hisFlags` | val | `ctx.flags.count { it.theirs }` | после №10, внутри `if` 455 | нет |
| 459 | `ourAfter` | val | `ourFlags + 1` | там же | нет |
| 460 | `hisAfter` | val | `hisFlags - (if (f.theirs) 1 else 0)` | там же | нет |

Справка о цене (для «дорогих»): `powerOf` — `Forecast.kt:462–472`, суммирует `effectiveDps` по своей стороне (каждый вызов — `meleeFactor` с проходом по противникам, `Forecast.kt:387–393`) плюс `weightedHits` по своей стороне и `profileOf(...).heal` по противникам: O(|side|·|opp|). `powerAfterFor` (518–539) делает две `mods(...)` (по 4 пары `count` по `ctx.flags`) и **два** `powerOf`. Итого полный проход тела = **6 вызовов `powerOf` на один вызов функции** (2 в `losingRace`, 2 в `powerAfterFor`, 2 в `run`).

Прочие вызываемые предикаты дёшевы: `fullSpeed` (World.kt:504) и `hasWeapon` (World.kt:421) идут через `unitsNow.of(creep)` — словарь фактов тика с ленивым созданием (`Facts.kt:87`), плюс кэши `bodyWeightNow`/`liveMovesNow`; `inContact` (World.kt:547) — O(|foes|·|ours|) на `getRange`.

---

## 4. Вложенные блоки

| Строки | Что | Выход наружу | Счётчики/записи |
|---|---|---|---|
| 290–295 | `if (rushNow && !rushStale && !intercept && !(stalledNow)) { … }` | да, 294 | 293 `kvetoAll++`, `kvetoHit++` |
| 296 | `if (fightImminentNow && rushStale) capCount(f, "rush.approach.expired")` — одна строка, **без блока и без выхода** | нет | `capBlocked["rush.approach.expired"]`, **и `capSeen.add(f.id)`** |
| 332–337 | `if (!losingRace && … inContact(foes, contactArmy)) { … }` | да, 336 | 335 `warmCapAll++`, `warmCap++` |
| 339–340 | `if (…) capCount(f, "contact.edge.lifted")` — «отдельно считаем то, что снято правкой», **без выхода** | нет | `capBlocked["contact.edge.lifted"]`, **и `capSeen.add(f.id)`** |
| 372–376 | `if (firstFightAhead && …) { val ourAfter; val hisAfter; if (ourAfter > hisAfter) return … }` | да, 375 | нет |
| 433–436 | `if (lostRace) { lostRaceOffers++; if (…) lostRaceOpened++ }` | нет | 434 `lostRaceOffers++`, 435 `lostRaceOpened++` |
| 451–466 | `run { … }` — «флагов больше ценой небольшого минуса» (v222). Внутри: 452–454 объявления (2 дорогих вызова мощи), 455 `if (oursNow >= theirsNow * floor) { … }`, 461 `if (ourFlags <= hisFlags && ourAfter > hisAfter) { … }` | да, 463 (`return` из **функции**, не из `run`) | 456 `majOffers++`, 462 `majOpened++` |

Локальных функций (`fun` внутри тела) в `captureBlock` нет. Они есть в `powerAfterFor` (`fun mods`, `fun k` — 521–530), которая вызывается из строки 415.

Переключателей (`USE_*`) в теле два: `USE_FIRST_FIGHT_UNSETTLED` (370, сейчас `false`) и `USE_GATE_VS_FIGHTER` (411, сейчас `true`). Упомянутые в комментариях `USE_NO_SEVENTH_FLAG`, `USE_FLAG_MAJORITY`, `CAPTURE_FLOOR` в коде тела **не читаются** (константы `USE_NO_SEVENTH_FLAG`/`USE_FLAG_MAJORITY` в пакете вообще отсутствуют — остались только имена в комментариях и в KDoc прибора `Instruments.kt:718`).

---

## 5. Ловушки для переноса в `Gate(tag, verdict)` с `Allow | Veto(reason) | Next`

### 5.1. Побочные эффекты, которые нельзя оторвать от места

1. **`capCount` — не «счётчик», а мутатор общего замка.** Он делает `capSeen.add(f.id)`, и первый вызов на пару (тик × флаг) запирает все следующие. Поэтому холостые вызовы в 296 и 340 **воруют учёт у настоящего запрета ниже**: сработав, они делают так, что `capCount(f, "contact.mass")` (336) или `capCount(f, "parity")` (467) в этом же тике для этого флага НЕ увеличат `capBlocked`. Если в таблице условия всех строк считаются целиком, эти два «прибора без выхода» начнут срабатывать в тех вызовах, где сегодня до них не доходит, — и `capgate=` изменится, хотя поведение бота нет.
2. **`capOffered` (246) зависит от `capSeen`**: «предложение» считается на каждый ВЫЗОВ, пока флаг в этом тике не записан. То есть разрешённый флаг даёт `capOffered++` столько раз, сколько раз его спросили (десятки), а запрещённый — ровно до первого `capCount`. Прибор `capgate=<сумма capBlocked>/<capOffered>` (Instruments.kt:516) на этом и построен; любое изменение числа вызовов ломает знаменатель.
3. **Сброс тика стоит ПОСЛЕ первых ворот** (245 идёт после `if (f.ours) return null` 244). Вызов только со своими флагами тик не сбрасывает. Если строка «наш флаг» уедет ниже сброса — поменяется момент очистки `capSeen`.
4. **Счётчики на части путей** (растут только у тех вызовов, что досюда дошли, и без дедупликации):
   * 293 `kvetoAll/kvetoHit` — только при срабатывании ворот броска и только при `unflaggedRushNow`;
   * 335 `warmCapAll/warmCap` — только внутри сработавших ворот контакта;
   * 413–414 `capOppSum/capAllSum` — у всех вызовов, дошедших до 413 (то есть ПРОШЕДШИХ ворота 1–8);
   * 434–435 `lostRaceOffers/lostRaceOpened` — только при `lostRace`;
   * 456/462 `majOffers/majOpened` — только в `run`, и только у вызовов, не отсечённых воротами 9 и 10.
     При полном обходе таблицы каждый из них начнёт считать вызовы, отсечённые ранними воротами, — это видимое изменение всех приборов `kveto=`, `warmcap=`, `capopp=`, `lostrace=`, `maj=`.
5. **Условие с побочным эффектом внутри условия** — нет ни одного: все `capCount`/`++` стоят отдельными операторами (лint этого пакета, коммиты `8660f3f`/`fc69271`, это уже вычистил). Единственная «нечистота в выражении» — `return capCount(f, …)`: значение выхода берётся из мутирующего вызова.

### 5.2. Ленивость и цена

* `losingRace` (316) и пара `ours/theirs` (415) **считают мощь**. Сегодня они лениво не вычисляются, если сработали ворота 1–5 (для `losingRace`) или 1–8 (для `ours/theirs`). Полный обход таблицы превращает один вызов в **6 `powerOf`** вместо нуля–двух. При `ctx.flags` = 7 и десятках вызовов за тик это главный риск CPU всей правки (`cpuGuard`/`CPU_GUARD_MS` в `runRunners` существует именно из-за цены этого места).
* Внутри `losingRace` цена ещё и условная: `behindOnScore && enemyRate > ourRate && (мощь…)` — при ведущем счёте мощь не считается вовсе. Разворачивание этого `&&` в «посчитать всё, потом сравнить» дороже сегодняшнего.
* `powerAfterFor` читает **`plannedCaptures`** (519), который набивается в течение тика (`planCapture`, Strategist.kt:507–510, очищается в `World.kt:1038`). Значит `ours/theirs` зависят от МОМЕНТА вызова внутри тика. Внутри одного вызова это константа, но выносить вычисление «на уровень тика» (посчитать раз и раздать строкам) **нельзя** — величина разная у разных вызовов одного тика.
* `floor` (429) нужен и строке 12 (в тексте причины), и строке 11 (в сравнении), и приборам 435 — он общий, а не принадлежит одной строке.

### 5.3. Вердикты, которые не укладываются в `Veto(константа)`

* **12 (468)**: причина вычисляемая — `"parity(${ours.toInt()}/${(theirs * floor).toInt()})"`, и она **не равна** метке, под которой отказ считается (`capCount(f, "parity")`, 467). В таблице придётся хранить отдельно `tag` (для прибора) и `reason` (для возврата).
* **5 (294)**: причина выбирается на лету — `if (unflaggedRushNow) "rush.unflagged" else "rush.approach"`; одна строка таблицы даёт две метки.
* **3 (249)**: единственный запрет, возвращающий строку **мимо** `capCount` (не считается). Если при переносе все `Veto` пойдут через общий счётчик, прибор `cap=` получит новый ключ `seventh` — это изменение отчёта, а не поведения, и его надо назвать заранее.

### 5.4. Можно ли считать условие каждой строки «вне очереди»

Аварийных мест нет: в теле нет ни одного `!!`, `minOf`/`first` на возможно пустом списке тоже нет (`centroidOf` возвращает `null` и это обработано 331; `powerOf` на пустых списках даёт 0.0 через `firstOrNull`/`sumOf`/`lanchester`, без NaN — `Forecast.kt:379–383`). Поэтому «нельзя» ниже — про цену и побочные эффекты, а не про падение.

| # | Строка | Условие чистое? | Можно ли вычислить вне очереди |
|---|---|---|---|
| 1 | 244 | да | **можно** (дёшево) |
| 2 | 247 | да | **можно** |
| 3 | 249 | да | **можно** |
| 4 | 253 | да | **можно** |
| 5 | 290 | да (чтение `var`-признаков) | **условие — можно**; тело (293 + `capCount`) — нет |
| 6 | 332 | да, но тянет `losingRace` | **нежелательно**: +2 `powerOf` на каждую строку; тело (335 + `capCount`) — нет |
| 7 | 372/375 | да | **можно** |
| 8 | 387 | да | **можно** |
| 9 | 439 | формально да, но зависит от `plannedCaptures` (меняется за тик) и от `opp`→`foughtFoe` | **нельзя считать заранее/для всех**: +2 `powerOf`, значение привязано к моменту вызова |
| 10 | 448 | да | **можно** |
| 11 | 455/461 | да | **нельзя**: +2 `powerOf`, и `majOffers++` (456) — побочный эффект прямо между двумя условиями строки |
| 12 | 468 | причина вычисляемая | **нельзя**: требует `ours/theirs/floor`, то есть `powerAfterFor` |

Итого: **4 строки (6, 9, 11, 12) вне очереди считать нельзя** (цена мощи + зависимость 9 от `plannedCaptures` + счётчик внутри 11), остальные 8 — можно; но **действия** (счётчики и `capCount`) всех без исключения строк обязаны исполняться строго в прежнем порядке и только на тех путях, где исполняются сейчас, иначе меняются приборы `capgate=`, `cap=`, `kveto=`, `warmcap=`, `capopp=`, `lostrace=`, `maj=`.

### 5.5. Прочая зависимость от порядка

* `intercept` (260), `stalledNow` и `behindOnScore` повторяются как «снятия» в строках 5, 6, 7, 8 — при разложении на таблицу это четыре независимые строки с общими сомножителями, а не одно общее «снятие»: набор сомножителей у каждой свой (например, 8 добавляет `!ctx.passiveEnemy`, а 6 — `!losingRace`).
* `foughtFoe` (411) участвует дважды: определяет `opp` (412 → строки 9, 11, 12) и сам служит условием строки 10. Разрывать его на две строки нельзя — это одно значение.
* `ticksLeft` (251) используется и в строке 4 (`losingAtTheEnd`), и в строке 8 (`raceWon`): обе строки читают одну величину, объявленную до первой из них.
* Строки 7 и 11 обе считают `ourAfter`/`hisAfter` **одинаковыми выражениями, но в разных областях видимости** (373–374 и 459–460). Это две разные локальные пары с совпадающим текстом; лint пакета (`tools/`, коммит `8660f3f`) ругается на дословно повторённую выборку — при переносе их придётся либо назвать одной функцией, либо оставить в разных строках таблицы сознательно.

### 5.6. Чего я не понял

* Назначение холостого `capCount` в 296 (`"rush.approach.expired"`) и 340 (`"contact.edge.lifted"`) как приборов ясно, но **не ясно, задумано ли**, что они запирают `capSeen` и тем самым обнуляют учёт последующего настоящего запрета для того же флага в том же тике. В комментариях это не сказано; поведение читается только из кода `capCount`. Если это дефект, его исправление изменит отчёт — то есть тождеством раскладка не будет.
* Строка `Strategist.kt:992` (`val best = ctx.flags.filter { … captureAllowed(ctx, it) } …`) недостижима из-за `return` на 991. Считаю её мёртвой и в расчёт вызовов за тик не беру, но на всякий случай отмечаю: если при раскладке кто-то «оживит» `commandGoal`, число вызовов ворот за тик вырастет на `ctx.flags`.

---

# Карта секций `PainAndGain.commandFight`

Файл: `starter/src/jsMain/kotlin/season4/painandgain/Fight.kt`
Точные границы функции: **строки 431–1189** (заголовок 431–433, тело 434–1188, закрывающая скобка 1189).

Итог по объёму: **30 общих локальных величин** (плюс 7 параметров), **22 локальные функции** и **1 локальный класс**, **9 значений `passTag`** («-» + восемь именованных проходов) и **четыре безымянные вставки** между ними.

---

## 1. Сигнатура и ранние выходы

```kotlin
internal fun PainAndGain.commandFight(
    army: List<Creep>,                       // 431
    combatEnemies: List<Creep>,              // 431
    armedEnemies: List<Creep>,               // 431
    out: MutableMap<String, Position>,       // 432  — ВЫХОД: id крипа → назначенная клетка
    intent: Intent = Intent.PRESS,           // 432
    ourFlagCells: Set<Int> = emptySet(),     // 433
    healersOnly: Boolean = false,            // 433
)
```

Возвращаемый тип — `Unit`. Результат отдаётся через мутацию параметра `out`.

Ранние выходы (все — `return` из функции целиком, до основной работы):

| строка | условие | что делает | состояние `out` на выходе |
|---|---|---|---|
| 434 | — | `out.clear()` — безусловно, первой строкой | пустой |
| 436 | `fighters.isEmpty() \|\| armedEnemies.isEmpty()` | `return` | пустой |
| 448 | `cells.isEmpty()` (после наполнения 441–447, **до** `Formation.fist`) | `return` | пустой |

Других `return` из тела функции нет; все прочие `return` принадлежат локальным функциям (см. раздел 3).

---

## 2. Общие локальные величины (в порядке объявления)

| строка | имя | val/var | тип | что это | кто ПИШЕТ после объявления |
|---|---|---|---|---|---|
| 435 | `fighters` | val | `List<Creep>` | `mobileOf(army)` — подвижные не-спавнящиеся свои | — |
| 437 | `enemyAt` | val | `HashSet<Int>` | ключи клеток, занятых `combatEnemies` | 438 (`add`); дальше только чтение (445) |
| 440 | `cells` | val | `HashMap<Int, Position>` | кандидатные клетки: всё проходимое в 2 клетках от бойца | 441–447 (`cells[key] =`); **449 — `Formation.fist` делает `cells.clear(); cells.putAll(keep)` внутри себя** (Formation.kt:273) |
| 449 | `ax`, `ay` | val | `Int`, `Int` | якорь кулака из `Formation.fist` | — (**нигде не читаются**: одноимённые `ax/ay` в проходе straggler — другие, объявлены на 719) |
| 473 | `AddrShooter` | — | локальный `class` | `(x, y, ranged, melee)` — снимок вражеского ствола | — |
| 474 | `addrShooters` | val | `ArrayList<AddrShooter>` | все его вооружённые стволы | 477 (`add`) |
| 479 | `addrLive` | val | `List<Creep>` | `living(army)` | — |
| 480 | `addrFor` | **var** | `String?` | id крипа, под которого посчитан кеш адресной опасности | 484 (в `addrPrepare`) |
| 481 | `addrRH`, `addrRA` | val | `DoubleArray` (размер = `addrShooters.size`) | лучший «другой» кандидат для стрелка: лекарь / любой | 485, 495 (в `addrPrepare`) |
| 482 | `addrMH`, `addrMA` | val | `DoubleArray` | то же для мили | 485, 496 (в `addrPrepare`) |
| 517 | `goal` | val | `IntArray?` | поле расстояний до очага, `ensureGoalField(...)` | — (сам вызов пишет поля объекта, см. раздел 5) |
| 524 | `taken` | val | `HashSet<Int>` | ключи уже розданных клеток | add: 644, 648, 674, 1127, 1174; remove: 633, 1169 |
| 527 | `allyAt` | val | `HashSet<Int>` | клетки живых своих | 528 (`add`); **нигде не читается — мёртвая величина** |
| 530 | `allyOf` | val | `HashMap<Int, Creep>` | клетка → стоящий в ней свой (для цепочек) | 531 (`put`), 660 (`remove(c.key)` когда крип реально уходит); читается 592, 1097, 1108 |
| 566 | `passTag` | **var** | `String` | имя текущего прохода для прибора `pass=` | 676, 717, 943, 954, 999, 1130, 1137, 1155; читается только на 661 внутри `place` |
| 678 | `rotatingMeet` | val | `HashMap<String, Position>` | id уходящего по ротации → его клетка (для встречи с лекарём) | 711 (`put`); читается 1037 |
| 679 | `medicked` | val | `HashSet<String>` | уходящие, к которым лекарь уже назначен | 1044 (`add`); читается 1037 |
| 729 | `weakestMelee` | val | `Creep?` | `armedEnemies.minByOrNull { it.hits }` | — (читается 923) |
| 736 | `hisMelee` | val | `List<Creep>` | его крипы с живым мили | — (читается 935, 1171) |
| 743 | `melees` | val | `List<Creep>` | наши чисто-мили | — (946, 1138, 1159, 1181, 1182) |
| 744 | `rangeds` | val | `List<Creep>` | наши со стрелковыми частями | — (956, 1138, 1179, 1180) |
| 745 | `healers` | val | `List<Creep>` | наши чистые лекари | — (753, 1000, 1183, 1184) |
| 749 | `stripped` | val | `List<Creep>` | раздетые (`unitOf(it).stripped`) | — (1131) |
| 753 | `healerCells` | val | `List<Position>` | НЫНЕШНИЕ клетки лекарей (снимок до раздачи) | — (1182) |
| 762 | `maxV` | **var** | `Double` | максимум уязвимости по `cells` | 765 (в цикле 763–766); читается 770, 771 (в `sagAt`) |
| 792 | `weakestFoe` | val | `Creep?` | `armedEnemies.minByOrNull { it.hits }` — дубль `weakestMelee` по выражению | — (923) |
| 1156 | `pinFoes` | val | `List<Creep>` | его боевые (мили+стрелки+лекари) среди `combatEnemies` | — (1160) |
| 1157 | `hisStuck` | val | `HashSet<Int>` | клетки его уставших крипов | 1158 (`add`); читается 1164 |

Три `var`, живущих через всю функцию: **`addrFor` (480)**, **`passTag` (566)**, **`maxV` (762)**. Плюс мутируемые коллекции: `cells`, `taken`, `allyOf`, `rotatingMeet`, `medicked`, `enemyAt`, `allyAt`, `hisStuck`, `addrRH/RA/MH/MA` и сам параметр `out`.

---

## 3. Локальные функции (в порядке объявления)

| строки | имя и параметры | возвращает | ЧИТАЕТ общие локальные | ПИШЕТ | счётчики / память | рекурсия |
|---|---|---|---|---|---|---|
| 483–499 | `addrPrepare(c: Creep)` | `Unit` | `addrShooters`, `addrLive`, `out` (488) | `addrFor` (484), `addrRH/RA/MH/MA` (485, 495, 496) | — (зовёт `healerOnly`, `InfluenceMap.cell`) | нет |
| 500–513 | `addressedAt(c: Creep, x: Int, y: Int)` | `Double` | `addrFor`, `addrShooters`, `addrRH/RA/MH/MA` | косвенно — через вызов `addrPrepare(c)` на 501 (ленивое кеширование) | — | нет |
| 515–516 | `danOf(c: Creep, key: Int)` | `Double` | — | — | `InfluenceMap.dangerAt(key)` (чтение) | нет |
| 519–523 | `goalCost(key: Int)` | `Double` | `goal` | — | — | нет |
| 540–552 | `pathDanger(c: Creep, p: Position)` | `Double` | — (зовёт `danOf`) | — | `DistanceMap.isTerrainWall` (чтение) | нет |
| 556–563 | `nearCells(c: Creep)` | `List<Pair<Int, Position>>` | `cells` | — | — | нет |
| **567–663** | **`place(c: Creep, wants: (Position)->Boolean, rank: (Position)->Double, depth: Int = 0, rescue: Boolean = false)`** | `Boolean` | `cells` (582), `taken` (585), `allyOf` (592), `out` (593), `passTag` (661) | `out` (`remove` 633, `put` 644, `put` 648), `taken` (`remove` 633, `add` 644, `add` 648), `allyOf` (`remove` 660) | `goalDecisions++` (622, **на любой глубине**), `goalFlips++` (624, **на любой глубине**), `adrN++`/`adrE +=`/`adrT +=`/`adrSame++` (653–654, **только `depth == 0`**), `passCount[passTag] += 1` (661, **только `depth == 0`**); `InfluenceMap.addClaim(b.x, b.y)` (659) — ЗАПИСЬ в поле притязаний; `InfluenceMap.dangerAt` (651) — чтение | **ДА**: 636 и 637, `place(tenant, …, depth + 1)`; ограничение `depth >= CHAIN_DEPTH → return false` (627) |
| 741 | `cellOf(f: Creep)` | `Position` | `out` | — | — | нет |
| 742 | `foeDist(x: Int, y: Int)` | `Int` | — (параметр `armedEnemies`) | — | — | нет |
| 751 | `intentOf(c: Creep)` | `Intent` | — (возвращает параметр `intent`, `c` не используется) | — | — | нет |
| 769–773 | `sagAt(key: Int)` | `Double` | `maxV` | — | `InfluenceMap.vulnerabilityOf/influenceOf` (чтение) | нет |
| 776–777 | `screenAt(c: Creep, p: Position)` | `Int` | `fighters`, `out` (через `cellOf`) | — | — | нет |
| 785–791 | `ttlAt(c: Creep, key: Int, p: Position)` | `Double` | — (зовёт `danOf`, `screenAt`) | — | `InfluenceMap.healReachAt/healFromSelf` (чтение) | нет |
| 793 | `stayBonus(c: Creep, p: Position)` | `Double` | — | — | — | нет |
| 797–818 | `scoreMelee(c, key, p, att, dan, focus)` | `Double` | — (зовёт `danOf`, `sagAt`, `stayBonus`) | — | `InfluenceMap.attractionTo/attMeleeAt/profileOf/takenOf/vulnerabilityOf/healReachAt/claimAt` (чтение); константа `USE_MELEE_STRIKE_VALUE` | нет |
| 821–840 | `scoreRanged(c, key, p, att, dan, focus)` | `Double` | — (зовёт `danOf`, `stayBonus`) | — | `InfluenceMap.attractionTo/attRangedAt/profileOf/takenOf/influenceOf/claimAt`; `USE_RANGED_SHOT_VALUE` | нет |
| 845–890 | `scoreHeal(c, key, p, att, dan)` | `Double` | — (зовёт `screenAt`, `stayBonus`) | — | читает ПОЛЯ объекта `victimNow` (869), `victimSaveable` (870), `wallCells` (872) и параметр `army`; `InfluenceMap.fireFieldAt/healOf/attHealAt/bestDeliveryAt/deliveryFireMode/influenceOf/claimAt`; `USE_HEAL_NEED_ACTUAL`, `USE_HEAL_NO_LINE` | нет |
| 895–901 | `weightsOf(i: Intent)` | `Triple<Double, Double, Int>` | — | — | — | нет |
| 908–942 | `placeScored(c: Creep, role: Int, i: Intent)` | `Boolean` | `weakestMelee` (923), `weakestFoe` (923), `hisMelee` (935) | через `place` | `Memory.chaseTarget[c.id]` (912, чтение); `gateLevels[minOf(lvl, size-1)]++` (938), `gateFell++` (940) | нет (зовёт `place`) |
| 1085–1086 | `adjSafe(p: Position)` — вложена в `run` прохода лекарей | `Boolean` | `fighters`, `out` (через `cellOf`) | — | — | нет |
| 1087–1101 | `terms(p: Position)` — там же | `DoubleArray` (8 слагаемых) | `allyOf` (1097), `out` (1097) | — | те же чтения, что у `scoreHeal`, плюс `goalCost` | нет |
| 1164 | `strikes(p: Position)` — объявлена ВНУТРИ цикла прохода pinned | `Boolean` | `hisStuck`, локальные цикла `near`, `ours` | — | зовёт верхнеуровневую `pinnedAt` (Fight.kt:416) | нет |

### `place(...)` подробно (567–663)

1. 569–573: локальные `best`/`bestScore`/`bestTenant` и параллельная пара `bare`/`bareScore` — «оценка без слагаемого спуска к очагу», нужна только прибору `goalFlips`.
2. 578: `canStep = canMove(c) && c.fatigue == 0`.
3. 582–620 — перебор ВСЕХ `cells` с отбрасыванием по координатам (583), по `canStep` (584), по `taken` (585), по предикату `wants` (586).
   - 592–594: `tenant` — живой сосед в клетке, которому либо не выдан приказ, либо выдан приказ «стой».
   - 601 `lethal`, 609 `bog`, 610–611 `sc0`, 615 `sc = sc0 + GOAL_STEP_COST * goalCost(key)`.
4. 621: `val b = best ?: return false`.
5. 622–624: приборы `goalDecisions` / `goalFlips`.
6. 626–647: цепочка. `depth >= CHAIN_DEPTH → return false` (627). При `rescue` снимается приказ жильца (633), затем попытка свопа (636) или обычного увода (637); при неудаче приказ возвращается, если клетка не занята (644), и `return false` (645).
7. 648: `taken.add(b.key); out[c.id] = b` — собственно назначение.
8. 650–655: прибор адресной опасности, только при `depth == 0`.
9. 659: `InfluenceMap.addClaim(b.x, b.y)`.
10. 660: `allyOf.remove(c.key)` — только если крип реально сдвинулся.
11. 661: `passCount[passTag]++`, только при `depth == 0`.
12. 662: `return true`.

---

## 4. Секции-проходы (в порядке исполнения)

| строки | имя | что решает | цикл и порядок | читает / пишет общие локальные | счётчики и память | выходы |
|---|---|---|---|---|---|---|
| 434–448 | **пролог** (безымянная) | `out.clear()`, состав `fighters`, множество вражеских клеток, кандидатные клетки | `for (c in fighters) for dx for dy` (441), порядок `sym(2)` — зависит от `mirrorTL` | пишет `enemyAt`, `cells` | — | два `return` из функции: 436, 448 |
| 449 | **сужение кулаком** (безымянная) | `Formation.fist` оставляет в `cells` только клетки в `FIST_RADIUS` от медианы с прямой видимостью, плюс девять клеток вокруг преследователей | — | **мутирует `cells` на месте**; возвращённые `ax/ay` не используются | читает `Memory.chaseOf` (Formation.kt:269) | — |
| 473–516 | **машинерия адресной опасности** (безымянная) | подготовка `addrShooters` и ленивого кеша `addrPrepare`/`addressedAt`; `danOf` — фасад над полем E | `for (e in armedEnemies)` (475) | пишет `addrShooters` | — | — |
| 517–563 | **поле цели и вспомогательные меры** (безымянная) | `ensureGoalField`, `goalCost`, `taken`, `allyAt`, `allyOf`, `pathDanger`, `nearCells` | `for (a in army)` дважды (528, 531) | пишет `taken` (пустой), `allyAt`, `allyOf` | `ensureGoalField` пишет поля `goalTick`, `goalField`, `goalSeeds`, `goalCx/Cy` и счётчики `goalHolds`/`goalRebuilds` — **один раз за тик** (кеш по `goalTick == getTicks()`, Fight.kt:371–372) | — |
| 566–663 | `passTag = "-"`; объявление `place` | — | — | — | — | — |
| **672–675** | **заморозка бойцов** (безымянная; предлагаемое имя — `freezeFightersWhenHealersOnly`) | при `healersOnly` всем НЕ-лекарям выдаётся приказ «стой», их клетки становятся `taken` — они обходятся как препятствия и тела для экрана | `for (f in fighters)` — порядок `fighters` | пишет `out` (674), `taken` (674) | **не зовёт `place`** → в `passCount` не попадает; `InfluenceMap.addClaim` НЕ ставится | — |
| **676–712** | **`retreat`** | увести того, кому грозит гибель (`hurtBadly` / `alone`) либо кто уходит по ротации (`Memory.rotByFocus`, `Memory.stepOutIds`); клетка — самая безопасная, среди равных подальше от врага и ближе к лекарю | `for (c in fighters)` — **без сортировки**, порядок `fighters` | читает `out` (681); пишет `out`/`taken`/`allyOf` через `place(… rescue = true …)` (708), пишет `rotatingMeet` (711) | читает `lostTick` (682), `Memory.rotByFocus`/`Memory.stepOutIds` (687), `huntsWounded` (706); через `place` — `passCount["retreat"]`, `goalDecisions/goalFlips`, `adr*`, `InfluenceMap.addClaim` | `continue` 681 (уже назначен), 688 (нет причины уходить) |
| **717–728** | **`straggler`** | вернуть к якорю отставших и вырвавшихся (дальше `FIST_RADIUS + STRAGGLER_SLACK` от медианы), кроме тех, кто уже в контакте | `fighters.sortedByDescending { расстояние до медианы }` (720) | пишет `out`/`taken`/`allyOf` через `place` (725) | `passCount["straggler"]` и приборы `place` | `continue` 721, 722, 724 |
| 729–753 | **объявления середины** (безымянная) | `weakestMelee`, `hisMelee`, `cellOf`, `foeDist`, разбиение армии на `melees`/`rangeds`/`healers`/`stripped`, `intentOf`, `healerCells` | — | пишет перечисленные локальные | — | — |
| **760–766** | **сброс полей раздачи** (безымянная; предлагаемое имя — `resetFieldsBeforeScoring`) | `InfluenceMap.clearClaim()` (760) — **стирает притязания, поставленные проходами retreat и straggler**; `stampHealNeed(living(army))` (761) — заново строит поле нужды, очищая `needLeft` и `inFire`; подсчёт `maxV` | `for ((k, _) in cells)` (763) | пишет `maxV` | пишет глобальное состояние `InfluenceMap`: `claim`, `attHeal`, `needLeft`, `inFire`, `totalNeed`, счётчики `wardsUnderRanged`/`wardsUnderStill` | — |
| 769–942 | **оценки и ворота** (безымянная) | `sagAt`, `screenAt`, `ttlAt`, `weakestFoe`, `stayBonus`, `scoreMelee`, `scoreRanged`, `scoreHeal`, `weightsOf`, `placeScored` | — | пишет `weakestFoe` | — | — |
| **943–953** | **`melee`** | клетка мили по `scoreMelee` с мягкими воротами выживания; при полном отказе — самая безопасная клетка | `melees.sortedBy { расстояние до ближайшего вооружённого врага }` (946); при `healersOnly` — пустой список | `out`/`taken`/`allyOf` через `place` | `passCount["melee"]`, `gateLevels`/`gateFell` (в `placeScored`), приборы `place` | `if (!ok)` 952 — добор |
| **954–981** | **`ranged`** | клетка стрелка по `scoreRanged`; добор — самая безопасная ИЗ ТЕХ, откуда достаёт, иначе любая самая безопасная | `rangeds.sortedBy { число своих кандидатных клеток в 2, откуда достаёт врага }` (956); при `healersOnly` — пусто | `cells` (в ключе сортировки), `out` (976) | `passCount["ranged"]`, `fallReach++` / `fallAny++` (979), `gateLevels`/`gateFell` | — (`val ok` на 957 **не читается**) |
| **985–998** | **идущие вперёд** (безымянная; предлагаемое имя — `markAdvancingWards`) | пометить бойцов, чья назначенная (или прошлотиковая) клетка ближе к его стволам — их лекарь не считает в «точном» режиме | `for (f in fighters)` (986), только при `USE_HEAL_EXACT_IN_FIRE` (сейчас `false`, тело мертво) | читает `out` (988) | `InfluenceMap.advancingWards.clear()` (985) + `add` (991, 996); `Memory.lastCell` (993, чтение) | `continue` 987, 993 (`?: continue`), 995 — **`passTag` здесь ещё `"ranged"`** |
| **999–1119** | **`healer`** | клетка лекаря: сперва встреча с уходящим раненым (1037–1049), иначе `scoreHeal` с воротами, иначе добор; затем приборы `hadj`/`hadjn` и зонд `hpick` | `for (c in healers)` (1000) — **без сортировки**, порядок `healers` (= порядок `fighters`) | читает `rotatingMeet`, `out`, `taken` (1107), `allyOf` (1108); пишет `medicked` (1044), `out`/`taken`/`allyOf` через `place` (1043, 1056, 1060) | `rotfMeet++` (1045); `InfluenceMap.saturateHeal` (1047, 1057) — **мутирует `needLeft` и `attHeal` по ходу цикла**; `hfireAll/hfireN` (1055), `hadjAll/hadjN/hfireAdj` (1064–1066), `hadjnAll/hadjnN` (1070–1071), `hpN/hpAdj/hpAvail/hpGate/hpDelta` (1102–1117); `Memory.lastHits` (1065, чтение) | внутри `run { … }` (1079–1118): `return@run` на 1080, 1103, 1114 — выходят из `run`, цикл продолжается |
| **1122–1128** | **хранитель флага** (безымянная; предлагаемое имя — `flagKeepersHold`) | крип, стоящий на нашем флаге и ещё не получивший приказа, получает «стой», если его клетка не занята | `for (c in fighters)` (1122) | читает `out` (1123), `taken` (1127); пишет `taken`, `out` (1127) | **не зовёт `place`** → мимо `passCount`; `passTag` здесь ещё `"healer"` | `continue` 1123 |
| **1130–1133** | **`stripped`** | раздетых увести из огня (опасность ×100 минус дистанция до ближайшего вооружённого) | `for (c in stripped)` (1131), без сортировки; при `healersOnly` — пусто | `out`/`taken`/`allyOf` через `place` | `passCount["stripped"]` | — |
| **1137–1142** | **`catchall`** | вооружённый не остаётся без места: безопаснее и ближе к своей нынешней клетке. Лекарей и раздетых не трогает | `for (c in melees + rangeds)` (1138) — конкатенация, порядок списков | читает `out` (1139) | `passCount["catchall"]` | `continue` 1139 |
| **1155–1175** | **`pinned`** | мили, стоящий рядом с зажатым врагом, переставляется в клетку удара — но только если переживёт её с порогом своего замысла; иначе прежний приказ возвращается | `melees.sortedBy { расстояние до ближайшего вооружённого }` (1159); при `healersOnly` — пусто | читает `out` (1165), `hisMelee` (1171); пишет `out` (`remove` 1169, `put` 1174 и через `place`), `taken` (`remove` 1169, `add` 1174 и через `place`) | `passCount["pinned"]`; читает `pinFoes`, `hisStuck`; зовёт `pinnedAt` | `continue` 1161 (нет зажатых рядом), 1166 (уже бьёт) |
| 1179–1188 | **эпилог приборов** (безымянная) | три числа согласованности строя | `count { … }` по `rangeds`, `melees`, `healers` | читает `out`, `healerCells`, `melees`, `rangeds`, `healers` | пишет поля `planGunsAll/planGunsIn/planMeleeAll/planMeleeHealed/planHealAll/planHealBehind` | `return@count false` на 1185 — выход из лямбды `count` |

---

## 5. Что исполняется между проходами и после них

**Подготовка полей (порядок обязателен):**

- 449 `Formation.fist(fighters, cells)` — **сужает `cells` на месте** до кулака; всё, что ниже, перебирает уже суженное множество.
- 517 `ensureGoalField(fighters, combatEnemies)` — строит/переиспользует поле расстояний до очага; **кешируется по тику** (`goalTick == getTicks()`), поэтому из пяти вызовов `commandFight` за тик поле строит только первый, а счётчики `goalHolds`/`goalRebuilds` растут один раз за тик.
- 760 `InfluenceMap.clearClaim()` — **стирает притязания, поставленные проходами retreat и straggler**; далее `claim` копится заново, с melee и до конца функции.
- 761 `InfluenceMap.stampHealNeed(living(army))` — перестраивает `attHeal`, `needLeft`, `inFire`, `totalNeed`.
- 762–766 — `maxV` (максимум уязвимости по кандидатным клеткам), питает `sagAt`.
- 985 `InfluenceMap.advancingWards.clear()` + 986–998 — заполняется перед проходом лекарей (при выключенном `USE_HEAL_EXACT_IN_FIRE` тело не исполняется, но `clear()` — да).
- 1047 и 1057 `InfluenceMap.saturateHeal(...)` — внутри прохода лекарей, после каждого удачного назначения: снимает покрытую нужду, поэтому следующий лекарь видит другое поле.

**Печать:** внутри функции печати нет ни одной (`println` не встречается на 431–1189).

**Итоговые счётчики:** 1179–1188 — `planGunsAll`, `planGunsIn`, `planMeleeAll`, `planMeleeHealed`, `planHealAll`, `planHealBehind`. Это **присваивания**, а не инкременты: они перезаписываются каждым вызовом, то есть после перебора замыслов хранят числа ПОСЛЕДНЕГО вызова.

**Возвращаемое значение:** `Unit`; результат — заполненный `out` плюс перечисленные побочные эффекты в `InfluenceMap`, `Memory`-независимых полях объекта и приборах.

---

## 6. Ловушки для переноса

### Затенение имён

1. **`ax`, `ay` объявлены дважды**: 449 (из `Formation.fist`, **мертвы**) и 719 (из `Formation.median`, внутри блока straggler). Если 449 превратить в поле класса, а 719 оставить локальным — поведение не изменится, но если наоборот, проход straggler начнёт мерить от другого якоря (`fist` возвращает ту же медиану, однако вычисляется она от `fighters` в момент 449, а не 719 — списки те же, так что значения совпадают; полагаться на это без проверки нельзя).
2. **Параметр `healersOnly` (433) и функция-член `healerOnly(creep)` (World.kt:424)** различаются одной буквой и стоят в одной строке 672. При переименовании полей это первая строка, где ошибка не будет заметна.
3. **`val key` затеняет верхнеуровневую функцию `key(x, y)` (Facts.kt:92)** в строках 443, 559, 589 (`for ((key, p) in cells)` в `place`), 673, 1088, 1126. Сейчас компилируется, потому что `Int` не `invoke`-абелен; при переносе в класс с полем `key` это станет ошибкой или, хуже, сменит разрешение имени.
4. `goal` (517) — локальное; у объекта есть поле `goalField` (PainAndGain.kt:144). Имена разные, но при механическом переименовании «goal → goalField» получится перезапись поля.
5. `intent` — параметр; `intentOf(c)` (751) возвращает именно его и игнорирует `c`. Это единственная точка, где портфельный поиск «замысел на крипа» схлопнут в общий замысел.
6. `danOf(c, key)` (515) **игнорирует параметр `c`** — возвращает `InfluenceMap.dangerAt(key)`. Параметр несёт только смысл «раньше здесь была адресная опасность»; удалять его при переносе нельзя механически (много мест вызова), но и полагаться на зависимость от крипа тоже нельзя.

### `var`, захваченные замыканием

7. **`passTag` (566) читается только внутри `place` на строке 661** и меняется восемью присваиваниями между проходами. При превращении `place` в метод и `passTag` в поле поведение сохранится, но **любая попытка передать `passTag` параметром обязана учесть, что в проходе `place` вызывается рекурсивно, а счёт идёт только при `depth == 0`**.
8. **`addrFor` (480) + четыре массива `addrRH/RA/MH/MA`** — ручное кеширование «на крипа»: `addressedAt` зовёт `addrPrepare`, когда `addrFor != c.id`. Массивы переиспользуются между крипами. Если `addressedAt` станет методом с локальными массивами, прибор `adr*` изменится (сейчас он считает с тем кешем, который остался от предыдущего крипа, если id совпал).
9. **`maxV` (762) читается внутри `sagAt`** (770–771). `sagAt` объявлена НИЖЕ цикла, который `maxV` наполняет, поэтому сейчас порядок верный; при переносе `sagAt` в метод класса, а `maxV` в поле, метод станет вызываемым и ДО наполнения.

### Порядок побочных эффектов

10. **`Formation.fist` (449) мутирует `cells`** — это не «вернуть якорь», а «сузить кандидатов». Любая перестановка вызова относительно 440–447 или относительно первого чтения `cells` меняет всю раздачу.
11. **`InfluenceMap.clearClaim()` на 760 стирает притязания проходов retreat и straggler.** То есть притязания НЕ непрерывны по всей функции: первые два прохода не видят взаимных притязаний вовсе (поле осталось от предыдущего вызова/предыдущего замысла), а с 760 счёт начинается с нуля. Это ровно то место, где «механический перенос» может случайно починить или сломать поведение.
12. **`InfluenceMap.saturateHeal` внутри цикла лекарей (1047, 1057)** делает проход лекарей зависимым от порядка `healers`. Порядок — это порядок `fighters`, то есть порядок исходного `army`.
13. **Снятие приказа не симметрично его выдаче.** В `place` при `rescue` (633) снимаются `out` и `taken`, но НЕ снимается `InfluenceMap.addClaim` и НЕ восстанавливается `allyOf` (удалённый на 660). То же в проходе pinned (1169): `out.remove` + `taken.remove` без отката притязания и `allyOf`. Любое «наведение порядка» здесь изменит оценки соседних клеток.
14. **`allyOf.remove(c.key)` на 660** снимает крипа из карты жильцов, как только он назначен на другую клетку. Дальнейшие проходы видят его старую клетку как пустую, хотя `taken` про неё ничего не говорит.
15. **Безымянные вставки исполняются под чужим `passTag`**: блок `advancingWards` (985–998) — под `"ranged"`, хранитель флага (1122–1128) — под `"healer"`, заморозка бойцов (672–675) — под `"-"`. Ни одна из трёх не зовёт `place`, поэтому в `passCount` не попадает; если при разрезании они станут методами, зовущими `place`, прибор `pass=` поменяет смысл молча.

### Метки и вложенные `run`

16. **`run { … }` на 1079–1118** (зонд `hpick`) содержит три `return@run` (1080 — `out[c.id] ?: return@run`, 1103, 1114). При вынесении блока в метод все три обязаны стать `return`, а не `return@…`, и метод обязан остаться ВНУТРИ тела цикла по лекарям (он читает `c`, `b`, `taken`, `allyOf`, `out` в их текущем состоянии).
17. **`return@count false` на 1185** — внутри `healers.count { … }`; при вынесении лямбды это тоже `return`.
18. `place` имеет два параметра со значениями по умолчанию и **одно место вызова с именованными аргументами в переставленном порядке** — 708: `place(c, { true }, rescue = true, rank = { … })`. Механическая смена сигнатуры (например, вынос `depth`/`rescue` в отдельные методы) сломает именно эту строку.
19. `strikes(p)` (1164) объявлена ВНУТРИ тела цикла и захватывает `near` и `ours`, которые пересчитываются на каждой итерации (`ours` — из `cellOf(f)`, то есть из ТЕКУЩЕГО плана). Вынос в метод требует передать оба явно.

### Несколько вызовов за тик и «пробный/окончательный»

20. **Функция зовётся 1–6 раз за тик** (`Strategist.kt`): 1410 — один раз при `cpuTight` (`Intent.PRESS`); 1433–1443 — в цикле по `Intent.values()` (до пяти вызовов, каждый в СВОЙ `trial`-словарь, с ранним `break` по бюджету CPU на 1434); 1531 — отдельный вызов с `healersOnly = true, Intent.HOLD`.
21. **Пробный и окончательный вызовы в теле функции НЕ различаются ничем** — у функции нет признака «это проба». Отсюда следствия, которые перенос обязан сохранить:
    - все приборы (`passCount`, `goalDecisions`, `goalFlips`, `adr*`, `gateLevels`, `gateFell`, `fallReach`, `fallAny`, `rotfMeet`, `hfire*`, `hadj*`, `hp*`) копятся **по разу на КАЖДЫЙ вызов**, то есть до шести раз за тик, а не по разу на принятый план;
    - поля `plan*` (1179–1188) **перезаписываются**, поэтому хранят числа последнего вызова, а не выбранного плана;
    - `InfluenceMap.claim`, `needLeft`, `attHeal`, `inFire`, `advancingWards` после выхода остаются от **последнего** оценённого замысла, который, вообще говоря, не тот, чей план выбран (`bestPlan`). Всё, что читает эти поля после командира, читает чужой замысел;
    - `ensureGoalField` кеширован по тику: первый вызов строит поле, остальные его переиспользуют, поэтому пять «одинаковых» вызовов не одинаковы по вычислению (но одинаковы по результату поля).
22. `USE_HEAL_EXACT_IN_FIRE` сейчас `false` (Tuning.kt:511), поэтому тело 986–998 не исполняется никогда, а `advancingWards` всегда пуст. Перенос не должен «оживить» блок.

### Мёртвое и неиспользуемое (переносить как есть, не «чинить»)

23. `allyAt` (527–528) наполняется и **никогда не читается**.
24. `ax`, `ay` (449) **никогда не читаются**.
25. `val ok` на **957** (проход ranged) и на **1056** (проход healer) **не читаются**: решение принимается по `c.id !in out` (976, 1060). При этом на 1056 выражение `met || placeScored(...)` полагается на **короткое замыкание**: если встреча удалась (`met == true`), `placeScored` НЕ вызывается, и лекарь не переоценивается. Вычислять оба операнда заранее — молчаливое изменение поведения.
26. `val keptReach` (977) — `USE_RANGED_FALLBACK_KEEPS_REACH && place(...)`: тоже короткое замыкание, при `false` у константы `place` не вызывался бы вовсе.
27. `weakestMelee` (729) и `weakestFoe` (792) вычисляются одним и тем же выражением `armedEnemies.minByOrNull { it.hits }`; в 923 они выбираются по роли, хотя значение одно и то же.

### Чего я не проверял до конца

28. Строка 633: `taken.remove(tenant.key)` снимает ключ **нынешней** клетки жильца, а `undo = out.remove(tenant.id)` возвращает его **назначенную** клетку. По предикату выбора жильца (592–594) эти две клетки совпадают, когда приказ есть; когда приказа нет (`t.id !in out`), `undo` равен `null`, а `taken.remove` всё равно снимает ключ — я не проверял, может ли этот ключ в этот момент принадлежать ДРУГОМУ крипу (цепочка глубины 2+). Если да, это уже сегодняшний дефект, а не следствие переноса.
29. Порядок `sym(2)` / `sym(1)` зависит от глобального `mirrorTL` (World.kt:637), поэтому порядок обхода кандидатных клеток и соседей — не лексикографический и меняется от стороны карты. Все места, где «при равных оценках решает порядок перебора» (618, 619), от этого зависят; я не проверял, устойчив ли выбор при равенстве оценок к смене стороны.

---

# Карта секций `PainAndGain.armyStrategy`

Файл: `starter/src/jsMain/kotlin/season4/painandgain/Strategist.kt`
Границы функции: **строки 1911–2623** (сигнатура на 1911, `= with(seg) {`; закрывающая `}` на 2623).
`ArmyStrategyIn` — 1858–1887, `ArmyStrategyOut` — 1889–1909, KDoc сегмента — 1857.

Терминология ниже: **«член»** = свойство объекта `PainAndGain` (`PainAndGain.kt:91 object PainAndGain`);
**«глобал»** = top-level `internal var` в `Strategist.kt` / `Instruments.kt` (синтаксически неотличим от члена
внутри extension-функции, но жить будет в другом месте при переносе в класс); **«Memory»** =
`internal object Memory` (`Memory.kt:34`).

---

## 1. Вход и выход

### `ArmyStrategyIn` (1858–1887), 28 полей — читаются голым именем через `with(seg)`

| Поле | Тип |
|---|---|
| `army` | `List<Creep>` |
| `enemyCreeps` | `List<Creep>` |
| `combatEnemies` | `List<Creep>` |
| `strikers` | `List<Creep>` |
| `armedEnemies` | `List<Creep>` |
| `enemyMassedNow` | `Boolean` |
| `now` | `Int` |
| `exchangeLedger` | `Int` |
| `exchangeLive` | `Boolean` |
| `exchangePaying` | `Boolean` |
| `mobileArmy` | `List<Creep>` |
| `chasers` | `List<Creep>` |
| `huntable` | `List<Creep>` |
| `meleeAdjacent` | `Boolean` |
| `exchangeRecent` | `Boolean` |
| `fightOn` | `Boolean` |
| `cornered` | `Boolean` |
| `stalled` | `Boolean` |
| `ours` | `Double` |
| `theirs` | `Double` |
| `theirsUp` | `Double` |
| `theirsDown` | `Double` |
| `enemyNear` | `Boolean` |
| `massCentroid` | `Position` |
| `massArmy` | `List<Creep>` |
| `contact` | `Boolean` |
| `breakOffNow` | `Boolean` |
| `retreatFeasible` | `Boolean` |

Однократно читаемые поля входа (важно при разрезании — они «привязывают» секцию):
`enemyMassedNow` → только 2469 (`fewFoes`); `exchangeLedger` → только 1970 (`ledgerOk`);
`exchangePaying` → только 2213 (`pushRaw`); `cornered` → только 2271 (`warmNow`);
`meleeAdjacent` → только 2271; `exchangeLive` → только 2271; `fightOn` → только 1965 (`holdingFlag`);
`exchangeRecent` → только 2146; `breakOffNow` → только 2248; `huntable` → 2213 и печать 2543.

### `ArmyStrategyOut` (1889–1909), 19 полей

| Поле выхода | Тип | Откуда |
|---|---|---|
| `sweep` | `Boolean` | локальная `sweep`, 1978 |
| `gathered` | `Boolean` | локальная `gathered`, 2025 |
| `leadHolds` | `Boolean` | локальная `leadHolds`, 2208 |
| `hisStill` | `Boolean` | локальная `hisStill`, 2267 |
| `warmNow` | `Boolean` | локальная `warmNow`, 2271 |
| `holdingSpot` | `Boolean` | локальная `holdingSpot`, 2300 |
| `objective` | `Objective?` | локальная `objective`, 2365 |
| `evadeTo` | `Position?` | локальная `evadeTo`, 2393 |
| `combatArmy` | `List<Creep>` | локальная `combatArmy`, 2434 |
| `theirMeleeIn` | `Boolean` | локальная `theirMeleeIn`, 2438 |
| `underTheirFire` | `Boolean` | локальная `underTheirFire`, 2439 |
| `enemyRetreating` | `Boolean` | локальная `enemyRetreating`, 2453 |
| `decision` | `Strategist.Decision` | локальная `decision`, 2464 |
| `retreatTo` | `Position?` | локальная `retreatTo`, 2526 |
| `post` | `Position` | локальная `post`, 2539 |
| `cmdWhyNow` | `String` | **чистый проброс** `decision.cmdWhy` (2564) — уже доступен через `decision` |
| `centroid` | `Position` | **чистый проброс** `ctx.ourCentroid` (2567) — не зависит ни от чего в функции |
| `threat` | `Creep?` | локальная `threat`, 2571 |
| `raider` | `Creep?` | локальная `raider`, 2575 |

**Ни одно поле выхода не является пробросом поля `seg`.** Два поля (`cmdWhyNow`, `centroid`) —
пробросы чужих структур без вычисления.

Важно: многие ЗНАЧИМЫЕ результаты функция отдаёт не через `ArmyStrategyOut`, а записью в члены/глобалы:
`posture`, `postureSince`, `cmdMode`, `pushing`, `huntingThreat`, `objectiveFlagId`, `retreatTarget`,
`interceptFlagId`, `stalemateNow`, `farmerQuietNow`, `scatteredLatched`, `approachRate`,
`evadeTarget`, `Memory.detachedIds` и др. Список — в таблице секций.

---

## 2. Таблица секций в порядке исполнения

Колонки: строки | что решает | объявляет (локальные) | читает из объявленного выше | пишет вне себя | ранние выходы.

| # | Строки | Что решает | Объявляет | Читает выше-объявленное | Пишет вне себя | Ранние выходы |
|---|---|---|---|---|---|---|
| S1 | 1912–1933 | Стая боя: кто из его боевых успевает прийти к нашей массе, и мощи по этой стае | `fightPack` val; внутри `run`: `flow`, `arrival`, `pack` val, `t0` val, `limit` **var**; `fightAll` val 1931, `oursFight` val 1932, `theirsFight` val 1933 | — (только `seg` и `ctx`) | **глобал `fightPackIds` = … (1930)** | `break` 1923 — выход из `for` внутри `run`, значение `pack` |
| S2 | 1934–1956 | Стая наступления (бой у ЕГО головы) и мощи по ней | `pushPack` val; внутри `run`: `head`, `toHead`, `ourTravel`, `arrival`, `pack`, `limit` **var**; `pushAll` 1954, `oursPush` 1955, `theirsPush` 1956 | `fightPack` | — | `return@run fightPack` 1940 и 1942; `break` 1947 |
| S3 | 1957–1979 | Пороги наступления и признак «слабее»; зачистка | `weaker` 1957, `stalemate` 1963, `holdingFlag` 1965, `ledgerOk` 1970, `flagRaises` 1972, `pushRatio` 1973, `pushRelease` 1974, `sweep` 1978 | `oursFight`, `theirsFight` | **член `stalemateNow` = stalemate (1964)** | нет |
| S4 | 1980–1991 | Флаг перехвата (липкий): не его флаг, к которому мы успеваем раньше фермера | `interceptFlag` val; внутри лямбд: `group`, `flow`, **`ours`** (Int) | — | **глобал `interceptFlagId` = interceptFlag?.id (1991)** | нет |
| S5 | 1992–2016 | Ярлыки фермера: сухость погони, тишина, проигранная гонка | `chaseDry` 2008, `quiet` 2009, `quietSinceFirstReach` 2014, **`lostRaceNow`** val 2016 | — | **член `farmerQuietNow` (2015)** | нет |
| S6 | 2017–2029 | Россыпь его вооружённых, её гистерезис, крупнейшая группа | `largestGroup` 2022, `scatteredRaw` 2023, `gathered` 2025, `scattered` 2027, `groupSeed` 2028, `largestMembers` 2029 | — | **глобал `scatteredLatched` = scatteredRaw (2026)** | нет |
| S7 | 2030–2056 | Дебют-гонка: свободные флаги, пара слабейших бегунов, признак `raceNow` | `raceForce` 2034, `racePair` 2039, **fun `pairBeats`** 2041, `raceFree` 2046, `raceTargets` 2049, `raceSlots` 2050, `rest` 2054, `secondGroup` 2055, `raceNow` 2056 | `armedEnemies`, `largestMembers`, `scattered`, `raceTargets` | — (`detachedRunners` читает `Memory.detachedIds`) | `return` внутри `pairBeats` (2042, 2044) — только из неё |
| S8 | 2057–2062 | Сухая охота (мы стреляли, потом тишина, отстаём) | `dryHunt` 2060 | `scattered` | **глобал `lastNonHuntTick` = now (2057)**, если `posture` ∈ {FLAG, EVADE, RETREAT} | нет |
| S9 | 2063–2078 | Ярлык `farmer` и то, КАКИМ входом он держится | **fun `rangedMass`** 2063, `theirRangedMass` 2064, `quietChain` 2065, `meleeIdle` 2075, `farmer` 2076, `viaDryHunt` 2077, `viaRace` 2078 | `quiet`, `chaseDry`, `quietSinceFirstReach`, `lostRaceNow`, `dryHunt`, `raceNow` | — | нет |
| S10 | 2079–2093 | Отзыв отряда «без цели»: целиком и поштучно | `detachedBefore` 2079, `idle` 2089 | — | **`idleDetachTicks` (2081, 2084)**, **`Memory.detachedIds.clear()` (2084)**, **`detachRecallTick` (2084, 2092)**, **член `idleRunnerTicks` (2087 put, 2088 retainAll, 2092 remove)**, **`Memory.detachedIds.removeAll` (2092)**, `println` 2083, 2091 (под `DEBUG_LOG`) | нет |
| S11 | 2094–2117 | Опоры и пороги для ВЫПУСКА и ОТЗЫВА (разные!) | `packRef` 2110, `coreRef` 2111, `coreFloor` 2112, `recallGroup` 2115, `recallRef` 2116, `recallFloor` 2117 | `viaRace`, `viaDryHunt`, `largestMembers`, `scattered`; **глобал `fightPackIds`, записанный в S1** | **глобал `scatteredAtRelease` (2114)**, только когда `Memory.detachedIds.isEmpty()` | нет |
| S12 | 2118–2133 | Отзыв бегунов, пока ядро ниже порога мощи | `floorNow` 2120, `core` **var** 2121, `recalled` **var** 2122, `short` 2123, `back` 2128 | `farmer`, `recallRef`, `recallFloor`, `theirsDown` | **`coreShortTicks` (2124, 2131)**, **`Memory.detachedIds.remove` (2129)**, **`detachRecallTick` (2131)**, `println` 2132 | `?: break` 2128 — выход из `while` |
| S13 | 2134–2136 | Счётчик «не фермер» и полный сброс отряда | — | `farmer` | **`farmerOffTicks` (2134)**, **`Memory.detachedIds.clear()` (2136)** | нет |
| S14 | 2137–2184 | ВЫПУСК отряда (ветка `else if` к 2136) | `armed` 2148, `unmanned` 2150, `pool` 2151, `remaining` **var** 2153, `holdingDet` 2155; в цикле: `without` 2159, `theirsVsCore` 2163, `target` 2174, `oursAfter`/`theirsAfter` 2177 | `viaDryHunt`, `viaRace`, `meleeIdle`, `raceSlots`, `raceNow`, `scattered`, `coreRef`, `coreFloor`, `theirRangedMass`, `largestMembers`, `theirsUp`, `detachRecallTick`, `fightOnNow` | **`Memory.detachedIds.add` (2180)**, **`splitAll++`, `splitFight++` (2181)** | `break` ×6: 2157, 2158, 2164, 2166, 2168, 2178 — все из `for (c in pool)` |
| S15 | 2185–2186 | Печать трассы отряда | — | `detachedBefore`, `farmer`, `dryHunt`, `raceNow`, `raceTargets`, `largestGroup` | `println` (под `DEBUG_LOG`) | нет |
| S16 | 2187–2200 | Вето погони и страховка CPU | `interceptDenies` 2187, `cpuGuardArmy` 2196, **`dryNow` 2198 (мёртвая — не читается нигде)**, `chaseVeto` 2200 | `interceptFlag` | `println` 2197; **`cpuMark("a.sweep")` 2199 → пишет `cpuPhases`** | нет |
| S17 | 2201–2210 | «Отрыв держит»: впереди по счёту — толчка нет | `leadHolds` 2208 | — | `println` 2209; **глобал `leadHoldsWas` (2210)** | нет |
| S18 | 2211–2242 | Сырое условие наступления и «беззубый остаток» | `pushRaw` 2213, `nearFoes` 2238, `toothless` 2239 | `sweep`, `chaseVeto`, `oursPush`, `theirsPush`, `pushRelease`, `pushRatio`, `leadHolds` | **`pushHeld = false` (2242)** | нет |
| S19 | 2243–2257 | **Решение о наступлении** (`pushing`) | — | `breakOffNow`, `pushRaw`, `toothless`, `stalled`, `fightOnNow`, `oursPush`, `theirsPush`, `pushRelease` | **член `pushing` (2248, 2249)**, **глобал `pushSince` (2250)**, **`pushToothless++` (2251)**, **`pushHeld = true` (2251, 2253)**, **`pushHeldTicks++` (2253)**, **`pushTicks++` (2257)** | нет |
| S20 | 2258–2284 | Неподвижность его центра, «тёплый контакт», бой по контакту | `hisStill` 2267 (внутри `run`: `a`, `b`), `warmNow` 2271, `hotContact` 2272, `contactFight` 2284 | `stalled`, `contact`, `exchangeLive`, `meleeAdjacent`, `cornered` | — (только читает `Memory.hisCentHist`) | нет |
| S21 | 2285–2304 | Местный очаг: вето «сперва тушим здесь» | `spotFoes` 2295, `holdingSpot` 2300 | `oursFight`, `theirsFight`, `contact` | **`spotHoldAll++` (2302)**, **`spotHoldNew++` (2303)** | нет |
| S22 | 2305–2318 | Боевая постура `annihilate` и приборы тёплого контакта | `annihilate` 2309 | `pushing` (новое), `contactFight`, `holdingSpot`, `warmNow`, `enemyNear`, `stalled` | **`warmContact++`, `warmTicks++` (2314)**, **`warmAnnAll++` (2316)**, **`warmAnn++`, `warmHold++` (2317)** | нет |
| S23 | 2319–2336 | «Нас преследуют» и «нужен выход» | `hunted` 2334, `escapeNeeded` 2336 | `oursFight`, `theirsFight` | — | нет |
| S24 | 2337–2348 | Истории центроидов, темп сближения, пересчёт полей выхода | внутри `run`: `ec`, `ac` | `escapeNeeded`, `cpuGuardArmy` | **`Memory.enemyDistHist` (2341)**, **`Memory.hisCentHist` (2343)**, **`Memory.ourCentHist` (2344)**, **член `approachRate` (2345)**; 2347 — либо `refreshEscape` (пишет `escapeFlows`/`escapeTheirs`/`escapeNearest`), либо `escapeFlows.clear()`, `escapeTheirs.clear()`, `escapeNearest.clear()`, **`evadeLeft = null`**; **`cpuMark("a.escape")` 2348** | нет |
| S25 | 2349–2351 | Уклонение ДО целей, если он близко | `enemyClose` 2350, `evadeFirst` 2351 | `hunted`, `annihilate`, `contact` | через `evadePoint`: **`evadeTarget`, `evadeEvaluatedAt`, `evadeLeft`**, `println` (Strategist.kt:721–781) | нет |
| S26 | 2352–2365 | Линия против близкого врага; цель-флаг | `holdLine` 2359, `interceptObjective` 2360 (внутри `group`, `flow`), `objective` 2365 | `pushing`, `annihilate`, `farmerQuietNow`(член, записан в S5), `interceptFlag`, `evadeFirst`, `pushRatio`, `hunted`, `cpuGuardArmy`, `objectiveFlagId` (СТАРОЕ) | — | нет |
| S27 | 2366–2381 | Прибор «почему у армии нет флаг-цели» | `why` 2372 (внутри `if`) | `objective`, `annihilate`, `evadeFirst`, `holdLine` | **член `objNone[why]` (2378)**, **`objAll++` (2380)**, **`cpuMark("a.obj")` (2381)** | нет |
| S28 | 2382–2394 | Точка уклонения | `rushFar` 2387, `evadeTo` 2393 | `oursFight`, `theirsFight`, `hunted`, `annihilate`, `contact`, `objective`, `evadeFirst` | возможен ВТОРОЙ вызов `evadePoint` (те же записи, что в S25); **`cpuMark("a.evade")` (2394)** | нет |
| S29 | 2395–2415 | Прибор дельты прогноза (поведение не меняет) | `simdFoeCentroid` 2403; внутри `if`: `base`, `next`, `delta`, `powerSaysNo` | — | **`simdSum +=`, `simdTicks++` (2409)**, **`simdPos++` (2410)**, **`Memory.simdShare` (2411)**, **`simdDisagree++` (2414)** | нет |
| S30 | 2416–2427 | Прибор разлёта армии | внутри `if`: `live`, `cx`, `cy`, `r` | `contact` | **`radSum +=`, `radTicks++` (2423)**, **`radMax` (2424)**, **`radWide++` (2425)** | нет |
| S31 | 2428–2431 | Признак уклонения и признак отхода | `evade` 2428, `retreat` 2431 | `evadeTo`, `annihilate`, `objective`, `enemyNear`, `weaker`, `retreatFeasible` | **член `evadeTarget = null` (2429)**, если `!evade` | нет |
| S32 | 2432–2455 | Меры для решения о режиме командира; «он отходит» | `combatArmy` 2434, `theirMeleeIn` 2438, `underTheirFire` 2439, `retreatByDistance` 2440, `retreatByHisStep` 2443 (внутри `o`,`a`,`b`,`op`), `enemyRetreating` 2453, `foesAtHand` 2455 | `combatEnemies`, `armedEnemies`, `massArmy`; **`Memory.enemyDistHist`/`hisCentHist`/`ourCentHist` ПОСЛЕ записи в S24** | **`rtrOld++`, `rtrRemoved++`, `rtrAdded++` (2448)** | нет |
| S33 | 2456–2506 | **Одно решение о состоянии армии**: `Strategist.decide` | `decision` 2464 | `annihilate`, `objective`, `evade`, `retreat`, `contact`, `armedEnemies`, `massArmy`, `stalledNow`(член), `enemyRetreating`, `underTheirFire`, `theirMeleeIn`, `outmatchedTicks`(член), `pushing`(новое), `enemyMassedNow`, `foesAtHand`, `enemyMassedSignal`(член), **`Memory.simdShare` — записан в S29**, `posture`(СТАРОЕ), `postureSince`(СТАРОЕ), `Memory.postureCandidate`, `Memory.candidateSince`, `Memory.armyPrev`, `flagFlipNow`(член), `getTicks()` | **`Memory.contactPrev` (2502)**, **`Memory.armyPrev` (2503)**, **`Memory.postureCandidate` (2504)**, **`Memory.candidateSince` (2505)**, **`stateEventTicks++` (2506)** | нет |
| S34 | 2507–2517 | Разбор решения; сохранение цели-флага | `newPosture` 2509, `postureTakes` 2510, `newFlagId` 2516 | `decision`, `objective` | **член `objectiveFlagId` (2517)** — только если `newFlagId != null \|\| postureTakes` | нет |
| S35 | 2518–2526 | Точка отхода по постуре, которая БУДЕТ принята | `postureNow` 2524, `retreatTo` 2526 | `postureTakes`, `newPosture`, `posture`(СТАРОЕ) | **член `retreatTarget = null` (2525)**, если `postureNow != RETREAT`; `retreatPoint(ctx)` (2526) — его побочные эффекты см. Strategist.kt:617 | нет |
| S36 | 2527–2539 | Точка поста: свой флаг под ногами → флаг перехвата → геометрический пост | `standingFlag` 2533, `post` 2539 | `enemyNear`, `interceptFlag` | **`postAll++` (2538)** | нет |
| S37 | 2540–2546 | Печать постуры | `postureKey` 2540 | `newPosture`, `objectiveFlagId`(новое), `ours`, `theirs`, `pushing`(новое), `objective`, `retreatTo`, `evadeTo`, `post`, `approachRate`(новое), **`huntingThreat` (СТАРОЕ — запись в S40)**, `theirsFight`, `fightPack`, `fightAll`, `theirsPush`, `pushPack`, `pushAll` | **глобал `postureLogged` (2542)**, `println` 2543–2545 | нет |
| S38 | 2547–2564 | **Применение постуры и режима командира** | `cmdWhyNow` 2564 | `decision` | **член `posture` = decision.posturePre (2561)**, **глобал `postureSince` (2562)**, **член `cmdMode` (2563)** | нет |
| S39 | 2566–2583 | Общие цели: угроза и рейдер на нашей половине | `centroid` 2567, `ourHalfCombat` 2568, `ourHalfSoft` 2569, **fun `arrivalOf`** 2570, `threat` 2571, `raider` 2575 (внутри `field`, `ourTravel`, `pack`) | `chasers`, `strikers`, `armedEnemies`, `enemyCreeps`, `combatEnemies` | — (`arrivalOf` читает член `arrivalById`) | нет |
| S40 | 2584–2593 | Охота на угрозу (с гистерезисом) | внутри `run`: `field`, `ourTravel`, `pack`, `o`, `t` | `threat`, `strikers`, `combatEnemies`; **`posture` — НОВОЕ значение из S38**; **`huntingThreat` — СТАРОЕ значение (2591)** | **член `huntingThreat` (2585)** | нет |
| S41 | 2594–2600 | Чистка по-крипных таблиц по живой армии | — | `army` | **`Memory.aggressiveIds`, `Memory.rallyingIds`, `Memory.engagingIds`, `Memory.holdSince`, `Memory.impatientIds`, `Memory.lastHits`, `Memory.lastCell` — `retainAll` (2594–2600)** | нет |
| S42 | 2602–2622 | Сборка `ArmyStrategyOut` | — | 19 локальных (см. раздел 1) | — | значение функции |

---

## 3. Локальные функции и лямбды, объявленные внутри

| Строка | Имя и параметры | Возвращает | Читает | Пишет |
|---|---|---|---|---|
| 1914–1929 | анонимный `run { … }` (инициализатор `fightPack`) | `ArrayList<Creep>` | `ctx`, `massCentroid`, `combatEnemies`, `strikers` | свои локальные `pack`, `limit` |
| 1939–1953 | анонимный `run { … }` (инициализатор `pushPack`) | `List<Creep>` | `combatEnemies`, `massArmy`, `strikers`, `fightPack`, `ctx` | свои локальные `pack`, `limit`; **два `return@run fightPack`** (1940, 1942) |
| 2041–2045 | **`fun pairBeats(f: FlagInfo): Boolean`** | `Boolean` | `racePair`, `armedEnemies`; `foesInEngage`, `ourPowerOf`, `enemyPowerOf` | ничего. **НИГДЕ НЕ ВЫЗЫВАЕТСЯ** (проверено grep по всему пакету) — мёртвая |
| 2063 | **`fun rangedMass(cs: List<Creep>): Double`** | `Double` | только параметр + `InfluenceMap.profileOf` | ничего. Вызывается 2064, 2166, 2168 |
| 2267–2270 | анонимный `run { … }` (правая часть `&&` в `hisStill`) | `Boolean` | `Memory.hisCentHist` (**до** обновления в S24) | ничего |
| 2339–2346 | **оператор-`run { … }`** (не инициализатор) | `Unit` | `armedEnemies`, `ctx.ourCentroid`, `Memory.*Hist` | `Memory.enemyDistHist`, `Memory.hisCentHist`, `Memory.ourCentHist`, `approachRate` |
| 2443–2447 | анонимный `run { … }` (правая часть `&&` в `retreatByHisStep`) | `Boolean` | `Memory.hisCentHist`, `Memory.ourCentHist` (**после** обновления) | ничего |
| 2570 | **`fun arrivalOf(c: Creep): Int`** | `Int` | член `arrivalById` | ничего. Вызывается только в компараторе 2571 |
| 2576–2583 | лямбда `?.takeIf { r -> … }` для `raider` | `Boolean` | `ctx`, `chasers`, `strikers`; `flowTo`, `pathTicks`, `packAt`, `catchable` | ничего |
| 2585–2593 | анонимный `run { … }` (правая часть `&&` в присваивании `huntingThreat`) | `Boolean` | `threat`, `strikers`, `combatEnemies`, **`huntingThreat` (старое, 2591)** | ничего |

Кроме этого — десятки обычных лямбд коллекций (`filter`/`map`/`minByOrNull`/…), которые ничего вне себя
не пишут; отдельно перечислены только те, что несут `return@…` или читают изменяемое состояние.

---

## 4. Цепочки «первое сработавшее условие выигрывает» vs последовательности

### 4.1 ЦЕПОЧКИ (кандидаты на таблицу именованных строк)

| Строки | Ветвей | Что выбирает | Теги/имена ветвей | Побочные эффекты в ветвях |
|---|---|---|---|---|
| **2372–2377** | **4** | строку-причину «почему нет флаг-цели» | **ЕСТЬ, строки: `"annihilate"`, `"evade"`, `"holdLine"`, `"gate"`** | нет; результат кладётся в `objNone` (2378) |
| **2249–2256** (`when`) + 2248 (`if (breakOffNow)`) | **4 + 1 перехват** | `pushing` — идти ли в наступление | нет строк; ветви различимы только по счётчикам: `pushSince`, `pushToothless`, `pushHeldTicks` | ветвь 1: `pushSince = now`; ветвь 2: `pushToothless++`, `pushHeld = true`; ветвь 3: `pushHeld = true`, `pushHeldTicks++`; ветвь 4 (`else`): ничего. Перехват 2248 (`breakOffNow`) НЕ трогает ни один счётчик и оставляет `pushHeld = false` |
| 1973 | **4** | `pushRatio` (число) | нет | нет |
| 1974 | **3** | `pushRelease` (число) | нет | нет |
| 1914 / 1939 | 2 / 2 | `fightPack` / `pushPack` | нет | нет |
| 1981 + 1984–1989 (`?:`) | 2 внешних + 2 внутренних (липкий → свежий) | `interceptFlag` | нет | нет |
| 2111 / 2112 / 2116 / 2117 | по 2 | `coreRef`, `coreFloor`, `recallRef`, `recallFloor` — какая опора и какой порог | нет; различитель — `viaDryHunt`/`viaRace`/`recallGroup` | нет |
| 2136 → 2145 (`if (!farmer) … else if (…)`) | 2 | сбросить отряд или ВЫПУСТИТЬ | нет | ветвь 1: `Memory.detachedIds.clear()`; ветвь 2: весь блок выпуска |
| 2151–2152 | 2 | порядок `pool` (кого отряжать первым) | нет | нет |
| **2156–2183** (guard-лестница в `for`) | **6 `break`-ворот** | до кого доотряжать | нет строк; ворота: «хватает флагов» (2157), «лимит гонки» (2158), «ядро без оружия / ниже порога» (2164), «стрелковая защита сухой охоты» (2166), «стрелковая защита гонки» (2168), «мощь после дебаффа цели» (2178) | `Memory.detachedIds.add`, `splitAll++`, `splitFight++` — только когда ни одни ворота не сработали |
| 2347 | 3 исхода (`refresh` / `clear` / **ничего**) | обновлять ли поля выхода | нет | `refreshEscape` или четыре очистки + `evadeLeft = null`. Средний случай (`escapeNeeded && cpuGuardArmy && escapeFlows.isNotEmpty()`) не делает НИЧЕГО — это молчаливая третья ветвь |
| 2365 | 3 исхода (null-вето → перехват → `chooseFlagObjective`) | `objective` | нет (теги — в S27) | нет |
| 2393 (`?:` + `if`) | 3 исхода | `evadeTo` | нет | второй вызов `evadePoint` пишет `evadeTarget`/`evadeLeft` |
| 2448 | 3 | какой счётчик «отходит» увеличить | нет | `rtrOld++`/`rtrRemoved++`/`rtrAdded++` |
| 2524 / 2526 / 2533 | по 2 | `postureNow`, `retreatTo`, `standingFlag` | нет | 2525: `retreatTarget = null` |
| **2539** (`?:` … `if … else`) | **3** | точка поста: свой флаг под ногами → флаг перехвата → `postPoint(ctx)` | нет | нет |
| 2591 | 2 | порог охоты (гистерезис) | нет | нет |
| 2081 | 2 | `idleDetachTicks++` или `= 0` | нет | запись глобала |

⚠️ Главная постурная цепочка — `when { annihilate → ANNIHILATE; hasObjective → FLAG; evade → EVADE; retreat → RETREAT; else → HOLD }` — **находится НЕ здесь**, а в `Strategist.decide` (Strategist.kt:97–103).
Внутри `armyStrategy` строки 2464–2501 — это СБОРКА аргументов, то есть последовательность, а не цепочка.

### 4.2 ПОСЛЕДОВАТЕЛЬНОСТИ независимых шагов (таблицы не заслуживают)

- S1+S2 (1912–1956) — два независимых расчёта стай; `pushPack` лишь падает обратно на `fightPack`.
- S5–S9 (2008–2078) — набор независимых ярлыков; «цепочкой» они становятся только в дизъюнкции 2076.
- S10, S12, S13, S14 — четыре РАЗНЫХ действия над `Memory.detachedIds` подряд, каждое со своим условием; между ними нет «первое выигрывает», кроме пары 2136/2145.
- S21, S22, S29, S30, S27, S32 (частично) — блоки приборов: чистые счётчики, побочные эффекты и ничего больше.
- S24 (2339–2346) — три независимых обновления истории плюс `approachRate`.
- S33 (2464–2501) — сборка `Strategist.Inputs`: ~15 независимых аргументов.
- S39 (2567–2583) — `threat` и `raider` считаются независимо друг от друга.
- S41 (2594–2600) — семь одинаковых по форме `retainAll`, между собой независимых.
- S42 — конструктор выхода.

---

## 5. Ветви, читающие настоящее время (`cpuMs()` / `CPU_GUARD_MS`)

`cpuMs()` — `Instruments.kt:56` (`getCpuTime() / 1_000_000.0`), `CPU_GUARD_MS = 50.0` — `Tuning.kt:676`.

| Строка | Что делает |
|---|---|
| **2196** | `val cpuGuardArmy = now > 1 && cpuMs() > CPU_GUARD_MS` — **единственное место, где время читается в решение**. Один вызов, значение зафиксировано в локальной. |
| 2197 | `println("cpu t=$now guard: posture keeps the objective (…)")` под `DEBUG_LOG`; **второй и третий вызов `cpuMs()`** внутри строки формата — значение другое, чем в 2196 (только печать). |
| **2347** | `if (escapeNeeded && !(cpuGuardArmy && escapeFlows.isNotEmpty())) refreshEscape(ctx, armedEnemies)` — при взведённой страховке и уже посчитанных полях **пропускается пересчёт полей выхода** (`escapeFlows`/`escapeTheirs`/`escapeNearest` остаются с прошлого тика). |
| **2365** | последний аргумент `chooseFlagObjective(…, if (cpuGuardArmy) objectiveFlagId else null)` — параметр `onlyFlagId` (Strategist.kt:553): **оценивается только ТЕКУЩАЯ цель-флаг**, перебор по карте пропускается. |

`cpuMark("a.sweep"/"a.escape"/"a.obj"/"a.evade")` (2199, 2348, 2381, 2394) тоже зовёт `cpuMs()`, но это
только запись в `cpuPhases` — решений не меняет. Других мест, зависящих от часов, в функции нет.

---

## 6. Ловушки для переноса

### 6.1 Затенение (shadowing)

1. **`lostRaceNow`** — на 2016 объявляется `val lostRaceNow: Boolean`, а на **1972 вызывается ФУНКЦИЯ
   `lostRaceNow()`** (`PainAndGain.lostRaceNow()`, Strategist.kt:480). Разные вещи с одним именем в одном
   теле. После 2016 имя `lostRaceNow` означает локальный Boolean (2065). Если поднять локальную в поле
   класса, вызов 1972 либо сломается, либо (хуже) молча начнёт означать другое.
2. **`ours`** — поле входа `seg.ours: Double`, но на **1987** внутри лямбды `interceptFlag` объявлен
   `val ours = group.maxOfOrNull { pathTicks(...) } ?: 0` типа **`Int`**. Внутри этой лямбды `ours` — время
   пути, а не мощь. Печать на 2543 (`our=${ours.toInt()}`) читает уже поле входа.
3. **`now`** — поле входа `seg.now: Int` (приходит из `armyMeasures`, где `now = getTicks()`,
   World.kt:703). В той же функции семь мест зовут **`getTicks()` напрямую** (2495, 2540, 2541, 2543, 2544
   и косвенно `evadePoint`/`retreatPoint`). Сегодня это одно и то же число; при разнесении по методам
   два разных выражения одного времени легко разойдутся.
4. **`escapeNeeded`** — локальная на 2336, а **параметр `chooseFlagObjective` тоже называется
   `escapeNeeded`** (Strategist.kt:553) и на 2365 в него передаётся **`hunted`**, НЕ локальная
   `escapeNeeded`. Позиционный вызов; при переписывании на именованные аргументы легко «исправить» в
   локальную и изменить поведение.
5. **`stalemateNow`** — член `PainAndGain` (PainAndGain.kt:333), пишется на 1964; рядом в файле есть
   локальная с тем же именем в другой функции (Strategist.kt:1679). Имя не уникально по файлу.
6. **`gathered`, `sweep`, `leadHolds`, `hisStill`, `warmNow`, `holdingSpot`, `centroid`, `post`,
   `objective`, `decision`, `threat`, `raider`** — все совпадают с именами полей `ArmyStrategyOut` и с
   именами локальных в вызывающем `runArmy` (PainAndGain.kt:433–451). Конфликтов в именах верхнего
   уровня нет (проверено grep), но при переносе в класс-носитель одноимённое поле класса и локальная
   в методе дадут тихое затенение.
7. Внутри цикла выпуска (2159) `without` и снаружи `remaining` — `remaining` переприсваивается на 2182;
   `core` (2121) и `recalled` (2122) — то же самое в цикле отзыва.

### 6.2 `var`, меняемые в нескольких секциях

| Величина | Где меняется | Замечание |
|---|---|---|
| **`pushing`** (член) | 2248, 2249–2256 | Но читается в **2213 (СТАРОЕ значение прошлого тика — выбор `pushRelease` vs `pushRatio`!)**, а после 2256 — новое (2303, 2309, 2317, 2359, 2468, 2543). Разрез между S18 и S19 обязан сохранить это различие. |
| **`posture`** (член) | 2561 | Читается СТАРЫМ на 1957, 2057, 2495, 2524; НОВЫМ — на 2585. |
| **`postureSince`** (глобал) | 2562 | Читается старым на 2496. |
| **`huntingThreat`** (член) | 2585 | Читается СТАРЫМ на 2543 (печать) и **внутри собственного присваивания на 2591** (гистерезис: правая часть вычисляется до записи). |
| **`objectiveFlagId`** (член) | 2517 (условно) | Читается СТАРЫМ на 2365 (под `cpuGuardArmy`), НОВЫМ — на 2540. |
| **`interceptFlagId`** (глобал) | 1991 | Читается СТАРЫМ на 1984 (липкость). |
| **`scatteredLatched`** (глобал) | 2026 | Читается на 2027 в той же секции — сразу после записи. |
| **`Memory.detachedIds`** | 2084, 2092, 2129, 2136, 2180 | Пять мест мутации в пяти разных секциях; читается на 2065, 2079, 2114, 2119, 2125, 2155, 2157, 2158 и косвенно через `detachedRunners`/`notDetached` (World.kt:455–456). Порядок мутаций — часть поведения. |
| **`detachRecallTick`** (глобал) | 2084, 2092, 2131 | Читается на 2147 (гейт `DETACH_WINDOW`) — то есть ПОСЛЕ трёх возможных записей в том же тике. |
| **`coreShortTicks`** (глобал) | 2124, 2131 | Записывается дважды подряд в одной секции. |
| **`idleDetachTicks`** (глобал) | 2081, 2084 | То же. |
| **`pushHeld`** (глобал) | 2242 (=false), 2251, 2253 (=true) | Сбрасывается в S18, взводится в S19. Разнести по методам — потерять сброс. |
| **`evadeTarget`** (член) | внутри `evadePoint` (2351/2393), затем **2429 `= null`** | Функция может установить цель, а через 36 строк сама её обнулить. |
| **`evadeLeft`** (глобал) | 2347 (`= null` в ветке очистки) и внутри `evadePoint` | |
| `core`, `recalled`, `remaining`, `limit` | локальные `var` внутри одной секции | безопасны, если секция не режется пополам |

### 6.3 Порядок: читатели ДО записи в ту же таблицу

Самое опасное место карты.

1. **`Memory.hisCentHist`**: читается на **2267** (`hisStill`) — ДО того, как `run`-блок на **2343**
   добавит в неё центр ЭТОГО тика; и читается на **2443–2446** (`retreatByHisStep`) — уже ПОСЛЕ.
   Две ветви функции видят РАЗНЫЕ версии одной очереди. Перестановка секций меняет обе.
2. **`Memory.ourCentHist`**: пишется 2344, читается 2443. Только «после».
3. **`Memory.enemyDistHist`**: пишется 2341, читается 2440 (`retreatByDistance`). Только «после».
   Обратите внимание: `approachRate` (2345) считается по этой же очереди сразу после записи.
4. **`Memory.simdShare`**: пишется прибором на **2411**, и через 82 строки читается решением на
   **2493** (`enemyMassed` в `Strategist.Inputs`). Прибор v397, объявленный «поведение НЕ меняет»,
   фактически стоит НА ПУТИ решения через сглаженную долю. Перенести прибор ниже `decide` = изменить
   поведение на один тик задержки.
5. **`Memory.armyPrev`**: читается на 2501 (`event = ctx.myCreeps.size < Memory.armyPrev`), пишется на
   **2503** — в той же секции, сразу после. Между чтением и записью ничего нет, но разрез между 2501 и
   2503 обязан остаться в одном методе.
6. **`Memory.postureCandidate` / `Memory.candidateSince`**: читаются 2496–2497, пишутся 2504–2505.
7. **`escapeFlows`/`escapeTheirs`/`escapeNearest`**: пишутся `refreshEscape` на 2347, читаются
   `evadePoint` на 2351 и 2393. Перенос `refreshEscape` ниже уклонения = уклонение по полям прошлого тика.
8. **`fightPackIds`**: пишется на 1930 (S1), читается на 2110 (S11) — через 180 строк, внутри той же
   функции. Единственный «канал» между секциями, идущий через глобал, а не через локальную.
9. **`farmerQuietNow`**: пишется 2015, читается 2359 (`holdLine`).
10. **`approachRate`**: пишется 2345, читается 2543 (печать) и `evadePoint` (Strategist.kt:771, 778).
11. **`Memory.contactPrev`** пишется на 2502, но в этой функции НЕ читается вовсе — читатель снаружи.
12. **`Memory.aggressiveIds`/`rallyingIds`/`engagingIds`/`impatientIds`** (2594–2598) чистятся
    напрямую через backing-set, а не через соответствующие `Latch` (`Memory.kt:61, 64, 67, 84`).
    Если класс-носитель станет прогонять их через защёлки, поведение чистки может измениться —
    **я не проверял, что делает `Latch.retainAll`**, это требует отдельного чтения `Latch`.

### 6.4 Вложенные `run { … return@run … }`

- **1939–1953**, инициализатор `pushPack`: **два `return@run fightPack`** (1940 и 1942). При вынесении
  в метод оба обязаны стать `return fightPack` и метод обязан получить `fightPack` параметром.
- **1914–1929**, инициализатор `fightPack`: `return@run` нет, но есть `break` (1923) из `for`, и
  значением `run` является последнее выражение `pack`.
- **2339–2346** — `run` как ОПЕРАТОР (не инициализатор), его значение отбрасывается; единственная
  причина существования — ограничить область `ec`/`ac`. При вынесении в метод превращается в `Unit`-метод.
- **2267–2270**, **2443–2447**, **2585–2593** — `run` как правая часть `&&`: **вычисляется только при
  истинности левой части**. Если превратить их в безусловный вызов метода, побочных эффектов там нет,
  но вычислительная стоимость и (для 2591) чтение `huntingThreat` станут безусловными.
- **2042 и 2044** — `return` из локальной `fun pairBeats`, а не из `armyStrategy`.

### 6.5 Мёртвое, что не надо «оживлять» при переносе

- **`fun pairBeats` (2041–2045)** — не вызывается нигде в пакете.
- **`val secondGroup` (2055)** — вычисляется и не читается.
- **`val dryNow` (2198)** — вычисляется и не читается.
- `raceTargets` (2049) и `raceSlots` (2050) — два имени одного значения `raceFree.size`.

### 6.6 Прочее, что может сломаться молча

1. **2248 — `if (breakOffNow) { pushing = false } else pushing = when { … }`**: `if/else` оборачивает
   САМО ПРИСВАИВАНИЕ, а не значение. При `breakOffNow` цепочка `when` не вычисляется вовсе, поэтому
   `pushSince`, `pushToothless`, `pushHeldTicks` не двигаются, а `pushHeld` остаётся `false` от 2242.
   Переписать как `pushing = if (breakOffNow) false else when { … }` — значит вычислить `when` и
   изменить счётчики.
2. **`evadePoint` зовётся из двух мест (2351 и 2393)**, но за тик срабатывает максимум одно (elvis на
   2393 короткозамкнут). Функция НЕ чистая: пишет `evadeTarget`, `evadeEvaluatedAt`, `evadeLeft` и
   печатает (Strategist.kt:721–781). Если при разрезании оба вызова окажутся безусловными — два
   пересчёта и две печати за тик.
3. **Ветвь 2372–2377 обязана повторять порядок дизъюнкции 2365**: `annihilate` → `evadeFirst != null`
   → `holdLine`. Если превратить 2365 в таблицу, а 2377 оставить как есть, прибор `objNone` начнёт
   врать (не поведение, но диагностику).
4. **`cpuMark` расставлены между секциями** (2199, 2348, 2381, 2394) и делят функцию на фазы
   `a.sweep` / `a.escape` / `a.obj` / `a.evade`. Это готовая разметка границ — но границы фаз НЕ
   совпадают с границами логических секций (например `a.sweep` кончается до `leadHolds`, хотя
   наступление считается дальше).
5. **`DEBUG_LOG`** гейтит печати 2083, 2091, 2132, 2186, 2197, 2209, 2541 — но НЕ печать внутри
   `evadePoint` (2771/2778 там печатают безусловно, кроме одной под `DEBUG_LOG`).
6. **Блок выпуска отряда (2145–2184) — ветвь `else if` от 2136.** Его условие охватывает `!fightOnNow`,
   контакт, `exchangeRecent`, `meleeIdle` и окно `DETACH_WINDOW`. Вынести блок в метод, а условие
   оставить — легко потерять, что ветвь ИСКЛЮЧАЕТ ветвь сброса (2136).
7. `pushTicks++` (2257) стоит БЕЗУСЛОВНО после всего решения о наступлении — комментарий 2245–2247
   прямо говорит, что раньше был второй инкремент и прибор врал. Не продублировать при разрезании.
8. **Чего я не проверял**: что именно делает `Latch` (`Memory.kt`) и есть ли у `retainAll` на его
   backing-set другой смысл; побочные эффекты `retreatPoint`, `postPoint`, `chooseFlagObjective`,
   `refreshEscape`, `powerAfterFor`, `spotEdgeAt`, `captureAllowed` (в карте они учтены как «чёрные
   ящики, зовущиеся отсюда»); значения `ctx` (`Ctx` собирается ДО функции и внутри не меняется —
   это утверждение основано на отсутствии записей `ctx.…` в теле, отдельно не доказано).

---

