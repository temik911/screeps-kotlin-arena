package season4.painandgain

import kotlin.math.abs
import screeps.api.Creep
import screeps.api.Position
import screeps.api.getRange

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
        val xs = core.map { it.x }.sorted(); val ys = core.map { it.y }.sorted()
        return Pair(xs[xs.size / 2], ys[ys.size / 2])
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
    fun brace(army: List<Creep>, enemies: List<Creep>, out: MutableMap<String, Position>) {
        out.clear()
        val core = army.filter { PainAndGain.canMove(it) && !it.spawning }
        if (core.size < 3 || enemies.isEmpty()) return
        val (ax, ay) = median(core)
        val ex = enemies.sumOf { it.x } / enemies.size; val ey = enemies.sumOf { it.y } / enemies.size
        val dx = (ex - ax).coerceIn(-1, 1); val dy = (ey - ay).coerceIn(-1, 1)
        if (dx == 0 && dy == 0) return
        // ряд крипа по роли: мили +1 к врагу, стрелки на якоре, лекари −1 (в тыл)
        fun rowOf(c: Creep) = when {
            PainAndGain.hasWeapon(c) && PainAndGain.hasMelee(c) && !PainAndGain.hasRanged(c) -> 1
            PainAndGain.hasWeapon(c) -> 0
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
                val key = px * 100 + py
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
    fun marchColumn(core: List<Creep>, ax: Int, ay: Int, sx: Int, sy: Int, out: MutableMap<String, Position>) {
        val taken = HashSet<Int>()
        // ...и марш даёт те же гарантии, что бой (v173): клетка не занята своим, крип способен шагнуть, одна клетка —
        // одному. Прежде колонна раздавала клетки своим кодом без этих проверок, и приказы выходили неисполнимыми
        val occupied = HashSet<Int>()
        for (a in core) occupied.add(a.x * 100 + a.y)
        for (c in core.sortedBy { maxOf(abs(it.x - ax), abs(it.y - ay)) }) {
            // лекарь идёт за подопечным, а не в строю: его место задаёт лечение, и приказ марша только уводил его
            if (PainAndGain.hasHeal(c) && !PainAndGain.hasWeapon(c)) continue
            if (c.fatigue > 0) continue
            val far = maxOf(abs(c.x - ax), abs(c.y - ay)) > PainAndGain.FIST_RADIUS
            val tx = if (far) ax else c.x + sx * 2
            val ty = if (far) ay else c.y + sy * 2
            // ...и шаг ВЫБИРАЕТСЯ из восьми, а не идёт напролом: прямой упирался в стену и в занятую клетку, и строй
            // застревал целиком — гейт поймал шестью строками (scouts, screen, army, camp, scatter)
            var best: Position? = null; var bestD = Int.MAX_VALUE
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = c.x + dx; val ny = c.y + dy
                if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
                if (DistanceMap.isTerrainWall(nx, ny)) continue
                if (nx * 100 + ny in taken) continue
                // под своим — не запрет, а цена: запрет останавливал колонну целиком (гейт 133 из 135,
                // army и camp), ровно как в бою, где полный запрет тоже пришлось заменить штрафом
                if (maxOf(abs(nx - ax), abs(ny - ay)) > PainAndGain.FIST_RADIUS + 1) continue
                // ...и болото в колонне стоит дороже крюка: крип, шагнувший в трясину, встаёт на четыре тика, а
                // колонна уходит без него — это и есть «армия вязнет и растягивается» (v183)
                val d = maxOf(abs(nx - tx), abs(ny - ty)) * 2 + (if (nx * 100 + ny in occupied) 3 else 0) +
                    (if (DistanceMap.isSwamp(nx, ny)) MARCH_SWAMP_COST else 0)
                if (d < bestD) { bestD = d; best = InfluenceMap.cell(nx, ny) }
            }
            val b = best ?: continue
            taken.add(b.x * 100 + b.y); out[c.id] = b
            occupied.remove(c.x * 100 + c.y)               // покинутая клетка освобождается для следующего в колонне
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
        val keep = HashMap(cells.filterValues { maxOf(abs(it.x - ax), abs(it.y - ay)) <= PainAndGain.FIST_RADIUS && clear(it.x, it.y) })
        if (keep.isNotEmpty()) {
            // ...КРОМЕ ПРЕСЛЕДОВАТЕЛЯ (v211): кулак и есть то единственное, что мешает крипу пойти за остовом,
            // поэтому отряд освобождается от него — и ровно на девять своих клеток, а не на всё поле
            val chasers = fighters.filter { it.id in Memory.chaseOf }
            if (chasers.isNotEmpty()) for ((k, p) in cells) {
                if (chasers.any { maxOf(abs(p.x - it.x), abs(p.y - it.y)) <= PainAndGain.COMMAND_REACH }) keep[k] = p
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
        val group = threats.filter { getRange(nearest, it) <= PainAndGain.ENGAGE_RANGE }
        val ec = PainAndGain.centroidOf(group.map { InfluenceMap.cell(it.x, it.y) }) ?: return
        val dx0 = PainAndGain.sgn(ec.x - anchor.x); val dy = PainAndGain.sgn(ec.y - anchor.y)
        val dx = if (dx0 == 0 && dy == 0) 1 else dx0
        val px = -dy; val py = dx
        val taken = HashSet<Int>()
        for (m in melees) if (!standoffLine) taken.add(m.x * 100 + m.y)
        fun rowCells(back: Int, n: Int): List<Position> {
            val out = ArrayList<Position>()
            for (k in SLOT_ORDER) {
                if (out.size >= n) break
                val x = anchor.x - back * dx + k * px; val y = anchor.y - back * dy + k * py
                if (x < 0 || y < 0 || x > 99 || y > 99 || DistanceMap.isTerrainWall(x, y) || (x * 100 + y) in taken) continue
                out.add(InfluenceMap.cell(x, y))
            }
            return out
        }
        fun assign(creeps: List<Creep>, cells: List<Position>) {
            for ((c, best) in assignPlaces(creeps, cells)) { slotOf[c.id] = best; taken.add(best.x * 100 + best.y) }
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
