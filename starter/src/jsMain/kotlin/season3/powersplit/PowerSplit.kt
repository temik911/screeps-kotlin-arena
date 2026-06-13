package season3.powersplit

import kotlinx.js.JsPlainObject
import screeps.api.ATTACK
import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.Creep
import screeps.api.HEAL
import screeps.api.MOVE
import screeps.api.Position
import screeps.api.RANGED_ATTACK
import screeps.api.RESOURCE_ENERGY
import screeps.api.Source
import screeps.api.TERRAIN_SWAMP
import screeps.api.TERRAIN_WALL
import screeps.api.WORK
import screeps.api.arenaInfo
import screeps.api.get
import screeps.api.getObjectsByPrototype
import screeps.api.getTerrainAt
import screeps.api.getTicks
import screeps.api.season3.BonusFlag
import screeps.api.structures.StructureContainer
import screeps.api.structures.StructureExtension
import screeps.api.structures.StructureRampart
import screeps.api.structures.StructureSpawn
import screeps.api.structures.StructureWall
import sourcemaps.runWithSourceMapSupport

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

    /** Тело харвестера: WORK качает источник, CARRY везёт, MOVE двигает. Цена 200. */
    private val HARVESTER_BODY: Array<BodyPartType> = arrayOf(MOVE, WORK, CARRY)
    private const val HARVESTER_COST = 200

    /** Тело бойца: MOVE + RANGED_ATTACK (с бонусом RANGED бьёт вдвойне). Цена 200. */
    private val FIGHTER_BODY: Array<BodyPartType> = arrayOf(MOVE, RANGED_ATTACK)
    private const val FIGHTER_COST = 200

    /** Сколько харвестеров копим, прежде чем переключиться на бойцов (экономика на WORK). */
    private const val TARGET_HARVESTERS = 4

    /** Минимум бойцов для перехода от контроля центра к штурму вражеского спавна. */
    private const val PUSH_MIN_FIGHTERS = 5

    /** Дальность RANGED_ATTACK. */
    private const val RANGED_RANGE = 3

    /** Печатать ли диагностику (на время разработки). */
    private const val DEBUG_LOG = true

    /** Печатать ли ASCII-карту поля один раз (terrain + структуры) для анализа геометрии.
     *  Геометрия снята и записана в память (s3-powersplit-map) — держим false; включать при нужде. */
    private const val DEBUG_MAP = false
    private var mapLogged = false

    private var greeted = false

    /** Позиция центра клетки (для getTerrainAt). */
    private fun cell(x: Int, y: Int): Position = IntPos(x = x, y = y).unsafeCast<Position>()

    fun tick() {
        if (!greeted) {
            greeted = true
            println("hello season3 power-split: ${arenaInfo.season} - ${arenaInfo.name}")
        }

        if (DEBUG_MAP && !mapLogged) {
            mapLogged = true
            logMap()
        }

        val mySpawn = getObjectsByPrototype(StructureSpawn::class).firstOrNull { it.my == true } ?: return
        val enemySpawn = getObjectsByPrototype(StructureSpawn::class).firstOrNull { it.my == false }

        val myCreeps = getObjectsByPrototype(Creep::class).filter { it.my && !it.spawning }
        val enemyCreeps = getObjectsByPrototype(Creep::class).filter { !it.my && !it.spawning }

        val sources = getObjectsByPrototype(Source::class)
        val bonusFlags = getObjectsByPrototype(BonusFlag::class)

        // целевой бонус-флаг: предпочитаем RANGED, иначе любой доступный. Флаги у базы исчезают
        // после захвата — пустой список означает «бонус уже взят» (или ещё не подгружены на 1-м тике).
        val targetFlag = bonusFlags.firstOrNull { it.bonusType == DESIRED_BONUS } ?: bonusFlags.firstOrNull()
        val bonusCaptured = bonusFlags.isEmpty()

        // роли по телу: захватчик — только MOVE; харвестер — есть WORK; боец — есть RANGED_ATTACK.
        val capturers = myCreeps.filter { c -> c.body.none { it.type == WORK || it.type == RANGED_ATTACK } }
        val harvesters = myCreeps.filter { c -> c.body.any { it.type == WORK } }
        val fighters = myCreeps.filter { c -> c.body.any { it.type == RANGED_ATTACK } }

        spawn(mySpawn, capturers, harvesters, bonusCaptured)

        // центр карты — середина между спавнами; ключевую зону (коридор) держим, потом штурмуем.
        val center: Position = if (enemySpawn != null) {
            cell((mySpawn.x + enemySpawn.x) / 2, (mySpawn.y + enemySpawn.y) / 2)
        } else {
            cell(mySpawn.x, mySpawn.y)
        }
        // переход от контроля центра к штурму: бонус взят и набралась ударная масса бойцов.
        val pushing = bonusCaptured && fighters.size >= PUSH_MIN_FIGHTERS

        for (creep in capturers) runCapturer(creep, targetFlag, center)
        for (creep in harvesters) runHarvester(creep, sources, mySpawn)
        for (creep in fighters) runFighter(creep, enemyCreeps, enemySpawn, center, pushing)

        if (DEBUG_LOG) {
            println(
                "ps: tick=${getTicks()} cap=${capturers.size} harv=${harvesters.size} fig=${fighters.size} " +
                    "enemy=${enemyCreeps.size} flags=${bonusFlags.size} captured=$bonusCaptured " +
                    "spawnE=${mySpawn.store[RESOURCE_ENERGY] ?: 0} push=$pushing"
            )
        }
    }

    /**
     * Очередь спавна: 1) захватчик RANGED-флага (пока бонус не взят); 2) харвестеры до
     * TARGET_HARVESTERS (экономика на WORK); 3) дальше бойцы. Спавним только если в store
     * хватает энергии на тело — иначе ждём, пока харвестеры донесут.
     */
    private fun spawn(spawn: StructureSpawn, capturers: List<Creep>, harvesters: List<Creep>, bonusCaptured: Boolean) {
        if (spawn.spawning != null) return
        val energy = spawn.store[RESOURCE_ENERGY] ?: 0
        when {
            !bonusCaptured && capturers.isEmpty() -> spawn.spawnCreep(CAPTURER_BODY) // дёшево (50), копить не надо
            harvesters.size < TARGET_HARVESTERS -> if (energy >= HARVESTER_COST) spawn.spawnCreep(HARVESTER_BODY)
            else -> if (energy >= FIGHTER_COST) spawn.spawnCreep(FIGHTER_BODY)
        }
    }

    /** Захватчик: бежит вставать на нужный бонус-флаг; после захвата едет к центру (мясо/разведка). */
    private fun runCapturer(creep: Creep, targetFlag: BonusFlag?, center: Position) {
        if (targetFlag != null) creep.moveTo(targetFlag) else creep.moveTo(center)
    }

    /** Харвестер: не полон — качает ближайший источник; полон — везёт энергию в спавн. */
    private fun runHarvester(creep: Creep, sources: Array<Source>, spawn: StructureSpawn) {
        val full = (creep.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) == 0
        if (!full) {
            val source = creep.findClosestByRange(sources) ?: return
            if (creep.getRangeTo(source) > 1) creep.moveTo(source) else creep.harvest(source)
        } else {
            if (creep.getRangeTo(spawn) > 1) creep.moveTo(spawn) else creep.transfer(spawn, RESOURCE_ENERGY)
        }
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
