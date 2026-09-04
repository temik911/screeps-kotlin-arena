package season4.escortrun

import screeps.api.Position
import screeps.api.TERRAIN_SWAMP
import screeps.api.TERRAIN_WALL
import screeps.api.getTerrainAt

/**
 * Местность и поля потока (копия из season4/painandgain по правилу «копировать, не делить»; входы в базы и
 * рампарты Pain and Gain здесь не нужны и убраны).
 *
 * Поле потока — стоимости пути до цели от каждой клетки: равнина 1, болото swampCost, стены −1. Крип спускается
 * по градиенту и гарантированно огибает любые преграды; searchPath на длинном обходе может упереться в стену.
 * Цена болота задаётся ВЫЗЫВАЮЩИМ по телу крипа: эскорт (период 4 на равнине, 20 на болоте) платит за болото
 * впятеро, пустой MOVE-тягач — столько же, сколько за равнину.
 */
object DistanceMap {

    private const val FIELD = 100

    private fun index(x: Int, y: Int) = x * FIELD + y

    private var terrainWalls: BooleanArray? = null
    private var swampCells: BooleanArray? = null

    /** Статичные преграды: стены местности плюс структуры, переданные в syncStructures (спавны, стены, башни). */
    private var staticBlocked: BooleanArray? = null
    private var structureSignature = -1

    /** Местность читается ОДИН раз одним проходом (стены и болото вместе): getTerrainAt — вызов через границу
     *  изолята, и три отдельных прохода по 10 000 клеток на первом тике упирались в лимит (Pain and Gain, матч 4). */
    private fun scanTerrain() {
        if (terrainWalls != null && swampCells != null) return
        val walls = BooleanArray(FIELD * FIELD)
        val swamp = BooleanArray(FIELD * FIELD)
        for (x in 0 until FIELD) {
            for (y in 0 until FIELD) {
                val t = getTerrainAt(InfluenceMap.cell(x, y))
                if (t == TERRAIN_WALL) walls[index(x, y)] = true
                else if (t == TERRAIN_SWAMP) swamp[index(x, y)] = true
            }
        }
        terrainWalls = walls
        swampCells = swamp
    }

    /** Непроходимые структуры (спавны, стены, башни, экстеншены) — подпись по позициям; при смене — пересбор. */
    fun syncStructures(structures: List<Position>) {
        val signature = structures.sumOf { it.x * 100 + it.y + 1 } * 31 + structures.size
        if (signature == structureSignature && staticBlocked != null) return
        structureSignature = signature
        scanTerrain()
        val block = terrainWalls!!.copyOf()
        for (s in structures) if (inBounds(s.x, s.y)) block[index(s.x, s.y)] = true
        staticBlocked = block
    }

    private fun ensureStaticBlocked() {
        if (staticBlocked != null) return
        scanTerrain()
        staticBlocked = terrainWalls!!.copyOf()
    }

    /** true, если клетка — стена (местность + непроходимые структуры). За границей — стена. */
    fun isWall(x: Int, y: Int): Boolean {
        ensureStaticBlocked()
        if (!inBounds(x, y)) return true
        return staticBlocked!![index(x, y)]
    }

    /** Стена ПО МЕСТНОСТИ (без структур) — для карты в журнал и проходимости соседних клеток. */
    fun isTerrainWall(x: Int, y: Int): Boolean {
        scanTerrain()
        return !inBounds(x, y) || terrainWalls!![index(x, y)]
    }

    /** true, если клетка — болото. */
    fun isSwamp(x: Int, y: Int): Boolean {
        if (!inBounds(x, y)) return false
        scanTerrain()
        return swampCells!![index(x, y)]
    }

    fun inBounds(x: Int, y: Int) = x in 0 until FIELD && y in 0 until FIELD

    /**
     * Поле потока до цели: стоимость пути (равнина 1, болото swampCost) с учётом стен и дополнительных преград.
     * maxDist — предел стоимости: дальше клетки остаются −1 (ограниченное поле «вблизи» для целей в нескольких
     * клетках — полный обход 10 000 клеток на каждую из дюжины целей стоил тика в гуще боя).
     */
    fun flowFieldTo(target: Position, extraBlocked: List<Position>, swampCost: Int, maxDist: Int = Int.MAX_VALUE): IntArray {
        ensureStaticBlocked()
        val block = staticBlocked!!.copyOf()
        for (p in extraBlocked) if (inBounds(p.x, p.y)) block[index(p.x, p.y)] = true
        if (inBounds(target.x, target.y)) block[index(target.x, target.y)] = false // цель всегда достижима
        return dial(target.x, target.y, block, maxOf(1, swampCost), maxDist)
    }

