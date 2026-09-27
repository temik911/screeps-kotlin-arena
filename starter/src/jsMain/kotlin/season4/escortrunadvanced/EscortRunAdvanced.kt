package season4.escortrunadvanced

import kotlinx.js.JsPlainObject
import screeps.api.ATTACK
import screeps.api.BODYPART_HITS
import screeps.api.BUILD_POWER
import screeps.api.CONSTRUCTION_COST
import screeps.api.EXTENSION_ENERGY_CAPACITY
import screeps.api.TOWER_CAPACITY
import screeps.api.TOWER_COOLDOWN
import screeps.api.TOWER_POWER_ATTACK
import screeps.api.TOWER_RANGE
import screeps.api.TOWER_ENERGY_COST
import screeps.api.createConstructionSite
import screeps.api.structures.StructureTower
import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.CREEP_SPAWN_TIME
import screeps.api.ConstructionSite
import screeps.api.Creep
import screeps.api.Flag
import screeps.api.GameObject
import screeps.api.HEAL
import screeps.api.MOVE
import screeps.api.Position
import screeps.api.RANGED_ATTACK
import screeps.api.RESOURCE_ENERGY
import screeps.api.Resource
import screeps.api.SOURCE_ENERGY_REGEN
import screeps.api.SPAWN_ENERGY_CAPACITY
import screeps.api.Source
import screeps.api.TERRAIN_SWAMP
import screeps.api.TERRAIN_WALL
import screeps.api.TOUGH
import screeps.api.WORK
import screeps.api.arenaInfo
import screeps.api.get
import screeps.api.getCpuTime
import screeps.api.getDirection
import screeps.api.getObjects
import screeps.api.getObjectsByPrototype
import screeps.api.getRange
import screeps.api.getTerrainAt
import screeps.api.getTicks
import screeps.api.structures.StructureContainer
import screeps.api.structures.StructureRampart
import screeps.api.structures.StructureSpawn
import screeps.api.structures.StructureWall
import sourcemaps.runWithSourceMapSupport

/** The bot's version, printed in the greeting — the only thing that ties a match log back to a commit. */
const val BOT_VERSION = 11

@OptIn(ExperimentalJsExport::class)
@JsExport
fun loop() {
    runWithSourceMapSupport { EscortRunAdvanced.tick() }
}

@JsPlainObject
external interface Cell {
    var x: Int
    var y: Int
}

fun cell(x: Int, y: Int): Position = Cell(x = x, y = y).unsafeCast<Position>()

fun key(p: Position) = p.x * 100 + p.y

fun cellOf(k: Int): Position = cell(k / 100, k % 100)

fun protoName(o: GameObject): String = (o.asDynamic().constructor?.name as? String) ?: "?"

fun own(my: Boolean?) = when (my) { true -> "o"; false -> "e"; null -> "n" }

fun at(p: Position) = "(${p.x},${p.y})"

val DIRECTIONS = listOf(-1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1)

/**
 * Season 4 «Escort Run» ADVANCED, v2 — the fortress.
 *
 * What v1's probe and eight live matches measured (docs/escort-run-advanced.md): three escorts a side (`M10T40`
 * 5000 hits period 4/20, `M5T40` 4500 8/40, `M3T42` 4500 14/70) standing in a 5×5 block of their own ramparts round the
 * spawn; three flags a side on the far edge; the win is all three escorts on flags, or ONE enemy escort dead. A creep on
 * its own rampart takes no damage — the rampart (10 000) takes it (1 100 hits in stored replays, melee and ranged
 * alike). And the field hunts: an escort that walks out alone meets two `M2A1` or a `T1M3R2` in the centre and dies
 * (four losses of v1).
 *
 * So v2 keeps the escorts home on the INNER ring of the block, where no melee can stand beside them, builds an economy
 * on the home source, and grows an army that
 *  - kills whatever comes near home (fighting from our ramparts where it can);
 *  - strikes an enemy escort caught outside its ramparts, when the fight against everything of his near it is ours;
 *  - lays siege to his block when the army outguns everything he has, melee first on the escort a melee can reach.
 * Every choice of fight is a simulation of the two groups (Bodies.fight), never a count.
 */
object EscortRunAdvanced {

    // ---------- economy ----------
    /** A source regenerates SOURCE_ENERGY_REGEN a tick; 2 energy per WORK per tick, so this many WORK saturate it. */
    private val WORK_TARGET = (SOURCE_ENERGY_REGEN + 1) / 2
    private const val HAULERS = 2

    // ---------- army ----------
    /** An enemy fighter within this many cells of our spawn is a threat to home. */
    private const val HOME_RADIUS = 12
    /** Enemy fighters this close to his escort defend it in a strike. */
    private const val ESCORT_GUARD_RADIUS = 10
    /** Our share of hit points that must survive the simulated strike / siege for it to be worth starting. */
    private const val STRIKE_MARGIN = 0.35
    private const val SIEGE_MARGIN = 0.5
    private const val SIEGE_MIN_FIGHTERS = 4
    /** Once started, a strike or siege is kept while the simulation still wins at all. */
    private const val KEEP_MARGIN = 0.05
    /** His fighter this close to our way to the target joins the fight we simulate. */
    private const val PATH_RADIUS = 6
    /** The army's bodies are M5X5 — five MOVE on five weights: a swamp cell costs five plain ones. */
    private const val ARMY_SWAMP_COST = 5
    /** An operation takes the largest bunch of fighters all within this many cells of one of them. */
    private const val CLUSTER_RADIUS = 8
    /** Defending, the home radius widens by this much, so a threat standing on its edge does not flip the mode. */
    private const val DEFEND_KEEP = 4
    /** Defenders leave the ramparts only when the open fight leaves them this share of their hit points. */
    private const val DEFEND_OPEN_MARGIN = 0.5
    /** An operation's way treats every cell this close to his fighters (not by the target) as a wall. */
    private const val DANGER_RADIUS = 3
    /** A breach is worth it while an escort of his stands out of his ramparts this close to his spawn. */
    private const val BREACH_TARGET_RANGE = 15
    /** How long before our arrival his army is taken to start answering an operation. */
    private const val NOTICE_TICKS = 25
    /** What a cell near his fighter adds to the army's way: forty plain cells of detour are worth avoiding one. */
    private const val DANGER_COST = 40
    /** A running operation is dropped only after its simulation has lost this many ticks in a row. */
    private const val DROP_AFTER = 3
    /** The convoy's period on plain: the pullers bring each train's MOVE to weight / 2. */
    private const val CONVOY_PERIOD = 2
    /** Home fighters before the convoy's pullers are made. */
    private const val CONVOY_MIN_GUARD = 1
    /** His fighter this close to a route or an escort on the way is the guard's business. */
    private const val CONVOY_GUARD_RADIUS = 8
    private const val PULLER_PRIORITY = 90
    private const val YIELD_PRIORITY = 95
    /** A fighter of his that has not changed cell for this many ticks holds a position and is not coming. */
    private const val STILL_TICKS = 30

    private const val FIGHTER_PRIORITY = 30
    private const val HAULER_PRIORITY = 20
    private const val WORKER_PRIORITY = 10

    private const val LOG_EVERY = 50

    private val MELEE = Bodies.body(MOVE to 5, ATTACK to 5)
    private val RANGED = Bodies.body(MOVE to 5, RANGED_ATTACK to 5)
    private val HARVESTER_FIRST = Bodies.body(WORK to 3, MOVE to 1)
    /** The second harvester builds the home works and feeds the tower: it carries. */
    private val HARVESTER_NEXT = Bodies.body(WORK to 2, CARRY to 2, MOVE to 1)
    private val HAULER = Bodies.body(CARRY to 2, MOVE to 1)
    /** The wall breaker: as much ATTACK as one spawn holds with MOVE enough for two ticks a plain cell. */
    private const val BREAKER_ATTACK = 9
    private val BREAKER = Bodies.body(MOVE to 5, ATTACK to BREAKER_ATTACK)
    /** The outpost's pioneer walks ninety cells: a MOVE on every part, WORK to saturate the source, one CARRY to build. */
    private val PIONEER = Bodies.body(WORK to 5, CARRY to 1, MOVE to 6)
    /** The outpost spawn's own worker steps one cell to its slot. */
    private val PIONEER_WORKER = Bodies.body(WORK to 5, CARRY to 1, MOVE to 1)
    private const val PIONEER_TRIES = 3

    // ---------- state between ticks ----------
    private val escortIds = HashSet<String>()
    private val escortHome = HashMap<String, Int>()
    /**
     * One army per base: the fighters born at the home spawn and those born at the outpost's. Each defends its own base
     * and runs its own operations — a threat at home must not pull the outpost's fighters across the map.
     */
    internal class Garrison(val name: String) {
        var mode = "hold"
        var modeTarget: String? = null
        var modeSince = 0
        /** The fighters of the running strike or siege. */
        var members: Set<String> = emptySet()
        /** The tick this base was first defended. */
        var attackedAt = -1
        /** Ticks in a row the running operation's simulation has lost. */
        var failStreak = 0
        val inOp get() = mode == "strike" || mode == "siege"
    }

    private var lastSiegeWhy = ""
    private var lastOpWhy = ""
    private val homeG = Garrison("home")
    private val outpostG = Garrison("outpost")
    /** Which base a fighter belongs to: the spawn it was born at. */
    private val originOf = HashMap<String, String>()
    private var armyMade = 0
    private val flowCache = HashMap<String, IntArray>()
    private val flowTick = HashMap<String, Int>()

    fun idOf(o: GameObject): String = "${o.asDynamic().id}"

    // ==================== the world ====================

    internal class World(
        val now: Int,
        val mySpawn: StructureSpawn?,
        val enemySpawn: StructureSpawn?,
        val mine: List<Creep>,
        val active: List<Creep>,
        val enemies: List<Creep>,
        val escorts: List<Creep>,
        val enemyEscorts: List<Creep>,
        val myFlags: List<Flag>,
        val enemyFlags: List<Flag>,
        val homeSource: Source?,
        val myRamparts: HashSet<Int>,
        val enemyRamparts: HashMap<Int, StructureRampart>,
        val occupant: HashMap<Int, Creep>,
        val enemyAt: HashSet<Int>,
        val blocked: List<Position>,
        val fighters: List<Creep>,
        val harvesters: List<Creep>,
        val haulers: List<Creep>,
        val enemyArmed: List<Creep>,
        val piles: List<Resource>,
        val walls: HashMap<Int, StructureWall>,
        /** Our spawn at the outpost (any spawn of ours but the home one). */
        val outpostSpawn: StructureSpawn?,
        /** Workers of the outpost: WORK enough to saturate a source alone. */
        val pioneers: List<Creep>,
    )

    /** The spawn we started with: every other spawn of ours is the outpost's. */
    private var homeSpawnKey = -1

    fun tick() {
        val w = sense()
        stillCells = w.escorts.map { cell(it.x, it.y) } + getObjectsByPrototype(ConstructionSite::class)
            .filter { it.exists && it.my == true && works?.any { p -> p.first == "tower" && p.second == key(it) } == true }.map { cell(it.x, it.y) }
        if (w.now == 1) probe(w)
        if (w.now in 2..5) printMap((w.now - 2) * 25)
        if (w.now == 1) { chooseBreach(w); planOutpost(w) }
        assignOrigins(w)
        runSpawn(w)
        runOutpostSpawn(w)
        runWorks(w)
        runTowers(w)
        if (!convoyOn) runEscorts(w)
        trackEnemies(w)
        runConvoy(w)
        runEconomy(w)
        runPioneers(w)
        val fighters = w.fighters.filter { !it.spawning }
        val homeF = fighters.filter { originOf[idOf(it)] != "outpost" }
        val outF = fighters.filter { originOf[idOf(it)] == "outpost" }
        decideArmy(w, homeG, homeF, w.mySpawn)
        runArmy(w, homeG, homeF, w.mySpawn, rallyCells(w), true)
        val opBase: Position? = w.outpostSpawn ?: outpost?.let { cellOf(it.spawnCell) }
        decideArmy(w, outpostG, outF, opBase)
        runArmy(w, outpostG, outF, opBase, outpostRally(w), false)
        TrafficManager.resolve(w.active.filter { idOf(it) !in escortIds && idOf(it) !in pinned && Bodies.liveMoves(it) > 0 }, w.active + w.enemies)
        if (w.now % LOG_EVERY == 0 || w.now == 2) logStatus(w)
    }

