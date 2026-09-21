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
import kotlin.reflect.*

/**
 * ТАКТИК (v251, этап 9 переработки, срез 1 — тождественный перенос; план — docs/pain-and-gain-rework.md, раздел 1).
 * Здесь решается, куда шагнёт каждый боец армии: признаки крипа ([Turn]), лестница цели — строки списка [ladder] (порядок
 * списка и есть приоритет, другого перечня ступеней нет; причины порядка — `tools/stub/painandgain/order.txt`), поле,
 * бегство и сплочение, цепочка шага — строки списка [steps] — и запрос хода арбитру. Прежде это был
 * цикл `for (creep in army)` внутри `runArmy` на 675 строк; тело перенесено дословно в [creepTurn], а величины тика,
 * которые цикл читал из `runArmy`, собраны в [ArmyTick]. Разрешение имён сохранено по построению: локальная переменная
 * тела → поле [ArmyTick] (приёмник `with`) → член `PainAndGain` (приёмник расширения) — ровно прежнее «локальная тела →
 * локальная runArmy → член объекта». Цикл однопроходный и идёт в порядке `army`: напарники читают `Memory.engagingIds`,
 * записанный соседом раньше в этом же тике. Следующие срезы — дробление тела на признаки / цель / шаг / запись и
 * `Proposal` с приоритетом как выход шага (v252).
 */

/** Приоритет предложенного хода (план, раздел 2): спасение выше задания, задание выше случая. */
internal enum class Priority { SURVIVE, MISSION, OPPORTUNITY }

/**
 * ПРЕДЛОЖЕНИЕ ХОДА (v252, этап 9): что тактик предлагает арбитру за одного бойца армии. [step] — клетка шага или null
 * («стоять»), [priority] — SURVIVE у бегства (`flee`, единственный смертельный порог — `mustFlee`), OPPORTUNITY у
 * свободного шага за добычей (ступени с меткой [RowMark.OPPORTUNITY] — метка стоит в строке [ladder]), MISSION у остального; [rank] — ранг
 * толкания `Arbiter.pushRank`, по которому арбитр разводит ходы (пока он приоритета не читает — срез тождественный);
 * [mission] — буква задания отряда крипа из постановки стратега (`Strategist.snapshot`: F бой, T захват, G поход, E
 * сопровождение), [term] — ветка шага, а у свободного шага — ступень лестницы, её и печатает прибор `tac t=` как
 * `задание.терм`. [rung] и [stepTag] — прежние теги для переписи `rung t=` и `why t=`. Удар, выстрел и лечение пока
 * назначаются после цикла (commandFire / healAndShoot) — в предложение они войдут отдельным срезом.
 */
internal class Proposal(
    val creep: Creep, val step: Position?, val priority: Priority, val rank: Int,
    val mission: Char, val term: String, val rung: String, val stepTag: String,
) {
    val why: String get() = "$mission.$term"
}

/** Приоритет по ветке шага и ступени лестницы — таблица из заголовка [Proposal]. */
internal fun priorityOf(step: RowMark, rung: RowMark): Priority = when {
    step == RowMark.SURVIVE -> Priority.SURVIVE
    step != RowMark.FREE -> Priority.MISSION
    rung == RowMark.OPPORTUNITY -> Priority.OPPORTUNITY
    else -> Priority.MISSION
}

/** Отдать предложение арбитру: перепись (прежняя и новая) и запрос хода — в прежнем порядке побочных действий. */
internal fun submit(p: Proposal, ctx: Ctx, view: ExchangeView, fist: Pair<Int, Int>? = null) {
    rungCount.bump(p.rung)
    stepCount.bump(p.stepTag)
    // ИСТОЧНИК ШАГА ОТДЕЛЬНО ПО ЛЕКАРЯМ (v516, `hstep=`; оператор 21.09.2026: «расстановкой управляет командир и
    // фронт в разные моменты боя»). Прибор `step=` считает все роли в одной строке (`free:813, order:704,
    // slotStep:377, flee:81` в разобранном поражении), и по нему нельзя сказать, чья правка вообще достаёт лекаря:
    // приказ командира (`order`, клетка из раздачи), слот строя (`slotStep`/`slotHold`), свободный шаг тактика
    // (`free`) или лестница бегства (`flee`). Это ровно то правило, что записано как «цена живёт там, где крип
    // ходит»: v435 стояла в командире, а лекарей 80 % тиков вели ветки тактика
    if (healerOnly(p.creep)) hstepTag.bump(p.stepTag)
    tacCount.bump(p.why)
    prioCount.bump(p.priority.name)
    // ЗАХВАТ — ТОЛЬКО ЧЕРЕЗ ВОРОТА (v282). Флаг берёт всякий, кто встал на его клетку, а ворота захвата (`captureBlock`)
    // спрашивали бегун и цели армии (objective, grab), но не шаг бойца: поле потока всегда открывает клетку ЦЕЛИ
    // (`DistanceMap.flowFieldTo`, «цель всегда достижима»), и когда цель — пост на клетке чужого флага, боец встаёт на
    // флаг. Из угла (85,88) пост — сама клетка D5 (49,49): в руке v281 ворота к 60-му тику отказали всем 413 предложениям,
    // а `melee_3` на 57-м, уже в уклонении, шагнул (50,50) → (49,49) — D5 (весь входящий ×1,1) наш за 9 тиков до первого
    // размена, и так во всех девяти играх из этого угла (свой дебафф на 56–74-м, размен на 67–76-м). Находка записана с
    // v230 («D5 из угла (85,88) берётся прямо на точке сбора») и с тех пор открыта. Здесь — единственное место, где
    // рождается интент шага армии, поэтому проверка одна на все ветки: разрешённый захват проходит как прежде
    // (planCapture), запрещённый — крип стоит, как POISED-бегун
    val flagAt = p.step?.let { s -> ctx.flags.firstOrNull { !it.ours && it.pos.x == s.x && it.pos.y == s.y } }
    val step0 = if (flagAt != null && captureBlock(ctx, flagAt, view, CapAsker.ARMY) != null) { strayCapRefused.n++; null } else p.step
    // КУЛАК ДЕЙСТВУЕТ НА ВСЯКИЙ ШАГ В БОЮ (v490, см. USE_FIST_EVERY_STEP). Запрет уходить дальше `FIST_RADIUS +
    // STRAGGLER_SLACK` от медианы боевых живёт внутри командирской раздачи (`Formation.fist`, зовётся из `Deal.kt:72`),
    // а она правит 5–20 % крипо-тиков — значит то единственное, что держит армию вместе, на остальных четырёх пятых не
    // действует вовсе. Здесь тот же запрет стоит на ЕДИНСТВЕННОМ месте рождения шага и потому действует на все ветки.
    // Он не выбирает клетку и не спорит с выбором: отменяется только шаг, который уводит ДАЛЬШЕ порога И дальше, чем
    // крип стоит сейчас, — возвращение к своим разрешено всегда. Спасение (SURVIVE) не трогается: выживание выше
    // задания. Это НЕ расширение командира (v155/v156, 0-8 и падения roost/scatter/camp) и не расширение строя
    // (v384/v385, 12-20 против 6-10): те раздавали клетки, этот ограничивает движение
    val step = if (!USE_FIST_EVERY_STEP || fist == null || step0 == null || p.priority == Priority.SURVIVE) step0 else {
        val now = maxOf(abs(p.creep.x - fist.first), abs(p.creep.y - fist.second))
        val next = maxOf(abs(step0.x - fist.first), abs(step0.y - fist.second))
        fistAll.n++
        if (next > FIST_RADIUS + STRAGGLER_SLACK && next > now) { fistHeld.n++; null } else step0
    }
    if (step != null) { TrafficManager.request(p.creep, step, p.rank); planCapture(ctx, step) }
}

/** Величины тика, которые покрипный цикл читает из runArmy; все посчитаны до цикла и в нём не меняются. */
internal class ArmyTick(
    val meas: ArmyMeasures,
    val strat: ArmyStrategy,
    val targ: ArmyTargets,
    val stanceOut: ArmyStance,
) {
    /** Центр кулака этого тика (v490, см. USE_FIST_EVERY_STEP): медиана боевых, пока бой идёт; вне боя кулака нет и
     *  запрет не действует — армия ходит за флагами, и стягивать её незачем. */
    val fistNow: Pair<Int, Int>? =
        if (!USE_FIST_EVERY_STEP || !meas.fight.contact) null
        else strat.inp.combatArmy.ifEmpty { null }?.let { Formation.median(it) }
}

/**
 * РОТАЦИЯ ПО ЕГО ФОКУСУ (v275, разбор 97 игр против ●ω<♥♪#6: все версии 0-8…2-14). Его стволы бьют нашего крипа с
 * наименьшей долей хитов в досягаемости (100 % выстрелов с выбором), и его раненые выходят из-под нашего огня за 1–2 тика
 * и возвращаются вылеченными (вылечено 95 %, погибло 4 %, больше четырёх частей он не теряет), а наши уходят по порогу
 * ROTATE_OUT — половине живого оружия — поздно: выход за 3 тика, 19 % не выходят вовсе, вылечено 65 %, погибло 35 %. И
 * в бою ротацию перебивал приказ командира, который уводил только добиваемого (hurtBadly). Наши пять побед над ним — игры,
 * где выжили раненые (93 % вылеченных против 52 %).
 *
 * Правило соперника берётся из сверки: каждый тик предсказываются главные жертвы его стволов по двум правилам — «наименьшая
 * доля» (Forecast.fracTargetOf) и «лекарь, иначе ближайший» (Forecast.wallTargetOf, v224/v229), — и на следующем тике
 * сверяются с тем, кто потерял больше всех; за окно TOUCH_WINDOW, не раньше STALL_TICKS замеров, действует то, что попадает
 * чаще. Пока его правило — «наименьшая доля»: боец, раненый и предсказанный жертвой его стволов, чей предсказанный урон
 * больше нашего лечения, доходящего до его клетки, уходит СРАЗУ — до потери частей, а не на половине оружия; возвращается,
 * когда вылечен полностью или уже не самый раненый среди наших в его досягаемости (тогда его стволы и так бьют другого).
 * Так его огонь переходит с одного на другого, и ни один не раздевается — его же цикл. Пока правило не его — ротация по
 * фокусу снимается, прежняя (ROTATE_OUT) остаётся. Новых чисел нет: дальности — движка, окна — существующие.
 */
internal fun rotateByFocus(army: List<Creep>, combatEnemies: List<Creep>) {
    val live = livingCombatants(army)
    // ПРЕДСКАЗАТЕЛЬ ТОЧНЕЕ (v276, разбор v275 по реплеям: предсказатель угадывал 64,8 % против 100 % его правила). Три
    // причины и три поправки: кандидаты его стволов — ВСЕ наши, и раздетые тоже (он их бьёт, а они выпадали из «живых»);
    // мили бьёт только вплотную — дальность удара движок сверяет по клеткам начала тика, и шаг перед ударом этого тика не
    // даёт (было MELEE_STEP_REACH = 2); истина — полученный УРОН, а не чистая потеря (жертву, которую подлечили, чистая
    // потеря не называла): урон = потеря за тик плюс наше лечение, назначенное ей прошлым тиком (Memory.healGiven). По
    // реплеям трёх поправок хватает на 92 % против урона, и правило перестаёт мигать (было выключено 30 % тиков контакта)
    val all = living(army)
    var mostLost: Creep? = null
    var mostLoss = 0
    for (c in all) {
        val l = (Memory.lastHits[c.id] ?: c.hits) - c.hits + (Memory.healGiven[c.id] ?: 0)
        if (l > mostLoss) { mostLoss = l; mostLost = c }
    }
    val truth = mostLost
    if (truth != null && (Memory.fracPrev != null || Memory.addrPrev != null)) {
        Memory.fracHits.addLast(Memory.fracPrev == truth.id)
        Memory.addrHits.addLast(Memory.addrPrev == truth.id)
        while (Memory.fracHits.size > TOUCH_WINDOW) Memory.fracHits.removeFirst()
        while (Memory.addrHits.size > TOUCH_WINDOW) Memory.addrHits.removeFirst()
        rotfN.n++
        if (Memory.fracPrev == truth.id) rotfF.n++
        if (Memory.addrPrev == truth.id) rotfA.n++
    }
    val byFrac = HashMap<String, Double>()
    val byAddr = HashMap<String, Double>()
    for (e in combatEnemies) {
        val q = InfluenceMap.profileOf(e)
        if (q.ranged > 0.0) {
            Forecast.fracTargetOf(unitsNow, e, all, RANGED_RANGE)?.let { t -> byFrac[t.id] = (byFrac[t.id] ?: 0.0) + q.ranged * InfluenceMap.takenOf(t) }
            Forecast.wallTargetOf(unitsNow, e, all, RANGED_RANGE)?.let { t -> byAddr[t.id] = (byAddr[t.id] ?: 0.0) + q.ranged * InfluenceMap.takenOf(t) }
        }
        if (q.melee > 0.0) {
            Forecast.fracTargetOf(unitsNow, e, all, 1)?.let { t -> byFrac[t.id] = (byFrac[t.id] ?: 0.0) + q.melee * InfluenceMap.takenOf(t) }
            Forecast.wallTargetOf(unitsNow, e, all, 1)?.let { t -> byAddr[t.id] = (byAddr[t.id] ?: 0.0) + q.melee * InfluenceMap.takenOf(t) }
        }
    }
    Memory.fracPrev = byFrac.maxByOrNull { it.value }?.key
    Memory.addrPrev = byAddr.maxByOrNull { it.value }?.key
    // ОЖИДАЕМЫЙ ВХОДЯЩИЙ ДЛЯ ЛЕЧЕНИЯ — АДРЕСНЫЙ (v292, см. focusPredDmg): модель, чаще попадающая за окно
    val fracN = Memory.fracHits.count { it }
    val addrN = Memory.addrHits.count { it }
    TacticianState.focusPredDmg = if (Memory.fracHits.size < STALL_TICKS) null else if (fracN >= addrN) HashMap(byFrac) else HashMap(byAddr)
    // ОН ОХОТИТСЯ ЗА РАНЕНЫМИ (v294): модель «наименьшая доля» за окно попадает чаще модели «лекарь, иначе ближайший»
    // ...и не в штурме, к которому нас вынуждает гонка очков (v295): отставая, мы обязаны брать его флаги, и в штурме лекари
    // идут за бойцами, а раненые не выходят — стенд match31:camp (лагерь бьёт наименьшие хиты и держит центр) при лекарях
    // позади проигрывал по очкам 23 934 : 24 002; ●ω<♥♪#6 флагов до 1500-го тика не берёт, и штурмовать его гонка не велит
    TacticianState.huntsWounded = Memory.fracHits.size >= STALL_TICKS && fracN > addrN && !WorldState.behindOnScore
    // ПРИБОР ПРАВИЛА, КОТОРОЕ РЕШАЕТ СУДЬБУ ЛЕКАРЕЙ (v509, `hw=` сработало / всего, `hwwhy=` на чём остановилось).
    // `huntsWounded` — единственное, что в КОНТАКТЕ выводит лекаря из досягаемости его стрелков (см. reachNow:
    // иначе избегается только мили, два шага). Реплеи четырёх поражений от MetalicaX#17 (21.09.2026) говорят, что
    // решает именно это: наши лекари под его стволами 85/383/106/99 крип-тиков против 14/161/20/14 в победах, он
    // кладёт в них 27-62 % одиночных выстрелов против наших 0-2 % по его, и с тика, где наш урон падает ниже его
    // ёмкости лечения 216, он не теряет больше никого. А прибора у правила не было ни одного: `hunt=` — это загон
    // (v331), другое правило. Три причины отказа разделены, потому что лечатся они по-разному: окно ещё не
    // наполнено, модель «наименьшая доля» не выиграла у адресной, или мы отстаём по счёту — последнее поставлено
    // в v295 ради штурма, и в этих матчах он отстаёт по нашей вине ровно с того тика, где он взял центральный флаг
    // ЛЕКАРЬ УХОДИТ ИЗ-ПОД ЕГО СТРЕЛКОВ ПО СВОЕЙ ПРИЧИНЕ, А НЕ ПО ЧУЖОЙ (v518, см. USE_HEALERS_OUT_OF_REACH).
    // `huntsWounded` управляло ДВУМЯ решениями сразу — выходом раненого из боя и выводом лекаря из досягаемости, —
    // и для второго его условие перевёрнуто: `huntsWounded = false` означает, что выиграла АДРЕСНАЯ модель, то есть
    // «он бьёт лекаря, если тот в досягаемости, иначе ближайшего». Ровно против такого соперника лекарю и нельзя
    // стоять под его стволами, а бот там его и оставлял: в контакте `reachNow` сужается до клеток вплотную к его
    // мили, клетки под стрелками остаются разрешёнными, и ступень бегства `supportInReach` в них не срабатывает.
    // Числа (21.09.2026, 35 реплеев против MetalicaX#17 и 1382 лога серий): `hwwhy=addrModel` в 7 руках из 8, а
    // когда наш вооружённый лекарь был в его тройке, он стрелял именно в лекаря в 61 % из 2523 случаев (мы в той же
    // позиции — 43 %). Экспозиция лекаря `hexp` 22,5 % в 106 победах против 54,9 % в 307 поражениях; по реплеям в
    // окне 0..60 тиков боя — 41 % в победах против 77 % в аннигиляциях, а дистанция лекаря до ближайшего врага
    // 6,1 против 3,1 клетки. Цена стояния на тройке — ноль: раненых вплотную у лекаря 1,24 против 1,27, в трёх
    // клетках 4,59 против 4,55, то есть доставка та же, а под огнём он 42 % тиков вместо 12 %.
    // Выход раненого остаётся под `huntsWounded`: там довод v294 верен — уведённый из боя раненый есть огонь,
    // потерянный даром, если его всё равно не ищут.
    TacticianState.healersOutOfReach = USE_HEALERS_OUT_OF_REACH && Memory.addrHits.size >= STALL_TICKS && addrN >= fracN
    horAll.n++
    if (TacticianState.healersOutOfReach) horOn.n++
    hwAll.n++
    if (TacticianState.huntsWounded) hwOn.n++
    hwWhy.bump(
        if (Memory.fracHits.size < STALL_TICKS) "window"
        else if (fracN <= addrN) "addrModel"
        else if (WorldState.behindOnScore) "behind"
        else "on"
    )
    val healersLive = live.any { healerOnly(it) }
    // РОТАЦИЯ ПО ЕГО ФОКУСУ ЖИВЁТ ПО ТОЙ МОДЕЛИ, ЧТО ВЫИГРАЛА СВЕРКУ (v510, см. USE_ROT_BY_BEST_MODEL).
    // В этой же функции двадцатью строками выше v292 уже установила верный принцип: ожидаемый входящий для лечения
    // берётся у модели, ЧАЩЕ ПОПАДАЮЩЕЙ за окно (`focusPredDmg`). А ротация — решение о том же самом, «кого он
    // сейчас выстрелит», — требовала победы ОДНОЙ конкретной модели («наименьшая доля») и при её проигрыше
    // выключалась целиком, да ещё и жертву брала из `byFrac`, то есть из проигравшей. Две точки читают одну
    // величину по-разному — тот самый класс, что уже был у удара мили (v506).
    // Цена названа прибором: восемь тестовых рук против MetalicaX#17 (21.09.2026, v509) дали `hwwhy=addrModel`
    // в СЕМИ из восьми — у него выигрывает адресная модель «лекарь в досягаемости, иначе ближайший», — и ротация
    // была мертва во всех семи (`rotf` с нулевыми числителями). В единственной руке, где механизм ожил
    // (`hwwhy=on:1145`, ротация 1199 тиков), экспозиция лекарей `hexp` = 24/132 = 18 % против 32 % во второй
    // победе и 38-70 % в шести поражениях, и рука выиграна.
    val bestFrac = Memory.fracHits.count { it } >= Memory.addrHits.count { it }
    val rotRules = healersLive && Memory.fracHits.size >= STALL_TICKS &&
        (if (USE_ROT_BY_BEST_MODEL) true else Memory.fracHits.count { it } > Memory.addrHits.count { it })
    if (!rotRules) {
        for (id in Memory.rotByFocus) Memory.rotatingIds.remove(id)
        Memory.rotByFocus.clear()
        return
    }
    rotfOn.n++
    fun frac(c: Creep) = c.hits.toDouble() / maxOf(1, c.hitsMax)
    fun inReach(c: Creep) = combatEnemies.any { e ->
        val q = InfluenceMap.profileOf(e)
        (q.ranged > 0.0 && getRange(e, c) <= RANGED_RANGE) || (q.melee > 0.0 && getRange(e, c) <= MELEE_STEP_REACH)
    }
    // возврат: вылечен полностью или уже не самый раненый среди наших в его досягаемости
    val back = ArrayList<String>()
    for (id in Memory.rotByFocus) {
        val c = live.firstOrNull { it.id == id }
        if (c == null) { back.add(id); continue }
        if (c.hits >= c.hitsMax) { back.add(id); continue }
        val lowest = live.filter { it.id != id && it.id !in Memory.rotByFocus && inReach(it) }.minOfOrNull { frac(it) }
        if (lowest != null && frac(c) > lowest) back.add(id)
    }
    for (id in back) { Memory.rotByFocus.remove(id); Memory.rotatingIds.remove(id); rotfBack.n++ }
    // выход: раненый, предсказанная жертва его стволов, и их урон больше нашего лечения в его клетке
    for (c in live) {
        if (!hasWeapon(c) || c.id in Memory.rotByFocus || !canMove(c) || c.hits >= c.hitsMax) continue
        // ...и ЖЕРТВА — ИЗ ТОЙ ЖЕ ПОБЕДИВШЕЙ МОДЕЛИ (v510): раньше здесь стояла `byFrac` даже тогда, когда сверка
        // называла победителем адресную, то есть правило уводило из-под огня того, кого он стрелять не собирался
        val pred = if (!USE_ROT_BY_BEST_MODEL || bestFrac) byFrac else byAddr
        val inc = pred[c.id] ?: continue
        if (inc <= InfluenceMap.healReachAt(c.key)) continue
        Memory.rotByFocus.add(c.id)
        Memory.rotatingIds.add(c.id)
        Memory.rotateSince[c.id] = getTicks()
        rotfOut.n++
    }
    rotfTicks.n += Memory.rotByFocus.size
}

/**
 * РАНЕНЫЙ ВЫХОДИТ ИЗ ЕГО ЗОНЫ И НЕ ВОЗВРАЩАЕТСЯ, ПОКА НЕ ВЫЛЕЧЕН (v285) — его же приём. Разбор 87 игр против ●ω<♥♪#6
 * (первые 100 тиков контакта, эпизоды «потерял три части и больше, в его досягаемости»): за три тика его раненый отходит
 * сам на +1,84 клетки, наш — на +0,42 (p < 0,001); свободная клетка дальше от его стрелков и нулевая усталость были у
 * наших в 81 % тиков, а отошли они в 42 %, его — в 70 %. Его склонность уходить растёт с уроном — у мили с 28 % до 68 %
 * к трём потерянным частям, у стрелков с 55 % до 80 % уже при одной, — у наших от урона не зависит (стрелки 43–52 %,
 * мили 26–45 %). Наши лекари стоят в его зоне 8,4–8,9 % тиков, его — 2,7–4,8 %. Отсюда и пороги, их никто не называл
 * числом: стрелок и лекарь выходят с первой потерянной части, мили — со второй (восемь ATTACK впереди тела), зона —
 * клетки его досягаемости (`reachCells`: стрелок в трёх, мили в двух). Ротация ROTATE_OUT уходила на половине оружия, а
 * ротация по фокусу (v275) — только по прогнозу его цели; обе в бою перебивались приказом и слотом строя. Здесь выход —
 * ступень выживания в лестнице (как бегство, v240: выше приказа) и проход отхода командира; вышедший — в `rotatingIds`
 * (слот строя и пары гонки его не берут), возвращается, когда потерянных частей меньше порога.
 */
internal fun stepOutWounded(army: List<Creep>, reach: Set<Int>, enemyRetreating: Boolean) {
    fun lost(c: Creep) = c.body.count { it.hits <= 0 }
    fun need(c: Creep) = if (isMelee(c)) 2 else 1
    val live = living(army)
    // ...И НЕ ПРОТИВ ОТХОДЯЩЕГО (v290): против кайтера раненые уходили и не возвращались, и боя не было; когда его армия
    // отходит (`enemyRetreating`), добивать некому — выведенные возвращаются, новые не выходят
    val back = Memory.stepOutIds.filter { id -> enemyRetreating || live.none { it.id == id && lost(it) >= need(it) } }
    for (id in back) {
        Memory.stepOutIds.remove(id)
        if (id !in Memory.rotByFocus) Memory.rotatingIds.remove(id)
        soutBack.n++
    }
    for (c in live) {
        if (enemyRetreating) break
        if (c.id in Memory.stepOutIds || !canMove(c)) continue
        if (stripped(c)) continue   // раздетый — своя ветка (support: бегство из досягаемости, v123)
        if (lost(c) < need(c) || (c.key) !in reach) continue
        Memory.stepOutIds.add(c.id)
        Memory.rotatingIds.add(c.id)
        Memory.rotateSince[c.id] = getTicks()
        soutOut.n++
    }
    soutTicks.n += Memory.stepOutIds.size
}

/** Цель хода: куда идти, на каком расстоянии встать, обходить ли стоящих врагов, брать ли поле «вблизи» (см. NEAR_FLOW). */
internal class Aim(val target: Position, val standoff: Int, val avoid: Boolean = false, val nearFlow: Boolean = false)

/** Счётчики лестницы цели и цепочки шага (прибор `reach t=`): считает тот, кто решает, печатает `Instruments`. */
internal val ladderTally = Tally("rung")
internal val stepsTally = Tally("step")

/** Причины отказа фактов хода (прибор `whynot t=`, см. [Why]): у факта с четырьмя и более операторами каждый конъюнкт назван в месте
 *  вызова. Строка лестницы, чьё условие — такой факт, называет его своим свойством `why`: общая трасса решения берёт оттуда
 *  первый ложный конъюнкт. */
private val ROT_GATE = Why("rotGate")
private val AGGR = Why("localAggressive")
private val SPOT_NOW = Why("spotNow")
private val GUARD_NOW = Why("guardNow")
private val IN_LINE = Why("inLine")
private val PACK = Why("pack")
private val PRESS_TARGET = Why("pressTarget")
private val HOLD_MELEE = Why("holdMelee")
private val POKER = Why("poker")
private val ENGAGE = Why("engage")
private val LEASHED = Why("leashed")
private val HEAL_MATE = Why("healMate")
private val MASS_KITE = Why("massKite")
private val ALONE_IN_FIRE = Why("aloneInFire")
private val FORMING = Why("forming")
private val MUST_FLEE = Why("mustFlee")
private val GROUPED = Why("grouped")
private val COHESION_HOLD = Why("cohesionHold")
private val RETREAT_HOLD = Why("retreatHold")

private var ladderRows: List<Row<Turn, Aim>>? = null

