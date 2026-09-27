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
const val BOT_VERSION = 1

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

fun letter(t: BodyPartType): String = when (t) {
    MOVE -> "M"; ATTACK -> "A"; RANGED_ATTACK -> "R"; HEAL -> "H"; CARRY -> "C"; WORK -> "W"; TOUGH -> "T"; else -> "?"
}

/** The body as counts by type in order of first appearance: `M10T40`. */
fun bodyOf(c: Creep): String {
    val n = LinkedHashMap<String, Int>()
    for (p in c.body) n[letter(p.type)] = (n[letter(p.type)] ?: 0) + 1
    return n.entries.joinToString("") { "${it.key}${it.value}" }
}

/** The body part by part, head first — damage takes parts from the head, so the order is what speed loses first. */
fun bodySeq(c: Creep): String = c.body.joinToString("") { letter(it.type) }

fun parts(c: Creep, type: BodyPartType): Int = c.body.count { it.type == type && it.hits > 0 }

/** Ticks per cell: ceil(weight × rate / (2 × live MOVE)), rate 2 on plain, 10 on swamp; weight counts dead parts too. */
fun period(c: Creep, swamp: Boolean): Int {
    val weight = c.body.count { it.type != MOVE && it.type != CARRY }
    val moves = parts(c, MOVE)
    if (moves == 0) return 9999
    return maxOf(1, (weight * (if (swamp) 10 else 2) + 2 * moves - 1) / (2 * moves))
}

fun protoName(o: GameObject): String = (o.asDynamic().constructor?.name as? String) ?: "?"

fun own(my: Boolean?) = when (my) { true -> "o"; false -> "e"; null -> "n" }

fun at(p: Position) = "(${p.x},${p.y})"

/**
 * v1 — a probe. Escort Run ADVANCED opened on 27.09.2026 with three escorts a side (one cell per 4, 8 and 14 ticks), three
 * flags on the far side, a spawn and a source at home, two sources and two containers on the far side, two "secret
 * mountain passes" and 5000 ticks; the description leaves out the bodies, the hits, whose the flags are, what the passes
 * are made of and the map. Tick 1 dumps all of it; the play is deliberately plain — each escort walks alone to its own
 * flag, and the spawn sends ranged hunters at the nearest enemy escort.
 */
object EscortRunAdvanced {
    private val escortIds = HashSet<String>()
    private val target = HashMap<String, String>()

    private fun idOf(o: GameObject): String = "${o.asDynamic().id}"

    fun tick() {
        val t = getTicks()
        val all = getObjects().filter { it.exists }
        val escortObjs = all.filter { protoName(it) == "EscortCreep" }.map { it.unsafeCast<Creep>() }
        if (escortIds.isEmpty()) for (c in escortObjs) escortIds.add(idOf(c))
        val creeps = getObjectsByPrototype(Creep::class).filter { it.exists }
        val byId = LinkedHashMap<String, Creep>()
        for (c in creeps) byId[idOf(c)] = c
        for (c in escortObjs) byId[idOf(c)] = c
        val everyone = byId.values.toList()
        val mine = everyone.filter { it.my }
        val theirs = everyone.filter { !it.my && !it.spawning }
        val escorts = mine.filter { idOf(it) in escortIds }
        val enemyEscorts = theirs.filter { idOf(it) in escortIds }
        val flags = getObjectsByPrototype(Flag::class).filter { it.exists }
        val spawns = getObjectsByPrototype(StructureSpawn::class).filter { it.exists }
        val mySpawn = spawns.firstOrNull { it.my == true }

        if (t == 1) probe(all, mine, theirs, flags, spawns)
        if (t in 2..5) printMap((t - 2) * 25)

        walkEscorts(escorts, flags)
        spawnHunters(mySpawn, mine)
        hunt(mine.filter { idOf(it) !in escortIds && !it.spawning }, theirs, enemyEscorts)

        if (t % 50 == 0 || t == 2) {
            println("t=$t e=${mySpawn?.store?.get(RESOURCE_ENERGY) ?: 0} ours=" +
                escorts.joinToString(" ") { "${at(it)}h${it.hits}f${it.fatigue}" } + " theirs=" +
                enemyEscorts.joinToString(" ") { "${at(it)}h${it.hits}f${it.fatigue}" } +
                " mine=${mine.size} enemies=${theirs.size} enemyBodies=" +
                theirs.filter { idOf(it) !in escortIds }.joinToString(",") { "${bodyOf(it)}${at(it)}" } +
                " flags=" + flags.joinToString(",") { "${at(it)}${own(it.my)}" } + " cpu=${getCpuTime() / 1_000_000}")
        }
    }

