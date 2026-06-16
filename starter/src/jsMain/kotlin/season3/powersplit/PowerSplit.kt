package season3.powersplit

import kotlinx.js.JsPlainObject
import screeps.api.ATTACK
import screeps.api.BOTTOM
import screeps.api.BOTTOM_LEFT
import screeps.api.BOTTOM_RIGHT
import screeps.api.BUILD_POWER
import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.ConstructionSite
import screeps.api.CostMatrix
import screeps.api.Creep
import screeps.api.HARVEST_POWER
import screeps.api.SOURCE_ENERGY_REGEN
import screeps.api.TOUGH
import screeps.api.DirectionConstant
import screeps.api.SearchPathOptions
import screeps.api.HEAL
import screeps.api.HEAL_POWER
import screeps.api.LEFT
import screeps.api.MOVE
import screeps.api.Position
import screeps.api.RIGHT
import screeps.api.SearchGoal
import screeps.api.TOP
import screeps.api.TOP_LEFT
import screeps.api.TOP_RIGHT
import screeps.api.createConstructionSite
import screeps.api.getDirection
import screeps.api.searchPath
import screeps.api.RANGED_ATTACK
import screeps.api.RANGED_ATTACK_POWER
import screeps.api.RANGED_HEAL_POWER
import screeps.api.RESOURCE_ENERGY
import screeps.api.Source
import screeps.api.TERRAIN_SWAMP
import screeps.api.TERRAIN_WALL
import screeps.api.TOWER_ENERGY_COST
import screeps.api.TOWER_RANGE
import screeps.api.WORK
import screeps.api.arenaInfo
import screeps.api.get
import screeps.api.getObjectsByPrototype
import screeps.api.getRange
import screeps.api.getTerrainAt
import screeps.api.getTicks
import screeps.api.season3.BonusFlag
import screeps.api.structures.StructureContainer
import screeps.api.structures.StructureExtension
import screeps.api.structures.StructureRampart
import screeps.api.structures.StructureSpawn
import screeps.api.structures.StructureTower
import screeps.api.structures.StructureWall
import sourcemaps.runWithSourceMapSupport
import kotlin.math.abs

/** Целочисленная позиция клетки — для getTerrainAt при логировании карты. */
@JsPlainObject
external interface IntPos {
    var x: Int
    var y: Int
}

