# Переработка бота Pain and Gain — архитектура и путь

Статус: **утверждено оператором 13.09.2026**, исполняется этапами (раздел 11). Первый вариант этого файла (13.09, план
без разрешения кодить) содержал диагноз и пять этапов; после слова оператора об архитектуре («все решения один раз, в
конце хода только непротиворечивые интенты, место для симуляции врага и выбора постановки, армия из подгрупп с
заданиями, бой из фронта + опасности + симуляции, расширяемость») проект проверен против кода тремя читателями и
двумя проектировщиками-оппонентами и переписан. Числа диагноза сняты с v235 (e2db549); ссылки `:NNNN` — строки
`PainAndGain.kt` на v235.

## 1. Диагноз — числами

| что | сколько |
|---|---|
| `PainAndGain.kt` | 10 405 строк, из них 5 135 (49 %) — комментарии; с `InfluenceMap`/`DistanceMap`/`TrafficManager` 12 115 |
| тумблеры `USE_*` | **277**: 174 включены, 103 выключены; 233 употребления в условиях, 143 ветки `if (USE_…)` |
| функции | 211; `runArmy` 2 692 строки, `commandFight` 637, `planBlock` 327, `tickBody` 302, `simulate` 184 |
| состояния | три пересекающихся набора: `Posture` (5), `CmdMode` (3), `Intent` (5) — до 75 сочетаний, и каждое где-то читается |
| ступени шага крипа | 28 тегов `whyTag` в одной цепочке `when`, порядок веток — единственное описание приоритетов |
| фазы тика | 22 метки `cpuMark` |

**Где принимаются решения** (замер v235 по коду и по логам):

- **клетка крипа — в пяти местах**: изготовка `commandBrace` (ряды по роли до контакта), слоты строя `planBlock`
  (линия одним рядом, `rowCells`), колонна марша `commandMarch`, цепочка из 28 ступеней в `runArmy` (`kite`, `wall`,
  `healMate`, `slot`, `post`, `prey`, …) и покрипная оценка командира `commandFight` (`scoreMelee`/`scoreRanged`/
  `scoreHeal`, замыслы, симуляция). В бою (`mode=FIGHT`) клетки даёт командир (51–54 % крипо-тиков), ряды изготовки
  рассыпаются с первым уроном; вне боя — ступени. Одно и то же понятие «где стоять лекарю» реализовано в v142, v183,
  v186, v200, v208, v224, v228, v234 и v235 — восемь редакций, из них живут четыре;
- **цель огня и лечения — в четырёх**: `commandFire` (приказ), `shoot` (свой выбор стрелка), `strike` (мили),
  `commandHeal` + `healAndShoot` (приказ и свой выбор лекаря, причём приказ отвергнут замером и действует только
  вплотную);
- **флаги — в трёх**: `captureBlock` (ворота), `chooseFlagObjective` (цель армии), `commandGoal` (цель марша) и
  отдельно — своё паросочетание бегунов в `runRunners`;
- **режим боя — в трёх**: постура (`posture`, гистерезис), режим командира (`cmdMode`), замысел (`intent` по
  симуляции); постуру ставит режим, режим смотрит на постуру, а замысел выбирается прогнозом, который, по замеру v235,
  равен исходу (отрицателен в 71 % строк поражений и 34 % побед) и исход не различает.

Последствие измерено на v231–v235: каждое новое правило встречало четыре прежних (v234: три редакции, потому что
досягаемость попала в условие бегства, потом в гонку за флагом; v235: назначение лекаря исполнялось 25–40 % тиков,
потому что выше стояли `order`, `kite` и `wall`). Правки не читаются, потому что нет одного места, где видно, почему
крип стоит там, где стоит.

## 2. Конвейер тика

```
World ──► Forecast ──► Strategist ══► Tactician ──► Arbiter ──► Executor ──► Memory.commit
факты     враг t+1,    постановка:     предложения    непротиво-   вызовы API   единственная
и меры    адресная     отряды +        по крипу:      речивые      без          запись
          опасность,   задания +       ход/контакт/   интенты      ветвлений    между тиками
          оценка       тактика         дальний
           ╰─────────────── Instruments: measure() каждой стадии → k=v, why=mission:term ───────────────╯
```

Правила конвейера (это и есть «решения один раз»):

- каждая стадия — чистая функция `вход → выход`, выход неизменяем; игровой API читает только World, пишет только
  Executor; межтиковое состояние читается из `Memory` и пишется **одним** `Memory.commit` после Executor. Сегодня
  записи вперемешку с решениями: `lastHits/lastCell` :7707, `prevShooters` :7717, `focusId` :6084, `planCapture`
  при запросе шага :7706;
