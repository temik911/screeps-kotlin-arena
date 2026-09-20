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






    /** Приборы наблюдения 5: сколько раз скаут попадал в пул огня, сколько тиков он был в нашей дальности. */







































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
        for (owner in listOf<Any>(this, InfluenceMap, DistanceMap, TrafficManager, Executor, Forecast, Memory, BodyMemo, Gauges, Orders, FireBook, Wall, StrategistState, WorldState, TacticianState, MapDump, Signals, Prev)) {
            val r = AbortRepair.repairFields(owner)
            maps += r.maps; sets += r.sets; entries += r.entries
        }
        abortEntries.n += entries
        println("abort t=${getTicks()}: the previous tick did not finish — $maps maps and $sets sets rebuilt in place ($entries entries)")
    }

    private fun tickBody() {
        val ctx = Ctx()
        // ПРИБОРЫ ПЕРВЫХ ТИКОВ (v447, этап 6): зонд, дамп карты и печать составов до этапа 6 звал сам `buildWorld` — мир
        // импортировал файл приборов. Зовёт их оркестровка, сразу после мира; порядок строк лога прежний (проверен оракулом)
        if (!greeted) {
            greeted = true
            probe(ctx.flags, ctx.myCreeps, ctx.enemyCreeps, ctx.home, ctx.enemyHome)
        }
        // дамп карты — четырьмя частями по 25 строк на тиках 3–6 (см. logMap)
        if (DEBUG_MAP && MapDump.mapMarks == null) captureMapMarks(ctx.flags, ctx.myCreeps, ctx.enemyCreeps)
        if (DEBUG_MAP && getTicks() in 3..6) logMap((getTicks() - 3) * 25)
        logBodies(ctx.myCreeps, ctx.enemyCreeps)
        readSignals(ctx)
        runRunners(ctx)
        cpuMark("runners")
        val army = runArmy(ctx)

        // боевые интенты уходят в API до разрешения движения: стенд разрешает конфликты за клетку в порядке первого
        // интента крипа, и порядок «удар, затем ход» — часть тождества с эталоном v235 (движку порядок безразличен)
        TrafficManager.markOrdered(Orders.commandOf.keys)
        val moves = TrafficManager.resolve(ctx.active.filter { canMove(it) }, ctx.myCreeps + ctx.enemyCreeps)
        Arbiter.audit()
        Executor.run(moves)
        cpuMark("resolve")
        cpuSummary()
        // хвост тика после перебора командира (v262): наибольший за матч — запас бюджета перебора
        if (cmdSearched) { cmdTailMax = maxOf(cmdTailMax, cpuMs() - cmdEndMs); cmdSearched = false }
        val rem = RememberTick(ctx, army?.meas, army?.strat, army?.stanceOut)
        printTick(ctx, rem)
    }





    /** Носители стадий армии этого тика — для `RememberTick` (см. Prev); `null` в тике без армии. */
    private fun runArmy(ctx: Ctx): ArmyTick? {
        if (ctx.army.isEmpty()) return null
        // хранители флагов — решение стратега; до v446 его звала первой строкой мера мира (ребро World → Strategist). До вызова в
        // armyMeasures не исполнялось ничего, кроме трёх чтений полей ctx, — порядок прежний
        updateKeepers(ctx, ctx.army)
        Memory.prevPosture = posture      // меры мира читают решение ПРОШЛОГО тика — явно, а не полем, которое стратег перепишет ниже
        val meas = ArmyMeasures(ctx)
        val strat = ArmyStrategy(ctx, meas)
        val targ = ArmyTargets(ctx, meas, strat)
        val stanceOut = ArmyStance(ctx, meas, strat, targ)
        armyBlock(ctx, meas, strat, targ, stanceOut)
        cpuMark("block")
        rotateByFocus(ctx.army, meas.forces.combatEnemies)
        // ...его система — только против того, кто охотится за ранеными (v294, см. huntsWounded)
        stepOutWounded(ctx.army, targ.zones.reachCells, strat.dec.enemyRetreating || !TacticianState.huntsWounded)
        armyCommand(ctx, meas, strat, targ, stanceOut)
        cpuMark("command")
        orderAudit(ctx, meas, strat, targ)
        healerWall(ctx, meas)
        cpuMark("plan")
        // ПОКРИПНАЯ ЛЕСТНИЦА — В ТАКТИКЕ (v251, этап 9): тело цикла перенесено в Tactician.kt дословно, величины тика —
        // в ArmyTick; порядок крипов тот же, проход один (см. заголовок Tactician.kt)
        val tick = ArmyTick(meas, strat, targ, stanceOut)
        for (creep in ctx.army) creepTurn(creep, ctx, tick)

        armyFireAndHeal(ctx, meas, targ)
        return tick
    }





}

// ==================== приборы стадии: счётчик живёт у того, кто считает (v447, план архитектуры, 4.7 и этап 6) ====================
// Объявления перенесены из Instruments.kt дословно; Instruments их читает и печатает, текст строк прежний.

internal val abortTicks = Gauges.counter("abort")

internal val abortEntries = Gauges.counter("abort", 1)
