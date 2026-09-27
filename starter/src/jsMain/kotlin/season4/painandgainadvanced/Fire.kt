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

    /**
     * Assign every armed creep of `ours` a target among `theirs`. `ourFx` scales our damage, `theirFx` their damage
     * taken and their healing. Returns melee swings and ranged shots; a ranged creep with several targets close takes
     * the mass attack when its total (10 / 4 / 1 per part at 1 / 2 / 3) beats the single shot's value.
     */
    fun assign(ours: List<Unit>, theirs: List<Unit>, ourFx: Effects, theirFx: Effects): List<Shot> {
        val assigned = HashMap<String, Double>()
        // the healing his mates can put on a target this tick: every healer of his in reach heals SOMEONE, and the
        // one we focus is the one he heals — counted in full, as a bound
        val healOn = HashMap<String, Double>()
        for (e in theirs) {
            var h = 0.0
            for (m in theirs) {
                if (m.heal == 0) continue
                val r = Grid.range(m.x, m.y, e.x, e.y)
                if (r <= 1) h += m.heal * 12 * theirFx.heal else if (r <= 3) h += m.heal * 4 * theirFx.heal
            }
            healOn[e.id] = h
        }
        val shots = ArrayList<Shot>()
        val shooters = ours.filter { it.melee > 0 || it.ranged > 0 }
            .sortedBy { u -> theirs.count { Grid.range(it.x, it.y, u.x, u.y) <= (if (u.melee > 0) 1 else 3) } }
        for (u in shooters) {
            val reach = if (u.melee > 0) 1 else 3
            val options = theirs.filter { Grid.range(it.x, it.y, u.x, u.y) <= reach }
            if (options.isEmpty()) continue
            var best: Unit? = null; var bestV = -1.0
            for (e in options) {
                val dmg = damageOf(u, e, ourFx) * theirFx.damageTaken
                val before = assigned[e.id] ?: 0.0
                val heal = healOn[e.id] ?: 0.0
                // damage that his heal undoes this tick removes nothing: what counts is the part of the running total
                // above his heal
                val v0 = outputRemoved(e, maxOf(0.0, before - heal), theirFx)
                val v1 = outputRemoved(e, maxOf(0.0, before + dmg - heal), theirFx)
                val focus = if (before > 0) 1.05 else 1.0
                val v = (v1 - v0) * focus + (if (e.hits <= before + dmg - heal) 5.0 else 0.0) + 0.001 * (1000.0 / e.hits)
                if (v > bestV) { bestV = v; best = e }
            }
            val tgt = best!!
            if (u.melee == 0 && u.ranged > 0) {
                val massValue = options.sumOf { e ->
                    val r = Grid.range(e.x, e.y, u.x, u.y)
                    val per = when (r) { 0, 1 -> 10; 2 -> 4; else -> 1 }
                    val dmg = u.ranged * per * ourFx.ranged * theirFx.damageTaken
                    val before = assigned[e.id] ?: 0.0
                    val heal = healOn[e.id] ?: 0.0
                    outputRemoved(e, maxOf(0.0, before + dmg - heal), theirFx) - outputRemoved(e, maxOf(0.0, before - heal), theirFx) + dmg * 0.0005
                }
                val single = bestV + damageOf(u, tgt, ourFx) * 0.0005
                if (options.size > 1 && massValue > single) {
                    for (e in options) {
                        val r = Grid.range(e.x, e.y, u.x, u.y)
                        val per = when (r) { 0, 1 -> 10; 2 -> 4; else -> 1 }
                        assigned[e.id] = (assigned[e.id] ?: 0.0) + u.ranged * per * ourFx.ranged * theirFx.damageTaken
                    }
                    shots.add(Shot(u, null, true))
                    continue
                }
            }
            assigned[tgt.id] = (assigned[tgt.id] ?: 0.0) + damageOf(u, tgt, ourFx) * theirFx.damageTaken
            shots.add(Shot(u, tgt, false))
        }
        return shots
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