/**
 * ЛЕСТНИЦА ЦЕЛИ: 27 ступеней, порядок списка = приоритет. Строка — лямбды с получателем [Turn]: голое имя — факт хода, `t.` —
 * величина тика ([ArmyTick]), остальное — член `PainAndGain` или верх пакета. Собирается один раз, внутри функции-расширения:
 * инициализатор верхнего уровня с `with(PainAndGain)` дал бы модулю тактика импорт объекта-оркестратора (ребро вверх), а
 * взаимные инициализаторы верхнего уровня в Kotlin/JS дают `undefined`.
 * ПЕРЕПИСЬ РЕШЕНИЙ (v203, этап 1): каждая ветка обеих цепочек называет себя, и счётчик копится за матч.
 */
internal fun ladder(): List<Row<Turn, Aim>> = ladderRows ?: listOf<Row<Turn, Aim>>(
    Row("keeper", { keeper }) { Aim(InfluenceMap.cell(creep.x, creep.y), 0) },
    Row("slotHold", { slotHold }) { Aim(InfluenceMap.cell(creep.x, creep.y), 0) },
    // приказ командира раньше всего боевого: он уже учёл, кто где встанет и что будет опасно (v137)
    // ...и ЛЕКАРЮ ПРИКАЗ «СТОЯТЬ» — ТОЖЕ ПРИКАЗ (v436, см. USE_COMMANDER_HEALERS_IN_CONTACT): раздача разрешает свою
    // клетку («стой»), но здесь такой приказ молча проваливался ниже, к healMate, и лекарь уходил с клетки, которую
    // командир оценил лучшей; у бойцов условие не тронуто
    Row("order", { Orders.commandOf[creep.id] != null && ((USE_COMMANDER_HEALERS_IN_CONTACT && healer) || Orders.commandOf[creep.id]!!.x != creep.x || Orders.commandOf[creep.id]!!.y != creep.y) }) { Aim(Orders.commandOf[creep.id]!!, 0) },
    // ПОГОНЯ ЗА ОСТОВОМ (v212) — сразу под приказом командира: пока он правит, клетку даёт он (и тоже
    // знает про погоню, см. chaseTarget в placeScored); когда молчит, преследователь идёт сюда. Ниже
    // кайта и строя стоять нельзя: обе ветки увели бы его обратно в кулак, а весь смысл отряда в том,
    // чтобы из кулака выйти
    Row("chase", { Squads.chaseTarget[creep.id]?.let { it.hits > 0 } == true }) { Aim(InfluenceMap.cell(Squads.chaseTarget[creep.id]!!.x, Squads.chaseTarget[creep.id]!!.y), if (hasRanged(creep)) RANGED_RANGE - 1 else 0, avoid = true) },
    // кайт раньше слота: слот ставит нас в строй, а строй сходится с блобом вплотную (v135)
    // дистанция кайта зависит от того, выгоден ли ему ВЕЕР: масс-атака бьёт в радиусе трёх (10/4/1 за часть),
    // поэтому в куче держим три — там веер стоит ему шестёрки урона вместо шестидесяти, — а поодиночке два,
    // где наш собственный огонь плотнее. MetalicaX#11 даёт 63 веера за матч против 37 у #10 и наших 12 (v135)
    // пока стволы не подтянулись — держим на клетку дальше и в бой не входим (v135, USE_KITE_UNTIL_READY)
    Row("kite", { massKite != null }, why = MASS_KITE) { kiteNow.n++; Aim(massKite!!, KITE_STANDOFF, avoid = true, nearFlow = true) },
    // ЛЕКАРЬ ВЫШЕ СТРОЯ В БОЮ (v147): замер по записи (6aa078c0, обе стороны по три лекаря) — у него в
    // дальности лечения стоят ВСЕ ТРИ каждый тик, у нас 1,8 из трёх, и лечение выходит 1584 против 9624.
    // Причина в порядке цепочки: слот стоял выше подопечного, а расстановка не знает, кого лечить, и уводила
    // лекаря в строй за пределы дальности. Лекарь вне HEAL_RANGE не лечит вовсе — в бою подопечный главнее
    // СТЕНА ЛЕЧЕНИЯ (v228, см. USE_HEAL_WALL): назначенная клетка стены — как слот, вплотную к удержимой жертве
    Row("wall", { healer && Wall.victimSaveable && Wall.wallCellOf[creep.id] != null }) { Aim(Wall.wallCellOf[creep.id]!!, 0) },
    // ЛЕКАРЬ ПРИ МИЛИ (v235, см. USE_HEALER_AT_MELEE): клетка при своём фронтовом мили с тыла — как слот
    Row("healMate", { healer && healMate != null && t.meas.fight.contact }, why = HEAL_MATE) { Aim(healMate!!, 1, nearFlow = true) },
    Row("slot", { slot != null }) { Aim(slot!!, 0) },
    // лекарь и в отходе идёт за подопечным (лечение — в тот же тик, что и шаг, 216 в тик восстанавливают
    // обломок за шесть тиков): прежде лекари шли к точке отхода сами, а раненые — врассыпную
    Row("healMateOut", { healer && healMate != null }, why = HEAL_MATE) { Aim(healMate!!, 1, nearFlow = true) },
    // отход — по обычному полю: поле «в обход» стоящих врагов (а дерущиеся стоят) увело пару в обход
    // стенного блока на другой край карты (матч 4)
    Row("evade", { posture == Posture.EVADE && t.strat.obj.evadeTo != null }) { Aim(t.strat.obj.evadeTo!!, 1) },
    Row("retreat", { posture == Posture.RETREAT && t.strat.dec.retreatTo != null }) { Aim(t.strat.dec.retreatTo!!, 1) },
    Row("formGo", { formGo }, why = FORMING) { Aim(InfluenceMap.cell(t.targ.form.formVan!!.x, t.targ.form.formVan!!.y), 1) },
    Row("wounded", { stripped && healerNear != null }) { Aim(healerNear!!, 1, avoid = true, nearFlow = true) },
    Row("rotate", { rotating && healerNear != null }, why = ROT_GATE) { Aim(healerNear!!, 1, avoid = true, nearFlow = true) },
    // сбор пачки (см. USE_REGROUP, REGROUP_TICKS): одинокий мили под смертельным огнём — к ближайшему мили-напарнику
    Row("regroup", { aloneInFire && melee && meleeMate != null && InfluenceMap.damageAt(creep.x, creep.y, t.meas.forces.combatEnemies) * REGROUP_TICKS >= creep.hits }, why = ALONE_IN_FIRE) { Aim(meleeMate!!, 1, avoid = true, nearFlow = true) },
    Row("alone", { aloneInFire }, why = ALONE_IN_FIRE) { Aim(t.targ.takers.armedCentroid, CLOSE_STANDOFF, avoid = true, nearFlow = true) },
    Row("leash", { leashed }, why = LEASHED) { Aim(t.targ.takers.armedCentroid, CLOSE_STANDOFF, avoid = true, nearFlow = true) },
    Row("engage", { engage != null }, RowMark.OPPORTUNITY, why = ENGAGE) { Aim(engage!!, if (melee) 1 else closeIn, nearFlow = true) },
    // мили держит линию (см. MELEE_HOLD_RANGE): что подошло на две клетки — рубит, за экраном не гонится
    Row("holdMelee", { holdMelee }, RowMark.OPPORTUNITY, why = HOLD_MELEE) { Aim(InfluenceMap.cell(creep.x, creep.y), 0) },
    Row("grab", { grab != null }) { Aim(grab!!.pos, 0, avoid = true) },
    // добивание без местного перевеса — отход к массе армии, а не бросок на «ближайшую добычу»; без
    // ловимой добычи (кайтеры) — тоже к массе: стоим строем и стреляем в то, что подойдёт
    Row("toCentroid", { posture == Posture.ANNIHILATE && !support && (!localAggressive || t.targ.quarry.prey == null) }) { Aim(t.targ.takers.armedCentroid, CLOSE_STANDOFF, avoid = true) },
    Row("prey", { t.targ.quarry.prey != null }, RowMark.OPPORTUNITY) { Aim(t.targ.quarry.prey!!, if (melee) 1 else closeIn, nearFlow = true) },
    Row("rally", { rallyTo != null }) { Aim(rallyTo!!, CLOSE_STANDOFF, avoid = true) },
    Row("objective", { t.strat.obj.objective != null }) { Aim(t.strat.obj.objective!!.flag.pos, if (t.targ.takers.objectiveCapturer == creep.id) 0 else CLOSE_STANDOFF, avoid = true) },
    Row("threat", { t.strat.threats.threat != null && huntingThreat && mobile }, RowMark.OPPORTUNITY) { Aim(t.strat.threats.threat!!, if (melee) 1 else closeIn) },
    Row("raider", { t.strat.threats.raider != null && mobile && !support }, RowMark.OPPORTUNITY) { Aim(t.strat.threats.raider!!, if (melee) 1 else RANGED_RANGE) },
    // ОТВЕРГНУТО стендом (v42): «держать линию там, где она стоит» (holdLine → armedCentroid вместо поста) — в матче 70
    // пост при враге рядом был точкой в 35 клетках позади, и каждый тик ДЕРЖАТЬ между тиками ДОБИТЬ разворачивал
    // армию к нему. Но возврат к посту делает работу в десятках сценариев (после отбитого рывка остаток добивается у
    // поста): 58 строк хуже / 48 лучше, гейтовая m33 farm+weak и m29 farm красные. Мигание лечится у корня — см.
    // chaseVeto и evasive
    Row("post", { true }) { Aim(t.strat.dec.post, POST_STANDOFF, avoid = true) },
).also { ladderRows = it }

/**
 * ФАКТЫ ОДНОГО ХОДА (v444, план архитектуры, 4.3 и этап 3): то, что крип знает о себе и о соседях ДО выбора цели. Каждая
 * величина определена один раз — в [buildTurn], рядом со своим комментарием-вердиктом, в прежнем порядке вычислений; здесь —
 * только имена и типы тех, что читают таблицы (лестница [LADDER], цепочка шага) и хвост хода. Имена полей не пересекаются с
 * полями [ArmyTick], [Ctx] и членами `PainAndGain` — это держит линт: строки таблиц — лямбды с получателем `Turn`, и поле,
 * совпавшее по имени с величиной тика, молча поменяло бы смысл условия. `givenUp`, `covered`, `holdReach` — прежние
 * локальные функции хода (замыкания над его фактами): их зовёт трассировка простоя мили.
 *
 * НОСИТЕЛЬ (v456, второй шаг архитектуры, 4.1 и этап 3): класс И ЕСТЬ прежний построитель `buildTurn` — его тело дословно и в
 * прежнем порядке. Поле объявлено там, где вычислено (до v456 имя писалось трижды: в заголовке класса, локальной построителя и
 * аргументом `x = x`); порядок текста — порядок инициализации; записи защёлок, счётчики приборов и печать `rot t=` стоят блоками
 * `init` на своих местах последовательности; прежние локальные функции (`guards`, `givenUp`, `pressCovered`, `covered`,
 * `mateNear`, `holdReach`) — методы на своих местах, и метод не читает поле, объявленное ниже него (держит линт). То, что читают
 * таблицы и хвост хода, — открытые поля, остальное `private`. Величины тика — приватные `meas` / `strat` / `targ` /
 * `stanceOut`; то, что ещё остаётся членом синглтона, читается явно — `t.pag.victimSaveable` (см. [ArmyTick.pag]).
 */
internal class Turn(val creep: Creep, val ctx: Ctx, val t: ArmyTick) {
    private val meas = t.meas
    private val strat = t.strat
    private val targ = t.targ
    private val stanceOut = t.stanceOut

