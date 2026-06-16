package season3.powersplit

import screeps.api.Position
import screeps.api.TERRAIN_SWAMP
import screeps.api.TERRAIN_WALL
import screeps.api.getObjectsByPrototype
import screeps.api.getTerrainAt
import screeps.api.structures.StructureRampart
import screeps.api.structures.StructureWall

/**
 * Карты расстояний по реальной достижимости (КОПИЯ из season3.spawnstrike, адаптирована под Power
 * Split). Ключевое отличие spawn-страйка: BFS ВЗВЕШЕН по местности — болото стоит SWAMP_COST тиков,
 * равнина 1 (Дейкстра кольцевыми корзинами). Поля потока меряют путь в ТИКАХ, поэтому маршруты сами
 * огибают болото, где тело 1:1 ходит раз в 5 тиков.
 *
 * Адаптация под нашу карту: W-стены ЛОМАЕМЫЕ (в spawn-страйке статичны) — поэтому набор стен
 * обновляется каждый тик (refresh) и входит в подпись перестройки карт.
 */
object DistanceMap {

    private const val FIELD = 100

    /** Цена шага НА болото в тиках: тела с MOVE 1:1 идут по болоту в 5 раз дольше. */
    private const val SWAMP_COST = 5
    /** Условная цена «прорыва» сквозь ломаемую W-стену / чужой рампарт в поле прорыва (ходов на пролом). */
    private const val WALL_PUSH_COST = 25
    private const val RAMPART_PUSH_COST = 15

    private fun index(x: Int, y: Int) = x * FIELD + y
    private fun inBounds(x: Int, y: Int) = x in 0 until FIELD && y in 0 until FIELD

    // --- СТАТИКА (terrain) — сканируем один раз ---
    private var terrainBlocked: BooleanArray? = null
    private var swampCells: BooleanArray? = null

    // --- ДИНАМИКА: ломаемые W-стены — набор обновляем каждый тик ---
    private var wallSet: Set<Int> = emptySet()

    /** Раз за тик: обновляем набор стоящих W-стен (они ломаются). Вызывать до запросов карт. */
    fun refresh() {
        ensureStatic()
        wallSet = getObjectsByPrototype(StructureWall::class).filter { it.exists }.mapTo(HashSet()) { it.x * 100 + it.y }
    }

    private fun ensureStatic() {
        if (terrainBlocked != null) return
        val tb = BooleanArray(FIELD * FIELD)
        val sw = BooleanArray(FIELD * FIELD)
        for (x in 0 until FIELD) for (y in 0 until FIELD) {
            val t = getTerrainAt(InfluenceMap.cell(x, y))
            if (t == TERRAIN_WALL) tb[index(x, y)] = true
            if (t == TERRAIN_SWAMP) sw[index(x, y)] = true
        }
        terrainBlocked = tb
        swampCells = sw
    }

    /** true, если клетка — стена (terrain ИЛИ стоящая StructureWall). За границей — стена. */
    fun isWall(x: Int, y: Int): Boolean {
        ensureStatic()
        if (!inBounds(x, y)) return true
        return terrainBlocked!![index(x, y)] || (x * 100 + y) in wallSet
    }

    /** true, если клетка — болото (для боевого скоринга: на болоте кайт невозможен). */
    fun isSwamp(x: Int, y: Int): Boolean {
        ensureStatic()
        if (!inBounds(x, y)) return false
        return swampCells!![index(x, y)]
    }

    /** Базовый блок (terrain + текущие W-стены) — копия, в неё дописываем рампарты/доп.преграды. */
    private fun baseBlocked(): BooleanArray {
        ensureStatic()
        val b = terrainBlocked!!.copyOf()
        for (k in wallSet) b[k] = true
        return b
    }

    // --- карты расстояний от обоих спавнов (по достижимости, болото взвешено) ---
    private var distFromMy: IntArray? = null
    private var distFromEnemy: IntArray? = null
    private var rampartSignature = -1
    // координаты спавнов — для геометрического тай-брейка в inOurHalf, когда враг клетку не достаёт
    private var mySpawnX = -1; private var mySpawnY = -1
    private var enemySpawnX = -1; private var enemySpawnY = -1

    private fun chebyshev(ax: Int, ay: Int, bx: Int, by: Int) = maxOf(kotlin.math.abs(ax - bx), kotlin.math.abs(ay - by))

