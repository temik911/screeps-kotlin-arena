package season4.painandgainadvanced

/**
 * Where each creep of a group in contact stands: a step chosen by scoring the creep's own cell and its eight
 * neighbours for its role.
 *
 * What it answers (27.09.2026, stachu3478#5 against v6, 14 lost for 3 in ninety ticks): our group stood formed at the
 * centre, but formed as it had walked — its melee inside the lump, behind our ranged and healers — and when his line
 * came (melee in front, ranged two to four behind, healers behind them, mass attack on our lump) only one to three of
 * our five melee ever swung and our healers healed from three cells at a third of their power. So: melee go to the
 * front of the group and onto his line; ranged keep three from his nearest creep and out of his melee's reach;
 * healers stand next to the mate they heal, on the side away from him.
 */
object Formation {
    private val dx = intArrayOf(0, 0, 1, 1, 1, 0, -1, -1, -1)
    private val dy = intArrayOf(0, -1, -1, 0, 1, 1, 1, 0, -1)

    class Ctx(
        val armed: List<Unit>,    // his creeps that fight or heal (all of his when none does)
        val foeMelee: IntArray,   // walking steps to his nearest creep with ATTACK: a melee has to walk to strike
        val front: Int,           // our line: the least range to him among our creeps with a weapon
        val blocked: (Int) -> Boolean,
    ) {
        /** Range to his nearest armed creep — ranged fire and healing reach by range, through walls. */
        fun foe(cell: Int): Int {
            val x = Grid.xOf(cell); val y = Grid.yOf(cell)
            var best = 99
            for (e in armed) { val r = Grid.range(x, y, e.x, e.y); if (r < best) best = r }
            return best
        }
    }

    fun ctx(ours: List<Unit>, theirs: List<Unit>, blocked: (Int) -> Boolean): Ctx {
        val armed = theirs.filter { it.armed || it.heal > 0 }.ifEmpty { theirs }
        val melee = theirs.filter { it.melee > 0 }
        val foeMelee = if (melee.isEmpty()) IntArray(Grid.N * Grid.N) { Grid.INF } else Grid.fresh(melee.map { it.cell }.toIntArray(), 1)
        val c = Ctx(armed, foeMelee, 99, blocked)
        val front = ours.filter { it.armed }.minOfOrNull { c.foe(it.cell) } ?: 99
        return Ctx(armed, foeMelee, front, blocked)
    }

    /** The cell `u` wants next (its own cell when it should stay), or -1 when nothing is better than standing. */
    fun step(u: Unit, role: Role, c: Ctx, patient: Unit?, engaged: Boolean): Int {
        var best = u.cell; var bestScore = score(u, u.cell, role, c, patient, engaged) + STAY_BONUS
        for (k in 1 until 9) {
            val nx = u.x + dx[k]; val ny = u.y + dy[k]
            if (!Grid.inside(nx, ny) || Grid.wall(nx, ny)) continue
            val n = Grid.idx(nx, ny)
            if (c.blocked(n)) continue
            val s = score(u, n, role, c, patient, engaged) - (if (Grid.swamp(nx, ny)) SWAMP_PENALTY else 0.0)
            if (s > bestScore) { bestScore = s; best = n }
        }
        return best
    }

    private fun score(u: Unit, cell: Int, role: Role, c: Ctx, patient: Unit?, engaged: Boolean): Double {
        val d = c.foe(cell).toDouble()
        val dm = c.foeMelee[cell].toDouble()
        return when (role) {
            Role.MELEE -> if (engaged) -d * 10 else -kotlin.math.abs(d - maxOf(1.0, c.front.toDouble())) * 10 - (if (d < c.front) 5.0 else 0.0)
            Role.RANGED -> {
                // three from his nearest is the most a single shot reaches; nearer only feeds his melee
                val reach = if (d <= 3) 20.0 - (3 - d) * 2 else -(d - 3) * (if (engaged) 8.0 else 2.0)
                val safe = when { dm <= 1 -> -60.0; dm <= 2 -> -25.0; else -> 0.0 }
                val behind = if (!engaged && d < c.front + 1) -12.0 else 0.0
                reach + safe + behind
            }
            Role.HEALER -> {
                val nearPatient = if (patient == null) 0.0 else {
                    val r = Grid.range(Grid.xOf(cell), Grid.yOf(cell), patient.x, patient.y)
                    if (r <= 1) 30.0 else if (r <= 3) 12.0 - r else -r * 3.0
                }
                val safe = when { dm <= 1 -> -50.0; dm <= 2 -> -20.0; d <= 1 -> -15.0; else -> 0.0 }
                val behind = if (d < c.front + 2) -(c.front + 2 - d) * 4 else 0.0
                nearPatient + safe + behind + minOf(d, 6.0) * 0.5
            }
            Role.PULLER -> 0.0
        }
    }

    private const val STAY_BONUS = 0.5
    private const val SWAMP_PENALTY = 6.0
}
