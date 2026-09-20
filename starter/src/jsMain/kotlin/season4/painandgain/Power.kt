package season4.painandgain

import kotlin.math.abs
import screeps.api.ATTACK
import screeps.api.Creep
import screeps.api.EFF_DAMAGE_TAKEN_MODIFIER
import screeps.api.HEAL
import screeps.api.MOVE
import screeps.api.Position
import screeps.api.RANGED_ATTACK
import screeps.api.TOUGH
import screeps.api.get
import screeps.api.getRange
import screeps.api.ATTACK_POWER
import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.CARRY_CAPACITY
import screeps.api.CostMatrix
import screeps.api.EFF_ATTACK_MODIFIER
import screeps.api.EFF_HEAL_MODIFIER
import screeps.api.EFF_RANGED_ATTACK_MODIFIER
import screeps.api.HEAL_POWER
import screeps.api.RANGED_ATTACK_POWER
import screeps.api.RANGED_HEAL_POWER
import screeps.api.RESOURCE_ENERGY
import screeps.api.SearchGoal
import screeps.api.SearchPathOptions
import screeps.api.TERRAIN_SWAMP
import screeps.api.TERRAIN_WALL
import screeps.api.WORK
import screeps.api.arenaInfo
import screeps.api.getObjectsByPrototype
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
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * МОДЕЛЬ МОЩИ И РАЗМЕНА (v446, план архитектуры, 4.6 и этап 5) — уровень 1: чистые функции над телами и профилями (`ourPowerOf`,
 * `enemyPowerOf`, `fightTicks`, `fightCost`, Ланчестер). До этапа 5 жили в `Forecast.kt` рядом с прокатом, а читали их меры
 * мира — ребро World → Forecast, вверх. Прокат `simulate` остался в `Forecast.kt`; модель перенесена дословно.
 */

/** Цена боя: хиты, которые снимут с нас, пока враги умирают по одному под нашим огнём (лекари первыми,
 *  их лечение вычитается); урон врага — с НАШИМ множителем входящего, наш — с ЕГО. */
internal fun fightCost(enemies: List<Creep>, ours: List<Creep>): Double {
    val ourDps = ours.sumOf { effectiveDps(it, enemies) }
    if (ourDps <= 0.0) return Double.MAX_VALUE
    val order = enemies.sortedWith(compareByDescending<Creep> { InfluenceMap.profileOf(it).heal }.thenBy { it.hits })
    val ourTaken = ours.maxOfOrNull { InfluenceMap.takenOf(it) } ?: 1.0
    var remaining = enemies.sumOf { effectiveDps(it, ours) } * ourTaken
    var heal = enemies.sumOf { InfluenceMap.profileOf(it).heal }
    var damage = 0.0
    for (e in order) {
        val net = ourDps * InfluenceMap.takenOf(e) - heal
        if (net <= 0.0) return Double.MAX_VALUE
        damage += remaining * e.hits / net
        remaining -= effectiveDps(e, ours) * ourTaken
        heal -= InfluenceMap.profileOf(e).heal
    }
    return damage
}

/** Тики боя: пока враги умирают по одному под нашим огнём (порядок и лечение — как в fightCost; удар мили — с долей
 *  смежности, как в мощи); MAX, если чистый урон не положителен. */
internal fun fightTicks(enemies: List<Creep>, ours: List<Creep>): Int {
    val meleeK = 1.0
    val ourDps = ours.sumOf { effectiveDps(it, enemies, 1.0, meleeK) }
    if (ourDps <= 0.0) return Int.MAX_VALUE / 2
    val order = enemies.sortedWith(compareByDescending<Creep> { InfluenceMap.profileOf(it).heal }.thenBy { it.hits })
    var heal = enemies.sumOf { InfluenceMap.profileOf(it).heal }
    var ticks = 0.0
    for (e in order) {
        val net = ourDps * InfluenceMap.takenOf(e) - heal
        if (net <= 0.0) return Int.MAX_VALUE / 2
        ticks += e.hits / net
        heal -= InfluenceMap.profileOf(e).heal
    }
    return ticks.toInt() + 1
}

