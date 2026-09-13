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
import kotlin.reflect.*

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

/** АУДИТ ПРИКАЗОВ КОМАНДИРА (v256, этап 10; сегмент runArmy): одна клетка — двоим (clash), исполнение приказов прошлого тика (obey, lost=stuck/foe/fat/else), дальние приказы, запись orderPrev. Перенесено дословно. */
internal class OrderAuditIn(
    val enemyCreeps: List<Creep>,
    val commandArmy: List<Creep>,
)

internal class OrderAuditOut(
)

internal fun PainAndGain.orderAudit(ctx: Ctx, seg: OrderAuditIn): OrderAuditOut = with(seg) {
    val seen = HashMap<Int, Int>()
    commandOf.values.forEach { p -> seen[p.x * 100 + p.y] = (seen[p.x * 100 + p.y] ?: 0) + 1 }
    val dup = seen.values.count { it > 1 }
    orderClash += dup
    // ГАРАНТИЯ, А НЕ НАБЛЮДЕНИЕ (v176, оператор: «не должно быть такого, что по приказам командира в одну
    // клетку собрались двое»). Раздача держит своё множество занятых, но источников приказа несколько — бой,
    // гонка, марш, хранители, отход, — и на стыке коллизия всё же случалась (одна на 431 приказ, режим боя).
    // Здесь она снимается: клетка остаётся за первым, второй теряет приказ и идёт по общим правилам
    if (dup > 0) {
        val used = HashSet<Int>()
        val drop = ArrayList<String>()
        for ((id, p) in commandOf) { val k = p.x * 100 + p.y; if (!used.add(k)) drop.add(id) }
        drop.forEach { commandOf.remove(it) }
    }
    if (dup > 0 && DEBUG_LOG) {
        val where = seen.entries.firstOrNull { it.value > 1 }?.key ?: 0
        val who = commandOf.filterValues { it.x * 100 + it.y == where }.keys.joinToString(",")
        println("clash t=${getTicks()}: mode=$cmdMode cell=(${where / 100},${where % 100}) who=$who")
    }
    Memory.orderPrev.forEach { (id, cell) ->
        // ...и захватчик из аудита исключается: его приказ — ФЛАГ, а не клетка, и ведёт его свой цикл;
        // считать его ослушником было бы неверно (v173)
        if (id in Memory.cmdDetach) return@forEach
        val c = commandArmy.firstOrNull { it.id == id } ?: return@forEach
        orderAuditN++
        // ...и ПРИКАЗ В ДВУХ ШАГАХ ИСПОЛНЕН, ЕСЛИ КРИП СТАЛ БЛИЖЕ (v184). Прибор сверял клетку крипа с
        // НАЗНАЧЕННОЙ и только с ней, а строй (`commandBrace`) назначает место в строю за несколько клеток —
        // такой приказ не мог быть засчитан НИКОГДА, и едва строй заработал, исполнение упало со 99 % до 62 %
        // при том, что крипы шли туда, куда велено. Из-за этого я успел записать в дефекты то, чего не было,
        // и починить не тот код (см. USE_BRACE_STEPS). По всей серии v183 «ушёл в другую клетку» набрал
        // 2 022 случая из 39 245 — почти все они этой природы
        val far = 
            (orderDist[id] ?: 0) > 1 && maxOf(abs(c.x - cell.x), abs(c.y - cell.y)) < (orderDist[id] ?: 0)
        if ((c.x == cell.x && c.y == cell.y) || far) orderAuditOk++
        else {
            // ...и КУДА делись остальные (v170): приказ был «стой», а крип ушёл; крип не двинулся
            // вовсе; двинулся, но в другую клетку; или не мог двигаться от усталости
            val here = orderWas[id]
            when {
                cell.x == here?.first && cell.y == here.second -> lostStay++
                // ...клетку мог занять ВРАГ: он ходит одновременно с нами, и его шаг делает приказ
                // неисполнимым задним числом — это неустранимо в принципе, и считать надо отдельно (v175)
                ctx.enemyCreeps.any { e -> e.x == cell.x && e.y == cell.y } -> lostEnemy++
                c.x == here?.first && c.y == here.second -> lostStuck++
                (orderFatigue[id] ?: 0) > 0 -> lostFatigue++
                else -> lostElsewhere++
            }
        }
        // ...и отдельно: СТАЛ ЛИ БЛИЖЕ к назначенной клетке (приказ бывает в двух шагах, за тик не дойти)
        val wasD = orderDist[id] ?: 99
        val nowD = maxOf(abs(c.x - cell.x), abs(c.y - cell.y))
        if (nowD < wasD) orderAuditCloser++
        // ...и ДЕРЖИТСЯ ЛИ приказ: та же клетка, что была назначена в прошлый тик
        if (commandOf[id]?.let { it.x == cell.x && it.y == cell.y } == true) orderAuditSame++
    }
    // ...и сколько приказов вообще достижимо за тик: клетка в двух шагах не может быть занята сразу,
    // и доля исполнения ограничена этим по построению (v170)
    commandOf.forEach { (id, p) ->
        val c = commandArmy.firstOrNull { it.id == id } ?: return@forEach
        if (maxOf(abs(c.x - p.x), abs(c.y - p.y)) > 1) orderFar++
    }
    orderWas.clear(); orderFatigue.clear()
    commandArmy.forEach { c -> orderWas[c.id] = c.x to c.y; orderFatigue[c.id] = c.fatigue }
    orderDist.clear()
    commandOf.forEach { (id, p) ->
        val c = commandArmy.firstOrNull { it.id == id }
        if (c != null) orderDist[id] = maxOf(abs(c.x - p.x), abs(c.y - p.y))
    }
    Memory.orderPrev.clear()
    commandOf.forEach { (id, p) -> Memory.orderPrev[id] = p }
    // потеря за прошлый тик по всем — ДО цикла: lastHits обновляется в конце каждой итерации, и для уже обработанных она была бы нулём
    OrderAuditOut(
    )
}