- **двойная стрелка Strategist ⇒ Tactician не случайна**: чтобы оценить кандидатную постановку, стратег вызывает
  тактика вхолостую (сегодня `commandFight(trial)` :6696 → `simulate` :6699). Поэтому Tactician обязан быть чистым;
  сегодня он пишет в глобальные `focusId`, `rangedLevelLatched` :9370, `InfluenceMap.stampHealNeed/claim` — и
  повторный вызов уже нечестен;
- **ни одна стадия не переопределяет решение другой**: конфликт — это два предложения с приоритетами, которые
  сводит Arbiter, и в приборах видно, кто проиграл (`why=conflict:…`).

### World — факты и меры

`Unit` (свой/чужой: живые ATTACK/RANGED/HEAL/MOVE **с порядком частей** — оружие впереди; hits, fatigue, позиция,
`canMove`), флаги и счёт, terrain, `DistanceMap` (потоки), `InfluenceMap` (поля этого тика; ядра `K_MELEE`/`K_HEAL`
уже считают «шаг + удар»), меры из историй `Memory` (контакт, масса, размен за окно `STALL_TICKS`, `lostRaceNow`,
сближение, `catchable`, прибытие). Готовый шов в коде — `Ctx` :3517/:3699.

### Forecast — место симуляции врага

- **`EnemyModel`** — две функции: `targetOf(shooter, ours, reach)` = проверенная модель его выбора цели (лекарь в
  досягаемости → ближайший → меньшие хиты; онлайн-A/B против правды следующего тика попадает в 85–91 % тиков,
  `wallTargetOf` :10057, :6919-6933) и `stepOf(enemy, world)` = модель шага из `simulate` :9260-9285 (мили к
  ближайшему мягкому, стрелок держит 3, лекарь к раненому; альтернативы замерены и хуже). Сегодня `targetOf` питает
  только стену лекарей, а внутри симуляции враг бьёт «минимум хитов» — другое правило; в новой схеме **одна модель
  в обоих местах**.
- **`predict(world) → Prediction`** — позиции и цели врага на t+1, O(E).
- **`threatAt(pred, creep, cell)`** — **адресная опасность t+1** для клетки-кандидата: кто из его стволов, шагнув по
  модели, выберет именно этого крипа в этой клетке. Это то, что тактику нужно вместо «simValue по клетке»: 14 крипов
  × 9 клеток × 12 стволов ≈ 1,5 k операций; симуляция по клетке стоила бы ~500 мс при лимите 100 (живьём 60–95 на
  холодных тиках). Заготовка есть: `addressedAt` :8600 (`USE_ADDRESSED_DANGER`, выключен).
- **`evaluate(pred, cells, fire, k) → Outcome`** — прокат постановки на k тиков; возвращает **структуру** (потери
  хитов обеих сторон, смерти, **раздетые части**, дельта флагов), а `power` — производная. Сегодняшнее одно число
  `power(us) − power(them)` :9336 не уходит в минус никогда :6746 — доктрина «аннигиляция = проигрыш» им непроверяема.
  Глубина по замеру 1 (ошибка 854 @1, 1 304 @2, 3 855 @4); глубже — только когда замер покажет.
- **`selfCheck`** — прибор ошибки прогноза :9515-9518 переезжает как штатный: модель, которая не бьёт правду, видна.
- **Бюджет**: инкумбент оценивается всегда; кандидаты — пока `cpuMs() < CPU_GUARD_MS − резерв(Tactician + Arbiter,
  p95 по приборам)`; при нехватке — инкумбент без оценки (`cpuTight` :6679 как есть).

### Strategist — постановка: отряды, задания, тактика

Выход — `Disposition = List<Squad>`, **каждый крип ровно в одном отряде** (включая неподвижных и раненых — иначе
родится второй реестр; сегодня план строится на `commandArmy` :6682, а оценивается на `mobileArmy` :6699).

**Что** и **как** разделены:

| `Mission` (что) | сегодня это |
|---|---|
| `Goto(point)` | `commandMarch`, `commandGoal`, RESERVE/EXIT бегунов |
| `Take(flag)` | бегуны по паросочетанию :4384-4446, `commandRace` :8385, хранитель (`Take` из одного, уже стоящего, `updateKeepers` :7948), `grabberOf` |
| `Fight(enemyGroup)` | ANNIHILATE/FIGHT, погоня `assignChase` :8338 = `Fight({калека})` с бюджетом генератора |
| `Escort(squadId)` | сегодня нет (лекари при бегунах); в первой реализации «стена вокруг жертвы» — вариант строя внутри `Fight`, не отряд |

`Tactic` (как) = веса термов + параметры строя (дистанция фронта, ширина, ряды, якорь, кулак). Нынешний `Intent`
(PRESS/HOLD/YIELD/FOCUS/KITE = тройки `weightsOf` :8938) — это и есть портфель тактик; Strategist перебирает
пары «постановка × тактика», как сегодня перебирает замыслы.