/**
 * МОЩЬ НИЖЕ ЛЕЧЕНИЯ — ВЕЛИЧИНА, А НЕ НОЛЬ (v417). Прежде здесь стоял зажим `maxOf(0.0, dps - enemyHeal)`, и он
 * терял ВСЮ информацию ниже точки, где его лечение перекрывает наш урон: «мы не добиваем на десять» и «нашей армии
 * нет» давали одно число — ноль. Цена измерена разбором 20 рейтинговых матчей: в четырёх поражениях-разгромах мы
 * провели в состоянии нулевой мощи 14,3-48,0 % тиков против максимум 2,9 % в тринадцати победах (p = 0,007), а
 * чистая пара на одном сопернике (`Coldkimchi#1`) дала за сто тиков 108 нанесённого урона против 4 093 полученного
 * при ЦЕЛОЙ армии и целых стволах: гейты вида «наша мощь >= его × коэффициент» ложны при любом коэффициенте, когда
 * слева ноль, и бот перестаёт давить, концентрировать и вступать в бой. Теперь недобор до лечения возвращается
 * отрицательной величиной той же размерности — сравнения сохраняют градиент, а знак по-прежнему говорит «этого не
 * пробить». ⚠️ Разрыв контакта как лечение симптома ОТВЕРГНУТ живым замером (v415/v416: подключение записанной, но
 * мёртвой ветки KITE дало 6-26 против базы 5-27 на `Coldkimchi#1`) — предмет в самой мере, а не в реакции на неё.
 */
internal fun lanchester(dps: Double, enemyHeal: Double, hits: Double): Double {
    val net = dps - enemyHeal
    val body = maxOf(0.0, hits)
    return if (net >= 0.0) sqrt(net * body) else -sqrt(-net * body)
}

/** Доля удара мили, которая ДОЙДЁТ: кайт-дисконт, только если противники сплошь стрелки, никто не
 *  прижат вплотную и мили медленнее каждого из них на болоте. */
internal fun meleeFactor(unit: Creep, opponents: List<Creep>): Double {
    if (opponents.any { hasMelee(it) || getRange(unit, it) <= MELEE_KEEP_RANGE }) return 1.0
    val ranged = opponents.filter { hasRanged(it) }
    if (ranged.isEmpty()) return 1.0
    val mine = swampPeriod(unit)
    return if (ranged.any { swampPeriod(it) > mine }) 1.0 else MELEE_KITE_DISCOUNT
}

/** Действенный урон крипа в тик против группы (с его эффектами): стрельба целиком, мили — по meleeFactor. */

internal fun effectiveDps(unit: Creep, opponents: List<Creep>, rangedK: Double = 1.0, meleeK: Double = 1.0): Double {
    val p = InfluenceMap.profileOf(unit)
    val full = p.ranged * rangedK + p.melee * meleeK * meleeFactor(unit, opponents)
    return full
    // МОЩЬ СЧИТАЕТСЯ ПО ТЕМ, КТО ДОСТАЁТ (v140): прежде крип шёл в силу полным профилем, даже стоя вне дальности, и
    // мера боя мерила ПОТЕНЦИАЛ, а не участие. Живьём на первом контакте `reach=2/5` — цель достают двое наших
    // стрелков из пяти, а он бьёт всеми двенадцатью, и по линейной мере это выглядит паритетом 4 087:4 087.
    // Ланчестер объясняет цену ошибки: сила идёт как КВАДРАТ числа стреляющих, поэтому вдвое меньшее участие — это
    // вчетверо меньшая армия. Тот, кто дойдёт за POWER_REACH_TICKS тиков, считается с половинным весом
    val d = opponents.minOf { getRange(unit, it) }
    val reach = if (hasRanged(unit)) RANGED_RANGE else MELEE_KEEP_RANGE
    // мягкая доля: выбрасывать дальних целиком нельзя — мера мощи держит ещё и захват флагов, и постуры на марше,
    // и жёсткий срез уронил гейт до 124/131 (roost, четыре scatter, camp). Тот, кто достаёт, — полный вес; кто дойдёт
    // за POWER_REACH_TICKS — половина; остальные — четверть
    return when {
        d <= reach -> full
        d <= reach + POWER_REACH_TICKS -> full * 0.5
        else -> full * 0.25
    }
}

/** Хиты в счёте мощи: по доле удара, которая дойдёт (кайтимая мили в бою не участвует; лекарь и
 *  безоружный — полностью), с поправкой на множитель входящего урона (флаг уязвимости). */
/**
 * БОЕВЫЕ ХИТЫ (v428): сколько урона надо снять, чтобы крип перестал БИТЬ, а не чтобы он умер. Движок снимает части
 * тела ПО ПОРЯДКУ, спереди назад, поэтому хвост из MOVE за последней боевой частью мощи стороне не добавляет: он
 * дотягивает труп, который уже не стреляет. Цена пропуска измерена в самих телах этой арены — у мили ATTACK стоит
 * ВПЕРЕДИ (`a8m8`), и он разоружается на hits <= 800 из 1600, то есть мера завышала его вдвое; у лекаря MOVE
 * впереди, HEAL сзади, и он лечит почти до смерти, то есть его 1200 засчитывались верно. Разбор шести матчей против
 * MetalicaX подтверждает это фактом: наши мили разоружаются на контакте+4…+17 в КАЖДОМ матче, включая победы, —
 * то есть половина их хитов перестаёт быть боевой в первые же тики размена.
 */