/** ПЕЧАТЬ ТИКА (v257, этап 10; хвост tickBody): строка застрявших, строка t= со всеми приборами раз в LOG_EVERY тиков, перепись rung / tac, поля fld. Перенесено дословно. */
internal class PrintTickIn(
    val myCreeps: List<Creep>,
    val enemyCreeps: List<Creep>,
    val active: List<Creep>,
    val combatEnemies: List<Creep>,
    val flags: List<FlagInfo>,
    val wounded: (Creep) -> Boolean,
    val army: List<Creep>,
    val runners: List<Creep>,
    val passiveEnemy: Boolean,
    val ourCentroid: Position,
    val enemyCentroid: Position?,
    val armedCentroid: Position,
)

internal class PrintTickOut(
)

internal fun PainAndGain.printTick(ctx: Ctx, seg: PrintTickIn): PrintTickOut = with(seg) {
    if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
        println("bfs t=${getTicks()} max=$bfsMaxTick cost=$bfsMaxCost")
        bfsMaxTick = 0
        bfsMaxCost = 0.0
    }
    if (DEBUG_LOG) logStuck(active, enemyCreeps)
    if (DEBUG_VISUALS) InfluenceMap.drawDebug(army, myCreeps, enemyCreeps)

    if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
        val ours = ourPowerOf(army, combatEnemies)
        val theirs = enemyPowerOf(combatEnemies, army)
        println(
            "t=${getTicks()} army=${army.size} runners=${runners.size}(${Memory.detachedIds.size} detached) enemies=${enemyCreeps.size}/${combatEnemies.size} " +
            "reach=${army.count { hasWeapon(it) && hasRanged(it) && combatEnemies.any { e -> getRange(it, e) <= RANGED_RANGE } }}/${army.count { hasWeapon(it) && hasRanged(it) }} " +
            // разброс строя (v191): диаметр группы стрелков и сколько вооружённых стоят дальше поводка от своего
            // центра. Реплеи говорят, что стирание приходит на диаметре 21, а пат — на диаметре 3
            "spread=${army.filter { hasWeapon(it) && hasRanged(it) }.let { sh -> if (sh.size > 1) sh.maxOf { a -> sh.maxOf { b -> getRange(a, b) } } else 0 }}/${army.count { hasWeapon(it) && getRange(it, armedCentroid) > LEASH_RANGE }} " +
            // ...и отдельно ЛЕКАРИ за поводком (v202): именно они разъезжались, а прибор их не считал вовсе
            "hfar=${army.count { !hasWeapon(it) && hasHeal(it) && getRange(it, armedCentroid) > LEASH_RANGE }}/${army.count { !hasWeapon(it) && hasHeal(it) }} " +
            // ЛЕКАРИ СУДЯТСЯ ВЫЖИВАНИЕМ, А НЕ ДОСТАВЛЕННЫМ ЛЕЧЕНИЕМ (этап 7): v202 поднял лечение и дал 0:3.
            // Тело лекаря — h6m6, лечащие части СПЕРЕДИ, поэтому урон уничтожает именно их и первыми; замер
            // разгрома 3d97c4 говорит, что его лекари сохраняют 100 % лечащих частей, наши 8 %. hcov — доля
            // нужды, покрытая назначенными клетками: прибор раздачи, а не исхода
            "hparts=${myCreeps.filter { hasHeal(it) }.sumOf { c -> c.body.count { it.type == HEAL && it.hits > 0 } }}/${myCreeps.filter { hasHeal(it) }.sumOf { c -> c.body.count { it.type == HEAL } }} " +
            "ehparts=${enemyCreeps.filter { hasHeal(it) }.sumOf { c -> c.body.count { it.type == HEAL && it.hits > 0 } }}/${enemyCreeps.filter { hasHeal(it) }.sumOf { c -> c.body.count { it.type == HEAL } }} " +
            "hcov=${InfluenceMap.healCoverage().let { (left, total) -> "${(total - left).toInt()}/${total.toInt()}" }} " +
            "hulk=${disarmedFoe.size} hulkreach=$hulkInReach/$hulkTicks revived=$hulkRevived chase=${Memory.chaseOf.size}/$chaseTicks kills=$chaseKills " +
            "capgate=${capBlocked.values.sum()}/$capOffered cap=" + capBlocked.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" } +
            " poised=$poisedTicks/$poisedAll edge=$edgeSpot/$edgeAll capopp=$capOppSum/$capAllSum" +
            " scout=$scoutShots/$scoutReach/$scoutTicks spotm=$spotMeleeTicks spothold=$spotHoldNew/$spotHoldAll sym=$symCore/$symFree " +
            "split=$splitFight/$splitAll recall=$recalled/$fightTicksNow healgap=$healGap/$healGapN nomedic=$noMedic/$healGapN flip=$aimFlips/$aimTicks aggro=$dangerBlind/$dangerBlindFar/$dangerMoves pushheld=$pushHeldTicks/$pushTicks lethal=$lethalHits/$lethalCells ledgerw=$ledgerWindow/$ourLostWindow/$hisLostWindow breakoff=$breakOffSplit/$breakOffN " +
            "race=${race100.ifEmpty { "-" }}/${race200.ifEmpty { "-" }} poisedcost=$poisedCost objnone=${objNone.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" }}/$objAll " +
            "objdrop=${objDrop.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" }}/$objDropN budget=$budgetSum/$budgetTicks " +
            "runner=${runnerMode.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" }}/$runnerModeN " +
            "cmdwhy=${cmdWhy.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" }}/$cmdWhyN " +
            "conc=$concSum/$concTicks concall=$concAll/$concAllTicks concmax=$concMax concfan=$fanShots/$fireShots " +
            "lostrace=$lostRaceOpened/$lostRaceOffers gather=$gatherSpread/$gatherHold close3=$closeHeld/$closeTicks guard=$guardFired/$guardTicks " +
            // приборы v221: тёплый контакт (пары к USE_FIGHT_BY_LEDGER), концентрация и цель мили, погоня за
            // кайтером, сбор в бою, и стрелки обеих сторон — «кто теряет стрелков первым», что реплей показал, а
            // консоль не показывала (имя `guns=` занято прибором v200)
            "warm=$warmTicks/$warmContact warmann=$warmAnn/$warmAnnAll warmhold=$warmHold/$warmAnn warmcmd=$warmCmd/$warmCmdAll warmfight=$warmFight/$warmFightAll warmcap=$warmCap/$warmCapAll " +
            "mconc=$mconcAll/$mconcTicks mconcmax=$mconcMax mpack=$mpackHit/$mpackAll pack=$packHeld/$packTicks mpackon=$mpackOnHit/$mpackOn kchase=$kchaseTicks/$kchaseAnn kveto=$kvetoHit/$kvetoAll gathera=$gatherAnn/$gatherAnnAll " +
            "annempty=${annEmpty.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" }}/$annEmptyAll " +
            "shooters=${army.count { hasWeapon(it) && hasRanged(it) }}/${combatEnemies.count { hasRanged(it) }} abort=$abortTicks/$abortEntries ovw=${Executor.ovwContact}/${Executor.ovwRanged} conf=${Arbiter.confReach}/${Arbiter.confFatigue} rtr=$rtrRemoved/$rtrOld/$rtrAdded mquiet=$mquietMoved/$mquietAll/${mquietGain.toInt()} mquietc=$cmdQuietMoved/$cmdQuietAll maj=$majOpened/$majOffers surv=$survTicks/$survLead/$survContact/$survFights adr=$adrN/${(adrE / maxOf(adrN, 1)).toInt()}/${(adrT / maxOf(adrN, 1)).toInt()}/$adrSame fhl=$fhlChosen/$fhlAvail mrush=$rushByArrival/$rushSignalAll/$massArrivalAdded zlb=$zlbTicks/$zlbZero hwall=$hwallTicks/$hwallVictimTicks hwallh=$hwallHeals/$hwallHealsAll hwalla=$hwallAddr/$hwallVictimTicks hwallp=$hwallPredA/$hwallPredL/$hwallPredN postc=$postContest/$postAll rot=$rotOut mdir=$marchFlow/$marchAll/$marchFlip hfull=$hfullN/$hfullAll hover=$hoverSum/$hdelivSum hswap=$hswapN hexp=$hexpN/$hexpAll hlost=$hlostSum hatm=$hatmN/$hatmAll hatmc=$hatmCmd/$hatmCmdAll hpick=$hpN/$hpAdj/$hpAvail/$hpGate dh=${hpDelta.joinToString(",") { (it / maxOf(hpAvail, 1)).toInt().toString() }} " +
            "retr=$retrTicks/$retrWithPoint/$retrUnderFire standfire=$standFire/$standTicks outmw=$outmTicks/$outmRetreat " +
                "score=${ourScore.toInt()}/${enemyScore.toInt()} rate=$ourRate/$enemyRate behind=$behindOnScore passive=$passiveEnemy flags=${flagsSummary(flags)} " +
                "obey=$orderAuditOk/$orderAuditN branch=$orderBranch fled=$orderFled clash=$orderClash lost=stay$lostStay/stuck$lostStuck/foe$lostEnemy/fat$lostFatigue/else$lostElsewhere kite=$kiteNow massed=$kiteMassed plan=$planStrict/$planLoose cmd=${commandOf.size}/$cmdTicks:$cmdBlocked mode=$cmdMode disp=$dispNow evt=$stateEventTicks fire=${fireOf.size} posture=$posture obj=${objectiveFlagId?.let { id -> flags.firstOrNull { it.id == id }?.let { "(${it.pos.x},${it.pos.y})" } } ?: "-"} hunt=$huntingThreat rush=$unflaggedRushNow " +
                "weak=$outmatchedTicks pat=$stalemateTicks/$patMax strip=$stripTicks touch=${(touchShare * 100).toInt()}/${(touchMin * 100).toInt()}/${(hisTouchShare * 100).toInt()} out=$outOfFireTicks back=$meleeBackTicks guns=$planGunsIn/$planGunsAll mheal=$planMeleeHealed/$planMeleeAll hline=$planHealBehind/$planHealAll our=${ours.toInt()} enemy=${theirs.toInt()} ledger=${enemyDamageTaken - ourDamageTaken} wounded=${army.count { !hasWeapon(it) && !hasHeal(it) }} hits=${army.sumOf { it.hits }}/${army.sumOf { it.hitsMax }} enemyHits=${combatEnemies.sumOf { it.hits }}/${combatEnemies.sumOf { it.hitsMax }} " +
                "centroid=(${ourCentroid.x},${ourCentroid.y}) enemyCentroid=${enemyCentroid?.let { "(${it.x},${it.y})" } ?: "-"}"
        )
        // ПЕРЕПИСЬ (v203): только ненулевые ветки, накопительно за матч. Сумма stepCount обязана равняться
        // размеру армии, умноженному на число тиков, — если не равна, перепись врёт, и всё на ней построенное тоже
        println("rung t=${getTicks()}: why=" + rungCount.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" } +
            " step=" + stepCount.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" } +
            " pass=" + passCount.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" } +
            " sum=${stepCount.values.sum()}")
        // ...и ТА ЖЕ ПЕРЕПИСЬ ПО ПРЕДЛОЖЕНИЯМ (v252, этап 9): «задание отряда . терм» и приоритет; сумма обязана совпасть с
        // суммой rung — оба счёта растут один раз на крипа армии за тик
        println("tac t=${getTicks()}: mt=" + tacCount.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" } +
            " prio=" + prioCount.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" } +
            " sum=${prioCount.values.sum()}")
        // ПОЛЯ: пики печатаются, чтобы обнулившееся поле было ВИДНО — прибор, умеющий сказать только
        // «поле построено», прибором не является
        if (FIELD_LOG) println("fld t=${getTicks()}: hdbf=${InfluenceMap.healDebuffStats()}" +
            " eM=${InfluenceMap.fieldPeak(InfluenceMap.eMelee).toInt()} eR=${InfluenceMap.fieldPeak(InfluenceMap.eRanged).toInt()}" +
            " eH=${InfluenceMap.fieldPeak(InfluenceMap.eHeal).toInt()} aM=${InfluenceMap.fieldPeak(InfluenceMap.aMelee).toInt()}" +
            " aR=${InfluenceMap.fieldPeak(InfluenceMap.aRanged).toInt()} aH=${InfluenceMap.fieldPeak(InfluenceMap.aHeal).toInt()}" +
            " eF=${InfluenceMap.fieldPeak(InfluenceMap.eFire).toInt()} atM=${InfluenceMap.fieldPeak(InfluenceMap.attMelee).toInt()}" +
            " atR=${InfluenceMap.fieldPeak(InfluenceMap.attRanged).toInt()} atH=${InfluenceMap.fieldPeak(InfluenceMap.attHeal).toInt()}" +
            // ЦЕЛЬ (этап 5): затравок в очаге, перестроек против удержаний очага, и — главное — доля решений
            // раздачи, которые слагаемое цели ИЗМЕНИЛО. flips=0 за сто тиков есть операционное определение
            // мёртвого кода
            " seeds=${goalSeeds.size} goal=(${goalCx},${goalCy}) rebuild=$goalRebuilds/$goalHolds flips=$goalFlips/$goalDecisions" +
            // ВОРОТА (этап 6): на каком пороге выживания крип нашёл клетку. gate5..gate1 — уровни лестницы,
            // fell — сколько раз клетки не нашлось даже при пороге в один тик и сработал общий добор. Это
            // посчитанная версия прежнего МОЛЧАЛИВОГО провала требования
            " gate=${gateLevels.drop(1).take(5).joinToString("/")} fell=$gateFell" +
            " intent=" + Memory.intentHist.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" })
        concSum = 0; concTicks = 0
        if (getTicks() % (LOG_EVERY * 10) == 0) println(TrafficManager.audit())
    }
    PrintTickOut(
    )
}

/** Раненый уступает дорогу всем: его место — за лекарями, а не между ними и строем. */

// ---------- отладка ----------
// версия играющей сборки — первой строкой лога матча: по ней матч привязывается к коду (см. правила сессий)
internal const val BOT_VERSION = "v258"

/** Печать приборов полей влияния. Сверка со ЗНАЧЕНИЯМИ (chk против прямого пересчёта по крипам,
 *  fldcmp против переносимого incNext) сняла свой вопрос и удалена на этапе 8: 0 из 304 950 клеток и
 *  0 из 22 355 494 сверок по 135 сценариям. Осталось то, что отвечает на живой вопрос, — пики полей:
 *  обнулившееся поле обязано быть ВИДНО, а не тихо давать нули. */
internal const val FIELD_LOG = true

/** Выключено: отрисовка влияния — ~57 000 вызовов contribution за тик (13×13 клеток × 12 стрелков × 28 крипов),
 *  первый тик матча 7 вылетел по таймауту именно в drawDebug; журнал даёт всё, что нужно для разбора. */
internal const val DEBUG_VISUALS = false

internal const val CPU_SLOW_MS = 60.0   // a tick over this prints its phases (the limit is 100 ms; the first tick 1 000)
