package season4.painandgainadvanced

import screeps.api.Creep
import screeps.api.getDirection

/**
 * Two-phase movement, copied from the basic Pain and Gain bot's TrafficManager (27.09.2026) and cut down: every creep
 * first registers the neighbouring cell it wants, then [resolve] settles all wishes at once by a DFS that pushes a
 * blocker of ours along a chain or swaps with it. The engine allows both: a cell counts as taken only by a creep that
 * does not move this tick. A tired creep's wish is dropped — it cannot step, and a chain that counted on it vacating
 * its cell would walk into it.
 */
object Traffic {
    private val desired = HashMap<String, Int>()
    private val priorityOf = HashMap<String, Int>()
    private val pinned = HashSet<String>()
    var moves = 0; private set
    var denied = 0; private set

    fun want(c: Creep, cellIdx: Int, priority: Int = 0) {
        if (c.fatigue > 0) return
        if (Grid.idx(c.x, c.y) == cellIdx) return
        desired[c.id] = cellIdx
        priorityOf[c.id] = priority
    }

    /** The creep holds its cell: a chain may not push it off (a flag keeper). */
    fun pin(c: Creep) { pinned.add(c.id) }

    fun wants(c: Creep): Boolean = c.id in desired

    fun resolve(mine: List<Creep>) {
        val occupant = HashMap<Int, Creep>()
        for (c in mine) occupant[Grid.idx(c.x, c.y)] = c
        val ids = HashSet<String>().apply { mine.forEach { add(it.id) } }
        val movement = HashMap<Int, Creep>()
        val assigned = HashMap<String, Int>()
        for (m in mine.filter { it.id in desired }.sortedByDescending { priorityOf[it.id] ?: 0 }) {
            if (m.id in assigned) continue
            if (!dfs(m, null, occupant, ids, movement, assigned, HashSet())) denied++
        }
        for ((coord, c) in movement) {
            val tx = Grid.xOf(coord); val ty = Grid.yOf(coord)
            if (tx != c.x || ty != c.y) { c.move(getDirection(tx - c.x, ty - c.y)); moves++ }
        }
        desired.clear(); priorityOf.clear(); pinned.clear()
    }

    fun resetCounters() { moves = 0; denied = 0 }

    private fun dfs(creep: Creep, fromCoord: Int?, occupant: Map<Int, Creep>, ids: Set<String>, movement: HashMap<Int, Creep>,
                    assigned: HashMap<String, Int>, visited: HashSet<String>): Boolean {
        visited.add(creep.id)
        val candidates = ArrayList<Int>(2)
        desired[creep.id]?.let { candidates.add(it) }
        if (fromCoord != null) candidates.add(fromCoord)
        for (coord in candidates) {
            if (movement.containsKey(coord)) continue
            if (coord == fromCoord) { place(coord, creep, movement, assigned); return true }
            val occ = occupant[coord]
            if (occ == null || occ.id == creep.id) { place(coord, creep, movement, assigned); return true }
            val occAssigned = assigned[occ.id]
            if (occAssigned != null) {
                if (occAssigned != coord) { place(coord, creep, movement, assigned); return true }
                continue
            }
            val canPush = occ.fatigue <= 0 && occ.id !in pinned &&
                (desired.containsKey(occ.id) || (priorityOf[creep.id] ?: 0) > (priorityOf[occ.id] ?: 0))
            if (occ.id in ids && occ.id !in visited && canPush) {
                if (dfs(occ, Grid.idx(creep.x, creep.y), occupant, ids, movement, assigned, visited)) {
                    if (movement.containsKey(coord)) continue
                    place(coord, creep, movement, assigned)
                    return true
                }
            }
        }
        return false
    }

    private fun place(coord: Int, c: Creep, movement: HashMap<Int, Creep>, assigned: HashMap<String, Int>) {
        movement[coord] = c
        assigned[c.id] = coord
    }
}
