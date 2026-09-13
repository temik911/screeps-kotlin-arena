package season4.painandgain

import screeps.api.CostMatrix
import screeps.api.Creep
import screeps.api.Position
import screeps.api.season4.ScoreFlag

/**
 * МИР ТИКА (v238, этап 3 переработки): снимок того, что видно, — и ничего больше. Пока здесь только типы, `FlagInfo` и
 * `Ctx`, перенесённые из `PainAndGain.kt` без изменений; сборка снимка переезжает сюда вместе с памятью между тиками на
 * этапе 4 (см. docs/pain-and-gain-rework.md, разделы 2 и 8).
 */

/** Флаг очков в этом тике: владелец, тип дебаффа, очки, кто стоит на клетке и чья охрана рядом. */
internal class FlagInfo(val flag: ScoreFlag, val mine: Boolean?, val type: String, val score: Int, val occupant: Creep?, val guards: List<Creep>) {
    val id: String get() = flag.id
    val pos: Position get() = flag
    val ours: Boolean get() = mine == true
    val theirs: Boolean get() = mine == false
    /** Очки в тик, которые даёт захват: чужой флаг — двойной размен (нам плюс, врагу минус). */
    val swing: Double get() = if (theirs) 2.0 * score else score.toDouble()
}

internal class Ctx(
    val home: Position,
    val enemyHome: Position,
    val myCreeps: List<Creep>,
    val active: List<Creep>,
    val army: List<Creep>,      // с оружием или лечением
    val runners: List<Creep>,   // безоружные и без лечения: захватчики
    val enemyCreeps: List<Creep>,
    val combatEnemies: List<Creep>,
    val blocked: List<Position>,
    /** Опасность (без флагов) — основа для матриц пути. */
    val rawDanger: CostMatrix,
    /** Опасность + НЕ НАШИ флаги как стены: путь без назначения на флаг не ступает. */
    val dangerMatrix: CostMatrix,
    val flags: List<FlagInfo>,
    /** Клетки не наших флагов (x*100+y): захват — только назначенным, см. flagBlocked. */
    val flagCells: Set<Int>,
    val flagBlocked: List<Position>,
    /** Вся боевая армия врага не сделала ни шага PASSIVE_TICKS тиков (порог захвата один, см. captureAllowed). */
    val passiveEnemy: Boolean,
    val ourCentroid: Position,
    val enemyCentroid: Position?,
)
