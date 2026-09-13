package season4.painandgain

import screeps.api.ATTACK
import screeps.api.ATTACK_POWER
import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.CARRY_CAPACITY
import screeps.api.CostMatrix
import screeps.api.Creep
import screeps.api.EFF_ATTACK_MODIFIER
import screeps.api.EFF_DAMAGE_TAKEN_MODIFIER
import screeps.api.EFF_HEAL_MODIFIER
import screeps.api.EFF_RANGED_ATTACK_MODIFIER
import screeps.api.HEAL
import screeps.api.HEAL_POWER
import screeps.api.MOVE
import screeps.api.Position
import screeps.api.RANGED_ATTACK
import screeps.api.RANGED_ATTACK_POWER
import screeps.api.RANGED_HEAL_POWER
import screeps.api.RESOURCE_ENERGY
import screeps.api.SearchGoal
import screeps.api.SearchPathOptions
import screeps.api.TERRAIN_SWAMP
import screeps.api.TERRAIN_WALL
import screeps.api.TOUGH
import screeps.api.WORK
import screeps.api.arenaInfo
import screeps.api.get
import screeps.api.getObjectsByPrototype
import screeps.api.getRange
import screeps.api.getTerrainAt
import screeps.api.getTicks
import screeps.api.getCpuTime
import screeps.api.searchPath
import screeps.api.season4.FLAG_TYPES
import screeps.api.season4.MAX_SCORE_PER_TICK
import screeps.api.season4.ScoreFlag
import screeps.api.season4.TICKS_LIMIT
import screeps.api.structures.StructureRampart
import screeps.api.structures.StructureSpawn
import screeps.api.structures.StructureWall
import sourcemaps.runWithSourceMapSupport
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import season4.painandgain.PainAndGain.Posture
import season4.painandgain.PainAndGain.Intent
import season4.painandgain.PainAndGain.CmdMode
import season4.painandgain.PainAndGain.Shooter
import season4.painandgain.PainAndGain.Objective
import season4.painandgain.PainAndGain.ChaseSample
import season4.painandgain.PainAndGain.FightCell
import season4.painandgain.PainAndGain.HypoMods

/**
 * ПРИБОРЫ (v255, этап 10 переработки; план — docs/pain-and-gain-rework.md, раздел 1, «Instruments»). Замеры CPU по фазам
 * тика (`cpuMark`, `cpuSummary`), зонд первого тика (`probe`), печать тел и флагов (`logBodies`, `bodySummary`,
 * `flagsSummary`), дамп карты (`captureMapMarks`, `logMap`) и строка застрявших (`logStuck`). Перенесены из объекта
 * `PainAndGain` дословно расширениями; счётчики, которые они печатают, пока живут в объекте.
 */

internal fun PainAndGain.cpuMs(): Double = try { getCpuTime() / 1_000_000.0 } catch (e: Throwable) { 0.0 }

internal fun PainAndGain.cpuMark(phase: String) { cpuPhases.add(phase to cpuMs()) }

internal fun PainAndGain.cpuSummary() {
    val ms = cpuMs()
    if (ms > cpuMaxMs) { cpuMaxMs = ms; cpuMaxTick = getTicks() }
    if (ms > CPU_SLOW_MS) cpuSlowTicks++
    if (DEBUG_LOG && (getTicks() <= 3 || ms > CPU_SLOW_MS || getTicks() % 100 == 0)) {
        var prev = 0.0
        val parts = cpuPhases.joinToString(" ") { (ph, at) -> val d = at - prev; prev = at; "$ph=${(d * 10).toInt() / 10.0}" }
        println("cpu t=${getTicks()} total=${(ms * 10).toInt() / 10.0}ms: $parts")
    }
    cpuPhases.clear()
    if (DEBUG_LOG && getTicks() % 100 == 0) {
        println("cpu t=${getTicks()}: max=${(cpuMaxMs * 10).toInt() / 10.0}ms at t=$cpuMaxTick slow(>${CPU_SLOW_MS.toInt()}ms)=$cpuSlowTicks limit=${arenaInfo.cpuTimeLimit / 1_000_000}/${arenaInfo.cpuTimeLimitFirstTick / 1_000_000}ms")
        cpuMaxMs = 0.0; cpuMaxTick = 0; cpuSlowTicks = 0
    }
}

// ==================== зонд ====================

