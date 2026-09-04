package season4.escortrun

import screeps.api.ATTACK
import screeps.api.ATTACK_POWER
import screeps.api.BODYPART_COST
import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.CARRY_CAPACITY
import screeps.api.CREEP_SPAWN_TIME
import screeps.api.CostMatrix
import screeps.api.Creep
import screeps.api.EFF_HEAL_MODIFIER
import screeps.api.Flag
import screeps.api.GameObject
import screeps.api.HARVEST_POWER
import screeps.api.HEAL
import screeps.api.HEAL_POWER
import screeps.api.MAX_CREEP_SIZE
import screeps.api.MOVE
import screeps.api.Position
import screeps.api.RANGED_ATTACK
import screeps.api.RANGED_ATTACK_POWER
import screeps.api.RANGED_HEAL_POWER
import screeps.api.RESOURCE_ENERGY
import screeps.api.SPAWN_ENERGY_CAPACITY
import screeps.api.SearchGoal
import screeps.api.SearchPathOptions
import screeps.api.Source
import screeps.api.TOUGH
import screeps.api.WORK
import screeps.api.arenaInfo
import screeps.api.get
import screeps.api.getObjects
import screeps.api.getObjectsByPrototype
import screeps.api.getRange
import screeps.api.getTicks
import screeps.api.searchPath
import screeps.api.structures.StructureContainer
import screeps.api.structures.StructureExtension
import screeps.api.structures.StructureRampart
import screeps.api.structures.StructureSpawn
import screeps.api.structures.StructureTower
import screeps.api.structures.StructureWall
import screeps.api.value
import sourcemaps.runWithSourceMapSupport
import kotlin.math.abs
import kotlin.math.sqrt

@OptIn(ExperimentalJsExport::class)
@JsExport
fun loop() {
    try {
        runWithSourceMapSupport {
            EscortRun.tick()
        }
    } catch (t: Throwable) {
        // страховка: даже если source-map-обработчик упадёт, логируем ошибку и не роняем тик
        println("loop error: ${t.message}")
        println(t.stackTraceToString())
    }
}

/**
 * Season 4 «Escort Run» (basic). Первая версия — до первого живого матча, поэтому одновременно зонд: первый тик
 * печатает всё, что видно (спавны, источники, контейнеры, флаги, тела, прототипы), тики 3–6 — карту.
 *
 * Правила (описание арены в клиенте, 04.09.2026): у каждого игрока база со спавном и источником энергии и один
 * особый крип [EscortCreep] (5000 хитов, ходит клетку за 4 тика по равнине и много медленнее по болоту); флаг —
 * на противоположной стороне карты. **Победа — довести своего эскорта до флага раньше противника или убить
 * эскорта противника.** На дальней стороне — два источника и два контейнера. Два «секретных горных прохода»:
 * один к флагу, другой к базе противника, оба мимо центра. Лимит 2000 тиков, потом ничья.
 *
 * Оси решения, все из состояния, не из подгонки под карту:
 *  - эскорт идёт к флагу по полю потока с ценой болота ИЗ СВОЕГО ТЕЛА (период на болоте / период на равнине) и не
 *    ждёт никого: он медленнее всех, конвой его догоняет, а стоять — терять гонку;
 *  - спавн: сначала добытчик (источник у базы — единственный рост дохода, окупается за десятки тиков), если только
 *    первый заказ врага не доходит до нашего эскорта раньше, чем боец через добытчика; дальше конвой — стрелки и
 *    лекари (лекарь — каждый третий), тела полные по бюджету спавна, урезанные — только под угрозой, до которой
 *    полное не успевает;
 *  - конвой: стрелки держатся у эскорта между ним и ближайшей угрозой (бонус за прикрытие), лекари вплотную к
 *    самому битому подопечному (эскорт — по умолчанию); локальный перевес по Ланчестеру решает, наступать или
 *    держаться под лечением; удар — сначала по убиваемому за тик, потом по угрозе на хит, потом по эскорту врага;
 *  - удар по эскорту врага (strike) — расчёт: дорога + бой с его охраной + 5000 хитов под его лечением должны
 *    уложиться и до его прихода на флаг, и до смерти нашего эскорта под теми, кто у него сейчас; иначе конвой
 *    остаётся дома. Гистерезис: начатый удар продолжается, пока наш эскорт доживает до конца удара.
 */
object EscortRun {

    // ---------- боевые константы ----------
    private const val RANGED_RANGE = 3
    private const val HEAL_RANGE = 3
    private const val MELEE_KEEP_RANGE = 2
    private const val MELEE_KITE_DISCOUNT = 0.1

    /** Радиус, в котором боевой враг считается угрозой эскорту (по Чебышеву: враг ходит клетку в тик, эскорт — в четыре). */
    private const val THREAT_RANGE = 10

    /** Локальная группа: свои в 4 клетках, враги в 6 — как в Pain and Gain. */
    private const val LOCAL_ALLY_RANGE = 4
    private const val LOCAL_ENEMY_RANGE = 6

    /** Гистерезис локальной агрессии: вступаем при перевесе, отходим под лечение при явной слабости. */
    private const val AGGR_ENTER = 1.15
    private const val AGGR_EXIT = 0.85

    /** Удар по эскорту врага: наш отряд должен бить его охрану с таким перевесом, и уложиться с запасом тиков. */
    private const val STRIKE_RATIO = 1.3
    private const val STRIKE_MARGIN = 20

    /** Охрана эскорта врага — боевые враги в стольких клетках от него. */
    private const val GUARD_RANGE = 6

    /** Стрелок конвоя стоит в этом зазоре от эскорта; ARRIVED_SLACK — где уже «прибыл» и не дёргается. */
    private const val GUARD_STANDOFF = 1
    private const val ARRIVED_SLACK = 2

    /** Веса оценки клетки (Pain and Gain, там выверены живыми матчами). */
    private const val STAY_BIAS = 5.0
    private const val W_DIST = 10.0
    private const val W_DAMAGE = 0.3
    private const val W_INFLUENCE = 0.1
    private const val W_OUTGOING = 30.0
    private const val W_MELEE = 50.0
    private const val AGGRO_MELEE_FACTOR = 0.3
    private const val W_SPREAD = 4.0
    private const val W_SWAMP = 40.0
    private const val SEPARATION_RADIUS = 1
    /** Бонус клетке МЕЖДУ эскортом и ближайшей угрозой: стрелок принимает огонь и выстрел на себя. Меньше цены шага
     *  (W_DIST), чтобы прикрытие не уводило стрелка от эскорта. */
    private const val W_COVER = 6.0
    /** Заслон: мили врага в BLOCK_RANGE от эскорта — конвой занимает клетки ВПЛОТНУЮ к эскорту (у него восемь соседей,
     *  одного занимает тягач): мили бьёт только соседа, и пока соседи — наши, эскорт для него недостижим, а мили под
     *  нашим огнём рубит бойца с 1200 хитами вместо эскорта. M7A7 у эскорта — 210 в тик, 5000 хитов за 24 тика (стенд). */
    private const val BLOCK_RANGE = 6
    private const val W_BLOCK = 15.0
    /** Лекарь: цена фактического огня по клетке и запрет клетки вплотную к мили врага (Pain and Gain v10). */
    private const val HEALER_W_FIRE = 0.02
    private const val HEALER_W_MELEE = 25.0

    /** Бегство бойца — только когда его добивают: меньше трети хитов и следующий тик снимает больше половины остатка. */
    private const val FLEE_HITS_SHARE = 0.34

    /** Приоритеты движения: эскорт толкает всех — его шаг стоит четыре тика, чужой — один; тягач, идущий на своё
     *  место в поезде, — следом; уступающий поезду дорогу — выше бойцов. */
    private const val ESCORT_PRIORITY = 100
    private const val YIELD_PRIORITY = 95
    private const val PULLER_PRIORITY = 90
    private const val HEALER_PRIORITY = 30
    private const val FIGHTER_PRIORITY = 20
    private const val HARVESTER_PRIORITY = 10
    private const val HUSK_PRIORITY = 0

    // ---------- поезд: буксировка эскорта ----------
    /** Буксировка (движок World, проверено по исходникам 04.09.2026; доки Arena описывают ту же схему): тягач подаёт
     *  pull(эскорт) и свой move, эскорт — move В КЛЕТКУ тягача; усталость хода эскорта уходит ГОЛОВЕ цепи, а MOVE эскорта,
     *  пока своя усталость нулевая, гасят усталость головы. Период поезда = ceil(Σвес × ставка / (2 × ΣMOVE)): эскорт
     *  40 не-MOVE + 10 MOVE идёт 4/20 тиков на клетку, с тягачом 10 MOVE — 2/10, с двумя (ΣMOVE 40) — 1/5. Тягач из
     *  чистых MOVE весит ноль и за свой рельеф не платит. Замер в первом матче: коды pull/move печатаются в журнал. */
    private const val USE_PULL = true
    /** Длина цепи тягачей: голова + один промежуточный; дальше выигрыш периода не окупает роды. */
    private const val MAX_CHAIN = 2
    /** У самого флага поезд распускается: эскорт делает последние шаги сам, тягачи не занимают клетку флага. */
    private const val TRAIN_DISSOLVE_RANGE = 2
    /** Тягач меньше стольких MOVE не строится; больше двадцати не помещается в ёмкость спавна. */
    private const val PULLER_MIN_MOVE = 4
    private const val PULLER_MAX_MOVE = 20
    /** Тягач в стольких клетках от эскорта — в цепи: эскорт ждёт его прицепки (усталость сходит к нулю); дальше — идёт к
     *  хвосту цепи сам, а эскорт без близких тягачей идёт своим ходом. */
    private const val JOIN_RANGE = 4

    /** Сколько тиков подряд стоящий эскорт при собирающемся поезде считается неисправностью, а не расчётом. */
    private const val TRAIN_STALL_TICKS = 8

    // ---------- спавн ----------
    /** Меньше стольких WORK добытчик не строится: два MOVE+CARRY на один WORK — это ещё не добытчик. */
    private const val HARVESTER_MIN_WORK = 1
    /** Больше стольких WORK не даёт ничего, пока регенерация источника не измерена: 5 WORK = 10 в тик, столько же даёт
     *  источник Мира (3000 за 300 тиков); замер по журналу поправит. */
    private const val HARVESTER_MAX_WORK = 5
    /** Каждый третий боец конвоя — лекарь: два стрелка убивают то, что стреляет, лекарь возвращает снятое. */
    private const val HEALER_EVERY = 3
    /** Минимальный боец под угрозой: меньше двух RANGED — не боец. */
    private const val MIN_FIGHTER_RANGED = 2
    /** Окно наблюдения за сближением врага (тиков) и половина его — сколько ждать первый заказ врага. */
    private const val APPROACH_WINDOW = 20
    /** Окно наблюдения за эскортом врага: его темп по маршруту решает, есть ли у нас запас на экономику. Эскорт идёт
     *  клетку за 1–4 тика, так что за полсотни тиков едущий проходит не меньше дюжины клеток, а стоящий — ноль. */
    private const val ESCORT_WATCH = 50
    /** Сколько тиков ждать первого заказа врага, прежде чем тратить стартовую энергию: заказ первого тика виден на
     *  втором, так что дольше ждать нечего, а каждый тик ожидания — фора сопернику в гонке. */
    private const val ENEMY_OPENING_WAIT = 2

    // ---------- диагностика ----------
    /** Версия бота — печатается ПЕРВОЙ строкой матча вместе с подписью ключевых параметров. Клиент арены читает скрипт
     *  при старте матча, поэтому пересобранный бандл попадает в игру только со следующего запуска, и по логу должно
     *  быть видно, какая сборка играла: вопрос «в игре какая версия?» иначе не решается ничем. Поднимать при каждом
     *  выкате в main (тег escort-run-vN). */
    private const val BOT_VERSION = "v8"

    private const val DEBUG_LOG = true
    private const val DEBUG_MAP = true
    private const val LOG_EVERY = 10

    /** До какого тика печатается построчная трасса гонки (см. logRace): дебют решает матч, и мерить надо именно его. */
    private const val RACE_TRACE_TICKS = 120
    private const val BODIES_EVERY = 50

    private val DIRECTIONS = listOf(
        -1 to -1, 0 to -1, 1 to -1,
        -1 to 0, 1 to 0,
        -1 to 1, 0 to 1, 1 to 1,
    )

    // ---------- состояние между тиками ----------
    private var greeted = false
    private var mapMarks: HashMap<Int, Char>? = null

    /** id крипов, живых на ПЕРВОМ тике: за один тик родить никого нельзя, значит это эскорты обеих сторон. */
    private val escortIds = HashSet<String>()

    /** Замер регенерации спавна: приращение энергии за тик без переливов и без родов. */
    private var prevSpawnEnergy = -1
    private var transferredLastTick = false
    private var spawnedLastTick = false
    private val regenSamples = ArrayDeque<Int>()

    /** Замер источника: энергия по тикам — регенерацию видно по приросту при известной добыче. */
    private var prevSourceEnergy = -1
    private var harvestedLastTick = 0
    private val sourceRegenSamples = ArrayDeque<Int>()

    /** Первый увиденный заказ врага (тик) — дебют противника решает наш. */
    private var firstEnemySeenTick = -1

    /** Локальная агрессия с гистерезисом по крипу. */
    private val aggressiveIds = HashSet<String>()

    /** Удар по эскорту врага идёт (гистерезис решения). */
    private var striking = false
    private var strikeSince = -1

    /** Хиты каждого крипа на прошлом тике — для правила «добивают». */
    private val lastHits = HashMap<String, Int>()

    /** Сближение врагов с нашим эскортом: id -> (тик, дистанция по полю). */
    private val approachHistory = HashMap<String, ArrayDeque<Pair<Int, Int>>>()

    /** Поезд: кто в этом тике сдвинут прямыми move (мимо TrafficManager), клетки, которые остальным надо освободить. */
    private val trainMoved = HashSet<String>()
    private var escortStallTicks = 0
    private var escortStallKey = -1
    private var raceOurCell = -1
    private var raceTheirCell = -1
    private var raceOurSteps = 0
    private var raceTheirSteps = 0
    private var yieldCells: Set<Int> = emptySet()
    private var trainLogLeft = 60
    private var pullBypassTested = false
    private var trainRolling = false

    /** Проверка буксировки РЕЗУЛЬТАТОМ: после тика, когда поезд «ехал» (все коды 0), эскорт обязан стоять в клетке, где
     *  был его тягач; PULL_FAILS_MAX провалов подряд — буксировка в этой арене не работает: тягачей больше не строим,
     *  имеющиеся становятся заслоном у эскорта, эскорт идёт сам. Иначе провал был бы тихим: коды 0, а эскорт стоит. */
    private const val PULL_FAILS_MAX = 3
    private var pullBroken = false
    private var pullFails = 0
    private var expectedEscortCell = -1

    /** План дебюта живёт PLAN_EVERY тиков, пока энергии на его первый заказ не хватает (прогон — миллисекунды, но их
     *  десятки в тик при лимите 50 мс). */
    private const val PLAN_EVERY = 5
    private var planCache: Plan? = null
    private var planCacheTick = -1000

    /** Кэш полей потока: ключ -> (поле, тик постройки). */
    private val flowCache = HashMap<String, IntArray>()
    private val flowCacheTick = HashMap<String, Int>()
    private var bfsThisTick = 0
    private var bfsMaxTick = 0

    private class Ctx(
        val mySpawn: StructureSpawn?,
        val enemySpawn: StructureSpawn?,
        val escort: Creep?,
        val enemyEscort: Creep?,
        val myFlag: Position?,
        val enemyFlag: Position?,
        val homeSource: Source?,
        val myCreeps: List<Creep>,
        val active: List<Creep>,
        val enemyCreeps: List<Creep>,
        val enemyPending: List<Creep>,
        val combatEnemies: List<Creep>,
        val harvesters: List<Creep>,
        val fighters: List<Creep>,
        val healers: List<Creep>,
        /** Тягачи — тела из одних MOVE (не эскорт), в порядке рождения: первый стоит вплотную к эскорту. */
        val pullers: List<Creep>,
        val husks: List<Creep>,
        val blocked: List<Position>,
        /** Непроходимое для шага (структуры, обездвиженные); поезд добавляет сюда клетку назначения головы. */
        val blockedSet: HashSet<Int>,
        val enemyPositions: Set<Int>,
        val occupantAt: Map<Int, Creep>,
        /** Поле эскорта к его флагу (цена болота — из его тела). */
        val escortFlow: IntArray?,
        /** Поле эскорта ВРАГА к его флагу — для оценки его прихода. */
        val enemyEscortFlow: IntArray?,
        /** Поле к нашему эскорту (для конвоя и для оценки прихода врагов). */
        val toEscortFlow: IntArray?,
    ) {
        var dangerMatrix: CostMatrix? = null
    }

