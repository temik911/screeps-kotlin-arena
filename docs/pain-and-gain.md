# Pain and Gain (season 4) — the bot's rules, history and tooling

Owner: the Pain and Gain session. Code: `starter/src/jsMain/kotlin/season4/painandgain/`, bindings
`types/src/jsMain/kotlin/screeps/api/season4/` (`ScoreFlag.kt`, `PainAndGainConstants.kt`), arena folder
`arenas/season4-pain_and_gain/`, stub harness `tools/stub/painandgain/`. Only this bot's session edits this file (see
the parallel-sessions rules in `CLAUDE.md`).

## Архитектура (итог переработок 13–14.09.2026, v263, и 19.09.2026, v441–v447)

Тик — конвейер стадий, каждая в своём файле пакета; решение, которое до переработки жило в пяти местах, лежит в одном
файле своей стадии. `PainAndGain.kt` держит `loop()`, то общее состояние, которое ещё не переехало к владельцам (с v454 — приборы, величины
одного тика и коллекции, которые чинит `AbortRepair`; межтиковые скаляры и константы лежат на верху файлов-владельцев) и
оркестровку: `tickBody` и `runArmy` — списки вызовов, стадия получает выходы
предыдущих целиком. План и история первой переработки — `docs/pain-and-gain-rework.md`, абзацы v236–v263 в `docs/pain-and-gain/history-v2xx.md`.

**Вторая переработка (19.09.2026, v441–v447, `docs/pain-and-gain-architecture.md`) дала каждому решению имя, которое служит
адресом, и счётчик, приходящий вместе с именем, — не изменив поведения ни на один интент** (каждый коммит под оракулом
тождества). Что это значит для того, кто правит бота:
- **словарь фактов** (`Facts.kt`): «чистый мили», «лекарь», «раздет», ключ клетки — по имени, определение одно; линт не даёт
  вернуть написание инлайном;
- **таблицы решений** (`Tables.kt`): лестница цели (27 строк), цепочка шага (8), ворота захвата (12), проходы раздачи (13),
  правила постуры, режима командира, его причины и наступления — списки именованных строк; тег из лога находится `grep`-ом в
  одной строке вместе с условием и действием, порядок списка — приоритет. Прибор `reach t=` считает по каждой строке «выиграла /
  условие истинно / перекрыта порядком» и включён в живой сборке; читать — `tools/series.py reach`;
- **контракты стадий без сантехники**: классов `…In` нет, поле объявлено один раз в `Out` владельца;
- **зависимости направлены вниз**, счётчик живёт у того, кто считает — оба правила держит гейт (`lint`, `graph`), а не память.

```
tickBody: Ctx (мир) → readSignals → runRunners → runArmy → TrafficManager.resolve → Arbiter.audit → Executor.run
          → cpuSummary → RememberTick → printTick
runArmy:  updateKeepers → ArmyMeasures (4 группы мер) → ArmyStrategy (восемь подстадий; Strategist.decide) → ArmyTargets (6) → ArmyStance (5) → armyBlock
          → armyCommand (Commander.kt) → orderAudit → healerWall
          → creepTurn для каждого бойца: Turn → ladder() → Stride → steps() → Proposal → submit
          → armyFireAndHeal
```

| файл | стадия | что в нём решается |
|---|---|---|
| `Facts.kt` | словарь фактов | `CreepFacts` — факты крипа за тик одним проходом по телу (живые и «рождённые» части, `armed`, `healerOnly`, `stripped`, `combatant`, `meleeOnlyBorn` / `meleeOnlyLive`), таблица `TickFacts` на тик, ключ клетки `key(x, y)` / `pos.key` |
| `World.kt` | мир и меры | носители `Ctx` (мир тика — прежний `buildWorld`), `ArmyMeasures`, `RememberTick`; сегмент `readSignals`; флаги с эффектами и счётом, поля потоков, признаки врага (ловим ли, стоит ли, грозит ли), путь и шаг бегства |
| `Types.kt`, `Geometry.kt`, `Clock.kt` | типы и службы нижних уровней | перечисления `Posture` / `CmdMode` / `Intent` и запись `ChaseSample`; обход и сторона карты в своей системе координат (`sym`, `mirrorTL`, `centroidOf`, `sgn`); часы тика `cpuMs` / `cpuMark` |
| `Power.kt` | модель мощи | `ourPowerOf`, `enemyPowerOf`, `fightTicks`, `fightCost`, Ланчестер — чистые функции над телами и профилями |
| `Forecast.kt` | прогноз | прокат `simulate` на `SIM_TICKS`, модель его выбора цели `wallTargetOf` / `fracTargetOf`, ошибка прогноза |
| `Strategist.kt` | стратег | одно решение о постуре и режиме `Strategist.decide` (смена — когда кандидат устоялся), гейт захвата и паритет, цель-флаг, точки отхода, поста и уклонения, отряд за флагами и его отзыв, погоня, гонка, постановка `Disposition` для прибора `disp=` и букв заданий; цепочки решений — таблицами (ворота захвата `captureGates()`, правила постуры / режима / причины в `Strategist.decide`, `pushRules()`) |
| `Commander.kt` | командир | стадия `armyCommand`: раздача командира с перебором замыслов под бюджетом тика (зовёт `commandFight`, `commandMarch`, `Formation.brace`, читает стратега и прогноз) |
| `Missions.kt` | задания бегунов | паросочетание «бегун ↔ флаг» по ценности на горизонте, режимы FLEE / RESERVE / EXIT / HOLD / TO_FLAG / POISED |
| `Tables.kt` | таблицы решений | `Row(тег, условие, [свойства `mark: RowMark`, `why: Why`,] действие)`, причина отказа факта `Why` (`c(имя, значение)`, прибор `whynot t=`), счётчики `Tally` (`won` / `true` / `shadowed`), обходчик `walk` — один на все таблицы, с полным обходом условий |
| `Tactician.kt` | тактик | цели тика (фокус, добыча, захватчик, досягаемость его стволов); ход крипа `creepTurn`: факты — носитель `Turn` → лестница цели `ladder()`, 27 строк → факты шага — носитель `Stride` → цепочка шага `steps()`, 8 строк; `Proposal` с приоритетом SURVIVE / MISSION / OPPORTUNITY и причиной «задание.терм», `submit` арбитру |
| `Deal.kt` | раздача | носитель одной раздачи `Deal` (класс верхнего уровня с v456): поля, методы-оценки `scoreMelee` / `scoreRanged` / `scoreHeal`, `place`, поле притязаний и тринадцать проходов в списке `passes` |
| `Fight.kt` | бой | раздача клеток в бою `commandFight` (ранние выходы и построение `Deal`): носитель одного вызова `Deal` (поля, методы-оценки `scoreMelee` / `scoreRanged` / `scoreHeal`, `place`, поле притязаний) и тринадцать проходов в списке `passes`; запись раздачи `DealRecord` — поле нужды (`InfluenceMap.HealNeed`) и пробы, свои у каждой пробы замысла, в мир и приборы уходит запись выбранной (`Commander.publishDeal`, v449); назначение огня и лечения, исполнители удара, выстрела и лечения |
| `Formation.kt` | строй | медиана, изготовка, колонна марша, кулак боя, ряды блока, `planFight`, стена лекарей; одно правило назначения крип → место (узкое место, затем сумма) |
| `Arbiter.kt`, `TrafficManager.kt` | арбитр | ранги толкания, развод ходов без вызовов API, счёт конфликтов `conf=` |
| `Executor.kt` | исполнитель | единственный писатель API: слоты интентов по крипу, затем ходы |
| `Memory.kt` | память | межтиковые истории и назначения, кандидат постуры |
| `Gauges.kt` | реестр приборов (уровень 0) | `Counter` / `Labelled` / `Real`, поле из частей (`GaugeField`), строка по раскладке (`Gauges.line`), «за тик» и «за окно», набор счётчиков записи `GaugeSet` и его влив `absorb`; владелец словарей и множеств приборов (в списке починки) |
| `Instruments.kt` | приборы | печать: фазы CPU (`cpuSummary`), раскладки строк `t=` (`T_LINE`) и `reach` (`REACH_LINE`), поля, вычисляемые на месте печати (`declareLine`), строки `rung` / `tac` / `fld`, зонд первого тика, дамп карты; аудит приказов. Читает всех, импортирует его только оркестратор |
| `Format.kt` | запись строкой | `bodySummary`, `typeChar`, `num` — чем стадии пишут крипа и флаг в свои строки лога |
| `Tuning.kt`, `Rules.kt` | настройки | константы, общие для нескольких стадий (константа одной стадии — в её файле); дальности движка |
| `InfluenceMap.kt`, `DistanceMap.kt`, `AbortRepair.kt` | службы | поля влияния и опасности; расстояния и потоки; починка таблиц после оборванного тика |

**Как менять.**
- Три вида таблиц, механика одна (`Tables.kt`): `Row` + `walk` — «первое сработавшее выигрывает», условия чистые, полный обход
  (`ladder()`, `steps()`, правила постуры / режима / причины в `Strategist.decide`, `pushRules()`); `Gate` + `pass()` —
  ПОСЛЕДОВАТЕЛЬНЫЕ ворота, где между выходами стоят дорогие вычисления и счётчики (`captureGates()`): новые ворота — строка
  списка, то, что нужно посчитать до них, — в их голове, поле для ворот ниже — в `CaptureCase`; `Pass` + `runPasses()` —
  последовательность, исполняются все (`Deal.passes` в `commandFight`): новый проход — метод носителя и строка списка.
- Решение «первое сработавшее условие выигрывает» — СТРОКА ТАБЛИЦЫ, а не ветка `when`: `Row("тег", { условие }) { действие }`
  в списке `ladder()` / `steps()`; место в списке — приоритет, другого описания приоритета нет. Условие — чистое чтение
  фактов: голое имя — поле `Turn` (или `Stride`), `t.` — величина тика, остальное — член `PainAndGain` или верх пакета.
  Новый ингредиент условия — поле `Turn`, объявленное в теле носителя там, где оно считается (имя не должно совпасть с
  открытым полем `ArmyTick`, `Ctx` или членом `PainAndGain` — держит линт). Счётчик или запись, которые должны случиться при выигрыше строки, — в её
  действии; в условии их быть не может (условия вычисляются у всех строк). Прибор `reach t=` считает строку сам; читать
  его — `tools/series.py reach`, помня, что у замыкающей строки и у строки, стоящей под своей «половиной», `shadowed`
  ненулевой по построению.
- **Строка несёт свои свойства** (v457, второй шаг архитектуры, 4.5): то, что код ВНЕ таблицы хочет знать о выигравшей
  строке, — её `mark` (`RowMark.SURVIVE` у бегства, `ORDER` у шага по приказу, `FREE` у свободного шага, `OPPORTUNITY` у
  ступеней-возможностей): `priorityOf(pace.mark, rung.mark)`, `pace.mark != RowMark.ORDER`. Сравнение тега со строковым
  литералом вне таблицы — FAIL линта `tag_outside_table` (список известных пуст): переименованная строка не может молча
  сменить приоритет предложения. Перечня ступеней в комментариях нет — он один, список `ladder()`.
- **Причина отказа — данными** (v457–v458, второй шаг архитектуры, 4.4): факт из цепочки `&&` с четырьмя и более операторами
  называет свои конъюнкты в месте вызова — `private val ENGAGE = Why("engage")`, `ENGAGE.c("inLine", inLine) && ENGAGE.c("aggr",
  localAggressive || spotNow) && …`. `c` возвращает значение как есть и считает ложное под именем, поэтому ленивость и значение
  прежние; дизъюнкция — одно имя на группу, фильтр кандидатов считает отказы по кандидатам (`foe.inReach`). Списка имён рядом нет.
  Строка таблицы, чьё условие — такой факт, называет его свойством: `Row("engage", { engage != null }, RowMark.OPPORTUNITY, why =
  ENGAGE)`. Читать: прибор `whynot t=` (накопительно, печатается с `reach`) — `tools/series.py whynot [--fact engage] [--by-outcome |
  --by-opponent] [--stand tools/stub/painandgain/out]`; общая трасса `trace t=` (на бойца: выигравшие строки лестницы и шага и первый
  ложный конъюнкт факта каждой строки выше) — только в окне тиков: на стенде `TRACE=t0-t1`, живьём — константы `TRACE_FROM` /
  `TRACE_TO` в `Clock.kt`. Имя конъюнкта не должно давать в строке текст `guard:` — так `identity.sh` ищет строки предохранителей CPU.
  Ручной копии условий факта «ради объяснения» в пакете быть не должно: она расходится с фактом при первой правке (v203, v458).