    /**
     * Строит карты расстояний от обоих спавнов. Проходимость раздельная: нам непроходимы чужие и
     * нейтральные рампарты, врагу — наши и нейтральные. Перестраиваем при смене подписи (рампарты ИЛИ
     * стены: на Power Split стены ломаются, пролом ворот меняет достижимость).
     */
    fun ensureBuilt(mySpawn: Position, enemySpawn: Position) {
        ensureStatic()
        mySpawnX = mySpawn.x; mySpawnY = mySpawn.y
        enemySpawnX = enemySpawn.x; enemySpawnY = enemySpawn.y
        val ramparts = getObjectsByPrototype(StructureRampart::class).filter { it.exists }
        var signature = wallSet.fold(0) { a, k -> a + k * 11 }
        signature += ramparts.sumOf { (it.x * 100 + it.y) * if (it.my == true) 3 else if (it.my == false) 5 else 7 }
        if (distFromMy != null && signature == rampartSignature) return
        rampartSignature = signature

        val myBlocked = baseBlocked()
        val enemyBlocked = baseBlocked()
        for (rampart in ramparts) {
            if (!inBounds(rampart.x, rampart.y)) continue
            if (rampart.my != true) myBlocked[index(rampart.x, rampart.y)] = true
            if (rampart.my != false) enemyBlocked[index(rampart.x, rampart.y)] = true
        }
        distFromMy = bfs(mySpawn.x, mySpawn.y, myBlocked)
        distFromEnemy = bfs(enemySpawn.x, enemySpawn.y, enemyBlocked)
    }

    /** true, если до клетки мы добираемся не позже врага (наша половина по достижимости, в тиках). */
    fun inOurHalf(x: Int, y: Int): Boolean {
        val my = distFromMy ?: return false
        val enemy = distFromEnemy ?: return false
        if (!inBounds(x, y)) return false
        val a = my[index(x, y)]
        val b = enemy[index(x, y)]
        return when {
            a < 0 -> false
            // враг не достаёт клетку (b<0): НЕ метим её «нашей» автоматически — пока центральные стены
            // целы, BFS врага не доходит до дальних углов (в т.ч. у ЕГО базы), и они ложно считались
            // нашими, плодя фантомных «интрудеров». Решаем геометрией: чей спавн ближе по чебышеву.
            b < 0 -> chebyshev(x, y, mySpawnX, mySpawnY) <= chebyshev(x, y, enemySpawnX, enemySpawnY)
            else -> a <= b
        }
    }

    /** Реальное расстояние в тиках от нашего спавна до клетки (MAX_VALUE — недостижимо/не построено). */
    fun stepsFromMy(x: Int, y: Int): Int {
        val my = distFromMy ?: return Int.MAX_VALUE
        if (!inBounds(x, y)) return Int.MAX_VALUE
        val d = my[index(x, y)]
        return if (d < 0) Int.MAX_VALUE else d
    }

    // --- «карман» спавна (сухая земля без болота/стен) — для ранней обороны у горловин ---
    private var baseZone: BooleanArray? = null

    /** Карман спавна: клетки, достижимые от спавна по СУХОЙ земле (без болота/стен/чужих рампартов). */
    fun ensureBaseZone(spawn: Position) {
        if (baseZone != null) return
        val block = baseBlocked()
        val swamp = swampCells!!
        for (i in block.indices) if (swamp[i]) block[i] = true
        getObjectsByPrototype(StructureRampart::class).forEach { if (it.my != true && inBounds(it.x, it.y)) block[index(it.x, it.y)] = true }
        val dist = bfs(spawn.x, spawn.y, block)
        baseZone = BooleanArray(FIELD * FIELD) { dist[it] >= 0 }
    }

    /** true, если клетка внутри кармана спавна (или карман не построен — тогда не ограничиваем). */
    fun inBaseZone(x: Int, y: Int): Boolean {
        val zone = baseZone ?: return true
        return inBounds(x, y) && zone[index(x, y)]
    }

    /**
     * Поле потока (BFS-расстояния в тиках до цели) с учётом стен + доп.преград (чужих рампартов).
     * По нему крип спускается по градиенту к цели, гарантированно огибая препятствия и болото.
     */
    fun flowFieldTo(target: Position, extraBlocked: List<Position>): IntArray {
        val block = baseBlocked()
        for (p in extraBlocked) if (inBounds(p.x, p.y)) block[index(p.x, p.y)] = true
        if (inBounds(target.x, target.y)) block[index(target.x, target.y)] = false // цель всегда достижима
        return bfs(target.x, target.y, block)
    }

