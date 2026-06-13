package season3.spawnstrike

import screeps.api.BodyPartType
import screeps.api.CostMatrix
import screeps.api.Creep
import screeps.api.Flag
import screeps.api.HEAL
import screeps.api.HEAL_POWER
import screeps.api.MAX_CREEP_SIZE
import screeps.api.MOVE
import screeps.api.Position
import screeps.api.RANGED_ATTACK
import screeps.api.RANGED_HEAL_POWER
import screeps.api.RESOURCE_ENERGY
import screeps.api.get
import screeps.api.TERRAIN_SWAMP
import screeps.api.TERRAIN_WALL
import screeps.api.TOUGH
import screeps.api.getTerrainAt
import screeps.api.structures.StructureExtension
import screeps.api.SearchGoal
import screeps.api.SearchPathOptions
import screeps.api.arenaInfo
import screeps.api.getObjectsByPrototype
import screeps.api.getRange
import screeps.api.getTicks
import screeps.api.searchPath
import screeps.api.structures.StructureRampart
import screeps.api.structures.StructureSpawn
import screeps.api.structures.StructureWall
import sourcemaps.runWithSourceMapSupport
import kotlin.math.abs

@OptIn(ExperimentalJsExport::class)
@JsExport
fun loop() {
    try {
        runWithSourceMapSupport {
            SpawnStrike.tick()
        }
    } catch (t: Throwable) {
        // страховка: даже если source-map-обработчик упадёт, логируем ошибку и не роняем тик
        println("loop error: ${t.message}")
        println(t.stackTraceToString())
    }
}

object SpawnStrike {

    /** Дальность RANGED_ATTACK. */
    private const val RANGED_RANGE = 3

    /** Дальность rangedHeal — на этой дистанции можем лечить союзника. */
    private const val HEAL_RANGE = 3

    /** Радиус вокруг флага, в котором ranger считает вражеского крипа угрозой. */
    private const val DEFEND_RANGE = 8

    /** Радиус, в котором учитываем чужих/своих крипов при оценке баланса сил. */
    private const val INFLUENCE_SCAN = 12

    /** Радиус вокруг отряда, в котором враги считаются «локальной угрозой» для решения регруппа/атака.
     *  Глобальный перевес врага у его спавна не должен прижимать нас к базе, когда рядом никого. */
    private const val ENGAGE_RADIUS = 14

    /** Минимум врагов рядом для регруппы. Одиночку (даже сильного) отряд фокусит и убивает —
     *  бежать от одного нападающего не нужно; регруппа задумана против МАССЫ врагов. */
    private const val REGROUP_MIN_ENEMIES = 2

    /** Сколько больших бойцов должно собраться, прежде чем пара переходит в атаку. */
    private const val SQUAD_SIZE = 2

    /** Радиус «кучи» вокруг центра масс отряда: дальше — боец отстал и подтягивается.
     *  =1 — чтобы большие стояли вплотную и могли лечить друг друга heal в упор (range 1). */
    private const val COHESION_RADIUS = 1

    /** Во сколько раз наша мощь должна превышать вражескую, чтобы перейти из обороны в наступление. */
    private const val AGGRESSION_FACTOR = 1.2

    /** Порог ВХОДА в регруппу: отступаем только при ЯВНОМ недовесе (наша мощь < 0.8× вражеской).
     *  Вместе с порогом выхода (AGGRESSION_FACTOR 1.2×) образует широкий гистерезис: примерно
     *  равный бой (0.8..1.2) не прерываем — ranged-отряд с лекарями и кайтом его обычно вытягивает. */
    private const val REGROUP_FACTOR = 0.8

    /** Дисконт мили-мощи врага в оценке баланса сил: вся наша армия — ranged и кайтит, мили
     *  (30/часть на бумаге) равной скорости кайтящего стрелка НЕ ДОГОНИТ — его мощь условна,
     *  И ОТ РАЗМЕРА СТАИ ЭТО НЕ ЗАВИСИТ (толпа просто бежит сзади). Поэтому дисконт жёсткий:
     *  при 0.3 пачка из 15 милишников всё ещё «перевешивала» пару арифметикой и включала регруппу.
     *  Полную цену мили получает, только реально прижав кого-то из наших вплотную
     *  (<= MELEE_KEEP_RANGE — ударит этим/следующим тиком) — настоящий зажим (угол, болото)
     *  баланс честно покажет. Критерий «подбежал к отряду» не годится: пара убегала, стая бежала
     *  следом, «контакт» сохранялся — бегство самоподдерживалось. */
    private const val MELEE_KITE_DISCOUNT = 0.1

    /** Запас в гонке спавнов (тиков): дожимаем чужой спавн вместо разворота домой, только если
     *  выигрываем гонку с этим запасом — оценки времени грубые (бой по пути, враг может фокусить
     *  не спавн, а крипов; наша пара под огнём у спавна теряет DPS на кайт). */
    private const val RACE_MARGIN = 10.0

    /** Глубина вторжения (тиков пути от нашего спавна), с которой враг ВКЛЮЧАЕТ фазу зачистки.
     *  Враг, топчущийся на границе половин, фазу не дёргает: гистерезис — вход только по глубине,
     *  выход только по чистой половине. Без него один дешёвый крип у границы каждые пару тиков
     *  сбрасывал латч прорыва пары (разворот посреди болота) и гонял цели всех охотников. */
    private const val CLEAR_HOME_DEPTH = 35

    /** Латч фазы зачистки (гистерезис, см. CLEAR_HOME_DEPTH). */
    private var clearingHomeActive = false

    /** Страховочный потолок обороны: если готовность армии почему-то не наступила (пара не
     *  построилась, охотников выбивают) — после этого тика выходим всё равно, не сидим вечно. */
    private const val HOLD_UNTIL_TICK = 600

    /** Сколько охотников должно собраться (помимо полной пары больших), чтобы армия считалась готовой. */
    private const val ASSAULT_HUNTERS = 2

    /** Наступление начато: латч — раз выйдя с базы, в режим «сидим дома» не возвращаемся
     *  (гибель большого посреди боя не должна телепортировать доктрину в ранний гейм). */
    private var assaultStarted = false

    /** Сколько больших РЕАЛЬНО достижимо по энергии (живые + кого пул ещё профинансирует).
     *  armyReady ждёт это число, а не номинальный BIG_CREEP_COUNT: если пул тянет лишь одного,
     *  ожидание второго держало бы армию дома до страховочного HOLD_UNTIL_TICK. */
    private var plannedBigs = BIG_CREEP_COUNT

    /** Радиус вокруг нашего спавна, в котором враг считается угрозой базе (отбиваем в раннем гейме).
     *  Тот же радиус вокруг ВРАЖЕСКОГО спавна — «гарнизон» его базы при оценке готовности штурма. */
    private const val BASE_DEFENSE_RANGE = 15

    /** Радиус вокруг вражеского спавна, в котором наши бойцы считаются «ударной группой у базы»
     *  (стоящие у горловин входов и подходящие фланги — внутрь заходим только с их перевесом). */
    private const val ASSAULT_GATHER_RANGE = 20

    /** Радиус вокруг горловины центрального входа: враги в нём «сторожат центр». Пара коммитится
     *  на прорыв, когда фланговые растащили оборону и у центра осталось меньше её мощи. */
    private const val CENTER_GUARD_RANGE = 10

    /** Дистанция осады пары от горловины входа: ВПЛОТНУЮ (1) к болоту — заходящий в проход враг
     *  сразу в радиусе стрельбы и собирает урон все 5-тиковые болотные шаги. */
    private const val SIEGE_STANDOFF = 1

    /** Латч прорыва пары: решившись идти центром, не разворачиваемся на полпути через болото
     *  из-за мигнувшего счёта сторожей. Сбрасывается регруппой/зачисткой/ранним геймом. */
    private var pairStormCommitted = false

    /** Веса оценки позиции пары больших: приближение к цели, штраф за урон, наше влияние,
     *  и БОНУС за исходящий урон (в бою доминирует — пара ищет клетку, откуда бьёт больше врагов). */
    private const val PAIR_W_DIST = 10.0
    private const val PAIR_W_DAMAGE = 0.3
    private const val PAIR_W_INFLUENCE = 0.1
    private const val PAIR_W_OUTGOING = 30.0

    /** Вес исходящего урона при ПРОРЫВЕ (breaching): НИЖЕ цены шага марша (PAIR_W_DIST), иначе
     *  кайтящая у границы радиуса приманка пригвождает прорыв: +30 за её обстрел перевешивал
     *  +10 за шаг — пара стояла и «фермила» неубиваемого кайтера, пока утекала выигранная
     *  гонка спавнов. Стрельба от движения не зависит (healAndShoot бьёт всё в радиусе),
     *  так что приманку пара обстреливает НА ХОДУ. */
    private const val PAIR_W_OUTGOING_BREACH = 5.0

    /** Тяга пары вплотную ВНЕ боя: лёгкая (чтобы не мешать манёвру/протискиванию), но при
     *  прочих равных конфигурация range 1 всегда выигрывает у range 2. */
    private const val PAIR_W_TOGETHER = 8.0

    /** Тяга пары вплотную В БОЮ (и при бегстве): на дистанции 1 взаимный хил — heal в упор
     *  (12/часть), на 2-3 — лишь rangedHeal (4/часть), т.е. разойтись на клетку = потерять
     *  8×блоки HP/тик живучести. Вес делает добровольный отход на 2 практически невыгодным —
     *  его не перебивает ни лишний враг в радиусе (outgoing +12), ни рампарт (+10). */
    private const val PAIR_W_TOGETHER_COMBAT = 40.0

    /** Максимальный АВАРИЙНЫЙ зазор пары — не целевое состояние (цель — вплотную, range 1),
     *  а транзитный: когда между крипами спавн/стена, их range равен 2, и жёсткий порог 1
     *  не оставлял бы ни одной допустимой конфигурации — пара зависала намертво. */
    private const val PAIR_TOGETHER_RANGE = 2

    /** Приоритет больших крипов в TrafficManager — они проталкивают маленьких, а не застревают за ними. */
    private const val BIG_PRIORITY = 1

    /** На какой дистанции мили-враг считается угрозой (2 = подойдёт на шаг и ударит). Держимся дальше. */
    private const val MELEE_KEEP_RANGE = 2

    /** Штраф за нахождение в зоне удара мили-врага — большой, чтобы держать дистанцию любой ценой. */
    private const val PAIR_W_MELEE = 50.0

    /** Во сколько раз смягчаем мили-штраф при наступлении с перевесом — иначе scoring парализует и атаку. */
    private const val AGGRO_MELEE_FACTOR = 0.3

    /** Радиус «личного пространства» охотника: своих ближе этого штрафуем, чтобы держать интервал. */
    private const val SEPARATION_RADIUS = 1

    /** Штраф за каждого своего в SEPARATION_RADIUS. Меньше PAIR_W_DIST(10), чтобы наступление не глохло,
     *  но достаточный, чтобы охотники шли широким фронтом, а не колонной, которую враг бьёт по одному. */
    private const val PAIR_W_SPREAD = 4.0

    /** Бонус клетки своего рампарта в бою: крип на нём защищён (урон уходит в рампарт). */
    private const val PAIR_W_RAMPART = 10.0

    /** Штраф клетки болота В БОЮ: на болоте тело 1:1 ходит раз в 5 тиков — кайт невозможен,
     *  крип превращается в неподвижную мишень. Сопоставим с мили-штрафом и НЕ смягчается
     *  агрессией (физика усталости от настроя не зависит). На своём рампарте не действует.
     *  Исключение — ПРОРЫВ (breaching): штурм чужой базы или возврат в свой карман к
     *  прорвавшемуся врагу. В обоих случаях путь существует ТОЛЬКО через болотный пояс,
     *  и решение «идём» уже принято; без исключения −40 болота против +10 за шаг
     *  приближения парализуют крипа у первой болотной клетки горловины. */
    private const val PAIR_W_SWAMP = 40.0

    /** Вес HEAL-частей в оценке силы стороны: лечение — не урон, но сильно повышает живучесть отряда. */
    private const val HEAL_POWER_WEIGHT = 0.5

    /** Минимум охотников на фланге для охвата: одиночка во фланг — корм, он идёт к паре (эскорт). */
    private const val MIN_FLANK_GROUP = 2

    /** Сколько охотников снимаем с охоты отбивать наш флаг (флаг = право на рампарты ромба). */
    private const val FLAG_RESPONDERS = 2

    /** Потолок энергии спавна: регенерирует 1/тик, но не выше этого значения. */
    private const val SPAWN_ENERGY_CAP = 300

    /** Приоритет капчерера в TrafficManager: ниже всех — его проталкивают, он не создаёт пробок. */
    private const val CAPTURER_PRIORITY = -1

    /** 8 направлений + «стоять» для синхронного сдвига пары. */
    private val DIRECTIONS = listOf(
        0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1,
    )


