@file:JsModule("arena/season_4/pain_and_gain/basic/prototypes")
@file:JsNonModule

package screeps.api.season4

import screeps.api.Flag

/**
 * Флаг очков в арене season4 Pain and Gain (basic). Захватывается МГНОВЕННО крипом, вставшим на
 * него, и каждый тик приносит владельцу [scorePerTick] очков — ценой глобального дебаффа
 * [effectType] на ВСЕХ крипов владельца, пока флаг под его контролем. Флаги одного типа у одного
 * игрока складываются в один эффект по формуле арены (документация клиента, arena-docs):
 *
 *  | эффект                      | один флаг | два флага |
 *  |-----------------------------|-----------|-----------|
 *  | EFF_ATTACK_MODIFIER         | ×0.8      | ×0.6      |
 *  | EFF_RANGED_ATTACK_MODIFIER  | ×0.8      | ×0.6      |
 *  | EFF_HEAL_MODIFIER           | ×0.75     | ×0.5      |
 *  | EFF_DAMAGE_TAKEN_MODIFIER   | ×1.1      | —         |
 *
 * Значение действия = base × (multiplier ?: 1) + (offset ?: 0). Advanced добавляет
 * EFF_FATIGUE_MODIFIER (×2/×4) и EFF_HITS_LOSS (−1/−2 хитов в тик, лечение компенсирует).
 */
external class ScoreFlag : Flag {

    /** Глобальный эффект, накладываемый на владельца, пока флаг под его контролем: одна из
     *  констант EFF_ATTACK_MODIFIER / EFF_RANGED_ATTACK_MODIFIER / EFF_HEAL_MODIFIER /
     *  EFF_DAMAGE_TAKEN_MODIFIER (в advanced также EFF_FATIGUE_MODIFIER и EFF_HITS_LOSS). */
    val effectType: String

    /** Очки, начисляемые владельцу каждый тик. */
    val scorePerTick: Int

}
