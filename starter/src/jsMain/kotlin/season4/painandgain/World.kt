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
import kotlin.reflect.*

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
    /** Факты крипов тика (Facts.kt): строятся заново каждый тик, живут вместе с `Ctx`. */
    val units: TickFacts,
    /** Его бойцы, которые грозят (см. threatening): сами с оружием либо с чужим стволом в досягаемости лечения. */
    val threats: List<Creep>,
) {
    // ИМЕНОВАННЫЕ ВЫБОРКИ ТИКА (v442, план архитектуры, этап 1). Каждая была выписана дословно в двух–пяти местах разных
    // стадий; списки и предикаты за тик не меняются, поэтому считаются один раз. Порядок элементов — как у filter.
    /** Бегуны с живым оружием или лечением: бойцы, посланные за флагом (detach, cmdDetach). */
    val combatRunners: List<Creep> = runners.filter { units.of(it).combatant }
    /** Наша сторона в бою: армия и бегуны-бойцы. */
    val side: List<Creep> = army + combatRunners
    /** Армия с живым оружием. */
    val armedArmy: List<Creep> = army.filter { units.of(it).armed }
    /** Лекари армии: без живого оружия, с живой HEAL. */
    val armyHealers: List<Creep> = army.filter { units.of(it).healerOnly }
    /** Армия с живой HEAL — лекари и вооружённые с лечением. */
    val armyWithHeal: List<Creep> = army.filter { units.of(it).liveHeal }
    val ourFlags: List<FlagInfo> = oursOf(flags)
    val flagsNotTheirs: List<FlagInfo> = flags.filter { !it.theirs }
}

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

internal fun ownerName(code: Int) = when (code) { 1 -> "us"; -1 -> "enemy"; else -> "none" }

/** Множитель стека по таблице арены: удар/стрельба 0.8 → 0.6, лечение 0.75 → 0.5, входящий 1.1 (флаг один);
 *  дальше — тем же шагом (в матче сверяется с effects крипов). */
internal fun stackMul(type: String, count: Int): Double {
    if (count <= 0) return 1.0
    return when (type) {
        EFF_ATTACK_MODIFIER, EFF_RANGED_ATTACK_MODIFIER -> 1.0 - 0.2 * count
        EFF_HEAL_MODIFIER -> 1.0 - 0.25 * count
        EFF_DAMAGE_TAKEN_MODIFIER -> 1.0 + 0.1 * count
        else -> 1.0
    }.coerceAtLeast(0.0)
}

