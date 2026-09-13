package season4.painandgain

import screeps.api.CostMatrix
import screeps.api.Creep
import screeps.api.Position
import screeps.api.season4.ScoreFlag
import screeps.api.ATTACK
import screeps.api.ATTACK_POWER
import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.CARRY_CAPACITY
import screeps.api.EFF_ATTACK_MODIFIER
import screeps.api.EFF_DAMAGE_TAKEN_MODIFIER
import screeps.api.EFF_HEAL_MODIFIER
import screeps.api.EFF_RANGED_ATTACK_MODIFIER
import screeps.api.HEAL
import screeps.api.HEAL_POWER
import screeps.api.MOVE
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
 * МИР ТИКА (v238, этап 3 переработки): снимок того, что видно, — и ничего больше. Пока здесь только типы, `FlagInfo` и
 * `Ctx`, перенесённые из `PainAndGain.kt` без изменений; сборка снимка переезжает сюда вместе с памятью между тиками на
 * этапе 4 (см. docs/pain-and-gain-rework.md, разделы 2 и 8).
 */

/** Флаг очков в этом тике: владелец, тип дебаффа, очки, кто стоит на клетке и чья охрана рядом. */
internal class FlagInfo(val flag: ScoreFlag, val mine: Boolean?, val type: String, val score: Int, val occupant: Creep?, val guards: List<Creep>) {
    val id: String get() = flag.id
    val pos: Position get() = flag
    val ours: Boolean get() = mine == true
    val theirs: Boolean get() = mine == false
    /** Очки в тик, которые даёт захват: чужой флаг — двойной размен (нам плюс, врагу минус). */
    val swing: Double get() = if (theirs) 2.0 * score else score.toDouble()
}

internal class Ctx(
    val home: Position,
    val enemyHome: Position,
    val myCreeps: List<Creep>,
    val active: List<Creep>,
    val army: List<Creep>,      // с оружием или лечением
    val runners: List<Creep>,   // безоружные и без лечения: захватчики
    val enemyCreeps: List<Creep>,
    val combatEnemies: List<Creep>,
    val blocked: List<Position>,
    /** Опасность (без флагов) — основа для матриц пути. */
    val rawDanger: CostMatrix,
    /** Опасность + НЕ НАШИ флаги как стены: путь без назначения на флаг не ступает. */
    val dangerMatrix: CostMatrix,
    val flags: List<FlagInfo>,
    /** Клетки не наших флагов (x*100+y): захват — только назначенным, см. flagBlocked. */
    val flagCells: Set<Int>,
    val flagBlocked: List<Position>,
    /** Вся боевая армия врага не сделала ни шага PASSIVE_TICKS тиков (порог захвата один, см. captureAllowed). */
    val passiveEnemy: Boolean,
    val ourCentroid: Position,
    val enemyCentroid: Position?,
)

// ==================== флаги и эффекты ====================

internal fun PainAndGain.collectFlags(myCreeps: List<Creep>, enemyCreeps: List<Creep>, combatEnemies: List<Creep>): List<FlagInfo> {
    val result = ArrayList<FlagInfo>()
    val all = getObjectsByPrototype(ScoreFlag::class).filter { it.exists }
    for (f in all) {
        val occupant = (myCreeps + enemyCreeps).firstOrNull { !it.spawning && it.x == f.x && it.y == f.y }
        val guards = combatEnemies.filter { getRange(it, f) <= FLAG_GUARD_RANGE }
        result.add(FlagInfo(f, f.my, f.effectType, f.scorePerTick, occupant, guards))
    }
    // журнал захватов: смена владельца и кто стоит на клетке — проверка механики «встал — захватил»
    for (fi in result) {
        val owner = if (fi.ours) 1 else if (fi.theirs) -1 else 0
        val prev = Memory.lastFlagOwner[fi.id]
        if (prev != null && prev != owner) flagFlipNow = true       // событие для стратега (v242)
        if (prev != null && prev != owner && DEBUG_LOG) {
            println("flag t=${getTicks()}: (${fi.pos.x},${fi.pos.y})${typeChar(fi.type)}${fi.score} owner ${ownerName(prev)} -> ${ownerName(owner)} occupant=${fi.occupant?.let { "${if (it.my) "my" else "enemy"} ${bodySummary(it)}" } ?: "none"}")
        }
        Memory.lastFlagOwner[fi.id] = owner
    }
    return result
}