Как выбирается постановка:

1. **генераторы** `world → Disposition?` — список; готовые формулы: паросочетание бегун↔флаг по `gain × horizon`,
   симметричное ядро `min(наши мили, его) + min(наши стрелки, его)` :8409, партия 1/2/3 по охране флага, бюджет
   погони `min(CHASE_MAX, (free−1)/2)`. Генераторы **сеются инкумбентом** — липкость назначений (`keeperIds`,
   `runnerFlag`, `chaseOf`, пары бегунов) получается даром;
2. **доктрины** `(world, disposition) → Boolean` — жёсткие фильтры, не подправки: паритет (`captureBlock` :4054 с
   полами `PARITY_FLOOR/_STALLED/_LOST`), аннигиляция, бюджет отрыва;
3. **оценка** допущенных — `Forecast.evaluate` через холостой прогон Tactician;
4. **один гистерезис**: пересмотр по событиям (контакт, смерть, смена владельца флага) и раз в K тиков, смена — если
   кандидат лучше инкумбента на порог. В Strategist уходят армейские константы `POSTURE_HOLD`, `PUSH_DWELL`,
   `PUSH_RATIO/RELEASE` (+4 варианта), `NEAR_RANGE/RELEASE`, `BREAK_OFF_*`, `STALL_*`, `RETREAT_RATIO/RELEASE` —
   большинство исчезает. **Не** уходят: защёлки отряда (`rangedLevelLatched`, липкий фокус `focusId`, `packId`) —
   это память отряда в Tactician; `holdSince`/`impatientIds`/`stuckTicks` — память крипа.

Заменяет `Posture` (4 писателя :5892/:6531/:6538/:6741), `CmdMode` :6457, `Intent`, все три флаговых решателя и
девять множеств «кто считается» в `runArmy`.

### Tactician — от отряда к предложению по крипу

Выход — `Proposal` на каждого крипа с **тремя типизированными слотами**: `moves` (ход), `contact` (attack|heal),
`ranged` (rangedAttack|rangedMassAttack|rangedHeal). Исключительность интентов — по типу; огонь с ходом не
конфликтует вовсе (удар считается с домоувой позиции, :9188-9191 сверено с движком). Двойной `attack()` бегуну
(:4472 и :7812) исчезает по построению.

- **Formation** — одна модель с якорем-параметром `Median | FrontContact | Point` (сегодня три якоря: медиана в
  изготовке :8044, фронтовой крип у угрозы в `planBlock` :9395, медиана + поле цели в командире :8476), ось, фронт
  на дистанции, ряды по роли (+1/0/−1), слоты, кулак (`FIST_RADIUS`), `wall(victim)`. **Одно** правило назначения
  крип→слот (сегодня два: пары :8093 и жадное :9418). `slotOf(creep): Position?` — мили вплотную к врагу слота не
  получает (:9453) и дерётся термами.
- **Клетка = argmax по соседям суммы термов** `Term = (creep, cell, ctx) → Double`: `formation` (расстояние до
  слота, 0 при `null`), `danger` (= `Forecast.threatAt`, адресная t+1), `front` (уязвимость/влияние линии —
  `W_FRONT/W_LINE/W_SAG` :8883-8897), `healReach`, `fireReach`, `traffic` (болото, занято, путь). Термы — список с
  весами из `Tactic`; сегодняшние `scoreMelee/scoreRanged/scoreHeal` + `place` :8667 переносятся дословно; три
  прохода, которые их обходят (`retreat` :8791, `stripped` :9111, `catchall` :9119), и четвёртый путь лекаря
  `atMelee` :9043 исчезают.
- **Ход предлагается ранжированным списком допустимых клеток без смертельных** (сегодня `LETHAL_CELL_COST` :3050 —
  штраф, не запрет). **SURVIVE — отдельный генератор** (`forbidden(creep)` + список бегства), а не вес: иначе конфликт
  «отряд против выживания» невидим.
- **Цели огня и лечения — задача назначения отряда** (many→one с насыщением: `killable` = залп достающих минус его
  лечение :8228, `commandHeal` ставит на умирающего ровно столько лекарей :8297, `damageBooked` :7895), а не «одна
  функция на роль» и не арбитр. Исполнитель не переопределяет (сегодня веер выше приказа :7916, `focusDying` выше
  приказа :7739, стена выше приказа лечения :7823).
- **Общая бронь клеток между отрядами** внутри Tactician; отряды обрабатываются в порядке зависимостей
  (защищаемый раньше экскорта).

Заменяет изготовку, слоты, колонну, 28 ступеней, покрипную оценку командира и пять решателей цели.

### Arbiter — только непротиворечивые интенты