    fun tick() {
        bfsMaxTick = maxOf(bfsMaxTick, bfsThisTick)
        bfsThisTick = 0
        val now = getTicks()

        val allCreeps = getObjectsByPrototype(Creep::class).filter { it.exists }
        // эскорт узнаётся по имени прототипа (биндинг модуля арены не импортируем: см. types/.../season4/EscortCreep.kt)
        // и по факту «жив на первом тике»; getObjects() — на случай, если getObjectsByPrototype(Creep) не отдаёт наследников
        val escortObjs = getObjects().filter { it.exists && protoName(it) == "EscortCreep" }.map { it.unsafeCast<Creep>() }
        if (escortIds.isEmpty()) {
            for (c in allCreeps) if (!c.spawning) escortIds.add(c.id)
            for (c in escortObjs) escortIds.add(c.id)
        }
        val byId = HashMap<String, Creep>()
        for (c in allCreeps) byId[c.id] = c
        for (c in escortObjs) byId[c.id] = c
        val creeps = byId.values.toList()

        val myCreeps = creeps.filter { it.my }
        val enemyAll = creeps.filter { !it.my }
        val enemyCreeps = enemyAll.filter { !it.spawning }
        val enemyPending = enemyAll.filter { it.spawning }
        val active = myCreeps.filter { !it.spawning }

        val escort = myCreeps.firstOrNull { isEscort(it) }
        val enemyEscort = enemyCreeps.firstOrNull { isEscort(it) }

        val spawns = getObjectsByPrototype(StructureSpawn::class).filter { it.exists }
        val mySpawn = spawns.firstOrNull { it.my == true }
        val enemySpawn = spawns.firstOrNull { it.my == false }
        val flags = getObjectsByPrototype(Flag::class).filter { it.exists }
        val sources = getObjectsByPrototype(Source::class).filter { it.exists }
        val containers = getObjectsByPrototype(StructureContainer::class).filter { it.exists }
        val walls = getObjectsByPrototype(StructureWall::class).filter { it.exists }
        val towers = getObjectsByPrototype(StructureTower::class).filter { it.exists }
        val extensions = getObjectsByPrototype(StructureExtension::class).filter { it.exists }
        val ramparts = getObjectsByPrototype(StructureRampart::class).filter { it.exists }

        val structuresBase: List<Position> = spawns + walls + towers + extensions
        val structures: List<Position> = structuresBase + ramparts.filter { it.my != true }
        DistanceMap.syncStructures(structures)

        val home = mySpawn ?: escort
        val myFlag = chooseFlag(flags, home, enemySpawn ?: enemyEscort, mine = true)
        val enemyFlag = chooseFlag(flags, enemySpawn ?: enemyEscort, home, mine = false)
        val homeSource = if (home != null) sources.minByOrNull { getRange(it, home) } else null

        if (!greeted) {
            greeted = true
            probe(spawns, sources, containers, flags, creeps, escortObjs, myFlag, enemyFlag)
        }
        if (DEBUG_MAP && mapMarks == null) captureMapMarks(spawns, sources, containers, flags, creeps)
        if (DEBUG_MAP && now in 3..6) logMap((now - 3) * 25)

        val combatEnemies = enemyCreeps.filter { isCombat(it) }
        val harvesters = active.filter { !isEscort(it) && it.body.any { p -> p.type == WORK } }
        val fighters = active.filter { !isEscort(it) && hasWeapon(it) }
        val healers = active.filter { !isEscort(it) && !hasWeapon(it) && hasHeal(it) }
        // блокировщик тоже из одних MOVE, но он не тягач: его место на клетке вражеского флага, а не в цепи поезда
        val pullers = active.filter { !isEscort(it) && it.id !in blockerIds && it.body.all { p -> p.type == MOVE } }.sortedWith(compareBy({ it.id.length }, { it.id }))
        val husks = active.filter { !isEscort(it) && it !in harvesters && it !in fighters && it !in healers && it !in pullers }

        val immobile = active.filter { !canMove(it) && !isEscort(it) }
        val blocked: List<Position> = structures + immobile
        val blockedSet = blocked.mapTo(HashSet()) { it.x * 100 + it.y }
        val enemyPositions = enemyCreeps.mapTo(HashSet()) { it.x * 100 + it.y }
        val occupantAt = HashMap<Int, Creep>()
        for (c in active) occupantAt[c.x * 100 + c.y] = c

        InfluenceMap.setEnemyBlocked(structures.mapTo(HashSet()) { it.x * 100 + it.y })
        InfluenceMap.setProtectedCells(emptySet())
        InfluenceMap.setEnemyTowers(towers.filter { it.my == false }.map { InfluenceMap.TowerThreat(it.x, it.y, (it.store[RESOURCE_ENERGY] ?: 0) >= InfluenceMap.towerCost, 0) })

        val escortFlow = if (escort != null && myFlag != null) flowTo("escort", myFlag, blocked, swampRatio(escort)) else null
        // поле ВРАГА — по ЕГО проходимости: рампарт пускает только владельца, и с нашим списком преград путь их эскорта
        // к их флагу в первом матче не находился вовсе (пятьдесят рампартов на карте), а приход читался как «никогда»
        val blockedForEnemy: List<Position> = structuresBase + ramparts.filter { it.my != false }
        val enemyEscortFlow = if (enemyEscort != null && enemyFlag != null) flowToWith("enemyEscort", enemyFlag, blockedForEnemy, swampRatio(enemyEscort)) else null
        val toEscortFlow = if (escort != null) flowTo("toEscort", escort, blocked, 5, ttl = 1) else null

        val ctx = Ctx(mySpawn, enemySpawn, escort, enemyEscort, myFlag, enemyFlag, homeSource, myCreeps, active, enemyCreeps, enemyPending, combatEnemies,
            harvesters, fighters, healers, pullers, husks, blocked, blockedSet, enemyPositions, occupantAt, escortFlow, enemyEscortFlow, toEscortFlow)

        measureRegen(ctx)
        // чистится ЗДЕСЬ, а не после заказа: контекст собран до runSpawn, и крип, рождённый в этом тике, в myCreeps
        // ещё не попал — очистка в том же тике стирала только что назначенного блокировщика, и бот покупал второго
        blockerIds.retainAll { id -> myCreeps.any { it.id == id } }
        if (firstEnemySeenTick < 0 && enemyAll.any { !isEscort(it) }) firstEnemySeenTick = now

        runSpawn(ctx)
        runTrain(ctx)
        runHarvesters(ctx)
        runSquad(ctx)
        runHusks(ctx)
        enforceYield(ctx)

        TrafficManager.resolve(active.filter { canMove(it) && it.id !in trainMoved }, myCreeps + enemyCreeps)
        InfluenceMap.pruneStances(myCreeps.mapTo(HashSet()) { it.id })

        for (c in active) lastHits[c.id] = c.hits
        lastHits.keys.retainAll { id -> active.any { it.id == id } }
        aggressiveIds.retainAll { id -> active.any { it.id == id } }

        if (DEBUG_LOG) logStuck(active, enemyCreeps)
        if (DEBUG_LOG) logRace(ctx)
        if (DEBUG_LOG && now % LOG_EVERY == 0) logStatus(ctx)
        if (DEBUG_LOG && now % BODIES_EVERY == 0) logBodies(ctx)
    }

    // ==================== зонд ====================

    private fun protoName(o: GameObject): String? {
        val ctor = o.asDynamic().constructor
        if (ctor == null || ctor == undefined) return null
        val name = ctor.name
        return if (jsTypeOf(name) == "string") name.unsafeCast<String>() else null
    }

    private fun isEscort(c: Creep): Boolean = c.id in escortIds || protoName(c) == "EscortCreep"

    private fun probe(spawns: List<StructureSpawn>, sources: List<Source>, containers: List<StructureContainer>, flags: List<Flag>, creeps: List<Creep>, escortObjs: List<Creep>, myFlag: Position?, enemyFlag: Position?) {
        println(
            "hello season4 escort-run $BOT_VERSION: ${arenaInfo.season} - ${arenaInfo.name} level=${arenaInfo.level} ticksLimit=${arenaInfo.ticksLimit} " +
                "cpu=${arenaInfo.cpuTimeLimit}/${arenaInfo.cpuTimeLimitFirstTick} t=${getTicks()}"
        )
        // подпись сборки: по ней видно, какая версия играет, даже если строка версии не поднята по забывчивости
        println(
            "tuning: pull=$USE_PULL chain=$MAX_CHAIN pullerMove=$PULLER_MIN_MOVE..$PULLER_MAX_MOVE openingWait=$ENEMY_OPENING_WAIT " +
                "escortWatch=$ESCORT_WATCH healerEvery=$HEALER_EVERY strikeRatio=$STRIKE_RATIO planEvery=$PLAN_EVERY"
        )
        println("spawns: " + spawns.joinToString(" ") { "(${it.x},${it.y}) my=${it.my} e=${it.store[RESOURCE_ENERGY]}/${it.store.getCapacity(RESOURCE_ENERGY)} hits=${it.hits}/${it.hitsMax} spawning=${it.spawning != null}" })
        println("sources: " + sources.joinToString(" ") { "(${it.x},${it.y}) e=${it.energy}/${it.energyCapacity}" })
        println("containers: " + containers.joinToString(" ") { "(${it.x},${it.y}) my=${it.my} e=${it.store[RESOURCE_ENERGY]}/${it.store.getCapacity(RESOURCE_ENERGY)} decay=${it.ticksToDecay}" })
        println("flags: " + flags.joinToString(" ") { "(${it.x},${it.y}) my=${it.my} proto=${protoName(it)}" } +
            " -> ours=${myFlag?.let { "(${it.x},${it.y})" } ?: "-"} theirs=${enemyFlag?.let { "(${it.x},${it.y})" } ?: "-"}")
        println("creeps: " + creeps.joinToString(" ") { "${it.id}(${it.x},${it.y}) my=${it.my} proto=${protoName(it)} ${bodyOrder(it)} hits=${it.hits}/${it.hitsMax} fatigue=${it.fatigue} spawning=${it.spawning}" })
        println("escorts by prototype name: ${escortObjs.size}, by first tick: ${escortIds.size} ids=${escortIds.joinToString(",")}")
        val esc = creeps.firstOrNull { it.my && isEscort(it) }
        if (esc != null) {
            println("our escort: ${bodyOrder(esc)} weight=${bodyWeight(esc)} moves=${liveMoves(esc)} plainPeriod=${plainPeriod(esc)} swampPeriod=${swampPeriod(esc)} store=${esc.store[RESOURCE_ENERGY]}/${esc.store.getCapacity(RESOURCE_ENERGY)}")
        }
        println("constants: spawnCap=${num(SPAWN_ENERGY_CAPACITY.asDynamic(), -1.0)} spawnTime=${num(CREEP_SPAWN_TIME.asDynamic(), -1.0)} harvest=${num(HARVEST_POWER.asDynamic(), -1.0)} " +
            "cost M=${cost(MOVE)} W=${cost(WORK)} C=${cost(CARRY)} R=${cost(RANGED_ATTACK)} A=${cost(ATTACK)} H=${cost(HEAL)} T=${cost(TOUGH)}")
        val others = getObjects().filter { it.exists }.groupBy { protoName(it) ?: "?" }.mapValues { it.value.size }
        println("objects by prototype: " + others.entries.joinToString(" ") { "${it.key}=${it.value}" })
        // стены и рампарты — где они и чьи: в первом матче их было 48 и 50, и о них не говорила ни одна строка журнала
        val walls = getObjectsByPrototype(StructureWall::class).filter { it.exists }
        val ramparts = getObjectsByPrototype(StructureRampart::class).filter { it.exists }
        println("walls(${walls.size}): " + walls.joinToString(" ") { "(${it.x},${it.y})h${it.hits}" })
        println("ramparts(${ramparts.size}): " + ramparts.joinToString(" ") { "(${it.x},${it.y})my=${it.my}h${it.hits}" })
    }

    /** Число из константы арены (внешнее объявление может оказаться undefined — тогда запасное). */
    private fun num(v: dynamic, fallback: Double): Double = if (jsTypeOf(v) == "number") v.unsafeCast<Double>() else fallback

    /** Наш флаг: помеченный my; без пометки — единственный, иначе самый далёкий от нашего дома (флаг «на противоположной
     *  стороне карты»); флаг врага — симметрично. */
    private fun chooseFlag(flags: List<Flag>, home: Position?, theirHome: Position?, mine: Boolean): Position? {
        val marked = flags.filter { it.my == mine }
        if (marked.isNotEmpty()) return marked.minByOrNull { if (home != null) getRange(it, home) else 0 }
        val unmarked = flags.filter { it.my == null }
        if (unmarked.size == 1) return unmarked[0]
        if (unmarked.isEmpty()) return null
        if (home == null) return unmarked[0]
        // без пометки: два флага «на противоположных сторонах» — наш тот, что дальше от нашего дома и ближе к дому врага
        return unmarked.maxByOrNull { getRange(it, home) - (if (theirHome != null) getRange(it, theirHome) else 0) }
    }

    // ==================== экономика и спавн ====================

    private fun cost(part: BodyPartType): Int = (BODYPART_COST.asDynamic()[part.value] as? Int) ?: 0

    private fun bodyCost(body: Array<BodyPartType>): Int = body.sumOf { cost(it) }

    /** Регенерация спавна и источника — по приращениям за тики без вмешательства. */
    private fun measureRegen(ctx: Ctx) {
        val spawn = ctx.mySpawn
        if (spawn != null) {
            val e = spawn.store[RESOURCE_ENERGY] ?: 0
            val cap = spawn.store.getCapacity(RESOURCE_ENERGY) ?: SPAWN_ENERGY_CAPACITY
            if (prevSpawnEnergy >= 0 && !transferredLastTick && !spawnedLastTick && prevSpawnEnergy < cap) {
                regenSamples.addLast(e - prevSpawnEnergy)
                while (regenSamples.size > 10) regenSamples.removeFirst()
            }
            prevSpawnEnergy = e
        }
        val source = ctx.homeSource
        if (source != null) {
            if (prevSourceEnergy >= 0 && prevSourceEnergy < source.energyCapacity) {
                sourceRegenSamples.addLast(source.energy - prevSourceEnergy + harvestedLastTick)
                while (sourceRegenSamples.size > 10) sourceRegenSamples.removeFirst()
            }
            prevSourceEnergy = source.energy
        }
        transferredLastTick = false
        spawnedLastTick = false
        harvestedLastTick = 0
    }

    /** Регенерация спавна в тик (медиана замеров; до замера — 1, как в Spawn and Swamp). */
    private fun spawnRegen(): Double {
        if (regenSamples.isEmpty()) return 1.0
        val sorted = regenSamples.sorted()
        return sorted[sorted.size / 2].toDouble().coerceAtLeast(0.0)
    }

    /** Регенерация источника в тик (медиана замеров; до замера — 10, источник Мира). */
    private fun sourceRegen(): Double {
        if (sourceRegenSamples.isEmpty()) return 10.0
        val sorted = sourceRegenSamples.sorted()
        return sorted[sorted.size / 2].toDouble().coerceAtLeast(0.0)
    }

    /** Доход в тик: регенерация спавна плюс добыча каждого добытчика по его телу и дороге (harvesterIncome), не больше
     *  регенерации источника в сумме; extraWork — ещё не рождённый добытчик с таким WORK (для оценки «через добытчика»). */
    private fun income(ctx: Ctx, extraWork: Int = 0): Double {
        if (ctx.homeSource == null) return spawnRegen()
        val d = harvestCommute(ctx)
        var harvest = ctx.harvesters.sumOf { c ->
            harvesterIncome(c.body.count { it.type == WORK && it.hits > 0 }, c.body.count { it.type == CARRY }, liveMoves(c), d)
        }
        if (extraWork > 0) harvest += harvesterIncome(extraWork, maxOf(1, extraWork), maxOf(1, extraWork), d)
        return spawnRegen() + minOf(harvest, sourceRegen())
    }

    /** Тиков до накопления energy при доходе income (бесконечность — при нулевом доходе). */
    private fun ticksToAccumulate(energy: Int, income: Double): Int =
        if (energy <= 0) 0 else if (income <= 0.0) Int.MAX_VALUE / 4 else (energy / income).toInt() + 1

    private fun spawnTicks(body: Array<BodyPartType>): Int = body.size * CREEP_SPAWN_TIME

    /** Доход добытчика (энергии в тик) по телу и дороге: d — шагов между клеткой у источника и клеткой у спавна (0 —
     *  есть клетка, смежная с обоими: копает и переливает каждый тик). Цикл — набрать ёмкость при 2 в тик на WORK,
     *  дойти гружёным (вес WORK + гружёные CARRY), перелить (тик), вернуться пустым; не больше регенерации источника. */
    private fun harvesterIncome(work: Int, carry: Int, moves: Int, d: Int): Double {
        val rate = work * HARVEST_POWER.toDouble()
        if (d <= 0) return minOf(rate, sourceRegen())
        val capacity = carry * CARRY_CAPACITY
        val harvestTicks = (capacity + rate - 1).toInt() / maxOf(1, rate.toInt())
        val loaded = periodOn(work + carry, moves, 2)
        val empty = periodOn(work, moves, 2)
        val cycle = harvestTicks + d * loaded + 1 + d * empty
        return minOf(capacity.toDouble() / cycle, sourceRegen())
    }

    /** Добытчик под бюджет и дорогу: перебор (WORK, CARRY, MOVE) по доходу; при равном доходе — дешевле. CARRY и MOVE
     *  спереди — урон снимает части с головы, а ценность добытчика — в WORK. Стенд показал цену догадки: M1C1W5 у
     *  источника в трёх клетках от спавна давал 3 в тик — гружёный он шёл клетку шесть тиков. */
    private fun harvesterBody(budget: Int, d: Int): Array<BodyPartType>? {
        var best: Array<BodyPartType>? = null
        var bestIncome = 0.0
        var bestCost = Int.MAX_VALUE
        for (w in HARVESTER_MIN_WORK..HARVESTER_MAX_WORK) for (c in 1..5) for (m in 1..8) {
            val costSum = w * cost(WORK) + c * cost(CARRY) + m * cost(MOVE)
            if (costSum > budget || w + c + m > MAX_CREEP_SIZE) continue
            val income = harvesterIncome(w, c, m, d)
            if (income > bestIncome + 1e-9 || (abs(income - bestIncome) <= 1e-9 && costSum < bestCost)) {
                bestIncome = income; bestCost = costSum
                val body = ArrayList<BodyPartType>()
                repeat(m) { body.add(MOVE) }
                repeat(c) { body.add(CARRY) }
                repeat(w) { body.add(WORK) }
                best = body.toTypedArray()
            }
        }
        return best
    }

    /** Дорога добытчика: шагов между клеткой у источника и клеткой у спавна (по Чебышеву, ноль при смежности). */
    private fun harvestCommute(ctx: Ctx): Int {
        val spawn = ctx.mySpawn ?: return 0
        val source = ctx.homeSource ?: return 0
        return maxOf(0, getRange(source, spawn) - 2)
    }

    /** Лекарь под бюджет: MOVE и HEAL 1:1, MOVE спереди (гибнут первыми: лечение остаётся, скорость падает вдвое — эскорт
     *  всё равно медленнее). */
    private fun healerBody(budget: Int): Array<BodyPartType>? {
        val n = minOf((budget / (cost(MOVE) + cost(HEAL))), MAX_CREEP_SIZE / 2)
        if (n < 1) return null
        val body = ArrayList<BodyPartType>()
        repeat(n) { body.add(MOVE) }
        repeat(n) { body.add(HEAL) }
        return body.toTypedArray()
    }

    /** Ценность тела: урон × хиты (Ланчестер) при полном ходе свежего тела (живых MOVE не меньше веса — перебор строит
     *  только такие). Бой здесь — у медленного эскорта, чьи 5000 хитов — наш запас, а не кайт: решает урон, и M5R5 (50
     *  урона, 1000 хитов) бьёт M8R4 (40, 1200) — стенд rush: M8R4 проиграл дуэль M5R5 на равных хитах. Скорость после
     *  потерь: M5R5 держит период 2 (темп поезда с одним тягачом) до трёхсот снятых хитов. */
    private fun bodyValue(body: Array<BodyPartType>): Int {
        val dps = body.count { it == RANGED_ATTACK } * RANGED_ATTACK_POWER + body.count { it == ATTACK } * ATTACK_POWER
        return dps * body.size * 100
    }

    private val guardBodyCache = HashMap<Int, Array<BodyPartType>>()

    /** Мили-боец под бюджет (тот же перебор и порядок, что у стрелка, с ATTACK вместо RANGED): против мили-противника.
     *  Мили в пять раз дешевле стрелка за единицу урона (80 за 30 против 150 за 10): M7A7 врага у эскорта — 210 в тик,
     *  и снимает его вплотную только такой же мили; стрелок кайтит, но эскорт кайтить не может. */
    private fun guardBody(budget: Int): Array<BodyPartType>? {
        val cap = minOf(budget, SPAWN_ENERGY_CAPACITY)
        val block = cost(ATTACK) + cost(MOVE)
        if (cap < block) return null
        return guardBodyCache.getOrPut(cap) {
            var best: Array<BodyPartType>? = null
            var bestValue = -1
            var a = 1
            while (a * block <= cap && 2 * a <= MAX_CREEP_SIZE) {
                val maxExtra = minOf((cap - a * block) / cost(MOVE), MAX_CREEP_SIZE - 2 * a)
                for (e in 0..maxExtra) {
                    // все MOVE спереди, удар в хвосте: части гибнут с головы, и M3A3 с ударом впереди стал M3A1 после первых
                    // двухсот хитов (стенд melee); скорость после потерь здесь второстепенна — бой идёт у медленного эскорта
                    val body = ArrayList<BodyPartType>(2 * a + e)
                    repeat(a + e) { body.add(MOVE) }
                    repeat(a) { body.add(ATTACK) }
                    val arr = body.toTypedArray()
                    val value = bodyValue(arr)
                    if (value > bestValue) { bestValue = value; best = arr }
                }
                a++
            }
            best ?: arrayOf(MOVE, ATTACK)
        }
    }