internal fun PainAndGain.ownerName(code: Int) = when (code) { 1 -> "us"; -1 -> "enemy"; else -> "none" }

/** Множитель стека по таблице арены: удар/стрельба 0.8 → 0.6, лечение 0.75 → 0.5, входящий 1.1 (флаг один);
 *  дальше — тем же шагом (в матче сверяется с effects крипов). */
internal fun PainAndGain.stackMul(type: String, count: Int): Double {
    if (count <= 0) return 1.0
    return when (type) {
        EFF_ATTACK_MODIFIER, EFF_RANGED_ATTACK_MODIFIER -> 1.0 - 0.2 * count
        EFF_HEAL_MODIFIER -> 1.0 - 0.25 * count
        EFF_DAMAGE_TAKEN_MODIFIER -> 1.0 + 0.1 * count
        else -> 1.0
    }.coerceAtLeast(0.0)
}

internal fun PainAndGain.sideModsOf(flags: List<FlagInfo>, mine: Boolean): InfluenceMap.SideMods {
    fun n(type: String) = flags.count { it.mine == mine && it.type == type }
    return InfluenceMap.SideMods(
        attack = stackMul(EFF_ATTACK_MODIFIER, n(EFF_ATTACK_MODIFIER)),
        ranged = stackMul(EFF_RANGED_ATTACK_MODIFIER, n(EFF_RANGED_ATTACK_MODIFIER)),
        heal = stackMul(EFF_HEAL_MODIFIER, n(EFF_HEAL_MODIFIER)),
        taken = stackMul(EFF_DAMAGE_TAKEN_MODIFIER, n(EFF_DAMAGE_TAKEN_MODIFIER)),
    )
}

/** Эффекты сторон: по массиву effects крипов, если API его отдаёт, иначе по подсчёту флагов (таблица
 *  арены). Печатает то и другое при изменении — в матче они обязаны совпасть. */
internal fun PainAndGain.applyEffects(flags: List<FlagInfo>, myCreeps: List<Creep>, enemyCreeps: List<Creep>) {
    val ours = sideModsOf(flags, true)
    val theirs = sideModsOf(flags, false)
    InfluenceMap.setSideMods(ours, theirs)
    val sample = myCreeps.firstOrNull { InfluenceMap.hasEffectsApi(it) }
    InfluenceMap.setOurTaken(if (sample != null) InfluenceMap.takenOf(sample) else ours.taken)
    val enemySample = enemyCreeps.firstOrNull { InfluenceMap.hasEffectsApi(it) }
    InfluenceMap.setTheirTaken(if (enemySample != null) InfluenceMap.takenOf(enemySample) else theirs.taken)
    if (DEBUG_LOG) {
        val key = "$ours|$theirs|${sample?.let { JSON.stringify(it.effects) }}|${enemySample?.let { JSON.stringify(it.effects) }}"
        if (key != lastEffectsKey) {
            lastEffectsKey = key
            println("effects t=${getTicks()}: byFlags ours=[$ours] theirs=[$theirs] api ours=${sample?.let { JSON.stringify(it.effects) } ?: "n/a"} theirs=${enemySample?.let { JSON.stringify(it.effects) } ?: "n/a"}")
        }
    }
}

