@file:JsModule("arena/season_4/pain_and_gain/basic/constants")
@file:JsNonModule

package screeps.api.season4

import screeps.api.Record

/** Тип флага очков: сколько очков в тик и какой эффект накладывает на владельца. */
external interface ScoreFlagType {
    val scorePerTick: Int
    val effectType: String
}

/** Лимит тиков матча (basic — 2000; advanced — 5000). */
external val TICKS_LIMIT: Int

/** Максимум очков в тик, который можно набирать, владея всеми флагами (basic — 25; advanced — 43). */
external val MAX_SCORE_PER_TICK: Int

/** Типы флагов по имени: имя типа -> (scorePerTick, effectType). */
external val FLAG_TYPES: Record<String, ScoreFlagType>