    val mobile = meas.forces.strikers.any { it.id == creep.id }
    val healer = healerOnly(creep)
    private val slot0 = targ.zones.slotOf[creep.id]
    val keeper = creep.id in Squads.keeperIds
    // раненый (без оружия и лечения, в армии по решению выше): ходит за ближайшим лекарем, в строй не входит
    val stripped = unitOf(creep).stripped
    // ротация (см. ROTATE_OUT): с гистерезисом, чтобы боец не дёргался у порога
    // ...и ротация по его фокусу (v275, см. rotateByFocus) решена до командира и старым порогом не снимается
    val stepOut = creep.id in Memory.stepOutIds
    // защёлка ротации по оружию обновляется ОПЕРАТОРОМ (v443, этап 2) — и только у того, до кого дошла бы прежняя цепочка
    // `||` / `&&`: не в ротации по фокусу, не выходит из строя, вооружённый не-лекарь при живых лекарях
    private val rotGate = ROT_GATE.c("notByFocus", creep.id !in Memory.rotByFocus) && ROT_GATE.c("notStepOut", !stepOut) && ROT_GATE.c("notHealer", !healer) && ROT_GATE.c("armed", hasWeapon(creep)) && ROT_GATE.c("healersAlive", targ.zones.healersAlive)
    // прибор `rotset=` (v463, дефект 2): крипо-тики живого бойца в `rotatingIds` — до обновления защёлки; часть 0 — размер набора на печати
    init { if (creep.id in Memory.rotatingIds) rotSetTicks.n++ }
    init {
        if (rotGate) {
            val weapons = creep.body.count { it.type == ATTACK || it.type == RANGED_ATTACK }
            val live = creep.body.count { (it.type == ATTACK || it.type == RANGED_ATTACK) && it.hits > 0 }
            val frac = if (weapons == 0) 1.0 else live.toDouble() / weapons
            // возврат на одну часть ВЫШЕ порога выхода (v126, USE_ROTATE_IN_ONE_PART): 0.9 от восьми частей ATTACK — все восемь, то есть
            // полное лечение блока; под огнём у фронта оно не наступает, и мили висит в ротации 50–97 тиков при 1050–1250 хитах
            // (матч 506, melee_4 116–212; 602, melee_3 314–372) — ноль ударов. Его мили (матч 14) вернулся при 5 из 8
            val backIn = live >= kotlin.math.ceil(weapons * ROTATE_OUT).toInt() + 1
            val wasOut = creep.id in Memory.rotatingLatch
            val isOut = Memory.rotatingLatch.update(creep.id, enter = frac < ROTATE_OUT, exit = backIn)
            if (wasOut && !isOut && DEBUG_LOG) println("rot t=${meas.exchange.now} in ${creep.id} frac=$frac took=${meas.exchange.now - (Memory.rotateSince[creep.id] ?: meas.exchange.now)}")
            if (!wasOut && isOut) { Memory.rotateSince[creep.id] = meas.exchange.now; rotOut.n++; if (DEBUG_LOG) println("rot t=${meas.exchange.now} out ${creep.id} frac=$frac hits=${creep.hits}") }
        } else if (creep.id in Memory.rotatingLatch && creep.id !in Memory.rotByFocus && !stepOut) {
            // ЗАЛИПШАЯ ЗАЩЁЛКА (v463, дефект 2): защёлка обновляется только под rotGate. Закрылся гейт у бойца, уже стоящего в
            // `rotatingIds` по её пути, и выхода нет: тактик его ротирующим уже не считает (`rotating` ниже требует rotGate), а
            // мир читает множество напрямую — lineMelees, lineRangeds, raceCapable. Закрытий у этого пути два, и они разные:
            //  - `armed` (потерял оружие): пока раздет, запись дублирует фильтры мира (они считают ЖИВЫЕ части), а при излечении
            //    гейт откроется и защёлка продолжит сама (доля оружия ниже порога — остаётся в ротации, выше backIn — выйдет).
            //    Вынужденный выход здесь не чинит ничего, а добавляет однотиковый артефакт: строй читает множество ДО хода бойца,
            //    и вылеченный на тик излечения попадал бы в линию (как всякий свежий вход) — гейт v463 показал это пятью
            //    разошедшимися сценариями без единого выигрыша. Запись остаётся, крипо-тики считает часть 0 прибора `rotstuck=`;
            //  - `healersAlive` (погибли все лекари): гейт не откроется никогда, боец с живым оружием до конца матча стоял вне
            //    линии и вне гонки. Выход — здесь же, в тот же тик; часть 1 прибора считает такие выходы
            if (targ.zones.healersAlive) rotStuck.n++
            else { rotStuckNoHeal.n++; Memory.rotatingIds.remove(creep.id) }
        }
    }
    val rotating = creep.id in Memory.rotByFocus || stepOut || (rotGate && creep.id in Memory.rotatingLatch)
    val support = healer || stripped
    val nearestEnemyRange = meas.forces.combatEnemies.minOfOrNull { getRange(creep, it) } ?: 99
    val localAllies = strat.inp.combatArmy.filter { getRange(creep, it) <= (if (posture == Posture.ANNIHILATE || posture == Posture.FLAG) ENGAGE_RANGE else RANGED_RANGE + 1) }
    val localEnemies = meas.forces.combatEnemies.filter { getRange(creep, it) <= ENGAGE_RANGE + RANGED_RANGE }
    val ratio = if (creep.id in Memory.aggressiveIds) LOCAL_ENTER_RATIO else PUSH_RATIO
    val ghost = run {
        val prev = Memory.lastHits[creep.id]
        val cell = Memory.lastCell[creep.id]
        if (prev == null || cell == null) 0 else {
            val lost = prev - creep.hits
            var explained = 0.0
            val taken = InfluenceMap.takenOf(creep)
            for (s in FireBook.prevShooters) {
                val d = maxOf(abs(s.cell / 100 - cell / 100), abs(s.cell % 100 - cell % 100))
                if (d <= RANGED_RANGE) explained += s.ranged * taken
                if (d <= 1) explained += s.melee * taken
            }
            if (lost > explained + 1.0) lost else 0
        }
    }
    init {
        if (ghost > 0 && DEBUG_LOG && getTicks() - (TacticianState.ghostLogged[creep.id] ?: -100) >= 10) {
            TacticianState.ghostLogged[creep.id] = getTicks()
            val nearest = meas.forces.combatEnemies.minOfOrNull { getRange(creep, it) } ?: -1
            println("ghost damage t=${getTicks()}: ${creep.id} -$ghost at (${creep.x},${creep.y}) hits=${creep.hits} nearestCombat=$nearest — источник не виден")
        }
    }
    // локальный перевес: бойцы, способные стрелять по той же цели через тик-другой, против врагов в их
    // досягаемости; цена боя — по ГРУППЕ (самый большой запас хода), в контакте цена больше не гейт
    val localAggressive = when {
        posture.withdrawing -> AGGR.c("withdraw.contact", inContact(localEnemies, localAllies)) && AGGR.c("withdraw.power", ourPowerOf(localAllies, localEnemies) >= enemyPowerOf(localEnemies, localAllies) * ratio)
        // добивание: армия в целом сильнее (или в контакте без отхода), но ЯВНО слабейшая на месте группа
        // отходит к массе — «всегда агрессивен» посылал четверых на двенадцать (матч 2 на стенде). Вход в
        // бой при 0.9 (при равных силах никто не вступал в бой — рывок врага кончался ничьёй), выход — только
        // при разгроме на месте (ANNIHILATE_HOLD_RATIO): в гуще боя местный счёт скачет, и бойцы по одному
        // «отходили к центру» и гибли поодиночке (стенд rush 7:2 против прежних 0:12)
        // отходить к массе есть смысл, только если масса не здесь: когда рядом больше половины армии, это и
        // есть масса, и «отход к центру» был шагом на месте под ударами (матч 3, t=140–280: армия из
        // семи-восьми «отходила к центру» сто сорок тиков и потеряла всех по одному, не стреляя в ответ)
        posture == Posture.ANNIHILATE -> AGGR.c("annihilate.noFoeOrMassOrPower", localEnemies.isEmpty() || localAllies.size * 2 >= strat.inp.combatArmy.size ||
            ourPowerReach(localAllies, localEnemies) >= enemyPowerReach(localEnemies, localAllies) * (if (creep.id in Memory.aggressiveIds) ANNIHILATE_HOLD_RATIO else LOCAL_ENTER_RATIO))
        localEnemies.isEmpty() -> true
        // без боевого своего в четырёх клетках (раненый один) запаса хода нет: maxOf пустого списка бросал
        // NoSuchElementException КАЖДЫЙ тик до конца матча — армия стояла 1500 тиков после выигранного боя
        // (стенд m19 nine, v30; в v29 то же падало в m8 rush t=115–120 и m28 wing t=1200, стенд этого не считал)
        else -> AGGR.c("power", ourPowerOf(localAllies, localEnemies) >= enemyPowerOf(localEnemies, localAllies) * ratio) &&
            AGGR.c("affordableOrContact", fightCost(localEnemies, localAllies) <= (localAllies.maxOfOrNull { speedSlack(it) } ?: 0) || inContact(localEnemies, localAllies))
    }
    init { Memory.aggressiveLatch.set(creep.id, localAggressive) }
    // ОТПЕЧАТОК РАСХОЖДЕНИЯ МАСШТАБОВ (этап 0): знаменатель — мили с боевым врагом в ENGAGE_RANGE,
    // числитель — из них те, где МЕСТНАЯ арифметика в клетке врага даёт перевес не ниже PUSH_RATIO, а
    // армейская мера при этом говорит «не наступать». Ненулевой числитель и есть наблюдение оператора
    // «трое наших мили боялись подойти к одному чужому», выраженное числом
    init {
        if (meleeOnlyBorn(creep)) {
            val near = localEnemies.filter { getRange(creep, it) <= ENGAGE_RANGE }
            if (near.isNotEmpty()) {
                edgeAll.n++
                if (!localAggressive && near.any { spotEdgeAt(it) >= PUSH_RATIO }) edgeSpot.n++
            }
        }
    }
    // бросок — только на врага «с боем» (см. threatening): одинокий лекарь врага в восьми клетках был целью бойца,
    // который ждал по сплочению группу, а группа ждала его как отставшего на пути к флагу — 1500 тиков (m1 rush)
    // бросок — только в строю: построение готово и не меньше двух вооружённых в FORM_RANGE; одиночный мили, ушедший
    // вперёд на стрелков врага, погиб за восемь тиков, пока остальные держали построение (матч 14, t=71–78)
    // в бою — в строю по факту (см. USE_INLINE_CONTACT_FREE): враг с боем в MELEE_HOLD_RANGE + 1 или под огнём
    // ...первый срез «враг в трёх или в поле огня» ОТВЕРГНУТ: blitz 7-1 → 5-3 (m28 5 890:23 972, m30 5 956:22 619, m33 3 817:24 121) —
    // пикет фермера в трёх снимал ворота, и мили танцевали с ним вместо марша (то, против чего ворота и стоят); второй срез:
    // только крип, КОТОРОГО БЬЮТ (потеря хитов за прошлый тик)
    // МЕСТНЫЙ ПЕРЕВЕС (v214, решение оператора: «локальный перевес снимает требование прикрытия»).
    // Считается полем удара в ЕГО клетке (см. spotEdgeAt), а не мощью армии: наш залп по нему против его
    // залпа по клетке рядом с ним. Порог — существующий PUSH_RATIO = 1.3, нового числа не заводим:
    // у сомкнутого блока dangerAt в его клетке 700–1500 против нашего залпа 200–500, отношение меньше
    // единицы и очаг не возникает; против одиночки 720/240 = 3.0 — возникает.
    // Добиваемость обязательна (killTicks конечен): очаг у трёх его лекарей — не очаг.
    // ⚠️ Это НЕ второе издание отвергнутого USE_INLINE_CONTACT_FREE: тот открывал ворота «врагом в трёх»,
    // и одиночный пикет фермера снимал их (blitz 7-1 -> 5-3). Здесь пикет даёт 240/240 = 1.0 и ворот
    // не открывает — открывает только настоящий численный перевес.
    // ...и ТОЛЬКО С ЖИВОЙ ATTACK (v452, пункт Д оператора; таблица А/Б в абзаце v442): местный перевес пускает мили ВПЕРЁД
    // БИТЬ, а раздетому бить нечем — он шёл вперёд по перевесу и не наносил ничего. Написание Б = meleeOnlyLive
    val spotNow =  SPOT_NOW.c("meleeLive", meleeOnlyLive(creep)) && SPOT_NOW.c("combatant", !support) && SPOT_NOW.c("notRotating", !rotating) &&
        SPOT_NOW.c("edgeOnSpot", localEnemies.any { e -> getRange(creep, e) <= ENGAGE_RANGE && spotEdgeAt(e) >= PUSH_RATIO && !targ.focus.killTicks(e).isInfinite() })
    init {
        if (spotNow) spotMeleeTicks.n++
        // МИЛИ ЗАЩИЩАЕТ СВОЕГО (v220, см. USE_MELEE_GUARDS_LINE). Ворота `inLine` требуют двух ВООРУЖЁННЫХ
    }
    // своих в FORM_RANGE = 2 — и это спираль: его мили раздевает наших стрелков, раздетый перестаёт быть
    // `hasWeapon`, ворота закрываются, наши мили перестают драться, и он раздевает следующего. Разбор
    // реплеев v219 показывает её прямо: в трёх разгромах трассировка простоя мили даёт `!inLine` 20/22/3
    // и `!covered` 20/15/6 крипо-тиков, а его мили стояли вплотную 53–73 крипо-тика против наших 14–18 —
    // «его мили нашли цели, наши держали линию, которую никто не атаковал».
    // Оговорка узкая и НЕ повторяет отвергнутое «враг в трёх снимает ворота» (blitz 7-1 -> 5-3, пикет
    // фермера снимал их и мили танцевали с пикетом): здесь ворота открывает не близость врага к НАМ, а
    // то, что его вооружённый мили УЖЕ дотянулся до нашего небоевого — стрелка, лекаря или раздетого.
    // Пикет одиночки такого не делает, а блоб делает первым же тиком
    // ...а КОГО защищаем — по живой ATTACK (v472, дефект 11 постановки, он же вопрос 1 после Д): «мягкий» — тот, кто ответить
    // не может; раздетый мили ответить не может, и его мили, рубящий нашего раздетого, открывает ворота так же, как рубящий
    // стрелка. До v472 стояло `!meleeOnlyBorn(a)`, и раздетый мили своим мягким не считался (846 крипо-тиков `mstrip=` на гейте)
    private fun guards(e: Creep) = InfluenceMap.profileOf(e).melee > 0.0 &&
        strat.inp.combatArmy.any { a -> a.id != creep.id && !meleeOnlyLive(a) && getRange(e, a) <= MELEE_KEEP_RANGE }
    // ПРИБОР ПРАВКИ v472 (v473): те же ворота по прежнему написанию А — крипо-тики, где ворота открыл ТОЛЬКО раздетый мили
    // (по Б открыты, по А закрыты), считаются в `gstrip=`; живая серия v472 (2-6) блоком не различает, различает это число
    private fun guardsBorn(e: Creep) = InfluenceMap.profileOf(e).melee > 0.0 &&
        strat.inp.combatArmy.any { a -> a.id != creep.id && !meleeOnlyBorn(a) && getRange(e, a) <= MELEE_KEEP_RANGE }
    // защита своего — работа ТЕЛОМ, остаётся написание А (рождённый мили): встать между его мили и своим мягким может и
    // раздетый (v452, пункт Д — разбиение оператора: тело — А, урон — Б)
    private val guardNow =  GUARD_NOW.c("meleeBorn", meleeOnlyBorn(creep)) && GUARD_NOW.c("combatant", !support) && GUARD_NOW.c("notRotating", !rotating) &&
        GUARD_NOW.c("foeToGuardFrom", localEnemies.any { e -> getRange(creep, e) <= ENGAGE_RANGE && guards(e) })
    init { if (meleeOnlyBorn(creep) && localEnemies.isNotEmpty()) { guardTicks.n++; if (guardNow) guardFired.n++ } }
    init { if (guardNow && localEnemies.none { e -> getRange(creep, e) <= ENGAGE_RANGE && guardsBorn(e) }) gstripGuard.n++ }
    val inLine =  IN_LINE.c("spotting", spotNow) || IN_LINE.c("guarding", guardNow) || (IN_LINE.c("formationReady", targ.form.formationReady) && IN_LINE.c("twoMatesInForm", strat.inp.combatArmy.count { it.id != creep.id && hasWeapon(it) && getRange(creep, it) <= FORM_RANGE } >= 2))
    // при бесплодной охоте (см. STALL_TICKS) броска нет: висящие крипы россыпи «ловимы» (не уходят стабильно), и
    // каждый наш крип танцевал со своим соседом вместо марша к флагу-цели (стенд m19 spread, travel=23 четыреста тиков)
    // «держит линию» — про мили В ЛИНИИ, а не про любого мили в бою: без этого условия мили, до которого враг
    // ещё не дошёл, стоял на месте весь бой. Матч 24: двое из четырёх простояли в 4–6 клетках от схватки в полном
    // здравии (M8A8 1600 на 140-м тике), пока двое дрались и армия гибла — 14:1 при равной мощи
    // прижим (см. USE_PRESS): мили в пачке — вплотную к цели фокуса в PRESS_RANGE, иначе к ближайшему ловимому врагу с
    // боем; стрелок — в кольцо ровно в RANGED_RANGE от цели фокуса
    private val pack = PACK.c("pressOn", stanceOut.press.pressOn) && PACK.c("meleeLive", meleeOnlyLive(creep)) && PACK.c("notRotating", !rotating) && PACK.c("aggr", localAggressive) &&
        PACK.c("mateInPackOrFoeAdjacent", strat.inp.combatArmy.any { it.id != creep.id && meleeOnlyLive(it) && getRange(creep, it) <= PRESS_PACK } ||
            localEnemies.any { getRange(creep, it) <= 1 })
    // отказ (см. PRESS_GIVEUP) действует, пока цель не вернулась в три (v111, USE_GIVEUP_RETURNS), и снимается,
    // когда цель стоит ВПЛОТНУЮ к нашему не-мили (v135, см. USE_GIVEUP_LIFTS_ON_BACK): отказ — про погоню, а
    // враг у нашего лекаря никуда не бежит
    fun givenUp(e: Creep) = e.id in StrategistState.pressGiveUp && !(getRange(creep, e) <= MELEE_HOLD_RANGE + 1) 
    // прижим — только под прикрытием стрелков (v122, USE_PRESS_COVER): та же мера, что у броска (см. covered, v55) —
    // MELEE_COVER стрелков в RANGED_RANGE + 1 от цели или свой вплотную к ней; иначе мили прижимают шагающую назад линию
    // в одиночку под её огонь (серия 307–326: けろびー#1 дважды за 240 тиков, наш мили 1600 → 752 за 14 тиков погони,
    // стрелки в двух за мили не достают — 44 выстрела против 70)
    private fun pressCovered(e: Creep) = 
        strat.inp.combatArmy.count { it.id != creep.id && hasRanged(it) && getRange(it, e) <= RANGED_RANGE + 1 } >= MELEE_COVER ||
        ctx.army.any { a -> a.id != creep.id && getRange(e, a) <= 1 }
    val pressTarget: Creep? = if (!PRESS_TARGET.c("pack", pack)) null else
        targ.focus.focusTarget?.takeIf { PRESS_TARGET.c("focus.inRange", getRange(creep, it) <= PRESS_RANGE) && PRESS_TARGET.c("focus.catchable", catchable(it, meas.chase.chasers)) && PRESS_TARGET.c("focus.notGivenUp", !givenUp(it)) && PRESS_TARGET.c("focus.covered", pressCovered(it)) }
            ?: localEnemies.filter { PRESS_TARGET.c("inRange", getRange(creep, it) <= PRESS_RANGE) && PRESS_TARGET.c("catchable", catchable(it, meas.chase.chasers)) && PRESS_TARGET.c("threatening", threatening(it, meas.forces.enemyCreeps)) && PRESS_TARGET.c("notGivenUp", !givenUp(it)) && PRESS_TARGET.c("covered", pressCovered(it)) }.minByOrNull { getRange(creep, it) }
    // против СОМКНУТОГО блоба мили не бросается (v135, см. USE_MELEE_HOLD_VS_MASS): наш мили, вошедший в двенадцать,
    // получает около 750 в тик (пять стрелков и четыре мили достают его) и живёт два-три тика — за матч наши мили
    // живут 50 крипо-тиков против его 664 и бьют 14 раз против 85. Держим линию и бьём то, что подошло; poker
    // (защита тыла) по-прежнему выше и работает
    // ...И НЕ ДЕРЖИМ ЛИНИЮ, КОГДА РЯДОМ ПЕРЕВЕС (v214): здесь мили получал цель «своя клетка», то есть
    // буквально стоял, как только любой враг оказывался в трёх клетках. Замер по 12 живым матчам: доля
    // касаний наших мили в затяжном бою 1,4 %, то есть четверо из четырнадцати не бьют вовсе
    // держать линию — работа ТЕЛОМ, остаётся написание А: линию держит и раздетый мили (v452, пункт Д — разбиение оператора)
    val holdMelee =  (HOLD_MELEE.c("meleeBorn", meleeOnlyBorn(creep)) && HOLD_MELEE.c("annihilate", posture == Posture.ANNIHILATE) && HOLD_MELEE.c("notPushing", !pushing) && HOLD_MELEE.c("contact", meas.fight.contact) && HOLD_MELEE.c("noPressTarget", pressTarget == null) &&
        HOLD_MELEE.c("notSpot", !spotNow) && HOLD_MELEE.c("foeAtHoldRange", localEnemies.any { getRange(creep, it) <= MELEE_HOLD_RANGE + 1 }))
    // прилипший (v43): его вооружённый мили ВПЛОТНУЮ к нашему стрелку, лекарю или раненому — цель ближайшего нашего мили в
    // ENGAGE_RANGE, поверх «держать линию в двух». Матч 73 (Coldkimchi): его мили подходили к нашим стрелкам и лекарям,
    // били по 240 и отходили — 46 ударов (11 тыс. урона) против наших 7, наши мили держали линию в 2–3 от его линии и не
    // доставали; стрельба при трёх лекарях с обеих сторон вылечена целиком, армия потеряна к 240-му при его 16000/16000
    // ...и ротирующий защищает тыл (v135, см. USE_POKER_WHILE_ROTATING): ротация значит «оружия меньше половины, иду
    // лечиться», но три живых ATTACK из восьми — это 90 урона в удар, а лекарь, к которому он идёт, — тот самый,
    // которого рубят. Ротация не отменяет poker, пока у бойца есть чем ударить
    // ...и ТОЛЬКО С ЖИВОЙ ATTACK (v452, пункт Д): «бьёт того, кто ткнулся в наших мягких» — без живой ATTACK он идёт к цели и
    // ударить нечем; сама ротация poker не отменяет (см. выше), отменяет пустое оружие. Написание Б = meleeOnlyLive
    private val poker: Creep? = if (POKER.c("meleeLive", meleeOnlyLive(creep)) && POKER.c("combatant", !support) && POKER.c("notRotating", (!rotating)) && POKER.c("notStalled", !meas.chase.stalled)) meas.forces.combatEnemies.filter { e ->
        POKER.c("foe.melee", InfluenceMap.profileOf(e).melee > 0.0) && POKER.c("foe.inRange", getRange(creep, e) <= ENGAGE_RANGE) && POKER.c("foe.notGivenUp", !givenUp(e)) &&
            // ...и «наш мягкий» здесь — по живой ATTACK, как у `guards` (v472, дефект 11): раздетого мили, в которого ткнулись, тоже бьём
            POKER.c("foe.atOurNonMelee", ctx.army.any { a -> a.id != creep.id && !meleeOnlyLive(a) && getRange(e, a) <= 1 })
    }.let { c ->
        // защита своего — тоже одной целью на всех (v221, см. USE_MELEE_PACK): цель пачки, если она среди них
        c.minByOrNull { getRange(creep, it) } } else null
    // прибор правки v472 (v473, см. guardsBorn): цель `poker` есть только благодаря раздетому мили — по А её не было бы
    init {
        if (poker != null && meas.forces.combatEnemies.none { e ->
                InfluenceMap.profileOf(e).melee > 0.0 && getRange(creep, e) <= ENGAGE_RANGE && !givenUp(e) &&
                    ctx.army.any { a -> a.id != creep.id && !meleeOnlyBorn(a) && getRange(e, a) <= 1 } }) gstripPoker.n++
    }
    // прикрытие (v55): мили бросается на цель в ENGAGE_RANGE, только если её достают наши стрелки — не меньше MELEE_COVER
    // стрелков в RANGED_RANGE + 1 от неё — или она вплотную к кому-то из наших. Матч 115 (stachu3478, битый до того
    // девятнадцать раз): его одиночный стрелок подошёл на 2–6 клеток к нашим мили и отступал по клетке в тик; трое мили
    // без врага в трёх (линию не держат) шли за ним на 5–6 клеток впереди своих стрелков в его блок из четырёх мили и
    // четырёх стрелков — 1200 хитов за два тика (t=86–87), трое из четырёх мили мертвы к 100-му при равной силе на 77-м;
    // «под огнём без двух вплотную — назад» срабатывал уже под ударами. В выигранном за пять минут до того матче его
    // мили сами шли в досягаемость наших стрелков и там гибли. Гейт 125/125 при 19 лучше / 24 хуже (правило боя
    // всегда перемешивает 20–30 строк: v52c 29/20, v53 29/30; правки вне боя — 0/0). Два сужения ОТВЕРГНУТЫ стендом:
    // «только цель в блоке» (не меньше двух других вооружённых в RANGED_RANGE от неё) — 124/125, 13 лучше / 24 хуже,
    // m33 farm+weak красная; «только без толчка» (pushing) — 123/125, 10 лучше / 19 хуже, m28 farm+weak и m29 camp
    // красные. Строки «стёрт → лидируем» в rush-сценариях — не это правило: там последний крип (лекарь, равная
    // скорость) уходит от погони при любом варианте, бой окончен на 94-м
    // ...и ПЕРЕВЕС НАД ЭТОЙ ЦЕЛЬЮ снимает требование прикрытия (v214, решение оператора). Порог берётся по
    // КОНКРЕТНОЙ цели, а не по spotNow: перевес над одним не должен открывать бросок на другого
    fun covered(e: Creep) =  holdMelee || (spotEdgeAt(e) >= PUSH_RATIO) ||
        (guards(e)) ||
        strat.inp.combatArmy.count { it.id != creep.id && hasRanged(it) && getRange(it, e) <= RANGED_RANGE + 1 } >= MELEE_COVER ||
        ctx.army.any { a -> a.id != creep.id && getRange(e, a) <= 1 }
    // ОДНА ДОБЫЧА НА ВСЕХ в толчке (v71): бросок — только на цель в ENGAGE_RANGE от добычи армии (prey — ближайший к центру по
    // полю). Стенд camp+shy (застенчивый лагерь матчей 133/152/159: отходит от наших в шести и возвращается на флаг): его
    // блоб рассыпался вокруг армии, мили брали одну цель в (34,39), стрелки другую в (46,55), плотность держала всех у
    // центра между ними — 800 тиков pushing=true, huntable 12/12, `step=stay` у всех, 9615:23822 (m30). Живьём — толчок к
    // стоящему блобу, который не сближается (матчи 70, 133, 152, 159)
    // мили входит парой (v101, USE_MELEE_PAIR_ENGAGE): к цели в досягаемости — другой наш мили в MELEE_HOLD_RANGE + 1 от неё
    val meleeOnly = meleeOnlyBorn(creep)
    private fun mateNear(e: Creep, r: Int) = strat.inp.combatArmy.any { m -> m.id != creep.id && meleeOnlyLive(m) && getRange(m, e) <= r }
    // ...и присоединяется к напарнику, уже стоящему в MELEE_HOLD_RANGE от цели: досягаемость на клетку больше
    fun holdReach(e: Creep) = if (meleeOnly && mateNear(e, MELEE_HOLD_RANGE)) MELEE_HOLD_RANGE + 1 else MELEE_HOLD_RANGE
    val engage = if (pressTarget != null) pressTarget else poker ?: if (ENGAGE.c("aggr", localAggressive || spotNow) && ENGAGE.c("combatant", !support) && ENGAGE.c("inLine", inLine) && ENGAGE.c("notRotating", !rotating) && ENGAGE.c("notStalled", !meas.chase.stalled)) meas.forces.combatEnemies.filter { ENGAGE.c("foe.inReach", getRange(creep, it) <= (if (holdMelee) holdReach(it) else ENGAGE_RANGE)) && ENGAGE.c("foe.catchable", catchable(it, meas.chase.chasers)) && ENGAGE.c("foe.threatening", threatening(it, meas.forces.enemyCreeps)) && ENGAGE.c("foe.notGivenUp", !givenUp(it)) && ENGAGE.c("foe.covered", !meleeOnlyBorn(creep) || covered(it))  }.let { c ->
        // ОДНА ЦЕЛЬ НА ВСЕХ МИЛИ (v221, см. USE_MELEE_PACK): цель пачки, если она среди допустимых этому мили,
        // иначе прежний ближайший — пачка ничего не запрещает, она только выбирает
        c.minByOrNull { getRange(creep, it) } } else null
    init { Memory.engagingLatch.set(creep.id, engage != null) }
    // пара к общей цели мили (v221, см. mpackHit): как часто ноги мили и так идут к цели фокуса
    init { if (meleeOnly && engage != null) { mpackAll.n++; if (engage.id == targ.focus.focusTarget?.id) mpackHit.n++ } }
    // поводок (см. LEASH_RANGE): при враге рядом дальше поводка от центра армии — к центру.
    // ПОВОДОК НЕ ТЯНУЛ ИМЕННО ТОГО, КТО УБЕЖАЛ (v191, USE_LEASH_IN_CONTACT): условие требовало врага РЯДОМ С
    // КРИПОМ, а у крипа, отставшего от боя, врагов рядом уже нет — и он оставался стоять там, где остановился.
    // Замер по реплеям против Coldkimchi#2: в матче со стиранием (3d97c4) диаметр группы наших стрелков в
    // контакте 21 клетка против восьми у него, и 3,7 крипа из 9,8 стояли дальше пяти от центра собственной
    // армии; в матче, который стиранием не кончился (3d97d8), диаметр 3 и дальше пяти — никого. Пятеро
    // стрелков, растянутые на двадцать клеток, не могут бить одну цель: conc даёт 1,3 из 5, а трое его
    // лекарей возвращают 216 хитов в тик — цель начинает терять хиты только под залпом ЧЕТЫРЁХ разом.
    // Поэтому поводок теперь действует, пока в контакте АРМИЯ, а не пока враг стоит рядом с самим крипом
    // ...И ПОВОДОК ДЕРЖИТ ЛЕКАРЕЙ (v202, оператор: «крипы разъезжаются в разные стороны и не держатся единым
    // кулаком»). Поводок исключал ВСЮ поддержку, то есть и лекарей, — и замер реплея 3d9a7c показывает, что
    // разъезжаются именно они: дальше восьми от центра армии наши мили 11 крипо-тиков из 3 428 (0,3 %),
    // стрелки 136 из 5 756 (2,4 %), а ЛЕКАРИ 1 402 из 5 379 — 26 %, с медианой отрыва 23 клетки. Это не
    // «идёт за подопечным»: подопечный по определению в строю, а двадцать три клетки — это уход с поля боя
    // ...И ЛЕКАРЯ ПОВОДОК ДЕРЖИТ ТАКЖЕ В ОТХОДЕ (v217). Оговорка про постуру снимается ТОЛЬКО для
    // лекаря: боец, отходящий сам, — это кайт, у него своя механика; а лекарь, отпущенный поводком,
    // просто перестаёт быть лекарем. Живой замер v216 по двадцати рейтинговым матчам: боевых крип-тиков,
    // где своего лекаря нет и в MASS_RANGE, — **32,1 % в поражениях против 2,5 % в победах**, а доля
    // тиков в постуре отхода — 63 % против 0 %. Замер v202, записанный рядом, называет и виновника:
    // разъезжаются именно лекари (26 % их крипо-тиков дальше восьми клеток, медиана отрыва 23 клетки).
    // ⚠️ Это НЕ повторение v202 целиком (0:3): там поводок включался ВЕЗДЕ и для всех, здесь снимается
    // ровно одна оговорка и ровно для лекаря
    private val leashHolds = (healer) || (!posture.withdrawing)
    val leashed = LEASHED.c("notStripped", !stripped) && LEASHED.c("mobile", canMove(creep)) && LEASHED.c("leashHolds", leashHolds) &&
        LEASHED.c("foeOrContact", localEnemies.isNotEmpty() || (meas.fight.contact)) && LEASHED.c("farFromMass", getRange(creep, targ.takers.armedCentroid) > LEASH_RANGE)
    // СТРЕЛОК НЕ ВСТАЁТ НА ДВЕ, ПОКА У ВРАГА ЖИВ МИЛИ (v220, решение оператора: «мы принимаем бой,
    // когда у нас впереди рэнжи, в которых его мили сразу врезаются на первом тике»).
    // `CLOSE_STANDOFF` = 2 — это ровно та клетка, с которой ЕГО мили делает ОДИН шаг и бьёт на 240;
    // с трёх ему нужно два шага, а наш выстрел достаёт и оттуда (RANGED_RANGE = 3). То есть сближение
    // до двух не покупает нам ни одного очка урона и дарит ему темп.
    // Замер серии v219 по реплеям (двадцать матчей, только контактные тики): доля крипо-тиков, которые
    // наши стрелки проводят в досягаемости его мили (d <= 2), против его стрелков под нашими мили —
    // в победах 11 % против 20 % (под давлением ОН), в поражениях 13 % против 10 % (под давлением МЫ).
    // В двух разгромах от MetalicaX#13 это 57 % против 12 % и 45 % против 14 % — вчетверо, и оба раза
    // армия сложилась с 12 до 0 за шестьдесят тиков при его нулевых потерях.
    // Тот же случай уже записан в файле с другой стороны (v43, «прилипший»): «его мили подходили к нашим
    // стрелкам и лекарям, били по 240 и отходили — 46 ударов (11 тыс. урона) против наших 7».
    // Оговорка снимается, когда бить некому: если у врага не осталось живого вооружённого мили,
    // сближение до двух снова бесплатно и напор сохраняется
    // ...и ТОЛЬКО ПРОТИВ БЛОБА (v220, замер гейта). Первая редакция спрашивала про любого живого мили
    // рядом и уронила `match28:scatter`: 24 310:13 503 -> 21 781:24 307, единственный FAIL. Причина
    // прямая — против РАССЫПАННОГО соперника «держать три» значит не догнать никого, а догонять там и
    // надо. Разгромы же, ради которых правка делается, все три были против БЛОБА (форма BLOB в разборе
    // реплеев: #4, #15, #19), и против блобов серия v219 дала 1-4. Признак блоба в файле уже есть —
    // `enemyMassedNow` (не меньше шести его вооружённых, две трети из них в MASS_RANGE от их центроида),
    // новых сущностей не заводится
    private val foeMeleeLive = meas.forces.enemyMassedNow && localEnemies.any { hasMelee(it) && InfluenceMap.profileOf(it).melee > 0.0 }
    // ⚠️ ...И ТЕПЕРЬ ЭТО ПРАВИЛО НАКОНЕЦ ДЕЙСТВУЕТ (v521, см. USE_HOLD_THREE_VS_MELEE). Всё, что описано выше, было
    // вычислено в `foeMeleeLive` и потрачено ТОЛЬКО НА ПРИБОР: сам `closeIn` этой величины не спрашивал. Четвёртый
    // случай класса «правило измеряется, но не действует». Его собственный прибор `close3` (сколько агрессивных
    // тиков стрелка пришлось на живого мили сомкнутого врага) делит исходы резче почти всего в своде: 27,2 %
    // в 316 поражениях против 2,1 % в 108 победах
    val closeIn = if (localAggressive && !(USE_HOLD_THREE_VS_MELEE && foeMeleeLive)) CLOSE_STANDOFF else RANGED_RANGE
    init { if (hasRanged(creep) && localAggressive) { closeTicks.n++; if (foeMeleeLive) closeHeld.n++ } }
    // ПРИБОР РЕЗУЛЬТАТА СТОЙКИ, а не её экспозиции (v522). `close3` считает, СКОЛЬКО агрессивных тиков стрелка
    // пришлось на живого мили сомкнутого врага, — это повод правила, и он не меняется от того, послушались его или
    // нет; A/B v521 это и показал (4,9 % у базы против 5,3 % у правки при неразличимом счёте). Здесь — то, что
    // правило меняет: крип-тики ВООРУЖЁННОГО стрелка на двух клетках и ближе от его ЖИВОГО мили, то есть ровно
    // там, где тот одним шагом бьёт на 240, а наш выстрел с трёх достаёт и так
    init {
        if (hasRanged(creep) && InfluenceMap.profileOf(creep).ranged > 0.0 && meas.fight.contact) {
            rnearAll.n++
            if (meas.forces.combatEnemies.any { InfluenceMap.profileOf(it).melee > 0.0 && getRange(creep, it) <= 2 }) rnearN.n++
        }
    }
    // сброс слота строя у мили с целью — работа ТЕЛОМ, остаётся написание А (v452, пункт Д — разбиение оператора)
    val melee = meleeOnlyBorn(creep)
    val meleeMate: Creep? = if (melee) strat.inp.combatArmy.filter { it.id != creep.id && meleeOnlyLive(it) && canMove(it) }.minByOrNull { getRange(creep, it) } else null
    // мили со слотом стены (см. planBlock) оставляет его ради цели: прижим или враг в досягаемости удара
    val slot = if (melee && engage != null) null else slot0
    val grab = targ.takers.grabberOf[creep.id]?.let { id -> ctx.flags.firstOrNull { it.id == id } }
    // лекарь держится вплотную к самому раненому бойцу РЯДОМ (в дальности лечения плюс шаг), иначе идёт к
    // ближайшему ходячему бойцу — не к самому раненому через полкарты: два лекаря шли к обездвиженному
    // остову за стеной, а строй ждал их у флага (стенд greedy)
    val healMate = if (HEAL_MATE.c("healer", healer)) {
        // хранитель флага — не подопечный, пока есть ходячие бойцы (v120, USE_HEALERS_NOT_WITH_KEEPERS): лекарь рядом с
        // хранителем видел в нём единственного «своего в четырёх», и трое лекарей стояли по одному у хранителей на D5 и
        // R3, а ударная шестёрка у A3 шла без лечения (spread m30 на v120i, 20291:24327); раненый хранитель снимается с
        // флага (см. updateKeepers) и становится подопечным как все
        val keptOut =  ctx.army.any { hasWeapon(it) && canMove(it) && it.id !in Squads.keeperIds }
        val fighters = ctx.army.filter { it.id != creep.id && hasWeapon(it) && !(keptOut && it.id in Squads.keeperIds) }
        // подопечные — вооружённые; вне боя рядом — и раненые (они сами идут к лекарю, см. wounded)
        val patients = ctx.army.filter { it.id != creep.id && !(healerOnly(it)) && !(keptOut && it.id in Squads.keeperIds) }
        val engagedNear = fighters.any { f -> getRange(creep, f) <= HEAL_RANGE + 1 && meas.forces.combatEnemies.any { getRange(f, it) <= RANGED_RANGE + 1 } }
        val near = (if (engagedNear) fighters else patients).filter { getRange(creep, it) <= HEAL_RANGE + 1 }
        // подопечный под огнём (v109b, USE_WARD_UNDER_FIRE) ОТВЕРГНУТ таблицей входов стенда: лекари шли к терявшему хиты
        // ВПЕРЁД, в досягаемость его стрелков — за первые 20 тиков контакта потери выше v108 в 13 сценариях из 26 (nine,
        // hunter, fourteen, screen+focus: скрипты «лекари первыми»), ниже в 5; m28 nine 1714 → 3010, m34 nine 3504 → 4400.
        // Остаётся выбор ЦЕЛИ лечения под огнём (см. rank в healAndShoot); подопечный — самый раненый вооружённый, как прежде
        // ...а при УДЕРЖИМОЙ жертве (v228, см. USE_HEAL_WALL) подопечный — она: лечение вплотную 72 против 24 издали,
        // и именно её он бьёт сейчас, а самый раненый в дальности — уже отведённый в тыл
        // ЛЕКАРЬ К РАНЕНОМУ ХРАНИТЕЛЮ (v310, см. GROUP_SAFE_DMG): в режиме пар ядро не дерётся вовсе, а хранителя на
        // флаге расстреливают его одиночки — 25 снятий «хиты ниже половины» за матч, и флаг возвращается к нему.
        // Правило v120 выше стояло против того, чтобы лечение уходило ОТ УДАРНОЙ ГРУППЫ; здесь её нет, а пара
        // «хранитель и лекарь» — уже группа, а группы он не бьёт (см. GROUP_SAFE_DMG). Идёт ближайший лекарь
        // ...и НЕ ВО ВРЕМЯ БОЯ И НЕ ПОСЛЕДНИМ ЛЕКАРЕМ (v311, гейт: match33:camp 15 828:23 982 — лагерь стенда дерётся,
        // когда к нему подходят, а лечение ушло к хранителям): один лекарь всегда остаётся с ядром
        // ...и «вне боя» здесь — КОНТАКТ МАССЫ АРМИИ, а не всякий выстрел за окно (v315): `fightOnNow` держится
        // двадцать тиков после любого выстрела, а фермер стреляет по одиночкам весь матч — лекарь не выходил к
        // хранителю почти никогда, и тот сходил с флага по хитам 6–11 раз за матч
        val medic = if (!HEAL_MATE.c("medic.pairsMode", Signals.groupSafe) || !HEAL_MATE.c("medic.noCoreContact", !meas.fight.contact)) null else run {
            val medics = ctx.army.filter { healerOnly(it) && canMove(it) }
            if (!HEAL_MATE.c("medic.twoMedics", medics.size >= 2)) return@run null
            // ...и подопечный — не только хранитель из армии, но и ДЕРЖАТЕЛЬ-БЕГУН на нашем флаге (v334): против
            // けろびー боя нет вовсе (kills=0, fire=0 за матч), гонку решают тела на флагах, а одиночку он
            // расстреливает — мы теряем 5,3 крипа за матч против его 0,5. Лекарь в ядре при этом проводит 65 % времени
            // без дела; у флага он делает пару, которую сгонять нечем
            // ...и к гарнизону лекарь идёт ЗАРАНЕЕ, а не по ране (v342): уход держателя решается сравнением «его
            // возможный урон по клетке против нашего лечения на ней» (v340), а лечение там ноль, пока лекарь в ядре, —
            // к раненому он уже не успевает, тот ушёл. Гарнизонных флагов четыре, лекарей три, один всегда с ядром
            val garrison = ctx.runners.filter { r -> Squads.garrisonOf[r.id] != null &&
                ctx.flags.any { f -> f.ours && f.pos.x == r.x && f.pos.y == r.y } }
            val holders = ctx.runners.filter { r -> r.hits < r.hitsMax &&
                ctx.flags.any { f -> f.ours && f.pos.x == r.x && f.pos.y == r.y } }
            val hurt = ctx.army.filter { it.id in Squads.keeperIds && it.hits < it.hitsMax } + holders + garrison
            if (!HEAL_MATE.c("medic.someoneHurt", hurt.isNotEmpty())) return@run null
            val free = medics.toMutableList()
            var mine: Creep? = null
            var taken = 0
            for (k in hurt.sortedBy { it.hits }) {
                if (taken >= medics.size - 1) break
                val m = free.minByOrNull { getRange(it, k) } ?: break
                free.remove(m); taken++
                if (m.id == creep.id) { mine = k; break }
            }
            mine
        }
        medic
            ?: (if (HEAL_MATE.c("victim.saveable", Wall.victimSaveable)) Wall.victimNow?.takeIf { v -> HEAL_MATE.c("victim.notMe", v.id != creep.id) && HEAL_MATE.c("victim.inReach", getRange(creep, v) <= HEAL_RANGE + 1) } else null)
            ?: near.maxByOrNull { it.hitsMax - it.hits }
            ?: fighters.filter { canMove(it) }.minByOrNull { getRange(creep, it) }
            ?: fighters.minByOrNull { getRange(creep, it) }
            ?: patients.minByOrNull { getRange(creep, it) }
    } else null
    // КАЙТ ПРОТИВ СОМКНУТОГО БЛОБА (v135, см. USE_MASS_KITE): его мили достаёт на клетку, стрелок — на три, значит
    // в ДВУХ его четыре мили (960 в тик вплотную) не дают ничего, а размен стрелками идёт ровно. Держим два от
    // ближайшего его мили, пока его армия сомкнута и мы сами ещё не в контакте
    // ...и ТОТ, КТО ОТВЕТИТЬ НЕ МОЖЕТ, уходит и из контакта (v135, см. USE_KITE_HELPLESS_OUT): стрелок вплотную
    // стреляет, мили бьёт, а лекарь и раздетый под ударом — только мясо: они дают 0 урона и держат его мили
    // занятым бесплатной целью. Лечение достаёт на три, так что отойдя, лекарь лечит треть, но живёт
    // ...и пока НАШИ СТВОЛЫ НЕ ПОДТЯНУЛИСЬ (v135, см. USE_KITE_UNTIL_READY): на входе телеметрия читает reach=2/5 —
    // в дальности два наших стрелка из пяти, а он бьёт всеми двенадцатью; к 50-му тику подтягиваются все, но бой
    // уже проигран. Пока достающих меньше двух третей живых, держим дистанцию, даже если его армия не сомкнута
    // ...и НЕ В ОБЪЯВЛЕННОМ ЗАСТОЕ (v269, гейт: match31:camp). Бросок (engage), защита тыла (poker) и изготовка (forming)
    // снимаются застоем, кайт — нет, а он стоит в цепочке шага выше всего, кроме приказа и слота. Против лагеря,
    // который сомкнут и не идёт, это вечная стойка: застой объявлен (`stalled`, командир молчит по `stall`), каждый
    // крип держит дистанцию до его мили и стоит — `kite/stay` 1 600 тиков, флаги и счёт застыли, проигрыш по очкам
    // 16 805:19 932 там, где v268 выигрывал 22 345:19 797. Правка v269 (ствол держит фокус) лишь привела траекторию в
    // эту ловушку: стойка кайта против стоящего лагеря была и раньше, её не вскрывала ни одна строка гейта
    val massKite: Creep? = if (MASS_KITE.c("combatant", (!support)) && MASS_KITE.c("enemyMassed", (meas.forces.enemyMassedNow)) && MASS_KITE.c("notStalled", !meas.chase.stalled) &&
            MASS_KITE.c("noFoeAdjacent", (meas.forces.combatEnemies.none { getRange(creep, it) <= 1 }  ))) {

             // ...или стрелок выходит из-под удара, НЕ ЗАМОЛКАЯ (v135, см. USE_KITE_KEEPS_FIRE): прежний срез
             // (USE_KITE_BREAKS_CONTACT) отвергнут за 0-6/0-6, потому что уходящий стрелок терял цель — его
             // дальность три. Здесь шаг назад разрешён, только когда в дальности стоят двое и больше его
        (meas.forces.combatEnemies.filter { InfluenceMap.profileOf(it).melee > 0.0 && getRange(creep, it) <= ENGAGE_RANGE })
            .minByOrNull { getRange(creep, it) }
    } else null
    val healerNear: Creep? = if (stripped || rotating) {
        val hs = ctx.army.filter { it.id != creep.id && healerOnly(it) }
        hs.minByOrNull { getRange(creep, it) }
    } else null
    // под огнём без двух бойцов вплотную — назад к строю, не вперёд: шип в строй врага бьют трое-четверо, а он один
    // вплотную, не «в двух клетках»: со счётом союзников в двух клетках мили под огнём не отходили и ныряли в блоб
    // врага по одному — три мили за восемь тиков при одном убитом (стенд m5 army, v22, t=300–308)
    val aloneInFire =  ALONE_IN_FIRE.c("combatant", !support) && ALONE_IN_FIRE.c("notStripped", !stripped) && ALONE_IN_FIRE.c("annihilate", posture == Posture.ANNIHILATE) && ALONE_IN_FIRE.c("notPushing", !pushing) && ALONE_IN_FIRE.c("underFire", InfluenceMap.damageAt(creep.x, creep.y, meas.forces.combatEnemies) > 0.0) &&
        ALONE_IN_FIRE.c("fewerThanTwoMates", strat.inp.combatArmy.count { it.id != creep.id && hasWeapon(it) && getRange(creep, it) <= 1 } < 2) && ALONE_IN_FIRE.c("noPressTarget", pressTarget == null) 
    // вес огня лекаря: подопечный в бою — только разница между клетками (HEALER_W_DAMAGE_FIGHT); место за
    // подопечным и шаг от мили задают HEALER_W_FRONT и HEALER_W_MELEE, а вес 0.05 в бою держал лекаря на кромке
    // огня в 2–3 клетках (лечение 24 вместо 72) и проиграл рубки sleeper на картах 4 и 8
    val healerFireW = if (USE_HEALER_DANGER_FLOW) HEALER_W_FLOW
        else if (healer && healMate != null && meas.forces.combatEnemies.any { getRange(healMate, it) <= RANGED_RANGE + 1 }) HEALER_W_DAMAGE_FIGHT else HEALER_W_DAMAGE
    // сбор: по полю марша (флаг-цель или пост, в обход врагов) авангард — самый продвинутый из ходячих
    // вооружённых (при равном поле — меньший id); кто дальше RALLY_RANGE от авангарда, идёт к нему
    // только на марше к флагу-цели: в HOLD цель — точка, к ней сходятся и так, а в ANNIHILATE ожидание
    // «далёкого» напарника, занятого своим боем, останавливало армию (стенд greedy)
    // ходячий по canMove, не «полноскоростной»: покалеченный участник, не входящий в сбор, но ждущий
    // далёких, замыкал группу в тупик (стенд rush: 10 против 2 до конца матча)
    private val groupedPre =  !support && canMove(creep) && posture == Posture.FLAG
    private val marchTarget: Position? = strat.obj.objective?.flag?.pos
    var rallyTo: Position? = null
    init {
        if (groupedPre && marchTarget != null) {
            val mf = flowAvoiding(ctx, marchTarget, creep)
            val my = mf[creep.key]
            var van: Creep? = null
            var vanFlow = my
            var vanId = creep.id
            // авангард — из массы (v120, USE_RALLY_VAN_FROM_MASS): оторвавшийся крип не точка сбора, как и в построении
            val rallyPool = meas.chase.mobileArmy.filter { hasWeapon(it) && getRange(it, targ.takers.armedCentroid) <= MASS_RANGE }.ifEmpty { meas.chase.mobileArmy }
            for (m in rallyPool) {
                if (m.id == creep.id || !hasWeapon(m)) continue
                val d = mf[m.key]
                if (d < 0) continue
                if (vanFlow < 0 || d < vanFlow || (d == vanFlow && m.id < vanId)) { vanFlow = d; vanId = m.id; van = m }
            }
            // с гистерезисом: с клетки «13 от авангарда» крип шёл к нему, со следующей («12») — снова к цели,
            // и два шага туда-обратно длились до конца матча, а авангард ждал (стенд rush)
            val rallyRange = if (creep.id in Memory.rallyingIds) RALLY_RANGE / 2 else RALLY_RANGE
            val rallyNow = van != null && getRange(creep, van) > rallyRange
            if (rallyNow) rallyTo = InfluenceMap.cell(van!!.x, van.y)
            Memory.rallyingLatch.set(creep.id, rallyNow)
        } else Memory.rallyingLatch.set(creep.id, false)
    }
    // построение: вне огня и без готовности авангард и собравшиеся у него стоят, остальные идут к нему
    // в контакте построение окончено: авангард — тот, кто уже дерётся, и «собраться у авангарда с дистанцией 1»
    // тянуло стрелков за ним внутрь строя врага, а стреляли они с 4–5 клеток впустую (матч 15, t=68–100)
    private val forming = FORMING.c("hasVan", targ.form.formVan != null) && FORMING.c("notReady", !targ.form.formationReady) && FORMING.c("combatant", !support) && FORMING.c("mobile", canMove(creep)) && FORMING.c("notWithdrawing", !posture.withdrawing) &&
        FORMING.c("threatNear", localEnemies.any { threatening(it, meas.forces.enemyCreeps) }) && FORMING.c("outOfHisRange", nearestEnemyRange > RANGED_RANGE) && FORMING.c("noContact", !meas.fight.contact) &&
        FORMING.c("notStalled", !(meas.chase.stalled))
    val formHold = forming && (targ.form.formVan!!.id == creep.id || getRange(creep, targ.form.formVan) <= FORM_RANGE)
    val formGo = forming && !formHold
    // в строю (см. USE_BLOCK): мили вплотную к врагу стоит и рубит, остальные — в свой слот
    val slotHold = slot != null && melee && localEnemies.any { getRange(creep, it) <= 1 }
}