    /** Надбавка стоимости прохода через клетку своего крипа при наступлении (мягкое обтекание, строй плотный). */
    private const val CROWD_COST = 3

    /** Рисовать ли матрицу влияния на поле (зелёный/красный — баланс сил).
     *  По замерам пользователя CPU тратит незначительно — можно держать включённой. */
    private const val DEBUG_VISUALS = true

    /** Рисовать ли матрицу урона на поле (оранжевый + число входящего урона). */
    private const val DEBUG_DAMAGE = false

    /** Печатать ли диагностику поведения рейнджеров. */
    private const val DEBUG_LOG = true

    /** Печатать ли ASCII-карту поля один раз (terrain + структуры) для анализа обороны. */
    private const val DEBUG_MAP = false
    private var mapLogged = false

    /** Подсвечивать ли найденные входы во вражескую базу (цветом на карте + лог центров). */
    private const val DEBUG_ENTRANCES = true
    private var entrancesLogged = false

    /** Сколько больших крипов создаём в начале (ходят парой, действуют синхронно). */
    private const val BIG_CREEP_COUNT = 2

    /** Состав блока большого крипа. ЭКСПЕРИМЕНТ (вкл.): 1×TOUGH + 4×MOVE + 2×RANGED + 1×HEAL.
     *  TOUGH — дешёвый передний буфер (10 энергии за 100 HP «брони»). Усталость: 4 не-MOVE части
     *  на 4 MOVE — по равнине каждый тик (паритет), по болоту шаг раз в 5 тиков (медленнее
     *  предыдущего 6-MOVE варианта, зато блок дешевле и живучее под фокусом).
     *  Предыдущие варианты: 3M+2R+1H (700, болото 5 тиков), 6M+2R+1H (850, болото 3 тика). */
    private const val BLOCK_TOUGH = 1
    private const val BLOCK_MOVES = 4
    private const val BLOCK_RANGED = 2
    private const val BLOCK_HEAL = 1

    /** Частей в блоке большого крипа. */
    private const val BLOCK_PARTS = BLOCK_TOUGH + BLOCK_MOVES + BLOCK_RANGED + BLOCK_HEAL

    /** Стоимость блока: TOUGH×10 + MOVE×50 + RANGED×150 + HEAL×250; текущий состав = 760. */
    private const val BLOCK_COST = BLOCK_TOUGH * 10 + BLOCK_MOVES * 50 + BLOCK_RANGED * 150 + BLOCK_HEAL * 250

    /** Тело маленького охотника. ЭКСПЕРИМЕНТ (вкл.): [MOVE, MOVE, RANGED] (250) идёт по болоту
     *  раз в 3 тика вместо 5 — быстрее заходит в базы, и лишний MOVE служит буфером HP; но
     *  дороже (реже спавнится: 250 тиков регена против 200). Если охотников станет ощутимо
     *  меньше — вернуть [MOVE, RANGED]. MOVE первой частью обязателен — порядок частей
     *  отличает охотника от защитника флага. */
    private val HUNTER_BODY: Array<BodyPartType> = arrayOf(MOVE, MOVE, RANGED_ATTACK)

    /** Стоимость HUNTER_BODY (держать в синхроне с телом выше). */
    private const val HUNTER_COST = 250

    /** Резерв стартового пула на эскорт (ASSAULT_HUNTERS охотников). Без резерва блоки больших
     *  съедали ВЕСЬ пул, и охотники строились из СЛУЧАЙНОГО остатка усечённого деления: при
     *  неудачном остатке эскорт копился с регена (1/тик), armyReady не наступал сотни тиков,
     *  и пара сидела дома до HOLD_UNTIL_TICK, пока враг свободно множился. */
    private const val ESCORT_RESERVE = ASSAULT_HUNTERS * HUNTER_COST

    /** Клетки своих рампартов на этот тик (защищённые огневые позиции) — для оценки клеток. */
    private var myRampartCells: Set<Int> = emptySet()

    /**
     * Отряд в режиме регруппы: отступаем кучей к спавну, лечимся, копим силу. Включается при реальном
     * перевесе врага, выключается только при чётком нашем перевесе (гистерезис против дёрганья).
     */
    private var squadRegrouping = false

    /** Последний известный владелец каждого флага по id — чтобы логировать только смену владельца. */
    private val lastFlagOwner = mutableMapOf<String, Boolean?>()

    init {
        println("hello season3 spawn-strike: ${arenaInfo.season} - ${arenaInfo.name}")
    }

    fun tick() {
        val mySpawn = getObjectsByPrototype(StructureSpawn::class).firstOrNull { it.my == true } ?: return

        if (DEBUG_MAP && !mapLogged) {
            mapLogged = true
            logMap()
        }

        val flags = getObjectsByPrototype(Flag::class)
        // флаги стабильно по близости к спавнам (не двигаются, в отличие от меняющегося my-статуса):
        // дальний от нашего спавна — чужой (захватываем), ближний — свой (держим).
        val homeFlag = flags.minByOrNull { getRange(it, mySpawn) }
        val targetFlag = flags.maxByOrNull { getRange(it, mySpawn) }

        val enemySpawn = getObjectsByPrototype(StructureSpawn::class).firstOrNull { it.my == false }

        val myCreeps = getObjectsByPrototype(Creep::class).filter { it.my && it.exists }
        // НЕ РОЖДЁННЫЕ вражеские крипы (spawning) — не цели и не угроза: они ещё не действуют,
        // бить их нельзя. Без фильтра стрелки переключались со спавна на «эмбриона» и ждали
        // его рождения вместо того, чтобы ломать сам спавн (а осада считала его гарнизоном).
        val enemyCreeps = getObjectsByPrototype(Creep::class).filter { !it.my && it.exists && !it.spawning }

        // роли по телу:
        //  захватчик флага — только MOVE (нет RANGED/HEAL);
        //  защитник флага — [RANGED, MOVE] (первая часть RANGED), отличается порядком от маленьких;
        //  маленький охотник — [MOVE, RANGED] (первая часть MOVE);
        //  большой — есть HEAL.
        val capturers = myCreeps.filter { c -> c.body.none { it.type == RANGED_ATTACK || it.type == HEAL } }
        val bigCreeps = myCreeps.filter { c -> c.body.any { it.type == HEAL } }
        val flagDefenders = myCreeps.filter { c -> c.body.none { it.type == HEAL } && c.body.firstOrNull()?.type == RANGED_ATTACK }
        val rangers = myCreeps.filter { c -> c.body.any { it.type == RANGED_ATTACK } } // защитник + охотники + большие (для стрельбы/лечения)

        // рампартом пользуется только владелец (право дают флаги, владение меняется по ходу боя):
        // для нас непроходимы чужие И нейтральные рампарты, для врага — наши и нейтральные.
        val ramparts = getObjectsByPrototype(StructureRampart::class).filter { it.exists }
        val walls = getObjectsByPrototype(StructureWall::class).filter { it.exists }
        // спавны и экстеншены непроходимы для всех (на них нельзя встать) — иначе крипы пытаются
        // пройти СКВОЗЬ свой спавн (поле потока течёт через него), упираются и стоят на месте.
        val structures = getObjectsByPrototype(StructureSpawn::class).filter { it.exists } +
            getObjectsByPrototype(StructureExtension::class).filter { it.exists }
        val blocked: List<Position> = walls + ramparts.filter { it.my != true } + structures
        val blockedForEnemy: List<Position> = walls + ramparts.filter { it.my != false } + structures

        // свои рампарты — защищённые огневые позиции: урон по крипу на них уходит в рампарт
        myRampartCells = ramparts.filter { it.my == true }.mapTo(HashSet()) { it.x * 100 + it.y }
        InfluenceMap.setProtectedCells(myRampartCells)

        // проходимость врага — для шага сближения в моделях урона/опасности: за стену/рампарт
        // опасность движением не распространяется (выстрел через стену при этом достаёт)
        InfluenceMap.setEnemyBlocked(blockedForEnemy.mapTo(HashSet()) { it.x * 100 + it.y })

        // карта опасности (для обхода красных зон и блокирующих структур) — строим раз за тик
        val dangerMatrix = InfluenceMap.dangerCostMatrix(enemyCreeps, blocked)

        logFlags(flags)

        // детекция входов во вражескую базу (болотные проходы в поясе стен) — подсветка для проверки
        if (DEBUG_ENTRANCES && enemySpawn != null) {
            val entrances = DistanceMap.enemyBaseEntrances(enemySpawn)
            InfluenceMap.drawEntrances(entrances)
            if (!entrancesLogged) {
                entrancesLogged = true
                println("enemy base entrances: ${entrances.size}")
                entrances.forEachIndexed { i, component ->
                    val cx = component.sumOf { it / 100 } / component.size
                    val cy = component.sumOf { it % 100 } / component.size
                    println("  entrance ${i + 1}: cells=${component.size} center=($cx,$cy)")
                }
            }
        }

        spawnIfNeeded(mySpawn, capturers, flagDefenders, bigCreeps, targetFlag, enemyCreeps)

        runCapturers(capturers, targetFlag, dangerMatrix)
        runFlagDefenders(flagDefenders, homeFlag, dangerMatrix)
        runRangers(rangers, myCreeps, enemyCreeps, homeFlag, enemySpawn, mySpawn, dangerMatrix, blocked, blockedForEnemy, capturers)

        // разом разрешаем все ходы: цепочки, свапы, проталкивание своих. Вызов в tick(), а не в
        // runRangers: капчерер должен ехать с 1-го тика, даже когда рейнджеров ещё нет.
        // НЕ movers (управляются напрямую / толкать нельзя): защитник флага (двойной move сбивал
        // его с флага) и капчерер, УЖЕ стоящий на своём флаге, — бойцы выталкивали его с флага,
        // и ромб терял владельца рампартов.
        val capturerIds = capturers.mapTo(HashSet()) { it.id }
        val movers = myCreeps.filter { c ->
            !c.spawning && !isFlagDefender(c) &&
                !(c.id in capturerIds && targetFlag != null && c.getRangeTo(targetFlag) == 0)
        }
        TrafficManager.resolve(movers, myCreeps + enemyCreeps)

        // стойки погибших выбрасываем — id не накапливаются за бой
        InfluenceMap.pruneStances(myCreeps.mapTo(HashSet()) { it.id })

        if (DEBUG_VISUALS) InfluenceMap.drawDebug(rangers, myCreeps, enemyCreeps)
        if (DEBUG_DAMAGE) InfluenceMap.drawDamage(rangers, enemyCreeps)
    }

    /** Печатаем флаг только когда его владелец изменился с прошлого тика. */
    private fun logFlags(flags: Array<Flag>) {
        for (flag in flags) {
            val previous = lastFlagOwner[flag.id]
            if (flag.id in lastFlagOwner && previous == flag.my) continue
            lastFlagOwner[flag.id] = flag.my
            val owner = when (flag.my) {
                true -> "my"
                false -> "enemy"
                else -> "neutral"
            }
            println("flag changed: $owner at (${flag.x}, ${flag.y})")
        }
    }

    /**
     * Печатает ASCII-карту поля один раз. Символы:
     *  '#' стена (terrain wall или StructureWall), '~' болото, '.' равнина,
     *  'M' мой спавн, 'E' вражеский спавн, 'F' мой флаг, 'f' вражеский флаг,
     *  'R' рампарт, 'x' экстеншен. Слева — номер строки y.
     */
    private fun logMap() {
        val marks = HashMap<Int, Char>()
        fun mark(x: Int, y: Int, c: Char) { marks[x * 100 + y] = c }

        getObjectsByPrototype(StructureWall::class).forEach { mark(it.x, it.y, '#') }
        getObjectsByPrototype(StructureRampart::class).forEach { mark(it.x, it.y, 'R') }
        getObjectsByPrototype(StructureExtension::class).forEach { mark(it.x, it.y, 'x') }
        getObjectsByPrototype(StructureSpawn::class).forEach { mark(it.x, it.y, if (it.my == true) 'M' else 'E') }
        getObjectsByPrototype(Flag::class).forEach { mark(it.x, it.y, if (it.my == true) 'F' else 'f') }

        println("=== MAP (rows y=0..99, cols x=0..99) ===")
        for (y in 0..99) {
            val row = StringBuilder()
            for (x in 0..99) {
                val structure = marks[x * 100 + y]
                row.append(
                    when {
                        structure != null -> structure
                        getTerrainAt(InfluenceMap.cell(x, y)) == TERRAIN_WALL -> '#'
                        getTerrainAt(InfluenceMap.cell(x, y)) == TERRAIN_SWAMP -> '~'
                        else -> '.'
                    }
                )
            }
            println("${y.toString().padStart(2, '0')}:$row")
        }
        println("=== END MAP ===")
    }

