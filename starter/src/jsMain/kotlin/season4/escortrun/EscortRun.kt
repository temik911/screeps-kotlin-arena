package season4.escortrun

import screeps.api.ATTACK
import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.ConstructionSite
import screeps.api.Creep
import screeps.api.Flag
import screeps.api.GameObject
import screeps.api.HARVEST_POWER
import screeps.api.MOVE
import screeps.api.Position
import screeps.api.RANGED_ATTACK
import screeps.api.RESOURCE_ENERGY
import screeps.api.SPAWN_ENERGY_CAPACITY
import screeps.api.Source
import screeps.api.TOUGH
import screeps.api.WORK
import screeps.api.arenaInfo
import screeps.api.get
import screeps.api.getObjects
import screeps.api.getObjectsByPrototype
import screeps.api.getRange
import screeps.api.getTicks
import screeps.api.structures.StructureContainer
import screeps.api.structures.StructureExtension
import screeps.api.structures.StructureRampart
import screeps.api.structures.StructureSpawn
import screeps.api.structures.StructureTower
import screeps.api.structures.StructureWall
import sourcemaps.runWithSourceMapSupport

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
 * Season 4 «Escort Run» (basic), v11 — переписан по мете поля, снятой с реплеев 27.09.2026 (docs/escort-run.md).
 *
 * Победа — свой эскорт (5000 хитов, `MTTTT`×10, период 4 по равнине) на своём флаге раньше чужого, или смерть чужого
 * эскорта. Слои, по которым решается матч, в порядке, в котором они наступают:
 *  1. **Гонка.** Эскорт — ГОЛОВА поезда и тянет тягачей за собой (обратный поезд сильных соперников): их MOVE гасят
 *     его усталость, период 2 при ΣMOVE 20. Пока спавн рождает тягача, а эскорт вплотную к спавну, эскорт тянет
 *     РОЖДАЮЩЕГОСЯ (спавн-пул) — это те пять тиков, которые мы проигрывали Hardy и 76561198870429455#24/#28/#29.
 *     Разбивка тягачей (M10 или M5+M5, …) выбирается прогоном (Opening).
 *  2. **Флаги.** Клетку флага можно занять: хранитель (M1) на своём флаге не пускает чужого блокировщика и уходит
 *     вбок в тот тик, когда эскорт шагает на флаг (эскорт выигрывает спор за клетку — movement.js rate1);
 *     блокировщик (M1) на чужом флаге запирает чужой эскорт, пока его не убьют.
 *  3. **Бойцы.** Кто занял наш флаг — убить; кто грызёт наш поезд — убить; чужой хранитель при проигранной гонке —
 *     убить и встать на его клетку.
 * Всё считается из состояния: тела — перебором и дуэлью, приходы — по полю маршрута и живым MOVE поезда.
 */
object EscortRun {

    // ---------- версия и подпись ----------
    /** Печатается первой строкой матча вместе с подписью ключевых параметров (клиент читает скрипт при старте матча,
     *  и по логу должно быть видно, какая сборка играла). Поднимать при каждой сборке, идущей в матч. */
    private const val BOT_VERSION = "v39"

    // ---------- поезд ----------
    /** Тягач — тело из одних MOVE не короче этого; короче — разведчик (хранитель или блокировщик флага). */
    private const val PULLER_MIN_MOVE = 3
    /** Сколько MOVE тягачей покупает дебют: вместе с десятью MOVE эскорта это период 2 на равнине (80 / 40). */
    private const val OPENING_MOVES = 10
    private const val MAX_CHAIN = 3

    // ---------- бой ----------
    private const val RANGED_RANGE = 3
    /** Боевой враг в стольких клетках от нашего эскорта — его угроза (он ходит клетку в тик, поезд — в два). */
    private const val THREAT_RANGE = 12
    /** Враг у нашего флага: на клетке флага или в стольких клетках от неё. */
    private const val FLAG_GUARD_RANGE = 1

    // ---------- приоритеты движения ----------
    private const val YIELD_PRIORITY = 95
    private const val PULLER_PRIORITY = 90
    private const val SCOUT_PRIORITY = 40
    private const val FIGHTER_PRIORITY = 30
    private const val WORKER_PRIORITY = 10

    private const val DEBUG_LOG = true
    private const val LOG_EVERY = 10
    private const val BODIES_EVERY = 50
    private const val RACE_TRACE_TICKS = 40

    private val DIRECTIONS = listOf(-1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1)

    // ---------- состояние между тиками ----------
    private var greeted = false
    private var personaRead = false
    /** id крипов, живых на ПЕРВОМ тике: за один тик родить никого нельзя, значит это эскорты обеих сторон. */
    private val escortIds = HashSet<String>()
    /** Задания разведчиков: id -> KEEP (наш флаг) / BLOCK (их флаг). Роль — по телу, задание — по нужде в момент рождения. */
    private val scoutMission = HashMap<String, String>()
    private val scoutQueue = ArrayDeque<String>()
    /** Порядок цепи прошлого тика: цепь не перетасовывается от тика к тику. */
    private var lastChain: List<String> = emptyList()
    /** План дебюта: MOVE каждого тягача по порядку и сколько из них уже заказано. */
    private var openingPlan: List<Int>? = null
    private var openingIdx = 0
    /** История позиций вражеских разведчиков: id -> (тик, дистанция до нашего флага, дистанция до их флага). */
    private val scoutTrack = HashMap<String, ArrayDeque<Triple<Int, Int, Int>>>()
    /** Темп чужого эскорта по наблюдению: (тик, дистанция по его полю). */
    private val enemyEscortHist = ArrayDeque<Pair<Int, Int>>()
    /** Проверка буксировки результатом: ожидаемая усталость эскорта после шага с цепью. */
    private var expectFatigue = -1
    private var pullChecks = 0
    private var pullMisses = 0
    private val flowCache = HashMap<String, IntArray>()
    private val flowCacheTick = HashMap<String, Int>()
    private var mapMarks: HashMap<Int, Char>? = null
    private var raceOurCell = -1
    private var raceTheirCell = -1

    /** Крипы, двинутые в этот тик прямыми интентами (поезд, хранитель, блокировщик на месте) — мимо TrafficManager. */
    private val pinned = HashSet<String>()
    /** Клетки, которые свои обязаны освободить (следующая клетка эскорта). */
    private var yieldCells: Set<Int> = emptySet()

    private fun idOf(o: GameObject): String = "${o.asDynamic().id}"

    // ==================== мир ====================

    internal class World(
        val now: Int,
        val mySpawn: StructureSpawn?,
        val enemySpawn: StructureSpawn?,
        val escort: Creep?,
        val enemyEscort: Creep?,
        val myFlag: Position?,
        val enemyFlag: Position?,
        val homeSource: Source?,
        val mine: List<Creep>,
        val active: List<Creep>,
        val enemies: List<Creep>,
        val enemyPending: List<Creep>,
        val pullers: List<Creep>,
        val scouts: List<Creep>,
        val fighters: List<Creep>,
        val workers: List<Creep>,
        val enemyArmed: List<Creep>,
        val enemyScouts: List<Creep>,
        val occupant: HashMap<Int, Creep>,
        val enemyAt: HashSet<Int>,
        val blocked: List<Position>,
        val blockedForEnemy: List<Position>,
        val myRamparts: List<Position>,
        val escortFlow: IntArray?,
        val enemyEscortFlow: IntArray?,
        /** Поле нашего эскорта БЕЗ чужих крипов: по нему видно, кто из них стоит на нашем пути (routeBlockers). */
        val escortFlowRaw: IntArray?,
        /** Сооружение, закрывшее наш флаг (их рампарт или стена на клетке флага, или все проходимые соседи флага под
         *  сооружениями — тогда ближнее к эскорту); null — флаг открыт. */
        val flagBlocker: GameObject?,
        /** Их стройплощадка на нашем флаге или вплотную: рампарт на ней достроится за десятки тиков. */
        val flagSite: ConstructionSite?,
    )

    fun tick() {
        if (!personaRead) {
            personaRead = true
            // личность посылки (docs/escort-run-redteam.md): tools/er-league.py оборачивает main.mjs и называет приёмы
            // красной команды в globalThis.ER_PERSONA; без обёртки — основной бот
            val named = js("globalThis.ER_PERSONA")
            RedTeam.configure(if (jsTypeOf(named) == "string") named.unsafeCast<String>() else "main")
        }
        val w = sense()
        if (!greeted) { greeted = true; probe(w) }
        if (mapMarks == null) captureMapMarks(w)
        if (w.now in 3..6) logMap((w.now - 3) * 25)
        pinned.clear()
        yieldCells = emptySet()
        if (openingPlan == null) { planOpening(w); aimSpawn(w) }
        trackEnemyScouts(w)
        assignScouts(w)
        decideHold(w)
        runSpawn(w)
        runTrain(w)
        runScouts(w)
        runFighters(w)
        runWorkers(w)
        RedTeam.act(w)
        enforceYield(w)
        TrafficManager.resolve(w.active.filter { idOf(it) !in pinned && Bodies.liveMoves(it) > 0 && !isEscort(it) }, w.active + w.enemies)
        if (DEBUG_LOG) {
            logRace(w)
            if (w.now % LOG_EVERY == 0) logStatus(w)
            if (w.now % BODIES_EVERY == 0) logBodies(w)
        }
    }

    private fun sense(): World {
        val now = getTicks()
        val allCreeps = getObjectsByPrototype(Creep::class).filter { it.exists }
        val escortObjs = getObjects().filter { it.exists && protoName(it) == "EscortCreep" }.map { it.unsafeCast<Creep>() }
        // эскорт — по имени прототипа (живьём работает: «EscortCreep=2»); «жив на первом тике» — только запасной
        // признак, когда имени нет: на стенде у racer тягач жив с нулевого тика и читался эскортом, отчего их поезд
        // считался пешим и их приход — вдвое позже настоящего
        if (escortIds.isEmpty()) {
            if (escortObjs.isNotEmpty()) for (c in escortObjs) escortIds.add(idOf(c))
            else for (c in allCreeps) if (!c.spawning) escortIds.add(idOf(c))
        }
        val byId = LinkedHashMap<String, Creep>()
        for (c in allCreeps) byId[idOf(c)] = c
        for (c in escortObjs) byId[idOf(c)] = c
        val creeps = byId.values.toList()
        val mine = creeps.filter { it.my }
        val enemyAll = creeps.filter { !it.my }
        val enemies = enemyAll.filter { !it.spawning }
        val active = mine.filter { !it.spawning }
        val escort = mine.firstOrNull { isEscort(it) }
        val enemyEscort = enemies.firstOrNull { isEscort(it) }

        val spawns = getObjectsByPrototype(StructureSpawn::class).filter { it.exists }
        val mySpawn = spawns.firstOrNull { it.my == true }
        val enemySpawn = spawns.firstOrNull { it.my == false }
        val flags = getObjectsByPrototype(Flag::class).filter { it.exists }
        val myFlag = flags.firstOrNull { it.my == true } ?: flags.maxByOrNull { f -> mySpawn?.let { getRange(f, it) } ?: 0 }
        val enemyFlag = flags.firstOrNull { it.my == false } ?: flags.firstOrNull { it !== myFlag }
        val sources = getObjectsByPrototype(Source::class).filter { it.exists }
        val homeSource = if (mySpawn != null) sources.minByOrNull { getRange(it, mySpawn) } else null

        val walls = getObjectsByPrototype(StructureWall::class).filter { it.exists }
        val towers = getObjectsByPrototype(StructureTower::class).filter { it.exists }
        val extensions = getObjectsByPrototype(StructureExtension::class).filter { it.exists }
        val ramparts = getObjectsByPrototype(StructureRampart::class).filter { it.exists }
        val structuresBase: List<Position> = spawns + walls + towers + extensions
        val blocked: List<Position> = structuresBase + ramparts.filter { it.my != true }
        DistanceMap.syncStructures(blocked)
        val blockedForEnemy: List<Position> = structuresBase + ramparts.filter { it.my != false }

        val occupant = HashMap<Int, Creep>()
        for (c in active) occupant[key(c)] = c
        for (c in enemies) occupant[key(c)] = c
        val enemyAt = enemies.mapTo(HashSet()) { key(it) }

        RedTeam.claim(mine)
        // крипы красной команды ведёт RedTeam: основная логика не считает их своими тягачами, разведчиками и бойцами
        val others = active.filter { !isEscort(it) && !RedTeam.owns(it) }
        val escortFlowRaw = if (escort != null && myFlag != null) flowTo("escort", myFlag, blocked, 5) else null
        // их разведчик, вставший на наш путь впереди (CHOKE соперника), — стена поля эскорта: поезд обходит его всем
        // маршрутом заранее, а не упирается и объезжает по месту (docs/escort-run-redteam.md)
        // стоящих: их M1, идущий к нашему флагу нашим же путём, «стеной» гонял поезд в обход, и приход вырос на 46
        // тиков за один тик (лига, v22 против v20)
        for (c in enemies) { val k = key(c); val was = enemyStill[idOf(c)]; if (was == null || was.first != k) enemyStill[idOf(c)] = k to now }
        // сколько тиков подряд их вооружённый держится при своём эскорте (телохранитель — подолгу, см. bodyguard)
        val theirEscNow = enemies.firstOrNull { isEscort(it) }
        for (c in enemies) if (!isEscort(c) && Bodies.isArmed(c)) {
            guardTicks[idOf(c)] = if (theirEscNow != null && getRange(c, theirEscNow) <= 3) (guardTicks[idOf(c)] ?: 0) + 1 else 0
        }
        enemyStill.keys.retainAll(enemies.mapTo(HashSet()) { idOf(it) })
        val stops = if (escort != null && escortFlowRaw != null) {
            // и на СЛЕДУЮЩЕЙ клетке тоже: без неё стена пропадала, когда эскорт подходил вплотную, поле возвращалось к
            // пути сквозь блокировщика, обход «врагов в шести клетках» уводил в сторону на болото, а оттуда прежнее
            // поле звало назад — эскорт качался по 400 усталости за шаг и стоял у блокировщика 50-100 тиков
            // (офлайн-лига, v24 против раннего блокировщика: 16 поражений из 80)
            val ahead = Chokes.route(escortFlowRaw, escort).toHashSet()
            enemies.filter { !isEscort(it) && Bodies.isScout(it, PULLER_MIN_MOVE) && isStill(it, now) && key(it) in ahead && (myFlag == null || getRange(it, myFlag) > FLAG_GUARD_RANGE) } +
                // и НАШ крип без живых MOVE: уступить клетку он не может — боец M4A4, потерявший в бою передние MOVE,
                // встал в коридоре перед эскортом, и тот простоял до 2000-го тика (стенд camp)
                active.filter { !isEscort(it) && Bodies.liveMoves(it) == 0 && key(it) in ahead }
        } else emptyList()
        // и клетки, где бьют их вооружённые (мили — две клетки, стрелок — четыре): поезд обходит пару «эскорт +
        // телохранитель» в широком центре, а не проходит вплотную (ricardo#23)
        val danger = ArrayList<Position>()
        if (escort != null && mySpawn != null && getRange(escort, mySpawn) > 3) for (e in enemies) {
            // уже в бою (враг в четырёх клетках) — клетки не ставим: стенами вокруг себя эскорт замирал и стоял под ударами
            // до смерти (けろびー#32: 118 тиков на одной клетке)
            if (isEscort(e) || !Bodies.isArmed(e) || getRange(e, escort) <= 4) continue
            val r = if (Bodies.live(e, RANGED_ATTACK) > 0) 4 else 2
            for (dx in -r..r) for (dy in -r..r) {
                val x = e.x + dx; val y = e.y + dy
                if (!DistanceMap.inBounds(x, y) || getRange(escort, InfluenceMap.cell(x, y)) <= 1) continue
                danger.add(InfluenceMap.cell(x, y))
            }
        }
        val walls2 = stops + danger
        val escortFlow = if (walls2.isEmpty() || myFlag == null) escortFlowRaw
            else flowTo("escort:" + walls2.map { key(it) }.sorted().joinToString(","), myFlag, blocked + walls2, 5)
        val enemyEscortFlow = if (enemyEscort != null && enemyFlag != null) flowTo("enemyEscort", enemyFlag, blockedForEnemy, 5) else null
        return World(
            now, mySpawn, enemySpawn, escort, enemyEscort, myFlag, enemyFlag, homeSource, mine, active, enemies,
            enemyAll.filter { it.spawning },
            pullers = others.filter { Bodies.isPuller(it, PULLER_MIN_MOVE) },
            scouts = mine.filter { !isEscort(it) && !RedTeam.owns(it) && Bodies.isScout(it, PULLER_MIN_MOVE) },
            fighters = others.filter { Bodies.wasArmed(it) },
            workers = others.filter { Bodies.isWorker(it) || Bodies.isHauler(it) },
            enemyArmed = enemies.filter { Bodies.isArmed(it) },
            enemyScouts = enemies.filter { !isEscort(it) && Bodies.isScout(it, PULLER_MIN_MOVE) },
            occupant = occupant, enemyAt = enemyAt, blocked = blocked, blockedForEnemy = blockedForEnemy, myRamparts = ramparts.filter { it.my == true },
            escortFlow = escortFlow, enemyEscortFlow = enemyEscortFlow, escortFlowRaw = escortFlowRaw,
            flagBlocker = flagBlockerOf(myFlag, escort, walls, ramparts),
            flagSite = myFlag?.let { f -> getObjectsByPrototype(ConstructionSite::class).firstOrNull { it.exists && it.my == false && getRange(it, f) <= 1 } },
        )
    }

    private fun key(p: Position) = p.x * 100 + p.y

    /**
     * Затычка и печать (docs/escort-run-redteam.md): рампарт соперника пропускает только владельца и строится даже под
     * нашим хранителем; стена — нет, но пять стен на соседях запечатывают карман флага на любой карте.
     */
    private fun flagBlockerOf(flag: Position?, escort: Creep?, walls: List<StructureWall>, ramparts: List<StructureRampart>): GameObject? {
        if (flag == null) return null
        fun at(x: Int, y: Int): GameObject? =
            ramparts.firstOrNull { it.my == false && it.x == x && it.y == y } ?: walls.firstOrNull { it.x == x && it.y == y }
        at(flag.x, flag.y)?.let { return it }
        val around = DIRECTIONS.map { (dx, dy) -> flag.x + dx to flag.y + dy }
            .filter { (x, y) -> DistanceMap.inBounds(x, y) && !DistanceMap.isTerrainWall(x, y) }
        if (around.isEmpty()) return null
        val shut = around.mapNotNull { (x, y) -> at(x, y) }
        if (shut.size < around.size) return null
        return shut.minByOrNull { if (escort != null) getRange(it, escort) else 0 }
    }

    /** Где и с какого тика стоит каждый их крип: стоящий STILL_TICKS тиков и дольше — не прохожий, а стоянка. */
    private val enemyStill = HashMap<String, Pair<Int, Int>>()
    private const val STILL_TICKS = 3
    private fun isStill(c: Creep, now: Int) = stillFor(c, now) >= STILL_TICKS
    private fun stillFor(c: Creep, now: Int) = enemyStill[idOf(c)]?.let { now - it.second } ?: 0
    /** Засада стоит на месте не меньше стольких тиков (охрана при эскорте на болоте стоит до двадцати, но при эскорте). */
    private const val CAMP_STILL = 15
    private fun protoName(o: GameObject): String? {
        val ctor = o.asDynamic().constructor
        if (ctor == null || ctor == undefined) return null
        val name = ctor.name
        return if (jsTypeOf(name) == "string") name.unsafeCast<String>() else null
    }
    private fun isEscort(c: Creep): Boolean = idOf(c) in escortIds
    private fun onCell(c: Position, p: Position?) = p != null && c.x == p.x && c.y == p.y
    private fun dist(a: Position, b: Position) = getRange(a, b)

