package season4.painandgain

import kotlin.math.abs
import screeps.api.Creep
import screeps.api.Position
import screeps.api.getRange
import screeps.api.ATTACK
import screeps.api.ATTACK_POWER
import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.CARRY_CAPACITY
import screeps.api.CostMatrix
import screeps.api.EFF_ATTACK_MODIFIER
import screeps.api.EFF_DAMAGE_TAKEN_MODIFIER
import screeps.api.EFF_HEAL_MODIFIER
import screeps.api.EFF_RANGED_ATTACK_MODIFIER
import screeps.api.HEAL
import screeps.api.HEAL_POWER
import screeps.api.MOVE
import screeps.api.RANGED_ATTACK
import screeps.api.RANGED_ATTACK_POWER
import screeps.api.RANGED_HEAL_POWER
import screeps.api.RESOURCE_ENERGY
import screeps.api.SearchGoal
import screeps.api.SearchPathOptions
import screeps.api.TERRAIN_SWAMP
import screeps.api.TERRAIN_WALL
import screeps.api.TOUGH
import screeps.api.WORK
import screeps.api.arenaInfo
import screeps.api.get
import screeps.api.getObjectsByPrototype
import screeps.api.getTerrainAt
import screeps.api.getTicks
import screeps.api.getCpuTime
import screeps.api.searchPath
import screeps.api.season4.FLAG_TYPES
import screeps.api.season4.MAX_SCORE_PER_TICK
import screeps.api.season4.ScoreFlag
import screeps.api.season4.TICKS_LIMIT
import screeps.api.structures.StructureRampart
import screeps.api.structures.StructureSpawn
import screeps.api.structures.StructureWall
import sourcemaps.runWithSourceMapSupport
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.reflect.*

/**
 * СТРОЙ (v248, этап 8 переработки, срез 1 — тождественный перенос). По плану (docs/pain-and-gain-rework.md, раздел
 * Tactician) это одна модель строя с якорем-параметром вместо трёх расчётов оси и фронта, живших в командире. Здесь
 * пока те же расчёты, что были, — изготовка [brace] (якорь — медиана, ось на его среднее, ряды по роли, места от
 * середины наружу), колонна марша [marchColumn] (якорь — медиана, направление даёт
 * вызывающий по полю потока), кулак боя [fist] (медиана и клетки в FIST_RADIUS без стены между) и ряды блока [rows]
 * (якорь — передний боец у угрозы, ось на центроид его группы, ряды по SLOT_ORDER) — с одной
 * медианой [median] на всех. Арифметика, порядок обхода и тай-брейки байт в байт прежние: 135 сценариев стенда дают те
 * же логи (v248). Срез 2 (v249): одно правило назначения крип→место — [assignPlaces] — в изготовке и в рядах блока.
 */
internal object Formation {
    /** СТРОЙ ДО БОЯ (v179, оператор): пока враг идёт, а контакта нет, командир строит фронт вокруг своего якоря —
     *  мили к нему лицом, стрелки за ними, лекари в тылу. Ширина ряда — BRACE_WIDTH в каждую сторону. */
    private const val BRACE_WIDTH = 3
    private const val MARCH_SWAMP_COST = 8      // в колонне: крюк в четыре клетки дешевле четырёх тиков неподвижности
    /** Порядок мест в ряду блока — от середины наружу. */
    private val SLOT_ORDER = intArrayOf(0, -1, 1, -2, 2, -3, 3, -4, 4)

    /** ЯКОРЬ — МЕДИАНА боевых покоординатно, а не среднее (v150, оператор): среднее тянет один отставший крип, медиана
     *  держится середины строя. Одна формула на изготовку, колонну, кулак и подтягивание отставших. */
    fun median(core: List<Creep>): Pair<Int, Int> {
        // ...средняя из двух при чётном — в своей системе координат (v286, см. mirrorTL): верхняя из (85,88), нижняя из (12,9)
        val xs = core.map { it.x }.sorted(); val ys = core.map { it.y }.sorted()
        val k = if (mirrorTL) (xs.size - 1) / 2 else xs.size / 2
        return Pair(xs[k], ys[k])
    }

