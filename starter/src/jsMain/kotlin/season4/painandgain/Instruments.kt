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

internal fun PainAndGain.cpuMs(): Double = try { getCpuTime() / 1_000_000.0 } catch (e: Throwable) { 0.0 }

internal fun PainAndGain.cpuMark(phase: String) { cpuPhases.add(phase to cpuMs()) }

/** Перебор замыслов под бюджетом (v262, см. Strategist.armyCommand): тиков с перебором, из них обрезанных, наибольший
 *  хвост тика после командира в мс — прибор srch= и запас бюджета. */
internal var srchTicks = 0
internal var srchCut = 0
internal var cmdTailMax = 0.0
internal var cmdEndMs = 0.0
internal var cmdSearched = false
/** «Зажатого бьём» (v264, pin=взято/возможностей/удержано/сверено): мили под приказом с клеткой вплотную к зажатому
 *  врагу в шаге; приказ, поставивший его туда; и был ли зажатый вплотную к нему на следующем тике. */
internal var pinOpp = 0
internal var pinOrd = 0
internal var pinChk = 0
internal var pinHeld = 0
/** Знаменатель ehparts= (v265): наибольшее число частей HEAL у его живой армии за матч, то есть его исходные — армия без
 *  спавна. Прежде знаменатель брал только крипов с ЖИВЫМ лечением, и лекарь, раздетый целиком, выпадал из дроби вместе
 *  со своими частями: разбор стены лечения видел в логе 12/12 там, где его лечение потеряло треть. */
internal var ehpartsAll = 0
/** Ротация по его фокусу (v275, rotf=выходов/возвратов/крипо-тиков в ней/тиков правила/попаданий «доля»/попаданий «лекарь,
 *  ближайший»/сверок). */
internal var rotfOut = 0
internal var rotfBack = 0
internal var rotfTicks = 0
internal var rotfOn = 0
internal var rotfF = 0
internal var rotfA = 0
internal var rotfN = 0
/** Встречи уходящего раненого с лекарём в раздаче командира (v276, rotfm=). */
internal var rotfMeet = 0
internal var meetRot = 0
internal var meetNear = 0
internal var meetPlan = 0
internal var meetDone = 0
internal var meetChk = 0
/** Самолечение в добиваемости (v266, fself=сменилось/всего): его лекарь в досягаемости наших стволов, пробиваемый без
 *  своего лечения и непробиваемый с ним. */
internal var fselfFlip = 0
internal var fselfAll = 0
/** Смены фокуса (v267, fsw=смен/тиков:ушла/далеко/стрелок/стволы/добиваем/раздета): на тиках с целью в досягаемости —
 *  сколько раз фокус сменился и почему: прежней цели нет среди живых боевых, она дальше шага от наших стрелков, лучшая
 *  добивается за тик, лучшая — стрелок (v60), у лучшей больше стволов (v70), прежняя без оружия и лечения. */
internal var fswTicks = 0
internal var fswN = 0
internal var fswLost = 0
internal var fswFar = 0
internal var fswKill = 0
internal var fswRanged = 0
internal var fswGuns = 0
internal var fswBare = 0
/** Выход раненого из его зоны (v285, sout=вышло/вернулось/крип-тиков вне): см. stepOutWounded. */
internal var soutOut = 0
internal var soutBack = 0
internal var soutTicks = 0
/** Шаг бойца на клетку чужого флага при закрытых воротах захвата (v282, stray=): столько раз крип остался стоять. */
internal var strayCapRefused = 0
/** Ноги за фокусом (v268, ffoc=до/после/стрелков): стрелки под приказом при живом фокусе, у которых фокус в досягаемости с
 *  нынешней клетки и с клетки приказа. */