internal fun PainAndGain.accountScore(flags: List<FlagInfo>) {
    val cap = num(MAX_SCORE_PER_TICK.asDynamic(), Double.MAX_VALUE)
    ourRate = minOf(cap, flags.filter { it.ours }.sumOf { it.score }.toDouble()).toInt()
    enemyRate = minOf(cap, flags.filter { it.theirs }.sumOf { it.score }.toDouble()).toInt()
    ourScore += ourRate
    enemyScore += enemyRate
    val remaining = maxOf(0, arenaInfo.ticksLimit - getTicks())
    behindOnScore = ourScore + ourRate * remaining < enemyScore + enemyRate * remaining
    behindTicks = if (behindOnScore) behindTicks + 1 else 0
    // СНИМОК ДЕБЮТА (v216): по собственному замеру файла забег решается в первые двести тиков, а все приборы
    // в доках сняты «по последнему тику матча». Две строки, которые называют забег там, где он решается
    if (getTicks() == 100) race100 = "$ourRate:$enemyRate"
    if (getTicks() == 200) race200 = "$ourRate:$enemyRate"
    if (DEBUG_LOG && getTicks() % 100 == 0) println("score t=${getTicks()}: our=${ourScore.toInt()} (+$ourRate/t) enemy=${enemyScore.toInt()} (+$enemyRate/t) behind=$behindOnScore lead=${(ourScore - enemyScore).toInt()} maxSwing=${(cap * remaining).toInt()}")
}

// ==================== армия ====================

/** Поле к цели; не наши флаги — стены (кроме самой цели: flowFieldTo всегда открывает целевую клетку). */
internal fun PainAndGain.flowTo(ctx: Ctx, target: Position, avoid: Boolean = false, near: Boolean = false): IntArray {
    val key = target.x * 100 + target.y + (if (avoid) 10000 else 0) + (if (near) 20000 else 0)
    val now = getTicks()
    val hit = Memory.flowCache[key]
    val at = Memory.flowCacheTick[key]
    // долгий срок — только ПОЛНОМУ полю к флагу (v131b): поле, посчитанное сверх бюджета BFS, ограничено NEAR_FLOW клетками, и
    // в кэше на тридцать тиков оно тридцать тиков говорило бегунам и армии «пути нет» (blitz 4-4 → 2-6, входы 3 хуже / 0 лучше)
    val ttl = if (near) 1 else FLOW_TTL
    if (hit != null && at != null && now - at < ttl) return hit
    if (hit != null && bfsCost >= BFS_BUDGET) return hit   // сверх бюджета — устаревшее поле
    // ...и СВЕРХ ВРЕМЕНИ ТОЖЕ, а не только сверх счётчика BFS (v184). Счётчик считает поля, а не миллисекунды, и
    // на втором тике матча их ещё мало: первый тик тратит около 185 мс из тысячи (built 55, prefetch 28, plan 25),
    // сторож на втором честно срабатывает на 63 мс, а следом всё равно запускается ПОЛНОЕ поле — и тик уходит
    // целиком с `Script execution timed out` в bfs. В двенадцати тестовых играх это случилось в пяти, поровну в
    // победах и поражениях, то есть разгромов не объясняет, но тик пропадает даром
    val bounded = near || bfsCost >= BFS_BUDGET ||
        (now > 1 && cpuMs() > CPU_GUARD_MS)
    bfsThisTick++
    bfsCost += if (bounded) 0.25 else 1.0
    val f = DistanceMap.flowFieldTo(target, ctx.flagBlocked + (if (avoid) ctx.blocked + avoidCells(ctx) else ctx.blocked),
        maxDist = if (bounded) NEAR_FLOW else Int.MAX_VALUE)
    Memory.flowCache[key] = f
    flowFull[key] = !bounded
    Memory.flowCacheTick[key] = if (bounded && !near) -1000 else now
    return f
}

internal fun PainAndGain.avoidCells(ctx: Ctx): List<Position> = avoidCellsCache ?: run {
    val seen = HashSet<Int>()
    val out = ArrayList<Position>()
    for (e in ctx.combatEnemies) for (dx in -AVOID_RANGE..AVOID_RANGE) for (dy in -AVOID_RANGE..AVOID_RANGE) {
        if (!stationary(e)) continue
        val x = e.x + dx; val y = e.y + dy
        if (x < 0 || y < 0 || x > 99 || y > 99) continue
        if (seen.add(x * 100 + y)) out.add(InfluenceMap.cell(x, y))
    }
    avoidCellsCache = out
    out
}

/** Поле к НЕ-вражеской цели (флаг, пост, сбор, отход): в обход врагов, если оттуда, где стоит крип, такой
 *  путь есть, иначе обычное (матч 2 на стенде: маршрут к дальнему флагу вёл через стоящий отряд врага). */