    /** Противник мили-доминантный: среди его боевых (живых и рождающихся) урон ATTACK больше урона RANGED. */
    private fun enemyMeleeDominant(ctx: Ctx): Boolean {
        val all = ctx.combatEnemies + ctx.enemyPending.filter { isCombat(it) }
        var melee = 0.0
        var ranged = 0.0
        for (e in all) { val p = InfluenceMap.profileOf(e); melee += p.melee; ranged += p.ranged }
        return melee > ranged
    }

    /** Тело бойца конвоя под бюджет по противнику: мили против мили-доминантного, иначе стрелок. */
    private fun combatBody(ctx: Ctx, budget: Int): Array<BodyPartType>? = if (enemyMeleeDominant(ctx)) guardBody(budget) else fighterBody(budget)

    private val fighterBodyCache = HashMap<Int, Array<BodyPartType>>()

    /** Тело стрелка под бюджет: перебор (R, T, запасные MOVE) по максимуму bodyValue; запасные MOVE спереди, затем
     *  RANGED, в хвост MOVE 1:1. На 1000 это M8R4 (Spawn and Swamp v9). */
    private fun fighterBody(budget: Int): Array<BodyPartType>? {
        val cap = minOf(budget, SPAWN_ENERGY_CAPACITY)
        val rangedBlock = cost(RANGED_ATTACK) + cost(MOVE)
        if (cap < rangedBlock) return null
        return fighterBodyCache.getOrPut(cap) {
            val toughBlock = cost(TOUGH) + cost(MOVE)
            var best: Array<BodyPartType>? = null
            var bestValue = -1
            var bestRanged = 0
            var r = 1
            while (r * rangedBlock <= cap) {
                var t = 0
                while (r * rangedBlock + t * toughBlock <= cap && 2 * (r + t) <= MAX_CREEP_SIZE) {
                    val spent = r * rangedBlock + t * toughBlock
                    val maxExtra = minOf((cap - spent) / cost(MOVE), MAX_CREEP_SIZE - 2 * (r + t))
                    for (e in 0..maxExtra) {
                        // TOUGH спереди (дешёвые хиты), затем все MOVE, стрельба в хвосте — см. guardBody
                        val body = ArrayList<BodyPartType>(2 * (r + t) + e)
                        repeat(t) { body.add(TOUGH) }
                        repeat(e + r + t) { body.add(MOVE) }
                        repeat(r) { body.add(RANGED_ATTACK) }
                        val arr = body.toTypedArray()
                        val value = bodyValue(arr)
                        if (value > bestValue || (value == bestValue && r > bestRanged)) { bestValue = value; best = arr; bestRanged = r }
                    }
                    t++
                }
                r++
            }
            best ?: arrayOf(MOVE, RANGED_ATTACK)
        }
    }

    /** Закрывает ли урезанное тело дефицит: враги, доходящие до эскорта за horizon тиков, умирают под суммарным уроном живых
     *  бойцов и этого тела раньше, чем их урон снимет хиты эскорта (с запасом на лечение и разброс — не больше четырёх
     *  пятых хитов). Без бойцов и без урона — не закрывает. */
    private fun closesDeficit(ctx: Ctx, body: Array<BodyPartType>, horizon: Int): Boolean {
        val escort = ctx.escort ?: return false
        val flow = ctx.toEscortFlow ?: return false
        val coming = (ctx.combatEnemies + ctx.enemyPending.filter { isCombat(it) }).filter { e ->
            val cell = if (flow[e.x * 100 + e.y] >= 0) e.x * 100 + e.y else freeNeighbourCell(flow, e.x, e.y)
            cell >= 0 && pathTicks(e, flow, cell) <= horizon
        }
        if (coming.isEmpty()) return false
        val enemyDps = coming.sumOf { val p = InfluenceMap.profileOf(it); p.melee + p.ranged }
        var enemyHits = coming.sumOf { it.hits }.toDouble()
        val bodyDps = (body.count { it == RANGED_ATTACK } * RANGED_ATTACK_POWER + body.count { it == ATTACK } * ATTACK_POWER).toDouble()
        val ours = ctx.fighters.map { effectiveDps(it, coming) to it.hits } + (bodyDps to body.size * 100)
        var ourDps = ours.sumOf { it.first }
        if (ourDps <= 0.0) return false
        // враг выбирает цель сам: (а) бьёт эскорт — мы должны убить его раньше, чем он снимет четыре пятых хитов эскорта;
        // (б) бьёт бойцов, слабейшего первым, — враг должен умереть, пока хоть один боец жив. Держим оба
        if (enemyDps * (enemyHits / ourDps) >= escort.hits * 0.8) return false
        for ((dps, hits) in ours.sortedBy { it.second }) {
            enemyHits -= ourDps * (hits / enemyDps)
            if (enemyHits <= 0.0) return true
            ourDps -= dps
            if (ourDps <= 0.0) return false
        }
        return enemyHits <= 0.0
    }

    /** Ближайший приход боевого врага (живого или рождающегося) к нашему эскорту, в тиках: ход его тела вдоль поля к
     *  эскорту; рождающийся — плюс остаток родов. MAX — угроз нет. */
    private fun earliestThreatArrival(ctx: Ctx): Int {
        val flow = ctx.toEscortFlow ?: return Int.MAX_VALUE / 4
        var best = Int.MAX_VALUE / 4
        for (e in ctx.combatEnemies) best = minOf(best, pathTicks(e, flow, e.x * 100 + e.y))
        val pendingLeft = ctx.enemySpawn?.spawning?.remainingTime ?: 0
        for (e in ctx.enemyPending) {
            if (!isCombat(e)) continue
            // рождающийся стоит В клетке спавна — она в поле стена; считаем от лучшей соседней клетки (без этого угроза
            // из спавна читалась как «никогда», и тягач за 800 покупался под носом у M7A7 — стенд melee)
            val walk = pathTicks(e, flow, freeNeighbourCell(flow, e.x, e.y))
            if (walk < Int.MAX_VALUE / 8) best = minOf(best, pendingLeft + walk)
        }
        return best
    }

    /** Мощь видимого боевого врага (живые и рождающиеся) по Ланчестеру — столько должен набрать конвой, чтобы оборона
     *  была обороной, а не жертвой: M6A5 (150 × 1100) против M7A7 (210 × 1400) погиб за пять тиков (стенд melee v9). */
    private fun requiredPower(ctx: Ctx): Double {
        val all = ctx.combatEnemies + ctx.enemyPending.filter { isCombat(it) }
        if (all.isEmpty()) return 0.0
        val dps = all.sumOf { val p = InfluenceMap.profileOf(it); p.melee + p.ranged }
        val hits = all.sumOf { it.hits }.toDouble()
        return sqrt(dps * hits)
    }

    /** Соседняя клетка с наименьшим (неотрицательным) значением поля — откуда крип из спавна выйдет; -1 — нет. */
    private fun freeNeighbourCell(flow: IntArray, x: Int, y: Int): Int {
        if (flow[x * 100 + y] >= 0) return x * 100 + y
        var best = -1
        var bestFlow = Int.MAX_VALUE
        for ((dx, dy) in DIRECTIONS) {
            val nx = x + dx; val ny = y + dy
            if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
            val f = flow[nx * 100 + ny]
            if (f in 0 until bestFlow) { bestFlow = f; best = nx * 100 + ny }
        }
        return best
    }

    // ---------- план дебюта: добытчик и тягачи по времени прихода эскорта ----------

    /** Заказ плана: тело и роль. */
    private class Order(val body: Array<BodyPartType>, val role: String)

    /** План: первый заказ (null — ничего не заказывать), предсказанный приход эскорта на флаг, готовность обороны у
     *  эскорта и энергия, накопленная к приходу (тай-брейк: см. planOpening). */
    private class Plan(val first: Order?, val arrival: Int, val fighterReady: Int, val energy: Double, val cost: Int, val desc: String)

    /** Итог прогона: приход эскорта, готовность обороны и энергия спавна на момент прихода. */
    private class Sim(val arrival: Int, val fighterReady: Int, val energyAtArrival: Double)

