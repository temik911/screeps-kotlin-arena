package season4.painandgainadvanced

import screeps.api.ATTACK
import screeps.api.CARRY
import screeps.api.Creep
import screeps.api.EFF_ATTACK_MODIFIER
import screeps.api.EFF_DAMAGE_TAKEN_MODIFIER
import screeps.api.EFF_FATIGUE_MODIFIER
import screeps.api.EFF_HEAL_MODIFIER
import screeps.api.EFF_HITS_LOSS
import screeps.api.EFF_RANGED_ATTACK_MODIFIER
import screeps.api.HEAL
import screeps.api.MOVE
import screeps.api.RANGED_ATTACK
import screeps.api.RESOURCE_ENERGY
import screeps.api.get
import screeps.api.season4.painandgainadvanced.ScoreFlag

/** What a creep is in the army: the two pullers (`c6m6`) are the only ones that carry, the rest fight. */
enum class Role { PULLER, MELEE, RANGED, HEALER }

/**
 * One creep's facts for a tick, read in one pass over its body. Parts are counted LIVE (hits > 0): the engine destroys
 * parts front to back, and the arena's bodies put TOUGH first and the weapon before the MOVE (`t4a8m6`), so a creep
 * keeps moving after its weapon is gone.
 */
class Unit(val c: Creep) {
    val id: String = c.id
    val x = c.x
    val y = c.y
    val cell = Grid.idx(x, y)
    val my = c.my
    var melee = 0; private set
    var ranged = 0; private set
    var heal = 0; private set
    var moves = 0; private set
    var carry = 0; private set
    /** Parts that weigh for fatigue: everything but MOVE and an empty CARRY. */
    var weight = 0; private set
    val born: Int = c.body.size

    init {
        val energy = c.store[RESOURCE_ENERGY] ?: 0
        var carryLeft = energy
        for (p in c.body) {
            val live = p.hits > 0
            when (p.type) {
                ATTACK -> { if (live) melee++; weight++ }
                RANGED_ATTACK -> { if (live) ranged++; weight++ }
                HEAL -> { if (live) heal++; weight++ }
                MOVE -> if (live) moves++
                CARRY -> { carry++; if (carryLeft > 0) { weight++; carryLeft -= 50 } }
                else -> weight++
            }
        }
    }

    val role: Role = when {
        c.body.any { it.type == CARRY } -> Role.PULLER
        c.body.any { it.type == HEAL } -> Role.HEALER
        c.body.any { it.type == RANGED_ATTACK } -> Role.RANGED
        else -> Role.MELEE
    }
    val heavy = born > 8 && role != Role.PULLER
    val armed get() = melee + ranged > 0
    val hits get() = c.hits
    val hitsMax get() = c.hitsMax
    val deficit get() = c.hitsMax - c.hits
    val energy get() = c.store[RESOURCE_ENERGY] ?: 0
    /** Ticks per plain step at a fatigue multiplier: ceil(2·weight·mult / (2·moves)). */
    fun ticksPerStep(mult: Int, swamp: Boolean = false): Int {
        if (weight == 0) return 1
        if (moves == 0) return 1000
        val f = (if (swamp) 10 else 2) * weight * mult
        return maxOf(1, (f + 2 * moves - 1) / (2 * moves))
    }
}

/**
 * A side's effects, derived from the flags it owns — the engine lays the same ones as `effects` on every creep, and
 * the probe line prints both so the two can be compared. Same-type flags stack: ×0.8 / ×0.6 for the weapons, ×0.75 /
 * ×0.5 for healing, ×2 / ×4 for fatigue, −1 / −2 hits a tick.
 */
class Effects(flags: List<ScoreFlag>, mine: Boolean) {
    private val owned = flags.filter { if (mine) it.my == true else it.my == false }
    private fun n(type: String) = owned.count { it.effectType == type }
    val damageTaken = if (n(EFF_DAMAGE_TAKEN_MODIFIER) > 0) 1.1 else 1.0
    val heal = 1.0 - 0.25 * n(EFF_HEAL_MODIFIER)
    val attack = 1.0 - 0.2 * n(EFF_ATTACK_MODIFIER)
    val ranged = 1.0 - 0.2 * n(EFF_RANGED_ATTACK_MODIFIER)
    val hitsLoss = n(EFF_HITS_LOSS)
    val fatigue = if (n(EFF_FATIGUE_MODIFIER) == 0) 1 else if (n(EFF_FATIGUE_MODIFIER) == 1) 2 else 4
    val rate = owned.sumOf { it.scorePerTick }
    override fun toString() = "d${damageTaken} h$heal a$attack r$ranged l$hitsLoss f$fatigue"
}

/**
 * The fight between two groups by Lanchester's linear law: each side's damage per tick, less the other side's
 * healing, against the other side's hits. `ratio` > 1 means we kill them before they kill us. Melee counts at full
 * weight — it is three quarters of an army's damage and the one that decides a fight that is joined; a tower of ours
 * adds its damage at the range it would fire from.
 */
class Duel(ours: List<Unit>, theirs: List<Unit>, ourFx: Effects, theirFx: Effects, ourTowerDps: Double = 0.0, theirTowerDps: Double = 0.0) {
    val ourDps = ours.sumOf { it.melee * 30 * ourFx.attack + it.ranged * 10 * ourFx.ranged } + ourTowerDps
    val theirDps = theirs.sumOf { it.melee * 30 * theirFx.attack + it.ranged * 10 * theirFx.ranged } + theirTowerDps
    val ourHeal = ours.sumOf { it.heal * 12 * ourFx.heal }
    val theirHeal = theirs.sumOf { it.heal * 12 * theirFx.heal }
    val ourHits = ours.sumOf { it.hits.toDouble() }
    val theirHits = theirs.sumOf { it.hits.toDouble() }
    private val toThem = maxOf(1.0, ourDps * theirFx.damageTaken - theirHeal)
    private val toUs = maxOf(1.0, theirDps * ourFx.damageTaken - ourHeal)
    val ticksToKillThem = theirHits / toThem
    val ticksToKillUs = ourHits / toUs
    val ratio = if (theirs.isEmpty()) 99.0 else ticksToKillUs / ticksToKillThem
}
