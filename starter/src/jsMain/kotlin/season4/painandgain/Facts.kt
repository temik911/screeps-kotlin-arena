package season4.painandgain

import screeps.api.ATTACK
import screeps.api.Creep
import screeps.api.get
import screeps.api.RESOURCE_ENERGY
import screeps.api.CARRY_CAPACITY
import screeps.api.CARRY
import screeps.api.HEAL
import screeps.api.MOVE
import screeps.api.Position
import screeps.api.RANGED_ATTACK

/**
 * СЛОВАРЬ ФАКТОВ (v442, docs/pain-and-gain-architecture.md, раздел 4.1 и этап 1). Факт о крипе считается один раз за тик
 * и читается по имени; определение у факта ОДНО — здесь. До этого «чистый мили», «лекарь», «ключ клетки» выписывались
 * инлайном в каждой ветке, и написания разошлись. Написание, сведённое сюда, не возвращается инлайном — это держит
 * `tools/stub/painandgain/lint.py`, строка `lint` гейта.
 *
 * Имена классов — `CreepFacts` (факты одного крипа) и `TickFacts` (таблица тика) с v448: план звал их `Unit` / `Units`,
 * и `Unit` заслонял `kotlin.Unit` во всём пакете — тип «ничего» приходилось писать полностью (`() -> kotlin.Unit`).
 *
 * Кэш корректен, потому что тело крипа внутри нашего `loop()` не меняется: единственный писатель API — `Executor` в
 * конце тика, а `Forecast.simulate` считает на своих профилях и `creep.body` не правит.
 */
internal class CreepFacts(val creep: Creep) {
    /** Ключ клетки, на которой крип стоит в этом тике. */
    val key: Int = creep.x * 100 + creep.y

    /** Живая часть ATTACK / RANGED_ATTACK / HEAL / MOVE (`hits > 0`). */
    val liveMelee: Boolean
    val liveRanged: Boolean
    val liveHeal: Boolean
    val liveMove: Boolean

    /** Часть ATTACK есть в теле, живая или нет: «рождён мили». Для мили с выбитым оружием истинно. */
    val bornMelee: Boolean
    /** Тело несёт ATTACK или RANGED_ATTACK, живую или нет: «рождён с оружием». */
    val bornArmed: Boolean
    /** Тело несёт ATTACK, RANGED_ATTACK или HEAL, живую или нет: «рождён бойцом» (бегун — чистый MOVE — им не бывает). */
    val bornCombatant: Boolean

    // порядок текста = порядок инициализации: всё, что считает проход по телу, объявлено ВЫШЕ него, производное — ниже
    init {
        var lm = false; var lr = false; var lh = false; var lv = false; var bm = false; var br = false; var bh = false
        for (p in creep.body) {
            val alive = p.hits > 0
            when (p.type) {
                ATTACK -> { bm = true; if (alive) lm = true }
                RANGED_ATTACK -> { br = true; if (alive) lr = true }
                HEAL -> { bh = true; if (alive) lh = true }
                MOVE -> if (alive) lv = true
                else -> {}
            }
        }
        liveMelee = lm; liveRanged = lr; liveHeal = lh; liveMove = lv; bornMelee = bm
        bornArmed = bm || br; bornCombatant = bm || br || bh
    }

    /** Есть живое оружие — ближнее или дальнее. */
    val armed: Boolean = liveMelee || liveRanged

    /** Лекарь: живого оружия нет, живая HEAL есть. */
    val healerOnly: Boolean = !armed && liveHeal
    /** Раздет: ни живого оружия, ни живой HEAL. Одно имя на то, что тактик звал `wounded`, а командир — `stripped`. */
    val stripped: Boolean = !armed && !liveHeal
    /** В строю: живое оружие или живая HEAL. */
    val combatant: Boolean = armed || liveHeal