internal fun PainAndGain.flowAvoiding(ctx: Ctx, target: Position, creep: Creep, near: Boolean = false): IntArray {
    val f = flowTo(ctx, target, true, near)
    return if (f[creep.x * 100 + creep.y] >= 0) f else flowTo(ctx, target, near = near)
}

/** Стая у цели: боевые враги рядом с ней и те, кто дойдёт до неё (своим телом по полю) не позже нас —
 *  из ХОДЯЧИХ: стоящий на месте STILL_TICKS тиков в стаю по «успеет дойти» не зачисляется (матч 1:
 *  армия врага не сделала ни шага за 1570 тиков, а «успевала» к каждому флагу, и армия простояла на посту). */
internal fun PainAndGain.packAt(ctx: Ctx, pos: Position, flow: IntArray, ourTravel: Int): List<Creep> {
    // МЕМО СТАИ (v132): пути его крипов к клетке флага ходятся раз в тик на флаг и поле, а не на каждого бегуна: пять отцепленных
    // бегунов × семь флагов × одиннадцать его крипов давали ~150 000 шагов по полю за тик (фаза r.cands 52–73 мс из 80–99 мс боевого
    // тика, восемь таймаутов в матче с Coldkimchi 07.09). Результат тот же
    if (packTicksTick != getTicks()) { Memory.packTicksCache.clear(); packTicksTick = getTicks() }
    val key = pos.x * 100 + pos.y
    val cached = Memory.packTicksCache[key]?.takeIf { it.first === flow }
    val ticksOf = cached?.second ?: HashMap<String, Int>().also { m ->
        for (e in ctx.combatEnemies) if (!stationary(e)) m[e.id] = pathTicks(e, flow, e.x * 100 + e.y)
        Memory.packTicksCache[key] = flow to m
    }
    return ctx.combatEnemies.filter { getRange(it, pos) <= FLAG_GUARD_RANGE || (ticksOf[it.id]?.let { t -> t <= ourTravel } == true) }
}

/** Сколько тиков враг не двигался (новый враг считается идущим). */
internal fun PainAndGain.stationaryFor(e: Creep): Int = getTicks() - (Memory.enemyLastMove[e.id] ?: getTicks())

/** Враг не двигался последние STILL_TICKS тиков. */
internal fun PainAndGain.stationary(e: Creep): Boolean = stationaryFor(e) >= STILL_TICKS

/**
 * Флаг-цель армии: не наш, разрешённый к захвату; со стаей — по перевесу Ланчестера (ratio) и цене боя в
 * запасе хода группы (или в контакте); без стаи — просто идём. Ценность — очки размена за тик пути.
 * Текущая цель держится, пока проходит по мягкому порогу (гистерезис).
 */
/** Враг, с которым есть бой: с уроном, либо лекарь, у которого рядом (в дальности лечения плюс шаг) свой с оружием
 *  в теле, живым или мёртвым (см. armedEnemies). */
/**
 * МЕСТНЫЙ ПЕРЕВЕС В МАСШТАБЕ УДАРА. Поля влияния уже меряют это сами и строятся раз в тик (см. buildFields):
 * `ourBurstAt(его клетка)` — наш урон в тик ПО НЕМУ (мили штампуется радиусом 2, стрелок 3), `dangerAt(его
 * клетка)` — его урон по нашему крипу, вставшему рядом. Отношение и есть операторское «трое наших против
 * одного его»: 3×240 против его 240 читается как 3,0. Это два обращения к массиву, а не новая сущность.
 * Существующие `localAllies`/`localEnemies` меряют радиусы 8 и 11 — то есть почти всю армию, и потому на
 * вопрос «сильнее ли мы ЗДЕСЬ» ответить не могут.
 */
/**
 * СКАУТ ВРАГА (v214, наблюдение оператора: «мы игнорируем крипов, у которых есть только мув-части — они
 * спокойно гуляют возле нашей армии, мы их пропускаем и не нападаем»). У каждой стороны их по два:
 * тело `M1h=100`, одна часть MOVE, сто хитов. Замер по записям: ОБА его скаута доживают до конца матча с
 * полными ста хитами, ни разу не обстрелянные, — и это они берут ему флаги.
 * Различитель точен и уже есть: `potentialOf` считает части ТЕЛА без проверки хитов, поэтому у остова
 * потенциал есть, а у скаута нулевой. Значит распоряжение v178 («разоружённый — не цель») не ослабляется
 * ни на йоту: оно про того, у кого оружие БЫЛО, а у скаута его не было никогда.
 */
