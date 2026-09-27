package season4.escortrunadvanced

import screeps.api.ATTACK
import screeps.api.ATTACK_POWER
import screeps.api.BODYPART_COST
import screeps.api.BodyPartType
import screeps.api.CARRY
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
 * Bodies: cost, weight, period, the role a body stands for, and a group-against-group fight. Pure functions of the
 * parts — a creep's role is read from its body, since `spawnCreep` hands back no id in the Arena (basic Escort Run,
 * match 6a9b37c8).
 */
internal object Bodies {

    fun cost(part: BodyPartType): Int = (BODYPART_COST.asDynamic()[part.value] as? Int) ?: 0

    fun cost(body: Array<BodyPartType>): Int = body.sumOf { cost(it) }

    fun letter(t: BodyPartType): String = when (t) {
        MOVE -> "M"; ATTACK -> "A"; RANGED_ATTACK -> "R"; HEAL -> "H"; WORK -> "W"; CARRY -> "C"; TOUGH -> "T"; else -> "?"
    }

    /** `M5A5` — counts by type in order of first appearance. */
    fun summary(body: Array<BodyPartType>): String {
        val order = ArrayList<String>()
        val count = HashMap<String, Int>()
        for (p in body) {
            val l = letter(p)
            if (count[l] == null) order.add(l)
            count[l] = (count[l] ?: 0) + 1
        }
        return order.joinToString("") { it + count[it] }
    }

    fun summaryOf(c: Creep): String = summary(Array(c.body.size) { c.body[it].type })

    /** Weight for fatigue: every part but MOVE and CARRY, the DEAD ones included (movement.js:237 never looks at hits). */
    fun weight(c: Creep): Int = c.body.count { it.type != MOVE && it.type != CARRY }

    fun live(c: Creep, t: BodyPartType): Int = c.body.count { it.type == t && it.hits > 0 }

    fun has(c: Creep, t: BodyPartType): Boolean = c.body.any { it.type == t }

    fun liveMoves(c: Creep): Int = live(c, MOVE)

    /** Ticks per cell: ceil(weight × rate / (2 × MOVE)), rate 2 on plain and 10 on swamp; no MOVE — never. */
    fun period(weight: Int, moves: Int, swamp: Boolean): Int {
        if (weight == 0) return 1
        if (moves <= 0) return Int.MAX_VALUE / 4
        val rate = if (swamp) 10 else 2
        return maxOf(1, (weight * rate + 2 * moves - 1) / (2 * moves))
    }

    fun period(c: Creep, swamp: Boolean): Int = period(weight(c), liveMoves(c), swamp)

    /** How many plain cells one swamp cell costs this creep — the swamp price of its flow field. */
    fun swampCost(c: Creep): Int = maxOf(1, period(c, true) / maxOf(1, period(c, false)))

    fun isWorker(c: Creep): Boolean = has(c, WORK)

    fun isArmed(c: Creep): Boolean = c.body.any { (it.type == ATTACK || it.type == RANGED_ATTACK || it.type == HEAL) && it.hits > 0 }

    fun wasArmed(c: Creep): Boolean = c.body.any { it.type == ATTACK || it.type == RANGED_ATTACK || it.type == HEAL }

    fun isHauler(c: Creep): Boolean = !isWorker(c) && !wasArmed(c) && has(c, CARRY)

    fun isPureMove(c: Creep): Boolean = c.body.isNotEmpty() && c.body.all { it.type == MOVE }

    fun isMelee(c: Creep): Boolean = has(c, ATTACK)

    fun isRanged(c: Creep): Boolean = !has(c, ATTACK) && has(c, RANGED_ATTACK)

    fun isHealer(c: Creep): Boolean = !has(c, ATTACK) && !has(c, RANGED_ATTACK) && has(c, HEAL)

    fun meleeDps(c: Creep): Int = live(c, ATTACK) * ATTACK_POWER
    fun rangedDps(c: Creep): Int = live(c, RANGED_ATTACK) * RANGED_ATTACK_POWER
    fun healPower(c: Creep): Int = live(c, HEAL) * HEAL_POWER

    fun body(vararg groups: Pair<BodyPartType, Int>): Array<BodyPartType> {
        val out = ArrayList<BodyPartType>()
        for ((t, n) in groups) repeat(n) { out.add(t) }
        return out.toTypedArray()
    }