    /** ОДНО ПРАВИЛО НАЗНАЧЕНИЯ КРИП → МЕСТО (v249, этап 8). Строй собран, когда встал ПОСЛЕДНИЙ, поэтому назначение
     *  минимизирует самый длинный путь до места, а при равенстве — сумму путей: порог по узкому месту (наименьшее T, при
     *  котором все места разбираются рёбрами не длиннее T — паросочетание Куна), затем венгерский алгоритм на рёбрах не
     *  длиннее T. Крипов и мест не больше четырнадцати, цена ничтожна. Прежние два правила были эвристиками и не считали,
     *  когда строй встанет: ближайшие пары в изготовке (v183: «ближайшую к себе» растягивало четырёх мили на семь клеток —
     *  строй 5,8×6,1 против его 4,5×3,9) и жадное по близости к ряду в блоке («так слоты не пересекаются»); пары как
     *  единое правило гейт отверг (133/135: match1:grab и match34:scatter из победы по очкам в поражение по очкам).
     *  Строки алгоритма — меньшая из сторон: мест бывает больше, чем крипов (стоячая линия выдаёт места по числу всех
     *  мили, а мили вплотную к врагу из крипов отфильтрован), и первая редакция, где строками всегда шли места, на этом
     *  зацикливалась — гейт стоял двадцать минут на восьми сценариях. Возвращает пары в порядке мест. */
    private fun assignPlaces(creeps: List<Creep>, cells: List<Position>): List<Pair<Creep, Position>> {
        val m = creeps.size; val k = cells.size
        if (m == 0 || k == 0) return emptyList()
        val byCell = k <= m
        val rows = if (byCell) k else m; val cols = if (byCell) m else k
        val d = Array(rows) { r -> IntArray(cols) { q ->
            val cell = cells[if (byCell) r else q]; val c = creeps[if (byCell) q else r]
            maxOf(abs(cell.x - c.x), abs(cell.y - c.y))
        } }
        val thresholds = d.flatMap { it.asList() }.distinct().sorted()
        var lo = 0; var hi = thresholds.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (matchingSize(d, thresholds[mid]) >= rows) hi = mid else lo = mid + 1
        }
        val t = thresholds[lo]
        val cost = Array(rows) { r -> IntArray(cols) { q -> if (d[r][q] <= t) d[r][q] else UNREACHABLE } }
        val colOf = hungarian(cost)
        val byIndex = ArrayList<Pair<Int, Creep>>(rows)
        for (r in 0 until rows) byIndex.add(if (byCell) Pair(r, creeps[colOf[r]]) else Pair(colOf[r], creeps[r]))
        byIndex.sortBy { it.first }
        return byIndex.map { Pair(it.second, cells[it.first]) }
    }
    private const val UNREACHABLE = 10000

    /** Размер паросочетания строк со столбцами по рёбрам не длиннее t (алгоритм Куна); строк не больше столбцов. */
    private fun matchingSize(d: Array<IntArray>, t: Int): Int {
        val k = d.size; val m = d[0].size
        val matchCreep = IntArray(m) { -1 }
        fun tryCell(j: Int, seen: BooleanArray): Boolean {
            for (i in 0 until m) {
                if (d[j][i] > t || seen[i]) continue
                seen[i] = true
                if (matchCreep[i] < 0 || tryCell(matchCreep[i], seen)) { matchCreep[i] = j; return true }
            }
            return false
        }
        var size = 0
        for (j in 0 until k) if (tryCell(j, BooleanArray(m))) size++
        return size
    }

    /** Венгерский алгоритм: строк не больше, чем столбцов (иначе он не завершается); возвращает столбец каждой строки. */
    private fun hungarian(cost: Array<IntArray>): IntArray {
        val n = cost.size; val m = cost[0].size
        val u = IntArray(n + 1); val v = IntArray(m + 1); val p = IntArray(m + 1); val way = IntArray(m + 1)
        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = IntArray(m + 1) { Int.MAX_VALUE }
            val used = BooleanArray(m + 1)
            do {
                used[j0] = true
                val i0 = p[j0]; var delta = Int.MAX_VALUE; var j1 = 0
                for (j in 1..m) if (!used[j]) {
                    val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0 }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j }
                }
                for (j in 0..m) if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minv[j] -= delta
                j0 = j1
            } while (p[j0] != 0)
            do { val j1 = way[j0]; p[j0] = p[j1]; j0 = j1 } while (j0 != 0)
        }
        val ans = IntArray(n)
        for (j in 1..m) if (p[j] != 0) ans[p[j] - 1] = j - 1
        return ans
    }

    /** СТРОЙ ДО БОЯ (v179, оператор): «мы стояли на флаге 30-40 тиков, и всё равно, когда враг подошёл, мы были не
     *  готовы — отряд растянут, впереди стояли рэнж-крипы, а у него компактный отряд с милишниками спереди». Пока враг
     *  ИДЁТ, но контакта ещё нет, командир строит фронт: ось — направление на его центр, мили на ближней к нему линии,
     *  стрелки за ними, лекари в тылу. Это не отвергнутый USE_COMMANDER_APPROACH: тот вёл армию ВПЛОТНУЮ к врагу
     *  раздачей клеток по его строю, а здесь никто не сближается — строй ставится вокруг своего же якоря. */
    fun brace(units: TickFacts, army: List<Creep>, enemies: List<Creep>, out: MutableMap<String, Position>) {
        out.clear()
        val core = army.filter { units.of(it).liveMove && !it.spawning }
        if (core.size < 3 || enemies.isEmpty()) return
        val (ax, ay) = median(core)
        val ex = enemies.sumOf { it.x } / enemies.size; val ey = enemies.sumOf { it.y } / enemies.size
        val dx = (ex - ax).coerceIn(-1, 1); val dy = (ey - ay).coerceIn(-1, 1)
        if (dx == 0 && dy == 0) return
        // ряд крипа по роли: мили +1 к врагу, стрелки на якоре, лекари −1 (в тыл)
        fun rowOf(c: Creep) = when {
            units.of(c).meleeOnlyLive -> 1
            units.of(c).armed -> 0
            else -> -1
        }
        val taken = HashSet<Int>()          // занятые МЕСТА в строю
        // МЕСТ РОВНО ПО ЧИСЛУ КРИПОВ, И ОТ СЕРЕДИНЫ НАРУЖУ (v183). Прежде каждый ряд был шириной 2×BRACE_WIDTH+1 = семь
        // клеток, и крип занимал БЛИЖАЙШУЮ к себе, — четыре мили растягивались на семь клеток, потому что каждый шёл в
        // своё место. Замер по разгрому 3d9532: наш строй 5,8 в ширину и 6,1 в глубину (35 клеток на 12 крипов) против
        // его 4,5 и 3,9 (17 клеток) — вдвое рыхлее. Здесь ряд получает СТОЛЬКО мест, сколько в нём крипов, места
        // берутся от середины наружу, а крипы разбираются по местам одним правилом (assignPlaces): строй выходит плотным
        for (row in 1 downTo -1) {
            val mine = core.filter { rowOf(it) == row }
            if (mine.isEmpty()) continue
            val slots = ArrayList<Position>()
            var side = 0
            while (slots.size < mine.size && abs(side) <= BRACE_WIDTH + 2) {
                val px = ax + dx * row - dy * side
                val py = ay + dy * row + dx * side
                val key = key(px, py)
                // ...и место в строю не ставится в болото: изготовка нужна затем, чтобы к первому выстрелу армия могла
                // двигаться, а крип, шагнувший в трясину, стоит там четыре тика (v183)
                if (px in 0..99 && py in 0..99 && !DistanceMap.isTerrainWall(px, py) && key !in taken &&
                    !(DistanceMap.isSwamp(px, py)) &&
                    enemies.none { it.x == px && it.y == py }) {
                    taken.add(key); slots.add(InfluenceMap.cell(px, py))
                }
                // 0, −1, +1, −2, +2, … — середина ряда заполняется первой
                side = if (side <= 0) -side + 1 else -side
            }
            // ПРИКАЗ СТРОЯ — РОВНО ШАГ (v183). Исполнитель понимает приказ БУКВАЛЬНО (USE_ORDER_IS_LAW: назначенная
            // клетка и есть шаг), а изготовка выдавала место в строю за пять клеток — приказ, который невозможно
            // выполнить. Прибор поймал это сразу, едва строй начал работать: исполнение упало со 99 % до 62 %
            // (obey=41/66, lost=stuck9/else16 на тиках изготовки в разгроме 3d95d8). Марш и бой давно дают ровно
            // шаг и проверяют выполнимость; теперь их даёт и строй. Крип на своём месте приказа не получает
            for ((bc, bs) in assignPlaces(mine, slots)) if (bs.x != bc.x || bs.y != bc.y) out[bc.id] = bs
        }
    }

    /** КОЛОННА МАРША: от якоря (ax, ay) по направлению (sx, sy), которое вызывающий берёт из поля потока; крип дальше
     *  FIST_RADIUS от якоря идёт к якорю, остальные — на два шага по оси; шаг выбирается из восьми соседей. */
    fun marchColumn(units: TickFacts, core: List<Creep>, ax: Int, ay: Int, sx: Int, sy: Int, out: MutableMap<String, Position>) {
        val taken = HashSet<Int>()
        // ...и марш даёт те же гарантии, что бой (v173): клетка не занята своим, крип способен шагнуть, одна клетка —
        // одному. Прежде колонна раздавала клетки своим кодом без этих проверок, и приказы выходили неисполнимыми
        val occupied = HashSet<Int>()
        for (a in core) occupied.add(a.key)
        for (c in core.sortedBy { maxOf(abs(it.x - ax), abs(it.y - ay)) }) {
            // лекарь идёт за подопечным, а не в строю: его место задаёт лечение, и приказ марша только уводил его
            if (units.of(c).healerOnly) continue
            if (c.fatigue > 0) continue
            val far = maxOf(abs(c.x - ax), abs(c.y - ay)) > FIST_RADIUS
            val tx = if (far) ax else c.x + sx * 2
            val ty = if (far) ay else c.y + sy * 2
            // ...и шаг ВЫБИРАЕТСЯ из восьми, а не идёт напролом: прямой упирался в стену и в занятую клетку, и строй
            // застревал целиком — гейт поймал шестью строками (scouts, screen, army, camp, scatter)
            var best: Position? = null; var bestD = Int.MAX_VALUE
            for (dx in sym(1)) for (dy in sym(1)) {
                if (dx == 0 && dy == 0) continue
                val nx = c.x + dx; val ny = c.y + dy
                if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
                if (DistanceMap.isTerrainWall(nx, ny)) continue
                if (key(nx, ny) in taken) continue
                // под своим — не запрет, а цена: запрет останавливал колонну целиком (гейт 133 из 135,
                // army и camp), ровно как в бою, где полный запрет тоже пришлось заменить штрафом
                if (maxOf(abs(nx - ax), abs(ny - ay)) > FIST_RADIUS + 1) continue
                // ...и болото в колонне стоит дороже крюка: крип, шагнувший в трясину, встаёт на четыре тика, а
                // колонна уходит без него — это и есть «армия вязнет и растягивается» (v183)
                val d = maxOf(abs(nx - tx), abs(ny - ty)) * 2 + (if (key(nx, ny) in occupied) 3 else 0) +
                    (if (DistanceMap.isSwamp(nx, ny)) MARCH_SWAMP_COST else 0)
                if (d < bestD) { bestD = d; best = InfluenceMap.cell(nx, ny) }
            }
            val b = best ?: continue
            taken.add(b.key); out[c.id] = b
            occupied.remove(c.key)               // покинутая клетка освобождается для следующего в колонне
        }
    }

    /** ЕДИНЫЙ КУЛАК (v150, оператор): армия — один организм, поэтому ни одна клетка не назначается дальше
     *  FIST_RADIUS от якоря. Якорь — МЕДИАНА боевых, а не среднее: среднее тянет один отставший крип, медиана
     *  держится середины строя. Без этого раздача была покрипной — каждый уходил в свою лучшую клетку, и оператор
     *  увидел это в повторе как «крипы разбегаются, нет единого кулака».
     *  ...и КЛЕТКА НЕ ЗА СТЕНОЙ (v178, оператор: «наша армия распалась на 2 половины из-за стены, так не должно
     *  происходить»). Кулак мерил расстояние по прямой, а стена между якорем и клеткой делает две половины из
     *  одной армии: соседи по числу оказываются в разных боях. Проверяется прямая от якоря к клетке.
     *  Кандидатные клетки `cells` сужаются на месте; возвращает якорь. */
    fun fist(fighters: List<Creep>, cells: HashMap<Int, Position>): Pair<Int, Int> {
        val (ax, ay) = median(fighters)
        fun clear(px: Int, py: Int): Boolean {
            var x = ax; var y = ay
            while (x != px || y != py) {
                x += (px - x).coerceIn(-1, 1); y += (py - y).coerceIn(-1, 1)
                if (DistanceMap.isTerrainWall(x, y)) return false
            }
            return true
        }
        val keep = HashMap(cells.filterValues { maxOf(abs(it.x - ax), abs(it.y - ay)) <= FIST_RADIUS && clear(it.x, it.y) })
        if (keep.isNotEmpty()) {
            // ...КРОМЕ ПРЕСЛЕДОВАТЕЛЯ (v211): кулак и есть то единственное, что мешает крипу пойти за остовом,
            // поэтому отряд освобождается от него — и ровно на девять своих клеток, а не на всё поле
            val chasers = fighters.filter { it.id in Memory.chaseOf }
            if (chasers.isNotEmpty()) for ((k, p) in cells) {
                if (chasers.any { maxOf(abs(p.x - it.x), abs(p.y - it.y)) <= COMMAND_REACH }) keep[k] = p
            }
            cells.clear(); cells.putAll(keep)
        }
        return Pair(ax, ay)
    }

    /**
     * РЯДЫ БЛОКА: слоты стоячей линии против его блоба; ось — на центроид его группы в ENGAGE_RANGE от ближайшего.
     * ЛИНИЯ ПРОТИВ ТЫЧКА (v126, USE_LINE_VS_POKE): его мили в 2–3 от наших БЕЗ удара — не рубка, а приманка (Coldkimchi, матчи 153,
     * 506, 602: его мили вплотную 3–5 % крип-тиков). Ряды за передним мили ставят наших стрелков в 3 − d от тычка — в 4–5 от его
     * стрелков за ним, — а его стрелки в 3 от нашего переднего мили: живьём 506 (t=74–130) его стрелки с целью в трёх 158
     * крип-тиков против наших 113, 63 его выстрела «только по мили»; стенд brawl — наши 24–51 %, его 56–70 % на всех восьми
     * картах. Пока никто из его мили не вплотную к нашим, стрелки и мили стоят ОДНИМ рядом в RANGED_RANGE от тычка: впереди
     * на выстрел бесплатно не стоит никто, а его стрелкам, чтобы стрелять, надо подойти туда, где достают и наши.
     * ...И ПРОТИВ СОМКНУТОГО БЛОБА (v135, см. USE_LINE_VS_BLOB): линия одним рядом — единственный строй, который делает нас
     * ШИРЕ (замер оператора по матчу 67: его девять вооружённых стоят шириной 9,7 клетки поперёк оси, наши 4,6), а вся
     * арифметика класса в том, что его двенадцать собирают около 750 в тик на ближайшем нашем. Правило выключалось при
     * его мили внутри — то есть ровно в блобе; включаем, когда его вооружённых шесть и больше и две трети из них в
     * MASS_RANGE от их центроида.
     * ГЛУБИНА РЯДА — ОТ ЕГО СТРЕЛКА (v73, пункт 1 плана оператора): d — дистанция фронта до ближайшего его ВООРУЖЁННОГО СТРЕЛКА
     * (цель, см. v67), а не до ближайшей угрозы. Его мили-тычок в двух от фронта давал d=2, ряд стрелков вставал в 3 − 2 = 1 за
     * фронтом и в 4–6 от его стрелков: матч 179 — его 4+ выстрелов в цель 11 % тиков против наших 1 %; та же картина в 140,
     * 150, 153, 160, 173. По реплеям одиннадцати матчей (t60–400) у стрелка в 4+ от его стрелка чаще всего НЕТ свободной
     * соседней клетки в трёх от него (м179: 265 из 327 крип-тиков фазы обмена) — шаг одного крипа не лечит, лечит глубина
     * ряда. Только когда его стрелок в RANGED_RANGE + 1 от фронта (ряд вровень с фронтом его достаёт): «всегда от стрелка»
     * ставил ряд вровень с передним мили и в марше — гейт 125/125, но 17 хуже / 7 лучше против v72b (rush/nine/block
     * m13/m28/m31/m32/m33 из уничтожения в лидерство, m34 camp 817 → 1615, m5 army 161 → 223).
     */
    fun rows(melees: List<Creep>, rangeds: List<Creep>, rear: List<Creep>, threats: List<Creep>, combatEnemies: List<Creep>,
             standoffLine: Boolean, slotOf: MutableMap<String, Position>) {
        val front = if (standoffLine) rangeds else melees.ifEmpty { rangeds }
        // якорь — ПЕРЕДНИЙ боец (ближайший к врагу), не центроид: центроид мили отстаёт от фронта на 1–2 клетки, и ряд
        // стрелков «в 3 − d» от него стоял в 4–5 от линии врага, вставшей в 3 от нашего переднего (матч 18)
        val pair = front.flatMap { f -> threats.map { e -> Triple(f, e, getRange(f, e)) } }.minByOrNull { it.third } ?: return
        val anchor = InfluenceMap.cell(pair.first.x, pair.first.y)
        val nearest = pair.second
        val group = threats.filter { getRange(nearest, it) <= ENGAGE_RANGE }
        val ec = centroidOf(group.map { InfluenceMap.cell(it.x, it.y) }) ?: return
        val dx0 = sgn(ec.x - anchor.x); val dy = sgn(ec.y - anchor.y)
        val dx = if (dx0 == 0 && dy == 0) 1 else dx0
        val px = -dy; val py = dx
        val taken = HashSet<Int>()
        for (m in melees) if (!standoffLine) taken.add(m.key)
        fun rowCells(back: Int, n: Int): List<Position> {
            val out = ArrayList<Position>()
            for (k in SLOT_ORDER) {
                if (out.size >= n) break
                val x = anchor.x - back * dx + k * px; val y = anchor.y - back * dy + k * py
                if (x < 0 || y < 0 || x > 99 || y > 99 || DistanceMap.isTerrainWall(x, y) || (key(x, y)) in taken) continue
                out.add(InfluenceMap.cell(x, y))
            }
            return out
        }
        fun assign(creeps: List<Creep>, cells: List<Position>) {
            for ((c, best) in assignPlaces(creeps, cells)) { slotOf[c.id] = best; taken.add(best.key) }
        }
        val d = pair.third
        if (standoffLine) {
            // ОДИН ряд из стрелков и мили в RANGED_RANGE от ближайшей угрозы (анкер в d: отрицательное back — шаг вперёд):
            // стрелки в середине (ближайшие к своим клеткам), мили на флангах; тыл на клетку позади. Мили вплотную к врагу
            // слота не получает — рубит по своим правилам. Замер оператора по реплею матча 67 (けろびー v2): его девять
            // вооружённых стоят шириной 9,7 клетки поперёк оси на нас, наши — 4,6; по замеру шириной (вооружённые целее 600)
            // у него стрелок с целью в трёх 72 %, у нас 63 %; матч 53 (Coldkimchi) — 5,7 против 4,2, 39 % против 28 %; против
            // атакующего (матч 38, ширина его колонны 2,8) этот строй не включается. Ряд «стрелки впереди, мили позади» (v37)
            // кайтер стенда разоружал первыми — здесь мили в той же линии делят его огонь. Но загораживания замер по всем
            // вооружённым НЕ подтвердил: наши стрелки в 4+ от цели стоят со свободной клеткой впереди 42 раза против 6 за
            // своим — они не заперты, а на ряд дальше (см. USE_RANGED_FRONT, threatOf)
            val backR = (RANGED_RANGE - d).coerceIn(-1, 2)
            assign(rangeds, rowCells(backR, rangeds.size))
            assign(melees.filter { m -> combatEnemies.none { getRange(m, it) <= 1 } }, rowCells(backR, melees.size))
            assign(rear, rowCells(backR + 1, rear.size))
            return
        }
        // СТРЕЛОК НЕ ВРОВЕНЬ С ФРОНТОМ ПРОТИВ БЛОБА (v135, см. USE_RANGED_ROW_VS_BLOB): при back = 0 ряд стрелков ложится на
        // ряд anchor — переднего мили, — а остальные три мили стоят СЗАДИ, и его мили первым достаёт мягкого. Замерено по
        // двум записям (6aa0008a, 6aa0037a, окно входа 30–120): у нас его мили первым доходит до стрелка или лекаря в 60 %
        // тиков, у него — в 0–10 %; наш мили ближе лишь в 2–3 тиках из 28, его — в 15–19. Правило `behindMelee` (v69)
        // ровно про это, но живёт в planFight, который в бою с блобом не вызывается
        val back = (RANGED_RANGE - d).coerceIn(0, 2)
        if (melees.isNotEmpty()) assign(rangeds, rowCells(back, rangeds.size))
        // тыл — всегда сразу за фронтом: ряд «за стрелками» при враге вплотную (back=2) ставил лекарей в трёх клетках
        // от мили, лечение 4 за часть вместо 12 (матч 22, t=110–130: лекари в 2–3 клетках от дерущихся мили)
        // тыл на клетку дальше против СОМКНУТОГО блоба (v135, см. USE_REAR_DEEPER_VS_BLOB): ряд 1 — это одна клетка за
        // фронтом, и его мили, обойдя фронт, достаёт лекарей тем же шагом; лечение с двух идёт третью (4 за часть против
        // 12), но лекарь при этом жив, а его смерть — середина цепи, которой класс нас убивает
        assign(rear, rowCells(1, rear.size))
    }
}

