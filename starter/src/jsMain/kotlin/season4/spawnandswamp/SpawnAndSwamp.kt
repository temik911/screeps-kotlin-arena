package season4.spawnandswamp

import screeps.api.ATTACK
import screeps.api.ATTACK_POWER
import screeps.api.BODYPART_COST
import screeps.api.BUILD_POWER
import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.CARRY_CAPACITY
import screeps.api.CONSTRUCTION_COST
import screeps.api.ConstructionSite
import screeps.api.CostMatrix
import screeps.api.CREEP_SPAWN_TIME
import screeps.api.Creep
import screeps.api.HEAL
import screeps.api.HEAL_POWER
import screeps.api.MAX_CREEP_SIZE
import screeps.api.MOVE
import screeps.api.Position
import screeps.api.RANGED_ATTACK
import screeps.api.RANGED_ATTACK_POWER
import screeps.api.RANGED_HEAL_POWER
import screeps.api.RESOURCE_ENERGY
import screeps.api.Resource
import screeps.api.SPAWN_ENERGY_CAPACITY
import screeps.api.SPAWN_HITS
import screeps.api.SearchGoal
import screeps.api.SearchPathOptions
import screeps.api.TERRAIN_SWAMP
import screeps.api.TERRAIN_WALL
import screeps.api.TOUGH
import screeps.api.TOWER_CAPACITY
import screeps.api.TOWER_COOLDOWN
import screeps.api.TOWER_ENERGY_COST
import screeps.api.TOWER_FALLOFF
import screeps.api.TOWER_FALLOFF_RANGE
import screeps.api.TOWER_HITS
import screeps.api.TOWER_OPTIMAL_RANGE
import screeps.api.TOWER_POWER_ATTACK
import screeps.api.TOWER_POWER_HEAL
import screeps.api.TOWER_RANGE
import screeps.api.WALL_HITS
import screeps.api.WORK
import screeps.api.arenaInfo
import screeps.api.get
import screeps.api.getObjectsByPrototype
import screeps.api.getRange
import screeps.api.getTerrainAt
import screeps.api.getTicks
import screeps.api.searchPath
import screeps.api.structures.StructureContainer
import screeps.api.structures.StructureExtension
import screeps.api.structures.StructureRampart
import screeps.api.structures.StructureSpawn
import screeps.api.structures.StructureTower
import screeps.api.structures.StructureWall
import sourcemaps.runWithSourceMapSupport
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

@OptIn(ExperimentalJsExport::class)
@JsExport
fun loop() {
    try {
        runWithSourceMapSupport {
            SpawnAndSwamp.tick()
        }
    } catch (t: Throwable) {
        // страховка: даже если source-map-обработчик упадёт, логируем ошибку и не роняем тик
        println("loop error: ${t.message}")
        println(t.stackTraceToString())
    }
}

/**
 * Season 4 «Spawn and Swamp» (basic).
 *
 * Правила (замерены 02.09.2026): карта 100×100 со СЛУЧАЙНЫМ рельефом (треть болота, треть стен);
 * спавн стартует с 1000 энергии и регенерирует 1/тик; по углам постоянные контейнеры, по карте каждые
 * 50 тиков появляется пара временных на 99 тиков; победа — только снос чужого спавна (3000 HP),
 * 2000 тиков — ничья.
 *
 * Две оси решения, обе считаются из состояния, а не подбираются под карту:
 *  - экономика: узкое место — не энергия на земле (её больше, чем спавн переварит), а ДОСТАВКА и
 *    пропускная способность спавна. Флот хаулеров считается от притока, который спавн способен
 *    превратить в бойцов; рейсы — по реальному пути (пустой CARRY усталости не даёт, гружёный по
 *    болоту ползёт);
 *  - армия: ranged+heal отряд обороняет спавн и дороги хаулеров, а в наступление идёт ВОЛНАМИ по
 *    перевесу сил или «последним звонком» до ничьей; внутри волны авангард ждёт отставших.
 */
object SpawnAndSwamp {

    // ---------- боевые константы ----------
    private const val RANGED_RANGE = 3
    private const val HEAL_RANGE = 3
    private const val MELEE_KEEP_RANGE = 2
    private const val MELEE_KITE_DISCOUNT = 0.1

    /** Перевес, при котором отряд переходит в наступление, и порог возврата в оборону (гистерезис). */
    private const val PUSH_RATIO = 1.3
    private const val PUSH_RELEASE_RATIO = 0.9

    /** Минимум бойцов в волне — одиночка под фокусом умирает, не дойдя. */
    private const val PUSH_MIN_FIGHTERS = 2

    /** Запас тиков к «последнему звонку» (марш + снос спавна) — бой в пути, кайтеры, усталость. */
    private const val LATE_MARGIN = 60

    /** Радиус тревоги у спавна (в тиках пути врага): боевой враг ближе — спавним бойца немедленно. */
    private const val SPAWN_ALARM_TICKS = 40

    /** Перевес видимой армии врага, при котором боец в очереди спавна важнее хаулера. */
    private const val DEFEND_MARGIN = 1.2

    /** Врагов-бойцов ближе этого (Чебышев) к точке энергии считаем её опасной для хаулера. */
    private const val SITE_DANGER_RANGE = 6

    /** Зазор волны в тиках ХОДА НАПАРНИКА (поле потока × его период, см. plainPeriod): авангард ждёт
     *  отставших своей волны, пока они позади больше, чем на COHESION_GAP (больше одного болотного шага
     *  в 5 тиков — иначе соседи по болоту «отстают» друг от друга и волна стоит), но не ждёт
     *  «пропавших» (застрявших, убитых) — дальше COHESION_GAP_MAX волна идёт без них. Отставший
     *  В ПРЕДЕЛАХ дистанции стрельбы отставшим не считается: рядом движение разрулят свап и
     *  обтекание, а ожидание давало вечный обмен местами. */
    private const val COHESION_GAP = 8
    /** Дальше этого отставший считается потерянным (застрял, ушёл другим путём): 100 тиков — двадцать
     *  клеток болота; прежние 40 бросали напарника за восемь болотных клеток. */
    private const val COHESION_GAP_MAX = 100
    /** Зазор строя, когда боевой враг в досягаемости (ENGAGE_RANGE + RANGED_RANGE): напарник может
     *  отставать не больше чем на столько тиков — иначе передний входит в размен один. Матч 7: f32
     *  обогнал полускоростного f28 на девять клеток и дважды дрался с M3R3 в одиночку (−240, −470). */
    private const val ENGAGE_COHESION_TICKS = 2

    /** Пост обороны: дистанция от спавна, на которой держится отряд (не вплотную — там выход
     *  новорождённых и слот сдающих хаулеров). */
    private const val HOME_STANDOFF = 3

    /** Радиус, в котором боец при локальном перевесе сворачивает на встречного боевого врага:
     *  враг на дистанции 4-5 иначе не под огнём и не цель — отряд стоит перед ним. */
    private const val ENGAGE_RANGE = 8

    /** Дистанция сближения при перевесе: 2, а не 3. На 3 стрельба одиночная и слабая массовая (0.1),
     *  враг шагает на 3 к нашему переднему и фокусит его, пока остальные вне дистанции. На 2 массовая
     *  атака бьёт 0.4 по всем рядом, а отступающий на шаг враг всё ещё под огнём. */
    private const val CLOSE_STANDOFF = 2

    /** Окно оценки ПРОИЗВОДСТВА врага (тиков): мощь боевых крипов, впервые увиденных за окно, делённая
     *  на окно. Прежний «рост» мерил остаток (мощь сейчас минус сто тиков назад): мы убиваем M3R3 по
     *  мере прихода, остаток не растёт, и постура прыгала PUSH→DEFEND четыре раза за 300 тиков
     *  (матч 7), хотя спавн врага рождал бойца каждые ~60 тиков. Наступление считается против того,
     *  что он РОДИТ за наш марш, плюс того, что стоит у него дома. */
    private const val PRODUCTION_WINDOW = 300
    /** Короче этого окно не сжимаем: по одному рождению темп не оценить. */
    private const val PRODUCTION_MIN_SPAN = 100

    // веса оценки клетки (скопированы из spawn-strike, где обкатаны)
    private const val PAIR_W_DIST = 10.0
    private const val PAIR_W_DAMAGE = 0.3
    private const val PAIR_W_INFLUENCE = 0.1
    private const val PAIR_W_OUTGOING = 30.0
    private const val PAIR_W_OUTGOING_BREACH = 5.0
    private const val PAIR_W_MELEE = 50.0
    private const val AGGRO_MELEE_FACTOR = 0.3
    private const val PAIR_W_SPREAD = 4.0
    private const val PAIR_W_SWAMP = 40.0
    private const val SEPARATION_RADIUS = 1
    private const val CROWD_COST = 3

    /** Приоритеты TrafficManager: боец толкает любого хаулера; ЕДУЩИЙ хаулер толкает СТОЯЩЕГО
     *  (припаркованный без интента иначе намертво перекрывает подход к спавну). */
    private const val FIGHTER_PRIORITY = 3
    /** Гружёный хаулер выше пустого: у спавна толпа праздных, и сдающий 8 тиков бодал паркующегося
     *  с тем же приоритетом (матч 6, 02.09). Праздный не просит шага и уступает любому. */
    private const val HAULER_LOADED_PRIORITY = 2
    private const val HAULER_PRIORITY = 1
    /** Кольцо парковки пустых хаулеров: на 2 (16 клеток) шестнадцать хаулеров не помещались и
     *  толкались с сдающими; на 3 — 24 клетки, и соседи спавна свободны. */
    private const val PARK_RANGE = 3

    // ---------- экономика ----------
    /** Блок хаулера [CARRY, MOVE]: 1:1 — с грузом по равнине 1 клетка/тик, по болоту 5 тиков/шаг;
     *  пустой идёт везде 1/тик (пустой CARRY усталости не даёт). Полный хаулер — 5 блоков: 250 ёмкости,
     *  четверть спавна за рейс, 30 тиков на рождение. Первые хаулеры — что по карману, но не меньше 2 блоков. */
    private const val HAULER_BLOCKS_MAX = 5
    private const val HAULER_BLOCKS_MIN = 2

    /** Предохранитель по трафику и CPU, не экономический порог: сколько хаулеров вообще допускаем. */
    private const val MAX_HAULERS = 16

    /** Хвост распада контейнера, который не берём: доехать и успеть забрать надо с запасом. */
    private const val DECAY_MARGIN = 3

    /** Цена клетки стены в поиске пролома: сначала меньше стен, при равном числе — слабее. */
    private const val WALL_STEP_COST = 1000

    /** Горизонт прогноза притока: рейс считаем по ближайшим точкам, чьей энергии хватает флоту на
     *  столько кругов — дальние углы в счёт не идут, пока рядом есть что брать. */
    private const val FLEET_ROUNDS = 4

    /** Хедж между экономикой и армией: пока приток ниже цели, хаулеры могут опережать бойцов по
     *  потраченной энергии не больше чем на один полный спавн (стартовую тысячу). Чистая «экономика
     *  сначала» на карте с рейсами по 100 тиков окупается лишь к концу игры (стенд: первый полный
     *  боец на 1250-м тике), чистый раш проигрывает тому, кто вырос; равный делёж — минимум худшего
     *  случая, пока о противнике ничего не известно. Видимая армия врага перебивает делёж (fighterFirst). */
    private val HAULER_LEAD: Int get() = SPAWN_ENERGY_CAPACITY

    // ---------- отладка ----------
    private const val DEBUG_LOG = true
    private const val DEBUG_MAP = true
    private const val DEBUG_VISUALS = true
    private const val LOG_EVERY = 10

    private val DIRECTIONS = listOf(
        0 to 0, -1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1,
    )

    private var greeted = false
    private var mapLogged = false
    private var pushing = false

    /** Волна в поле, а осада по фронту не сходится: волна держит кромку башни и ждёт подкрепления (см. newPushing). */
    private var siegeHold = false
    private var lastPushReason = ""

    /** id бойца -> номер волны, с которой он ушёл в наступление. Нет в карте — стоит на посту. */
    private val wave = HashMap<String, Int>()
    private var waveCounter = 0

    /** Рождение боевого крипа врага: тик первого появления, мощь, хиты и урон одиночки. */
    private class Birth(val tick: Int, val power: Double, val hits: Int, val dps: Double, val melee: Double)

    /** Рождения за PRODUCTION_WINDOW — темп производства врага и «типичный» его боец. */
    private val enemyBirths = ArrayDeque<Birth>()
    private val enemySeen = HashSet<String>()
    private var firstCombatSeen = -1

    /** Боевые враги, идущие к нам (темп сближения > 0 или новые): их волна встречает в поле группами,
     *  остальные («стоящие») ждут её дома. Заполняется в enemyArrivalTicks. */
    private val approachingIds = HashSet<String>()

    /** id врага -> оценка тиков до нашего спавна (см. enemyArrivalTicks); не идущие — Int.MAX_VALUE / 2. */
    private val arrivalById = HashMap<String, Int>()

    /** Наш спавн в этом тике — мили врага вплотную к нему бьёт в полную силу (см. meleeFactor). */
    private var homeSpawnPos: Position? = null

    /** Пик мощи набега (самая сильная идущая к нам группа) за PRODUCTION_WINDOW — под него строится
     *  домашний мили-гарнизон, когда противник сам мили (см. guardNeeded). */
    private var raidPeak = 0.0
    private var raidPeakTick = -1
    private var guardNeeded = false

    /** Окно оценки сближения врага: по двум тикам темп не оценить, по двадцати — уже да. */
    private const val APPROACH_WINDOW = 20

    /** id врага -> история (тик, тики пути до нашего спавна) за APPROACH_WINDOW — темп сближения. */
    private val approachHistory = HashMap<String, ArrayDeque<Pair<Int, Int>>>()

    /** id врага -> его клетка в ПРОШЛОМ тике: отступает ли он от мили (см. retreating). */
    private val enemyPrevCell = HashMap<String, Int>()

    /** id площадки башни врага -> (тик первого наблюдения, прогресс тогда) — темп стройки. */
    private val siteSeen = HashMap<String, Pair<Int, Int>>()

    /** Гистерезис решений «охотимся на угрозу на нашей половине» (группой) и локальной агрессии
     *  (на бойца): пороговое решение без памяти дрожит на границе радиуса — два бойца 300 тиков
     *  менялись местами на кромке, один шаг «в бой», другой «домой» (стенд 02.09). */
    private var huntingThreat = false
    private val aggressiveIds = HashSet<String>()

    /** Дерёмся ли с врагом у дома (см. homeFight в runFighters): спавн под огнём, враг у ворот или
     *  гарнизон целиком сильнее. Иначе — пост отрядом и ждём подкрепления, а не по одному навстречу. */
    private var homeFight = false
    private var homeMode = "-"

    /** Страховка от НЕВИДИМОГО урона: хиты и клетка бойца в прошлом тике; ghostHit — сколько снято
     *  сверх того, что объясняют видимые враги и башни. Матч 11: башни в модели не было, и четыре
     *  бойца умерли «ниоткуда» с flee=false; всё, чего модель не знает, должно хотя бы гнать прочь. */
    private val lastHits = HashMap<String, Int>()
    private val lastCell = HashMap<String, Int>()
    private val ghostLogged = HashMap<String, Int>()

    /** Стрелки врага прошлого тика — клетка и урон: выстрел объясняется по ним, а не по нынешним, потому
     *  что убитый в тот же тик стрелок из живых уже выбыл, а его последний выстрел — нет (стенд harass). */
    private class Shooter(val cell: Int, val ranged: Double, val melee: Double)
    private var prevShooters: List<Shooter> = emptyList()

    /** План пролома к запертому контейнеру: кэш по числу живых стен (стена умерла — пересчёт). */
    private var breachCache: BreachPlan? = null
    private var breachWallCount = -1
    private var breachLogged = false

    /** Запертый контейнер и стены на кратчайшем к нему проходе, от спавна к контейнеру; steps — шаги
     *  пустого крипа от спавна до контейнера по этому проходу, loadedTicks — тики гружёного хаулера
     *  обратно (болото SWAMP_COST): рейс через пролом, пока поле loadedToSpawn контейнера не видит. */
    private class BreachPlan(val container: StructureContainer, val walls: List<StructureWall>, val steps: Int, val loadedTicks: Int) {
        val totalHits: Int get() = walls.sumOf { it.hits ?: WALL_HITS }
        fun current(): StructureWall? = walls.firstOrNull { it.exists && (it.hits ?: 0) > 0 }
        /** Рейс хаулера от спавна к контейнеру пролома и обратно (как tripTicks). */
        val trip: Int get() = steps + loadedTicks + 2
    }

    /** Двоичная куча (cost, cell) для Дейкстры с большими ценами стен. */
    private class MinHeap {
        private var keys = IntArray(256)
        private var vals = IntArray(256)
        var size = 0
        fun push(k: Int, v: Int) {
            if (size == keys.size) { keys = keys.copyOf(size * 2); vals = vals.copyOf(size * 2) }
            var i = size++
            keys[i] = k; vals[i] = v
            while (i > 0) {
                val p = (i - 1) / 2
                if (keys[p] <= keys[i]) break
                swap(i, p); i = p
            }
        }
        fun popKey(): Int = keys[0]
        fun popVal(): Int = vals[0]
        fun pop() {
            size--
            keys[0] = keys[size]; vals[0] = vals[size]
            var i = 0
            while (true) {
                val l = 2 * i + 1; val r = l + 1
                var m = i
                if (l < size && keys[l] < keys[m]) m = l
                if (r < size && keys[r] < keys[m]) m = r
                if (m == i) break
                swap(i, m); i = m
            }
        }
        private fun swap(a: Int, b: Int) {
            val k = keys[a]; keys[a] = keys[b]; keys[b] = k
            val v = vals[a]; vals[a] = vals[b]; vals[b] = v
        }
    }

    /** id хаулера -> id точки энергии, которую он взял (липкое назначение). */
    private val haulerSite = HashMap<String, String>()

    /** Подпись набора точек энергии на прошлом тике — чтобы печатать только появление/исчезновение. */
    private var lastSitesKey = ""

    /** Сколько энергии ушло на хаулеров и на бойцов — для хеджа HAULER_LEAD. */
    private var spentHaulers = 0
    private var spentFighters = 0

    /** Замер регена спавна (первые 100 тиков). */
    private var lastSpawnEnergy = -1
    private var regenSamples = 0
    private var regenSum = 0
    private var deliveringLastTick = false

    // ---------- кэши на тик ----------
    /** id точки -> поле в шагах до неё (пустой хаулер). */
    private val siteStepsCache = HashMap<String, IntArray>()

    /** упакованная клетка цели -> поле потока к ней (гружёный/боец, болото ×5). */
    private val flowCache = HashMap<Int, IntArray>()

    // ---------- модель ----------

    /** Точка энергии: контейнер или куча на земле. */
    private class EnergySite(
        val id: String,
        val pos: Position,
        val energy: Int,
        val container: StructureContainer?,
        val resource: Resource?,
        val ticksToDecay: Int?,
        val ours: Boolean,
        val myTicks: Int,      // ход ГРУЖЁНОГО от точки к нашему спавну (болото ×5)
        val enemyTicks: Int,   // то же для врага к его спавну
        val safe: Boolean,
    )

    /** Состояние тика, посчитанное один раз. */
    /** Башня врага в этом тике: объект и «кормится ли» — выстрел есть в ней самой или носильщик с
     *  энергией в кулдауне хода от неё (см. towerFed). Некормленная башня не стреляет и не считается. */
    private class TowerInfo(val pos: Position, val fed: Boolean, val cooldown: Int, val obj: StructureTower? = null)

    /** Строящаяся башня врага: площадка и через сколько тиков достроится — по наблюдаемому темпу, а
     *  пока темпа нет, по WORK строителей рядом. Для симуляции осады башня, которая встанет до конца
     *  осады, — башня: волна ушла при площадке 945/1250 и была отозвана через 35 тиков, когда башня
     *  встала (матч 13); ждать первого выстрела, чтобы поверить в башню, — ошибка матча 11. */
    private class PendingTower(val info: TowerInfo, val eta: Int)

    private class Ctx(
        val mySpawn: StructureSpawn,
        val enemySpawn: StructureSpawn?,
        val myCreeps: List<Creep>,
        val active: List<Creep>,
        val haulers: List<Creep>,
        val fighters: List<Creep>,
        val enemyCreeps: List<Creep>,
        val combatEnemies: List<Creep>,
        val blocked: List<Position>,
        val blockedForEnemy: List<Position>,
        val dangerMatrix: CostMatrix,
        val loadedToSpawn: IntArray,  // гружёный к нашему спавну (болото ×5), наша проходимость
        val stepsToSpawn: IntArray,   // пустой к нашему спавну, шаги
        val enemyApproach: IntArray,  // враг к нашему спавну по ЕГО проходимости
        val sites: List<EnergySite>,
        val enemyTowers: List<TowerInfo>,
        val ramparts: List<StructureRampart>,
        val pendingEnemies: List<Creep>,   // боевые крипы врага, ещё рождающиеся в его спавне (разведка)
        val pendingTowers: List<PendingTower>, // площадки башен врага с оценкой достройки
    )

