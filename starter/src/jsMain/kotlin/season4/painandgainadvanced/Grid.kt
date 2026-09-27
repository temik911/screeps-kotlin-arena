package season4.painandgainadvanced

import screeps.api.TERRAIN_SWAMP
import screeps.api.TERRAIN_WALL
import screeps.api.getTerrainAt

/**
 * The terrain, read once, and Dijkstra fields over it. The layout of the arena is fixed but its terrain is random per
 * match (27.09.2026: two probes on two maps differ in 1530 cells — the two big central walls and the four swamps stay,
 * everything small moves), so every distance here is computed from the match's own terrain, never written down.
 *
 * Costs are in fatigue-ticks of a creep whose MOVE covers its weight on plain: a plain step is 1, a swamp step 5. A
 * creep that carries no weight (an empty puller) pays nothing on swamp either — its field is built with swamp 1.
 */
object Grid {
    const val N = 100
    const val PLAIN = 0
    const val SWAMP = 1
    const val WALL = 2
    const val INF = 1_000_000

    private val terrain = ByteArray(N * N)
    private var ready = false

    fun idx(x: Int, y: Int) = y * N + x
    fun xOf(i: Int) = i % N
    fun yOf(i: Int) = i / N
    fun inside(x: Int, y: Int) = x in 0 until N && y in 0 until N

    fun init() {
        if (ready) return
        for (y in 0 until N) for (x in 0 until N) {
            val t = getTerrainAt(cell(x, y))
            terrain[idx(x, y)] = when (t) { TERRAIN_WALL -> WALL.toByte(); TERRAIN_SWAMP -> SWAMP.toByte(); else -> PLAIN.toByte() }
        }
        ready = true
    }

    fun at(x: Int, y: Int): Int = if (inside(x, y)) terrain[idx(x, y)].toInt() else WALL
    fun wall(x: Int, y: Int) = at(x, y) == WALL
    fun swamp(x: Int, y: Int) = at(x, y) == SWAMP

    private val cache = HashMap<String, IntArray>()

    /** Distance from every cell to the nearest of [goals] (cells as idx); a goal cell itself is 0. Cached by key. */
    fun field(key: String, goals: IntArray, swampCost: Int = 5): IntArray =
        cache.getOrPut("$key/$swampCost") { dijkstra(goals, swampCost) }

    fun to(x: Int, y: Int, swampCost: Int = 5): IntArray = field("c$x,$y", intArrayOf(idx(x, y)), swampCost)

    /** A field to every passable cell within Chebyshev [r] of (x, y): a group's goal is an area, not a cell. Toward a
     *  single cell a diagonal approach has exactly one neighbour that is nearer, so fourteen creeps walking to one
     *  cell form one diagonal file whose head stands on the goal and whose rest only trade places (v3, 27.09.2026). */
    fun area(x: Int, y: Int, r: Int, swampCost: Int = 5): IntArray {
        val goals = ArrayList<Int>()
        for (yy in y - r..y + r) for (xx in x - r..x + r) if (inside(xx, yy) && !wall(xx, yy)) goals.add(idx(xx, yy))
        return field("a$x,$y,$r", goals.toIntArray(), swampCost)
    }

    /** A field that is not cached — its goals change every tick (the enemy's creeps). */
    fun fresh(goals: IntArray, swampCost: Int = 5): IntArray = dijkstra(goals, swampCost)

    private val dx = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    private val dy = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

    private fun dijkstra(goals: IntArray, swampCost: Int): IntArray {
        val dist = IntArray(N * N) { INF }
        val heap = Heap()
        for (g in goals) { if (terrain[g].toInt() != WALL) { dist[g] = 0; heap.push(0, g) } }
        while (heap.size > 0) {
            val d = heap.topKey(); val i = heap.pop()
            if (d > dist[i]) continue
            val x = xOf(i); val y = yOf(i)
            // the cost is paid for ENTERING a cell, so the field of a goal is the cost to walk from a cell to it:
            // stepping from neighbour n into i costs the terrain of i
            val enter = if (terrain[i].toInt() == SWAMP) swampCost else 1
            for (k in 0 until 8) {
                val nx = x + dx[k]; val ny = y + dy[k]
                if (!inside(nx, ny)) continue
                val n = idx(nx, ny)
                if (terrain[n].toInt() == WALL) continue
                val nd = d + enter
                if (nd < dist[n]) { dist[n] = nd; heap.push(nd, n) }
            }
        }
        return dist
    }

    /** Cells of the 8-neighbourhood of (x, y) that are not walls. */
    fun neighbours(x: Int, y: Int): List<Int> {
        val out = ArrayList<Int>(8)
        for (k in 0 until 8) { val nx = x + dx[k]; val ny = y + dy[k]; if (inside(nx, ny) && !wall(nx, ny)) out.add(idx(nx, ny)) }
        return out
    }

    fun range(a: Int, b: Int) = maxOf(kotlin.math.abs(xOf(a) - xOf(b)), kotlin.math.abs(yOf(a) - yOf(b)))
    fun range(ax: Int, ay: Int, bx: Int, by: Int) = maxOf(kotlin.math.abs(ax - bx), kotlin.math.abs(ay - by))

    /** A binary min-heap of (key, value) pairs. */
    private class Heap {
        private var keys = IntArray(1024); private var vals = IntArray(1024)
        var size = 0
        fun push(k: Int, v: Int) {
            if (size == keys.size) { keys = keys.copyOf(size * 2); vals = vals.copyOf(size * 2) }
            var i = size++
            keys[i] = k; vals[i] = v
            while (i > 0) { val p = (i - 1) / 2; if (keys[p] <= keys[i]) break; swap(i, p); i = p }
        }
        fun topKey() = keys[0]
        fun pop(): Int {
            val v = vals[0]
            size--
            if (size > 0) {
                keys[0] = keys[size]; vals[0] = vals[size]
                var i = 0
                while (true) {
                    val l = 2 * i + 1; val r = l + 1; var m = i
                    if (l < size && keys[l] < keys[m]) m = l
                    if (r < size && keys[r] < keys[m]) m = r
                    if (m == i) break
                    swap(i, m); i = m
                }
            }
            return v
        }
        private fun swap(a: Int, b: Int) { val k = keys[a]; keys[a] = keys[b]; keys[b] = k; val v = vals[a]; vals[a] = vals[b]; vals[b] = v }
    }
}