Настоящий арбитр — **движение**, и там `TrafficManager` сегодня и арбитр, и исполнитель: `resolve` сам зовёт
`creep.move` (`TrafficManager.kt:135`), молча отбрасывает уставших (:66), DFS может **поставить крипа в клетку,
которую он не просил** (своп в `fromCoord` :172-181; защита `SWAP_RESPECTS_INTENT` только для `ordered` :179), не
знает об опасности, и крип может не получить хода вовсе (`orderedDenied` :140). Правила Arbiter:

- R1 ход только из списка предложения — своп и цепочка тоже (закрывает `SWAP_RESPECTS_INTENT` по построению);
- R2 уставший/неподвижный → `move = null` явно, с прибором;
- R3 если весь список MISSION запрещён SURVIVE-генератором — берётся список бегства, печать `conflict:mission<survive`;
- R4 `pushRank = f(приоритет, роль, задание)` — единственное место чисел 1..7 (сегодня `ORDER_PRIORITY_*` :3061,
  и «больший толкает стоящего меньшего» :203 выталкивает хранителя или лекаря стены любым мили с приказом);
- R5 цель контакта/дальнего — в досягаемости с **текущей** клетки;
- R6 один интент на семейство — по типу;
- R7 клетка не двоим — `TrafficSolver.solve(requests, obstacles, mem) → Map<id, cell>` без `creep.move`.

Выход — `IntentSet(intents, conflicts)`. ⚠️ SURVIVE выше MISSION **меняет измеренное поведение**: сегодня ветка
`order` :7546 стоит выше `mustFlee` :7559, приказ бьёт бегство — это гейт и A/B, а не «правильно по построению».

### Executor, Memory, Instruments

Executor — цикл по `IntentSet`, единственный писатель API. `Memory` — единственный владелец межтиковых таблиц:
кольца историй (:3237-3277, :3376-3382, :4253-4270), память назначений (`keeperIds`, `runnerFlag`, `chaseOf`), память
отряда (фокус, защёлки), `TrafficMemory` (`lastDesired/stuckTicks`, `TrafficManager.kt:49-56`), кэши потоков,
`simPending`; в списке `AbortRepair` :3587 — `Memory, InfluenceMap, DistanceMap, TrafficManager, Instruments`.
Instruments — `measure()` каждой стадии; **старые имена приборов (`posture=`, `mode=`, `why=`) печатаются
отображением из новых структур до конца миграции**, иначе `series.py compare/metrics` теряет сопоставимость с v2xx.

## 3. Контракты стадий (сигнатуры, без тел)

```kotlin
class Unit(id, ours, pos, hits, hitsMax, fatigue, live: Profile, potential: Profile, armedParts, tailParts, canMove)
class World(tick, us: List<Unit>, them: List<Unit>, flags, terrain, fields: InfluenceMap.Snapshot, flows: DistanceMap,
            measures: Measures /* contact, massed, exchangeWindow, lostRaceNow, arrivalById … */, mem: Memory /* read-only */)

interface EnemyModel { fun targetOf(shooter: Unit, ours: List<Unit>, reach: Int): Unit?; fun stepOf(e: Unit, w: World): Cell }
class Prediction(cellAt1: Map<String, Cell>, targetAt1: Map<String, String?>)
class Outcome(ourLost, hisLost, ourDead, hisDead, ourStripped, hisStripped, flagDelta) { fun power(): Double }
object Forecast {
  fun predict(w: World, model: EnemyModel): Prediction
  fun threatAt(w: World, pred: Prediction, c: Unit, cell: Cell): Double
  fun evaluate(w: World, pred: Prediction, cells: Map<String, Cell>, fire: FireAlloc, k: Int): Outcome
  fun selfCheck(mem: Memory, w: World): SimError
}

sealed class Mission { class Goto(to: Cell); class Take(flag: FlagInfo); class Fight(group: List<String>); class Escort(squad: Int) }
class Tactic(weights: Map<TermId, Double>, front: Int, width: Int, rows: Rows, anchor: AnchorRule, fist: Int?)
class Squad(id: Int, members: List<String>, mission: Mission, tactic: Tactic, dependsOn: Int?)
class Disposition(squads: List<Squad>)                 // init: каждый Unit ровно в одном
typealias Generator = (World, Disposition?) -> Disposition?      // сеется инкумбентом
typealias Doctrine  = (World, Disposition) -> Boolean
object Strategist { fun choose(w: World, incumbent: Disposition?, gens: List<Generator>, doctrines: List<Doctrine>,
                               dryRun: (Disposition) -> List<Proposal>, budget: CpuBudget): Disposition }

class Slots(anchor: Cell, axis: Dir, slotOf: Map<String, Cell?>, fistCenter: Cell?, fistRadius: Int?)
object Formation { fun layout(w: World, squad: Squad, threat: List<Unit>, reserved: Set<Cell>): Slots
                   fun wall(victim: Unit, w: World): List<Cell> }

fun interface Term { fun score(c: Unit, cell: Cell, ctx: TermCtx): Double }
sealed class RangedIntent { class Attack(id); object Mass; class Heal(id) }
sealed class ContactIntent { class Attack(id); class Heal(id) }
class FireAlloc(ranged: Map<String, RangedIntent>, contact: Map<String, ContactIntent>)   // назначение отряда с насыщением
enum class Priority { SURVIVE, MISSION, OPPORTUNITY }
class MoveProposal(cells: List<Cell> /* ранжированы, без смертельных */, priority: Priority, swappable: Boolean, why: String)
class Proposal(creep: String, moves: List<MoveProposal>, contact: ContactIntent?, ranged: RangedIntent?, why: String)
object Tactician { fun propose(w: World, d: Disposition, pred: Prediction, mem: Memory): List<Proposal> }   // чистая

class Intent(creep: String, move: Cell?, contact: ContactIntent?, ranged: RangedIntent?, why: String)
class IntentSet(intents: List<Intent>, conflicts: List<String>)
object Arbiter { fun resolve(w: World, proposals: List<Proposal>, rules: List<Rule>): IntentSet }
object TrafficSolver { fun solve(req: List<TrafficRequest>, obstacles: List<Cell>, mem: TrafficMemory): Map<String, Cell> }
object Executor { fun run(intents: IntentSet, api: GameApi) }
class Memory(hist, assignments, squad, traffic, disposition: Disposition?, sim: SimPending) {
  fun commit(w: World, intents: IntentSet, d: Disposition): Memory }
```

