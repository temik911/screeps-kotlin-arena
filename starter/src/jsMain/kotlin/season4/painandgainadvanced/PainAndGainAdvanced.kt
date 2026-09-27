package season4.painandgainadvanced

import kotlinx.js.JsPlainObject
import screeps.api.ATTACK
import screeps.api.ATTACK_POWER
import screeps.api.BODYPART_HITS
import screeps.api.CARRY_CAPACITY
import screeps.api.HEAL_POWER
import screeps.api.RANGED_ATTACK_POWER
import screeps.api.RANGED_HEAL_POWER
import screeps.api.TOWER_CAPACITY
import screeps.api.TOWER_COOLDOWN
import screeps.api.TOWER_ENERGY_COST
import screeps.api.TOWER_FALLOFF
import screeps.api.TOWER_FALLOFF_RANGE
import screeps.api.TOWER_HITS
import screeps.api.TOWER_OPTIMAL_RANGE
import screeps.api.TOWER_POWER_ATTACK
import screeps.api.TOWER_POWER_HEAL
import screeps.api.TOWER_RANGE
import screeps.api.CARRY
import screeps.api.Creep
import screeps.api.GameObject
import screeps.api.HEAL
import screeps.api.MOVE
import screeps.api.Position
import screeps.api.RANGED_ATTACK
import screeps.api.RESOURCE_ENERGY
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
import screeps.api.season4.painandgainadvanced.FLAG_TYPES
import screeps.api.season4.painandgainadvanced.MAX_SCORE_PER_TICK
import screeps.api.season4.painandgainadvanced.ScoreFlag
import screeps.api.season4.painandgainadvanced.TICKS_LIMIT
import screeps.api.structures.StructureContainer
import screeps.api.structures.StructureTower
import sourcemaps.runWithSourceMapSupport

/** The bot's version, printed in the greeting — the only thing that ties a match log back to a commit. */
const val BOT_VERSION = 1

@OptIn(ExperimentalJsExport::class)
@JsExport
fun loop() {
    runWithSourceMapSupport { PainAndGainAdvanced.tick() }
}

@JsPlainObject
external interface Cell {
    var x: Int
    var y: Int
}

fun cell(x: Int, y: Int): Position = Cell(x = x, y = y).unsafeCast<Position>()

fun bodyOf(c: Creep): String {
    val n = LinkedHashMap<String, Int>()
    for (p in c.body) {
        val ch = when (p.type) {
            MOVE -> "M"; ATTACK -> "A"; RANGED_ATTACK -> "R"; HEAL -> "H"; CARRY -> "C"; WORK -> "W"; TOUGH -> "T"; else -> "?"
        }
        n[ch] = (n[ch] ?: 0) + 1
    }
    return n.entries.joinToString("") { "${it.key}${it.value}" }
}

fun parts(c: Creep, type: Any): Int = c.body.count { it.type == type && it.hits > 0 }

fun armed(c: Creep) = parts(c, ATTACK) + parts(c, RANGED_ATTACK) + parts(c, HEAL) > 0

/**
 * v1 — a probe. Pain and Gain advanced opened on 27.09.2026 and its description leaves out the map, the bodies of the
 * sixteen, which flags carry the four towers and what the containers hold: tick 1 dumps all of it (and the terrain), and
 * the play is deliberately plain — the army walks as one group, takes the nearest flag it does not own, fights what comes
 * within reach, and whoever has CARRY feeds a tower of ours from the nearest container.
 */
object PainAndGainAdvanced {
    private var ourScore = 0
    private var theirScore = 0

    fun tick() {
        val t = getTicks()
        val creeps = getObjectsByPrototype(Creep::class).filter { it.exists }
        val mine = creeps.filter { it.my }
        val theirs = creeps.filter { !it.my }
        val flags = getObjectsByPrototype(ScoreFlag::class).filter { it.exists }
        val towers = getObjectsByPrototype(StructureTower::class).filter { it.exists }
        val containers = getObjectsByPrototype(StructureContainer::class).filter { it.exists }
        if (t == 1) probe(mine, theirs, flags, towers, containers)

        for (f in flags) {
            if (f.my == true) ourScore += f.scorePerTick
            if (f.my == false) theirScore += f.scorePerTick
        }

        towersAct(towers, mine, theirs)
        haul(mine, towers, containers)
        army(mine, theirs, flags)

        if (t % 100 == 0 || t == 2) {
            val ours = flags.filter { it.my == true }.joinToString(",") { "${it.x}:${it.y}" }
            val his = flags.filter { it.my == false }.joinToString(",") { "${it.x}:${it.y}" }
            val tw = towers.joinToString(" ") { "(${it.x},${it.y})${own(it.my)}e${it.store[RESOURCE_ENERGY] ?: 0}" }
            println("t=$t score=$ourScore:$theirScore rate=${flags.filter { it.my == true }.sumOf { it.scorePerTick }}:" +
                "${flags.filter { it.my == false }.sumOf { it.scorePerTick }} army=${mine.size}:${theirs.size} " +
                "hits=${mine.sumOf { it.hits }}:${theirs.sumOf { it.hits }} flags=[$ours]|[$his] towers=$tw " +
                "effects=${mine.firstOrNull()?.effects?.let { JSON.stringify(it) } ?: "-"} cpu=${getCpuTime() / 1_000_000}")
        }
    }