/**
 * ФАКТЫ ШАГА (v444, план архитектуры, этап 3): то, что становится известно ПОСЛЕ выбора цели, — поле потока к ней, огонь,
 * бегство, сплочение. `creep`, `ctx`, `t` — те же объекты, что у [Turn].
 * НОСИТЕЛЬ (v456, второй шаг архитектуры, 4.1 и этап 3): ПОЛЕ ОБЪЯВЛЕНО ТАМ, ГДЕ ВЫЧИСЛЕНО. До v456 каждое имя писалось трижды —
 * в заголовке класса, локальной в построителе `buildStride` и аргументом `x = x` его `return Stride(…)`; теперь класс И ЕСТЬ
 * прежнее тело построителя, дословно и в прежнем порядке: порядок текста — порядок инициализации, операторы между фактами
 * (приборы лекаря, записи терпения) стоят блоками `init` на своих местах последовательности. То, что читают цепочка шага
 * ([steps]) и хвост хода, — открытые поля; остальное `private`. Факты хода читаются явно — `turn.slot`; величины тика — через
 * приватные `meas` / `strat` / `targ`; то, что ещё остаётся членом синглтона, — через `t.pag` (`t.pag.lostTick`, см. [ArmyTick]).
 */
internal class Stride(val turn: Turn, val aim: Aim) {
    val creep: Creep get() = turn.creep
    val ctx: Ctx get() = turn.ctx
    val t: ArmyTick get() = turn.t
    private val meas = turn.t.meas
    private val strat = turn.t.strat
    private val targ = turn.t.targ

    private val target = aim.target
    private val standoff = aim.standoff
    private val avoid = aim.avoid
    private val nearFlow = aim.nearFlow   // цель-крип рядом: поле «вблизи» (см. NEAR_FLOW)
    private val flow0 = if (turn.slot != null || turn.keeper) NO_FLOW else if (avoid) flowAvoiding(ctx, target, creep, nearFlow) else flowTo(ctx, target, near = nearFlow)
    // за пределом поля «вблизи» — полное поле
    val flow = if (nearFlow && turn.slot == null && !turn.keeper && flow0[creep.key] < 0)
        (if (avoid) flowAvoiding(ctx, target, creep) else flowTo(ctx, target)) else flow0

    val nearbyEnemies = meas.forces.combatEnemies.filter { getRange(creep, it) <= 12 }
    // «в бою», плотность и передний ряд — по врагам С БОЕМ: три безоружных лекаря врага в 4–5 клетках после выигранного
    // боя держали армию в зоне плотности и не давали толкнуть своего — мили, зажатый своими лекарями, и армия
    // простояли тысячу тиков в 20 клетках от флага (стенд m16 nine)
    val inCombat = meas.forces.armedEnemies.any { creep.getRangeTo(it) <= RANGED_RANGE + 2 }
    val localThreats = turn.localEnemies.filter { threatening(it, meas.forces.enemyCreeps) }
    private val underFire = InfluenceMap.damageAt(creep.x, creep.y, meas.forces.combatEnemies) > 0.0 || turn.ghost > 0
    // бегство: смертельный урон за два тика; безоружный лекарь — от врага рядом, если рядом нет ни одного
    // вооружённого своего (при нём лекарь стоит и лечит: бегущий лекарь — потерянные 72 в тик, матч 3);
    // невидимый урон
    // вооружённый бежит, только когда его ДОБИВАЮТ: за прошлый тик снято не меньше половины оставшихся хитов и
    // осталось меньше трети. Прежнее «весь возможный огонь по клетке за два тика больше хитов» в бою 12 на 12
    // верно для КАЖДОЙ клетки у вражеского блоба (1260 против 1600), и трое мили с полными хитами
    // разворачивались спиной в первый тик контакта — их резали в спину, строй рассыпался (стенд sleeper,
    // t=550; матчи 4–6 в первом размене теряли 1:5 при равной силе)
    private val lostLastTick = Memory.lastHits[creep.id]?.let { it - creep.hits } ?: 0
    // лекарь и раненый бегут (врассыпную) только в одиночестве: при своём рядом — отход группой по постуре
    // раненый (без оружия и лечения) и в контакте уходит из ПОЛНОЙ досягаемости — стрелка в трёх, мили в двух (v123,
    // USE_STRIPPED_LEAVES_REACH): сужение до мили в контакте — для лекаря, которому лечить фронт; обезоруженному в
    // огне делать нечего, а живым он вернётся с лечением. Серия 307–326: наши обезоруженные стояли в трёх от его
    // вооружённых половину своего времени (131 из 258, 264 из 552 крип-тиков), его — десятую (26 из 79, 7 из 11);
    // правило «обезоруженные в досягаемости» — 3 из 6 поражений и 0 из 18 побед
    val reachMine = targ.zones.reachNow
    val inReach = (creep.key) in reachMine
    // ...и для лекаря закрытые для шага клетки — полная досягаемость (v234, вторая редакция), бегство — по прежней
    val avoidCells = reachMine
    // прибор v234: лекарь в бою и в досягаемости его вооружённых; урон по лекарям
    init { if (turn.healer && inCombat) { hexpAll.n++; if ((creep.key) in targ.zones.reachCells) hexpN.n++; hlostSum.n += (Wall.lostTick[creep.id] ?: 0) } }
    // ПРИЛЕГАНИЕ МИЛИ (v501, прибор). Разбор десяти рук v497 по реплеям назвал эту величину лучшим кандидатом в
    // причину: доля крипо-тиков вооружённого мили с врагом на дистанции 1 — 49-59 % в победах против 42-46 % в
    // поражениях, замерено ДО предрешённости и по знаменателю «вооружённых», то есть не падает от того, что наших
    // срезали. Арифметика делает её крупной: a8m8 вплотную даёт 240 урона в тик против 60 у r6m6, и четыре мили ×
    // 16 тиков × 10 п.п. ≈ 1 500 урона — больше, чем нужно, чтобы перевалить ещё одного его крипа за порог
    // безоружности. Своего прибора у неё нет: `touch=` насыщен (100/100/100 во всех десяти матчах) и различать не
    // может. Здесь: вплотную / в шаге от вплотную / мили-тиков в бою
    // ⚠️ И ГИПОТЕЗА ЗАКРЫТА СВОИМ ЖЕ ПРИБОРОМ (v501, 14 рук против MetalicaX#17, 4-10). Доля «вплотную»: в победах
    // 11,8 / 18,4 / 18,1 / 23,5 %, в поражениях 11,9-36,6 % со средним 20,3 %; «в шаге» — 42,8-51,2 % против
    // 34,8-59,2 %. Перекрытие полное, в поражениях прилегание даже чуть выше. Прилегание мили исходов НЕ делит, и
    // правку по нему делать не на чем. Сам разбор реплеев это и предупреждал: около сорока величин на пяти окнах
    // = двести проверок, и два чистых разделения 6/4 ожидаются от одного везения. Прибор оставлен: он дёшев,
    // тождествен и отвечает на этот вопрос навсегда
    init {
        if (InfluenceMap.profileOf(creep).melee > 0.0 && inCombat) {
            madjAll.n++
            val d = meas.forces.armedEnemies.minOfOrNull { creep.getRangeTo(it) } ?: 99
            if (d <= 1) madjN.n++
            if (d <= 2) madjStep.n++
        }
    }
    /** БЕГСТВО НЕ РАБОТАЕТ, КОГДА БЕЖАТЬ НЕКУДА (v519, см. USE_HEALER_STANDS): ни одна из восьми клеток шага не
     *  выходит из досягаемости его вооружённых ПОСЛЕ их собственного шага (стрелок 3 + 1, мили 1 + 1). Тела арены
     *  несут MOVE ровно в половину тела, то есть преследователь ходит с той же скоростью, и в этом состоянии уход
     *  дистанции не даёт вовсе. Считается из состояния, соперник в условии не назван. */
    val fleeHopeless = USE_HEALER_STANDS && turn.support && run {
        val guns = meas.forces.armedEnemies
        if (guns.isEmpty()) false else {
            // ожидаемый входящий в клетку ПОСЛЕ его шага: стрелок достаёт 3 + 1, мили 1 + 1
            fun incoming(x: Int, y: Int): Double = guns.sumOf { g ->
                val q = InfluenceMap.profileOf(g)
                val rr = if (q.ranged > 0.0) RANGED_RANGE + 1 else 0
                val rm = if (q.melee > 0.0) 2 else 0
                val d = maxOf(abs(x - g.x), abs(y - g.y))
                (if (rr > 0 && d <= rr) q.ranged else 0.0) + (if (rm > 0 && d <= rm) q.melee else 0.0)
            }
            val here = incoming(creep.x, creep.y)
            // ...И ВЫИГРЫШ ШАГА СРАВНИВАЕТСЯ С ЦЕНОЙ УХОДА В ТЕХ ЖЕ ЕДИНИЦАХ. Оставшись, лекарь доставляет
            // подопечному 72 вплотную и 24 в двух-трёх клетках; уйдя, он доставляет ноль и снижает входящий на
            // `here - best`. Уходить стоит только когда снятого урона БОЛЬШЕ, чем несделанного лечения: уход
            // из-под одного стрелка снимает 60 и не окупает 72, из-под двух — 120 и окупает
            val mate = turn.healMate
            val deliver = if (!turn.healer || mate == null) 0.0
                else InfluenceMap.healOf(creep) * (if (getRange(creep, mate) <= 1) 1.0 else 1.0 / 3.0)
            val best = dirsNow().filter { (dx, dy) -> dx != 0 || dy != 0 }
                .mapNotNull { (dx, dy) ->
                    val x = creep.x + dx; val y = creep.y + dy
                    if (x in 0..99 && y in 0..99) incoming(x, y) else null
                }.minOrNull() ?: here
            here > 0.0 && here - best <= deliver
        }
    }
    init { if (turn.support && inCombat) { fuseAll.n++; if (fleeHopeless) fuseN.n++ } }
    val mustFlee = MUST_FLEE.c("supportAloneNearFoe", turn.support && nearbyEnemies.any { getRange(creep, it) <= RANGED_RANGE + 1 } && ctx.army.none { it.id != creep.id && getRange(creep, it) <= HEAL_RANGE }) ||
        // ...НО ЛЕЧАЩИЙ ВПЛОТНУЮ НЕ БЕЖИТ (v518, см. USE_HEALERS_OUT_OF_REACH). У шага изъятие на этот случай есть с
        // v234 (`carveBase` ниже), у бегства не было: пока зону сужали до клеток вплотную к его мили (v294), лекарь
        // в неё почти не попадал, и отсутствие изъятия не проявлялось. С расширением зоны эта ступень становится
        // главным путём лекаря, и без изъятия она уводила бы его ровно из той клетки, где он даёт 72 лечения в тик.
        // Граница ровно по арифметике: остаётся только ВПЛОТНУЮ (72), дальнее лечение (24) достаётся и снаружи зоны —
        // контрфакт v511 по четырём поражениям дал «вне огня дальним» 440/288/288/460 против фактических 404/240/264/456
        // Изъятие действует ТОЛЬКО в том состоянии, которое правка и создаёт (`healersOutOfReach`): под тумблером
        // целиком оно меняло и старое поведение при узкой зоне — гейт назвал цену сразу, match13:brawl+heals
        // 4842:822 (армия врага уничтожена на t=389) превращался в 14356:15094 к пределу тиков
        // ⚠️ ...И ЛЕКАРЬ, КОТОРОМУ ЕСТЬ КОГО ЛЕЧИТЬ, НЕ БЕЖИТ ВООБЩЕ (v519, см. USE_HEALER_STANDS). Это вывод из
        // отказа v518, и он арифметический. Тела арены несут MOVE ровно в половину тела, значит КАЖДЫЙ крип ходит
        // клетку в тик — и преследователь тоже. Бегущий лекарь дистанции не набирает: он даёт НОЛЬ лечения
        // (`fleeStep` уводит от врага, а подопечные — у врага) и получает тот же входящий. Стоящий даёт 72 в тик
        // вплотную. Размен решается без всякой серии: 72 против нуля при одинаковом уроне.
        // Прибор назвал цену прямо: в поражениях лекаря ведёт бегство в 35-50 % шагов (`hstep=flee` 192/239/428 из
        // 553/666/864), в победах — в 0,1-0,3 % (3/5/11 из 3300/3900/3900). И строя в поражениях нет вовсе
        // (`lay=FREE:54,ROWS:11` против `ROWS:41,FREE:36` в победе): бегство и есть то, что его разбирает
        MUST_FLEE.c("supportInReach", turn.support && inReach &&
            !(USE_HEALER_STANDS && fleeHopeless && turn.healer && turn.healMate != null &&
                turn.healMate.hits < turn.healMate.hitsMax && getRange(creep, turn.healMate) <= HEAL_RANGE) &&
            !(TacticianState.healersOutOfReach && turn.healer && turn.healMate != null &&
                turn.healMate.hits < turn.healMate.hitsMax && getRange(creep, turn.healMate) <= 1)) ||
        MUST_FLEE.c("stepOutInReach", turn.stepOut && (creep.key) in targ.zones.reachCells) ||
        MUST_FLEE.c("bleeding", lostLastTick * 2 >= creep.hits && creep.hits * 3 < creep.hitsMax) ||
        MUST_FLEE.c("ghostDamage", turn.ghost > 0 && creep.hits <= turn.ghost)

    // сплочение: авангард ждёт отставших группы (в тиках ИХ хода), пока сам не под огнём и напарник
    // не в бою; при враге в досягаемости зазор тесный — собираемся ДО входа под огонь
    val myFlow = flow[creep.key]
    private val grouped = GROUPED.c("combatant", !turn.support) && GROUPED.c("groupPostureOrThreatHunt", posture == Posture.ANNIHILATE || posture == Posture.FLAG || (huntingThreat && strat.threats.threat != null && target === strat.threats.threat))
    // напарники строя — ходячие ВООРУЖЁННЫЕ: у лекаря своя цель (подопечный), и взаимное ожидание «лекарь
    // отстал от флага — боец отстал от подопечного лекаря» запирало группу навсегда (стенд greedy)
    private val mates = if (grouped) armedMatesOf(meas.chase.mobileArmy, creep) else emptyList()
    private val mateFighting = mates.any { m -> meas.forces.combatEnemies.any { m.getRangeTo(it) <= RANGED_RANGE + 2 } }
    private val gap = if (turn.localEnemies.isEmpty()) COHESION_GAP else ENGAGE_COHESION_TICKS
    // идущий к авангарду (rallyTo) не ждёт никого: четверо шли к авангарду и «ждали» одиночку в 14 клетках,
    // а тот ждал их — взаимное ожидание на 1600 тиков (стенд m2 scouts, v4)
    // подбирающий флаг рядом (grab) не ждёт никого, и его никто не ждёт: он ждал по сплочению группу у своего
    // флага, а группа ждала его как «отставшего» на пути к цели — взаимное ожидание на 1200 тиков при живом
    // враге из двух скаутов на наших флагах (стенд m3 kite, проигрыш по очкам 9128:23661)
    private val cohesionHold = COHESION_HOLD.c("grouped", grouped) && COHESION_HOLD.c("noRally", turn.rallyTo == null) && COHESION_HOLD.c("noGrab", turn.grab == null) && COHESION_HOLD.c("noEngage", turn.engage == null) && COHESION_HOLD.c("notUnderFire", !underFire) && COHESION_HOLD.c("noMateFighting", !mateFighting) && COHESION_HOLD.c("onFlow", myFlow >= 0) && COHESION_HOLD.c("notArrived", creep.getRangeTo(target) > standoff + ARRIVED_SLACK) && COHESION_HOLD.c("mateLagging", run {
        var lagging = false
        for (m in mates) {
            if (getRange(creep, m) <= RANGED_RANGE) continue
            if (targ.takers.grabberOf.containsKey(m.id) || m.id in Memory.engagingIds) continue
            val d = flow[m.key]
            if (d < 0) continue
            // напарник на другом обходе (только на марше к флагу): далеко и не впереди — ждём его, он идёт
            // к нам (см. rallyTo)
            if (posture == Posture.FLAG && getRange(creep, m) > RALLY_RANGE && plainPeriod(m) <= RALLY_MAX_PERIOD && (d > myFlow || (d == myFlow && m.id > creep.id))) { lagging = true; break }
            val lag = (d - myFlow) * plainPeriod(m)
            if (lag in (gap + 1)..COHESION_GAP_MAX) { lagging = true; break }
        }
        lagging
    })
    // отход строем (см. RETREAT_GAP): передняя половина ждёт отставшего от тела армии, пока сама вне огня
    val retreatHold = RETREAT_HOLD.c("withdrawing", (posture.withdrawing)) && RETREAT_HOLD.c("combatant", !turn.support) && RETREAT_HOLD.c("mobile", canMove(creep)) && RETREAT_HOLD.c("notUnderFire", !underFire) && RETREAT_HOLD.c("foeFar", turn.nearestEnemyRange > RANGED_RANGE + 1) && RETREAT_HOLD.c("onFlow", myFlow >= 0) && RETREAT_HOLD.c("rearStretched", run {
        val flows = armedOf(meas.chase.mobileArmy).map { flow[it.key] }.filter { it >= 0 }.sorted()
        if (flows.isEmpty()) return@run false
        val rear = flows.last()
        val median = flows[flows.size / 2]
        rear - median > RETREAT_GAP && myFlow <= median
    })
    // терпение (см. COHESION_PATIENCE): затянувшееся ожидание снимается до конца отставания
    init {
        if (!cohesionHold) Memory.holdSince.remove(creep.id)
        else if (creep.id !in Memory.holdSince) Memory.holdSince[creep.id] = getTicks()
    }
    private val waitedOut = cohesionHold && getTicks() - (Memory.holdSince[creep.id] ?: getTicks()) >= COHESION_PATIENCE
    init { Memory.impatientLatch.update(creep.id, enter = waitedOut, exit = !cohesionHold) }
    val hold = (cohesionHold && creep.id !in Memory.impatientIds) || turn.formHold || retreatHold
}