## 4. Расширяемость — что добавляется, не трогая чужого

| хочу | где | что ещё трогается |
|---|---|---|
| новое задание отряда | `Mission` + обработчик в Tactician | ничего |
| новый вариант постановки | `Generator` | ничего |
| новую тактику боя (веса, строй) | `Tactic` в портфеле | ничего |
| новый учёт в бою («оружие впереди», «его лекарь вплотную») | `Term` | список термов тактики |
| новая модель врага | реализация `EnemyModel` | `selfCheck` покажет, лучше ли она |
| новая доктрина | `Doctrine` | ничего |
| новый прибор | `measure()` своей стадии | ничего |
| новое правило совместимости | `Rule` в Arbiter | ничего |

## 5. Файлы

`World.kt`, `Forecast.kt`, `Strategist.kt` (+ `Missions.kt`), `Formation.kt`, `Tactician.kt` (+ `Terms.kt`),
`Arbiter.kt`, `Executor.kt`, `Memory.kt`, `Instruments.kt`, `Rules.kt` (константы движка: 3/3/1/2, ⅓, 1,0/0,4/0,1,
модификаторы флагов — сегодня литералами в ~20 местах); `PainAndGain.kt` — `loop()` и оркестровка (~150 строк;
**имя не меняется**: его `.export.mjs` импортируют `arenas/…/main.mjs` и `tools/stub/painandgain/run.mjs:20`).
`InfluenceMap.kt`, `DistanceMap.kt` — службы World; `TrafficManager.kt` → `TrafficSolver`. Сборка `per-file`
подхватывает новые файлы без правки Gradle; реестр source-map — тоже.

## 6. Что убирается

1. **103 выключенных тумблера и их ветки** — целиком. Их комментарии-вердикты переносятся в приложение
   `docs/pain-and-gain.md` «Отвергнутое» скриптом до удаления (ничего не теряется, но код их не несёт).
2. **174 включённых тумблера** — становятся обычным кодом: условие упрощается, константа `USE_…` исчезает, комментарий
   с замером уходит в docs (в коде остаётся одна строка «что» и ссылка на версию).
3. **Дублирующие решатели**: из пяти механизмов клетки остаётся один (Formation + Planner), из четырёх решателей цели —
   один, из трёх флаговых — один, из трёх наборов состояний — один.
4. **Симуляция и перебор замыслов** (`simulate`, `planFight`, `rollout`, портфель) — кандидат на удаление: прогноз равен
   исходу и замысел исход не различает (замер v235); решается на этапе 3 замером, а не мнением: стенд плюс A/B без них.
5. **Мёртвые приборы** (`cmdBlocked` и подобные, названные в docs устаревшими) и всё, что печатается, но не читалось в
   разборах трёх последних серий.

## 7. Соответствие старого кода новым стадиям (снято с кода v235)

### Конвейер тика сегодня