/** Число из константы арены (внешнее объявление может оказаться undefined — тогда запасное). */
internal fun PainAndGain.num(v: dynamic, fallback: Double): Double = if (jsTypeOf(v) == "number") v.unsafeCast<Double>() else fallback

internal fun PainAndGain.probe(flags: List<FlagInfo>, myCreeps: List<Creep>, enemyCreeps: List<Creep>, home: Position, enemyHome: Position) {
    println(
        "hello season4 pain-and-gain $BOT_VERSION: ${arenaInfo.season} - ${arenaInfo.name} level=${arenaInfo.level} " +
            "ticksLimit=${arenaInfo.ticksLimit} cpu=${arenaInfo.cpuTimeLimit}/${arenaInfo.cpuTimeLimitFirstTick}"
    )
    // подпись сборки: по ней видно, какая версия играет, даже если строку версии забыли поднять
    println(
        "tuning: pushX=$PUSH_EXCHANGE parity=$PARITY_FLOOR/$CAPTURE_FLOOR/$PARITY_FLOOR_STALLED push=$PUSH_RATIO/$PUSH_RATIO_BEHIND " +
            "stall=$STALL_TICKS/$STALL_PICKET/$STALL_DAMAGE/$STALL_COOLDOWN march=$MARCH_STALL_TICKS rush=$APPROACH_RUSH/$EVADE_EQUAL_RATIO " +
            "mass=$MASS_RANGE leash=$LEASH_RANGE meleeHold=$MELEE_HOLD_RANGE press=$PRESS_RANGE/$PRESS_PACK/$PRESS_PATIENCE/$PRESS_CLOSING/$PRESS_GIVEUP healer=$HEALER_VALUE keep=$KEEP_RANGE/$KEEP_PICKET/$KEEP_RELEASE"
    )
    println(
        "pain-and-gain: TICKS_LIMIT=${num(TICKS_LIMIT.asDynamic(), -1.0)} MAX_SCORE_PER_TICK=${num(MAX_SCORE_PER_TICK.asDynamic(), -1.0)} " +
            "FLAG_TYPES=${try { JSON.stringify(FLAG_TYPES) } catch (t: Throwable) { "?" }} " +
            "EFF: attack=$EFF_ATTACK_MODIFIER ranged=$EFF_RANGED_ATTACK_MODIFIER heal=$EFF_HEAL_MODIFIER taken=$EFF_DAMAGE_TAKEN_MODIFIER " +
            "power: R=$RANGED_ATTACK_POWER A=$ATTACK_POWER H=$HEAL_POWER/$RANGED_HEAL_POWER"
    )
    println("flags: " + flags.joinToString(" ") { "(${it.pos.x},${it.pos.y})${typeChar(it.type)}${it.score}my=${it.mine}" })
    println("home=(${home.x},${home.y}) enemyHome=(${enemyHome.x},${enemyHome.y})")
    println("my creeps (${myCreeps.size}): " + myCreeps.joinToString(" ") { "${it.id}(${it.x},${it.y})${bodySummary(it)}${if (it.spawning) "S" else ""}" })
    println("enemy creeps (${enemyCreeps.size}): " + enemyCreeps.joinToString(" ") { "${it.id}(${it.x},${it.y})${bodySummary(it)}" })
    val walls = getObjectsByPrototype(StructureWall::class).size
    val ramparts = getObjectsByPrototype(StructureRampart::class).size
    val spawns = getObjectsByPrototype(StructureSpawn::class).size
    println("structures: walls=$walls ramparts=$ramparts spawns=$spawns")
    // тела целиком (с порядком частей) — по ним видно, что выдано, и что умрёт первым
    for (c in myCreeps) println("body ${c.id}: " + c.body.joinToString("") { partChar(it.type).toString() })
    for (c in enemyCreeps) println("enemy body ${c.id}: " + c.body.joinToString("") { partChar(it.type).toString() })
}

internal fun PainAndGain.partChar(type: BodyPartType): Char = when (type) {
    TOUGH -> 'T'; MOVE -> 'M'; RANGED_ATTACK -> 'R'; ATTACK -> 'A'; HEAL -> 'H'; CARRY -> 'C'; WORK -> 'W'
}

internal fun PainAndGain.typeChar(type: String): Char = when (type) {
    EFF_ATTACK_MODIFIER -> 'A'
    EFF_RANGED_ATTACK_MODIFIER -> 'R'
    EFF_HEAL_MODIFIER -> 'H'
    EFF_DAMAGE_TAKEN_MODIFIER -> 'D'
    else -> '?'
}