    // ==================== маршрут и приходы ====================

    /**
     * Шаг эскорта по его полю. Из клеток, что ближе к флагу, берётся ближайшая; при равенстве — вплотную к спавну,
     * пока спавн рождает тягача (спавн-пул: 76561198870429455#24 шёл (8,91) (9,91) (10,90), и каждая из этих клеток
     * не удлиняла путь). Клетки врагов непроходимы; свои уступают дорогу.
     */
    private fun escortStep(w: World, from: Position, flow: IntArray, preferSpawn: Boolean): Position? {
        val here = flow[key(from)]
        if (here <= 0) return null
        var best: Position? = null
        var bestD = Int.MAX_VALUE
        var bestNear = false
        for ((dx, dy) in DIRECTIONS) {
            val x = from.x + dx; val y = from.y + dy
            if (!DistanceMap.inBounds(x, y)) continue
            val k = x * 100 + y
            val d = flow[k]
            if (d < 0 || d >= here) continue
            if (k in w.enemyAt) continue
            val near = preferSpawn && w.mySpawn != null && maxOf(kotlin.math.abs(x - w.mySpawn.x), kotlin.math.abs(y - w.mySpawn.y)) <= 1
            if (d < bestD || (d == bestD && near && !bestNear)) { bestD = d; best = InfluenceMap.cell(x, y); bestNear = near }
        }
        return best
    }

    /** Клетки маршрута эскорта до флага (тем же выбором шага, что и сам эскорт, без учёта крипов). */
    private fun routeCells(w: World, from: Position, flow: IntArray, preferSpawn: Boolean): List<Opening.Cell> {
        val out = ArrayList<Opening.Cell>()
        var cur: Position = from
        var guard = 0
        while (guard++ < 400) {
            val here = flow[key(cur)]
            if (here <= 0) break
            var best: Position? = null
            var bestD = Int.MAX_VALUE
            var bestNear = false
            for ((dx, dy) in DIRECTIONS) {
                val x = cur.x + dx; val y = cur.y + dy
                if (!DistanceMap.inBounds(x, y)) continue
                val d = flow[x * 100 + y]
                if (d < 0 || d >= here) continue
                val near = preferSpawn && w.mySpawn != null && maxOf(kotlin.math.abs(x - w.mySpawn.x), kotlin.math.abs(y - w.mySpawn.y)) <= 1
                if (d < bestD || (d == bestD && near && !bestNear)) { bestD = d; best = InfluenceMap.cell(x, y); bestNear = near }
            }
            if (best == null) break
            out.add(Opening.Cell(best.x, best.y, DistanceMap.isSwamp(best.x, best.y)))
            cur = best
        }
        return out
    }

    /** Тики пути эскорта по полю при данном ΣMOVE поезда (голова — эскорт, вес — его). */
    private fun routeTicks(escort: Creep, flow: IntArray, moves: Int): Int {
        var cell = key(escort)
        if (flow[cell] < 0) return Int.MAX_VALUE / 4
        val weight = Bodies.weight(escort)
        var ticks = maxOf(0, escort.fatigue) / maxOf(1, 2 * moves)
        var steps = 0
        while (flow[cell] > 0 && steps < 400) {
            val cx = cell / 100; val cy = cell % 100
            var best = -1
            var bestFlow = flow[cell]
            for ((dx, dy) in DIRECTIONS) {
                val nx = cx + dx; val ny = cy + dy
                if (!DistanceMap.inBounds(nx, ny)) continue
                val f = flow[nx * 100 + ny]
                if (f in 0 until bestFlow) { bestFlow = f; best = nx * 100 + ny }
            }
            if (best < 0) break
            cell = best
            steps++
            ticks += Bodies.period(weight, moves, DistanceMap.isSwamp(cell / 100, cell % 100))
        }
        return ticks
    }

    /** Цепь поезда: MOVE эскорта и тягачей, стоящих вплотную цепочкой (или идущих к ней — они догонят). */
    private fun ourTrainMoves(w: World): Int {
        val escort = w.escort ?: return 0
        return Bodies.liveMoves(escort) + w.pullers.sumOf { Bodies.liveMoves(it) }
    }

    private fun ourArrival(w: World): Int {
        val escort = w.escort ?: return Int.MAX_VALUE / 4
        val flow = w.escortFlow ?: return Int.MAX_VALUE / 4
        if (onCell(escort, w.myFlag)) return 0
        return routeTicks(escort, flow, ourTrainMoves(w))
    }

    /** Экономический дебют (null — ещё не решён, решается на 2-м тике). */
    private var econ: Boolean? = null
    /** Эскорт ждёт дома победителя их тяжёлого (decideHold, экономика). */
    private var econWait = false

    /** Доход в тик: спавн сам +1, и добытчики у источника — по 2 с каждой WORK. */
    private fun income(w: World): Int {
        val src = w.homeSource ?: return 1
        return 1 + w.mine.filter { !isEscort(it) && !it.spawning && dist(it, src) <= 1 }.sumOf { 2 * Bodies.live(it, WORK) }
    }

    /**
     * Экономический дебют (docs/escort-run-econ.md): добытчик W3 без MOVE и CARRY (добыча на пол), носильщик C1M1 —
     * подтягивает его к домашнему источнику и возит упавшую энергию на спавн. 400 из стартовых 500; тягачи — с дохода
     * (п. 2°°), доход ~6 в тик (Suruks#2: +302 за тики 50–100). Тягач меньше PULLER_MIN_MOVE считался бы разведчиком.
     */
    private fun econOrder(w: World, e: Int): Boolean {
        val mine = w.mine.filter { !isEscort(it) }
        // после первого тягача (200 на 1-м тике) остаётся ~300: носильщик 100 и добытчик на остальное (W2, 4 в тик)
        val works = (e - Bodies.cost(CARRY) - Bodies.cost(MOVE)).coerceIn(2 * Bodies.cost(WORK), 3 * Bodies.cost(WORK)) / Bodies.cost(WORK)
        val steps = listOf(
            Triple(mine.none { Bodies.isHauler(it) }, arrayOf<BodyPartType>(CARRY, MOVE), "hauler"),
            Triple(mine.none { Bodies.has(it, WORK) && !Bodies.has(it, CARRY) }, Array<BodyPartType>(works) { WORK }, "miner"))
        for ((missing, body, role) in steps) {
            if (!missing) continue
            if (e >= Bodies.cost(body)) { order(w, body, role, "economy opening"); return true }
            saving(w, "economy $role", Bodies.cost(body)); return true
        }
        return false
    }

    /** Тягачи врага: его тела из одних MOVE в трёх клетках от его эскорта (они и держат его скорость). */
    private fun enemyPullers(w: World): List<Creep> {
        val e = w.enemyEscort ?: return emptyList()
        return w.enemies.filter { !isEscort(it) && Bodies.isPuller(it, PULLER_MIN_MOVE) && dist(it, e) <= 3 }
    }

    /** Приход чужого эскорта: по телу и его поезду, а если он движется медленнее — по наблюдённому темпу. */
    private fun theirArrival(w: World): Int {
        val e = w.enemyEscort ?: return Int.MAX_VALUE / 4
        val flow = w.enemyEscortFlow ?: return Int.MAX_VALUE / 4
        if (onCell(e, w.enemyFlag)) return 0
        val moves = Bodies.liveMoves(e) + enemyPullers(w).sumOf { Bodies.liveMoves(it) } +
            // тягачи в пути к нему тоже его — через десяток тиков они в цепи
            w.enemies.filter { !isEscort(it) && Bodies.isPuller(it, PULLER_MIN_MOVE) && dist(it, e) in 4..20 }.sumOf { Bodies.liveMoves(it) }
        val byBody = routeTicks(e, flow, moves)
        val here = flow[key(e)]
        if (here < 0) return byBody
        if (enemyEscortHist.isEmpty() || enemyEscortHist.last().first != w.now) enemyEscortHist.addLast(w.now to here)
        while (enemyEscortHist.isNotEmpty() && enemyEscortHist.first().first < w.now - 40) enemyEscortHist.removeFirst()
        val (t0, d0) = enemyEscortHist.first()
        // стоящий сорок тиков эскорт не придёт никогда (stachu3478#9/#10 свой почти не двигал) — но темп ИДУЩЕГО
        // эскорта по наблюдению врёт: сорок тиков болота в центре (период 10) давали «160» тому, кто пришёл через 84
        // (стенд match4:racer — блокировщик не покупался, гонка проиграна)
        if (w.now - t0 >= 40 && d0 == here) return Int.MAX_VALUE / 4
        return byBody
    }

    // ==================== дебют ====================

    /** Прогон дебюта на первом тике (лимит CPU — секунда): все разбиения OPENING_MOVES на 1..3 тягача. */
    private fun planOpening(w: World) {
        val escort = w.escort
        val spawn = w.mySpawn
        val flow = w.escortFlow
        if (escort == null || spawn == null || flow == null) { openingPlan = listOf(OPENING_MOVES); return }
        val route = routeCells(w, escort, flow, preferSpawn = true)
        val energy = spawn.store[RESOURCE_ENERGY] ?: 0
        var best: Opening.Result? = null
        val all = ArrayList<Opening.Result>()
        for (split in Opening.splits(OPENING_MOVES, PULLER_MIN_MOVE)) {
            val r = Opening.simulate(escort.x, escort.y, Bodies.weight(escort), Bodies.liveMoves(escort), escort.fatigue, route,
                spawn.x, spawn.y, w.now, energy, 1.0, split, Bodies.cost(MOVE))
            all.add(r)
            if (best == null || r.arrival < best.arrival || (r.arrival == best.arrival && r.orders.size < best.orders.size)) best = r
        }
        openingPlan = best?.orders ?: listOf(OPENING_MOVES)
        println("opening t=${w.now}: route=${route.size} cells (swamp ${route.count { it.swamp }}), best $best; " +
            all.sortedBy { it.arrival }.take(6).joinToString(" | "))
    }

    /**
     * Куда спавн выпускает рождённого: по умолчанию движок берёт первую свободную клетку из directions = [TOP, …], и
     * наш верхний спавн (9,9) выпускал каждого крипа в (9,8) — от маршрута, а их нижний (9,90) — в (9,89), к центру.
     * Два шага лишних на каждом крипе: хранитель, рождённый в один тик с их блокировщиком, приходил к флагу на тик
     * позже и отдавал клетку (6ab83f48, ricardo#4). Направления сортируются по полю к нашему флагу (все пути идут
     * через центр) — первый выход ближе к маршруту.
     */
    private fun aimSpawn(w: World) {
        val spawn = w.mySpawn ?: return
        val flag = w.myFlag ?: return
        val f = flowTo("scout:${flag.x},${flag.y}", flag, w.blocked, 1)
        val dirs = DIRECTIONS.filter { (dx, dy) -> DistanceMap.inBounds(spawn.x + dx, spawn.y + dy) && f[(spawn.x + dx) * 100 + spawn.y + dy] >= 0 }
            .sortedBy { (dx, dy) -> f[(spawn.x + dx) * 100 + spawn.y + dy] }
        if (dirs.isEmpty()) return
        val arr = dirs.map { (dx, dy) -> screeps.api.getDirection(dx, dy) }.toTypedArray()
        val rc = spawn.asDynamic().setDirections(arr)
        println("spawn directions: ${dirs.joinToString(" ") { (dx, dy) -> "(${spawn.x + dx},${spawn.y + dy})=${f[(spawn.x + dx) * 100 + spawn.y + dy]}" }} rc=$rc")
    }

    // ==================== спавн ====================

    private fun energyOf(w: World) = w.mySpawn?.store?.get(RESOURCE_ENERGY) ?: 0

    internal fun order(w: World, body: Array<BodyPartType>, role: String, why: String): Boolean {
        val spawn = w.mySpawn ?: return false
        val r = spawn.spawnCreep(body)
        if (r.`object` != null) {
            println("spawn t=${w.now}: $role ${Bodies.summary(body)} cost=${Bodies.cost(body)} — $why")
            return true
        }
        println("spawn t=${w.now}: $role ${Bodies.summary(body)} FAILED err=${r.error} — $why")
        return false
    }

    private fun saving(w: World, what: String, cost: Int) {
        if (DEBUG_LOG && w.now % LOG_EVERY == 0) println("spawn t=${w.now}: saving for $what e=${energyOf(w)}/$cost")
    }

    /** Разведчики по заданию (живые и рождающиеся; заказанный, но ещё не увиденный — в очереди). */
    private fun scoutsOn(w: World, mission: String) = w.scouts.count { scoutMission[idOf(it)] == mission } + scoutQueue.count { it == mission }

