package season4.spawnandswampadvanced

import screeps.api.ATTACK
import screeps.api.BODYPART_COST
import screeps.api.BUILD_POWER
import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.CARRY_CAPACITY
import screeps.api.CONSTRUCTION_COST
import screeps.api.CREEP_SPAWN_TIME
import screeps.api.ConstructionSite
import screeps.api.Creep
import screeps.api.GameObject
import screeps.api.HARVEST_POWER
import screeps.api.HEAL
import screeps.api.HEAL_POWER
import screeps.api.MAX_CONSTRUCTION_SITES
import screeps.api.MOVE
import screeps.api.OBSTACLE_OBJECT_TYPES
import screeps.api.Position
import screeps.api.RAMPART_HITS
import screeps.api.RANGED_ATTACK
import screeps.api.RANGED_ATTACK_POWER
import screeps.api.ATTACK_POWER
import screeps.api.RESOURCE_DECAY
import screeps.api.RESOURCE_ENERGY
import screeps.api.SOURCE_ENERGY_REGEN
import screeps.api.SPAWN_ENERGY_CAPACITY
import screeps.api.SPAWN_HITS
import screeps.api.SearchGoal
import screeps.api.SearchPathOptions
import screeps.api.Source
import screeps.api.TERRAIN_SWAMP
import screeps.api.TERRAIN_WALL
import screeps.api.TOWER_COOLDOWN
import screeps.api.TOWER_FALLOFF
import screeps.api.TOWER_FALLOFF_RANGE
import screeps.api.TOWER_OPTIMAL_RANGE
import screeps.api.TOWER_POWER_ATTACK
import screeps.api.TOWER_HITS
import screeps.api.TOWER_RANGE
import screeps.api.TOWER_ENERGY_COST
import screeps.api.WORK
import screeps.api.arenaInfo
import screeps.api.createConstructionSite
import screeps.api.get
import screeps.api.getObjects
import screeps.api.getObjectsByPrototype
import screeps.api.getRange
import screeps.api.getTerrainAt
import screeps.api.getTicks
import screeps.api.searchPath
import screeps.api.structures.Structure
import screeps.api.structures.StructureContainer
import screeps.api.structures.StructureRampart
import screeps.api.structures.StructureSpawn
import screeps.api.structures.StructureTower
import screeps.api.structures.StructureWall
import sourcemaps.runWithSourceMapSupport

@OptIn(ExperimentalJsExport::class)
@JsExport
fun loop() {
    try {
        runWithSourceMapSupport {
            SpawnAndSwampAdvanced.tick()
        }
    } catch (t: Throwable) {
        println("loop error: ${t.message}")
        println(t.stackTraceToString())
    }
}

/**
 * Season 4 «Spawn and Swamp» ADVANCED — v2. Правила и замеры — `docs/spawn-and-swamp-advanced.md`.
 *
 * Матч начинается без спавна: у каждого рабочий `M5W4C1` (несёт 50) на фиксированной стартовой клетке, раскладка
 * источников, контейнеров и стен постоянна, рельеф случаен, стороны меняются. Проигрыш — когда у тебя не осталось
 * ни крипа, ни постройки (площадка не спасает: v1 проиграл с площадкой спавна на карте).
 *
 * Слои, в порядке, в котором наступают:
 *  1. **Дебют.** Домашний источник — тот, до которого рабочий дойдёт раньше всех (путь в ЕГО тиках на равнине и
 *     болоте), клетка спавна — в двух шагах от источника с наибольшим числом клеток, смежных с обоими. Рабочий
 *     копает партию, которую стройка съедает целыми тиками без остатка (`batchFor`): у `W4C1` это 40 за пять тиков
 *     и два тика стройки — 5,71 в тик, у сильных соперников спавн готов к 213-му тику, у v1 с партией 50 — к 238-му.
 *  2. **База.** Каждый наш спавн — база: свой источник и клетки у обоих. Добытчики стоят на этих клетках, копают
 *     каждый тик и сдают в спавн; сколько WORK нужно источнику, считается из его восстановления.
 *  3. **Второй спавн.** Когда дом насыщен и прикрыт бойцом, строитель идёт к ближайшему «нашему» источнику (к нему
 *     мы ближе, чем соперник) и строит там спавн тем же циклом — удвоение дохода и вторая жизнь.
 *  4. **Армия.** Бойцы копятся у дома и дерутся всей кучей: защищают базы от угроз, а выходят волной, только когда
 *     сила волны (Ланчестер) превосходит всю видимую армию и кормленые башни соперника; волна держится вместе и
 *     отходит, если на месте проигрывает. v1 гнал стрелков по одному — все погибли поодиночке.
 */
object SpawnAndSwampAdvanced {

    /** Печатается первой строкой матча: по ней лог связывается с коммитом, а `--arena` инструментов отличает режим
     *  от базового (фильтр по подстроке — поэтому в имени обязательно `spawn-and-swamp-advanced`). */
    private const val BOT_VERSION = "v10"

    private const val LOG_EVERY = 50

    // ---------- армия ----------
    private const val RANGED_RANGE = 3
    /** Волна выходит, когда её сила больше силы соперника в столько раз: запас на ошибку оценки (тот же, что у
     *  базового бота, где он замерен живыми матчами). */
    private const val PUSH_RATIO = 1.3
    /** Волна на месте проигрывает, если её сила меньше местной силы соперника в столько раз, — отход. */
    private const val RETREAT_RATIO = 0.8
    /** Угроза дому: боевой враг в стольких клетках от нашего спавна или от нашего рабочего (дальность стрелка плюс
     *  несколько шагов подхода). */
    private const val HOME_THREAT_RANGE = 10
    private const val WORKER_THREAT_RANGE = 6
    /** Защита снимается, только когда угроз нет и на столько клеток дальше: без запаса стрелки соперника, кружащие
     *  у границы (けろびー, v4), переключали позу каждые два-три тика, и наши бегали за ними туда-обратно. */
    private const val THREAT_RELEASE = 5
    /** Волна держится: передние ждут отставших, если те отстали больше чем на столько клеток до цели… */
    private const val COHESION = 3
    /** …но только тех, кто может догнать (отстал не больше чем на четыре таких шага), и не дольше, чем такой
     *  отставший идёт через полосу догона по болоту (5 тиков на клетку) в среднем наполовину: v5 ждал всех без
     *  срока, отставший в тесноте не мог подойти ближе трёх клеток, и волна из одиннадцати простояла с 1405-го
     *  тика до конца матча — ничья при 39 наших против 16. */
    private const val CATCH_UP = COHESION * 4
    private const val HOLD_LIMIT = CATCH_UP * 5 / 2
    /** Крип врага, до которого столько клеток от волны, — местная угроза для решения об отходе. */
    private const val LOCAL_RANGE = 10

    // ---------- состояние между тиками ----------
    private data class Pos(val x: Int, val y: Int)

    /** База: спавн (или его будущая клетка), её источник и клетки, смежные с обоими. */
    private class Base(val sourceId: String, val spawnCell: Pos, val slots: List<Pos>) {
        var spawnId: String? = null
        var towerCell: Pos? = null
    }

    private val bases = ArrayList<Base>()
    /** Где стоит добытчик: id крипа → клетка базы. */
    private val slotOf = HashMap<String, Pos>()
    /** Строитель второго спавна и его цель. */
    private var builderId: String? = null
    /** Строитель заказан, но ещё не узнан: id объекта из `spawnCreep` в тик заказа не тот, что у крипа потом
     *  (v3 «терял» строителя через тик после заказа), поэтому он узнаётся по телу среди наших рабочих без клетки. */
    private var builderPending = false
    private var expansion: Base? = null
    private var expansionPlaced = false
    /** Волна: id бойцов, ушедших в атаку. Пусто — армия дома. */
    private val wave = HashSet<String>()
    private var lastPosture = ""
    private var enemyStart: Pos? = null
    private var enemyKnown: Set<String> = emptySet()
    private var myKnown: Map<String, String> = emptyMap()
    private var spawnUpAt = -1
    private var defending = false
    private var attackedOnce = false
    /** Роли крипов вне экономики баз и армии: пробойщик, строитель сейфа. Заказанный крип узнаётся по телу. */
    private val roleOf = HashMap<String, String>()
    private val pendingRoles = ArrayList<Pair<String, String>>()
    private var vault: Vault? = null
    private var holdTicks = 0
    /** Чужие площадки башен: id → (тик, прогресс) при первой встрече — из них скорость его стройки. */
    private val enemySiteSeen = HashMap<String, Pair<Int, Int>>()

    // ---------- мелочи ----------
    private fun idOf(o: GameObject): String = o.id.asDynamic().toString().unsafeCast<String>()

    private fun cell(x: Int, y: Int): Position {
        val o: dynamic = js("({})")
        o.x = x
        o.y = y
        return o.unsafeCast<Position>()
    }

    private fun cell(p: Pos): Position = cell(p.x, p.y)

    private fun posOf(o: Position) = Pos(o.x, o.y)