/** Дробная позиция — для угла клетки при отрисовке rect (visual). */
@JsPlainObject
external interface FloatPos {
    var x: Double
    var y: Double
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun loop() {
    try {
        runWithSourceMapSupport {
            PowerSplit.tick()
        }
    } catch (t: Throwable) {
        // страховка: не роняем тик, даже если упал source-map-обработчик — логируем и продолжаем
        println("loop error: ${t.message}")
        println(t.stackTraceToString())
    }
}

/**
 * Бот арены **season3 Power Split** (basic).
 *
 * Правила:
 *  - У базы: 1 StructureSpawn, 1 Source и ТРИ BonusFlag. Шагнув крипом на один флаг, забираем его
 *    бонус — остальные флаги базы исчезают. Бонус действует на ВСЕХ наших крипов до конца игры:
 *    ATTACK +100% attack; RANGED_ATTACK +100% rangedAttack/rangedMassAttack; HEAL +100% heal/rangedHeal.
 *  - Цель: уничтожить ПЕРВЫЙ StructureSpawn противника.
 *  - По карте раскидана спорная энергия в Source и StructureContainer.
 *  - В стенах есть секретные проходы: ломая StructureWall, можно срезать путь.
 *  - Лимит 2000 тиков; по истечении — ничья.
 *
 * Это КАРКАС: захватывает выбранный бонус-флаг и гонит боевых крипов на вражеский спавн.
 * Дальше развивать: экономика (harvest/withdraw → энергия на крупных крипов), выбор бонуса под
 * состав, кайт/строй (переиспользовать наработки из season3.spawnstrike), пролом стен для срезки.
 */
object PowerSplit {

    /** Берём бонус RANGED — наш профиль кайтящего стрелка (как в spawn-strike), +100% к
     *  rangedAttack/rangedMassAttack действует на ВСЕХ наших крипов до конца игры. */
    private val DESIRED_BONUS: BodyPartType = RANGED_ATTACK

    /** Тело захватчика флага: один MOVE — добежать и встать на флаг (флаги в 4 клетках от спавна). */
    private val CAPTURER_BODY: Array<BodyPartType> = arrayOf(MOVE)
    private const val CAPTURER_COST = 50

    /** Тело копателя: 3×WORK (6 энергии/тик), 1×CARRY (буфер), 1×MOVE. Цена 400 — влезает в
     *  стартовые 500. Один MOVE: копатель идёт к источнику ОДИН раз (медленно, гружёным не ходит)
     *  и дальше стоит, копая на месте — скорость не нужна. ДВА копателя = 6 WORK = 12/тик:
     *  покрывают реген источника (10/тик) и выкапывают стартовый запас (1000). */
    private val MINER_BODY: Array<BodyPartType> = arrayOf(WORK, WORK, WORK, CARRY, MOVE)
    private const val MINER_COST = 400
    private const val TARGET_MINERS = 2

    /** Тело хаулера: 1×CARRY + 1×MOVE. Цена 100 (дёшево — подключается рано). Паритет 1:1 —
     *  гружёным ходит каждый тик. Курсирует копатель↔спавн: копатели сами отдают ему transfer'ом. */
    private val HAULER_BODY: Array<BodyPartType> = arrayOf(CARRY, MOVE)
    private const val HAULER_COST = 100
    private const val TARGET_HAULERS = 1

    /** Тело форвард-майнера: 6×WORK (12/тик — выбирает реген 10 + запас), 1×CARRY (буфер для
     *  build/transfer; без CARRY добыча теряется), 6×MOVE (длинный болотистый путь до 2-го
     *  источника). Цена 950. Отличается от домашнего копателя числом WORK (6 vs 3). */
    private val FORWARD_MINER_BODY: Array<BodyPartType> = arrayOf(WORK, WORK, WORK, WORK, WORK, WORK, CARRY, MOVE, MOVE, MOVE, MOVE, MOVE, MOVE)
    private const val FORWARD_MINER_COST = 950

    /** Тело guard'а (блокирует проход у вражеской форвард-базы): 3×MOVE (дойти), 3×WORK (строить
     *  рампарты+башню), 1×CARRY (буфер). Цена 500. Отличается от домашнего копателя числом MOVE (3 vs 1). */
    private val GUARD_BODY: Array<BodyPartType> = arrayOf(MOVE, MOVE, MOVE, WORK, WORK, WORK, CARRY)
    private const val GUARD_COST = 500
    private const val TARGET_GUARDS = 1

    /** Тело бойца: 4×MOVE + 3×RANGED + 1×HEAL = 900 (влезает в store одного спавна 1000).
     *  Паритет MOVE (4 на 4 не-MOVE) — ходит каждый тик по равнине, кайтит. 3 RANGED с бонусом
     *  RANGED бьют вдвойне (≈60/тик одиночной + RMA) — упор в урон, т.к. бонус удешевляет ranged
     *  вдвое. 1 HEAL — самодостаточность (self-heal 12/тик); в группе бойцы лечат друг друга
     *  (heal в упор совместим со стрельбой, rangedHeal на дистанции — вместо выстрела).
     *  Порядок: MOVE-буфер спереди, RANGED под ними, HEAL последним (гибнет последним — лечит до конца). */
    private val FIGHTER_BODY: Array<BodyPartType> = arrayOf(MOVE, MOVE, MOVE, MOVE, RANGED_ATTACK, RANGED_ATTACK, RANGED_ATTACK, HEAL)
    private const val FIGHTER_COST = 900

    /** Тело харассера: 3×MOVE + 2×RANGED = 450. Мобильный экономический юнит: с бонусом ×2 ≈ 40 урона/тик
     *  (рвёт дешёвых майнеров), 3M:2R — ходит каждый тик по равнине и кайтит, обгоняет 1-MOVE домашних и
     *  тяжёлых форвард-майнеров на болоте. Без HEAL → роль HARASSER. Защищает форвард-базу/экономику и
     *  рейдит открытых вражеских эко-крипов. Тело — тюнинг №1 (альт. 2M2R=400 / 3M2R1H=700). */
    private val HARASSER_BODY: Array<BodyPartType> = arrayOf(MOVE, MOVE, MOVE, RANGED_ATTACK, RANGED_ATTACK)
    private const val HARASSER_COST = 450

    /** На сколько клеток ВПЕРЁД (к вражескому спавну, по проходу) выносим idle-пост харассера от
     *  форвард-базы: перехватываем атакующих в горловине, а не у самого майнера. */
    private const val REMOTE_DEFEND_STEPS = 4

    // --- веса утилитарного выбора спавна (chooseSpawnBody); старты, тюнятся по логам ---
    /** Копим на дорогого юнита, если его desire выше доступного максимума хотя бы во столько раз. */
    private const val SAVE_MARGIN = 1.3
    private const val W_MINER_INCOME = 20.0
    private const val W_HAUL = 120.0
    private const val W_BONUS_RACE = 200.0
    private const val W_H_DEFEND_FWD = 300.0
    private const val W_H_RAID = 250.0
    private const val W_H_ARMYGAP = 2.0
    private const val ARMYGAP_CAP = 200.0
    private const val W_FIGHTER_BASE = 100.0
    private const val W_FIGHTER_ARMYGAP = 3.0
    /** Множитель «безопасности»: считаем нужным перекрыть вражескую мощь с запасом. */
    private const val ARMY_SAFETY = 1.1
    /** Пропускная способность одного хаулера (энергии/тик) — для оценки нехватки хаулеров. */
    private const val HAULER_THROUGHPUT = 10
    private const val DESIRED_FWD_HARASSERS = 1
    private const val MAX_HARASSERS = 3
    /** Радиус «враг рядом с форвард-базой / нашими флагами» для гейтов спавна. */
    private const val SPAWN_NEAR_R = 8

    // --- харассер (мобильный эко-юнит) ---
    /** Эскорт врага в этом радиусе от эко-крипа → цель «прикрыта», в рейде не трогаем. */
    private const val ECO_ESCORT_R = 3
    /** Радиус перехвата врага у форвард-базы харассером. */
    private const val FWD_INTERCEPT_R = 12
    /** В рейде НЕ лезем к эко-крипам ближе этого к вражескому спавну (balanced — не дайвим базу). */
    private const val RAID_BASE_KEEPOUT = 15
    /** Бонус приоритета defendTarget (как «ближе на N тиков»), если интрудер давит нашу экономику. */
    private const val ECO_THREAT_BUMP = 6
    /** Радиус «вражеский боец давит нашу экономику» — считаем угрозой и идём защищать (даже вне половины). */
    private const val ECO_DEFEND_R = 6
    /** Радиус вокруг вражеского источника, в котором группа болота срывает его экономику (констракшн-сайты + майнер). */
    private const val SRC_DISRUPT_R = 6
    /** ТРЕВОГА БАЗЫ: боевой враг в этом радиусе от ЛЮБОГО нашего спавна — критическая угроза, на неё идут
     *  ближайшие бойцы МИНУЯ деление на группы/станции (потеря спавна = проигрыш). */
    private const val SPAWN_ALARM_R = 9
    /** Оборона своей половины — ОБЩАЯ: интрудера на нашей половине в этом радиусе боец бьёт независимо
     *  от своей группы (иначе страж стоял у форвард-спавна, пока прорвавшегося на нашу половину врага
     *  «приписали» к чужой группе). Деление по точкам остаётся для ВРАГОВ НА ЧУЖОЙ стороне. */
    private const val DEFEND_CROSS_REACH = 20
    /** Радиус «под башней»: внутри него вражеская башня бьёт сильно — туда не лезем и врагов там не
     *  преследуем; башня у вражеского источника отменяет болотную задачу (прорываемся в другом месте). */
    private const val TOWER_GUARD_R = 10
    /** Радиус «интрудер недостижим из-за башни»: в зоне угрозы башни до врага не добраться — исключаем
     *  его из целей И из «угроз» (не держит нас в DEFEND, не блокирует раш базы). Шире TOWER_GUARD_R. */
    private const val TOWER_REACH_BLOCK_R = 16
    /** Горизонт оценки «не заходить под башню»: не входим в клетку, если за столько ходов башня снесёт
     *  больше HP, чем крип получит лечением (своё + соседей). */
    private const val TOWER_AVOID_TICKS = 10
    /** Вес урона башни в поле прорыва (доп.цена клетки) и потолок — чтобы путь огибал зоны башен/тоннелил
     *  сквозь стены. Lookahead — на сколько шагов вперёд по полю ищем ближайшую стену для пролома. */
    private const val PUSH_DANGER_W = 0.3
    private const val PUSH_DANGER_CAP = 40
    private const val PUSH_LOOKAHEAD = 30
    /** Вес дистанции при выборе цели охоты (ближе — желаннее). */
    private const val HUNT_DIST_W = 1.0

    /** Минимум бойцов для перехода от контроля коридора к штурму вражеского спавна (ударный кулак).
     *  Тела сильные (3R+1H, 900) — 5 штук = 15 RANGED + взаимный хил, уже грозно. */
    private const val PUSH_MIN_FIGHTERS = 5

    /** Не штурмовать спавн раньше этого тика (0 = боевой режим; поднимать только для тестов экономики). */
    private const val PUSH_NOT_BEFORE_TICK = 0

    /** Пролом стен: ломаем W-ворота, только если короткий путь (сквозь них) короче реального обхода
     *  хотя бы на столько клеток — иначе пролом не стоит времени бойцов. */
    private const val BREACH_MARGIN = 12

    /** Выбор прохода: враги в этом радиусе от горловины прохода считаются «засевшими» (контестят). */
    private const val CONTEST_RADIUS = 5

    /** Штраф к длине маршрута за каждого засевшего у прохода врага — если враг засел в одном проходе,
     *  делает другой (чуть длиннее, но свободный) выгоднее. Обход контестируемого центра. */
    private const val CONTEST_PENALTY = 30

    /** Дальность RANGED_ATTACK. */
    private const val RANGED_RANGE = 3

    // --- веса позиционного скоринга клетки бойца (адаптация scoreCell из spawn-strike под одиночек) ---
    /** Цена шага марша к цели (по реальному пути/flow). */
    private const val PAIR_W_DIST = 10.0
    /** Штраф за входящий урон на клетке. */
    private const val PAIR_W_DAMAGE = 0.3
    /** Вес влияния (баланса сил) на клетке. */
    private const val PAIR_W_INFLUENCE = 0.1
    /** БОНУС за исходящий урон: откуда боец достаёт больше врагов — в бою доминанта (ищет огневую). */
    private const val PAIR_W_OUTGOING = 30.0
    /** Вес исходящего урона при ПРОРЫВЕ: ниже цены шага, иначе кайтящая приманка пригвождает штурм. */
    private const val PAIR_W_OUTGOING_BREACH = 5.0
    /** Штраф за зону удара мили-врага (держим дистанцию — ranged-преимущество). */
    private const val PAIR_W_MELEE = 50.0
    /** Смягчение мили-штрафа при наступлении с перевесом (иначе scoring парализует и атаку). */
    private const val AGGRO_MELEE_FACTOR = 0.3
    /** Бонус клетки своего рампарта в бою (урон уходит в рампарт). */
    private const val PAIR_W_RAMPART = 10.0
    /** Штраф клетки болота В БОЮ: ходишь раз в 5 тиков, кайт невозможен. Снят на рампарте/при прорыве. */
    private const val PAIR_W_SWAMP = 40.0
    /** Штраф за скученность своих (широкий фронт вместо колонны). */
    private const val PAIR_W_SPREAD = 4.0
    /** Тяга при побеге к нашей сильнейшей зоне (за тик пути по flow). Вторична к безопасности (урон×10):
     *  среди безопасных клеток бежим К поддержке армии, а не в случайный тихий угол. */
    private const val PAIR_W_FLEE_PULL = 2.0
    /** Сплочённость стражей у станции: награда за каждого своего в радиусе ближнего хила (1). Держит их
     *  плотной кучей (для heal в упор), но мала, чтобы не тянуть ближе дистанции стрельбы standoff. */
    private const val PAIR_W_COHESION = 2.0
    /** Радиус «личного пространства» бойца: своих ближе этого штрафуем. */
    private const val SEPARATION_RADIUS = 1
    /** На какой дистанции мили-враг считается угрозой (2 = подойдёт на шаг и ударит). */
    private const val MELEE_KEEP_RANGE = 2

    /** Запас в гонке спавнов (тиков): дожимаем чужой спавн вместо отката на защиту, только если
     *  выигрываем гонку с этим запасом — оценки времени грубые (бой в пути, фокус не на спавне). */
    private const val RACE_MARGIN = 10.0
    /** Латч гонки: решение «дожимаем» залипает, пока гонка не проиграна ЯВНО (enemyTicks <= ourTicks).
     *  Без него ourTicks/enemyTicks дрожат у порога → pushWinsRace мигал каждый тик, постура скакала
     *  DEFEND↔PUSH, и бойцы метались между интрудером и брешью. Входим в гонку по запасу RACE_MARGIN,
     *  выходим только при явном проигрыше — гистерезис гасит дребезг. */
    private var lastRaceCommitted = false
    /** fighterId -> id залипшего интрудера-цели обороны. Персональная ЛИПКАЯ цель вместо одного
     *  глобального defendTarget: тот прыгал между далеко разнесёнными интрудерами (набор мигал) и бойцы
     *  метались. Боец держит цель, пока она жива и в радиусе DEFEND_STICKY_RANGE. */
    private val fighterDefendId = HashMap<String, String>()
    /** Не бросаем залипшую цель обороны, пока она в этом радиусе (даже если на тик вышла из набора). */
    private const val DEFEND_STICKY_RANGE = 8
    /** При штурме (pushing) НОВУЮ цель обороны берём, только если интрудер уже в этом радиусе — близких
     *  добиваем, за дальними не гонимся (идём на спавн). Вне штурма отвечаем на любого ближайшего. */
    private const val DEFEND_ENGAGE_RANGE = 6

    // --- СТРАТЕГИЧЕСКАЯ ПОСТУРА: выводится из экономики обеих сторон, управляет выбором спавна и штурмом ---
    private enum class Posture { BOOTSTRAP, DEFEND, EXPAND, RAID, PUSH }
    private var lastPosture = Posture.BOOTSTRAP   // латч для гистерезиса
    /** Враг в этом радиусе (тиков) от нашего спавна — смертельная угроза базе → DEFEND. */
    private const val DEFEND_RADIUS = 14
    /** Во сколько раз вражеская мощь должна превышать нашу, чтобы уйти в оборону. */
    private const val DEFEND_MARGIN = 1.15
    /** Перевес для штурма (PUSH): наша мощь >= вражеской ×PUSH_RATIO. */
    private const val PUSH_RATIO = 1.0
    /** Полоса гистерезиса: из PUSH не выходим, пока наша мощь >= вражеской ×(PUSH_RATIO−PUSH_HYST). */
    private const val PUSH_HYST = 0.25
    /** Минимальный перевес, чтобы рейдить вражескую экономику (не лезем в рейд слабее). */
    private const val RAID_SAFETY = 1.0
    /** Насколько доход врага должен превышать наш, чтобы считать его экономику «уязвимой целью». */
    private const val INCOME_EDGE = 4.0
    /** Вес лечения в боевой мощи стороны (лекарь — поддержка: не урон, но живучесть). */
    private const val HEAL_POWER_WEIGHT = 0.5

    /** Клетки наших рампартов на этот тик (защищённые огневые позиции) — для скоринга клеток. */
    private var myRampartCells: Set<Int> = emptySet()

    /** Печатать ли диагностику (на время разработки). */
    private const val DEBUG_LOG = true

    /** Рисовать ли карту опасности/влияния на поле (influence — зелёный/красный, урон — оранжевый). */
    private const val DEBUG_DANGER = false
    /** Диагностика геометрии: рисовать две стратегические точки (узкий проход у нашего доп.источника
     *  + болото у вражеского доп.источника). Временный оверлей для проверки гипотезы. */
    private const val DEBUG_STRATEGY = true
    // кэш выбранных доп. источников (ближайших ПО ПУТИ, доступных) и вычисленных путей/коридора/болота
    private var stratOurSrcX = -1; private var stratOurSrcY = -1
    private var stratEnemySrcX = -1; private var stratEnemySrcY = -1
    private var stratPath1 = IntArray(0); private var stratCorridor = IntArray(0)
    private var stratPath2 = IntArray(0); private var stratSwamp = IntArray(0)
    // ДВЕ КЛЕТКИ КОНТРОЛЯ (пакеты x*100+y, -1 = не найдено): страж входа в наш коридор (держим, враг
    // идёт по одному) и позиция обстрела вражеского болота (враг застревает — лёгкая мишень).
    private var corridorGuardCell = -1
    private var swampControlCell = -1
    /** Сколько бойцов держат вход в наш коридор (ПРИОРИТЕТ): 1-ширинный choke — много не нужно;
     *  остальные идут прорывать вражеский коридор к болоту. */
    private const val CORRIDOR_GUARD_QUOTA = 3
    /** fighterId -> станция: 0 = страж коридора (наш источник), 1 = атакующий болото врага. Липко:
     *  первые крипы заполняют квоту стража (приоритет защиты нашего источника), следующие идут в атаку. */
    private val fighterStation = HashMap<String, Int>()

    /** Лимит байт визуала за тик (как в spawn-strike) — не рисуем больше. */
    private const val VISUAL_BYTE_LIMIT = 400_000

    /** Печатать ли ASCII-карту поля один раз (terrain + структуры) для анализа геометрии.
     *  Геометрия снята и записана в память (s3-powersplit-map) — держим false; включать при нужде. */
    private const val DEBUG_MAP = false
    private var mapLogged = false

    private var greeted = false

    // --- ПАМЯТЬ БОТА: ключевые точки ищем на первом тике и кэшируем (сторона старта и точные
    // координаты не фиксированы — НИЧЕГО не хардкодим, всё находим в рантайме через .my/близость). ---
    private var layoutReady = false
    private var enemySpawnX = 0
    private var enemySpawnY = 0
    private var centerX = 0
    private var centerY = 0
    // «Наша половина» и расстояния по достижимости теперь считает DistanceMap (взвешенный по болоту BFS).
    // точка контроля коридора — середина РЕАЛЬНОГО пути дом→враг (достижимая горловина между базами,
    // не центр-в-стене). Здесь бойцы держат проход и стартуют штурм.
    private var rallyX = 0; private var rallyY = 0
    // клетка горловины кратчайшего прохода (для лога); -1 если проходов нет.
    private var breachX = -1; private var breachY = -1
    /** Один «секретный проход» сквозь центральный хребет: стены кластера на пути (по порядку от дома),
     *  длина пути через него и горловина (для оценки контеста). */
    private class BreachRoute(val walls: List<Int>, val pathLen: Int, val chokeX: Int, val chokeY: Int)
    // ВСЕ проходы (кластеры ломаемых W-стен), найдены на старте. Каждый тик выбираем наименее
    // контестируемый: если враг засел в одном проходе — ломимся через другой.
    private var breachRoutes: List<BreachRoute> = emptyList()
    private var aroundLen = Int.MAX_VALUE   // длина обходного (коридорного) пути дом→враг (стены целы)
    // АКТИВНЫЙ маршрут пролома (стены выбранного прохода, по порядку) — ставится каждый тик; пуст =
    // идём обходным коридором без пролома. Цель пролома = ПЕРВАЯ ещё стоящая стена из него.
    private var breachWalls: List<Int> = emptyList()
    private var ourFlagX = 0   // НАШ бонус-флаг нужного типа (ближний к нашему спавну, не вражеский)
    private var ourFlagY = 0
    private var initialOwnFlags = 0
    // Фиксированные клетки копателей у нашего источника: две СМЕЖНЫЕ друг другу клетки-соседа
    // (гарантируют общую клетку для хаулера, смежную обоим). Майнеры встают по id: меньший -> spot1.
    private var sourceX = 0; private var sourceY = 0   // наш источник (клетку нельзя занимать)
    private var minerSpot1X = 0; private var minerSpot1Y = 0
    private var minerSpot2X = 0; private var minerSpot2Y = 0

    /** Направления, с которых хаулеры ФАКТИЧЕСКИ стыкуются со спавном на разгрузке — наблюдаем в
     *  рантайме (pathfinding может привести не со стороны источника). Капчерер/бойцы НЕ рождаются
     *  в эти клетки, чтобы не блокировать разгрузку хаулера. */
    private val dockDirs = mutableSetOf<DirectionConstant>()
    private val ALL_DIRECTIONS: Array<DirectionConstant> =
        arrayOf(TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT, TOP_LEFT)
    /** Латч: наш бонус взят (наши флаги исчезли). Не сбрасывается — бонус на всю игру. */
    private var bonusCaptured = false

    // --- ФОРВАРД-БАЗА у второго источника: строим там спавн, форвард-майнер заливает в него
    // энергию прямо с источника (возить далеко невыгодно). Координаты — рантайм, без хардкода. ---
    private var forwardEnabled = false   // найден ли достижимый второй источник
    private var secondSrcX = 0; private var secondSrcY = 0   // второй источник (клетку не занимать)
    private var fwdMinerX = 0; private var fwdMinerY = 0      // клетка форвард-майнера (на ней рампарт; достаёт источник И обе соседние клетки)
    private var towerX = -1; private var towerY = -1         // клетка башни (рампарт + башня); -1 если места нет
    private var fwdSpawnX = 0; private var fwdSpawnY = 0      // клетка форвард-спавна (рампарт + спавн)
    // пост ремоут-гварда: на несколько клеток ВПЕРЁД от форвард-базы по проходу к вражескому спавну —
    // перехватываем атакующих в горловине раньше, чем они дойдут до майнера. -1 если не вычислен.
    private var remoteDefendX = -1; private var remoteDefendY = -1
    private var homeSpawnId: String = ""  // id стартового спавна — отличать домашний от форвардного

    // --- GUARD-ПОСТ у вражеской форвард-базы: контейнер у прохода к ближайшему доп. источнику врага.
    // Guard встаёт на контейнер (рампарт на себе), строит рядом рампарт+башню и кормит её из контейнера. ---
    private var guardEnabled = false
    private var guardX = 0; private var guardY = 0           // клетка контейнера (на ней стоит guard + рампарт)
    private var guardTowerX = 0; private var guardTowerY = 0 // клетка башни (сосед, ближайший к врагу)

    /** Фаза челнока-хаулера: куда он сейчас едет. Переключается в момент прибытия на конец. */
    private enum class HaulerPhase { TO_SPAWN, TO_STAND }
    private val haulerPhase = HashMap<String, HaulerPhase>()

    /** Позиция центра клетки. */
    private fun cell(x: Int, y: Int): Position = IntPos(x = x, y = y).unsafeCast<Position>()

    /** Класс роли крипа — единый для своих И врагов (зеркальный учёт экономики/армии). */
    private enum class RoleClass { CAPTURER, MINER, FORWARD_MINER, HAULER, GUARD, FIGHTER, HARASSER, MELEE, MEDIC, OTHER }

    /** Классификатор роли по телу. Границы те же, что были инлайн: боец = RANGED+HEAL; харассер =
     *  RANGED без HEAL (бывший remoteGuard); MELEE = ATTACK; форвард-майнер = WORK>=6; guard = WORK 1..5
     *  и MOVE>=3; копатель = WORK 1..5 и MOVE<3; хаулер = CARRY без WORK/RANGED; капчер = только MOVE. */
    private fun roleOf(c: Creep): RoleClass {
        val w = c.body.count { it.type == WORK }
        val m = c.body.count { it.type == MOVE }
        val r = c.body.count { it.type == RANGED_ATTACK }
        val h = c.body.count { it.type == HEAL }
        val a = c.body.count { it.type == ATTACK }
        val carry = c.body.any { it.type == CARRY }
        return when {
            r > 0 && h > 0 -> RoleClass.FIGHTER
            r > 0 -> RoleClass.HARASSER
            a > 0 -> RoleClass.MELEE
            h > 0 -> RoleClass.MEDIC // чистый лекарь (HEAL без атаки) — военная поддержка, не экономика
            w >= 6 -> RoleClass.FORWARD_MINER
            w in 1..5 && m >= 3 -> RoleClass.GUARD
            w in 1..5 -> RoleClass.MINER
            carry -> RoleClass.HAULER
            m > 0 -> RoleClass.CAPTURER
            else -> RoleClass.OTHER
        }
    }

    /** Стоимость части тела (энергия). Жёсткой мапой — надёжнее Record-индексации BODYPART_COST. */
    private fun partCost(t: BodyPartType): Int = when {
        t == MOVE -> 50
        t == WORK -> 100
        t == CARRY -> 50
        t == ATTACK -> 80
        t == RANGED_ATTACK -> 150
        t == HEAL -> 250
        t == TOUGH -> 10
        else -> 0
    }

    /** Вложенная энергия в крипа (сумма стоимости частей) — мера «сколько противник/мы потратили». */
    private fun creepCost(c: Creep): Int = c.body.sumOf { partCost(it.type) }

    /** Снимок экономики стороны на тик: банк спавнов, добыча, вложенная стоимость (эко/армия), боевая
     *  мощь (с бонус-множителем стороны), счётчики ролей, есть ли форвард-база. Считается по ЖИВЫМ
     *  крипам (pending из spawning не учитываем — баг прототипа Spawning.creep; передабл безвреден). */
    private class Economy(
        val spawnBank: Int,
        val minerWorkParts: Int,
        val harvestPerTick: Double,
        val ecoValue: Int,
        val armyValue: Int,
        val rangedPower: Double,
        val meleePower: Double,
        val healPower: Double,
        val counts: Map<RoleClass, Int>,
        val hasForwardBase: Boolean,
    ) {
        // боевая мощь = атака + ДОЛЯ лечения (лекарь — армейская поддержка: повышает живучесть отряда)
        val power: Double get() = rangedPower + meleePower + healPower * HEAL_POWER_WEIGHT
        fun count(r: RoleClass) = counts[r] ?: 0
    }

    private fun accountEconomy(spawns: List<StructureSpawn>, creeps: List<Creep>, rangedMult: Double, meleeMult: Double): Economy {
        val counts = HashMap<RoleClass, Int>()
        var ecoValue = 0; var armyValue = 0
        var ranged = 0.0; var melee = 0.0; var heal = 0.0
        var minerWork = 0
        for (c in creeps) {
            val role = roleOf(c)
            counts[role] = (counts[role] ?: 0) + 1
            val cost = creepCost(c)
            val p = InfluenceMap.profileOf(c)
            // боевая мощь ВЗВЕШЕНА по живучести: крип с бОльшим HP (TOUGH/MOVE-буфер дёшево добавляют
            // HP, прикрывая RANGED) переживает размен и дольше держит DPS → реально сильнее. Норма
            // EHP_REF = HP нашего бойца (800), чтобы типовой боец остался ≈ прежним (шкала не ломается).
            val surv = c.hits.toDouble() / InfluenceMap.EHP_REF
            ranged += p.ranged * surv; melee += p.melee * surv; heal += p.heal
            when (role) {
                RoleClass.MINER, RoleClass.FORWARD_MINER -> { minerWork += c.body.count { it.type == WORK }; ecoValue += cost }
                RoleClass.HAULER, RoleClass.CAPTURER, RoleClass.GUARD -> ecoValue += cost
                else -> armyValue += cost // FIGHTER, HARASSER, MELEE, MEDIC, OTHER (боевые/поддержка/неизвестные)
            }
        }
        val hasForwardBase = spawns.size >= 2
        // steady-state доход: майнеры дают work*HARVEST_POWER, но источник отдаёт не больше регена;
        // форвард-база ≈ второй источник в работе → удваиваем потолок. Грубо, для ОТНОСИТЕЛЬНЫХ сравнений.
        val regenCap = SOURCE_ENERGY_REGEN * (1 + if (hasForwardBase) 1 else 0)
        val harvest = minOf(minerWork * HARVEST_POWER, regenCap).toDouble()
        val spawnBank = spawns.sumOf { it.store[RESOURCE_ENERGY] ?: 0 }
        return Economy(spawnBank, minerWork, harvest, ecoValue, armyValue, ranged * rangedMult, melee * meleeMult, heal, counts, hasForwardBase)
    }

    /** Стратегическая постура из экономики обеих сторон (с гистерезисом против дёрганья):
     *  DEFEND — враг угрожает базе/перевешивает; BOOTSTRAP — нет базовой экономики; PUSH — перевес+бонус
     *  или выигранная гонка спавнов; RAID — мы безопасно сильнее И экономика врага уязвима; иначе EXPAND. */
    private fun derivePosture(us: Economy, them: Economy, intruders: List<Creep>, pushWinsRace: Boolean, tick: Int): Posture {
        val ourPower = us.power; val theirPower = them.power
        val ecoSeeded = us.count(RoleClass.MINER) >= TARGET_MINERS && us.count(RoleClass.HAULER) >= TARGET_HAULERS
        val nearestIntruder = intruders.minOfOrNull { DistanceMap.stepsFromMy(it.x, it.y) } ?: Int.MAX_VALUE
        val lethalHome = intruders.isNotEmpty() && !pushWinsRace &&
            (nearestIntruder <= DEFEND_RADIUS || theirPower > ourPower * DEFEND_MARGIN)
        val decisive = bonusCaptured && us.count(RoleClass.FIGHTER) >= PUSH_MIN_FIGHTERS &&
            ourPower >= theirPower * PUSH_RATIO && tick >= PUSH_NOT_BEFORE_TICK
        val ecoExposed = them.count(RoleClass.FORWARD_MINER) > 0 || them.harvestPerTick > us.harvestPerTick + INCOME_EDGE
        val raw = when {
            lethalHome -> Posture.DEFEND
            !ecoSeeded -> Posture.BOOTSTRAP
            decisive || pushWinsRace -> Posture.PUSH
            ecoExposed && ourPower >= theirPower * RAID_SAFETY -> Posture.RAID
            else -> Posture.EXPAND
        }
        // гистерезис: DEFEND срабатывает немедленно (безопасность), из PUSH не выходим в полосе PUSH_HYST
        val next = when {
            raw == Posture.DEFEND -> Posture.DEFEND
            lastPosture == Posture.PUSH && bonusCaptured && us.count(RoleClass.FIGHTER) >= PUSH_MIN_FIGHTERS &&
                ourPower >= theirPower * (PUSH_RATIO - PUSH_HYST) && tick >= PUSH_NOT_BEFORE_TICK -> Posture.PUSH
            else -> raw
        }
        lastPosture = next
        return next
    }

    /** Шаг к цели в ОБХОД опасности: первый шаг searchPath по danger-матрице (враги/башни дороги,
     *  стены 255). Для одиночек (форвард-майнер, guard), идущих через контестируемые зоны. */
    private fun moveAvoiding(creep: Creep, target: Position, matrix: CostMatrix): Boolean {
        val step = searchPath(creep, SearchGoal(pos = target, range = 0), SearchPathOptions(costMatrix = matrix)).path.firstOrNull() ?: return false
        creep.move(getDirection(step.x - creep.x, step.y - creep.y))
        return true
    }

    /** Рядом ли вражеский БОЕЦ, способный достать крипа (для безоружных одиночек — форвард-майнер).
     *  +1 к дальности стрельбы — фора на тик, чтобы начать убегать ДО получения урона. */
    private fun threatenedBy(creep: Creep, enemyCreeps: List<Creep>): Boolean =
        enemyCreeps.any { e ->
            e.body.any { (it.type == RANGED_ATTACK || it.type == ATTACK) && it.hits > 0 } &&
                creep.getRangeTo(e) <= RANGED_RANGE + 1
        }

    /** Угрожаемый безоружный одиночка ОТСТУПАЕТ к домашнему спавну в обход опасности (danger-матрица),
     *  а НЕ «бежит от врага» вслепую — последнее загоняло его в угол карты (видно по логам: застревал
     *  на (93,28)). Если путь к дому по матрице зажат — прямой moveTo, лишь бы двигаться к своим. */
    private fun retreatHome(creep: Creep, danger: CostMatrix, homeSpawn: StructureSpawn) {
        if (!moveAvoiding(creep, cell(homeSpawn.x, homeSpawn.y), danger)) creep.moveTo(homeSpawn)
    }

    /**
     * Один раз на старте находит и запоминает геометрию. Сторона (низ-лево / верх-право) и точные
     * координаты не зашиты: свой спавн — по .my, свои флаги — те, что БЛИЖЕ к нашему спавну, чем к
     * вражескому (на чужой флаг шагать бесполезно — он не наш). Цель захвата — наш флаг типа RANGED.
     */
    private fun ensureLayout(mySpawn: StructureSpawn, enemySpawn: StructureSpawn?, bonusFlags: Array<BonusFlag>, sources: Array<Source>) {
        if (layoutReady || enemySpawn == null || bonusFlags.isEmpty()) return
        val ourFlags = bonusFlags.filter { getRange(it, mySpawn) < getRange(it, enemySpawn) }
        if (ourFlags.isEmpty()) return
        val ourSource = sources.minByOrNull { getRange(it, mySpawn) } ?: return // ждём, пока виден наш источник
        val flag = ourFlags.firstOrNull { it.bonusType == DESIRED_BONUS } ?: ourFlags.first()
        ourFlagX = flag.x; ourFlagY = flag.y
        enemySpawnX = enemySpawn.x; enemySpawnY = enemySpawn.y
        centerX = (mySpawn.x + enemySpawn.x) / 2; centerY = (mySpawn.y + enemySpawn.y) / 2
        initialOwnFlags = ourFlags.size
        sourceX = ourSource.x; sourceY = ourSource.y
        chooseMinerSpots(ourSource)
        // dock-клетка хаулера (сосед спавна, ближайший к копателю — оттуда он приезжает разгружаться):
        // ДЕТЕРМИНИРОВАННО сеем dockDirs сразу на старте, чтобы НИКАКОЙ крип не рождался на ней (иначе
        // блокирует разгрузку). Раньше наблюдали с задержкой, и майнеры/хаулеры выходили ровно на dock.
        val dock = passableNeighbors(mySpawn.x, mySpawn.y)
            .minByOrNull { maxOf(abs(it / 100 - minerSpot1X), abs(it % 100 - minerSpot1Y)) }
        if (dock != null) dockDirs.add(getDirection(dock / 100 - mySpawn.x, dock % 100 - mySpawn.y))
        homeSpawnId = mySpawn.id
        // точка контроля коридора — середина реального пути дом→враг (через стены), достижимая
        val homePos = cell(mySpawn.x, mySpawn.y)
        val enemyGoal = SearchGoal(pos = cell(enemySpawn.x, enemySpawn.y), range = 1)
        val realRes = searchPath(homePos, enemyGoal, SearchPathOptions(costMatrix = wallCostMatrix()))
        val path = realRes.path
        if (path.isNotEmpty()) { val mid = path[path.size / 2]; rallyX = mid.x; rallyY = mid.y } else { rallyX = centerX; rallyY = centerY }
        // длина обходного коридорного пути (все стены целы) — база для сравнения с проломом проходов
        aroundLen = if (realRes.incomplete) Int.MAX_VALUE else path.size
        // находим ВСЕ секретные проходы (кластеры ломаемых W-стен) — для динамического выбора в бою
        findBreachRoutes(homePos, enemyGoal)
        breachRoutes.firstOrNull()?.let { breachX = it.chokeX; breachY = it.chokeY }
        findForwardBase(mySpawn, ourSource, sources)
        findGuardPost(enemySpawn, sources)
        layoutReady = true
        if (DEBUG_LOG) println(
            "layout: mySpawn=(${mySpawn.x},${mySpawn.y}) enemySpawn=($enemySpawnX,$enemySpawnY) " +
                "sources=${sources.map { "(${it.x},${it.y})" }} homeSrc=(${ourSource.x},${ourSource.y}) " +
                "ourFlag=($ourFlagX,$ourFlagY) center=($centerX,$centerY) ownFlags=$initialOwnFlags " +
                "minerSpots=($minerSpot1X,$minerSpot1Y),($minerSpot2X,$minerSpot2Y) " +
                "forward=$forwardEnabled src=($secondSrcX,$secondSrcY) fwdSpawn=($fwdSpawnX,$fwdSpawnY) fwdMiner=($fwdMinerX,$fwdMinerY) tower=($towerX,$towerY) rDefend=($remoteDefendX,$remoteDefendY) " +
                "guard=$guardEnabled guardAt=($guardX,$guardY) guardTower=($guardTowerX,$guardTowerY) " +
                "rally=($rallyX,$rallyY) aroundLen=$aroundLen routes=${breachRoutes.map { "choke(${it.chokeX},${it.chokeY})len=${it.pathLen}walls=${it.walls.size}" }}"
        )
    }

    /**
     * Находит второй источник под форвард-базу: ближайший к спавну ДОСТИЖИМЫЙ источник, кроме
     * домашнего (центральный за стенами отсеивается — searchPath к нему incomplete). Затем подбирает
     * рядом с ним площадку под спавн (fwdSpawn) и клетку майнера (fwdMiner), смежную И источнику,
     * И площадке — чтобы майнер одной позицией и копал, и строил/заливал спавн. Координаты кэшируются.
     */
    private fun findForwardBase(mySpawn: StructureSpawn, homeSource: Source, sources: Array<Source>) {
        val spawnPos = cell(mySpawn.x, mySpawn.y)
        // ВАЖНО: searchPath сам учитывает только terrain-стены, но НЕ структуры. Центральный источник
        // закрыт StructureWall (ломаемыми) — без их учёта путь к нему «достижим». Помечаем все стены
        // непроходимыми (255) в costMatrix, чтобы отсеять источники за стенами (пока не ломаем их).
        val opts = SearchPathOptions(costMatrix = wallCostMatrix())
        val enemyPos = cell(enemySpawnX, enemySpawnY)
        val reachable = sources
            .filter { it.x != homeSource.x || it.y != homeSource.y }
            .filter { searchPath(spawnPos, SearchGoal(pos = cell(it.x, it.y), range = 1), opts).incomplete.not() }
        // форвард-база — ТОЛЬКО на НАШЕЙ половине (источник ближе к нам, чем к врагу). Иначе ближайший
        // достижимый «второй» источник оказывается ВРАЖЕСКИМ (центральный за стенами отсеян), и одинокий
        // безоружный майнер тащится через всю карту к спавну врага и гибнет. Нет своего — фича выключена.
        val second = reachable
            .filter { getRange(cell(it.x, it.y), spawnPos) < getRange(cell(it.x, it.y), enemyPos) }
            .minByOrNull { getRange(mySpawn, it) }
        if (DEBUG_LOG) println(
            "findForward: reachableNonHome=${reachable.map { "(${it.x},${it.y})" }} " +
                "picked=${second?.let { "(${it.x},${it.y})" } ?: "NONE (нет источника на нашей половине)"}"
        )
        if (second == null) return
        // майнер встаёт на соседа источника (там рампарт; harvest источника + строит/кормит соседние
        // клетки в range 1). Башня и спавн — на ДВУХ соседях клетки майнера (он достаёт обе).
        for (mc in passableNeighbors(second.x, second.y)) {
            val mx = mc / 100; val my = mc % 100
            // соседи клетки майнера, не источник и не сама клетка майнера — кандидаты под башню/спавн
            val slots = passableNeighbors(mx, my).filter { (it / 100 != second.x || it % 100 != second.y) && it != mc }
            if (slots.size < 2) continue
            secondSrcX = second.x; secondSrcY = second.y
            fwdMinerX = mx; fwdMinerY = my
            // башня — на клетку, БЛИЖАЙШУЮ к вражескому спавну (Чебышев): простреливает подходы;
            // спавн — на дальнюю клетку (за башней, прикрыт). enemySpawnX/Y уже закэшированы выше.
            val byDistToEnemy = slots.sortedBy { maxOf(abs(it / 100 - enemySpawnX), abs(it % 100 - enemySpawnY)) }
            towerX = byDistToEnemy.first() / 100; towerY = byDistToEnemy.first() % 100
            fwdSpawnX = byDistToEnemy.last() / 100; fwdSpawnY = byDistToEnemy.last() % 100
            // пост ремоут-гварда: реальный путь от форвард-базы к вражескому спавну, N-я клетка вперёд
            // (в проходе/горловине). Путь короче N — берём его конец; пути нет — стоим у майнера.
            val toEnemy = searchPath(cell(mx, my), SearchGoal(pos = cell(enemySpawnX, enemySpawnY), range = 1), SearchPathOptions(costMatrix = wallCostMatrix())).path
            val defendCell = toEnemy.getOrNull(minOf(REMOTE_DEFEND_STEPS - 1, toEnemy.size - 1))
            remoteDefendX = defendCell?.x ?: mx; remoteDefendY = defendCell?.y ?: my
            forwardEnabled = true
            return
        }
    }

    /**
     * Guard-пост: упреждаем вражескую форвард-базу. Враг (по симметрии) построит второй спавн у
     * своего ближайшего достижимого доп. источника; у прохода туда стоит контейнер. Ставим guard на
     * контейнер, чтобы рампартом+башней (питается из контейнера) запереть/простреливать этот проход.
     * Находим: ближайший к ВРАЖЕСКОМУ спавну достижимый доп. источник → ближайший к нему контейнер →
     * клетку башни (сосед контейнера, ближайший к врагу). Координаты кэшируются.
     */
    private fun findGuardPost(enemySpawn: StructureSpawn, sources: Array<Source>) {
        val enemyPos = cell(enemySpawn.x, enemySpawn.y)
        val opts = SearchPathOptions(costMatrix = wallCostMatrix())
        val enemyHome = sources.minByOrNull { getRange(enemySpawn, it) } ?: return
        val enemyFwd = sources
            .filter { it.x != enemyHome.x || it.y != enemyHome.y }
            .filter { searchPath(enemyPos, SearchGoal(pos = cell(it.x, it.y), range = 1), opts).incomplete.not() }
            .minByOrNull { getRange(enemySpawn, it) } ?: return
        // контейнер у этого прохода — ближайший к доп. источнику врага
        val container = getObjectsByPrototype(StructureContainer::class)
            .filter { it.exists }
            .minByOrNull { getRange(it, enemyFwd) } ?: return
        val slots = passableNeighbors(container.x, container.y)
        if (slots.isEmpty()) return
        // башня — на соседе контейнера, БЛИЖАЙШЕМ к вражескому спавну (простреливает проход к врагу)
        val towerSlot = slots.minByOrNull { maxOf(abs(it / 100 - enemySpawn.x), abs(it % 100 - enemySpawn.y)) }!!
        guardX = container.x; guardY = container.y
        guardTowerX = towerSlot / 100; guardTowerY = towerSlot % 100
        guardEnabled = true
    }

    /** Ближайший ДОСТУПНЫЙ ПО ПУТИ доп. источник от позиции `from` (кроме домашнего — смежного со
     *  спавном). Доступность через searchPath со СТЕНАМИ=255: центральный источник за ломаемыми
     *  W-стенами даёт incomplete → отсеивается. Ранжируем по ДЛИНЕ ПУТИ (не Чебышеву) — «ближайший по
     *  пути», как просил пользователь. Для DEBUG_STRATEGY. */
    private fun nearestAccessibleSource(from: Position, sources: Array<Source>): Source? {
        val home = sources.minByOrNull { getRange(from, it) } // домашний = смежный со спавном
        val opts = SearchPathOptions(costMatrix = wallCostMatrix())
        return sources
            .filter { it !== home }
            .mapNotNull { src ->
                val res = searchPath(from, SearchGoal(pos = cell(src.x, src.y), range = 1), opts)
                if (res.incomplete) null else src to res.path.size
            }
            .minByOrNull { it.second }?.first
    }

    /** Клетки пути (пакеты x*100+y) от (fromX,fromY) до (toX,toY) с учётом стен (255). Включает старт. */
    private fun pathCells(fromX: Int, fromY: Int, toX: Int, toY: Int): List<Int> {
        val opts = SearchPathOptions(costMatrix = wallCostMatrix())
        val path = searchPath(cell(fromX, fromY), SearchGoal(pos = cell(toX, toY), range = 1), opts).path
        val out = ArrayList<Int>(path.size + 1)
        out.add(fromX * 100 + fromY)
        path.forEach { out.add(it.x * 100 + it.y) }
        return out
    }

    /** true, если клетка — часть прохода ШИРИНОЙ 1: проходима И зажата стенами с двух противоположных
     *  сторон (по горизонтали ИЛИ по вертикали). */
    private fun isNarrowCell(x: Int, y: Int): Boolean {
        if (DistanceMap.isWall(x, y)) return false
        val h = DistanceMap.isWall(x - 1, y) && DistanceMap.isWall(x + 1, y)
        val v = DistanceMap.isWall(x, y - 1) && DistanceMap.isWall(x, y + 1)
        return h || v
    }

    /** Твёрдая (проходимая, не-болото) клетка на краю болота, ближайшая к нашему спавну по пути —
     *  позиция, с которой лучники бьют застрявших в болоте врагов. -1, если болота/края нет. */
    private fun swampFiringCell(swamp: IntArray): Int {
        if (swamp.isEmpty()) return -1
        val swampSet = swamp.toHashSet()
        val cands = HashSet<Int>()
        for (c in swamp) {
            val cx = c / 100; val cy = c % 100
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx; val ny = cy + dy
                if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
                val n = nx * 100 + ny
                if (n in swampSet || DistanceMap.isWall(nx, ny)) continue
                cands.add(n)
            }
        }
        return cands.minByOrNull { DistanceMap.stepsFromMy(it / 100, it % 100) } ?: -1
    }

    /** Спускаясь по полю прорыва от (sx,sy) к спавну, возвращает пакет ПЕРВОЙ ломаемой преграды
     *  (W-стена или чужой рампарт) на пути — её надо проломить, чтобы идти дальше. -1, если преграды
     *  нет в пределах PUSH_LOOKAHEAD (путь открыт — просто идём). */
    private fun nextPushBlockCell(sx: Int, sy: Int, field: IntArray, enemyRampartCells: Set<Int>): Int {
        var cx = sx; var cy = sy
        repeat(PUSH_LOOKAHEAD) {
            val here = field[cx * 100 + cy]
            if (here <= 0) return -1 // достигли спавна / клетка недостижима
            var bestV = here; var bx = -1; var by = -1
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx; val ny = cy + dy
                if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
                val v = field[nx * 100 + ny]
                if (v in 0 until bestV) { bestV = v; bx = nx; by = ny }
            }
            if (bx < 0) return -1
            val packed = bx * 100 + by
            // в поле прорыва W-стены проходимы (field>=0) → isWall тут = именно ломаемая W-стена
            if (DistanceMap.isWall(bx, by) || packed in enemyRampartCells) return packed
            cx = bx; cy = by
        }
        return -1
    }

    /** 8-связный залив болота из клеток-семян: всё связное болото, к которому они принадлежат. */
    private fun floodSwamp(seeds: List<Int>): List<Int> {
        val region = HashSet<Int>()
        val stack = ArrayDeque<Int>()
        seeds.forEach { if (region.add(it)) stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val c = stack.removeLast(); val cx = c / 100; val cy = c % 100
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx; val ny = cy + dy
                if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
                if (!DistanceMap.isSwamp(nx, ny)) continue
                val n = nx * 100 + ny
                if (region.add(n)) stack.addLast(n)
            }
        }
        return region.toList()
    }

    /** CostMatrix со стенами (StructureWall = 255) для searchPath — учитывает СТРУКТУРНЫЕ стены,
     *  которые searchPath сам не видит (иначе путь «проходит» сквозь них). */
    private fun wallCostMatrix(): CostMatrix {
        val m = CostMatrix()
        getObjectsByPrototype(StructureWall::class).forEach { if (it.exists) m.set(it.x, it.y, 255) }
        return m
    }

    /** Связные кластеры ломаемых W-стен (8-связность) — каждый кластер это один «секретный проход»
     *  сквозь центральный хребет (пробив его, открываем срезку база→база). Возвращает пакеты x*100+y. */
    private fun wallClusters(): List<List<Int>> {
        val set = HashSet<Int>()
        getObjectsByPrototype(StructureWall::class).forEach { if (it.exists) set.add(it.x * 100 + it.y) }
        val seen = HashSet<Int>()
        val clusters = ArrayList<List<Int>>()
        for (start in set) {
            if (start in seen) continue
            val cluster = ArrayList<Int>()
            val stack = ArrayDeque<Int>(); stack.addLast(start); seen.add(start)
            while (stack.isNotEmpty()) {
                val c = stack.removeLast(); cluster.add(c)
                val cx = c / 100; val cy = c % 100
                for (dx in -1..1) for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val n = (cx + dx) * 100 + (cy + dy)
                    if (n in set && n !in seen) { seen.add(n); stack.addLast(n) }
                }
            }
            clusters.add(cluster)
        }
        return clusters
    }

    /** Находит все маршруты пролома: для каждого кластера W-стен строит путь дом→враг, где проходим
     *  ТОЛЬКО сквозь этот кластер (остальные стены = 255). Если путь полон и реально идёт сквозь
     *  кластер — это «проход»; запоминаем его стены на пути (по порядку), длину и горловину. В бою
     *  выбираем наименее контестируемый. Маршруты отсортированы по длине (короткий — первым). */
    private fun findBreachRoutes(homePos: Position, enemyGoal: SearchGoal) {
        val allWalls = getObjectsByPrototype(StructureWall::class).filter { it.exists }
        val routes = ArrayList<BreachRoute>()
        for (cluster in wallClusters()) {
            val clusterSet = cluster.toHashSet()
            val m = CostMatrix()
            allWalls.forEach { val p = it.x * 100 + it.y; if (p !in clusterSet) m.set(it.x, it.y, 255) }
            val res = searchPath(homePos, enemyGoal, SearchPathOptions(costMatrix = m))
            if (res.incomplete) continue
            val onPath = res.path.mapNotNull { p -> (p.x * 100 + p.y).takeIf { it in clusterSet } }
            if (onPath.isEmpty()) continue // путь не идёт сквозь кластер (использует открытый коридор)
            val choke = onPath[onPath.size / 2]
            routes.add(BreachRoute(onPath, res.path.size, choke / 100, choke % 100))
        }
        breachRoutes = routes.sortedBy { it.pathLen }
    }

    /** Проходимые соседи клетки (не стена, в границах) как упакованные x*100+y. */
    private fun passableNeighbors(cx: Int, cy: Int): List<Int> {
        val out = ArrayList<Int>(8)
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            val x = cx + dx; val y = cy + dy
            if (x < 0 || y < 0 || x > 99 || y > 99) continue
            if (getTerrainAt(cell(x, y)) == TERRAIN_WALL) continue
            out.add(x * 100 + y)
        }
        return out
    }

    /**
     * Выбирает две клетки копателей вокруг источника: проходимые соседи источника, СМЕЖНЫЕ друг
     * другу — тогда у пары гарантированно есть общая клетка, смежная обоим (для хаулера, см.
     * haulerStand). Fallback: первые два проходимых соседа (общей клетки может не быть — хаулер
     * обслужит одного).
     */
    private fun chooseMinerSpots(source: Source) {
        val nbrs = ArrayList<Int>(8)
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            val x = source.x + dx; val y = source.y + dy
            if (x < 0 || y < 0 || x > 99 || y > 99) continue
            if (getTerrainAt(cell(x, y)) == TERRAIN_WALL) continue
            nbrs.add(x * 100 + y)
        }
        var a = nbrs.firstOrNull() ?: (source.x * 100 + source.y)
        var b = a
        loop@ for (i in nbrs.indices) for (j in nbrs.indices) {
            if (i == j) continue
            val ax = nbrs[i] / 100; val ay = nbrs[i] % 100; val bx = nbrs[j] / 100; val by = nbrs[j] % 100
            if (maxOf(abs(ax - bx), abs(ay - by)) == 1) { a = nbrs[i]; b = nbrs[j]; break@loop }
        }
        if (a == b) b = nbrs.getOrNull(1) ?: a // нет смежной пары — берём любые два
        minerSpot1X = a / 100; minerSpot1Y = a % 100
        minerSpot2X = b / 100; minerSpot2Y = b % 100
    }

    fun tick() {
        if (!greeted) {
            greeted = true
            println("hello season3 power-split: ${arenaInfo.season} - ${arenaInfo.name}")
        }

        if (DEBUG_MAP && !mapLogged) {
            mapLogged = true
            logMap()
        }

        val mySpawns = getObjectsByPrototype(StructureSpawn::class).filter { it.my == true }
        val homeSpawn = mySpawns.firstOrNull { it.id == homeSpawnId } ?: mySpawns.firstOrNull() ?: return
        val enemySpawn = getObjectsByPrototype(StructureSpawn::class).firstOrNull { it.my == false }

        val myCreeps = getObjectsByPrototype(Creep::class).filter { it.my && !it.spawning }
        val enemyCreeps = getObjectsByPrototype(Creep::class).filter { !it.my && !it.spawning }

        val sources = getObjectsByPrototype(Source::class)
        val bonusFlags = getObjectsByPrototype(BonusFlag::class)

        // на первом тике запоминаем геометрию (свой спавн/флаг/центр/споты копателей, форвард-базу) — из кэша
        ensureLayout(homeSpawn, enemySpawn, bonusFlags, sources)
        if (!layoutReady) return // геометрия ещё не определена (флаги/враг/источник не видны) — ждём тик

        // форвард-спавн — наш спавн, отличный от домашнего (появляется после постройки)
        val forwardSpawn = mySpawns.firstOrNull { it.id != homeSpawnId }

        // бонус взят, если число НАШИХ флагов (ближе к нашему спавну) УМЕНЬШИЛОСЬ относительно
        // стартового: захваченный флаг остаётся постоянным маркером бонуса, а два других исчезают
        // (3 -> 1). Либо флаг стал нашим (my==true). Латч на всю игру (бонус постоянный).
        val enemySpawnPos = cell(enemySpawnX, enemySpawnY)
        val ourFlagsNow = bonusFlags.filter { getRange(it, homeSpawn) < getRange(it, enemySpawnPos) }
        if (initialOwnFlags > 0 && (ourFlagsNow.size < initialOwnFlags || ourFlagsNow.any { it.my == true })) {
            bonusCaptured = true
        }
        // вражеский бонус: их флаги — ближе к ВРАЖЕСКОМУ спавну; когда остался один (захватили) — это
        // их тип бонуса (удвоит соответствующую угрозу в combatMatrix). Симметрично нашей детекции.
        val enemyFlags = bonusFlags.filter { getRange(it, enemySpawnPos) < getRange(it, homeSpawn) }
        val enemyBonus: BodyPartType? = if (enemyFlags.size == 1) enemyFlags[0].bonusType else null

        // роли по телу через единый классификатор roleOf (тот же — на врагах, для эко-учёта)
        val byRole = myCreeps.groupBy(::roleOf)
        val forwardMiners = byRole[RoleClass.FORWARD_MINER].orEmpty()
        val guards = byRole[RoleClass.GUARD].orEmpty()
        val miners = byRole[RoleClass.MINER].orEmpty()
        val haulers = byRole[RoleClass.HAULER].orEmpty()
        val fighters = byRole[RoleClass.FIGHTER].orEmpty()            // RANGED + HEAL (самодостаточный)
        val harassers = byRole[RoleClass.HARASSER].orEmpty()          // RANGED без HEAL (мобильный эко-юнит)
        val capturers = byRole[RoleClass.CAPTURER].orEmpty()
        haulerPhase.keys.retainAll(haulers.mapTo(HashSet()) { it.id }) // выбрасываем фазы погибших хаулеров

        // ЭКОНОМИЧЕСКИЙ УЧЁТ обеих сторон (полная видимость врага): банки спавнов, добыча, вложенная
        // стоимость эко/армии, боевая мощь с бонус-множителями. Управляет выбором спавна и постурой.
        val enemySpawns = getObjectsByPrototype(StructureSpawn::class).filter { it.my == false }
        val ourRangedMult = if (bonusCaptured) 2.0 else 1.0 // наш бонус — RANGED (DESIRED_BONUS)
        val enemyRangedMult = if (enemyBonus == RANGED_ATTACK) 2.0 else 1.0
        val enemyMeleeMult = if (enemyBonus == ATTACK) 2.0 else 1.0
        val us = accountEconomy(mySpawns, myCreeps, ourRangedMult, 1.0)
        val them = accountEconomy(enemySpawns, enemyCreeps, enemyRangedMult, enemyMeleeMult)

        // башни бьют ближайшего врага в радиусе (заряженная башня — оборона форвард-базы)
        if (enemyCreeps.isNotEmpty()) {
            for (tower in getObjectsByPrototype(StructureTower::class).filter { it.my == true }) {
                if ((tower.store[RESOURCE_ENERGY] ?: 0) < TOWER_ENERGY_COST) continue
                val target = enemyCreeps.minByOrNull { getRange(tower, it) } ?: continue
                if (getRange(tower, target) <= TOWER_RANGE) tower.attack(target)
            }
        }

        // INFLUENCE MAP (полная боевая модель, копия из spawn-strike): обновляем контекст и строим
        // матрицу опасности. Учитывает бонус (×2), урон башен, LOS (угроза НЕ сквозь стены), шаг
        // сближения врага. По ней ходят и бойцы, и одиночки (форвард-майнер, guard).
        DistanceMap.refresh()                            // обновляем набор ломаемых W-стен
        DistanceMap.ensureBuilt(homeSpawn, enemySpawnPos) // карты достижимости (болото взвешено)
        DistanceMap.ensureBaseZone(homeSpawn)             // сухой карман нашего спавна
        InfluenceMap.setBonuses(if (bonusCaptured) DESIRED_BONUS else null, enemyBonus)
        InfluenceMap.pruneStances(myCreeps.mapTo(HashSet()) { it.id })
        val myRamparts = getObjectsByPrototype(StructureRampart::class).filter { it.my == true }
        myRampartCells = myRamparts.mapTo(HashSet()) { it.x * 100 + it.y }
        InfluenceMap.setProtectedCells(myRampartCells)
        // непроходимое для ШАГА ВРАГА (чтобы опасность не «телепортировалась» за наши структуры)
        val enemyBlockedCells = HashSet<Int>()
        myRamparts.forEach { enemyBlockedCells.add(it.x * 100 + it.y) }
        getObjectsByPrototype(StructureSpawn::class).forEach { enemyBlockedCells.add(it.x * 100 + it.y) }
        getObjectsByPrototype(StructureExtension::class).forEach { enemyBlockedCells.add(it.x * 100 + it.y) }
        getObjectsByPrototype(StructureContainer::class).forEach { enemyBlockedCells.add(it.x * 100 + it.y) }
        InfluenceMap.setEnemyBlocked(enemyBlockedCells)
        // непроходимые клетки матрицы: стены + вражеские рампарты (зайти нельзя; узкий проход — ломаем)
        val blocked = ArrayList<Position>()
        getObjectsByPrototype(StructureWall::class).forEach { if (it.exists) blocked.add(cell(it.x, it.y)) }
        getObjectsByPrototype(StructureRampart::class).forEach { if (it.my == false) blocked.add(cell(it.x, it.y)) }
        // «рампарт со стройкой внутри» (башня/спавн строятся под уже стоящим нашим рампартом): рампарт
        // проходим, и крип, встав на него, БЛОКИРУЕТ достройку конструкции снизу. Делаем такие клетки
        // непроходимыми, чтобы никто не вставал и стройка завершилась. (Рампарт-self строится под
        // строителем — там рампарта ещё нет, в это условие не попадает, форвард-майнер/guard стоят.)
        getObjectsByPrototype(ConstructionSite::class).forEach {
            if (it.my == true && (it.x * 100 + it.y) in myRampartCells) blocked.add(cell(it.x, it.y))
        }
        val combat = InfluenceMap.dangerCostMatrix(enemyCreeps, myCreeps, blocked)
        if (DEBUG_DANGER) { InfluenceMap.drawDebug(fighters, myCreeps, enemyCreeps); InfluenceMap.drawDangerMatrix(combat) }
        // СТРАТЕГИЧЕСКИЕ КЛЕТКИ КОНТРОЛЯ (считаем один раз, кэш): страж входа в наш коридор + позиция
        // обстрела вражеского болота. Используются для разделения армии до пуша.
        if (stratOurSrcX < 0) {
            val our = nearestAccessibleSource(cell(homeSpawn.x, homeSpawn.y), sources)
            val enemy = nearestAccessibleSource(cell(enemySpawnX, enemySpawnY), sources)
            if (our != null && enemy != null) {
                stratOurSrcX = our.x; stratOurSrcY = our.y
                stratEnemySrcX = enemy.x; stratEnemySrcY = enemy.y
                // ТОЧКА 1: путь наш источник → база врага; узкие (1-ширинные) клетки НА нём
                val p1 = pathCells(our.x, our.y, enemySpawnX, enemySpawnY)
                stratPath1 = p1.toIntArray()
                stratCorridor = p1.filter { isNarrowCell(it / 100, it % 100) }.toIntArray()
                // СТРАЖ = клетка коридора, ближайшая к нашему спавну по пути (держим вход, враг идёт по одному)
                corridorGuardCell = stratCorridor.minByOrNull { DistanceMap.stepsFromMy(it / 100, it % 100) } ?: -1
                // ТОЧКА 2: путь источник врага → база врага; болото на нём → берём ТОЛЬКО патч, ближайший
                // к вражескому источнику (если на пути несколько), 8-связным заливом
                val p2 = pathCells(enemy.x, enemy.y, enemySpawnX, enemySpawnY)
                stratPath2 = p2.toIntArray()
                val seeds = p2.filter { DistanceMap.isSwamp(it / 100, it % 100) }
                val nearSeed = seeds.minByOrNull { maxOf(abs(it / 100 - enemy.x), abs(it % 100 - enemy.y)) }
                stratSwamp = if (nearSeed != null) floodSwamp(listOf(nearSeed)).toIntArray() else IntArray(0)
                // КОНТРОЛЬ БОЛОТА = твёрдая клетка на краю болота, ближайшая к нашему подходу
                swampControlCell = swampFiringCell(stratSwamp)
                if (DEBUG_LOG) println("strategic: ourS2=($stratOurSrcX,$stratOurSrcY) enemyS2=($stratEnemySrcX,$stratEnemySrcY) corridor=${stratCorridor.size} guard=$corridorGuardCell swampRegion=${stratSwamp.size} swampCtrl=$swampControlCell")
            }
        }
        if (DEBUG_STRATEGY && stratOurSrcX >= 0)
            InfluenceMap.drawStrategicPoints(stratOurSrcX, stratOurSrcY, stratEnemySrcX, stratEnemySrcY, stratPath1, stratCorridor, stratPath2, stratSwamp, corridorGuardCell, swampControlCell)

        // цель захватчика — НАШ кэшированный флаг (объект исчезнет при захвате, позиция — нет). ВСЕГДА
        // флаг: дойдя, капчер ОСТАЁТСЯ на нём и больше никуда не идёт (держит бонус, не уходит к центру).
        val captureTarget = cell(ourFlagX, ourFlagY)

        for (creep in capturers) if (creep.getRangeTo(captureTarget) > 0) creep.moveTo(captureTarget)
        // копатели по id (детерминированно) встают каждый на свою фиксированную клетку
        val minersSorted = miners.sortedBy { it.id.unsafeCast<Double>() }
        for ((idx, creep) in minersSorted.withIndex()) {
            val spot = if (idx == 0) cell(minerSpot1X, minerSpot1Y) else cell(minerSpot2X, minerSpot2Y)
            runMiner(creep, spot, sources, haulers, homeSpawn)
        }
        for (creep in haulers) runHauler(creep, miners, homeSpawn)
        // форвард-майнер и guard идут к своим точкам ЧЕРЕЗ матрицу опасности (обходят огонь/башни)
        for (creep in forwardMiners) runForwardMiner(creep, sources, forwardSpawn, combat, homeSpawn, enemyCreeps)
        for (creep in guards) runGuard(creep, combat)

        // БОЙ: стрельба с фокусом по лекарям, кайт и бесконфликтное движение через TrafficManager.
        val focus = fighterFocus(fighters, enemyCreeps)
        val rally = cell(rallyX, rallyY) // горловина коридора — держим до штурма
        // ВЫБОР ПРОХОДА: цена маршрута = длина + штраф за врагов, засевших у его горловины. Берём
        // наименее контестируемый проход; если он дешевле обходного коридора — ломимся через него
        // (иначе breachWalls пуст → идём коридором). Враг засел в центре → выберем верхний/нижний.
        fun contestAt(cx: Int, cy: Int) = enemyCreeps.count { getRange(cell(it.x, it.y), cell(cx, cy)) <= CONTEST_RADIUS }
        val bestRoute = breachRoutes.minByOrNull { it.pathLen + contestAt(it.chokeX, it.chokeY) * CONTEST_PENALTY }
        val corridorCost = (if (aroundLen == Int.MAX_VALUE) Int.MAX_VALUE else aroundLen + contestAt(rallyX, rallyY) * CONTEST_PENALTY)
        val routeCost = bestRoute?.let { it.pathLen + contestAt(it.chokeX, it.chokeY) * CONTEST_PENALTY } ?: Int.MAX_VALUE
        breachWalls = if (bestRoute != null && routeCost < corridorCost) bestRoute.walls else emptyList()
        // текущая стена пролома = ПЕРВАЯ ещё стоящая из ворот выбранного прохода. Сломав её, цель сама
        // переходит на следующую — так бойцы прогрызают тоннель по очереди, а не уходят в обход.
        val breachWall: StructureWall? = if (breachWalls.isNotEmpty()) {
            val existing = getObjectsByPrototype(StructureWall::class).filter { it.exists }
            breachWalls.firstNotNullOfOrNull { packed -> existing.firstOrNull { it.x == packed / 100 && it.y == packed % 100 } }
        } else null
        // ОБОРОНА: отбиваем врагов на нашей половине (по достижимости) И боевых, давящих нашу
        // ЭКОНОМИКУ (рядом с майнером/форвард-майнером/хаулером) — даже за границей половины.
        val ourEco = miners + forwardMiners + haulers
        val intruders = enemyCreeps.filter { e ->
            DistanceMap.inOurHalf(e.x, e.y) ||
                (InfluenceMap.profileOf(e).let { p -> p.melee + p.ranged > 0.0 } && ourEco.any { getRange(e, it) <= ECO_DEFEND_R })
        }
        // ВРАЖЕСКИЕ БАШНИ + интрудеры ПОД БАШНЕЙ (в зоне её угрозы — до них не добраться). Их исключаем
        // из «угроз»: недостижимые интрудеры НЕ должны держать нас в DEFEND и блокировать раш базы.
        val enemyTowers = getObjectsByPrototype(StructureTower::class).filter { it.my == false && it.exists }
        fun towerShielded(e: Creep) = enemyTowers.any { getRange(e, it) <= TOWER_REACH_BLOCK_R }
        val threats = intruders.filter { !towerShielded(it) } // достижимые угрозы
        // ЦЕЛЬ обороны — интрудер, ближайший к ЛЮБОМУ нашему КРИТ-АКТИВУ: спавнам (ВКЛЮЧАЯ второй —
        // потеря спавна = проигрыш, потому спавн приоритетнее) и эко-крипам. Раньше мерили только от
        // ДОМАШНЕГО спавна (stepsFromMy) → враг у второго спавна (далеко от дома) игнорировался.
        val defendTargetRaw = intruders.minByOrNull { intr ->
            val toSpawn = mySpawns.minOfOrNull { getRange(intr, it) } ?: Int.MAX_VALUE
            val toEco = ourEco.minOfOrNull { getRange(intr, it) } ?: Int.MAX_VALUE
            minOf(toSpawn, toEco + 2) // спавн критичнее эко на 2 клетки
        }
        val enemyRamparts = getObjectsByPrototype(StructureRampart::class).filter { it.my == false }
        // контекст позиционного скоринга: кэш flow-полей на тик, наборы непроходимых/занятых клеток.
        val flowCache = HashMap<Int, IntArray>()
        fun flowTo(t: Position): IntArray = flowCache.getOrPut(t.x * 100 + t.y) { DistanceMap.flowFieldTo(t, blocked) }
        // ГОНКА СПАВНОВ: враги зашли на нашу половину, но если мы снесём их спавн раньше, чем они наш
        // (домашний) — дожимаем, а не откатываемся на защиту. Время = марш (flow) + hits/DPS; их подход
        // по ИХ проходимости (стены + НАШИ рампарты). RACE_MARGIN страхует грубость оценки.
        val pushWinsRace = run {
            // гонка имеет смысл ТОЛЬКО реальной армией: иначе один боец «выигрывал гонку» (враг не достаёт
            // наш домашний спавн), обнулял defendTarget и уходил ломать стену вместо защиты майнера.
            if (enemySpawn == null || threats.isEmpty() || fighters.size < PUSH_MIN_FIGHTERS || !bonusCaptured) {
                lastRaceCommitted = false; return@run false
            }
            val ourFlow = flowTo(enemySpawn)
            val ourTravel = fighters.minOf { val d = ourFlow[it.x * 100 + it.y]; if (d < 0) Int.MAX_VALUE else d }
            val ourDps = fighters.sumOf { InfluenceMap.profileOf(it).ranged }
            val blockedForEnemy = ArrayList<Position>()
            getObjectsByPrototype(StructureWall::class).forEach { if (it.exists) blockedForEnemy.add(cell(it.x, it.y)) }
            myRamparts.forEach { blockedForEnemy.add(cell(it.x, it.y)) }
            val enemyApproach = DistanceMap.flowFieldTo(homeSpawn, blockedForEnemy)
            // считаем только ДОСТИЖИМЫЕ угрозы (под башней до нас не дойдут — в гонку не входят)
            val enemyTravel = threats.minOf { val d = enemyApproach[it.x * 100 + it.y]; if (d < 0) Int.MAX_VALUE else d }
            val enemyDps = threats.sumOf { val p = InfluenceMap.profileOf(it); p.melee + p.ranged }
            lastRaceCommitted = when {
                ourTravel == Int.MAX_VALUE || ourDps <= 0.0 -> false
                enemyTravel == Int.MAX_VALUE || enemyDps <= 0.0 -> true
                else -> {
                    val ourTicks = maxOf(0, ourTravel - RANGED_RANGE) + (enemySpawn.hits ?: enemySpawn.hitsMax ?: 3000) / ourDps
                    val enemyTicks = enemyTravel + (homeSpawn.hits ?: homeSpawn.hitsMax ?: 3000) / enemyDps
                    // ВХОД в гонку — по запасу RACE_MARGIN; УДЕРЖАНИЕ — пока гонка не проиграна ЯВНО.
                    // Гистерезис: в серой зоне (enemyTicks-MARGIN .. enemyTicks) держим прошлое решение.
                    if (lastRaceCommitted) ourTicks < enemyTicks else ourTicks + RACE_MARGIN < enemyTicks
                }
            }
            lastRaceCommitted
        }
        // выигрываем гонку — НЕ бросаем штурм ради защиты половины
        val defendTarget = if (pushWinsRace) null else defendTargetRaw
        // СТРАТЕГИЧЕСКАЯ ПОСТУРА (управляет и выбором спавна, и штурмом). Учитываем только ДОСТИЖИМЫЕ
        // угрозы: интрудеры под башней не должны держать нас в DEFEND (до них всё равно не добраться).
        val posture = derivePosture(us, them, threats, pushWinsRace, getTicks())
        val pushing = posture == Posture.PUSH

        // ВЫБОР СПАВНА по экономике/постуре (вместо фикс-очереди). Домашний — всё; форвард-спавн — армия.
        chooseSpawnBody(homeSpawn, us, them, posture, intruders, enemyCreeps, forwardOnly = false)?.let { spawnWith(homeSpawn, it.body, it.dirs) }
        if (forwardSpawn != null) chooseSpawnBody(forwardSpawn, us, them, posture, intruders, enemyCreeps, forwardOnly = true)?.let { spawnWith(forwardSpawn, it.body, it.dirs) }
        val blockedSet = blocked.mapTo(HashSet()) { it.x * 100 + it.y }
        val enemyPositions = enemyCreeps.mapTo(HashSet()) { it.x * 100 + it.y }
        val occupiedByFighters = fighters.mapTo(HashSet()) { it.x * 100 + it.y }
        val meleeEnemies = enemyCreeps.filter { InfluenceMap.profileOf(it).melee > 0.0 }
        // глобальный учёт хила на тик: id цели -> уже вложено / ожидаемый входящий урон (для пре-хила)
        val healDone = HashMap<String, Int>()
        val incoming = HashMap<String, Int>()
        // НАША СИЛЬНЕЙШАЯ ЗОНА для побега: клетка бойца с макс. influenceAt, НО только если там мы РЕАЛЬНО
        // доминируем (influence>0) — тогда откатываемся к силе/хилу. Если проигрываем ВЕЗДЕ (давят числом),
        // «лучшая» клетка = просто наименее проигрышная КУЧА (обычно у форвард-спавна, где бойцы рождаются
        // и жмутся) — бежать туда = кормить врага. В этом случае откатываемся в ДОМАШНЮЮ базу (рампарты/тыл).
        val armyStrongZone: Position = run {
            val best = fighters.maxByOrNull { InfluenceMap.influenceAt(it.x, it.y, fighters, enemyCreeps) }
            if (best != null && InfluenceMap.influenceAt(best.x, best.y, fighters, enemyCreeps) > 0.0) cell(best.x, best.y)
            else cell(homeSpawn.x, homeSpawn.y)
        }
        // РАЗДЕЛЕНИЕ АРМИИ до пуша: ПРИОРИТЕТ — держать вход в наш коридор (защита нашего источника),
        // первые крипы заполняют квоту стража; следующие идут прорываться к вражескому болоту. Станция
        // липкая (по id в карте), чтобы не металось. Гибнет страж → квота освобождается, доберём нового.
        // ВСЯ армия (бойцы И харассеры) — без различий по спавну: форвард-харассеры тоже расходятся.
        val army = fighters + harassers
        fighterStation.keys.retainAll(army.mapTo(HashSet()) { it.id })
        var guardCount = army.count { fighterStation[it.id] == 0 }
        for (f in army) if (f.id !in fighterStation) {
            if (guardCount < CORRIDOR_GUARD_QUOTA) { fighterStation[f.id] = 0; guardCount++ } else fighterStation[f.id] = 1
        }
        val guardPos = if (corridorGuardCell >= 0) cell(corridorGuardCell / 100, corridorGuardCell % 100) else rally
        val swampPos = if (swampControlCell >= 0) cell(swampControlCell / 100, swampControlCell % 100) else rally
        // РАЗДЕЛЕНИЕ ИНТРУДЕРОВ на 2 группы по близости (по ДОСТИЖИМОСТИ/flow) к двум стратегическим
        // точкам: интрудер «принадлежит» той точке, до которой он ближе. Боец бьёт ТОЛЬКО свою группу
        // (где его станция — ближайшая точка) — иначе вся армия бросала станции и бежала к ближайшему
        // врагу через всю карту. У кого своей группы нет — держит станцию.
        // БАШНЯ У ВРАЖЕСКОГО ИСТОЧНИКА: болото становится мёртвой зоной — захватить/удержать нельзя.
        // Бросаем болотную задачу (не лезем под башню, не преследуем врагов под ней), группу болота
        // перенаправляем на staging центрального прорыва (rally) — «прорываемся через другие места».
        val enemySrcCell = if (stratEnemySrcX >= 0) cell(stratEnemySrcX, stratEnemySrcY) else null
        // enemyTowers/towerShielded определены выше (у intruders). Башня У ИСТОЧНИКА — отдельный, более
        // близкий радиус (отмена болотной задачи), чем «интрудер недостижим из-за башни».
        val enemyTowerAtSrc = enemySrcCell != null && enemyTowers.any { getRange(it, enemySrcCell) <= TOWER_GUARD_R }
        val guardFlow = if (corridorGuardCell >= 0) flowTo(guardPos) else null
        val swampFlow = if (swampControlCell >= 0) flowTo(swampPos) else null
        fun nearerSwamp(e: Creep): Boolean {
            val dg = guardFlow?.get(e.x * 100 + e.y)?.takeIf { it >= 0 } ?: Int.MAX_VALUE
            val ds = swampFlow?.get(e.x * 100 + e.y)?.takeIf { it >= 0 } ?: Int.MAX_VALUE
            return ds < dg
        }
        // прикрытые башней враги выпадают из обеих групп — мы их не преследуем (не кормим башню)
        val swampIntruders = intruders.filter { nearerSwamp(it) && !towerShielded(it) }
        val corridorIntruders = intruders.filter { !nearerSwamp(it) && !towerShielded(it) }
        // ТРЕВОГА БАЗЫ: боевые враги у ЛЮБОГО нашего спавна (вкл. прорвавшихся к главному) — цель для ВСЕХ
        // бойцов, минуя деление на группы/станции и towerShielded. Иначе стражи кучковались у форвард-
        // спавна (там их станция), пока прорвавшиеся крипы били главный спавн.
        val spawnThreats = enemyCreeps.filter { e ->
            InfluenceMap.profileOf(e).let { p -> p.melee + p.ranged > 0.0 } && mySpawns.any { getRange(e, it) <= SPAWN_ALARM_R }
        }
        // БЛИЗКИЕ боевые враги — общая оборона: ЛЮБОГО боевого врага рядом с собой боец бьёт независимо
        // от группы/классификации. НЕ доверяем inOurHalf (в углах врёт → враг у нашего источника считался
        // «чужой половиной» и стражи его игнорировали). Берём всех боевых (не под башней); на бойца
        // ограничиваем радиусом DEFEND_CROSS_REACH, чтобы не гонять через карту. Деление — для ДАЛЬНИХ.
        val nearbyCombat = enemyCreeps.filter { e ->
            InfluenceMap.profileOf(e).let { p -> p.melee + p.ranged > 0.0 } && !towerShielded(e)
        }
        // станция болотной группы: при башне у источника — НЕ к болоту (смерть под башней), а на rally
        val swampStationPos = if (enemyTowerAtSrc) rally else swampPos
        // СРЫВ ЭКОНОМИКИ ВРАГА у его источника: группа болота без боевой цели давит вражеские
        // констракшн-сайты (встаём на клетку — отмена постройки) и добивает эко-крипов/майнера у
        // источника (лучим, даже под рампартом — сквозь стену). ТОЛЬКО если у источника НЕТ башни.
        val enemyCS = if (enemySrcCell != null && !enemyTowerAtSrc) getObjectsByPrototype(ConstructionSite::class)
            .filter { it.my == false && it.exists && getRange(it, enemySrcCell) <= SRC_DISRUPT_R } else emptyList()
        val enemySrcEco = if (enemySrcCell != null && !enemyTowerAtSrc) enemyCreeps
            .filter { InfluenceMap.profileOf(it).let { p -> p.melee + p.ranged == 0.0 } && getRange(it, enemySrcCell) <= SRC_DISRUPT_R } else emptyList()
        // ПОЛЕ ПРОРЫВА к вражескому спавну: ломаемые стены/чужие рампарты проходимы по цене, урон башен —
        // доп.цена клетки → путь обходит башни ИЛИ тоннелит сквозь стены (новый проход). По нему пушат
        // штурмующие И идлящие/заблокированные крипы. Считаем раз за тик, только когда кто-то будет пушить.
        val enemyRampartCells = enemyRamparts.mapTo(HashSet()) { it.x * 100 + it.y }
        val anyTowerBlock = enemyTowerAtSrc && army.any { fighterStation[it.id] == 1 }
        val pushField: IntArray? = if (enemySpawn != null && (pushing || anyTowerBlock)) {
            val danger = IntArray(10000)
            if (enemyTowers.isNotEmpty()) for (x in 0..99) for (y in 0..99) {
                val d = InfluenceMap.enemyTowerDamageAt(x, y)
                if (d > 0.0) danger[x * 100 + y] = minOf(PUSH_DANGER_CAP, (d * PUSH_DANGER_W).toInt())
            }
            DistanceMap.pushFieldTo(enemySpawnPos, danger)
        } else null
        val allWalls = if (pushField != null) getObjectsByPrototype(StructureWall::class).filter { it.exists } else emptyList()
        for (creep in fighters) {
            // СТОЙКА по балансу сил (себя ВКЛЮЧАЕМ — боец часть силы; иначе двое перед врагом каждый
            // видит «нас один» и оба отступают). Отступаем при недовесе (порог RETREAT_THRESHOLD).
            val balance = InfluenceMap.influenceAt(creep.x, creep.y, fighters, enemyCreeps)
            val stance = InfluenceMap.updateStance(creep.id, balance)
            // ОБОРОНА, РАЗВЯЗАННАЯ С race-флипом: цель берём из intruders НАПРЯМУЮ (не из defendTarget —
            // тот гас при pushWinsRace=true и дёргал бойца на брешь, лекарь врага успевал откачать).
            // Раз вступив в бой с интрудером — ДОВОДИМ: липко держим, пока он жив и в DEFEND_STICKY_RANGE,
            // даже если штурм/гонка мигнули. При штурме НОВУЮ цель берём только если интрудер уже близко
            // (DEFEND_ENGAGE_RANGE) — дальних не преследуем; вне штурма отвечаем на любого ближайшего.
            // цели обороны — ТОЛЬКО БЛИЗКИЕ боевые враги (в DEFEND_CROSS_REACH) + угрозы у наших спавнов
            // (любая дистанция). НЕ берём дальних интрудеров «своей группы» — иначе боец залипал (sticky)
            // на дальней цели и брёл к ней через всю карту вместо пуша/станции (отсюда «облепили спавн»
            // и «не пушат»). Деление на группы теперь решает лишь куда идти БЕЗ боя (станция/пуш).
            val nearbyDefense = nearbyCombat.filter { creep.getRangeTo(it) <= DEFEND_CROSS_REACH }
            val myGroup = if (spawnThreats.isEmpty()) nearbyDefense else (nearbyDefense + spawnThreats).distinct()
            // ПУШАЩИЙ (штурм ИЛИ болото-под-башней) НЕ гоняется за кайтерами — идёт к спавну, стреляя по
            // пути; перебивают только угрозы у НАШИХ спавнов. Иначе враги у их башни (d5-20) дёргали его
            // push↔defend (они кайтят/мерцают через границу башни) — крип скакал туда-сюда.
            val pushCapable = enemySpawn != null && pushField != null && (pushing || (fighterStation[creep.id] == 1 && enemyTowerAtSrc))
            val myDefend: Creep? = run {
                if (pushCapable) return@run spawnThreats.minByOrNull { creep.getRangeTo(it) }
                    ?.also { fighterDefendId[creep.id] = it.id } ?: run { fighterDefendId.remove(creep.id); null }
                // ОБОРОНА: липко держим БЛИЗКОГО не-прикрытого башней врага (или угрозу спавна); за
                // дальними кайтерами НЕ гонимся — берём ТОЛЬКО ≤ DEFEND_ENGAGE_RANGE, иначе скачем.
                val kept = fighterDefendId[creep.id]?.let { id -> enemyCreeps.firstOrNull { it.id == id } }
                    ?.takeIf { it in spawnThreats || (creep.getRangeTo(it) <= DEFEND_STICKY_RANGE && !towerShielded(it)) }
                if (kept != null) { fighterDefendId[creep.id] = kept.id; return@run kept }
                val cand = (spawnThreats + nearbyCombat.filter { creep.getRangeTo(it) <= DEFEND_ENGAGE_RANGE })
                    .minByOrNull { creep.getRangeTo(it) }
                if (cand != null) { fighterDefendId[creep.id] = cand.id; cand } else { fighterDefendId.remove(creep.id); null }
            }
            // группа болота (station 1) без боевой цели и не в штурме — срывает экономику врага у его
            // источника: констракшн-сайт (встаём на него) ПРИОРИТЕТНЕЕ, иначе эко-крип/майнер (лучим).
            val swampIdle = fighterStation[creep.id] == 1 && myDefend == null && !pushing
            val disruptSite = if (swampIdle) enemyCS.minByOrNull { creep.getRangeTo(it) } else null
            val disruptMiner = if (swampIdle && disruptSite == null) enemySrcEco.minByOrNull { creep.getRangeTo(it) } else null
            // ПУШ: штурм (global PUSH) ИЛИ нет задачи и станция мертва (болото под башней) → идём к
            // вражескому спавну по ПОЛЮ ПРОРЫВА (сквозь стены, в обход башен), ломая первую преграду на пути.
            val idle = myDefend == null && disruptSite == null && disruptMiner == null
            val pushNow = pushField != null && enemySpawn != null &&
                (pushing || (idle && fighterStation[creep.id] == 1 && enemyTowerAtSrc))
            val pushBlock = if (pushNow) nextPushBlockCell(creep.x, creep.y, pushField!!, enemyRampartCells) else -1
            val pushWall = if (pushBlock >= 0) allWalls.firstOrNull { it.x == pushBlock / 100 && it.y == pushBlock % 100 } else null
            val pushRampart = if (pushBlock >= 0 && pushWall == null) enemyRamparts.firstOrNull { it.x == pushBlock / 100 && it.y == pushBlock % 100 } else null
            val focusFor = myDefend ?: disruptMiner ?: focus
            // ДВИЖЕНИЕ: при обороне — к своей липкой цели (интрудер у нашего спавна/экономики), а НЕ к
            // случайному ближайшему врагу. Стрельба по пути — focus/ближний в радиусе (fighterShoot).
            val (target: Position, reason: String) = when {
                myDefend != null -> cell(myDefend.x, myDefend.y) to "defend"
                pushNow && enemySpawn != null -> enemySpawn to "push"  // штурм по полю прорыва (преграду ломаем по пути)
                disruptSite != null -> cell(disruptSite.x, disruptSite.y) to "disruptSite"   // ВСТАЁМ на сайт — отмена
                disruptMiner != null -> cell(disruptMiner.x, disruptMiner.y) to "disruptMiner" // подойти и лучить майнера
                // до пуша — на свою станцию: страж коридора (наш источник) ИЛИ контроль болота (rally при башне)
                fighterStation[creep.id] == 1 -> swampStationPos to "swampStn"
                else -> guardPos to "guardStn"
            }
            // преграда на пути к цели — ломаем. При пуше берём преграду с поля прорыва (стена/рампарт), иначе — рампарт по линии к цели
            val blockRampart = if (pushNow) (pushRampart ?: forwardRampart(creep, target, enemyRamparts)) else forwardRampart(creep, target, enemyRamparts)
            // отступаем (mustFlee), если стойка RETREAT ИЛИ чистый урон с учётом хила смертелен за 2 хода
            val mustFlee = stance == InfluenceMap.Stance.RETREAT ||
                creep.hits < InfluenceMap.netDamageAt(creep.x, creep.y, enemyCreeps, fighters) * 2
            val aggressive = stance == InfluenceMap.Stance.ADVANCE
            val inCombat = enemyCreeps.any { creep.getRangeTo(it) <= RANGED_RANGE + 2 }
            // прорыв: штурм (идём на спавн сквозь стены/болото) — снимаем штраф болота
            val breaching = pushNow && !mustFlee
            // стреляем всегда (в т.ч. отступая); при пуше ломаем преграду на пути прорыва (стену/рампарт)
            fighterCombat(creep, fighters, enemyCreeps, enemySpawn, focusFor, if (myDefend == null && pushNow && !mustFlee) (pushWall ?: breachWall) else null, blockRampart, pushing, healDone, incoming)
            // при побеге целью движения становится наша сильнейшая зона (откат к поддержке), иначе — боевая цель
            val moveTarget = if (mustFlee) armyStrongZone else target
            // на констракшн-сайт надо ВСТАТЬ (range 0 — отмена); к остальным целям держим дистанцию стрельбы
            val standoff = if (disruptSite != null && !mustFlee) 0 else RANGED_RANGE
            // при пуше движемся по ПОЛЮ ПРОРЫВА (сквозь стены, в обход башен), иначе — обычный flow к цели
            val moveFlow = if (!mustFlee && pushNow) pushField!! else flowTo(moveTarget)
            // страж у станции (guardStn) — плотная куча на дистанции стрельбы от охраняемой клетки (cluster)
            val step = bestFighterMove(creep, moveTarget, moveFlow, standoff, mustFlee, aggressive, inCombat, breaching, enemyCreeps, fighters, meleeEnemies, blockedSet, enemyPositions, occupiedByFighters, reason == "guardStn")
            // ЛОГ РЕШЕНИЯ КАЖДОГО бойца: st=станция(0 страж/1 болото), reason=ветка цели, tgt=куда идём,
            // push=пушим ли (+почему нет), wall=преграда пролома на пути, grp=сколько целей видит, def=
            // липкая цель обороны, near=ближайший враг (дист), flee/стойка, step=куда шагнул (stay=стоим).
            if (DEBUG_LOG) {
                val nd = enemyCreeps.minByOrNull { creep.getRangeTo(it) }?.let { creep.getRangeTo(it) } ?: -1
                val pushWhy = when {
                    pushing -> "PUSH"
                    !idle -> "busy"
                    fighterStation[creep.id] != 1 -> "st0"
                    !enemyTowerAtSrc -> "noTwrSrc"
                    pushField == null -> "noFld"
                    else -> "ok"
                }
                val wallStr = pushWall?.let { "W(${it.x},${it.y})" } ?: pushRampart?.let { "R(${it.x},${it.y})" } ?: "-"
                println("fig ${creep.id} st${fighterStation[creep.id]} (${creep.x},${creep.y})hp${creep.hits} $reason tgt=(${target.x},${target.y}) " +
                    "push=$pushNow($pushWhy) wall=$wallStr blk=$pushBlock grp=${myGroup.size} def=${myDefend?.let { "(${it.x},${it.y})" } ?: "-"} " +
                    "near=d$nd flee=$mustFlee ${stance.name.take(3)} step=${step?.let { "(${it.x},${it.y})" } ?: "stay"}")
            }
            step?.let { TrafficManager.request(creep, it) }
        }
        // ХАРАССЕР: мобильный эко-юнит (заменил статичный костыль-гвард). Приоритеты на тик:
        //  1) оборона нашей половины — ближайший идёт на defendTarget (быстрый ответчик, кулак не рвём);
        //  2) перехват врага у форвард-базы (на подходе/в горловине);
        //  3) рейд (balanced): кайт-убийство ОТКРЫТОГО вражеского эко-крипа, не у их базы;
        //  4) idle — у выносного поста прохода / rally.
        if (harassers.isNotEmpty()) {
            // рейд-цели — ТОЛЬКО экономика (майнеры/хаулеры/капчер); медик (роль MEDIC) сюда НЕ входит,
            // он армейская поддержка — его добивает боевой фокус, а не эко-рейд.
            val enemyEco = enemyCreeps.filter { roleOf(it).let { r -> r == RoleClass.MINER || r == RoleClass.FORWARD_MINER || r == RoleClass.HAULER || r == RoleClass.CAPTURER } }
            val enemyCombat = enemyCreeps.filter { InfluenceMap.profileOf(it).let { p -> p.melee + p.ranged > 0.0 } }
            val fwdCell = cell(fwdMinerX, fwdMinerY)
            val fwdThreat = if (forwardEnabled) enemyCombat.filter { getRange(it, fwdCell) <= FWD_INTERCEPT_R }.minByOrNull { getRange(it, fwdCell) } else null
            val responder = if (defendTarget != null) harassers.minByOrNull { it.getRangeTo(defendTarget) } else null
            for (creep in harassers) {
                val raidTarget = if (posture == Posture.RAID) economyHuntTarget(creep, enemyEco, enemyCombat, enemySpawn) else null
                val focusC: Creep? = when {
                    defendTarget != null && creep === responder -> defendTarget
                    fwdThreat != null -> fwdThreat
                    raidTarget != null -> raidTarget
                    else -> null
                }
                // нет активной цели — расходимся на СВОЮ стратегическую станцию (как бойцы), НЕ залипаем у форварда
                val target: Position = focusC?.let { cell(it.x, it.y) } ?: (if (fighterStation[creep.id] == 1) swampStationPos else guardPos)
                fighterShoot(creep, enemyCreeps, null, focusC, null, null, false) // эко-юнит: только стрельба, фокус на цели
                val mustFlee = creep.hits < InfluenceMap.netDamageAt(creep.x, creep.y, enemyCreeps, harassers) * 2
                val inCombat = enemyCreeps.any { creep.getRangeTo(it) <= RANGED_RANGE + 2 }
                val stop = if (focusC != null) RANGED_RANGE else 1
                // при побеге харассер откатывается к сильнейшей зоне АРМИИ (под защиту кулака), не к посту
                val moveTarget = if (mustFlee) armyStrongZone else target
                val step = bestFighterMove(creep, moveTarget, flowTo(moveTarget), stop, mustFlee, false, inCombat, false, enemyCreeps, harassers, meleeEnemies, blockedSet, enemyPositions, occupiedByFighters)
                step?.let { TrafficManager.request(creep, it) }
            }
        }
        TrafficManager.resolve(fighters + harassers, myCreeps + enemyCreeps)

        if (DEBUG_LOG) {
            println(
                "ps: tick=${getTicks()} cap=${capturers.size} mnr=${miners.size} fmnr=${forwardMiners.size} grd=${guards.size} har=${harassers.size} hlr=${haulers.size} fig=${fighters.size} " +
                    "enemy=${enemyCreeps.size} captured=$bonusCaptured spawns=${mySpawns.size} " +
                    "intruders=${intruders.size}(threat=${threats.size},corr=${corridorIntruders.size},swamp=${swampIntruders.size},alarm=${spawnThreats.size}) defend=${defendTarget?.let { "(${it.x},${it.y})d=${DistanceMap.stepsFromMy(it.x, it.y)}" } ?: "-"} " +
                    "route=${bestRoute?.let { "choke(${it.chokeX},${it.chokeY})" } ?: "-"} breach=${breachWall?.let { "(${it.x},${it.y})" } ?: "corridor"} eRamp=${enemyRamparts.size} race=$pushWinsRace " +
                    "homeE=${homeSpawn.store[RESOURCE_ENERGY] ?: 0} fwdE=${forwardSpawn?.store?.get(RESOURCE_ENERGY) ?: -1} posture=$posture push=$pushing " +
                    "azone=(${armyStrongZone.x},${armyStrongZone.y}) fwdSpawn=($fwdSpawnX,$fwdSpawnY) guard=$corridorGuardCell swamp=$swampControlCell towerSrc=$enemyTowerAtSrc pushField=${pushField != null} " +
                    "ECO us[bank=${us.spawnBank} harv=${us.harvestPerTick.toInt()} eco=${us.ecoValue} army=${us.armyValue} pow=${us.power.toInt()}] " +
                    "them[bank=${them.spawnBank} harv=${them.harvestPerTick.toInt()} eco=${them.ecoValue} army=${them.armyValue} pow=${them.power.toInt()} fwd=${them.hasForwardBase} fm=${them.count(RoleClass.FORWARD_MINER)} mnr=${them.count(RoleClass.MINER)}]"
            )
        }
    }

    /**
     * Очередь спавна (bootstrap-порядок): 1) ПЕРВЫЙ копатель (влезает в стартовые 500) — идёт
     * копать; 2) хаулер (дёшево, 100) — сразу начинает вывозить, копатель не отрывается от
     * источника; 3) ВТОРОЙ копатель; 4) захватчик флага (50, бонус не срочен — бойцов ещё нет);
     * 5) дальше бойцы. Тела дороже капчерера спавним только когда в store хватает энергии.
     */
    private class SpawnCandidate(val body: Array<BodyPartType>, val cost: Int, val dirs: Array<DirectionConstant>?, val desire: Double)

    /**
     * Утилитарный выбор тела к спавну: каждому кандидату — desire из постуры + дефицитов (доход, разрыв
     * армий vs враг, бонус не взят, состояние форвард-базы), выбираем макс ДОСТУПНЫЙ; если глобально
     * лучший дороже энергии и заметно желаннее — копим (null). forwardOnly: форвард-спавн строит только
     * армию (HARASSER/FIGHTER) — он у фронта. Счётчики ролей — ГЛОБАЛЬНЫЕ (us), решает экономика целиком.
     */
    private fun chooseSpawnBody(spawn: StructureSpawn, us: Economy, them: Economy, posture: Posture, intruders: List<Creep>, enemyCreeps: List<Creep>, forwardOnly: Boolean): SpawnCandidate? {
        if (spawn.spawning != null) return null
        val energy = spawn.store[RESOURCE_ENERGY] ?: 0
        // ВСЕ крипы выходят мимо dock-клетки хаулера (включая майнеров/хаулеров): иначе новорождённый
        // занимал клетку разгрузки. До источника майнер дойдёт — пара лишних тиков не критична.
        val avoidDock = ALL_DIRECTIONS.filter { it !in dockDirs }.toTypedArray().ifEmpty { ALL_DIRECTIONS }
        fun combat(c: Creep) = InfluenceMap.profileOf(c).let { it.melee + it.ranged > 0.0 }

        val ecoSeeded = us.count(RoleClass.MINER) >= TARGET_MINERS && us.count(RoleClass.HAULER) >= TARGET_HAULERS
        val armyGap = maxOf(0.0, them.power * ARMY_SAFETY - us.power)
        val regenCap = SOURCE_ENERGY_REGEN * (1 + if (us.hasForwardBase) 1 else 0)
        val incomeGap = maxOf(0.0, regenCap - us.harvestPerTick)
        val deliveryGap = maxOf(0, (us.minerWorkParts * HARVEST_POWER + HAULER_THROUGHPUT - 1) / HAULER_THROUGHPUT - us.count(RoleClass.HAULER))
        val enemyNearFwd = forwardEnabled && enemyCreeps.any { combat(it) && getRange(it, cell(fwdMinerX, fwdMinerY)) <= SPAWN_NEAR_R }
        val enemyNearFlag = !bonusCaptured && enemyCreeps.any { getRange(it, cell(ourFlagX, ourFlagY)) <= SPAWN_NEAR_R }
        // маршрут к форвард-базе свободен от вражеских бойцов? (семплим путь дом→форвард-майнер) —
        // иначе безоружного майнера перехватят и убьют по дороге. spawn здесь = домашний (!forwardOnly).
        val routeClear = !forwardEnabled || (1..3).none { i ->
            val px = spawn.x + (fwdMinerX - spawn.x) * i / 4
            val py = spawn.y + (fwdMinerY - spawn.y) * i / 4
            enemyCreeps.any { combat(it) && getRange(it, cell(px, py)) <= SPAWN_NEAR_R }
        }

        val cands = ArrayList<SpawnCandidate>()
        fun add(body: Array<BodyPartType>, cost: Int, dirs: Array<DirectionConstant>?, desire: Double) {
            if (desire > 0.0) cands.add(SpawnCandidate(body, cost, dirs, desire))
        }

        if (!forwardOnly) {
            // MINER — фундамент дохода (счётчик-гейт даёт открытие miner→hauler→miner)
            var d = when { us.count(RoleClass.MINER) == 0 -> 1000.0; us.count(RoleClass.MINER) < TARGET_MINERS -> 600.0; else -> 0.0 }
            d += W_MINER_INCOME * incomeGap
            if (posture == Posture.DEFEND) d *= 0.4
            add(MINER_BODY, MINER_COST, avoidDock, d)
            // HAULER — доставка от майнеров к спавну
            var dh = if (us.count(RoleClass.HAULER) < TARGET_HAULERS && us.count(RoleClass.MINER) >= 1) 700.0 else 0.0
            dh += W_HAUL * deliveryGap
            if (posture == Posture.DEFEND) dh *= 0.5
            add(HAULER_BODY, HAULER_COST, avoidDock, dh)
            // CAPTURER — захват бонуса (×2 урон всем на всю игру); +гонка, если враг у наших флагов
            var dc = if (!bonusCaptured && us.count(RoleClass.CAPTURER) == 0 && initialOwnFlags > 0) 400.0 else 0.0
            if (dc > 0 && enemyNearFlag) dc += W_BONUS_RACE
            add(CAPTURER_BODY, CAPTURER_COST, avoidDock, dc)
            // FORWARD_MINER — расширение ТОЛЬКО безопасно: не в DEFEND, не под огнём у базы, мы НЕ слабее
            // армией (иначе безоружного 950-майнера враг убьёт), и маршрут к базе чист. Уступаем по армии
            // или враг на пути → df=0, селектор переключится на капчер/бойца (армия раньше расширения).
            var df = if (forwardEnabled && us.count(RoleClass.FORWARD_MINER) == 0 && !us.hasForwardBase && ecoSeeded) 550.0 else 0.0
            if (posture == Posture.DEFEND || enemyNearFwd || them.power > us.power || !routeClear) df = 0.0
            add(FORWARD_MINER_BODY, FORWARD_MINER_COST, avoidDock, df)
            // GUARD — упреждающий пост у вражеской форвард-базы (низкий приоритет)
            var dg = if (guardEnabled && us.count(RoleClass.GUARD) == 0 && ecoSeeded && us.power >= them.power) 150.0 else 0.0
            if (posture == Posture.DEFEND) dg = 0.0
            add(GUARD_BODY, GUARD_COST, avoidDock, dg)
        }
        // HARASSER — мобильный эко-юнит (дешёвый стоп-гэп армии + защита форварда + рейд)
        var dha = 0.0
        if (enemyNearFwd && us.hasForwardBase && us.count(RoleClass.HARASSER) < DESIRED_FWD_HARASSERS) dha += W_H_DEFEND_FWD
        if (posture == Posture.RAID && us.count(RoleClass.HARASSER) < MAX_HARASSERS) dha += W_H_RAID
        dha += W_H_ARMYGAP * minOf(armyGap, ARMYGAP_CAP)
        dha *= when (posture) { Posture.RAID -> 1.5; Posture.DEFEND -> 1.3; else -> 1.0 }
        add(HARASSER_BODY, HARASSER_COST, avoidDock, dha)
        // FIGHTER — основная армия (после экономики; масштаб от разрыва армий и постуры)
        var dfi = 0.0
        if (ecoSeeded) {
            dfi = W_FIGHTER_BASE + W_FIGHTER_ARMYGAP * armyGap
            if (posture == Posture.PUSH) dfi += 200.0
            if (posture == Posture.DEFEND && intruders.isNotEmpty()) dfi += 150.0
        }
        add(FIGHTER_BODY, FIGHTER_COST, avoidDock, dfi)

        if (cands.isEmpty()) return null
        val best = cands.maxByOrNull { it.desire }!!
        val affBest = cands.filter { it.cost <= energy }.maxByOrNull { it.desire }
        // лучший недоступен и заметно желаннее доступного — копим (null); иначе тратим доступный максимум
        if (best.cost > energy && (affBest == null || best.desire > affBest.desire * SAVE_MARGIN)) return null
        return affBest
    }

    /** Спавн с заданием направлений выхода: ставим directions (если непустые), затем спавним. */
    private fun spawnWith(spawn: StructureSpawn, body: Array<BodyPartType>, dirs: Array<DirectionConstant>?) {
        if (dirs != null && dirs.isNotEmpty()) spawn.setDirections(dirs)
        spawn.spawnCreep(body)
    }

    /**
     * Копатель: стоит вплотную к ближайшему источнику и harvest каждый тик. Энергию из CARRY
     * отдаёт хаулеру, стоящему рядом (передача крип→крип — это transfer со стороны отдающего;
     * withdraw в API работает только со структур). Bootstrap-режим: пока хаулеров НЕТ и CARRY
     * полон — относит энергию в спавн сам (иначе стартовая добыча пропадала бы в полный буфер).
     */
    private fun runMiner(creep: Creep, spot: Position, sources: Array<Source>, haulers: List<Creep>, spawn: StructureSpawn) {
        val full = (creep.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) == 0
        if (haulers.isEmpty() && full) {
            // нет хаулеров и буфер полон — сами относим в спавн (только bootstrap)
            if (creep.getRangeTo(spawn) > 1) creep.moveTo(spawn) else creep.transfer(spawn, RESOURCE_ENERGY)
            return
        }
        // встаём ровно на свою фиксированную клетку (она — сосед источника, range 1 для harvest)
        if (creep.getRangeTo(spot) > 0) {
            creep.moveTo(spot)
            return
        }
        val source = creep.findClosestByRange(sources) ?: return
        creep.harvest(source)
        // КАЖДЫЙ тик пытаемся отдать всё в хаулера вплотную (не отрываясь от источника).
        if ((creep.store[RESOURCE_ENERGY] ?: 0) > 0) {
            val hauler = haulers.firstOrNull { creep.getRangeTo(it) <= 1 }
            if (hauler != null) creep.transfer(hauler, RESOURCE_ENERGY)
        }
    }

    /**
     * Форвард-майнер у второго источника: строит укреплённую мини-базу и кормит её. Стоит на своей
     * клетке (на рампарте), достаёт источник и обе соседние клетки (range 1). Порядок построек —
     * сначала защита себя, потом башня, потом спавн (каждая структура — ПОД рампартом):
     *   1) рампарт на своей клетке (fwdMiner) — урон по майнеру уходит в рампарт;
     *   2) рампарт на клетке башни (tower);
     *   3) башня на клетке башни (внутри рампарта);
     *   4) рампарт на клетке спавна;
     *   5) спавн на клетке спавна (внутри рампарта).
     * Приоритет в каждый тик: зарядить башню, если она построена и не полна (оборона) → достроить
     * следующую структуру по порядку → кормить готовый спавн. harvest/build — конфликтующие
     * action-интенты (чередуем, копим на ПОЛНЫЙ build); harvest+transfer — совместимы (заряд/кормёжка).
     */
    private fun runForwardMiner(creep: Creep, sources: Array<Source>, forwardSpawn: StructureSpawn?, danger: CostMatrix, homeSpawn: StructureSpawn, enemyCreeps: List<Creep>) {
        fun rampartAt(x: Int, y: Int) =
            getObjectsByPrototype(StructureRampart::class).any { it.my == true && it.x == x && it.y == y }

        val onRampart = rampartAt(creep.x, creep.y)
        val threatened = threatenedBy(creep, enemyCreeps)
        val rangeToSpot = creep.getRangeTo(cell(fwdMinerX, fwdMinerY))

        // под рампартом крип ЗАЩИЩЁН — не отступает (иначе бросит пост и вылезет под огонь); отступает
        // к ДОМУ, только если открыт (рампарта на клетке ещё/уже нет) и враг рядом.
        if (threatened && !onRampart) {
            if (DEBUG_LOG) println("fmnr ${creep.id} RETREAT pos=(${creep.x},${creep.y}) fat=${creep.fatigue} range2spot=$rangeToSpot enemies=${enemyCreeps.size}")
            retreatHome(creep, danger, homeSpawn); return
        }

        if (rangeToSpot > 0) {
            // едем к точке в обход огня/башен. Логируем ВСЁ, что влияет на шаг: позицию, усталость
            // (на болоте тяжёлое тело копит fatigue и стоит по нескольку тиков!), длину найденного
            // пути, сам шаг и стоимость danger-матрицы здесь/в следующей клетке — чтобы понять застой.
            val path = searchPath(creep, SearchGoal(pos = cell(fwdMinerX, fwdMinerY), range = 0), SearchPathOptions(costMatrix = danger)).path
            val step = path.firstOrNull()
            if (DEBUG_LOG) println(
                "fmnr ${creep.id} TRAVEL pos=(${creep.x},${creep.y}) spot=($fwdMinerX,$fwdMinerY) range=$rangeToSpot fat=${creep.fatigue} " +
                    "threat=$threatened onRamp=$onRampart pathLen=${path.size} step=${step?.let { "(${it.x},${it.y})" } ?: "NONE"} " +
                    "dangerHere=${danger.get(creep.x, creep.y)} dangerNext=${step?.let { danger.get(it.x, it.y) } ?: -1}"
            )
            if (step != null) {
                creep.move(getDirection(step.x - creep.x, step.y - creep.y))
            } else {
                // путь к точке вообще не найден — отступаем к дому, не стоим под убоем
                if (!moveAvoiding(creep, cell(homeSpawn.x, homeSpawn.y), danger)) creep.moveTo(homeSpawn)
            }
            return
        }
        val source = sources.firstOrNull { it.x == secondSrcX && it.y == secondSrcY }
            ?: creep.findClosestByRange(sources) ?: return

        val tower = getObjectsByPrototype(StructureTower::class).firstOrNull { it.my == true && it.x == towerX && it.y == towerY }
        val hasTowerSlot = towerX >= 0

        // 1) ОБОРОНА: башня построена и не полна — заряжаем её (harvest + transfer в один тик).
        if (tower != null && (tower.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0) {
            creep.harvest(source)
            if ((creep.store[RESOURCE_ENERGY] ?: 0) > 0) creep.transfer(tower, RESOURCE_ENERGY)
            return
        }

        // 2) стройка по порядку: первый невыполненный шаг (пополнение — harvest источника)
        val refill = { creep.harvest(source); Unit }
        when {
            !rampartAt(fwdMinerX, fwdMinerY) -> buildStep(creep, fwdMinerX, fwdMinerY, { createConstructionSite(fwdMinerX, fwdMinerY, StructureRampart::class.js) }, refill)
            hasTowerSlot && !rampartAt(towerX, towerY) -> buildStep(creep, towerX, towerY, { createConstructionSite(towerX, towerY, StructureRampart::class.js) }, refill)
            hasTowerSlot && tower == null -> buildStep(creep, towerX, towerY, { createConstructionSite(towerX, towerY, StructureTower::class.js) }, refill)
            !rampartAt(fwdSpawnX, fwdSpawnY) -> buildStep(creep, fwdSpawnX, fwdSpawnY, { createConstructionSite(fwdSpawnX, fwdSpawnY, StructureRampart::class.js) }, refill)
            forwardSpawn == null -> buildStep(creep, fwdSpawnX, fwdSpawnY, { createConstructionSite(fwdSpawnX, fwdSpawnY, StructureSpawn::class.js) }, refill)
            // 3) всё построено, башня полна — кормим форвард-спавн (harvest + transfer)
            else -> {
                creep.harvest(source)
                if (forwardSpawn != null && (creep.store[RESOURCE_ENERGY] ?: 0) > 0) creep.transfer(forwardSpawn, RESOURCE_ENERGY)
            }
        }
    }

    /**
     * Шаг стройки на клетке (cx,cy): если есть наша стройплощадка — строим её, копя энергию до
     * ПОЛНОГО действия build (иначе WORK простаивают); если площадки ещё нет — ставим её (create).
     * refill — как пополнять энергию (harvest источника / withdraw из контейнера). build не
     * совмещается с harvest, поэтому когда не строим — пополняемся.
     */
    private fun buildStep(creep: Creep, cx: Int, cy: Int, create: () -> Unit, refill: () -> Unit) {
        val site = getObjectsByPrototype(ConstructionSite::class).firstOrNull { it.my == true && it.x == cx && it.y == cy }
        if (site == null) {
            create() // площадки нет — заказываем (повторный create на существующую вернёт error, безвреден)
            refill()
            return
        }
        val fullBuild = creep.body.count { it.type == WORK && it.hits > 0 } * BUILD_POWER
        val carried = creep.store[RESOURCE_ENERGY] ?: 0
        val free = creep.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0
        if (carried >= fullBuild || free == 0) creep.build(site) else refill()
    }

    /**
     * Guard у вражеской форвард-базы: встаёт на контейнер прохода, строит рампарт на себе → рампарт
     * + башню на соседе (ближе к врагу) и кормит башню ИЗ КОНТЕЙНЕРА (withdraw, не harvest). Башня
     * простреливает проход (стрельбу ведёт общий tower-цикл в tick). Энергия для стройки/зарядки —
     * withdraw из контейнера (контейнер — Structure, withdraw разрешён).
     */
    private fun runGuard(creep: Creep, danger: CostMatrix) {
        if (creep.getRangeTo(cell(guardX, guardY)) > 0) {
            moveAvoiding(creep, cell(guardX, guardY), danger) // через всю карту к вражескому проходу — в обход огня
            return
        }
        val container = getObjectsByPrototype(StructureContainer::class).firstOrNull { it.x == guardX && it.y == guardY }
        val tower = getObjectsByPrototype(StructureTower::class).firstOrNull { it.my == true && it.x == guardTowerX && it.y == guardTowerY }
        fun rampartAt(x: Int, y: Int) = getObjectsByPrototype(StructureRampart::class).any { it.my == true && it.x == x && it.y == y }
        val refill = { if (container != null) { creep.withdraw(container, RESOURCE_ENERGY); Unit } else Unit }

        // 1) башня построена и не полна → кормим из контейнера (transfer/withdraw чередуем — оба resource-интенты)
        if (tower != null && (tower.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0) {
            if ((creep.store[RESOURCE_ENERGY] ?: 0) > 0) creep.transfer(tower, RESOURCE_ENERGY)
            else if (container != null) creep.withdraw(container, RESOURCE_ENERGY)
            return
        }
        // 2) стройка по порядку: рампарт на себе → рампарт на клетке башни → башня
        when {
            !rampartAt(guardX, guardY) -> buildStep(creep, guardX, guardY, { createConstructionSite(guardX, guardY, StructureRampart::class.js) }, refill)
            !rampartAt(guardTowerX, guardTowerY) -> buildStep(creep, guardTowerX, guardTowerY, { createConstructionSite(guardTowerX, guardTowerY, StructureRampart::class.js) }, refill)
            tower == null -> buildStep(creep, guardTowerX, guardTowerY, { createConstructionSite(guardTowerX, guardTowerY, StructureTower::class.js) }, refill)
            // 3) всё построено, башня полна — держим CARRY заполненным (резерв на дозарядку при бое)
            else -> if ((creep.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0 && container != null) creep.withdraw(container, RESOURCE_ENERGY)
        }
    }

    /**
     * Хаулер — чистый челнок спавн⇄копатели без простоя. Направление держим явной фазой, а не
     * грузом: в момент ПРИБЫТИЯ на конец переключаемся на противоположный и тем же тиком едем
     * туда. Передача регистрируется по позиции на начало тика, поэтому:
     *  - прибыл на клетку сбора → майнеры в ЭТОТ тик кладут энергию (transfer), а хаулер уже едет к спавну;
     *  - прибыл к спавну → transfer в спавн + move назад, в ОДИН тик.
     * При плече в 1 шаг получается ровно 2-тиковый цикл (как и задумано).
     */
    private fun runHauler(creep: Creep, miners: List<Creep>, spawn: StructureSpawn) {
        val stand = haulerStand(miners, spawn)
        val atSpawn = creep.getRangeTo(spawn) <= 1
        val atStand = stand != null && creep.getRangeTo(stand) == 0

        // переключаем фазу при достижении конца маршрута
        var phase = haulerPhase[creep.id] ?: HaulerPhase.TO_STAND
        if (atSpawn) phase = HaulerPhase.TO_STAND
        else if (atStand) phase = HaulerPhase.TO_SPAWN
        haulerPhase[creep.id] = phase

        if (atSpawn) {
            creep.transfer(spawn, RESOURCE_ENERGY) // у спавна всегда отдаём (пустой — no-op)
            // запоминаем клетку стыковки, чтобы новые крипы тут не рождались (блокируя разгрузку)
            if (creep.x != spawn.x || creep.y != spawn.y) dockDirs.add(getDirection(creep.x - spawn.x, creep.y - spawn.y))
        }
        when (phase) {
            HaulerPhase.TO_SPAWN -> creep.moveTo(spawn)
            HaulerPhase.TO_STAND -> if (stand != null) creep.moveTo(stand)
        }
    }

    /**
     * Клетка СТОЯНКИ хаулера — свободная проходимая клетка, на которую он реально встанет (range 1
     * к копателям, чтобы те наполняли его), НЕ занятая копателем/источником. Среди подходящих —
     * ближайшая к спавну (короче плечо челнока). При двух копателях требуем смежность ОБОИМ
     * (оба наполняют сразу); если такой нет — смежную хотя бы одному.
     */
    private fun haulerStand(miners: List<Creep>, spawn: StructureSpawn): Position? {
        if (miners.isEmpty()) return null
        val m1 = miners[0]
        val m2 = miners.getOrNull(1)

        fun usable(x: Int, y: Int): Boolean {
            if (x < 0 || y < 0 || x > 99 || y > 99) return false
            if (getTerrainAt(cell(x, y)) == TERRAIN_WALL) return false
            if (x == sourceX && y == sourceY) return false // на источнике стоять нельзя
            if (x == m1.x && y == m1.y) return false        // занято копателем
            if (m2 != null && x == m2.x && y == m2.y) return false
            return true
        }

        // клетки в range 1 от m1 (и от m2, если он есть) — ближайшую к спавну
        var best: Int = -1
        var bestDist = Int.MAX_VALUE
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val x = m1.x + dx; val y = m1.y + dy
                if (!usable(x, y)) continue
                if (m2 != null && maxOf(abs(x - m2.x), abs(y - m2.y)) > 1) continue // нужна смежность обоим
                val d = maxOf(abs(x - spawn.x), abs(y - spawn.y))
                if (d < bestDist) { bestDist = d; best = x * 100 + y }
            }
        }
        // двух-смежной нет — берём смежную хотя бы первому копателю (тоже ближайшую к спавну)
        if (best < 0) {
            for (dx in -1..1) {
                for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val x = m1.x + dx; val y = m1.y + dy
                    if (!usable(x, y)) continue
                    val d = maxOf(abs(x - spawn.x), abs(y - spawn.y))
                    if (d < bestDist) { bestDist = d; best = x * 100 + y }
                }
            }
        }
        return if (best >= 0) cell(best / 100, best % 100) else null
    }

    /** Общая цель фокуса на тик: лекарь (макс HEAL) — приоритет, гасит наш урон; затем «убиваемый
     *  ЗА ЭТОТ ТИК» (hits ≤ суммарный доступный наш урон); затем ОППОРТУНИСТИЧЕСКИ открытый вражеский
     *  ЭКО-крип по стоимости (форвард-майнер 950 ≫ хаулер 100 — добиваем чужую экономику, НЕ бросая
     *  реальный бой: для боевых целей терм = 0); затем самый раненый. Только из достижимых стрельбой. */
    private fun fighterFocus(fighters: List<Creep>, enemyCreeps: List<Creep>): Creep? {
        val inRange = enemyCreeps.filter { e -> fighters.any { it.getRangeTo(e) <= RANGED_RANGE } }
        if (inRange.isEmpty()) return null
        val mult = if (bonusCaptured) 2 else 1 // наш захваченный RANGED-бонус удваивает урон
        fun availDamage(e: Creep) = fighters.filter { it.getRangeTo(e) <= RANGED_RANGE }
            .sumOf { it.body.count { p -> p.type == RANGED_ATTACK && p.hits > 0 } } * RANGED_ATTACK_POWER * mult
        val enemyCombat = enemyCreeps.filter { InfluenceMap.profileOf(it).let { p -> p.melee + p.ranged > 0.0 } }
        // открытый эко-крип (не боевой, без эскорта) → по стоимости; иначе 0 (не влияет на боевой фокус)
        fun ecoScore(e: Creep): Int {
            if (InfluenceMap.profileOf(e).let { p -> p.melee + p.ranged > 0.0 }) return 0
            if (enemyCombat.any { getRange(it, e) <= ECO_ESCORT_R }) return 0
            return creepCost(e)
        }
        return inRange.minWithOrNull(
            compareByDescending<Creep> { it.body.count { p -> p.type == HEAL && p.hits > 0 } }
                .thenBy { if (it.hits <= availDamage(it)) 0 else 1 }
                .thenByDescending { ecoScore(it) }
                .thenBy { it.hits }
        )
    }

    /**
     * Действие бойца за тик: лечение и/или стрельба (HEAL-часть). heal В УПОР (range 1) — отдельный
     * пайплайн, совмещается со стрельбой → лечим И стреляем. rangedHeal (range 2-3) конфликтует со
     * стрельбой → лечим вместо выстрела. Цель лечения — самый раненый союзник (включая себя).
     * Нет раненых рядом — просто стреляем.
     */
    private fun fighterCombat(creep: Creep, allies: List<Creep>, enemyCreeps: List<Creep>, enemySpawn: StructureSpawn?, focus: Creep?, breachWall: StructureWall?, blockRampart: StructureRampart?, storm: Boolean, healDone: HashMap<String, Int>, incoming: HashMap<String, Int>) {
        val healParts = creep.body.count { it.type == HEAL && it.hits > 0 }
        if (healParts > 0) {
            // потребность цели = дефицит HP + ОЖИДАЕМЫЙ входящий урон − уже вложенный хил (пре-хил:
            // даже полный крип под огнём лечится, т.к. урон и хил применяются одновременно).
            fun need(t: Creep): Int {
                val deficit = t.hitsMax - t.hits
                val expected = incoming.getOrPut(t.id) { InfluenceMap.damageAt(t.x, t.y, enemyCreeps).toInt() }
                return deficit + expected - (healDone[t.id] ?: 0)
            }
            val candidates = allies.filter { need(it) > 0 && creep.getRangeTo(it) <= RANGED_RANGE }
            // сначала кого достаём ВПЛОТНУЮ (heal range 1 совместим со стрельбой → лечим И стреляем)
            val close = candidates.filter { creep.getRangeTo(it) <= 1 }.maxByOrNull { need(it) }
            if (close != null) {
                creep.heal(close)
                healDone[close.id] = (healDone[close.id] ?: 0) + healParts * HEAL_POWER
                fighterShoot(creep, enemyCreeps, enemySpawn, focus, breachWall, blockRampart, storm)
                return
            }
            // rangedHeal конфликтует со стрельбой → меняем выстрел на хил 4/часть только при РЕАЛЬНОМ
            // дефиците HP (не ради пре-хила: чистый пре-хил полному крипу — потеря DPS)
            val far = candidates.filter { it.hitsMax - it.hits > 0 }.maxByOrNull { need(it) }
            if (far != null) {
                creep.rangedHeal(far)
                healDone[far.id] = (healDone[far.id] ?: 0) + healParts * RANGED_HEAL_POWER
                return
            }
        }
        fighterShoot(creep, enemyCreeps, enemySpawn, focus, breachWall, blockRampart, storm)
    }

    /** Стрельба бойца. Врагов в радиусе нет — ломаем ПРЕГРАДУ на пути (рампарт → стена-ворота → спавн).
     *  Есть враги: УМНАЯ массовая (если суммарный урон по дистанции-rate {1:1,2:0.4,3:0.1} больше
     *  полного одиночного выстрела 1.0), иначе одиночный — штурм→спавн, фокус, самый раненый лекарь. */
    private fun fighterShoot(creep: Creep, enemyCreeps: List<Creep>, enemySpawn: StructureSpawn?, focus: Creep?, breachWall: StructureWall?, blockRampart: StructureRampart?, storm: Boolean) {
        val creepsInRange = enemyCreeps.filter { creep.getRangeTo(it) <= RANGED_RANGE }
        val spawnInRange = enemySpawn != null && creep.getRangeTo(enemySpawn) <= RANGED_RANGE
        if (creepsInRange.isEmpty()) {
            // вражеских крипов рядом нет — разрушаем преграду на пути, иначе бьём спавн
            if (blockRampart != null) { creep.rangedAttack(blockRampart); return }
            if (breachWall != null && creep.getRangeTo(breachWall) <= RANGED_RANGE) { creep.rangedAttack(breachWall); return }
            if (spawnInRange) creep.rangedAttack(enemySpawn!!)
            return
        }
        var massValue = creepsInRange.sumOf { InfluenceMap.rangedRate(creep.getRangeTo(it)) }
        if (spawnInRange) massValue += InfluenceMap.rangedRate(creep.getRangeTo(enemySpawn!!))
        if (massValue > 1.0) { creep.rangedMassAttack(); return } // массовая выгоднее полного одиночного
        val target = when {
            storm && spawnInRange -> enemySpawn // штурм: победа = снос спавна, не распыляемся
            focus != null && creep.getRangeTo(focus) <= RANGED_RANGE -> focus
            else -> creepsInRange.minWithOrNull(
                compareByDescending<Creep> { InfluenceMap.profileOf(it).heal }.thenBy { it.hits }
            )
        }
        target?.let { creep.rangedAttack(it) }
    }

    /** Вражеский рампарт «на пути вперёд»: в радиусе стрельбы и БЛИЖЕ к цели, чем сам боец (лежит
     *  между ним и целью). Зайти на него нельзя — ломаем, чтобы продвинуться. Берём ближайший. */
    private fun forwardRampart(creep: Creep, target: Position, enemyRamparts: List<StructureRampart>): StructureRampart? {
        val myDist = creep.getRangeTo(target)
        return enemyRamparts
            .filter { creep.getRangeTo(it) <= RANGED_RANGE && getRange(cell(it.x, it.y), target) < myDist }
            .minByOrNull { creep.getRangeTo(it) }
    }

    /** Цель эко-рейда (balanced): самый дорогой ОТКРЫТЫЙ (без эскорта) вражеский эко-крип, достижимый и
     *  НЕ у самой вражеской базы (не дайвим). Ценность = creepCost − дистанция. null — некого/опасно. */
    private fun economyHuntTarget(hunter: Creep, enemyEco: List<Creep>, enemyCombat: List<Creep>, enemySpawn: StructureSpawn?): Creep? =
        enemyEco.filter { e ->
            enemyCombat.none { getRange(it, e) <= ECO_ESCORT_R } &&                       // открыт (нет эскорта)
                DistanceMap.stepsFromMy(e.x, e.y) < Int.MAX_VALUE &&                       // достижим
                (enemySpawn == null || getRange(e, enemySpawn) > RAID_BASE_KEEPOUT)        // не у их базы
        }.maxByOrNull { creepCost(it).toDouble() - hunter.getRangeTo(it) * HUNT_DIST_W }

    /** Эффективный исходящий урон ranged-бойца с клетки (в долях мощи): сколько врагов и насколько достаёт. */
    private fun outgoingValue(x: Int, y: Int, enemyCreeps: List<Creep>): Double {
        var massValue = 0.0
        var anyInRange = false
        for (e in enemyCreeps) {
            val d = getRange(cell(x, y), e)
            if (d <= RANGED_RANGE) { anyInRange = true; massValue += InfluenceMap.rangedRate(d) }
        }
        if (!anyInRange) return 0.0
        return maxOf(massValue, 1.0)
    }

    /** Клетка проходима для бойца: в границах, не стена, без вражеского крипа. */
    private fun passableFighter(x: Int, y: Int, blockedSet: Set<Int>, enemyPositions: Set<Int>): Boolean {
        if (x < 0 || y < 0 || x > 99 || y > 99) return false
        val key = x * 100 + y
        if (key in blockedSet || key in enemyPositions) return false
        return getTerrainAt(cell(x, y)) != TERRAIN_WALL
    }

    /**
     * Оценка клетки для бойца (адаптация scoreCell из spawn-strike под одиночек). Наступление: подойти
     * на standoff к цели (по реальному пути через flow; «прибыли» — по Чебышеву, стрельба сквозь стены),
     * максимум исходящего урона (огневая позиция), минимум входящего, держать дистанцию от мили,
     * рампарт-бонус, штраф болота (кайт невозможен) и скученности (веер). mustFlee: прочь от урона.
     */
    private fun scoreCell(x: Int, y: Int, target: Position, flow: IntArray, standoff: Int, mustFlee: Boolean, aggressive: Boolean, inCombat: Boolean, breaching: Boolean, enemyCreeps: List<Creep>, allies: List<Creep>, meleeEnemies: List<Creep>, cluster: Boolean = false): Double {
        val flowDist = flow[x * 100 + y]
        val cheb = getRange(cell(x, y), target)
        val firePenalty = when {
            cheb <= standoff -> (standoff - cheb) * 0.5
            // цель НЕдостижима по пути (узкий коридор перекрыт стеной/рампартом): подходим по ЧЕБЫШЕВУ
            // (даёт градиент сближения) — выстрел идёт сквозь стену, а блокирующий рампарт сломаем в
            // упор. Иначе плоский штраф 1000 убивал стимул, и сильная группа застывала в коридоре.
            flowDist < 0 -> (cheb - standoff).toDouble() + 100.0 // +100 хуже достижимого пути, но С градиентом
            flowDist > standoff -> (flowDist - standoff).toDouble()
            else -> (standoff - flowDist) * 0.5
        }
        val separation = allies.count { (it.x != x || it.y != y) && getRange(cell(x, y), it) <= SEPARATION_RADIUS } * PAIR_W_SPREAD
        // СТРАЖИ у станции (cluster): держим дистанцию стрельбы standoff(3) до охраняемой клетки И стоим
        // ПЛОТНО — награда за каждого своего в радиусе ближнего хила (1). Так стреляем по клетке, но не
        // лезем вплотную (мили), и можем лечить друг друга в упор.
        if (cluster && !mustFlee && !inCombat) {
            val cohesion = allies.count { (it.x != x || it.y != y) && getRange(cell(x, y), it) <= 1 } * PAIR_W_COHESION
            return -firePenalty * PAIR_W_DIST + cohesion
        }
        // вне боя — чистый марш по полю потока + интервал (мягкие термы тянут крипа к куче)
        if (!mustFlee && !inCombat) return -firePenalty * PAIR_W_DIST - separation

        val onRampart = x * 100 + y in myRampartCells
        val damage = InfluenceMap.netDamageAt(x, y, enemyCreeps, allies)
        // при перевесе (ADVANCE) смягчаем штраф за входящий урон — иначе боец не шагает в «мёртвую зону»
        // (под огнём, но ещё не в радиусе стрельбы), и сильная группа застывает, не закрывая дистанцию.
        val damageWeight = if (aggressive) PAIR_W_DAMAGE * AGGRO_MELEE_FACTOR else PAIR_W_DAMAGE
        val meleeWeight = if (aggressive) PAIR_W_MELEE * AGGRO_MELEE_FACTOR else PAIR_W_MELEE
        val meleeThreat = if (onRampart) 0.0 else meleeEnemies.count { getRange(cell(x, y), it) <= MELEE_KEEP_RANGE } * meleeWeight
        val rampartBonus = if (onRampart) PAIR_W_RAMPART else 0.0
        val breach = breaching && !mustFlee
        val swampPenalty = if (!onRampart && !breach && DistanceMap.isSwamp(x, y)) PAIR_W_SWAMP else 0.0
        if (mustFlee) {
            val proximity = enemyCreeps.sumOf { 1.0 / (getRange(cell(x, y), it) + 1) }
            // ТЯГА к сильнейшей зоне (target при побеге = наша сильнейшая зона): среди безопасных клеток
            // бежим К поддержке армии, а не в случайный тихий угол. flowDist — путь до зоны (болото взвешено).
            val homePull = (if (flowDist >= 0) flowDist.toDouble() else cheb + 100.0) * PAIR_W_FLEE_PULL
            return -damage * 10.0 - proximity * 5.0 - meleeThreat + rampartBonus - swampPenalty - homePull
        }
        // influence НЕ учитываем при наступлении (aggressive): он создаёт «обрыв» на границе радиуса
        // влияния врага (d>4: врага нет в сумме → высоко; шаг вперёд → враг входит → обвал), и боец
        // застывал ровно вне дистанции стрельбы. Мы уже решили (стойка ADVANCE), что сильнее — идём.
        val influenceTerm = if (aggressive) 0.0 else InfluenceMap.influenceAt(x, y, allies, enemyCreeps) * PAIR_W_INFLUENCE
        val outgoingWeight = if (breach) PAIR_W_OUTGOING_BREACH else PAIR_W_OUTGOING
        val outgoing = outgoingValue(x, y, enemyCreeps)
        return -firePenalty * PAIR_W_DIST - damage * damageWeight + influenceTerm +
            outgoing * outgoingWeight - meleeThreat - separation + rampartBonus - swampPenalty
    }

    /**
     * Лучший ход бойца по оценке 9 клеток (исходящий урон, дистанция к цели по flow, входящий урон,
     * влияние, мили/болото-штрафы, веер). Если свободного улучшающего хода нет, но занятая своим
     * клетка строго ближе к цели — запрашиваем её (TrafficManager протолкнёт цепочкой/свапом).
     */
    private fun bestFighterMove(creep: Creep, target: Position, flow: IntArray, standoff: Int, mustFlee: Boolean, aggressive: Boolean, inCombat: Boolean, breaching: Boolean, enemyCreeps: List<Creep>, allies: List<Creep>, meleeEnemies: List<Creep>, blockedSet: Set<Int>, enemyPositions: Set<Int>, occupied: Set<Int>, cluster: Boolean = false): Position? {
        var bestScore = scoreCell(creep.x, creep.y, target, flow, standoff, mustFlee, aggressive, inCombat, breaching, enemyCreeps, allies, meleeEnemies, cluster)
        var bx = creep.x; var by = creep.y
        val hereDist = flow[creep.x * 100 + creep.y]
        var pushDist = if (hereDist >= 0) hereDist else Int.MAX_VALUE
        var pushX = -1; var pushY = -1
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            val x = creep.x + dx; val y = creep.y + dy
            if (!passableFighter(x, y, blockedSet, enemyPositions)) continue
            // НЕ заходим под башню: если за TOWER_AVOID_TICKS она снесёт больше HP, чем крип получит
            // лечением (своё + соседей, healAt лечит сквозь стены) — клетка запретна (кроме побега).
            if (!mustFlee) {
                val towerDmg = InfluenceMap.enemyTowerDamageAt(x, y)
                if (towerDmg > 0.0 && towerDmg * TOWER_AVOID_TICKS > InfluenceMap.healAt(x, y, allies) * TOWER_AVOID_TICKS) continue
            }
            if (x * 100 + y in occupied) { // клетка своего — обтекаем (веер), но помним как толчок
                if (!mustFlee && !inCombat) {
                    val fd = flow[x * 100 + y]
                    if (fd in 0 until pushDist) { pushDist = fd; pushX = x; pushY = y }
                }
                continue
            }
            val s = scoreCell(x, y, target, flow, standoff, mustFlee, aggressive, inCombat, breaching, enemyCreeps, allies, meleeEnemies, cluster)
            if (s > bestScore) { bestScore = s; bx = x; by = y }
        }
        if (bx != creep.x || by != creep.y) return cell(bx, by)
        if (pushX >= 0) return cell(pushX, pushY)
        return null
    }

    /**
     * Печатает ASCII-карту поля один раз (terrain + структуры) для анализа геометрии. Символы:
     *  '#' камень (terrain wall, НЕпроходим), 'W' StructureWall (ЛОМАЕТСЯ — секретные проходы),
     *  '~' болото, '.' равнина, 'M' мой спавн, 'E' вражеский спавн, 'S' источник, 'c' контейнер,
     *  'a'/'r'/'h' bonus-флаг (attack/ranged/heal), '+' экстеншен, 'R' рампарт. Слева — номер строки y.
     *  Размер поля считаем 100x100 (стандарт арены); пустые края, если карта меньше, безвредны.
     */
    private fun logMap() {
        val marks = HashMap<Int, Char>()
        fun mark(x: Int, y: Int, c: Char) { marks[x * 100 + y] = c }

        getObjectsByPrototype(StructureWall::class).forEach { mark(it.x, it.y, 'W') }
        getObjectsByPrototype(StructureRampart::class).forEach { mark(it.x, it.y, 'R') }
        getObjectsByPrototype(StructureExtension::class).forEach { mark(it.x, it.y, '+') }
        getObjectsByPrototype(StructureContainer::class).forEach { mark(it.x, it.y, 'c') }
        getObjectsByPrototype(Source::class).forEach { mark(it.x, it.y, 'S') }
        getObjectsByPrototype(StructureSpawn::class).forEach { mark(it.x, it.y, if (it.my == true) 'M' else 'E') }
        getObjectsByPrototype(BonusFlag::class).forEach {
            mark(it.x, it.y, when (it.bonusType) {
                ATTACK -> 'a'
                RANGED_ATTACK -> 'r'
                HEAL -> 'h'
                else -> '?'
            })
        }

        println("=== POWER SPLIT MAP (rows y=0..99, cols x=0..99) ===")
        for (y in 0..99) {
            val row = StringBuilder()
            for (x in 0..99) {
                val structure = marks[x * 100 + y]
                row.append(
                    when {
                        structure != null -> structure
                        getTerrainAt(cell(x, y)) == TERRAIN_WALL -> '#'
                        getTerrainAt(cell(x, y)) == TERRAIN_SWAMP -> '~'
                        else -> '.'
                    }
                )
            }
            println("${y.toString().padStart(2, '0')}:$row")
        }
        println("=== END MAP ===")
    }
}
