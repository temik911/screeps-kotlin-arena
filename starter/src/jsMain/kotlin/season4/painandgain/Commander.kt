package season4.painandgain

import screeps.api.Creep
import screeps.api.ATTACK
import screeps.api.ATTACK_POWER
import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.CARRY_CAPACITY
import screeps.api.CostMatrix
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
import kotlin.reflect.*

// РАЗДАЧА КОМАНДИРА — СТАДИЯ УРОВНЯ 4 (v446, план архитектуры, этап 5). `armyCommand` жил в файле стратега (уровень 3), а зовёт
// раздачу клеток боя `commandFight` (Fight.kt), марш `commandMarch` и изготовку `Formation.brace` (Formation.kt) — уровень 4: два
// ребра вверх, которых раздел 2.5 плана не называл. Перебор замыслов с прогоном каждого — работа над клетками боя, а не решение о
// состоянии армии, поэтому стадия переехала к тем, кого зовёт; вниз она по-прежнему читает стратега (`commandRace`, `commandGoal`)
// и прогноз (`Forecast.simulate`). Текст перенесён дословно.

internal class ArmyCommandOut(
)

/** РАЗДАЧА КОМАНДИРА (v256, этап 10; сегмент runArmy): режим FIGHT — перебор замыслов commandFight с прогнозом Forecast.simulate, изготовка (Formation.brace) и гонка (commandRace, commandGoal, commandMarch), погоня (assignChase), постановка стратега для прибора disp= и букв заданий missionOf. Перенесено дословно. */
internal fun PainAndGain.armyCommand(ctx: Ctx, meas: ArmyMeasuresOut, strat: ArmyStrategyOut, targ: ArmyTargetsOut, stanceOut: ArmyStance): ArmyCommandOut {
    val ourFlagCells = ctx.ourFlags.mapTo(HashSet()) { it.pos.key }
    val commanderNow =  cmdMode == CmdMode.FIGHT
    // ...а в гонке командир раздаёт задания по флагам (v160, см. commandRace): это второй его режим, и с ним
    // он перестаёт молчать там, где раньше просто уступал место старым правилам
    // ...и ПОХОД — тот же вопрос, что гонка, только врага рядом нет: командир так же раздаёт флаги группами,
    // а ядро держит вместе. Прежняя попытка вести поход (v151) провалилась 0-8 потому, что вела его РАЗДАЧА
    // КЛЕТОК ПРОТИВ СТРОЯ — расстановка, которой в походе нечего расставлять; здесь у похода свой режим (v160)
    val raceCommandNow = 
        (cmdMode == CmdMode.RACE || (cmdMode == CmdMode.MARCH))
    // сколько тиков командир действительно правил армией, и почему не правил: без этого спор «виноват командир
    // или базовая логика» решается догадкой, а в разгроме 6aa075ce постура была HOLD, то есть он молчал
    if (commanderNow) cmdTicks++ else cmdBlocked = strat.cmdWhyNow
    // ПОГОНЯ НАЗНАЧАЕТСЯ ДО ПЕРЕБОРА (v211): раздача прогоняется пять раз, по разу на замысел, и отряд обязан
    // быть один и тот же во всех пяти — иначе прогноз оценивает пять разных армий.
    // ...И НЕ ЗАВИСИТ ОТ ТОГО, ПРАВИТ ЛИ КОМАНДИР (v212). Первая редакция стояла под `commanderNow`, и первый же
    // живой матч это закрыл: `cmd=8/1900` — командир правил восемь тиков из тысячи девятисот, погоня не
    // выдалась ни разу (`chase=0/0` при двух остовах). Доля командира гуляет по матчам от 8 тиков до двух
    // третей, поэтому назначение стоит выше него: приказ один, а исполняют его оба пути движения — командирская
    // раздача, когда он правит, и обычная цепочка целей (ветка `chase`), когда молчит
    assignChase(meas.mobileArmy, meas.enemyCreeps, meas.armedEnemies)
    val disposition = Strategist.snapshot(ctx.army, ctx.runners, Memory.runnerFlag, Memory.detachedIds, Memory.cmdDetach,
        Memory.keeperIds, Memory.chaseOf, posture, cmdMode, objectiveFlagId, meas.armedEnemies)
    dispNow = Strategist.summary(disposition)
    missionOf.clear()
    for (sq in disposition.squads) for (id in sq.members) missionOf[id] = sq.mission.tag
    if (commanderNow) {
        // СТРАХОВКА ПО ВРЕМЕНИ И ДЛЯ КОМАНДИРА (v158): она стояла на бегунах и на выборе цели, а на самой
        // дорогой части — переборе замыслов с прогоном каждого — не стояла. В рейтинговой серии 09.09.2026 это
        // дважды кончилось `Script execution timed out` (матчи 6aa085d7 и 6aa086d3): тик пропал целиком, армия
        // не сходила. При нехватке времени командир раздаёт клетки одним замыслом, без перебора и прогонов
        val cpuTight =  getTicks() > 1 && cpuMs() > CPU_GUARD_MS
        if (cpuTight && DEBUG_LOG) println("cpu t=${getTicks()} guard: the commander skips the search (${(cpuMs() * 10).toInt() / 10.0}ms)")
        // ...и при разрыве контакта (v227, см. USE_ZERO_LEAD_BREAK) замысел не выбирается прогоном — он задан: KITE
        if (cpuTight) publishDeal(commandFight(meas.commandArmy, meas.combatEnemies, meas.armedEnemies, commandOf, Intent.PRESS, ourFlagCells = ourFlagCells), tried = 1)
        else {
            // командир предлагает несколько замыслов, симуляция выбирает лучший по мощи через Forecast.SIM_TICKS (v138)
            var bestScore = -Double.MAX_VALUE
            var bestPlan: Map<String, Position>? = null
            var bestIntent = Intent.PRESS
            // выбор цели фокуса симуляцией ОТВЕРГНУТ (v138): перебор пар (замысел, цель) дал 1-7 и 2-6 против
            // 3-13, а проведённая до стрельбы цель уронила гейт до 129/131 и дала m33:kite 0:21 135 — назначенная
            // цель ломает липкость фокуса, которая и держит наш огонь на одном. Перебираются только замыслы
            // ПОРТФЕЛЬНЫЙ ЖАДНЫЙ ПОИСК (v139, Churchill & Buro 2013): сперва лучший ЕДИНЫЙ замысел, затем
            // восхождение — крип за крипом пробуем все замыслы и оставляем тот, чей прогон лучше. Так армия
            // смешивает поведение, чего единая политика не умеет
            // ПЕРЕБОР ПОД БЮДЖЕТОМ ТИКА (v262, этап 10; план, раздел 1: «инкумбент оценивается всегда; кандидаты — пока
            // хватает CPU»). Страховка cpuTight выше смотрит только на начало перебора, а сам перебор пяти замыслов цены не
            // проверял: живьём на тике первого контакта он начинался около 35 мс и стоил 46 — тик кончался тайм-аутом
            // (руки v250 и v253, серия v245). Теперь первый замысел оценивается всегда, а следующий — только если тик с ценой
            // прошлой пробы не выйдет за CPU_GUARD_MS — тот же порог «дальше необязательная работа не делается», что у
            // страховок бегунов и командира. Первая редакция (v262) держала запас «лимит движка минус наибольший хвост тика
            // после командира» и тайм-аут не сняла: хвост мерился только на тиках боя, и на ПЕРВОМ тике боя запаса не было
            // вовсе (рука против MetalicaX#15, t=47: перебор 51,9 мс без обрезки, хвост там ~16 мс против 5–9 на марше).
            // Потеря целого тика на входе в бой хуже, чем два оценённых замысла вместо пяти. Хвост (cmdTailMax) остался прибором
            var lastCost = 0.0
            srchTicks++
            // ЗАПИСЬ ВЫБРАННОЙ РАЗДАЧИ (v449, пункт В): каждая проба несёт своё поле нужды и свои пробы; в мир и в приборы
            // уходит запись победителя, а не последнего оценённого (см. DealRecord)
            var bestRec: DealRecord? = null
            var lastIntent: Intent? = null
            var tried = 0
            for (intent in Intent.values()) {
                if (bestPlan != null && cpuMs() + lastCost > CPU_GUARD_MS) { srchCut++; break }
                val t0 = cpuMs()
                val trial = HashMap<String, Position>()
                val rec = commandFight(meas.commandArmy, meas.combatEnemies, meas.armedEnemies, trial, intent, ourFlagCells = ourFlagCells)
                tried++; lastIntent = intent
                // прогноз считает ТОТ бой, который случится: наши в симуляции бьют ту же липкую цель фокуса,
                // что и бот на самом деле, а не «самого раненого» (v140) — прежде прогноз и поведение расходились
                val sc = Forecast.simulate(meas.mobileArmy, meas.armedEnemies, trial, Forecast.SIM_TICKS, targ.focusTarget, intent)   // состав без хранителей: «тот же, что у плана» (v242) отвергнут A/B вместе с применением постуры один раз
                if (sc > bestScore) { bestScore = sc; bestPlan = trial; bestIntent = intent; bestRec = rec }
                lastCost = cpuMs() - t0
            }
            publishDeal(bestRec, tried)
            if (bestPlan != null && bestIntent != lastIntent) srchDiff.n++
            cmdEndMs = cpuMs(); cmdSearched = true
            // ГИСТОГРАММА ЗАМЫСЛА (этап 8): перебор из пяти стоит пяти раздач за тик, и окупается ли он —
            // вопрос к числу, а не к мнению. Счётчик стоит ЗДЕСЬ, где замысел действительно выбирается:
            // первая редакция поставила его внутрь блока USE_PORTFOLIO_SEARCH, который выключен, — то есть
            // я едва не отправил в бой мёртвый прибор, ровно тот отказ, о котором вся эта пачка
            if (bestPlan != null) Memory.intentHist[bestIntent.name] = (Memory.intentHist[bestIntent.name] ?: 0) + 1
            // ...восхождение идёт по ГРУППАМ РОЛЕЙ, а не по отдельным крипам (v139): в литературе это называют
            // кластеризацией юнитов, и при нашей грубой оценке она обязательна — назначая замысел каждому крипу
            // порознь, поиск рвал строй (гейт 128/131, m30:kite 0:21 899 при CPU всего 3,8 мс, то есть дело не
            // в цене, а в том, что смешанные наборы получают завышенную оценку)
            commandOf.clear()
            bestPlan?.let { commandOf.putAll(it) }
            // ОТХОД ПО ПРОГНОЗУ (v165, оператор: переносить логику в командира). Решение «бежать» принимала постура,
            // а у командира есть прибор, которого у неё нет, — симуляция размена. Если ЛУЧШИЙ из его замыслов
            // кончается тем, что уцелевшая мощь врага перевешивает нашу, драться незачем: он объявляет отход сам
            // ОШИБКА ПРОГНОЗА (v166): командир обещает разность мощи через Forecast.SIM_TICKS тиков — здесь она запоминается,
            // а на Forecast.SIM_TICKS-м тике сверяется с тем, что вышло на самом деле. Прибор нужен потому, что оценка НИ
            // РАЗУ не уходит в минус (см. USE_COMMAND_RETREAT): пока неизвестно, на сколько она врёт, командир
            // выбирает замысел числом, которому нельзя верить
            // ...и факт меряется ТОЙ ЖЕ формулой, что прогноз: сравнивать оценку симуляции с ланчестеровской
            // мощью — сравнивать разные величины, и первая редакция прибора именно этим и занималась
            val nowDiff = Forecast.simulate(meas.mobileArmy, meas.armedEnemies, emptyMap(), 0, targ.focusTarget, null)
            Forecast.simPending[getTicks() + Forecast.SIM_TICKS] = bestScore to nowDiff
            Forecast.simPending.remove(getTicks())?.let { (predicted, was) ->
                val actual = nowDiff - was          // как разность изменилась НА САМОМ ДЕЛЕ за Forecast.SIM_TICKS
                val expected = predicted - was      // как её обещал изменить прогноз
                Forecast.simErrSum += abs(actual - expected); Forecast.simErrN++
                if (expected > 0 && actual < 0) Forecast.simErrWrongSign++
            }
            Forecast.simPending.keys.filter { it < getTicks() }.forEach { Forecast.simPending.remove(it) }
            if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) println("sim t=${getTicks()}: intent=$bestIntent score=${bestScore.toInt()} obey=$orderAuditOk/$orderAuditN closer=$orderAuditCloser same=$orderAuditSame far=$orderFar clash=$orderClash fled=$orderFled branch=$orderBranch lost=stay$lostStay/stuck$lostStuck/fat$lostFatigue/else$lostElsewhere err=${if (Forecast.simErrN > 0) (Forecast.simErrSum / Forecast.simErrN).toInt() else 0} wrongSign=${Forecast.simErrWrongSign}/${Forecast.simErrN}")
        }
    // ⚠️ Здесь стояла ветка «в бою, пока по нам не стреляют, командир тоже отпускает за флагами». Она
    // НЕДОСТИЖИМА ДВАЖДЫ: стоит в `else` от `if (commanderNow)`, то есть `commanderNow` здесь ложно по
    // построению, — и вдобавок сам режим боя требует `underTheirFire`, поэтому `commanderNow && !underTheirFire`
    // противоречиво и само по себе. Комментарий при ней утверждал обратное и ссылался на замер, которого она
    // никогда не проходила. Снята в v215; дифф отчёта пуст побайтово. Оживлять её было бы и незачем: с v215
    // отпускать в бою запрещено вовсе (см. USE_NO_SPLIT_IN_FIGHT)
    // ...и ТОЛЬКО когда он ИДЁТ на нас: изготовка при всяком враге в десяти клетках вставала поперёк гонки за
    // флагами — армия строилась вместо захвата, и гейт рухнул до 122 из 135 (roost трижды)
    } else if (!meas.contact && !pushing &&
            (stanceOut.armiesClosing || (stanceOut.enemyApproaching && meas.enemyMassedNow)) &&
            meas.armedEnemies.any { e -> meas.commandArmy.any { getRange(e, it) <= BRACE_RANGE } }) {
        // ИЗГОТОВКА (v179): враг идёт, контакта ещё нет — строим фронт, а не ждём его растянутыми
        // ...и строится ЯДРО, а отпущенные за флагами своего задания не бросают (v183) — ровно как в марше ядра.
        // Изготовка отзывала в строй и захватчиков, и гонка очков от этого проседала: сценарий camp 15 983:16 266
        cmdTicks++
        // ...и гонка идёт ПАРАЛЛЕЛЬНО строю: изготовка стояла В ЦЕПОЧКЕ ПЕРЕД гонкой, поэтому, пока враг
        // подходил, командир не отпускал за флагами вовсе — ни одного захватчика не назначалось, и сценарий
        // camp кончался 15 983:16 266. Сперва раздаются задания на захват, затем ядро из оставшихся строится
        commandRace(ctx, meas.commandArmy, meas.armedEnemies, ctx.flags, commandOf)
        val runners = HashMap(commandOf)
        Formation.brace(unitsNow, meas.commandArmy.filter { it.id !in Memory.cmdDetach }, meas.armedEnemies, commandOf)
        commandOf.putAll(runners)
    } else if (raceCommandNow) {
        cmdTicks++
        commandRace(ctx, meas.commandArmy, meas.armedEnemies, ctx.flags, commandOf)
        // прибор второго тика (v222): фаза plan стоит 20–28 мс на тиках 1–2 и 0,7 мс на третьем — метки внутри неё
        // называют, что именно (строка cpu печатается на первых трёх тиках и на медленных)
        cpuMark("p.race")
        // ...и ядро, оставшееся после раздачи заданий, идёт СТРОЕМ к цели (v163): прежде командир раздавал только
        // задания на захват, а ядро шло врозь по прежним веткам и приходило к бою растянутым
        // ...и только пока враг ДАЛЕКО: рядом с ним решают тактические ветки — экран, добыча, перехват, — а строй,
        // ведущий ядро на флаг мимо них, ронял screen и scatter (гейт 131 из 135)
        // ЗАГОН ВМЕСТО МАРША (v331): ядро, оставшееся после раздачи флагов, ловит его одиночку двумя группами
        val restCore = notCmdDetached(meas.mobileArmy)
        val hunting = commandHunt(ctx, restCore, meas.armedEnemies, commandOf)
        if (!hunting && meas.armedEnemies.none { e -> meas.mobileArmy.any { getRange(e, it) <= MARCH_SAFE } }) {
            // цель марша — своя (v164): раньше здесь стояла objectiveFlagId, посчитанная до командира
            val goal = commandGoal(ctx, meas.mobileArmy, meas.armedEnemies)
            cpuMark("p.goal")
            val steps = HashMap<String, Position>()
            commandMarch(ctx, notCmdDetached(meas.mobileArmy), goal, steps)
            commandOf.putAll(steps)
            cpuMark("p.march")
        }
    }
        // ...и задания на захват снимаются вместе с режимом: без этого крип, отпущенный командиром за флагом,
        // оставался захватчиком НАВСЕГДА — армия таяла тик за тиком, и сценарий kite давал 0 очков (v160)
        else { commandOf.clear(); Memory.cmdDetach.clear() }
    // ЛЕКАРИ — ПОД ПРИКАЗОМ ВО ВСЯКОМ КОНТАКТЕ (v436, см. USE_COMMANDER_HEALERS_IN_CONTACT). Режим боя против Coldkimchi
    // включён в 25–40 % тиков контакта (остальное — outmatched, retreat, posture, nofire), и в молчании командира клетку
    // лекаря выбирают ветки тактика: healMate ведёт к самому раненому в четырёх (уже отведённому из огня; совпадает с
    // теряющим хиты в 38,7 %), и цена доставки v435 действовала на ≤ 20 % лекаре-тиков (Opus, 32 реплея). Здесь командир
    // раздаёт ОДНИХ лекарей той же ценой клетки; бойцов не трогает — «командир на любой контакт» ронял roost и scatter
    if (USE_COMMANDER_HEALERS_IN_CONTACT && !commanderNow && meas.contact && meas.armedEnemies.isNotEmpty()) {
        val only = HashMap<String, Position>()
        publishDeal(commandFight(meas.commandArmy, meas.combatEnemies, meas.armedEnemies, only, Intent.HOLD, ourFlagCells = ourFlagCells, healersOnly = true), tried = 1)
        var given = 0
        for (h in meas.commandArmy) if (healerOnly(h) && h.id !in Memory.cmdDetach) only[h.id]?.let { commandOf[h.id] = it; given++ }
        cmdHealTicks.n++; cmdHealGiven.n += given
    }

        // ИСПОЛНЕНИЕ ПРИКАЗА (v167): прогноз считает, что крип встанет туда, куда назначено, а между приказом и
    // клеткой стоят трафик, свопы и фатиг. Здесь считается доля тех, кто на следующем тике оказался ровно
    // на своей клетке: если она мала, ошибка прогноза объясняется не моделью, а неисполнением
    // КОЛЛИЗИИ ПРИКАЗОВ (v172, оператор: «не должно быть такого, что по приказам командира в одну клетку
    // собрались двое»). Внутри одной раздачи это исключено множеством taken, но приказы приходят из РАЗНЫХ
    // мест — бой, гонка, марш ядра, — и вот там пересечение возможно; здесь оно считается
    return ArmyCommandOut(
    )
}

