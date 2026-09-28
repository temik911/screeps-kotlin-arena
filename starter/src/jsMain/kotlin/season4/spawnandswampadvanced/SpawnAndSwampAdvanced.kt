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
import screeps.api.MAX_CREEP_SIZE
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
import screeps.api.CostMatrix
import screeps.api.FindPathOptions
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
import screeps.api.getCpuTime
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
    private const val BOT_VERSION = "v54"

    private const val LOG_EVERY = 50

    // ---------- армия ----------
    private const val RANGED_RANGE = 3
    /** Под угрозой спавн рожает бойца, пока сила дома меньше силы угрозы в столько раз (Ланчестер — только для
     *  заказа, решения боя считает прогон). */
    private const val PUSH_RATIO = 1.3
    /** Волна выходит, если по прогону боя побеждает и сохраняет не меньше этой доли своих хитов: запас на то, чего
     *  прогон не видит — подход под огнём, строй, мили у спавна. Ланчестер с лечением «как уроном» пускал волну 1,3×
     *  против пар стрелок+лекарь 76561198870429455 и stachu3478 — обе волны v11 легли и отошли. v12 с долей 0,3
     *  против stachu3478 вышел трижды (прогон обещал 57 %, 39 % и 30 %) и трижды отошёл с потерями: прогон считает
     *  бой всей волной разом, а волна приходит растянутой — половина, пока калибровки по замерам нет. */
    private const val PUSH_KEEP = 0.5
    /** Нарушитель — его строитель или площадка спавна/башни у нашего источника в стольких клетках (спавн базы ставится в
     *  двух шагах от источника, строитель — рядом с ним). */
    private const val INTRUDER_SOURCE_RANGE = 4

    /** У его спавна «стоит» то, что в стольких клетках от него: защитники, которые успеют к удару по этому спавну. */
    private const val STRIKE_GUARD_RANGE = 15

    /** Спавны в стольких клетках друг от друга делят запас на одно тело (замер basic: 3 — да, 25 — нет). */
    private const val POOL_RANGE = 3

    /** Прогон дольше этого не смотрим: бой, который не решается за столько тиков, для решения — ничья. */
    private const val SIM_LIMIT = 300
    /** Угроза дому: боевой враг в стольких клетках от нашего спавна или от нашего рабочего (дальность стрелка плюс
     *  несколько шагов подхода). */
    private const val HOME_THREAT_RANGE = 10
    private const val WORKER_THREAT_RANGE = 6
    /** Защита снимается, только когда угроз нет и на столько клеток дальше: без запаса стрелки соперника, кружащие
     *  у границы (けろびー, v4), переключали позу каждые два-три тика, и наши бегали за ними туда-обратно. */
    private const val THREAT_RELEASE = 5
    /** Урон его бойца, делённый на это, — надбавка к цене клетки в его досягаемости: `M5R5` (50) даёт +25 на клетку, и
     *  проход сквозь зону одного стрелка (~7 клеток) обходится дороже обхода в пару десятков клеток. */
    private const val DANGER_SCALE = 2
    /** Башня бьёт на 20 клеток с затуханием; опасна до десяти, где выстрел ещё больше половины. */
    private const val TOWER_DANGER_RANGE = 10

    /** Конвой держится в стольких клетках от сопровождаемого: вокруг него, но не на его клетках работ. */
    private const val ESCORT_RANGE = 3

    /** Место работ (пролом сейфа, клетка второго спавна) безопасно, если его боевых крипов нет ближе стольких клеток:
     *  дальность стрелка плюс путь, который он проходит, пока пробойщик ломает стену. */
    private const val WORKSITE_SAFE = 12

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
        /** Второй спавн у того же источника: доход он не поднимает (источник даёт свои 10 в тик), но запас спавнов в
         *  досягаемости `spawnCreep` общий — тело до 2000 вместо 1000 (идея оператора 27.09.2026). */
        var twinCell: Pos? = null
        var twinId: String? = null
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
    /** Основатель: стартовый рабочий, который уйдёт строить вторую базу, как только первую насытит полный добытчик на
     *  замену. Он уже оплачен и уже умеет строить спавн, а замена стоит 600 против 850 строителя — и выходит на ~500
     *  тиков раньше: けろびー#15 так ставит второй спавн к 683–733-му, v37 ставил к 1117–2129-му и сыграл с ним три ничьи
     *  из четырёх при его 22 энергии в тик на армию против наших 10. */
    private var founderId: String? = null
    private var founderPlan: Base? = null
    private var founderPlanAt = -1000
    /** Волна: id бойцов, ушедших в атаку. Пусто — армия дома. */
    private val wave = HashSet<String>()
    private var lastPosture = ""
    private var enemyStart: Pos? = null
    private var enemyKnown: Set<String> = emptySet()
    private var myKnown: Map<String, String> = emptyMap()
    private var spawnUpAt = -1
    private var defending = false
    private var attackedOnce = false
    /** Дом держит нынешние угрозы: их нет, или прогон дома (бойцы и кормленые башни) против них выигран. */
    private var homeHolds = true
    /** Угроза бьёт: кто-то из угроз в досягаемости нашего спавна или рабочего (как `striking` у отзыва волны). */
    private var threatStrikes = false

    /** Прибор калибровки прогона: что он обещал на выходе волны (удара) и что вышло к её концу. Решения прогона
     *  держатся на запасе `PUSH_KEEP`, который пока назван, а не измерен; этот прибор его меряет. */
    private class Calib(val kind: String, val t0: Int, val ours: Set<String>, val theirs: Set<String>,
                        val ours0: Int, val theirs0: Int, val predKeep: Double, val predWin: Boolean)
    private var calib: Calib? = null
    /** Рождения его боевых крипов: тик, урон+лечение, хиты — из них его производство к нашему подходу. */
    private val enemyBirths = ArrayList<Pair<Int, List<String>>>()
    private val enemyCombatSeen = HashSet<String>()
    private var enemySpawnSeenAt = -1
    /** Роли крипов вне экономики баз и армии: пробойщик, строитель сейфа. Заказанный крип узнаётся по телу. */
    private val roleOf = HashMap<String, String>()
    private val pendingRoles = ArrayList<Pair<String, String>>()
    /** Оба кармана с контейнерами: свой первым (по пути от стартов), второй — следом, когда свой работает или его
     *  место небезопасно. Оператор 27.09.2026: второй «точно такой же закуток был прямо около нашего дополнительного
     *  спавна и полностью свободен», а бот видел только свой. */
    private val vaults = ArrayList<Vault>()
    /** Путь от первой базы к спавну сейфа — сколько дому идти на помощь, если печать сейфа ломают. */
    private val vaultReturn = HashMap<Int, Int>()
    /** Хиты клеток печати на прошлом тике: падение за тик — урон, который по ней идёт. */
    private val sealPrev = HashMap<Pos, Int>()
    private var openAt = -1
    private var openCache: Set<Int> = emptySet()
    /** Кто заказывает для сейфа: номер сейфа → (число баз на момент расчёта, id ближайшего по пути спавна базы). */
    private val vaultFrom = HashMap<Int, Pair<Int, String>>()
    private var holdTicks = 0
    /** Цель удара: волна идёт к ЭТОМУ спавну, отвечая по дороге только тем, кто рядом, — прогон выбрал его среди всех. */
    private var strikeTargetId: String? = null
    /** Его спавн, который бойцы в досягаемости снесут раньше, чем их перебьют рядом стоящие (прогон с огнём по нему). */
    private var finish: GameObject? = null
    /** Урон его башен по пути к его спавну: id спавна → (тик замера, урон). Путь меняется медленно — раз в 25 тиков. */
    private val routeDamage = HashMap<String, Pair<Int, Int>>()
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

    // ---------- опасность ----------

    /** Карта опасности тика: клетки, куда достаёт (или за шаг достанет) его боец, дорожают на его урон, делённый на
     *  `DANGER_SCALE`; клетки под его кормлеными башнями — на их выстрел в тик. Ею ходят все, кто не в бою: рабочие,
     *  строители, пробойщик, заправщик, пополнение к сбору и к волне. Оператор 27.09.2026: «наши крипы при ходьбе не
     *  учитывают опасность клетки и пытаются пройти через кучу вражеских боевых крипов и просто умирают по одиночке». */
    private var danger: CostMatrix? = null

    private fun buildDanger(enemyCombat: List<Creep>, enemyTowers: List<StructureTower>) {
        val add = HashMap<Int, Int>()
        for (e in enemyCombat) {
            val dps = dpsOf(e)
            if (dps <= 0) continue
            val r = (if (liveParts(e, RANGED_ATTACK) > 0) RANGED_RANGE else 1) + 1
            for (dx in -r..r) for (dy in -r..r) {
                val x = e.x + dx; val y = e.y + dy
                if (x !in 0..99 || y !in 0..99) continue
                add[x * 100 + y] = (add[x * 100 + y] ?: 0) + dps
            }
        }
        for (tw in enemyTowers) {
            if (energyOf(tw) <= 0) continue
            val r = TOWER_DANGER_RANGE
            for (dx in -r..r) for (dy in -r..r) {
                val x = tw.x + dx; val y = tw.y + dy
                if (x !in 0..99 || y !in 0..99) continue
                add[x * 100 + y] = (add[x * 100 + y] ?: 0) + (towerShot(maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))) / TOWER_COOLDOWN).toInt()
            }
        }
        if (add.isEmpty()) { danger = null; return }
        val cost = HashMap<Int, Int>()
        for ((k, d) in add) {
            val x = k / 100; val y = k % 100
            val ter = getTerrainAt(cell(x, y))
            if (ter == TERRAIN_WALL) continue
            val base = if (ter == TERRAIN_SWAMP) 10 else 2
            cost[k] = minOf(254, base + d / DANGER_SCALE)
        }
        // своя матрица отключает у поиска пути обход препятствий: он не знает ни крипов, ни построек (basic записал то
        // же). v27 без этого вёл строителя сейфа сквозь StructureWall — тот простоял у пролома до смерти, другой не
        // сдвинулся за 1300 тиков. Препятствия и крипы — непроходимы, свой рампарт — безопасная клетка
        for (o in getObjects()) {
            if (o.x !in 0..99 || o.y !in 0..99) continue
            val k = o.x * 100 + o.y
            when {
                o is Creep -> cost[k] = 255
                o is StructureRampart && o.asDynamic().my == true -> if ((cost[k] ?: 0) < 255) cost[k] = 1
                o is Structure && o !is StructureContainer && o !is StructureRampart -> cost[k] = 255
                o is StructureRampart -> cost[k] = 255
            }
        }
        val m = CostMatrix()
        for ((k, v) in cost) m.set(k / 100, k % 100, v)
        danger = m
    }

    /** Ход в обход опасности (для тех, кто не в бою). */
    private fun go(c: Creep, target: Position) {
        val m = danger
        if (m == null) { c.moveTo(target); return }
        val o: dynamic = js("({})")
        o.costMatrix = m
        c.moveTo(target, o.unsafeCast<FindPathOptions>())
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
        // наша площадка спавна у этого источника, оставшаяся от погибшего строителя, — и есть клетка базы: v39 ставил
        // рядом новую (старая — препятствие для плана), и к 1740-му у источника (1,32) стояли три наших спавна
        val own = getObjectsByPrototype(ConstructionSite::class).firstOrNull { it.my == true && isSpawnSite(it) && cheb(posOf(it), s) == 2 }
        if (own != null) {
            val c = posOf(own)
            val slots = ArrayList<Pos>()
            for (ax in c.x - 1..c.x + 1) for (ay in c.y - 1..c.y + 1) {
                val a = Pos(ax, ay)
                if (a != c && walkable(a, blocked) && cheb(a, s) == 1) slots.add(a)
            }
            if (slots.isNotEmpty()) return Base(idOf(src), c, slots)
        }
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
    /** До кармана путь меряется до его окрестности: стены стоят в 4–5 клетках от центра блока контейнеров. */
    private const val VAULT_REACH = 6

    private class Vault(
        val index: Int, val containers: List<Pos>, val interior: Set<Pos>, val sealed: Boolean,
        val breaches: List<Pair<Pos, Pos>>, val spawnCell: Pos, val towerCell: Pos?,
    ) {
        /** Клетка пролома и клетка снаружи у неё — выбираются в момент заказа пробойщика, от ближайшего нашего спавна. */
        var wall: Pos? = null
        var outside: Pos? = null
        var stage = if (sealed) "breach" else "build"
        var spawnId: String? = null
        val breacherRole get() = "breacher:$index"
        val builderRole get() = "vaultBuilder:$index"
    }

    private fun neighbours(p: Pos): List<Pos> {
        val out = ArrayList<Pos>(8)
        for (dx in -1..1) for (dy in -1..1) if (dx != 0 || dy != 0) out.add(Pos(p.x + dx, p.y + dy))
        return out
    }

    private fun planVaults(start: Position, all: Array<GameObject>) {
        val ourStart = posOf(start)
        val enemy = enemyStart ?: return
        val containers = all.filter { it is StructureContainer }.map { posOf(it) }
        if (containers.isEmpty()) return
        // кластеры контейнеров (в двух клетках друг от друга)
        val clusters = ArrayList<MutableList<Pos>>()
        for (c in containers) {
            val home = clusters.firstOrNull { cl -> cl.any { cheb(it, c) <= 2 } }
            if (home != null) home.add(c) else clusters.add(mutableListOf(c))
        }
        fun center(cl: List<Pos>) = Pos(cl.sumOf { it.x } / cl.size, cl.sumOf { it.y } / cl.size)
        // свой карман — первым, по ПУТИ от стартов (до его окрестности: внутрь пути нет): v20 мерил прямой от спавна у
        // (67,1), и «своим» оказался карман за центральной стеной
        fun reach(from: Pos, c: Pos): Int {
            val r = searchPath(cell(from), SearchGoal(pos = cell(c), range = VAULT_REACH), SearchPathOptions(plainCost = 1, swampCost = 5))
            return if (r.incomplete) Int.MAX_VALUE / 4 else r.cost
        }
        val ordered = clusters.sortedByDescending { reach(enemy, center(it)) - reach(ourStart, center(it)) }
        for (cluster in ordered) {
            val c0 = center(cluster)
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
            fun outsideOpen(p: Pos) = open(p) && p !in interior
            val breaches = ArrayList<Pair<Pos, Pos>>()
            if (sealed) for (w in walls) {
                if (neighbours(w).none { it in interior }) continue
                for (n in neighbours(w)) if (outsideOpen(n)) breaches.add(w to n)
            }
            if (sealed && breaches.isEmpty()) { println("vault: sealed but no breachable wall at ${c0.x},${c0.y}"); continue }
            // глубина клетки: расстояние до ближайшей клетки, где может встать соперник (пролом закроет наш рампарт)
            fun depth(p: Pos): Int {
                var d = 99
                for (dx in -6..6) for (dy in -6..6) {
                    val q = Pos(p.x + dx, p.y + dy)
                    if (outsideOpen(q)) d = minOf(d, cheb(p, q))
                }
                return d
            }
            val free = interior.filter { it !in cluster }
            val spawnCell = free.filter { c -> cluster.any { cheb(it, c) <= 1 } }
                .maxWithOrNull(compareBy<Pos> { minOf(depth(it), RANGED_RANGE + 1) }.thenBy { c -> cluster.count { cheb(it, c) <= 1 } }) ?: continue
            val towerCell = free.filter { it != spawnCell && cluster.any { c -> cheb(c, it) <= 1 } }
                .maxWithOrNull(compareBy<Pos> { minOf(depth(it), RANGED_RANGE + 1) }.thenByDescending { cheb(it, spawnCell) })
            val v = Vault(vaults.size, cluster, interior, sealed, breaches, spawnCell, towerCell)
            vaults.add(v)
            println("vault ${v.index}: containers=${cluster.joinToString(" ") { "${it.x},${it.y}" }} sealed=$sealed interior=${interior.size} " +
                "breaches=${breaches.size} spawn=${spawnCell.x},${spawnCell.y} depth=${depth(spawnCell)} tower=${towerCell?.let { "${it.x},${it.y}" }} " +
                "reach us=${reach(ourStart, c0)} them=${reach(enemy, c0)}")
        }
    }

    /** Пролом — клетка стены, до которой ближе всего идти от этого спавна (по пути). */
    private fun chooseBreach(v: Vault, from: Position) {
        if (!v.sealed || v.wall != null) return
        val best = v.breaches.minByOrNull { pathTicks(from, cell(it.second)) } ?: return
        v.wall = best.first
        v.outside = best.second
        println("vault ${v.index}: breach at (${best.first.x},${best.first.y}) from (${from.x},${from.y})")
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
        // WORK в хвосте: части гибнут спереди назад, и 850 урона v45 сняли все пять WORK головы — калека держал роль сейфа
        // 1293 тика; при ногах и переносках спереди те же 850 урона не трогают ни одного WORK
        return (List(work) { MOVE } + List(4) { CARRY } + List(work) { WORK }).toTypedArray()
    }

    /** Заправщик сейфа без стройки: четыре CARRY носят по 200 из контейнера в спавн за два-три тика. */
    private fun fillerBody(): Array<BodyPartType> = arrayOf(CARRY, CARRY, CARRY, CARRY, MOVE)

    private fun worksiteSafe(cells: List<Pos>): Boolean =
        getObjectsByPrototype(Creep::class).none { c -> !c.my && isCombat(c) && cells.any { cheb(posOf(c), it) <= WORKSITE_SAFE } }

    private fun wallObject(v: Vault, all: Array<GameObject>): GameObject? {
        val w = v.wall ?: return null
        return all.firstOrNull { it is StructureWall && it.x == w.x && it.y == w.y }
    }

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
        for (v in vaults) {
            val w = v.wall
            if (v.stage == "breach" && w != null && wallObject(v, all) == null) {
                v.stage = "build"
                println("vault ${v.index}: breached t=$t at (${w.x},${w.y})")
            }
            if (v.spawnId == null) {
                val sp = mySpawns.firstOrNull { it.x == v.spawnCell.x && it.y == v.spawnCell.y }
                if (sp != null) { v.spawnId = idOf(sp); v.stage = "run"; println("vault ${v.index}: spawn up t=$t at (${sp.x},${sp.y})") }
            }
        }
        val mySites = all.filter { it is ConstructionSite && it.asDynamic().my == true }.unsafeCast<List<ConstructionSite>>()
        for (c in mine) {
            if (c.spawning) continue
            val role = roleOf[idOf(c)] ?: continue
            val v = vaults.getOrNull(role.substringAfter(':', "").toIntOrNull() ?: -1) ?: continue
            // роль без рабочих частей свободна: следующий заказ сейфа идёт новым телом
            val spent = (role.startsWith("breacher:") && liveParts(c, ATTACK) == 0) ||
                (role.startsWith("vaultBuilder:") && v.stage != "run" && liveParts(c, WORK) == 0)
            if (spent) { roleOf.remove(idOf(c)); println("role t=$t $role spent ${bodyOf(c)}"); continue }
            when {
                role.startsWith("breacher:") -> {
                    val wo = wallObject(v, all)
                    val out = v.outside
                    if (wo == null || out == null) { roleOf.remove(idOf(c)); continue }
                    // последний удар — только при строителе сейфа у пролома: пролом, открытый до его прихода, — это его
                    // сейф (stachu3478 выносил наш карман носильщиками, пока пролом стоял открытым без строителя сотни тиков)
                    val hit = liveParts(c, ATTACK) * ATTACK_POWER
                    val left = wo.asDynamic().hits.unsafeCast<Int>()
                    val builderNear = mine.any { roleOf[idOf(it)] == v.builderRole && !it.spawning && cheb(posOf(it), out) <= 3 }
                    if (getRange(c, wo) > 1) go(c, cell(out))
                    else if (left > 2 * hit || builderNear) c.attack(wo)
                }
                role.startsWith("vaultBuilder:") -> runVaultBuilder(t, c, v, all, mySites, mySpawns)
            }
        }
    }

    private fun containerAt(p: Pos, all: Array<GameObject>): StructureContainer? =
        all.firstOrNull { it is StructureContainer && it.x == p.x && it.y == p.y } as? StructureContainer

    private fun runVaultBuilder(t: Int, c: Creep, v: Vault, all: Array<GameObject>, mySites: List<ConstructionSite>, mySpawns: List<StructureSpawn>) {
        val me = posOf(c)
        if (v.stage == "breach") { v.outside?.let { if (cheb(me, it) > 2) go(c, cell(it)) }; return }
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
            // стоим у контейнера в досягаемости площадки: берём и строим в один тик; контейнеры пусты — берём из спавна
            // сейфа (v12: соперник пробил рампарт, когда сейф опустел, и строителю было не из чего закрыть пролом)
            val vaultSpawn = mySpawns.firstOrNull { idOf(it) == v.spawnId }
            val nearC: Structure? = full.firstOrNull { getRange(c, it) <= 1 }
                ?: vaultSpawn?.takeIf { full.isEmpty() && getRange(c, it) <= 1 && energyOf(it) > 0 }
            if (nearC != null && free > 0) c.withdraw(nearC, RESOURCE_ENERGY)
            if (full.isEmpty() && vaultSpawn != null && nearC == null && e == 0) { go(c, vaultSpawn); return }
            if (e > 0 && getRange(c, site) <= 3) c.build(site)
            val spot = v.interior.filter { p -> p !in v.containers && p != v.spawnCell && p != v.towerCell && full.any { cheb(posOf(it), p) <= 1 } && cheb(p, posOf(site)) <= 3 }
                .minByOrNull { cheb(it, me) }
            if (spot != null && spot != me) go(c, cell(spot)) else if (spot == null && getRange(c, site) > 3) go(c, site)
            return
        }
        // заправщик: из контейнера в спавн сейфа и его башню
        val spawn = mySpawns.firstOrNull { idOf(it) == v.spawnId }
        val tower = all.firstOrNull { it is StructureTower && it.asDynamic().my == true && v.towerCell?.let { tc -> it.x == tc.x && it.y == tc.y } == true } as? StructureTower
        val sink: Structure? = when {
            tower != null && (tower.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0 -> tower
            spawn != null && (spawn.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0 && full.isNotEmpty() -> spawn
            else -> null
        }
        if (e > 0 && sink != null) {
            if (getRange(c, sink) <= 1) c.transfer(sink, RESOURCE_ENERGY) else go(c, sink)
            return
        }
        // сейф выпит — башню кормит спавн сейфа, пока его бойцы в её досягаемости: v51–v52 после 1221–2347-го держали
        // башню сейфа пустой 2398 тиков из 2400, заправщик стоял без дела, а его бойцы стояли в 10 клетках от неё
        // 375–2183 тика за ничью. Прирост спавна 1 в тик — ровно выстрел на перезарядку (50–80 урона в тик из башни за
        // стеной 10000), а тот же прирост в бойцах окупается, только если боец живёт 1000–1600 тиков
        val foesNear = tower != null && getObjectsByPrototype(Creep::class).any { !it.my && isCombat(it) && getRange(it, tower) <= TOWER_RANGE }
        val src: Structure = full.minByOrNull { getRange(c, it) }
            ?: spawn?.takeIf { foesNear && energyOf(it) > 0 && (tower?.store?.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0 }
            ?: return
        if (free > 0) { if (getRange(c, src) <= 1) c.withdraw(src, RESOURCE_ENERGY) else go(c, src) }
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
                planVaults(w, all)
            }
        }
        resolveRoles(t, mine)

        // база узнаёт свой спавн, когда он достроен
        for (b in bases) if (b.spawnId == null) {
            val sp = mySpawns.firstOrNull { it.x == b.spawnCell.x && it.y == b.spawnCell.y }
            if (sp != null) {
                b.spawnId = idOf(sp)
                if (spawnUpAt < 0) {
                    spawnUpAt = t
                    val starter = mine.firstOrNull { !it.spawning && liveParts(it, WORK) > 0 && slotOf[idOf(it)]?.let { s -> b.slots.contains(s) } == true }
                    val plan = if (starter == null) null else expansionTarget(listOf(sp), sources, all)
                    if (starter != null && plan != null) {
                        founderId = idOf(starter); founderPlan = plan
                        println("founder plan t=$t ${bodyOf(starter)} -> (${plan.spawnCell.x},${plan.spawnCell.y})")
                    }
                }
                println("spawn up t=$t at (${sp.x},${sp.y}) e=${energyOf(sp)}")
            }
        }
        // павшая база выбывает: ни её спавна, ни близнеца, ни нашей площадки спавна на её клетке. v46–v51 держали её в
        // `bases` навсегда, и её источник — ближайший наш, в 19–25 тиках пути — пропадал из кандидатов расширения: 5451
        // тик, 40 % всего свободного и безопасного времени, во всех четырёх не-победах с けろびー и ни в одной из побед
        val fallen = bases.filter { b ->
            b.spawnId != null && byId[b.spawnId!!] == null && (b.twinId == null || byId[b.twinId!!] == null) &&
                sites.none { it.my == true && it.x == b.spawnCell.x && it.y == b.spawnCell.y }
        }
        for (b in fallen) {
            bases.remove(b)
            slotOf.entries.removeAll { it.value in b.slots }
            println("base fallen t=$t at (${b.spawnCell.x},${b.spawnCell.y})")
        }
        val exp = expansion
        if (exp != null && exp.spawnId == null) {
            val sp = mySpawns.firstOrNull { it.x == exp.spawnCell.x && it.y == exp.spawnCell.y }
            if (sp != null) {
                exp.spawnId = idOf(sp)
                bases.add(exp)
                val builder = builderId
                expansion = null
                builderId = null
                println("expansion spawn up t=$t at (${sp.x},${sp.y})")
                // строитель идёт дальше, как основатель: новый спавн рожает ему замену, и он уходит к следующему источнику
                // — без заказа строителя за 850 и без очереди спавна. Так けろびー#19 одним стартовым рабочим ставит спавн
                // каждые 467–620 тиков (230, 723, 1190, 1810), а наш заказ третьей базы ждал 365–669 тиков после второй
                if (builder != null && mine.any { idOf(it) == builder }) {
                    val next = expansionTarget(listOf(sp), sources, all)
                    if (next != null) {
                        founderId = builder; founderPlan = next; founderPlanAt = t
                        println("founder plan t=$t goes on -> (${next.spawnCell.x},${next.spawnCell.y})")
                    }
                }
            }
        }

        val enemyCombat = theirs.filter { isCombat(it) }
        buildDanger(enemyCombat, all.filter { it is StructureTower && it.asDynamic().my == false }.unsafeCast<List<StructureTower>>())
        for (e in enemyCombat) if (enemyCombatSeen.add(idOf(e))) enemyBirths.add(t to typesOf(e))
        if (enemySpawnSeenAt < 0 && all.any { it is StructureSpawn && it.asDynamic().my == false }) enemySpawnSeenAt = t
        for (b in bases) {
            val sp = b.spawnId?.let { byId[it] } as? StructureSpawn ?: continue
            planRamparts(t, b, mine, all)
        }
        val enemyTowers = all.filter { it is StructureTower && it.asDynamic().my == false }.unsafeCast<List<StructureTower>>()

        val homeSpawns = homeSpawnObjects(byId)
        val workersAll = mine.filter { liveParts(it, WORK) > 0 }
        val near = homeThreats(enemyCombat, homeSpawns, workersAll, 0)
        val wide = homeThreats(enemyCombat, homeSpawns, workersAll, THREAT_RELEASE)
        defending = if (defending) wide.isNotEmpty() else near.isNotEmpty()
        if (defending) attackedOnce = true
        val threats = if (defending) wide else emptyList()
        threatStrikes = threats.any { e -> homeSpawns.any { getRange(e, it) <= RANGED_RANGE + 1 } || workersAll.any { getRange(e, it) <= RANGED_RANGE + 1 } }
        homeHolds = threats.isEmpty() || run {
            val towersNow = all.filter { it is StructureTower && it.asDynamic().my == true && energyOf(it) > 0 }.unsafeCast<List<StructureTower>>()
            val ours = mine.filter { !it.spawning && isCombat(it) && idOf(it) !in wave && idOf(it) !in roleOf }.map { simOf(it) } +
                towersNow.filter { tw -> threats.any { getRange(it, tw) <= TOWER_RANGE } }.map { simTowerOf(it, all) }
            simulate(ours, threats.map { simOf(it) }).win
        }
        val myTowers = all.filter { it is StructureTower && it.asDynamic().my == true }.unsafeCast<List<StructureTower>>()
        for (b in bases) {
            val sp = b.spawnId?.let { byId[it] } as? StructureSpawn ?: continue
            planTwin(t, b, sp, mine, all)
            planTower(t, b, sp, mine, all)
            val tc = b.twinCell
            if (tc != null && b.twinId == null) {
                val tw = mySpawns.firstOrNull { it.x == tc.x && it.y == tc.y }
                if (tw != null) { b.twinId = idOf(tw); println("twin spawn up t=$t at (${tw.x},${tw.y})") }
            }
        }
        runTowers(myTowers, theirs, mine)

        runWorkers(t, mine, byId, sites.filter { it.my == true }, sources, all, enemyCombat)
        runVault(t, mine, all, mySpawns)
        runArmy(t, mine, theirs, enemyCombat, enemyTowers, all, byId, threats)
        for (b in bases) {
            val sp = b.spawnId?.let { byId[it] } as? StructureSpawn ?: continue
            if (sp.spawning == null) runSpawn(t, b, sp, mine, threats)
            // второй спавн рожает, пока первый занят: запас у них общий, очередь — две
            val tw = b.twinId?.let { byId[it] } as? StructureSpawn ?: continue
            if (tw.spawning == null && sp.spawning != null) spawnFighter(t, tw, energyOf(tw), why = if (threats.isEmpty()) "twin" else "threat")
        }
        for (v in vaults) {
            val sp = v.spawnId?.let { byId[it] } as? StructureSpawn ?: continue
            if (sp.spawning != null) continue
            // заправщик погиб — сейф рождает себе нового: без него спавн сейфа живёт на +1 в тик
            val left = v.containers.sumOf { p -> containerAt(p, all)?.let { energyOf(it) } ?: 0 }
            if (!hasRole(v.builderRole) && left > 0) order(t, sp, energyOf(sp), fillerBody(), v.builderRole)
            else spawnFighter(t, sp, energyOf(sp), why = "vault${v.index}")
        }

        logChanges(t, all, mine)
        if (t % LOG_EVERY == 0) logStatus(t, mine, theirs, mySpawns, sources, all)
    }

    // ---------- дебют ----------

    /** Домашний источник — тот, до которого рабочий дойдёт раньше; при равенстве — дальше от соперника. v19 брал
     *  ближний к нему по прямой, но прямая через центральную стену врёт: stachu3478 пошёл ко второму нашему источнику
     *  снизу, а v19 встал у верхнего — и проиграл. Спорный источник у нас забирает охота на его строителя. */
    private fun openingPlan(w: Creep, sources: Array<Source>, all: Array<GameObject>) {
        val blocked = blockedCells(all)
        val enemy = enemyStart
        // дом — не просто ближний источник, а тот, откуда раньше встанет вторая база: приход к нему плюс путь основателя
        // до цели, которую бот выберет сам (порядок `expansionTarget`: наибольший запас по пути перед соперником). v46
        // при равном приходе (37 = 37 на 21 карте из 22) брал угол дальше от соперника по прямой — это не покупало ничего
        // (его первый боец приходил даже раньше), а основатель шёл оттуда 65 тиков во второй угол, центральный источник
        // оставался ему: против stachu3478#17 дом в другом углу дал 9–0–3, этот — 7–6–8 (34 игры)
        val them = HashMap<String, Int>()
        if (enemy != null) for (s in sources) them[idOf(s)] = pathTicks(cell(enemy), s)
        fun secondTicks(home: Source): Int {
            if (enemy == null) return 0
            val cand = sources.filter { it !== home }.map { o -> Triple(o, pathTicks(home, o), them[idOf(o)] ?: Int.MAX_VALUE / 4) }
                .filter { (_, us, th) -> us * 4 <= th * 5 }
                .sortedWith(compareByDescending<Triple<Source, Int, Int>> { it.third - it.second }.thenBy { it.second })
            return cand.firstOrNull()?.second ?: Int.MAX_VALUE / 4
        }
        val ranked = sources.map { s ->
            val ticks = ticksTo(w, s, 1)
            val far = if (enemy == null) 0 else getRange(cell(enemy), s)
            Triple(s, ticks, far) to ticks + secondTicks(s)
        }.sortedWith(compareBy<Pair<Triple<Source, Int, Int>, Int>> { it.second }.thenBy { it.first.second }.thenByDescending { it.first.third })
            .map { it.first }
        println("opening: sources by second base " + ranked.joinToString(" ") { "(${it.first.x},${it.first.y})t=${it.second}+${secondTicks(it.first)}" })
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
        // основатель уходит, когда замена вышла из спавна и одна насыщает источник первой базы
        val fid = founderId
        var plan = founderPlan
        if (fid != null && plan != null && builderId == null && expansion == null && !builderPending) {
            val founder = mine.firstOrNull { idOf(it) == fid }
            // база, на которой стоит основатель (первая — для стартового, новая — для идущего дальше)
            val b1 = founder?.let { f -> bases.firstOrNull { b -> slotOf[fid]?.let { b.slots.contains(it) } == true }
                ?: bases.minByOrNull { cheb(posOf(f), it.spawnCell) } }
            if (founder == null || b1 == null) { founderId = null; founderPlan = null }
            else {
                val needWork = (SOURCE_ENERGY_REGEN + HARVEST_POWER - 1) / HARVEST_POWER
                val stay = mine.filter { c ->
                    idOf(c) != fid && !c.spawning && idOf(c) !in roleOf && liveParts(c, WORK) > 0 && cheb(posOf(c), b1.spawnCell) <= 2
                }.sumOf { liveParts(it, WORK) }
                // пока на карте есть его бойцы, основатель уходит только при нашем бойце дома: v42 уходил на 302-м, и
                // рейдер M3R3 убивал его у первого рампарта или замену на открытой клетке первой базы
                val covered = enemyCombat.isEmpty() || mine.any { isCombat(it) && !it.spawning }
                // цель устаревает, пока он ждёт замену: раз в полсотни тиков — заново, от его базы
                val fromSpawn = b1.spawnId?.let { id -> mySpawnsById(id) }
                if (stay >= needWork && covered && t - founderPlanAt >= LOG_EVERY && fromSpawn != null) {
                    founderPlanAt = t
                    plan = expansionTarget(listOf(fromSpawn), sources, all)
                    founderPlan = plan
                    if (plan == null) { founderId = null }
                }
                if (plan != null && stay >= needWork && covered && worksiteSafe(listOf(plan.spawnCell))) {
                    builderId = fid; expansion = plan; expansionPlaced = false
                    slotOf.remove(fid); founderId = null; founderPlan = null
                    println("founder t=$t ${bodyOf(founder)} leaves for (${plan.spawnCell.x},${plan.spawnCell.y})")
                }
            }
        }
        for (w in workers) {
            val id = idOf(w)
            if (id in roleOf) continue
            if (id == builderId) { runBuilder(w, byId, mySites, all, enemyCombat); continue }
            val slot = slotOf[id] ?: assignSlot(w) ?: continue
            val base = bases.firstOrNull { it.slots.contains(slot) } ?: continue
            val src = byId[base.sourceId] as? Source ?: continue
            if (w.x != slot.x || w.y != slot.y) {
                go(w, cell(slot))
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
        // башня раньше рампартов: рампарт над спавном его армия пробивает за ~40 тиков, и добытчики v15 отстраивали
        // его снова и снова, не дойдя до башни
        // дом угрозу не держит — энергия в спавн (защитник), строим только рампарты (200 за 10000 — дешевле бойца);
        // держит или угроз нет — башня раньше рампартов (v16). v30 проиграл けろびー к 900-му: площадка башни встала на
        // 368-м от первого рейдера M3R3, 180 тиков вся добыча шла в неё, и спавн не родил ни одного защитника
        // (v32 отдавал добычу спавну и строил только рампарты, пока дом не держит угрозу, — и против потока M4R3H1
        // けろびー#1 башня базы так и не встала; откат к v16: башня раньше рампартов)
        val site = mySites.filter { getRange(w, it) <= 3 }
            .sortedWith(compareBy<ConstructionSite> { if (isRampartSite(it)) 1 else 0 }.thenBy { getRange(w, it) }).firstOrNull()
        // на клетке площадки стоит крип — стройка препятствия не идёт; v6 так простоял 400 тиков: боец встал на
        // площадку башни, рабочий с полным запасом каждый тик «строил» впустую и не копал, спавн жил на +1 в тик
        val siteFree = site != null && (isRampartSite(site) || getObjectsByPrototype(Creep::class).none { it.x == site.x && it.y == site.y })
        // все наши площадки у базы — оборона (рампарты и башня), и строятся они и под угрозой: v12 весь матч «защищался»
        // и так и не начал башню первой базы (0/1250 к 2500-му)
        if (site != null && siteFree) {
            if (e >= batchFor(w) || (src.energy == 0 && e > 0)) {
                if (w.build(site).asDynamic().unsafeCast<Int>() == 0) return
            } else if (src.energy > 0) { w.harvest(src); return }
        }
        // сдаём в тот из спавнов базы рядом, где есть место (второй спавн делит с первым запас на одно тело)
        val sink = getObjectsByPrototype(StructureSpawn::class)
            .filter { it.my == true && getRange(w, it) <= 1 && (it.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0 }
            .minByOrNull { energyOf(it) } ?: spawn
        val spawnFree = sink.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0
        // все спавны рядом полны, а у источника есть свободная клетка базы, смежная со спавном с местом, — туда: иначе
        // добытчик стоит, а близнец не получает ничего
        if (spawnFree <= 0) {
            val base = bases.firstOrNull { b -> slotOf[idOf(w)]?.let { b.slots.contains(it) } == true }
            val roomy = getObjectsByPrototype(StructureSpawn::class).filter { it.my == true && (it.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0 }
            val held = slotOf.values.toSet()
            val occupied = getObjectsByPrototype(Creep::class).map { posOf(it) }.toSet()
            val better = base?.slots?.firstOrNull { c -> c !in held && c !in occupied && roomy.any { cheb(posOf(it), c) <= 1 } }
            if (better != null) { slotOf[idOf(w)] = better; println("harvester t=$t moves to (${better.x},${better.y}) by a spawn with room") }
        }
        val canDeliver = e > 0 && spawnFree > 0 && getRange(w, sink) <= 1
        // копка исполняется раньше сдачи: место под неё — от запаса начала тика
        if (src.energy > 0 && getRange(w, src) <= 1 && cap - e >= h) {
            w.harvest(src)
        } else if (spawnFree <= 0 && e > 0) {
            val site = mySites.filter { getRange(w, it) <= 3 }.minByOrNull { getRange(w, it) }
            if (site != null) w.build(site)
        }
        if (canDeliver) w.transfer(sink, RESOURCE_ENERGY)
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
    private fun expansionTarget(froms: List<GameObject>, sources: Array<Source>, all: Array<GameObject>): Base? {
        val enemySpawns = all.filter { it is StructureSpawn && it.asDynamic().my == false }
        val enemyFrom: List<Position> = enemySpawns.ifEmpty { listOfNotNull(enemyStart?.let { cell(it) }) }
        if (enemyFrom.isEmpty()) return null
        val taken = bases.map { it.sourceId }.toSet()
        val blocked = blockedCells(all)
        if (froms.isEmpty()) return null
        // «наш» путь — от ближайшей по пути нашей базы: v43 слал строителей к (50,79) из (3,32) — 95 тиков по его
        // маршруту, пятеро легли, — при второй базе (32,96) в 36 тиках от цели
        val nearest = HashMap<String, GameObject>()
        val ranked = sources.filter { idOf(it) !in taken }.map { s ->
            val (from, d) = froms.map { it to pathTicks(it, s) }.minByOrNull { it.second }!!
            nearest[idOf(s)] = from
            Triple(s, d, enemyFrom.minOf { pathTicks(it, s) })
        }
        println("expansion: candidates " + ranked.joinToString(" ") { "(${it.first.x},${it.first.y})us=${it.second}/them=${it.third}" })
        // кандидат — любой свободный источник (без его спавна или площадки рядом), кроме глубоко его (к нему он вдвое
        // ближе нас); первым — самый спорный: тот, до которого ему ближе всего, — его он заберёт раньше, а спокойный
        // наш подождёт. v29 брал только «наши» (мы ближе его спавна) и отдал stachu3478 свой второй домашний источник:
        // его спавн у центрального был к нему на восемь тиков ближе, источник стоял свободным до ~820-го, а к 1200-му у
        // него было четыре источника против наших двух. Лучший из БЕЗОПАСНЫХ — как с v22
        val hisStuff = all.filter { it.asDynamic().my == false && (it is StructureSpawn || it is ConstructionSite) }
        // кандидаты — где мы не дальше его больше чем на четверть пути (v30–v32 слали строителя через всю карту к ЕГО
        // второму домашнему источнику); порядок — как в v29, с наибольшего нашего запаса: «самый спорный первым» (v30,
        // v34) уводил первого строителя к центральному источнику вместо нашего второго домашнего, и A/B против
        // stachu3478#15 дал 2 из 4 против 4 из 4 у v29 — у него к 1500-му было 3–4 источника против наших двух
        val free = ranked.filter { (s, us, them) -> us * 4 <= them * 5 && hisStuff.none { getRange(it, s) <= INTRUDER_SOURCE_RANGE + 1 } }
        for ((s, _, _) in free.sortedWith(compareByDescending<Triple<Source, Int, Int>> { it.third - it.second }.thenBy { it.second })) {
            val from = nearest[idOf(s)]!!
            planBase(s, from, blocked)?.takeIf { worksiteSafe(listOf(it.spawnCell)) }?.let { expansionFrom = idOf(from); return it }
        }
        return null
    }

    private fun runBuilder(w: Creep, byId: Map<String, GameObject>, mySites: List<ConstructionSite>, all: Array<GameObject>, enemyCombat: List<Creep>) {
        val base = expansion ?: return
        val src = byId[base.sourceId] as? Source ?: return
        val slot = base.slots.minByOrNull { getRange(w, cell(it)) } ?: return
        if (getRange(w, src) > 1) { go(w, cell(slot)); return }
        // по предложению оператора: сперва рампарт ПОД СОБОЙ, потом рампарт на клетку спавна, потом спавн под ним —
        // строитель и будущий спавн с первого часа под 10000 хитов (v4–v10 строили голый спавн, stachu3478 снёс его
        // вместе со строителем)
        val here = Pos(w.x, w.y)
        val structures = getObjects()
        fun rampartAt(p: Pos) = structures.any { it is StructureRampart && it.asDynamic().my == true && it.x == p.x && it.y == p.y }
        val spawnSiteThere = mySites.any { it.x == base.spawnCell.x && it.y == base.spawnCell.y && isSpawnSite(it) }
        val next: Pair<Pos, String>? = when {
            here in base.slots && !rampartAt(here) -> here to "rampart"
            // площадка спавна уже заложена (прежним строителем) — рампарт на её клетку не встанет, строим спавн
            spawnSiteThere -> base.spawnCell to "spawn"
            !rampartAt(base.spawnCell) -> base.spawnCell to "rampart"
            else -> base.spawnCell to "spawn"
        }
        val (cellNext, kind) = next ?: return
        var site = mySites.firstOrNull { it.x == cellNext.x && it.y == cellNext.y && (if (kind == "rampart") isRampartSite(it) else isSpawnSite(it)) }
        if (site == null) {
            val r = if (kind == "rampart") createConstructionSite(cellNext.x, cellNext.y, StructureRampart::class.js)
                else createConstructionSite(cellNext.x, cellNext.y, StructureSpawn::class.js)
            println("expansion: $kind site (${cellNext.x},${cellNext.y}) src=(${src.x},${src.y}) t=${getTicks()} err=${r.error}")
            if (r.error != null) { if (kind == "spawn") expansion = null; return }
            expansionPlaced = true
            site = r.`object`
        }
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
        val k = minOf(MAX_CREEP_SIZE / 2, energy / pair)
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
            idOf(c) != builderId && idOf(c) != founderId &&
                (slotOf[idOf(c)]?.let { b.slots.contains(it) } == true || (c.spawning && getRange(c, cell(b.spawnCell)) <= 1 && liveParts(c, WORK) > 0))
        }.sumOf { liveParts(it, WORK) }

    /** Угрозы дому: боевые враги у наших спавнов или у наших рабочих (с запасом `extra` — для снятия защиты). */
    private fun homeThreats(enemyCombat: List<Creep>, homeSpawns: List<GameObject>, workers: List<Creep>, extra: Int): List<Creep> {
        val open = openVaults(enemyCombat)
        return enemyCombat.filter { e ->
            homeSpawns.any { getRange(e, it) <= HOME_THREAT_RANGE + extra && reaches(e, it, open) } ||
                workers.any { getRange(e, it) <= WORKER_THREAT_RANGE + extra && reaches(e, it, open) }
        }
    }

    /** Угроза — тот, кто может навредить, а не тот, кто рядом: спавн сейфа стоит в глубине кармана (дальше выстрела от
     *  любой внешней клетки) за стенами и нашим рампартом в проломе, и крип снаружи запечатанного кармана до него и до
     *  заправщика внутри не достаёт. v43 с けろびー#12 73 % времени после 1000-го считал «угрозой» его M4R3H1 у стен
     *  сейфа: весь дом уходил в защитники, волна не выходила ни разу, 62 из 69 рождений — «threat», ничья. */
    private fun reaches(e: Creep, target: GameObject, open: Set<Int>): Boolean {
        val p = posOf(target)
        val v = vaults.firstOrNull { p in it.interior } ?: return true
        return v.index in open || posOf(e) in v.interior
    }

    /** Сейфы, чья печать открыта или откроется раньше, чем дом успеет вернуться: клетка стены кармана без стены и без
     *  нашего рампарта — открыта; под огнём — хиты печати на урон, который она теряет в тик (замер, а не возможный
     *  урон: его M4R3H1 у стен сейфа v43 за 3900 тиков сняли с рампарта пролома 2674), против пути от первой базы. */
    private fun openVaults(enemyCombat: List<Creep>): Set<Int> {
        val t = getTicks()
        if (openAt == t) return openCache
        val out = HashSet<Int>()
        val structures = getObjects()
        val sealHits = HashMap<Pos, Int>()
        for (o in structures) {
            if (o is StructureWall || (o is StructureRampart && o.asDynamic().my == true)) {
                val q = posOf(o)
                sealHits[q] = (sealHits[q] ?: 0) + (o.asDynamic().hits?.unsafeCast<Int>() ?: 0)
            }
        }
        for (v in vaults) {
            if (!v.sealed || v.spawnId == null) { out.add(v.index); continue }
            val back = vaultReturn.getOrPut(v.index) {
                bases.firstOrNull()?.let { b -> pathTicks(cell(b.spawnCell), cell(v.spawnCell)) } ?: 0
            }
            for ((w, _) in v.breaches) {
                val hits = sealHits[w] ?: 0
                if (hits <= 0) { out.add(v.index); break }
                val dps = (sealPrev[w] ?: hits) - hits
                if (dps > 0 && hits / dps < back) { out.add(v.index); break }
            }
        }
        sealPrev.clear()
        sealPrev.putAll(sealHits)
        openAt = t
        openCache = out
        return out
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
        // башня — после сейфа, если дом ещё не трогали. v16 ставил её сразу после первого бойца: 1250 из добычи на
        // 330–460-м тике — ровно когда приходят первые M4R3H1 けろびー, и спавн без притока проиграл дважды к 500-му.
        // Ранний дом держат сторожевые рампарты (planRamparts), башня — ответ на нападение
        val v = vaults.firstOrNull()
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
            if (c == b.spawnCell || c in b.slots || c == b.twinCell || !walkable(c, blocked)) continue
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

    /** Рампарты базы — над спавном и над каждой занятой клеткой добытчика (200 за 10000 хитов, свои под ним
     *  работают, урон идёт в рампарт): ставятся, когда у дома есть боец или его уже атаковали, по одному. Рейдеры
     *  けろびー (M3R3, 30 в тик) убивали незащищённых добытчиков за 13–33 тика; сквозь рампарт им нужно 330. */
    private fun planRamparts(t: Int, b: Base, mine: List<Creep>, all: Array<GameObject>) {
        // крыша — с того тика, как у него есть спавн: его первый рейдер приходит к ~364-му (M3R3 けろびー#2/#7 с 282-го,
        // по одному в ~67 тиков), а v42 начинал рампарты только с первым своим бойцом — замена W5 на базе, откуда ушёл
        // основатель, гибла на открытой клетке, спавн без дохода копил на бойца до конца игры (два поражения fame 28.09)
        if (mine.none { isCombat(it) } && !attackedOnce && enemySpawnSeenAt < 0) return
        val occupied = b.slots.filter { s -> mine.any { it.x == s.x && it.y == s.y && idOf(it) in slotOf } }
        // сторожевые: клетки-выходы спавна под рампартом, по одной на каждого бойца дома и ещё одна — боец на своём
        // рампарте неуязвим, пока тот цел (двум M4R3H1 на 10000 нужно ~170 тиков), и рождается спавн прямо на них
        val homeFighters = mine.count { isCombat(it) && idOf(it) !in wave && idOf(it) !in roleOf }
        val guards = guardCells(b, all).take(homeFighters + 1)
        // сперва клетки добытчиков — доход базы: добытчик на своём рампарте копает и сдаёт под огнём, и спавн рожает
        // бойца; потом спавн, потом сторожевые
        val want = occupied + b.spawnCell + guards
        val have = all.filter { (it is StructureRampart || it is ConstructionSite && isRampartSite(it)) && it.asDynamic().my == true }
            .map { posOf(it) }.toSet()
        if (all.any { it is ConstructionSite && it.asDynamic().my == true && isRampartSite(it) && cheb(posOf(it), b.spawnCell) <= 2 }) return
        // пока у базы строится башня — новых рампартов не ставим: энергия добытчиков одна
        val tc = b.towerCell
        if (tc != null && all.any { it is ConstructionSite && it.asDynamic().my == true && it.x == tc.x && it.y == tc.y }) return
        val next = want.firstOrNull { it !in have } ?: return
        val r = createConstructionSite(next.x, next.y, StructureRampart::class.js)
        println("rampart site t=$t at (${next.x},${next.y}) base=(${b.spawnCell.x},${b.spawnCell.y}) err=${r.error}")
    }

    /** Клетки-выходы спавна базы: соседние со спавном, проходимые, не клетки добытчиков и не клетка башни; первыми —
     *  ближние к сопернику (оттуда приходят). */
    private fun guardCells(b: Base, all: Array<GameObject>): List<Pos> {
        val blocked = blockedCells(all)
        val enemy = enemyStart
        return neighbours(b.spawnCell).filter { it !in b.slots && it != b.towerCell && it != b.twinCell && walkable(it, blocked) }
            .sortedBy { if (enemy == null) 0 else cheb(it, enemy) }
    }

    private fun myRampartAt(p: Pos, all: Array<GameObject>) =
        all.any { it is StructureRampart && it.asDynamic().my == true && it.x == p.x && it.y == p.y }

    private fun isRampartSite(o: GameObject): Boolean {
        val st = o.asDynamic().structure
        if (st != null && st != undefined && protoName(st) == "StructureRampart") return true
        val cost = CONSTRUCTION_COST.asDynamic()["StructureRampart"].unsafeCast<Int?>()
        return cost != null && o.asDynamic().progressTotal == cost
    }

    /** Второй спавн у главной базы: когда экономика уже взяла следующий источник (или брать нечего) и дом держит угрозы;
     *  клетка — у клеток добытчиков (они строят и сдают в него без шага), не отнимая у спавна последний выход. */
    private fun planTwin(t: Int, b: Base, spawn: StructureSpawn, mine: List<Creep>, all: Array<GameObject>) {
        if (b !== bases.first() || b.twinCell != null) return
        if (mine.none { isCombat(it) } || !homeHolds) return
        val economyTaken = bases.size >= 2 || (expansionPlanAt >= 0 && expansionPlan == null && expansion == null)
        if (!economyTaken) return
        val blocked = blockedCells(all)
        fun exits(extra: Pos): Int {
            var n = 0
            for (x in b.spawnCell.x - 1..b.spawnCell.x + 1) for (y in b.spawnCell.y - 1..b.spawnCell.y + 1) {
                val p = Pos(x, y)
                if (p != b.spawnCell && p != extra && p !in b.slots && p != b.towerCell && walkable(p, blocked)) n++
            }
            return n
        }
        var best: Pos? = null
        var bestScore = Int.MIN_VALUE
        for (dx in -2..2) for (dy in -2..2) {
            val c = Pos(b.spawnCell.x + dx, b.spawnCell.y + dy)
            if (c == b.spawnCell || c in b.slots || c == b.towerCell || !walkable(c, blocked)) continue
            val feeders = b.slots.count { cheb(it, c) <= 1 }
            if (feeders == 0 || exits(c) <= 1) continue
            val score = feeders * 1000 + (if (isSwamp(c)) 0 else 100) - cheb(c, b.spawnCell)
            if (score > bestScore) { bestScore = score; best = c }
        }
        val c = best ?: return
        val r = createConstructionSite(c.x, c.y, StructureSpawn::class.js)
        println("twin site t=$t at (${c.x},${c.y}) base=(${b.spawnCell.x},${b.spawnCell.y}) err=${r.error}")
        if (r.error == null) b.twinCell = c
    }

    /** Башня бьёт боевого врага в досягаемости (ближнего — у него выстрел сильнее), при равенстве — самого битого;
     *  без врагов лечит самого битого нашего. */
    private fun runTowers(towers: List<StructureTower>, theirs: List<Creep>, mine: List<Creep>) {
        for (tw in towers) {
            if (energyOf(tw) < TOWER_ENERGY_COST || tw.cooldown > 0) continue
            // рождающийся крип стоит на клетке спавна и неуязвим, пока не выйдет (оператор 27.09.2026)
            val foe = theirs.filter { !it.spawning && getRange(tw, it) <= TOWER_RANGE }
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
        val held = slotOf.filterKeys { it != founderId }.values.toSet()
        val freeSlots = b.slots.count { it !in held }
        // доход идёт (кто-то уже копает на базе) — ждём полное тело: v37 рожал по 200 добытчиков M1W1C1 (2 в тик вместо
        // 10), и шесть таких легли на клетках одной базы; нет никого — рожаем, что можем, иначе спавн живёт на +1 в тик
        val incomeFlows = mine.any { !it.spawning && liveParts(it, WORK) > 0 && slotOf[idOf(it)]?.let { s -> b.slots.contains(s) } == true }
        val fighters = mine.filter { isCombat(it) && idOf(it) !in wave }
        val threat = threats
        val body: Array<BodyPartType>
        val why: String
        // боец «по угрозе» — только против угрозы, которая бьёт; стоящая поодаль армия ждёт, а спавн тем временем берёт
        // сейф и источники (их места работ проверяют свою безопасность сами). v45 против stachu3478#17 рожал «по угрозе»
        // 40–76 бойцов после 1000-го в каждой не-победе (в победах 3–4), угроза била спавн или рабочего лишь 21–58 %
        // времени обороны, сейф не взят ни разу — 2 источника против его 4
        if (threat.isNotEmpty() && threatStrikes && power(fighters) < power(threat) * PUSH_RATIO) {
            // только полное тело: v4 рожал под угрозой M2R2 по одному, и все они погибли поодиночке (basic измерил
            // то же: тринадцать тел по 300 проиграли трём M5R5 на равной энергии)
            return spawnFighter(t, spawn, energy, why = "threat")
        } else if (haveWork < needWork && freeSlots > 0) {
            body = workerBody(needWork - haveWork, if (incomeFlows) Int.MAX_VALUE / 2 else energy)
            why = "work $haveWork/$needWork"
        // расширение и сейф — и под угрозой, если дом её держит (место работ проверяет свою безопасность само): v22
        // проиграл stachu3478 при двух источниках против его пяти — его харассеры у нашей базы держали «угрозу»
        // постоянно, и после второго спавна на 862-м мы не расширились ни разу
        // сейф раньше расширения: его 10000 заберёт тот, кто вскроет первым (stachu3478 вынес наш карман к ~1000-му, пока
        // v36 строил сначала второй спавн), а источник подождёт
        } else if ((homeHolds || !threatStrikes) && fighters.isNotEmpty() && vaultOrder(t, spawn, energy)) {
            return
        } else if ((homeHolds || !threatStrikes) && fighters.isNotEmpty() && expansionOrder(t, spawn, energy)) {
            return
        } else {
            return spawnFighter(t, spawn, energy, why = "army")
        }
        if (energy < costOf(body)) return
        val r = spawn.spawnCreep(body)
        println("spawn t=$t ${bodyText(body)} cost=${costOf(body)} energy=$energy why=$why err=${r.error}")
    }

    /**
     * Строитель следующего спавна — раньше сейфа и независимо от него: источник даёт 10 в тик до конца матча, с 400-го
     * тика это ~46000, а сейф — 10000 один раз. v14 ждал «работающего» сейфа, сейф застрял под бойцами stachu3478, и
     * второй спавн не встал ни разу за две ничьи. Цели — все «наши» источники по очереди, пока они есть и место работ
     * свободно от его бойцов. Возвращает, занят ли спавн (копит на строителя или заказал его).
     */
    private var expansionPlanAt = -1000
    private var expansionPlan: Base? = null
    /** Спавн базы, ближайшей к цели расширения по пути: строителя рожает он. */
    private var expansionFrom: String? = null

    private fun expansionOrder(t: Int, spawn: StructureSpawn, energy: Int): Boolean {
        if (expansion != null || builderId != null || builderPending) return false
        if (t - expansionPlanAt >= LOG_EVERY) {
            expansionPlanAt = t
            val froms = bases.mapNotNull { b -> b.spawnId?.let { id -> getObjects().firstOrNull { idOf(it) == id } } }
            expansionPlan = expansionTarget(froms, getObjectsByPrototype(Source::class), getObjects())
        }
        val target = expansionPlan ?: return false
        if (expansionFrom != idOf(spawn)) return false
        if (!worksiteSafe(listOf(target.spawnCell))) return false
        val body = builderBody()
        // спавн рожает из запаса всех спавнов рядом: с близнецом добытчики делят сдачу, и 850 в одном спавне — это ~1700
        // пула (v46 ждал вдвое дольше)
        if (poolOf(spawn).sumOf { energyOf(it) } < costOf(body)) return true
        val r = spawn.spawnCreep(body)
        println("spawn t=$t ${bodyText(body)} cost=${costOf(body)} energy=$energy why=expansion to (${target.spawnCell.x},${target.spawnCell.y}) err=${r.error}")
        if (r.error == null) { builderPending = true; expansion = target; expansionPlaced = false; expansionPlan = null; expansionPlanAt = -1000 }
        return true
    }

    /** Заказ для сейфа: пробойщик, пока стена цела; строитель — когда пролом готов или откроется раньше, чем
     *  строитель дойдёт (оставшиеся хиты стены / урон пробойщика против пути строителя). Возвращает, занят ли спавн. */
    private fun vaultOrder(t: Int, spawn: StructureSpawn, energy: Int): Boolean {
        val all = getObjects()
        // один сейф в работе за раз: начатый доводим, иначе — первый по очереди, до которого можно
        val started = vaults.firstOrNull { it.stage != "run" && (hasRole(it.breacherRole) || hasRole(it.builderRole)) }
        // карман, который он уже выпил или занял своей постройкой, — не сейф
        val candidates = if (started != null) listOf(started) else vaults.filter { v ->
            v.stage != "run" && v.containers.sumOf { p -> containerAt(p, all)?.let { energyOf(it) } ?: 0 } > 0 &&
                all.none { it.asDynamic().my == false && it is Structure && Pos(it.x, it.y) in v.interior }
        }
        for (v in candidates) {
            // заказывает база, ближайшая к сейфу: второй карман — у нашего дополнительного спавна
            // ближайшая — по пути, а не по прямой: для левого кармана прямая выбирала спавн сейфа (55,67) — 36 клеток
            // против 38 у (3,32), а путь идёт через другую половину карты
            val baseSpawns = bases.mapNotNull { b -> b.spawnId?.let { id -> getObjects().firstOrNull { idOf(it) == id } } }
            val cached = vaultFrom[v.index]?.takeIf { it.first == baseSpawns.size }?.second
            val fromId = cached ?: baseSpawns.minByOrNull { pathTicks(it, cell(v.spawnCell)) }?.let { idOf(it) }?.also { vaultFrom[v.index] = baseSpawns.size to it }
            val nearestBase = baseSpawns.firstOrNull { idOf(it) == fromId } ?: continue
            if (nearestBase.x != spawn.x || nearestBase.y != spawn.y) { if (started != null) return false; continue }
            chooseBreach(v, spawn)
            // его башня у сейфа (けろびー ставил передовую базу с башней в девяти клетках от нашего пролома, и пробойщик
            // со строителем легли под ней): пока она стоит, сейф — не стройка, а цель для армии
            val near = listOfNotNull(v.wall, v.spawnCell)
            if (all.any { it is StructureTower && it.asDynamic().my == false && near.any { p -> cheb(posOf(it), p) <= 12 } }) continue
            // место работ под его бойцами — не заказываем: v13 перекупал пробойщика M7A7 двадцать раз подряд, каждый шёл к
            // стене один и ложился под тремя M4R3H1 けろびー; армия сперва расчищает, потом сейф
            if (!worksiteSafe(listOfNotNull(v.outside, v.spawnCell))) continue
            if (v.stage == "breach" && !hasRole(v.breacherRole)) return order(t, spawn, energy, breacherBody(), v.breacherRole)
            if (hasRole(v.builderRole)) return false
            val wallLeft = wallObject(v, all)?.asDynamic()?.hits?.unsafeCast<Int>() ?: 0
            val breacher = getObjectsByPrototype(Creep::class).firstOrNull { roleOf[idOf(it)] == v.breacherRole }
            val w = v.wall
            val breakTicks = if (wallLeft <= 0) 0 else if (breacher == null || w == null || getRange(breacher, cell(w)) > 1) Int.MAX_VALUE
                else wallLeft / maxOf(1, liveParts(breacher, ATTACK) * ATTACK_POWER)
            val walk = pathTicks(spawn, cell(v.outside ?: v.spawnCell))
            if (breakTicks <= walk + vaultBuilderBody().size * CREEP_SPAWN_TIME) return order(t, spawn, energy, vaultBuilderBody(), v.builderRole)
            return false
        }
        return false
    }

    private fun order(t: Int, spawn: StructureSpawn, energy: Int, body: Array<BodyPartType>, role: String): Boolean {
        if (poolOf(spawn).sumOf { energyOf(it) } < costOf(body)) return true
        val r = spawn.spawnCreep(body)
        println("spawn t=$t ${bodyText(body)} cost=${costOf(body)} energy=$energy why=$role err=${r.error}")
        if (r.error == null) pendingRoles.add(role to bodyText(body))
        return true
    }

    /** Лекарь той же цены, что стрелок: MOVE на каждую часть и HEAL на остальное. */
    private fun healerBody(energy: Int): Array<BodyPartType> {
        val pair = (BODYPART_COST[MOVE] ?: 50) + (BODYPART_COST[HEAL] ?: 250)
        val k = minOf(MAX_CREEP_SIZE / 2, energy / pair)
        val out = ArrayList<BodyPartType>()
        repeat(k) { out.add(MOVE) }
        repeat(k) { out.add(HEAL) }
        val spare = (energy - k * pair) / (BODYPART_COST[MOVE] ?: 50)
        repeat(minOf(spare, k)) { out.add(0, MOVE) }
        return out.toTypedArray()
    }

    /** Кого рожать: стрелка или лекаря — прогоном всей нашей армии с кандидатом против его армии и того, что он родит
     *  за то же время, плюс его башни. Против армии без лечения лекарь ничего не решает — прогон выберет стрелка. */
    private var armyCache: Pair<List<SimUnit>, List<SimUnit>>? = null

    private fun chooseFighter(cap: Int): Array<BodyPartType> {
        val ranger = fighterBody(cap)
        val healer = healerBody(cap)
        val (ours, theirs) = armyCache ?: return ranger
        if (theirs.isEmpty()) return ranger
        val r = simulate(ours + SimUnit(ranger.map { it.asDynamic().unsafeCast<String>() }, ranger.size * 100), theirs)
        val h = simulate(ours + SimUnit(healer.map { it.asDynamic().unsafeCast<String>() }, healer.size * 100), theirs)
        val score = { x: SimResult -> (if (x.win) 1_000_000 else 0) + x.left - x.theirLeft }
        return if (score(h) > score(r)) healer else ranger
    }

    /** Спавны, чей запас берёт `spawnCreep` этого спавна: наши в `POOL_RANGE` (basic замерил: в 3 клетках
     *  считается, в 25 — нет; 20 из документации не проверено). */
    private fun mySpawnsById(id: String): StructureSpawn? =
        getObjectsByPrototype(StructureSpawn::class).firstOrNull { it.my == true && idOf(it) == id }

    private fun poolOf(spawn: StructureSpawn): List<StructureSpawn> =
        getObjectsByPrototype(StructureSpawn::class).filter { it.my == true && getRange(it, spawn) <= POOL_RANGE }

    private fun spawnFighter(t: Int, spawn: StructureSpawn, energy: Int, why: String) {
        val pool = poolOf(spawn)
        val poolEnergy = pool.sumOf { energyOf(it) }
        // потолок тела — только спавны пула, в которые кто-то сдаёт (добытчик рядом) или сам заказчик: близнец, до
        // которого не дотягивается ни один добытчик, копит лишь +1 в тик. v46–v53 ждали двойного тела за 2000, основной
        // спавн стоял полным, добытчику некуда было сдавать, и источник копался 9–63 % времени (против けろびー#19 —
        // 15–33 тыс. энергии за игру, 30–50 % всей нашей добычи)
        val feeders = getObjectsByPrototype(Creep::class).filter { it.my && !it.spawning && liveParts(it, WORK) > 0 }
        val fed = pool.filter { sp -> idOf(sp) == idOf(spawn) || feeders.any { getRange(it, sp) <= 1 } }
        val poolCap = fed.sumOf { it.store.getCapacity(RESOURCE_ENERGY) ?: SPAWN_ENERGY_CAPACITY }
        // под угрозой — тело одного спавна: защитник нужен сейчас, а не через 180 тиков накопления на двойное
        val cap = if (why == "threat") minOf(poolCap, SPAWN_ENERGY_CAPACITY) else poolCap
        val full = chooseFighter(cap)
        if (poolEnergy < costOf(full)) return
        val r = spawn.spawnCreep(full)
        println("spawn t=$t ${bodyText(full)} cost=${costOf(full)} energy=$energy pool=$poolEnergy/${pool.size} why=$why err=${r.error}")
    }

    // ---------- сила ----------

    private fun isCombat(c: Creep) = c.body.any { (it.type == RANGED_ATTACK || it.type == ATTACK || it.type == HEAL) && it.hits > 0 }

    private fun dpsOf(c: Creep) = liveParts(c, RANGED_ATTACK) * RANGED_ATTACK_POWER + liveParts(c, ATTACK) * ATTACK_POWER

    private fun healOf(c: Creep) = liveParts(c, HEAL) * HEAL_POWER

    /** Сколько урона и лечения в тик снимает единица нашего урона: выход крипа, делённый на урон, который гасит его
     *  последнюю рабочую часть (части гибнут спереди назад: у тела с оружием в хвосте выход живёт до смерти). «Лекари
     *  первыми» v39 били танков stachu3478 `t5m8r2h1` — 32 выхода на 1600 хитов и самолечение, 0,020 на единицу, — пока
     *  его M5R5 давал 0,056 и стрелял (в ничьей: 1980 выстрелов по танкам, 403 по M5R5). */
    private fun outputRate(types: List<String>, hits: Int, output: Int): Double {
        if (output <= 0) return 0.0
        val j = types.indexOfLast { it == "ranged_attack" || it == "attack" || it == "heal" }
        if (j < 0) return 0.0
        return output.toDouble() / maxOf(1, hits - 100 * (types.size - 1 - j))
    }

    private fun rateOf(c: Creep) = outputRate(typesOf(c), c.hits, dpsOf(c) + healOf(c))

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

    /** Его армия к нашему приходу: нынешние боевые крипы плюс рождённые за окно (не больше 300 тиков с его первого
     *  спавна), разнесённые на путь; Ланчестер по сумме. */
    /** Кого он родит за `ticks`: рождения за окно (не больше 300 тиков с его первого спавна), разнесённые на срок,
     *  телами из того же окна по кругу. */
    private fun projectedBirths(t: Int, ticks: Int, spawns: Int): List<SimUnit> {
        if (enemySpawnSeenAt < 0 || ticks <= 0) return emptyList()
        val window = minOf(300, t - enemySpawnSeenAt)
        val recent = if (window <= 0) emptyList() else enemyBirths.filter { it.first > t - window }
        if (recent.isEmpty()) {
            // рождений его бойцов ещё не видели, а спавны у него есть: каждый рожает полное тело раз в столько тиков,
            // сколько насыщенный источник с восстановлением спавна копит на вместимость спавна (~91). v22 ушёл одним
            // бойцом на 360-м против «силы 0» и потерял дом трём M4R3H1 けろびー#9 к 500-му
            val period = SPAWN_ENERGY_CAPACITY / (SOURCE_ENERGY_REGEN + 1)
            val k = kotlin.math.round(spawns.toDouble() * ticks / period).toInt()
            val types = fighterBody(SPAWN_ENERGY_CAPACITY).map { it.asDynamic().unsafeCast<String>() }
            return List(k) { SimUnit(types, types.size * 100) }
        }
        val k = kotlin.math.round(recent.size.toDouble() * ticks / window).toInt()
        return List(k) { i -> val types = recent[i % recent.size].second; SimUnit(types, types.size * 100) }
    }

    private fun calibStart(t: Int, kind: String, ours: List<Creep>, theirs: List<Creep>, r: SimResult, byId: Map<String, GameObject>) {
        calibEnd(t, "superseded", byId)
        calib = Calib(kind, t, ours.map { idOf(it) }.toSet(), theirs.map { idOf(it) }.toSet(),
            ours.sumOf { it.hits }, theirs.sumOf { it.hits }, r.keep, r.win)
    }

    private fun calibEnd(t: Int, why: String, byId: Map<String, GameObject>) {
        val c = calib ?: return
        calib = null
        val ourAlive = c.ours.mapNotNull { byId[it] as? Creep }
        val theirAlive = c.theirs.mapNotNull { byId[it] as? Creep }
        val keep = ourAlive.sumOf { it.hits }.toDouble() / c.ours0.coerceAtLeast(1)
        val theirKeep = theirAlive.sumOf { it.hits }.toDouble() / c.theirs0.coerceAtLeast(1)
        println("calib ${c.kind} t=${c.t0}..$t $why pred keep=${(c.predKeep * 100).toInt()}% win=${c.predWin} " +
            "actual keep=${(keep * 100).toInt()}% ours ${ourAlive.size}/${c.ours.size} theirs ${theirAlive.size}/${c.theirs.size} theirKeep=${(theirKeep * 100).toInt()}%")
    }

    // ---------- прогон боя ----------

    /** Боец в прогоне: типы частей спереди назад и хиты общим числом — движок держит части «сзади наперёд» (часть i
     *  жива, пока хиты больше 100 × (n−1−i)), поэтому урон снимает передние части первыми, а лечение возвращает их
     *  последними. Башня — боец без частей со своим уроном и 3000 хитов, её бьют последней. */
    private class SimUnit(val types: List<String>, var hits: Int, val fixedDps: Int = 0, val maxHits: Int = types.size * 100) {
        val tower get() = types.isEmpty()
        fun live(type: String): Int {
            var c = 0
            val n = types.size
            for (i in 0 until n) if (types[i] == type && hits > 100 * (n - 1 - i)) c++
            return c
        }
        fun dps(): Int = fixedDps + live("ranged_attack") * RANGED_ATTACK_POWER + live("attack") * ATTACK_POWER
        fun heal(): Int = live("heal") * HEAL_POWER
        fun copy() = SimUnit(types, hits, fixedDps, maxHits)
    }

    private class SimResult(val win: Boolean, val keep: Double, val left: Int, val theirLeft: Int, val ticks: Int)

    private fun typesOf(c: Creep): List<String> = c.body.map { it.type.asDynamic().unsafeCast<String>() }

    private fun simOf(c: Creep) = SimUnit(typesOf(c), c.hits)

    /** Башня как боец прогона: выстрел на дальности нашего стрелка у неё (4 клетки) раз в перезарядку. */
    private fun simTower(hits: Int) = SimUnit(emptyList(), hits, (towerShot(RANGED_RANGE + 1) / TOWER_COOLDOWN).toInt(), hits)

    /** Его спавн как цель прогона: без урона, хиты спавна плюс рампарт на его клетке. */
    private fun simSpawnOf(sp: GameObject, all: Array<GameObject>): SimUnit {
        val rampart = all.firstOrNull { it is StructureRampart && it.x == sp.x && it.y == sp.y }?.asDynamic()?.hits?.unsafeCast<Int>() ?: 0
        val hits = (sp.asDynamic().hits?.unsafeCast<Int>() ?: SPAWN_HITS) + rampart
        return SimUnit(emptyList(), hits, 0, hits)
    }

    /** Башня под рампартом бьётся вместе с ним: весь урон по ней сперва снимает рампарт. v20 считал башню бойцом на
     *  3000, волна из 13 шла на обещанные 55 % против двух башен под рампартами (13000 каждая) — вернулись трое. */
    /** Урон его башен по дороге группы к цели: путь — по карте опасности, как ходит волна; на каждой клетке — выстрел
     *  каждой башни, до которой клетка в досягаемости, на тики этой клетки (болото — 5 у тела 1:1). Прогон удара видит
     *  только башни в 10 клетках от цели, а башня (48,78) けろびー била наши волны к его второму спавну с 15–20 клеток:
     *  26900 урона за окна волн одной ничьей, ни одного выстрела в ответ. */
    private fun routeTowerDamage(t: Int, from: Position, to: GameObject, towers: List<StructureTower>): Int {
        if (towers.isEmpty()) return 0
        routeDamage[idOf(to)]?.let { (at, d) -> if (t - at < 25) return d }
        val o: dynamic = js("({})")
        danger?.let { o.costMatrix = it }
        o.plainCost = 1
        o.swampCost = 5
        val r = searchPath(from, SearchGoal(pos = to, range = RANGED_RANGE), o.unsafeCast<SearchPathOptions>())
        val falloff = TOWER_FALLOFF_RANGE.asDynamic().unsafeCast<Double>()
        var dmg = 0.0
        for (p in r.path) {
            val ticks = if (getTerrainAt(p) == TERRAIN_SWAMP) 5 else 1
            for (tw in towers) {
                val d = maxOf(kotlin.math.abs(p.x - tw.x), kotlin.math.abs(p.y - tw.y))
                if (d < falloff) dmg += towerShot(d) / TOWER_COOLDOWN * ticks
            }
        }
        routeDamage[idOf(to)] = t to dmg.toInt()
        return dmg.toInt()
    }

    /** Группа после урона по дороге: башня бьёт в одного, пока не убьёт, — снимаем с самых слабых целиком. */
    private fun preDamage(units: List<SimUnit>, dmg: Int): List<SimUnit> {
        var left = dmg
        val out = units.map { it.copy() }.sortedBy { it.hits }
        for (u in out) { if (left <= 0) break; val take = minOf(left, u.hits); u.hits -= take; left -= take }
        return out.filter { it.hits > 0 }
    }

    private fun simTowerOf(tw: StructureTower, all: Array<GameObject>): SimUnit {
        val rampart = all.firstOrNull { it is StructureRampart && it.x == tw.x && it.y == tw.y }?.asDynamic()?.hits?.unsafeCast<Int>() ?: 0
        return simTower((tw.hits ?: TOWER_HITS) + rampart)
    }

    /** Бой по тикам: обе стороны бьют одновременно всем уроном в одну цель (лекарей первыми, потом самого битого,
     *  башни последними; перебор урона уходит в следующую), лечение возвращает хиты самым битым. Лечение считается
     *  вплотную (12 за часть) для обеих сторон: строй лекарей у раненого — их и наша задача. */
    /** Снесём ли постройку с `preyHits` хитами, стреляя только по ней, пока нас бьют `theirs`: тик сноса или null. */
    private fun finishTicks(ours: List<SimUnit>, theirs: List<SimUnit>, preyHits: Int): Int? {
        val a = ours.map { it.copy() }.toMutableList()
        var prey = preyHits
        val db = theirs.sumOf { it.dps() }
        var t = 0
        while (a.isNotEmpty() && t < SIM_LIMIT) {
            t++
            prey -= a.sumOf { it.dps() }
            if (prey <= 0) return t
            var left = db
            for (u in a.sortedBy { it.hits }) { if (left <= 0) break; val take = minOf(left, u.hits); u.hits -= take; left -= take }
            a.removeAll { it.hits <= 0 }
            var heal = a.sumOf { it.heal() }
            for (u in a.filter { it.hits < it.maxHits }.sortedByDescending { it.maxHits - it.hits }) { if (heal <= 0) break; val add = minOf(heal, u.maxHits - u.hits); u.hits += add; heal -= add }
        }
        return null
    }

    private fun simulate(ours: List<SimUnit>, theirs: List<SimUnit>, limit: Int = SIM_LIMIT): SimResult {
        val a = ours.map { it.copy() }.toMutableList()
        val b = theirs.map { it.copy() }.toMutableList()
        val aStart = a.sumOf { it.hits }.coerceAtLeast(1)
        var t = 0
        fun strike(side: MutableList<SimUnit>, dmg: Int) {
            var left = dmg
            val order = side.sortedWith(compareBy<SimUnit> { if (it.tower) 1 else 0 }
                .thenByDescending { outputRate(it.types, it.hits, it.dps() + it.heal()) }.thenBy { it.hits })
            for (u in order) {
                if (left <= 0) break
                val take = minOf(left, u.hits)
                u.hits -= take
                left -= take
            }
        }
        fun mend(side: MutableList<SimUnit>, heal: Int) {
            var left = heal
            for (u in side.filter { !it.tower && it.hits > 0 && it.hits < it.maxHits }.sortedByDescending { it.maxHits - it.hits }) {
                if (left <= 0) break
                val add = minOf(left, u.maxHits - u.hits)
                u.hits += add
                left -= add
            }
        }
        while (t < limit && a.isNotEmpty() && b.isNotEmpty()) {
            t++
            val da = a.sumOf { it.dps() }
            val db = b.sumOf { it.dps() }
            val ha = a.sumOf { it.heal() }
            val hb = b.sumOf { it.heal() }
            strike(b, da)
            strike(a, db)
            a.removeAll { it.hits <= 0 }
            b.removeAll { it.hits <= 0 }
            mend(a, ha)
            mend(b, hb)
            if (da == 0 && db == 0) break
        }
        val left = a.sumOf { it.hits }
        return SimResult(b.isEmpty() && a.isNotEmpty(), left.toDouble() / aStart, left, b.sumOf { it.hits }, t)
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
        if (wave.isEmpty()) strikeTargetId = null
        calib?.let { c -> if (wave.isEmpty()) calibEnd(t, "wiped", byId) else if (t - c.t0 >= SIM_LIMIT) calibEnd(t, "timeout", byId) }
        val homeSpawns = homeSpawnObjects(byId)
        // сбор — у спавна, ближайшего к сопернику: туда сходятся бойцы всех баз
        val enemy = enemyStart
        val home: Position = homeSpawns.minByOrNull { if (enemy == null) 0 else getRange(it, cell(enemy)) }
            ?: bases.firstOrNull()?.let { cell(it.spawnCell) } ?: return
        val enemyObjects = all.filter { it.asDynamic().my == false && it !is Creep && it !is ConstructionSite }
        val homeGroup = fighters.filter { idOf(it) !in wave }
        val pending = pendingTowers(t, all, homeGroup)
        val enemyPower = power(enemyCombat) + towerPower(enemyTowers)
        // к нашему приходу у него будет больше: его производство за окно, помноженное на путь до его спавна. v10 ушёл
        // одним бойцом на 360-м против «силы 0», а к подходу у けろびー стояли M5R5 и башня
        val enemySpawnObjs = all.filter { it is StructureSpawn && it.asDynamic().my == false }
        val arrival = if (enemySpawnObjs.isEmpty()) 0 else enemySpawnObjs.minOf { pathTicks(home, it) }
        val fedTowers = enemyTowers.filter { energyOf(it) > 0 }
        // его ближний спавн — тоже в прогоне: боец без урона с хитами спавна и рампарта над ним. Волна побеждает,
        // только снеся его под огнём башен, а не перебив защитников
        val nearSpawn = enemySpawnObjs.minByOrNull { pathTicks(home, it) }
        val enemyAtArrival = enemyCombat.map { simOf(it) } + projectedBirths(t, arrival, enemySpawnObjs.size) +
            fedTowers.map { simTowerOf(it, all) } + List(pending) { simTower(TOWER_HITS) } + listOfNotNull(nearSpawn?.let { simSpawnOf(it, all) })
        armyCache = fighters.map { simOf(it) } to enemyAtArrival
        val lastCall = t > arenaInfo.ticksLimit - 600
        val myTowers = all.filter { it is StructureTower && it.asDynamic().my == true }.unsafeCast<List<StructureTower>>()
        // дом под угрозой, которую он сам (с башнями) по прогону не держит, — волна возвращается: v10 ушёл волной, а
        // поток его M3R3 перебил добытчиков, которых спавн рожал заново каждые 13 тиков
        // …и только если угроза бьёт: в досягаемости спавна или рабочего. v17 развернул волну из 14, шедшую на победу,
        // из-за четверых, круживших у базы вне выстрела; спавн под рампартом их ждёт
        val striking = threats.filter { e -> homeSpawns.any { getRange(e, it) <= RANGED_RANGE + 1 } || mine.any { liveParts(it, WORK) > 0 && getRange(e, it) <= RANGED_RANGE + 1 } }
        if (wave.isNotEmpty() && striking.isNotEmpty() && !lastCall) {
            val home0 = homeGroup.map { simOf(it) } + myTowers.filter { tw -> energyOf(tw) > 0 && threats.any { getRange(it, tw) <= TOWER_RANGE } }.map { simTowerOf(it, all) }
            val r = simulate(home0, threats.map { simOf(it) })
            if (!r.win) {
                println("recall t=$t wave=${wave.size} home=${homeGroup.size} vs threats=${threats.size} sim=${r.left}/${r.theirLeft}")
                wave.clear()
                calibEnd(t, "recall", byId)
            }
        }

        // волна делится: минимальный набор защитников (ближние к угрозам), при котором прогон дома с башнями угрозы
        // бьёт, остаётся; остальные — кандидаты в волну, и их прогон — против его армии без этих угроз. v31 досидел
        // ничью с 13 бойцами против его четырёх, башен и спавна: его одиночки кружили у наших баз, «угроза» не
        // снималась, а волна ждала тишины дома — за игру ни одного выхода
        val threatIds = threats.map { idOf(it) }.toSet()
        var defenders: List<Creep> = emptyList()
        if (threats.isNotEmpty() && !lastCall) {
            val towersNearThreats = myTowers.filter { tw -> energyOf(tw) > 0 && threats.any { getRange(it, tw) <= TOWER_RANGE } }.map { simTowerOf(it, all) }
            val byNearness = homeGroup.sortedBy { f -> threats.minOf { getRange(f, it) } }
            // больше защитников — не хуже, поэтому минимальный набор ищется двоичным поиском (~5 прогонов, а не по одному)
            val threatUnits = threats.map { simOf(it) }
            fun holds(k: Int) = simulate(byNearness.take(k).map { simOf(it) } + towersNearThreats, threatUnits).win
            var lo = 0
            var hi = byNearness.size
            if (!holds(hi)) lo = hi else while (lo < hi) { val mid = (lo + hi) / 2; if (holds(mid)) hi = mid else lo = mid + 1 }
            defenders = byNearness.take(lo)
        }
        val pushers = homeGroup.filter { it !in defenders }
        val enemyForPush = enemyCombat.filter { idOf(it) !in threatIds }.map { simOf(it) } + projectedBirths(t, arrival, enemySpawnObjs.size) +
            fedTowers.map { simTowerOf(it, all) } + List(pending) { simTower(TOWER_HITS) } + listOfNotNull(nearSpawn?.let { simSpawnOf(it, all) })
        // выход волны: прогон всего дома против его армии к нашему приходу (с тем, что он родит по дороге) и его
        // кормленых башен — и тех площадок башен, что достроятся к подходу (v5 лёг под достроившейся). Уже ушедшая волна
        // не держит дом: дом, который сам по прогону побеждает, выходит следом
        // последний призыв не ждёт тишины дома: v22 досидел ничью, потому что его харассеры держали угрозу до 5000-го
        // прибор: почему волна не выходит — раз в 100 тиков, пока дома есть бойцы и волны нет
        if (wave.isEmpty() && homeGroup.isNotEmpty() && t % 100 == 0) {
            val r0 = if (pushers.isEmpty()) null else simulate(pushers.map { simOf(it) }, enemyForPush)
            println("pushcheck t=$t home=${homeGroup.size} def=${defenders.size} pushers=${pushers.size} threats=${threats.size} " +
                "ours dps=${pushers.sumOf { dpsOf(it) }} heal=${pushers.sumOf { healOf(it) }} hits=${pushers.sumOf { it.hits }} | " +
                "his units=${enemyForPush.size} dps=${enemyForPush.sumOf { it.dps() }} heal=${enemyForPush.sumOf { it.heal() }} " +
                "hits=${enemyForPush.sumOf { it.hits }} (creeps ${enemyCombat.size - threatIds.size} births ${projectedBirths(t, arrival, enemySpawnObjs.size).size} " +
                "towers ${fedTowers.size}+$pending spawn ${nearSpawn?.let { simSpawnOf(it, all).hits }}) arrival=$arrival | " +
                "sim ${r0?.let { "win=${it.win} keep=${(it.keep * 100).toInt()}% ticks=${it.ticks} left=${it.left}/${it.theirLeft}" }}")
        }
        if (pushers.isNotEmpty() && (enemyObjects.isNotEmpty() || theirs.isNotEmpty())) {
            val r = simulate(pushers.map { simOf(it) }, enemyForPush)
            if ((r.win && r.keep >= PUSH_KEEP) || lastCall) {
                calibStart(t, "push", pushers, enemyCombat, r, byId)
                for (f in pushers) wave.add(idOf(f))
                strikeTargetId = null
                println("push t=$t wave=${wave.size} home=${pushers.size}+${defenders.size}def vs ${enemyForPush.size} (army ${enemyCombat.size} towers ${fedTowers.size}+$pending arrival=$arrival) " +
                    "sim keep=${(r.keep * 100).toInt()}% ticks=${r.ticks}${if (lastCall) " lastCall" else ""}")
            } else if (enemySpawnObjs.isNotEmpty()) {
                // удар по его ближнему спавну: против всей армии не выигрываем, но расширение у нас под боком стерегут
                // немногие. stachu3478 ставил спавн у центрального источника на нашей стороне, убил нашего строителя и
                // перерос нас по источникам (32 крипа против 11 к 3000-му) — а его передовые спавны стояли под двумя-
                // четырьмя стражами. Против них: стражи в 15 клетках, его доля будущих рождений на путь, башни рядом
                // цель — не ближний спавн, а тот, что прогон берёт с наибольшим запасом, с уроном башен по дороге:
                // v37 бил по (32,96) все девять ударов ничьей, проходя под башней у его первого спавна
                val center = pushers.minByOrNull { c -> pushers.sumOf { getRange(it, c) } }!!
                val ourDps = pushers.sumOf { dpsOf(it) }.coerceAtLeast(1)
                val start = pushers.sumOf { it.hits }.coerceAtLeast(1)
                var best: GameObject? = null
                var bestR: SimResult? = null
                var bestKeep = -1.0
                var bestFoes: List<Creep> = emptyList()
                var bestInfo = ""
                for (sp in enemySpawnObjs) {
                val toSp = pathTicks(home, sp)
                // защитники — все его бойцы, что дойдут до цели не позже нас (стоят к ней не дальше, чем мы): v18 бил
                // по «стражам в 15 клетках», слал одного-двух бойцов на спавн без стражей, и по дороге их ловила его
                // армия — 40–48 потерянных крипов за ничью
                // …и за время осады: хиты спавна с рампартом на наш урон — v28 бил группами по 1–7 «спавн без стражей»,
                // а его бродячая армия успевала к осаде (60 потерянных крипов в поражении от stachu3478)
                val siegeTicks = simSpawnOf(sp, all).hits / ourDps
                val reach = maxOf(STRIKE_GUARD_RANGE, getRange(home, sp) + siegeTicks)
                val guards = enemyCombat.filter { getRange(it, sp) <= reach && idOf(it) !in threatIds }
                val births = projectedBirths(t, toSp, enemySpawnObjs.size).let { b -> b.take((b.size + enemySpawnObjs.size - 1) / enemySpawnObjs.size) }
                val towersNear = enemyTowers.filter { energyOf(it) > 0 && getRange(it, sp) <= TOWER_FALLOFF_RANGE / 2 }
                // и те его бойцы, что уже рядом с нашей группой: их видит прогон отхода, и без них удар и отход v22
                // сменяли друг друга каждый тик (1568–1574)
                val nearUs = enemyCombat.filter { e -> getRange(e, center) <= LOCAL_RANGE && e !in guards && idOf(e) !in threatIds }
                val local = (guards + nearUs).map { simOf(it) } + births + towersNear.map { simTowerOf(it, all) } + simSpawnOf(sp, all)
                val route = routeTowerDamage(t, home, sp, fedTowers.filter { it !in towersNear })
                val rs = simulate(preDamage(pushers.map { simOf(it) }, route), local)
                val keep = rs.left.toDouble() / start
                if (rs.win && keep >= PUSH_KEEP && keep > bestKeep) {
                    best = sp; bestR = rs; bestKeep = keep; bestFoes = guards + nearUs
                    bestInfo = "guards=${guards.size} births=${births.size} towers=${towersNear.size} route=$route arrival=$toSp"
                }
                }
                val sp = best
                val rs = bestR
                if (sp != null && rs != null) {
                    calibStart(t, "strike", pushers, bestFoes, SimResult(rs.win, bestKeep, rs.left, rs.theirLeft, rs.ticks), byId)
                    for (f in pushers) wave.add(idOf(f))
                    strikeTargetId = idOf(sp)
                    println("strike t=$t wave=${wave.size} at spawn (${sp.x},${sp.y}) of ${enemySpawnObjs.size} $bestInfo " +
                        "sim keep=${(bestKeep * 100).toInt()}% (whole army: ${(r.keep * 100).toInt()}% win=${r.win})")
                }
            }
        }
        // добивание: v39 отошёл от последнего спавна stachu3478#14 при 350 хитах (4 тика огня) — прогон отхода видел
        // 58 тиков своей жизни и не видел спавна; потом волна прошла в 4 клетках от него на 150 хитах за танком — ничья
        finish = null
        var finishTicks = Int.MAX_VALUE
        for (sp in enemySpawnObjs) {
            val shooters = fighters.filter { f -> (liveParts(f, RANGED_ATTACK) > 0 && getRange(f, sp) <= RANGED_RANGE) || (liveParts(f, ATTACK) > 0 && getRange(f, sp) <= 1) }
            val dps = shooters.sumOf { dpsOf(it) }
            if (dps <= 0) continue
            val preyHits = simSpawnOf(sp, all).hits
            val foes = enemyCombat.filter { getRange(it, sp) <= LOCAL_RANGE }.map { simOf(it) } +
                fedTowers.filter { getRange(it, sp) <= TOWER_FALLOFF_RANGE / 2 }.map { simTowerOf(it, all) }
            val ticks = finishTicks(shooters.map { simOf(it) }, foes, preyHits) ?: continue
            if (ticks < finishTicks) { finishTicks = ticks; finish = sp }
        }
        finish?.let { if (t % 10 == 0) println("finish t=$t spawn (${it.x},${it.y}) ticks=$finishTicks") }
        val waveCreeps = fighters.filter { idOf(it) in wave }
        if (waveCreeps.isNotEmpty() && !lastCall) {
            // отход: на месте волна по прогону проигрывает тем, кто рядом, и башням, что её достают
            val center = waveCreeps.minByOrNull { c -> waveCreeps.sumOf { getRange(it, c) } }!!
            val local = enemyCombat.filter { getRange(it, center) <= LOCAL_RANGE }
            val localTowers = fedTowers.filter { getRange(it, center) <= TOWER_FALLOFF_RANGE / 2 }
            if (local.isNotEmpty() || localTowers.isNotEmpty()) {
                val r = simulate(waveCreeps.map { simOf(it) }, local.map { simOf(it) } + localTowers.map { simTowerOf(it, all) })
                if (!r.win && finish == null) {
                    println("retreat t=$t wave=${waveCreeps.size} vs local=${local.size}+${localTowers.size}tw sim=${r.left}/${r.theirLeft} ticks=${r.ticks}")
                    wave.clear()
                    calibEnd(t, "retreat", byId)
                }
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
            // удар идёт к выбранному спавну: по дороге — только бойцы рядом с головой, у цели — обычный выбор (башня
            // рядом со спавном бьётся первой)
            val locked = strikeTargetId?.let { id -> enemyObjects.firstOrNull { idOf(it) == id } }
            if (locked == null) strikeTargetId = null
            val goal = if (locked != null && getRange(front, locked) > LOCAL_RANGE)
                enemyCombat.filter { !it.spawning && getRange(front, it) <= LOCAL_RANGE }.minByOrNull { getRange(front, it) } ?: locked
                else pickGoal(front, enemyCombat, enemyObjects, theirs)
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
                    if (healAct(f, mine, waveNow)) continue
                    if (fleeMelee(f, enemyCombat)) continue
                    val d = toGoal[f] ?: 0
                    val fighting = theirs.any { isCombat(it) && getRange(f, it) <= RANGED_RANGE + 1 }
                    if (hold && d - frontD <= COHESION && !fighting) continue
                    // к цели напрямую — только когда она рядом (бой); остальной путь — в обход его бойцов и башен
                    if (d > keep) { if (d <= LOCAL_RANGE) f.moveTo(goal) else go(f, goal) }
                }
            } else for (f in waveNow) { shoot(f, theirs, enemyObjects); healAct(f, mine, waveNow) }
        }
        // дом: угрозу бьём всей кучей, если на месте сильнее (с башнями) или она уже бьёт спавн или рабочего;
        // иначе держимся у спавна — туда ей придётся подойти на выстрел. За пределы домашней зоны не гонимся
        val target = threats.minByOrNull { e -> homeSpawns.minOfOrNull { getRange(e, it) } ?: 0 }
        val anchor: Position = target?.let { tg -> homeSpawns.minByOrNull { getRange(tg, it) } } ?: home
        var engage = false
        if (target != null) {
            // бьём угрозу, только если прогон дома (с нашими кормлеными башнями, что её достают) против неё побеждает.
            // v11 бил и проигранную, если она «била спавн» — а спавн сейфа за стеной недосягаем, и бойцы из обоих
            // спавнов по одному шли в толпу из 36 у входа в сейф. Предела погони нет: угроза по определению в домашней
            // зоне (v9: зазор между пределом погони и снятием защиты держал защиту вечно)
            val local = threats.filter { getRange(it, target) <= LOCAL_RANGE }
            val ours = homeGroup.map { simOf(it) } + myTowers.filter { tw -> energyOf(tw) > 0 && getRange(tw, target) <= TOWER_RANGE }.map { simTowerOf(it, all) }
            engage = simulate(ours, local.map { simOf(it) }).win
        }
        // нарушители: его строитель или площадка спавна/башни на нашей территории — у наших спавнов или у «наших»
        // источников (к ним ближе наш спавн, чем его). Для угроз они невидимы (не боевые), и v17 спокойно смотрел, как
        // stachu3478 170 тиков строит спавн у нашего источника. Охотники — ближайшие бойцы дома, сколько нужно, чтобы
        // прогон против его бойцов рядом с нарушителем выиграл с запасом
        val hunters = HashSet<String>()
        var intruder: GameObject? = null
        if (threats.isEmpty() && homeGroup.isNotEmpty()) {
            intruder = intruders(all, theirs, homeSpawns).minByOrNull { i -> homeGroup.minOf { getRange(it, i) } }
            val it0 = intruder
            if (it0 != null) {
                // как и у удара: против всех его бойцов, что дойдут до нарушителя не позже нашего ближнего охотника
                val sorted = homeGroup.sortedBy { getRange(it, it0) }
                val reach = maxOf(LOCAL_RANGE, getRange(sorted.first(), it0))
                val guards = enemyCombat.filter { getRange(it, it0) <= reach }.map { simOf(it) }
                for (k in 1..sorted.size) {
                    val h = sorted.take(k)
                    val r = simulate(h.map { simOf(it) }, guards)
                    if (guards.isEmpty() || (r.win && r.keep >= PUSH_KEEP)) { h.forEach { hunters.add(idOf(it)) }; break }
                }
                if (hunters.isNotEmpty() && t % 10 == 0) println("hunt t=$t ${describe(it0)} hunters=${hunters.size} guards=${guards.size}")
                if (hunters.isEmpty()) intruder = null
            }
        }
        siteWatch(t, all, mine)
        val blocked = blockedCells(all)
        val reserved = reservedCells(all)
        // кого сопровождать: строитель расширения, пробойщик, строитель сейфа — пока он вне дома (дальше домашней зоны
        // от всех наших спавнов)
        val escort: Creep? = mine.filter { c ->
            !c.spawning && (idOf(c) == builderId || roleOf[idOf(c)]?.let { it.startsWith("breacher:") || it.startsWith("vaultBuilder:") } == true) &&
                homeSpawns.none { getRange(c, it) <= HOME_THREAT_RANGE }
        }.minByOrNull { c -> homeSpawns.minOfOrNull { getRange(c, it) } ?: 0 }
        // допуск сбора растёт с толпой: квадрат со стороной 2r+1 вмещает всех вдвое с запасом (v9: 35 бойцов у точки с
        // допуском 2 — 25 клеток — забили клетки у спавна, и новорождённому некуда было выйти)
        val rallySpread = maxOf(2, kotlin.math.ceil(kotlin.math.sqrt(2.0 * homeGroup.size) / 2).toInt())
        // конвой — только когда он по прогону бьёт его бойцов у сопровождаемого: бой без огня наших башен против его
        // шара v51–v52 проигрывали 39 из 48 раз (263 тыс. наших потерь против 112 тыс. его), и охрана строителей стоила
        // 9–19 тыс. энергии за ничью; нет перевеса — бойцы остаются дома под башнями, строитель идёт один
        val escortOk = escort != null && run {
            val foes = enemyCombat.filter { getRange(it, escort) <= LOCAL_RANGE }.map { simOf(it) }
            foes.isEmpty() || simulate(homeGroup.filter { idOf(it) !in wave }.map { simOf(it) }, foes).let { it.win && it.keep >= PUSH_KEEP }
        }
        for (f in homeGroup) {
            if (idOf(f) in wave) continue
            shoot(f, theirs, enemyObjects)
            if (healAct(f, mine, homeGroup)) continue
            if (fleeMelee(f, enemyCombat)) continue
            val prey = intruder
            if (prey != null && idOf(f) in hunters) {
                // строителя бьём с дальности стрелка; на площадку встаём — во внешнем мире чужую площадку давят ногой
                val want = if (prey is ConstructionSite) 0 else RANGED_RANGE - 1
                if (getRange(f, prey) > want) go(f, prey)
                continue
            }
            val onRampart = myRampartAt(Pos(f.x, f.y), all)
            if (engage && target != null) {
                if (getRange(f, target) > RANGED_RANGE) f.moveTo(target)
            } else if (target != null && onRampart && theirs.any { isCombat(it) && getRange(f, it) <= RANGED_RANGE + 2 }) {
                // на своём рампарте у боя — стоим: он принимает урон, а мы стреляем
            } else if (target != null && holdOnGuard(f, homeSpawns, all)) {
                // проигранная угроза: встали на свободный сторожевой рампарт своего ближайшего спавна
            } else if (target != null) {
                // проигранная угроза: держимся у СВОЕГО ближайшего спавна, в укрытии — внутри сейфа, если он ближе всех,
                // иначе в точке сбора этой базы — и копимся, пока прогон не скажет «бьём»
                val mySpawn = homeSpawns.minByOrNull { getRange(f, it) } ?: anchor
                val vv = vaults.firstOrNull { v -> v.spawnId != null && mySpawn.x == v.spawnCell.x && mySpawn.y == v.spawnCell.y }
                if (vv != null) {
                    if (Pos(f.x, f.y) !in vv.interior || Pos(f.x, f.y) in reserved) {
                        val spot = vv.interior.filter { it !in reserved && it !in vv.containers }.minByOrNull { cheb(it, Pos(f.x, f.y)) }
                        if (spot != null) go(f, cell(spot))
                    }
                } else {
                    val rally = rallyFor(mySpawn, reserved, blocked)
                    if (Pos(f.x, f.y) in reserved || getRange(f, cell(rally)) > rallySpread) go(f, cell(rally))
                }
            } else if (escort != null && escortOk) {
                // конвой: свободные бойцы идут со строителем или пробойщиком, ушедшим из дома, — угроза у него (рабочий)
                // включает защиту, и она бьётся по прогону. v29 потерял семь строителей расширения подряд (≈6000 энергии)
                // на пути к месту работ: проверка безопасности видела только тик заказа
                if (getRange(f, escort) > ESCORT_RANGE) go(f, escort)
            } else {
                // сбор — не у самого спавна, а в точке сбора: клетки у спавна, добытчиков, башни и площадок заняты
                // делом (выход для рождения, копка, стройка), и боец на них ломает базу
                val rally = rallyFor(anchor, reserved, blocked)
                if (Pos(f.x, f.y) in reserved || getRange(f, cell(rally)) > rallySpread) go(f, cell(rally))
            }
        }
    }

    /** Его небоевые крипы и площадки спавна/башни у наших спавнов (домашняя зона) или у «наших» источников. */
    private fun intruders(all: Array<GameObject>, theirs: List<Creep>, homeSpawns: List<GameObject>): List<GameObject> {
        val hisSpawns = all.filter { it is StructureSpawn && it.asDynamic().my == false }
        val ours = all.filter { it is Source }.filter { s ->
            val us = homeSpawns.minOfOrNull { getRange(it, s) } ?: Int.MAX_VALUE
            val him = hisSpawns.minOfOrNull { getRange(it, s) } ?: Int.MAX_VALUE
            us < him || bases.any { b -> b.sourceId == idOf(s) } || expansion?.sourceId == idOf(s)
        }
        fun inZone(o: GameObject) = homeSpawns.any { getRange(o, it) <= HOME_THREAT_RANGE + THREAT_RELEASE } ||
            ours.any { getRange(o, it) <= INTRUDER_SOURCE_RANGE }
        val towerCost = CONSTRUCTION_COST.asDynamic()["StructureTower"].unsafeCast<Int?>()
        val sites = all.filter { it is ConstructionSite && it.asDynamic().my == false }.unsafeCast<List<ConstructionSite>>()
            .filter { isSpawnSite(it) || it.progressTotal == towerCost }
        return theirs.filter { !isCombat(it) && inZone(it) } + sites.filter { inZone(it) }
    }

    /** Замер движка: исчезает ли его площадка, на которую встал наш крип. */
    private val siteStoodOn = HashMap<String, Int>()

    private fun siteWatch(t: Int, all: Array<GameObject>, mine: List<Creep>) {
        val sites = all.filter { it is ConstructionSite && it.asDynamic().my == false }
        for (s in sites) if (mine.any { it.x == s.x && it.y == s.y } && idOf(s) !in siteStoodOn) {
            siteStoodOn[idOf(s)] = t
            println("probe t=$t stand on his site ${describe(s)}")
        }
        val live = sites.map { idOf(it) }.toSet()
        for ((id, at) in siteStoodOn.entries.toList()) if (id !in live && at >= 0) {
            println("probe t=$t his site stood on at t=$at is gone")
            siteStoodOn[id] = -1
        }
    }

    /** Встать на свободный сторожевой рампарт у ближайшего своего спавна; `false`, если таких нет. */
    private fun holdOnGuard(f: Creep, homeSpawns: List<GameObject>, all: Array<GameObject>): Boolean {
        val sp = homeSpawns.minByOrNull { getRange(f, it) } ?: return false
        val b = bases.firstOrNull { it.spawnCell.x == sp.x && it.spawnCell.y == sp.y } ?: return false
        val me = Pos(f.x, f.y)
        val taken = getObjectsByPrototype(Creep::class).filter { it !== f }.map { posOf(it) }.toSet()
        val spot = guardCells(b, all).filter { myRampartAt(it, all) && (it == me || it !in taken) }.minByOrNull { cheb(it, me) } ?: return false
        if (spot != me) go(f, cell(spot))
        return true
    }

    /** Все наши спавны: базы и сейф. Один список для угроз, сбора и защиты — v9 считал его в двух местах, и во втором
     *  не было спавна сейфа: угроза у сейфа держала защиту, а бойцы стояли у первой базы до конца матча. */
    private fun homeSpawnObjects(byId: Map<String, GameObject>): List<GameObject> =
        bases.mapNotNull { b -> b.spawnId?.let { byId[it] } } + bases.mapNotNull { b -> b.twinId?.let { byId[it] } } +
            vaults.mapNotNull { v -> v.spawnId?.let { byId[it] } }

    /** Клетки, на которых боец не стоит: у спавна (выходы для рождения), клетки добытчиков, башни и наших площадок. */
    private fun reservedCells(all: Array<GameObject>): Set<Pos> {
        val out = HashSet<Pos>()
        for (b in bases) {
            for (x in b.spawnCell.x - 1..b.spawnCell.x + 1) for (y in b.spawnCell.y - 1..b.spawnCell.y + 1) out.add(Pos(x, y))
            out.addAll(b.slots)
            b.towerCell?.let { out.add(it) }
            b.twinCell?.let { tc -> for (x in tc.x - 1..tc.x + 1) for (y in tc.y - 1..tc.y + 1) out.add(Pos(x, y)) }
        }
        for (v in vaults) {
            if (v.stage == "run" || v.wall != null) {
                for (x in v.spawnCell.x - 1..v.spawnCell.x + 1) for (y in v.spawnCell.y - 1..v.spawnCell.y + 1) out.add(Pos(x, y))
                v.towerCell?.let { out.add(it) }
            }
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
            if (r < 4 || c in reserved || !walkable(c, blocked) || vaults.any { it.interior.contains(c) }) continue
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
        enemyCombat.filter { !it.spawning && getRange(f, it) <= LOCAL_RANGE }.minByOrNull { getRange(f, it) }?.let { return it }
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
    private fun shoot(f: Creep, theirsAll: List<Creep>, enemyObjects: List<GameObject>): Boolean {
        // добивание: его спавн, который бойцы рядом снесут раньше, чем их перебьют, — первым
        val fin = finish
        if (fin != null && liveParts(f, RANGED_ATTACK) > 0 && getRange(f, fin) <= RANGED_RANGE) { f.rangedAttack(fin); return true }
        if (fin != null && liveParts(f, ATTACK) > 0 && getRange(f, fin) <= 1) { f.attack(fin); return true }
        // рождающийся крип неуязвим, пока не выйдет со спавна: выстрел в него пропадает (оператор 27.09.2026)
        val theirs = theirsAll.filter { !it.spawning }
        // мили (пробойщик после пролома): вплотную — крип, иначе постройка
        if (liveParts(f, ATTACK) > 0) {
            val adj = theirs.filter { getRange(f, it) <= 1 }.minByOrNull { it.hits }
                ?: enemyObjects.filter { it is Structure && getRange(f, it) <= 1 }.firstOrNull()
            if (adj != null) { f.attack(adj); return true }
        }
        if (liveParts(f, RANGED_ATTACK) == 0) return false
        val inRange = theirs.filter { getRange(f, it) <= RANGED_RANGE }
        // массовый — размазанный урон, его отлечивают; при лекарях рядом бьём в одного, как считает прогон
        val healers = inRange.any { healOf(it) > 0 }
        val mass = inRange.sumOf { when (getRange(f, it)) { 0, 1 -> 10; 2 -> 4; else -> 1 } }
        if (!healers && mass > 10) { f.rangedMassAttack(); return true }
        // порядок целей — как в прогоне: лекари первыми, затем самые битые боевые, затем остальные
        val target = inRange.sortedWith(compareBy<Creep> { if (isCombat(it)) 0 else 1 }.thenByDescending { rateOf(it) }.thenBy { it.hits }).firstOrNull()
        if (target != null) { f.rangedAttack(target); return true }
        val st = enemyObjects.filter { it is Structure && getRange(f, it) <= RANGED_RANGE }
            .sortedWith(compareBy<GameObject> { if (it is StructureTower) 0 else if (it is StructureRampart) 1 else if (it is StructureSpawn) 2 else 3 })
            .firstOrNull()
        if (st != null) { f.rangedAttack(st); return true }
        return false
    }

    /** Лекарь: лечит самого битого своего вплотную (12 за часть) или издали (4), идёт к раненому своей группы, а без
     *  раненых держится за ближайшим стрелком группы. Возвращает, распорядился ли он ходом (чистый лекарь — всегда). */
    private fun healAct(f: Creep, mine: List<Creep>, group: List<Creep>): Boolean {
        if (liveParts(f, HEAL) == 0) return false
        val hurt = mine.filter { !it.spawning && it.hits < it.hitsMax && getRange(f, it) <= RANGED_RANGE }
            .sortedWith(compareBy<Creep> { if (getRange(f, it) <= 1) 0 else 1 }.thenByDescending { it.hitsMax - it.hits }).firstOrNull()
        if (hurt != null) { if (getRange(f, hurt) <= 1) f.heal(hurt) else f.rangedHeal(hurt) }
        if (dpsOf(f) > 0) return false
        val wounded = group.filter { it !== f && it.hits < it.hitsMax }.maxByOrNull { it.hitsMax - it.hits }
        val lead = wounded ?: group.filter { it !== f && dpsOf(it) > 0 }.minByOrNull { getRange(f, it) }
        if (lead != null && getRange(f, lead) > 1) go(f, lead)
        return true
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
        println("tuning: keep=$PUSH_KEEP simLimit=$SIM_LIMIT threatRatio=$PUSH_RATIO homeThreat=$HOME_THREAT_RANGE workerThreat=$WORKER_THREAT_RANGE " +
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
        println("t=$t mine=${mine.size} [${mine.joinToString(" ") { "${bodyOf(it)}@${it.x},${it.y}" }}] carried=$carried wave=${wave.size} cpu=${getCpuTime() / 1_000_000}ms " +
            "spawns=${mySpawns.joinToString(" ") { describe(it) }} sites=${all.filter { it is ConstructionSite && it.asDynamic().my == true }.joinToString(" ") { describe(it) }}")
        println("sources t=$t " + sources.joinToString(" ") { "(${it.x},${it.y})${it.energy}" } + " bases=" +
            bases.joinToString(" ") { b -> "src${b.sourceId}:work=${homeWork(b, mine)}" })
        val enemies = all.filter { it.asDynamic().my == false && it !is Creep }
        println("enemy t=$t creeps=${theirs.size} [${theirs.joinToString(" ") { "${bodyOf(it)}@${it.x},${it.y}" }}] " +
            "objects=${enemies.joinToString(" ") { describe(it) }}")
    }
}
