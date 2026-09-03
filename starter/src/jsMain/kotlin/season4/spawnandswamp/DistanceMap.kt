package season4.spawnandswamp

import screeps.api.Position
import screeps.api.TERRAIN_SWAMP
import screeps.api.TERRAIN_WALL
import screeps.api.getObjectsByPrototype
import screeps.api.getTerrainAt
import screeps.api.structures.StructureRampart
import screeps.api.structures.StructureWall
import kotlin.math.abs

/**
 * Карты расстояний по реальной достижимости (BFS в шагах) от каждого спавна.
 * Проходимость у сторон РАЗНАЯ: рампарт пускает только владельца (владение даётся флагами и
 * меняется по ходу боя — карты перестраиваются при смене владельца любого рампарта).
 * Позволяет делить поле на половины по тому, кто быстрее доберётся до клетки (а не по прямой линии).
 */
object DistanceMap {

    private const val FIELD = 100

    private var distFromMy: IntArray? = null
    private var distFromEnemy: IntArray? = null

    /** Подпись владения рампартами при последней постройке карт (наши * 10000 + вражеские). */
    private var rampartSignature = -1

    /** Статичные преграды (terrain wall + StructureWall) — кэш. В Spawn and Swamp структурные стены
     *  ЛОМАЮТСЯ (пролом к запертому контейнеру) — syncWalls сбрасывает кэш при смене их числа. */
    private var staticBlocked: BooleanArray? = null
    private var wallCount = -1

    /** Число живых StructureWall изменилось — все поля по проходимости строятся заново. */
    fun syncWalls(count: Int) {
        if (count == wallCount) return
        wallCount = count
        staticBlocked = null
        rampartSignature = -1
        baseZone = null
        entrancesCache.clear()
        entranceEdges.clear()
    }

    /** «Карман» спавна — клетки, достижимые от спавна по сухой земле (без болота/стен/рампартов). */
    private var baseZone: BooleanArray? = null

    private fun index(x: Int, y: Int) = x * FIELD + y

    /**
     * Строит карты расстояний от обоих спавнов. Проходимость раздельная: для нас непроходимы
     * чужие и нейтральные рампарты, для врага — наши и нейтральные (рампартом пользуется только
     * владелец, право даётся флагами). Владение меняется по ходу боя, поэтому при смене
     * подписи владения карты перестраиваются (2 BFS — дёшево, происходит редко).
     */
    fun ensureBuilt(mySpawn: Position, enemySpawn: Position) {
        ensureStaticBlocked()
        val ramparts = getObjectsByPrototype(StructureRampart::class).filter { it.exists }
        // подпись чувствительна к ПОЗИЦИЯМ и владельцу каждого рампарта: простые счётчики
        // не замечали обмен владением двух рампартов в один тик (count не менялся)
        val signature = ramparts.sumOf { (it.x * 100 + it.y) * if (it.my == true) 3 else if (it.my == false) 5 else 7 }
        if (distFromMy != null && signature == rampartSignature) return
        rampartSignature = signature

        val myBlocked = staticBlocked!!.copyOf()
        val enemyBlocked = staticBlocked!!.copyOf()
        for (rampart in ramparts) {
            if (!inBounds(rampart.x, rampart.y)) continue
            if (rampart.my != true) myBlocked[index(rampart.x, rampart.y)] = true
            if (rampart.my != false) enemyBlocked[index(rampart.x, rampart.y)] = true
        }
        distFromMy = bfs(mySpawn.x, mySpawn.y, myBlocked)
        distFromEnemy = bfs(enemySpawn.x, enemySpawn.y, enemyBlocked)
    }

    /** true, если до клетки мы добираемся не позже врага (клетка в нашей половине по достижимости). */
    fun inOurHalf(x: Int, y: Int): Boolean {
        val my = distFromMy ?: return false
        val enemy = distFromEnemy ?: return false
        if (!inBounds(x, y)) return false
        val a = my[index(x, y)]
        val b = enemy[index(x, y)]
        return when {
            a < 0 -> false      // мы не достаём — не наша
            b < 0 -> true       // враг не достаёт, а мы достаём — наша
            else -> a <= b
        }
    }