    private fun sense(): World {
        val now = getTicks()
        val all = getObjects().filter { it.exists }
        val escortObjs = all.filter { protoName(it) == "EscortCreep" }.map { it.unsafeCast<Creep>() }
        if (escortIds.isEmpty()) for (c in escortObjs) escortIds.add(idOf(c))
        val byId = LinkedHashMap<String, Creep>()
        for (c in getObjectsByPrototype(Creep::class)) if (c.exists) byId[idOf(c)] = c
        for (c in escortObjs) byId[idOf(c)] = c
        val creeps = byId.values.toList()
        val mine = creeps.filter { it.my }
        val enemies = creeps.filter { !it.my && !it.spawning }
        val active = mine.filter { !it.spawning }
        val spawns = getObjectsByPrototype(StructureSpawn::class).filter { it.exists }
        if (homeSpawnKey < 0) spawns.firstOrNull { it.my == true }?.let { homeSpawnKey = key(it) }
        val mySpawn = spawns.firstOrNull { it.my == true && key(it) == homeSpawnKey }
        val outpostSpawn = spawns.firstOrNull { it.my == true && key(it) != homeSpawnKey }
        val flags = getObjectsByPrototype(Flag::class).filter { it.exists }
        val sources = getObjectsByPrototype(Source::class).filter { it.exists }
        val ramparts = getObjectsByPrototype(StructureRampart::class).filter { it.exists }
        val myRamparts = ramparts.filter { it.my == true }.mapTo(HashSet()) { key(it) }
        val enemyRamparts = HashMap<Int, StructureRampart>()
        for (r in ramparts) if (r.my != true) enemyRamparts[key(r)] = r
        val walls = getObjectsByPrototype(StructureWall::class).filter { it.exists }
        val towers = getObjectsByPrototype(StructureTower::class).filter { it.exists }
        val blocked: List<Position> = spawns + walls + sources + towers + ramparts.filter { it.my != true }
        DistanceMap.syncStructures(blocked)
        val occupant = HashMap<Int, Creep>()
        for (c in active) occupant[key(c)] = c
        for (c in enemies) occupant[key(c)] = c
        val others = active.filter { idOf(it) !in escortIds }
        return World(
            now = now, mySpawn = mySpawn, enemySpawn = spawns.firstOrNull { it.my == false },
            mine = mine, active = active, enemies = enemies,
            escorts = mine.filter { idOf(it) in escortIds }, enemyEscorts = enemies.filter { idOf(it) in escortIds },
            myFlags = flags.filter { it.my == true }, enemyFlags = flags.filter { it.my == false },
            homeSource = mySpawn?.let { s -> sources.minByOrNull { getRange(it, s) } },
            myRamparts = myRamparts, enemyRamparts = enemyRamparts, occupant = occupant,
            enemyAt = enemies.mapTo(HashSet()) { key(it) }, blocked = blocked,
            fighters = others.filter { Bodies.wasArmed(it) },
            harvesters = mine.filter { idOf(it) !in escortIds && Bodies.isWorker(it) && !isPioneer(it) },
            pioneers = mine.filter { idOf(it) !in escortIds && isPioneer(it) },
            outpostSpawn = outpostSpawn,
            haulers = mine.filter { idOf(it) !in escortIds && Bodies.isHauler(it) },
            enemyArmed = enemies.filter { idOf(it) !in escortIds && Bodies.isArmed(it) },
            piles = getObjectsByPrototype(Resource::class).filter { it.exists && it.resourceType == RESOURCE_ENERGY },
            walls = HashMap<Int, StructureWall>().also { m -> for (x in walls) m[key(x)] = x },
        )
    }

    /** Cells our own creeps must walk round: the escorts standing at home. They never yield (they are not movers), so a
     *  field through them sends a creep into an escort's back for good. */
    private var stillCells: List<Position> = emptyList()

    /** An outpost worker: WORK enough to saturate a source alone (home harvesters are split in two). */
    private fun isPioneer(c: Creep) = c.body.count { it.type == WORK } >= WORK_TARGET

    /** A fighter belongs to the spawn it was born at. */
    private fun assignOrigins(w: World) {
        for (c in w.mine) {
            if (idOf(c) in originOf || idOf(c) in escortIds) continue
            val op = w.outpostSpawn
            originOf[idOf(c)] = if (op != null && getRange(c, op) <= 1) "outpost" else "home"
        }
        if (w.now % 100 == 0) originOf.keys.retainAll(w.mine.mapTo(HashSet()) { idOf(it) })
    }

    /** A flow field for our movers: round the structures and round our standing escorts. */
    private fun flowTo(name: String, target: Position, swampCost: Int): IntArray =
        flowTo(name + ":" + stillCells.joinToString(",") { key(it).toString() }, target, swampCost, stillCells)

    private fun flowTo(name: String, target: Position, swampCost: Int, extra: List<Position>): IntArray {
        val k = "$name:${key(target)}:$swampCost"
        val now = getTicks()
        val cached = flowCache[k]
        if (cached != null && flowTick[k] == now) return cached
        val f = DistanceMap.flowFieldTo(target, extra, swampCost)
        flowCache[k] = f
        flowTick[k] = now
        if (flowCache.size > 64) {
            val stale = flowTick.filter { it.value < now }.keys
            for (s in stale) { flowCache.remove(s); flowTick.remove(s) }
        }
        return f
    }

    private fun onRampart(w: World, c: Position, mine: Boolean) = if (mine) key(c) in w.myRamparts else w.enemyRamparts.containsKey(key(c))

    // ==================== spawn ====================

    private fun energy(w: World) = w.mySpawn?.store?.get(RESOURCE_ENERGY) ?: 0

    private fun runSpawn(w: World) {
        val spawn = w.mySpawn ?: return
        if (spawn.spawning != null) return
        val works = w.harvesters.sumOf { Bodies.live(it, WORK) }
        // his escort already out of his ramparts and nobody of ours to strike it: the first fighter goes before the second
        // half of the economy — Hardy#1 walks all three to the flags from tick 0 and is on them by 568 (6ab9255d)
        val raceOn = w.enemyEscorts.any { !onRampart(w, it, false) } && w.fighters.isEmpty()
        val puller = nextPuller(w)
        var pullerFor: String? = null
        val order: Array<BodyPartType> = when {
            w.harvesters.isEmpty() -> HARVESTER_FIRST
            w.haulers.isEmpty() -> HAULER
            raceOn -> MELEE
            works < WORK_TARGET -> HARVESTER_NEXT
            w.haulers.size < HAULERS -> HAULER
            // then the delivery: the corridor's breaker, then the pullers — the trains through the corridor are on the
            // flags long before a race through the centre could be, and every stronger bot of the field wins late
            // (stachu3478#3 ~950, けろびー ~1700): inside a one-cell pass one melee reaches the wall, so the wall falls at
            // the pace of the strongest one
            (breachLeft(w) || corridorLeft(w)) && w.fighters.isNotEmpty() && w.mine.none { Bodies.live(it, ATTACK) >= BREAKER_ATTACK } -> BREAKER
            // the convoy's pullers once home has its guard: M{need} per escort for the convoy's period
            puller != null -> { pullerFor = puller.second; puller.first }
            // the outpost's pioneer: the second economy
            outpost != null && w.outpostSpawn == null && w.pioneers.isEmpty() && pioneersSent < PIONEER_TRIES &&
                w.fighters.size >= 2 -> PIONEER
            else -> nextFighter(w)
        }
        if (energy(w) < Bodies.cost(order)) return
        val r = spawn.spawnCreep(order)
        if (r.error == null) {
            pullerFor?.let { pullerOrders.addLast(it) }
            if (order.any { it == ATTACK || it == RANGED_ATTACK || it == HEAL }) armyMade++
            if (order.count { it == WORK } >= WORK_TARGET) pioneersSent++
            println("spawn t=${w.now}: ${Bodies.summary(order)} e=${energy(w)} army=$armyMade")
        }
    }

    /** Two melee to one ranged: the melee is three times the damage per energy against anything that stands still (an
     *  escort, a rampart), the ranged is what reaches a kiter. */
    private fun nextFighter(w: World): Array<BodyPartType> {
        val melee = w.fighters.count { Bodies.isMelee(it) }
        val ranged = w.fighters.count { Bodies.isRanged(it) }
        return if (ranged * 2 < melee) RANGED else MELEE
    }

    // ==================== escorts at home ====================

    /**
     * Inner cells of our block: our ramparts none of whose neighbours a creep of his can stand on (every neighbour is our
     * rampart, a structure or a wall) — no melee reaches an escort there, and a ranged shot goes into the rampart.
     * Taken farthest from the home source first, so the haulers' side of the spawn stays free.
     */
    private fun innerCells(w: World): List<Int> {
        val spawn = w.mySpawn ?: return emptyList()
        val structures = w.blocked.mapTo(HashSet()) { key(it) }
        val inner = w.myRamparts.filter { k ->
            val x = k / 100; val y = k % 100
            DIRECTIONS.all { (dx, dy) ->
                val nx = x + dx; val ny = y + dy
                val nk = nx * 100 + ny
                !DistanceMap.inBounds(nx, ny) || DistanceMap.isTerrainWall(nx, ny) || nk in w.myRamparts || nk in structures
            } && k != key(spawn)
        }
        val src = w.homeSource
        return inner.sortedByDescending { k -> if (src == null) 0 else { val dx = k / 100 - src.x; val dy = k % 100 - src.y; dx * dx + dy * dy } }
    }

    private fun runEscorts(w: World) {
        if (escortHome.isEmpty() && w.escorts.isNotEmpty()) {
            // each escort takes the inner cell nearest to it — one step from the start. Sending them to the cells
            // farthest from the source walked three slow escorts across the whole block, and the first harvester stood
            // between them for 550 ticks (Hardy#1, 6ab9255d); the haulers find a free side of the spawn by themselves
            // (dock), and every flow of ours walks round the escorts (stillCells)
            val cells = innerCells(w).toMutableList()
            for (e in w.escorts.sortedByDescending { Bodies.period(it, false) }) {
                val best = cells.minByOrNull { getRange(e, cellOf(it)) } ?: break
                escortHome[idOf(e)] = best
                cells.remove(best)
            }
            println("escorts home: " + w.escorts.joinToString(" ") { "${Bodies.summaryOf(it)}${at(it)}->${escortHome[idOf(it)]?.let { k -> at(cellOf(k)) }}" })
        }
        for (e in w.escorts) {
            val home = escortHome[idOf(e)] ?: continue
            if (key(e) == home || e.fatigue > 0) continue
            val f = flowTo("escort", cellOf(home), Bodies.swampCost(e), emptyList())
            val step = DistanceMap.flowStep(f, e.x, e.y, 0, w.occupant.keys, w.enemyAt) ?: continue
            if (w.occupant.containsKey(key(step))) continue
            e.move(getDirection(step.x - e.x, step.y - e.y))
        }
    }

    // ==================== economy ====================

    private val spotOf = HashMap<String, Int>()

