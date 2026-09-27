package season4.spawnandswampadvanced

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
import screeps.api.MAX_CONSTRUCTION_SITES
import screeps.api.MOVE
import screeps.api.OBSTACLE_OBJECT_TYPES
import screeps.api.Position
import screeps.api.RAMPART_HITS
import screeps.api.RANGED_ATTACK
import screeps.api.RESOURCE_DECAY
import screeps.api.RESOURCE_ENERGY
import screeps.api.Resource
import screeps.api.SOURCE_ENERGY_REGEN
import screeps.api.SPAWN_ENERGY_CAPACITY
import screeps.api.SPAWN_HITS
import screeps.api.SearchGoal
import screeps.api.Source
import screeps.api.TERRAIN_SWAMP
import screeps.api.TERRAIN_WALL
import screeps.api.TOWER_FALLOFF_RANGE
import screeps.api.TOWER_POWER_ATTACK
import screeps.api.WORK
import screeps.api.arenaInfo
import screeps.api.createConstructionSite
import screeps.api.findClosestByPath
import screeps.api.get
import screeps.api.getObjects
import screeps.api.getObjectsByPrototype
import screeps.api.getRange
import screeps.api.getTerrainAt
import screeps.api.getTicks
import screeps.api.searchPath
import screeps.api.structures.Structure
import screeps.api.structures.StructureContainer
import screeps.api.structures.StructureSpawn
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
 * Season 4 «Spawn and Swamp» ADVANCED — v1, разведчик.
 *
 * Правила известны только по описанию арены: карта 100x100 в основном из болота, на старте у каждого ОДИН рабочий
 * в середине комнаты и НИ ОДНОГО спавна; энергия в контейнерах и источниках; победа — уничтожить все объекты
 * соперника; 10000 тиков, дальше ничья. Первая задача этой версии — снять с живого матча то, чего описание не
 * говорит: тело рабочего, раскладку источников и контейнеров, цену спавна и остальных построек
 * (`CONSTRUCTION_COST` рантайма — d.ts клиента устаревает), смысл `SOURCE_ENERGY_REGEN`, что делает соперник и
 * какие его объекты остаются, когда матч не кончается.
 *
 * Поведение минимальное, но играющее: рабочий ставит площадку спавна у ближайшего источника (клетка в двух шагах от
 * источника с наибольшим числом клеток, смежных с обоими: там добытчик копает и сдаёт, не двигаясь), наполняется
 * там, где рейс дешевле (источник или контейнер), и строит; затем спавн рождает добытчиков, а после них стрелков,
 * которые идут на ближайший вражеский объект. Числа экономики здесь — заглушки разведчика
 * ([PROBE_WORKERS], тела), их заменит расчёт из замеренных правил.
 */
object SpawnAndSwampAdvanced {

    /** Печатается первой строкой матча: по ней лог связывается с коммитом, а `--arena` инструментов отличает режим
     *  от базового (фильтр по подстроке — поэтому в имени обязательно `spawn-and-swamp-advanced`). */
    private const val BOT_VERSION = "v1"

    private const val LOG_EVERY = 50

    /** Заглушка разведчика: сколько добытчиков держать до первого стрелка. */
    private const val PROBE_WORKERS = 4

    /** Заглушка разведчика: тела. Рабочий W2C1M3 идёт по равнине клетку в тик и гружёный. */
    private val WORKER_BODY: Array<BodyPartType> = arrayOf(WORK, WORK, CARRY, MOVE, MOVE, MOVE)
    private val FIGHTER_BODY: Array<BodyPartType> =
        arrayOf(MOVE, RANGED_ATTACK, MOVE, RANGED_ATTACK, MOVE, RANGED_ATTACK, MOVE, RANGED_ATTACK, MOVE, RANGED_ATTACK)

    private const val RANGED_RANGE = 3
    /** Враг-крип ближе этого — цель стрелка раньше построек. */
    private const val CREEP_AGGRO = 12

    // ---------- состояние между тиками ----------
    /** Рабочий наполняется (true) или тратит (false). Ключ — id строкой: id крипа в рантайме число. */
    private val filling = HashMap<String, Boolean>()
    /** Выбранный рабочим источник энергии: держится, пока не опустел. */
    private val energyTarget = HashMap<String, String>()
    private var spawnSiteTries = 0
    private var mySpawnSeenAt = -1
    private var enemyKnown: Set<String> = emptySet()
    private var myKnown: Map<String, String> = emptyMap()

    private fun idOf(o: GameObject): String = o.id.asDynamic().toString().unsafeCast<String>()

    private fun cell(x: Int, y: Int): Position {
        val o: dynamic = js("({})")
        o.x = x
        o.y = y
        return o.unsafeCast<Position>()
    }

