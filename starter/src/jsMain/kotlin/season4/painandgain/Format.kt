package season4.painandgain

import screeps.api.ATTACK
import screeps.api.CARRY
import screeps.api.Creep
import screeps.api.EFF_ATTACK_MODIFIER
import screeps.api.EFF_DAMAGE_TAKEN_MODIFIER
import screeps.api.EFF_HEAL_MODIFIER
import screeps.api.EFF_RANGED_ATTACK_MODIFIER
import screeps.api.HEAL
import screeps.api.MOVE
import screeps.api.RANGED_ATTACK
import screeps.api.TOUGH
import screeps.api.WORK

// ЗАПИСЬ КРИПА И ФЛАГА СТРОКОЙ (v447, план архитектуры, этап 6) — уровень 1. `bodySummary`, `typeChar`, `num` печатают стадии в своих
// строках лога (`flag t=`, `detach t=`, `f<id> …`); пока они жили в Instruments.kt, четыре стадии импортировали файл приборов
// ради трёх функций форматирования. Перенесены дословно.

/** Число из константы арены (внешнее объявление может оказаться undefined — тогда запасное). */
internal fun PainAndGain.num(v: dynamic, fallback: Double): Double = if (jsTypeOf(v) == "number") v.unsafeCast<Double>() else fallback

internal fun PainAndGain.typeChar(type: String): Char = when (type) {
    EFF_ATTACK_MODIFIER -> 'A'
    EFF_RANGED_ATTACK_MODIFIER -> 'R'
    EFF_HEAL_MODIFIER -> 'H'
    EFF_DAMAGE_TAKEN_MODIFIER -> 'D'
    else -> '?'
}

/** Сводка тела: T10M4R3H1 (только живые части). */
internal fun PainAndGain.bodySummary(creep: Creep): String {
    val order = listOf(TOUGH to 'T', MOVE to 'M', RANGED_ATTACK to 'R', ATTACK to 'A', HEAL to 'H', CARRY to 'C', WORK to 'W')
    val sb = StringBuilder()
    for ((type, ch) in order) {
        val n = creep.body.count { it.type == type && it.hits > 0 }
        if (n > 0) sb.append(ch).append(n)
    }
    return sb.toString()
}
