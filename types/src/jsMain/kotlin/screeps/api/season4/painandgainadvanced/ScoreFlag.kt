@file:JsModule("arena/season_4/pain_and_gain/advanced/prototypes")
@file:JsNonModule

package screeps.api.season4.painandgainadvanced

import screeps.api.Flag

/**
 * The score flag of season 4 Pain and Gain ADVANCED. The same shape as the basic one
 * ([screeps.api.season4.ScoreFlag]), but a separate prototype in the arena's own module, so
 * `getObjectsByPrototype` needs this class: eleven flags, the basic four effects plus
 * EFF_FATIGUE_MODIFIER (×2 / ×4 fatigue gained) and EFF_HITS_LOSS (−1 / −2 hits per tick), and
 * some of the flags carry a neutral StructureTower that goes to the flag's owner.
 */
external class ScoreFlag : Flag {

    /** The global effect laid on every creep of the owner while the flag is held — one of the
     *  EFF_* constants of `game/constants`. */
    val effectType: String

    /** Score awarded to the owner each tick. */
    val scorePerTick: Int

}