internal fun PainAndGain.scoutFoe(e: Creep): Boolean =
    InfluenceMap.potentialOf(e).let { it.melee + it.ranged + it.heal <= 0.0 } && canMove(e)

internal fun PainAndGain.spotEdgeAt(e: Creep): Double {
    val k = e.x * 100 + e.y
    val d = InfluenceMap.dangerAt(k)
    return if (d <= 0.0) Double.MAX_VALUE else InfluenceMap.ourBurstAt(k) / d
}

internal fun PainAndGain.threatening(e: Creep, enemyCreeps: List<Creep>): Boolean {
    val q = InfluenceMap.profileOf(e)
    return q.melee + q.ranged > 0.0 || enemyCreeps.any { w -> w.id != e.id && getRange(w, e) <= HEAL_RANGE + 1 && w.body.any { it.type == ATTACK || it.type == RANGED_ATTACK } }
}

/** Точка отхода: дом и углы НАШЕЙ половины — достижимая и самая дальняя от центра армии врага (отход в
 *  дальний угол через всю карту вёл сквозь врага, и армию добивали по одному — стенд rush). */
/** Враг уходит: за CHASE_WINDOW тиков отдалился от ТОГДАШНЕГО центра нашей армии больше чем на клетку. Кайтер
 *  держит дистанцию, и «дистанция до нас не растёт» его не выдаёт — выдаёт движение прочь от места, где мы были.
 *  Порог делает уходящим и блоб, шагнувший назад на две клетки: все двенадцать «неловимы» восемь тиков, huntable 0/12,
 *  наступление снято, армия к посту (матч 70, t=196–207, 223, 234, 554). ОТВЕРГНУТО стендом: порог «больше половины окна»
 *  (уходил дольше, чем стоял) — 66 строк хуже / 44 лучше: кайтеры добиваются медленнее на десятке карт (за ними гонятся
 *  дольше), россыпи m12/m19/m24/m29/m30 spread и m29/m30 farm из победы в проигрыш; гистерезис в самом наступлении —
 *  тоже (см. pushing). Открытая находка. */

internal fun PainAndGain.evasive(e: Creep): Boolean {
    val h = Memory.enemyCellHist[e.id] ?: return false
    if (h.size < CHASE_WINDOW || Memory.ourCentroidHist.size < CHASE_WINDOW) return false
    val c0 = Memory.ourCentroidHist.first()
    val old = h.first()
    val cPos = InfluenceMap.cell(c0 / 100, c0 % 100)
    val oldPos = InfluenceMap.cell(old / 100, old % 100)
    return getRange(e, cPos) > getRange(oldPos, cPos) + 1
}

/** Ловим ли враг: вплотную к нашему вооружённому (MELEE_KEEP_RANGE), медленнее нашего самого быстрого или не
 *  уходит (см. evasive). Только за ловимым идут стая, охота, добивание и местный бросок (см. CHASE_WINDOW). */
internal fun PainAndGain.catchable(e: Creep, armed: List<Creep>): Boolean {
    if (armed.any { getRange(e, it) <= MELEE_KEEP_RANGE }) return true
    // медленнее нас ТАМ, ГДЕ СТОИТ (v48): период на его клетке — в болоте тело с половиной MOVE ходит клетку в пять
    // тиков, и застрявший в болоте ловим, хотя на равнине он равен нам (матч 90, см. InfluenceMap.enemyOrigins)
    if (periodAt(e, e.x, e.y) > (armed.minOfOrNull { plainPeriod(it) } ?: 1)) return true
    if (!evasive(e)) return true
    return false
}