- Факт о крипе, ключ клетки, группа постур, выборка — по имени из словаря, а не инлайном: определение факта одно, в
  `Facts.kt` (`CreepFacts`); обёртки `hasMelee(c)`, `healerOnly(c)`, `meleeOnlyBorn(c)`… и функции-выборки (`living`, `armedOf`,
  `notDetached`…) — в `World.kt`; выборки по спискам тика — поля `Ctx` (`side`, `threats`, `armedArmy`…). Новый вариант
  факта получает имя в `CreepFacts` и правило в `tools/stub/painandgain/lint.py`. «Чистый мили» — ДВА факта (`meleeOnlyBorn`
  и `meleeOnlyLive`), см. таблицу в абзаце v442. Классы зовутся `CreepFacts` / `TickFacts` с v448: имя `Unit` из плана
  заслоняло `kotlin.Unit` во всём пакете.
- **Код без приёмника** (v454 → v459, второй шаг архитектуры, этапы 1 и 6): `object PainAndGain` — это `tick()`, список починки и
  оркестровка; состояния в нём нет, и никто не получает его ни приёмником, ни параметром (расширений синглтона 161 → 65 на v454 → 0
  на v459). Имя в теле функции разрешается по ДВУМ областям — локальная → верх пакета; общее состояние читается квалифицированно
  (`Orders.commandOf`, `Signals.groupSafe`, `Prev.approachRate`, `Memory.lastHits`). Правила линта `needless_receiver` и
  `member_vs_toplevel` остаются в гейте: расширение синглтона без нужды — FAIL.
- **Владельцы состояния** (v459, этап 6). Величина ОДНОГО ТИКА — поле носителя стадии (`val x = …` там, где считается; порядок
  стережёт компилятор). Величина, которую кто-то читает РАНЬШЕ её писателя в тике, несёт значение прошлого тика — оно лежит в
  `Prev` (`Memory.kt`), копирует `RememberTick` в конце тика (и только в тике с армией); читатель пишет `Prev.approachRate`, а не
  голое имя, про которое надо знать, что писатель ещё не отработал. Если одну функцию зовут и до писателя, и после (ворота захвата
  — из `runRunners` и из `runArmy`; `exitMargin`), значение ей называет ЗОВУЩИЙ: `captureAllowed(ctx, f, Prev.exchange)` против
  `captureAllowed(ctx, f, meas.view)`. Словарь или множество, живущие весь матч, — поле объекта-владельца в файле писателя
  (`Orders`, `FireBook`, `Wall`, `StrategistState`, `WorldState`, `TacticianState`, `Signals`), не выше нижнего читателя; новый
  владелец — строка в списке починки `repairAfterAbort` (оттуда же его берёт правило одного писателя). Таблица «по id своего
  бойца» объявляется `Memory.perCreepSet()` / `Memory.perCreepMap<V>()` — мёртвых снимает одна `Memory.prune(living)` в хвосте
  стратега; чистка, которая на деле решение, остаётся на своём месте.
- **Носитель: поле объявлено там, где вычислено** (v456, второй шаг архитектуры, этап 3). Стадия армии — КЛАСС, чьё тело и есть
  прежняя функция стадии: `class ArmyMeasures(ctx, pag)`, `ArmyTargets`, `ArmyStance`, мир — `class Ctx(pag)`, память тика —
  `RememberTick(ctx)`; факты хода и шага — `class Turn(creep, ctx, t)` и `class Stride(turn, aim)`. Новая величина от стадии к
  стадии, новый факт хода для строки лестницы, новый факт шага — ОДНО место: `val contact = …` в теле носителя, там, где она
  считается (до v456 имя писалось трижды: в заголовке класса `…Out` / `Turn`, локальной в построителе и аргументом `x = x` его
  `return`). Порядок текста — порядок инициализации; оператор между фактами (счётчик прибора, запись в память, печать) — блок
  `init { … }` на своём месте последовательности; прежняя локальная функция — метод на своём месте, и метод не читает поле,
  объявленное ниже него (в функции это запрещал компилятор, в классе — правило линта `carrier_method_order`). Читают снаружи —
  открытое поле, нет — `private`. Оркестровка — по-прежнему список вызовов: `val meas = ArmyMeasures(ctx)`; читатели пишут
  `meas.fight.contact`, `t.meas.fight.contact`, `turn.slot`. С v456 по v458 то, что ещё оставалось членом синглтона, носитель читал
  через параметр `pag` (квалифицированное `PainAndGain.x` дало бы модулю стадии импорт оркестратора — ребро вверх, которое гейт
  графа не пропускает); с v459 членов нет, параметра тоже. Поле носителя внутри лямбды инициализатора компилятор не сужает по
  `!= null`, как сужал локальную: там пишется `x!!` с комментарием, где непустота проверена. Класс-параметр чистого решателя
  (`Strategist.Inputs`) остаётся списком — `decide` тестируем без игровых объектов (открытый вопрос оператору: с ним действие 5
  таблицы «цена правки» стоит четыре места, а не два); `PushCase` читает меры и пачки из носителей. Пустых `…Out` нет (v459).
- **Длинная стадия — цепочка подстадий-носителей** (v456–v457, этап 4): `ArmyStrategy` — список из восьми вызовов (`StrategyPacks` →
  `StrategyThresholds` → `StrategyDetach` → `StrategyPush` → `StrategyContact` → `StrategyObjective` → `StrategyDecide` →
  `StrategyThreats`), у каждой свой выход; параметры подстадии — носители предыдущих, и читает она их квалифицированно
  (`packs.oursFight`). Место правки стратегии ищется по имени подстадии; читатели пишут `strat.obj.objective`. Швы — минимумы
  `tools/cutwidth.py`, подтверждённые чтением: состояние, идущее через члены объекта и `Memory`, прибор не видит (шов внутри
  `StrategyObjective` не резать — порядок «темп сближения → поля бегства → цель-флаг → точка уклонения» есть поведение).
  Так же устроены остальные стадии армии (v457): меры `ArmyMeasures` = `forces` (силы сторон) → `exchange` (размен) → `chase`
  (погоня, затор) → `fight` (мощь, контакт, отход); цели `ArmyTargets` = `pool` (занятость клеток, пул огня) → `focus` → `quarry`
  (добыча) → `takers` (захватчики) → `form` (авангард, готовность строя) → `zones` (досягаемость его стволов, слоты); стойка
  `ArmyStance` = `windows` (блок и окна истории) → `press` (прижим) → `breakOff` → `applied` (постура применена) → `gauges`.
  Читатели пишут `meas.fight.contact`, `targ.quarry.prey`, `stanceOut.press.pressOn`. **У функции с ранними выходами фасад —
  сама функция:** тело класса вернуться не может, поэтому `commandRace` (`RaceRoster` → `RaceBudget` → `RaceRoutes` →
  `RaceGarrison` → `RaceParties`) и `runRunners` (`RunnerMatch` → `RunnerMoves`) держат свои `return` на прежних местах МЕЖДУ
  вызовами подстадий; изменяемое, шедшее через шов локальной (`budget`), — открытое `var`-поле своей подстадии (`purse.budget`).
  Метод обычного класса режется на СЕКЦИИ — приватные методы по порядку (`Deal.place` → `evict`, `commit`; `Deal.passHealer` →
  `meetWounded`, `adjacencyGauge`, `pickProbe`); цикл поиска клетки в `place` не резан — через его шов идут три величины, а
  путь самый горячий у командира.
- Зависимость направлена ВНИЗ или внутри уровня (`tools/stub/painandgain/levels.txt`, строка `graph` гейта): 0 — `Rules`,
  `Tuning`, `Types`, `Tables`; 1 — `Memory`, `Facts`, `Geometry`, `Power`, `Clock`, `DistanceMap`, `InfluenceMap` и приёмники
  `Executor`, `Arbiter`, `TrafficManager`; 2 — `World`; 3 — `Forecast`, `Strategist`, `Missions`; 4 — `Tactician`, `Fight`,
  `Formation`, `Commander`; 7 — `PainAndGain`, `AbortRepair`; вне уровней — `Instruments` (читает всех). Решение прошлого
  тика читается явно (`Memory.prevPosture`), а не полем, которое стадия выше ещё не переписала. `object` получает нужное
  параметром (`units: TickFacts`), а не через синглтон `PainAndGain`.
- Состояние: таблицы и множества — полями объектов из списка владельцев `repairAfterAbort` (иначе оборванный тик их не
  починит; новый владелец — `BodyMemo` в `Facts.kt`, v454); простое состояние — на верхнем уровне файла-писателя, **но не
  выше нижнего читателя**: `pushing`, `retreatTarget`, `lastFireTick` пишут стратег и бой, а объявлены они в `World.kt`,
  потому что меры мира читают их значение прошлого тика; доли касания за полное окно пишет стратег, а объявлены они в
  `Power.kt` у модели мощи. Объявление на уровне писателя дало бы ребро вверх — его не пропустит строка `graph` гейта. **Счётчик живёт у того, кто считает:** объявляется в
  файле стадии, которая его наращивает (блок «приборы стадии» в конце файла); `Instruments.kt` его читает и печатает, текст
  строк не меняется. Прежнее правило («счётчик, который только печатается, — в `Instruments.kt`») отменено оператором
  19.09.2026: оно сделало файл приборов шиной общего состояния. Ни одна стадия не импортирует `Instruments` — это держит
  строка `graph` гейта; функцию, которой стадия пишет свою строку лога, — в `Format.kt`, часы — в `Clock.kt`. Величина тика,
  которую сбрасывает мир, а считает стадия выше, объявляется не выше обоих писателей (сейчас — три счётчика в `World.kt`).
- **Прибор — объявление у того, кто считает, и имя в раскладке** (v455, `Gauges.kt`): `internal val rotOut = Gauges.counter("rot")`
  в файле стадии, `rotOut.n++` там, где считается, и `"rot"` в списке `T_LINE` (`Instruments.kt`) — порядок полей строки `t=`
  задаёт этот список и больше ничто. Поле из нескольких частей — `Gauges.counter("hold", 1)`, `…("keep2", 3, sep = ":")`,
  `…("lost", 0, label = "stay")`; части одного поля могут жить в разных файлах. Словарь причин — `Gauges.labelled("cmdwhy")` и
  `cmdWhy.bump(причина)` (его знаменатель — счётчик следующей части того же поля); «за этот тик» и «за окно печати» —
  `perTick = true` / `perWindow = true` при объявлении. Счётчик прохода раздачи объявляется ОДИН раз — в `DealRecord`
  (`val fallReach = g.counter("fall")`): близнец матча заводится сам, выбранную раздачу вливает `Gauges.absorb`. Величина
  состояния или снимок мира в строке — `Gauges.computed("имя") { … }` в `declareLine` (`Instruments.kt`). Новая таблица решений
  регистрирует свой `Tally("имя")` сама; её имя — в `REACH_LINE`. Словари и множества приборов — значения полей `Gauges`
  (объект из списка починки): оборванный тик чинит их по построению. Маска в `logdiff.py` не нужна: поле, которого нет в
  эталоне, маскируется само и называется в отчёте `identity.sh`. Счётчик — объект с полем `n`, а не `var x by …`:
  делегированное свойство в Kotlin/JS строит объект-ссылку и два замыкания на каждое чтение и запись.
- Две строки гейта держат форму, а не память (`docs/pain-and-gain-architecture.md`, этап 0), и блокируют посадку:
  `lint` — написание, сведённое к одному определению, не возвращается инлайном (`tools/stub/painandgain/lint.py`);
  `graph` — нового ребра вопреки уровням нет, список известных в `levels.txt` только убывает (`tools/depgraph.py` по
  скомпилированным модулям). Новый файл пакета обязан назвать свой уровень в `levels.txt`.
