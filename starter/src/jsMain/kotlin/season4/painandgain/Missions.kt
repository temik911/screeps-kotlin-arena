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
 * ЗАДАНИЯ БЕГУНОВ (v255, этап 10 переработки; план — раздел 1, «Mission»). Бегуны — безоружные и без лечения крипы и те,
 * кого стратег отрядил за флагами, — получают задание `Take` (флаг по паросочетанию «бегун ↔ флаг» по ценности на
 * горизонте) или `Goto` (резерв за армией, выход), а исполняют его режимами FLEE / RESERVE / EXIT / HOLD / TO_FLAG /
 * POISED. Перенесено из объекта `PainAndGain` дословно расширениями.
 */

// ==================== захватчики ====================

/** Флаг, за которым стоит идти захватчику: не наш и без врага на клетке, или наш пустой (охрана клеткой). */
internal fun PainAndGain.wantsRunner(f: FlagInfo): Boolean {
    val occ = f.occupant
    if (occ != null && !occ.my) return false
    return !f.ours || occ == null
}

internal fun PainAndGain.runRunners(ctx: Ctx) {
    val runners = ctx.runners
    Memory.idleRunnerIds.clear()
    if (runners.isEmpty()) { Memory.runnerFlag.clear(); return }
    Memory.runnerFlag.keys.retainAll { id -> runners.any { it.id == id } }
    val flagById = ctx.flags.associateBy { it.id }
    fun dbg(s: Creep, mode: String, f: FlagInfo?, step: Position? = null) {
        // ПРИБОР ЗАБЕГА (v216): все ветки поведения бегуна проходят ровно здесь, поэтому счёт стоит тут, а не
        // в каждой из них. Считается ВСЕГДА, независимо от DEBUG_LOG: прибор, который виден только в логе с
        // подробностями, нельзя сложить по серии
        val tag = mode.substringBefore(':')
        runnerMode[tag] = (runnerMode[tag] ?: 0) + 1
        runnerModeN++
        // ...и цена простоя В ОЧКАХ, а не в тиках: тик у флага, который нельзя взять, стоит его score.
        // Разбор v214 считал эту величину вручную («166 тиков x 3 очка ~ 500, матч проигран с разрывом 292»)
        if (tag == "POISED" && f != null) poisedCost += f.score
        if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
            println("  r${s.id} (${s.x},${s.y}) ${bodySummary(s)} hits=${s.hits} $mode flag=${f?.let { "(${it.pos.x},${it.pos.y})${typeChar(it.type)}${it.score}my=${it.mine}" } ?: "-"} fatigue=${s.fatigue} step=${step?.let { "(${it.x},${it.y})" } ?: "stay"}${if (TrafficManager.isStuck(s.id)) " STUCK" else ""}")
        }
    }
    // назначение — глобальное жадное паросочетание по ценности (лучшая пара «захватчик-флаг» первой),
    // НЕ зависящее от порядка обхода: обход в порядке текущих назначений менял очерёдность от тика к
    // тику, два скаута по очереди отбирали друг у друга центральный флаг и полторы тысячи тиков
    // шагали туда-обратно на одной клетке, ни разу не выйдя за неё (матч 1). Взявший флаг идёт за
    // следующим НЕ НАШИМ; сидеть на своём — когда чужих свободных на всех не хватает
    class Cand(val runner: Creep, val flag: FlagInfo, val value: Double)
    val cands = ArrayList<Cand>()
    // страховка CPU (v131): тик уже дороже CPU_GUARD_MS — бегуны оставляют прежние флаги, кандидаты не пересчитываются
    val cpuGuard =  getTicks() > 1 && cpuMs() > CPU_GUARD_MS
    if (cpuGuard && DEBUG_LOG) println("cpu t=${getTicks()} guard: runners keep their flags (${(cpuMs() * 10).toInt() / 10.0}ms)")
    if (!cpuGuard) for (s in runners) {
        val currentId = Memory.runnerFlag[s.id]
        val armedRunner = hasWeapon(s)
        for (f in ctx.flags) {
            val occ = f.occupant
            // занято (чужим — ждём отряд; своим — тот и держит). Вооружённый бегун (отряд, см. USE_DETACH) идёт на флаг с
            // ОДИНОЧНЫМ врагом слабее себя — в матче 126 отряд стоял POISED в клетке от D5, пока на нём сидел его крип
            if (occ != null && occ.id != s.id) {
                if (!armedRunner || occ.my) continue
                val e = ctx.enemyCreeps.firstOrNull { it.x == f.pos.x && it.y == f.pos.y } ?: continue
                if (ctx.enemyCreeps.any { it.id != e.id && getRange(it, f.pos) <= ENGAGE_RANGE }) continue
                if (ourPowerOf(listOf(s), listOf(e)) <= enemyPowerOf(listOf(e), listOf(s))) continue
            }
            val flow = flowTo(ctx, f.pos)
            val ticks = pathTicks(s, flow, s.x * 100 + s.y)
            if (ticks >= Int.MAX_VALUE / 4) continue
            // стая у флага — охрана рядом И те, кто дойдёт до него раньше нас: скаут шёл к дальнему H4, пока
            // армия врага шла туда же, и вошёл в неё (матч 3, t=70–87); охраны в 11 клетках было мало.
            // Для вооружённого бегуна стая — только та, что сильнее его (v57)
            val pack = packAt(ctx, f.pos, flow, ticks)
            // тихий фермер бегуну не стая (v105, USE_FARMER_RUNNER_PACK_FREE); безоружному скауту — по-прежнему стая
            if (pack.isNotEmpty() &&
                (!armedRunner || enemyPowerOf(pack, listOf(s)) >= ourPowerOf(listOf(s), pack))) continue
            // при охотнике (см. escapeFlows) флаг без выхода — карман: три безоружных крипа сидели на угловых флагах,
            // пока армия врага шла к ним, и были добиты по одному — последний на 545-м тике, аннигиляция при +5000
            // очков (матч 13)
            // СВОБОДНЫЙ ФЛАГ БЕГУНА (v130, USE_RUNNER_FREE_FLAG): вето запаса выхода не касается бегуна без оружия, если его ближайший
            // угрожающий крип дальше от флага, чем путь бегуна плюс порог его бегства (SCOUT_FLEE_TRIGGER): бегун дойдёт раньше,
            // чем угроза войдёт в его порог. Матч 407: армия 530 тиков в EVADE при его армии в 47 клетках, бегун scout_1 всё
            // время RESERVE — у каждого флага запас выхода отрицателен или неизвестен; бегун — M1 на 100 хитов, армию не тянет
            val hisNearestToFlag = ctx.combatEnemies.filter { threatening(it, ctx.enemyCreeps) }.minOfOrNull { getRange(it, f.pos) } ?: Int.MAX_VALUE / 4
            if (escapeFlows.isNotEmpty() && exitMargin(ctx, f.pos, ticks) < 0) continue
            // свой пустой флаг стоит половину — но СИДЯЩИЙ на нём закрывает клетку от чужих бегунов (матч 2:
            // центральный D5 забрал вражеский M1, пока армия уходила за соседним флагом, и вернуть его было
            // некому); чужой — двойной размен; флаг, который порог силы сейчас не разрешает, — пятую часть
            // (ждать у него можно, но сидеть на своём полезнее); дорогой по силе — позже дешёвого (см.
            // captureCost); текущий — с премией
            val gain = (if (f.ours) 0.5 * f.score else f.swing) * captureCost(ctx, f)
            val horizon = if (farmerQuietNow) maxOf(1, arenaInfo.ticksLimit - getTicks() - ticks).toDouble() else 1.0 / (ticks + 5)
            val value = gain * horizon *
                (if (f.id == currentId) 1.25 else 1.0) * (if (!f.ours && !captureAllowed(ctx, f)) 0.2 else 1.0)
            cands.add(Cand(s, f, value))
        }
    }
    cpuMark("r.cands")
    cands.sortByDescending { it.value }
    val assigned = HashSet<String>()
    val taken = HashSet<String>()
    if (cpuGuard) for (s in runners) Memory.runnerFlag[s.id]?.let { id -> if (flagById[id] != null) { assigned.add(s.id); taken.add(id) } }
    for (c in cands) {
        if (c.runner.id in assigned || c.flag.id in taken) continue
        assigned.add(c.runner.id)
        taken.add(c.flag.id)
        Memory.runnerFlag[c.runner.id] = c.flag.id
    }
    // пары (v94): флаг со стаей, с которой один не справится, — двум ближайшим свободным вооружённым, если справятся вдвоём
    if (!cpuGuard) {
        for (f in ctx.flags) {
            if (f.ours || f.id in taken) continue
            if (f.occupant?.my == true) continue
            val free = runners.filter { it.id !in assigned && hasWeapon(it) }.sortedBy { getRange(it, f.pos) }.take(2)
            if (free.size < 2) continue
            val flow = flowTo(ctx, f.pos)
            val ticks = free.maxOf { pathTicks(it, flow, it.x * 100 + it.y) }
            if (ticks >= Int.MAX_VALUE / 4) continue
            val occ = ctx.enemyCreeps.filter { it.x == f.pos.x && it.y == f.pos.y }
            val pack = (packAt(ctx, f.pos, flow, ticks) + occ).distinctBy { it.id }
            if (pack.isEmpty()) continue
            if (ourPowerOf(free, pack) <= enemyPowerOf(pack, free)) continue
            if (escapeFlows.isNotEmpty() && exitMargin(ctx, f.pos, ticks) < 0) continue
            for (r in free) { assigned.add(r.id); Memory.runnerFlag[r.id] = f.id }
            taken.add(f.id)
        }
    }
    for (s in runners) if (s.id !in assigned) Memory.runnerFlag.remove(s.id)

    for (s in runners) {
        val f = Memory.runnerFlag[s.id]?.let { flagById[it] }
        val onIt = f != null && s.x == f.pos.x && s.y == f.pos.y
        val nearby = ctx.combatEnemies.filter { getRange(s, it) <= RANGED_RANGE + 2 }
        val underFire = InfluenceMap.damageAt(s.x, s.y, ctx.combatEnemies) > 0.0
        // мили-бегун (отряд) рубит вплотную (v57): healAndShoot за бегунов только стреляет, удар мили выдаёт цикл армии
        if (hasMelee(s)) ctx.enemyCreeps.filter { getRange(s, it) <= 1 }.minByOrNull { it.hits }?.let { Executor.attack(s, it) }
        // захватчик без замены: от врага «с боем» ближе SCOUT_FLEE_TRIGGER — прочь (пустой MOVE ходит клетку за тик и
        // по болоту, где стрелок вязнет), даже с флага: флаг останется нашим, пока враг сам на него не встанет
        val threats = ctx.combatEnemies.filter { getRange(s, it) <= SCOUT_FLEE_TRIGGER && threatening(it, ctx.enemyCreeps) }
        if (canMove(s) && (underFire || threats.isNotEmpty())) {
            // поиск пути бегства может не дать шага (скаут в матче 3 «бежал» на месте три тика и погиб) —
            // тогда жадно: соседняя клетка подальше от врагов и под меньшим огнём; в опасности шаг делается ВСЕГДА,
            // и на не лучшую клетку тоже: скаут у стены (4,40) «бежал» стоя тридцать тиков рядом с боем и погиб
            // (матч 12) — стоящего враг на равной скорости достаёт следующим тиком, идущего нет
            val foes = threats.ifEmpty { ctx.combatEnemies }
            val danger = underFire || nearby.isNotEmpty()
            val step = fleeStep(s, foes, ctx.dangerMatrix, SCOUT_FLEE_RANGE) ?: greedyFlee(ctx, s, foes, force = danger)
            if (step != null) TrafficManager.request(s, step, Arbiter.RUNNER_PRIORITY)
            dbg(s, "FLEE", f, step)
            continue
        }
        if (f == null) {
            // все флаги при деле: к армии, за её спиной
            val step = if (s.getRangeTo(ctx.ourCentroid) > POST_STANDOFF + 2) pathStep(s, ctx.ourCentroid, POST_STANDOFF + 2, crowdMatrixOf(ctx, -1)) else null
            if (step != null) TrafficManager.request(s, step, Arbiter.RUNNER_PRIORITY)
            Memory.idleRunnerIds.add(s.id)
            dbg(s, "RESERVE", null, step)
            continue
        }
        // выход закрывается (см. exitMargin): с флага — в лучшую точку выхода, пока устье открыто
        if (f != null && escapeFlows.isNotEmpty() && getRange(s, f.pos) <= 2 && exitMargin(ctx, f.pos, 0) < 0) {
            val to = runnerEscape(ctx, s)
            if (to != null) {
                val step = pathStep(s, to, 1, ctx.dangerMatrix)
                if (step != null) TrafficManager.request(s, step, Arbiter.RUNNER_PRIORITY)
                dbg(s, "EXIT", f, step)
                continue
            }
        }
        if (onIt) {
            dbg(s, if (f.ours) "HOLD" else "HOLD_WAIT", f)
            continue
        }
        // брать ли флаг сейчас (дебафф): нельзя — ждём рядом, шаг на клетку сделаем, когда станет можно
        val block = captureBlock(ctx, f)
        val allowed = block == null
        val range = if (allowed) 0 else 1
        // свой назначенный флаг открыт для шага, остальные не наши — стены (см. Ctx.flagCells)
        // при запрете захвата клетка флага — стена и для его же бегуна: путь к «зазору 1» шёл ЧЕРЕЗ флаг, и скаут брал R3
        // на 49-м при уклонении с 3-го (матч 56), как и в четырёх боях с けろびー до правила v34/v35
        // ПАРА ИДЁТ ВМЕСТЕ (v158): на флаг под стаей отправляются двое (см. USE_RUNNER_PAIRS), потому что один не
        // справится, — но шли они каждый своим путём и приходили порознь, то есть по одному против той же стаи.
        // Идущий впереди ждёт отставшего: тот же кулак, только на двоих
        val step = if (s.getRangeTo(f.pos) > range) pathStep(s, f.pos, range, crowdMatrixOf(ctx, if (allowed) f.pos.x * 100 + f.pos.y else -1)) else null
        if (step != null) { TrafficManager.request(s, step, Arbiter.RUNNER_PRIORITY); planCapture(ctx, step) }
        // прибор наблюдения 4: бегун дошёл до флага, и ему запрещено на него встать. Пара «стоя/всего с целью»
        poisedAll++
        if (!allowed && step == null) poisedTicks++
        dbg(s, if (allowed) "TO_FLAG" else "POISED:$block", f, step)
    }
}

internal const val SCOUT_FLEE_RANGE = 12