`tickBody` :3595 → поля (`buildFields` :3656, `Ctx` :3699) → `runRunners` :4359 (паросочетание, режимы
FLEE/RESERVE/EXIT/HOLD/TO_FLAG/POISED) → `runArmy` :5037: замеры → постура :5230-5893 → общие цели → `cmdMode`
:6457 → три перезаписи постуры (:6531 живая; :6538 и :6741 мертвы — запертые тумблеры, 0 срабатываний в 270 логах
гейта) → командир (`commandFight`×5 + `simulate`, или `commandBrace`, или `commandRace`+`commandGoal`+`commandMarch`;
`commandOf` по писателю на режим, конкуренция только внутри BRACE :6785) → снятие дублей клеток :6827 → стена
лекарей → покрипный цикл :6975-7709 (28 ступеней `when` → поток → `mustFlee` → шаг `when`, где приказ читается
второй раз :7546) → `TrafficManager.request` :7706 → `commandFire`/`commandHeal`/`healAndShoot` :7721-7724 →
`TrafficManager.resolve` :3794 → приборы.

Интенты: движение отложено (единственный `creep.move` в `resolve`); удар/выстрел/лечение — немедленно из кода
решений (`attack` :4472/:7750, `heal`/`rangedHeal` ×6 :7827-7871, `rangedMassAttack` :7917, `rangedAttack` :7938);
`fireOf`/`healOf` — совещательные. Дефекты: два `attack()` бегуну за тик; отряжённый лекарь без оружия не лечит
(:7724). Приборы: `t=` — конкатенация :3827-3865; `rung t=` — `rungCount` :7704, `stepCount` :7705, `passCount`
:8778; `cpuMark` закрывает предыдущий интервал (перебор замыслов печатается как `plan`).

### Forecast ← сегодня

`simulate` :9161 (глубина 1, одно число, потребитель — argmax по пяти замыслам :6694-6701 → `commandOf` :6736);
прибор ошибки :9515-9518; `wallTargetOf` :10057 с онлайн-A/B :6919-6933 (питает только стену); `addressedAt` :8600
(выключен); `InfluenceMap` уже с шагом (`K_MELEE` радиус 2, `K_HEAL` радиус 4, `dangerAt`; `fireFieldAt` — этот
тик); истории → World. CPU: 100 мс, живьём 60–95 на тиках 2–35, перебор ≈ 20–28 мс, два тайм-аута 09.09.

### Strategist ← сегодня

Членство — девять фильтров (`strikers` :5045, `mobileArmy` :5097, `commandArmy` :5100, `chasers` :5101, `massArmy`
:5259, `combatArmy` :5935, `packMelee` :6093, `contactPack` :6121, `formers` :6174, `rallyPool` :7336) и три реестра
(`detachedIds`, `cmdDetach`, `keeperIds`) + `chaseOf`. Отряды-неявные: бегуны :4384-4529, `commandRace` :8385
(рекрутирует в бегунов через `cmdDetach` :8458), старый отряд `detachedIds` :5510-5614, `assignChase` :8338,
`grabberOf` :6158, авангард `formVan`/`rallyTo`. Флаги: `captureBlock` :4054 (ворота по порядку причин),
`chooseFlagObjective` :4653, `commandGoal` :8019. Состояния: `Posture` :3217 (таблица :5825, `POSTURE_HOLD = 5`,
~56 чтений), `CmdMode` :9493 (16 чтений), `Intent` :7998 (без гистерезиса).

### Tactician ← сегодня

Ось/фронт трижды (`commandBrace` :8040, `planBlock` :9345 с `rowCells`/`SLOT_ORDER`/`standoffLine` :9391,
`commandFight` :8476 с кулаком), `commandMarch` :8131. Оценка: `scoreMelee` :8883, `scoreRanged` :8892, `scoreHeal`
:8902, `weightsOf` :8938, `place` :8667, `placeScored` :8951; проходы retreat → straggler → melee → ranged → healer
→ holders → stripped → catchall. Цели: `focusCmp` :6011 (десять ярусов), липкий `focusTarget` :6082, `packTarget`
:6093, `commandFire` :8209, `commandHeal` :8282; исполнители `strike` :7729, `shoot` :7897, `healAndShoot` :7777.

### Arbiter ← где одно решение перебивает другое

постура 4 писателя; `commandOf` 4 источника; `slotOf` заполнен :6650 и стёрт :6652; дубли клеток :6827; ступень
`order` :7372 против шага :7546 и `mustFlee` :7559; `TrafficManager.place` даёт не ту клетку (`orderChanged`), отказ
`orderDenied`; `commandFire` стирает `fireOf` :8211; веер/`focusDying`/стена выше приказа; два `attack()`; лекарь
без лечения. Все десять — правила R1–R7 или исчезают по построению.

## 8. Путь миграции — перекройка по швам, десять посадок