internal fun PainAndGain.flagsSummary(flags: List<FlagInfo>): String =
    flags.joinToString(",") { "${typeChar(it.type)}${it.score}${if (it.ours) "+" else if (it.theirs) "-" else "0"}${if (it.occupant != null) (if (it.occupant.my) "s" else "e") else ""}${if (it.guards.isNotEmpty()) "g${it.guards.size}" else ""}" }

/** Состав армий (живые части) — при каждом изменении: видно потери и покалеченных. */
internal fun PainAndGain.logBodies(myCreeps: List<Creep>, enemyCreeps: List<Creep>) {
    if (!DEBUG_LOG) return
    val key = myCreeps.joinToString(",") { bodySummary(it) } + "|" + enemyCreeps.joinToString(",") { bodySummary(it) }
    if (key == lastBodiesKey) return
    lastBodiesKey = key
    println("armies t=${getTicks()}: ours(${myCreeps.size}) " + myCreeps.joinToString(" ") { "${bodySummary(it)}h=${it.hits}" } +
        " | enemy(${enemyCreeps.size}) " + enemyCreeps.joinToString(" ") { "(${it.x},${it.y})${bodySummary(it)}h=${it.hits}" })
}

/** Сводка тела: T10M4R3H1 (только живые части). */
internal fun PainAndGain.bodySummary(creep: Creep): String {
    val order = listOf(TOUGH to 'T', MOVE to 'M', RANGED_ATTACK to 'R', ATTACK to 'A', HEAL to 'H', CARRY to 'C', WORK to 'W')
    val sb = StringBuilder()
    for ((type, ch) in order) {
        val n = creep.body.count { it.type == type && it.hits > 0 }
        if (n > 0) sb.append(ch).append(n)
    }
    return sb.toString()
}

// ==================== диагностика ====================

internal fun PainAndGain.logStuck(active: List<Creep>, enemyCreeps: List<Creep>) {
    for (c in active) {
        if (TrafficManager.stuckFor(c.id) != TrafficManager.STUCK_TICKS) continue
        val want = TrafficManager.lastDesiredOf(c.id)
        val occ = want?.let { w -> (active + enemyCreeps).firstOrNull { it.x * 100 + it.y == w } }
        val occWant = occ?.let { TrafficManager.lastDesiredOf(it.id) }
        println("stuck ${c.id} at (${c.x},${c.y}) fatigue=${c.fatigue} wants=${want?.let { "(${it / 100},${it % 100})" }} " +
            "occ=${occ?.let { "${it.id} my=${it.my} fatigue=${it.fatigue} ${bodySummary(it)} wants=${occWant?.let { w -> "(${w / 100},${w % 100})" } ?: "-"}" } ?: "free"}")
    }
}

/** ASCII-карта один раз: '#' стена, '~' болото, '.' равнина, 'F' флаг, 'm' наш крип, 'e' вражеский. */
internal fun PainAndGain.captureMapMarks(flags: List<FlagInfo>, myCreeps: List<Creep>, enemyCreeps: List<Creep>) {
    val m = HashMap<Int, Char>()
    fun mark(x: Int, y: Int, c: Char) { m[x * 100 + y] = c }
    getObjectsByPrototype(StructureWall::class).forEach { mark(it.x, it.y, '#') }
    getObjectsByPrototype(StructureRampart::class).forEach { mark(it.x, it.y, 'R') }
    getObjectsByPrototype(StructureSpawn::class).forEach { mark(it.x, it.y, if (it.my == true) 'M' else 'E') }
    enemyCreeps.forEach { mark(it.x, it.y, 'e') }
    myCreeps.forEach { mark(it.x, it.y, 'm') }
    flags.forEach { mark(it.pos.x, it.pos.y, 'F') }
    mapMarks = m
}

/** Дамп карты — четырьмя частями по 25 строк на тиках 3–6, одной строкой каждая: сто println на холодном первом
 *  тике упирались в лимит (матч 17), а весь дамп вторым тиком — в бюджет 100 мс (матч 18). Метки крипов сняты на
 *  первом тике (см. captureMapMarks), чтобы стенд получал стартовые клетки. */
internal fun PainAndGain.logMap(fromRow: Int) {
    val marks = mapMarks ?: return
    val out = StringBuilder(if (fromRow == 0) "=== MAP (rows y=0..99, cols x=0..99) ===" else "")
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