    private fun own(my: Boolean?) = if (my == true) "o" else if (my == false) "e" else "n"   // a neutral owner is `undefined`, which a `when` null branch does not catch

    private fun probe(mine: List<Creep>, theirs: List<Creep>, flags: List<ScoreFlag>, towers: List<StructureTower>, containers: List<StructureContainer>) {
        println("hello season4 pain-and-gain-advanced v$BOT_VERSION: ${arenaInfo.season} - ${arenaInfo.name} level=${arenaInfo.level} " +
            "ticksLimit=${arenaInfo.ticksLimit} TICKS_LIMIT=$TICKS_LIMIT MAX_SCORE_PER_TICK=$MAX_SCORE_PER_TICK")
        println("tuning: probe")
        println("flagtypes: ${JSON.stringify(FLAG_TYPES)}")
        println("consts: TOWER_RANGE=$TOWER_RANGE TOWER_POWER_ATTACK=$TOWER_POWER_ATTACK TOWER_POWER_HEAL=$TOWER_POWER_HEAL " +
            "TOWER_OPTIMAL_RANGE=$TOWER_OPTIMAL_RANGE TOWER_FALLOFF_RANGE=$TOWER_FALLOFF_RANGE TOWER_FALLOFF=$TOWER_FALLOFF " +
            "TOWER_COOLDOWN=$TOWER_COOLDOWN TOWER_CAPACITY=$TOWER_CAPACITY TOWER_ENERGY_COST=$TOWER_ENERGY_COST TOWER_HITS=$TOWER_HITS " +
            "ATTACK_POWER=$ATTACK_POWER RANGED_ATTACK_POWER=$RANGED_ATTACK_POWER HEAL_POWER=$HEAL_POWER RANGED_HEAL_POWER=$RANGED_HEAL_POWER " +
            "BODYPART_HITS=$BODYPART_HITS CARRY_CAPACITY=$CARRY_CAPACITY")
        for (f in flags) println("flag: (${f.x},${f.y}) ${f.effectType} ${f.scorePerTick} ${own(f.my)} id=${f.id}")
        for (tw in towers) println("tower: (${tw.x},${tw.y}) ${own(tw.my)} e=${tw.store[RESOURCE_ENERGY] ?: 0}/${tw.store.getCapacity(RESOURCE_ENERGY)} " +
            "hits=${tw.hits}/${tw.hitsMax} cd=${tw.cooldown} id=${tw.id}")
        for (c in containers) println("container: (${c.x},${c.y}) e=${c.store[RESOURCE_ENERGY] ?: 0} hits=${c.hits} id=${c.id}")
        for (c in mine) println("mine: (${c.x},${c.y}) ${bodyOf(c)} hits=${c.hits} id=${c.id}")
        for (c in theirs) println("theirs: (${c.x},${c.y}) ${bodyOf(c)} hits=${c.hits} id=${c.id}")
        val kinds = HashMap<String, Int>()
        for (o in getObjects()) {
            val name = o.asDynamic().constructor.name as String
            kinds[name] = (kinds[name] ?: 0) + 1
        }
        println("objects: ${kinds.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }}")
        val sb = StringBuilder()
        for (y in 0 until 100) {
            sb.clear()
            for (x in 0 until 100) {
                val tr = getTerrainAt(cell(x, y))
                sb.append(when (tr) { TERRAIN_WALL -> '#'; TERRAIN_SWAMP -> '~'; else -> '.' })
            }
            println("map ${y.toString().padStart(2, '0')} $sb")
        }
    }

