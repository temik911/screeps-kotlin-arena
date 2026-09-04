package season4.painandgain

import screeps.api.ATTACK
import screeps.api.ATTACK_POWER
import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.CARRY_CAPACITY
import screeps.api.CostMatrix
import screeps.api.Creep
import screeps.api.EFF_ATTACK_MODIFIER
import screeps.api.EFF_DAMAGE_TAKEN_MODIFIER
import screeps.api.EFF_HEAL_MODIFIER
import screeps.api.EFF_RANGED_ATTACK_MODIFIER
import screeps.api.HEAL
import screeps.api.HEAL_POWER
import screeps.api.MOVE
import screeps.api.Position
import screeps.api.RANGED_ATTACK
import screeps.api.RANGED_ATTACK_POWER
import screeps.api.RANGED_HEAL_POWER
import screeps.api.RESOURCE_ENERGY
import screeps.api.SearchGoal
import screeps.api.SearchPathOptions
import screeps.api.TERRAIN_SWAMP
import screeps.api.TERRAIN_WALL
import screeps.api.TOUGH
import screeps.api.WORK
import screeps.api.arenaInfo
import screeps.api.get
import screeps.api.getObjectsByPrototype
import screeps.api.getRange
import screeps.api.getTerrainAt
import screeps.api.getTicks
import screeps.api.searchPath
import screeps.api.season4.FLAG_TYPES
import screeps.api.season4.MAX_SCORE_PER_TICK
import screeps.api.season4.ScoreFlag
import screeps.api.season4.TICKS_LIMIT
import screeps.api.structures.StructureRampart
import screeps.api.structures.StructureSpawn
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
            PainAndGain.tick()
        }
    } catch (t: Throwable) {
        // страховка: даже если source-map-обработчик упадёт, логируем ошибку и не роняем тик
        println("loop error: ${t.message}")
        println(t.stackTraceToString())
    }
}

/**
 * Season 4 «Pain and Gain» (basic). Первая версия — до первого живого матча.
 *
 * Правила (описание арены в клиенте, 04.09.2026): у каждого игрока ЗАРАНЕЕ ВЫДАННАЯ армия из 14
 * крипов — ни спавна, ни стройки, ни энергии, ни замены погибшим. По карте семь нейтральных
 * [ScoreFlag]: крип захватывает флаг, ВСТАВ на его клетку; захваченный флаг каждый тик приносит
 * владельцу очки и вешает глобальный дебафф на ВСЮ его армию (флаги одного типа складываются):
 *
 *  | флаг              | шт | очки/тик | эффект                      | один  | два  |
 *  |-------------------|----|----------|-----------------------------|-------|------|
 *  | Vulnerability     | 1  | 5        | входящий боевой урон        | ×1.1  | —    |
 *  | Heal reduction    | 2  | 4        | лечение                     | ×0.75 | ×0.5 |
 *  | Attack reduction  | 2  | 3        | удар ATTACK                 | ×0.8  | ×0.6 |
 *  | Ranged reduction  | 2  | 3        | выстрел RANGED_ATTACK       | ×0.8  | ×0.6 |
 *
 * Итого 25 очков/тик (MAX_SCORE_PER_TICK). 2000 тиков; побеждает больший счёт, равный — ничья;
 * досрочно — уничтожение армии противника (победа независимо от счёта) или недосягаемый отрыв.
 *
 * Оси решения, все из состояния, не из подгонки под карту:
 *  - счёт: безоружные крипы — захватчики, идут по флагам по выигрышу очков за тик хода; армия
 *    берёт флаги по пути и рейдом. Флаг берётся, только если армия С ЕГО дебаффом не слабее
 *    армии врага (маргинальная цена по Ланчестеру с эффектами обеих сторон) — или если по
 *    прогнозу счёта мы проигрываем: тогда очки важнее силы;
 *  - бой: армия одной группой; постура — ДОБИТЬ (уничтожение армии врага выигрывает матч:
 *    перевес PUSH_RATIO по мощи с эффектами), РЕЙД/ЗАЧИСТКА флага, ПОСТ у своих флагов, ОТХОД
 *    (враг сильнее в RETREAT_RATIO и рядом — армию без замены не разменивают). Внутри — локальный
 *    перевес, цена боя в запасе хода, сплочение, бегство от смертельного урона, фокус, лечение;
 *  - мощь считается по МОДИФИЦИРОВАННЫМ эффектами урону, лечению и хитам (InfluenceMap).
 */
object PainAndGain {

    // ---------- боевые константы ----------
    private const val RANGED_RANGE = 3
    private const val HEAL_RANGE = 3
    private const val MELEE_KEEP_RANGE = 2
    private const val MELEE_KITE_DISCOUNT = 0.1

    /** Перевес, при котором армия идёт добивать, и порог продолжения. Порог продолжения выше единицы: прежний
     *  0.9 вместе со входом «по контакту» открывал лазейку — контакт с ОДНИМ стрелком включал ДОБИТЬ, а дальше
     *  гистерезис держал его при 0.97, и армия прошла через всю карту к вражескому углу (матч 3, t=60–96). */
    private const val PUSH_RATIO = 1.3
    private const val PUSH_RELEASE_RATIO = 1.1

    /** Проигрывая по прогнозу счёта, армия идёт добивать при меньшем перевесе: очки — у того, кто держит больше
     *  флагов, и он же слабее (дебаффы глобальны на сторону), а отсидеться — проиграть наверняка. Не при
     *  равенстве: оценка мощи шумит на ±5%, а прогноз счёта на 41-м тике («отстаём» на пять очков, пока скаут
     *  делает последний шаг к флагу) — не повод для решающего боя (стенд army). */
    private const val PUSH_RATIO_BEHIND = 1.1
    private const val PUSH_RELEASE_RATIO_BEHIND = 1.05

    /** Отставание, длящееся дольше стольких тиков, — уже не «скаут делает последний шаг к флагу», а пат: враг держит
     *  на флаг больше, его армия стоит, а наша при 1.01 полторы тысячи тиков ждёт перевеса 1.1 и проигрывает по
     *  очкам по единице в тик (стенд m3 army: 20252:20028 на 1992-м тике). Тогда в бой при равенстве. */
    private const val BEHIND_PATIENCE = 200
    private const val PUSH_RATIO_STALEMATE = 1.0
    private const val PUSH_RELEASE_RATIO_STALEMATE = 0.95

    /** Местный бой (группа против стаи рядом) и продолжение уже начатого: вход при 0.9 — при равных силах никто
     *  не вступал в бой, и рывок врага кончался ничьёй (стенд rush). */
    private const val LOCAL_ENTER_RATIO = 0.9

    /** Перевес врага, при котором армия отходит (и порог выхода из отхода). Решение об отходе принимается ДО
     *  контакта: при равной скорости из контакта не выйти, и 1.3 означало «в контакт при 0.77 и до конца» —
     *  матч 3, где 0.77 из четырёх дебаффов стоили всей армии. */
    private const val RETREAT_RATIO = 1.15
    private const val RETREAT_RELEASE_RATIO = 1.05

    /** Перевес врага, при котором армия ВЫХОДИТ из уже идущего боя в контакте, — много выше порога входа в отход:
     *  первая потеря в ровном бою даёт 1.18, и ОТХОД в тот же тик поставил стрелков «ждать отставших» без единого
     *  выстрела, пока мили врага резали авангард, а дальше ДОБИТЬ/ОТХОД мигали через тик — размен 1:10 при
     *  равной силе (матч 6, t=64–100). Из контакта при равной скорости всё равно не уйти; уходим, только когда
     *  бой проигран явно. */
    private const val RETREAT_CONTACT_RATIO = 1.5

    /** Захват флага допустим, пока армия с его дебаффом НЕ СЛАБЕЕ армии врага — считая ОБЕ стороны: чужой
     *  флаг снимает дебафф с врага. При равных армиях первый флаг берёт тот, кто согласен стать слабее;
     *  прежний 0.9 «с запасом на ошибку оценки» сам был ошибкой — при 0.9 бой проигран (матч 3: три флага
     *  по 0.9 каждый дали 0.77 и разгром). Против НЕПОДВИЖНОЙ армии (PASSIVE_TICKS без единого шага —
     *  мёртвый бот матчей 1–2) порог чуть ниже: при ровно 1.0 мёртвому боту не проиграть, но и не выиграть
     *  (ни одного флага — 0:0); 0.95 пускает дешёвые флаги, а 0.85 оставляло армию при 0.83 к пробуждению
     *  «спящего» лагеря (стенд sleeper: победа держалась на удаче кайта). */
    private const val CAPTURE_FLOOR = 1.0
    private const val CAPTURE_FLOOR_PASSIVE = 0.95
    private const val PASSIVE_TICKS = 100

    /** В последних тиках матча проигрывающему по счёту флаги нужны любой ценой: бой уже не успеет. */
    private const val LAST_CALL_TICKS = 300

    /** Построение перед контактом: боец не входит в дальность врага (≤ RANGED_RANGE от боевого врага), пока у
     *  авангарда (ближайшего к врагу ходячего вооружённого) в FORM_RANGE клетках не соберётся доля FORM_SHARE
     *  вооружённых, что в RALLY_RANGE от него; кто дальше — идёт к авангарду, не заходя в огонь. Матч 4: колонна
     *  с марша входила в блоб врага по одному — melee_2 один внутри двенадцати, стрелки в 4–5 клетках, лекари
     *  дальше, минус два мили за шесть тиков при равной силе; прежнее «любой напарник в бою снимает ожидание»
     *  и было командой «в атаку по одному». */
    private const val FORM_RANGE = 2
    private const val FORM_SHARE = 0.75

    /** Дольше стольких тиков строй не ждёт: кто мог подойти — подошёл. Без этого готовность могла не наступить
     *  никогда — в двух клетках от авангарда на кромке огня семерым нет места, и армия 1300 тиков стояла в
     *  четырёх клетках от лагеря врага в постуре ДОБИТЬ, проигрывая по очкам (стенд m1 grab). */
    private const val FORM_PATIENCE = 10

    /** Ловимость (см. catchable/evasive): окно наблюдения за движением врага, и поводок — дальше стольких клеток от
     *  центра вооружённой армии боец при враге рядом идёт к центру, а не к цели. Уходящего с равной скоростью не
     *  догнать никогда: в матче 5 армия 80 тиков гналась за тремя мили-приманками через всю карту, потом два мили —
     *  40 клеток за одиноким лекарем, и половина армии осталась одна против пяти стрелков с лекарями. */
    private const val CHASE_WINDOW = 8
    private const val LEASH_RANGE = 8

    /** Плотность строя при враге рядом (см. compact): шаг разрешён только на клетку в COMPACT_RANGE от центра
     *  вооружённой армии или с двумя вооружёнными соседями. Авангард один впереди не шагает на врага — ждёт линию;
     *  линия идёт вперёд линией. Матчи 4, 6, 7: первый размен всякий раз проигран 0:2, потому что мили-авангард
     *  дрался один, а стрелки в трёх-пяти клетках за ним ждали «готовности» и не стреляли. */
    private const val COMPACT_RANGE = 2

    /** Отход строем: убежавший вперёд дальше стольких клеток (по полю отхода) от самого отставшего вооружённого
     *  ждёт его вне огня — иначе погоня добивает отставших по одному (матч 4: армия рассыпалась по трём углам). */
    private const val RETREAT_GAP = 3

    /** Вес фактического огня в оценке клетки ЛЕКАРЯ: шаг к подопечному (10) стоит двух стрелков (120 → 6), трёх
     *  уже нет. Тело H×6 M×6 теряет лечение с первого попадания — каждые 100 урона это −12 лечения в тик до конца
     *  матча, замены нет, — поэтому лекарь не лезет под сосредоточенный огонь ради 72 вместо 24. Прежняя общая
     *  оценка (урон ×0.3 с шагом сближения мили, зона мили −50) держала лекарей в 2–4 клетках даже без огня и
     *  гнала их прочь (матч 3). Варианты, проверенные стендом sleeper на четырёх временах пробуждения: этот —
     *  10:0 во всех четырёх; «вплотную под любым огнём» (0.02) и «без штрафа за соседа, с отдельным штрафом за
     *  мили вплотную» — 2:12 во всех четырёх (стендовый враг бьёт цель с наименьшими хитами). */
    private const val HEALER_W_DAMAGE = 0.05

    /** Внутри стольких клеток от цели (Чебышев, сверх standoff) строй не держат: группа уже на месте, а
     *  ожидание «отставших» у самого флага запирало захватчика на тысячу тиков (стенд greedy/grab). */
    private const val ARRIVED_SLACK = 2

    /** Охрана флага: боевые враги в этом радиусе от флага (дальность сближения + выстрел). */
    private const val FLAG_GUARD_RANGE = 11

    /** Враг «рядом» с армией (для отхода): в этих клетках от кого-то из наших; из отхода выходим, когда он
     *  дальше NEAR_RANGE + NEAR_RELEASE (иначе постура прыгала каждый тик на кромке радиуса). */
    private const val NEAR_RANGE = 14
    private const val NEAR_RELEASE = 6

    private const val COHESION_GAP = 8
    private const val COHESION_GAP_MAX = 100
    private const val ENGAGE_COHESION_TICKS = 2
    private const val POST_STANDOFF = 2
    private const val ENGAGE_RANGE = 8
    private const val CLOSE_STANDOFF = 2
    private const val APPROACH_WINDOW = 20

    /** Столько тиков без сдвига — враг «стоит» и в стаи по «успеет дойти» не входит (см. packAt). */
    private const val STILL_TICKS = 20

    /** Дальше стольких клеток от авангарда отставший идёт К АВАНГАРДУ, а не к цели, и авангард его ждёт:
     *  сплочение по разнице поля не видит раскола на два обхода препятствия (матч 2 на стенде: две колонны
     *  в 30 клетках друг от друга с равной «отставкой», северная четвёрка легла под двенадцать). */
    private const val RALLY_RANGE = 12

