package season4.escortrun

import screeps.api.Position

/**
 * Узкие места маршрута поезда: где один крип на клетке стоит поезду дороже всего (docs/escort-run-redteam.md).
 *
 * Поезд идёт с периодом 2 по равнине и 10 по болоту (вес 40, ΣMOVE 20), и чужой крип на его клетке заставляет
 * обходить — часто через болото вокруг узкого прохода. Замер по 120 маршрутам живых карт: лучшая клетка стоит поезду
 * медианно 18 тиков, две — 36, и лежит она в 30–36 клетках от флага, на выходе из центра. В лиге 27.09.2026 один M1,
 * купленный после дебюта и перебегавший вперёд поезда, обыграл v19 четыре раза из четырёх.
 */
internal object Chokes {

    /** Цена шага поезда на клетку (ключ x*100+y): 2 равнина, 10 болото. */
    fun cost(k: Int) = if (DistanceMap.isSwamp(k / 100, k % 100)) 10 else 2

    /** Клетки пути по полю от `from` (не включая) до цели — спуском по убыванию. */
    fun route(flow: IntArray, from: Position): List<Int> {
        val out = ArrayList<Int>()
        var cell = from.x * 100 + from.y
        if (cell !in flow.indices) return out
        var guard = 0
        while (flow[cell] > 0 && guard++ < 400) {
            val cx = cell / 100; val cy = cell % 100
            var best = -1
            var bestD = flow[cell]
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val x = cx + dx; val y = cy + dy
                if (!DistanceMap.inBounds(x, y)) continue
                val d = flow[x * 100 + y]
                if (d in 0 until bestD) { bestD = d; best = x * 100 + y }
            }
            if (best < 0) break
            out.add(best)
            cell = best
        }
        return out
    }

    /**
     * Цена обхода клетки route[i] поездом: кратчайший путь в окне 11×11 от `prev` (клетка перед ней) до одной из
     * route[i+1..i+4] в обход route[i], против того же отрезка по маршруту. Обхода в окне нет — 60.
     */
    fun penalty(route: List<Int>, i: Int, prev: Int): Int {
        val c = route[i]
        val cx = c / 100; val cy = c % 100
        val r = 5
        val dist = HashMap<Int, Int>()
        val open = ArrayList<Pair<Int, Int>>()
        dist[prev] = 0; open.add(0 to prev)
        while (open.isNotEmpty()) {
            var bi = 0
            for (j in open.indices) if (open[j].first < open[bi].first) bi = j
            val (d, k) = open.removeAt(bi)
            if (d > (dist[k] ?: Int.MAX_VALUE)) continue
            val kx = k / 100; val ky = k % 100
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val x = kx + dx; val y = ky + dy
                if (kotlin.math.abs(x - cx) > r || kotlin.math.abs(y - cy) > r) continue
                if (DistanceMap.isWall(x, y)) continue
                val n = x * 100 + y
                if (n == c) continue
                val nd = d + cost(n)
                if (nd < (dist[n] ?: Int.MAX_VALUE)) { dist[n] = nd; open.add(nd to n) }
            }
        }
        var best = 60
        var along = cost(c)
        for (k in 1..4) {
            if (i + k >= route.size) break
            along += cost(route[i + k])
            val d = dist[route[i + k]] ?: continue
            best = minOf(best, d - along)
        }
        return maxOf(0, best)
    }

    class Pick(val cell: Int, val penalty: Int, val theirEta: Int)

    /**
     * Лучшая клетка их маршрута для нашего блокировщика: наибольшая цена обхода среди клеток, куда мы успеваем раньше
     * поезда (`ourEta` < тиков поезда до неё); при равной — та, что поезд встретит раньше. null — ни одной с ценой ≥ minPen.
     */
    fun best(route: List<Int>, start: Int, ourEta: (Int) -> Int, exclude: (Int) -> Boolean, minPen: Int): Pick? {
        var best: Pick? = null
        var theirEta = 0
        var prev = start
        for (i in route.indices) {
            val k = route[i]
            theirEta += cost(k)
            if (i >= 1 && !exclude(k) && ourEta(k) < theirEta) {
                val p = penalty(route, i, prev)
                if (p >= minPen && (best == null || p > best.penalty || (p == best.penalty && theirEta < best.theirEta))) best = Pick(k, p, theirEta)
            }
            prev = k
        }
        return best
    }
}