    /**
     * ПОЛЕ ПРОРЫВА к target: ломаемые W-стены и ЧУЖИЕ рампарты проходимы по высокой цене (WALL/RAMPART_
     * PUSH_COST — условный «пролом»), terrain-стены непроходимы; опасность (dangerField, урон башен по
     * клеткам) добавляется к цене шага. Так путь либо обходит зоны башен, либо ТОННЕЛИТ сквозь стены
     * (новый проход), где это дешевле. Взвешенная Дейкстра кольцевыми корзинами (Dial). dangerField —
     * пакет x*100+y → доп.цена (>=0). Возвращает поле стоимостей (−1 = недостижимо).
     */
    fun pushFieldTo(target: Position, dangerField: IntArray): IntArray {
        ensureStatic()
        val dist = IntArray(FIELD * FIELD) { -1 }
        if (!inBounds(target.x, target.y)) return dist
        val terrain = terrainBlocked!!
        val swamp = swampCells!!
        val enemyRamp = getObjectsByPrototype(StructureRampart::class)
            .filter { it.exists && it.my == false }.mapTo(HashSet()) { it.x * 100 + it.y }
        var maxDanger = 0
        for (d in dangerField) if (d > maxDanger) maxDanger = d
        val ring = maxOf(WALL_PUSH_COST, RAMPART_PUSH_COST, SWAMP_COST) + maxDanger + 1
        val buckets = Array(ring) { ArrayDeque<Int>() }
        val ti = index(target.x, target.y)
        dist[ti] = 0
        buckets[0].addLast(ti)
        var current = 0; var queued = 1; var guard = 0
        val guardMax = ring * FIELD * FIELD
        while (queued > 0) {
            val bucket = buckets[current % ring]
            if (bucket.isEmpty()) { current++; if (++guard > guardMax) break; continue }
            val cell = bucket.removeFirst(); queued--
            if (dist[cell] != current) continue // устаревшая запись
            val cx = cell / FIELD; val cy = cell % FIELD
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx; val ny = cy + dy
                if (!inBounds(nx, ny)) continue
                val ni = index(nx, ny)
                if (terrain[ni]) continue // terrain-стена — непроходима
                val packed = nx * 100 + ny
                val base = when {
                    packed in wallSet -> WALL_PUSH_COST       // ломаемая W-стена
                    packed in enemyRamp -> RAMPART_PUSH_COST  // чужой рампарт
                    swamp[ni] -> SWAMP_COST
                    else -> 1
                }
                val next = current + base + dangerField[ni]
                if (dist[ni] < 0 || next < dist[ni]) {
                    dist[ni] = next
                    buckets[next % ring].addLast(ni)
                    queued++
                }
            }
        }
        return dist
    }

    /**
     * Следующий шаг по полю потока — соседняя клетка, ближе к цели; среди приближающих предпочитает
     * СВОБОДНУЮ (не занятую своим) → задние обтекают передних, отряд веером. Возвращает null, если уже
     * в пределах stopRange или поле пустое.
     */
    fun flowStep(flow: IntArray, x: Int, y: Int, stopRange: Int, occupied: Set<Int>, enemyPositions: Set<Int>): Position? {
        if (!inBounds(x, y)) return null
        val here = flow[index(x, y)]
        if (here in 0..stopRange) return null
        if (here < 0) return null

        var freeDist = Int.MAX_VALUE; var freeX = -1; var freeY = -1
        var anyDist = Int.MAX_VALUE; var anyX = -1; var anyY = -1
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx; val ny = y + dy
            if (!inBounds(nx, ny)) continue
            val ni = index(nx, ny)
            if (ni in enemyPositions) continue
            val d = flow[ni]
            if (d < 0 || d >= here) continue
            if (d < anyDist) { anyDist = d; anyX = nx; anyY = ny }
            if (ni !in occupied && d < freeDist) { freeDist = d; freeX = nx; freeY = ny }
        }
        return when {
            freeX >= 0 -> InfluenceMap.cell(freeX, freeY)
            anyX >= 0 -> InfluenceMap.cell(anyX, anyY)
            else -> null
        }
    }

    /**
     * Волновой обход с ЦЕНОЙ местности (8 направлений): равнина 1, болото SWAMP_COST. Расстояние в
     * тиках, -1 = недостижимо. Дейкстра кольцевыми корзинами (Dial): цены целые и малые, куча не нужна.
     */
    private fun bfs(startX: Int, startY: Int, blocked: BooleanArray): IntArray {
        val dist = IntArray(FIELD * FIELD) { -1 }
        if (!inBounds(startX, startY)) return dist
        val swamp = swampCells!!
        val buckets = Array(SWAMP_COST + 1) { ArrayDeque<Int>() }
        dist[index(startX, startY)] = 0
        buckets[0].addLast(index(startX, startY))
        var current = 0
        var queued = 1

        while (queued > 0) {
            val bucket = buckets[current % (SWAMP_COST + 1)]
            if (bucket.isEmpty()) { current++; continue }
            val cell = bucket.removeFirst()
            queued--
            if (dist[cell] != current) continue // устаревшая запись — нашли путь дешевле

            val cx = cell / FIELD; val cy = cell % FIELD
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx; val ny = cy + dy
                if (!inBounds(nx, ny)) continue
                val ni = index(nx, ny)
                if (blocked[ni]) continue
                val next = current + if (swamp[ni]) SWAMP_COST else 1
                if (dist[ni] < 0 || next < dist[ni]) {
                    dist[ni] = next
                    buckets[next % (SWAMP_COST + 1)].addLast(ni)
                    queued++
                }
            }
        }
        return dist
    }
}