    /** Клетки маршрута эскорта по полю: болото ли каждая (для расчёта времени с разным ΣMOVE). */
    private fun routeCells(escort: Creep, flow: IntArray): BooleanArray {
        var cell = escort.x * 100 + escort.y
        val out = ArrayList<Boolean>()
        if (flow[cell] < 0) return BooleanArray(0)
        var steps = 0
        while (flow[cell] > 0 && steps < 400) {
            val cx = cell / 100
            val cy = cell % 100
            var best = -1
            var bestFlow = flow[cell]
            for (dx in -1..1) for (dy in -1..1) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
                val f = flow[nx * 100 + ny]
                if (f in 0 until bestFlow) { bestFlow = f; best = nx * 100 + ny }
            }
            if (best < 0) break
            cell = best
            steps++
            out.add(DistanceMap.isSwamp(cell / 100, cell % 100))
        }
        return out.toBooleanArray()
    }

    /**
     * Прогон спавна по своей же политике: энергия растёт на доход (регенерация + добытчики по мере рождения и подхода к
     * источнику), заказы идут по списку, как только спавн свободен и энергии хватает; тягач после родов догоняет эскорт
     * (клетка в тик, догон — дистанция от спавна до эскорта в момент рождения) и с этого тика ΣMOVE поезда растёт.
     * Эскорт идёт по маршруту клетка за клеткой с периодом поезда на каждой (усталость сейчас — первая задержка).
     * Возвращает тик прихода на флаг (от сейчас), MAX — не приходит за матч.
     */
    private fun simulateArrival(ctx: Ctx, escort: Creep, route: BooleanArray, orders: List<Order>, fighterFull: Array<BodyPartType>): Sim {
        // оборона готова, когда мощь купленных бойцов (в плане и полные тела после него) по Ланчестеру достигает мощи
        // видимого врага (requiredPower) — с рождением и подходом последнего из них к эскорту
        val required = requiredPower(ctx)
        var energyAtArrival = -1.0
        var defDps = ctx.fighters.sumOf { val p = InfluenceMap.profileOf(it); p.melee + p.ranged }
        var defHits = ctx.fighters.sumOf { it.hits }.toDouble()
        fun defended() = (defDps > 0.0) && sqrt(defDps * defHits) >= required
        val spawn = ctx.mySpawn ?: return Sim(Int.MAX_VALUE / 4, Int.MAX_VALUE / 4, 0.0)
        val horizon = arenaInfo.ticksLimit - getTicks()
        var energy = (spawn.store[RESOURCE_ENERGY] ?: 0).toDouble()
        val cap = spawn.store.getCapacity(RESOURCE_ENERGY) ?: SPAWN_ENERGY_CAPACITY
        var income = income(ctx)
        var busyUntil = spawn.spawning?.remainingTime ?: 0
        val d = harvestCommute(ctx)
        val sourceWalk = ctx.homeSource?.let { getRange(spawn, it) } ?: 0
        val weight = bodyWeight(escort)
        var moves = trainMoves(escort, ctx.pullers)
        val spawnToEscort = getRange(spawn, escort)
        // события: (тик, +доход) и (тик, +MOVE поезда)
        val incomeAt = ArrayList<Pair<Int, Double>>()
        val movesAt = ArrayList<Pair<Int, Int>>()
        var next = 0
        var cell = 0
        var cellStart = -(escort.fatigue / maxOf(1, 2 * liveMoves(escort))) // остаток усталости — ждём
        var t = 0
        var arrival = Int.MAX_VALUE / 4
        var fighterReady = if (defended()) 0 else Int.MAX_VALUE / 4
        var defenseBirth = -1 // тик рождения бойца, с которым оборона готова: подход считается ОТ РОЖДЕНИЯ, по эскорту в тот момент
        val fighterCost = bodyCost(fighterFull)
        val fullDps = (fighterFull.count { it == RANGED_ATTACK } * RANGED_ATTACK_POWER + fighterFull.count { it == ATTACK } * ATTACK_POWER).toDouble()
        // подход рождённого к эскорту: расстояние до него в момент рождения, а эскорт за время подхода уходит дальше с
        // периодом поезда p — путь W = D × p/(p−1) (при p = 1 догнать нельзя: не дальше трёх D). Считать от заказа
        // (стенд melee v10: 151 в прогоне против 171 в жизни, эскорт мёртв на 174) — ошибка величиной в сам бой
        fun walkToEscort(atTick: Int): Int {
            val dist = spawnToEscort + cell
            val p = trainPeriod(weight, moves, false)
            return if (p <= 1) 3 * dist else minOf(3 * dist, dist * p / (p - 1))
        }
        while (t < horizon) {
            if (defenseBirth == t && fighterReady >= Int.MAX_VALUE / 4) fighterReady = t + walkToEscort(t)
            // заказ плана; после плана — полные бойцы по мере энергии, пока оборона не готова
            if (t >= busyUntil) {
                if (next < orders.size) {
                    if (energy >= bodyCost(orders[next].body)) {
                        val o = orders[next]
                        energy -= bodyCost(o.body)
                        busyUntil = t + spawnTicks(o.body)
                        if (o.role == "harvester") incomeAt.add(busyUntil + sourceWalk to harvesterIncome(o.body.count { it == WORK }, o.body.count { it == CARRY }, o.body.count { it == MOVE }, d))
                        else if (o.role == "puller") movesAt.add(busyUntil + spawnToEscort + cell to o.body.size)
                        else if (o.role == "fighter") {
                            defDps += o.body.count { it == RANGED_ATTACK } * RANGED_ATTACK_POWER + o.body.count { it == ATTACK } * ATTACK_POWER
                            defHits += o.body.size * 100
                            if (fighterReady >= Int.MAX_VALUE / 4 && defenseBirth < 0 && defended()) defenseBirth = busyUntil
                        }
                        next++
                    }
                } else if (fighterReady >= Int.MAX_VALUE / 4 && defenseBirth < 0 && energy >= fighterCost) {
                    energy -= fighterCost
                    busyUntil = t + spawnTicks(fighterFull)
                    defDps += fullDps
                    defHits += fighterFull.size * 100
                    if (defended()) defenseBirth = busyUntil
                }
            }
            if (arrival < Int.MAX_VALUE / 4 && fighterReady < Int.MAX_VALUE / 4) break
            for (i in incomeAt.indices) if (incomeAt[i].first == t) income = minOf(income + incomeAt[i].second, spawnRegen() + sourceRegen())
            for (i in movesAt.indices) if (movesAt[i].first == t) moves += movesAt[i].second
            energy = minOf(cap.toDouble(), energy + income)
            // эскорт: клетка занимает период поезда на ней
            if (arrival >= Int.MAX_VALUE / 4) {
                if (cell < route.size) {
                    val period = trainPeriod(weight, moves, route[cell])
                    if (t - cellStart >= period) { cell++; cellStart = t; if (cell >= route.size) arrival = t }
                } else arrival = t
                if (arrival < Int.MAX_VALUE / 4) energyAtArrival = energy
                if (arrival < Int.MAX_VALUE / 4 && fighterReady < Int.MAX_VALUE / 4) break
            }
            t++
        }
        return Sim(arrival, fighterReady, if (energyAtArrival >= 0.0) energyAtArrival else energy)
    }

    /**
     * Срок обороны: к какому тику у эскорта должен стоять полный боец. Враг доходит до эскорта за A тиков (видимые и
     * рождающиеся боевые — по их ходу; никого не видно — его спавн рождает боевое тело за полный бюджет и идёт к эскорту
     * по полю), потом снимает с эскорта свой урон в тик (видимый; иначе — стрелок за полный бюджет спавна) — боец обязан
     * прийти раньше, чем урон съест хиты эскорта за вычетом добивания стрелка.
     */
    private fun defenseDeadline(ctx: Ctx, fighterFull: Array<BodyPartType>): Int {
        val escort = ctx.escort ?: return Int.MAX_VALUE / 4
        val fullBudget = minOf(ctx.mySpawn?.store?.getCapacity(RESOURCE_ENERGY) ?: SPAWN_ENERGY_CAPACITY, SPAWN_ENERGY_CAPACITY)
        val typicalDps = RANGED_ATTACK_POWER.toDouble() * (fullBudget / (cost(RANGED_ATTACK) + cost(MOVE)))
        // срок есть только у ВИДИМОЙ угрозы: первый заказ врага виден со второго тика, а воображаемый стрелок «на всякий
        // случай» заставлял строить бойца первым и в пустой карте, и поезд не покупался вовсе (стенд v9: гонка проиграна)
        val visible = (ctx.combatEnemies + ctx.enemyPending.filter { isCombat(it) })
        if (visible.isEmpty()) return Int.MAX_VALUE / 8
        val enemyDps = visible.sumOf { val p = InfluenceMap.profileOf(it); p.melee + p.ranged }.takeIf { it > 0.0 } ?: typicalDps
        val arrival = earliestThreatArrival(ctx)
        val ourDps = fighterFull.count { it == RANGED_ATTACK } * RANGED_ATTACK_POWER.toDouble() + fighterFull.count { it == ATTACK } * ATTACK_POWER.toDouble()
        val killTicks = if (ourDps <= 0.0) 0 else (enemyDps * 10 / ourDps).toInt() // тело врага ≈ 10 хитов на единицу урона в тик
        if (arrival >= Int.MAX_VALUE / 8) return Int.MAX_VALUE / 8 // угроза не доходит — срока нет
        // запас: боец успевает, если эскорт к его приходу потерял не больше четырёх пятых хитов (лечение и разброс)
        return (arrival.toLong() + (escort.hits * 0.8 / enemyDps).toLong() - killTicks).coerceIn(0L, Int.MAX_VALUE / 8L).toInt()
    }

    /** Лучший план дебюта: последовательности до трёх заказов из {добытчик под бюджет, тягач из n MOVE, боец под бюджет},
     *  каждая роль не больше раза, в любом порядке — по цене «приход эскорта + опоздание бойца к сроку обороны» (тик
     *  прихода и тик опоздания весят одинаково: пока бойца нет, эскорт под огнём теряет хиты каждый тик так же, как
     *  теряет тики гонки). Боец первым за всю тысячу (стенд v5) оставлял доход 1 в тик на двести тиков — ни тягача, ни
     *  добытчика, ни второго бойца против второго M7A7; здесь боец конкурирует с экономикой на равных, по прогону. */
    private fun planOpening(ctx: Ctx, fullBudget: Int): Plan? {
        val escort = ctx.escort ?: return null
        val flow = ctx.escortFlow ?: return null
        val route = routeCells(escort, flow)
        if (route.isEmpty()) return null
        val d = harvestCommute(ctx)
        val fighterFull = combatBody(ctx, fullBudget) ?: arrayOf(MOVE, RANGED_ATTACK)
        val deadline = defenseDeadline(ctx, fighterFull)
        // сетка кандидатов (прогон стоит миллисекунды, а их сотни в тик при лимите арены 50 мс)
        val harvesters = ArrayList<Order>()
        if (ctx.harvesters.isEmpty() && ctx.homeSource != null && ctx.homeSource.energy > 0) {
            for (b in intArrayOf(300, 600, fullBudget)) harvesterBody(minOf(b, fullBudget), d)?.let { hb -> if (harvesters.none { it.body.contentEquals(hb) }) harvesters.add(Order(hb, "harvester")) }
        }
        // ускорение НЕ покупается, пока нет ни одного бойца, а видимая угроза доходит до эскорта раньше, чем он
        // финиширует: там, где срок обороны срывают все планы, критерий «минимальное опоздание» выбирал доход, деньги
        // уходили в скорость, и эскорт умирал за пятнадцать тиков до прихода бойца (стенд melee: гибель на 231)
        // угроза для этого правила — тот, кто идёт К НАМ, а не сопровождает своего: боец, стоящий у вражеского эскорта,
        // ближе к нему, чем к нашему, и оборона против него не нужна (стенд guard: конвой соперника читался как угроза,
        // ускорение не покупалось вовсе, и гонка проигрывалась пешком)
        val comingAtUs = ctx.escort != null && ctx.enemyEscort != null &&
            (ctx.combatEnemies + ctx.enemyPending.filter { isCombat(it) }).any { getRange(it, ctx.escort) < getRange(it, ctx.enemyEscort) }
        val defenceFirst = ctx.fighters.isEmpty() && deadline < Int.MAX_VALUE / 8 && comingAtUs && earliestThreatArrival(ctx) < ourArrival(ctx)
        val pullers = ArrayList<Order>()
        if (USE_PULL && !pullBroken && !defenceFirst && ctx.pullers.size < MAX_CHAIN) {
            for (n in intArrayOf(PULLER_MIN_MOVE, 10, PULLER_MAX_MOVE)) if (n * cost(MOVE) <= fullBudget) pullers.add(Order(Array(n) { MOVE }, "puller"))
        }
        val fighters = ArrayList<Order>()
        if (ctx.fighters.isEmpty() && deadline < Int.MAX_VALUE / 8) {
            for (b in intArrayOf(fullBudget * 7 / 10, fullBudget)) combatBody(ctx, b)?.let { fb -> if (fighters.none { it.body.contentEquals(fb) }) fighters.add(Order(fb, "fighter")) }
        }
        // цена плана — приход эскорта, а срок обороны жёсткий: боец позже срока — эскорт мёртв, а мёртвый эскорт не
        // приходит никуда (мягкий штраф «тик за тик» покупал тягач при бойце на 57 тиков позже срока). Планы, срывающие
        // срок, ранжируются после всех выполняющих — по опозданию.
        // ⚠️ Пробовал третий класс «гонка уже выиграна по наблюдению — ранжируем по энергии»: против стоящего эскорта
        // врага он включался сразу, скорость переставала цениться совсем, и приход уезжал с 259 тиков на 436. Наблюдение
        // «враг стоит» может смениться в любой тик, а потерянные тики не возвращаются — экономика берётся только тогда,
        // когда она гонку НЕ замедляет (тай-брейк по энергии ниже), а лишние деньги тратит ветка конвоя
        val horizon = arenaInfo.ticksLimit - getTicks()
        fun costOf(s: Sim) = if (deadline >= Int.MAX_VALUE / 8 || s.fighterReady <= deadline) minOf(s.arrival, horizon) else horizon + minOf(s.fighterReady, horizon) - deadline
        val base = simulateArrival(ctx, escort, route, emptyList(), fighterFull)
        var best = Plan(null, base.arrival, base.fighterReady, base.energyAtArrival, costOf(base), "nothing")
        var sims = 0
        val all = ArrayList<Plan>()
        // при РАВНОЙ цене выигрывает план с большей энергией к приходу эскорта: добытчик гонку не ускоряет, поэтому по
        // одной цене он всегда проигрывал «ничего» — и в первом живом матче бот семьсот тиков копил на бойца при доходе
        // 1 в тик, не построив ни одного добытчика. Доход — это будущие бойцы и тягачи, и он берётся, когда бесплатен
        fun better(p: Plan) = p.cost < best.cost || (p.cost == best.cost && p.energy > best.energy + 1e-9)
        fun consider(orders: List<Order>) {
            sims++
            val s = simulateArrival(ctx, escort, route, orders, fighterFull)
            val c = costOf(s)
            val p = Plan(orders.first(), s.arrival, s.fighterReady, s.energyAtArrival, c, orders.joinToString("+") { "${it.role}:${summaryOf(it.body)}" })
            if (DEBUG_PLANS) all.add(p)
            if (better(p)) best = p
        }
        // перестановки заказов: добытчик и боец не больше раза, а ТЯГАЧЕЙ столько, сколько ещё влезает в цепь — два по
        // десять MOVE обгоняют один на двадцать, потому что первый начинает тянуть вдвое раньше (стенд: приход 230
        // против 247), и без повтора роли план этого варианта не видел вовсе
        val pullersLeft = maxOf(0, MAX_CHAIN - ctx.pullers.size)
        fun rec(prefix: List<Order>, usedHarvester: Boolean, usedFighter: Boolean, pullersRest: Int) {
            if (prefix.isNotEmpty()) consider(prefix)
            if (prefix.size >= 3) return
            if (!usedHarvester) for (o in harvesters) rec(prefix + o, true, usedFighter, pullersRest)
            if (!usedFighter) for (o in fighters) rec(prefix + o, usedHarvester, true, pullersRest)
            if (pullersRest > 0) for (o in pullers) rec(prefix + o, usedHarvester, usedFighter, pullersRest - 1)
        }
        rec(emptyList(), false, false, pullersLeft)
        if (best.first != null && DEBUG_LOG && getTicks() % LOG_EVERY == 0) println("plan t=${getTicks()}: ${best.desc} arrival=${best.arrival} fighterReady=${best.fighterReady} energy=${best.energy.toInt()} deadline=$deadline cost=${best.cost} sims=$sims (nothing: arrival=${base.arrival} fighter=${base.fighterReady} energy=${base.energyAtArrival.toInt()})")
        if (DEBUG_PLANS && !plansDumped) {
            plansDumped = true
            println("plans t=${getTicks()} deadline=$deadline required=${requiredPower(ctx).toInt()}:\n" + all.sortedWith(compareBy({ it.cost }, { -it.energy })).take(12).joinToString("\n") { "  cost=${it.cost} arrival=${it.arrival} fighter=${it.fighterReady} energy=${it.energy.toInt()} ${it.desc}" })
        }
        return best
    }

    /** Разовый дамп дюжины лучших планов первого прогона — чтобы читать выбор прогона по числам, а не гадать. */
    private const val DEBUG_PLANS = true
    private var plansDumped = false

    private fun runSpawn(ctx: Ctx) {
        val spawn = ctx.mySpawn ?: return
        if (spawn.spawning != null) return
        val energy = spawn.store[RESOURCE_ENERGY] ?: 0
        val cap = spawn.store.getCapacity(RESOURCE_ENERGY) ?: SPAWN_ENERGY_CAPACITY
        val fullBudget = minOf(cap, SPAWN_ENERGY_CAPACITY)
        val now = getTicks()

        // дебют врага виден со ВТОРОГО тика (его spawnCreep на первом), и знать его надо: стартовой энергии хватает на
        // одно решение, и потраченная до этого знания она уходит в скорость там, где нужен был боец (стенд melee: тягач
        // на первом тике, эскорт мёртв на 231). Ждём только появления заказа, а не половину окна в десять тиков, как
        // унаследовано из Spawn and Swamp: те десять тиков — это пять клеток форы сопернику, заказавшему на первом
        val enemyOrderVisible = ctx.enemyPending.isNotEmpty() || ctx.enemyCreeps.any { !isEscort(it) } || (ctx.enemySpawn?.spawning != null)
        if (ctx.myCreeps.none { !isEscort(it) } && !enemyOrderVisible && now <= ENEMY_OPENING_WAIT) return

        val threat = earliestThreatArrival(ctx)
        val inc = income(ctx)
        val fighterFull = combatBody(ctx, fullBudget) ?: arrayOf(MOVE, RANGED_ATTACK)

        // угроза у ворот: враг дойдёт раньше, чем накопится полное тело, — строим то, что есть, если оно ЗАКРЫВАЕТ дефицит:
        // вместе с уже живыми бойцами убивает подходящих врагов раньше, чем те снимут хиты эскорта (M2A2 за 260 против M7A7
        // этого не делает — 24 тика по 210 — и лишь съедал энергию плана; стенд melee v7). Остальное решает прогон плана
        if (threat < Int.MAX_VALUE / 4 && energy < bodyCost(fighterFull)) {
            val fighterNow = ticksToAccumulate(bodyCost(fighterFull) - energy, inc) + spawnTicks(fighterFull)
            if (threat < fighterNow) {
                val part = combatBody(ctx, energy)
                if (part != null && part.count { it == RANGED_ATTACK || it == ATTACK } >= MIN_FIGHTER_RANGED && closesDeficit(ctx, part, fighterNow)) {
                    order(spawn, part, "fighter", "undersized, closes the deficit: threat=$threat fighterNow=$fighterNow"); return
                }
            }
        }

        // дебют — по прогону: что из (добытчик, тягач, боец) в каком порядке приводит эскорт раньше при бойце к сроку
        // обороны; план пересчитывается раз в PLAN_EVERY тиков (состояние меняется медленно, прогон стоит миллисекунд)
        if (ctx.harvesters.isEmpty() || (USE_PULL && !pullBroken && ctx.pullers.size < MAX_CHAIN) || ctx.fighters.isEmpty()) {
            val plan = if (planCache != null && now - planCacheTick < PLAN_EVERY && (planCache!!.first == null || energy < bodyCost(planCache!!.first!!.body))) planCache else planOpening(ctx, fullBudget).also { planCache = it; planCacheTick = now }
            if (plan?.first != null) {
                val o = plan.first
                if (energy >= bodyCost(o.body)) { order(spawn, o.body, o.role, "plan ${plan.desc} arrival=${plan.arrival} fighterReady=${plan.fighterReady} threat=$threat income=${inc.toInt()}"); return }
                if (DEBUG_LOG && now % LOG_EVERY == 0) println("spawn t=$now: saving ${o.role} ${summaryOf(o.body)} e=$energy/${bodyCost(o.body)} plan=${plan.desc} arrival=${plan.arrival} threat=$threat")
                return
            }
        }

        // гонка проиграна, скорость куплена — занимаем клетку ИХ флага: пятьдесят энергии не дают их эскорту
        // финишировать вовсе, пока они не построят бойца и не убьют блокировщика. Это самый дешёвый способ сорвать
        // чужую гонку, и три матча подряд она решалась несколькими тиками
        if (raceLost(ctx) && ctx.pullers.isNotEmpty() && ctx.enemyFlag != null && !flagBlocked(ctx) &&
            ctx.myCreeps.none { it.id in blockerIds }) {
            val enemyArrival = enemyEscortArrivalObserved(ctx)
            val body = blockerBody()
            // путь считается по ПОЛЮ от клетки, куда крип родится: клетка самого спавна в поле непроходима, а прямая
            // до их флага на этой карте вдвое короче настоящего пути через центральные коридоры
            val blockFlow = flowTo("enemyFlag", ctx.enemyFlag, ctx.blocked, 1, ttl = 10)
            val fromCell = freeNeighbourCell(blockFlow, spawn.x, spawn.y)
            val walk = if (fromCell < 0) -1 else blockFlow[fromCell]
            val ready = if (walk < 0) Int.MAX_VALUE / 4 else ticksToAccumulate(bodyCost(body) - energy, inc) + spawnTicks(body) + walk
            if (ready < enemyArrival) {
                if (energy >= bodyCost(body)) {
                    val r = spawn.spawnCreep(body)
                    if (r.`object` != null) {
                        spawnedLastTick = true
                        r.`object`?.let { blockerIds.add(it.id) }
                        println("spawn t=$now: blocker ${summaryOf(body)} — race lost (ours ${ourArrival(ctx)} vs theirs $enemyArrival), their flag in $walk steps, ready in $ready")
                        return
                    }
                } else {
                    if (DEBUG_LOG && now % LOG_EVERY == 0) println("spawn t=$now: saving blocker e=$energy/${bodyCost(body)} ready=$ready enemyArrival=$enemyArrival")
                    return
                }
            }
        }

        // гонка проиграна по расчёту, и план выше не нашёл, чем её ускорить, — деньги идут в перехватчика: тело,
        // которое
        // успевает дойти до их поезда и убить ТЯГАЧА (тысяча хитов, без оружия) прежде, чем их эскорт придёт на флаг.
        // Смерть тягача возвращает им пеший период — вдвое. Копить на полное тело здесь бессмысленно: в матче 2 бот
        // держал «saving fighter 980» при доходе 2 в тик, то есть 438 тиков, при матче, который решался за 250
        if (raceLost(ctx)) {
            val target = strikeTarget(ctx)
            val enemyArrival = enemyEscortArrivalObserved(ctx)
            if (target != null && enemyArrival < Int.MAX_VALUE / 8) {
                // путь оценивается по Чебышеву от спавна до цели — нижняя граница, поля к ней сейчас может не быть вовсе
                val walk = getRange(spawn, target)
                var best: Array<BodyPartType>? = null
                var bestReady = Int.MAX_VALUE
                var b = cost(MOVE) + cost(RANGED_ATTACK)
                while (b <= fullBudget) {
                    val body = fighterBody(b)
                    b += 200
                    if (body == null) continue
                    val dps = body.count { it == RANGED_ATTACK } * RANGED_ATTACK_POWER.toDouble()
                    if (dps <= 0.0) continue
                    val ready = ticksToAccumulate(bodyCost(body) - energy, inc) + spawnTicks(body) + walk + (target.hits / dps).toInt()
                    if (ready < bestReady) { bestReady = ready; best = body }
                }
                if (best != null && bestReady < enemyArrival) {
                    if (energy >= bodyCost(best)) {
                        order(spawn, best, "fighter", "race lost (ours ${ourArrival(ctx)} vs theirs $enemyArrival) — interceptor for ${bodySummary(target)}h${target.hits}, ready in $bestReady")
                        return
                    }
                    if (DEBUG_LOG && now % LOG_EVERY == 0) println("spawn t=$now: saving interceptor ${summaryOf(best)} e=$energy/${bodyCost(best)} race lost (ours ${ourArrival(ctx)} vs theirs $enemyArrival) ready=$bestReady")
                    return
                }
            }
        }

        // пока боевого врага не видно, копить на бойца незачем — деньги идут в доход, если он окупается за остаток матча
        // (в первом живом матче бот семьсот тиков держал «saving fighter e=249/980» при доходе 1 в тик и пустом поле)
        if (requiredPower(ctx) <= 0.0 && ctx.harvesters.isEmpty() && ctx.homeSource != null && ctx.homeSource.energy > 0) {
            // из тел под разные бюджеты берётся то, что окупается БЫСТРЕЕ всех (ожидание накопления + стоимость делить на
            // прирост): до окупаемости деньги заморожены, а после работают, и дешёвый добытчик поднимает доход, на котором
            // следующий копится быстрее. По «отдаче за остаток матча» выигрывал M5C5W5 за 1000 — тысяча тиков без спавна
            val d = harvestCommute(ctx)
            var best: Array<BodyPartType>? = null
            var bestPayback = Double.MAX_VALUE
            var bestGain = 0.0
            var b = 200
            while (b <= fullBudget) {
                val hb = harvesterBody(b, d)
                b += 100
                if (hb == null) continue
                val gain = harvesterIncome(hb.count { it == WORK }, hb.count { it == CARRY }, hb.count { it == MOVE }, d)
                if (gain <= 0.0) continue
                val cost = bodyCost(hb)
                val payback = ticksToAccumulate(cost - energy, inc) + spawnTicks(hb) + cost / gain
                val left = arenaInfo.ticksLimit - now - payback
                if (left <= 0) continue // до конца матча не окупится — не берём
                if (payback < bestPayback) { bestPayback = payback; best = hb; bestGain = gain }
            }
            if (best != null) {
                if (energy >= bodyCost(best)) { order(spawn, best, "harvester", "no enemy in sight: +${bestGain.toInt()}/tick, pays for itself in ${bestPayback.toInt()} ticks"); return }
                if (DEBUG_LOG && now % LOG_EVERY == 0) println("spawn t=$now: saving harvester ${summaryOf(best)} e=$energy/${bodyCost(best)} gain=${bestGain.toInt()}/tick payback=${bestPayback.toInt()}")
                return
            }
        }

        // конвой: лекарь каждый третий (первый — стрелок: убивать то, что стреляет, важнее лечить)
        val squad = ctx.fighters.size + ctx.healers.size
        val wantHealer = squad >= HEALER_EVERY - 1 && ctx.healers.size < (squad + 1) / HEALER_EVERY
        val full = if (wantHealer) healerBody(fullBudget) else fighterFull
        if (full == null) return
        val fullCost = bodyCost(full)
        if (energy >= fullCost) {
            order(spawn, full, if (wantHealer) "healer" else "fighter", "full threat=$threat income=${inc.toInt()} enemyMelee=${enemyMeleeDominant(ctx)}")
            return
        }
        // урезанное тело под угрозой — только через closesDeficit выше: тело «хоть какое-то» (T2M4R2 за 520) умирало первым и
        // оставляло эскорт без полного бойца (стенд rush v10)
        val wait = ticksToAccumulate(fullCost - energy, inc)
        if (DEBUG_LOG && now % LOG_EVERY == 0) println("spawn t=$now: saving ${if (wantHealer) "healer" else "fighter"} e=$energy/$fullCost wait=$wait threat=$threat income=${inc.toInt()}")
    }

    private fun order(spawn: StructureSpawn, body: Array<BodyPartType>, role: String, why: String) {
        val r = spawn.spawnCreep(body)
        val err = r.error
        if (r.`object` != null) {
            spawnedLastTick = true
            println("spawn t=${getTicks()}: $role ${summaryOf(body)} cost=${bodyCost(body)} — $why")
        } else if (DEBUG_LOG) println("spawn t=${getTicks()}: $role ${summaryOf(body)} FAILED err=$err — $why")
    }

    // ==================== эскорт и поезд ====================

    /** Тела поезда: вес и живые MOVE эскорта плюс живые MOVE тягачей (их вес — ноль). */
    private fun trainMoves(escort: Creep, pullers: List<Creep>): Int = liveMoves(escort) + pullers.sumOf { liveMoves(it) }

    /** Период поезда на клетке: ceil(вес × ставка / (2 × ΣMOVE)), не меньше одного тика. */
    private fun trainPeriod(weight: Int, moves: Int, swamp: Boolean): Int {
        if (moves <= 0) return Int.MAX_VALUE / 4
        val rate = if (swamp) 10 else 2
        return maxOf(1, (weight * rate + 2 * moves - 1) / (2 * moves))
    }

    /** Тики пути эскорта по его полю с данным ΣMOVE поезда (по местности каждой клетки маршрута). */
    private fun routeTicks(escort: Creep, flow: IntArray, moves: Int): Int {
        var cell = escort.x * 100 + escort.y
        if (flow[cell] < 0) return Int.MAX_VALUE / 4
        val weight = bodyWeight(escort)
        var ticks = 0
        var steps = 0
        while (flow[cell] > 0 && steps < 400) {
            val cx = cell / 100
            val cy = cell % 100
            var best = -1
            var bestFlow = flow[cell]
            for (dx in -1..1) for (dy in -1..1) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
                val f = flow[nx * 100 + ny]
                if (f in 0 until bestFlow) { bestFlow = f; best = nx * 100 + ny }
            }
            if (best < 0) break
            cell = best
            steps++
            ticks += trainPeriod(weight, moves, DistanceMap.isSwamp(cell / 100, cell % 100))
        }
        return ticks
    }

    /**
     * Урон, после которого крип теряет ещё один живой MOVE. Части умирают С ГОЛОВЫ тела (хиты раздаются с хвоста,
     * `_recalc-body.js:10-18`), а в теле эскорта `MTTTT`×10 MOVE стоят первыми — поэтому первые СТО единиц урона
     * отнимают MOVE и уводят поезд с периода 2 на 3. Считается по живым частям, а не по раскладке «как обычно».
     */
    private fun damageToNextMoveLoss(c: Creep): Int {
        var sum = 0
        for (p in c.body) {
            if (p.hits <= 0) continue
            sum += p.hits
            if (p.type == MOVE) return sum
        }
        return Int.MAX_VALUE / 4
    }

    /** Тики пути, где с тика `slowAt` ΣMOVE поезда падает до `movesAfter` (замедление наступает по ходу, а не сразу). */
    private fun routeTicksSlowed(escort: Creep, flow: IntArray, moves: Int, slowAt: Int, movesAfter: Int): Int {
        var cell = escort.x * 100 + escort.y
        if (flow[cell] < 0) return Int.MAX_VALUE / 4
        val weight = bodyWeight(escort)
        var ticks = 0
        var steps = 0
        while (flow[cell] > 0 && steps < 400) {
            val cx = cell / 100
            val cy = cell % 100
            var best = -1
            var bestFlow = flow[cell]
            for (dx in -1..1) for (dy in -1..1) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
                val f = flow[nx * 100 + ny]
                if (f in 0 until bestFlow) { bestFlow = f; best = nx * 100 + ny }
            }
            if (best < 0) break
            cell = best
            steps++
            ticks += trainPeriod(weight, if (ticks >= slowAt) movesAfter else moves, DistanceMap.isSwamp(cell / 100, cell % 100))
        }
        return ticks
    }

    /**
     * Клетки цепи вперёд по полю: первая — следующая клетка от точки отсчёта, каждая следующая — от предыдущей.
     * Точка отсчёта — эскорт, а когда он в этот тик шагает — клетка, в которую он шагает: цепь строится там, где
     * эскорт БУДЕТ, иначе тягач целится в клетку, которую эскорт займёт сам, и они спорят за неё каждый тик.
     */
    private fun chainCells(ctx: Ctx, flow: IntArray, n: Int, from: Position? = null): List<Position> {
        val escort = ctx.escort ?: return emptyList()
        val out = ArrayList<Position>()
        var x = from?.x ?: escort.x
        var y = from?.y ?: escort.y
        for (i in 0 until n) {
            val next = DistanceMap.flowStep(flow, x, y, 0, emptySet(), ctx.enemyPositions) ?: break
            out.add(next)
            x = next.x
            y = next.y
        }
        return out
    }

    private fun dirTo(from: Position, to: Position) = screeps.api.getDirection(to.x - from.x, to.y - from.y)

    private fun selfStep(ctx: Ctx, escort: Creep, flow: IntArray) {
        val step = DistanceMap.flowStep(flow, escort.x, escort.y, 0, ctx.occupantAt.keys, ctx.enemyPositions)
        if (step != null) TrafficManager.request(escort, step, ESCORT_PRIORITY)
    }

    /**
     * Эскорт идёт к флагу. Без тягачей (или у самого флага) — сам, по полю, толкая своих. С тягачами — поездом: тягачи
     * встают на клетки цепи впереди эскорта (первый — на его следующую клетку, второй — на следующую от неё); когда все
     * на местах и ни у кого нет усталости, голова шагает дальше по полю, каждый тягач тянет заднего, а задний шагает в
     * его клетку (все интенты — прямые, мимо TrafficManager, и переподаются каждый тик). Пока тягачи строятся, эскорт
     * идёт сам, пока шаг дешевле задержки прицепки, которую он этим шагом создаёт (расчёт — в теле метода), и стоит
     * неподвижно, когда дороже; ожидание «на всякий случай» стоило живых матчей.
     */
    private fun runTrain(ctx: Ctx) {
        trainMoved.clear()
        yieldCells = emptySet()
        trainRolling = false
        val escort = ctx.escort ?: return
        val flow = ctx.escortFlow ?: return
        healAndShootOne(escort, ctx, focusOf(ctx, listOf(escort)))
        // проверка прошлого «поехали» результатом (см. PULL_FAILS_MAX): эскорт в клетке тягача И без усталости — иначе
        // либо не сдвинулся, либо шагнул своим ходом (усталость хода не ушла тягачу — буксировки не было; без этого
        // признака стенд без pull выглядел как «поезд едет», только вчетверо медленнее, и тягачи покупались дальше)
        if (expectedEscortCell >= 0) {
            if (escort.x * 100 + escort.y == expectedEscortCell && escort.fatigue == 0) pullFails = 0
            else {
                pullFails++
                println("train t=${getTicks()}: pull FAILED — escort at (${escort.x},${escort.y}) fatigue=${escort.fatigue}, expected (${expectedEscortCell / 100},${expectedEscortCell % 100}) with fatigue 0; fails=$pullFails")
                if (pullFails >= PULL_FAILS_MAX && !pullBroken) { pullBroken = true; println("train t=${getTicks()}: PULL BROKEN in this arena — no more pullers, the escort walks by itself, pullers become a screen") }
            }
            expectedEscortCell = -1
        }
        val here = flow[escort.x * 100 + escort.y]
        if (here == 0) return // на флаге
        val pullers = ctx.pullers.filter { canMove(it) }
        // цепь — из тягачей РЯДОМ с эскортом (в JOIN_RANGE), по близости; далёкие идут к хвосту цепи и встраиваются,
        // когда дойдут: с цепью «все тягачи разом» второй тягач, рождённый в сорока клетках, сорок тиков держал «далеко»,
        // эскорт шёл своим ходом мимо первого и ушёл в болото с усталостью 380 (стенд none, t=200)
        val near = pullers.filter { getRange(it, escort) <= JOIN_RANGE }.sortedWith(compareBy({ getRange(it, escort) }, { it.id.length }, { it.id })).take(MAX_CHAIN)
        val far = pullers.filter { it !in near }
        if (!USE_PULL || pullBroken || pullers.isEmpty() || here <= TRAIN_DISSOLVE_RANGE) {
            selfStep(ctx, escort, flow)
            if (pullBroken) for (p in pullers) { // заслон: вплотную к эскорту, как лекарь к подопечному
                val step = bestHealerMove(p, escort, ctx.toEscortFlow ?: flowNear(ctx, escort), ctx.combatEnemies.any { getRange(p, it) <= RANGED_RANGE + 1 }, ctx, ctx.combatEnemies.filter { hasMelee(it) })
                if (step != null) TrafficManager.request(p, step, HEALER_PRIORITY)
            }
            return
        }
        // далёкие тягачи — к клетке за хвостом цепи (поле полное, болото по цене равнины: тягач весит ноль)
        val tailCells = chainCells(ctx, flow, near.size + far.size)
        for ((k, p) in far.withIndex()) {
            val slot = tailCells.getOrNull(near.size + k) ?: tailCells.lastOrNull() ?: continue
            if (p.x == slot.x && p.y == slot.y) continue
            val f = flowTo("slot", slot, ctx.blocked, 1, ttl = 1)
            val step = DistanceMap.flowStep(f, p.x, p.y, 0, ctx.occupantAt.keys, ctx.enemyPositions)
            if (step != null) TrafficManager.request(p, step, PULLER_PRIORITY)
        }
        if (near.isEmpty()) { selfStep(ctx, escort, flow); return }
        val chain = near
        val n = chain.size
        val cells = chainCells(ctx, flow, n + 1)
        if (cells.size < n) { selfStep(ctx, escort, flow); return }
        val headNext = if (cells.size > n) cells[n] else null
        val inPlace = chain.indices.all { i -> chain[i].x == cells[i].x && chain[i].y == cells[i].y }
        val head = chain[n - 1]
        val rested = escort.fatigue == 0 && chain.all { it.fatigue == 0 }
        val slots = HashSet<Int>()
        for (c in cells) slots.add(c.x * 100 + c.y)
        yieldCells = slots
        if (headNext != null) ctx.blockedSet.add(headNext.x * 100 + headNext.y)
        val headNextOcc = headNext?.let { ctx.occupantAt[it.x * 100 + it.y] }
        if (inPlace) {
            // связки — КАЖДЫЙ тик, и в ожидании тоже: MOVE буксируемых гасят усталость головы только пока связка есть
            // (_add-fatigue.js:24-26), а связка живёт один тик и ставится интентом pull (addPulling); рвётся она лишь
            // шагом буксируемого не в клетку тягача (movement.js:176-181), стоящий буксируемый её не рвёт
            val codes = StringBuilder()
            for (i in n - 1 downTo 0) {
                val pulled: Creep = if (i == 0) escort else chain[i - 1]
                val rcPull = chain[i].pull(pulled)
                codes.append(" pull$i=$rcPull")
            }
            if (headNext != null && rested && headNextOcc == null) {
                // поезд едет: голова — дальше по полю, каждый буксируемый шагает в клетку своего тягача
                val rcHead = head.move(dirTo(head, headNext))
                codes.append(" head=$rcHead")
                for (i in n - 1 downTo 0) {
                    val pulled: Creep = if (i == 0) escort else chain[i - 1]
                    val rcMove = pulled.move(dirTo(pulled, chain[i]))
                    codes.append(" move$i=$rcMove")
                }
                trainMoved.add(escort.id)
                for (c in chain) trainMoved.add(c.id)
                trainRolling = true
                expectedEscortCell = chain[0].x * 100 + chain[0].y
                if (DEBUG_LOG && (trainLogLeft > 0 || getTicks() % 50 == 0)) {
                    trainLogLeft--
                    println("train t=${getTicks()}: escort (${escort.x},${escort.y}) f=${escort.fatigue} -> (${chain[0].x},${chain[0].y}); head (${head.x},${head.y}) f=${head.fatigue} -> (${headNext.x},${headNext.y}) " +
                        "moves=${trainMoves(escort, chain)} period=${trainPeriod(bodyWeight(escort), trainMoves(escort, chain), DistanceMap.isSwamp(chain[0].x, chain[0].y))} codes:$codes")
                }
                return
            }
            trainMoved.add(escort.id)
            for (c in chain) trainMoved.add(c.id) // стоим в сцепке: TrafficManager поезд не двигает и не толкает
            if (DEBUG_LOG && (trainLogLeft > 0 && getTicks() % 5 == 0)) {
                println("train t=${getTicks()}: linked, waiting — escort (${escort.x},${escort.y}) f=${escort.fatigue} head (${head.x},${head.y}) f=${head.fatigue} headNext=${headNext?.let { "(${it.x},${it.y})" } ?: "-"} occ=${headNextOcc?.id ?: "-"} codes:$codes")
            }
            return
        }
        // Сбор цепи. Тягачи идут на свои клетки (толкая бойцов); поле к клетке — полное и с болотом по цене равнины:
        // тягач из одних MOVE весит ноль и болота не замечает, а ограниченное поле (30) не доставало до спавна, и
        // тягач, рождённый при эскорте в сорока клетках, не трогался с места.
        // ЭСКОРТ ПРИ ЭТОМ НЕ СТОИТ ПРОСТО ТАК — стояние тоже имеет цену, и она считается, а не назначается: шаг сейчас
        // даёт клетку, которую поезд проехал бы за pTrain тиков, и стоит задержки старта поезда на (pSelf − k) тиков,
        // где k — тиков цепи до её клеток, взятых из того же поля, которым тягачи и ходят. Идём, пока выигрыш больше
        // задержки; на равнине (pTrain 2, pSelf 4) это значит «идём, пока цепь не соберётся раньше чем через три тика».
        var chainEta = 0
        var blockedByEscort = false
        for (i in chain.indices) {
            val p = chain[i]
            val slot = cells[i]
            if (p.x == slot.x && p.y == slot.y) continue
            val f = flowTo("slot", slot, ctx.blocked, 1, ttl = 1)
            val d = f[p.x * 100 + p.y]
            chainEta = maxOf(chainEta, if (d >= 0) d else JOIN_RANGE * 2)
            // ⚠️ Тягач, чей единственный путь к слоту идёт ЧЕРЕЗ клетку стоящего эскорта, не придёт никогда: раньше его
            // пропускал обмен местами (эскорт отлетал на клетку назад), а обмен запрещён. Тогда «подожду, цепь вот-вот
            // соберётся» превращается в вечное стояние — и это не теория: живой матч 6a9b335e простоял так до 2000-го
            // тика и кончился ничьёй с соперником, который свой эскорт не двигал вовсе. Расстояние по полю этого не
            // видит (поле знает стены, а не крипов), поэтому спрашиваем сам шаг
            val step = DistanceMap.flowStep(f, p.x, p.y, 0, ctx.occupantAt.keys, ctx.enemyPositions)
            if (step != null && step.x == escort.x && step.y == escort.y) blockedByEscort = true
        }
        val ahead = cells.getOrNull(0)
        var escortStepping = false
        if (ahead != null && escort.fatigue == 0 && chainEta > 0) {
            val weight = bodyWeight(escort)
            val swamp = DistanceMap.isSwamp(ahead.x, ahead.y)
            val pSelf = trainPeriod(weight, liveMoves(escort), swamp)
            val pTrain = trainPeriod(weight, trainMoves(escort, chain), swamp)
            // при РАВЕНСТВЕ выигрыша и задержки идём: по тикам это ничья, а по риску — нет, потому что стоящий эскорт
            // и есть то состояние, из которого вырастают заторы, а идущий их не создаёт
            escortStepping = blockedByEscort || pTrain >= maxOf(0, pSelf - chainEta)
        }
        // тягач целится туда, где эскорт БУДЕТ: иначе он идёт в клетку, которую эскорт занимает сам, и оба спорят за неё
        val slotCells = if (escortStepping) chainCells(ctx, flow, chain.size + 1, ahead) else cells
        for (i in chain.indices) {
            val p = chain[i]
            val slot = slotCells.getOrNull(i) ?: cells[i]
            if (p.x == slot.x && p.y == slot.y) continue
            val f = flowTo("slot", slot, ctx.blocked, 1, ttl = 1)
            // занятые клетки — ОБХОДИТЬ: тягач, рождённый позади, шёл к слоту сквозь эскорта и полагался на то, что
            // диспетчер поменяет их местами, то есть откатит эскорта на клетку назад. Обход стоит тягачу тик, откат
            // стоит эскорту два, и это его единственная работа в матче
            val step = DistanceMap.flowStep(f, p.x, p.y, 0, ctx.occupantAt.keys, ctx.enemyPositions)
            if (step != null) TrafficManager.request(p, step, PULLER_PRIORITY)
        }
        // Сторож на весь класс «сборка не двигается с места». Причину, найденную в живом матче, мы устранили выше, но
        // цена ошибки здесь — весь матч (ничья на 2000 тиков), поэтому у неподвижности есть предел.
        // ⚠️ Считается ИМЕННО неподвижность всей картины, а не стояние эскорта: пока цепь подходит, эскорт стоит
        // законно — поезд с двумя тягачами идёт клетку в тик, и дождаться его выгоднее любого шага. Первая редакция
        // сторожа смотрела только на эскорта, срабатывала 29 раз за матч там, где голова цепи просто гасила усталость
        // после болота, и гнала эскорт вперёд из выгодного ожидания — сценарий guard подорожал с 380 тиков до 405
        // усталость — ЧАСТЬ признака: пока она тает, система работает, даже если никто не сместился ни на клетку.
        // Голова цепи после болота гасит 400 единиц десять тиков подряд, и без этого слагаемого сторож считал такое
        // ожидание залипанием (55 срабатываний за матч) и выталкивал эскорт из выгодной прицепки
        val stallKey = escort.x * 100 + escort.y + 10000 * chainEta + chain.sumOf { it.x * 100 + it.y } +
            1000000 * (escort.fatigue + chain.sumOf { it.fatigue })
        if (!escortStepping && stallKey == escortStallKey) {
            escortStallTicks++
            if (escortStallTicks > TRAIN_STALL_TICKS) {
                escortStepping = true
                println("train t=${getTicks()}: STALLED — nothing has moved for $escortStallTicks ticks: escort (${escort.x},${escort.y}) f=${escort.fatigue}, eta=$chainEta, " +
                    "pullers=${chain.joinToString(" ") { "${it.id}(${it.x},${it.y})f=${it.fatigue}" }}; walking on")
            }
        } else escortStallTicks = 0
        escortStallKey = stallKey
        if (escortStepping) selfStep(ctx, escort, flow)
        // а если стоит — то СТОИТ: без этого диспетчер считает неподвижного эскорта свободным и меняет его местами с
        // тягачом, обходящим его к слоту; в живом матче эскорт так уехал на клетку НАЗАД (t=30 (14,84) -> t=40 (13,85))
        else trainMoved.add(escort.id)
        // замер обхода World (game/creeps.js:135-142): move(объект крипа) минует ERR_TIRED — в типах Arena этого нет,
        // код возврата в живом матче решит, можно ли тащить усталого эскорта; тягач в этот тик не едет, интент безвреден
        if (!pullBypassTested && escort.fatigue > 0 && getRange(escort, chain[0]) <= 1) {
            pullBypassTested = true
            val rc = escort.asDynamic().move(chain[0])
            println("pull bypass test t=${getTicks()}: escort.move(puller) with fatigue=${escort.fatigue} -> $rc (0 = World bypass lives in Arena; -10/-11 = it does not)")
        }
        if (DEBUG_LOG && trainLogLeft > 0 && getTicks() % 5 == 0) {
            println("train t=${getTicks()}: forming — escort (${escort.x},${escort.y}) f=${escort.fatigue} stepping=$escortStepping eta=$chainEta slots=${slotCells.joinToString(" ") { "(${it.x},${it.y})" }} " +
                "pullers=${chain.joinToString(" ") { "${it.id}(${it.x},${it.y})f=${it.fatigue}" }} rested=$rested headNextOcc=${headNextOcc?.id ?: "-"}")
        }
    }

    /** Кто из своих (не поезд) стоит на клетках цепи или клетке назначения головы и не уходит — шаг в сторону. */
    private fun enforceYield(ctx: Ctx) {
        if (yieldCells.isEmpty()) return
        for (c in ctx.active) {
            if (c.id in trainMoved || isEscort(c) || c in ctx.pullers || !canMove(c)) continue
            val here = c.x * 100 + c.y
            if (here !in yieldCells) continue
            val want = TrafficManager.desiredOf(c.id)
            if (want != null && want !in yieldCells) continue
            var best: Position? = null
            for ((dx, dy) in DIRECTIONS) {
                val x = c.x + dx; val y = c.y + dy
                val key = x * 100 + y
                if (!passable(x, y, ctx) || key in yieldCells || ctx.occupantAt.containsKey(key)) continue
                best = InfluenceMap.cell(x, y)
                break
            }
            if (best != null) TrafficManager.request(c, best, YIELD_PRIORITY)
        }
    }

    // ==================== добытчики ====================

    private fun runHarvesters(ctx: Ctx) {
        val spawn = ctx.mySpawn ?: return
        val source = ctx.homeSource ?: return
        for (h in ctx.harvesters) {
            val carrying = h.store[RESOURCE_ENERGY] ?: 0
            val capacity = h.store.getCapacity(RESOURCE_ENERGY) ?: 0
            val nearSource = getRange(h, source) <= 1
            val nearSpawn = getRange(h, spawn) <= 1
            // враг рядом — прочь, к спавну (добытчик безоружен, а спавн — за ним конвой не ходит)
            val danger = ctx.combatEnemies.filter { getRange(h, it) <= RANGED_RANGE + 1 }
            if (danger.isNotEmpty()) {
                val step = greedyFlee(ctx, h, danger, force = true)
                if (step != null) TrafficManager.request(h, step, HARVESTER_PRIORITY)
                if (nearSpawn && carrying > 0) { h.transfer(spawn, RESOURCE_ENERGY); transferredLastTick = true }
                continue
            }
            if (nearSource && nearSpawn) {
                if (source.energy > 0 && carrying < capacity) { h.harvest(source); harvestedLastTick += minOf(source.energy, h.body.count { it.type == WORK && it.hits > 0 } * HARVEST_POWER) }
                if (carrying > 0 && (spawn.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0) { h.transfer(spawn, RESOURCE_ENERGY); transferredLastTick = true }
                continue
            }
            val deliver = carrying >= capacity || (carrying > 0 && source.energy <= 0)
            if (deliver) {
                if (nearSpawn) { h.transfer(spawn, RESOURCE_ENERGY); transferredLastTick = true }
                else stepTo(h, spawn, 1, HARVESTER_PRIORITY, ctx, swampRatio(h))
            } else {
                if (nearSource) { if (source.energy > 0) { h.harvest(source); harvestedLastTick += minOf(source.energy, h.body.count { it.type == WORK && it.hits > 0 } * HARVEST_POWER) } }
                else stepTo(h, source, 1, HARVESTER_PRIORITY, ctx, swampRatio(h))
            }
        }
    }

    /** Шаг к цели по полю потока (полное поле, кэш по цели и цене болота). */
    private fun stepTo(creep: Creep, target: Position, range: Int, priority: Int, ctx: Ctx, swampCost: Int) {
        val flow = flowTo("to:${target.x},${target.y}:$swampCost", target, ctx.blocked, swampCost, ttl = 5)
        val step = DistanceMap.flowStep(flow, creep.x, creep.y, range, ctx.occupantAt.keys, ctx.enemyPositions)
        if (step != null) TrafficManager.request(creep, step, priority)
    }

    // ==================== безоружные обломки ====================

    /** Боец без оружия или лекарь без лечения: домой, к спавну, чтобы не занимать клетки конвоя. */
    private fun runHusks(ctx: Ctx) {
        val home = ctx.mySpawn ?: return
        val enemyFlag = ctx.enemyFlag
        for (h in ctx.husks) {
            if (!canMove(h)) continue
            if (h.id in blockerIds && enemyFlag != null) {
                if (h.x == enemyFlag.x && h.y == enemyFlag.y) continue // на месте — стоим намертво
                val flow = flowTo("enemyFlag", enemyFlag, ctx.blocked, 1, ttl = 10)
                val step = DistanceMap.flowStep(flow, h.x, h.y, 0, ctx.occupantAt.keys, ctx.enemyPositions)
                if (step != null) TrafficManager.request(h, step, HUSK_PRIORITY)
                continue
            }
            if (getRange(h, home) <= 2) continue
            stepTo(h, home, 2, HUSK_PRIORITY, ctx, swampRatio(h))
        }
    }

    // ==================== конвой ====================

    private fun runSquad(ctx: Ctx) {
        val escort = ctx.escort
        val fighters = ctx.fighters
        val healers = ctx.healers
        if (fighters.isEmpty() && healers.isEmpty()) return

        val strike = decideStrike(ctx)
        val strikers = if (strike) fighters.filter { fullSpeed(it) } else emptyList()
        val guards = fighters.filter { it !in strikers }

        val focus = focusOf(ctx, fighters)
        // угрозы у эскорта — для бонуса прикрытия
        val threats = if (escort != null) ctx.combatEnemies.filter { getRange(it, escort) <= THREAT_RANGE } else emptyList()
        val nearestThreat = if (escort != null) threats.minByOrNull { getRange(it, escort) } else null
        val meleeEnemies = ctx.combatEnemies.filter { hasMelee(it) }

        for (f in fighters) {
            healAndShootOne(f, ctx, focus)
            if (!canMove(f)) continue
            val local = localGroup(f, ctx)
            val enemiesNear = ctx.combatEnemies.filter { getRange(f, it) <= LOCAL_ENEMY_RANGE }
            val inCombat = enemiesNear.any { getRange(f, it) <= RANGED_RANGE + 1 }
            val aggressive = updateAggression(f, local, enemiesNear)
            // добивают — к лекарю (если он есть), иначе стоять и стрелять
            val nearestHealer = healers.minByOrNull { getRange(f, it) }
            if (mustFlee(f, ctx) && nearestHealer != null) {
                val step = fleeStep(f, enemiesNear, dangerMatrix(ctx)) ?: bestSingleMove(f, nearestHealer, flowNear(ctx, nearestHealer), 1, false, inCombat, ctx, local, meleeEnemies, null)
                if (step != null) TrafficManager.request(f, step, FIGHTER_PRIORITY)
                continue
            }
            val strikeAt = strikeTarget(ctx)
            val striker = f in strikers && strikeAt != null
            // перехват: угрозу у эскорта встречают как можно раньше — стрелок при локальном перевесе или против чистого
            // мили (его кайтят с трёх клеток, эскорт кайтить не может: M7A7 снимает 5000 хитов за 24 тика вплотную, а
            // один M8R4 убивает его за 35 — бой надо начать за десятки клеток до эскорта); мили-боец — при перевесе
            val engage = !striker && nearestThreat != null && escort != null && getRange(f, escort) <= THREAT_RANGE &&
                (aggressive || (hasRanged(f) && hasMelee(nearestThreat) && !hasRanged(nearestThreat)))
            val target: Position = if (striker) strikeAt!! else if (engage) nearestThreat!! else escort ?: ctx.mySpawn ?: continue
            val flow = if (striker) flowTo("toStrike", strikeAt!!, ctx.blocked, 5, ttl = 1) else if (engage) flowNear(ctx, target) else (ctx.toEscortFlow ?: flowNear(ctx, target))
            val standoff = if (striker || (engage && hasRanged(f))) RANGED_RANGE else if (engage) 1 else GUARD_STANDOFF
            val cover = if (!striker && !engage && nearestThreat != null && escort != null) nearestThreat to escort else null
            val step = bestSingleMove(f, target, flow, standoff, aggressive, inCombat, ctx, local, meleeEnemies, cover)
            if (step != null) TrafficManager.request(f, step, FIGHTER_PRIORITY)
        }

        for (h in healers) {
            healAndShootOne(h, ctx, focus)
            if (!canMove(h)) continue
            val charge = chargeOf(h, ctx) ?: continue
            val enemiesNear = ctx.combatEnemies.filter { getRange(h, it) <= LOCAL_ENEMY_RANGE }
            val inCombat = enemiesNear.any { getRange(h, it) <= RANGED_RANGE + 1 }
            if (mustFlee(h, ctx) && ctx.active.none { it !== h && !isEscort(it) && getRange(h, it) <= HEAL_RANGE }) {
                val step = fleeStep(h, enemiesNear, dangerMatrix(ctx))
                if (step != null) { TrafficManager.request(h, step, HEALER_PRIORITY); continue }
            }
            val flow = if (charge === escort && ctx.toEscortFlow != null) ctx.toEscortFlow else flowNear(ctx, charge)
            val step = bestHealerMove(h, charge, flow, inCombat, ctx, meleeEnemies)
            if (step != null) TrafficManager.request(h, step, HEALER_PRIORITY)
        }
        if (guards.isEmpty() && strikers.isNotEmpty() && DEBUG_LOG && getTicks() % LOG_EVERY == 0) println("strike t=${getTicks()}: all ${strikers.size} fighters on the enemy escort")
    }

    /** Подопечный лекаря: самый битый союзник в HEAL_RANGE+1 (эскорт при равенстве), без раненых — эскорт (или ближайший боец). */
    private fun chargeOf(h: Creep, ctx: Ctx): Creep? {
        val escort = ctx.escort
        val candidates = ctx.active.filter { it !== h && it.body.none { p -> p.type == WORK } }
        val hurt = candidates.filter { it.hits < it.hitsMax && getRange(h, it) <= HEAL_RANGE + 1 }
        if (hurt.isNotEmpty()) return hurt.maxByOrNull { (it.hitsMax - it.hits) + (if (it === escort) 1 else 0) }
        if (escort != null) return escort
        return candidates.filter { hasWeapon(it) }.minByOrNull { getRange(h, it) } ?: candidates.minByOrNull { getRange(h, it) }
    }

    /** Локальная группа бойца: вооружённые и лечащие свои в LOCAL_ALLY_RANGE, и эскорт (его хиты — наш запас в бою, см. powerOf). */
    private fun localGroup(f: Creep, ctx: Ctx): List<Creep> =
        ctx.active.filter { (isEscort(it) || hasWeapon(it) || hasHeal(it)) && getRange(f, it) <= LOCAL_ALLY_RANGE }

    private fun updateAggression(f: Creep, local: List<Creep>, enemiesNear: List<Creep>): Boolean {
        if (enemiesNear.isEmpty()) { aggressiveIds.remove(f.id); return true }
        val ours = powerOf(local, enemiesNear)
        val theirs = powerOf(enemiesNear, local)
        val was = f.id in aggressiveIds
        val now = if (theirs <= 0.0) true else if (was) ours >= theirs * AGGR_EXIT else ours >= theirs * AGGR_ENTER
        if (now) aggressiveIds.add(f.id) else aggressiveIds.remove(f.id)
        return now
    }

    /** Добивают: меньше трети хитов и за прошлый тик снято больше половины остатка. */
    private fun mustFlee(c: Creep, ctx: Ctx): Boolean {
        val prev = lastHits[c.id] ?: c.hits
        val lost = prev - c.hits
        if (c.hits >= c.hitsMax * FLEE_HITS_SHARE) return false
        val incoming = maxOf(lost.toDouble(), InfluenceMap.netDamageAt(c.x, c.y, ctx.combatEnemies, ctx.active))
        return incoming * 2 > c.hits
    }

    // ---------- удар по эскорту врага ----------

    /** Решение об ударе — расчёт по состоянию: дорога + бой с охраной + добивание 5000 хитов под их лечением должны
     *  уложиться до прихода их эскорта на флаг и до смерти нашего под теми, кто у него сейчас; охрану бьём с перевесом
     *  STRIKE_RATIO. Гистерезис: начатый удар продолжается, пока наш эскорт доживает до его конца. */
    /**
     * Цель удара: эскорт врага или его тягач — что быстрее сорвёт их гонку. Тягач стоит убивать, когда он у них есть:
     * тысяча хитов против пяти тысяч, и его смерть отнимает у них ровно столько же — половину скорости. Возвращает
     * цель и её хиты; null — целей нет.
     */
    private fun strikeTarget(ctx: Ctx): Creep? {
        val escort = ctx.enemyEscort ?: return null
        val pullers = enemyPullers(ctx)
        if (pullers.isEmpty()) return escort
        // выбираем по хитам: тягач дешевле эскорта во столько же раз, во сколько отнимает скорости
        return pullers.minByOrNull { it.hits } ?: escort
    }

    private fun decideStrike(ctx: Ctx): Boolean {
        val target = strikeTarget(ctx)
        val strikers = ctx.fighters.filter { fullSpeed(it) }
        if (target == null || strikers.isEmpty() || ctx.enemyEscortFlow == null) { striking = false; return false }
        val flow = flowTo("toEnemyEscort", target, ctx.blocked, 5, ttl = 1)
        val travel = strikers.maxOf { pathTicks(it, flow, it.x * 100 + it.y) }
        val guards = ctx.combatEnemies.filter { getRange(it, target) <= GUARD_RANGE }
        val ourDps = strikers.sumOf { effectiveDps(it, guards + target) }
        val guardHeal = guards.sumOf { InfluenceMap.profileOf(it).heal }
        val enemyHealOnEscort = ctx.combatEnemies.sumOf { e ->
            val d = getRange(e, target)
            val h = InfluenceMap.profileOf(e).heal
            if (d <= 1) h else if (d <= HEAL_RANGE) h * RANGED_HEAL_POWER / HEAL_POWER else 0.0
        }
        val guardFightTicks = if (guards.isEmpty()) 0 else if (ourDps - guardHeal <= 0.0) Int.MAX_VALUE / 4 else (guards.sumOf { it.hits } / (ourDps - guardHeal)).toInt() + 1
        val net = ourDps - enemyHealOnEscort
        val killTicks = if (net <= 0.0) Int.MAX_VALUE / 4 else (target.hits / net).toInt() + 1
        val total = travel.toLong() + guardFightTicks + killTicks
        val enemyArrival = enemyEscortArrival(ctx)
        // Удар окупается ЗАДОЛГО до убийства. Части умирают с головы тела, а у эскорта MOVE стоят первыми, поэтому
        // первые сто хитов отнимают его поезду MOVE и уводят период с 2 на 3 — весь остаток пути дорожает в полтора
        // раза. Добить 5000 под лечением стоит десятков тиков огня, снять сотню — одного, и цель удара считается по
        // тому, что наступает раньше и решает гонку: их приход ПОСЛЕ нашего
        val slowTicks = if (net <= 0.0) Int.MAX_VALUE / 4 else (damageToNextMoveLoss(target) / net).toInt() + 1
        val slowTotal = travel.toLong() + guardFightTicks + slowTicks
        val enemyMoves = trainMoves(target, enemyPullersOf(ctx, target))
        val enemyArrivalSlowed = if (slowTotal >= Int.MAX_VALUE / 4L || enemyMoves <= 1) enemyArrival
            else routeTicksSlowed(target, ctx.enemyEscortFlow, enemyMoves, slowTotal.toInt(), enemyMoves - 1)
        val ours = ourArrival(ctx)
        val slowWins = slowTotal < enemyArrival && enemyArrivalSlowed > ours
        val reach = if (total < enemyArrival) total else slowTotal
        val ourEscortDeath = escortDeathTicks(ctx, minOf(reach, Int.MAX_VALUE / 4L).toInt())
        val powerOk = guards.isEmpty() || powerOf(strikers, guards) >= powerOf(guards, strikers) * STRIKE_RATIO
        // гонка проиграна — удар это единственное, что её меняет, и ждать «запаса» уже не на что
        val lost = raceLost(ctx)
        val margin = if (striking || lost) 0 else STRIKE_MARGIN
        val feasible = (lost || total < enemyArrival - margin || slowWins) && reach < ourEscortDeath - margin && powerOk
        val prev = striking
        striking = feasible
        if (striking && !prev) strikeSince = getTicks()
        if (DEBUG_LOG && (striking != prev || getTicks() % BODIES_EVERY == 0)) {
            println("strike t=${getTicks()}: ${if (striking) "GO" else "no"} travel=$travel guards=${guards.size} guardFight=$guardFightTicks kill=$killTicks total=$total " +
                "slow=$slowTicks(dmg=${damageToNextMoveLoss(target)}) slowTotal=$slowTotal slowedArrival=$enemyArrivalSlowed ourArrival=$ours slowWins=$slowWins " +
                "enemyArrival=$enemyArrival ourEscortDeath=$ourEscortDeath dps=${ourDps.toInt()} heal=${enemyHealOnEscort.toInt()} powerOk=$powerOk strikers=${strikers.size}")
        }
        return striking
    }

    /** Тягачи врага: его тела из одних MOVE в двух клетках от его эскорта. */
    private fun enemyPullersOf(ctx: Ctx, target: Creep): List<Creep> =
        ctx.enemyCreeps.filter { !isEscort(it) && it.body.all { p -> p.type == MOVE } && getRange(it, target) <= 2 }

    /** Приход эскорта врага на его флаг — с его поездом: тягачи врага — его тела из одних MOVE в двух клетках от эскорта. */
    private fun enemyEscortArrival(ctx: Ctx): Int {
        val target = ctx.enemyEscort ?: return Int.MAX_VALUE / 4
        val flow = ctx.enemyEscortFlow ?: return Int.MAX_VALUE / 4
        return routeTicks(target, flow, trainMoves(target, enemyPullersOf(ctx, target)))
    }

    /**
     * Блокировщик: наш крип, стоящий НА клетке вражеского флага. Победа засчитывается эскорту, вставшему на флаг, а на
     * занятую клетку не встать — механику подтвердил стенд, где тягач соперника сам заперся на своём флаге. Стоит это
     * один MOVE (50 энергии) против четырёхсот за перехватчика и пяти тысяч хитов их эскорта.
     *
     * Покупается ТОЛЬКО после того, как куплена скорость (есть хотя бы один тягач), и только при проигранной гонке:
     * пятьдесят энергии на старте — это полсотни тиков задержки тягача при доходе в единицу, то есть двадцать пять
     * клеток эскорта. Порядок важен и по другой причине: пеший крип из одного MOVE идёт клетку в тик, вдвое быстрее
     * поезда, и, выйдя следом, он обгоняет его и уходит вперёд — а выпущенный до поезда он делит с ним узкий коридор и
     * запирает собственный эскорт (так первая попытка простояла в центре карты сто тиков).
     */
    private fun blockerBody(): Array<BodyPartType> = arrayOf(MOVE)

    /** На клетке вражеского флага уже стоит наш крип. */
    private fun flagBlocked(ctx: Ctx): Boolean {
        val flag = ctx.enemyFlag ?: return false
        return ctx.active.any { it.x == flag.x && it.y == flag.y }
    }

    /** Крипы, посланные занять вражеский флаг. */
    private val blockerIds = HashSet<String>()

    /** Тягачи ВРАГА: тела из одних MOVE рядом с его эскортом — они и держат его скорость. */
    private fun enemyPullers(ctx: Ctx): List<Creep> {
        val target = ctx.enemyEscort ?: return emptyList()
        return ctx.enemyCreeps.filter { !isEscort(it) && it.body.isNotEmpty() && it.body.all { p -> p.type == MOVE } && getRange(it, target) <= JOIN_RANGE }
    }

    /**
     * Наш приход на флаг с нынешним поездом (тиков) — та же мера, что и у врага, чтобы их можно было сравнивать.
     */
    private fun ourArrival(ctx: Ctx): Int {
        val escort = ctx.escort ?: return Int.MAX_VALUE / 4
        val flow = ctx.escortFlow ?: return Int.MAX_VALUE / 4
        return routeTicks(escort, flow, trainMoves(escort, ctx.pullers))
    }

    /**
     * Гонка проиграна по расчёту: их эскорт приходит раньше нашего. Тогда скорость больше ничего не решает, и деньги
     * должны идти в то, что меняет исход — прежде всего в убийство ИХ ТЯГАЧА: он безоружен, у M10 тысяча хитов против
     * пяти тысяч у эскорта, а его смерть возвращает их поезду пеший период (у эскорта из сорока TOUGH и десяти MOVE
     * это 4 тика на клетку вместо 2, вдвое). Матч 2 (04.09.2026) проигран двумя тиками, и всё это время бот копил на
     * полного бойца, который не успевал ни к чему.
     */
    private fun raceLost(ctx: Ctx): Boolean {
        val ours = ourArrival(ctx)
        if (ours >= Int.MAX_VALUE / 8) return false
        val theirs = enemyEscortArrivalObserved(ctx)
        return theirs < Int.MAX_VALUE / 8 && theirs <= ours
    }

    /** История дистанции эскорта врага до его флага: (тик, дистанция по его полю). */
    private val enemyEscortHist = ArrayDeque<Pair<Int, Int>>()

    /**
     * Приход эскорта врага ПО НАБЛЮДЕНИЮ: остаток пути делённый на его же темп за окно. Стоящий эскорт не приходит
     * никогда — и это не догадка, а измерение: в первом матче враг не сдвинул свой эскорт за весь лог. Пока истории
     * мало, берётся оценка по телу (enemyEscortArrival). Оценка самокорректируется: враг тронулся — темп появился
     * через несколько тиков, и план тут же возвращается к гонке (пересчёт раз в PLAN_EVERY тиков).
     */
    private fun enemyEscortArrivalObserved(ctx: Ctx): Int {
        val target = ctx.enemyEscort ?: return Int.MAX_VALUE / 4
        val flow = ctx.enemyEscortFlow ?: return Int.MAX_VALUE / 4
        val here = flow[target.x * 100 + target.y]
        if (here < 0) return Int.MAX_VALUE / 4
        val now = getTicks()
        if (enemyEscortHist.isEmpty() || enemyEscortHist.last().first != now) enemyEscortHist.addLast(now to here)
        while (enemyEscortHist.isNotEmpty() && enemyEscortHist.first().first < now - ESCORT_WATCH) enemyEscortHist.removeFirst()
        val (t0, d0) = enemyEscortHist.first()
        if (now - t0 < ESCORT_WATCH) return enemyEscortArrival(ctx) // истории мало — оценка по телу
        val rate = (d0 - here).toDouble() / (now - t0)
        return if (rate <= 0.0) Int.MAX_VALUE / 4 else (here / rate).toInt()
    }

    /** Через сколько тиков умрёт наш эскорт под боевыми врагами: теми, что у него сейчас (в THREAT_RANGE), и теми, что
     *  дойдут до него за horizon тиков (по их ходу вдоль поля к эскорту) — с их прихода; минус наше лечение рядом с ним;
     *  бесконечность — если лечение перекрывает урон или угроз нет. Удар по эскорту врага без этого уводил единственного
     *  бойца от эскорта, к которому шёл M7A7 (стенд melee: угроза в пути — не угроза «в десяти клетках»). */
    private fun escortDeathTicks(ctx: Ctx, horizon: Int = 0): Int {
        val escort = ctx.escort ?: return 0
        val flow = ctx.toEscortFlow
        val arrivals = ctx.combatEnemies.mapNotNull { e ->
            val near = getRange(e, escort) <= THREAT_RANGE
            val arrival = if (near) 0 else if (flow != null) pathTicks(e, flow, e.x * 100 + e.y) else Int.MAX_VALUE / 4
            if (arrival <= horizon) e to arrival else null
        }
        if (arrivals.isEmpty()) return Int.MAX_VALUE / 4
        val heal = ctx.healers.sumOf { h ->
            val parts = h.body.count { it.type == HEAL && it.hits > 0 }
            val d = getRange(h, escort)
            if (d <= 1) parts * HEAL_POWER.toDouble() else if (d <= HEAL_RANGE) parts * RANGED_HEAL_POWER.toDouble() else 0.0
        }
        // урон нарастает с приходом каждого: идём по приходам и списываем хиты
        var hits = escort.hits.toDouble()
        var t = 0
        var dps = 0.0
        val sorted = arrivals.sortedBy { it.second }
        var i = 0
        while (i < sorted.size) {
            val (e, arrival) = sorted[i]
            val net = dps - heal
            if (net > 0.0 && hits / net <= arrival - t) return t + (hits / net).toInt()
            if (net > 0.0) hits -= net * (arrival - t)
            t = arrival
            val p = InfluenceMap.profileOf(e)
            dps += p.melee + p.ranged
            i++
        }
        val net = dps - heal
        if (net <= 0.0) return Int.MAX_VALUE / 4
        return t + (hits / net).toInt()
    }

    // ---------- стрельба и лечение ----------

    /** Фокус-цель отряда: боевой враг в RANGED_RANGE+1 от любого из нас — убиваемый за тик первым, затем угроза на хит. */
    private fun focusOf(ctx: Ctx, ours: List<Creep>): Creep? {
        val reachable = ctx.combatEnemies.filter { e -> ours.any { getRange(it, e) <= RANGED_RANGE + 1 } }
        if (reachable.isEmpty()) return null
        val dpsInRange = { e: Creep -> ours.sumOf { o -> if (getRange(o, e) <= RANGED_RANGE) InfluenceMap.profileOf(o).ranged else 0.0 } + ours.sumOf { o -> if (getRange(o, e) <= 1) InfluenceMap.profileOf(o).melee else 0.0 } }
        val killable = reachable.filter { dpsInRange(it) >= it.hits }
        if (killable.isNotEmpty()) return killable.maxByOrNull { threatOf(it) }
        return reachable.maxByOrNull { threatOf(it) / maxOf(1, it.hits) }
    }

    private fun threatOf(e: Creep): Double {
        val p = InfluenceMap.profileOf(e)
        return p.melee + p.ranged + p.heal
    }

    /** Лечение и стрельба одного крипа: лекарь лечит самого нуждающегося (вплотную — heal, дальше — rangedHeal),
     *  стрелок бьёт фокус/убиваемого/эскорт врага; мили — соседа. */
    private fun healAndShootOne(creep: Creep, ctx: Ctx, focus: Creep?) {
        val healParts = creep.body.count { it.type == HEAL && it.hits > 0 }
        if (healParts > 0) {
            val candidates = ctx.active.filter { getRange(creep, it) <= HEAL_RANGE && (it.hits < it.hitsMax || InfluenceMap.damageAt(it.x, it.y, ctx.combatEnemies) > 0.0) }
            val need = { c: Creep -> (c.hitsMax - c.hits) + InfluenceMap.damageAt(c.x, c.y, ctx.combatEnemies).toInt() + (if (isEscort(c)) 1 else 0) }
            val close = candidates.filter { getRange(creep, it) <= 1 }.maxByOrNull { need(it) }
            if (close != null) creep.heal(close)
            else {
                val far = candidates.filter { it.hits < it.hitsMax }.maxByOrNull { need(it) }
                if (far != null) creep.rangedHeal(far)
            }
        }
        if (hasMelee(creep)) {
            val adjacent = ctx.enemyCreeps.filter { getRange(creep, it) <= 1 }
            val t = if (focus != null && getRange(creep, focus) <= 1) focus else adjacent.filter { isCombat(it) }.minByOrNull { it.hits } ?: adjacent.minByOrNull { it.hits }
            if (t != null) creep.attack(t)
        }
        if (hasRanged(creep)) shoot(creep, ctx, focus)
    }

    private fun shoot(creep: Creep, ctx: Ctx, focus: Creep?) {
        val inRange = ctx.enemyCreeps.filter { getRange(creep, it) <= RANGED_RANGE }
        if (inRange.isEmpty()) return
        val combatInRange = inRange.filter { isCombat(it) }
        val enemyEscort = ctx.enemyEscort?.takeIf { getRange(creep, it) <= RANGED_RANGE }
        // веер: несколько боевых целей вплотную; против лекарей — только фокус (веер размазывает урон, лечение его съедает)
        val massValue = combatInRange.sumOf { InfluenceMap.rangedRate(getRange(creep, it)) } + (if (enemyEscort != null) InfluenceMap.rangedRate(getRange(creep, enemyEscort)) else 0.0)
        val enemyHeals = ctx.combatEnemies.any { InfluenceMap.profileOf(it).heal > 0.0 }
        if (combatInRange.size >= 2 && massValue > (if (enemyHeals) 2.5 else 1.0)) { creep.rangedMassAttack(); return }
        val target = when {
            focus != null && getRange(creep, focus) <= RANGED_RANGE -> focus
            combatInRange.isNotEmpty() -> combatInRange.minByOrNull { it.hits }
            enemyEscort != null -> enemyEscort
            else -> inRange.minByOrNull { it.hits }
        }
        target?.let { creep.rangedAttack(it) }
    }

    // ==================== движение ====================

    /** Поле с ЧУЖИМ списком преград (проходимость врага): свой ключ кэша, чтобы не смешивать с нашими полями. */
    private fun flowToWith(key: String, target: Position, blocked: List<Position>, swampCost: Int): IntArray =
        flowTo("enemyside:$key", target, blocked, swampCost)

    private fun flowTo(key: String, target: Position, blocked: List<Position>, swampCost: Int, ttl: Int = 50, maxDist: Int = Int.MAX_VALUE): IntArray {
        val k = "$key@${target.x},${target.y}"
        val now = getTicks()
        val hit = flowCache[k]
        val at = flowCacheTick[k]
        if (hit != null && at != null && now - at < ttl) return hit
        bfsThisTick++
        val f = DistanceMap.flowFieldTo(target, blocked, swampCost, maxDist)
        flowCache[k] = f
        flowCacheTick[k] = now
        // кэш не растёт бесконечно: цели-крипы меняют клетку каждый тик
        if (flowCache.size > 64) {
            val old = flowCacheTick.entries.sortedBy { it.value }.take(32).map { it.key }
            for (o in old) { flowCache.remove(o); flowCacheTick.remove(o) }
        }
        return f
    }

    /** Поле к цели-крипу вблизи (ограниченный обход), живёт один тик. */
    private fun flowNear(ctx: Ctx, target: Position): IntArray = flowTo("near", target, ctx.blocked, 5, ttl = 1, maxDist = 30)

    private fun dangerMatrix(ctx: Ctx): CostMatrix = ctx.dangerMatrix ?: InfluenceMap.dangerCostMatrix(ctx.combatEnemies, ctx.blocked).also { ctx.dangerMatrix = it }

    /** Лучший шаг бойца: оценка своей клетки и соседних (Pain and Gain), статичный блокер в лучшей клетке обходится. */
    private fun bestSingleMove(
        creep: Creep, target: Position, flow: IntArray, standoff: Int, aggressive: Boolean, inCombat: Boolean,
        ctx: Ctx, allies: List<Creep>, meleeEnemies: List<Creep>, cover: Pair<Creep, Creep>?,
    ): Position? {
        val hereDist = flow[creep.x * 100 + creep.y]
        val settled = !inCombat && hereDist in 0..(standoff + ARRIVED_SLACK)
        var bestScore = scoreCell(creep, creep.x, creep.y, target, flow, standoff, aggressive, inCombat, ctx, allies, meleeEnemies, cover) + (if (settled) STAY_BIAS else 0.0)
        var bx = creep.x; var by = creep.y
        var pushDist = if (hereDist >= 0) hereDist else Int.MAX_VALUE
        var pushX = -1; var pushY = -1
        var blockedByStatic = false
        val stuck = TrafficManager.isStuck(creep.id)
        for ((dx, dy) in DIRECTIONS) {
            val x = creep.x + dx; val y = creep.y + dy
            if (!passable(x, y, ctx)) continue
            val occ = ctx.occupantAt[x * 100 + y]
            if (occ != null) {
                val fd = flow[x * 100 + y]
                val static = TrafficManager.wasStatic(occ.id) || !canMove(occ) || isEscort(occ)
                if (fd in 0 until (if (hereDist >= 0) hereDist else Int.MAX_VALUE) && (static || stuck)) blockedByStatic = true
                else if (!inCombat && !isEscort(occ) && fd in 0 until pushDist) { pushDist = fd; pushX = x; pushY = y }
                continue
            }
            val s = scoreCell(creep, x, y, target, flow, standoff, aggressive, inCombat, ctx, allies, meleeEnemies, cover)
            if (s > bestScore) { bestScore = s; bx = x; by = y }
        }
        if (bx != creep.x || by != creep.y) return InfluenceMap.cell(bx, by)
        if (pushX >= 0) return InfluenceMap.cell(pushX, pushY)
        if (blockedByStatic && hereDist >= 0) {
            var dx0 = 0; var dy0 = 0; var best = hereDist + 5
            for ((dx, dy) in DIRECTIONS) {
                val x = creep.x + dx; val y = creep.y + dy
                if (!passable(x, y, ctx) || ctx.occupantAt.containsKey(x * 100 + y)) continue
                val fd = flow[x * 100 + y]
                if (fd in 0..best && (dx0 == 0 && dy0 == 0 || fd < best)) { best = fd; dx0 = dx; dy0 = dy }
            }
            if (dx0 != 0 || dy0 != 0) return InfluenceMap.cell(creep.x + dx0, creep.y + dy0)
        }
        return null
    }

    /** Оценка клетки стрелка: приблизиться на standoff по полю; в бою — исходящий урон, чистый входящий (с лечением),
     *  влияние, зона мили, болото без перевеса, цена прижатия и прикрытие эскорта. */
    private fun scoreCell(
        creep: Creep, x: Int, y: Int, target: Position, flow: IntArray, standoff: Int, aggressive: Boolean, inCombat: Boolean,
        ctx: Ctx, allies: List<Creep>, meleeEnemies: List<Creep>, cover: Pair<Creep, Creep>?,
    ): Double {
        val flowDist = flow[x * 100 + y]
        val cheb = getRange(InfluenceMap.cell(x, y), target)
        val firePenalty = when {
            cheb <= standoff -> (standoff - cheb) * 0.5
            flowDist < 0 -> 1000.0
            flowDist > standoff -> (flowDist - standoff).toDouble()
            else -> (standoff - flowDist) * 0.5
        }
        var separation = allies.count { (it.x != x || it.y != y) && getRange(InfluenceMap.cell(x, y), it) <= SEPARATION_RADIUS } * W_SPREAD
        // прикрытие: клетка ближе к угрозе, чем эскорт, и не дальше от эскорта, чем зазор прибытия; заслон — при мили
        // врага рядом с эскортом клетки вплотную к нему ценнее всего, и соседство со своими не штрафуется
        var coverBonus = 0.0
        if (cover != null) {
            val (threat, escort) = cover
            val here = InfluenceMap.cell(x, y)
            if (getRange(here, threat) < getRange(escort, threat) && getRange(here, escort) <= standoff + ARRIVED_SLACK) coverBonus = W_COVER
            if (hasMelee(threat) || meleeEnemies.any { getRange(it, escort) <= BLOCK_RANGE }) {
                separation = 0.0
                if (getRange(here, escort) <= 1) coverBonus += W_BLOCK
            }
        }
        if (!inCombat) return -firePenalty * W_DIST - (if (flowDist > standoff + ARRIVED_SLACK) 0.0 else separation) + coverBonus

        val enemies = ctx.combatEnemies
        val damage = InfluenceMap.netDamageAt(x, y, enemies, allies)
        val meleeSelf = isMelee(creep) && !hasRanged(creep)
        val meleeWeight = if (aggressive) W_MELEE * AGGRO_MELEE_FACTOR else W_MELEE
        val meleeThreat = if (meleeSelf) 0.0 else meleeEnemies.count { getRange(InfluenceMap.cell(x, y), it) <= MELEE_KEEP_RANGE } * meleeWeight
        val swampPenalty = if (!aggressive && DistanceMap.isSwamp(x, y)) W_SWAMP else 0.0
        val influence = if (meleeSelf && aggressive) 0.0 else InfluenceMap.influenceAt(x, y, allies, enemies)
        val outgoing = if (!aggressive && damage > 0.0) 0.0 else if (meleeSelf) (if (enemies.any { getRange(InfluenceMap.cell(x, y), it) <= 1 }) 1.0 else 0.0) else if (hasRanged(creep)) outgoingValue(x, y, ctx) else 0.0
        val damageTerm = if (aggressive) 0.0 else damage * W_DAMAGE
        // цена прижатия — для стрелка, который кайтит: мили-боец дерётся вплотную, и при перевесе прижатие никого не держит
        // (M6A7 с потерянными MOVE стоял в двух клетках от M5A7, рубившего эскорт: −63 за клетку рядом с ним — стенд melee)
        val pinned = if (meleeSelf || aggressive) 0.0 else (periodAt(creep, x, y) - 1) * InfluenceMap.fireAt(x, y, enemies) * W_DAMAGE
        return -firePenalty * W_DIST - damageTerm + influence * W_INFLUENCE + outgoing * W_OUTGOING - meleeThreat - separation - swampPenalty - pinned + coverBonus
    }

    /** Ценность стрельбы из клетки: сумма rate по целям в дальности (боевые и эскорт врага), не меньше одного выстрела. */
    private fun outgoingValue(x: Int, y: Int, ctx: Ctx): Double {
        var massValue = 0.0
        var anyInRange = false
        val here = InfluenceMap.cell(x, y)
        for (enemy in ctx.combatEnemies) {
            val d = getRange(here, enemy)
            if (d <= RANGED_RANGE) { anyInRange = true; massValue += InfluenceMap.rangedRate(d) }
        }
        val ee = ctx.enemyEscort
        if (ee != null && getRange(here, ee) <= RANGED_RANGE) { anyInRange = true; massValue += InfluenceMap.rangedRate(getRange(here, ee)) }
        if (!anyInRange) return 0.0
        return maxOf(massValue, 1.0)
    }

    /** Шаг лекаря: вплотную к подопечному по полю; из равных клеток — под меньшим фактическим огнём, не вплотную к мили врага;
     *  без штрафа за соседей (его место — рядом со своими). */
    private fun bestHealerMove(h: Creep, charge: Creep, flow: IntArray, inCombat: Boolean, ctx: Ctx, meleeEnemies: List<Creep>): Position? {
        fun score(x: Int, y: Int): Double {
            val flowDist = flow[x * 100 + y]
            val cheb = getRange(InfluenceMap.cell(x, y), charge)
            val firePenalty = when {
                cheb <= 1 -> 0.0
                flowDist < 0 -> 1000.0
                else -> (flowDist - 1).toDouble()
            }
            if (!inCombat) return -firePenalty * W_DIST
            val fire = InfluenceMap.fireAt(x, y, ctx.combatEnemies)
            val meleeReach = meleeEnemies.count { getRange(InfluenceMap.cell(x, y), it) <= 1 } * HEALER_W_MELEE
            val pinned = (periodAt(h, x, y) - 1) * fire * W_DAMAGE
            return -firePenalty * W_DIST - fire * HEALER_W_FIRE - meleeReach - pinned
        }
        val hereDist = flow[h.x * 100 + h.y]
        val settled = getRange(h, charge) <= 1
        var bestScore = score(h.x, h.y) + (if (settled) STAY_BIAS else 0.0)
        var bx = h.x; var by = h.y
        var pushX = -1; var pushY = -1; var pushDist = if (hereDist >= 0) hereDist else Int.MAX_VALUE
        for ((dx, dy) in DIRECTIONS) {
            val x = h.x + dx; val y = h.y + dy
            if (!passable(x, y, ctx)) continue
            val occ = ctx.occupantAt[x * 100 + y]
            if (occ != null) {
                val fd = flow[x * 100 + y]
                // лекарь толкает безоружного обломка, но не бойца и не эскорт
                if (occ in ctx.husks && fd in 0 until pushDist) { pushDist = fd; pushX = x; pushY = y }
                continue
            }
            val s = score(x, y)
            if (s > bestScore) { bestScore = s; bx = x; by = y }
        }
        if (bx != h.x || by != h.y) return InfluenceMap.cell(bx, by)
        if (pushX >= 0) return InfluenceMap.cell(pushX, pushY)
        return null
    }

    private fun passable(x: Int, y: Int, ctx: Ctx): Boolean {
        if (x < 0 || y < 0 || x > 99 || y > 99) return false
        val key = x * 100 + y
        if (key in ctx.blockedSet || key in ctx.enemyPositions) return false
        return !DistanceMap.isTerrainWall(x, y)
    }

    /** Жадный шаг бегства: свободная соседняя клетка с наибольшей дальностью до ближайшего врага, при равной — под меньшим огнём. */
    private fun greedyFlee(ctx: Ctx, creep: Creep, enemies: List<Creep>, force: Boolean = false): Position? {
        var best: Position? = null
        var bestRange = if (force) -1 else (enemies.minOfOrNull { getRange(creep, it) } ?: 0)
        var bestFire = if (force) Double.MAX_VALUE else InfluenceMap.fireAt(creep.x, creep.y, enemies)
        for ((dx, dy) in DIRECTIONS) {
            val x = creep.x + dx; val y = creep.y + dy
            if (!passable(x, y, ctx) || ctx.occupantAt.containsKey(x * 100 + y)) continue
            val pos = InfluenceMap.cell(x, y)
            val range = enemies.minOfOrNull { getRange(pos, it) } ?: 0
            val fire = InfluenceMap.fireAt(x, y, enemies)
            if (range > bestRange || (range == bestRange && fire < bestFire)) { best = pos; bestRange = range; bestFire = fire }
        }
        return best
    }

    private fun fleeStep(creep: Creep, enemies: List<Creep>, dangerMatrix: CostMatrix, range: Int = RANGED_RANGE): Position? {
        if (enemies.isEmpty()) return null
        val goals = enemies.map { e -> SearchGoal(pos = InfluenceMap.cell(e.x, e.y), range = range) }.toTypedArray()
        val result = searchPath(creep, goals, SearchPathOptions(flee = true, costMatrix = dangerMatrix))
        return result.path.firstOrNull()
    }

    // ==================== тело, скорость, мощь ====================

    private fun canMove(creep: Creep) = creep.body.any { it.type == MOVE && it.hits > 0 }
    private fun hasMelee(creep: Creep) = creep.body.any { it.type == ATTACK && it.hits > 0 }
    private fun isMelee(creep: Creep) = creep.body.any { it.type == ATTACK }
    private fun hasRanged(creep: Creep) = creep.body.any { it.type == RANGED_ATTACK && it.hits > 0 }
    private fun hasHeal(creep: Creep) = creep.body.any { it.type == HEAL && it.hits > 0 }
    private fun hasWeapon(creep: Creep) = hasRanged(creep) || hasMelee(creep)
    private fun isCombat(creep: Creep): Boolean { val p = InfluenceMap.profileOf(creep); return p.melee + p.ranged + p.heal > 0.0 }

    /** Сводка тела: T10M4R3H1 (только живые части). */
    private fun bodySummary(creep: Creep): String {
        val order = listOf(TOUGH to 'T', MOVE to 'M', RANGED_ATTACK to 'R', ATTACK to 'A', HEAL to 'H', CARRY to 'C', WORK to 'W')
        val sb = StringBuilder()
        for ((type, ch) in order) {
            val n = creep.body.count { it.type == type && it.hits > 0 }
            if (n > 0) sb.append(ch).append(n)
        }
        return sb.toString().ifEmpty { "dead" }
    }

    /** Тело в порядке частей (зонд): «TTTTMMMM», мёртвые части строчными. */
    private fun bodyOrder(creep: Creep): String {
        val sb = StringBuilder()
        for (p in creep.body) {
            val ch = when (p.type) { TOUGH -> 'T'; MOVE -> 'M'; RANGED_ATTACK -> 'R'; ATTACK -> 'A'; HEAL -> 'H'; CARRY -> 'C'; WORK -> 'W'; else -> '?' }
            sb.append(if (p.hits > 0) ch else ch.lowercaseChar())
        }
        return sb.toString()
    }

    private fun summaryOf(body: Array<BodyPartType>): String {
        val order = listOf(TOUGH to 'T', MOVE to 'M', RANGED_ATTACK to 'R', ATTACK to 'A', HEAL to 'H', CARRY to 'C', WORK to 'W')
        val sb = StringBuilder()
        for ((type, ch) in order) { val n = body.count { it == type }; if (n > 0) sb.append(ch).append(n) }
        return sb.toString()
    }

    /** Вес тела для усталости: части не-MOVE и не-CARRY ПО ТИПУ (мёртвые весят — movement.js:237) плюс гружёные CARRY. */
    private fun bodyWeight(creep: Creep): Int {
        val parts = creep.body.count { it.type != MOVE && it.type != CARRY }
        val carried = creep.store[RESOURCE_ENERGY] ?: 0
        return parts + (carried + CARRY_CAPACITY - 1) / CARRY_CAPACITY
    }

    private fun liveMoves(creep: Creep) = creep.body.count { it.type == MOVE && it.hits > 0 }

    /** Период хода (тиков на клетку): после шага fatigue = вес × цена местности − 2 × живые MOVE, дальше −2×MOVE в тик. */
    private fun periodOn(weight: Int, moves: Int, rate: Int): Int {
        if (moves <= 0) return Int.MAX_VALUE / 4
        val left = weight * rate - 2 * moves
        return if (left <= 0) 1 else 1 + (left + 2 * moves - 1) / (2 * moves)
    }

    private fun plainPeriod(creep: Creep) = periodOn(bodyWeight(creep), liveMoves(creep), 2)
    private fun swampPeriod(creep: Creep) = periodOn(bodyWeight(creep), liveMoves(creep), 10)
    private fun periodAt(creep: Creep, x: Int, y: Int) = periodOn(bodyWeight(creep), liveMoves(creep), if (DistanceMap.isSwamp(x, y)) 10 else 2)
    private fun fullSpeed(creep: Creep) = plainPeriod(creep) == 1

    /** Цена болота для поля потока этого тела: во сколько раз шаг на болото дольше шага по равнине. */
    private fun swampRatio(creep: Creep): Int {
        val plain = plainPeriod(creep)
        val swamp = swampPeriod(creep)
        if (plain >= Int.MAX_VALUE / 4 || plain <= 0) return 5
        return maxOf(1, minOf(20, (swamp + plain - 1) / plain))
    }

    /** Тики хода крипа по спуску вдоль поля потока от клетки до цели — по его телу и местности. */
    private fun pathTicks(creep: Creep, flow: IntArray, startCell: Int): Int {
        var cell = startCell
        if (cell < 0 || flow[cell] < 0) return Int.MAX_VALUE / 4
        var ticks = 0
        var steps = 0
        while (flow[cell] > 0 && steps < 400) {
            val cx = cell / 100
            val cy = cell % 100
            var best = -1
            var bestFlow = flow[cell]
            for (dx in -1..1) for (dy in -1..1) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
                val f = flow[nx * 100 + ny]
                if (f in 0 until bestFlow) { bestFlow = f; best = nx * 100 + ny }
            }
            if (best < 0) break
            cell = best
            steps++
            ticks += periodAt(creep, cell / 100, cell % 100)
        }
        return ticks
    }

    /** Доля удара мили, которая дойдёт: кайт-дисконт, если противники сплошь стрелки, никто не вплотную и мили медленнее их на болоте. */
    private fun meleeFactor(unit: Creep, opponents: List<Creep>): Double {
        if (opponents.any { hasMelee(it) || getRange(unit, it) <= MELEE_KEEP_RANGE }) return 1.0
        val ranged = opponents.filter { hasRanged(it) }
        if (ranged.isEmpty()) return 1.0
        val mine = swampPeriod(unit)
        return if (ranged.any { swampPeriod(it) > mine }) 1.0 else MELEE_KITE_DISCOUNT
    }

    private fun effectiveDps(unit: Creep, opponents: List<Creep>): Double {
        val p = InfluenceMap.profileOf(unit)
        return p.ranged + p.melee * meleeFactor(unit, opponents)
    }

    /** Мощь группы по Ланчестеру против противника: √((урон − лечение противника) × хиты вооружённых). Эскорт в группе —
     *  его хиты целиком: он и есть цель врага, и пока враг снимает его 5000, наш боец снимает врага (M10A6 против M7A7 у
     *  эскорта читался как 536 против 542 и стоял в трёх клетках, пока эскорт умирал — стенд melee). */
    private fun powerOf(side: List<Creep>, opp: List<Creep>): Double {
        val dps = side.sumOf { effectiveDps(it, opp) }
        val heal = opp.sumOf { InfluenceMap.profileOf(it).heal }
        val hits = side.sumOf { u ->
            val p = InfluenceMap.profileOf(u)
            val raw = p.ranged + p.melee
            if (isEscort(u)) u.hits.toDouble()
            else if (raw <= 0.0) (if (p.heal > 0.0) u.hits.toDouble() else 0.0) else u.hits * effectiveDps(u, opp) / raw
        }
        return sqrt(maxOf(0.0, dps - heal) * maxOf(0.0, hits))
    }

    // ==================== диагностика ====================

    private fun logStatus(ctx: Ctx) {
        val now = getTicks()
        val escort = ctx.escort
        val ourArrival = if (escort != null && ctx.escortFlow != null) routeTicks(escort, ctx.escortFlow, trainMoves(escort, ctx.pullers)) else -1
        val theirArrival = if (ctx.enemyEscort != null) enemyEscortArrival(ctx) else -1
        val threats = if (escort != null) ctx.combatEnemies.count { getRange(it, escort) <= THREAT_RANGE } else 0
        println(
            "t=$now spawnE=${ctx.mySpawn?.store?.get(RESOURCE_ENERGY)} regen=${spawnRegen()} source=${ctx.homeSource?.energy}/${ctx.homeSource?.energyCapacity} srcRegen=${sourceRegen()} income=${income(ctx).toInt()} " +
                "escort=${escort?.let { "(${it.x},${it.y}) ${it.hits}/${it.hitsMax} f=${it.fatigue} arrive=$ourArrival" } ?: "DEAD"} " +
                "enemyEscort=${ctx.enemyEscort?.let { "(${it.x},${it.y}) ${it.hits}/${it.hitsMax} arrive=$theirArrival" } ?: "none"} " +
                "harv=${ctx.harvesters.size} pullers=${ctx.pullers.size} train=${if (trainRolling) "rolling" else "-"} fighters=${ctx.fighters.size} healers=${ctx.healers.size} husks=${ctx.husks.size} enemies=${ctx.enemyCreeps.size}/${ctx.combatEnemies.size} pending=${ctx.enemyPending.size} " +
                "threats=$threats death=${escortDeathTicks(ctx).let { if (it >= Int.MAX_VALUE / 4) "inf" else it.toString() }} strike=$striking bfs=$bfsMaxTick"
        )
        bfsMaxTick = 0
        if (now % (LOG_EVERY * 10) == 0) println(TrafficManager.audit())
    }

    /**
     * Трасса гонки: строка в тот тик, когда любой из эскортов сменил клетку — позиция, усталость и число сделанных
     * шагов у обоих. Прибор поставлен под конкретный вопрос: соперник Hardy проводит своего эскорта быстрее, чем
     * позволяет период тела (4 тика на клетку по равнине при весе 40 и десяти MOVE), и делает это ДО того, как у него
     * появляется хоть один второй крип. Сводка раз в десять тиков этого не ловит — она даёт только позицию, из которой
     * период восстанавливается с точностью «между тремя и пятью». Трасса даёт тик каждого шага, то есть период прямо.
     */
    private fun logRace(ctx: Ctx) {
        val now = getTicks()
        if (now > RACE_TRACE_TICKS) return
        val ours = ctx.escort
        val theirs = ctx.enemyEscort
        val oc = ours?.let { it.x * 100 + it.y } ?: -1
        val tc = theirs?.let { it.x * 100 + it.y } ?: -1
        if (oc == raceOurCell && tc == raceTheirCell) return
        if (raceOurCell >= 0 && oc != raceOurCell) raceOurSteps++
        if (raceTheirCell >= 0 && tc != raceTheirCell) raceTheirSteps++
        raceOurCell = oc
        raceTheirCell = tc
        println(
            "race t=$now ours=${ours?.let { "(${it.x},${it.y}) f=${it.fatigue} steps=$raceOurSteps" } ?: "DEAD"} " +
                "theirs=${theirs?.let { "(${it.x},${it.y}) f=${it.fatigue} steps=$raceTheirSteps" } ?: "none"} " +
                "theirCreeps=${ctx.enemyCreeps.size}${ctx.enemyCreeps.filter { !isEscort(it) }.joinToString("") { " ${it.id}(${it.x},${it.y})${bodySummary(it)}" }} " +
                "theirPending=${ctx.enemyPending.size}" +
                // состав и эффекты чужого эскорта — здесь, а не в зонде первого тика: если он идёт быстрее своего тела,
                // разница обязана быть видна либо в живых MOVE, либо в эффекте, и оба меняются ПО ХОДУ матча
                (theirs?.let { " theirBody=${bodySummary(it)} theirMoves=${liveMoves(it)} theirWeight=${bodyWeight(it)} theirEffects=${effectsOf(it)}" } ?: "") +
                (ours?.let { " ourEffects=${effectsOf(ours)}" } ?: "")
        )
    }

    /** Эффекты объекта строкой (тип, конец, множитель) — их может не быть вовсе, поле опционально. */
    private fun effectsOf(o: GameObject): String {
        val eff = o.effects ?: return "-"
        if (eff.isEmpty()) return "-"
        return eff.joinToString(",") { e ->
            val d = e.asDynamic()
            "${d.effect}@end=${d.endTime}x${d.multiplier}"
        }
    }

    private fun logBodies(ctx: Ctx) {
        println("bodies t=${getTicks()} ours: " + ctx.active.joinToString(" ") { "${it.id}${if (isEscort(it)) "E" else ""}(${it.x},${it.y})${bodySummary(it)}h${it.hits}" } +
            " | enemy: " + ctx.enemyCreeps.joinToString(" ") { "${it.id}${if (isEscort(it)) "E" else ""}(${it.x},${it.y})${bodySummary(it)}h${it.hits}" } +
            " | pending: " + ctx.enemyPending.joinToString(" ") { bodyOrder(it) })
    }

    private fun logStuck(active: List<Creep>, enemyCreeps: List<Creep>) {
        for (c in active) {
            if (TrafficManager.stuckFor(c.id) != TrafficManager.STUCK_TICKS) continue
            val want = TrafficManager.lastDesiredOf(c.id)
            val occ = want?.let { w -> (active + enemyCreeps).firstOrNull { it.x * 100 + it.y == w } }
            val occWant = occ?.let { TrafficManager.lastDesiredOf(it.id) }
            println("stuck ${c.id} at (${c.x},${c.y}) fatigue=${c.fatigue} wants=${want?.let { "(${it / 100},${it % 100})" }} " +
                "occ=${occ?.let { "${it.id} my=${it.my} fatigue=${it.fatigue} ${bodySummary(it)} wants=${occWant?.let { w -> "(${w / 100},${w % 100})" } ?: "-"}" } ?: "free"}")
        }
    }

    /** Метки карты, снятые на первом тике: спавны, источники, контейнеры, флаги, крипы. */
    private fun captureMapMarks(spawns: List<StructureSpawn>, sources: List<Source>, containers: List<StructureContainer>, flags: List<Flag>, creeps: List<Creep>) {
        val m = HashMap<Int, Char>()
        fun mark(x: Int, y: Int, c: Char) { m[x * 100 + y] = c }
        // рампарты — раньше их не было на карте вовсе, а в первом матче их пятьдесят (и сорок восемь стен)
        getObjectsByPrototype(StructureRampart::class).forEach { mark(it.x, it.y, if (it.my == true) 'r' else 'R') }
        getObjectsByPrototype(StructureWall::class).forEach { mark(it.x, it.y, '#') }
        containers.forEach { mark(it.x, it.y, 'C') }
        sources.forEach { mark(it.x, it.y, 'S') }
        spawns.forEach { mark(it.x, it.y, if (it.my == true) 'M' else 'E') }
        creeps.forEach { mark(it.x, it.y, if (isEscort(it)) (if (it.my) 'x' else 'X') else if (it.my) 'm' else 'e') }
        flags.forEach { mark(it.x, it.y, if (it.my == true) 'f' else 'F') }
        mapMarks = m
    }

    /** Дамп карты — четырьмя частями по 25 строк на тиках 3–6, одной строкой каждая (первый тик — холодный JIT,
     *  второй — обычный бюджет; Pain and Gain упирался в оба). */
    private fun logMap(fromRow: Int) {
        val marks = mapMarks ?: return
        val out = StringBuilder(if (fromRow == 0) "=== MAP (rows y=0..99, cols x=0..99; # wall ~ swamp r/R our/their ramparts M/E spawns S source C container f/F flags x/X escorts) ===" else "")
        for (y in fromRow until minOf(fromRow + 25, 100)) {
            val row = StringBuilder()
            for (x in 0..99) {
                val isWall = DistanceMap.isTerrainWall(x, y)
                val isSwamp = !isWall && DistanceMap.isSwamp(x, y)
                val structure = marks[x * 100 + y]
                row.append(
                    when {
                        structure != null -> structure
                        isWall -> '#'
                        isSwamp -> '~'
                        else -> '.'
                    }
                )
            }
            if (out.isNotEmpty()) out.append('\n')
            out.append(y.toString().padStart(2, '0')).append(':').append(row)
        }
        println(out.toString())
        if (fromRow + 25 >= 100) {
            var swamp = 0
            var wall = 0
            for (y in 0..99) for (x in 0..99) { if (DistanceMap.isTerrainWall(x, y)) wall++ else if (DistanceMap.isSwamp(x, y)) swamp++ }
            println("=== END MAP swamp=$swamp wall=$wall plain=${10000 - swamp - wall} ===")
        }
    }
}
