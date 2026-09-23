package season4.painandgain

import screeps.api.Creep
import screeps.api.Position

/**
 * ПАМЯТЬ МЕЖДУ ТИКАМИ (v239, этап 4 переработки): владелец того, что живёт дольше тика, — кольца историй (хиты, центры,
 * дистанции, размен), защёлки крипов и кэши полей. С v459–v460 рядом с ней ещё два владельца уровня 1: `Prev` (ниже в этом файле —
 * величины прошлого тика, явно) и `Squads` (`Squads.kt` — память назначений: хранители, флаги бегунов, погоня, отряды; до v460 она жила
 * здесь). Перенесено из `PainAndGain.kt` без изменений; целевое правило плана — стадии читают память, а пишет её один
 * `Memory.commit` после исполнения (раздел 2 docs/pain-and-gain-rework.md); пока записи остаются там, где были.
 * Объект в списке починки после оборванного тика (см. AbortRepair).
 */
/**
 * ЗАЩЁЛКА (v443, план архитектуры, 4.2 и этап 2): гистерезис «вошёл по одному условию, вышел по другому». До этого он был
 * выписан руками пять раз, и запись в память пряталась внутри выражения `val x = … run { … Memory.X.add(id) … }`.
 * Это ВИД поверх множества, а не владелец: множество остаётся полем `Memory`, потому что `AbortRepair.repairFields` чинит
 * только таблицы и множества среди собственных полей объекта-владельца — коллекция, спрятанная внутрь `Latch`, выпала бы
 * из починки после оборванного тика. Починка идёт на месте, так что ссылка вида остаётся верной.
 */
internal class Latch(private val ids: MutableSet<String>) {
    /** Состояние ПОСЛЕ обновления. Внутри: выходит по [exit], иначе остаётся; снаружи: входит по [enter]. Вышедший в этом же
     *  обновлении не входит обратно, даже если [enter] истинно. */
    fun update(id: String, enter: Boolean, exit: Boolean): Boolean =
        if (id in ids) { if (exit) { ids.remove(id); false } else true }
        else if (enter) { ids.add(id); true }
        else false

    /** Без гистерезиса в самой защёлке: состояние равно условию (гистерезис — в пороге, который условие читает). */
    fun set(id: String, on: Boolean): Boolean = update(id, enter = on, exit = !on)

    operator fun contains(id: String) = id in ids
}

internal object Memory {
    // ТАБЛИЦЫ «ПО ID СВОЕГО БОЙЦА» (v459, второй шаг архитектуры, этап 6.4). Таблица, объявленная через [perCreepSet] / [perCreepMap],
    // чистится от мёртвых id ОДНОЙ [prune] — до v459 это были семь одинаковых строк `retainAll` в хвосте стратега, и новая таблица
    // требовала восьмой, которую не держало ничто. Реестр стоит ВЫШЕ таблиц: поля объекта инициализируются по порядку текста. Сами
    // таблицы остаются прямыми полями `Memory` — починка после оборванного тика обходит только их; реестр — список ссылок, его не чинят.
    // Чистки, которые на деле РЕШЕНИЯ (`detachedIds` — живой, вооружённый, подвижный; `runnerFlag` — только нынешние бегуны; гарнизон,
    // курьер), сюда не входят и стоят на своих местах. НЕ чистит никто: `cmdDetach` в бою — находка 2.8 п. 10 плана (дефект 4).
    // `rotatingIds` и `rotateSince` — здесь с v463 (дефект 2): мёртвые id копились в них до конца матча, а сам набор читает мир.
    private val creepSets = ArrayList<MutableSet<String>>()
    private val creepMaps = ArrayList<MutableMap<String, *>>()
    private fun perCreepSet(): HashSet<String> = HashSet<String>().also { creepSets.add(it) }
    private fun <V> perCreepMap(): HashMap<String, V> = HashMap<String, V>().also { creepMaps.add(it) }

    /** Снять записи погибших бойцов со всех таблиц «по id своего бойца». Место вызова в тике — хвост стратега (`StrategyThreats`): оно
     *  входит в тождество, размеры таблиц печатают приборы. */
    fun prune(living: List<Creep>) {
        for (t in creepSets) t.retainAll { id -> living.any { it.id == id } }
        for (t in creepMaps) t.keys.retainAll { id -> living.any { it.id == id } }
    }