    private fun probe(all: List<GameObject>, mine: List<Creep>, theirs: List<Creep>, flags: List<Flag>, spawns: List<StructureSpawn>) {
        println("hello season4 escort-run-advanced v$BOT_VERSION: ${arenaInfo.season} - ${arenaInfo.name} level=${arenaInfo.level} " +
            "ticksLimit=${arenaInfo.ticksLimit} cpu=${arenaInfo.cpuTimeLimit}/${arenaInfo.cpuTimeLimitFirstTick} t=${getTicks()}")
        println("tuning: probe walk=solo hunters=ranged")
        println("consts: SPAWN_ENERGY_CAPACITY=$SPAWN_ENERGY_CAPACITY SOURCE_ENERGY_REGEN=$SOURCE_ENERGY_REGEN CREEP_SPAWN_TIME=$CREEP_SPAWN_TIME BODYPART_HITS=$BODYPART_HITS")
        for (s in spawns) println("spawn: ${at(s)} ${own(s.my)} e=${s.store[RESOURCE_ENERGY]}/${s.store.getCapacity(RESOURCE_ENERGY)} hits=${s.hits}/${s.hitsMax}")
        for (f in flags) println("flag: ${at(f)} ${own(f.my)} proto=${protoName(f)} id=${idOf(f)}")
        for (s in getObjectsByPrototype(Source::class)) println("source: ${at(s)} ${s.energy}/${s.energyCapacity}")
        for (c in getObjectsByPrototype(StructureContainer::class)) println("container: ${at(c)} e=${c.store[RESOURCE_ENERGY]} hits=${c.hits}")
        for (c in mine) println("mine: ${at(c)} proto=${protoName(c)} ${bodyOf(c)} seq=${bodySeq(c)} hits=${c.hits}/${c.hitsMax} " +
            "period=${period(c, false)}/${period(c, true)} fatigue=${c.fatigue} id=${idOf(c)}")
        for (c in theirs) println("theirs: ${at(c)} proto=${protoName(c)} ${bodyOf(c)} hits=${c.hits}/${c.hitsMax} period=${period(c, false)}/${period(c, true)} id=${idOf(c)}")
        val walls = getObjectsByPrototype(StructureWall::class).filter { it.exists }
        println("walls(${walls.size}): " + walls.joinToString(" ") { "${it.x},${it.y}:${it.hits}" })
        val ramparts = getObjectsByPrototype(StructureRampart::class).filter { it.exists }
        println("ramparts(${ramparts.size}): " + ramparts.joinToString(" ") { "${it.x},${it.y}:${own(it.my)}${it.hits}" })
        val kinds = HashMap<String, Int>()
        for (o in all) kinds[protoName(o)] = (kinds[protoName(o)] ?: 0) + 1
        println("objects: ${kinds.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }}")
    }

    private fun printMap(fromRow: Int) {
        val marks = HashMap<Int, Char>()
        getObjectsByPrototype(StructureWall::class).forEach { marks[it.x * 100 + it.y] = 'W' }
        getObjectsByPrototype(StructureRampart::class).forEach { marks[it.x * 100 + it.y] = if (it.my == true) 'r' else 'R' }
        getObjectsByPrototype(StructureContainer::class).forEach { marks[it.x * 100 + it.y] = 'C' }
        getObjectsByPrototype(Source::class).forEach { marks[it.x * 100 + it.y] = 'S' }
        getObjectsByPrototype(StructureSpawn::class).forEach { marks[it.x * 100 + it.y] = if (it.my == true) 'M' else 'E' }
        getObjectsByPrototype(Flag::class).forEach { marks[it.x * 100 + it.y] = if (it.my == true) 'f' else if (it.my == false) 'F' else 'n' }
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

    /** Each escort to its own flag, the slowest choosing first (it has the least time to spare). */
    private fun walkEscorts(escorts: List<Creep>, flags: List<Flag>) {
        val goals = flags.filter { it.my == true }.ifEmpty { flags }
        val taken = HashSet<String>()
        for (e in escorts.sortedByDescending { period(it, false) }) {
            val known = target[idOf(e)]?.let { id -> goals.firstOrNull { idOf(it) == id } }
            val goal = known ?: goals.filter { idOf(it) !in taken }.minByOrNull { getRange(e, it) } ?: continue
            target[idOf(e)] = idOf(goal)
            taken.add(idOf(goal))
            if (e.x == goal.x && e.y == goal.y) continue
            if (e.fatigue == 0) e.moveTo(goal)
        }
    }

    private fun spawnHunters(spawn: StructureSpawn?, mine: List<Creep>) {
        if (spawn == null || spawn.spawning != null) return
        val energy = spawn.store[RESOURCE_ENERGY] ?: 0
        if (energy < 400) return
        val pairs = minOf(energy / 200, 5)
        val body = ArrayList<BodyPartType>()
        repeat(pairs) { body.add(MOVE) }
        repeat(pairs) { body.add(RANGED_ATTACK) }
        spawn.spawnCreep(body.toTypedArray())
    }

    private fun hunt(hunters: List<Creep>, theirs: List<Creep>, enemyEscorts: List<Creep>) {
        for (h in hunters) {
            val near = theirs.filter { getRange(h, it) <= 3 }.minByOrNull { it.hits }
            if (near != null) h.rangedAttack(near)
            val goal = enemyEscorts.minByOrNull { getRange(h, it) } ?: theirs.minByOrNull { getRange(h, it) } ?: continue
            if (getRange(h, goal) > 2) h.moveTo(goal)
        }
    }
}