**Почему не «переписать рядом»**: оракул с доказательной силой у проекта один — тождество, и оно сильнее, чем
считалось: `compare.py` сравнивает пять полей, но **логи 135 сценариев детерминированы** (в стубе нет `random`),
кроме строк `cpu t=` и `time=` в `done:`, — значит оракул механических этапов = **`compare` «без изменений 135» +
пустой diff логов без `^cpu t=`**. Перекройка покрывает им ~⅔ работы и дробит поведенческие шаги до размера,
который живой A/B (±2 из 8) ещё различает; новый бот рядом судился бы только PASS/FAIL (гейт не падает никогда —
экспозиция командира 0,4 %) и переоткрывал бы каждое принятое живьём правило своим A/B. Приём из «рядом» берётся
один: на поведенческом шаге заменяемый механизм остаётся в сборке **на один цикл A/B** как контрольная рука и
удаляется в том же батче.

**До первого удаления — знание**: в комментариях кода 205 номеров версий, в docs 196; **30 есть только в коде**
(v103, v129, v130, v137, v142, v143, v147, v150, v151, v153, **v160–v177**, v179, v180, v212 — эпоха командира:
гонка, огонь и лечение по приказу, марш ядра, отход по прогнозу, ошибка прогноза, приказ на шаг, своп, изготовка).
Скрипт `tools/stub/painandgain/verdicts.py`: у каждого `USE_X` берёт блок комментария над ним и печатает
приложения docs в формате :3111 «Отвергнутые правила (v203, этап 2)»; режим `--check`: множество `vNNN` из
удалённых строк ⊆ docs, каждая удалённая строка комментария > 40 символов найдена в docs, каждое число-замер
найдено — ненулевой счёт = коммит не делается.

| # | что | оракул | A/B | тег |
|---|---|---|---|---|
| 0 | эталон: `regress.sh land > gate_235.txt` + логи `out/run-land-*` → `runs/gate_235/`; `verdicts.py`; раздел docs «v137–v180 (командир)» | — | — | tools отдельным коммитом |
| 1 | прополка 103 OFF-тумблеров (в т. ч. мёртвые писатели постуры :6538/:6741), вердикты → docs, `--check` = 0 | тождество | — | v236 |
| 2 | сплющивание 174 ON-тумблеров, замеры → docs | тождество | — | v237 |
| 3 | `World.kt` (перенос :3607-3699 без смены порядка); `IntentSet` + `Executor.kt` — десять вызовов API уходят в конец, «последний писатель побеждает» (стуб так и делает, `world.mjs:44`), прибор `ovw=` перезаписей; Arbiter v0 — правила только считают `conf=` | тождество + одна тест-игра (cpu по стадиям, `abort=0`, стек из нового файла мапится на `.kt`) | — | v238 |
| 4 | `Instruments.kt` (тот же текст из тех же членов); `Memory.kt` (владелец колец, в списке `AbortRepair`, прибор `repair=` на тике 1); `Forecast.kt` (перенос `simulate`, `wallTargetOf`, ошибки прогноза); `Rules.kt`; `cpuMark` по стадиям | тождество | — | v239 |
| 5 | Arbiter v1: приоритеты, проигравший снимается, R1–R7, `TrafficSolver` без `creep.move` | FAIL-only; `ovw` → 0, `conf` > 0 | MetalicaX#15 | v240 |
| 6 | Strategist: `Disposition` вместо трёх состояний, генераторы из бегунов/ядра/погони, доктрины из `captureBlock`, один гистерезис; старые имена приборов отображением; старый путь под константой на один цикл | FAIL-only + `exposure:` | Coldkimchi#2 + MetalicaX#15; **затем серия 20** | v241 |
| 7 | Tactician-а: термы из `scoreMelee/Ranged/Heal` дословно + `threatAt` вместо `dangerAt`; назначение огня/лечения отрядом, исполнитель не переопределяет | FAIL-only; diff логов почти пуст (ничьи argmax) | MetalicaX#14 + #15 | v242 |
| 8 | Tactician-б: `Formation` вместо `commandBrace`/`planBlock`/`commandMarch` | FAIL-only (марш/изготовка на стенде экспонированы) | けろびー#1 + MetalicaX#15 | v243 |
| 9 | Tactician-в: 28 ступеней → `Proposal` с приоритетами, `why=mission:term` (сумма = армия × тики) | FAIL-only | MetalicaX#14 + Coldkimchi#2; **серия 20** | v244 |
| 10 | удаление контрольных путей, `PainAndGain.kt` ≈ оркестровка; docs «Архитектура»; строка в `CLAUDE.md` | тождество с 9; `--check` = 0 | — | v245 |

Календарь ≈ 3–3,5 недели: 2 дня механика (1–2), 2 дня срезы формы (3–4), полдня Arbiter, 3 дня Strategist,
6–8 дней Tactician, день docs; играбельная версия каждые 1–2 дня. Один цикл A/B 8+8 × 2 бота ≈ 3–4 ч, серия 20 ≈
3–4 ч, посадка ≈ 5 мин; `git rebase main` перед каждой посадкой, `BOT_VERSION` поднимается на каждую.

