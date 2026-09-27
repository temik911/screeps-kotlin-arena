package season4.escortrunadvanced

import kotlinx.js.JsPlainObject
import screeps.api.ATTACK
import screeps.api.BODYPART_HITS
import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.CREEP_SPAWN_TIME
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
const val BOT_VERSION = 5

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

    private const val FIGHTER_PRIORITY = 30
    private const val HAULER_PRIORITY = 20
    private const val WORKER_PRIORITY = 10

    private const val LOG_EVERY = 50

    private val MELEE = Bodies.body(MOVE to 5, ATTACK to 5)
    private val RANGED = Bodies.body(MOVE to 5, RANGED_ATTACK to 5)
    private val HARVESTER_FIRST = Bodies.body(WORK to 3, MOVE to 1)
    private val HARVESTER_NEXT = Bodies.body(WORK to 2, MOVE to 1)
    private val HAULER = Bodies.body(CARRY to 2, MOVE to 1)
    /** The wall breaker: as much ATTACK as one spawn holds with MOVE enough for two ticks a plain cell. */
    private const val BREAKER_ATTACK = 9
    private val BREAKER = Bodies.body(MOVE to 5, ATTACK to BREAKER_ATTACK)

    // ---------- state between ticks ----------
    private val escortIds = HashSet<String>()
    private val escortHome = HashMap<String, Int>()
    private var mode = "hold"
    private var modeTarget: String? = null
    private var modeSince = 0
    /** The fighters of the running strike or siege. */
    private var members: Set<String> = emptySet()
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
    )

    fun tick() {
        val w = sense()
        stillCells = w.escorts.map { cell(it.x, it.y) }
        if (w.now == 1) probe(w)
        if (w.now in 2..5) printMap((w.now - 2) * 25)
        if (w.now == 1) chooseBreach(w)
        runSpawn(w)
        runEscorts(w)
        runEconomy(w)
        decideArmy(w)
        runArmy(w)
        TrafficManager.resolve(w.active.filter { idOf(it) !in escortIds && Bodies.liveMoves(it) > 0 }, w.active + w.enemies)
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
        val mySpawn = spawns.firstOrNull { it.my == true }
        val flags = getObjectsByPrototype(Flag::class).filter { it.exists }
        val sources = getObjectsByPrototype(Source::class).filter { it.exists }
        val ramparts = getObjectsByPrototype(StructureRampart::class).filter { it.exists }
        val myRamparts = ramparts.filter { it.my == true }.mapTo(HashSet()) { key(it) }
        val enemyRamparts = HashMap<Int, StructureRampart>()
        for (r in ramparts) if (r.my != true) enemyRamparts[key(r)] = r
        val walls = getObjectsByPrototype(StructureWall::class).filter { it.exists }
        val blocked: List<Position> = spawns + walls + sources + ramparts.filter { it.my != true }
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
            harvesters = mine.filter { idOf(it) !in escortIds && Bodies.isWorker(it) },
            haulers = mine.filter { idOf(it) !in escortIds && Bodies.isHauler(it) },
            enemyArmed = enemies.filter { idOf(it) !in escortIds && Bodies.isArmed(it) },
            piles = getObjectsByPrototype(Resource::class).filter { it.exists && it.resourceType == RESOURCE_ENERGY },
            walls = HashMap<Int, StructureWall>().also { m -> for (x in walls) m[key(x)] = x },
        )
    }

    /** Cells our own creeps must walk round: the escorts standing at home. They never yield (they are not movers), so a
     *  field through them sends a creep into an escort's back for good. */
    private var stillCells: List<Position> = emptyList()

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
        val order: Array<BodyPartType> = when {
            w.harvesters.isEmpty() -> HARVESTER_FIRST
            w.haulers.isEmpty() -> HAULER
            raceOn -> MELEE
            works < WORK_TARGET -> HARVESTER_NEXT
            w.haulers.size < HAULERS -> HAULER
            // inside a one-cell pass one melee reaches the wall, so the wall falls at the pace of the strongest one
            breachLeft(w) && w.fighters.size >= 2 && w.mine.none { Bodies.live(it, ATTACK) >= BREAKER_ATTACK } -> BREAKER
            else -> nextFighter(w)
        }
        if (energy(w) < Bodies.cost(order)) return
        val r = spawn.spawnCreep(order)
        if (r.error == null) {
            if (order.any { it == ATTACK || it == RANGED_ATTACK || it == HEAL }) armyMade++
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
        val base = DistanceMap.flowFieldTo(his, emptyList(), ARMY_SWAMP_COST)
        val s0 = startNear(base, my)
        val basePath = s0?.let { descend(base, it) } ?: emptyList()
        val len0 = s0?.let { base[it] } ?: Int.MAX_VALUE
        var best: Triple<Set<Int>, List<Int>, Int>? = null
        val comps = wallComponents(w.walls.keys)
        println("breach: ${comps.size} wall groups, way between the spawns $len0 (${basePath.size} cells)")
        for (comp in comps) {
            val f = DistanceMap.flowFieldOpen(his, comp, ARMY_SWAMP_COST)
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
    }

    private fun breachLeft(w: World) = breachPath?.any { w.walls.containsKey(it) } == true

    /** The wall to hit now: the first one of the chosen group still standing along our way. */
    private fun breachTarget(w: World): StructureWall? {
        val path = breachPath ?: return null
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

    /** Enemy fighters near home. Once defending, the radius widens by DEFEND_KEEP: v4 flipped DEFEND/HOLD every tick
     *  against an M5R5 standing at exactly twelve (stachu3478#1, 6ab92799) and its fighters walked out and back. */
    private fun homeThreats(w: World): List<Creep> {
        val spawn = w.mySpawn ?: return emptyList()
        val radius = HOME_RADIUS + (if (mode == "defend") DEFEND_KEEP else 0)
        return w.enemyArmed.filter { getRange(it, spawn) <= radius }
    }

    /** The largest bunch of our fighters all within CLUSTER_RADIUS of one of them — the group an operation takes. */
    private fun cluster(fighters: List<Creep>): List<Creep> =
        fighters.map { a -> fighters.filter { getRange(it, a) <= CLUSTER_RADIUS } }.maxByOrNull { it.size } ?: emptyList()

    /** His fighters that stand by this escort of his (they will be in the fight). */
    private fun guardsOf(w: World, e: Creep) = w.enemyArmed.filter { getRange(it, e) <= ESCORT_GUARD_RADIUS }

    /** The cells our group walks from `from` to `target`, down the army's flow field (at most 250). */
    private fun pathCells(from: Position, target: Position): List<Int> {
        val f = flowTo("army", target, ARMY_SWAMP_COST)
        val out = ArrayList<Int>()
        var x = from.x; var y = from.y
        if (!DistanceMap.inBounds(x, y) || f[x * 100 + y] < 0) return out
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
        val path = pathCells(from, target)
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
    private fun beatsReinforcements(w: World, fighters: List<Creep>, from: Position, target: Creep): Boolean {
        val inWay = inTheWay(w, from, target).mapTo(HashSet()) { idOf(it) }
        val others = w.enemyArmed.filter { idOf(it) !in inWay }
        if (others.isEmpty()) return true
        val all = Bodies.fight(units(fighters), units(w.enemyArmed))
        if (all.weWin && all.margin() >= KEEP_MARGIN) return true
        val flow = flowTo("army", target, ARMY_SWAMP_COST)
        val eta = fighters.mapNotNull { f -> flow[key(f)].takeIf { it >= 0 } }.maxOrNull() ?: return false
        val dps = fighters.sumOf { Bodies.meleeDps(it) + Bodies.rangedDps(it) }
        if (dps == 0) return false
        var need = target.hits + (w.enemyRamparts[key(target)]?.hits ?: 0)
        if (!meleeReachable(w, target)) need += breachCell(w, target)?.let { w.enemyRamparts[key(it)]?.hits } ?: 0
        val theirs = others.minOf { getRange(it, target) }
        return eta + need / dps < theirs
    }

    /**
     * The army's mode. Defending home comes first. Otherwise an operation (strike on an escort of his out of his
     * ramparts, or siege) is weighed for ONE group: the running operation's members, or, to start one, the largest
     * cluster of our fighters. The group walks without waiting — a group that waits for its rear waited for every newborn
     * at home in v4, and 51 fighters stood strung out for 4000 ticks (6ab927b0); a fighter born later is a reserve.
     */
    private fun decideArmy(w: World) {
        val fighters = w.fighters.filter { !it.spawning }
        val prev = mode
        val prevTarget = modeTarget
        val threats = homeThreats(w)
        val inOp = prev == "strike" || prev == "siege"
        val group = if (inOp) fighters.filter { idOf(it) in members } else cluster(fighters)
        val ours = units(group)
        val from: Position? = if (group.isEmpty()) null else cell(group.sumOf { it.x } / group.size, group.sumOf { it.y } / group.size)
        if (threats.isNotEmpty()) {
            mode = "defend"; modeTarget = null
        } else {
            // strike: an escort of his outside his ramparts, where the fight against everything guarding it is ours
            // of several, the one nearest to our group: any kill wins, so the soonest one
            var picked: Pair<Creep, Bodies.Outcome>? = null
            for (e in w.enemyEscorts.filter { !onRampart(w, it, false) }) {
                if (from == null) break
                val sim = Bodies.fight(ours, units(inTheWay(w, from, e)))
                val need = if (prev == "strike" && prevTarget == idOf(e)) KEEP_MARGIN else STRIKE_MARGIN
                if (sim.weWin && sim.margin() >= need && beatsReinforcements(w, group, from, e) &&
                    (picked == null || getRange(e, from) < getRange(picked.first, from)))
                    picked = e to sim
            }
            val siegeNeed = if (prev == "siege") KEEP_MARGIN else SIEGE_MARGIN
            val siegeTarget = w.enemyEscorts.filter { meleeReachable(w, it) }.minByOrNull { it.hits } ?: w.enemyEscorts.minByOrNull { it.hits }
            val siegeSim = if (from != null && siegeTarget != null && group.size >= SIEGE_MIN_FIGHTERS) Bodies.fight(ours, units(inTheWay(w, from, siegeTarget))) else null
            if (picked != null) {
                mode = "strike"; modeTarget = idOf(picked.first)
                if (prev != mode || prevTarget != modeTarget) println("army t=${w.now}: STRIKE ${Bodies.summaryOf(picked.first)}${at(picked.first)} h${picked.first.hits} sim=${picked.second} group=${group.size}/${fighters.size}")
            } else if (siegeSim != null && siegeSim.weWin && siegeSim.margin() >= siegeNeed && beatsReinforcements(w, group, from!!, siegeTarget!!)) {
                mode = "siege"
                modeTarget = idOf(siegeTarget)
                if (prev != mode || prevTarget != modeTarget) println("army t=${w.now}: SIEGE ${Bodies.summaryOf(siegeTarget)}${at(siegeTarget)} h${siegeTarget.hits} sim=$siegeSim group=${group.size}/${fighters.size}")
            } else {
                mode = "hold"; modeTarget = null
            }
        }
        members = if (mode == "strike" || mode == "siege") group.mapTo(HashSet()) { idOf(it) } else emptySet()
        if (prev != mode) {
            if (mode == "defend") println("army t=${w.now}: DEFEND against ${threats.joinToString(" ") { "${Bodies.summaryOf(it)}${at(it)}" }} fighters=${fighters.size}")
            if (mode == "hold") println("army t=${w.now}: HOLD (was $prev)")
            modeSince = w.now
        }
    }

    // ==================== the army: how ====================

    /** Where an idle fighter stands: the outer ring of our block, the side that faces the middle of the map first. */
    private fun rallyCells(w: World): List<Int> {
        val inner = innerCells(w).toHashSet()
        val spawn = w.mySpawn ?: return emptyList()
        return w.myRamparts.filter { it !in inner && it != key(spawn) }
            .sortedBy { k -> val dx = k / 100 - 50; val dy = k % 100 - 50; dx * dx + dy * dy }
    }

    private fun runArmy(w: World) {
        val fighters = w.fighters.filter { !it.spawning }
        if (fighters.isEmpty()) return
        val target: Creep? = modeTarget?.let { id -> w.enemies.firstOrNull { idOf(it) == id } }
        val threats = homeThreats(w)
        val rally = rallyCells(w)
        var rallyIdx = 0
        val inOp = mode == "strike" || mode == "siege"
        for (f in fighters) act(w, f, target)
        // reserves (not in the running operation) break the wall while nothing threatens home
        val reserves = fighters.filter { !(inOp && idOf(it) in members) }
        val breaching = if (mode != "defend") runBreach(w, reserves) else emptySet()
        for (f in fighters) {
            if (idOf(f) in breaching) continue
            val focus: Position? = when {
                mode == "defend" -> threats.minByOrNull { getRange(it, f) }
                inOp && idOf(f) in members -> target
                else -> null
            }
            if (focus == null) {
                val spot = rally.getOrNull(rallyIdx++) ?: continue
                if (key(f) != spot) step(w, f, cellOf(spot), 0)
                continue
            }
            if (Bodies.isMelee(f)) {
                // siege on an escort no melee can reach: break the rampart beside it that we can stand next to
                val goal = if (mode == "siege" && target != null && !meleeReachable(w, target)) breachCell(w, target) ?: target else focus
                if (getRange(f, goal) > 1) step(w, f, goal, 1)
            } else {
                val meleeNear = w.enemyArmed.filter { Bodies.meleeDps(it) > 0 && getRange(it, f) <= 1 }
                if (meleeNear.isNotEmpty()) kite(w, f, meleeNear)
                else if (getRange(f, focus) > 3) step(w, f, focus, 3)
            }
        }
    }

    /** An enemy rampart next to the target escort that has a cell beside it we can stand on. */
    private fun breachCell(w: World, e: Creep): Position? {
        return DIRECTIONS.map { (dx, dy) -> cell(e.x + dx, e.y + dy) }
            .filter { w.enemyRamparts.containsKey(key(it)) && DIRECTIONS.any { (dx, dy) ->
                val x = it.x + dx; val y = it.y + dy
                DistanceMap.inBounds(x, y) && !DistanceMap.isWall(x, y) && !w.enemyRamparts.containsKey(x * 100 + y) } }
            .minByOrNull { r -> w.enemyRamparts[key(r)]?.hits ?: 0 }
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
    private fun act(w: World, f: Creep, target: Creep?) {
        fun rank(e: Creep): Int = when {
            target != null && idOf(e) == modeTarget -> 0
            idOf(e) in escortIds -> 1
            Bodies.isArmed(e) -> 2
            else -> 3
        }
        if (Bodies.live(f, ATTACK) > 0) {
            val adj = w.enemies.filter { getRange(it, f) <= 1 }.sortedWith(compareBy({ rank(it) }, { it.hits })).firstOrNull()
            if (adj != null) f.attack(adj)
            else if (mode == "siege" && target != null) {
                breachCell(w, target)?.takeIf { getRange(it, f) <= 1 }?.let { r -> w.enemyRamparts[key(r)]?.let { f.attack(it) } }
            }
        }
        if (Bodies.live(f, RANGED_ATTACK) > 0) {
            val inRange = w.enemies.filter { getRange(it, f) <= 3 }
            val close = inRange.count { getRange(it, f) <= 1 && !onRampart(w, it, false) }
            val best = inRange.sortedWith(compareBy({ rank(it) }, { it.hits })).firstOrNull()
            if (close >= 2) f.rangedMassAttack()
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
        println("tuning: fortress escortCell=nearest still=walls race=fighterFirst strikeSim=path group=cluster$CLUSTER_RADIUS breach=auto homeRadius=$HOME_RADIUS strike=$STRIKE_MARGIN siege=$SIEGE_MARGIN/$SIEGE_MIN_FIGHTERS work=$WORK_TARGET haulers=$HAULERS " +
            "melee=${Bodies.summary(MELEE)} ranged=${Bodies.summary(RANGED)}")
        println("consts: SPAWN_ENERGY_CAPACITY=$SPAWN_ENERGY_CAPACITY SOURCE_ENERGY_REGEN=$SOURCE_ENERGY_REGEN CREEP_SPAWN_TIME=$CREEP_SPAWN_TIME BODYPART_HITS=$BODYPART_HITS")
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
        println("t=${w.now} e=${energy(w)} mode=$mode${modeTarget?.let { "($it)" } ?: ""} work=${w.harvesters.sumOf { Bodies.live(it, WORK) }} " +
            "haulers=${w.haulers.size} fighters=${w.fighters.size}(${w.fighters.joinToString(",") { Bodies.summaryOf(it) + at(it) }}) " +
            "ours=${w.escorts.joinToString(" ") { "${at(it)}h${it.hits}" }} his=${w.enemyEscorts.joinToString(" ") { "${at(it)}h${it.hits}${if (onRampart(w, it, false)) "r" else ""}" }} " +
            "enemies=${w.enemies.filter { idOf(it) !in escortIds }.joinToString(",") { Bodies.summaryOf(it) + at(it) }} cpu=${getCpuTime() / 1_000_000}")
    }

    @Suppress("unused")
    private val keep = listOf(TOUGH, MOVE)
}