    /**
     * Очередь спавна (каждого по одному, кроме маленьких):
     *  1) захватчик дальнего флага [MOVE];
     *  2) защитник ближнего флага [RANGED, MOVE] (порядок частей отличает его от маленьких);
     *  3) 2 максимально сильных крипа (блоки 2×RANGED + 1×HEAL + 3×MOVE);
     *  4) безостановочно — маленькие [MOVE, RANGED] для зачистки карты.
     *
     * Экономика: большие строятся из СТАРТОВОГО пула (экстеншены + спавн); реген спавна — 1/тик
     * с потолком 300, его хватает только на маленьких. Блоки пересчитываем каждый тик и НЕ кэшируем:
     * однажды закэшированный 0 навсегда отключал больших. Если энергии на блок пока нет, но пул
     * в принципе может его дать — копим (ничего не спавним); если не может — переходим на маленьких.
     */
    private fun spawnIfNeeded(spawn: StructureSpawn, capturers: List<Creep>, flagDefenders: List<Creep>, bigCreeps: List<Creep>, targetFlag: Flag?, enemyCreeps: List<Creep>) {
        if (spawn.spawning != null) return

        // дальний флаг под охраной боевого врага: голый [MOVE]-капчерер туда — труп за 50 энергии,
        // и «смерть -> немедленный респавн -> смерть» душит экономику (50 тиков регена за цикл).
        // Не плодим смертников — очередь идёт дальше, капчерера дострoим после зачистки.
        val flagCamped = targetFlag != null && enemyCreeps.any { e ->
            getRange(e, targetFlag) <= DEFEND_RANGE && InfluenceMap.profileOf(e).let { it.melee + it.ranged > 0.0 }
        }

        val body: Array<BodyPartType>? = when {
            capturers.isEmpty() && !flagCamped -> arrayOf(MOVE) // захватчик дальнего флага
            flagDefenders.isEmpty() -> arrayOf(RANGED_ATTACK, MOVE) // защитник ближнего флага (RANGED первой)
            bigCreeps.size < BIG_CREEP_COUNT -> {
                val remaining = BIG_CREEP_COUNT - bigCreeps.size
                // пул может не тянуть ПОЛНЫЙ комплект — деградируем к меньшему числу больших:
                // один большой лучше, чем ни одного (раньше при нехватке на двоих бот навсегда
                // уходил в маленьких, хотя одиночный блок-крип был по карману).
                val affordable = (remaining downTo 1).firstOrNull { bigPoolPossible(it) }
                plannedBigs = bigCreeps.size + (affordable ?: 0)
                when {
                    affordable == null -> HUNTER_BODY // пул не тянет даже одного большого
                    computeBigBlocks(spawn, affordable) >= 1 -> bigBody(computeBigBlocks(spawn, affordable))
                    else -> null // копим на блок — реген докопит
                }
            }
            else -> HUNTER_BODY // маленькие охотники (MOVE первой)
        }
        if (body != null) spawn.spawnCreep(body)
    }

    /** Сколько блоков влезает в следующего большого: энергию делим поровну на оставшихся больших.
     *  Из пула предварительно вычтен резерв на эскорт (ESCORT_RESERVE) — армия это пара И охотники. */
    private fun computeBigBlocks(spawn: StructureSpawn, remainingBigs: Int): Int {
        val totalEnergy = ((spawn.store[RESOURCE_ENERGY] ?: 0) + extensionEnergy() - ESCORT_RESERVE).coerceAtLeast(0)
        val byEnergy = totalEnergy / remainingBigs / BLOCK_COST
        val bySize = MAX_CREEP_SIZE / BLOCK_PARTS
        return minOf(byEnergy, bySize)
    }

    /** Энергия в наших экстеншенах (часть стартового пула — не пополняется). */
    private fun extensionEnergy(): Int = getObjectsByPrototype(StructureExtension::class)
        .filter { it.my == true }
        .sumOf { it.store[RESOURCE_ENERGY] ?: 0 }

    /** Может ли пул в принципе профинансировать блоки больших: экстеншены + потолок регена
     *  спавна, за вычетом резерва на эскорт. */
    private fun bigPoolPossible(remainingBigs: Int): Boolean =
        extensionEnergy() + SPAWN_ENERGY_CAP - ESCORT_RESERVE >= remainingBigs * BLOCK_COST

    /**
     * Захватчик бежит на дальний (чужой) флаг и стоит на нём. Болото игнорирует (тело [MOVE] без
     * усталости), а вот зоны вражеского огня обходит (dangerMatrix) — голым он умирает мгновенно.
     * Ход через TrafficManager (низший приоритет): бойцы его проталкивают, пробок в проходах нет.
     */
    private fun runCapturers(capturers: List<Creep>, targetFlag: Flag?, dangerMatrix: CostMatrix) {
        if (targetFlag == null) return
        for (creep in capturers) {
            if (creep.spawning) continue
            if (creep.getRangeTo(targetFlag) == 0) continue // стоим на флаге — держим
            pathStep(creep, targetFlag, 0, dangerMatrix)?.let { TrafficManager.request(creep, it, CAPTURER_PRIORITY) }
        }
    }

    /** Защитник встаёт на ближний (свой) флаг и держит его; стрельба по врагам — через общий проход.
     *  Идёт первым шагом searchPath с dangerMatrix (обходит красные зоны), мимо TrafficManager —
     *  он не mover (двойной move сбивал его с флага). */
    private fun runFlagDefenders(defenders: List<Creep>, homeFlag: Flag?, dangerMatrix: CostMatrix) {
        if (homeFlag == null) return
        for (creep in defenders) {
            if (creep.spawning) continue
            if (creep.getRangeTo(homeFlag) > 0) pathStep(creep, homeFlag, 0, dangerMatrix)?.let { creep.moveTo(it) }
        }
    }

    /**
     * Тело большого крипа из `blocks` блоков (состав блока — BLOCK_*-константы).
     * Порядок: все TOUGH -> все MOVE -> все RANGED -> все HEAL. Урон сносит части спереди:
     * сначала дешёвая TOUGH-«броня», затем MOVE-буфер, RANGED под ними, а HEAL в самом
     * конце — гибнет последним, поэтому крип лечит до конца.
     */
    private fun bigBody(blocks: Int): Array<BodyPartType> {
        val body = ArrayList<BodyPartType>(BLOCK_PARTS * blocks)
        repeat(BLOCK_TOUGH * blocks) { body.add(TOUGH) } // броня вперёд
        repeat(BLOCK_MOVES * blocks) { body.add(MOVE) } // мув-буфер
        repeat(BLOCK_RANGED * blocks) { body.add(RANGED_ATTACK) } // все рэнж
        repeat(BLOCK_HEAL * blocks) { body.add(HEAL) } // весь хил в самый конец
        return body.toTypedArray()
    }