    private fun runSpawn(w: World) {
        val spawn = w.mySpawn ?: return
        if (spawn.spawning != null) return
        val e = energyOf(w)
        val escort = w.escort
        if (RedTeam.spawn(w, e, late = false)) return

        // 0. дебют решается на 2-м тике (docs/escort-run-econ.md): на 1-м их первый крип не виден. Их первый — тягач —
        //    гонка, дебют M4+M6; иначе их эскорт идёт сам, запас гонки ~200 тиков, и он идёт в экономику: у всех, кто
        //    нас ещё бьёт (ricardo M4A3, Suruks W3, stachu W3M1C1), так, а при доходе 1 в тик бойцов к нужному тику нет
        // Первый тягач дебюта покупается на 1-м тике в любом случае: ожидание решения стоило гонщикам тика в каждой игре
        // (лига v37: зеркало main 10-16-4 против 14-14-2); решение — на 2-м, вместо второго тягача
        if (econ == null && w.now >= 2) {
            val first = (w.enemyPending + w.enemies).filter { !isEscort(it) }
            // Экономика окупается, когда их первая трата — ТЯЖЁЛЫЙ боец и ничего из одних MOVE: их доход остаётся 1 в тик,
            // угроза одна и уже на поле, их эскорт идёт пешком (запас гонки ~200), и наш доход успевает купить её
            // победителя. Первая трата — экономика (W3 Suruks, W3M1C1 stachu): их армия придёт позже, быстрый поезд
            // проходит центр раньше неё (A/B против stachu#10: гонка 5-3, экономика 4-4 — наш медленный поезд пришёл к их
            // M1A1 и M3A3). Лёгкий первый боец (M1A1 けろびー#32) — гонка с ранним защитником вместо второго тягача (v29).
            // Любой их крип из одних MOVE — гонка: хранителя M1 первым ставят и гонщики (76561198870429455, ShuP1, けろびー)
            econ = first.isNotEmpty() && first.none { Bodies.isPureMove(it) } && first.any { Bodies.wasArmed(it) && heavy(it) }
            println("opening t=${w.now}: their first ${first.joinToString(" ") { Bodies.summaryOf(it) }} — ${if (econ == true) "ECONOMY" else "race"}")
            if (econ == true) openingIdx = openingPlan?.size ?: 0
        }
        if (econ == true && econOrder(w, e)) return

        // 1. дебют: тягачи по прогону. (v16-v17 меняли против «экономиста» второго тягача на охрану поезда: stachu3478
        //    это било в трёх из пяти, но けろびー — тоже экономист по первому заказу, без раннего бойца, — его M5A1 со
        //    120-го и R5M5 со 177-го догоняли медленный поезд: 1 из 7 при v16 и 0 из 2 при v17, где M6 ждал до 56-го.
        //    Убрано: против stachu держит дом, см. decideHold.)
        val plan = openingPlan
        // лёгкий вооружённый враг уже в поле к покупке второго тягача, а тягачей у них нет (экономист: их эскорт дома) —
        // вместо тягача победитель его дуэли: он идёт с поездом и снимает перехватчика у центра (けろびー#32: M1A1 с 4-го
        // тика ждал в центре; тягач за 300 — это 250 тиков до защитника, и поезд терял тягачей и эскорт). Тяжёлого
        // (M4A3 ricardo#23) на наличные не победить — там полный поезд проходит центр раньше их пары
        if (plan != null && openingIdx in 1 until plan.size && escort != null && !earlyDefender) {
            val light = (w.enemyArmed + w.enemyPending.filter { Bodies.wasArmed(it) }).filter { !isEscort(it) && !heavy(it) }
            val theirPullers = enemyPullers(w).isNotEmpty() || w.enemyPending.any { Bodies.isPuller(it, PULLER_MIN_MOVE) }
            if (light.isNotEmpty() && !theirPullers) {
                val body = fieldWinner(light, emptyList(), SPAWN_ENERGY_CAPACITY)
                if (body != null && Bodies.cost(body) <= e + 30) {
                    earlyDefender = true
                    openingIdx = plan.size
                    println("spawn t=${w.now}: EARLY DEFENDER ${Bodies.summary(body)} instead of the rest of the opening — light ${light.joinToString(" ") { Bodies.summaryOf(it) }}, no puller of theirs")
                }
            }
        }
        if (plan != null && openingIdx < plan.size && escort != null) {
            val body = Bodies.moves(plan[openingIdx])
            if (e >= Bodies.cost(body)) {
                if (order(w, body, "puller", "opening ${openingIdx + 1}/${plan.size} plan=M${plan.joinToString("+M")}")) openingIdx++
            } else saving(w, "opening puller", Bodies.cost(body))
            return
        }

        if (RedTeam.spawn(w, e, late = true)) return

        val ours = ourArrival(w)
        val theirs = theirArrival(w)

        if (holding) { holdSpawn(w, e, ours, theirs); return }

        // 2. оборона поезда: боевые враги у эскорта сильнее наших бойцов рядом с ним
        if (escort != null) {
            // захватчик нашего флага — дело стража флага (п. 3, тело под захватчиков): здесь он копил 600 на M3R3 против
            // стрелка на флаге, и страж не покупался вовсе (стенд rsq, Suruks#2)
            val threats = w.enemyArmed.filter { !onOurFlag(w, it) && (dist(it, escort) <= THREAT_RANGE || approaching(w, it, escort)) }
            if (threats.isNotEmpty()) {
                val guards = w.fighters.filter { Bodies.isArmed(it) && dist(it, escort) <= THREAT_RANGE + 5 }
                if (!wins(guards, threats)) {
                    val body = cheapestWinner(threats, guards, SPAWN_ENERGY_CAPACITY)
                    // копить на защитника, который родится после нашего финиша, — запереть спавн ни за что: M3R3 за 600
                    // против стрелка копился со 150-го (энергия 1 в тик), и ни хранитель, ни страж флага не покупались
                    // (стенд rsq). Не успевает — спавн идёт дальше по очереди
                    val ready = ourArrival(w).let { ours -> body != null && Bodies.cost(body) - e < ours }
                    if (body != null && ready) {
                        if (e >= Bodies.cost(body)) { if (order(w, body, "defender", "threats ${threats.joinToString(" ") { Bodies.summaryOf(it) + "@" + dist(it, escort) }} guards=${guards.size}")) fighterQueue.addLast(ESCORT_GUARD); return }
                        saving(w, "defender ${Bodies.summary(body)}", Bodies.cost(body)); return
                    }
                }
            }
        }


        // 2''. наш флаг закрыт сооружением — пролом: мили на всю наличную энергию, затем ещё, пока пролом стоит
        if (breachOrder(w, e)) return

        // 2°. вооружённый враг в поле (не телохранитель, не у своей базы), которого наши бойцы не бьют, а победитель дуэли
        //     недорог, — победитель раньше хранителя и блокировщиков: перехватчик けろびー#32 (M1A1 в центре с 50-го) бил
        //     тягачей и 166 тиков эскорт, а защитник копился за хранителем и разведчиками до 361-го (v28 и v29 0-6)
        // в экономике — и телохранитель: его бросок на поезд (ricardo) и есть то, против чего копится доход
        val fieldArmed = w.enemyArmed.filter { !isEscort(it) && (econ == true || !bodyguard(w, it)) && !onOurFlag(w, it) && (w.enemySpawn == null || dist(it, w.enemySpawn) > CAMP_BASE_RANGE) }
        if (fieldArmed.isNotEmpty() && !wins(w.fighters.filter { Bodies.isArmed(it) }, fieldArmed) && !fighterQueue.contains(ESCORT_GUARD)) {
            val body = fieldWinner(fieldArmed, w.fighters.filter { Bodies.isArmed(it) }, SPAWN_ENERGY_CAPACITY)
            // и только тот, кто родится до нашего финиша: T1M2A1 против стрелка Suruks копился со 130-го по 240-й и
            // родился бы к ~290-му, а спавн тем временем не купил хранителя, который удержал бы флаг (v35, 6abac619)
            val budgetCap = if (econ == true) SPAWN_ENERGY_CAPACITY else EARLY_WINNER_BUDGET
            if (body != null && Bodies.cost(body) <= budgetCap && Bodies.cost(body) - e < ourArrival(w) * income(w)) {
                if (e >= Bodies.cost(body)) { if (order(w, body, "defender", "field threat ${fieldArmed.joinToString(" ") { Bodies.summaryOf(it) + "@(" + it.x + "," + it.y + ")" }}")) fighterQueue.addLast(ESCORT_GUARD); return }
                saving(w, "field defender ${Bodies.summary(body)}", Bodies.cost(body)); return
            }
        }

        // 2°°. экономика: тягачи с дохода, пока у поезда меньше OPENING_MOVES MOVE (дебют дал один M2)
        if (econ == true && escort != null) {
            val have = w.mine.filter { !isEscort(it) && Bodies.isPuller(it, PULLER_MIN_MOVE) }.sumOf { it.body.count { p -> p.type == MOVE } }
            if (have < OPENING_MOVES) {
                // не меньше PULLER_MIN_MOVE: M1 считался разведчиком, в поезд не вставал, и правило покупало его снова и снова
                val body = Bodies.moves(maxOf(PULLER_MIN_MOVE, minOf(OPENING_MOVES - have, e / Bodies.cost(MOVE))))
                if (e >= Bodies.cost(body)) { order(w, body, "puller", "economy: the train has $have MOVE of $OPENING_MOVES"); return }
                saving(w, "train puller", Bodies.cost(body)); return
            }
        }

        // гонка проиграна, только если их приход РАНЬШЕ нашего с запасом: ничьи по оценке на 51-м тике (наш 194-197,
        // их 196-210 во всей серии v11) на деле выигрывал наш поезд, а блокировщик, купленный первым «на всякий
        // случай», отдавал наш флаг их блокировщику (ricardo#5 трижды)
        val raceLost = theirs + RACE_MARGIN < ours
        val myFlag = w.myFlag
        val enemyFlag = w.enemyFlag

        // при проигранной гонке сперва их флаг: только он и выигрывает матч; наш хранитель подождёт
        if (raceLost && theirFlagOrder(w, e, ours, theirs)) return

        // 3. НАШ ФЛАГ. Враг на клетке или вплотную (блокировщик ждёт, когда хранитель сойдёт, и занимает клетку
        //    подхода эскорта) — страж, если нашего стража у флага нет. Флаг пуст — хранитель M1, но только если он
        //    успеет РАНЬШЕ их разведчика: иначе клетку займут до него, и нужен страж с оружием (он и есть хранитель,
        //    только умеющий снять того, кто пришёл первым). Живой матч 6ab83551 (#7): их M1 с первого тика — на
        //    нашем флаге к сотому, наш эскорт простоял у флага до их финиша.
        if (myFlag != null && escort != null && !onCell(escort, myFlag)) {
            val squatters = w.enemies.filter { dist(it, myFlag) <= FLAG_GUARD_RANGE }
            val guard = fightersOn(w, GUARD_FLAG)
            val keeperEta = scoutEta(w, myFlag)
            val rival = enemyScoutEta(w, myFlag, "MINE")
            val needGuard = guard == 0 && (squatters.isNotEmpty() || (scoutsOn(w, KEEP) == 0 && rival < keeperEta) ||
                (scoutsOn(w, KEEP) > 0 && w.scouts.none { scoutMission[idOf(it)] == KEEP && onCell(it, myFlag) } && rival < scoutEtaOfKeeper(w, myFlag)))
            if (needGuard) {
                val targetHits = maxOf(100, squatters.maxOfOrNull { it.hits } ?: 100)
                val pick = fastHunter(w, myFlag, targetHits, e, squatters)
                // страж «на опережение» (флаг ещё пуст, но их разведчик успеет раньше нашего хранителя) нужен, только
                // если сам успевает к приходу эскорта; иначе эти деньги сперва идут в блокировщик их флага (ниже)
                if (pick != null && (squatters.isNotEmpty() || pick.second <= ours + GUARD_SLACK)) {
                    val (body, arrive) = pick
                    if (e >= Bodies.cost(body)) {
                        if (order(w, body, "flag-guard", "our flag: squatters=${squatters.joinToString(" ") { Bodies.summaryOf(it) + "@" + dist(it, myFlag) }} rivalEta=$rival keeperEta=$keeperEta arrive=$arrive ours=$ours theirs=$theirs")) fighterQueue.addLast(GUARD_FLAG)
                        return
                    }
                    saving(w, "flag guard ${Bodies.summary(body)} arrive=$arrive", Bodies.cost(body)); return
                }
            }
            // страж не куплен (не успевает к эскорту) — хранитель всё равно нужен: их разведчик, «идущий к нам» у
            // центра, часто идёт к СВОЕМУ флагу, а без хранителя наш флаг брал их поздний блокировщик (стенд
            // rev+keep+blk при блокировщике на 51-м: страж на 231-м, поражение на 298-м)
            if (ours > KEEPER_MIN_LEAD && scoutsOn(w, KEEP) == 0 && guard == 0 && squatters.isEmpty() && w.enemies.none { onCell(it, myFlag) }) {
                // хранитель — раньше блокировщика их маршрута. v22-v23 ставили блокировщик первым, когда их разведчиков
                // ещё не видно (так лига била v21, 3-1), и живьём отдали флаг ShuP1#3: его M1, рождённый в тот же 51-й
                // тик, у центра читался идущим к своему флагу и сел на наш к ~190-му, до нашего хранителя (6ab8fcb0)
                val body = Bodies.moves(1)
                if (e >= Bodies.cost(body)) { if (order(w, body, "keeper", "our flag is empty; keeperEta=$keeperEta rivalEta=$rival ours=$ours theirs=$theirs")) scoutQueue.addLast(KEEP); return }
                saving(w, "keeper", Bodies.cost(body)); return
            }
            if (holdKeepers(w, e, ours)) return
        }

        // 4'. клетки подхода. Их M1, ждущий у нашего флага, пока наш хранитель на клетке, встаёт ровно на клетку
        //     подхода нашего эскорта (он идёт от центра тем же путём), и эскорт обходит его — на карте 6ab843ac обход
        //     пришёлся на болото (десять тиков вместо двух), гонку, выигранную на пути, проиграли на последней клетке
        //     (76561198870429455#31 и #34 — 101-й тик, M1 к нашему флагу). Второй M1 идёт туда, где обход дороже: на
        //     клетку подхода НАШЕГО эскорта (второй хранитель) или ИХ (блокировщик); свободный их флаг — всегда блок.
        //     Цена обхода — по полю маршрута с перекрытой клеткой подхода, из местности каждой карты.
        // 3'. их маршрут: блокировщик на самое дорогое для их поезда место (один M1 после дебюта обыгрывал v19 четыре
        //     из четырёх). После хранителя нашего флага: поставленный раньше него, он отдавал наш флаг их раннему
        //     блокировщику (стенд match4:rev+keep+blk — поражение на 298-м вместо победы на 245-м)
        // 3''. их флаг свободен и наш M1 успеет раньше их разведчиков — туда, раньше блокировщика маршрута. Правило v19,
        //      которое v20 заслонил блокировщиком: «два блокировщика маршрута» (CC) шлют первый M1 на НАШ путь, и к
        //      100-му тику он читается идущим к нам, а их флаг пуст до ~240-го; хранитель обычного соперника к тому же
        //      тику уже идёт к своему флагу и флаг не отдаёт (офлайн: CC против v26 — 48 %)
        //      Но тот же ход против «M1 на наш флаг» и «раннего блокировщика» проигрывает гонку к их флагу их хранителю,
        //      купленному на том же 101-м (76 -> 57 % и 62 -> 51 %), а на 101-м тике их не различить. Детерминированный
        //      выбор бьётся одним ответом (худший случай 51-52 %), поэтому жребий: M1 на их флаг с вероятностью
        //      FLAG_FIRST_P (расчётный худший случай ~60 %), иначе блокировщик маршрута. Тянется один раз за матч
        val theirFreeEarly = w.enemyFlag != null && w.occupant[key(w.enemyFlag)] == null &&
            enemyScoutEta(w, w.enemyFlag, "THEIRS") > scoutEta(w, w.enemyFlag)
        if (theirFreeEarly && flagFirst == null) {
            flagFirst = kotlin.random.Random.nextDouble() < FLAG_FIRST_P
            println("mix t=${w.now}: their flag free before our choke — draw ${if (flagFirst == true) "FLAG first" else "choke first"} (p=$FLAG_FIRST_P)")
        }
        if (!raceLost && theirFreeEarly && flagFirst == true && theirFlagOrder(w, e, ours, theirs, blockerOnly = true)) return
        if (chokeOrder(w, e)) return

        if (!raceLost) {
            val ourPen = detourPenalty(w.escort, w.escortFlow, w.myFlag, w.blocked, "ours")
            val theirPen = detourPenalty(w.enemyEscort, w.enemyEscortFlow, w.enemyFlag, w.blockedForEnemy, "theirs")
            val theirFree = w.enemyFlag != null && w.occupant[key(w.enemyFlag)] == null && enemyScoutEta(w, w.enemyFlag, "THEIRS") > scoutEta(w, w.enemyFlag)
            val approachFirst = !theirFree && ourPen > 0 && ourPen >= theirPen
            if (approachFirst && approachOrder(w, e, ours, ourPen, theirPen)) return
            if (theirFlagOrder(w, e, ours, theirs, blockerOnly = true)) return
            if (ourPen > 0 && approachOrder(w, e, ours, ourPen, theirPen)) return
        }

        // 5'. наш маршрут (docs/escort-run-redteam.md): их крип, стоявший на нашем пути, — чистильщик. После всех M1:
        //     копя 130, он на 120 тиков запирал спавн и не давал купить ни второго хранителя, ни M1 на их свободный
        //     флаг (офлайн-лига: «два блокировщика маршрута» брали 57 рук из 80)
        if (clearOrder(w, e)) return

        // 7. доход: пока боевого врага нет рядом, деньги работают в добытчике
        if (w.workers.isEmpty() && w.homeSource != null && arenaInfo.ticksLimit - w.now > 400) {
            val body = arrayOf(MOVE, CARRY, WORK, WORK)
            if (e >= Bodies.cost(body)) { order(w, body, "harvester", "income"); return }
            saving(w, "harvester", Bodies.cost(body)); return
        }

        // 8. армия: полный боец (мили — дешевле за урон, и почти все цели безоружны)
        val body = meleeBody(minOf(e, SPAWN_ENERGY_CAPACITY))
        if (body != null && e >= 520 && order(w, body, "fighter", "army")) fighterQueue.addLast(ESCORT_GUARD)
    }

    /**
     * ИХ ФЛАГ — только если их эскорт придёт не позже нашего. Флаг свободен и наш M1 успевает раньше их разведчика —
     * блокировщик за пятьдесят; флаг держит их хранитель (или их разведчик придёт первым) — ломатель: мили, который
     * успевает убить хранителя и встать на клетку до прихода их эскорта. Не успевает — не покупаем вовсе: деньги
     * нужнее дома. Хранитель на клетке пропускает свой эскорт обменом (rate1), поэтому блокировщик рядом с чужим
     * хранителем бесполезен. true — заказ сделан или копим на него.
     */
    private fun theirFlagOrder(w: World, e: Int, ours: Int, theirs: Int, blockerOnly: Boolean = false): Boolean {
        val enemyFlag = w.enemyFlag ?: return false
        if (w.enemyEscort != null && theirs > 0 && w.active.none { onCell(it, enemyFlag) }) {
            val held = w.enemies.firstOrNull { onCell(it, enemyFlag) }
            val rival = enemyScoutEta(w, enemyFlag, "THEIRS")
            val blockerEta = scoutEta(w, enemyFlag)
            // блокировщик полезен и при их хранителе на флаге: он встаёт на клетку подхода их эскорта (runScouts) и
            // стоит им обхода — двух тиков гонки; а свободный флаг он просто занимает
            if (scoutsOn(w, BLOCK) == 0 && fightersOn(w, BREAK) == 0 && blockerEta < theirs) {
                val body = Bodies.moves(1)
                if (e >= Bodies.cost(body)) { if (order(w, body, "blocker", "their flag ${held?.let { "held by " + Bodies.summaryOf(it) } ?: "free"}: blockerEta=$blockerEta rivalEta=$rival ours=$ours theirs=$theirs")) scoutQueue.addLast(BLOCK); return true }
                saving(w, "blocker", Bodies.cost(body)); return true
            }
            if (!blockerOnly && (held != null || rival <= blockerEta) && fightersOn(w, BREAK) == 0) {
                val pick = fastHunter(w, enemyFlag, maxOf(100, held?.hits ?: 100), e, listOfNotNull(held))
                if (pick != null && pick.second < theirs) {
                    val (body, arrive) = pick
                    if (e >= Bodies.cost(body)) { if (order(w, body, "breaker", "their flag ${held?.let { Bodies.summaryOf(it) } ?: "rival@$rival"}; arrive=$arrive ours=$ours theirs=$theirs")) fighterQueue.addLast(BREAK); return true }
                    saving(w, "breaker ${Bodies.summary(body)} arrive=$arrive theirs=$theirs", Bodies.cost(body)); return true
                }
            }
        }
        return false
    }

    private var chokesOrdered = 0
    /** Путь убийства (см. runSpawn): наш боец убивает их тяжёлого и их одинокий эскорт. */
    private var killPath = false
    /** Вместо второго тягача куплен (или копится) победитель лёгкого перехватчика (см. runSpawn). */
    private var earlyDefender = false
    /** Жребий развилки «их флаг свободен»: null — ещё не тянули (см. 3'' в runSpawn). */
    private var flagFirst: Boolean? = null
    /** Доля матчей, где при свободном их флаге M1 идёт туда раньше блокировщика маршрута: минимакс по офлайн-матрице
     *  (v26 против v26 с личностями: C 62,5/51, CC 52,5/89, B 76/57 — ход «блокировщик» / ход «флаг»). */
    private const val FLAG_FIRST_P = 0.2
    /** Проломщиков одновременно не больше: каждый — ещё 30 урона в тик на часть ATTACK по 10 000 хитов. */
    private const val MAX_BREACHERS = 3