- Механическая правка проверяется тождеством: `regress.sh land` → `compare.py` «без изменений 135» → `logdiff.py` 0 →
  `verdicts.py check` 0 пропаж — одной командой `zsh tools/stub/painandgain/identity.sh 440` (эталон `runs/gate_440`;
  первым делом она проверяет, что предохранители CPU молчали, — см. абзац v441). Поведенческая — гейтом FAIL-only и живым A/B 8+8 против последней посадки, «не хуже на
  обоих ботах».

**Что из плана не сделано и что должно быть верно, чтобы сделать.**
- Постановка как решатель (`Disposition` вместо `Posture` / `CmdMode` / `Intent`): постановка печатается и даёт букву
  задания, решает `Strategist.decide`. Нужна оценка постановок, которая бьёт правду: прокат с одним числом мощи в минус
  не уходит и доктрину «аннигиляция = проигрыш» не проверяет (v242 отвергнут живым A/B).
- Адресная опасность `threatAt` в оценке клетки: отвергнута дважды — заменой (v244) и добавочным термом (v246). Против
  кайтера она уводит того, кого его ствол выберет целью; опасность клетки в этих матчах не решает (разбор серии v245).
- Спасение первым у арбитра (v253): отвергнуто — бегущий с высшим рангом ломает строй против стоячего блоба.
- Огонь и лечение в `Proposal`: назначаются после покрипного цикла; назначение отрядом с насыщением отвергалось только
  вместе с тремя другими правками (v244), по одной не пробовалось.
- Одна модель строя с якорем-параметром: общие медиана и правило назначения есть, три расчёта якоря — три функции.
- Клетка как argmax списка термов: оценки `score*` остаются функциями внутри одной раздачи `commandFight`.

## Measured rules and design

**`season4/painandgain/`** — Season 4 "Pain and Gain" (basic) bot (first version 04.09.2026). **Rules** (the arena description in the client lobby; the website docs do not describe this mode, only the effect table in the client bundle `screeps_arena.app/Contents/Resources/app/arena-docs/index.html` does): each player has a **pre-set army of 14 creeps — no spawning, construction, energy or replacements**; seven neutral `ScoreFlag`s, captured by **standing on the cell**; a captured flag scores per tick for its owner and applies a global debuff to the owner's whole army, same-type flags stack: Vulnerability ×1 (5/tick, incoming combat damage ×1.1), Heal reduction ×2 (4/tick each, healing ×0.75/×0.5), Attack reduction ×2 (3/tick, ATTACK ×0.8/×0.6), Ranged reduction ×2 (3/tick, RANGED_ATTACK ×0.8/×0.6) — 25/tick in total (`MAX_SCORE_PER_TICK`); 2000 ticks; **win = higher score, or destroy all enemy creeps**; the match ends early when an army is destroyed or the lead is mathematically unreachable — measured 07.09.2026 on the v132 series: the tick the lead exceeds 25 × the remaining ticks (every flag's rate together), wins at 1859 and 1931 exactly there, losses at 1718–1983 with both armies whole; the bot's `maxSwing` is this rule, and a match at 1700–2000 with both armies alive is a points end, not an annihilation. API: `ScoreFlag` (`effectType`, `scorePerTick`; binding `types/.../season4/ScoreFlag.kt`, module `arena/season_4/pain_and_gain/basic/prototypes`), constants `TICKS_LIMIT`/`MAX_SCORE_PER_TICK`/`FLAG_TYPES` (`PainAndGainConstants.kt`), the debuffs arrive as `GameObject.effects` (`EFF_*_MODIFIER` with `data.multiplier`; the bot also derives them from flag ownership and logs both — they must agree in the log). **Design:** homes = the initial centroids of both armies (no spawns); weaponless/heal-less creeps are *runners* that capture flags by swing per tick of their own walking time, weighted by the flag's cost in power; a flag is captured only while the army *with* that debuff keeps ≥ `CAPTURE_FLOOR` (0.9) of the enemy's power — the marginal Lanchester price with both sides' effects (`powerAfter`: a ranged flag cheapens only ranged dps, a heal flag raises the enemy's net dps, vulnerability shrinks hits) — or whenever the score projection says we lose (`behindOnScore`, self-limiting: it flips back once our rate leads); the army moves as one group with a posture: ANNIHILATE (≥ `PUSH_RATIO` by effects-aware power, or *in contact* unless retreat is feasible — no enemy melee adjacent and our slowest mobile creep faster than their fastest), RETREAT (enemy ≥ `RETREAT_RATIO` and near, to home or an own-half corner, never across the map), FLAG (the best flag objective by swing×cost/travel, guarded ones by Lanchester + fight cost within the group's speed slack), HOLD (post at the centroid of our flags); inside: local aggression with hysteresis, cohesion among mobile weapon holders only (healers follow their charge and never hold; nobody holds within `ARRIVED_SLACK` of the target), focus fire by *threat per hit* (killable first, then (live melee + live ranged + heal)/hits — "healers first" lost a mirror fight 11:6, threat-per-hit won it 12:9), `InfluenceMap` multiplies dps/heal by the creeps' effects and incoming damage by our vulnerability. **Offline stub** (job tmp dir, same loader-hook harness as spawn-and-swamp, no spawns): a point-symmetric 100×100 map with seven flags and a mirrored 14-creep army (bodies guessed: 2×M4 runners, 4×T2M5A3, 4×M4R4, 2×M3H3, 2×M5R2), enemy scripts `none|grab|rush|greedy`, early end on annihilation or unreachable lead. v1 results: none 10864:0 (two side flags, the rest blocked by the floor), grab 24672:16542 with no losses, rush — enemy army destroyed at t=426 (12 killed for 9), greedy — destroyed at t=303; errors 0.

## Version history — по эпохам, в `docs/pain-and-gain/`

Один абзац на версию или живой матч (v1–v3 свёрнуты в абзац «Measured rules and design» выше). С 20.09.2026 история лежит
отдельными файлами по эпохам — главный файл читается за один заход, а вопрос «это уже пробовали?» задаётся инструменту:
`python3 tools/know.py <тег строки | факт | константа>` собирает место в коде, абзацы версий, архив вердиктов и счётчики `reach`.

- [`history-v0xx.md`](pain-and-gain/history-v0xx.md) — живые матчи 1–… (по абзацу на матч) и v4–v99, 84 абзаца
- [`history-v1xx.md`](pain-and-gain/history-v1xx.md) — v100–v180 (в том числе «эпоха командира» v137–v180, перенесённая из комментариев кода), 53 абзаца
- [`history-v2xx.md`](pain-and-gain/history-v2xx.md) — v204–v299: карта влияния, переработка v236–v263, стена лечения, 97 абзацев
- [`history-v3xx.md`](pain-and-gain/history-v3xx.md) — v300–v399, 42 абзаца
- [`history-v4xx.md`](pain-and-gain/history-v4xx.md) — v401 и далее: мера боя, архитектура решений v441–v447, доработки А–Д v448–v452, второй шаг архитектуры с v453; **новые абзацы версий пишутся сюда**, 47 абзацев

## Stub harness

`tools/stub/painandgain/` (see its `README.md`): the compiled bundle of this worktree's build runs under Node against a
stub `game` package through a loader hook — constants, prototypes, Dijkstra `searchPath`, simultaneous movement with
swaps and chains, fatigue by part type, front-to-back part damage and healing from the tail of the body — plus the
arena's `ScoreFlag`, the score, the global debuffs as `effects`, and the early end on annihilation or an unreachable
lead. Maps `map-match1..35.txt` are the `DEBUG_MAP` dumps of thirty-five live matches (`MAP=…`, `START=match2` when we
were player 2); enemy scripts `none|scouts|grab|rush|greedy|army|hunter|kite|sleeper|nine|…|spread|roost|farm|camp|tour|split|blitz|scatter|brawl (+dart)|screen+focus (+poke, +blob, +blob+dense)|ghost` (`SLEEP=` the wake-up tick; the full list with each form's live model is in the README — `scatter` is the match-240 scatterer built from the replay, v100; `brawl` is けろびー's blob of matches 238/249 with the stand's `ours act:` uptime line, v101; `ghost` with `REPLAY=<id>.replay.json.gz` is the recorded opponent of a live match walking its replay while our bot plays live — the map, flags, bodies and start cells come from the replay too; see Match analysis below).
`zsh tools/stub/painandgain/regress.sh <tag>` runs every scenario on the twenty-five live maps and prints one `PASS`/`FAIL`
line each (pass = the enemy army destroyed, or the match ended with our score ahead, with zero errors); it is the
landing gate in `tools/land.sh`. The synthetic point-symmetric map with the pre-match guessed bodies is kept for hand
runs only (its `rush` is a points draw with v9: M3H3 healers and T2M5A3 melee behave nothing like the real army).

## Match analysis

`tools/autopsy.py <id | prefix | log file>` — the autopsy of one live match in one command (06.09.2026; the first of the four analysis tools the operator ordered in the order autopsy, decision trace, ghost, ledger). It reads our console — from the replay's `logs`, from the match store through `match-log.py` (now importable, `full_log`), or from a `play.py --logs` file — and the replay when there is one (`~/ScreepsArena/replays/<id>.replay.json.gz`; `--fetch` downloads a missing one through the API — `match-log.py replay`, the same page fetch the logs use; until 19.09.2026 it went through arukuka's tool and the client's inspector, which the operator closed), and prints, in order: the outcome's form (annihilation, points at the limit, early on points) with the ticks the score and the damage ledger diverged; the opponent's form by the replay track — blob / line / scatter / farmer / sentinel / touring — with the numbers it was judged on (dispersion, his centroid's speed before contact and during it, where his ranged stood, his melee adjacency, flags visited); the timeline every `--step` ticks with both centroids' distance to the map edge; the posture transitions and the 100-tick window with the most of them; the first contact's geometry (who marched and who stood in the ten ticks before, distance to the edge, compactness, who fired the first five ticks' volleys, the bot's own power ratio at contact); the fight decomposed as `replay-damage` did it (expected damage and healing per side from the intents against the observed hits, uptime per role, melee adjacency creep-ticks, shots by target role, deaths in order); the nets that fired and the ones that stayed silent (stall, detach, keepers, plan yields, press give-ups by creep, evades with each point's distance to the edge, flag events); the runners' longest stands; the `why` trace's idle melee with the filters that stopped them; then DIAGNOSIS — one line per rule that crossed its threshold, with the number: corner fight, posture flicker, quiet behind (he stood / he toured), give-up storm, runner stood, melee idle, uptime, melee adjacency, first volleys, marched into a formed line, outdamaged, went in short, first blood his, annihilated ahead, errors — and the history against the same opponent from the cache. On the four matches it was built against it said in a second what the half-hour readings had said: 273 — corner fight 97 % of 468 contact ticks, give-ups 80 per 100, melee uptime 71 % against 98 %, his form LINE (ranged at 3–4, adjacency 2 %, centroid 1.1 cells per ten ticks in contact); 267 — first volleys 6:10 for him, ranged uptime 63 % against 99 %, outdamaged 16 236:672; 264 (the win over the same blob) — first volleys 10:4 for us, his compactness 11 against our 1.5 at contact; 258 (no replay, the log alone) — 21 posture transitions in the 100 ticks from t=508 and 1 440 quiet ticks behind with his centroid touring 56 cells. `--json` prints the whole measurement for aggregation across matches. The thresholds sit at the top of the file, each with the question it encodes; a rule that fires on a win (264's corner fight, fought at our own post) is a hint, not a verdict — the report prints the number, the reader decides.

