package season4.escortrunadvanced

import screeps.api.Creep
import screeps.api.Position
import screeps.api.getDirection
import screeps.api.getRange

/**
 * The reverse train of Escort Run (copied from the basic bot, season4/escortrun, where it took the arena's first place):
 * the escort is the HEAD and pulls a chain of pure-MOVE pullers behind it. Every tick the escort pulls the first, each
 * puller pulls the next — the link lives one tick and is set again every tick, the waiting ticks too, because the pulled
 * creeps' MOVEs shed the head's fatigue only while the link exists. When the head's fatigue is zero it steps, and each
 * puller steps into the cell of the one ahead (a pulled creep must step exactly there or the link breaks). The period
 * of the whole train is ceil(weight × rate / (2 × Σ MOVE of the train)) — the pullers weigh nothing.
 */
internal object Trains {

    private fun dirTo(from: Position, to: Position) = getDirection(to.x - from.x, to.y - from.y)

    /** The chain behind `escort`: each next puller adjacent to the one before, last tick's order kept. */
    fun chainOf(escort: Creep, pullers: List<Creep>, lastOrder: List<String>, idOf: (Creep) -> String): List<Creep> {
        val free = pullers.filter { !it.spawning }.sortedBy { val i = lastOrder.indexOf(idOf(it)); if (i < 0) 100 else i }.toMutableList()
        val chain = ArrayList<Creep>()
        var cur: Creep = escort
        while (true) {
            val next = free.firstOrNull { getRange(it, cur) <= 1 } ?: break
            chain.add(next); free.remove(next); cur = next
        }
        return chain
    }

    /**
     * One tick of the train: the links, and the step to `next` when the head is rested. True when it stepped. When the
     * first puller itself stands on `next` the two swap (it is pulled into the escort's cell, which is exactly where a
     * pulled creep must go) and the rest of the chain holds this tick — before the start the pullers gather round the
     * escort in any order, and v11's first trains stood at home because each one's next cell held its own puller.
     */
    fun step(escort: Creep, chain: List<Creep>, next: Position?): Boolean {
        if (chain.isNotEmpty()) escort.pull(chain[0])
        for (i in 0 until chain.size - 1) chain[i].pull(chain[i + 1])
        if (next == null || escort.fatigue > 0) return false
        escort.move(dirTo(escort, next))
        if (chain.isNotEmpty() && chain[0].x == next.x && chain[0].y == next.y) {
            chain[0].move(dirTo(chain[0], escort))
            return true
        }
        var ahead: Position = escort
        for (p in chain) { p.move(dirTo(p, ahead)); ahead = p }
        return true
    }

    /** Period of a train on plain / swamp: ceil(weight × rate / (2 × Σ MOVE)). */
    fun period(weight: Int, moves: Int, swamp: Boolean): Int = Bodies.period(weight, moves, swamp)

    /** MOVE the pullers must add for a period of `p` on plain: Σ MOVE ≥ weight / p. */
    fun movesFor(weight: Int, ownMoves: Int, p: Int): Int = maxOf(0, (weight + p - 1) / p - ownMoves)
}