    /**
     * Дальнобойные бойцы действуют сквадом: собираются в кучу у центра масс отряда и
     * наступают вместе, только когда набралась критическая масса (SQUAD_SIZE).
     * Отставшие подтягиваются к куче (cohesion). Матрица опасности поверх squad-логики:
     * под явной угрозой боец отступает/кайтит независимо от строя — выживание важнее.
     * Приоритет целей при наступлении: угроза у флага -> вражеский спавн -> ближайший враг -> свой флаг.
     */
    private fun runRangers(
        rangers: List<Creep>,
        allies: List<Creep>,
        enemyCreeps: List<Creep>,
        homeFlag: Flag?,
        enemySpawn: StructureSpawn?,
        mySpawn: StructureSpawn,
        dangerMatrix: CostMatrix,
        blocked: List<Position>,
        blockedForEnemy: List<Position>,
        capturers: List<Creep>,
    ) {
        // угрозы НАШЕМУ флагу — любые враги (флаг крадёт и безоружный MOVE-крип, а флаг = право на ромб)
        val threats = if (homeFlag != null) enemyCreeps.filter { getRange(it, homeFlag) <= DEFEND_RANGE } else emptyList()
        val homeFlagLost = homeFlag != null && homeFlag.my != true

        val active = rangers.filter { !it.spawning }
        if (active.isEmpty()) return

        // «горячие точки» на карте влияния — вражеские крипы с живой боевой частью.
        val combatEnemies = enemyCreeps.filter {
            val profile = InfluenceMap.profileOf(it)
            profile.melee + profile.ranged > 0.0
        }

        // враги в НАШЕЙ половине карты — агрессивно зачищаем их.
        // Половина считается по реальной достижимости (BFS с учётом стен/рампартов), а не по прямой.
        if (enemySpawn != null) DistanceMap.ensureBuilt(mySpawn, enemySpawn)
        DistanceMap.ensureBaseZone(mySpawn)
        val ourHalfEnemies = if (enemySpawn != null) {
            combatEnemies.filter { DistanceMap.inOurHalf(it.x, it.y) }
        } else {
            combatEnemies
        }

        // вооружённые большие (есть HEAL и живой RANGED) — ударная пара; разоружённые — мобильные медики.
        val bigArmed = active.filter { c -> c.body.any { it.type == HEAL } && hasRanged(c) }
        val medics = active.filter { c -> c.body.any { it.type == HEAL } && !hasRanged(c) }

        // маленькие охотники (без HEAL, не защитник флага) — фланги/эскорт пары.
        val hunters = active.filter { c -> c.body.none { it.type == HEAL } && !isFlagDefender(c) }

        // безопасна ли ранняя вылазка за карман ради флага: угрозы у флага безоружны (вор-капчерер —
        // бесплатный фраг) ИЛИ наши охотники их локально перевешивают. Без проверки каждый свежий
        // охотник бежал к флагу и умирал об численно превосходящего врага — конвейер смертников,
        // armyReady не наступал никогда, пара сидела дома до HOLD_UNTIL_TICK.
        val combatThreats = threats.filter { val p = InfluenceMap.profileOf(it); p.melee + p.ranged > 0.0 }
        val flagSortieSafe = combatThreats.isEmpty() ||
            powerOf(hunters) >= enemyPowerOf(combatThreats, active) * AGGRESSION_FACTOR

        // выход с базы — ПО ГОТОВНОСТИ армии, а не по таймеру: полная пара больших + эскорт
        // охотников. Готовая раньше армия атакует раньше (не дарим врагу время), а недостроенная
        // не выходит по звонку. HOLD_UNTIL_TICK — страховочный потолок против вечного сидения
        // (пара невозможна по энергии, охотников выбивают на спавне и т.п.).
        val armyReady = bigArmed.size >= minOf(BIG_CREEP_COUNT, plannedBigs) && hunters.size >= ASSAULT_HUNTERS
        if (!assaultStarted && (armyReady || getTicks() >= HOLD_UNTIL_TICK)) {
            assaultStarted = true
            if (DEBUG_LOG) println("assault: started at tick=${getTicks()} bigs=${bigArmed.size} hunters=${hunters.size}")
        }
        // ранний гейм (оборона базы) — пока наступление не начато.
        val earlyGame = !assaultStarted

        // оценка перевеса — ЛОКАЛЬНАЯ С ОБЕИХ СТОРОН: только бойцы рядом с отрядом (в ENGAGE_RADIUS
        // от центра сквада). Глобальная масса врага у его спавна не должна прижимать нас к базе,
        // а наши разбежавшиеся по карте охотники и защитник флага дома не должны раздувать «нашу»
        // силу — в локальном бою их нет. Близость врагов — по достижимости ДЛЯ ВРАГА (BFS по его
        // проходимости: наши рампарты его не пускают), иначе враг за рампартной стеной считался бы
        // «рядом» и ложно включал регруппу.
        // Центр якорим к БОЛЬШИМ (ядро отряда), а не к среднему всех active: иначе, когда охотники
        // убегают вперёд, центр оказывается в чистом поле и local скачет каждый тик → цель пары мигает.
        val anchorCreeps = active.filter { c -> c.body.any { it.type == HEAL } && hasRanged(c) }.ifEmpty { active }
        val squadCenter = InfluenceMap.cell(anchorCreeps.sumOf { it.x } / anchorCreeps.size, anchorCreeps.sumOf { it.y } / anchorCreeps.size)
        val squadFlow = DistanceMap.flowFieldTo(squadCenter, blockedForEnemy)
        val allyFlow = DistanceMap.flowFieldTo(squadCenter, blocked) // наша проходимость (свои рампарты открыты)
        val localEnemies = combatEnemies.filter { squadFlow[it.x * 100 + it.y] in 0..ENGAGE_RADIUS }
        // союзники — СИММЕТРИЧНО врагам (те же 14 шагов пути, каждая сторона по своей проходимости):
        // фланговый охотник рядом с боем не должен выпадать из нашей силы из-за Чебышев-метрики
        val localAllies = active.filter { allyFlow[it.x * 100 + it.y] in 0..ENGAGE_RADIUS }
        // HEAL учитываем с весом: лечение — не урон, но отряд с лекарями заметно живучее
        val ourPower = powerOf(localAllies)
        // мили врага дисконтируем (enemyPowerOf): наш отряд целиком ranged и кайтит, мили равной
        // скорости его не догонит — полная цена только при реальном зажиме вплотную.
        // (Своих мили у нас нет; если появятся — пересмотреть симметрию.)
        val enemyPower = enemyPowerOf(localEnemies, localAllies)
        val weAreStronger = localEnemies.isEmpty() || ourPower >= enemyPower * AGGRESSION_FACTOR

        // режим отряда с ШИРОКИМ гистерезисом: в регруппу — только при явном недовесе (< 0.8×),
        // обратно в наступление — при явном перевесе (>= 1.2×); примерно равный бой не прерываем.
        // Ядро на своих рампартах не отступает вовсе: позиция и так почти неуязвима.
        val coreOnRamparts = bigArmed.isNotEmpty() && bigArmed.all { it.x * 100 + it.y in myRampartCells }
        squadRegrouping = when {
            earlyGame || localEnemies.size < REGROUP_MIN_ENEMIES -> false // одиночку фокусим, не бежим
            coreOnRamparts -> false             // стоим на рампартах — держим позицию, урон идёт в них
            weAreStronger -> false              // мы явно сильнее локально — наступаем
            ourPower < enemyPower * REGROUP_FACTOR -> true // явный недовес — собираемся в кулак
            else -> squadRegrouping              // 0.8..1.2× — держим текущее состояние
        }
        // агрессивная оценка клеток: при перевесе смягчаем мили-штраф, иначе scoring парализует и атаку.
        val aggressive = weAreStronger && !squadRegrouping

        // враги, подошедшие к нашему спавну — их отбиваем в раннем гейме.
        val baseDefenders = combatEnemies.filter { getRange(it, mySpawn) <= BASE_DEFENSE_RANGE }

        // ГОНКА СПАВНОВ: враги зашли на нашу половину, но если мы добьём их спавн раньше, чем они
        // наш, — дожимаем, а не разворачиваемся (разворот пары в двух тайлах от победы уже стоил
        // матча). Грубая оценка: наше время = марш пары до дистанции стрельбы + hits спавна / наш
        // DPS; их время = марш до нашего спавна (по ИХ проходимости) + hits нашего спавна / их DPS.
        // RACE_MARGIN страхует грубость (бой в пути, враг может фокусить не спавн, а крипов).
        val pushWinsRace = if (enemySpawn != null && ourHalfEnemies.isNotEmpty() && bigArmed.isNotEmpty()) {
            val spawnFlow = DistanceMap.flowFieldTo(enemySpawn, blocked)
            val pairTravel = bigArmed.minOf { spawnFlow[it.x * 100 + it.y].let { d -> if (d < 0) Int.MAX_VALUE else d } }
            val pairDps = bigArmed.sumOf { InfluenceMap.profileOf(it).ranged }
            val enemyApproach = DistanceMap.flowFieldTo(mySpawn, blockedForEnemy)
            val enemyTravel = ourHalfEnemies.minOf { enemyApproach[it.x * 100 + it.y].let { d -> if (d < 0) Int.MAX_VALUE else d } }
            val enemyDps = ourHalfEnemies.sumOf { val p = InfluenceMap.profileOf(it); p.melee + p.ranged }
            when {
                pairTravel == Int.MAX_VALUE || pairDps <= 0.0 -> false // нам спавн не добить — гонки нет
                enemyTravel == Int.MAX_VALUE || enemyDps <= 0.0 -> true // им наш не добить — спокойно дожимаем
                else -> {
                    // hits/hitsMax у Structure в биндингах nullable — фоллбек на номинал спавна (3000)
                    val ourTicks = maxOf(0, pairTravel - RANGED_RANGE) + (enemySpawn.hits ?: enemySpawn.hitsMax ?: 3000) / pairDps
                    val enemyTicks = enemyTravel + (mySpawn.hits ?: mySpawn.hitsMax ?: 3000) / enemyDps
                    ourTicks + RACE_MARGIN < enemyTicks
                }
            }
        } else {
            false
        }

        // ФАЗА ЗАЧИСТКИ: на нашей половине есть боевые враги И гонку спавнов мы не выигрываем —
        // сначала вычищаем свой тыл (любой коридор), и только потом идём на чужой спавн. Иначе
        // размен «мы рашим их спавн, они наш» враг выигрывает по темпу.
        clearingHomeActive = when {
            earlyGame || ourHalfEnemies.isEmpty() || pushWinsRace -> false
            ourHalfEnemies.any { DistanceMap.stepsFromMy(it.x, it.y) <= CLEAR_HOME_DEPTH } -> true
            else -> clearingHomeActive // враг лишь на границе половин — состояние не дёргаем
        }
        val clearingHome = clearingHomeActive

        // ШТУРМ БАЗЫ. Ничья (никто не снёс спавн) — тоже не победа, поэтому вечной осады нет:
        // фланговые ВСЕГДА заходят своими входами и растаскивают оборону в стороны, а ПАРА ждёт у
        // горловины центрального входа и коммитится на прорыв, когда центр оголился (сторожей у
        // центрального входа меньше мощи пары) или когда ударная группа в целом сильнее гарнизона.
        // обе стороны — только БОЕВЫЕ крипы: гарнизон из combatEnemies (живые ATTACK/RANGED),
        // наша ударная группа — только вооружённые (разоружённый медик штурму не помощник,
        // его хил не должен раздувать готовность).
        val garrison = if (enemySpawn != null) combatEnemies.filter { getRange(it, enemySpawn) <= BASE_DEFENSE_RANGE } else emptyList()
        val strikers = if (enemySpawn != null) active.filter { hasRanged(it) && getRange(it, enemySpawn) <= ASSAULT_GATHER_RANGE } else emptyList()
        // гарнизон оцениваем с кайт-дисконтом мили (enemyPowerOf): одиночный милишник у спавна
        // не блокирует штурм — заходя, он обязан пересечь наш огонь и никого не достанет
        val baseAssaultReady = garrison.isEmpty() || powerOf(strikers) >= enemyPowerOf(garrison, strikers) * AGGRESSION_FACTOR
        val centerClear = if (enemySpawn != null && bigArmed.isNotEmpty()) {
            val gate = siegePoint(enemySpawn)
            val centerGuards = combatEnemies.filter { getRange(it, gate) <= CENTER_GUARD_RANGE }
            powerOf(bigArmed) >= enemyPowerOf(centerGuards, bigArmed) * AGGRESSION_FACTOR
        } else {
            false
        }
        // латч: решившись на прорыв, держим решение (не разворачиваемся на полпути через болото
        // из-за мигнувшего счёта сторожей); сбрасывают его только регруппа/зачистка/ранний гейм.
        pairStormCommitted = when {
            earlyGame || squadRegrouping || clearingHome || bigArmed.isEmpty() -> false
            baseAssaultReady || centerClear -> true
            else -> pairStormCommitted
        }
        val pairStorm = pairStormCommitted

        // цели охоты: ранний гейм — только угроза базе; зачистка — наша половина; перевес — вся карта.
        val huntTargets = when {
            earlyGame -> baseDefenders
            clearingHome -> ourHalfEnemies
            weAreStronger -> combatEnemies
            else -> emptyList()
        }
        // фокус — ближайший к нашему спавну враг (по реальной достижимости).
        // цель движения — только ДОСТИЖИМЫЙ враг (за рампартом без пути скипаем; стрелять по нему всё
        // равно можно — ranged бьёт сквозь стены). Если все недостижимы — фокус-цели нет, идём дальше по приоритету.
        val reachableHunt = if (enemySpawn != null) {
            huntTargets.filter { DistanceMap.stepsFromMy(it.x, it.y) < Int.MAX_VALUE }
        } else {
            huntTargets
        }
        val priorityTarget = reachableHunt.minByOrNull {
            if (enemySpawn != null) DistanceMap.stepsFromMy(it.x, it.y) else getRange(it, mySpawn)
        }
        // поле потока к фокус-цели — чтобы движение огибало стены/ромб, а не упиралось в них
        val targetFlow = priorityTarget?.let { DistanceMap.flowFieldTo(it, blocked) }
        // позиции своих (для веера: задние обтекают передних по полю потока)
        val occupied = allies.filter { !it.spawning }.map { it.x * 100 + it.y }.toSet()

        // рубеж: в раннем гейме — у нашего спавна (оборона базы); позже — граница половин.
        val frontline: Position = when {
            earlyGame -> mySpawn
            enemySpawn != null -> InfluenceMap.cell((mySpawn.x + enemySpawn.x) / 2, (mySpawn.y + enemySpawn.y) / 2)
            else -> homeFlag ?: mySpawn
        }

        // оборона раннего гейма — у ГОРЛОВИН своего кармана, а не в глубине у спавна: враг,
        // заходящий болотным проходом, собирает урон все свои 5-тиковые шаги. Центры входов;
        // распределение по флангу (чётность id), чтобы не толпиться у одного входа.
        val homeGates: List<Position> = if (earlyGame) {
            DistanceMap.baseEntrances(mySpawn, mine = true).map { comp ->
                InfluenceMap.cell(comp.sumOf { it / 100 } / comp.size, comp.sumOf { it % 100 } / comp.size)
            }
        } else {
            emptyList()
        }

        // строй (центр масс и cohesion) строим вокруг вооружённых больших — они держатся вплотную
        // для ближнего хила; маленькие охотятся отдельно и центр не размывают.
        val squadBase = bigArmed.ifEmpty { active }
        val centroid = InfluenceMap.cell(squadBase.sumOf { it.x } / squadBase.size, squadBase.sumOf { it.y } / squadBase.size)
        val assembled = squadBase.count { getRange(it, centroid) <= COHESION_RADIUS }
        val squadReady = combatEnemies.isEmpty() || assembled >= minOf(SQUAD_SIZE, squadBase.size)

        // mustFlee пары больших — отступают оба, если любой разоружён (нет стрельбы) или под смертельным
        // чистым уроном (с учётом нашего хила).
        val groupMustFlee = squadBase.any { c ->
            !hasRanged(c) || c.hits < InfluenceMap.netDamageAt(c.x, c.y, enemyCreeps, allies) * 2
        }

        // при наступлении свои крипы — «дорогие», чтобы задние обтекали стрелков и вставали веером
        val crowdMatrix = dangerMatrix.clone()
        for (ally in allies) {
            val current = crowdMatrix.get(ally.x, ally.y)
            if (current < 255) crowdMatrix.set(ally.x, ally.y, minOf(254, current + CROWD_COST))
        }

        if (DEBUG_LOG) {
            val spawnInfo = if (enemySpawn != null) "(${enemySpawn.x},${enemySpawn.y})" else "null"
            println(
                "squad: active=${active.size} combat=${combatEnemies.size} local=${localEnemies.size} enemies=${enemyCreeps.size} " +
                    "ourPower=${ourPower.toInt()} enemyPower=${enemyPower.toInt()} regroup=$squadRegrouping coreRamp=$coreOnRamparts aggro=$aggressive " +
                    "clearHome=$clearingHome race=$pushWinsRace storm=$pairStorm(all=$baseAssaultReady center=$centerClear) garrison=${garrison.size} " +
                    "enemySpawn=$spawnInfo centroid=(${centroid.x},${centroid.y}) assembled=$assembled ready=$squadReady"
            )
        }

        // точка отхода при регруппе — СВОЙ РАМПАРТ в нашей половине, ближайший к ядру: почти
        // неуязвимая огневая позиция (урон уходит в рампарт), враг вынужден ломиться под огонь,
        // а coreOnRamparts затем держит позицию. Право на рампарты дают флаги; если своих
        // рампартов в нашей половине нет — прежняя логика: клетка нашего СИЛЬНЕЙШЕГО влияния
        // (крипы сбиваются в плотный кулак, а не размазываются к базе). Кандидаты — только на
        // НАШЕЙ половине: иначе кучка охотников в чужой глубине утаскивала регруппу вперёд.
        val rallyAllies = allies.filter { !it.spawning }
        val rallyRampart = myRampartCells.asSequence()
            .map { InfluenceMap.cell(it / 100, it % 100) }
            .filter { DistanceMap.inOurHalf(it.x, it.y) }
            .minByOrNull { getRange(it, centroid) }
        val rallyCandidates = rallyAllies.filter { DistanceMap.inOurHalf(it.x, it.y) }.ifEmpty { rallyAllies }
        val rallyPoint: Position = rallyRampart
            ?: rallyCandidates.maxByOrNull { InfluenceMap.influenceAt(it.x, it.y, rallyAllies, emptyList()) }
                ?.let { InfluenceMap.cell(it.x, it.y) }
            ?: mySpawn

        // фокус-файр: общая цель стрельбы на тик. ПРИОРИТЕТ — лекари (макс. хил): лекарь гасит
        // наш урон по всей вражеской стае, его смерть рушит их живучесть. Среди равных по хилу
        // (в т.ч. все 0 — лекарей нет): сначала «убиваемые ЗА ЭТОТ ТИК» (hits не больше суммы
        // доступного огня — гарантированный килл), затем min-hits (добивание лучше распыления).
        val inFireRange = enemyCreeps.filter { e -> active.any { it.getRangeTo(e) <= RANGED_RANGE } }
        val focusPool = inFireRange.filter { e -> combatEnemies.any { it.id == e.id } }.ifEmpty { inFireRange }
        fun fireAvailableAt(e: Creep) = active.filter { it.getRangeTo(e) <= RANGED_RANGE }
            .sumOf { InfluenceMap.profileOf(it).ranged }
        val focusTarget = focusPool.minWithOrNull(
            compareByDescending<Creep> { InfluenceMap.profileOf(it).heal } // макс. хил — в первую очередь
                .thenBy { if (it.hits <= fireAvailableAt(it)) 0 else 1 }   // добиваемый за этот тик
                .thenBy { it.hits }                                        // самый раненый
                .thenBy { getRange(it, squadCenter) }
        )

        // фланговый охват — только группой: считаем охотников на каждом фланге (по чётности id),
        // одиночное подкрепление идёт к паре и воюет вместе с ней, а не уходит на убой соло.
        val leftHunters = hunters.count { isLeftFlank(it) }
        val rightHunters = hunters.size - leftHunters

        // наш флаг потерян/под угрозой — ближайшие охотники снимаются отбивать его (флаг = право
        // на ромб). Но только если это ДЕЙСТВЕННО: есть кого стрелять (угрозы достаём сквозь
        // стены) или на флаг можно встать (достижим). Потерянный флаг внутри ЧУЖОГО рампартного
        // кольца без врагов рядом недостижим — респондеры вечно стояли бы у кольца без дела.
        val flagActionable = homeFlag != null &&
            (threats.isNotEmpty() || DistanceMap.stepsFromMy(homeFlag.x, homeFlag.y) < Int.MAX_VALUE)
        val flagResponders: Set<String> = if (homeFlag != null && flagActionable && (homeFlagLost || threats.isNotEmpty())) {
            hunters.sortedBy { getRange(it, homeFlag) }.take(FLAG_RESPONDERS).mapTo(HashSet()) { it.id }
        } else {
            emptySet()
        }

        // кэш полей потока на тик: охотники одного фланга разделяют одну цель — один BFS вместо N
        val flowCache = HashMap<Int, IntArray>()
        fun cachedFlowTo(target: Position): IntArray =
            flowCache.getOrPut(target.x * 100 + target.y) { DistanceMap.flowFieldTo(target, blocked) }

        // фланговые потоки к вражескому спавну: флангу ЗАКРЫТЫ все входы базы, кроме своего, —
        // путь обязан обогнуть базу снаружи и зайти только своим входом, даже если через чужой
        // вход или карман «дешевле». Свой вход фланга — крайняя по x компонента (лево/право).
        var leftFlankFlow: IntArray? = null
        var rightFlankFlow: IntArray? = null
        fun flankFlow(left: Boolean): IntArray? {
            if (enemySpawn == null) return null
            (if (left) leftFlankFlow else rightFlankFlow)?.let { return it }
            val own = flankEntrance(enemySpawn, left) ?: return null
            val closedOthers = DistanceMap.enemyBaseEntrances(enemySpawn)
                .filter { it !== own }
                .flatMap { component -> component.map { InfluenceMap.cell(it / 100, it % 100) } }
            val flow = DistanceMap.flowFieldTo(enemySpawn, blocked + closedOthers)
            if (left) leftFlankFlow = flow else rightFlankFlow = flow
            return flow
        }

        // пара больших крипов — отдельная детерминированная логика связки (всегда вплотную).
        // в регруппе цель пары — точка сильнейшего влияния (сбор в кулак); в раннем гейме — оборона у рубежа;
        // в фазе зачистки — ближайший к нашему спавну враг нашей половины (тыл важнее чужого спавна);
        // в наступлении при готовности штурма пара идёт ЦЕНТРОМ прямо на вражеский спавн, а при
        // сильном гарнизоне — встаёт у внешней горловины центрального входа (осада: копим фланговых,
        // расстреливаем выходящих через болото, внутрь в одиночку не лезем).
        val pairSiege = !squadRegrouping && !earlyGame && !clearingHome && enemySpawn != null && !pairStorm
        val pairTarget: Position = when {
            squadRegrouping -> rallyPoint
            earlyGame -> priorityTarget ?: frontline
            clearingHome -> priorityTarget ?: frontline
            enemySpawn != null -> if (pairStorm) enemySpawn else siegePoint(enemySpawn)
            else -> priorityTarget ?: frontline
        }
        // в осаде пара стоит ВПЛОТНУЮ к болоту горловины (standoff 1, а не дистанция стрельбы 3):
        // заходящий в проход враг сразу под огнём и собирает урон все свои 5-тиковые шаги
        val pairStandoff = if (pairSiege) SIEGE_STANDOFF else RANGED_RANGE
        // в регруппе не используем жадное бегство (оно разносит пару) — идём к спавну единой связкой.
        val pairFlee = groupMustFlee && !squadRegrouping
        val enemyPositions = enemyCreeps.mapTo(HashSet()) { it.x * 100 + it.y }
        val huntBlockedSet = blocked.mapTo(HashSet()) { it.x * 100 + it.y }
        val meleeEnemies = enemyCreeps.filter { InfluenceMap.profileOf(it).melee > 0.0 } // от них держим дистанцию

        // БЛОКАДА СПАВНА при шторме: ближайшие охотники разбирают клетки ВПЛОТНУЮ к вражескому
        // спавну — новорождённым врагам некуда выйти (или выходят в упор под весь наш огонь),
        // подкрепления гарнизона отключаются на время добивания спавна.
        val blockadeAssign = HashMap<String, Position>()
        if (pairStorm && enemySpawn != null) {
            val cells = DIRECTIONS.asSequence()
                .filter { (dx, dy) -> dx != 0 || dy != 0 }
                .map { (dx, dy) -> InfluenceMap.cell(enemySpawn.x + dx, enemySpawn.y + dy) }
                .filter { passableForBig(it.x, it.y, huntBlockedSet, emptySet()) }
                .toList()
            hunters.filter { getRange(it, enemySpawn) <= ASSAULT_GATHER_RANGE }
                .sortedBy { getRange(it, enemySpawn) }
                .zip(cells)
                .forEach { (h, cell) -> blockadeAssign[h.id] = cell }
        }
        // прорыв пары через болото: штурм чужой базы ИЛИ возврат в свой карман (враг прорвался
        // к нашему спавну — путь к нему только через болотный пояс, штраф болота парализовал бы защиту)
        val pairBreach = pairStorm || DistanceMap.inBaseZone(pairTarget.x, pairTarget.y)
        runBigPair(bigArmed, pairTarget, pairStandoff, pairFlee, earlyGame, aggressive, pairBreach, enemyCreeps, allies, blocked, enemyPositions, dangerMatrix)
        runMedics(medics, allies, dangerMatrix, mySpawn)

        for (creep in active) {
            val isBig = creep.body.any { it.type == HEAL }
            if (isBig) continue // большие обработаны парой выше
            if (isFlagDefender(creep)) continue // защитник флага — управляется отдельно
            // маленький крип (без HEAL) после раннего гейма — одиночный охотник за чужими крипами.
            val hunter = !earlyGame && !isBig

            val moveTarget: Position? = when {
                threats.isNotEmpty() -> creep.findClosestByRange(threats.toTypedArray())
                priorityTarget != null -> priorityTarget // фокусим врага, ближайшего к нашему спавну
                // ранний гейм без угроз — маленькие держат горловины кармана своим флангом
                earlyGame && homeGates.isNotEmpty() ->
                    if (isLeftFlank(creep)) homeGates.minByOrNull { it.x }!! else homeGates.maxByOrNull { it.x }!!
                earlyGame -> frontline // входы не нашлись — держим оборону у спавна, не выходим
                combatEnemies.isNotEmpty() -> frontline // враги ещё на своей половине — держим рубеж, не лезем на чужую
                enemySpawn != null -> enemySpawn // карта чистая — добиваем спавн врага
                enemyCreeps.isNotEmpty() -> creep.findClosestByRange(enemyCreeps.toTypedArray())
                else -> homeFlag
            }

            // локальный баланс сил вокруг крипа решает, наступать или отходить.
            // Враги — только БОЕВЫЕ: от безвредного MOVE-капчерера не кайтим и не бежим.
            val nearbyAllies = allies.filter { getRange(creep, it) <= INFLUENCE_SCAN }
            val nearbyEnemies = combatEnemies.filter { getRange(creep, it) <= INFLUENCE_SCAN }
            // локальный баланс сил вокруг крипа решает, наступать или отходить
            val balance = InfluenceMap.influenceAt(creep.x, creep.y, nearbyAllies, nearbyEnemies)
            val stance = InfluenceMap.updateStance(creep.id, balance)

            // уже есть кого расстреливать в радиусе — держим позицию, не лезем вперёд
            val canShoot = enemyCreeps.any { creep.getRangeTo(it) <= RANGED_RANGE } ||
                (enemySpawn != null && creep.getRangeTo(enemySpawn) <= RANGED_RANGE)

            // отступаем, если разоружены (нет стрельбы) или чистый урон (с учётом хила) смертелен за 2 хода
            val mustFlee = !hasRanged(creep) ||
                creep.hits < InfluenceMap.netDamageAt(creep.x, creep.y, nearbyEnemies, nearbyAllies) * 2

            var huntDbg = "" // диагностика охотника: цель и flow на клетке/соседях

            // ранний гейм, угроза НАШЕМУ флагу: идём отбивать даже ЗА карман — флаг в ромбе,
            // за болотным поясом, и его потеря отдаёт врагу рампарты на всю середину игры
            // (одинокий защитник [RANGED,MOVE] против пары ранних охотников не жилец).
            // Только если вылазка не самоубийство (flagSortieSafe) — иначе уступаем флаг до сбора армии.
            val defendingFlag = earlyGame && flagSortieSafe && moveTarget != null && threats.any { it === moveTarget }

            // желаемая клетка хода (null = стоять); сами ходы разрулит TrafficManager
            val rawStep: Position? = when {
                // отряд в регруппе — ЯДРО стягивается кучей к точке сильнейшего влияния (не каждый
                // в свою сторону, не к спавну), там сбивается в кулак и лечится. Стрельба продолжается
                // в healAndShoot — отходим, отстреливаясь. Перебивает жадный flee. ДАЛЬНИЕ охотники
                // (за ENGAGE_RADIUS от центра) не бегут через карту — продолжают фланг/эскорт.
                squadRegrouping && allyFlow[creep.x * 100 + creep.y] in 0..ENGAGE_RADIUS ->
                    pathStep(creep, rallyPoint, 1, dangerMatrix)
                // на клетке слишком много урона для наших HP — уходим, даже если зона «наша»
                mustFlee ->
                    // фоллбек к флагу — range 1: на сам флаг не лезем (там защитник, его не протолкнуть)
                    fleeStep(creep, nearbyEnemies, dangerMatrix) ?: homeFlag?.let { pathStep(creep, it, 1, dangerMatrix) }
                // отбиваем наш флаг в раннем гейме — кламп кармана на этих крипов не действует
                defendingFlag && moveTarget != null ->
                    pathStep(creep, moveTarget, RANGED_RANGE, crowdMatrix)
                // маленький охотник: та же оценка позиций, что у пары (исходящий урон, дистанция к цели
                // по реальному пути, мили-штраф, влияние), но в одиночку — со своим flow к цели.
                hunter -> {
                    // приоритет целей: отбить наш флаг (если назначен респондером) -> зачистка нашей
                    // половины (фокус на ближайшем к нашему спавну) -> фланговый охват ГРУППОЙ
                    // (одиночка во фланг — корм) -> эскорт пары -> ближайший враг.
                    val flankGroup = if (isLeftFlank(creep)) leftHunters else rightHunters
                    var flankFlowOverride: IntArray? = null
                    val huntTarget: Position? = when {
                        creep.id in flagResponders ->
                            threats.minByOrNull { getRange(creep, it) } ?: homeFlag
                        // блокада: назначенный охотник встаёт на свою клетку вплотную к спавну
                        creep.id in blockadeAssign -> blockadeAssign[creep.id]
                        // фаза зачистки: вся стая давит одного врага нашей половины, фланги подождут
                        clearingHome ->
                            priorityTarget ?: creep.findClosestByRange(ourHalfEnemies.toTypedArray())
                        enemySpawn != null && (flankGroup >= MIN_FLANK_GROUP || bigArmed.isEmpty()) -> {
                            // фланговые заходят ВСЕГДА (даже при сильном гарнизоне): их работа —
                            // растащить оборону к боковым входам и оголить центр для прорыва пары.
                            // Поток своего фланга (чужие входы закрыты); входы не нашлись — общий.
                            flankFlowOverride = flankFlow(isLeftFlank(creep))
                            enemySpawn
                        }
                        bigArmed.isNotEmpty() -> centroid // одиночное подкрепление — к паре, воюем вместе
                        else -> {
                            val reachable = enemyCreeps.filter { DistanceMap.stepsFromMy(it.x, it.y) < Int.MAX_VALUE }
                            when {
                                reachable.isNotEmpty() -> creep.findClosestByRange(reachable.toTypedArray())
                                enemyCreeps.isNotEmpty() -> creep.findClosestByRange(enemyCreeps.toTypedArray())
                                else -> null
                            }
                        }
                    }
                    if (huntTarget != null) {
                        val huntFlow = flankFlowOverride ?: cachedFlowTo(huntTarget)
                        if (DEBUG_LOG) {
                            val f0 = huntFlow[creep.x * 100 + creep.y]
                            var fmin = Int.MAX_VALUE
                            for ((dx, dy) in DIRECTIONS) {
                                val nx = creep.x + dx; val ny = creep.y + dy
                                if (dx == 0 && dy == 0) continue
                                if (nx * 100 + ny in occupied) continue
                                if (!passableForBig(nx, ny, huntBlockedSet, enemyPositions)) continue
                                val fd = huntFlow[nx * 100 + ny]
                                if (fd in 0 until fmin) fmin = fd
                            }
                            huntDbg = " ht=(${huntTarget.x},${huntTarget.y}) f0=$f0 fminFree=${if (fmin == Int.MAX_VALUE) "none" else fmin}"
                        }
                        if (huntFlow[creep.x * 100 + creep.y] < 0) {
                            // цель в недоступном для нас кармане (например, враг на флаге внутри
                            // чужого рампартного кольца) — flow не построился. Стрельба сквозь
                            // стены её достаёт: в радиусе — стоим и стреляем; дальше идём, только
                            // если путь РЕАЛЬНО приближает (см. pathStepCloser — и не замираем
                            // перед боковым обходом, и не осциллируем на кольце подхода).
                            if (creep.getRangeTo(huntTarget) <= RANGED_RANGE) {
                                null
                            } else {
                                pathStepCloser(creep, huntTarget, RANGED_RANGE, dangerMatrix)
                            }
                        } else {
                            val inCombat = combatEnemies.any { creep.getRangeTo(it) <= RANGED_RANGE + 2 }
                            // прорыв через болото: штурм чужой базы (цель — её спавн/блокадная
                            // клетка) или возврат в свой карман к прорвавшемуся врагу — без этого
                            // болотный штраф парализует охотника у горловины так же, как раньше пару
                            val breaching = (enemySpawn != null && huntTarget === enemySpawn) ||
                                creep.id in blockadeAssign ||
                                DistanceMap.inBaseZone(huntTarget.x, huntTarget.y)
                            // блокадная клетка — цель, на которой надо СТОЯТЬ (standoff 0)
                            val huntStandoff = if (creep.id in blockadeAssign) 0 else RANGED_RANGE
                            bestSingleMove(creep, huntTarget, huntFlow, huntStandoff, mustFlee, false, aggressive, inCombat, breaching, true, enemyCreeps, allies, meleeEnemies, huntBlockedSet, enemyPositions, occupied)
                        }
                    } else {
                        null
                    }
                }
                // давим к фокус-цели по полю потока (не отсиживаясь на границе зоны влияния):
                // при глобальном перевесе — везде; в раннем гейме — на врагов у базы (в пределах кармана).
                // Стоп — по Чебышеву (стрельба сквозь стены достаёт), а не по flow: тот в тиках
                // (болото=5) и тянул бы крипа вглубь, когда цель уже давно в радиусе огня.
                (weAreStronger || earlyGame) && priorityTarget != null && targetFlow != null ->
                    if (creep.getRangeTo(priorityTarget) <= RANGED_RANGE) null
                    else DistanceMap.flowStep(targetFlow, creep.x, creep.y, RANGED_RANGE, occupied, enemyPositions)
                // под явной угрозой по балансу сил — отступаем/кайтим вне зависимости от строя
                stance == InfluenceMap.Stance.RETREAT ->
                    fleeStep(creep, nearbyEnemies, dangerMatrix) ?: homeFlag?.let { pathStep(creep, it, 1, dangerMatrix) }
                // отстал от кучи — подтягиваемся к центру масс. В раннем гейме строй не нужен:
                // оборона — это позиции у горловин кармана, а не кулак вокруг пары у спавна.
                !earlyGame && getRange(creep, centroid) > COHESION_RADIUS ->
                    pathStep(creep, centroid, 1, dangerMatrix)
                // в куче, но отряд ещё мал — ждём пополнения (не стоим под огнём)
                !squadReady ->
                    if (tooClose(creep, nearbyEnemies)) fleeStep(creep, nearbyEnemies, dangerMatrix) else null
                // можем стрелять — стоим на огневой (или кайтим, если враг подошёл вплотную)
                canShoot ->
                    if (tooClose(creep, nearbyEnemies)) fleeStep(creep, nearbyEnemies, dangerMatrix) else null
                // сквад собран, стрелять не по кому — наступаем к цели
                stance == InfluenceMap.Stance.ADVANCE ->
                    when {
                        // к фокус-цели идём по полю потока — гарантированно огибает стены и ромб
                        moveTarget === priorityTarget && targetFlow != null ->
                            DistanceMap.flowStep(targetFlow, creep.x, creep.y, RANGED_RANGE, occupied, enemyPositions)
                        // к остальным целям (рубеж, спавн) — обычный поиск пути, обтекая своих
                        moveTarget != null -> pathStep(creep, moveTarget, RANGED_RANGE, crowdMatrix)
                        else -> null
                    }
                // HOLD — держим идеальную дистанцию
                else ->
                    if (tooClose(creep, nearbyEnemies)) fleeStep(creep, nearbyEnemies, dangerMatrix) else null
            }

            // в раннем гейме не выходим за карман спавна: шаг наружу гасим (стоим у края, стреляем по
            // застрявшим на болоте врагам). Исключения: mustFlee (выживание) и защита флага.
            val step: Position? = if (earlyGame && !mustFlee && !defendingFlag && rawStep != null && !DistanceMap.inBaseZone(rawStep.x, rawStep.y)) {
                null
            } else {
                rawStep
            }

            if (DEBUG_LOG) {
                val tgt = when (moveTarget) {
                    null -> "none"
                    enemySpawn -> "spawn"
                    else -> "(${moveTarget.x},${moveTarget.y})"
                }
                val stepInfo = if (step != null) "(${step.x},${step.y})" else "stay"
                val distToTarget = if (moveTarget != null) creep.getRangeTo(moveTarget) else -1
                println(
                    "  r${creep.id} (${creep.x},${creep.y}) hits=${creep.hits} tgt=$tgt dist=$distToTarget " +
                        "stance=$stance mustFlee=$mustFlee canShoot=$canShoot step=$stepInfo$huntDbg"
                )
            }

            if (step != null) TrafficManager.request(creep, step)
        }

        // лечение и стрельба отдельным проходом — с общим учётом вложенного хила и фокус-целью
        healAndShoot(active, allies, enemyCreeps, enemySpawn, focusTarget, pairStorm)
    }

