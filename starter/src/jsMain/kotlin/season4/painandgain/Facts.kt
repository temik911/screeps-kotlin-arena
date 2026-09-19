package season4.painandgain

import screeps.api.ATTACK
import screeps.api.Creep
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
 * ⚠️ Имя класса — из плана, и оно заслоняет `kotlin.Unit` во всём пакете: тип «ничего» здесь пишется `kotlin.Unit`
 * (так в `Forecast.kt`: `() -> kotlin.Unit`). Ошибка громкая — компилятор откажет, молча смысл не меняется.
 *
 * Кэш корректен, потому что тело крипа внутри нашего `loop()` не меняется: единственный писатель API — `Executor` в
 * конце тика, а `Forecast.simulate` считает на своих профилях и `creep.body` не правит.
 */
internal class Unit(val creep: Creep) {
    /** Ключ клетки, на которой крип стоит в этом тике. */
    val key: Int = creep.x * 100 + creep.y

    /** Живая часть ATTACK / RANGED_ATTACK / HEAL / MOVE (`hits > 0`). */
    val liveMelee: Boolean
    val liveRanged: Boolean
    val liveHeal: Boolean
    val liveMove: Boolean

    /** Часть ATTACK есть в теле, живая или нет: «рождён мили». Для мили с выбитым оружием истинно. */
    val bornMelee: Boolean

    init {
        var lm = false; var lr = false; var lh = false; var lv = false; var bm = false
        for (p in creep.body) {
            val alive = p.hits > 0
            when (p.type) {
                ATTACK -> { bm = true; if (alive) lm = true }
                RANGED_ATTACK -> if (alive) lr = true
                HEAL -> if (alive) lh = true
                MOVE -> if (alive) lv = true
                else -> {}
            }
        }
        liveMelee = lm; liveRanged = lr; liveHeal = lh; liveMove = lv; bornMelee = bm
    }

    /** Есть живое оружие — ближнее или дальнее. */
    val armed: Boolean = liveMelee || liveRanged

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
internal class Units(creeps: List<Creep>) {
    private val byId = HashMap<String, Unit>()

    init { for (c in creeps) byId[c.id] = Unit(c) }

    /** Факты крипа; крип, которого в снимке тика не было, считается на месте — тем же определением. */
    fun of(creep: Creep): Unit = byId[creep.id] ?: Unit(creep).also { byId[creep.id] = it }
}

/** Ключ клетки: `x * 100 + y`. `inline` даёт тот же скомпилированный код, что и написание инлайном. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun key(x: Int, y: Int): Int = x * 100 + y

/** Ключ клетки, на которой стоит объект (крип, флаг, точка пути). */
internal inline val Position.key: Int get() = x * 100 + y
