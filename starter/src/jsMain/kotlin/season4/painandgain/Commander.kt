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

/** РАЗДАЧА КОМАНДИРА (v256, этап 10; сегмент runArmy): режим FIGHT — перебор замыслов commandFight с прогнозом Forecast.simulate, изготовка (Formation.brace) и гонка (commandRace, commandGoal, commandMarch), погоня (assignChase), постановка стратега для прибора disp= и букв заданий missionOf. Перенесено дословно. */
internal fun armyCommand(ctx: Ctx, meas: ArmyMeasures, strat: ArmyStrategy, targ: ArmyTargets, stanceOut: ArmyStance) {
    val ourFlagCells = ctx.ourFlags.mapTo(HashSet()) { it.pos.key }
    // КОМАНДИР ВНЕ БЛОКА СТРОЯ (v160): весь его расчёт стоял внутри `if (blockOn)`, а blockOn требует постуры
    // ANNIHILATE, врагов в поле и отсутствия добивания — то есть командир молчал везде, кроме рубки, что бы ни
    // говорил его собственный режим: замер показал mode=FIGHT в 150 строках лога при cmdTicks=26. Теперь он
    // считается всегда и сам решает по режиму
    val commanderNow =  strat.dec.decision.cmdMode == CmdMode.FIGHT
    // ...а в гонке командир раздаёт задания по флагам (v160, см. commandRace): это второй его режим, и с ним
    // он перестаёт молчать там, где раньше просто уступал место старым правилам
    // ...и ПОХОД — тот же вопрос, что гонка, только врага рядом нет: командир так же раздаёт флаги группами,
    // а ядро держит вместе. Прежняя попытка вести поход (v151) провалилась 0-8 потому, что вела его РАЗДАЧА
    // КЛЕТОК ПРОТИВ СТРОЯ — расстановка, которой в походе нечего расставлять; здесь у похода свой режим (v160)
    val raceCommandNow = 
        (strat.dec.decision.cmdMode == CmdMode.RACE || (strat.dec.decision.cmdMode == CmdMode.MARCH))
    // сколько тиков командир действительно правил армией, и почему не правил: без этого спор «виноват командир
    // или базовая логика» решается догадкой, а в разгроме 6aa075ce постура была HOLD, то есть он молчал
    if (commanderNow) cmdTicks++ else cmdBlocked = strat.dec.cmdWhyNow
    // прибор `cmdinert=` (v465, дефект 4): в режиме боя без `fightOnNow` набор отряжённых командиром не трогает никто — ни отзыв
    // боем (он под fightOnNow), ни гонка (её не зовут): отряжённые остаются бегунами по инерции
    if (commanderNow && !meas.fight.fightOnNow && Squads.cmdDetach.isNotEmpty()) cmdInertFight.n++
    // ПОГОНЯ НАЗНАЧАЕТСЯ ДО ПЕРЕБОРА (v211): раздача прогоняется пять раз, по разу на замысел, и отряд обязан
    // быть один и тот же во всех пяти — иначе прогноз оценивает пять разных армий.
    // ...И НЕ ЗАВИСИТ ОТ ТОГО, ПРАВИТ ЛИ КОМАНДИР (v212). Первая редакция стояла под `commanderNow`, и первый же
    // живой матч это закрыл: `cmd=8/1900` — командир правил восемь тиков из тысячи девятисот, погоня не
    // выдалась ни разу (`chase=0/0` при двух остовах). Доля командира гуляет по матчам от 8 тиков до двух
    // третей, поэтому назначение стоит выше него: приказ один, а исполняют его оба пути движения — командирская
    // раздача, когда он правит, и обычная цепочка целей (ветка `chase`), когда молчит
    assignChase(meas.chase.mobileArmy, meas.forces.enemyCreeps, meas.forces.armedEnemies)
    val disposition = Strategist.snapshot(ctx.army, ctx.runners, Squads.runnerFlag, Squads.detachedIds, Squads.cmdDetach,
        Squads.keeperIds, Squads.chaseOf, posture, strat.dec.decision.cmdMode, objectiveFlagId, meas.forces.armedEnemies)
    dispNow = Strategist.summary(disposition)
    Orders.missionOf.clear()
    for (sq in disposition.squads) for (id in sq.members) Orders.missionOf[id] = sq.mission.tag
    // НАЧАТЫЙ РАЗМЕН ВНЕ КОНТАКТА — МАРШ НА НЕГО, А НЕ БОЕВАЯ РАЗДАЧА (v569, см. USE_MARCH_INTO_ENGAGE). Перебор
    // замыслов вне досягаемости оценивает любой шаг внутрь как «первым получить залп» и держит строй на дистанции;
    // марш ведёт ядро колонной, с лекарями, по полю к его ближайшему вооружённому. С контактом — обычная раздача
    // ...и «вне досягаемости» — это ни один наш не достаёт ни одного его вооружённого, а не `meas.fight.contact`:
    // контакт в боте — его вооружённый в RANGED_RANGE + 1, и строи стоят фронтами ровно на этой клетке, «в контакте»
    // и без единого выстрела (v570: первая редакция включала марш 21–51 тик из 77–443 тиков начатого размена)
    val outOfReach = meas.forces.armedEnemies.none { e -> meas.chase.mobileArmy.any { getRange(e, it) <= RANGED_RANGE } }
    val engageMarch = commanderNow && USE_MARCH_INTO_ENGAGE && Signals.engagingGarrison && outOfReach &&
        meas.forces.armedEnemies.isNotEmpty() && meas.chase.mobileArmy.size >= 2
    if (engageMarch) {
        val (mx, my) = Formation.median(meas.chase.mobileArmy)
        val prey = meas.forces.armedEnemies.minByOrNull { maxOf(abs(it.x - mx), abs(it.y - my)) }!!
        val steps = HashMap<String, Position>()
        commandMarch(ctx, notCmdDetached(meas.chase.mobileArmy), InfluenceMap.cell(prey.x, prey.y), steps)
        Orders.commandOf.clear()
        Orders.commandOf.putAll(steps)
        Orders.source = "engage"
        engageMarchN.n++
    } else if (commanderNow) {
        // СТРАХОВКА ПО ВРЕМЕНИ И ДЛЯ КОМАНДИРА (v158): она стояла на бегунах и на выборе цели, а на самой
        // дорогой части — переборе замыслов с прогоном каждого — не стояла. В рейтинговой серии 09.09.2026 это
        // дважды кончилось `Script execution timed out` (матчи 6aa085d7 и 6aa086d3): тик пропал целиком, армия
        // не сходила. При нехватке времени командир раздаёт клетки одним замыслом, без перебора и прогонов
        val cpuTight =  getTicks() > 1 && cpuMs() > CPU_GUARD_MS
        if (cpuTight && DEBUG_LOG) println("cpu t=${getTicks()} guard: the commander skips the search (${(cpuMs() * 10).toInt() / 10.0}ms)")
        // ...и при разрыве контакта (v227, см. USE_ZERO_LEAD_BREAK) замысел не выбирается прогоном — он задан: KITE
        Orders.source = if (cpuTight) "fight.tight" else "fight"
        if (cpuTight) publishDeal(commandFight(meas.chase.commandArmy, meas.forces.combatEnemies, meas.forces.armedEnemies, Orders.commandOf, Intent.PRESS, ourFlagCells = ourFlagCells), tried = 1)
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
            // ПЕРЕБОР ЧИСТ (v460, этап 6.7 второго шага, прибор `impure=`): проба замысла считает в СВОЮ запись, и в мир попадает только
            // выбранная (v449). Чтобы дефект не вернулся с первой же новой оценкой, до перебора снимаются записи владельцев общих
            // полей, до которых раздача дотягивается, — счётчик публикаций поля нужды и приказы, — а после сверяются: разница обязана
            // быть нулём на всём гейте (строка `impure` в regress.sh)
            val publishedBefore = InfluenceMap.publishedWrites
            val ordersBefore = HashMap(Orders.commandOf)
            // ЗАПИСЬ ВЫБРАННОЙ РАЗДАЧИ (v449, пункт В): каждая проба несёт своё поле нужды и свои пробы; в мир и в приборы
            // уходит запись победителя, а не последнего оценённого (см. DealRecord)
            var bestRec: DealRecord? = null
            var lastIntent: Intent? = null
            var tried = 0
            for (intent in Intent.values()) {
                if (bestPlan != null && cpuMs() + lastCost > CPU_GUARD_MS) { srchCut++; break }
                val t0 = cpuMs()
                val trial = HashMap<String, Position>()
                val rec = commandFight(meas.chase.commandArmy, meas.forces.combatEnemies, meas.forces.armedEnemies, trial, intent, ourFlagCells = ourFlagCells)
                tried++; lastIntent = intent
                // прогноз считает ТОТ бой, который случится: наши в симуляции бьют ту же липкую цель фокуса,
                // что и бот на самом деле, а не «самого раненого» (v140) — прежде прогноз и поведение расходились
                val sc = Forecast.simulate(meas.chase.mobileArmy, meas.forces.armedEnemies, trial, Forecast.SIM_TICKS, targ.focus.focusTarget, intent)   // состав без хранителей: «тот же, что у плана» (v242) отвергнут A/B вместе с применением постуры один раз
                if (sc > bestScore) { bestScore = sc; bestPlan = trial; bestIntent = intent; bestRec = rec }
                lastCost = cpuMs() - t0
            }
            impure.n += (InfluenceMap.publishedWrites - publishedBefore) + (if (ordersBefore != Orders.commandOf) 1 else 0)
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
            Orders.commandOf.clear()
            bestPlan?.let { Orders.commandOf.putAll(it) }
            // ОТХОД ПО ПРОГНОЗУ (v165, оператор: переносить логику в командира). Решение «бежать» принимала постура,
            // а у командира есть прибор, которого у неё нет, — симуляция размена. Если ЛУЧШИЙ из его замыслов
            // кончается тем, что уцелевшая мощь врага перевешивает нашу, драться незачем: он объявляет отход сам
            // ОШИБКА ПРОГНОЗА (v166): командир обещает разность мощи через Forecast.SIM_TICKS тиков — здесь она запоминается,
            // а на Forecast.SIM_TICKS-м тике сверяется с тем, что вышло на самом деле. Прибор нужен потому, что оценка НИ
            // РАЗУ не уходит в минус (см. USE_COMMAND_RETREAT): пока неизвестно, на сколько она врёт, командир
            // выбирает замысел числом, которому нельзя верить
            // ...и факт меряется ТОЙ ЖЕ формулой, что прогноз: сравнивать оценку симуляции с ланчестеровской
            // мощью — сравнивать разные величины, и первая редакция прибора именно этим и занималась
            val nowDiff = Forecast.simulate(meas.chase.mobileArmy, meas.forces.armedEnemies, emptyMap(), 0, targ.focus.focusTarget, null)
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
    } else if (Signals.baitPhase && !meas.fight.contact && meas.forces.armedEnemies.isNotEmpty()) {
        // ПРИМАНКА ПРОТИВ ОСЛАБЛЕННОГО (v587, см. USE_BAIT_VS_DEBUFFED): половина армии с лекарями стоит, вторая — в
        // MASS_RANGE + FIST_RADIUS клетках дальше от его кулака; при контакте бой ведёт обычный режим боя всей армией
        Orders.commandOf.clear()
        commandBait(ctx, meas.chase.mobileArmy, meas.forces.armedEnemies, Orders.commandOf)
        Orders.source = "bait"
        baitLedTicks.n++
    // ⚠️ Здесь стояла ветка «в бою, пока по нам не стреляют, командир тоже отпускает за флагами». Она
    // НЕДОСТИЖИМА ДВАЖДЫ: стоит в `else` от `if (commanderNow)`, то есть `commanderNow` здесь ложно по
    // построению, — и вдобавок сам режим боя требует `underTheirFire`, поэтому `commanderNow && !underTheirFire`
    // противоречиво и само по себе. Комментарий при ней утверждал обратное и ссылался на замер, которого она
    // никогда не проходила. Снята в v215; дифф отчёта пуст побайтово. Оживлять её было бы и незачем: с v215
    // отпускать в бою запрещено вовсе (см. USE_NO_SPLIT_IN_FIGHT)
    // ...и ТОЛЬКО когда он ИДЁТ на нас: изготовка при всяком враге в десяти клетках вставала поперёк гонки за
    // флагами — армия строилась вместо захвата, и гейт рухнул до 122 из 135 (roost трижды)
    } else if (!meas.fight.contact && !pushing &&
            (stanceOut.windows.armiesClosing || (stanceOut.windows.enemyApproaching && meas.forces.enemyMassedNow)) &&
            meas.forces.armedEnemies.any { e -> meas.chase.commandArmy.any { getRange(e, it) <= BRACE_RANGE } }) {
        // ИЗГОТОВКА (v179): враг идёт, контакта ещё нет — строим фронт, а не ждём его растянутыми
        // ...и строится ЯДРО, а отпущенные за флагами своего задания не бросают (v183) — ровно как в марше ядра.
        // Изготовка отзывала в строй и захватчиков, и гонка очков от этого проседала: сценарий camp 15 983:16 266
        cmdTicks++
        // ...и гонка идёт ПАРАЛЛЕЛЬНО строю: изготовка стояла В ЦЕПОЧКЕ ПЕРЕД гонкой, поэтому, пока враг
        // подходил, командир не отпускал за флагами вовсе — ни одного захватчика не назначалось, и сценарий
        // camp кончался 15 983:16 266. Сперва раздаются задания на захват, затем ядро из оставшихся строится
        // ...гонка раздаёт задания (Squads), а не приказы на клетку, и словарь приказов не трогает (v470, дефект 9: здесь стояла
        // копия «приказов гонки» в обход изготовки — копия пустого словаря; `Formation.brace` чистит приказы сам)
        Orders.source = "brace"
        commandRace(ctx, meas, meas.chase.commandArmy, meas.forces.armedEnemies, ctx.flags)
        Formation.brace(unitsNow, meas.chase.commandArmy.filter { it.id !in Squads.cmdDetach }, meas.forces.armedEnemies, Orders.commandOf)
    } else if (raceCommandNow) {
        cmdTicks++
        // приказы прошлого тика снимаются ЗДЕСЬ (v470): до того это делал `out.clear()` первой строкой гонки — единственное, что
        // она с приказами делала; ниже загон и марш пишут приказы заново
        Orders.source = "race"
        Orders.commandOf.clear()
        commandRace(ctx, meas, meas.chase.commandArmy, meas.forces.armedEnemies, ctx.flags)
        // прибор второго тика (v222): фаза plan стоит 20–28 мс на тиках 1–2 и 0,7 мс на третьем — метки внутри неё
        // называют, что именно (строка cpu печатается на первых трёх тиках и на медленных)
        cpuMark("p.race")
        // ...и ядро, оставшееся после раздачи заданий, идёт СТРОЕМ к цели (v163): прежде командир раздавал только
        // задания на захват, а ядро шло врозь по прежним веткам и приходило к бою растянутым
        // ...и только пока враг ДАЛЕКО: рядом с ним решают тактические ветки — экран, добыча, перехват, — а строй,
        // ведущий ядро на флаг мимо них, ронял screen и scatter (гейт 131 из 135)
        // ЗАГОН ВМЕСТО МАРША (v331): ядро, оставшееся после раздачи флагов, ловит его одиночку двумя группами
        val restCore = notCmdDetached(meas.chase.mobileArmy)
        val hunting = commandHunt(ctx, restCore, meas.forces.armedEnemies, Orders.commandOf)
        if (hunting) Orders.source = "hunt"
        // КОЛОННА НЕ РАСПУСКАЕТСЯ ПРИ ЕГО КУЛАКЕ (v572, см. USE_COLUMN_VS_FIST): марш ведёт ядро, пока его вооружённые
        // дальше MARCH_SAFE; ближе — каждый крип идёт по своей ступени, армия рассыпается, и одиночек он собирает
        val columnHolds = USE_COLUMN_VS_FIST && Signals.enemyFistNow
        if (columnHolds) columnTicks.n++
        if (!hunting && (columnHolds || meas.forces.armedEnemies.none { e -> meas.chase.mobileArmy.any { getRange(e, it) <= MARCH_SAFE } })) {
            // цель марша — своя (v164): раньше здесь стояла objectiveFlagId, посчитанная до командира
            val goal = loneHealerGoal(ctx, meas.chase.mobileArmy, meas.forces.armedEnemies)
                ?: defendedFlag(ctx, meas.fight.enemyNear)?.pos
                ?: commandGoal(ctx, meas.view, strat.obj.approachRate, strat.detach.farmerQuietNow, meas.chase.mobileArmy, meas.forces.armedEnemies)
            cpuMark("p.goal")
            val steps = HashMap<String, Position>()
            commandMarch(ctx, notCmdDetached(meas.chase.mobileArmy), goal, steps)
            Orders.commandOf.putAll(steps)
            if (steps.isNotEmpty()) Orders.source = "march"
            cpuMark("p.march")
        }
    }
        // ...и задания на захват снимаются вместе с режимом: без этого крип, отпущенный командиром за флагом,
        // оставался захватчиком НАВСЕГДА — армия таяла тик за тиком, и сценарий kite давал 0 очков (v160)
        else { Orders.source = "none"; Orders.commandOf.clear(); Squads.recallAll(Squads.Source.COMMANDER) }
    // ЛЕКАРИ — ПОД ПРИКАЗОМ ВО ВСЯКОМ КОНТАКТЕ (v436, см. USE_COMMANDER_HEALERS_IN_CONTACT). Режим боя против Coldkimchi
    // включён в 25–40 % тиков контакта (остальное — outmatched, retreat, posture, nofire), и в молчании командира клетку
    // лекаря выбирают ветки тактика: healMate ведёт к самому раненому в четырёх (уже отведённому из огня; совпадает с
    // теряющим хиты в 38,7 %), и цена доставки v435 действовала на ≤ 20 % лекаре-тиков (Opus, 32 реплея). Здесь командир
    // раздаёт ОДНИХ лекарей той же ценой клетки; бойцов не трогает — «командир на любой контакт» ронял roost и scatter
    if (USE_COMMANDER_HEALERS_IN_CONTACT && !commanderNow && meas.fight.contact && meas.forces.armedEnemies.isNotEmpty()) {
        val only = HashMap<String, Position>()
        publishDeal(commandFight(meas.chase.commandArmy, meas.forces.combatEnemies, meas.forces.armedEnemies, only, Intent.HOLD, ourFlagCells = ourFlagCells, healersOnly = true), tried = 1)
        var given = 0
        for (h in meas.chase.commandArmy) if (healerOnly(h) && h.id !in Squads.cmdDetach) only[h.id]?.let { Orders.commandOf[h.id] = it; given++ }
        if (given > 0) Orders.source = "heal"
        cmdHealTicks.n++; cmdHealGiven.n += given
    }

        // ИСПОЛНЕНИЕ ПРИКАЗА (v167): прогноз считает, что крип встанет туда, куда назначено, а между приказом и
    // клеткой стоят трафик, свопы и фатиг. Здесь считается доля тех, кто на следующем тике оказался ровно
    // на своей клетке: если она мала, ошибка прогноза объясняется не моделью, а неисполнением
    // КОЛЛИЗИИ ПРИКАЗОВ (v172, оператор: «не должно быть такого, что по приказам командира в одну клетку
    // собрались двое»). Внутри одной раздачи это исключено множеством taken, но приказы приходят из РАЗНЫХ
    // мест — бой, гонка, марш ядра, — и вот там пересечение возможно; здесь оно считается
}