/** Клетка после ticks шагов спуска по полю от start (враг идёт за нами к цели поля). */
internal fun PainAndGain.projectAlong(flow: IntArray, start: Int, ticks: Int): Int {
    var cell = start
    var left = ticks
    while (left > 0 && flow[cell] > 0) {
        val cx = cell / 100; val cy = cell % 100
        var next = -1; var nv = flow[cell]
        for ((dx, dy) in DIRECTIONS) {
            val x = cx + dx; val y = cy + dy
            if (x < 0 || y < 0 || x > 99 || y > 99) continue
            val v = flow[x * 100 + y]
            if (v in 0 until nv) { nv = v; next = x * 100 + y }
        }
        if (next < 0) break
        cell = next; left--
    }
    return cell
}

/** Значение поля в клетке или, если она закрыта (чужой флаг — препятствие в полях потока), в лучшей соседней плюс
 *  шаг: запас выхода читался в клетке чужого флага и был «нет пути» для всякого флага-цели (стенд m1 scouts). */
internal fun PainAndGain.flowNear(flow: IntArray, p: Position): Int {
    val here = flow[p.x * 100 + p.y]
    if (here >= 0) return here
    var best = -1
    for ((dx, dy) in DIRECTIONS) {
        if (dx == 0 && dy == 0) continue
        val x = p.x + dx; val y = p.y + dy
        if (x < 0 || y < 0 || x > 99 || y > 99) continue
        val v = flow[x * 100 + y]
        if (v >= 0 && (best < 0 || v + 1 < best)) best = v + 1
    }
    return best
}

/** Ближайшая проходимая клетка: центр наших флагов попал в стенной блок, поле к нему было пустым (flow=-1), и
 *  армия стояла на месте, пока враг подходил (матч 11, t=90–103). */
internal fun PainAndGain.passableNear(p: Position): Position {
    if (!DistanceMap.isTerrainWall(p.x, p.y)) return p
    for (r in 1..30) for (dx in -r..r) for (dy in -r..r) {
        if (maxOf(abs(dx), abs(dy)) != r) continue
        val x = p.x + dx; val y = p.y + dy
        if (x in 0..99 && y in 0..99 && !DistanceMap.isTerrainWall(x, y)) return InfluenceMap.cell(x, y)
    }
    return p
}

/** Через сколько тиков боевые враги дойдут до нашего дома — по темпу сближения за APPROACH_WINDOW;
 *  новый враг — по ходу его тела вдоль поля. Заполняет approachingIds/arrivalById (для приоритета угроз). */
internal fun PainAndGain.enemyArrivalTicks(ctx: Ctx) {
    val now = getTicks()
    Memory.approachHistory.keys.retainAll { id -> ctx.combatEnemies.any { it.id == id } }
    Memory.approachingIds.clear()
    arrivalById.clear()
    val enemyApproach = flowTo(ctx, ctx.home)
    for (e in ctx.combatEnemies) {
        val approach = enemyApproach[e.x * 100 + e.y]
        if (approach < 0) continue
        val h = Memory.approachHistory.getOrPut(e.id) { ArrayDeque() }
        h.addLast(now to approach)
        while (h.isNotEmpty() && h.first().first < now - APPROACH_WINDOW) h.removeFirst()
        val (t0, a0) = h.first()
        val arrival = if (now - t0 < APPROACH_WINDOW / 2) pathTicks(e, enemyApproach, e.x * 100 + e.y) else {
            val rate = (a0 - approach).toDouble() / (now - t0)
            if (rate > 0.0) (approach / rate).toInt() else Int.MAX_VALUE / 2
        }
        arrivalById[e.id] = arrival
        if (arrival < Int.MAX_VALUE / 2) Memory.approachingIds.add(e.id)
    }
}

internal fun PainAndGain.sgn(v: Int) = if (v > 0) 1 else if (v < 0) -1 else 0

/** Хранители флагов (см. KEEP_RANGE): снятие, потом назначение. */
internal fun PainAndGain.enemyCreeps(ctx: Ctx): List<Creep> = ctx.enemyCreeps

// ==================== тело, скорость, мощь ====================

internal fun PainAndGain.canMove(creep: Creep) = creep.body.any { it.type == MOVE && it.hits > 0 }

internal fun PainAndGain.hasMelee(creep: Creep) = creep.body.any { it.type == ATTACK && it.hits > 0 }

