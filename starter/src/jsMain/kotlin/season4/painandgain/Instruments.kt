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
import kotlin.reflect.*

/**
 * ПРИБОРЫ (v255, этап 10 переработки; план — docs/pain-and-gain-rework.md, раздел 1, «Instruments»). Замеры CPU по фазам
 * тика (`cpuMark`, `cpuSummary`), зонд первого тика (`probe`), печать тел и флагов (`logBodies`, `bodySummary`,
 * `flagsSummary`), дамп карты (`captureMapMarks`, `logMap`) и строка застрявших (`logStuck`). Перенесены из объекта
 * `PainAndGain` дословно расширениями; счётчики, которые они печатают, пока живут в объекте.
 */

/** «Зажатого бьём» (v264, pin=взято/возможностей/удержано/сверено): мили под приказом с клеткой вплотную к зажатому
 *  врагу в шаге; приказ, поставивший его туда; и был ли зажатый вплотную к нему на следующем тике. */
internal val pinOpp = Gauges.counter("pin", 1)
internal val pinOrd = Gauges.counter("pin")
internal val pinChk = Gauges.counter("pin", 3)
internal val pinHeld = Gauges.counter("pin", 2)
/** Знаменатель ehparts= (v265): наибольшее число частей HEAL у его живой армии за матч, то есть его исходные — армия без
 *  спавна. Прежде знаменатель брал только крипов с ЖИВЫМ лечением, и лекарь, раздетый целиком, выпадал из дроби вместе
 *  со своими частями: разбор стены лечения видел в логе 12/12 там, где его лечение потеряло треть. */
internal var ehpartsAll = 0
/** Встречи уходящего раненого с лекарём в раздаче командира (v276, rotfm=). */
internal val meetRot = Gauges.counter("meet", 2)
internal val meetNear = Gauges.counter("meet", 1)
internal val meetPlan = Gauges.counter("meet")
internal val meetDone = Gauges.counter("meet", 3)
internal val meetChk = Gauges.counter("meet", 4)
/** Режим пар (v298, gsafe=тиков в режиме/урон по группе за окно, fguard=бегуно-тиков охраны при флаге). */
/** Его флаги за окно и из них занятые его крипом (v302, sit=): режим пар против сидящего на флагах не включается. */
/** Хранители (v305, keep2=назначено/крип-тиков/снято: ядро/стая/сошёл). */
/** Ноги за фокусом (v268, ffoc=до/после/стрелков): стрелки под приказом при живом фокусе, у которых фокус в досягаемости с
 *  нынешней клетки и с клетки приказа. */
internal val ffocAll = Gauges.counter("ffoc", 2)
internal val ffocBefore = Gauges.counter("ffoc")
internal val ffocAfter = Gauges.counter("ffoc", 1)

