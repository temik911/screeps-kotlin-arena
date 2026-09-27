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

    /**
     * A fighter's body with its MOVE spread through it (M W M W …, the rest of the MOVE at the tail): damage takes parts
     * from the head, and a body that starts with all its MOVE stands still after its first 500 damage — v10's M5A5 #221
     * stood 252 ticks at (14,13) with five dead MOVE and 460 hits, and an M5R5 at 550 hits walked at period 5 (6ab939aa).
     * Spread, half the damage leaves half the MOVE and half the weapon.
     */
    fun interleaved(weapon: BodyPartType, weapons: Int, moves: Int): Array<BodyPartType> {
        val out = ArrayList<BodyPartType>()
        var w = weapons; var m = moves
        while (w > 0 || m > 0) {
            if (m > 0) { out.add(MOVE); m-- }
            if (w > 0) { out.add(weapon); w-- }
        }
        return out.toTypedArray()
    }

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
            // a ranged unit facing MASS_CROWD or more melee of the other side fires rangedMassAttack: they come to it and
            // stand beside it, and ten a part hits each of them — stachu3478#3 held the centre with dozens of M1A1 and
            // v4's simulation, firing one target a tick, called every fight with them lost (6ab92899)
            val ourMass = massShooters(us, them)
            val theirMass = massShooters(them, us)
            val ourDmg = us.sumOf { if (it in ourMass) it.count(MELEE_PART) * ATTACK_POWER else it.dps() }
            val theirDmg = them.sumOf { if (it in theirMass) it.count(MELEE_PART) * ATTACK_POWER else it.dps() }
            val ourHeal = us.sumOf { it.heal() }
            val theirHeal = them.sumOf { it.heal() }
            for (u in ourMass) mass(them, u.count(RANGED_ATTACK) * RANGED_ATTACK_POWER)
            for (u in theirMass) mass(us, u.count(RANGED_ATTACK) * RANGED_ATTACK_POWER)
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

    class Race(val done: Boolean, val ticks: Int, val oursLeft: Int, val oursStart: Int) {
        fun margin(): Double = if (!done || oursStart == 0) 0.0 else oursLeft.toDouble() / oursStart
        override fun toString() = "${if (done) "kill" else "fail"}/${ticks}t ours=$oursLeft/$oursStart"
    }

    /**
     * A race to ONE kill — the arena ends when one of his escorts dies, so an operation is not "beat his army" but "the
     * escort dies before our group does". Every tick all our weapons go into the target: the rampart under it first (a
     * rampart takes no healing), then the escort, which his healers present mend; his fighters join at their arrival tick
     * (`theirs` = unit to the tick it arrives) and fire at our weakest. v10's simulation weighed the whole fight and struck
     * nothing while stachu3478#3's M10T40 stood 200 ticks by his flag with no guard nearer than 28 ticks (6ab939c2).
     */
    fun race(ours: List<Unit>, rampart: Int, escort: Int, escortMax: Int, theirs: List<Pair<Unit, Int>>, horizon: Int = 300): Race {
        val us = ours.filter { it.alive() }.toMutableList()
        val start = us.sumOf { it.total() }
        if (us.isEmpty()) return Race(false, 0, 0, 0)
        var ramp = rampart
        var hp = escort
        for (t in 1..horizon) {
            val active = theirs.filter { it.second <= t && it.first.alive() }.map { it.first }
            var d = us.sumOf { it.dps() }
            if (ramp > 0) { val take = minOf(ramp, d); ramp -= take; d -= take }
            hp -= d
            if (hp <= 0) return Race(true, t, us.sumOf { it.total() }, start)
            if (ramp <= 0) hp = minOf(escortMax, hp + active.sumOf { it.heal() })
            spread(us, active.sumOf { it.dps() })
            us.removeAll { !it.alive() }
            if (us.isEmpty()) return Race(false, t, 0, start)
            mend(us, us.sumOf { it.heal() })
        }
        return Race(false, horizon, us.sumOf { it.total() }, start)
    }

    private val MELEE_PART = ATTACK
    /** How many melee of the other side must be alive for a ranged unit to count on a mass attack. */
    private const val MASS_CROWD = 3

    private fun massShooters(side: List<Unit>, other: List<Unit>): Set<Unit> {
        val melee = other.count { it.alive() && it.count(ATTACK) > 0 }
        if (melee < MASS_CROWD) return emptySet()
        return side.filter { it.alive() && it.count(RANGED_ATTACK) > 0 }.toSet()
    }

    /** One mass attack: `perTarget` to each of the MASS_CROWD weakest living units of the side. */
    private fun mass(side: List<Unit>, perTarget: Int) {
        for (u in side.filter { it.alive() }.sortedBy { it.total() }.take(MASS_CROWD)) u.damage(perTarget)
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