/** Свободный шаг (строка `free` цепочки шага): прежнее тело ветки дословно. Имена: локальная → [Stride] → [Turn] → [ArmyTick]. */
internal fun freeStep(s: Stride): Position? {
    with(s.turn.t) { with(s.turn) { with(s) {
        val target = aim.target
        val standoff = aim.standoff
        // клетка флага открыта только назначенному на него (захватчик цели, «подобрать» рядом)
        val designated = grab?.pos ?: strat.obj.objective?.flag?.pos?.takeIf { targ.takers.objectiveCapturer == creep.id }
        var myBlocked = if (designated != null) targ.pool.blockedSet - (designated.key) else targ.pool.blockedSet
        // плотность (см. COMPACT_RANGE): при враге в досягаемости — только на клетки строя
        // лекарь и раненый — вне правила (их цель — свой в строю); снаружи зоны шаг К центру всегда открыт:
        // прежде крип вне зоны не мог шагнуть никуда (все соседи тоже вне), и три лекаря простояли весь бой
        // матча 8 в 4–5 клетках от строя
        if (!stripped && localThreats.isNotEmpty() && !posture.withdrawing && canMove(creep)) {
            val armedMates = armedMatesOf(meas.chase.mobileArmy, creep)
            val myRange = getRange(creep, targ.takers.armedCentroid)
            val loose = HashSet<Int>()
            for ((dx, dy) in dirsNow()) {
                val x = creep.x + dx; val y = creep.y + dy
                if (x < 0 || y < 0 || x > 99 || y > 99) continue
                val c = InfluenceMap.cell(x, y)
                val r = getRange(c, targ.takers.armedCentroid)
                // шаг ВПЕРЁД (по потоку к цели) открыт на клетку в COMPACT_RANGE + 1: иначе авангард не мог
                // выйти из зоны, а центр не сдвигался, пока никто не выходил, — блоб 1200 тиков стоял в
                // четырёх клетках от последнего лекаря врага и проиграл по очкам (стенд m5 kite); линия
                // ползёт гусеницей — впереди не дальше трёх от центра, остальные подтягиваются
                val fd = flow[key(x, y)]
                val advancing = myFlow >= 0 && fd in 0 until myFlow
                val compact = r <= COMPACT_RANGE || (advancing && r <= COMPACT_RANGE + 1) || armedMates.count { getRange(c, it) <= 1 } >= 2 || (r < myRange)
                if (!compact) loose.add(key(x, y))
            }
            if (loose.isNotEmpty()) myBlocked = myBlocked + loose
        }
        // передний ряд — вооружённым: лекарь и раненый не встают на клетку вплотную к боевому врагу (она
        // нужна нашему мили; трое лекарей без штрафа за соседей заняли ряд перед своими мили у самого
        // раненого бойца, и размен шёл без наших ударов — стенд m7 sleeper, 4 убитых врага против 5)
        // уже вплотную к врагу — блок переднего ряда снят: окружённый лекарь стоял (все соседи «передний
        // ряд»), а не уходил (матч 10, healer_2 на (14,10) между двумя мили врага)
        // лекарь и раненый снаружи досягаемости в неё не входят (см. reachCells)
        // лекарь с РАНЕНЫМ подопечным в дальности лечения принимает дальний огонь ради лечения вплотную (72 за часть
        // против 24): запреты «не входить в досягаемость» и «не вставать рядом с врагом» держали его в двух-трёх
        // клетках от того, кого бьют. Матч 34 (бой 57–550): урона получено поровну (42285 против 41684), вылечено
        // 29275 против их 39420, у них вдвое больше событий «+144» (два лекаря вплотную на одном) — 46 против 22;
        // наш лекарь стоял вплотную к самому раненому в 13% замеров и дальше трёх клеток — в 32%. Закрытыми
        // остаются клетки вплотную к вражескому МИЛИ: там лекарь не лечит, а умирает
        val healingNow = healer && healMate != null && healMate.hits < healMate.hitsMax && getRange(creep, healMate) <= HEAL_RANGE + 1
        // ...И ИЗЪЯТИЕ ДЕЙСТВУЕТ ТОЛЬКО ПРИ ЛЕЧЕНИИ ВПЛОТНУЮ (v511, см. USE_HEAL_CARVE_ADJACENT_ONLY). Обоснование
        // изъятия — «72 за часть против 24», то есть ВПЛОТНУЮ; но условие пускало лекаря в досягаемость и когда
        // подопечный в двух-четырёх клетках, а там он льёт ту же треть, что лил бы снаружи. Реплеи четырёх
        // поражений от MetalicaX#17 (21.09.2026) эту половину оценили прямо: лекарь-тиков под его стволами с
        // лечением ТОЛЬКО дальним (или вовсе без лечения) 11-20 за матч, полученного урона 110-284, а контрфакт
        // «стоять вне огня и лечить дальним» даёт РОВНО ТО ЖЕ лечение — 440/288/288/460 против фактических
        // 404/240/264/456. Эти тики не куплены ничем, и в двух поражениях из четырёх в них не лечили совсем
        val carveBase = if (!USE_HEAL_CARVE_ADJACENT_ONLY) healingNow else healingNow && getRange(creep, healMate!!) <= 1
        // ...И ИЗЪЯТИЕ ТРЕБУЕТ ПЕРВОГО РЯДА (v513, см. USE_HEAL_CARVE_NEEDS_FRONT). Изъятие разрешает лекарю войти в
        // досягаемость его стрелков ради лечения бойца — но когда у армии не осталось НИ ОДНОЙ живой части ATTACK,
        // этого бойца уже некому прикрыть, и лекарь встаёт рядом с тем, кто его не защищает.
        // Проверка оператора (21.09.2026) подтверждена реплеями восьми матчей и звучит так: при верных эшелонах его
        // стрелку, чтобы достать лекаря на 3, надо встать ВПЛОТНУЮ к нашим мили, где его рубят на 240 в тик. Замер:
        // из 566 событий «он выстрелил в нашего лекаря» он стоял >= 2 клеток от нашего ближайшего ЖИВОГО мили в
        // 100 %, выстрелов с дистанции 1 — НОЛЬ, и наших мили на линии выстрела не было ни разу. А различает исходы
        // не глубина рядов (зазор мили->лекари у нас −0,1/1,1/0,7/−0,3 в поражениях и −1,0..+1,7 в победах, у него
        // 1,8-3,5 всегда), а наличие первого ряда ВООБЩЕ: событий «живого мили нет ни одного» 36/79/35/50 % в
        // поражениях против 0/20/0/0 % в победах, и среди фактических выстрелов по лекарям на них приходится 61-93 %
        val frontAlive = meas.forces.allies.any { hasMelee(it) }
        val carveNow = carveBase && (!USE_HEAL_CARVE_NEEDS_FRONT || frontAlive)
        if (turn.healer && carveBase) { hfrontAll.n++; if (!frontAlive) hfrontOff.n++ }
        if (support && !inReach && avoidCells.isNotEmpty() && !(carveNow)) myBlocked = myBlocked + avoidCells
        if (support && localThreats.isNotEmpty() && localThreats.none { getRange(creep, it) <= 1 }) {
            val front = HashSet<Int>()
            for ((dx, dy) in dirsNow()) {
                if (dx == 0 && dy == 0) continue
                val x = creep.x + dx; val y = creep.y + dy
                if (x < 0 || y < 0 || x > 99 || y > 99) continue
                val c = InfluenceMap.cell(x, y)
                val byMelee = targ.pool.meleeEnemies.any { getRange(c, it) <= 1 }
                val byPatient = healingNow && getRange(c, healMate!!) <= 1
                if (localEnemies.any { getRange(c, it) <= 1 } && (byMelee || !byPatient)) front.add(key(x, y))
            }
            if (front.isNotEmpty()) myBlocked = myBlocked + front
        }
        // ПРИКАЗ ИСПОЛНЯЕТСЯ, А НЕ ПЕРЕСЧИТЫВАЕТСЯ (v167): назначенная клетка была лишь ОДНИМ слагаемым в
        // оценке шага наравне с опасностью и соседями, и опасность её перевешивала — прибор показал, что
        // крип доходит до своей клетки в 7 % случаев (10 из 144) и даже приближается лишь в 32 %. Прогноз
        // при этом считает, что армия встанет по плану: он опирался на фикцию. Клетка в ОДНОМ шаге теперь
        // запрашивается напрямую, как это делают захватчики
        val chosen = bestSingleMove(creep, target, flow, standoff, localAggressive || spotNow, inCombat, meas.forces.enemyCreeps, meas.forces.allies, targ.pool.meleeEnemies, myBlocked, targ.pool.enemyPositions, targ.focus.occupantAt, healerFireW, targ.focus.focusTarget)
        return chosen
    } } }
}

private var stepRows: List<Row<Stride, Position?>>? = null

/**
 * ЦЕПОЧКА ШАГА: восемь строк, порядок списка = приоритет; действие отдаёт клетку шага или null («стоять»). Получатель строк —
 * [Stride]: голое имя — факт шага, `turn.` — факт хода, `t.` — величина тика. Счётчики `orderFled` / `orderBranch` и `pin`
 * хранителя стоят в действии своей строки — исполняются только у выигравшей, как прежде в теле ветки.
 * СТРОКИ `immobile` («крип без живой MOVE стоит») БОЛЬШЕ НЕТ (v448, пункт Б.4 оператора) — удалена по доказательству, не по
 * счётчику: тела арены фиксированы (A×8 M×8, R×6 M×6, H×6 M×6, M×1), MOVE у всех в хвосте, а движок раздаёт хиты частям
 * по общему числу с хвоста (`_recalc-body.js`: с последней части по 100) — у живого крипа последняя часть всегда жива, и
 * это MOVE; стенд бьёт спереди и лечит с хвоста — тот же инвариант. Счётчик: 0 на гейте, 0 в 20 матчах диагноза, 0 в 20
 * матчах серии v447. Тела стуба на синтетической карте (T2M5A3, ATTACK в хвосте) вне ворот; там неподвижный крип
 * предложил бы шаг, который оркестровка не отдаёт разводу (`resolve` берёт только `canMove`).
 */
internal fun steps(): List<Row<Stride, Position?>> = stepRows ?: listOf<Row<Stride, Position?>>(
    // ВЫЖИВАНИЕ ВЫШЕ ЗАДАНИЯ (v240, этап 5 переработки, решение оператора 13.09.2026): крип под смертельным
    // огнём бежит, даже если у него приказ командира или пост хранителя. До v240 приказ стоял выше бегства
    // (v172 «приказ — закон»), и комментарий у бегства утверждал обратное. Цена конфликта — прибор:
    // `fled=` (приказов, перебитых бегством) и `step=flee` в гистограмме шагов
    Row("flee", { mustFlee }, RowMark.SURVIVE, why = MUST_FLEE) {
        if (Orders.commandOf.containsKey(creep.id)) orderFled.n++
        fleeStep(creep, nearbyEnemies, ctx.dangerMatrix, if (turn.support || turn.stepOut) RANGED_RANGE + 1 else RANGED_RANGE) ?: pathStep(creep, t.strat.dec.retreatTo ?: t.strat.dec.post, 1, ctx.dangerMatrix)
    },
    // ХРАНИТЕЛЬ ТОЖЕ СЛУШАЕТ ПРИКАЗ (v173, оператор): «уйти с флага крип должен только если командир решит
    // собрать отряд, или если крип может попасть в опасность». Прежде хранитель стоял всегда и приказа не
    // видел вовсе — он был вне командира по построению (mobileArmy исключает keeperIds)
    Row("keeperOrder", { turn.keeper && Orders.commandOf.containsKey(creep.id) }) { ruleCount.bump(Orders.source); Orders.commandOf[creep.id]!!.takeIf { it.x != creep.x || it.y != creep.y } },
    Row("keeperStay", { turn.keeper }) { TrafficManager.pin(creep.id); null },
    // ПРИКАЗ — ЗАКОН (v172, оператор): «все крипы должны двигаться ТОЛЬКО по приказу командира… нельзя не
    // слушаться приказов командира». Приказ исполняется БУКВАЛЬНО: назначенная клетка и есть шаг. Прежняя
    // попытка сделать так провалилась (гейт 133, исполнение 3 %) потому, что командир раздавал клетки, не
    // считая того, что считает крип, — теперь считает (см. rankStep в commandFight), и цена ошибки лежит
    // на нём, а не на непослушании
    // ...и во ВСЕХ режимах, а не только в бою (v172, оператор): «все крипы должны двигаться ТОЛЬКО по
    // приказу командира». В гонке и походе приказ тоже закон — там он ведёт ядро строем и за флагами
    Row("order", { Orders.commandOf.containsKey(creep.id) }, RowMark.ORDER) {
        orderBranch.n++          // сколько приказов реально дошло до ветки исполнения (v173)
        ruleCount.bump(Orders.source)   // ...и ОТ КОГО он (v486, см. rule=): метка ветки командира этого тика
        if (turn.slot != null) dualOrder.n++   // ...и был ли у крипа ОДНОВРЕМЕННО слой строя (v487, см. dual=)
        val cell = Orders.commandOf[creep.id]!!
        if (cell.x == creep.x && cell.y == creep.y) null else cell
    },
    Row("slotHold", { turn.slot != null && turn.slotHold }) { dualSlot.n++; null },
    Row("slotStep", { turn.slot != null }) { dualSlot.n++; slotStep(creep, turn.slot!!, t.targ.pool.blockedSet, t.targ.pool.enemyPositions, t.targ.focus.occupantAt, t.meas.forces.combatEnemies, if (turn.support && !inReach) reachMine else emptySet()) },
    // ПРИКАЗ ВЫШЕ СЛОТА И ОСТАНОВКИ (v171): в выборе ШАГА приказ не участвовал вовсе — слот уводил крипа в
    // строй, а hold оставлял на месте, и приказ работал только в последней ветке. Разбор потерь показал
    // цену: из 143 приказов 50 кончались уходом в другую клетку и 36 — тем, что крип не двинулся
    // ...и только В БОЮ: в гонке очков приказ марша перебивал удержание, и camp падал 4 155:16 209
    Row("hold", { hold }) { null },
    Row("free", { true }, RowMark.FREE) { freeStep(this) },
).also { stepRows = it }

/** Ход одного бойца армии: тело прежнего цикла runArmy без изменений (см. заголовок файла). С v444 оно разложено по швам, в том
 *  же порядке: факты ([buildTurn]) → цель по лестнице → поле, бегство, сплочение → шаг → предложение арбитру.
 *  Имена читаются так: локальная → поле [Turn] → поле [ArmyTick] → член `PainAndGain` → верх пакета. */
internal fun creepTurn(creep: Creep, ctx: Ctx, t: ArmyTick) {
    val tracing = traceNow(getTicks())
    if (tracing) forgetFirstFalse()
    val turn = Turn(creep, ctx, t)
    // ПЕРЕПИСЬ РЕШЕНИЙ (v203, этап 1): каждая ветка обеих цепочек называет себя, и счётчик копится за матч.
    // Повод — пять правил за сутки, которые прошли гейт и не исполнились ни разу: по коду нельзя было
    // сказать, какая ветка живая. Перепись отвечает на это числом, а не чтением. Она же заменяет ручной
    // дубль цепочки в TRACE_WHY, который успел рассинхронизироваться и рассказывал о боте неправду
    val rung = walk(ladder(), turn, ladderTally)
    val whyTag = rung.tag
    val aim = rung.act(turn)
    val stride = Stride(turn, aim)
    val pace = walk(steps(), stride, stepsTally)
    val stepTag = pace.tag
    val step = pace.act(stride)
    // ОБЩАЯ ТРАССА РЕШЕНИЯ (v458, этап 5 второго шага): выигравшая строка и, для каждой строки выше, первый ложный конъюнкт её факта.
    // Строка не повторяет условий — она читает то, что факт сам сказал о себе (см. Why); печатается только в окне (см. traceNow)
    if (tracing) println("trace t=${getTicks()} ${creep.id}@(${creep.x},${creep.y}): rung=${rung.tag} above=[${aboveOf(ladder(), rung)}] step=$stepTag above=[${aboveOf(steps(), pace)}] to=${step?.let { "(${it.x},${it.y})" } ?: "stay"}")
    with(t) { with(turn) { with(stride) {
        val target = aim.target
        val standoff = aim.standoff
        // СЛЕПОТА К ОПАСНОСТИ НА ШАГЕ (v215, оператор: «линия фронта должна работать ВСЕГДА на
        // передвижение»). Пара: шагов в клетку, несущую урон, при ВЫКЛЮЧЕННОМ слагаемом опасности —
        // против всех шагов. Слагаемое выключено в двух местах: вне боя `scoreCell` возвращается до него
        // вовсе, а при агрессии оно обнуляется. Приказ командира слепым не считается: он один и считается
        // по полям (см. scoreMelee/scoreRanged/scoreHeal)
        if (step != null) {
            dangerMoves.n++
            if (pace.mark != RowMark.ORDER && InfluenceMap.dangerAt(step.key) > 0.0) {
                if (!inCombat) dangerBlindFar.n++ else if (localAggressive || spotNow) dangerBlind.n++
            }
        }
        if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
            println("  f${creep.id} (${creep.x},${creep.y}) ${bodySummary(creep)} hits=${creep.hits}/${creep.hitsMax} tgt=(${target.x},${target.y}) so=$standoff flow=$myFlow flee=$mustFlee combat=$inCombat aggr=$localAggressive hold=$hold${if (formHold) "(form)" else if (retreatHold) "(rear)" else ""}${if (leashed) " leash" else ""}${if (stripped) " WOUNDED" else ""}${if (pressTarget != null) " PRESS" else ""} spd=${plainPeriod(creep)} fatigue=${creep.fatigue} step=${step?.let { "(${it.x},${it.y})" } ?: "stay"}${if (TrafficManager.isStuck(creep.id)) " STUCK" else ""}")
        }
        // СОГЛАСОВАНИЕ ДВИЖЕНИЙ — ЗА КОМАНДИРОМ (v170, оператор). Разрешение конфликтов уже устроено правильно:
        // поиск в глубину с цепочками и свопами, по ПРИОРИТЕТУ. Но приоритет задавали разрозненные места — раненый,
        // боец, захватчик, — и замысел в нём не участвовал. Теперь очередь назначает командир: крип, исполняющий
        // приказ, идёт первым, а среди приказов вперёд пропускается тот, чья клетка важнее для боя — мили,
        // выходящий в контакт, затем стрелок с целью, затем лекарь к подопечному, и лишь потом все прочие
        val prio = Arbiter.pushRank(ordered = Orders.commandOf.containsKey(creep.id), melee = meleeOnlyLive(creep),
            armed = hasWeapon(creep), healer = hasHeal(creep), stripped = stripped)
        // ...И ШАГ СТАНОВИТСЯ ПРЕДЛОЖЕНИЕМ (v252, этап 9): решение крипа — значение, которое отдаётся арбитру одним вызовом,
        // с приоритетом и причиной «задание отряда . терм» (терм — ветка шага, а у свободного шага — ступень лестницы)
        submit(Proposal(creep, step, priorityOf(pace.mark, rung.mark), prio, Orders.missionOf[creep.id] ?: '?',
            if (pace.mark == RowMark.FREE) whyTag else stepTag, whyTag, stepTag), ctx, meas.view, t.fistNow)
        Memory.lastHits[creep.id] = creep.hits
        Memory.lastCell[creep.id] = creep.key
    } } }
}

/** Шаг к слоту строя без поля потока: соседняя проходимая клетка, ближайшая к слоту (при равенстве — под меньшим
 *  огнём); занятая своим — через трафик, если только она ближе; клетки banned (досягаемость для лекаря) закрыты. */
internal fun slotStep(creep: Creep, slot: Position, blockedSet: Set<Int>, enemyPositions: Set<Int>, occupantAt: Map<Int, Creep>, enemies: List<Creep>, banned: Set<Int>): Position? {
    val here = getRange(creep, slot)
    if (here == 0) return null
    var best: Position? = null
    var bestD = here
    var bestFire = Double.MAX_VALUE
    var push: Position? = null
    var pushD = here
    for ((dx, dy) in dirsNow()) {
        if (dx == 0 && dy == 0) continue
        val x = creep.x + dx; val y = creep.y + dy
        if (!passable(x, y, blockedSet, enemyPositions) || (key(x, y)) in banned) continue
        val c = InfluenceMap.cell(x, y)
        val d = getRange(c, slot)
        if (occupantAt[key(x, y)] != null) { if (d < pushD) { pushD = d; push = c }; continue }
        val fire = InfluenceMap.damageAt(x, y, enemies)
        if (d < bestD || (d == bestD && fire < bestFire)) { bestD = d; bestFire = fire; best = c }
    }
    return best ?: push
}

internal fun bestSingleMove(
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
    healerFireW: Double = HEALER_W_DAMAGE,
    focus: Creep? = null,
): Position? {
    val hereDist = flow[creep.key]
    // своя клетка с форой — только ПРИБЫВ (в зазоре standoff): вне боя не дёргаемся ради мелочи (см.
    // STAY_BIAS); на марше форы нет — вместе со штрафом за соседей она съедала выигрыш шага (матч 2)
    val settled = !inCombat && hereDist in 0..(standoff + ARRIVED_SLACK)
    var bestScore = scoreCell(creep, creep.x, creep.y, target, flow, standoff, aggressive, inCombat, enemyCreeps, allies, meleeEnemies, healerFireW, focus) + (if (settled) STAY_BIAS else 0.0)
    var bx = creep.x; var by = creep.y
    var pushDist = if (hereDist >= 0) hereDist else Int.MAX_VALUE
    var pushX = -1; var pushY = -1
    var blockedByStatic = false
    val stuck = TrafficManager.isStuck(creep.id)
    for ((dx, dy) in dirsNow()) {
        if (dx == 0 && dy == 0) continue
        val x = creep.x + dx; val y = creep.y + dy
        if (!passable(x, y, blockedSet, enemyPositions)) continue
        val occ = occupantAt[key(x, y)]
        if (occ != null) {
            val fd = flow[key(x, y)]
            val static = TrafficManager.wasStatic(occ.id) || !canMove(occ)
            // лекарь и раненый уступают и в бою (TrafficManager: заявитель приоритетнее стоящего — swap): раненый,
            // «прибывший» к лекарю, стоял на единственной клетке между тремя лекарями в кармане у стены и их
            // подопечным, и лечения не было (стенд m7 sleeper); лекаря толкает только вооружённый
            val yielding = canMove(occ) && stripped(occ) && healerOnly(creep)
            if (yielding && fd in 0 until pushDist) { pushDist = fd; pushX = x; pushY = y }
            else if (fd in 0 until (if (hereDist >= 0) hereDist else Int.MAX_VALUE) && (static || stuck)) blockedByStatic = true
            else if (!inCombat && fd in 0 until pushDist) { pushDist = fd; pushX = x; pushY = y }
            continue
        }
        val s = scoreCell(creep, x, y, target, flow, standoff, aggressive, inCombat, enemyCreeps, allies, meleeEnemies, healerFireW, focus)
        if (s > bestScore) { bestScore = s; bx = x; by = y }
    }
    // МИЛИ БЬЁТ ИЗ ТИХОЙ КЛЕТКИ (v222, см. USE_MELEE_QUIET_CELL): выбранная клетка оставляет мили вплотную к его цели —
    // из клеток, где он так же бьёт ту же цель (своя и свободные соседние), берётся та, куда приходит меньше чистого
    // урона. Прибор считает и при выключенном тумблере: сколько раз правка увела бы и сколько опасности сняла бы
    if (inCombat && aggressive && meleeOnlyLive(creep) &&
        (target.key) in enemyPositions && maxOf(abs(bx - target.x), abs(by - target.y)) <= 1) {
        mquietAll++
        val chosen = InfluenceMap.netDamageAt(bx, by, enemyCreeps, allies)
        var qx = bx; var qy = by; var qd = chosen
        for ((dx, dy) in dirsNow()) {
            val x = creep.x + dx; val y = creep.y + dy
            if ((x == bx && y == by) || maxOf(abs(x - target.x), abs(y - target.y)) > 1) continue
            if ((dx != 0 || dy != 0) && (!passable(x, y, blockedSet, enemyPositions) || occupantAt.containsKey(key(x, y)))) continue
            val d = InfluenceMap.netDamageAt(x, y, enemyCreeps, allies)
            if (d < qd) { qd = d; qx = x; qy = y }
        }
        if (qx != bx || qy != by) {
            mquietMoved++; mquietGain += chosen - qd
        }
    }
    if (bx != creep.x || by != creep.y) return InfluenceMap.cell(bx, by)
    if (pushX >= 0) return InfluenceMap.cell(pushX, pushY)
    if (blockedByStatic && hereDist >= 0) {
        // ...и ОПАСНОСТЬ УЧАСТВУЕТ И ЗДЕСЬ (v215): это единственный шаг, который считался по чистому потоку,
        // мимо оценки клетки. При равном расстоянии по потоку берётся клетка под меньшим огнём — тай-брейк, а
        // не приоритет: выбраться из-за статической помехи всё равно важнее
        var dx0 = 0; var dy0 = 0; var best = hereDist + DistanceMap.SWAMP_COST; var bestDan = Double.MAX_VALUE
        for ((dx, dy) in dirsNow()) {
            if (dx == 0 && dy == 0) continue
            val x = creep.x + dx; val y = creep.y + dy
            if (!passable(x, y, blockedSet, enemyPositions) || occupantAt.containsKey(key(x, y))) continue
            val fd = flow[key(x, y)]
            val dan = InfluenceMap.dangerAt(key(x, y))
            if (fd in 0..best && (dx0 == 0 && dy0 == 0 || fd < best || (fd == best && dan < bestDan))) {
                best = fd; bestDan = dan; dx0 = dx; dy0 = dy
            }
        }
        if (dx0 != 0 || dy0 != 0) return InfluenceMap.cell(creep.x + dx0, creep.y + dy0)
    }
    return null
}