internal fun cpuSummary() {
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

internal fun probe(flags: List<FlagInfo>, myCreeps: List<Creep>, enemyCreeps: List<Creep>, home: Position, enemyHome: Position) {
    println(
        "hello season4 pain-and-gain $BOT_VERSION: ${arenaInfo.season} - ${arenaInfo.name} level=${arenaInfo.level} " +
            "ticksLimit=${arenaInfo.ticksLimit} cpu=${arenaInfo.cpuTimeLimit}/${arenaInfo.cpuTimeLimitFirstTick}"
    )
    // подпись сборки: по ней видно, какая версия играет, даже если строку версии забыли поднять
    println(
        "tuning: pushX=$PUSH_EXCHANGE parity=$PARITY_FLOOR/$CAPTURE_FLOOR/$PARITY_FLOOR_STALLED push=$PUSH_RATIO/$PUSH_RATIO_BEHIND " +
            "stall=$STALL_TICKS/$STALL_PICKET/$STALL_DAMAGE/$STALL_COOLDOWN march=$MARCH_STALL_TICKS rush=$APPROACH_RUSH/$EVADE_EQUAL_RATIO " +
            "mass=$MASS_RANGE leash=$LEASH_RANGE meleeHold=$MELEE_HOLD_RANGE press=$PRESS_RANGE/$PRESS_PACK/$PRESS_PATIENCE/$PRESS_CLOSING/$PRESS_GIVEUP healer=$HEALER_VALUE keep=$KEEP_RANGE/$KEEP_PICKET/$KEEP_RELEASE hold=$HOLD_WATCH group=$GROUP_WINDOW/$GROUP_SAFE_DMG"
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

internal fun partChar(type: BodyPartType): Char = when (type) {
    TOUGH -> 'T'; MOVE -> 'M'; RANGED_ATTACK -> 'R'; ATTACK -> 'A'; HEAL -> 'H'; CARRY -> 'C'; WORK -> 'W'
}

internal fun flagsSummary(flags: List<FlagInfo>): String =
    flags.joinToString(",") { "${typeChar(it.type)}${it.score}${if (it.ours) "+" else if (it.theirs) "-" else "0"}${if (it.occupant != null) (if (it.occupant.my) "s" else "e") else ""}${if (it.guards.isNotEmpty()) "g${it.guards.size}" else ""}" }

/** Состав армий (живые части) — при каждом изменении: видно потери и покалеченных. */
internal fun logBodies(myCreeps: List<Creep>, enemyCreeps: List<Creep>) {
    if (!DEBUG_LOG) return
    val key = myCreeps.joinToString(",") { bodySummary(it) } + "|" + enemyCreeps.joinToString(",") { bodySummary(it) }
    if (key == lastBodiesKey) return
    lastBodiesKey = key
    println("armies t=${getTicks()}: ours(${myCreeps.size}) " + myCreeps.joinToString(" ") { "${bodySummary(it)}h=${it.hits}" } +
        " | enemy(${enemyCreeps.size}) " + enemyCreeps.joinToString(" ") { "(${it.x},${it.y})${bodySummary(it)}h=${it.hits}" })
}

// ==================== диагностика ====================

internal fun logStuck(active: List<Creep>, enemyCreeps: List<Creep>) {
    for (c in active) {
        if (TrafficManager.stuckFor(c.id) != TrafficManager.STUCK_TICKS) continue
        val want = TrafficManager.lastDesiredOf(c.id)
        val occ = want?.let { w -> (active + enemyCreeps).firstOrNull { it.key == w } }
        val occWant = occ?.let { TrafficManager.lastDesiredOf(it.id) }
        println("stuck ${c.id} at (${c.x},${c.y}) fatigue=${c.fatigue} wants=${want?.let { "(${it / 100},${it % 100})" }} " +
            "occ=${occ?.let { "${it.id} my=${it.my} fatigue=${it.fatigue} ${bodySummary(it)} wants=${occWant?.let { w -> "(${w / 100},${w % 100})" } ?: "-"}" } ?: "free"}")
    }
}

/** ASCII-карта один раз: '#' стена, '~' болото, '.' равнина, 'F' флаг, 'm' наш крип, 'e' вражеский. */
internal fun PainAndGain.captureMapMarks(flags: List<FlagInfo>, myCreeps: List<Creep>, enemyCreeps: List<Creep>) {
    val m = HashMap<Int, Char>()
    fun mark(x: Int, y: Int, c: Char) { m[key(x, y)] = c }
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
            val structure = marks[key(x, y)]
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

internal class OrderAuditOut(
)

/** АУДИТ ПРИКАЗОВ КОМАНДИРА (v256, этап 10; сегмент runArmy): одна клетка — двоим (clash), исполнение приказов прошлого тика (obey, lost=stuck/foe/fat/else), дальние приказы, запись orderPrev. Перенесено дословно. */
internal fun PainAndGain.orderAudit(ctx: Ctx, meas: ArmyMeasures, targ: ArmyTargets): OrderAuditOut {
    val seen = HashMap<Int, Int>()
    commandOf.values.forEach { p -> seen[p.key] = (seen[p.key] ?: 0) + 1 }
    val dup = seen.values.count { it > 1 }
    orderClash.n += dup
    // ГАРАНТИЯ, А НЕ НАБЛЮДЕНИЕ (v176, оператор: «не должно быть такого, что по приказам командира в одну
    // клетку собрались двое»). Раздача держит своё множество занятых, но источников приказа несколько — бой,
    // гонка, марш, хранители, отход, — и на стыке коллизия всё же случалась (одна на 431 приказ, режим боя).
    // Здесь она снимается: клетка остаётся за первым, второй теряет приказ и идёт по общим правилам
    if (dup > 0) {
        val used = HashSet<Int>()
        val drop = ArrayList<String>()
        for ((id, p) in commandOf) { val k = p.key; if (!used.add(k)) drop.add(id) }
        drop.forEach { commandOf.remove(it) }
    }
    if (dup > 0 && DEBUG_LOG) {
        val where = seen.entries.firstOrNull { it.value > 1 }?.key ?: 0
        val who = commandOf.filterValues { it.key == where }.keys.joinToString(",")
        println("clash t=${getTicks()}: mode=$cmdMode cell=(${where / 100},${where % 100}) who=$who")
    }
    // ЗАЖАТОГО БЬЁМ — ПРИБОР (v264): считается по итоговым приказам, а не внутри раздачи — та идёт по разу на
    // замысел перебора и насчитала бы пробные планы. Сначала сверка вчерашних постановок, потом сегодняшние
    for ((id, foeId) in Memory.pinWatch) {
        val c = meas.commandArmy.firstOrNull { it.id == id } ?: continue
        val e = ctx.enemyCreeps.firstOrNull { it.id == foeId } ?: continue
        pinChk.n++
        if (getRange(c, e) <= 1) pinHeld.n++
    }
    Memory.pinWatch.clear()
    // ВСТРЕЧА РАНЕНОГО С ЛЕКАРЁМ — ПРИБОР ПО ИСПОЛНЕНИЮ (v276, meet=план/лекарь рядом/уходящих с приказом/исполнено/сверено):
    // счётчик rotfm считал встречи в пробных планах, а по реплеям лекарь после выхода раненого стоит в двух клетках, как и
    // до правки. Здесь — по итоговым приказам: у уходящего по фокусу с приказом был ли лекарь в двух клетках от его
    // клетки, получил ли лекарь клетку вплотную к ней, и стояли ли они вплотную на следующем тике
    for ((rid, hid) in Memory.meetWatch) {
        val r = meas.commandArmy.firstOrNull { it.id == rid } ?: continue
        val h = meas.commandArmy.firstOrNull { it.id == hid } ?: continue
        meetChk.n++
        if (getRange(r, h) <= 1) meetDone.n++
    }
    Memory.meetWatch.clear()
    for (rid in Memory.rotByFocus) {
        val r = meas.commandArmy.firstOrNull { it.id == rid } ?: continue
        val dest = commandOf[rid] ?: continue
        meetRot.n++
        val medics = meas.commandArmy.filter { it.id != rid && healerOnly(it) }
        if (medics.any { getRange(it, dest) <= 2 }) meetNear.n++
        val m = medics.firstOrNull { h -> commandOf[h.id]?.let { getRange(it, dest) <= 1 } == true } ?: continue
        meetPlan.n++
        Memory.meetWatch[rid] = m.id
    }
    // НОГИ ЗА ФОКУСОМ — ПРИБОР (v268, ffoc=до/после/стрелков): стрелки под приказом командира при живом фокусе — у скольких
    // фокус в досягаемости с нынешней клетки и с клетки приказа. Реплеи до правки: 25–32 % до шага, 14–20 % после
    targ.focusTarget?.takeIf { it.hits > 0 }?.let { f ->
        for (c in meas.commandArmy) {
            if (!hasRanged(c)) continue
            val cell = commandOf[c.id] ?: continue
            ffocAll.n++
            if (getRange(c, f) <= RANGED_RANGE) ffocBefore.n++
            if (getRange(cell, f) <= RANGED_RANGE) ffocAfter.n++
        }
    }
    run {
        val foes = ctx.combatEnemies.filter { e -> InfluenceMap.profileOf(e).let { it.melee + it.ranged + it.heal > 0.0 } }
        if (foes.isEmpty() || commandOf.isEmpty()) return@run
        val stuck = HashSet<Int>()
        for (e in ctx.enemyCreeps) if (e.fatigue > 0) stuck.add(e.key)
        val foeAt = HashSet<Int>()
        for (e in ctx.enemyCreeps) foeAt.add(e.key)
        val plan = HashMap<String, Int>()
        for (f in meas.commandArmy) if (f.hits > 0) plan[f.id] = (commandOf[f.id] ?: InfluenceMap.cell(f.x, f.y)).let { it.key }
        for (c in meas.commandArmy) {
            if (!(meleeOnlyLive(c))) continue
            val mine = commandOf[c.id] ?: continue
            val near = foes.filter { getRange(c, it) <= 2 }
            if (near.isEmpty()) continue
            val ours = HashSet<Int>()
            for ((id, k) in plan) if (id != c.id) ours.add(k)
            val canStep = canMove(c) && c.fatigue == 0
            var opp = false
            for (dx in -1..1) for (dy in -1..1) {
                if (opp) continue
                if (!canStep && (dx != 0 || dy != 0)) continue
                val x = c.x + dx
                val y = c.y + dy
                if (x < 0 || y < 0 || x > 99 || y > 99 || DistanceMap.isTerrainWall(x, y)) continue
                val key = key(x, y)
                if (key in foeAt || key in ours) continue
                val p = InfluenceMap.cell(x, y)
                if (near.any { e -> getRange(p, e) <= 1 && pinnedAt(p, e, ours, stuck) }) opp = true
            }
            if (!opp) continue
            pinOpp.n++
            val hit = near.firstOrNull { e -> getRange(mine, e) <= 1 && pinnedAt(mine, e, ours, stuck) } ?: continue
            pinOrd.n++
            Memory.pinWatch[c.id] = hit.id
        }
    }
    Memory.orderPrev.forEach { (id, cell) ->
        // ...и захватчик из аудита исключается: его приказ — ФЛАГ, а не клетка, и ведёт его свой цикл;
        // считать его ослушником было бы неверно (v173)
        if (id in Memory.cmdDetach) return@forEach
        val c = meas.commandArmy.firstOrNull { it.id == id } ?: return@forEach
        orderAuditN.n++
        // ...и ПРИКАЗ В ДВУХ ШАГАХ ИСПОЛНЕН, ЕСЛИ КРИП СТАЛ БЛИЖЕ (v184). Прибор сверял клетку крипа с
        // НАЗНАЧЕННОЙ и только с ней, а строй (`commandBrace`) назначает место в строю за несколько клеток —
        // такой приказ не мог быть засчитан НИКОГДА, и едва строй заработал, исполнение упало со 99 % до 62 %
        // при том, что крипы шли туда, куда велено. Из-за этого я успел записать в дефекты то, чего не было,
        // и починить не тот код (см. USE_BRACE_STEPS). По всей серии v183 «ушёл в другую клетку» набрал
        // 2 022 случая из 39 245 — почти все они этой природы
        val far = 
            (orderDist[id] ?: 0) > 1 && maxOf(abs(c.x - cell.x), abs(c.y - cell.y)) < (orderDist[id] ?: 0)
        if ((c.x == cell.x && c.y == cell.y) || far) orderAuditOk.n++
        else {
            // ...и КУДА делись остальные (v170): приказ был «стой», а крип ушёл; крип не двинулся
            // вовсе; двинулся, но в другую клетку; или не мог двигаться от усталости
            val here = orderWas[id]
            when {
                cell.x == here?.first && cell.y == here.second -> lostStay.n++
                // ...клетку мог занять ВРАГ: он ходит одновременно с нами, и его шаг делает приказ
                // неисполнимым задним числом — это неустранимо в принципе, и считать надо отдельно (v175)
                ctx.enemyCreeps.any { e -> e.x == cell.x && e.y == cell.y } -> lostEnemy.n++
                c.x == here?.first && c.y == here.second -> lostStuck.n++
                (orderFatigue[id] ?: 0) > 0 -> lostFatigue.n++
                else -> lostElsewhere.n++
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
        val c = meas.commandArmy.firstOrNull { it.id == id } ?: return@forEach
        if (maxOf(abs(c.x - p.x), abs(c.y - p.y)) > 1) orderFar++
    }
    orderWas.clear(); orderFatigue.clear()
    meas.commandArmy.forEach { c -> orderWas[c.id] = c.x to c.y; orderFatigue[c.id] = c.fatigue }
    orderDist.clear()
    commandOf.forEach { (id, p) ->
        val c = meas.commandArmy.firstOrNull { it.id == id }
        if (c != null) orderDist[id] = maxOf(abs(c.x - p.x), abs(c.y - p.y))
    }
    Memory.orderPrev.clear()
    commandOf.forEach { (id, p) -> Memory.orderPrev[id] = p }
    // потеря за прошлый тик по всем — ДО цикла: lastHits обновляется в конце каждой итерации, и для уже обработанных она была бы нулём
    return OrderAuditOut(
    )
}

internal class PrintTickOut(
)

/** ПЕЧАТЬ ТИКА (v257, этап 10; хвост tickBody): строка застрявших, строка t= со всеми приборами раз в LOG_EVERY тиков, перепись rung / tac, поля fld. Перенесено дословно. */
internal fun PainAndGain.printTick(ctx: Ctx, rem: RememberTickOut): PrintTickOut {
    if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
        println("bfs t=${getTicks()} max=$bfsMaxTick cost=$bfsMaxCost")
        bfsMaxTick = 0
        bfsMaxCost = 0.0
    }
    if (DEBUG_LOG) logStuck(ctx.active, ctx.enemyCreeps)
    if (DEBUG_VISUALS) InfluenceMap.drawDebug(ctx.army, ctx.myCreeps, ctx.enemyCreeps)

    if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
        val ours = ourPowerOf(ctx.army, ctx.combatEnemies)
        val theirs = enemyPowerOf(ctx.combatEnemies, ctx.army)
        tickView = TickView(ctx, rem, ours, theirs)
        declareLine()
        println(Gauges.line(T_LINE))
        // ПЕРЕПИСЬ (v203): только ненулевые ветки, накопительно за матч. Сумма stepCount обязана равняться
        // размеру армии, умноженному на число тиков, — если не равна, перепись врёт, и всё на ней построенное тоже
        println("rung t=${getTicks()}: why=" + rungCount.shown() +
            " step=" + stepCount.shown() +
            " pass=" + Gauges.labelledAt("pass").shown() +
            " sum=${stepCount.sum()}")
        // ...и ТА ЖЕ ПЕРЕПИСЬ ПО ПРЕДЛОЖЕНИЯМ (v252, этап 9): «задание отряда . терм» и приоритет; сумма обязана совпасть с
        // суммой rung — оба счёта растут один раз на крипа армии за тик
        // ДОСТИЖИМОСТЬ ПО СТРОКАМ ТАБЛИЦ (v444, прибор `reach t=`, см. Tables.kt): тег:выиграла/условие истинно/перекрыта порядком
        println("reach t=${getTicks()}: " + REACH_LINE.joinToString(" ") { tallyOf(it).print() } + " err=${REACH_LINE.sumOf { tallyOf(it).err }}")
        println("tac t=${getTicks()}: mt=" + tacCount.shown() +
            " prio=" + prioCount.shown() +
            " sum=${prioCount.sum()}")
        // ПОЛЯ: пики печатаются, чтобы обнулившееся поле было ВИДНО — прибор, умеющий сказать только
        // «поле построено», прибором не является
        if (FIELD_LOG) println("fld t=${getTicks()}: hdbf=${InfluenceMap.healDebuffStats()}" +
            " eM=${InfluenceMap.fieldPeak(InfluenceMap.eMelee).toInt()} eR=${InfluenceMap.fieldPeak(InfluenceMap.eRanged).toInt()}" +
            " eH=${InfluenceMap.fieldPeak(InfluenceMap.eHeal).toInt()} aM=${InfluenceMap.fieldPeak(InfluenceMap.aMelee).toInt()}" +
            " aR=${InfluenceMap.fieldPeak(InfluenceMap.aRanged).toInt()} aH=${InfluenceMap.fieldPeak(InfluenceMap.aHeal).toInt()}" +
            " eF=${InfluenceMap.fieldPeak(InfluenceMap.eFire).toInt()} atM=${InfluenceMap.fieldPeak(InfluenceMap.attMelee).toInt()}" +
            " atR=${InfluenceMap.fieldPeak(InfluenceMap.attRanged).toInt()} atH=${(InfluenceMap.published?.let { InfluenceMap.fieldPeak(it.attHeal) } ?: 0.0).toInt()}" +
            // ЦЕЛЬ (этап 5): затравок в очаге, перестроек против удержаний очага, и — главное — доля решений
            // раздачи, которые слагаемое цели ИЗМЕНИЛО. flips=0 за сто тиков есть операционное определение
            // мёртвого кода
            " seeds=${goalSeeds.size} goal=(${goalCx},${goalCy}) rebuild=$goalRebuilds/$goalHolds flips=${Gauges.counterAt("flips")}/${Gauges.counterAt("flips", 1)}" +
            // ВОРОТА (этап 6): на каком пороге выживания крип нашёл клетку. gate5..gate1 — уровни лестницы,
            // fell — сколько раз клетки не нашлось даже при пороге в один тик и сработал общий добор. Это
            // посчитанная версия прежнего МОЛЧАЛИВОГО провала требования
            " gate=${Gauges.intsAt("gateLevels").drop(1).take(5).joinToString("/")} fell=${Gauges.counterAt("fell")}" +
            " intent=" + Memory.intentHist.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" })
        Gauges.endWindow()
        if (getTicks() % (LOG_EVERY * 10) == 0) println(TrafficManager.audit())
    }
    // снимок накопительных счётчиков тактика и строя — КАЖДЫЙ тик, в конце: следующая печать отдаст разницу за свой тик
    Gauges.endTick()
    // МИЛИ БЕЗ ЖИВОЙ ATTACK (счётчик к пункту Д оператора, v451): крипо-тиков, где рождённый мили стоит без живой ATTACK (написание
    // А истинно, Б ложно), тиков с хотя бы одним таким, и крипо-тиков таких в трёх клетках от его вооружённого — редкое
    // состояние, которое обязано быть посчитано до правки мест, где оно решает про урон
    val strippedMelee = ctx.army.count { meleeOnlyBorn(it) && !meleeOnlyLive(it) }
    if (strippedMelee > 0) {
        mstripTicks.n += strippedMelee; mstripAny.n++
        mstripReach.n += ctx.army.count { meleeOnlyBorn(it) && !meleeOnlyLive(it) && ctx.threats.any { e -> getRange(it, e) <= RANGED_RANGE } }
    }
    return PrintTickOut(
    )
}

/** РАСКЛАДКА СТРОКИ `reach t=` (v455): таблицы решений по именам их счётчиков, в порядке печати. Таблица регистрирует счётчик сама
 *  (`Tally("имя")`, Tables.kt) — новая таблица здесь называется один раз. `err` — сумма по всем: у последовательностей (`gate`,
 *  `pass`) полного обхода нет, и их `err` всегда ноль. */
internal val REACH_LINE = listOf("rung", "step", "gate", "pass", "posture", "mode", "cmdwhy", "push")

/** То, что вычисляемым полям строки `t=` нужно от тика: мир, память тика и мощь сторон. Ставится перед печатью строки. */
internal class TickView(val ctx: Ctx, val rem: RememberTickOut, val ours: Double, val theirs: Double)

private lateinit var tickView: TickView
private var lineDeclared = false

/**
 * РАСКЛАДКА СТРОКИ `t=` (v455, второй шаг архитектуры, этап 2): порядок полей — ЭТОТ список, и другого описания порядка нет.
 * Поле объявляет тот, кто его считает (`Gauges.counter("имя", часть)` у стадии — см. Gauges.kt); здесь — только имена. Имя с
 * хвостом `@2` — второе поле с тем же печатным именем (в строке два `hunt=`: охота за остовами и «охотимся на угрозу»); имя с
 * ведущим `<` печатается без пробела перед собой: `fmassed=0stray=0` — склейка, которой прибор `stray` невидим для `series.py`
 * с момента появления; она воспроизводится побайтно до решения оператора (docs/pain-and-gain-architecture-2.md, 2.8, п. 1).
 */
internal val T_LINE = listOf(
    "t", "army", "runners", "enemies", "reach", "spread", "hfar", "hparts", "ehparts", "hcov", "hulk", "hulkreach",
    "revived", "chase", "kills", "capgate", "cap", "capq", "capqu", "capu", "capeval", "capidle", "mstrip", "poised", "edge",
    "capopp", "ffight", "fmassed", "<stray", "sout", "hold", "gsafe", "fguard", "route", "man", "gcov", "courier", "hunt",
    "toothless", "sit", "keep2", "keep3", "scout", "spotm", "spothold", "sym", "split", "recall", "healgap", "nomedic",
    "flip", "aggro", "pushheld", "lethal", "ledgerw", "breakoff", "race", "poisedcost", "objnone", "objdrop", "budget",
    "runner", "cmdwhy", "ovl", "conc", "concall", "concmax", "concfan", "lostrace", "gather", "close3", "guard", "warm",
    "warmann", "warmhold", "warmcmd", "warmfight", "warmcap", "mconc", "mconcmax", "mpack", "pack", "mpackon", "kchase",
    "kveto", "gathera", "annempty", "shooters", "abort", "srch", "deals", "srchd", "pin", "fself", "rotf", "rotfm", "meet",
    "fsw", "ffoc", "ovw", "conf", "rtr", "mquiet", "mquietc", "maj", "surv", "adr", "rad", "simd", "fhl", "mrush", "zlb",
    "hwall", "hwallh", "hwalla", "hwallp", "postc", "rot", "mdir", "hfull", "hover", "hswap", "hexp", "hlost", "hwallx",
    "hpick", "hadj", "hadjn", "hfire", "hstill", "cmdheal", "dh", "retr", "standfire", "outmw", "score", "rate", "behind",
    "passive", "flags", "obey", "branch", "fled", "clash", "lost", "kite", "massed", "plan", "cmd", "mode", "disp", "evt",
    "fire", "posture", "obj", "hunt@2", "rush", "weak", "pat", "strip", "touch", "touchl", "out", "back", "guns", "mheal",
    "hline", "fall", "our", "enemy", "ledger", "wounded", "hits", "enemyHits", "centroid", "enemyCentroid",
)

/** Поля строки `t=`, которые считаются НА МЕСТЕ ПЕЧАТИ: снимок мира, величины состояния, отношения накопителей. Объявляются один
 *  раз, при первой печати; расширение оркестратора — пока они читают его члены (величины одного тика уходят к носителям на этапе 6). */
private fun PainAndGain.declareLine() {
    if (lineDeclared) return
    lineDeclared = true
    Gauges.computed("t") { "${getTicks()}" }
    Gauges.computed("army") { "${tickView.ctx.army.size}" }
    Gauges.computed("runners") { "${tickView.ctx.runners.size}(${Memory.detachedIds.size} detached)" }
    Gauges.computed("enemies") { "${tickView.ctx.enemyCreeps.size}/${tickView.ctx.combatEnemies.size}" }
    Gauges.computed("reach") { "${tickView.ctx.army.count { hasRanged(it) && tickView.ctx.combatEnemies.any { e -> getRange(it, e) <= RANGED_RANGE } }}/${tickView.ctx.army.count { hasRanged(it) }}" }
    // разброс строя (v191): диаметр группы стрелков и сколько вооружённых стоят дальше поводка от своего
    // центра. Реплеи говорят, что стирание приходит на диаметре 21, а пат — на диаметре 3
    Gauges.computed("spread") { "${tickView.ctx.army.filter { hasRanged(it) }.let { sh -> if (sh.size > 1) sh.maxOf { a -> sh.maxOf { b -> getRange(a, b) } } else 0 }}/${tickView.ctx.army.count { hasWeapon(it) && getRange(it, tickView.rem.armedCentroid) > LEASH_RANGE }}" }
    // ...и отдельно ЛЕКАРИ за поводком (v202): именно они разъезжались, а прибор их не считал вовсе
    Gauges.computed("hfar") { "${tickView.ctx.army.count { healerOnly(it) && getRange(it, tickView.rem.armedCentroid) > LEASH_RANGE }}/${tickView.ctx.army.count { healerOnly(it) }}" }
    // ЛЕКАРИ СУДЯТСЯ ВЫЖИВАНИЕМ, А НЕ ДОСТАВЛЕННЫМ ЛЕЧЕНИЕМ (этап 7): v202 поднял лечение и дал 0:3.
    // Тело лекаря — h6m6, лечащие части СПЕРЕДИ, поэтому урон уничтожает именно их и первыми; замер
    // разгрома 3d97c4 говорит, что его лекари сохраняют 100 % лечащих частей, наши 8 %. hcov — доля
    // нужды, покрытая назначенными клетками: прибор раздачи, а не исхода
    Gauges.computed("hparts") { "${withHeal(tickView.ctx.myCreeps).sumOf { c -> c.body.count { it.type == HEAL && it.hits > 0 } }}/${withHeal(tickView.ctx.myCreeps).sumOf { c -> c.body.count { it.type == HEAL } }}" }
    Gauges.computed("ehparts") { "${tickView.ctx.enemyCreeps.sumOf { c -> c.body.count { it.type == HEAL && it.hits > 0 } }}/${tickView.ctx.enemyCreeps.sumOf { c -> c.body.count { it.type == HEAL } }.let { ehpartsAll = maxOf(ehpartsAll, it); ehpartsAll }}" }
    Gauges.computed("hcov") { "${(InfluenceMap.published?.healCoverage() ?: (0.0 to 0.0)).let { (left, total) -> "${(total - left).toInt()}/${total.toInt()}" }}" }
    Gauges.computed("hulk") { "${disarmedFoe.size}" }
    Gauges.computed("chase") { "${Memory.chaseOf.size}/$chaseTicks" }
    // прибор ворот с одним писателем (v451, пункт Г): всерьёз / по одному на тик × флаг / оценочные / холостые
    Gauges.computed("ffight") { "$firstFightTick" }
    Gauges.computed("fmassed") { "${if (fightMassedSeen) 1 else 0}" }
    Gauges.computed("gsafe", 1) { "$groupDmgWindow" }
    Gauges.computed("ledgerw") { "$ledgerWindow/$ourLostWindow/$hisLostWindow" }
    Gauges.computed("race") { "${race100.ifEmpty { "-" }}/${race200.ifEmpty { "-" }}" }
    // приборы v221: тёплый контакт (пары к USE_FIGHT_BY_LEDGER), концентрация и цель мили, погоня за
    // кайтером, сбор в бою, и стрелки обеих сторон — «кто теряет стрелков первым», что реплей показал, а
    // консоль не показывала (имя `guns=` занято прибором v200)
    Gauges.computed("shooters") { "${tickView.ctx.army.count { hasRanged(it) }}/${tickView.ctx.combatEnemies.count { hasRanged(it) }}" }
    Gauges.computed("srch") { "$srchCut/$srchTicks/${cmdTailMax.toInt()}" }
    Gauges.computed("ovw") { "${Executor.ovwContact}/${Executor.ovwRanged}" }
    Gauges.computed("conf") { "${Arbiter.confReach}/${Arbiter.confFatigue}" }
    Gauges.computed("mquiet") { "$mquietMoved/$mquietAll/${mquietGain.toInt()}" }
    Gauges.computed("rad") { "${(radSum * 10).toInt()}/$radTicks/$radMax/$radWide" }
    Gauges.computed("simd") { "${(simdSum * 100).toInt()}/$simdTicks/$simdPos/$simdDisagree" }
    Gauges.computed("hstill") { "${InfluenceMap.wardsUnderStill}/${InfluenceMap.wardsUnderRanged}" }
    Gauges.computed("score") { "${ourScore.toInt()}/${enemyScore.toInt()}" }
    Gauges.computed("rate") { "$ourRate/$enemyRate" }
    Gauges.computed("behind") { "$behindOnScore" }
    Gauges.computed("passive") { "${tickView.ctx.passiveEnemy}" }
    Gauges.computed("flags") { "${flagsSummary(tickView.ctx.flags)}" }
    Gauges.computed("massed") { "$kiteMassed" }
    Gauges.computed("cmd") { "${commandOf.size}/$cmdTicks:$cmdBlocked" }
    Gauges.computed("mode") { "$cmdMode" }
    Gauges.computed("disp") { "$dispNow" }
    Gauges.computed("fire") { "${fireOf.size}" }
    Gauges.computed("posture") { "$posture" }
    Gauges.computed("obj") { "${objectiveFlagId?.let { id -> tickView.ctx.flags.firstOrNull { it.id == id }?.let { "(${it.pos.x},${it.pos.y})" } } ?: "-"}" }
    Gauges.computed("hunt@2") { "$huntingThreat" }
    Gauges.computed("rush") { "$unflaggedRushNow" }
    Gauges.computed("weak") { "$outmatchedTicks" }
    Gauges.computed("touch") { "${(touchShare * 100).toInt()}/${(touchMin * 100).toInt()}/${(hisTouchShare * 100).toInt()}" }
    Gauges.computed("touchl") { "${(touchShareLast * 100).toInt()}/${(hisTouchShareLast * 100).toInt()}" }
    Gauges.computed("our") { "${tickView.ours.toInt()}" }
    Gauges.computed("enemy") { "${tickView.theirs.toInt()}" }
    Gauges.computed("ledger") { "${enemyDamageTaken - ourDamageTaken}" }
    Gauges.computed("wounded") { "${tickView.ctx.army.count { stripped(it) }}" }
    Gauges.computed("hits") { "${tickView.ctx.army.sumOf { it.hits }}/${tickView.ctx.army.sumOf { it.hitsMax }}" }
    Gauges.computed("enemyHits") { "${tickView.ctx.combatEnemies.sumOf { it.hits }}/${tickView.ctx.combatEnemies.sumOf { it.hitsMax }}" }
    Gauges.computed("centroid") { "(${tickView.ctx.ourCentroid.x},${tickView.ctx.ourCentroid.y})" }
    Gauges.computed("enemyCentroid") { "${tickView.ctx.enemyCentroid?.let { "(${it.x},${it.y})" } ?: "-"}" }
}

/** Мили без живой ATTACK (v451, счётчик к пункту Д): крипо-тиков / тиков с хотя бы одним / крипо-тиков в досягаемости его
 *  стволов — прибор `mstrip=`; считает и печатает сам прибор. */
private val mstripTicks = Gauges.counter("mstrip")
private val mstripAny = Gauges.counter("mstrip", 1)
private val mstripReach = Gauges.counter("mstrip", 2)

/** Раненый уступает дорогу всем: его место — за лекарями, а не между ними и строем. */

// ---------- отладка ----------
// версия играющей сборки — первой строкой лога матча: по ней матч привязывается к коду (см. правила сессий)
internal const val BOT_VERSION = "v455"

/** Печать приборов полей влияния. Сверка со ЗНАЧЕНИЯМИ (chk против прямого пересчёта по крипам,
 *  fldcmp против переносимого incNext) сняла свой вопрос и удалена на этапе 8: 0 из 304 950 клеток и
 *  0 из 22 355 494 сверок по 135 сценариям. Осталось то, что отвечает на живой вопрос, — пики полей:
 *  обнулившееся поле обязано быть ВИДНО, а не тихо давать нули. */
internal const val FIELD_LOG = true

/** Выключено: отрисовка влияния — ~57 000 вызовов contribution за тик (13×13 клеток × 12 стрелков × 28 крипов),
 *  первый тик матча 7 вылетел по таймауту именно в drawDebug; журнал даёт всё, что нужно для разбора. */
internal const val DEBUG_VISUALS = false

internal const val CPU_SLOW_MS = 60.0   // a tick over this prints its phases (the limit is 100 ms; the first tick 1 000)

internal val postContest = Gauges.counter("postc")

internal val hswapN = Gauges.counter("hswap")

/** ...и то же в раздаче командира (вторая редакция): приказов мили, где выбранная клетка — удар, и из них уведённых. */
internal val cmdQuietAll = Gauges.counter("mquietc", 1)

internal val cmdQuietMoved = Gauges.counter("mquietc")

/** РАЗЛЁТ АРМИИ ПОД УРОНОМ (v409, прибор; проект «армия не рассыпается»): сумма радиуса (наибольшее расстояние
 *  от центра боевых до нашего боевого) ×10, тиков замера, наибольший радиус за матч и тиков с радиусом больше пяти.
 *  Мера взята из замера тел: в поражении армия расползается с пяти клеток на четырнадцать за двадцать тиков, в
 *  победе держится в пяти и отрастает (см. v407). Считается только под уроном — там, где связность и решает. */

internal val zlbTicks = Gauges.counter("zlb")

internal val survTicks = Gauges.counter("surv")

internal val survContact = Gauges.counter("surv", 2)

/** ...и тиков режима, где уходить некуда и он в контакте — бой строем (вторая редакция). */
internal val survFights = Gauges.counter("surv", 3)

internal var lastBodiesKey = ""

// ---------- модель ----------

/** ЗАМЕР CPU (07.09.2026): в живых логах «Script execution timed out» на ПЕРВОМ тике в 12 матчах из 20 каждой серии — первый
 *  тик убивается лимитом (cpuTimeLimitFirstTick), и до этого дня у бота не было ни одной своей меры CPU (строка «cpu t=» —
 *  стендовая). На первых трёх тиках после каждой фазы печатается `cpu t=N <фаза>=мс` отдельной строкой — последняя строка перед
 *  таймаутом называет фазу, съевшую бюджет; раз в сто тиков — самый долгий тик с прошлой строки. getCpuTime() — наносекунды
 *  с начала тика (на стенде — по hrtime). */
internal var cpuMaxMs = 0.0

internal var cpuMaxTick = 0

internal var cpuSlowTicks = 0

internal val meleeBackTicks = Gauges.counter("back")                        // прибор: тиков, в которые мили ставился ПОЗАДИ строя (v195)

internal val outOfFireTicks = Gauges.counter("out")                        // крипо-тиков, в которые мили уводился из его кольца

internal val stripTicks = Gauges.counter("strip")                            // тиков, в которые залп сводился на ОДНОГО его лекаря

internal val lostEnemy = Gauges.counter("lost", 2, label = "foe")      // клетку приказа занял враг (v175)      // приказов, отменённых бегством (v173)     // сколько раз одна клетка была назначена двоим (v172)

/** Цель пачки мили (v221, см. USE_MELEE_PACK): липкий id и значение этого тика — для командира. */
/** Пары пачки (v221): «тиков с целью пачки / тиков, где у нас есть мили и у него боевые»; «крипо-тиков мили, чьи
 *  ноги идут к цели пачки / крипо-тиков мили с целью ног, пока цель пачки есть». */
internal val packHeld = Gauges.counter("pack")

internal val mpackOnHit = Gauges.counter("mpackon")

internal val mpackOn = Gauges.counter("mpackon", 1)

// ==================== приборы стадии, бывшие членами object PainAndGain (v455, второй шаг архитектуры, этап 2) ====================
