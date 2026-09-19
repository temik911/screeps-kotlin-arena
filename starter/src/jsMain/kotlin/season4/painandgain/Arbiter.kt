package season4.painandgain

/**
 * АРБИТР, ВЕРСИЯ 1 (v240, этап 5 переработки). Правила совместимости интентов:
 *  R2 — ход уставшего или неподвижного отбрасывается явно и считается (`conf=<цель вне досягаемости>/<уставших>`);
 *  R3 — выживание выше задания: бегство стоит над приказом и постом хранителя в цепочке шага (`fled=`, `step=flee`);
 *  R4 — очередь толкания `pushRank` — единственное место чисел 1..7: приказ по роли (мили, стрелок, лекарь, прочие),
 *       затем бойцы, бегуны, раненые;
 *  R5 — цель контакта/дальнего в досягаемости с текущей клетки (пока только счёт, см. Executor.unreachable);
 *  R6 — один интент на семейство — по типу слотов Executor;
 *  R7 — клетка не двоим: TrafficManager решает, но API не зовёт — ходы выдаёт Executor.run.
 *  R1 (ход только из списка предложения) ждёт планировщика с ранжированным списком клеток (этапы 7–9).
 */
internal object Arbiter {
    var confReach = 0
    var confFatigue = 0

    // очередь толкания (v170, оператор: «командир должен согласовать все движения»): исполняющий приказ идёт первым,
    // среди приказов — мили в контакт, стрелок с целью, лекарь к подопечному, затем прочие; без приказа — бойцы, бегуны,
    // раненые. Больший толкает стоящего меньшего (см. TrafficManager.dfs)
    const val ORDER_PRIORITY_MELEE = 7
    const val ORDER_PRIORITY_RANGED = 6
    const val ORDER_PRIORITY_HEAL = 5
    const val ORDER_PRIORITY = 4           // приказ командира выше прочих: он считает всю армию сразу (v167)
    const val FIGHTER_PRIORITY = 3
    const val RUNNER_PRIORITY = 2
    const val WOUNDED_PRIORITY = 1

    fun pushRank(ordered: Boolean, melee: Boolean, armed: Boolean, healer: Boolean, stripped: Boolean): Int = when {
        ordered -> when {
            melee -> ORDER_PRIORITY_MELEE
            armed -> ORDER_PRIORITY_RANGED
            healer -> ORDER_PRIORITY_HEAL
            else -> ORDER_PRIORITY
        }
        stripped -> WOUNDED_PRIORITY
        else -> FIGHTER_PRIORITY
    }

    fun audit() { confReach += Executor.unreachable(RANGED_RANGE) }
}