    private fun protoName(o: Any): String = o.asDynamic().constructor.name.unsafeCast<String?>() ?: "?"

    private fun energyOf(o: dynamic): Int = (o.store?.getUsedCapacity("energy") as Int?) ?: 0

    /** Тело строкой `W2C1M3`: типы частей в рантайме — строки. */
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

    /** Площадка спавна: по прототипу её будущей постройки, а если рантайм его не отдаёт — по цене спавна. */
    private fun isSpawnSite(site: ConstructionSite): Boolean {
        val s = site.structure
        if (s != null && protoName(s) == "StructureSpawn") return true
        val cost = CONSTRUCTION_COST.asDynamic()["StructureSpawn"].unsafeCast<Int?>()
        return cost != null && site.progressTotal == cost
    }

    private fun liveParts(c: Creep, type: BodyPartType): Int = c.body.count { it.type == type && it.hits > 0 }

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

    fun tick() {
        val t = getTicks()
        val all = getObjects()
        val creeps = getObjectsByPrototype(Creep::class)
        val mine = creeps.filter { it.my }
        val theirs = creeps.filter { !it.my }
        val spawns = getObjectsByPrototype(StructureSpawn::class)
        val mySpawn = spawns.firstOrNull { it.my == true }
        val sources = getObjectsByPrototype(Source::class)
        val sites = getObjectsByPrototype(ConstructionSite::class)
        val mySites = sites.filter { it.my == true }

        if (t == 1) dumpWorld(all)

        if (mySpawn != null && mySpawnSeenAt < 0) {
            mySpawnSeenAt = t
            println("my spawn up t=$t at (${mySpawn.x},${mySpawn.y}) e=${energyOf(mySpawn)} hits=${mySpawn.hits}")
        }

        // площадка спавна ставится сразу: она ничего не стоит, пока её не строят, а рабочий знает, куда нести
        if (mySpawn == null && mySites.none { isSpawnSite(it) } && spawnSiteTries < 20) {
            val w = mine.firstOrNull { liveParts(it, WORK) > 0 }
            if (w != null) placeSpawnSite(w, sources, all)
        }

        val workers = mine.filter { !it.spawning && liveParts(it, WORK) > 0 }
        val fighters = mine.filter { !it.spawning && liveParts(it, RANGED_ATTACK) > 0 }
        for (w in workers) runWorker(w, mySpawn, mySites, sources)
        for (f in fighters) runFighter(f, theirs, all, mySpawn)

        if (mySpawn != null && mySpawn.spawning == null) runSpawn(mySpawn, workers.size + mine.count { it.spawning && it.body.any { p -> p.type == WORK } }, fighters.size)

        logChanges(t, all, mine)
        if (t % LOG_EVERY == 0) logStatus(t, mine, theirs, mySpawn, spawns, sources, sites, all)
    }

    // ---------- дамп мира: то, ради чего эта версия существует ----------