internal fun commandMarch(ctx: Ctx, army: List<Creep>, goal: Position?, out: MutableMap<String, Position>) {
    out.clear()
    if (goal == null) return
    val core = mobileOf(army)
    if (core.size < 2) return
    val (ax, ay) = Formation.median(core)
    // ...и направление задаёт ПУТЬ, а не прямая на цель: жадный шаг упирался в стену и строй застревал целиком —
    // сценарий screen шёл в режиме марша все 185 строк лога и проигрывал счёт 10 782:14 471. Ведущий — тот, кто
    // ближе всех к якорю; его шаг по полю потока и есть направление колонны (v163)
    val lead = core.minByOrNull { maxOf(abs(it.x - ax), abs(it.y - ay)) }!!
    marchAll.n++
    val sx: Int; val sy: Int
    val descent = flowDescent(ctx, goal, ax, ay, lead)
    if (descent != null) { marchFlow.n++; sx = descent.first; sy = descent.second }
    else {
        val step = pathStep(lead, goal, 1, crowdMatrixOf(ctx, goal.key))
        sx = if (step != null) (step.x - lead.x).coerceIn(-1, 1) else (goal.x - ax).coerceIn(-1, 1)
        sy = if (step != null) (step.y - lead.y).coerceIn(-1, 1) else (goal.y - ay).coerceIn(-1, 1)
    }
    if ((sx != 0 || sy != 0) && sx == -marchPrevSx && sy == -marchPrevSy) marchFlip.n++
    marchPrevSx = sx; marchPrevSy = sy
    if (sx == 0 && sy == 0) return
    Formation.marchColumn(unitsNow, core, ax, ay, sx, sy, out)
}