    /** ПОСТУРА ПРОШЛОГО ТИКА (v446, план архитектуры, 4.6): меры мира читают решение стратега, принятое тиком раньше, — и до
     *  v446 читали его полем `posture` объекта-оркестратора, про которое надо было ЗНАТЬ, что стратег его в этом тике ещё не
     *  переписал. Снимок ставит оркестровка непосредственно перед `armyMeasures`; значение то же самое. */
    var prevPosture: Posture = Posture.HOLD
    val chasedIds = HashSet<String>()
    /** Какой замысел выбрал перебор (этап 8). Перебор из пяти стоит пяти раздач за тик; если гистограмма
     *  сосредоточена на одном-двух, платить за него незачем — и это решается числом, а не мнением. */
    val intentHist = HashMap<String, Int>()
    /** ДОЛЯ ТИКОВ, ГДЕ ПРОГНОЗ ОБЕЩАЕТ ВЫИГРАННЫЙ РАЗМЕН (v398), сглаженная экспоненциально. Одно-тиковая дельта
     *  шумит, а различает исход именно доля: замер 16 игр против MetalicaX#13 дал 0,59 в победах против 0,30 в
     *  поражениях (семь побед из восьми выше 0,49, шесть поражений из восьми ниже 0,31). Живёт в памяти, потому
     *  что окно длиннее тика; объект в списке починки после оборванного тика. */
    var simdShare = 0.0
    val approachingIds = HashSet<String>()
    val approachHistory = HashMap<String, ArrayDeque<Pair<Int, Int>>>()
    val enemyPrevCell = HashMap<String, Int>()
    /** id врага -> тик его последнего сдвига (см. stationary). */
    val enemyLastMove = HashMap<String, Int>()
    /** Центр вооружённой армии и клетки врагов за последние CHASE_WINDOW тиков (см. evasive). */
    val ourCentroidHist = ArrayDeque<Int>()
    val enemyCellHist = HashMap<String, ArrayDeque<Int>>()
    /** Кто сейчас идёт к авангарду (гистерезис сбора, см. rallyTo). */
    val rallyingIds = perCreepSet()
    val rallyingLatch = Latch(rallyingIds)
    /** Кто на прошлом тике шёл на личную цель (engage): такого не ждут по сплочению. */
    val engagingIds = perCreepSet()
    val engagingLatch = Latch(engagingIds)
    val holdSince = perCreepMap<Int>()
    val impatientIds = perCreepSet()
    val impatientLatch = Latch(impatientIds)
    /** Дистанция центра боевых врагов до нашего за последние тики — темп сближения для запаса выхода. */
    val enemyDistHist = ArrayDeque<Int>()
    val hisCentHist = ArrayDeque<Int>()   // клетка центра его вооружённых за APPROACH_WINDOW (v113: ПОДХОДИТ ОН, не мы)
    val ourCentHist = ArrayDeque<Int>()   // ...и клетка нашего центра за то же окно, в те же тики (v222, см. USE_RETREAT_BY_HIS_STEP)
    val idleRunnerIds = HashSet<String>()         // бегуны без цели в этом тике (RESERVE; см. USE_DETACH_IDLE_RECALL)
    val enemyCentHist = ArrayDeque<Int>()         // клетка центра его армии по тикам погони (рядом с armyDistHist)
    /** РАЗМЕН ЗА ОКНО (v216): снимок `enemyDamageTaken - ourDamageTaken` за последние LEDGER_WINDOW тиков.
     *  Матчевая сумма (`exchangeLedger`) на вопрос «проигрываем ли мы размен ПРЯМО СЕЙЧАС» не отвечает: в ней
     *  господствует история. Окно не назначено, а взято существующее: `STALL_TICKS` — тот самый срок, которым
     *  файл уже определяет «размен был недавно» (`exchangeRecent`), то есть длительность одного обмена линией. */
    val ledgerHist = ArrayDeque<Int>()
    /** ...и обе половины отдельно: признак отхода спрашивает ОТНОШЕНИЕ потерь, а не разность. */
    val ourLostHist = ArrayDeque<Int>()
    val hisLostHist = ArrayDeque<Int>()
    /** Снимки `ourDamageTaken` за последние GROUP_WINDOW тиков — из них берётся ПИКОВОЕ окно урона по нам за матч
     *  (v534, см. USE_UNWIPEABLE_OPENS). Окно то же, что у groupSafe: длительность одного сближения. */
    val takenHist = ArrayDeque<Int>()
    val aggressiveIds = perCreepSet()
    val aggressiveLatch = Latch(aggressiveIds)
    val lastHits = perCreepMap<Int>()
    val theirsHist = ArrayDeque<Double>()   // его мощь против армии за MEASURE_WINDOW тиков (см. USE_CORE_MEASURE_WINDOW)
    val lastCell = perCreepMap<Int>()
    /** Клетки наших боевых прошлого тика (v579, см. USE_NO_LONERS_VS_HUNTER): по ним признак «он добил нашего одиночку»
     *  узнаёт, где погиб пропавший и сколько своих было рядом. Переписывается целиком каждый тик. */
    val ourPrevCells = HashMap<String, Int>()
    /** Патруль приманки на ходу (v598, см. USE_MOVING_BAIT): [сторона отрезка ±1, клетка медианы приманки прошлого тика,
     *  тиков подряд без сдвига медианы, тиков приманки без контакта (см. BAIT_PATIENCE), 1 — терпение кончилось до конца матча,
     *  тиков раскладки подряд (v599, см. BAIT_SETUP)]. */
    val baitPatrol = IntArray(6)
    /** Стоящие гарнизоны (v600, см. USE_STANDING_GARRISONS): крип -> клетка флага его отряда; раскладка один раз за матч
     *  (`Garrisons.assign`), посты внутри отряда не закреплены. */
    val garrisonFlag = HashMap<String, Int>()
    /** Снять лагерь (v605, см. USE_CAMP_BREAK): крип -> отряд, крип -> флаг, который отряд держит в конце; этапы
     *  [этап, тик начала этапа, тиков его лагеря подряд (на этапах — путь отряда в начале этапа), флаг первого, второго,
     *  третьего отряда, клетка засады (v615), тик прихода в засаду (v615)]. */
    val garrisonSquad = HashMap<String, Int>()
    val garrisonHome = HashMap<String, Int>()
    val campBreak = IntArray(8)
    /** Отряд-налётчик ушёл к своим от его группы (v616, см. USE_ALL_RAIDERS): гистерезис опасности по отряду. */
    val raidRefuge = BooleanArray(3)
    /** Отряд-налётчик идёт при нём вместе с соседом (v616): на этом тике — к общей цели. */
    val raidTogether = BooleanArray(3)
    /** Крипы отряда, отданные обычным веткам боя (v620, шар в бою): гарнизонный шаг их не ведёт. */
    val garrisonReleased = HashSet<String>()
    val lastFlagOwner = HashMap<String, Int>()
    // ---------- кэши на тик ----------
    val flowCache = HashMap<Int, IntArray>()
    val flowCacheTick = HashMap<Int, Int>()   // тик расчёта поля (см. FLOW_TTL)
    val rotatingIds = perCreepSet()   // бойцы в ротации (см. ROTATE_OUT); под чисткой мёртвых с v463 (дефект 2)
    val rotatingLatch = Latch(rotatingIds)
    val rotateSince = perCreepMap<Int>()   // тик выхода в ротацию (замер длительности, см. USE_ROTATE_OVER_SLOT); чистится с v463
    val enemyHitsHist = ArrayDeque<Int>()         // сумма хитов врага за STALL_TICKS тиков (чистый урон)
    val marchHist = ArrayDeque<Int>()             // клетка центра вооружённой массы за MARCH_STALL_TICKS тиков
    val armyDistHist = ArrayDeque<Int>()          // дистанция между центрами армий за CHASE_WINDOW тиков (см. третья сетка)
    val ourHitsHist = ArrayDeque<Int>()           // и наша: бьют только нас — это бой, не простой
    val meleeDistHist = ArrayDeque<Int>()         // дистанция их мили до наших вооружённых за окно терпения (см. PRESS_CLOSING)
    val centreDistHist = ArrayDeque<Int>()        // дистанция между центрами вооружённых армий за то же окно (см. standingNow)
    val approachHist = ArrayDeque<Int>()          // то же расстояние, но пишется и БЕЗ контакта (см. enemyApproaching)
    val stalemateOurHist = ArrayDeque<Int>()      // сумма наших хитов за окно (см. stalemateNow)
    val stalemateHisHist = ArrayDeque<Int>()      // и его — чтобы отличить пат от проигранного размена
    val hisTouchHist = ArrayDeque<Int>()           // то же по ЕГО мили: цена флага A3 держится на обеих долях
    val touchHist = ArrayDeque<Int>()              // доля наших мили, стоявших вплотную к врагу, по тикам контакта
    val ourCentreHist = ArrayDeque<Int>()        // клетка центра наших вооружённых за окно терпения (см. USE_STANDING_LINE_HOLDS)
    val lastArmedRange = HashMap<String, Int>()   // враг → дистанция до ближайшего нашего боеспособного тик назад (см. threatOf)
    val packTicksCache = HashMap<Int, Pair<IntArray, Map<String, Int>>>()   // клетка флага → (поле, id врага → тики пути), см. packAt
    val orderPrev = HashMap<String, Position>()   // приказы прошлого тика — для проверки исполнения (v167)
    /** Мили -> зажатый враг, к которому его поставил приказ (v264): на следующем тике прибор pin= сверяет, стоял ли
     *  зажатый вплотную, то есть состоялся ли удар. */
    val pinWatch = HashMap<String, String>()
    /** Ротация по его фокусу (v275, см. rotateByFocus): кто ушёл по ней; сверка двух правил его выбора цели с фактом. */
    val rotByFocus = HashSet<String>()
    /** Раненые, вышедшие из его зоны (v285, см. stepOutWounded): держатся вне её, пока не вылечены ниже порога. */
    val stepOutIds = HashSet<String>()
    /** Хиты каждого нашего крипа на прошлом тике и урон по «группе» за последние GROUP_WINDOW тиков (v298, см. groupSafe). */
    val groupHitsPrev = HashMap<String, Int>()
    /** Тик, когда крип в последний раз потерял хиты (v544, см. USE_KEEPER_LEAVES_ON_REAL_HIT). */
    val lastHurtAt = HashMap<String, Int>()
    val groupDmgHist = ArrayDeque<Int>()
    /** Его флаги и из них занятые его крипом за последние GROUP_WINDOW тиков, упаковано как флаги * 8 + занятые (v302). */
    val flagSitHist = ArrayDeque<Int>()
    /** Цель загона (v331): id его крипа, за которым идут две группы ядра, пока он жив и один. */
    var huntQuarry: String? = null
    /** Наше лечение, назначенное каждому своему за прошлый тик (v276): полученный урон = потеря + это лечение. */
    val healGiven = HashMap<String, Int>()
    /** Уходящий раненый -> лекарь, которому приказана клетка рядом с ним (v276, прибор meet=): сверка на следующем тике. */
    val meetWatch = HashMap<String, String>()
    val fracHits = ArrayDeque<Boolean>()
    val addrHits = ArrayDeque<Boolean>()
    var fracPrev: String? = null
    var addrPrev: String? = null
    val lastPlan = HashMap<String, Int>()   // крип → клетка прошлого плана (см. planFight: память расстановки)
    /** Контакт и численность армии на прошлом тике — события для стратега (v242). */
    var contactPrev = false
    var armyPrev = 0
    /** Кандидат постуры прошлого тика и тик, с которого он предлагается без перерыва (v250, см. Strategist.decide). */
    var postureCandidate: Posture? = null
    var candidateSince = 0
}