    fun tick() {
        val mySpawn = getObjectsByPrototype(StructureSpawn::class).firstOrNull { it.my == true } ?: return
        val enemySpawn = getObjectsByPrototype(StructureSpawn::class).firstOrNull { it.my == false && it.exists }
        siteStepsCache.clear()
        flowCache.clear()

        if (!greeted) {
            greeted = true
            println(
                "hello season4 spawn-and-swamp: ${arenaInfo.season} - ${arenaInfo.name} level=${arenaInfo.level} " +
                    "ticksLimit=${arenaInfo.ticksLimit} cpu=${arenaInfo.cpuTimeLimit}/${arenaInfo.cpuTimeLimitFirstTick}"
            )
            println(
                "constants: SPAWN_HITS=$SPAWN_HITS SPAWN_CAP=$SPAWN_ENERGY_CAPACITY CARRY=$CARRY_CAPACITY " +
                    "SPAWN_TIME=$CREEP_SPAWN_TIME MAX_SIZE=$MAX_CREEP_SIZE " +
                    "cost: M=${cost(MOVE)} C=${cost(CARRY)} R=${cost(RANGED_ATTACK)} H=${cost(HEAL)} A=${cost(ATTACK)} T=${cost(TOUGH)} W=${cost(WORK)}"
            )
            // константы башни — сверка модели (InfluenceMap.towerShot) с движком этой арены
            println(
                "tower: attack=$TOWER_POWER_ATTACK heal=$TOWER_POWER_HEAL optimal=$TOWER_OPTIMAL_RANGE falloffRange=$TOWER_FALLOFF_RANGE " +
                    "falloff=$TOWER_FALLOFF cooldown=$TOWER_COOLDOWN capacity=$TOWER_CAPACITY shotCost=$TOWER_ENERGY_COST range=$TOWER_RANGE hits=$TOWER_HITS " +
                    "buildPower=$BUILD_POWER build=${JSON.stringify(CONSTRUCTION_COST)} " +
                    "model shot(1..4,10,20)=${(1..4).map { InfluenceMap.towerShot(it).toInt() }} ${InfluenceMap.towerShot(10).toInt()} ${InfluenceMap.towerShot(20).toInt()}"
            )
            println("my spawn=(${mySpawn.x},${mySpawn.y}) energy=${mySpawn.store[RESOURCE_ENERGY]} hits=${mySpawn.hits}/${mySpawn.hitsMax} " +
                "enemy spawn=${enemySpawn?.let { "(${it.x},${it.y})" } ?: "none"}")
            val empty = getObjectsByPrototype(StructureContainer::class).filter { (it.store[RESOURCE_ENERGY] ?: 0) <= 0 }
            if (empty.isNotEmpty()) println("empty containers: " + empty.joinToString(" ") { "(${it.x},${it.y})cap=${it.store.getCapacity(RESOURCE_ENERGY)}my=${it.my}" })
        }
        if (DEBUG_MAP && !mapLogged) {
            mapLogged = true
            logMap()
        }

        val myCreeps = getObjectsByPrototype(Creep::class).filter { it.my && it.exists }
        val enemyCreeps = getObjectsByPrototype(Creep::class).filter { !it.my && it.exists && !it.spawning }
        val active = myCreeps.filter { !it.spawning }

        val haulers = active.filter { c -> c.body.any { it.type == CARRY } }
        val fighters = active.filter { c -> c.body.none { it.type == CARRY } }
        // армия врага — И лекари: M4H2 без оружия считался «мягкой» целью, как хаулер, и бойцы шли за ним
        // как за рейдером — прямо в его конвой из четырёх M3R3 (матч 13, t=1150); в локальном перевесе его
        // лечения не было вовсе, и пара лезла в шар с тремя лекарями (t=1060). Лекарь без урона — тоже
        // цель (фокус — лекари первыми) и тоже хиты в счёте Ланчестера
        val combatEnemies = enemyCreeps.filter { val p = InfluenceMap.profileOf(it); p.melee + p.ranged + p.heal > 0.0 }
        // ОБЕЗДВИЖЕННЫЕ (все MOVE выбиты): стоят навсегда, но стреляют. Для движения они — преграда:
        // просивший шаг обездвиженный «обещал» освободить клетку, TrafficManager верил, и шесть бойцов
        // трёх волн 300 тиков стояли за двумя такими в пробке (матч 02.09).
        val immobile = active.filter { !canMove(it) }
        if (DEBUG_LOG && getTicks() % 50 == 0 && enemyCreeps.isNotEmpty()) {
            println("enemy creeps t=${getTicks()}: " + enemyCreeps.joinToString(" ") { "(${it.x},${it.y})${bodySummary(it)}h=${it.hits}/${it.hitsMax}" })
        }
        // башни врага — источник огня (см. InfluenceMap: урон, влияние, опасность), не только препятствие
        val enemyTowers = getObjectsByPrototype(StructureTower::class)
            .filter { it.exists && it.my != true && (it.hits ?: 0) > 0 }
            .map { TowerInfo(it, towerFed(it, enemyCreeps), it.cooldown, it) }
        InfluenceMap.setEnemyTowers(enemyTowers.map { InfluenceMap.TowerThreat(it.pos.x, it.pos.y, it.fed, it.cooldown) })
        // строящиеся башни врага: площадка видна за сотни тиков до первого выстрела (1250 энергии по 5 за
        // WORK в тик). Тип — по полной стоимости: у башни она своя (1250), а structure чужой площадки API не
        // отдаёт (матч 13: «null(7,50)195/1250»). Темп — наблюдаемый (не раньше половины окна), до того — по
        // WORK строителей в трёх клетках; без темпа и строителей площадка стоит и башней не считается
        val towerCost = CONSTRUCTION_COST["StructureTower"] ?: 0
        val enemySites = getObjectsByPrototype(ConstructionSite::class).filter { it.exists && it.my != true }
        val pendingTowers = enemySites.filter { towerCost > 0 && it.progressTotal == towerCost }.mapNotNull { site ->
            val progress = site.progress ?: 0
            val (t0, p0) = siteSeen.getOrPut(site.id) { getTicks() to progress }
            val observed = if (getTicks() - t0 >= APPROACH_WINDOW / 2) (progress - p0).toDouble() / (getTicks() - t0) else -1.0
            val builders = enemyCreeps.sumOf { c -> if (getRange(c, site) <= 3) c.body.count { it.type == WORK && it.hits > 0 } else 0 }
            val rate = if (observed >= 0.0) observed else builders * BUILD_POWER.toDouble()
            if (rate <= 0.0) null else PendingTower(TowerInfo(site, true, 0), ceil(((site.progressTotal ?: 0) - progress) / rate).toInt())
        }
        siteSeen.keys.retainAll { id -> enemySites.any { it.id == id } }
        // РАЗВЕДКА: рождающийся крип врага виден со второго тика его spawnCreep — его тело и есть дебют
        // противника. Матч 12: два M5R1 за 800 родились на 1-м и 20-м тиках, а мы узнали о них, когда они
        // пришли на 150-м, потратив стартовую тысячу на бурильщика и хаулера
        val enemyPendingAll = getObjectsByPrototype(Creep::class).filter { !it.my && it.exists && it.spawning }
        val enemyPending = enemyPendingAll.filter { val p = InfluenceMap.profileOf(it); p.melee + p.ranged + p.heal > 0.0 }
        if (DEBUG_LOG && getTicks() % 10 == 0) {
            val sp = enemySpawn?.spawning
            if (enemyPendingAll.isNotEmpty() || sp != null) {
                val spBody = sp?.asDynamic()?.creep?.body
                val spText = if (sp == null) "null" else "remaining=${sp.remainingTime}/${sp.needTime} body=${if (spBody == null) "?" else JSON.stringify(spBody)}"
                println("enemy spawning t=${getTicks()}: creeps=" + enemyPendingAll.joinToString(" ") { "(${it.x},${it.y})${bodySummary(it)}" } + " spawn.spawning=$spText")
            }
        }

        // непроходимое: стены, чужие/нейтральные рампарты, спавны и прочие структуры-препятствия.
        // Контейнеры проходимы. На этой карте структур обычно нет, но код обязан работать на любой.
        val walls = getObjectsByPrototype(StructureWall::class).filter { it.exists }
        val ramparts = getObjectsByPrototype(StructureRampart::class).filter { it.exists }
        if (DEBUG_LOG && getTicks() % 50 == 0) {
            // структуры врага: башни (заряд, кулдаун, кормление), рампарты и стройки — башня строится
            // 1250 энергии, и площадка видна задолго до первого выстрела
            val enemyRamparts = ramparts.count { it.my != true }
            if (enemyTowers.isNotEmpty() || enemySites.isNotEmpty() || enemyRamparts > 0) {
                println("enemy structures t=${getTicks()}: " +
                    enemyTowers.joinToString(" ") { "T(${it.pos.x},${it.pos.y})h=${it.obj?.hits}e=${it.obj?.store?.get(RESOURCE_ENERGY)}cd=${it.cooldown}fed=${it.fed}" } +
                    " ramparts=$enemyRamparts sites: " +
                    enemySites.joinToString(" ") { "${it.structure?.asDynamic()?.constructor?.name}(${it.x},${it.y})${it.progress}/${it.progressTotal}" } +
                    " pendingTowers: " + pendingTowers.joinToString(" ") { "(${it.info.pos.x},${it.info.pos.y})eta=${it.eta}" })
            }
        }
        val structures: List<Position> = getObjectsByPrototype(StructureSpawn::class).filter { it.exists } +
            getObjectsByPrototype(StructureExtension::class).filter { it.exists } +
            getObjectsByPrototype(StructureTower::class).filter { it.exists }
        val blocked: List<Position> = walls + ramparts.filter { it.my != true } + structures + immobile
        val blockedForEnemy: List<Position> = walls + ramparts.filter { it.my != false } + structures

        InfluenceMap.setProtectedCells(ramparts.filter { it.my == true }.mapTo(HashSet()) { it.x * 100 + it.y })
        InfluenceMap.setEnemyBlocked(blockedForEnemy.mapTo(HashSet()) { it.x * 100 + it.y })
        val dangerMatrix = InfluenceMap.dangerCostMatrix(enemyCreeps, blocked)

        DistanceMap.syncWalls(walls.size) // снесённая стена пролома открывает проход — поля заново
        if (enemySpawn != null) DistanceMap.ensureBuilt(mySpawn, enemySpawn)

        val loadedToSpawn = DistanceMap.flowFieldTo(mySpawn, blocked)
        flowCache[mySpawn.x * 100 + mySpawn.y] = loadedToSpawn
        val stepsToSpawn = DistanceMap.stepFieldTo(mySpawn, blocked)
        val enemyApproach = DistanceMap.flowFieldTo(mySpawn, blockedForEnemy)
        val enemyLoaded = enemySpawn?.let { DistanceMap.flowFieldTo(it, blockedForEnemy) }

        val sites = collectSites(combatEnemies, loadedToSpawn, enemyLoaded)
        val ctx = Ctx(mySpawn, enemySpawn, myCreeps, active, haulers, fighters, enemyCreeps, combatEnemies, blocked, blockedForEnemy, dangerMatrix, loadedToSpawn, stepsToSpawn, enemyApproach, sites, enemyTowers, ramparts, enemyPending, pendingTowers)

        logSites(sites)
        measureRegen(mySpawn, haulers.any { (it.store[RESOURCE_ENERGY] ?: 0) > 0 && it.getRangeTo(mySpawn) <= 1 })
        val breach = breachPlan(ctx)
        if (DEBUG_LOG && breach != null && !breachLogged) {
            breachLogged = true
            println("breach plan: container (${breach.container.x},${breach.container.y}) e=${breach.container.store[RESOURCE_ENERGY]} walls=${breach.walls.size} hits=${breach.totalHits}: " +
                breach.walls.joinToString(" ") { "(${it.x},${it.y})h=${it.hits}" })
        }

        // тревога: боевой враг в SPAWN_ALARM_TICKS пути (по ЕГО проходимости) от нашего спавна
        // …по тем, кто бьёт: лекарь без конвоя спавну не страшен, и «боец первым» из-за него — трата
        val alarm = combatEnemies.any { val p = InfluenceMap.profileOf(it); p.melee + p.ranged > 0.0 && enemyApproach[it.x * 100 + it.y] in 0..SPAWN_ALARM_TICKS }
        // оборона: все вооружённые (бурильщик и покалеченные дома дерутся) — по ней дефицит и очередь
        // спавна; наступление runFighters считает по полноскоростным стрелкам (см. fullSpeed): 1200
        // хитов бурильщика, который в поле не идёт, раздули корень Ланчестера и выпустили волну из
        // двух раненых (матч 6)
        homeSpawnPos = mySpawn
        val defenders = fighters.filter { hasWeapon(it) }
        val ourDefense = ourPowerOf(defenders, combatEnemies)
        val enemyPower = enemyPowerOf(combatEnemies, fighters)
        // для решений спавна враг — «скоро»: с теми, кто ещё рождается у его спавна
        val threatsSoon = combatEnemies + enemyPending
        val enemyArrival = enemyArrivalTicks(ctx)
        val spawnUnderFire = InfluenceMap.fireAt(mySpawn.x, mySpawn.y, combatEnemies) > 0.0
        spawnIfNeeded(ctx, defenders, threatsSoon, alarm, enemyArrival, spawnUnderFire)
        runHaulers(ctx)
        val ourOffense = runFighters(ctx, enemyPower, alarm)

        TrafficManager.resolve(active.filter { canMove(it) }, myCreeps + enemyCreeps)
        InfluenceMap.pruneStances(myCreeps.mapTo(HashSet()) { it.id })
        enemyPrevCell.clear()
        for (e in enemyCreeps) enemyPrevCell[e.id] = e.x * 100 + e.y
        if (DEBUG_LOG) logStuck(active, enemyCreeps)

        if (DEBUG_VISUALS) InfluenceMap.drawDebug(fighters, myCreeps, enemyCreeps)

        if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
            val carried = haulers.sumOf { it.store[RESOURCE_ENERGY] ?: 0 }
            val usable = usableSites(ctx)
            println(
                "t=${getTicks()} spawnE=${mySpawn.store[RESOURCE_ENERGY]} spawning=${mySpawn.spawning != null} " +
                    "haulers=${haulers.size} carried=$carried fighters=${fighters.size} enemies=${enemyCreeps.size}/${combatEnemies.size} " +
                    "sites=${sites.size} usable=${usable.sumOf { it.energy }} income=${projectedIncome(ctx, usable).toInt()}/${targetIncome().toInt()} " +
                    "push=$pushing($lastPushReason) alarm=$alarm home=$homeMode our=${ourOffense.toInt()}/${ourDefense.toInt()} enemy=${enemyPower.toInt()} pending=${enemyPending.size} arrival=${if (enemyArrival >= Int.MAX_VALUE / 4) "-" else enemyArrival.toString()} towers=${enemyTowers.count { it.fed }}/${enemyTowers.size}+${pendingTowers.size} enemySpawnHits=${enemySpawn?.hits}"
            )
            if (getTicks() % (LOG_EVERY * 10) == 0) println(TrafficManager.audit())
        }
    }

    // ==================== экономика ====================

    private fun cost(part: BodyPartType): Int = BODYPART_COST[part] ?: 0
    private fun blockCost(): Int = cost(CARRY) + cost(MOVE)
    private fun capacityOf(creep: Creep): Int = creep.store.getCapacity(RESOURCE_ENERGY) ?: 0

    private fun haulerBody(blocks: Int): Array<BodyPartType> {
        val body = ArrayList<BodyPartType>(blocks * 2)
        repeat(blocks) { body.add(CARRY) }
        repeat(blocks) { body.add(MOVE) }
        return body.toTypedArray()
    }

    /**
     * Точки энергии с оценкой принадлежности и безопасности. «Наша» — до которой гружёный
     * хаулер доезжает до нашего спавна не позже, чем вражеский до своего (по реальному пути,
     * болото ×5). Опасная — рядом боевой враг или клетка под его огнём.
     */
    private fun collectSites(combatEnemies: List<Creep>, loadedToSpawn: IntArray, enemyLoaded: IntArray?): List<EnergySite> {
        val result = ArrayList<EnergySite>()
        fun add(id: String, pos: Position, energy: Int, container: StructureContainer?, resource: Resource?, decay: Int?) {
            if (energy <= 0) return
            if (pos.x !in 0..99 || pos.y !in 0..99) return
            val my = loadedToSpawn[pos.x * 100 + pos.y]
            val en = enemyLoaded?.get(pos.x * 100 + pos.y) ?: -1
            if (my < 0) return // недостижима для нас — не точка
            val ours = en < 0 || my <= en
            val safe = combatEnemies.none { getRange(it, pos) <= SITE_DANGER_RANGE } &&
                InfluenceMap.damageAt(pos.x, pos.y, combatEnemies) <= 0.0
            result.add(EnergySite(id, pos, energy, container, resource, decay, ours, my, en, safe))
        }
        for (c in getObjectsByPrototype(StructureContainer::class)) {
            if (!c.exists) continue
            add(c.id, c, c.store[RESOURCE_ENERGY] ?: 0, c, null, c.ticksToDecay)
        }
        for (r in getObjectsByPrototype(Resource::class)) {
            if (!r.exists || r.resourceType != RESOURCE_ENERGY) continue
            add(r.id, r, r.amount, null, r, r.ticksToDecay)
        }
        return result
    }

    /**
     * План пролома: контейнер с энергией, недостижимый по нашей проходимости (заперт структурными
     * стенами), и стены на самом дешёвом к нему проходе. Дейкстра от спавна: шаг 1, клетка стены —
     * WALL_STEP_COST + hits/10 (сначала меньше стен, потом слабее), стены рельефа и структуры
     * непроходимы. Контейнер — наш, если по той же цене он ближе к нам, чем к вражескому спавну.
     * Пересчёт только при смене числа живых стен.
     */
    private fun breachPlan(ctx: Ctx): BreachPlan? {
        val walls = getObjectsByPrototype(StructureWall::class).filter { it.exists }
        if (walls.size == breachWallCount) return breachCache?.takeIf { it.container.exists && (it.container.store[RESOURCE_ENERGY] ?: 0) > 0 }
        breachWallCount = walls.size
        breachCache = null
        val locked = getObjectsByPrototype(StructureContainer::class).filter {
            it.exists && (it.store[RESOURCE_ENERGY] ?: 0) > 0 && it.x in 0..99 && it.y in 0..99 && ctx.loadedToSpawn[it.x * 100 + it.y] < 0
        }
        if (locked.isEmpty()) return null
        val wallAt = HashMap<Int, StructureWall>()
        for (w in walls) wallAt[w.x * 100 + w.y] = w
        val hard = HashSet<Int>()
        for (p in ctx.blocked) if (p.x * 100 + p.y !in wallAt) hard.add(p.x * 100 + p.y)

        fun dijkstra(sx: Int, sy: Int, prev: IntArray?): IntArray {
            val dist = IntArray(10000) { -1 }
            val heap = MinHeap()
            dist[sx * 100 + sy] = 0
            heap.push(0, sx * 100 + sy)
            while (heap.size > 0) {
                val d = heap.popKey(); val cell = heap.popVal(); heap.pop()
                if (d != dist[cell]) continue
                val cx = cell / 100; val cy = cell % 100
                for (dx in -1..1) for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx; val ny = cy + dy
                    if (nx !in 0..99 || ny !in 0..99) continue
                    val ni = nx * 100 + ny
                    if (ni in hard) continue
                    if (getTerrainAt(InfluenceMap.cell(nx, ny)) == TERRAIN_WALL) continue
                    val step = wallAt[ni]?.let { WALL_STEP_COST + (it.hits ?: WALL_HITS) / 10 } ?: 1
                    val nd = d + step
                    if (dist[ni] < 0 || nd < dist[ni]) { dist[ni] = nd; prev?.set(ni, cell); heap.push(nd, ni) }
                }
            }
            return dist
        }
        val prev = IntArray(10000) { -1 }
        val my = dijkstra(ctx.mySpawn.x, ctx.mySpawn.y, prev)
        val enemy = ctx.enemySpawn?.let { dijkstra(it.x, it.y, null) }
        val target = locked
            .filter { my[it.x * 100 + it.y] >= 0 && (enemy == null || enemy[it.x * 100 + it.y] < 0 || my[it.x * 100 + it.y] <= enemy[it.x * 100 + it.y]) }
            .minByOrNull { my[it.x * 100 + it.y] } ?: return null
        val path = ArrayList<StructureWall>()
        var cell = target.x * 100 + target.y
        var steps = 0
        var loaded = 0
        while (cell >= 0 && cell != ctx.mySpawn.x * 100 + ctx.mySpawn.y) {
            wallAt[cell]?.let { path.add(it) }
            steps++
            loaded += if (DistanceMap.isSwamp(cell / 100, cell % 100)) DistanceMap.SWAMP_COST else 1
            cell = prev[cell]
        }
        path.reverse() // от спавна к контейнеру
        if (path.isEmpty()) return null
        breachCache = BreachPlan(target, path, steps, loaded)
        return breachCache
    }

    private fun canMove(creep: Creep) = creep.body.any { it.type == MOVE && it.hits > 0 }
    private fun hasMelee(creep: Creep) = creep.body.any { it.type == ATTACK && it.hits > 0 }
    private fun isMelee(creep: Creep) = creep.body.any { it.type == ATTACK }
    private fun hasWeapon(creep: Creep) = hasRanged(creep) || hasMelee(creep)

    /** Тело бурильщика [MOVE, ATTACK]×k: k минимизирует спавн + ломку (6k + H/30k тиков → k ≈ √(H/180)),
     *  в пределах бюджета и размера тела. */
    private fun breacherBlocks(budget: Int, totalHits: Int): Int {
        val block = cost(MOVE) + cost(ATTACK)
        val perTick = CREEP_SPAWN_TIME * 2 * ATTACK_POWER
        val opt = kotlin.math.sqrt(totalHits.toDouble() / perTick).let { kotlin.math.round(it).toInt() }
        return minOf(opt.coerceAtLeast(1), budget / block, MAX_CREEP_SIZE / 2)
    }

    /** Блоки бурильщика, которого спавн закажет из энергии energy: минимальный хаулер из неё зарезервирован
     *  (ветка спавна, breachOpenIn и breachGuardReady считают ОДНО тело: прогон с телом на 130 дороже
     *  оставлял симуляции 90 энергии вместо 220 и «страж к 1989-му», стенд freeze). */
    private fun plannedBreacherBlocks(energy: Int, totalHits: Int): Int =
        breacherBlocks(energy - HAULER_BLOCKS_MIN * blockCost(), totalHits)

    private fun breacherBody(blocks: Int): Array<BodyPartType> {
        // вперемешку: урон снимает части спереди, блок MOVE впереди оставлял обездвиженного мили
        // с полным ударом (матч 02.09: f11 700 тиков стоял турелью на выходе из базы)
        val body = ArrayList<BodyPartType>(blocks * 2)
        repeat(blocks) { body.add(MOVE); body.add(ATTACK) }
        return body.toTypedArray()
    }

    /** Сводка тела: T10M4R3H1 (только живые части). */
    private fun bodySummary(creep: Creep): String {
        val order = listOf(TOUGH to 'T', MOVE to 'M', RANGED_ATTACK to 'R', ATTACK to 'A', HEAL to 'H', CARRY to 'C', WORK to 'W')
        val sb = StringBuilder()
        for ((type, ch) in order) {
            val n = creep.body.count { it.type == type && it.hits > 0 }
            if (n > 0) sb.append(ch).append(n)
        }
        return sb.toString()
    }

    /** Диагностика застревания: в момент, когда крип пересёк порог STUCK_TICKS, печатаем, чего он
     *  хочет и кто стоит на той клетке (и чего хочет тот). Пробка 02.09 длилась 270 тиков молча. */
    private fun logStuck(active: List<Creep>, enemyCreeps: List<Creep>) {
        for (c in active) {
            if (TrafficManager.stuckFor(c.id) != TrafficManager.STUCK_TICKS) continue
            val want = TrafficManager.lastDesiredOf(c.id)
            val occ = want?.let { w -> (active + enemyCreeps).firstOrNull { it.x * 100 + it.y == w } }
            val occWant = occ?.let { TrafficManager.lastDesiredOf(it.id) }
            println("stuck ${c.id} at (${c.x},${c.y}) fatigue=${c.fatigue} wants=${want?.let { "(${it / 100},${it % 100})" }} " +
                "occ=${occ?.let { "${it.id} my=${it.my} fatigue=${it.fatigue} ${bodySummary(it)} wants=${occWant?.let { w -> "(${w / 100},${w % 100})" } ?: "-"}" } ?: "free"}")
        }
    }

    /** Точки, куда хаулер поедет: безопасные, наши или контестные с запасом по пути. */
    private fun usableSites(ctx: Ctx): List<EnergySite> =
        ctx.sites.filter { it.safe && (it.ours || contestedOk(it, ctx.combatEnemies)) }

    /** Поле в шагах до точки (пустой хаулер идёт по болоту как по суше) — кэш на тик. */
    private fun siteSteps(ctx: Ctx, site: EnergySite): IntArray =
        siteStepsCache.getOrPut(site.id) { DistanceMap.stepFieldTo(site.pos, ctx.blocked) }

    /** Шаги пустого крипа до точки по реальному пути (стенные блоки вокруг кармана спавна делают
     *  Чебышев бесполезным: точка в 20 клетках по прямой лежит в 100 шагах в обход). */
    private fun stepsTo(ctx: Ctx, site: EnergySite, creep: Creep): Int =
        siteSteps(ctx, site)[creep.x * 100 + creep.y].let { if (it < 0) Int.MAX_VALUE / 4 else it }

    /** Рейс хаулера от спавна к точке и обратно: пустым — шаги, гружёным — тики (болото ×5), плюс
     *  withdraw и transfer. */
    private fun tripTicks(ctx: Ctx, site: EnergySite): Int =
        ctx.stepsToSpawn[site.pos.x * 100 + site.pos.y].coerceAtLeast(0) + site.myTicks + 2

    /** Целевой приток: сколько энергии в тик спавн способен превратить в бойцов — цена части полного
     *  бойца, делённая на время спавна части. Приток выше этого копится в очереди хаулеров у спавна
     *  и ничего не ускоряет. */
    private fun targetIncome(): Double {
        val body = fighterBody(SPAWN_ENERGY_CAPACITY, spawnLimited = true) // при полном спавне строится это тело
        return body.sumOf { cost(it) }.toDouble() / (body.size * CREEP_SPAWN_TIME)
    }

    /** Средний рейс по точкам, которые флот РЕАЛЬНО будет возить: ближайшие по рейсу, пока их
     *  энергии хватает на FLEET_ROUNDS кругов всего флота. Точки, распадающиеся раньше рейса, не
     *  считаются. 0 — возить нечего. */
    private fun fleetTrip(ctx: Ctx, usable: List<EnergySite>, fleetCapacity: Int): Double =
        // без фильтра по распаду: рейс от спавна длиннее половины жизни временного контейнера, и
        // прогноз выбрасывал их все (income=0 при двенадцати хаулерах в работе); хаулеры решают
        // про распад сами, по своей позиции (decayOk)
        fleetTripOf(usable.map { it.energy to tripTicks(ctx, it) }, fleetCapacity)

    /** То же по точкам (энергия, рейс) — для прогноза с точками, которых в поле ещё нет (пролом). */
    private fun fleetTripOf(points: List<Pair<Int, Int>>, fleetCapacity: Int): Double = fleetHaul(points, fleetCapacity).first

    /** Рейс флота и энергия, которую он РЕАЛЬНО увезёт за FLEET_ROUNDS кругов: ближайшие точки, пока их
     *  энергии хватает флоту на столько кругов (не меньше одного полного хаулера). */
    private fun fleetHaul(points: List<Pair<Int, Int>>, fleetCapacity: Int): Pair<Double, Int> {
        val sorted = points.sortedBy { it.second }
        if (sorted.isEmpty()) return 0.0 to 0
        val need = maxOf(fleetCapacity * FLEET_ROUNDS, HAULER_BLOCKS_MAX * CARRY_CAPACITY)
        var energy = 0
        var weighted = 0.0
        for ((e, trip) in sorted) {
            val take = minOf(e, need - energy)
            if (take <= 0) break
            energy += take
            weighted += take.toDouble() * trip
        }
        return (if (energy > 0) weighted / energy else 0.0) to energy
    }

    /** Приток флота по точкам: за рейс он увозит не больше своей ёмкости и не больше, чем лежит на земле
     *  в расчёте на круг. Двенадцать хаулеров стояли без дела при 450 энергии на земле, а «приток ниже
     *  цели» купил ещё троих по 500 (матч 15, t=680-750). */
    private fun incomeOf(points: List<Pair<Int, Int>>, fleetCapacity: Int): Double {
        val (trip, taken) = fleetHaul(points, fleetCapacity)
        if (trip <= 0.0) return 0.0
        return minOf(fleetCapacity.toDouble(), taken.toDouble() / FLEET_ROUNDS) / trip.coerceAtLeast(3.0)
    }

    /** Узкое место — ёмкость флота, а не энергия на земле: с ещё одним хаулером (addCapacity) флот увёз бы
     *  за круги больше, чем увозит сейчас. Иначе новый хаулер встанет в ту же очередь у пустых точек. */
    private fun capacityBound(points: List<Pair<Int, Int>>, fleetCapacity: Int, addCapacity: Int): Boolean {
        val (trip, taken) = fleetHaul(points, fleetCapacity + addCapacity)
        return trip > 0.0 && taken > fleetCapacity * FLEET_ROUNDS
    }

    private fun fleetPoints(ctx: Ctx, usable: List<EnergySite>): List<Pair<Int, Int>> = usable.map { it.energy to tripTicks(ctx, it) }

    /** Прогноз притока текущего флота по точкам, которые он будет возить (см. incomeOf). */
    private fun projectedIncome(ctx: Ctx, usable: List<EnergySite>): Double =
        incomeOf(fleetPoints(ctx, usable), ctx.haulers.sumOf { capacityOf(it) })

    private fun runHaulers(ctx: Ctx) {
        val haulers = ctx.haulers
        val mySpawn = ctx.mySpawn
        val siteById = ctx.sites.associateBy { it.id }
        // контейнер пролома, пока стена стоит: точка, которая откроется через breachOpenIn
        val breach = breachPlan(ctx)
        val breachWall = breach?.current()
        val breachOpen = if (breach != null && breachWall != null) breachOpenIn(ctx, breach, mySpawn.store[RESOURCE_ENERGY] ?: 0) else Int.MAX_VALUE / 4
        val breachSafe = breach != null && ctx.combatEnemies.none { getRange(it, breach.container) <= SITE_DANGER_RANGE } &&
            (breachWall == null || InfluenceMap.damageAt(breachWall.x, breachWall.y, ctx.combatEnemies) <= 0.0)
        val wallSteps by lazy { DistanceMap.stepFieldTo(breachWall!!, ctx.blocked) }
        haulerSite.keys.retainAll { id -> haulers.any { it.id == id } }

        // свои крипы — «дорогие» клетки: searchPath крипов не знает и ведёт сквозь припаркованного,
        // а TrafficManager протолкнуть его может не всегда — пусть путь их обтекает
        val crowdMatrix = ctx.dangerMatrix.clone()
        for (ally in ctx.active) {
            val current = crowdMatrix.get(ally.x, ally.y)
            if (current < 255) crowdMatrix.set(ally.x, ally.y, minOf(254, current + CROWD_COST))
        }
        // застрявший хаулер (STUCK_TICKS тиков просит один шаг и стоит): чужие клетки для его пути
        // непроходимы — пусть обходит, а не бодает
        val stuckMatrix by lazy {
            val m = ctx.dangerMatrix.clone()
            for (ally in ctx.active) m.set(ally.x, ally.y, 255)
            m
        }
        fun matrixFor(h: Creep): CostMatrix = if (TrafficManager.isStuck(h.id)) stuckMatrix else crowdMatrix
        // обездвиженный хаулер (все MOVE выбиты) шага не просит: он вне movers, его «желание» лишь
        // обещает освободить клетку, которую он не освободит (находка screeps-rules 02.09)
        fun go(h: Creep, step: Position?) {
            if (step == null || !canMove(h)) return
            val loaded = (h.store[RESOURCE_ENERGY] ?: 0) > 0
            TrafficManager.request(h, step, if (loaded) HAULER_LOADED_PRIORITY else HAULER_PRIORITY)
        }
        fun dbg(h: Creep, mode: String, site: EnergySite?, step: Position? = null) {
            if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
                println("  h${h.id} (${h.x},${h.y}) carry=${h.store[RESOURCE_ENERGY]}/${capacityOf(h)} $mode site=${site?.let { "(${it.pos.x},${it.pos.y})e=${it.energy}" } ?: "-"} toSpawn=${h.getRangeTo(mySpawn)} fatigue=${h.fatigue} step=${step?.let { "(${it.x},${it.y})" } ?: "stay"}${if (TrafficManager.isStuck(h.id)) " STUCK" else ""}")
            }
        }

        // in-flight: сколько энергии уже «увозят» с точки (свободная ёмкость назначенных)
        val claimed = HashMap<String, Int>()
        for (h in haulers) {
            val sid = haulerSite[h.id] ?: continue
            claimed[sid] = (claimed[sid] ?: 0) + (h.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0)
        }

        for (h in haulers) {
            val carrying = h.store[RESOURCE_ENERGY] ?: 0
            val free = h.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0

            // остов: все CARRY выбиты, возить нечем — уходим за кольцо парковки и не занимаем клетки
            // сдачи (шесть остовов стояли вплотную к спавну в режиме DELIVER до конца матча 8)
            if (capacityOf(h) == 0) {
                haulerSite.remove(h.id)
                val step = if (h.getRangeTo(mySpawn) <= PARK_RANGE + 1) {
                    searchPath(h, SearchGoal(pos = mySpawn, range = PARK_RANGE + 1), SearchPathOptions(flee = true, costMatrix = crowdMatrix)).path.firstOrNull()
                } else null
                go(h, step)
                dbg(h, "HUSK", null, step)
                continue
            }

            // под огнём — бросаем всё и уходим к спавну (хаулер не боец; груз важнее, чем точка)
            val incoming = InfluenceMap.damageAt(h.x, h.y, ctx.combatEnemies)
            if (incoming > 0.0) {
                haulerSite.remove(h.id)?.let { sid -> claimed[sid] = ((claimed[sid] ?: 0) - free).coerceAtLeast(0) }
                val step = fleeStep(h, ctx.combatEnemies, ctx.dangerMatrix) ?: pathStep(h, mySpawn, 1, matrixFor(h))
                // шаг бегства в болото с грузом — прижатие на пять тиков под огнём (вес груза, см.
                // periodAt); пустой идёт по болоту как по суше — груз бросаем, он полежит (−1/тик),
                // вернёмся. Четыре гружёных хаулера ползли из-под M5R5 через болото с fatigue=40 (матч 8)
                if (carrying > 0 && step != null && periodAt(h, step.x, step.y) > 1) h.drop(RESOURCE_ENERGY)
                go(h, step)
                if (carrying > 0 && h.getRangeTo(mySpawn) <= 1) h.transfer(mySpawn, RESOURCE_ENERGY)
                dbg(h, "FLEE", null, step)
                continue
            }

            // липкое назначение: держим точку, пока она есть, безопасна и там осталось что брать
            var site = haulerSite[h.id]?.let { siteById[it] }
            if (site != null) {
                val others = (claimed[site.id] ?: 0) - free
                val stale = !site.safe || site.energy - others <= 0 || !decayOk(ctx, site, h)
                if (stale) {
                    haulerSite.remove(h.id)
                    claimed[site.id] = ((claimed[site.id] ?: 0) - free).coerceAtLeast(0)
                    site = null
                }
            }

            if (free == 0 || (carrying > 0 && site == null)) {
                // везём: у спавна — сдаём; спавн полон — ждём рядом, не занимая его соседние клетки.
                // Назначение снимаем и возвращаем точке «увозимую» ёмкость.
                haulerSite.remove(h.id)?.let { sid -> claimed[sid] = ((claimed[sid] ?: 0) - free).coerceAtLeast(0) }
                val spawnFree = mySpawn.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0
                if (h.getRangeTo(mySpawn) <= 1) {
                    if (spawnFree > 0) h.transfer(mySpawn, RESOURCE_ENERGY)
                    dbg(h, if (spawnFree > 0) "DELIVER" else "WAIT_FULL", null)
                } else {
                    val stopAt = if (spawnFree > 0) 1 else 2
                    val step = if (h.getRangeTo(mySpawn) > stopAt) pathStep(h, mySpawn, stopAt, matrixFor(h)) else null
                    go(h, step)
                    dbg(h, "TO_SPAWN", null, step)
                }
                continue
            }

            if (site == null) {
                // выбор точки: больше энергии за меньший рейс (пустым — шаги по пути, обратно — гружёным),
                // с учётом уже увозимого другими
                var best: EnergySite? = null
                var bestValue = 0.0
                for (s in ctx.sites) {
                    if (!s.safe) continue
                    if (!s.ours && !contestedOk(s, ctx.combatEnemies)) continue
                    val available = s.energy - (claimed[s.id] ?: 0)
                    if (available <= 0) continue
                    val steps = stepsTo(ctx, s, h)
                    if (steps >= Int.MAX_VALUE / 4) continue
                    if (!decayOk(ctx, s, h)) continue
                    val value = minOf(available, free).toDouble() / (steps + s.myTicks + 2)
                    if (value > bestValue) { bestValue = value; best = s }
                }
                // контейнер пролома за стеной: рейс — от большего из хода до стены и ожидания пролома, обратно
                // гружёным по проходу. Первый хаулер ушёл на 45 клеток к угловому контейнеру и вернулся через
                // 90 тиков, а пролом в девяти клетках открылся через 50 (матч 14)
                var waitAtWall: StructureWall? = null
                if (breach != null && breachWall != null && breachSafe && breachOpen < Int.MAX_VALUE / 4) {
                    val mine = if (haulerSite[h.id] == breach.container.id) free else 0
                    val available = (breach.container.store[RESOURCE_ENERGY] ?: 0) - (claimed[breach.container.id] ?: 0) + mine
                    val steps = wallSteps[h.x * 100 + h.y]
                    if (available > 0 && steps >= 0) {
                        val value = minOf(available, free).toDouble() / (maxOf(steps, breachOpen) + breach.loadedTicks + 2)
                        if (value > bestValue) { bestValue = value; best = null; waitAtWall = breachWall }
                    }
                }
                if (waitAtWall != null && breach != null) {
                    haulerSite[h.id] = breach.container.id
                    claimed[breach.container.id] = (claimed[breach.container.id] ?: 0) + free
                    val step = if (h.getRangeTo(waitAtWall) > 1) pathStep(h, waitAtWall, 1, matrixFor(h)) else null
                    go(h, step)
                    dbg(h, "TO_BREACH", null, step)
                    continue
                }
                site = best
                if (site != null) {
                    haulerSite[h.id] = site.id
                    claimed[site.id] = (claimed[site.id] ?: 0) + free
                }
            }

            if (site == null) {
                // возить нечего: с грузом — к спавну, пустой — паркуемся на кольце PARK_RANGE
                // (не вплотную: соседние клетки спавна — выход новорождённых и подход сдающих).
                // Стоящий НЕ регистрирует интент: без желания и с приоритетом 0 его протолкнёт свапом любой едущий.
                if (carrying > 0) {
                    val step = if (h.getRangeTo(mySpawn) <= 1) { h.transfer(mySpawn, RESOURCE_ENERGY); null } else pathStep(h, mySpawn, 1, matrixFor(h))
                    go(h, step)
                    dbg(h, "DUMP", null, step)
                } else if (h.getRangeTo(mySpawn) > PARK_RANGE) {
                    val step = pathStep(h, mySpawn, PARK_RANGE, matrixFor(h))
                    go(h, step)
                    dbg(h, "PARK", null, step)
                } else if (h.getRangeTo(mySpawn) < PARK_RANGE) {
                    val goal = SearchGoal(pos = mySpawn, range = PARK_RANGE - 1)
                    val step = searchPath(h, goal, SearchPathOptions(flee = true, costMatrix = crowdMatrix)).path.firstOrNull()
                    go(h, step)
                    dbg(h, "PARK_OUT", null, step)
                } else {
                    dbg(h, "IDLE", null)
                }
                continue
            }

            if (h.getRangeTo(site.pos) <= 1) {
                site.container?.let { h.withdraw(it, RESOURCE_ENERGY) }
                site.resource?.let { h.pickup(it) }
                dbg(h, "LOAD", site)
            } else {
                val step = pathStep(h, site.pos, 1, matrixFor(h))
                go(h, step)
                dbg(h, "TO_SITE", site, step)
            }
        }
    }

    /** Успеем ли доехать (пустым — 1 клетка/тик по любой земле, по реальному пути) до распада точки. */
    private fun decayOk(ctx: Ctx, site: EnergySite, hauler: Creep): Boolean {
        val decay = site.ticksToDecay ?: return true
        return decay > stepsTo(ctx, site, hauler) + DECAY_MARGIN
    }

    /** Контестная (не наша по достижимости) точка берётся, если враг до неё не ближе нас
     *  с большим запасом и рядом нет его бойцов (safe уже проверен снаружи). */
    private fun contestedOk(site: EnergySite, combatEnemies: List<Creep>): Boolean {
        if (site.enemyTicks < 0) return true
        return site.myTicks <= site.enemyTicks * 3 / 2 && combatEnemies.none { getRange(it, site.pos) <= SITE_DANGER_RANGE * 2 }
    }

    // ==================== спавн ====================

    /**
     * Очередь спавна. Хаулер — пока прогноз притока флота ниже того, что спавн способен переварить,
     * и на земле есть что возить; боец — вне очереди при тревоге или когда видимая армия врага
     * перевешивает нашу (DEFEND_MARGIN); иначе боец под доступную энергию (спавн держит не больше 1000).
     * Без тревоги копим до тела с БОЛЬШИМ числом боевых частей, если ожидаемая энергия его обещает:
     * меньше целей под фокус, лечение концентрируется; первого защитника не ждём вовсе.
     */
    private fun spawnIfNeeded(ctx: Ctx, defenders: List<Creep>, threats: List<Creep>, alarm: Boolean, enemyArrival: Int, spawnUnderFire: Boolean) {
        val spawn = ctx.mySpawn
        if (spawn.spawning != null) return
        val energy = spawn.store[RESOURCE_ENERGY] ?: 0
        val carried = ctx.haulers.sumOf { it.store[RESOURCE_ENERGY] ?: 0 }
        val ourPower = ourPowerOf(defenders, threats)
        val enemyPower = enemyPowerOf(threats, defenders)
        // ДЕБЮТ: стартовую тысячу не тратим, пока не увидели, что рождает противник: его крип, заказанный
        // на первом тике, виден как spawning со второго. Если он не рождает ничего, ждём не дольше половины
        // окна оценки сближения — столько нужно, чтобы понять, идёт ли к нам уже стоящий в поле враг
        // (стенд freeze: неподвижные стражи в 60 тиках пути принимались за атаку). Дебют «бурильщик
        // первым» против ранней атаки проигрывает без вариантов (матч 12)
        if (ctx.myCreeps.isEmpty() && getTicks() <= APPROACH_WINDOW / 2 && ctx.pendingEnemies.isEmpty() && ctx.enemySpawn?.spawning == null) return

        val usable = usableSites(ctx)
        // включая рождающихся; остов без живых CARRY флот не пополняет — место в лимите свободно
        val allHaulers = ctx.myCreeps.count { c -> c.body.any { it.type == CARRY && it.hits > 0 } }

        // ПРОЛОМ: контейнер за стеной у спавна (5000 в девяти клетках против 2500 в сорока восьми) —
        // мили-бурильщик первым: ATTACK бьёт структуры впятеро дешевле RANGED. Хаулеру оставляем
        // минимальное тело, чтобы он был готов к открытию.
        val income = projectedIncome(ctx, usable)
        // пол притока — регенерация спавна: при нуле хаулеров «время догнать» было бесконечным, и «боец
        // первым» либо замыкался сам на себя (стенд 02.09), либо запрещался вовсе — и против ранней атаки
        // спавн держал 262 энергии на бурильщика и хаулера (матч 12)
        val regen = if (regenSamples > 0) regenSum.toDouble() / regenSamples else 1.0
        val fullBody = fighterBody(SPAWN_ENERGY_CAPACITY)
        val bodyPower = lanchester(fullBody.count { it == RANGED_ATTACK } * RANGED_ATTACK_POWER.toDouble(), 0.0, fullBody.size * 100)
        val deficit = enemyPower * DEFEND_MARGIN - ourPower
        val catchUpTicks = if (deficit <= 0.0 || bodyPower <= 0.0) 0.0 else
            ceil(deficit / bodyPower) * maxOf(fullBody.size * CREEP_SPAWN_TIME.toDouble(), fullBody.sumOf { cost(it) } / maxOf(income, regen, 1.0))
        val breach = breachPlan(ctx)
        // полный боец приходит из экономики пролома, пока пролом не открыт (см. breachGuardReady), иначе
        // из нынешнего притока; враг, который придёт раньше, — сначала боец из того, что есть
        val guardReady = if (breach != null) minOf(catchUpTicks, breachGuardReady(ctx, breach, energy).toDouble()) else catchUpTicks
        val fighterFirst = alarm || (deficit > 0.0 && enemyArrival <= guardReady)
        if (breach != null && !alarm && !fighterFirst && ctx.myCreeps.none { isMelee(it) }) {
            val k = plannedBreacherBlocks(energy, breach.totalHits)
            // бурильщик — оптимальным телом; копим на него, только если энергия уже в пути (в спавне и у
            // хаулеров), иначе очередь хаулерам: после дебюта «боец первым» ветка триста тиков копила
            // регенерацию на однокубовый бурильщик и не пускала ни одного хаулера (стенд freeze/harass)
            val kOpt = breacherBlocks(SPAWN_ENERGY_CAPACITY - HAULER_BLOCKS_MIN * blockCost(), breach.totalHits)
            val breacherCost = kOpt * (cost(MOVE) + cost(ATTACK)) + HAULER_BLOCKS_MIN * blockCost()
            if (k < kOpt) {
                if (energy + carried >= breacherCost) return // копим — доедет
            }
            if (k >= kOpt) {
                val r = spawn.spawnCreep(breacherBody(k))
                if (r.error == null) spentFighters += k * (cost(MOVE) + cost(ATTACK))
                if (DEBUG_LOG) {
                    val trace = StringBuilder()
                    val sim = breachGuardReady(ctx, breach, energy, trace)
                    println("spawn: breacher blocks=$k walls=${breach.walls.size} hits=${breach.totalHits} trip=${breach.trip} open=${breachOpenIn(ctx, breach, energy)} guardReady=${guardReady.toInt()} sim=$sim arrival=$enemyArrival err=${r.error}$trace")
                }
                return
            }
        }
        if (DEBUG_LOG && breach != null && fighterFirst && !alarm && ctx.myCreeps.none { isMelee(it) } && getTicks() % 10 == 0) {
            println("spawn: breach postponed — enemy arrives in $enemyArrival, guard by breach in ${breachGuardReady(ctx, breach, energy)}, deficit=${deficit.toInt()}")
        }
        // хаулер нужен, пока прогноз притока ниже того, что спавн переваривает, И спавн не насыщен:
        // при полном спавне с грузом в пути приток уже стоит в очереди, и новый хаулер только
        // отодвигает бойца (стенд: целевой приток 27 при теле M8R4 разгонял флот до 15 при спавне,
        // простаивающем полным)
        val needHauler = allHaulers < MAX_HAULERS &&
            !(energy >= SPAWN_ENERGY_CAPACITY && carried > 0) &&
            capacityBound(fleetPoints(ctx, usable), ctx.haulers.sumOf { capacityOf(it) }, HAULER_BLOCKS_MIN * CARRY_CAPACITY) &&
            projectedIncome(ctx, usable) < targetIncome()
        // стража «на всякий случай» нет: армия врага видна с момента его spawnCreep, и боец строится
        // в ответ на неё (fighterFirst). Страж за 500 стоял 200 тиков без дела, а второй хаулер
        // из-за него появился на 120-м тике — противник к 70-му вывел в поле вдвое больше ёмкости.
        // Боец первым — если видимая армия врага перевешивает И успеет дойти раньше, чем мы закроем
        // дефицит (тел × максимум из времени рождения и накопления энергии). Два разведчика врага
        // на другом краю карты 170 тиков держали спавн на «боец первым» при притоке 10/23 (02.09).
        // (income, deficit, fighterFirst — выше, до ветки бурильщика)
        val haulerTurn = needHauler && !fighterFirst && spentHaulers <= spentFighters + HAULER_LEAD

        if (haulerTurn) {
            val affordable = minOf(HAULER_BLOCKS_MAX, energy / blockCost())
            if (affordable < HAULER_BLOCKS_MIN) return // копим
            // копим на полного, если приток обещает; самого первого хаулера не ждём — без него притока нет
            val expected = minOf(HAULER_BLOCKS_MAX, (energy + carried) / blockCost())
            if (ctx.haulers.isNotEmpty() && affordable < HAULER_BLOCKS_MAX && expected > affordable) return
            val r = spawn.spawnCreep(haulerBody(affordable))
            if (r.error == null) spentHaulers += affordable * blockCost()
            if (DEBUG_LOG) println("spawn: hauler #${allHaulers + 1} blocks=$affordable income=${projectedIncome(ctx, usable).toInt()}/${targetIncome().toInt()} spent=$spentHaulers/$spentFighters err=${r.error}")
            return
        }
        // очередь хаулера, но энергии на бойца тоже нет — копим на того, кто первый по карману
        if (needHauler && !fighterFirst && energy < cost(RANGED_ATTACK) + cost(MOVE)) return

        val minFighter = cost(RANGED_ATTACK) + cost(MOVE)
        if (energy < minFighter) return

        // ЛАГЕРЬ у спавна: враг рядом и сильнее — боец по 300 умирает один (матч 02.09: восемь
        // подряд). Копим на полное тело; хуже, чем ждать, здесь только кормить.
        if (alarm && ourPower < enemyPower && energy < SPAWN_ENERGY_CAPACITY) return

        // ожидаемая энергия — в спавне и В ПУТИ (хаулеры), не пул на земле: тот приедет за рейсы.
        // Копим на тело ценнее (урон×HP), если враг не успеет прийти за время накопления: тринадцать
        // тел по 300 против трёх по 1000 проиграли на равной энергии — массовая атака бьёт по всем.
        // пока есть приток, ожидаемая энергия — полный спавн (дойдёт за waitTicks); без притока —
        // только то, что уже в пути. Иначе в момент, когда хаулеры едут пустыми, «ожидаемое» равно
        // текущему, и спавн выпускал тело по 260 между двумя полными.
        // спавн полон и хаулеры ждут сдачи — узкое место спавн, тело считаем на тик рождения
        val spawnLimited = energy >= SPAWN_ENERGY_CAPACITY && carried > 0
        // противник мили, а домашний мили-гарнизон слабее его набега — строим гарнизон (см. guardNeeded):
        // шар из пяти M5A1H1 у нашего спавна дома убивает бурильщик (180 в тик вплотную), а не стрелки
        val guard = guardNeeded
        val body = if (guard) guardBody(energy, spawnLimited) else fighterBody(energy, spawnLimited)
        val full = if (guard) guardBody(SPAWN_ENERGY_CAPACITY, spawnLimited) else fighterBody(SPAWN_ENERGY_CAPACITY, spawnLimited)
        // Ждём полное тело, если гарнизон и так держит (deficit <= 0: недомерок ничего не добавит) или
        // если враг придёт позже, чем доедет недостающее — по ГРУЖЁНЫМ хаулерам в пути, не по притоку
        // с земли: при пустых точках (income=0) и 2150 энергии в дороге «ждать» выходило 773 тика, и
        // спавн выпустил M1R1 за 200 и M2R1 за 250 (матч 8). И при тревоге тоже: при держащем
        // гарнизоне тревога выпускала M1R1 по 200 (матч 9); недомерок при тревоге — только когда
        // гарнизон не держит и энергия не успевает
        val gap = SPAWN_ENERGY_CAPACITY - energy
        if (gap > 0 && bodyValue(full) > bodyValue(body)) {
            val waitTicks = energyArrivalTicks(ctx, gap, income)
            if (deficit <= 0.0 || enemyArrival > waitTicks) return
            // недомерок — только если САМ закрывает дефицит: тело, которое ничего не меняет, — корм
            // (матч 12: M2R1 и M5R1 по одному против трёх M5R1); под огнём спавна строим, что есть
            if (!spawnUnderFire && !closesDeficit(body, defenders, threats)) return
        }

        val r = spawn.spawnCreep(body)
        if (r.error == null) spentFighters += body.sumOf { cost(it) }
        if (DEBUG_LOG) println("spawn: ${if (guard) "guard" else "fighter"} parts=${body.size} cost=${body.sumOf { cost(it) }} energy=$energy alarm=$alarm first=$fighterFirst our=${ourPower.toInt()}/${enemyPower.toInt()} deficit=${deficit.toInt()} fire=$spawnUnderFire arrival=${if (enemyArrival >= Int.MAX_VALUE / 4) "-" else enemyArrival.toString()} spent=$spentHaulers/$spentFighters err=${r.error}")
    }

    /** Через сколько тиков в спавн доедет ещё gap энергии: гружёные хаулеры по тикам гружёного пути,
     *  ближние первыми, пока их груз не покроет разрыв; остаток — по притоку с земли. */
    private fun energyArrivalTicks(ctx: Ctx, gap: Int, income: Double): Double {
        fun ticksOf(h: Creep) = ctx.loadedToSpawn[h.x * 100 + h.y].let { if (it < 0) Int.MAX_VALUE / 4 else it }
        var covered = 0
        var ticks = 0.0
        for (h in ctx.haulers.filter { (it.store[RESOURCE_ENERGY] ?: 0) > 0 }.sortedBy { ticksOf(it) }) {
            if (covered >= gap) break
            covered += h.store[RESOURCE_ENERGY] ?: 0
            ticks = ticksOf(h).toDouble()
        }
        if (covered < gap) ticks += (gap - covered) / maxOf(income, 1.0)
        return ticks
    }

    /**
     * Ценность тела под наш строй: урон в тик, проинтегрированный по урону, который тело выдерживает,
     * ОСТАВАЯСЬ полноскоростным (период 1 на равнине). Факты движка: части умирают спереди; вес тела
     * для усталости — по ТИПУ частей, мёртвые весят (movement.js:237); усталость снимают только живые
     * MOVE (tick.js:105). Боец, потерявший скорость, из волны выпадает (тормозит её или отстаёт и
     * гибнет один), так что его урон волне не достаётся и здесь не считается. Матч 7: M5R5 после
     * первых 100 урона ходил вдвое медленнее (f28: fatigue=4 на равнине при 676 хитах), после 500 —
     * втрое (f29), и вся первая волна разбилась об это.
     */
    private fun bodyValue(body: Array<BodyPartType>): Int {
        val weight = body.count { it != MOVE && it != CARRY }
        var moves = body.count { it == MOVE }
        var ranged = body.count { it == RANGED_ATTACK }
        var melee = body.count { it == ATTACK }
        var value = 0
        for (part in body) {
            if (moves < weight) break // скорость потеряна — дальше тело волне не нужно
            value += (ranged * RANGED_ATTACK_POWER + melee * ATTACK_POWER) * 100 // сто хитов этой части боец бьёт с текущим уроном
            when (part) {
                MOVE -> moves--
                RANGED_ATTACK -> ranged--
                ATTACK -> melee--
                else -> {}
            }
        }
        return value
    }

    private val guardBodyCache = HashMap<Int, Array<BodyPartType>>()

    /** Тело домашнего мили-гарнизона под бюджет — тот же перебор и порядок, что у fighterBody, но с
     *  ATTACK вместо RANGED: запасные MOVE вперёд, удар, MOVE 1:1 в хвост. На 1000 это M12A5: 150 удара,
     *  1700 хитов, 1200 из них на полной скорости. Строится только против мили-противника (guardNeeded):
     *  стрелка мили не догоняет, а мили-шар у спавна не кайтится и режется только вплотную. */
    private fun guardBody(budget: Int, spawnLimited: Boolean = false): Array<BodyPartType> {
        val cap = minOf(budget, SPAWN_ENERGY_CAPACITY)
        return guardBodyCache.getOrPut(cap * 2 + (if (spawnLimited) 1 else 0)) {
            val block = cost(ATTACK) + cost(MOVE)
            var best: Array<BodyPartType>? = null
            var bestValue = -1.0
            var a = 1
            while (a * block <= cap && 2 * a <= MAX_CREEP_SIZE) {
                val maxExtra = minOf((cap - a * block) / cost(MOVE), MAX_CREEP_SIZE - 2 * a)
                for (e in 0..maxExtra) {
                    val body = ArrayList<BodyPartType>(2 * a + e)
                    repeat(e) { body.add(MOVE) }
                    repeat(a) { body.add(ATTACK) }
                    repeat(a) { body.add(MOVE) }
                    val arr = body.toTypedArray()
                    val value = bodyValue(arr).toDouble() / (if (spawnLimited) arr.size * CREEP_SPAWN_TIME else 1)
                    if (value > bestValue) { bestValue = value; best = arr }
                }
                a++
            }
            best ?: arrayOf(MOVE, ATTACK)
        }
    }

    private val fighterBodyCache = HashMap<Int, Array<BodyPartType>>()

    /**
     * Тело бойца под бюджет: перебор (R стрелковых, T броневых, E запасных MOVE) по максимуму bodyValue;
     * при узком спавне (энергия в избытке, хаулеры ждут) — по ценности на тик рождения. Порядок частей —
     * из того же расчёта: запасные MOVE ВПЕРЁД (умирая, они не меняют ни урона, ни скорости — вес по
     * типу, а живых MOVE остаётся не меньше веса), затем RANGED (урон тает, скорость держится), в хвост
     * MOVE 1:1 к остальным. На 1000 это M8R4: 40 урона и 900 хитов на полной скорости, против
     * прежнего M5R5 (50 урона, скорость теряется после 100) и T3M7R4 (после 400). TOUGH перебор не
     * выбирает: пара TOUGH+MOVE (60) даёт 100 хитов запаса, запасной MOVE (50) — тоже 100, и без веса.
     */
    private fun fighterBody(budget: Int, spawnLimited: Boolean = false): Array<BodyPartType> {
        val cap = minOf(budget, SPAWN_ENERGY_CAPACITY)
        return fighterBodyCache.getOrPut(cap * 2 + (if (spawnLimited) 1 else 0)) {
            val rangedBlock = cost(RANGED_ATTACK) + cost(MOVE)
            val toughBlock = cost(TOUGH) + cost(MOVE)
            var best: Array<BodyPartType>? = null
            var bestValue = -1.0
            var bestRanged = 0
            var r = 1
            while (r * rangedBlock <= cap) {
                var t = 0
                while (r * rangedBlock + t * toughBlock <= cap && 2 * (r + t) <= MAX_CREEP_SIZE) {
                    val spent = r * rangedBlock + t * toughBlock
                    val maxExtra = minOf((cap - spent) / cost(MOVE), MAX_CREEP_SIZE - 2 * (r + t))
                    for (e in 0..maxExtra) {
                        val body = ArrayList<BodyPartType>(2 * (r + t) + e)
                        repeat(t) { body.add(TOUGH) }
                        repeat(e) { body.add(MOVE) }
                        repeat(r) { body.add(RANGED_ATTACK) }
                        repeat(r + t) { body.add(MOVE) }
                        val arr = body.toTypedArray()
                        val value = bodyValue(arr).toDouble() / (if (spawnLimited) arr.size * CREEP_SPAWN_TIME else 1)
                        if (value > bestValue || (value == bestValue && r > bestRanged)) { bestValue = value; best = arr; bestRanged = r }
                    }
                    t++
                }
                r++
            }
            best ?: arrayOf(MOVE, RANGED_ATTACK)
        }
    }

    /** Вес тела для усталости: части не-MOVE и не-CARRY ПО ТИПУ (мёртвые весят — movement.js:237)
     *  плюс гружёные CARRY (по 50 с хвоста). */
    private fun bodyWeight(creep: Creep): Int {
        val parts = creep.body.count { it.type != MOVE && it.type != CARRY }
        val carried = creep.store[RESOURCE_ENERGY] ?: 0
        return parts + (carried + CARRY_CAPACITY - 1) / CARRY_CAPACITY
    }

    private fun liveMoves(creep: Creep) = creep.body.count { it.type == MOVE && it.hits > 0 }

    /** Период хода (тиков на клетку): после шага fatigue = вес × цена местности − 2 × живые MOVE, дальше
     *  −2×MOVE в тик, следующий ход при нуле (tick.js:105, movement.js:237). M5R5: равнина 1, болото 5;
     *  тот же боец без одного MOVE — 2 и 6. */
    private fun periodOn(weight: Int, moves: Int, rate: Int): Int {
        if (moves <= 0) return Int.MAX_VALUE / 4
        val left = weight * rate - 2 * moves
        return if (left <= 0) 1 else 1 + (left + 2 * moves - 1) / (2 * moves)
    }

    private fun plainPeriod(creep: Creep) = periodOn(bodyWeight(creep), liveMoves(creep), 2)
    private fun periodAt(creep: Creep, x: Int, y: Int) =
        periodOn(bodyWeight(creep), liveMoves(creep), if (DistanceMap.isSwamp(x, y)) 10 else 2)

    /** Полноскоростной: клетка равнины за тик. Только такие ходят волнами и на охоту — покалеченный
     *  либо тормозит группу, либо отстаёт и гибнет один; дома он полноценный защитник. */
    private fun fullSpeed(creep: Creep) = plainPeriod(creep) == 1

    /** Сколько урона крип ещё выдержит, не теряя скорости: части умирают спереди, вес не меняется,
     *  скорость держится, пока живых MOVE не меньше веса. Уже медленный — 0. */
    private fun speedSlack(creep: Creep): Int {
        val weight = bodyWeight(creep)
        var moves = liveMoves(creep)
        var slack = 0
        for (part in creep.body) {
            if (part.hits <= 0) continue
            if (moves < weight) break
            slack += part.hits
            if (part.type == MOVE) moves--
        }
        return slack
    }

    /**
     * Цена боя для того из наших, кого враг фокусит: враги умирают по одному (слабейший первым) под
     * нашим суммарным стрелковым уроном, и пока очередной жив, стреляют все оставшиеся. Ланчестер
     * говорит, кто победит, но не почём: M5R5 «побеждает» M3R3 один на один, отдавая 450 хитов и
     * скорость (матч 7, дважды). Бой берём, если эта цена укладывается в запас скорости группы.
     */
    private fun fightCost(enemies: List<Creep>, ours: List<Creep>, towers: List<TowerInfo> = emptyList()): Double {
        val ourDps = ours.sumOf { InfluenceMap.profileOf(it).ranged }
        if (ourDps <= 0.0) return Double.MAX_VALUE
        // порядок целей — как у нашего фокуса (healAndShoot): лекари первыми, затем по хитам; лечение
        // живых вычитается из нашего урона — без этого пара M8R4 «брала» стаю из двух M3R3 и M4H2 за
        // 1227 хитов при 2400 своих и ушла волной на неё (матч 11: 48 лечения против 80 урона — цена
        // втрое выше, и обе волны легли)
        val order = enemies.sortedWith(compareByDescending<Creep> { InfluenceMap.profileOf(it).heal }.thenBy { it.hits })
        // башни бьют всё время боя (их самих не добиваем — цель не они), с худшей для нас дистанции
        var remaining = enemies.sumOf { effectiveDps(it, ours, homeSpawnPos) } + towers.sumOf { towerDpsFor(it, enemies) }
        var heal = enemies.sumOf { InfluenceMap.profileOf(it).heal }
        var damage = 0.0
        for (e in order) {
            val net = ourDps - heal
            if (net <= 0.0) return Double.MAX_VALUE
            damage += remaining * e.hits / net
            remaining -= effectiveDps(e, ours, homeSpawnPos)
            heal -= InfluenceMap.profileOf(e).heal
        }
        return damage
    }

    /** Кормится ли башня: выстрел уже в ней или носильщик с энергией в кулдауне хода (отдаёт вплотную
     *  за тот же тик). Некормленная башня — препятствие, не огонь. */
    private fun towerFed(tower: StructureTower, enemyCreeps: List<Creep>): Boolean {
        if ((tower.store[RESOURCE_ENERGY] ?: 0) >= InfluenceMap.towerCost) return true
        return enemyCreeps.any { (it.store[RESOURCE_ENERGY] ?: 0) > 0 && getRange(it, tower) <= InfluenceMap.towerCooldown }
    }

    /** Дистанция, с которой башня бьёт по нам, когда мы стреляем по этим целям с standoff: худший для
     *  нас случай — ближняя к башне сторона (scoreCell сам выберет дальнюю, если она есть). */
    private fun towerRangeFor(t: TowerInfo, targets: List<Position>, standoff: Int = RANGED_RANGE): Int =
        maxOf(1, targets.minOf { getRange(t.pos, it) } - standoff)

    private fun towerDpsFor(t: TowerInfo, targets: List<Position>, standoff: Int = RANGED_RANGE): Double =
        if (targets.isEmpty()) 0.0 else InfluenceMap.towerShot(towerRangeFor(t, targets, standoff)) / InfluenceMap.towerCooldown

    /** Кормленные башни врага, чей выстрел достаёт до нас у этих целей. */
    private fun coveringTowers(ctx: Ctx, targets: List<Position>, standoff: Int = RANGED_RANGE): List<TowerInfo> =
        if (targets.isEmpty()) emptyList() else ctx.enemyTowers.filter { it.fed && InfluenceMap.towerShot(towerRangeFor(it, targets, standoff)) > 0.0 }

    private class SiegeResult(val win: Boolean, val ticks: Int, val hitsLost: Int) {
        override fun toString() = "${if (win) "win" else "lose"}/${ticks}t/-$hitsLost"
    }
    private val SIEGE_LOSE = SiegeResult(false, Int.MAX_VALUE / 2, 0)

    /** Предел симуляции осады в тиках: дольше — не осада, а размен на истощение. */
    private const val SIEGE_LIMIT = 400

    /** Боец в симуляции: живые части спереди назад, урон снимает их по порядку (как в движке). */
    private class SimUnit(parts: List<Pair<BodyPartType, Int>>) {
        val types = parts.map { it.first }
        val hits = IntArray(parts.size) { parts[it].second }
        fun alive() = hits.any { it > 0 }
        fun total() = hits.sum()
        fun dps(): Double {
            var d = 0.0
            for (i in types.indices) {
                if (hits[i] <= 0) continue
                if (types[i] == RANGED_ATTACK) d += RANGED_ATTACK_POWER.toDouble()
                else if (types[i] == ATTACK) d += ATTACK_POWER.toDouble()
            }
            return d
        }
        /** Снять урон спереди; возвращает снятое (меньше amount, если боец кончился). */
        fun hit(amount: Double): Double {
            var left = amount
            for (i in hits.indices) {
                if (left <= 0.0) break
                if (hits[i] <= 0) continue
                val take = minOf(hits[i].toDouble(), left)
                hits[i] -= ceil(take).toInt()
                left -= take
            }
            return amount - left
        }
    }

    /**
     * Осада спавна врага волной — симуляция по тикам, а не «мощь против мощи». Защитники фокусят
     * самого раненого (как наш healAndShoot — их), лечение живых вычитается из нашего урона, башня раз
     * в кулдаун снимает выстрел с самого боеспособного — и один выстрел на 850-1000 выключает целого
     * стрелка (части гибнут спереди: MOVE, затем RANGED). Пул хитов этого не видел: четыре M8R4
     * «переживали» башню по среднему урону и легли по одному за выстрел, сняв со спавна 1200 из 3000
     * (матч 11). Потери пути (attrition) снимаются до осады фокусом по самому раненому. ratio — запас:
     * хиты спавна считаются с этим множителем (1.3 на выход, 0.9 на продолжение), плюс рампарт на нём.
     */
    private fun siegeOutcome(wave: List<Creep>, attrition: Double, defenders: List<Creep>, towers: List<TowerInfo>, spawn: StructureSpawn, rampartHits: Int, ratio: Double, flow: IntArray, extraShots: Int = 0): SiegeResult {
        if (wave.isEmpty()) return SIEGE_LOSE
        val units = wave.map { c -> SimUnit(c.body.filter { it.hits > 0 }.map { it.type to it.hits }) }
        var left = attrition
        while (left > 0.0) {
            val v = units.filter { it.alive() }.minByOrNull { it.total() } ?: return SIEGE_LOSE
            val taken = v.hit(left)
            if (taken <= 0.0) return SIEGE_LOSE
            left -= taken
        }
        class Def(val hits: Double, val dps: Double, val heal: Double)
        val defs = ArrayDeque(defenders
            .sortedWith(compareByDescending<Creep> { InfluenceMap.profileOf(it).heal }.thenBy { it.hits })
            .map { Def(it.hits.toDouble(), effectiveDps(it, wave, spawn), InfluenceMap.profileOf(it).heal) })
        var defHits = defs.firstOrNull()?.hits ?: 0.0
        class Gun(val tower: TowerInfo, val shot: Double, var next: Int)
        val guns = towers.map { Gun(it, InfluenceMap.towerShot(towerRangeFor(it, listOf(spawn))), maxOf(0, it.cooldown)) }
        var lost = 0.0
        fun fire(t: Int, shotOf: (Gun) -> Double) {
            for (g in guns) {
                if (t < g.next) continue
                val shot = shotOf(g)
                if (shot <= 0.0) continue
                val v = units.filter { it.alive() }.maxWithOrNull(compareBy({ it.dps() }, { it.total() })) ?: return
                lost += v.hit(shot)
                g.next = t + InfluenceMap.towerCooldown
            }
        }
        // ПОДХОД: от нынешней позиции авангарда спуском по полю потока до дистанции выстрела по спавну,
        // тик за тиком в темпе самого медленного (болото — см. periodAt), под выстрелами башен по
        // текущей дистанции. Башня достаёт за двадцать клеток: по болоту это полсотни тиков и пять
        // выстрелов до того, как волна вообще увидит спавн (стенд: пятеро легли на подходе)
        var clock = 0
        if (guns.isNotEmpty()) {
            var cell = wave.filter { flow[it.x * 100 + it.y] >= 0 }.minByOrNull { flow[it.x * 100 + it.y] }?.let { it.x * 100 + it.y } ?: -1
            var steps = 0
            while (cell >= 0 && steps < 300 && getRange(InfluenceMap.cell(cell / 100, cell % 100), spawn) > RANGED_RANGE) {
                val cx = cell / 100
                val cy = cell % 100
                var best = -1
                var bestFlow = flow[cell]
                for (dx in -1..1) for (dy in -1..1) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
                    val f = flow[nx * 100 + ny]
                    if (f in 0 until bestFlow) { bestFlow = f; best = nx * 100 + ny }
                }
                if (best < 0) break
                cell = best
                steps++
                val period = wave.maxOf { periodAt(it, cell / 100, cell % 100) }
                repeat(period) {
                    fire(clock) { g -> InfluenceMap.towerShot(getRange(g.tower.pos, InfluenceMap.cell(cell / 100, cell % 100))) }
                    clock++
                }
                if (units.none { it.alive() }) return SiegeResult(false, 0, lost.toInt())
            }
        }
        // запас на выход: план обязан пережить ещё столько выстрелов у кольца сверх предсказанных — один
        // выстрел решает судьбу целого стрелка, и множитель хитов спавна этой зернистости не видит
        // (стенд: «победа с запасом 1400 хитов» обернулась откатом, когда второй выстрел подхода снял
        // на 150 больше расчётного)
        repeat(extraShots) {
            for (g in guns) {
                val v = units.filter { it.alive() }.maxWithOrNull(compareBy({ it.dps() }, { it.total() })) ?: break
                lost += v.hit(g.shot)
            }
        }
        var spawnHits = (spawn.hits ?: SPAWN_HITS) * ratio + rampartHits
        for (i in 0 until SIEGE_LIMIT) {
            val t = clock + i
            fire(t) { g -> g.shot }
            val defDps = defs.sumOf { it.dps }
            if (defDps > 0.0) {
                val v = units.filter { it.alive() }.minByOrNull { it.total() }
                if (v != null) lost += v.hit(defDps)
            }
            val ourDps = units.sumOf { it.dps() }
            if (ourDps <= 0.0) return SiegeResult(false, i, lost.toInt())
            if (defs.isNotEmpty()) {
                val net = ourDps - defs.sumOf { it.heal }
                if (net <= 0.0) return SiegeResult(false, i, lost.toInt())
                defHits -= net
                while (defHits <= 0.0 && defs.isNotEmpty()) {
                    val carry = -defHits
                    defs.removeFirst()
                    defHits = (defs.firstOrNull()?.hits ?: 0.0) - carry
                }
            } else {
                spawnHits -= ourDps
                if (spawnHits <= 0.0) return SiegeResult(true, i + 1, lost.toInt())
            }
        }
        return SiegeResult(false, SIEGE_LIMIT, lost.toInt())
    }

    // ==================== армия ====================

    private fun flowTo(ctx: Ctx, target: Position): IntArray =
        flowCache.getOrPut(target.x * 100 + target.y) { DistanceMap.flowFieldTo(target, ctx.blocked) }

    /** Армия. Возвращает мощь наступления (ушедшие волны плюс готовые уйти с поста) — для журнала. */
    private fun runFighters(ctx: Ctx, enemyPower: Double, alarm: Boolean): Double {
        val fighters = ctx.fighters
        if (fighters.isEmpty()) { wave.clear(); return 0.0 }
        // остов (стрельба выбита) из волны выбывает: он идёт домой (см. !hasWeapon), а волна держала строй
        // «для отставшего» по нему — семеро стояли в сорока клетках от спавна врага сто тиков, пока f49 с
        // четырьмя MOVE уходил к нашему (стенд tower+stream: чужой спавн умер на 1545-м вместо 1125-го)
        wave.keys.retainAll { id -> fighters.any { it.id == id && hasWeapon(it) } }
        val mySpawn = ctx.mySpawn
        val enemySpawn = ctx.enemySpawn
        val allies = ctx.myCreeps
        val enemyCreeps = ctx.enemyCreeps
        val combatEnemies = ctx.combatEnemies

        val ourHalfCombat = combatEnemies.filter { DistanceMap.inOurHalf(it.x, it.y) }
        val ourHalfSoft = enemyCreeps.filter { c -> combatEnemies.none { it.id == c.id } && DistanceMap.inOurHalf(c.x, c.y) }

        // ---- постура: оборона / наступление (гистерезис + последний звонок до ничьей) ----
        val dps = fighters.sumOf { InfluenceMap.profileOf(it).ranged }
        // в поле — полноскоростные стрелки (см. fullSpeed): покалеченный ходит вдвое-втрое медленнее
        // и либо тормозит волну, либо отстаёт и гибнет один; дома он полноценный защитник
        val strikers = fighters.filter { fullSpeed(it) && hasRanged(it) }
        var lastCall = false
        var travel = Int.MAX_VALUE / 2
        if (enemySpawn != null && dps > 0.0) {
            val spawnFlow = flowTo(ctx, enemySpawn)
            // марш — по ближайшему к цели стрелку волны (центр масс бывает на стене, где поле = -1)
            travel = strikers.ifEmpty { fighters }
                .minOf { spawnFlow[it.x * 100 + it.y].let { d -> if (d < 0) Int.MAX_VALUE / 2 else d } }
            val kill = ((enemySpawn.hits ?: SPAWN_HITS) / dps).toInt()
            val remaining = arenaInfo.ticksLimit - getTicks()
            lastCall = remaining <= travel + kill + LATE_MARGIN
        }
        // волна собирается ДОМА: полноскоростные стрелки не в волне и в зоне тревоги от спавна. Боец,
        // ушедший на охоту, в волну не зачисляется — в матче 6 волна из двух охотников на севере и
        // новорождённого на юге ушла тремя маршрутами и полегла по одному
        fun atHome(c: Creep) = ctx.loadedToSpawn[c.x * 100 + c.y] in 0..SPAWN_ALARM_TICKS
        val freeStrikers = strikers.filter { it.id !in wave }
        val staging = freeStrikers.filter { atHome(it) }
        // сила наступления: ушедшие волны (кто ещё вооружён — волна своих ждёт, см. hold) плюс те,
        // кто готов уйти с поста
        val offensive = fighters.filter { (it.id in wave && hasWeapon(it)) || staging.any { s -> s.id == it.id } }
        val ourOffense = ourPowerOf(offensive, combatEnemies)
        // волна в пути платит за каждую стычку (fightCost): идущих к нам встречаем группами (группа —
        // одновременно), рождённых за марш и осаду — по одному, типичным бойцом врага. Уходим, если
        // после всех стычек и ещё одной такой же (запас на ошибку оценки) хиты остаются, а по
        // Ланчестеру остаток сильнее стоящих у врага дома. Прежний счёт «мощь против мощи» не знал
        // цены пути: пара M8R4 ушла при 438 против 437 и легла об два M5R5 подряд (матч 8)
        val production = enemyProductionPerTick(getTicks(), combatEnemies + ctx.pendingEnemies)
        val massing = combatEnemies.filter { it.id !in approachingIds }
        val massingPower = enemyPowerOf(massing, strikers)
        val waveDps = offensive.sumOf { InfluenceMap.profileOf(it).ranged }
        val waveHits = offensive.sumOf { it.hits }
        val siege = if (enemySpawn != null && waveDps > 0.0) ((enemySpawn.hits ?: SPAWN_HITS) / waveDps).toInt() else 0
        val horizon = minOf(travel, arenaInfo.ticksLimit) + siege
        var attrition = 0.0
        var maxPack = 0.0
        val unmet = combatEnemies.filter { it.id in approachingIds }.toMutableList()
        while (unmet.isNotEmpty()) {
            val seed = unmet.first()
            val pack = combatEnemies.filter { getRange(it, seed) <= ENGAGE_RANGE + RANGED_RANGE }
            unmet.removeAll { u -> pack.any { it.id == u.id } }
            attrition += fightCost(pack, offensive)
            maxPack = maxOf(maxPack, enemyPowerOf(pack, strikers))
        }
        val typical = typicalBirth()
        val unitCost = if (typical != null && waveDps > 0.0) typical.dps * typical.hits / waveDps else 0.0
        val streamUnits = if (typical != null && typical.power > 0.0) production * horizon / typical.power else 0.0
        attrition += streamUnits * unitCost
        val hitsLeft = waveHits - attrition - unitCost
        val waveAfter = if (hitsLeft > 0.0) lanchester(
            offensive.sumOf { effectiveDps(it, massing, null) },
            massing.sumOf { InfluenceMap.profileOf(it).heal }, hitsLeft.toInt()) else 0.0
        // осада по симуляции (см. siegeOutcome): стоящие у врага, кормленные башни у его спавна, рампарт
        // на спавне; на выход — с запасом «ещё одна стычка» и 1.3 хитов спавна, на продолжение — 0.9
        // …и те, что достроятся до конца осады (см. PendingTower): подход travel плюс сама осада
        val siegeTowers = if (enemySpawn != null) coveringTowers(ctx, listOf(enemySpawn)) +
            ctx.pendingTowers.filter { it.eta <= travel + siege && InfluenceMap.towerShot(towerRangeFor(it.info, listOf(enemySpawn))) > 0.0 }.map { it.info }
        else emptyList()
        val spawnRampart = enemySpawn?.let { s -> ctx.ramparts.filter { it.my != true && it.x == s.x && it.y == s.y }.sumOf { it.hits ?: 0 } } ?: 0
        // на выход — ГРУППА ПОСТА, которая уйдёт вместе (волны друг друга не ждут: подкрепление по двое
        // догоняло первую волну через сотню тиков и ложилось под башню по очереди — стенд); на
        // продолжение — ушедшие волны
        val waveMembers = fighters.filter { it.id in wave && hasWeapon(it) }
        val spawnFlow = if (enemySpawn != null) flowTo(ctx, enemySpawn) else IntArray(0)
        // ФРОНТ волны — те, кто держится вместе: в зазоре сплочения марша от авангарда по полю к спавну
        // врага. Осада на продолжение считается по фронту, а не по всем ушедшим: подкрепление в полутора
        // сотнях клеток позади в осаде не участвует, и «выигрыш» с ним отправил бы авангард под башню одного
        val waveFront = if (waveMembers.isNotEmpty() && spawnFlow.isNotEmpty()) {
            val van = waveMembers.mapNotNull { m -> spawnFlow[m.x * 100 + m.y].takeIf { it >= 0 } }.minOrNull() ?: 0
            waveMembers.filter { m -> spawnFlow[m.x * 100 + m.y].let { it >= 0 && it - van <= COHESION_GAP } }
        } else waveMembers
        val siegeStart = if (enemySpawn != null) siegeOutcome(staging, attrition + unitCost, massing, siegeTowers, enemySpawn, spawnRampart, PUSH_RATIO, spawnFlow, extraShots = 1) else SIEGE_LOSE
        val siegeGo = if (enemySpawn != null) siegeOutcome(waveFront, attrition, massing, siegeTowers, enemySpawn, spawnRampart, PUSH_RELEASE_RATIO, spawnFlow) else SIEGE_LOSE
        // осада фронтом ВМЕСТЕ с группой поста: когда волна держит кромку, подкрепление уходит к ней, если
        // сумма выигрывает (с запасом на выход, как siegeStart)
        val siegeJoin = if (enemySpawn != null && waveFront.isNotEmpty() && staging.isNotEmpty()) siegeOutcome(waveFront + staging, attrition + unitCost, massing, siegeTowers, enemySpawn, spawnRampart, PUSH_RATIO, spawnFlow, extraShots = 1) else SIEGE_LOSE
        val notWeaker = ourOffense >= enemyPower
        // тревога отменяет наступление, только если ДОМАШНИЙ гарнизон с угрозой не справится:
        // разведчик врага у северного выхода отзывал всю армию с южного (02.09, трижды). Гарнизон —
        // те, кто ОСТАНЕТСЯ дома: без ждущих ухода (staging) — иначе «держит» считалось с теми, кто
        // через тик уйдёт, и постура прыгала PUSH/DEFEND каждый тик (матч 9: шестнадцать волн за
        // тридцать тиков). Спавн под огнём — наступление не начинаем и не продолжаем вовсе, пока
        // не выигрываем гонку спавнов: пять мили били наш спавн, а армия ушла волной (матч 9)
        val homeThreats = combatEnemies.filter { ctx.enemyApproach[it.x * 100 + it.y] in 0..SPAWN_ALARM_TICKS }
        // счёт — против ВСЕЙ стаи вокруг угроз у дома, как у охоты (матч 6): кольцо тревоги в 40 тиков
        // резало шар по болоту, и «гарнизон бьёт угрозу у дома» считалось против трёх из восьми, а пятеро
        // стояли на клетку дальше (стенд hover: 635 против 464 при 758 у всего шара)
        val homePack = combatEnemies.filter { e -> homeThreats.any { getRange(e, it) <= ENGAGE_RANGE + RANGED_RANGE } }
        val homeGuard = fighters.filter { it.id !in wave && staging.none { s -> s.id == it.id } && hasWeapon(it) }
        val guardHolds = homeThreats.isEmpty() ||
            ourPowerOf(homeGuard, homePack) >= enemyPowerOf(homePack, homeGuard) * DEFEND_MARGIN
        val spawnUnderFire = InfluenceMap.fireAt(mySpawn.x, mySpawn.y, combatEnemies) > 0.0
        // бой у дома — только если спавн уже под огнём, враг у ворот (достаёт пост или спавн) или
        // гарнизон ЦЕЛИКОМ сильнее всей угрозы. Иначе пост отрядом: враг в 25-40 тиках пути сильнее —
        // навстречу ему уходили по одному, и семь бойцов подряд легли о стаю из трёх M3R3 с двумя
        // лекарями (матч 11), а бурильщик перед тем 60 тиков бегал за кайтером и умер, не ударив.
        // Правило матча 9 («враг у дома — дерёмся всем составом») живёт в homeAtGates.
        val homeAll = fighters.filter { it.id !in wave && hasWeapon(it) }
        // с гистерезисом, как охота: начатый бой продолжаем при 0.9 — иначе первые потери переключали
        // «дерёмся» в «пост», и отряд разворачивался под огнём
        val homeOurs = ourPowerOf(homeAll, homePack)
        val homeTheirs = enemyPowerOf(homePack, homeAll)
        val homeWins = homeThreats.isNotEmpty() &&
            homeOurs >= homeTheirs * (if (homeFight) PUSH_RELEASE_RATIO else DEFEND_MARGIN)
        // «у ворот» — ВНУТРИ поста, а не в семи клетках: шар из двух M3R3 и двух M4H2 ходил в 6-8 клетках
        // от спавна, и на каждом заходе рывок «всем составом» делал один свежий боец (прочие — остовы без
        // RANGED); враг отходил на клетку, бой отменялся, боец оставался в шаре — девять подряд (матч 13).
        // Рывок пост сильнее не делает, подкрепление — делает. Слабее врага мы сжимаем пост в тень спавна
        // (scoreCell: без перевеса урон — штраф, стрельба — нет), и до спавна враг дотянется только через
        // весь отряд разом — это spawnUnderFire
        val homeAtGates = homeThreats.any { getRange(it, mySpawn) <= HOME_STANDOFF }
        homeFight = homeThreats.isNotEmpty() && (spawnUnderFire || homeAtGates || homeWins)
        // в журнал — с причиной и счётом: «fight:gates(790/759)» читается без пересчёта
        homeMode = if (homeThreats.isEmpty()) "-" else (if (homeFight) "fight:" + (if (spawnUnderFire) "fire" else if (homeAtGates) "gates" else "wins") else "hold") +
            "(${homeOurs.toInt()}/${homeTheirs.toInt()})"
        val strongerNow = staging.size >= PUSH_MIN_FIGHTERS && siegeStart.win && guardHolds
        // пик набега за окно: под него строится мили-гарнизон, если противник сам мили (см. guardNeeded)
        if (raidPeakTick < 0 || getTicks() - raidPeakTick > PRODUCTION_WINDOW || maxPack >= raidPeak) { raidPeak = maxPack; raidPeakTick = getTicks() }
        val meleeOpponent = typical != null && typical.dps > 0.0 && typical.melee > typical.dps / 2
        // включая рождающихся: гарнизон рождается 51 тик, и без этого спавн ставил третьего, пока
        // второй ещё не вышел (стенд)
        val homeMelee = ctx.myCreeps.filter { it.id !in wave && isMelee(it) && !hasRanged(it) }
        guardNeeded = meleeOpponent && raidPeak > 0.0 &&
            ourPowerOf(homeMelee, combatEnemies) < raidPeak * DEFEND_MARGIN
        val newPushing = when {
            enemySpawn == null -> false
            (spawnUnderFire || alarm && !guardHolds) && !pushWinsRace(ctx, ourHalfCombat, siegeGo) -> false
            // последний звонок — тоже только с выигрышной осадой: армия, положенная под башню в конце,
            // не приносит ничьей, а дома она её держит
            lastCall && notWeaker && siegeGo.win -> true
            strongerNow -> true
            // ушедшую волну не отзываем из-за запаса «ещё одна стычка»: у ворот врага он ей не нужен
            pushing && siegeGo.win -> true
            // волна в поле, а осада разонравилась — не домой через весь коридор под преследованием, а к
            // кромке башни: пост в поле, к нему идёт подкрепление (siegeJoin). Семеро дошли до двадцати
            // клеток от спавна врага, осада по фронту после стычки с тройкой стала «lose», волна отозвана;
            // обратно сто девяносто клеток под шаром — один погиб, четверо покалечены, спавн врага цел, а
            // победа пришла последним звонком на 1754-м (матч 16)
            pushing && waveMembers.isNotEmpty() -> true
            else -> false
        }
        // …но только пока авангард ВНЕ дальности башни: волна под башней уже платит выстрелами, и выход из-под
        // огня стоит те же два выстрела, что и добивание — уцелевшие выходили на кромку посреди штурма и
        // входили снова по одному (стенд tower+stream: спавн врага 300 тиков стоял на 668 хитах)
        val vanguard = waveFront.minByOrNull { spawnFlow[it.x * 100 + it.y] }
        val frontCovered = vanguard != null && coveringTowers(ctx, listOf(vanguard), 0).isNotEmpty()
        siegeHold = newPushing && !siegeGo.win && waveMembers.isNotEmpty() && !frontCovered
        if (DEBUG_LOG && (newPushing != pushing || getTicks() % (LOG_EVERY * 10) == 0)) {
            println("posture: ${if (newPushing) "PUSH" else "DEFEND"} t=${getTicks()} our=${ourOffense.toInt()} hits=$waveHits attrition=${attrition.toInt()}+${unitCost.toInt()} after=${waveAfter.toInt()} enemy=${enemyPower.toInt()} massing=${massingPower.toInt()} pack=${maxPack.toInt()} production=${(production * 100).toInt()}/100t stream=${(streamUnits * 10).toInt() / 10.0} travel=$travel siege=$siege sim=$siegeStart/$siegeGo join=$siegeJoin hold=$siegeHold front=${waveFront.size}/${waveMembers.size} towers=${siegeTowers.size} staging=${staging.size} guardHolds=$guardHolds home=$homeMode spawnFire=$spawnUnderFire guardNeeded=$guardNeeded raidPeak=${raidPeak.toInt()} lastCall=$lastCall alarm=$alarm")
        }
        pushing = newPushing
        lastPushReason = when {
            !pushing -> "defend"
            siegeHold -> "hold"
            lastCall -> "lastCall"
            else -> "stronger"
        }

        // ---- волны: в наступление уходят группой, пополнение копится на посту до следующей волны ----
        if (!pushing) {
            wave.clear()
        } else {
            // в волну — только полноскоростные стрелки с поста: бурильщик (мили) кайтеров не догоняет и
            // гибнет в поле, а дома он и защита спавна, и пролом; обездвиженный никуда не идёт
            // группа уходит, только если сама выигрывает осаду (см. siegeStart), или по последнему звонку
            // …или к волне, держащей кромку, когда вместе с ней осада выигрывается (см. siegeJoin)
            if (staging.isNotEmpty() && (strongerNow || lastCall || (siegeHold && siegeJoin.win && guardHolds))) {
                waveCounter++
                staging.forEach { wave[it.id] = waveCounter }
                if (DEBUG_LOG) println("wave $waveCounter departs: ${staging.size} fighters t=${getTicks()}")
            }
        }

        // ---- общие цели ----
        // 1) боевой враг на нашей половине (ближайший к спавну по пути) — зачистка тыла;
        // 2) рейдер по хаулерам (безоружный враг на нашей половине);
        // 3) ушедшая волна — вражеский спавн;
        // 4) пост у своего спавна.
        val centroid = InfluenceMap.cell(fighters.sumOf { it.x } / fighters.size, fighters.sumOf { it.y } / fighters.size)
        // цель отряда — враг, который РАНЬШЕ придёт к нашему спавну (по темпу сближения); среди не
        // идущих — ближайший к центру отряда (по Чебышеву: «ближайший к спавну по пути» менял цель
        // каждый тик, и бойцы дёргались). Матч 9: армия гонялась на севере за одиночкой, ближайшей
        // к центру отряда, пока шар из пяти мили шёл с юга к спавну
        fun arrivalOf(c: Creep) = arrivalById[c.id] ?: Int.MAX_VALUE / 2
        val threat = ourHalfCombat.minWithOrNull(compareBy<Creep>({ arrivalOf(it) }, { getRange(it, centroid) }))
        val raider = ourHalfSoft.minByOrNull { getRange(it, centroid) }
        // охота на боевого врага на нашей половине — решение ГРУППЫ с гистерезисом: свободные
        // полноскоростные стрелки против ВСЕХ врагов рядом с угрозой, и по перевесу, и по цене боя
        // (см. fightCost). Гарнизон с бурильщиком против одного врага отправил двоих на троих M3R3
        // (матч 6: один стал турелью, второй погиб)
        huntingThreat = threat != null && freeStrikers.isNotEmpty() && run {
            // стая — те, кто рядом с угрозой, И те, кто дойдёт до неё раньше нас (по ИХ ходу вдоль поля к
            // ней): пятеро вышли на двоих (цена 265 при запасе 900), за 60 тиков подхода и сбора к угрозе
            // подошли ещё двое, у контакта стая из четырёх стоила 983, охота отменилась под огнём в болоте,
            // и двое легли, не убив никого (матч 15, t=960-1060)
            val field = flowTo(ctx, threat)
            val ourTravel = freeStrikers.map { pathTicks(it, field, it.x * 100 + it.y) }.filter { it < Int.MAX_VALUE / 4 }.maxOrNull() ?: Int.MAX_VALUE / 4
            val pack = combatEnemies.filter { getRange(it, threat) <= ENGAGE_RANGE + RANGED_RANGE || pathTicks(it, field, it.x * 100 + it.y) <= ourTravel }
            val ours = ourPowerOf(freeStrikers, pack)
            val theirs = enemyPowerOf(pack, freeStrikers)
            ours >= theirs * (if (huntingThreat) PUSH_RELEASE_RATIO else PUSH_RATIO) &&
                (fightCost(pack, freeStrikers, coveringTowers(ctx, pack)) <= freeStrikers.maxOf { speedSlack(it) } || inContact(pack, freeStrikers))
        }
        aggressiveIds.retainAll { id -> fighters.any { it.id == id } }
        lastHits.keys.retainAll { id -> fighters.any { it.id == id } }
        lastCell.keys.retainAll { id -> fighters.any { it.id == id } }

        val enemyPositions = enemyCreeps.mapTo(HashSet()) { it.x * 100 + it.y }
        val blockedSet = ctx.blocked.mapTo(HashSet()) { it.x * 100 + it.y }
        val meleeEnemies = enemyCreeps.filter { InfluenceMap.profileOf(it).melee > 0.0 }

        // фокус-файр: лекари -> добиваемые за тик -> самые раненые
        val inFireRange = enemyCreeps.filter { e -> fighters.any { it.getRangeTo(e) <= RANGED_RANGE } }
        val focusPool = inFireRange.filter { e -> combatEnemies.any { it.id == e.id } }.ifEmpty { inFireRange }
        fun fireAvailableAt(e: Creep) = fighters.filter { it.getRangeTo(e) <= RANGED_RANGE }.sumOf { InfluenceMap.profileOf(it).ranged }
        val focusTarget = focusPool.minWithOrNull(
            compareByDescending<Creep> { InfluenceMap.profileOf(it).heal }
                .thenBy { if (it.hits <= fireAvailableAt(it)) 0 else 1 }
                .thenBy { it.hits }
                .thenBy { getRange(it, centroid) }
        )

        // стена пролома: свободные дома добивают её (бурильщик вплотную, стрелки с дистанции)
        val wallTarget = breachPlan(ctx)?.current()

        // враг У ДОМА (в радиусе тревоги по его пути к спавну): выбора нет — дерёмся всем составом,
        // одной целью, без оглядки на соотношение. Матч 02.09: трое врагов в пяти клетках от спавна,
        // в дальности трое наших, девять сидели на посту «без перевеса не идём» и смотрели.
        val homeTarget = homeThreats.minWithOrNull(compareBy<Creep>({ arrivalOf(it) }, { getRange(it, centroid) }))
        val occupantAt = HashMap<Int, Creep>()
        for (c in ctx.active) occupantAt[c.x * 100 + c.y] = c

        for (creep in fighters) {
            val marching = enemySpawn != null && creep.id in wave
            // за угрозой и рейдером ходят только полноскоростные: покалеченный никого не догонит и
            // никуда не успеет — его место дома (пост, стена пролома, враг у дома)
            val mobile = strikers.any { it.id == creep.id }
            // перевес — ЛОКАЛЬНЫЙ: бойцы, способные стрелять по той же цели через тик-другой (в
            // дальности выстрела + 1), против врагов в их досягаемости. Радиус 8 считал напарника в
            // пяти болотных клетках позади (25 тиков хода) — авангард лез в размен один и гиб (02.09).
            // И по цене: размен, который снимает скорость даже с самого целого из нас, не наш — M5R5
            // «побеждал» M3R3 один на один за 450 хитов и половину хода (матч 7). Порог — по ГРУППЕ,
            // а не по себе: со своим запасом раненый f27 отказался, а целый f28 пошёл — и дрался с
            // M5R5 один, пока f27 стоял в четырёх клетках вне дальности (матч 8: −950 и −330 хитов)
            // на марше группа — волна в досягаемости сближения: в коридоре шириной в две клетки она идёт
            // колонной, и по «соседям в четырёх клетках» хвост из двоих считал себя слабее тройки и отходил,
            // пока четверо шли впереди (матч 16, t=1040)
            val localAllies = fighters.filter { getRange(creep, it) <= (if (marching) ENGAGE_RANGE else RANGED_RANGE + 1) }
            val localEnemies = combatEnemies.filter { getRange(creep, it) <= ENGAGE_RANGE + RANGED_RANGE }
            val ratio = if (creep.id in aggressiveIds) PUSH_RELEASE_RATIO else PUSH_RATIO
            // башни, достающие до боя с этими врагами (или до меня самого, если врагов рядом нет)
            val localTowers = if (localEnemies.isEmpty()) coveringTowers(ctx, listOf(creep), 0) else coveringTowers(ctx, localEnemies)
            // невидимый урон за прошлый тик: снято больше, чем объясняют враги и башни (см. lastHits)
            val ghost = run {
                val prev = lastHits[creep.id]
                val cell = lastCell[creep.id]
                if (prev == null || cell == null) 0 else {
                    val lost = prev - creep.hits
                    // по стрелкам прошлого тика с их тогдашних клеток против нашей тогдашней клетки — те же
                    // позиции, с которых выстрел и делался; плюс залп башни
                    var explained = InfluenceMap.towerBurstAt(cell / 100, cell % 100, requireFed = false)
                    for (s in prevShooters) {
                        val d = maxOf(abs(s.cell / 100 - cell / 100), abs(s.cell % 100 - cell % 100))
                        if (d <= RANGED_RANGE) explained += s.ranged
                        if (d <= 1) explained += s.melee
                    }
                    if (lost > explained + 1.0) lost else 0
                }
            }
            if (ghost > 0 && DEBUG_LOG && getTicks() - (ghostLogged[creep.id] ?: -100) >= 10) {
                ghostLogged[creep.id] = getTicks()
                val nearest = combatEnemies.minOfOrNull { getRange(creep, it) } ?: -1
                println("ghost damage t=${getTicks()}: f${creep.id} -$ghost at (${creep.x},${creep.y}) hits=${creep.hits} nearestCombat=$nearest — источник не виден")
            }
            val localAggressive = when {
                // враг у дома: дерёмся, только если бой у дома наш (см. homeFight), иначе пост отрядом
                homeTarget != null && !marching -> homeFight
                // осада под башней: локальный счёт «цена ≤ запас хода» под башней не сходится никогда
                // (любой выстрел снимает ход), решение за симуляцией всей волны
                marching && localTowers.isNotEmpty() -> pushing && !siegeHold
                localEnemies.isEmpty() -> localTowers.isEmpty()
                // в контакте (враг достаёт до кого-то из нас за шаг) цена боя больше не гейт: отступать под
                // огнём через болото — тот же размен, только без убитых. Пятеро при 722 против 379 отказались
                // от боя ценой 983 при запасе 900, отошли по болоту с усталостью 24-32 и отдали двоих, не убив
                // никого (матч 15). Цена боя решает, ВХОДИТЬ ли в бой; в бою решает счёт (с гистерезисом ratio)
                else -> ourPowerOf(localAllies, localEnemies) >= enemyPowerOf(localEnemies, localAllies) * ratio &&
                    (fightCost(localEnemies, localAllies, localTowers) <= localAllies.maxOf { speedSlack(it) } || inContact(localEnemies, localAllies))
            }
            if (localAggressive) aggressiveIds.add(creep.id) else aggressiveIds.remove(creep.id)
            // встречный боевой враг рядом — при локальном перевесе сворачиваем на него (см. ENGAGE_RANGE)
            val engage = if (localAggressive) combatEnemies.filter { getRange(creep, it) <= ENGAGE_RANGE }.minByOrNull { getRange(creep, it) } else null
            // при перевесе сближаемся до CLOSE_STANDOFF; без перевеса на врага не идём вовсе —
            // держим пост у спавна отрядом (по одному нас и били), кайт и бегство — в mustFlee
            val closeIn = if (localAggressive) CLOSE_STANDOFF else RANGED_RANGE
            val melee = isMelee(creep) && !hasRanged(creep)
            // мили дома бьёт ту угрозу, которую догонит (см. catchable), ближайшую; не «самую раннюю» —
            // та может кайтить, пока другая стоит и бьёт спавн
            val meleeHomeTarget = if (melee && homeFight) homeThreats.filter { catchable(creep, it) }.minByOrNull { getRange(creep, it) } else null
            val target: Position
            val standoff: Int
            // мили (бурильщик) на поводке: враг у дома, стена пролома, пост — и ничего дальше.
            // За целью «на нашей половине» он ушёл на другой край карты и стал турелью (02.09).
            when {
                !hasWeapon(creep) -> { target = mySpawn; standoff = HOME_STANDOFF + 1 }
                homeTarget != null && (!marching || melee) && homeFight && (!melee || meleeHomeTarget != null) -> { target = if (melee) meleeHomeTarget!! else homeTarget; standoff = if (melee) 1 else CLOSE_STANDOFF }
                melee && wallTarget != null -> { target = wallTarget; standoff = 1 }
                melee -> { target = mySpawn; standoff = HOME_STANDOFF }
                // враг у дома сильнее гарнизона — пост отрядом, подкрепление копится у спавна
                homeTarget != null && !marching -> { target = mySpawn; standoff = HOME_STANDOFF }
                engage != null -> { target = engage; standoff = if (melee) 1 else closeIn }
                threat != null && huntingThreat && !marching && mobile -> { target = threat; standoff = closeIn }
                raider != null && !marching && mobile -> { target = raider; standoff = RANGED_RANGE }
                marching -> { target = enemySpawn!!; standoff = if (melee) 1 else RANGED_RANGE }
                wallTarget != null -> { target = wallTarget; standoff = if (melee) 1 else RANGED_RANGE }
                else -> { target = mySpawn; standoff = HOME_STANDOFF }
            }
            val flow = flowTo(ctx, target)
            val breaching = marching && target === enemySpawn

            val nearbyEnemies = combatEnemies.filter { getRange(creep, it) <= 12 }
            // «в бою» — только по крипам врага: под башней без крипов полный счёт клетки (влияние союзников,
            // строй) давал инерцию кучи — десять бойцов сто двадцать тиков стояли у кромки, теряя по
            // одному на выстрел, потому что шаг от кучи терял больше влияния, чем давал поток (стенд)
            val inCombat = combatEnemies.any { creep.getRangeTo(it) <= RANGED_RANGE + 2 }
            // «под огнём» — огонь крипов (и невидимый): поле башни тянется на двадцать клеток, и с ним
            // строй не держал бы никто на всём подходе к спавну врага
            val underFire = InfluenceMap.damageAt(creep.x, creep.y, combatEnemies) - InfluenceMap.towerSustainedAt(creep.x, creep.y) > 0.0 || ghost > 0
            // безоружный (стрельба выбита) бежит только от врага рядом; без врага он стоит на посту за
            // кольцом сдачи — три таких по 300 хитов стояли вплотную к спавну «в бегстве» (матч 8).
            // Башня — залпом: если следующий выстрел добивает — прочь; невидимый урон — по той же мерке
            // …кроме вооружённого в идущей осаде: его гибель уже в цене симуляции, а живой он и стреляет,
            // и принимает выстрел, который иначе достался бы целому (стенд: боец с двумя RANGED убежал,
            // и осада, посчитанная с ним, откатилась)
            val sieging = marching && pushing && localTowers.isNotEmpty()
            val mustFlee = (!hasWeapon(creep) && nearbyEnemies.isNotEmpty()) ||
                creep.hits < InfluenceMap.netDamageAt(creep.x, creep.y, nearbyEnemies, allies) * 2 ||
                (!sieging && creep.hits <= InfluenceMap.towerBurstAt(creep.x, creep.y)) ||
                (ghost > 0 && creep.hits <= ghost)

            // сплочение: авангард ждёт отставших СВОЕЙ группы (в тиках ИХ хода), пока сам не под огнём —
            // и на марше, и при сближении с врагом: «в бою не ждём» отправляло переднего в размен,
            // пока напарник полз по болоту в пяти клетках (02.09)
            val myFlow = flow[creep.x * 100 + creep.y]
            val hunting = !marching && threat != null && target === threat
            val mates = when {
                // волны друг друга не ждут (подкрепление по двое догоняло первую через сотню тиков — стенд)…
                // кроме фронта, держащего кромку: тогда все ушедшие — одна группа, и фронт ждёт подкрепление
                marching -> fighters.filter { it.id != creep.id && (if (siegeHold) it.id in wave else wave[it.id] == wave[creep.id]) && canMove(it) }
                hunting -> freeStrikers.filter { it.id != creep.id }
                else -> emptyList()
            }
            // напарник в бою — не ждёт никто, на любой дистанции: f29 держал строй «для отставшего» в
            // тринадцати клетках от f43, который один дрался в болоте с двумя (матч 7); в матче 6 —
            // в трёх клетках от f28, ставшего турелью
            val mateFighting = mates.any { m -> combatEnemies.any { m.getRangeTo(it) <= RANGED_RANGE + 2 } }
            // при враге в досягаемости или у кромки башни зазор тесный: дальше двух тиков напарника не
            // отпускаем — собираемся ДО входа под огонь; под огнём башни строй не держит никто: каждый
            // тик ожидания — доля выстрела, и пятеро семьдесят тиков ждали друг друга в четырнадцати
            // клетках от башни, теряя по бойцу на выстрел (стенд)
            val nearTower = coveringTowers(ctx, listOf(creep), 2).isNotEmpty()
            val inCoverage = InfluenceMap.towerSustainedAt(creep.x, creep.y) > 0.0
            val gap = if (localEnemies.isEmpty() && !nearTower) COHESION_GAP else ENGAGE_COHESION_TICKS
            // бой у дома — строй не держит никто: бурильщик стоял в трёх клетках от пяти мили, бивших
            // спавн, а четыре M8R4 — в сорока, все с hold=true «для отставшего» (матч 9)
            val hold = (marching || hunting) && !homeFight && !underFire && !inCoverage && !mateFighting && myFlow >= 0 && creep.getRangeTo(target) > standoff && run {
                var lagging = false
                for (m in mates) {
                    if (getRange(creep, m) <= RANGED_RANGE) continue // рядом — не отстал
                    val d = flow[m.x * 100 + m.y]
                    if (d < 0) continue
                    val lag = (d - myFlow) * plainPeriod(m) // поле в тиках полного хода × его период
                    if (lag in (gap + 1)..COHESION_GAP_MAX) { lagging = true; break }
                }
                lagging
            }

            val step: Position? = when {
                !canMove(creep) -> null // обездвижен — только стреляет
                mustFlee -> fleeStep(creep, nearbyEnemies, ctx.dangerMatrix) ?: pathStep(creep, mySpawn, 1, ctx.dangerMatrix)
                hold -> null
                // волна держит кромку башни: из-под огня кормленной башни — прочь; в поле — обычный шаг, но не
                // в её дальность (враг у кромки бьётся по локальному счёту, см. localAggressive)
                marching && siegeHold && coveringTowers(ctx, listOf(creep), 0).isNotEmpty() -> towerEdgeStep(creep, ctx)
                marching && siegeHold -> bestSingleMove(creep, target, flow, standoff, localAggressive, inCombat, breaching, enemyCreeps, allies, meleeEnemies, blockedSet, enemyPositions, occupantAt)
                    ?.takeIf { s -> coveringTowers(ctx, listOf(InfluenceMap.cell(s.x, s.y)), 0).isEmpty() }
                else -> bestSingleMove(creep, target, flow, standoff, localAggressive, inCombat, breaching, enemyCreeps, allies, meleeEnemies, blockedSet, enemyPositions, occupantAt)
            }
            if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
                println("  f${creep.id} (${creep.x},${creep.y}) hits=${creep.hits}/${creep.hitsMax} wave=${wave[creep.id] ?: 0} tgt=(${target.x},${target.y}) flow=$myFlow flee=$mustFlee combat=$inCombat aggr=$localAggressive hold=$hold spd=${plainPeriod(creep)} fatigue=${creep.fatigue} step=${step?.let { "(${it.x},${it.y})" } ?: "stay"}${if (TrafficManager.isStuck(creep.id)) " STUCK" else ""}")
            }
            if (step != null) TrafficManager.request(creep, step, FIGHTER_PRIORITY)
            lastHits[creep.id] = creep.hits
            lastCell[creep.id] = creep.x * 100 + creep.y
        }

        prevShooters = combatEnemies.map { val p = InfluenceMap.profileOf(it); Shooter(it.x * 100 + it.y, p.ranged, p.melee) }
        healAndShoot(fighters, allies, enemyCreeps, enemySpawn, focusTarget, pushing, wallTarget)
        return ourOffense
    }

    /** Удар мили: фокус-цель вплотную, иначе самый раненый сосед, иначе спавн врага, иначе стена пролома. */
    private fun strike(creep: Creep, enemyCreeps: List<Creep>, enemySpawn: StructureSpawn?, focusTarget: Creep?, wallTarget: StructureWall?) {
        if (!hasMelee(creep)) return
        val adjacent = enemyCreeps.filter { creep.getRangeTo(it) <= 1 }
        val target: screeps.api.GameObject? = when {
            focusTarget != null && creep.getRangeTo(focusTarget) <= 1 -> focusTarget
            adjacent.isNotEmpty() -> adjacent.minByOrNull { it.hits }
            enemySpawn != null && creep.getRangeTo(enemySpawn) <= 1 -> enemySpawn
            wallTarget != null && creep.getRangeTo(wallTarget) <= 1 -> wallTarget
            else -> null
        }
        target?.let { creep.attack(it) }
    }

    /** Темп производства врага (мощь/тик): мощь боевых крипов, впервые увиденных за PRODUCTION_WINDOW,
     *  делённая на окно — не короче PRODUCTION_MIN_SPAN и не длиннее времени с первого боевого крипа.
     *  Мощь одиночки — по полным хитам: рождённый цел, а фраги наши его темпа не меняют. */
    private fun enemyProductionPerTick(now: Int, combatEnemies: List<Creep>): Double {
        for (e in combatEnemies) {
            if (!enemySeen.add(e.id)) continue
            if (firstCombatSeen < 0) firstCombatSeen = now
            val p = InfluenceMap.profileOf(e)
            val dps = p.ranged + p.melee
            enemyBirths.addLast(Birth(now, lanchester(dps, 0.0, e.hitsMax), e.hitsMax, dps, p.melee))
        }
        while (enemyBirths.isNotEmpty() && enemyBirths.first().tick < now - PRODUCTION_WINDOW) enemyBirths.removeFirst()
        if (firstCombatSeen < 0) return 0.0
        val span = minOf(PRODUCTION_WINDOW, maxOf(PRODUCTION_MIN_SPAN, now - firstCombatSeen))
        return enemyBirths.sumOf { it.power } / span
    }

    /** Типичный боец врага по рождениям за окно (средние мощь, хиты, урон) — null, пока рождений нет. */
    private fun typicalBirth(): Birth? {
        if (enemyBirths.isEmpty()) return null
        val n = enemyBirths.size
        return Birth(0, enemyBirths.sumOf { it.power } / n, enemyBirths.sumOf { it.hits } / n, enemyBirths.sumOf { it.dps } / n, enemyBirths.sumOf { it.melee } / n)
    }

    /**
     * Через сколько тиков ближайший боевой враг ДОЙДЁТ до нашего спавна — по наблюдаемому темпу
     * сближения за APPROACH_WINDOW, а не по расстоянию: сторож на выходе из базы в 45 тиках пути
     * стоит на месте и не приходит никогда; расстояние принимало его за атаку и держало спавн на
     * бойцах при нуле хаулеров. Новый враг (истории меньше половины окна) считается идущим прямо
     * к нам. Нет врагов или никто не сближается — «бесконечность» (Int.MAX_VALUE / 2).
     */
    private fun enemyArrivalTicks(ctx: Ctx): Int {
        val now = getTicks()
        approachHistory.keys.retainAll { id -> ctx.combatEnemies.any { it.id == id } }
        approachingIds.clear()
        arrivalById.clear()
        var best = Int.MAX_VALUE / 2
        for (e in ctx.combatEnemies) {
            val approach = ctx.enemyApproach[e.x * 100 + e.y]
            if (approach < 0) continue
            val h = approachHistory.getOrPut(e.id) { ArrayDeque() }
            h.addLast(now to approach)
            while (h.isNotEmpty() && h.first().first < now - APPROACH_WINDOW) h.removeFirst()
            val (t0, a0) = h.first()
            // новый враг — по ходу ЕГО тела вдоль поля (см. pathTicks), не по взвешенному полю
            val arrival = if (now - t0 < APPROACH_WINDOW / 2) pathTicks(e, ctx.enemyApproach, e.x * 100 + e.y) else {
                val rate = (a0 - approach).toDouble() / (now - t0)
                if (rate > 0.0) (approach / rate).toInt() else Int.MAX_VALUE / 2
            }
            arrivalById[e.id] = arrival
            if (arrival < Int.MAX_VALUE / 2) approachingIds.add(e.id)
            if (arrival < best) best = arrival
        }
        // рождающиеся у спавна врага: дорога от его спавна их телом плюс остаток рождения (если виден)
        val remaining = ctx.enemySpawn?.spawning?.remainingTime ?: 0
        for (e in ctx.pendingEnemies) {
            val start = flowNear(ctx.enemyApproach, e.x, e.y)
            val arrival = if (start < 0) Int.MAX_VALUE / 2 else pathTicks(e, ctx.enemyApproach, start) + remaining
            arrivalById[e.id] = arrival
            if (arrival < Int.MAX_VALUE / 2) approachingIds.add(e.id)
            if (arrival < best) best = arrival
        }
        return best
    }

    /**
     * Гонка спавнов: враг у нашей базы, но если мы снесём его спавн раньше, чем он наш, —
     * дожимаем. Наше время = марш до дистанции стрельбы + hits/DPS; его — марш + hits/DPS.
     */
    private fun pushWinsRace(ctx: Ctx, ourHalfCombat: List<Creep>, siege: SiegeResult): Boolean {
        val fighters = ctx.fighters
        val enemySpawn = ctx.enemySpawn ?: return false
        if (!pushing || !siege.win || fighters.isEmpty() || ourHalfCombat.isEmpty()) return false
        val spawnFlow = flowTo(ctx, enemySpawn)
        val travel = fighters.minOf { spawnFlow[it.x * 100 + it.y].let { d -> if (d < 0) Int.MAX_VALUE else d } }
        val dps = fighters.sumOf { InfluenceMap.profileOf(it).ranged }
        val enemyTravel = ourHalfCombat.minOf { ctx.enemyApproach[it.x * 100 + it.y].let { d -> if (d < 0) Int.MAX_VALUE else d } }
        val enemyDps = ourHalfCombat.sumOf { val p = InfluenceMap.profileOf(it); p.melee + p.ranged }
        if (travel == Int.MAX_VALUE || dps <= 0.0) return false
        if (enemyTravel == Int.MAX_VALUE || enemyDps <= 0.0) return true
        // наше время осады — по симуляции (башня и защитники), не hits/dps
        val ourTicks = maxOf(0, travel - RANGED_RANGE) + siege.ticks.toDouble()
        val enemyTicks = enemyTravel + (ctx.mySpawn.hits ?: SPAWN_HITS) / enemyDps
        return ourTicks + 10.0 < enemyTicks
    }

    /** Лечение и стрельба за один проход (см. spawn-strike: heal вплотную совместим со стрельбой,
     *  rangedHeal — нет; лечение распределяется по потребности с учётом входящего урона). */
    private fun healAndShoot(active: List<Creep>, allies: List<Creep>, enemyCreeps: List<Creep>, enemySpawn: StructureSpawn?, focusTarget: Creep?, stormSpawn: Boolean, wallTarget: StructureWall? = null) {
        val healDone = HashMap<String, Int>()
        val incoming = HashMap<String, Int>()
        fun need(target: Creep): Int {
            val deficit = target.hitsMax - target.hits
            val expected = incoming.getOrPut(target.id) { InfluenceMap.damageAt(target.x, target.y, enemyCreeps).toInt() }
            return deficit + expected - (healDone[target.id] ?: 0)
        }
        for (creep in active) {
            strike(creep, enemyCreeps, enemySpawn, focusTarget, wallTarget) // мили — отдельный пайплайн, совместим со стрельбой
            val healParts = creep.body.count { it.type == HEAL && it.hits > 0 }
            if (healParts > 0) {
                val candidates = allies.filter { !it.spawning && need(it) > 0 && creep.getRangeTo(it) <= HEAL_RANGE }
                val closeTarget = candidates.filter { creep.getRangeTo(it) <= 1 }.maxByOrNull { need(it) }
                if (closeTarget != null) {
                    creep.heal(closeTarget)
                    healDone[closeTarget.id] = (healDone[closeTarget.id] ?: 0) + healParts * HEAL_POWER
                    shoot(creep, enemyCreeps, enemySpawn, focusTarget, stormSpawn, wallTarget)
                    continue
                }
                val farTarget = candidates.filter { it.hitsMax - it.hits > 0 }.maxByOrNull { need(it) }
                if (farTarget != null) {
                    creep.rangedHeal(farTarget)
                    healDone[farTarget.id] = (healDone[farTarget.id] ?: 0) + healParts * RANGED_HEAL_POWER
                    continue
                }
            }
            shoot(creep, enemyCreeps, enemySpawn, focusTarget, stormSpawn, wallTarget)
        }
    }

    private fun shoot(creep: Creep, enemyCreeps: List<Creep>, enemySpawn: StructureSpawn?, focusTarget: Creep?, stormSpawn: Boolean, wallTarget: StructureWall? = null) {
        if (!hasRanged(creep)) return
        val creepsInRange = enemyCreeps.filter { creep.getRangeTo(it) <= RANGED_RANGE }
        val spawnInRange = enemySpawn != null && creep.getRangeTo(enemySpawn) <= RANGED_RANGE
        if (creepsInRange.isEmpty() && !spawnInRange) {
            // стрелять не по кому — добиваем стену пролома, если она в дальности
            if (wallTarget != null && creep.getRangeTo(wallTarget) <= RANGED_RANGE) creep.rangedAttack(wallTarget)
            return
        }

        // массовый выстрел считается по БОЕВЫМ целям (и спавну): чужие хаулеры в упор поднимали
        // massValue выше единицы, и боец бил по площади — 1 урона за часть по стрелку на 3, вместо
        // 10 одиночным. Дуэль M5R5 против M3R3 в их коридоре хаулеров: 17 выстрелов получил, 11 нанёс (матч 6)
        val combatInRange = creepsInRange.filter { c -> val p = InfluenceMap.profileOf(c); p.melee + p.ranged + p.heal > 0.0 }
        val massPool = if (combatInRange.isNotEmpty()) combatInRange else creepsInRange
        var massValue = massPool.sumOf { InfluenceMap.rangedRate(creep.getRangeTo(it)) }
        if (spawnInRange) massValue += InfluenceMap.rangedRate(creep.getRangeTo(enemySpawn!!))

        // штурм: спавн — пока в дальности нет боевых крипов (они стреляют, спавн — нет; симуляция осады
        // считает так же: защитники первыми, затем спавн), носильщики башни огня не отвлекают
        if (stormSpawn && spawnInRange && combatInRange.isEmpty()) {
            creep.rangedAttack(enemySpawn!!)
            return
        }
        if (massValue > 1.0) {
            creep.rangedMassAttack()
        } else {
            val target = when {
                stormSpawn && spawnInRange -> enemySpawn
                focusTarget != null && creep.getRangeTo(focusTarget) <= RANGED_RANGE -> focusTarget
                creepsInRange.isNotEmpty() -> creepsInRange.minWithOrNull(compareByDescending<Creep> { InfluenceMap.profileOf(it).heal }.thenBy { it.hits })
                else -> enemySpawn
            }
            target?.let { creep.rangedAttack(it) }
        }
    }

    /** Лучший одиночный шаг по оценке клеток (см. scoreCell); занятые своими клетки — обтекаем,
     *  а если свободных приближающих нет — толкаем своего через TrafficManager. */
    private fun bestSingleMove(
        creep: Creep,
        target: Position,
        flow: IntArray,
        standoff: Int,
        aggressive: Boolean,
        inCombat: Boolean,
        breaching: Boolean,
        enemyCreeps: List<Creep>,
        allies: List<Creep>,
        meleeEnemies: List<Creep>,
        blockedSet: Set<Int>,
        enemyPositions: Set<Int>,
        occupantAt: Map<Int, Creep>,
    ): Position? {
        var bestScore = scoreCell(creep, creep.x, creep.y, target, flow, standoff, aggressive, inCombat, breaching, enemyCreeps, allies, meleeEnemies)
        var bx = creep.x; var by = creep.y
        val hereDist = flow[creep.x * 100 + creep.y]
        var pushDist = if (hereDist >= 0) hereDist else Int.MAX_VALUE
        var pushX = -1; var pushY = -1
        // приближающая клетка занята своим, который СТОИТ (не просил шага) или мы уже застряли за ним:
        // толкать бесполезно — обходим. Колонна из семи бойцов 500 тиков стояла за одним замершим (02.09)
        var blockedByStatic = false
        val stuck = TrafficManager.isStuck(creep.id)
        for ((dx, dy) in DIRECTIONS) {
            if (dx == 0 && dy == 0) continue
            val x = creep.x + dx; val y = creep.y + dy
            if (!passable(x, y, blockedSet, enemyPositions)) continue
            val occ = occupantAt[x * 100 + y]
            if (occ != null) {
                val fd = flow[x * 100 + y]
                val static = TrafficManager.wasStatic(occ.id) || !canMove(occ)
                if (fd in 0 until (if (hereDist >= 0) hereDist else Int.MAX_VALUE) && (static || stuck)) blockedByStatic = true
                else if (!inCombat && fd in 0 until pushDist) { pushDist = fd; pushX = x; pushY = y }
                continue
            }
            val s = scoreCell(creep, x, y, target, flow, standoff, aggressive, inCombat, breaching, enemyCreeps, allies, meleeEnemies)
            if (s > bestScore) { bestScore = s; bx = x; by = y }
        }
        if (bx != creep.x || by != creep.y) return InfluenceMap.cell(bx, by)
        if (pushX >= 0) return InfluenceMap.cell(pushX, pushY)
        if (blockedByStatic && hereDist >= 0) {
            // обход: свободная соседняя клетка не дальше от цели, чем один болотный шаг
            var dx0 = 0; var dy0 = 0; var best = hereDist + DistanceMap.SWAMP_COST
            for ((dx, dy) in DIRECTIONS) {
                if (dx == 0 && dy == 0) continue
                val x = creep.x + dx; val y = creep.y + dy
                if (!passable(x, y, blockedSet, enemyPositions) || occupantAt.containsKey(x * 100 + y)) continue
                val fd = flow[x * 100 + y]
                if (fd in 0..best && (dx0 == 0 && dy0 == 0 || fd < best)) { best = fd; dx0 = dx; dy0 = dy }
            }
            if (dx0 != 0 || dy0 != 0) return InfluenceMap.cell(creep.x + dx0, creep.y + dy0)
        }
        return null
    }

    /** Оценка клетки: приблизиться на standoff к цели по реальному пути; в бою — исходящий урон,
     *  чистый входящий (с хилом), влияние, штраф за зону мили, за болото (ловушка: 5 тиков на шаг) и
     *  цена прижатия — тики без хода на этой клетке × огонь по ней. */
    private fun scoreCell(creep: Creep, x: Int, y: Int, target: Position, flow: IntArray, standoff: Int, aggressive: Boolean, inCombat: Boolean, breaching: Boolean, enemyCreeps: List<Creep>, allies: List<Creep>, meleeEnemies: List<Creep>): Double {
        val flowDist = flow[x * 100 + y]
        val cheb = getRange(InfluenceMap.cell(x, y), target)
        val firePenalty = when {
            cheb <= standoff -> (standoff - cheb) * 0.5
            flowDist < 0 -> 1000.0
            flowDist > standoff -> (flowDist - standoff).toDouble()
            else -> (standoff - flowDist) * 0.5
        }
        val separation = allies.count { !it.spawning && (it.x != x || it.y != y) && getRange(InfluenceMap.cell(x, y), it) <= SEPARATION_RADIUS } * PAIR_W_SPREAD
        // башня не кайтится и по дороге не добивается: её средний урон штрафуем всегда (и без боя, и при
        // перевесе), чтобы осаждающие вставали на дальней от неё стороне спавна (выстрел слабеет на 50
        // за клетку); без перевеса он ещё и внутри damage
        val towerTerm = InfluenceMap.towerSustainedAt(x, y) * PAIR_W_DAMAGE
        if (!inCombat) return -firePenalty * PAIR_W_DIST - separation - towerTerm

        val damage = InfluenceMap.netDamageAt(x, y, enemyCreeps, allies)
        // сам мили (бурильщик, гарнизон): зона мили врага — его рабочее место, а не угроза, и влияние
        // врага его не отталкивает; удар доходит только вплотную. Матч 10: бурильщик сорок тиков стоял
        // в трёх клетках от M5A3, бившего наш спавн, — штраф за зону мили (−15) и влияние (−9) съедали
        // выигрыш шага (10), и «лучшей клеткой» была своя
        val meleeSelf = isMelee(creep) && !hasRanged(creep)
        val meleeWeight = if (aggressive) PAIR_W_MELEE * AGGRO_MELEE_FACTOR else PAIR_W_MELEE
        val meleeThreat = if (meleeSelf) 0.0 else meleeEnemies.count { getRange(InfluenceMap.cell(x, y), it) <= MELEE_KEEP_RANGE } * meleeWeight
        // болото — ловушка только БЕЗ перевеса: при перевесе размен наш и на болоте (дальность у обоих
        // 3, отступивший за 4 сам выходит из боя). Плоский штраф 40 превышал выигрыш поля потока на
        // границе равнина→болото (1 тик = 10) — боец замирал на кромке в пяти клетках от врага (02.09)
        val swampPenalty = if (!aggressive && !breaching && DistanceMap.isSwamp(x, y)) PAIR_W_SWAMP else 0.0
        val influence = if (meleeSelf && aggressive) 0.0 else InfluenceMap.influenceAt(x, y, allies, enemyCreeps)
        val outgoingWeight = if (breaching) PAIR_W_OUTGOING_BREACH else PAIR_W_OUTGOING
        // без перевеса исходящий урон стоит чего-то только там, где входящего нет (клетка вне досягаемости
        // врага и его шага, см. damageAt): размен проигран по счёту, и каждый обмен — чистая потеря, а
        // бесплатный выстрел по медленной мили остаётся бесплатным. С весом 30 за единицу массового
        // выстрела (10 за врага вплотную) он тянул бойца «без перевеса» в середину шара: свежий M8R4 шёл с
        // поста на четверых с двумя лекарями при −18 за урон и +840 за «исходящий», и так девять подряд
        // (матч 13, t=1390-1660). Стрельба от этого не зависит — shoot бьёт всё в дальности; здесь только шаг
        val outgoing = if (!aggressive && damage > 0.0) 0.0 else if (meleeSelf) (if (enemyCreeps.any { getRange(InfluenceMap.cell(x, y), it) <= 1 }) 1.0 else 0.0) else outgoingValue(x, y, enemyCreeps)
        // при локальном перевесе входящий урон НЕ штрафуем: размен наш, а штраф держал бойцов на
        // дистанции 4-5, где стрелять нельзя, пока враг шагал на 3 и фокусил переднего (бой 02.09:
        // 130 урона/тик против 80 — и проигран, убив двоих). Условие «influence >= 0» не спасало:
        // у клетки в досягаемости роя влияние отрицательное при любом перевесе по сумме.
        // Отступление при смертельном уроне остаётся за mustFlee.
        val damageTerm = if (aggressive) 0.0 else damage * PAIR_W_DAMAGE
        // прижатие: лишние тики без хода на клетке (усталость по телу и местности, см. periodAt) ×
        // фактический огонь по ней — как урон, при любом перевесе: прижатый не кайтит, не уходит и
        // напарникам не помогает (матч 7: f32 в болоте на (49,6) отдал 470 хитов одному M3R3, а
        // клетка равнины на дистанции 3 была рядом)
        // башня в прижатии не участвует: её огонь одинаков и на месте, и в болоте, а штраф считал
        // только клетку шага — волна замирала на кромке болота в четырнадцати клетках от башни (стенд)
        val pinned = (periodAt(creep, x, y) - 1) * (InfluenceMap.fireAt(x, y, enemyCreeps) - InfluenceMap.towerSustainedAt(x, y)) * PAIR_W_DAMAGE
        val towerAggro = if (aggressive) towerTerm else 0.0
        return -firePenalty * PAIR_W_DIST - damageTerm + influence * PAIR_W_INFLUENCE +
            outgoing * outgoingWeight - meleeThreat - separation - swampPenalty - pinned - towerAggro
    }

    private fun outgoingValue(x: Int, y: Int, enemyCreeps: List<Creep>): Double {
        var massValue = 0.0
        var anyInRange = false
        for (enemy in enemyCreeps) {
            val d = getRange(InfluenceMap.cell(x, y), enemy)
            if (d <= RANGED_RANGE) { anyInRange = true; massValue += InfluenceMap.rangedRate(d) }
        }
        if (!anyInRange) return 0.0
        return maxOf(massValue, 1.0)
    }

    private fun passable(x: Int, y: Int, blockedSet: Set<Int>, enemyPositions: Set<Int>): Boolean {
        if (x < 0 || y < 0 || x > 99 || y > 99) return false
        val key = x * 100 + y
        if (key in blockedSet || key in enemyPositions) return false
        return getTerrainAt(InfluenceMap.cell(x, y)) != TERRAIN_WALL
    }

    private fun hasRanged(creep: Creep) = creep.body.any { it.type == RANGED_ATTACK && it.hits > 0 }

    /**
     * Мощь группы по Ланчестеру: √(чистый урон в тик × суммарные хиты). Кто из двух групп при
     * сосредоточенном огне побеждает, решает произведение урон×здоровье, а не урон: T10M4R3H1 (30
     * урона, 1800 HP, лечит 12) в дуэли бьёт наш M5R5 (50 урона, 1000 HP) — по «урону» он слабее в
     * полтора раза. Корень делает меру линейной по численности (две одинаковые группы — вдвое), так
     * что PUSH_RATIO и DEFEND_MARGIN сохраняют смысл. Лечение противника вычитается из нашего урона.
     */
    private fun lanchester(dps: Double, enemyHeal: Double, hits: Int): Double =
        sqrt(maxOf(0.0, dps - enemyHeal) * hits.coerceAtLeast(0))

    private fun swampPeriod(creep: Creep) = periodOn(bodyWeight(creep), liveMoves(creep), 10)

    /**
     * Доля удара мили, которая ДОЙДЁТ до противников. Кайт-дисконт MELEE_KITE_DISCOUNT — только когда
     * противники сплошь стрелки, никто не прижат вплотную и мили медленнее каждого из них на болоте
     * (на равнине все ходят клетку за тик, разница — в болоте, см. periodOn): бурильщик за 180 «мощи»
     * раздул перевес и погиб в поле, не догнав ни одного стрелка. Иначе — полный удар: мили против
     * мили сойдутся сами; шар M5A1H1 (болото 2 тика/клетка) быстрее нашего M8R4 (3) и догоняет;
     * мили вплотную к спавну бьёт структуру, которая не кайтит. Матч 9: пять мили у нашего спавна
     * шли с дисконтом 0.1 — «гарнизон держит», и армия ушла волной от горящего спавна.
     */
    private fun meleeFactor(unit: Creep, opponents: List<Creep>, structure: Position?): Double {
        if (opponents.any { hasMelee(it) || getRange(unit, it) <= MELEE_KEEP_RANGE }) return 1.0
        if (structure != null && getRange(unit, structure) <= 1) return 1.0
        val ranged = opponents.filter { hasRanged(it) }
        if (ranged.isEmpty()) return 1.0
        val mine = swampPeriod(unit)
        return if (ranged.any { swampPeriod(it) > mine }) 1.0 else MELEE_KITE_DISCOUNT
    }

    /** Действенный урон крипа в тик против группы: стрельба целиком, мили — по meleeFactor. */
    private fun effectiveDps(unit: Creep, opponents: List<Creep>, structure: Position?): Double {
        val p = InfluenceMap.profileOf(unit)
        return p.ranged + p.melee * meleeFactor(unit, opponents, structure)
    }

    /** НАША мощь против группы врага (см. meleeFactor). */
    /** Хиты бойца в счёте мощи — по доле удара, которая ДОЙДЁТ (см. meleeFactor): мили, которого кайтят,
     *  в бою не участвует, и его хиты — не ресурс. Бурильщик на 1200 хитов с дисконтом 0.1 давал
     *  √(28·1500) против √(20·1200) — «гарнизон держит», и он 150 тиков бегал за двумя M5R1, не ударив
     *  ни разу (матч 12). */
    private fun weightedHits(unit: Creep, opponents: List<Creep>, structure: Position?): Double {
        val p = InfluenceMap.profileOf(unit)
        val raw = p.ranged + p.melee
        if (raw <= 0.0) return unit.hits.toDouble()
        return unit.hits * effectiveDps(unit, opponents, structure) / raw
    }
    private fun ourPowerOf(ours: List<Creep>, theirs: List<Creep>): Double {
        val dps = ours.sumOf { effectiveDps(it, theirs, null) }
        val heal = theirs.sumOf { InfluenceMap.profileOf(it).heal }
        return lanchester(dps, heal, ours.sumOf { weightedHits(it, theirs, null) }.toInt())
    }

    /** Мощь врага против нашей группы (см. meleeFactor; мили у нашего спавна — в полную силу). */
    private fun enemyPowerOf(theirs: List<Creep>, ours: List<Creep>): Double {
        val dps = theirs.sumOf { effectiveDps(it, ours, homeSpawnPos) }
        val heal = ours.sumOf { InfluenceMap.profileOf(it).heal }
        return lanchester(dps, heal, theirs.sumOf { weightedHits(it, ours, homeSpawnPos) }.toInt())
    }


    /** Закроет ли это тело дефицит обороны вместе с нынешними защитниками (по Ланчестеру с лечением врага). */
    private fun closesDeficit(body: Array<BodyPartType>, defenders: List<Creep>, threats: List<Creep>): Boolean {
        val dps = defenders.sumOf { effectiveDps(it, threats, null) } +
            body.count { it == RANGED_ATTACK } * RANGED_ATTACK_POWER + body.count { it == ATTACK } * ATTACK_POWER
        val hits = defenders.sumOf { weightedHits(it, threats, null) } + body.size * 100
        val heal = threats.sumOf { InfluenceMap.profileOf(it).heal }
        return lanchester(dps, heal, hits.toInt()) >= enemyPowerOf(threats, defenders) * DEFEND_MARGIN
    }

    /**
     * Через сколько тиков откроется контейнер пролома: живой бурильщик — его ход до текущей стены по
     * полю (болото по его телу) плюс ломка остатка живыми ATTACK; рождающийся — остаток рождения и ход
     * от спавна; без бурильщика — рождение оптимального тела под бюджет, ход от спавна и ломка всех стен.
     * Пролом уже открыт — 0; ломать некому и не на что — «никогда».
     */
    private fun breachOpenIn(ctx: Ctx, breach: BreachPlan, budget: Int): Int {
        val wall = breach.current() ?: return 0
        val hits = breach.totalHits
        val field = flowTo(ctx, wall)
        val breacher = ctx.myCreeps.filter { isMelee(it) }.minByOrNull { getRange(it, wall) }
        val attacks: Int
        val born: Int
        val walk: Int
        if (breacher != null) {
            attacks = breacher.body.count { it.type == ATTACK && it.hits > 0 }
            born = if (breacher.spawning) ctx.mySpawn.spawning?.remainingTime ?: 0 else 0
            val fromSpawn = flowNear(field, ctx.mySpawn.x, ctx.mySpawn.y)
            walk = if (breacher.spawning) (if (fromSpawn < 0) breach.steps else field[fromSpawn])
            else pathTicks(breacher, field, breacher.x * 100 + breacher.y)
        } else {
            val k = plannedBreacherBlocks(budget, hits)
            if (k <= 0) return Int.MAX_VALUE / 4
            attacks = k
            born = 2 * k * CREEP_SPAWN_TIME
            val fromSpawn = flowNear(field, ctx.mySpawn.x, ctx.mySpawn.y)
            walk = if (fromSpawn < 0) breach.steps else field[fromSpawn]
        }
        if (attacks <= 0 || walk >= Int.MAX_VALUE / 4) return Int.MAX_VALUE / 4
        return born + walk + (hits + attacks * ATTACK_POWER - 1) / (attacks * ATTACK_POWER)
    }

    /**
     * Через сколько тиков дебют «бурильщик первым» даст ПОЛНОГО бойца — прогон политики самого спавна
     * (см. spawnIfNeeded) по тикам от нынешнего состояния: бурильщик (если его нет) из энергии спавна,
     * контейнер пролома становится точкой флота через breachOpenIn, хаулеры покупаются, пока их очередь
     * (HAULER_LEAD), есть что возить и прогноз притока ниже целевого, остальное копится на полное тело.
     * Враг, который придёт раньше, встречает пустой спавн — тогда боец из стартовой энергии, а пролом
     * после (матч 12). Прежняя формула складывала ломку с накоплением по ЦЕЛЕВОМУ притоку (27/тик)
     * сразу после пролома, а флот в тот момент — один хаулер за 200 с притоком 5 и ходом к угловому
     * контейнеру: «страж к 167-му» против прихода врага на 168-й, и на 167-м спавн держал 278 энергии
     * без единого стрелка (матч 14).
     */
    private fun breachGuardReady(ctx: Ctx, breach: BreachPlan, budget: Int, trace: StringBuilder? = null): Int {
        val breacherAlive = ctx.myCreeps.any { isMelee(it) }
        val k = plannedBreacherBlocks(budget, breach.totalHits).coerceAtLeast(1)
        val breacherCost = if (breacherAlive) 0 else k * (cost(MOVE) + cost(ATTACK))
        val open = breachOpenIn(ctx, breach, budget)
        val fighter = fighterBody(SPAWN_ENERGY_CAPACITY)
        val fighterCost = fighter.sumOf { cost(it) }
        val regen = if (regenSamples > 0) regenSum.toDouble() / regenSamples else 1.0
        val target = targetIncome()
        val points = fleetPoints(ctx, usableSites(ctx))
        val breachPoint = (breach.container.store[RESOURCE_ENERGY] ?: 0) to breach.trip
        val horizon = arenaInfo.ticksLimit - getTicks()
        var energy = (budget - breacherCost).toDouble() + ctx.haulers.sumOf { it.store[RESOURCE_ENERGY] ?: 0 }
        var fleet = ctx.haulers.sumOf { capacityOf(it) }
        var haulers = ctx.myCreeps.count { c -> c.body.any { it.type == CARRY } }
        var spentH = spentHaulers
        var spentF = spentFighters + breacherCost
        var busyUntil = if (breacherAlive) 0 else 2 * k * CREEP_SPAWN_TIME
        var income = 0.0
        var bound = false
        var incomeFleet = -1
        for (t in 0 until horizon) {
            if (fleet != incomeFleet || t == open) {
                val pts = if (t >= open) points + breachPoint else points
                income = incomeOf(pts, fleet)
                bound = capacityBound(pts, fleet, HAULER_BLOCKS_MIN * CARRY_CAPACITY)
                incomeFleet = fleet
            }
            energy += regen + income
            if (trace != null && t % 50 == 0) trace.append(" $t:e=${energy.toInt()}/f=$fleet/i=${income.toInt()}/h=$haulers/sp=$spentH")
            if (t < busyUntil) continue
            if (haulers < MAX_HAULERS && bound && income < target && spentH <= spentF + HAULER_LEAD) {
                val blocks = minOf(HAULER_BLOCKS_MAX, (energy / blockCost()).toInt())
                if (blocks >= HAULER_BLOCKS_MIN) {
                    energy -= blocks * blockCost()
                    spentH += blocks * blockCost()
                    haulers++
                    fleet += blocks * CARRY_CAPACITY
                    busyUntil = t + 2 * blocks * CREEP_SPAWN_TIME
                }
                continue // очередь хаулера: копим на него, не на бойца
            }
            if (energy >= fighterCost) return t + fighter.size * CREEP_SPAWN_TIME
        }
        return horizon
    }

    /** Тики ХОДА крипа по спуску вдоль поля потока от клетки до цели — по его телу и местности (periodAt):
     *  поле взвешено болотом ×5, а M5R1 идёт по болоту клетку за тик, и поле завышало его приход втрое. */
    private fun pathTicks(creep: Creep, flow: IntArray, startCell: Int): Int {
        var cell = startCell
        if (cell < 0 || flow[cell] < 0) return Int.MAX_VALUE / 2
        var ticks = 0
        var steps = 0
        while (flow[cell] > 0 && steps < 400) {
            val cx = cell / 100
            val cy = cell % 100
            var best = -1
            var bestFlow = flow[cell]
            for (dx in -1..1) for (dy in -1..1) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
                val f = flow[nx * 100 + ny]
                if (f in 0 until bestFlow) { bestFlow = f; best = nx * 100 + ny }
            }
            if (best < 0) break
            cell = best
            steps++
            ticks += periodAt(creep, cell / 100, cell % 100)
        }
        return ticks
    }

    /** Ближайшая к клетке проходимая клетка поля (сама клетка структуры в поле −1). */
    private fun flowNear(flow: IntArray, x: Int, y: Int): Int {
        var best = -1
        for (dx in -1..1) for (dy in -1..1) {
            val nx = x + dx
            val ny = y + dy
            if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
            val c = nx * 100 + ny
            if (flow[c] >= 0 && (best < 0 || flow[c] < flow[best])) best = c
        }
        return best
    }

    /** Догонит ли наш мили эту цель: она сама мили, уже в двух клетках, медленнее нас на болоте или
     *  обездвижена. Иначе это кайтер — за ним не ходим, ждём у поста, пока подойдёт (матч 12). */
    /** Мили догонит цель: та сама мили, вплотную, обездвижена, медленнее нас на болоте — или НЕ ОТСТУПАЕТ:
     *  за прошлый тик не отошла от нас (стоит или идёт навстречу). Два M5R1 полсотни тиков стояли в трёх
     *  клетках от бурильщика M6A6, били его и спавн, и он не сделал ни шага: «стрелок быстрее — не
     *  догнать», а шаг к нему в оценке клетки виден только вплотную (матч 14). Кайтящий отходит — и
     *  на следующем тике снова «не догнать», мили возвращается на пост. */
    /** Контакт: кто-то из врагов достаёт до кого-то из наших за шаг (дистанция стрельбы плюс клетка). */
    private fun inContact(enemies: List<Creep>, ours: List<Creep>): Boolean =
        enemies.any { e -> ours.any { getRange(e, it) <= RANGED_RANGE + 1 } }

    private fun catchable(unit: Creep, target: Creep): Boolean =
        hasMelee(target) || getRange(unit, target) <= MELEE_KEEP_RANGE || !canMove(target) || swampPeriod(target) > swampPeriod(unit) ||
            !retreating(unit, target)

    /** Цель за прошлый тик увеличила дистанцию до нас: её прежняя клетка (enemyPrevCell) была ближе. */
    private fun retreating(unit: Creep, target: Creep): Boolean {
        val prev = enemyPrevCell[target.id] ?: return false
        return getRange(unit, InfluenceMap.cell(prev / 100, prev % 100)) < getRange(unit, target)
    }

    private fun pathStep(creep: Creep, target: Position, range: Int, dangerMatrix: CostMatrix): Position? {
        val goal = SearchGoal(pos = target, range = range)
        val result = searchPath(creep, goal, SearchPathOptions(costMatrix = dangerMatrix))
        return result.path.firstOrNull()
    }

    /** Шаг из-под огня кормленных башен врага: бегство от их клеток за предел дальности выстрела. */
    private fun towerEdgeStep(creep: Creep, ctx: Ctx): Position? {
        val goals = ctx.enemyTowers.filter { it.fed }.map { SearchGoal(pos = InfluenceMap.cell(it.pos.x, it.pos.y), range = InfluenceMap.towerFalloffRange.toInt()) }.toTypedArray()
        if (goals.isEmpty()) return null
        return searchPath(creep, goals, SearchPathOptions(flee = true, costMatrix = ctx.dangerMatrix)).path.firstOrNull()
    }

    private fun fleeStep(creep: Creep, enemies: List<Creep>, dangerMatrix: CostMatrix): Position? {
        if (enemies.isEmpty()) return null
        val goals = enemies.map { e -> SearchGoal(pos = InfluenceMap.cell(e.x, e.y), range = RANGED_RANGE) }.toTypedArray()
        val result = searchPath(creep, goals, SearchPathOptions(flee = true, costMatrix = dangerMatrix))
        return result.path.firstOrNull()
    }

    // ==================== диагностика ====================

    /** Печатаем список точек энергии только при изменении состава (появление/исчезновение). */
    private fun logSites(sites: List<EnergySite>) {
        if (!DEBUG_LOG) return
        val key = sites.joinToString(",") { it.id }
        if (key == lastSitesKey) return
        lastSitesKey = key
        println("sites t=${getTicks()}: " + sites.joinToString(" ") {
            "${if (it.container != null) "C" else "R"}(${it.pos.x},${it.pos.y})e=${it.energy}${it.ticksToDecay?.let { d -> "d=$d" } ?: ""}${if (it.ours) "*" else ""}${if (it.safe) "" else "!"}"
        })
    }

    /** Реген спавна: средний прирост энергии за тик по тикам, когда спавн не рожает и рядом нет
     *  сдающего хаулера (его transfer исказил бы замер). Печатается на 100-м тике. */
    private fun measureRegen(spawn: StructureSpawn, deliveringNearby: Boolean) {
        val e = spawn.store[RESOURCE_ENERGY] ?: 0
        // действия применяются в КОНЦЕ тика: сдача, начатая на прошлом тике, видна в энергии сейчас
        // полный спавн прироста не показывает: первая проба на 1000/1000 давала ноль, и до второй пробы
        // оценка регенерации была 0.0 — прогон дебюта (breachGuardReady) не рос ни на единицу (стенд freeze)
        if (lastSpawnEnergy in 0 until SPAWN_ENERGY_CAPACITY && spawn.spawning == null && !deliveringNearby && !deliveringLastTick && getTicks() <= 100) {
            val d = e - lastSpawnEnergy
            if (d >= 0) { regenSamples++; regenSum += d }
        }
        deliveringLastTick = deliveringNearby
        lastSpawnEnergy = e
        if (DEBUG_LOG && getTicks() == 100) {
            println("spawn regen estimate: ${if (regenSamples > 0) regenSum.toDouble() / regenSamples else -1.0} per tick over $regenSamples clean samples")
        }
    }

    /** ASCII-карта один раз: '#' стена, '~' болото, '.' равнина, 'M'/'E' спавны, 'C' контейнер, 'x' структура. */
    private fun logMap() {
        val marks = HashMap<Int, Char>()
        fun mark(x: Int, y: Int, c: Char) { marks[x * 100 + y] = c }
        getObjectsByPrototype(StructureWall::class).forEach { mark(it.x, it.y, '#') }
        getObjectsByPrototype(StructureRampart::class).forEach { mark(it.x, it.y, 'R') }
        getObjectsByPrototype(StructureExtension::class).forEach { mark(it.x, it.y, 'x') }
        getObjectsByPrototype(StructureTower::class).forEach { mark(it.x, it.y, 'T') }
        getObjectsByPrototype(StructureContainer::class).forEach { mark(it.x, it.y, 'C') }
        getObjectsByPrototype(StructureSpawn::class).forEach { mark(it.x, it.y, if (it.my == true) 'M' else 'E') }

        var swamp = 0
        var wall = 0
        println("=== MAP (rows y=0..99, cols x=0..99) ===")
        for (y in 0..99) {
            val row = StringBuilder()
            for (x in 0..99) {
                val terrain = getTerrainAt(InfluenceMap.cell(x, y))
                if (terrain == TERRAIN_SWAMP) swamp++
                if (terrain == TERRAIN_WALL) wall++
                val structure = marks[x * 100 + y]
                row.append(
                    when {
                        structure != null -> structure
                        terrain == TERRAIN_WALL -> '#'
                        terrain == TERRAIN_SWAMP -> '~'
                        else -> '.'
                    }
                )
            }
            println("${y.toString().padStart(2, '0')}:$row")
        }
        println("=== END MAP swamp=$swamp wall=$wall plain=${10000 - swamp - wall} ===")
    }
}
