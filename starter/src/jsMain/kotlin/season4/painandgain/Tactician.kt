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
import season4.painandgain.PainAndGain.Posture
import season4.painandgain.PainAndGain.Intent
import season4.painandgain.PainAndGain.CmdMode
import season4.painandgain.PainAndGain.Shooter
import season4.painandgain.PainAndGain.Objective
import season4.painandgain.PainAndGain.ChaseSample
import season4.painandgain.PainAndGain.FightCell
import season4.painandgain.PainAndGain.HypoMods

/**
 * ТАКТИК (v251, этап 9 переработки, срез 1 — тождественный перенос; план — docs/pain-and-gain-rework.md, раздел 1).
 * Здесь решается, куда шагнёт каждый боец армии: признаки крипа, лестница цели (27 ступеней: keeper, slotHold, order,
 * chase, kite, wall, healMate, slot, healMateOut, evade, retreat, formGo, wounded, rotate, regroup, alone, leash, engage,
 * holdMelee, grab, toCentroid, prey, rally, objective, threat, raider, post), поле, бегство и сплочение, цепочка шага
 * (immobile, flee, keeperOrder, keeperStay, order, slotHold/slotStep, hold, free) и запрос хода арбитру. Прежде это был
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
 * свободного шага за добычей (ступени engage, holdMelee, prey, threat, raider), MISSION у остального; [rank] — ранг
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
internal fun priorityOf(stepTag: String, rung: String): Priority = when {
    stepTag == "flee" -> Priority.SURVIVE
    stepTag != "free" -> Priority.MISSION
    rung == "engage" || rung == "holdMelee" || rung == "prey" || rung == "threat" || rung == "raider" -> Priority.OPPORTUNITY
    else -> Priority.MISSION
}

/** Отдать предложение арбитру: перепись (прежняя и новая) и запрос хода — в прежнем порядке побочных действий. */
internal fun PainAndGain.submit(p: Proposal, ctx: Ctx) {
    rungCount[p.rung] = (rungCount[p.rung] ?: 0) + 1
    stepCount[p.stepTag] = (stepCount[p.stepTag] ?: 0) + 1
    tacCount[p.why] = (tacCount[p.why] ?: 0) + 1
    prioCount[p.priority.name] = (prioCount[p.priority.name] ?: 0) + 1
    if (p.step != null) { TrafficManager.request(p.creep, p.step, p.rank); planCapture(ctx, p.step) }
}

/** Величины тика, которые покрипный цикл читает из runArmy; все посчитаны до цикла и в нём не меняются. */
internal class ArmyTick(
    val army: List<Creep>,
    val allies: List<Creep>,
    val enemyCreeps: List<Creep>,
    val combatEnemies: List<Creep>,
    val strikers: List<Creep>,
    val armedEnemies: List<Creep>,
    val enemyMassedNow: Boolean,
    val now: Int,
    val mobileArmy: List<Creep>,
    val chasers: List<Creep>,
    val stalled: Boolean,
    val contact: Boolean,
    val objective: PainAndGain.Objective?,
    val evadeTo: Position?,
    val combatArmy: List<Creep>,
    val retreatTo: Position?,
    val post: Position,
    val threat: Creep?,
    val raider: Creep?,
    val enemyPositions: HashSet<Int>,
    val blockedSet: Set<Int>,
    val meleeEnemies: List<Creep>,
    val killTicks: (Creep) -> Double,
    val focusTarget: Creep?,
    val occupantAt: HashMap<Int, Creep>,
    val prey: Creep?,
    val armedCentroid: Position,
    val objectiveCapturer: String?,
    val grabberOf: HashMap<String, String>,
    val formVan: Creep?,
    val formationReady: Boolean,
    val reachCells: HashSet<Int>,
    val reachNow: HashSet<Int>,
    val healersAlive: Boolean,
    val slotOf: HashMap<String, Position>,
    val pressOn: Boolean,
)