/** Оценка клетки: приблизиться на standoff к цели по реальному пути; в бою — исходящий урон, чистый
 *  входящий (с хилом), влияние, штраф за зону мили, за болото (без перевеса) и цена прижатия. */
internal fun scoreCell(creep: Creep, x: Int, y: Int, target: Position, flow: IntArray, standoff: Int, aggressive: Boolean, inCombat: Boolean, enemyCreeps: List<Creep>, allies: List<Creep>, meleeEnemies: List<Creep>, healerFireW: Double = HEALER_W_DAMAGE, focus: Creep? = null): Double {
    val flowDist = flow[key(x, y)]
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
    // лекарь и раненый без штрафа за соседей: их место — вплотную к своему (штраф 4 за соседа в зоне прибытия
    // против выигрыша шага 10 держал лекаря в 3–4 клетках от подопечного, матч 8)
    if (!inCombat) return -firePenalty * PAIR_W_DIST - (if (!hasWeapon(creep) || flowDist > standoff + ARRIVED_SLACK) 0.0 else separation)

    val damage = InfluenceMap.netDamageAt(x, y, enemyCreeps, allies)
    // ЖЁСТКИЙ ПОТОЛОК (v215, см. USE_LETHAL_CELL_VETO): два тика урона по штампованному полю против хитов
    // крипа. Стоит ДО ветки лекаря — она возвращается раньше, а лекарь под смертельным залпом это те самые
    // «пара крипов, покалеченных первым же ударом». Не запрет, а вес: когда смертельны все восемь клеток и
    // своя, порядок между ними остаётся осмысленным
    // ...и считается ЧИСТЫЙ урон, тот же, что и в самой оценке: штампованное поле не знает про наше лечение, и
    // крип в кулаке из трёх лекарей объявлял смертельной клетку, которую лекари держат. Замер поймал это одной
    // строкой: match28:farm+weak 24 003:14 553 -> 22 407:23 999, то есть выигранный флаговый забег стал
    // проигранным — крипы переставали вставать на спорные флаги. Одна величина опасности на две надобности
    val lethalNow = damage * 2.0 >= creep.hits
    if (lethalNow) lethalHits.n++          // счётчик — оператором, не внутри выражения (v448, линт чистых инициализаторов)
    val lethalTerm = if (lethalNow) LETHAL_PENALTY else 0.0
    lethalCells.n++
    // лекарь: вплотную к подопечному (поле), из клеток равной близости — под меньшим ФАКТИЧЕСКИМ огнём (fireAt:
    // без шага сближения мили — иначе клетка рядом с бойцом, который рубится вплотную, «стоит» 720 и лекарь
    // стоит в трёх клетках; от мили, что действительно подошёл, лекарь отойдёт следующим тиком)
    if (!hasWeapon(creep)) {
        val fire = InfluenceMap.fireAt(x, y, enemyCreeps)
        val pinnedHealer = (periodAt(creep, x, y) - 1) * fire * PAIR_W_DAMAGE
        // вплотную к мили врага — прочь (см. HEALER_W_MELEE): штраф в 2 клетках бил по всем клеткам у подопечного, когда
        // мили стоял по другую его сторону, и лекарь уходил на 4 (вне даже дистанционного лечения) — стенд m2 rush
        val meleeReach = meleeEnemies.count { getRange(InfluenceMap.cell(x, y), it) <= 1 } * HEALER_W_MELEE
        return -firePenalty * PAIR_W_DIST - fire * healerFireW - meleeReach - (0.0) - pinnedHealer - lethalTerm
    }
    // клетка «как для бьющего мили» — угроза его мили в двух не штрафуется, а исходящий считается «есть кто вплотную» — только
    // пока есть ЖИВАЯ ATTACK (v452, пункт Д): раздетый мили бить не может и платит за его мили в двух, как стрелок. Написание Б
    val meleeSelf = meleeOnlyLive(creep)
    val meleeWeight = if (aggressive) PAIR_W_MELEE * AGGRO_MELEE_FACTOR else PAIR_W_MELEE
    val meleeThreat = if (meleeSelf) 0.0 else meleeEnemies.count { getRange(InfluenceMap.cell(x, y), it) <= MELEE_KEEP_RANGE } * meleeWeight
    val swampPenalty = if (!aggressive && DistanceMap.isSwamp(x, y)) PAIR_W_SWAMP else 0.0
    val influence = if (meleeSelf && aggressive) 0.0 else InfluenceMap.influenceAt(x, y, allies, enemyCreeps)
    val outgoing = if (!aggressive && damage > 0.0) 0.0 else if (meleeSelf) (if (enemyCreeps.any { getRange(InfluenceMap.cell(x, y), it) <= 1 }) 1.0 else 0.0) else if (hasRanged(creep)) outgoingValue(x, y, enemyCreeps) else 0.0
    // КЛЕТКА СТРЕЛКА ЗНАЕТ ПРО ФОКУС (v218, см. USE_CELL_KNOWS_FOCUS). Выбор цели сведён на одну по всей
    // армии и работает каждый тик (commandFire не гейтится cmdMode) — не сведены НОГИ: ни одна ветка шага
    // не знала слова `focusTarget`, а `outgoingValue` ЦЕЛЕАГНОСТИЧЕН (суммирует rangedRate по ВСЕМ врагам
    // в трёх). Стоять в трёх от фокуса и в трёх от случайного лекаря было одинаково хорошо — отсюда живые
    // 1,67–1,94 ствола на цель при нужных четырёх-пяти.
    // Единицы надбавки — те же, в каких `outgoing` и меряется: «ещё один ствол по цели, которая решает»,
    // поэтому веса не заводится. Форма — существующее ядро K_ATT_RANGED (InfluenceMap.attractionTo), у него
    // ПИК РОВНО НА ТРОЙКЕ, то есть стрелок останавливается на своей дальности, а не влезает в радиус мили.
    // Нормируется на targetValue, чтобы осталась чистая форма ядра, а не ценность конкретной цели.
    // ⚠️ Это НЕ отвергнутый USE_PRESS_RING: там менялась сама ЦЕЛЬ движения (target/standoff на кольцо), и
    // стрелки расползались по девяти клеткам радиуса — шесть разоружённых к 80-му тику (m18 rush). Здесь
    // target/standoff/поток прежние, сдвигается только выбор среди девяти соседних клеток, и слагаемое
    // честно конкурирует с separation (4 за соседа) и meleeThreat (50).
    // ⚠️ Оговорка про опасность сохранена дословно: там, где `outgoing` обнуляется как плата за стояние под
    // огнём без агрессии, надбавка тоже равна нулю — иначе фокус начал бы оплачивать опасную клетку
    val focusPull = 0.0

        // редакция клала чистое ядро (0,90–1,00 единицы `outgoing`), то есть около 30 очков против 10 за шаг —
        // три шага. Стрелки съезжали с назначенных клеток, и правка ОТМЕНИЛА выигрыш яруса пробиваемой цели:
        // семь строк потеряли уничтожение армии врага (match30:fourteen t=114 -> t=1157, match33:fourteen
        // t=122 -> t=1152), хотя стволов на цель стало 1,74 против 1,63. Это и есть провал USE_PRESS_RING в
        // другой одежде. Шаг по потоку стоит PAIR_W_DIST, единица `outgoing` стоит PAIR_W_OUTGOING, поэтому
        // «фокус весит один шаг» — это в точности их отношение
        (PAIR_W_DIST / PAIR_W_OUTGOING)
    // АГРЕССИЯ МАСШТАБИРУЕТ ОПАСНОСТЬ, А НЕ ОБНУЛЯЕТ (v215, решение оператора). Множитель берётся
    // существующий — тот же AGGRO_MELEE_FACTOR, которым агрессия уже режет штраф за зону мили; новой
    // константы здесь заводить нечего. Разница не косметическая: при уроне 600 член оценки был 0, стал 54,
    // а шаг по потоку стоит 10 — то есть напор сохраняется, но клетка под залпом перестаёт быть бесплатной
    // ...и НЕ ВЕЗДЕ, А ЗА ЛИНИЕЙ ФРОНТА. Первая редакция брала скидку по всей карте и уронила гейт одной
    // строкой: match28:farm+weak 24 003:14 553 -> 22 407:23 999, причём НАШ счёт почти не изменился, а ЕГО
    // вырос с 14 553 до 23 999 — то есть мы не перестали брать флаги, мы перестали давить на его. Это и есть
    // цена платы за опасность там, где мы сильнее. Линия фронта — это `influenceOf`: где наш залп перебивает
    // его, агрессия остаётся бесплатной и напор цел; где перебивает он, опасность считается даже при агрессии,
    // и «первым же ударом калечит пару наших» становится дорогим шагом. Одно обращение к штампованному массиву
    val damageTerm = if (!aggressive) damage * PAIR_W_DAMAGE
        else if (InfluenceMap.influenceOf(key(x, y)) < 0.0)
            damage * PAIR_W_DAMAGE * AGGRO_MELEE_FACTOR
        else 0.0
    val pinned = (periodAt(creep, x, y) - 1) * InfluenceMap.fireAt(x, y, enemyCreeps) * PAIR_W_DAMAGE
    // ...притяжение к ПРИКАЗУ (v168, множитель orderPull) снято в v261: крип с приказом до свободного шага не доходит —
    // его перехватывает ветка order цепочки шага, и множитель здесь всегда был единицей
    return -firePenalty * PAIR_W_DIST - damageTerm + influence * PAIR_W_INFLUENCE +
        (outgoing + focusPull) * PAIR_W_OUTGOING - meleeThreat - separation - swampPenalty - pinned - lethalTerm
}

internal fun outgoingValue(x: Int, y: Int, enemyCreeps: List<Creep>): Double {
    var massValue = 0.0
    var anyInRange = false
    for (enemy in enemyCreeps) {
        val d = getRange(InfluenceMap.cell(x, y), enemy)
        if (d <= RANGED_RANGE) { anyInRange = true; massValue += InfluenceMap.rangedRate(d) }
    }
    if (!anyInRange) return 0.0
    return maxOf(massValue, 1.0)
}

internal fun passable(x: Int, y: Int, blockedSet: Set<Int>, enemyPositions: Set<Int>): Boolean {
    if (x < 0 || y < 0 || x > 99 || y > 99) return false
    val key = key(x, y)
    if (key in blockedSet || key in enemyPositions) return false
    return !DistanceMap.isTerrainWall(x, y)
}


/** ЦЕЛИ ТИКА ДЛЯ ТАКТИКА (v256, этап 10; сегмент runArmy): позиции и занятость, фокус огня (focusTarget, focusOrder), добыча, захватчик цели, авангард и готовность строя, досягаемость его стволов (reachCells, reachNow). Перенесено дословно. */
internal class ArmyTargets(ctx: Ctx, meas: ArmyMeasures, strat: ArmyStrategy) {
    val pool = TargetsPool(ctx, meas, strat)
    val focus = TargetsFocus(ctx, meas, strat, pool)
    val quarry = TargetsQuarry(ctx, meas, strat)
    val takers = TargetsTakers(ctx, meas, strat)
    val form = TargetsForm(meas, takers)
    val zones = TargetsZones(ctx, meas)
}

/** ПОДСТАДИЯ 1 ЦЕЛЕЙ: позиции и занятость клеток, его мили, пул огня — кто в дальности, сколько огня достаёт цель, цели-скауты. */
internal class TargetsPool(private val ctx: Ctx, private val meas: ArmyMeasures, private val strat: ArmyStrategy) {
    val enemyPositions = meas.forces.enemyCreeps.mapTo(HashSet()) { it.key }
    val blockedSet: Set<Int> = ctx.blocked.mapTo(HashSet()) { it.key } + ctx.flagCells
    val meleeEnemies = meas.forces.enemyCreeps.filter { InfluenceMap.profileOf(it).melee > 0.0 }

    // фокус-файр: добиваемые за тик -> наибольшая угроза на хит (урон, который враг СЕЙЧАС наносит нам, плюс
    // его лечение, делённые на его хиты: мили вплотную за 1000 хитов снимает 90, стрелок за 800 — 40, лекарь
    // за 600 — 36; «лекари первыми» без учёта хитов вело огонь мимо мили, который резал наш строй)
    private val inFireRange = meas.forces.enemyCreeps.filter { e -> strat.inp.combatArmy.any { it.getRangeTo(e) <= RANGED_RANGE } }
    // остовы (без оружия и лечения) — вне пула, пока есть боевые. Матч 22: разоружённый M6 с 542 хитами простоял
    // 25 тиков в 2–4 клетках от наших стрелков необстрелянным и был вылечен обратно в M8A8, пока пять его стрелков
    // добивали наших. ДВА способа перенести на него огонь ОТВЕРГНУТЫ стендом: «отрастающая угроза» (мёртвые части
    // оружия × 0.5 в угрозе любой цели) проиграла m9 hunter, m3 army и все рубки sleeper; «остов, добиваемый за
    // два залпа, — сразу после добиваемых за тик» проиграла m3 army, рубки sleeper и m18 spread. Огонь по живой
    // угрозе, а не по раненым, — то, на чём стенд стоит; остов на лечении врага — открытая находка
    private fun fireAvailable(e: Creep) = ctx.army.filter { it.getRangeTo(e) <= RANGED_RANGE }.sumOf { InfluenceMap.profileOf(it).ranged } +
        ctx.army.filter { it.getRangeTo(e) <= 1 }.sumOf { InfluenceMap.profileOf(it).melee }
    // ОСТОВ, КОТОРЫЙ УМИРАЕТ ОТ ОДНОГО ЗАЛПА, — НЕ РАЗМЕН, А БЕСПЛАТНОЕ УБИЙСТВО (v210, оператор по записи:
    // «выбили все боевые части и перестали их добивать, за счёт чего они прошли мимо нас к своей второй половине,
    // где был лекарь, и вылечились до полного здоровья»). Пока у врага жив лекарь, выбитая часть не убрана, а
    // выключена: единственный способ убрать её насовсем — убить крипа. Замер по пяти матчам v208: 13
    // восстановлений; r6m6 простоял разоружённым 42 тика, из них 29 в НАШЕЙ ДАЛЬНОСТИ ВЫСТРЕЛА, с минимумом в
    // 16 хитов — залп по нему стоил одного тика огня и вернул бы шесть стрелковых частей навсегда.
    // ⚠️ Это НЕ третья попытка тех двух, что стенд уже отверг. «Отрастающая угроза» и «остов в два залпа сразу
    // после добиваемых за тик» ставили остов в очередь ПРОТИВ живой угрозы — здесь он входит в пул ТОЛЬКО когда
    // умирает от уже доступного залпа, то есть попадает ровно в верхний ярус `focusCmp` («добиваемые за тик»),
    // где живой угрозе он не мешает: та в этом ярусе стоит по своей же угрозе выше.
    // ⚠️ И это НЕ притяжение: правка v209 (цена цели по потенциалу тела) свой прибор не сдвинула ни на стенде
    // (137 -> 130 восстановлений на 135 сценариях), ни живьём (2,6 -> 3,0 на матч) и снята.
    // СКАУТ У ФЛАГА — ЦЕЛЬ ЦЕНОЙ ОДНОГО ЗАЛПА (v214). Форма взята у killableHulk и она уже проверена живьём:
    // срабатывала 739 раз в 102 логах из 135, то есть в очередь встаёт и стреляет. Остова она не спасала лишь
    // потому, что его лечили обратно, — у скаута этой причины нет, он умирает насовсем и перестаёт брать флаги.
    // Его угроза (threatOf) равна нулю, поэтому внутри верхнего яруса focusCmp он стоит ПОСЛЕДНИМ: любой живой
    // добиваемый враг выше. Условие «на флаге или в шаге от него» не даёт огню уходить в скаута, гуляющего мимо.
    private val scoutTargets = inFireRange.filter { e ->
        scoutFoe(e) && ctx.flags.any { !it.ours && getRange(e, it.pos) <= 1 } &&
            e.hits <= fireAvailable(e) * InfluenceMap.takenOf(e)
    }
    init { scoutShots.n += scoutTargets.size }
    val focusPool = (inFireRange.filter { e -> meas.forces.combatEnemies.any { it.id == e.id } } + scoutTargets)
        .ifEmpty { inFireRange }
    fun fireAvailableAt(e: Creep) = fireAvailable(e)
}

