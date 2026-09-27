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
    private const val BOT_VERSION = "v2"

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
    /** Волна держится: передний ждёт, пока остальные не подойдут на столько клеток. */
    private const val COHESION = 3
    /** Крип врага, до которого столько клеток от волны, — местная угроза для решения об отходе. */
    private const val LOCAL_RANGE = 10

    // ---------- состояние между тиками ----------
    private data class Pos(val x: Int, val y: Int)

    /** База: спавн (или его будущая клетка), её источник и клетки, смежные с обоими. */
    private class Base(val sourceId: String, val spawnCell: Pos, val slots: List<Pos>) {
        var spawnId: String? = null
    }

    private val bases = ArrayList<Base>()
    /** Где стоит добытчик: id крипа → клетка базы. */
    private val slotOf = HashMap<String, Pos>()
    /** Строитель второго спавна и его цель. */
    private var builderId: String? = null
    private var expansion: Base? = null
    private var expansionPlaced = false
    /** Волна: id бойцов, ушедших в атаку. Пусто — армия дома. */
    private val wave = HashSet<String>()
    private var lastPosture = ""
    private var enemyStart: Pos? = null
    private var enemyKnown: Set<String> = emptySet()
    private var myKnown: Map<String, String> = emptyMap()
    private var spawnUpAt = -1

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
            if (w != null) openingPlan(w, sources, all)
        }

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

        val homeSpawns = bases.mapNotNull { b -> b.spawnId?.let { byId[it] } }
        val workersAll = mine.filter { liveParts(it, WORK) > 0 }
        val threats = homeThreats(enemyCombat, homeSpawns, workersAll)

        runWorkers(t, mine, byId, sites.filter { it.my == true }, sources, all, enemyCombat)
        runArmy(t, mine, theirs, enemyCombat, enemyTowers, all, byId, threats)
        for (b in bases) {
            val sp = b.spawnId?.let { byId[it] } as? StructureSpawn ?: continue
            if (sp.spawning == null) runSpawn(t, b, sp, mine, threats)
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
        if (builderId != null && workers.none { idOf(it) == builderId }) {
            println("builder lost t=$t")
            builderId = null
            expansion = null
            expansionPlaced = false
        }
        for (w in workers) {
            val id = idOf(w)
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

    /** Добытчик на клетке у источника и спавна: копает каждый тик и сдаёт, когда следующая копка переполнила бы
     *  его; спавн полон — достраивает площадки в досягаемости. */
    private var deliveryProbe = 0

    private fun harvestAndDeliver(t: Int, w: Creep, src: Source, spawn: StructureSpawn, mySites: List<ConstructionSite>) {
        val e = w.store[RESOURCE_ENERGY] ?: 0
        val cap = w.store.getCapacity(RESOURCE_ENERGY) ?: 0
        val h = HARVEST_POWER * liveParts(w, WORK)
        val spawnFree = spawn.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0
        val hv = if (src.energy > 0 && getRange(w, src) <= 1 && (e + h <= cap || spawnFree > 0)) w.harvest(src) else null
        var tr: Any? = null
        if (e > 0 && spawnFree > 0 && getRange(w, spawn) <= 1 && e + h > cap) tr = w.transfer(spawn, RESOURCE_ENERGY)
        if (spawnFree <= 0 && e > 0) {
            val site = mySites.filter { getRange(w, it) <= 3 }.minByOrNull { getRange(w, it) }
            if (site != null && hv == null) w.build(site)
        }
        // замер движка: можно ли копать и сдавать в один тик (первые тики после спавна)
        if (tr != null && deliveryProbe < 6) {
            deliveryProbe++
            println("probe t=$t harvest+transfer: hv=$hv tr=$tr store=$e spawnE=${energyOf(spawn)}")
        }
    }

    // ---------- второй спавн ----------

    private fun expansionTarget(from: Position, sources: Array<Source>, all: Array<GameObject>): Base? {
        val enemy = enemyStart ?: return null
        val taken = bases.map { it.sourceId }.toSet()
        val blocked = blockedCells(all)
        val candidates = sources.filter { idOf(it) !in taken }.filter { s ->
            // «наш» источник: к нему ближе мы, чем стартовая клетка соперника
            getRange(from, s) < getRange(cell(enemy), s)
        }.sortedBy { getRange(from, it) }
        for (s in candidates) planBase(s, from, blocked)?.let { return it }
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
        mine.filter { c -> slotOf[idOf(c)]?.let { b.slots.contains(it) } == true || (c.spawning && getRange(c, cell(b.spawnCell)) <= 1 && liveParts(c, WORK) > 0) }
            .sumOf { liveParts(it, WORK) }

    /** Угрозы дому: боевые враги у наших спавнов или у наших рабочих. */
    private fun homeThreats(enemyCombat: List<Creep>, homeSpawns: List<GameObject>, workers: List<Creep>): List<Creep> =
        enemyCombat.filter { e ->
            homeSpawns.any { getRange(e, it) <= HOME_THREAT_RANGE } || workers.any { getRange(e, it) <= WORKER_THREAT_RANGE }
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
            body = fighterBody(energy)
            why = "threat"
            if (body.size < 4) return
        } else if (haveWork < needWork && freeSlots > 0) {
            body = workerBody(needWork - haveWork, energy)
            why = "work $haveWork/$needWork"
        } else if (bases.size == 1 && expansion == null && builderId == null && fighters.isNotEmpty() && threat.isEmpty() && b === bases.first()) {
            val target = expansionTarget(spawn, getObjectsByPrototype(Source::class), getObjects()) ?: run {
                return spawnFighter(t, spawn, energy, why = "army")
            }
            body = builderBody()
            if (energy < costOf(body)) return
            val r = spawn.spawnCreep(body)
            val c = r.`object`
            println("spawn t=$t ${bodyText(body)} cost=${costOf(body)} energy=$energy why=expansion to (${target.spawnCell.x},${target.spawnCell.y}) err=${r.error}")
            if (c != null) { builderId = idOf(c); expansion = target; expansionPlaced = false }
            return
        } else {
            return spawnFighter(t, spawn, energy, why = "army")
        }
        if (energy < costOf(body)) return
        val r = spawn.spawnCreep(body)
        println("spawn t=$t ${bodyText(body)} cost=${costOf(body)} energy=$energy why=$why err=${r.error}")
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
        if (fed.isEmpty()) return 0.0
        val dps = fed.size * towerShot(RANGED_RANGE + 1) / TOWER_COOLDOWN
        val hits = fed.sumOf { it.hits ?: 0 }.toDouble()
        return kotlin.math.sqrt(dps * hits)
    }

    // ---------- армия ----------

    private fun runArmy(
        t: Int, mine: List<Creep>, theirs: List<Creep>, enemyCombat: List<Creep>, enemyTowers: List<StructureTower>,
        all: Array<GameObject>, byId: Map<String, GameObject>, threats: List<Creep>,
    ) {
        val fighters = mine.filter { !it.spawning && isCombat(it) }
        wave.retainAll(fighters.map { idOf(it) }.toSet())
        val homeSpawns = bases.mapNotNull { b -> b.spawnId?.let { byId[it] } }
        // сбор — у спавна, ближайшего к сопернику: туда сходятся бойцы всех баз
        val enemy = enemyStart
        val home: Position = homeSpawns.minByOrNull { if (enemy == null) 0 else getRange(it, cell(enemy)) }
            ?: bases.firstOrNull()?.let { cell(it.spawnCell) } ?: return
        val enemyObjects = all.filter { it.asDynamic().my == false && it !is Creep && it !is ConstructionSite }
        val enemyPower = power(enemyCombat) + towerPower(enemyTowers)
        val homeGroup = fighters.filter { idOf(it) !in wave }
        val lastCall = t > arenaInfo.ticksLimit - 600

        // выход волны: сила дома больше всей армии и башен соперника
        if (wave.isEmpty() && threats.isEmpty() && homeGroup.isNotEmpty() &&
            (power(homeGroup) >= enemyPower * PUSH_RATIO || lastCall) && (enemyObjects.isNotEmpty() || theirs.isNotEmpty())) {
            for (f in homeGroup) wave.add(idOf(f))
            println("push t=$t wave=${wave.size} power=${power(homeGroup).toInt()} vs enemy=${enemyPower.toInt()} (army ${power(enemyCombat).toInt()} towers ${towerPower(enemyTowers).toInt()})${if (lastCall) " lastCall" else ""}")
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
            val laggard = waveNow.any { getRange(it, front) > COHESION }
            for (f in waveNow) {
                val acted = shoot(f, theirs, enemyObjects)
                if (goal == null) continue
                if (fleeMelee(f, enemyCombat)) continue
                val range = getRange(f, goal)
                if (f === front && laggard && !acted) continue
                if (f !== front && getRange(f, front) > COHESION) { f.moveTo(front); continue }
                val keep = if (goal is Creep) RANGED_RANGE else RANGED_RANGE - 1
                if (range > keep) f.moveTo(goal)
            }
        }
        // дом: защита от угроз всей кучей, иначе сбор у спавна
        for (f in homeGroup) {
            if (idOf(f) in wave) continue
            shoot(f, theirs, enemyObjects)
            if (fleeMelee(f, enemyCombat)) continue
            val target = threats.minByOrNull { getRange(f, it) }
            if (target != null) {
                if (getRange(f, target) > RANGED_RANGE) f.moveTo(target)
            } else if (getRange(f, home) > 3) f.moveTo(home)
        }
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