/** ПУБЛИКАЦИЯ ВЫБРАННОЙ РАЗДАЧИ (v449, пункт В оператора): поле нужды выбранной раздачи уходит в мир (`InfluenceMap.published`),
 *  её пробы вливаются в приборы (DealRecord.mergeInto); пробы всех раздач тика считаются отдельно — прибор `deals=выбрано/сыграно`.
 *  Зовётся из трёх мест, где командир раздаёт клетки: перебор замыслов (победитель), раздача при нехватке CPU, раздача одних
 *  лекарей. */
internal fun publishDeal(rec: DealRecord?, tried: Int) {
    dealsTried.n += tried
    if (rec == null) return
    dealsChosen.n++
    InfluenceMap.published = rec.need
    mergeDeal(rec)
}

// ==================== приборы стадии: счётчик живёт у того, кто считает (v446, план архитектуры, 4.7) ====================
// Перенесены из Instruments.kt дословно; Instruments их читает и печатает. `cmdSearched` сбрасывает оркестровка в конце тика.

/** Раздач, ушедших в мир (по одной на тик раздачи) / раздач сыграно, включая пробы замыслов (v449, прибор `deals=`). */
internal val dealsChosen = Gauges.counter("deals")
internal val dealsTried = Gauges.counter("deals", 1)
/** Тиков перебора, где выбранный замысел — не последний оценённый (v449, прибор `srchd=`): столько раз до v449 после командира
 *  в мире оставалось поле нужды чужого замысла (97 % выборок гейта и 89 % живых по строкам `sim t=` v447). */