/** ПОДСТАДИЯ 2 ЦЕЛЕЙ: мера цели (лечение на ней, угроза, тики до убийства) и выбор фокуса с удержанием (`focusTarget`, `focusOrder`). */
internal class TargetsFocus(private val ctx: Ctx, private val meas: ArmyMeasures, private val strat: ArmyStrategy, private val pool: TargetsPool) {
    // лечение, которое враг получит на этой цели: вплотную — полное, на дистанции — треть (rangedHeal 4 против 12)
    // ...И САМА ЦЕЛЬ ТОЖЕ ЛЕЧИТ СЕБЯ (v266, разбор стены лечения по реплеям серии v263). Сумма шла по всем, КРОМЕ цели, и
    // его лекарь под нашим огнём выглядел пробиваемым, хотя лечит себя в 52–71 % таких тиков (против Coldkimchi; у ●ω<♥♪ —
    // 21 %): модель завышала чистый урон по лекарю на +29 в тик, и 39 % тиков, где лекарь «пробиваем», им не были. Ровно на
    // этой ложной посылке стоят ярусы «лекарь в досягаемости — первым» (v224) и «лекарь, из-за которого цель не умирает»
    // (v134) — обоим нужен конечный killTicks. С самолечением ложных «пробиваемых» ноль. Добиваемость в commandFire
    // (`killable`) самолечение уже считала — её сумма идёт по всем врагам, включая саму цель; здесь это та же модель, а не
    // вторая. Каскадный пересчёт по реплеям: чистый урон армии в тик против Coldkimchi +8,9 → +22,2 при его лечении как
    // было, −9,8 → −7,9 при его лекарях, успевающих перераспределиться; против ротации ●ω разницы нет
    private fun healOn(e: Creep) = meas.forces.enemyCreeps.filter { h -> getRange(h, e) <= HEAL_RANGE }.sumOf { h -> val q = InfluenceMap.profileOf(h); if (getRange(h, e) <= 1) q.heal else q.heal / 3.0 }
    // дистанция каждого врага до ближайшего нашего боеспособного сейчас и тик назад — «идёт ли» (см. threatOf)
    private val prevArmedRange = HashMap(Memory.lastArmedRange)
    init { Memory.lastArmedRange.clear() }
    init { for (e in meas.forces.enemyCreeps) Memory.lastArmedRange[e.id] = strat.inp.combatArmy.minOfOrNull { getRange(e, it) } ?: 99 }
    private fun threatOf(e: Creep): Double {
        val p = InfluenceMap.profileOf(e)
        // мили — полная угроза ВПЛОТНУЮ к нашему или в двух, когда ИДЁТ на нас (v41: дистанция до наших боеспособных
        // меньше, чем тик назад). В двух он считался живым на все 240 (в четыре стрелка) всегда, и в
        // стоячей линии наш огонь шёл в него: по реплеям трёх проигрышей при его вооружённом стрелке в трёх наши выстрелы
        // уходили в стрелка на 39 / 36 / 46 % (матчи 67, 53, 43), остальное в мили и лекарей; его — в наших стрелков на
        // 65 / 70 / 62 %. Его мили в 2–3 от наших за бой махнули 56 раз на четверых (0,06 в тик — 13 урона), его стрелки
        // дали 480 выстрелов (34 в тик на каждого); мили лечился и вставал обратно в строй, а стрелки жили вооружёнными
        // 875 крип-тиков против наших 413 — и выстрелов 535 против 258 при равной дисциплине (цель в трёх 67 % против
        // 55 %, фокус 0,73 против 0,72). Вошедший вплотную мили — 240 в тот же тик (фокус считается каждый тик), а в
        // двух он — скидка кайта, как и в трёх. Чистая скидка в двух (без «идёт») на гейте: линии (wing) лучше на
        // 1,4–9,9 тыс. очков, но против атакующих пять уничтожений стали лидерством (rush/nine/fourteen/block, m5 army):
        // идущий мили в двух вплотную уже в следующий тик, и тик его огня без фокуса — цена, которую стенд заметил
        val r = Memory.lastArmedRange[e.id] ?: 99
        val meleeLive = p.melee > 0.0 && (r <= 1 || (r <= MELEE_KEEP_RANGE && (prevArmedRange[e.id] ?: 99) > r))
        val rangedLive = p.ranged > 0.0 && strat.inp.combatArmy.any { getRange(e, it) <= RANGED_RANGE }
        // лекарь в угрозе — треть лечения ВСЕГДА (24 против 60 у стрелка): «живой» лекарь при раненом соседе весил 72 и
        // собирал четверть-треть нашего огня (матч 44: 66 из 192, матч 45: 46 из 175, при HEALER_VALUE 1.0), пока けろびー
        // тратил на лекарей 6–15 % и снимал наших стрелков (96 выстрелов по ним против наших 24 по его). Лекаря бьют,
        // когда его добить за тик (первый ярус фокуса) или когда в дальности нет вооружённых (focusOrder)
        return (if (meleeLive) p.melee else p.melee * MELEE_KITE_DISCOUNT) + (if (rangedLive) p.ranged else p.ranged * 0.5) +
            p.heal / 3.0 * HEALER_VALUE
    }
    // тики до убийства нашим огнём в дальности за вычетом их лечения на цели; бесконечность — цель не убиваема
    fun killTicks(e: Creep): Double {
        val net = pool.fireAvailableAt(e) * InfluenceMap.takenOf(e) - healOn(e)
        return if (net <= 0.0) Double.POSITIVE_INFINITY else e.hits / net
    }
    // фокус: добиваемые за тик, затем наибольшая угроза, снимаемая за тик боя (угроза / тики до убийства), и лишь
    // потом угроза на хит — «угроза на хит» слала огонь в лекарей врага за строем, которых лечили друг друга
    // быстрее, чем мы били (стенд m2 rush: выигранный без потерь рывок стал разгромом)
    private fun armedRanged(e: Creep?) = e != null && InfluenceMap.profileOf(e).ranged > 0.0
    // его мили, стоящий вплотную к кому-то из наших: он бьёт ПРЯМО СЕЙЧАС на 240 в тик (v135)
    private fun meleeHitting(e: Creep?) = e != null && InfluenceMap.profileOf(e).melee > 0.0 &&
        strat.inp.combatArmy.any { getRange(e, it) <= 1 }
    // его лекарь: живое лечение и никакого живого оружия (v134, см. USE_FOCUS_HEALER_FIRST)
    private fun armedHealer(e: Creep?) = e != null && InfluenceMap.profileOf(e).heal > 0.0 &&
        InfluenceMap.profileOf(e).ranged == 0.0 && InfluenceMap.profileOf(e).melee == 0.0
    // лекарь, из-за которого цель не умирает: в HEAL_RANGE от вооружённого врага, которого нам не убить
    private fun savesSomeone(h: Creep) = pool.focusPool.any { c -> c.id != h.id && InfluenceMap.profileOf(c).let { it.melee + it.ranged > 0.0 } &&
        getRange(h, c) <= HEAL_RANGE && killTicks(c).isInfinite() }
    // стволов, достающих цель (v70, см. USE_FOCUS_GUNS)
    private fun gunsAt(e: Creep?) = if (e == null) 0 else strat.inp.combatArmy.count { hasRanged(it) && it.getRangeTo(e) <= RANGED_RANGE }
    private val focusCmp = compareBy<Creep> { if (it.hits <= pool.fireAvailableAt(it) * InfluenceMap.takenOf(it)) 1 else 0 }
        // ЛЕКАРЬ В ДОСЯГАЕМОСТИ — ЦЕЛЬ ПЕРВЫМ (v224, см. USE_FOCUS_ANY_HEALER): правило соперника, снятое с реплеев
        // обеих сторон, — его ствол при нашем лекаре в досягаемости бьёт лекаря в 82–97 % выстрелов
        // ...И ТОЛЬКО ТОТ ЛЕКАРЬ, КОТОРОГО ДОТЯГИВАЮЩИЙСЯ ОГОНЬ ПРОБИВАЕТ (v224, серия): без этого условия ярус
        // ставил выше стрелка с пятью стволами лекаря в глубине его строя с одним стволом — рейтинговое поражение от
        // けろびー#1 (v223 — победа 21 242 : 1 455): 167 тиков фокуса на лекаре, его лечащих частей 18/18 весь бой,
        // убийств 0, армия в ноль на 360-м. Условие — то же `killTicks`, что у яруса v134: огонь в дальности
        // за вычетом лечения на цели больше нуля
        // ⚠️ ...И ПРОБИВАЕМОСТЬ ЛЕКАРЯ ПО ДОСТИЖИМОМУ ОГНЮ ОТВЕРГНУТА ЖИВЬЁМ (v394 -> v395). Мера была расширена
        // здесь же на `killTicksReach`, и прибор пошёл ровно туда, куда задумано: `fhl` (фокус лёг на его лекаря
        // при лекаре под стволами) с 0/13 до 12/19..25/26, его лечащих частей в победах осталось 3,0 против 5,1,
        // его крипов живо 4,0 против 5,1. Но счёт не сдвинулся (6-10 против базы 7-9 на 16 играх), и замер назвал
        // цену: стволов на цель в окне боя 2,09 против 2,69 в победах и 2,32 против 2,51 в поражениях — его лекари
        // стоят СЗАДИ, и правильная цель оказалась менее достижимой. Выигрыш в выборе разменян на потерю
        // концентрации, итог нулевой. Мера `fireReachable` остаётся — на ней стоит ярус сходимости ниже (v395)
        // ...и первоначальная запись замера (v394), ради чисел: разбор 55 матчей против MetalicaX#9
        // (44 поражения, 11 побед) нашёл различитель и он один: до контакта группы не расходятся ничем (AUC по всем
        // приборам максимум 0,60 — выходим одинаково, встречаемся в одной точке на том же тике), а с ПЕРВОГО тика
        // размена полученный урон одинаков, тогда как нанесённый отличается в 1,6 раза (828 против 519). Механизм —
        // его лечащие части: в поражениях они не падают ВОВСЕ (18/18 на тридцатом тике боя в 30 матчах из 44), и его
        // армия отрастает — стрелковых частей 16 -> 24 -> 27 -> 30, потому что лечение возвращает части. В победах
        // его лечение уходит в ноль к сороковому тику, и разделитель на тридцатом («его лечащих частей не больше
        // двенадцати») даёт 11 побед из 11 против 2 поражений из 44. Прибор решения — `fhl`: доля тиков, где фокус
        // лёг на его лекаря, при том что лекарь стоял в пуле И наши стволы его достают, — 0,50 в победах против
        // 0,09 в поражениях при ОДИНАКОВОЙ возможности (573 тика, где лекарь был под стволами, а стреляли в другого;
        // против MetalicaX#13 проигранный матч печатает ровно `fhl=0/13`). Запирал ярус `killTicks` по мгновенному
        // огню: его трое лекарей стоят сомкнуто и дают до 216 лечения в тик, наша концентрация в этом окне 2,47-2,70
        // ствола, разница между победой и поражением — ОДИН ствол (5 из 5 в дальности против 4 из 5). Поэтому мера
        // берётся по огню, который дотянется за шаг. ⚠️ Защита v224 остаётся на месте и она здесь единственная,
        // что удерживает от возврата к отвергнутому: `gunsAt(it) > 0` требует, чтобы хотя бы один ствол доставал
        // УЖЕ СЕЙЧАС, а дальность расширена ровно на шаг, — лекарь в глубине его строя (пять клеток и дальше) не
        // проходит, как не проходил и в v224, где такой ярус дал 167 тиков фокуса на недостижимом лекаре и армию в
        // ноль на 360-м тике
        .thenBy { if (armedHealer(it) && gunsAt(it) > 0 && (!killTicks(it).isInfinite())) 1 else 0 }
        // ДОТЯГИВАЮЩИЕСЯ СТВОЛЫ НЕ РАСХОДЯТСЯ ПО ТРЁМ ЦЕЛЯМ (v218, см. USE_FOCUS_BREAKABLE_FIRST). Порог
        // записан в файле пятикратно — «при 216 лечения в тик цель пробивают четыре-пять стволов», — но
        // числом его писать не нужно: «стволов хватает» тождественно «сумма того, что уже дотягивается,
        // превышает лечение цели», а это у бота посчитано ровно один раз — конечным `killTicks` (см. там же:
        // net <= 0 даёт бесконечность). Поэтому ярус читает готовую величину и НЕ вводит ни одной константы.
        // Почему ярус нужен ЗДЕСЬ, а не ниже: `killTicks` уже участвует в сравнении, но на пятом ярусе, под
        // «стрелки первыми» (v60) и под `gunsAt` (v70), — и неубиваемый стрелок обгонял цель, которую мы
        // реально пробиваем. Живой замер v217: `conc` 1,67–1,94 ствола на цель при нужных четырёх-пяти
        // ...первый срез «всякий снимаемый лекарь выше стрелков» ОТВЕРГНУТ стендом: гейт 131, но входы +20 хуже 11 / лучше 9,
        // +50 хуже 15 / лучше 7, m34 split 21 210:24 100 → 15 365:24 095. Второй срез: не «лекарь вообще», а ТОТ, ИЗ-ЗА КОГО
        // цель не умирает — лекарь в HEAL_RANGE от неубиваемого кандидата (живьём его лекарь у нашей цели 62–83 % тиков)
        .thenBy { if (armedHealer(it) && !killTicks(it).isInfinite() && savesSomeone(it)) 1 else 0 }
        // стрелки первыми (v60, см. USE_FOCUS_RANGED_FIRST), и вместе с ними — его мили, КОТОРЫЙ УЖЕ БЬЁТ (v135,
        // см. USE_FOCUS_MELEE_IN_CONTACT): внутри яруса порядок решает угроза за тик, а там мили вплотную (240) выше
        // стрелка (60) сам собой
        .thenBy { if ((armedRanged(it))) 1 else 0 }
        // ⚠️ СХОДИМОСТЬ СТВОЛОВ ЗА ШАГ ОТВЕРГНУТА ЖИВЬЁМ (v395 -> v396), и отвергнута своим же прибором: против
        // MetalicaX#13 счёт 3-13 против базы 7-9 на шестнадцати играх, стволов на цель в окне боя 1,88 против 2,69
        // в победах — то есть мера не выросла, а УПАЛА. Причина названа замером: ярус выбирает цель, до которой
        // надо ИДТИ, а расстановка этот шаг не исполняет — она решает свою задачу (очаг и флаги), поэтому стрелки
        // стреляют по выбранной цели с краю или не стреляют вовсе. Вместе с v244/v246 (прогноз в опасность, 0-8 и
        // 0-8) и v394 (прогноз в пробиваемость лекаря, 6-10) это ТРЕТЬЯ форма одной ошибки: прогноз меняет ОЦЕНКУ,
        // не меняя того, кто и куда идёт, — и каждый раз оценка уходит вперёд исполнения. Ниже — прежний ярус v70
        // ...и прежняя запись v395, ради чисел: ярус считал стволы, достающие цель
        // В ЭТОТ ТИК, то есть мгновенный снимок, из которого не видно, сойдётся наш огонь в следующем тике или
        // разойдётся. Между тем предмет измерен и он один: исход решают первые шесть тиков размена, а в них —
        // гонка за его СТРЕЛКОВЫМИ частями (снято к шестому тику: 16,8 в победах против 9,0 в поражениях, d=1,93),
        // тогда как мили-размен в обоих исходах одинаков. Снятие частей упирается в концентрацию: каждый наш
        // стрелок даёт 60 урона, в окне боя на цель сходится 2,5 ствола (2,69 в победах против 2,51 в поражениях),
        // а лечение цели у него 129-147 в тик — то есть 2,5 ствола дают 150 и стоят РОВНО НА ГРАНИ, а три ствола
        // дают 180 и пробивают. Не хватает одного ствола, и мгновенный счёт этот ствол не видит: цель, до которой
        // стрелку шаг, считается недостижимой, хотя к следующему тику она под огнём. ⚠️ Это НЕ адресная опасность
        // (v244, v246): те две пробы подставляли прогноз в ОПАСНОСТЬ клетки и обе отвергнуты живьём (0-8 и 0-8)
        // за то, что обнуляли у бойцов чувство угрозы. Здесь прогноз идёт в ВЫБОР ЦЕЛИ, опасность не трогает
        // вовсе, и судится своим механическим прибором — стволов на цель в окне боя
        .thenBy { gunsAt(it) }
        .thenBy { val t = killTicks(it); if (t.isInfinite()) 0.0 else threatOf(it) / t }
        // чистый урон по цели: наш огонь в дальности минус её лечение (вплотную — полное, на дистанции — треть). Когда
        // никого не убить (лечение везде не меньше огня), «угроза на хит» слала огонь в того, кого лечат три лекаря
        // вплотную; выше — цель, к которой лекари ДАЛЕКО (оператор, 05.09.2026: «лекари к ней далеки — не вылечат
        // близким хилом и потеряют ход») и которую достают больше наших стволов
        .thenBy { threatOf(it) / it.hits.coerceAtLeast(1) }
        .thenByDescending { it.hits }
        .thenByDescending { getRange(it, strat.threats.centroid) }
    private val focusBest = pool.focusPool.maxWithOrNull(focusCmp)
    // прибор яруса «лекарь первым» (v224): его лекарь в досягаемости наших стволов был / фокус лёг на лекаря
    // ...и прибор v266 (fself=): его лекарь в досягаемости наших стволов, которого модель без самолечения читала
    // пробиваемым, а с ним — нет, то есть сколько решений о добиваемости правка поменяла
    init {
        for (e in pool.focusPool) if (armedHealer(e) && gunsAt(e) > 0) {
            fselfAll.n++
            val fire = pool.fireAvailableAt(e) * InfluenceMap.takenOf(e)
            val own = InfluenceMap.profileOf(e).heal
            val others = healOn(e) - own
            if (fire - others > 0.0 && fire - others - own <= 0.0) fselfFlip.n++
        }
    }
    init { if (pool.focusPool.any { armedHealer(it) && gunsAt(it) > 0 }) { fhlAvail.n++; if (focusBest != null && armedHealer(focusBest)) fhlChosen.n++ } }
    // ЛИПКИЙ фокус (v45): цель держится, пока жива с оружием или лечением и в шаге от досягаемости хоть одного нашего стрелка;
    // сменяется на ту, что добивается за тик. Замер по реплеям (матчи 78, 73, 67): наибольшее число наших выстрелов в ОДНУ
    // цель за тик — 1 в 57 тиках из 111, 2 в 42, 3 в 10, четыре и больше в 2 (1 %); у Coldkimchi 4+ в 11 % тиков, у けろびー
    // в 14 %. При 216 лечения в тик пробивают только четыре-пять стволов в одну цель — мы этого не делали почти никогда:
    // цель фокуса менялась, ряд стоял поперёк оси, каждый стрелок доставал своего (оператор: «нет фокус-файра — каждый
    // рэндж стреляет в своего соперника»)
    // ЛИПКОСТЬ РАБОТАЕТ, КАК ЗАПИСАНА (v267, наблюдение оператора: «фокус часто меняется — мы одного бьём, потом кидаем,
    // бежим к другому, а тот за это время лечится»). Правило v45 держит цель, пока она «в шаге от досягаемости хоть
    // одного нашего стрелка», — но прежнюю цель искали в focusPool, а это враги НЕ ДАЛЬШЕ трёх (inFireRange): цель,
    // шагнувшая на четыре, сбрасывалась в тот же тик, и льгота «в шаге» не срабатывала никогда. Реплеи серии v263 и рук
    // v266 (модель выбора повторяет наши выстрелы на 94–98 %): фокус меняется 37–45 раз на 100 тиков контакта, средняя
    // серия 1,9 тика, 64–68 % смен — «цель ушла на четыре» (в 85–88 % из них — ровно на четыре); 61 % сброшенных
    // возвращаются в три за три тика, а фокус к ним — лишь в 33–38 %. Теперь прежняя цель ищется и среди живых боевых
    // врагов вне досягаемости, а её стволы для правила v70 считаются тем же «в шаге» (gunsNear): у цели на четырёх в
    // досягаемости ноль стволов, и moreGuns сбрасывал бы её тем же тиком
    private val focusPrevId = focusId
    private val focusPrev = focusId?.let { id -> pool.focusPool.firstOrNull { it.id == id } ?: meas.forces.combatEnemies.firstOrNull { it.id == id } }
    private fun gunsNear(e: Creep) = strat.inp.combatArmy.count { hasRanged(it) && it.getRangeTo(e) <= RANGED_RANGE + 1 }
    private val killableNow = focusBest != null && focusBest.hits <= pool.fireAvailableAt(focusBest) * InfluenceMap.takenOf(focusBest)
    // …и не к мили, чья угроза схлопнулась (v49): матч 91 (Coldkimchi, 430 тиков боя) — его мили тычет вплотную (угроза 240,
    // фокус на нём), отходит к лекарям, и фокус на нём держится: 395 выстрелов в мили под 727 его лечений вплотную, 101 в
    // стрелков (35 % при стрелке в трёх, у него 71 %). Мили держится, пока вплотную или идёт (см. threatOf); «отпускать
    // всякую неубиваемую цель, пока есть убиваемая» ОТВЕРГНУТО стендом — фокус перескакивал при каждом шаге его лекаря
    // (125/125, но 19 хуже / 12 лучше: screen+flagless m32 −16145, block+flagless m32 −7640, camp m34 −9294, кайтеры медленнее ×7)
    // липкость уступает стрелку (v60): прежняя цель не стрелок, а лучшая — вооружённый стрелок
    private val rangedNow =  armedRanged(focusBest) && !armedRanged(focusPrev)
    // липкость уступает цели, которую достают на FOCUS_GUNS_SWITCH стволов больше (v70)
    private val moreGuns =  focusBest != null && focusPrev != null && gunsAt(focusBest) >= gunsNear(focusPrev) + FOCUS_GUNS_SWITCH
    private val prevArmed = focusPrev != null && InfluenceMap.profileOf(focusPrev).let { it.melee + it.ranged + it.heal > 0.0 }
    private val prevNear = focusPrev != null && strat.inp.combatArmy.any { hasRanged(it) && getRange(it, focusPrev) <= RANGED_RANGE + 1 }
    val focusTarget = if (focusPrev != null && !killableNow && !rangedNow && !moreGuns && prevArmed && prevNear) focusPrev else focusBest
    init { focusId = focusTarget?.id }
    // прибор v267 (fsw=смен/тиков:ушла/далеко/стрелок/стволы/добиваем/раздета): смена фокуса и её причина — на тиках, где
    // есть кого бить в досягаемости
    init {
        if (pool.focusPool.isNotEmpty()) {
            fswTicks.n++
            if (focusPrevId != null && focusTarget?.id != focusPrevId) {
                fswN.n++
                when {
                    focusPrev == null -> fswLost.n++
                    !prevNear -> fswFar.n++
                    killableNow -> fswKill.n++
                    rangedNow -> fswRanged.n++
                    moreGuns -> fswGuns.n++
                    else -> fswBare.n++
                }
            }
        }
    }
    private val packMelee = pureMeleeOf(strat.inp.combatArmy)
    init {
        if (packMelee.isNotEmpty() && meas.forces.combatEnemies.isNotEmpty()) packTicks.n++
        // ранжир для бойца, у которого цель фокуса вне дальности: ПЕРВАЯ по ранжиру цель в его дальности, а не «самый раненый в
    }
    // дальности» — тот размазывал огонь: 1.91 цели в тик, 66 из 192 выстрелов в лекарей при HEALER_VALUE 1.0 (матч 44)
    val focusOrder = pool.focusPool.sortedWith(focusCmp.reversed())
    val occupantAt = HashMap<Int, Creep>()
    init { for (c in ctx.active) occupantAt[c.key] = c }
}

/** ПОДСТАДИЯ 3 ЦЕЛЕЙ: добыча армии — ближайший по пути боевой враг; в бою по контакту — только тот, кто уже в руках. */
internal class TargetsQuarry(private val ctx: Ctx, private val meas: ArmyMeasures, private val strat: ArmyStrategy) {
    // добить: цель армии — ближайший к центру армии боевой враг (по пути); в бою ПО КОНТАКТУ (без перевеса) —
    // только враг, который УЖЕ у нас в руках (в RANGED_RANGE + 2 от своих): стая «в 11 клетках» включала основную
    // массу врага, и контакт с одним забредшим мили увёл армию с дома на неё — бой при 0.97 проигран 12:1 (матч 13)
    private val contactPack = meas.forces.combatEnemies.filter { e -> strat.inp.combatArmy.any { getRange(e, it) <= RANGED_RANGE + 2 } }
    // расстояние до КАЖДОГО кандидата в добычу мерилось своим полем BFS, а поля считаются под бюджетом
    // (см. BFS_BUDGET): сверх него кандидат получает ограниченное (NEAR_FLOW) или устаревшее поле, и «до него
    // неизвестно» читается как «бесконечно далеко». Сравнения кандидатов при этом нет вовсе — есть сравнение
    // качества полей. Стенд m18 roost: два скаута по разным углам карты, цель армии мигала (13,49)/(90,8) КАЖДЫЙ
    // ТИК, армия шагала туда-сюда и простояла так до конца матча — армия врага перебита к 203-му, а матч проигран
    // по очкам 17683:24331; в живом матче 25 армия ровно так же простояла 990 тиков при добыче в 11 клетках.
    // ОДНО поле от центра армии меряет всех кандидатов в одних единицах и в один тик — и стоит дешевле, чем поле
    // на каждого кандидата
    private val preyField by lazy { flowTo(ctx, strat.threats.centroid) }
    // враг НА клетке вражеского флага недостижим по полю (такая клетка в нём стена) — читаем по соседней: нам
    // нужно дойти ДО него, а не встать на него
    private fun travelTo(e: Creep): Int {
        var best = -1
        for (dx in sym(1)) for (dy in sym(1)) {
            val x = e.x + dx; val y = e.y + dy
            if (x !in 0..99 || y !in 0..99) continue
            val d = preyField[key(x, y)]
            if (d >= 0 && (best < 0 || d < best)) best = d
        }
        return if (best < 0) Int.MAX_VALUE / 2 else best
    }
    // за недостижимым не гонимся: прежде «недостижим» и «очень далеко» были одним числом, и армия шла на цель,
    // до которой нет пути
    private fun nearestPrey(list: List<Creep>): Creep? =
        list.filter { travelTo(it) < Int.MAX_VALUE / 4 }.minByOrNull { travelTo(it) }
    val prey = when {
        posture != Posture.ANNIHILATE -> null
        strat.thr.sweep -> nearestPrey(meas.forces.enemyCreeps)
        pushing -> nearestPrey(meas.chase.huntable)
        else -> nearestPrey(contactPack.filter { catchable(it, meas.chase.chasers) })
    }
}

/** ПОДСТАДИЯ 4 ЦЕЛЕЙ: центр вооружённых, захватчик флага-цели и попутные захватчики ближних флагов. */
internal class TargetsTakers(private val ctx: Ctx, private val meas: ArmyMeasures, private val strat: ArmyStrategy) {
    val armedCentroid = clusterCentroid(armedOf(meas.chase.mobileArmy).ifEmpty { ctx.army }) ?: strat.threats.centroid
    // захватчик флага-цели — ближайший к флагу ВООРУЖЁННЫЙ член группы (одной клетки на всех не хватит; лекарь
    // ходит за подопечным, и назначенный захватчиком лекарь тысячу тиков стоял рядом с флагом — стенд greedy)
    val objectiveCapturer = strat.obj.objective?.let { o -> armedOf(meas.chase.mobileArmy).ifEmpty { meas.chase.mobileArmy }.minByOrNull { getRange(it, o.flag.pos) }?.id }
    // флаг рядом (не наш, свободный, без врага в дальности, разрешён) — на него шагает ближайший из наших
    val grabberOf = HashMap<String, String>()
    init {
        for (f in ctx.flags) {
            if (f.ours || f.occupant != null || f.id == objectiveFlagId) continue
            if (meas.forces.combatEnemies.any { getRange(it, f.pos) <= RANGED_RANGE + 1 }) continue
            if (!captureAllowed(ctx, f, meas.view, CapAsker.ARMY)) continue
            // ...и НЕ ЛЕКАРЬ (v215, см. USE_HEALER_NEVER_PINNED): тот же отбор, что строкой выше у захватчика цели
            val near = meas.chase.mobileArmy.filter { getRange(it, f.pos) <= 3 && (hasWeapon(it)) }
                .minByOrNull { getRange(it, f.pos) } ?: continue
            grabberOf[near.id] = f.id
        }
    }
}

/** ПОДСТАДИЯ 5 ЦЕЛЕЙ: авангард построения, собранность и готовность строя (с терпением ожидания). */
internal class TargetsForm(private val meas: ArmyMeasures, private val takers: TargetsTakers) {
    // построение перед контактом (см. FORM_RANGE): авангард — ближайший к врагу ходячий вооружённый; готовность —
    // доля вооружённых в RALLY_RANGE от него, собравшихся в FORM_RANGE; клетки под огнём — в дальности стрелка
    // враг здесь — С БОЕМ (см. threatening): построение собирается перед огнём, а у одинокого лекаря огня нет. Уцелевший
    // лекарь врага шёл за армией в семи клетках, «авангардом» становился ЗАДНИЙ боец, и построение тянуло армию назад,
    // а цель — вперёд: шаг туда, шаг обратно 140 тиков у (20,21) при флаге-цели в 30 (стенд m13 rush, v30)
    private val formers = armedOf(meas.chase.mobileArmy)
    // авангард — только из массы (см. MASS_RANGE): оторвавшийся крип не точка сбора
    private val formMass = formers.filter { getRange(it, takers.armedCentroid) <= MASS_RANGE }.ifEmpty { formers }
    // авангард есть, только пока враг с боем в досягаемости броска от кого-то из строя: авангард «против врага где-то на
    // карте» при одиноком лекаре врага в одиннадцати клетках давал построение, которому не собраться (штраф за соседей
    // отталкивал мили от авангарда в блобе) и не дождаться терпения (оно считается по врагу с боем) — армия простояла
    // тысячу тиков в 25 клетках от флага-цели (стенд m30 block, v30)
    val formVan = if (meas.forces.armedEnemies.none { e -> formers.any { getRange(e, it) <= ENGAGE_RANGE + RANGED_RANGE } }) null
        else formMass.minWithOrNull(compareBy<Creep>({ f -> meas.forces.armedEnemies.minOf { getRange(f, it) } }, { it.id }))
    private val formationGathered = formVan == null || run {
        val formVan = formVan!!     // поле носителя внутри лямбды инициализатора компилятор не сужает, как сужал локальную; непустота — левее, в этом же `||`
        val near = formers.filter { getRange(it, formVan) <= RALLY_RANGE }
        val needed = maxOf(2, ceil(FORM_SHARE * near.size).toInt())
        // ком — только у неподвижной цели (см. USE_FORM_CLUMP_STILL_ONLY): его вооружённые в досягаемости авангарда стоят CHASE_WINDOW
        val vanFoes = meas.forces.armedEnemies.filter { getRange(it, formVan) <= ENGAGE_RANGE + RANGED_RANGE }
        // ...и это БЛОК, а не одиночка фермера на флаге (v133d): не меньше SPLIT_MIN неподвижных в ENGAGE_RANGE друг от друга
        val foesStill = vanFoes.size >= (SPLIT_MIN) && vanFoes.all { e ->
            val h = Memory.enemyCellHist[e.id]
            h != null && h.size >= CHASE_WINDOW && maxOf(abs(h.first() / 100 - h.last() / 100), abs(h.first() % 100 - h.last() % 100)) <= 1
        } && (vanFoes.any { e -> vanFoes.count { getRange(e, it) <= ENGAGE_RANGE } >= SPLIT_MIN })
        if ((!foesStill)) near.count { getRange(it, formVan) <= FORM_RANGE } >= needed
        else {
            // ком (см. USE_FORM_CLUMP): в FORM_RANGE от авангарда, или вплотную к собранному и не дальше FORM_RANGE + 1
            val gathered = near.filter { getRange(it, formVan) <= FORM_RANGE }.toMutableList()
            var grew = true
            while (grew) {
                grew = false
                for (f in near) if (gathered.none { it.id == f.id } && getRange(f, formVan) <= FORM_RANGE + 1 && gathered.any { getRange(f, it) <= 1 }) { gathered.add(f); grew = true }
            }
            gathered.size >= needed
        }
    }
    private val formWaiting = formVan != null && !formationGathered && meas.forces.armedEnemies.any { e -> formers.any { getRange(e, it) <= ENGAGE_RANGE + RANGED_RANGE } }
    // терпение — с появления авангарда, а не с последнего несобранного тика (v120, USE_FORM_PATIENCE_FROM_VAN): авангард,
    // шагнувший к цели, сам ломал построение (нужны 5 из 6 в двух клетках от него, оставалось 4), по formGo шагал назад,
    // построение собиралось, он шагал снова — цикл в два тика 1300 тиков на spread m31 (20579:24316) при таймере
    // терпения, сбрасываемом каждым собранным тиком
    init { if (!formWaiting) formWaitSince = -1 else if (formWaitSince < 0) formWaitSince = getTicks() }
    val formationReady = formationGathered || (formWaitSince >= 0 && getTicks() - formWaitSince >= FORM_PATIENCE)
}

/** ПОДСТАДИЯ 6 ЦЕЛЕЙ: досягаемость врага для лекаря и раненого, клетки огня, живы ли лекари, слоты тика. */
internal class TargetsZones(private val ctx: Ctx, private val meas: ArmyMeasures) {
    // досягаемость врага для лекаря и раненого (см. reachCells): стрелок бьёт на 3, мили шагнёт и ударит на 2. Тело
    // лекаря HHHHHHMMMMMM — лечение впереди, и первое же попадание снимает 12 лечения в тик навсегда; наши лекари
    // входили в зону огня к подопечному и к 130-му были M6H4/M6H2, лекари врага за строем не получили ни царапины
    // и отрастили ему армию (матчи 14–15). Лекарь в зону не входит, изнутри уходит; раненые приходят к нему сами.
    val reachCells = HashSet<Int>()
    init {
        for (e in meas.forces.combatEnemies) {
            val q = InfluenceMap.profileOf(e)
            val r = if (q.ranged > 0.0) RANGED_RANGE else if (q.melee > 0.0) 2 else 0
            if (r == 0) continue
            for (dx in sym(r)) for (dy in sym(r)) {
                val x = e.x + dx; val y = e.y + dy
                if (x in 0..99 && y in 0..99) reachCells.add(key(x, y))
            }
        }
    }
    // в контактном бою лекарь избегает только мили (2 клетки): «вне досягаемости стрелков» выкидывало слот тыла из
    // ряда back+1, лекари стояли в 4–6 клетках за фронтом, и мили гибли без лечения, пока лекари врага стояли у своей
    // линии под нашим огнём (матч 21, t=68–84: четыре мили за шестнадцать тиков, ни одного лечения)
    // только вплотную к мили (клетка в 2 — это ряд сразу за нашим фронтом, свой мили между ними)
    private val meleeReachCells = HashSet<Int>()
    init {
        for (e in meas.forces.combatEnemies) {
            if (InfluenceMap.profileOf(e).melee <= 0.0) continue
            for (dx in sym(1)) for (dy in sym(1)) {
                val x = e.x + dx; val y = e.y + dy
                if (x in 0..99 && y in 0..99) meleeReachCells.add(key(x, y))
            }
        }
    }
    // ...И В КОНТАКТЕ ЛЕКАРЬ ТОЖЕ ВНЕ ДОСЯГАЕМОСТИ ЕГО СТРЕЛКОВ (v293) — вместе с выходом раненых к нему (stepOutWounded), то
    // есть его система целиком. Аудит 112 игр (v289–v290): урон по нашим лекарям 11,7 хита в тик контакта против 0,6 по его,
    // под его огнём наши 8,2 % тиков, его 2,2 %; его лекари стоят в 5,0–5,7 клетки от наших вооружённых, а его раненые
    // отходят к ним сами (+1,84 клетки за три тика против наших +0,42). Каждая половина порознь отвергнута: лекарь вне
    // досягаемости без выхода раненых — мили гибли без лечения (матч 21, эта строка до v293), выход раненых без лекарей
    // позади — v290 (5-11 против Coldkimchi#1). Здесь обе
    // ...И ТОЛЬКО ПРОТИВ ТОГО, КТО ОХОТИТСЯ ЗА РАНЕНЫМИ (v294, см. huntsWounded). Живьём v293 — 13-19 против ●ω<♥♪#6 (у v289
    // 15-33, у v292 8-24) и 2-14 против Coldkimchi#1 (у v289 10-6, p ≈ 0,01): против бьющего наименьшую долю раненому надо
    // уходить к лекарям позади, против бьющего «лекаря, иначе ближайшего» уведённый из боя раненый — огонь, потерянный даром.
    // Правило его стволов бот мерит сам (сверка двух моделей с фактом), это не подгонка под имя
    // ...И БЕЗ ПЕРВОГО РЯДА ДОСЯГАЕМОСТЬ СНОВА ПОЛНАЯ (v515, см. USE_REACH_FULL_WITHOUT_FRONT). Сужение до мили
    // (v293/v294) стоит ради того, чтобы лекарь не бросал фронт: пока свои мили дерутся, лечить их надо вплотную.
    // Но когда у армии не осталось НИ ОДНОЙ живой части ATTACK, фронта нет вовсе — бросать нечего, а лекарь стоит
    // под его стрелками без всякого прикрытия. Ступень бегства для этого уже есть (`supportInReach` в `mustFlee`),
    // закрыт был только вход в неё: `inReach` считается по этому набору, а тот в контакте не знает о его стрелках.
    // Замер восьми реплеев (21.09.2026, проверка оператора по эшелонам): из 566 событий «он выстрелил в нашего
    // лекаря» он стоял >= 2 клеток от нашего ближайшего живого мили в 100 %, с дистанции 1 — НОЛЬ; а разделяет
    // исходы наличие первого ряда вообще — «живого мили нет ни одного» 36/79/35/50 % тиков в поражениях против
    // 0/20/0/0 % в победах, и на эти тики приходится 61-93 % всех выстрелов по нашим лекарям
    val noFront = meas.forces.allies.none { hasMelee(it) }
    init { nofrontAll.n++; if (noFront) nofrontN.n++ }
    // ...И КОГДА ЕГО СТВОЛЫ АДРЕСУЮТСЯ ЛЕКАРЮ (v518, см. USE_HEALERS_OUT_OF_REACH и healersOutOfReach): это тот же
    // набор, что даёт ступень бегства `supportInReach`, поэтому одна строка закрывает и вход в зону, и выход из неё
    val reachNow = if (!meas.fight.contact || TacticianState.huntsWounded || TacticianState.healersOutOfReach ||
        (USE_REACH_FULL_WITHOUT_FRONT && noFront)) reachCells else meleeReachCells
    private val fireCells = HashSet<Int>()
    init {
        for (e in meas.forces.combatEnemies) for (dx in sym(RANGED_RANGE)) for (dy in sym(RANGED_RANGE)) {
            val x = e.x + dx; val y = e.y + dy
            if (x in 0..99 && y in 0..99) fireCells.add(key(x, y))
        }
    }

