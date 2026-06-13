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
import screeps.api.DirectionConstant
import screeps.api.SearchPathOptions
import screeps.api.HEAL
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

    /** Тело бойца: MOVE + RANGED_ATTACK (с бонусом RANGED бьёт вдвойне). Цена 200. */
    private val FIGHTER_BODY: Array<BodyPartType> = arrayOf(MOVE, RANGED_ATTACK)
    private const val FIGHTER_COST = 200

    /** Минимум бойцов для перехода от контроля центра к штурму вражеского спавна. */
    private const val PUSH_MIN_FIGHTERS = 5

    /** ТЕСТОВЫЙ флаг: не штурмовать вражеский спавн раньше этого тика — на тестовых матчах копим и
     *  проверяем экономику, не рашим. Для боевых вернуть 0. */
    private const val PUSH_NOT_BEFORE_TICK = 1500

    /** Дальность RANGED_ATTACK. */
    private const val RANGED_RANGE = 3

    /** Печатать ли диагностику (на время разработки). */
    private const val DEBUG_LOG = true

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
    private var ourFlagX = 0   // НАШ бонус-флаг нужного типа (ближний к нашему спавну, не вражеский)
    private var ourFlagY = 0
    private var initialOwnFlags = 0
    // Фиксированные клетки копателей у нашего источника: две СМЕЖНЫЕ друг другу клетки-соседа
    // (гарантируют общую клетку для хаулера, смежную обоим). Майнеры встают по id: меньший -> spot1.
    private var sourceX = 0; private var sourceY = 0   // наш источник (клетку нельзя занимать)
    private var minerSpot1X = 0; private var minerSpot1Y = 0
    private var minerSpot2X = 0; private var minerSpot2Y = 0
    /** Направление выхода из спавна в сторону источника — для майнеров и хаулера (им туда ехать). */
    private var minerDir: DirectionConstant? = null

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
        // направление выхода майнера — к его клетке у источника (экономит тики на старте)
        minerDir = getDirection(minerSpot1X - mySpawn.x, minerSpot1Y - mySpawn.y)
        homeSpawnId = mySpawn.id
        findForwardBase(mySpawn, ourSource, sources)
        findGuardPost(enemySpawn, sources)
        layoutReady = true
        if (DEBUG_LOG) println(
            "layout: mySpawn=(${mySpawn.x},${mySpawn.y}) enemySpawn=($enemySpawnX,$enemySpawnY) " +
                "ourFlag=($ourFlagX,$ourFlagY) center=($centerX,$centerY) ownFlags=$initialOwnFlags " +
                "minerSpots=($minerSpot1X,$minerSpot1Y),($minerSpot2X,$minerSpot2Y) " +
                "forward=$forwardEnabled src=($secondSrcX,$secondSrcY) fwdSpawn=($fwdSpawnX,$fwdSpawnY) fwdMiner=($fwdMinerX,$fwdMinerY) tower=($towerX,$towerY) " +
                "guard=$guardEnabled guardAt=($guardX,$guardY) guardTower=($guardTowerX,$guardTowerY)"
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
        val blocked = CostMatrix()
        getObjectsByPrototype(StructureWall::class).forEach { if (it.exists) blocked.set(it.x, it.y, 255) }
        val opts = SearchPathOptions(costMatrix = blocked)
        val second = sources
            .filter { it.x != homeSource.x || it.y != homeSource.y }
            .filter { searchPath(spawnPos, SearchGoal(pos = cell(it.x, it.y), range = 1), opts).incomplete.not() }
            .minByOrNull { getRange(mySpawn, it) } ?: return
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
        val blocked = CostMatrix()
        getObjectsByPrototype(StructureWall::class).forEach { if (it.exists) blocked.set(it.x, it.y, 255) }
        val opts = SearchPathOptions(costMatrix = blocked)
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

        // роли по телу: форвард-майнер — WORK>=6; guard — WORK 1..5 и MOVE>=3 (3 MOVE, у домашнего 1);
        // домашний копатель — WORK 1..5 и MOVE<3; хаулер — CARRY без WORK/RANGED; боец — RANGED;
        // захватчик — только MOVE.
        val forwardMiners = myCreeps.filter { c -> c.body.count { it.type == WORK } >= 6 }
        val guards = myCreeps.filter { c ->
            val w = c.body.count { it.type == WORK }
            w in 1..5 && c.body.count { it.type == MOVE } >= 3
        }
        val miners = myCreeps.filter { c ->
            val w = c.body.count { it.type == WORK }
            w in 1..5 && c.body.count { it.type == MOVE } < 3
        }
        val haulers = myCreeps.filter { c -> c.body.none { it.type == WORK || it.type == RANGED_ATTACK } && c.body.any { it.type == CARRY } }
        val fighters = myCreeps.filter { c -> c.body.any { it.type == RANGED_ATTACK } }
        val capturers = myCreeps.filter { c -> c.body.none { it.type == WORK || it.type == RANGED_ATTACK || it.type == CARRY } }
        haulerPhase.keys.retainAll(haulers.mapTo(HashSet()) { it.id }) // выбрасываем фазы погибших хаулеров

        spawn(homeSpawn, capturers, miners, haulers, forwardMiners, guards, bonusCaptured)
        // форвард-спавн (как построится и наполнится) — производит бойцов из своего store, ближе к фронту
        if (forwardSpawn != null && forwardSpawn.spawning == null && (forwardSpawn.store[RESOURCE_ENERGY] ?: 0) >= FIGHTER_COST) {
            forwardSpawn.spawnCreep(FIGHTER_BODY)
        }

        // башни бьют ближайшего врага в радиусе (заряженная башня — оборона форвард-базы)
        if (enemyCreeps.isNotEmpty()) {
            for (tower in getObjectsByPrototype(StructureTower::class).filter { it.my == true }) {
                if ((tower.store[RESOURCE_ENERGY] ?: 0) < TOWER_ENERGY_COST) continue
                val target = enemyCreeps.minByOrNull { getRange(tower, it) } ?: continue
                if (getRange(tower, target) <= TOWER_RANGE) tower.attack(target)
            }
        }

        val center = cell(centerX, centerY)
        // цель захватчика — НАШ кэшированный флаг (объект исчезнет при захвате, позиция — нет)
        val captureTarget = if (!bonusCaptured) cell(ourFlagX, ourFlagY) else center
        // переход от контроля центра к штурму: бонус взят, набралась ударная масса бойцов И настал
        // тик штурма (на тестовых матчах PUSH_NOT_BEFORE_TICK держит армию у центра — проверяем экономику).
        val pushing = bonusCaptured && fighters.size >= PUSH_MIN_FIGHTERS && getTicks() >= PUSH_NOT_BEFORE_TICK

        for (creep in capturers) creep.moveTo(captureTarget)
        // копатели по id (детерминированно) встают каждый на свою фиксированную клетку
        val minersSorted = miners.sortedBy { it.id.unsafeCast<Double>() }
        for ((idx, creep) in minersSorted.withIndex()) {
            val spot = if (idx == 0) cell(minerSpot1X, minerSpot1Y) else cell(minerSpot2X, minerSpot2Y)
            runMiner(creep, spot, sources, haulers, homeSpawn)
        }
        for (creep in haulers) runHauler(creep, miners, homeSpawn)
        for (creep in forwardMiners) runForwardMiner(creep, sources, forwardSpawn)
        for (creep in guards) runGuard(creep)
        for (creep in fighters) runFighter(creep, enemyCreeps, enemySpawn, center, pushing)

        if (DEBUG_LOG) {
            println(
                "ps: tick=${getTicks()} cap=${capturers.size} mnr=${miners.size} fmnr=${forwardMiners.size} grd=${guards.size} hlr=${haulers.size} fig=${fighters.size} " +
                    "enemy=${enemyCreeps.size} captured=$bonusCaptured spawns=${mySpawns.size} " +
                    "homeE=${homeSpawn.store[RESOURCE_ENERGY] ?: 0} fwdE=${forwardSpawn?.store?.get(RESOURCE_ENERGY) ?: -1} push=$pushing"
            )
        }
    }

    /**
     * Очередь спавна (bootstrap-порядок): 1) ПЕРВЫЙ копатель (влезает в стартовые 500) — идёт
     * копать; 2) хаулер (дёшево, 100) — сразу начинает вывозить, копатель не отрывается от
     * источника; 3) ВТОРОЙ копатель; 4) захватчик флага (50, бонус не срочен — бойцов ещё нет);
     * 5) дальше бойцы. Тела дороже капчерера спавним только когда в store хватает энергии.
     */
    private fun spawn(spawn: StructureSpawn, capturers: List<Creep>, miners: List<Creep>, haulers: List<Creep>, forwardMiners: List<Creep>, guards: List<Creep>, bonusCaptured: Boolean) {
        if (spawn.spawning != null) return
        val energy = spawn.store[RESOURCE_ENERGY] ?: 0
        // майнеры/хаулер выходят в сторону источника; остальные — куда угодно, КРОМЕ клеток,
        // через которые хаулеры реально стыкуются со спавном (dockDirs, наблюдаются).
        val toSource = minerDir?.let { arrayOf(it) }
        val avoidDock = ALL_DIRECTIONS.filter { it !in dockDirs }.toTypedArray()
            .ifEmpty { ALL_DIRECTIONS } // если вдруг исключили всё — не запираем спавн
        when {
            miners.isEmpty() -> if (energy >= MINER_COST) spawnWith(spawn, MINER_BODY, toSource)
            haulers.size < TARGET_HAULERS -> if (energy >= HAULER_COST) spawnWith(spawn, HAULER_BODY, toSource)
            miners.size < TARGET_MINERS -> if (energy >= MINER_COST) spawnWith(spawn, MINER_BODY, toSource)
            // форвард-майнер: строит и кормит второй спавн у дальнего источника (один на базу)
            forwardEnabled && forwardMiners.isEmpty() -> if (energy >= FORWARD_MINER_COST) spawnWith(spawn, FORWARD_MINER_BODY, avoidDock)
            !bonusCaptured && capturers.isEmpty() -> if (energy >= CAPTURER_COST) spawnWith(spawn, CAPTURER_BODY, avoidDock)
            // guard: упреждающий пост у вражеской форвард-базы (один)
            guardEnabled && guards.isEmpty() -> if (energy >= GUARD_COST) spawnWith(spawn, GUARD_BODY, avoidDock)
            else -> if (energy >= FIGHTER_COST) spawnWith(spawn, FIGHTER_BODY, avoidDock)
        }
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
    private fun runForwardMiner(creep: Creep, sources: Array<Source>, forwardSpawn: StructureSpawn?) {
        if (creep.getRangeTo(cell(fwdMinerX, fwdMinerY)) > 0) {
            creep.moveTo(cell(fwdMinerX, fwdMinerY))
            return
        }
        val source = sources.firstOrNull { it.x == secondSrcX && it.y == secondSrcY }
            ?: creep.findClosestByRange(sources) ?: return

        fun rampartAt(x: Int, y: Int) =
            getObjectsByPrototype(StructureRampart::class).any { it.my == true && it.x == x && it.y == y }
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
    private fun runGuard(creep: Creep) {
        if (creep.getRangeTo(cell(guardX, guardY)) > 0) {
            creep.moveTo(cell(guardX, guardY))
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

    /**
     * Боец: всегда стреляет по тому, что в радиусе (массовая выгоднее одиночной по нескольким целям).
     * Движение: до накопления массы держим ЦЕНТР (контроль коридора), затем штурмуем вражеский спавн.
     */
    private fun runFighter(creep: Creep, enemyCreeps: List<Creep>, enemySpawn: StructureSpawn?, center: Position, pushing: Boolean) {
        shoot(creep, enemyCreeps, enemySpawn)
        val target: Position = if (pushing && enemySpawn != null) enemySpawn else center
        creep.moveTo(target)
    }

    /** Стрельба: массовая, если в радиусе ≥2 целей; иначе одиночная по самому раненому, или по спавну. */
    private fun shoot(creep: Creep, enemyCreeps: List<Creep>, enemySpawn: StructureSpawn?) {
        val inRange = enemyCreeps.filter { creep.getRangeTo(it) <= RANGED_RANGE }
        when {
            inRange.size >= 2 -> creep.rangedMassAttack()
            inRange.size == 1 -> creep.rangedAttack(inRange.minByOrNull { it.hits }!!)
            enemySpawn != null && creep.getRangeTo(enemySpawn) <= RANGED_RANGE -> creep.rangedAttack(enemySpawn)
        }
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