    private fun cheb(a: Pos, b: Pos) = maxOf(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))

    private fun protoName(o: Any): String = o.asDynamic().constructor.name.unsafeCast<String?>() ?: "?"

    private fun energyOf(o: dynamic): Int = (o.store?.getUsedCapacity("energy") as Int?) ?: 0

    private fun bodyText(types: List<String>): String {
        val order = listOf("move" to "M", "work" to "W", "carry" to "C", "attack" to "A", "ranged_attack" to "R",
            "heal" to "H", "tough" to "T", "claim" to "K")
        val counts = LinkedHashMap<String, Int>()
        for (t in types) counts[t] = (counts[t] ?: 0) + 1
        return order.filter { counts.containsKey(it.first) }.joinToString("") { "${it.second}${counts[it.first]}" } +
            counts.keys.filter { k -> order.none { it.first == k } }.joinToString("") { "?$it${counts[it]}" }
    }

    private fun bodyText(body: Array<BodyPartType>): String = bodyText(body.map { it.asDynamic().unsafeCast<String>() })

    private fun bodyOf(parts: Array<Any>): String = bodyText(parts.map { it.asDynamic().type.unsafeCast<String>() })

    private fun bodyOf(c: Creep): String = bodyOf(c.body.unsafeCast<Array<Any>>())

    private fun liveParts(c: Creep, type: BodyPartType): Int = c.body.count { it.type == type && it.hits > 0 }

    private fun costOf(body: Array<BodyPartType>): Int = body.sumOf { BODYPART_COST[it] ?: 0 }

    private fun isSpawnSite(site: ConstructionSite): Boolean {
        val s = site.structure
        if (s != null && protoName(s) == "StructureSpawn") return true
        val cost = CONSTRUCTION_COST.asDynamic()["StructureSpawn"].unsafeCast<Int?>()
        return cost != null && site.progressTotal == cost
    }

    private fun describe(o: GameObject): String {
        val d = o.asDynamic()
        val sb = StringBuilder("${protoName(o)}(${o.x},${o.y})")
        if (d.my != undefined) sb.append(" my=${d.my}")
        if (d.hits != undefined) sb.append(" hits=${d.hits}/${d.hitsMax}")
        if (d.store != undefined) sb.append(" e=${energyOf(d)}/${d.store.getCapacity("energy")}")
        if (d.energyCapacity != undefined) sb.append(" src=${d.energy}/${d.energyCapacity}")
        if (d.amount != undefined) sb.append(" amount=${d.amount}")
        if (d.progressTotal != undefined) {
            val s = d.structure
            sb.append(" site=${if (s == null || s == undefined) "?" else protoName(s)} ${d.progress}/${d.progressTotal}")
        }
        if (d.body != undefined) sb.append(" body=${bodyOf(d.body.unsafeCast<Array<Any>>())}")
        if (d.ticksToDecay != undefined) sb.append(" decay=${d.ticksToDecay}")
        if (d.spawning != undefined && d.spawning != null && d.spawning != false) {
            val sp = d.spawning
            if (sp.remainingTime != undefined) sb.append(" spawning=${sp.remainingTime}/${sp.needTime}")
        }
        return sb.toString()
    }

    // ---------- движение: сколько тиков крип тратит на клетку ----------

    /** Вес для усталости: всё, кроме MOVE, а CARRY — только гружёный (движок считает части по ТИПУ, мёртвые тоже). */
    private fun weight(c: Creep, loaded: Boolean): Int =
        c.body.count { it.type != MOVE && (it.type != CARRY || loaded) }

    private fun periodOn(c: Creep, terrainFactor: Int, loaded: Boolean): Int {
        val moves = liveParts(c, MOVE)
        if (moves == 0) return 1000
        val fatigue = terrainFactor * weight(c, loaded)
        return maxOf(1, (fatigue + 2 * moves - 1) / (2 * moves))
    }

    /** Путь в тиках этого крипа: стоимость клетки — его период на равнине и болоте. */
    private fun ticksTo(c: Creep, target: Position, range: Int, loaded: Boolean = false): Int {
        val r = searchPath(c, SearchGoal(pos = target, range = range),
            SearchPathOptions(plainCost = periodOn(c, 2, loaded), swampCost = periodOn(c, 10, loaded)))
        return if (r.incomplete) Int.MAX_VALUE / 4 else r.cost
    }

    // ---------- рельеф и клетки ----------

    private fun blockedCells(all: Array<GameObject>): Set<Pos> {
        val out = HashSet<Pos>()
        for (o in all) if (o is Structure && o !is StructureContainer && o !is StructureRampart || o is ConstructionSite) out.add(Pos(o.x, o.y))
        return out
    }

    private fun walkable(p: Pos, blocked: Set<Pos>): Boolean =
        p.x in 1..98 && p.y in 1..98 && getTerrainAt(cell(p)) != TERRAIN_WALL && p !in blocked

    private fun isSwamp(p: Pos) = getTerrainAt(cell(p)) == TERRAIN_SWAMP

    /** Клетка базы у источника: в двух шагах от него, с наибольшим числом клеток, смежных с обоими (на них добытчик
     *  копает и сдаёт без шага), и хотя бы одним свободным выходом для рождения; равнина лучше болота. */
    private fun planBase(src: Source, from: Position, blocked: Set<Pos>): Base? {
        val s = posOf(src)
        var best: Base? = null
        var bestScore = Int.MIN_VALUE
        for (dx in -2..2) for (dy in -2..2) {
            val c = Pos(s.x + dx, s.y + dy)
            if (cheb(c, s) != 2 || !walkable(c, blocked)) continue
            val slots = ArrayList<Pos>()
            var exits = 0
            for (ax in c.x - 1..c.x + 1) for (ay in c.y - 1..c.y + 1) {
                val a = Pos(ax, ay)
                if (a == c || !walkable(a, blocked)) continue
                if (cheb(a, s) == 1) slots.add(a) else exits++
            }
            if (slots.isEmpty() || exits == 0) continue
            val score = slots.size * 1000 + (if (isSwamp(c)) 0 else 100) + minOf(exits, 3) * 10 - getRange(from, cell(c))
            if (score > bestScore) { bestScore = score; best = Base(idOf(src), c, slots) }
        }
        return best
    }

    // ---------- сейф ----------

    /**
     * Сейф — свой блок из четырёх контейнеров по 2500 в кармане из `StructureWall` (10000 хитов) и центральной стены
     * рельефа: 10000 энергии, которые в первых матчах не тронул никто (контейнеры полны до конца в каждом логе).
     * Пробойщик ломает одну клетку стены — ближайшую к дому по пути; пролом сразу закрывается НАШИМ рампартом (200,
     * 10000 хитов: свои проходят, чужие нет — предложение оператора 27.09.2026), и карман снова запечатан, но уже
     * только для соперника. Внутри — спавн на клетке, до которой стрелок снаружи не достаёт (не ближе 4 от любой
     * внешней клетки), и башня; всё строится энергией самих контейнеров, строитель потом их заправщик.
     * Рельеф случаен: если карман не запечатан рельефом, ломать нечего — стадия пролома пропускается.
     */
    private class Vault(
        val containers: List<Pos>, val interior: Set<Pos>, val wall: Pos?, val outside: Pos?,
        val spawnCell: Pos, val towerCell: Pos?,
    ) {
        var stage = if (wall == null) "build" else "breach"
        var spawnId: String? = null
    }

    private fun neighbours(p: Pos): List<Pos> {
        val out = ArrayList<Pos>(8)
        for (dx in -1..1) for (dy in -1..1) if (dx != 0 || dy != 0) out.add(Pos(p.x + dx, p.y + dy))
        return out
    }

    private fun planVault(from: Position, all: Array<GameObject>) {
        val ourStart = posOf(from)
        val enemy = enemyStart ?: return
        val containers = all.filter { it is StructureContainer }.map { posOf(it) }
        if (containers.isEmpty()) return
        // кластеры контейнеров (в двух клетках друг от друга); свой — тот, что ближе к нашему старту, чем к его
        val clusters = ArrayList<MutableList<Pos>>()
        for (c in containers) {
            val home = clusters.firstOrNull { cl -> cl.any { cheb(it, c) <= 2 } }
            if (home != null) home.add(c) else clusters.add(mutableListOf(c))
        }
        fun center(cl: List<Pos>) = Pos(cl.sumOf { it.x } / cl.size, cl.sumOf { it.y } / cl.size)
        val cluster = clusters.minByOrNull { cheb(center(it), ourStart) - cheb(center(it), enemy) } ?: return
        val c0 = center(cluster)
        if (cheb(c0, ourStart) >= cheb(c0, enemy)) { println("vault: no cluster on our side"); return }
        val walls = all.filter { it is StructureWall && cheb(posOf(it), c0) <= 8 }.map { posOf(it) }.toSet()
        fun open(p: Pos) = p.x in 0..99 && p.y in 0..99 && getTerrainAt(cell(p)) != TERRAIN_WALL && p !in walls
        // внутренность кармана: заливка от контейнеров; дошла до края окна — карман рельефом не запечатан
        val limit = 9
        val interior = HashSet<Pos>()
        val queue = ArrayDeque(cluster)
        interior.addAll(cluster)
        var sealed = true
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            for (n in neighbours(p)) {
                if (n in interior || !open(n)) continue
                if (cheb(n, c0) >= limit) { sealed = false; continue }
                interior.add(n)
                queue.addLast(n)
            }
        }
        // клетки, где может стоять соперник: проходимые вне кармана
        fun outsideOpen(p: Pos) = open(p) && p !in interior
        var wall: Pos? = null
        var outside: Pos? = null
        if (sealed) {
            var best = Int.MAX_VALUE
            for (w in walls) {
                if (neighbours(w).none { it in interior }) continue
                for (n in neighbours(w)) {
                    if (!outsideOpen(n)) continue
                    val cost = pathTicks(from, cell(n))
                    if (cost < best) { best = cost; wall = w; outside = n }
                }
            }
            if (wall == null) { println("vault: sealed but no breachable wall"); return }
        }
        // глубина клетки: расстояние до ближайшей клетки, где может встать соперник (пролом закрыт рампартом)
        fun depth(p: Pos): Int {
            var d = 99
            for (dx in -6..6) for (dy in -6..6) {
                val q = Pos(p.x + dx, p.y + dy)
                if (q != wall && outsideOpen(q)) d = minOf(d, cheb(p, q))
            }
            return d
        }
        val free = interior.filter { it !in cluster && it != wall }
        val spawnCell = free.filter { c -> cluster.any { cheb(it, c) <= 1 } }
            .maxWithOrNull(compareBy<Pos> { minOf(depth(it), RANGED_RANGE + 1) }.thenBy { c -> cluster.count { cheb(it, c) <= 1 } }
                .thenByDescending { c -> wall?.let { cheb(it, c) } ?: 0 }) ?: return
        val towerCell = free.filter { it != spawnCell && cheb(it, spawnCell) >= 1 && cluster.any { c -> cheb(c, it) <= 1 } }
            .maxWithOrNull(compareBy<Pos> { minOf(depth(it), RANGED_RANGE + 1) }.thenByDescending { cheb(it, spawnCell) })
        vault = Vault(cluster, interior, wall, outside, spawnCell, towerCell)
        println("vault: containers=${cluster.joinToString(" ") { "${it.x},${it.y}" }} sealed=$sealed interior=${interior.size} " +
            "wall=${wall?.let { "${it.x},${it.y}" }} outside=${outside?.let { "${it.x},${it.y}" }} spawn=${spawnCell.x},${spawnCell.y} " +
            "depth=${depth(spawnCell)} tower=${towerCell?.let { "${it.x},${it.y}" }}")
    }

    /** Пробойщик: столько пар MOVE+ATTACK, сколько вмещает спавн — ATTACK бьёт стену втрое сильнее RANGED за почти
     *  половину цены, а ход 1:1 доводит его по равнине за тик на клетку. */
    private fun breacherBody(): Array<BodyPartType> {
        val k = SPAWN_ENERGY_CAPACITY / ((BODYPART_COST[MOVE] ?: 50) + (BODYPART_COST[ATTACK] ?: 80))
        val out = ArrayList<BodyPartType>()
        repeat(k) { out.add(MOVE); out.add(ATTACK) }
        return out.toTypedArray()
    }

    /** Строитель сейфа: пять WORK строят 25 в тик, а берёт он из контейнера в тот же тик (`withdraw` — не работа);
     *  четыре CARRY — чтобы потом, заправщиком, носить в спавн по 200; ноги — ход пустого 1:1. */
    private fun vaultBuilderBody(): Array<BodyPartType> {
        val work = (SOURCE_ENERGY_REGEN + HARVEST_POWER - 1) / HARVEST_POWER
        return (List(work) { WORK } + List(4) { CARRY } + List(work) { MOVE }).toTypedArray()
    }

    /** Заправщик сейфа без стройки: четыре CARRY носят по 200 из контейнера в спавн за два-три тика. */
    private fun fillerBody(): Array<BodyPartType> = arrayOf(CARRY, CARRY, CARRY, CARRY, MOVE)

    private fun wallObject(v: Vault, all: Array<GameObject>): GameObject? =
        v.wall?.let { w -> all.firstOrNull { it is StructureWall && it.x == w.x && it.y == w.y } }

    private fun resolveRoles(t: Int, mine: List<Creep>) {
        roleOf.keys.retainAll(mine.map { idOf(it) }.toSet())
        val it = pendingRoles.iterator()
        while (it.hasNext()) {
            val (role, sig) = it.next()
            val c = mine.firstOrNull { c -> bodyOf(c) == sig && idOf(c) !in roleOf && idOf(c) !in slotOf && idOf(c) != builderId } ?: continue
            roleOf[idOf(c)] = role
            it.remove()
            println("role t=$t $role is ${idOf(c)} ${bodyOf(c)}")
        }
    }

    private fun hasRole(role: String) = roleOf.containsValue(role) || pendingRoles.any { it.first == role }

    private fun runVault(t: Int, mine: List<Creep>, all: Array<GameObject>, mySpawns: List<StructureSpawn>) {
        val v = vault ?: return
        if (v.stage == "breach" && v.wall != null && wallObject(v, all) == null) {
            v.stage = "build"
            println("vault: breached t=$t at (${v.wall.x},${v.wall.y})")
        }
        if (v.spawnId == null) {
            val sp = mySpawns.firstOrNull { it.x == v.spawnCell.x && it.y == v.spawnCell.y }
            if (sp != null) { v.spawnId = idOf(sp); v.stage = "run"; println("vault: spawn up t=$t at (${sp.x},${sp.y})") }
        }
        val mySites = all.filter { it is ConstructionSite && it.asDynamic().my == true }.unsafeCast<List<ConstructionSite>>()
        for (c in mine) {
            if (c.spawning) continue
            when (roleOf[idOf(c)]) {
                "breacher" -> {
                    val w = wallObject(v, all)
                    val out = v.outside
                    if (w == null || out == null) { roleOf.remove(idOf(c)); continue }
                    if (getRange(c, w) > 1) c.moveTo(cell(out)) else c.attack(w)
                }
                "vaultBuilder" -> runVaultBuilder(t, c, v, all, mySites, mySpawns)
            }
        }
    }

    private fun containerAt(p: Pos, all: Array<GameObject>): StructureContainer? =
        all.firstOrNull { it is StructureContainer && it.x == p.x && it.y == p.y } as? StructureContainer

    private fun runVaultBuilder(t: Int, c: Creep, v: Vault, all: Array<GameObject>, mySites: List<ConstructionSite>, mySpawns: List<StructureSpawn>) {
        val me = posOf(c)
        if (v.stage == "breach") { v.outside?.let { if (cheb(me, it) > 2) c.moveTo(cell(it)) }; return }
        val full = v.containers.mapNotNull { containerAt(it, all) }.filter { energyOf(it) > 0 }
        // внутри: сперва площадки — рампарт в пролом, спавн, башня (по одной, по очереди)
        if (me in v.interior) {
            val wall = v.wall
            if (wall != null && all.none { (it is StructureRampart || it is ConstructionSite) && it.x == wall.x && it.y == wall.y }) {
                val r = createConstructionSite(wall.x, wall.y, StructureRampart::class.js)
                println("vault: rampart site t=$t at (${wall.x},${wall.y}) err=${r.error}")
            } else if (v.spawnId == null && mySites.none { it.x == v.spawnCell.x && it.y == v.spawnCell.y }) {
                val r = createConstructionSite(v.spawnCell.x, v.spawnCell.y, StructureSpawn::class.js)
                println("vault: spawn site t=$t at (${v.spawnCell.x},${v.spawnCell.y}) err=${r.error}")
            } else if (v.spawnId != null && v.towerCell != null && all.none { (it is StructureTower || it is ConstructionSite) && it.x == v.towerCell.x && it.y == v.towerCell.y }) {
                val r = createConstructionSite(v.towerCell.x, v.towerCell.y, StructureTower::class.js)
                println("vault: tower site t=$t at (${v.towerCell.x},${v.towerCell.y}) err=${r.error}")
            }
        }
        val vaultSites = mySites.filter { Pos(it.x, it.y) == v.wall || Pos(it.x, it.y) == v.spawnCell || Pos(it.x, it.y) == v.towerCell }
            .sortedBy { if (Pos(it.x, it.y) == v.wall) 0 else if (Pos(it.x, it.y) == v.spawnCell) 1 else 2 }
        val e = c.store[RESOURCE_ENERGY] ?: 0
        val free = c.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0
        val site = vaultSites.firstOrNull()
        if (site != null && liveParts(c, WORK) > 0) {
            // стоим у контейнера в досягаемости площадки: берём и строим в один тик
            val nearC = full.firstOrNull { getRange(c, it) <= 1 }
            if (nearC != null && free > 0) c.withdraw(nearC, RESOURCE_ENERGY)
            if (e > 0 && getRange(c, site) <= 3) c.build(site)
            val spot = v.interior.filter { p -> p !in v.containers && p != v.spawnCell && p != v.towerCell && full.any { cheb(posOf(it), p) <= 1 } && cheb(p, posOf(site)) <= 3 }
                .minByOrNull { cheb(it, me) }
            if (spot != null && spot != me) c.moveTo(cell(spot)) else if (spot == null && getRange(c, site) > 3) c.moveTo(site)
            return
        }
        // заправщик: из контейнера в спавн сейфа и его башню
        val spawn = mySpawns.firstOrNull { idOf(it) == v.spawnId }
        val tower = all.firstOrNull { it is StructureTower && it.asDynamic().my == true && v.towerCell?.let { tc -> it.x == tc.x && it.y == tc.y } == true } as? StructureTower
        val sink: Structure? = when {
            tower != null && (tower.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0 -> tower
            spawn != null && (spawn.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0 -> spawn
            else -> null
        }
        if (e > 0 && sink != null) {
            if (getRange(c, sink) <= 1) c.transfer(sink, RESOURCE_ENERGY) else c.moveTo(sink)
            return
        }
        val src = full.minByOrNull { getRange(c, it) } ?: return
        if (free > 0) { if (getRange(c, src) <= 1) c.withdraw(src, RESOURCE_ENERGY) else c.moveTo(src) }
    }

    // ---------- тик ----------

    fun tick() {
        val t = getTicks()
        val all = getObjects()
        val byId = HashMap<String, GameObject>()
        for (o in all) byId[idOf(o)] = o
        val creeps = getObjectsByPrototype(Creep::class)
        val mine = creeps.filter { it.my }
        val theirs = creeps.filter { !it.my }
        val sources = getObjectsByPrototype(Source::class)
        val sites = getObjectsByPrototype(ConstructionSite::class)
        val mySpawns = getObjectsByPrototype(StructureSpawn::class).filter { it.my == true }

        if (t == 1) {
            dumpWorld(all)
            theirs.firstOrNull()?.let { enemyStart = posOf(it) }
            val w = mine.firstOrNull()
            if (w != null) {
                openingPlan(w, sources, all)
                val home = bases.firstOrNull()?.let { cell(it.spawnCell) } ?: w
                planVault(home, all)
            }
        }
        resolveRoles(t, mine)

        // база узнаёт свой спавн, когда он достроен
        for (b in bases) if (b.spawnId == null) {
            val sp = mySpawns.firstOrNull { it.x == b.spawnCell.x && it.y == b.spawnCell.y }
            if (sp != null) {
                b.spawnId = idOf(sp)
                if (spawnUpAt < 0) spawnUpAt = t
                println("spawn up t=$t at (${sp.x},${sp.y}) e=${energyOf(sp)}")
            }
        }
        val exp = expansion
        if (exp != null && exp.spawnId == null) {
            val sp = mySpawns.firstOrNull { it.x == exp.spawnCell.x && it.y == exp.spawnCell.y }
            if (sp != null) {
                exp.spawnId = idOf(sp)
                bases.add(exp)
                expansion = null
                builderId = null
                println("expansion spawn up t=$t at (${sp.x},${sp.y})")
            }
        }

        val enemyCombat = theirs.filter { isCombat(it) }
        val enemyTowers = all.filter { it is StructureTower && it.asDynamic().my == false }.unsafeCast<List<StructureTower>>()

        val homeSpawns = homeSpawnObjects(byId)
        val workersAll = mine.filter { liveParts(it, WORK) > 0 }
        val near = homeThreats(enemyCombat, homeSpawns, workersAll, 0)
        val wide = homeThreats(enemyCombat, homeSpawns, workersAll, THREAT_RELEASE)
        defending = if (defending) wide.isNotEmpty() else near.isNotEmpty()
        if (defending) attackedOnce = true
        val threats = if (defending) wide else emptyList()
        val myTowers = all.filter { it is StructureTower && it.asDynamic().my == true }.unsafeCast<List<StructureTower>>()
        for (b in bases) {
            val sp = b.spawnId?.let { byId[it] } as? StructureSpawn ?: continue
            planTower(t, b, sp, mine, all)
        }
        runTowers(myTowers, theirs, mine)

        runWorkers(t, mine, byId, sites.filter { it.my == true }, sources, all, enemyCombat)
        runVault(t, mine, all, mySpawns)
        runArmy(t, mine, theirs, enemyCombat, enemyTowers, all, byId, threats)
        for (b in bases) {
            val sp = b.spawnId?.let { byId[it] } as? StructureSpawn ?: continue
            if (sp.spawning == null) runSpawn(t, b, sp, mine, threats)
        }
        (vault?.spawnId?.let { byId[it] } as? StructureSpawn)?.let { sp ->
            if (sp.spawning != null) return@let
            // заправщик погиб — сейф рождает себе нового: без него спавн сейфа живёт на +1 в тик
            val left = vault?.containers?.sumOf { p -> containerAt(p, all)?.let { energyOf(it) } ?: 0 } ?: 0
            if (!hasRole("vaultBuilder") && left > 0) order(t, sp, energyOf(sp), fillerBody(), "vaultBuilder")
            else spawnFighter(t, sp, energyOf(sp), why = "vault")
        }

        logChanges(t, all, mine)
        if (t % LOG_EVERY == 0) logStatus(t, mine, theirs, mySpawns, sources, all)
    }

    // ---------- дебют ----------

    /** Домашний источник — тот, до которого рабочий дойдёт раньше; при равенстве — дальше от соперника. */
    private fun openingPlan(w: Creep, sources: Array<Source>, all: Array<GameObject>) {
        val blocked = blockedCells(all)
        val enemy = enemyStart
        val ranked = sources.map { s ->
            val ticks = ticksTo(w, s, 1)
            val far = if (enemy == null) 0 else getRange(cell(enemy), s)
            Triple(s, ticks, far)
        }.sortedWith(compareBy<Triple<Source, Int, Int>> { it.second }.thenByDescending { it.third })
        println("opening: sources by arrival " + ranked.joinToString(" ") { "(${it.first.x},${it.first.y})t=${it.second}" })
        for ((src, ticks, _) in ranked) {
            val base = planBase(src, w, blocked) ?: continue
            val r = createConstructionSite(base.spawnCell.x, base.spawnCell.y, StructureSpawn::class.js)
            println("opening: spawn site (${base.spawnCell.x},${base.spawnCell.y}) src=(${src.x},${src.y}) arrival=$ticks " +
                "slots=${base.slots.joinToString(" ") { "${it.x},${it.y}" }} batch=${batchFor(w)} err=${r.error}")
            if (r.error == null) { bases.add(base); return }
        }
    }

    /** Партия наполнения при стройке: сколько копать, чтобы стройка съела всё целыми тиками. Один тик — одно
     *  действие из копать/строить, поэтому скорость партии E — E / (E/копка + ⌈E/стройка⌉); у W4C1: 40 → 5,71. */
    private fun batchFor(c: Creep): Int {
        val work = liveParts(c, WORK)
        val cap = c.store.getCapacity(RESOURCE_ENERGY) ?: 0
        val h = HARVEST_POWER * work
        val b = BUILD_POWER * work
        if (h <= 0 || b <= 0 || cap <= 0) return cap
        var best = h
        var bestRate = 0.0
        var e = h
        while (e <= cap) {
            val ticks = e / h + (e + b - 1) / b
            val rate = e.toDouble() / ticks
            if (rate > bestRate + 1e-9) { bestRate = rate; best = e }
            e += h
        }
        return best
    }

    // ---------- рабочие ----------

    private fun runWorkers(
        t: Int, mine: List<Creep>, byId: Map<String, GameObject>, mySites: List<ConstructionSite>,
        sources: Array<Source>, all: Array<GameObject>, enemyCombat: List<Creep>,
    ) {
        val workers = mine.filter { !it.spawning && liveParts(it, WORK) > 0 }
        slotOf.keys.retainAll(workers.map { idOf(it) }.toSet())
        if (builderPending) {
            val sig = bodyText(builderBody())
            val b = mine.firstOrNull { bodyOf(it) == sig && idOf(it) !in slotOf }
            if (b != null) { builderId = idOf(b); builderPending = false; println("builder t=$t is ${idOf(b)} at (${b.x},${b.y})") }
        }
        if (builderId != null && mine.none { idOf(it) == builderId }) {
            println("builder lost t=$t")
            builderId = null
            expansion = null
            expansionPlaced = false
        }
        for (w in workers) {
            val id = idOf(w)
            if (id in roleOf) continue
            if (id == builderId) { runBuilder(w, byId, mySites, all, enemyCombat); continue }
            val slot = slotOf[id] ?: assignSlot(w) ?: continue
            val base = bases.firstOrNull { it.slots.contains(slot) } ?: continue
            val src = byId[base.sourceId] as? Source ?: continue
            if (w.x != slot.x || w.y != slot.y) {
                w.moveTo(cell(slot))
                if (getRange(w, src) > 1) continue
            }
            val spawn = base.spawnId?.let { byId[it] } as? StructureSpawn
            if (spawn == null) {
                // спавн ещё площадка: цикл дебюта
                val site = mySites.firstOrNull { it.x == base.spawnCell.x && it.y == base.spawnCell.y }
                val e = w.store[RESOURCE_ENERGY] ?: 0
                if (site == null) { if (src.energy > 0) w.harvest(src); continue }
                if (e >= batchFor(w) || (src.energy == 0 && e > 0)) w.build(site)
                else w.harvest(src)
                continue
            }
            harvestAndDeliver(t, w, src, spawn, mySites)
        }
    }

    /** Свободная клетка ближайшей базы; `null`, если все заняты. */
    private fun assignSlot(w: Creep): Pos? {
        val taken = slotOf.values.toSet()
        for (b in bases.sortedBy { getRange(w, cell(it.spawnCell)) }) {
            val free = b.slots.filter { it !in taken }.minByOrNull { getRange(w, cell(it)) } ?: continue
            slotOf[idOf(w)] = free
            return free
        }
        return null
    }

    /** Добытчик на клетке у источника и спавна: копает и сдаёт в ОДИН тик, каждый тик. Движок исполняет копку
     *  раньше сдачи, а сдаёт запас начала тика: v2, сдававший раз в семь тиков перед переполнением, терял копку
     *  переполнения (7 в тик вместо 8 у W4, замер 27.09.2026); сдача каждый тик оставляет в запасе одну копку и не
     *  теряет ничего. Спавн полон — копает, пока есть место, и достраивает площадки в досягаемости. */
    private fun harvestAndDeliver(t: Int, w: Creep, src: Source, spawn: StructureSpawn, mySites: List<ConstructionSite>) {
        val e = w.store[RESOURCE_ENERGY] ?: 0
        val cap = w.store.getCapacity(RESOURCE_ENERGY) ?: 0
        val h = HARVEST_POWER * liveParts(w, WORK)
        // башня рядом ест первой: один выстрел — 10, а без неё дом беззащитен
        val tower = getObjectsByPrototype(StructureTower::class).firstOrNull {
            it.my == true && getRange(w, it) <= 1 && (it.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0
        }
        if (tower != null && e > 0) {
            if (src.energy > 0 && getRange(w, src) <= 1 && cap - e >= h) w.harvest(src)
            w.transfer(tower, RESOURCE_ENERGY)
            return
        }
        // площадка в досягаемости и дому ничто не грозит: строим циклом дебюта (копка и стройка — одно действие)
        val site = mySites.filter { getRange(w, it) <= 3 }.minByOrNull { getRange(w, it) }
        // на клетке площадки стоит крип — стройка препятствия не идёт; v6 так простоял 400 тиков: боец встал на
        // площадку башни, рабочий с полным запасом каждый тик «строил» впустую и не копал, спавн жил на +1 в тик
        val siteFree = site != null && getObjectsByPrototype(Creep::class).none { it.x == site.x && it.y == site.y }
        if (site != null && siteFree && !defending) {
            if (e >= batchFor(w) || (src.energy == 0 && e > 0)) {
                if (w.build(site).asDynamic().unsafeCast<Int>() == 0) return
            } else if (src.energy > 0) { w.harvest(src); return }
        }
        val spawnFree = spawn.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0
        val canDeliver = e > 0 && spawnFree > 0 && getRange(w, spawn) <= 1
        // копка исполняется раньше сдачи: место под неё — от запаса начала тика
        if (src.energy > 0 && getRange(w, src) <= 1 && cap - e >= h) {
            w.harvest(src)
        } else if (spawnFree <= 0 && e > 0) {
            val site = mySites.filter { getRange(w, it) <= 3 }.minByOrNull { getRange(w, it) }
            if (site != null) w.build(site)
        }
        if (canDeliver) w.transfer(spawn, RESOURCE_ENERGY)
    }

    // ---------- второй спавн ----------

    /** Путь в тиках тела 1:1 (равнина 1, болото 5): так ходят строитель и рабочий старта. */
    private fun pathTicks(from: Position, to: Position): Int {
        val r = searchPath(from, SearchGoal(pos = to, range = 1), SearchPathOptions(plainCost = 1, swampCost = 5))
        return if (r.incomplete) Int.MAX_VALUE / 4 else r.cost
    }

    /** Цель второго спавна: источник, до которого наш спавн доходит раньше соперника с наибольшим запасом (его
     *  спавн, а пока его нет — стартовая клетка): строитель идёт один, и спорный источник (v3 выбрал центральный
     *  с запасом в 10 тиков) — это строитель и площадка под его первой же волной. */
    private fun expansionTarget(from: Position, sources: Array<Source>, all: Array<GameObject>): Base? {
        val enemySpawns = all.filter { it is StructureSpawn && it.asDynamic().my == false }
        val enemyFrom: List<Position> = enemySpawns.ifEmpty { listOfNotNull(enemyStart?.let { cell(it) }) }
        if (enemyFrom.isEmpty()) return null
        val taken = bases.map { it.sourceId }.toSet()
        val blocked = blockedCells(all)
        val ranked = sources.filter { idOf(it) !in taken }.map { s -> Triple(s, pathTicks(from, s), enemyFrom.minOf { pathTicks(it, s) }) }
        println("expansion: candidates " + ranked.joinToString(" ") { "(${it.first.x},${it.first.y})us=${it.second}/them=${it.third}" })
        for ((s, _, _) in ranked.filter { it.second < it.third }.sortedWith(compareByDescending<Triple<Source, Int, Int>> { it.third - it.second }.thenBy { it.second })) {
            planBase(s, from, blocked)?.let { return it }
        }
        return null
    }

    private fun runBuilder(w: Creep, byId: Map<String, GameObject>, mySites: List<ConstructionSite>, all: Array<GameObject>, enemyCombat: List<Creep>) {
        val base = expansion ?: return
        val src = byId[base.sourceId] as? Source ?: return
        val slot = base.slots.minByOrNull { getRange(w, cell(it)) } ?: return
        if (getRange(w, src) > 1) { w.moveTo(cell(slot)); return }
        if (!expansionPlaced) {
            val r = createConstructionSite(base.spawnCell.x, base.spawnCell.y, StructureSpawn::class.js)
            println("expansion: site (${base.spawnCell.x},${base.spawnCell.y}) src=(${src.x},${src.y}) t=${getTicks()} err=${r.error}")
            expansionPlaced = r.error == null
            if (!expansionPlaced) { expansion = null; return }
        }
        val site = mySites.firstOrNull { it.x == base.spawnCell.x && it.y == base.spawnCell.y }
        val e = w.store[RESOURCE_ENERGY] ?: 0
        if (site == null) { if (src.energy > 0) w.harvest(src); return }
        if (e >= batchFor(w) || (src.energy == 0 && e > 0)) w.build(site) else w.harvest(src)
    }

    // ---------- спавн ----------

    private fun workerBody(missingWork: Int, energy: Int): Array<BodyPartType> {
        // добытчик стоит на клетке у источника: один MOVE дотащит его до соседней клетки, CARRY — для сдачи
        val cost1 = (BODYPART_COST[WORK] ?: 100)
        val fixed = (BODYPART_COST[CARRY] ?: 50) + (BODYPART_COST[MOVE] ?: 50)
        val w = minOf(missingWork, maxOf(1, (energy - fixed) / cost1)).coerceAtLeast(1)
        return (List(w) { WORK } + CARRY + MOVE).toTypedArray()
    }

    private fun fighterBody(energy: Int): Array<BodyPartType> {
        val pair = (BODYPART_COST[MOVE] ?: 50) + (BODYPART_COST[RANGED_ATTACK] ?: 150)
        val k = minOf(5, energy / pair)
        val out = ArrayList<BodyPartType>()
        repeat(k) { out.add(MOVE); out.add(RANGED_ATTACK) }
        return out.toTypedArray()
    }

    /** Строитель второго спавна: столько WORK, чтобы в своём цикле копать весь приток источника, и MOVE на каждую
     *  часть — чтобы по болоту дойти как рабочий старта. */
    private fun builderBody(): Array<BodyPartType> {
        val work = (SOURCE_ENERGY_REGEN + HARVEST_POWER - 1) / HARVEST_POWER
        return (List(work) { WORK } + CARRY + List(work + 1) { MOVE }).toTypedArray()
    }

    private fun homeWork(b: Base, mine: List<Creep>): Int =
        mine.filter { c ->
            idOf(c) != builderId &&
                (slotOf[idOf(c)]?.let { b.slots.contains(it) } == true || (c.spawning && getRange(c, cell(b.spawnCell)) <= 1 && liveParts(c, WORK) > 0))
        }.sumOf { liveParts(it, WORK) }

    /** Угрозы дому: боевые враги у наших спавнов или у наших рабочих (с запасом `extra` — для снятия защиты). */
    private fun homeThreats(enemyCombat: List<Creep>, homeSpawns: List<GameObject>, workers: List<Creep>, extra: Int): List<Creep> =
        enemyCombat.filter { e ->
            homeSpawns.any { getRange(e, it) <= HOME_THREAT_RANGE + extra } || workers.any { getRange(e, it) <= WORKER_THREAT_RANGE + extra }
        }

    // ---------- башня ----------

    private fun towerAt(b: Base, all: Array<GameObject>): GameObject? {
        val c = b.towerCell ?: return null
        return all.firstOrNull { (it is StructureTower || it is ConstructionSite) && it.x == c.x && it.y == c.y && it.asDynamic().my == true }
    }

    /** Башня у базы: ставится, когда у дома уже есть боец (первые тики спавна кормят бойца, который прикроет
     *  стройку); клетка — рядом с клетками добытчиков (они её строят и кормят без шага), не отнимая у спавна
     *  последний выход. Кормленая башня — 1000 в упор и ~550 на десяти клетках раз в десять тиков при 3000 хитов:
     *  против стрелков, кружащих у дома (けろびー), и потока мили-лекарей (Hardy) это дешевле любого бойца. */
    private fun planTower(t: Int, b: Base, spawn: StructureSpawn, mine: List<Creep>, all: Array<GameObject>) {
        if (towerAt(b, all) != null) return
        if (b.towerCell != null) { println("tower at (${b.towerCell}) gone t=$t"); b.towerCell = null }
        if (mine.none { isCombat(it) }) return
        // башня базы — после сейфа, если дом ещё не трогали: её 1250 — сто с лишним тиков всей добычи, а сейф за
        // те же деньги пробойщика отдаёт 10000
        val v = vault
        if (v != null && v.stage != "run" && !attackedOnce) return
        val blocked = blockedCells(all)
        fun exits(extra: Pos): Int {
            var n = 0
            for (x in b.spawnCell.x - 1..b.spawnCell.x + 1) for (y in b.spawnCell.y - 1..b.spawnCell.y + 1) {
                val p = Pos(x, y)
                if (p != b.spawnCell && p != extra && p !in b.slots && walkable(p, blocked)) n++
            }
            return n
        }
        var best: Pos? = null
        var bestScore = Int.MIN_VALUE
        for (dx in -3..3) for (dy in -3..3) {
            val c = Pos(b.spawnCell.x + dx, b.spawnCell.y + dy)
            if (c == b.spawnCell || c in b.slots || !walkable(c, blocked)) continue
            val feeders = b.slots.count { cheb(it, c) <= 1 }
            if (feeders == 0 || exits(c) == 0) continue
            val score = feeders * 1000 + (if (isSwamp(c)) 0 else 100) - cheb(c, b.spawnCell)
            if (score > bestScore) { bestScore = score; best = c }
        }
        val c = best ?: return
        val r = createConstructionSite(c.x, c.y, StructureTower::class.js)
        println("tower site t=$t at (${c.x},${c.y}) base=(${b.spawnCell.x},${b.spawnCell.y}) err=${r.error}")
        if (r.error == null) b.towerCell = c
    }

    /** Башня бьёт боевого врага в досягаемости (ближнего — у него выстрел сильнее), при равенстве — самого битого;
     *  без врагов лечит самого битого нашего. */
    private fun runTowers(towers: List<StructureTower>, theirs: List<Creep>, mine: List<Creep>) {
        for (tw in towers) {
            if (energyOf(tw) < TOWER_ENERGY_COST || tw.cooldown > 0) continue
            val foe = theirs.filter { getRange(tw, it) <= TOWER_RANGE }
                .sortedWith(compareBy<Creep> { if (isCombat(it)) 0 else 1 }.thenBy { getRange(tw, it) }.thenBy { it.hits })
                .firstOrNull()
            if (foe != null) { tw.attack(foe); continue }
            val hurt = mine.filter { !it.spawning && it.hits < it.hitsMax && getRange(tw, it) <= TOWER_RANGE }.maxByOrNull { it.hitsMax - it.hits }
            if (hurt != null) tw.heal(hurt)
        }
    }

    private fun runSpawn(t: Int, b: Base, spawn: StructureSpawn, mine: List<Creep>, threats: List<Creep>) {
        val energy = energyOf(spawn)
        val needWork = (SOURCE_ENERGY_REGEN + HARVEST_POWER - 1) / HARVEST_POWER
        val haveWork = homeWork(b, mine)
        val freeSlots = b.slots.count { it !in slotOf.values.toSet() }
        val fighters = mine.filter { isCombat(it) }
        val threat = threats
        val body: Array<BodyPartType>
        val why: String
        if (threat.isNotEmpty() && power(fighters) < power(threat) * PUSH_RATIO) {
            // только полное тело: v4 рожал под угрозой M2R2 по одному, и все они погибли поодиночке (basic измерил
            // то же: тринадцать тел по 300 проиграли трём M5R5 на равной энергии)
            return spawnFighter(t, spawn, energy, why = "threat")
        } else if (haveWork < needWork && freeSlots > 0) {
            body = workerBody(needWork - haveWork, energy)
            why = "work $haveWork/$needWork"
        } else if (b === bases.first() && threat.isEmpty() && fighters.isNotEmpty() && vaultOrder(t, spawn, energy)) {
            return
        } else if (bases.size == 1 && expansion == null && builderId == null && !builderPending && fighters.isNotEmpty() &&
            threat.isEmpty() && b === bases.first() && (vault == null || vault?.stage == "run")) {
            body = builderBody()
            if (energy < costOf(body)) return
            val target = expansionTarget(spawn, getObjectsByPrototype(Source::class), getObjects()) ?: run {
                return spawnFighter(t, spawn, energy, why = "army")
            }
            val r = spawn.spawnCreep(body)
            val c = r.`object`
            println("spawn t=$t ${bodyText(body)} cost=${costOf(body)} energy=$energy why=expansion to (${target.spawnCell.x},${target.spawnCell.y}) err=${r.error}")
            if (r.error == null) { builderPending = true; expansion = target; expansionPlaced = false }
            println("spawn object id=${c?.let { idOf(it) }}")
            return
        } else {
            return spawnFighter(t, spawn, energy, why = "army")
        }
        if (energy < costOf(body)) return
        val r = spawn.spawnCreep(body)
        println("spawn t=$t ${bodyText(body)} cost=${costOf(body)} energy=$energy why=$why err=${r.error}")
    }

    /** Заказ для сейфа: пробойщик, пока стена цела; строитель — когда пролом готов или откроется раньше, чем
     *  строитель дойдёт (оставшиеся хиты стены / урон пробойщика против пути строителя). Возвращает, занят ли спавн. */
    private fun vaultOrder(t: Int, spawn: StructureSpawn, energy: Int): Boolean {
        val v = vault ?: return false
        if (v.stage == "run") return false
        val all = getObjects()
        if (v.stage == "breach" && !hasRole("breacher")) return order(t, spawn, energy, breacherBody(), "breacher")
        if (hasRole("vaultBuilder")) return false
        val wallLeft = wallObject(v, all)?.asDynamic()?.hits?.unsafeCast<Int>() ?: 0
        val breacher = getObjectsByPrototype(Creep::class).firstOrNull { roleOf[idOf(it)] == "breacher" }
        val breakTicks = if (wallLeft <= 0) 0 else if (breacher == null || getRange(breacher, cell(v.wall!!)) > 1) Int.MAX_VALUE
            else wallLeft / maxOf(1, liveParts(breacher, ATTACK) * ATTACK_POWER)
        val walk = pathTicks(spawn, cell(v.outside ?: v.spawnCell))
        if (breakTicks <= walk + vaultBuilderBody().size * CREEP_SPAWN_TIME) return order(t, spawn, energy, vaultBuilderBody(), "vaultBuilder")
        return false
    }

    private fun order(t: Int, spawn: StructureSpawn, energy: Int, body: Array<BodyPartType>, role: String): Boolean {
        if (energy < costOf(body)) return true
        val r = spawn.spawnCreep(body)
        println("spawn t=$t ${bodyText(body)} cost=${costOf(body)} energy=$energy why=$role err=${r.error}")
        if (r.error == null) pendingRoles.add(role to bodyText(body))
        return true
    }

    private fun spawnFighter(t: Int, spawn: StructureSpawn, energy: Int, why: String) {
        val full = fighterBody(SPAWN_ENERGY_CAPACITY)
        if (energy < costOf(full)) return
        val r = spawn.spawnCreep(full)
        println("spawn t=$t ${bodyText(full)} cost=${costOf(full)} energy=$energy why=$why err=${r.error}")
    }

    // ---------- сила ----------

    private fun isCombat(c: Creep) = c.body.any { (it.type == RANGED_ATTACK || it.type == ATTACK || it.type == HEAL) && it.hits > 0 }

    private fun dpsOf(c: Creep) = liveParts(c, RANGED_ATTACK) * RANGED_ATTACK_POWER + liveParts(c, ATTACK) * ATTACK_POWER

    private fun healOf(c: Creep) = liveParts(c, HEAL) * HEAL_POWER

    /** Сила группы по Ланчестеру: √(Σ(урон+лечение) × Σхиты) — линейна по числу одинаковых бойцов. */
    private fun power(group: List<Creep>): Double {
        val out = group.sumOf { dpsOf(it) + healOf(it) }.toDouble()
        val hits = group.sumOf { it.hits }.toDouble()
        return kotlin.math.sqrt(out * hits)
    }

    /** Выстрел башни на дистанции r по константам рантайма (d.ts клиента устаревает: там falloffRange 20, а
     *  рантайм говорит 21): полная сила до оптимальной дальности, дальше линейный спад до нуля. */
    private fun towerShot(r: Int): Double {
        val power = TOWER_POWER_ATTACK.asDynamic().unsafeCast<Double>()
        val optimal = TOWER_OPTIMAL_RANGE.asDynamic().unsafeCast<Double>()
        val falloffRange = TOWER_FALLOFF_RANGE.asDynamic().unsafeCast<Double>()
        val falloff = TOWER_FALLOFF.asDynamic().unsafeCast<Double>()
        if (r <= optimal) return power
        if (r >= falloffRange) return 0.0
        return power * (1 - falloff * (r - optimal) / (falloffRange - optimal))
    }

    /** Кормленая башня как боец: выстрел на дальности нашего стрелка (3 клетки от спавна, башня рядом — 4) раз в
     *  перезарядку и её хиты. */
    private fun towerPower(towers: List<StructureTower>): Double {
        val fed = towers.filter { energyOf(it) > 0 }
        return towerPowerOf(fed.size, fed.sumOf { it.hits ?: 0 }.toDouble())
    }

    private fun towerPowerOf(count: Int, hits: Double): Double {
        if (count <= 0) return 0.0
        val dps = count * towerShot(RANGED_RANGE + 1) / TOWER_COOLDOWN
        return kotlin.math.sqrt(dps * hits)
    }

    /** Сколько его площадок башен достроится к приходу нашей группы: скорость — по его же прогрессу с первой
     *  встречи площадки (пока замера нет — считаем достроенной, если готова хотя бы наполовину), приход — путь
     *  группы в тиках тела 1:1. */
    private fun pendingTowers(t: Int, all: Array<GameObject>, group: List<Creep>): Int {
        val towerCost = CONSTRUCTION_COST.asDynamic()["StructureTower"].unsafeCast<Int?>() ?: return 0
        val sites = all.filter { it is ConstructionSite && it.asDynamic().my == false }.unsafeCast<List<ConstructionSite>>()
            .filter { s -> (s.structure?.let { protoName(it) } == "StructureTower") || s.progressTotal == towerCost }
        enemySiteSeen.keys.retainAll(sites.map { idOf(it) }.toSet())
        val from = group.firstOrNull() ?: return sites.size
        var n = 0
        for (s in sites) {
            val p = s.progress ?: 0
            val total = s.progressTotal ?: towerCost
            val seen = enemySiteSeen.getOrPut(idOf(s)) { t to p }
            val dt = t - seen.first
            val eta = if (dt >= 10 && p > seen.second) (total - p).toDouble() * dt / (p - seen.second) else if (p * 2 >= total) 0.0 else Double.MAX_VALUE
            if (eta <= pathTicks(from, s)) n++
        }
        return n
    }

    // ---------- армия ----------

    private fun runArmy(
        t: Int, mine: List<Creep>, theirs: List<Creep>, enemyCombat: List<Creep>, enemyTowers: List<StructureTower>,
        all: Array<GameObject>, byId: Map<String, GameObject>, threats: List<Creep>,
    ) {
        val fighters = mine.filter { !it.spawning && isCombat(it) && idOf(it) !in roleOf }
        wave.retainAll(fighters.map { idOf(it) }.toSet())
        val homeSpawns = homeSpawnObjects(byId)
        // сбор — у спавна, ближайшего к сопернику: туда сходятся бойцы всех баз
        val enemy = enemyStart
        val home: Position = homeSpawns.minByOrNull { if (enemy == null) 0 else getRange(it, cell(enemy)) }
            ?: bases.firstOrNull()?.let { cell(it.spawnCell) } ?: return
        val enemyObjects = all.filter { it.asDynamic().my == false && it !is Creep && it !is ConstructionSite }
        val homeGroup = fighters.filter { idOf(it) !in wave }
        val pending = pendingTowers(t, all, homeGroup)
        val enemyPower = power(enemyCombat) + towerPower(enemyTowers) + towerPowerOf(pending, TOWER_HITS.toDouble() * pending)
        val lastCall = t > arenaInfo.ticksLimit - 600

        // выход волны: сила дома больше всей армии и башен соперника (и тех его площадок башен, что достроятся к
        // нашему подходу — v5 ушёл одним бойцом против одного M4R3, пока башня была площадкой, и лёг под ней).
        // Уже ушедшая волна не держит дом: дом, который сам сильнее соперника, выходит следом
        if (threats.isEmpty() && homeGroup.isNotEmpty() &&
            (power(homeGroup) >= enemyPower * PUSH_RATIO || lastCall) && (enemyObjects.isNotEmpty() || theirs.isNotEmpty())) {
            for (f in homeGroup) wave.add(idOf(f))
            println("push t=$t wave=${wave.size} power=${power(homeGroup).toInt()} vs enemy=${enemyPower.toInt()} (army ${power(enemyCombat).toInt()} towers ${towerPower(enemyTowers).toInt()} pending=$pending)${if (lastCall) " lastCall" else ""}")
        }
        val waveCreeps = fighters.filter { idOf(it) in wave }
        if (waveCreeps.isNotEmpty()) {
            // отход: на месте волна слабее местной силы соперника
            val center = waveCreeps.minByOrNull { c -> waveCreeps.sumOf { getRange(it, c) } }!!
            val local = enemyCombat.filter { getRange(it, center) <= LOCAL_RANGE }
            val localTowers = enemyTowers.filter { getRange(it, center) <= TOWER_FALLOFF_RANGE / 2 }
            val localPower = power(local) + towerPower(localTowers)
            if (!lastCall && localPower > 0 && power(waveCreeps) < localPower * RETREAT_RATIO) {
                println("retreat t=$t wave=${waveCreeps.size} power=${power(waveCreeps).toInt()} vs local=${localPower.toInt()}")
                wave.clear()
            }
        }
        val posture = when {
            wave.isNotEmpty() -> "push"
            threats.isNotEmpty() -> "defend"
            else -> "gather"
        }
        if (posture != lastPosture) {
            println("posture t=$t $lastPosture->$posture fighters=${fighters.size} threats=${threats.size} enemyPower=${enemyPower.toInt()}")
            lastPosture = posture
        }

        // волна: ближайшая цель — боевые враги рядом, иначе постройки, иначе остальные крипы
        val waveNow = fighters.filter { idOf(it) in wave }
        if (waveNow.isNotEmpty()) {
            val front = waveNow.minByOrNull { f -> nearestTargetRange(f, enemyCombat, enemyObjects, theirs) }!!
            val goal = pickGoal(front, enemyCombat, enemyObjects, theirs)
            if (goal != null) {
                // все идут к цели; передние ждут отставших, которые могут догнать, и не дольше срока
                val toGoal = waveNow.associateWith { getRange(it, goal) }
                val frontD = toGoal.values.minOrNull() ?: 0
                val catching = waveNow.any { (toGoal[it] ?: 0) - frontD in (COHESION + 1)..CATCH_UP }
                holdTicks = if (catching) holdTicks + 1 else 0
                val hold = catching && holdTicks <= HOLD_LIMIT
                val keep = if (goal is Creep) RANGED_RANGE else RANGED_RANGE - 1
                for (f in waveNow) {
                    shoot(f, theirs, enemyObjects)
                    if (fleeMelee(f, enemyCombat)) continue
                    val d = toGoal[f] ?: 0
                    val fighting = theirs.any { isCombat(it) && getRange(f, it) <= RANGED_RANGE + 1 }
                    if (hold && d - frontD <= COHESION && !fighting) continue
                    if (d > keep) f.moveTo(goal)
                }
            } else for (f in waveNow) shoot(f, theirs, enemyObjects)
        }
        // дом: угрозу бьём всей кучей, если на месте сильнее (с башнями) или она уже бьёт спавн или рабочего;
        // иначе держимся у спавна — туда ей придётся подойти на выстрел. За пределы домашней зоны не гонимся
        val myTowers = all.filter { it is StructureTower && it.asDynamic().my == true }.unsafeCast<List<StructureTower>>()
        val workers = mine.filter { liveParts(it, WORK) > 0 }
        val target = threats.minByOrNull { e -> homeSpawns.minOfOrNull { getRange(e, it) } ?: 0 }
        val anchor: Position = target?.let { tg -> homeSpawns.minByOrNull { getRange(tg, it) } } ?: home
        var engage = false
        if (target != null) {
            val local = threats.filter { getRange(it, target) <= LOCAL_RANGE }
            val ours = power(homeGroup) + towerPower(myTowers.filter { getRange(it, target) <= TOWER_RANGE })
            val striking = getRange(target, anchor) <= RANGED_RANGE + 1 || workers.any { getRange(target, it) <= RANGED_RANGE + 1 }
            // предела погони нет: угроза по определению в домашней зоне и выпадает из неё сама. v9 бил только в 13
            // клетках от спавна, а защита держится до 15 — угроза в этом зазоре держала защиту (и запрет волны) вечно
            engage = ours >= power(local) || striking
        }
        val blocked = blockedCells(all)
        val reserved = reservedCells(all)
        // допуск сбора растёт с толпой: квадрат со стороной 2r+1 вмещает всех вдвое с запасом (v9: 35 бойцов у точки с
        // допуском 2 — 25 клеток — забили клетки у спавна, и новорождённому некуда было выйти)
        val rallySpread = maxOf(2, kotlin.math.ceil(kotlin.math.sqrt(2.0 * homeGroup.size) / 2).toInt())
        for (f in homeGroup) {
            if (idOf(f) in wave) continue
            shoot(f, theirs, enemyObjects)
            if (fleeMelee(f, enemyCombat)) continue
            if (engage && target != null) {
                if (getRange(f, target) > RANGED_RANGE) f.moveTo(target)
            } else {
                // сбор — не у самого спавна, а в точке сбора: клетки у спавна, добытчиков, башни и площадок заняты
                // делом (выход для рождения, копка, стройка), и боец на них ломает базу
                val rally = rallyFor(anchor, reserved, blocked)
                if (Pos(f.x, f.y) in reserved || getRange(f, cell(rally)) > rallySpread) f.moveTo(cell(rally))
            }
        }
    }

    /** Все наши спавны: базы и сейф. Один список для угроз, сбора и защиты — v9 считал его в двух местах, и во втором
     *  не было спавна сейфа: угроза у сейфа держала защиту, а бойцы стояли у первой базы до конца матча. */
    private fun homeSpawnObjects(byId: Map<String, GameObject>): List<GameObject> =
        bases.mapNotNull { b -> b.spawnId?.let { byId[it] } } + listOfNotNull(vault?.spawnId?.let { byId[it] })

    /** Клетки, на которых боец не стоит: у спавна (выходы для рождения), клетки добытчиков, башни и наших площадок. */
    private fun reservedCells(all: Array<GameObject>): Set<Pos> {
        val out = HashSet<Pos>()
        for (b in bases) {
            for (x in b.spawnCell.x - 1..b.spawnCell.x + 1) for (y in b.spawnCell.y - 1..b.spawnCell.y + 1) out.add(Pos(x, y))
            out.addAll(b.slots)
            b.towerCell?.let { out.add(it) }
        }
        vault?.let { v ->
            for (x in v.spawnCell.x - 1..v.spawnCell.x + 1) for (y in v.spawnCell.y - 1..v.spawnCell.y + 1) out.add(Pos(x, y))
            v.towerCell?.let { out.add(it) }
            v.wall?.let { out.add(it) }
        }
        for (o in all) if (o is ConstructionSite && o.asDynamic().my == true) out.add(Pos(o.x, o.y))
        return out
    }

    /** Точка сбора у спавна: свободная проходимая клетка в 4–5 шагах от него, ближе всех к сопернику (равнина лучше):
     *  бойцы в двух клетках от неё стоят не ближе двух шагов к спавну. */
    private fun rallyFor(anchor: Position, reserved: Set<Pos>, blocked: Set<Pos>): Pos {
        val enemy = enemyStart
        var best = Pos(anchor.x, anchor.y)
        var bestScore = Int.MAX_VALUE
        for (dx in -5..5) for (dy in -5..5) {
            val c = Pos(anchor.x + dx, anchor.y + dy)
            val r = cheb(c, Pos(anchor.x, anchor.y))
            // не внутри сейфа: из кармана один выход шириной в клетку, и волна из него вытекала бы по одному
            if (r < 4 || c in reserved || !walkable(c, blocked) || vault?.interior?.contains(c) == true) continue
            val score = (if (enemy == null) 0 else cheb(c, enemy)) * 10 + (if (isSwamp(c)) 5 else 0)
            if (score < bestScore) { bestScore = score; best = c }
        }
        return best
    }

    private fun nearestTargetRange(f: Creep, enemyCombat: List<Creep>, enemyObjects: List<GameObject>, theirs: List<Creep>): Int {
        val g = pickGoal(f, enemyCombat, enemyObjects, theirs) ?: return Int.MAX_VALUE
        return getRange(f, g)
    }

    /** Цель волны: боевой враг в пределах местной дальности, иначе ближайшая постройка (башня раньше спавна),
     *  иначе ближайший крип. */
    private fun pickGoal(f: Creep, enemyCombat: List<Creep>, enemyObjects: List<GameObject>, theirs: List<Creep>): GameObject? {
        enemyCombat.filter { getRange(f, it) <= LOCAL_RANGE }.minByOrNull { getRange(f, it) }?.let { return it }
        val structures = enemyObjects.filter { it is Structure }
        structures.filter { it is StructureTower }.minByOrNull { getRange(f, it) }?.let { tw ->
            val sp = structures.filter { it is StructureSpawn }.minByOrNull { getRange(f, it) }
            if (sp == null || getRange(f, tw) <= getRange(f, sp) + 3) return tw
        }
        structures.minByOrNull { getRange(f, it) }?.let { return it }
        return theirs.minByOrNull { getRange(f, it) }
    }

    /** Стрельба: по крипам в досягаемости (сперва боевые, самые битые), иначе по постройкам; массовая — когда она
     *  бьёт сильнее одиночной. Возвращает, стрелял ли. */
    private fun shoot(f: Creep, theirs: List<Creep>, enemyObjects: List<GameObject>): Boolean {
        // мили (пробойщик после пролома): вплотную — крип, иначе постройка
        if (liveParts(f, ATTACK) > 0) {
            val adj = theirs.filter { getRange(f, it) <= 1 }.minByOrNull { it.hits }
                ?: enemyObjects.filter { it is Structure && getRange(f, it) <= 1 }.firstOrNull()
            if (adj != null) { f.attack(adj); return true }
        }
        if (liveParts(f, RANGED_ATTACK) == 0) return false
        val inRange = theirs.filter { getRange(f, it) <= RANGED_RANGE }
        val mass = inRange.sumOf { when (getRange(f, it)) { 0, 1 -> 10; 2 -> 4; else -> 1 } }
        if (mass > 10) { f.rangedMassAttack(); return true }
        val target = inRange.sortedWith(compareBy<Creep> { if (isCombat(it)) 0 else 1 }.thenBy { it.hits }).firstOrNull()
        if (target != null) { f.rangedAttack(target); return true }
        val st = enemyObjects.filter { it is Structure && getRange(f, it) <= RANGED_RANGE }
            .sortedWith(compareBy<GameObject> { if (it is StructureTower) 0 else if (it is StructureRampart) 1 else if (it is StructureSpawn) 2 else 3 })
            .firstOrNull()
        if (st != null) { f.rangedAttack(st); return true }
        return false
    }

    /** Стрелок не стоит рядом с мили: шаг прочь, если враг с ATTACK в двух клетках. */
    private fun fleeMelee(f: Creep, enemyCombat: List<Creep>): Boolean {
        if (liveParts(f, RANGED_ATTACK) == 0) return false
        val melee = enemyCombat.filter { liveParts(it, ATTACK) > 0 && getRange(f, it) <= 2 }
        if (melee.isEmpty()) return false
        val goals = melee.map { SearchGoal(pos = it, range = 3) }.toTypedArray()
        val step = searchPath(f, goals, SearchPathOptions(flee = true)).path.firstOrNull() ?: return false
        f.moveTo(step)
        return true
    }

    // ---------- журнал ----------

    private fun dumpWorld(all: Array<GameObject>) {
        println("hello season4 spawn-and-swamp-advanced $BOT_VERSION")
        println("tuning: push=$PUSH_RATIO retreat=$RETREAT_RATIO homeThreat=$HOME_THREAT_RANGE workerThreat=$WORKER_THREAT_RANGE " +
            "cohesion=$COHESION local=$LOCAL_RANGE fighter=${bodyText(fighterBody(SPAWN_ENERGY_CAPACITY))} builder=${bodyText(builderBody())}")
        println("arena: name=${arenaInfo.name} level=${arenaInfo.level} season=${arenaInfo.season} ticksLimit=${arenaInfo.ticksLimit} " +
            "cpu=${arenaInfo.cpuTimeLimit} cpuFirst=${arenaInfo.cpuTimeLimitFirstTick}")
        println("constants: build=${JSON.stringify(CONSTRUCTION_COST)} body=${JSON.stringify(BODYPART_COST)} " +
            "sourceRegen=$SOURCE_ENERGY_REGEN harvest=$HARVEST_POWER buildPower=$BUILD_POWER carry=$CARRY_CAPACITY " +
            "spawnCap=$SPAWN_ENERGY_CAPACITY spawnHits=$SPAWN_HITS spawnTime=$CREEP_SPAWN_TIME decay=$RESOURCE_DECAY " +
            "maxSites=$MAX_CONSTRUCTION_SITES rampart=$RAMPART_HITS tower=$TOWER_POWER_ATTACK/$TOWER_FALLOFF_RANGE " +
            "obstacles=${JSON.stringify(OBSTACLE_OBJECT_TYPES)}")
        val byProto = all.groupBy { protoName(it) }
        println("objects: " + byProto.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value.size}" })
        for ((name, list) in byProto.entries.sortedBy { it.key }) {
            if (name == "StructureWall" || name == "Source" || name == "StructureContainer") continue
            for (o in list) println("obj: ${describe(o)}")
        }
        // рельеф карты случаен (раскладка объектов постоянна) — печатаем её, чтобы матч можно было перечитать
        val rows = Array(100) { CharArray(100) }
        for (y in 0 until 100) for (x in 0 until 100) {
            val ter = getTerrainAt(cell(x, y))
            rows[y][x] = when (ter) { TERRAIN_WALL -> '#'; TERRAIN_SWAMP -> '~'; else -> '.' }
        }
        for (o in all) {
            if (o.x !in 0..99 || o.y !in 0..99) continue
            rows[o.y][o.x] = when (protoName(o)) {
                "Source" -> 'S'
                "StructureContainer" -> 'C'
                "StructureWall" -> 'X'
                "Creep" -> if (o.asDynamic().my == true) 'M' else 'E'
                else -> '?'
            }
        }
        for (y in 0 until 100) println("map ${y.toString().padStart(2, '0')} ${rows[y].concatToString()}")
    }

    private fun logChanges(t: Int, all: Array<GameObject>, mine: List<Creep>) {
        val enemies = all.filter { it.asDynamic().my == false && it.exists }
        val now = enemies.associateBy { idOf(it) }
        val appeared = now.keys - enemyKnown
        val gone = enemyKnown - now.keys
        if (t > 1 && (appeared.isNotEmpty() || gone.isNotEmpty())) {
            println("enemy objects t=$t +[${appeared.joinToString(" ") { describe(now.getValue(it)) }}] -${gone.size} now=${now.size}")
        }
        enemyKnown = now.keys
        val myNow = mine.associate { idOf(it) to bodyOf(it) }
        val died = myKnown.keys - myNow.keys
        if (died.isNotEmpty()) println("my creeps lost t=$t: ${died.joinToString(" ") { myKnown.getValue(it) }}")
        myKnown = myNow
    }

    private fun logStatus(
        t: Int, mine: List<Creep>, theirs: List<Creep>, mySpawns: List<StructureSpawn>, sources: Array<Source>, all: Array<GameObject>,
    ) {
        val carried = mine.sumOf { it.store[RESOURCE_ENERGY] ?: 0 }
        println("t=$t mine=${mine.size} [${mine.joinToString(" ") { "${bodyOf(it)}@${it.x},${it.y}" }}] carried=$carried wave=${wave.size} " +
            "spawns=${mySpawns.joinToString(" ") { describe(it) }} sites=${all.filter { it is ConstructionSite && it.asDynamic().my == true }.joinToString(" ") { describe(it) }}")
        println("sources t=$t " + sources.joinToString(" ") { "(${it.x},${it.y})${it.energy}" } + " bases=" +
            bases.joinToString(" ") { b -> "src${b.sourceId}:work=${homeWork(b, mine)}" })
        val enemies = all.filter { it.asDynamic().my == false && it !is Creep }
        println("enemy t=$t creeps=${theirs.size} [${theirs.joinToString(" ") { "${bodyOf(it)}@${it.x},${it.y}" }}] " +
            "objects=${enemies.joinToString(" ") { describe(it) }}")
    }
}