internal fun fightingHits(c: Creep): Double {
    if (!USE_FIGHTING_HITS) return c.hits.toDouble()
    val body = c.body
    var last = -1
    for (i in body.indices) {
        val t = body[i].type
        if (body[i].hits > 0 && (t == ATTACK || t == RANGED_ATTACK || t == HEAL)) last = i
    }
    if (last < 0) return 0.0
    var sum = 0.0
    for (i in 0..last) sum += body[i].hits.toDouble()
    return sum
}

internal fun weightedHits(unit: Creep, opponents: List<Creep>, hitsK: Double = 1.0): Double {
    val p = InfluenceMap.profileOf(unit)
    val raw = p.ranged + p.melee
    val taken = InfluenceMap.takenOf(unit).coerceAtLeast(0.01)
    // раненый (оружие или лечение в теле мертво) хитов в счёт не даёт: он не в строю и огня на себя не берёт
    if (raw <= 0.0 && p.heal <= 0.0 && bornCombatant(unit)) return 0.0
    val share = if (raw <= 0.0) 1.0 else effectiveDps(unit, opponents) / raw
    return fightingHits(unit) * share * hitsK / taken
}

internal fun hypoMods(type: String, k: Double) = HypoMods(
    ranged = if (type == EFF_RANGED_ATTACK_MODIFIER) k else 1.0,
    melee = if (type == EFF_ATTACK_MODIFIER) k else 1.0,
    heal = if (type == EFF_HEAL_MODIFIER) k else 1.0,
    hits = if (type == EFF_DAMAGE_TAKEN_MODIFIER) 1.0 / k else 1.0,
)

/** Мощь стороны по Ланчестеру против группы противника: √(её урон − его лечение) × её хиты; mods — её
 *  гипотетические множители, oppMods — множитель лечения противника. */
internal fun powerOf(side: List<Creep>, opp: List<Creep>, mods: HypoMods, oppMods: HypoMods): Double {
    // удар мили — с долей смежности (v103, USE_MELEE_ADJACENCY_SHARE); хиты (weightedHits) без неё
    val his = side.firstOrNull()?.my == false
    // доля касаний — последняя ИЗМЕРЕННАЯ за полное окно (v433, USE_TOUCH_SHARE_LAST), а не единица, которую окно
    // показывает вне контакта: ATTACK бьёт на 1, и разрыв контакта не делает мили досягающим
    // ...одна на тик для всех читателей (v474, дефект 12): пишет `RememberTick`, а не стойка посреди тика
    val share = if (USE_TOUCH_SHARE_LAST) (if (his) Prev.hisTouchShareLast else Prev.touchShareLast) else (if (his) Prev.hisTouchShare else Prev.touchShare)
    val meleeK = mods.melee * share
    val dps = side.sumOf { effectiveDps(it, opp, mods.ranged, meleeK) }
    val heal = opp.sumOf { InfluenceMap.profileOf(it).heal } * oppMods.heal
    return lanchester(dps, heal, side.sumOf { weightedHits(it, opp, mods.hits) })
}

/** Мощь по УЧАСТИЮ: считает только тех, кто достаёт цель, и решает «вступать ли в бой здесь и сейчас» (v140). */
internal fun ourPowerReach(ours: List<Creep>, theirs: List<Creep>): Double {
    val v = powerOf(ours, theirs, NO_MODS, NO_MODS)
    return v
}

internal fun enemyPowerReach(theirs: List<Creep>, ours: List<Creep>): Double {
    val v = powerOf(theirs, ours, NO_MODS, NO_MODS)
    return v
}

/** НАША мощь против группы врага (текущие эффекты). */
internal fun ourPowerOf(ours: List<Creep>, theirs: List<Creep>): Double = powerOf(ours, theirs, NO_MODS, NO_MODS)

/** Мощь врага против нашей группы (текущие эффекты). */
internal fun enemyPowerOf(theirs: List<Creep>, ours: List<Creep>): Double = powerOf(theirs, ours, NO_MODS, NO_MODS)

internal const val POWER_REACH_TICKS = 2

internal class Shooter(val cell: Int, val ranged: Double, val melee: Double)

/** Гипотетические множители стороны сверх текущих эффектов (маргинальная цена флага, см. powerAfter). */
internal class HypoMods(val ranged: Double = 1.0, val melee: Double = 1.0, val heal: Double = 1.0, val hits: Double = 1.0)

// ==================== межтиковое состояние и константы стадии (до v454 — члены object PainAndGain; второй шаг архитектуры, этап 1) ====================

internal val NO_MODS = HypoMods()

// Доля касания за полное окно — вход модели мощи — с v474 живёт в `Prev` (Memory.kt): вчерашнее явно, одно значение на тик.
