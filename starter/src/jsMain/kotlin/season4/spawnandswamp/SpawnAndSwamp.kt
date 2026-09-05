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
import screeps.api.EXTENSION_ENERGY_CAPACITY
import screeps.api.EXTENSION_HITS
import screeps.api.MAX_CONSTRUCTION_SITES
import screeps.api.OBSTACLE_OBJECT_TYPES
import screeps.api.RAMPART_HITS
import screeps.api.createConstructionSite
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
    /** Версия бота: печатается первой строкой лога и привязывает матч к коду (правило 5 в CLAUDE.md).
     *  Растёт на каждую правку поведения, которая уходит в живой матч. */
    private const val BOT_VERSION = 30

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

    // ---------- стройка ----------
    /** Кольцо, в котором ставится башня: ровно вторая клетка от спавна. Ближе — занимает клетку, на
     *  которую выходят новорождённые; дальше — теряет по 50 урона за клетку по всем, кто бьёт спавн,
     *  и между башней и спавном не остаётся клетки, с которой смотритель достаёт до обоих. */
    private const val TOWER_RING = 2

    /** Дальность стройки в движке: строить можно с трёх клеток. */
    private const val BUILD_RANGE = 3

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

    /** Наши живые башни в этом тике — их огонь входит в счёт мощи (см. ourTowerDps). */
    private var myTowers: List<StructureTower> = emptyList()

    /** Тики, когда враг стоял ПОД ВЫСТРЕЛОМ башни (в TOWER_FALLOFF_RANGE от спавна), за окно
     *  PRODUCTION_WINDOW. Это КПД башни, замеренный её же геометрией: в матче 26 бой шёл в тридцати
     *  пяти клетках от спавна, тревога держалась 81%, а башня не достала бы ни до кого. */
    private val homeFightTicks = ArrayDeque<Int>()

    /** Дом в критическом положении в прошлом тике: враг бьёт спавн и гарнизон не держит. Пока так,
     *  каждая единица в спавне принадлежит бойцу, и смотритель из спавна не берёт. */
    private var lastHomeCritical = false

    /** РАЗМЕН за последнее окно: чистая потеря хитов по обе стороны, по тикам. Чистая — значит с
     *  вычетом лечения: отбитый и залеченный хит прогрессом не является, а именно им счёт и обманывается.
     *  Погибший считается своими последними хитами целиком. Окно, а не «с начала боя»: враг шагает в
     *  кольцо тревоги и обратно каждые несколько тиков, и счёт «с начала» стирался раньше, чем успевал
     *  накопиться (матч 34: x100/56 → x0/0 → x20/144, а между этими строками мы отдали двух бойцов). */
    private val ourHitsSeen = HashMap<String, Int>()
    private val theirHitsSeen = HashMap<String, Int>()
    private val ourLostWindow = ArrayDeque<Pair<Int, Double>>()
    private val theirLostWindow = ArrayDeque<Pair<Int, Double>>()

    /** Потрачено на стройку (смотритель): считается отдельно от бойцов — иначе замер смертности
     *  бойцов (survivalOfFighters) припишет им энергию, которая в бой и не шла. */
    private var spentBuild = 0

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
    /** Сдача в спавн по тикам за окно PRODUCTION_WINDOW: (тик, сколько сдано). Прогноз притока говорит,
     *  сколько флот МОГ БЫ возить; это — сколько он ВОЗИТ на самом деле. Матч 22 (05.09.2026): по замеру
     *  повтора противник всадил 133 выстрела в наши безоружные хаулеры, приток упал с 22 до 4, а прогноз
     *  всё это время видел на земле 5-9 тысяч и звал покупать ещё — флот вырос до одиннадцати. */
    private val delivered = ArrayDeque<Pair<Int, Int>>()
    private val haulerStore = HashMap<String, Int>()
    private var firstHaulerTick = -1

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
        val builders: List<Creep>,      // крипы с WORK: площадка и кормление башни, ни возка, ни бой
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
        val myTowers: List<StructureTower>,    // наши живые башни
        val mySites: List<ConstructionSite>,   // наши недостроенные площадки
    )

    fun tick() {
        val mySpawn = getObjectsByPrototype(StructureSpawn::class).firstOrNull { it.my == true } ?: return
        val enemySpawn = getObjectsByPrototype(StructureSpawn::class).firstOrNull { it.my == false && it.exists }
        siteStepsCache.clear()
        flowCache.clear()

        if (!greeted) {
            greeted = true
            println(
                "hello season4 spawn-and-swamp v$BOT_VERSION: ${arenaInfo.season} - ${arenaInfo.name} level=${arenaInfo.level} " +
                    "ticksLimit=${arenaInfo.ticksLimit} cpu=${arenaInfo.cpuTimeLimit}/${arenaInfo.cpuTimeLimitFirstTick}"
            )
            // подпись поведения: по ней лог матча читается без диффа — какие пороги решали в ЭТОМ матче
            println(
                "tuning: push=$PUSH_RATIO/$PUSH_RELEASE_RATIO defend=$DEFEND_MARGIN late=$LATE_MARGIN " +
                    "siegeLimit=$SIEGE_LIMIT cohesion=$COHESION_GAP engage=$ENGAGE_RANGE alarm=$SPAWN_ALARM_TICKS " +
                    "haulers=$HAULER_BLOCKS_MIN..$HAULER_BLOCKS_MAX/$MAX_HAULERS rounds=$FLEET_ROUNDS lead=$HAULER_LEAD"
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
            // ЧТО ЕЩЁ МОЖНО ПОСТАВИТЬ: рампарт непроходим для врага и рождается сразу с полными хитами,
            // расширения поднимают потолок тела. Печатаем то, что отдаёт САМА арена, — d.ts клиента устаревает
            // (в нём TOWER_FALLOFF_RANGE=20, а рантайм говорит 21)
            println(
                "structures: rampartHits=$RAMPART_HITS wallHits=$WALL_HITS extension=$EXTENSION_ENERGY_CAPACITY/$EXTENSION_HITS " +
                    "maxSites=$MAX_CONSTRUCTION_SITES obstacles=${JSON.stringify(OBSTACLE_OBJECT_TYPES)}"
            )
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

        // СТРОИТЕЛЬ — крип с WORK: он не возит в спавн и не воюет. Тип по телу, а не по живым частям:
        // с выбитыми WORK он всё ещё не хаулер (маршрут у него свой), и кормить башню он может дальше
        val builders = active.filter { c -> c.body.any { it.type == WORK } }
        val haulers = active.filter { c -> c.body.any { it.type == CARRY } && c.body.none { it.type == WORK } }
        val fighters = active.filter { c -> c.body.none { it.type == CARRY } && c.body.none { it.type == WORK } }
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
        myTowers = getObjectsByPrototype(StructureTower::class).filter { it.exists && it.my == true && (it.hits ?: 0) > 0 }
        val mySites = getObjectsByPrototype(ConstructionSite::class).filter { it.exists && it.my == true }
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
        val ctx = Ctx(mySpawn, enemySpawn, myCreeps, active, haulers, fighters, builders, enemyCreeps, combatEnemies, blocked, blockedForEnemy, dangerMatrix, loadedToSpawn, stepsToSpawn, enemyApproach, sites, enemyTowers, ramparts, enemyPending, pendingTowers, myTowers, mySites)

        logSites(sites)
        measureRegen(mySpawn, haulers.any { (it.store[RESOURCE_ENERGY] ?: 0) > 0 && it.getRangeTo(mySpawn) <= 1 })
        measureDelivery(ctx)
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
        measureHomeFight(ctx)
        measureExchange(ctx)
        spawnIfNeeded(ctx, defenders, threatsSoon, alarm, enemyArrival, spawnUnderFire)
        runTowers(ctx)
        runHaulers(ctx)
        runBuilders(ctx)
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
                    "sites=${sites.size} usable=${usable.sumOf { it.energy }} income=${projectedIncome(ctx, usable).toInt()}/${targetIncome().toInt()}${realisedIncome().let { if (it < 0) "" else "r" + it.toInt() }} " +
                    "push=$pushing($lastPushReason) alarm=$alarm home=$homeMode our=${ourOffense.toInt()}/${ourDefense.toInt()} enemy=${enemyPower.toInt()} pending=${enemyPending.size} arrival=${if (enemyArrival >= Int.MAX_VALUE / 4) "-" else enemyArrival.toString()} towers=${enemyTowers.count { it.fed }}/${enemyTowers.size}+${pendingTowers.size} enemySpawnHits=${enemySpawn?.hits} " +
                    "mine=${myTowers.joinToString(",") { "T(${it.x},${it.y})h=${it.hits}e=${it.store[RESOURCE_ENERGY]}" }.ifEmpty { "-" }}${ctx.mySites.joinToString("") { "+site(${it.x},${it.y})${it.progress}/${it.progressTotal}" }} home=${(homeShare() * 100).toInt()}%"
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

    /** Тело бурильщика [MOVE, ATTACK]×k под нынешний ПОТОК энергии (регенерация + приток): k минимизирует
     *  накопление недостающего + рождение + ход + ломку. Прежняя формула (k ≈ √(H/180)) считала энергию
     *  бесплатной: после дебюта «боец первым» ветка ждала тело за 980 при потоке 1/тик и пролом не открылся
     *  за весь матч (матч 17). expected — энергия в спавне и в пути. Ветка спавна, breachOpenIn и
     *  guardReadySim считают ОДНО тело. */
    private fun breacherBlocksFor(totalHits: Int, walk: Int, steps: Int, expected: Int, flow: Double, fire: Int): Int {
        var best = 0
        var bestT = breachIncomeStart(totalHits, walk, steps, expected, flow, fire, 0) // без бурильщика: стрелки на посту или никогда
        for (k in 1..MAX_CREEP_SIZE / 2) {
            val t = breachIncomeStart(totalHits, walk, steps, expected, flow, fire, k)
            if (t < bestT) { bestT = t; best = k }
        }
        return best
    }

    /** Старт притока с пролома при k блоках бурильщика: стена открыта (breachOpenAt) И минимальный хаулер
     *  рождён и дошёл до контейнера (steps) — оба из потока после expected, бурильщик первым. Цель плана —
     *  именно приток, не падение стены: без резерва на хаулера стена открылась на 350-м, а первый хаулер
     *  дошёл на 470-м (стенд stream17). k=0 — открытие огнём стрелков. */
    private fun breachIncomeStart(hits: Int, walk: Int, steps: Int, expected: Int, flow: Double, fire: Int, k: Int): Int {
        val f = maxOf(flow, 1.0)
        val block = cost(MOVE) + cost(ATTACK)
        val born = 2 * k * CREEP_SPAWN_TIME
        val wait = ceil(maxOf(0.0, (k * block - expected) / f)).toInt()
        val open = breachOpenAt(hits, fire, k, wait + born + walk)
        if (open >= Int.MAX_VALUE / 4) return open
        val haulerCost = HAULER_BLOCKS_MIN * blockCost()
        val haulerAt = maxOf(wait + born, ceil(maxOf(0.0, (k * block + haulerCost - expected) / f)).toInt())
        val haulerReady = haulerAt + 2 * HAULER_BLOCKS_MIN * CREEP_SPAWN_TIME + steps
        return maxOf(open, haulerReady)
    }

    /** Огонь наших стрелков по текущей стене пролома: живые RANGED тех, кто стоит в её дальности — боец на
     *  посту без целей бьёт стену (wallTarget в strike/shoot). Матч 17: стрелок на посту снял 3060 хитов за
     *  сто тиков, а план этого не знал и купил бурильщика за 260, открывавшего стену на 18 тиков раньше. */
    private fun wallFire(ctx: Ctx, breach: BreachPlan): Int {
        val wall = breach.current() ?: return 0
        return ctx.myCreeps.filter { !it.spawning && getRange(it, wall) <= RANGED_RANGE }
            .sumOf { c -> c.body.count { it.type == RANGED_ATTACK && it.hits > 0 } } * RANGED_ATTACK_POWER
    }

    /** Через сколько тиков падёт стена в hits: огонь стрелков fire с этого тика плюс бурильщик с attacks
     *  ATTACK, подходящий через lead тиков. Ломать некому — «никогда». */
    private fun breachOpenAt(hits: Int, fire: Int, attacks: Int, lead: Int): Int {
        val never = Int.MAX_VALUE / 4
        val byFire = if (fire > 0) (hits + fire - 1) / fire else never
        if (attacks <= 0 || lead >= never) return byFire
        if (byFire <= lead) return byFire
        val left = hits - fire * lead
        val rate = attacks * ATTACK_POWER + fire
        return lead + (left + rate - 1) / rate
    }

    /** Ход бурильщика от спавна до текущей стены пролома по полю (болото ×5). */
    private fun breachWalk(ctx: Ctx, breach: BreachPlan): Int {
        val wall = breach.current() ?: return 0
        val field = flowTo(ctx, wall)
        val fromSpawn = flowNear(field, ctx.mySpawn.x, ctx.mySpawn.y)
        return if (fromSpawn < 0) breach.steps else field[fromSpawn]
    }

    /** Прибавка притока от точки пролома для флота не меньше минимального хаулера — против нынешних точек. */
    private fun breachGain(points: List<Pair<Int, Int>>, breach: BreachPlan, fleet: Int): Double {
        val ref = maxOf(fleet, HAULER_BLOCKS_MIN * CARRY_CAPACITY)
        val point = (breach.container.store[RESOURCE_ENERGY] ?: 0) to breach.trip
        return incomeOf(points + point, ref) - incomeOf(points, ref)
    }

    private fun regenRate(): Double = if (regenSamples > 0) regenSum.toDouble() / regenSamples else 1.0

    /** Поток энергии в спавн: регенерация плюс прогноз притока флота. */
    private fun energyFlow(ctx: Ctx): Double = projectedIncome(ctx, usableSites(ctx)) + regenRate()

    /**
     * Решение по бурильщику ИЗ СОСТОЯНИЯ: тело под поток (breacherBlocksFor); стоит ли пролом своей цены —
     * прибавка притока флота от его точки за остаток матча (не больше содержимого контейнера) против цены
     * тела; и порядок с хаулером — копить на бурильщика или пустить минимального хаулера вперёд, если с его
     * притоком бурильщик доступен раньше, чем накоплением без него (после бойца за тысячу: 42 энергии при
     * потоке 1 — копить 218 тиков, через хаулера 288 → копим; при 300 — хаулер вперёд). null — пролом
     * бурильщика не стоит (контейнер за стеной не ближе угловых). Пара: блоки и «копить на него».
     */
    private fun breacherOrderOf(breach: BreachPlan, hits: Int, walk: Int, fire: Int, points: List<Pair<Int, Int>>, fleet: Int, expected: Int, flow: Double, horizon: Int): Pair<Int, Boolean>? {
        val block = cost(MOVE) + cost(ATTACK)
        val f = maxOf(flow, 1.0)
        val k = breacherBlocksFor(hits, walk, breach.steps, expected, flow, fire)
        if (k == 0) return null // стрелки на посту откроют сами — бурильщик приток не приблизит
        val breacherCost = k * block
        val wait = ceil(maxOf(0.0, (breacherCost - expected) / f)).toInt()
        val start = breachIncomeStart(hits, walk, breach.steps, expected, flow, fire, k)
        // выигрыш — против старта притока БЕЗ бурильщика (огнём стрелков), а не против «никогда»
        val base = minOf(horizon, breachIncomeStart(hits, walk, breach.steps, expected, flow, fire, 0))
        val gain = breachGain(points, breach, fleet) * (base - start)
        val container = (breach.container.store[RESOURCE_ENERGY] ?: 0).toDouble()
        if (minOf(gain, container) <= breacherCost) return null
        val haulerCost = HAULER_BLOCKS_MIN * blockCost()
        val yieldH = incomeOf(points, fleet + HAULER_BLOCKS_MIN * CARRY_CAPACITY) - incomeOf(points, fleet)
        val afterHauler = expected - haulerCost
        val viaHauler = maxOf(0.0, -afterHauler / f) + 2 * HAULER_BLOCKS_MIN * CREEP_SPAWN_TIME +
            maxOf(0.0, (breacherCost - maxOf(afterHauler, 0)) / (f + yieldH))
        return k to (wait <= viaHauler)
    }

    private fun breacherOrder(ctx: Ctx, breach: BreachPlan, usable: List<EnergySite>, energy: Int, carried: Int, flow: Double): Pair<Int, Boolean>? =
        breacherOrderOf(breach, breach.totalHits, breachWalk(ctx, breach), wallFire(ctx, breach), fleetPoints(ctx, usable),
            ctx.haulers.sumOf { capacityOf(it) }, energy + carried, flow, arenaInfo.ticksLimit - getTicks())

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
        val breachOpen = if (breach != null && breachWall != null) breachOpenIn(ctx, breach, mySpawn.store[RESOURCE_ENERGY] ?: 0, energyFlow(ctx)) else Int.MAX_VALUE / 4
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
        val allHaulers = ctx.myCreeps.count { c -> c.body.none { it.type == WORK } && c.body.any { it.type == CARRY && it.hits > 0 } }

        // ПРОЛОМ: контейнер за стеной у спавна (5000 в девяти клетках против 2500 в сорока восьми) —
        // мили-бурильщик первым: ATTACK бьёт структуры впятеро дешевле RANGED. Хаулеру оставляем
        // минимальное тело, чтобы он был готов к открытию.
        val income = projectedIncome(ctx, usable)
        // пол потока — регенерация спавна: при нуле хаулеров «время догнать» было бесконечным, и «боец
        // первым» либо замыкался сам на себя (стенд 02.09), либо запрещался вовсе — и против ранней атаки
        // спавн держал 262 энергии на бурильщика и хаулера (матч 12)
        val regen = regenRate()
        val flow = income + regen
        val fullBody = fighterBody(SPAWN_ENERGY_CAPACITY)
        val fullCost = fullBody.sumOf { cost(it) }
        val deficit = enemyPower * DEFEND_MARGIN - ourPower
        val breach = breachPlan(ctx)
        // СКОЛЬКО ЖИВЁТ СПАВН при нынешнем входящем уроне: 3000 хитов, делённые на выстрелы в тик.
        // Это часы для обоих правил ожидания ниже. «Придёт враг» (enemyArrival) на них не отвечает: враг,
        // который УЖЕ стоит вплотную и стреляет, никуда не «приходит», и оба правила ждали полное тело,
        // пока спавн сносили (матч 19: 904 энергии в банке, ноль крипов, снесён на 1000-м)
        val spawnFire = InfluenceMap.fireAt(spawn.x, spawn.y, threats)
        val spawnLife = if (spawnFire > 0.0) (spawn.hits ?: SPAWN_HITS) / spawnFire else Double.MAX_VALUE
        val minFighter = cost(RANGED_ATTACK) + cost(MOVE)
        // БОЕЦ ПЕРВЫМ — держать энергию под полное тело, не покупая ничего, — только когда так боец
        // приходит раньше. «Держать» — полный боец из того, что в спавне и едет, при нынешнем потоке;
        // «вкладывать» — прогон политики самого спавна (бурильщик, хаулеры) до бойца из выросшего притока.
        // Прежнее правило держало всегда, пока враг приходил раньше прогона: при потоке 1/тик спавн двести
        // тиков копил на тысячу, запретив хаулеров, которые одни могли поток поднять (матч 17, 210–430:
        // ни хаулера, ни бойца, «guard by breach in 495»). Держим, если так боец успевает к приходу врага,
        // или приходит раньше, чем вложением, или недомерок из наличной энергии сам закрывает дефицит
        // (ветка бойца ниже); иначе вкладываем — враг всё равно придёт раньше бойца, и только приток даёт
        // следующего
        val holdReady = energyArrivalTicks(ctx, fullCost - energy, flow) + fullBody.size * CREEP_SPAWN_TIME
        val investReady = guardReadySim(ctx, breach, energy).toDouble()
        val closesNow = energy >= minFighter && closesDeficit(fighterBody(energy), defenders, threats)
        // тревога — тот же выбор, а не безусловный запрет: враг, вставший у ворот на тысячу тиков, держал
        // спавн на регенерации 1/тик без единого хаулера при открытом проломе в девяти клетках (стенд stream17)
        // под тревогой враг уже в SPAWN_ALARM_TICKS от спавна, даже если стоит: enemyArrival для стоящего
        // шара — «никогда», и угроза выходила несрочной (стенд tower+hover: четыре хаулера под тревогой)
        val threatIn = if (alarm) minOf(enemyArrival, SPAWN_ALARM_TICKS) else enemyArrival
        val fighterFirst = (alarm || deficit > 0.0) && threatIn < investReady &&
            (holdReady <= threatIn || holdReady < investReady || closesNow)
        if (breach != null && !alarm && !fighterFirst && ctx.myCreeps.none { isMelee(it) }) {
            val order = breacherOrder(ctx, breach, usable, energy, carried, flow)
            // бурильщик под поток — и копим на него, если по потоку он ближе, чем через хаулера; иначе
            // хаулер вперёд (ветка хаулера ниже) — после него та же проверка снова укажет на бурильщика
            if (order != null && order.second) {
                val k = order.first
                val breacherCost = k * (cost(MOVE) + cost(ATTACK))
                if (energy < breacherCost) {
                    if (DEBUG_LOG && getTicks() % 10 == 0) println("spawn: saving for breacher blocks=$k cost=$breacherCost energy=$energy carried=$carried flow=${(flow * 10).toInt() / 10.0} hold=${holdReady.toInt()} invest=${investReady.toInt()}")
                    return
                }
                val r = spawn.spawnCreep(breacherBody(k))
                if (r.error == null) spentFighters += breacherCost
                if (DEBUG_LOG) {
                    val trace = StringBuilder()
                    val sim = guardReadySim(ctx, breach, energy, trace)
                    println("spawn: breacher blocks=$k walls=${breach.walls.size} hits=${breach.totalHits} fire=${wallFire(ctx, breach)} trip=${breach.trip} open=${breachOpenIn(ctx, breach, energy, flow)} hold=${holdReady.toInt()} invest=${investReady.toInt()} sim=$sim arrival=$enemyArrival err=${r.error}$trace")
                }
                return
            }
        }
        lastHomeCritical = alarm && ourPower < enemyPower && spawnUnderFire
        if (DEBUG_LOG && fighterFirst && energy < fullCost && getTicks() % 10 == 0) {
            println("spawn: fighter first — enemy arrives in $threatIn, hold=${holdReady.toInt()} invest=${investReady.toInt()} deficit=${deficit.toInt()} alarm=$alarm closes=$closesNow flow=${(flow * 10).toInt() / 10.0}")
        }
        // хаулер нужен, пока прогноз притока ниже того, что спавн переваривает, И спавн не насыщен:
        // при полном спавне с грузом в пути приток уже стоит в очереди, и новый хаулер только
        // отодвигает бойца (стенд: целевой приток 27 при теле M8R4 разгонял флот до 15 при спавне,
        // простаивающем полным)
        // ФЛОТ НЕ РАСТЁТ, ПОКА ОН НЕ ВЫВОЗИТ ОБЕЩАННОЕ. Прогноз считает энергию, лежащую на земле,
        // достижимой; охота на хаулеров, распад точки до приезда и пробки в этот счёт не входят, и в матче 22
        // флот рос до одиннадцати, пока сдача падала с 22 до 4. Замер (realisedIncome) отвечает на тот же
        // вопрос фактом; пока он держится у прогноза (с той же гистерезисной долей, что и у наступления),
        // ёмкость — узкое место и покупка имеет смысл. Ниже — узкое место не ёмкость, и ещё один хаулер
        // поедет умирать туда же
        val realised = realisedIncome()
        val fleetDelivers = realised < 0.0 || realised >= projectedIncome(ctx, usable) * PUSH_RELEASE_RATIO
        val needHauler = allHaulers < MAX_HAULERS && fleetDelivers &&
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
        val haulerTurn = needHauler && !fighterFirst && spentHaulers <= spentFighters + spentBuild + HAULER_LEAD

        if (haulerTurn) {
            val affordable = minOf(HAULER_BLOCKS_MAX, energy / blockCost())
            if (affordable < HAULER_BLOCKS_MIN) return // копим
            // копим на полного, если приток обещает; самого первого хаулера не ждём — без него притока нет
            val expected = minOf(HAULER_BLOCKS_MAX, (energy + carried) / blockCost())
            if (ctx.haulers.isNotEmpty() && affordable < HAULER_BLOCKS_MAX && expected > affordable) return
            val r = spawn.spawnCreep(haulerBody(affordable))
            if (r.error == null) spentHaulers += affordable * blockCost()
            if (DEBUG_LOG) println("spawn: hauler #${allHaulers + 1} blocks=$affordable income=${projectedIncome(ctx, usable).toInt()}/${targetIncome().toInt()} real=${if (realised < 0) "-" else realised.toInt().toString()} spent=$spentHaulers/$spentFighters err=${r.error}")
            return
        }
        // очередь хаулера, но энергии на бойца тоже нет — копим на того, кто первый по карману
        if (needHauler && !fighterFirst && energy < cost(RANGED_ATTACK) + cost(MOVE)) return

        if (energy < minFighter) return

        // БАШНЯ ДОМА. Площадка ничего не стоит, поэтому ставится сразу, как только счёт (towerWorth)
        // говорит, что дома она даёт больше бойца за ту же энергию. Смотритель — часть цены башни:
        // без него площадку некому строить, а готовая башня молчит (ёмкость — один выстрел)
        if (ctx.myTowers.isEmpty() && ctx.mySites.isEmpty()) {
            val trace = StringBuilder()
            val worth = towerWorth(defenders, threats, flow, trace)
            if (DEBUG_LOG && getTicks() % (LOG_EVERY * 5) == 0 && trace.isNotEmpty()) println("tower: worth=$worth$trace")
            // СЧЁТ УЖЕ ОТВЕТИЛ. towerWorth сравнил башню с бойцом против тех же врагов и с замеренной
            // смертностью бойцов; спрашивать сверх этого «а не купить ли всё-таки бойца» (fighterFirst)
            // значит запретить башню ровно там, где она и нужна, — враг у ворот (матч 26: worth=true
            // трижды, площадка не поставлена ни разу). Остаются только часы: спавн должен дожить
            if (worth) {
                val spot = towerSpot(ctx)
                if (spot != null) {
                    val r = createConstructionSite(spot.x, spot.y, StructureTower::class.js)
                    if (DEBUG_LOG) println("tower: site at (${spot.x},${spot.y})$trace flow=${(flow * 10).toInt() / 10.0} err=${r.error}")
                }
            }
        }
        if (ctx.builders.isEmpty() && (ctx.mySites.isNotEmpty() || ctx.myTowers.isNotEmpty())) {
            val builder = builderBody(builderWork(flow))
            val builderCost = builder.sumOf { cost(it) }
            if (energy < builderCost) {
                // копим на смотрителя, только пока спавн доживает до него — те же часы, что у правила
                // лагеря ниже: копить под сносимым спавном нельзя ни на что
                if (spawnLife <= energyArrivalTicks(ctx, builderCost - energy, flow)) return
                if (DEBUG_LOG && getTicks() % 10 == 0) println("spawn: saving for builder cost=$builderCost energy=$energy")
                return
            }
            val r = spawn.spawnCreep(builder)
            if (r.error == null) spentBuild += builderCost
            if (DEBUG_LOG) println("spawn: builder work=${builder.count { it == WORK }} cost=$builderCost energy=$energy err=${r.error}")
            return
        }

        // ЛАГЕРЬ у спавна: враг рядом и сильнее — боец по 300 умирает один (матч 02.09: восемь
        // подряд). Копим на полное тело — но только пока СПАВН ДОЖИВАЕТ до него: 3000 хитов, делённые
        // на входящий урон, против времени накопления недостающего при нынешнем потоке. Без этого счёта
        // правило копило до конца: матч 19 (05.09.2026) — с 850-го по 1000-й спавн набрал с 584 до 904
        // энергии и не построил НИЧЕГО, пока последние бойцы гибли по одному, и был снесён с 904 в банке
        if (alarm && ourPower < enemyPower && energy < SPAWN_ENERGY_CAPACITY &&
            spawnLife > energyArrivalTicks(ctx, SPAWN_ENERGY_CAPACITY - energy, flow)) return

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
            val waitTicks = energyArrivalTicks(ctx, gap, flow)
            if (deficit <= 0.0 || (enemyArrival > waitTicks && spawnLife > waitTicks)) return
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

    // ЧАСТИ HEAL В ТЕЛЕ БОЙЦА — ЗАМЕРЕНО И ОТВЕРГНУТО (05.09.2026, после матча 22, где его пара
    // «стрелок + лекарь» лечила 1469 раз вплотную, а у нас лечения не было вовсе). Ценность лечения
    // считалась двумя способами: как хиты, которые оно возвращает за бой (heal × хиты врага), и как
    // множитель живучести enemyDps/(enemyDps − heal). Обе формулы верны для ДУЭЛИ и обе выбрали одно и
    // то же вырождение — «одна пушка и два лекаря» (mmmmrhhmmm, 10 урона и 24 лечения): против одного
    // M3R3 с 30 урона два лекаря дают пятикратную живучесть. В групповом бою по одной цели бьют трое, и
    // лечение делится, а урона у тела нет. Стенд: fortress перестал браться вовсе (было 1381),
    // tower+stream 1591 против 1015, tower+healball 1285 против 965, tower+hover 1073 против 943.
    // Настоящий приём противника — не части в теле, а ОТДЕЛЬНЫЙ лекарь рядом с полным стрелком: это
    // формация из двух крипов, а не тело, и делать её надо как формацию (следующий кандидат).

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

        // ---- постура: оборона / наступление (гистерезис + срок выхода до ничьей) ----
        // в поле — полноскоростные стрелки (см. fullSpeed): покалеченный ходит вдвое-втрое медленнее
        // и либо тормозит волну, либо отстаёт и гибнет один; дома он полноценный защитник
        val strikers = fighters.filter { fullSpeed(it) && hasRanged(it) }
        // ПОДХОД — по ближайшему к цели стрелку (центр масс бывает на стене, где поле = -1): по нему
        // считается горизонт производства врага, то есть когда осада НАЧНЁТСЯ. Срок, до которого волна
        // обязана выйти, считается ниже и по всей группе — это разные величины, и прежде их путали
        // (условие dps > 0 то же, что и было: пока стрелять некому, подход не считается)
        val dps = fighters.sumOf { InfluenceMap.profileOf(it).ranged }
        var travel = Int.MAX_VALUE / 2
        if (enemySpawn != null && dps > 0.0) {
            val flowToEnemy = flowTo(ctx, enemySpawn)
            travel = strikers.ifEmpty { fighters }
                .minOf { flowToEnemy[it.x * 100 + it.y].let { d -> if (d < 0) Int.MAX_VALUE / 2 else d } }
        }
        val remaining = arenaInfo.ticksLimit - getTicks()
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
        // СРОК ВЫХОДА: штурм успевает, только если группа ещё дойдёт и добьёт до лимита тиков. Ход
        // группы — по САМОМУ дальнему её бойцу (идут вместе, осада начинается с приходом последнего),
        // время осады — из её же симуляции. Прежний «последний звонок» брал ход БЛИЖАЙШЕГО бойца и урон
        // ВСЕЙ армии, включая стоящих дома: в матче 18 он сработал на 1990-м, когда авангард стоял в 51
        // клетке, а масса армии — в сотне, и матч кончился ничьей при нетронутых 3000 хитов чужого спавна
        fun budget(travelTicks: Int, siege: SiegeResult) = travelTicks.toLong() + minOf(siege.ticks, SIEGE_LIMIT)
        val startTravel = travelOf(staging, spawnFlow)
        val frontTravel = travelOf(waveFront, spawnFlow)
        val never = Long.MAX_VALUE / 4
        val goNeed = minOf(
            if (staging.isEmpty()) never else budget(startTravel, siegeStart),
            if (waveFront.isEmpty()) never else budget(frontTravel, siegeGo))
        val lastCall = goNeed < never && remaining <= goNeed + LATE_MARGIN
        // Может ли враг ещё отнять у нас спавн за остаток: его ближайший боец доходит за enemyApproach и
        // снимает 3000 хитов своим уроном (рождённый позже карту уже не пересечёт). Пока может — армию
        // из дома не выгребаем даже в конце: положенная под башню, она обменяла бы ничью на поражение
        // (матч 9). Не может — ничья и поражение стоят одного, и терять нечего
        val enemyReach = combatEnemies.minOfOrNull {
            ctx.enemyApproach[it.x * 100 + it.y].let { d -> if (d < 0) Int.MAX_VALUE / 4 else d }
        } ?: Int.MAX_VALUE / 4
        val enemyDps = combatEnemies.sumOf { val p = InfluenceMap.profileOf(it); p.ranged + p.melee }
        val homeAtRisk = enemyDps > 0.0 &&
            enemyReach + (mySpawn.hits ?: SPAWN_HITS) / enemyDps <= remaining.toDouble()
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
        // КТО В БОЮ, А НЕ КТО ЖИВ. Их огонь убивает одного нашего за killTicks; тот, кому идти дольше,
        // в ЭТОМ бою не стреляет — он придёт к следующему, уже в меньшинстве. Матч 26 (05.09.2026):
        // шестеро при 1794 против 1272 пошли навстречу стае у угловой точки, в дальности одновременно
        // стояли трое, и за сорок тиков мы отдали двоих, не убив никого. Порог — не число: это время
        // жизни нашего строя под их уроном, посчитанное из их урона и наших хитов, и путь каждого
        // считается его собственным телом по болоту (pathTicks)
        val homeAnchor = homeThreats.minByOrNull { ctx.enemyApproach[it.x * 100 + it.y] }
        val homeReady = if (homeAnchor == null) homeAll else {
            // окно — время жизни ВСЕГО строя под их огнём, а не до первой смерти: бой идёт, пока есть
            // кому стрелять, и подкрепление, успевшее к середине, в нём участвует. По короткому окну
            // (до первой смерти) отряд переставал выходить навстречу вовсе: ball 664→897 на стенде
            val theirDps = homePack.sumOf { effectiveDps(it, homeAll, homeSpawnPos) }
            val ourHits = homeAll.sumOf { it.hits }
            val fightTicks = if (theirDps <= 0.0) Int.MAX_VALUE / 4 else (ourHits / theirDps).toInt()
            val toThreat = flowTo(ctx, homeAnchor)
            homeAll.filter { pathTicks(it, toThreat, it.x * 100 + it.y) <= maxOf(fightTicks, RANGED_RANGE) }
        }
        // с гистерезисом, как охота: начатый бой продолжаем при 0.9 — иначе первые потери переключали
        // «дерёмся» в «пост», и отряд разворачивался под огнём
        val homeOurs = ourPowerOf(homeReady, homePack)
        val homeTheirs = enemyPowerOf(homePack, homeReady)
        // РАЗМЕН — ПРИБОР, НЕ ВЕТО (замерено и отвергнуто 06.09.2026). Модель предсказывает размен:
        // его урон, делённый на наш чистый (за вычетом его лечения); measureExchange говорит, сколько
        // уходит на самом деле, и в проигранных матчах расходились они вдесятеро. Вето по этому
        // расхождению было построено (v27), починено дважды (окно вместо счётчика — v28, жизнь строя
        // вместо трёхсот тиков — v29) и трижды измерено вживую: v26 без него — 7-1-1 на девяти матчах,
        // четыре победы над けろびー; с ним — три матча подряд проиграны тому же けろびー, рейтинг
        // 1120→1087, и стенд платил тем же (tower+hover 993→1196). Причина: вето отменяет и те бои,
        // которые мы БЕРЁМ, и оставляет поле врагу — а поле здесь это энергия, и матч решает она.
        // Числа остаются в журнале (x<наши>/<его>~<предсказание>w<окно>): расхождение реально и ждёт
        // правки в самой модели, а не заплатки поверх неё
        val ourNet = homeReady.sumOf { effectiveDps(it, homePack, null) } - homePack.sumOf { InfluenceMap.profileOf(it).heal }
        val theirNet = homePack.sumOf { effectiveDps(it, homeReady, homeSpawnPos) }
        val predicted = if (ourNet <= 0.0) Double.MAX_VALUE else theirNet / ourNet
        // окно свидетельства — сколько живёт наш строй под его нынешним огнём: старше этого срока
        // размен относится к бою, которого больше нет (матч 35: восемь целых против трёх подбитых, а
        // вето держало пост по счёту трёхсоттиковой давности). Границы окна — уже существующие
        // масштабы: не короче окна сближения и не длиннее окна производства
        val lineLife = if (theirNet <= 0.0) PRODUCTION_WINDOW else (homeAll.sumOf { it.hits } / theirNet).toInt()
        val exchangeWindow = lineLife.coerceIn(APPROACH_WINDOW, PRODUCTION_WINDOW)
        val ourLost = lostIn(ourLostWindow, exchangeWindow)
        val theirLost = lostIn(theirLostWindow, exchangeWindow)
        // свидетельство считается достаточным, когда мы отдали хиты целого бойца: это не порог по вкусу,
        // а единица размена — цена одного нашего тела
        val decided = ourLost >= (homeAll.minOfOrNull { it.hitsMax } ?: Int.MAX_VALUE)
        val exchangeOk = !decided || (predicted < Double.MAX_VALUE && ourLost <= predicted * theirLost * DEFEND_MARGIN)
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
            "(${homeOurs.toInt()}/${homeTheirs.toInt()}[${homeReady.size}/${homeAll.size}]" +
            "x${ourLost.toInt()}/${theirLost.toInt()}~${(predicted * 100).toInt()}w$exchangeWindow${if (exchangeOk) "" else "!"})"
        // ДОМ НА ВРЕМЯ ВЫЛАЗКИ. Пока волна ходит — ход группы плюс осада по её же симуляции — до нашего
        // спавна успевают дойти те приближающиеся, у кого подход меньше этого срока. Держать их должен
        // гарнизон, то есть те, кто ОСТАНЕТСЯ (homeGuard уже без staging), а не только тот, кто нужен
        // против стоящих в кольце тревоги. Прежде спрашивали лишь про кольцо в сорок тиков, и матч 20
        // (05.09.2026) кончился так: на 290-м двое ушли по вердикту «win/208t», пока армия врага в 587
        // шла к нам и была в 123 тиках; дом опустел, армия пришла на 550-м, спавн снесён на 600-м
        val sortieTicks = if (startTravel >= Int.MAX_VALUE / 8) Int.MAX_VALUE / 8
            else startTravel + minOf(siegeStart.ticks, SIEGE_LIMIT)
        val arrivingHome = combatEnemies.filter {
            it.id in approachingIds && (arrivalById[it.id] ?: Int.MAX_VALUE / 2) <= sortieTicks
        }
        val guardHoldsSortie = arrivingHome.isEmpty() ||
            ourPowerOf(homeGuard, arrivingHome) >= enemyPowerOf(arrivingHome, homeGuard) * DEFEND_MARGIN
        val strongerNow = staging.size >= PUSH_MIN_FIGHTERS && siegeStart.win && guardHolds && guardHoldsSortie
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
            lastCall && notWeaker && (siegeGo.win || siegeStart.win || !homeAtRisk) -> true
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
        // …и только пока подкрепление ЕЩЁ УСПЕВАЕТ дойти и добить вместе с фронтом: держать кромку ради
        // группы, которая не придёт до конца матча, — это ничья по расписанию (матч 18: hold=true с
        // 1800-го при двухстах тиках в запасе, подкрепление уходило по одному бойцу и не успело). Ход
        // подкрепления — от поста, а если поста нет, от спавна: следующий боец родится там
        val homeTravel = if (spawnFlow.isEmpty()) Int.MAX_VALUE / 4
        else flowNear(spawnFlow, mySpawn.x, mySpawn.y).let { if (it < 0) Int.MAX_VALUE / 4 else spawnFlow[it] }
        val reinforceTravel = if (staging.isNotEmpty()) startTravel else homeTravel
        val holdInTime = remaining > budget(reinforceTravel, siegeJoin) + LATE_MARGIN
        siegeHold = newPushing && !siegeGo.win && waveMembers.isNotEmpty() && !frontCovered && holdInTime
        if (DEBUG_LOG && (newPushing != pushing || getTicks() % (LOG_EVERY * 10) == 0)) {
            println("posture: ${if (newPushing) "PUSH" else "DEFEND"} t=${getTicks()} our=${ourOffense.toInt()} hits=$waveHits attrition=${attrition.toInt()}+${unitCost.toInt()} after=${waveAfter.toInt()} enemy=${enemyPower.toInt()} massing=${massingPower.toInt()} pack=${maxPack.toInt()} production=${(production * 100).toInt()}/100t stream=${(streamUnits * 10).toInt() / 10.0} travel=$travel siege=$siege sim=$siegeStart/$siegeGo join=$siegeJoin hold=$siegeHold(${if (holdInTime) "inTime" else "late"}) need=${if (goNeed >= never) "-" else goNeed.toString()}/$remaining risk=$homeAtRisk front=${waveFront.size}/${waveMembers.size} towers=${siegeTowers.size} staging=${staging.size} guardHolds=$guardHolds/${guardHoldsSortie}(${arrivingHome.size}@$sortieTicks) home=$homeMode spawnFire=$spawnUnderFire guardNeeded=$guardNeeded raidPeak=${raidPeak.toInt()} lastCall=$lastCall alarm=$alarm")
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
            // на последнем звонке уходят только те, кто ещё УСПЕЕТ дойти, и уходят группой: боец,
            // отправленный в одиночку за сорок тиков до конца, не доходит никуда, а дома он защитник
            // (матч 18: волны 4-6 состояли из одного бойца каждая). Одиночка уходит, только если впереди
            // уже стоит волна, к которой он идёт
            val lastCallGo = lastCall && remaining > startTravel + LATE_MARGIN / 2 &&
                (staging.size >= PUSH_MIN_FIGHTERS || waveMembers.isNotEmpty())
            if (staging.isNotEmpty() && (strongerNow || lastCallGo || (siegeHold && siegeJoin.win && guardHolds && guardHoldsSortie))) {
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
                    shoot(creep, enemyCreeps, enemySpawn, focusTarget, stormSpawn, wallTarget, active)
                    continue
                }
                val farTarget = candidates.filter { it.hitsMax - it.hits > 0 }.maxByOrNull { need(it) }
                if (farTarget != null) {
                    creep.rangedHeal(farTarget)
                    healDone[farTarget.id] = (healDone[farTarget.id] ?: 0) + healParts * RANGED_HEAL_POWER
                    continue
                }
            }
            shoot(creep, enemyCreeps, enemySpawn, focusTarget, stormSpawn, wallTarget, active)
        }
    }

    private fun shoot(creep: Creep, enemyCreeps: List<Creep>, enemySpawn: StructureSpawn?, focusTarget: Creep?, stormSpawn: Boolean, wallTarget: StructureWall? = null, allies: List<Creep> = emptyList()) {
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
                // общей цели не достать — берём ту, по которой УЖЕ могут стрелять остальные наши: так
                // соседи сходятся на одной цели сами. Прежде каждый выбирал независимо «лекарь, потом самый
                // раненый», и огонь размазывался: замер повтора матча 22 — 1.6 цели за тик и доля фокуса
                // 0.73 против 1.04 и 0.98 у противника, 43 наших выстрела из 74 ушли в лекаря, пока его
                // стрелок жил вооружённым и стрелял
                creepsInRange.isNotEmpty() -> creepsInRange.minWithOrNull(
                    compareByDescending<Creep> { e -> allies.count { it.id != creep.id && hasRanged(it) && it.getRangeTo(e) <= RANGED_RANGE } }
                        .thenByDescending { InfluenceMap.profileOf(it).heal }
                        .thenBy { it.hits })
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
        val dps = ours.sumOf { effectiveDps(it, theirs, null) } + ourTowerDps(theirs)
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
        val dps = defenders.sumOf { effectiveDps(it, threats, null) } + ourTowerDps(threats) +
            body.count { it == RANGED_ATTACK } * RANGED_ATTACK_POWER + body.count { it == ATTACK } * ATTACK_POWER
        val hits = defenders.sumOf { weightedHits(it, threats, null) } + body.size * 100
        val heal = threats.sumOf { InfluenceMap.profileOf(it).heal }
        return lanchester(dps, heal, hits.toInt()) >= enemyPowerOf(threats, defenders) * DEFEND_MARGIN
    }

    /**
     * Через сколько тиков откроется контейнер пролома: живой бурильщик — его ход до текущей стены по
     * полю (болото по его телу) плюс ломка остатка живыми ATTACK; рождающийся — остаток рождения и ход
     * от спавна; без бурильщика — накопление на тело под поток (breacherBlocksFor), его рождение, ход от
     * спавна и ломка всех стен. Пролом уже открыт — 0; ломать некому — «никогда».
     */
    private fun breachOpenIn(ctx: Ctx, breach: BreachPlan, budget: Int, flow: Double): Int {
        val wall = breach.current() ?: return 0
        val hits = breach.totalHits
        val field = flowTo(ctx, wall)
        val fire = wallFire(ctx, breach)
        val breacher = ctx.myCreeps.filter { isMelee(it) }.minByOrNull { getRange(it, wall) }
        val spawnWalk = breachWalk(ctx, breach)
        if (breacher != null) {
            val attacks = breacher.body.count { it.type == ATTACK && it.hits > 0 }
            val born = if (breacher.spawning) ctx.mySpawn.spawning?.remainingTime ?: 0 else 0
            val walk = if (breacher.spawning) spawnWalk else pathTicks(breacher, field, breacher.x * 100 + breacher.y)
            return breachOpenAt(hits, fire, attacks, if (walk >= Int.MAX_VALUE / 4) walk else born + walk)
        }
        val expected = budget + ctx.haulers.sumOf { it.store[RESOURCE_ENERGY] ?: 0 }
        val k = breacherBlocksFor(hits, spawnWalk, breach.steps, expected, flow, fire)
        if (k == 0) return breachOpenAt(hits, fire, 0, 0)
        val wait = ceil(maxOf(0.0, (k * (cost(MOVE) + cost(ATTACK)) - expected) / maxOf(flow, 1.0))).toInt()
        return breachOpenAt(hits, fire, k, wait + 2 * k * CREEP_SPAWN_TIME + spawnWalk)
    }

    /**
     * Через сколько тиков ВЛОЖЕНИЕ даст ПОЛНОГО бойца — прогон политики самого спавна (см. spawnIfNeeded)
     * по тикам от нынешнего состояния: бурильщик по breacherOrderOf (тело под поток, окупаемость, порядок с
     * хаулером), контейнер пролома становится точкой флота с открытия, хаулеры покупаются, пока их очередь
     * (HAULER_LEAD), есть что возить и прогноз притока ниже целевого, остальное копится на полное тело. Без
     * пролома — только хаулеры. Прежняя формула складывала ломку с накоплением по ЦЕЛЕВОМУ притоку (27/тик)
     * сразу после пролома, а флот в тот момент — один хаулер за 200 с притоком 5 и ходом к угловому
     * контейнеру: «страж к 167-му» против прихода врага на 168-й, и на 167-м спавн держал 278 энергии без
     * единого стрелка (матч 14). Прогон с телом бурильщика не из ветки спавна оставлял симуляции 90
     * энергии вместо 220 и «страж к 1989-му» (стенд freeze) — тело здесь то же, что в ветке.
     */
    private fun guardReadySim(ctx: Ctx, breach: BreachPlan?, budget: Int, trace: StringBuilder? = null): Int {
        val fighter = fighterBody(SPAWN_ENERGY_CAPACITY)
        val fighterCost = fighter.sumOf { cost(it) }
        val regen = regenRate()
        val target = targetIncome()
        val points = fleetPoints(ctx, usableSites(ctx))
        val horizon = arenaInfo.ticksLimit - getTicks()
        val never = Int.MAX_VALUE / 4
        val block = cost(MOVE) + cost(ATTACK)
        val walk = if (breach != null) breachWalk(ctx, breach) else 0
        val fire = if (breach != null) wallFire(ctx, breach) else 0
        val breachPoint = if (breach != null) (breach.container.store[RESOURCE_ENERGY] ?: 0) to breach.trip else 0 to 0
        var energy = budget.toDouble() + ctx.haulers.sumOf { it.store[RESOURCE_ENERGY] ?: 0 }
        var fleet = ctx.haulers.sumOf { capacityOf(it) }
        var haulers = ctx.myCreeps.count { c -> c.body.any { it.type == CARRY } }
        var spentH = spentHaulers
        var spentF = spentFighters
        var busyUntil = 0
        // открытие пролома: живой бурильщик — по его ходу и ударам; иначе огнём стрелков на посту, а прогон
        // купит бурильщика сам по правилам ветки, если тот ускоряет открытие сильнее своей цены
        var breacherPending = breach != null && ctx.myCreeps.none { isMelee(it) }
        var open = when {
            breach == null -> never
            !breacherPending -> breachOpenIn(ctx, breach, budget, regen + incomeOf(points, fleet))
            else -> breachOpenAt(breach.totalHits, fire, 0, 0)
        }
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
            if (breacherPending && breach != null && t < open) {
                val hitsLeft = maxOf(1, breach.totalHits - fire * t)
                val order = breacherOrderOf(breach, hitsLeft, walk, fire, points, fleet, energy.toInt(), regen + income, horizon - t)
                if (order == null) breacherPending = false // стрелки откроют сами или пролом не стоит бурильщика — только хаулеры
                else if (order.second) {
                    val k = order.first
                    val c = k * block
                    if (energy >= c) {
                        energy -= c
                        spentF += c
                        busyUntil = t + 2 * k * CREEP_SPAWN_TIME
                        open = t + breachOpenAt(hitsLeft, fire, k, 2 * k * CREEP_SPAWN_TIME + walk)
                        breacherPending = false
                    }
                    continue // копим на бурильщика или ждём его рождения
                }
                // иначе хаулер вперёд
            }
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

    /** Тики хода ГРУППЫ до цели по полю потока — по САМОМУ дальнему её бойцу: группа идёт вместе, и
     *  осада начинается, когда дошёл последний. Пустая группа или пустое поле — «никогда». */
    private fun travelOf(group: List<Creep>, flow: IntArray): Int {
        if (group.isEmpty() || flow.isEmpty()) return Int.MAX_VALUE / 4
        return group.maxOf { flow[it.x * 100 + it.y].let { d -> if (d < 0) Int.MAX_VALUE / 4 else d } }
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
    /** Сколько энергии флот сдал в спавн за тик — по падению груза хаулера рядом со спавном. Это ФАКТ,
     *  в отличие от projectedIncome: охота на хаулеров, распад точки до приезда и пробки видны только здесь. */
    private fun measureDelivery(ctx: Ctx) {
        val now = getTicks()
        var sum = 0
        for (h in ctx.haulers) {
            val store = h.store[RESOURCE_ENERGY] ?: 0
            val was = haulerStore[h.id]
            if (was != null && was > store && getRange(h, ctx.mySpawn) <= 1) sum += was - store
            haulerStore[h.id] = store
        }
        haulerStore.keys.retainAll(ctx.haulers.mapTo(HashSet()) { it.id })
        if (firstHaulerTick < 0 && ctx.haulers.isNotEmpty()) firstHaulerTick = now
        if (sum > 0) delivered.addLast(now to sum)
        while (delivered.isNotEmpty() && delivered.first().first < now - PRODUCTION_WINDOW) delivered.removeFirst()
    }

    /** Замеренный приток (энергии в тик) за окно; -1, пока флот не проработал целое окно и мерить нечего. */
    private fun realisedIncome(): Double {
        val now = getTicks()
        if (firstHaulerTick < 0) return -1.0
        val span = minOf(PRODUCTION_WINDOW, now - firstHaulerTick)
        if (span < PRODUCTION_WINDOW) return -1.0
        return delivered.sumOf { it.second }.toDouble() / span
    }

    // ==================== стройка ====================

    /** Цена структуры — из константы арены, а не числом (площадка врага на 1000 в матче 25 — это спавн). */
    private fun buildCost(name: String): Int = CONSTRUCTION_COST[name] ?: 0

    /** Чистая потеря хитов стороны за тик: разница с прошлым тиком по живым (отрицательная, если
     *  залечили) плюс полные последние хиты тех, кого не стало. */
    private fun sideLoss(seen: HashMap<String, Int>, live: List<Creep>): Double {
        var lost = 0.0
        val ids = HashSet<String>()
        for (c in live) {
            ids.add(c.id)
            val prev = seen[c.id]
            if (prev != null) lost += (prev - c.hits).toDouble()
            seen[c.id] = c.hits
        }
        for (id in seen.keys.toList()) if (id !in ids) { lost += (seen[id] ?: 0).toDouble(); seen.remove(id) }
        return lost
    }

    /** Размен за окно. Считается КАЖДЫЙ тик по всем вооружённым с обеих сторон — фильтр «у дома» здесь
     *  вреден: крип, вышедший из кольца, исчез бы из списка живых и был бы засчитан как убитый. */
    private fun measureExchange(ctx: Ctx) {
        val now = getTicks()
        val ourLost = sideLoss(ourHitsSeen, ctx.fighters.filter { hasWeapon(it) })
        val theirLost = sideLoss(theirHitsSeen, ctx.combatEnemies)
        if (ourLost != 0.0) ourLostWindow.addLast(now to ourLost)
        if (theirLost != 0.0) theirLostWindow.addLast(now to theirLost)
        while (ourLostWindow.isNotEmpty() && ourLostWindow.first().first < now - PRODUCTION_WINDOW) ourLostWindow.removeFirst()
        while (theirLostWindow.isNotEmpty() && theirLostWindow.first().first < now - PRODUCTION_WINDOW) theirLostWindow.removeFirst()
    }

    /** Размен за последние `window` тиков. Окно задаёт спрашивающий: свидетельство старше жизни
     *  нашего строя относится к другой армии — у той стороны с тех пор и состав другой. */
    private fun lostIn(window: ArrayDeque<Pair<Int, Double>>, ticks: Int): Double {
        val from = getTicks() - ticks
        return window.sumOf { if (it.first >= from) it.second else 0.0 }
    }

    /** Замер КПД башни: был ли в этот тик враг там, куда башня достаёт. */
    private fun measureHomeFight(ctx: Ctx) {
        val now = getTicks()
        val underFire = ctx.combatEnemies.any { InfluenceMap.towerShot(getRange(ctx.mySpawn, it)) > 0.0 }
        if (underFire) homeFightTicks.addLast(now)
        while (homeFightTicks.isNotEmpty() && homeFightTicks.first() < now - PRODUCTION_WINDOW) homeFightTicks.removeFirst()
    }

    /** Доля последнего окна, когда враг стоял под выстрелом башни. Башня бьёт только их, поэтому это и
     *  есть её КПД — замеренный её собственной геометрией, а не верой в то, что враг придёт. */
    private fun homeShare(): Double {
        val span = minOf(PRODUCTION_WINDOW, getTicks() + 1)
        return if (span <= 0) 0.0 else homeFightTicks.size.toDouble() / span
    }

    /** Клетка башни: второе кольцо от спавна (ближе — занимает клетку выхода новорождённых, дальше —
     *  теряет по 50 урона за клетку и не оставляет клетки, с которой смотритель достаёт до обоих),
     *  проходимая и свободная, из таких — ближайшая к спавну врага: оттуда приходят. */
    private fun towerSpot(ctx: Ctx): Position? {
        val spawn = ctx.mySpawn
        val enemy: Position = ctx.enemySpawn ?: spawn
        val busy = ctx.blocked.mapTo(HashSet()) { it.x * 100 + it.y }
        var best: Position? = null
        var bestScore = Int.MAX_VALUE
        for (dx in -TOWER_RING..TOWER_RING) for (dy in -TOWER_RING..TOWER_RING) {
            if (maxOf(abs(dx), abs(dy)) != TOWER_RING) continue
            val x = spawn.x + dx
            val y = spawn.y + dy
            if (x < 1 || y < 1 || x > 98 || y > 98) continue
            val pos = InfluenceMap.cell(x, y)
            if (getTerrainAt(pos) == TERRAIN_WALL) continue
            if (x * 100 + y in busy) continue
            val score = getRange(pos, enemy)
            if (score < bestScore) { bestScore = score; best = pos }
        }
        return best
    }

    /** Сколько WORK у смотрителя. Время до готовой башни — накопление её цены по потоку плюс стройка
     *  (BUILD_POWER за WORK в тик); лишняя WORK ускоряет вторую половину и удлиняет первую. Минимум
     *  суммы: k = √(цена × поток / (BUILD_POWER × цена WORK)) — из потока, а не назначено. */
    private fun builderWork(flow: Double): Int {
        val k = sqrt(buildCost("StructureTower") * maxOf(flow, 0.5) / (BUILD_POWER * cost(WORK)))
        return k.toInt().coerceIn(1, (MAX_CREEP_SIZE - 4) / 2)
    }

    /** Тело смотрителя [MOVE×2, CARRY×2, WORK×k]: WORK в хвосте — урон снимает части спереди, и
     *  разоружённый смотритель ещё возит выстрелы в башню; двух MOVE хватает на три клетки у ворот,
     *  двух CARRY — на десять выстрелов без возврата к спавну. */
    private fun builderBody(k: Int): Array<BodyPartType> {
        val body = ArrayList<BodyPartType>(4 + k)
        repeat(2) { body.add(MOVE) }
        repeat(2) { body.add(CARRY) }
        repeat(k) { body.add(WORK) }
        return body.toTypedArray()
    }

    /** Доля вложенного в бойцов, которая ЖИВА: цена уцелевших частей всех живых бойцов к потраченному
     *  на бойцов. Боец — расходник, и это единственная разница между ним и башней, которую снимок
     *  «урон×хиты» не видит вовсе: за матч 25 мы вложили в бойцов 6780 и к 690-му тику держали пятерых.
     *  Пока никого не потеряли — единица, и башня честно проигрывает бойцу. */
    private fun survivalOfFighters(fighters: List<Creep>): Double {
        if (spentFighters <= 0) return 1.0
        val alive = fighters.sumOf { c -> c.body.sumOf { if (it.hits > 0) cost(it.type) else 0 } }
        return (alive.toDouble() / spentFighters).coerceIn(0.0, 1.0)
    }

    /** Окупается ли башня против бойца за ту же энергию. Мера одна и та же — ПРИБАВКА к мощи обороны
     *  против тех же врагов, с их лечением (см. lanchester): против пары «стрелок + лекарь» непрерывный
     *  урон бойца съедается лечением, а выстрел башни — 1000 разом — нет, и это видно только если
     *  считать против реального врага, а не против абстрактного тела.
     *  Башня работает лишь дома, поэтому её урон и хиты умножены на замеренную долю боя дома (homeShare);
     *  бойцу — скидка на смертность (survivalOfFighters): купленный боец гибнет, поставленная башня стоит.
     *  Цена башни — вместе со смотрителем: без него площадку некому строить, а готовая башня молчит. */
    private fun towerWorth(defenders: List<Creep>, threats: List<Creep>, flow: Double, trace: StringBuilder? = null): Boolean {
        val share = homeShare()
        if (share <= 0.0 || threats.isEmpty()) return false
        val heal = threats.sumOf { InfluenceMap.profileOf(it).heal }
        val dps = defenders.sumOf { effectiveDps(it, threats, null) } + ourTowerDps(threats)
        val hits = defenders.sumOf { weightedHits(it, threats, null) }
        val base = lanchester(dps, heal, hits.toInt())
        // враг бьёт спавн с трёх клеток, башня стоит во втором кольце — худший случай по дальности
        val towerDps = InfluenceMap.towerShot(TOWER_RING + RANGED_RANGE) / InfluenceMap.towerCooldown
        val towerPrice = buildCost("StructureTower") + builderBody(builderWork(flow)).sumOf { cost(it) }
        val withTower = lanchester(dps + towerDps * share, heal, (hits + TOWER_HITS * share).toInt())
        val body = fighterBody(SPAWN_ENERGY_CAPACITY)
        val bodyDps = (body.count { it == RANGED_ATTACK } * RANGED_ATTACK_POWER + body.count { it == ATTACK } * ATTACK_POWER).toDouble()
        val fighterPrice = body.sumOf { cost(it) }
        if (towerPrice <= 0 || fighterPrice <= 0) return false
        val survival = survivalOfFighters(defenders)
        val withFighter = lanchester(dps + bodyDps, heal, (hits + body.size * 100).toInt())
        val gainTower = (withTower - base) / towerPrice
        val gainFighter = (withFighter - base) * survival / fighterPrice
        trace?.append(" share=${(share * 100).toInt()}% surv=${(survival * 100).toInt()}% base=${base.toInt()} " +
            "tower=${withTower.toInt()}/$towerPrice=${(gainTower * 1000).toInt()} fighter=${withFighter.toInt()}/$fighterPrice=${(gainFighter * 1000).toInt()}")
        // прибавка должна быть ПОЛОЖИТЕЛЬНОЙ: при выбитых бойцах и лечении врага выше урона одиночки
        // обе прибавки — ноль, и ничья «0 >= 0» покупала башню там, где покупать нечего вовсе
        return gainTower > 0.0 && gainTower >= gainFighter
    }

    /** Огонь НАШИХ башен по этой группе. Это геометрия, а не число: выстрел падает на 50 за клетку и
     *  за TOWER_FALLOFF_RANGE не долетает вовсе, поэтому в осаде у чужого спавна домашняя башня даёт
     *  ноль, а по лагерю у наших ворот — почти полный урон. Хиты башни в счёт НЕ идут: они наш ресурс
     *  только тогда, когда враг стреляет именно в неё. */
    private fun ourTowerDps(theirs: List<Creep>): Double {
        if (theirs.isEmpty() || myTowers.isEmpty()) return 0.0
        return myTowers.sumOf { t ->
            if ((t.store[RESOURCE_ENERGY] ?: 0) < TOWER_ENERGY_COST) 0.0
            else InfluenceMap.towerShot(theirs.minOf { getRange(t, it) }) / InfluenceMap.towerCooldown
        }
    }

    /** Башня стреляет раз в кулдаун: сначала в того, кого этим выстрелом убьёт (выстрел не делится),
     *  иначе в самого опасного из достижимых. Некого бить — лечит самого израненного своего: 600 за
     *  выстрел, полсотни частей HEAL. Пустая башня молчит — кормит её смотритель. */
    private fun runTowers(ctx: Ctx) {
        for (t in ctx.myTowers) {
            if (t.cooldown > 0) continue
            if ((t.store[RESOURCE_ENERGY] ?: 0) < TOWER_ENERGY_COST) continue
            val target = ctx.combatEnemies.filter { InfluenceMap.towerShot(getRange(t, it)) > 0.0 }
                .minWithOrNull(
                    compareByDescending<Creep> { InfluenceMap.towerShot(getRange(t, it)) >= it.hits }
                        .thenByDescending { effectiveDps(it, ctx.fighters, homeSpawnPos) }
                        .thenBy { getRange(t, it) })
            if (target != null) {
                t.attack(target)
                if (DEBUG_LOG) println("  tower (${t.x},${t.y}) -> (${target.x},${target.y}) r=${getRange(t, target)} dmg=${InfluenceMap.towerShot(getRange(t, target)).toInt()} hits=${target.hits}")
                continue
            }
            val hurt = ctx.active.filter { it.hits < it.hitsMax && InfluenceMap.towerShot(getRange(t, it)) > 0.0 }
                .minByOrNull { it.hits * 100 / maxOf(it.hitsMax, 1) }
            if (hurt != null) t.heal(hurt)
        }
    }

    /** Смотритель: пока есть площадка — возит в неё энергию из спавна и строит, башня готова — держит
     *  в ней выстрел. Он безоружен и от огня уходит, как хаулер, продолжая работать на ходу (стройка и
     *  передача — интенты, шагу они не мешают). Энергию спавна берёт, только когда она не нужна бойцу
     *  прямо сейчас: под «бойцом первым» тысяча в спавне принадлежит бойцу. */
    private fun runBuilders(ctx: Ctx) {
        if (ctx.builders.isEmpty()) return
        val spawn = ctx.mySpawn
        val site = ctx.mySites.minByOrNull { getRange(spawn, it) }
        val tower = ctx.myTowers.filter { (it.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0 }.minByOrNull { getRange(spawn, it) }
        for (b in ctx.builders) {
            val carrying = b.store[RESOURCE_ENERGY] ?: 0
            val free = b.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0
            val goal: Position? = site ?: tower
            val reach = if (site != null) BUILD_RANGE else 1
            val canAct = goal != null && getRange(b, goal) <= reach
            val mayTake = !lastHomeCritical && (spawn.store[RESOURCE_ENERGY] ?: 0) > 0 && getRange(b, spawn) <= 1
            if (canAct && carrying > 0) {
                if (site != null) b.build(site) else tower?.let { b.transfer(it, RESOURCE_ENERGY) }
            }
            if (mayTake && free > 0) b.withdraw(spawn, RESOURCE_ENERGY)
            val incoming = InfluenceMap.damageAt(b.x, b.y, ctx.combatEnemies)
            val step = when {
                incoming > 0.0 -> fleeStep(b, ctx.combatEnemies, ctx.dangerMatrix) ?: pathStep(b, spawn, 1, ctx.dangerMatrix)
                goal == null -> if (getRange(b, spawn) > PARK_RANGE) pathStep(b, spawn, PARK_RANGE, ctx.dangerMatrix) else null
                carrying <= 0 && !mayTake -> pathStep(b, spawn, 1, ctx.dangerMatrix)
                !canAct -> pathStep(b, goal, reach, ctx.dangerMatrix)
                else -> null
            }
            if (step != null && canMove(b)) TrafficManager.request(b, step, HAULER_LOADED_PRIORITY)
            if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
                println("  b${b.id} (${b.x},${b.y}) carry=$carrying/${capacityOf(b)} work=${b.body.count { it.type == WORK && it.hits > 0 }} " +
                    "goal=${goal?.let { "(${it.x},${it.y})" } ?: "-"}${if (site != null) "site" else "feed"} act=$canAct take=$mayTake fire=${incoming.toInt()} step=${step?.let { "(${it.x},${it.y})" } ?: "stay"}")
            }
        }
    }

    private fun measureRegen(spawn: StructureSpawn, deliveringNearby: Boolean) {
        val e = spawn.store[RESOURCE_ENERGY] ?: 0
        // действия применяются в КОНЦЕ тика: сдача, начатая на прошлом тике, видна в энергии сейчас
        // полный спавн прироста не показывает: первая проба на 1000/1000 давала ноль, и до второй пробы
        // оценка регенерации была 0.0 — прогон дебюта (guardReadySim) не рос ни на единицу (стенд freeze)
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
