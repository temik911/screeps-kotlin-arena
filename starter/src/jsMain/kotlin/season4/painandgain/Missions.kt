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

/**
 * ЗАДАНИЯ БЕГУНОВ (v255, этап 10 переработки; план — раздел 1, «Mission»). Бегуны — безоружные и без лечения крипы и те,
 * кого стратег отрядил за флагами, — получают задание `Take` (флаг по паросочетанию «бегун ↔ флаг» по ценности на
 * горизонте) или `Goto` (резерв за армией, выход), а исполняют его режимами FLEE / RESERVE / EXIT / HOLD / TO_FLAG /
 * POISED. Перенесено из объекта `PainAndGain` дословно расширениями.
 */

// ==================== захватчики ====================

/** Флаг, за которым стоит идти захватчику: не наш и без врага на клетке, или наш пустой (охрана клеткой). */
internal fun wantsRunner(f: FlagInfo): Boolean {
    val occ = f.occupant
    if (occ != null && !occ.my) return false
    return !f.ours || occ == null
}

internal fun PainAndGain.runRunners(ctx: Ctx) {
    val runners = ctx.runners
    Memory.idleRunnerIds.clear()
    if (runners.isEmpty()) { Memory.runnerFlag.clear(); return }
    val match = RunnerMatch(ctx, runners, this)
    RunnerMoves(ctx, runners, match, this)
}