internal var ffocAll = 0
internal var ffocBefore = 0
internal var ffocAfter = 0

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
    val focusTarget: Creep?,
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
    // ЗАЖАТОГО БЬЁМ — ПРИБОР (v264): считается по итоговым приказам, а не внутри раздачи — та идёт по разу на
    // замысел перебора и насчитала бы пробные планы. Сначала сверка вчерашних постановок, потом сегодняшние
    for ((id, foeId) in Memory.pinWatch) {
        val c = commandArmy.firstOrNull { it.id == id } ?: continue
        val e = ctx.enemyCreeps.firstOrNull { it.id == foeId } ?: continue
        pinChk++
        if (getRange(c, e) <= 1) pinHeld++
    }
    Memory.pinWatch.clear()
    // ВСТРЕЧА РАНЕНОГО С ЛЕКАРЁМ — ПРИБОР ПО ИСПОЛНЕНИЮ (v276, meet=план/лекарь рядом/уходящих с приказом/исполнено/сверено):
    // счётчик rotfm считал встречи в пробных планах, а по реплеям лекарь после выхода раненого стоит в двух клетках, как и
    // до правки. Здесь — по итоговым приказам: у уходящего по фокусу с приказом был ли лекарь в двух клетках от его
    // клетки, получил ли лекарь клетку вплотную к ней, и стояли ли они вплотную на следующем тике
    for ((rid, hid) in Memory.meetWatch) {
        val r = commandArmy.firstOrNull { it.id == rid } ?: continue
        val h = commandArmy.firstOrNull { it.id == hid } ?: continue
        meetChk++
        if (getRange(r, h) <= 1) meetDone++
    }
    Memory.meetWatch.clear()
    for (rid in Memory.rotByFocus) {
        val r = commandArmy.firstOrNull { it.id == rid } ?: continue
        val dest = commandOf[rid] ?: continue
        meetRot++
        val medics = commandArmy.filter { it.id != rid && hasHeal(it) && !hasWeapon(it) }
        if (medics.any { getRange(it, dest) <= 2 }) meetNear++
        val m = medics.firstOrNull { h -> commandOf[h.id]?.let { getRange(it, dest) <= 1 } == true } ?: continue
        meetPlan++
        Memory.meetWatch[rid] = m.id
    }
    // НОГИ ЗА ФОКУСОМ — ПРИБОР (v268, ffoc=до/после/стрелков): стрелки под приказом командира при живом фокусе — у скольких
    // фокус в досягаемости с нынешней клетки и с клетки приказа. Реплеи до правки: 25–32 % до шага, 14–20 % после
    focusTarget?.takeIf { it.hits > 0 }?.let { f ->
        for (c in commandArmy) {
            if (!hasRanged(c) || !hasWeapon(c)) continue
            val cell = commandOf[c.id] ?: continue
            ffocAll++
            if (getRange(c, f) <= RANGED_RANGE) ffocBefore++
            if (getRange(cell, f) <= RANGED_RANGE) ffocAfter++
        }
    }
    run {
        val foes = ctx.combatEnemies.filter { e -> InfluenceMap.profileOf(e).let { it.melee + it.ranged + it.heal > 0.0 } }
        if (foes.isEmpty() || commandOf.isEmpty()) return@run
        val stuck = HashSet<Int>()
        for (e in ctx.enemyCreeps) if (e.fatigue > 0) stuck.add(e.x * 100 + e.y)
        val foeAt = HashSet<Int>()
        for (e in ctx.enemyCreeps) foeAt.add(e.x * 100 + e.y)
        val plan = HashMap<String, Int>()
        for (f in commandArmy) if (f.hits > 0) plan[f.id] = (commandOf[f.id] ?: InfluenceMap.cell(f.x, f.y)).let { it.x * 100 + it.y }
        for (c in commandArmy) {
            if (!(isMelee(c) && !hasRanged(c) && hasMelee(c))) continue
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
                val key = x * 100 + y
                if (key in foeAt || key in ours) continue
                val p = InfluenceMap.cell(x, y)
                if (near.any { e -> getRange(p, e) <= 1 && pinnedAt(p, e, ours, stuck) }) opp = true
            }
            if (!opp) continue
            pinOpp++
            val hit = near.firstOrNull { e -> getRange(mine, e) <= 1 && pinnedAt(mine, e, ours, stuck) } ?: continue
            pinOrd++
            Memory.pinWatch[c.id] = hit.id
        }
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
            "ehparts=${enemyCreeps.sumOf { c -> c.body.count { it.type == HEAL && it.hits > 0 } }}/${enemyCreeps.sumOf { c -> c.body.count { it.type == HEAL } }.let { ehpartsAll = maxOf(ehpartsAll, it); ehpartsAll }} " +
            "hcov=${InfluenceMap.healCoverage().let { (left, total) -> "${(total - left).toInt()}/${total.toInt()}" }} " +
            "hulk=${disarmedFoe.size} hulkreach=$hulkInReach/$hulkTicks revived=$hulkRevived chase=${Memory.chaseOf.size}/$chaseTicks kills=$chaseKills " +
            "capgate=${capBlocked.values.sum()}/$capOffered cap=" + capBlocked.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" } +
            " poised=$poisedTicks/$poisedAll edge=$edgeSpot/$edgeAll capopp=$capOppSum/$capAllSum ffight=$firstFightTick stray=$strayCapRefused sout=$soutOut/$soutBack/$soutTicks" +
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
            "shooters=${army.count { hasWeapon(it) && hasRanged(it) }}/${combatEnemies.count { hasRanged(it) }} abort=$abortTicks/$abortEntries srch=$srchCut/$srchTicks/${cmdTailMax.toInt()} pin=$pinOrd/$pinOpp/$pinHeld/$pinChk fself=$fselfFlip/$fselfAll rotf=$rotfOut/$rotfBack/$rotfTicks/$rotfOn/$rotfF/$rotfA/$rotfN rotfm=$rotfMeet meet=$meetPlan/$meetNear/$meetRot/$meetDone/$meetChk fsw=$fswN/$fswTicks:$fswLost/$fswFar/$fswRanged/$fswGuns/$fswKill/$fswBare ffoc=$ffocBefore/$ffocAfter/$ffocAll ovw=${Executor.ovwContact}/${Executor.ovwRanged} conf=${Arbiter.confReach}/${Arbiter.confFatigue} rtr=$rtrRemoved/$rtrOld/$rtrAdded mquiet=$mquietMoved/$mquietAll/${mquietGain.toInt()} mquietc=$cmdQuietMoved/$cmdQuietAll maj=$majOpened/$majOffers surv=$survTicks/$survLead/$survContact/$survFights adr=$adrN/${(adrE / maxOf(adrN, 1)).toInt()}/${(adrT / maxOf(adrN, 1)).toInt()}/$adrSame fhl=$fhlChosen/$fhlAvail mrush=$rushByArrival/$rushSignalAll/$massArrivalAdded zlb=$zlbTicks/$zlbZero hwall=$hwallTicks/$hwallVictimTicks hwallh=$hwallHeals/$hwallHealsAll hwalla=$hwallAddr/$hwallVictimTicks hwallp=$hwallPredA/$hwallPredL/$hwallPredN postc=$postContest/$postAll rot=$rotOut mdir=$marchFlow/$marchAll/$marchFlip hfull=$hfullN/$hfullAll hover=$hoverSum/$hdelivSum hswap=$hswapN hexp=$hexpN/$hexpAll hlost=$hlostSum hwallx=$hwallYield/$hwallFar hpick=$hpN/$hpAdj/$hpAvail/$hpGate dh=${hpDelta.joinToString(",") { (it / maxOf(hpAvail, 1)).toInt().toString() }} " +
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
internal const val BOT_VERSION = "v290"