/** Шаг колонны по полю потока к цели (v232, см. USE_MARCH_FLOW_DIRECTION): сосед клетки якоря с наименьшим расстоянием
 *  до цели; якорь на стене — от клетки ведущего; null — цель по полю недостижима; (0,0) — якорь на цели. */
internal fun flowDescent(ctx: Ctx, goal: Position, ax: Int, ay: Int, lead: Creep): Pair<Int, Int>? {
    val field = flowTo(ctx, goal)
    val fx: Int; val fy: Int
    if (field[key(ax, ay)] >= 0) { fx = ax; fy = ay }
    else if (field[lead.key] >= 0) { fx = lead.x; fy = lead.y }
    else return null
    // НИЧЬЯ СПУСКА — ПРЕЖНЕЕ НАПРАВЛЕНИЕ, ЗАТЕМ САМОЕ ПРЯМОЕ К ЦЕЛИ (v283). Сосед с наименьшим расстоянием брался ПЕРВЫЙ по
    // порядку обхода, а на открытой земле равных соседей два-три (восемь направлений при чебышёвском шаге). Якорь —
    // медиана ядра, и после шага колонны она ложится то на одну, то на другую из двух клеток, у которых первый по обходу
    // спуск разный: стенд match35:screen (v283, пост в середине пути) — якорь (44,45) → шаг (1,0), якорь (45,44) → шаг
    // (0,1), колонна перестраивается под новое направление и возвращает медиану назад; 1 300 тиков в пяти клетках от D5,
    // центр армии на месте, счёт отдан 10 791:14 495. Прибор разворотов `mdir` этого не видел: направления не
    // противоположны, а перпендикулярны. Выбор среди равных теперь не зависит от порядка обхода: прежнее направление, если
    // оно среди лучших, иначе ближайшее к прямой на цель
    val here = field[key(fx, fy)]
    var bestD = here
    for (dx in sym(1)) for (dy in sym(1)) {
        if (dx == 0 && dy == 0) continue
        val nx = fx + dx; val ny = fy + dy
        if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
        val d = field[key(nx, ny)]
        if (d >= 0 && d < bestD) bestD = d
    }
    if (bestD >= here) return Pair(0, 0)
    var bx = 0; var by = 0; var bestDot = Int.MIN_VALUE
    for (dx in sym(1)) for (dy in sym(1)) {
        if (dx == 0 && dy == 0) continue
        val nx = fx + dx; val ny = fy + dy
        if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
        if (field[key(nx, ny)] != bestD) continue
        if (dx == marchPrevSx && dy == marchPrevSy) return Pair(dx, dy)
        val dot = dx * (goal.x - fx) + dy * (goal.y - fy)
        if (dot > bestDot) { bestDot = dot; bx = dx; by = dy }
    }
    return Pair(bx, by)
}

/** Мини-состояние для симуляции (v138): позиция, хиты и профиль крипа. */

internal fun planBlock(approachRate: Double, army: List<Creep>, combatEnemies: List<Creep>, armedEnemies: List<Creep>, slotOf: MutableMap<String, Position>) {
    val melees = lineMelees(army)
    val rangeds = lineRangeds(army)
    val rear = army.filter { c -> melees.none { it.id == c.id } && rangeds.none { it.id == c.id } }
    val armed = melees + rangeds
    if (armed.isEmpty()) return
    val threats = armedEnemies.ifEmpty { combatEnemies }
    // его первый ряд — стрелки (v113, USE_RANGED_FRONT_VS_RANGED): ближайший к нашему фронту его вооружённый — стрелок без мили,
    // и это БЛОБ — не меньше RANGED_FRONT_GROUP его вооружённых в ENGAGE_RANGE от него (первый срез без этого условия включал
    // ряд вровень против гарнизона-стрелка россыпи: гейт m30 scatter 21613:24313 красный)
    // ...и блоб ПОДХОДИТ САМ — его темп к нам за окно подхода не ниже темпа броска (approachRate, см. APPROACH_RUSH) — либо ряд уже
    // защёлкнут этим контактом: стоящий у флага лагерь (гейт m31 camp 15105:22908 красный при ряде вровень против любого блоба,
    // и тот же счёт при «дистанция сократилась» — она сокращается и от нашего марша к лагерю) — не вход в рубку, там игра на очки
    // approachRate — темп сокращения дистанции центров, он растёт и от НАШЕГО марша к стоящему лагерю (m31 camp красный при
    // «сокращается» и при approachRate): нужен сдвиг ЕГО центра за окно — не меньше четверти окна (v57: «это он уходит»)
    val hisMoved = Memory.hisCentHist.size >= 2 && run {
        val a = Memory.hisCentHist.first(); val b = Memory.hisCentHist.last()
        maxOf(abs(a / 100 - b / 100), abs(a % 100 - b % 100)) >= APPROACH_WINDOW / 4
    }
    val closing = approachRate >= APPROACH_RUSH && hisMoved
    val inReach = threats.any { e -> armed.any { getRange(it, e) <= ENGAGE_RANGE } }
    if (!inReach) rangedLevelLatched = false
    // ...и это уже ВХОД: его ближайший в RANGED_RANGE + 1 от нашего фронта — проходящий мимо на 5–8 блоб-фермер (m31 camp,
    // тот же счёт при любом признаке подхода) ряда не получает
    val hisFrontRanged =  (closing || rangedLevelLatched) && threats.minByOrNull { e -> armed.minOf { getRange(it, e) } }
        ?.let { n -> val pr = InfluenceMap.profileOf(n); pr.ranged > 0.0 && pr.melee <= 0.0 && armed.minOf { getRange(it, n) } <= RANGED_RANGE + 1 && threats.count { getRange(n, it) <= ENGAGE_RANGE } >= RANGED_FRONT_GROUP } == true
    if (hisFrontRanged && inReach) rangedLevelLatched = true
    Formation.rows(melees, rangeds, rear, threats, combatEnemies, standoffLine = rangeds.isNotEmpty() && hisFrontRanged, slotOf)
}