/** ПОДСТАДИЯ 1 БЕГУНОВ: назначение — держатели, охрана, пары командира и глобальное жадное паросочетание «захватчик — флаг»; пишет `Memory.runnerFlag`. */
internal class RunnerMatch(private val ctx: Ctx, private val runners: List<Creep>, private val pag: PainAndGain) {
    init { Memory.runnerFlag.keys.retainAll { id -> runners.any { it.id == id } } }
    val flagById = ctx.flags.associateBy { it.id }
    // назначение — глобальное жадное паросочетание по ценности (лучшая пара «захватчик-флаг» первой),
    // НЕ зависящее от порядка обхода: обход в порядке текущих назначений менял очерёдность от тика к
    // тику, два скаута по очереди отбирали друг у друга центральный флаг и полторы тысячи тиков
    // шагали туда-обратно на одной клетке, ни разу не выйдя за неё (матч 1). Взявший флаг идёт за
    // следующим НЕ НАШИМ; сидеть на своём — когда чужих свободных на всех не хватает
    private class Cand(val runner: Creep, val flag: FlagInfo, val value: Double)
    private val cands = ArrayList<Cand>()
    // ДЕРЖАТЕЛЬ ОСТАЁТСЯ (v297, см. HOLD_WATCH): бегун на нашем флаге при его крипе рядом в паросочетании не участвует —
    // флаг за ним и закрыт для других. Прежде сидеть на своём стоило половину его очков, любой чужой флаг перевешивал, и
    // скаут уходил, а его крип вставал на клетку через 11–15 тиков
    val holds = HashMap<String, FlagInfo>()
    init { for (s in runners) pag.heldFlag(ctx, s)?.let { holds[s.id] = it } }
    // ...И ОХРАНА ПРИ НЁМ (v298, см. GROUP_SAFE_DMG): второй из пары стоит рядом с флагом, который взял первый
    private val guards = HashMap<String, FlagInfo>()
    // ...и ОХРАНА ТОЛЬКО ОТ БЕЗДЕЛЬЯ (v335): вооружённый рядом с уже нашим флагом не приносит ничего, а стоит тела.
    // Разбор 16 матчей: на флагах стоит 1,5 нашего тела, и ЕЩЁ 1,5 топчется в 1–3 клетках от флага, который уже наш
    // (34 764 крипо-тика). Те же тела на других флагах — это втрое больше очков, поэтому охрана остаётся, только если
    // брать больше нечего: ни одного не нашего свободного флага и ни одного своего без тела
    private val nothingElse = ctx.flags.none { f -> (!f.ours && f.occupant == null) || (f.ours && f.occupant?.my != true) }
    init {
        if (nothingElse) for (s in runners) if (s.id !in holds && hasWeapon(s) && pag.groupSafe && s.id in Memory.cmdDetach)
            pag.guardFlag(ctx, s)?.let { guards[s.id] = it }
    }
    // ...и пара, посланная командиром, идёт к своему флагу вместе, а не расходится паросочетанием по одному
    private val orders = HashMap<String, FlagInfo>()
    init {
        if (pag.groupSafe) for (s in runners) if (s.id !in holds && s.id !in guards && s.id in Memory.cmdDetach)
            Memory.runnerFlag[s.id]?.let { id -> flagById[id] }?.takeIf { it.occupant == null || it.occupant?.my == true }?.let { orders[s.id] = it }
    }
    // страховка CPU (v131): тик уже дороже CPU_GUARD_MS — бегуны оставляют прежние флаги, кандидаты не пересчитываются
    private val cpuGuard =  getTicks() > 1 && cpuMs() > CPU_GUARD_MS
    init { if (cpuGuard && DEBUG_LOG) println("cpu t=${getTicks()} guard: runners keep their flags (${(cpuMs() * 10).toInt() / 10.0}ms)") }
    init {
        if (!cpuGuard) for (s in runners) {
            if (s.id in holds || s.id in guards || s.id in orders) continue
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
                    if (pag.ourPowerOf(listOf(s), listOf(e)) <= pag.enemyPowerOf(listOf(e), listOf(s))) continue
                }
                val flow = flowTo(ctx, f.pos)
                val ticks = pathTicks(s, flow, s.key)
                if (ticks >= Int.MAX_VALUE / 4) continue
                // стая у флага — охрана рядом И те, кто дойдёт до него раньше нас: скаут шёл к дальнему H4, пока
                // армия врага шла туда же, и вошёл в неё (матч 3, t=70–87); охраны в 11 клетках было мало.
                // Для вооружённого бегуна стая — только та, что сильнее его (v57)
                val pack = packAt(ctx, f.pos, flow, ticks)
                // тихий фермер бегуну не стая (v105, USE_FARMER_RUNNER_PACK_FREE); безоружному скауту — по-прежнему стая
                if (pack.isNotEmpty() &&
                    (!armedRunner || pag.enemyPowerOf(pack, listOf(s)) >= pag.ourPowerOf(listOf(s), pack))) continue
                // при охотнике (см. escapeFlows) флаг без выхода — карман: три безоружных крипа сидели на угловых флагах,
                // пока армия врага шла к ним, и были добиты по одному — последний на 545-м тике, аннигиляция при +5000
                // очков (матч 13)
                // СВОБОДНЫЙ ФЛАГ БЕГУНА (v130, USE_RUNNER_FREE_FLAG): вето запаса выхода не касается бегуна без оружия, если его ближайший
                // угрожающий крип дальше от флага, чем путь бегуна плюс порог его бегства (SCOUT_FLEE_TRIGGER): бегун дойдёт раньше,
                // чем угроза войдёт в его порог. Матч 407: армия 530 тиков в EVADE при его армии в 47 клетках, бегун scout_1 всё
                // время RESERVE — у каждого флага запас выхода отрицателен или неизвестен; бегун — M1 на 100 хитов, армию не тянет
                val hisNearestToFlag = ctx.threats.minOfOrNull { getRange(it, f.pos) } ?: Int.MAX_VALUE / 4
                if (pag.escapeFlows.isNotEmpty() && pag.exitMargin(ctx, f.pos, ticks) < 0) continue
                // свой пустой флаг стоит половину — но СИДЯЩИЙ на нём закрывает клетку от чужих бегунов (матч 2:
                // центральный D5 забрал вражеский M1, пока армия уходила за соседним флагом, и вернуть его было
                // некому); чужой — двойной размен; флаг, который порог силы сейчас не разрешает, — пятую часть
                // (ждать у него можно, но сидеть на своём полезнее); дорогой по силе — позже дешёвого (см.
                // captureCost); текущий — с премией
                val gain = (if (f.ours) 0.5 * f.score else f.swing) * pag.captureCost(ctx, f)
                val horizon = if (pag.farmerQuietNow) maxOf(1, arenaInfo.ticksLimit - getTicks() - ticks).toDouble() else 1.0 / (ticks + 5)
                val value = gain * horizon *
                    (if (f.id == currentId) 1.25 else 1.0) * (if (!f.ours && !pag.captureAllowed(ctx, f, serious = false)) 0.2 else 1.0)   // оценка, не ворота (v451, capeval=)
                cands.add(Cand(s, f, value))
            }
        }
    }
    init { cpuMark("r.cands") }
    init { cands.sortByDescending { it.value } }
    private val assigned = HashSet<String>()
    private val taken = HashSet<String>()
    init { for ((id, f) in holds) { assigned.add(id); taken.add(f.id); Memory.runnerFlag[id] = f.id; holdPinned.n++ } }
    init { for ((id, f) in guards) { assigned.add(id); Memory.runnerFlag[id] = f.id; flagGuardTicks.n++ } }
    init { for ((id, f) in orders) { assigned.add(id); taken.add(f.id); Memory.runnerFlag[id] = f.id } }
    init { if (cpuGuard) for (s in runners) Memory.runnerFlag[s.id]?.let { id -> if (flagById[id] != null) { assigned.add(s.id); taken.add(id) } } }
    init {
        for (c in cands) {
            if (c.runner.id in assigned || c.flag.id in taken) continue
            assigned.add(c.runner.id)
            taken.add(c.flag.id)
            Memory.runnerFlag[c.runner.id] = c.flag.id
        }
    }
    // пары (v94): флаг со стаей, с которой один не справится, — двум ближайшим свободным вооружённым, если справятся вдвоём
    init {
        if (!cpuGuard) {
            for (f in ctx.flags) {
                if (f.ours || f.id in taken) continue
                if (f.occupant?.my == true) continue
                val free = runners.filter { it.id !in assigned && hasWeapon(it) }.sortedBy { getRange(it, f.pos) }.take(2)
                if (free.size < 2) continue
                val flow = flowTo(ctx, f.pos)
                val ticks = free.maxOf { pathTicks(it, flow, it.key) }
                if (ticks >= Int.MAX_VALUE / 4) continue
                val occ = ctx.enemyCreeps.filter { it.x == f.pos.x && it.y == f.pos.y }
                val pack = (packAt(ctx, f.pos, flow, ticks) + occ).distinctBy { it.id }
                if (pack.isEmpty()) continue
                if (pag.ourPowerOf(free, pack) <= pag.enemyPowerOf(pack, free)) continue
                if (pag.escapeFlows.isNotEmpty() && pag.exitMargin(ctx, f.pos, ticks) < 0) continue
                for (r in free) { assigned.add(r.id); Memory.runnerFlag[r.id] = f.id }
                taken.add(f.id)
            }
        }
    }
    init { for (s in runners) if (s.id !in assigned) Memory.runnerFlag.remove(s.id) }
}