/** Ход одного бойца армии: тело прежнего цикла runArmy без изменений (см. заголовок файла). */
internal fun PainAndGain.creepTurn(creep: Creep, ctx: Ctx, t: ArmyTick) {
    with(t) {
        val mobile = strikers.any { it.id == creep.id }
        val healer = !hasWeapon(creep) && hasHeal(creep)
        val slot0 = slotOf[creep.id]
        val keeper = creep.id in Memory.keeperIds
        // раненый (без оружия и лечения, в армии по решению выше): ходит за ближайшим лекарем, в строй не входит
        val wounded = !healer && !hasWeapon(creep)
        // ротация (см. ROTATE_OUT): с гистерезисом, чтобы боец не дёргался у порога
        val rotating =  !healer && hasWeapon(creep) && healersAlive && run {
            val weapons = creep.body.count { it.type == ATTACK || it.type == RANGED_ATTACK }
            val live = creep.body.count { (it.type == ATTACK || it.type == RANGED_ATTACK) && it.hits > 0 }
            val frac = if (weapons == 0) 1.0 else live.toDouble() / weapons
            // возврат на одну часть ВЫШЕ порога выхода (v126, USE_ROTATE_IN_ONE_PART): 0.9 от восьми частей ATTACK — все восемь, то есть
            // полное лечение блока; под огнём у фронта оно не наступает, и мили висит в ротации 50–97 тиков при 1050–1250 хитах
            // (матч 506, melee_4 116–212; 602, melee_3 314–372) — ноль ударов. Его мили (матч 14) вернулся при 5 из 8
            val backIn = live >= kotlin.math.ceil(weapons * ROTATE_OUT).toInt() + 1
            if (creep.id in Memory.rotatingIds) { if (backIn) { Memory.rotatingIds.remove(creep.id); if (DEBUG_LOG) println("rot t=$now in ${creep.id} frac=$frac took=${now - (Memory.rotateSince[creep.id] ?: now)}"); false } else true }
            else if (frac < ROTATE_OUT) {
                Memory.rotatingIds.add(creep.id); Memory.rotateSince[creep.id] = now; rotOut++; if (DEBUG_LOG) println("rot t=$now out ${creep.id} frac=$frac hits=${creep.hits}"); true
            } else false
        }
        val support = healer || wounded
        val nearestEnemyRange = combatEnemies.minOfOrNull { getRange(creep, it) } ?: 99
        val localAllies = combatArmy.filter { getRange(creep, it) <= (if (posture == Posture.ANNIHILATE || posture == Posture.FLAG) ENGAGE_RANGE else RANGED_RANGE + 1) }
        val localEnemies = combatEnemies.filter { getRange(creep, it) <= ENGAGE_RANGE + RANGED_RANGE }
        val ratio = if (creep.id in Memory.aggressiveIds) LOCAL_ENTER_RATIO else PUSH_RATIO
        val ghost = run {
            val prev = Memory.lastHits[creep.id]
            val cell = Memory.lastCell[creep.id]
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
            posture == Posture.RETREAT || posture == Posture.EVADE -> inContact(localEnemies, localAllies) && ourPowerOf(localAllies, localEnemies) >= enemyPowerOf(localEnemies, localAllies) * ratio
            // добивание: армия в целом сильнее (или в контакте без отхода), но ЯВНО слабейшая на месте группа
            // отходит к массе — «всегда агрессивен» посылал четверых на двенадцать (матч 2 на стенде). Вход в
            // бой при 0.9 (при равных силах никто не вступал в бой — рывок врага кончался ничьёй), выход — только
            // при разгроме на месте (ANNIHILATE_HOLD_RATIO): в гуще боя местный счёт скачет, и бойцы по одному
            // «отходили к центру» и гибли поодиночке (стенд rush 7:2 против прежних 0:12)
            // отходить к массе есть смысл, только если масса не здесь: когда рядом больше половины армии, это и
            // есть масса, и «отход к центру» был шагом на месте под ударами (матч 3, t=140–280: армия из
            // семи-восьми «отходила к центру» сто сорок тиков и потеряла всех по одному, не стреляя в ответ)
            posture == Posture.ANNIHILATE -> localEnemies.isEmpty() || localAllies.size * 2 >= combatArmy.size ||
                ourPowerReach(localAllies, localEnemies) >= enemyPowerReach(localEnemies, localAllies) * (if (creep.id in Memory.aggressiveIds) ANNIHILATE_HOLD_RATIO else LOCAL_ENTER_RATIO)
            localEnemies.isEmpty() -> true
            // без боевого своего в четырёх клетках (раненый один) запаса хода нет: maxOf пустого списка бросал
            // NoSuchElementException КАЖДЫЙ тик до конца матча — армия стояла 1500 тиков после выигранного боя
            // (стенд m19 nine, v30; в v29 то же падало в m8 rush t=115–120 и m28 wing t=1200, стенд этого не считал)
            else -> ourPowerOf(localAllies, localEnemies) >= enemyPowerOf(localEnemies, localAllies) * ratio &&
                (fightCost(localEnemies, localAllies) <= (localAllies.maxOfOrNull { speedSlack(it) } ?: 0) || inContact(localEnemies, localAllies))
        }
        if (localAggressive) Memory.aggressiveIds.add(creep.id) else Memory.aggressiveIds.remove(creep.id)
        // ОТПЕЧАТОК РАСХОЖДЕНИЯ МАСШТАБОВ (этап 0): знаменатель — мили с боевым врагом в ENGAGE_RANGE,
        // числитель — из них те, где МЕСТНАЯ арифметика в клетке врага даёт перевес не ниже PUSH_RATIO, а
        // армейская мера при этом говорит «не наступать». Ненулевой числитель и есть наблюдение оператора
        // «трое наших мили боялись подойти к одному чужому», выраженное числом
        if (isMelee(creep) && !hasRanged(creep)) {
            val near = localEnemies.filter { getRange(creep, it) <= ENGAGE_RANGE }
            if (near.isNotEmpty()) {
                edgeAll++
                if (!localAggressive && near.any { spotEdgeAt(it) >= PUSH_RATIO }) edgeSpot++
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
        val spotNow =  isMelee(creep) && !hasRanged(creep) && !support && !rotating &&
            localEnemies.any { e -> getRange(creep, e) <= ENGAGE_RANGE && spotEdgeAt(e) >= PUSH_RATIO && !killTicks(e).isInfinite() }
        if (spotNow) spotMeleeTicks++
        // МИЛИ ЗАЩИЩАЕТ СВОЕГО (v220, см. USE_MELEE_GUARDS_LINE). Ворота `inLine` требуют двух ВООРУЖЁННЫХ
        // своих в FORM_RANGE = 2 — и это спираль: его мили раздевает наших стрелков, раздетый перестаёт быть
        // `hasWeapon`, ворота закрываются, наши мили перестают драться, и он раздевает следующего. Разбор
        // реплеев v219 показывает её прямо: в трёх разгромах трассировка простоя мили даёт `!inLine` 20/22/3
        // и `!covered` 20/15/6 крипо-тиков, а его мили стояли вплотную 53–73 крипо-тика против наших 14–18 —
        // «его мили нашли цели, наши держали линию, которую никто не атаковал».
        // Оговорка узкая и НЕ повторяет отвергнутое «враг в трёх снимает ворота» (blitz 7-1 -> 5-3, пикет
        // фермера снимал их и мили танцевали с пикетом): здесь ворота открывает не близость врага к НАМ, а
        // то, что его вооружённый мили УЖЕ дотянулся до нашего небоевого — стрелка, лекаря или раздетого.
        // Пикет одиночки такого не делает, а блоб делает первым же тиком
        fun guards(e: Creep) = InfluenceMap.profileOf(e).melee > 0.0 &&
            combatArmy.any { a -> a.id != creep.id && !(isMelee(a) && !hasRanged(a)) && getRange(e, a) <= MELEE_KEEP_RANGE }
        val guardNow =  isMelee(creep) && !hasRanged(creep) && !support && !rotating &&
            localEnemies.any { e -> getRange(creep, e) <= ENGAGE_RANGE && guards(e) }
        if (isMelee(creep) && !hasRanged(creep) && localEnemies.isNotEmpty()) { guardTicks++; if (guardNow) guardFired++ }
        val inLine =  spotNow || guardNow || (formationReady && combatArmy.count { it.id != creep.id && hasWeapon(it) && getRange(creep, it) <= FORM_RANGE } >= 2)
        // при бесплодной охоте (см. STALL_TICKS) броска нет: висящие крипы россыпи «ловимы» (не уходят стабильно), и
        // каждый наш крип танцевал со своим соседом вместо марша к флагу-цели (стенд m19 spread, travel=23 четыреста тиков)
        // «держит линию» — про мили В ЛИНИИ, а не про любого мили в бою: без этого условия мили, до которого враг
        // ещё не дошёл, стоял на месте весь бой. Матч 24: двое из четырёх простояли в 4–6 клетках от схватки в полном
        // здравии (M8A8 1600 на 140-м тике), пока двое дрались и армия гибла — 14:1 при равной мощи
        // прижим (см. USE_PRESS): мили в пачке — вплотную к цели фокуса в PRESS_RANGE, иначе к ближайшему ловимому врагу с
        // боем; стрелок — в кольцо ровно в RANGED_RANGE от цели фокуса
        val pack = pressOn && isMelee(creep) && !hasRanged(creep) && hasMelee(creep) && !rotating && localAggressive &&
            (combatArmy.any { it.id != creep.id && isMelee(it) && !hasRanged(it) && hasMelee(it) && getRange(creep, it) <= PRESS_PACK } ||
                localEnemies.any { getRange(creep, it) <= 1 })
        // отказ (см. PRESS_GIVEUP) действует, пока цель не вернулась в три (v111, USE_GIVEUP_RETURNS), и снимается,
        // когда цель стоит ВПЛОТНУЮ к нашему не-мили (v135, см. USE_GIVEUP_LIFTS_ON_BACK): отказ — про погоню, а
        // враг у нашего лекаря никуда не бежит
        fun givenUp(e: Creep) = e.id in pressGiveUp && !(getRange(creep, e) <= MELEE_HOLD_RANGE + 1) 
        // прижим — только под прикрытием стрелков (v122, USE_PRESS_COVER): та же мера, что у броска (см. covered, v55) —
        // MELEE_COVER стрелков в RANGED_RANGE + 1 от цели или свой вплотную к ней; иначе мили прижимают шагающую назад линию
        // в одиночку под её огонь (серия 307–326: けろびー#1 дважды за 240 тиков, наш мили 1600 → 752 за 14 тиков погони,
        // стрелки в двух за мили не достают — 44 выстрела против 70)
        fun pressCovered(e: Creep) = 
            combatArmy.count { it.id != creep.id && hasRanged(it) && getRange(it, e) <= RANGED_RANGE + 1 } >= MELEE_COVER ||
            army.any { a -> a.id != creep.id && getRange(e, a) <= 1 }
        val pressTarget: Creep? = if (!pack) null else
            focusTarget?.takeIf { getRange(creep, it) <= PRESS_RANGE && catchable(it, chasers) && !givenUp(it) && pressCovered(it) }
                ?: localEnemies.filter { getRange(creep, it) <= PRESS_RANGE && catchable(it, chasers) && threatening(it, enemyCreeps) && !givenUp(it) && pressCovered(it) }.minByOrNull { getRange(creep, it) }
        // против СОМКНУТОГО блоба мили не бросается (v135, см. USE_MELEE_HOLD_VS_MASS): наш мили, вошедший в двенадцать,
        // получает около 750 в тик (пять стрелков и четыре мили достают его) и живёт два-три тика — за матч наши мили
        // живут 50 крипо-тиков против его 664 и бьют 14 раз против 85. Держим линию и бьём то, что подошло; poker
        // (защита тыла) по-прежнему выше и работает
        // ...И НЕ ДЕРЖИМ ЛИНИЮ, КОГДА РЯДОМ ПЕРЕВЕС (v214): здесь мили получал цель «своя клетка», то есть
        // буквально стоял, как только любой враг оказывался в трёх клетках. Замер по 12 живым матчам: доля
        // касаний наших мили в затяжном бою 1,4 %, то есть четверо из четырнадцати не бьют вовсе
        val holdMelee =  (isMelee(creep) && !hasRanged(creep) && posture == Posture.ANNIHILATE && !pushing && contact && pressTarget == null &&
            !spotNow && localEnemies.any { getRange(creep, it) <= MELEE_HOLD_RANGE + 1 })
        // прилипший (v43): его вооружённый мили ВПЛОТНУЮ к нашему стрелку, лекарю или раненому — цель ближайшего нашего мили в
        // ENGAGE_RANGE, поверх «держать линию в двух». Матч 73 (Coldkimchi): его мили подходили к нашим стрелкам и лекарям,
        // били по 240 и отходили — 46 ударов (11 тыс. урона) против наших 7, наши мили держали линию в 2–3 от его линии и не
        // доставали; стрельба при трёх лекарях с обеих сторон вылечена целиком, армия потеряна к 240-му при его 16000/16000
        // ...и ротирующий защищает тыл (v135, см. USE_POKER_WHILE_ROTATING): ротация значит «оружия меньше половины, иду
        // лечиться», но три живых ATTACK из восьми — это 90 урона в удар, а лекарь, к которому он идёт, — тот самый,
        // которого рубят. Ротация не отменяет poker, пока у бойца есть чем ударить
        val poker: Creep? = if (isMelee(creep) && !hasRanged(creep) && !support && (!rotating) && !stalled) combatEnemies.filter { e ->
            InfluenceMap.profileOf(e).melee > 0.0 && getRange(creep, e) <= ENGAGE_RANGE && !givenUp(e) &&
                army.any { a -> a.id != creep.id && !(isMelee(a) && !hasRanged(a)) && getRange(e, a) <= 1 }
        }.let { c ->
            // защита своего — тоже одной целью на всех (v221, см. USE_MELEE_PACK): цель пачки, если она среди них
            c.minByOrNull { getRange(creep, it) } } else null
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
            combatArmy.count { it.id != creep.id && hasRanged(it) && getRange(it, e) <= RANGED_RANGE + 1 } >= MELEE_COVER ||
            army.any { a -> a.id != creep.id && getRange(e, a) <= 1 }
        // ОДНА ДОБЫЧА НА ВСЕХ в толчке (v71): бросок — только на цель в ENGAGE_RANGE от добычи армии (prey — ближайший к центру по
        // полю). Стенд camp+shy (застенчивый лагерь матчей 133/152/159: отходит от наших в шести и возвращается на флаг): его
        // блоб рассыпался вокруг армии, мили брали одну цель в (34,39), стрелки другую в (46,55), плотность держала всех у
        // центра между ними — 800 тиков pushing=true, huntable 12/12, `step=stay` у всех, 9615:23822 (m30). Живьём — толчок к
        // стоящему блобу, который не сближается (матчи 70, 133, 152, 159)
        // мили входит парой (v101, USE_MELEE_PAIR_ENGAGE): к цели в досягаемости — другой наш мили в MELEE_HOLD_RANGE + 1 от неё
        val meleeOnly = isMelee(creep) && !hasRanged(creep)
        fun mateNear(e: Creep, r: Int) = combatArmy.any { m -> m.id != creep.id && isMelee(m) && !hasRanged(m) && hasMelee(m) && getRange(m, e) <= r }
        // ...и присоединяется к напарнику, уже стоящему в MELEE_HOLD_RANGE от цели: досягаемость на клетку больше
        fun holdReach(e: Creep) = if (meleeOnly && mateNear(e, MELEE_HOLD_RANGE)) MELEE_HOLD_RANGE + 1 else MELEE_HOLD_RANGE
        val engage = if (pressTarget != null) pressTarget else poker ?: if ((localAggressive || spotNow) && !support && inLine && !rotating && !stalled) combatEnemies.filter { getRange(creep, it) <= (if (holdMelee) holdReach(it) else ENGAGE_RANGE) && catchable(it, chasers) && threatening(it, enemyCreeps) && !givenUp(it) && (!isMelee(creep) || hasRanged(creep) || covered(it))  }.let { c ->
            // ОДНА ЦЕЛЬ НА ВСЕХ МИЛИ (v221, см. USE_MELEE_PACK): цель пачки, если она среди допустимых этому мили,
            // иначе прежний ближайший — пачка ничего не запрещает, она только выбирает
            c.minByOrNull { getRange(creep, it) } } else null
        if (engage != null) Memory.engagingIds.add(creep.id) else Memory.engagingIds.remove(creep.id)
        // пара к общей цели мили (v221, см. mpackHit): как часто ноги мили и так идут к цели фокуса
        if (meleeOnly && engage != null) { mpackAll++; if (engage.id == focusTarget?.id) mpackHit++ }
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
        val leashHolds = (healer) || (posture != Posture.RETREAT && posture != Posture.EVADE)
        val leashed = !wounded && canMove(creep) && leashHolds &&
            (localEnemies.isNotEmpty() || (contact)) && getRange(creep, armedCentroid) > LEASH_RANGE
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
        val foeMeleeLive = enemyMassedNow && localEnemies.any { hasMelee(it) && InfluenceMap.profileOf(it).melee > 0.0 }
        val closeIn = if (localAggressive) CLOSE_STANDOFF else RANGED_RANGE
        if (hasRanged(creep) && localAggressive) { closeTicks++; if (foeMeleeLive) closeHeld++ }
        val melee = isMelee(creep) && !hasRanged(creep)
        val meleeMate: Creep? = if (melee) combatArmy.filter { it.id != creep.id && isMelee(it) && !hasRanged(it) && hasMelee(it) && canMove(it) }.minByOrNull { getRange(creep, it) } else null
        // мили со слотом стены (см. planBlock) оставляет его ради цели: прижим или враг в досягаемости удара
        val slot = if (melee && engage != null) null else slot0
        val grab = grabberOf[creep.id]?.let { id -> ctx.flags.firstOrNull { it.id == id } }
        // лекарь держится вплотную к самому раненому бойцу РЯДОМ (в дальности лечения плюс шаг), иначе идёт к
        // ближайшему ходячему бойцу — не к самому раненому через полкарты: два лекаря шли к обездвиженному
        // остову за стеной, а строй ждал их у флага (стенд greedy)
        val healMate = if (healer) {
            // хранитель флага — не подопечный, пока есть ходячие бойцы (v120, USE_HEALERS_NOT_WITH_KEEPERS): лекарь рядом с
            // хранителем видел в нём единственного «своего в четырёх», и трое лекарей стояли по одному у хранителей на D5 и
            // R3, а ударная шестёрка у A3 шла без лечения (spread m30 на v120i, 20291:24327); раненый хранитель снимается с
            // флага (см. updateKeepers) и становится подопечным как все
            val keptOut =  army.any { hasWeapon(it) && canMove(it) && it.id !in Memory.keeperIds }
            val fighters = army.filter { it.id != creep.id && hasWeapon(it) && !(keptOut && it.id in Memory.keeperIds) }
            // подопечные — вооружённые; вне боя рядом — и раненые (они сами идут к лекарю, см. wounded)
            val patients = army.filter { it.id != creep.id && !(hasHeal(it) && !hasWeapon(it)) && !(keptOut && it.id in Memory.keeperIds) }
            val engagedNear = fighters.any { f -> getRange(creep, f) <= HEAL_RANGE + 1 && combatEnemies.any { getRange(f, it) <= RANGED_RANGE + 1 } }
            val near = (if (engagedNear) fighters else patients).filter { getRange(creep, it) <= HEAL_RANGE + 1 }
            // подопечный под огнём (v109b, USE_WARD_UNDER_FIRE) ОТВЕРГНУТ таблицей входов стенда: лекари шли к терявшему хиты
            // ВПЕРЁД, в досягаемость его стрелков — за первые 20 тиков контакта потери выше v108 в 13 сценариях из 26 (nine,
            // hunter, fourteen, screen+focus: скрипты «лекари первыми»), ниже в 5; m28 nine 1714 → 3010, m34 nine 3504 → 4400.
            // Остаётся выбор ЦЕЛИ лечения под огнём (см. rank в healAndShoot); подопечный — самый раненый вооружённый, как прежде
            // ...а при УДЕРЖИМОЙ жертве (v228, см. USE_HEAL_WALL) подопечный — она: лечение вплотную 72 против 24 издали,
            // и именно её он бьёт сейчас, а самый раненый в дальности — уже отведённый в тыл
            (if (victimSaveable) victimNow?.takeIf { v -> v.id != creep.id && getRange(creep, v) <= HEAL_RANGE + 1 } else null)
                ?: (null)
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
        val massKite: Creep? = if ((!support) && (enemyMassedNow) &&
                (combatEnemies.none { getRange(creep, it) <= 1 }  )) {

                 // ...или стрелок выходит из-под удара, НЕ ЗАМОЛКАЯ (v135, см. USE_KITE_KEEPS_FIRE): прежний срез
                 // (USE_KITE_BREAKS_CONTACT) отвергнут за 0-6/0-6, потому что уходящий стрелок терял цель — его
                 // дальность три. Здесь шаг назад разрешён, только когда в дальности стоят двое и больше его
            (combatEnemies.filter { InfluenceMap.profileOf(it).melee > 0.0 && getRange(creep, it) <= ENGAGE_RANGE })
                .minByOrNull { getRange(creep, it) }
        } else null
        val healerNear: Creep? = if (wounded || rotating) {
            val hs = army.filter { it.id != creep.id && !hasWeapon(it) && hasHeal(it) }
            val mine = combatEnemies.minOfOrNull { getRange(creep, it) } ?: 99
            (null)
                ?: hs.minByOrNull { getRange(creep, it) }
        } else null
        // под огнём без двух бойцов вплотную — назад к строю, не вперёд: шип в строй врага бьют трое-четверо, а он один
        // вплотную, не «в двух клетках»: со счётом союзников в двух клетках мили под огнём не отходили и ныряли в блоб
        // врага по одному — три мили за восемь тиков при одном убитом (стенд m5 army, v22, t=300–308)
        val aloneInFire =  !support && !wounded && posture == Posture.ANNIHILATE && !pushing && InfluenceMap.damageAt(creep.x, creep.y, combatEnemies) > 0.0 &&
            combatArmy.count { it.id != creep.id && hasWeapon(it) && getRange(creep, it) <= 1 } < 2 && pressTarget == null 
        // вес огня лекаря: подопечный в бою — только разница между клетками (HEALER_W_DAMAGE_FIGHT); место за
        // подопечным и шаг от мили задают HEALER_W_FRONT и HEALER_W_MELEE, а вес 0.05 в бою держал лекаря на кромке
        // огня в 2–3 клетках (лечение 24 вместо 72) и проиграл рубки sleeper на картах 4 и 8
        val healerFireW = if (healer && healMate != null && combatEnemies.any { getRange(healMate, it) <= RANGED_RANGE + 1 }) HEALER_W_DAMAGE_FIGHT else HEALER_W_DAMAGE
        // сбор: по полю марша (флаг-цель или пост, в обход врагов) авангард — самый продвинутый из ходячих
        // вооружённых (при равном поле — меньший id); кто дальше RALLY_RANGE от авангарда, идёт к нему
        // только на марше к флагу-цели: в HOLD цель — точка, к ней сходятся и так, а в ANNIHILATE ожидание
        // «далёкого» напарника, занятого своим боем, останавливало армию (стенд greedy)
        // ходячий по canMove, не «полноскоростной»: покалеченный участник, не входящий в сбор, но ждущий
        // далёких, замыкал группу в тупик (стенд rush: 10 против 2 до конца матча)
        val groupedPre =  !support && canMove(creep) && posture == Posture.FLAG
        val marchTarget: Position? = objective?.flag?.pos
        var rallyTo: Position? = null
        if (groupedPre && marchTarget != null) {
            val mf = flowAvoiding(ctx, marchTarget, creep)
            val my = mf[creep.x * 100 + creep.y]
            var van: Creep? = null
            var vanFlow = my
            var vanId = creep.id
            // авангард — из массы (v120, USE_RALLY_VAN_FROM_MASS): оторвавшийся крип не точка сбора, как и в построении
            val rallyPool = mobileArmy.filter { hasWeapon(it) && getRange(it, armedCentroid) <= MASS_RANGE }.ifEmpty { mobileArmy }
            for (m in rallyPool) {
                if (m.id == creep.id || !hasWeapon(m)) continue
                val d = mf[m.x * 100 + m.y]
                if (d < 0) continue
                if (vanFlow < 0 || d < vanFlow || (d == vanFlow && m.id < vanId)) { vanFlow = d; vanId = m.id; van = m }
            }
            // с гистерезисом: с клетки «13 от авангарда» крип шёл к нему, со следующей («12») — снова к цели,
            // и два шага туда-обратно длились до конца матча, а авангард ждал (стенд rush)
            val rallyRange = if (creep.id in Memory.rallyingIds) RALLY_RANGE / 2 else RALLY_RANGE
            if (van != null && getRange(creep, van) > rallyRange) { rallyTo = InfluenceMap.cell(van.x, van.y); Memory.rallyingIds.add(creep.id) }
            else Memory.rallyingIds.remove(creep.id)
        } else Memory.rallyingIds.remove(creep.id)
        // построение: вне огня и без готовности авангард и собравшиеся у него стоят, остальные идут к нему
        // в контакте построение окончено: авангард — тот, кто уже дерётся, и «собраться у авангарда с дистанцией 1»
        // тянуло стрелков за ним внутрь строя врага, а стреляли они с 4–5 клеток впустую (матч 15, t=68–100)
        val forming = formVan != null && !formationReady && !support && canMove(creep) && posture != Posture.RETREAT && posture != Posture.EVADE &&
            localEnemies.any { threatening(it, enemyCreeps) } && nearestEnemyRange > RANGED_RANGE && !contact &&
            !(stalled)
        val formHold = forming && (formVan!!.id == creep.id || getRange(creep, formVan) <= FORM_RANGE)
        val formGo = forming && !formHold
        val target: Position
        val standoff: Int
        var avoid = false
        var nearFlow = false   // цель-крип рядом: поле «вблизи» (см. NEAR_FLOW)
        // в строю (см. USE_BLOCK): мили вплотную к врагу стоит и рубит, остальные — в свой слот
        val slotHold = slot != null && melee && localEnemies.any { getRange(creep, it) <= 1 }
        // ПЕРЕПИСЬ РЕШЕНИЙ (v203, этап 1): каждая ветка обеих цепочек называет себя, и счётчик копится за матч.
        // Повод — пять правил за сутки, которые прошли гейт и не исполнились ни разу: по коду нельзя было
        // сказать, какая ветка живая. Перепись отвечает на это числом, а не чтением. Она же заменяет ручной
        // дубль цепочки в TRACE_WHY, который успел рассинхронизироваться и рассказывал о боте неправду
        var whyTag = "?"
        when {
            keeper -> { whyTag = "keeper"; target = InfluenceMap.cell(creep.x, creep.y); standoff = 0 }
            slotHold -> { whyTag = "slotHold"; target = InfluenceMap.cell(creep.x, creep.y); standoff = 0 }
            // приказ командира раньше всего боевого: он уже учёл, кто где встанет и что будет опасно (v137)
            commandOf[creep.id] != null && (commandOf[creep.id]!!.x != creep.x || commandOf[creep.id]!!.y != creep.y) ->
                { whyTag = "order"; target = commandOf[creep.id]!!; standoff = 0 }
            // ПОГОНЯ ЗА ОСТОВОМ (v212) — сразу под приказом командира: пока он правит, клетку даёт он (и тоже
            // знает про погоню, см. chaseTarget в placeScored); когда молчит, преследователь идёт сюда. Ниже
            // кайта и строя стоять нельзя: обе ветки увели бы его обратно в кулак, а весь смысл отряда в том,
            // чтобы из кулака выйти
            Memory.chaseTarget[creep.id]?.let { it.hits > 0 } == true -> {
                whyTag = "chase"
                target = InfluenceMap.cell(Memory.chaseTarget[creep.id]!!.x, Memory.chaseTarget[creep.id]!!.y)
                standoff = if (hasRanged(creep)) RANGED_RANGE - 1 else 0
                avoid = true
            }
            // кайт раньше слота: слот ставит нас в строй, а строй сходится с блобом вплотную (v135)
            // дистанция кайта зависит от того, выгоден ли ему ВЕЕР: масс-атака бьёт в радиусе трёх (10/4/1 за часть),
            // поэтому в куче держим три — там веер стоит ему шестёрки урона вместо шестидесяти, — а поодиночке два,
            // где наш собственный огонь плотнее. MetalicaX#11 даёт 63 веера за матч против 37 у #10 и наших 12 (v135)
            massKite != null -> {
                whyTag = "kite"; kiteNow++
                // пока стволы не подтянулись — держим на клетку дальше и в бой не входим (v135, USE_KITE_UNTIL_READY)
                target = massKite
                standoff = KITE_STANDOFF
                avoid = true; nearFlow = true
            }
            // ЛЕКАРЬ ВЫШЕ СТРОЯ В БОЮ (v147): замер по записи (6aa078c0, обе стороны по три лекаря) — у него в
            // дальности лечения стоят ВСЕ ТРИ каждый тик, у нас 1,8 из трёх, и лечение выходит 1584 против 9624.
            // Причина в порядке цепочки: слот стоял выше подопечного, а расстановка не знает, кого лечить, и уводила
            // лекаря в строй за пределы дальности. Лекарь вне HEAL_RANGE не лечит вовсе — в бою подопечный главнее
            // СТЕНА ЛЕЧЕНИЯ (v228, см. USE_HEAL_WALL): назначенная клетка стены — как слот, вплотную к удержимой жертве
             healer && victimSaveable && wallCellOf[creep.id] != null ->
                { whyTag = "wall"; target = wallCellOf[creep.id]!!; standoff = 0 }
            // ЛЕКАРЬ ПРИ МИЛИ (v235, см. USE_HEALER_AT_MELEE): клетка при своём фронтовом мили с тыла — как слот
             healer && healMate != null && contact ->
                { whyTag = "healMate"; target = healMate; standoff = 1; nearFlow = true }
            slot != null -> { whyTag = "slot"; target = slot; standoff = 0 }
            // лекарь и в отходе идёт за подопечным (лечение — в тот же тик, что и шаг, 216 в тик восстанавливают
            // обломок за шесть тиков): прежде лекари шли к точке отхода сами, а раненые — врассыпную
            healer && healMate != null -> { whyTag = "healMateOut"; target = healMate; standoff = 1; nearFlow = true }
            // отход — по обычному полю: поле «в обход» стоящих врагов (а дерущиеся стоят) увело пару в обход
            // стенного блока на другой край карты (матч 4)
            posture == Posture.EVADE && evadeTo != null -> { whyTag = "evade"; target = evadeTo; standoff = 1 }
            posture == Posture.RETREAT && retreatTo != null -> { whyTag = "retreat"; target = retreatTo; standoff = 1 }
            formGo -> { whyTag = "formGo"; target = InfluenceMap.cell(formVan!!.x, formVan.y); standoff = 1 }
            wounded && healerNear != null -> { whyTag = "wounded"; target = healerNear; standoff = 1; avoid = true; nearFlow = true }
            rotating && healerNear != null -> { whyTag = "rotate"; target = healerNear; standoff = 1; avoid = true; nearFlow = true }
            // сбор пачки (см. USE_REGROUP, REGROUP_TICKS): одинокий мили под смертельным огнём — к ближайшему мили-напарнику
             aloneInFire && melee && meleeMate != null && InfluenceMap.damageAt(creep.x, creep.y, combatEnemies) * REGROUP_TICKS >= creep.hits -> { whyTag = "regroup"; target = meleeMate; standoff = 1; avoid = true; nearFlow = true }
            aloneInFire -> { whyTag = "alone"; target = armedCentroid; standoff = CLOSE_STANDOFF; avoid = true; nearFlow = true }
            leashed -> { whyTag = "leash"; target = armedCentroid; standoff = CLOSE_STANDOFF; avoid = true; nearFlow = true }
            engage != null -> { whyTag = "engage"; target = engage; standoff = if (melee) 1 else closeIn; nearFlow = true }
            // мили держит линию (см. MELEE_HOLD_RANGE): что подошло на две клетки — рубит, за экраном не гонится
            holdMelee -> { whyTag = "holdMelee"; target = InfluenceMap.cell(creep.x, creep.y); standoff = 0 }
            grab != null -> { whyTag = "grab"; target = grab.pos; standoff = 0; avoid = true }
            // добивание без местного перевеса — отход к массе армии, а не бросок на «ближайшую добычу»; без
            // ловимой добычи (кайтеры) — тоже к массе: стоим строем и стреляем в то, что подойдёт
            posture == Posture.ANNIHILATE && !support && (!localAggressive || prey == null) -> { whyTag = "toCentroid"; target = armedCentroid; standoff = CLOSE_STANDOFF; avoid = true }
            prey != null -> { whyTag = "prey"; target = prey; standoff = if (melee) 1 else closeIn; nearFlow = true }
            rallyTo != null -> { whyTag = "rally"; target = rallyTo; standoff = CLOSE_STANDOFF; avoid = true }
            objective != null -> {
                whyTag = "objective"
                val capturer = objectiveCapturer == creep.id
                target = objective.flag.pos
                standoff = if (capturer) 0 else CLOSE_STANDOFF
                avoid = true
            }
            threat != null && huntingThreat && mobile -> { whyTag = "threat"; target = threat; standoff = if (melee) 1 else closeIn }
            raider != null && mobile && !support -> { whyTag = "raider"; target = raider; standoff = if (melee) 1 else RANGED_RANGE }
            // ОТВЕРГНУТО стендом (v42): «держать линию там, где она стоит» (holdLine → armedCentroid вместо поста) — в матче 70
            // пост при враге рядом был точкой в 35 клетках позади, и каждый тик ДЕРЖАТЬ между тиками ДОБИТЬ разворачивал
            // армию к нему. Но возврат к посту делает работу в десятках сценариев (после отбитого рывка остаток добивается у
            // поста): 58 строк хуже / 48 лучше, гейтовая m33 farm+weak и m29 farm красные. Мигание лечится у корня — см.
            // chaseVeto и evasive
            else -> { whyTag = "post"; target = post; standoff = POST_STANDOFF; avoid = true }
        }
        val flow0 = if (slot != null || keeper) NO_FLOW else if (avoid) flowAvoiding(ctx, target, creep, nearFlow) else flowTo(ctx, target, near = nearFlow)
        // за пределом поля «вблизи» — полное поле
        val flow = if (nearFlow && slot == null && !keeper && flow0[creep.x * 100 + creep.y] < 0)
            (if (avoid) flowAvoiding(ctx, target, creep) else flowTo(ctx, target)) else flow0

        val nearbyEnemies = combatEnemies.filter { getRange(creep, it) <= 12 }
        // «в бою», плотность и передний ряд — по врагам С БОЕМ: три безоружных лекаря врага в 4–5 клетках после выигранного
        // боя держали армию в зоне плотности и не давали толкнуть своего — мили, зажатый своими лекарями, и армия
        // простояли тысячу тиков в 20 клетках от флага (стенд m16 nine)
        val inCombat = armedEnemies.any { creep.getRangeTo(it) <= RANGED_RANGE + 2 }
        val localThreats = localEnemies.filter { threatening(it, enemyCreeps) }
        val underFire = InfluenceMap.damageAt(creep.x, creep.y, combatEnemies) > 0.0 || ghost > 0
        // бегство: смертельный урон за два тика; безоружный лекарь — от врага рядом, если рядом нет ни одного
        // вооружённого своего (при нём лекарь стоит и лечит: бегущий лекарь — потерянные 72 в тик, матч 3);
        // невидимый урон
        // вооружённый бежит, только когда его ДОБИВАЮТ: за прошлый тик снято не меньше половины оставшихся хитов и
        // осталось меньше трети. Прежнее «весь возможный огонь по клетке за два тика больше хитов» в бою 12 на 12
        // верно для КАЖДОЙ клетки у вражеского блоба (1260 против 1600), и трое мили с полными хитами
        // разворачивались спиной в первый тик контакта — их резали в спину, строй рассыпался (стенд sleeper,
        // t=550; матчи 4–6 в первом размене теряли 1:5 при равной силе)
        val lostLastTick = Memory.lastHits[creep.id]?.let { it - creep.hits } ?: 0
        // лекарь и раненый бегут (врассыпную) только в одиночестве: при своём рядом — отход группой по постуре
        // раненый (без оружия и лечения) и в контакте уходит из ПОЛНОЙ досягаемости — стрелка в трёх, мили в двух (v123,
        // USE_STRIPPED_LEAVES_REACH): сужение до мили в контакте — для лекаря, которому лечить фронт; обезоруженному в
        // огне делать нечего, а живым он вернётся с лечением. Серия 307–326: наши обезоруженные стояли в трёх от его
        // вооружённых половину своего времени (131 из 258, 264 из 552 крип-тиков), его — десятую (26 из 79, 7 из 11);
        // правило «обезоруженные в досягаемости» — 3 из 6 поражений и 0 из 18 побед
        val reachMine = reachNow
        val inReach = (creep.x * 100 + creep.y) in reachMine
        // ...и для лекаря закрытые для шага клетки — полная досягаемость (v234, вторая редакция), бегство — по прежней
        val avoidCells = reachMine
        // прибор v234: лекарь в бою и в досягаемости его вооружённых; урон по лекарям
        if (healer && inCombat) { hexpAll++; if ((creep.x * 100 + creep.y) in reachCells) hexpN++; hlostSum += (lostTick[creep.id] ?: 0) }
        val mustFlee = (support && nearbyEnemies.any { getRange(creep, it) <= RANGED_RANGE + 1 } && army.none { it.id != creep.id && getRange(creep, it) <= HEAL_RANGE }) ||
            (support && inReach) ||
            (lostLastTick * 2 >= creep.hits && creep.hits * 3 < creep.hitsMax) ||
            (ghost > 0 && creep.hits <= ghost)

        // сплочение: авангард ждёт отставших группы (в тиках ИХ хода), пока сам не под огнём и напарник
        // не в бою; при враге в досягаемости зазор тесный — собираемся ДО входа под огонь
        val myFlow = flow[creep.x * 100 + creep.y]
        val grouped = !support && (posture == Posture.ANNIHILATE || posture == Posture.FLAG || (huntingThreat && threat != null && target === threat))
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
        val cohesionHold = grouped && rallyTo == null && grab == null && engage == null && !underFire && !mateFighting && myFlow >= 0 && creep.getRangeTo(target) > standoff + ARRIVED_SLACK && run {
            var lagging = false
            for (m in mates) {
                if (getRange(creep, m) <= RANGED_RANGE) continue
                if (grabberOf.containsKey(m.id) || m.id in Memory.engagingIds) continue
                val d = flow[m.x * 100 + m.y]
                if (d < 0) continue
                // напарник на другом обходе (только на марше к флагу): далеко и не впереди — ждём его, он идёт
                // к нам (см. rallyTo)
                if (posture == Posture.FLAG && getRange(creep, m) > RALLY_RANGE && plainPeriod(m) <= RALLY_MAX_PERIOD && (d > myFlow || (d == myFlow && m.id > creep.id))) { lagging = true; break }
                val lag = (d - myFlow) * plainPeriod(m)
                if (lag in (gap + 1)..COHESION_GAP_MAX) { lagging = true; break }
            }
            lagging
        }
        // отход строем (см. RETREAT_GAP): передняя половина ждёт отставшего от тела армии, пока сама вне огня
        val retreatHold = (posture == Posture.RETREAT || posture == Posture.EVADE) && !support && canMove(creep) && !underFire && nearestEnemyRange > RANGED_RANGE + 1 && myFlow >= 0 && run {
            val flows = mobileArmy.filter { hasWeapon(it) }.map { flow[it.x * 100 + it.y] }.filter { it >= 0 }.sorted()
            if (flows.isEmpty()) return@run false
            val rear = flows.last()
            val median = flows[flows.size / 2]
            rear - median > RETREAT_GAP && myFlow <= median
        }
        // терпение (см. COHESION_PATIENCE): затянувшееся ожидание снимается до конца отставания
        if (cohesionHold) {
            val since = Memory.holdSince.getOrPut(creep.id) { getTicks() }
            if (getTicks() - since >= COHESION_PATIENCE) Memory.impatientIds.add(creep.id)
        } else { Memory.holdSince.remove(creep.id); Memory.impatientIds.remove(creep.id) }
        val hold = (cohesionHold && creep.id !in Memory.impatientIds) || formHold || retreatHold

        var stepTag = "?"
        val step: Position? = when {
            !canMove(creep) -> { stepTag = "immobile"; null }
            // ВЫЖИВАНИЕ ВЫШЕ ЗАДАНИЯ (v240, этап 5 переработки, решение оператора 13.09.2026): крип под смертельным
            // огнём бежит, даже если у него приказ командира или пост хранителя. До v240 приказ стоял выше бегства
            // (v172 «приказ — закон»), и комментарий у бегства утверждал обратное. Цена конфликта — прибор:
            // `fled=` (приказов, перебитых бегством) и `step=flee` в гистограмме шагов
            mustFlee -> {
                stepTag = "flee"
                if (commandOf.containsKey(creep.id)) orderFled++
                fleeStep(creep, nearbyEnemies, ctx.dangerMatrix, if (support) RANGED_RANGE + 1 else RANGED_RANGE) ?: pathStep(creep, retreatTo ?: post, 1, ctx.dangerMatrix)
            }
            // ХРАНИТЕЛЬ ТОЖЕ СЛУШАЕТ ПРИКАЗ (v173, оператор): «уйти с флага крип должен только если командир решит
            // собрать отряд, или если крип может попасть в опасность». Прежде хранитель стоял всегда и приказа не
            // видел вовсе — он был вне командира по построению (mobileArmy исключает keeperIds)
             keeper && commandOf.containsKey(creep.id) -> commandOf[creep.id]!!
                .takeIf { it.x != creep.x || it.y != creep.y }.also { stepTag = "keeperOrder" }
            keeper -> { stepTag = "keeperStay"; null }
            // ПРИКАЗ — ЗАКОН (v172, оператор): «все крипы должны двигаться ТОЛЬКО по приказу командира… нельзя не
            // слушаться приказов командира». Приказ исполняется БУКВАЛЬНО: назначенная клетка и есть шаг. Прежняя
            // попытка сделать так провалилась (гейт 133, исполнение 3 %) потому, что командир раздавал клетки, не
            // считая того, что считает крип, — теперь считает (см. rankStep в commandFight), и цена ошибки лежит
            // на нём, а не на непослушании
            // ...и во ВСЕХ режимах, а не только в бою (v172, оператор): «все крипы должны двигаться ТОЛЬКО по
            // приказу командира». В гонке и походе приказ тоже закон — там он ведёт ядро строем и за флагами
             commandOf.containsKey(creep.id) -> {
                orderBranch++          // сколько приказов реально дошло до ветки исполнения (v173)
                stepTag = "order"
                val cell = commandOf[creep.id]!!
                if (cell.x == creep.x && cell.y == creep.y) null
                else cell
            }
            slot != null -> { stepTag = if (slotHold) "slotHold" else "slotStep"; if (slotHold) null else slotStep(creep, slot, blockedSet, enemyPositions, occupantAt, combatEnemies, if (support && !inReach) reachMine else emptySet()) }
            // ПРИКАЗ ВЫШЕ СЛОТА И ОСТАНОВКИ (v171): в выборе ШАГА приказ не участвовал вовсе — слот уводил крипа в
            // строй, а hold оставлял на месте, и приказ работал только в последней ветке. Разбор потерь показал
            // цену: из 143 приказов 50 кончались уходом в другую клетку и 36 — тем, что крип не двинулся
            // ...и только В БОЮ: в гонке очков приказ марша перебивал удержание, и camp падал 4 155:16 209
            hold -> { stepTag = "hold"; null }
            else -> {
                stepTag = "free"
                // клетка флага открыта только назначенному на него (захватчик цели, «подобрать» рядом)
                val designated = grab?.pos ?: objective?.flag?.pos?.takeIf { objectiveCapturer == creep.id }
                var myBlocked = if (designated != null) blockedSet - (designated.x * 100 + designated.y) else blockedSet
                // плотность (см. COMPACT_RANGE): при враге в досягаемости — только на клетки строя
                // лекарь и раненый — вне правила (их цель — свой в строю); снаружи зоны шаг К центру всегда открыт:
                // прежде крип вне зоны не мог шагнуть никуда (все соседи тоже вне), и три лекаря простояли весь бой
                // матча 8 в 4–5 клетках от строя
                if (!wounded && localThreats.isNotEmpty() && posture != Posture.RETREAT && posture != Posture.EVADE && canMove(creep)) {
                    val armedMates = mobileArmy.filter { it.id != creep.id && hasWeapon(it) }
                    val myRange = getRange(creep, armedCentroid)
                    val loose = HashSet<Int>()
                    for ((dx, dy) in DIRECTIONS) {
                        val x = creep.x + dx; val y = creep.y + dy
                        if (x < 0 || y < 0 || x > 99 || y > 99) continue
                        val c = InfluenceMap.cell(x, y)
                        val r = getRange(c, armedCentroid)
                        // шаг ВПЕРЁД (по потоку к цели) открыт на клетку в COMPACT_RANGE + 1: иначе авангард не мог
                        // выйти из зоны, а центр не сдвигался, пока никто не выходил, — блоб 1200 тиков стоял в
                        // четырёх клетках от последнего лекаря врага и проиграл по очкам (стенд m5 kite); линия
                        // ползёт гусеницей — впереди не дальше трёх от центра, остальные подтягиваются
                        val fd = flow[x * 100 + y]
                        val advancing = myFlow >= 0 && fd in 0 until myFlow
                        val compact = r <= COMPACT_RANGE || (advancing && r <= COMPACT_RANGE + 1) || armedMates.count { getRange(c, it) <= 1 } >= 2 || (r < myRange)
                        if (!compact) loose.add(x * 100 + y)
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
                if (support && !inReach && avoidCells.isNotEmpty() && !(healingNow)) myBlocked = myBlocked + avoidCells
                if (support && localThreats.isNotEmpty() && localThreats.none { getRange(creep, it) <= 1 }) {
                    val front = HashSet<Int>()
                    for ((dx, dy) in DIRECTIONS) {
                        if (dx == 0 && dy == 0) continue
                        val x = creep.x + dx; val y = creep.y + dy
                        if (x < 0 || y < 0 || x > 99 || y > 99) continue
                        val c = InfluenceMap.cell(x, y)
                        val byMelee = meleeEnemies.any { getRange(c, it) <= 1 }
                        val byPatient = healingNow && getRange(c, healMate!!) <= 1
                        if (localEnemies.any { getRange(c, it) <= 1 } && (byMelee || !byPatient)) front.add(x * 100 + y)
                    }
                    if (front.isNotEmpty()) myBlocked = myBlocked + front
                }
                // ПРИКАЗ ИСПОЛНЯЕТСЯ, А НЕ ПЕРЕСЧИТЫВАЕТСЯ (v167): назначенная клетка была лишь ОДНИМ слагаемым в
                // оценке шага наравне с опасностью и соседями, и опасность её перевешивала — прибор показал, что
                // крип доходит до своей клетки в 7 % случаев (10 из 144) и даже приближается лишь в 32 %. Прогноз
                // при этом считает, что армия встанет по плану: он опирался на фикцию. Клетка в ОДНОМ шаге теперь
                // запрашивается напрямую, как это делают захватчики
                val ordered: Position? = null
                orderPull = if (commandOf.containsKey(creep.id)) ORDER_PULL else 1.0
                val chosen = ordered ?: bestSingleMove(creep, target, flow, standoff, localAggressive || spotNow, inCombat, enemyCreeps, allies, meleeEnemies, myBlocked, enemyPositions, occupantAt, healerFireW, focusTarget)
                orderPull = 1.0
                chosen
            }
        }
        // СЛЕПОТА К ОПАСНОСТИ НА ШАГЕ (v215, оператор: «линия фронта должна работать ВСЕГДА на
        // передвижение»). Пара: шагов в клетку, несущую урон, при ВЫКЛЮЧЕННОМ слагаемом опасности —
        // против всех шагов. Слагаемое выключено в двух местах: вне боя `scoreCell` возвращается до него
        // вовсе, а при агрессии оно обнуляется. Приказ командира слепым не считается: он один и считается
        // по полям (см. scoreMelee/scoreRanged/scoreHeal)
        if (step != null) {
            dangerMoves++
            if (stepTag != "order" && InfluenceMap.dangerAt(step.x * 100 + step.y) > 0.0) {
                if (!inCombat) dangerBlindFar++ else if (localAggressive || spotNow) dangerBlind++
            }
        }
        if (TRACE_WHY && DEBUG_LOG && meleeOnly && hasMelee(creep) && engage == null && posture != Posture.RETREAT && posture != Posture.EVADE) {
            // только враг «с боем» (см. threatening): праздность при небоевых остатках после выигранного боя — не находка
            val near = combatEnemies.filter { getRange(creep, it) <= ENGAGE_RANGE && threatening(it, enemyCreeps) }.minByOrNull { getRange(creep, it) }
            if (near != null) {
                val r = ArrayList<String>()
                if (support) r.add("support")
                if (rotating) r.add("rotating")
                if (stalled) r.add("stalled")
                if (!localAggressive) r.add("!aggr(${ourPowerOf(localAllies, localEnemies).toInt()}/${enemyPowerOf(localEnemies, localAllies).toInt()}x${ratio} cost=${fightCost(localEnemies, localAllies).let { if (it >= Double.MAX_VALUE / 2) "inf" else it.toInt().toString() }} slack=${localAllies.maxOfOrNull { speedSlack(it) } ?: 0} le=${localEnemies.size} la=${localAllies.size})")
                if (!inLine) r.add("!inLine")
                val d = getRange(creep, near)
                if (holdMelee && d > holdReach(near)) r.add("hold:d$d>${holdReach(near)}")
                if (!catchable(near, chasers)) r.add("!catchable")
                if (givenUp(near)) r.add("giveup")
                if (!covered(near)) r.add("!covered")
                if (r.isEmpty()) r.add("?")
                // ДУБЛЬ ЦЕПОЧКИ УДАЛЁН (v203): здесь стояла вторая, написанная руками копия цепочки целей — и она
                // успела рассинхронизироваться (в ней не было guardMate, rallyBlob, shieldMate, приказа, поиска,
                // кайта, ухода из кольца и кольца прижима). Прибор, повторяющий решение вручную, рассказывает о
                // боте неправду ровно тогда, когда бот меняется; настоящая ветка называет себя сама
                val did = whyTag
                val short = { id: String -> id.replace(Regex("^pg_player\\d_"), "") }
                whyLines.add("${short(creep.id)}@(${creep.x},${creep.y})d$d>${short(near.id)}[${r.joinToString(",")}]$did${if (hold) "+hold" else ""}/${step?.let { "(${it.x},${it.y})" } ?: "stay"}")
                for (k in r) whySum[k] = (whySum[k] ?: 0) + 1
                whySum["idle"] = (whySum["idle"] ?: 0) + 1
            }
        }
        if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
            println("  f${creep.id} (${creep.x},${creep.y}) ${bodySummary(creep)} hits=${creep.hits}/${creep.hitsMax} tgt=(${target.x},${target.y}) so=$standoff flow=$myFlow flee=$mustFlee combat=$inCombat aggr=$localAggressive hold=$hold${if (formHold) "(form)" else if (retreatHold) "(rear)" else ""}${if (leashed) " leash" else ""}${if (wounded) " WOUNDED" else ""}${if (pressTarget != null || false) " PRESS" else ""} spd=${plainPeriod(creep)} fatigue=${creep.fatigue} step=${step?.let { "(${it.x},${it.y})" } ?: "stay"}${if (TrafficManager.isStuck(creep.id)) " STUCK" else ""}")
        }
        // СОГЛАСОВАНИЕ ДВИЖЕНИЙ — ЗА КОМАНДИРОМ (v170, оператор). Разрешение конфликтов уже устроено правильно:
        // поиск в глубину с цепочками и свопами, по ПРИОРИТЕТУ. Но приоритет задавали разрозненные места — раненый,
        // боец, захватчик, — и замысел в нём не участвовал. Теперь очередь назначает командир: крип, исполняющий
        // приказ, идёт первым, а среди приказов вперёд пропускается тот, чья клетка важнее для боя — мили,
        // выходящий в контакт, затем стрелок с целью, затем лекарь к подопечному, и лишь потом все прочие
        val prio = Arbiter.pushRank(ordered = commandOf.containsKey(creep.id), melee = hasWeapon(creep) && hasMelee(creep) && !hasRanged(creep),
            armed = hasWeapon(creep), healer = hasHeal(creep), wounded = wounded)
        // ...И ШАГ СТАНОВИТСЯ ПРЕДЛОЖЕНИЕМ (v252, этап 9): решение крипа — значение, которое отдаётся арбитру одним вызовом,
        // с приоритетом и причиной «задание отряда . терм» (терм — ветка шага, а у свободного шага — ступень лестницы)
        submit(Proposal(creep, step, priorityOf(stepTag, whyTag), prio, missionOf[creep.id] ?: '?',
            if (stepTag == "free") whyTag else stepTag, whyTag, stepTag), ctx)
        Memory.lastHits[creep.id] = creep.hits
        Memory.lastCell[creep.id] = creep.x * 100 + creep.y
    }
}

/** Шаг к слоту строя без поля потока: соседняя проходимая клетка, ближайшая к слоту (при равенстве — под меньшим
 *  огнём); занятая своим — через трафик, если только она ближе; клетки banned (досягаемость для лекаря) закрыты. */
internal fun PainAndGain.slotStep(creep: Creep, slot: Position, blockedSet: Set<Int>, enemyPositions: Set<Int>, occupantAt: Map<Int, Creep>, enemies: List<Creep>, banned: Set<Int>): Position? {
    val here = getRange(creep, slot)
    if (here == 0) return null
    var best: Position? = null
    var bestD = here
    var bestFire = Double.MAX_VALUE
    var push: Position? = null
    var pushD = here
    for ((dx, dy) in DIRECTIONS) {
        if (dx == 0 && dy == 0) continue
        val x = creep.x + dx; val y = creep.y + dy
        if (!passable(x, y, blockedSet, enemyPositions) || (x * 100 + y) in banned) continue
        val c = InfluenceMap.cell(x, y)
        val d = getRange(c, slot)
        if (occupantAt[x * 100 + y] != null) { if (d < pushD) { pushD = d; push = c }; continue }
        val fire = InfluenceMap.damageAt(x, y, enemies)
        if (d < bestD || (d == bestD && fire < bestFire)) { bestD = d; bestFire = fire; best = c }
    }
    return best ?: push
}

internal fun PainAndGain.bestSingleMove(
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
    val hereDist = flow[creep.x * 100 + creep.y]
    // своя клетка с форой — только ПРИБЫВ (в зазоре standoff): вне боя не дёргаемся ради мелочи (см.
    // STAY_BIAS); на марше форы нет — вместе со штрафом за соседей она съедала выигрыш шага (матч 2)
    val settled = !inCombat && hereDist in 0..(standoff + ARRIVED_SLACK)
    var bestScore = scoreCell(creep, creep.x, creep.y, target, flow, standoff, aggressive, inCombat, enemyCreeps, allies, meleeEnemies, healerFireW, focus) + (if (settled) STAY_BIAS else 0.0)
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
            // лекарь и раненый уступают и в бою (TrafficManager: заявитель приоритетнее стоящего — swap): раненый,
            // «прибывший» к лекарю, стоял на единственной клетке между тремя лекарями в кармане у стены и их
            // подопечным, и лечения не было (стенд m7 sleeper); лекаря толкает только вооружённый
            val yielding = canMove(occ) && !hasWeapon(occ) && !hasHeal(occ) && hasHeal(creep) && !hasWeapon(creep)
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
    if (inCombat && aggressive && isMelee(creep) && !hasRanged(creep) && hasMelee(creep) &&
        (target.x * 100 + target.y) in enemyPositions && maxOf(abs(bx - target.x), abs(by - target.y)) <= 1) {
        mquietAll++
        val chosen = InfluenceMap.netDamageAt(bx, by, enemyCreeps, allies)
        var qx = bx; var qy = by; var qd = chosen
        for ((dx, dy) in DIRECTIONS) {
            val x = creep.x + dx; val y = creep.y + dy
            if ((x == bx && y == by) || maxOf(abs(x - target.x), abs(y - target.y)) > 1) continue
            if ((dx != 0 || dy != 0) && (!passable(x, y, blockedSet, enemyPositions) || occupantAt.containsKey(x * 100 + y))) continue
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
        for ((dx, dy) in DIRECTIONS) {
            if (dx == 0 && dy == 0) continue
            val x = creep.x + dx; val y = creep.y + dy
            if (!passable(x, y, blockedSet, enemyPositions) || occupantAt.containsKey(x * 100 + y)) continue
            val fd = flow[x * 100 + y]
            val dan = InfluenceMap.dangerAt(x * 100 + y)
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
internal fun PainAndGain.scoreCell(creep: Creep, x: Int, y: Int, target: Position, flow: IntArray, standoff: Int, aggressive: Boolean, inCombat: Boolean, enemyCreeps: List<Creep>, allies: List<Creep>, meleeEnemies: List<Creep>, healerFireW: Double = HEALER_W_DAMAGE, focus: Creep? = null): Double {
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
    val lethalTerm = if (damage * 2.0 >= creep.hits) LETHAL_PENALTY.also { lethalHits++ } else 0.0
    lethalCells++
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
    val meleeSelf = isMelee(creep) && !hasRanged(creep)
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
        else if (InfluenceMap.influenceOf(x * 100 + y) < 0.0)
            damage * PAIR_W_DAMAGE * AGGRO_MELEE_FACTOR
        else 0.0
    val pinned = (periodAt(creep, x, y) - 1) * InfluenceMap.fireAt(x, y, enemyCreeps) * PAIR_W_DAMAGE
    // ...и притяжение к ПРИКАЗУ сильнее (v168, см. orderPull): назначенная клетка была одним слагаемым наравне с
    // влиянием, угрозой мили и разделением, и они её перевешивали — до своей клетки доходили 7 % крипов
    return -firePenalty * PAIR_W_DIST * orderPull - damageTerm + influence * PAIR_W_INFLUENCE +
        (outgoing + focusPull) * PAIR_W_OUTGOING - meleeThreat - separation - swampPenalty - pinned - lethalTerm
}

internal fun PainAndGain.outgoingValue(x: Int, y: Int, enemyCreeps: List<Creep>): Double {
    var massValue = 0.0
    var anyInRange = false
    for (enemy in enemyCreeps) {
        val d = getRange(InfluenceMap.cell(x, y), enemy)
        if (d <= RANGED_RANGE) { anyInRange = true; massValue += InfluenceMap.rangedRate(d) }
    }
    if (!anyInRange) return 0.0
    return maxOf(massValue, 1.0)
}

internal fun PainAndGain.passable(x: Int, y: Int, blockedSet: Set<Int>, enemyPositions: Set<Int>): Boolean {
    if (x < 0 || y < 0 || x > 99 || y > 99) return false
    val key = x * 100 + y
    if (key in blockedSet || key in enemyPositions) return false
    return !DistanceMap.isTerrainWall(x, y)
}