    val healersAlive = ctx.army.any { healerOnly(it) && canMove(it) }
    // строй рядами в бою по контакту (см. USE_BLOCK); при перевесе (добивание) — прежняя охота
    val slotOf = HashMap<String, Position>()
}

internal const val FOCUS_GUNS_SWITCH = 2

internal const val LETHAL_PENALTY = 1e6      // не запрет, а вес: если смертельны все клетки, порядок между ними цел

/** Построение перед контактом: боец не входит в дальность врага (≤ RANGED_RANGE от боевого врага), пока у
 *  авангарда (ближайшего к врагу ходячего вооружённого) в FORM_RANGE клетках не соберётся доля FORM_SHARE
 *  вооружённых, что в RALLY_RANGE от него; кто дальше — идёт к авангарду, не заходя в огонь. Матч 4: колонна
 *  с марша входила в блоб врага по одному — melee_2 один внутри двенадцати, стрелки в 4–5 клетках, лекари
 *  дальше, минус два мили за шесть тиков при равной силе; прежнее «любой напарник в бою снимает ожидание»
 *  и было командой «в атаку по одному». */
internal const val FORM_RANGE = 2

/** Цена уцелевшего тела в оценке симуляции (v138): аннигиляция — поражение при любом счёте, значит крип дороже
 *  своего оружия. Величина в тех же единицах, что профиль: 240 — удар мили, то есть тело весит примерно один удар. */
internal const val KITE_STANDOFF = MELEE_HOLD_RANGE   // ОТСКОК (порог 2, отход на 3) отвергнут: 0-6 и 1-5 против 5-7 и 2-10

internal const val MELEE_COVER = 2         // стрелков в RANGED_RANGE + 1 от цели, чтобы мили пошёл на неё (v55)

internal const val REGROUP_TICKS = 5

internal const val FORM_SHARE = 0.75

/** Дольше стольких тиков строй не ждёт: кто мог подойти — подошёл. Без этого готовность могла не наступить
 *  никогда — в двух клетках от авангарда на кромке огня семерым нет места, и армия 1300 тиков стояла в
 *  четырёх клетках от лагеря врага в постуре ДОБИТЬ, проигрывая по очкам (стенд m1 grab). */
internal const val FORM_PATIENCE = 10

/** Плотность строя при враге рядом (см. compact): шаг разрешён только на клетку в COMPACT_RANGE от центра
 *  вооружённой армии или с двумя вооружёнными соседями. Авангард один впереди не шагает на врага — ждёт линию;
 *  линия идёт вперёд линией. Матчи 4, 6, 7: первый размен всякий раз проигран 0:2, потому что мили-авангард
 *  дрался один, а стрелки в трёх-пяти клетках за ним ждали «готовности» и не стреляли. */
internal const val COMPACT_RANGE = 2

/** Терпение сплочения: кто держится по сплочению дольше стольких тиков, идёт дальше, пока отставание не
 *  рассосётся. Четвёртый тупик взаимного ожидания (грабер, сбор, бросок, а теперь болото): группа, разрезанная
 *  болотной полосой и стенным блоком, ждала друг друга по кругу 1500 тиков при 10:11 по очкам (стенд m9 kite);
 *  правила ожидания не видят, кто кого держит, — предохранитель по времени видит. */
internal const val COHESION_PATIENCE = 30

/** Отход строем: когда самый отставший вооружённый отстал (по полю отхода) от ТЕЛА армии — медианы по полю —
 *  дальше стольких клеток, передняя половина ждёт его вне огня: иначе погоня добивает отставших по одному
 *  (матч 4: армия рассыпалась по трём углам). Мерить от самого быстрого нельзя: глубина блоба из двенадцати по
 *  ходу — 3–4 клетки, голова стояла через тик, стоящая голова блокировала колонну за собой (см. bestSingleMove:
 *  статичный впереди — боковой шаг), хвост отставал ещё больше, и уход от равного по скорости врага шёл на
 *  0,5 клетки/тик — догнан в 46 тиках при запасе 13 (стенд m11 sleeper). */
internal const val RETREAT_GAP = 3

/** ЦЕНА ОГНЯ ПО ЛЕКАРЮ — ПОТОК, А НЕ РАЗОВАЯ ВЕЛИЧИНА (v520, см. USE_HEALER_DANGER_FLOW). Вес выводится, а не
 *  назначается. В шкале оценки клетки лекаря «шаг к подопечному» стоит 10 (см. HEALER_W_DAMAGE ниже), а стоит он
 *  прироста доставки с 24 до 72, то есть 48 лечения — значит единица шкалы это примерно 4,8 лечения. Сто урона по
 *  лекарю убивает часть HEAL, а часть — это 12 лечения в тик, и не на один тик, а до конца матча: за горизонт
 *  HEALER_FLOW_TICKS это 12 × 10 = 120 лечения, то есть 25 единиц шкалы на каждые 100 урона — вес 0,25.
 *  Прежние 0,02 и 0,05 отвечают горизонту меньше одного тика, то есть считают потерю частей разовой. */
internal const val HEAL_POWER_PER_PART = 12.0
internal const val HEALER_STEP_VALUE = 4.8
internal const val HEALER_FLOW_TICKS = 10
internal const val HEALER_W_FLOW = HEAL_POWER_PER_PART * HEALER_FLOW_TICKS / (100.0 * HEALER_STEP_VALUE)

/** Вес фактического огня в оценке клетки ЛЕКАРЯ: шаг к подопечному (10) стоит двух стрелков (120 → 6), трёх
 *  уже нет. Тело H×6 M×6 теряет лечение с первого попадания — каждые 100 урона это −12 лечения в тик до конца
 *  матча, замены нет, — поэтому лекарь не лезет под сосредоточенный огонь ради 72 вместо 24. Прежняя общая
 *  оценка (урон ×0.3 с шагом сближения мили, зона мили −50) держала лекарей в 2–4 клетках даже без огня и
 *  гнала их прочь (матч 3). Варианты, проверенные стендом sleeper на четырёх временах пробуждения: этот —
 *  10:0 во всех четырёх; «вплотную под любым огнём» (0.02) и «без штрафа за соседа, с отдельным штрафом за
 *  мили вплотную» — 2:12 во всех четырёх (стендовый враг бьёт цель с наименьшими хитами). */
internal const val HEALER_W_DAMAGE = 0.05

/** Штраф лекарю и раненому за клетку вплотную к живому вражескому мили: два с половиной шага пути — шаг прочь,
 *  когда мили подошёл (при равной скорости он бьёт лишь раз; в клетку рядом с врагом лекарь и так не входит). С весом огня 0.005 «только разница» (v9) лекари вставали вплотную к подопечному С ТОЙ СТОРОНЫ, где враг, и
 *  враг бил их первыми — матч 9: два лекаря без HEAL к 140-му тику, а лекари врага в 2–4 клетках за его строем не
 *  получили ни царапины и после боя вернули его армии всё (M8 → M8A8 к 185-му). Лекарь — единственная
 *  невосполнимая потеря: место у подопечного — с дальней от огня стороны (вес огня), от подошедшего мили — прочь. Стенд это правило не любит: его рывок держит лекарей вплотную и по лекарям не бьёт, оттого рубки
 *  sleeper на дебаффах v9 выигрывал вчистую, а v10 — по очкам; живые противники бьют лекарей первыми. */
internal const val HEALER_W_MELEE = 25.0

/** Вес огня для лекаря, чей подопечный УЖЕ в бою. 0.005 (v9) — только подсказка, лекарь шёл под огонь стрелков в
 *  первый ряд (матч 9); 0.05 (v10a) держал его на кромке огня в 2–3 клетках всегда, и рубки sleeper на дебаффах
 *  0.6–0.75 были проиграны; 0.02 (v10) — клетка вплотную сзади выигрывала у отступа. При паритете (v14) обмен
 *  равных армий решает ВЫЖИВШИЙ лекарь: обе стороны остаются без оружия, и отрастает та, у которой лекари целы —
 *  враг держит своих в 2–4 клетках за строем, наши лечили касанием в первом ряду и были расстреляны (матч 13).
 *  Проверено на восьми боях стенда: 0.05 и 0.02 дали ОДИНАКОВЫЕ исходы до тика — бой решает доля мощи к контакту
 *  (см. PARITY_FLOOR), а не этот вес; оставлен 0.02, строй лекарей вне досягаемости стрелков — открытая находка. */
internal const val HEALER_W_DAMAGE_FIGHT = 0.02

internal const val COHESION_GAP = 8

internal const val COHESION_GAP_MAX = 100

internal const val ENGAGE_COHESION_TICKS = 2

internal const val CLOSE_STANDOFF = 2

/** Далёкого напарника медленнее этого (тиков на клетку) авангард не ждёт — калека дойдёт, когда дойдёт. */
internal const val RALLY_MAX_PERIOD = 3

/** Уже дерущийся выходит из боя только при таком местном соотношении (см. localAggressive в ANNIHILATE). */
internal const val ANNIHILATE_HOLD_RATIO = 0.5

/** Вне боя шаг делается только ради заметно лучшей клетки: у поста клетки в зазоре standoff равноценны с
 *  точностью до штрафа за соседа (4), и отряд без порога всё время менялся местами — сотни обменов на сто
 *  тиков в матче 1. Порог меньше выигрыша одной клетки по полю (10) и больше одного соседа. */
internal const val STAY_BIAS = 5.0

// веса оценки клетки (скопированы из spawn-and-swamp, где обкатаны)
internal const val PAIR_W_DIST = 10.0

internal const val PAIR_W_DAMAGE = 0.3

internal const val PAIR_W_INFLUENCE = 0.1

internal const val PAIR_W_OUTGOING = 30.0

internal const val PAIR_W_MELEE = 50.0

internal const val AGGRO_MELEE_FACTOR = 0.3

internal const val PAIR_W_SPREAD = 4.0

internal const val PAIR_W_SWAMP = 40.0

internal const val SEPARATION_RADIUS = 1

/** Тик, с которого строй ждёт готовности (см. FORM_PATIENCE); -1 — не ждёт. */
internal var formWaitSince = -1

internal var focusId: String? = null              // липкая цель фокуса (v45, см. focusTarget)

// ==================== приборы стадии: счётчик живёт у того, кто считает (v447, план архитектуры, 4.7 и этап 6) ====================
// Объявления перенесены из Instruments.kt дословно; Instruments их читает и печатает, текст строк прежний.

/** Крипо-тиков строки `kite` лестницы за матч (v135, диагностика). До v448 — «сколько крипов кайтят в этом тике»: мир
 *  сбрасывал в начале тика (`readSignals`), а считала лестница — счётчик с двумя писателями, объявленный ниже обоих
 *  (находка этапа 6). Теперь он накопительный и живёт у того, кто считает; разницу за тик берёт печать (`kite=`), и
 *  печатаемое число то же. */
internal val kiteNow = Gauges.counter("kite", perTick = true)

/** Ротация по его фокусу (v275, rotf=выходов/возвратов/крипо-тиков в ней/тиков правила/попаданий «доля»/попаданий «лекарь,
 *  ближайший»/сверок). */
internal val rotfOut = Gauges.counter("rotf")

internal val rotfBack = Gauges.counter("rotf", 1)

internal val rotfTicks = Gauges.counter("rotf", 2)

internal val rotfOn = Gauges.counter("rotf", 3)

internal val rotfF = Gauges.counter("rotf", 4)

internal val rotfA = Gauges.counter("rotf", 5)

internal val rotfN = Gauges.counter("rotf", 6)

/** Самолечение в добиваемости (v266, fself=сменилось/всего): его лекарь в досягаемости наших стволов, пробиваемый без
 *  своего лечения и непробиваемый с ним. */
internal val fselfFlip = Gauges.counter("fself")

internal val fselfAll = Gauges.counter("fself", 1)

/** Смены фокуса (v267, fsw=смен/тиков:ушла/далеко/стрелок/стволы/добиваем/раздета): на тиках с целью в досягаемости —
 *  сколько раз фокус сменился и почему: прежней цели нет среди живых боевых, она дальше шага от наших стрелков, лучшая
 *  добивается за тик, лучшая — стрелок (v60), у лучшей больше стволов (v70), прежняя без оружия и лечения. */
internal val fswTicks = Gauges.counter("fsw", 1)

internal val fswN = Gauges.counter("fsw")

internal val fswLost = Gauges.counter("fsw", 2, sep = ":")

internal val fswFar = Gauges.counter("fsw", 3)

internal val fswKill = Gauges.counter("fsw", 6)

internal val fswRanged = Gauges.counter("fsw", 4)

internal val fswGuns = Gauges.counter("fsw", 5)

internal val fswBare = Gauges.counter("fsw", 7)

/** Выход раненого из его зоны (v285, sout=вышло/вернулось/крип-тиков вне): см. stepOutWounded. */
internal val soutOut = Gauges.counter("sout")

internal val soutBack = Gauges.counter("sout", 1)

internal val soutTicks = Gauges.counter("sout", 2)

/** Шаг бойца на клетку чужого флага при закрытых воротах захвата (v282, stray=): столько раз крип остался стоять. */
internal val strayCapRefused = Gauges.counter("stray")

/** Прибор: мили-тиков, где перевес открыл ворота. Пара к edge=, который считает, где их открыть стоило. */
internal val spotMeleeTicks = Gauges.counter("spotm")

internal val rotOut = Gauges.counter("rot")

/** Защёлка ротации (v463, дефект 2): крипо-тики живого бойца в `rotatingIds` (часть 1 поля `rotset=`; часть 0 — размер набора на
 *  печати, в `Instruments.kt`) и «в наборе по пути защёлки при закрытом rotGate» — `rotstuck=по оружию/без лекарей`: часть 0 —
 *  крипо-тики раздетого при живых лекарях (запись остаётся), часть 1 — вынужденные выходы при погибших лекарях. */
internal val rotSetTicks = Gauges.counter("rotset", 1)

internal val rotStuck = Gauges.counter("rotstuck")

internal val rotStuckNoHeal = Gauges.counter("rotstuck", 1)

/** Лекарь вне досягаемости (v234): лекаре-тиков в досягаемости / в бою, урон по лекарям. */
/** Прилегание мили (v501): крипо-тиков вооружённого мили с врагом вплотную / в шаге от вплотную / в бою. */
internal val madjN = Gauges.counter("madj")

internal val madjStep = Gauges.counter("madj", 1)

internal val madjAll = Gauges.counter("madj", 2)

/** ИСТОЧНИК ШАГА ЛЕКАРЯ (v516, `hstep=`): приказ командира (`order`), слот строя (`slotStep`/`slotHold`), свободный
 *  шаг тактика (`free`) или лестница бегства (`flee`) — по какой ветке лекарь реально ходит. Общий `step=` считает
 *  все роли вместе и на вопрос «в том ли месте правка» не отвечает. */
internal val hstepTag = Gauges.labelled("hstep")

/** НЕТ ПЕРВОГО РЯДА (v515, `nofront=` тиков без единой живой части ATTACK / тиков со стадией армии): состояние, на
 *  которое приходится 61-93 % всех его выстрелов по нашим лекарям (замер восьми реплеев 21.09.2026). */
internal val nofrontN = Gauges.counter("nofront")

internal val nofrontAll = Gauges.counter("nofront", 1)

/** ИЗЪЯТИЕ ЛЕКАРЯ БЕЗ ПЕРВОГО РЯДА (v513, `hfront=` отказано / раз, когда изъятие было бы взято): сколько раз
 *  лекарь НЕ получил права войти в досягаемость, потому что живых частей ATTACK у армии не осталось. */
internal val hfrontOff = Gauges.counter("hfront")

internal val hfrontAll = Gauges.counter("hfront", 1)

/** ОХОТА ЗА РАНЕНЫМИ (v509, `hw=` сработало / всего): единственное правило, выводящее лекаря из досягаемости его
 *  стрелков в контакте (см. reachNow). До v509 прибора не имело — `hunt=` это загон, другое правило. */
internal val hwOn = Gauges.counter("hw")

internal val hwAll = Gauges.counter("hw", 1)

/** На чём остановилось правило охоты за ранеными (v509, `hwwhy=`): окно не наполнено / выиграла адресная модель /
 *  мы отстаём по счёту (условие v295) / сработало. */
internal val hwWhy = Gauges.labelled("hwwhy")

/** ЛЕКАРЬ ВНЕ ДОСЯГАЕМОСТИ ЕГО СТРЕЛКОВ (v518, `hor=` сработало / всего): своё правило лекаря, отделённое от охоты
 *  за ранеными. Его собственный вердикт читается вместе с `hexp=` — доля тиков, когда лекарь всё-таки в зоне. */
/** БЕЖАТЬ НЕКУДА (v519, `fuse=` безнадёжных / всего проверок у поддержки в бою): ни одна клетка шага не выходит
 *  из досягаемости его вооружённых после их шага. Прибор правки «лекарь, которому есть кого лечить, не бежит». */
/** СТРЕЛОК ПОД ЕГО МИЛИ (v522, `rnear=` крип-тиков вооружённого стрелка в двух клетках от его живого мили / всего
 *  его боевых крип-тиков): прибор РЕЗУЛЬТАТА стойки `closeIn`, в отличие от `close3`, который меряет её повод. */
/** ПЕРЕРАЗДАЧА КЛЕТКИ (v522, `repass=` сохранено / всего проходов мили, стрелка и раздетого): сколько раз боевая
 *  раскладка НЕ стала затирать клетку, уже выданную проходом отхода или ротации. Прибор правки USE_RETREAT_CELL_STICKS. */
internal val repassKept = Gauges.counter("repass")

internal val repassAll = Gauges.counter("repass", 1)

internal val rnearN = Gauges.counter("rnear")

internal val rnearAll = Gauges.counter("rnear", 1)

internal val fuseN = Gauges.counter("fuse")

internal val fuseAll = Gauges.counter("fuse", 1)

internal val horOn = Gauges.counter("hor")

internal val horAll = Gauges.counter("hor", 1)

internal val hexpN = Gauges.counter("hexp")

internal val hexpAll = Gauges.counter("hexp", 1)

internal val hlostSum = Gauges.counter("hlost")

internal val scoutShots = Gauges.counter("scout")

/** Очаг: тиков-мили с врагом в ENGAGE_RANGE (знаменатель) и из них тех, где МЕСТНАЯ арифметика даёт перевес,
 *  а армейская мера при этом говорит «не наступать». Ненулевой числитель — отпечаток расхождения масштабов. */
internal val edgeSpot = Gauges.counter("edge")

internal val edgeAll = Gauges.counter("edge", 1)

/** Пары к USE_MELEE_QUIET_CELL: шагов мили, где выбранная клетка оставляла удар, и из них тех, где правка увела в клетку
 *  тише; сумма снятой опасности (урон/тик). */
internal var mquietAll = 0

internal var mquietMoved = 0

internal var mquietGain = 0.0

internal val fhlAvail = Gauges.counter("fhl", 1)

internal val fhlChosen = Gauges.counter("fhl")

/** Пара «шагов в клетку под уроном при выключенном слагаемом опасности / всех шагов» (v215). */
internal val dangerBlind = Gauges.counter("aggro")

/** ...и отдельно — та же слепота ВНЕ боя. Ноль здесь не дефект прибора, а арифметика: поле урона достаёт
 *  на 4 клетки, а `inCombat` стоит на 5 (см. USE_DANGER_SCALED_BY_AGGRO). */
internal val dangerBlindFar = Gauges.counter("aggro", 1)

/** Пара «клеток, отвергнутых как смертельные / оценённых клеток» (v215, см. USE_LETHAL_CELL_VETO). */
internal val lethalHits = Gauges.counter("lethal")

internal val lethalCells = Gauges.counter("lethal", 1)

internal val dangerMoves = Gauges.counter("aggro", 2)

/** Пара «крипо-тиков, где стрелку не дали сблизиться до двух из-за живого мили врага / всех крипо-тиков
 *  стрелка в местной агрессии» (v220, см. closeIn). Числитель — сколько раз оговорка вообще сработала. */
internal val closeHeld = Gauges.counter("close3")

internal val closeTicks = Gauges.counter("close3", 1)

/** Пара «крипо-тиков, где ворота броска открыла защита своего / всех крипо-тиков мили при враге рядом»
 *  (v220, см. USE_MELEE_GUARDS_LINE). */
/** Ворота, открытые ТОЛЬКО раздетым мили (v473, прибор правки v472): `gstrip=guardNow/poker` в крипо-тиках. */
internal val gstripGuard = Gauges.counter("gstrip")
internal val gstripPoker = Gauges.counter("gstrip", 1)

internal val guardFired = Gauges.counter("guard")

internal val guardTicks = Gauges.counter("guard", 1)

internal val mpackAll = Gauges.counter("mpack", 1)

internal val packTicks = Gauges.counter("pack", 1)

// ==================== межтиковое состояние и константы стадии (до v454 — члены object PainAndGain; второй шаг архитектуры, этап 1) ====================

internal val NO_FLOW = IntArray(10000) { -1 }

// ==================== приборы стадии, бывшие членами object PainAndGain (v455, второй шаг архитектуры, этап 2) ====================

/** Пара «крипо-тиков мили, чья цель ног — цель фокуса / крипо-тиков мили с целью ног» (v221). */
internal val mpackHit = Gauges.counter("mpack")

internal val orderBranch = Gauges.counter("branch")

internal val orderFled = Gauges.counter("fled")

internal val rungCount = Gauges.labelledOnly("rung")        // перепись решений (v203): какая ветка ЦЕЛИ выбрана, сколько раз

internal val stepCount = Gauges.labelledOnly("step")        // ...и какая ветка ШАГА

/** ОТ КОГО ПРИКАЗ (v486, `rule=`): `step=order` складывал ШЕСТЬ разных источников — раздачу боя, её же без перебора
 *  под страховкой CPU, изготовку, гонку, загон, марш и «одних лекарей», — и по логу нельзя было сказать, где правит
 *  командир, а где марш; ровно на этом я в этот день дважды искал дефект не в том месте. Считается в самих строках
 *  приказа (`order`, `keeperOrder`) меткой [Orders.source], поэтому сумма поля равна числу исполненных приказов, а
 *  остальные управляющие видны в `step=` как прежде. Поставлен по запросу оператора «свести бота к одному
 *  управляющему»: пока источников несколько, их доли надо знать числом, иначе сведение выкинет то, что держит бой
 *  (см. отказы USE_COMMANDER_EVERY_FIGHT / _ALWAYS / _APPROACH, каждый измерен). */
/** Кулак на всяком шаге (v490, `fist=отменённых шагов/шагов в бою`): сколько раз запрет удержал крипа от ухода за
 *  пределы кулака и сколько шагов вообще проходило под запретом. Первая часть и есть цена правки. */
internal val fistHeld = Gauges.counter("fist")

internal val fistAll = Gauges.counter("fist", 1)

internal val ruleCount = Gauges.labelled("rule")

/** ДВОЙНОЕ УПРАВЛЕНИЕ (v487, `dual=приказ при живом слоте/слот прочитан`). Планировщик строя и раздача командира
 *  пишут в РАЗНЫЕ карты (`slotOf` и `Orders.commandOf`) и друг друга не чистят, а спор между ними решается только
 *  внутри покрипного хода — и решается ДВАЖДЫ независимо: на лестнице цели `slotHold` стоит ВЫШЕ приказа, в цепочке
 *  шага приказ стоит ВЫШЕ слота. Комментарий `Formation.kt:754` при этом утверждает, что расстановка при командире
 *  молчит, тогда как условия командира в `armyBlock` нет вовсе. Первая часть поля и есть цена этого: крипо-тики, где
 *  строй уже назначил крипу клетку, а пошёл он по приказу. Прибор стоит ПЕРЕД правкой, потому что без него неизвестно,
 *  сколько стоит её устранение (проект docs/pain-and-gain-one-controller.md, шаг 1). */
internal val dualOrder = Gauges.counter("dual")

internal val dualSlot = Gauges.counter("dual", 1)

internal val tacCount = Gauges.labelledOnly("tac")        // ...и какое «задание.терм» предложено арбитру (v252, прибор tac t=)

internal val prioCount = Gauges.labelledOnly("prio")        // ...и с каким приоритетом (SURVIVE / MISSION / OPPORTUNITY)

/** СОСТОЯНИЕ ТАКТИКА (v459, второй шаг архитектуры, этап 6): сверка модели его выбора цели и журнал печати — как есть. */
internal object TacticianState {
    internal val ghostLogged = HashMap<String, Int>()
    /** Предсказанный урон его стволов по нашим на этот тик — по модели его выбора цели, что чаще попадает (v292, см.
     *  rotateByFocus); null, пока сверок меньше окна. Читает лечение вместо неадресного damageAt. */
    internal var focusPredDmg: Map<String, Double>? = null
    /** Его стволы, по сверке с фактом, бьют нашего с наименьшей долей хитов в досягаемости — охотятся за ранеными (v294,
     *  см. rotateByFocus): тогда раненые уходят к лекарям позади, а лекари стоят вне его досягаемости. */
    internal var huntsWounded = false
    /** Лекарь стоит ВНЕ досягаемости его стрелков (v518, см. USE_HEALERS_OUT_OF_REACH): отдельная величина от
     *  [huntsWounded], потому что решения разные. Уводить РАНЕНОГО из боя стоит только против того, кто бьёт
     *  наименьшую долю хитов; уводить ЛЕКАРЯ — против того, кто бьёт лекаря, то есть в точно противоположном случае. */
    internal var healersOutOfReach = false
}