/** ПОДСТАДИЯ 2: ход каждого бегуна по назначенному флагу — удар вплотную, бегство, резерв, выход, удержание, охрана, подход; прибор забега (`dbg`). */
internal class RunnerMoves(private val ctx: Ctx, private val runners: List<Creep>, private val match: RunnerMatch, private val pag: PainAndGain) {
    private fun dbg(s: Creep, mode: String, f: FlagInfo?, step: Position? = null) {
        // ПРИБОР ЗАБЕГА (v216): все ветки поведения бегуна проходят ровно здесь, поэтому счёт стоит тут, а не
        // в каждой из них. Считается ВСЕГДА, независимо от DEBUG_LOG: прибор, который виден только в логе с
        // подробностями, нельзя сложить по серии
        val tag = mode.substringBefore(':')
        runnerMode.bump(tag)
        runnerModeN.n++
        // ...и цена простоя В ОЧКАХ, а не в тиках: тик у флага, который нельзя взять, стоит его score.
        // Разбор v214 считал эту величину вручную («166 тиков x 3 очка ~ 500, матч проигран с разрывом 292»)
        if (tag == "POISED" && f != null) poisedCost.n += f.score
        if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
            println("  r${s.id} (${s.x},${s.y}) ${bodySummary(s)} hits=${s.hits} $mode flag=${f?.let { "(${it.pos.x},${it.pos.y})${typeChar(it.type)}${it.score}my=${it.mine}" } ?: "-"} fatigue=${s.fatigue} step=${step?.let { "(${it.x},${it.y})" } ?: "stay"}${if (TrafficManager.isStuck(s.id)) " STUCK" else ""}")
        }
    }
    init {
        for (s in runners) {
            val f = Memory.runnerFlag[s.id]?.let { match.flagById[it] }
            val onIt = f != null && s.x == f.pos.x && s.y == f.pos.y
            val nearby = ctx.combatEnemies.filter { getRange(s, it) <= RANGED_RANGE + 2 }
            val underFire = InfluenceMap.damageAt(s.x, s.y, ctx.combatEnemies) > 0.0
            // мили-бегун (отряд) рубит вплотную (v57): healAndShoot за бегунов только стреляет, удар мили выдаёт цикл армии
            if (hasMelee(s)) ctx.enemyCreeps.filter { getRange(s, it) <= 1 }.minByOrNull { it.hits }?.let { Executor.attack(s, it) }
            // захватчик без замены: от врага «с боем» ближе SCOUT_FLEE_TRIGGER — прочь (пустой MOVE ходит клетку за тик и
            // по болоту, где стрелок вязнет), даже с флага: флаг останется нашим, пока враг сам на него не встанет
            val threats = ctx.combatEnemies.filter { getRange(s, it) <= SCOUT_FLEE_TRIGGER && threatening(it, ctx.enemyCreeps) }
            // ...а ВООРУЖЁННЫЙ бегун бежит от силы, а не от всякого (v297, см. HOLD_WATCH): его одиночный стрелок или мили в
            // восьми клетках снимал нашего бойца с флага, хотя тот бьёт его один на один; уходит, когда его стволы рядом
            // перевешивают наших в досягаемости
            val outgunned = !hasWeapon(s) || run {
                val foes = ctx.combatEnemies.filter { getRange(s, it) <= SCOUT_FLEE_TRIGGER }
                val mates = (ctx.army + runners).filter { hasWeapon(it) && getRange(s, it) <= RANGED_RANGE }
                foes.isNotEmpty() && pag.enemyPowerOf(foes, mates) >= pag.ourPowerOf(mates, foes)
            }
            // ⚠️ ОТВЕРГНУТО ЗАМЕРОМ (v329): «держатель уходит только от полученного урона, а не от счёта стволов» (v327).
            // Основание было сильным — 90,6 % потерь флага это сход держателя (581 из 641), ни одной потери под нашим телом,
            // у 82 % сошедших ноль урона за предыдущие десять тиков, а сидеть дёшево (1 177 эпизодов, 0,5 % смертей), — но
            // держать стало нечем: захватов 17 против 37 за матч, наших флагов 1,41 против 1,89, тел на флагах 1,22 против
            // 1,51. Сидящий крип перестаёт быть тем, кто берёт следующий флаг, а берут у нас те же два-четыре тела
            // ...а КРИП ПОСТОЯННОГО ГАРНИЗОНА УХОДИТ, КОГДА ЕГО ВОЗМОЖНЫЙ УРОН ПО КЛЕТКЕ БОЛЬШЕ НАШЕГО ФАКТИЧЕСКОГО
            // ЛЕЧЕНИЯ НА НЕЙ (v340, правило оператора 16.09.2026: «уходить только тогда, когда его потенциальный урон
            // превышает наш фактический хил, а не по количеству крипов»). Обе прежние меры были грубыми: «не уходит вовсе»
            // (v338) дало 1,82 нашего флага против 2,60 — стоящий до конца гибнет и выбывает насовсем; «уходит от двоих»
            // (v339) считает головы, а решает размен. Обе величины уже есть в поле влияния: damageAt — сумма его стволов,
            // достающих клетку (с его дебаффами), healAt — лечение наших лекарей в дальности (с нашими)
            val incoming = InfluenceMap.damageAt(s.x, s.y, ctx.combatEnemies)
            val healing = InfluenceMap.healAt(s.x, s.y, ctx.armyWithHeal)
            val garrisonStays = pag.groupSafe && Memory.garrisonOf[s.id] != null && match.holds.containsKey(s.id) &&
                s.hits * 2 >= s.hitsMax && incoming <= healing
            if (garrisonStays && (underFire || threats.isNotEmpty())) holdArmedStay.n++
            if (canMove(s) && (underFire || threats.isNotEmpty()) && !outgunned) holdArmedStay.n++
            if (canMove(s) && (underFire || threats.isNotEmpty()) && outgunned && !garrisonStays) {
                // поиск пути бегства может не дать шага (скаут в матче 3 «бежал» на месте три тика и погиб) —
                // тогда жадно: соседняя клетка подальше от врагов и под меньшим огнём; в опасности шаг делается ВСЕГДА,
                // и на не лучшую клетку тоже: скаут у стены (4,40) «бежал» стоя тридцать тиков рядом с боем и погиб
                // (матч 12) — стоящего враг на равной скорости достаёт следующим тиком, идущего нет
                val foes = threats.ifEmpty { ctx.combatEnemies }
                val danger = underFire || nearby.isNotEmpty()
                // ...а ГАРНИЗОННЫЙ ОТХОДИТ НА ШАГ ИЗ-ПОД ВЫСТРЕЛА (v347): бегство на SCOUT_FLEE_RANGE уводит его на дюжину
                // клеток, и флаг стоит пустым все двадцать тиков дороги туда и обратно; из четырёх-семи закреплённых стоит
                // в среднем 2,4. Достаточно выйти за дальность его стрелка — вернётся он через два-три тика
                val fleeTo = if (pag.groupSafe && Memory.garrisonOf[s.id] != null) RANGED_RANGE + 1 else SCOUT_FLEE_RANGE
                val step = fleeStep(s, foes, ctx.dangerMatrix, fleeTo) ?: greedyFlee(ctx, s, foes, force = danger)
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
            if (f != null && pag.escapeFlows.isNotEmpty() && getRange(s, f.pos) <= 2 && pag.exitMargin(ctx, f.pos, 0) < 0) {
                val to = pag.runnerEscape(ctx, s)
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
            // ОХРАНА (v298): у флага, который держит свой, стоит рядом; дальше клетки — подходит
            if (f.ours && f.occupant?.my == true && f.occupant?.id != s.id) {
                val step = if (getRange(s, f.pos) > 1) pathStep(s, f.pos, 1, crowdMatrixOf(ctx, -1)) else null
                if (step != null) TrafficManager.request(s, step, Arbiter.RUNNER_PRIORITY)
                dbg(s, "GUARD", f, step)
                continue
            }
            // брать ли флаг сейчас (дебафф): нельзя — ждём рядом, шаг на клетку сделаем, когда станет можно
            val block = pag.captureBlock(ctx, f)
            val allowed = block == null
            val range = if (allowed) 0 else 1
            // свой назначенный флаг открыт для шага, остальные не наши — стены (см. Ctx.flagCells)
            // при запрете захвата клетка флага — стена и для его же бегуна: путь к «зазору 1» шёл ЧЕРЕЗ флаг, и скаут брал R3
            // на 49-м при уклонении с 3-го (матч 56), как и в четырёх боях с けろびー до правила v34/v35
            // ПАРА ИДЁТ ВМЕСТЕ (v158): на флаг под стаей отправляются двое (см. USE_RUNNER_PAIRS), потому что один не
            // справится, — но шли они каждый своим путём и приходили порознь, то есть по одному против той же стаи.
            // Идущий впереди ждёт отставшего: тот же кулак, только на двоих
            val step = if (s.getRangeTo(f.pos) > range) pathStep(s, f.pos, range, crowdMatrixOf(ctx, if (allowed) f.pos.key else -1)) else null
            if (step != null) { TrafficManager.request(s, step, Arbiter.RUNNER_PRIORITY); pag.planCapture(ctx, step) }
            // прибор наблюдения 4: бегун дошёл до флага, и ему запрещено на него встать. Пара «стоя/всего с целью»
            poisedAll.n++
            if (!allowed && step == null) poisedTicks.n++
            dbg(s, if (allowed) "TO_FLAG" else "POISED:$block", f, step)
        }
    }
}

internal const val SCOUT_FLEE_RANGE = 12

// ==================== приборы стадии: счётчик живёт у того, кто считает (v447, план архитектуры, 4.7 и этап 6) ====================
// Объявления перенесены из Instruments.kt дословно; Instruments их читает и печатает, текст строк прежний.

/** Удержание флага (v297, hold=закреплено/осталось/гонка/бой): бегуно-тики, закреплённые за своим флагом правилом
 *  HOLD_WATCH; вооружённый бегун, который прежде бежал бы, а сила врага рядом его не перевешивает; держатели, оставленные
 *  командирской гонкой; держатели, не отозванные боем вне контакта ядра. */
internal val holdPinned = Gauges.counter("hold")

internal val flagGuardTicks = Gauges.counter("fguard")

internal val holdArmedStay = Gauges.counter("hold", 1)

/** Тиков, когда бегун стоял вплотную к назначенному флагу и не брал его, и тиков с назначенным флагом. */
internal val poisedTicks = Gauges.counter("poised")

internal val poisedAll = Gauges.counter("poised", 1)

internal val runnerModeN = Gauges.counter("runner", 1)

/** Цена простоя бегуна В ОЧКАХ: тик у флага, который нельзя взять, стоит `f.score` очков. */
internal val poisedCost = Gauges.counter("poisedcost")

/** Чем заняты бегуны: пары по режимам (`dbg` — единственная точка, через которую проходят все ветки). */
internal val runnerMode = Gauges.labelled("runner")
