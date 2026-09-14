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
import screeps.api.getCpuTime
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






    internal val PUSH_DWELL = CHASE_WINDOW
    /** Адресный урон этого тика по нашим (v229/v233): кто из его стрелков и мили в кого целится по модели его выбора. */
    internal val addressedDmg = HashMap<String, Double>()
    /** Приборы наблюдения 5: сколько раз скаут попадал в пул огня, сколько тиков он был в нашей дальности. */
    /** Флаги этого тика — чтобы приказ огня мог спросить «стоит ли скаут на не нашем флаге», не таская список. */
    internal var flagsNow: List<FlagInfo> = emptyList()

























    /** Окно размена для признака отхода — существующий срок «размен был недавно», а не новое число (v216). */
    internal val LEDGER_WINDOW = STALL_TICKS


    internal val capBlocked = HashMap<String, Int>()
    internal val capSeen = HashSet<String>()      // (тик, флаг) считается один раз, а не по разу на вызывающего
    internal val disarmedFoe = HashSet<String>()

    /** Крипов, вставших на каждом уровне ворот (индекс = порог выживания в тиках), и добор мимо ворот. */
    internal val gateLevels = IntArray(8)


    internal var goalField: IntArray? = null
    internal var goalSeeds: IntArray = IntArray(0)
    internal var goalCx = -1
    internal var goalCy = -1

    internal val DIRECTIONS = listOf(
        0 to 0, -1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1,
    )


    internal var mapMarks: HashMap<Int, Char>? = null   // метки дампа карты, снятые на первом тике
    internal var posture = Posture.HOLD
    internal var objectiveFlagId: String? = null


    internal val arrivalById = HashMap<String, Int>()
    internal var huntingThreat = false
    /** ДОБИТЬ по перевесу (не по контакту) — только к нему применяется гистерезис PUSH_RELEASE_RATIO. */
    internal var pushing = false
    /** Точка отхода — одна на весь отход (см. retreatPoint). */
    internal var retreatTarget: Position? = null
    internal val whyLines = ArrayList<String>()             // трасса решений мили за тик (см. TRACE_WHY)
    internal val whySum = HashMap<String, Int>()             // причины за сто тиков
    internal val escapeFlows = HashMap<Int, IntArray>()
    internal val escapeTheirs = HashMap<Int, Int>()
    internal val escapeNearest = HashMap<Int, Int>()   // клетка врага, ближайшего к точке
    internal var victimNow: Creep? = null          // стена лечения (v228): терявший больше всех за прошлый тик
    internal var victimSaveable = false            // ...и его потеря не больше доставимого в него лечения
    /** Лекарь при мили (v235, см. USE_HEALER_AT_MELEE): лекарь → id его фронтового мили и клетка при нём с тыла. */
    internal val meleeWardOf = HashMap<String, String>()
    internal val meleeWardCell = HashMap<String, Position>()
    internal val wallAddrHits = ArrayDeque<Boolean>()
    internal val wallLostHits = ArrayDeque<Boolean>()
    internal var wallCells: List<Position> = emptyList()   // клетки стены: соседние с жертвой, его вооружённые мили дальше двух
    internal val wallCellOf = HashMap<String, Position>()  // клетка стены, назначенная лекарю на этот тик
    internal var approachRate = 0.0
    internal var unflaggedRushNow = false                  // бросок безфлаговой армии на нас (см. EVADE_EQUAL_RATIO)
    internal var fightImminentNow = false                  // сомкнутая армия врага идёт на нас, с флагом или без (см. captureAllowed)
    internal var approachingNow = false                    // та же, но по его подходу, без безфлагового броска (v284, см. captureBlock)
    internal var rushStartDist = 0                         // расстояние между центрами на начало броска (v215)
    internal var fightImminentTicks = 0                    // тиков подряд «бой близко» (см. USE_RUSH_VETO_SUSTAINED)
    internal var noFireTicks = 0                           // тиков подряд враг с боем рядом и не снял с нас ни хита (см. USE_INTERCEPT)
    internal var enemyNotFightingNow = false               // фермер: noFireTicks ≥ STALL_TICKS (см. USE_INTERCEPT)
    internal var enemyMassedSignal = false                 // его вооружённые сомкнуты по форме или по прибытию (v281, readSignals)
    internal var firstFightTick = 0                        // тик первого размена (exchangeLive); 0 — первый бой впереди (v281)
    internal val idleRunnerTicks = HashMap<String, Int>()  // бегун → подряд тиков без цели (v85: поштучный отзыв)
    internal var lastDistanceKeptTick = -1000              // последний тик, когда погоня не сближала (см. USE_DETACH, v57)
    internal var farmerQuietNow = false                    // противник тих FARMER_QUIET с первой досягаемости (см. USE_FARMER_PACK_FREE)
    internal var lastHurtTick = 0                          // последний тик, когда враг снял с нас хиты (см. farmer в runArmy)
    internal var lastFireTick = -1000                      // последний тик, когда кто-то из наших бил или стрелял (см. USE_COLD_CONTACT)
    internal var lastReachTick = -1                        // последний тик с его вооружённым в ENGAGE_RANGE от наших
    internal var firstNearTick = -1                        // первый тик с его вооружённым в ENGAGE_RANGE + RANGED_RANGE (v72: признаки фермера — от него)
    internal var ourDamageTaken = 0                        // снято с нас за матч (см. USE_PUSH_LEDGER)
    internal var ledgerWindow = 0
    internal var ourLostWindow = 0
    internal var hisLostWindow = 0
    /** Чем заняты бегуны: пары по режимам (`dbg` — единственная точка, через которую проходят все ветки). */
    internal val runnerMode = HashMap<String, Int>()
    /** Темп очков на сотом и двухсотом тике — снимок дебюта, которого не снимал ни один прибор. */
    /** Почему у армии нет флаг-цели: пара по причинам против всех тиков (v216). */
    internal val objNone = HashMap<String, Int>()
    /** ...и разложение САМОГО выбора: какой фильтр снял флаг-кандидата (v216). */
    internal val objDrop = HashMap<String, Int>()
    internal var enemyDamageTaken = 0                      // снято с него за матч
    internal val lostTick = HashMap<String, Int>()   // потеря хитов за прошлый тик по всей армии, снятая до обновления lastHits (v109)
    internal val ghostLogged = HashMap<String, Int>()
    internal var prevShooters: List<Shooter> = emptyList()

    // ---------- счёт ----------
    internal var ourScore = 0.0
    internal var enemyScore = 0.0
    internal var ourRate = 0
    internal var enemyRate = 0
    /** По прогнозу (счёт + темп × остаток) мы проигрываем: очки важнее силы (см. captureAllowed). */
    internal var behindOnScore = false
    /** Сколько тиков подряд отстаём по прогнозу (см. BEHIND_PATIENCE). */
    internal var behindTicks = 0

    internal val flowFull = HashMap<Int, Boolean>()   // поле посчитано целиком (не ограничено NEAR_FLOW), см. flowTo/v131b
    internal var flowSig = 0                            // подпись препятствий, при которой считан кэш
    internal var bfsMaxCost = 0.0
    internal var bfsMaxTick = 0

    internal val cpuPhases = ArrayList<Pair<String, Double>>()

    /** Отметка «тик открыт» (см. USE_ABORT_REPAIR) и пара прибора: оборванных тиков / записей, положенных обратно. */
    private var tickOpen = false
    /** Вес тела и живые MOVE на этот тик (см. USE_BODY_MEMO); чистятся в начале тика. */
    internal val bodyWeightNow = HashMap<String, Int>()
    internal val liveMovesNow = HashMap<String, Int>()

    fun tick() {
        if (tickOpen) repairAfterAbort()
        tickOpen = true
        tickBody()
        tickOpen = false
    }

    private fun repairAfterAbort() {
        abortTicks++
        var maps = 0; var sets = 0; var entries = 0
        for (owner in listOf<Any>(this, InfluenceMap, DistanceMap, TrafficManager, Executor, Forecast, Memory)) {
            val r = AbortRepair.repairFields(owner)
            maps += r.maps; sets += r.sets; entries += r.entries
        }
        abortEntries += entries
        println("abort t=${getTicks()}: the previous tick did not finish — $maps maps and $sets sets rebuilt in place ($entries entries)")
    }

    private fun tickBody() {
        val buildWorldSeg = buildWorld(BuildWorldIn(
        ))
        val myCreeps = buildWorldSeg.myCreeps
        val enemyCreeps = buildWorldSeg.enemyCreeps
        val active = buildWorldSeg.active
        val combatEnemies = buildWorldSeg.combatEnemies
        val flags = buildWorldSeg.flags
        val wounded = buildWorldSeg.wounded
        val army = buildWorldSeg.army
        val runners = buildWorldSeg.runners
        val passiveEnemy = buildWorldSeg.passiveEnemy
        val ourCentroid = buildWorldSeg.ourCentroid
        val enemyCentroid = buildWorldSeg.enemyCentroid
        val ctx = buildWorldSeg.ctx
        val readSignalsSeg = readSignals(ctx, ReadSignalsIn(
            myCreeps = myCreeps,
            enemyCreeps = enemyCreeps,
            combatEnemies = combatEnemies,
            flags = flags,
            army = army,
            passiveEnemy = passiveEnemy,
            ourCentroid = ourCentroid,
            enemyCentroid = enemyCentroid,
            ctx = ctx,
        ))
        runRunners(ctx)
        cpuMark("runners")
        runArmy(ctx)

        // боевые интенты уходят в API до разрешения движения: стенд разрешает конфликты за клетку в порядке первого
        // интента крипа, и порядок «удар, затем ход» — часть тождества с эталоном v235 (движку порядок безразличен)
        TrafficManager.markOrdered(commandOf.keys)
        val moves = TrafficManager.resolve(active.filter { canMove(it) }, myCreeps + enemyCreeps)
        Arbiter.audit()
        Executor.run(moves)
        cpuMark("resolve")
        cpuSummary()
        // хвост тика после перебора командира (v262): наибольший за матч — запас бюджета перебора
        if (cmdSearched) { cmdTailMax = maxOf(cmdTailMax, cpuMs() - cmdEndMs); cmdSearched = false }
        val rememberTickSeg = rememberTick(ctx, RememberTickIn(
            myCreeps = myCreeps,
            enemyCreeps = enemyCreeps,
            army = army,
            ourCentroid = ourCentroid,
        ))
        val armedCentroid = rememberTickSeg.armedCentroid
        val printTickSeg = printTick(ctx, PrintTickIn(
            myCreeps = myCreeps,
            enemyCreeps = enemyCreeps,
            active = active,
            combatEnemies = combatEnemies,
            flags = flags,
            wounded = wounded,
            army = army,
            runners = runners,
            passiveEnemy = passiveEnemy,
            ourCentroid = ourCentroid,
            enemyCentroid = enemyCentroid,
            armedCentroid = armedCentroid,
        ))
    }

    /** Флаги, на которые наши крипы уже шагают в ЭТОТ тик (см. planCapture): два захвата одним тиком — D5 армией и H4
     *  скаутом — каждый в отдельности проходил порог паритета, вместе дали 0.93 и разгром 12:0 (стенд m9 hunter, t=98). */
    internal val plannedCaptures = HashSet<String>()
    internal val NO_FLOW = IntArray(10000) { -1 }
    internal var stalledNow = false                        // бесплодная охота (см. STALL_TICKS) — снимает и запрет захвата в контакте
    internal var stalemateNow = false                      // отставание с меньшим темпом дольше BEHIND_PATIENCE (см. PARITY_FLOOR_LOST)
    internal var hisTouchShare = 1.0
    internal var touchShare = 1.0                          // она же за окно; до заполнения окна — единица, чтобы вход в бой не менялся
    internal var touchMin = 1.0                            // минимум за матч — прибор
    internal val rungCount = HashMap<String, Int>()        // перепись решений (v203): какая ветка ЦЕЛИ выбрана, сколько раз
    internal val stepCount = HashMap<String, Int>()        // ...и какая ветка ШАГА
    internal val tacCount = HashMap<String, Int>()         // ...и какое «задание.терм» предложено арбитру (v252, прибор tac t=)
    internal val prioCount = HashMap<String, Int>()        // ...и с каким приоритетом (SURVIVE / MISSION / OPPORTUNITY)
    internal val missionOf = HashMap<String, Char>()      // крип → буква задания его отряда этим тиком (v252, из Strategist.snapshot)
    internal val passCount = HashMap<String, Int>()        // ...и какой проход раздачи командира сколько клеток назначил
    internal var planGunsIn = 0; internal var planGunsAll = 0        // прибор согласованности строя (v200)
    internal var planMeleeHealed = 0; internal var planMeleeAll = 0
    internal var planHealBehind = 0; internal var planHealAll = 0
    /** ЗОНД РАЗДАЧИ ЛЕКАРЕЙ (v224): раздач / выбрана клетка вплотную к бойцу вне его огня / такая свободная клетка была
     *  рядом, а выбрана другая / из них кандидат не прошёл ворота выживания; и средняя разница слагаемых оценки
     *  «выбранная минус кандидат» (положительная — слагаемое тянуло ОТ кандидата): притяжение, огонь, линия, экран,
     *  занятость, стоять, жилец, очаг. */
    internal var hpN = 0; internal var hpAdj = 0; internal var hpAvail = 0; internal var hpGate = 0
    internal val hpDelta = DoubleArray(8)
    internal var stalemateTicks = 0                        // сколько тиков подряд бой не двигается ни в чью пользу
    internal var patMax = 0                                // самый длинный пат за матч — прибор, чтобы правило не мерили вслепую
    internal val pressChase = HashMap<String, ArrayDeque<ChaseSample>>()  // погоня за целью прижима по тикам (см. PRESS_GIVEUP)
    internal val pressGiveUp = HashMap<String, Int>()      // цель прижима, от которой отказались, → тик, до которого




    private fun runArmy(ctx: Ctx) {
        val army = ctx.army
        if (army.isEmpty()) return
        val armyMeasuresSeg = armyMeasures(ctx, ArmyMeasuresIn(
            army = army,
        ))
        val allies = armyMeasuresSeg.allies
        val enemyCreeps = armyMeasuresSeg.enemyCreeps
        val combatEnemies = armyMeasuresSeg.combatEnemies
        val strikers = armyMeasuresSeg.strikers
        val armedEnemies = armyMeasuresSeg.armedEnemies
        val enemyMassedNow = armyMeasuresSeg.enemyMassedNow
        val now = armyMeasuresSeg.now
        val exchangeLedger = armyMeasuresSeg.exchangeLedger
        val exchangeLive = armyMeasuresSeg.exchangeLive
        val exchangePaying = armyMeasuresSeg.exchangePaying
        val mobileArmy = armyMeasuresSeg.mobileArmy
        val commandArmy = armyMeasuresSeg.commandArmy
        val chasers = armyMeasuresSeg.chasers
        val huntable = armyMeasuresSeg.huntable
        val meleeAdjacent = armyMeasuresSeg.meleeAdjacent
        val exchangeRecent = armyMeasuresSeg.exchangeRecent
        val fightOn = armyMeasuresSeg.fightOn
        val cornered = armyMeasuresSeg.cornered
        val stalled = armyMeasuresSeg.stalled
        val ours = armyMeasuresSeg.ours
        val theirs = armyMeasuresSeg.theirs
        val theirsUp = armyMeasuresSeg.theirsUp
        val theirsDown = armyMeasuresSeg.theirsDown
        val enemyNear = armyMeasuresSeg.enemyNear
        val massCentroid = armyMeasuresSeg.massCentroid
        val massArmy = armyMeasuresSeg.massArmy
        val contact = armyMeasuresSeg.contact
        val breakOffNow = armyMeasuresSeg.breakOffNow
        val retreatFeasible = armyMeasuresSeg.retreatFeasible
        val armyStrategySeg = armyStrategy(ctx, ArmyStrategyIn(
            army = army,
            enemyCreeps = enemyCreeps,
            combatEnemies = combatEnemies,
            strikers = strikers,
            armedEnemies = armedEnemies,
            enemyMassedNow = enemyMassedNow,
            now = now,
            exchangeLedger = exchangeLedger,
            exchangeLive = exchangeLive,
            exchangePaying = exchangePaying,
            mobileArmy = mobileArmy,
            chasers = chasers,
            huntable = huntable,
            meleeAdjacent = meleeAdjacent,
            exchangeRecent = exchangeRecent,
            fightOn = fightOn,
            cornered = cornered,
            stalled = stalled,
            ours = ours,
            theirs = theirs,
            theirsUp = theirsUp,
            theirsDown = theirsDown,
            enemyNear = enemyNear,
            massCentroid = massCentroid,
            massArmy = massArmy,
            contact = contact,
            breakOffNow = breakOffNow,
            retreatFeasible = retreatFeasible,
        ))
        val sweep = armyStrategySeg.sweep
        val gathered = armyStrategySeg.gathered
        val leadHolds = armyStrategySeg.leadHolds
        val hisStill = armyStrategySeg.hisStill
        val warmNow = armyStrategySeg.warmNow
        val holdingSpot = armyStrategySeg.holdingSpot
        val objective = armyStrategySeg.objective
        val evadeTo = armyStrategySeg.evadeTo
        val combatArmy = armyStrategySeg.combatArmy
        val theirMeleeIn = armyStrategySeg.theirMeleeIn
        val underTheirFire = armyStrategySeg.underTheirFire
        val enemyRetreating = armyStrategySeg.enemyRetreating
        val decision = armyStrategySeg.decision
        val retreatTo = armyStrategySeg.retreatTo
        val post = armyStrategySeg.post
        val cmdWhyNow = armyStrategySeg.cmdWhyNow
        val centroid = armyStrategySeg.centroid
        val threat = armyStrategySeg.threat
        val raider = armyStrategySeg.raider
        val armyTargetsSeg = armyTargets(ctx, ArmyTargetsIn(
            army = army,
            enemyCreeps = enemyCreeps,
            combatEnemies = combatEnemies,
            armedEnemies = armedEnemies,
            mobileArmy = mobileArmy,
            chasers = chasers,
            huntable = huntable,
            ours = ours,
            contact = contact,
            sweep = sweep,
            gathered = gathered,
            objective = objective,
            combatArmy = combatArmy,
            centroid = centroid,
        ))
        val enemyPositions = armyTargetsSeg.enemyPositions
        val blockedSet = armyTargetsSeg.blockedSet
        val meleeEnemies = armyTargetsSeg.meleeEnemies
        val killTicks = armyTargetsSeg.killTicks
        val focusTarget = armyTargetsSeg.focusTarget
        val focusOrder = armyTargetsSeg.focusOrder
        val occupantAt = armyTargetsSeg.occupantAt
        val prey = armyTargetsSeg.prey
        val armedCentroid = armyTargetsSeg.armedCentroid
        val objectiveCapturer = armyTargetsSeg.objectiveCapturer
        val grabberOf = armyTargetsSeg.grabberOf
        val formVan = armyTargetsSeg.formVan
        val formationReady = armyTargetsSeg.formationReady
        val reachCells = armyTargetsSeg.reachCells
        val reachNow = armyTargetsSeg.reachNow
        val healersAlive = armyTargetsSeg.healersAlive
        val slotOf = armyTargetsSeg.slotOf
        val armyStanceSeg = armyStance(ctx, ArmyStanceIn(
            army = army,
            combatEnemies = combatEnemies,
            strikers = strikers,
            armedEnemies = armedEnemies,
            exchangeLive = exchangeLive,
            meleeAdjacent = meleeAdjacent,
            cornered = cornered,
            contact = contact,
            breakOffNow = breakOffNow,
            leadHolds = leadHolds,
            hisStill = hisStill,
            warmNow = warmNow,
            holdingSpot = holdingSpot,
            objective = objective,
            evadeTo = evadeTo,
            combatArmy = combatArmy,
            theirMeleeIn = theirMeleeIn,
            underTheirFire = underTheirFire,
            decision = decision,
            retreatTo = retreatTo,
            cmdWhyNow = cmdWhyNow,
            prey = prey,
        ))
        val blockOn = armyStanceSeg.blockOn
        val armiesClosing = armyStanceSeg.armiesClosing
        val enemyApproaching = armyStanceSeg.enemyApproaching
        val ourYielding = armyStanceSeg.ourYielding
        val pressOn = armyStanceSeg.pressOn
        val armyBlockSeg = armyBlock(ctx, ArmyBlockIn(
            enemyCreeps = enemyCreeps,
            combatEnemies = combatEnemies,
            armedEnemies = armedEnemies,
            mobileArmy = mobileArmy,
            contact = contact,
            theirMeleeIn = theirMeleeIn,
            enemyRetreating = enemyRetreating,
            focusTarget = focusTarget,
            slotOf = slotOf,
            blockOn = blockOn,
            armiesClosing = armiesClosing,
            ourYielding = ourYielding,
            pressOn = pressOn,
        ))
        cpuMark("block")
        rotateByFocus(army, combatEnemies)
        val armyCommandSeg = armyCommand(ctx, ArmyCommandIn(
            army = army,
            enemyCreeps = enemyCreeps,
            combatEnemies = combatEnemies,
            armedEnemies = armedEnemies,
            enemyMassedNow = enemyMassedNow,
            mobileArmy = mobileArmy,
            commandArmy = commandArmy,
            ours = ours,
            contact = contact,
            cmdWhyNow = cmdWhyNow,
            focusTarget = focusTarget,
            armiesClosing = armiesClosing,
            enemyApproaching = enemyApproaching,
        ))
        cpuMark("command")
        val orderAuditSeg = orderAudit(ctx, OrderAuditIn(
            enemyCreeps = enemyCreeps,
            commandArmy = commandArmy,
            focusTarget = focusTarget,
        ))
        val healerWallSeg = healerWall(ctx, HealerWallIn(
            army = army,
            enemyCreeps = enemyCreeps,
            combatEnemies = combatEnemies,
        ))
        cpuMark("plan")
        // ПОКРИПНАЯ ЛЕСТНИЦА — В ТАКТИКЕ (v251, этап 9): тело цикла перенесено в Tactician.kt дословно, величины тика —
        // в ArmyTick; порядок крипов тот же, проход один (см. заголовок Tactician.kt)
        val tick = ArmyTick(
            army = army,
            allies = allies,
            enemyCreeps = enemyCreeps,
            combatEnemies = combatEnemies,
            strikers = strikers,
            armedEnemies = armedEnemies,
            enemyMassedNow = enemyMassedNow,
            now = now,
            mobileArmy = mobileArmy,
            chasers = chasers,
            stalled = stalled,
            contact = contact,
            objective = objective,
            evadeTo = evadeTo,
            combatArmy = combatArmy,
            retreatTo = retreatTo,
            post = post,
            threat = threat,
            raider = raider,
            enemyPositions = enemyPositions,
            blockedSet = blockedSet,
            meleeEnemies = meleeEnemies,
            killTicks = killTicks,
            focusTarget = focusTarget,
            occupantAt = occupantAt,
            prey = prey,
            armedCentroid = armedCentroid,
            objectiveCapturer = objectiveCapturer,
            grabberOf = grabberOf,
            formVan = formVan,
            formationReady = formationReady,
            reachCells = reachCells,
            reachNow = reachNow,
            healersAlive = healersAlive,
            slotOf = slotOf,
            pressOn = pressOn,
        )
        for (creep in army) creepTurn(creep, ctx, tick)

        val armyFireAndHealSeg = armyFireAndHeal(ctx, ArmyFireAndHealIn(
            army = army,
            allies = allies,
            enemyCreeps = enemyCreeps,
            combatEnemies = combatEnemies,
            focusTarget = focusTarget,
            focusOrder = focusOrder,
        ))
    }

    /** Урон, уже расписанный по цели в этом тике (v140, отказ от перебоя): чистится вместе с shotsAt. */
    internal val damageBooked = HashMap<String, Double>()


    internal var cmdMode = CmdMode.MARCH
    /** Событие этого тика для стратега (v242): флаг сменил владельца — считается при сборе флагов, до решения. */
    internal var flagFlipNow = false
    internal val fireOf = HashMap<String, String>() // крип → цель, назначенная командиром (v161)
    internal var orderAuditOk = 0
    internal var orderAuditN = 0
    internal var orderAuditCloser = 0
    internal var orderAuditSame = 0
    internal var orderFar = 0
    internal var orderClash = 0
    internal var orderFled = 0
    internal var orderBranch = 0
    internal val orderWas = HashMap<String, Pair<Int, Int>>()   // где крип стоял в момент приказа (v170)
    internal val orderFatigue = HashMap<String, Int>()
    internal var lostStay = 0        // приказ был «стой», а крип ушёл
    internal var lostStuck = 0       // крип остался на месте, хотя приказ был другой
    internal var lostFatigue = 0     // не мог двигаться от усталости
    internal var lostElsewhere = 0   // двинулся, но в другую клетку
    internal val orderDist = HashMap<String, Int>()
    internal val healOf = HashMap<String, String>() // лекарь → пациент, назначенный командиром (v162)
    /** Почему командир не правил — в ПОСЛЕДНИЙ тик, когда не правил. ⚠️ В строке `t=` (поле `cmd=…:причина`)
     *  это значение УСТАРЕВШЕЕ: оно пишется только на тиках без командира, поэтому `cmd=0/200:outmatched
     *  mode=FIGHT` значит «сейчас бой, а в последний тик без командира причиной был outmatched». Разбор серии
     *  v220 прочёл его как причину текущего тика и приписал разгромам «отход»; честная картина по тикам —
     *  гистограмма `cmdwhy` (v221). */
    internal var cmdBlocked = "-"
    /** РАЗЛОЖЕНИЕ НЕВХОДА В РЕЖИМ БОЯ (v215). Прежний `cmdBlocked` НАЗЫВАЛ причину, не проверив её: он писал
     *  «posture», если постура не ANNIHILATE, — а условие боя постуры ANNIHILATE не требует вовсе, оно требует
     *  `!pushing && underTheirFire && (сомкнут || шесть рядом) && постура не отход`. По логам рейтинговой серии
     *  из-за этого выходило, будто виновата постура. Прибор, называющий не тот множитель, отправляет чинить не
     *  то место, поэтому причина берётся из ТОЙ ЖЕ цепочки веток, что и сам режим. */
    internal val cmdWhy = HashMap<String, Int>()
    /** Идёт ли бой ПРЯМО СЕЙЧАС — считается до отряда и до командирской гонки, чтобы обе читали этот тик. */
    internal var fightOnNow = false
    /** Пара «крипов отозвано в кулак / тиков боя» (v215). */
    internal var recalled = 0
    internal var outmatchedTicks = 0                // сколько тиков подряд наша мощь ниже BREAK_OFF_RATIO от его (v185)
    internal val commandOf = HashMap<String, Position>()   // крип → клетка, назначенная командиром (v137)
    private var commandFocus: Creep? = null              // цель фокуса, выбранная симуляцией вместе с планом (v138)
    internal var focusBreakableNow = false            // дотягивающиеся стволы пробивают лечение фокуса (v218, см. USE_FAN_KEEPS_FOCUS)
    internal val shotsAt = HashMap<String, Int>()     // выстрелы по цели за тик (см. conc в строке t=)
    internal var concMax = 0
    /** Пара «предъявлений, где послабление проигранной гонки решило исход / всех предъявлений с этим признаком»
     *  (v218, см. lostRaceNow). Числитель — флаг, прошедший по PARITY_FLOOR_LOST и НЕ прошедший бы по
     *  PARITY_FLOOR. До починки клапана он обязан быть около нуля в забегах: разбор v217 дал 582/1753/2407
     *  отказа по паритету в трёх проигранных забегах при 40 в среднем по победам. */
    internal var lostRaceOpened = 0
    /** Пара «тиков в HOLD с растянутым строем стрелков / всех тиков в HOLD» (v218). Проверяет записанное в коде
     *  основание, по которому сбор (см. rallyTo) работает ТОЛЬКО в постуре FLAG: «в HOLD цель — точка, к ней
     *  сходятся и так». Если числитель мал — основание верно и трогать сбор незачем. Растяжка считается тем же
     *  порогом, каким сбор и включается (RALLY_RANGE). */
    internal var gatherSpread = 0
    /** Размен идёт прямо сейчас (v221, см. exchangeLive) — для гейта захвата, который зовётся из `runRunners`
     *  раньше `runArmy` и потому читает окно прошлого тика. */
    internal var exchangeLiveNow = false
    /** Удары мили по цели за тик (v221) — как `shotsAt`, но из `strike`; чистится там же. */
    internal val strikesAt = HashMap<String, Int>()
    /** Пара «сумма наибольшего числа ударов мили в одну цель за тик / тиков с ударами» и максимум (v221).
     *  Разбор блоб-поражений двух серий: во всех через 40 тиков после контакта он не потерял ни одного стрелка
     *  и ни одного мили, мы — стрелков и мили; его четыре мили кладут 960 в одну нашу цель в 1200 хитов. */
    internal var mconcAll = 0
    internal var mconcMax = 0
    /** Пара «крипо-тиков мили, чья цель ног — цель фокуса / крипо-тиков мили с целью ног» (v221). */
    internal var mpackHit = 0
    /** Погоня за кайтером (v221, только прибор, см. kiteChaseNow): тиков односторонней погони в ANNIHILATE /
     *  тиков ANNIHILATE; отказов безфлагового броска в такой погоне / всех отказов безфлагового броска. */
    internal var kiteChaseSeen = false
    internal var kchaseTicks = 0
    /** Разложение тиков ANNIHILATE без размена по источнику (v221, см. annEmptyAll): cmd — режим боя командира,
     *  push — толчок, spot — очаг, melee — его мили вплотную, corner — загнанная группа, still — контакт со стоящим
     *  (USE_WARM_NEEDS_HIS_MOVE), warm — тёплый контакт (с правкой обязан быть нулём), held — постура удержана
     *  гистерезисом без контакта. */
    internal val annEmpty = HashMap<String, Int>()
    internal var annEmptyAll = 0

    internal val NO_MODS = HypoMods()

}