    /**
     * Лечение и стрельба для всего отряда за один проход.
     *
     * Механика уровней действий Screeps:
     *  - heal (вплотную, range 1) — отдельный пайплайн, совмещается со стрельбой → лечим И стреляем;
     *  - rangedHeal (range 2-3) и rangedAttack — один пайплайн → либо лечим на дистанции, либо стреляем.
     *
     * Лечение распределяем глобально: ведём учёт уже вложенного хила (healDone), а потребность цели —
     * дефицит HP ПЛЮС ожидаемый входящий урон (пре-хил: урон и лечение применяются одновременно,
     * поэтому полный HP крип под огнём тоже лечится — иначе тик урона уходит в чистый минус).
     * Каждый лекарь сначала ищет нуждающегося ВПЛОТНУЮ (heal + стрельба), иначе ближайшего в радиусе
     * rangedHeal (тогда вместо стрельбы). Некого лечить — просто стреляет.
     */
    private fun healAndShoot(active: List<Creep>, allies: List<Creep>, enemyCreeps: List<Creep>, enemySpawn: StructureSpawn?, focusTarget: Creep?, stormSpawn: Boolean) {
        val healDone = HashMap<String, Int>() // id цели -> уже вложенный хил за этот тик
        val incoming = HashMap<String, Int>() // id цели -> ожидаемый входящий урон на её клетке (кэш на тик)

        fun need(target: Creep): Int {
            val deficit = target.hitsMax - target.hits
            val expected = incoming.getOrPut(target.id) { InfluenceMap.damageAt(target.x, target.y, enemyCreeps).toInt() }
            return deficit + expected - (healDone[target.id] ?: 0)
        }

        for (creep in active) {
            val healParts = creep.body.count { it.type == HEAL && it.hits > 0 }

            if (healParts > 0) {
                // нуждающиеся в радиусе лечения (дефицит + ожидаемый урон ещё не покрыты)
                val candidates = allies.filter { need(it) > 0 && creep.getRangeTo(it) <= HEAL_RANGE }

                // приоритет — самый нуждающийся; сначала кого достаём вплотную (heal + стрельба)
                val closeTarget = candidates.filter { creep.getRangeTo(it) <= 1 }.maxByOrNull { need(it) }
                if (closeTarget != null) {
                    creep.heal(closeTarget)
                    healDone[closeTarget.id] = (healDone[closeTarget.id] ?: 0) + healParts * HEAL_POWER
                    shoot(creep, enemyCreeps, enemySpawn, focusTarget, stormSpawn) // heal вплотную совместим со стрельбой
                    continue
                }

                // rangedHeal конфликтует со стрельбой: менять выстрел (10+/часть) на хил 4/часть
                // оправдано только при РЕАЛЬНОМ дефиците HP. Чистый пре-хил полному крипу через
                // rangedHeal — потеря DPS (пара на дистанции 2 друг от друга переставала стрелять).
                val farTarget = candidates.filter { it.hitsMax - it.hits > 0 }.maxByOrNull { need(it) }
                if (farTarget != null) {
                    creep.rangedHeal(farTarget) // конфликтует со стрельбой — в этот тик не стреляем
                    healDone[farTarget.id] = (healDone[farTarget.id] ?: 0) + healParts * RANGED_HEAL_POWER
                    continue
                }
            }

            // лечить некого (или нет смысла) — стреляем
            shoot(creep, enemyCreeps, enemySpawn, focusTarget, stormSpawn)
        }
    }