    /** Далёкого напарника медленнее этого (тиков на клетку) авангард не ждёт — калека дойдёт, когда дойдёт. */
    private const val RALLY_MAX_PERIOD = 3

    /** Уже дерущийся выходит из боя только при таком местном соотношении (см. localAggressive в ANNIHILATE). */
    private const val ANNIHILATE_HOLD_RATIO = 0.5
    private const val USE_AVOID = true
    private const val USE_RALLY = true
    private const val USE_LOCAL_ANNIHILATE = true

    /** Клетки в такой близости от боевого врага поле «в обход» считает стеной (см. flowAvoiding). */
    private const val AVOID_RANGE = RANGED_RANGE + 1

    /** Вне боя шаг делается только ради заметно лучшей клетки: у поста клетки в зазоре standoff равноценны с
     *  точностью до штрафа за соседа (4), и отряд без порога всё время менялся местами — сотни обменов на сто
     *  тиков в матче 1. Порог меньше выигрыша одной клетки по полю (10) и больше одного соседа. */
    private const val STAY_BIAS = 5.0

    // веса оценки клетки (скопированы из spawn-and-swamp, где обкатаны)
    private const val PAIR_W_DIST = 10.0
    private const val PAIR_W_DAMAGE = 0.3
    private const val PAIR_W_INFLUENCE = 0.1
    private const val PAIR_W_OUTGOING = 30.0
    private const val PAIR_W_MELEE = 50.0
    private const val AGGRO_MELEE_FACTOR = 0.3
    private const val PAIR_W_SPREAD = 4.0
    private const val PAIR_W_SWAMP = 40.0
    private const val SEPARATION_RADIUS = 1
    private const val CROWD_COST = 3

    private const val FIGHTER_PRIORITY = 3
    private const val RUNNER_PRIORITY = 2

    // ---------- отладка ----------
    private const val DEBUG_LOG = true
    private const val DEBUG_MAP = true
    /** Выключено: отрисовка влияния — ~57 000 вызовов contribution за тик (13×13 клеток × 12 стрелков × 28 крипов),
     *  первый тик матча 7 вылетел по таймауту именно в drawDebug; журнал даёт всё, что нужно для разбора. */
    private const val DEBUG_VISUALS = false
    private const val LOG_EVERY = 10

    private val DIRECTIONS = listOf(
        0 to 0, -1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1,
    )

    private enum class Posture { HOLD, RETREAT, ANNIHILATE, FLAG }

    private var greeted = false
    private var mapLogged = false
    private var posture = Posture.HOLD
    private var objectiveFlagId: String? = null
    private var postureLogged = ""

    /** Стартовые центры армий — «дома» сторон (спавнов нет): половины карты и точка поста. */
    private var homePos: Position? = null
    private var enemyHomePos: Position? = null

    private val approachingIds = HashSet<String>()
    private val arrivalById = HashMap<String, Int>()
    private val approachHistory = HashMap<String, ArrayDeque<Pair<Int, Int>>>()
    private val enemyPrevCell = HashMap<String, Int>()
    /** id врага -> тик его последнего сдвига (см. stationary). */
    private val enemyLastMove = HashMap<String, Int>()
    /** Центр вооружённой армии и клетки врагов за последние CHASE_WINDOW тиков (см. evasive). */
    private val ourCentroidHist = ArrayDeque<Int>()
    private val enemyCellHist = HashMap<String, ArrayDeque<Int>>()
    /** Кто сейчас идёт к авангарду (гистерезис сбора, см. rallyTo). */
    private val rallyingIds = HashSet<String>()
    private var huntingThreat = false
    /** ДОБИТЬ по перевесу (не по контакту) — только к нему применяется гистерезис PUSH_RELEASE_RATIO. */
    private var pushing = false
    /** Точка отхода — одна на весь отход (см. retreatPoint). */
    private var retreatTarget: Position? = null
    /** Тик, с которого строй ждёт готовности (см. FORM_PATIENCE); -1 — не ждёт. */
    private var formWaitSince = -1
    private val aggressiveIds = HashSet<String>()
    private val lastHits = HashMap<String, Int>()
    private val lastCell = HashMap<String, Int>()
    private val ghostLogged = HashMap<String, Int>()
    private class Shooter(val cell: Int, val ranged: Double, val melee: Double)
    private var prevShooters: List<Shooter> = emptyList()

    /** id захватчика -> id флага (липкое назначение). */
    private val runnerFlag = HashMap<String, String>()

    // ---------- счёт ----------
    private var ourScore = 0.0
    private var enemyScore = 0.0
    private var ourRate = 0
    private var enemyRate = 0
    /** По прогнозу (счёт + темп × остаток) мы проигрываем: очки важнее силы (см. captureAllowed). */
    private var behindOnScore = false
    /** Сколько тиков подряд отстаём по прогнозу (см. BEHIND_PATIENCE). */
    private var behindTicks = 0
    private val lastFlagOwner = HashMap<String, Int>()
    private var lastEffectsKey = ""
    private var lastBodiesKey = ""

    // ---------- кэши на тик ----------
    private val flowCache = HashMap<Int, IntArray>()

    // ---------- модель ----------

    /** Флаг очков в этом тике: владелец, тип дебаффа, очки, кто стоит на клетке и чья охрана рядом. */
    private class FlagInfo(val flag: ScoreFlag, val mine: Boolean?, val type: String, val score: Int, val occupant: Creep?, val guards: List<Creep>) {
        val id: String get() = flag.id
        val pos: Position get() = flag
        val ours: Boolean get() = mine == true
        val theirs: Boolean get() = mine == false
        /** Очки в тик, которые даёт захват: чужой флаг — двойной размен (нам плюс, врагу минус). */
        val swing: Double get() = if (theirs) 2.0 * score else score.toDouble()
    }

    private class Ctx(
        val home: Position,
        val enemyHome: Position,
        val myCreeps: List<Creep>,
        val active: List<Creep>,
        val army: List<Creep>,      // с оружием или лечением
        val runners: List<Creep>,   // безоружные и без лечения: захватчики
        val enemyCreeps: List<Creep>,
        val combatEnemies: List<Creep>,
        val blocked: List<Position>,
        /** Опасность (без флагов) — основа для матриц пути. */
        val rawDanger: CostMatrix,
        /** Опасность + НЕ НАШИ флаги как стены: путь без назначения на флаг не ступает. */
        val dangerMatrix: CostMatrix,
        val flags: List<FlagInfo>,
        /** Клетки не наших флагов (x*100+y): захват — только назначенным, см. flagBlocked. */
        val flagCells: Set<Int>,
        val flagBlocked: List<Position>,
        /** Вся боевая армия врага не сделала ни шага PASSIVE_TICKS тиков (см. CAPTURE_FLOOR_PASSIVE). */
        val passiveEnemy: Boolean,
        val ourCentroid: Position,
        val enemyCentroid: Position?,
    )

