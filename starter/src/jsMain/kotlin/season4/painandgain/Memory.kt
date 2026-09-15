package season4.painandgain

import screeps.api.Creep
import screeps.api.Position

/**
 * ПАМЯТЬ МЕЖДУ ТИКАМИ (v239, этап 4 переработки): единственный владелец того, что живёт дольше тика, — кольца историй
 * (хиты, центры, дистанции, размен), память назначений (хранители, флаги бегунов, погоня, отряд), защёлки крипов и кэши
 * полей. Перенесено из `PainAndGain.kt` без изменений; целевое правило плана — стадии читают память, а пишет её один
 * `Memory.commit` после исполнения (раздел 2 docs/pain-and-gain-rework.md); пока записи остаются там, где были.
 * Объект в списке починки после оборванного тика (см. AbortRepair).
 */
internal object Memory {
    /** Кто из наших назначен добить какой остов: id нашего -> id остова. Считается РАЗ в тик, до перебора
     *  замыслов, иначе пять прогонов раздачи дали бы пять разных отрядов. */
    val chaseOf = HashMap<String, String>()
    /** ...и сам объект цели. Искать остов в combatEnemies/armedEnemies НЕЛЬЗЯ: он по определению не входит ни в
     *  один из них — это ровно та невидимость, из-за которой предмет и возник. Первая редакция погони искала там,
     *  фокус не назначался никогда, и стенд показал 13 назначений при нуле добитых. */
    val chaseTarget = HashMap<String, Creep>()
    val chasedIds = HashSet<String>()
    /** Какой замысел выбрал перебор (этап 8). Перебор из пяти стоит пяти раздач за тик; если гистограмма
     *  сосредоточена на одном-двух, платить за него незачем — и это решается числом, а не мнением. */
    val intentHist = HashMap<String, Int>()
    val approachingIds = HashSet<String>()
    val approachHistory = HashMap<String, ArrayDeque<Pair<Int, Int>>>()
    val enemyPrevCell = HashMap<String, Int>()
    /** id врага -> тик его последнего сдвига (см. stationary). */
    val enemyLastMove = HashMap<String, Int>()
    /** Центр вооружённой армии и клетки врагов за последние CHASE_WINDOW тиков (см. evasive). */
    val ourCentroidHist = ArrayDeque<Int>()
    val enemyCellHist = HashMap<String, ArrayDeque<Int>>()
    /** Кто сейчас идёт к авангарду (гистерезис сбора, см. rallyTo). */
    val rallyingIds = HashSet<String>()
    /** Кто на прошлом тике шёл на личную цель (engage): такого не ждут по сплочению. */
    val engagingIds = HashSet<String>()
    val holdSince = HashMap<String, Int>()
    val impatientIds = HashSet<String>()
    /** Дистанция центра боевых врагов до нашего за последние тики — темп сближения для запаса выхода. */
    val enemyDistHist = ArrayDeque<Int>()
    val hisCentHist = ArrayDeque<Int>()   // клетка центра его вооружённых за APPROACH_WINDOW (v113: ПОДХОДИТ ОН, не мы)
    val ourCentHist = ArrayDeque<Int>()   // ...и клетка нашего центра за то же окно, в те же тики (v222, см. USE_RETREAT_BY_HIS_STEP)
    val detachedIds = HashSet<String>()           // отряды: вооружённые, зачисленные в бегуны (см. USE_DETACH)
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
    val aggressiveIds = HashSet<String>()
    val lastHits = HashMap<String, Int>()
    val theirsHist = ArrayDeque<Double>()   // его мощь против армии за MEASURE_WINDOW тиков (см. USE_CORE_MEASURE_WINDOW)
    val lastCell = HashMap<String, Int>()
    /** id захватчика -> id флага (липкое назначение). */
    val runnerFlag = HashMap<String, String>()
    val lastFlagOwner = HashMap<String, Int>()
    // ---------- кэши на тик ----------
    val flowCache = HashMap<Int, IntArray>()
    val flowCacheTick = HashMap<Int, Int>()   // тик расчёта поля (см. FLOW_TTL)
    val rotatingIds = HashSet<String>()   // бойцы в ротации (см. ROTATE_OUT)
    val rotateSince = HashMap<String, Int>()   // тик выхода в ротацию (замер длительности, см. USE_ROTATE_OVER_SLOT)
    val keeperIds = HashMap<String, String>()   // хранитель флага → id флага (см. KEEP_RANGE)
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
    val cmdDetach = HashSet<String>()      // кого командир отправил за флагами (v160, режимы RACE и MARCH)
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
    val groupDmgHist = ArrayDeque<Int>()
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
