@file:JsModule("arena/season_3/power_split/basic/prototypes")
@file:JsNonModule

package screeps.api.season3

import screeps.api.BodyPartType
import screeps.api.Flag

/**
 * Флаг бонуса в арене season3 Power Split. У базы стоят три таких флага; крип, шагнувший на
 * один из них, захватывает его бонус (остальные флаги базы исчезают). Бонус действует на ВСЕХ
 * наших крипов до конца игры:
 *  - bonusType == ATTACK         -> +100% к attack;
 *  - bonusType == RANGED_ATTACK  -> +100% к rangedAttack и rangedMassAttack;
 *  - bonusType == HEAL           -> +100% к heal и rangedHeal.
 */
external class BonusFlag : Flag {

    /** Тип бонуса: ATTACK / RANGED_ATTACK / HEAL. */
    val bonusType: BodyPartType

}