/** Расстановка боя (см. USE_PLAN): клетки с признаками, роли по порядку признаков, жадное назначение. Выход — slotOf,
 *  движение к слоту — как у строя (slotStep). Мили вплотную к врагу слота не получает (рубит по своим правилам), его
 *  клетка занята. */
internal fun planFight(army: List<Creep>, combatEnemies: List<Creep>, armedEnemies: List<Creep>, enemyCreeps: List<Creep>, slotOf: MutableMap<String, Position>, focusTarget: Creep?) {
    val melees = lineMelees(army)
    val rangeds = lineRangeds(army)
    val rear = army.filter { c -> melees.none { it.id == c.id } && rangeds.none { it.id == c.id } }
    if (melees.isEmpty() && rangeds.isEmpty()) return
    val threats = armedEnemies.ifEmpty { combatEnemies }
    if (threats.isEmpty()) return
    val theirMelee = threats.filter { InfluenceMap.profileOf(it).melee > 0.0 }
    // ЦЕЛЬ КЛЕТКИ — ЕГО СТРЕЛОК (v67; как фокус v60): «есть цель в трёх» считало целью и мили-приманку в 2–3, клетка в трёх от
    // неё держалась памятью расстановки, и стрелки стояли в 4 от его стрелков со свободной клеткой впереди 142 крип-тика,
    // стреляя в мили «за неимением» (матч 160, девятое поражение Coldkimchi: 79 выстрелов в мили при его стрелках в 3–4,
    // 3:198 и 4:213 крип-тиков; его 341 выстрел против наших 199, армия стёрта при его 16000/16000). Мили — цель клетки,
    // только когда стрелков у него нет
    val shootAt = threats.filter { InfluenceMap.profileOf(it).ranged > 0.0 }.ifEmpty { threats }
    val enemyAt = enemyCreeps.mapTo(HashSet()) { it.key }
    // клетки-кандидаты: в RANGED_RANGE от любого нашего (дальше — марш, не расстановка), проходимые, не под врагом, и НА
    // НАШЕЙ СТОРОНЕ (v61): не дальше от центра наших вооружённых, чем от центра его угроз. Матч 145 (Coldkimchi, седьмое
    // поражение, армия стёрта за сорок тиков при его 16000/16000): его линия стояла в (73–76, 9–13), наш центр в (79,8), и
    // ярусы «цель в трёх → нет его мили вплотную → нет его мили в двух» выбрали стрелкам клетки (76–80, 15–16) — ЗА его
    // линией, где его мили не стоят, — и одиннадцать крипов пошли к ним сквозь его строй по одному (so=0 flow=−1 у всех на
    // 370–380-м): reach 1/3, наш огонь 1,2 в тик против его 4,2
    val ourC = centroidOf(armedOf(army).ifEmpty { army })
    val theirC = centroidOf(threats)
    val cells = HashMap<Int, FightCell>()
    for (c in army) for (dx in sym(RANGED_RANGE)) for (dy in sym(RANGED_RANGE)) {
        val x = c.x + dx; val y = c.y + dy
        val key = key(x, y)
        if (key in cells || x < 0 || y < 0 || x > 99 || y > 99 || DistanceMap.isTerrainWall(x, y) || key in enemyAt) continue
        val p = InfluenceMap.cell(x, y)
        if (ourC != null && theirC != null && getRange(p, ourC) > getRange(p, theirC)) continue
        cells[key] = FightCell(p, key, InfluenceMap.damageAt(x, y, combatEnemies), shootAt.count { getRange(p, it) <= RANGED_RANGE },
            focusTarget != null && getRange(p, focusTarget) <= RANGED_RANGE,
            theirMelee.count { getRange(p, it) <= 1 }, theirMelee.count { getRange(p, it) <= MELEE_KEEP_RANGE },
            threats.minOf { getRange(p, it) })
    }
    if (cells.isEmpty()) return
    val taken = HashSet<Int>()
    val meleeFree = melees.filter { m -> combatEnemies.none { getRange(m, it) <= 1 } }
    for (m in melees) if (meleeFree.none { it.id == m.id }) taken.add(m.key)
    // клетка под своим — только его: без этого стрелкам назначались клетки друг друга (матч 73, t=120: ranged_1 → клетка
    // ranged_3, ranged_3 → клетка ranged_1, ranged_2 → клетка ranged_3), и строй крутился на месте под огнём
    val ownAt = HashMap<Int, String>()
    for (c in army) ownAt[c.key] = c.id
    // память расстановки: клетка прошлого тика остаётся, пока держит верхние ярусы своей роли (keep) — без памяти «лучшая»
    // клетка менялась каждый тик (урон, фланги), стрелки блуждали вбок вместо шага за фронтом, и кайтер стенда бил идущих
    // за ним мили при reach=0/5 (m28 kite, армия потеряна); ряд строя двигался вместе с передним мили и потому успевал
    val plan = HashMap<String, Int>()
    fun place(c: Creep, cmp: Comparator<FightCell>, keep: ((FightCell) -> Boolean)?, ok: (FightCell) -> Boolean): FightCell? {
        fun free(cell: FightCell) = cell.key !in taken && (ownAt[cell.key] ?: c.id) == c.id
        val prev = keep?.let { k -> Memory.lastPlan[c.id]?.let { cells[it] }?.takeIf { free(it) && k(it) } }
        val best = prev ?: cells.values.filter { free(it) && ok(it) }.maxWithOrNull(cmp) ?: return null
        slotOf[c.id] = best.pos
        taken.add(best.key)
        plan[c.id] = best.key
        return best
    }
    // стрелки: цель в трёх → не вплотную к его мили → не в двух от его мили → цель фокуса в трёх → дистанция до ближайшей
    // угрозы (с целью: 3 лучше 2 лучше 1 — линия стоит на выстреле; БЕЗ цели: ближе к врагу — иначе стрелок вне
    // досягаемости стоял на месте, пока кайтер отходил на клетку в тик и бил идущих за ним мили: m28 kite, reach=0/5 сто
    // тиков, армия потеряна) → меньше урона → ближе к себе
    val rangedCells = HashMap<String, FightCell>()
    fun rangedCmp(c: Creep) = compareBy<FightCell> { if (it.focusIn) 1 else 0 }   // цель фокуса в трёх — первое (v45)
        .thenBy { if (it.targets > 0) 1 else 0 }
        .thenBy { if (it.meleeAdj == 0) 1 else 0 }
        .thenBy { if (it.meleeNear == 0) 1 else 0 }
        .thenBy { if (it.targets > 0) minOf(it.dist, RANGED_RANGE) else -it.dist }
        .thenBy { -it.dmg }
        .thenBy { -getRange(c, it.pos) }
    // СТРЕЛОК НЕ ВПЕРЕДИ МИЛИ (v69): клетка стрелка не ближе к угрозе, чем нынешний фронт мили минус шаг. Матч 169 (stachu3478,
    // третье поражение при 24 победах): его линия отходила по клетке в тик, клетки «его стрелок в трёх» (v67) уходили за ней,
    // стрелки шли вперёд, мили (клетки по памяти) отстали на 8–10 клеток, и его мили (в трёх 41 % тиков) съели стрелков по
    // одному — 19 крип-тиков разоружённых, армия стёрта за сто тиков при его 15435/16000. «Стрелки впереди» отвергнуто ещё
    // v37 — сюда оно вошло через план
    val meleeFrontDist = meleeFree.minOfOrNull { m -> threats.minOf { getRange(m, it) } }
    fun behindMelee(cell: FightCell) = meleeFrontDist == null || cell.dist >= meleeFrontDist - 1
    val constrained = rangeds.sortedBy { c -> cells.values.count { it.targets > 0 && it.meleeAdj == 0 && getRange(c, it.pos) <= 1 } }
    for (r in constrained) {
        val strict = place(r, rangedCmp(r), { it.targets > 0 && it.meleeAdj == 0 && it.meleeNear == 0 && behindMelee(it) }) { behindMelee(it) }
        if (strict != null) planStrict.n++ else planLoose.n++
        val cell = strict ?: place(r, rangedCmp(r), null) { true } ?: continue
        rangedCells[r.id] = cell
    }
    // мили без врага вплотную — заслон перед стрелком: клетка рядом с клеткой стрелка и ближе к угрозе, чем она; его мили в
    // двух (есть кого встретить) лучше, чем нет; вплотную к двум и больше его мили — хуже; меньше урона; ближе к себе.
    // Нет такой клетки — фланг: клетка на той же дистанции, что и стрелки
    val front = rangedCells.values.toList()
    fun meleeCmp(c: Creep) = compareBy<FightCell> { if (it.meleeAdj <= 1) 1 else 0 }
        .thenBy { if (it.meleeNear > 0) 1 else 0 }
        .thenBy { -it.dist }
        .thenBy { -it.dmg }
        .thenBy { -getRange(c, it.pos) }
    val frontDist = front.minOfOrNull { it.dist } ?: RANGED_RANGE
    // ЗАСЛОН ИМЕЕТ СМЫСЛ, ПОКА ЗАСЛОНЯЮЩИЙ БЬЁТ (v195, USE_MELEE_BEHIND_WHEN_IDLE). Клетка мили выбиралась смежной с
    // клеткой стрелка и БЛИЖЕ к врагу, чем она, — то есть на клетку впереди строя; при стрелках в трёх это ровно
    // двойка, где его пятеро стрелков достают, а наш ATTACK (дальность 1) не достаёт. Замер против Coldkimchi#2:
    // мили стоит вплотную к врагу 1 % крипо-тиков, его блок меняет клетку 71–76 % тиков, урона мы получаем в
    // 1,5–3,9 раза больше, чем наносим, и к двухсотому тику у наших мили 0–16 атакующих частей из 32 против его
    // 24–32. Роль щита размен не спасает: его пятеро дают 300 в тик, наши трое лекарей возвращают 216. Пока доля
    // касаний измеряется единицей (стенд, всякий соперник, идущий в контакт) — всё как было; упала — мили встаёт
    // ПОЗАДИ стрелков. Это же место, а не ступень движения: в бою клетки раздаёт командир, и slot старше holdMelee
    fun inFront(cell: FightCell) = front.any { rc -> getRange(cell.pos, rc.pos) <= 1 &&
        (cell.dist < rc.dist) }
    for (m in meleeFree.sortedBy { c -> front.minOfOrNull { getRange(c, it.pos) } ?: 0 }) {
        place(m, meleeCmp(m), { it.meleeAdj <= 1 && inFront(it) }) { cell -> inFront(cell) }
            ?: place(m, meleeCmp(m), null) { cell -> cell.dist == frontDist && front.any { rc -> getRange(cell.pos, rc.pos) <= 2 } }
            ?: place(m, meleeCmp(m), null) { true }
    }
    // лекари: вплотную к бойцу с наибольшим входящим уроном на ЕГО клетке; сам не в двух от его мили; под меньшим огнём
    val fighterCells = ArrayList(rangedCells.values)
    for (m in meleeFree) slotOf[m.id]?.let { p -> cells[p.key]?.let { fighterCells.add(it) } }
    for (m in melees) if (meleeFree.none { it.id == m.id }) cells[m.key]?.let { fighterCells.add(it) }
    val healers = rear.filter { hasHeal(it) }
    val wounded = rear.filter { !hasHeal(it) }
    fun needAt(cell: FightCell): Double = fighterCells.filter { getRange(cell.pos, it.pos) <= 1 }.maxOfOrNull { it.dmg } ?: -1.0
    // ПОКРЫТИЕ (v128, USE_HEALERS_COVER): клетка лекаря — где больше НАШИХ бойцов вплотную, и лишь потом наибольший входящий
    // урон у соседа. Живьём (реплеи 506/411, первые 60 тиков контакта) его лекарь вплотную к нашей цели 62–83 % тиков, наш к
    // его цели 14–16 %, при том что цель огня у обеих сторон меняется почти каждый тик (та же, что тиком раньше, в 31–44 %):
    // лекарь, идущий к бойцу под огнём, приходит, когда огонь уже ушёл; лекарь, касающийся многих, уже стоит рядом
    val healerCells = ArrayList<FightCell>()
    // покрытие — по бойцам, у которых ЕЩЁ нет лекаря вплотную (жадное покрытие): по простому числу соседей трое лекарей
    // сбегались к одной плотной группе и бросали остальных (v128a: brawl m31 из победы в уничтожение армии)
    fun coverAt(cell: FightCell): Int = fighterCells.count { f -> getRange(cell.pos, f.pos) <= 1 && healerCells.none { h -> getRange(h.pos, f.pos) <= 1 } }
    // против сомкнутого блоба «клетка без его мили» уступает покрытию (v135, см. USE_HEALERS_COVER_OVER_SAFETY):
    // у блоба КАЖДАЯ клетка рядом с раненым соседствует с его мили, и лекари садились туда, где лечить некого —
    // 60 крипо-тиков с раненым вплотную из шестисот против его 129, лечений 85+12r против 132+65r
    fun healerCmp(c: Creep) = compareBy<FightCell> { if (it.meleeNear == 0) 1 else 0 }
        .thenBy { needAt(it) }
        .thenBy { -it.dmg }
        .thenBy { -getRange(c, it.pos) }
    fun byFighter(cell: FightCell) = fighterCells.any { getRange(cell.pos, it.pos) <= 1 }
    for (h in healers.sortedBy { c -> fighterCells.minOfOrNull { getRange(c, it.pos) } ?: 0 }) {
        val cell = place(h, healerCmp(h), { it.meleeNear == 0 && byFighter(it) }) { cell -> byFighter(cell) }
            ?: place(h, healerCmp(h), null) { cell -> fighterCells.any { getRange(cell.pos, it.pos) <= HEAL_RANGE } }
            ?: place(h, healerCmp(h), null) { true }
        if (cell != null) healerCells.add(cell)
    }
    // раненые без оружия: дальше выстрела, рядом с лекарем, под меньшим огнём
    fun woundedCmp(c: Creep) = compareBy<FightCell> { if (it.dist > RANGED_RANGE) 1 else 0 }
        .thenBy { if (healerCells.any { h -> getRange(it.pos, h.pos) <= 1 }) 1 else 0 }
        .thenBy { -it.dmg }
        .thenBy { -getRange(c, it.pos) }
    for (w in wounded) place(w, woundedCmp(w), { it.dist > RANGED_RANGE }) { true }
    Memory.lastPlan.clear()
    Memory.lastPlan.putAll(plan)
}

