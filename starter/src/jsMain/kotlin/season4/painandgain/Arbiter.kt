package season4.painandgain

/**
 * АРБИТР, ВЕРСИЯ 1 (v240, этап 5 переработки). Правила совместимости интентов:
 *  R2 — ход уставшего или неподвижного отбрасывается явно и считается (`conf=<цель вне досягаемости>/<уставших>`);
 *  R3 — выживание выше задания: бегство стоит над приказом и постом хранителя в цепочке шага (`fled=`, `step=flee`);
 *  R4 — очередь толкания `rankOf` — единственное место чисел 1..8: спасение (Priority.SURVIVE, v253) выше всего, затем
 *       приказ по роли (мили, стрелок, лекарь, прочие), затем бойцы, бегуны, раненые;
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
    /** СПАСЕНИЕ ВЫШЕ ВСЕГО В ОЧЕРЕДИ (v253, этап 9; решение оператора №2 плана — выживание всегда выше задания). До v253
     *  бегство стояло над приказом только в цепочке шага крипа (R3, v240), а в очереди толкания бегущий имел ранг своей
     *  роли: раздетый — WOUNDED_PRIORITY, самый низкий, поэтому его клетку отхода первым забирал любой сосед, а стоящий
     *  на пути боец не сдвигался (сдвигается только стоящий МЕНЬШЕГО ранга). Теперь ход SURVIVE разбирается первым и
     *  толкает стоящих */
    const val SURVIVE_PRIORITY = 8

    fun pushRank(ordered: Boolean, melee: Boolean, armed: Boolean, healer: Boolean, wounded: Boolean): Int = when {
        ordered -> when {
            melee -> ORDER_PRIORITY_MELEE
            armed -> ORDER_PRIORITY_RANGED
            healer -> ORDER_PRIORITY_HEAL
            else -> ORDER_PRIORITY
        }
        wounded -> WOUNDED_PRIORITY
        else -> FIGHTER_PRIORITY
    }

    /** Ранг предложения: SURVIVE — [SURVIVE_PRIORITY], иначе [pushRank] по приказу и роли. */
    fun rankOf(priority: Priority, ordered: Boolean, melee: Boolean, armed: Boolean, healer: Boolean, wounded: Boolean): Int =
        if (priority == Priority.SURVIVE) SURVIVE_PRIORITY else pushRank(ordered, melee, armed, healer, wounded)

    fun audit() { confReach += Executor.unreachable(RANGED_RANGE) }
}