/** Печать приборов полей влияния. Сверка со ЗНАЧЕНИЯМИ (chk против прямого пересчёта по крипам,
 *  fldcmp против переносимого incNext) сняла свой вопрос и удалена на этапе 8: 0 из 304 950 клеток и
 *  0 из 22 355 494 сверок по 135 сценариям. Осталось то, что отвечает на живой вопрос, — пики полей:
 *  обнулившееся поле обязано быть ВИДНО, а не тихо давать нули. */
internal const val FIELD_LOG = true

/** Выключено: отрисовка влияния — ~57 000 вызовов contribution за тик (13×13 клеток × 12 стрелков × 28 крипов),
 *  первый тик матча 7 вылетел по таймауту именно в drawDebug; журнал даёт всё, что нужно для разбора. */
internal const val DEBUG_VISUALS = false

internal const val CPU_SLOW_MS = 60.0   // a tick over this prints its phases (the limit is 100 ms; the first tick 1 000)

/** Пара: сколько оставлено в ядре против сколько было свободных. */
internal var symCore = 0

internal var symFree = 0

/** Прибор: мили-тиков, где перевес открыл ворота. Пара к edge=, который считает, где их открыть стоило. */
internal var spotMeleeTicks = 0

/** Вето «сперва туши очаг»: тиков с очагом и из них тех, где вето ИЗМЕНИЛО решение о постуре. */
internal var spotHoldAll = 0