## 9. Проверка и риски

- **Тождество** (этапы 1–4, 10): `zsh tools/stub/painandgain/regress.sh land` → `compare.py` «без изменений 135» **и**
  `diff` логов `out/run-land-*` с `runs/gate_235/` без строк `^cpu t=` и `time=` — пуст.
- **Поведение** (5–9): гейт FAIL-only + `exposure:`; A/B 8+8 против названных ботов, контроль — предыдущая
  посаженная версия, «не хуже на обоих»; серия 20 после посадок 6 и 9.
- **CPU на холодной VM**: `cpu t=` по стадиям, `max=` за 100 тиков, `Script execution timed out` за тест-игру; стенд
  бесполезен (×8). Первый тик: префетч потоков :3704 остаётся в `World.build`; **никаких взаимных инициализаторов
  верхнего уровня между новыми файлами** (циклический импорт в Kotlin/JS даёт `undefined`) — инициализация в
  функциях.
- **`repairAfterAbort`**: список владельцев :3587 явный — новая таблица вне списка повторяет дефект v222; все
  кольца в `Memory`, счётчики в `Instruments`, оба в списке.
- **Source maps**: один намеренный `throw` из нового файла на стенде — кадр указывает на `.kt`.
- **Слоты + поля**: складывать `formation` с `score*` как термы — новая калибровка весов, тождество на этой границе
  ломается по построению → отдельный замер на этапе 8; до него в бою `slotOf = null`, как сегодня в FIGHT.
- **Параллельные сессии**: трогаются только `season4/painandgain/*`, `docs/pain-and-gain.md`,
  `tools/stub/painandgain/{compare.py, verdicts.py, README.md}`; `types/`, Gradle, `arenas/`, `land.sh`, строки
  `regress.sh` — нет. `verdicts.py` и diff-режим `compare.py` (под `tools/`) и строка `CLAUDE.md` — отдельными
  немедленными коммитами.

## 10. Решения оператора (13.09.2026)

1. **Путь — перекройка по швам**, десять посадок v236–v245 (раздел 8); не «новый бот рядом».
2. **Выживание всегда выше задания**: SURVIVE — отдельный генератор запрещённых клеток и списка бегства; когда весь
   список задания запрещён, крип бежит и арбитр печатает `conflict:mission<survive`. Это меняет измеренное
   поведение (сегодня в бою приказ выше бегства) — судится гейтом и A/B на этапе 5.
3. **Портфель тактик остаётся как `Tactic`**: PRESS/HOLD/YIELD/FOCUS/KITE = веса термов + параметры строя; стратег
   перебирает пары «постановка × тактика» через `Forecast.evaluate` холостым прогоном тактика, под бюджетом CPU.
4. **Пересмотр постановки — по событиям и раз в K тиков**, смена при выигрыше над инкумбентом на порог; армейские
   гистерезисы (`POSTURE_HOLD`, `PUSH_DWELL` и родня) исчезают вместе с тремя наборами состояний.

Решения, принятые мной и названные в плане: `Escort` в перечне, но первая реализация «стены» — вариант строя внутри
`Fight`; в бою `slotOf = null` до отдельного замера «слоты + поля» на этапе 8; старые имена приборов печатаются
отображением до конца миграции.

## 11. Первые шаги после утверждения

Ход работ: этап 0 — сделан 13.09.2026 (эталон `runs/gate_235/`, `verdicts.py`, раздел «v137–v180»); этап 1 — v236,
13.09.2026 (см. абзац v236 в `docs/pain-and-gain.md`); этап 2 — v237, 13.09.2026 (абзац v237); этап 3 — v238, 13.09.2026 (абзац v238); этап 4 — v239, 13.09.2026 (абзац v239; `Instruments.kt` и метки CPU по
стадиям перенесены на этапы 6 и 9).

1. Этот файл — утверждённый план; коммит по пути на ветку `pain-and-gain`.
2. Этап 0: эталон гейта `gate_235.txt` + копия логов в `runs/gate_235/`; `tools/stub/painandgain/verdicts.py`
   (приложения docs + `--check`); раздел docs «v137–v180 (командир)» из комментариев :1039-1262, :6676-6814,
   :8385-8511. Инструмент под `tools/` — отдельным немедленным коммитом.
3. Этап 1 (прополка) → тождество → посадка `pain-and-gain-v236`; далее по таблице раздела 8, одна посадка на этап,
   `git rebase main` перед каждой, `BOT_VERSION` на каждую.
4. Рейтинговые серии из 20 после этапов 6 и 9 — **только по слову оператора**; A/B 8+8 — по таблице.
