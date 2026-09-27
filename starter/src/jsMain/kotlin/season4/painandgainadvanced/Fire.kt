package season4.painandgainadvanced

import screeps.api.ATTACK
import screeps.api.HEAL
import screeps.api.RANGED_ATTACK

/**
 * Who shoots whom and who heals whom, for one tick.
 *
 * The engine destroys a creep's parts front to back and the arena's bodies put TOUGH first, then the weapon, then
 * MOVE (`t4a8m6`, `a4m4`): damage is worth what it takes OUT of the target's output. On a fresh heavy the first 400
 * go into armour and remove nothing; on a fresh light the first 100 already remove an ATTACK part; below the weapon
 * the creep is a harmless runner and a kill removes nothing more (the basic bot measured the same, `kill-removes-
 * no-output`). So the fire is assigned greedily by output removed per point of damage, each target's "need" being its
 * hits above the tail plus the healing its mates can put on it this tick — damage past that is waste. Healing is the
 * mirror: it goes where it gives back the most output (the parts are filled from the tail, so healed hits restore the
 * rearmost dead part first).
 */
object Fire {

    /** Output lost by `u` if it takes `dmg` more damage now, in the same units as the duel (melee 30, ranged 10, heal 12
     *  per part, times the owner's effects). */
    fun outputRemoved(u: Unit, dmg: Double, fx: Effects): Double {
        var left = dmg
        var lost = 0.0
        for (p in u.c.body) {
            if (p.hits <= 0) continue
            if (left <= 0) break
            val take = minOf(left, p.hits.toDouble())
            left -= take
            if (take >= p.hits) lost += partValue(p.type, fx)
        }
        return lost
    }

    /** Output restored if `u` is healed by `amount`: parts are filled from the tail, so the healed hits land on the
     *  rearmost dead parts first. */
    fun outputRestored(u: Unit, amount: Double, fx: Effects): Double {
        var left = amount
        var gained = 0.0
        val body = u.c.body
        for (i in body.indices.reversed()) {
            val p = body[i]
            if (p.hits >= 100) continue
            if (left <= 0) break
            val need = 100 - p.hits
            val give = minOf(left, need.toDouble())
            left -= give
            if (p.hits == 0 && give >= need) gained += partValue(p.type, fx)
        }
        return gained
    }

    fun partValue(type: Any, fx: Effects): Double = when (type) {
        ATTACK -> 30 * fx.attack
        RANGED_ATTACK -> 10 * fx.ranged
        HEAL -> 12 * fx.heal
        else -> 0.0
    }

    /** Hits the creep has above its unarmed tail: damage past this takes nothing more out of it. */
    fun weaponRoom(u: Unit): Int {
        var lastWeapon = -1
        val body = u.c.body
        for (i in body.indices) if ((body[i].type == ATTACK || body[i].type == RANGED_ATTACK || body[i].type == HEAL) && body[i].hits > 0) lastWeapon = i
        if (lastWeapon < 0) return 0
        var tail = 0
        for (i in lastWeapon + 1 until body.size) tail += body[i].hits
        return u.hits - tail
    }

    class Shot(val shooter: Unit, val target: Unit?, val mass: Boolean)

    /** The enemy the group has been focusing: kept while it lives and something of ours reaches it, since damage on a
     *  healed target is only worth what it gains over the healing, tick after tick. */
    var focusId: String? = null