**The ghost (tool 3, `REPLAY=… run.mjs 2000 ghost`, 06.09.2026).** The enemy's creeps walk the cells the replay recorded, tick for tick (one move intent to the adjacent recorded cell, resolved by the engine as live; a path step when two or more behind; no fatigue), fire by rule (an armed ranged of ours first, then the lowest hits within three; melee at the adjacent lowest; healers on the most wounded mate within three) and, outliving their record, stand and keep firing. The run ends with `ghost <id>:` (recorded outcome against the stand's; ghosts off their recorded cell — 1–3 % of creep-ticks; our own creeps off OUR recorded cell with the first tick) and `ghost entry` (hits lost by each side over the first 20/50/100 ticks after contact, stand against record), with `entry t=N:` intent lines for the first thirty ticks. Measured on four records (273 Coldkimchi L, 267 and 251 けろびー L, 264 けろびー W): the opening and the entry are faithful at the decision level — the same evade points to the cell, contact at t=98/42/101 against the record's 98/41/100 — and the exchange is not: his side lost 3 774 / 3 791 / 3 114 hits in the first twenty ticks against 1 081 / 1 441 / 726 recorded, ours as recorded, because our melee reached his line four times as often (20 swings against 5): his step-backs are the record's, timed to OUR recorded melee, and our own creeps part from our recorded cells from tick 1–2 on path tie-breaks (90 % of creep-ticks a cell or two off). Two reactions were tried and rejected with numbers (`+shy`: ranged and healers back off from our melee within two — 6 970 / 4 476 lost; `+nurse`: a healer steps to its wounded — 5 462 / 3 693); they stay as options. A record whose opening the current build no longer repeats (251, v101's opening) meets nobody until t=271 against the record's 63, and the `ghost entry` line says so. So the ghost answers "does the current build take the recorded entry, and how far does the exchange part from the record" — not "would it have won"; the stand's `brawl` and `wing` remain the fight-rule instruments, and the ghost is their calibration against a real opponent's tempo and entry.

**The ledger (tool 4, `tools/ledger.py [--last N] [--since DD.MM] [--opponent X] [--version vNN] [--rows] [--csv] [--json]`, 06.09.2026).** Every Pain and Gain match in the match store (`tools/match-log.py`, the API's documents fetched through the client), autopsied (the replay too when `~/ScreepsArena/replays/` has it) and added up in six seconds: by opponent (W-L, rating won and lost, mean ticks, his forms, the rules firing in the losses to him), by our version, by diagnosis rule (fires in losses against wins), by outcome form, by his form, and the rating leaks. What it said the first time, over 290 matches 180-110 (+283, 57 with replays, 04.09–06.09): the rating leaks are けろびー −293 over 61 losses of 124, MetalicaX −121 over 17 of 20 (mean 1 900 ticks — points races, tagged posture flicker 16 and quiet behind 11), G1N6ERbreadMan −97 over 9 of 31 (annihilated ahead 8 — the parity doctrine's own case), Coldkimchi −95 over 15 of 17 (fights: give-up storm 14, melee adjacency 13, first blood his 13, uptime 11). 173 of 180 wins and 97 of 110 losses are annihilations — the arena is decided by the fight, and the flags decide 3-9 at the limit. The rules that fire only in losses: uptime 40 % of losses / 0 % of wins, annihilated ahead 27 / 0, melee adjacency 24 / 0, outdamaged 10 / 0, quiet behind 36 / 2, first blood his 26 / 2; give-up storm fires in 50 % of WINS and 42 % of losses, posture flicker in 40 / 54 — hints, not causes, and the give-up work of v96/v104 aimed at a symptom. By his form, replays only: blob 9-39, touring 0-6, line 0-1. The order of work the numbers give: the fight's uptime and adjacency against the blob (けろびー, Coldkimchi), then the points race against the tourer (MetalicaX), then the parity doctrine's annihilated-ahead losses (G1N6ERbreadMan). **Corrected 07.09.2026:** the `uptime` rule was an artifact of counting live parts from the back and is gone (see v109–v111); with parts counted front to back the rules that fire only in losses are stripped in reach (20 % / 0 %), entry lost (19 / 0), melee adjacency (27 / 0), annihilated ahead (27 / 0), quiet behind (36 / 2) and first blood his (26 / 2) — the blob item is the entry exchange, not idleness.

**A username is not a bot — results by the opponent's code version (07.09.2026, the operator).** The server names the code each side played with (`codes[].version`, its per-slot upload counter, and `codes[]._id`, the slot), and the opponents run several at once: けろびー has five in play today — #1 (code …7c54), #2 (…8500) and #3 (…853f) are the blob, 16-15, 20-15 and 15-8 against us; #4 (…85b3) and #5 (…8758) are the tourer and the farmer, 9-14 and 2-14, −141 rating between them — MetalicaX #3 (…9002) 3-12 and #4 (…922a) 1-8 are both tourers, Coldkimchi #1 (…8bd0) 3-16 the line. Read by name alone, けろびー was 63-61 and "a coin"; read by code he is three blobs we beat more often than not and two farmers that beat us three times in four. One code id per name-and-version pair over the whole cache, so `#version` is the key. `match-log.py` carries `opp_code` (and the opponent string `name#version`), `play.py --history` prints it after the name, the autopsy's header and its history line split by his version, and the ledger has a `by opponent VERSION` table with the first and last time each was seen.

**The instruments of the night.** `tools/replay.py` grew five subcommands ported from the job's folder — `block` (his block in the frame of the axis between the armed centroids, healer adjacency, his centroid's steps, ASCII pictures), `dance` (the melee at two: away/still/toward by the next tick), `persist` (each side's fire staying on one creep, and a healer beside it), `brawl` (his melee adjacent to ours: victims, our nearest melee's distance, could we swing back) and `bodies` (both sides at given ticks with the real part order) — and a sixth, `swings` (what each side's melee hit against the best enemy adjacent to it). The stand's `+blob` got its seventh cut, `+dense` (only the two front fighters at three, the rest behind and adjacent to two blockmates), and the measure rejected it: the blob got weaker on all six maps (entries ours everywhere, three blobs destroyed against one), because a column at four fires with two creeps while the arc fires with all; the finding it leaves is in the stand's README — the live block's ranged stand at 4–5 by choice, its entry is the melee dance and the focused volleys, not density.

**Единый кулак и то, чем командир был на самом деле (09.09.2026, v141–v154).** Оператор посмотрел повторы и назвал форму: «крипы разбегаются, лекари всегда очень далеко, некоторые крипы отстают и умирают брошенными, мили лезут в толпу, нет единого кулака», а следом и причину — «смешение нескольких стратегий». Приборы подтвердили обе половины числом. Счётчик `cmd=<крипов под приказом>/<тиков командира>:<что помешало>`, добавленный в тик-строку, показал командира таким, каким он был: **27 тиков из пятисот, и в большинстве из них приказ получал ОДИН крип из двенадцати** — всё остальное время армия шла по старым правилам, и командир успевал только выдернуть крипа из строя. Разброс строя (медиана позиций боевых, скрипт `cohesion`): боевые роли у нас плотнее его (3,3 против 5,2), а `max 33` давали бегуны за флагами — «разбегание» было не про строй. Зато **лекарь до ближайшего своего бойца: 4,1 клетки у нас против 1,7 у него при дальности лечения 3**, то есть наш лекарь в среднем стоял вне дальности; при трёх лекарях у каждой стороны **у него в дальности все три каждый тик, у нас 1,8 из трёх**, и лечение выходило 1 584 против 9 624. Причин оказалось две, обе в порядке правил: в раздаче клеток лекарь ранжировался как `incNext*100 + расстояние` (опасность стократно тяжелее близости), а в цепочке движения СЛОТ стоял выше подопечного — расстановка о лечении не знает и уводила лекаря в строй. Третья находка того же рода: **раздетый крип не попадал ни в одну группу командира** (ни оружия, ни лечения) и приказа не получал вовсе — стоял под огнём, 172 крипо-тика из 238 против его 7 из 11.

Ключевой контроль этой ночи: против MetalicaX#10, который громил нас за 100–200 тиков, **кайт с выключенным командиром дал ровно те же 1-7, что и командир** — то есть в этих разгромах командир не был виноват, бот проигрывал одинаково в обеих конфигурациях. Что сдвинуло дело — **единый кулак** (`USE_FIST`): якорь армии как МЕДИАНА боевых (среднее тянет один отставший крип) и запрет назначать клетку дальше `FIST_RADIUS` от него, вместе с окном командира на ЛЮБОЙ контакт с вооружённым врагом, а не только с сомкнутым блобом (`USE_COMMANDER_EVERY_FIGHT`). Замер против MetalicaX#10, тестовыми играми: **8-8 за шестнадцать против 3-13 за шестнадцать** у всех прежних конфигураций (командир как был 2-6, командир постоянно 1-7, кайт 1-7, кайт с фокусом по лекарям 1-7). Радиус кулака замерен по обе стороны и не назван, а вычислен: 3 даёт 2-6 (кулак теснее строя — его веер накрывает всех разом, стрелкам негде держать дистанцию), 5 даёт 6-10, 4 даёт 8-8. `COMMAND_REACH = 1` («приказ ровно за шаг, никаких толканий») отвергнут замером 2-6: командиру нужен горизонт шире шага, иначе он не может ни развернуть строй, ни увести лекаря за спины. Отвергнут и командир НА ПОДХОДЕ (`USE_COMMANDER_APPROACH`, 0-8): раздача клеток умеет ровно одно — расставить армию относительно ЕГО строя, а на подходе задача другая, и фолбэк «ближе к врагу» вёл армию прямо в него; отдавать командиру поход и флаги можно только после того, как у него появится собственный замысел похода.

**И что из этого не выдержало гейта (v155–v156).** Кулак и расширенное окно командира были включены ОДНОВРЕМЕННО, поэтому 8-8 против MetalicaX#10 принадлежит паре, а не одному из двух, — и гейт разделил их сам. `USE_COMMANDER_EVERY_FIGHT` (командир на любой контакт) уронил roost и scatter: формы, где враг сидит на флагах или разбегается, а командир строит кулак против того, кто строем не дерётся; порог «не меньше трёх его вооружённых у нашей армии» этого не спас (упали scatter и kite вместо них). Хуже того, `USE_COMMANDER_ALWAYS` из v144 — снятие условий про постуру, отход и затор — уронил camp: 8 112 : 17 419 при живых 14 : 14, то есть не бой, а проигранная гонка очков, где армия стоит в кулаке вместо захвата; с выключенным тумблером тот же сценарий кончается `enemy army destroyed at t=616, alive=14/0`. Снятые условия несли смысл, и это записано числом. Осталось включённым то, что гейт держит: кулак, лекарь при бойце, лекарь выше слота, выброс мили по расчёту, раздетые под приказом, фокус по лекарям — **гейт 131/131**. Чему принадлежит живое 8-8, разделит замер против MetalicaX#10 на этой сборке; сервер арены в ту ночь ушёл в 502, и замер ждёт его.

**Прибор: враг, который бьёт по лекарям (09.09.2026).** Кат `+heals` в `run.mjs` существовал, но НИ ОДНА строка гейта его не гоняла — вот почему стенд «по лекарям не бьёт» и вот чего стоило это молчание правилу выше. Теперь четыре строки его гоняют (`brawl+heals` на match13/20/28, `screen+focus+blob+heals` на match35), и стоят они в GRIND, а не в run: бот проходит их не все — match28 при нынешней настройке теряет армию (723 : 4 865), а при `USE_COMMANDER_ALWAYS = true` проходит. Открытая находка ставится в прибор, а не в ворота: ворота остаются прежними 131, чтобы история замеров была сравнима, а новая форма врага меряется рядом и ждёт, когда бот закроет её по существу.

**Признак боя — огонь, а не его мили вплотную (v157, гейт 135/135).** Четыре условия, снятые в v144 разом, гейт разобрал по одному, и виновным оказалось не то, на которое я думал: постура ни при чём (camp падал с ней и без неё, теми же 8 112 : 17 419), затор ни при чём, а держал ворота `theirMeleeIn` — «его мили в ОДНОЙ клетке от нашего вооружённого». Условие узкое до бесполезности: против врага, который мили не подводит, оно ложно ВЕСЬ бой, и новая строка match28:brawl+heals показала это счётчиком — `cmd=1/17`, командир молчал, пока армию убивали (наша армия уничтожена, 0 : 13). Заменено на `underTheirFire` — «мы под его уроном», тот же вопрос без оговорки о мили, — и camp он по-прежнему исключает, потому что там враг сидит на флагах и по нам не стреляет. Та же строка стала `enemy army destroyed at t=428, alive=12/0`, все четыре строки с врагом, бьющим по лекарям, закрылись, и они переехали из grind в ворота: **гейт 131 -> 135**. Из четырёх прежних оговорок в условии командира осталась одна, и теперь она названа числом, а не преданием.

**Правило, которое заворачивал прибор.** `USE_FOCUS_HEALER_FIRST` стоял выключенным с отметкой «отвергнуто стендом дважды», и там же была записана причина отказа: «СТЕНД ПО ЛЕКАРЯМ НЕ БЬЁТ и своих держит вплотную, поэтому огонь по его лекарям на стенде — чистая потеря темпа, а живьём это 17–35 % его залпа. Предмет открыт, и он не в боте, а в приборе». Живые числа сказали то же самое прямее некуда: **наш ожидаемый урон за бой 5 060 против его лечения 5 544** — пока его лекари живы, мы не можем убить никого в принципе, и выстрелов по его лекарям у нас было НОЛЬ против его 44 по нашим. Включённое живьём правило подняло наш урон до 8 980 и выровняло вход в бой: **9 386 : 8 355 там, где было 12 909 : 5 005**. Само по себе оно счёта не изменило (1-7), но без него не работает арифметика размена, и это тот случай, когда отвергать надо было прибор, а не правило.

**Потерянный тик (v158).** В рейтинговой серии 09.09.2026 дважды сработало `Script execution timed out` (матчи 6aa085d7 и 6aa086d3) — тик пропадал целиком, и армия не ходила вовсе. Страховка по времени (`USE_CPU_GUARD`, `CPU_GUARD_MS = 50`) стояла на бегунах и на выборе цели, но не на самом дорогом, что есть в боте: командир перебирает замыслы и прогоняет симуляцию на каждый. Теперь при перерасходе он раздаёт клетки ОДНИМ замыслом, без перебора и прогонов, — на порядок дешевле. Отладочная печать в том же заходе проверена и оправдана: и тик-строка, и построчная идут раз в `LOG_EVERY`, то есть двенадцать строк на десять тиков (2 135 строк на 179 записанных тиков), — таймаут дало совпадение дорогого тика с печатью, а не печать сама по себе.

**Чего разбор рейтинговой серии НЕ дал.** Диагнозы автопсии по девяти матчам (4-5, рейтинг 1 213 -> 1 201) распределены одинаково между победами и поражениями: мигание постуры 3 побед / 4 поражения, простой мили 2 / 4, шторм отказов 2 / 4. Это ровно то, о чём говорит запись про ledger: подсказки, а не причины. Дальше локальные логи не ведут — реплеи тянутся через тот же API, который в ту ночь ушёл в 502 (сайт при этом отвечал 200: лежал бэкенд, не сеть).

**Симуляция: получаемый урон тоже по модификатору (v159).** Прогноз множил на эффекты флагов НАШ удар и лечение (это делает `profileOf`), а входящий урон вычитал как есть — при том что флаг вешает на ВЛАДЕЛЬЦА ещё и +10 % получаемого урона (`EFF_DAMAGE_TAKEN_MODIFIER`, замерено в живых `effects`). При двух-трёх флагах прогноз ошибался в выживаемости на десятки процентов, и ровно эта величина решает, стоит ли вступать в размен. Принято, гейт 135/135. **Кулак в прогнозе (`USE_SIM_FIST`) — отвергнут:** идея та же, что и работала прежде (сближать прогноз с настоящим боем: в бою крипу нельзя выйти за `FIST_RADIUS` от якоря, а в раскатке было можно), но camp match31 падает во всех трёх редакциях — 15 891:23 063 при простом запрете, столько же при разрешении шага, ПРИБЛИЖАЮЩЕГО к якорю, и 14 937:19 509 с вернувшимися условиями отхода. Причинность проверена прямым отключением тумблера: без него та же строка даёт 20 265:14 589. Командир правит там всего двадцать тиков (`cmd=12/20`), и этих двадцати хватает, чтобы проиграть гонку очков — согласованный с кулаком прогноз выбирает замысел, который держит армию вместе, а в гонке нужен темп. Живьём стоит перепроверить против MetalicaX#10, где кулак и дал 8-8.

**Заодно вернулись две оговорки.** Снятие условий «его отход» и «затор» (v144) оказалось лишним: расширение власти командира несёт `underTheirFire`, а не снятие всего подряд — строка match28:brawl+heals проходит и с ними, а гейт держит 135 даже при изменившейся симуляции. Из четырёх прежних оговорок теперь сняты `theirMeleeIn` (заменена на огонь) — и только она.


**Восемь дефектов первой сшибки, и чего они стоили на рейтинге (10.09.2026, v183–v184).** Оператор смотрел повтор поражения от MetalicaX#9 (матч 3d9532, последний крип пал на 243-м тике) и назвал пять вещей: армия принимает бой рассредоточенной, у него все стрелки готовы и лекари при своих, а у нас нет; лекари в первый тик боя лечат себя фулловыми; наш стрелок оказывается вплотную к его мили и, отходя, утаскивает за собой ещё двоих; армия рвётся надвое, и двое лекарей остаются отрезанными. Всё пять подтвердились числом, и за ними нашлось восемь причин — все внутри командира.

Строй в одной рамке обеих армий (окно t=60..120): наш **5,8 в ширину и 6,1 в глубину — 35 клеток на двенадцать крипов, против его 4,5 и 3,9, то есть семнадцати**; и роли вывернуты наизнанку — **наши мили на 0,6 клетки ПОЗАДИ своего центра, стрелки на 0,9 ВПЕРЕДИ**, тогда как у него мили +0,5, стрелки −0,4. Причина первая и главная: **изготовка (`commandBrace`) не выполнялась ни одного тика за матч** — её условие `!contact && armiesClosing` невыполнимо по построению, потому что `armiesClosing` набирается только В контакте (история центров чистится, едва контакт пропал). Бот сам это печатал: `cmd=0/50 … cmd=0/90` за весь подход. Признак сближения теперь считается и без контакта; изготовка идёт против СОМКНУТОГО врага (рассыпавшегося надо обгонять), не подменяет напор (со строем поверх `pushing` сценарий camp переставал добивать: было «армия соперника уничтожена на t=986», стало 15 983:16 266; без него — уничтожение на t=558) и **идёт ПАРАЛЛЕЛЬНО гонке**, а не вместо неё: стоя в цепочке перед гонкой, она не давала отпустить за флагами ни одного крипа. Ряд получает столько мест, сколько в нём крипов, места берутся от середины наружу, крипы разбираются ближайшими парами. Дальность изготовки 10 -> 13 (при 16 падает kite).

Остальные семь. **Стрелок за мили** лежал выключенным с выводом «предмета нет» — вывод был неверен, просто мерили не тем прибором; линия считалась по САМОМУ ВЫДВИНУТОМУ мили (один смелый открывал право всем) и весила 40 рядом с членом `incNext × 100`. Теперь медиана и запрет. **Стрелок между «выстрелить» и «уцелеть» выбирает уцелеть**: все замыслы требуют разом быть вне досягаемости его мили и доставать цель, а такой клетки рядом с его строем часто нет — общий фолбэк оставлял стрелка под ударом 59 крипо-тиков из 117. **Лечение**: приказ командира брался, только если пациент ВПЛОТНУЮ, поэтому любой сосед его отбрасывал; полный сосед обходил раненого в двух клетках, потому что ближняя ветка пробуется раньше дальней — **8 лечений из 55 (14 %) ушли в цель на полных хитах, не получившую урона, у соперника таких 0 из 172**, и в трёх случаях рядом стоял крип с потерей 900–1 060. Приказ теперь действует на всей лечебной дальности, рана важнее соседства, а сам командир ранжирует пациентов по ДОШЕДШЕМУ лечению (вплотную вчетверо сильнее) — без этой третьей части первые две роняли обе строки kite. **Клетка лекаря**: ранг вёл его к БЛИЖАЙШЕМУ раненому, а ближайший раненый — тот, кто в контакте; лекарь шёл в рубку. Замер: **наши лекари в трёх клетках от его вооружённого 78 % крипо-тиков против его 49 %, выстрелов по нашим лекарям 52 против 18**, и первым в разгроме умирал лекарь. После правки (вплотную к своему, и среди таких — самая безопасная клетка; ранг по одной опасности отвергнут, он парковал лекаря на краю дальности и ронял brawl+heals до 12 056:17 958) стало **42 % против его 54 %, выстрелов 29**. **Гистерезис постуры**: изъятие для EVADE само рождало пилу с периодом ровно `POSTURE_HOLD` — семь переходов за сто тиков перед контактом, и каждый EVADE отодвигал армию к стене. Срока не ждёт теперь только RETREAT. **Болото**: о нём знала только СТАРАЯ ветка движения, а командир, забравший все приказы, — нет; замер по четырём разгромам — **усталость 489 крипо-тиков против его 36, стоим на месте 22 % против 7 %, самый долгий простой 47 тиков против семи**. Шаг в болото теперь стоит и в бою, и в строю, и в колонне. **Командир уходил из боя**, потому что центр его блоба сдвигался: `enemyRetreating` стоит в цепочке режимов ВЫШЕ условия боя, и в разгаре рубки давал `cmd=0/70:retreat mode=RACE` — армия расходилась за флагами и за десять тиков падала с десяти крипов до одного. Уступает теперь только когда его мили не рубят наших. **Потерянный тик**: сторож CPU честно срабатывал на 63 мс, а следом всё равно запускалось ПОЛНОЕ поле потока и уносило тик (`Script execution timed out` в пяти матчах из двенадцати, поровну в победах и поражениях) — счётчик `BFS_BUDGET` считает поля, а не миллисекунды.

**И отвергнутое, с числами.** `USE_BRACE_STEPS` — «приказ строя ровно на шаг». Повод выглядел твёрдо: едва строй заработал, прибор исполнения показал 62 % вместо 99 %. Посылка была неверна: клетка уходит в `TrafficManager.request`, а тот делает `creep.move(getDirection(tx − x, ty − y))`, и `getDirection` — функция ДВИЖКА, нормализующая любой вектор в одно из восьми направлений; дальний приказ и есть шаг в его сторону. 62 % — артефакт прибора, который сверяет клетку крипа с НАЗНАЧЕННОЙ. Предвычисление шага заодно лишает трафик цепочек и свопов: match29:block с 21 500:744 упал до 6 072:18 325.

**Что пачка дала на рейтинге, и чего не дала.** Две серии по двадцать матчей: v183 (шесть первых правок) — 12:8, рейтинг 1 277 -> 1 267; v184 (все восемь плюс два отката) — 12:8, рейтинг 1 267 -> 1 282, первое место в арене почти всю серию. Итог обеих: **24 победы, 16 поражений, 1 277 -> 1 282**. Предыдущая серия v182 дала 15:5 и +47, и эту разницу честнее списать на жребий, чем на регрессию: сорок матчей подряд держат 60 % побед, а двадцать матчей — выборка, в которой +47 и −10 отличаются на два-три исхода. Ни одна из восьми правок не мерилась рейтингом поодиночке; каждая мерилась СВОИМ прибором, и там сдвиги однозначны (открытость лекарей 78 % -> 42 %, лечение впустую 14 % -> ожидается 0, строй из «ни одного тика» в работающий, роли в строю из вывернутых в правильные).

**Кто нас бьёт теперь.** В серии v184 шесть поражений из восьми — от Coldkimchi (#1 и #2), остальные от MetalicaX#10. Прежний главный обидчик MetalicaX#9 в серии не встретился ни разу, а восемнадцать тестовых игр против него на этой сборке дали 10:10 против прежних 1:2. Форма поражений от Coldkimchi другая, чем у MetalicaX: матчи на 600–1 800 тиков, то есть гонка очков и затяжной размен, а не двухсоттиковый разгром. Это и есть предмет следующего разбора.

**И честная граница этой ночи: механизм двухсоттиковых разгромов от MetalicaX#9/#10 не найден.** Приборы тик-строки его не показывают. Три гипотезы, выведенные из двенадцати тестовых игр против ОДНОГО соперника, на серии из двадцати оказались пусты все три — бой у края карты (21,1 клетки до края в победах против 22,9 в поражениях), замысел YIELD (30 % замыслов в победах против 24 % в поражениях) и недостача стрелков в контакте (1,7 из 5,0 против 1,6 из 4,8). Одна из них успела попасть в код и выключена обратно. Урок записан рядом с ней: двенадцать игр против одного соперника не отличают причину от совпадения, и надёжны только те находки, где найден МЕХАНИЗМ, а не корреляция исходов.

**Пачка правок оказалась регрессией, и это видно по всей истории версий (10.09.2026, v185).** Замер `tools/series.py versions` по хранилищу: **v181 — 39 матчей, 26-13 (67 %), +50 рейтинга, +1,3 за матч**; v183 — 64 матча, 30-34 (47 %); v184 — 64 матча, 27-37 (42 %), −34; v185 — 20 матчей, 9-11 (45 %), −46. Четыре рейтинговые серии подряд после v181 дают около половины побед там, где v181 давал две трети, и рейтинг прошёл 1 277 -> 1 297 -> 1 183. Тридцать девять матчей против ста сорока восьми — это уже не жребий, и вывод обязан быть назван прямо: **восемь правок, каждая обоснованная СВОИМ механизмом и каждая улучшившая СВОЙ прибор, в сумме сделали бота слабее.** Приборы мерили то, что мерили, — открытость лекарей, лечение впустую, роли в строю, усталость, — и ни один из них не мерил исход.

Отсюда рабочее правило, которого этой ночью не хватило: **механизм — основание ПРАВКИ, но не доказательство ПОЛЬЗЫ.** Доказательство даёт только серия, и раз серия одна на пачку, пачка обязана быть из одной правки. Возвращать тумблеры надо по одному, с рейтинговым замером на каждый; иначе неизвестно, какая из восьми вредит, а какая помогает.

**Что было сделано в v185 и что оно дало.** Прибор разделил серию v184 начисто: в восьми поражениях армия дралась при мощи ниже 60 % от вражеской от 31 до 94 % боевых тиков (410 из 512), в одиннадцати победах из двенадцати — ноль таких тиков (3 из 236 по всей пачке). Против Coldkimchi это читается построчно: его мощь держится около 4 000 весь бой, наша падает до 1 500–2 000 при живых одиннадцати крипах. Командир теперь объявляет отход по ИЗМЕРЕННОЙ мощи обеих сторон (прогноз для этого негоден: он не уходит в минус никогда, см. USE_COMMAND_RETREAT), а не только выходит из режима боя — постура липкая и сама по себе держала армию в размене сотни тиков. Достижимость правила проверена, а не предположена: при пороге 2,0 исход сценария sleeper сдвинулся (14 живых -> 11, t=1316 -> 1341) — та самая проверка, которой не получила изготовка и из-за которой она год простояла мёртвой. И всё же серия v185 дала 9-11: правило верно по существу и НЕ окупилось по исходу, что ровно и есть предмет абзаца выше.

**Заодно починен прибор исполнения приказов.** Он сверял клетку крипа с НАЗНАЧЕННОЙ и только с ней, поэтому приказ в двух шагах не мог быть засчитан никогда: едва изготовка заработала, «исполнение» упало с 99 % до 62 % при том, что крипы шли туда, куда велено, — и я успел записать в дефекты то, чего не было, и починить не тот код. Теперь приближение к дальней клетке считается исполнением.

**Лекарь теряет то, чем лечит, — и это решало матч (10.09.2026, v186).** Двенадцать тестовых игр против Coldkimchi#2 на v185 дали 1:11, и одиннадцать поражений из двенадцати кончились уничтожением армии при том, что соперник заканчивал матч НЕТРОНУТЫМ: `hits 0/0` против его `16000/16000`. Разбор записи 3d97c4 свёл всё к одному числу. Тело лекаря у обеих сторон одно и то же — `h6m6`, **лечащие части стоят ПЕРВЫМИ и потому гибнут первыми**. За бой его лекари сохраняли лечащие части 804 крипо-тика из 804 (100 %), наши — 72 из 804 (8 %). К семидесятому тику, через семнадцать после контакта, двое наших из трёх были уже без лечения вовсе; лечение за бой 4 604 против его 14 256, и наши лекари простояли 729 крипо-тиков с раненым в лечебной дальности, потому что лечить им стало нечем.

Защищает его лекарей не дистанция, а **тела**: они стоят ВНУТРИ строя (рядом с его мили 86 % крипо-тиков, со стрелками 63 %), наши — на фланге (16 % и 15 %) при глубине −2,7 и боковом разбросе 3,6 против его 1,1. Ровно туда их и уводило правило «самая безопасная клетка, откуда достаёт» (v183): пустая клетка лучше по входящему на бумаге, стрелком достаётся точно так же, и в ней некому принять выстрел. Прибор той правки при этом честно улучшался — открытость лекарей падала с 78 % до 42 %, — пока лечение обваливалось. Теперь клетка лекаря ранжируется по числу СВОИХ вплотную (тел, которые примут выстрел), опасность — тай-брейк.

Результат замерен обоими способами. Прибор: лечащие части целы 90 % крипо-тиков вместо 8 %, лечат при раненом в дальности 84 % вместо 8 % (у него 100 % и 97 %). Исход: аннигиляций против Coldkimchi#2 стало **две из двенадцати вместо десяти**, счёт 2:10 вместо 1:11.

**И контроль, который надо было поставить раньше.** Конфигурация v181 (все тумблеры этой сессии выключены) против того же соперника даёт те же 1:11 — то есть неудобство Coldkimchi#2 создано НЕ пачкой правок. Но аннигиляций у неё 4 из 12 против 10 у v185: пачка не делает соперника неудобным, она стоит нам армии. Оба факта верны одновременно, и путать их нельзя.

**Что осталось после починки лекарей (v187).** Форма поражения сменилась начисто: «обе армии целы, флаги 1:4, счёт 3 210:18 145». Мы выживаем и не набираем очков, потому что вето контакта и паритета запрещает брать флаг, пока мы слабее, — а против Coldkimchi#2 мы слабее ВСЕГДА: лечение его блока перекрывает наш урон, и ланчестеровская мощь, которую сравнивает CAPTURE_EDGE, держится около нуля весь матч. Вето существует ради одного — не терять армию за флаги; при целой армии оно охраняет уже не её, а нулевой счёт. Поэтому исключение записано в ПОТЕРЯХ, а не в мощи: пока в строю не меньше десяти бойцов и лекарей и армия держит три четверти хитов, отставание по скорости очков снимает вето контакта. Паритетный пол ниже по-прежнему считает цену дебаффа, который флаг вешает на владельца.

**И отвергнутое рядом с ним: «целая армия — это и есть запас» (v187, 0:12).** Рассуждение выглядело безупречно и держалось на измеренном: вето захвата требует перевеса по МОЩИ, против Coldkimchi#2 перевеса не бывает никогда, матч кончается «обе армии целы, флаги 1:4, счёт 3 210:18 145», — значит вето, поставленное беречь армию, при целой армии берегло только ноль очков. Замер ответил без оговорок: **0:12 при девяти аннигиляциях из двенадцати** против 2:10 при двух у той же сборки без правила. Причина ровно та, что уже записана в v181 («снятие вето стоит АРМИИ»): флаг вешает дебафф на ВЛАДЕЛЬЦА, поэтому армия, взявшая его при неравной мощи, перестаёт быть целой — условие отменяет само себя через десяток тиков. Паритетный пол считает эту цену для ОДНОГО флага и не умеет считать её для режима «берём, пока целы». Наблюдение, из которого правило выросло, остаётся верным и открытым: против этого соперника мы выживаем и не набираем очков.

Это третья за сутки правка, обоснованная механизмом и отвергнутая исходом, — и тем же порядком, что записан выше: **механизм даёт право попробовать, а не право оставить.**

**Пат мерится временем, и это не открыло флаги (v189, 0:5).** Дефект v187 был назван точно: «армия цела» истинно и на ВХОДЕ в размен, до всякого пата, поэтому правило брало флаг ровно тогда, когда дебафф решал бой. Замена — признак, который на входе ложен по построению: сто тиков контакта, за которые ни одна сторона не потеряла и пяти процентов своих хитов. Счёт действительно вырос — 2 458–4 910 против 700–900 у сборки без правила, то есть флаги мы брать начали, — и армия погибла ровно так же, как под v187: 0:5 при четырёх аннигиляциях, пачка остановлена досрочно. Вывод одной строкой: **дебафф флага бесплатен ЕМУ, а не нам.** Его лечение 237 228 против нашего урона 156 100 — полтора раза сверху, и четыре флага поверх этого; наш урон под одним дебаффом падает примерно до 125 000 при том же его лечении, и «не можем убить» превращается в «не можем даже давить».

**Правило v190 не сработало ни разу, и это мой дефект, а не его.** Счётчик пата вычислялся ВНУТРИ условия, загейченного на `USE_CAPTURE_IN_STALEMATE`, а тумблер выключили при отказе от v189. Счётчик замер на нуле, правило «раздеть лекаря вместо того, чтобы убивать» не включилось ни в одном тике, и двенадцать тестовых игр, которыми его собирались оценить, измерили предыдущую сборку. Прибор теперь считается безусловно — тем он и прибор, — а строка `t=` несёт `pat=` и `strip=`, чтобы такое не повторилось молча.

**Зато случайность дала контроль дороже самого правила.** Одна и та же сборка сыграла против Coldkimchi#2 два батча по двенадцать игр: **2:10 оба раза — и семь аннигиляций против двух.** То есть счёт на двенадцати играх воспроизводится, а число аннигиляций на неизменной сборке гуляет от 2 до 7. Всякий мой довод, опиравшийся на счёт аннигиляций, опирался на шум; доводы, опиравшиеся на счёт побед (0:12 у v187, 0:5 у v189 против 2:10), стоят по-прежнему.

**Наши стрелки — не группа, и это измерено (10.09.2026, v191).** По двенадцати играм: урона мы получаем в 1,5–3,9 раза больше, чем наносим, в КАЖДОМ матче, включая выигранные, и весь он садится на мили — на двухсотом тике у наших четверых мили остаётся от 0 до 16 атакующих частей из тридцати двух, у его — от 24 до 32. Но дело не в мили. Реплеи: в матче со стиранием (3d97c4) диаметр группы наших пяти стрелков в тиках контакта — 21 клетка против восьми у него, и 3,7 крипа из 9,8 стоят дальше пяти от центра собственной армии; в матче, который стиранием не кончился (3d97d8), диаметр 3 и дальше пяти нет никого. Дальше восьми в бою уходили ВСЕ одиннадцать бойцов: стрелки 29 % крипо-тиков, лекари 32 %, мили 18 %, медиана отрыва 16 клеток, максимум 49. Пятеро стрелков, растянутые на двадцать клеток, не могут стрелять в одну цель — прибор `conc` даёт 1,3 из 5, — а трое его лекарей возвращают 216 хитов в тик: цель начинает терять хиты только под залпом ЧЕТЫРЁХ стрелков разом. Отсюда и «не можем убить», и вся арифметика пата выше.

Поводок в коде уже был — восемь клеток от центра вооружённых, — и спрашивал не то: он требовал врага РЯДОМ С КРИПОМ, а у крипа, отставшего от боя, врагов рядом нет. Он возвращал всех, кроме того единственного, кто ушёл. Теперь он держит, пока в контакте АРМИЯ. Строка `t=` несёт `spread=<диаметр группы стрелков>/<вооружённых дальше поводка>`.

**Мили не участвует в бою вовсе, и это не его вина (10.09.2026, v192).** Два замера рядом: наш мили стоял вплотную к врагу в 1 % замеров, его блок менял клетку в 71–76 % тиков контакта. При равной скорости блок, который каждый тик уходит, догнать нельзя — значит один процент не дефект логики мили, а геометрия соперника, отказывающегося от контакта. Дефект в том, что `holdMelee` делает с этим фактом: он замораживает мили НА МЕСТЕ, а измеренное место — три клетки до ближайшего врага, то есть внутри его дальности выстрела и вне своей дальности удара. Четыре тела с 32 частями ATTACK весь бой не наносят ничего и принимают всё; отсюда постоянная этих игр — получаем в 1,5–3,9 раза больше урона, чем наносим, во всех матчах, включая выигранные. Довод «мили работает щитом» тоже не проходит, и арифметика коротка: его пятеро стрелков дают около 300 в тик, наши трое лекарей возвращают 216 — тело, поставленное в его кольцо, проигрывает размен по построению. Мили, ни разу не дотянувшийся за окно контакта, уходит за спину своих стрелков. Признак ИЗМЕРЯЕТСЯ, а не назначается: доля касаний за пятьдесят тиков контакта, до заполнения окна она равна единице (вход в бой не меняется) и сама поднимается против того, кто в контакт идёт, — правило снимается без порога, подогнанного под сегодняшнего соперника.

**И приговор поводку по исходу: 2:10, то есть ничего (10.09.2026).** Прибор правки сдвинулся так, как обещал механизм: диаметр группы стрелков в бою упал с 21 до 3–8 (у него 8), вооружённых дальше поводка стало ноль, `conc` вырос с 1,3 до 1,8. Форма матчей сменилась начисто — матчи пошли по 1700–1800 тиков вместо 900–1400, счёт стал близким (15812:21575, 17909:22012, 20377:16977) вместо 0:13741. **Исход не сдвинулся ни на игру: 2:10 у v186, 2:10 у v190, 2:10 у v191.** Три разных сборки против одного соперника дают один и тот же счёт, и это, вместе с калибровкой аннигиляций выше, задаёт цену всякому будущему доводу: двенадцать игр отличают 2:10 от 0:12, но не отличают 2:10 от 3:9.

Разбор двенадцати по флагам делит поражения надвое. Пять — стирание: армия ноль, он держит все семь флагов, 25 очков в тик. Пять — забег, проигранный в НАЧАЛЕ: армия цела, и в двух из них мы заканчиваем ВПЕРЕДИ по флагам (11 против 7 и 18 против 4) и всё равно проигрываем по накопленному. В победах наши флаги в конце дают 13,5 очка в тик, в поражениях 5,0. Значит фронта ровно два: стирания и скорость набора очков в первой трети матча — и второй фронт упирается в цену флага, а она в модели неверна (см. ниже).

**Забег по флагам мы ведём двумя разведчиками, и это не доктрина, а пропущенная категория (10.09.2026, v194).** Медиана скорости очков по двенадцати играм v191: наша 3 против его 5 к двухсотому тику, 3 против 10 к четырёхсотому, 3 против 15 к шестисотому и 0 против 15 к девятисотому. Мы держим ОДИН флаг всю первую треть матча, потом ни одного. Отряжённых бойцов при этом ноль во ВСЕХ двенадцати матчах — флаги берут два разведчика по сто хитов, которых снимает один залп. Вето захвата тут ни при чём: `POISED` блокирует около 150 крипо-тиков на матч.

Механика в ярлыке: отряд набирается только против соперника, помеченного `farmer`, а ярлык требует, чтобы тот НЕ ДРАЛСЯ — `raceNow` хочет его россыпи, `dryHunt` хочет тишины по огню и урону за окно. Coldkimchi#2 делает и то и другое сразу: держит плотный блок в контакте (`massed=true` каждый тик) и отряжает два-четыре крипа за флагами. Категории «дерётся и фармит одновременно» у бота нет, поэтому выпуск не срабатывает ни разу. Теперь основание выпуска — не ярлык соперника, а НАША измеренная бесполезность: мили, ни разу не дотянувшийся за окно контакта, отряжается за флагами по прежней проверенной цепочке с её паритетными полами, а те после v193 считают его вклад по измеренной доле — модель сама разрешает отпустить того, кто ничего не даёт, и сама запретит, когда он начнёт бить.

**И третье правило подряд, которое не сработало ни разу, — на этот раз пойманное прибором за два матча.** v192 уводил недостающего мили из вражеского кольца; живой замер дал `out=0`. Причина: в бою клетки раздаёт командир, и ступень `slot != null` стоит в цепочке движения выше `holdMelee`, то есть и выше правки. Отсюда рабочее правило, которого не хватало дважды за сутки (v190 и v192): **у всякого нового правила должен быть счётчик срабатываний в строке `t=`, и первый же матч батча проверяется на то, что счётчик не ноль.** Мёртвое правило стоит целого батча — часа игр, измеряющих предыдущую сборку.

**Ряд, нацеленный в три, приходит в четыре (10.09.2026, v196).** Ряд стрелков в `planBlock` ставится в `RANGED_RANGE` от ближайшей угрозы — ровно в три. Его блок меняет клетку 71–76 % тиков контакта, и шаг делается ОДНОВРЕМЕННО с нашим, поэтому ряд, нацеленный в три, приходит в четыре. Консоль согласна: медиана дистанции наших стрелков до ближайшего его крипа — 4 при дальности выстрела 3, в дальности они 41 % замеров, `reach` даёт 1,5–2 из 5 и `conc` 1,3–1,8 из 5 — при том, что трое его лекарей возвращают 216 хитов в тик и цель начинает терять хиты только под залпом ЧЕТЫРЁХ. Прицел ряда сдвинут на клетку ближе, когда доля касаний измеряется низкой (то есть он уклоняется); против идущего в контакт она равна единице и прицел прежний.

**И отвергнутое рядом с ним (v195): мили позади стрелков.** Правило срабатывало 2 тика за живой матч — против сомкнутого блока строй раздаёт `planBlock`, а не `planFight`, где правка жила. И посылка была неверна: в `planBlock` мили стоит в общей линии со стрелками НАМЕРЕННО, и замер, который это держит, записан там же — строй «стрелки впереди, мили позади» (v37) кайтер стенда разоружал первыми. Живые числа согласны: гибнут только атакующие части (0–16 из 32), стрелковые целы (24–30 из 30), то есть тела мили работают самой дешёвой бронёй, какая у нас есть, и убирать её — значит подставить под тот же огонь единственное, чем мы бьём.

**Общий счёт этой ночи по правилам, которые не сработали: три из шести.** v190 (раздеть лекаря) — счётчик пата был загейчен отвергнутым тумблером и стоял на нуле; v192 (мили из кольца) — `out=0`, потому что в бою ступень `slot` командира старше `holdMelee`; v195 (мили позади) — `back=2`, потому что расстановку делает другая функция. Каждое стоило батча или его части. Отсюда рабочее правило, вписанное выше и повторённое здесь: **у нового правила обязан быть счётчик срабатываний в строке `t=`, и первый матч проверяется на то, что счётчик не ноль, ДО того как запускается серия.** v196 первым его прошёл: `lead=139` за матч.

**Итог v196 и v192 по живому замеру — оба выключены с числами.** v196 (упреждение ряда на клетку) стал ПЕРВЫМ за сутки правилом с ненулевым счётчиком срабатываний: `lead` 19–139 за матч. И он же первым показал, зачем счётчик нужен не один: **правило работает и своего прибора не двигает** — `reach` за окно боя 1,11 против 1,13 у сборки без него, `conc` 1,48 против 1,48, — а исход 0:4 при трёх аннигиляциях. Открытое объяснение: ряд, придвинутый на клетку, входит в его кольцо глубже, залп сгоняет стрелков обратно, и средняя дистанция не меняется. v192 (мили из кольца) выключен раньше и по другой причине — `out=0`: ступень стояла в цепочке ДВИЖЕНИЯ, а в бою клетки раздаёт командир, и `slot != null` старше `holdMelee`.

**Что остаётся включённым после этой ночи и почему.** v191 (поводок держит, пока в контакте АРМИЯ) — исход не сдвинул (2:10), но свой прибор сдвинул начисто (диаметр группы стрелков 21 → 3–8, вооружённых за поводком 0) и по существу верен: прежнее условие не возвращало ровно того, кто ушёл. v193 (цена флага по ИЗМЕРЕННОЙ доле касаний мили) — исправление перевёрнутых цен, открытое с матча 47; живой серией отдельно не мерилось. v194 (мили, который не достаёт, отряжается за флагами) — срабатывает редко, потому что паритетный пол выпуска считает нас вдвое слабее, и это верно по существу: мы действительно теряем крипов, а он нет. Тумблер оставлен включённым — он оживает ровно тогда, когда мощь перестанет проваливаться.

**Прибор пата починен, и первое же его применение закрыло правило v190 (10.09.2026, v198–v199).** Окно пата очищалось на КАЖДОМ тике без контакта, а в стоянке `contact` мигает: в матче 3d9894, где 1700 тиков не погиб ни один крип ни у нас, ни у него, счётчик доходил лишь до 48 из ста нужных. Признак, который не может стать истинным в том самом состоянии, ради которого написан, ничего не измеряет — и именно поэтому v189 не с чем было сравнивать. Теперь разрыв короче двадцати тиков окно не роняет, и прибор ожил сразу: `pat=114/131` и `pat=0/208` в трёх проверочных матчах.

Вместе с прибором впервые в жизни заработало правило v190 — «раздеть его лекаря вместо того, чтобы убивать»: `strip=157` и `strip=169` тиков сосредоточенного огня по ОДНОМУ лекарю. И первый же замер механизма его закрыл: **его лекари сохранили лечащие части 3603 крипо-тика из 3603, то есть 100 %.** Неверна была посылка коммита — «наш залп около 300 в тик». По замеру до цели достают 1–2 наших стрелка (`reach` 1,1–1,8 из 5), это 60–120 в тик, а трое его лекарей лечат друг друга на 216: раздеть лекаря нельзя, пока стрелки не достают вчетвером. Тумблер выключен с этими числами; сам прибор оставлен — он верен и нужен следующему, кто станет читать пат.

Побочно тот же реплей подтвердил, что правка v186 держится: наши лекари сохраняют лечащие части **96 %** крипо-тиков против 8 % до неё (у него 100 %).

**Строй был вывернут наизнанку, и выворачивал его сам командир (10.09.2026, v200, три замечания оператора по повтору).** Все три названы по повтору и все три подтверждены замером реплея 3d9943.

*Мили уезжают из-под лекарей.* На первом тике контакта под лечением **один мили из четырёх** (t=60–61). Это голова той же цепочки, что описана в v186: мили теряет атакующие части, лечение их не возвращает, и к двухсотому тику у наших мили 0–16 частей ATTACK из 32 против его 24–32.

*Четверо стрелков стоят в стороне и не стреляют.* В дальности выстрела **0–3 из пяти**, а на тиках 133, 134, 136, 140, 144 — **ноль из пяти**. Механика оказалась замкнутым кругом: стрелку запрещена всякая клетка ближе МЕДИАННОЙ линии мили (`behindMelee`, v183), а мили по замеру стоит ПОЗАДИ — глубина по оси на врага у мили −1,35 против +0,68 у стрелков, — значит линия уходит на 4–5, отбор клеток пустеет целиком, и стрелок проваливается в общий добор, который про дальность выстрела не знает вовсе.

*Лекари в первой линии.* Глубина по оси у лекарей **+0,32** — впереди мили; в **155 тиках из 335** лекари в среднем ближе к врагу, чем мили; на t=63, через три тика после контакта, один лекарь уже без лечащих частей. Причина в ранге v186: экран считает ТЕЛА вокруг клетки и ничего не знает о глубине, а `mates` — это раненые, то есть ровно те, кто на фронте.

Все три правки — жёсткие фильтры в `place` внутри `commandFight`: клетка мили только в дальности лечения от живого лекаря; линия запрета для стрелка не дальше `RANGED_RANGE`; клетка лекаря только тогда, когда хотя бы один свой вооружённый стоит к врагу ближе. Фильтры, а не ранги: при отсутствии подходящей клетки работает прежний добор, и строй не может замереть.

**И побочная находка, объясняющая два сегодняшних промаха: против сомкнутого блока расстановку решает `commandFight`, а не `planFight`/`planBlock`.** Приказ командира перебивает слот (`USE_ORDER_IS_LAW`), а приказы получают 8–12 крипов из 12 — поэтому v195 (мили позади стрелков, правка в `planFight`) дал `back=2`, а v196 (упреждение ряда, правка в `planBlock`) хоть и срабатывал, но не двигал `reach`. Правило, поставленное в расстановку, которую перебивает приказ, не измеряет ничего.

Приборы после правки, за окно боя 80–600: **стрелков в дальности выстрела 72 %** (было около 30 %), **мили под лечением 91 %** (было 25 % на входе в бой), **лекарей за линией 97 %** (было: впереди мили в 46 % тиков). Строка `t=` несёт `guns=`, `mheal=`, `hline=` — и это РЕЗУЛЬТАТ раздачи, а не факт вызова правила.

**План и явь разошлись, и это объясняет всю серию честных неудач (10.09.2026, v201).** Серия v200 дала 2:10 при том, что все три ограничения оператора держались: прибор `guns` (назначенные командиром клетки) показывал стрелка с целью в дальности в 69 % случаев. Прибор `reach` (где крипы РЕАЛЬНО стоят) в той же серии дал 1,11 стрелка из пяти — 22 %. Послушание приказа при этом 99,9 %: крипы идут ровно туда, куда велено. Весь разрыв в том, что клетка выбрана по СЕГОДНЯШНИМ его позициям, а его блок меняет клетку 71–76 % тиков контакта и шагает ОДНОВРЕМЕННО с нами — к моменту прихода цель уже вне дальности.

Отсюда и общий диагноз этой ночи: **каждая правка расстановки честно двигала свой прибор и не двигала бой, потому что мерила ПЛАН, а не ЯВЬ.** Клетка стрелка теперь берётся с запасом на его шаг (`RANGED_RANGE − 1`), и — в отличие от v196, пробовавшего ту же мысль в `planBlock`, — правка стоит в `commandFight`, то есть в коде, который против этого соперника исполняется: приказ старше слота, и приказ получают 8–12 крипов из 12.

Результат серии из двенадцати: **`reach` 1,71 против 1,11 (медиана 2 против 1), `conc` 1,60 против 1,46, счёт 3:9 при четырёх аннигиляциях против 2:10 при пяти.** Впервые за сутки сдвинулся прибор яви, и впервые счёт вышел за потолок 2:10, который держался у v186, v190, v191 и v200. Двенадцать игр не отличают 3:9 от 2:10 — это записано выше и остаётся верным, — но здесь за счётом стоит сдвиг измеряемого механизма, а не один-два исхода.

**Что осталось открытым.** Порог остаётся прежним: цель начинает терять хиты только под залпом ЧЕТЫРЁХ стрелков (его трое лекарей возвращают 216 в тик), а `conc` — 1,6 из 5. Дальность теперь есть у двоих, значит следующий предмет — не позиция, а ВЫБОР ЦЕЛИ: сейчас стрелок без досягаемой цели фокуса бьёт по наименее раненому из достижимых, то есть каждый по своему.

**Два замечания оператора по повтору, оба локализованы (10.09.2026, v202).**

*«Хилеры хилят ближайшего фулового крипа вместо битого в нескольких клетках».* Механизм ровно такой: `need` = дефицит ПЛЮС ожидаемый входящий урон, поэтому крип на полных хитах, просто стоящий под огнём, попадает в кандидаты, а ближняя ветка (вплотную) берётся раньше дальней ВСЕГДА — значит полный сосед обходит раненого в двух-трёх клетках. Правило против этого написано по тому же замечанию ещё в v183 и лежало выключенным: отключено оно было не своим замером, а оптом при откате пачки. Число лежало рядом с ним всё это время: 8 лечений из 55 (14 %) уходили в цель на полных хитах, у соперника таких 0 из 172, и в трёх случаях рядом стоял крип с потерей 900–1 060.

*«Крипы разъезжаются и не держатся единым кулаком».* Разъезжаются ЛЕКАРИ, и только они. Дальше восьми от центра армии: мили 11 крипо-тиков из 3 428 (0,3 %), стрелки 136 из 5 756 (2,4 %), лекари **1 402 из 5 379 — 26 %**, медиана отрыва 23 клетки. Причина — одно условие: поводок исключал всю поддержку (`!support`, где `support = healer || wounded`), то есть ровно тех, кто уходил, никто и не возвращал.

Проверка тремя матчами: механика обеих правок держится — лекари за поводком **0 из 495** лекаро-тиков, доставленное лечение выросло с 15 355 до 18 660 за матч. **Исход при этом хуже: 0:3 при трёх аннигиляциях, размен урона 3,45 против 2,23** (получаем 22 950 против 18 261, наносим 6 658 против 8 199). Три матча не отличают регрессию от жребия — та же выборка не отличает 2:10 от 3:9, — но и безвредность на них не доказывается. Гипотеза на проверку следующей серией: поводок держит лекаря в кулаке, а кулак стоит под огнём; прежде лекарь из-под огня уходил — переставал лечить, но и переставал умирать. Оба тумблера (`USE_LEASH_HOLDS_HEALERS`, `USE_HEAL_DEFICIT_FIRST`) снимаются одной строкой.


## Отвергнутые правила (перенесено из кода, v203, этап 2)

Эти правила были написаны, измерены и выключены. Их тумблеры удалены из кода: **выключенный тумблер рядом с живым кодом
читается как «переключатель, который можно щёлкнуть», а код вокруг за сотню версий уходит вперёд** — и щелчок делает уже не
то, что обещает комментарий. Ровно так родилось правило, написанное ВНУТРИ `behindMelee`, чей мастер-тумблер был выключен.
Перепись решений (этап 1) подтвердила, что все они дают ноль и на 135 сценариях стенда, и в живом матче. Текст замеров
сохранён здесь дословно — это самое ценное, что в них было.

### USE_ROTATE_OVER_SLOT

```
РОТИРУЮЩИЙ ИДЁТ К ЛЕКАРЮ ПОВЕРХ СЛОТА (v126, серия 347–366): planBlock/planFight исключают его из фронта, и он получает слот
ЗАДНЕГО ряда, а в порядке движения слот стоит раньше ротации — боец шёл в тыловой слот, где лекаря может не быть (матч 506,
Coldkimchi: melee_1 в ротации с 92-го по 119-й, 27 тиков при дефиците 400; трое из четырёх мили в ротации на 101–114-м).
НА СТЕНДЕ НЕОТЛИЧИМО: 26 сценариев и 8 dart до цифры те же (slotHold стоит раньше обоих, а ротирующий на стенде — вплотную
к врагу); живьём ближайший лекарь стоит у фронта, и «к лекарю» есть «к фронту». Выключено как незамеренное. */
private const val USE_ROTATE_OVER_SLOT = false
```

### USE_MELEE_GUARD

```
ПРИКРЫТИЕ ТЫЛА (v135): мили, которому некого бить, идёт не в слот строя, а к тому нашему стрелку, лекарю или
раненому, к которому ближе всего его вооружённый мили, и встаёт рядом. Замер по двенадцати поражениям от блобов
(тестовые игры 08.09.2026): его мили смежны 84–143 крипо-тика против наших 14–20, наш ближайший мили дальше пяти
клеток в 61 из 114 смежных пар, а верхние блокировки решения — `!covered` (1183) и `!inLine` (1035), которые poker
и так обходит. То есть мили не мешают бить: они стоят в строю, пока его мили идут мимо них в наш тыл. */
private const val USE_MELEE_GUARD = false
```

### USE_RALLY_BEFORE_BLOB

```
СБОР ПЕРЕД БЛОБОМ (v135): пятнадцать отвергнутых проб меняли, ЧТО делают крипы, когда блоб уже на них, а вход
проигран раньше — на первом контакте телеметрия читает `reach=2/5`: цель достают два наших стрелка из пяти, он бьёт
двенадцатью, и за двадцать тиков мы теряем 9 500–10 600 хитов против его 5 800–6 500. Пока его сомкнутая армия ещё
НЕ в контакте (никого ближе RANGED_RANGE + 1) и достающих меньше двух третей, крип идёт к массе своих.
ОТВЕРГНУТО в двух формах. Без условия близости — 29 упавших строк гейта: шесть его вооружённых кучей это обычное
дело, и армия собиралась вместо игры за флаги (army, camp, screen, farm+weak, scouts, grab — все по очкам).
С условием «его центроид ближе ENGAGE_RANGE + RANGED_RANGE» гейт 131/131, но 1-5 и 0-6 против 5-7 и 2-10 у v135:
тик, потраченный на сбор, отдаёт блобу дистанцию, а собраться всё равно не успеваем — он входит быстрее. */
private const val USE_RALLY_BEFORE_BLOB = false
```

### USE_RANGED_KEEPS_BEHIND

```
СТРЕЛОК ЗА СПИНУ МИЛИ (v135): его мили первым доходит до нашего стрелка или лекаря в 60 % тиков входа, у него так
в 0–10 % (замер по 6aa0008a и 6aa0037a: наш мили ближе в 2–3 тиках из 28, его — в 15–19). Расстановкой это не
лечится — мера не дрогнула, — потому что слот в бою почти не используется: крип идёт к цели. Поэтому цель и
правится: стрелок, к которому его мили не дальше, чем к нашему ближайшему мили, идёт за спину этого мили.
ОТВЕРГНУТО: 1-5 и 1-5 против 5-7 и 2-10 у v135, гейт 130/131 (match31:camp). Стрелок, уходящий за спину, теряет
цель — его дальность три, и клетка за мили обычно вне её, — так что закрытым он оказывается ценой молчания. */
private const val USE_RANGED_KEEPS_BEHIND = false
```

### USE_PURE_COMBAT

```
ЧИСТЫЙ БОЙ (v139): пока командир ведёт бой с сомкнутым блобом, прежняя цепочка целей молчит целиком. Две ошибки
наследия уже найдены и исправлены — `slotHold` перебивал приказ (в блобе это ВСЕ четыре мили с первого тика), и
порядок интентов в симуляции был обратным движку (движение идёт последним, атака считается по позиции ДО него).
Этот тумблер проверяет, есть ли ещё такие: если чистый бой играет лучше, мешает именно накопленная логика.
ОТВЕТ: НЕ МЕШАЕТ. Чистый бой дал 2-6 и 2-6 — ровно то же, что с полной цепочкой правил (2-6 и 2-6). Значит узкое
место не в наследии, а в КАЧЕСТВЕ ПРОГНОЗА: план оценивается симуляцией на четыре тика с приближённой моделью
врага, и на этом горизонте хорошие планы от плохих не отличаются. Переписывание бота с нуля на том же прогнозе
дало бы то же самое. */
private const val USE_PURE_COMBAT = false
```

### USE_FORWARD_SEARCH

```
ПРОГНОЗ РАЗМЕНА НА ТИК ВПЕРЁД (v136): двадцать проб провалились потому, что каждая приближает потиковый ВЫБОР
одним постоянным правилом, а числа, которых они не сдвинули, — следствия выбора: его ожидаемый урон 20 000 против
наших 7 400, смежность его мили 79–85 крипо-тиков против наших 14–22, его мили первым доходит до наших мягких в
60 % тиков против наших 0–10 %. Здесь крип ВЫБИРАЕТ: своя клетка и восемь соседних, для каждой — что мы с неё
нанесём и что получим в следующий тик (его мили в MELEE_KEEP_RANGE шагнёт и ударит). Порядок ЛЕКСИКОГРАФИЧЕСКИЙ,
не взвешенная сумма: сперва больше своего урона, потом меньше входящего — сумма дала бы купить безопасность ценой
огня, чем и провалились все пробы об отходе. Работает только при сомкнутой армии врага и не для лекарей.
ЗАМЕРЕНО И ОТЛОЖЕНО: 4-8 и 2-10 против 5-7 и 2-10 у v135 (гейт 131/131), с ярусом выживания (USE_SEARCH_SURVIVAL)
3-3 и 0-6 — вровень или хуже. Причина названа оператором и подтверждается замерами: КАЖДЫЙ КРИП СЧИТАЕТ ЗА СЕБЯ.
Двое выбирают одну клетку, третий загораживает четвёртого, и лучший ход каждого не складывается в лучший ход
армии; его же строй ходит линией шаг в шаг, то есть решение у него ОДНО на всех. Оценка клетки здесь верна и
остаётся ядром для командира (см. USE_COMMANDER), а покриповое применение выключено. */
private const val USE_FORWARD_SEARCH = false
```

### blobClose

```
...и только когда блоб УЖЕ БЛИЗКО: первый срез («всякая сомкнутая армия») уронил 29 строк гейта — армия
собиралась вместо игры за флаги, потому что шесть его вооружённых кучей это обычное дело. Нужен вход:
его вооружённый центроид ближе ENGAGE_RANGE + RANGED_RANGE от нашей массы
val blobClose = combatEnemies.isNotEmpty() && centroidOf(armedEnemies.ifEmpty { combatEnemies })
```