internal fun PainAndGain.isMelee(creep: Creep) = creep.body.any { it.type == ATTACK }

internal fun PainAndGain.hasRanged(creep: Creep) = creep.body.any { it.type == RANGED_ATTACK && it.hits > 0 }

internal fun PainAndGain.hasHeal(creep: Creep) = creep.body.any { it.type == HEAL && it.hits > 0 }

internal fun PainAndGain.hasWeapon(creep: Creep) = hasRanged(creep) || hasMelee(creep)

/** Вес тела для усталости: части не-MOVE и не-CARRY ПО ТИПУ (мёртвые весят — movement.js:237)
 *  плюс гружёные CARRY. */
internal fun PainAndGain.bodyWeight(creep: Creep): Int {
    bodyWeightNow[creep.id]?.let { return it }
    val parts = creep.body.count { it.type != MOVE && it.type != CARRY }
    val carried = creep.store[RESOURCE_ENERGY] ?: 0
    val w = parts + (carried + CARRY_CAPACITY - 1) / CARRY_CAPACITY
    bodyWeightNow[creep.id] = w
    return w
}

internal fun PainAndGain.liveMoves(creep: Creep): Int {
    liveMovesNow[creep.id]?.let { return it }
    val m = creep.body.count { it.type == MOVE && it.hits > 0 }
    liveMovesNow[creep.id] = m
    return m
}

/** Период хода (тиков на клетку): после шага fatigue = вес × цена местности − 2 × живые MOVE, дальше
 *  −2×MOVE в тик, следующий ход при нуле (tick.js:105, movement.js:237). */
internal fun PainAndGain.periodOn(weight: Int, moves: Int, rate: Int): Int {
    if (moves <= 0) return Int.MAX_VALUE / 4
    val left = weight * rate - 2 * moves
    return if (left <= 0) 1 else 1 + (left + 2 * moves - 1) / (2 * moves)
}

internal fun PainAndGain.plainPeriod(creep: Creep) = periodOn(bodyWeight(creep), liveMoves(creep), 2)

internal fun PainAndGain.periodAt(creep: Creep, x: Int, y: Int) =
    periodOn(bodyWeight(creep), liveMoves(creep), if (DistanceMap.isSwamp(x, y)) 10 else 2)

internal fun PainAndGain.swampPeriod(creep: Creep) = periodOn(bodyWeight(creep), liveMoves(creep), 10)

internal fun PainAndGain.fullSpeed(creep: Creep) = plainPeriod(creep) == 1

/** Сколько урона крип ещё выдержит, не теряя скорости (части умирают спереди). */
internal fun PainAndGain.speedSlack(creep: Creep): Int {
    val weight = bodyWeight(creep)
    if (weight == 0) return creep.hits // тела без веса (чистый MOVE) скорости не теряют
    var moves = liveMoves(creep)
    var slack = 0
    for (part in creep.body) {
        if (part.hits <= 0) continue
        if (moves < weight) break
        slack += part.hits
        if (part.type == MOVE) moves--
    }
    return slack
}

/** Тики хода крипа по спуску вдоль поля потока от клетки до цели — по его телу и местности (periodAt). */
internal fun PainAndGain.pathTicks(creep: Creep, flow: IntArray, startCell: Int): Int {
    var cell = startCell
    if (cell < 0 || flow[cell] < 0) return Int.MAX_VALUE / 2
    var ticks = 0
    var steps = 0
    while (flow[cell] > 0 && steps < 400) {
        val cx = cell / 100
        val cy = cell % 100
        var best = -1
        var bestFlow = flow[cell]
        for (dx in -1..1) for (dy in -1..1) {
            val nx = cx + dx
            val ny = cy + dy
            if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
            val f = flow[nx * 100 + ny]
            if (f in 0 until bestFlow) { bestFlow = f; best = nx * 100 + ny }
        }
        if (best < 0) break
        cell = best
        steps++
        ticks += periodAt(creep, cell / 100, cell % 100)
    }
    return ticks
}