internal var postAll = 0

internal var postContest = 0

internal var rotOut = 0

/** Марш (v232): тиков с направлением по полю потока, тиков с целью марша, разворотов направления на обратное. */
internal var marchFlow = 0

internal var marchAll = 0

internal var marchFlip = 0

/** Лечение по дефициту (v233): лечений в полного / всех, лечения сверх подтверждённой нужды / доставлено, переназначений. */
internal var hfullN = 0

internal var hfullAll = 0

internal var hoverSum = 0

internal var hdelivSum = 0

internal var hswapN = 0

/** Лекарь вне досягаемости (v234): лекаре-тиков в досягаемости / в бою, урон по лекарям. */
internal var hexpN = 0

internal var hexpAll = 0

internal var hlostSum = 0

internal var spotHoldNew = 0

internal var scoutShots = 0

internal var scoutReach = 0

internal var scoutTicks = 0

internal var chaseTicks = 0

internal var chaseKills = 0

/** Прибор локализации: |opp| против |combatEnemies| — если держится единицей, локализация ничего не меняет. */
internal var capOppSum = 0

internal var capAllSum = 0

internal var capOffered = 0

/** Тиков, когда бегун стоял вплотную к назначенному флагу и не брал его, и тиков с назначенным флагом. */
internal var poisedTicks = 0

internal var poisedAll = 0

/** Очаг: тиков-мили с врагом в ENGAGE_RANGE (знаменатель) и из них тех, где МЕСТНАЯ арифметика даёт перевес,
 *  а армейская мера при этом говорит «не наступать». Ненулевой числитель — отпечаток расхождения масштабов. */
internal var edgeSpot = 0

internal var edgeAll = 0

internal var hulkTicks = 0

internal var hulkInReach = 0

internal var hulkRevived = 0

internal var gateFell = 0

internal var goalRebuilds = 0

internal var goalHolds = 0

/** Решений раздачи, где слагаемое цели изменило выбранную клетку, и решений всего. */
internal var goalFlips = 0

internal var goalDecisions = 0

/** Пара «тиков, где наступление удержано сроком / тиков с решением» (v215). */
internal var pushHeldTicks = 0

internal var pushTicks = 0

internal var kiteNow = 0                               // сколько крипов кайтят в этом тике (v135, диагностика)

internal var kiteMassed = false                        // была ли его армия сомкнута в этом тике (v135, диагностика)

internal var planStrict = 0                            // стрелков, вставших в клетку без его мили в двух (v135)

internal var planLoose = 0                             // ...и вставших куда придётся

/** Прибор к USE_RETREAT_BY_HIS_STEP: тиков, где старый признак говорил «отходит», из них тех, где его шаг — нет, и
 *  тиков, где новый говорит «отходит», а старый — нет (мы наступали быстрее, чем он пятился). */
internal var rtrOld = 0

internal var rtrRemoved = 0

internal var rtrAdded = 0

/** Пары к USE_MELEE_QUIET_CELL: шагов мили, где выбранная клетка оставляла удар, и из них тех, где правка увела в клетку
 *  тише; сумма снятой опасности (урон/тик). */
internal var mquietAll = 0

internal var mquietMoved = 0

internal var mquietGain = 0.0

/** ...и то же в раздаче командира (вторая редакция): приказов мили, где выбранная клетка — удар, и из них уведённых. */
internal var cmdQuietAll = 0

internal var cmdQuietMoved = 0

/** Пара к USE_GUARD_IS_CATCHABLE: крипо-проверок, где враг «уходит», и из них тех, где он страж своего флага. */
/** Пара к USE_FLAG_MAJORITY: отказов по паритету при армиях на паритете и из них тех, где флаг давал перевес по флагам. */
internal var majOffers = 0

internal var majOpened = 0

/** Пара к USE_ADDRESSED_DANGER (v224): раздач командира, сумма E и сумма T в выбранных клетках, раздач с T >= E. */
internal var adrN = 0

internal var adrE = 0.0

internal var adrT = 0.0

internal var adrSame = 0

/** Пара к USE_FOCUS_ANY_HEALER (v224): тиков с его лекарем в досягаемости наших стволов и из них тех, где фокус — лекарь. */
internal var fhlAvail = 0