/**
 * ПРОШЛЫЙ ТИК — ЯВНО (v459, второй шаг архитектуры, этап 6.2). Величина, которую стадия читает РАНЬШЕ, чем её писатель отработал в
 * этом тике, несёт значение прошлого тика. Пока такие величины были членами синглтона, по тексту этого не было видно: то же голое имя
 * значило «сегодня» ниже писателя и «вчера» выше него. Сегодняшнее значение — поле носителя стадии (порядок стережёт компилятор),
 * вчерашнее — здесь; копирует `RememberTick`, последняя стадия тика, и только в тике, где армия считала меры (иначе значения
 * остаются прежними — как оставались члены). Объект в списке починки; уровень 1 — читать его может любая стадия.
 */
internal object Prev {
    /** Размен глазами ворот захвата — см. [ExchangeView]; читают ворота, вызванные из `runRunners`, и `readSignals`. */
    var exchange = ExchangeView(stalled = false, exchangeLive = false, ourLostWindow = 0)
    /** Размен и потери его стороны за окно — для строки `t=` (поле `ledgerw`), которая печатается и в тике без армии. */
    var ledgerWindow = 0
    var hisLostWindow = 0
    /** Темп сближения его армии с нашей (пишет `StrategyObjective`): вчерашний читают `readSignals` и бегуны (`exitMargin`, `runnerEscape`). */
    var approachRate = 0.0
    /** Противник тих `FARMER_QUIET` с первой досягаемости (пишет `StrategyDetach`): вчерашний читает горизонт ценности флага у бегунов. */
    var farmerQuietNow = false
    /** Доля мили, достающих врага, за окно контакта — наша и его (пишет `StanceWindows`): вчерашнюю читает `StrategyDetach`, стоящий раньше. */
    var touchShare = 1.0
    var hisTouchShare = 1.0
    /** ...и ПОСЛЕДНЯЯ доля за ПОЛНОЕ окно — вход меры мощи (`powerOf`, v433; сюда — v474, дефект 12 постановки). До v474 её писала
     *  стойка посреди тика голым скаляром Power.kt, и мощь в одном тике считалась двумя долями: бегуны, меры, стратег и цели —
     *  по вчерашней, командир и тактик — по сегодняшней. Пишет `RememberTick`, значение одно на тик для всех читателей. */
    var touchShareLast = 1.0
    var hisTouchShareLast = 1.0
    /** Режим командира (пишет `StrategyDecide`) — для строки `t=` (поле `mode`), которая печатается и в тике без армии. */
    var cmdMode = CmdMode.MARCH
}