    /**
     * Стрельба (выполняется всегда, в т.ч. при отступлении — это и есть кайтинг).
     * Выбираем rangedMassAttack только если её суммарный урон по всем целям в радиусе
     * больше, чем полный одиночный выстрел (single = 1.0 в единицах мощи); иначе бьём одну цель:
     * фокус-цель отряда (добиваем все вместе), а без неё — самого раненого в радиусе.
     */
    private fun shoot(creep: Creep, enemyCreeps: List<Creep>, enemySpawn: StructureSpawn?, focusTarget: Creep?, stormSpawn: Boolean) {
        val creepsInRange = enemyCreeps.filter { creep.getRangeTo(it) <= RANGED_RANGE }
        val spawnInRange = enemySpawn != null && creep.getRangeTo(enemySpawn) <= RANGED_RANGE

        if (creepsInRange.isEmpty() && !spawnInRange) return

        // суммарный урон массовой атаки в единицах мощи: сумма distance-rate по всем целям в радиусе
        var massValue = creepsInRange.sumOf { InfluenceMap.rangedRate(creep.getRangeTo(it)) }
        if (spawnInRange) massValue += InfluenceMap.rangedRate(creep.getRangeTo(enemySpawn!!))

        if (massValue > 1.0) {
            creep.rangedMassAttack() // массовая выгоднее полного одиночного (фокус-цель она тоже заденет)
        } else {
            val target = when {
                // ШТУРМ: победа — только снос спавна (3000 HP), каждый распылённый на крипов тик
                // удлиняет гонку, которую страхует RACE_MARGIN. Одиночный огонь — в спавн.
                stormSpawn && spawnInRange -> enemySpawn
                focusTarget != null && creep.getRangeTo(focusTarget) <= RANGED_RANGE -> focusTarget
                // squad-фокус недосягаем — среди досягаемых тот же приоритет: лекарь, затем добиваемый
                creepsInRange.isNotEmpty() ->
                    creepsInRange.minWithOrNull(
                        compareByDescending<Creep> { InfluenceMap.profileOf(it).heal }.thenBy { it.hits }
                    )
                else -> enemySpawn
            }
            target?.let { creep.rangedAttack(it) }
        }
    }