internal val srchDiff = Gauges.counter("srchd")

/** Перебор замыслов под бюджетом (v262, см. Strategist.armyCommand): тиков с перебором, из них обрезанных, наибольший
 *  хвост тика после командира в мс — прибор srch= и запас бюджета. */
internal var srchTicks = 0
internal var srchCut = 0
internal var cmdTailMax = 0.0
internal var cmdEndMs = 0.0
internal var cmdSearched = false

/** Постановка этого тика, как её задают старые решатели (v241, см. Strategist.snapshot): прибор `disp=`. */
internal var dispNow = "-"

internal var cmdTicks = 0                       // тиков, когда командир правил армией (диагностика, v143)

// ==================== приборы стадии, бывшие членами object PainAndGain (v455, второй шаг архитектуры, этап 2) ====================

internal val cmdHealTicks = Gauges.counter("cmdheal", 1)   // тики «только лекари» и выданных приказов (v436, прибор cmdheal=)

internal val cmdHealGiven = Gauges.counter("cmdheal")

/** Почему командир не правил — в ПОСЛЕДНИЙ тик, когда не правил. ⚠️ В строке `t=` (поле `cmd=…:причина`)
 *  это значение УСТАРЕВШЕЕ: оно пишется только на тиках без командира, поэтому `cmd=0/200:outmatched
 *  mode=FIGHT` значит «сейчас бой, а в последний тик без командира причиной был outmatched». Разбор серии
 *  v220 прочёл его как причину текущего тика и приписал разгромам «отход»; честная картина по тикам —
 *  гистограмма `cmdwhy` (v221). */
internal var cmdBlocked = "-"

// счётчики аудита приказов: считает `orderAudit` (пока в Instruments.kt; этап 4 второго шага переносит его сюда — он снимает приказы, то
// есть стадия, а не прибор), читает строка `sim` командира — объявление у читателя-стадии: стадия не импортирует Instruments

internal val orderAuditOk = Gauges.counter("obey")

internal val orderAuditN = Gauges.counter("obey", 1)

internal var orderAuditCloser = 0

internal var orderAuditSame = 0

internal var orderFar = 0

internal val orderClash = Gauges.counter("clash")

internal val lostStay = Gauges.counter("lost", label = "stay")        // приказ был «стой», а крип ушёл

internal val lostStuck = Gauges.counter("lost", 1, label = "stuck")       // крип остался на месте, хотя приказ был другой

internal val lostFatigue = Gauges.counter("lost", 3, label = "fat")     // не мог двигаться от усталости

internal val lostElsewhere = Gauges.counter("lost", 4, label = "else")   // двинулся, но в другую клетку