    fun tick() {
        flowCache.clear()
        avoidCellsCache = null

        val myCreeps = getObjectsByPrototype(Creep::class).filter { it.my && it.exists }
        val enemyCreeps = getObjectsByPrototype(Creep::class).filter { !it.my && it.exists && !it.spawning }
        val active = myCreeps.filter { !it.spawning }
        val combatEnemies = enemyCreeps.filter { val p = InfluenceMap.profileOf(it); p.melee + p.ranged + p.heal > 0.0 }

        // дома сторон — стартовые центры армий: спавнов на карте нет, половины и пост считаются от них
        if (homePos == null && active.isNotEmpty()) homePos = centroidOf(active)
        if (enemyHomePos == null && enemyCreeps.isNotEmpty()) enemyHomePos = centroidOf(enemyCreeps)
        val home = homePos ?: centroidOf(active) ?: InfluenceMap.cell(50, 50)
        val enemyHome = enemyHomePos ?: InfluenceMap.cell(99 - home.x, 99 - home.y)

        val flags = collectFlags(myCreeps, enemyCreeps, combatEnemies)
        applyEffects(flags, myCreeps, enemyCreeps)
        accountScore(flags)

        if (!greeted) {
            greeted = true
            probe(flags, myCreeps, enemyCreeps, home, enemyHome)
        }
        if (DEBUG_MAP && !mapLogged) {
            mapLogged = true
            logMap(flags, myCreeps, enemyCreeps)
        }
        logBodies(myCreeps, enemyCreeps)

        val army = active.filter { hasWeapon(it) || hasHeal(it) }
        val runners = active.filter { !hasWeapon(it) && !hasHeal(it) }
        val immobile = active.filter { !canMove(it) }

        val walls = getObjectsByPrototype(StructureWall::class).filter { it.exists }
        val ramparts = getObjectsByPrototype(StructureRampart::class).filter { it.exists }
        val spawns = getObjectsByPrototype(StructureSpawn::class).filter { it.exists }
        val blocked: List<Position> = walls + ramparts.filter { it.my != true } + spawns + immobile
        val blockedForEnemy: List<Position> = walls + ramparts.filter { it.my != false } + spawns

        InfluenceMap.setProtectedCells(ramparts.filter { it.my == true }.mapTo(HashSet()) { it.x * 100 + it.y })
        InfluenceMap.setEnemyBlocked(blockedForEnemy.mapTo(HashSet()) { it.x * 100 + it.y })
        val rawDanger = InfluenceMap.dangerCostMatrix(enemyCreeps, blocked)
        // флаг берётся тем, кто на него ВСТАЛ, — и любой шаг армии через чужой флаг был захватом: в матче 3 армия
        // на марше взяла D5 и второй A3 (occupant=none в журнале) и дралась при A×0.6 D×1.1 против врага, с
        // которого сама же сняла дебаффы. Не наш флаг — стена для всех, кроме назначенного на него
        val flagCells = flags.filter { !it.ours }.mapTo(HashSet()) { it.pos.x * 100 + it.pos.y }
        val flagBlocked = flags.filter { !it.ours }.map { it.pos }
        val dangerMatrix = rawDanger.clone()
        for (c in flagCells) dangerMatrix.set(c / 100, c % 100, 255)
        val passiveEnemy = combatEnemies.isNotEmpty() && combatEnemies.all { stationaryFor(it) >= PASSIVE_TICKS }

        DistanceMap.syncWalls(walls.size)
        DistanceMap.ensureBuilt(home, enemyHome)

        val ourCentroid = centroidOf(army.ifEmpty { active }) ?: home
        val enemyCentroid = centroidOf(combatEnemies.ifEmpty { enemyCreeps })
        val ctx = Ctx(home, enemyHome, myCreeps, active, army, runners, enemyCreeps, combatEnemies, blocked, rawDanger, dangerMatrix, flags, flagCells, flagBlocked, passiveEnemy, ourCentroid, enemyCentroid)

        enemyArrivalTicks(ctx)
        runRunners(ctx)
        runArmy(ctx)

        TrafficManager.resolve(active.filter { canMove(it) }, myCreeps + enemyCreeps)
        InfluenceMap.pruneStances(myCreeps.mapTo(HashSet()) { it.id })
        // кто из врагов сдвинулся за тик — для признака «стоит на месте» (см. stationary)
        for (e in enemyCreeps) {
            val cell = e.x * 100 + e.y
            if (enemyPrevCell[e.id] != cell) enemyLastMove[e.id] = getTicks()
        }
        enemyLastMove.keys.retainAll { id -> enemyCreeps.any { it.id == id } }
        enemyPrevCell.clear()
        for (e in enemyCreeps) enemyPrevCell[e.id] = e.x * 100 + e.y
        // история движения — для ловимости (см. evasive)
        val armedCentroid = centroidOf(army.filter { hasWeapon(it) }.ifEmpty { army }) ?: ourCentroid
        ourCentroidHist.addLast(armedCentroid.x * 100 + armedCentroid.y)
        while (ourCentroidHist.size > CHASE_WINDOW) ourCentroidHist.removeFirst()
        for (e in enemyCreeps) {
            val h = enemyCellHist.getOrPut(e.id) { ArrayDeque() }
            h.addLast(e.x * 100 + e.y)
            while (h.size > CHASE_WINDOW) h.removeFirst()
        }
        enemyCellHist.keys.retainAll { id -> enemyCreeps.any { it.id == id } }
        if (DEBUG_LOG) logStuck(active, enemyCreeps)
        if (DEBUG_VISUALS) InfluenceMap.drawDebug(army, myCreeps, enemyCreeps)

        if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
            val ours = ourPowerOf(army, combatEnemies)
            val theirs = enemyPowerOf(combatEnemies, army)
            println(
                "t=${getTicks()} army=${army.size} runners=${runners.size} enemies=${enemyCreeps.size}/${combatEnemies.size} " +
                    "score=${ourScore.toInt()}/${enemyScore.toInt()} rate=$ourRate/$enemyRate behind=$behindOnScore passive=$passiveEnemy flags=${flagsSummary(flags)} " +
                    "posture=$posture obj=${objectiveFlagId?.let { id -> flags.firstOrNull { it.id == id }?.let { "(${it.pos.x},${it.pos.y})" } } ?: "-"} hunt=$huntingThreat " +
                    "our=${ours.toInt()} enemy=${theirs.toInt()} hits=${army.sumOf { it.hits }}/${army.sumOf { it.hitsMax }} enemyHits=${combatEnemies.sumOf { it.hits }}/${combatEnemies.sumOf { it.hitsMax }} " +
                    "centroid=(${ourCentroid.x},${ourCentroid.y}) enemyCentroid=${enemyCentroid?.let { "(${it.x},${it.y})" } ?: "-"}"
            )
            if (getTicks() % (LOG_EVERY * 10) == 0) println(TrafficManager.audit())
        }
    }

    // ==================== зонд ====================

    /** Число из константы арены (внешнее объявление может оказаться undefined — тогда запасное). */
    private fun num(v: dynamic, fallback: Double): Double = if (jsTypeOf(v) == "number") v.unsafeCast<Double>() else fallback

    private fun probe(flags: List<FlagInfo>, myCreeps: List<Creep>, enemyCreeps: List<Creep>, home: Position, enemyHome: Position) {
        println(
            "hello season4 pain-and-gain: ${arenaInfo.season} - ${arenaInfo.name} level=${arenaInfo.level} " +
                "ticksLimit=${arenaInfo.ticksLimit} cpu=${arenaInfo.cpuTimeLimit}/${arenaInfo.cpuTimeLimitFirstTick}"
        )
        println(
            "pain-and-gain: TICKS_LIMIT=${num(TICKS_LIMIT.asDynamic(), -1.0)} MAX_SCORE_PER_TICK=${num(MAX_SCORE_PER_TICK.asDynamic(), -1.0)} " +
                "FLAG_TYPES=${try { JSON.stringify(FLAG_TYPES) } catch (t: Throwable) { "?" }} " +
                "EFF: attack=$EFF_ATTACK_MODIFIER ranged=$EFF_RANGED_ATTACK_MODIFIER heal=$EFF_HEAL_MODIFIER taken=$EFF_DAMAGE_TAKEN_MODIFIER " +
                "power: R=$RANGED_ATTACK_POWER A=$ATTACK_POWER H=$HEAL_POWER/$RANGED_HEAL_POWER"
        )
        println("flags: " + flags.joinToString(" ") { "(${it.pos.x},${it.pos.y})${typeChar(it.type)}${it.score}my=${it.mine}" })
        println("home=(${home.x},${home.y}) enemyHome=(${enemyHome.x},${enemyHome.y})")
        println("my creeps (${myCreeps.size}): " + myCreeps.joinToString(" ") { "${it.id}(${it.x},${it.y})${bodySummary(it)}${if (it.spawning) "S" else ""}" })
        println("enemy creeps (${enemyCreeps.size}): " + enemyCreeps.joinToString(" ") { "${it.id}(${it.x},${it.y})${bodySummary(it)}" })
        val walls = getObjectsByPrototype(StructureWall::class).size
        val ramparts = getObjectsByPrototype(StructureRampart::class).size
        val spawns = getObjectsByPrototype(StructureSpawn::class).size
        println("structures: walls=$walls ramparts=$ramparts spawns=$spawns")
        // тела целиком (с порядком частей) — по ним видно, что выдано, и что умрёт первым
        for (c in myCreeps) println("body ${c.id}: " + c.body.joinToString("") { partChar(it.type).toString() })
        for (c in enemyCreeps) println("enemy body ${c.id}: " + c.body.joinToString("") { partChar(it.type).toString() })
    }

    private fun partChar(type: BodyPartType): Char = when (type) {
        TOUGH -> 'T'; MOVE -> 'M'; RANGED_ATTACK -> 'R'; ATTACK -> 'A'; HEAL -> 'H'; CARRY -> 'C'; WORK -> 'W'
    }

    private fun typeChar(type: String): Char = when (type) {
        EFF_ATTACK_MODIFIER -> 'A'
        EFF_RANGED_ATTACK_MODIFIER -> 'R'
        EFF_HEAL_MODIFIER -> 'H'
        EFF_DAMAGE_TAKEN_MODIFIER -> 'D'
        else -> '?'
    }

    private fun flagsSummary(flags: List<FlagInfo>): String =
        flags.joinToString(",") { "${typeChar(it.type)}${it.score}${if (it.ours) "+" else if (it.theirs) "-" else "0"}${if (it.occupant != null) (if (it.occupant.my) "s" else "e") else ""}${if (it.guards.isNotEmpty()) "g${it.guards.size}" else ""}" }

    /** Состав армий (живые части) — при каждом изменении: видно потери и покалеченных. */
    private fun logBodies(myCreeps: List<Creep>, enemyCreeps: List<Creep>) {
        if (!DEBUG_LOG) return
        val key = myCreeps.joinToString(",") { bodySummary(it) } + "|" + enemyCreeps.joinToString(",") { bodySummary(it) }
        if (key == lastBodiesKey) return
        lastBodiesKey = key
        println("armies t=${getTicks()}: ours(${myCreeps.size}) " + myCreeps.joinToString(" ") { "${bodySummary(it)}h=${it.hits}" } +
            " | enemy(${enemyCreeps.size}) " + enemyCreeps.joinToString(" ") { "(${it.x},${it.y})${bodySummary(it)}h=${it.hits}" })
    }

    // ==================== флаги и эффекты ====================

    private fun collectFlags(myCreeps: List<Creep>, enemyCreeps: List<Creep>, combatEnemies: List<Creep>): List<FlagInfo> {
        val result = ArrayList<FlagInfo>()
        val all = getObjectsByPrototype(ScoreFlag::class).filter { it.exists }
        for (f in all) {
            val occupant = (myCreeps + enemyCreeps).firstOrNull { !it.spawning && it.x == f.x && it.y == f.y }
            val guards = combatEnemies.filter { getRange(it, f) <= FLAG_GUARD_RANGE }
            result.add(FlagInfo(f, f.my, f.effectType, f.scorePerTick, occupant, guards))
        }
        // журнал захватов: смена владельца и кто стоит на клетке — проверка механики «встал — захватил»
        for (fi in result) {
            val owner = if (fi.ours) 1 else if (fi.theirs) -1 else 0
            val prev = lastFlagOwner[fi.id]
            if (prev != null && prev != owner && DEBUG_LOG) {
                println("flag t=${getTicks()}: (${fi.pos.x},${fi.pos.y})${typeChar(fi.type)}${fi.score} owner ${ownerName(prev)} -> ${ownerName(owner)} occupant=${fi.occupant?.let { "${if (it.my) "my" else "enemy"} ${bodySummary(it)}" } ?: "none"}")
            }
            lastFlagOwner[fi.id] = owner
        }
        return result
    }

    private fun ownerName(code: Int) = when (code) { 1 -> "us"; -1 -> "enemy"; else -> "none" }

    /** Множитель стека по таблице арены: удар/стрельба 0.8 → 0.6, лечение 0.75 → 0.5, входящий 1.1 (флаг один);
     *  дальше — тем же шагом (в матче сверяется с effects крипов). */
    private fun stackMul(type: String, count: Int): Double {
        if (count <= 0) return 1.0
        return when (type) {
            EFF_ATTACK_MODIFIER, EFF_RANGED_ATTACK_MODIFIER -> 1.0 - 0.2 * count
            EFF_HEAL_MODIFIER -> 1.0 - 0.25 * count
            EFF_DAMAGE_TAKEN_MODIFIER -> 1.0 + 0.1 * count
            else -> 1.0
        }.coerceAtLeast(0.0)
    }

    private fun sideModsOf(flags: List<FlagInfo>, mine: Boolean): InfluenceMap.SideMods {
        fun n(type: String) = flags.count { it.mine == mine && it.type == type }
        return InfluenceMap.SideMods(
            attack = stackMul(EFF_ATTACK_MODIFIER, n(EFF_ATTACK_MODIFIER)),
            ranged = stackMul(EFF_RANGED_ATTACK_MODIFIER, n(EFF_RANGED_ATTACK_MODIFIER)),
            heal = stackMul(EFF_HEAL_MODIFIER, n(EFF_HEAL_MODIFIER)),
            taken = stackMul(EFF_DAMAGE_TAKEN_MODIFIER, n(EFF_DAMAGE_TAKEN_MODIFIER)),
        )
    }

    /** Эффекты сторон: по массиву effects крипов, если API его отдаёт, иначе по подсчёту флагов (таблица
     *  арены). Печатает то и другое при изменении — в матче они обязаны совпасть. */
    private fun applyEffects(flags: List<FlagInfo>, myCreeps: List<Creep>, enemyCreeps: List<Creep>) {
        val ours = sideModsOf(flags, true)
        val theirs = sideModsOf(flags, false)
        InfluenceMap.setSideMods(ours, theirs)
        val sample = myCreeps.firstOrNull { InfluenceMap.hasEffectsApi(it) }
        InfluenceMap.setOurTaken(if (sample != null) InfluenceMap.takenOf(sample) else ours.taken)
        if (DEBUG_LOG) {
            val enemySample = enemyCreeps.firstOrNull { InfluenceMap.hasEffectsApi(it) }
            val key = "$ours|$theirs|${sample?.let { JSON.stringify(it.effects) }}|${enemySample?.let { JSON.stringify(it.effects) }}"
            if (key != lastEffectsKey) {
                lastEffectsKey = key
                println("effects t=${getTicks()}: byFlags ours=[$ours] theirs=[$theirs] api ours=${sample?.let { JSON.stringify(it.effects) } ?: "n/a"} theirs=${enemySample?.let { JSON.stringify(it.effects) } ?: "n/a"}")
            }
        }
    }

    private fun accountScore(flags: List<FlagInfo>) {
        val cap = num(MAX_SCORE_PER_TICK.asDynamic(), Double.MAX_VALUE)
        ourRate = minOf(cap, flags.filter { it.ours }.sumOf { it.score }.toDouble()).toInt()
        enemyRate = minOf(cap, flags.filter { it.theirs }.sumOf { it.score }.toDouble()).toInt()
        ourScore += ourRate
        enemyScore += enemyRate
        val remaining = maxOf(0, arenaInfo.ticksLimit - getTicks())
        behindOnScore = ourScore + ourRate * remaining < enemyScore + enemyRate * remaining
        behindTicks = if (behindOnScore) behindTicks + 1 else 0
        if (DEBUG_LOG && getTicks() % 100 == 0) println("score t=${getTicks()}: our=${ourScore.toInt()} (+$ourRate/t) enemy=${enemyScore.toInt()} (+$enemyRate/t) behind=$behindOnScore lead=${(ourScore - enemyScore).toInt()} maxSwing=${(cap * remaining).toInt()}")
    }

    /**
     * Брать ли этот (не наш) флаг сейчас. Свой — да. Врага в поле нет — да. Иначе — только если армия С ЭТИМ
     * дебаффом всё ещё не слабее армии врага (CAPTURE_FLOOR; против неподвижной — CAPTURE_FLOOR_PASSIVE):
     * маргинальная цена по Ланчестеру с эффектами ОБЕИХ сторон — у флага стрельбы дешевеет только стрелковая
     * часть урона, у флага лечения растёт чистый урон врага по нам, у флага уязвимости тают хиты, а чужой флаг
     * ещё и возвращает врагу то, что снимал с него. Отставание по счёту флагов больше не открывает: оно
     * значит, что враг держит больше и слабее — ответ ему бой (PUSH_RATIO_BEHIND), а не ещё один дебафф
     * (матч 3: «отстаём» на 2 очка при 6:10 разрешило всё подряд). Исключение — последние LAST_CALL_TICKS:
     * бой уже не успеет, и очки решают.
     */
    private fun captureAllowed(ctx: Ctx, f: FlagInfo): Boolean {
        if (f.ours) return true
        if (ctx.combatEnemies.isEmpty()) return true
        if (behindOnScore && arenaInfo.ticksLimit - getTicks() <= LAST_CALL_TICKS) return true
        val (ours, theirs) = powerAfter(ctx, f)
        return ours >= theirs * (if (ctx.passiveEnemy) CAPTURE_FLOOR_PASSIVE else CAPTURE_FLOOR)
    }

    /** Мощь сторон, если мы возьмём ещё этот флаг: наша — с его дебаффом; вражья — без него, если флаг был его. */
    private fun powerAfter(ctx: Ctx, f: FlagInfo): Pair<Double, Double> {
        val nOurs = ctx.flags.count { it.ours && it.type == f.type }
        val kOurs = stackMul(f.type, nOurs + 1) / stackMul(f.type, nOurs).coerceAtLeast(0.01)
        val nTheirs = ctx.flags.count { it.theirs && it.type == f.type }
        val kTheirs = if (f.theirs) stackMul(f.type, nTheirs - 1) / stackMul(f.type, nTheirs).coerceAtLeast(0.01) else 1.0
        val ourMods = hypoMods(f.type, kOurs)
        val theirMods = hypoMods(f.type, kTheirs)
        return powerOf(ctx.army, ctx.combatEnemies, ourMods, theirMods) to powerOf(ctx.combatEnemies, ctx.army, theirMods, ourMods)
    }

    /** Цена флага в силе — доля нашей мощи, которая останется после захвата (1 — бесплатно): флаг лечения
     *  для армии почти без лекарей дёшев, флаг уязвимости стоит всем; дорогие берутся последними. */
    private fun captureCost(ctx: Ctx, f: FlagInfo): Double {
        if (f.ours || ctx.combatEnemies.isEmpty()) return 1.0
        val now = ourPowerOf(ctx.army, ctx.combatEnemies)
        if (now <= 0.0) return 1.0
        return (powerAfter(ctx, f).first / now).coerceIn(0.0, 1.0)
    }

    // ==================== захватчики ====================

    /** Флаг, за которым стоит идти захватчику: не наш и без врага на клетке, или наш пустой (охрана клеткой). */
    private fun wantsRunner(f: FlagInfo): Boolean {
        val occ = f.occupant
        if (occ != null && !occ.my) return false
        return !f.ours || occ == null
    }

    private fun runRunners(ctx: Ctx) {
        val runners = ctx.runners
        if (runners.isEmpty()) { runnerFlag.clear(); return }
        runnerFlag.keys.retainAll { id -> runners.any { it.id == id } }
        val flagById = ctx.flags.associateBy { it.id }
        fun dbg(s: Creep, mode: String, f: FlagInfo?, step: Position? = null) {
            if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
                println("  r${s.id} (${s.x},${s.y}) ${bodySummary(s)} hits=${s.hits} $mode flag=${f?.let { "(${it.pos.x},${it.pos.y})${typeChar(it.type)}${it.score}my=${it.mine}" } ?: "-"} fatigue=${s.fatigue} step=${step?.let { "(${it.x},${it.y})" } ?: "stay"}${if (TrafficManager.isStuck(s.id)) " STUCK" else ""}")
            }
        }
        // назначение — глобальное жадное паросочетание по ценности (лучшая пара «захватчик-флаг» первой),
        // НЕ зависящее от порядка обхода: обход в порядке текущих назначений менял очерёдность от тика к
        // тику, два скаута по очереди отбирали друг у друга центральный флаг и полторы тысячи тиков
        // шагали туда-обратно на одной клетке, ни разу не выйдя за неё (матч 1). Взявший флаг идёт за
        // следующим НЕ НАШИМ; сидеть на своём — когда чужих свободных на всех не хватает
        class Cand(val runner: Creep, val flag: FlagInfo, val value: Double)
        val cands = ArrayList<Cand>()
        for (s in runners) {
            val currentId = runnerFlag[s.id]
            for (f in ctx.flags) {
                val occ = f.occupant
                if (occ != null && occ.id != s.id) continue // занято (чужим — ждём отряд; своим — тот и держит)
                val flow = flowTo(ctx, f.pos)
                val ticks = pathTicks(s, flow, s.x * 100 + s.y)
                if (ticks >= Int.MAX_VALUE / 4) continue
                // стая у флага — охрана рядом И те, кто дойдёт до него раньше нас: скаут шёл к дальнему H4, пока
                // армия врага шла туда же, и вошёл в неё (матч 3, t=70–87); охраны в 11 клетках было мало
                if (packAt(ctx, f.pos, flow, ticks).isNotEmpty()) continue
                // свой пустой флаг стоит половину — но СИДЯЩИЙ на нём закрывает клетку от чужих бегунов (матч 2:
                // центральный D5 забрал вражеский M1, пока армия уходила за соседним флагом, и вернуть его было
                // некому); чужой — двойной размен; флаг, который порог силы сейчас не разрешает, — пятую часть
                // (ждать у него можно, но сидеть на своём полезнее); дорогой по силе — позже дешёвого (см.
                // captureCost); текущий — с премией
                val value = (if (f.ours) 0.5 * f.score else f.swing) * captureCost(ctx, f) / (ticks + 5) *
                    (if (f.id == currentId) 1.25 else 1.0) * (if (!f.ours && !captureAllowed(ctx, f)) 0.2 else 1.0)
                cands.add(Cand(s, f, value))
            }
        }
        cands.sortByDescending { it.value }
        val assigned = HashSet<String>()
        val taken = HashSet<String>()
        for (c in cands) {
            if (c.runner.id in assigned || c.flag.id in taken) continue
            assigned.add(c.runner.id)
            taken.add(c.flag.id)
            runnerFlag[c.runner.id] = c.flag.id
        }
        for (s in runners) if (s.id !in assigned) runnerFlag.remove(s.id)

        for (s in runners) {
            val f = runnerFlag[s.id]?.let { flagById[it] }
            val onIt = f != null && s.x == f.pos.x && s.y == f.pos.y
            val nearby = ctx.combatEnemies.filter { getRange(s, it) <= RANGED_RANGE + 2 }
            val underFire = InfluenceMap.damageAt(s.x, s.y, ctx.combatEnemies) > 0.0
            // захватчик без замены: от врага рядом — прочь (пустой MOVE ходит клетку за тик и по болоту, где
            // стрелок вязнет), даже с флага: флаг останется нашим, пока враг сам на него не встанет
            if (canMove(s) && (underFire || nearby.any { getRange(s, it) <= RANGED_RANGE + 1 })) {
                // поиск пути бегства может не дать шага (скаут в матче 3 «бежал» на месте три тика и погиб) —
                // тогда жадно: соседняя клетка подальше от врагов и под меньшим огнём
                val foes = nearby.ifEmpty { ctx.combatEnemies }
                val step = fleeStep(s, foes, ctx.dangerMatrix) ?: greedyFlee(ctx, s, foes)
                if (step != null) TrafficManager.request(s, step, RUNNER_PRIORITY)
                dbg(s, "FLEE", f, step)
                continue
            }
            if (f == null) {
                // все флаги при деле: к армии, за её спиной
                val step = if (s.getRangeTo(ctx.ourCentroid) > POST_STANDOFF + 2) pathStep(s, ctx.ourCentroid, POST_STANDOFF + 2, crowdMatrixOf(ctx, -1)) else null
                if (step != null) TrafficManager.request(s, step, RUNNER_PRIORITY)
                dbg(s, "RESERVE", null, step)
                continue
            }
            if (onIt) {
                dbg(s, if (f.ours) "HOLD" else "HOLD_WAIT", f)
                continue
            }
            // брать ли флаг сейчас (дебафф): нельзя — ждём рядом, шаг на клетку сделаем, когда станет можно
            val allowed = captureAllowed(ctx, f)
            val range = if (allowed) 0 else 1
            // свой назначенный флаг открыт для шага, остальные не наши — стены (см. Ctx.flagCells)
            val step = if (s.getRangeTo(f.pos) > range) pathStep(s, f.pos, range, crowdMatrixOf(ctx, f.pos.x * 100 + f.pos.y)) else null
            if (step != null) TrafficManager.request(s, step, RUNNER_PRIORITY)
            dbg(s, if (allowed) "TO_FLAG" else "POISED", f, step)
        }
    }

    // ==================== армия ====================

    /** Поле к цели; не наши флаги — стены (кроме самой цели: flowFieldTo всегда открывает целевую клетку). */
    private fun flowTo(ctx: Ctx, target: Position, avoid: Boolean = false): IntArray =
        flowCache.getOrPut(target.x * 100 + target.y + (if (avoid) 10000 else 0)) {
            DistanceMap.flowFieldTo(target, ctx.flagBlocked + (if (avoid) ctx.blocked + avoidCells(ctx) else ctx.blocked))
        }

    /** Клетки в AVOID_RANGE от СТОЯЩИХ боевых врагов — поле «в обход» ведёт мимо лагеря, а не сквозь него.
     *  Идущий враг не обходится: обход идущего навстречу разводил строй с поста в стороны за тик до
     *  столкновения, и рывок врага, прежде отбитый к 212-му тику, стал разгромом (стенд rush). */
    private var avoidCellsCache: List<Position>? = null
    private fun avoidCells(ctx: Ctx): List<Position> = avoidCellsCache ?: run {
        val seen = HashSet<Int>()
        val out = ArrayList<Position>()
        for (e in ctx.combatEnemies) for (dx in -AVOID_RANGE..AVOID_RANGE) for (dy in -AVOID_RANGE..AVOID_RANGE) {
            if (!stationary(e)) continue
            val x = e.x + dx; val y = e.y + dy
            if (x < 0 || y < 0 || x > 99 || y > 99) continue
            if (seen.add(x * 100 + y)) out.add(InfluenceMap.cell(x, y))
        }
        avoidCellsCache = out
        out
    }

    /** Поле к НЕ-вражеской цели (флаг, пост, сбор, отход): в обход врагов, если оттуда, где стоит крип, такой
     *  путь есть, иначе обычное (матч 2 на стенде: маршрут к дальнему флагу вёл через стоящий отряд врага). */
    private fun flowAvoiding(ctx: Ctx, target: Position, creep: Creep): IntArray {
        if (!USE_AVOID) return flowTo(ctx, target)
        val f = flowTo(ctx, target, true)
        return if (f[creep.x * 100 + creep.y] >= 0) f else flowTo(ctx, target)
    }

    /** Стая у цели: боевые враги рядом с ней и те, кто дойдёт до неё (своим телом по полю) не позже нас —
     *  из ХОДЯЧИХ: стоящий на месте STILL_TICKS тиков в стаю по «успеет дойти» не зачисляется (матч 1:
     *  армия врага не сделала ни шага за 1570 тиков, а «успевала» к каждому флагу, и армия простояла на посту). */
    private fun packAt(ctx: Ctx, pos: Position, flow: IntArray, ourTravel: Int): List<Creep> =
        ctx.combatEnemies.filter { getRange(it, pos) <= FLAG_GUARD_RANGE || (!stationary(it) && pathTicks(it, flow, it.x * 100 + it.y) <= ourTravel) }

    /** Сколько тиков враг не двигался (новый враг считается идущим). */
    private fun stationaryFor(e: Creep): Int = getTicks() - (enemyLastMove[e.id] ?: getTicks())

    /** Враг не двигался последние STILL_TICKS тиков. */
    private fun stationary(e: Creep): Boolean = stationaryFor(e) >= STILL_TICKS

    private class Objective(val flag: FlagInfo, val pack: List<Creep>, val value: Double, val travel: Int)

    /**
     * Флаг-цель армии: не наш, разрешённый к захвату; со стаей — по перевесу Ланчестера (ratio) и цене боя в
     * запасе хода группы (или в контакте); без стаи — просто идём. Ценность — очки размена за тик пути.
     * Текущая цель держится, пока проходит по мягкому порогу (гистерезис).
     */
    private fun chooseFlagObjective(ctx: Ctx, group: List<Creep>, pushRatio: Double): Objective? {
        if (group.isEmpty()) return null
        var best: Objective? = null
        for (f in ctx.flags) {
            if (f.ours) continue
            if (!captureAllowed(ctx, f)) continue
            val flow = flowTo(ctx, f.pos)
            val travel = group.maxOf { pathTicks(it, flow, it.x * 100 + it.y) }
            if (travel >= Int.MAX_VALUE / 4) continue
            val pack = packAt(ctx, f.pos, flow, travel)
            val current = f.id == objectiveFlagId
            val ratio = if (current) LOCAL_ENTER_RATIO else pushRatio
            // цена боя — гейт на ВХОД к охраняемому флагу (лазейка «уже в контакте» отправила армию к дальнему
            // флагу с девятью охранниками сквозь наступающую армию — стенд rush, t=61)
            val ok = pack.isEmpty() || (ourPowerOf(group, pack) >= enemyPowerOf(pack, group) * ratio &&
                fightCost(pack, group) <= group.maxOf { speedSlack(it) })
            if (!ok) continue
            // гистерезис: текущая цель ценнее на четверть, чтобы не прыгать между равными; дорогой по силе — позже
            val value = f.swing * captureCost(ctx, f) / (travel + 10) * (if (current) 1.25 else 1.0)
            if (best == null || value > best.value) best = Objective(f, pack, value, travel)
        }
        return best
    }

    /** Точка отхода: дом и углы НАШЕЙ половины — достижимая и самая дальняя от центра армии врага (отход в
     *  дальний угол через всю карту вёл сквозь врага, и армию добивали по одному — стенд rush). */
    /** Враг уходит: за CHASE_WINDOW тиков отдалился от ТОГДАШНЕГО центра нашей армии больше чем на клетку. Кайтер
     *  держит дистанцию, и «дистанция до нас не растёт» его не выдаёт — выдаёт движение прочь от места, где мы были. */
    private fun evasive(e: Creep): Boolean {
        val h = enemyCellHist[e.id] ?: return false
        if (h.size < CHASE_WINDOW || ourCentroidHist.size < CHASE_WINDOW) return false
        val c0 = ourCentroidHist.first()
        val old = h.first()
        val cPos = InfluenceMap.cell(c0 / 100, c0 % 100)
        val oldPos = InfluenceMap.cell(old / 100, old % 100)
        return getRange(e, cPos) > getRange(oldPos, cPos) + 1
    }

    /** Ловим ли враг: вплотную к нашему вооружённому (MELEE_KEEP_RANGE), медленнее нашего самого быстрого или не
     *  уходит (см. evasive). Только за ловимым идут стая, охота, добивание и местный бросок (см. CHASE_WINDOW). */
    private fun catchable(e: Creep, armed: List<Creep>): Boolean =
        armed.any { getRange(e, it) <= MELEE_KEEP_RANGE } ||
            plainPeriod(e) > (armed.minOfOrNull { plainPeriod(it) } ?: 1) ||
            !evasive(e)

    private fun retreatPoint(ctx: Ctx): Position {
        val enemy = ctx.enemyCentroid ?: return ctx.home
        // точка одна на весь отход, пока враг не ближе к ней, чем мы: смена точки на ходу ((3,96), потом (96,96))
        // развела армию по трём углам карты, и погоня добила всех поодиночке (матч 4)
        retreatTarget?.let { t -> if (getRange(t, enemy) > getRange(t, ctx.ourCentroid)) return t }
        val corners = listOf(InfluenceMap.cell(3, 3), InfluenceMap.cell(3, 96), InfluenceMap.cell(96, 3), InfluenceMap.cell(96, 96))
        // дом, пока враг не ближе к нему, чем мы; угол — только тогда: «выигрышный по дистанции» угол (3,96) в
        // матче 5 был ловушкой — армия отошла в него от кайтеров и была расстреляна, не имея куда шагнуть
        val enemyBetween = getRange(enemy, ctx.home) < getRange(ctx.ourCentroid, ctx.home)
        val candidates = if (enemyBetween) listOf(ctx.home) + corners.filter { DistanceMap.inOurHalf(it.x, it.y) } else listOf(ctx.home)
        var best = ctx.home
        var bestScore = Int.MIN_VALUE
        for (c in candidates) {
            val flow = flowTo(ctx, c)
            val reach = ctx.army.count { flow[it.x * 100 + it.y] >= 0 }
            if (reach == 0) continue
            // выигрыш дистанции от врага НА КЛЕТКУ ПУТИ: «самая дальняя от врага» точка (3,96) лежала за его
            // флангом — 66 клеток пути ради 62 дистанции, мимо его строя; дом в 17 клетках даёт 22
            val score = getRange(c, enemy) - getRange(c, ctx.ourCentroid)
            if (score > bestScore) { bestScore = score; best = c }
        }
        retreatTarget = best
        return best
    }

    /** Пост: центр наших флагов (их и держим), без флагов — дом. */
    private fun postPoint(ctx: Ctx): Position =
        centroidOf(ctx.flags.filter { it.ours }.map { it.pos }) ?: ctx.home

    private fun runArmy(ctx: Ctx) {
        val army = ctx.army
        if (army.isEmpty()) return
        val allies = ctx.myCreeps
        val enemyCreeps = ctx.enemyCreeps
        val combatEnemies = ctx.combatEnemies

        val strikers = army.filter { fullSpeed(it) && hasWeapon(it) }
        val mobileArmy = army.filter { canMove(it) }
        val chasers = strikers.ifEmpty { mobileArmy }
        // кого вообще можно догнать (см. catchable): добивание по перевесу идёт только за ними
        val huntable = combatEnemies.filter { catchable(it, chasers) }

        // ---- постура ----
        val ours = ourPowerOf(army, combatEnemies)
        val theirs = enemyPowerOf(combatEnemies, army)
        val nearRange = if (posture == Posture.RETREAT) NEAR_RANGE + NEAR_RELEASE else NEAR_RANGE
        val enemyNear = combatEnemies.any { e -> army.any { getRange(e, it) <= nearRange } }
        // контакт решает сам: пассивного поста в контакте нет — он отдаёт армию по одному (стенд rush: десять
        // за двоих). Отход из контакта возможен, только если он не бегство: никто из врагов-мили не вплотную
        // и наш строй не медленнее их самого быстрого — при равной скорости преследователь стреляет в спину
        // каждый тик, а обездвиженные остаются врагу (стенд rush: отход при 1250 против 1619 отдал ещё
        // шестерых). Иначе в контакте — бой всем составом, даже слабее: рубка с фокусом лучше разгрома
        val contact = inContact(combatEnemies, army)
        val meleeAdjacent = combatEnemies.any { e -> hasMelee(e) && army.any { getRange(e, it) <= 1 } }
        val ourPeriod = mobileArmy.maxOfOrNull { plainPeriod(it) } ?: 1
        val theirPeriod = combatEnemies.filter { canMove(it) }.minOfOrNull { plainPeriod(it) } ?: Int.MAX_VALUE / 4
        // при РАВНОЙ скорости отход из контакта без мили вплотную — размен выстрелами в обе стороны, не бегство; из
        // точки отхода, куда уже пришли, отходить некуда — бой (матч 5: семеро в углу (3,96) при «отходе» не
        // шевелились и не били, пока их расстреливали с трёх клеток)
        val atRetreatPoint = retreatTarget?.let { getRange(ctx.ourCentroid, it) <= POST_STANDOFF + ARRIVED_SLACK } ?: false
        // из контакта отхода нет: «отход, пока мили не вплотную» мигал ДОБИТЬ/ОТХОД через тик и отдавал армию по одному
        // в матчах 4, 6 и 7 (в седьмом — при 1.53, без единой потери у врага); при равной скорости из контакта не
        // уйти, и единственный способ его кончить — бой строем
        val retreatFeasible = !contact && !atRetreatPoint
        val weaker = theirs >= ours * (if (posture == Posture.RETREAT) RETREAT_RELEASE_RATIO else RETREAT_RATIO)
        // из идущего боя (см. RETREAT_CONTACT_RATIO) — только при явном проигрыше
        val weakerContact = theirs >= ours * (if (posture == Posture.ANNIHILATE) RETREAT_CONTACT_RATIO else if (posture == Posture.RETREAT) RETREAT_RELEASE_RATIO else RETREAT_RATIO)
        // ДОБИТЬ по перевесу — с гистерезисом; по контакту — пока контакт есть (без гистерезиса: см. PUSH_RELEASE_RATIO)
        val stalemate = behindTicks >= BEHIND_PATIENCE
        val pushRatio = if (stalemate) PUSH_RATIO_STALEMATE else if (behindOnScore) PUSH_RATIO_BEHIND else PUSH_RATIO
        val pushRelease = if (stalemate) PUSH_RELEASE_RATIO_STALEMATE else if (behindOnScore) PUSH_RELEASE_RATIO_BEHIND else PUSH_RELEASE_RATIO
        pushing = huntable.isNotEmpty() && strikers.isNotEmpty() && ours >= theirs * (if (pushing) pushRelease else pushRatio)
        // бой по контакту — пока отход невозможен: мили врага вплотную. Решение ТИК ЗА ТИКОМ, и это не дрожание, а
        // кайт погони: слабее — отходим, стреляя и рубя на ходу (strike/shoot идут в любой постуре); догнал мили —
        // вся армия разворачивается на него (авангард погони один против всех), отстал — снова отход. На стенде
        // sleeper при 0.83 это 10:0; «поймали — деремся до конца контакта» дало 2:11, «слабее — только отход» 2:6
        val contactFight = combatEnemies.isNotEmpty() && strikers.isNotEmpty() && contact && !(retreatFeasible && weakerContact)
        val annihilate = pushing || contactFight
        val retreat = combatEnemies.isNotEmpty() && !annihilate && enemyNear && weaker && retreatFeasible
        val objective = if (annihilate || retreat) null else chooseFlagObjective(ctx, strikers.ifEmpty { mobileArmy }, pushRatio)
        val newPosture = when {
            annihilate -> Posture.ANNIHILATE
            retreat -> Posture.RETREAT
            objective != null -> Posture.FLAG
            else -> Posture.HOLD
        }
        objectiveFlagId = objective?.flag?.id
        if (newPosture != Posture.RETREAT) retreatTarget = null
        val retreatTo = if (newPosture == Posture.RETREAT) retreatPoint(ctx) else null
        val post = postPoint(ctx)
        val postureKey = "$newPosture:${objectiveFlagId ?: ""}"
        if (DEBUG_LOG && (postureKey != postureLogged || getTicks() % (LOG_EVERY * 10) == 0)) {
            postureLogged = postureKey
            println("posture: $newPosture t=${getTicks()} our=${ours.toInt()} enemy=${theirs.toInt()} near=$enemyNear contact=$contact pushing=$pushing huntable=${huntable.size}/${combatEnemies.size} retreatFeasible=$retreatFeasible strikers=${strikers.size}/${army.size} " +
                "obj=${objective?.let { "(${it.flag.pos.x},${it.flag.pos.y})${typeChar(it.flag.type)}${it.flag.score} pack=${it.pack.size} travel=${it.travel} v=${(it.value * 100).toInt()}" } ?: "-"} " +
                "retreatTo=${retreatTo?.let { "(${it.x},${it.y})" } ?: "-"} post=(${post.x},${post.y}) behind=$behindOnScore/$behindTicks hunt=$huntingThreat")
        }
        posture = newPosture

        // ---- общие цели ----
        val centroid = ctx.ourCentroid
        val ourHalfCombat = combatEnemies.filter { DistanceMap.inOurHalf(it.x, it.y) }
        val ourHalfSoft = enemyCreeps.filter { c -> combatEnemies.none { it.id == c.id } && DistanceMap.inOurHalf(c.x, c.y) }
        fun arrivalOf(c: Creep) = arrivalById[c.id] ?: Int.MAX_VALUE / 2
        val threat = ourHalfCombat.filter { catchable(it, chasers) }.minWithOrNull(compareBy<Creep>({ arrivalOf(it) }, { getRange(it, centroid) }))
        // рейдер: чужой безоружный на нашей половине — тот, что ближе к нашему флагу (захватчик идёт к нему); гонимся,
        // только если стрелки бьют стаю вокруг него: без этой проверки армия гналась за безоружным остовом к
        // стоявшей за ним армии врага и вошла в бой при 0.77 (матч 3, t=100)
        val raider = ourHalfSoft.minByOrNull { r -> minOf(getRange(r, centroid), ctx.flags.filter { !it.theirs }.minOfOrNull { getRange(r, it.pos) } ?: 99) }
            ?.takeIf { r ->
                // стая рейдера — и те, кто дойдёт до него не позже нас: скаут в 12 клетках впереди своей армии был
                // «без охраны», и армия вышла из дома ему навстречу — прямо под удар всей армии врага (матч 6, t=50)
                val field = flowTo(ctx, r)
                val ourTravel = chasers.map { pathTicks(it, field, it.x * 100 + it.y) }.filter { it < Int.MAX_VALUE / 4 }.maxOrNull() ?: Int.MAX_VALUE / 4
                val pack = packAt(ctx, r, field, ourTravel)
                catchable(r, chasers) && (pack.isEmpty() || (strikers.isNotEmpty() && ourPowerOf(strikers, pack) >= enemyPowerOf(pack, strikers) * PUSH_RATIO))
            }
        // охота на угрозу на нашей половине — решение группы с гистерезисом: стрелки против всей стаи у угрозы
        huntingThreat = posture != Posture.RETREAT && threat != null && strikers.isNotEmpty() && run {
            val field = flowTo(ctx, threat)
            val ourTravel = strikers.map { pathTicks(it, field, it.x * 100 + it.y) }.filter { it < Int.MAX_VALUE / 4 }.maxOrNull() ?: Int.MAX_VALUE / 4
            val pack = combatEnemies.filter { getRange(it, threat) <= ENGAGE_RANGE + RANGED_RANGE || (!stationary(it) && pathTicks(it, field, it.x * 100 + it.y) <= ourTravel) }
            val o = ourPowerOf(strikers, pack)
            val t = enemyPowerOf(pack, strikers)
            o >= t * (if (huntingThreat) PUSH_RELEASE_RATIO else PUSH_RATIO) &&
                (fightCost(pack, strikers) <= strikers.maxOf { speedSlack(it) } || inContact(pack, strikers))
        }
        aggressiveIds.retainAll { id -> army.any { it.id == id } }
        rallyingIds.retainAll { id -> army.any { it.id == id } }
        lastHits.keys.retainAll { id -> army.any { it.id == id } }
        lastCell.keys.retainAll { id -> army.any { it.id == id } }

        val enemyPositions = enemyCreeps.mapTo(HashSet()) { it.x * 100 + it.y }
        val blockedSet: Set<Int> = ctx.blocked.mapTo(HashSet()) { it.x * 100 + it.y } + ctx.flagCells
        val meleeEnemies = enemyCreeps.filter { InfluenceMap.profileOf(it).melee > 0.0 }

        // фокус-файр: добиваемые за тик -> наибольшая угроза на хит (урон, который враг СЕЙЧАС наносит нам, плюс
        // его лечение, делённые на его хиты: мили вплотную за 1000 хитов снимает 90, стрелок за 800 — 40, лекарь
        // за 600 — 36; «лекари первыми» без учёта хитов вело огонь мимо мили, который резал наш строй)
        val inFireRange = enemyCreeps.filter { e -> army.any { it.getRangeTo(e) <= RANGED_RANGE } }
        val focusPool = inFireRange.filter { e -> combatEnemies.any { it.id == e.id } }.ifEmpty { inFireRange }
        fun fireAvailableAt(e: Creep) = army.filter { it.getRangeTo(e) <= RANGED_RANGE }.sumOf { InfluenceMap.profileOf(it).ranged }
        fun threatPerHit(e: Creep): Double {
            val p = InfluenceMap.profileOf(e)
            val meleeLive = p.melee > 0.0 && army.any { getRange(e, it) <= MELEE_KEEP_RANGE }
            val rangedLive = p.ranged > 0.0 && army.any { getRange(e, it) <= RANGED_RANGE }
            return ((if (meleeLive) p.melee else p.melee * MELEE_KITE_DISCOUNT) + (if (rangedLive) p.ranged else p.ranged * 0.5) + p.heal) / e.hits.coerceAtLeast(1)
        }
        val focusTarget = focusPool.maxWithOrNull(
            compareBy<Creep> { if (it.hits <= fireAvailableAt(it) * InfluenceMap.takenOf(it)) 1 else 0 }
                .thenBy { threatPerHit(it) }
                .thenByDescending { it.hits }
                .thenByDescending { getRange(it, centroid) }
        )
        val occupantAt = HashMap<Int, Creep>()
        for (c in ctx.active) occupantAt[c.x * 100 + c.y] = c
        // добить: цель армии — ближайший к центру армии боевой враг (по пути); в бою ПО КОНТАКТУ (без перевеса) —
        // только из стаи, с которой контакт: контакт с одним стрелком не повод идти на армию врага за полкарты
        val contactPack = combatEnemies.filter { e -> army.any { getRange(e, it) <= ENGAGE_RANGE + RANGED_RANGE } }
        val prey = when {
            posture != Posture.ANNIHILATE -> null
            pushing -> huntable.minByOrNull { pathTicksFrom(ctx, centroid, it) }
            else -> contactPack.filter { catchable(it, chasers) }.minByOrNull { pathTicksFrom(ctx, centroid, it) }
        }
        val armedCentroid = centroidOf(mobileArmy.filter { hasWeapon(it) }.ifEmpty { army }) ?: centroid
        // захватчик флага-цели — ближайший к флагу ВООРУЖЁННЫЙ член группы (одной клетки на всех не хватит; лекарь
        // ходит за подопечным, и назначенный захватчиком лекарь тысячу тиков стоял рядом с флагом — стенд greedy)
        val objectiveCapturer = objective?.let { o -> mobileArmy.filter { hasWeapon(it) }.ifEmpty { mobileArmy }.minByOrNull { getRange(it, o.flag.pos) }?.id }
        // флаг рядом (не наш, свободный, без врага в дальности, разрешён) — на него шагает ближайший из наших
        val grabberOf = HashMap<String, String>()
        for (f in ctx.flags) {
            if (f.ours || f.occupant != null || f.id == objectiveFlagId) continue
            if (combatEnemies.any { getRange(it, f.pos) <= RANGED_RANGE + 1 }) continue
            if (!captureAllowed(ctx, f)) continue
            val near = mobileArmy.filter { getRange(it, f.pos) <= 3 }.minByOrNull { getRange(it, f.pos) } ?: continue
            grabberOf[near.id] = f.id
        }

        // построение перед контактом (см. FORM_RANGE): авангард — ближайший к врагу ходячий вооружённый; готовность —
        // доля вооружённых в RALLY_RANGE от него, собравшихся в FORM_RANGE; клетки под огнём — в дальности стрелка
        val formers = mobileArmy.filter { hasWeapon(it) }
        val formVan = if (combatEnemies.isEmpty()) null else formers.minWithOrNull(compareBy<Creep>({ f -> combatEnemies.minOf { getRange(f, it) } }, { it.id }))
        val formationGathered = formVan == null || run {
            val near = formers.filter { getRange(it, formVan) <= RALLY_RANGE }
            val needed = maxOf(2, ceil(FORM_SHARE * near.size).toInt())
            near.count { getRange(it, formVan) <= FORM_RANGE } >= needed
        }
        val formWaiting = formVan != null && !formationGathered && combatEnemies.any { e -> formers.any { getRange(e, it) <= ENGAGE_RANGE + RANGED_RANGE } }
        if (!formWaiting) formWaitSince = -1 else if (formWaitSince < 0) formWaitSince = getTicks()
        val formationReady = formationGathered || (formWaitSince >= 0 && getTicks() - formWaitSince >= FORM_PATIENCE)
        val fireCells = HashSet<Int>()
        for (e in combatEnemies) for (dx in -RANGED_RANGE..RANGED_RANGE) for (dy in -RANGED_RANGE..RANGED_RANGE) {
            val x = e.x + dx; val y = e.y + dy
            if (x in 0..99 && y in 0..99) fireCells.add(x * 100 + y)
        }

        for (creep in army) {
            val mobile = strikers.any { it.id == creep.id }
            val healer = !hasWeapon(creep) && hasHeal(creep)
            val nearestEnemyRange = combatEnemies.minOfOrNull { getRange(creep, it) } ?: 99
            val localAllies = army.filter { getRange(creep, it) <= (if (posture == Posture.ANNIHILATE || posture == Posture.FLAG) ENGAGE_RANGE else RANGED_RANGE + 1) }
            val localEnemies = combatEnemies.filter { getRange(creep, it) <= ENGAGE_RANGE + RANGED_RANGE }
            val ratio = if (creep.id in aggressiveIds) LOCAL_ENTER_RATIO else PUSH_RATIO
            val ghost = run {
                val prev = lastHits[creep.id]
                val cell = lastCell[creep.id]
                if (prev == null || cell == null) 0 else {
                    val lost = prev - creep.hits
                    var explained = 0.0
                    val taken = InfluenceMap.takenOf(creep)
                    for (s in prevShooters) {
                        val d = maxOf(abs(s.cell / 100 - cell / 100), abs(s.cell % 100 - cell % 100))
                        if (d <= RANGED_RANGE) explained += s.ranged * taken
                        if (d <= 1) explained += s.melee * taken
                    }
                    if (lost > explained + 1.0) lost else 0
                }
            }
            if (ghost > 0 && DEBUG_LOG && getTicks() - (ghostLogged[creep.id] ?: -100) >= 10) {
                ghostLogged[creep.id] = getTicks()
                val nearest = combatEnemies.minOfOrNull { getRange(creep, it) } ?: -1
                println("ghost damage t=${getTicks()}: ${creep.id} -$ghost at (${creep.x},${creep.y}) hits=${creep.hits} nearestCombat=$nearest — источник не виден")
            }
            // локальный перевес: бойцы, способные стрелять по той же цели через тик-другой, против врагов в их
            // досягаемости; цена боя — по ГРУППЕ (самый большой запас хода), в контакте цена больше не гейт
            val localAggressive = when {
                posture == Posture.RETREAT -> inContact(localEnemies, localAllies) && ourPowerOf(localAllies, localEnemies) >= enemyPowerOf(localEnemies, localAllies) * ratio
                // добивание: армия в целом сильнее (или в контакте без отхода), но ЯВНО слабейшая на месте группа
                // отходит к массе — «всегда агрессивен» посылал четверых на двенадцать (матч 2 на стенде). Вход в
                // бой при 0.9 (при равных силах никто не вступал в бой — рывок врага кончался ничьёй), выход — только
                // при разгроме на месте (ANNIHILATE_HOLD_RATIO): в гуще боя местный счёт скачет, и бойцы по одному
                // «отходили к центру» и гибли поодиночке (стенд rush 7:2 против прежних 0:12)
                // отходить к массе есть смысл, только если масса не здесь: когда рядом больше половины армии, это и
                // есть масса, и «отход к центру» был шагом на месте под ударами (матч 3, t=140–280: армия из
                // семи-восьми «отходила к центру» сто сорок тиков и потеряла всех по одному, не стреляя в ответ)
                posture == Posture.ANNIHILATE -> !USE_LOCAL_ANNIHILATE || localEnemies.isEmpty() || localAllies.size * 2 >= army.size ||
                    ourPowerOf(localAllies, localEnemies) >= enemyPowerOf(localEnemies, localAllies) * (if (creep.id in aggressiveIds) ANNIHILATE_HOLD_RATIO else LOCAL_ENTER_RATIO)
                localEnemies.isEmpty() -> true
                else -> ourPowerOf(localAllies, localEnemies) >= enemyPowerOf(localEnemies, localAllies) * ratio &&
                    (fightCost(localEnemies, localAllies) <= localAllies.maxOf { speedSlack(it) } || inContact(localEnemies, localAllies))
            }
            if (localAggressive) aggressiveIds.add(creep.id) else aggressiveIds.remove(creep.id)
            val engage = if (localAggressive && !healer) combatEnemies.filter { getRange(creep, it) <= ENGAGE_RANGE && catchable(it, chasers) }.minByOrNull { getRange(creep, it) } else null
            // поводок (см. LEASH_RANGE): при враге рядом дальше поводка от центра армии — к центру
            val leashed = !healer && canMove(creep) && posture != Posture.RETREAT && localEnemies.isNotEmpty() && getRange(creep, armedCentroid) > LEASH_RANGE
            val closeIn = if (localAggressive) CLOSE_STANDOFF else RANGED_RANGE
            val melee = isMelee(creep) && !hasRanged(creep)
            val grab = grabberOf[creep.id]?.let { id -> ctx.flags.firstOrNull { it.id == id } }
            // лекарь держится вплотную к самому раненому бойцу РЯДОМ (в дальности лечения плюс шаг), иначе идёт к
            // ближайшему ходячему бойцу — не к самому раненому через полкарты: два лекаря шли к обездвиженному
            // остову за стеной, а строй ждал их у флага (стенд greedy)
            val healMate = if (healer) {
                val fighters = army.filter { it.id != creep.id && hasWeapon(it) }
                fighters.filter { getRange(creep, it) <= HEAL_RANGE + 1 }.maxByOrNull { it.hitsMax - it.hits }
                    ?: fighters.filter { canMove(it) }.minByOrNull { getRange(creep, it) }
                    ?: fighters.minByOrNull { getRange(creep, it) }
            } else null
            // сбор: по полю марша (флаг-цель или пост, в обход врагов) авангард — самый продвинутый из ходячих
            // вооружённых (при равном поле — меньший id); кто дальше RALLY_RANGE от авангарда, идёт к нему
            // только на марше к флагу-цели: в HOLD цель — точка, к ней сходятся и так, а в ANNIHILATE ожидание
            // «далёкого» напарника, занятого своим боем, останавливало армию (стенд greedy)
            // ходячий по canMove, не «полноскоростной»: покалеченный участник, не входящий в сбор, но ждущий
            // далёких, замыкал группу в тупик (стенд rush: 10 против 2 до конца матча)
            val groupedPre = USE_RALLY && !healer && canMove(creep) && posture == Posture.FLAG
            val marchTarget: Position? = objective?.flag?.pos
            var rallyTo: Position? = null
            if (groupedPre && marchTarget != null) {
                val mf = flowAvoiding(ctx, marchTarget, creep)
                val my = mf[creep.x * 100 + creep.y]
                var van: Creep? = null
                var vanFlow = my
                var vanId = creep.id
                for (m in mobileArmy) {
                    if (m.id == creep.id || !hasWeapon(m)) continue
                    val d = mf[m.x * 100 + m.y]
                    if (d < 0) continue
                    if (vanFlow < 0 || d < vanFlow || (d == vanFlow && m.id < vanId)) { vanFlow = d; vanId = m.id; van = m }
                }
                // с гистерезисом: с клетки «13 от авангарда» крип шёл к нему, со следующей («12») — снова к цели,
                // и два шага туда-обратно длились до конца матча, а авангард ждал (стенд rush)
                val rallyRange = if (creep.id in rallyingIds) RALLY_RANGE / 2 else RALLY_RANGE
                if (van != null && getRange(creep, van) > rallyRange) { rallyTo = InfluenceMap.cell(van.x, van.y); rallyingIds.add(creep.id) }
                else rallyingIds.remove(creep.id)
            } else rallyingIds.remove(creep.id)
            // построение: вне огня и без готовности авангард и собравшиеся у него стоят, остальные идут к нему
            val forming = formVan != null && !formationReady && !healer && canMove(creep) && posture != Posture.RETREAT &&
                localEnemies.isNotEmpty() && nearestEnemyRange > RANGED_RANGE
            val formHold = forming && (formVan!!.id == creep.id || getRange(creep, formVan) <= FORM_RANGE)
            val formGo = forming && !formHold
            val target: Position
            val standoff: Int
            var avoid = false
            when {
                // отход — по обычному полю: поле «в обход» стоящих врагов (а дерущиеся стоят) увело пару в обход
                // стенного блока на другой край карты (матч 4)
                posture == Posture.RETREAT && retreatTo != null -> { target = retreatTo; standoff = 1 }
                formGo -> { target = InfluenceMap.cell(formVan!!.x, formVan.y); standoff = 1 }
                healer && healMate != null -> { target = healMate; standoff = 1 }
                leashed -> { target = armedCentroid; standoff = CLOSE_STANDOFF; avoid = true }
                engage != null -> { target = engage; standoff = if (melee) 1 else closeIn }
                grab != null -> { target = grab.pos; standoff = 0; avoid = true }
                // добивание без местного перевеса — отход к массе армии, а не бросок на «ближайшую добычу»; без
                // ловимой добычи (кайтеры) — тоже к массе: стоим строем и стреляем в то, что подойдёт
                posture == Posture.ANNIHILATE && !healer && (!localAggressive || prey == null) -> { target = armedCentroid; standoff = CLOSE_STANDOFF; avoid = true }
                prey != null -> { target = prey; standoff = if (melee) 1 else closeIn }
                rallyTo != null -> { target = rallyTo; standoff = CLOSE_STANDOFF; avoid = true }
                objective != null -> {
                    val capturer = objectiveCapturer == creep.id
                    target = objective.flag.pos
                    standoff = if (capturer) 0 else CLOSE_STANDOFF
                    avoid = true
                }
                threat != null && huntingThreat && mobile -> { target = threat; standoff = if (melee) 1 else closeIn }
                raider != null && mobile && !healer -> { target = raider; standoff = if (melee) 1 else RANGED_RANGE }
                else -> { target = post; standoff = POST_STANDOFF; avoid = true }
            }
            val flow = if (avoid) flowAvoiding(ctx, target, creep) else flowTo(ctx, target)

            val nearbyEnemies = combatEnemies.filter { getRange(creep, it) <= 12 }
            val inCombat = combatEnemies.any { creep.getRangeTo(it) <= RANGED_RANGE + 2 }
            val underFire = InfluenceMap.damageAt(creep.x, creep.y, combatEnemies) > 0.0 || ghost > 0
            // бегство: смертельный урон за два тика; безоружный лекарь — от врага рядом, если рядом нет ни одного
            // вооружённого своего (при нём лекарь стоит и лечит: бегущий лекарь — потерянные 72 в тик, матч 3);
            // невидимый урон
            // вооружённый бежит, только когда его ДОБИВАЮТ: за прошлый тик снято не меньше половины оставшихся хитов и
            // осталось меньше трети. Прежнее «весь возможный огонь по клетке за два тика больше хитов» в бою 12 на 12
            // верно для КАЖДОЙ клетки у вражеского блоба (1260 против 1600), и трое мили с полными хитами
            // разворачивались спиной в первый тик контакта — их резали в спину, строй рассыпался (стенд sleeper,
            // t=550; матчи 4–6 в первом размене теряли 1:5 при равной силе)
            val lostLastTick = lastHits[creep.id]?.let { it - creep.hits } ?: 0
            val mustFlee = (healer && nearbyEnemies.any { getRange(creep, it) <= RANGED_RANGE + 1 } && army.none { it.id != creep.id && hasWeapon(it) && getRange(creep, it) <= HEAL_RANGE }) ||
                (lostLastTick * 2 >= creep.hits && creep.hits * 3 < creep.hitsMax) ||
                (ghost > 0 && creep.hits <= ghost)

            // сплочение: авангард ждёт отставших группы (в тиках ИХ хода), пока сам не под огнём и напарник
            // не в бою; при враге в досягаемости зазор тесный — собираемся ДО входа под огонь
            val myFlow = flow[creep.x * 100 + creep.y]
            val grouped = !healer && (posture == Posture.ANNIHILATE || posture == Posture.FLAG || (huntingThreat && threat != null && target === threat))
            // напарники строя — ходячие ВООРУЖЁННЫЕ: у лекаря своя цель (подопечный), и взаимное ожидание «лекарь
            // отстал от флага — боец отстал от подопечного лекаря» запирало группу навсегда (стенд greedy)
            val mates = if (grouped) mobileArmy.filter { it.id != creep.id && hasWeapon(it) } else emptyList()
            val mateFighting = mates.any { m -> combatEnemies.any { m.getRangeTo(it) <= RANGED_RANGE + 2 } }
            val gap = if (localEnemies.isEmpty()) COHESION_GAP else ENGAGE_COHESION_TICKS
            // идущий к авангарду (rallyTo) не ждёт никого: четверо шли к авангарду и «ждали» одиночку в 14 клетках,
            // а тот ждал их — взаимное ожидание на 1600 тиков (стенд m2 scouts, v4)
            // подбирающий флаг рядом (grab) не ждёт никого, и его никто не ждёт: он ждал по сплочению группу у своего
            // флага, а группа ждала его как «отставшего» на пути к цели — взаимное ожидание на 1200 тиков при живом
            // враге из двух скаутов на наших флагах (стенд m3 kite, проигрыш по очкам 9128:23661)
            val cohesionHold = grouped && rallyTo == null && grab == null && !underFire && !mateFighting && myFlow >= 0 && creep.getRangeTo(target) > standoff + ARRIVED_SLACK && run {
                var lagging = false
                for (m in mates) {
                    if (getRange(creep, m) <= RANGED_RANGE) continue
                    if (grabberOf.containsKey(m.id)) continue
                    val d = flow[m.x * 100 + m.y]
                    if (d < 0) continue
                    // напарник на другом обходе (только на марше к флагу): далеко и не впереди — ждём его, он идёт
                    // к нам (см. rallyTo)
                    if (USE_RALLY && posture == Posture.FLAG && getRange(creep, m) > RALLY_RANGE && plainPeriod(m) <= RALLY_MAX_PERIOD && (d > myFlow || (d == myFlow && m.id > creep.id))) { lagging = true; break }
                    val lag = (d - myFlow) * plainPeriod(m)
                    if (lag in (gap + 1)..COHESION_GAP_MAX) { lagging = true; break }
                }
                lagging
            }
            // отход строем (см. RETREAT_GAP): ушедший вперёд ждёт самого отставшего вооружённого, пока сам вне огня
            val retreatHold = posture == Posture.RETREAT && !healer && canMove(creep) && !underFire && nearestEnemyRange > RANGED_RANGE + 1 && myFlow >= 0 && run {
                val rear = mobileArmy.filter { it.id != creep.id && hasWeapon(it) }.maxOfOrNull { flow[it.x * 100 + it.y] } ?: -1
                rear >= 0 && rear - myFlow > RETREAT_GAP
            }
            val hold = cohesionHold || formHold || retreatHold

            val step: Position? = when {
                !canMove(creep) -> null
                mustFlee -> fleeStep(creep, nearbyEnemies, ctx.dangerMatrix) ?: pathStep(creep, retreatTo ?: post, 1, ctx.dangerMatrix)
                hold -> null
                else -> {
                    // клетка флага открыта только назначенному на него (захватчик цели, «подобрать» рядом)
                    val designated = grab?.pos ?: objective?.flag?.pos?.takeIf { objectiveCapturer == creep.id }
                    var myBlocked = if (designated != null) blockedSet - (designated.x * 100 + designated.y) else blockedSet
                    // плотность (см. COMPACT_RANGE): при враге в досягаемости — только на клетки строя
                    if (localEnemies.isNotEmpty() && posture != Posture.RETREAT && canMove(creep)) {
                        val armedMates = mobileArmy.filter { it.id != creep.id && hasWeapon(it) }
                        val loose = HashSet<Int>()
                        for ((dx, dy) in DIRECTIONS) {
                            val x = creep.x + dx; val y = creep.y + dy
                            if (x < 0 || y < 0 || x > 99 || y > 99) continue
                            val c = InfluenceMap.cell(x, y)
                            val compact = getRange(c, armedCentroid) <= COMPACT_RANGE || armedMates.count { getRange(c, it) <= 1 } >= 2
                            if (!compact) loose.add(x * 100 + y)
                        }
                        if (loose.isNotEmpty()) myBlocked = myBlocked + loose
                    }
                    bestSingleMove(creep, target, flow, standoff, localAggressive, inCombat, enemyCreeps, allies, meleeEnemies, myBlocked, enemyPositions, occupantAt)
                }
            }
            if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
                println("  f${creep.id} (${creep.x},${creep.y}) ${bodySummary(creep)} hits=${creep.hits}/${creep.hitsMax} tgt=(${target.x},${target.y}) so=$standoff flow=$myFlow flee=$mustFlee combat=$inCombat aggr=$localAggressive hold=$hold${if (formHold) "(form)" else if (retreatHold) "(rear)" else ""}${if (leashed) " leash" else ""} spd=${plainPeriod(creep)} fatigue=${creep.fatigue} step=${step?.let { "(${it.x},${it.y})" } ?: "stay"}${if (TrafficManager.isStuck(creep.id)) " STUCK" else ""}")
            }
            if (step != null) TrafficManager.request(creep, step, FIGHTER_PRIORITY)
            lastHits[creep.id] = creep.hits
            lastCell[creep.id] = creep.x * 100 + creep.y
        }

        prevShooters = combatEnemies.map { val p = InfluenceMap.profileOf(it); Shooter(it.x * 100 + it.y, p.ranged, p.melee) }
        healAndShoot(army, allies, enemyCreeps, focusTarget)
    }

    /** Удар мили: фокус-цель вплотную, иначе самый раненый сосед. */
    private fun strike(creep: Creep, enemyCreeps: List<Creep>, focusTarget: Creep?) {
        if (!hasMelee(creep)) return
        val adjacent = enemyCreeps.filter { creep.getRangeTo(it) <= 1 }
        val target: Creep? = when {
            focusTarget != null && creep.getRangeTo(focusTarget) <= 1 -> focusTarget
            adjacent.isNotEmpty() -> adjacent.minByOrNull { it.hits }
            else -> null
        }
        target?.let { creep.attack(it) }
    }

    /** Через сколько тиков боевые враги дойдут до нашего дома — по темпу сближения за APPROACH_WINDOW;
     *  новый враг — по ходу его тела вдоль поля. Заполняет approachingIds/arrivalById (для приоритета угроз). */
    private fun enemyArrivalTicks(ctx: Ctx) {
        val now = getTicks()
        approachHistory.keys.retainAll { id -> ctx.combatEnemies.any { it.id == id } }
        approachingIds.clear()
        arrivalById.clear()
        val enemyApproach = flowTo(ctx, ctx.home)
        for (e in ctx.combatEnemies) {
            val approach = enemyApproach[e.x * 100 + e.y]
            if (approach < 0) continue
            val h = approachHistory.getOrPut(e.id) { ArrayDeque() }
            h.addLast(now to approach)
            while (h.isNotEmpty() && h.first().first < now - APPROACH_WINDOW) h.removeFirst()
            val (t0, a0) = h.first()
            val arrival = if (now - t0 < APPROACH_WINDOW / 2) pathTicks(e, enemyApproach, e.x * 100 + e.y) else {
                val rate = (a0 - approach).toDouble() / (now - t0)
                if (rate > 0.0) (approach / rate).toInt() else Int.MAX_VALUE / 2
            }
            arrivalById[e.id] = arrival
            if (arrival < Int.MAX_VALUE / 2) approachingIds.add(e.id)
        }
    }

    private fun healAndShoot(active: List<Creep>, allies: List<Creep>, enemyCreeps: List<Creep>, focusTarget: Creep?) {
        val healDone = HashMap<String, Int>()
        val incoming = HashMap<String, Int>()
        fun need(target: Creep): Int {
            val deficit = target.hitsMax - target.hits
            val expected = incoming.getOrPut(target.id) { InfluenceMap.damageAt(target.x, target.y, enemyCreeps).toInt() }
            return deficit + expected - (healDone[target.id] ?: 0)
        }
        for (creep in active) {
            strike(creep, enemyCreeps, focusTarget)
            val healParts = creep.body.count { it.type == HEAL && it.hits > 0 }
            if (healParts > 0) {
                val candidates = allies.filter { !it.spawning && need(it) > 0 && creep.getRangeTo(it) <= HEAL_RANGE }
                val closeTarget = candidates.filter { creep.getRangeTo(it) <= 1 }.maxByOrNull { need(it) }
                if (closeTarget != null) {
                    creep.heal(closeTarget)
                    healDone[closeTarget.id] = (healDone[closeTarget.id] ?: 0) + InfluenceMap.modified(creep, EFF_HEAL_MODIFIER, healParts * HEAL_POWER.toDouble()).toInt()
                    shoot(creep, enemyCreeps, focusTarget)
                    continue
                }
                val farTarget = candidates.filter { it.hitsMax - it.hits > 0 }.maxByOrNull { need(it) }
                if (farTarget != null) {
                    creep.rangedHeal(farTarget)
                    healDone[farTarget.id] = (healDone[farTarget.id] ?: 0) + InfluenceMap.modified(creep, EFF_HEAL_MODIFIER, healParts * RANGED_HEAL_POWER.toDouble()).toInt()
                    continue
                }
            }
            shoot(creep, enemyCreeps, focusTarget)
        }
    }

    private fun shoot(creep: Creep, enemyCreeps: List<Creep>, focusTarget: Creep?) {
        if (!hasRanged(creep)) return
        val creepsInRange = enemyCreeps.filter { creep.getRangeTo(it) <= RANGED_RANGE }
        if (creepsInRange.isEmpty()) return
        val combatInRange = creepsInRange.filter { c -> val p = InfluenceMap.profileOf(c); p.melee + p.ranged + p.heal > 0.0 }
        val massPool = if (combatInRange.isNotEmpty()) combatInRange else creepsInRange
        val massValue = massPool.sumOf { InfluenceMap.rangedRate(creep.getRangeTo(it)) }
        // против армии с лекарями — только фокус: веер размазывает урон по трём-пяти целям, и три лекаря (216 в тик)
        // вылечивают его целиком, пока враг сосредоточенно снимает 540 в тик с одного нашего (стенд sleeper: наш чистый
        // урон 200 в тик против 540). Веер — когда врагу нечем лечить или он даёт не меньше двух с половиной выстрелов
        val enemyHeals = enemyCreeps.any { InfluenceMap.profileOf(it).heal > 0.0 }
        if (massValue > (if (enemyHeals) 2.5 else 1.0)) {
            creep.rangedMassAttack()
        } else {
            // фокус-цель вне дальности — добиваем самого раненого боевого в дальности (безоружных — в последнюю очередь)
            val target = when {
                focusTarget != null && creep.getRangeTo(focusTarget) <= RANGED_RANGE -> focusTarget
                else -> massPool.minByOrNull { it.hits }
            }
            target?.let { creep.rangedAttack(it) }
        }
    }

    private fun bestSingleMove(
        creep: Creep,
        target: Position,
        flow: IntArray,
        standoff: Int,
        aggressive: Boolean,
        inCombat: Boolean,
        enemyCreeps: List<Creep>,
        allies: List<Creep>,
        meleeEnemies: List<Creep>,
        blockedSet: Set<Int>,
        enemyPositions: Set<Int>,
        occupantAt: Map<Int, Creep>,
    ): Position? {
        val hereDist = flow[creep.x * 100 + creep.y]
        // своя клетка с форой — только ПРИБЫВ (в зазоре standoff): вне боя не дёргаемся ради мелочи (см.
        // STAY_BIAS); на марше форы нет — вместе со штрафом за соседей она съедала выигрыш шага (матч 2)
        val settled = !inCombat && hereDist in 0..(standoff + ARRIVED_SLACK)
        var bestScore = scoreCell(creep, creep.x, creep.y, target, flow, standoff, aggressive, inCombat, enemyCreeps, allies, meleeEnemies) + (if (settled) STAY_BIAS else 0.0)
        var bx = creep.x; var by = creep.y
        var pushDist = if (hereDist >= 0) hereDist else Int.MAX_VALUE
        var pushX = -1; var pushY = -1
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
            val s = scoreCell(creep, x, y, target, flow, standoff, aggressive, inCombat, enemyCreeps, allies, meleeEnemies)
            if (s > bestScore) { bestScore = s; bx = x; by = y }
        }
        if (bx != creep.x || by != creep.y) return InfluenceMap.cell(bx, by)
        if (pushX >= 0) return InfluenceMap.cell(pushX, pushY)
        if (blockedByStatic && hereDist >= 0) {
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

    /** Оценка клетки: приблизиться на standoff к цели по реальному пути; в бою — исходящий урон, чистый
     *  входящий (с хилом), влияние, штраф за зону мили, за болото (без перевеса) и цена прижатия. */
    private fun scoreCell(creep: Creep, x: Int, y: Int, target: Position, flow: IntArray, standoff: Int, aggressive: Boolean, inCombat: Boolean, enemyCreeps: List<Creep>, allies: List<Creep>, meleeEnemies: List<Creep>): Double {
        val flowDist = flow[x * 100 + y]
        val cheb = getRange(InfluenceMap.cell(x, y), target)
        val firePenalty = when {
            cheb <= standoff -> (standoff - cheb) * 0.5
            flowDist < 0 -> 1000.0
            flowDist > standoff -> (flowDist - standoff).toDouble()
            else -> (standoff - flowDist) * 0.5
        }
        val separation = allies.count { !it.spawning && (it.x != x || it.y != y) && getRange(InfluenceMap.cell(x, y), it) <= SEPARATION_RADIUS } * PAIR_W_SPREAD
        // на марше (вне боя и дальше зазора прибытия) соседи не штрафуются: в колонне клетка впереди почти всегда
        // соседствует с двумя союзниками (−8), и штраф вместе с форой своей клетки (+5) съедал выигрыш шага (10) —
        // двенадцать бойцов простояли 1800 тиков в 12 клетках от цели (матч 2); разрежение нужно на месте и в бою
        if (!inCombat) return -firePenalty * PAIR_W_DIST - (if (flowDist > standoff + ARRIVED_SLACK) 0.0 else separation)

        val damage = InfluenceMap.netDamageAt(x, y, enemyCreeps, allies)
        // лекарь: вплотную к подопечному (поле), из клеток равной близости — под меньшим ФАКТИЧЕСКИМ огнём (fireAt:
        // без шага сближения мили — иначе клетка рядом с бойцом, который рубится вплотную, «стоит» 720 и лекарь
        // стоит в трёх клетках; от мили, что действительно подошёл, лекарь отойдёт следующим тиком)
        if (!hasWeapon(creep) && hasHeal(creep)) {
            val fire = InfluenceMap.fireAt(x, y, enemyCreeps)
            val pinnedHealer = (periodAt(creep, x, y) - 1) * fire * PAIR_W_DAMAGE
            return -firePenalty * PAIR_W_DIST - fire * HEALER_W_DAMAGE - separation - pinnedHealer
        }
        val meleeSelf = isMelee(creep) && !hasRanged(creep)
        val meleeWeight = if (aggressive) PAIR_W_MELEE * AGGRO_MELEE_FACTOR else PAIR_W_MELEE
        val meleeThreat = if (meleeSelf) 0.0 else meleeEnemies.count { getRange(InfluenceMap.cell(x, y), it) <= MELEE_KEEP_RANGE } * meleeWeight
        val swampPenalty = if (!aggressive && DistanceMap.isSwamp(x, y)) PAIR_W_SWAMP else 0.0
        val influence = if (meleeSelf && aggressive) 0.0 else InfluenceMap.influenceAt(x, y, allies, enemyCreeps)
        val outgoing = if (!aggressive && damage > 0.0) 0.0 else if (meleeSelf) (if (enemyCreeps.any { getRange(InfluenceMap.cell(x, y), it) <= 1 }) 1.0 else 0.0) else if (hasRanged(creep)) outgoingValue(x, y, enemyCreeps) else 0.0
        val damageTerm = if (aggressive) 0.0 else damage * PAIR_W_DAMAGE
        val pinned = (periodAt(creep, x, y) - 1) * InfluenceMap.fireAt(x, y, enemyCreeps) * PAIR_W_DAMAGE
        return -firePenalty * PAIR_W_DIST - damageTerm + influence * PAIR_W_INFLUENCE +
            outgoing * PAIR_W_OUTGOING - meleeThreat - separation - swampPenalty - pinned
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
        return !DistanceMap.isTerrainWall(x, y)
    }

    // ==================== тело, скорость, мощь ====================

    private fun canMove(creep: Creep) = creep.body.any { it.type == MOVE && it.hits > 0 }
    private fun hasMelee(creep: Creep) = creep.body.any { it.type == ATTACK && it.hits > 0 }
    private fun isMelee(creep: Creep) = creep.body.any { it.type == ATTACK }
    private fun hasRanged(creep: Creep) = creep.body.any { it.type == RANGED_ATTACK && it.hits > 0 }
    private fun hasHeal(creep: Creep) = creep.body.any { it.type == HEAL && it.hits > 0 }
    private fun hasWeapon(creep: Creep) = hasRanged(creep) || hasMelee(creep)

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

    /** Вес тела для усталости: части не-MOVE и не-CARRY ПО ТИПУ (мёртвые весят — movement.js:237)
     *  плюс гружёные CARRY. */
    private fun bodyWeight(creep: Creep): Int {
        val parts = creep.body.count { it.type != MOVE && it.type != CARRY }
        val carried = creep.store[RESOURCE_ENERGY] ?: 0
        return parts + (carried + CARRY_CAPACITY - 1) / CARRY_CAPACITY
    }

    private fun liveMoves(creep: Creep) = creep.body.count { it.type == MOVE && it.hits > 0 }

    /** Период хода (тиков на клетку): после шага fatigue = вес × цена местности − 2 × живые MOVE, дальше
     *  −2×MOVE в тик, следующий ход при нуле (tick.js:105, movement.js:237). */
    private fun periodOn(weight: Int, moves: Int, rate: Int): Int {
        if (moves <= 0) return Int.MAX_VALUE / 4
        val left = weight * rate - 2 * moves
        return if (left <= 0) 1 else 1 + (left + 2 * moves - 1) / (2 * moves)
    }

    private fun plainPeriod(creep: Creep) = periodOn(bodyWeight(creep), liveMoves(creep), 2)
    private fun periodAt(creep: Creep, x: Int, y: Int) =
        periodOn(bodyWeight(creep), liveMoves(creep), if (DistanceMap.isSwamp(x, y)) 10 else 2)
    private fun swampPeriod(creep: Creep) = periodOn(bodyWeight(creep), liveMoves(creep), 10)
    private fun fullSpeed(creep: Creep) = plainPeriod(creep) == 1

    /** Сколько урона крип ещё выдержит, не теряя скорости (части умирают спереди). */
    private fun speedSlack(creep: Creep): Int {
        val weight = bodyWeight(creep)
        if (weight == 0) return creep.hits // тела без веса (чистый MOVE) скорости не теряют
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

    /** Цена боя: хиты, которые снимут с нас, пока враги умирают по одному под нашим огнём (лекари первыми,
     *  их лечение вычитается); урон врага — с НАШИМ множителем входящего, наш — с ЕГО. */
    private fun fightCost(enemies: List<Creep>, ours: List<Creep>): Double {
        val ourDps = ours.sumOf { effectiveDps(it, enemies) }
        if (ourDps <= 0.0) return Double.MAX_VALUE
        val order = enemies.sortedWith(compareByDescending<Creep> { InfluenceMap.profileOf(it).heal }.thenBy { it.hits })
        val ourTaken = ours.maxOfOrNull { InfluenceMap.takenOf(it) } ?: 1.0
        var remaining = enemies.sumOf { effectiveDps(it, ours) } * ourTaken
        var heal = enemies.sumOf { InfluenceMap.profileOf(it).heal }
        var damage = 0.0
        for (e in order) {
            val net = ourDps * InfluenceMap.takenOf(e) - heal
            if (net <= 0.0) return Double.MAX_VALUE
            damage += remaining * e.hits / net
            remaining -= effectiveDps(e, ours) * ourTaken
            heal -= InfluenceMap.profileOf(e).heal
        }
        return damage
    }

    private fun lanchester(dps: Double, enemyHeal: Double, hits: Double): Double =
        sqrt(maxOf(0.0, dps - enemyHeal) * maxOf(0.0, hits))

    /** Доля удара мили, которая ДОЙДЁТ: кайт-дисконт, только если противники сплошь стрелки, никто не
     *  прижат вплотную и мили медленнее каждого из них на болоте. */
    private fun meleeFactor(unit: Creep, opponents: List<Creep>): Double {
        if (opponents.any { hasMelee(it) || getRange(unit, it) <= MELEE_KEEP_RANGE }) return 1.0
        val ranged = opponents.filter { hasRanged(it) }
        if (ranged.isEmpty()) return 1.0
        val mine = swampPeriod(unit)
        return if (ranged.any { swampPeriod(it) > mine }) 1.0 else MELEE_KITE_DISCOUNT
    }

    /** Действенный урон крипа в тик против группы (с его эффектами): стрельба целиком, мили — по meleeFactor. */
    private fun effectiveDps(unit: Creep, opponents: List<Creep>, rangedK: Double = 1.0, meleeK: Double = 1.0): Double {
        val p = InfluenceMap.profileOf(unit)
        return p.ranged * rangedK + p.melee * meleeK * meleeFactor(unit, opponents)
    }

    /** Хиты в счёте мощи: по доле удара, которая дойдёт (кайтимая мили в бою не участвует; лекарь и
     *  безоружный — полностью), с поправкой на множитель входящего урона (флаг уязвимости). */
    private fun weightedHits(unit: Creep, opponents: List<Creep>, hitsK: Double = 1.0): Double {
        val p = InfluenceMap.profileOf(unit)
        val raw = p.ranged + p.melee
        val taken = InfluenceMap.takenOf(unit).coerceAtLeast(0.01)
        val share = if (raw <= 0.0) 1.0 else effectiveDps(unit, opponents) / raw
        return unit.hits * share * hitsK / taken
    }

    /** Гипотетические множители стороны сверх текущих эффектов (маргинальная цена флага, см. powerAfter). */
    private class HypoMods(val ranged: Double = 1.0, val melee: Double = 1.0, val heal: Double = 1.0, val hits: Double = 1.0)
    private val NO_MODS = HypoMods()
    private fun hypoMods(type: String, k: Double) = HypoMods(
        ranged = if (type == EFF_RANGED_ATTACK_MODIFIER) k else 1.0,
        melee = if (type == EFF_ATTACK_MODIFIER) k else 1.0,
        heal = if (type == EFF_HEAL_MODIFIER) k else 1.0,
        hits = if (type == EFF_DAMAGE_TAKEN_MODIFIER) 1.0 / k else 1.0,
    )

    /** Мощь стороны по Ланчестеру против группы противника: √(её урон − его лечение) × её хиты; mods — её
     *  гипотетические множители, oppMods — множитель лечения противника. */
    private fun powerOf(side: List<Creep>, opp: List<Creep>, mods: HypoMods, oppMods: HypoMods): Double {
        val dps = side.sumOf { effectiveDps(it, opp, mods.ranged, mods.melee) }
        val heal = opp.sumOf { InfluenceMap.profileOf(it).heal } * oppMods.heal
        return lanchester(dps, heal, side.sumOf { weightedHits(it, opp, mods.hits) })
    }

    /** НАША мощь против группы врага (текущие эффекты). */
    private fun ourPowerOf(ours: List<Creep>, theirs: List<Creep>): Double = powerOf(ours, theirs, NO_MODS, NO_MODS)

    /** Мощь врага против нашей группы (текущие эффекты). */
    private fun enemyPowerOf(theirs: List<Creep>, ours: List<Creep>): Double = powerOf(theirs, ours, NO_MODS, NO_MODS)

    /** Тики хода крипа по спуску вдоль поля потока от клетки до цели — по его телу и местности (periodAt). */
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

    /** Тики пути от точки до крипа по полю к нему (болото ×5, тело не учитывается) — для выбора жертвы. */
    private fun pathTicksFrom(ctx: Ctx, from: Position, to: Creep): Int {
        val flow = flowTo(ctx, to)
        val d = flow[from.x * 100 + from.y]
        return if (d < 0) Int.MAX_VALUE / 2 else d
    }

    private fun inContact(enemies: List<Creep>, ours: List<Creep>): Boolean =
        enemies.any { e -> ours.any { getRange(e, it) <= RANGED_RANGE + 1 } }

    /** Матрица пути захватчика: опасность + свои крипы дороже + не наши флаги стены, кроме allowCell (его флаг). */
    private fun crowdMatrixOf(ctx: Ctx, allowCell: Int): CostMatrix {
        val crowdMatrix = ctx.rawDanger.clone()
        for (ally in ctx.active) {
            val current = crowdMatrix.get(ally.x, ally.y)
            if (current < 255) crowdMatrix.set(ally.x, ally.y, minOf(254, current + CROWD_COST))
        }
        for (c in ctx.flagCells) if (c != allowCell) crowdMatrix.set(c / 100, c % 100, 255)
        return crowdMatrix
    }

    /** Жадный шаг бегства: свободная соседняя клетка (не стена, не чужой флаг, не занята) с наибольшей
     *  дальностью до ближайшего врага, при равной — под меньшим огнём; null — некуда. */
    private fun greedyFlee(ctx: Ctx, creep: Creep, enemies: List<Creep>): Position? {
        val occupied = (ctx.myCreeps + ctx.enemyCreeps).filter { !it.spawning }.mapTo(HashSet()) { it.x * 100 + it.y }
        var best: Position? = null
        var bestRange = enemies.minOfOrNull { getRange(creep, it) } ?: 0
        var bestFire = InfluenceMap.fireAt(creep.x, creep.y, enemies)
        for ((dx, dy) in DIRECTIONS) {
            if (dx == 0 && dy == 0) continue
            val x = creep.x + dx; val y = creep.y + dy
            if (x < 0 || y < 0 || x > 99 || y > 99) continue
            val key = x * 100 + y
            if (key in occupied || key in ctx.flagCells || DistanceMap.isWall(x, y) || ctx.blocked.any { it.x == x && it.y == y }) continue
            val pos = InfluenceMap.cell(x, y)
            val range = enemies.minOfOrNull { getRange(pos, it) } ?: 0
            val fire = InfluenceMap.fireAt(x, y, enemies)
            if (range > bestRange || (range == bestRange && fire < bestFire)) { best = pos; bestRange = range; bestFire = fire }
        }
        return best
    }

    private fun pathStep(creep: Creep, target: Position, range: Int, dangerMatrix: CostMatrix): Position? {
        val goal = SearchGoal(pos = target, range = range)
        val result = searchPath(creep, goal, SearchPathOptions(costMatrix = dangerMatrix))
        return result.path.firstOrNull()
    }

    private fun fleeStep(creep: Creep, enemies: List<Creep>, dangerMatrix: CostMatrix): Position? {
        if (enemies.isEmpty()) return null
        val goals = enemies.map { e -> SearchGoal(pos = InfluenceMap.cell(e.x, e.y), range = RANGED_RANGE) }.toTypedArray()
        val result = searchPath(creep, goals, SearchPathOptions(flee = true, costMatrix = dangerMatrix))
        return result.path.firstOrNull()
    }

    private fun centroidOf(points: List<Position>): Position? {
        if (points.isEmpty()) return null
        return InfluenceMap.cell(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)
    }

    // ==================== диагностика ====================

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

    /** ASCII-карта один раз: '#' стена, '~' болото, '.' равнина, 'F' флаг, 'm' наш крип, 'e' вражеский. */
    private fun logMap(flags: List<FlagInfo>, myCreeps: List<Creep>, enemyCreeps: List<Creep>) {
        val marks = HashMap<Int, Char>()
        fun mark(x: Int, y: Int, c: Char) { marks[x * 100 + y] = c }
        getObjectsByPrototype(StructureWall::class).forEach { mark(it.x, it.y, '#') }
        getObjectsByPrototype(StructureRampart::class).forEach { mark(it.x, it.y, 'R') }
        getObjectsByPrototype(StructureSpawn::class).forEach { mark(it.x, it.y, if (it.my == true) 'M' else 'E') }
        enemyCreeps.forEach { mark(it.x, it.y, 'e') }
        myCreeps.forEach { mark(it.x, it.y, 'm') }
        flags.forEach { mark(it.pos.x, it.pos.y, 'F') }

        var swamp = 0
        var wall = 0
        println("=== MAP (rows y=0..99, cols x=0..99) ===")
        for (y in 0..99) {
            val row = StringBuilder()
            for (x in 0..99) {
                val isWall = DistanceMap.isTerrainWall(x, y)
                val isSwamp = !isWall && DistanceMap.isSwamp(x, y)
                if (isSwamp) swamp++
                if (isWall) wall++
                val structure = marks[x * 100 + y]
                row.append(
                    when {
                        structure != null -> structure
                        isWall -> '#'
                        isSwamp -> '~'
                        else -> '.'
                    }
                )
            }
            println("${y.toString().padStart(2, '0')}:$row")
        }
        println("=== END MAP swamp=$swamp wall=$wall plain=${10000 - swamp - wall} ===")
    }
}
