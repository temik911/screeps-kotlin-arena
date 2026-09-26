package season4.escortrun

import screeps.api.ATTACK
import screeps.api.ATTACK_POWER
import screeps.api.BODYPART_COST
import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.CREEP_SPAWN_TIME
import screeps.api.Creep
import screeps.api.HEAL
import screeps.api.HEAL_POWER
import screeps.api.MOVE
import screeps.api.RANGED_ATTACK
import screeps.api.RANGED_ATTACK_POWER
import screeps.api.TOUGH
import screeps.api.WORK
import screeps.api.value

/**
 * Тела: цена, вес, период, роль по телу и дуэль «наше тело против их группы». Всё — чистые функции от частей, без
 * состояния бота: роль крипа читается из тела (id из spawnCreep в Арене равен undefined — матч 6a9b37c8, v9).
 */
internal object Bodies {

    fun cost(part: BodyPartType): Int = (BODYPART_COST.asDynamic()[part.value] as? Int) ?: 0

    fun cost(body: Array<BodyPartType>): Int = body.sumOf { cost(it) }

    fun spawnTicks(body: Array<BodyPartType>): Int = body.size * CREEP_SPAWN_TIME

    fun moves(n: Int): Array<BodyPartType> = Array(n) { MOVE }

    /** Строка тела «M10», «M2A2» — по типам в порядке первого появления. */
    fun summary(body: Array<BodyPartType>): String {
        val order = ArrayList<BodyPartType>()
        val count = HashMap<String, Int>()
        for (p in body) {
            if (count[p.value] == null) order.add(p)
            count[p.value] = (count[p.value] ?: 0) + 1
        }
        return order.joinToString("") { letter(it) + count[it.value] }
    }

    fun summaryOf(c: Creep): String = summary(Array(c.body.size) { c.body[it].type })

    fun letter(t: BodyPartType): String = when (t) {
        MOVE -> "M"; ATTACK -> "A"; RANGED_ATTACK -> "R"; HEAL -> "H"; WORK -> "W"; CARRY -> "C"; TOUGH -> "T"; else -> "?"
    }

    /** Вес для усталости: все части, кроме MOVE и CARRY, включая МЁРТВЫЕ (movement.js:237 не смотрит на hits). */
    fun weight(c: Creep): Int = c.body.count { it.type != MOVE && it.type != CARRY }

    fun liveMoves(c: Creep): Int = c.body.count { it.type == MOVE && it.hits > 0 }

    fun live(c: Creep, t: BodyPartType): Int = c.body.count { it.type == t && it.hits > 0 }

    fun has(c: Creep, t: BodyPartType): Boolean = c.body.any { it.type == t }

    /** Период на клетке: ceil(вес × ставка / (2 × MOVE)), ставка 2 равнина / 10 болото; без MOVE — никогда. */
    fun period(weight: Int, moves: Int, swamp: Boolean): Int {
        if (weight == 0) return 1
        if (moves <= 0) return Int.MAX_VALUE / 4
        val rate = if (swamp) 10 else 2
        return maxOf(1, (weight * rate + 2 * moves - 1) / (2 * moves))
    }

    fun isPureMove(c: Creep): Boolean = c.body.isNotEmpty() && c.body.all { it.type == MOVE }

    /** Тягач — тело из одних MOVE не короче PULLER_MIN_MOVE; короче — разведчик (хранитель или блокировщик флага). */
    fun isPuller(c: Creep, minMove: Int): Boolean = isPureMove(c) && c.body.size >= minMove

    fun isScout(c: Creep, minMove: Int): Boolean = isPureMove(c) && c.body.size < minMove

    fun isArmed(c: Creep): Boolean = c.body.any { (it.type == ATTACK || it.type == RANGED_ATTACK) && it.hits > 0 }

    fun wasArmed(c: Creep): Boolean = c.body.any { it.type == ATTACK || it.type == RANGED_ATTACK }

    fun isHealer(c: Creep): Boolean = !wasArmed(c) && c.body.any { it.type == HEAL }

    fun isWorker(c: Creep): Boolean = c.body.any { it.type == WORK }

    fun isHauler(c: Creep): Boolean = !isWorker(c) && !wasArmed(c) && c.body.any { it.type == CARRY }

    fun meleeDps(c: Creep): Int = live(c, ATTACK) * ATTACK_POWER
    fun rangedDps(c: Creep): Int = live(c, RANGED_ATTACK) * RANGED_ATTACK_POWER
    fun healPower(c: Creep): Int = live(c, HEAL) * HEAL_POWER

    // ---------- дуэль ----------

    /** Часть тела в дуэли: тип и хиты. Урон снимается С ГОЛОВЫ тела, лечение возвращает с хвоста (_recalc-body.js). */
    class Unit(val parts: Array<BodyPartType>, val hits: IntArray) {
        fun alive() = hits.any { it > 0 }
        fun total() = hits.sum()
        fun count(t: BodyPartType) = parts.indices.count { parts[it] == t && hits[it] > 0 }
        fun dps(melee: Boolean) = count(ATTACK) * ATTACK_POWER * (if (melee) 1 else 0) + count(RANGED_ATTACK) * RANGED_ATTACK_POWER
        fun heal() = count(HEAL) * HEAL_POWER
        fun damage(d: Int) {
            var left = d
            for (i in parts.indices) { if (left <= 0) break; val take = minOf(hits[i], left); hits[i] -= take; left -= take }
        }
        fun healUp(h: Int) {
            var left = h
            for (i in parts.indices.reversed()) { if (left <= 0) break; val add = minOf(100 - hits[i], left); hits[i] += add; left -= add }
        }
    }

    fun unitOf(body: Array<BodyPartType>) = Unit(body.copyOf(), IntArray(body.size) { 100 })
    fun unitOf(c: Creep) = Unit(Array(c.body.size) { c.body[it].type }, IntArray(c.body.size) { c.body[it].hits })

    /**
     * Дуэль нашего тела против группы врагов «вплотную» (мили достают, стрелки достают): каждый тик обе стороны бьют
     * всей живой мощью, мы — по слабейшему живому врагу, они — по нам; лечение каждой стороны — по себе. Возвращает
     * тиков до победы (враги мертвы) или -1, если первыми гибнем мы (или не кончается за horizon). Это грубая мера —
     * позиционной игры нет, — и нужна она для выбора ТЕЛА: какое самое дешёвое тело побеждает этих врагов.
     */
    fun duel(ours: Unit, theirs: List<Unit>, horizon: Int = 200): Int {
        val them = theirs.filter { it.alive() }.toMutableList()
        if (them.isEmpty()) return 0
        for (t in 1..horizon) {
            val ourDmg = ours.dps(true)
            val theirDmg = them.sumOf { it.dps(true) }
            val target = them.minByOrNull { it.total() }!!
            target.damage(ourDmg)
            ours.damage(theirDmg)
            for (u in them) if (u.alive()) u.healUp(u.heal())
            if (ours.alive()) ours.healUp(ours.heal())
            them.removeAll { !it.alive() }
            // взаимная гибель — не победа: M1A1 против M1A1 «выигрывался», и держание дома снималось ради боя в поле,
            // где наш боец умирал вместе с их перехватчиком (stachu3478#1, 6ab84583)
            if (!ours.alive()) return -1
            if (them.isEmpty()) return t
        }
        return -1
    }
}