internal fun PainAndGain.inContact(enemies: List<Creep>, ours: List<Creep>): Boolean =
    enemies.any { e -> ours.any { getRange(e, it) <= RANGED_RANGE + 1 } }

/** Матрица пути захватчика: опасность + свои крипы дороже + не наши флаги стены, кроме allowCell (его флаг). */
internal fun PainAndGain.crowdMatrixOf(ctx: Ctx, allowCell: Int): CostMatrix {
    val crowdMatrix = ctx.rawDanger.clone()
    for (ally in ctx.active) {
        val current = crowdMatrix.get(ally.x, ally.y)
        if (current < 255) crowdMatrix.set(ally.x, ally.y, minOf(254, current + CROWD_COST))
    }
    for (c in ctx.flagCells) if (c != allowCell) crowdMatrix.set(c / 100, c % 100, 255)
    return crowdMatrix
}

/** Жадный шаг бегства: свободная соседняя клетка (не стена, не чужой флаг, не занята) с наибольшей
 *  дальностью до ближайшего врага, при равной — под меньшим огнём; null — некуда. */
internal fun PainAndGain.greedyFlee(ctx: Ctx, creep: Creep, enemies: List<Creep>, force: Boolean = false): Position? {
    val occupied = (ctx.myCreeps + ctx.enemyCreeps).filter { !it.spawning }.mapTo(HashSet()) { it.x * 100 + it.y }
    var best: Position? = null
    // force: лучшая из соседних, даже если она не лучше своей клетки (см. SCOUT_FLEE_TRIGGER)
    var bestRange = if (force) -1 else (enemies.minOfOrNull { getRange(creep, it) } ?: 0)
    var bestFire = if (force) Double.MAX_VALUE else InfluenceMap.fireAt(creep.x, creep.y, enemies)
    for ((dx, dy) in DIRECTIONS) {
        if (dx == 0 && dy == 0) continue
        val x = creep.x + dx; val y = creep.y + dy
        if (x < 0 || y < 0 || x > 99 || y > 99) continue
        val key = x * 100 + y
        if (key in occupied || key in ctx.flagCells || DistanceMap.isWall(x, y) || ctx.blocked.any { it.x == x && it.y == y }) continue
        val pos = InfluenceMap.cell(x, y)
        val range = enemies.minOfOrNull { getRange(pos, it) } ?: 0
        val fire = InfluenceMap.fireAt(x, y, enemies)
        if (range > bestRange || (range == bestRange && fire < bestFire)) { best = pos; bestRange = range; bestFire = fire }
    }
    return best
}

internal fun PainAndGain.pathStep(creep: Creep, target: Position, range: Int, dangerMatrix: CostMatrix): Position? {
    val goal = SearchGoal(pos = target, range = range)
    val result = searchPath(creep, goal, SearchPathOptions(costMatrix = dangerMatrix))
    return result.path.firstOrNull()
}

internal fun PainAndGain.fleeStep(creep: Creep, enemies: List<Creep>, dangerMatrix: CostMatrix, range: Int = RANGED_RANGE): Position? {
    if (enemies.isEmpty()) return null
    val goals = enemies.map { e -> SearchGoal(pos = InfluenceMap.cell(e.x, e.y), range = range) }.toTypedArray()
    val result = searchPath(creep, goals, SearchPathOptions(flee = true, costMatrix = dangerMatrix))
    return result.path.firstOrNull()
}

/** Центр крупнейшей группы (v120, см. USE_MASS_CLUSTER_CENTROID): сид — крип с наибольшим числом своих из списка в MASS_RANGE
 *  (при равенстве — больший id), центр — по его группе; без переключателя — среднее по всем. */
internal fun PainAndGain.clusterCentroid(cs: List<Creep>): Position? {
    if (cs.size <= 2) return centroidOf(cs)
    val seed = cs.maxWithOrNull(compareBy<Creep>({ c -> cs.count { getRange(c, it) <= MASS_RANGE } }, { it.id })) ?: return null
    return centroidOf(cs.filter { getRange(seed, it) <= MASS_RANGE })
}

internal fun PainAndGain.centroidOf(points: List<Position>): Position? {
    if (points.isEmpty()) return null
    return InfluenceMap.cell(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)
}