internal fun sideModsOf(flags: List<FlagInfo>, mine: Boolean): InfluenceMap.SideMods {
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
internal fun applyEffects(flags: List<FlagInfo>, myCreeps: List<Creep>, enemyCreeps: List<Creep>) {
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
    ourRate = minOf(cap, oursOf(flags).sumOf { it.score }.toDouble()).toInt()
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
internal fun flowTo(ctx: Ctx, target: Position, avoid: Boolean = false, near: Boolean = false): IntArray {
    val key = target.key + (if (avoid) 10000 else 0) + (if (near) 20000 else 0)
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
    Memory.flowCacheTick[key] = if (bounded && !near) -1000 else now
    return f
}

internal fun avoidCells(ctx: Ctx): List<Position> = avoidCellsCache ?: run {
    val seen = HashSet<Int>()
    val out = ArrayList<Position>()
    for (e in ctx.combatEnemies) for (dx in sym(AVOID_RANGE)) for (dy in sym(AVOID_RANGE)) {
        if (!stationary(e)) continue
        val x = e.x + dx; val y = e.y + dy
        if (x < 0 || y < 0 || x > 99 || y > 99) continue
        if (seen.add(key(x, y))) out.add(InfluenceMap.cell(x, y))
    }
    avoidCellsCache = out
    out
}

/** Поле к НЕ-вражеской цели (флаг, пост, сбор, отход): в обход врагов, если оттуда, где стоит крип, такой
 *  путь есть, иначе обычное (матч 2 на стенде: маршрут к дальнему флагу вёл через стоящий отряд врага). */
internal fun flowAvoiding(ctx: Ctx, target: Position, creep: Creep, near: Boolean = false): IntArray {
    val f = flowTo(ctx, target, true, near)
    return if (f[creep.key] >= 0) f else flowTo(ctx, target, near = near)
}

/** Стая у цели: боевые враги рядом с ней и те, кто дойдёт до неё (своим телом по полю) не позже нас —
 *  из ХОДЯЧИХ: стоящий на месте STILL_TICKS тиков в стаю по «успеет дойти» не зачисляется (матч 1:
 *  армия врага не сделала ни шага за 1570 тиков, а «успевала» к каждому флагу, и армия простояла на посту). */
internal fun packAt(ctx: Ctx, pos: Position, flow: IntArray, ourTravel: Int): List<Creep> {
    // МЕМО СТАИ (v132): пути его крипов к клетке флага ходятся раз в тик на флаг и поле, а не на каждого бегуна: пять отцепленных
    // бегунов × семь флагов × одиннадцать его крипов давали ~150 000 шагов по полю за тик (фаза r.cands 52–73 мс из 80–99 мс боевого
    // тика, восемь таймаутов в матче с Coldkimchi 07.09). Результат тот же
    if (packTicksTick != getTicks()) { Memory.packTicksCache.clear(); packTicksTick = getTicks() }
    val key = pos.key
    val cached = Memory.packTicksCache[key]?.takeIf { it.first === flow }
    val ticksOf = cached?.second ?: HashMap<String, Int>().also { m ->
        for (e in ctx.combatEnemies) if (!stationary(e)) m[e.id] = pathTicks(e, flow, e.key)
        Memory.packTicksCache[key] = flow to m
    }
    return ctx.combatEnemies.filter { getRange(it, pos) <= FLAG_GUARD_RANGE || (ticksOf[it.id]?.let { t -> t <= ourTravel } == true) }
}

/** Сколько тиков враг не двигался (новый враг считается идущим). */
internal fun stationaryFor(e: Creep): Int = getTicks() - (Memory.enemyLastMove[e.id] ?: getTicks())

/** Враг не двигался последние STILL_TICKS тиков. */
internal fun stationary(e: Creep): Boolean = stationaryFor(e) >= STILL_TICKS

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
internal fun scoutFoe(e: Creep): Boolean =
    InfluenceMap.potentialOf(e).let { it.melee + it.ranged + it.heal <= 0.0 } && canMove(e)

internal fun spotEdgeAt(e: Creep): Double {
    val k = e.key
    val d = InfluenceMap.dangerAt(k)
    return if (d <= 0.0) Double.MAX_VALUE else InfluenceMap.ourBurstAt(k) / d
}

internal fun threatening(e: Creep, enemyCreeps: List<Creep>): Boolean {
    val q = InfluenceMap.profileOf(e)
    return q.melee + q.ranged > 0.0 || enemyCreeps.any { w -> w.id != e.id && getRange(w, e) <= HEAL_RANGE + 1 && bornArmed(w) }
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

internal fun evasive(e: Creep): Boolean {
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
internal fun catchable(e: Creep, armed: List<Creep>): Boolean {
    if (armed.any { getRange(e, it) <= MELEE_KEEP_RANGE }) return true
    // медленнее нас ТАМ, ГДЕ СТОИТ (v48): период на его клетке — в болоте тело с половиной MOVE ходит клетку в пять
    // тиков, и застрявший в болоте ловим, хотя на равнине он равен нам (матч 90, см. InfluenceMap.enemyOrigins)
    if (periodAt(e, e.x, e.y) > (armed.minOfOrNull { plainPeriod(it) } ?: 1)) return true
    if (!evasive(e)) return true
    return false
}

/** Клетка после ticks шагов спуска по полю от start (враг идёт за нами к цели поля). */
internal fun projectAlong(flow: IntArray, start: Int, ticks: Int): Int {
    var cell = start
    var left = ticks
    while (left > 0 && flow[cell] > 0) {
        val cx = cell / 100; val cy = cell % 100
        var next = -1; var nv = flow[cell]
        for ((dx, dy) in dirsNow()) {
            val x = cx + dx; val y = cy + dy
            if (x < 0 || y < 0 || x > 99 || y > 99) continue
            val v = flow[key(x, y)]
            if (v in 0 until nv) { nv = v; next = key(x, y) }
        }
        if (next < 0) break
        cell = next; left--
    }
    return cell
}

/** Значение поля в клетке или, если она закрыта (чужой флаг — препятствие в полях потока), в лучшей соседней плюс
 *  шаг: запас выхода читался в клетке чужого флага и был «нет пути» для всякого флага-цели (стенд m1 scouts). */
internal fun flowNear(flow: IntArray, p: Position): Int {
    val here = flow[p.key]
    if (here >= 0) return here
    var best = -1
    for ((dx, dy) in dirsNow()) {
        if (dx == 0 && dy == 0) continue
        val x = p.x + dx; val y = p.y + dy
        if (x < 0 || y < 0 || x > 99 || y > 99) continue
        val v = flow[key(x, y)]
        if (v >= 0 && (best < 0 || v + 1 < best)) best = v + 1
    }
    return best
}

/** Ближайшая проходимая клетка: центр наших флагов попал в стенной блок, поле к нему было пустым (flow=-1), и
 *  армия стояла на месте, пока враг подходил (матч 11, t=90–103). */
internal fun passableNear(p: Position): Position {
    if (!DistanceMap.isTerrainWall(p.x, p.y)) return p
    for (r in 1..30) for (dx in sym(r)) for (dy in sym(r)) {
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
        val approach = enemyApproach[e.key]
        if (approach < 0) continue
        val h = Memory.approachHistory.getOrPut(e.id) { ArrayDeque() }
        h.addLast(now to approach)
        while (h.isNotEmpty() && h.first().first < now - APPROACH_WINDOW) h.removeFirst()
        val (t0, a0) = h.first()
        val arrival = if (now - t0 < APPROACH_WINDOW / 2) pathTicks(e, enemyApproach, e.key) else {
            val rate = (a0 - approach).toDouble() / (now - t0)
            if (rate > 0.0) (approach / rate).toInt() else Int.MAX_VALUE / 2
        }
        arrivalById[e.id] = arrival
        if (arrival < Int.MAX_VALUE / 2) Memory.approachingIds.add(e.id)
    }
}


/** Хранители флагов (см. KEEP_RANGE): снятие, потом назначение. */
internal fun enemyCreeps(ctx: Ctx): List<Creep> = ctx.enemyCreeps

// ==================== факты о флагах (v446: до этапа 5 жили у заданий бегунов, а читал их и мир — ребро вверх) ====================

/** Флаг, который крип держит (v297, см. HOLD_WATCH): наш флаг под ним, пока его крип не дальше HOLD_WATCH от флага; в
 *  режиме пар (v298, см. GROUP_SAFE_DMG) — всегда: фермер возвращается, а гонять держателя туда и обратно — пустая трата. */
internal fun PainAndGain.heldFlag(ctx: Ctx, c: Creep): FlagInfo? =
    ctx.flags.firstOrNull { it.ours && it.pos.x == c.x && it.pos.y == c.y }
        ?.takeIf { f -> groupSafe || ctx.enemyCreeps.any { getRange(it, f.pos) <= HOLD_WATCH } }

/** Флаг, при котором крип стоит охраной (v298): наш флаг его задания, на клетке — другой наш крип, сам крип не дальше
 *  двух клеток, и его крип не дальше HOLD_WATCH от флага (в режиме пар — всегда, как у держателя). */
internal fun PainAndGain.guardFlag(ctx: Ctx, c: Creep): FlagInfo? {
    val f = Memory.runnerFlag[c.id]?.let { id -> ctx.flags.firstOrNull { it.id == id } } ?: return null
    val occ = f.occupant ?: return null
    if (!f.ours || occ.my != true || occ.id == c.id || getRange(c, f.pos) > 2) return null
    return f.takeIf { groupSafe || ctx.enemyCreeps.any { getRange(it, f.pos) <= HOLD_WATCH } }
}

// ==================== тело, скорость, мощь ====================

// ИМЕНОВАННЫЕ ВЫБОРКИ (v442, план архитектуры, этап 1). Выборка, выписанная дословно в нескольких функциях, у которых
// СВОЙ список или предикат по памяти, меняющейся за тик, полем `Ctx` стать не может: она обязана считаться там же и
// тогда же, где считалась. Поэтому это функции — определение одно, точки вычисления прежние. Выборки по спискам тика с
// неизменным за тик предикатом — поля `Ctx` (side, threats, armedArmy…).
internal fun living(creeps: List<Creep>) = creeps.filter { it.hits > 0 }
internal fun livingCombatants(creeps: List<Creep>) = creeps.filter { it.hits > 0 && (combatant(it)) }
internal fun armedOf(creeps: List<Creep>) = creeps.filter { hasWeapon(it) }
internal fun armedMatesOf(creeps: List<Creep>, self: Creep) = creeps.filter { it.id != self.id && hasWeapon(it) }
internal fun rangedOf(creeps: List<Creep>) = creeps.filter { hasRanged(it) }
internal fun withHeal(creeps: List<Creep>) = creeps.filter { hasHeal(it) }
internal fun pureMeleeOf(creeps: List<Creep>) = creeps.filter { meleeOnlyLive(it) }
/** Ходячие и уже родившиеся: те, кому командир и строй вообще могут дать клетку. */
internal fun mobileOf(creeps: List<Creep>) = creeps.filter { canMove(it) && !it.spawning }
/** Бойцы линии: чистые мили / стрелки, не ушедшие в ротацию (читает память — считается в момент вызова). */
internal fun lineMelees(creeps: List<Creep>) = creeps.filter { meleeOnlyLive(it) && it.id !in Memory.rotatingIds }
internal fun lineRangeds(creeps: List<Creep>) = creeps.filter { hasRanged(it) && it.id !in Memory.rotatingIds }
/** Состав гонки за флагом: вооружённый, на полной скорости, не хранитель и не в ротации (память — в момент вызова). */
internal fun raceCapable(creeps: List<Creep>) = creeps.filter { hasWeapon(it) && fullSpeed(it) && it.id !in Memory.keeperIds && it.id !in Memory.rotatingIds }
/** Отряд за флагами и остальные — по памяти В МОМЕНТ ВЫЗОВА: стратег правит detachedIds и cmdDetach посреди тика. */
internal fun detachedRunners(ctx: Ctx) = ctx.runners.filter { it.id in Memory.detachedIds }
internal fun notDetached(creeps: List<Creep>) = creeps.filter { it.id !in Memory.detachedIds }
internal fun notCmdDetached(creeps: List<Creep>) = creeps.filter { it.id !in Memory.cmdDetach }
/** Его вооружённые в досягаемости боя от точки (флага). */
internal fun foesInEngage(foes: List<Creep>, pos: Position) = foes.filter { getRange(it, pos) <= ENGAGE_RANGE }
/** Наши флаги из списка (до `Ctx` — в счёте очков; в `Ctx` это поле ourFlags). */
internal fun oursOf(flags: List<FlagInfo>) = flags.filter { it.ours }
/** Список без этого крипа. */
internal fun List<Creep>.without(c: Creep) = filter { it.id != c.id }



/** Тики хода крипа по спуску вдоль поля потока от клетки до цели — по его телу и местности (periodAt). */
internal fun pathTicks(creep: Creep, flow: IntArray, startCell: Int): Int {
    var cell = startCell
    if (cell < 0 || flow[cell] < 0) return Int.MAX_VALUE / 2
    var ticks = 0
    var steps = 0
    while (flow[cell] > 0 && steps < 400) {
        val cx = cell / 100
        val cy = cell % 100
        var best = -1
        var bestFlow = flow[cell]
        for (dx in sym(1)) for (dy in sym(1)) {
            val nx = cx + dx
            val ny = cy + dy
            if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
            val f = flow[key(nx, ny)]
            if (f in 0 until bestFlow) { bestFlow = f; best = key(nx, ny) }
        }
        if (best < 0) break
        cell = best
        steps++
        ticks += periodAt(creep, cell / 100, cell % 100)
    }
    return ticks
}

internal fun inContact(enemies: List<Creep>, ours: List<Creep>): Boolean =
    enemies.any { e -> ours.any { getRange(e, it) <= RANGED_RANGE + 1 } }

/** Матрица пути захватчика: опасность + свои крипы дороже + не наши флаги стены, кроме allowCell (его флаг). */
internal fun crowdMatrixOf(ctx: Ctx, allowCell: Int): CostMatrix {
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
internal fun greedyFlee(ctx: Ctx, creep: Creep, enemies: List<Creep>, force: Boolean = false): Position? {
    val occupied = (ctx.myCreeps + ctx.enemyCreeps).filter { !it.spawning }.mapTo(HashSet()) { it.key }
    var best: Position? = null
    // force: лучшая из соседних, даже если она не лучше своей клетки (см. SCOUT_FLEE_TRIGGER)
    var bestRange = if (force) -1 else (enemies.minOfOrNull { getRange(creep, it) } ?: 0)
    var bestFire = if (force) Double.MAX_VALUE else InfluenceMap.fireAt(creep.x, creep.y, enemies)
    // ...И ПРИ РАВНОМ ОТРЫВЕ — К ДОМУ (v373). Жадное бегство знало только дистанцию до врага и потому уводило в угол
    // карты, где клеток больше нет: разбор 4 матчей против топ-1 `ricardo18informatica2020#14` — из шести погибших
    // скаутов ПЯТЬ кончили бегство дальше от дома, чем начали (один 57 → 85 клеток), ЧЕТВЕРО умерли в 3–7 клетках от
    // края, и число клеток, увеличивающих дистанцию, падало до нуля в пяти случаях из шести. Цена — 2 671 очко за
    // матч потерянного владения, тогда как один ДОШЕДШИЙ и выживший курьер дал 8 155 очков (34 % нашего счёта в том
    // матче). Дом — это и сторона, где стоят свои, и сторона, где карта не кончается
    var bestHome = getRange(creep, ctx.home)
    for ((dx, dy) in dirsNow()) {
        if (dx == 0 && dy == 0) continue
        val x = creep.x + dx; val y = creep.y + dy
        if (x < 0 || y < 0 || x > 99 || y > 99) continue
        val key = key(x, y)
        if (key in occupied || key in ctx.flagCells || DistanceMap.isWall(x, y) || ctx.blocked.any { it.x == x && it.y == y }) continue
        val pos = InfluenceMap.cell(x, y)
        val range = enemies.minOfOrNull { getRange(pos, it) } ?: 0
        val fire = InfluenceMap.fireAt(x, y, enemies)
        val home = getRange(pos, ctx.home)
        val better = range > bestRange ||
            (range == bestRange && fire < bestFire) ||
            (range == bestRange && fire == bestFire && home < bestHome)
        if (better) { best = pos; bestRange = range; bestFire = fire; bestHome = home }
    }
    return best
}

internal fun pathStep(creep: Creep, target: Position, range: Int, dangerMatrix: CostMatrix): Position? {
    val goal = SearchGoal(pos = target, range = range)
    val result = searchPath(creep, goal, SearchPathOptions(costMatrix = dangerMatrix))
    return result.path.firstOrNull()
}

internal fun fleeStep(creep: Creep, enemies: List<Creep>, dangerMatrix: CostMatrix, range: Int = RANGED_RANGE): Position? {
    if (enemies.isEmpty()) return null
    val goals = enemies.map { e -> SearchGoal(pos = InfluenceMap.cell(e.x, e.y), range = range) }.toTypedArray()
    val result = searchPath(creep, goals, SearchPathOptions(flee = true, costMatrix = dangerMatrix))
    return result.path.firstOrNull()
}

/** Центр крупнейшей группы (v120, см. USE_MASS_CLUSTER_CENTROID): сид — крип с наибольшим числом своих из списка в MASS_RANGE
 *  (при равенстве — больший id), центр — по его группе; без переключателя — среднее по всем. */
internal fun clusterCentroid(cs: List<Creep>): Position? {
    if (cs.size <= 2) return centroidOf(cs)
    val seed = cs.maxWithOrNull(compareBy<Creep>({ c -> cs.count { getRange(c, it) <= MASS_RANGE } }, { it.id })) ?: return null
    return centroidOf(cs.filter { getRange(seed, it) <= MASS_RANGE })
}


/** Восемь направлений и стояние в своей системе координат (см. mirrorTL). */
internal fun dirsNow(): List<Pair<Int, Int>> = if (mirrorTL) DIRECTIONS_MIRROR else DIRECTIONS

internal class ArmyMeasuresOut(
    val allies: List<Creep>,
    val enemyCreeps: List<Creep>,
    val combatEnemies: List<Creep>,
    val strikers: List<Creep>,
    val armedEnemies: List<Creep>,
    val enemyMassedNow: Boolean,
    val now: Int,
    val exchangeLedger: Int,
    val exchangeLive: Boolean,
    val exchangePaying: Boolean,
    val mobileArmy: List<Creep>,
    val commandArmy: List<Creep>,
    val chasers: List<Creep>,
    val huntable: List<Creep>,
    val meleeAdjacent: Boolean,
    val exchangeRecent: Boolean,
    val fightOn: Boolean,
    val cornered: Boolean,
    val stalled: Boolean,
    val ours: Double,
    val theirs: Double,
    val theirsUp: Double,
    val theirsDown: Double,
    val enemyNear: Boolean,
    val massCentroid: Position,
    val massArmy: List<Creep>,
    val contact: Boolean,
    val breakOffNow: Boolean,
    val retreatFeasible: Boolean,
)

/** МЕРЫ АРМИИ ЗА ТИК (v256, этап 10; сегмент runArmy): бойцы и его вооружённые, сомкнутость, охота (huntable), масса и контакт, истории дистанций и центров, простой и бесплодная охота, отход по размену, признак «слабее», выполнимость отхода. Перенесено дословно. */
internal fun PainAndGain.armyMeasures(ctx: Ctx): ArmyMeasuresOut {
    val allies = ctx.myCreeps
    val enemyCreeps = ctx.enemyCreeps
    val combatEnemies = ctx.combatEnemies

    val strikers = ctx.army.filter { fullSpeed(it) && hasWeapon(it) && it.id !in Memory.keeperIds }
    // враги, с которыми есть бой: с уроном — и лекари, у которых рядом (в дальности лечения плюс шаг) есть свой с
    // оружием в теле, живым или мёртвым: такого лекарь вернёт в строй за шесть тиков (стенд m2 rush: армия ушла за
    // флагами от лекарей с обломками, и через сто тиков те вернулись в полном теле). Стая из одних лекарей при
    // скаутах (сила 0) не повод для боя: два уцелевших лекаря врага кайтили в четырёх клетках 1700 тиков, армия
    // в ДОБИТЬ то гналась, то сбивалась в кучу и не шла за флагами, пока скауты врага брали пять (m7 rush)
    val armedEnemies = ctx.threats
    // сомкнутая масса врага — та же мера, что в postureOf (см. enemyMassed): шесть и больше вооружённых, две трети
    // которых в MASS_RANGE от их центроида. Считается раз на тик, а не на крипа (v135)
    val enemyMassedNow = armedEnemies.size >= 6 && centroidOf(armedEnemies)?.let { c ->
        armedEnemies.count { getRange(it, c) <= MASS_RANGE } * 3 >= armedEnemies.size * 2 } == true
    kiteMassed = enemyMassedNow
    // чистый урон врагу за окно: сумма его хитов ниже, чем STALL_TICKS тиков назад (попадание с полным лечением в тот же
    // тик — не прогресс: стрелок россыпи с трёх клеток попадал, лечился, и «обмен уронами» сбрасывал простой);
    // простой — только когда добыча в досягаемости броска, а прогресса нет (на марше к врагу за 20+ клеток простоя
    // нет — стенд m3 army: пауза на подходе выключала давление, и обе армии простояли до конца)
    val now = getTicks()
    val enemyHitsNow = enemyCreeps.sumOf { it.hits }
    if (lastEnemyHitsTotal >= 0 && enemyHitsNow < lastEnemyHitsTotal) enemyDamageTaken += lastEnemyHitsTotal - enemyHitsNow
    lastEnemyHitsTotal = enemyHitsNow
    val ourHitsNow = ctx.army.sumOf { it.hits }
    Memory.enemyHitsHist.addLast(enemyHitsNow); Memory.ourHitsHist.addLast(ourHitsNow)
    while (Memory.enemyHitsHist.size > STALL_TICKS) Memory.enemyHitsHist.removeFirst()
    while (Memory.ourHitsHist.size > STALL_TICKS) Memory.ourHitsHist.removeFirst()
    // РАЗМЕН ЗА ОКНО ПОДНЯТ СЮДА (v221) из блока толчка: его слагаемые готовы уже здесь — наши потери копятся в
    // прологе, его строкой выше, — а читать окно нужно раньше, чем решаются бой по контакту и простой. Между
    // старым и новым местом окна не читал никто (`lostRaceNow()` зовётся из `runRunners`, до `runArmy`, и ниже
    // блока толчка; ранних выходов в этом отрезке нет), поэтому перенос без тумблера: дифф отчёта пуст побайтово
    val exchangeLedger = enemyDamageTaken - ourDamageTaken
    // ...и тот же размен ЗА ОКНО (v216, см. ledgerHist): срез берётся в одной точке тика, потому что
    // слагаемые обновляются в разных местах — наши потери в прологе, его в runArmy
    Memory.ledgerHist.addLast(exchangeLedger)
    Memory.ourLostHist.addLast(ourDamageTaken)
    Memory.hisLostHist.addLast(enemyDamageTaken)
    while (Memory.ledgerHist.size > LEDGER_WINDOW + 1) Memory.ledgerHist.removeFirst()
    while (Memory.ourLostHist.size > LEDGER_WINDOW + 1) Memory.ourLostHist.removeFirst()
    while (Memory.hisLostHist.size > LEDGER_WINDOW + 1) Memory.hisLostHist.removeFirst()
    ledgerWindow = if (Memory.ledgerHist.size >= 2) Memory.ledgerHist.last() - Memory.ledgerHist.first() else 0
    ourLostWindow = if (Memory.ourLostHist.size >= 2) Memory.ourLostHist.last() - Memory.ourLostHist.first() else 0
    hisLostWindow = if (Memory.hisLostHist.size >= 2) Memory.hisLostHist.last() - Memory.hisLostHist.first() else 0
    // РАЗМЕН ИДЁТ (v221, см. USE_FIGHT_BY_LEDGER): за LEDGER_WINDOW тиков хоть одна сторона потеряла
    // STALL_DAMAGE хитов. Та же мера, что у клапана проигранной гонки (см. lostRaceNow) и у простоя
    // (netDamage), только по обеим половинам окна: новых чисел нет. Разбор двух рейтинговых серий по реплеям
    // обеих сторон делит ею забеги начисто: в проигранных армия 38 % тиков в постуре ANNIHILATE, и в 92 %
    // этих тиков размена нет вовсе, — это 35 % матча против 4 % в выигранных (v219: 24 % против 5 %)
    val exchangeLive = ourLostWindow >= STALL_DAMAGE || hisLostWindow >= STALL_DAMAGE
    exchangeLiveNow = exchangeLive
    if (exchangeLive && firstFightTick == 0) firstFightTick = getTicks()
    // наступление окупается (см. PUSH_EXCHANGE); без окна — да (нечего мерить)
    val exchangePaying = Memory.ourHitsHist.size < STALL_TICKS ||
        (Memory.enemyHitsHist.first() - enemyHitsNow) >= (Memory.ourHitsHist.first() - ourHitsNow) * PUSH_EXCHANGE
    val mobileArmy = ctx.army.filter { canMove(it) && it.id !in Memory.keeperIds }
    // ...а командир видит ВСЁ поле, включая хранителей флагов: решение снять хранителя — его, а не следствие того,
    // что он невидим (v173, оператор). Держат флаг они по-прежнему сами, пока приказа нет
    // ...и В РЕЖИМЕ ПАР БЕЗ ХРАНИТЕЛЕЙ (v306, см. GROUP_SAFE_DMG): состав командира — единственное место, где хранитель
    // ему виден (марш и погоня берут mobileArmy и strikers, а те его исключают), и приказ уводит его с флага (правило
    // v173, «хранитель тоже слушает приказ»). Против けろびー#19 это 71 снятие хранителя из 110 за матч — «сошёл с клетки»
    val commandArmy = ctx.army.filter { canMove(it) && !(groupSafe && it.id in Memory.keeperIds) }
    val chasers = strikers.ifEmpty { mobileArmy }
    // кого вообще можно догнать (см. catchable): добивание по перевесу идёт только за ними, и по ним же считается
    // пикет простоя — поэтому охота посчитана здесь, до простоя
    val huntable = armedEnemies.filter { catchable(it, chasers) }
    cpuMark("a.hunt")
    // его мили вплотную к нашим — поднято сюда из блока постуры (v221, перенос без тумблера): читать его нужно и
    // прибору погони ниже, а между старым и новым местом он не менялся и не читался
    val meleeAdjacent = combatEnemies.any { e -> hasMelee(e) && ctx.army.any { getRange(e, it) <= 1 } }
    // ПАРА К ПОГОНЕ ЗА КАЙТЕРОМ (v221, только прибор; см. kchaseTicks). Разбор v220, матч с ●ω<♥♪#1: он не взял ни
    // одного флага за 700 тиков, мы 300 тиков гнались за его отходящей линией в постуре ANNIHILATE и легли 12 → 0,
    // а вето безфлагового броска отказало захвату 3329 раз. Простой «держит дистанцию» против него не наступает:
    // любой его выстрел даёт netDamage, и простой гаснет. Прибор считает тики, где погоня идёт в ОДНУ сторону —
    // за окно мы потеряли STALL_DAMAGE, он меньше нас, его центр от нас уходит, его мили не вплотную. Считается ДО
    // простоя и по тем величинам, какими простой читал бы её: постура и история дистанции — прошлого тика
    val kiteChaseNow = Memory.prevPosture == Posture.ANNIHILATE && ourLostWindow >= STALL_DAMAGE && ledgerWindow < 0 && !meleeAdjacent &&
        Memory.enemyDistHist.size >= 2 && Memory.enemyDistHist.last() > Memory.enemyDistHist.first()
    kiteChaseSeen = kiteChaseNow
    if (Memory.prevPosture == Posture.ANNIHILATE) { kchaseAnn.n++; if (kiteChaseNow) kchaseTicks.n++ }
    // с обеих сторон: бьют только нас — бой, не простой (матч 20, t=117)
    val netDamage = Memory.enemyHitsHist.size == STALL_TICKS &&
        (Memory.enemyHitsHist.first() - enemyHitsNow >= STALL_DAMAGE || Memory.ourHitsHist.first() - ourHitsNow >= STALL_DAMAGE)
    if (netDamage) stallUntil = 0
    // пикет в досягаемости броска все STALL_TICKS подряд (см. STALL_PICKET): армия, только что вошедшая в
    // досягаемость, простоя не даёт (матч 20, t=64). Считается пикет по ДОГОНЯЕМЫМ (см. catchable): прежде
    // «не больше STALL_PICKET вооружённых» отделяло пикет от армии по ЧИСЛУ, и блоб, который от нас уклоняется и
    // не дерётся, одним своим числом отменял простой — армия гналась за ним весь матч. Живой матч 26: huntable=0/8,
    // 902 хита урона за 1500 тиков с обеих сторон, ни одной смерти, проигрыш 12721:23408; стенд farm на карте 21:
    // три флага на одиннадцать очков в тик простояли ничьими 1500 тиков. Кого можем догнать — драка; кто
    // уклоняется — не повод стоять
    val nearArmed = armedEnemies.count { e -> strikers.any { getRange(it, e) <= ENGAGE_RANGE } }
    val nearCatchable = huntable.count { e -> strikers.any { getRange(it, e) <= ENGAGE_RANGE } }
    // пикет — МЕНЬШИНСТВО его вооружённых (v50, оператор по матчу 101: «загоняем врага в угол, а потом разворачиваемся и
    // уходим в центр, не добив»): загнанный в угол остаток из трёх-четырёх вооружённых с лекарями четыре раза за матч
    // считался пикетом («picket of 3 armed in reach for 20 ticks — flags until +300», t=161, 547, 895, 1256), и армия на
    // триста тиков уходила за флагами. Когда в досягаемости не меньше половины его вооружённых, это его армия, а не пикет
    val picket = nearArmed >= 1 && nearCatchable <= STALL_PICKET && nearArmed * 2 < armedEnemies.size
    preyNearTicks = if (picket) preyNearTicks + 1 else 0
    // ВТОРОЙ вид простоя — марш, который не идёт. Пикет ловит бесплодную погоню, только пока добыча ближе
    // ENGAGE_RANGE; за этой чертой армия «гналась» и стояла, а простой не считался ни разу. Идущая погоня обязана
    // двигать центр вооружённой массы: за MARCH_STALL_TICKS тиков он не сдвинулся НИ НА КЛЕТКУ — это не марш.
    // Матч 25: добыча стояла в 11 клетках, ближе никого, армия 990 тиков дёргалась на месте у (46,34); за весь
    // матч ни одного урона ни с одной стороны, и проигрыш по очкам 19927:24205 при 12 против 13 в тик
    val marchCell = centroidOf(ctx.armedArmy.ifEmpty { ctx.army })?.let { it.key } ?: -1
    Memory.marchHist.addLast(marchCell)
    // ТРЕТИЙ вид простоя — враг, который держит дистанцию: в добивании без контакта дистанция между центрами армий за
    // CHASE_WINDOW тиков не сократилась, и враг дальше броска. Матч 48 (けろびー v5, фермер): он взял шесть флагов к 80-му
    // (сам на 0,75 мощи), парил в 10–28 клетках от нас и не дрался — за 1600 тиков ни одного выстрела с нашей стороны;
    // пикет не срабатывал (враг дальше ENGAGE_RANGE), марш не «стоял» (армия за ним ходила), и ANNIHILATE держал армию
    // лицом к нему на двух-трёх флагах против его пяти: 8 в тик против 17, проигрыш 15652:22950 при 16000/16000 у обоих
    // застой по ближайшей группе (v132, USE_STALL_NEAREST_GROUP): центр его группы, ближайшей к нашему вооружённому центру
    val ourArmedCentroid = centroidOf(ctx.armedArmy.ifEmpty { ctx.army })
    val stallCentroid: Position? = ctx.enemyCentroid
    val armyDist = stallCentroid?.let { getRange(ourArmedCentroid ?: it, it) } ?: -1
    // бой — контакт С ОБМЕНОМ (v74, см. USE_COLD_CONTACT): выстрел наш или удар по нам не дальше STALL_TICKS назад
    val exchangeRecent = now - lastFireTick <= STALL_TICKS || (lastHurtTick > 0 && now - lastHurtTick <= STALL_TICKS)
    val fightOn = inContact(armedEnemies, ctx.army) && (exchangeRecent)
    val pauseReach = 2 * ENGAGE_RANGE   // v106: окно сквозь мигание
    val pausedChase =  Memory.prevPosture == Posture.HOLD &&
        armedEnemies.any { e -> ctx.army.any { getRange(e, it) <= pauseReach } }
    if ((Memory.prevPosture == Posture.ANNIHILATE || pausedChase) && !fightOn && armyDist >= 0) {
        Memory.armyDistHist.addLast(armyDist)
        // центр ВООРУЖЁННЫХ (v58): центр всех его крипов двигали два бегающих скаута, и стоящий на D5 лагерь «уходил» —
        // отряд на 566-м при his_moved=0 по реплею (матч 133, одиннадцатый проигрыш фермеру-лагерю 8346:22771)
        Memory.enemyCentHist.addLast((centroidOf(armedEnemies))?.let { it.key } ?: -1)
    } else { Memory.armyDistHist.clear(); Memory.enemyCentHist.clear() }
    while (Memory.armyDistHist.size > DETACH_WINDOW + 1) Memory.armyDistHist.removeFirst()
    while (Memory.enemyCentHist.size > DETACH_WINDOW + 1) Memory.enemyCentHist.removeFirst()
    // ...и это ОН уходит (v57): центр его вооружённых за окно сместился не меньше чем на четверть окна — стоячий лагерь, у
    // которого замираем мы, дистанцию не «держит». Окно отряда — DETACH_WINDOW (v59): по восьми тикам (CHASE_WINDOW) отряд
    // собрался на 207-м тике матча 139, когда дистанция за сто тиков сократилась с 40 до 12 — армия почти догнала, а
    // восьмитиковое окно поймало паузу; ядро без пяти встало, он ушёл и запарковался (3174:22931)
    fun kept(window: Int, needMoved: Boolean = true): Boolean {
        if (Memory.armyDistHist.size <= window || Memory.enemyCentHist.size <= window) return false
        val d0 = Memory.armyDistHist.elementAt(Memory.armyDistHist.size - 1 - window)
        val a = Memory.enemyCentHist.elementAt(Memory.enemyCentHist.size - 1 - window); val b = Memory.enemyCentHist.last()
        if (a < 0 || b < 0) return false
        val moved = maxOf(abs(a / 100 - b / 100), abs(a % 100 - b % 100))
        return Memory.armyDistHist.last() >= d0 && Memory.armyDistHist.last() > ENGAGE_RANGE && (!needMoved || moved >= window / 4)
    }
    val distanceKept = combatEnemies.isNotEmpty() && kept(DETACH_WINDOW)
    if (distanceKept) lastDistanceKeptTick = now
    // для простоя — и без его сдвига (v98, USE_KEEPS_DISTANCE_ANY_MOVE): застенчивый блоб держит дистанцию шагами на 2–3
    // загнанная группа в досягаемости (см. USE_STALL_CORNERED_GROUP): неподвижная группа его вооружённых не меньше SPLIT_MIN,
    // хотя бы один в досягаемости ударников, слабее их в PUSH_RATIO — застой «держит дистанцию» не наступает
    val corneredInReach =  armedEnemies.size >= SPLIT_MIN && run {
        val still = armedEnemies.filter { e ->
            val h = Memory.enemyCellHist[e.id]
            h != null && h.size >= CHASE_WINDOW && maxOf(abs(h.first() / 100 - h.last() / 100), abs(h.first() % 100 - h.last() % 100)) <= 1
        }
        val group = still.filter { e -> still.count { getRange(e, it) <= ENGAGE_RANGE } >= SPLIT_MIN }
        group.isNotEmpty() && group.any { e -> chasers.any { getRange(it, e) <= ENGAGE_RANGE + RANGED_RANGE } } &&
            ourPowerOf(chasers, group) >= enemyPowerOf(group, chasers) * PUSH_RATIO
    }
    val cornered = corneredInReach 
    if (DEBUG_LOG && cornered != corneredWas) println("cornered t=$now: ${if (cornered) "a still weak group of his in reach — no distance stall" else "gone"}")
    corneredWas = cornered
    val keepsDistance = !cornered && (
        ((distanceKept || (combatEnemies.isNotEmpty() && kept(DETACH_WINDOW, needMoved = false)))))
    while (Memory.marchHist.size > MARCH_STALL_TICKS) Memory.marchHist.removeFirst()
    // в контакте стоять — законно (строй рубится на месте), и полное взаимное лечение даёт нулевой чистый урон
    val marchStalled = pushing && marchCell >= 0 && Memory.marchHist.size == MARCH_STALL_TICKS &&
        Memory.marchHist.all { it == marchCell } && !fightOn
    // сухой толчок (v86): толчок PASSIVE_TICKS без нашего выстрела и без удара по нам — не толчок
    // в любой постуре, кроме отхода и уклонения: в ПОСТУ с висящим рядом врагом «держим линию» без простоя длилось до
    // конца матча (стенд m19 spread, t=600–1600)
    // боевые враги нужны ПИКЕТУ (он из них и состоит), а простою марша — нет: зачистка идёт ровно тогда, когда
    // боевых не осталось, и там сетка не сработала ни разу (стенд m18 roost: 1500 тиков погони за двумя скаутами)
    // ...И В ОТХОДЕ ТОЖЕ (v217). Оговорка «в любой постуре, кроме отхода и уклонения» стояла без своего
    // замера, а следствие у неё было тяжёлое: `!stalled` — единственный оставшийся выключатель
    // `contactFight`, поэтому после объявления отхода выйти из него было нельзя по построению, и счёт
    // оставался нулём при живых двенадцати крипах (живой замер v216: `objnone` даёт annihilate:179-207 при
    // постуре RETREAT). Условие `!netDamage` остаётся и само по себе узко: под огнём простой не объявляется,
    // так что правка касается ровно тихого отхода — того, из которого и надо уметь выйти
    if (!netDamage && now >= stallUntil &&
        ((combatEnemies.isNotEmpty() && preyNearTicks >= STALL_TICKS) || marchStalled || keepsDistance)) {
        stallUntil = now + STALL_COOLDOWN
        if (DEBUG_LOG) println("stall t=$now: ${if (marchStalled) "the march has not moved a cell for $MARCH_STALL_TICKS ticks" else if (keepsDistance) "the enemy keeps its distance (${Memory.armyDistHist.first()} -> ${Memory.armyDistHist.last()} over $CHASE_WINDOW ticks)" else "picket of $nearArmed armed in reach for $preyNearTicks ticks"} without damage either way — flags until $stallUntil")
    }
    val stalled = now < stallUntil
    stalledNow = stalled

    // ---- постура ----
    val ours = ourPowerOf(ctx.army, combatEnemies)
    val theirs = enemyPowerOf(combatEnemies, ctx.army)
    // РЕЖИМ ВЫЖИВАНИЯ (v223, доктрина оператора, см. USE_SURVIVAL): ведём по очкам, а его армия сильнее — не деремся,
    // а уходим, беря флаги с выходом. Порог и гистерезис — как у «слабее», по мощи всей армии
    val leadingNow = ourScore > enemyScore
    if (leadingNow && armedEnemies.isNotEmpty()) survLead.n++
    Memory.theirsHist.addLast(theirs)
    while (Memory.theirsHist.size > MEASURE_WINDOW) Memory.theirsHist.removeFirst()
    // множители окна (v114): его мощь сейчас → его сильнейшая и слабейшая за окно; при нулевой мере — 1
    val theirsUp = if (theirs > 0.0) (Memory.theirsHist.maxOrNull() ?: theirs) / theirs else 1.0
    val theirsDown = if (theirs > 0.0) (Memory.theirsHist.minOrNull() ?: theirs) / theirs else 1.0
    val nearRange = if (Memory.prevPosture == Posture.RETREAT) NEAR_RANGE + NEAR_RELEASE else NEAR_RANGE
    // ОТВЕРГНУТО стендом: «враг рядом — рядом с МАССОЙ армии, а не с любым нашим крипом» (хранители стоят по
    // одному на разных концах карты, и висящий у хранителя враг россыпи отменяет цель-флаг у всей армии).
    // Целевой сценарий m22 spread не сдвинулся вовсе (16622:24323), а m20 spread перешёл из победы в проигрыш
    // (24337:21666 -> 19400:24328) и m19 block из уничтожения армии врага на 389-м в победу по очкам на 1142-м;
    // выиграли только россыпи на 12, 19 и 21. Мигание постуры на россыпи — открытая находка
    val enemyNear = armedEnemies.any { e -> ctx.army.any { getRange(e, it) <= nearRange } }
    // контакт решает сам: пассивного поста в контакте нет — он отдаёт армию по одному (стенд rush: десять
    // за двоих). Отход из контакта возможен, только если он не бегство: никто из врагов-мили не вплотную
    // и наш строй не медленнее их самого быстрого — при равной скорости преследователь стреляет в спину
    // каждый тик, а обездвиженные остаются врагу (стенд rush: отход при 1250 против 1619 отдал ещё
    // шестерых). Иначе в контакте — бой всем составом, даже слабее: рубка с фокусом лучше разгрома
    // контакт армии — контакт её МАССЫ (см. MASS_RANGE): один оторвавшийся не переводит армию в бой
    val massCentroid = clusterCentroid(ctx.armedArmy.ifEmpty { ctx.army }) ?: ctx.ourCentroid
    val massArmy = ctx.army.filter { getRange(it, massCentroid) <= MASS_RANGE }.ifEmpty { ctx.army }
    val contact = inContact(armedEnemies, massArmy)
    // ТРЕТЬЯ ПОСТАНОВКА (v227, см. USE_ZERO_LEAD_BREAK): мощь ноль при отрыве по очкам STALL_TICKS подряд — армия
    // разрывает контакт СТРОЕМ: командир раздаёт клетки замыслом KITE с дальностью «вне его стрелкового огня», свободный
    // шаг отскакивает от ближайшего вооружённого на RANGED_RANGE + 1; постура не меняется, все механизмы боя живут.
    // Не бегство к точке (первая редакция выживания) и не непрерывное уклонение к выходу (вторая), а шаг назад строем
    val zeroLead = leadingNow && contact && armedEnemies.isNotEmpty() && ours <= 0.0 && theirs > 0.0
    zeroLeadTicks = if (zeroLead) zeroLeadTicks + 1 else 0
    if (zeroLead) zlbZero.n++
    // ИДЁТ ЛИ БОЙ (v215): контакт по МАССЕ армии либо размен за последние STALL_TICKS тиков. Считается
    // здесь, ВЫШЕ отряда и командирской гонки, — оба механизма разделения читают его этим тиком, а не
    // прошлым (порядок тика: runRunners идёт раньше runArmy, и признак, посчитанный ниже, опаздывал бы)
    fightOnNow = contact || exchangeRecent
    coreContactNow = contact
    // ПАРА К ОТЗЫВУ ПО РАЗМЕНУ (v221, только прибор): сколько тиков «бой идёт» держится на одном слове
    // «контакт» — ни одна сторона за окно не потеряла STALL_DAMAGE. Читать вместе с recall= и budget=
    if (fightOnNow) { warmFightAll.n++; if (!exchangeLive) warmFight.n++ }
    // ПРИЗНАК ОТХОДА СЧИТАЕТСЯ ЗДЕСЬ (v217, см. USE_BREAK_OFF_HOLDS_LINE): он должен успеть погасить
    // наступление, а `pushing` решается на триста строк ниже. Величины готовы: контакт уже есть, а
    // `ourDamageTaken`/`enemyDamageTaken` копятся с начала матча
    val outmatchedNow =  contact && armedEnemies.isNotEmpty() &&
        ourDamageTaken > 0 && enemyDamageTaken < ourDamageTaken * BREAK_OFF_RATIO
    outmatchedTicks = if (outmatchedNow) outmatchedTicks + 1 else 0
    val breakOffNow = outmatchedTicks >= BREAK_OFF_TICKS
    // ОТЗЫВ (v215, см. USE_NO_SPLIT_IN_FIGHT): едва бой начался, отпущенные возвращаются в кулак. Очистка стоит
    // ЗДЕСЬ, а не внутри `commandRace`: выйдя из режима гонки, командир эту функцию не зовёт вовсе, и `cmdDetach`
    // оставался с прошлого тика — отпущенные не возвращались никогда
    // ...КРОМЕ ДЕРЖАТЕЛЕЙ, пока ядро не в контакте (v297, см. HOLD_WATCH): «бой» — это и выстрел любого нашего, и урон по
    // любому нашему, и одиночный стрелок фермера, подстреливший держателя на краю карты, снимал с флагов всех. В контакте
    // ядра в кулак возвращаются все, как прежде
    if (fightOnNow) {
        fightTicksNow.n++
        // ...а в режиме пар (v326) — и отряды на пути к флагу: «бой» здесь держится двадцать тиков после любого выстрела,
        // и отзыв срабатывал 506 раз за матч, отправляя в ядро тех, кого командир только что послал (man=124, split=392).
        // Возражение v301 снято тем же, чем и в v325: сидящий на флагах в режим пар не попадает (v302)
        val keep = if (contact) emptySet() else ctx.runners.filter { (heldFlag(ctx, it) ?: guardFlag(ctx, it)) != null ||
            (groupSafe && it.id in Memory.cmdDetach) }.mapTo(HashSet()) { it.id }
        val before = Memory.cmdDetach.size + Memory.detachedIds.size
        Memory.cmdDetach.retainAll(keep)
        Memory.detachedIds.retainAll(keep)
        val kept = Memory.cmdDetach.size + Memory.detachedIds.size
        holdKeptFight.n += kept
        recalled.n += before - kept
    }
    val ourPeriod = mobileArmy.maxOfOrNull { plainPeriod(it) } ?: 1
    val theirPeriod = combatEnemies.filter { canMove(it) }.minOfOrNull { plainPeriod(it) } ?: Int.MAX_VALUE / 4
    // при РАВНОЙ скорости отход из контакта без мили вплотную — размен выстрелами в обе стороны, не бегство; из
    // точки отхода, куда уже пришли, отходить некуда — бой (матч 5: семеро в углу (3,96) при «отходе» не
    // шевелились и не били, пока их расстреливали с трёх клеток)
    val atRetreatPoint = retreatTarget?.let { getRange(ctx.ourCentroid, it) <= POST_STANDOFF + ARRIVED_SLACK } ?: false
    // из контакта отхода нет: «отход, пока мили не вплотную» мигал ДОБИТЬ/ОТХОД через тик и отдавал армию по одному
    // в матчах 4, 6 и 7 (в седьмом — при 1.53, без единой потери у врага); при равной скорости из контакта не
    // уйти, и единственный способ его кончить — бой строем
    // без стрелков строя нет — лекарей и раненых травят, и «в контакте держим строй» держало их у поста, где
    // стоял враг (стенд m7 sleeper: три лекаря с flee=true разбежались по карте и были добиты поодиночке)
    val retreatFeasible = (!contact || strikers.isEmpty()) && !atRetreatPoint
    cpuMark("a.retreat")
    return ArmyMeasuresOut(
        allies = allies,
        enemyCreeps = enemyCreeps,
        combatEnemies = combatEnemies,
        strikers = strikers,
        armedEnemies = armedEnemies,
        enemyMassedNow = enemyMassedNow,
        now = now,
        exchangeLedger = exchangeLedger,
        exchangeLive = exchangeLive,
        exchangePaying = exchangePaying,
        mobileArmy = mobileArmy,
        commandArmy = commandArmy,
        chasers = chasers,
        huntable = huntable,
        meleeAdjacent = meleeAdjacent,
        exchangeRecent = exchangeRecent,
        fightOn = fightOn,
        cornered = cornered,
        stalled = stalled,
        ours = ours,
        theirs = theirs,
        theirsUp = theirsUp,
        theirsDown = theirsDown,
        enemyNear = enemyNear,
        massCentroid = massCentroid,
        massArmy = massArmy,
        contact = contact,
        breakOffNow = breakOffNow,
        retreatFeasible = retreatFeasible,
    )
}

internal class RememberTickOut(
    val armedCentroid: Position,
)

/** ПАМЯТЬ ТИКА (v257, этап 10; сегмент tickBody после исполнения): стойки полей, его прошлые клетки и ходы, история центра наших вооружённых и его клеток. Перенесено дословно. */
internal fun rememberTick(ctx: Ctx, bw: BuildWorldOut): RememberTickOut {
    InfluenceMap.pruneStances(bw.myCreeps.mapTo(HashSet()) { it.id })
    // кто из врагов сдвинулся за тик — для признака «стоит на месте» (см. stationary)
    for (e in bw.enemyCreeps) {
        val cell = e.key
        if (Memory.enemyPrevCell[e.id] != cell) Memory.enemyLastMove[e.id] = getTicks()
    }
    Memory.enemyLastMove.keys.retainAll { id -> bw.enemyCreeps.any { it.id == id } }
    Memory.enemyPrevCell.clear()
    for (e in bw.enemyCreeps) Memory.enemyPrevCell[e.id] = e.key
    // история движения — для ловимости (см. evasive)
    val armedCentroid = centroidOf(ctx.armedArmy.ifEmpty { bw.army }) ?: bw.ourCentroid
    Memory.ourCentroidHist.addLast(armedCentroid.key)
    while (Memory.ourCentroidHist.size > CHASE_WINDOW) Memory.ourCentroidHist.removeFirst()
    for (e in bw.enemyCreeps) {
        val h = Memory.enemyCellHist.getOrPut(e.id) { ArrayDeque() }
        h.addLast(e.key)
        while (h.size > CHASE_WINDOW) h.removeFirst()
    }
    Memory.enemyCellHist.keys.retainAll { id -> bw.enemyCreeps.any { it.id == id } }
    return RememberTickOut(
        armedCentroid = armedCentroid,
    )
}

internal class ReadSignalsOut(
)

/** СИГНАЛЫ ТИКА (v257, этап 10; сегмент tickBody до бегунов и армии): сомкнутость по форме и по прибытию, бросок безфлаговой армии (unflaggedRushNow), «бой близко» (fightImminentNow), полученный урон, тишина огня, «он не дерётся» (enemyNotFightingNow). Перенесено дословно. */
internal fun PainAndGain.readSignals(ctx: Ctx, bw: BuildWorldOut): ReadSignalsOut {
    plannedCaptures.clear()
    // доктрина «первый флаг — их» (см. EVADE_EQUAL_RATIO) — до бегунов: их захват идёт тем же гейтом
    // сомкнутая армия (см. MASS_RANGE): россыпь по флагам и клубок фермера — не бросок, хотя их части тоже идут к нам
    val armedNow = bw.ctx.threats
    val massedByShape = armedNow.size >= 6 && centroidOf(armedNow)?.let { c -> armedNow.count { getRange(it, c) <= MASS_RANGE } * 3 >= armedNow.size * 2 } == true
    // СОМКНУТ ТОТ, КТО ПРИХОДИТ ВМЕСТЕ (v226, см. USE_MASS_BY_ARRIVAL): колонна на марше двумя эшелонами (пять впереди,
    // четверо в пятнадцати клетках позади) по форме не сомкнута — центр масс лежит в зазоре, и «в MASS_RANGE от центра»
    // даёт ноль, — а к нам она приходит целиком за шесть тиков. Мера прихода: две трети его вооружённых не дальше
    // MASS_RANGE от ближайшего к нашей массе по расстоянию до неё
    val massedByArrival =  armedNow.size >= 6 && run {
        val d = armedNow.map { getRange(it, bw.ctx.ourCentroid) }
        val near = d.minOrNull() ?: return@run false
        d.count { it - near <= MASS_RANGE } * 3 >= armedNow.size * 2
    }
    if (massedByArrival && !massedByShape) massArrivalAdded.n++
    val enemyMassed = massedByShape || massedByArrival
    enemyMassedSignal = enemyMassed
    // ДРАЛСЯ ЛИ ОН С НАМИ СОМКНУТЫМ (v434, см. USE_GATE_VS_FIGHTER): защёлка на матч. Россыпь дерётся стычками по одному-два
    // крипа у флагов и сюда не попадает; блок, который между боями расходится группами по флагам, — попадает первым же боем
    if (exchangeLiveNow && enemyMassed) fightMassedSeen = true
    // с гистерезисом: темп сближения ходит вокруг порога (колонна на марше то растягивается шире MASS_RANGE, то
    // замедляется), и без него уклонение сменялось стоянием каждые десять-тридцать тиков, пока враг шёл — матч 32:
    // EVADE 57, HOLD 69 при approach=84, EVADE 94, HOLD 109 при 42, EVADE 117, HOLD 122, контакт на 127-м и 12:0.
    // Начатый бросок кончается, когда враг взял флаг, замер, разошёлся или ушёл дальше EVADE_RANGE и не приближается
    val noEnemyFlag = bw.ctx.flags.none { it.theirs }
    // блоб, идущий к СВОБОДНОМУ ФЛАГУ, а не на нас (v127, USE_RUSH_NOT_FLAG_BOUND): ближайший к его центру свободный флаг
    // не дальше нашего центра, и его темп к этому флагу не ниже темпа к нам — он на туре. Матч 5 серии 367–386 (MetalicaX#3):
    // с 10-го по 41-й rush=true, армия на посту, захваты под вето; он взял D5 на 41-м, R3 на 45-м, оба A3 на 60-м, наш
    // первый флаг — на 54-м. Бросок сквозь центр без захвата снова читается броском, когда его темп к флагу падает
    val rushSignal = !bw.ctx.passiveEnemy && noEnemyFlag && approachRate >= APPROACH_RUSH && enemyMassed  
    if (rushSignal) { rushSignalAll.n++; if (!massedByShape) rushByArrival.n++ }
    val rushHold = unflaggedRushNow && !bw.ctx.passiveEnemy && noEnemyFlag && armedNow.isNotEmpty() &&
        (approachRate > 0.0 || armedNow.any { getRange(it, bw.ctx.ourCentroid) <= EVADE_RANGE })
    unflaggedRushNow = rushSignal || rushHold
    // бой близко — для ЗАХВАТОВ флаг врага не в счёт: «безфлаговый» бросок кончился на 39-м тике, когда его армия по пути
    // взяла D5, и скаут взял R3 на 42-м (матч 47, пятый бой с けろびー подряд с R×0.8); уклонение по-прежнему только от
    // безфлагового (флагованный слабее — с ним дерёмся), а дебафф перед боем не берём ни от кого
    // ...и «идёт ОН, а не мы» (v216, см. USE_RUSH_NEEDS_HIS_MOVE): тот же корректор, что уже стоит у строя
    // стрелков, — центр его вооружённых обязан сдвинуться за окно не меньше чем на четверть окна
    val hisCentreMoved =  (Memory.hisCentHist.size >= 2 && run {
        val a = Memory.hisCentHist.first(); val b = Memory.hisCentHist.last()
        maxOf(abs(a / 100 - b / 100), abs(a % 100 - b % 100)) >= APPROACH_WINDOW / 4
    })
    approachingNow = !bw.ctx.passiveEnemy && approachRate >= APPROACH_RUSH && enemyMassed && hisCentreMoved
    fightImminentNow = unflaggedRushNow || approachingNow
    // ...и ЗАПОМИНАЕМ РАССТОЯНИЕ НА НАЧАЛО ПОДХОДА (v215, см. USE_RUSH_VETO_EXPIRES). Первая редакция срока
    // сравнивала с ТЕКУЩИМ расстоянием между центрами — а оно по мере подхода сокращается, то есть срок
    // ужесточался ровно наоборот и вето снималось в тот момент, когда бой действительно начинался: строка
    // match20:brawl+heals перестала проходить гейт (7 674:2 503 -> 12 310:18 500). Время, которое ему нужно,
    // чтобы дойти, задаётся расстоянием НА СТАРТЕ броска, и оно не меняется, пока бросок идёт
    if (fightImminentNow && fightImminentTicks == 0)
        rushStartDist = bw.ctx.enemyCentroid?.let { getRange(bw.ctx.ourCentroid, it) } ?: 0
    fightImminentTicks = if (fightImminentNow) fightImminentTicks + 1 else 0
    // враг рядом, но не воюет: армия с боем в досягаемости броска, и наши хиты не падали STALL_TICKS тиков подряд — фермер
    // (けろびー v5, матчи 51 и 57: шесть флагов к 82-му, 1400 тиков рядом без единого выстрела, 10804:22779 при 12 наших
    // против его 4 к концу); гейты боя в captureAllowed («в контакте», «бой близко») к нему не применяются, паритет —
    // применяется. Атакующий стреляет через несколько тиков после контакта, и счётчик не доходит до STALL_TICKS
    // по ВСЕМ нашим (v56): сумма по армии падала на хиты только что отряжённых в бегуны, «нас ударили» — и отряд распускался
    // на следующий же тик (стенд m28 farm+weak: 6 detached на 100-м, 0 на 101-м с hurt=0, трижды за матч)
    val ourHitsSum = bw.ctx.myCreeps.sumOf { it.hits }
    val hurt = lastOurHits >= 0 && ourHitsSum < lastOurHits
    if (hurt) ourDamageTaken += lastOurHits - ourHitsSum
    lastOurHits = ourHitsSum
    // УРОН ПО ГРУППЕ (v298, см. GROUP_SAFE_DMG): по нашим крипам, у которых свой крип с оружием в двух клетках. Режим
    // включается, когда его армия не одним кулаком (крупнейшая группа с оружием в ENGAGE_RANGE — не больше двух третей
    // его вооружённых), и держится, пока он не бьёт группу: кулак, притихший на время, режима не открывает
    var groupDmg = 0
    val oursArmed = bw.ctx.myCreeps.filter { hasWeapon(it) }
    for (c in bw.ctx.myCreeps) {
        val prev = Memory.groupHitsPrev[c.id]
        if (prev != null && c.hits < prev && oursArmed.any { it.id != c.id && getRange(it, c) <= 2 }) groupDmg += prev - c.hits
        Memory.groupHitsPrev[c.id] = c.hits
    }
    Memory.groupDmgHist.addLast(groupDmg)
    while (Memory.groupDmgHist.size > GROUP_WINDOW) Memory.groupDmgHist.removeFirst()
    groupDmgWindow = Memory.groupDmgHist.sum()
    val hisW = bw.ctx.combatEnemies.filter { hasWeapon(it) }
    val largestW = hisW.maxOfOrNull { e -> hisW.count { getRange(e, it) <= ENGAGE_RANGE } } ?: 0
    val splitNow = hisW.size >= 3 && largestW * 3 <= hisW.size * 2
    // ...И НЕ ПРОТИВ ТОГО, КТО ДЕРЖИТ СВОИ ФЛАГИ ТЕЛОМ (v302): на занятую клетку пара не встанет, такой флаг отбирает
    // только сила ядра, и дробить армию парами не за чем. Замер по 44 реплеям: его флаго-тики с его крипом НА клетке —
    // けろびー 4 %, Coldkimchi#2 и MetalicaX по 1 %, а System и 恒哥吊 66 %; стендовые фермеры (scatter, camp, farm+weak)
    // сажают крипа на каждый свой флаг — там пары ходили впустую, а ядру не хватало силы отбить занятый H4
    // (match33:scatter 22 377:24 235, FAIL гейта)
    val hisFlagsNow = bw.ctx.flags.count { it.theirs }
    val hisSitNow = bw.ctx.flags.count { it.theirs && it.occupant != null && it.occupant?.my != true }
    Memory.flagSitHist.addLast(hisFlagsNow * 8 + hisSitNow)
    while (Memory.flagSitHist.size > GROUP_WINDOW) Memory.flagSitHist.removeFirst()
    val sitHis = Memory.flagSitHist.sumOf { it / 8 }
    val sitOcc = Memory.flagSitHist.sumOf { it % 8 }
    // ...и «сидит» — это сидит на ВСЕХ, а не на трети (v371). Порог в треть (sitOcc * 3 >= sitHis) равнял стендовый
    // scatter, где его крип стоит на 95,8 % флаго-тиков (минимум по шести строкам 75 %), с топ-1
    // `ricardo18informatica2020#14`, который сидит на 47 %: у первого отбирать нечего и пары ходят впустую (четыре
    // строки гейта scatter падали 11–19 тыс. против 24 тыс.), у второго половина флагов пуста — там и гарнизон, и
    // курьер имеют смысл. Порог три четверти разводит их с запасом: camp 20 %, farm 50 %, けろびー 6 %
    val sitsOnFlags = sitOcc * 4 >= sitHis * 3
    // ...И «ОН СИДИТ НА ФЛАГАХ» БОЛЬШЕ НЕ ВЫКЛЮЧАЕТ РЕЖИМ ПАР (v370). Признак вводился против стендовых лагерей, где
    // пары ходили отбивать занятый флаг и не могли (match33:scatter 22 377 : 24 235), — но он гасил ВЕСЬ аппарат, а
    // не только поход на занятую клетку. Цена измерена на топ-1 `ricardo18informatica2020#14`: у него групповой урон
    // по нашим 36 при пороге 300 (в восемь раз ниже — он наши группы не бьёт вовсе, kills=0 за матч), то есть режим
    // пар ему подходит идеально, а сидит он на 47 % своих флаго-тиков — и режим не включался НИ РАЗУ (1,2 % тиков
    // против 97,3 % у けろびー). Ни постоянного гарнизона, ни правила ухода, ни курьера против топ-1 не работало.
    // Запрет на занятые флаги живёт там, где он и нужен: пара и курьер не идут на клетку под его телом
    groupSafe = getTicks() >= GROUP_WINDOW && groupDmgWindow <= GROUP_SAFE_DMG && !sitsOnFlags && (groupSafe || splitNow)
    if (groupSafe) groupSafeTicks.n++
    flagSitOcc.n = sitOcc; flagSitAll.n = sitHis
    val enemyNear = armedNow.any { e -> bw.ctx.army.any { getRange(e, it) <= ENGAGE_RANGE + RANGED_RANGE } }
    noFireTicks = if (enemyNear && !hurt) noFireTicks + 1 else 0
    // фермер — не только «не стреляет», но и «держится дальше броска»: стоящий в 3–6 экран стенда тоже не стрелял, пока
    // мы стояли на своём флаге, и перехват превратил бой, который v38 выигрывала на 439-м, в стояние до конца матча
    // (m31 screen: 16000/16000 у обоих 800 тиков, проигрыш по очкам); враг в ENGAGE_RANGE — это бой, не перехват
    val enemyWithinReach = armedNow.any { e -> bw.ctx.army.any { getRange(e, it) <= ENGAGE_RANGE } }
    enemyNotFightingNow =  noFireTicks >= STALL_TICKS && !enemyWithinReach
    if (hurt) lastHurtTick = getTicks()
    if (enemyWithinReach) lastReachTick = getTicks()
    // «он подходил» для фермера — по NEAR, не по броску (v72): застенчивый лагерь стенда (camp+shy) держит девять клеток и в
    // восемь не входит никогда — вся цепочка фермера (отряды, порог гонки, стая не преграда) молчала 800 тиков при
    // pushing=true в девяти клетках от него (m30, 9615:23822)
    if (enemyNear && firstNearTick < 0) firstNearTick = getTicks()
    return ReadSignalsOut(
    )
}

internal class BuildWorldOut(
    val myCreeps: List<Creep>,
    val enemyCreeps: List<Creep>,
    val active: List<Creep>,
    val combatEnemies: List<Creep>,
    val flags: List<FlagInfo>,
    val army: List<Creep>,
    val runners: List<Creep>,
    val passiveEnemy: Boolean,
    val ourCentroid: Position,
    val enemyCentroid: Position?,
    val ctx: Ctx,
)

/** СБОРКА МИРА (v257, этап 10; начало tickBody): сброс тиковых кэшей, крипы обеих сторон, дом, флаги с эффектами и счётом, раздел армии и бегунов, препятствия, поля влияния, матрицы опасности, карта расстояний, Ctx, прибытие врага. Перенесено дословно. */
internal fun PainAndGain.buildWorld(): BuildWorldOut {
    BodyMemo.bodyWeightNow.clear()
    BodyMemo.liveMovesNow.clear()
    Executor.clear()
    flagFlipNow = false
    bfsMaxTick = maxOf(bfsMaxTick, bfsThisTick)
    bfsMaxCost = maxOf(bfsMaxCost, bfsCost)
    bfsThisTick = 0
    bfsCost = 0.0
    val nowT = getTicks()
    val dead = Memory.flowCacheTick.filterValues { nowT - it > FLOW_KEEP }.keys.toList()
    for (k in dead) { Memory.flowCache.remove(k); Memory.flowCacheTick.remove(k) }
    avoidCellsCache = null

    val myCreeps = getObjectsByPrototype(Creep::class).filter { it.my && it.exists }
    val enemyCreeps = getObjectsByPrototype(Creep::class).filter { !it.my && it.exists && !it.spawning }
    // словарь фактов тика (Facts.kt) — до первого ролевого теста: всё ниже читает его через unitOf
    val units = TickFacts(myCreeps + enemyCreeps)
    unitsNow = units
    val active = myCreeps.filter { !it.spawning }
    val combatEnemies = enemyCreeps.filter { val p = InfluenceMap.profileOf(it); p.melee + p.ranged + p.heal > 0.0 }

    // дома сторон — стартовые центры армий: спавнов на карте нет, половины и пост считаются от них
    // ...и сторона — до первого центра: округление центра уже в своей системе координат (v286, см. mirrorTL)
    if (homePos == null && active.isNotEmpty()) mirrorTL = (active.sumOf { it.x } + active.sumOf { it.y }) < 98 * active.size
    if (homePos == null && active.isNotEmpty()) homePos = centroidOf(active)
    if (enemyHomePos == null && enemyCreeps.isNotEmpty()) enemyHomePos = centroidOf(enemyCreeps)
    val home = homePos ?: centroidOf(active) ?: InfluenceMap.cell(50, 50)
    val enemyHome = enemyHomePos ?: InfluenceMap.cell(99 - home.x, 99 - home.y)

    val flags = collectFlags(myCreeps, enemyCreeps, combatEnemies)
    flagsNow = flags
    applyEffects(flags, myCreeps, enemyCreeps)
    accountScore(flags)


    // раненый (см. wounded): боец, потерявший всё оружие, остаётся в армии, пока жив хоть один ходячий лекарь —
    // лечение возвращает части (движок: части живы по сумме хитов, лечение идёт с хвоста тела: в матче 8 melee_1
    // из M5 с 416 хитами стал M8A8 к 199-му тику у одного лекаря); прежде он уходил «бегуном» за флагами и гиб
    val healersAlive = active.any { healerOnly(it) && canMove(it) }
    fun wounded(c: Creep) =  healersAlive && stripped(c) && bornCombatant(c)
    Memory.detachedIds.retainAll { id -> active.any { it.id == id && hasWeapon(it) && canMove(it) } }
    // ...и зачисленные КОМАНДИРОМ (v160, см. commandRace): его задание на захват действует так же, как detach —
    // иначе крип, посланный за флагом, остаётся бойцом строя и флага не берёт
    val takers = { id: String -> id in Memory.detachedIds || (id in Memory.cmdDetach) }
    val army = active.filter { (combatant(it) || wounded(it)) && !takers(it.id) }
    val runners = active.filter { (stripped(it) && !wounded(it)) || takers(it.id) }
    val immobile = active.filter { !canMove(it) }

    val walls = getObjectsByPrototype(StructureWall::class).filter { it.exists }
    val ramparts = getObjectsByPrototype(StructureRampart::class).filter { it.exists }
    val spawns = getObjectsByPrototype(StructureSpawn::class).filter { it.exists }
    val blocked: List<Position> = walls + ramparts.filter { it.my != true } + spawns + immobile
    val blockedForEnemy: List<Position> = walls + ramparts.filter { it.my != false } + spawns

    InfluenceMap.setProtectedCells(ramparts.filter { it.my == true }.mapTo(HashSet()) { it.key })
    InfluenceMap.setEnemyBlocked(blockedForEnemy.mapTo(HashSet()) { it.key })
    // ПОЛЯ ВЛИЯНИЯ (v204): строятся ОДИН раз за тик над одним множеством крипов — прежде commandFight
    // пересобирал ту же опасность пять раз за тик, по разу на замысел, и звал profileOf внутри цикла
    // по клеткам. Опасность клетки в раздаче читается отсюда (см. inc в commandFight).
    InfluenceMap.buildFields(active, enemyCreeps)
    // скауты врага: сколько их и сколько крипо-тиков они провели в дальности наших стволов. Знаменатель
    // большой при нулевом числителе — это и есть «мы их пропускаем», сказанное числом
    for (e in enemyCreeps) if (scoutFoe(e)) {
        scoutTicks.n++
        if (active.any { hasRanged(it) && getRange(it, e) <= RANGED_RANGE }) scoutReach.n++
    }
    // остовы: считаются ПОСЛЕ построения полей, чтобы потенциал тела уже был известен
    for (e in enemyCreeps) {
        val live = InfluenceMap.profileOf(e)
        val pot = InfluenceMap.potentialOf(e)
        val armable = pot.melee + pot.ranged > 0.0
        val disarmed = armable && live.melee + live.ranged <= 0.0
        if (disarmed) {
            disarmedFoe.add(e.id)
            hulkTicks.n++
            if (active.any { hasRanged(it) && getRange(it, e) <= RANGED_RANGE }) hulkInReach.n++
        } else if (e.id in disarmedFoe) {
            disarmedFoe.remove(e.id)
            if (live.melee + live.ranged > 0.0) hulkRevived.n++
        }
    }
    cpuMark("fields")
    val rawDanger = InfluenceMap.dangerCostMatrix(enemyCreeps, blocked)
    // флаг берётся тем, кто на него ВСТАЛ, — и любой шаг армии через чужой флаг был захватом: в матче 3 армия
    // на марше взяла D5 и второй A3 (occupant=none в журнале) и дралась при A×0.6 D×1.1 против врага, с
    // которого сама же сняла дебаффы. Не наш флаг — стена для всех, кроме назначенного на него
    val notOurs = flags.filter { !it.ours }
    val flagCells = notOurs.mapTo(HashSet()) { it.pos.key }
    val flagBlocked = notOurs.map { it.pos }
    val blockSig = blocked.sumOf { it.key + 1 } * 31 + flagBlocked.sumOf { it.key + 1 }
    // смена препятствий: поля не удаляются, а помечаются устаревшими — сверх бюджета (см. BFS_BUDGET) идут как есть
    if (blockSig != flowSig) { for (k in Memory.flowCacheTick.keys.toList()) Memory.flowCacheTick[k] = -1000; flowSig = blockSig }
    val dangerMatrix = rawDanger.clone()
    for (c in flagCells) dangerMatrix.set(c / 100, c % 100, 255)
    val passiveEnemy = combatEnemies.isNotEmpty() && combatEnemies.all { stationaryFor(it) >= PASSIVE_TICKS }

    DistanceMap.syncWalls(walls.size)
    DistanceMap.ensureBuilt(home, enemyHome)
    cpuMark("built")

    cpuMark("prep")
    val ourCentroid = centroidOf(army.ifEmpty { active }) ?: home
    val enemyCentroid = centroidOf(combatEnemies.ifEmpty { enemyCreeps })
    val ctx = Ctx(home, enemyHome, myCreeps, active, army, runners, enemyCreeps, combatEnemies, blocked, rawDanger, dangerMatrix, flags, flagCells, flagBlocked, passiveEnemy, ourCentroid, enemyCentroid, units,
        threats = combatEnemies.filter { threatening(it, enemyCreeps) })

    enemyArrivalTicks(ctx)
    // предзагрузка (v131b): потоки ко всем флагам считаются на первом тике, чей лимит 1000 мс, — второй тик (лимит 100 мс,
    // холодный JIT, 60–95 мс живьём) находит их в кэше вместо семи BFS
    if (getTicks() == 1) { for (f in ctx.flags) flowTo(ctx, f.pos); cpuMark("prefetch") }
    cpuMark("arrival")
    return BuildWorldOut(
        myCreeps = myCreeps,
        enemyCreeps = enemyCreeps,
        active = active,
        combatEnemies = combatEnemies,
        flags = flags,
        army = army,
        runners = runners,
        passiveEnemy = passiveEnemy,
        ourCentroid = ourCentroid,
        enemyCentroid = enemyCentroid,
        ctx = ctx,
    )
}

internal const val NEAR_RELEASE = 6

/** Столько тиков без сдвига — враг «стоит» и в стаи по «успеет дойти» не входит (см. packAt). */
internal const val STILL_TICKS = 20

/** Клетки в такой близости от боевого врага поле «в обход» считает стеной (см. flowAvoiding). */
internal const val AVOID_RANGE = RANGED_RANGE + 1

internal const val CROWD_COST = 3

internal const val DEBUG_MAP = true

internal const val FLOW_KEEP = 60   // тиков без обращения — запись кэша вычищается (иначе рост на клетках целей)

internal var greeted = false

/** Стартовые центры армий — «дома» сторон (спавнов нет): половины карты и точка поста. */
internal var homePos: Position? = null

internal var enemyHomePos: Position? = null

internal var corneredWas = false

/** Режим выживания (v223, см. USE_SURVIVAL) и его приборы: тиков в режиме / тиков, где мы ведём при его вооружённых /
 *  тиков режима в контакте (то есть там, где прежняя доктрина дралась бы). */
/** Третья постановка (v227, см. USE_ZERO_LEAD_BREAK): тиков подряд с мощью ноль при отрыве; режим разрыва контакта;
 *  приборы — тиков разрыва / тиков с мощью ноль при отрыве. */
internal var zeroLeadTicks = 0

internal var lastOurHits = -1                          // сумма хитов армии на прошлом тике (для noFireTicks)

internal var lastEnemyHitsTotal = -1

internal var lastEffectsKey = ""

internal var bfsCost = 0.0

internal var bfsThisTick = 0

internal var preyNearTicks = 0                         // тиков подряд с пикетом (см. STALL_PICKET) в досягаемости

internal var stallUntil = 0

/** Клетки в AVOID_RANGE от СТОЯЩИХ боевых врагов — поле «в обход» ведёт мимо лагеря, а не сквозь него.
 *  Идущий враг не обходится: обход идущего навстречу разводил строй с поста в стороны за тик до
 *  столкновения, и рывок врага, прежде отбитый к 212-му тику, стал разгромом (стенд rush). */
internal var avoidCellsCache: List<Position>? = null

internal var packTicksTick = -1

// ==================== приборы стадии: счётчик живёт у того, кто считает (v447, план архитектуры, 4.7 и этап 6) ====================
// Объявления перенесены из Instruments.kt дословно; Instruments их читает и печатает, текст строк прежний.

internal val groupSafeTicks = Gauges.counter("gsafe")

internal val flagSitOcc = Gauges.counter("sit")

internal val flagSitAll = Gauges.counter("sit", 1)

internal val holdKeptFight = Gauges.counter("hold", 3)

internal val scoutReach = Gauges.counter("scout", 1)

internal val scoutTicks = Gauges.counter("scout", 2)

internal val hulkTicks = Gauges.counter("hulkreach", 1)

internal val hulkInReach = Gauges.counter("hulkreach")

internal val hulkRevived = Gauges.counter("revived")

internal var kiteMassed = false                        // была ли его армия сомкнута в этом тике (v135, диагностика)

/** Пара к USE_MASS_BY_ARRIVAL (v226): тиков сигнала броска только по мере прихода / всех тиков сигнала / тиков, где мера
 *  прихода добавила «сомкнут» к мере формы. */
internal val rushByArrival = Gauges.counter("mrush")

internal val rushSignalAll = Gauges.counter("mrush", 1)

internal val massArrivalAdded = Gauges.counter("mrush", 2)

internal val zlbZero = Gauges.counter("zlb", 1)

internal val survLead = Gauges.counter("surv", 1)

internal var race100 = ""

internal var race200 = ""

internal val fightTicksNow = Gauges.counter("recall", 1)

internal val warmFight = Gauges.counter("warmfight")

internal val warmFightAll = Gauges.counter("warmfight", 1)

internal val kchaseAnn = Gauges.counter("kchase", 1)

// ==================== межтиковое состояние и константы стадии (до v454 — члены object PainAndGain; второй шаг архитектуры, этап 1) ====================

internal var rushStartDist = 0                         // расстояние между центрами на начало броска (v215)

internal var fightImminentTicks = 0                    // тиков подряд «бой близко» (см. USE_RUSH_VETO_SUSTAINED)

internal var noFireTicks = 0                           // тиков подряд враг с боем рядом и не снял с нас ни хита (см. USE_INTERCEPT)

internal var firstFightTick = 0                        // тик первого размена (exchangeLive); 0 — первый бой впереди (v281)

internal var fightMassedSeen = false                   // он хоть раз дрался с нами СОМКНУТЫМ (v434, см. USE_GATE_VS_FIGHTER)

internal var lastDistanceKeptTick = -1000              // последний тик, когда погоня не сближала (см. USE_DETACH, v57)

internal var lastHurtTick = 0                          // последний тик, когда враг снял с нас хиты (см. farmer в runArmy)

internal var firstNearTick = -1                        // первый тик с его вооружённым в ENGAGE_RANGE + RANGED_RANGE (v72: признаки фермера — от него)

internal var ourDamageTaken = 0                        // снято с нас за матч (см. USE_PUSH_LEDGER)

internal var enemyDamageTaken = 0                      // снято с него за матч

internal var ourScore = 0.0

internal var enemyScore = 0.0

/** Сколько тиков подряд отстаём по прогнозу (см. BEHIND_PATIENCE). */
internal var behindTicks = 0

internal var outmatchedTicks = 0                // сколько тиков подряд наша мощь ниже BREAK_OFF_RATIO от его (v185)

internal var flowSig = 0                            // подпись препятствий, при которой считан кэш

/** Окно размена для признака отхода — существующий срок «размен был недавно», а не новое число (v216). */
internal val LEDGER_WINDOW = STALL_TICKS

internal val DIRECTIONS = listOf(
    0 to 0, -1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1,
)

/** Те же направления в зеркальной системе координат (v286, см. mirrorTL). */
internal val DIRECTIONS_MIRROR = DIRECTIONS.map { (dx, dy) -> -dx to -dy }

// ТРИ ВЕЛИЧИНЫ, КОТОРЫЕ ПИШЕТ СТАДИЯ ВЫШЕ МИРА, А ОБЪЯВЛЕНЫ ЗДЕСЬ (v454): `pushing` и `retreatTarget` пишет стратег, `lastFireTick` —
// бой, но читают их и меры мира (`armyMeasures`: застой марша, «у точки отхода», «размен был недавно») — РАНЬШЕ записи этого
// тика, то есть значение ПРОШЛОГО тика. Объявление стоит не выше нижнего читателя: на верху файла-писателя мир импортировал
// бы стратега и бой — ребро вверх, которое гейт графа не пропустит (levels.txt). Запись направлена вниз, это разрешено.
/** ДОБИТЬ по перевесу (не по контакту) — только к нему применяется гистерезис PUSH_RELEASE_RATIO. */
internal var pushing = false

/** Точка отхода — одна на весь отход (см. retreatPoint). */
internal var retreatTarget: Position? = null

internal var lastFireTick = -1000                      // последний тик, когда кто-то из наших бил или стрелял (см. USE_COLD_CONTACT)

// ==================== приборы стадии, бывшие членами object PainAndGain (v455, второй шаг архитектуры, этап 2) ====================

/** Пара «крипов отозвано в кулак / тиков боя» (v215). */
internal val recalled = Gauges.counter("recall")

internal val kchaseTicks = Gauges.counter("kchase")

/** Погоня за кайтером (v221, только прибор, см. kiteChaseNow): тиков односторонней погони в ANNIHILATE /
 *  тиков ANNIHILATE; отказов безфлагового броска в такой погоне / всех отказов безфлагового броска. */
internal var kiteChaseSeen = false

// ==================== приборы стадии, бывшие членами object PainAndGain (v455, второй шаг архитектуры, этап 2) ====================

internal var lastReachTick = -1                        // последний тик с его вооружённым в ENGAGE_RANGE от наших

internal var bfsMaxCost = 0.0

internal var bfsMaxTick = 0