    /**
     * Логика пары больших крипов. Они действуют как жёсткая связка: всегда держатся вплотную,
     * двигаются синхронным сдвигом в лучшем направлении (перебор + оценка по урону/влиянию/цели),
     * а если разошлись — сближаются. Регистрирует ходы в TrafficManager (стрельба/хил — отдельно).
     */
    private fun runBigPair(
        bigs: List<Creep>,
        target: Position,
        standoff: Int,
        mustFlee: Boolean,
        earlyGame: Boolean,
        aggressive: Boolean,
        breaching: Boolean,
        enemyCreeps: List<Creep>,
        allies: List<Creep>,
        blocked: List<Position>,
        enemyPositions: Set<Int>,
        dangerMatrix: CostMatrix,
    ) {
        val active = bigs.filter { !it.spawning }
        if (active.isEmpty()) return

        val blockedSet = blocked.mapTo(HashSet()) { it.x * 100 + it.y }
        // клетки УСТАВШИХ своих (fatigue > 0, кроме самой пары): протолкнуть их нельзя — request
        // в такую клетку гарантированно проваливается. Если ход одного из пары проваливается,
        // а второго нет, связка растягивается (эскорт-охотники, уставшие на болоте прямо на пути
        // пары, растаскивали её всю горловину). Не планируем туда вовсе.
        val pairIds = active.mapTo(HashSet()) { it.id }
        val unpushable = allies.asSequence()
            .filter { !it.spawning && it.fatigue > 0 && it.id !in pairIds }
            .mapTo(HashSet()) { it.x * 100 + it.y }
        // поле потока к цели пары — РЕАЛЬНАЯ дистанция по пути (огибает стены), а не Чебышев.
        // Без него пара жмётся к стене напротив цели за ней и стоит.
        val pairFlow = DistanceMap.flowFieldTo(target, blocked)
        // мили-враги (есть ATTACK) — от них держим дистанцию, не подпускаем на удар
        val meleeEnemies = enemyCreeps.filter { InfluenceMap.profileOf(it).melee > 0.0 }
        // в бою ли пара: есть враг в зоне контакта у любого из членов. Вне боя — чистый марш по flow
        // (без «мягких» термов влияния/хила, иначе пара клубится у строя и не идёт вперёд).
        val inCombat = enemyCreeps.any { e -> active.any { it.getRangeTo(e) <= RANGED_RANGE + 2 } }

        // одиночка (напарник погиб) — лучший шаг из 9 (оценка включает исходящий урон → ищет огневую)
        if (active.size == 1) {
            val a = active[0]
            bestSingleMove(a, target, pairFlow, standoff, mustFlee, earlyGame, aggressive, inCombat, breaching, false, enemyCreeps, allies, meleeEnemies, blockedSet, enemyPositions + unpushable, emptySet())
                ?.let { TrafficManager.request(a, it, BIG_PRIORITY) }
            return
        }

        val a = active[0]
        val b = active[1]

        if (DEBUG_LOG) {
            val fa = pairFlow[a.x * 100 + a.y]
            val fb = pairFlow[b.x * 100 + b.y]
            println(
                "bigpair: tick=${getTicks()} early=$earlyGame mustFlee=$mustFlee aggro=$aggressive breach=$breaching " +
                    "a=(${a.x},${a.y}) b=(${b.x},${b.y}) target=(${target.x},${target.y}) range=${getRange(a, b)} " +
                    "fa=$fa fb=$fb melee=${meleeEnemies.size}"
            )
        }

        // усталость: уставший (fatigue > 0, обычно после болота) в этот тик не сдвинется —
        // напарник НЕ уходит от него, иначе связка рвётся и вплотную-хил (range 1) пропадает.
        val aTired = a.fatigue > 0
        val bTired = b.fatigue > 0

        // разошлись слишком далеко — сначала сближаемся: идём НАВСТРЕЧУ оба (раньше шёл только второй,
        // а первый стоял столбом даже под огнём, и при заблокированном пути пара зависала намертво).
        // Порог 2 (а не 1), чтобы пара не зависала, когда между ними стоит спавн/стена (range всегда 2).
        if (getRange(a, b) > PAIR_TOGETHER_RANGE) {
            if (!bTired) pathStep(b, a, 1, dangerMatrix)?.let { TrafficManager.request(b, it, BIG_PRIORITY) }
            if (!aTired) pathStep(a, b, 1, dangerMatrix)?.let { TrafficManager.request(a, it, BIG_PRIORITY) }
            return
        }

        // распределение здоровья: на более простреливаемой клетке должен стоять более здоровый крип.
        // Используем enemyPressureAt (с затуханием по дистанции) — он различает соседние клетки тоньше,
        // чем damageAt (который для обоих в радиусе одинаков). Раненый на опасной клетке -> меняемся местами.
        // Только ВПЛОТНУЮ (range 1) и только когда оба могут шагнуть: уставший своп сорвёт.
        val healthier = if (a.hits >= b.hits) a else b
        val wounded = if (healthier === a) b else a
        if (a.hits != b.hits && getRange(a, b) <= 1 && !aTired && !bTired) {
            val woundedPressure = InfluenceMap.enemyPressureAt(wounded.x, wounded.y, enemyCreeps)
            val healthierPressure = InfluenceMap.enemyPressureAt(healthier.x, healthier.y, enemyCreeps)
            if (woundedPressure > healthierPressure) {
                TrafficManager.request(healthier, InfluenceMap.cell(wounded.x, wounded.y), BIG_PRIORITY)
                TrafficManager.request(wounded, InfluenceMap.cell(healthier.x, healthier.y), BIG_PRIORITY)
                return
            }
        }

        // перебираем все пары соседних позиций (A', B'), оставляя пару вплотную (range<=1),
        // и выбираем лучшую по оценке. Перебор даёт смену ориентации (колонна/бок-о-бок) — пара
        // протискивается узкими проходами, а не застревает синхронным сдвигом.
        // Уставший зафиксирован на своей клетке (единственное «направление» — стоять): напарник
        // выбирает себе позицию вокруг него, но не уходит — связка и вплотную-хил сохраняются.
        val aDirections = if (aTired) listOf(0 to 0) else DIRECTIONS
        val bDirections = if (bTired) listOf(0 to 0) else DIRECTIONS
        var bestScore = scorePair(a.x, a.y, b.x, b.y, target, pairFlow, standoff, mustFlee, aggressive, inCombat, breaching, enemyCreeps, allies, meleeEnemies)
        var bestAx = a.x; var bestAy = a.y; var bestBx = b.x; var bestBy = b.y
        for ((adx, ady) in aDirections) {
            val ax = a.x + adx; val ay = a.y + ady
            if (!passableForBig(ax, ay, blockedSet, enemyPositions)) continue
            if (ax * 100 + ay in unpushable && (ax != a.x || ay != a.y)) continue
            if (earlyGame && !DistanceMap.inBaseZone(ax, ay)) continue
            for ((bdx, bdy) in bDirections) {
                val bx = b.x + bdx; val by = b.y + bdy
                if (ax == bx && ay == by) continue // не на одной клетке
                if (maxOf(abs(ax - bx), abs(ay - by)) > PAIR_TOGETHER_RANGE) continue // держимся вместе (можно огибать препятствие)
                if (!passableForBig(bx, by, blockedSet, enemyPositions)) continue
                if (bx * 100 + by in unpushable && (bx != b.x || by != b.y)) continue
                if (earlyGame && !DistanceMap.inBaseZone(bx, by)) continue

                val s = scorePair(ax, ay, bx, by, target, pairFlow, standoff, mustFlee, aggressive, inCombat, breaching, enemyCreeps, allies, meleeEnemies)
                if (s > bestScore) {
                    bestScore = s; bestAx = ax; bestAy = ay; bestBx = bx; bestBy = by
                }
            }
        }
        if (bestAx != a.x || bestAy != a.y) TrafficManager.request(a, InfluenceMap.cell(bestAx, bestAy), BIG_PRIORITY)
        if (bestBx != b.x || bestBy != b.y) TrafficManager.request(b, InfluenceMap.cell(bestBx, bestBy), BIG_PRIORITY)
    }

    /**
     * Разоружённый большой крип — мобильный лекарь: следует вплотную за ближайшим вооружённым
     * союзником, лечит через общий проход healAndShoot. Если воюющих нет — отходит к спавну.
     */
    private fun runMedics(medics: List<Creep>, allies: List<Creep>, dangerMatrix: CostMatrix, mySpawn: StructureSpawn) {
        val armed = allies.filter { !it.spawning && hasRanged(it) }
        for (medic in medics) {
            if (medic.spawning) continue
            val follow = if (armed.isNotEmpty()) medic.findClosestByRange(armed.toTypedArray()) else null
            if (follow != null) {
                if (medic.getRangeTo(follow) > 1) {
                    pathStep(medic, follow, 1, dangerMatrix)?.let { TrafficManager.request(medic, it, BIG_PRIORITY) }
                }
            } else if (medic.getRangeTo(mySpawn) > 2) {
                // сопровождать некого — отходим к спавну, не стоим под огнём в чистом поле
                pathStep(medic, mySpawn, 2, dangerMatrix)?.let { TrafficManager.request(medic, it, BIG_PRIORITY) }
            }
        }
    }

    /**
     * Лучший одиночный шаг крипа по оценке клеток (исходящий урон, дистанция к цели по flow, входящий
     * урон с учётом хила, влияние, мили-штраф). Используется и для одиночного большого, и для охотников.
     * occupied — позиции своих, на них не лезем (веер); для одиночки передаётся пустой набор.
     */
    private fun bestSingleMove(
        creep: Creep,
        target: Position,
        pairFlow: IntArray,
        standoff: Int,
        mustFlee: Boolean,
        earlyGame: Boolean,
        aggressive: Boolean,
        inCombat: Boolean,
        breaching: Boolean,
        spread: Boolean,
        enemyCreeps: List<Creep>,
        allies: List<Creep>,
        meleeEnemies: List<Creep>,
        blockedSet: Set<Int>,
        enemyPositions: Set<Int>,
        occupied: Set<Int>,
    ): Position? {
        var bestScore = scoreCell(creep.x, creep.y, target, pairFlow, standoff, mustFlee, aggressive, inCombat, breaching, spread, enemyCreeps, allies, meleeEnemies)
        var bx = creep.x; var by = creep.y
        // лучший «проталкивающий» ход: занятая СВОИМ клетка строго ближе к цели. Если свободных
        // улучшающих нет — запрашиваем её, TrafficManager разрулит цепочкой/свапом; иначе колонна
        // на узком пути намертво стоит за головным (его свободные соседи цель не приближают).
        val hereDist = pairFlow[creep.x * 100 + creep.y]
        var pushDist = if (hereDist >= 0) hereDist else Int.MAX_VALUE
        var pushX = -1; var pushY = -1
        for ((dx, dy) in DIRECTIONS) {
            val x = creep.x + dx; val y = creep.y + dy
            if (!passableForBig(x, y, blockedSet, enemyPositions)) continue
            if (earlyGame && !DistanceMap.inBaseZone(x, y)) continue
            if (x * 100 + y in occupied) { // клетка занята своим — обтекаем (веер), но помним как толчок
                if (!mustFlee && !inCombat) {
                    val fd = pairFlow[x * 100 + y]
                    if (fd in 0 until pushDist) { pushDist = fd; pushX = x; pushY = y }
                }
                continue
            }
            val s = scoreCell(x, y, target, pairFlow, standoff, mustFlee, aggressive, inCombat, breaching, spread, enemyCreeps, allies, meleeEnemies)
            if (s > bestScore) { bestScore = s; bx = x; by = y }
        }
        if (bx != creep.x || by != creep.y) return InfluenceMap.cell(bx, by)
        if (pushX >= 0) return InfluenceMap.cell(pushX, pushY) // свободного хода вперёд нет — толкаем своего
        return null
    }

    /** Оценка позиции пары (сумма по обеим клеткам + бонус за близость друг к другу). Больше — лучше. */
    private fun scorePair(ax: Int, ay: Int, bx: Int, by: Int, target: Position, pairFlow: IntArray, standoff: Int, mustFlee: Boolean, aggressive: Boolean, inCombat: Boolean, breaching: Boolean, enemyCreeps: List<Creep>, allies: List<Creep>, meleeEnemies: List<Creep>): Double {
        // в бою/бегстве прижатие жёсткое (потеря ближнего хила дороже любых позиционных бонусов)
        val togetherWeight = if (inCombat || mustFlee) PAIR_W_TOGETHER_COMBAT else PAIR_W_TOGETHER
        val together = -getRange(InfluenceMap.cell(ax, ay), InfluenceMap.cell(bx, by)) * togetherWeight
        // пара НЕ разбегается (spread=false) — ей нужно держаться вместе для взаимного хила
        return scoreCell(ax, ay, target, pairFlow, standoff, mustFlee, aggressive, inCombat, breaching, false, enemyCreeps, allies, meleeEnemies) +
            scoreCell(bx, by, target, pairFlow, standoff, mustFlee, aggressive, inCombat, breaching, false, enemyCreeps, allies, meleeEnemies) + together
    }