    // «ЧИСТЫЙ МИЛИ» — ДВА РАЗНЫХ ФАКТА, и они не сведены намеренно (план, правило 3.4, решение оператора 9): до v442 это
    // были написания А `isMelee(x) && !hasRanged(x)` (23 места) и Б `hasWeapon(x) && hasMelee(x) && !hasRanged(x)` / В
    // `hasMelee(x) && !hasRanged(x)` (5 мест). Расходятся они на мили с ВЫБИТЫМ оружием: для него А истинно, Б ложно.
    // Таблица «место → написание → что это значит» — docs/pain-and-gain.md, абзац v442; свести их — решение о поведении.
    /** Написание А: рождён мили и без живого дальнего. Истинно и для мили, у которого выбиты все ATTACK. */
    val meleeOnlyBorn: Boolean = bornMelee && !liveRanged
    /** Написания Б и В: живая ATTACK и без живого дальнего. Для мили с выбитым оружием ложно. */
    val meleeOnlyLive: Boolean = liveMelee && !liveRanged
}

/**
 * Факты всех крипов тика. Строится ЗАНОВО каждый тик в `buildWorld` и держится в `Ctx`: таблица, которая переживает тик
 * в объекте-синглтоне вне списка владельцев `AbortRepair`, повторяет дефект v222 (оборванный тик оставляет хеш-таблицу
 * посреди перестройки, и следующая вставка виснет). Новый объект на тик оборванного тика не наследует.
 */
internal class TickFacts(creeps: List<Creep>) {
    private val byId = HashMap<String, CreepFacts>()

    init { for (c in creeps) byId[c.id] = CreepFacts(c) }

    /** Факты крипа; крип, которого в снимке тика не было, считается на месте — тем же определением. */
    fun of(creep: Creep): CreepFacts = byId[creep.id] ?: CreepFacts(creep).also { byId[creep.id] = it }
}

/** Ключ клетки: `x * 100 + y`. `inline` даёт тот же скомпилированный код, что и написание инлайном. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun key(x: Int, y: Int): Int = x * 100 + y

/** Ключ клетки, на которой стоит объект (крип, флаг, точка пути). */
internal inline val Position.key: Int get() = x * 100 + y

// ==================== обёртки над фактами тика (до v446 жили в World.kt; читают таблицу тика `unitsNow` у получателя) ====================

/** Факты крипа в этом тике (см. Facts.kt). Обёртки ниже — тонкие: определение каждого факта одно, в `CreepFacts`. */
internal fun PainAndGain.unitOf(creep: Creep): CreepFacts = unitsNow.of(creep)

internal fun PainAndGain.canMove(creep: Creep) = unitOf(creep).liveMove

internal fun PainAndGain.hasMelee(creep: Creep) = unitOf(creep).liveMelee

internal fun PainAndGain.isMelee(creep: Creep) = unitOf(creep).bornMelee

internal fun PainAndGain.hasRanged(creep: Creep) = unitOf(creep).liveRanged

internal fun PainAndGain.hasHeal(creep: Creep) = unitOf(creep).liveHeal

internal fun PainAndGain.hasWeapon(creep: Creep) = unitOf(creep).armed

/** Лекарь: без живого оружия, с живой HEAL. */
internal fun PainAndGain.healerOnly(creep: Creep) = unitOf(creep).healerOnly

/** Раздет: ни живого оружия, ни живой HEAL (у тактика это звалось `wounded`, у командира `stripped`). */
internal fun PainAndGain.stripped(creep: Creep) = unitOf(creep).stripped

/** В строю: живое оружие или живая HEAL. */
internal fun PainAndGain.combatant(creep: Creep) = unitOf(creep).combatant

/** Рождён с оружием / рождён бойцом: часть в теле есть, живая или нет. */
internal fun PainAndGain.bornArmed(creep: Creep) = unitOf(creep).bornArmed
internal fun PainAndGain.bornCombatant(creep: Creep) = unitOf(creep).bornCombatant

/** «Чистый мили», написание А — рождён мили: истинно и с выбитым оружием (см. CreepFacts.meleeOnlyBorn). */
internal fun PainAndGain.meleeOnlyBorn(creep: Creep) = unitOf(creep).meleeOnlyBorn

/** «Чистый мили», написания Б и В — с живой ATTACK (см. CreepFacts.meleeOnlyLive). */
internal fun PainAndGain.meleeOnlyLive(creep: Creep) = unitOf(creep).meleeOnlyLive

// ==================== тело и скорость (до v446 жили в World.kt; модель мощи уровнем ниже мира читала отсюда swampPeriod) ====================

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