    /** Поле до НЕСКОЛЬКИХ целей сразу (ближайшая из них). */
    fun flowFieldToAny(targets: List<Position>, extraBlocked: List<Position>, swampCost: Int, maxDist: Int = Int.MAX_VALUE): IntArray {
        ensureStaticBlocked()
        val block = staticBlocked!!.copyOf()
        for (p in extraBlocked) if (inBounds(p.x, p.y)) block[index(p.x, p.y)] = true
        for (t in targets) if (inBounds(t.x, t.y)) block[index(t.x, t.y)] = false
        return dialMulti(targets, block, maxOf(1, swampCost), maxDist)
    }

    /**
     * Следующий шаг по полю потока — соседняя клетка, что ближе к цели. Среди приближающих предпочитает
     * СВОБОДНУЮ (не занятую своим), чтобы задние обтекали передних; клетки врагов непроходимы вовсе.
     * null — крип уже в пределах stopRange от цели или поле пустое.
     */
    fun flowStep(flow: IntArray, x: Int, y: Int, stopRange: Int, occupied: Set<Int>, enemyPositions: Set<Int>): Position? {
        if (!inBounds(x, y)) return null
        val here = flow[index(x, y)]
        if (here in 0..stopRange) return null
        if (here < 0) return null

        var freeDist = Int.MAX_VALUE
        var freeX = -1
        var freeY = -1
        var anyDist = Int.MAX_VALUE
        var anyX = -1
        var anyY = -1
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (!inBounds(nx, ny)) continue
                val ni = index(nx, ny)
                if (ni in enemyPositions) continue
                val d = flow[ni]
                if (d < 0 || d >= here) continue
                if (d < anyDist) { anyDist = d; anyX = nx; anyY = ny }
                if (ni !in occupied && d < freeDist) { freeDist = d; freeX = nx; freeY = ny }
            }
        }
        return when {
            freeX >= 0 -> InfluenceMap.cell(freeX, freeY)
            anyX >= 0 -> InfluenceMap.cell(anyX, anyY)
            else -> null
        }
    }

    /**
     * Волновой обход с целочисленной ценой местности (8 направлений): Дейкстра кольцевыми корзинами (Dial) —
     * цены целые и малые, куча не нужна. Расстояние −1 = недостижимо.
     */
    private fun dial(startX: Int, startY: Int, blocked: BooleanArray, swampCost: Int, maxDist: Int): IntArray =
        dialMulti(listOf(InfluenceMap.cell(startX, startY)), blocked, swampCost, maxDist)

    private fun dialMulti(starts: List<Position>, blocked: BooleanArray, swampCost: Int, maxDist: Int): IntArray {
        val dist = IntArray(FIELD * FIELD) { -1 }
        scanTerrain()
        val swamp = swampCells!!
        val buckets = Array(swampCost + 1) { ArrayDeque<Int>() }
        var queued = 0
        for (s in starts) {
            if (!inBounds(s.x, s.y)) continue
            val si = index(s.x, s.y)
            if (dist[si] == 0) continue
            dist[si] = 0
            buckets[0].addLast(si)
            queued++
        }
        var current = 0

        while (queued > 0) {
            val bucket = buckets[current % (swampCost + 1)]
            if (bucket.isEmpty()) {
                current++
                continue
            }
            val cell = bucket.removeFirst()
            queued--
            if (dist[cell] != current) continue // устаревшая запись — нашли путь дешевле

            val cx = cell / FIELD
            val cy = cell % FIELD
            for (dx in -1..1) {
                for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx
                    val ny = cy + dy
                    if (!inBounds(nx, ny)) continue
                    val ni = index(nx, ny)
                    if (blocked[ni]) continue
                    val next = current + if (swamp[ni]) swampCost else 1
                    if (next > maxDist) continue
                    if (dist[ni] < 0 || next < dist[ni]) {
                        dist[ni] = next
                        buckets[next % (swampCost + 1)].addLast(ni)
                        queued++
                    }
                }
            }
        }
        return dist
    }
}