    /** Оценка одной клетки. Наступление: подойти на standoff к цели (по реальному пути; обычно
     *  дистанция стрельбы 3, в осаде 1 — вплотную к горловине); mustFlee: прочь от урона.
     *  Свой рампарт — защищённая огневая позиция: урона и мили-угрозы на нём нет, в бою клетка премируется. */
    private fun scoreCell(x: Int, y: Int, target: Position, pairFlow: IntArray, standoff: Int, mustFlee: Boolean, aggressive: Boolean, inCombat: Boolean, breaching: Boolean, spread: Boolean, enemyCreeps: List<Creep>, allies: List<Creep>, meleeEnemies: List<Creep>): Double {
        // дистанция до цели по реальному пути (через flow). Стремимся на standoff от неё.
        // «Прибыли» проверяем по ЧЕБЫШЕВУ: стрельба работает сквозь стены, так что в радиусе
        // standoff цель уже под огнём; flow же измеряется в ТИКАХ (болото=5) и тянул бы крипа
        // лезть глубже, когда стрелять можно с места.
        val flowDist = pairFlow[x * 100 + y]
        val cheb = getRange(InfluenceMap.cell(x, y), target)
        val firePenalty = when {
            cheb <= standoff -> (standoff - cheb) * 0.5 // дострелим отсюда — лёгкий штраф за подползание
            flowDist < 0 -> 1000.0 // недостижимо и не дострелить — очень плохо
            flowDist > standoff -> (flowDist - standoff).toDouble()
            else -> (standoff - flowDist) * 0.5
        }
        // штраф за скученность: держим интервал, чтобы охотники шли широким фронтом, а не колонной
        // (которую враг бьёт по одному). Клетку-кандидат (где стоит свой) из счёта исключаем.
        val separation = if (spread) {
            allies.count { (it.x != x || it.y != y) && getRange(InfluenceMap.cell(x, y), it) <= SEPARATION_RADIUS } * PAIR_W_SPREAD
        } else 0.0
        // ВНЕ БОЯ — чистый марш по полю потока к цели + интервал. «Мягкие» термы (влияние своих, бонус
        // близости к лекарям через netDamage) иначе притягивают крипа к куче и не дают переднему шагнуть.
        if (!mustFlee && !inCombat) return -firePenalty * PAIR_W_DIST - separation

        val onRampart = x * 100 + y in myRampartCells
        val damage = InfluenceMap.netDamageAt(x, y, enemyCreeps, allies) // чистый урон с учётом нашего хила (на рампарте 0)
        // штраф за зону удара мили-врага — держим дистанцию, не подпускаем (ranged-преимущество).
        // при наступлении с перевесом смягчаем (×AGGRO_MELEE_FACTOR), иначе штраф парализует и атаку.
        // на своём рампарте мили не страшен — удары уходят в рампарт.
        val meleeWeight = if (aggressive) PAIR_W_MELEE * AGGRO_MELEE_FACTOR else PAIR_W_MELEE
        val meleeThreat = if (onRampart) 0.0 else meleeEnemies.count { getRange(InfluenceMap.cell(x, y), it) <= MELEE_KEEP_RANGE } * meleeWeight
        val rampartBonus = if (onRampart) PAIR_W_RAMPART else 0.0
        // болото в бою — ловушка: ходишь раз в 5 тиков, кайт невозможен. На своём рампарте неважно
        // (там и так не нужно убегать — урон уходит в рампарт). При ПРОРЫВЕ (не в бегстве) штраф
        // снят: к цели ведёт только болотный пояс (штурм чужой базы / возврат в свой карман),
        // решение «идём» уже принято — иначе −40 болота против +10 за шаг приближения
        // парализуют крипа у первой болотной клетки горловины.
        val breach = breaching && !mustFlee
        val swampPenalty = if (!onRampart && !breach && DistanceMap.isSwamp(x, y)) PAIR_W_SWAMP else 0.0
        if (mustFlee) {
            // спасаемся: меньше урона и дальше от врагов
            val proximity = enemyCreeps.sumOf { 1.0 / (getRange(InfluenceMap.cell(x, y), it) + 1) }
            return -damage * 10.0 - proximity * 5.0 - meleeThreat + rampartBonus - swampPenalty
        }
        val influence = InfluenceMap.influenceAt(x, y, allies, enemyCreeps)
        // бонус за исходящий урон: откуда крип достаёт больше врагов — в бою это доминанта.
        // При ПРОРЫВЕ вес режем ниже цены шага: цель прорыва — спавн/враг в глубине, а не
        // обстрел кайтящей приманки с места (по ней стреляем на ходу).
        val outgoingWeight = if (breach) PAIR_W_OUTGOING_BREACH else PAIR_W_OUTGOING
        val outgoing = outgoingValue(x, y, enemyCreeps)
        return -firePenalty * PAIR_W_DIST - damage * PAIR_W_DAMAGE + influence * PAIR_W_INFLUENCE +
            outgoing * outgoingWeight - meleeThreat - separation + rampartBonus - swampPenalty
    }

    /** Эффективный исходящий урон ranged-крипа с клетки (в долях мощи): сколько врагов и насколько достаёт. */
    private fun outgoingValue(x: Int, y: Int, enemyCreeps: List<Creep>): Double {
        var massValue = 0.0
        var anyInRange = false
        for (enemy in enemyCreeps) {
            val d = getRange(InfluenceMap.cell(x, y), enemy)
            if (d <= RANGED_RANGE) {
                anyInRange = true
                massValue += InfluenceMap.rangedRate(d)
            }
        }
        if (!anyInRange) return 0.0
        return maxOf(massValue, 1.0) // массовая или одиночная — что выгоднее (как при стрельбе)
    }

    /** Фланг охотника по чётности id: чётный — левый, нечётный — правый.
     *  id в рантайме ЧИСЛО (хоть и типизирован String) — чётность берём как у числа,
     *  строковые операции (.last()/template) на нём падают (TypeError: not a CharSequence). */
    private fun isLeftFlank(creep: Creep) = creep.id.unsafeCast<Double>() % 2.0 == 0.0

    /** Защитник флага: без HEAL, RANGED первой частью (порядок частей отличает его от охотников). */
    private fun isFlagDefender(creep: Creep) =
        creep.body.none { it.type == HEAL } && creep.body.firstOrNull()?.type == RANGED_ATTACK

    /** Клетка проходима для большого крипа: в границах, не стена/чужой рампарт, без вражеского крипа. */
    private fun passableForBig(x: Int, y: Int, blockedSet: Set<Int>, enemyPositions: Set<Int>): Boolean {
        if (x < 0 || y < 0 || x > 99 || y > 99) return false
        val key = x * 100 + y
        if (key in blockedSet || key in enemyPositions) return false
        return getTerrainAt(InfluenceMap.cell(x, y)) != TERRAIN_WALL
    }

    /**
     * Шаг маленького охотника: гонится за ближайшим вражеским крипом (или спавном, если крипов нет),
     * держит дистанцию стрельбы (кайтит при сближении), стоит когда уже в радиусе.
     */
    private fun tooClose(creep: Creep, nearbyEnemies: List<Creep>): Boolean {
        val closest = nearbyEnemies.minByOrNull { creep.getRangeTo(it) } ?: return false
        return creep.getRangeTo(closest) < RANGED_RANGE
    }

    /** Есть ли у крипа хоть одна живая RANGED_ATTACK-часть (может ли он вообще стрелять). */
    private fun hasRanged(creep: Creep) = creep.body.any { it.type == RANGED_ATTACK && it.hits > 0 }

    /** Суммарная боевая мощь группы: урон (мили+дальний) + лечение с весом HEAL_POWER_WEIGHT. */
    private fun powerOf(creeps: List<Creep>): Double =
        creeps.fold(0.0) { acc, c -> val p = InfluenceMap.profileOf(c); acc + p.melee + p.ranged + p.heal * HEAL_POWER_WEIGHT }

    /** Мощь ВРАЖЕСКОЙ группы глазами кайтящего ranged-отряда: мили, не прижавший никого из
     *  `allies` вплотную, дисконтируется (он сначала должен догнать). Без этого одиночный жирный
     *  милишник у спавна «на бумаге» перевешивал пару и блокировал штурм, хотя при заходе он
     *  обязан пересечь наш огонь и умирает, никого не достав. */
    private fun enemyPowerOf(creeps: List<Creep>, allies: List<Creep>): Double =
        creeps.fold(0.0) { acc, c ->
            val p = InfluenceMap.profileOf(c)
            val pinning = p.melee > 0.0 && allies.any { getRange(c, it) <= MELEE_KEEP_RANGE }
            acc + p.melee * (if (pinning) 1.0 else MELEE_KITE_DISCOUNT) + p.ranged + p.heal * HEAL_POWER_WEIGHT
        }

    /** Вход фланга: крайняя по x компонента входов (лево/право). null — входы не найдены. */
    private fun flankEntrance(enemySpawn: StructureSpawn, left: Boolean): IntArray? {
        val entrances = DistanceMap.enemyBaseEntrances(enemySpawn)
        if (entrances.isEmpty()) return null
        val byX = entrances.sortedBy { component -> component.sumOf { it / 100 } / component.size }
        return if (left) byX.first() else byX.last()
    }

    /** Внешняя горловина входа: СЕРЕДИНА наружной кромки (клеток, касающихся сухой земли вне
     *  кармана). Именно середина: вход бывает широким, и пара у его края покрывает огнём только
     *  часть выхода — по центру простреливается весь. */
    private fun entranceMouth(component: IntArray): Position {
        val edge = DistanceMap.entranceOuterEdge(component) ?: component
        val cx = edge.sumOf { it / 100 } / edge.size
        val cy = edge.sumOf { it % 100 } / edge.size
        val mouth = edge.minByOrNull { maxOf(abs(it / 100 - cx), abs(it % 100 - cy)) }!!
        return InfluenceMap.cell(mouth / 100, mouth % 100)
    }

    /** Точка осады для пары: внешняя горловина ЦЕНТРАЛЬНОГО входа (ближайшего по x к спавну). */
    private fun siegePoint(enemySpawn: StructureSpawn): Position {
        val entrances = DistanceMap.enemyBaseEntrances(enemySpawn)
        if (entrances.isEmpty()) return enemySpawn
        val central = entrances.minByOrNull { component -> abs(component.sumOf { it / 100 } / component.size - enemySpawn.x) }!!
        return entranceMouth(central)
    }

    /**
     * Первый шаг к цели (с остановкой на дистанции `range`) в обход опасных клеток.
     * Берём первый шаг даже у неполного пути (incomplete) — для далёких целей через стены
     * searchPath часто не достраивает путь целиком, но первый шаг всё равно ведёт в нужную сторону.
     */
    private fun pathStep(creep: Creep, target: Position, range: Int, dangerMatrix: CostMatrix): Position? {
        val goal = SearchGoal(pos = target, range = range)
        val result = searchPath(creep, goal, SearchPathOptions(costMatrix = dangerMatrix))
        return result.path.firstOrNull()
    }

    /**
     * Первый шаг к (возможно недостижимой) цели, только если путь РЕАЛЬНО приближает.
     * searchPath для недостижимой цели ведёт к ближайшей доступной точке — смотрим на КОНЕЦ
     * пути: он строго ближе к цели, чем мы сейчас, — идём (даже если первый шаг боковой,
     * в обход препятствия); не ближе — мы уже на кольце ближайшего подхода, стоим.
     * Сравнение по первому шагу не годится в обе стороны: «шаг строго ближе» замораживал
     * охотника перед боковым обходом стены, а безусловный шаг гонял соседей вечным свопом.
     */
    private fun pathStepCloser(creep: Creep, target: Position, range: Int, dangerMatrix: CostMatrix): Position? {
        val goal = SearchGoal(pos = target, range = range)
        val result = searchPath(creep, goal, SearchPathOptions(costMatrix = dangerMatrix))
        val dest = result.path.lastOrNull() ?: return null
        if (getRange(dest, target) >= creep.getRangeTo(target)) return null
        return result.path.firstOrNull()
    }

    /** Первый шаг прочь от врагов (flee) в обход опасных клеток. */
    private fun fleeStep(creep: Creep, enemies: List<Creep>, dangerMatrix: CostMatrix): Position? {
        if (enemies.isEmpty()) return null
        val goals = enemies
            .map { e -> SearchGoal(pos = InfluenceMap.cell(e.x, e.y), range = RANGED_RANGE) }
            .toTypedArray()
        val result = searchPath(creep, goals, SearchPathOptions(flee = true, costMatrix = dangerMatrix))
        return result.path.firstOrNull()
    }
}