/** ПРИМАНКА И РЕЗЕРВ (v587–v589, см. USE_BAIT_VS_DEBUFFED): армия делится пополам по id — лекари поровну, вооружённые через
 *  одного, так что в каждой половине есть лечение. На линии «центр его вооружённых -> медиана нашей армии» приманка встаёт в
 *  ENGAGE_RANGE от его центра, резерв — ещё на MASS_RANGE + FIST_RADIUS дальше (ближайшие проходимые клетки). Приказы — шаги пути. */
internal fun commandBait(ctx: Ctx, army: List<Creep>, hisArmed: List<Creep>, out: MutableMap<String, Position>) {
    val core = mobileOf(army).filter { bornCombatant(it) }
    if (core.size < 2 * BAIT_MIN || hisArmed.isEmpty()) return
    val healers = core.filter { healerOnly(it) }.sortedBy { it.id }
    val armed = core.filter { !healerOnly(it) }.sortedBy { it.id }
    val bait = ArrayList<Creep>()
    val reserve = ArrayList<Creep>()
    healers.forEachIndexed { i, c -> if (i % 2 == 0) bait.add(c) else reserve.add(c) }
    armed.forEachIndexed { i, c -> if (i % 2 == 0) bait.add(c) else reserve.add(c) }
    val (mx, my) = Formation.median(core)
    val fx = hisArmed.sumOf { it.x } / hisArmed.size
    val fy = hisArmed.sumOf { it.y } / hisArmed.size
    // ЛИНИЯ «ЕГО КУЛАК -> НАША АРМИЯ» (v589): приманка встаёт на ней в ENGAGE_RANGE от центра его вооружённых — там, куда он
    // подходит к группам, — а резерв на той же линии ещё на MASS_RANGE + FIST_RADIUS дальше. В первой руке v588 приманка
    // стояла там, где была армия, в 22 клетках от его кулака, и за 800 тиков он к ней не подошёл ни разу
    val vx = (mx - fx).toDouble(); val vy = (my - fy).toDouble()
    val norm = maxOf(abs(vx), abs(vy)).coerceAtLeast(1.0)
    val ux = vx / norm; val uy = vy / norm
    val gap = MASS_RANGE + FIST_RADIUS
    fun walkableNear(tx: Int, ty: Int): Position {
        for (r in 0..gap) for (ox in -r..r) for (oy in -r..r) {
            if (maxOf(abs(ox), abs(oy)) != r) continue
            val x = tx + ox; val y = ty + oy
            if (x < 1 || y < 1 || x > 98 || y > 98 || DistanceMap.isWall(x, y)) continue
            return InfluenceMap.cell(x, y)
        }
        return InfluenceMap.cell(tx.coerceIn(1, 98), ty.coerceIn(1, 98))
    }
    val baitAt = walkableNear((fx + ux * ENGAGE_RANGE).toInt().coerceIn(2, 97), (fy + uy * ENGAGE_RANGE).toInt().coerceIn(2, 97))
    val reserveAt = walkableNear((fx + ux * (ENGAGE_RANGE + gap)).toInt().coerceIn(2, 97), (fy + uy * (ENGAGE_RANGE + gap)).toInt().coerceIn(2, 97))
    val matrix = crowdMatrixOf(ctx, -1)
    for (c in bait) if (getRange(c, baitAt) > FIST_RADIUS / 2) pathStep(c, baitAt, FIST_RADIUS / 2, matrix)?.let { out[c.id] = it }
    for (c in reserve) if (getRange(c, reserveAt) > FIST_RADIUS / 2) pathStep(c, reserveAt, FIST_RADIUS / 2, matrix)?.let { out[c.id] = it }
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
// Перенесены из Instruments.kt дословно; Instruments их читает и печатает. `cmdSearched` сбрасывает операция `commanderTailDone()` — её зовёт оркестровка в конце тика.

/** Раздач, ушедших в мир (по одной на тик раздачи) / раздач сыграно, включая пробы замыслов (v449, прибор `deals=`). */
/** Записей в общее состояние ЗА ВРЕМЯ перебора замыслов (v460, прибор `impure=`): публикации поля нужды и изменения приказов между
 *  началом перебора и публикацией победителя. На всём гейте — ноль; ненулевое значение значит, что проба замысла пишет в мир. */
internal val impure = Gauges.counter("impure")

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
/** Хвост тика после перебора (v262) снят — операция владельца: `cmdSearched` пишет только этот файл, оркестровка зовёт её в конце тика. */
internal fun commanderTailDone() { if (cmdSearched) { cmdTailMax = maxOf(cmdTailMax, cpuMs() - cmdEndMs); cmdSearched = false } }

/** Постановка этого тика, как её задают старые решатели (v241, см. Strategist.snapshot): прибор `disp=`. */
internal var dispNow = "-"

internal var cmdTicks = 0                       // тиков, когда командир правил армией (диагностика, v143)

/** Набор командира по инерции (v465, дефект 4): тиков режима боя без `fightOnNow` при непустом `cmdDetach` (часть 1 — тики
 *  пустой армии, в `PainAndGain.runArmy`). */
internal val cmdInertFight = Gauges.counter("cmdinert")

// ==================== приборы стадии, бывшие членами object PainAndGain (v455, второй шаг архитектуры, этап 2) ====================

internal val cmdHealTicks = Gauges.counter("cmdheal", 1)   // тики «только лекари» и выданных приказов (v436, прибор cmdheal=)

internal val cmdHealGiven = Gauges.counter("cmdheal")

/** Почему командир не правил — в ПОСЛЕДНИЙ тик, когда не правил. ⚠️ В строке `t=` (поле `cmd=…:причина`)
 *  это значение УСТАРЕВШЕЕ: оно пишется только на тиках без командира, поэтому `cmd=0/200:outmatched
 *  mode=FIGHT` значит «сейчас бой, а в последний тик без командира причиной был outmatched». Разбор серии
 *  v220 прочёл его как причину текущего тика и приписал разгромам «отход»; честная картина по тикам —
 *  гистограмма `cmdwhy` (v221). */
internal var cmdBlocked = "-"

/** Тиков, где командир вёл начатый размен маршем на его армию, а не боевой раздачей (v569, `engmarch=`). */
internal val engageMarchN = Gauges.counter("engmarch")

/** Тиков гонки, где колонна держится при его кулаке ближе MARCH_SAFE (v572, `column=`). */
internal val columnTicks = Gauges.counter("column")

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

// ==================== аудит приказов (до v456 — в Instruments.kt) ====================
// `orderAudit` СНИМАЕТ приказы (`commandOf.remove` у клетки, назначенной двоим) и пишет память слежения (`Memory.pinWatch`,
// `meetWatch`, `orderPrev`) — это стадия уровня командира, а не прибор: файл приборов писал решения (второй шаг архитектуры,
// этап 4.4). Перенесён дословно, вместе со счётчиками, которые считает только он.

/** «Зажатого бьём» (v264, pin=взято/возможностей/удержано/сверено): мили под приказом с клеткой вплотную к зажатому
 *  врагу в шаге; приказ, поставивший его туда; и был ли зажатый вплотную к нему на следующем тике. */
internal val pinOpp = Gauges.counter("pin", 1)
internal val pinOrd = Gauges.counter("pin")
internal val pinChk = Gauges.counter("pin", 3)
internal val pinHeld = Gauges.counter("pin", 2)
/** Встречи уходящего раненого с лекарём в раздаче командира (v276, rotfm=). */
internal val meetRot = Gauges.counter("meet", 2)
internal val meetNear = Gauges.counter("meet", 1)
internal val meetPlan = Gauges.counter("meet")
internal val meetDone = Gauges.counter("meet", 3)
internal val meetChk = Gauges.counter("meet", 4)
/** Ноги за фокусом (v268, ffoc=до/после/стрелков): стрелки под приказом при живом фокусе, у которых фокус в досягаемости с
 *  нынешней клетки и с клетки приказа. */
internal val ffocAll = Gauges.counter("ffoc", 2)
internal val ffocBefore = Gauges.counter("ffoc")
internal val ffocAfter = Gauges.counter("ffoc", 1)
internal val lostEnemy = Gauges.counter("lost", 2, label = "foe")      // клетку приказа занял враг (v175)      // приказов, отменённых бегством (v173)     // сколько раз одна клетка была назначена двоим (v172)

/** АУДИТ ПРИКАЗОВ КОМАНДИРА (v256, этап 10; сегмент runArmy): одна клетка — двоим (clash), исполнение приказов прошлого тика (obey, lost=stuck/foe/fat/else), дальние приказы, запись orderPrev. Перенесено дословно. */
internal fun orderAudit(ctx: Ctx, meas: ArmyMeasures, strat: ArmyStrategy, targ: ArmyTargets) {
    val seen = HashMap<Int, Int>()
    Orders.commandOf.values.forEach { p -> seen[p.key] = (seen[p.key] ?: 0) + 1 }
    val dup = seen.values.count { it > 1 }
    orderClash.n += dup
    // ГАРАНТИЯ, А НЕ НАБЛЮДЕНИЕ (v176, оператор: «не должно быть такого, что по приказам командира в одну
    // клетку собрались двое»). Раздача держит своё множество занятых, но источников приказа несколько — бой,
    // гонка, марш, хранители, отход, — и на стыке коллизия всё же случалась (одна на 431 приказ, режим боя).
    // Здесь она снимается: клетка остаётся за первым, второй теряет приказ и идёт по общим правилам
    if (dup > 0) {
        val used = HashSet<Int>()
        val drop = ArrayList<String>()
        for ((id, p) in Orders.commandOf) { val k = p.key; if (!used.add(k)) drop.add(id) }
        drop.forEach { Orders.commandOf.remove(it) }
    }
    if (dup > 0 && DEBUG_LOG) {
        val where = seen.entries.firstOrNull { it.value > 1 }?.key ?: 0
        val who = Orders.commandOf.filterValues { it.key == where }.keys.joinToString(",")
        println("clash t=${getTicks()}: mode=${strat.dec.decision.cmdMode} cell=(${where / 100},${where % 100}) who=$who")
    }
    // ЗАЖАТОГО БЬЁМ — ПРИБОР (v264): считается по итоговым приказам, а не внутри раздачи — та идёт по разу на
    // замысел перебора и насчитала бы пробные планы. Сначала сверка вчерашних постановок, потом сегодняшние
    for ((id, foeId) in Memory.pinWatch) {
        val c = meas.chase.commandArmy.firstOrNull { it.id == id } ?: continue
        val e = ctx.enemyCreeps.firstOrNull { it.id == foeId } ?: continue
        pinChk.n++
        if (getRange(c, e) <= 1) pinHeld.n++
    }
    Memory.pinWatch.clear()
    // ВСТРЕЧА РАНЕНОГО С ЛЕКАРЁМ — ПРИБОР ПО ИСПОЛНЕНИЮ (v276, meet=план/лекарь рядом/уходящих с приказом/исполнено/сверено):
    // счётчик rotfm считал встречи в пробных планах, а по реплеям лекарь после выхода раненого стоит в двух клетках, как и
    // до правки. Здесь — по итоговым приказам: у уходящего по фокусу с приказом был ли лекарь в двух клетках от его
    // клетки, получил ли лекарь клетку вплотную к ней, и стояли ли они вплотную на следующем тике
    for ((rid, hid) in Memory.meetWatch) {
        val r = meas.chase.commandArmy.firstOrNull { it.id == rid } ?: continue
        val h = meas.chase.commandArmy.firstOrNull { it.id == hid } ?: continue
        meetChk.n++
        if (getRange(r, h) <= 1) meetDone.n++
    }
    Memory.meetWatch.clear()
    for (rid in Memory.rotByFocus) {
        val r = meas.chase.commandArmy.firstOrNull { it.id == rid } ?: continue
        val dest = Orders.commandOf[rid] ?: continue
        meetRot.n++
        val medics = meas.chase.commandArmy.filter { it.id != rid && healerOnly(it) }
        if (medics.any { getRange(it, dest) <= 2 }) meetNear.n++
        val m = medics.firstOrNull { h -> Orders.commandOf[h.id]?.let { getRange(it, dest) <= 1 } == true } ?: continue
        meetPlan.n++
        Memory.meetWatch[rid] = m.id
    }
    // НОГИ ЗА ФОКУСОМ — ПРИБОР (v268, ffoc=до/после/стрелков): стрелки под приказом командира при живом фокусе — у скольких
    // фокус в досягаемости с нынешней клетки и с клетки приказа. Реплеи до правки: 25–32 % до шага, 14–20 % после
    targ.focus.focusTarget?.takeIf { it.hits > 0 }?.let { f ->
        for (c in meas.chase.commandArmy) {
            if (!hasRanged(c)) continue
            val cell = Orders.commandOf[c.id] ?: continue
            ffocAll.n++
            if (getRange(c, f) <= RANGED_RANGE) ffocBefore.n++
            if (getRange(cell, f) <= RANGED_RANGE) ffocAfter.n++
        }
    }
    run {
        val foes = ctx.combatEnemies.filter { e -> InfluenceMap.profileOf(e).let { it.melee + it.ranged + it.heal > 0.0 } }
        if (foes.isEmpty() || Orders.commandOf.isEmpty()) return@run
        val stuck = HashSet<Int>()
        for (e in ctx.enemyCreeps) if (e.fatigue > 0) stuck.add(e.key)
        val foeAt = HashSet<Int>()
        for (e in ctx.enemyCreeps) foeAt.add(e.key)
        val plan = HashMap<String, Int>()
        for (f in meas.chase.commandArmy) if (f.hits > 0) plan[f.id] = (Orders.commandOf[f.id] ?: InfluenceMap.cell(f.x, f.y)).let { it.key }
        for (c in meas.chase.commandArmy) {
            if (!(meleeOnlyLive(c))) continue
            val mine = Orders.commandOf[c.id] ?: continue
            val near = foes.filter { getRange(c, it) <= 2 }
            if (near.isEmpty()) continue
            val ours = HashSet<Int>()
            for ((id, k) in plan) if (id != c.id) ours.add(k)
            val canStep = canMove(c) && c.fatigue == 0
            var opp = false
            for (dx in -1..1) for (dy in -1..1) {
                if (opp) continue
                if (!canStep && (dx != 0 || dy != 0)) continue
                val x = c.x + dx
                val y = c.y + dy
                if (x < 0 || y < 0 || x > 99 || y > 99 || DistanceMap.isTerrainWall(x, y)) continue
                val key = key(x, y)
                if (key in foeAt || key in ours) continue
                val p = InfluenceMap.cell(x, y)
                if (near.any { e -> getRange(p, e) <= 1 && pinnedAt(p, e, ours, stuck) }) opp = true
            }
            if (!opp) continue
            pinOpp.n++
            val hit = near.firstOrNull { e -> getRange(mine, e) <= 1 && pinnedAt(mine, e, ours, stuck) } ?: continue
            pinOrd.n++
            Memory.pinWatch[c.id] = hit.id
        }
    }
    Memory.orderPrev.forEach { (id, cell) ->
        // ...и захватчик из аудита исключается: его приказ — ФЛАГ, а не клетка, и ведёт его свой цикл;
        // считать его ослушником было бы неверно (v173)
        if (id in Squads.cmdDetach) return@forEach
        val c = meas.chase.commandArmy.firstOrNull { it.id == id } ?: return@forEach
        orderAuditN.n++
        // ...и ПРИКАЗ В ДВУХ ШАГАХ ИСПОЛНЕН, ЕСЛИ КРИП СТАЛ БЛИЖЕ (v184). Прибор сверял клетку крипа с
        // НАЗНАЧЕННОЙ и только с ней, а строй (`commandBrace`) назначает место в строю за несколько клеток —
        // такой приказ не мог быть засчитан НИКОГДА, и едва строй заработал, исполнение упало со 99 % до 62 %
        // при том, что крипы шли туда, куда велено. Из-за этого я успел записать в дефекты то, чего не было,
        // и починить не тот код (см. USE_BRACE_STEPS). По всей серии v183 «ушёл в другую клетку» набрал
        // 2 022 случая из 39 245 — почти все они этой природы
        val far = 
            (Orders.orderDist[id] ?: 0) > 1 && maxOf(abs(c.x - cell.x), abs(c.y - cell.y)) < (Orders.orderDist[id] ?: 0)
        if ((c.x == cell.x && c.y == cell.y) || far) orderAuditOk.n++
        else {
            // ...и КУДА делись остальные (v170): приказ был «стой», а крип ушёл; крип не двинулся
            // вовсе; двинулся, но в другую клетку; или не мог двигаться от усталости
            val here = Orders.orderWas[id]
            when {
                cell.x == here?.first && cell.y == here.second -> lostStay.n++
                // ...клетку мог занять ВРАГ: он ходит одновременно с нами, и его шаг делает приказ
                // неисполнимым задним числом — это неустранимо в принципе, и считать надо отдельно (v175)
                ctx.enemyCreeps.any { e -> e.x == cell.x && e.y == cell.y } -> lostEnemy.n++
                c.x == here?.first && c.y == here.second -> lostStuck.n++
                (Orders.orderFatigue[id] ?: 0) > 0 -> lostFatigue.n++
                else -> lostElsewhere.n++
            }
        }
        // ...и отдельно: СТАЛ ЛИ БЛИЖЕ к назначенной клетке (приказ бывает в двух шагах, за тик не дойти)
        val wasD = Orders.orderDist[id] ?: 99
        val nowD = maxOf(abs(c.x - cell.x), abs(c.y - cell.y))
        if (nowD < wasD) orderAuditCloser++
        // ...и ДЕРЖИТСЯ ЛИ приказ: та же клетка, что была назначена в прошлый тик
        if (Orders.commandOf[id]?.let { it.x == cell.x && it.y == cell.y } == true) orderAuditSame++
    }
    // ...и сколько приказов вообще достижимо за тик: клетка в двух шагах не может быть занята сразу,
    // и доля исполнения ограничена этим по построению (v170)
    Orders.commandOf.forEach { (id, p) ->
        val c = meas.chase.commandArmy.firstOrNull { it.id == id } ?: return@forEach
        if (maxOf(abs(c.x - p.x), abs(c.y - p.y)) > 1) orderFar++
    }
    Orders.orderWas.clear(); Orders.orderFatigue.clear()
    meas.chase.commandArmy.forEach { c -> Orders.orderWas[c.id] = c.x to c.y; Orders.orderFatigue[c.id] = c.fatigue }
    Orders.orderDist.clear()
    Orders.commandOf.forEach { (id, p) ->
        val c = meas.chase.commandArmy.firstOrNull { it.id == id }
        if (c != null) Orders.orderDist[id] = maxOf(abs(c.x - p.x), abs(c.y - p.y))
    }
    Memory.orderPrev.clear()
    Orders.commandOf.forEach { (id, p) -> Memory.orderPrev[id] = p }
    // потеря за прошлый тик по всем — ДО цикла: lastHits обновляется в конце каждой итерации, и для уже обработанных она была бы нулём
}

/** ПРИКАЗЫ КОМАНДИРА (v459, второй шаг архитектуры, этап 6): словари одного тика, которые живут весь матч и чистятся на своих местах — перенесены из `object PainAndGain` как есть. С v469 тик без армии сбрасывает их операцией [dismiss] (до неё `commandOf` переживал такой тик — `runArmy` выходит раньше раздачи). Владелец — в списке починки после оборванного тика. */
internal object Orders {
    internal val commandOf = HashMap<String, Position>()   // крип → клетка, назначенная командиром (v137)
    internal val missionOf = HashMap<String, Char>()      // крип → буква задания его отряда этим тиком (v252, из Strategist.snapshot)
    internal val orderWas = HashMap<String, Pair<Int, Int>>()   // где крип стоял в момент приказа (v170)
    internal val orderFatigue = HashMap<String, Int>()
    internal val orderDist = HashMap<String, Int>()

    /** КТО ИМЕННО ПРАВИЛ ЭТИМ ТИКОМ (v486, прибор `rule=`). Ветки [armyCommand] взаимоисключающи (цепочка if/else),
     *  поэтому источник приказа описывается ОДНОЙ меткой на тик: `fight` — раздача боя, `fight.tight` — она же без
     *  перебора под страховкой CPU, `brace` — изготовка, `hunt` — загон, `march` — колонна похода, `heal` — раздача
     *  одних лекарей в контакте, `none` — приказов не выдавалось. Метка нужна потому, что `step=order` складывал все
     *  шесть источников в одно число, и по логу нельзя было сказать, где правит командир, а где марш или изготовка;
     *  ровно на этом я в этот день дважды искал дефект не в том месте. */
    internal var source = "none"

    /** СБРОС ПРИКАЗОВ В ТИК БЕЗ АРМИИ (v469, дефект 8 постановки). Стадии армии не зовутся, раздачи нет, и приказы прошлого тика
     *  переживали тик: `markOrdered(commandOf.keys)` отдавал их арбитру движения, где живой крип с застарелым приказом — боец,
     *  ставший бегуном после раздевания, — получал право приказа (`SWAP_RESPECTS_INTENT`, `orderedDenied`) на клетку, которой
     *  ему никто в этот тик не назначал; мёртвые id в словарях висели до конца матча. Операция одна на все словари тика;
     *  прибор `cmdstale=тиков со снятыми приказами/приказов снято/из них у живых крипов` — третья часть и есть число тиков,
     *  где сброс мог изменить движение. */
    fun dismiss(living: List<Creep>) {
        if (commandOf.isNotEmpty()) {
            staleTicks.n++; staleOrders.n += commandOf.size
            staleLive.n += commandOf.keys.count { id -> living.any { it.id == id } }
        }
        commandOf.clear(); missionOf.clear(); orderWas.clear(); orderFatigue.clear(); orderDist.clear(); source = "none"
    }
    internal val staleTicks = Gauges.counter("cmdstale")
    internal val staleOrders = Gauges.counter("cmdstale", 1)
    internal val staleLive = Gauges.counter("cmdstale", 2)
}