    // ---------- the fight ----------

    /** A body in the fight: types and hits per part. Damage comes off the HEAD, healing goes back from the tail
     *  (_recalc-body.js) — so the parts that die first are the ones listed first. */
    class Unit(val parts: Array<BodyPartType>, val hits: IntArray) {
        fun alive() = hits.any { it > 0 }
        fun total() = hits.sum()
        fun missing() = parts.size * 100 - total()
        fun count(t: BodyPartType) = parts.indices.count { parts[it] == t && hits[it] > 0 }
        fun dps() = count(ATTACK) * ATTACK_POWER + count(RANGED_ATTACK) * RANGED_ATTACK_POWER
        fun heal() = count(HEAL) * HEAL_POWER
        /** Takes damage, returns what was left over after the unit died. */
        fun damage(d: Int): Int {
            var left = d
            for (i in parts.indices) { if (left <= 0) break; val take = minOf(hits[i], left); hits[i] -= take; left -= take }
            return left
        }
        fun healUp(h: Int): Int {
            var left = h
            for (i in parts.indices.reversed()) {
                if (left <= 0) break
                val add = minOf(100 - hits[i], left); hits[i] += add; left -= add
            }
            return left
        }
    }

    fun unitOf(body: Array<BodyPartType>) = Unit(body.copyOf(), IntArray(body.size) { 100 })
    fun unitOf(c: Creep) = Unit(Array(c.body.size) { c.body[it].type }, IntArray(c.body.size) { c.body[it].hits })

    class Outcome(val weWin: Boolean, val ticks: Int, val oursLeft: Int, val theirsLeft: Int, val oursStart: Int, val theirsStart: Int) {
        /** Share of our hit points left at the end — 0 when we lose. */
        fun margin(): Double = if (!weWin || oursStart == 0) 0.0 else oursLeft.toDouble() / oursStart
        override fun toString() = "${if (weWin) "win" else "lose"}/${ticks}t ours=$oursLeft/$oursStart theirs=$theirsLeft/$theirsStart"
    }

    /**
     * Two groups in contact: every tick each side fires all its live weapons at the other's weakest unit (overflow goes
     * to the next one), and heals its most hurt unit with all its HEAL. It knows nothing of position — who reaches whom
     * and who kites is the caller's business; what it answers is whose firepower and hit points outlast whose.
     * A draw by the horizon (both sides heal more than they take) counts as a loss for us: a fight we cannot finish is
     * not a fight to start.
     */
    fun fight(ours: List<Unit>, theirs: List<Unit>, horizon: Int = 300): Outcome {
        val us = ours.filter { it.alive() }.toMutableList()
        val them = theirs.filter { it.alive() }.toMutableList()
        val usStart = us.sumOf { it.total() }
        val themStart = them.sumOf { it.total() }
        if (them.isEmpty()) return Outcome(true, 0, usStart, 0, usStart, 0)
        if (us.isEmpty()) return Outcome(false, 0, 0, themStart, 0, themStart)
        for (t in 1..horizon) {
            val ourDmg = us.sumOf { it.dps() }
            val theirDmg = them.sumOf { it.dps() }
            val ourHeal = us.sumOf { it.heal() }
            val theirHeal = them.sumOf { it.heal() }
            spread(them, ourDmg)
            spread(us, theirDmg)
            us.removeAll { !it.alive() }
            them.removeAll { !it.alive() }
            mend(us, ourHeal)
            mend(them, theirHeal)
            if (us.isEmpty()) return Outcome(false, t, 0, them.sumOf { it.total() }, usStart, themStart)
            if (them.isEmpty()) return Outcome(true, t, us.sumOf { it.total() }, 0, usStart, themStart)
        }
        return Outcome(false, horizon, us.sumOf { it.total() }, them.sumOf { it.total() }, usStart, themStart)
    }

    private fun spread(side: List<Unit>, damage: Int) {
        var left = damage
        while (left > 0) {
            val target = side.filter { it.alive() }.minByOrNull { it.total() } ?: return
            left = target.damage(left)
        }
    }

    private fun mend(side: List<Unit>, heal: Int) {
        var left = heal
        while (left > 0) {
            val target = side.filter { it.alive() && it.missing() > 0 }.maxByOrNull { it.missing() } ?: return
            val before = left
            left = target.healUp(left)
            if (left == before) return
        }
    }
}