    /**
     * Group focus. For every enemy in reach: what all our guns that reach it put on it this tick, less what his healers
     * can put back ("net"), against the hits standing between it and a stripped weapon ("room" — armour, then the
     * weapon itself). Ticks to strip = room / net; the value is its output (melee 30, ranged 10, heal 12 a part, with
     * its owner's effects). The group fires at the best value per tick of stripping, everyone who reaches it, and the
     * rest go to the next such target.
     *
     * Why (27.09.2026, stachu3478#5 against v7): his two heavy healers put back ~220k hits in a thousand ticks against
     * our ~76k of ranged fire, while the per-shot greedy of v4-v7 saw one volley on a fresh heavy remove nothing (its
     * armour) and scattered our fire over his light creeps and even his pullers (225 hits on them). Damage spread
     * under heavy healing removes nothing; concentrated past his heal it strips a weapon every few ticks.
     */
    fun assign(ours: List<Unit>, theirs: List<Unit>, ourFx: Effects, theirFx: Effects): List<Shot> {
        val healOn = HashMap<String, Double>()
        for (e in theirs) {
            var h = 0.0
            for (m in theirs) {
                if (m.heal == 0 || m === e) continue
                val r = Grid.range(m.x, m.y, e.x, e.y)
                if (r <= 1) h += m.heal * 12 * theirFx.heal else if (r <= 3) h += m.heal * 4 * theirFx.heal
            }
            // his healers split between the creeps we hit; one target gets at most what its nearest two can give
            healOn[e.id] = minOf(h, 2 * 96.0 * theirFx.heal) + e.heal * 12 * theirFx.heal
        }
        val shooters = ours.filter { it.melee > 0 || it.ranged > 0 }.toMutableList()
        val shots = ArrayList<Shot>()
        val left = theirs.toMutableList()
        while (shooters.isNotEmpty() && left.isNotEmpty()) {
            var best: Unit? = null; var bestScore = 0.0
            for (e in left) {
                val net = shooters.sumOf { u -> reachDamage(u, e, ourFx) } * theirFx.damageTaken - (healOn[e.id] ?: 0.0)
                if (net <= 0) continue
                val room = maxOf(1, weaponRoom(e))
                val value = e.melee * 30 * theirFx.attack + e.ranged * 10 * theirFx.ranged + e.heal * 12 * theirFx.heal
                // a creep with no weapon left is worth only its kill (a runner still holds flags), a little
                val worth = if (value > 0) value else 2.0
                val ticks = room / net
                var score = worth / maxOf(1.0, ticks)
                if (e.id == focusId) score *= 1.5
                if (e.hits <= net) score *= 1.3
                if (score > bestScore) { bestScore = score; best = e }
            }
            if (best == null) break
            val tgt = best
            if (shots.isEmpty()) focusId = tgt.id
            val onIt = shooters.filter { reachDamage(it, tgt, ourFx) > 0 }
            for (u in onIt) shots.add(Shot(u, tgt, false))
            shooters.removeAll(onIt)
            left.remove(tgt)
        }
        // a ranged creep with no target of the focus in reach, or with several enemies close, takes the mass attack
        // when its 10 / 4 / 1 per part over everything in three beats its single shot
        val out = ArrayList<Shot>()
        for (s in shots) {
            val u = s.shooter
            if (u.melee == 0 && u.ranged > 0) {
                val near = theirs.filter { Grid.range(it.x, it.y, u.x, u.y) <= 3 }
                val mass = near.sumOf { e -> when (Grid.range(e.x, e.y, u.x, u.y)) { 0, 1 -> 10; 2 -> 4; else -> 1 }.toDouble() }
                if (near.size >= 3 && mass > 10 * 1.5) { out.add(Shot(u, null, true)); continue }
            }
            out.add(s)
        }
        // shooters left with nothing assigned (their targets all out-healed) still fire at whatever is in reach
        for (u in shooters) {
            val tgt = theirs.filter { reachDamage(u, it, ourFx) > 0 }.minByOrNull { it.hits } ?: continue
            out.add(Shot(u, tgt, false))
        }
        return out
    }

    private fun reachDamage(u: Unit, e: Unit, fx: Effects): Double {
        val r = Grid.range(u.x, u.y, e.x, e.y)
        return (if (r <= 1) u.melee * 30 * fx.attack else 0.0) + (if (r <= 3) u.ranged * 10 * fx.ranged else 0.0)
    }

    private fun damageOf(u: Unit, e: Unit, fx: Effects): Double =
        if (u.melee > 0 && Grid.range(u.x, u.y, e.x, e.y) <= 1) u.melee * 30 * fx.attack
        else u.ranged * 10 * fx.ranged

    class Heal(val healer: Unit, val target: Unit, val ranged: Boolean)

    /**
     * Every healer of ours heals one: the target whose healing gives back the most output, counting the damage the
     * enemy can put on it this tick (a creep under fire is healed before it loses a part, not after). A healer
     * with nothing to restore tops up the most hurt in reach, since the hits-loss flags drain everyone.
     */
    fun heals(healers: List<Unit>, ours: List<Unit>, theirs: List<Unit>, ourFx: Effects, theirFx: Effects): List<Heal> {
        val threat = HashMap<String, Double>()
        for (a in ours) {
            var d = 0.0
            for (e in theirs) {
                val r = Grid.range(e.x, e.y, a.x, a.y)
                if (r <= 2 && e.melee > 0) d += e.melee * 30 * theirFx.attack   // a melee two away steps in and swings
                if (r <= 4 && e.ranged > 0) d += e.ranged * 10 * theirFx.ranged
            }
            threat[a.id] = d * ourFx.damageTaken
        }
        val given = HashMap<String, Double>()
        val out = ArrayList<Heal>()
        for (h in healers.filter { it.heal > 0 }.sortedBy { u -> ours.count { Grid.range(it.x, it.y, u.x, u.y) <= 1 } }) {
            var best: Unit? = null; var bestV = 0.0; var bestRanged = false
            for (a in ours) {
                val r = Grid.range(a.x, a.y, h.x, h.y)
                if (r > 3) continue
                val amount = h.heal * (if (r <= 1) 12 else 4) * ourFx.heal
                val already = given[a.id] ?: 0.0
                val deficit = a.deficit + (threat[a.id] ?: 0.0) - already
                if (deficit <= 0) continue
                val useful = minOf(amount, deficit)
                // restore dead parts first; otherwise shield the one under fire; otherwise top up
                val restore = outputRestored(a, already + useful, ourFx) - outputRestored(a, already, ourFx)
                val v = restore * 10 + (threat[a.id] ?: 0.0) * 0.01 + useful * 0.001
                if (v > bestV) { bestV = v; best = a; bestRanged = r > 1 }
            }
            if (best != null) {
                val r = Grid.range(best.x, best.y, h.x, h.y)
                given[best.id] = (given[best.id] ?: 0.0) + h.heal * (if (r <= 1) 12 else 4) * ourFx.heal
                out.add(Heal(h, best, bestRanged))
            }
        }
        return out
    }
}
