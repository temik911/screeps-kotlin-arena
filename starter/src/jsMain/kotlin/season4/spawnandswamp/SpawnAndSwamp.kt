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
import screeps.api.getCpuTime
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
    private const val BOT_VERSION = 108

    // ---------- switches of v84 (each rule can be turned off alone; the verdicts go into their KDoc) ----------
    /** A healer in a wave follows the most damaged member / the vanguard instead of walking home (runFighters). */
    private const val USE_HEALER_WARD = true
    /** A body without a single MOVE is not counted as army production (enemyProductionPerTick). */
    private const val USE_LEGLESS_NOT_ARMY = true
    /** A born healer's heal counts in its production power like damage (enemyProductionPerTick). */
    private const val USE_HEAL_IN_BIRTHS = true
    /** A melee's value is the share of his hits it can reach, not all-or-nothing (meleeShare).
     *  OFF — measured 25.09.2026 and not landed: on the gate it is the whole cause of `tower+healball` 521 -> 1264 and
     *  `stream17` 888 -> 952 (swarm, stream and pairs got 10-20 ticks faster). Against the healball's slow M3R3 the melee
     *  kills the damage and the healers left do nothing, so weighting by HITS undervalues exactly the melee that wins;
     *  and our own breacher (M6A6, five ticks a swamp cell) makes even M4H2 "faster". What decided Ranamar#2 was the
     *  march — his fast army meets the melee before the spawn — and that belongs to the siege's attrition, not here. */
    private const val USE_MELEE_SHARE = true
    /** meleeShare weighs his creeps by their damage (ranged + melee), not their hits (v99). Against Ranamar (25.09.2026,
     *  lost at t≈410) his home guard M1A1 made our breacher M6A6 a full defender against his two kiting M5R1 — deficit
     *  -147, the spawn "waited for the full body", bought two haulers instead, and the kiters husked them and shot the
     *  spawn down with 800 energy of bodies. */
    private const val USE_SHARE_BY_DAMAGE = true
    /** The siege target is kept until it falls; only a spawn on our half takes it from one on his (tick, v85).
     *  OFF — measured live 25.09.2026 and rejected: four unrated games against marlyman123#96 went 0-2-2 where every
     *  earlier build drew. Held on his fortress, the army went at 13000 of work it cannot do — fifteen waves in one
     *  game — and the house fell behind it; chasing his forward spawns had at least cost him his economy. Holding a
     *  target is only right while a wave is out against it (USE_TARGET_COMMIT). */
    private const val USE_TARGET_HOLD = false
    /** The target is kept while a wave is out against it (in the field or holding): a spawn he puts up during the
     *  march does not pull the wave off; between waves the target is chosen as before (tick, v86). */
    private const val USE_TARGET_COMMIT = true
    /** The siege's defenders are those of his that can reach the target within our approach and the siege,
     *  not his whole army wherever it stands (runFighters, v87). */
    private const val USE_LOCAL_DEFENDERS = true
    /** Two losing sieges are compared by the work left on the target: nothing repairs a rampart or a spawn, so a
     *  siege that does more of the work is progress even when it fails (SiegeResult.better, v87).
     *  OFF — measured on the gate 25.09.2026: it alone takes tower+stream 574 -> 1229, tower+healball 521 -> 905 and
     *  fortress 1583 -> 1916. Losing runs compared by work left prefer the melee body wherever nothing wins yet, and
     *  melee is bought into a siege that more guns would have won — the v72 effect again, by another road. */
    private const val USE_PROGRESS_COMPARE = false
    /** The last call's deadline walks and works every spawn he has, not only the target (goNeed, v85). */
    private const val USE_TOUR_CLOCK = true
    private var siegeTargetId: String? = null
    /** The tick a hauler was last ordered, by any of our spawns (spawnIfNeeded, v85). */
    private var haulerOrderedAt = -1
    /** The home fight's window is measured from the first of us to arrive, not from now (homeReady).
     *  OFF — measured 25.09.2026 and not landed: it breaks the gate's `siege6` fixture (six hunters every sixty ticks
     *  at our gates): counted from the first arrival, three and then two of the garrison read "ready", the home fight
     *  started as `fight:wins(308/274[2/3])` and `fight:fire(206/185[1/2])` and lost the garrison by t=750, and the
     *  tower site was left at 610/1250. The draw it came from (76561198870429455#35, a camp 40-48 ticks out that the
     *  post beat 7:0 once it went) stays unexplained by the stub; it needs that opponent live. */
    private const val USE_HOME_FIRST_ARRIVAL = false

    /** ДОСЯГАЕМОСТЬ ЭКСТЕНШЕНА до спавна — ИЗМЕРЕНО ДВУМЯ ЖИВЫМИ МАТЧАМИ 07.09.2026, и спор доков
     *  закрыт. Они противоречили себе на соседних строках: `spawnCreep` — «within SPAWN_RANGE» (20),
     *  `StructureExtension` — «regardless of distance», разработчик (2021, ветка про эту самую арену) —
     *  «remotely». Опыт: один экстеншен, залитый на 100, и заказ тела дороже, чем лежит в спавне.
     *    дальний, 25 клеток: spawnE=995 extE=100 cost=1000 -> err=-6, крип НЕ родился;
     *    контроль, 3 клетки: spawnE=972 extE=100 cost=1000 -> err=null, крип РОДИЛСЯ.
     *  Значит: экстеншены spawnCreep питают (это доказывает контроль), и расстояние их ограничивает
     *  (это доказывает пара). Живая фраза — про SPAWN_RANGE; «regardless of distance» здесь неверна.
     *  Точный радиус опытом не взят: 25 вне, 3 внутри, между ними не мерено — 20 из доков этому не
     *  противоречит. Следствие, ради которого всё и делалось: ДЕШЁВОЙ ТОЧКИ СДАЧИ ЗА 200 НЕ
     *  СУЩЕСТВУЕТ, у дальних куч экстеншен бесполезен, и точка сдачи — по-прежнему спавн за 1000. */
    /** Сколько точек сдачи держим. Предел — не вкус: даровой замер на стенде даёт прибавку и на
     *  четвёртой (farm 13.2 -> 27.0 в тик), но каждая следующая покупается из излишка и потому сама
     *  себя ограничивает притоком. Три — столько успевает окупиться в матче на 400-2000 тиков. */
    private const val FORWARD_SPAWNS = 3

    private const val EXTENSION_REACH = 20

    /** ПОТОЛОК ТЕЛА в энергии: спавн плюс его экстеншены — и он НАМЕРЕННО не используется выборщиками
     *  тел, потому что замер сказал не поднимать его. Читается только приборами (лог, проба).
     *  ЗАМЕР (стенд, 07.09.2026): экстеншены выдавались готовыми и полными с первого тика, то есть
     *  даром, — и всё равно хуже. `tower` 450 → 471 / 545 / 520 при двух, пяти и десяти; `tower+stream`
     *  574 → 1007 / 677 / 919; `tower+fortspawn` 868 → 990 / 584 / 939; `fortress` 1332 → 1261 / 1346 /
     *  1414. Десять клеток из двенадцати хуже. Прогон осады обещал обратное и не соврал — внутри осады
     *  тело на 1200 действительно кончает её за 17 тиков против 20, — но он не видит, чем тело платит
     *  СНАРУЖИ: спавн один и последователен, три тика на часть, и тело в 22 части держит его 66 тиков
     *  против 36. Армия растёт реже и крупнее, а решает её темп. Это же объясняет, почему за 187
     *  сохранённых реплеев экстеншен не построил НИ ОДИН соперник. */
    private var bodyCap = SPAWN_ENERGY_CAPACITY

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
    /** The influence-map overlay (InfluenceMap.drawDebug): OFF in live matches since v77. It cost a tick's CPU budget in
     *  real matches — `Script execution timed out` in 11 of the 20 games of the v73 series (24.09.2026), 180-424 ticks in
     *  five of them, the army frozen 140-300 ticks at a stretch — and ~156 of one loss's 355 overruns were spent drawing
     *  it after the moves were resolved. It decides nothing; turn it on only to look at a single game. */
    private const val DEBUG_VISUALS = false
    private const val LOG_EVERY = 10

    /**
     * CPU BY PHASE (v77, copied from Pain and Gain's `cpuMark`/`cpuSummary`, 07.09.2026). The arena gives a tick
     * `cpuTimeLimit` (50 ms) and kills the script past it; the bot had no measure of its own CPU at all. Every phase of
     * [tick] is marked; once a tick has spent more than [CPU_WARN_MS] every further mark is printed at once as
     * `cpu t=N <phase> at=<ms>` — so the last such line before a `Script execution timed out` names the last phase that
     * finished, and the one after it is the culprit. At the end of a slow tick (over [CPU_SLOW_MS]), on the first three
     * ticks and every hundred ticks the whole split is printed (`cpu t=N total=…: phase=ms …`), and every hundred ticks
     * the slowest tick since the last report. getCpuTime() is nanoseconds since the tick started; the stub answers 0,
     * so the gate's logs stay deterministic.
     */
    private const val CPU_WARN_MS = 35.0
    private const val CPU_SLOW_MS = 25.0
    private val cpuPhases = ArrayList<Pair<String, Double>>()
    private var cpuMaxMs = 0.0
    private var cpuMaxTick = 0
    private var cpuSlowTicks = 0
    private fun cpuMs(): Double = try { getCpuTime() / 1_000_000.0 } catch (e: Throwable) { 0.0 }
    /** The arena's per-tick limit in ms (100 in Spawn and Swamp, read from arenaInfo; 50 if it says nothing). */
    private fun cpuBudgetMs(): Double = (arenaInfo.cpuTimeLimit.toDouble() / 1_000_000.0).let { if (it > 0.0) it else 50.0 }
    /** Share of the tick the optional forward-spawn search may start a step under (see forwardSpot): the phases after
     *  it took up to ~50 ms on the slowest live ticks of v79. */
    private const val FWD_CPU_SHARE = 0.45
    private fun cpuMark(phase: String) {
        val now = cpuMs()
        cpuPhases.add(phase to now)
        if (DEBUG_LOG && now > CPU_WARN_MS) println("cpu t=${getTicks()} $phase at=${(now * 10).toInt() / 10.0}")
    }
    private fun cpuSummary() {
        val ms = cpuMs()
        if (ms > cpuMaxMs) { cpuMaxMs = ms; cpuMaxTick = getTicks() }
        if (ms > CPU_SLOW_MS) cpuSlowTicks++
        if (DEBUG_LOG && (getTicks() <= 3 || ms > CPU_SLOW_MS || getTicks() % 100 == 0)) {
            var prev = 0.0
            val parts = cpuPhases.joinToString(" ") { (ph, at) -> val d = at - prev; prev = at; "$ph=${(d * 10).toInt() / 10.0}" }
            println("cpu t=${getTicks()} total=${(ms * 10).toInt() / 10.0}: $parts")
        }
        cpuPhases.clear()
        if (DEBUG_LOG && getTicks() % 100 == 0) {
            println("cpu t=${getTicks()} max=${(cpuMaxMs * 10).toInt() / 10.0} at=$cpuMaxTick slow=$cpuSlowTicks " +
                "limit=${arenaInfo.cpuTimeLimit / 1_000_000}/${arenaInfo.cpuTimeLimitFirstTick / 1_000_000}")
            cpuMaxMs = 0.0; cpuMaxTick = 0; cpuSlowTicks = 0
        }
    }

    private val DIRECTIONS = listOf(
        0 to 0, -1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1,
    )

    private var greeted = false
    private var mapLogged = false
    private var pushing = false

    /** Волна в поле, а осада по фронту не сходится: волна держит кромку башни и ждёт подкрепления (см. newPushing). */
    private var siegeHold = false
    private var lastPushReason = ""

    /**
     * REACH COUNTERS — which named row of a decision ended it, counted over the whole match and printed as
     * `reach spawn …` / `reach posture …` every 5×LOG_EVERY ticks (cumulative, so `series.py metrics --t1 N` reads the
     * value at tick N). A change aimed at a row nobody reaches is inert; this is the instrument that says so BEFORE a
     * match is played (the Pain and Gain lesson: three changes in a row went into a branch that moved nobody).
     * Spawn rows: one per free spawn per tick, the `return` that ended [spawnIfNeeded], and `busy` for a tick in which
     * no spawn was free (every one still growing a body) — so `busy` over the ticks is the production's utilisation.
     * Posture rows: one per tick with an army — the posture (`defend`/`stronger`/`hold`/`lastCall`, see lastPushReason)
     * and the home-fight verdict (`calm`/`hHold`/`hFire`/`hGates`/`hWins`, see homeMode). Nothing here changes a
     * decision.
     */
    private val spawnReach = LinkedHashMap<String, Int>()
    private val postureReach = LinkedHashMap<String, Int>()
    private fun reach(row: String) { spawnReach[row] = (spawnReach[row] ?: 0) + 1 }
    private fun reachLine(what: String, rows: Map<String, Int>) =
        "reach $what t=${getTicks()} " + rows.entries.joinToString(" ") { "${it.key}=${it.value}" }

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

    /** Доживает ли спавн до полного бойца при нынешнем входящем уроне (прошлый тик). Пока доживает,
     *  смотритель может брать из спавна: башня — такое же вложение, как боец, и достраивать её надо.
     *  Не доживает — каждая единица принадлежит бойцу, как и в правиле лагеря. */
    private var lastSpawnOutlivesFighter = true

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
    /**
     * УБИТЫЕ ВОЗЧИКИ — и груз, который ушёл вместе с ними. Прибор, а не правило: разбор двадцати матчей
     * 08.09.2026 дал цепочку, у которой это первое звено, и только его в НАШЕМ логе не было видно
     * вовсе. В победе над けろびー он не убил ни одного возчика (7 выстрелов по ним), приток держался
     * 13.5 в тик, армия стояла 5.0 против его 1.1, и его лекари отменили 3% нашего урона. В двух
     * поражениях того же ряда он убил семь и четыре (111 и 225 выстрелов), приток упал до 9.2 и 11.6,
     * армия — до 2.7 против 3.2 и 3.0 против 2.1, а лечение отменило 85% и 55%: ниже своей скорости
     * лечения урон не убивает никого, поэтому размен переворачивается не на проценты, а с 9:16 в нашу
     * пользу на 18:3 против нас — в матче, где мы потратили в 1.7 раза БОЛЬШЕ него.
     * Цепочка сходится на трёх матчах; поле печатается, чтобы `series.py metrics` проверил её на двадцати.
     */
    private val knownHaulers = HashSet<String>()
    private var haulersLost = 0
    private var haulerCargoLost = 0
    private val haulerCargo = HashMap<String, Int>()

    private fun measureHaulerLoss(ctx: Ctx) {
        val alive = ctx.myCreeps.mapTo(HashSet()) { it.id }
        val gone = knownHaulers.filter { it !in alive }
        for (id in gone) {
            haulersLost++
            haulerCargoLost += haulerCargo.remove(id) ?: 0
            knownHaulers.remove(id)
        }
        for (h in ctx.haulers) {
            knownHaulers.add(h.id)
            haulerCargo[h.id] = h.store[RESOURCE_ENERGY] ?: 0
        }
    }

    private val siteSeen = HashMap<String, Pair<Int, Int>>()

    /** Площадка -> (тиков, что смотритель провёл В ДОСЯГАЕМОСТИ от неё; её прогресс на первом таком
     *  тике). Знаменатель наблюдаемой скорости стройки: см. siteReadyTicks. */
    private val siteWork = HashMap<String, Pair<Int, Int>>()

    private fun measureSiteWork(ctx: Ctx) {
        for (s in ctx.mySites) {
            if (ctx.builders.none { getRange(it, s) <= BUILD_RANGE }) continue
            val was = siteWork[s.id]
            siteWork[s.id] = if (was == null) 1 to (s.progress ?: 0) else (was.first + 1) to was.second
        }
        siteWork.keys.retainAll { id -> ctx.mySites.any { it.id == id } }
    }

    /** Кончится ли осада раньше, если следующее тело — мили: ответ СИМУЛЯЦИИ, снятый в runFighters и
     *  прочитанный на следующем тике теми, кто решает состав, — отбором волны (кого пускать) и спавном
     *  (что покупать). Один ответ на оба вопроса: разойдясь, они дают армию, которую покупают для
     *  штурма и не выпускают. */
    /**
     * ЛЕЧЕНИЕ ВРАГА НА ОДНО НАШЕ ТЕЛО — то, чего не знал перебор тел. bodyValue считала урон × хиты и
     * молчала о том, что лечение вычитается из урона ПЕРВЫМ: против 36 лечения в тик (его M5H3, 12 за
     * часть вплотную) наши 40 урона — это 4, а не 40. Замерено на пяти реплеях: его лекари отменяют от
     * 12% до 63% всего, что мы по нему выстрелили, при уптайме 99% против нашего 81%.
     * Делится на число НАШИХ стрелков не для красоты: по одной цели бьёт вся волна, и лечение
     * вычитается из СУММЫ, а не из каждого тела. Отсюда и направление: чем больше армия, тем меньше
     * поправка, и при трёх и более стрелках она перестаёт менять ответ вовсе (M8R4 остаётся M8R4).
     * Одиночка же выбирает M5R5 — тело, которым играет けろびー.
     */
    private var foeHeal = 0

    /**
     * ЧЕРЕЗ СКОЛЬКО КОНЧИТСЯ МАТЧ, ЕСЛИ КОНЧИМ ЕГО МЫ — подход волны плюс её осада, то самое goNeed,
     * которым бот уже решает «пора выходить». Экономическому вложению нужен горизонт, и это
     * единственный горизонт, который бот вообще способен вычислить.
     * Четвёртая попытка точки сдачи спрашивала его на 177-м тике, получала «никогда» (армии ещё нет) и
     * заключала, что горизонта не существует. Вывод был неверен: вопрос задавался слишком рано.
     * Точка покупается, когда флот собран, приток замерен и тысяча свободна, — то есть после 500-го
     * тика, когда волна уже есть и вердикт осмыслен. Замер, ради которого это и понадобилось: в
     * tower+fortspawn площадка ставилась на 570-м и достраивалась к 920-му, а матч кончался на 868-м —
     * 900 энергии и смотритель в стройку, которая не успела отдать ни одной единицы.
     */
    private var siegeEndsIn = Long.MAX_VALUE / 4

    private var assaultWantsMelee = false

    /** Тот же вопрос и тот же прогон, что у assaultWantsMelee, но про ЛЕКАРЯ. Гейта «спавн в броне»
     *  здесь нет и не нужно: лекарь не наносит урона, поэтому по голой цели прогон сам отвечает
     *  «медленнее» — правило гейтит себя тем, что считает. */
    private var assaultWantsHealer = false

    /** ОДНОКРАТНАЯ ПРОБА РАССТОЯНИЯ, а не боевое правило. Доки Арены противоречат себе: `spawnCreep`
     *  говорит «в пределах SPAWN_RANGE», `StructureExtension` — «на любом расстоянии»; кода движка нет,
     *  консоли в Арене нет, и различить это может только матч. Различает УСПЕХ, а не код возврата:
     *  -6 — это сразу ERR_NOT_ENOUGH_ENERGY, ERR_NOT_ENOUGH_RESOURCES и ERR_NOT_ENOUGH_EXTENSIONS.
     *  Включается EXT_PROBE ровно на один матч; выключенная, вся ветка мертва.
     *  Ответ нужен не потолку (тот отвергнут замером, см. bodyCap), а ТОЧКЕ СДАЧИ: если расстояние не
     *  ограничивает, экстеншен у дальних куч стоит 200 вместо 1000 за спавн. */
    private const val EXT_PROBE = false

    /** Фаза пробы. true — ДАЛЬНИЙ экстеншен (25 клеток, спорный радиус); false — КОНТРОЛЬ, ближний.
     *  Контроль обязателен и не факультативен: -6 это сразу три ошибки, и «дальний не сработал» без
     *  «ближний сработал» не значит ничего — могло оказаться, что экстеншены не питают spawnCreep
     *  вовсе и неверны ОБЕ фразы доков. */
    private const val EXT_PROBE_FAR = false
    private var extProbeDone = false

    /** Проба ответила (в любую сторону) — дальше бот живёт обычной жизнью. */
    private var extAnswered = false

    /** Считать ли экстеншены в бюджете тела. Начинаем с «да» (так говорят и доки, и разработчик) и
     *  ОПРОВЕРГАЕМ фактом: заказ тела дороже, чем лежит в спавне, оплачивается только экстеншенами, и
     *  если он не прошёл — они не досягаемы, и больше мы на них не рассчитываем до конца матча. Без
     *  этого недосягаемый экстеншен заклинил бы спавн навсегда: бюджет обещает тело, которого касса не
     *  оплачивает, и так каждый тик. */
    private var extReach = true

    /** Стройки этого тика — по одной записи на площадку, у каждой свои часы и свой источник энергии
     *  (см. SiteJob). Считается раз в тик в spawnIfNeeded и читается позже строителями; пока спавн
     *  рождает крипа, список остаётся с прошлого тика — он про экономику и меняется медленно. */
    private var siteJobs: List<SiteJob> = emptyList()

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

    /** Был ли враг в нашей дальности рядом с бойцом в ПРОШЛОМ тике — урон приходит в конце тика, и
     *  разбирать его надо по обстановке, в которой он нанесён. */
    private val nearLast = HashMap<String, Boolean>()
    private var takenTrade = 0
    private var takenHusk = 0
    private var takenUnans = 0
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
    /** Точки, которые мы уже видели, и энергия, с которой каждая ПОЯВИЛАСЬ. Ключи не чистятся: пропавшая
     *  и вернувшаяся точка (например, ставшая на тик недостижимой) иначе сосчиталась бы дважды. */
    private val siteFirstSeen = HashMap<String, Int>()
    private val appeared = ArrayDeque<Pair<Int, Int>>()

    private val delivered = ArrayDeque<Pair<Int, Int>>()
    private val haulerStore = HashMap<String, Int>()
    private var firstHaulerTick = -1

    /**
     * ОТДАЧА ОТ ПОСЛЕДНЕГО РОСТА ФЛОТА. Ёмкость и ЗАМЕРЕННАЯ сдача в тик покупки прошлого хаулера —
     * этого хватает, чтобы спросить о следующем то единственное, что имеет значение: подняла ли
     * прошлая покупка доставку на самом деле.
     * Прежний сторож (fleetDelivers) сравнивал замер с ПРОГНОЗОМ, а прогноз падает вместе с ближними
     * кучами — и «замер выше упавшего прогноза» читалось как «ёмкость и есть узкое место». В матче
     * 6a9ffb75 (けろびー#19, поражение) флот дорос до одиннадцати хаулеров при ДВУХ бойцах, а
     * замеренная сдача за то же время упала с 14-15 до 3-5 в тик: деньги ушли в возчиков, которым
     * нечего возить, и армии не осталось. Тот же счёт на стенде: со вторым спавном рейсы короче,
     * capacityBound охотнее говорит «да», и флот упирается в шестнадцать при сдаче 15-17.
     */
    private var fleetMark = 0
    private var fleetMarkIncome = -1.0
    private var fleetMarkTick = -1

    /**
     * САМОЕ БОЛЬШОЕ ЧИСЛО ЕГО СПАВНОВ ЗА МАТЧ. Это не счёт целей, а ЕГО ОТВЕТ НА ВОПРОС О ГОРИЗОНТЕ —
     * тот самый, которого у нас нет и которого четыре попытки подряд требовали от симуляции осады,
     * получая «никогда». Тысячу в производство кладёт только тот, кто рассчитывает прожить достаточно,
     * чтобы она отбилась; поставив второй спавн, противник СКАЗАЛ, что матч будет длинным и
     * экономическим. Против того, кто идёт убивать сразу (все 26 сценариев стенда — один спавн от
     * начала до конца), та же тысяча — это отнятый боец, и замер это показывает без двусмысленности:
     * точка сдачи, поставленная безусловно, роняет ВЕСЬ стенд (tower+healball 521->806, tower+stream
     * 574->992, tower+pairs 538->749, stream17 888->973, siege6 теряет башню). Против けろびー#19 он
     * доходит до второго спавна к 220-300 тику в шести матчах из шести и кончает с четырьмя-шестью.
     * Пик, а не текущее число: убитый спавн не отменяет того, что он его строил.
     */
    private var foeSpawnPeak = 0

    private var lastSpawnEnergy = -1
    private var regenSamples = 0
    private var regenSum = 0
    private var deliveringLastTick = false

    // ---------- кэши на тик ----------
    /** id точки -> поле в шагах до неё (пустой хаулер). */
    private val siteStepsCache = HashMap<String, IntArray>()

    /** упакованная клетка цели -> поле потока к ней (гружёный/боец, болото ×5). */
    private val flowCache = HashMap<Int, IntArray>()

    /** Поле подхода под огнём — свой кэш на тик (см. assaultTo). */
    private val assaultCache = HashMap<Int, IntArray>()

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

    /**
     * HIS RAMPARTS BY CELL (v82): hits of every enemy rampart, keyed x*100+y, refreshed each tick. A rampart shields
     * EVERYTHING on its cell — a tower, a spawn, a creep standing on it: our damage there goes into the rampart first,
     * and nothing repairs it (the Arena creep has no `repair`). The siege simulation knew this only for the rampart on
     * the spawn; the fortress of the v73 series (marlyman123, ricardo) puts one on the tower and three to four more
     * under its defenders, so `sim=win` sent waves at 13000 of tower work it priced at 3000, and 18-62 % of their fire
     * against structures went into the tower's rampart.
     */
    private val enemyShield = HashMap<Int, Int>()
    private fun shieldAt(p: Position) = enemyShield[p.x * 100 + p.y] ?: 0

    /** The wave front's siege is won by the direct plan (see siegeOutcome): in the storm, fire and swings go to the
     *  spawn, not to a defender behind a rampart and not to the tower. Set each tick with siegeGo. */
    private var stormDirect = false

    /** Строящаяся башня врага: площадка и через сколько тиков достроится — по наблюдаемому темпу, а
     *  пока темпа нет, по WORK строителей рядом. Для симуляции осады башня, которая встанет до конца
     *  осады, — башня: волна ушла при площадке 945/1250 и была отозвана через 35 тиков, когда башня
     *  встала (матч 13); ждать первого выстрела, чтобы поверить в башню, — ошибка матча 11. */
    private class PendingTower(val info: TowerInfo, val eta: Int)

    private class Ctx(
        val mySpawn: StructureSpawn,           // ДОМ: геометрия, тревога, оборона — всё считается от него
        val mySpawns: List<StructureSpawn>,    // все наши живые: второй строится у энергии (forwardSpot)
        val enemySpawn: StructureSpawn?,       // ЦЕЛЬ осады — ближайший его спавн, не обязательно исходный
        val enemySpawns: List<StructureSpawn>, // ВСЕ его живые спавны: он их строит, и победа — это все
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
        val loadedToSpawn: IntArray,  // гружёный к ДОМУ (болото ×5): геометрия, тревога, «дома ли»
        val stepsToSpawn: IntArray,   // пустой к ДОМУ, шаги
        // …а ВОЗКА меряется до БЛИЖАЙШЕГО нашего спавна: хаулер сдаёт туда (dropOff), и в этом весь
        // смысл второй точки сдачи. Пока спавн один, оба поля совпадают с домашними слово в слово
        val haulLoaded: IntArray,     // гружёный к ближайшему нашему спавну
        val haulSteps: IntArray,      // пустой к ближайшему нашему спавну, шаги
        val enemyApproach: IntArray,  // враг к нашему спавну по ЕГО проходимости
        val sites: List<EnergySite>,
        val enemyTowers: List<TowerInfo>,
        val ramparts: List<StructureRampart>,
        val pendingEnemies: List<Creep>,   // боевые крипы врага, ещё рождающиеся в его спавне (разведка)
        val pendingTowers: List<PendingTower>, // площадки башен врага с оценкой достройки
        val myTowers: List<StructureTower>,    // наши живые башни
        val myExtensions: List<StructureExtension>, // наши живые экстеншены: потолок тела и куда сдавать
        val mySites: List<ConstructionSite>,   // наши недостроенные площадки
    )

    fun tick() {
        // a tick killed by the cpu limit never reached cpuSummary, and its marks polluted the next tick's split
        cpuPhases.clear()
        // Спавнов у нас может быть больше одного. Порядок getObjectsByPrototype — порядок создания,
        // поэтому первый и есть ДОМ: на нём держится вся геометрия, и она не переезжает от стройки
        val mySpawns = getObjectsByPrototype(StructureSpawn::class).filter { it.my == true && it.exists }
        val mySpawn = mySpawns.firstOrNull() ?: return
        // СПАВНОВ У НЕГО СКОЛЬКО УГОДНО. Порядок getObjectsByPrototype — порядок создания, поэтому
        // первый живой и есть его ИСХОДНЫЙ: на нём держится геометрия карты (поля расстояний, чья
        // половина, чьи кучи энергии), и она не должна ездить вслед за целью. Цель осады — ДРУГОЕ:
        // ближайший к нам, потому что до него ближе идти и потому что он опаснее — построенный на
        // нашей половине спавн рождает армию у нас за спиной (けろびー#17 ставил два таких)
        val enemySpawns = getObjectsByPrototype(StructureSpawn::class).filter { it.my == false && it.exists }
        val enemyHome = enemySpawns.firstOrNull()
        // THE TARGET HOLDS UNTIL IT FALLS (v85). Re-chosen every tick as the nearest, the target was taken by every
        // spawn he put up: thirteen switches in four games against marlyman123#96, each new forward spawn pulling the
        // wave off the edge of his main one, which got 0, 57, 1300 and 0 damage — and a draw on every clock. His
        // forward spawns are free to him (a container dumped on the ground) and cheap to us (3000 hits, no rampart),
        // his main is built once and never repaired: it is the one a whole group must be kept on. So the target, once
        // chosen, is kept while it stands; the one exception is a spawn on OUR half while the target is on his — it
        // breeds his army behind our back (ricardo's at our corner, stachu's on our half) and is also on the way.
        val held = enemySpawns.firstOrNull { it.id == siegeTargetId }
        val onOurHalf: (StructureSpawn) -> Boolean = { s -> enemyHome != null && getRange(s, mySpawn) < getRange(s, enemyHome) }
        val intruder = enemySpawns.filter { onOurHalf(it) }.minByOrNull { getRange(mySpawn, it) }
        // the spawn this army takes soonest, by the last scoring (see targetCost); none it can take — null
        val takeable = if (!USE_TARGET_BY_TAKE) null else
            enemySpawns.filter { (targetCost[it.id] ?: Long.MAX_VALUE) < Long.MAX_VALUE / 4 }.minByOrNull { targetCost[it.id]!! }
        val heldTakeable = held != null && (targetCost[held.id] ?: Long.MAX_VALUE) < Long.MAX_VALUE / 4
        val enemySpawn = when {
            // A SPAWN OF HIS ON OUR HALF COMES FIRST, even over a committed wave (v97): けろびー's (77,25), 24 cells from
            // ours, bred his army behind us for 500 ticks, 3000 hits and no rampart, while the wave went to his main
            // fifty cells off and died there
            USE_TARGET_COMMIT && intruder != null && (held == null || !onOurHalf(held)) -> intruder
            // committed: a wave is out against the held target (the wave map outlives the tick; see runFighters) —
            // while it can take it, or while there is nothing else it can take (v104)
            USE_TARGET_COMMIT && held != null && wave.isNotEmpty() && (!USE_TARGET_BY_TAKE || heldTakeable || takeable == null) -> held
            // between waves, or with the held one out of reach: the spawn it takes soonest (v104)
            takeable != null -> takeable
            !USE_TARGET_HOLD || held == null -> enemySpawns.minByOrNull { getRange(mySpawn, it) }
            intruder != null && !onOurHalf(held) -> intruder
            else -> held
        }
        siegeTargetId = enemySpawn?.id
        siteStepsCache.clear()
        assaultCache.clear()

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
                "enemy spawns=${enemySpawns.joinToString(" ") { "(${it.x},${it.y})" }.ifEmpty { "none" }}")
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
        val builders = active.filter { c -> c.body.any { it.type == WORK } && !isPileBuilder(c) }
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
        val myExtensions = getObjectsByPrototype(StructureExtension::class).filter { it.exists && it.my == true && (it.hits ?: 0) > 0 }
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
        // EVERY SITE OF HIS AND WHEN IT BECOMES A STRUCTURE (v103, see stompJobs): observed pace, or his WORK within
        // three cells; neither — it is not being built now and waits for us
        enemySitesNow = enemySites.map { site ->
            val progress = site.progress ?: 0
            val (t0, p0) = siteSeen.getOrPut(site.id) { getTicks() to progress }
            val observed = if (getTicks() - t0 >= APPROACH_WINDOW / 2) (progress - p0).toDouble() / (getTicks() - t0) else -1.0
            val builders = enemyCreeps.sumOf { c -> if (getRange(c, site) <= 3) c.body.count { it.type == WORK && it.hits > 0 } else 0 }
            val rate = if (observed > 0.0) observed else builders * BUILD_POWER.toDouble()
            site to (if (rate <= 0.0) Int.MAX_VALUE / 2 else ceil(((site.progressTotal ?: 0) - progress) / rate).toInt())
        }
        // темп меряется и у СВОЕЙ площадки (towerReadyTicks): прибор один на обе стороны
        siteSeen.keys.retainAll { id -> enemySites.any { it.id == id } || mySites.any { it.id == id } }
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
        enemyShield.clear()
        for (r in ramparts) if (r.my != true && (r.hits ?: 0) > 0) enemyShield[r.x * 100 + r.y] = (enemyShield[r.x * 100 + r.y] ?: 0) + (r.hits ?: 0)
        InfluenceMap.setEnemyBlocked(blockedForEnemy.mapTo(HashSet()) { it.x * 100 + it.y })
        cpuMark("objects")
        val dangerMatrix = InfluenceMap.dangerCostMatrix(enemyCreeps, blocked)
        cpuMark("danger")

        DistanceMap.syncWalls(walls.size) // снесённая стена пролома открывает проход — поля заново
        // геометрия — по ИСХОДНОМУ спавну: ensureBuilt кэширует поле по подписи рампартов и цели не
        // видит вовсе, а «наша половина» не может переезжать оттого, что он построил спавн в центре
        if (enemyHome != null) DistanceMap.ensureBuilt(mySpawn, enemyHome)

        // FIELDS OUTLIVE THE TICK WHILE THE OBSTACLES DO (v93; v79 did it for the drop cells). A flow or step field
        // is a pure function of its target, the terrain and `blocked` (structure walls included), so both caches live
        // until the SET of blocked cells changes. With v92's pile spawns the bot held six and more spawns, and the two
        // fields per spawn per tick (nearestField) took 17 ms of the slow ticks — 222 overruns in one game
        val blockedSig = blocked.mapTo(HashSet()) { it.x * 100 + it.y }.sorted().toIntArray()
        if (!blockedSig.contentEquals(cellStepsSig)) { cellStepsCache.clear(); flowCache.clear(); cellStepsSig = blockedSig }
        fun flowF(t: Position) = flowCache.getOrPut(t.x * 100 + t.y) { DistanceMap.flowFieldTo(t, blocked) }
        fun stepF(t: Position) = cellStepsCache.getOrPut(t.x * 100 + t.y) { DistanceMap.stepFieldTo(t, blocked) }
        val loadedToSpawn = flowF(mySpawn)
        /** Поэлементный минимум полей до всех наших спавнов: рейс считается до того, куда сдают. */
        fun nearestField(steps: Boolean): IntArray {
            if (mySpawns.size <= 1) return if (steps) stepF(mySpawn) else loadedToSpawn
            var acc: IntArray? = null
            for (sp in mySpawns) {
                val f = if (steps) stepF(sp) else flowF(sp)
                val cur = acc
                if (cur == null) acc = f.copyOf()   // a copy: the minimum is taken in place, and f is a cached field
                else for (i in cur.indices) {
                    val a = cur[i]
                    val b = f[i]
                    cur[i] = if (a < 0) b else if (b < 0) a else minOf(a, b)
                }
            }
            return acc ?: loadedToSpawn
        }
        val stepsToSpawn = stepF(mySpawn)
        val haulLoaded = nearestField(false)
        val haulSteps = if (mySpawns.size <= 1) stepsToSpawn else nearestField(true)
        val enemyApproach = DistanceMap.flowFieldTo(mySpawn, blockedForEnemy)
        val enemyLoaded = enemyHome?.let { DistanceMap.flowFieldTo(it, blockedForEnemy) }

        cpuMark("fields")
        val sites = collectSites(combatEnemies, loadedToSpawn, enemyLoaded).filter { !reservedForPile(it) }
        // ПОТОЛОК ТЕЛА этого тика: спавн плюс досягаемые экстеншены. Считается ДО любого выбора тела
        bodyCap = SPAWN_ENERGY_CAPACITY +
            myExtensions.count { getRange(it, mySpawn) <= EXTENSION_REACH } * EXTENSION_ENERGY_CAPACITY
        val ctx = Ctx(mySpawn, mySpawns, enemySpawn, enemySpawns, myCreeps, active, haulers, fighters, builders, enemyCreeps, combatEnemies, blocked, blockedForEnemy, dangerMatrix, loadedToSpawn, stepsToSpawn, haulLoaded, haulSteps, enemyApproach, sites, enemyTowers, ramparts, enemyPending, pendingTowers, myTowers, myExtensions, mySites)

        rememberDrops(sites)
        logSites(sites)
        measureRegen(mySpawn, haulers.any { (it.store[RESOURCE_ENERGY] ?: 0) > 0 && it.getRangeTo(mySpawn) <= 1 })
        homeHits.addLast(getTicks() to (mySpawn.hits ?: SPAWN_HITS))
        while (homeHits.isNotEmpty() && homeHits.first().first < getTicks() - PRODUCTION_WINDOW) homeHits.removeFirst()
        measureDelivery(ctx)
        measureSupply(ctx)
        measureSiteWork(ctx)
        measureHaulerLoss(ctx)
        cpuMark("measure")
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
        val defenders = fighters.filter { inArms(it) }
        val ourDefense = ourPowerOf(defenders, combatEnemies)
        val enemyPower = enemyPowerOf(combatEnemies, fighters)
        // для решений спавна враг — «скоро»: с теми, кто ещё рождается у его спавна
        val threatsSoon = combatEnemies + enemyPending
        val enemyArrival = enemyArrivalTicks(ctx)
        // THE SPAWN ANSWERS THE FIGHT THAT WILL BE AT OUR HOUSE (v101): those walking at us, those already in the alarm
        // ring and those being born. His home guard is not in it — every Ranamar bot keeps an M1A1 at his spawn, eighty
        // cells off, and it made our breacher a full defender against his two kiting M5R1 at our spawn (deficit -147 in
        // all four matches, lost at t≈410); his army standing at home joins the moment it walks
        val homeBound = if (!USE_HOME_BOUND) threatsSoon else threatsSoon.filter { e ->
            (arrivalById[e.id] ?: Int.MAX_VALUE / 2) < Int.MAX_VALUE / 2 || enemyApproach[e.x * 100 + e.y] in 0..SPAWN_ALARM_TICKS
        }
        val spawnUnderFire = InfluenceMap.fireAt(mySpawn.x, mySpawn.y, combatEnemies) > 0.0
        cpuMark("threat")
        measureHomeFight(ctx)
        measureExchange(ctx)
        foeHeal = foeHealPerBody(ctx)
        cpuMark("home")
        // ВТОРОЙ СПАВН ПРОИЗВОДИТ, ТОЛЬКО ЕСЛИ ЕГО СПРАШИВАЮТ. Функция брала ОДИН спавн на тик, и пока
        // спавн был один, это было одно и то же; со вторым — нет: энергия делится между складами
        // (хаулеры сдают в ближайший), а заказ по-прежнему уходит в один, отчего ни один не набирает
        // тысячу на полное тело. Замерено на стенде (twospawn): постройка второго и третьего спавнов
        // без этого хода роняет сбор с 20.1 до 12.5 в тик и снимает победу вовсе. Площадки ставит
        // ПЕРВЫЙ ход тика — они общие, и три хода поставили бы три
        val freeSpawns = ctx.mySpawns.filter { it.spawning == null }.sortedByDescending { it.store[RESOURCE_ENERGY] ?: 0 }
        if (freeSpawns.isEmpty()) spawnIfNeeded(ctx, defenders, homeBound, alarm, enemyArrival, spawnUnderFire, null, true)
        else freeSpawns.forEachIndexed { i, sp ->
            spawnIfNeeded(ctx, defenders, homeBound, alarm, enemyArrival, spawnUnderFire, sp, i == 0)
        }
        cpuMark("spawn")
        runTowers(ctx)
        runHaulers(ctx)
        cpuMark("haulers")
        runBuilders(ctx)
        if (USE_PILE_SPAWN) runPileBuilder(ctx)
        cpuMark("builders")
        val ourOffense = runFighters(ctx, enemyPower, alarm)
        cpuMark("fighters")

        TrafficManager.resolve(active.filter { canMove(it) }, myCreeps + enemyCreeps)
        cpuMark("traffic")
        InfluenceMap.pruneStances(myCreeps.mapTo(HashSet()) { it.id })
        enemyPrevCell.clear()
        for (e in enemyCreeps) enemyPrevCell[e.id] = e.x * 100 + e.y
        if (DEBUG_LOG) logStuck(active, enemyCreeps)

        if (DEBUG_VISUALS) InfluenceMap.drawDebug(fighters, myCreeps, enemyCreeps)
        cpuMark("debug")

        if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
            val carried = haulers.sumOf { it.store[RESOURCE_ENERGY] ?: 0 }
            val usable = usableSites(ctx)
            println(
                "t=${getTicks()} spawnE=${mySpawn.store[RESOURCE_ENERGY]} spawning=${mySpawn.spawning != null} " +
                    "haulers=${haulers.size} carried=$carried haulersLost=$haulersLost cargoLost=$haulerCargoLost " +
                    "fighters=${fighters.size} enemies=${enemyCreeps.size}/${combatEnemies.size} foeHeal=$foeHeal " +
                    "sites=${sites.size} usable=${usable.sumOf { it.energy }} income=${projectedIncome(ctx, usable).toInt()}/${targetIncome().toInt()}${realisedIncome().let { if (it < 0) "" else "r" + it.toInt() }}s${supplyRate().toInt()} " +
                    "push=$pushing($lastPushReason) alarm=$alarm home=$homeMode our=${ourOffense.toInt()}/${ourDefense.toInt()} enemy=${enemyPower.toInt()} pending=${enemyPending.size} arrival=${if (enemyArrival >= Int.MAX_VALUE / 4) "-" else enemyArrival.toString()} towers=${enemyTowers.count { it.fed }}/${enemyTowers.size}+${pendingTowers.size} enemySpawns=${enemySpawns.size}@${enemySpawn?.let { "${it.x},${it.y}" } ?: "-"} enemySpawnHits=${enemySpawn?.hits}+${spawnRampartHits(ctx)} " +
                    "mine=${myTowers.joinToString(",") { "T(${it.x},${it.y})h=${it.hits}e=${it.store[RESOURCE_ENERGY]}" }.ifEmpty { "-" }}${ctx.mySites.joinToString("") { "+site(${it.x},${it.y})${it.progress}/${it.progressTotal}" }} home=${(homeShare() * 100).toInt()}%"
            )
            if (getTicks() % (LOG_EVERY * 5) == 0) {   // cumulative, so every fifty ticks loses nothing
                println(reachLine("spawn", spawnReach))
                println(reachLine("posture", postureReach))
                if (pileWhy.isNotEmpty()) println(reachLine("pile", pileWhy))
            }
            if (getTicks() % (LOG_EVERY * 10) == 0) println(TrafficManager.audit())
        }
        cpuSummary()
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

    /** Лечение врага в тик, делённое на число наших живых стрелков: столько лечения приходится на
     *  урон ОДНОГО тела, когда волна бьёт по одной цели. Считается по частям вплотную (HEAL_POWER):
     *  его лекари стоят рядом со своими — 182 лечения вплотную против 11 с расстояния (реплей 6a9eb70c). */
    private fun foeHealPerBody(ctx: Ctx): Int {
        val heal = ctx.enemyCreeps.sumOf { c -> c.body.count { it.type == HEAL && it.hits > 0 } } * HEAL_POWER
        if (heal <= 0) return 0
        val shooters = ctx.fighters.count { hasWeapon(it) }.coerceAtLeast(1)
        // округляется ЗДЕСЬ, а не в ключе кэша: иначе два тика с поправкой 12 и 15 делят ключ, но
        // считаются по-разному, и в кэше остаётся тот ответ, который случился первым
        return (heal / shooters) / RANGED_HEAL_POWER * RANGED_HEAL_POWER
    }

    /** Что карта РОНЯЛА: клетка -> (сколько энергии там появлялось, сколько тиков она живёт).
     *  Решение о второй точке сдачи — про ВСЕ будущие кучи, а не про две лежащие сейчас; лучшая
     *  доступная выборка будущего — то, что уже случилось. У постоянных куч жизнь 0. */
    private val dropHistory = HashMap<Int, Pair<Int, Int>>()

    private fun rememberDrops(sites: List<EnergySite>) {
        for (s in sites) {
            val key = s.pos.x * 100 + s.pos.y
            val was = dropHistory[key]
            val life = maxOf(s.ticksToDecay ?: 0, was?.second ?: 0)
            if (was == null || s.energy > was.first) dropHistory[key] = s.energy to life
        }
    }

    private val cellStepsCache = HashMap<Int, IntArray>()

    /**
     * WHAT THE CACHE ABOVE IS VALID FOR (v79). A step field is a pure function of its target, the terrain, the
     * structure walls (DistanceMap's static layer) and `ctx.blocked` — and the walls are in `ctx.blocked` too. The cache
     * used to be cleared every tick, so `collectRate` rebuilt a full 10000-cell BFS for every cell in `dropHistory` on
     * every tick the forward-spawn placement was asked (every tick against an opponent with two spawns): the live trace
     * of v78 put that block at 50 ms of a 100 ms tick on its slow ticks, and the tick ran out of cpu 115 and 210 times in
     * two games. The fields are now kept while the SET of blocked cells is unchanged — the same arrays the per-tick
     * cache would have rebuilt, so no decision changes.
     */
    private var cellStepsSig = IntArray(0)

    /** Поле шагов ДО клетки — и для кучи из истории, и для склада площадки; живёт, пока не меняются преграды (cellStepsSig). */
    private fun cellSteps(ctx: Ctx, key: Int): IntArray =
        cellStepsCache.getOrPut(key) { DistanceMap.stepFieldTo(InfluenceMap.cell(key / 100, key % 100), ctx.blocked) }

    /** Шаги от клетки до точки — по СОСЕДНЕЙ клетке: сама занята структурой и в поле шагов не
     *  определена, а хаулер и так встаёт рядом. Кандидат меряется так же — построенный спавн тоже
     *  станет препятствием, и сравнение обязано быть одинаковым. */
    private fun stepsFrom(field: IntArray, at: Position): Int {
        var near = -1
        for (dx in -1..1) for (dy in -1..1) {
            val x = at.x + dx
            val y = at.y + dy
            if (x < 0 || y < 0 || x > 99 || y > 99) continue
            val d = field[x * 100 + y]
            if (d >= 0 && (near < 0 || d < near)) near = d
        }
        return near
    }

    /**
     * ОТНОСИТЕЛЬНАЯ скорость сбора при сдаче в эту клетку. Абсолютное число здесь врёт примерно в 2.7
     * раза (модель считает средний рейс по истории падений, а не то, что флот делает на самом деле), и
     * ЭТИМ его пользоваться нельзя; отношение же выходит точным — замер даровым спавном на стенде:
     * модель обещала ×1.31, вышло ×1.33; обещала ×1.77, вышло ×1.77. Поэтому forwardWorth берёт отсюда
     * ТОЛЬКО отношение, а масштаб — у замеренного притока (realisedIncome).
     * keepHome: вторая точка не заменяет дом, а добавляется к нему — хаулер сдаёт в БЛИЖАЙШИЙ свой
     * спавн (dropOff). Без этого середина карты выигрывала у всего, будучи равноудалённой от всех куч.
     */
    private fun collectRate(ctx: Ctx, at: Position, capacity: Int, keepHome: Boolean = false): Double {
        if (capacity <= 0 || dropHistory.isEmpty()) return 0.0
        var reached = 0.0
        var all = 0.0
        var tripWeight = 0.0
        for ((key, drop) in dropHistory) {
            val (energy, life) = drop
            all += energy
            val steps = if (keepHome) {
                val a = stepsFrom(cellSteps(ctx, key), at)
                val h = stepsFrom(cellSteps(ctx, key), ctx.mySpawn as Position)
                if (a < 0) h else if (h < 0) a else minOf(a, h)
            } else stepsFrom(cellSteps(ctx, key), at)
            if (steps < 0) continue
            if (life > 0 && steps > life) continue      // не успеть до распада — этой энергии нет
            reached += energy
            tripWeight += 2.0 * steps * energy
        }
        if (reached <= 0.0 || all <= 0.0 || tripWeight <= 0.0) return 0.0
        return capacity / (tripWeight / reached) * (reached / all)
    }

    private fun canMove(creep: Creep) = creep.body.any { it.type == MOVE && it.hits > 0 }
    private fun hasMelee(creep: Creep) = creep.body.any { it.type == ATTACK && it.hits > 0 }
    private fun isMelee(creep: Creep) = creep.body.any { it.type == ATTACK }
    private fun hasWeapon(creep: Creep) = hasRanged(creep) || hasMelee(creep)
    private fun hasHeal(creep: Creep) = creep.body.any { it.type == HEAL && it.hits > 0 }

    /** ЕЩЁ В СТРОЮ: стреляет ИЛИ лечит. Лекарь оружия не носит, но бой без него — другой бой, и в
     *  обороне он считается наравне (его лечение вычитается из урона врага в enemyPowerOf). Обратное
     *  тоже верно: остов, у которого сбито ЛЕЧЕНИЕ, уходит домой ровно так же, как остов со сбитыми
     *  стволами, — поэтому правило одно на оба случая. */
    private fun inArms(creep: Creep) = hasWeapon(creep) || hasHeal(creep)

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

    /**
     * КУДА СДАВАТЬ: ближайший наш спавн, у которого есть место. Смысл второго спавна в том и есть —
     * рейс считается до НЕГО, а не до дома; при одном спавне ответ всегда дом.
     * Расстояние — по гружёному полю потока (кэш на тик), а не по Чебышеву: спавн стоит в стенном
     * кармане, и прямая линия там врёт.
     */
    private fun dropOff(ctx: Ctx, creep: Creep): StructureSpawn {
        if (ctx.mySpawns.size <= 1) return ctx.mySpawn
        val free = ctx.mySpawns.filter { (it.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0 }
        val pool = if (free.isEmpty()) ctx.mySpawns else free
        return pool.minByOrNull {
            flowTo(ctx, it)[creep.x * 100 + creep.y].let { d -> if (d < 0) Int.MAX_VALUE else d }
        } ?: ctx.mySpawn
    }

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
        ctx.haulSteps[site.pos.x * 100 + site.pos.y].coerceAtLeast(0) + site.myTicks + 2

    /**
     * Целевой приток: сколько энергии в тик спавны способны превратить в бойцов — цена части полного
     * бойца, делённая на время спавна части, УМНОЖЕННАЯ НА ЧИСЛО СПАВНОВ. Приток выше этого копится в
     * очереди хаулеров у спавна и ничего не ускоряет.
     * ⚠️ УМНОЖАТЬ НА ЧИСЛО СПАВНОВ ЗДЕСЬ НЕЛЬЗЯ, ХОТЯ ПО СМЫСЛУ ХОЧЕТСЯ. Довод был прямой: у けろびー#19
     * спавнов четыре-шесть, его потолок вшестеро выше, он собирает 42080 против наших 10400. Но замер
     * отвечает иначе (стенд twospawn): с потолком 83 вместо 27 флот вырастает до предела в шестнадцать
     * хаулеров, замеренная сдача остаётся 15-17 в тик, и шесть тысяч уходят в возчиков, которым нечего
     * возить, — победа на 1976-м тике превращается в её отсутствие к 2000-му. Потолок спавна и правда
     * перестаёт быть узким местом со вторым спавном, но узким местом становится не он, а КАРТА, и
     * запрет на рост флота держится здесь. Считать по спавнам можно будет тогда, когда рост будет
     * ограничен доставкой, а не этим числом.
     */
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
                val home = dropOff(ctx, h)
                val step = fleeStep(h, ctx.combatEnemies, ctx.dangerMatrix) ?: pathStep(h, home, 1, matrixFor(h))
                // шаг бегства в болото с грузом — прижатие на пять тиков под огнём (вес груза, см.
                // periodAt); пустой идёт по болоту как по суше — груз бросаем, он полежит (−1/тик),
                // вернёмся. Четыре гружёных хаулера ползли из-под M5R5 через болото с fatigue=40 (матч 8)
                if (carrying > 0 && step != null && periodAt(h, step.x, step.y) > 1) h.drop(RESOURCE_ENERGY)
                go(h, step)
                if (carrying > 0 && h.getRangeTo(home) <= 1) h.transfer(home, RESOURCE_ENERGY)
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
                val drop = dropOff(ctx, h)
                val spawnFree = drop.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0
                // ГДЕ ЛЕЖИТ ЭНЕРГИЯ, ДЛЯ БЮДЖЕТА НЕВАЖНО: spawnCreep берёт сперва из спавна, потом из
                // экстеншенов, так что сотня в экстеншене — та же сотня. Поэтому сдаём в БЛИЖАЙШЕЕ, где
                // есть место, а не в спавн и только потом в экстеншены: то правило выстраивало доставку
                // в очередь (спавн до тысячи → долив → покупка) и стоило стенду tower+stream 574 → 1612
                val ext = ctx.myExtensions.filter { (it.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0 }
                    .minByOrNull { h.getRangeTo(it) }
                    ?.takeIf { spawnFree == 0 || h.getRangeTo(it) < h.getRangeTo(drop) }
                if (ext != null) {
                    if (h.getRangeTo(ext) <= 1) { h.transfer(ext, RESOURCE_ENERGY); dbg(h, "FILL_EXT", null) }
                    else { val step = pathStep(h, ext, 1, matrixFor(h)); go(h, step); dbg(h, "TO_EXT", null, step) }
                    continue
                }
                if (h.getRangeTo(drop) <= 1) {
                    if (spawnFree > 0) h.transfer(drop, RESOURCE_ENERGY)
                    dbg(h, if (spawnFree > 0) "DELIVER" else "WAIT_FULL", null)
                } else {
                    val stopAt = if (spawnFree > 0) 1 else 2
                    val step = if (h.getRangeTo(drop) > stopAt) pathStep(h, drop, stopAt, matrixFor(h)) else null
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
                    val back = dropOff(ctx, h)
                    val step = if (h.getRangeTo(back) <= 1) { h.transfer(back, RESOURCE_ENERGY); null } else pathStep(h, back, 1, matrixFor(h))
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
    private fun spawnIfNeeded(
        ctx: Ctx, defenders: List<Creep>, threats: List<Creep>, alarm: Boolean, enemyArrival: Int,
        spawnUnderFire: Boolean, only: StructureSpawn?, placeSites: Boolean,
    ) {
        // ПРОИЗВОДИТ ЛЮБОЙ СВОБОДНЫЙ, и берётся тот, у кого энергии больше: хаулеры сдают в ближайший,
        // поэтому энергия копится там, где короче рейс, и очередь должна идти оттуда же
        // ПЛОЩАДКУ СТАВИТ НЕ СПАВН. Башня, точка сдачи и проба расстояния — это createConstructionSite:
        // спавн за них не платит и их не производит, строит смотритель из кучи. А функция уходила из
        // тика первой же строкой, как только КАЖДЫЙ спавн был занят, — и уносила с собой все три.
        // Насыщенный спавн занят ПОСТОЯННО: в тестовом матче против けろびー#19 (6a9ffb7f) spawning=true
        // без перерыва с 200-го тика до конца, спавн стоял полным (1000) при 522-650 груза на хаулерах,
        // которые не могли сдать, — то есть ровно в тех тиках, когда вторая точка производства нужна
        // больше всего, ветку её постановки не исполняли ВОВСЕ. Отсюда и «башня в победах 0.45, в
        // поражениях 0.04»: в победах спавн простаивает, и площадка успевает родиться.
        val free = only
        val spawn = free ?: ctx.mySpawns.maxByOrNull { it.store[RESOURCE_ENERGY] ?: 0 } ?: return
        val energy = spawn.store[RESOURCE_ENERGY] ?: 0
        // БЮДЖЕТ ТЕЛА — спавн плюс досягаемые экстеншены: spawnCreep берёт из них сам, спавн тратится
        // первым. Всё остальное в этой функции (хаулер, бурильщик, смотритель) считается по ЭНЕРГИИ
        // СПАВНА: те покупки не спавн делает, а мы, и экстеншен для них не касса
        val extEnergy = if (!extReach) 0 else ctx.myExtensions.sumOf { it.store[RESOURCE_ENERGY] ?: 0 }
        val budget = energy + extEnergy
        val carried = ctx.haulers.sumOf { it.store[RESOURCE_ENERGY] ?: 0 }
        val ourPower = ourPowerOf(defenders, threats)
        val enemyPower = enemyPowerOf(threats, defenders)
        // ДЕБЮТ: стартовую тысячу не тратим, пока не увидели, что рождает противник: его крип, заказанный
        // на первом тике, виден как spawning со второго. Если он не рождает ничего, ждём не дольше половины
        // окна оценки сближения — столько нужно, чтобы понять, идёт ли к нам уже стоящий в поле враг
        // (стенд freeze: неподвижные стражи в 60 тиках пути принимались за атаку). Дебют «бурильщик
        // первым» против ранней атаки проигрывает без вариантов (матч 12)
        if (ctx.myCreeps.isEmpty() && getTicks() <= APPROACH_WINDOW / 2 && ctx.pendingEnemies.isEmpty() && ctx.enemySpawn?.spawning == null) return reach("open")

        val usable = usableSites(ctx)
        cpuMark("sp.usable")
        // включая рождающихся; остов без живых CARRY флот не пополняет — место в лимите свободно
        val allHaulers = ctx.myCreeps.count { c -> c.body.none { it.type == WORK } && c.body.any { it.type == CARRY && it.hits > 0 } }

        // ПРОЛОМ: контейнер за стеной у спавна (5000 в девяти клетках против 2500 в сорока восьми) —
        // мили-бурильщик первым: ATTACK бьёт структуры впятеро дешевле RANGED. Хаулеру оставляем
        // минимальное тело, чтобы он был готов к открытию.
        val income = projectedIncome(ctx, usable)
        cpuMark("sp.income")
        // пол потока — регенерация спавна: при нуле хаулеров «время догнать» было бесконечным, и «боец
        // первым» либо замыкался сам на себя (стенд 02.09), либо запрещался вовсе — и против ранней атаки
        // спавн держал 262 энергии на бурильщика и хаулера (матч 12)
        val regen = regenRate()
        val flow = income + regen
        val fullBody = fighterBody(SPAWN_ENERGY_CAPACITY)
        val fullCost = fullBody.sumOf { cost(it) }
        // …and a gun faster than every melee of ours is answered by our guns alone (v101): the classes are not pooled,
        // or a melee that never lands a swing covers the kiters shooting the spawn
        val deficit = if (USE_HOME_BOUND) maxOf(enemyPower * DEFEND_MARGIN - ourPower, kiteDeficit(defenders, threats))
            else enemyPower * DEFEND_MARGIN - ourPower
        val breach = breachPlan(ctx)
        // СКОЛЬКО ЖИВЁТ СПАВН при нынешнем входящем уроне: 3000 хитов, делённые на выстрелы в тик.
        // Это часы для обоих правил ожидания ниже. «Придёт враг» (enemyArrival) на них не отвечает: враг,
        // который УЖЕ стоит вплотную и стреляет, никуда не «приходит», и оба правила ждали полное тело,
        // пока спавн сносили (матч 19: 904 энергии в банке, ноль крипов, снесён на 1000-м)
        val spawnFire = InfluenceMap.fireAt(spawn.x, spawn.y, threats)
        val spawnLife = if (spawnFire > 0.0) (spawn.hits ?: SPAWN_HITS) / spawnFire else Double.MAX_VALUE
        // ЧАСЫ ПЛОЩАДКИ — У КАЖДОЙ СВОИ. Готовая башня в сроке не нуждается — её надо только кормить;
        // недостроенная становится башней, лишь когда на неё довезут остаток, и при мёртвой экономике
        // это «никогда». Спавн-точка-сдачи судится другим сроком и кормится не из спавна (см. SiteJob)
        cpuMark("sp.pre")
        // THE SPAWN'S LIFE BY ITS TREND TOO (v96): the clock of a site took the spawn's life from this tick's fire
        // only, and against raids — six hunters every sixty ticks, the gate's siege6 — the spawn is "not under fire"
        // between them, so its life read infinite and a tower site was fed to 769/1250 while the house went down. The
        // hits the home spawn actually lost over the production window give the other answer; the shorter one is used
        siteJobs = buildJobs(ctx, if (USE_SPAWN_TREND) minOf(spawnLife, homeLifeByTrend(ctx)) else spawnLife, flow, energy)
        cpuMark("sp.jobs")
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
        val holdReady = energyArrivalTicks(ctx, fullCost - budget, flow) + fullBody.size * CREEP_SPAWN_TIME
        val investReady = guardReadySim(ctx, breach, energy).toDouble()
        cpuMark("sp.invest")
        val closesNow = budget >= minFighter && closesDeficit(fighterBody(budget), defenders, threats)
        cpuMark("sp.closes")
        // тревога — тот же выбор, а не безусловный запрет: враг, вставший у ворот на тысячу тиков, держал
        // спавн на регенерации 1/тик без единого хаулера при открытом проломе в девяти клетках (стенд stream17)
        // под тревогой враг уже в SPAWN_ALARM_TICKS от спавна, даже если стоит: enemyArrival для стоящего
        // шара — «никогда», и угроза выходила несрочной (стенд tower+hover: четыре хаулера под тревогой)
        val arriveIn = if (alarm) minOf(enemyArrival, SPAWN_ALARM_TICKS) else enemyArrival
        // THE THREAT IS WHEN THE HOUSE FALLS, NOT WHEN THEY ARRIVE (v97). Under alarm the arrival was capped at 40, and a
        // loitering raider (けろびー's M5A1, dps 30) or births at his spawn on our half held "fighter first" for the whole
        // game — 35 lines of "enemy arrives in 40" in one loss, the fleet stuck at 5-6 and 6-7 a tick; against Ranamar
        // his first body (M5R1, 10 a tick, 300 ticks to our spawn) spent our opening thousand on a guard. What the
        // threat takes is the spawn: its hits (and ours rampart over it) at the damage the threats bring.
        val threatDps = threats.sumOf { val p = InfluenceMap.profileOf(it); p.ranged + p.melee }
        val houseHits = (spawn.hits ?: SPAWN_HITS) + ctx.ramparts.filter { it.my == true && it.x == spawn.x && it.y == spawn.y }.sumOf { it.hits ?: 0 }
        val killIn = if (!USE_THREAT_KILL_TIME || threatDps <= 0.0) 0 else (houseHits / threatDps).toInt()
        val threatIn = if (USE_PACK_FALL) houseFallsIn(ctx, defenders, threats, houseHits)
            else if (arriveIn >= Int.MAX_VALUE / 4) arriveIn else arriveIn + killIn
        // UNDER FIRE AND NOT HELD, THERE IS NOTHING TO INVEST IN (v107). "Invest" means the fleet grows and brings the
        // fighter sooner; with his guns already shooting our spawn and our defenders not holding them, the new haulers
        // are what they shoot next: against Ranamar (v106, lost at t≈400) deficit=185 from t=100 and his M5R1 at the
        // spawn from t=171, the house "fell in 150+", "investing" won every tick, haulers were bought and husked, and the
        // first gun (M1R1 for 180) came at 390. The alarm alone is not the question: a harasser in the ring that shoots
        // nothing of ours cost the gate's harass 534 -> 608 held on "fighter first"
        val armNow = USE_ARM_AT_DOOR && spawnUnderFire && deficit > 0.0
        val fighterFirst = armNow || (alarm || deficit > 0.0) && threatIn < investReady &&
            (holdReady <= threatIn || holdReady < investReady || closesNow)
        val realised = realisedIncome()
        // ПРОШЛАЯ ПОКУПКА НЕ ДОЛЖНА БЫЛА СДЕЛАТЬ ХУЖЕ (см. fleetMark). Спрашивается не «выросла ли
        // сдача» — в долгой стройке она растёт медленнее окна замера, и требование роста стоило
        // фикстуре fortress трёхсот тиков, — а «не УПАЛА ли». Падение при выросшей ёмкости значит, что
        // узкое место не ёмкость, и следующий возчик поедет умирать туда же: в матче 6a9ffb75 флот
        // дорос до одиннадцати хаулеров при ДВУХ бойцах, пока замеренная сдача падала с 14-15 до 3-5
        val fleetGrew = ctx.haulers.sumOf { capacityOf(it) } > fleetMark
        val lastHarmed = fleetMarkIncome >= 0.0 && realised >= 0.0 && fleetGrew &&
            getTicks() - fleetMarkTick >= PRODUCTION_WINDOW / 2 && realised < fleetMarkIncome
        val fleetDelivers = !lastHarmed &&
            (realised < 0.0 || realised >= projectedIncome(ctx, usable) * PUSH_RELEASE_RATIO)
        // ПОТОК, А НЕ КУЧА. capacityBound спрашивает, лежит ли на земле на четыре круга всего флота, —
        // это вопрос про склад. Карта же роняет две точки по 2000 каждые пятьдесят тиков: восемьдесят в
        // тик ПОЯВЛЯЕТСЯ. Пока появляется больше, чем мы увозим, узкое место — ёмкость по определению,
        // сколько бы ни лежало прямо сейчас (матч 45: 6050 на земле, восемь хаулеров, приток 9 из 80).
        // Оба ответа «да» — покупаем; сторожа при этом остаются прежние: замеренная сдача (fleetDelivers)
        // запрещает рост, когда флот не вывозит обещанного, а цель спавна (targetIncome) — когда возить
        // уже некуда
        val taking = realisedIncome().let { if (it < 0.0) projectedIncome(ctx, usable) else it }
        val supplyBound = supplyRate() > taking
        val needHauler = allHaulers < MAX_HAULERS && fleetDelivers &&
            !(energy >= SPAWN_ENERGY_CAPACITY && carried > 0) &&
            (supplyBound || capacityBound(fleetPoints(ctx, usable), ctx.haulers.sumOf { capacityOf(it) }, HAULER_BLOCKS_MIN * CARRY_CAPACITY)) &&
            projectedIncome(ctx, usable) < targetIncome()
        cpuMark("sp.fleet")

        if (placeSites) {
            // ПЛОЩАДКИ — ДО ПОКУПОК, А НЕ ПОСЛЕ. Ни одна из трёх не тратит спавна, а стояли они за четырьмя
            // «return» подряд (коплю на бурильщика, коплю на хаулера, на бойца не хватает) плюс за занятым
            // спавном. Собственный комментарий башни — «площадка ничего не стоит, поэтому ставится сразу»
            // — все эти годы был неправдой ровно потому, что стоял ниже них
            // БАШНЯ ДОМА. Площадка ничего не стоит, поэтому ставится сразу, как только счёт (towerWorth)
            // говорит, что дома она даёт больше бойца за ту же энергию. Смотритель — часть цены башни:
            // без него площадку некому строить, а готовая башня молчит (ёмкость — один выстрел)
            // ...и пока идёт ПРОБА, других площадок нет вовсе: один смотритель на две стройки не кончает
            // ни одной (первый матч пробы: экстеншен 100/200, башня 20/1250, ответа нет). Ветка живёт
            // только при включённой EXT_PROBE и снимается вместе с ней
            if (ctx.myTowers.isEmpty() && !(EXT_PROBE && !extAnswered)) {
                // площадка уже стоит — спрашиваем про ОСТАТОК: бросить недостроенное дороже, чем достроить.
                // Спрашиваем при этом про ПЛОЩАДКУ БАШНИ, а не про ближайшую: чужая по назначению стройка
                // рядом с домом отвечала за башню и на «стоит ли уже», и на «успеем ли»
                val job = siteJobs.firstOrNull { it.kind == "StructureTower" }
                val site = job?.site
                val left = if (site == null) -1 else job.left
                val trace = StringBuilder()
                // ЧАСЫ. towerWorth считает прибавку на энергию и ничего не знает о времени: боец рождается
                // за десятки тиков, а площадка становится башней только когда на неё довезут остаток. При
                // притоке 1-2 в тик это «никогда», и энергия уходит в недостроенное — три поражения из пяти
                // (06.09.2026) держали площадку 220-863 из 1250 до конца матча. Горизонт — уже существующие
                // сроки: жизнь спавна под нынешним огнём и остаток матча
                val inTime = job?.inTime ?: false
                val worth = inTime && towerWorth(defenders, threats, flow, left, trace)
                if (DEBUG_LOG && getTicks() % (LOG_EVERY * 5) == 0 && (trace.isNotEmpty() || site != null)) {
                    println("tower: worth=$worth inTime=$inTime job=${job ?: "-"} jobs=${siteJobs.size}$trace")
                }
                // СЧЁТ УЖЕ ОТВЕТИЛ. towerWorth сравнил башню с бойцом против тех же врагов и с замеренной
                // смертностью бойцов; спрашивать сверх этого «а не купить ли всё-таки бойца» (fighterFirst)
                // значит запретить башню ровно там, где она и нужна, — враг у ворот (матч 26: worth=true
                // трижды, площадка не поставлена ни разу). Остаются только часы: спавн должен дожить
                if (worth && site == null) {
                    val spot = towerSpot(ctx)
                    if (spot != null) {
                        val r = createConstructionSite(spot.x, spot.y, StructureTower::class.js)
                        if (DEBUG_LOG) println("tower: site at (${spot.x},${spot.y})$trace flow=${(flow * 10).toInt() / 10.0} err=${r.error}")
                    }
                }
            }
            cpuMark("sp.tower")
            // ТОЧКА СДАЧИ — ПОКУПКА ИЗ ИЗЛИШКА, А НЕ СТАВКА НА ДЛИНУ МАТЧА. Горизонта матча бот не знает и
            // знать не может (четвёртая попытка требовала его от симуляции осады и получила «никогда»), но
            // вопрос снимается сам, если тысяча тратится ТОЛЬКО когда она иначе пролежит: спавн полон, флот
            // собран, дефицита обороны нет и площадок нет вовсе. Так покупает и けろびー — его точки идут
            // после сбора флота, примерно раз в полтораста тиков, из свободных денег
            val fwdKeeper = if (ctx.builders.isEmpty()) builderBody(builderWork(flow)).sumOf { cost(it) } else 0
            if (DEBUG_LOG && getTicks() % (LOG_EVERY * 10) == 0) {
                println("fwd gates: t=${getTicks()} sites=${ctx.mySites.size} spawns=${ctx.mySpawns.size} budget=$budget/$fwdKeeper " +
                    "deficit=${deficit.toInt()} needHauler=$needHauler fighterFirst=$fighterFirst alarm=$alarm")
            }
            if (!USE_PILE_SPAWN && ctx.mySites.isEmpty() && ctx.mySpawns.size < FORWARD_SPAWNS &&
                // ЦЕНУ СМОТРИТЕЛЯ СПРАШИВАЛИ ДВАЖДЫ — И ОДИН РАЗ НЕ ВОВРЕМЯ. Она уже стоит в price у
                // forwardWorth, то есть в ответе на «окупится ли»; а здесь её же требовали НАЛИЧНЫМИ в
                // момент постановки, когда площадки ещё нет и смотритель ещё не нужен. Спавн столько не
                // держит: после сбора флота в кассе 90-470 при цене смотрителя 600-700 (прогон none,
                // тики 300-420 — единственное, что осталось запирать ветку после снятия занятого спавна).
                // Смотрителя покупает своя ветка ниже, и по своим часам: когда площадка ЕСТЬ и успевает
                // ХАУЛЕР И ТОЧКА СДАЧИ НЕ КОНКУРЕНТЫ, А ГЕЙТ ССОРИЛ ИХ ЗА ДЕНЬГИ, КОТОРЫХ ОНИ НЕ ДЕЛЯТ.
                // Дефицитен у нас не кошелёк, а ВРЕМЯ СПАВНА: тело растёт по три тика на часть, и пока оно
                // растёт, спавн не делает больше ничего (в матче 6a9ffb7f spawning=true без перерыва с 200-го
                // тика). Хаулер съедает это время целиком; площадка не съедает его вовсе — её оплачивает куча
                // руками смотрителя. Условие «сначала перестань хотеть хаулера» откладывало точку сдачи до
                // MAX_HAULERS=16, то есть навсегда: за все прогоны ветка добиралась до вопроса трижды, и все
                // три раза — раньше трёхсотого тика, когда притока ещё не замерено. Окупаемость считает
                // forwardWorth, и тысяча из кучи в цене у неё уже стоит
                // ГОРИЗОНТ СПРАШИВАЕМ У НЕГО (см. foeSpawnPeak): пока он держит один спавн, он играет на
                // убийство, и наша тысяча — это отнятый боец; поставил второй — сам объявил матч длинным
                foeSpawnPeak > 1 &&
                deficit <= 0.0 && !fighterFirst && !alarm) {
                val capacity = ctx.haulers.sumOf { h -> h.body.count { it.type == CARRY && it.hits > 0 } } * CARRY_CAPACITY
                val why = StringBuilder()
                val spot = forwardSpot(ctx, capacity, flow, why)
                if (DEBUG_LOG && spot == null && getTicks() % (LOG_EVERY * 5) == 0) println("fwd spot: none$why")
                if (spot != null) {
                    val trace = StringBuilder()
                    if (forwardWorth(ctx, spot, capacity, flow, energy, trace)) {
                        val r = createConstructionSite(spot.x, spot.y, StructureSpawn::class.js)
                        if (DEBUG_LOG) println("forward spawn: site at (${spot.x},${spot.y})$trace err=${r.error}")
                    } else if (DEBUG_LOG && getTicks() % (LOG_EVERY * 5) == 0) {
                        println("forward spawn: no t=${getTicks()} (${spot.x},${spot.y}) measured=${(realisedIncome() * 10).toInt() / 10.0}$trace")
                    }
                }
            }
            // ПРОБА РАССТОЯНИЯ (EXT_PROBE): один экстеншен ДАЛЬШЕ спорного радиуса, и всё. Боевого
            // правила «строить экстеншены» здесь нет — потолок тела отвергнут замером (см. bodyCap)
            cpuMark("sp.fwd")
            if (EXT_PROBE && !extProbeDone && ctx.myExtensions.isEmpty() && ctx.mySites.isEmpty() && !fighterFirst) {
                val spot = extensionProbeSpot(ctx)
                if (spot != null) {
                    val r = createConstructionSite(spot.x, spot.y, StructureExtension::class.js)
                    if (r.error == null) extProbeDone = true
                    if (DEBUG_LOG) println("extprobe site at (${spot.x},${spot.y}) range=${getRange(spot, ctx.mySpawn)} err=${r.error}")
                }
            }
        }
        // ТЕЛО ЗАКАЗЫВАТЬ НЕКУДА, ПОКА ВСЕ СПАВНЫ ЗАНЯТЫ. Всё, что ниже, — это spawnCreep и накопление
        // под него; площадки выше уже решены
        if (free == null) return reach("busy")

        if (breach != null && !alarm && !fighterFirst && ctx.myCreeps.none { isMelee(it) }) {
            val order = breacherOrder(ctx, breach, usable, energy, carried, flow)
            // бурильщик под поток — и копим на него, если по потоку он ближе, чем через хаулера; иначе
            // хаулер вперёд (ветка хаулера ниже) — после него та же проверка снова укажет на бурильщика
            if (order != null && order.second) {
                val k = order.first
                val breacherCost = k * (cost(MOVE) + cost(ATTACK))
                if (energy < breacherCost) {
                    if (DEBUG_LOG && getTicks() % 10 == 0) println("spawn: saving for breacher blocks=$k cost=$breacherCost energy=$energy carried=$carried flow=${(flow * 10).toInt() / 10.0} hold=${holdReady.toInt()} invest=${investReady.toInt()}")
                    return reach("brSave")
                }
                val r = spawn.spawnCreep(breacherBody(k))
                reach(if (r.error == null) "brBuy" else "err")
                if (r.error == null) spentFighters += breacherCost
                if (DEBUG_LOG) {
                    val trace = StringBuilder()
                    val sim = guardReadySim(ctx, breach, energy, trace)
                    println("spawn: breacher blocks=$k walls=${breach.walls.size} hits=${breach.totalHits} fire=${wallFire(ctx, breach)} trip=${breach.trip} open=${breachOpenIn(ctx, breach, energy, flow)} hold=${holdReady.toInt()} invest=${investReady.toInt()} sim=$sim arrival=$enemyArrival err=${r.error}$trace")
                }
                return
            }
        }
        cpuMark("sp.breach")
        lastSpawnOutlivesFighter = spawnLife > fullBody.size * CREEP_SPAWN_TIME
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
        // стража «на всякий случай» нет: армия врага видна с момента его spawnCreep, и боец строится
        // в ответ на неё (fighterFirst). Страж за 500 стоял 200 тиков без дела, а второй хаулер
        // из-за него появился на 120-м тике — противник к 70-му вывел в поле вдвое больше ёмкости.
        // Боец первым — если видимая армия врага перевешивает И успеет дойти раньше, чем мы закроем
        // дефицит (тел × максимум из времени рождения и накопления энергии). Два разведчика врага
        // на другом краю карты 170 тиков держали спавн на «боец первым» при притоке 10/23 (02.09).
        // (income, deficit, fighterFirst — выше, до ветки бурильщика)
        // ХЕДЖ ПО ЖИВОЙ СИЛЕ, А НЕ ПО КАССЕ. Прежде сравнивались суммы потраченного, и убитый боец
        // продолжал оправдывать хаулера всю игру: вложив в бойцов восемь тысяч и держа двоих, мы имели
        // право на девять тысяч флота — и ровно это и вышло (ничья 1999 тиков: 15 хаулеров против трёх
        // бойцов, чужой спавн отбит с 2200 обратно до 3000). Считаем то, что ЕСТЬ: цену уцелевших
        // частей живого флота против цены уцелевших частей живых вооружённых
        val liveHaulers = ctx.haulers.sumOf { liveCost(it) }
        val liveFighters = defenders.sumOf { liveCost(it) }
        // ONE HAULER A TICK FROM ALL SPAWNS (v85): every free spawn runs this cascade on the same snapshot, and the
        // creep ordered by the first is not in it — two spawns bought `hauler #8` in one tick (24.09.2026)
        val haulerTurn = needHauler && !fighterFirst && liveHaulers <= liveFighters + HAULER_LEAD && haulerOrderedAt != getTicks()

        if (haulerTurn) {
            val affordable = minOf(HAULER_BLOCKS_MAX, energy / blockCost())
            if (affordable < HAULER_BLOCKS_MIN) return reach("hSave") // копим
            // копим на полного, если приток обещает; самого первого хаулера не ждём — без него притока нет
            val expected = minOf(HAULER_BLOCKS_MAX, (energy + carried) / blockCost())
            if (ctx.haulers.isNotEmpty() && affordable < HAULER_BLOCKS_MAX && expected > affordable) return reach("hWait")
            val r = spawn.spawnCreep(haulerBody(affordable))
            reach(if (r.error == null) "hBuy" else "err")
            if (r.error == null) {
                haulerOrderedAt = getTicks()
                spentHaulers += affordable * blockCost()
                fleetMark = ctx.haulers.sumOf { capacityOf(it) }
                fleetMarkIncome = realised
                fleetMarkTick = getTicks()
            }
            if (DEBUG_LOG) println("spawn: hauler #${allHaulers + 1} blocks=$affordable income=${projectedIncome(ctx, usable).toInt()}/${targetIncome().toInt()} real=${if (realised < 0) "-" else realised.toInt().toString()} supply=${supplyRate().toInt()} live=$liveHaulers/$liveFighters mark=${fleetMark}/${(fleetMarkIncome * 10).toInt() / 10.0} err=${r.error}")
            return
        }
        // THE PILE BUILDER (v88, see USE_PILE_SPAWN): one at a time, bought once the fleet's delivery is measured,
        // there is no threat to answer and the match still has a whole job in it; it lives in the field and waits for
        // a fresh container (born at home it reaches almost none in time: 99 ticks of life, three a cell on swamp).
        // It is paid from the spawn but its work is paid by energy that would rot. Never saved for: a fighter takes
        // the turn when the energy is short.
        val pileJobTicks = PILE_BODY.size * CREEP_SPAWN_TIME + ceil(buildCost("StructureSpawn").toDouble() / (BUILD_POWER * PILE_BODY.count { it == WORK })).toInt()
        // …and only while our half has been QUIET for the whole production window (homeShare: ticks under alarm in it):
        // "no alarm this tick" bought it in the lull between two raids of the gate's siege6 (six hunters every sixty
        // ticks), it stood under fire by the house, and the 600 it cost was the tower site's 750 left dead
        // a bare home spawn is a reason on its own (v94, see USE_SPAWN_RAMPART): no quiet window needed, only no threat
        val wantRampart = bareSpawn(ctx)?.let { it.id == ctx.mySpawn.id } == true
        if (USE_PILE_SPAWN && ctx.myCreeps.none { isPileBuilder(it) } && !fighterFirst && !alarm && deficit <= 0.0 &&
            // …but never out of the opening: the house needs its rampart by his first strike (t≈600 for けろびー, ≈790 for
            // Ranamar), the fleet needs the first thousand now — bought in the opening it took the stub's tower+stream
            // 574 -> 1108; once delivery is measured the fleet stands
            realised >= 0.0 && (wantRampart || homeShare() <= 0.0) &&
            arenaInfo.ticksLimit - getTicks() > 2 * pileJobTicks) {
            val price = PILE_BODY.sumOf { cost(it) }
            if (energy >= price) {
                val r = spawn.spawnCreep(PILE_BODY)
                reach(if (r.error == null) "pbBuy" else "err")
                r.`object`?.let { pileBuilderIds.add(it.id); pileOrderedAt = getTicks() }
                if (DEBUG_LOG) println("spawn: pile builder cost=$price energy=$energy err=${r.error}")
                return
            }
        }
        // очередь хаулера, но энергии на бойца тоже нет — копим на того, кто первый по карману
        if (needHauler && !fighterFirst && energy < cost(RANGED_ATTACK) + cost(MOVE)) return reach("hQueue")

        if (budget < minFighter) return reach("poor")

        // СМОТРИТЕЛЬ — ЧАСТЬ ЦЕНЫ БАШНИ, И ЧАСЫ У НЕГО ТЕ ЖЕ. Под площадку, которая не достроится
        // в срок, он не покупается: в проигранном матче 22:22 он стоил 700 при притоке 2 в тик и
        // достроил 95 из 482 оставшихся. У готовой башни срока нет — её надо только кормить
        // смотритель покупается под РАБОТУ, которую успеваем сделать, или под готовую башню, которую
        // надо кормить; «есть хоть какая-то площадка» этого вопроса не задаёт
        if (ctx.builders.isEmpty() && (siteJobs.any { it.site != null && it.inTime } || ctx.myTowers.isNotEmpty())) {
            // ТЕЛО ПОД РАБОТУ, А НЕ ПОД ЛЮБУЮ. Работа выбирается тем же правилом, что и в runBuilders
            val forJob = siteJobs.filter { it.site != null && it.inTime }.minByOrNull { getRange(spawn, it.site!!) }
            val builder = keeperBody(ctx, forJob?.site?.let { InfluenceMap.cell(it.x, it.y) }, forJob?.left ?: 0, flow)
            val builderCost = builder.sumOf { cost(it) }
            if (energy < builderCost) {
                // копим на смотрителя, только пока спавн доживает до него — те же часы, что у правила
                // лагеря ниже: копить под сносимым спавном нельзя ни на что
                if (spawnLife <= energyArrivalTicks(ctx, builderCost - energy, flow)) return reach("kDying")
                if (DEBUG_LOG && getTicks() % 10 == 0) println("spawn: saving for builder cost=$builderCost energy=$energy")
                return reach("kSave")
            }
            val r = spawn.spawnCreep(builder)
            reach(if (r.error == null) "kBuy" else "err")
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
            spawnLife > energyArrivalTicks(ctx, SPAWN_ENERGY_CAPACITY - energy, flow)) return reach("camp")

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
        // мили строится по двум причинам, и обе — счёт: дома против мили-шара (guardNeeded), в поле —
        // когда симуляция говорит, что с ним осада кончится раньше (assaultWantsMelee)
        // ...а лекарь — по третьему ответу того же прогона (assaultWantsHealer). Мили-гарнизон дома
        // старше: против мили-шара у спавна лекарь без урона не держит ничего
        val guard = guardNeeded || assaultWantsMelee
        // ЛЕКАРЬ ПОКУПАЕТСЯ ТОЛЬКО ТОТ, О КОМ СПРАШИВАЛИ. assaultWantsHealer — ответ прогона про ПОЛНОЕ
        // тело (healerBody(SPAWN_ENERGY_CAPACITY)), а покупка брала healerBody(budget) и на половине
        // кассы выпускала M8H1: одна часть, двенадцать лечения, против сотен урона. Это тот же дефект,
        // что нашёлся в v51, — вопрос про одно, ответ применяется к другому (стенд stream17: 888 -> 1329).
        // Не хватает на настоящего — берём бойца, а не огрызок лекаря; копить незачем, вопрос задаётся
        // каждый тик заново
        val healer = !guard && assaultWantsHealer && budget >= SPAWN_ENERGY_CAPACITY
        val body = if (guard) guardBody(budget, spawnLimited) else if (healer) healerBody(budget, spawnLimited) else fighterBody(budget, spawnLimited)
        val full = if (guard) guardBody(SPAWN_ENERGY_CAPACITY, spawnLimited) else if (healer) healerBody(SPAWN_ENERGY_CAPACITY, spawnLimited) else fighterBody(SPAWN_ENERGY_CAPACITY, spawnLimited)
        // Ждём полное тело, если гарнизон и так держит (deficit <= 0: недомерок ничего не добавит) или
        // если враг придёт позже, чем доедет недостающее — по ГРУЖЁНЫМ хаулерам в пути, не по притоку
        // с земли: при пустых точках (income=0) и 2150 энергии в дороге «ждать» выходило 773 тика, и
        // спавн выпустил M1R1 за 200 и M2R1 за 250 (матч 8). И при тревоге тоже: при держащем
        // гарнизоне тревога выпускала M1R1 по 200 (матч 9); недомерок при тревоге — только когда
        // гарнизон не держит и энергия не успевает
        val gap = bodyCap - budget
        if (gap > 0 && bodyValue(full) > bodyValue(body)) {
            val waitTicks = energyArrivalTicks(ctx, gap, flow)
            if (deficit <= 0.0 || (enemyArrival > waitTicks && spawnLife > waitTicks)) return reach("wFull")
            // недомерок — только если САМ закрывает дефицит: тело, которое ничего не меняет, — корм
            // (матч 12: M2R1 и M5R1 по одному против трёх M5R1); под огнём спавна строим, что есть
            if (!spawnUnderFire && !closesDeficit(body, defenders, threats)) return reach("wRunt")
        }

        val r = spawn.spawnCreep(body)
        reach(if (r.error != null) "err" else if (guard) "gBuy" else if (healer) "heBuy" else "fBuy")
        if (r.error == null) spentFighters += body.sumOf { cost(it) }
        // ПРОБА: тело дороже, чем лежит в спавне, оплачивается только экстеншенами. Одна строка на
        // такой заказ — это и есть подтверждение (или опровержение) того, что они питают spawnCreep;
        // код возврата тут не различает ничего, -6 это сразу три ошибки, различает УСПЕХ
        // ПРОБА И ПРЕДОХРАНИТЕЛЬ В ОДНОМ. Тело дороже, чем лежит в спавне, оплачивается только
        // экстеншенами — значит УСПЕХ такого заказа и есть ответ (код возврата не отвечает: -6 это
        // сразу три разные ошибки). Не прошло — экстеншены не досягаемы, и бюджет перестаёт их считать
        val price = body.sumOf { cost(it) }
        if (price > energy && extEnergy > 0) {
            val ok = r.error == null
            if (!ok) extReach = false
            extAnswered = true
            if (DEBUG_LOG) println("extprobe t=${getTicks()} spawnE=$energy extE=$extEnergy exts=${ctx.myExtensions.size} range=${ctx.myExtensions.minOfOrNull { getRange(it, spawn) } ?: -1} cost=$price parts=${body.size} err=${r.error} reach=$ok")
        }
        if (DEBUG_LOG) println("spawn: ${if (guard) "guard" else if (healer) "healer" else "fighter"} parts=${body.size} cost=${body.sumOf { cost(it) }} energy=$energy want=${if (assaultWantsMelee) "melee" else if (assaultWantsHealer) "healer" else "ranged"} alarm=$alarm first=$fighterFirst our=${ourPower.toInt()}/${enemyPower.toInt()} deficit=${deficit.toInt()} fire=$spawnUnderFire arrival=${if (enemyArrival >= Int.MAX_VALUE / 4) "-" else enemyArrival.toString()} spent=$spentHaulers/$spentFighters err=${r.error}")
    }

    /**
     * СТРОЙКА — ЭТО РАБОТА, А НЕ ОБЪЕКТ. У каждой площадки три ответа, и они РАЗНЫЕ у разных построек:
     *  - `kind` — что из неё станет (цена площадки и есть её имя: башня 1250, спавн 1000);
     *  - `deadline` — срок, к которому она обязана встать. У домашней башни это жизнь спавна под огнём:
     *    башня, которая встанет после того, как нас снесли, не оборона. У спавна-точки-сдачи — только
     *    остаток матча: он не оборона, а экономика, и окупается не в этом бою;
     *  - `supply` — откуда смотритель берёт энергию. Домашнюю башню кормят из спавна; дальнюю площадку
     *    так кормить нельзя вовсе — два CARRY на рейс в восемьдесят тиков тысячу не довезут, — и она
     *    кормится из кучи, рядом с которой и стоит.
     * `site == null` — работа ещё ПЛАН: так спрашивают «а если начать».
     */
    private class SiteJob(
        val site: ConstructionSite?,
        val kind: String,
        val left: Int,
        val supply: Position,
        val fromSpawn: Boolean,
        val deadline: Double,
        val ready: Double,
    ) {
        val inTime: Boolean get() = ready < deadline
        override fun toString() =
            "$kind${site?.let { "(${it.x},${it.y})" } ?: "?"}left=$left ready=${if (ready >= Double.MAX_VALUE / 2) "never" else ready.toInt().toString()}/${deadline.toInt()}"
    }

    /** Только то, что бот СТАВИТ сам: цена площадки — её имя. У рампарта и экстеншена цена ОБЩАЯ
     *  (200 обоим), поэтому опознание по цене однозначно ровно пока мы не ставим рампартов; ставим —
     *  и площадку придётся различать чем-то ещё. */
    private val BUILDABLE = if (EXT_PROBE) arrayOf("StructureTower", "StructureSpawn", "StructureExtension")
        else arrayOf("StructureTower", "StructureSpawn")

    private fun buildKindOf(site: ConstructionSite): String? {
        val total = site.progressTotal ?: return null
        return BUILDABLE.firstOrNull { buildCost(it) == total }
    }

    /** Откуда кормить эту площадку: из спавна, если он ближе, иначе из ближайшей безопасной кучи.
     *  Вопрос решается расстоянием, а не видом постройки: башня стоит у спавна и кормится оттуда сама
     *  собой, а площадка у энергии — от энергии, и обе получают один и тот же ответ одного правила. */
    private fun supplyFor(ctx: Ctx, at: Position, left: Int = 0, carry: Int = 2 * CARRY_CAPACITY, arrive: Int = 0): Pair<Position, Boolean> {
        // СКЛАД ОБЯЗАН ПЕРЕЖИТЬ ВСЮ РАБОТУ, А НЕ ОДИН РЕЙС — и дожить до её НАЧАЛА. Временная куча
        // живёт 99 тиков; смотритель возит по два CARRY, значит тысяча — это десять рейсов, а до места
        // он ещё идёт полсотни шагов. Без обоих слагаемых куча вплотную к площадке проходит проверку
        // (десять рейсов по четыре тика = 40 из 99) и распадается раньше, чем до неё дошли: площадка
        // встаёт на 40/1000 и не двигается — замерено на пятой попытке точки сдачи
        fun outlivesJob(site: EnergySite): Boolean {
            val life = site.ticksToDecay ?: return true
            if (left <= 0 || carry <= 0) return life > 2 * getRange(at, site.pos)
            val trips = ceil(left.toDouble() / minOf(carry, site.energy).coerceAtLeast(1))
            return life >= arrive + trips * (2.0 * getRange(at, site.pos) + 2.0)
        }
        val pile = ctx.sites.filter { it.safe && it.energy > 0 && it.container != null && outlivesJob(it) }
            .minByOrNull { getRange(at, it.pos) }
        if (pile != null && getRange(at, pile.pos) < getRange(at, ctx.mySpawn)) return pile.pos to false
        return ctx.mySpawn as Position to true
    }

    /**
     * ГДЕ СТОИТ ВТОРАЯ ТОЧКА СДАЧИ: ВПЛОТНУЮ К КУЧЕ, КОТОРАЯ ЕЁ ОПЛАТИТ. Место не ищется по карте — оно
     * ПОЯВЛЯЕТСЯ, когда карта роняет кучу, которой хватает на всю тысячу и которая доживёт до конца
     * стройки. Причина в пароме: смотритель носит два CARRY, тысяча — десять рейсов, и в полусотне
     * шагов от склада это 1100 тиков, а вплотную — сорок. Четыре попытки подряд ставили площадку «в
     * лучшем месте карты» и ни одна её не достроила.
     * Кандидат обязан быть НАШИМ по шагам, а не по Чебышёву: спавн стоит в стенном кармане, клетка «в
     * 38 по прямой» лежит в 80 шагах в обход. Мера — та же, что у куч (EnergySite.safe), но зеркальная:
     * кучу берут, если успевают раньше врага с запасом, а спавн надо ещё и ДЕРЖАТЬ.
     */
    private fun forwardSpot(ctx: Ctx, capacity: Int, flow: Double, why: StringBuilder? = null): Position? {
        val enemy: Position = ctx.enemySpawn ?: return null.also { why?.append(" noEnemySpawn") }
        if (dropHistory.isEmpty()) return null.also { why?.append(" noDrops") }
        val enemySteps = DistanceMap.stepFieldTo(enemy, ctx.blockedForEnemy)
        val busy = ctx.blocked.mapTo(HashSet()) { it.x * 100 + it.y }
        val price = buildCost("StructureSpawn")
        val keeper = builderBody(builderWork(flow))
        val carry = (ctx.builders.maxOfOrNull { b -> b.body.count { it.type == CARRY && it.hits > 0 } }
            ?: keeper.count { it == CARRY }) * CARRY_CAPACITY
        if (carry <= 0) return null.also { why?.append(" noCarry") }
        // THE QUESTION CAN WAIT A TICK, THE TICK CANNOT (v80). Every collectRate below reads a step field to EVERY
        // drop cell, and after the blocked set changes they are all rebuilt at once — 71 ms of a 100 ms tick in the
        // v79 trace, while the rest of the tick needs up to ~50 — and a tick past the limit is killed with every order
        // in it. So the fields are built here first, one at a time, and the search is put off the moment the tick has
        // spent its share: what was built stays cached (cellStepsSig), and the same answer comes a tick or two later
        // on a warm cache. The stub's clock reads 0 and never defers. Counted as `fwdCpu` in `reach spawn`.
        for (key in dropHistory.keys) {
            if (key !in cellStepsCache && cpuMs() > cpuBudgetMs() * FWD_CPU_SHARE) {
                spawnReach["fwdCpu"] = (spawnReach["fwdCpu"] ?: 0) + 1
                why?.append(" cpu")
                return null
            }
            cellSteps(ctx, key)
        }
        var best: Position? = null
        var bestRate = collectRate(ctx, ctx.mySpawn as Position, capacity)
        var unsafe = 0; var thin = 0; var dying = 0; var noCell = 0; var his = 0; var slower = 0
        for (src in ctx.sites) {
            // КУЧА НЕ ОБЯЗАНА ОПЛАТИТЬ ВСЮ ПЛОЩАДКУ ОДНА. Смотритель ходит к БЛИЖАЙШЕЙ живой куче
            // (supplyFor пересчитывается каждый тик), поэтому от места нужно, чтобы рядом было с чего
            // начать, а не чтобы одна куча держала тысячу. Прежнее требование просило именно этого — и
            // такие кучи выгребают наши же хаулеры: за шесть тестовых матчей против けろびー#19 площадка
            // нашлась один раз из шести, в остальных forwardSpot не возвращал ничего вовсе
            if (!src.safe) { unsafe++; continue }
            if (src.container == null || src.energy < carry) { thin++; continue }
            val arrive = if (ctx.builders.isNotEmpty()) ctx.builders.minOf { getRange(it, src.pos) }
                else ctx.stepsToSpawn[src.pos.x * 100 + src.pos.y].coerceAtLeast(0) + keeper.size * CREEP_SPAWN_TIME
            val work = ceil(price.toDouble() / carry) * 4.0 + price.toDouble() / (builderWork(flow) * BUILD_POWER)
            val life = src.ticksToDecay
            if (life != null && life < arrive + work) { dying++; continue }
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val x = src.pos.x + dx
                val y = src.pos.y + dy
                if (x !in 1..98 || y !in 1..98) continue
                val key = x * 100 + y
                val pos = InfluenceMap.cell(x, y)
                if (getTerrainAt(pos) == TERRAIN_WALL) { noCell++; continue }
                if (key in busy) { noCell++; continue }
                if (ctx.loadedToSpawn[key] < 0) { noCell++; continue }
                val ours = ctx.stepsToSpawn[key]
                val hisSteps = enemySteps[key]
                if (ours < 0) { noCell++; continue }
                // ЗАПАС В ПОЛТОРА РАЗА БЫЛ МОЙ И ОКАЗАЛСЯ ЗАПРЕТОМ НА ТО, РАДИ ЧЕГО ВСЁ. Он отбрасывал
                // всю середину карты, а середина — это и есть энергия: в ничьей 6a9feeab наши угловые
                // кучи выскреблись до 50, а две по 2000 лежали в 52 клетках и распадались за 6 и 56
                // тиков, отчего приток стал 0 из 33 и мы собрали 10400 против его 42080 при 117400
                // распавшихся. けろびー ставит точки сдачи в (63,70), (80,91) и даже (36,29) — в НАШЕЙ
                // половине, — и берёт середину. Условие остаётся только одно и настоящее: клетка не
                // должна быть ближе к нему, чем к нам; риск за неё платит экономические ворота
                if (hisSteps >= 0 && ours > hisSteps) { his++; continue }
                val rate = collectRate(ctx, pos, capacity, keepHome = true)
                if (rate > bestRate) { bestRate = rate; best = pos } else slower++
            }
        }
        why?.append(" piles=${ctx.sites.size} unsafe=$unsafe thin=$thin dying=$dying cell=$noCell his=$his slower=$slower home=${(bestRate * 100).toInt() / 100.0} carry=$carry")
        return best
    }

    /**
     * Окупится ли она. ПРИБАВКА СЧИТАЕТСЯ ОТНОШЕНИЕМ МОДЕЛИ НА ЗАМЕРЕННЫЙ ПРИТОК, а не абсолютом
     * модели, и это — причина пяти отвергнутых попыток подряд: collectRate занижает абсолют примерно
     * в 2.7 раза (она обещала прибавку 1.5 в тик там, где даровой спавн на стенде дал 4.3-6.3), но
     * отношение её точно, и оно же — единственное, что от неё нужно. Без замеренного притока
     * (realisedIncome < 0, первые триста тиков производства) вопрос не задаётся вовсе: цены нет.
     * Горизонт — предел арены, и он честен именно потому, что покупка делается ИЗ ИЗЛИШКА (см. ветку
     * постановки): тысяча, которая иначе пролежала бы в полном спавне, не отнимает бойца.
     */
    private fun forwardWorth(ctx: Ctx, spot: Position, capacity: Int, flow: Double, energy: Int, trace: StringBuilder): Boolean {
        // ПОЛОВИНЫ ОКНА ДОСТАТОЧНО: точка сдачи строится ещё сотню тиков после решения, и целое окно
        // отодвигало её готовность за пятисотый тик — в матчах против けろびー#19 исход решён к 430-му
        val measured = realisedIncome(PRODUCTION_WINDOW / 2)
        // «ЕЩЁ НЕ ЗАМЕРЕНО» И «ЗАМЕРЕНО НУЛЁМ» — РАЗНЫЕ ОТВЕТЫ. realisedIncome даёт -1, пока окна
        // производства не набралось (первые триста тиков); в это время цены нет вовсе и вопрос не
        // задаётся. Ноль же — это факт: приток замерен и он нулевой, и вот тут запасная шкала нужна
        if (measured < 0.0) return false
        val now = collectRate(ctx, ctx.mySpawn as Position, capacity)
        val then = collectRate(ctx, spot, capacity, keepHome = true)
        if (now <= 0.0 || then <= now) return false
        // ШКАЛА — ЗАМЕРЕННЫЙ ПРИТОК, ПОКА ОН ЕСТЬ. Но там, где точка сдачи нужнее всего, притока уже
        // НЕТ: ближние кучи выскреблены, дальние распадаются раньше, чем до них доедешь, и замер даёт
        // ноль (ничья 6a9feeab: income=0/33, собрано 10400 против его 42080). Умножать отношение на
        // ноль — значит запретить лекарство ровно по симптому болезни. Когда замера нет, берётся
        // АБСОЛЮТНАЯ разница модели: она занижена примерно в 2.7 раза, то есть заведомо осторожна
        val gain = if (measured > 0.0) measured * (then / now - 1.0) else then - now
        val keeperArrival = if (ctx.builders.isNotEmpty()) ctx.builders.minOf { getRange(it, spot) }
            else ctx.stepsToSpawn[spot.x * 100 + spot.y].coerceAtLeast(0) +
                keeperBody(ctx, spot, buildCost("StructureSpawn"), flow).size * CREEP_SPAWN_TIME
        val (supply, fromSpawn) = supplyFor(ctx, spot, buildCost("StructureSpawn"), arrive = keeperArrival)
        val build = siteReadyTicks(ctx, null, "StructureSpawn", buildCost("StructureSpawn"), fromSpawn, flow, energy, supply, spot)
        // ГОРИЗОНТ — РАНЬШЕЕ ИЗ ДВУХ: предел арены и срок, за который матч кончим МЫ. Второй известен
        // именно здесь и именно сейчас: точка покупается после сбора флота и замера притока, а к тому
        // времени волна есть и вердикт осады осмыслен (в отличие от 177-го тика четвёртой попытки)
        // КОНЕЦ ОСАДЫ — НЕ КОНЕЦ МАТЧА, ПОКА СПАВН У НЕГО НЕ ПОСЛЕДНИЙ. siegeEndsIn отвечает на вопрос
        // «когда мы снесём ТОТ спавн», и брать его за горизонт можно ровно тогда, когда снос того спавна
        // и есть победа. У けろびー#19 их четыре-шесть: в тестовом матче 6a9ffb7f мы довели ближний до 240
        // хитов и всё равно проиграли. Замерено на живой ветке (6a9ffb75, t=360-400): gain=4.8 в тик,
        // build=94, price=1600 — и ends=64, отчего left=-30 и вопрос закрывался при горизонте в 1640
        // тиков, которых на самом деле оставалось. Пока спавн у него один, прежний расчёт верен и остаётся
        val ending = if (ctx.enemySpawns.size > 1) Long.MAX_VALUE / 8 else siegeEndsIn
        val horizon = minOf((arenaInfo.ticksLimit - getTicks()).toLong(), ending).toDouble()
        val left = horizon - build
        val price = buildCost("StructureSpawn") +
            (if (ctx.builders.isEmpty()) keeperBody(ctx, spot, buildCost("StructureSpawn"), flow).sumOf { cost(it) } else 0)
        trace.append(" ratio=${(then / now * 100).toInt() / 100.0} measured=${(measured * 10).toInt() / 10.0} gain=${(gain * 10).toInt() / 10.0}/t " +
            "ends=${if (siegeEndsIn >= Long.MAX_VALUE / 8) "-" else siegeEndsIn.toString()} " +
            "build=${build.toInt()} left=${left.toInt()} price=$price supply=(${supply.x},${supply.y})${if (fromSpawn) "*" else ""}")
        return left > 0 && gain * left > price
    }

    /** Работы этого тика. Когда башни нет и площадки под неё нет, в список входит ПЛАН башни — тем же
     *  вопросом «успеем ли», каким судят стоящую. */
    private fun buildJobs(ctx: Ctx, spawnLife: Double, flow: Double, energy: Int): List<SiteJob> {
        val matchLeft = (arenaInfo.ticksLimit - getTicks()).toDouble()
        val jobs = ArrayList<SiteJob>()
        for (s in ctx.mySites) {
            val kind = buildKindOf(s) ?: continue
            val left = ((s.progressTotal ?: 0) - (s.progress ?: 0)).coerceAtLeast(0)
            val keeperArrival = if (ctx.builders.isNotEmpty()) ctx.builders.minOf { getRange(it, s) }
                else ctx.stepsToSpawn[s.x * 100 + s.y].coerceAtLeast(0) +
                    keeperBody(ctx, InfluenceMap.cell(s.x, s.y), left, flow).size * CREEP_SPAWN_TIME
            val (supply, fromSpawn) = supplyFor(ctx, s, left, arrive = keeperArrival)
            // ЧАСЫ ПО НАЗНАЧЕНИЮ. Башня — оборона: она обязана встать, пока спавн жив. Спавн — экономика:
            // ему довольно успеть до конца матча, а окупаемость проверена отдельно, при постановке
            val deadline = if (kind == "StructureTower") minOf(spawnLife, matchLeft) else matchLeft
            jobs.add(SiteJob(s, kind, left, supply, fromSpawn,
                deadline, siteReadyTicks(ctx, s, kind, left, fromSpawn, flow, energy, supply, InfluenceMap.cell(s.x, s.y))))
        }
        if (ctx.myTowers.isEmpty() && jobs.none { it.kind == "StructureTower" }) {
            val left = buildCost("StructureTower")
            jobs.add(SiteJob(null, "StructureTower", left, ctx.mySpawn, true,
                minOf(spawnLife, matchLeft), siteReadyTicks(ctx, null, "StructureTower", left, true, flow, energy)))
        }
        return jobs
    }

    /** Через сколько тиков в спавн доедет ещё gap энергии: гружёные хаулеры по тикам гружёного пути,
     *  ближние первыми, пока их груз не покроет разрыв; остаток — по притоку с земли. */
    private fun energyArrivalTicks(ctx: Ctx, gap: Int, income: Double): Double {
        fun ticksOf(h: Creep) = ctx.haulLoaded[h.x * 100 + h.y].let { if (it < 0) Int.MAX_VALUE / 4 else it }
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
        var heal = body.count { it == HEAL }
        var value = 0
        for (part in body) {
            // обрыв держит TOUGH в узде: снятый, он делает броню лучшей частью тела (замерено 06.09.2026 —
            // состав выродился в ttttt mmmmmmmmmm rrr, три ствола на восемнадцать частей, harass перестал
            // брать чужой спавн). ЭТА функция отвечает на вопрос «сколько чего брать»; на вопрос
            // «что терять первым» отвечает порядок частей в fighterBody, и он от неё независим
            if (moves < weight) break // скорость потеряна — дальше тело волне не нужно
            // сто хитов этой части боец бьёт с текущим уроном; лекарь — те же сто хитов лечит
            // лечение врага съедает урон ПЕРВЫМ: тело, чей урон ниже лечения, не убивает никого,
            // сколько бы оно ни жило. Своё лечение (heal) от этого не страдает — его вычитать не из чего
            value += (maxOf(0, ranged * RANGED_ATTACK_POWER + melee * ATTACK_POWER - foeHeal) + heal * HEAL_POWER) * 100
            when (part) {
                MOVE -> moves--
                RANGED_ATTACK -> ranged--
                ATTACK -> melee--
                HEAL -> heal--
                else -> {}
            }
        }
        return value
    }

    /** Ключ кэша тел: бюджет, узкий ли спавн И поправка на лечение врага. Прежний ключ знал только
     *  первые два, поэтому первый же ответ матча жил до конца — а лекари у врага появляются к 220-му
     *  тику. Поправка уже округлена до RANGED_HEAL_POWER в foeHealPerBody — без округления каждая
     *  смерть стрелка заводила бы новый перебор. */
    private fun bodyKey(cap: Int, spawnLimited: Boolean): Int =
        (cap * 2 + (if (spawnLimited) 1 else 0)) * 64 + (foeHeal / RANGED_HEAL_POWER).coerceIn(0, 63)

    private val guardBodyCache = HashMap<Int, Array<BodyPartType>>()

    /** Тело домашнего мили-гарнизона под бюджет — тот же перебор и порядок, что у fighterBody, но с
     *  ATTACK вместо RANGED: запасные MOVE вперёд, удар, MOVE 1:1 в хвост. На 1000 это M12A5: 150 удара,
     *  1700 хитов, 1200 из них на полной скорости. Строится только против мили-противника (guardNeeded):
     *  стрелка мили не догоняет, а мили-шар у спавна не кайтится и режется только вплотную. */
    private fun guardBody(budget: Int, spawnLimited: Boolean = false): Array<BodyPartType> {
        val cap = minOf(budget, SPAWN_ENERGY_CAPACITY)
        return guardBodyCache.getOrPut(bodyKey(cap, spawnLimited)) {
            val block = cost(ATTACK) + cost(MOVE)
            var best: Array<BodyPartType>? = null
            var bestValue = -1.0
            var a = 1
            while (a * block <= cap && 2 * a <= MAX_CREEP_SIZE) {
                val maxExtra = minOf((cap - a * block) / cost(MOVE), MAX_CREEP_SIZE - 2 * a)
                for (e in 0..maxExtra) {
                    val body = ArrayList<BodyPartType>(2 * a + e)
                    val scored = ArrayList<BodyPartType>(2 * a + e)
                    repeat(e) { scored.add(MOVE) }
                    repeat(a) { scored.add(ATTACK) }
                    repeat(a) { scored.add(MOVE) }
                    repeat(e + a) { body.add(MOVE) }
                    repeat(a) { body.add(ATTACK) }
                    val arr = body.toTypedArray()
                    val value = bodyValue(scored.toTypedArray()).toDouble() / (if (spawnLimited) arr.size * CREEP_SPAWN_TIME else 1)
                    if (value > bestValue) { bestValue = value; best = arr }
                }
                a++
            }
            best ?: arrayOf(MOVE, ATTACK)
        }
    }

    private val healerBodyCache = HashMap<Int, Array<BodyPartType>>()

    /** Тело лекаря под бюджет — тот же перебор и порядок, что у fighterBody, но с HEAL вместо RANGED:
     *  запасные MOVE вперёд, лечение в хвост (урон снимает части спереди, и сбиваться лечение должно
     *  последним), MOVE 1:1 к остальным. На 1000 это M10H2 — 24 лечения вплотную и 1200 хитов, а не
     *  M5H3 противника (36 и 800): счёт тот же, что выбрал M8R4 вместо M5R5, и он считает не ставку, а
     *  ставку × хиты, которые тело успеет прожить. Болото — 1 тик на клетку, то есть волна с ним не
     *  растягивается ни при какой скорости бойцов.
     *  Лекарь у противника — けろびー с 220-го тика, и его лекари съели от 12% до 63% всего, что мы по
     *  нему выстрелили (пять реплеев 07.09.2026: 1920, 4308, 10044, 5628 и 2276 вылеченного против
     *  нуля у нас). Само по себе тело не стреляет, поэтому покупается только когда прогон осады говорит,
     *  что с ним она кончится раньше (assaultWantsHealer). */
    private fun healerBody(budget: Int, spawnLimited: Boolean = false): Array<BodyPartType> {
        val cap = minOf(budget, SPAWN_ENERGY_CAPACITY)
        return healerBodyCache.getOrPut(bodyKey(cap, spawnLimited)) {
            val block = cost(HEAL) + cost(MOVE)
            var best: Array<BodyPartType>? = null
            var bestValue = -1.0
            var bestHeals = 0
            var h = 1
            while (h * block <= cap && 2 * h <= MAX_CREEP_SIZE) {
                val maxExtra = minOf((cap - h * block) / cost(MOVE), MAX_CREEP_SIZE - 2 * h)
                for (e in 0..maxExtra) {
                    val body = ArrayList<BodyPartType>(2 * h + e)
                    val scored = ArrayList<BodyPartType>(2 * h + e)
                    repeat(e) { scored.add(MOVE) }
                    repeat(h) { scored.add(HEAL) }
                    repeat(h) { scored.add(MOVE) }
                    repeat(e + h) { body.add(MOVE) }
                    repeat(h) { body.add(HEAL) }
                    val arr = body.toTypedArray()
                    val value = bodyValue(scored.toTypedArray()).toDouble() / (if (spawnLimited) arr.size * CREEP_SPAWN_TIME else 1)
                    // РАВНЫЙ СЧЁТ РЕШАЕТСЯ В ПОЛЬЗУ СТАВКИ, А НЕ ЗАПАСНЫХ НОГ. bodyValue считает
                    // лечение суммой по хитам, и на тысячу M10H2 (24 в тик, 1200 хитов) и M5H3 (36 и
                    // 800) дают РОВНО одно и то же — 28800; выбор между ними делал порядок перебора, и
                    // выпадал M10H2. Но лечение упирается не в свою жизнь, а в чужой урон: у けろびー#19
                    // это 400 в тик, и при таком входящем лишний хит не отменяет ничего, а лишняя часть
                    // HEAL отменяет двенадцать. Его собственный лекарь — ровно M5H3.
                    val heals = arr.count { it == HEAL }
                    if (value > bestValue || (value == bestValue && heals > bestHeals)) { bestValue = value; bestHeals = heals; best = arr }
                }
                h++
            }
            best ?: arrayOf(MOVE, HEAL)
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
        return fighterBodyCache.getOrPut(bodyKey(cap, spawnLimited)) {
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
                        // СОСТАВ считается по прежней раскладке (bodyValue и её обрыв рассуждают о
                        // скорости, а не о порядке), а ВЫПУСКАЕТСЯ тело стволами назад: урон снимает части
                        // спереди, и матч 40 кончился тремя остовами с целыми ногами и сбитым оружием, тогда
                        // как его M5R5 — это [MOVE×5, RANGED×5] и в логе виден как «M1R5 564/1000»:
                        // одна живая нога и ВСЕ пять стволов
                        val scored = ArrayList<BodyPartType>(2 * (r + t) + e)
                        repeat(t) { scored.add(TOUGH) }
                        repeat(e) { scored.add(MOVE) }
                        repeat(r) { scored.add(RANGED_ATTACK) }
                        repeat(r + t) { scored.add(MOVE) }
                        repeat(t) { body.add(TOUGH) }
                        repeat(e + r + t) { body.add(MOVE) }
                        repeat(r) { body.add(RANGED_ATTACK) }
                        val arr = body.toTypedArray()
                        val value = bodyValue(scored.toTypedArray()).toDouble() / (if (spawnLimited) arr.size * CREEP_SPAWN_TIME else 1)
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

    /** `direct` — the plan that won the comparison in [siegeOutcome]: the spawn straight away, past his shielded
     *  defenders and his towers, rather than them first. */
    private class SiegeResult(val win: Boolean, val ticks: Int, val hitsLost: Int, val direct: Boolean = false, val left: Double = Double.MAX_VALUE) {
        override fun toString() = "${if (win) "win" else "lose"}/${ticks}t/-$hitsLost${if (direct) "/direct" else ""}"

        /** Лучше — та осада, что кончается ПОБЕДОЙ раньше; при равном сроке — дешевле по хитам. Два
         *  проигрыша не сравниваются вовсе: «продержаться на десять тиков дольше» — не причина менять
         *  состав армии, и первая попытка сравнивать их сроком выбирала мили там, где осады нет. */
        fun better(other: SiegeResult): Boolean =
            if (!win) (USE_PROGRESS_COMPARE && !other.win && left < other.left)
            else if (!other.win) true
            else ticks < other.ticks || (ticks == other.ticks && hitsLost < other.hitsLost)
    }
    private val SIEGE_LOSE = SiegeResult(false, Int.MAX_VALUE / 2, 0)

    /** Предел симуляции осады в тиках: дольше — не осада, а размен на истощение. */
    private const val SIEGE_LIMIT = 400

    /** Боец в симуляции: живые части спереди назад, урон снимает их по порядку (как в движке).
     *  reach — доля удара мили, которая дойдёт до ЖИВЫХ (meleeFactor): структура не кайтит, защитник
     *  кайтит, и это два разных урона у одного тела. */
    private class SimUnit(parts: List<Pair<BodyPartType, Int>>, val reach: Double = 1.0) {
        val types = parts.map { it.first }
        val hits = IntArray(parts.size) { parts[it].second }
        fun alive() = hits.any { it > 0 }
        fun total() = hits.sum()
        /** По СТРУКТУРЕ: спавн и башня не уходят от мили, поэтому удар полный. */
        fun dps(): Double {
            var d = 0.0
            for (i in types.indices) {
                if (hits[i] <= 0) continue
                if (types[i] == RANGED_ATTACK) d += RANGED_ATTACK_POWER.toDouble()
                else if (types[i] == ATTACK) d += ATTACK_POWER.toDouble()
            }
            return d
        }
        /** По ЗАЩИТНИКАМ: мили доходит той же долей, что и в поле. Без этого симуляция списывала
         *  очередь лекарей у чужого спавна по 150 в тик и обещала осаду на тик короче стрелковой
         *  (стенд fortress: melee=win/13t против ranged=win/14t — разница внутри ошибки модели). */
        fun creepDps(): Double {
            var d = 0.0
            for (i in types.indices) {
                if (hits[i] <= 0) continue
                if (types[i] == RANGED_ATTACK) d += RANGED_ATTACK_POWER.toDouble()
                else if (types[i] == ATTACK) d += ATTACK_POWER * reach
            }
            return d
        }
        /** Лечение в тик — по RANGED_HEAL_POWER (4 с части), а не по HEAL_POWER (12). Вплотную лекарь
         *  лечит втрое сильнее, и у противника 96% лечений оказались именно вплотную (реплеи 07.09.2026:
         *  80+3r, 120+0r, 279+5r, 155+7r, 95+4r), — но ВПЛОТНУЮ это положение, а положений эта симуляция
         *  не знает: она считает волну точкой. Для урона такое допущение осторожно (достаётся всем), для
         *  лечения — наоборот, и по полной ставке прогон обещал, что один лекарь кончит осаду на
         *  одиннадцать тиков раньше пятого стрелка (стенд tower+enemy: 56 против 67), после чего волна
         *  уходила пятёркой вместо шестёрки и матч не выигрывался вовсе. Считаем то, что лекарь даёт
         *  БЕЗ положения; за остальное платит строй, а строя прогон не обещает. */
        fun healPower(): Double {
            var h = 0.0
            for (i in types.indices) if (hits[i] > 0 && types[i] == HEAL) h += RANGED_HEAL_POWER.toDouble()
            return h
        }
        /** Вернуть хиты. Движок держит их «сзади наперёд» (part[i] = hits − 100×(n−1−i)), значит лечение
         *  поднимает самую ЗАДНЮЮ повреждённую часть — ту, которую урон снял последней; мёртвая часть при
         *  этом оживает, она не удалена, у неё ноль хитов. Возвращает возвращённое. */
        fun mend(amount: Double): Double {
            var left = amount
            for (i in hits.indices.reversed()) {
                if (left <= 0.0) break
                if (hits[i] >= 100) continue
                val add = minOf((100 - hits[i]).toDouble(), left)
                hits[i] += add.toInt()
                left -= add
            }
            return amount - left
        }
        /** Недостающие хиты — по сотне на часть (это максимум части в движке). */
        fun missing() = types.size * 100 - total()
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
    /**
     * TWO PLANS, THE BETTER ONE (v83). The siege used to have one order: his defenders first, then the tower if it was
     * the cheaper work, then the spawn. Once a defender on his rampart is priced honestly (v82, 10000+ of work each),
     * that order loses against every fortress, and the wave holds at the tower's edge for a thousand ticks while the
     * spawn — the only thing that wins — stands there (v82 against marlyman123#96: `sim=lose`, `join=lose`, hold=1160).
     * The other order is as legal: the spawn straight away, killing only the defenders that are NOT shielded, while the
     * shielded ones and the towers keep firing. Both are run and the better kept (SiegeResult.better: the earlier win);
     * with nothing shielded and no tower there is nothing to skip and the second run is not made. On the stub the
     * tower scenarios now print `/direct` verdicts that win sooner, and every one of the 26 still ends on the same tick
     * as v73. The live fire follows the chosen plan (stormDirect).
     */
    private fun siegeOutcome(wave: List<Creep>, attrition: Double, defenders: List<Creep>, towers: List<TowerInfo>, spawn: StructureSpawn, rampartHits: Int, ratio: Double, flow: IntArray, extraShots: Int = 0, extra: Array<BodyPartType>? = null, approach: Int = 0): SiegeResult {
        val ordered = siegeRun(wave, attrition, defenders, towers, spawn, rampartHits, ratio, flow, extraShots, extra, approach, direct = false)
        if (towers.isEmpty() && defenders.none { shieldAt(it) > 0 }) return ordered
        val direct = siegeRun(wave, attrition, defenders, towers, spawn, rampartHits, ratio, flow, extraShots, extra, approach, direct = true)
        return if (direct.better(ordered)) direct else ordered
    }

    private fun siegeRun(wave: List<Creep>, attrition: Double, defenders: List<Creep>, towers: List<TowerInfo>, spawn: StructureSpawn, rampartHits: Int, ratio: Double, flow: IntArray, extraShots: Int, extra: Array<BodyPartType>?, approach: Int, direct: Boolean): SiegeResult {
        if (wave.isEmpty()) return SIEGE_LOSE
        // extra — ещё не купленное тело: тем же прогоном спрашиваем, с каким из них осада кончится раньше
        val units = ArrayList(wave.map { c ->
            SimUnit(c.body.filter { it.hits > 0 }.map { it.type to it.hits }, meleeFactor(c, defenders, null))
        })
        if (extra != null) units.add(SimUnit(extra.map { it to 100 }, meleeReach(extra, defenders)))
        // НАШЕ ЛЕЧЕНИЕ — в прогоне. Чужое из нашего урона вычиталось всегда (defs.heal ниже), своего
        // не было вовсе, и на вопрос «а если лекарь» симуляция отвечала «строго хуже» ПО ПОСТРОЕНИЮ:
        // тело без урона в модели без лечения не может ничего. Лечим самого пострадавшего живого —
        // тот же выбор, что делает healAndShoot
        val anyHeal = units.any { u -> u.types.any { it == HEAL } }
        fun mendWave(): Double {
            if (!anyHeal) return 0.0 // прогон без лекарей не платит за них ничего: он идёт каждый тик
            val alive = units.filter { it.alive() }
            val power = alive.sumOf { it.healPower() }
            if (power <= 0.0) return 0.0
            val v = alive.maxByOrNull { it.missing() } ?: return 0.0
            return v.mend(power)
        }
        // МАРШ ТОЖЕ БОЙ, И ЛЕКАРЬ ЛЕЧИТ НА НЁМ. attrition — урон, полученный ПО ДОРОГЕ, и прежде он
        // вываливался в волну одним куском ДО прогона, где лечение только и работало. То есть ровно в
        // том месте, ради которого лекарь и нужен, его в модели не было вовсе, и на вопрос «а если
        // лекарь» симуляция отвечала «строго хуже» по построению: за сорок матчей вердикт «healer»
        // выиграл 2 раза из 1714, а куплен лекарь не был НИ РАЗУ за семьдесят с лишним живых матчей,
        // при том что чужое лечение отменяет от 42% до 85% всего, что мы выстрелили.
        // Разносим урон марша по тикам подхода и лечим каждый тик — тем же mendWave, что и в осаде.
        // approach = 0 (звонящие, которым ход неизвестен) оставляет прежнее поведение слово в слово.
        fun absorb(damage: Double): Boolean {
            var left = damage
            while (left > 0.0) {
                val v = units.filter { it.alive() }.minByOrNull { it.total() } ?: return false
                val taken = v.hit(left)
                if (taken <= 0.0) return false
                left -= taken
            }
            return true
        }
        if (approach > 0 && attrition > 0.0) {
            val perTick = attrition / approach
            repeat(approach) {
                if (!absorb(perTick)) return SIEGE_LOSE
                mendWave()
            }
        } else if (!absorb(attrition)) return SIEGE_LOSE
        // a defender on his rampart is killed through it: `shield` goes first, at our full damage (heal restores a
        // creep, never a rampart), and only then the creep's hits against our damage minus his heal (see enemyShield)
        class Def(val hits: Double, val dps: Double, val heal: Double, val shield: Double)
        // the direct plan leaves his shielded defenders alone: they fire (and heal) for the whole siege
        val skipped = if (direct) defenders.filter { shieldAt(it) > 0 } else emptyList()
        val skippedDps = skipped.sumOf { effectiveDps(it, wave, spawn) }
        val skippedHeal = skipped.sumOf { InfluenceMap.profileOf(it).heal }
        val defs = ArrayDeque(defenders.filter { it !in skipped }
            .sortedWith(compareByDescending<Creep> { InfluenceMap.profileOf(it).heal }.thenBy { it.hits })
            .map { Def(it.hits.toDouble(), effectiveDps(it, wave, spawn), InfluenceMap.profileOf(it).heal, shieldAt(it).toDouble()) })
        var defHits = defs.firstOrNull()?.hits ?: 0.0
        var defShield = defs.firstOrNull()?.shield ?: 0.0
        // башня — цель с хитами: её огонь идёт, пока она жива, и наш урон её снимает (см. кольцо ниже)
        class Gun(val tower: TowerInfo, val shot: Double, var next: Int, var hits: Double)
        // ЧЕМ БЛИЖЕ СТОИМ, ТЕМ СИЛЬНЕЕ ВЫСТРЕЛ. Дистанция башни считается от дистанции, с которой мы
        // бьём цель: стрелок стоит в трёх клетках, мили — в одной, и там выстрел на сотню больше. Без
        // этого прогон с мили-телом обещал победу под тремя башнями крепости и получал её на сто
        // семнадцать тиков позже (стенд fortress)
        val standoff = if (extra != null && extra.none { it == RANGED_ATTACK } && extra.any { it == ATTACK }) 1 else RANGED_RANGE
        val guns = towers.mapTo(ArrayList()) {
            Gun(it, InfluenceMap.towerShot(towerRangeFor(it, listOf(spawn), standoff)), maxOf(0, it.cooldown),
                (it.obj?.hits ?: TOWER_HITS).toDouble() + shieldAt(it.pos))   // its rampart is part of its hits
        }
        var lost = 0.0
        fun fire(t: Int, shotOf: (Gun) -> Double) {
            for (g in guns) {
                if (t < g.next) continue
                val shot = shotOf(g)
                if (shot <= 0.0) continue
                // башня бьёт самого ОПАСНОГО, а лекарь опасен: пока он жив, выстрел приходится отменять
                // каждый тик. Без этого он в прогоне был бессмертен (dps=0 — значит никогда не цель) и
                // получался дешевле любого стрелка по построению
                val v = units.filter { it.alive() }.maxWithOrNull(compareBy({ it.dps() + it.healPower() }, { it.total() })) ?: return
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
                // ТЕМП МАРША — по самому медленному из тех, КТО ЕЩЁ ХОДИТ. У крипа без живых MOVE период
                // «никогда» (periodOn), и максимум по всей группе превращал подход в repeat на полмиллиарда:
                // стенд tower+healball встал намертво на 480-м тике, как только в группу для вопроса о теле
                // попала вся армия, а в ней — сбитый на ноги боец. Идти некому — осады нет
                val period = wave.filter { liveMoves(it) > 0 }.maxOfOrNull { periodAt(it, cell / 100, cell % 100) }
                    ?: return SIEGE_LOSE
                repeat(period) {
                    fire(clock) { g -> InfluenceMap.towerShot(getRange(g.tower.pos, InfluenceMap.cell(cell / 100, cell % 100))) }
                    lost = maxOf(0.0, lost - mendWave()) // лечат и на марше: подход по болоту — полсотни тиков под башней
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
                val v = units.filter { it.alive() }.maxWithOrNull(compareBy({ it.dps() + it.healPower() }, { it.total() })) ?: break
                lost += v.hit(g.shot)
            }
        }
        var spawnHits = (spawn.hits ?: SPAWN_HITS) * ratio + rampartHits
        for (i in 0 until SIEGE_LIMIT) {
            val t = clock + i
            fire(t) { g -> g.shot }
            val defDps = defs.sumOf { it.dps } + skippedDps
            if (defDps > 0.0) {
                val v = units.filter { it.alive() }.minByOrNull { it.total() }
                if (v != null) lost += v.hit(defDps)
            }
            lost = maxOf(0.0, lost - mendWave())
            val ourDps = units.sumOf { it.dps() }
            if (ourDps <= 0.0) return SiegeResult(false, i, lost.toInt(), direct, spawnHits)
            if (defs.isNotEmpty()) {
                if (defShield > 0.0) {
                    defShield -= units.sumOf { it.creepDps() }
                    if (defShield < 0.0) { defHits += defShield; defShield = 0.0 }
                } else {
                    val net = units.sumOf { it.creepDps() } - defs.sumOf { it.heal } - skippedHeal
                    if (net <= 0.0) return SiegeResult(false, i, lost.toInt(), direct, spawnHits)
                    defHits -= net
                }
                while (defHits <= 0.0 && defs.isNotEmpty()) {
                    val carry = -defHits
                    defs.removeFirst()
                    defShield = defs.firstOrNull()?.shield ?: 0.0
                    defHits = (defs.firstOrNull()?.hits ?: 0.0) - (if (defShield > 0.0) 0.0 else carry)
                    if (defShield > 0.0) defShield -= carry
                }
            } else {
                // защитников нет — БАШНЯ перед спавном: пока она жива, её выстрел ложится каждый кулдаун
                // до конца осады, и снять его — то же, что снять защитника, только навсегда. Лечения у
                // неё нет, хиты известны, и при собранном огне это десяток тиков. Мягкие цели вперёд:
                // защитники и стреляют, и умирают быстрее, поэтому они выше по порядку
                // ...и только если она умрёт РАНЬШЕ спавна: спавн и есть условие победы, а башня —
                // помеха. Три башни крепости это девять тысяч хитов против трёх у спавна: грызть их
                // вместо цели — проигрыш по определению
                // ...и только если ВСЕ достающие башни в сумме не дороже спавна. При равных хитах (в этой
                // арене у обоих по 3000) башня выгоднее: урон тот же, а стрелять она больше не будет
                // никогда — ни по этой волне, ни по следующим. Три башни крепости — девять тысяч против
                // трёх, и грызть их вместо цели уже проигрыш. Сравнение то же, что в стрельбе
                val reach = guns.filter { it.shot > 0.0 }
                val gun = if (!direct && reach.sumOf { it.hits } <= ((spawn.hits ?: SPAWN_HITS) + rampartHits).toDouble()) reach.firstOrNull() else null
                if (gun != null) {
                    gun.hits -= ourDps
                    if (gun.hits <= 0.0) guns.remove(gun)
                } else {
                    spawnHits -= ourDps
                    if (spawnHits <= 0.0) return SiegeResult(true, i + 1, lost.toInt(), direct)
                }
            }
        }
        return SiegeResult(false, SIEGE_LIMIT, lost.toInt(), direct, spawnHits)
    }

    // ==================== армия ====================

    private var towerFireTick = -1
    private var towerFire: IntArray? = null

    private fun flowTo(ctx: Ctx, target: Position): IntArray =
        flowCache.getOrPut(target.x * 100 + target.y) { DistanceMap.flowFieldTo(target, ctx.blocked) }

    /** Урон кормленных чужих башен по клеткам — цена клетки в поле подхода. Заполняются только клетки
     *  в круге каждой башни: за TOWER_FALLOFF_RANGE выстрел не долетает вовсе. */
    private fun towerFireField(ctx: Ctx): IntArray? {
        // ONE FIELD A TICK (v78): the towers are fixed for the tick, and every assaultTo used to rebuild the whole
        // 10000-cell field and its 43×43 circle per tower before it even looked at its own cache
        if (towerFireTick == getTicks()) return towerFire
        towerFireTick = getTicks()
        towerFire = null
        val fed = ctx.enemyTowers.filter { it.fed }
        if (fed.isEmpty()) return null
        val fire = IntArray(10000)
        towerFire = fire
        for (t in fed) {
            for (dx in -TOWER_FALLOFF_RANGE..TOWER_FALLOFF_RANGE) for (dy in -TOWER_FALLOFF_RANGE..TOWER_FALLOFF_RANGE) {
                val x = t.pos.x + dx
                val y = t.pos.y + dy
                if (x < 0 || y < 0 || x > 99 || y > 99) continue
                val shot = InfluenceMap.towerShot(maxOf(abs(dx), abs(dy))) / InfluenceMap.towerCooldown
                if (shot <= 0.0) continue
                val i = x * 100 + y
                fire[i] = minOf(DistanceMap.FIRE_CAP, fire[i] + ceil(shot).toInt())
            }
        }
        return fire
    }

    /** ПОЛЕ ПОДХОДА к чужому спавну: то же поле пути, но цена клетки — полученный там урон (см.
     *  flowFieldTo). Им ходит волна и по нему же шагает симуляция осады, поэтому её приговор относится
     *  к тому маршруту, которым волна действительно пойдёт. Башен нет — это обычное поле, клетка в
     *  клетку прежнее. Часы (travelOf, homeTravel) остаются на обычном поле: они меряют ход, а не
     *  урон, и обход в них не входит — оценка выхода становится оптимистичнее на длину обхода. */
    private fun assaultTo(ctx: Ctx, target: Position): IntArray {
        val fire = towerFireField(ctx) ?: return flowTo(ctx, target)
        return assaultCache.getOrPut(target.x * 100 + target.y) {
            DistanceMap.flowFieldTo(target, ctx.blocked, DistanceMap.SWAMP_COST, fire)
        }
    }

    /** Армия. Возвращает мощь наступления (ушедшие волны плюс готовые уйти с поста) — для журнала. */
    private fun runFighters(ctx: Ctx, enemyPower: Double, alarm: Boolean): Double {
        val fighters = ctx.fighters
        measureIncoming(fighters, ctx.combatEnemies)
        if (fighters.isEmpty()) { wave.clear(); return 0.0 }
        // остов (стрельба выбита) из волны выбывает: он идёт домой (см. !hasWeapon), а волна держала строй
        // «для отставшего» по нему — семеро стояли в сорока клетках от спавна врага сто тиков, пока f49 с
        // четырьмя MOVE уходил к нашему (стенд tower+stream: чужой спавн умер на 1545-м вместо 1125-го)
        wave.keys.retainAll { id -> fighters.any { it.id == id && inArms(it) } }
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
        // РАБОТА РЕШАЕТ СОСТАВ, и отвечает на это симуляция осады (assaultWantsMelee, ниже): по
        // структуре ATTACK даёт 30 урона за 80 энергии против 10 за 150 у RANGED — впятеро дешевле, —
        // а по кайтящему стрелку не работает вовсе, и обе половины она моделирует сама. Мгновенный
        // срез («хиты структуры больше хитов живых крипов») этого не умеет: в ПОТОКЕ живых крипов
        // всегда мало, и мили уходил под кайт (стенд: stream17 888->1455)
        // ...и только пока мили не нужен ДОМА: тот же guardNeeded, по которому он и строится против
        // мили-шара. Иначе волна уводит гарнизон ровно тогда, когда к дому идёт поток (стенд stream17:
        // наш спавн снесён на 1357-м)
        // ...и лекарь идёт со всеми всегда: стрелять он не умеет, дома в одиночку не делает ничего,
        // а его дело — держать живой ту группу, которая работает
        val strikers = fighters.filter { fullSpeed(it) && (hasRanged(it) || hasHeal(it) || (assaultWantsMelee && !guardNeeded && hasMelee(it))) }
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
        // THE ARMY GATHERS AT OUR SPAWN NEAREST THE TARGET (v98). Since the pile spawns (v88-v92) 60-70 % of our
        // bodies are born at forward spawns, and every one of them walked to the home post first, to be staged there
        // and walk back out: 200-440 ticks from home to his base against marlyman123, the time the siege never had.
        // The rally point is the spawn of ours nearest the target that is not under fire now; the waves are staged in
        // the alarm ring of THAT spawn, idle guns stand at its post. The home stays the home: its threats still call
        // the free fighters (homeTarget), the husks and the melee guard go there.
        // "Nearest" is by the walk, on the field the waves march by, not by range: in the gate's fortress a pile spawn
        // at (88,65) is six cells "nearer" his (5,50) than home (94,49) and a longer walk, and the rally there cost
        // tower+fortspawn 942 -> 1177
        val rallySpawn: StructureSpawn = if (!USE_RALLY_FORWARD || enemySpawn == null) mySpawn else {
            val walk = flowTo(ctx, enemySpawn)
            ctx.mySpawns.filter { sp -> InfluenceMap.damageAt(sp.x, sp.y, combatEnemies) <= 0.0 && stepsFrom(walk, sp) >= 0 }
                .minByOrNull { stepsFrom(walk, it) } ?: mySpawn
        }
        val rallyField = if (rallySpawn.id == mySpawn.id) ctx.loadedToSpawn else flowTo(ctx, rallySpawn)
        fun atHome(c: Creep) = rallyField[c.x * 100 + c.y] in 0..SPAWN_ALARM_TICKS
        val freeStrikers = strikers.filter { it.id !in wave }
        val staging = freeStrikers.filter { atHome(it) }
        // сила наступления: ушедшие волны (кто ещё вооружён — волна своих ждёт, см. hold) плюс те,
        // кто готов уйти с поста
        val offensive = fighters.filter { (it.id in wave && inArms(it)) || staging.any { s -> s.id == it.id } }
        val ourOffense = ourPowerOf(offensive, combatEnemies)
        // волна в пути платит за каждую стычку (fightCost): идущих к нам встречаем группами (группа —
        // одновременно), рождённых за марш и осаду — по одному, типичным бойцом врага. Уходим, если
        // после всех стычек и ещё одной такой же (запас на ошибку оценки) хиты остаются, а по
        // Ланчестеру остаток сильнее стоящих у врага дома. Прежний счёт «мощь против мощи» не знал
        // цены пути: пара M8R4 ушла при 438 против 437 и легла об два M5R5 подряд (матч 8)
        cpuMark("f.pre")
        val production = enemyProductionPerTick(getTicks(), combatEnemies + ctx.pendingEnemies)
        val massing = combatEnemies.filter { it.id !in approachingIds }
        val massingPower = enemyPowerOf(massing, strikers)
        // THE SIEGE IS DEFENDED BY WHO CAN GET THERE (v87). `massing` is every armed creep of his not walking at us,
        // wherever it stands — against marlyman123#96 `massing=1999` put his field army, forty cells away, into the
        // siege of his main spawn, and every siege of it read `lose`. A defender of the target is one that reaches it
        // within our approach and the siege (at its plain pace, the optimistic one for him); the rest are the field
        // army, which the march already pays for (attrition, maxPack) and the posture weighs (massingPower).
        val siegeDefenders = if (!USE_LOCAL_DEFENDERS || enemySpawn == null) massing else {
            val within = (minOf(travel, arenaInfo.ticksLimit) + SIEGE_LIMIT / 4).toLong()
            massing.filter { getRange(it, enemySpawn).toLong() * plainPeriod(it).coerceAtMost(10) <= within }
        }
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
        // …and the rampart his builder puts over it by the time our siege would be over (v108, see rampartBy)
        val spawnRampart = if (enemySpawn != null && USE_PENDING_RAMPART) rampartBy(enemySpawn, travel, waveDps) else spawnRampartHits(ctx)
        // на выход — ГРУППА ПОСТА, которая уйдёт вместе (волны друг друга не ждут: подкрепление по двое
        // догоняло первую волну через сотню тиков и ложилось под башню по очереди — стенд); на
        // продолжение — ушедшие волны
        val waveMembers = fighters.filter { it.id in wave && inArms(it) }
        val spawnFlow = if (enemySpawn != null) flowTo(ctx, enemySpawn) else IntArray(0)
        // маршрут волны — по урону; часы (startTravel, homeTravel, сплочение) остаются на spawnFlow
        val assaultFlow = if (enemySpawn != null) assaultTo(ctx, enemySpawn) else IntArray(0)
        // ФРОНТ волны — те, кто держится вместе: в зазоре сплочения марша от авангарда по полю к спавну
        // врага. Осада на продолжение считается по фронту, а не по всем ушедшим: подкрепление в полутора
        // сотнях клеток позади в осаде не участвует, и «выигрыш» с ним отправил бы авангард под башню одного
        val waveFront = if (waveMembers.isNotEmpty() && spawnFlow.isNotEmpty()) {
            val van = waveMembers.mapNotNull { m -> spawnFlow[m.x * 100 + m.y].takeIf { it >= 0 } }.minOrNull() ?: 0
            waveMembers.filter { m -> spawnFlow[m.x * 100 + m.y].let { it >= 0 && it - van <= COHESION_GAP } }
        } else waveMembers
        // ход считается ДО прогонов: он им теперь нужен — по нему разносится урон марша (см. approach)
        val startTravel = travelTicksOf(staging, assaultFlow, spawnFlow)
        val frontTravel = travelTicksOf(waveFront, assaultFlow, spawnFlow)
        cpuMark("f.march")
        val siegeStart = if (enemySpawn != null) siegeOutcome(staging, attrition + unitCost, siegeDefenders, siegeTowers, enemySpawn, spawnRampart, PUSH_RATIO, assaultFlow, extraShots = 1, approach = startTravel) else SIEGE_LOSE
        val siegeGo = if (enemySpawn != null) siegeOutcome(waveFront, attrition, siegeDefenders, siegeTowers, enemySpawn, spawnRampart, PUSH_RELEASE_RATIO, assaultFlow, approach = frontTravel) else SIEGE_LOSE
        // the front fires the way its winning plan does (v83): past his shielded defenders and his towers, at the spawn
        stormDirect = siegeGo.win && siegeGo.direct
        // осада фронтом ВМЕСТЕ с группой поста: когда волна держит кромку, подкрепление уходит к ней, если
        // сумма выигрывает (с запасом на выход, как siegeStart)
        val siegeJoin = if (enemySpawn != null && waveFront.isNotEmpty() && staging.isNotEmpty()) siegeOutcome(waveFront + staging, attrition + unitCost, siegeDefenders, siegeTowers, enemySpawn, spawnRampart, PUSH_RATIO, assaultFlow, extraShots = 1) else SIEGE_LOSE
        // КАКОЕ ТЕЛО КОНЧИТ ОСАДУ РАНЬШЕ — спрашивается тем же прогоном; ответ читают отбор волны и
        // спавн на следующем тике.
        // ГРУППА ЗДЕСЬ — ВСЯ АРМИЯ, а не фронт, и это не мелочь. Прогон отвечает на «а если ЕЩЁ ОДНО
        // такое тело», а ответ применяется к КАЖДОЙ следующей покупке, пока не переменится: чтобы он
        // переменился, купленное обязано попадать в ту же группу, по которой вопрос и задан. Фронт —
        // четверо в зазоре сплочения, и пополнение в него не входит, поэтому вопрос вечно задавался о
        // четвёрке, а ответ тратил весь матч. С мили это было незаметно (мили всё равно бьёт, и армия
        // из мили спавн доламывает), с лекарем — нет: стенд tower+enemy взял 31 лекаря, четырёх
        // стрелков и не снёс спавн вовсе, где раньше сносил на 827-м
        val siegeCrew = offensive.filter { fullSpeed(it) }.ifEmpty { if (waveFront.isNotEmpty()) waveFront else staging }
        // ...и вопрос ставится только там, где работа СТРУКТУРНАЯ по существу: спавн в броне или под
        // прикрытием башни. По голому спавну симуляция честно отвечает «мили быстрее» (3000 хитов это
        // 22 тика против 65), и она права — но тело покупается не только для осады, а стрелок ещё и
        // защищает флот, пока волна собирается. Без этой привязки бот брал мили против потока и терял
        // дом (стенд stream17: наш спавн снесён на 1357-м, где раньше была победа на 888-м)
        val armoured = spawnRampart > 0 || siegeTowers.isNotEmpty()
        if (enemySpawn == null || siegeCrew.isEmpty()) { assaultWantsMelee = false; assaultWantsHealer = false }
        else {
            // с тем же маршем: тело выбирается по тому, чем кончится ВЕСЬ поход, а не только работа
            // под спавном, — иначе лекарь снова оценивается там, где он не нужен
            val crewTravel = travelTicksOf(siegeCrew, assaultFlow, spawnFlow)
            fun run(extra: Array<BodyPartType>) =
                siegeOutcome(siegeCrew, attrition, siegeDefenders, siegeTowers, enemySpawn, spawnRampart, PUSH_RATIO, assaultFlow, extra = extra, approach = crewTravel)
            val withRanged = run(fighterBody(SPAWN_ENERGY_CAPACITY))
            val withMelee = if (armoured) run(guardBody(SPAWN_ENERGY_CAPACITY)) else SIEGE_LOSE
            // ЛЕКАРЬ спрашивается всегда, когда осада вообще считается. Он ничего не ломает, значит по
            // незащищённой цели прогон честно скажет «дольше» и его не возьмут; выиграть он может
            // только тем, ради чего и нужен, — тем, что волна доживает до конца работы
            val withHealer = run(healerBody(SPAWN_ENERGY_CAPACITY))
            var bestName = "ranged"
            var best = withRanged
            if (withMelee.better(best)) { bestName = "melee"; best = withMelee }
            if (withHealer.better(best)) { bestName = "healer"; best = withHealer }
            assaultWantsMelee = bestName == "melee"
            assaultWantsHealer = bestName == "healer"
            if (DEBUG_LOG && getTicks() % LOG_EVERY == 0 && (bestName != "ranged" || withRanged.win)) {
                println("assault body: ranged=$withRanged melee=$withMelee healer=$withHealer rampart=$spawnRampart crew=${siegeCrew.size} heal=${siegeCrew.count { hasHeal(it) }} cap=$bodyCap -> $bestName")
            }
        }
        // СРОК ВЫХОДА: штурм успевает, только если группа ещё дойдёт и добьёт до лимита тиков. Ход
        // группы — по САМОМУ дальнему её бойцу (идут вместе, осада начинается с приходом последнего),
        // время осады — из её же симуляции. Прежний «последний звонок» брал ход БЛИЖАЙШЕГО бойца и урон
        // ВСЕЙ армии, включая стоящих дома: в матче 18 он сработал на 1990-м, когда авангард стоял в 51
        // клетке, а масса армии — в сотне, и матч кончился ничьей при нетронутых 3000 хитов чужого спавна
        fun budget(travelTicks: Int, siege: SiegeResult) = travelTicks.toLong() + minOf(siege.ticks, SIEGE_LIMIT)
        // ход считается по маршруту подхода и СВОИМ телом (pathTicks), а не по цене поля: цена поля
        // под огнём — это урон, а часам нужны тики. Пустое поле или недостижимая цель — прежний ответ
        val never = Long.MAX_VALUE / 4
        // THE CLOCK COUNTS EVERY SPAWN HE HAS (v85). Only the fall of the LAST of his spawns wins (the replays: his
        // original fell at t=688 with three more standing and the match ran to a draw), yet the deadline priced the
        // walk and siege of one: the last call of 0c6bf0 fired at t=1600 with three alive. After the target, the rest
        // are taken nearest-next, each walked at the group's slowest swamp pace (pessimistic, by Chebyshev) and worked
        // down, rampart included, by the group's damage to structures.
        val tourGroup = (waveFront + staging).ifEmpty { siegeCrew }
        // WHICH OF HIS SPAWNS THIS GROUP CAN TAKE, AND HOW SOON (v104; read by the target choice at the next tick's start).
        // For each: the group's walk on the assault field to it, his creeps that get there within the walk and a
        // quarter of the siege limit, his fed towers over it (and those done by then), his rampart on it — and the
        // siege run on all of it. A siege it loses costs "never". In the three draws against marlyman123 the target
        // was "nearest to our house", kept while a wave was out: his new spawns stood untouched 330-730 ticks each
        // while the front waited at the one fort it could not take (C: 6437 of ours against 2551, and 0 of 6 taken)
        if (USE_TARGET_BY_TAKE && (getTicks() % LOG_EVERY == 0 || ctx.enemySpawns.any { it.id !in targetCost })) {
            targetCost.clear()
            val tourDpsNow = tourGroup.sumOf { val p = InfluenceMap.profileOf(it); p.ranged + p.melee }
            for (s in ctx.enemySpawns) {
                if (tourGroup.isEmpty()) { targetCost[s.id] = Long.MAX_VALUE; continue }
                val field = assaultTo(ctx, s)
                val walk = travelTicksOf(tourGroup, field, flowTo(ctx, s))
                if (walk >= Int.MAX_VALUE / 4) { targetCost[s.id] = Long.MAX_VALUE; continue }
                val within = (walk + SIEGE_LIMIT / 4).toLong()
                val defs = massing.filter { getRange(it, s).toLong() * plainPeriod(it).coerceAtMost(10) <= within }
                val towersS = coveringTowers(ctx, listOf(s)) + ctx.pendingTowers.filter {
                    it.eta <= walk + SIEGE_LIMIT && InfluenceMap.towerShot(towerRangeFor(it.info, listOf(s))) > 0.0
                }.map { it.info }
                val sim = siegeOutcome(tourGroup, attrition, defs, towersS, s, if (USE_PENDING_RAMPART) rampartBy(s, walk, tourDpsNow) else shieldAt(s), PUSH_RATIO, field, approach = walk)
                targetCost[s.id] = if (sim.win) walk.toLong() + sim.ticks else Long.MAX_VALUE
            }
            if (DEBUG_LOG && getTicks() % (LOG_EVERY * 5) == 0) println("targets t=${getTicks()}: " + ctx.enemySpawns.joinToString(" ") { s ->
                "(${s.x},${s.y})=${targetCost[s.id].let { if (it == null || it >= Long.MAX_VALUE / 4) "-" else it.toString() }}${if (s.id == enemySpawn?.id) "*" else ""}"
            } + " group=${tourGroup.size}")
        }
        val tourDps = tourGroup.sumOf { val p = InfluenceMap.profileOf(it); p.ranged + p.melee }
        val tour: Long = if (!USE_TOUR_CLOCK || enemySpawn == null) 0L else {
            val period = tourGroup.maxOfOrNull { swampPeriod(it) } ?: 1
            var t = 0L
            var from: Position = enemySpawn
            val rest = ctx.enemySpawns.filter { it.id != enemySpawn.id }.toMutableList()
            while (rest.isNotEmpty()) {
                if (tourDps <= 0.0) { t = never; break }
                val next = rest.minByOrNull { getRange(from, it) }!!
                t += getRange(from, next).toLong() * period + ceil(((next.hits ?: SPAWN_HITS) + shieldAt(next)) / tourDps).toLong()
                from = next
                rest.remove(next)
            }
            t
        }
        val goNeed = minOf(
            if (staging.isEmpty()) never else budget(startTravel, siegeStart),
            if (waveFront.isEmpty()) never else budget(frontTravel, siegeGo)).let { if (it >= never || tour >= never) never else it + tour }
        val lastCall = goNeed < never && remaining <= goNeed + LATE_MARGIN
        siegeEndsIn = goNeed
        // Может ли враг ещё отнять у нас спавн за остаток: его ближайший боец доходит за enemyApproach и
        // снимает 3000 хитов своим уроном (рождённый позже карту уже не пересечёт). Пока может — армию
        // из дома не выгребаем даже в конце: положенная под башню, она обменяла бы ничью на поражение
        // (матч 9). Не может — ничья и поражение стоят одного, и терять нечего
        val enemyReach = combatEnemies.minOfOrNull {
            ctx.enemyApproach[it.x * 100 + it.y].let { d -> if (d < 0) Int.MAX_VALUE / 4 else d }
        } ?: Int.MAX_VALUE / 4
        val enemyDps = combatEnemies.sumOf { val p = InfluenceMap.profileOf(it); p.ranged + p.melee }
        val houseFallsAt = if (enemyDps > 0.0) enemyReach + (mySpawn.hits ?: SPAWN_HITS) / enemyDps else Double.MAX_VALUE
        val homeAtRisk = enemyDps > 0.0 && houseFallsAt <= remaining.toDouble()
        val notWeaker = ourOffense >= enemyPower
        // тревога отменяет наступление, только если ДОМАШНИЙ гарнизон с угрозой не справится:
        // разведчик врага у северного выхода отзывал всю армию с южного (02.09, трижды). Гарнизон —
        // те, кто ОСТАНЕТСЯ дома: без ждущих ухода (staging) — иначе «держит» считалось с теми, кто
        // через тик уйдёт, и постура прыгала PUSH/DEFEND каждый тик (матч 9: шестнадцать волн за
        // тридцать тиков). Спавн под огнём — наступление не начинаем и не продолжаем вовсе, пока
        // не выигрываем гонку спавнов: пять мили били наш спавн, а армия ушла волной (матч 9)
        // ДЕРЖАЩИЕ НАШУ ЭНЕРГИЮ — тоже угроза дому. Точка, рядом с которой стоит боевой враг, из
        // пригодных вычёркивается целиком, и в ничьей 1999 тиков флот из четырнадцати вёз два в тик при
        // появлении восьмидесяти, пока семеро бойцов стояли дома. Берём только точки НА НАШЕЙ стороне
        // (ours) и стоящие целого рейса (полный хаулер) — за мелочью через карту не ходят. Гнаться это
        // не заставит: стоящий на точке не отходит, значит catchable на нём верен, а отойдёт — правило
        // само его отпустит; решает всё тот же счёт homeWins и тот же сторож guardHolds
        val tripWorth = HAULER_BLOCKS_MAX * CARRY_CAPACITY
        val denied = ctx.sites.filter { !it.safe && it.ours && it.energy >= tripWorth }
        val homeThreats = combatEnemies.filter { e ->
            ctx.enemyApproach[e.x * 100 + e.y] in 0..SPAWN_ALARM_TICKS ||
                denied.any { getRange(e, it.pos) <= SITE_DANGER_RANGE }
        }
        // счёт — против ВСЕЙ стаи вокруг угроз у дома, как у охоты (матч 6): кольцо тревоги в 40 тиков
        // резало шар по болоту, и «гарнизон бьёт угрозу у дома» считалось против трёх из восьми, а пятеро
        // стояли на клетку дальше (стенд hover: 635 против 464 при 758 у всего шара)
        val homePack = combatEnemies.filter { e -> homeThreats.any { getRange(e, it) <= ENGAGE_RANGE + RANGED_RANGE } }
        val homeGuard = fighters.filter { it.id !in wave && staging.none { s -> s.id == it.id } && inArms(it) }
        val guardHolds = homeThreats.isEmpty() ||
            ourPowerOf(homeGuard, homePack) >= enemyPowerOf(homePack, homeGuard) * DEFEND_MARGIN
        // WHILE A WAVE IS OUT, THOSE STAGED AT HOME ARE THE GARRISON (v102): they leave only with a new wave, and there is
        // none this tick unless one departs. Counted as leaving, they made the garrison fail and called the wave back; a
        // fighter back in the ring became "staging" again and the wave left again — against Ranamar#2 eleven waves in
        // 130 ticks (t=294-426) under one raider, and no wave ever got past (86,89)
        val stayHolds = homeThreats.isEmpty() || !USE_STAGING_GUARDS || waveMembers.isEmpty() || run {
            val stay = fighters.filter { it.id !in wave && inArms(it) }
            ourPowerOf(stay, homePack) >= enemyPowerOf(homePack, stay) * DEFEND_MARGIN
        }
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
            // THE FIGHT STARTS WHEN THE FIRST OF US GETS THERE, NOT NOW (v84). The window was measured from this
            // tick, so against a camp 40-48 ticks out every fighter was "too far" for a line life of ~18 ticks and
            // the garrison read [0/4], [0/5] for a thousand ticks — two draws of the v73 series against armies that
            // our post, once it finally went at the last call, beat 7:0. The post walks together; a fighter is in the
            // fight if it arrives within the line's life of the first arrival.
            val walks = homeAll.associate { it.id to pathTicks(it, toThreat, it.x * 100 + it.y) }
            val first = walks.values.filter { it < Int.MAX_VALUE / 4 }.minOrNull() ?: 0
            homeAll.filter { f ->
                // ДОСТАЁТ ИЛИ ДОГОНИТ. Стрелок, который не достаёт до ближайшего из стаи и не может её
                // догнать (она уходит и не медленнее его — см. catchable, замер по прошлому тику), в этом
                // бою не выстрелит ни разу: он будет идти за ней и получать. Такой бой не доводится, а
                // недоведённый бой против пары с лекарем — чистый убыток: наши хиты не возвращаются, его
                // возвращаются по 72 в тик. Ждать их дома дешевле — спавн не отходит, и прийти к нему
                // им всё равно придётся (тогда они не отходят, и catchable верен сам собой)
                val near = homePack.minByOrNull { getRange(f, it) }
                val reachable = near == null || getRange(f, near) <= RANGED_RANGE || catchable(f, near)
                reachable && (walks[f.id] ?: Int.MAX_VALUE / 2).toLong() <=
                    (if (USE_HOME_FIRST_ARRIVAL) first.toLong() else 0L) + maxOf(fightTicks, RANGED_RANGE)
            }
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
        // A RECALL MUST SAVE SOMETHING (v97). The whole push was called off whenever the home guard did not hold and the
        // race could not be priced (it never can with two spawns of his): against ricardo#24 one M6A3 on our half took
        // twelve bodies off a spawn ten ticks from falling (rampart down, 540 a tick coming off), and fifteen sat at home
        // for two hundred ticks. A recall is worth it only if the wave gets back before the house falls — the home
        // threats' arrival plus our spawn's hits (and rampart) at their damage; a wave that cannot arrive in time saves
        // nothing by leaving and loses the siege it was winning
        val recallSaves = !USE_RECALL_IF_SAVES || waveMembers.isEmpty() || run {
            val threatDps = homeThreats.sumOf { val p = InfluenceMap.profileOf(it); p.ranged + p.melee }
            if (threatDps <= 0.0) return@run false
            val arrive = if (spawnUnderFire) 0 else homeThreats.minOf { (arrivalById[it.id] ?: Int.MAX_VALUE / 4).coerceAtMost(SPAWN_ALARM_TICKS) }
            val house = (mySpawn.hits ?: SPAWN_HITS) + ctx.ramparts.filter { it.my == true && it.x == mySpawn.x && it.y == mySpawn.y }.sumOf { it.hits ?: 0 }
            val falls = arrive + house / threatDps
            val back = waveMembers.maxOf { pathTicks(it, ctx.loadedToSpawn, it.x * 100 + it.y).coerceAtMost(Int.MAX_VALUE / 4) }
            back < falls
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
        // от дома до фронта — тоже по маршруту подхода, телом самого медленного из живых бойцов
        // (новорождённый будет такой же); бойцов нет — по прежнему полю
        val homeTravel = if (assaultFlow.isNotEmpty() && fighters.isNotEmpty())
            flowNear(assaultFlow, mySpawn.x, mySpawn.y).let { cell ->
                if (cell < 0) Int.MAX_VALUE / 4
                else fighters.maxOf { pathTicks(it, assaultFlow, cell) }.let { if (it >= Int.MAX_VALUE / 4) Int.MAX_VALUE / 4 else it }
            }
        else if (spawnFlow.isEmpty()) Int.MAX_VALUE / 4
        else flowNear(spawnFlow, mySpawn.x, mySpawn.y).let { if (it < 0) Int.MAX_VALUE / 4 else spawnFlow[it] }
        val reinforceTravel = if (staging.isNotEmpty()) startTravel else homeTravel
        val newPushing = when {
            enemySpawn == null -> false
            (spawnUnderFire || alarm && !(if (USE_STAGING_GUARDS && waveMembers.isNotEmpty()) stayHolds else guardHolds)) &&
                !pushWinsRace(ctx, ourHalfCombat, siegeGo) && recallSaves -> false
            // последний звонок — тоже только с выигрышной осадой: армия, положенная под башню в конце,
            // не приносит ничьей, а дома она её держит
            // THE LAST CALL WEIGHS THE HOUSE, NOT THE ARMIES (v102): a house he cannot take in what is left cannot be
            // lost by going, so we go; one he can take — we go if the siege wins and ends before it falls. "Not weaker"
            // vetoed the last call of Ranamar#2's draw at t≈1400 (400 < 477) with our spawn never in danger all match
            USE_LAST_CALL_RACE && lastCall -> !homeAtRisk || ((siegeGo.win || siegeStart.win) && goNeed < houseFallsAt)
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
        // reach: the posture of this tick and the home-fight verdict (see homeMode) — ticks with an army only
        postureReach[lastPushReason] = (postureReach[lastPushReason] ?: 0) + 1
        val homeRow = when (homeMode.substringBefore('(')) { "-" -> "calm"; "hold" -> "hHold"; "fight:fire" -> "hFire"; "fight:gates" -> "hGates"; else -> "hWins" }
        postureReach[homeRow] = (postureReach[homeRow] ?: 0) + 1

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

        // ФОКУС-ФАЙР ПО ВРЕМЕНИ ДО СМЕРТИ. Повтор проигранного матча: его доля фокуса 0.95 при 1.14
        // цели в тик, наша 0.75 при 1.68, и молчание тут ни при чём (мы стреляли в 104 случаях из 110).
        // Прежний порядок ставил лечение первым и выбирал лекаря, до которого достаёт ОДИН ствол: сорок
        // урона против тридцати шести лечения — двести двадцать пять тиков, то есть распыление под видом
        // фокуса. Порядок «больше стволов» вместо лечения тоже неверен — стенд ответил tower+healball
        // 976→1231. Верный вопрос один и он же снимает лексикографию: какая цель умрёт БЫСТРЕЕ под теми
        // стволами, что до неё достают, за вычетом лечения тех, кто достаёт до неё
        val inFireRange = enemyCreeps.filter { e -> fighters.any { it.getRangeTo(e) <= RANGED_RANGE } }
        val focusPool = inFireRange.filter { e -> combatEnemies.any { it.id == e.id } }.ifEmpty { inFireRange }
        fun fireAvailableAt(e: Creep) = fighters.filter { it.getRangeTo(e) <= RANGED_RANGE }.sumOf { InfluenceMap.profileOf(it).ranged }
        // лечение, которое ДОСТАЁТ до цели: лечат с трёх клеток, и лечащие сами могут быть где угодно
        fun healCovering(e: Creep) = enemyCreeps.filter { getRange(it, e) <= HEAL_RANGE }.sumOf { InfluenceMap.profileOf(it).heal }
        fun ticksToKill(e: Creep): Double {
            val net = fireAvailableAt(e) - healCovering(e)
            if (net <= 0.0) return Double.MAX_VALUE
            // his rampart under the target takes our fire first, at full damage — heal never restores it (v83)
            val shield = shieldAt(e)
            return (if (shield > 0) shield / fireAvailableAt(e) else 0.0) + e.hits / net
        }
        val focusTarget = focusPool.minWithOrNull(
            compareBy<Creep> { ticksToKill(it) }
                .thenByDescending { InfluenceMap.profileOf(it).heal }
                .thenBy { getRange(it, centroid) }
        )

        // стена пролома: свободные дома добивают её (бурильщик вплотную, стрелки с дистанции)
        val wallTarget = breachPlan(ctx)?.current()

        // враг У ДОМА (в радиусе тревоги по его пути к спавну): выбора нет — дерёмся всем составом,
        // одной целью, без оглядки на соотношение. Матч 02.09: трое врагов в пяти клетках от спавна,
        // в дальности трое наших, девять сидели на посту «без перевеса не идём» и смотрели.
        cpuMark("f.posture")
        val homeTarget = homeThreats.minWithOrNull(compareBy<Creep>({ arrivalOf(it) }, { getRange(it, centroid) }))
        // A SOFT TARGET IS A TEAM'S JOB, NOT THE ARMY'S (v105). Every free gun went after the one soft creep nearest the
        // centroid: against marlyman123#96 all 22 chased one disarmed "M4 400/1200" at t=1760 while the staging post
        // stood empty and no wave ever left. The team is the free guns that can catch it, nearest by their own walk,
        // as many as kill it before it gets home at its own pace; none can catch it — nobody goes
        val raidTeam: Set<String>? = if (!USE_RAID_TEAM || raider == null) null else {
            val field = flowTo(ctx, raider)
            val flight = enemySpawn?.let { pathTicks(raider, flowTo(ctx, it), raider.x * 100 + raider.y) } ?: Int.MAX_VALUE / 4
            val team = HashSet<String>()
            var dps = 0.0
            for ((c, _) in freeStrikers.filter { hasRanged(it) && catchable(it, raider) }
                .map { it to pathTicks(it, field, it.x * 100 + it.y) }.filter { it.second < Int.MAX_VALUE / 4 }.sortedBy { it.second }) {
                team.add(c.id)
                dps += InfluenceMap.profileOf(c).ranged
                if (raider.hits / dps <= flight) break
            }
            team
        }
        val stompOf = if (USE_STOMP && homeThreats.isEmpty()) stompJobs(ctx, fighters.filter { f ->
            f.id !in wave && hasWeapon(f) && strikers.any { it.id == f.id } && !isMelee(f)
        }, combatEnemies, centroid) else emptyMap()
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
            // THE ONE HITTING THE SPAWN FIRST (v94): it stands still while it swings, so it is always caught, and it is the
            // one taking the match — in the v93 losses to Ranamar his lone M15A3 swung 33-34 times at our spawn while our
            // melee chased his kiting healers two to twenty cells away
            // only a MELEE next to the spawn: it stands to swing; a gun at three kites (the gate's siege6: our melee sent
            // after six ranged hunters around the spawn lost the garrison and left the tower site dead)
            val spawnHitters = if (USE_HOME_STRIKER_FIRST) homeThreats.filter { e -> hasMelee(e) && getRange(e, mySpawn) <= 1 } else emptyList()
            val meleeHomeTarget = if (melee && homeFight) (spawnHitters.minByOrNull { getRange(creep, it) }
                ?: homeThreats.filter { catchable(creep, it) }.minByOrNull { getRange(creep, it) }) else null
            val target: Position
            val standoff: Int
            // A HEALER IN A WAVE WALKS WITH THE WAVE (v84). It carries no weapon, so the first row below sent it home —
            // while the wave kept it as a member (inArms counts heal) and, holding the tower's edge, waited for it as
            // a laggard: 400-480 ticks of standing in the v73 series against marlyman123#114, with the wave's own
            // simulation saying `melee=win`. It follows the most damaged member by share of hits, or the vanguard
            // (nearest the enemy spawn on the assault field) while nobody is hurt; a husk — no weapon AND no heal —
            // still goes home.
            val healer = USE_HEALER_WARD && !hasWeapon(creep) && hasHeal(creep)
            val ward: Creep? = if (healer && marching) {
                val mates = fighters.filter { it.id != creep.id && it.id in wave && inArms(it) }
                mates.filter { it.hits < it.hitsMax }.minByOrNull { it.hits.toDouble() / it.hitsMax }
                    ?: mates.minByOrNull { assaultFlow.getOrNull(it.x * 100 + it.y)?.takeIf { d -> d >= 0 } ?: Int.MAX_VALUE }
            } else null
            // мили (бурильщик) на поводке: враг у дома, стена пролома, пост — и ничего дальше.
            // За целью «на нашей половине» он ушёл на другой край карты и стал турелью (02.09).
            // a member of the next wave waits where the wave is staged (v98): a healer without a ward and a melee striker
            // went home by the two rows below while the wave was staged at a forward spawn, were never "staging", and
            // the wave never left (the gate's tower+fortspawn: 810-1050 `our=0/1009`, the siege 942 -> 1177); the home
            // guard and a husk stay home
            val striker = strikers.any { it.id == creep.id }
            when {
                ward != null -> { target = ward; standoff = 1 }
                !hasWeapon(creep) -> { target = if (striker && hasHeal(creep)) rallySpawn else mySpawn; standoff = HOME_STANDOFF + 1 }
                homeTarget != null && (!marching || melee) && homeFight && (!melee || meleeHomeTarget != null) -> { target = if (melee) meleeHomeTarget!! else homeTarget; standoff = if (melee) 1 else CLOSE_STANDOFF }
                melee && wallTarget != null -> { target = wallTarget; standoff = 1 }
                // поводок — про ПОГОНЮ, а не про осаду: мили, не идущий в волне, остаётся дома, потому
                // что за кайтящей целью он уходил на другой край карты и становился турелью (02.09).
                // Идущий в волне марширует со всеми — чужой спавн не кайтит, и мили взят ради него
                melee && !marching -> { target = if (striker) rallySpawn else mySpawn; standoff = HOME_STANDOFF }
                // враг у дома сильнее гарнизона — пост отрядом, подкрепление копится у спавна
                homeTarget != null && !marching -> { target = mySpawn; standoff = HOME_STANDOFF }
                engage != null -> { target = engage; standoff = if (melee) 1 else closeIn }
                threat != null && huntingThreat && !marching && mobile -> { target = threat; standoff = closeIn }
                stompOf[creep.id] != null && !marching -> { target = stompOf[creep.id]!!; standoff = 0 }
                raider != null && !marching && mobile && (raidTeam == null || creep.id in raidTeam) -> { target = raider; standoff = RANGED_RANGE }
                marching -> { target = enemySpawn!!; standoff = if (melee) 1 else RANGED_RANGE }
                wallTarget != null -> { target = wallTarget; standoff = if (melee) 1 else RANGED_RANGE }
                else -> { target = rallySpawn; standoff = HOME_STANDOFF }
            }
            // к чужому спавну идём полем подхода (по урону), ко всему прочему — обычным
            val flow = if (enemySpawn != null && target.x == enemySpawn.x && target.y == enemySpawn.y) assaultFlow else flowTo(ctx, target)
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
            val mustFlee = (!hasWeapon(creep) && ward == null && nearbyEnemies.isNotEmpty()) ||
                creep.hits < InfluenceMap.netDamageAt(creep.x, creep.y, nearbyEnemies, allies) * 2 ||
                (!sieging && creep.hits <= InfluenceMap.towerBurstAt(creep.x, creep.y)) ||
                (ghost > 0 && creep.hits <= ghost)

            // сплочение: авангард ждёт отставших СВОЕЙ группы (в тиках ИХ хода), пока сам не под огнём —
            // и на марше, и при сближении с врагом: «в бою не ждём» отправляло переднего в размен,
            // пока напарник полз по болоту в пяти клетках (02.09)
            // ONE FIELD FOR THE WHOLE WAVE (v100). Each member measured "behind me" on the field of its OWN target, and
            // a healer's target is its ward: on two fields "behind" is not antisymmetric, the waiting closed into a
            // cycle and nobody was the rearmost — against けろびー#18 (25.09.2026) f30 waited for the healer on the
            // assault field, the healer waited for f30 on its ward's, f34 for both, 187 ticks (t≈400-587) while his
            // spawn sites on our half stood guarded by one builder. Measured on one field a mate is waited for only if it
            // is strictly behind, the rearmost waits for nobody, and the wave converges whatever its make-up.
            // The healer is the one member whose own target is never on the march field; a gun that engages a creep on
            // the way keeps that creep's field (moved onto the march field too, the gate's tower+stream wave walked
            // into the tower without its second wave: 574 -> 1007)
            val cohesionFlow = if (USE_COMMON_COHESION && marching && ward != null && enemySpawn != null) assaultFlow else flow
            val myFlow = cohesionFlow[creep.x * 100 + creep.y]
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
            // A COLUMN IS NOT A LAG (v95). In a corridor one cell wide the wave is a column, the tail cannot close the
            // gap because the cells ahead are held by those waiting for it, and the head waited for ever: 20 bodies at
            // x=1, y=73-87 from t≈1720 to the clock against ricardo, ten at x=98 from 1675 to 1975 against marlyman#142
            // with `sim=…/win/9t/direct`. A mate joined to me by a chain of mates, each within two cells of the next,
            // is queued behind me; only one cut off from that chain is lagging.
            // only on the march: a hunt at home keeps waiting for its laggards (applied to hunts too it sent the gate's
            // siege6 garrison into the hunters piecemeal and left the tower site at 769)
            val linked: Set<String> = if (!USE_COLUMN_COHESION || !marching) emptySet() else {
                val seen = HashSet<String>()
                val queue = ArrayDeque<Creep>()
                queue.add(creep); seen.add(creep.id)
                while (queue.isNotEmpty()) {
                    val c = queue.removeFirst()
                    for (m in mates) if (m.id !in seen && getRange(c, m) <= 2) { seen.add(m.id); queue.add(m) }
                }
                seen
            }
            val hold = (marching || hunting) && !homeFight && !underFire && !inCoverage && !mateFighting && myFlow >= 0 && creep.getRangeTo(target) > standoff && run {
                var lagging = false
                for (m in mates) {
                    if (getRange(creep, m) <= RANGED_RANGE) continue // рядом — не отстал
                    if (m.id in linked) continue // в очереди за мной, а не отстал
                    val d = cohesionFlow[m.x * 100 + m.y]
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
                // A HOLDING FRONT KEEPS THE EDGE OF HIS CREEPS' FIRE TOO, NOT ONLY OF HIS TOWERS (v107). With no tower on the
                // way "hold" was a march: against ●ω<♥♪#2 (t=550) f74 and f100, locally weaker, stepped west into his pair
                // at (56,8) with another seven cells behind them, and both died with nothing of his dead. A holding step
                // may not take more of his fire than the cell it leaves — out of combat only: in it the fight's own score steps
                // (the edge held in combat froze the gate's tower+stream front before his stream: 574 -> 1321)
                marching && siegeHold -> bestSingleMove(creep, target, flow, standoff, localAggressive, inCombat, breaching, enemyCreeps, allies, meleeEnemies, blockedSet, enemyPositions, occupantAt)
                    ?.takeIf { s -> coveringTowers(ctx, listOf(InfluenceMap.cell(s.x, s.y)), 0).isEmpty() &&
                        (!USE_HOLD_FIRE_EDGE || inCombat || InfluenceMap.damageAt(s.x, s.y, combatEnemies) <= InfluenceMap.damageAt(creep.x, creep.y, combatEnemies)) }
                else -> bestSingleMove(creep, target, flow, standoff, localAggressive, inCombat, breaching, enemyCreeps, allies, meleeEnemies, blockedSet, enemyPositions, occupantAt)
            }
            if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
                println("  f${creep.id} (${creep.x},${creep.y}) hits=${creep.hits}/${creep.hitsMax} wave=${wave[creep.id] ?: 0} tgt=(${target.x},${target.y}) flow=$myFlow flee=$mustFlee combat=$inCombat aggr=$localAggressive hold=$hold spd=${plainPeriod(creep)} fatigue=${creep.fatigue} step=${step?.let { "(${it.x},${it.y})" } ?: "stay"}${if (TrafficManager.isStuck(creep.id)) " STUCK" else ""}")
            }
            if (step != null) TrafficManager.request(creep, step, FIGHTER_PRIORITY)
            lastHits[creep.id] = creep.hits
            lastCell[creep.id] = creep.x * 100 + creep.y
        }

        cpuMark("f.creeps")
        prevShooters = combatEnemies.map { val p = InfluenceMap.profileOf(it); Shooter(it.x * 100 + it.y, p.ranged, p.melee) }
        // БАШНЯ — ЦЕЛЬ ПЕРЕД СПАВНОМ, но после крипов: порядок тот же, что в симуляции осады (мягкие
        // вперёд — они и стреляют, и умирают быстрее), и разнобой между стрельбой и симуляцией как раз
        // и давал стенду разброс. Кормленная башня живёт до конца осады и бьёт каждый кулдаун; спавн
        // не стреляет вовсе, поэтому его очередь последняя
        // цена спавна — ВМЕСТЕ с рампартом на нём: пока тот цел, наш огонь по спавну идёт в него, и
        // сравнивать башню надо с настоящей работой (3000 + 10000), а не с голой табличкой спавна
        val enemySpawnHits = (enemySpawn?.hits ?: 0) + spawnRampartHits(ctx)
        val liveTowers = ctx.enemyTowers.filter { it.fed && (it.obj?.hits ?: 0) > 0 }
        // …and a tower's work is its rampart too (v82, see enemyShield): 3000 + 10000 is not cheaper than the spawn
        fun towerWork(t: TowerInfo) = (t.obj?.hits ?: 0) + shieldAt(t.pos)
        val towerTarget = if (stormDirect || liveTowers.sumOf { towerWork(it) } > enemySpawnHits) null
            else liveTowers.minByOrNull { if (it.obj == null) Int.MAX_VALUE else towerWork(it) }?.obj
        healAndShoot(fighters, allies, enemyCreeps, enemySpawn, focusTarget, pushing, wallTarget, towerTarget)
        return ourOffense
    }

    /** Удар мили: фокус-цель вплотную, иначе самый раненый сосед, иначе спавн врага, иначе стена пролома. */
    /** Разбор входящего урона по корзинам (см. поля takenTrade/Husk/Unans). Считается в начале тика:
     *  lastHits снят до стрельбы прошлого тика, значит разница — это урон, полученный ЗА прошлый тик,
     *  и разбирать его надо по обстановке прошлого тика (nearLast). */
    private fun measureIncoming(fighters: List<Creep>, combatEnemies: List<Creep>) {
        for (f in fighters) {
            val prev = lastHits[f.id]
            if (prev != null) {
                val lost = prev - f.hits
                if (lost > 0) {
                    when {
                        nearLast[f.id] != true -> takenUnans += lost
                        hasWeapon(f) -> takenTrade += lost
                        else -> takenHusk += lost
                    }
                }
            }
        }
        nearLast.keys.retainAll { id -> fighters.any { it.id == id } }
        for (f in fighters) nearLast[f.id] = combatEnemies.any { getRange(f, it) <= RANGED_RANGE }
        if (DEBUG_LOG && getTicks() % 200 == 0 && takenTrade + takenHusk + takenUnans > 0) {
            println("taken t=${getTicks()}: trade=$takenTrade husk=$takenHusk unanswerable=$takenUnans")
        }
    }

    private fun strike(creep: Creep, enemyCreeps: List<Creep>, enemySpawn: StructureSpawn?, focusTarget: Creep?, wallTarget: StructureWall?) {
        if (!hasMelee(creep)) return
        val adjacent = enemyCreeps.filter { creep.getRangeTo(it) <= 1 }
        val target: screeps.api.GameObject? = when {
            // the direct storm (v83): swings go into the spawn while everything next to us stands behind a rampart
            (stormDirect || USE_SHIELD_LAST) && enemySpawn != null && creep.getRangeTo(enemySpawn) <= 1 && adjacent.all { shieldAt(it) > 0 } -> enemySpawn
            focusTarget != null && creep.getRangeTo(focusTarget) <= 1 -> focusTarget
            // a defender behind his rampart costs its rampart first (10000) — the last choice, not the weakest (v95:
            // against marlyman123 our melee swung 188 and 302 times at his posts against 64 and 58 at the spawn)
            adjacent.isNotEmpty() -> (if (USE_SHIELD_LAST) adjacent.filter { shieldAt(it) <= 0 }.minByOrNull { it.hits } else null)
                ?: adjacent.minByOrNull { it.hits + shieldAt(it) }
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
            // A BODY WITHOUT A SINGLE MOVE IS NOT ARMY (v84): けろびー's `A3` is carried to his own wall at t≈20 and
            // breaks it there; counted as the first combat birth it started the production clock two hundred ticks
            // before his army, so the rate came out at +170/100t while he grew +393/100t and our first wave left into
            // it (three losses to けろびー#18, 24.09.2026). Never counted, never seen again: it cannot reach us.
            if (USE_LEGLESS_NOT_ARMY && e.body.none { it.type == MOVE }) continue
            if (!enemySeen.add(e.id)) continue
            if (firstCombatSeen < 0) firstCombatSeen = now
            val p = InfluenceMap.profileOf(e)
            val dps = p.ranged + p.melee
            // a healer is born with no damage, and at power 0 his M5H3s did not exist for the estimate; what he adds
            // to a fight is heal against our damage, the same currency in the damage race — so it enters as such
            val strength = dps + (if (USE_HEAL_IN_BIRTHS) p.heal else 0.0)
            enemyBirths.addLast(Birth(now, lanchester(strength, 0.0, e.hitsMax), e.hitsMax, dps, p.melee))
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
        // размен «мы снесём его раньше, чем он нас» действителен ровно тогда, когда его спавн ОДИН:
        // с двумя снос первого не кончает матч, а свой мы уже отдали
        if (ctx.enemySpawns.size > 1) return false
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
    private fun healAndShoot(active: List<Creep>, allies: List<Creep>, enemyCreeps: List<Creep>, enemySpawn: StructureSpawn?, focusTarget: Creep?, stormSpawn: Boolean, wallTarget: StructureWall? = null, towerTarget: StructureTower? = null) {
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
                    shoot(creep, enemyCreeps, enemySpawn, focusTarget, stormSpawn, wallTarget, active, towerTarget)
                    continue
                }
                val farTarget = candidates.filter { it.hitsMax - it.hits > 0 }.maxByOrNull { need(it) }
                if (farTarget != null) {
                    creep.rangedHeal(farTarget)
                    healDone[farTarget.id] = (healDone[farTarget.id] ?: 0) + healParts * RANGED_HEAL_POWER
                    continue
                }
            }
            shoot(creep, enemyCreeps, enemySpawn, focusTarget, stormSpawn, wallTarget, active, towerTarget)
        }
    }

    private fun shoot(creep: Creep, enemyCreeps: List<Creep>, enemySpawn: StructureSpawn?, focusTarget: Creep?, stormSpawn: Boolean, wallTarget: StructureWall? = null, allies: List<Creep> = emptyList(), towerTarget: StructureTower? = null) {
        if (!hasRanged(creep)) return
        val creepsInRange = enemyCreeps.filter { creep.getRangeTo(it) <= RANGED_RANGE }
        val spawnInRange = enemySpawn != null && creep.getRangeTo(enemySpawn) <= RANGED_RANGE
        val towerInRange = towerTarget != null && creep.getRangeTo(towerTarget) <= RANGED_RANGE
        if (creepsInRange.isEmpty() && !spawnInRange && !towerInRange) {
            // стрелять не по кому — добиваем стену пролома, если она в дальности
            if (wallTarget != null && creep.getRangeTo(wallTarget) <= RANGED_RANGE) creep.rangedAttack(wallTarget)
            return
        }

        // массовый выстрел считается по БОЕВЫМ целям (и спавну): чужие хаулеры в упор поднимали
        // massValue выше единицы, и боец бил по площади — 1 урона за часть по стрелку на 3, вместо
        // 10 одиночным. Дуэль M5R5 против M3R3 в их коридоре хаулеров: 17 выстрелов получил, 11 нанёс (матч 6)
        val combatInRange = creepsInRange.filter { c -> val p = InfluenceMap.profileOf(c); p.melee + p.ranged + p.heal > 0.0 }
        // the direct storm (v83): a defender behind his rampart is not in the way, the spawn is the target
        if (stormSpawn && (stormDirect || USE_SHIELD_LAST) && spawnInRange && combatInRange.all { shieldAt(it) > 0 }) {
            creep.rangedAttack(enemySpawn!!)
            return
        }
        val massPool = if (combatInRange.isNotEmpty()) combatInRange else creepsInRange
        var massValue = massPool.sumOf { InfluenceMap.rangedRate(creep.getRangeTo(it)) }
        if (spawnInRange) massValue += InfluenceMap.rangedRate(creep.getRangeTo(enemySpawn!!))

        // штурм: спавн — пока в дальности нет боевых крипов (они стреляют, спавн — нет; симуляция осады
        // считает так же: защитники первыми, затем спавн), носильщики башни огня не отвлекают
        // боевых крипов в дальности нет — башня, потом спавн (тот же порядок, что в симуляции)
        if (combatInRange.isEmpty() && towerInRange) {
            creep.rangedAttack(towerTarget!!)
            return
        }
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
        if (structure != null && getRange(unit, structure) <= 1) return 1.0
        if (opponents.any { getRange(unit, it) <= MELEE_KEEP_RANGE }) return 1.0
        if (!USE_MELEE_SHARE) return meleeSwitch(swampPeriod(unit), opponents)
        return meleeShare(swampPeriod(unit), opponents)
    }

    /** То же, что meleeFactor, для ЕЩЁ НЕ КУПЛЕННОГО тела: позиции у него нет, значит нет и клаузы
     *  «уже вплотную», а болотный период считается по частям тела (гружёных CARRY у бойца не бывает). */
    private fun meleeReach(body: Array<BodyPartType>, opponents: List<Creep>): Double =
        periodOn(body.count { it != MOVE && it != CARRY }, body.count { it == MOVE }, 10).let {
            if (USE_MELEE_SHARE) meleeShare(it, opponents) else meleeSwitch(it, opponents)
        }

    /**
     * THE SHARE A MELEE REACHES, NOT A SWITCH (v84). "Any melee among them → full value" was the rule, on the reasoning
     * that melee meet melee; against Ranamar#2 one fast M15A3 in a group of fast M10R2 and M10H2 (all at one swamp cell a
     * tick, ours at three) made our M12A5 worth its whole 150 in every price — and 3.3-3.9 thousand energy of them stood
     * next to an enemy in 1 % of their ticks and landed nothing. What a melee reaches is: his melee (they come to us
     * to strike, and are struck back), and those not faster than it on swamp, where the difference in speed lives. The
     * factor is that share of his hits at full value and the rest at MELEE_KITE_DISCOUNT; all-melee or all-slower
     * gives 1.0 and all-fast-ranged gives the discount, as before.
     */
    /** The rule before v84 (see meleeShare), kept for USE_MELEE_SHARE = false: any melee among them, or any
     *  ranged slower than ours, gives full value to the whole group; otherwise the kite discount. */
    private fun meleeSwitch(minePeriod: Int, opponents: List<Creep>): Double {
        if (opponents.any { hasMelee(it) }) return 1.0
        val ranged = opponents.filter { hasRanged(it) }
        if (ranged.isEmpty()) return 1.0
        return if (ranged.any { swampPeriod(it) > minePeriod }) 1.0 else MELEE_KITE_DISCOUNT
    }

    private fun meleeShare(minePeriod: Int, opponents: List<Creep>): Double {
        val armed = opponents.filter { hasMelee(it) || hasRanged(it) || hasHeal(it) }
        if (armed.none { hasRanged(it) }) return 1.0
        var reach = 0.0
        var all = 0.0
        for (o in armed) {
            // BY THE DAMAGE IT CAN STOP, NOT BY THE HITS (v99): what a melee takes out of his group is the damage of those
            // it reaches; a healer's hits weighed as much as a gun's, so the healball's fast M4H2 outweighed the slow
            // M3R3 our melee kills — and a healer does no damage to be stopped
            val h = if (USE_SHARE_BY_DAMAGE) InfluenceMap.profileOf(o).let { it.ranged + it.melee } else o.hits.toDouble().coerceAtLeast(1.0)
            all += h
            // a gun of our melee's own speed keeps its distance for ever — only a SLOWER one is reached (as meleeSwitch
            // always said; with ">=" the gate's tower+healball counted our guard full against his M3R3: 521 -> 1400)
            val reached = if (USE_SHARE_BY_DAMAGE) swampPeriod(o) > minePeriod else swampPeriod(o) >= minePeriod
            if (hasMelee(o) || reached) reach += h
        }
        if (all <= 0.0) return 1.0
        val share = reach / all
        return share + (1.0 - share) * MELEE_KITE_DISCOUNT
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


    /**
     * HIS SITES ARE ERASED BY A STEP (v103). A creep of ours stepping onto a construction site of his removes it with
     * everything built into it (seen live: his raider stepped on our tower site at 776/1250 and it was gone). His spawns
     * are built this way from a pile, a hundred ticks each and mostly unguarded — けろびー#18 put up four on our half and
     * middle, marlyman123 two to five a match, and the siege only ever saw a spawn once it stood. For every site not
     * under his fed tower, the free guns nearest to it by their own walk are sent, as few as hold its guards (none
     * guarding: one), if the first of them gets there before the site is done; the rest stay where they were.
     * Returns the site each of them goes to.
     */
    private fun stompJobs(ctx: Ctx, free: List<Creep>, combatEnemies: List<Creep>, centroid: Position): Map<String, Position> {
        val out = HashMap<String, Position>()
        // A STEP ERASES ONLY A SITE OF AN OBSTACLE (v103b). Measured live: his creep on our tower site erased it at
        // once, and our f29 stood on his road site (11,12) from t=574 to 1600 and it stayed, to be built at 1677. His
        // site's kind is not given to us, but a spawn (1000) and a tower (1250) have prices no other site has; and a
        // site that outlives one of ours standing on it is not erasable, whatever it is, and is left alone
        val erasable = setOf(buildCost("StructureSpawn"), buildCost("StructureTower"))
        for ((site, _) in enemySitesNow) if (ctx.myCreeps.any { it.x == site.x && it.y == site.y }) stompFailed.add(site.id)
        stompFailed.retainAll { id -> enemySitesNow.any { it.first.id == id } }
        if (free.isEmpty()) return out
        for ((site, doneIn) in enemySitesNow.sortedBy { getRange(it.first, centroid) }) {
            if ((site.progressTotal ?: 0) !in erasable || site.id in stompFailed) continue
            if (coveringTowers(ctx, listOf(site), 0).isNotEmpty()) continue
            val guards = combatEnemies.filter { getRange(it, site) <= ENGAGE_RANGE + RANGED_RANGE }
            val field = flowTo(ctx, site)
            val cands = free.filter { it.id !in out }.map { it to pathTicks(it, field, it.x * 100 + it.y) }
                .filter { it.second < Int.MAX_VALUE / 4 && it.second < doneIn }.sortedBy { it.second }
            val team = ArrayList<Creep>()
            var holds = false
            for ((c, _) in cands) {
                team.add(c)
                holds = guards.isEmpty() || ourPowerOf(team, guards) >= enemyPowerOf(guards, team) * PUSH_RATIO
                if (holds) break
            }
            if (!holds) continue
            for (c in team) out[c.id] = site
            stompReach++
        }
        if (DEBUG_LOG && getTicks() % LOG_EVERY == 0 && enemySitesNow.isNotEmpty()) {
            println("stomp t=${getTicks()}: " + enemySitesNow.joinToString(" ") { (s, d) ->
                "(${s.x},${s.y})${s.progress}/${s.progressTotal}@${if (d >= Int.MAX_VALUE / 4) "-" else d.toString()}" +
                    "=${out.filterValues { it === s }.keys.joinToString(",") { "f$it" }.ifEmpty { "-" }}"
            } + " jobs=$stompReach")
        }
        return out
    }

    /** The defence deficit against his guns that no melee of ours can reach — faster or as fast on swamp as every one
     *  of them — counted against our guns (and towers) only (v101). No such guns: no deficit of this class. */
    private fun kiteDeficit(defenders: List<Creep>, threats: List<Creep>): Double {
        val meleePeriods = defenders.filter { hasMelee(it) }.map { swampPeriod(it) }
        val kiters = threats.filter { e -> hasRanged(e) && !hasMelee(e) && meleePeriods.none { swampPeriod(e) > it } }
        if (kiters.isEmpty()) return Double.NEGATIVE_INFINITY
        val guns = defenders.filter { hasRanged(it) || hasHeal(it) }
        return enemyPowerOf(kiters, guns) * DEFEND_MARGIN - ourPowerOf(guns, kiters)
    }

    /**
     * WHEN THE HOUSE FALLS (v101): to the first pack of his that our armed creeps do not hold — a pack being everyone
     * arrived by then, in the order they arrive (one standing in the alarm ring counts as arriving within it) — at that
     * pack's own damage; never while every pack is held. v97 took the arrival of the nearest and the damage of all:
     * against けろびー#19 a loitering M5A1 (held 464:134 by our garrison) and his army ninety cells off, standing, read
     * "enemy arrives in 46-52" for 500 ticks, and "fighter first" bought no hauler while his raider killed all five.
     */
    private fun houseFallsIn(ctx: Ctx, defenders: List<Creep>, threats: List<Creep>, houseHits: Int): Int {
        val never = Int.MAX_VALUE / 2
        val eta = threats.mapNotNull { e ->
            var a = arrivalById[e.id] ?: never
            if (ctx.enemyApproach[e.x * 100 + e.y] in 0..SPAWN_ALARM_TICKS) a = minOf(a, SPAWN_ALARM_TICKS)
            if (a >= never) null else e to a
        }.sortedBy { it.second }
        val pack = ArrayList<Creep>()
        var i = 0
        while (i < eta.size) {
            val t = eta[i].second
            while (i < eta.size && eta[i].second == t) { pack.add(eta[i].first); i++ }
            val dps = pack.sumOf { val p = InfluenceMap.profileOf(it); p.ranged + p.melee }
            if (dps <= 0.0) continue
            val held = ourPowerOf(defenders, pack) >= enemyPowerOf(pack, defenders) * DEFEND_MARGIN &&
                kiteDeficit(defenders, pack) <= 0.0
            if (held) continue
            return t + (houseHits / dps).toInt()
        }
        return never
    }

    /**
     * HIS RAMPART OVER A SPAWN WHEN OUR SIEGE OF IT WOULD BE OVER (v108). The siege priced a spawn by the rampart on it
     * now: Ranamar#2's builder put one over his bare spawn at t=498-537, and every siege planned against "3000 hits"
     * before that met 13000. A rampart's site on the spawn's cell (no other site of that price can stand there) done
     * before we arrive and knock the bare spawn down is priced as the rampart it will be.
     */
    private fun rampartBy(p: Position, arrival: Int, dps: Double): Int {
        val now = shieldAt(p)
        if (now > 0) return now
        val site = enemySitesNow.firstOrNull { (s, _) -> s.x == p.x && s.y == p.y && (s.progressTotal ?: 0) == buildCost("StructureRampart") } ?: return 0
        val bareFalls = if (dps > 0.0) (SPAWN_HITS / dps).toLong() else Long.MAX_VALUE / 4
        return if (site.second.toLong() <= arrival.toLong() + bareFalls) RAMPART_HITS else 0
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
        // the body is the same at every step: its two periods once per walk, not a body count per cell (v78, CPU —
        // this walk runs for every fighter and every enemy several times a tick, up to 400 cells each)
        val weight = bodyWeight(creep)
        val moves = liveMoves(creep)
        val onPlain = periodOn(weight, moves, 2)
        val onSwamp = periodOn(weight, moves, 10)
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
            ticks += if (DistanceMap.isSwamp(cell / 100, cell % 100)) onSwamp else onPlain
        }
        return ticks
    }

    /** Тики хода ГРУППЫ до цели по полю потока — по САМОМУ дальнему её бойцу: группа идёт вместе, и
     *  осада начинается, когда дошёл последний. Пустая группа или пустое поле — «никогда». */
    /** Ход группы по маршруту в ТИКАХ: каждый своим телом по клеткам поля подхода, берём худшего.
     *  `plain` — запасной ответ, когда маршрута нет (поле пусто или цель недостижима). */
    private fun travelTicksOf(group: List<Creep>, route: IntArray, plain: IntArray): Int {
        if (group.isEmpty() || route.isEmpty()) return travelOf(group, plain)
        var worst = 0
        for (c in group) {
            val t = pathTicks(c, route, c.x * 100 + c.y)
            if (t >= Int.MAX_VALUE / 4) return travelOf(group, plain)
            if (t > worst) worst = t
        }
        return worst
    }

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

    private fun catchable(unit: Creep, target: Creep): Boolean {
        // v94: "a melee" and "within two" held for any speed, and Ranamar's M10H2 and M10R2 (a swamp cell a tick against
        // our three) kept two cells off our melee for whole matches — 0-1 % of their ticks adjacent — while his M15A3
        // took our spawn. Being near or being melee catches him only if he is not faster than us on swamp
        val notFaster = swampPeriod(target) >= swampPeriod(unit)
        val near = getRange(unit, target) <= MELEE_KEEP_RANGE
        return if (USE_HOME_STRIKER_FIRST)
            (hasMelee(target) && notFaster) || (near && notFaster) || !canMove(target) || swampPeriod(target) > swampPeriod(unit) || !retreating(unit, target)
        else hasMelee(target) || near || !canMove(target) || swampPeriod(target) > swampPeriod(unit) || !retreating(unit, target)
    }

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
            // into ANY of our spawns (v85): a hauler hands over at the nearest one (dropOff), and counted at home only,
            // the forward spawn (2,97) of 0c6b11 took 3405 while the log read real=6 — and v64's guard on fleet growth
            // read that as delivery falling
            if (was != null && was > store && ctx.mySpawns.any { getRange(h, it) <= 1 }) sum += was - store
            haulerStore[h.id] = store
        }
        haulerStore.keys.retainAll(ctx.haulers.mapTo(HashSet()) { it.id })
        if (firstHaulerTick < 0 && ctx.haulers.isNotEmpty()) firstHaulerTick = now
        foeSpawnPeak = maxOf(foeSpawnPeak, ctx.enemySpawns.size)
        if (sum > 0) delivered.addLast(now to sum)
        while (delivered.isNotEmpty() && delivered.first().first < now - PRODUCTION_WINDOW) delivered.removeFirst()
    }

    /** Сколько энергии ПОЯВИЛОСЬ на карте в этот тик: новые точки со своим начальным запасом. Это приток
     *  предложения, а не остаток на земле, и именно он говорит, есть ли смысл в ещё одной ёмкости. */
    private fun measureSupply(ctx: Ctx) {
        val now = getTicks()
        var fresh = 0
        for (s in ctx.sites) {
            if (siteFirstSeen.containsKey(s.id)) continue
            siteFirstSeen[s.id] = s.energy
            // ПОЯВИЛОСЬ — НЕ ЗНАЧИТ ДОСТАЛОСЬ. Карта роняет по 2000 каждые полсотни тиков, и сумма
            // «появилось в тик» выходит 80-97 при нашей сдаче в 14. Ровно это число и держало ворота
            // хаулера открытыми: supplyBound = «предложение больше того, что мы берём» отвечало «да»
            // всегда, потому что считало и те кучи, до которых не успеть до распада. В матче 6a9ffb75
            // флот дорос до одиннадцати при ДВУХ бойцах, а замеренная сдача упала с 14-15 до 3-5.
            // Куча, до которой пустой хаулер не доедет за её же время жизни, — не предложение
            val life = s.ticksToDecay
            if (life != null && ctx.haulSteps[s.pos.x * 100 + s.pos.y].let { it < 0 || it > life }) continue
            fresh += s.energy
        }
        if (fresh > 0) appeared.addLast(now to fresh)
        while (appeared.isNotEmpty() && appeared.first().first < now - PRODUCTION_WINDOW) appeared.removeFirst()
    }

    /** Появление энергии в тик за окно (см. measureSupply). */
    private fun supplyRate(): Double {
        val span = minOf(PRODUCTION_WINDOW, getTicks() + 1)
        return if (span <= 0) 0.0 else appeared.sumOf { it.second }.toDouble() / span
    }

    /**
     * Замеренный приток (энергии в тик); -1, пока флот не проработал [minSpan] тиков и мерить нечего.
     * ЦЕЛОЕ ОКНО — НЕ УСЛОВИЕ ИЗМЕРЕНИЯ, А ЕГО ТОЧНОСТЬ. Делится всегда на прожитый пролёт, поэтому
     * ответ на половине окна — такой же замер, только шумнее; требование полных трёхсот было чистотой
     * ради чистоты и стоило вопросу о точке сдачи полутора сотен тиков. Решениям, которые от шума
     * ломаются (рост флота), окно по-прежнему нужно целиком — они и спрашивают по умолчанию.
     */
    private fun realisedIncome(minSpan: Int = PRODUCTION_WINDOW): Double {
        val now = getTicks()
        if (firstHaulerTick < 0) return -1.0
        val span = minOf(PRODUCTION_WINDOW, now - firstHaulerTick)
        if (span < minSpan) return -1.0
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

    /** Клетка ПРОБЫ расстояния: строго дальше спорного радиуса и не дальше, чем нужно, чтобы это
     *  доказать, — проходимая, свободная, на нашей половине и подальше от врага. Ближе радиуса ставить
     *  бессмысленно: там ответ одинаков при обоих чтениях доков. */
    private fun extensionProbeSpot(ctx: Ctx): Position? {
        val spawn = ctx.mySpawn
        val enemy: Position = ctx.enemySpawn ?: spawn
        val busy = ctx.blocked.mapTo(HashSet()) { it.x * 100 + it.y }
        var best: Position? = null
        var bestScore = -1
        val lo = if (EXT_PROBE_FAR) EXTENSION_REACH + 1 else 2
        val hi = if (EXT_PROBE_FAR) EXTENSION_REACH + 5 else 3
        for (dx in -hi..hi) for (dy in -hi..hi) {
            val ring = maxOf(abs(dx), abs(dy))
            if (ring < lo || ring > hi) continue
            val x = spawn.x + dx
            val y = spawn.y + dy
            if (x < 1 || y < 1 || x > 98 || y > 98) continue
            val pos = InfluenceMap.cell(x, y)
            if (getTerrainAt(pos) == TERRAIN_WALL) continue
            if (x * 100 + y in busy) continue
            if (ctx.stepsToSpawn[x * 100 + y] < 0) continue // недостижимо — смотритель туда не дойдёт
            val score = getRange(pos, enemy)
            if (score > bestScore) { bestScore = score; best = pos }
        }
        return best
    }

    /** Сколько WORK у смотрителя. Время до готовой башни — накопление её цены по потоку плюс стройка
     *  (BUILD_POWER за WORK в тик); лишняя WORK ускоряет вторую половину и удлиняет первую. Минимум
     *  суммы: k = √(цена × поток / (BUILD_POWER × цена WORK)) — из потока, а не назначено. */
    private fun builderWork(flow: Double, walk: Boolean = false): Int {
        val k = sqrt(buildCost("StructureTower") * maxOf(flow, 0.5) / (BUILD_POWER * cost(WORK)))
        // ТЕЛО ОБЯЗАНО ПОМЕЩАТЬСЯ В СПАВН. Потолок спавна — SPAWN_ENERGY_CAPACITY, и смотритель дороже
        // него не строится НИКОГДА, а правило «копим на смотрителя» при этом возвращает управление
        // каждый тик и не даёт построить ничего другого: 176 тиков подряд «saving for builder
        // cost=1200 energy=1000» и два бойца за весь матч. Формула считает ОПТИМУМ, а не то, что можно
        // купить, и её надо обрезать кошельком — при нынешнем притоке она даёт пять WORK, но приток
        // считается прогнозом и всплеск делает тело неоплатным
        // с ногами каждый WORK тянет за собой свой MOVE, и кошелёк считает пару
        val affordable = if (walk) (SPAWN_ENERGY_CAPACITY - 2 * cost(CARRY) - 2 * cost(MOVE)) / (cost(WORK) + cost(MOVE))
            else (SPAWN_ENERGY_CAPACITY - 2 * cost(MOVE) - 2 * cost(CARRY)) / cost(WORK)
        return k.toInt().coerceIn(1, minOf((MAX_CREEP_SIZE - 4) / 2, affordable))
    }

    /** Тело смотрителя [MOVE×2, CARRY×2, WORK×k]: WORK в хвосте — урон снимает части спереди, и
     *  разоружённый смотритель ещё возит выстрелы в башню; двух MOVE хватает на три клетки у ворот,
     *  двух CARRY — на десять выстрелов без возврата к спавну. */
    private fun builderBody(k: Int, walk: Boolean = false): Array<BodyPartType> {
        val move = if (walk) 2 + k else 2
        val body = ArrayList<BodyPartType>(2 + move + k)
        repeat(move) { body.add(MOVE) }
        repeat(2) { body.add(CARRY) }
        repeat(k) { body.add(WORK) }
        return body.toTypedArray()
    }

    /**
     * НУЖНЫ ЛИ СМОТРИТЕЛЮ НОГИ ПОД ЭТУ РАБОТУ. Тело [MOVE×2, CARRY×2, WORK×k] задумано стоять у
     * домашней башни в трёх клетках, и там оно право: лишний MOVE там ничего не ускоряет, а WORK
     * ускоряет. На карте оно не ходит ВОВСЕ. Семь не-MOVE частей на два MOVE — это 3.5 тика на клетку
     * по равнине и 17.5 по болоту; замерено на стенде (twospawn): смотритель вышел к площадке в
     * пятидесяти шагах на 250-м тике и к 960-му дошёл до половины пути, а срок готовности тем временем
     * поднялся с 60 до 1020 при падающем остатке матча, они пересеклись — и площадка, простояв 0/1000
     * весь матч, умерла вместе с работой. Это и есть причина, по которой ни одна попытка точки сдачи
     * не показала пользы: её ставили, но некому было дойти.
     * Правило считается, а не назначается: ноги нужны, когда ПОХОД без них дольше самой стройки.
     * Усталость за шаг — два на не-MOVE часть, восстановление — два на MOVE, отсюда тики на клетку.
     * По равнине (болото впятеро хуже, то есть оценка заведомо осторожная).
     */
    private fun keeperWalks(ctx: Ctx, at: Position?, left: Int, flow: Double): Boolean {
        val pos = at ?: return false
        val steps = ctx.stepsToSpawn[pos.x * 100 + pos.y]
        if (steps <= 0) return false
        val k = builderWork(flow)
        val perStep = 2.0 * (2 + k) / (2 * 2)
        return steps * perStep > left.coerceAtLeast(1).toDouble() / (k * BUILD_POWER)
    }

    /** Тело смотрителя под конкретную работу: с ногами или без (см. keeperWalks). */
    private fun keeperBody(ctx: Ctx, at: Position?, left: Int, flow: Double): Array<BodyPartType> {
        val walk = keeperWalks(ctx, at, left, flow)
        return builderBody(builderWork(flow, walk), walk)
    }

    /** Доля вложенного в бойцов, которая ЖИВА: цена уцелевших частей всех живых бойцов к потраченному
     *  на бойцов. Боец — расходник, и это единственная разница между ним и башней, которую снимок
     *  «урон×хиты» не видит вовсе: за матч 25 мы вложили в бойцов 6780 и к 690-му тику держали пятерых.
     *  Пока никого не потеряли — единица, и башня честно проигрывает бойцу. */
    private fun survivalOfFighters(fighters: List<Creep>): Double {
        if (spentFighters <= 0) return 1.0
        return (fighters.sumOf { liveCost(it) }.toDouble() / spentFighters).coerceIn(0.0, 1.0)
    }

    /** Хиты рампарта НА клетке чужого спавна. Пока он цел, весь урон по спавну достаётся ему, поэтому
     *  работа по спавну — это его хиты ПЛЮС эти. Считалось в одном месте (симуляция осады) и не
     *  считалось в двух других (выбор цели в стрельбе и то же правило внутри симуляции), из-за чего бот
     *  спорил сам с собой при укреплённом спавне. Соперники ставят рампарт на спавн в девяти матчах из
     *  десяти (07.09.2026). */
    private fun spawnRampartHits(ctx: Ctx): Int {
        val s = ctx.enemySpawn ?: return 0
        return ctx.ramparts.filter { it.my != true && it.x == s.x && it.y == s.y }.sumOf { it.hits ?: 0 }
    }

    /** Цена УЦЕЛЕВШИХ частей крипа: что из вложенного в него ещё существует. Выбитая часть не стоит
     *  ничего — ни как урон, ни как хиты, — и в сравнении сил считать её нельзя. */
    private fun liveCost(creep: Creep): Int = creep.body.sumOf { if (it.hits > 0) cost(it.type) else 0 }

    /** Через сколько тиков у нас будет ГОТОВАЯ башня: довезти остаток её цены при нынешнем потоке и
     *  превратить его в прогресс руками смотрителя — срок задаёт та половина, что медленнее. Пока
     *  смотрителя нет, в срок входят и его цена, и его рождение. Как только он работает и прошло
     *  полокна, вопрос перестаёт быть модельным: темп площадки ВИДЕН, и берётся он — тем же прибором,
     *  которым бот меряет ЧУЖУЮ площадку (siteSeen/pendingTowers), потому что своя ничем не отличается.
     *  Стоящая площадка не «строится долго», а не достроится никогда. */
    private fun siteReadyTicks(ctx: Ctx, site: ConstructionSite?, kind: String, left: Int, fromSpawn: Boolean, flow: Double, energy: Int, supply: Position? = null, at: Position? = null): Double {
        val siteLeft = if (site == null) buildCost(kind) else left
        if (siteLeft <= 0) return 0.0
        val work = ctx.builders.sumOf { b -> b.body.count { it.type == WORK && it.hits > 0 } }
        if (site != null && work > 0) {
            val progress = site.progress ?: 0
            // ЧАСЫ СКОРОСТИ ИДУТ ТОЛЬКО ТОГДА, КОГДА СМОТРИТЕЛЬ У ПЛОЩАДКИ. Прежде отсчёт шёл от
            // появления площадки, и в замер попадал ПОХОД: у домашней башни это три клетки и ничего не
            // меняло, а у площадки в полусотне шагов выходило 40 прогресса на 100 тиков = 0.4/тик, срок
            // 2400 при остатке матча 1700, вердикт «не успеть» — и смотритель бросал стройку, которую
            // сам же начал. Просто «считать с первого прогресса» — не ответ: тогда площадка, у которой
            // смотритель СТОИТ, а энергии нет, перестаёт выглядеть мёртвой, и фикстура осады сразу
            // ловит это (siege6: 420 утопленных против 10). Различает эти два случая не прогресс и не
            // календарь, а присутствие рук: тики похода не считаются, тики простоя У ПЛОЩАДКИ считаются
            val (worked, p0) = siteWork[site.id] ?: (0 to progress)
            val seen = worked
            if (seen >= APPROACH_WINDOW / 2) {
                val observed = (progress - p0).toDouble() / seen
                // ноль — это ещё не «никогда»: смотритель мог не дойти, а спавн стоять пустым. Замер
                // отвечает, только когда он что-то видел; про причину простоя говорит поток, и на него
                // отвечает модель ниже. Своя первая проба этого правила молча замораживала площадку
                // через десять тиков после покупки смотрителя — тот ещё шёл (стенд siege: 0/1250)
                if (observed > 0.0) return siteLeft / observed
            }
        }
        val keeper = keeperBody(ctx, at, siteLeft, flow)
        val hasKeeper = ctx.builders.isNotEmpty()
        val need = siteLeft + (if (hasKeeper) 0 else keeper.sumOf { cost(it) })
        val rate = (if (work > 0) work else builderWork(flow)) * BUILD_POWER.toDouble()
        val born = if (hasKeeper) 0 else keeper.size * CREEP_SPAWN_TIME
        // поток здесь — ЗАМЕРЕННЫЙ (realisedIncome, среднее за окно производства), а не прогноз этого
        // тика: вопрос «довезут ли остаток» — про устойчивую скорость, а не про рябь. С прогнозом
        // вердикт переключался на каждом провале, смотритель то бросал площадку, то возвращался, и
        // осада выигрывалась на 270 тиков позже (стенд siege: 1631 против 1361)
        val steady = realisedIncome().let { if (it < 0.0) flow else it + regenRate() }
        // ПЛОЩАДКА, КОТОРУЮ КОРМЯТ НЕ ИЗ СПАВНА, НЕ ЖДЁТ ПРИТОКА. Энергия для неё уже лежит рядом, и
        // срок задаёт только скорость рук смотрителя; ждать доставки в спавн — считать чужой счёт
        val supplied = if (fromSpawn) energyArrivalTicks(ctx, maxOf(0, need - energy), steady) else 0.0
        // ПАРОМ. Площадку ограничивает не только приток, но и НОГИ смотрителя: он несёт два CARRY за
        // рейс, значит тысяча — это десять рейсов, а рейс — дорога туда и обратно. Прежняя строка
        // считала это бесплатным по времени, и площадка в полусотне шагов от склада оценивалась в 80
        // тиков вместо 1187. У домашней башни склад в двух клетках и паром почти нулевой — потому
        // прежняя формула на ней и не врала
        val ferry = if (supply == null) 0.0 else {
            val carry = (ctx.builders.maxOfOrNull { b -> b.body.count { it.type == CARRY && it.hits > 0 } }
                ?: builderBody(builderWork(flow)).count { it == CARRY }) * CARRY_CAPACITY
            val here = at ?: site?.let { InfluenceMap.cell(it.x, it.y) }
            val hop = if (here == null) 0 else stepsFrom(cellSteps(ctx, supply.x * 100 + supply.y), here).coerceAtLeast(0)
            if (carry <= 0) Double.MAX_VALUE / 4 else ceil(siteLeft.toDouble() / carry) * (2.0 * hop + 2.0)
        }
        return maxOf(supplied, siteLeft / rate + ferry) + born
    }

    /** Окупается ли башня против бойца за ту же энергию. Мера одна и та же — ПРИБАВКА к мощи обороны
     *  против тех же врагов, с их лечением (см. lanchester): против пары «стрелок + лекарь» непрерывный
     *  урон бойца съедается лечением, а выстрел башни — 1000 разом — нет, и это видно только если
     *  считать против реального врага, а не против абстрактного тела.
     *  Башня работает лишь дома, поэтому её урон и хиты умножены на замеренную долю боя дома (homeShare);
     *  бойцу — скидка на смертность (survivalOfFighters): купленный боец гибнет, поставленная башня стоит.
     *  Цена башни — вместе со смотрителем: без него площадку некому строить, а готовая башня молчит. */
    private fun towerWorth(defenders: List<Creep>, threats: List<Creep>, flow: Double, left: Int = -1, trace: StringBuilder? = null): Boolean {
        val share = homeShare()
        if (share <= 0.0 || threats.isEmpty()) return false
        val heal = threats.sumOf { InfluenceMap.profileOf(it).heal }
        val dps = defenders.sumOf { effectiveDps(it, threats, null) } + ourTowerDps(threats)
        val hits = defenders.sumOf { weightedHits(it, threats, null) }
        val base = lanchester(dps, heal, hits.toInt())
        // враг бьёт спавн с трёх клеток, башня стоит во втором кольце — худший случай по дальности
        val towerDps = InfluenceMap.towerShot(TOWER_RING + RANGED_RANGE) / InfluenceMap.towerCooldown
        // цена — ОСТАТОК, а не всё вложенное: после 800 из 1250 башня стоит 450, и с бойцом сравнивать
        // надо именно их. Вложенное уже потрачено и выбор больше не определяет
        val towerPrice = (if (left >= 0) left else buildCost("StructureTower")) +
            (if (left >= 0) 0 else builderBody(builderWork(flow)).sumOf { cost(it) })
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

    // ДОРОГИ — ЗАМЕРЕНО И ОТВЕРГНУТО (06.09.2026). Дорога делает гружёный шаг по болоту из пяти тиков
    // одним за 50 энергии, и приток упирался именно в гружёное плечо, так что счёт выглядел заманчиво.
    // Проба положила одну дорогу на САМОЙ топтаной клетке болота и записала её в лог: за матч эта клетка
    // набрала ШЕСТЬ тиков гружёного хаулера, то есть экономия 24 тика за 50 энергии. Износ ни при чём —
    // его не было вовсе (2500/2500 за 1150 тиков; ROAD_HITS в рантайме 2500, а не 500 из typings).
    // Причина в том, что трафик не концентрируется: поле пути и так обходит болото по цене ×5, а точки
    // появляются в случайных местах карты, и общего коридора не возникает. Мостить нечего.

    /** Смотритель: пока есть площадка — возит в неё энергию из спавна и строит, башня готова — держит
     *  в ней выстрел. Он безоружен и от огня уходит, как хаулер, продолжая работать на ходу (стройка и
     *  передача — интенты, шагу они не мешают). Энергию спавна берёт, только когда она не нужна бойцу
     *  прямо сейчас: под «бойцом первым» тысяча в спавне принадлежит бойцу. */
    // ==================== кучи: спавн из выгруженного контейнера (v88) ====================

    /**
     * A FORWARD SPAWN FROM A DUMPED CONTAINER (v88). How the top of the field builds its economy — the operator's
     * observation of 24.09.2026, confirmed by every replay of the v73 series: a builder walks to a fresh temporary
     * container, empties it onto the ground next to itself (≈25·CARRY−5 a tick: 2000 in 20-44 ticks) and builds a
     * spawn from the pile — a hundred acts of 10 with two WORK — then carries what is left into the new spawn.
     * けろびー#18 put up eleven that way in three games, ricardo five, marlyman123 a cluster that took 29 thousand.
     * A temporary container lives 99 ticks and a pile ~1300, so the spawn is paid by energy that would otherwise rot:
     * to the home economy the whole thing costs one builder (600) per match. All five of our delivery-point attempts
     * (v49-v70) failed on exactly "a supply that does not rot in 99 ticks"; this is that supply.
     * Which container (pileCandidate): a live temporary one on our side, safe, not being hauled by our fleet, that the
     * builder reaches and empties before it rots and that no armed creep of his can reach before the work is done.
     */
    private const val USE_PILE_SPAWN = true
    /**
     * A RAMPART ON EVERY SPAWN OF OURS (v94). 200 energy for 10000 hits over the cell — the cheapest hit points in the
     * game, and nothing repairs them on either side. marlyman123, ricardo and Ranamar all put one on their spawn by
     * t≈206-556; we had none, and in all three v93 losses to Ranamar a single M15A3 took our 3000 in 33-34 swings
     * (90 each): with a rampart that is 145 ticks instead of 34. The pile builder does it first — the home spawn, then
     * each of ours that is bare and not under fire — with energy withdrawn from that spawn (a forward one holds the
     * ~700 of its pile).
     */
    // OFF for now (25.09.2026): on the gate it alone loses `fortress` (won at 1371 without it) — the builder, held on
    // the ramparts of each new spawn, stops turning piles into spawns; to be fixed before it goes on
    private const val USE_SPAWN_RAMPART = false
    /** At home our melee goes first for whoever hits the spawn, and "near" or "melee" is caught only if not faster (v94). */
    private const val USE_HOME_STRIKER_FIRST = true
    /** A mate chained to me through mates within two cells is queued, not lagging (cohesion hold, v95). */
    private const val USE_COLUMN_COHESION = true
    /** "Fighter first" weighs the threat by when the house falls — arrival plus the spawn's hits at their damage (v97). */
    private const val USE_THREAT_KILL_TIME = true
    /** A wave is recalled for home only if it gets back before the house falls (posture, v97). */
    private const val USE_RECALL_IF_SAVES = true
    /** A marching healer measures its laggards on the assault field, as the guns do, not on its ward's (runFighters, v100). */
    private const val USE_COMMON_COHESION = true
    /** The spawn's threats are those walking at our house, in its alarm ring or being born, not his home guard; the
     *  deficit is the larger of all against all and his kiters against our guns (tick, spawnIfNeeded, v101). */
    private const val USE_HOME_BOUND = true
    /** "Fighter first" takes the house's fall from the first pack of his our garrison does not hold (houseFallsIn, v101). */
    private const val USE_PACK_FALL = true
    /** The last call goes whenever the house cannot fall in what is left, or the siege wins before it falls (v102). */
    private const val USE_LAST_CALL_RACE = true
    /** While a wave is out, the recall asks whether everyone not in it holds the house, those staged included (v102). */
    private const val USE_STAGING_GUARDS = true
    /** Free guns are sent to step on his construction sites, as many as hold their guards (stompJobs, v103). */
    private const val USE_STOMP = true
    /** His construction sites of this tick with the ticks until each becomes a structure (tick, stompJobs). */
    private var enemySitesNow: List<Pair<ConstructionSite, Int>> = emptyList()
    /** Site-ticks a stomp team was sent (stompJobs), for the log. */
    private var stompReach = 0
    /** His sites one of ours stood on without erasing them (stompJobs, v103b). */
    private val stompFailed = HashSet<String>()
    /** A soft target on our half is chased by a team that can catch and kill it, not by every free gun (runFighters, v105). */
    private const val USE_RAID_TEAM = true
    /** A holding wave's step takes no more of his creeps' fire than the cell it leaves (runFighters, v107).
     *  Built with it and rejected: calling the wave back whenever what of his reaches the front before our help is
     *  stronger than it — the gate's ball 444 -> 1332: his ball stays home as the spawn's guard, and the front waiting
     *  outside its reach for the next wave was right. */
    private const val USE_HOLD_FIRE_EDGE = true
    /** His guns shooting our spawn that our defenders do not hold make the next body a fighter, whatever investing promises (v107). */
    private const val USE_ARM_AT_DOOR = true
    /** A siege prices his spawn with the rampart his builder finishes over it before the siege would end (rampartBy, v108). */
    private const val USE_PENDING_RAMPART = true
    /** The target is the spawn of his this army takes soonest by a siege run, held only while it can be taken
     *  (runFighters scores, tick chooses, v104). */
    private const val USE_TARGET_BY_TAKE = true
    /** His spawn id to the group's walk plus siege ticks for it, or Long.MAX_VALUE when the siege is lost (v104). */
    private val targetCost = HashMap<String, Long>()
    /** Waves are staged and idle guns posted at our spawn nearest the target, not at home (runFighters, v98). */
    private const val USE_RALLY_FORWARD = true
    /** A site's deadline takes the home spawn's life from the hits it lost over the production window too (v96). */
    private const val USE_SPAWN_TREND = false   // measured on siege6 25.09.2026: the spawn loses hits only at the end, the site was fed while the GARRISON died — the clock needs the garrison, not the spawn
    /** The home spawn's hits over the production window (tick to hits), for homeLifeByTrend. */
    private val homeHits = ArrayDeque<Pair<Int, Int>>()

    /** How long the home spawn lives at the rate it has actually been losing hits over the window; infinite while it
     *  has lost none (a rampart over it takes the blows and its hits stay). */
    private fun homeLifeByTrend(ctx: Ctx): Double {
        val first = homeHits.firstOrNull() ?: return Double.MAX_VALUE
        val now = ctx.mySpawn.hits ?: SPAWN_HITS
        val lost = first.second - now
        val span = getTicks() - first.first
        if (lost <= 0 || span <= 0) return Double.MAX_VALUE
        return now / (lost.toDouble() / span)
    }
    /** A defender behind his rampart is the last thing our guns and swings pick, after the spawn (strike, shoot, v95). */
    private const val USE_SHIELD_LAST = true

    /** Our first spawn without a rampart of ours on its cell and without fire on it; null — all covered. */
    private fun bareSpawn(ctx: Ctx): StructureSpawn? = if (!USE_SPAWN_RAMPART) null else ctx.mySpawns.firstOrNull { sp ->
        ctx.ramparts.none { it.exists && it.my == true && it.x == sp.x && it.y == sp.y } &&
            InfluenceMap.damageAt(sp.x, sp.y, ctx.combatEnemies) <= 0.0
    }

    /** The pile builder's job outranks the haulers sent to its container (pileCandidate, v91). */
    private const val USE_PILE_RIGHT_OF_WAY = true
    /** A job is refused if any armed creep of his could reach the container before the work is done.
     *  OFF — measured live 25.09.2026 (v91, four games against marlyman123#96): with Chebyshev distance at his plain
     *  pace against ~130 ticks of work every armed creep on most of the map "reached" it, and the builder, waiting at
     *  (35,47), refused fresh containers at (47,47) and (44,37) 328-1103 times a game: not one job in four games. The
     *  honest test would need his real path to the cell; what is known is kept — the cell is safe now (site.safe), and
     *  a builder under fire runs and drops the job. */
    private const val USE_PILE_THREAT_ETA = false
    private class PileJob(val containerId: String, val c: Position, val p: Position, val s: Position)
    private var pileJob: PileJob? = null
    private val pileBuilderIds = HashSet<String>()
    private var pileOrderedAt = -1
    /** Why the waiting builder found no job, per reason, over the match (printed as `reach pile`, v90). */
    private val pileWhy = LinkedHashMap<String, Int>()
    /** MOVE in front (damage takes the legs before the trade), 2 WORK for 10 a tick: M4C4W2, 600. Empty it weighs its
     *  WORK only — a cell a tick on plain, three on swamp. */
    private val PILE_BODY: Array<BodyPartType> = arrayOf(MOVE, MOVE, MOVE, MOVE, CARRY, CARRY, CARRY, CARRY, WORK, WORK)

    /** BY BODY, NOT BY ID (v89). The id taken from spawnCreep's result matched on the stub and never live: in the
     *  six v88 games the builder was bought 5-13 times a match and run by runBuilders as a tower keeper, and no pile
     *  site was ever placed. Nothing else of ours carries WORK with three or more CARRY (the keeper has two, a
     *  hauler no WORK), so the body is the identity; the id set only covers the tick of the order. */
    private fun isPileBuilder(c: Creep) = c.id in pileBuilderIds ||
        (c.body.any { it.type == WORK } && c.body.count { it.type == CARRY } >= 3)

    /** The pile of the job: energy on the builder's cell. */
    private fun pileOf(job: PileJob): Resource? = getObjectsByPrototype(Resource::class)
        .firstOrNull { it.exists && it.resourceType == RESOURCE_ENERGY && it.x == job.p.x && it.y == job.p.y }

    /** Cells for a job at container c: P next to c where the builder stands (and the pile lies), S next to P for the
     *  spawn, with at least three free cells around it for the creeps it will bear. Null — no room. */
    private fun pileCells(ctx: Ctx, c: Position): Pair<Position, Position>? {
        val taken = HashSet<Int>()
        for (p in ctx.blocked) taken.add(p.x * 100 + p.y)
        for (s in getObjectsByPrototype(ConstructionSite::class)) if (s.exists) taken.add(s.x * 100 + s.y)
        for (s in getObjectsByPrototype(StructureContainer::class)) if (s.exists) taken.add(s.x * 100 + s.y)
        fun open(x: Int, y: Int) = x in 1..98 && y in 1..98 && (x * 100 + y) !in taken &&
            getTerrainAt(InfluenceMap.cell(x, y)) != TERRAIN_WALL
        for (dx in -1..1) for (dy in -1..1) {
            val px = c.x + dx; val py = c.y + dy
            if ((dx == 0 && dy == 0) || !open(px, py)) continue
            for (ex in -1..1) for (ey in -1..1) {
                val sx = px + ex; val sy = py + ey
                if ((ex == 0 && ey == 0) || (sx == c.x && sy == c.y) || !open(sx, sy)) continue
                var exits = 0
                for (fx in -1..1) for (fy in -1..1) {
                    val nx = sx + fx; val ny = sy + fy
                    if ((fx == 0 && fy == 0) || (nx == px && ny == py) || (nx == c.x && ny == c.y)) continue
                    if (open(nx, ny)) exits++
                }
                if (exits >= 3) return InfluenceMap.cell(px, py) to InfluenceMap.cell(sx, sy)
            }
        }
        return null
    }

    /** The best job for a builder that is at `from` (or will be born at the home spawn in `bornIn` ticks): nearest
     *  by walk among the containers that pass all the timing tests (see USE_PILE_SPAWN). */
    private fun pileCandidate(ctx: Ctx, builder: Creep?, bornIn: Int): PileJob? {
        val carry = PILE_BODY.count { it == CARRY }
        val work = PILE_BODY.count { it == WORK }
        val price = buildCost("StructureSpawn")
        val dumpRate = CARRY_CAPACITY / 2.0 * carry - BUILD_POWER.toDouble()   // withdraw one tick, drop the next
        val buildTicks = ceil(price.toDouble() / (BUILD_POWER * work)).toInt()
        val swampPace = periodOn(work, PILE_BODY.count { it == MOVE }, 10)   // empty: only WORK weighs
        var best: PileJob? = null
        var bestWalk = Int.MAX_VALUE
        val why = if (builder != null) pileWhy else null
        fun no(k: String) { if (why != null) why[k] = (why[k] ?: 0) + 1 }
        for (site in ctx.sites) {
            val c = site.container ?: continue
            val life = site.ticksToDecay ?: continue          // permanent containers are the fleet's
            if (!site.ours) { no("his"); continue }
            if (!site.safe) { no("unsafe"); continue }
            // WHAT THE FLEET WILL TAKE IS NOT THE BUILDER'S (v90): the haulers already sent carry away their free
            // capacity; the rest must still pay for the spawn and the pile's decay over the build. v89 skipped any
            // container a single hauler was sent to — and against marlyman123#96 that was every fresh one on our
            // half: in four games the builder never got a job
            // v91: v90's subtraction left no job either — in four games 583-982 rejections as "hauled" and no site.
            // The builder has the right of way: a job reserves its container (reservedForPile) and the haulers sent
            // to it are sent elsewhere. The fleet brings one load a hauler before the container rots; dumped, the
            // whole of it becomes a spawn with its regeneration, a production point by the sources, and ~700 in it
            val fleetTakes = if (USE_PILE_RIGHT_OF_WAY) 0 else ctx.haulers.filter { haulerSite[it.id] == site.id }.sumOf { it.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0 }
            if (site.energy - fleetTakes < price + 2 * buildTicks) { no(if (fleetTakes > 0) "hauled" else "thin"); continue }
            val walk = if (builder != null) pathTicks(builder, flowTo(ctx, c), builder.x * 100 + builder.y)
                else bornIn + ctx.stepsToSpawn[c.x * 100 + c.y].let { if (it < 0) Int.MAX_VALUE / 4 else it * swampPace }
            if (walk >= Int.MAX_VALUE / 4) { no("noway"); continue }
            val dump = ceil((site.energy - fleetTakes) / dumpRate).toInt()
            if (life < walk + dump + 3) { no("late"); continue }  // it rots before it is on the ground
            val work0 = walk + dump + buildTicks
            // nobody of his armed reaches it before the job is done (at his plain pace — the optimistic one for him)
            if (USE_PILE_THREAT_ETA && ctx.combatEnemies.any { getRange(it, c).toLong() * plainPeriod(it).coerceAtMost(10) <= work0 }) { no("threat"); continue }
            val cells = pileCells(ctx, c)
            if (cells == null) { no("cells"); continue }
            if (walk < bestWalk) { bestWalk = walk; best = PileJob(site.id, InfluenceMap.cell(c.x, c.y), cells.first, cells.second) }
        }
        if (best != null) no("found")
        return best
    }

    /** Where an idle pile builder waits: among the cells where containers have dropped so far (dropHistory) on our
     *  side of the map, the one nearest the middle of them all — so the next fresh one is as few ticks away as the
     *  map lets it be. Home while nothing has dropped on our side yet. */
    private fun pileWaitCell(ctx: Ctx): Position {
        val ours = dropHistory.keys.filter { k -> DistanceMap.inOurHalf(k / 100, k % 100) && ctx.loadedToSpawn[k] >= 0 }
        if (ours.isEmpty()) return ctx.mySpawn
        val mx = ours.sumOf { it / 100 } / ours.size
        val my = ours.sumOf { it % 100 } / ours.size
        val k = ours.minByOrNull { maxOf(kotlin.math.abs(it / 100 - mx), kotlin.math.abs(it % 100 - my)) }!!
        return InfluenceMap.cell(k / 100, k % 100)
    }

    /** The job's container and pile are the builder's: the fleet does not claim them (tick, v88). */
    private fun reservedForPile(site: EnergySite): Boolean {
        val job = pileJob ?: return false
        return site.id == job.containerId || (site.resource != null && site.pos.x == job.p.x && site.pos.y == job.p.y)
    }

    private fun runPileBuilder(ctx: Ctx) {
        // the creep ordered THIS tick is not in the snapshot the tick began with: keep its id until the next one
        if (pileOrderedAt != getTicks()) pileBuilderIds.retainAll(ctx.myCreeps.mapTo(HashSet()) { it.id })
        val b = ctx.active.firstOrNull { isPileBuilder(it) }
        if (b == null) { if (pileBuilderIds.isEmpty()) pileJob = null; return }
        val work = b.body.count { it.type == WORK && it.hits > 0 }
        var job = pileJob
        if (job != null) {
            val j: PileJob = job
            val cont = getObjectsByPrototype(StructureContainer::class).firstOrNull { it.exists && it.id == j.containerId }
            val site = ctx.mySites.firstOrNull { it.x == j.s.x && it.y == j.s.y }
            val spawnThere = ctx.mySpawns.firstOrNull { it.x == j.s.x && it.y == j.s.y }
            val pile = pileOf(j)
            val spawnFull = spawnThere != null && (spawnThere.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) <= 0
            val carrying = b.store[RESOURCE_ENERGY] ?: 0
            val finished = spawnThere != null && (pile == null || spawnFull) && (carrying <= 0 || spawnFull)
            // the container rotted or was taken before a site stood: nothing to build from
            val lost = site == null && spawnThere == null && pile == null && (cont == null || (cont.store[RESOURCE_ENERGY] ?: 0) <= 0)
            if (finished || lost) {
                spawnReach[if (finished) "pbDone" else "pbLost"] = (spawnReach[if (finished) "pbDone" else "pbLost"] ?: 0) + 1
                pileJob = null
                job = null
            }
        }
        // a bare spawn of ours comes before any pile job not yet started (the container still full, nothing dumped)
        val bare = bareSpawn(ctx)
        if (bare != null && job != null && pileOf(job) == null && ctx.mySites.none { it.x == job!!.s.x && it.y == job!!.s.y }) {
            pileJob = null
            job = null
        }
        if (bare != null && job == null) {
            val incoming0 = InfluenceMap.damageAt(b.x, b.y, ctx.combatEnemies)
            if (incoming0 > 0.0) {
                (fleeStep(b, ctx.combatEnemies, ctx.dangerMatrix) ?: pathStep(b, ctx.mySpawn, 1, ctx.dangerMatrix))?.let { if (canMove(b)) TrafficManager.request(b, it, HAULER_LOADED_PRIORITY) }
                return
            }
            if (getRange(b, bare) > 1) {
                pathStep(b, bare, 1, ctx.dangerMatrix)?.let { if (canMove(b)) TrafficManager.request(b, it, HAULER_LOADED_PRIORITY) }
            } else {
                val site = ctx.mySites.firstOrNull { it.x == bare.x && it.y == bare.y }
                if (site == null) {
                    val r = createConstructionSite(bare.x, bare.y, StructureRampart::class.js)
                    if (DEBUG_LOG) println("spawn rampart: site at (${bare.x},${bare.y}) err=${r.error}")
                }
                val carrying = b.store[RESOURCE_ENERGY] ?: 0
                if (site != null && carrying > 0) b.build(site)
                else if (carrying <= 0 && (bare.store[RESOURCE_ENERGY] ?: 0) > 0) b.withdraw(bare, RESOURCE_ENERGY)
            }
            if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) println("  pb${b.id} (${b.x},${b.y}) carry=${b.store[RESOURCE_ENERGY] ?: 0} rampart=(${bare.x},${bare.y})")
            return
        }
        if (job == null) {
            job = pileCandidate(ctx, b, 0)
            pileJob = job
        }
        val incoming = InfluenceMap.damageAt(b.x, b.y, ctx.combatEnemies)
        val step: Position? = when {
            incoming > 0.0 -> fleeStep(b, ctx.combatEnemies, ctx.dangerMatrix) ?: pathStep(b, ctx.mySpawn, 1, ctx.dangerMatrix)
            job == null -> pileWaitCell(ctx).let { w -> if (getRange(b, w) > PARK_RANGE) pathStep(b, w, PARK_RANGE, ctx.dangerMatrix) else null }
            b.x != job.p.x || b.y != job.p.y -> pathStep(b, job.p, 0, ctx.dangerMatrix)
            else -> null
        }
        if (step != null && canMove(b)) TrafficManager.request(b, step, HAULER_LOADED_PRIORITY)
        if (job != null && b.x == job.p.x && b.y == job.p.y) {
            val j: PileJob = job
            val site = ctx.mySites.firstOrNull { it.x == j.s.x && it.y == j.s.y }
            val spawnThere = ctx.mySpawns.firstOrNull { it.x == j.s.x && it.y == j.s.y }
            if (site == null && spawnThere == null) {
                val r = createConstructionSite(j.s.x, j.s.y, StructureSpawn::class.js)
                if (DEBUG_LOG) println("pile spawn: site at (${j.s.x},${j.s.y}) from container (${j.c.x},${j.c.y}) err=${r.error}")
            }
            val carrying = b.store[RESOURCE_ENERGY] ?: 0
            val free = b.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0
            if (site != null && carrying > 0) b.build(site)
            val cont = getObjectsByPrototype(StructureContainer::class).firstOrNull { it.exists && it.id == j.containerId }
            if (cont != null && (cont.store[RESOURCE_ENERGY] ?: 0) > 0) {
                // alternate: fill up from the container, then put all but one build's worth on the ground
                if (free > 0) b.withdraw(cont, RESOURCE_ENERGY)
                else b.drop(RESOURCE_ENERGY, (carrying - BUILD_POWER * work).coerceAtLeast(0))
            } else {
                val pile = pileOf(j)
                if (pile != null && free > 0) b.pickup(pile)
                if (spawnThere != null && carrying > 0) b.transfer(spawnThere, RESOURCE_ENERGY)
            }
        }
        if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
            val j = job
            println("  pb${b.id} (${b.x},${b.y}) carry=${b.store[RESOURCE_ENERGY] ?: 0} job=${j?.let { "c(${it.c.x},${it.c.y})p(${it.p.x},${it.p.y})s(${it.s.x},${it.s.y})" } ?: "-"} " +
                "pile=${j?.let { pileOf(it)?.amount } ?: "-"} fire=${incoming.toInt()} step=${step?.let { "(${it.x},${it.y})" } ?: "stay"}")
        }
    }

    private fun runBuilders(ctx: Ctx) {
        if (ctx.builders.isEmpty()) return
        val spawn = ctx.mySpawn
        // НЕДОСТРОЙ НЕ КОРМЯТ. Площадка, которую при нынешнем притоке не успеть достроить, не «строится
        // долго» — она мертва, и каждая ходка смотрителя уносит в неё энергию, которой не хватает на тело
        // (матч 06.09 22:22: 863 из 1250 к концу матча при притоке 1-2 в тик). Часы — СВОИ у каждой
        // площадки (SiteJob), а не одни на всех
        val job = siteJobs.filter { it.site != null && it.inTime }.minByOrNull { getRange(spawn, it.site!!) }
        val site = job?.site
        val tower = ctx.myTowers.filter { (it.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0) > 0 }.minByOrNull { getRange(spawn, it) }
        for (b in ctx.builders) {
            val carrying = b.store[RESOURCE_ENERGY] ?: 0
            val free = b.store.getFreeCapacity(RESOURCE_ENERGY) ?: 0
            val goal: Position? = site ?: tower
            val reach = if (site != null) BUILD_RANGE else 1
            val canAct = goal != null && getRange(b, goal) <= reach
            // ОТКУДА ЭТА ПЛОЩАДКА КОРМИТСЯ — сказано в самой работе. Домашнюю башню кормят из спавна;
            // площадку у энергии — из кучи рядом с ней, иначе смотритель возит тысячу по сорок клеток
            val fromPile = job != null && !job.fromSpawn
            val pile = if (fromPile) ctx.sites.filter { it.container != null && it.safe && it.energy > 0 }
                .minByOrNull { getRange(b, it.pos) } else null
            val mayTake = !fromPile && lastSpawnOutlivesFighter && (spawn.store[RESOURCE_ENERGY] ?: 0) > 0 && getRange(b, spawn) <= 1
            val mayScoop = pile != null && free > 0 && getRange(b, pile.pos) <= 1
            if (canAct && carrying > 0) {
                if (site != null) b.build(site) else tower?.let { b.transfer(it, RESOURCE_ENERGY) }
            }
            // кормить нечего — груз возвращается в спавн, а не лежит в смотрителе до конца матча
            if (goal == null && carrying > 0 && getRange(b, spawn) <= 1) b.transfer(spawn, RESOURCE_ENERGY)
            if (mayTake && free > 0) b.withdraw(spawn, RESOURCE_ENERGY)
            if (mayScoop) pile!!.container?.let { b.withdraw(it, RESOURCE_ENERGY) }
            val incoming = InfluenceMap.damageAt(b.x, b.y, ctx.combatEnemies)
            val step = when {
                incoming > 0.0 -> fleeStep(b, ctx.combatEnemies, ctx.dangerMatrix) ?: pathStep(b, spawn, 1, ctx.dangerMatrix)
                goal == null -> if (getRange(b, spawn) > PARK_RANGE) pathStep(b, spawn, PARK_RANGE, ctx.dangerMatrix) else null
                // пустой идёт за энергией ТУДА, ГДЕ ОНА ДЛЯ ЭТОЙ ПЛОЩАДКИ: к спавну или к куче
                carrying <= 0 && pile != null -> if (getRange(b, pile.pos) > 1) pathStep(b, pile.pos, 1, ctx.dangerMatrix) else null
                carrying <= 0 && !mayTake -> pathStep(b, spawn, 1, ctx.dangerMatrix)
                !canAct -> pathStep(b, goal, reach, ctx.dangerMatrix)
                else -> null
            }
            if (step != null && canMove(b)) TrafficManager.request(b, step, HAULER_LOADED_PRIORITY)
            if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
                println("  b${b.id} (${b.x},${b.y}) carry=$carrying/${capacityOf(b)} work=${b.body.count { it.type == WORK && it.hits > 0 }} " +
                    "goal=${goal?.let { "(${it.x},${it.y})" } ?: "-"}${if (site != null) "site" else "feed"} job=${job ?: "-"} act=$canAct take=$mayTake scoop=$mayScoop fire=${incoming.toInt()} step=${step?.let { "(${it.x},${it.y})" } ?: "stay"}")
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