    private fun towersAct(towers: List<StructureTower>, mine: List<Creep>, theirs: List<Creep>) {
        for (tw in towers) {
            if (tw.my != true || (tw.store[RESOURCE_ENERGY] ?: 0) < 10 || tw.cooldown > 0) continue
            val foe = theirs.filter { getRange(tw, it) <= 20 }.minByOrNull { getRange(tw, it) * 10000 + it.hits }
            if (foe != null) { tw.attack(foe); continue }
            val hurt = mine.filter { it.hits < it.hitsMax && getRange(tw, it) <= 20 }.minByOrNull { it.hits - it.hitsMax }
            if (hurt != null) tw.heal(hurt)
        }
    }

    private val hauling = HashSet<String>()

    private fun haul(mine: List<Creep>, towers: List<StructureTower>, containers: List<StructureContainer>) {
        val ours = towers.filter { it.my == true && (it.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0 }
        for (c in mine) {
            if (parts(c, CARRY) == 0) continue
            if (ours.isEmpty()) { hauling.remove(c.id); continue }
            hauling.add(c.id)
            val carried = c.store[RESOURCE_ENERGY] ?: 0
            if (carried > 0) {
                val tw = ours.minByOrNull { getRange(c, it) }!!
                if (getRange(c, tw) <= 1) c.transfer(tw, RESOURCE_ENERGY) else c.moveTo(tw)
            } else {
                val src = containers.filter { (it.store[RESOURCE_ENERGY] ?: 0) > 0 }.minByOrNull { getRange(c, it) }
                if (src == null) { hauling.remove(c.id); continue }
                if (getRange(c, src) <= 1) c.withdraw(src, RESOURCE_ENERGY) else c.moveTo(src)
            }
        }
    }

    private fun army(mine: List<Creep>, theirs: List<Creep>, flags: List<ScoreFlag>) {
        val fighters = mine.filter { it.id !in hauling }
        if (fighters.isEmpty()) return
        val cx = fighters.sumOf { it.x } / fighters.size
        val cy = fighters.sumOf { it.y } / fighters.size
        val centre = cell(cx, cy)
        val nearFoe = theirs.filter { armed(it) }.minByOrNull { getRange(centre, it) }
        val goal: Position? = when {
            nearFoe != null && getRange(centre, nearFoe) <= 12 -> nearFoe
            else -> flags.filter { it.my != true }.minByOrNull { getRange(centre, it) }
                ?: theirs.minByOrNull { getRange(centre, it) }
        }
        for (c in fighters) {
            act(c, mine, theirs)
            if (c.fatigue > 0 || goal == null) continue
            val ahead = getRange(c, goal) + 3 < getRange(centre, goal) && getRange(c, centre) > 3
            if (ahead) continue
            val rangedOnly = parts(c, ATTACK) == 0 && parts(c, RANGED_ATTACK) > 0
            val foe = theirs.minByOrNull { getRange(c, it) }
            if (rangedOnly && foe != null && getRange(c, foe) <= 2) continue
            if (parts(c, ATTACK) + parts(c, RANGED_ATTACK) == 0 && parts(c, HEAL) > 0) {
                val charge = mine.filter { it.id != c.id && armed(it) && parts(it, HEAL) == 0 }.minByOrNull { getRange(c, it) }
                if (charge != null && getRange(charge, goal) < getRange(c, goal)) { c.moveTo(charge); continue }
            }
            c.moveTo(goal)
        }
    }

    private fun act(c: Creep, mine: List<Creep>, theirs: List<Creep>) {
        if (parts(c, ATTACK) > 0) {
            val foe = theirs.filter { getRange(c, it) <= 1 }.minByOrNull { it.hits }
            if (foe != null) c.attack(foe)
        }
        if (parts(c, RANGED_ATTACK) > 0) {
            val inReach = theirs.filter { getRange(c, it) <= 3 }
            val mass = inReach.sumOf { when (getRange(c, it)) { 0, 1 -> 10; 2 -> 4; else -> 1 }.toInt() }
            if (mass > 10) c.rangedMassAttack()
            else inReach.minByOrNull { it.hits }?.let { c.rangedAttack(it) }
        }
        if (parts(c, HEAL) > 0) {
            val near = mine.filter { it.hits < it.hitsMax && getRange(c, it) <= 1 }.minByOrNull { it.hits.toDouble() / it.hitsMax }
            if (near != null) { c.heal(near); return }
            val far = mine.filter { it.hits < it.hitsMax && getRange(c, it) <= 3 }.minByOrNull { it.hits.toDouble() / it.hitsMax }
            if (far != null) c.rangedHeal(far)
        }
    }
}