    private fun dumpWorld(all: Array<GameObject>) {
        println("hello season4 spawn-and-swamp-advanced $BOT_VERSION")
        println("tuning: probe workers=$PROBE_WORKERS worker=${bodyText(WORKER_BODY)} fighter=${bodyText(FIGHTER_BODY)}")
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
            if (name == "StructureWall") {
                println("walls ${list.size}: " + list.joinToString(" ") { "${it.x},${it.y}" })
                continue
            }
            for (o in list) println("obj: ${describe(o)}")
        }
        // рельеф: счёт и карта строками (# стена, ~ болото, . равнина; поверх — объекты)
        var w = 0; var s = 0; var p = 0; var maxX = 0; var maxY = 0
        val rows = Array(100) { CharArray(100) }
        for (y in 0 until 100) for (x in 0 until 100) {
            val ter = getTerrainAt(cell(x, y))
            rows[y][x] = when (ter) { TERRAIN_WALL -> { w++; '#' } TERRAIN_SWAMP -> { s++; '~' } else -> { p++; '.' } }
            if (ter != TERRAIN_WALL) { if (x > maxX) maxX = x; if (y > maxY) maxY = y }
        }
        for (o in all) {
            if (o.x !in 0..99 || o.y !in 0..99) continue
            val d = o.asDynamic()
            rows[o.y][o.x] = when (protoName(o)) {
                "Source" -> 'S'
                "StructureContainer" -> 'C'
                "StructureWall" -> 'X'
                "StructureSpawn" -> if (d.my == true) 'P' else 'p'
                "Creep" -> if (d.my == true) 'M' else 'E'
                "Resource" -> 'r'
                else -> '?'
            }
        }
        println("terrain: wall=$w swamp=$s plain=$p maxX=$maxX maxY=$maxY")
        for (y in 0 until 100) println("map ${y.toString().padStart(2, '0')} ${rows[y].concatToString()}")
    }

    // ---------- спавн из рабочего ----------

    private fun blockedCells(all: Array<GameObject>): Set<Int> {
        val out = HashSet<Int>()
        for (o in all) if (o is Structure || o is ConstructionSite) out.add(o.x * 100 + o.y)
        return out
    }

    private fun walkable(x: Int, y: Int, blocked: Set<Int>): Boolean =
        x in 1..98 && y in 1..98 && getTerrainAt(cell(x, y)) != TERRAIN_WALL && (x * 100 + y) !in blocked

    /** Клетка спавна: в двух шагах от ближайшего по пути источника, где больше всего клеток, смежных и с источником,
     *  и со спавном (на них добытчик копает и сдаёт без шага); равнина лучше болота, ближе к рабочему лучше. */
    private fun placeSpawnSite(w: Creep, sources: Array<Source>, all: Array<GameObject>) {
        spawnSiteTries++
        if (sources.isEmpty()) { println("spawn site: no sources"); return }
        val src = findClosestByPath(w, sources)
        val blocked = blockedCells(all)
        var best: Position? = null
        var bestScore = Int.MIN_VALUE
        for (dx in -2..2) for (dy in -2..2) {
            if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 2) continue
            val x = src.x + dx; val y = src.y + dy
            if (!walkable(x, y, blocked)) continue
            var slots = 0
            for (ax in x - 1..x + 1) for (ay in y - 1..y + 1) {
                if (ax == x && ay == y) continue
                if (maxOf(kotlin.math.abs(ax - src.x), kotlin.math.abs(ay - src.y)) != 1) continue
                if (walkable(ax, ay, blocked)) slots++
            }
            val plain = if (getTerrainAt(cell(x, y)) == TERRAIN_SWAMP) 0 else 1
            val score = slots * 1000 + plain * 100 - getRange(w, cell(x, y))
            if (score > bestScore) { bestScore = score; best = cell(x, y) }
        }
        if (best == null) { println("spawn site: no cell near source (${src.x},${src.y})"); return }
        val r = createConstructionSite(best.x, best.y, StructureSpawn::class.js)
        println("spawn site: at (${best.x},${best.y}) src=(${src.x},${src.y}) slots=${bestScore / 1000} " +
            "err=${r.error} site=${r.`object`?.let { describe(it) }}")
    }

    // ---------- рабочий ----------

    private fun runWorker(w: Creep, mySpawn: StructureSpawn?, mySites: List<ConstructionSite>, sources: Array<Source>) {
        val id = idOf(w)
        val e = w.store[RESOURCE_ENERGY] ?: 0
        val free = w.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0
        var fill = filling[id] ?: (e == 0)
        if (e == 0) fill = true
        if (free == 0) fill = false
        filling[id] = fill

        if (fill) {
            val target = pickEnergy(w, sources)
            if (target == null) {
                if (e > 0) filling[id] = false
                return
            }
            if (getRange(w, target) > 1) { w.moveTo(target); return }
            when (target) {
                is Source -> w.harvest(target)
                is Resource -> w.pickup(target)
                is Structure -> w.withdraw(target, RESOURCE_ENERGY)
                else -> {}
            }
            return
        }

        if (mySpawn == null) {
            val site = mySites.firstOrNull { isSpawnSite(it) } ?: return
            if (getRange(w, site) > 3) w.moveTo(site) else w.build(site)
            return
        }
        if ((mySpawn.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0) {
            if (getRange(w, mySpawn) > 1) w.moveTo(mySpawn) else w.transfer(mySpawn, RESOURCE_ENERGY)
            return
        }
        val site = mySites.minByOrNull { getRange(w, it) }
        if (site != null) {
            if (getRange(w, site) > 3) w.moveTo(site) else w.build(site)
            return
        }
        if (getRange(w, mySpawn) > 2) w.moveTo(mySpawn)
    }

    /** Откуда наполняться: минимум (путь до цели + время наполнения там), в единицах стоимости пути (равнина 2,
     *  болото 10 — у рабочего 1:1 это и есть тики ×2). Цель держится, пока не опустела. */
    private fun pickEnergy(w: Creep, sources: Array<Source>): GameObject? {
        val id = idOf(w)
        val held = energyTarget[id]
        if (held != null) {
            val o = getObjects().firstOrNull { idOf(it) == held }
            if (o != null && o.exists && energyAvailable(o) > 0) return o
            energyTarget.remove(id)
        }
        val work = liveParts(w, WORK).coerceAtLeast(1)
        val free = w.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0
        val candidates = ArrayList<GameObject>()
        candidates.addAll(sources.filter { it.energy > 0 })
        candidates.addAll(getObjectsByPrototype(StructureContainer::class).filter { energyOf(it) > 0 })
        candidates.addAll(getObjectsByPrototype(Resource::class).filter { it.amount >= 20 })
        val near = candidates.sortedBy { getRange(w, it) }.take(8)
        var best: GameObject? = null
        var bestCost = Double.MAX_VALUE
        for (c in near) {
            val path = searchPath(w, SearchGoal(pos = c, range = 1))
            if (path.incomplete) continue
            val fillCost = if (c is Source) 2.0 * free / (HARVEST_POWER * work) else 0.0
            val crowd = energyTarget.values.count { it == idOf(c) } * 10.0
            val cost = path.cost + fillCost + crowd
            if (cost < bestCost) { bestCost = cost; best = c }
        }
        if (best != null) energyTarget[id] = idOf(best)
        return best
    }

    private fun energyAvailable(o: GameObject): Int = when (o) {
        is Source -> o.energy
        is Resource -> o.amount
        else -> energyOf(o)
    }

    // ---------- спавн ----------

    private fun runSpawn(spawn: StructureSpawn, workers: Int, fighters: Int) {
        val energy = energyOf(spawn)
        val body = if (workers < PROBE_WORKERS) WORKER_BODY else FIGHTER_BODY
        val cost = body.sumOf { BODYPART_COST[it] ?: 0 }
        if (energy < cost) return
        val r = spawn.spawnCreep(body)
        println("spawn t=${getTicks()} ${bodyText(body)} cost=$cost " +
            "energy=$energy workers=$workers fighters=$fighters err=${r.error}")
    }

    // ---------- стрелок ----------

    private fun enemyObjects(all: Array<GameObject>): List<GameObject> = all.filter {
        val d = it.asDynamic()
        d.my == false && it.exists
    }

    private fun runFighter(f: Creep, theirs: List<Creep>, all: Array<GameObject>, mySpawn: StructureSpawn?) {
        val nearCreep = theirs.filter { getRange(f, it) <= CREEP_AGGRO }.minByOrNull { getRange(f, it) }
        val enemies = enemyObjects(all)
        val target: GameObject? = nearCreep
            ?: enemies.filter { it is StructureSpawn }.minByOrNull { getRange(f, it) }
            ?: enemies.filter { it is Structure }.minByOrNull { getRange(f, it) }
            ?: theirs.minByOrNull { getRange(f, it) }
            ?: enemies.filter { it is ConstructionSite }.minByOrNull { getRange(f, it) }
        if (target == null) {
            if (mySpawn != null && getRange(f, mySpawn) > 3) f.moveTo(mySpawn)
            return
        }
        val range = getRange(f, target)
        if (target is ConstructionSite) {
            // во внешнем мире чужую площадку давят, наступив на неё; здесь это и проверяем
            if (range > 0) f.moveTo(target)
            return
        }
        if (range <= RANGED_RANGE) f.rangedAttack(target)
        if (range > 2) f.moveTo(target)
    }

    // ---------- журнал ----------

    private fun logChanges(t: Int, all: Array<GameObject>, mine: List<Creep>) {
        val enemies = enemyObjects(all)
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
        t: Int, mine: List<Creep>, theirs: List<Creep>, mySpawn: StructureSpawn?, spawns: Array<StructureSpawn>,
        sources: Array<Source>, sites: Array<ConstructionSite>, all: Array<GameObject>,
    ) {
        val carried = mine.sumOf { it.store[RESOURCE_ENERGY] ?: 0 }
        println("t=$t mine=${mine.size} [${mine.joinToString(" ") { "${bodyOf(it)}@${it.x},${it.y}" }}] carried=$carried " +
            "spawn=${mySpawn?.let { describe(it) } ?: "none"} sites=${sites.filter { it.my == true }.joinToString(" ") { describe(it) }}")
        println("sources t=$t " + sources.joinToString(" ") { "(${it.x},${it.y})${it.energy}/${it.energyCapacity}" } +
            " containers=" + getObjectsByPrototype(StructureContainer::class).filter { energyOf(it) > 0 }.joinToString(" ") { "(${it.x},${it.y})${energyOf(it)}" })
        val enemies = enemyObjects(all).filter { it !is Creep }
        println("enemy t=$t creeps=${theirs.size} [${theirs.joinToString(" ") { "${bodyOf(it)}@${it.x},${it.y}" }}] " +
            "objects=${enemies.joinToString(" ") { describe(it) }} spawnsAll=${spawns.size}")
    }
}