    private fun harvestSpots(w: World): List<Int> {
        val src = w.homeSource ?: return emptyList()
        val spawn = w.mySpawn ?: return emptyList()
        return DIRECTIONS.map { (dx, dy) -> (src.x + dx) * 100 + (src.y + dy) }
            .filter { k -> DistanceMap.inBounds(k / 100, k % 100) && !DistanceMap.isWall(k / 100, k % 100) }
            .sortedBy { getRange(cellOf(it), spawn) }
    }

    private fun runEconomy(w: World) {
        val src = w.homeSource ?: return
        val spawn = w.mySpawn ?: return
        val spots = harvestSpots(w)
        spotOf.keys.retainAll(w.harvesters.mapTo(HashSet()) { idOf(it) })
        for (h in w.harvesters) {
            if (h.spawning) continue
            val spot = spotOf[idOf(h)] ?: spots.firstOrNull { s -> spotOf.values.none { it == s } }?.also { spotOf[idOf(h)] = it } ?: continue
            if (key(h) != spot) {
                val f = flowTo("spot", cellOf(spot), Bodies.swampCost(h))
                DistanceMap.flowStep(f, h.x, h.y, 0, w.occupant.keys, w.enemyAt)?.let { TrafficManager.request(h, it, WORKER_PRIORITY) }
            }
            if (Bodies.live(h, CARRY) > 0 && workAndFeed(w, h, src)) continue
            if (getRange(h, src) <= 1) h.harvest(src)
        }
        for (c in w.haulers) {
            if (c.spawning) continue
            val carried = c.store[RESOURCE_ENERGY] ?: 0
            val free = c.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0
            // pick up whatever lies within reach, every tick
            w.piles.firstOrNull { getRange(it, c) <= 1 && free > 0 }?.let { c.pickup(it) }
            var trace: String
            if (free > 0 && carried < 50 * Bodies.live(c, CARRY) / 2 + 1 || carried == 0) {
                val pile = w.piles.filter { getRange(it, src) <= 2 }.maxByOrNull { it.amount }
                val goal: Position = pile ?: spots.firstOrNull()?.let { cellOf(it) } ?: continue
                trace = "load->${at(goal)}${pile?.let { "p${it.amount}" } ?: ""}"
                if (getRange(c, goal) > 1) {
                    val f = flowTo("pile", goal, Bodies.swampCost(c))
                    val st = DistanceMap.flowStep(f, c.x, c.y, 1, w.occupant.keys, w.enemyAt)
                    trace += " f=${f[key(c)]} step=${st?.let { at(it) }}"
                    st?.let { TrafficManager.request(c, it, HAULER_PRIORITY) }
                }
            } else {
                trace = "unload"
                if (getRange(c, spawn) <= 1) trace += " rc=${c.transfer(spawn, RESOURCE_ENERGY)}"
                else trace += " dock=" + dock(w, c, spawn)
            }
            if (w.now % LOG_EVERY == 0) println("haul t=${w.now} ${idOf(c)}${at(c)} c=$carried free=$free fat=${c.fatigue} $trace")
        }
    }

    /**
     * To a free cell beside the spawn, walking round our escorts: every cell beside the spawn is an inner cell, the
     * escorts hold three of them for the whole match, and a field that only knows the spawn sends the hauler into the
     * back of an escort (it then asks for that cell forever — the escort is not a mover and never yields).
     */
    private fun dock(w: World, c: Creep, spawn: StructureSpawn): String {
        val still = w.escorts.map { cell(it.x, it.y) }
        val free = DIRECTIONS.map { (dx, dy) -> cell(spawn.x + dx, spawn.y + dy) }
            .filter { !DistanceMap.isWall(it.x, it.y) && (w.occupant[key(it)]?.let { o -> idOf(o) == idOf(c) || idOf(o) !in escortIds } ?: true) }
        if (free.isEmpty()) return "nofree"
        val k = "dock:" + free.joinToString(",") { key(it).toString() } + ":" + Bodies.swampCost(c)
        val now = getTicks()
        val f = flowCache[k]?.takeIf { flowTick[k] == now } ?: DistanceMap.flowFieldToAny(free, still, Bodies.swampCost(c)).also { flowCache[k] = it; flowTick[k] = now }
        val st = DistanceMap.flowStep(f, c.x, c.y, 0, w.occupant.keys, w.enemyAt)
        st?.let { TrafficManager.request(c, it, HAULER_PRIORITY) }
        return "${free.size} f=${f[key(c)]} step=${st?.let { at(it) }}"
    }

    // ==================== home works: ramparts over the economy, a tower fed by the harvester ====================

    /** What to build at home, in order: (kind, cell). */
    private var works: List<Pair<String, Int>>? = null
    /** The tick home was first defended — raids unlock the tower before its fighter count. */
    private val attackedAt get() = homeG.attackedAt

    /**
     * Computed once the harvest spots are known. けろびー#11 raided our source three times with his T3M8R5 and healers
     * (568, 900, 1650 — 6ab92ec4) and killed every harvester and hauler each time: the economy stood in the open seven
     * cells from the spawn. So, as Spawn and Swamp advanced learned against the same raiders: a rampart over each
     * harvest spot (200 energy for 10 000 hits of shield — a creep on its own rampart takes no damage), a tower beside the
     * second spot so the harvester there builds and feeds it with no hauler, a rampart over the tower, and ramparts
     * along the haulers' way from the first spot to our block.
     */
    private fun planWorks(w: World) {
        if (works != null) return
        val spawn = w.mySpawn ?: return
        val src = w.homeSource ?: return
        val spots = harvestSpots(w).take(2)
        if (spots.size < 2) { works = emptyList(); return }
        fun free(k: Int) = DistanceMap.inBounds(k / 100, k % 100) && !DistanceMap.isWall(k / 100, k % 100) &&
            k !in w.myRamparts && k !in spots && k != key(src)
        val tower = DIRECTIONS.map { (dx, dy) -> (spots[1] / 100 + dx) * 100 + (spots[1] % 100 + dy) }.filter { free(it) }
            .sortedWith(compareBy<Int>({ -spots.count { s -> cheb(s, it) <= 1 } }, { getRange(cellOf(it), spawn) },
                { if (DistanceMap.isSwamp(it / 100, it % 100)) 1 else 0 })).firstOrNull()
        val f = DistanceMap.flowFieldTo(spawn, stillCells + listOfNotNull(tower?.let { cellOf(it) }), 1)
        // only what the builder on the second spot reaches (range 3): a site out of its reach would hold the one-site
        // queue for good
        val route = descend(f, spots[0]).filter { it !in w.myRamparts && it !in spots && it != key(spawn) && it != tower && cheb(it, spots[1]) <= 3 }
        val list = ArrayList<Pair<String, Int>>()
        for (s in spots) list.add("rampart" to s)
        if (tower != null) { list.add("tower" to tower); list.add("rampart" to tower) }
        for (r in route) list.add("rampart" to r)
        works = list
        println("works: " + list.joinToString(" ") { "${it.first}${at(cellOf(it.second))}" })
    }

    /** How many fighters must stand before each kind is built, unless home has been attacked. */
    private fun worksGate(kind: String, index: Int, w: World): Boolean {
        val fighters = w.fighters.size
        val attacked = attackedAt >= 0
        return when {
            kind == "tower" -> fighters >= 4 || attacked
            index < 2 -> fighters >= 1 || attacked
            else -> fighters >= 3 || attacked
        }
    }

    private fun builtAt(k: Int, kind: String): Boolean = when (kind) {
        "tower" -> getObjectsByPrototype(StructureTower::class).any { it.exists && it.my == true && key(it) == k }
        else -> getObjectsByPrototype(StructureRampart::class).any { it.exists && it.my == true && key(it) == k }
    }

    /** One site at a time: the first unbuilt work whose gate is open. */
    private fun runWorks(w: World) {
        planWorks(w)
        val list = works ?: return
        val sites = getObjectsByPrototype(ConstructionSite::class).filter { it.exists && it.my == true }
        if (sites.isNotEmpty()) return
        for ((i, pair) in list.withIndex()) {
            val (kind, k) = pair
            if (builtAt(k, kind)) continue
            if (!worksGate(kind, i, w)) return
            val r = if (kind == "tower") createConstructionSite(k / 100, k % 100, StructureTower::class.js)
                else createConstructionSite(k / 100, k % 100, StructureRampart::class.js)
            println("works t=${w.now}: $kind site ${at(cellOf(k))} err=${r.error}")
            return
        }
    }