    /**
     * Строит «карман» спавна: клетки, достижимые от спавна по сухой земле, не пересекая болото,
     * стены и рампарты. В раннем гейме обороны бойцы не выходят за этот карман — враг вязнет на
     * болоте по периметру, а мы расстреливаем его с края кармана.
     */
    fun ensureBaseZone(spawn: Position) {
        if (baseZone != null) return
        ensureStaticBlocked()
        val block = staticBlocked!!.copyOf()
        for (x in 0 until FIELD) {
            for (y in 0 until FIELD) {
                if (getTerrainAt(InfluenceMap.cell(x, y)) == TERRAIN_SWAMP) block[index(x, y)] = true
            }
        }
        getObjectsByPrototype(StructureRampart::class).forEach { if (it.my != true && inBounds(it.x, it.y)) block[index(it.x, it.y)] = true }
        val dist = bfs(spawn.x, spawn.y, block)
        baseZone = BooleanArray(FIELD * FIELD) { dist[it] >= 0 }
    }

    /** true, если клетка внутри кармана спавна (или карман не построен — тогда не ограничиваем). */
    fun inBaseZone(x: Int, y: Int): Boolean {
        val zone = baseZone ?: return true
        return inBounds(x, y) && zone[index(x, y)]
    }

    /** true, если клетка — стена (terrain wall + StructureWall; рампарты сюда НЕ входят). За границей — стена. */
    fun isWall(x: Int, y: Int): Boolean {
        ensureStaticBlocked()
        if (!inBounds(x, y)) return true
        return staticBlocked!![index(x, y)]
    }

    private fun ensureStaticBlocked() {
        if (staticBlocked != null) return
        val block = BooleanArray(FIELD * FIELD)
        for (x in 0 until FIELD) {
            for (y in 0 until FIELD) {
                if (getTerrainAt(InfluenceMap.cell(x, y)) == TERRAIN_WALL) block[index(x, y)] = true
            }
        }
        getObjectsByPrototype(StructureWall::class).forEach { if (inBounds(it.x, it.y)) block[index(it.x, it.y)] = true }
        staticBlocked = block
    }

    /**
     * Поле потока (BFS-расстояния до цели) с учётом стен и дополнительных преград (чужих рампартов).
     * По нему крип спускается по градиенту к цели, гарантированно огибая любые препятствия —
     * в отличие от searchPath, который на длинном обходе может упереться в стену.
     */
    fun flowFieldTo(target: Position, extraBlocked: List<Position>, swampCost: Int = SWAMP_COST): IntArray {
        ensureStaticBlocked()
        val block = staticBlocked!!.copyOf()
        for (p in extraBlocked) if (inBounds(p.x, p.y)) block[index(p.x, p.y)] = true
        if (inBounds(target.x, target.y)) block[index(target.x, target.y)] = false // цель всегда достижима
        return bfs(target.x, target.y, block, swampCost)
    }

    /** Поле в ШАГАХ (болото = равнина): пустой CARRY усталости не даёт, пустой хаулер идёт по болоту
     *  как по суше — его дорогу к энергии считаем этим полем, обратную (с грузом) — обычным. */
    fun stepFieldTo(target: Position, extraBlocked: List<Position>): IntArray = flowFieldTo(target, extraBlocked, 1)