internal class HealerWallOut(
)

/** ПОТЕРИ ТИКА И СТЕНА ЛЕКАРЕЙ (v256, этап 10; сегмент runArmy перед покрипным циклом): потеря каждого бойца за тик (lostTick), жертва по адресному огню его стволов или по потере, клетки стены вокруг неё и лекари к ним (victimNow, victimSaveable, wallCells, wallCellOf). Перенесено дословно. */
internal fun healerWall(ctx: Ctx, meas: ArmyMeasures): HealerWallOut {
    Wall.lostTick.clear()
    for (c in ctx.army) Wall.lostTick[c.id] = ((Memory.lastHits[c.id] ?: c.hits) - c.hits).coerceAtLeast(0)
    // СТЕНА ЛЕЧЕНИЯ (v228, см. USE_HEAL_WALL): жертва — терявший больше всех за прошлый тик; удержима, если её потеря не
    // больше лечения, которое наши лекари доставят в неё следующим тиком (вплотную или в шаге от вплотную — полное, в трёх
    // — треть; лекарь считается и для себя)
    // ...и стена стоит только на БЕЗОПАСНЫХ клетках (стенд m28 brawl+heals при первой редакции: лекари вплотную к жертве
    // попадали под удары его мили — 240 за удар по лечащим частям, которые у h6m6 спереди, — лечение обнулялось и
    // раздевалась вся армия; уничтожение на 298-м стало проигрышем на 1751-м). Клетка стены — свободная соседняя с жертвой,
    // от которой его вооружённые мили дальше двух (за один ход не встанут вплотную); живьём против Coldkimchi#1 такая клетка
    // у жертвы есть в 83–89 % тиков, в блобе MetalicaX#9 — в 29 %. Лекарь получает ближайшую свою клетку как слот; без
    // клеток жертва не удержима, и правило молчит
    Wall.victimNow = null; Wall.victimSaveable = false; Wall.wallCells = emptyList(); Wall.wallCellOf.clear()
    Wall.addressedDmg.clear()
    // ...И ЖЕРТВА — ПО АДРЕСНОМУ ОГНЮ ЭТОГО ТИКА (v229, см. USE_HEAL_WALL_ADDRESSED): его правило выбора цели по
    // текущим клеткам — лекарь в досягаемости первым, иначе ближайший, при равенстве с меньшими хитами
    // ...и карта адресного урона живёт тик (v233, см. USE_HEAL_BY_DEFICIT): её читает выбор пациента
    val addressed = Wall.addressedDmg
    val live = livingCombatants(ctx.army)
    for (e in ctx.combatEnemies) {
        val q = InfluenceMap.profileOf(e)
        if (q.ranged > 0.0) Forecast.wallTargetOf(unitsNow, e, live, RANGED_RANGE)?.let { t -> addressed[t.id] = (addressed[t.id] ?: 0.0) + q.ranged }
        if (q.melee > 0.0) Forecast.wallTargetOf(unitsNow, e, live, MELEE_STEP_REACH)?.let { t -> addressed[t.id] = (addressed[t.id] ?: 0.0) + q.melee }
    }
    val byAddress = addressed.entries.maxByOrNull { it.value }
    val lostV = ctx.army.filter { (combatant(it)) && (Wall.lostTick[it.id] ?: 0) > 0 }.maxByOrNull { Wall.lostTick[it.id] ?: 0 }
    // ...И АДРЕС БЕРЁТСЯ, ПОКА ОН ПОПАДАЕТ (v229, вторая редакция по стенду m28 brawl+heals: его сценарий стреляет «в
    // вооружённого стрелка первым», а не в лекаря, и адресная жертва промахивалась — уничтожение на 442-м стало
    // проигрышем на 1570-м). Оба предсказателя сверяются с фактом следующего тика (кто потерял больше всех) за окно
    // TOUCH_WINDOW; адресный используется, только пока попадает чаще, чем «по потере», — против того, чьё правило
    // цели другое, стена сама возвращается к v228
    if (lostV != null && (wallAddrPrev != null || wallLostPrev != null)) {
        Wall.wallAddrHits.addLast(wallAddrPrev == lostV.id); Wall.wallLostHits.addLast(wallLostPrev == lostV.id)
        while (Wall.wallAddrHits.size > TOUCH_WINDOW) Wall.wallAddrHits.removeFirst()
        while (Wall.wallLostHits.size > TOUCH_WINDOW) Wall.wallLostHits.removeFirst()
        hwallPredN.n++; if (wallAddrPrev == lostV.id) hwallPredA.n++; if (wallLostPrev == lostV.id) hwallPredL.n++
    }
    wallAddrPrev = byAddress?.key; wallLostPrev = lostV?.id
    val addrWins = Wall.wallAddrHits.size >= STALL_TICKS && Wall.wallAddrHits.count { it } > Wall.wallLostHits.count { it }
    val v = if (byAddress != null && addrWins) ctx.army.firstOrNull { it.id == byAddress.key } ?: lostV else lostV
    if (v != null) {
        val useAddr = byAddress != null && addrWins && v.id == byAddress.key
        if (useAddr) hwallAddr.n++
        val loss = if (useAddr) byAddress!!.value else (Wall.lostTick[v.id] ?: 0).toDouble()
        val hisMelee = ctx.combatEnemies.filter { hasMelee(it) }
        val hisRanged = ctx.combatEnemies.filter { hasRanged(it) }
        val occupied = HashSet<Int>()
        for (c in ctx.army) if (!healerOnly(c)) occupied.add(c.key)
        for (e in ctx.enemyCreeps) occupied.add(e.key)
        val cells = ArrayList<Position>()
        for (dx in sym(1)) for (dy in sym(1)) {
            if (dx == 0 && dy == 0) continue
            val x = v.x + dx; val y = v.y + dy
            if (x < 0 || y < 0 || x > 99 || y > 99 || DistanceMap.isTerrainWall(x, y) || (key(x, y)) in occupied) continue
            val cell = InfluenceMap.cell(x, y)
            if (hisMelee.none { getRange(cell, it) <= 2 }) cells.add(cell)
        }
        Wall.wallCells = cells
        val healers = ctx.army.filter { hasHeal(it) && it.id != v.id }
        val free = ArrayList(cells)
        for (h in healers.sortedBy { getRange(it, v) }) {
            if (free.isEmpty() || getRange(h, v) > HEAL_RANGE + 1) break
            val best = free.minByOrNull { getRange(h, it) } ?: break
            free.remove(best)
            Wall.wallCellOf[h.id] = best
        }
        val potential = ctx.army.filter { hasHeal(it) }.sumOf { h ->
            val heal = InfluenceMap.profileOf(h).heal
            val cell = Wall.wallCellOf[h.id]
            val d = getRange(h, v)
            if (h.id == v.id || (cell != null && getRange(h, cell) <= 1)) heal else if (d <= HEAL_RANGE) heal / 3.0 else 0.0
        }
        Wall.victimNow = v
        Wall.victimSaveable = cells.isNotEmpty() && loss <= potential
        if (!Wall.victimSaveable) Wall.wallCellOf.clear()
        hwallVictimTicks.n++
        if (Wall.victimSaveable) hwallTicks.n++
    }
    return HealerWallOut(
    )
}

