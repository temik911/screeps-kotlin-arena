@file:JsModule("arena/season_4/pain_and_gain/advanced/constants")
@file:JsNonModule

package screeps.api.season4.painandgainadvanced

import screeps.api.Record

/** A flag type: its score per tick and the effect it lays on its owner. */
external interface ScoreFlagType {
    val scorePerTick: Int
    val effectType: String
}

/** The match's tick limit (5000 in advanced). */
external val TICKS_LIMIT: Int

/** The score per tick of every flag together (43 in advanced). */
external val MAX_SCORE_PER_TICK: Int

/** Flag types by name: name -> (scorePerTick, effectType). */
external val FLAG_TYPES: Record<String, ScoreFlagType>