internal var fhlChosen = 0

/** Пара к USE_MASS_BY_ARRIVAL (v226): тиков сигнала броска только по мере прихода / всех тиков сигнала / тиков, где мера
 *  прихода добавила «сомкнут» к мере формы. */
internal var rushByArrival = 0

internal var rushSignalAll = 0

internal var massArrivalAdded = 0

internal var zlbTicks = 0

internal var zlbZero = 0

/** Стена лечения (v270, hwallx=уступлено/дальних): дальних лечений жертвы стены, и сколько из них уступило лечению
 *  вплотную раненого соседа, которое доставляет больше. */
internal var hwallFar = 0

internal var hwallYield = 0

internal var hwallTicks = 0

internal var hwallVictimTicks = 0

internal var hwallHeals = 0

internal var hwallHealsAll = 0

internal var hwallAddr = 0

internal var hwallPredA = 0

internal var hwallPredL = 0

internal var hwallPredN = 0

internal var survTicks = 0

internal var survLead = 0

internal var survContact = 0

/** ...и тиков режима, где уходить некуда и он в контакте — бой строем (вторая редакция). */
internal var survFights = 0

/** Пара «тиков, где ланчестерова мощь и фактический размен расходятся / тиков с признаком» (v216). */
internal var breakOffSplit = 0

internal var breakOffN = 0

internal var runnerModeN = 0

/** Цена простоя бегуна В ОЧКАХ: тик у флага, который нельзя взять, стоит `f.score` очков. */
internal var poisedCost = 0

/** Бюджет командирской гонки: сколько отпущено, каким ядром и из скольких свободных. */
internal var budgetSum = 0

internal var budgetTicks = 0

internal var objAll = 0

internal var objDropN = 0

internal var race100 = ""

internal var race200 = ""

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

internal var abortTicks = 0

internal var abortEntries = 0

internal var meleeBackTicks = 0                        // прибор: тиков, в которые мили ставился ПОЗАДИ строя (v195)

internal var outOfFireTicks = 0                        // крипо-тиков, в которые мили уводился из его кольца

internal var stripTicks = 0                            // тиков, в которые залп сводился на ОДНОГО его лекаря

internal var stateEventTicks = 0

/** Постановка этого тика, как её задают старые решатели (v241, см. Strategist.snapshot): прибор `disp=`. */
internal var dispNow = "-"

internal var lostEnemy = 0      // клетку приказа занял враг (v175)      // приказов, отменённых бегством (v173)     // сколько раз одна клетка была назначена двоим (v172)

internal var cmdTicks = 0                       // тиков, когда командир правил армией (диагностика, v143)

internal var cmdWhyN = 0

/** Пара «отпущено во время боя / отпущено всего» (v215, наблюдение оператора «отряд распадается»). */
internal var splitFight = 0

internal var splitAll = 0

/** Пара «крипо-тиков боя без своего лекаря в дальности лечения / крипо-тиков боя» (v215). */
internal var healGap = 0

/** ...и крипо-тики боя, где своего лекаря нет и в MASS_RANGE — «в бою ни одного хиллера» (v215). */
internal var noMedic = 0

internal var healGapN = 0

/** Пара «смен направления армии / тиков» (v215, наблюдение «разворачиваемся много раз»). */
internal var aimFlips = 0

internal var aimTicks = 0

/** Пара «шагов в клетку под уроном при выключенном слагаемом опасности / всех шагов» (v215). */
internal var dangerBlind = 0

/** ...и отдельно — та же слепота ВНЕ боя. Ноль здесь не дефект прибора, а арифметика: поле урона достаёт
 *  на 4 клетки, а `inCombat` стоит на 5 (см. USE_DANGER_SCALED_BY_AGGRO). */
internal var dangerBlindFar = 0

/** Пара «клеток, отвергнутых как смертельные / оценённых клеток» (v215, см. USE_LETHAL_CELL_VETO). */
internal var lethalHits = 0

internal var lethalCells = 0

internal var dangerMoves = 0

internal var fightTicksNow = 0

internal var concSum = 0                          // сумма «наибольшее число выстрелов в одну цель за тик» с прошлой строки t=

internal var concTicks = 0                        // тиков с выстрелами с прошлой строки t=

