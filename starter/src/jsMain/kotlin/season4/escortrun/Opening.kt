package season4.escortrun

/**
 * Прогон дебюта поезда: какие тягачи (сколько MOVE в каком порядке) приводят эскорт к флагу раньше всего.
 *
 * Модель — та, что измерена на реплеях соперников (27.09.2026) и сверена с движком (`movement.js`, `_add-fatigue.js`,
 * `tick.js:105-107`):
 *  - эскорт — ГОЛОВА поезда: шагает, когда его усталость в начале тика ноль, и платит вес × (2 равнина / 10 болото);
 *  - в конце тика усталость гасят 2 × (его MOVE + MOVE всех тягачей в цепи позади него): их избыток уходит голове;
 *  - **спавн-пул**: пока наш спавн рождает тягача, а эскорт стоит вплотную к спавну, эскорт тянет РОЖДАЮЩЕГОСЯ, и
 *    его MOVE тоже гасят усталость эскорта (ряд усталости 76561198870429455#24/#28/#29: 60, 20, 0, 40, 0, 40 — до
 *    выхода из соседства спавна, потом 60, 40, 20, 0);
 *  - тягач рождается через 3 тика на часть, идёт клетку в тик (вес ноль) и встаёт в хвост цепи, догнав её.
 * Маршрут берётся готовый — клетки, которыми пойдёт сам эскорт (см. EscortRun.routeCells).
 */
internal object Opening {

    class Cell(val x: Int, val y: Int, val swamp: Boolean)

    class Result(val orders: List<Int>, val arrival: Int, val joinTicks: List<Int>) {
        override fun toString() = "M" + orders.joinToString("+M") + " arrival=$arrival joins=" + joinTicks.joinToString(",")
    }

    private class Puller(val moves: Int, val bornAt: Int) {
        var x = 0; var y = 0
        var joined = false
        var joinTick = -1
    }

    /**
     * @param route клетки пути эскорта от следующей до флага включительно
     * @param startTick тик, с которого считаем (1 — дебют)
     * @param energy энергия спавна сейчас; regen — прирост за тик
     * @param orders MOVE каждого заказанного тягача по порядку
     */
    fun simulate(
        startX: Int, startY: Int, escortWeight: Int, escortMoves: Int, escortFatigue: Int,
        route: List<Cell>, spawnX: Int, spawnY: Int, startTick: Int, energy: Int, regen: Double,
        orders: List<Int>, moveCost: Int, horizon: Int = 1500,
    ): Result {
        var ex = startX; var ey = startY
        var f = escortFatigue
        var i = 0
        var e = energy.toDouble()
        var next = 0
        var spawnFreeAt = startTick
        var spawningMoves = 0 // MOVE рождающегося сейчас тягача (0 — спавн свободен)
        var spawningUntil = -1
        val pullers = ArrayList<Puller>()
        var t = startTick
        while (t < startTick + horizon) {
            // заказ: спавн свободен и денег хватает
            if (next < orders.size && t >= spawnFreeAt && e >= orders[next] * moveCost) {
                e -= orders[next] * moveCost
                val need = orders[next] * 3
                spawningMoves = orders[next]
                spawningUntil = t + need
                spawnFreeAt = t + need
                pullers.add(Puller(orders[next], t + need).also { it.x = spawnX + 1; it.y = spawnY })
                next++
            }
            // рождённые идут к хвосту цепи; догнавший встаёт в цепь (со следующего тика он буксируемый)
            val tail = pullers.filter { it.joined }.lastOrNull()
            val tx = tail?.x ?: ex
            val ty = tail?.y ?: ey
            for (p in pullers) {
                if (p.joined || t < p.bornAt) continue
                val d = maxOf(kotlin.math.abs(p.x - tx), kotlin.math.abs(p.y - ty))
                if (d <= 1) { p.joined = true; p.joinTick = t; continue }
                p.x += sign(tx - p.x); p.y += sign(ty - p.y)
            }
            val chainMoves = pullers.filter { it.joined }.sumOf { it.moves }
            // спавн-пул — только пока цепи нет: эскорт тянет ОДНОГО, и тягач в цепи ценнее рождающегося
            val nearSpawn = maxOf(kotlin.math.abs(ex - spawnX), kotlin.math.abs(ey - spawnY)) <= 1
            val spawnPull = if (chainMoves == 0 && nearSpawn && spawningMoves > 0 && t > spawningUntil - spawningMoves * 3 && t < spawningUntil) spawningMoves else 0
            if (f == 0 && i < route.size) {
                val c = route[i]
                // цепь идёт следом: каждый тягач — в клетку переднего
                val joined = pullers.filter { it.joined }
                for (k in joined.indices.reversed()) {
                    if (k == 0) { joined[k].x = ex; joined[k].y = ey } else { joined[k].x = joined[k - 1].x; joined[k].y = joined[k - 1].y }
                }
                ex = c.x; ey = c.y
                f += escortWeight * (if (c.swamp) 10 else 2)
                i++
                if (i == route.size) return Result(orders, t, pullers.map { it.joinTick })
            }
            f = maxOf(0, f - 2 * (escortMoves + chainMoves + spawnPull))
            if (t >= spawningUntil) spawningMoves = 0
            e += regen
            t++
        }
        return Result(orders, Int.MAX_VALUE / 4, pullers.map { it.joinTick })
    }

    private fun sign(v: Int) = if (v > 0) 1 else if (v < 0) -1 else 0

    /** Разбиения `total` MOVE на 1..3 тягача с минимумом `minPart` в каждом — все порядки. */
    fun splits(total: Int, minPart: Int): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        out.add(listOf(total))
        for (a in minPart..total - minPart) out.add(listOf(a, total - a))
        for (a in minPart..total - 2 * minPart) for (b in minPart..total - a - minPart) out.add(listOf(a, b, total - a - b))
        return out
    }
}
