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






    /** Адресный урон этого тика по нашим (v229/v233): кто из его стрелков и мили в кого целится по модели его выбора. */
    internal val addressedDmg = HashMap<String, Double>()
    /** Приборы наблюдения 5: сколько раз скаут попадал в пул огня, сколько тиков он был в нашей дальности. */
    /** Флаги этого тика — чтобы приказ огня мог спросить «стоит ли скаут на не нашем флаге», не таская список. */
    internal var flagsNow: List<FlagInfo> = emptyList()



























    internal val disarmedFoe = HashSet<String>()






    internal var mapMarks: HashMap<Int, Char>? = null   // метки дампа карты, снятые на первом тике


    internal val arrivalById = HashMap<String, Int>()
    internal val whyLines = ArrayList<String>()             // трасса решений мили за тик (см. TRACE_WHY)
    internal val whySum = HashMap<String, Int>()             // причины за сто тиков
    internal val escapeFlows = HashMap<Int, IntArray>()
    internal val escapeTheirs = HashMap<Int, Int>()
    internal val escapeNearest = HashMap<Int, Int>()   // клетка врага, ближайшего к точке
    internal var victimNow: Creep? = null          // стена лечения (v228): терявший больше всех за прошлый тик
    internal var victimSaveable = false            // ...и его потеря не больше доставимого в него лечения
    internal val wallAddrHits = ArrayDeque<Boolean>()
    internal val wallLostHits = ArrayDeque<Boolean>()
    internal var wallCells: List<Position> = emptyList()   // клетки стены: соседние с жертвой, его вооружённые мили дальше двух
    internal val wallCellOf = HashMap<String, Position>()  // клетка стены, назначенная лекарю на этот тик
    internal var approachRate = 0.0
    internal var unflaggedRushNow = false                  // бросок безфлаговой армии на нас (см. EVADE_EQUAL_RATIO)
    internal var fightImminentNow = false                  // сомкнутая армия врага идёт на нас, с флагом или без (см. captureAllowed)
    internal var approachingNow = false                    // та же, но по его подходу, без безфлагового броска (v284, см. captureBlock)
    internal var enemyNotFightingNow = false               // фермер: noFireTicks ≥ STALL_TICKS (см. USE_INTERCEPT)
    internal var enemyMassedSignal = false
    /** Предсказанный урон его стволов по нашим на этот тик — по модели его выбора цели, что чаще попадает (v292, см.
     *  rotateByFocus); null, пока сверок меньше окна. Читает лечение вместо неадресного damageAt. */
    internal var focusPredDmg: Map<String, Double>? = null
    /** Его стволы, по сверке с фактом, бьют нашего с наименьшей долей хитов в досягаемости — охотятся за ранеными (v294,
     *  см. rotateByFocus): тогда раненые уходят к лекарям позади, а лекари стоят вне его досягаемости. */
    internal var huntsWounded = false
    internal val idleRunnerTicks = HashMap<String, Int>()  // бегун → подряд тиков без цели (v85: поштучный отзыв)
    internal var farmerQuietNow = false                    // противник тих FARMER_QUIET с первой досягаемости (см. USE_FARMER_PACK_FREE)
    internal var ledgerWindow = 0
    internal var ourLostWindow = 0
    internal var hisLostWindow = 0
    /** Темп очков на сотом и двухсотом тике — снимок дебюта, которого не снимал ни один прибор. */
    internal val lostTick = HashMap<String, Int>()   // потеря хитов за прошлый тик по всей армии, снятая до обновления lastHits (v109)
    internal val ghostLogged = HashMap<String, Int>()
    internal var prevShooters: List<Shooter> = emptyList()

    // ---------- счёт ----------
    internal var ourRate = 0
    internal var enemyRate = 0
    /** По прогнозу (счёт + темп × остаток) мы проигрываем: очки важнее силы (см. captureAllowed). */
    internal var behindOnScore = false



    /** Отметка «тик открыт» (см. USE_ABORT_REPAIR) и пара прибора: оборванных тиков / записей, положенных обратно. */
    private var tickOpen = false

    fun tick() {
        if (tickOpen) repairAfterAbort()
        tickOpen = true
        tickBody()
        tickOpen = false
    }

    private fun repairAfterAbort() {
        abortTicks.n++
        var maps = 0; var sets = 0; var entries = 0
        for (owner in listOf<Any>(this, InfluenceMap, DistanceMap, TrafficManager, Executor, Forecast, Memory, BodyMemo, Gauges)) {
            val r = AbortRepair.repairFields(owner)
            maps += r.maps; sets += r.sets; entries += r.entries
        }
        abortEntries.n += entries
        println("abort t=${getTicks()}: the previous tick did not finish — $maps maps and $sets sets rebuilt in place ($entries entries)")
    }

    private fun tickBody() {
        val ctx = Ctx(this)
        // ПРИБОРЫ ПЕРВЫХ ТИКОВ (v447, этап 6): зонд, дамп карты и печать составов до этапа 6 звал сам `buildWorld` — мир
        // импортировал файл приборов. Зовёт их оркестровка, сразу после мира; порядок строк лога прежний (проверен оракулом)
        if (!greeted) {
            greeted = true
            probe(ctx.flags, ctx.myCreeps, ctx.enemyCreeps, ctx.home, ctx.enemyHome)
        }
        // дамп карты — четырьмя частями по 25 строк на тиках 3–6 (см. logMap)
        if (DEBUG_MAP && mapMarks == null) captureMapMarks(ctx.flags, ctx.myCreeps, ctx.enemyCreeps)
        if (DEBUG_MAP && getTicks() in 3..6) logMap((getTicks() - 3) * 25)
        logBodies(ctx.myCreeps, ctx.enemyCreeps)
        readSignals(ctx)
        runRunners(ctx)
        cpuMark("runners")
        runArmy(ctx)

        // боевые интенты уходят в API до разрешения движения: стенд разрешает конфликты за клетку в порядке первого
        // интента крипа, и порядок «удар, затем ход» — часть тождества с эталоном v235 (движку порядок безразличен)
        TrafficManager.markOrdered(commandOf.keys)
        val moves = TrafficManager.resolve(ctx.active.filter { canMove(it) }, ctx.myCreeps + ctx.enemyCreeps)
        Arbiter.audit()
        Executor.run(moves)
        cpuMark("resolve")
        cpuSummary()
        // хвост тика после перебора командира (v262): наибольший за матч — запас бюджета перебора
        if (cmdSearched) { cmdTailMax = maxOf(cmdTailMax, cpuMs() - cmdEndMs); cmdSearched = false }
        val rem = RememberTick(ctx)
        printTick(ctx, rem)
    }

    /** Флаги, на которые наши крипы уже шагают в ЭТОТ тик (см. planCapture): два захвата одним тиком — D5 армией и H4
     *  скаутом — каждый в отдельности проходил порог паритета, вместе дали 0.93 и разгром 12:0 (стенд m9 hunter, t=98). */
    internal val plannedCaptures = HashSet<String>()
    internal var stalledNow = false                        // бесплодная охота (см. STALL_TICKS) — снимает и запрет захвата в контакте
    internal var hisTouchShare = 1.0
    internal var touchShare = 1.0                          // она же за окно; до заполнения окна — единица, чтобы вход в бой не менялся
    internal val missionOf = HashMap<String, Char>()      // крип → буква задания его отряда этим тиком (v252, из Strategist.snapshot)
    internal val pressChase = HashMap<String, ArrayDeque<ChaseSample>>()  // погоня за целью прижима по тикам (см. PRESS_GIVEUP)
    internal val pressGiveUp = HashMap<String, Int>()      // цель прижима, от которой отказались, → тик, до которого




    private fun runArmy(ctx: Ctx) {
        if (ctx.army.isEmpty()) return
        // хранители флагов — решение стратега; до v446 его звала первой строкой мера мира (ребро World → Strategist). До вызова в
        // armyMeasures не исполнялось ничего, кроме трёх чтений полей ctx, — порядок прежний
        updateKeepers(ctx, ctx.army)
        Memory.prevPosture = posture      // меры мира читают решение ПРОШЛОГО тика — явно, а не полем, которое стратег перепишет ниже
        val meas = ArmyMeasures(ctx, this)
        val strat = armyStrategy(ctx, meas)
        val targ = ArmyTargets(ctx, meas, strat, this)
        val stanceOut = ArmyStance(ctx, meas, strat, targ, this)
        armyBlock(ctx, meas, strat, targ, stanceOut)
        cpuMark("block")
        rotateByFocus(ctx.army, meas.combatEnemies)
        // ...его система — только против того, кто охотится за ранеными (v294, см. huntsWounded)
        stepOutWounded(ctx.army, targ.reachCells, strat.enemyRetreating || !huntsWounded)
        armyCommand(ctx, meas, strat, targ, stanceOut)
        cpuMark("command")
        orderAudit(ctx, meas, targ)
        healerWall(ctx, meas)
        cpuMark("plan")
        // ПОКРИПНАЯ ЛЕСТНИЦА — В ТАКТИКЕ (v251, этап 9): тело цикла перенесено в Tactician.kt дословно, величины тика —
        // в ArmyTick; порядок крипов тот же, проход один (см. заголовок Tactician.kt)
        val tick = ArmyTick(meas, strat, targ, stanceOut, this)
        for (creep in ctx.army) creepTurn(creep, ctx, tick)

        armyFireAndHeal(ctx, meas, targ)
    }

    /** Урон, уже расписанный по цели в этом тике (v140, отказ от перебоя): чистится вместе с shotsAt. */
    internal val damageBooked = HashMap<String, Double>()


    internal var cmdMode = CmdMode.MARCH
    /** Событие этого тика для стратега (v242): флаг сменил владельца — считается при сборе флагов, до решения. */
    internal var flagFlipNow = false
    internal val fireOf = HashMap<String, String>() // крип → цель, назначенная командиром (v161)
    internal val orderWas = HashMap<String, Pair<Int, Int>>()   // где крип стоял в момент приказа (v170)
    internal val orderFatigue = HashMap<String, Int>()
    internal val orderDist = HashMap<String, Int>()
    internal val healOf = HashMap<String, String>() // лекарь → пациент, назначенный командиром (v162)
    /** Идёт ли бой ПРЯМО СЕЙЧАС — считается до отряда и до командирской гонки, чтобы обе читали этот тик. */
    internal var fightOnNow = false
    internal var groupSafe = false                         // v298: он не бьёт наших, стоящих группой (см. GROUP_SAFE_DMG)
    internal var coreContactNow = false                    // v315: контакт массы армии (а не всякий выстрел за окно)
    internal var groupDmgWindow = 0
    internal val commandOf = HashMap<String, Position>()   // крип → клетка, назначенная командиром (v137)
    internal val shotsAt = HashMap<String, Int>()     // выстрелы по цели за тик (см. conc в строке t=)
    /** Размен идёт прямо сейчас (v221, см. exchangeLive) — для гейта захвата, который зовётся из `runRunners`
     *  раньше `runArmy` и потому читает окно прошлого тика. */
    internal var exchangeLiveNow = false
    /** Удары мили по цели за тик (v221) — как `shotsAt`, но из `strike`; чистится там же. */
    internal val strikesAt = HashMap<String, Int>()


}

// ==================== приборы стадии: счётчик живёт у того, кто считает (v447, план архитектуры, 4.7 и этап 6) ====================
// Объявления перенесены из Instruments.kt дословно; Instruments их читает и печатает, текст строк прежний.

internal val abortTicks = Gauges.counter("abort")

internal val abortEntries = Gauges.counter("abort", 1)