    /**
     * Пролом закрытого флага. Их эскорт после затычки идёт без тягачей и финиширует к ~800-му (офлайн-лига: затычка с
     * двумя блокировщиками брала у v25 35 % рук), так что время есть, а энергии — 1 в тик: ранний дешёвый мили
     * наносит больше урона к сроку, чем поздний большой, поэтому покупается сразу, как есть 130.
     */
    /** Поле к клетке ожидания пролома: в трёх клетках от флага, ближайшей к нему по пути эскорта. */
    private fun breachWaitFlow(w: World): IntArray? {
        val flag = w.myFlag ?: return null
        val raw = w.escortFlowRaw ?: return null
        var best = -1
        var bestD = Int.MAX_VALUE
        for (dx in -3..3) for (dy in -3..3) {
            if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 3) continue
            val x = flag.x + dx; val y = flag.y + dy
            if (!DistanceMap.inBounds(x, y) || DistanceMap.isWall(x, y)) continue
            val d = raw[x * 100 + y]
            if (d in 0 until bestD) { bestD = d; best = x * 100 + y }
        }
        if (best < 0) return null
        return flowTo("breachWait:$best", cellPos(best), w.blocked, 5)
    }

    private fun breachOrder(w: World, e: Int): Boolean {
        // их площадка на нашем флаге — пролом начинается, пока рампарт ещё строится: первый проломщик иначе ждал 130
        // до ~331-го при рампарте на ~259-м и опаздывал к их финишу на ~815-м (офлайн, затычка + блокировщик, 4 из 40)
        val b: GameObject = w.flagBlocker ?: w.flagSite ?: return false
        if (fightersOn(w, BREACH) >= MAX_BREACHERS) return false
        // тело — как у быстрого охотника: минимум «ждать энергию + идти + ломать 10 000»; M1A1 шёл по болоту 120 тиков и
        // ломал 333 — дольше, чем подождать M2A2
        val hits = if (w.flagBlocker != null) ((b.asDynamic().hits as? Int) ?: 10000) else 10000
        val body = fastHunter(w, b.unsafeCast<Position>(), hits, e, emptyList())?.first ?: meleeBody(minOf(e, SPAWN_ENERGY_CAPACITY)) ?: return false
        if (e < Bodies.cost(body)) { saving(w, "breacher ${Bodies.summary(body)}", Bodies.cost(body)); return true }
        if (order(w, body, "breacher", "our flag shut by ${protoName(b)}@(${b.asDynamic().x},${b.asDynamic().y}) hits=${b.asDynamic().hits}")) fighterQueue.addLast(BREACH)
        return true
    }

    private fun cellPos(k: Int): Position = InfluenceMap.cell(k / 100, k % 100)

    /** Клетка рядом с флагом (или сам флаг): там работают хранители и блокировщики флага, не CHOKE. */
    private fun nearFlag(k: Int, flag: Position?) = flag != null && maxOf(kotlin.math.abs(k / 100 - flag.x), kotlin.math.abs(k % 100 - flag.y)) <= 2

    /** Лучшая клетка их маршрута для нашего M1, который сейчас в `from` (или родится у спавна после `wait` тиков). */
    private fun chokePick(w: World, from: Position, wait: Int): Chokes.Pick? {
        val esc = w.enemyEscort ?: return null
        val flow = w.enemyEscortFlow ?: return null
        val route = Chokes.route(flow, esc)
        if (route.size < 3) return null
        val ours = w.escort?.let { e -> w.escortFlow?.let { Chokes.route(it, e).toHashSet() } } ?: HashSet()
        return Chokes.best(route, key(esc), { k -> wait + getRange(from, cellPos(k)) * 6 / 5 + 3 }, { k -> k in ours || nearFlag(k, w.enemyFlag) }, CHOKE_MIN_PENALTY)
    }

    private fun chokeOrder(w: World, e: Int): Boolean {
        if (chokesOrdered >= MAX_CHOKES || scoutsOn(w, CHOKE) > 0) return false
        val spawn = w.mySpawn ?: return false
        val body = Bodies.moves(1)
        val pick = chokePick(w, spawn, maxOf(0, Bodies.cost(body) - e) + 3) ?: return false
        // задержка их поезда нужна, пока гонка не решена: пробка на 12–60 тиков не меняет исхода, когда их эскорт и без
        // неё приходит позже нашего с запасом больше её цены и ошибки оценки (наш приход на 51-м тике оценивался в
        // 194–195 при живых ~250). Против пешего эскорта (Suruks, ricardo: наш запас ~216) эти 50 — лишний хранитель флага
        val ours = ourArrival(w)
        val theirs = theirArrival(w)
        if (theirs < Int.MAX_VALUE / 8 && theirs - ours > pick.penalty + RACE_ERR) return false
        if (e >= Bodies.cost(body)) {
            if (order(w, body, "choke", "their route (${pick.cell / 100},${pick.cell % 100}) costs them ${pick.penalty}, they reach it in ${pick.theirEta}")) { scoutQueue.addLast(CHOKE); chokesOrdered++ }
            return true
        }
        saving(w, "choke", Bodies.cost(body)); return true
    }

    /** Чужие крипы на НАШЕМ маршруте впереди эскорта (кроме тех, что у нашего флага — это дело стража флага), по порядку пути. */
    /** Их крипы, хоть раз СТОЯВШИЕ на нашем пути впереди эскорта: блокировщик перебегает и стоящим бывает недолго
     *  (стенд chk: чистильщик копился и снимался, пока тот бежал), поэтому метка остаётся до его смерти. */
    private val harassers = HashSet<String>()

    private fun routeBlockers(w: World, stillOnly: Boolean = false): List<Pair<Creep, Int>> {
        val esc = w.escort ?: return emptyList()
        val flow = w.escortFlowRaw ?: return emptyList()
        val route = Chokes.route(flow, esc)
        val idx = HashMap<Int, Int>()
        route.forEachIndexed { i, k -> idx[k] = i }
        val flag = w.myFlag
        return w.enemies.filter { !isEscort(it) && (flag == null || dist(it, flag) > FLAG_GUARD_RANGE) && (!stillOnly || isStill(it, w.now)) }
            .mapNotNull { c -> idx[key(c)]?.let { c to it } }.sortedBy { it.second }
    }

    private fun clearOrder(w: World, e: Int): Boolean {
        val esc = w.escort ?: return false
        val flow = w.escortFlowRaw ?: return false
        // метка: стоящий на нашем пути впереди, и обход его клетки стоит нам не меньше CLEAR_MIN_PENALTY
        val route = Chokes.route(flow, esc)
        for ((c, i) in routeBlockers(w, stillOnly = true)) {
            if (idOf(c) in harassers) continue
            val pen = Chokes.penalty(route, i, if (i == 0) key(esc) else route[i - 1])
            if (pen >= CLEAR_MIN_PENALTY) { harassers.add(idOf(c)); println("route t=${w.now}: harasser ${idOf(c)} ${Bodies.summaryOf(c)}@(${c.x},${c.y}) costs us $pen") }
        }
        harassers.retainAll(w.enemies.mapTo(HashSet()) { idOf(it) })
        if (fightersOn(w, CLEAR) > 0) return false
        val b = w.enemies.filter { idOf(it) in harassers }.minByOrNull { dist(it, esc) } ?: return false
        val (body, arrive) = fastHunter(w, b, b.hits, e, listOf(b)) ?: return false
        if (e >= Bodies.cost(body)) {
            if (order(w, body, "clearer", "harasser ${Bodies.summaryOf(b)}@(${b.x},${b.y}); arrive=$arrive")) fighterQueue.addLast(CLEAR)
            return true
        }
        saving(w, "clearer ${Bodies.summary(body)}", Bodies.cost(body)); return true
    }

    /** Второй хранитель на клетку подхода нашего эскорта: если его ещё нет, хранитель флага есть, и он успевает раньше эскорта. */
    private fun approachOrder(w: World, e: Int, ours: Int, ourPen: Int, theirPen: Int): Boolean {
        if (scoutsOn(w, APPROACH) > 0 || scoutsOn(w, KEEP) == 0) return false
        val a = approachCell(w.escort, w.escortFlow) ?: return false
        val eta = scoutEta(w, a)
        if (eta >= ours) return false
        val body = Bodies.moves(1)
        if (e >= Bodies.cost(body)) { if (order(w, body, "approach-keeper", "our approach (${a.x},${a.y}) detour costs us $ourPen vs them $theirPen; eta=$eta ours=$ours")) scoutQueue.addLast(APPROACH); return true }
        saving(w, "approach keeper", Bodies.cost(body)); return true
    }

    /** Клетки маршрута эскорта по полю, от следующей до флага включительно. */
    private fun routeOf(escort: Creep, flow: IntArray): List<Int> {
        val out = ArrayList<Int>()
        var cell = key(escort)
        var guard = 0
        while (flow[cell] > 0 && guard++ < 400) {
            val cx = cell / 100; val cy = cell % 100
            var best = -1
            var bestD = flow[cell]
            for ((dx, dy) in DIRECTIONS) {
                val nx = cx + dx; val ny = cy + dy
                if (!DistanceMap.inBounds(nx, ny)) continue
                val d = flow[nx * 100 + ny]
                if (d in 0 until bestD) { bestD = d; best = nx * 100 + ny }
            }
            if (best < 0) break
            out.add(best)
            cell = best
        }
        return out
    }

    /**
     * Во сколько тиков эскорту обойдётся чужой крип на его клетке подхода: поле к флагу с этой клеткой непроходимой,
     * разница в клетке ПЕРЕД ней (единица поля — два тика поезда на равнине, болото — пять единиц).
     */
    private fun detourPenalty(escort: Creep?, flow: IntArray?, flag: Position?, blocked: List<Position>, tag: String): Int {
        if (escort == null || flow == null || flag == null) return 0
        val route = routeOf(escort, flow)
        if (route.size < 3) return 0
        val a = route[route.size - 2]
        val p = route[route.size - 3]
        val g = flowTo("detour:$tag:$a", flag, blocked + InfluenceMap.cell(a / 100, a % 100), 5, ttl = 100)
        val d0 = flow[p]; val d1 = g[p]
        if (d0 < 0) return 0
        if (d1 < 0) return 99
        return (d1 - d0) * 2
    }

    private const val KEEP = "keep"
    /** Второй хранитель — на клетке ПОДХОДА нашего эскорта к флагу (см. runScouts). */
    private const val APPROACH = "approach"
    private const val BLOCK = "block"
    private const val BREAK = "break"
    /** Проломщик: бьёт сооружение, закрывшее наш флаг (затычка соперника). */
    private const val BREACH = "breach"
    private const val GUARD_FLAG = "flag"
    private const val ESCORT_GUARD = "escort"
    /** Разведчик на узком месте ИХ маршрута (Chokes): встаёт туда, где обход дороже всего их поезду, и перебегает вперёд. */
    private const val CHOKE = "choke"
    /** Боец, снимающий чужих крипов с НАШЕГО маршрута впереди поезда (ответ на CHOKE соперника). */
    private const val CLEAR = "clear"
    /** Блокировщик покупается, если клетка стоит их поезду не меньше стольких тиков. */
    private const val CHOKE_MIN_PENALTY = 8
    private const val MAX_CHOKES = 2
    /** Чистильщик покупается, если чужой крип на нашем маршруте стоит нам не меньше стольких тиков обхода. */
    private const val CLEAR_MIN_PENALTY = 4
    /** Гонка считается проигранной, если их приход раньше нашего больше чем на столько тиков (ошибка оценки — пара
     *  тиков; ничья по оценке — не проигрыш). */
    private const val RACE_MARGIN = 2
    /** Ошибка оценки гонки: наш приход на 51-м тике оценивался в 194–195 при живых ~250 (28.09.2026, серия v30). */
    private const val RACE_ERR = 60
    /** Хранитель не нужен, если эскорт придёт раньше, чем он дойдёт (путь M1 до флага от спавна ~100 клеток). */
    private const val KEEPER_MIN_LEAD = 20
    /** Страж флага на опережение покупается, только если приходит не позже эскорта плюс столько тиков. */
    private const val GUARD_SLACK = 20

    /** Враг идёт к нашему эскорту: его дистанция сокращается и он в тридцати клетках. */
    private fun approaching(w: World, e: Creep, escort: Creep): Boolean {
        val d = dist(e, escort)
        if (d > 30) return false
        val prev = lastEnemyDist[idOf(e)]
        lastEnemyDist[idOf(e)] = d
        return prev != null && d < prev
    }
    private val lastEnemyDist = HashMap<String, Int>()

    private fun wins(ours: List<Creep>, theirs: List<Creep>): Boolean {
        if (theirs.isEmpty()) return true
        if (ours.isEmpty()) return false
        val merged = Bodies.Unit(ours.flatMap { c -> c.body.map { it.type } }.toTypedArray(), ours.flatMap { c -> c.body.map { it.hits } }.toIntArray())
        return Bodies.duel(merged, theirs.map { Bodies.unitOf(it) }) >= 0
    }

    /** Мили-тело под бюджет: MOVE спереди (буфер урона, части гибнут с головы), ATTACK сзади; MOVE = ATTACK — клетка в
     *  тик по равнине. */
    private fun meleeBody(budget: Int): Array<BodyPartType>? {
        val pair = Bodies.cost(MOVE) + Bodies.cost(ATTACK)
        val n = minOf(budget / pair, 25)
        if (n <= 0) return null
        return Array(n) { MOVE } + Array(n) { ATTACK }
    }

    private fun rangedBody(budget: Int): Array<BodyPartType>? {
        val pair = Bodies.cost(MOVE) + Bodies.cost(RANGED_ATTACK)
        val n = minOf(budget / pair, 25)
        if (n <= 0) return null
        return Array(n) { MOVE } + Array(n) { RANGED_ATTACK }
    }

    /** Самое дешёвое тело, которое вместе с `helpers` выигрывает дуэль у `threats`. Против чистых стрелков — стрелок
     *  (мили не догонит кайтящего), иначе мили (урон за энергию втрое дешевле). */
    private fun cheapestWinner(threats: List<Creep>, helpers: List<Creep>, cap: Int): Array<BodyPartType>? {
        val kiters = threats.isNotEmpty() && threats.all { Bodies.has(it, RANGED_ATTACK) && !Bodies.has(it, ATTACK) }
        val pair = Bodies.cost(MOVE) + Bodies.cost(if (kiters) RANGED_ATTACK else ATTACK)
        for (n in 1..minOf(cap / pair, 25)) {
            val body = if (kiters) rangedBody(n * pair)!! else meleeBody(n * pair)!!
            val merged = helpers.flatMap { c -> c.body.map { it.type } } + body.toList()
            val hits = helpers.flatMap { c -> c.body.map { it.hits } } + body.map { 100 }
            if (Bodies.duel(Bodies.Unit(merged.toTypedArray(), hits.toIntArray()), threats.map { Bodies.unitOf(it) }) >= 0) return body
        }
        return null
    }

    /**
     * Победитель, который ПОСЛЕ боя ещё ходит: TOUGH спереди (гибнет первым), MOVE, ATTACK; самое дешёвое тело, которое
     * выигрывает дуэль и сохраняет хоть один MOVE, при MOVE не меньше трети остального (три тика на клетку по равнине —
     * быстрее их одинокого эскорта). M4A4 против M4A3 ricardo#23 выигрывал, но терял все MOVE (они спереди) и стоял, пока
     * их эскорт проходил мимо к флагу; T8M4A3 стоит столько же.
     */
    private fun fieldWinner(threats: List<Creep>, helpers: List<Creep>, cap: Int): Array<BodyPartType>? {
        var best: Array<BodyPartType>? = null
        var bestCost = Int.MAX_VALUE
        // победитель, который не догоняет, не дерётся: по болоту центра он не медленнее самого быстрого из тех, кого идёт
        // снимать (T2M1A1 — 15 тиков на клетку болота против 5 у M1A1 — полсотни тиков стоял в четырёх клетках от
        // перехватчика, пока тот бил эскорт; けろびー#32, 28.09.2026)
        val catchUp = threats.minOfOrNull { c -> Bodies.period(c.body.count { it.type != MOVE && it.type != CARRY }, Bodies.live(c, MOVE), true) } ?: Int.MAX_VALUE
        // MOVE — до двойного числа прочих частей: не медленнее M4A3 по болоту (у него 4 MOVE на 3 части, 4 тика на
        // клетку) значит MOVE ≥ 1,25 прочих, и при «не больше прочих» такого тела перебор не находил вовсе (v37, стенд camp)
        for (a in 1..8) for (t in 0..12) for (m in maxOf(1, (a + t + 2) / 3)..minOf(2 * (a + t), 50 - a - t)) {
            val body = Array(t) { TOUGH } + Array(m) { MOVE } + Array(a) { ATTACK }
            val cost = Bodies.cost(body)
            if (cost > cap || cost >= bestCost || body.size > 50) continue
            if (Bodies.period(a + t, m, true) > catchUp) continue
            val merged = helpers.flatMap { c -> c.body.map { it.type } } + body.toList()
            val hits = helpers.flatMap { c -> c.body.map { it.hits } } + body.map { 100 }
            val unit = Bodies.Unit(merged.toTypedArray(), hits.toIntArray())
            // после боя — не медленнее трёх тиков на клетку равнины: мёртвые части тоже весят (движок считает вес по
            // типам частей), и T7M3A2, выигравший дуэль с одним живым MOVE, полз по 9 тиков на клетку, пока их эскорт
            // проходил мимо (ricardo#23, 28.09.2026)
            val before = unit.total()
            if (Bodies.duel(unit, threats.map { Bodies.unitOf(it) }) < 0) continue
            // с запасом: T2M1A1 «выигрывал» у M1A1 на волосок, и живьём оба гибли (けろびー#32)
            if (unit.total() * 100 < before * FIELD_MARGIN_PCT) continue
            if (unit.count(MOVE) * 3 < merged.count { it != MOVE && it != CARRY }) continue
            best = body; bestCost = cost
        }
        return best
    }

    /**
     * Охотник с самым РАННИМ приходом к цели: ожидание энергии (1 в тик) + роды + путь по полю с ценой болота из его
     * тела + добивание цели. Мили M_m A_a: MOVE спереди. Тяжёлый дольше копится, лёгкий дольше идёт по болоту (M1A1 —
     * пять тиков на клетку болота) и дольше бьёт; решает сумма, а не цена. Против вооружённых целей тело обязано ещё и
     * выигрывать дуэль.
     */
    private fun fastHunter(w: World, target: Position, targetHits: Int, e: Int, foes: List<Creep>): Pair<Array<BodyPartType>, Int>? {
        val spawn = w.mySpawn ?: return null
        var best: Array<BodyPartType>? = null
        var bestT = Int.MAX_VALUE
        val armed = foes.filter { Bodies.isArmed(it) }
        for (a in 1..3) for (m in a..3 * a) {
            val body = Array(m) { MOVE } + Array(a) { ATTACK }
            val cost = Bodies.cost(body)
            if (cost > SPAWN_ENERGY_CAPACITY) continue
            if (armed.isNotEmpty() && Bodies.duel(Bodies.unitOf(body), armed.map { Bodies.unitOf(it) }) < 0) continue
            val swamp = Bodies.period(a, m, true)
            val f = flowTo("hunt:${target.x},${target.y}", target, w.blocked, swamp)
            val from = spawnExitDist(f, spawn)
            if (from < 0) continue
            val t = w.now + maxOf(0, cost - e) + Bodies.spawnTicks(body) + from + (targetHits + 30 * a - 1) / (30 * a)
            if (t < bestT || (t == bestT && cost < Bodies.cost(best!!))) { bestT = t; best = body }
        }
        return best?.let { it to (bestT - w.now) }
    }

    /** Дистанция по полю от клетки выхода из спавна (сама клетка спавна в поле непроходима). */
    private fun spawnExitDist(f: IntArray, spawn: Position): Int {
        var best = -1
        for ((dx, dy) in DIRECTIONS) {
            val x = spawn.x + dx; val y = spawn.y + dy
            if (!DistanceMap.inBounds(x, y)) continue
            val d = f[x * 100 + y]
            if (d >= 0 && (best < 0 || d < best)) best = d
        }
        return best
    }

    /** Когда наш новый M1, заказанный сейчас, встанет на клетку: ожидание 50 энергии + 3 тика родов + путь. */
    private fun scoutEta(w: World, target: Position): Int {
        val spawn = w.mySpawn ?: return Int.MAX_VALUE / 4
        val f = flowTo("scout:${target.x},${target.y}", target, w.blocked, 1)
        val d = spawnExitDist(f, spawn)
        if (d < 0) return Int.MAX_VALUE / 4
        return maxOf(0, Bodies.cost(MOVE) - energyOf(w)) + 3 + d
    }

    /** Когда наш уже идущий хранитель встанет на наш флаг (или бесконечность, если его нет). */
    private fun scoutEtaOfKeeper(w: World, flag: Position): Int {
        val k = w.scouts.firstOrNull { scoutMission[idOf(it)] == KEEP } ?: return Int.MAX_VALUE / 4
        if (k.spawning) return scoutEta(w, flag)
        val f = flowTo("scout:${flag.x},${flag.y}", flag, w.blocked, 1)
        val d = f[key(k)]
        return if (d < 0) Int.MAX_VALUE / 4 else d
    }

    /** Самый ранний приход ИХ разведчика на клетку: по его дистанции (клетка в тик), если он идёт туда или неясно
     *  куда (до развилки в центре обе дистанции сокращаются одинаково). Уже стоящий там — 0. */
    private fun enemyScoutEta(w: World, target: Position, heading: String): Int {
        val f = flowTo("scout:${target.x},${target.y}", target, w.blocked, 1)
        var best = Int.MAX_VALUE / 4
        for (s in w.enemyScouts) {
            if (onCell(s, target)) return 0
            val h = scoutHeading(idOf(s))
            if (h != "" && h != heading) continue
            val d = f[key(s)]
            if (d >= 0 && d < best) best = d
        }
        return best
    }

    /** Бойцы по заданию (живые, рождающиеся и заказанные). */
    private fun fightersOn(w: World, role: String) =
        w.mine.count { !isEscort(it) && Bodies.wasArmed(it) && fighterRole[idOf(it)] == role } + fighterQueue.count { it == role }

    // ==================== разведчики: хранитель и блокировщик ====================

    private fun assignScouts(w: World) {
        val live = w.scouts.mapTo(HashSet()) { idOf(it) }
        scoutMission.keys.retainAll { it in live }
        for (s in w.scouts) {
            val id = idOf(s)
            if (scoutMission.containsKey(id)) continue
            val m = if (scoutQueue.isNotEmpty()) scoutQueue.removeFirst() else if (w.scouts.none { scoutMission[idOf(it)] == KEEP }) KEEP else BLOCK
            scoutMission[id] = m
            println("scout t=${w.now}: ${id} -> $m")
        }
    }

    /**
     * Хранитель стоит на нашем флаге; уходит вбок в тот тик, когда эскорт шагает на флаг (эскорт выигрывает спор за
     * клетку: к нему идёт тягач, rate1). Флаг занят врагом — ждёт вплотную и встаёт, как только клетка освободится.
     * Блокировщик — то же на их флаге, но не уходит никогда.
     */
    private fun runScouts(w: World) {
        for (s in w.scouts) {
            if (s.spawning || Bodies.liveMoves(s) == 0) continue
            var mission = scoutMission[idOf(s)] ?: continue
            // флаг закрыт сооружением — хранители у флага только занимают клетки, с которых его ломают (офлайн: из двух
            // проломщиков бил один, остальные соседи флага — эскорт, хранитель, второй хранитель): они уходят блокировать
            if (w.flagBlocker != null && (mission == KEEP || mission == APPROACH)) { mission = BLOCK; scoutMission[idOf(s)] = BLOCK }
            if (mission == CHOKE) { runChoke(w, s); continue }
            if (mission == APPROACH) {
                // флаг опустел под вооружённым врагом (хранителя выбили) — встаёт на него сам: клетку держит тот, кто
                // на ней, а их стрелок у флага займёт её в следующий тик (Suruks#2)
                val f0 = w.myFlag
                if (f0 != null && dist(s, f0) <= 1 && w.occupant[key(f0)] == null && w.enemyArmed.any { dist(it, f0) <= 3 }) {
                    s.move(dirTo(s, f0)); pinned.add(idOf(s)); scoutMission[idOf(s)] = KEEP
                    println("scout t=${w.now}: ${idOf(s)} approach keeper steps onto the emptied flag")
                    continue
                }
                // второй хранитель: на клетке подхода нашего эскорта; эскорт, подойдя, сдвинет его (runTrain)
                val a = approachCell(w.escort, w.escortFlow) ?: w.myFlag ?: continue
                if (onCell(s, a)) { if (keeperStepAside != idOf(s)) pinned.add(idOf(s)); continue }
                val occ = w.occupant[key(a)]
                if (occ != null && occ !== s) { if (dist(s, a) <= 1) pinned.add(idOf(s)) else stepAround(w, s, a, 1, 1, 5)?.let { TrafficManager.request(s, it, SCOUT_PRIORITY) }; continue }
                val step = stepAround(w, s, a, 0, 1, 5) ?: continue
                TrafficManager.request(s, step, SCOUT_PRIORITY)
                continue
            }
            val flag = (if (mission == KEEP) w.myFlag else w.enemyFlag) ?: continue
            if (onCell(s, flag)) {
                if (mission == KEEP && keeperStepAside == idOf(s)) continue // уже сдвинут поездом
                pinned.add(idOf(s))
                continue
            }
            val flagOcc = w.occupant[key(flag)]
            val d = dist(s, flag)
            // Флаг держит их крип. Блокировщик встаёт на клетку ПОДХОДА их эскорта (последнюю клетку его маршрута перед
            // флагом): их хранитель пропустит эскорт обменом, но до флага эскорту придётся обходить нас — лишний шаг,
            // два тика гонки. Так же их M1, ждущий у нашего флага, стоял ровно на нашей клетке подхода (стенд
            // stuck:rev+keep+blk: обход (94,93)→(95,94), проигрыш на 246-м тике при равном ходе). Хранитель, наоборот,
            // ждёт НЕ на нашей клетке подхода, чтобы не запереть собственный эскорт.
            if (flagOcc != null && !flagOcc.my) {
                val ownApproach = if (mission == BLOCK) approachCell(w.enemyEscort, w.enemyEscortFlow) else approachCell(w.escort, w.escortFlow)
                val spot: Position? = if (mission == BLOCK) ownApproach?.takeIf { w.occupant[key(it)].let { o -> o == null || o === s } }
                    else null
                if (spot != null) {
                    if (onCell(s, spot)) { pinned.add(idOf(s)); continue }
                    val step = stepAround(w, s, spot, 0, 1, 1)
                    if (step != null) { TrafficManager.request(s, step, SCOUT_PRIORITY); continue }
                }
                if (d <= 1 && (mission == BLOCK || ownApproach == null || !onCell(s, ownApproach))) { pinned.add(idOf(s)); continue }
            }
            if (d <= 1) {
                if (flagOcc == null) { s.move(dirTo(s, flag)); pinned.add(idOf(s)); continue }
                pinned.add(idOf(s))
                continue
            }
            val step = stepAround(w, s, flag, 1, 1, 50) ?: continue
            TrafficManager.request(s, step, SCOUT_PRIORITY)
        }
    }

    private val chokeTarget = HashMap<String, Int>()
    private val chokeAt = HashMap<String, Int>()

    /**
     * Блокировщик на узком месте их маршрута: цель пересчитывается раз в пять тиков или когда их поезд её миновал;
     * обошли — перебегает на следующую (M1 ходит клетку в тик, поезд — в два). Целей больше нет — становится обычным
     * блокировщиком их флага.
     */
    internal fun runChoke(w: World, s: Creep) {
        val id = idOf(s)
        val esc = w.enemyEscort
        val flow = w.enemyEscortFlow
        if (esc == null || flow == null) { scoutMission[id] = BLOCK; return }
        val ahead = Chokes.route(flow, esc).toHashSet()
        val cur = chokeTarget[id] ?: -1
        // стоим на цели, а поезд до неё ещё не дошёл — держим: пересчёт «успеваем ли» на своей же клетке (запас три
        // тика) снимал блокировщика за тик до поезда, и тот проходил даром (лига, v22 против v20: (61,61) брошена на 179-м)
        val hold = cur >= 0 && s.x == cur / 100 && s.y == cur % 100 && flow[cur] >= 0 && flow[key(esc)] > flow[cur]
        if (!hold && (cur < 0 || cur !in ahead || w.now - (chokeAt[id] ?: -99) >= 5)) {
            val pick = chokePick(w, s, 0)
            val next = pick?.cell ?: -1
            if (next != cur) println("choke t=${w.now}: $id ${if (pick == null) "no target -> block" else "target (${next / 100},${next % 100}) pen=${pick.penalty} theirEta=${pick.theirEta}"}")
            chokeTarget[id] = next
            chokeAt[id] = w.now
        }
        val t = chokeTarget[id] ?: -1
        if (t < 0) { scoutMission[id] = BLOCK; return }
        val goal = cellPos(t)
        if (onCell(s, goal)) { pinned.add(id); return }
        stepAround(w, s, goal, 0, 1, 5)?.let { TrafficManager.request(s, it, SCOUT_PRIORITY) }
    }

    /** Клетка подхода эскорта к его флагу: последняя клетка его маршрута по полю перед флагом (null — маршрута нет). */
    private fun approachCell(escort: Creep?, flow: IntArray?): Position? {
        if (escort == null || flow == null) return null
        var cell = key(escort)
        if (flow[cell] <= 0) return null
        var guard = 0
        while (guard++ < 400) {
            val cx = cell / 100; val cy = cell % 100
            var best = -1
            var bestD = flow[cell]
            for ((dx, dy) in DIRECTIONS) {
                val nx = cx + dx; val ny = cy + dy
                if (!DistanceMap.inBounds(nx, ny)) continue
                val d = flow[nx * 100 + ny]
                if (d in 0 until bestD) { bestD = d; best = nx * 100 + ny }
            }
            if (best < 0) return null
            if (bestD == 0) return InfluenceMap.cell(cx, cy)
            cell = best
        }
        return null
    }

    // ==================== поезд ====================

    private var keeperStepAside: String? = null

    /**
     * Обратный поезд: эскорт — голова, тягачи — цепью позади. Каждый тик: эскорт тянет первого, каждый — следующего
     * (связка живёт тик и ставится заново — и в тики ожидания тоже: MOVE буксируемых гасят усталость головы, пока
     * связка есть). Эскорт шагает, когда его усталость ноль; каждый тягач шагает в клетку переднего. Тягачи не в цепи
     * идут к её хвосту. Пока спавн рождает тягача и цепи нет, эскорт вплотную к спавну тянет рождающегося.
     */
    private fun runTrain(w: World) {
        keeperStepAside = null
        val escort = w.escort ?: return
        // флаг закрыт и проломщики в пути — поезд ждёт в трёх клетках от флага: у входа в карман он занимал клетки, с
        // которых бьют рампарт, и из двух проломщиков бил один (офлайн, затычка: 4 поражения из 40 на ~815-м)
        val waiting = w.flagBlocker != null && fightersOn(w, BREACH) > 0
        val flow = (if (holding) homeFlow(w) else null) ?: (if (waiting) breachWaitFlow(w) else null) ?: w.escortFlow ?: return
        val flag = w.myFlag ?: return
        pinned.add(idOf(escort))
        // проверка буксировки результатом: после шага с цепью усталость обязана быть меньше пешей
        if (expectFatigue >= 0) {
            pullChecks++
            if (escort.fatigue > expectFatigue) { pullMisses++; println("train t=${w.now}: pull MISS fatigue=${escort.fatigue} expected<=${expectFatigue} misses=$pullMisses/$pullChecks") }
            expectFatigue = -1
        }
        if (onCell(escort, flag)) return

        // цепь: вплотную друг за другом, порядок прошлого тика сохраняется
        val free = w.pullers.filter { !it.spawning && Bodies.liveMoves(it) > 0 }.toMutableList()
        free.sortBy { val i = lastChain.indexOf(idOf(it)); if (i < 0) 100 else i }
        val chain = ArrayList<Creep>()
        var cur: Creep = escort
        while (chain.size < MAX_CHAIN) {
            val next = free.firstOrNull { dist(it, cur) <= 1 } ?: break
            chain.add(next); free.remove(next); cur = next
        }
        lastChain = chain.map { idOf(it) }
        for (p in chain) pinned.add(idOf(p))
        // дома тягачи тоже встают на рампарты вокруг эскорта: тысяча хитов тягача — первая цель мили (stachu#9 снял
        // нашего M10 за 33 тика), а на рампарте удар уходит в рампарт
        if (holding && (homeFlow(w)?.get(key(escort)) ?: -1) == 0) {
            for (p in chain + free) {
                if (w.myRamparts.any { onCell(p, it) }) continue
                val spot = w.myRamparts.filter { r -> dist(r, escort) <= 1 && !w.occupant.containsKey(key(r)) }.minByOrNull { dist(it, p) } ?: continue
                pinned.remove(idOf(p))
                val step = stepAround(w, p, spot, 0, 1, 1) ?: continue
                TrafficManager.request(p, step, PULLER_PRIORITY)
            }
        }

        // связки
        val spawn = w.mySpawn
        var spawnPull = ""
        if (chain.isNotEmpty()) escort.pull(chain[0])
        else if (spawn != null && spawn.spawning != null && dist(escort, spawn) <= 1) {
            val babe = w.mine.firstOrNull { it.spawning && Bodies.isPureMove(it) }
            if (babe != null) spawnPull = " spawnPull=${escort.pull(babe)}"
        }
        for (i in 0 until chain.size - 1) chain[i].pull(chain[i + 1])
        val chainMoves = chain.sumOf { Bodies.liveMoves(it) }

        // шаг эскорта
        val preferSpawn = spawn != null && spawn.spawning != null && chain.isEmpty()
        // все клетки ближе к флагу заняты врагом (их блокировщик стоит на клетке подхода, пока наш хранитель на
        // флаге; стенд rev+keep+blk: эскорт простоял в двух клетках от флага до их финиша) — обход по полю, где враги
        // вблизи эскорта непроходимы
        val next = escortStep(w, escort, flow, preferSpawn) ?: run {
            val near = w.enemies.filter { dist(it, escort) <= 6 }
            if (near.isEmpty() || flow[key(escort)] <= 0) null
            else escortStep(w, escort, flowTo("escortDetour", flag, w.blocked + near, 5, ttl = 1), false)
        }
        var moved = false
        if (next != null) {
            val occ = w.occupant[key(next)]
            // на клетке флага наш — хранитель M1 или страж флага: оба уходят вбок, эскорт встаёт
            val keeperThere = occ != null && occ.my && !isEscort(occ) && occ !in chain
            if (onCell(next, flag) && keeperThere) {
                // Финиш — ОБМЕНОМ и только отдохнувшим эскортом: хранитель шагает в клетку эскорта, эскорт — на флаг.
                // У шага эскорта тогда высший ранг спора за клетку (movement.js: к нему идёт крип С ЭТОЙ САМОЙ клетки,
                // rate1 = 100), и их блокировщик, ждущий вплотную, клетку не возьмёт. v11 уводил хранителя ВБОК и
                // втягивал усталого эскорта формой move(крип) — живьём эскорт не сдвинулся, а их M1 встал на флаг в
                // тот же тик (6ab83ebc, #31, t=247; 6ab83eea, ricardo#5): два поражения на последней клетке. Усталый
                // эскорт ждёт тик — хранитель при этом стоит и клетку держит.
                if (escort.fatigue == 0) {
                    occ!!.move(dirTo(occ, escort))
                    keeperStepAside = idOf(occ)
                    pinned.add(idOf(occ))
                    val rc = escort.move(dirTo(escort, next))
                    moved = true
                    println("train t=${w.now}: FINISH swap with ${idOf(occ)} ${Bodies.summaryOf(occ)}, escort -> flag rc=$rc")
                }
            } else if (escort.fatigue == 0 && occ != null && occ.my && occ !in chain && !isEscort(occ) &&
                (scoutMission.containsKey(idOf(occ)) || fighterRole[idOf(occ)] == GUARD_FLAG)) {
                // на следующей клетке наш неподвижный (второй хранитель на клетке подхода): он уходит вбок в тот же
                // тик, эскорт входит; некуда — обмен (он в клетку эскорта, цепь этот тик стоит)
                val aside = asideCell(w, occ, setOf(key(escort), key(next), key(flag)) + chain.map { key(it) })
                keeperStepAside = idOf(occ)
                if (aside != null) { occ.move(dirTo(occ, aside)); escort.move(dirTo(escort, next)); moveChain(chain, escort) }
                else { occ.move(dirTo(occ, escort)); escort.move(dirTo(escort, next)) }
                moved = true
                println("train t=${w.now}: ${idOf(occ)} ${Bodies.summaryOf(occ)} gives the escort its cell (${next.x},${next.y}) ${if (aside != null) "aside" else "by swap"}")
            } else if (escort.fatigue == 0 && occ != null && occ.my && occ !in chain && Bodies.isPuller(occ, PULLER_MIN_MOVE)) {
                // на следующей клетке наш тягач не из цепи (поезд развернулся домой, и хвост оказался впереди) — обмен:
                // он в клетку эскорта, эскорт в его; цепь этот тик стоит. Без обмена оба просили клетку друг друга, и
                // эскорт простоял в (23,76) сто десять тиков, пока перехватчик ел тягачей (стенд icpt, v15)
                occ.move(dirTo(occ, escort))
                pinned.add(idOf(occ))
                escort.move(dirTo(escort, next))
                moved = true
            } else if (escort.fatigue == 0 && (occ == null || occ.my)) {
                if (occ != null && occ.my) yieldCells = setOf(key(next))
                escort.move(dirTo(escort, next))
                moveChain(chain, escort)
                moved = true
                // пешком (без цепи) после шага остаётся вес×ставка − 2×MOVE; с цепью — меньше на её вклад
                if (chain.isNotEmpty()) {
                    val cost = Bodies.weight(escort) * (if (DistanceMap.isSwamp(next.x, next.y)) 10 else 2)
                    expectFatigue = maxOf(0, cost - 2 * (Bodies.liveMoves(escort) + chainMoves))
                }
            }
        }
        if (DEBUG_LOG && (w.now <= RACE_TRACE_TICKS || w.now % 25 == 0)) {
            println("train t=${w.now}: escort (${escort.x},${escort.y}) f=${escort.fatigue} ${if (moved) "-> (${next!!.x},${next.y})" else "waits"} " +
                "chain=${chain.joinToString(" ") { "${idOf(it)}(${it.x},${it.y})M${Bodies.liveMoves(it)}" }} free=${free.size}$spawnPull")
        }

        // тягачи не в цепи — к её хвосту (клетка вплотную к последнему), не наступая на следующую клетку эскорта
        val tail: Creep = chain.lastOrNull() ?: escort
        for (p in free) {
            if (p.spawning || Bodies.liveMoves(p) == 0) continue
            if (dist(p, tail) <= 1) continue
            val f = flowTo("tail", tail, w.blocked, 1, ttl = 1)
            val step = DistanceMap.flowStep(f, p.x, p.y, 1, w.occupant.keys, w.enemyAt) ?: continue
            if (next != null && step.x == next.x && step.y == next.y) continue
            TrafficManager.request(p, step, PULLER_PRIORITY)
        }
    }

    /** Каждый тягач цепи шагает в клетку переднего (буксируемый обязан шагнуть ИМЕННО туда, иначе связка рвётся). */
    private fun moveChain(chain: List<Creep>, escort: Creep) {
        var ahead: Creep = escort
        for (p in chain) { p.move(dirTo(p, ahead)); ahead = p }
    }

    /** Свободная клетка вплотную к крипу, не из запрещённых (клетки поезда). */
    private fun asideCell(w: World, c: Creep, forbidden: Set<Int>): Position? {
        for ((dx, dy) in DIRECTIONS) {
            val x = c.x + dx; val y = c.y + dy
            val k = x * 100 + y
            if (!DistanceMap.inBounds(x, y) || DistanceMap.isWall(x, y) || k in forbidden || w.occupant.containsKey(k)) continue
            return InfluenceMap.cell(x, y)
        }
        return null
    }

    private fun dirTo(from: Position, to: Position) = screeps.api.getDirection(to.x - from.x, to.y - from.y)

    /** Свои (не поезд), стоящие на клетке, куда шагает эскорт, — шаг в сторону. */
    private fun enforceYield(w: World) {
        if (yieldCells.isEmpty()) return
        for (c in w.active) {
            val id = idOf(c)
            if (id in pinned || isEscort(c) || Bodies.liveMoves(c) == 0) continue
            if (key(c) !in yieldCells) continue
            val want = TrafficManager.desiredOf(id)
            if (want != null && want !in yieldCells) continue
            val aside = asideCell(w, c, yieldCells + (w.escort?.let { setOf(key(it)) } ?: emptySet()))
            if (aside != null) TrafficManager.request(c, aside, YIELD_PRIORITY)
        }
    }

    // ==================== оборона дома: эскорт на своих рампартах ====================

    /** Эскорт держится дома: рядом враг, которого наша охрана не побеждает, и он успеет к эскорту раньше финиша. */
    private var holding = false
    private var holdSince = -1
    /** Какие враги держат нас дома — для журнала и для бойцов. */
    private var holdThreats: List<Creep> = emptyList()

    /**
     * ДОМ. Удар по эскорту на его СОБСТВЕННОМ рампарте приходится в рампарт (attack.js/rangedAttack.js: цель под
     * рампартом подменяется рампартом, 10 000 хитов), и боец на рампарте тоже неуязвим. Поэтому раннего мили, которого
     * охрана не бьёт в поле, эскорт встречает дома, а не в центре: ricardo#8 на 20-м тике заказал M4A3 (440 энергии,
     * 90 урона в тик) и за 55 тиков у центра убил наш эскорт с полными хитами, пока наши тягачи стояли рядом (6ab841a9,
     * 6ab841e5 — два поражения v12 из двух). Дома его ждёт боец с рампарта; стоит враг поодаль — ждём, копя охрану,
     * которая его побьёт: их эскорт без тягача идёт вчетверо дольше, а их флаг держит наш блокировщик.
     * Держимся, только если враг успевает к эскорту раньше финиша, а эскорт успевает домой раньше врага.
     */
    private fun decideHold(w: World) {
        val escort = w.escort
        val spawn = w.mySpawn
        if (escort == null || spawn == null || w.myRamparts.isEmpty() || onCell(escort, w.myFlag)) { holding = false; return }
        // экономика: победителя их тяжёлого у нас нет — эскорт ждёт его дома, пока ожидание оплачено запасом гонки (их
        // эскорт пеший), и выходит вместе с ним. Без этого поезд входил в развилку раньше победителя, купленного на доход
        // W2 к 169-му, и телохранитель бросался на 171-м (стенд guard, v37: эскорт дошёл с 50 хитами)
        // Ждать победителя — только против ТЕЛОХРАНИТЕЛЯ (тяжёлый при своём эскорте бросается на поезд в развилке); тяжёлого,
        // идущего к нашей базе (рашер ricardo#7: M4A3 к нашим рампартам к ~110-му), встречает обычное держание — боец с
        // рампарта, на который экономика даёт деньги быстрее. И конец этого ожидания — не «отпуск»: пометка released
        // запрещала потом держать дом из-за идущего к нам рашера, и эскорт вышел ему навстречу (6abb805d)
        val econHeld = econWait
        econWait = false
        if (econ == true) {
            val heavies = w.enemyArmed.filter { !isEscort(it) && heavy(it) && bodyguard(w, it) }
            val ourArmed = w.fighters.filter { Bodies.isArmed(it) && !it.spawning }
            if (heavies.isNotEmpty() && !wins(ourArmed, heavies) && theirArrival(w) - ourArrival(w) - RACE_ERR > 0) {
                if (!holding) { holdSince = w.now; println("hold t=${w.now}: HOME for the winner — ${heavies.joinToString(" ") { Bodies.summaryOf(it) }}, margin ${theirArrival(w) - ourArrival(w)}") }
                holding = true; holdThreats = heavies; econWait = true
                return
            }
            if (econHeld) { holding = false; println("hold t=${w.now}: the wait for the winner is over") }
        }
        val armed = w.enemyArmed.filter { !isEscort(it) } + w.enemyPending.filter { Bodies.wasArmed(it) }
        val guards = w.fighters.filter { Bodies.isArmed(it) && dist(it, escort) <= 6 }
        val ours = ourArrival(w)
        // телохранитель — рождённый вооружённый в трёх клетках от своего эскорта: он идёт с ним, к нам не идёт, и дом
        // от него не спасает (ricardo18informatica2020#23: M4A3 при своём эскорте с 75-го тика; v28 держал дом до 175-го
        // и выпустил поезд ровно к их паре в центре — 0-6)
        val threats = armed.filter { e ->
            val eta = dist(e, escort) + (if (e.spawning) 3 * e.body.size else 0)
            eta < ours && !bodyguard(w, e) && !onOurFlag(w, e)
        }
        // держимся против того, кто БЛИЗКО (живые в HOLD_RADIUS) или ещё рождается; дальние — не повод сидеть дома:
        // экономика stachu3478 рождает бойца за бойцом, и «наша охрана бьёт всех» не наступало никогда — эскорт
        // простоял дома до их финиша на 408-м, хотя их эскорт шёл пешком (6ab843f2)
        // …или кто ИДЁТ к эскорту: его дистанция сократилась за десять тиков на пять и больше (рашер ricardo#8 рождается
        // в восьмидесяти клетках и идёт прямо на нас — без этого признака держание снималось в тик его рождения)
        for (e in threats) if (!e.spawning) {
            val h = closing.getOrPut(idOf(e)) { ArrayDeque() }
            h.addLast(w.now to dist(e, escort))
            while (h.size > 11) h.removeFirst()
        }
        closing.keys.retainAll { id -> threats.any { idOf(it) == id } }
        val coming = { e: Creep -> closing[idOf(e)]?.let { h -> h.size >= 11 && h.first().second - h.last().second >= 5 } == true }
        // намерение дальнего ещё не видно (рождается или меньше десяти тиков истории) — он тоже держит
        // рождающийся дом не держит: в первые сорок тиков эскорт и так у спавна (тянет рождающихся тягачей), а держание
        // до рождения M4A3 ricardo#23 и прояснения, что это телохранитель, стоило поезду 35 тиков — и он пришёл в центр
        // вровень с их парой (0-6). Новорождённый рядом со своим эскортом — тоже не «неясный»: это телохранитель в
        // становлении; рашер ricardo#8 рождается вдали от своего эскорта и держит, как прежде
        val theirEscH = w.enemyEscort
        // …но только тяжёлый: рождающийся перехватчик stachu (M1A1) держит, как в v28 — держание с его рождения ставит
        // спавн копить на бойца дома раньше хранителя (стенд econ+icpt: без этого боец опоздал, и поезд погиб)
        val unknown = { e: Creep ->
            if (e.spawning) !heavy(e)
            else (closing[idOf(e)]?.size ?: 0) < 11 && !(theirEscH != null && dist(e, theirEscH) <= NEWBORN_AT_ESCORT)
        }
        // держась, отпускаем только того, кто отошёл вдвое дальше: иначе перехватчик stachu, стоявший в центре в 45-60
        // клетках, то держал, то отпускал эскорт каждые несколько тиков, и тот ходил туда-сюда у дома (6ab84583)
        val radius = if (holding) 2 * HOLD_RADIUS else HOLD_RADIUS
        // Однажды отпустив эскорт, дом больше не держим из-за дальних и «неясных»: только живой враг в HOLD_NEAR
        // клетках. Иначе против экономиста каждый его новый боец (у stachu3478 — M3A3 на ~170-м, при его эскорте)
        // снова загонял наш эскорт домой, пока его пеший эскорт шёл к флагу (v15-v17: 0 из 9 после убийства его
        // перехватчика у наших рампартов)
        val decisive0 = if (released) threats.filter { !it.spawning && dist(it, escort) <= HOLD_NEAR }
            else threats.filter { dist(it, escort) <= radius || coming(it) || unknown(it) }
        // Стрелки без мили дом не держат, если весь их огонь за наш путь до флага эскорт выдерживает: с рампарта их
        // не достать (кайтят), а дом — это ожидание, в котором растёт их армия. stachu3478#1: его M1A1 умер у наших
        // рампартов на 176-м, но M2R2 (20 урона в тик) стоял в центре, и эскорт просидел дома до их финиша на 405-м
        // (6ab84f50); 20 × 224 тика пути — 4480 из 5000
        val fire = decisive0.filter { !it.spawning }.sumOf { Bodies.rangedDps(it) }
        val ranged = decisive0.isNotEmpty() && decisive0.none { Bodies.has(it, ATTACK) }
        // И дом не держит, когда ожидание уже проигрывает само: их эскорт придёт раньше, чем наш успеет от дома, если
        // выйти позже чем сейчас (стенд econ+icpt: перехватчик не подошёл к рампартам, и эскорт просидел дома до их
        // финиша на 473-м, хотя выход на 200-м приходил к 422-му)
        val theirs = theirArrival(w)
        val lastCall = holding && theirs < Int.MAX_VALUE / 8 && theirs <= ours + HOLD_LAST_CALL
        // засада: стоящий на нашем пути (или в трёх клетках от него) вооружённый, которого наши бойцы в поле не
        // побеждают, — держит всегда: к эскорту он не идёт (радиус и «идёт к нам» его не видят), а выпущенный поезд идёт
        // прямо на него (ricardo#23: M4A3 в горлышке центра, v28 0-6 на сервере, 0-40 в стенде)
        // путь убийства: их тяжёлый держит дом, пока жив и наши бойцы его не побеждают, — телохранитель он или нет
        // (иначе держание снималось на 38-м, и 300 энергии победителя уходили в четырёх M1)
        val killHeavies = if (killPath) w.enemyArmed.filter { !isEscort(it) && heavy(it) } else emptyList()
        // держит дом только ТЯЖЁЛАЯ засада; лёгкую (M1A1 けろびー#32) бьёт боец, купленный раньше разведчиков, а поезд идёт:
        // держание до её смерти на ~345-м отдавало время их экономике (R3M5, M5H3, T3M8R5 к ~450-му)
        // охотники держат дом, как засада, и последний срок гонки их не отменяет: выйти — значит отдать эскорт (ricardo#7,
        // 6abb805d: M4A3 ждал в развилке, v37 и v38 выпускали поезд по сроку гонки, эскорт гиб на ~200-м под ударами)
        huntersNow = if (dist(escort, spawn) <= CAMP_BASE_RANGE) hunters(w, escort) else emptyList()
        if (huntersNow.isNotEmpty() && w.now % 25 == 0) println("hold t=${w.now}: HUNTERS ${huntersNow.joinToString(" ") { Bodies.summaryOf(it) + "@(" + it.x + "," + it.y + ")" }} — leaving now loses the escort")
        val camp = (campers(w).filter { heavy(it) } + killHeavies.filter { !wins(w.fighters.filter { f -> Bodies.isArmed(f) }, killHeavies) } + huntersNow).distinct()
        val decisive = if (camp.isNotEmpty()) camp else if ((ranged && fire * ours < escort.hits) || lastCall) emptyList() else decisive0
        if (decisive.isEmpty() || (camp.isEmpty() && wins(guards, decisive.filter { !it.spawning }.ifEmpty { decisive }))) {
            if (holding) { released = true; println("hold t=${w.now}: released after ${w.now - holdSince} ticks — threats=${threats.size} guards=${guards.size}") }
            holding = false; holdThreats = emptyList(); return
        }
        val home = homeFlow(w) ?: run { holding = false; return }
        val homeD = home[key(escort)]
        if (homeD < 0) { holding = false; return }
        val homeTicks = homeD * 2
        // по угрозам И засадам: телохранитель из угроз исключён, но на пути убийства держит — пустой minOf ронял тик до
        // спавна, и за весь матч не покупалось ничего (тестовые против ricardo#23, 28.09.2026)
        val contact = (threats + camp).minOfOrNull { e -> dist(e, escort) + (if (e.spawning) 3 * e.body.size else 0) } ?: (Int.MAX_VALUE / 4)
        // уже держимся — держимся, пока угроза есть (гистерезис); иначе — только если успеваем домой до встречи
        // засада держит всегда: стоит она неподвижно, и эскорт, уже вышедший, возвращается к рампартам (до неё далеко)
        val hold = holding || homeTicks < contact || camp.isNotEmpty()
        if (hold && !holding) { holdSince = w.now; println("hold t=${w.now}: HOME — threats ${threats.joinToString(" ") { Bodies.summaryOf(it) + "@" + dist(it, escort) + (if (it.spawning) "(spawning)" else "") }} guards=${guards.size} homeTicks=$homeTicks contact=$contact ours=$ours") }
        holding = hold
        holdThreats = (threats + camp).distinct()
    }

    /** Охотники этого тика (decideHold): их победителя покупает holdSpawn, как против засады. */
    private var huntersNow: List<Creep> = emptyList()

    /**
     * Охотники — вооружённые, которых наши бойцы вместе не бьют и которые, выйди эскорт СЕЙЧАС, догоняют поезд на его
     * пути (приходят к клетке пути не позже поезда и ходят не медленнее его) и за оставшийся путь бьют больше хитов
     * эскорта. Выход при них — не гонка, а потеря эскорта: против пешего эскорта соперника без тягачей и охраны матч
     * выигрывают тогда эскорт на рампарте и бойцы (их одинокий эскорт убивают к ~370–410-му, стенд ambush). Телохранитель
     * исключён — он ходит со своим эскортом, и его держит ожидание победителя (econWait). Расстояние — по прямой, без
     * стен и болота: оценка в пользу охотника.
     */
    private fun hunters(w: World, escort: Creep): List<Creep> {
        val flow = w.escortFlowRaw ?: return emptyList()
        val route = Chokes.route(flow, escort)
        val ours = ourArrival(w)
        if (route.isEmpty() || ours >= Int.MAX_VALUE / 8) return emptyList()
        val per = ours.toDouble() / route.size
        val ourArmed = w.fighters.filter { Bodies.isArmed(it) && !it.spawning }
        var damage = 0.0
        val found = ArrayList<Creep>()
        for (x in w.enemyArmed) {
            if (isEscort(x) || x.spawning || bodyguard(w, x) || onOurFlag(w, x)) continue
            val xPer = Bodies.period(Bodies.weight(x), Bodies.liveMoves(x), false)
            val dps = 30 * Bodies.live(x, ATTACK) + 10 * Bodies.live(x, RANGED_ATTACK)
            if (dps == 0 || xPer > per) continue
            val meet = route.indices.firstOrNull { i ->
                val k = route[i]
                maxOf(kotlin.math.abs(k / 100 - x.x), kotlin.math.abs(k % 100 - x.y)) * xPer <= i * per
            } ?: continue
            damage += (route.size - meet) * per * dps
            found.add(x)
        }
        if (damage < escort.hits || wins(ourArmed, found)) return emptyList()
        return found
    }

    /**
     * Захватчик нашего флага — на клетке или вплотную (FLAG_GUARD_RANGE). Дом от него не спасает и засадой он не
     * считается: к эскорту он не идёт, он закрывает цель, и снять его — дело стража флага (runSpawn, п. 3) и бойцов
     * (цель squatter). Suruks#2 (6abac117): стрелок M2R2 сел на наш флаг, когда эскорт был в трёх тиках от финиша, и
     * держание засады развернуло эскорт домой — на эти деньги покупались M1A1 и хранители «пока держимся», стража не было.
     */
    private fun onOurFlag(w: World, e: Creep): Boolean = w.myFlag?.let { dist(e, it) <= FLAG_GUARD_RANGE } ?: false

    /** Дистанции вооружённых врагов до нашего флага за последние тики (кто к нему идёт). */
    private val toFlag = HashMap<String, ArrayDeque<Int>>()

    /**
     * Удержание флага. Вооружённый враг, идущий к нашему флагу и успевающий туда раньше эскорта, выбивает хранителя M1
     * (100 хитов) за 100/урон тиков и садится на клетку; эскорт упирается в неё. Suruks#2: стрелок M2R2 рождался на
     * 107–109-м и был на флаге к 238–243-му, эскорт подходил к 248–252-му — во всех пяти таких играх поражение, во всех,
     * где стрелок опаздывал, победа (A/B v30/v34, 28.09.2026). Клетку держит сумма хитов хранителей у флага: нужно
     * (наш приход − его приход + 1) × его урон в тик. Не хватает — ещё хранитель M1, если он успевает к флагу раньше врага
     * (опоздавший на занятую клетку не встанет); второй ждёт вплотную и встаёт, как только клетка освободится (runScouts).
     */
    private fun holdKeepers(w: World, e: Int, ours: Int): Boolean {
        val flag = w.myFlag ?: return false
        for (x in w.enemyArmed) if (!isEscort(x)) {
            val h = toFlag.getOrPut(idOf(x)) { ArrayDeque() }
            h.addLast(dist(x, flag)); while (h.size > 11) h.removeFirst()
        }
        toFlag.keys.retainAll(w.enemyArmed.mapTo(HashSet()) { idOf(it) })
        if (ours >= Int.MAX_VALUE / 8) return false
        val heading = w.enemyArmed.filter { x ->
            !isEscort(x) && !bodyguard(w, x) && !onCell(x, flag) && toFlag[idOf(x)]?.let { h -> h.size >= 11 && h.first() - h.last() >= 5 } == true
        }
        if (heading.isEmpty()) return false
        var need = 0
        var dps = 0
        var firstEta = Int.MAX_VALUE
        for (x in heading) {
            // приход — по полю с ценой болота из его тела: по прямой стрелок Suruks выходил к флагу на ~206-м вместо
            // живых 238–243-х, и хранитель, успевавший к ~211-му, считался опоздавшим
            val plain = Bodies.period(Bodies.weight(x), Bodies.liveMoves(x), false)
            val swamp = Bodies.period(Bodies.weight(x), Bodies.liveMoves(x), true)
            val f = flowTo("toFlag", flag, w.blocked, maxOf(1, swamp / maxOf(1, plain)))
            val steps = f[key(x)].let { if (it >= 0) it else dist(x, flag) }
            val eta = w.now + steps * plain
            val gap = w.now + ours - eta + 1
            if (gap <= 0) continue
            val d = 30 * Bodies.live(x, ATTACK) + 10 * Bodies.live(x, RANGED_ATTACK)
            need += gap * d
            dps += d
            firstEta = minOf(firstEta, eta)
        }
        if (DEBUG_LOG && w.now % 10 == 0) println("holdflag t=${w.now}: heading ${heading.joinToString(" ") { Bodies.summaryOf(it) + "@" + dist(it, flag) }} need=$need firstEta=$firstEta ours=${w.now + ours}")
        if (need <= 0) return false
        // хранитель подхода стоит вплотную и встаёт на опустевший флаг (runScouts) — его хиты тоже держат клетку
        val holders = setOf(KEEP, APPROACH)
        val have = w.scouts.filter { scoutMission[idOf(it)] in holders }.sumOf { it.hits } + 100 * scoutQueue.count { it in holders }
        if (have >= need) return false
        val body = Bodies.moves(1)
        val arrive = w.now + maxOf(0, Bodies.cost(body) - e) + Bodies.spawnTicks(body) + scoutEta(w, flag)
        // успеть надо не к его приходу, а к падению клетки: стоящие хранители держат её have/dps тиков (M2R2 Suruks:
        // хранитель и хранитель подхода — ещё 10 тиков после 236-го, а новый M1 от 140-го поспевал к 242-му)
        if (arrive >= firstEta + have / maxOf(1, dps)) return false
        if (e >= Bodies.cost(body)) {
            if (order(w, body, "keeper", "hold the flag: ${heading.joinToString(" ") { Bodies.summaryOf(it) + "@" + dist(it, flag) }} first at ~$firstEta, ours at ~${w.now + ours}; keeper hits $have of $need")) scoutQueue.addLast(KEEP)
            return true
        }
        saving(w, "hold keeper", Bodies.cost(body)); return true
    }

    /** Помеченные засады: однажды вставший у нашего пути остаётся засадой до смерти — он отходит к подошедшим крипам
     *  и возвращается, и признак «стоит» мигал, снимая держание (стенд camp: пять выходов и возвратов за 200 тиков). */
    private val campMarks = HashSet<String>()
    /** Телохранитель: рождённый вооружённый в трёх клетках от их эскорта, УЖЕ ушедшего от их спавна дальше шести клеток —
     *  у самого спавна рядом с эскортом стоит любой новорождённый, и рашер ricardo#8 читался телохранителем (стенд rush8). */
    private fun bodyguard(w: World, e: Creep): Boolean {
        val esc = w.enemyEscort ?: return false
        val sp = w.enemySpawn ?: return false
        // и подолгу: рашер ricardo#8 по пути к нам проходит мимо своего эскорта и на эти тики читался телохранителем
        return !e.spawning && dist(e, esc) <= 3 && dist(esc, sp) > 6 && (guardTicks[idOf(e)] ?: 0) >= GUARD_TICKS
    }
    private val guardTicks = HashMap<String, Int>()
    /** Новорождённый не ближе стольких клеток к своему эскорту — не «неясный» (M4A3 ricardo#23 рождается на 22-м в 3-6
     *  клетках от ушедшего вперёд эскорта; рашер ricardo#8 — на 41-м, когда его эскорт уже в ~10). */
    private const val NEWBORN_AT_ESCORT = 6
    private const val GUARD_TICKS = 15

    /** Тяжёлый — вооружённый враг, для победы над которым в поле нужно тело дороже HEAVY_COST (M4A3 ricardo#23 — да,
     *  M1A1 stachu — нет: его бьёт дешёвый боец с рампарта, и засадой он не считается). */
    private fun heavy(e: Creep): Boolean {
        val body = cheapestWinner(listOf(e), emptyList(), SPAWN_ENERGY_CAPACITY) ?: return true
        return Bodies.cost(body) > HEAVY_COST
    }
    private const val HEAVY_COST = 260
    /** Победитель дуэли в поле сохраняет не меньше стольких процентов хитов. */
    private const val FIELD_MARGIN_PCT = 35
    /** Победитель вооружённого врага в поле покупается раньше разведчиков, если стоит не больше этого. */
    private const val EARLY_WINNER_BUDGET = 400
    /** Тяжёлый ближе стольких клеток к нашему спавну — он идёт на базу, и его бьёт боец с рампарта. */
    private const val HEAVY_NEAR_BASE = 25

    /** Засада — дальше стольких клеток от нашего спавна (рампарты 5×5 и подступы к ним — дело бойца с рампарта). */
    private const val CAMP_BASE_RANGE = 8

    /** Засада: живые вооружённые, вставшие в трёх клетках от оставшегося пути эскорта, которых наши бойцы не побеждают. */
    private fun campers(w: World): List<Creep> {
        val esc = w.escort ?: return emptyList()
        val flow = w.escortFlowRaw ?: return emptyList()
        val route = Chokes.route(flow, esc)
        val base = w.mySpawn
        // у наших рампартов — не засада: того, кто пришёл к базе (ricardo#8, перехватчик stachu), бьёт боец с рампарта
        val away = { e: Creep -> base == null || dist(e, base) > CAMP_BASE_RANGE }
        // и не охрана при их эскорте: та стоит, пока эскорт отдыхает на болоте, но идёт с ним своим путём (стенд
        // econ+icpt: M3A3 stachu при эскорте читался засадой, и поезд уходил домой)
        val theirEsc = w.enemyEscort
        val guarding = { e: Creep -> theirEsc != null && dist(e, theirEsc) <= 3 }
        for (e in w.enemyArmed) {
            // и лёгкий тоже: M1A1 けろびー#32 ждёт в центре с 50-го, к рампартам не идёт, и поезд, прошедший мимо, терял
            // тягачей, а эскорт — по 30 в тик до смерти в 26 клетках от флага (v29 0-6)
            if (isEscort(e) || idOf(e) in campMarks || stillFor(e, w.now) < CAMP_STILL || !away(e) || guarding(e) || onOurFlag(w, e)) continue
            if (route.any { k -> maxOf(kotlin.math.abs(k / 100 - e.x), kotlin.math.abs(k % 100 - e.y)) <= 3 }) {
                campMarks.add(idOf(e)); println("hold t=${w.now}: CAMPER ${idOf(e)} ${Bodies.summaryOf(e)}@(${e.x},${e.y}) on our route")
            }
        }
        campMarks.retainAll(w.enemyArmed.mapTo(HashSet()) { idOf(it) })
        // держит, пока СЕЙЧАС в пяти клетках от оставшегося пути: M4A3 ricardo#23 к ~235-му уходит охранять свой эскорт
        // его маршрутом, и полный поезд, вышедший тогда, финиширует к ~420-му — раньше их пешего (~460)
        val cs = w.enemyArmed.filter { e -> idOf(e) in campMarks && away(e) && !guarding(e) && !onOurFlag(w, e) && route.any { k -> maxOf(kotlin.math.abs(k / 100 - e.x), kotlin.math.abs(k % 100 - e.y)) <= 5 } }
        // держит, пока жива и на пути, — побеждают ли её наши бойцы, решает покупка и охота, но не выпуск поезда: выпущенный
        // вслед бойцу поезд шёл прямо на засаду (けろびー#32: T2M1A1 и M1A1 убили друг друга, тягачи погибли там же)
        return cs
    }


    /**
     * Заказы, пока эскорт дома. Эскорт на рампарте неуязвим, флаги — нет, поэтому сперва флаги: блокировщик их флага
     * (их эскорт без тягача всё равно идёт к нему), хранитель нашего; затем боец, который побеждает угрозу в поле (с
     * ним держаться дома больше незачем), а если он дороже, чем копится за двести тиков, — дешёвый боец на рампарт:
     * с рампарта он бьёт подошедшего без ответного урона.
     */
    private fun holdSpawn(w: World, e: Int, ours: Int, theirs: Int) {
        val escort = w.escort ?: return
        if (econWait) {
            val armedOurs = w.fighters.filter { Bodies.isArmed(it) }
            val winner = fieldWinner(holdThreats, armedOurs, SPAWN_ENERGY_CAPACITY) ?: cheapestWinner(holdThreats, armedOurs, SPAWN_ENERGY_CAPACITY)
            if (winner != null && !fighterQueue.contains(ESCORT_GUARD)) {
                if (e >= Bodies.cost(winner)) { if (order(w, winner, "defender", "economy: the winner the train waits for vs ${holdThreats.joinToString(" ") { Bodies.summaryOf(it) }}")) fighterQueue.addLast(ESCORT_GUARD); return }
                saving(w, "the winner ${Bodies.summary(winner)}", Bodies.cost(winner)); return
            }
            return
        }
        // сильная угроза — та, которую дешёвый боец в поле не побеждает (M4A3 ricardo#23 в центре не подходит к нашим
        // рампартам, и M1A1 на рампарте против него бесполезен): сперва M1 на их флаг — их эскорт идёт один и без него
        // финиширует, пока мы держимся, — затем самое дешёвое тело, выигрывающее у них дуэль в поле
        val liveThreats = holdThreats.filter { !it.spawning }.ifEmpty { holdThreats }
        val cheapUnit = meleeBody(Bodies.cost(MOVE) + Bodies.cost(ATTACK))!!
        val armedOurs = w.fighters.filter { Bodies.isArmed(it) }
        // только против засады: к рампартам она не подходит; кто подходит (ricardo#8, перехватчик stachu), того бьёт
        // дешёвый боец с рампарта, и полный поезд потом выигрывает гонку (стенд rush8, econ+icpt)
        // охотник у наших рампартов — не засада: его бьёт дешёвый боец с рампарта (стенд ambush, v37: два M1A1 убили M4A3
        // у рампартов); вдали — победитель в поле
        val farHunters = huntersNow.filter { w.mySpawn == null || dist(it, w.mySpawn) > CAMP_BASE_RANGE }
        val camp = ((if (killPath) liveThreats.filter { heavy(it) }.ifEmpty { campers(w) } else campers(w)) + farHunters).distinct()
        val strong = camp.isNotEmpty() && Bodies.duel(Bodies.unitOf(cheapUnit), camp.map { Bodies.unitOf(it) }) < 0
        if (strong && !wins(armedOurs, camp)) {
            // на пути убийства сперва M1 на их флаг: их эскорт идёт один, и наш M1, купленный сразу, проходит центр
            // задолго до их пары; купленный после победителя, он шёл за их эскортом по узкому пути и опаздывал
            // (ricardo#23, 28.09.2026). Против засады в горлышке — наоборот, победитель первым: M1 гиб у неё каждые 50
            if (killPath && theirFlagOrder(w, e, ours, theirs, blockerOnly = true)) return
            val winner = fieldWinner(camp, armedOurs, SPAWN_ENERGY_CAPACITY) ?: cheapestWinner(camp, armedOurs, SPAWN_ENERGY_CAPACITY)
            if (winner != null && !fighterQueue.contains(ESCORT_GUARD)) {
                if (e >= Bodies.cost(winner)) { if (order(w, winner, "defender", "field winner vs ${camp.joinToString(" ") { Bodies.summaryOf(it) }}")) fighterQueue.addLast(ESCORT_GUARD); return }
                saving(w, "field winner ${Bodies.summary(winner)}", Bodies.cost(winner)); return
            }
            if (theirFlagOrder(w, e, ours, theirs, blockerOnly = true)) return
        }
        // боец дома — прежде флагов, если бойца ещё нет: перехватчик stachu3478 приходил к нашим рампартам на ~175-м
        // тике и ждал там; боец за 130, купленный третьим (после блокировщика и хранителя), появлялся на 231-м, эскорт
        // выходил к 250-му и не успевал к их пешему финишу (~410; 6ab84583, 6ab845fd). Первым он встречает перехватчика
        // у рампартов, эскорт уходит к ~185-му, а блокировщик за ним ещё успевает к их флагу задолго до их эскорта
        if (w.fighters.none { Bodies.isArmed(it) } && !fighterQueue.contains(ESCORT_GUARD)) {
            val cheap = meleeBody(Bodies.cost(MOVE) + Bodies.cost(ATTACK))!!
            if (e >= Bodies.cost(cheap)) { if (order(w, cheap, "defender", "holding at home, no armed guard yet")) fighterQueue.addLast(ESCORT_GUARD); return }
            // копим, только если блокировщик их флага ещё успеет после бойца; иначе сперва блокировщик
            // блокировщику после бойца копить ещё пятьдесят с нуля — это и есть его задержка (стенд icpt: гонщик с
            // перехватчиком финишировал на 246-м, пока мы копили на бойца, а блокировщик шёл следом)
            val blockerLate = w.enemyFlag != null && scoutsOn(w, BLOCK) == 0 &&
                (Bodies.cost(cheap) - e) + Bodies.spawnTicks(cheap) + Bodies.cost(MOVE) + scoutEta(w, w.enemyFlag) >= theirs - RACE_MARGIN
            if (!blockerLate) { saving(w, "home defender ${Bodies.summary(cheap)}", Bodies.cost(cheap)); return }
        }
        if (theirFlagOrder(w, e, ours, theirs, blockerOnly = true)) return
        val myFlag = w.myFlag
        if (myFlag != null && scoutsOn(w, KEEP) == 0 && fightersOn(w, GUARD_FLAG) == 0 && w.enemies.none { onCell(it, myFlag) }) {
            val body = Bodies.moves(1)
            if (e >= Bodies.cost(body)) { if (order(w, body, "keeper", "holding at home; our flag is empty")) scoutQueue.addLast(KEEP); return }
            saving(w, "keeper", Bodies.cost(body)); return
        }
        val live = holdThreats.filter { !it.spawning }.ifEmpty { holdThreats }
        val guards = w.fighters.filter { Bodies.isArmed(it) && dist(it, escort) <= 8 }
        // дома без бойца — сперва ДЕШЁВЫЙ боец на рампарт, сразу как хватит: цель «боец, побеждающий в поле» росла с
        // каждым новым врагом (M1A1 → M2A2 → M3A3), и v13 копил на неё двести тиков, не купив никого, пока M1A1
        // stachu3478#1 стоял у наших рампартов, а его эскорт шёл к флагу (6ab843f2)
        val cheap = meleeBody(Bodies.cost(MOVE) + Bodies.cost(ATTACK))!!
        val body = if (guards.isEmpty() && !fighterQueue.contains(ESCORT_GUARD)) cheap
            else cheapestWinner(live, guards, SPAWN_ENERGY_CAPACITY) ?: meleeBody(minOf(e, SPAWN_ENERGY_CAPACITY)) ?: cheap
        if (e >= Bodies.cost(body)) { if (order(w, body, "defender", "holding at home vs ${live.joinToString(" ") { Bodies.summaryOf(it) + "@" + dist(it, escort) }}; guards=${guards.size}")) fighterQueue.addLast(ESCORT_GUARD); return }
        saving(w, "home defender ${Bodies.summary(body)}", Bodies.cost(body))
    }

    private val closing = HashMap<String, ArrayDeque<Pair<Int, Int>>>()
    /** Эскорт уже однажды выходил из дома после держания. */
    private var released = false
    /** Держание снимается, когда их приход ближе нашего пути от дома плюс столько тиков: ждать дальше — проиграть. */
    private const val HOLD_LAST_CALL = 60
    /** После первого выхода дом держит только живой враг в стольких клетках. */
    private const val HOLD_NEAR = 15

    /** Угроза держит эскорт дома, только пока она в стольких клетках от него (или ещё рождается, до выхода из дома). */
    private const val HOLD_RADIUS = 40

    /**
     * Рампарты, к которым враг не может встать вплотную: все восемь соседей — наши рампарты или непроходимое (кольцо
     * вокруг спавна). Мили врага эскорта на такой клетке не достаёт вовсе, а стрелок бьёт в рампарт. Угловой рампарт
     * так не защищает: стенд rush8 — M4A3 сто одиннадцать тиков рубил рампарт (11,88) под эскортом и снёс его.
     */
    private fun safeRamparts(w: World): List<Position> {
        val mine = w.myRamparts.mapTo(HashSet()) { key(it) }
        val inner = w.myRamparts.filter { r ->
            DIRECTIONS.all { (dx, dy) -> val x = r.x + dx; val y = r.y + dy; !DistanceMap.inBounds(x, y) || (x * 100 + y) in mine || DistanceMap.isWall(x, y) }
        }
        return inner.ifEmpty { w.myRamparts }
    }

    /** Поле к безопасным рампартам (цена болота эскорта). */
    private fun homeFlow(w: World): IntArray? {
        if (w.myRamparts.isEmpty()) return null
        val k = "home"
        val now = getTicks()
        val hit = flowCache[k]
        if (hit != null && (flowCacheTick[k] ?: -100) > now - 50) return hit
        val f = DistanceMap.flowFieldToAny(safeRamparts(w), w.blocked, 5)
        flowCache[k] = f
        flowCacheTick[k] = now
        return f
    }

    // ==================== бойцы ====================

    /** Задания бойцов: id -> GUARD_FLAG (наш флаг), BREAK (их флаг), ESCORT_GUARD (при эскорте). Задание даётся при
     *  рождении из очереди заказов (id из spawnCreep в Арене нет), а без очереди — по нужде. */
    private val fighterRole = HashMap<String, String>()
    private val fighterQueue = ArrayDeque<String>()

    /**
     * Бойцы. Общее для всех: враг, грызущий наш эскорт (в THREAT_RANGE от него), — цель любого бойца в двадцати
     * клетках. Дальше по заданию:
     *  - GUARD_FLAG: убить всех у нашего флага (на клетке и вплотную — вплотную стоящий занимает клетку подхода
     *    эскорта); флаг свободен — встать НА него хранителем (уходит вбок перед эскортом, как M1);
     *  - BREAK: убить того, кто держит их флаг, и встать на клетку самому;
     *  - ESCORT_GUARD: держаться у эскорта, бить их тягачей и эскорт, если достаёт.
     */
    private fun runFighters(w: World) {
        val all = w.mine.filter { !isEscort(it) && Bodies.wasArmed(it) }
        val live = all.mapTo(HashSet()) { idOf(it) }
        fighterRole.keys.retainAll { it in live }
        for (f in all) {
            val id = idOf(f)
            if (fighterRole.containsKey(id)) continue
            fighterRole[id] = if (fighterQueue.isNotEmpty()) fighterQueue.removeFirst() else ESCORT_GUARD
            println("fighter t=${w.now}: $id ${Bodies.summaryOf(f)} -> ${fighterRole[id]}")
        }
        val escort = w.escort
        val myFlag = w.myFlag
        val enemyFlag = w.enemyFlag
        for (f in w.fighters) {
            if (f.spawning) continue
            val id = idOf(f)
            val role = fighterRole[id] ?: ESCORT_GUARD
            val melee = Bodies.has(f, ATTACK)
            var target: Creep? = null
            var standOn: Position? = null
            var lead: Position? = null
            var why: String
            val threat = if (escort != null) w.enemyArmed.filter { dist(it, escort) <= THREAT_RANGE && dist(it, f) <= 20 }.minByOrNull { dist(it, f) } else null
            if (role == BREACH) {
                val b = w.flagBlocker ?: w.flagSite
                if (b == null) { fighterRole[id] = ESCORT_GUARD } else {
                    val bp = b.unsafeCast<Position>()
                    if (dist(f, bp) <= 1) { if (w.flagBlocker != null) f.attack(b); if (DEBUG_LOG && w.now % LOG_EVERY == 0) println("fighter t=${w.now}: $id breach ${protoName(b)} hits=${b.asDynamic().hits}"); continue }
                    // по дороге бьёт соседних врагов: их блокировщик в узком коридоре держал троих проломщиков в трёх
                    // клетках друг за другом до конца матча (офлайн, затычка с погоней, 6ab906a0)
                    attackBest(f, w, null)
                    if (Bodies.liveMoves(f) == 0) continue
                    val swampCost = maxOf(1, Bodies.period(Bodies.weight(f), Bodies.liveMoves(f), true))
                    stepAround(w, f, bp, 1, swampCost, 5)?.let { TrafficManager.request(f, it, FIGHTER_PRIORITY) }
                    continue
                }
            }
            // дома, но наши бойцы вместе побеждают угрозу — идут на неё, а не ждут на рампарте того, кто к рампартам не
            // придёт (M4A3 ricardo#23 стоит в центре)
            val homeHunt = holding && role == ESCORT_GUARD && escort != null && holdThreats.any { !it.spawning } &&
                wins(w.fighters.filter { Bodies.isArmed(it) }, holdThreats.filter { !it.spawning })
            if (homeHunt) {
                val prey = holdThreats.filter { !it.spawning && it.exists }.minByOrNull { dist(it, f) }
                if (prey != null) {
                    attackBest(f, w, prey)
                    if (Bodies.liveMoves(f) > 0 && dist(f, prey) > 1) {
                        val swampCost = maxOf(1, Bodies.period(Bodies.weight(f), Bodies.liveMoves(f), true))
                        stepAround(w, f, prey, 1, swampCost, 1)?.let { TrafficManager.request(f, it, FIGHTER_PRIORITY) }
                    }
                    if (DEBUG_LOG && w.now % LOG_EVERY == 0) println("fighter t=${w.now}: $id ${Bodies.summaryOf(f)} (${f.x},${f.y}) hunt ${Bodies.summaryOf(prey)} (${prey.x},${prey.y})")
                    continue
                }
            }
            if (holding && role == ESCORT_GUARD && escort != null) {
                // дома: бьём всё, что достаём, и стоим на рампарте у эскорта — с рампарта урон приходится не в нас
                attackBest(f, w, w.enemies.filter { dist(it, f) <= (if (melee) 1 else RANGED_RANGE) }.minByOrNull { it.hits })
                if (Bodies.liveMoves(f) == 0) continue
                // рампарт, с которого достаём ближайшую угрозу (мили — вплотную, стрелок — на три), иначе у эскорта
                val reach = if (melee) 1 else RANGED_RANGE
                val foe = holdThreats.filter { !it.spawning }.minByOrNull { dist(it, escort) }
                val free = w.myRamparts.filter { r -> w.occupant[key(r)].let { it == null || it === f } }
                val spot = (if (foe != null) free.filter { dist(it, foe) <= reach }.minByOrNull { dist(it, f) } else null)
                    ?: free.filter { dist(it, escort) <= 2 }.minByOrNull { dist(it, escort) * 100 + dist(it, f) }
                if (spot != null && onCell(f, spot)) { pinned.add(id); continue }
                val step = if (spot != null) stepAround(w, f, spot, 0, 5, 1) else stepAround(w, f, escort, 1, 5, 1)
                if (step != null) TrafficManager.request(f, step, FIGHTER_PRIORITY)
                continue
            }
            when {
                threat != null && (role == ESCORT_GUARD || dist(f, escort!!) <= 8) -> { target = threat; why = "defend" }
                role == GUARD_FLAG && myFlag != null -> {
                    target = w.enemies.filter { dist(it, myFlag) <= FLAG_GUARD_RANGE }.minByOrNull { dist(it, f) * 10 + (if (onCell(it, myFlag)) 0 else 1) }
                    why = if (target != null) "clear" else "keep"
                    // хранитель: на клетку флага, если там никого (эскорт, подойдя, сдвинет его — см. FINISH)
                    if (target == null && (escort == null || !onCell(escort, myFlag)) && w.occupant[key(myFlag)].let { it == null || it === f }) standOn = myFlag
                }
                role == CLEAR -> {
                    // чистильщик: первый чужой крип на нашем пути; нет — идёт впереди поезда по его маршруту
                    target = routeBlockers(w).firstOrNull()?.first
                        ?: escort?.let { e -> w.enemies.filter { idOf(it) in harassers }.minByOrNull { dist(it, e) } }
                    why = if (target != null) "clear" else "lead"
                    if (target == null && escort != null) {
                        val r = w.escortFlow?.let { Chokes.route(it, escort) }
                        lead = r?.getOrNull(minOf(6, r.size - 1))?.let { cellPos(it) }
                    }
                }
                role == BREAK && enemyFlag != null -> {
                    target = w.enemies.firstOrNull { onCell(it, enemyFlag) } ?: w.enemies.filter { !isEscort(it) && dist(it, enemyFlag) <= 1 && Bodies.isArmed(it) }.minByOrNull { dist(it, f) }
                    why = if (target != null) "break" else "block"
                    if (target == null && w.occupant[key(enemyFlag)].let { it == null || it === f }) standOn = enemyFlag
                }
                else -> {
                    // у нашего флага враг, а эскорт уже рядом с ним — это и есть оборона эскорта: без этого два флага,
                    // запертые друг другом, стояли до 2000-го тика при шести наших M4A4 «у эскорта» (стенд
                    // match4:rev+keep+blk)
                    val squatter = if (myFlag != null && escort != null && dist(escort, myFlag) <= 12)
                        w.enemies.filter { dist(it, myFlag) <= FLAG_GUARD_RANGE }.minByOrNull { dist(it, f) } else null
                    // вооружённых врагов нет, а гонку они выигрывают — их эскорт и есть цель, где бы он ни был (убитый эскорт —
                    // победа; ricardo#23 ведёт его один)
                    // …и когда вооружённые есть, но охрану их эскорта наши бойцы вместе побеждают: M4A3 ricardo#23 после
                    // засады идёт рядом со своим эскортом, и наш M4A4 шёл за нашим поездом, пока их эскорт финишировал
                    val theirEsc = w.enemyEscort
                    val escortGuards = if (theirEsc != null) w.enemyArmed.filter { !isEscort(it) && dist(it, theirEsc) <= 8 } else emptyList()
                    val chaseEscort = theirEsc != null && theirArrival(w) + RACE_MARGIN < ourArrival(w) &&
                        (escortGuards.isEmpty() || wins(w.fighters.filter { Bodies.isArmed(it) }, escortGuards))
                    // засада на нашем пути, которую наши бойцы бьют, — цель раньше их эскорта
                    val ambush = campers(w).filter { wins(w.fighters.filter { g -> Bodies.isArmed(g) }, listOf(it)) }.minByOrNull { dist(it, f) }
                    target = squatter ?: ambush ?: (if (chaseEscort) escortGuards.minByOrNull { dist(it, f) } ?: theirEsc else null)
                        ?: (enemyPullers(w) + listOfNotNull(w.enemyEscort)).filter { dist(it, f) <= 12 }.minByOrNull { it.hits }
                    why = if (squatter != null) "clear" else if (target != null) "harass" else "follow"
                }
            }
            attackBest(f, w, target)
            if (Bodies.liveMoves(f) == 0) continue
            if (standOn != null && onCell(f, standOn)) {
                if (keeperStepAside != id) pinned.add(id)
                continue
            }
            val goal: Position = target ?: standOn ?: lead ?: escort ?: w.mySpawn ?: continue
            val range = if (standOn != null && target == null) 0 else if (lead != null && target == null) 1 else if (target == null) 2 else if (melee) 1 else RANGED_RANGE
            if (dist(f, goal) <= range) continue
            val swampCost = maxOf(1, Bodies.period(Bodies.weight(f), Bodies.liveMoves(f), true))
            val step = stepAround(w, f, goal, range, swampCost, if (target != null) 1 else 5)
            if (step != null) TrafficManager.request(f, step, FIGHTER_PRIORITY)
            if (DEBUG_LOG && w.now % LOG_EVERY == 0) println("fighter t=${w.now}: $id ${Bodies.summaryOf(f)} (${f.x},${f.y}) $role/$why -> ${target?.let { "${idOf(it)} ${Bodies.summaryOf(it)} (${it.x},${it.y})" } ?: standOn?.let { "stand (${it.x},${it.y})" } ?: "escort"}")
        }
    }

    /**
     * Шаг к цели по полю. Вблизи цели (15 клеток) поле строится заново с НАШИМИ неподвижными крипами (поезд,
     * хранители — они в `pinned` и не толкаются) как стенами: иначе единственная «строго ближе» клетка оказывается
     * клеткой тягача, и боец стоит за поездом, пока их эскорт финиширует (стенд blk1+keep: страж флага простоял в
     * (91,91) за нашим же поездом с 250-го тика до поражения на 281-м).
     */
    private fun stepAround(w: World, c: Creep, goal: Position, range: Int, swampCost: Int, ttl: Int): Position? {
        if (dist(c, goal) <= 15) {
            val walls = w.active.filter { idOf(it) in pinned && it !== c && !onCell(it, goal) }
            val f = flowTo("near:${idOf(c)}", goal, w.blocked + walls, swampCost, ttl = 1)
            val step = DistanceMap.flowStep(f, c.x, c.y, range, w.occupant.keys, w.enemyAt)
            if (step != null) return step
        }
        val f = flowTo("to:${goal.x},${goal.y}", goal, w.blocked, swampCost, ttl = ttl)
        val step = DistanceMap.flowStep(f, c.x, c.y, range, w.occupant.keys, w.enemyAt) ?: return null
        // шаг в клетку нашего неподвижного (поезд дома, хранитель) — не шаг: его не толкнуть. Обход по полю, где наши
        // неподвижные в пяти клетках — стены (стенд rush8: блокировщик простоял за эскортом у спавна 150 тиков)
        val occ = w.occupant[key(step)]
        if (occ != null && occ.my && idOf(occ) in pinned) {
            val walls = w.active.filter { idOf(it) in pinned && it !== c && dist(it, c) <= 5 }
            val g = flowTo("around:${idOf(c)}", goal, w.blocked + walls, swampCost, ttl = 1)
            return DistanceMap.flowStep(g, c.x, c.y, range, w.occupant.keys, w.enemyAt)
        }
        return step
    }

    /** Удар: цель, если достаёт; иначе вооружённый враг рядом; иначе любой рядом (слабейший). */
    private fun attackBest(f: Creep, w: World, target: Creep?) {
        if (Bodies.live(f, ATTACK) > 0) {
            val adj = w.enemies.filter { dist(f, it) <= 1 }
            val t = if (target != null && dist(f, target) <= 1) target else adj.filter { Bodies.isArmed(it) }.minByOrNull { it.hits } ?: adj.minByOrNull { it.hits }
            if (t != null) f.attack(t)
        }
        if (Bodies.live(f, RANGED_ATTACK) > 0) {
            val inRange = w.enemies.filter { dist(f, it) <= RANGED_RANGE }
            val t = if (target != null && dist(f, target) <= RANGED_RANGE) target else inRange.filter { Bodies.isArmed(it) }.minByOrNull { it.hits } ?: inRange.minByOrNull { it.hits }
            if (t != null) f.rangedAttack(t)
        }
    }

    // ==================== экономика ====================

    private fun runWorkers(w: World) {
        val spawn = w.mySpawn ?: return
        val source = w.homeSource ?: return
        val miners = w.workers.filter { Bodies.has(it, WORK) && !Bodies.has(it, CARRY) && !it.spawning }
        val haulers = w.workers.filter { Bodies.isHauler(it) && !it.spawning }
        // добытчик без MOVE: у источника — добывает на пол; нет — ждёт буксира
        for (m in miners) { pinned.add(idOf(m)); if (dist(m, source) <= 1) m.harvest(source) }
        val towed = miners.firstOrNull { dist(it, source) > 1 }
        for ((i, h) in haulers.withIndex()) {
            if (i == 0 && towed != null) {
                // буксир: носильщик тянет добытчика к источнику; стоя у источника, отходит вбок и втягивает его на свою клетку
                if (dist(h, towed) > 1) { stepTo(w, h, towed, 1); continue }
                // шаг буксира — только на СВОБОДНУЮ клетку: занятую (эскорт у спавна, живой тест против stachu#10)
                // движок не даёт, тянущий стоит, и буксируемый с ним — двадцать тиков pull=0 move=0 на месте
                val toSrc = flowTo("toSource", source, w.blocked, 1)
                val to = if (dist(h, source) <= 1) asideCell(w, h, setOf(key(towed), key(source)))
                    else DIRECTIONS.map { (dx, dy) -> h.x + dx to h.y + dy }
                        .filter { (x, y) -> DistanceMap.inBounds(x, y) && !DistanceMap.isWall(x, y) && !w.occupant.containsKey(x * 100 + y) && toSrc[x * 100 + y] in 0 until toSrc[key(h)] }
                        .minByOrNull { (x, y) -> toSrc[x * 100 + y] }?.let { (x, y) -> InfluenceMap.cell(x, y) }
                if (to != null && towed.fatigue == 0 && h.fatigue == 0) {
                    // буксируемый без MOVE ходит формой move(направление) ПОСЛЕ pull: живьём она ответила 0 и добытчик
                    // шёл за носильщиком раз в третий тик, а форма move(крип) отвечала 0 и не двигала вовсе (как втягивание
                    // эскорта в v11) — 28.09.2026, 6abad699 против 6abad564
                    val rp = h.pull(towed); val rm = h.move(dirTo(h, to)); val rt = towed.move(dirTo(towed, h))
                    pinned.add(idOf(h))
                    if (DEBUG_LOG && w.now < 120) println("tow t=${w.now}: hauler (${h.x},${h.y}) -> (${to.x},${to.y}) pull=$rp move=$rm miner (${towed.x},${towed.y}) move=$rt")
                }
                continue
            }
            val carrying = h.store[RESOURCE_ENERGY] ?: 0
            val capacity = h.store.getCapacity(RESOURCE_ENERGY) ?: 0
            val pile = getObjectsByPrototype(screeps.api.Resource::class)
                .filter { it.exists && it.resourceType == RESOURCE_ENERGY && dist(it, source) <= 2 }.maxByOrNull { it.amount }
            if (carrying < capacity && pile != null) {
                if (dist(h, pile) <= 1) h.pickup(pile) else stepTo(w, h, pile, 1)
            } else if (carrying > 0) {
                if (dist(h, spawn) <= 1) h.transfer(spawn, RESOURCE_ENERGY) else stepTo(w, h, spawn, 1)
            } else if (dist(h, source) > 2) stepTo(w, h, source, 2)
        }
        // самодобытчики (MCWW): добывают и носят сами
        for (h in w.workers) {
            if (h.spawning || h in miners || h in haulers) continue
            val carrying = h.store[RESOURCE_ENERGY] ?: 0
            val capacity = h.store.getCapacity(RESOURCE_ENERGY) ?: 0
            val nearSource = dist(h, source) <= 1
            val nearSpawn = dist(h, spawn) <= 1
            val deliver = carrying >= capacity || (carrying > 0 && source.energy <= 0)
            if (deliver) {
                if (nearSpawn) h.transfer(spawn, RESOURCE_ENERGY) else stepTo(w, h, spawn, 1)
            } else {
                if (nearSource) { if (source.energy > 0) h.harvest(source) } else stepTo(w, h, source, 1)
            }
        }
    }

    /** Шаг крипа красной команды: в обход наших неподвижных (эскорт, поезд) — иначе строитель полз за одиноким эскортом
     *  по его коридору четыре тика на клетку (лига, plug против v19, 27.09.2026: к контейнеру на 210-м вместо ~110-го). */
    internal fun stepRed(w: World, c: Creep, target: Position, range: Int, avoidOwn: Boolean = false) {
        val swampCost = maxOf(1, Bodies.period(Bodies.weight(c) + ((c.store[RESOURCE_ENERGY] ?: 0) + 49) / 50, Bodies.liveMoves(c), true))
        // в обход ВСЕХ своих рядом, не только неподвижных: одинокий эскорт идёт клетку в четыре тика, и засада M4A3 шла
        // за ним до центра вдвое дольше живой (ricardo#7 обогнал свой эскорт на 25-м, 6abb805d)
        val step = (if (avoidOwn) {
            val walls = w.active.filter { it !== c && dist(it, c) <= 3 }
            DistanceMap.flowStep(flowTo("avoid:${idOf(c)}", target, w.blocked + walls, swampCost, ttl = 1), c.x, c.y, range, w.occupant.keys, w.enemyAt)
        } else null) ?: stepAround(w, c, target, range, swampCost, 20) ?: return
        TrafficManager.request(c, step, SCOUT_PRIORITY)
    }

    internal fun stepTo(w: World, c: Creep, target: Position, range: Int) {
        val swampCost = maxOf(1, Bodies.period(Bodies.weight(c) + ((c.store[RESOURCE_ENERGY] ?: 0) + 49) / 50, Bodies.liveMoves(c), true))
        val f = flowTo("to:${target.x},${target.y}:$swampCost", target, w.blocked, swampCost)
        val step = DistanceMap.flowStep(f, c.x, c.y, range, w.occupant.keys, w.enemyAt) ?: return
        TrafficManager.request(c, step, WORKER_PRIORITY)
    }

    // ==================== враг ====================

    /** Куда идут их разведчики: по изменению дистанции до обоих флагов за окно (до развилки у центра оба сокращаются). */
    private fun trackEnemyScouts(w: World) {
        val mf = w.myFlag ?: return
        val ef = w.enemyFlag ?: return
        val toMine = flowTo("toMyFlag", mf, w.blocked, 1)
        val toTheirs = flowTo("toTheirFlag", ef, w.blocked, 1)
        val live = HashSet<String>()
        for (s in w.enemyScouts) {
            val id = idOf(s)
            live.add(id)
            val h = scoutTrack.getOrPut(id) { ArrayDeque() }
            h.addLast(Triple(w.now, toMine[key(s)], toTheirs[key(s)]))
            while (h.size > 12) h.removeFirst()
        }
        scoutTrack.keys.retainAll { it in live }
    }

    /** Куда идёт их разведчик: MINE (к нашему флагу), THEIRS (к своему), "" — неясно. */
    private fun scoutHeading(id: String): String {
        val h = scoutTrack[id] ?: return ""
        if (h.size < 6) return ""
        val a = h.first(); val b = h.last()
        val dm = a.second - b.second
        val dt = a.third - b.third
        if (b.second == 0) return "MINE"
        if (b.third == 0) return "THEIRS"
        return if (dm > dt) "MINE" else if (dt > dm) "THEIRS" else ""
    }

    // ==================== поля ====================

    private fun flowTo(name: String, target: Position, blocked: List<Position>, swampCostIn: Int, ttl: Int = 50): IntArray {
        // цена болота — кольцо корзин в DistanceMap.dial: крип без живых MOVE давал период «никогда» (Int.MAX/4), и
        // поле пыталось завести полмиллиарда корзин (стенд rush+harvest:nopull, t=229 — куча исчерпана)
        val swampCost = swampCostIn.coerceIn(1, 20)
        val k = "$name@${target.x},${target.y}/$swampCost"
        val now = getTicks()
        val hit = flowCache[k]
        val at = flowCacheTick[k]
        if (hit != null && at != null && now - at < ttl) return hit
        val f = DistanceMap.flowFieldTo(target, blocked, swampCost)
        flowCache[k] = f
        flowCacheTick[k] = now
        if (flowCache.size > 64) {
            val old = flowCacheTick.entries.sortedBy { it.value }.take(32).map { it.key }
            for (o in old) { flowCache.remove(o); flowCacheTick.remove(o) }
        }
        return f
    }

    // ==================== журнал ====================

    private fun probe(w: World) {
        println("hello season4 escort-run $BOT_VERSION: ${arenaInfo.season} - ${arenaInfo.name} level=${arenaInfo.level} ticksLimit=${arenaInfo.ticksLimit} " +
            "cpu=${arenaInfo.cpuTimeLimit}/${arenaInfo.cpuTimeLimitFirstTick} t=${w.now}")
        println("tuning: train=reverse spawnPull=true openingMoves=$OPENING_MOVES pullerMin=$PULLER_MIN_MOVE chain=$MAX_CHAIN raceMargin=$RACE_MARGIN keeperLead=$KEEPER_MIN_LEAD threat=$THREAT_RANGE")
        println("persona: ${RedTeam.describe()}")
        println("world: spawn=${w.mySpawn?.let { "(${it.x},${it.y}) e=${it.store[RESOURCE_ENERGY]}" }} enemySpawn=${w.enemySpawn?.let { "(${it.x},${it.y})" }} " +
            "escort=${w.escort?.let { "(${it.x},${it.y}) ${Bodies.summaryOf(it)}" }} enemyEscort=${w.enemyEscort?.let { "(${it.x},${it.y})" }} " +
            "myFlag=${w.myFlag?.let { "(${it.x},${it.y})" }} enemyFlag=${w.enemyFlag?.let { "(${it.x},${it.y})" }} source=${w.homeSource?.let { "(${it.x},${it.y}) ${it.energy}/${it.energyCapacity}" }}")
    }

    private fun logStatus(w: World) {
        val escort = w.escort
        println(
            "t=${w.now} e=${energyOf(w)} escort=${escort?.let { "(${it.x},${it.y}) h=${it.hits} f=${it.fatigue} m=${Bodies.liveMoves(it)}" } ?: "DEAD"} " +
                "arrive=${ourArrival(w)} enemyEscort=${w.enemyEscort?.let { "(${it.x},${it.y}) h=${it.hits} f=${it.fatigue} m=${Bodies.liveMoves(it)}+${enemyPullers(w).sumOf { p -> Bodies.liveMoves(p) }}" } ?: "none"} theirArrive=${theirArrival(w)} " +
                "pullers=${w.pullers.size} chain=${lastChain.size} scouts=${w.scouts.joinToString(",") { "${scoutMission[idOf(it)] ?: "?"}(${it.x},${it.y})" }} " +
                "fighters=${w.fighters.size} workers=${w.workers.size} enemies=${w.enemies.size} armed=${w.enemyArmed.size} " +
                "myFlag=${w.myFlag?.let { f -> w.occupant[key(f)]?.let { (if (it.my) "us:" else "THEM:") + Bodies.summaryOf(it) } ?: "free" }} " +
                "theirFlag=${w.enemyFlag?.let { f -> w.occupant[key(f)]?.let { (if (it.my) "us:" else "them:") + Bodies.summaryOf(it) } ?: "free" }} " +
                "enemyScouts=${w.enemyScouts.joinToString(",") { "${idOf(it)}(${it.x},${it.y})${scoutHeading(idOf(it))}" }} pull=${pullChecks - pullMisses}/$pullChecks"
        )
    }

    private fun logRace(w: World) {
        if (w.now > RACE_TRACE_TICKS) return
        val ours = w.escort
        val theirs = w.enemyEscort
        println("race t=${w.now} ours=${ours?.let { "(${it.x},${it.y}) f=${it.fatigue}" }} theirs=${theirs?.let { "(${it.x},${it.y}) f=${it.fatigue}" }} " +
            "theirs+=${w.enemies.filter { !isEscort(it) }.joinToString(" ") { "${Bodies.summaryOf(it)}(${it.x},${it.y})" }} pending=${w.enemyPending.joinToString(" ") { Bodies.summaryOf(it) }}")
    }

    private fun logBodies(w: World) {
        println("bodies t=${w.now} ours: " + w.active.joinToString(" ") { "${idOf(it)}${if (isEscort(it)) "E" else ""}(${it.x},${it.y})${Bodies.summaryOf(it)}h${it.hits}" } +
            " | enemy: " + w.enemies.joinToString(" ") { "${idOf(it)}${if (isEscort(it)) "E" else ""}(${it.x},${it.y})${Bodies.summaryOf(it)}h${it.hits}" } +
            " | pending: " + w.enemyPending.joinToString(" ") { Bodies.summaryOf(it) })
    }

    private fun captureMapMarks(w: World) {
        val m = HashMap<Int, Char>()
        fun mark(x: Int, y: Int, c: Char) { m[x * 100 + y] = c }
        getObjectsByPrototype(StructureRampart::class).forEach { mark(it.x, it.y, if (it.my == true) 'r' else 'R') }
        getObjectsByPrototype(StructureWall::class).forEach { mark(it.x, it.y, 'W') }
        getObjectsByPrototype(StructureContainer::class).forEach { mark(it.x, it.y, 'C') }
        getObjectsByPrototype(Source::class).forEach { mark(it.x, it.y, 'S') }
        getObjectsByPrototype(StructureSpawn::class).forEach { mark(it.x, it.y, if (it.my == true) 'M' else 'E') }
        w.myFlag?.let { mark(it.x, it.y, 'f') }
        w.enemyFlag?.let { mark(it.x, it.y, 'F') }
        mapMarks = m
    }

    private fun logMap(fromRow: Int) {
        val marks = mapMarks ?: return
        val out = StringBuilder(if (fromRow == 0) "=== MAP (y rows, x cols; # wall ~ swamp r/R ramparts W walls M/E spawns S source C container f/F flags) ===" else "")
        for (y in fromRow until minOf(fromRow + 25, 100)) {
            val row = StringBuilder()
            for (x in 0..99) {
                val s = marks[x * 100 + y]
                row.append(when { s != null -> s; DistanceMap.isTerrainWall(x, y) -> '#'; DistanceMap.isSwamp(x, y) -> '~'; else -> '.' })
            }
            if (out.isNotEmpty()) out.append('\n')
            out.append(y.toString().padStart(2, '0')).append(':').append(row)
        }
        println(out.toString())
    }

    @Suppress("unused")
    private val harvestPower = HARVEST_POWER
}