internal class ArmyBlockOut(
)

/** СТРОЙ РЯДАМИ В БОЮ ПО КОНТАКТУ (v256, этап 10; сегмент runArmy): при blockOn — planFight (признаки клеток) или planBlock (ряды), слоты в slotOf. Перенесено дословно. */
internal fun armyBlock(ctx: Ctx, meas: ArmyMeasures, strat: ArmyStrategy, targ: ArmyTargets, stanceOut: ArmyStance): ArmyBlockOut {
    if (stanceOut.windows.blockOn) {
        // расстановка (см. USE_PLAN) — только в СТОЯЧЕМ бою (признак прижима: линия стоит под огнём, его мили не идут);
        // против атаки и в погоне — ряды за передним мили: свободная расстановка рыхлее рядов, и с ней остаток
        // атакующего уходил, а кайтер добивался позже (гейт v43c: block/nine/rush «уничтожение → лидерство» ×10, кайтеры
        // медленнее ×7 при wing ×4, block+flagless ×2 и farm+weak m33 +7561 лучше)
        val standoffNow = stanceOut.press.pressOn && !strat.dec.enemyRetreating
        // ОТВЕРГНУТО: расстановка и в контакте, пока его мили не идут на нас (!theirMeleeClosing) — ради матча 73 (Coldkimchi:
        // его мили подходили к нашим стрелкам и лекарям вплотную, били по 240 и отходили — 46 ударов против наших 7, а
        // прижим требует «его мили не вплотную», и расстановка была выключена ровно в этом бою): едва атака врага встаёт,
        // бой берёт расстановка, и остаток уходит — 123/125, m28 wing и m31 camp проиграны, против прижима-только 21 хуже /
        // 16 лучше. Прилипший к стрелку мили — дело нашего мили (см. poker в engage), не строя
        // стоячий бой по центрам армий (v47): контакт держится дольше окна терпения, центры вооружённых армий не сближаются,
        // враг не отходит — расстановка; атака (центры сближаются) и погоня (враг отходит) — ряды
        // ...и это ЛИНИЯ, а не рубка (v62): его вооружённый мили не ближе MELEE_HOLD_RANGE + 1 к нашим вооружённым. Матч 150
        // (боевой けろびー, армия стёрта к 180-му, форма матча 140): контакт на 70-м, центры не сближались (его мили среди наших),
        // «стоячий бой» → расстановка ставила стрелков колонной x=81 в 1–3 клетках от его стрелков, к 80-му двое наших стрелков
        // стояли уже за его линией на клетках, равноудалённых от обоих центров (v61 их не режет); ярусы «подальше от его мили» в
        // рубке ведут сквозь его строй. Прижим (standoffNow) это условие и так несёт; стоячий бой по центрам (v47) — нет
        // РУБКА — его мили ВПЛОТНУЮ, не «в трёх» (v64): линия Coldkimchi держит мили в 2–3 от наших и не рубит (матч 153, восьмое
        // поражение: 2:699 и 3:1431 крип-тиков его мили, 61 удар за 700 тиков боя) — по «в трёх» v62 расстановка была выключена
        // весь бой, ряды planBlock ставили стрелков за передним мили, и наш огонь (1611 выстрелов против его 1428) шёл по
        // разным целям: 4+ в одну цель 9 тиков против его 44 при 216 лечения в тик на цели с обеих сторон
        val meleeBrawl = strat.dec.theirMeleeIn
        val standingNow = meas.fight.contact && Memory.centreDistHist.size > PRESS_PATIENCE && !stanceOut.windows.armiesClosing && !strat.dec.enemyRetreating && !meleeBrawl && !stanceOut.windows.ourYielding
        if (DEBUG_LOG && stanceOut.windows.ourYielding && meas.fight.contact && !stanceOut.windows.armiesClosing && !strat.dec.enemyRetreating && !meleeBrawl && yieldingTick != getTicks() - 1) println("plan t=${getTicks()}: our line has yielded ${PRESS_CLOSING}+ cells over $PRESS_PATIENCE ticks — rows behind the front melee, not the plan")
        if (stanceOut.windows.ourYielding) yieldingTick = getTicks()
        val planNow =  (standoffNow || standingNow)
        // командир (v137): в бою с сомкнутым блобом решение одно на армию, и оно вытесняет оба планировщика
        // ...и НЕ против того, кто уходит (v139): в сценарии kite враг держит дистанцию, боя нет, и командир держал
        // армию в размене вместо игры за флаги — 0:21 899 и 0:21 082 при исправном CPU
        // ...и только когда рубка ИДЁТ: против лагеря у флага (camp) командир тоже держал армию в размене вместо
        // очков — 13 311:23 559. Мера уже есть: его мили внутри нашего строя (meleeBrawl)
        // КОМАНДИР ПРАВИТ ВЕСЬ БОЙ (v144): замер сказал, что он правил 27 тиков из пятисот и в большинстве из них
        // приказ получал ОДИН крип из двенадцати (cmd=1/27:noMelee) — остальное время армия шла по старым
        // правилам, и они тянули в другую сторону. Единого кулака из этого выйти не могло: командир успевал лишь
        // выдернуть крипа из строя. Теперь условие одно — контакт с сомкнутым врагом
        // ...и на ПОДХОДЕ тоже (v151): иначе армия сходится к бою врассыпную по старым правилам и собирается в кулак
        // уже под огнём — переход между двумя управлениями и есть то смешение, которое оператор назвал
        // ...но «любой контакт» оказалось слишком широко: гейт уронил roost и scatter — формы, где враг сидит на
        // флагах или разбегается, и командир строил кулак против того, кто строем не дерётся. Условие по существу
        // не «сомкнут ли он», а «сколько его вооружённых стоит у нашей армии»: группа — дело командира, одиночка —
        // нет (v155)
        // цена командира отдельной строкой в разбивке (v158): она была спрятана в фазе «plan» вместе с обеими
        // расстановками, и когда живьём дважды сработал `Script execution timed out`, сказать по логу, чей это
        // расход, было нечем. На стенде вопрос не решается — там весь тик стоит 2,4 мс на пике
        // РАССТАНОВКА МОЛЧИТ ПРИ КОМАНДИРЕ (v165, оператор: продолжать переносить логику в командира). Слоты и
        // командирские клетки — два ответа на один вопрос «кто где стоит»; пока командир ведёт бой, спрашивать
        // второй раз незачем, и крип, которому клетки не досталось, шёл в слот прежней расстановки
        if (planNow) planFight(meas.chase.mobileArmy, meas.forces.combatEnemies, meas.forces.armedEnemies, meas.forces.enemyCreeps, targ.zones.slotOf, targ.focus.focusTarget)
        else planBlock(strat.obj.approachRate, meas.chase.mobileArmy, meas.forces.combatEnemies, meas.forces.armedEnemies, targ.zones.slotOf)
    }
    return ArmyBlockOut(
    )
}