    /**
     * Следующий шаг крипа по полю потока — соседняя клетка, что ближе к цели.
     * Среди приближающих клеток предпочитает СВОБОДНУЮ (не занятую своим крипом) — так задние
     * обтекают передних и отряд расходится веером, а не идёт цепочкой. occupied — позиции своих
     * (x*100+y); enemyPositions — клетки врагов, на них не шагаем вовсе (TrafficManager такой
     * request всё равно отклонит, и крип вечно бодал бы занятую врагом клетку).
     * Возвращает null, если крип уже в пределах stopRange от цели или поле пустое.
     */
    fun flowStep(flow: IntArray, x: Int, y: Int, stopRange: Int, occupied: Set<Int>, enemyPositions: Set<Int>): Position? {
        if (!inBounds(x, y)) return null
        val here = flow[index(x, y)]
        if (here in 0..stopRange) return null // уже достаточно близко — стоим и стреляем
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
                if (ni in enemyPositions) continue // клетка врага — непроходима
                val d = flow[ni]
                if (d < 0 || d >= here) continue // только клетки, приближающие к цели
                if (d < anyDist) { anyDist = d; anyX = nx; anyY = ny }
                if (ni !in occupied && d < freeDist) { freeDist = d; freeX = nx; freeY = ny }
            }
        }
        return when {
            freeX >= 0 -> InfluenceMap.cell(freeX, freeY) // свободная клетка ближе к цели — обтекаем
            anyX >= 0 -> InfluenceMap.cell(anyX, anyY) // все заняты своими — TrafficManager разрулит свапом
            else -> null
        }
    }

    /** Реальное расстояние в шагах от нашего спавна до клетки (MAX_VALUE — недостижимо/не построено). */
    fun stepsFromMy(x: Int, y: Int): Int {
        val my = distFromMy ?: return Int.MAX_VALUE
        if (!inBounds(x, y)) return Int.MAX_VALUE
        val d = my[index(x, y)]
        return if (d < 0) Int.MAX_VALUE else d
    }

    private fun inBounds(x: Int, y: Int) = x in 0 until FIELD && y in 0 until FIELD

    /** Цена шага НА болото в тиках: тела с MOVE 1:1 (пара, охотники) идут по болоту в 5 раз дольше.
     *  Без этого flow-поля вели крипов напрямик через болото, где они вязли, хотя рядом был обход. */
    const val SWAMP_COST = 5

    /** true, если клетка — болото (для боевого скоринга: на болоте кайт невозможен). */
    fun isSwamp(x: Int, y: Int): Boolean {
        if (!inBounds(x, y)) return false
        return ensureSwamp()[index(x, y)]
    }

    /** Радиус поиска входов вокруг вражеского спавна: боковые проходы пояса стен лежат дальше
     *  центрального (~25+ клеток от спавна по Чебышеву), 20 находило только центральный. */
    private const val ENTRANCE_SCAN = 30

    /** Входы в базы (кэш по стороне — terrain статичен). Каждый вход — массив упакованных клеток x*100+y. */
    private val entrancesCache = HashMap<Boolean, List<IntArray>>()

    /** Наружная кромка каждого входа (клетки, касающиеся сухой земли ВНЕ кармана) — по ссылке компоненты. */
    private val entranceEdges = HashMap<IntArray, IntArray>()

    /** Наружная кромка входа (заполняется при детекции). null — не вход/не найдено. */
    fun entranceOuterEdge(component: IntArray): IntArray? = entranceEdges[component]

    /** Входы во вражескую базу — см. baseEntrances. */
    fun enemyBaseEntrances(enemySpawn: Position): List<IntArray> = baseEntrances(enemySpawn, mine = false)

    /**
     * Входы в базу у спавна: проходы в поясе стен состоят из БОЛОТА и друг с другом
     * не соединены. Ищем их как компоненты связности болота (8-связность), примыкающие к «сухому
     * карману» спавна — области, достижимой от спавна по суше (болото, стены и непроходимые для
     * владельца рампарты не пересекаем). Каждая компонента в радиусе ENTRANCE_SCAN — один вход.
     * mine выбирает проходимость владельца кармана (наша/вражеская — рампарты пускают только хозяина).
     */
    fun baseEntrances(spawn: Position, mine: Boolean): List<IntArray> {
        entrancesCache[mine]?.let { return it }
        ensureStaticBlocked()
        val swamp = ensureSwamp()

        // сухой карман спавна — по проходимости ЕГО владельца (чужие/нейтральные рампарты блокируют)
        val block = staticBlocked!!.copyOf()
        for (i in block.indices) if (swamp[i]) block[i] = true
        getObjectsByPrototype(StructureRampart::class).forEach {
            if (it.my != mine && inBounds(it.x, it.y)) block[index(it.x, it.y)] = true
        }
        val pocket = bfs(spawn.x, spawn.y, block)

        fun touchesPocket(x: Int, y: Int): Boolean {
            for (dx in -1..1) {
                for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (inBounds(nx, ny) && pocket[index(nx, ny)] >= 0) return true
                }
            }
            return false
        }

        val visited = BooleanArray(FIELD * FIELD)
        val result = ArrayList<IntArray>()
        for (x in 0 until FIELD) {
            for (y in 0 until FIELD) {
                val start = index(x, y)
                if (!swamp[start] || visited[start]) continue
                if (maxOf(abs(x - spawn.x), abs(y - spawn.y)) > ENTRANCE_SCAN) continue
                if (!touchesPocket(x, y)) continue

                // флад-филл всей болотной компоненты (в пределах радиуса поиска)
                val component = ArrayList<Int>()
                val queue = ArrayDeque<Int>()
                visited[start] = true
                queue.addLast(start)
                while (queue.isNotEmpty()) {
                    val cell = queue.removeFirst()
                    component.add(cell)
                    val cx = cell / FIELD
                    val cy = cell % FIELD
                    for (dx in -1..1) {
                        for (dy in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx
                            val ny = cy + dy
                            if (!inBounds(nx, ny)) continue
                            if (maxOf(abs(nx - spawn.x), abs(ny - spawn.y)) > ENTRANCE_SCAN) continue
                            val ni = index(nx, ny)
                            if (visited[ni] || !swamp[ni]) continue
                            visited[ni] = true
                            queue.addLast(ni)
                        }
                    }
                }

                // вход обязан СКВОЗИТЬ: кроме кармана касаться сухой проходимой земли ВНЕ кармана.
                // Болотная лужа-тупик внутри базы касается только кармана — это не вход, отсеиваем.
                // Попутно собираем НАРУЖНУЮ КРОМКУ входа — она нужна осаде (стоять по её середине).
                val edge = ArrayList<Int>()
                for (cell in component) {
                    val cellX = cell / FIELD
                    val cellY = cell % FIELD
                    var touchesOutside = false
                    loop@ for (dx in -1..1) {
                        for (dy in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cellX + dx
                            val ny = cellY + dy
                            if (!inBounds(nx, ny)) continue
                            val ni = index(nx, ny)
                            if (!block[ni] && pocket[ni] < 0) {
                                touchesOutside = true
                                break@loop
                            }
                        }
                    }
                    if (touchesOutside) edge.add(cell)
                }
                if (edge.isNotEmpty()) {
                    val packed = component.toIntArray()
                    result.add(packed)
                    entranceEdges[packed] = edge.toIntArray()
                }
            }
        }
        entrancesCache[mine] = result
        return result
    }

    /** Кэш болот (terrain статичен). */
    private var swampCells: BooleanArray? = null

    private fun ensureSwamp(): BooleanArray {
        swampCells?.let { return it }
        val swamp = BooleanArray(FIELD * FIELD)
        for (x in 0 until FIELD) {
            for (y in 0 until FIELD) {
                if (getTerrainAt(InfluenceMap.cell(x, y)) == TERRAIN_SWAMP) swamp[index(x, y)] = true
            }
        }
        swampCells = swamp
        return swamp
    }

    /**
     * Волновой обход с ЦЕНОЙ местности (8 направлений — крипы ходят и по диагонали): равнина 1,
     * болото SWAMP_COST. Расстояние — в тиках пути, -1 = недостижимо. Дейкстра кольцевыми
     * корзинами (Dial): цены целые и малые, куча не нужна.
     */
    private fun bfs(startX: Int, startY: Int, blocked: BooleanArray, swampCost: Int = SWAMP_COST): IntArray {
        val dist = IntArray(FIELD * FIELD) { -1 }
        if (!inBounds(startX, startY)) return dist
        val swamp = ensureSwamp()
        val buckets = Array(swampCost + 1) { ArrayDeque<Int>() }
        dist[index(startX, startY)] = 0
        buckets[0].addLast(index(startX, startY))
        var current = 0
        var queued = 1

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