/** ...и то же НАКОПЛЕННОЕ за матч (v217). Прежняя пара чистится после каждой строки `t=` (см. LOG_EVERY),
 *  поэтому в разгроме, где последнее окно прошло без единого выстрела, прибор показывал ноль замеров —
 *  и по серии его было не сложить. Порог, ради которого он существует, записан в файле пятикратно:
 *  при 216 лечения в тик цель пробивают четыре-пять стволов. */
internal var concAll = 0

internal var concAllTicks = 0

/** Пара «крипо-тиков веером / всех крипо-тиков огня» (v218). Веер (`rangedMassAttack`) не кладёт ничего в
 *  `shotsAt`, поэтому тик, где все стрелки ушли в веер, НЕ ПОПАДАЕТ ДАЖЕ В ЗНАМЕНАТЕЛЬ `conc` — измеренные
 *  1,67–1,94 ствола на цель сняты по подмножеству тиков, и без этой пары их нельзя читать. */
internal var fanShots = 0

internal var fireShots = 0

internal var lostRaceOffers = 0

internal var gatherHold = 0

/** Пара «крипо-тиков, где стрелку не дали сблизиться до двух из-за живого мили врага / всех крипо-тиков
 *  стрелка в местной агрессии» (v220, см. closeIn). Числитель — сколько раз оговорка вообще сработала. */
internal var closeHeld = 0

internal var closeTicks = 0

/** Пара «крипо-тиков, где ворота броска открыла защита своего / всех крипо-тиков мили при враге рядом»
 *  (v220, см. USE_MELEE_GUARDS_LINE). */
internal var guardFired = 0

internal var guardTicks = 0

/** Тройка «тиков в отходе / из них с точкой отхода / из них под огнём» (v217). Средний числитель обязан
 *  быть нулём, пока `retreatTo` считается по `newPosture`, а постуру перезаписывает командир. */
internal var retrTicks = 0

internal var retrWithPoint = 0

internal var retrUnderFire = 0

/** Пара «крипо-тиков в отходе, где крип стрелял или бил / всех крипо-тиков в отходе» (v217). */
internal var standFire = 0

internal var standTicks = 0

/** Пара «тиков признака outmatched / из них с постурой отхода» (v217, решение оператора). */
internal var outmTicks = 0

internal var outmRetreat = 0

/** ПРИБОРЫ ТЁПЛОГО КОНТАКТА (v221, пары к USE_FIGHT_BY_LEDGER, см. warmNow):
 *  `warm` — тиков контакта без размена / тиков контакта; `warmann` — тиков ANNIHILATE, державшихся только таким
 *  контактом / тиков ANNIHILATE; `warmhold` — из них тиков, где флаг-цель подхватила бы «держим линию»;
 *  `warmcmd` — тиков режима боя при тёплом контакте / тиков режима боя (там постуру вернёт командир);
 *  `warmfight` — тиков «бой идёт» без размена / тиков «бой идёт» (отзыв бегунов, USE_NO_SPLIT_IN_FIGHT);
 *  `warmcap` — отказов захвата `contact.mass` без размена / отказов `contact.mass`. */
internal var warmTicks = 0

internal var warmContact = 0

internal var warmAnn = 0

internal var warmAnnAll = 0

internal var warmHold = 0

internal var warmCmd = 0

internal var warmCmdAll = 0

internal var warmFight = 0

internal var warmFightAll = 0

internal var warmCap = 0

internal var warmCapAll = 0

internal var mconcTicks = 0

internal var mpackAll = 0

internal var kchaseAnn = 0

internal var kvetoHit = 0

internal var kvetoAll = 0

/** Пара «тиков ANNIHILATE со строем стрелков шире RALLY_RANGE / тиков ANNIHILATE» (v221, см. gatherSpread). */
internal var gatherAnn = 0

internal var gatherAnnAll = 0

/** Цель пачки мили (v221, см. USE_MELEE_PACK): липкий id и значение этого тика — для командира. */
/** Пары пачки (v221): «тиков с целью пачки / тиков, где у нас есть мили и у него боевые»; «крипо-тиков мили, чьи
 *  ноги идут к цели пачки / крипо-тиков мили с целью ног, пока цель пачки есть». */
internal var packHeld = 0

internal var packTicks = 0

internal var mpackOnHit = 0

internal var mpackOn = 0