internal const val RANGED_FRONT_GROUP = 6   // блоб: столько его вооружённых в ENGAGE_RANGE от ближайшего (россыпь — 1–3)

internal var marchPrevSx = 0

internal var marchPrevSy = 0

internal var rangedLevelLatched = false     // ряд вровень защёлкнут контактом с подходящим блобом; снимается, когда никого в ENGAGE_RANGE

internal var wallAddrPrev: String? = null      // кого адресный предсказатель назвал жертвой прошлым тиком

internal var wallLostPrev: String? = null      // ...и кого назвал предсказатель по потере

internal var yieldingTick = -1                        // последний тик, когда наша линия отступала (лог v96)

internal class FightCell(val pos: Position, val key: Int, val dmg: Double, val targets: Int, val focusIn: Boolean,
                        val meleeAdj: Int, val meleeNear: Int, val dist: Int)

// ==================== приборы стадии: счётчик живёт у того, кто считает (v447, план архитектуры, 4.7 и этап 6) ====================
// Объявления перенесены из Instruments.kt дословно; Instruments их читает и печатает, текст строк прежний.

/** Стрелков, вставших в клетку без его мили в двух, и вставших куда придётся — за матч (v135, `planFight`). До v448 — за
 *  тик: мир сбрасывал в `readSignals`, а считал строй — счётчики с двумя писателями (находка этапа 6). Теперь они
 *  накопительные и живут у того, кто считает; разницу за тик берёт печать (`plan=`), и печатаемые числа те же. */
internal val planStrict = Gauges.counter("plan", perTick = true)

internal val planLoose = Gauges.counter("plan", 1, perTick = true)

/** Марш (v232): тиков с направлением по полю потока, тиков с целью марша, разворотов направления на обратное. */
internal val marchFlow = Gauges.counter("mdir")

internal val marchAll = Gauges.counter("mdir", 1)

internal val marchFlip = Gauges.counter("mdir", 2)

internal val hwallTicks = Gauges.counter("hwall")

internal val hwallVictimTicks = Gauges.also(Gauges.counter("hwall", 1), "hwalla", 1)

internal val hwallAddr = Gauges.counter("hwalla")

internal val hwallPredA = Gauges.counter("hwallp")

internal val hwallPredL = Gauges.counter("hwallp", 1)

internal val hwallPredN = Gauges.counter("hwallp", 2)

/** СТЕНА ЛЕКАРЕЙ И ПОТЕРИ ТИКА (v459, второй шаг архитектуры, этап 6): то, что пишет `healerWall`, — перенесено из `object PainAndGain` как есть. `victimNow`, `victimSaveable`, `wallCells` раздача боя читает РАНЬШЕ, чем стена их перепишет в этом тике, — то есть значением прошлого тика (см. Prev). */
internal object Wall {
    /** Адресный урон этого тика по нашим (v229/v233): кто из его стрелков и мили в кого целится по модели его выбора. */
    internal val addressedDmg = HashMap<String, Double>()
    /** Темп очков на сотом и двухсотом тике — снимок дебюта, которого не снимал ни один прибор. */
    internal val lostTick = HashMap<String, Int>()   // потеря хитов за прошлый тик по всей армии, снятая до обновления lastHits (v109)
    internal val wallAddrHits = ArrayDeque<Boolean>()
    internal val wallLostHits = ArrayDeque<Boolean>()
    internal val wallCellOf = HashMap<String, Position>()  // клетка стены, назначенная лекарю на этот тик
    internal var victimNow: Creep? = null          // стена лечения (v228): терявший больше всех за прошлый тик
    internal var victimSaveable = false            // ...и его потеря не больше доставимого в него лечения
    internal var wallCells: List<Position> = emptyList()   // клетки стены: соседние с жертвой, его вооружённые мили дальше двух
}