    /**
     * A harvester with CARRY: the tower beside it eats first (one shot is 10 energy, and without it home has no guard),
     * then a site within reach is built from its store, topped up from a pile beside it, and otherwise it harvests.
     * Harvest and build are one action a tick. A tower site under a creep does not build (Spawn and Swamp advanced v6 lost
     * 400 ticks so), so our movers treat it as a wall (stillCells).
     */
    private fun workAndFeed(w: World, h: Creep, src: Source): Boolean {
        val carried = h.store[RESOURCE_ENERGY] ?: 0
        val tower = getObjectsByPrototype(StructureTower::class).firstOrNull {
            it.exists && it.my == true && getRange(it, h) <= 1 && (it.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0
        }
        if (tower != null && carried > 0) {
            h.transfer(tower, RESOURCE_ENERGY)
            if (getRange(h, src) <= 1) h.harvest(src)
            return true
        }
        val site = getObjectsByPrototype(ConstructionSite::class).filter { it.exists && it.my == true && getRange(it, h) <= 3 }
            .firstOrNull { s -> w.occupant[key(s)] == null || isRampartSite(s) } ?: return false
        if (carried >= BUILD_POWER * Bodies.live(h, WORK)) { h.build(site); return true }
        val pile = w.piles.filter { getRange(it, h) <= 1 }.maxByOrNull { it.amount }
        if (pile != null && (h.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0) h.pickup(pile)
        if (carried > 0 && pile == null && src.energy == 0) { h.build(site); return true }
        if (getRange(h, src) <= 1) h.harvest(src)
        return true
    }

    private fun isRampartSite(s: ConstructionSite): Boolean {
        val list = works ?: return false
        return list.any { it.first == "rampart" && it.second == key(s) } && list.none { it.first == "tower" && it.second == key(s) && !builtAt(key(s), "tower") }
    }

    /** Towers fire at the nearest armed enemy in range (the damage falls 50 a cell), the weakest of equals; else heal. */
    private fun runTowers(w: World) {
        for (tw in getObjectsByPrototype(StructureTower::class).filter { it.exists && it.my == true }) {
            if ((tw.store[RESOURCE_ENERGY] ?: 0) < TOWER_ENERGY_COST || tw.cooldown > 0) continue
            val foe = w.enemies.filter { getRange(tw, it) <= TOWER_RANGE }
                .sortedWith(compareBy<Creep>({ if (Bodies.isArmed(it) || idOf(it) in escortIds) 0 else 1 }, { getRange(tw, it) }, { it.hits })).firstOrNull()
            if (foe != null) { tw.attack(foe); continue }
            val hurt = w.active.filter { it.hits < it.hitsMax && getRange(tw, it) <= TOWER_RANGE }.maxByOrNull { it.hitsMax - it.hits }
            if (hurt != null) tw.heal(hurt)
        }
    }

    // ==================== the outpost: a second spawn by the far source ====================

    /** The outpost's plan: the source it lives on, the spawn's cell two from it, and the cells beside both (a harvester
     *  there harvests and hands the energy to the spawn in the same tick). */
    private class Outpost(val source: Int, val spawnCell: Int, val slots: List<Int>)
    private var outpost: Outpost? = null
    private var pioneersSent = 0

    /**
     * Planned at tick one. A second spawn is a second economy: every side has the same income — one source regenerates 10
     * a tick, the spawn 1 — and the far edge holds two more sources nobody lives on. stachu3478 built his spawn by his flag
     * (94,26) at 994 with a tower under a rampart (6ab92e1f). Ours goes by the far source nearest to our flags: its
     * fighters stand where both sides' escorts end their way (the flags of the two sides are joined along the right
     * edge, round the centre), and the harvester on it adds ten a tick. The spawn's cell is taken as in Spawn and Swamp
     * advanced: two from the source, with the most cells beside both, not swamp, the nearer to our flags.
     */
    private fun planOutpost(w: World) {
        val my = w.mySpawn ?: return
        val his = w.enemySpawn
        val flags = w.myFlags
        if (flags.isEmpty()) return
        val sources = getObjectsByPrototype(Source::class).filter { s ->
            s.exists && getRange(s, my) > 20 && (his == null || getRange(s, his) > 20)
        }
        val src = sources.minByOrNull { s -> flags.minOf { getRange(it, s) } } ?: return
        var best: Outpost? = null
        var bestScore = Int.MIN_VALUE
        for (dx in -2..2) for (dy in -2..2) {
            val c = (src.x + dx) * 100 + (src.y + dy)
            if (cheb(c, key(src)) != 2 || !DistanceMap.inBounds(c / 100, c % 100) || DistanceMap.isWall(c / 100, c % 100)) continue
            if (flags.any { key(it) == c }) continue
            val slots = ArrayList<Int>()
            var exits = 0
            for ((ax, ay) in DIRECTIONS) {
                val a = (c / 100 + ax) * 100 + (c % 100 + ay)
                if (!DistanceMap.inBounds(a / 100, a % 100) || DistanceMap.isWall(a / 100, a % 100) || flags.any { key(it) == a }) continue
                if (cheb(a, key(src)) == 1) slots.add(a) else exits++
            }
            if (slots.isEmpty() || exits == 0) continue
            val score = slots.size * 1000 + (if (DistanceMap.isSwamp(c / 100, c % 100)) 0 else 100) + minOf(exits, 3) * 10 -
                flags.minOf { getRange(it, cellOf(c)) }
            if (score > bestScore) { bestScore = score; best = Outpost(key(src), c, slots) }
        }
        outpost = best
        println("outpost: " + (best?.let { "source ${at(cellOf(it.source))} spawn ${at(cellOf(it.spawnCell))} slots ${it.slots.joinToString(" ") { s -> at(cellOf(s)) }}" } ?: "none"))
    }

    private fun rampartAt(k: Int) = getObjectsByPrototype(StructureRampart::class).any { it.exists && it.my == true && key(it) == k }

    /**
     * A pioneer (W5C1M6) walks to the outpost's slot — round his fighters, whose cells within four are walls to it —
     * and, standing there, builds a rampart under itself, a rampart on the spawn's cell and the spawn under that rampart
     * (Spawn and Swamp advanced: a bare spawn and its builder were razed by stachu3478 before the order changed), harvesting
     * between batches. Once the spawn stands it harvests and hands everything to it every tick.
     */
    private fun runPioneers(w: World) {
        val op = outpost ?: return
        val src = getObjectsByPrototype(Source::class).firstOrNull { it.exists && key(it) == op.source } ?: return
        val taken = HashSet<Int>()
        for (p in w.pioneers.filter { !it.spawning }.sortedBy { getRange(it, src) }) {
            val slot = op.slots.filter { it !in taken }.minByOrNull { getRange(cellOf(it), p) } ?: continue
            taken.add(slot)
            if (key(p) != slot) {
                val danger = w.enemyArmed.flatMap { e -> (-4..4).flatMap { dx -> (-4..4).map { dy -> cell(e.x + dx, e.y + dy) } } }
                    .filter { DistanceMap.inBounds(it.x, it.y) && getRange(it, cellOf(slot)) > 2 }
                val f = if (danger.isEmpty()) flowTo("pioneer", cellOf(slot), Bodies.swampCost(p))
                    else DistanceMap.flowFieldTo(cellOf(slot), danger + stillCells, Bodies.swampCost(p))
                val st = DistanceMap.flowStep(f, p.x, p.y, 0, w.occupant.keys, w.enemyAt)
                val home = w.mySpawn
                if (st != null) TrafficManager.request(p, st, WORKER_PRIORITY + 5)
                else if (home != null && getRange(p, home) <= 1) {
                    // no safe way yet: wait off the spawn's cells, or it cannot put a creep out
                    DIRECTIONS.map { (dx, dy) -> cell(p.x + dx, p.y + dy) }
                        .firstOrNull { c -> getRange(c, home) >= 2 && !DistanceMap.isWall(c.x, c.y) && !w.occupant.containsKey(key(c)) }
                        ?.let { TrafficManager.request(p, it, WORKER_PRIORITY + 5) }
                }
                if (getRange(p, src) > 1) continue
            }
            val e = p.store[RESOURCE_ENERGY] ?: 0
            val spawn = w.outpostSpawn
            if (spawn != null) {
                if (getRange(p, src) <= 1) p.harvest(src)
                if (e > 0 && getRange(p, spawn) <= 1) p.transfer(spawn, RESOURCE_ENERGY)
                continue
            }
            val next: Pair<Int, String> = when {
                key(p) in op.slots && !rampartAt(key(p)) -> key(p) to "rampart"
                !rampartAt(op.spawnCell) -> op.spawnCell to "rampart"
                else -> op.spawnCell to "spawn"
            }
            val sites = getObjectsByPrototype(ConstructionSite::class).filter { it.exists && it.my == true }
            var site = sites.firstOrNull { key(it) == next.first }
            if (site == null && sites.none { getRange(it, p) <= 3 && key(it) != next.first }) {
                val r = if (next.second == "spawn") createConstructionSite(next.first / 100, next.first % 100, StructureSpawn::class.js)
                    else createConstructionSite(next.first / 100, next.first % 100, StructureRampart::class.js)
                println("outpost t=${w.now}: ${next.second} site ${at(cellOf(next.first))} err=${r.error}")
                site = r.`object`
            }
            if (site == null) site = sites.filter { getRange(it, p) <= 3 }.minByOrNull { getRange(it, p) }
            if (site != null && (e >= Bodies.live(p, CARRY) * 50 || (src.energy == 0 && e > 0))) p.build(site)
            else if (getRange(p, src) <= 1) p.harvest(src)
        }
    }

    /** The outpost's spawn: a harvester for its slot when none stands there, then fighters of the outpost's garrison. */
    private fun runOutpostSpawn(w: World) {
        val spawn = w.outpostSpawn ?: return
        if (spawn.spawning != null) return
        val order = if (w.pioneers.none { !it.spawning && getRange(it, spawn) <= 3 }) PIONEER_WORKER else nextFighter(w)
        if ((spawn.store[RESOURCE_ENERGY] ?: 0) < Bodies.cost(order)) return
        val r = spawn.spawnCreep(order)
        if (r.error == null) println("outpost spawn t=${w.now}: ${Bodies.summary(order)}")
    }

    /** Where the outpost's idle fighters stand: round its spawn, the side facing his flags first. */
    private fun outpostRally(w: World): List<Int> {
        val op = outpost ?: return emptyList()
        val his = w.enemyFlags
        val out = ArrayList<Int>()
        for (dx in -3..3) for (dy in -3..3) {
            val k = (op.spawnCell / 100 + dx) * 100 + (op.spawnCell % 100 + dy)
            if (cheb(k, op.spawnCell) < 2 || k in op.slots || k == op.source) continue
            if (!DistanceMap.inBounds(k / 100, k % 100) || DistanceMap.isWall(k / 100, k % 100)) continue
            if (w.myFlags.any { key(it) == k }) continue
            out.add(k)
        }
        return out.sortedBy { k -> if (his.isEmpty()) 0 else his.minOf { getRange(it, cellOf(k)) } }
    }

    // ==================== the convoy: all three escorts to the flags ====================

    /** Escort id -> the flag cell it goes to (fixed when the convoy is planned). */
    private var convoyFlags: Map<String, Int>? = null
    /** Puller id -> the escort it pulls; escorts waiting for a puller being born, in spawn order. */
    private val pullerOf = HashMap<String, String>()
    private val pullerOrders = ArrayDeque<String>()
    private val lastChains = HashMap<String, List<String>>()
    private var convoyOn = false
    private var convoyAt = -1
    /** Creeps moved by direct intents this tick (the trains) — kept out of the traffic manager. */
    private val pinned = HashSet<String>()

    private fun isPuller(c: Creep) = Bodies.isPureMove(c) && c.body.size >= 3

    /** Pullers are told apart from scouts by size; each is tied to the escort it was ordered for when first seen. */
    private fun assignPullers(w: World) {
        for (c in w.mine) {
            if (!isPuller(c) || idOf(c) in pullerOf) continue
            val e = pullerOrders.removeFirstOrNull() ?: w.escorts.firstOrNull()?.let { idOf(it) } ?: continue
            pullerOf[idOf(c)] = e
        }
        if (w.now % 100 == 0) pullerOf.keys.retainAll(w.mine.mapTo(HashSet()) { idOf(it) })
    }

    private fun pullersFor(w: World, e: Creep) = w.mine.filter { pullerOf[idOf(it)] == idOf(e) }

    /** MOVE the pullers must add to escort `e` for the convoy's period. */
    private fun pullNeed(e: Creep) = Trains.movesFor(Bodies.weight(e), Bodies.liveMoves(e), CONVOY_PERIOD)

    /** The route field of a flag: plain 1, swamp 5 (a pulled train's swamp period is five times its plain one), and the
     *  danger price round his fighters. */
    private fun routeFlow(w: World, flag: Int): IntArray {
        val k = "route:$flag"
        val now = getTicks()
        flowCache[k]?.takeIf { flowTick[k] == now }?.let { return it }
        val cost = IntArray(10000)
        for (e in w.enemyArmed) for (dx in -DANGER_RADIUS..DANGER_RADIUS) for (dy in -DANGER_RADIUS..DANGER_RADIUS) {
            val x = e.x + dx; val y = e.y + dy
            if (DistanceMap.inBounds(x, y)) cost[x * 100 + y] = DANGER_COST
        }
        // no still cells here: the escorts ARE the ones walking (v11's first build blocked their own cells and the
        // trains stood at home with the start given)
        val f = DistanceMap.flowFieldCost(cellOf(flag), cost, ARMY_SWAMP_COST, emptyList())
        flowCache[k] = f
        flowTick[k] = now
        return f
    }

    /**
     * Which escort goes to which flag: the assignment whose slowest arrival is soonest (all trains share the convoy's
     * period, so the longest route decides), ties by the total. Six permutations.
     */
    private fun planConvoy(w: World): Map<String, Int>? {
        val es = w.escorts
        val flags = w.myFlags.map { key(it) }
        if (es.size != 3 || flags.size < 3) return null
        val d = es.map { e -> flags.map { f -> pathCells(w, e, cellOf(f)).size.takeIf { it > 0 } ?: 9999 } }
        var best: List<Int>? = null
        var bestMax = Int.MAX_VALUE; var bestSum = Int.MAX_VALUE
        for (a in 0..2) for (b in 0..2) for (c in 0..2) {
            if (a == b || b == c || a == c) continue
            val ds = listOf(d[0][a], d[1][b], d[2][c])
            val mx = ds.max(); val sm = ds.sum()
            if (mx < bestMax || (mx == bestMax && sm < bestSum)) { bestMax = mx; bestSum = sm; best = listOf(a, b, c) }
        }
        val pick = best ?: return null
        return es.indices.associate { i -> idOf(es[i]) to flags[pick[i]] }
    }

    /** The convoy is wanted once home has a guard and nothing threatens it. */
    private fun convoyWanted(w: World): Boolean =
        homeG.mode != "defend" && w.fighters.count { originOf[idOf(it)] != "outpost" } >= CONVOY_MIN_GUARD

    /** The next puller the home spawn should make, or null: M{need} for the escort shortest of its MOVE. The order is
     *  queued by the caller only when the spawn takes it (the puller is tied to its escort when first seen). */
    private fun nextPuller(w: World): Pair<Array<BodyPartType>, String>? {
        if (convoyOn || !convoyWanted(w)) return null
        for (e in w.escorts.sortedByDescending { pullNeed(it) }) {
            val short = pullNeed(e) - pullersFor(w, e).sumOf { it.body.size }
            if (short <= 0) continue
            return Bodies.body(MOVE to minOf(short, SPAWN_ENERGY_CAPACITY / Bodies.cost(MOVE))) to idOf(e)
        }
        return null
    }

    /** Where and when each of his creeps last changed cell. */
    private val enemyMoved = HashMap<String, Pair<Int, Int>>()

    private fun trackEnemies(w: World) {
        for (e in w.enemies) {
            val was = enemyMoved[idOf(e)]
            if (was == null || was.first != key(e)) enemyMoved[idOf(e)] = key(e) to w.now
        }
        if (w.now % 100 == 0) enemyMoved.keys.retainAll(w.enemies.mapTo(HashSet()) { idOf(it) })
    }

    private fun mobile(w: World, e: Creep) = (enemyMoved[idOf(e)]?.second ?: 0) >= w.now - STILL_TICKS

    /**
     * The fight the guard takes on by setting out: everything of his near any of the routes, and every fighter of his
     * that is on the move anywhere — a hunter or a siege army can meet a train that walks two ticks a cell anywhere on
     * its way. A blob that holds its cell is not coming: the danger-priced route goes round it (stand, blob+harvest: the
     * convoy through the centre, round a blob of M5R5, was on the flags at 883). v11's first build counted only what
     * stood near the routes at the start and met the stand's siege army halfway (hunt/siege+harvest, three escorts lost).
     */
    private fun convoyThreats(w: World, plan: Map<String, Int>): List<Creep> {
        val cells = HashSet<Int>()
        for (e in w.escorts) { val f = plan[idOf(e)] ?: continue; cells.addAll(pathCells(w, e, cellOf(f))) }
        return w.enemyArmed.filter { en -> mobile(w, en) || cells.any { k -> maxOf(kotlin.math.abs(k / 100 - en.x), kotlin.math.abs(k % 100 - en.y)) <= CONVOY_GUARD_RADIUS } }
    }

    /**
     * Before the start the pullers stand beside their escort; the convoy starts when every escort has its MOVE and the
     * home guard's simulation against everything near the routes is ours. Then each escort heads its train down its
     * route (the danger-priced field to its flag) and stops on the flag.
     */
    private fun runConvoy(w: World) {
        pinned.clear()
        assignPullers(w)
        if (w.escorts.size != 3) return
        val plan = convoyFlags ?: planConvoy(w)?.also { convoyFlags = it; println("convoy: plan " + w.escorts.joinToString(" ") { e -> "${Bodies.summaryOf(e)}->${it[idOf(e)]?.let { f -> at(cellOf(f)) }}" }) } ?: return
        val ready = w.escorts.all { e -> Trains.chainOf(e, pullersFor(w, e), lastChains[idOf(e)] ?: emptyList()) { idOf(it) }.sumOf { it.body.size } >= pullNeed(e) }
        if (!convoyOn && ready) {
            val guard = w.fighters.filter { originOf[idOf(it)] != "outpost" && !it.spawning }
            val sim = Bodies.fight(units(guard), units(convoyThreats(w, plan)))
            if (sim.weWin && sim.margin() >= STRIKE_MARGIN) {
                convoyOn = true; convoyAt = w.now
                println("convoy t=${w.now}: START guard=${guard.size} sim=$sim")
            } else if (w.now % LOG_EVERY == 0) println("convoy t=${w.now}: ready, waiting — sim=$sim")
        }
        for (e in w.escorts) {
            val flag = plan[idOf(e)] ?: continue
            val mine = pullersFor(w, e)
            val chain = Trains.chainOf(e, mine, lastChains[idOf(e)] ?: emptyList()) { idOf(it) }
            lastChains[idOf(e)] = chain.map { idOf(it) }
            // pullers not in the chain walk to its tail
            val tail: Position = chain.lastOrNull() ?: e
            for (p in mine) {
                if (p.spawning || p in chain || getRange(p, tail) <= 1) continue
                val f = flowTo("tail", tail, 1)
                DistanceMap.flowStep(f, p.x, p.y, 1, w.occupant.keys, w.enemyAt)?.let { TrafficManager.request(p, it, PULLER_PRIORITY) }
            }
            if (!convoyOn) continue
            pinned.add(idOf(e)); for (p in chain) pinned.add(idOf(p))
            if (key(e) == flag) { Trains.step(e, chain, null); continue }
            val f = routeFlow(w, flag)
            val here = f[key(e)]
            // the next cell: the one nearest the flag that is free, holds our own first puller (a swap), or holds one of
            // ours that is not in any train (it will step aside); another train's creep or his creep is waited out
            val trainIds = HashSet<String>().apply { addAll(escortIds); addAll(pullerOf.keys) }
            var next: Position? = null
            var bestD = here
            for ((dx, dy) in DIRECTIONS) {
                val x = e.x + dx; val y = e.y + dy
                if (!DistanceMap.inBounds(x, y)) continue
                val d = f[x * 100 + y]
                if (d < 0 || d >= bestD) continue
                val occ = w.occupant[x * 100 + y]
                val ok = occ == null || (chain.isNotEmpty() && occ === chain[0]) || (occ.my && idOf(occ) !in trainIds)
                if (!ok) continue
                bestD = d; next = cell(x, y)
            }
            // one of ours (not of a train) stands on the next cell: it steps aside this tick, the train goes next tick
            val occ = next?.let { w.occupant[key(it)] }
            if (occ != null && occ.my && idOf(occ) !in trainIds) {
                DIRECTIONS.map { (dx, dy) -> cell(occ.x + dx, occ.y + dy) }
                    .firstOrNull { c -> !DistanceMap.isWall(c.x, c.y) && !w.occupant.containsKey(key(c)) && key(c) != key(e) }
                    ?.let { TrafficManager.request(occ, it, YIELD_PRIORITY) }
                next = null
            }
            val stepped = Trains.step(e, chain, next)
            if (w.now % LOG_EVERY == 0 || (stepped && next != null && key(next) == flag))
                println("convoy t=${w.now}: ${Bodies.summaryOf(e)}${at(e)} f${e.fatigue} chain=${chain.sumOf { it.body.size }}M -> ${at(cellOf(flag))} left=$here")
        }
    }

    // ==================== the breach ====================

    /** The wall cells to break, in the order our path meets them (null — nothing worth breaking). */
    private var breachPath: List<Int>? = null
    private var breachWalls: Set<Int> = emptySet()
    private var breachOpenedAt = -1

    private fun cheb(a: Int, b: Int) = maxOf(kotlin.math.abs(a / 100 - b / 100), kotlin.math.abs(a % 100 - b % 100))

    /** 8-connected groups of wall structures: each group is one "pass" of the map. */
    private fun wallComponents(walls: Collection<Int>): List<Set<Int>> {
        val left = walls.toHashSet()
        val out = ArrayList<Set<Int>>()
        while (left.isNotEmpty()) {
            val seed = left.first()
            val comp = HashSet<Int>()
            val stack = ArrayDeque<Int>().apply { add(seed) }
            left.remove(seed)
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                comp.add(c)
                for ((dx, dy) in DIRECTIONS) {
                    val n = (c / 100 + dx) * 100 + (c % 100 + dy)
                    if (left.remove(n)) stack.add(n)
                }
            }
            out.add(comp)
        }
        return out
    }

    /** The cell beside `p` a flow field reaches first — a spawn's own cell is a structure and has no distance. */
    private fun startNear(f: IntArray, p: Position): Int? =
        DIRECTIONS.map { (dx, dy) -> (p.x + dx) * 100 + (p.y + dy) }
            .filter { DistanceMap.inBounds(it / 100, it % 100) && f[it] >= 0 }.minByOrNull { f[it] }

    private fun descend(f: IntArray, from: Int): List<Int> {
        val out = ArrayList<Int>()
        var c = from
        for (i in 0 until 400) {
            out.add(c)
            if (f[c] <= 0) break
            var best = -1; var bestD = f[c]
            for ((dx, dy) in DIRECTIONS) {
                val nx = c / 100 + dx; val ny = c % 100 + dy
                if (!DistanceMap.inBounds(nx, ny)) continue
                val d = f[nx * 100 + ny]
                if (d in 0 until bestD) { bestD = d; best = nx * 100 + ny }
            }
            if (best < 0) break
            c = best
        }
        return out
    }

    /**
     * Which wall to break, computed from the map: the group of walls whose removal opens a way from our spawn to his that
     * does NOT run along today's way (through the centre). A second road to his base is what beats a player who holds
     * the centre — けろびー#11 sat there with five T3M8R5, three healers and two towers while his escorts waited outside
     * his ramparts, forty cells from his army (6ab9267a). On this layout it is the left-edge pass (x=6, ten walls of
     * 5000); the corridors to the flags do not change the way between the bases at all and are never picked.
     */
    private fun chooseBreach(w: World) {
        val my = w.mySpawn ?: return
        val his = w.enemySpawn ?: return
        // his spawn stands inside his block of ramparts, which we cannot walk: the way is measured to it as if his
        // ramparts were open (the question is how to reach the block, not how to enter it) — without that no field
        // leaves his spawn and v5's first build found "no way" for every wall group
        val hisBlock = w.enemyRamparts.keys
        val base = DistanceMap.flowFieldOpen(his, hisBlock, ARMY_SWAMP_COST)
        val s0 = startNear(base, my)
        val basePath = s0?.let { descend(base, it) } ?: emptyList()
        val len0 = s0?.let { base[it] } ?: Int.MAX_VALUE
        var best: Triple<Set<Int>, List<Int>, Int>? = null
        val comps = wallComponents(w.walls.keys)
        println("breach: ${comps.size} wall groups, way between the spawns $len0 (${basePath.size} cells)")
        for (comp in comps) {
            val f = DistanceMap.flowFieldOpen(his, comp + hisBlock, ARMY_SWAMP_COST)
            val s = startNear(f, my)
            if (s == null) { println("breach? ${comp.size} walls from ${at(cellOf(comp.first()))}: no way"); continue }
            val path = descend(f, s)
            if (path.none { it in comp }) { println("breach? ${comp.size} walls from ${at(cellOf(comp.first()))}: way ${f[s]} does not use it"); continue }
            val far = path.filter { getRange(cellOf(it), my) > 12 && getRange(cellOf(it), his) > 12 }
            if (far.isEmpty()) continue
            val overlap = far.count { c -> basePath.any { cheb(c, it) <= 4 } }.toDouble() / far.size
            println("breach? ${comp.size} walls from ${at(cellOf(comp.first()))}: way ${f[s]} vs $len0, overlap ${(overlap * 100).toInt()}%")
            if (overlap > 0.35 || f[s] > len0 * 13 / 10) continue
            if (best == null || f[s] < best.third) best = Triple(comp, path, f[s])
        }
        best?.let { (comp, path, len) ->
            breachWalls = comp
            breachPath = path.filter { it in comp }
            println("breach: ${comp.size} walls, first ${breachPath?.firstOrNull()?.let { at(cellOf(it)) }}, way $len vs $len0")
        }
        // the corridor: the wall group whose removal shortens our escorts' way to one of our flags the most — a way to
        // the flags that does not cross the centre, where けろびー holds a blob with two towers and stachu3478#3 a swarm
        val start = startNear(DistanceMap.flowFieldTo(my, emptyList(), ARMY_SWAMP_COST), my) ?: return
        var bestGain = 0
        for (comp in comps) {
            if (comp == breachWalls) continue
            for (flag in w.myFlags) {
                val closed = DistanceMap.flowFieldTo(flag, emptyList(), ARMY_SWAMP_COST)[start]
                val f = DistanceMap.flowFieldOpen(flag, comp, ARMY_SWAMP_COST)
                val open = f[start]
                if (closed < 0 || open < 0) continue
                val gain = closed - open
                if (gain > bestGain && gain * 5 >= closed) {
                    bestGain = gain
                    corridorPath = descend(f, start).filter { it in comp }
                }
            }
        }
        corridorPath?.let { println("corridor: ${it.size} walls, first ${it.firstOrNull()?.let { k -> at(cellOf(k)) }}, a flag's way shorter by $bestGain") }
    }

    /** The corridor's walls in the order our way meets them (null — no corridor worth breaking). */
    private var corridorPath: List<Int>? = null

    /** The corridor is broken while it stands and the convoy has not started (a breach gains nothing once they walk). */
    private fun corridorLeft(w: World): Boolean = !convoyOn && corridorPath?.any { w.walls.containsKey(it) } == true

    /**
     * A breach is worth its walls only while there is something behind it to strike: an escort of his standing out of
     * his ramparts by his base. けろびー#11 kept two escorts outside his block for 1200 ticks (6ab92dd5) — the pass was
     * the way to them; stachu3478#1 kept his on ramparts and it was HIS siege that walked through the pass we had opened,
     * 22 ticks after it fell (6ab92e1f).
     */
    private fun breachLeft(w: World): Boolean {
        if (breachPath?.any { w.walls.containsKey(it) } != true) return false
        val his = w.enemySpawn ?: return false
        return w.enemyEscorts.any { !onRampart(w, it, false) && getRange(it, his) <= BREACH_TARGET_RANGE }
    }

    /** The wall to hit now: the pass while there is a target behind it, else the corridor to our flags — the first wall
     *  of the group still standing along our way. */
    private fun breachTarget(w: World): StructureWall? {
        val path = (if (breachLeft(w)) breachPath else if (corridorLeft(w)) corridorPath else null) ?: return null
        val k = path.firstOrNull { w.walls.containsKey(it) }
        if (k == null) {
            if (breachOpenedAt < 0) { breachOpenedAt = w.now; println("breach t=${w.now}: OPEN") }
            return null
        }
        return w.walls[k]
    }

    /**
     * Idle fighters break the wall: melee on the cells beside it (the strongest first — inside the pass there is room for
     * one), ranged within three of it; the rest wait five cells off. Only cells on OUR side count: the far side of a
     * standing wall is not reachable, and a flow toward the wall itself would route through it.
     */
    private fun runBreach(w: World, idle: List<Creep>): Set<String> {
        val wall = breachTarget(w) ?: return emptySet()
        val spawn = w.mySpawn ?: return emptySet()
        val side = flowTo("homeside", spawn, ARMY_SWAMP_COST)
        fun ours(k: Int) = DistanceMap.inBounds(k / 100, k % 100) && side[k] >= 0 && !w.walls.containsKey(k)
        val meleeSlots = DIRECTIONS.map { (dx, dy) -> (wall.x + dx) * 100 + (wall.y + dy) }.filter { ours(it) }
        val rangedSlots = ArrayList<Int>()
        for (dx in -3..3) for (dy in -3..3) {
            val k = (wall.x + dx) * 100 + (wall.y + dy)
            if (k !in meleeSlots && ours(k)) rangedSlots.add(k)
        }
        val taken = HashSet<Int>()
        val used = HashSet<String>()
        fun assign(f: Creep, slots: List<Int>) {
            // a slot is free unless handed out this tick or held by his creep or by our escort (ours move aside)
            val free = slots.filter { k -> k !in taken && w.occupant[k]?.let { o -> o.my && idOf(o) !in escortIds } ?: true }
            val slot = free.minByOrNull { getRange(cellOf(it), f) }
            used.add(idOf(f))
            if (slot == null) { if (getRange(f, wall) > 5) step(w, f, wall, 5); return }
            taken.add(slot)
            if (key(f) != slot) step(w, f, cellOf(slot), 0)
        }
        for (f in idle.filter { Bodies.live(it, ATTACK) > 0 }.sortedByDescending { Bodies.live(it, ATTACK) }) assign(f, meleeSlots)
        for (f in idle.filter { Bodies.live(it, ATTACK) == 0 && Bodies.live(it, RANGED_ATTACK) > 0 }) assign(f, rangedSlots)
        for (f in idle) {
            if (idOf(f) !in used) continue
            if (Bodies.live(f, ATTACK) > 0 && getRange(f, wall) <= 1 && w.enemies.none { getRange(it, f) <= 1 }) f.attack(wall)
            else if (Bodies.live(f, RANGED_ATTACK) > 0 && getRange(f, wall) <= 3 && w.enemies.none { getRange(it, f) <= 3 }) f.rangedAttack(wall)
        }
        if (w.now % LOG_EVERY == 0) println("breach t=${w.now}: wall ${at(wall)} h${wall.hits} left=${breachPath?.count { w.walls.containsKey(it) }} crew=${used.size}")
        return used
    }

    // ==================== the army: what to do ====================

    private fun units(cs: List<Creep>) = cs.map { Bodies.unitOf(it) }

    /** Enemy fighters near a garrison's base. Once defending, the radius widens by DEFEND_KEEP: v4 flipped DEFEND/HOLD
     *  every tick against an M5R5 standing at exactly twelve (stachu3478#1, 6ab92799) and its fighters walked out and
     *  back. */
    private fun threatsTo(w: World, g: Garrison, base: Position?): List<Creep> {
        if (base == null) return emptyList()
        val radius = HOME_RADIUS + (if (g.mode == "defend") DEFEND_KEEP else 0)
        return w.enemyArmed.filter { getRange(it, base) <= radius }
    }

    /** The largest bunch of our fighters all within CLUSTER_RADIUS of one of them — the group an operation takes. */
    private fun cluster(fighters: List<Creep>): List<Creep> =
        fighters.map { a -> fighters.filter { getRange(it, a) <= CLUSTER_RADIUS } }.maxByOrNull { it.size } ?: emptyList()

    /** His fighters that stand by this escort of his (they will be in the fight). */
    private fun guardsOf(w: World, e: Creep) = w.enemyArmed.filter { getRange(it, e) <= ESCORT_GUARD_RADIUS }

    /**
     * The army's way to a target round his fighters: every cell within DANGER_RADIUS of an armed creep of his that is not
     * guarding the target is a wall. v5 measured a strike only along the straight way — through stachu3478#3's swarm of
     * M1A1 in the centre — while his M10T40 stood 200 ticks by his flag 29 cells from the nearest of them; the way round
     * was 135 ticks without a single meeting (6ab92e36). Where no way round exists the plain field is used.
     */
    private fun opFlow(w: World, target: Position): IntArray {
        val k = "op:${key(target)}"
        val now = getTicks()
        flowCache[k]?.takeIf { flowTick[k] == now }?.let { return it }
        // a price, not a wall: walls round his creeps opened and shut the only gap as they shuffled, and the army's
        // decision flipped with them every tick (stand, blob+harvest: SIEGE/HOLD a thousand times)
        val cost = IntArray(10000)
        var any = false
        for (e in w.enemyArmed) {
            if (getRange(e, target) <= ESCORT_GUARD_RADIUS) continue
            for (dx in -DANGER_RADIUS..DANGER_RADIUS) for (dy in -DANGER_RADIUS..DANGER_RADIUS) {
                val x = e.x + dx; val y = e.y + dy
                if (DistanceMap.inBounds(x, y)) { cost[x * 100 + y] = DANGER_COST; any = true }
            }
        }
        val f = if (!any) flowTo("army", target, ARMY_SWAMP_COST)
            else DistanceMap.flowFieldCost(target, cost, ARMY_SWAMP_COST, stillCells)
        flowCache[k] = f
        flowTick[k] = now
        return f
    }

    /** The field a group of ours takes to the target: round his fighters when that way exists from where it stands. */
    private fun wayFor(w: World, from: Position, target: Position): IntArray {
        val round = opFlow(w, target)
        return if (anchor(round, from) != null) round else flowTo("army", target, ARMY_SWAMP_COST)
    }

    /** The reachable cell nearest to `p` in field `f` — a group's centre often falls on the spawn or an escort's cell,
     *  where a field has no value: the stand's blob+harvest line saw an empty way there, "nobody in the way", a won
     *  siege, and the next tick a lost one, every six ticks for 3000 ticks. */
    private fun anchor(f: IntArray, p: Position): Position? {
        if (DistanceMap.inBounds(p.x, p.y) && f[key(p)] >= 0) return p
        for (r in 1..5) {
            var best: Position? = null
            for (dx in -r..r) for (dy in -r..r) {
                if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
                val x = p.x + dx; val y = p.y + dy
                if (!DistanceMap.inBounds(x, y) || f[x * 100 + y] < 0) continue
                if (best == null || f[x * 100 + y] < f[key(best)]) best = cell(x, y)
            }
            if (best != null) return best
        }
        return null
    }

    /** The cells our group walks from `from` to `target`, down its way (at most 250). */
    private fun pathCells(w: World, from: Position, target: Position): List<Int> {
        val f = wayFor(w, from, target)
        val out = ArrayList<Int>()
        val start = anchor(f, from) ?: return out
        var x = start.x; var y = start.y
        for (i in 0 until 250) {
            out.add(x * 100 + y)
            val here = f[x * 100 + y]
            if (here <= 0) break
            var best = -1; var bestD = here
            for ((dx, dy) in DIRECTIONS) {
                val nx = x + dx; val ny = y + dy
                if (!DistanceMap.inBounds(nx, ny)) continue
                val d = f[nx * 100 + ny]
                if (d in 0 until bestD) { bestD = d; best = nx * 100 + ny }
            }
            if (best < 0) break
            x = best / 100; y = best % 100
        }
        return out
    }

    /**
     * Everything of his that will be in the fight if our group goes from `from` to `target`: the fighters by the target
     * and every fighter within PATH_RADIUS of the way there. v3 weighed a strike against the escort's guards alone, and
     * sent twenty M5A5 one by one to an escort of けろびー#11 standing outside his ramparts — through his blob of five
     * T3M8R5 and three healers holding the centre (6ab9267a).
     */
    private fun inTheWay(w: World, from: Position, target: Creep): List<Creep> {
        val path = pathCells(w, from, target)
        return w.enemyArmed.filter { e ->
            getRange(e, target) <= ESCORT_GUARD_RADIUS || path.any { k -> maxOf(kotlin.math.abs(k / 100 - e.x), kotlin.math.abs(k % 100 - e.y)) <= PATH_RADIUS }
        }
    }

    /** The one of his escorts a melee can reach: on a cell with a neighbour we can stand on. */
    private fun meleeReachable(w: World, e: Creep): Boolean = DIRECTIONS.any { (dx, dy) ->
        val x = e.x + dx; val y = e.y + dy
        DistanceMap.inBounds(x, y) && !DistanceMap.isWall(x, y) && !w.enemyRamparts.containsKey(x * 100 + y)
    }

    /**
     * The kill is done before the rest of his army comes: our group's way there (the army's flow — it walks at its rear's
     * pace) plus the damage still between us and the dead escort (its hits, its rampart's, and the rampart beside it for
     * one no melee reaches) over our firepower, against the nearest fighter of his that is not already in the simulated
     * fight. A strike through the left-edge pass meets no one on the way, and his blob in the centre is what arrives.
     */
    /**
     * The fight an operation meets: what is on our way and by the target, plus every other fighter of his that can reach
     * the target (a cell a tick at best — the range is the soonest he comes) before the kill is done — our way there (the
     * group's slowest, along its way) plus the damage still between us and the dead escort (its hits, its rampart's, and
     * the rampart beside it for one no melee reaches) over our firepower. v5 vetoed the operation outright when anybody
     * could come sooner than that, and so never struck a far escort at all (6ab92e36, 6ab92dd5); the question is not whether
     * he comes but whether we still win when he does.
     */
    private fun opFight(w: World, group: List<Creep>, from: Position, target: Creep): Bodies.Outcome {
        val inWay = inTheWay(w, from, target)
        val ids = inWay.mapTo(HashSet()) { idOf(it) }
        val flow = wayFor(w, from, target)
        // ticks, not the field's value: the danger price inflates the field (an M5X5 walks a cell a tick on plain)
        val eta = pathCells(w, from, target).size
        val dps = maxOf(1, group.sumOf { Bodies.meleeDps(it) + Bodies.rangedDps(it) })
        var need = target.hits + (w.enemyRamparts[key(target)]?.hits ?: 0)
        if (!meleeReachable(w, target)) need += breachCell(w, target)?.let { w.enemyRamparts[key(it)]?.hits } ?: 0
        // he answers what he sees coming, not our leaving home: those join who reach the target within the kill plus the
        // last NOTICE_TICKS of our way. Counting the whole way, v8 never struck けろびー#15's two escorts standing out of
        // his ramparts by his spawn from 179 to ~1600 with the pass open from 743 — his blob sat 45 cells off in the
        // centre, a 9-tick kill away (6ab93352)
        val done = minOf(eta, NOTICE_TICKS) + need / dps
        val joiners = w.enemyArmed.filter { idOf(it) !in ids && getRange(it, target) <= done }
        lastOpWhy = "way=${if (flow === opFlow(w, target)) "round" else "plain"} eta=$eta done=$done inWay=${inWay.size} join=${joiners.size}"
        return Bodies.fight(units(group), units(inWay + joiners))
    }

    /**
     * The army's mode. Defending home comes first. Otherwise an operation (strike on an escort of his out of his
     * ramparts, or siege) is weighed for ONE group: the running operation's members, or, to start one, the largest
     * cluster of our fighters. The group walks without waiting — a group that waits for its rear waited for every newborn
     * at home in v4, and 51 fighters stood strung out for 4000 ticks (6ab927b0); a fighter born later is a reserve.
     */
    private fun decideArmy(w: World, g: Garrison, fighters: List<Creep>, base: Position?) {
        val prev = g.mode
        val prevTarget = g.modeTarget
        val threats = threatsTo(w, g, base)
        val inOp = g.inOp
        val group = if (inOp) fighters.filter { idOf(it) in g.members } else cluster(fighters)
        val ours = units(group)
        val from: Position? = if (group.isEmpty()) null else cell(group.sumOf { it.x } / group.size, group.sumOf { it.y } / group.size)
        var mode: String
        var modeTarget: String? = null
        if (threats.isNotEmpty()) {
            mode = "defend"
            if (g.attackedAt < 0) g.attackedAt = w.now
        } else {
            // strike: an escort of his outside his ramparts, where the fight against everything guarding it is ours
            // of several, the one nearest to our group: any kill wins, so the soonest one
            var picked: Pair<Creep, Bodies.Outcome>? = null
            for (e in w.enemyEscorts.filter { !onRampart(w, it, false) }) {
                if (from == null) break
                val sim = opFight(w, group, from, e)
                val need = if (prev == "strike" && prevTarget == idOf(e)) KEEP_MARGIN else STRIKE_MARGIN
                if (sim.weWin && sim.margin() >= need && (picked == null || getRange(e, from) < getRange(picked.first, from)))
                    picked = e to sim
            }
            val siegeNeed = if (prev == "siege") KEEP_MARGIN else SIEGE_MARGIN
            val siegeTarget = w.enemyEscorts.filter { meleeReachable(w, it) }.minByOrNull { it.hits } ?: w.enemyEscorts.minByOrNull { it.hits }
            val siegeSim = if (from != null && siegeTarget != null && group.size >= SIEGE_MIN_FIGHTERS) opFight(w, group, from, siegeTarget) else null
            lastSiegeWhy = "${siegeTarget?.let { at(it) }} sim=$siegeSim need=$siegeNeed $lastOpWhy"
            if (picked != null) {
                mode = "strike"; modeTarget = idOf(picked.first)
                if (prev != mode || prevTarget != modeTarget) println("army ${g.name} t=${w.now}: STRIKE ${Bodies.summaryOf(picked.first)}${at(picked.first)} h${picked.first.hits} sim=${picked.second} group=${group.size}/${fighters.size}")
            } else if (convoyOn && g === homeG) {
                // the home army walks with the convoy
                mode = "convoy"
                if (prev != mode) println("army ${g.name} t=${w.now}: CONVOY guard=${fighters.size}")
            } else if (siegeSim != null && siegeTarget != null && siegeSim.weWin && siegeSim.margin() >= siegeNeed) {
                mode = "siege"
                modeTarget = idOf(siegeTarget)
                if (prev != mode || prevTarget != modeTarget) println("army ${g.name} t=${w.now}: SIEGE ${Bodies.summaryOf(siegeTarget)}${at(siegeTarget)} h${siegeTarget.hits} sim=$siegeSim group=${group.size}/${fighters.size}")
            } else {
                mode = "hold"
            }
        }
        // an operation is dropped only after DROP_AFTER lost ticks in a row, and only while its target lives
        if (inOp && mode == "hold" && prevTarget != null && w.enemies.any { idOf(it) == prevTarget }) {
            g.failStreak++
            if (g.failStreak < DROP_AFTER) { mode = prev; modeTarget = prevTarget }
        } else if (mode == prev && modeTarget == prevTarget) g.failStreak = 0
        g.mode = mode
        g.modeTarget = modeTarget
        g.members = if (g.inOp) group.mapTo(HashSet()) { idOf(it) } else emptySet()
        if (prev != mode) {
            if (mode == "defend") println("army ${g.name} t=${w.now}: DEFEND against ${threats.joinToString(" ") { "${Bodies.summaryOf(it)}${at(it)}" }} fighters=${fighters.size}")
            if (mode == "hold") println("army ${g.name} t=${w.now}: HOLD (was $prev) group=${group.size} from=${from?.let { at(it) }} " +
                "siege=${lastSiegeWhy}")
            g.modeSince = w.now
        }
    }

    // ==================== the army: how ====================

    /** Where an idle fighter stands: the outer ring of our block, the side that faces the middle of the map first. */
    private fun rallyCells(w: World): List<Int> {
        val inner = innerCells(w).toHashSet()
        val spawn = w.mySpawn ?: return emptyList()
        // only the ramparts of home: v8's first build rallied the home army on the outpost's ramparts (they lie nearer to
        // the middle of the map) and it stood on the outpost's spawn site
        val ring = basesRamparts(w, spawn).filter { it !in inner && it != key(spawn) }
            .sortedBy { k -> val dx = k / 100 - 50; val dy = k % 100 - 50; dx * dx + dy * dy }
        // then the rings outside the block, never a cell beside the spawn: with more fighters than ramparts the rest
        // stood within four of the spawn, took its eight cells, and the spawn sat on 1000 energy unable to put a creep
        // out (stand, blob+harvest)
        val out = ArrayList<Int>()
        for (r in 3..6) for (dx in -r..r) for (dy in -r..r) {
            if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
            val k = (spawn.x + dx) * 100 + (spawn.y + dy)
            if (!DistanceMap.inBounds(k / 100, k % 100) || DistanceMap.isWall(k / 100, k % 100) || k in w.myRamparts) continue
            if (works?.any { it.second == k } == true || harvestSpots(w).take(2).contains(k)) continue
            out.add(k)
        }
        return ring + out.sortedBy { k -> val dx = k / 100 - 50; val dy = k % 100 - 50; dx * dx + dy * dy }
    }

    private fun runArmy(w: World, g: Garrison, fighters: List<Creep>, base: Position?, rally: List<Int>, breach: Boolean) {
        if (fighters.isEmpty()) return
        val target: Creep? = g.modeTarget?.let { id -> w.enemies.firstOrNull { idOf(it) == id } }
        val threats = threatsTo(w, g, base)
        var rallyIdx = 0
        val inOp = g.inOp
        for (f in fighters) act(w, f, g, target)
        // reserves (not in the running operation) break the wall while nothing threatens home
        val reserves = fighters.filter { !(inOp && idOf(it) in g.members) }
        val breaching = if (breach && g.mode != "defend" && (breachLeft(w) || corridorLeft(w))) runBreach(w, reserves) else emptySet()
        val walled = if (g.mode == "defend") holdRamparts(w, fighters, threats, base) else emptySet()
        for (f in fighters) {
            if (idOf(f) in breaching || idOf(f) in walled) continue
            if (g.mode == "convoy") { guardConvoy(w, f); continue }
            val focus: Position? = when {
                g.mode == "defend" -> threats.minByOrNull { getRange(it, f) }
                inOp && idOf(f) in g.members -> target
                else -> null
            }
            if (focus == null) {
                val spot = rally.getOrNull(rallyIdx++)
                if (spot == null) { if (base != null && getRange(f, base) > 6) step(w, f, base, 6); continue }
                if (key(f) != spot) step(w, f, cellOf(spot), 0)
                continue
            }
            val opMember = inOp && idOf(f) in g.members
            if (Bodies.isMelee(f)) {
                // siege on an escort no melee can reach: break the rampart beside it that we can stand next to
                val goal = if (g.mode == "siege" && target != null && !meleeReachable(w, target)) breachCell(w, target) ?: target else focus
                if (getRange(f, goal) > 1) { if (opMember) stepOp(w, f, goal, 1) else step(w, f, goal, 1) }
            } else {
                val meleeNear = w.enemyArmed.filter { Bodies.meleeDps(it) > 0 && getRange(it, f) <= 1 }
                if (meleeNear.isNotEmpty()) kite(w, f, meleeNear)
                else if (getRange(f, focus) > 3) { if (opMember) stepOp(w, f, focus, 3) else step(w, f, focus, 3) }
            }
        }
    }

    /**
     * Defending from our ramparts: unless the fight in the open is ours with a wide margin, every defender takes a free
     * rampart of ours nearest to the threat — a melee one beside it if there is one, a ranged one within three — and
     * fights from there, taking no damage while the rampart holds. v5 walked out to meet stachu3478#1's M7A6M1, M4R5M1
     * and M4H3M1 one by one: eleven fighters, no kill, all dead in the ten cells north of our block, while the rampart
     * next to the cell his melee struck from stood free (6ab92e1f). Returns the defenders placed.
     */
    /** Our ramparts belonging to the base at `base` (within HOME_RADIUS), less the outpost's spawn cell and worker slots. */
    private fun basesRamparts(w: World, base: Position): List<Int> {
        val op = outpost
        return w.myRamparts.filter { k -> getRange(cellOf(k), base) <= HOME_RADIUS && (op == null || (k != op.spawnCell && k !in op.slots)) }
    }

    private fun holdRamparts(w: World, fighters: List<Creep>, threats: List<Creep>, base: Position?): Set<String> {
        if (threats.isEmpty() || fighters.isEmpty() || base == null) return emptySet()
        val open = Bodies.fight(units(fighters), units(threats))
        if (open.weWin && open.margin() >= DEFEND_OPEN_MARGIN) return emptySet()
        val spawnKey = w.mySpawn?.let { key(it) }
        val towerCells = works?.filter { it.first == "tower" }?.map { it.second }?.toSet() ?: emptySet()
        val cells = basesRamparts(w, base).filter { k -> k != spawnKey && k !in towerCells &&
            (w.occupant[k]?.let { o -> o.my && idOf(o) !in escortIds } ?: true) }
        val taken = HashSet<Int>()
        val placed = HashSet<String>()
        fun near(k: Int) = threats.minOf { getRange(it, cellOf(k)) }
        for (f in fighters.sortedByDescending { Bodies.meleeDps(it) }) {
            val want = if (Bodies.isMelee(f)) 1 else 3
            val cell = cells.filter { it !in taken }
                .minWithOrNull(compareBy<Int>({ maxOf(0, near(it) - want) }, { getRange(cellOf(it), f) })) ?: continue
            taken.add(cell)
            placed.add(idOf(f))
            if (key(f) != cell) step(w, f, cellOf(cell), 0)
        }
        return placed
    }

    /**
     * A guard of the convoy: his fighter within CONVOY_GUARD_RADIUS of an escort of ours is met (the nearest one); else the
     * guard keeps three cells from the nearest escort still on its way — not beside it, where it would stand on the
     * train's next cell. His creep on one of our flags is met too: a blocker must die before the escort steps on.
     */
    private fun guardConvoy(w: World, f: Creep) {
        val plan = convoyFlags ?: return
        val walking = w.escorts.filter { e -> plan[idOf(e)]?.let { key(e) != it } == true }
        val blockers = w.enemies.filter { en -> plan.values.any { fl -> getRange(en, cellOf(fl)) <= 1 } && idOf(en) !in escortIds }
        val threats = w.enemyArmed.filter { en -> w.escorts.any { getRange(it, en) <= CONVOY_GUARD_RADIUS } } + blockers
        val foe = threats.minByOrNull { getRange(it, f) }
        if (foe != null) {
            val want = if (Bodies.isMelee(f)) 1 else 3
            if (getRange(f, foe) > want) step(w, f, foe, want)
            return
        }
        val e = walking.minByOrNull { getRange(it, f) } ?: w.escorts.minByOrNull { getRange(it, f) } ?: return
        if (getRange(f, e) > 3) step(w, f, e, 3)
    }

    /** An enemy rampart next to the target escort that has a cell beside it we can stand on. */
    private fun breachCell(w: World, e: Creep): Position? {
        return DIRECTIONS.map { (dx, dy) -> cell(e.x + dx, e.y + dy) }
            .filter { w.enemyRamparts.containsKey(key(it)) && DIRECTIONS.any { (dx, dy) ->
                val x = it.x + dx; val y = it.y + dy
                DistanceMap.inBounds(x, y) && !DistanceMap.isWall(x, y) && !w.enemyRamparts.containsKey(x * 100 + y) } }
            .minByOrNull { r -> w.enemyRamparts[key(r)]?.hits ?: 0 }
    }

    /** An operation's member walks its way round his fighters (opFlow). */
    private fun stepOp(w: World, c: Creep, goal: Position, range: Int) {
        val f = wayFor(w, c, goal)
        DistanceMap.flowStep(f, c.x, c.y, range, w.occupant.keys, w.enemyAt)?.let { TrafficManager.request(c, it, FIGHTER_PRIORITY) }
    }

    private fun step(w: World, c: Creep, goal: Position, range: Int) {
        val f = flowTo("army", goal, Bodies.swampCost(c))
        DistanceMap.flowStep(f, c.x, c.y, range, w.occupant.keys, w.enemyAt)?.let { TrafficManager.request(c, it, FIGHTER_PRIORITY) }
    }

    /** A ranged fighter with a melee beside it steps to the free neighbour farthest from all of them. */
    private fun kite(w: World, c: Creep, melee: List<Creep>) {
        var best: Position? = null
        var bestScore = Int.MIN_VALUE
        for ((dx, dy) in DIRECTIONS) {
            val x = c.x + dx; val y = c.y + dy
            if (!DistanceMap.inBounds(x, y) || DistanceMap.isWall(x, y) || w.occupant.containsKey(x * 100 + y)) continue
            if (w.enemyRamparts.containsKey(x * 100 + y)) continue
            val p = cell(x, y)
            val score = melee.minOf { getRange(it, p) } * 10 - (if (DistanceMap.isSwamp(x, y)) 3 else 0)
            if (score > bestScore) { bestScore = score; best = p }
        }
        best?.let { TrafficManager.request(c, it, FIGHTER_PRIORITY + 5) }
    }

    /** Fire: an escort of his first (it is the win), then the weakest armed, then anything; heal the most hurt. */
    private fun act(w: World, f: Creep, g: Garrison, target: Creep?) {
        val modeTarget = g.modeTarget
        fun rank(e: Creep): Int = when {
            target != null && idOf(e) == modeTarget -> 0
            idOf(e) in escortIds -> 1
            Bodies.isArmed(e) -> 2
            else -> 3
        }
        if (Bodies.live(f, ATTACK) > 0) {
            val adj = w.enemies.filter { getRange(it, f) <= 1 }.sortedWith(compareBy({ rank(it) }, { it.hits })).firstOrNull()
            if (adj != null) f.attack(adj)
            else if (g.mode == "siege" && target != null) {
                breachCell(w, target)?.takeIf { getRange(it, f) <= 1 }?.let { r -> w.enemyRamparts[key(r)]?.let { f.attack(it) } }
            }
        }
        if (Bodies.live(f, RANGED_ATTACK) > 0) {
            val inRange = w.enemies.filter { getRange(it, f) <= 3 }
            // a mass attack hits each creep in reach for 10 / 4 / 1 a part at range 1 / 2 / 3 (one under his rampart
            // hits the rampart): it beats one shot of 10 once the sum is larger — a crowd of M1A1 round a ranged
            val mass = inRange.filter { !onRampart(w, it, false) }.sumOf { when (getRange(it, f)) { 0, 1 -> 10; 2 -> 4; else -> 1 }.toInt() }
            val best = inRange.sortedWith(compareBy({ rank(it) }, { it.hits })).firstOrNull()
            val targetFar = target != null && best != null && idOf(best) == modeTarget && getRange(best, f) > 1
            if (mass > 10 && !targetFar) f.rangedMassAttack()
            else if (best != null) f.rangedAttack(best)
        }
        if (Bodies.live(f, HEAL) > 0) {
            val hurt = w.active.filter { it.hits < it.hitsMax && getRange(it, f) <= 3 }.maxByOrNull { it.hitsMax - it.hits }
            if (hurt != null) { if (getRange(hurt, f) <= 1) f.heal(hurt) else f.rangedHeal(hurt) }
        }
    }

    // ==================== logging ====================

    private fun probe(w: World) {
        println("hello season4 escort-run-advanced v$BOT_VERSION: ${arenaInfo.season} - ${arenaInfo.name} level=${arenaInfo.level} " +
            "ticksLimit=${arenaInfo.ticksLimit} cpu=${arenaInfo.cpuTimeLimit}/${arenaInfo.cpuTimeLimitFirstTick} t=${w.now}")
        println("tuning: fortress escortCell=nearest still=walls race=fighterFirst strikeSim=path group=cluster$CLUSTER_RADIUS breach=auto mass=sum works=spots,tower,route outpost=farSource opWay=price$DANGER_COST danger=$DANGER_RADIUS drop=$DROP_AFTER joiners=notice$NOTICE_TICKS breach=ifTarget defend=ramparts convoy=p$CONVOY_PERIOD corridor=auto homeRadius=$HOME_RADIUS strike=$STRIKE_MARGIN siege=$SIEGE_MARGIN/$SIEGE_MIN_FIGHTERS work=$WORK_TARGET haulers=$HAULERS " +
            "melee=${Bodies.summary(MELEE)} ranged=${Bodies.summary(RANGED)}")
        println("consts: SPAWN_ENERGY_CAPACITY=$SPAWN_ENERGY_CAPACITY SOURCE_ENERGY_REGEN=$SOURCE_ENERGY_REGEN CREEP_SPAWN_TIME=$CREEP_SPAWN_TIME BODYPART_HITS=$BODYPART_HITS " +
            "EXTENSION_ENERGY_CAPACITY=$EXTENSION_ENERGY_CAPACITY TOWER_POWER_ATTACK=$TOWER_POWER_ATTACK TOWER_RANGE=$TOWER_RANGE TOWER_CAPACITY=$TOWER_CAPACITY " +
            "TOWER_COOLDOWN=$TOWER_COOLDOWN BUILD_POWER=$BUILD_POWER COST=${JSON.stringify(CONSTRUCTION_COST)}")
        for (f in w.myFlags + w.enemyFlags) println("flag: ${at(f)} ${own(f.my)}")
        println("spawns: mine=${w.mySpawn?.let { at(it) }} e=${energy(w)} his=${w.enemySpawn?.let { at(it) }} source=${w.homeSource?.let { at(it) }}")
        for (c in w.escorts) println("escort: ${at(c)} ${Bodies.summaryOf(c)} hits=${c.hits} period=${Bodies.period(c, false)}/${Bodies.period(c, true)} id=${idOf(c)}")
        for (c in w.enemyEscorts) println("his escort: ${at(c)} ${Bodies.summaryOf(c)} hits=${c.hits} id=${idOf(c)}")
        val walls = getObjectsByPrototype(StructureWall::class).filter { it.exists }
        println("walls(${walls.size}): " + walls.joinToString(" ") { "${it.x},${it.y}:${it.hits}" })
        println("containers: " + getObjectsByPrototype(StructureContainer::class).joinToString(" ") { "${at(it)}e${it.store[RESOURCE_ENERGY]}" })
        println("sources: " + getObjectsByPrototype(Source::class).joinToString(" ") { "${at(it)}${it.energy}/${it.energyCapacity}" })
    }

    private fun printMap(fromRow: Int) {
        val marks = HashMap<Int, Char>()
        getObjectsByPrototype(StructureWall::class).forEach { marks[key(it)] = 'W' }
        getObjectsByPrototype(StructureRampart::class).forEach { marks[key(it)] = if (it.my == true) 'r' else 'R' }
        getObjectsByPrototype(StructureContainer::class).forEach { marks[key(it)] = 'C' }
        getObjectsByPrototype(Source::class).forEach { marks[key(it)] = 'S' }
        getObjectsByPrototype(StructureSpawn::class).forEach { marks[key(it)] = if (it.my == true) 'M' else 'E' }
        getObjectsByPrototype(Flag::class).forEach { marks[key(it)] = if (it.my == true) 'f' else if (it.my == false) 'F' else 'n' }
        val sb = StringBuilder()
        for (y in fromRow until minOf(fromRow + 25, 100)) {
            sb.clear()
            for (x in 0 until 100) {
                val m = marks[x * 100 + y]
                sb.append(m ?: when (getTerrainAt(cell(x, y))) { TERRAIN_WALL -> '#'; TERRAIN_SWAMP -> '~'; else -> '.' })
            }
            println("map ${y.toString().padStart(2, '0')} $sb")
        }
    }

    private fun logStatus(w: World) {
        println("t=${w.now} e=${energy(w)} home=${homeG.mode}${homeG.modeTarget?.let { "($it)" } ?: ""} outpost=${outpostG.mode}${outpostG.modeTarget?.let { "($it)" } ?: ""}" +
            "${w.outpostSpawn?.let { "[spawn e${it.store[RESOURCE_ENERGY]}]" } ?: ""} pioneers=${w.pioneers.joinToString(",") { at(it) }} " +
            "work=${w.harvesters.sumOf { Bodies.live(it, WORK) }} " +
            "haulers=${w.haulers.size} fighters=${w.fighters.size}(${w.fighters.joinToString(",") { Bodies.summaryOf(it) + at(it) + (if (originOf[idOf(it)] == "outpost") "o" else "") }}) " +
            "ours=${w.escorts.joinToString(" ") { "${at(it)}h${it.hits}" }} his=${w.enemyEscorts.joinToString(" ") { "${at(it)}h${it.hits}${if (onRampart(w, it, false)) "r" else ""}" }} " +
            "enemies=${w.enemies.filter { idOf(it) !in escortIds }.joinToString(",") { Bodies.summaryOf(it) + at(it) }} cpu=${getCpuTime() / 1_000_000}")
    }

    @Suppress("unused")
    private val keep = listOf(TOUGH, MOVE)
}
