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
const val BOT_VERSION = 2

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

    private const val FIGHTER_PRIORITY = 30
    private const val HAULER_PRIORITY = 20
    private const val WORKER_PRIORITY = 10

    private const val LOG_EVERY = 50

    private val MELEE = Bodies.body(MOVE to 5, ATTACK to 5)
    private val RANGED = Bodies.body(MOVE to 5, RANGED_ATTACK to 5)
    private val HARVESTER_FIRST = Bodies.body(WORK to 3, MOVE to 1)
    private val HARVESTER_NEXT = Bodies.body(WORK to 2, MOVE to 1)
    private val HAULER = Bodies.body(CARRY to 2, MOVE to 1)

    // ---------- state between ticks ----------
    private val escortIds = HashSet<String>()
    private val escortHome = HashMap<String, Int>()
    private var mode = "hold"
    private var modeTarget: String? = null
    private var modeSince = 0
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
    )

    fun tick() {
        val w = sense()
        if (w.now == 1) probe(w)
        if (w.now in 2..5) printMap((w.now - 2) * 25)
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
        )
    }

    private fun flowTo(name: String, target: Position, swampCost: Int, extra: List<Position> = emptyList()): IntArray {
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
        val order: Array<BodyPartType> = when {
            w.harvesters.isEmpty() -> HARVESTER_FIRST
            w.haulers.isEmpty() -> HAULER
            works < WORK_TARGET -> HARVESTER_NEXT
            w.haulers.size < HAULERS -> HAULER
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
            val f = flowTo("escort", cellOf(home), Bodies.swampCost(e))
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
            if (free > 0 && carried < 50 * Bodies.live(c, CARRY) / 2 + 1 || carried == 0) {
                val pile = w.piles.filter { getRange(it, src) <= 2 }.maxByOrNull { it.amount }
                val goal: Position = pile ?: spots.firstOrNull()?.let { cellOf(it) } ?: continue
                if (getRange(c, goal) > 1) {
                    val f = flowTo("pile", goal, Bodies.swampCost(c))
                    DistanceMap.flowStep(f, c.x, c.y, 1, w.occupant.keys, w.enemyAt)?.let { TrafficManager.request(c, it, HAULER_PRIORITY) }
                }
            } else {
                if (getRange(c, spawn) <= 1) c.transfer(spawn, RESOURCE_ENERGY)
                else {
                    val f = flowTo("spawn", spawn, Bodies.swampCost(c))
                    DistanceMap.flowStep(f, c.x, c.y, 1, w.occupant.keys, w.enemyAt)?.let { TrafficManager.request(c, it, HAULER_PRIORITY) }
                }
            }
        }
    }

    // ==================== the army: what to do ====================

    private fun units(cs: List<Creep>) = cs.map { Bodies.unitOf(it) }

    private fun homeThreats(w: World): List<Creep> {
        val spawn = w.mySpawn ?: return emptyList()
        return w.enemyArmed.filter { getRange(it, spawn) <= HOME_RADIUS }
    }

    /** His fighters that stand by this escort of his (they will be in the fight). */
    private fun guardsOf(w: World, e: Creep) = w.enemyArmed.filter { getRange(it, e) <= ESCORT_GUARD_RADIUS }

    /** The one of his escorts a melee can reach: on a cell with a neighbour we can stand on. */
    private fun meleeReachable(w: World, e: Creep): Boolean = DIRECTIONS.any { (dx, dy) ->
        val x = e.x + dx; val y = e.y + dy
        DistanceMap.inBounds(x, y) && !DistanceMap.isWall(x, y) && !w.enemyRamparts.containsKey(x * 100 + y)
    }

    private fun decideArmy(w: World) {
        val fighters = w.fighters.filter { !it.spawning }
        val prev = mode
        val prevTarget = modeTarget
        val threats = homeThreats(w)
        val ours = units(fighters)
        if (threats.isNotEmpty()) {
            mode = "defend"; modeTarget = null
        } else {
            // strike: an escort of his outside his ramparts, where the fight against everything guarding it is ours
            var picked: Pair<Creep, Bodies.Outcome>? = null
            for (e in w.enemyEscorts.filter { !onRampart(w, it, false) }) {
                val sim = Bodies.fight(ours, units(guardsOf(w, e)))
                val need = if (prev == "strike" && prevTarget == idOf(e)) KEEP_MARGIN else STRIKE_MARGIN
                val from = w.mySpawn ?: fighters.firstOrNull() ?: continue
                if (fighters.isNotEmpty() && sim.weWin && sim.margin() >= need && (picked == null || getRange(e, from) < getRange(picked.first, from)))
                    picked = e to sim
            }
            val all = Bodies.fight(ours, units(w.enemyArmed))
            val siegeNeed = if (prev == "siege") KEEP_MARGIN else SIEGE_MARGIN
            if (picked != null) {
                mode = "strike"; modeTarget = idOf(picked.first)
                if (prev != mode || prevTarget != modeTarget) println("army t=${w.now}: STRIKE ${Bodies.summaryOf(picked.first)}${at(picked.first)} h${picked.first.hits} sim=${picked.second} fighters=${fighters.size}")
            } else if (fighters.size >= SIEGE_MIN_FIGHTERS && all.weWin && all.margin() >= siegeNeed && w.enemyEscorts.isNotEmpty()) {
                mode = "siege"
                val target = w.enemyEscorts.filter { meleeReachable(w, it) }.minByOrNull { it.hits }
                    ?: w.enemyEscorts.minByOrNull { it.hits }
                modeTarget = target?.let { idOf(it) }
                if (prev != mode || prevTarget != modeTarget) println("army t=${w.now}: SIEGE ${target?.let { "${Bodies.summaryOf(it)}${at(it)} h${it.hits}" }} sim=$all fighters=${fighters.size}")
            } else {
                mode = "hold"; modeTarget = null
            }
        }
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
        for (f in fighters) {
            act(w, f, target)
            val focus: Position? = when (mode) {
                "defend" -> threats.minByOrNull { getRange(it, f) }
                "strike", "siege" -> target
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
        println("tuning: fortress homeRadius=$HOME_RADIUS strike=$STRIKE_MARGIN siege=$SIEGE_MARGIN/$SIEGE_MIN_FIGHTERS work=$WORK_TARGET haulers=$HAULERS " +
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
