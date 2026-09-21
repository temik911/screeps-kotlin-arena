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

/**
 * СТРАТЕГ (этап 6 переработки; план — docs/pain-and-gain-rework.md, разделы 2 и 8).
 *
 * Одно место, где армия решает, в каком она состоянии. До v241 это были три решения через шестьсот строк друг от друга:
 * постура (`when` из annihilate / objective / evade / retreat) с гистерезисом POSTURE_HOLD и спасением без срока, режим
 * командира (MARCH / RACE / FIGHT) и перезапись постуры режимом боя. Здесь они сведены в [decide]: входы — меры,
 * посчитанные до решения, выход — одно [Decision]. v241 (тождество) сохранил прежние значения и точки применения;
 * v242 пробовал применять решение один раз (постура после перезаписи с точки решения) и оценивать прогнозом тот же
 * состав, что планирует командир, — отвергнуто живым A/B (0-8 против MetalicaX#15 при контроле 2-6; см. runArmy у
 * применения постуры), v243 вернул точки применения v241; v242 также замерил пересмотр по событиям (см. decide). Дальше
 * постановка становится решателем вместе с переписью тех, кто читает постуру (этапы 7–9). Типы постановки
 * [Disposition] / [Squad] / [Mission] объявлены и печатаются прибором `disp=` по нынешним
 * составам; постановка как решатель вместо тройки Posture / CmdMode / Intent — следующие срезы.
 */
internal object Strategist {
    class Decision(
        val newPosture: Posture, val postureTakes: Boolean,
        /** постура после гистерезиса, до перезаписи режимом боя — применяется у решения (v241, v243) */
        val posturePre: Posture, val postureSincePre: Int,
        val cmdMode: CmdMode, val cmdWhy: String,
        /** постура после перезаписи режимом боя — применяется там, где стояла перезапись (v241; «один раз» v242 отвергнуто) */
        val postureFinal: Posture, val postureSinceFinal: Int,
        val event: Boolean,
        /** кандидат этого тика и начало его непрерывного ряда — в Memory до следующего тика (v250) */
        val candidate: Posture, val candidateSince: Int,
    )

    /** Один вопрос о режиме командира: вход решения, признак «бой сейчас» и — для цепочки причин — уже выбранный режим. */
    class ModeCase(val i: StrategyInputs, val fightNow: Boolean) { var mode: CmdMode = CmdMode.RACE }

    /** Счётчики трёх таблиц решения (прибор `reach t=`): постура, режим командира, причина режима. */
    val postureTally = Tally("posture")
    val modeTally = Tally("mode")
    val whyTally = Tally("cmdwhy")

    /** ПРАВИЛА ПОСТУРЫ (v445): порядок списка = приоритет. Строка выигрывает — её действие называет постуру; условие истинно, а
     *  выиграла строка выше — `shadowed`: так видно, например, сколько тиков уклонение перекрыто целью-флагом. */
    private val POSTURE_RULES: List<Row<StrategyInputs, Posture>> by lazy { listOf<Row<StrategyInputs, Posture>>(
        Row("annihilate", { annihilate }) { Posture.ANNIHILATE },
        Row("flag", { hasObjective }) { Posture.FLAG },
        Row("evade", { evade }) { Posture.EVADE },
        Row("retreat", { retreat }) { Posture.RETREAT },
        Row("hold", { true }) { Posture.HOLD },
    ) }

    // РЕЖИМЫ КОМАНДИРА (v160, оператор: «командир должен оркестрировать всю игру»). Раздача клеток против его
    // строя — это ОДИН режим, и все формы, на которых расширение окна падало (camp, roost, scatter, kite),
    // просят другого: там враг строем не дерётся, а сидит на флагах, разбегается или держит дистанцию, и
    // выигрывает не кулак, а счёт. Поэтому командир сперва называет РЕЖИМ, а уже режим решает, что делать
    // ...и режим НАЗНАЧАЕТ постуру: командир решил драться — значит армия уничтожает, а не держит и не бежит
    /** РЕЖИМ КОМАНДИРА: поход → гонка при застое или его отходе → бой → гонка. */
    private val MODE_RULES: List<Row<ModeCase, CmdMode>> by lazy { listOf<Row<ModeCase, CmdMode>>(
        Row("march", { i.marchNow }) { CmdMode.MARCH },
        Row("race.stall", { i.stalled || i.hisRetreat }) { CmdMode.RACE },
        Row("fight", { fightNow }) { CmdMode.FIGHT },
        Row("race", { true }) { CmdMode.RACE },
    ) }

    /** ПРИЧИНА РЕЖИМА — из той же цепочки (v215); тег строки и есть причина, которую печатает `cmdwhy=`. */
    private val WHY_RULES: List<Row<ModeCase, String>> by lazy { listOf<Row<ModeCase, String>>(
        Row("fight", { mode == CmdMode.FIGHT }) { "fight" },
        Row("march", { mode == CmdMode.MARCH }) { "march" },
        Row("outmatched", { i.outmatched }) { "outmatched" },
        Row("stall", { i.stalled }) { "stall" },
        Row("retreat", { i.hisRetreat }) { "retreat" },
        Row("push", { pushing }) { "push" },
        Row("nofire", { !i.underTheirFire }) { "nofire" },
        Row("few", { i.fewFoes }) { "few" },
        Row("posture", { true }) { "posture" },
    ) }

    fun decide(i: StrategyInputs): Decision {
        val newPosture = walk(POSTURE_RULES, i, postureTally).act(i)
        // ГИСТЕРЕЗИС ПОСТУРЫ (v181): держится не меньше POSTURE_HOLD тиков; раньше срока меняется только на RETREAT —
        // спасение не ждёт; EVADE срока ждёт (v183: изъятие для EVADE само рождало пилу с периодом POSTURE_HOLD)
        // ...и ИЗЪЯТИЕ ДЕЙСТВУЕТ ТОЛЬКО ПОД ОГНЁМ (v479, см. USE_ESCAPE_UNDER_FIRE): условие отхода мигает вместе с
        // целью-флагом, а изъятие пускало отход без срока — постура пилила FLAG<->RETREAT с периодом POSTURE_HOLD
        val escape = newPosture == Posture.RETREAT && (!USE_ESCAPE_UNDER_FIRE || i.underTheirFire)
        // ПЕРЕСМОТР ПО СОБЫТИЯМ (решение оператора 13.09) ПОКА НЕ ВКЛЮЧЁН — три определения события отвергнуты гейтом
        // (v242, замер в runArmy у поля event): любое изменение контакта, только появившийся контакт, гибель своего + смена
        // владельца флага — каждое роняло scatter m34 и меняло счёт 19–34 сценариев, потому что на стенде эти события
        // случаются десятки раз за матч и срок POSTURE_HOLD перестаёт что-либо держать. Что должно быть верно, чтобы
        // правило заработало: событие — не одиночный тик, а перемена, устоявшаяся дольше мерцания (например, новое
        // решение стабильно ≥ 3 тиков) — отдельный замеряемый срез. Событие считается прибором evt=
        // ...И СРОК ОТСЧИТЫВАЕТСЯ ОТ КАНДИДАТА, А НЕ ОТ ПРЕЖНЕЙ ПОСТУРЫ (v250, остаток этапа 6 — то самое «что должно быть
        // верно» из абзаца выше). Прежде срок держал только прежнюю постуру: простояв POSTURE_HOLD, она сменялась на ЛЮБОГО
        // кандидата этого тика, даже мелькнувшего на один тик. Стенд match34:scatter (v249) — последние 300 тиков кандидат
        // FLAG↔ANNIHILATE менялся через 1–10 тиков (пикеты его россыпи то входили в досягаемость, то уходили: contact и
        // pushing мигали при постоянной мощи 3985 против 2114), применённая постура качалась каждые 5–20, армия шла к A3 и
        // возвращалась, матч проигран 23558:24314. Мера мощи по всей силе вместе с отрядом (первая проба среза) счёт не
        // сдвинула ни на очко — мигали не меры, а сама смена. Здесь смена берётся, когда новый кандидат предлагается
        // POSTURE_HOLD тиков подряд — это и минимальный срок постуры, потому что следующему кандидату нужно столько же.
        // Спасение (RETREAT) по-прежнему без срока; бой под огнём тоже: его ставит перезапись режимом FIGHT ниже
        val candSince = if (newPosture == i.candidate) i.candidateSince else i.now
        val takes = newPosture == i.posturePrev || escape || i.now - candSince >= POSTURE_HOLD
        val pre = if (takes) newPosture else i.posturePrev
        val sincePre = if (takes && newPosture != i.posturePrev) i.now else i.postureSincePrev
        // РЕЖИМ КОМАНДИРА (v160): поход — врага рядом нет; гонка — затор или его отход вне рубки; бой — под его огнём,
        // его группа у руки, мы не наступаем и не бежим (v217: наступление режим боя не исключает — кулак нужен там,
        // где лечение не даёт добить, а признак «мы позади по размену» и есть !pushing … underFire)
        // ...И ПРОТИВ КУЛАКА СТРОЕВОГО БОЯ НЕ БЫВАЕТ (v351). Замер по 40 матчам против MetalicaX#15 (версии
        // v342–v348): исход двоичный — либо мы переживаем первое столкновение всеми четырнадцатью и выигрываем
        // 20–22 тыс. против 5–9 тыс., либо нас вырезают к 200-му тику (8 побед, 32 поражения). Различитель — режим
        // командира в первые 60 тиков контакта: в победах одно окно FIGHT против шести RACE, в поражениях четыре
        // против трёх; за 50 тиков контакта убито 4,0 его крипа против 0,0, наших живых 14,0 против 13,0, наши хиты
        // 12 560 против 8 133, его 5 719 против 9 935. Причинность проверена по времени: в 26 поражениях из 32 режим
        // боя включался РАНЬШЕ первой нашей смерти (медиана 80-й тик против 100-го), а три победы из восьми прошли
        // вовсе без него. Возражение к этому выводу названо и оно честное: `!pushing` само означает «мы позади по
        // размену», и в момент включения у нас уже 10 556 хитов из 16 000 против 12 668 в победах, — то есть связь
        // может быть обратной. Различает их только живой замер, и он здесь: A/B 8+8 против MetalicaX#15.
        // ⚠️ СУЖЕНО ГЕЙТОМ (v352): запрет «никогда против кулака» уронил match20:brawl+heals — там строй УНИЧТОЖАЕТ
        // его армию за 221 тик (1 739 : 562), а без него матч тянется до 1 980-го и проигран 14 982 : 15 493;
        // exposure показал «командир в бою 0,0 %», то есть правило сняло режим боя во всех 135 сценариях. Различитель
        // взят из того же живого замера, которым правило и обосновано: в победах в момент боя у нас 12 560 хитов
        // против его 5 719 — мы впереди вдвое и добиваем, в поражениях 8 133 против 9 935 — мы уже позади, и строй
        // проигранный размен не выправляет. Поэтому запрет действует только позади по хитам: впереди — бьём строем
        val fightNow = !pushing && i.underTheirFire && !i.fewFoes && !pre.withdrawing &&
            !(i.enemyMassed && USE_NO_FIST_FIGHT)
        val case = ModeCase(i, fightNow)
        val mode = walk(MODE_RULES, case, modeTally).act(case)
        // ...и причина берётся из той же цепочки (v215): прибор, повторяющий решение своим порядком, врёт ровно тогда,
        // когда бот меняется
        case.mode = mode
        val why = walk(WHY_RULES, case, whyTally).tag
        // РЕЖИМ НАЗНАЧАЕТ ПОСТУРУ (v162): командир решил драться — армия уничтожает, а не держит и не бежит; запись
        // через те же часы (v215)
        val overrideFight = mode == CmdMode.FIGHT && pre != Posture.ANNIHILATE
        val final = if (overrideFight) Posture.ANNIHILATE else pre
        val sinceFinal = if (overrideFight) i.now else sincePre
        return Decision(newPosture, takes, pre, sincePre, mode, why, final, sinceFinal, i.event, newPosture, candSince)
    }

    // ---- постановка: типы плана (раздел 3) — пока только снимок для прибора ----

    sealed class Mission {
        class Goto(val to: String) : Mission()
        class Take(val flagId: String) : Mission()
        class Fight(val group: List<String>) : Mission()
        class Escort(val squad: Int) : Mission()
        val tag: Char get() = when (this) { is Goto -> 'G'; is Take -> 'T'; is Fight -> 'F'; is Escort -> 'E' }
    }

    class Squad(val id: Int, val members: List<String>, val mission: Mission)
    class Disposition(val squads: List<Squad>)

    /** Постановка, какой её сегодня задают старые решатели: главный отряд по постуре и режиму, бегуны и отряжённые —
     *  `Take` своего флага, преследователи — `Fight` остова, хранители — `Take` флага под ногами. */
    fun snapshot(army: List<Creep>, runners: List<Creep>, runnerFlag: Map<String, String>, detached: Set<String>,
                 cmdDetach: Set<String>, keepers: Map<String, String>, chase: Map<String, String>,
                 posture: Posture, cmdMode: CmdMode, objectiveFlagId: String?, hisArmed: List<Creep>): Disposition {
        val squads = ArrayList<Squad>()
        var n = 0
        val taken = HashSet<String>()
        for (c in runners) {
            val f = runnerFlag[c.id] ?: continue
            squads.add(Squad(n++, listOf(c.id), Mission.Take(f))); taken.add(c.id)
        }
        for ((id, f) in keepers) if (id !in taken) { squads.add(Squad(n++, listOf(id), Mission.Take(f))); taken.add(id) }
        for ((id, hulk) in chase) if (id !in taken) { squads.add(Squad(n++, listOf(id), Mission.Fight(listOf(hulk)))); taken.add(id) }
        for (c in army) if (c.id !in taken && (c.id in detached || c.id in cmdDetach)) {
            squads.add(Squad(n++, listOf(c.id), Mission.Take(runnerFlag[c.id] ?: "?"))); taken.add(c.id)
        }
        val main = army.filter { it.id !in taken }.map { it.id }
        val mission: Mission = when {
            cmdMode == CmdMode.FIGHT || posture == Posture.ANNIHILATE -> Mission.Fight(hisArmed.map { it.id })
            posture == Posture.FLAG && objectiveFlagId != null -> Mission.Take(objectiveFlagId)
            cmdMode == CmdMode.MARCH -> Mission.Goto("goal")
            posture == Posture.RETREAT -> Mission.Goto("retreat")
            posture == Posture.EVADE -> Mission.Goto("evade")
            else -> Mission.Goto("post")
        }
        if (main.isNotEmpty()) squads.add(Squad(n, main, mission))
        return Disposition(squads)
    }

    /** `disp=F12+T1+T1+F1`: задание и численность каждого отряда, главный первым. */
    fun summary(d: Disposition): String {
        val parts = d.squads.sortedByDescending { it.members.size }.map { "${it.mission.tag}${it.members.size}" }
        return if (parts.isEmpty()) "-" else parts.joinToString("+")
    }
}

/**
 * Брать ли этот (не наш) флаг сейчас. Свой — да. Врага в поле нет — да. Иначе — по паритету (см. PARITY_FLOOR):
 * армия С ЭТИМ дебаффом не слабее армии врага (CAPTURE_FLOOR), а пока отрыва нет — не меньше PARITY_FLOOR их
 * мощи:
 * маргинальная цена по Ланчестеру с эффектами ОБЕИХ сторон — у флага стрельбы дешевеет только стрелковая
 * часть урона, у флага лечения растёт чистый урон врага по нам, у флага уязвимости тают хиты, а чужой флаг
 * ещё и возвращает врагу то, что снимал с него. Отставание по счёту флагов больше не открывает: оно
 * значит, что враг держит больше и слабее — ответ ему бой (PUSH_RATIO_BEHIND), а не ещё один дебафф
 * (матч 3: «отстаём» на 2 очка при 6:10 разрешило всё подряд). Исключение — последние LAST_CALL_TICKS:
 * бой уже не успеет, и очки решают.
 */
/** ⚠️ Параметр `runner` снят в v216: он был объявлен у обеих функций и НИ РАЗУ не читался в теле
 *  `captureBlock`. `runRunners` передавал `runner = true`, и это не меняло ничего — у бегуна те же ворота, что
 *  у армии. Дифф отчёта по 135 сценариям пуст побайтово, как и обязан быть у мёртвого. */
internal fun captureAllowed(ctx: Ctx, f: FlagInfo, view: ExchangeView, asker: CapAsker, serious: Boolean = true): Boolean = captureBlock(ctx, f, view, asker, serious) == null

/** КТО СПРАШИВАЕТ ВОРОТА (v467, дефект 6 постановки): бегуны идут ДО стадий армии и спрашивают по вчерашним данным (`Prev.exchange`,
 *  `interceptFlagId`, `fightPackIds`, `objectiveFlagId`, `firstFightTick`, `kiteChaseSeen` прошлого тика); армия, гонка и тактик — по
 *  сегодняшним (`meas.view` и те же величины, переписанные стадиями армии этого тика). Параметр — явный: место вызова называет
 *  своё время, и прибор `capdis=` считает пары «тик × флаг», где два времени дали РАЗНЫЙ ответ про один флаг. */
internal enum class CapAsker { RUNNER, ARMY }

/** Какие ворота держат захват — null, если разрешено (v135, прибор к разрезу `tools/flagcut.py`): пять ворот отказывали
 *  молча, и в логе стояло только POISED, поэтому нельзя было сказать, ЧТО именно держит бегуна в клетке от свободного
 *  флага. Условия и их порядок те же, что были в captureAllowed.
 *  ОДИН ПИСАТЕЛЬ ПРИБОРА ВОРОТ (v451, пункт Г оператора): вердикт считается ЗДЕСЬ, где он возвращён, — а не замком `capSeen`
 *  внутри ворот. Прежние приборы `capgate=` / `cap=` врали трояко: `capCount` — мутатор замка, и первый вызов на пару «тик ×
 *  флаг» запирал остальные; холостые вызовы в головах ворот `contact.mass` (`rush.approach.expired`) и `first.fight`
 *  (`contact.edge.lifted`) — без выхода — забирали учёт у настоящего запрета ниже в том же тике (недосчитаны `parity`, `enough`,
 *  `first.fight`); `capOffered` рос на каждый вызов, включая ОЦЕНОЧНЫЕ из `Missions.kt` (множитель ценности флага), так что
 *  знаменатель зависел от числа спрашивающих. «Всерьёз» и «оценка» различаются параметром [serious], а не замком: оценка
 *  считается отдельно (`capeval=`), холостые приборы — своими счётчиками (`capidle=`), замка не касаясь. Старые поля печатались
 *  рядом до пересъёмки эталона и сняты в v477 (вопрос 4 оператора) вместе с замком `capSeen` и `capCount`. */
internal fun captureBlock(ctx: Ctx, f: FlagInfo, view: ExchangeView, asker: CapAsker, serious: Boolean = true): String? {
    val v = pass(captureGates(), CaptureCase(ctx, f, view), captureTally)
    val reason = (v as? Verdict.Veto)?.reason
    if (!serious) capqEval.n++
    else {
        capqAsked.n++
        // причина `parity(ours/floor)` вычисляемая — считается под одним именем, как и у прежнего `capCount(f, "parity")`
        val why = reason?.let { if (it.startsWith("parity(")) "parity" else it }
        if (why != null) capqVeto.n++
        // ...и по одному на пару «тик × флаг» — первый вопрос всерьёз решает (сравнимо с прежним `capgate=` / `cap=`, где
        // первым мог быть и холостой вызов); множество трогают только вопросы всерьёз
        if (capquTick != getTicks()) { capquTick = getTicks(); capquSeen.clear(); capRunnerSaid.clear(); capquaSeen.clear() }
        if (capquSeen.add(f.id)) {
            capquAsked.n++
            if (why != null) { capquVeto.n++; capquWhy.bump(why) }
        }
        // ...И ОТВЕТ АРМИИ ОТДЕЛЬНО ОТ ОТВЕТА БЕГУНУ (v467, дефект 6): `capqu=` / `capu=` описывают ответ ПЕРВОМУ спросившему
        // всерьёз, то есть бегуну (он идёт до армии) — там, где бегун о флаге не спрашивал, первым оказывается кто-то из армии.
        // `capqua=` / `capua=` — ответ армии по одному на пару «тик × флаг»; третья часть `capqua=` — пары, о которых спросили ОБА;
        // `capdis=` — те из них, где ответы разошлись, меткой `ворота бегуна>ворота армии` (`ok` — разрешено)
        when (asker) {
            CapAsker.RUNNER -> if (f.id !in capRunnerSaid) capRunnerSaid[f.id] = why ?: "ok"
            CapAsker.ARMY -> if (capquaSeen.add(f.id)) {
                capquaAsked.n++
                if (why != null) { capquaVeto.n++; capquaWhy.bump(why) }
                val said = capRunnerSaid[f.id]
                if (said != null) {
                    capquaBoth.n++
                    if ((said == "ok") != (why == null)) capDisagree.bump("$said>${why ?: "ok"}")
                }
            }
        }
    }
    return reason
}

// ==================== прибор ворот захвата с одним писателем (v451, пункт Г) ====================
/** Вопросов всерьёз / из них запретов; то же по одному на пару «тик × флаг» и причины запретов; оценочных вопросов; холостых
 *  приборов в головах ворот (`rush.approach.expired`, `contact.edge.lifted`) — печать `capq=`, `capu=`, `capqu=`, `capeval=`,
 *  `capidle=`. */
internal val capqAsked = Gauges.counter("capq", 1)

/** Вето дебаффа выхода при целой его армии (v530, см. USE_NO_OUTPUT_DEBUFF_WHOLE_ARMY): запретов и пропусков. */
/** Сброс отряда по погасшему ярлыку «фермер» (v533, см. FARMER_OFF_TICKS): роспусков и тиков, когда признак был
 *  ложен при живом отряде (то есть случаев, которые окно теперь переживает). */
internal val farmOffRecall = Gauges.counter("farmoff")

internal val farmOffHeld = Gauges.counter("farmoff", 1)

internal val debuffVeto = Gauges.counter("dbfveto")

internal val debuffPass = Gauges.counter("dbfveto", 1)
internal val capqVeto = Gauges.counter("capq")
internal val capquAsked = Gauges.counter("capqu", 1)
internal val capquVeto = Gauges.counter("capqu")
internal val capquWhy = Gauges.labelled("capu")
internal var capquTick = -1
internal val capquSeen = Gauges.marks("capqu")
internal val capqEval = Gauges.counter("capeval")
internal val capIdleRush = Gauges.counter("capidle")
internal val capIdleEdge = Gauges.counter("capidle", 1)
/** Ответ ворот АРМИИ (v467, дефект 6): запретов / вопросов по одному на тик × флаг / из них пар, о которых спросил и бегун; причины;
 *  расхождения с ответом бегуну (`бегун>армия`). Ответ бегуна на пару держится до конца тика — словарь чинится (см. Gauges). */
internal val capquaVeto = Gauges.counter("capqua")
internal val capquaAsked = Gauges.counter("capqua", 1)
internal val capquaBoth = Gauges.counter("capqua", 2)
internal val capquaWhy = Gauges.labelled("capua")
internal val capDisagree = Gauges.labelled("capdis")
internal val capquaSeen = Gauges.marks("capqua")
/** Ответ бегуну по флагу в этом тике (причина или `ok`) — поле StrategistState: чинится после оборванного тика, как и словари Gauges. */
internal val capRunnerSaid: HashMap<String, String> get() = StrategistState.capRunnerSaid

/**
 * ОДИН ВОПРОС О ЗАХВАТЕ (v445, носитель вместо цепочки локальных): флаг и то, что ворота успели о нём узнать. Величину пишут
 * ворота, до которых дошли, в прежнем порядке; ворота ниже её читают. Дорогое (мощь сторон — `losingRace`, `ours` / `theirs`)
 * считается, только если дошли: функцию зовут десятки раз за тик, и для настоящих ворот, и для оценки ценности флага.
 */
// v456 (второй шаг архитектуры, этап 3): у носителя остались только величины, которые одни ворота пишут, а ДРУГИЕ, ниже, читают.
// `contactArmy`, `firstFightAhead`, `raceWon` читали только свои же ворота — следы механического переноса v445; теперь это локальные
// своих ворот (`contact.mass`, `first.fight`, `enough`).
internal class CaptureCase(val ctx: Ctx, val f: FlagInfo, val view: ExchangeView) {
    var ticksLeft = 0
    var intercept = false
    var rushStale = false
    var losingRace = false
    var foes: List<Creep> = emptyList()
    var foughtFoe = false
    var opp: List<Creep> = emptyList()
    var ours = 0.0
    var theirs = 0.0
    var floor = 0.0
}

/** Причины отказа (прибор `whynot t=`, см. Why в Tables.kt). У входов постуры ложный конъюнкт — почему вход не поднят; у ворот захвата
 *  цепочка — условие ВЕТО, и ложный конъюнкт — почему ворота не запретили (прошли насквозь). */
private val CONTACT_FIGHT = Why("contactFight")
private val ANNIHILATE = Why("annihilate")
private val EVADE = Why("evade")
private val RETREAT = Why("retreat")
private val ENEMY_RETREATING = Why("enemyRetreating")
private val GATE_RUSH = Why("gate.rush")
private val GATE_CONTACT_MASS = Why("gate.contactMass")
private val GATE_FIRST_FIGHT = Why("gate.firstFight")
private val GATE_ENOUGH = Why("gate.enough")
private val DRY_HUNT = Why("dryHunt")
private val QUIET_CHAIN = Why("quietChain")
private val FARMER = Why("farmer")
private val PUSH_RAW = Why("pushRaw")
private val HUNTED = Why("hunted")
private val HOLD_LINE = Why("holdLine")

/** Счётчики ворот захвата (прибор `reach t=`, таблица `gate`): дошли / решили. */
internal val captureTally = Tally("gate")

private var captureGateRows: List<Gate<CaptureCase>>? = null

/**
 * ВОРОТА ЗАХВАТА: двенадцать ворот, порядок списка = порядок проверки. Ворота — лямбда с получателем [CaptureCase]: сперва то,
 * что раньше стояло МЕЖДУ выходами (вычисления, счётчики приборов, холостые `capCount`) — в прежнем порядке, — затем вердикт.
 * Имя ворот — адрес: `grep` по тегу находит и условие, и его вердикт-комментарий. Причина запрета печатается прежняя; у `rush`
 * их две (`rush.unflagged` / `rush.approach`), у `parity` причина вычисляемая и не равна метке счёта, `seventh` идёт мимо
 * `capCount`, как и шёл.
 */
internal fun captureGates(): List<Gate<CaptureCase>> = captureGateRows ?: listOf<Gate<CaptureCase>>(
    Gate("ours") {
        if (f.ours) return@Gate Verdict.Allow
        Verdict.Next
    },
    Gate("noFoe") {
        if (ctx.combatEnemies.isEmpty()) return@Gate Verdict.Allow
        Verdict.Next
    },
    Gate("seventh") {
        // седьмой флаг — никогда при живой его армии (v127, USE_NO_SEVENTH_FLAG): все дебаффы наши, ни одного его
        if (ctx.flags.count { it.ours } + 1 >= ctx.flags.size) return@Gate Verdict.Veto("seventh")
        Verdict.Next
    },
    Gate("lastCall") {
        // последний зов и при РАВНОМ счёте: ничья 0:0 после уклонения (см. EVADE_EQUAL_RATIO) отдана не будет
        ticksLeft = arenaInfo.ticksLimit - getTicks()
        val losingAtTheEnd = (ourScore - enemyScore) + (WorldState.ourRate - WorldState.enemyRate) * ticksLeft <= 0
        if ((WorldState.behindOnScore || losingAtTheEnd) && ticksLeft <= LAST_CALL_TICKS) return@Gate Verdict.Allow
        Verdict.Next
    },
    Gate("rush") {
        // во время броска безфлаговой армии (см. unflaggedRushNow — тот же сигнал, что уводит армию в уклонение) флаг не берёт
        // НИКТО: бой через двадцать тиков, и дебафф ложится на него. Скаут брал R3 на 42–43-м тике во всех четырёх боях с
        // けろびー (матчи 38, 43, 44, 45) — −20 % стрелкам в решающем размене, — проходя порог паритета с запасом три очка мощи
        // (3967 против 3964: модель мощи считает мили полными, а их обезоруживают за первые двадцать тиков). Три очка в тик
        // за тридцать тиков против пятой части огня на весь бой
        // флаг перехвата (см. USE_INTERCEPT) открыт и в контакте с фермером, и при «бое близко»; остальные — нет
        intercept = Signals.enemyNotFightingNow && f.id == interceptFlagId
        // «бой близко» закрывает НОВУЮ цель сразу, а УЖЕ выбранную снимает, лишь продержавшись RUSH_VETO_TICKS подряд (v114, матч
        // 277): его блоб, шагающий на 9–13 клетках, гонял темп подхода 31–63 % через порог 50 % каждые три тика, и цель D5 (ценность
        // 22–41) назначалась и снималась через тик — FLAG↔HOLD 17 раз за сто тиков, три шага к флагу и три назад, 520 тихих тиков
        // при отставании. Первый срез (вето целиком после 10 тиков) открывал флаги и настоящему броску — его темп тоже рвётся
        // (колонна растягивается): scatter m28 24317:18115 → 24316:24069, screen m32 −9515, army m35 из стирания в отрыв; при
        // вето только на новую цель бросок закрыт как прежде, а мигание не сбрасывает уже идущий захват
        // ...и та форма (v114b: вето сразу на новую цель, на уже идущую — после 10 тиков) тоже отвергнута: удержанная цель вела
        // армию к флагу навстречу НАСТОЯЩЕМУ броску — scatter m28 и screen m32 те же, camp m31 22868:22895 красный. Мера «он ходит,
        // а не бросается» в боте уже есть — простой «держит дистанцию» (окно DETACH_WINDOW, срабатывал в 277 на 221-м: «флаги до
        // 521» — и в этом окне цель мигала): простой снимает вето «бой близко», как снимает вето «в контакте» ниже
        // (USE_STALL_LIFTS_RUSH_VETO); настоящий бросок дистанцию сокращает и простоя не даёт
        // ⚠️ Здесь стояла `val current = f.id == objectiveFlagId` — объявлена и не читалась ни разу (остаток
        // отвергнутой липкости v114b). Снята в v215; дифф отчёта пуст побайтово, как и обязан быть у мёртвого
        // бегун берёт флаг НАШЕЙ половины и под броском (v128, USE_RUNNER_HALF_UNDER_RUSH): вето «бой близко» держало и скаутов —
        // матчи 5, 8, 19 серий 367–406: rush=true с 10-го по 39-й, бегуны 0 detached, наш первый флаг на 42–98-м при его шести к
        // 80–91-му; пол паритета ниже по-прежнему считает цену дебаффа
        // ...и у вето подхода есть СРОК (v215, см. USE_RUSH_VETO_EXPIRES). Доктрина безфлагового дебюта
        // (`unflaggedRushNow`) сроку не подлежит и остаётся глобальной
        rushStale =  !Signals.unflaggedRushNow && fightImminentTicks > rushStartDist
        // ...И БЕЗФЛАГОВЫЙ БРОСОК КОНЧАЕТСЯ ПЕРВЫМ РАЗМЕНОМ (v284), а настоящий подход — нет. Вето держится, пока держится
        // сигнал, а удержание безфлагового броска (`rushHold`) — «он без флагов и в EVADE_RANGE от нас»; против ●ω<♥♪#6,
        // который флагов не берёт и весь бой стоит в 25 клетках, это весь матч. Прежде это пряталось: армия брала D5 шагом мимо
        // ворот; когда шаг пошёл через них (v282), в 9 из 10 игр из угла (85,88) первый флаг взят после 1000-го тика или
        // никогда (у контроля — в 2 из 34), и три игры отданы по очкам при живой и большей армии (12 на 7, 11 на 7, 10 на 6).
        // После первого размена бой уже идёт, и безфлаговый бросок как признак «бой через двадцать тиков» ничего не говорит;
        // но его сомкнутая армия, которая ИДЁТ на нас (`approachingNow`: темп сближения, сомкнутость, сдвиг его центра), —
        // говорит и после размена: первая редакция сняла вето целиком и уронила гейт на match30:camp (уничтожение лагеря на
        // 1459-м → проигрыш по очкам 21 635 : 24 096 — флаг, взятый на его подходе, лёг на бой)
        val rushNow = Signals.approachingNow || (Signals.unflaggedRushNow && firstFightTick == 0)
        if (GATE_RUSH.c("rushNow", rushNow) && GATE_RUSH.c("notStale", !rushStale) && GATE_RUSH.c("notIntercept", !intercept) && GATE_RUSH.c("notStalled", !(view.stalled))) {
            // пара к погоне за кайтером (v221, см. kiteChaseSeen): сколько отказов доктрины безфлагового броска
            // выдано, пока мы гонимся за отходящим, который бьёт нас сильнее, чем мы его
            if (Signals.unflaggedRushNow) { kvetoAll.n++; if (kiteChaseSeen) kvetoHit.n++ }
            return@Gate Verdict.Veto(if (Signals.unflaggedRushNow) "rush.unflagged" else "rush.approach")
        }
        Verdict.Next
    },
    Gate("debuff.whole") {
        // ДЕБАФФ СОБСТВЕННОГО ВЫХОДА НЕ БЕРЁТСЯ, ПОКА ЕГО АРМИЯ ЦЕЛА (v530, см. USE_NO_OUTPUT_DEBUFF_WHOLE_ARMY).
        // Ворота `rush` и `contact.mass` ниже говорят ровно это, но обе включаются ПОСЛЕ начала боя, а флаг берётся
        // бегуном на первых сорока тиках — и дебафф въезжает в решающий размен. Замер 32 живых матчей: флаг RANGED
        // (-20 % стрелкам за 3 очка в тик) наш в 46-50 % тиков окна 1-120, флагов ATTACK и HEAL у НЕГО ноль, а
        // уязвимость (+10 % входящего за 5 очков) он держит в 45-54 %. Цена дебаффа за одно очко счёта по нашему же
        // замеру: уязвимость 6,4, лечение 9,5, RANGED 13,0, ATTACK 13,3. И очки в этом окне не стоят ничего:
        // поражения кончаются на 350-м тике при 842 очках, победы идут 1275 тиков при 21 929
        // ...и УЗКО, чтобы не повторить урок v526 (одни ворота на два решения): вето держится только ДО ПЕРВОГО
        // РАЗМЕНА и только пока его целая армия идёт на нас. Именно этот флаг въезжает в решающий бой; после начала
        // боя решают прежние ворота `rush` и `contact.mass`. Широкая редакция (без срока) уронила match33:camp —
        // 23 050:23 371, 114 отказов за матч: лагерь надо давить очками весь матч. Одного срока не хватило: в лагере
        // размена НЕ БЫВАЕТ ВОВСЕ (`ffight=0`), и «до первого размена» там вечно, — поэтому второе условие: он
        // должен СТРЕЛЯТЬ, — и ЭТО БЫЛО ОШИБКОЙ, снятой живой серией: в первые сорок тиков не стрелял ещё никто,
        // молчание длиной STALL_TICKS истинно, и правило не сработало НИ РАЗУ (`dbfveto=0 %`, доля флага RANGED
        // 0,47 против 0,49 в контроле). Условия «он подходит» и «он стреляет» сняты: лагерь спасает не они, а
        // ОКНО ПЕРВОГО СТОЛКНОВЕНИЯ. Замер: флаг берётся бегуном на 20-40-м тике, первый размен около 60-го,
        // поражение кончается на 350-м. Вне окна решают прежние ворота, и лагерь давится очками весь матч.
        // ⚠️ Одного окна тоже мало: без признака сомкнутости упали `match21:spread` (5 949:24 266), `match31:farm`,
        // `match28:scatter` и `match33:scatter` — против РАЗРОЗНЕННОГО врага дебют флагами и решает гонку очков.
        // Признак взят готовый (`enemyMassedSignal`, v226): блоб, идущий вместе, сомкнут уже на двадцатом тике,
        // рассыпающаяся по флагам армия — нет. И третье, последнее: `approachingNow` — фермер сомкнут в клубок, но
        // НЕ ИДЁТ, и без этого условия падал `match31:farm` (18 469:23 732).
        // ⚠️ И ещё одна редакция, снятая живым логом: одного `approachingNow` мало. Замер тика за тиком показал, что
        // флаг RANGED берётся НЕ в дебюте, а на 55-м тике — ПОСЛЕ первого размена (46-й): `fmassed` до 40-го равен
        // нулю, на 50-м единице, и к моменту захвата он уже не «подходит», а ДЕРЁТСЯ, отчего вето не взводилось ни
        // разу (`dbfveto=0/19939` живьём). Поэтому признак «он идёт» дополнен признаком «размен уже был»
        if (USE_NO_OUTPUT_DEBUFF_WHOLE_ARMY && f.type != EFF_DAMAGE_TAKEN_MODIFIER &&
            ctx.threats.size >= MASS_ARMED_MIN && Signals.enemyMassedSignal &&
            (Signals.approachingNow || firstFightTick > 0) &&
            getTicks() <= FIRST_CLASH_TICKS) { debuffVeto.n++; return@Gate Verdict.Veto("debuff.whole") }
        debuffPass.n++
        Verdict.Next
    },
    Gate("contact.mass") {
        if (Signals.fightImminentNow && rushStale) capIdleRush.n++   // холостой прибор: своим счётчиком тоже (v451)
        // в контакте флаги не берём, пока есть кому драться: дебафф ложится на идущий бой (матч 9: скаут взял R3 на 125-м
        // тике — −20% стрелкам в решающем размене ради трёх очков в тик); без стрелков защищать нечего, а очки — всё,
        // что осталось (стенд m4 sleeper: запрет при охоте за обломками отдал матч по очкам)
        // при бесплодной охоте (см. STALL_TICKS) контакт мнимый — висящие в трёх-шести клетках крипы россыпи мигали
        // контактом, и цель-флаг пропадала через тик после назначения (стенд m19 spread)
        // ...и ВЕТО КОНТАКТА СНИМАЕТСЯ, КОГДА МЫ ОТСТАЁМ ПО СКОРОСТИ ОЧКОВ (v180). Разбор семи поражений серии: пять из
        // них — не бой, а гонка, где обе армии целы, а мы держим ТРИ флага против его четырёх и набираем 10-12 очков в
        // тик против его 13-15. Вето контакта не давало взять четвёртый почти весь матч, а снималось лишь за
        // LAST_CALL_TICKS до конца — то есть после того, как отрыв уже сделан. Паритет мощи при этом остаётся: доктрина
        // не в том, чтобы не брать флаги, а в том, чтобы не брать их ценой армии
        // ...и снятие вето стоит АРМИИ, если брать флаг под его ударом без запаса: серия v180 дала 12-8 с рейтингом
        // +16, гонок-поражений стало три вместо пяти, но разгромов пять вместо двух — армия гибла к 200-300 тику.
        // Поэтому вето снимается только при ПЕРЕВЕСЕ, а не при простом паритете (v181)
        // ...и ЦЕЛАЯ АРМИЯ САМА ПО СЕБЕ ЕСТЬ ЗАПАС (v187, разбор Coldkimchi#2). Вето существует ради одного — не терять
        // армию за флаги; когда армия цела, оно защищает уже не её, а нулевой счёт. Против Coldkimchi#2 наша мощь
        // структурно ниже его (лечение блока перекрывает наш урон, потому Ланчестер и даёт нам около нуля), поэтому
        // порог CAPTURE_EDGE не берётся НИКОГДА, и матч кончается «обе армии целы, флаги 1:4, счёт 3 210:18 145».
        // Условие здесь не про мощь, а про потери: пока армия почти не тронута и мы отстаём по скорости очков, флаг
        // берётся. Дебафф флага ложится на владельца — эту цену считает паритетный пол ниже, он остаётся на месте
        losingRace =  WorldState.behindOnScore && WorldState.enemyRate > WorldState.ourRate &&
            (
                ourPowerOf(ctx.army, ctx.combatEnemies) >= enemyPowerOf(ctx.combatEnemies, ctx.army) * CAPTURE_EDGE)
        // ...и ПАТ СНИМАЕТ ВЕТО КОНТАКТА (v189): бой, в котором за целое окно ни одна сторона не потеряла заметной
        // доли хитов, армии не угрожает, а дебафф флага в нём ничего не решает — решают очки (см. stalemateNow)
        // ВЕТО КОНТАКТА — ПО МАССЕ АРМИИ, А НЕ ПО ЛЮБОМУ КРИПУ (v214, решение оператора: вето становится местным).
        // Контакт определялся в файле дважды: в runArmy по МАССЕ (см. massArmy), а здесь — по любому нашему
        // вооружённому на полной скорости. Одного отбившегося крипа, задетого его пикетом на другом конце карты,
        // хватало, чтобы закрыть разом ВСЕ семь флагов. Живой замер по 12 матчам: `contact` — 512 отказов в
        // поражениях против 99 в победах, то есть главный дискриминатор исхода.
        // ⚠️ Это НЕ отвергнутая правка про `enemyNear` (см. комментарий там же): та меняла постуру, из-за чего
        // висящий у хранителя враг переставал отменять цель-флаг, и m20 spread перешёл из победы в поражение.
        // Здесь меняется потребитель — гейт захвата, — а постура не трогается вовсе.
        foes = ctx.threats
        val mass = centroidOf(ctx.army)
        val contactArmy = if (mass == null) ctx.army else ctx.army.filter { getRange(it, mass) <= MASS_RANGE }
        if (GATE_CONTACT_MASS.c("notLosingRace", !losingRace) && GATE_CONTACT_MASS.c("notStalled", !view.stalled) && GATE_CONTACT_MASS.c("notIntercept", !intercept) && GATE_CONTACT_MASS.c("striker", contactArmy.any { fullSpeed(it) && hasWeapon(it) }) && GATE_CONTACT_MASS.c("inContact", inContact(foes, contactArmy))) {
            // пара к вето контакта по размену (v221, только прибор): сколько отказов выдано контактом, в котором за
            // окно ни одна сторона не потеряла STALL_DAMAGE. Окно — прошлого тика: runRunners идёт раньше runArmy
            warmCapAll.n++; if (!view.exchangeLive) warmCap.n++
            return@Gate Verdict.Veto("contact.mass")
        }
        Verdict.Next
    },
    Gate("first.fight") {
        // ...и отдельно считаем то, что этой правкой снято: стычка одиночки вне массы
        if (GATE_CONTACT_MASS.c("whole.notLosingRace", !losingRace) && GATE_CONTACT_MASS.c("whole.notStalled", !view.stalled) && GATE_CONTACT_MASS.c("whole.notIntercept", !intercept) && GATE_CONTACT_MASS.c("whole.striker", ctx.army.any { fullSpeed(it) && hasWeapon(it) }) && GATE_CONTACT_MASS.c("whole.inContact", inContact(foes, ctx.army))) {
            capIdleEdge.n++   // холостой прибор: своим счётчиком тоже (v451)
        }
        // ПЕРВЫЙ БОЙ — БЕЗ ЛИШНЕГО ДЕБАФФА (v281). Пока его сомкнутая армия цела и размена ещё не было, флаг, после которого
        // флагов у нас станет больше, чем у него, не берётся: дебафф ложится на ВЛАДЕЛЬЦА, и платит его первый бой двух целых
        // армий, который решает аннигиляцию, а очки после выигранного боя берутся даром. Разбор 148 игр против ●ω<♥♪#6
        // (v263–v277): наш дебафф на тике контакта — 6-88, без него — 9-22 (Fisher p = 0,002), внутри одного угла 4-40 против
        // 9-22 (p = 0,033). Ворота ниже его пропускали двумя путями. Пол паритета: скаут брал R3 на 42–43-м тике из угла (12,9)
        // при мощи 3967 против порога 4087 × 0,97 = 3964 — модель мощи ценит R×0,8 в три процента, считая мили полными, а бой
        // решают стрелки (мили раздеты за первые тики); ровно так же он брал R3 против けろびー, и тогда закрыли только бросок
        // (`rush.unflagged`), а ●ω к 42-му не идёт на нас. И большинство флагов (v222): при 0:0 первый флаг даёт перевес и
        // открывается при паритете — так D5 (весь входящий ×1,1) брался в среднем за 9 тиков до его удара. Меру мощи оператор
        // трогать запретил, поэтому цена здесь не мощью, а счётом: сравняться с ним по флагам можно, обогнать — нет. Новых
        // чисел нет: «размен был» — тот же exchangeLive (STALL_DAMAGE за LEDGER_WINDOW), «сомкнут» — тот же признак, что у
        // броска. Снимается отставанием по очкам (очки важнее силы), бесплодной охотой, перехватом у фермера, его
        // неподвижностью и последним зовом выше; разбросанная по флагам армия (фермер) правило не включает
        // ...И ПЕРВЫЙ БОЙ КОНЧАЕТСЯ НЕ ТОГДА, КОГДА НАЧАЛСЯ (v420). Условие `firstFightTick == 0` читается «первый бой
        // ВПЕРЕДИ», то есть ворота снимались ровно в тот тик, когда первый размен начался, — а лишний дебафф платит не
        // подготовка к бою, а сам бой. Прибор говорил это давно и с нулевым разбросом: `cap=first.fight` даёт ровно
        // 14 крип-тиков запрета, все при t <= 10, во ВСЕХ 64 тестовых и 3 рейтинговых матчах, тогда как армия против
        // Coldkimchi#1 гибнет в медиане на t=530. Разрез тех же 64 игр по прибору `rate=` на t=150 (наш темп очков,
        // то есть держим ли мы флаг): темп 0 — 16-11 (59 % побед), темп больше нуля — 3-34 (8 %). Разница не в том,
        // берём ли флаг вообще, а КОГДА: медиана первого владения 280 в победах против 120 в поражениях при контакте
        // на 52-м. Направление проверено — группа без флага проигрывает размен ВДВОЕ хуже (`ledger` −1 753 против
        // −765 на t=150) и всё равно побеждает в семь раз чаще, то есть флаг не следствие плохого боя, а его причина;
        // парный замер внутри матча даёт −3,5 хита в тик при <= 1 флаге против −24,9 при 3 и больше (хуже в 41 матче
        // из 51). Условие становится «первый бой не РЕШЁН» — тем же `exchangeLive`, которым он и засекается строкой
        // выше в World, так что «начался» и «идёт» считаются одним признаком и новых чисел правило не вводит. В 91 %
        // поражений и 84 % побед размен в момент захвата ШЁЛ, то есть ворота теперь достают до случая. Снятия прежние
        // (отставание по очкам, застой, перехват) не тронуты: отстав по счёту, флаг берём — и это же открывает ворота,
        // когда флаги начнёт брать он. ⚠️ Гейт стенда к правке СЛЕП: отчёт побайтово тождествен базе, потому что на
        // стенде размен идёт тогда же, когда армия в контакте, а контакт перекрыт воротами `contact.mass` выше
        val firstFightAhead = GATE_FIRST_FIGHT.c("noFightYetOrUnsettled", firstFightTick == 0 || (USE_FIRST_FIGHT_UNSETTLED && view.exchangeLive)) &&
            GATE_FIRST_FIGHT.c("enemyMassed", Signals.enemyMassedSignal) && GATE_FIRST_FIGHT.c("notPassive", !ctx.passiveEnemy)
        if (GATE_FIRST_FIGHT.c("firstFightAhead", firstFightAhead) && GATE_FIRST_FIGHT.c("notBehindOnScore", !WorldState.behindOnScore) && GATE_FIRST_FIGHT.c("notStalled", !view.stalled) && GATE_FIRST_FIGHT.c("notIntercept", !intercept)) {
            val ourAfter = ctx.flags.count { it.ours } + 1
            val hisAfter = ctx.flags.count { it.theirs } - (if (f.theirs) 1 else 0)
            if (ourAfter > hisAfter) return@Gate Verdict.Veto("first.fight")
        }
        Verdict.Next
    },
    Gate("enough") {
        // ФЛАГОВ — СКОЛЬКО НУЖНО ДЛЯ ПОБЕДЫ ПО ОЧКАМ (v289). Пока его сомкнутая армия стоит, каждый флаг — дебафф на идущий
        // бой, а очки нужны только чтобы быть впереди к концу: аннигиляция решает раньше. Разбор v281–v286 (Opus, 48 игр против
        // Coldkimchi#1 и 72 против ●ω<♥♪#6): в контакте при равенстве сил без своих флагов чистый размен +0,1 хита в тик,
        // с флагом лечения −26, с тремя флагами и больше −13,6; при нашем обвале против Coldkimchi мы держали 3+ флага в 33 из
        // 58 случаев, при его — в 7 из 22; флаг лечения на наших обвалах против ●ω — 14 из 35, на его — 1 из 17 (p = 0,011). Оба
        // флагов почти не берут, пока наша армия цела, и одного-двух флагов хватает на победу по очкам. Поэтому новый флаг
        // берётся, только если по проекции на конец матча (счёт + темп × остаток) без него мы не впереди; когда он начнёт
        // брать флаги и проекция перевернётся, правило само откроет следующий. Новых чисел нет: проекция — та же, что у
        // `losingAtTheEnd` выше, «стоит» — тот же признак сомкнутости, что у ворот первого боя
        val raceWon = (ourScore - enemyScore) + (WorldState.ourRate - WorldState.enemyRate) * ticksLeft > 0
        if (GATE_ENOUGH.c("raceWon", raceWon) && GATE_ENOUGH.c("enemyMassed", Signals.enemyMassedSignal) && GATE_ENOUGH.c("notPassive", !ctx.passiveEnemy) && GATE_ENOUGH.c("notStalled", !view.stalled) && GATE_ENOUGH.c("notIntercept", !intercept)) return@Gate Verdict.Veto("enough")
        Verdict.Next
    },
    Gate("parityOk") {
        // паритет (см. PARITY_FLOOR): не впереди или отрыв не растёт — флаг, оставляющий не меньше PARITY_FLOOR их
        // мощи; впереди с растущим отрывом — только не слабее
        // СИЛА ВРАГА ДЛЯ ЭТОГО ФЛАГА (v214, решение оператора: вето становится местным). Здесь стояло
        // `powerAfter(ctx, f)`, где сторона врага — ВСЯ его армия без учёта расстояния: крип в шестидесяти клетках
        // весил столько же, сколько стоящий вплотную. Дебафф флага глобален, поэтому его цену платит тот бой,
        // который РЕАЛЬНО случится, — это либо идущий бой (fightPack, «кто успевает прийти к нашей массе»), либо
        // бой за сам флаг (его боевые в FLAG_GUARD_RANGE, уже посчитаны в collectFlags как guards).
        // Кто не участвует ни в одном, не платит и не считается. Доктрина паритета остаётся: powerAfterFor
        // по-прежнему берёт нашу мощь С дебаффом флага против его без дебаффа и требует пол.
        // Взято дешёвое множество (guards вместо packAt): оно не стоит ни одного BFS. Если прибор capopp покажет,
        // что локализация мало что меняет, следующим шагом сюда войдёт packAt с полем пути к флагу.
        // ...и ПУСТО ЗНАЧИТ ПУСТО (v216, см. USE_EMPTY_OPP_MEANS_FREE): откат `.ifEmpty { ctx.combatEnemies }`
        // выключал локализацию ровно там, где она нужнее всего, — боя нет и флаг не охраняется
        val oppLocal = ctx.combatEnemies.filter { it.id in fightPackIds || f.guards.any { g -> g.id == it.id } }
        // ...НО ТОТ, КТО УЖЕ ДРАЛСЯ С НАМИ, ПЛАТИТ ВСЕЙ АРМИЕЙ (v433–v434, см. USE_GATE_VS_FIGHTER): дебафф флага не снимается
        // до конца матча, и цену платит бой, который СЛУЧИТСЯ. Вне контакта локальный противник пуст или почти пуст (0,10–0,30
        // его крипа на флаг при 10–11 боевых на карте), powerOf(пусто) даёт ноль, и пол вырождается в «ours >= 0»: так прошли
        // 4 захвата из 21 в поздних поражениях от Coldkimchi#1. Первая редакция (v433) различала по «сомкнут и не пассивен» —
        // и не сработала: между боями его армия стоит ГРУППАМИ у флагов (`massed=false` с t=140 при 12 вооружённых, A3 с
        // шестью стражами), пять флагов взяты при мере 554 против 987. Вторая («был размен», firstFightTick) уронила гейт на
        // match34:scatter (21 979 : 24 321 по очкам): россыпь тоже дерётся — стычками у флагов по одному-два крипа, и целиком
        // она не приходит никогда. Различитель — история ЭТОГО матча: он хоть раз дрался с нами СОМКНУТЫМ (fightMassedSeen)
        // и не спит; фермер, который не бьёт вовсе (kills=0, fire=0 за матч), и россыпь сюда не попадают
        // ...И ЗАЩЁЛКА СНИМАЕТСЯ ЗАМЕРОМ (v534, см. USE_UNWIPEABLE_OPENS): «он дрался сомкнутым» — одно событие, а
        // вопрос ворот в том, решит ли он матч боем. Цена ЭТОГО флага входит в собственный замер: лечение берётся с
        // его дебаффом лечения, урон — с его множителем получаемого урона
        val hypo = hypoModsFor(ctx, f, true)
        val unwipeable = cannotWipeUs(hypo.heal, 1.0 / hypo.hits.coerceAtLeast(0.01))
        if (USE_GATE_VS_FIGHTER && fightMassedSeen && !ctx.passiveEnemy) {
            unwipeAll.n++
            if (unwipeable) unwipeOpen.n++
        }
        foughtFoe = USE_GATE_VS_FIGHTER && fightMassedSeen && !ctx.passiveEnemy && !unwipeable
        opp = if (foughtFoe) ctx.combatEnemies else oppLocal
        capOppSum.n += opp.size
        capAllSum.n += ctx.combatEnemies.size
        val after = powerAfterFor(ctx,
            ctx.side,
            opp, f)
        ours = after.first; theirs = after.second
        // ИНВЕРСИЯ СНЯТА (v214). Здесь стояло `needed = ourScore <= enemyScore || ourRate <= enemyRate`, и когда
        // мы ВЕДЁМ по счёту и по темпу, пол становился CAPTURE_FLOOR = 1.0 — СТРОЖЕ, чем PARITY_FLOOR = 0.97 при
        // отставании. То есть выигранная позиция запрещала закреплять выигрыш. Замер по 12 живым матчам говорит,
        // что решают именно такие матчи: три поражения при ЖИВОЙ армии с разрывом 603, 1628 и 292 очка из ~18 000,
        // и одна победа с разрывом 168 — это 60–100 тиков ОДНОГО флага. Доктрина паритета остаётся: пол по-прежнему
        // сравнивает нашу мощь С дебаффом флага против его без дебаффа, просто перестаёт ужесточаться от того,
        // что мы впереди.
        // неподвижный враг — тоже армия: «пассивный» порог 0.95 пустил третий флаг против спящего, тот проснулся, и бой
        // при 0.96 был проигран (стенд m6 sleeper); порог один
        // проигранная гонка с тем, кто ни разу не ударил (v63, см. PARITY_FLOOR_LOST)
        val lostRace = lostRaceNow(view)
        floor = if (lostRace) PARITY_FLOOR_LOST else if (view.stalled) PARITY_FLOOR_STALLED else PARITY_FLOOR
        // ПАРА К КЛАПАНУ (v218, см. lostRaceOpened): «послабление решило исход» — флаг прошёл по PARITY_FLOOR_LOST
        // и НЕ прошёл бы по PARITY_FLOOR. Считается ЗДЕСЬ, а не у признака, потому что вопрос прибора не «был ли
        // признак истинен», а «изменил ли он хоть один отказ»
        if (lostRace) {
            lostRaceOffers.n++
            if (ours >= theirs * floor && ours < theirs * PARITY_FLOOR) lostRaceOpened.n++
        }
        // ...и в ПАТУ паритетный пол тоже молчит: он сравнивает мощь, а в бою, где никто никого не убивает, мощь
        // обеих сторон ланчестером считается около нуля, и сравнивать нечего (v189)
        if (ours >= theirs * floor) return@Gate Verdict.Allow
        Verdict.Next
    },
    Gate("groupSafe") {
        // ПАРИТЕТ НЕ СТОРОЖ ПРОТИВ ТОГО, КТО НЕ БЬЁТ НАШИХ В ГРУППЕ (v336, вторая проба). Первая (v328) стояла поверх
        // «держатель не уходит под огнём» (v327) и дала хуже — но там некому было и брать: захватов 17 за матч. На нынешней
        // основе запрет виден числом: ворота отказывают по паритету 3 134 раза за матч из 4 411 отказов, и бегуны 52 %
        // времени сидят без флага (RESERVE 2 273 из 4 405 меток). Доктрина паритета бережёт армию перед боем, которого с
        // фермером не будет вовсе: за матч у нас kills=0 и fire=0, его крипы ни разу не входят в нашу досягаемость
        // ...и НЕ ПРОТИВ ТОГО, КТО УЖЕ ДРАЛСЯ И ВЕРНЁТСЯ (v433–v434, см. USE_GATE_VS_FIGHTER): признак отвечает о ПРОШЛОМ уроне
        // за GROUP_WINDOW = 40 тиков, а цена флага — величина будущая и невозвратная; у Coldkimchi#1 «не бьёт» — это пауза
        // между боями (8 захватов из 21, в 6aae34db признак держался 750 тиков подряд и пропустил флаг при 879 против 2 672)
        if (Signals.groupSafe && !foughtFoe) return@Gate Verdict.Allow
        Verdict.Next
    },
    Gate("majority") {
        // ФЛАГОВ БОЛЬШЕ ЦЕНОЙ НЕБОЛЬШОГО МИНУСА (v222, решение оператора, см. USE_FLAG_MAJORITY): армии СЕЙЧАС на паритете,
        // перевеса по флагам у нас нет, а этот флаг его даёт — минус ровно дебафф этого флага
        run {
            val side = ctx.side
            val oursNow = ourPowerOf(side, opp)
            val theirsNow = enemyPowerOf(opp, side)
            if (oursNow >= theirsNow * floor) {
                majOffers.n++
                val ourFlags = ctx.flags.count { it.ours }
                val hisFlags = ctx.flags.count { it.theirs }
                val ourAfter = ourFlags + 1
                val hisAfter = hisFlags - (if (f.theirs) 1 else 0)
                if (ourFlags <= hisFlags && ourAfter > hisAfter) {
                    majOpened.n++
                    return@Gate Verdict.Allow
                }
            }
        }
        Verdict.Next
    },
    Gate("parity") {
        Verdict.Veto("parity(${ours.toInt()}/${(theirs * floor).toInt()})")
    },
).also { captureGateRows = it }

/** Проигранная гонка (v63/v88): проигрыш по проекции на конец матча при PASSIVE_TICKS без удара по нам (v99: одна и та же
 *  для порога захвата и для стаи у свободного флага, см. USE_LOST_RACE_PACK_PARITY). */
internal fun lostRaceNow(view: ExchangeView): Boolean {
    val ticksLeft = arenaInfo.ticksLimit - getTicks()
    val losingAtTheEnd = (ourScore - enemyScore) + (WorldState.ourRate - WorldState.enemyRate) * ticksLeft <= 0
    val quiet = lastHurtTick == 0 || getTicks() - lastHurtTick >= FARMER_QUIET   // тишина (v65, см. FARMER_QUIET)
    // КЛАПАН ПО РАЗМЕНУ, А НЕ ПО ТИШИНЕ (v218, решение оператора). Здесь стояло `quietShort` — «сто тиков
    // ПОДРЯД без единого полученного удара». Против бота, чей пикет нас постоянно задевает, такой тишины не
    // наступает никогда, и послабленный пол PARITY_FLOOR_LOST не открывался ни разу: разбор двадцати матчей
    // v217 дал 1531 отказ по паритету в поражениях против 40 в победах, а в проигранных забегах 582, 1753 и
    // 2407 — при том, что в けろびー#12 (#20) за все 1900 тиков не разменяно НИ ОДНОГО хита с обеих сторон.
    // Признак заменён на СОБСТВЕННОЕ определение боя в этом файле — `netDamage` (см. STALL_DAMAGE): «за
    // STALL_TICKS любая сторона потеряла STALL_DAMAGE хитов». Нового числа не заводится, и оно вычисляется
    // из живых хитов обеих сторон, а не названо под текущего соперника. По замеру делит правильно: в #20
    // окно показало ledgerw=0/0/0 (0 < 300 — клапан открыт), в аннигиляции #1 потери шли ~3000 за окно
    // (много больше 300 — клапан закрыт, доктрина паритета цела).
    // ⚠️ Доктрина НЕ меняется: пол по-прежнему сравнивает нашу мощь С дебаффом флага против его без дебаффа,
    // меняется только признак «бой идёт». Меру мощи оператор трогать запретил — она остаётся зажатой в ноль
    // его лечением, и это открытый предмет
    // ...и признак сужен до НАШИХ потерь (v218, замер гейта). Первая редакция брала `netDamage` целиком —
    // «за окно потеряла ЛЮБАЯ сторона», — и уронила две строки лагеря: match31:camp отрыв 5189 -> 2185,
    // match34:camp уничтожение с t=951 на t=1212. Причина в асимметрии: пока мы безнаказанно бьём лагерь,
    // потери есть У НЕГО, признак говорит «бой идёт», и строгий пол возвращается там, где мы ВЫИГРЫВАЕМ
    // бой. Пол существует ради одного — не потерять НАШУ армию за флаги; значит и спрашивать надо про наши
    // потери. Окно и порог те же (LEDGER_WINDOW = STALL_TICKS, STALL_DAMAGE), новых чисел по-прежнему нет
    val quietShort = view.ourLostWindow < STALL_DAMAGE || getTicks() - lastHurtTick >= PASSIVE_TICKS
    return losingAtTheEnd && quietShort
}

internal fun planCapture(ctx: Ctx, step: Position?) {
    if (step == null) return
    ctx.flags.firstOrNull { !it.ours && it.pos.x == step.x && it.pos.y == step.y }?.let { WorldState.announceCapture(it.id) }
}

/**
 * ОН НЕ УСПЕВАЕТ РЕШИТЬ МАТЧ БОЕМ (v534, см. USE_UNWIPEABLE_OPENS).
 *
 * Величина вместо защёлки `fightMassedSeen`: хватит ли ему оставшихся тиков, чтобы снять с нас все хиты, если
 * остаток матча он будет драться так же сильно, как в самый сильный свой отрезок за этот матч. Лечение — общим
 * котлом по телам (уничтожают армию целиком), урон — пиковым окном GROUP_WINDOW. [healMul] и [takenMul] — доля
 * «после» к «сейчас» для берущегося флага: дебафф, за который отвечает решение, входит в собственную цену.
 *
 * До первого размена пик равен нулю и мерить нечего, поэтому ворота молчат, пока размена не было и пока после
 * него не прошло GROUP_WINDOW — то есть пока пик не успел записаться хотя бы одним полным окном.
 */
internal fun cannotWipeUs(healMul: Double, takenMul: Double): Boolean {
    if (!USE_UNWIPEABLE_OPENS) return false
    if (firstFightTick <= 0 || getTicks() - firstFightTick < GROUP_WINDOW) return false
    val left = arenaInfo.ticksLimit - getTicks()
    if (left <= 0) return true
    return minOf(wipeByHits(healMul, takenMul), wipeByBodies()) > left
}

/** Оценка «сколько тиков ему нужно на наши хиты» (v536, см. USE_WIPE_BY_NET_LOSS). `hisPeakDamage` считается из
 *  `ourDamageTaken` — хитов, которых армия НЕДОСЧИТАЛАСЬ, то есть УЖЕ за вычетом лечения; поэтому лечение здесь не
 *  вычитается (v534 вычитала его второй раз). Дебафф лечения берущегося флага поднимает будущий чистый темп — делим. */
internal fun wipeByHits(healMul: Double, takenMul: Double): Double {
    val rate = Signals.hisPeakDamage * takenMul / healMul.coerceAtLeast(0.01)
    if (rate <= 0.0) return Double.MAX_VALUE
    return Signals.ourHitsNow / rate
}

/** Вторая оценка той же величины — по ТЕЛАМ (v536): при темпе, с каким он их снимает с начала матча, хватит ли ему
 *  тиков на оставшиеся. Тела — итог, которого модель хитов не видит: лечение не спасает того, кого сосредоточили. */
internal fun wipeByBodies(): Double {
    if (!USE_WIPE_BY_NET_LOSS) return Double.MAX_VALUE
    val lost = Signals.ourBodiesStart - Signals.ourBodiesNow
    if (lost <= 0) return Double.MAX_VALUE
    return getTicks().toDouble() * Signals.ourBodiesNow / lost
}

/** Мощь сторон, если мы возьмём ещё этот флаг (и те, на которые уже шагаем в этот тик): наша — с их дебаффами;
 *  вражья — без них, если флаги были его. */
internal fun powerAfter(ctx: Ctx, f: FlagInfo): Pair<Double, Double> =
    powerAfterFor(ctx, ctx.side, ctx.combatEnemies, f)

/** То же для заданной стороны и группы врага (v95: пул проверяет ядро без крипа с дебаффом его флага-цели). */
/** Множители стороны в состоянии «после захвата [f]» (и тех флагов, на которые мы шагаем в этот тик): отношение
 *  дебаффов «после» к нынешним. Вынесено из [powerAfterFor] в v534 — той же величиной пользуются ворота
 *  «он не успевает нас уничтожить» (см. cannotWipeUs); тело не тронуто. */
internal fun hypoModsFor(ctx: Ctx, f: FlagInfo, mine: Boolean): HypoMods {
    val taking = HashSet(WorldState.plannedCaptures); taking.add(f.id)
    // и флаг-цель армии (v121): захват, который уже идёт, — часть состояния «после»
    fun k(type: String): Double {
        val now = ctx.flags.count { it.mine == mine && it.type == type }
        // его флаги В ПОЛЁТЕ (v128, USE_HIS_FLAGS_IN_FLIGHT): свободный флаг с его вооружённым вплотную — его в состоянии «после»,
        // и пол паритета видит симметричный размен, а не наш дебафф против его чистой армии (гастролёр берёт D5 на 39–41-м)
        val after = ctx.flags.count { it.type == type && (if (mine) (it.ours || it.id in taking) else ((it.theirs && it.id !in taking))) }
        return stackMul(type, after) / stackMul(type, now).coerceAtLeast(0.01)
    }
    return HypoMods(ranged = k(EFF_RANGED_ATTACK_MODIFIER), melee = k(EFF_ATTACK_MODIFIER), heal = k(EFF_HEAL_MODIFIER), hits = 1.0 / k(EFF_DAMAGE_TAKEN_MODIFIER))
}

internal fun powerAfterFor(ctx: Ctx, side: List<Creep>, opp: List<Creep>, f: FlagInfo): Pair<Double, Double> {
    val ourMods = hypoModsFor(ctx, f, true)
    val theirMods = hypoModsFor(ctx, f, false)
    // для captureAllowed сторона — армия с вооружёнными и лечащими бегунами (v59): паритет захвата — страховка от
    // аннигиляции стороны, а отряженные в бегуны (см. USE_DETACH) живы и вооружены. Ядро без пяти отряжённых стояло в
    // паритете (2918 против 2954), захват был запрещён ВСЕМ, и четверо отряжённых 800 тиков стояли POISED в клетке от
    // свободных D5, A3, R3 и H4 — 3174:22931, двенадцатый проигрыш фермеру (матч 139); до отряда, при 1,41, захваты были
    // разрешены и скаутам
    return powerOf(side, opp, ourMods, theirMods) to powerOf(opp, side, theirMods, ourMods)
}

/** Цена флага в силе — доля нашей мощи, которая останется после захвата (1 — бесплатно): флаг лечения
 *  для армии почти без лекарей дёшев, флаг уязвимости стоит всем; дорогие берутся последними. */
internal fun captureCost(ctx: Ctx, f: FlagInfo): Double {
    if (f.ours || ctx.combatEnemies.isEmpty()) return 1.0
    // ОДИН СОСТАВ ПО ОБЕ СТОРОНЫ ДРОБИ (v216, см. USE_CAPTURE_COST_ONE_SIDE): знаменатель обязан считаться по
    // той же стороне, что и числитель в powerAfter, иначе отношение выходит больше единицы и обрезается в 1,0
    val side = ctx.side
    val now = ourPowerOf(side, ctx.combatEnemies)
    if (now <= 0.0) return 1.0
    return (powerAfter(ctx, f).first / now).coerceIn(0.0, 1.0)
}

internal fun chooseFlagObjective(ctx: Ctx, view: ExchangeView, approachRate: Double, farmerQuietNow: Boolean, group: List<Creep>, pushRatio: Double, escapeNeeded: Boolean = false, onlyFlagId: String? = null): Objective? {
    if (group.isEmpty()) return null
    var best: Objective? = null
    for (f in ctx.flags) {
        objDropN.n++
        if (f.ours) { objDrop.bump("ours"); continue }
        if (onlyFlagId != null && f.id != onlyFlagId) { objDrop.bump("cpu"); continue }   // страховка CPU (v131c)
        if (!captureAllowed(ctx, f, view, CapAsker.ARMY)) { objDrop.bump("gate"); continue }
        // СВОЯ ПОЛОВИНА (v312, см. GROUP_SAFE_DMG): против фермера гонка решается не числом захватов, а числом
        // УДЕРЖАННЫХ флагов, а удержать можно те, до которых ему дальше, чем нам. Свои R3, A3, H4 и центральный D5 — это
        // 15 очков в тик против его 10; контрфакт разбора (гарнизоны на своих R3, A3 и обоих H4) давал 30,4 тыс. : 18,1 тыс.
        // и 20 побед из 21. Флаг его половины берётся, только когда своя уже наша
        if (Signals.groupSafe && getRange(f.pos, ctx.home) > getRange(f.pos, ctx.enemyHome) &&
            ctx.flags.any { !it.ours && getRange(it.pos, ctx.home) <= getRange(it.pos, ctx.enemyHome) }) {
            objDrop.bump("far"); continue
        }
        // ЦЕЛЬ АРМИИ НЕ ДУБЛИРУЕТ ФЛАГ БЕГУНА (v216). Обе соседние раздачи это уже проверяют — `commandRace`
        // («флаг, взятый бегуном, не дублируем») и `grabberOf` (исключает флаг-цель), — а самая дорогая, цель
        // ВСЕЙ армии, не проверяла. При потолке в один-два работающих крипа на семи флагах дубль означает, что
        // работающий флаг остаётся ОДИН. Флаг, который бегуну брать запрещено, сюда всё равно не дойдёт: гейт
        // `captureAllowed` у них общий и стоит строкой выше
        // ...и только если бегун ДОЙДЁТ РАНЬШЕ. Первая редакция снимала флаг с любого назначения, и это
        // уронило гейт: строка match33:scatter перестала проходить, отрыв −33 691, три строки из 135 остались
        // без изменений. Причина: назначение `runnerFlag` держится и за убегающим, и за тем, кто стоит у флага
        // без права встать, — армия теряла цель, до которой бегун не дойдёт никогда
        val flow = flowTo(ctx, f.pos)
        val travel = group.maxOf { pathTicks(it, flow, it.key) }
        if (travel >= Int.MAX_VALUE / 4) { objDrop.bump("nopath"); continue }
        if (escapeNeeded) {
            // покинутую точку уклонения армия не идёт «захватывать»: у R3 (13,49) счёт места упал, точка покинута — и
            // тут же выбрана целью в шести тиках, навстречу врагу (матч 12, t=188)
            val left = evadeLeft
            if (left != null && left.x == f.pos.x && left.y == f.pos.y) { objDrop.bump("evadeLeft"); continue }
            // только флаг с выходом (см. ESCAPE_MARGIN, REACTION_LAG): за H4 в угол (8,90) армия шла 52 тика, пока
            // враг шёл на неё с 80 клеток (матч 11); за R3 (13,49) — 41 тик вдоль западного края при спящем враге,
            // и тот пошёл на полпути (матч 12)
            if (exitMargin(ctx, f.pos, travel, approachRate) < ESCAPE_MARGIN) { objDrop.bump("exit"); continue }
        }
        // ⚠️ ПРОБОВАНО И ОТВЕРГНУТО (матч 23): запрет марша длиннее, чем врагу дойти до нашей армии, когда мы впереди
        // с растущим отрывом. Ни одного проигрыша стенда он не предотвращает, а бот становится пассивен всякий раз,
        // когда ведёт: m23 rush 23279→21496, m22 wing 23180→21362, m17 wing 23638→21510, m7 sleeper 22462→20033.
        // Растянутую колонну чинит масса (см. MASS_RANGE), а не отказ от цели
        // стая без урона ничего не охраняет: три лекаря врага, лечащие друг друга, делали цену боя бесконечной для
        // ослабленной армии, и она 1500 тиков держала пост при шести свободных флагах (стенд m2 rush)
        val pack = packAt(ctx, f.pos, flow, travel).filter { threatening(it, ctx.enemyCreeps) }
        val current = f.id == objectiveFlagId
        val ratio = if (current) LOCAL_ENTER_RATIO else pushRatio
        // цена боя — гейт на ВХОД к охраняемому флагу (лазейка «уже в контакте» отправила армию к дальнему
        // флагу с девятью охранниками сквозь наступающую армию — стенд rush, t=61)
        // проигранная гонка (v99, USE_LOST_RACE_PACK_PARITY): стая у флага, у которого его вооружённых сейчас нет, — по паритету
        // ...и тот же обход в режиме пар (v304, см. GROUP_SAFE_DMG): «цена боя у флага» считает бой, которого не будет —
        // он от наших групп отходит. Против けろびー#19 стая отменяла флаг-цель 4 146 раз из 8 057 отказов, армия
        // оставалась без цели три четверти матча, и мы держали 1,9 флага против его 4,8
        val ok = pack.isEmpty() || (farmerQuietNow) || Signals.groupSafe ||
            (ourPowerOf(group, pack) >= enemyPowerOf(pack, group) * ratio && fightCost(pack, group) <= group.maxOf { speedSlack(it) })
        if (!ok) { objDrop.bump("pack"); continue }
        objDrop.bump("taken")
        // гистерезис: текущая цель ценнее на четверть, чтобы не прыгать между равными; дорогой по силе — позже
        val value = f.swing * captureCost(ctx, f) / (travel + 10) * (if (current) 1.25 else 1.0)
        if (best == null || value > best.value) best = Objective(f, pack, value, travel)
    }
    return best
}

internal fun retreatPoint(ctx: Ctx): Position {
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
        val reach = ctx.army.count { flow[it.key] >= 0 }
        if (reach == 0) continue
        // выигрыш дистанции от врага НА КЛЕТКУ ПУТИ: «самая дальняя от врага» точка (3,96) лежала за его
        // флангом — 66 клеток пути ради 62 дистанции, мимо его строя; дом в 17 клетках даёт 22
        val score = getRange(c, enemy) - getRange(c, ctx.ourCentroid)
        if (score > bestScore) { bestScore = score; best = c }
    }
    retreatTarget = best
    return best
}

/** Точки выхода: семь флагов и оба дома (углы — карманы, их нет; см. EVADE_SAFE). */
/** Точки выхода: флаги, оба дома и ЧЕТЫРЕ угла карты. Флаги стоят между домами — на оси подхода врага, и с одними
 *  флагами «прочь» не существовало: уклонение от броска шло к R3 навстречу врагу 30 клеток и было поймано у флага
 *  (матч 34, t=3–57). Углы — то, что всегда лежит в стороне от оси. */
internal fun escapeCandidates(ctx: Ctx): List<Position> =
    ctx.flags.map { it.pos } + listOf(ctx.home, ctx.enemyHome) +
        // углы — только при броске: как постоянные кандидаты они меняли всякое уклонение (102 сценария в одну сторону,
        // 105 в другую, потеря армии m9 sleeper), а нужны они там, где флаги лежат на оси подхода
        (if (Signals.unflaggedRushNow) listOf(InfluenceMap.cell(3, 3), InfluenceMap.cell(3, 96), InfluenceMap.cell(96, 3), InfluenceMap.cell(96, 96)).map { passableNear(it) } else emptyList())

/** Поля потока к точкам выхода и ход врага до каждой — раз в EVADE_EVAL_EVERY тиков (девять полей). */
internal fun refreshEscape(ctx: Ctx, armed: List<Creep>) {
    val now = getTicks()
    if (now - escapeAt < EVADE_EVAL_EVERY && StrategistState.escapeFlows.isNotEmpty()) return
    escapeAt = now
    StrategistState.escapeFlows.clear(); StrategistState.escapeTheirs.clear(); StrategistState.escapeNearest.clear()
    for (c in escapeCandidates(ctx)) {
        val key = c.key
        val flow = flowTo(ctx, c)
        StrategistState.escapeFlows[key] = flow
        var bestTicks = Int.MAX_VALUE / 4
        var bestCell = -1
        for (e in armed) {
            val t = pathTicks(e, flow, e.key)
            if (t < bestTicks) { bestTicks = t; bestCell = e.key }
        }
        StrategistState.escapeTheirs[key] = bestTicks
        StrategistState.escapeNearest[key] = bestCell
    }
}

/** Запас выхода из точки c при нашем прибытии туда через arrive тиков. Преследователь идёт ЗА НАМИ, к c, а не к
 *  выходам: ближайший к c враг проецируется по полю к c на approachRate × arrive шагов, и от этой клетки считается
 *  его путь к каждому другому выходу минус наш путь от c туда; лучший из них — запас. Фора «идёт к выходу
 *  мгновенно» браковала всякую цель при идущем на нас враге, и армия сидела дома до боя в кармане (стенд m10
 *  hunter, m8 army); без выхода точка — карман (матч 11). */
internal fun exitMargin(ctx: Ctx, c: Position, arrive: Int, approachRate: Double): Int {
    val ckey = c.key
    val flowC = StrategistState.escapeFlows[ckey] ?: return Int.MIN_VALUE / 2
    val start = StrategistState.escapeNearest[ckey] ?: return Int.MIN_VALUE / 2
    val theirsC = StrategistState.escapeTheirs[ckey] ?: return Int.MIN_VALUE / 2
    if (theirsC >= Int.MAX_VALUE / 4) return Int.MIN_VALUE / 2   // его путь неизвестен — выход не подтверждён (v54)
    // при броске (см. EVADE_EQUAL_RATIO) преследователь идёт за нами на ПОЛНОЙ скорости: проекция по замеренному
    // темпу (0.42 в паузе колонны) считала дом безопасным выходом, и армия ушла в свой угол под удар (матч 32)
    val rate = if (Signals.unflaggedRushNow) 1.0 else approachRate
    val pursuer = if (start < 0) -1 else projectAlong(flowC, start, minOf(theirsC, (rate * arrive).toInt() + REACTION_LAG))
    var best = Int.MIN_VALUE / 2
    for (d in escapeCandidates(ctx)) {
        if (d.x == c.x && d.y == c.y) continue
        val flow = StrategistState.escapeFlows[d.key] ?: continue
        val step = flowNear(flow, c)
        if (step < 0) continue
        val theirs = if (pursuer < 0) Int.MAX_VALUE / 4 else flowNear(flow, InfluenceMap.cell(pursuer / 100, pursuer % 100)).let { if (it < 0) Int.MAX_VALUE / 4 else it }
        best = maxOf(best, theirs - step)
    }
    return best
}

/** Точка уклонения (см. EVADE_SAFE): из точек выхода — с наибольшим счётом (меньший из запасов прибытия и выхода);
 *  где стоим — только пока счёт не меньше EVADE_SAFE; прежняя держится, пока не хуже лучшей на EVADE_HYSTERESIS;
 *  null — карман, уходить некуда. */
/** Точка выхода для одиночного бегуна: лучшая по меньшему из запасов прибытия и выхода на ЕГО пути, не та, где он
 *  стоит (см. evadePoint для армии). */
internal fun runnerEscape(ctx: Ctx, s: Creep, approachRate: Double): Position? {
    var best: Position? = null
    var bestScore = Int.MIN_VALUE
    for (c in escapeCandidates(ctx)) {
        if (getRange(c, s) <= 2) continue
        val key = c.key
        val flow = StrategistState.escapeFlows[key] ?: continue
        val theirs = StrategistState.escapeTheirs[key] ?: continue
        val ticks = pathTicks(s, flow, s.key)
        if (ticks >= Int.MAX_VALUE / 4) continue
        val score = minOf(theirs - ticks, exitMargin(ctx, c, ticks, approachRate))
        if (best == null || score > bestScore) { bestScore = score; best = c }
    }
    return best
}

internal fun evadePoint(ctx: Ctx, armed: List<Creep>, strikers: List<Creep>, approachRate: Double): Position? {
    val now = getTicks()
    val cur = evadeTarget
    val arrived = cur != null && getRange(ctx.ourCentroid, cur) <= EVADE_ARRIVED
    if (cur != null && !arrived && now - evadeEvaluatedAt < EVADE_EVAL_EVERY) return cur
    evadeEvaluatedAt = now
    // прибыли в другую точку — покинутая снова допустима (см. evadeLeft)
    evadeLeft?.let { l -> if (arrived && cur != null && (cur.x != l.x || cur.y != l.y)) evadeLeft = null }
    val left = evadeLeft
    var best: Position? = null
    var bestScore = Int.MIN_VALUE
    var bestArrive = 0
    var bestExit = 0
    var curScore = Int.MIN_VALUE
    // при броске (см. EVADE_EQUAL_RATIO) выход обязан быть ПРОЧЬ: не ближе к центру вражеской армии, чем мы сейчас.
    // Запас «мы придём первыми» считал R3 на оси подхода безопасным, и армия шла врагу навстречу (матч 34)
    val enemyCentre = if (Signals.unflaggedRushNow) centroidOf(armed) else null
    for (c in escapeCandidates(ctx)) {
        if (left != null && c.x == left.x && c.y == left.y) continue
        if (enemyCentre != null && getRange(c, enemyCentre) <= getRange(ctx.ourCentroid, enemyCentre)) continue
        val key = c.key
        val flow = StrategistState.escapeFlows[key] ?: continue
        val theirs = StrategistState.escapeTheirs[key] ?: continue
        // «ему не дойти» — это НЕИЗВЕСТНОСТЬ, не безопасность (v54): поле за бюджетом BFS даёт Int.MAX_VALUE / 4, и точка
        // получала запас 536870903 — армия ушла в угол (96,3) при его армии в 75 клетках к югу и была там стёрта (матч 114,
        // Coldkimchi, t=333); бегство вбок (v53) не включилось, потому что «лучшая точка» была положительной
        if (theirs >= Int.MAX_VALUE / 4) continue
        val ourTicks = strikers.maxOfOrNull { pathTicks(it, flow, it.key) } ?: continue
        if (ourTicks >= Int.MAX_VALUE / 4) continue
        val exit = exitMargin(ctx, c, ourTicks, approachRate)
        // он там раньше нас — это марш в него (v79); выход нулевой — это карман (v87)
        if ((theirs - ourTicks < 0 || exit <= 0)) continue
        val score = minOf(theirs - ourTicks, exit)
        if (getRange(c, ctx.ourCentroid) <= EVADE_ARRIVED && score < EVADE_SAFE) { evadeLeft = c; continue }
        if (cur != null && c.x == cur.x && c.y == cur.y) curScore = score
        if (best == null || score > bestScore) { bestScore = score; best = c; bestArrive = theirs - ourTicks; bestExit = exit }
    }
    if (cur != null && !arrived && curScore >= bestScore - EVADE_HYSTERESIS && curScore > 0) return cur
    // ни одной точки с запасом — бегство направлением (см. fleePoint); точка с нулевым или отрицательным запасом — это шаг
    // сквозь преследователя или в угол (матч 80: (3,3) −40, дом 0, (96,96) 0)
    if (best == null || bestScore <= 0) {
        val flee = fleePoint(ctx, armed)
        val edge = flee?.let { minOf(it.x, it.y, 99 - it.x, 99 - it.y) } ?: 99
        if (flee != null && edge <= FLEE_EDGE_MIN) {
            if (DEBUG_LOG && (cur != null || getTicks() % (LOG_EVERY * 5) == 0)) println("evade: t=$now flee=(${flee.x},${flee.y}) refused — ${edge} from the edge, the army stands at the post (see true)")
            evadeTarget = null
            return null
        }
        if (flee != null) {
            if (cur == null || cur.x != flee.x || cur.y != flee.y)
                println("evade: t=$now flee=(${flee.x},${flee.y}) best=${best?.let { "(${it.x},${it.y})" } ?: "-"} score=$bestScore from=(${ctx.ourCentroid.x},${ctx.ourCentroid.y}) approach=${(approachRate * 100).toInt()}")
            evadeTarget = flee
            return flee
        }
    }
    if (best == null) { evadeTarget = null; return null }
    if (cur == null || cur.x != best.x || cur.y != best.y)
        println("evade: t=$now to=(${best.x},${best.y}) score=$bestScore arrive=$bestArrive exit=$bestExit from=(${ctx.ourCentroid.x},${ctx.ourCentroid.y}) approach=${(approachRate * 100).toInt()}")
    evadeTarget = best
    return best
}

/** Пост: центр наших флагов (их и держим), без флагов — дом; и НЕ БЛИЖЕ EVADE_RANGE к краю карты (v46). Матчи 78 и 80
 *  (Coldkimchi, стоячая линия при паритете, из контакта отхода нет): единственный наш флаг — угловой H4 (90,8), пост на
 *  нём, армия стояла в углу; когда он пошёл на нас, ни одна точка уклонения не имела запаса (лог evade: (96,3) 13, дальше
 *  (3,3) −40 сквозь него, дом с выходом 0, угол (96,96) с выходом 0) — контакт на 319-м и 510-м, армия стёрта при его
 *  16000/16000. Флаг остаётся нашим, пока на него не встанет чужой (хранитель встаёт, когда враг подходит, см. KEEP_RANGE);
 *  стоять на нём армии незачем, а угол — ловушка для равного по скорости. */
internal fun postPoint(ctx: Ctx): Position {
    // ПОКА СВОИХ ФЛАГОВ НЕТ — ПОСТ В СЕРЕДИНЕ ПУТИ МЕЖДУ СТАРТАМИ (v283, `DistanceMap.midpoint`). Здесь стоял ближайший к нам
    // флаг «нашей половины» (путь от нашего старта не длиннее его пути), и один и тот же бот играл два дебюта по ничьей в
    // один шаг: D5 (49,49) в 39 шагах от (85,88) и в 40 от (12,9), поэтому из (85,88) пост — центр, а из (12,9) — свой R3
    // (13,49) у края. Против ●ω<♥♪#6 это весь разрыв: 13-62 из (85,88) против 2-71 из (12,9) по 148 играм (Fisher p =
    // 0,005), контакт на 66-м против 156-го — из (12,9) армия ждёт у края, он проходит 81 клетку против наших 17 и бьёт у R3;
    // и в руках v281–v282, где своего дебаффа в первом бою уже нет, из (12,9) 1-12 против 7-12. Середина — клетка, куда обе
    // армии приходят одновременно: наименьшая разница путей от стартов, при равной — наименьшая их сумма (середина самого
    // быстрого пути); считается из тех же полей, что и половина, и на карте без симметрии остаётся верной. Прежние формы:
    // «ждать у A3 в 25 от D5» (v129) отвергнута; «D5 с обеих сторон» (v230) сдвинула контакт в центр, но против
    // MetalicaX#10/#14 счёт не сдвинула (8-8 против 9-7) — там край не решал; против ●ω её не играли. Шагнуть на клетку
    // флага армия теперь может только через ворота захвата (v282), поэтому пост на клетке D5 флаг не берёт
    // ...И С ЗАПАСОМ НА СТРОЙ (v284): середина — точка, куда он может прийти в тот же тик, и армия встречала его из
    // колонны. Стенд match13:brawl+heals (v283): пост в центре достигнут на 50-м тике, контакт на 53-м, его мили дошли до
    // наших стрелков, к 96-му у него остались стрелки, у нас мили, армия стёрта к 150-му, матч отдан по очкам 13 864 : 14 141
    // (у R3, где армия 43 тика ждала строем, — уничтожение его армии на 408-м). Запас — не новое число, а MARCH_SAFE: марш
    // ядра строем идёт, только пока его вооружённые дальше MARCH_SAFE, ближе армию ведут тактические ветки; пост, куда мы
    // приходим на MARCH_SAFE тиков раньше него, — место, где марш кончается до того, как он войдёт в эту зону. Из обоих углов
    // точка сдвинута к своему старту одинаково
    val midway = DistanceMap.midpoint(MARCH_SAFE)
    // центральный флаг наш — пост на нём (v102, USE_POST_ON_CENTRE)
    val centre = ctx.flags.firstOrNull { it.ours && it.type == EFF_DAMAGE_TAKEN_MODIFIER }?.pos
    val c = centre ?: centroidOf(ctx.ourFlags.map { it.pos }) ?: midway ?: ctx.home
    return passableNear(c)
}

/** Бегство направлением (v46): когда ни одна точка выхода не даёт запаса (см. evadePoint), цель — клетка в EVADE_RANGE от
 *  нашего центра в том из восьми направлений, где после шага центр вооружённого врага дальше всего, среди направлений,
 *  чья клетка не ближе EVADE_RANGE к краю (у края направлений вдвое меньше, в углу — вчетверо: матчи 78, 80). Точки
 *  выхода (флаги, дом, углы) при равной скорости преследователя все «за ним» или без выхода, и армия шла в наименее
 *  плохую — в угол. */
internal fun fleePoint(ctx: Ctx, armed: List<Creep>): Position? {
    val ec = centroidOf(armed) ?: return null
    val oc = ctx.ourCentroid
    // отступ от края — не больше нынешнего: у края все направления вдоль края запрещались, оставались только внутрь, и
    // армия с (29,6) пошла на (52,32) наискосок МИМО его центра (30,30) — перехвачена на 69-м, стёрта к 120-му (матч 83,
    // けろびー боевой, которого v45 била). Направление обязано уводить: скалярное произведение с вектором «от него к нам»
    // положительно; из допустимых — то, где его центр после шага дальше всего
    val margin = minOf(EVADE_RANGE, oc.x, oc.y, 99 - oc.x, 99 - oc.y).coerceAtLeast(0)
    val ax = oc.x - ec.x; val ay = oc.y - ec.y
    // ВБОК, когда «прочь» некуда (v53): из домашнего угла каждое направление прочь от его центра уводит за карту — матч 110,
    // бросок боевого けろびー с 3-го тика, уклонение из угла в угол, контакт у (79,81)–(93,93), армия стёрта к 150-му; тот же
    // бой в открытом поле выигран пять раз. Направление допустимо, если конец шага не ближе EVADE_RANGE к его центру:
    // сначала уводящие (скалярное произведение > 0), затем боковые; бой, если придёт, придёт в поле.
    // НАХОДКА (матч 115, не исправлено): у верхнего края при враге точно к югу «прочь» — за картой, и знак крошечного
    // бокового смещения его центра решал запад/восток: (5,3) на 56-м, (3,3) на 61-м, (50,6) на 71-м, (57,6) на 76-м —
    // центр армии за 20 тиков (30,3)→(28,3)→(25,3)→(27,3)→(30,3), контакт на 77-м. Ранжирование по расстоянию с
    // гистерезисом (v55-опыт) гейт прошло, но 25 строк хуже / 29 лучше — то же дрожание было во всех девятнадцати
    // победах над тем же соперником, решил не отход, а бой (см. covered)
    fun pick(away: Boolean): Position? {
        var best: Position? = null
        var bestD = -1
        for ((dx, dy) in dirsNow()) {
            if (dx == 0 && dy == 0) continue
            val dot = dx * ax + dy * ay
            if (if (away) dot <= 0 else dot < 0) continue
            val x = oc.x + dx * EVADE_RANGE; val y = oc.y + dy * EVADE_RANGE
            if (x < margin || y < margin || x > 99 - margin || y > 99 - margin) continue
            val c = passableNear(InfluenceMap.cell(x, y))
            val d = getRange(c, ec)
            if (d > bestD) { bestD = d; best = c }
        }
        return best?.takeIf { getRange(it, ec) >= (if (away) getRange(oc, ec) + 1 else EVADE_RANGE) }
    }
    return pick(true) ?: pick(false)
}

internal fun updateKeepers(ctx: Ctx, army: List<Creep>) {
    val armedEnemies = ctx.threats
    // упреждение ухода — только против СОМКНУТОГО и только ОДИНОКОМУ (v357, см. KEEP_LEAD): обе проверки нужны, и
    // каждая отвергла свою отдельную редакцию живым замером
    fun keepLeadFor(c: Creep) = if (!Signals.groupSafe && Signals.enemyMassedSignal &&
        army.none { it.id != c.id && hasWeapon(it) && getRange(it, c) <= KEEP_ALONE }) KEEP_LEAD else 0
    // ХРАНИТЕЛЬ В РЕЖИМЕ ПАР (v303, см. GROUP_SAFE_DMG): его держит не близость врага, а сила ядра без него. Флаг, с
    // которого армия ушла, фермер забирает через 11–15 тиков, а хранителя ставило только «его крип в KEEP_RANGE» —
    // против けろびー армия брала флаг и уходила, и мы держали 2,15 флага против его 4,6
    // ...И МЕРА ЯДРА — ТЕ, КТО К НЕМУ ПОДОШЁЛ (v314): «крупнейшая его группа на карте» (v301) держала при ядре почти всех,
    // и хранитель на флаге бывал один: 24–33 назначения за матч, 0,8 стоящего хранителя в среднем, 1,7 нашего флага против
    // его 5,0. Опора та же, что у отзыва с v296, — его крипы, которые успевают дойти до нашей массы; в ядре при этом
    // всегда остаются двое с оружием
    fun coreHolds(core: List<Creep>): Boolean = core.count { hasWeapon(it) } >= 2
    // ...И СНИМАЕТ ХРАНИТЕЛЯ МЕСТНАЯ СИЛА, А НЕ СЧЁТ (v305): порог «больше двух его вооружённых в десяти клетках» снимал
    // хранителя каждые несколько тиков — его крипы бродят мимо, — и флаг оставался пустым: «keeps at t=46 … released at
    // t=50», 174 таких события за матч при 1,96 наших флага против его 4,79. Здесь тот же вопрос, что у бегуна с v297:
    // бьёт ли его стая у флага того, кто на нём стоит, вместе с нашими рядом
    // ⚠️ ДВА ИСПУГА ОТВЕРГНУТЫ ЗАМЕРОМ (v308). Сравнение сил (v305) сгоняло нашего мили с флага одним его стрелком —
    // 34–43 снятия за матч; давление «двое его боевых в трёх клетках пять тиков» (v307, порог из контрфакта разбора)
    // снимало хранителя от гуляющей мимо ТРОЙКИ ЕГО ЛЕКАРЕЙ — те в `combatEnemies` входят, а бить некого: 1,72 наших
    // флага против 2,39 у v306, счёт 7–10 тыс. против 11–17 тыс. В режиме пар хранителя снимает только нужда ядра и
    // собственные хиты ниже половины: пока его не бьют, флаг стоит очков каждый тик
    var core = army.filter { it.id !in Squads.keeperIds }
    val iter = Squads.keeperIds.entries.iterator()
    while (iter.hasNext()) {
        val e = iter.next()
        val c = army.firstOrNull { it.id == e.key }
        val f = ctx.flags.firstOrNull { it.id == e.value }
        // враг с боем в KEEP_RANGE от флага — хранителя нет: стрелок стоял на R3 весь бой, пока в десяти клетках
        // висели скауты врага, и не стрелял (матч 18)
        // ⚠️ ПРАВИЛО УХОДА ПРИНИМАЕТ РЕШЕНИЕ, А НЕ ПОДПИСЫВАЕТ ЕГО (v365, дефект v353). Проверка «его возможный урон по
        // клетке больше нашего фактического лечения» стояла НИЖЕ, в `when`, вычисляющем ярлык `why`, куда попадают уже
        // ПОСЛЕ того, как `!stay` решено, — то есть правило только переименовывало снятие по половине хитов в «hurt» и
        // не снимало никого ни разу. Разбор 4 матчей v361: все 13 снятий с ярлыком «hurt» произошли при 0,37–0,49
        // хитов, медианное запаздывание от первого удара — 7 тиков, его мили при снятии стоял в ОДНОЙ клетке, и 8 из
        // 13 кончились смертью в течение 40 тиков (это 8 из наших 9 смертей). Лечения за все девять агоний получено
        // 0 HP: лекаря ближе шести клеток не было ни разу, ближайший свой ствол — медиана 16 клеток, 15–23 тика хода
        // при агонии 3–12 тиков. Предупреждение при этом огромно: его вооружённый стоит в четырёх клетках медиану
        // 19 тиков до первого удара
        // ...И ТОЛЬКО В РЕЖИМЕ ПАР (v366). Правило, ставшее решающим, против けろびー#19 дало 10-6 при потерях 0,50 тела
        // за матч вместо 3,75, но против блоба MetalicaX#15 уронило блок до 1-7: перевес по флагам −1,09 против +1,82
        // у v357, потери 8,88 тела против 4,25. Разница по существу та же, что у упреждения (см. KEEP_ALONE): у
        // фермера хранитель стоит один, помощи нет ни от лекаря (медиана 16 клеток), ни от своих стволов (16 клеток,
        // 15–23 тика хода), и уход — единственное, что у него есть; в бою с кулаком он стоит в строю, где уход с
        // клетки рушит строй и отдаёт флаг. Вне режима пар порог остаётся прежним — половина хитов
        val keeperLeaves = c != null && (c.hits * 2 < c.hitsMax || (Signals.groupSafe &&
            InfluenceMap.damageSoonAt(c.x, c.y, ctx.combatEnemies, keepLeadFor(c)) >
            InfluenceMap.healAt(c.x, c.y, ctx.armyWithHeal)))
        val onFlag = c != null && f != null && f.ours && c.x == f.pos.x && c.y == f.pos.y && !keeperLeaves
        val stay = onFlag && (if (Signals.groupSafe) coreHolds(core)
            else enemyCreeps(ctx).any { it.id != c!!.id && getRange(f!!.pos, it) <= KEEP_RELEASE } &&
                armedEnemies.count { getRange(f.pos, it) <= KEEP_RANGE } <= KEEP_PICKET)
        if (!stay) {
            keepOff++
            if (!onFlag) keepOffLeft.n++ else if (Signals.groupSafe && !coreHolds(core)) keepOffCore.n++ else keepOffPack.n++
            // ПОЧЕМУ СНЯТ (v309, прибор): «сошёл с клетки» — это три разных случая, и порог чинить можно, только зная, какой
            val why = when {
                c == null -> "gone"
                f == null || !f.ours -> "flag"
                c.x != f.pos.x || c.y != f.pos.y -> "moved"
                // ...И ХРАНИТЕЛЬ УХОДИТ ПО ТОМУ ЖЕ ПРАВИЛУ, ЧТО ГАРНИЗОННЫЙ БЕГУН (v353, правило оператора
                // 16.09.2026 «уходить только тогда, когда его потенциальный урон превышает наш фактический хил»).
                // Разбор 4 матчей v348 по реплеям: за четыре матча мы потеряли 12 крипов, он — 1, и 9 из 12 умерших
                // были ХРАНИТЕЛЯМИ — крипами армии, приколотыми к клетке флага через TrafficManager.pin, для которых
                // правило v340 не действовало вовсе: оно написано в ветке бегунов (Missions.kt), а хранителя снимало
                // только «ниже половины хитов». Цена разнобоя измерена: снятие по хитам фиксируется за 1–3 тика до
                // смерти во всех семи наблюдаемых случаях, агония длится в среднем 5,8 тика, а полную скорость крип
                // теряет через 3,0 тика после первого удара — то есть прежний порог назначен ровно на последний тик,
                // когда уйти ещё можно. Уход был физически возможен в 12 случаях из 12 (в среднем 4,0 свободной
                // клетки увеличивали дистанцию), и он работает: по тик-парам шаг выводит из-под его мили в 56 %
                // случаев против 27 % у стоящего, из-под стрелка — 32 % против 10 %. Помощь не успевала: ближайший
                // наш лекарь в медиане 24 клетках (22 тика хода), свой ствол в трёх клетках — 0 случаев из 12
                // ...И СМОТРИТ НА ДВА ТИКА ВПЕРЁД (v354, см. damageSoonAt): мера «кто достаёт сейчас» дала 14
                // снятий за матч при 74 эпизодах «под огнём у флага» — угроза видна ровно тогда, когда уходить уже
                // поздно, потому что полная скорость держится всего 3,0 тика после первого удара
                keeperLeaves -> "hurt"
                else -> "core"
            }
            // счётчик причины — оператором после выбора, а не внутри выражения (v448, линт чистых инициализаторов)
            when (why) { "gone" -> keepOffGone.n++; "flag" -> keepOffFlag.n++; "moved" -> keepOffMoved.n++; "hurt" -> keepOffHurt.n++ }
            if (DEBUG_LOG) println("keeper t=${getTicks()}: ${e.key} released from ${e.value} ($why)")
            iter.remove()
            if (c != null) core = core + c
        } else keepTicks.n++
    }
    for (f in ctx.flags) {
        if (!f.ours) continue
        val occ = f.occupant ?: continue
        if (occ.my != true || army.none { it.id == occ.id } || occ.id in Squads.keeperIds) continue
        // ...и хранителем не становится лекарь (v215, см. USE_HEALER_NEVER_PINNED): ветка `keeper` первая в
        // цепочке целей, и пришпиленный к флагу лекарь выключается из боя целиком
        // ...НО В РЕЖИМЕ ПАР БОЯ НЕТ, И ВЫКЛЮЧАТЬСЯ НЕ ИЗ ЧЕГО (v535, см. USE_HEALER_HOLDS_FLAG)
        if (!(USE_HEALER_HOLDS_FLAG && Signals.groupSafe) && army.any { it.id == occ.id && healerOnly(it) }) continue
        if (Squads.runnerFlag.values.contains(f.id)) continue
        if (!Signals.groupSafe && enemyCreeps(ctx).none { getRange(f.pos, it) <= KEEP_RANGE }) continue
        val cand = army.firstOrNull { it.id == occ.id } ?: continue
        // ...и НЕ НАЗНАЧАЕТСЯ ТОТ, КОГО ПРАВИЛО ТУТ ЖЕ СНИМЕТ (v353). Без зеркального условия снятие отменялось тем
        // же тиком: 21 из 27 событий «released (hurt)» сопровождались строкой `keeps` в ТОМ ЖЕ тике (78 %), и правка
        // выше без этой была бы отменена каждым тиком заново
        if (cand.hits * 2 < cand.hitsMax ||
            InfluenceMap.damageSoonAt(cand.x, cand.y, ctx.combatEnemies, keepLeadFor(cand)) >
            InfluenceMap.healAt(cand.x, cand.y, ctx.armyWithHeal)) { keepOffHurt.n++; continue }
        if (Signals.groupSafe) {
            if (!coreHolds(core.without(occ))) continue
        } else if (armedEnemies.count { getRange(f.pos, it) <= KEEP_RANGE } > KEEP_PICKET) continue
        Squads.keeperIds[occ.id] = f.id
        keepOn.n++
        if (Signals.groupSafe) core = core.without(occ)
        if (DEBUG_LOG) println("keeper t=${getTicks()}: ${occ.id} keeps ${f.id} at (${f.pos.x},${f.pos.y})")
    }
    // прибор v535: крипо-тиков хранителя-лекаря из всех крипо-тиков хранителя
    for (id in Squads.keeperIds.keys) {
        hkeepAll.n++
        if (army.any { it.id == id && healerOnly(it) }) hkeepHeal.n++
    }
}

/** РЕЖИМ ГОНКИ (v160, оператор: командир оркестрирует всю игру, «в случае затишья — раздавать задания по захватам
 *  флагов небольшими группами»). Здесь враг строем не дерётся — сидит на флагах, разбегается или держит дистанцию,
 *  — и решает не кулак, а счёт. Командир раздаёт ЗАДАНИЯ: каждому незанятому флагу — ближайшая горстка, по одному
 *  крипу на свободный флаг и по двое на тот, у которого стоят его вооружённые; ядро (половина армии) остаётся
 *  целым, потому что аннигиляция проигрывает матч при любом счёте. Назначение идёт той же картой commandOf, что и
 *  в бою, поэтому исполняют его те же правила движения. */
/** ОГОНЬ ПО ПРИКАЗУ (v161, оператор: перевести выбор целей на командира). Прежде «кто куда встал» решал командир, а
 *  «кто в кого бьёт» — отдельный проход со своим липким фокусом, и это два решения об одном размене. Здесь цель
 *  назначается ПОИМЁННО, одной политикой: сперва ищем того, кого армия ДОБИВАЕТ этим тиком (залп всех, кто его
 *  достаёт, перекрывает хиты вместе с лечением, которое до него дотягивается) — на него идут все достающие; если
 *  добить некого, огонь сходится на прежней липкой цели, а кто её не достаёт, бьёт ближайшего вооружённого. */
/** ЯДРО ИДЁТ СТРОЕМ (v163, оператор: перевести на командира и движение вне боя). Ведёт ядро как одно тело: шаг в
 *  сторону цели, клетки раздаются по одной на крипа, отставший подтягивается к якорю, и ни одна клетка не выходит
 *  за FIST_RADIUS. Это НЕ отвергнутый USE_COMMANDER_APPROACH: тот вёл поход РАССТАНОВКОЙ ПРОТИВ СТРОЯ, которой в
 *  походе нечего расставлять, и потому упирался в фолбэк «ближе к врагу». */
/** ЦЕЛЬ ПОХОДА ВЫБИРАЕТ КОМАНДИР (v164, оператор). Прежде он вёл ядро строем к цели, которую выбрал НЕ ОН:
 *  objectiveFlagId считается раньше, отдельной логикой ценности флага. Здесь цель — его решение, и по его же
 *  правилам: флаг, который БРАТЬ МОЖНО (флаг дебаффает владельца, поэтому мимо гейта захвата ходить незачем),
 *  ближайший к армии, со штрафом за его вооружённых рядом — заслонённый берётся боем, а не строевым шагом. */
internal fun commandGoal(ctx: Ctx, view: ExchangeView, approachRate: Double, farmerQuietNow: Boolean, army: List<Creep>, armedEnemies: List<Creep>): Position? {
    if (army.isEmpty()) return null
    // ...и оценку флага командир не изобретает заново, а ВЫЗЫВАЕТ: chooseFlagObjective считает ценность, путь,
    // пачку у флага и возможность отхода — всё, чего не знает «ближайший разрешённый». Своя формула была написана
    // и отвергнута замером: по близости 131 из 135 (camp дважды, screen, brawl+heals), по ценности на шаг 132,
    // с квадратичным штрафом расстояния снова 131. Решение остаётся командирским — он спрашивает и решает
    return chooseFlagObjective(ctx, view, approachRate, farmerQuietNow, army, PUSH_RATIO)?.flag?.pos
}

/**
 * Назначает погоню за остовами: крипами врага, у которых выбито всё оружие, но тело его помнит, — пока у него
 * жив лекарь, такой крип это полная его мощь на таймере. Условие на цель одно и оно из той же модели, что всё
 * остальное: ОПАСНОСТЬ В ЕГО КЛЕТКЕ должна быть настолько мала, чтобы преследователь прожил там CHASE_TTL
 * тиков. Этим одним условием сказано и «он оторвался от своего блока», и «нашему там не смертельно», и
 * отзыв погони: как только остов вернулся под прикрытие, опасность растёт и назначение само перестаёт
 * выдаваться. Отдельного правила отзыва не нужно.
 */
internal fun assignChase(army: List<Creep>, enemyCreeps: List<Creep>, armedEnemies: List<Creep>) {
    Squads.chaseOf.clear()
    Squads.chaseTarget.clear()
    val fighters = army.filter { canMove(it) && !it.spawning && hasWeapon(it) }
    if (fighters.size < CHASE_MIN_ARMY) return
    if (enemyCreeps.none { InfluenceMap.profileOf(it).heal > 0.0 }) return   // лечить некому — остов и так труп
    val hulks = enemyCreeps.filter { e ->
        val pot = InfluenceMap.potentialOf(e)
        val live = InfluenceMap.profileOf(e)
        pot.melee + pot.ranged > 0.0 && live.melee + live.ranged <= 0.0
    }
    if (hulks.isEmpty()) return
    val free = fighters.toMutableList()
    // ...и НЕ БОЛЬШЕ ПОЛОВИНЫ, как у гонки за флагами: ядро остаётся сильнее его армии, иначе погоня покупает
    // остова ценой боя. Та же проверка паритета, тот же довод — аннигиляция это поражение при любом счёте
    var budget = minOf(CHASE_MAX, (free.size - 1) / 2)
    for (h in hulks.sortedBy { e -> free.minOfOrNull { getRange(it, e) } ?: 99 }) {
        if (budget <= 0) break
        val key = h.key
        val danger = InfluenceMap.dangerAt(key)
        // преследователь выбирается ближайший, стрелок вперёд мили: он добивает с трёх и не лезет под ответ
        // ТРИ СУЖЕНИЯ ОТРЯДА ЗАМЕРЕНЫ И ОТВЕРГНУТЫ (v213). Базовая линия стенда — 137 восстановлений при 65
        // решающих уничтожениях армии врага. Отряд из ДВУХ: погонь 1689, добито 411, восстановлений 104
        // (−24 %), уничтожений 55. Отряд из ОДНОГО: 1507/317, восстановлений 131 (−4 %, то есть выгоды нет),
        // уничтожений 61. «Только тот, чей огонь и так пропадает» (у кого в своей дальности нет живого
        // вооружённого): 1475/362, восстановлений 143 — ХУЖЕ базовой линии, потому что незанятый крип по
        // построению стоит далеко от боя и доходит до остова позже, чем тот до лекаря.
        // Работает только отряд из двух, и его цена — не поражения (наша армия не уничтожена ни разу, отрыв по
        // очкам ровный, −0,65 %), а десять сценариев, где вместо аннигиляции врага матч кончается по очкам с
        // нами впереди. Это размен формы победы на снятые восстановления, и он принят.
        val chaser = free.filter { c -> c.hits > danger * CHASE_TTL }
            .minWithOrNull(compareBy({ if (hasRanged(it)) 0 else 1 }, { getRange(it, h) })) ?: continue
        val without = free.filter { it.id != chaser.id }
        if (without.none { hasWeapon(it) }) break
        if (ourPowerOf(without, armedEnemies) < enemyPowerOf(armedEnemies, without) * PARITY_FLOOR) break
        Squads.chaseOf[chaser.id] = h.id
        Squads.chaseTarget[chaser.id] = h
        free.remove(chaser)
        budget--
    }
    if (Squads.chaseOf.isNotEmpty()) chaseTicks++
    // прибор: остов, за которым была погоня и который перестал существовать, — это её результат
    for (id in Memory.chasedIds.toList()) if (enemyCreeps.none { it.id == id }) { chaseKills.n++; Memory.chasedIds.remove(id) }
    Memory.chasedIds.addAll(Squads.chaseOf.values)
}

/**
 * ЗАГОН (v331, предложение оператора 16.09.2026: «получить преимущество по крипам — делить армию на несколько
 * независимых групп, чтобы несколько групп догоняли конкретную цель, загоняя её с разных сторон, пока кто-то не выйдет
 * на огневой рубеж»).
 *
 * Основание в числах: против けろびー#19 мы теряем 5,3 крипа за матч, он — 0,5, и каждый его живой крип забирает флаг,
 * едва мы с него уйдём. Догнать его кулаком нельзя: скорости равны, и за CHASE_WINDOW он отходит ровно на столько же.
 * Загон решает это геометрией, а не скоростью: одна пара идёт ПРЯМО на цель и гонит её, вторая — в точку на ЕГО векторе
 * отхода (от первой пары), то есть туда, куда он побежит; сойдясь, они держат его в дальности выстрела.
 *
 * Работает только в режиме пар (см. GROUP_SAFE_DMG): там ядро всё равно не дерётся строем, а цель — одиночка, от
 * которой он сам не помогает (от наших групп из двух и больше он отходит).
 */
internal fun commandHunt(ctx: Ctx, hunters: List<Creep>, armedEnemies: List<Creep>,
                                     out: MutableMap<String, Position>): Boolean {
    // ...и НЕ ПРОТИВ СОБРАННОЙ АРМИИ (v332, гейт: match29:camp 17 855:23 669): у лагеря пикет отходит к своим, загон
    // тянется за ним и бросает флаги, а рядом с его массой цель перестаёт быть одиночкой на следующем же шаге
    if (!Signals.groupSafe || Signals.enemyMassedSignal || hunters.size < 3) return false
    val centre = centroidOf(hunters) ?: return false
    // цель — его одиночка (не больше одного своего в ENGAGE_RANGE) поближе к нам и в пределах HUNT_REACH
    fun lone(e: Creep) = ctx.combatEnemies.count { it.id != e.id && getRange(e, it) <= ENGAGE_RANGE } <= 1
    val sticky = Memory.huntQuarry?.let { id -> ctx.combatEnemies.firstOrNull { it.id == id } }
        ?.takeIf { lone(it) && getRange(centre, it) <= HUNT_REACH }
    val quarry = sticky ?: ctx.combatEnemies
        .filter { lone(it) && getRange(centre, it) <= HUNT_REACH }
        .minByOrNull { getRange(centre, it) } ?: run { Memory.huntQuarry = null; return false }
    // ...И ТОЛЬКО ТОГО, КТО УЖЕ ЗАЖАТ (v333). Первая редакция (v331) слала половину ядра в точку на его векторе отхода в
    // шести клетках впереди: при равных скоростях она недостижима — он уходит ровно на столько же, и за 457–644 тика
    // загона в матче убитых НОЛЬ при его 11–12 живых. Догнать равного по скорости в поле нельзя; поймать можно только
    // того, кто уже между двумя нашими группами, — тогда его отход перпендикулярен обеим, и стрелок держит три клетки
    val near = hunters.filter { getRange(it, quarry) <= HUNT_REACH / 2 }
    if (near.size < 3) { Memory.huntQuarry = null; return false }
    // две группы по сторонам от цели: делим по знаку проекции на ось «цель — ближайший наш»
    val lead = near.minByOrNull { getRange(it, quarry) } ?: return false
    val ax = lead.x - quarry.x; val ay = lead.y - quarry.y
    val sideA = near.filter { (it.x - quarry.x) * ax + (it.y - quarry.y) * ay >= 0 }
    val sideB = near.filter { (it.x - quarry.x) * ax + (it.y - quarry.y) * ay < 0 }
    if (sideA.isEmpty() || sideB.isEmpty()) { Memory.huntQuarry = null; return false }
    Memory.huntQuarry = quarry.id
    huntTicks.n++
    val matrix = crowdMatrixOf(ctx, -1)
    for (c in near) {
        val step = pathStep(c, InfluenceMap.cell(quarry.x, quarry.y), if (hasRanged(c)) RANGED_RANGE - 1 else 1, matrix)
        if (step != null) out[c.id] = step
    }
    huntCreepTicks.n += near.size
    return true
}

/** ГОНКА ЗА ФЛАГАМИ КОМАНДИРА: раздаёт ЗАДАНИЯ (`Squads.detach` — отряжённые командиром, флаг бегуна, гарнизон, курьер), а не
 *  приказы на клетку: шаг отряжённого делает ветка бегуна. Параметр `out` снят в v470 (дефект 9 постановки): функция его
 *  только чистила и ни разу не писала, а вызывающие копировали пустой словарь и клали обратно (см. Commander.kt). Чистка
 *  приказов перед гонкой стоит теперь у вызова, где она и действует. */
internal fun commandRace(ctx: Ctx, meas: ArmyMeasures, army: List<Creep>, armedEnemies: List<Creep>, flags: List<FlagInfo>) {
    val roster = RaceRoster(ctx, meas, armedEnemies)
    // прибор `raceexit=` (v465, дефект 4): ранние выходы гонки, когда прежний состав командира был НЕ пуст — состав очищен
    // (`RaceRoster`), и до конца тика его не восстановит никто: на следующем тике гарнизон и курьер возвращаются в армию
    val hadOut = roster.alreadyOut.isNotEmpty()
    if (roster.fightBlocks && roster.holding.isEmpty()) { if (hadOut) raceExitFight.n++; return }
    // ...и состав считается ЦЕЛИКОМ, вместе с уже отпущенными командиром: иначе он каждый тик берёт половину
    // ОСТАВШИХСЯ и отпускает ещё, а ушедшие ему не видны — армия распадалась экспоненциально, до двух крипов к
    // концу матча (match29:kite, cmd=0/1090, army=2, 0 очков). Задание раздаётся заново на всех, а не поверх
    val mine = army + (roster.alreadyOut)
    // ИНВАРИАНТЫ ЧЛЕНСТВА (v464, дефект 3): в состав гонки не входят отряжённый стратегом В ЭТОМ тике (StrategyDetach стоит выше и
    // пишет detachedIds, а commandArmy заморожен на начало тика — тот же крип получал ещё и командирский флаг, и со следующего тика
    // ветка бегуна шла по нему, решение стратега молча перекрывалось), хранитель (вне режима пар он в commandArmy и получал задание
    // на другой флаг — сходил с клетки) и преследователь (assignChase стоит выше: в этом тике он ходит за остовом, со следующего —
    // бегун). Предохранитель на самой операции — Squads.detach со счётчиком sqref=
    fun raceFit(c: Creep): Boolean {   // прибор `racex=` считает, кого из подвижных вооружённых состав не взял и почему
        if (c.id in Squads.detachedIds) { raceExclDet.n++; return false }
        if (c.id in Squads.keeperIds) { raceExclKeep.n++; return false }
        if (c.id in Squads.chaseOf) { raceExclChase.n++; return false }
        return true
    }
    // ...И В РЕЖИМЕ ПАР В СОСТАВ ВХОДЯТ ЛЕКАРИ (v535, см. USE_HEALER_HOLDS_FLAG): `hasWeapon` держал трёх лекарей в ядре
    // всегда — правило v214 («лекари всегда остаются в основной армии») написано для боя, а режим пар и означает, что
    // боя нет. Тело на флаге держит его в шесть раз надёжнее пустого (замер по 59 матчам с けろびー#19: наш флаг с нашим
    // телом теряется в 8,3 % срезов, пустой — в 49,8 %), а лекарь — лучшее тело для этого: h6m6 это 1200 хитов и 72
    // лечения себе в тик. Уходит он по тому же правилу, что и всякий хранитель в режиме пар (см. keeperLeaves)
    val healersJoin = USE_HEALER_HOLDS_FLAG && roster.safe
    val free = mine.filter { canMove(it) && !it.spawning && (hasWeapon(it) || (healersJoin && healerOnly(it))) && raceFit(it) }.toMutableList()
    if (free.isEmpty()) { if (hadOut) raceExitFree.n++; return }
    // очистка набора командира — ЗДЕСЬ, за ранними выходами (v465, дефект 4; до того — в RaceRoster.init): на выходах выше прежний
    // состав переживает тик; ниже набор строится заново — держатели (RaceBudget), отряды в пути (RaceRoutes), гарнизон, горстки
    Squads.recallAll(Squads.Source.COMMANDER)
    val purse = RaceBudget(ctx, meas, roster, free)
    if (roster.fightBlocks) { if (hadOut) raceExitBlocks.n++; return }
    if (purse.budget <= 0) { if (hadOut) raceExitBudget.n++; return }
    val routes = RaceRoutes(ctx, meas, flags, roster, free, purse)
    RaceGarrison(ctx, flags, roster, free, purse)
    RaceParties(ctx, meas, armedEnemies, roster, free, purse, routes)
}

/** ПОДСТАДИЯ 1 ГОНКИ: состав — уже отпущенные (снимаются ДО очистки `Squads.cmdDetach`, а она с v465 стоит в `commandRace` ЗА ранними выходами), держатели, режим пар (`safe`), мера ядра (`coreHolds`), запрет боем. */
internal class RaceRoster(private val ctx: Ctx, private val meas: ArmyMeasures, private val armedEnemies: List<Creep>) {
    // ...и состав берётся ДО очистки (v215, см. USE_RACE_COUNTS_RELEASED): очистка стояла строкой выше чтения.
    // СОСТАВ ПЕРЕЖИВАЕТ РАННИЙ ВЫХОД (v465, дефект 4): очистка стояла здесь, в init, и ранние выходы `commandRace` («бой блокирует и
    // держателей нет», «свободных нет») оставляли набор ПУСТЫМ до следующего тика — гарнизон и курьер возвращались в армию, получали
    // её приказы и сходили с флагов на тик. Теперь очистка стоит в `commandRace` за этими выходами (см. там)
    val alreadyOut = ctx.runners.filter { it.id in Squads.cmdDetach }
    // ДЕРЖАТЕЛИ ОСТАЮТСЯ (v297, см. HOLD_WATCH): отпущенный, стоящий на взятом флаге при его крипе рядом, сохраняет
    // задание, пока хватает бюджета и ядро без него держит паритет. Задания раздавались только на ЧУЖИЕ флаги, и
    // взявший флаг на следующем тике уходил за другим или в армию — против けろびー#19 130 из 194 сходов вооружённых
    val holding = alreadyOut.filter { canMove(it) && hasWeapon(it) && (heldFlag(ctx, it) ?: guardFlag(ctx, it)) != null }
        .sortedByDescending { (heldFlag(ctx, it) ?: guardFlag(ctx, it))?.score ?: 0 }
    // ОТРЯД ИЗ ДВУХ ЕМУ НЕ ЦЕЛЬ (v298, см. GROUP_SAFE_DMG): пока он не бьёт наших, стоящих группой, выпуск меряется не всей
    // его армией, а тем, чтобы в ядре оставались двое с оружием, и флаг берёт пара — если его стая у флага её не бьёт.
    // Прежде ядро без отпущенных сравнивалось со всей его армией, разбросанной группами по 1–4 по пяти флагам, и при
    // целых армиях 14 на 14 выпуск не случался: против けろびー#19 226 назначений за 1040 тиков гонки при бюджете 5,6
    // ...И НЕ ПРОТИВ ТОГО, КТО УЖЕ ДРАЛСЯ С НАМИ СОМКНУТЫМ (v438, см. USE_PAIRS_NOT_VS_FIGHTER): тот же различитель, что у ворот
    // захвата с v434, — признак «не бьёт» говорит о прошлом за GROUP_WINDOW, а цену раздробленной армии платит бой, который
    // случится; гейт match33:camp: после первого боя его блок отошёл и раздробился, режим пар отпустил семерых из двенадцати
    // ...И ЗДЕСЬ ТА ЖЕ ЗАЩЁЛКА СНИМАЕТСЯ ТЕМ ЖЕ ЗАМЕРОМ (v534, см. USE_UNWIPEABLE_OPENS): величина одна — «решит ли он
    // матч боем», — и правил на неё было два. Дебаффа режим пар не покупает, поэтому множители единичные
    val safe = Signals.groupSafe && !(USE_PAIRS_NOT_VS_FIGHTER && fightMassedSeen && !ctx.passiveEnemy &&
        !cannotWipeUs(1.0, 1.0))
    // ...и мера ядра в режиме пар — его КРУПНЕЙШАЯ ГРУППА, а не «двое с оружием» (v301): доктрина паритета остаётся, меняется
    // только опора — та же локализация, что у ворот захвата с v214 и у отзыва с v296. «Двое с оружием» (первая редакция)
    // отпускали столько, что ядро переставало брать флаг, на котором сидит его крип: стендовый фермер scatter держал
    // оба H4 до конца (match33/34:scatter 21 199:24 238 и 20 803:24 322 — FAIL гейта), тогда как целая армия их отбивала
    // ...и в режиме пар ядру довольно ДВОИХ С ОРУЖИЕМ (v318): мера «его крипы рядом с ядром» (v314) держала в ядре
    // восемь-одиннадцать крипов из двенадцати — он бродит рядом весь матч, — и на флагах стояло полтора наших тела.
    // Режим и открывается только против того, кто группы не бьёт (см. GROUP_SAFE_DMG); начнёт бить — окно в сто тиков
    // закрывает режим, и все возвращаются в кулак
    fun coreHolds(without: List<Creep>) = if (safe) without.count { hasWeapon(it) } >= 2
        else without.any { hasWeapon(it) } && ourPowerOf(without, armedEnemies) >= enemyPowerOf(armedEnemies, without) * PARITY_FLOOR
    // В БОЮ НЕ ОТПУСКАЕМ НИКОГО (v215, см. USE_NO_SPLIT_IN_FIGHT). Проверки «мы в контакте» здесь не было вовсе,
    // а RACE — ветка `else` в выборе режима, то есть значение по умолчанию: достаточно, чтобы по нам на тик
    // перестали стрелять, и командир раздавал задания на захват посреди рубки. Держателей, которых бой вне контакта
    // ядра оставил (см. armyMeasures), он не отпускает, а оставляет
    // ...И В РЕЖИМЕ ПАР БОЙ ВНЕ КОНТАКТА ЯДРА ВЫПУСКА НЕ ОСТАНАВЛИВАЕТ (v325). Правило один раз отвергалось (v301): на
    // стенде оно отправляло пару к флагу посреди перестрелки и уносило из ядра двоих, а стендовые фермеры сидят на своих
    // флагах, и такой флаг берёт только сила ядра — match33:scatter падал (21 199:24 238). С v302 сидящий на флагах в
    // режим пар не попадает вовсе, поэтому возражение снято, а цена запрета видна числом: `fightOnNow` держится двадцать
    // тиков после ЛЮБОГО выстрела, けろびー стреляет по одиночкам весь матч, и командир выходил из раздачи, не успев
    // завести ни одного держателя, — 20 назначений за 260 тиков при бюджете 3,2 бойца в тик и man=2 за матч
    val fightBlocks = if (safe) meas.fight.contact else meas.fight.fightOnNow
}

/** ПОДСТАДИЯ 2: бюджет выпуска — симметричное ядро (в режиме пар — двое с оружием) и держатели, сохраняющие задание. `budget` тратят подстадии ниже. */
internal class RaceBudget(private val ctx: Ctx, private val meas: ArmyMeasures, private val roster: RaceRoster, private val free: MutableList<Creep>) {
    // СИММЕТРИЧНАЯ АРМИЯ (v214, решение оператора): «держать в основной армии столько же крипов, сколько у
    // врага, симметрично по типам боевых; лекари всегда остаются в основной армии; остальных отпустить».
    // Считается по ВСЕМ его живым боевым крипам, как он и просил. Лекари сюда не попадают вовсе: `free`
    // отбирается по hasWeapon, а лекарь без оружия в него не входит.
    // ⚠️ Арифметика, о которой надо помнить: при целых армиях 14 на 14 у нас 4 мили и 5 стрелков, у него
    // столько же — симметрия оставляет в ядре все девять и не отпускает НИКОГО. Это ровно то, чего просит
    // оператор, и это безопаснее прежней половины (match29:kite: армия таяла до двух крипов при нуле очков).
    // Флаговый забег при этом ложится на двух наших скаутов и на флаг-цель армии, открытую локализацией вето.
    private val core = run {
        // ...и «сколько у врага» значит «сколько у врага ЗДЕСЬ» (v216, см. USE_SYMMETRY_BY_NEAR)
        val near = ctx.combatEnemies.filter { e ->
            e.id in fightPackIds || getRange(e, ctx.ourCentroid) <= MARCH_SAFE
        }
        val hisMelee = near.count { hasMelee(it) }
        val hisRanged = near.count { hasRanged(it) }
        minOf(free.count { meleeOnlyLive(it) }, hisMelee) + minOf(free.count { hasRanged(it) }, hisRanged)
    }
    init { if (!meas.fight.fightOnNow) { symCore.n += core; symFree.n += free.size } }
    // ...и В РЕЖИМЕ ПАР БЮДЖЕТ НЕ СИММЕТРИЧЕН (v324): симметрия (v214, решение оператора) держит в ядре столько же, сколько
    // его боевых рядом, и против けろびー это 4–6 крипов независимо от того, что он с ядром не дерётся, — на флагах стоит
    // полтора наших тела из четырнадцати при его пяти флагах. В режиме пар в ядре остаются двое с оружием, остальные идут
    // на флаги; ярлык режима и означает «он не бьёт наших в группе», а начнёт — окно в сто тиков его закроет
    var budget = if (roster.safe) free.size - 2 else free.size - core
    init { if (!meas.fight.fightOnNow) { budgetSum.n += maxOf(0, budget); budgetTicks.n++ } }
    init {
        for (h in roster.holding) {
            if (budget <= 0) break
            val without = free.filter { it.id != h.id }
            if (!roster.coreHolds(without)) break
            val f = heldFlag(ctx, h) ?: guardFlag(ctx, h) ?: continue
            Squads.detach(h.id, f.id); free.remove(h); budget--; holdKeptRace.n++
        }
    }
}

/** ПОДСТАДИЯ 3: флаги, которые брать можно (`wanted`), и отряды в пути, сохраняющие задание (режим пар). */
internal class RaceRoutes(private val ctx: Ctx, private val meas: ArmyMeasures, private val flags: List<FlagInfo>, private val roster: RaceRoster, private val free: MutableList<Creep>, private val purse: RaceBudget) {
    // флаги — от ближайшего к армии; занятые нами пропускаем
    // ...и только те, которые БРАТЬ МОЖНО: флаг вешает дебафф на ВЛАДЕЛЬЦА (−20 % удару, −25 % лечению, +10 %
    // получаемому урону за штуку), поэтому доктрина паритета держит захват в узде через captureAllowed, и гонка
    // мимо неё — не гонка, а разоружение. Первая редакция это правило игнорировала, и гейт поймал: camp
    // 7 911:17 364, scatter 15 273:24 303 против 22 810:19 091 и 24 268:17 098 без режима
    // ...а пара (v298) — только на свободную клетку: флаг, на котором сидит его крип, берёт армия силой. Первая редакция
    // слала пары и на занятые — стендовый фермер scatter держит на каждом своём флаге по крипу, пары весь матч ходили к ним и
    // бежали, ядро из шести флагов не брало, и match28/19:scatter проиграны по очкам (18 873:24 312, 14 925:24 322)
    val wanted = flags.filter { !it.ours && it.occupant?.my != true && captureAllowed(ctx, it, meas.view, CapAsker.ARMY) && !(roster.safe && it.occupant != null) &&
        !(roster.safe && getRange(it.pos, ctx.home) > getRange(it.pos, ctx.enemyHome) &&
            flags.any { o -> !o.ours && getRange(o.pos, ctx.home) <= getRange(o.pos, ctx.enemyHome) }) }

        .sortedBy { f -> free.minOf { getRange(it, f.pos) } }
    // ...И ОТРЯД НА ПУТИ К ФЛАГУ СОХРАНЯЕТ ЗАДАНИЕ (v299): задание раздавалось заново каждый тик, а флаг, к которому уже
    // идёт бегун, командир не дублирует, — своя же пара с прошлого тика закрывала ему этот флаг, и её распускали через
    // тик: против けろびー#19 армия мерцала 12↔6 каждые десять тиков, охрана у взятого флага стояла 0–12 тиков за матч
    init {
        if (roster.safe) {
            val outIds = roster.alreadyOut.mapTo(HashSet()) { it.id }
            val enRoute = free.filter { it.id in outIds }.groupBy { Squads.runnerFlag[it.id] }
            for ((fid, members) in enRoute) {
                // ...и СВОЙ ПУСТОЙ ФЛАГ ТОЖЕ ЖДЁТ (v317): гарнизонный боец (v316) шёл к нашему флагу, а удержание задания
                // знало только про чужие флаги — его распускали через тик, и он возвращался в армию, не дойдя: man=14–99
                // назначений за матч при 1,74 нашего флага
                val f = wanted.firstOrNull { it.id == fid }
                    ?: flags.firstOrNull { it.id == fid && it.ours && it.occupant?.my != true }
                    ?: continue
                if (purse.budget < members.size) continue
                val without = free.filter { c -> members.none { it.id == c.id } }
                if (!roster.coreHolds(without)) break
                for (c in members) { Squads.detach(c.id, f.id); free.remove(c) }
                purse.budget -= members.size
                routeKept.n += members.size
            }
        }
    }
}

/** ПОДСТАДИЯ 4 (режим пар): постоянный гарнизон ближних флагов, курьер на дорогой флаг, прибор покрытия лечением, тела на свои пустые флаги. */
internal class RaceGarrison(private val ctx: Ctx, private val flags: List<FlagInfo>, private val roster: RaceRoster, private val free: MutableList<Creep>, private val purse: RaceBudget) {
    // СВОЙ ПУСТОЙ ФЛАГ — СНАЧАЛА (v316, см. GROUP_SAFE_DMG): держатели заводились только из захвата армией, и на флагах
    // стоял в среднем ОДИН наш крип из четырнадцати: 1,6 нашего флага против его 5,1 при 37 взятиях и 36 потерях за матч,
    // среднее владение 59 тиков. Гарнизон ставится прямо: на каждый наш флаг без нашего крипа на клетке — ближайший
    // свободный боец, и лишь затем пары за чужими флагами
    // ПОСТОЯННЫЙ ГАРНИЗОН (v337, контрфакт разбора: ядро расходится по четырём ближним флагам с первого тика — 19 838 :
    // 16 739 и 16 побед из 16). Прежде назначение пересчитывалось каждый тик, и крип половину матча шёл через карту:
    // среднее владение 74–105 тиков против его 158, потому что он берёт флаги рядом с собой, а мы — где придётся.
    // Здесь четыре ближайших к дому флага закрепляются за крипами на весь матч и меняются, только если крип погиб
    init {
        if (roster.safe) {
            // прибор `garscout=` (v466, дефект 5): записи скаутов гарнизона на входе в тик гонки — чистка ниже их не удерживает (скаут не в
            // составе и не в cmdDetach), и запись пересоздаётся по сегодняшней близости: на тот же флаг / на другой / не пересоздана
            val scoutBefore = HashMap<String, String>()
            for ((id, fid) in Squads.garrisonOf) if (ctx.runners.any { it.id == id && stripped(it) }) scoutBefore[id] = fid
            Squads.garrisonOf.keys.retainAll { id -> free.any { it.id == id } || Squads.cmdDetach.contains(id) }
            // ...и ШЕСТОЙ ФЛАГ — ТОЛЬКО ПРОТИВ РАССЫПАННОГО (v348): шестёрку гейт отверг на match29:camp (15 991 : 23 801),
            // где его армия собрана и ядру тоньше двух вооружённых уже не устоять; у けろびー армия рассыпана весь матч, и
            // шестое тело — это ровно тот флаг, которого не хватило в двух матчах, проигранных на 22 и 33 очка
            val homeFlags = flags.sortedBy { getRange(it.pos, ctx.home) }
                .take(if (Signals.enemyMassedSignal) GARRISON_FLAGS else GARRISON_FLAGS + 1)
            // ...И СКАУТЫ — ТОЖЕ ГАРНИЗОН (v341): они тела, в бою не нужны, а держат флаг не хуже вооружённого; из четырёх
            // закреплённых в среднем стоит двое — остальные в пути, — и два скаута добавляют ровно недостающие тела
            val scoutsFree = ctx.runners.filter { stripped(it) && canMove(it) && it.id !in Squads.garrisonOf }
                .toMutableList()
            // КУРЬЕР НА ДОРОГОЙ ФЛАГ, КОТОРЫЙ ОН НЕ ДЕРЖИТ ТЕЛОМ (v367). Замер владения по каждому флагу за 8 матчей на
            // соперника показал, что весь проигрыш по очкам сидит в дорогих флагах, и у ОБОИХ соперников там дыра одного
            // вида. けろびー#19: H4#2 даёт ему −44 560 очков разрыва, H4#1 −15 920, и на клетке он стоит 0–1 % тиков —
            // флаг его, а тела нет. ricardo18informatica2020#14 (топ-1): R3#5 и R3#6 он ДЕРЖИТ телом 94–97 % тиков и
            // отдавать не станет, но H4#1 у него не охраняется вовсе (0 %) при владении вровень с нами — занять его
            // целиком даёт +3 460 очков за матч при дефиците 1 400. Пустую клетку берёт кто угодно, поэтому идёт скаут:
            // он тело, в бою не нужен и стоит 100 хитов вместо 1 200–1 600
            // ...и приз должен быть свободен НЕ ТОЛЬКО НА КЛЕТКЕ (v367, сужение по гейту): первая редакция смотрела лишь
            // на occupant и уронила match34:camp (14 481 : 23 960) — скаут уходил на флаг, у которого стоит его
            // вооружённый, и там гиб, ничего не удержав. Скаута сгоняет любой ствол (см. SCOUT_FLEE_TRIGGER), поэтому
            // приз — это флаг, у которого его стволов нет вовсе
            val prize = flags.filter { !it.ours && it.score >= COURIER_SCORE && it.occupant == null &&
                ctx.combatEnemies.none { e -> getRange(e, it.pos) <= SCOUT_FLEE_TRIGGER } }
                .maxByOrNull { it.swing * 100 - getRange(it.pos, ctx.home) }
            // ...и слот курьера ПОСТОЯННЫЙ, как гарнизон (v369). Первая редакция брала скаута из «свободных», а свободных
            // нет: обоих с первого тика забирает постоянный гарнизон, — прибор показал ОДИН тик курьера за матч, и тот
            // единственный тик вырывал скаута из гарнизона и ломал закрепление (0-8 против топ-1, флагов 2,65 против
            // 3,07). Теперь скаут закрепляется за призом на весь матч и в гарнизон не входит вовсе
            Squads.courierOf.keys.retainAll { id -> ctx.runners.any { it.id == id } }
            Squads.courierOf.entries.retainAll { e -> flags.any { it.id == e.value && !it.ours } }
            if (prize != null && Squads.courierOf.isEmpty()) {
                val pick = ctx.runners.filter { stripped(it) && canMove(it) }
                    .minByOrNull { getRange(it, prize.pos) }
                if (pick != null) Squads.courierOf[pick.id] = prize.id
            }
            for ((id, fid) in Squads.courierOf) {
                val c = ctx.runners.firstOrNull { it.id == id } ?: continue
                Squads.garrisonOf.remove(id)
                scoutsFree.removeAll { it.id == id }
                Squads.detach(id, fid)
                free.removeAll { it.id == c.id }
                courierTicks.n++
            }
            for (f in homeFlags) {
                if (Squads.garrisonOf.values.contains(f.id)) continue
                val sc = scoutsFree.minByOrNull { getRange(it, f.pos) }
                if (sc != null && scoutsFree.size >= homeFlags.count { fl -> !Squads.garrisonOf.values.contains(fl.id) }) {
                    Squads.garrisonOf[sc.id] = f.id; scoutsFree.remove(sc); continue
                }
                if (purse.budget <= 0) break
                val c = free.filter { it.id !in Squads.garrisonOf }.minByOrNull { getRange(it, f.pos) } ?: break
                val without = free.without(c)
                if (!roster.coreHolds(without)) break
                Squads.garrisonOf[c.id] = f.id
            }
            for ((id, fid) in scoutBefore) { val now = Squads.garrisonOf[id]; if (now == null) garScoutLost.n++ else if (now == fid) garScoutSame.n++ else garScoutSwitch.n++ }
            // скаут-гарнизон ходит по тем же правилам бегуна: задание за ним, пока он жив
            for ((id, fid) in Squads.garrisonOf) if (ctx.runners.any { it.id == id && !hasWeapon(it) }) Squads.assign(id, fid)
            // ⚠️ ОТВЕРГНУТО ЗАМЕРОМ (v347): смена на флаге — раненый гарнизонный отдаёт флаг целому из ядра (v346). Наших
            // флагов 2,81 против 3,03 у пятёрки без смены, тел на флагах 2,27 против 2,43: смена меняет ОДНОГО уходящего на
            // другого идущего, а клетка всё равно пустует, пока сменщик идёт
            // прибор покрытия гарнизона лечением (v361): считается по стоящим, до раздачи заданий
            val medics = ctx.armyHealers
            for ((id, _) in Squads.garrisonOf) {
                val c = ctx.runners.firstOrNull { it.id == id } ?: ctx.army.firstOrNull { it.id == id } ?: continue
                garAll.n++
                if (medics.any { getRange(it, c) <= HEAL_RANGE }) garCovered.n++
            }
            for ((id, fid) in Squads.garrisonOf) {
                val c = free.firstOrNull { it.id == id } ?: continue
                if (purse.budget <= 0) break
                Squads.detach(id, fid); free.remove(c); purse.budget--; manned.n++
            }
            val unmanned = flags.filter { it.ours && it.occupant?.my != true &&
                ctx.runners.none { r -> Squads.runnerFlag[r.id] == it.id } }
                .sortedByDescending { it.score }
            for (f in unmanned) {
                if (purse.budget <= 0) break
                // ...и на флаг садится ПАРА (v330), а в ней первым — стрелок (v319): одиночка на флаге живёт мало. Разбор
                // 16 матчей v325/v326: мы теряем 5,3 крипа за матч против его 0,5, и 100 % наших смертей — в одиночку
                // (ни одного своего с оружием в трёх клетках), 75 % — в трёх клетках от флага; от групп из двух и больше он
                // отходит (см. GROUP_SAFE_DMG). Второй стоит рядом охраной (см. guardFlag) и не даёт бить первого даром
                val c = free.filter { hasRanged(it) }.minByOrNull { getRange(it, f.pos) }
                    ?: free.minByOrNull { getRange(it, f.pos) } ?: break
                val without = free.without(c)
                if (!roster.coreHolds(without)) break
                Squads.detach(c.id, f.id); free.remove(c); purse.budget--
                manned.n++
            }
        }
    }
}

/** ПОДСТАДИЯ 5: горстки за чужими флагами — размер по его армии, пара со стрелком, ядро обязано остаться сильнее. */
internal class RaceParties(private val ctx: Ctx, private val meas: ArmyMeasures, private val armedEnemies: List<Creep>, private val roster: RaceRoster, private val free: MutableList<Creep>, private val purse: RaceBudget, private val routes: RaceRoutes) {
    init {
        for (f in routes.wanted) {
            if (purse.budget <= 0) break
            // ...и размер горстки задаёт НЕ флаг, а его армия: пока она цела и на ходу, одиночку она перехватывает и
            // бьёт — на match29:kite наши уходили за флагами по одному, армия таяла до двух крипов, а очков не было
            // вовсе (0 : 22 469). Одиночка посылается, только когда перехватывать некому
            val guarded = armedEnemies.any { getRange(it, f.pos) <= ENGAGE_RANGE }
            val loose = armedEnemies.count { e -> canMove(e) } >= COMMAND_MIN_FOES
            // ...идущий туда бегун ЗАСЧИТЫВАЕТСЯ в группу, а не отменяет её: прежний фильтр выкидывал флаг целиком, и
            // когда бегуны разбирали все доступные флаги, командир не отпускал никого вовсе — гейт терял roost
            // (7 597:24 268) и camp, где прежняя логика отряда выпускала бойцов
            // ...а флаг, который уже берёт бегун, командир не дублирует: засчитывать бегуна в группу и досылать бойца
            // замерено хуже — 131 из 135 против 133 (roost трижды, camp)
            if (ctx.runners.any { r -> Squads.runnerFlag[r.id] == f.id }) continue
            val need = if (roster.safe || guarded) 2 else if (loose) RACE_PARTY else 1
            if (purse.budget < need) continue
            // ...а пара — со стрелком (v298): два мили не отвечают его стрелку, который бьёт их с трёх клеток
            val party = if (roster.safe) run {
                val byRange = free.sortedBy { getRange(it, f.pos) }
                val r = byRange.firstOrNull { hasRanged(it) }
                if (r == null) byRange.take(2) else listOf(r) + byRange.filter { it.id != r.id }.take(1)
            } else free.sortedBy { getRange(it, f.pos) }.take(need)
            if (party.size < need) continue
            if (roster.safe) {
                val pack = foesInEngage(armedEnemies, f.pos)
                if (pack.isNotEmpty() && enemyPowerOf(pack, party) >= ourPowerOf(party, pack)) continue
            }
            // ...и ЯДРО ОБЯЗАНО ОСТАТЬСЯ СИЛЬНЕЕ ЕГО АРМИИ — та же проверка, которой держится отряд (см. USE_DETACH):
            // аннигиляция проигрывает матч при любом счёте, поэтому отпускать можно лишь до тех пор, пока оставшиеся
            // держат паритет. Без неё командир растаскивал армию грубее прежней логики и ронял army-сценарии (гейт 127)
            val without = free.filter { c -> party.none { it.id == c.id } }
            if (!roster.coreHolds(without)) break
            // ...и ЗАДАНИЕ — это зачисление в захватчики с целью, а не клетка: вооружённый крип, приведённый к флагу
            // как боец, флага НЕ БЕРЁТ (захват делают бегуны), и первая редакция на сценарии kite набрала 0 очков.
            // Командир решает КТО и КУДА, а ведёт и берёт существующий механизм захвата (v160)
            for (c in party) { Squads.detach(c.id, f.id); free.remove(c); splitAll.n++; if (meas.fight.fightOnNow) splitFight.n++ }
            purse.budget -= need
        }
    }
}


/** СТОЙКА АРМИИ ПОСЛЕ РЕШЕНИЯ (v256, этап 10; сегмент runArmy): блок в бою (blockOn), прижим стоящей линии (pressing), признаки отхода по мощи и по размену, применение режима командира и окончательной постуры (Strategist.decide → postureFinal), приборы режима. Перенесено дословно. */
internal class ArmyStance(ctx: Ctx, meas: ArmyMeasures, strat: ArmyStrategy, targ: ArmyTargets) {
    val windows = StanceWindows(ctx, meas, strat, targ)
    val press = StancePress(meas, strat, windows)
    val breakOff = StanceBreakOff(meas, strat)
    val applied = StanceApply(meas, strat)
    val gauges = StanceGauges(meas, strat)
}

/** ПОДСТАДИЯ 1 СТОЙКИ: блок в бою и окна истории — лекарь у дерущегося, смена направления, сближение их мили и центров армий, подход без контакта, доля касания мили, пат, отход нашей линии. */
internal class StanceWindows(private val ctx: Ctx, private val meas: ArmyMeasures, private val strat: ArmyStrategy, private val targ: ArmyTargets) {
    init { cpuMark("posture") }
    val blockOn =  posture == Posture.ANNIHILATE && !pushing && meas.forces.combatEnemies.isNotEmpty() && meas.forces.strikers.isNotEmpty()
    // прижим (см. USE_PRESS, PRESS_PATIENCE): линия врага стоит — контакт, его огонь достаёт наших, и ни один его мили не
    // в MELEE_HOLD_RANGE + 1 от наших вооружённых; включившись, держится, пока есть контакт и строй
    // ЕСТЬ ЛИ ЛЕКАРЬ У ТОГО, КТО ДЕРЁТСЯ (v215, оператор: «без хиллеров ни один бой выиграть невозможно»).
    // Прежний `hfar` мерил другое — расстояние лекаря до центроида ВООРУЖЁННЫХ; у армии, растянутой на
    // тридцать клеток, этот центроид стоит посреди пустоты, и «лекарь при армии» там ничего не значит.
    // Здесь вопрос задан по КАЖДОМУ дерущемуся: есть ли свой лекарь в дальности лечения
    private val medsNow = ctx.armyHealers
    init {
        for (c in strat.inp.combatArmy) {
            if (!hasWeapon(c) || meas.forces.armedEnemies.none { getRange(c, it) <= RANGED_RANGE + 1 }) continue
            healGapN.n++
            if (medsNow.none { getRange(c, it) <= HEAL_RANGE }) healGap.n++
            // ...и ОТДЕЛЬНО — жалоба оператора дословно: «в бою не оказывается НИ ОДНОГО хиллера». Это не «лекарь в
            // четырёх клетках вместо трёх», это «лекаря рядом нет вовсе»: боец дерётся там, куда лекарь не придёт
            if (medsNow.none { getRange(c, it) <= MASS_RANGE }) noMedic.n++
        }
    }
    // СМЕНА НАПРАВЛЕНИЯ АРМИИ (v215, оператор: «пару тиков погоня, потом разворот, и так много раз»).
    // Направление — это то, КУДА армия идёт: флаг-цель, добыча или пост. Одна смена за матч — это план,
    // сто — это дрожь, и пара «смен/тиков» отличает одно от другого
    private val aimNow = strat.obj.objective?.flag?.id?.let { "F$it" } ?: targ.quarry.prey?.id?.let { "E$it" } ?: "P"
    init {
        aimTicks.n++
        if (lastAim.isNotEmpty() && aimNow != lastAim) aimFlips.n++
        lastAim = aimNow
    }
    // «их мили идут» (см. PRESS_CLOSING): дистанция их мили до наших вооружённых за окно терпения
    private val theirMeleeDist = meas.forces.combatEnemies.filter { InfluenceMap.profileOf(it).melee > 0.0 }
        .minOfOrNull { e -> armedOf(strat.inp.combatArmy).minOfOrNull { getRange(e, it) } ?: 99 } ?: 99
    init { if (meas.fight.contact) Memory.meleeDistHist.addLast(theirMeleeDist) else Memory.meleeDistHist.clear() }
    init { while (Memory.meleeDistHist.size > PRESS_PATIENCE + 1) Memory.meleeDistHist.removeFirst() }
    val theirMeleeClosing = Memory.meleeDistHist.size > PRESS_PATIENCE && Memory.meleeDistHist.first() - Memory.meleeDistHist.last() >= PRESS_CLOSING
    // «армии сближаются» (v47, оператор: «признак по сближению центров армий»): дистанция между центрами ВООРУЖЁННЫХ
    // армий за окно терпения сократилась не меньше PRESS_CLOSING — атака; стоит — стоячий бой (см. standingNow). Признак
    // «его мили не вплотную» выключал расстановку ровно в боях с Coldkimchi (его мили лезут вплотную к стрелкам), а
    // «его мили не идут» (v43f) брал и остановившуюся атаку — центры армий отличают одно от другого
    private val ourArmedC = centroidOf(armedOf(strat.inp.combatArmy).map { InfluenceMap.cell(it.x, it.y) })
    private val theirArmedC = centroidOf(meas.forces.armedEnemies.map { InfluenceMap.cell(it.x, it.y) })
    init { if (meas.fight.contact && ourArmedC != null && theirArmedC != null) Memory.centreDistHist.addLast(getRange(ourArmedC, theirArmedC)) else Memory.centreDistHist.clear() }
    init { while (Memory.centreDistHist.size > PRESS_PATIENCE + 1) Memory.centreDistHist.removeFirst() }
    val armiesClosing = Memory.centreDistHist.size > PRESS_PATIENCE && Memory.centreDistHist.first() - Memory.centreDistHist.last() >= PRESS_CLOSING
    // СБЛИЖЕНИЕ БЕЗ КОНТАКТА (v183, оператор: «делай строй до боя»). Изготовка требовала `!contact && armiesClosing`,
    // а armiesClosing набирается ТОЛЬКО в контакте: строкой выше история центров чистится, едва контакт пропал.
    // Условия взаимоисключающие — commandBrace не выполнялся НИ РАЗУ, и прибор это подтвердил на разгроме 3d9532:
    // за весь подход командир отдал ноль приказов (cmd=0/50 … cmd=0/90), к первому выстрелу армия стояла смазкой
    // 5,8×6,1 клетки против его кирпича 4,5×3,9. Здесь то же расстояние пишется ВСЕГДА, и «он идёт на нас»
    // становится измеримым до первого выстрела
    init { if (ourArmedC != null && theirArmedC != null) Memory.approachHist.addLast(getRange(ourArmedC, theirArmedC)) else Memory.approachHist.clear() }
    init { while (Memory.approachHist.size > PRESS_PATIENCE + 1) Memory.approachHist.removeFirst() }
    val enemyApproaching =  Memory.approachHist.size > PRESS_PATIENCE &&
        Memory.approachHist.first() - Memory.approachHist.last() >= PRESS_CLOSING
    // ПАТ: БОЙ, В КОТОРОМ НИКТО НИКОГО НЕ УБЬЁТ (v189, разбор Coldkimchi#2). Замер матча 3d97d8 за 1400 тиков:
    // наших выстрелов 2 639 против его 1 234, нашего урона 156 100 против его 78 310 — и при этом его лечение
    // 237 228, наше 158 400. Обе стороны перелечивают входящее, никто не гибнет, и матч решают ФЛАГИ: у него
    // четыре, у нас один, счёт 3 210:18 145. В таком бою дебафф флага БЕСПЛАТЕН: он режет урон и лечение, но
    // «убить нельзя» от этого не меняется, пока запас лечения велик, — и соперник этим пользуется, а мы нет,
    // потому что вето захвата считает нас слабее и запрещает всё. Пат меряется временем: за окно STALEMATE_WINDOW
    // ни одна сторона не потеряла больше STALEMATE_LOSS своих хитов. Именно временем, и в этом отличие от
    // отвергнутого USE_CAPTURE_WHEN_WHOLE (v187, 0:12): «армия цела» истинно и на входе в размен, до всякого пата,
    // поэтому та правка брала флаг ровно тогда, когда дебафф решал бой, — и армия гибла девять раз из двенадцати
    // ДОСТАЁТ ЛИ НАШ МИЛИ ВООБЩЕ (v192, см. USE_MELEE_OUT_OF_FIRE): доля мили, стоявших вплотную к врагу, за окно
    // контакта. Величина измеряется, а не назначается: против кайтера она падает до нуля сама, против того, кто
    // идёт в размен, держится высокой, и правило снимается без порога, подогнанного под сегодняшнего соперника
    init {
        if (meas.fight.contact) {
            // ...ПО ЖИВОЙ ATTACK (v452, пункт Д): «достаёт ли наш мили» — вопрос об ударе, и раздетый мили в знаменателе занижал долю
            // касания (у него — завышал симметрично, см. hisMelee ниже). Написание Б = meleeOnlyLive
            val meleeN = strat.inp.combatArmy.count { meleeOnlyLive(it) }
            val touched = strat.inp.combatArmy.count { meleeOnlyLive(it) && meas.forces.combatEnemies.any { e -> getRange(it, e) <= 1 } }
            // ...и при НУЛЕ мили в ядре окно не трогается вовсе: иначе отряд, уведённый по этому же признаку, обнуляет
            // мили в строю, доля прыгает к единице, признак гаснет и отряд отзывается — качели через тик
            if (meleeN > 0) Memory.touchHist.addLast(100 * touched / meleeN)
            val hisMelee = meas.forces.combatEnemies.count { meleeOnlyLive(it) }
            val hisTouched = meas.forces.combatEnemies.count { meleeOnlyLive(it) && strat.inp.combatArmy.any { a -> getRange(it, a) <= 1 } }
            if (hisMelee > 0) Memory.hisTouchHist.addLast(100 * hisTouched / hisMelee)
        } else { Memory.touchHist.clear(); Memory.hisTouchHist.clear() }
    }
    init { while (Memory.touchHist.size > TOUCH_WINDOW) Memory.touchHist.removeFirst() }
    init { while (Memory.hisTouchHist.size > TOUCH_WINDOW) Memory.hisTouchHist.removeFirst() }
    // ...И НЕПОЛНОЕ ОКНО ЛУЧШЕ ЗАВЕДОМО НЕВЕРНОГО ЗНАЧЕНИЯ ПО УМОЛЧАНИЮ (v502, см. USE_TOUCH_PARTIAL_WINDOW). Доля
    // требовала TOUCH_WINDOW = 50 тиков контакта ПОДРЯД (история чистится на каждом тике без контакта строкой выше),
    // а бой против MetalicaX#17 решается за 40-50 тиков при мерцающем контакте. Когда окно не наполнилось, действует
    // 1.0 — «мили бьёт вплотную каждый тик», — при настоящем прилегании около 20 % (прибор `madj=` v501, 14 рук).
    // Мера мощи завышает тогда мили впятеро, а на ней стоят цена захвата, пол паритета, постура и отход. Замер
    // `touchl=` по тем же 14 рукам: в четырёх победах 8, 16, 18, 23 — ни разу 100; в десяти поражениях пять раз 100,
    // то есть ровно «замера нет». Правка берёт среднее по тому, что есть, начиная с TOUCH_MIN_SAMPLES замеров
    val touchShare = if (Memory.touchHist.size >= TOUCH_WINDOW) Memory.touchHist.sum() / (100.0 * Memory.touchHist.size)
        else if (USE_TOUCH_PARTIAL_WINDOW && Memory.touchHist.size >= TOUCH_MIN_SAMPLES) Memory.touchHist.sum() / (100.0 * Memory.touchHist.size)
        else 1.0
    val hisTouchShare = if (Memory.hisTouchHist.size >= TOUCH_WINDOW) Memory.hisTouchHist.sum() / (100.0 * Memory.hisTouchHist.size)
        else if (USE_TOUCH_PARTIAL_WINDOW && Memory.hisTouchHist.size >= TOUCH_MIN_SAMPLES) Memory.hisTouchHist.sum() / (100.0 * Memory.hisTouchHist.size)
        else 1.0
    init { if (touchShare < touchMin) touchMin = touchShare }
    // ...и ПОСЛЕДНЯЯ ИЗМЕРЕННАЯ доля живёт дальше окна (v433, см. USE_TOUCH_SHARE_LAST): мера мощи берёт её, а не единицу,
    // которую окно показывает вне контакта — ровно в те тики, когда ворота захвата и срабатывают
    // ...и с v474 (дефект 12) её ЗАПОМИНАЕТ `RememberTick` в `Prev.touchShareLast`, а не стойка посреди тика: до того мощь в одном
    // тике считалась двумя долями — по вчерашней до этой строки, по сегодняшней после (командир, тактик)
    private val stalemateOurNow = strat.inp.combatArmy.sumOf { it.hits }
    private val stalemateHisNow = meas.forces.combatEnemies.sumOf { it.hits }
    // ОКНО ПАТА ПЕРЕЖИВАЕТ МИГАНИЕ КОНТАКТА (v198). Окно очищалось на КАЖДОМ тике без контакта, а `contact` в
    // стоянке мигает — и в матче 3d9894, где 1700 тиков не погиб ни один крип ни у нас, ни у него, прибор дошёл
    // лишь до 48 из ста нужных (`pat=0/48`). Признак, который в чистейшем пату не может стать истинным, ничего не
    // измеряет: он и был причиной, по которой v189 не с чем было сравнивать. Разрыв короче STALEMATE_GAP окно
    // держит — сто тиков «ни одна сторона не потеряла и пяти процентов хитов» остаются теми же ста тиками
    init {
        if (meas.fight.contact) { Memory.stalemateOurHist.addLast(stalemateOurNow); Memory.stalemateHisHist.addLast(stalemateHisNow); stalemateGap = 0 }
        else {
            stalemateGap++
            if (stalemateGap > STALEMATE_GAP) { Memory.stalemateOurHist.clear(); Memory.stalemateHisHist.clear() }
        }
    }
    init { while (Memory.stalemateOurHist.size > STALEMATE_WINDOW) Memory.stalemateOurHist.removeFirst() }
    init { while (Memory.stalemateHisHist.size > STALEMATE_WINDOW) Memory.stalemateHisHist.removeFirst() }
    // ПРИБОР, а не правило: счётчик пата считается всегда, независимо от того, кто им пользуется. Он был
    // загейчен на USE_CAPTURE_IN_STALEMATE, и когда тот выключили после отказа v189, счётчик замер на нуле —
    // вместе с ним умерло правило v190, которое на него опиралось: двенадцать игр измерили не его, а v186
    private val stalemateNow = Memory.stalemateOurHist.size >= STALEMATE_WINDOW && run {
        val ourDrop = Memory.stalemateOurHist.first() - Memory.stalemateOurHist.last()
        val hisDrop = Memory.stalemateHisHist.first() - Memory.stalemateHisHist.last()
        ourDrop < Memory.stalemateOurHist.first() * STALEMATE_LOSS && hisDrop < Memory.stalemateHisHist.first() * STALEMATE_LOSS
    }
    init { stalemateTicks.n = if (stalemateNow) stalemateTicks.n + 1 else 0 }
    init { if (stalemateTicks.n > patMax.n) patMax.n = stalemateTicks.n }
    // наша линия отступает (v96, USE_STANDING_LINE_HOLDS): центр наших вооружённых за окно терпения отдалился от его
    // НЫНЕШНЕГО центра на PRESS_CLOSING и больше — бой не стоячий, это отход под огнём, и расстановке в нём места нет
    init { if (meas.fight.contact && ourArmedC != null) Memory.ourCentreHist.addLast(ourArmedC.key) else Memory.ourCentreHist.clear() }
    init { while (Memory.ourCentreHist.size > PRESS_PATIENCE + 1) Memory.ourCentreHist.removeFirst() }
    val ourYielding =  theirArmedC != null && Memory.ourCentreHist.size > PRESS_PATIENCE && run {
        val was = Memory.ourCentreHist.first(); val now0 = Memory.ourCentreHist.last()
        // `!!`: поле носителя внутри лямбды инициализатора компилятор не сужает, как сужал локальную; непустота проверена левее, в этом же `&&`
        getRange(InfluenceMap.cell(now0 / 100, now0 % 100), theirArmedC!!) - getRange(InfluenceMap.cell(was / 100, was % 100), theirArmedC!!) >= PRESS_CLOSING
    }
}

/** ПОДСТАДИЯ 2: прижим стоящей линии — счётчик стоянки, защёлка `pressing`, «цель уходит» (`pressChase` / `pressGiveUp`). */
internal class StancePress(private val meas: ArmyMeasures, private val strat: ArmyStrategy, private val windows: StanceWindows) {
    // их мили на подходе — в (MELEE_HOLD_RANGE + 2 .. ENGAGE_RANGE] от наших вооружённых — это не стоячая линия и не пустое
    // место: матч 56 (G1N6ERbreadMan) — прижим на 115-м при его мили в 4–7, через три тика они вошли в нашу пачку у своих же
    // стрелков (его 51 удар против наших 24, a→ranged 31), пять наших потеряны за двадцать тиков. Прижим — линии, чьи мили
    // либо у неё (Kero v2, Coldkimchi: в 2–3 и не бьют), либо далеко; подходящих строй ждёт — они входят в его фокус сами
    private val theirMeleeMid = meas.forces.combatEnemies.any { e ->
        InfluenceMap.profileOf(e).melee > 0.0 &&
            (armedOf(strat.inp.combatArmy).minOfOrNull { getRange(e, it) } ?: 99).let { it > MELEE_HOLD_RANGE + 2 && it <= ENGAGE_RANGE }
    }
    init { standoffTicks = if (meas.fight.contact && strat.inp.underTheirFire && !strat.inp.theirMeleeIn && !windows.theirMeleeClosing && !theirMeleeMid) standoffTicks + 1 else 0 }
    init { pressing =  windows.blockOn && meas.fight.contact && !strat.push.leadHolds && (standoffTicks >= PRESS_PATIENCE || pressing) }
    val pressOn = pressing
    // «цель уходит» (см. PRESS_GIVEUP): за два тика прижима дистанция от наших мили до неё не сократилась
    init {
        if (windows.blockOn) {
            val ourMelee = pureMeleeOf(strat.inp.combatArmy)
            for (e in meas.forces.combatEnemies) {
                val near = ourMelee.minByOrNull { getRange(e, it) } ?: continue
                val d = getRange(e, near)
                if (d > PRESS_RANGE + 1) { StrategistState.pressChase.remove(e.id); continue }
                val h = StrategistState.pressChase.getOrPut(e.id) { ArrayDeque() }
                h.addLast(ChaseSample(d, e.key, near.key))
                while (h.size > 3) h.removeFirst()
                // цель ушла (v96, USE_GIVEUP_HE_LEAVES): САМА отдалилась от места, где стоял наш ближайший мили в начале окна, —
                // а не «мы к ней не приблизились»: идущий за нашим отходом к тому месту приближается
                val first = h.first()
                val from = InfluenceMap.cell(first.meleeCell / 100, first.meleeCell % 100)
                val left =  getRange(e, from) > getRange(InfluenceMap.cell(first.eCell / 100, first.eCell % 100), from)
                if (h.size == 3 && d > (MELEE_HOLD_RANGE) && h.last().d >= first.d && left) {
                    StrategistState.pressGiveUp[e.id] = getTicks() + PRESS_GIVEUP
                    h.clear()
                    if (DEBUG_LOG) println("press t=${getTicks()}: ${e.id} keeps its distance — not pressed for $PRESS_GIVEUP ticks")
                }
            }
        } else StrategistState.pressChase.clear()
    }
    init { StrategistState.pressGiveUp.entries.removeAll { it.value <= getTicks() } }
    init { if (DEBUG_LOG && pressOn && standoffTicks == PRESS_PATIENCE) println("press t=${getTicks()}: the enemy line has stood at range for $PRESS_PATIENCE ticks under fire — the pack goes in") }
}

/** ПОДСТАДИЯ 3: признаки проигранного размена — по мощи и по счёту хитов; сам признак отхода посчитан мерами (`meas.breakOffNow`), здесь прибор расхождения. */
internal class StanceBreakOff(private val meas: ArmyMeasures, private val strat: ArmyStrategy) {
    // РАЗМЕН, КОТОРЫЙ УЖЕ ПРОИГРАН, НАДО ПРЕКРАЩАТЬ (v185, разбор серии). Прибор разделил двадцать матчей начисто:
    // в ВОСЬМИ поражениях армия стояла в бою при мощи ниже 60 % от его от 31 до 94 % боевых тиков (410 тиков из
    // 512), в ОДИННАДЦАТИ победах из двенадцати — НОЛЬ таких тиков (3 из 236 по всей пачке). Против Coldkimchi это
    // видно построчно: его мощь держится около 4 000 весь бой, наша падает до 1 500–2 000 при живых одиннадцати
    // крипах — он вылечивает своих обратно, мы нет, а армия продолжает стоять и таять. Доктрина паритета говорит
    // ровно это: не менять, когда мы слабее. Признак — ИЗМЕРЕННАЯ мощь обеих сторон, а не прогноз командира: тот
    // в минус не уходит никогда (см. USE_COMMAND_RETREAT), и потому основанием служить не может
    // ...И ПРИЗНАК СЧИТАЕТСЯ ПО ФАКТУ РАЗМЕНА (v216, см. USE_BREAK_OFF_BY_LEDGER). Обе величины считаются на
    // контактных тиках, чтобы прибор назвал числом, как часто они расходятся: старый признак стоил ровно
    // столько же — два вызова powerOf, — поэтому цена замера нулевая
    private val outmatchedByPower =  meas.fight.contact && meas.forces.armedEnemies.isNotEmpty() && run {
        val oursNow = ourPowerOf(strat.inp.combatArmy, meas.forces.combatEnemies)
        val theirsNow = enemyPowerOf(meas.forces.combatEnemies, strat.inp.combatArmy)
        theirsNow > 0.0 && oursNow < theirsNow * BREAK_OFF_RATIO
    }
    // «мы теряем хиты, а он почти нет» — то же отношение BREAK_OFF_RATIO, только по фактическим потерям
    // ...и размен берётся НАКОПЛЕННЫЙ, а не за окно. Окно в STALL_TICKS тиков ловит местный размах и врёт в
    // другую сторону: на 200-м тике match35 за последние двадцать тиков мы потеряли 998 хитов против его 327 —
    // и признак объявил отход, — тогда как ЗА МАТЧ мы к этому моменту убили троих его крипов и не потеряли ни
    // одного (16 000/16 000 у нас против его 10 798/11 600). Строка перестала проходить гейт. Вопрос «стоит ли
    // прекращать размен» — про размен целиком, а не про последние двадцать тиков; окно осталось прибором
    private val outmatchedByLedger =  meas.fight.contact && meas.forces.armedEnemies.isNotEmpty() &&
        ourDamageTaken > 0 && enemyDamageTaken < ourDamageTaken * BREAK_OFF_RATIO
    // ...а САМ признак посчитан выше (v217, см. outmatchedNow): здесь остаётся только прибор расхождения
    init {
        if (meas.fight.contact && meas.forces.armedEnemies.isNotEmpty()) {
            breakOffN.n++
            if (outmatchedByPower != outmatchedByLedger) breakOffSplit.n++
        }
    }
}

/** ПОДСТАДИЯ 4: причина режима командира и применение окончательной постуры (`Strategist.decide` -> `postureFinal`). */
internal class StanceApply(private val meas: ArmyMeasures, private val strat: ArmyStrategy) {
    init { cmdWhy.bump(strat.dec.cmdWhyNow) }
    init {
        cmdWhyN.n++
        // ...и условие командира теперь ОДНО: он правит там, где сам назвал режим боя. Прежние пять множителей
    }
    // (контакт, сомкнутость или шесть рядом, постура, огонь, отсутствие отхода и затора) целиком перешли в
    // выбор режима выше — это то же самое, сказанное один раз, и дальше режимы можно наполнять по одному
    // ...и ЗАПИСЬ ИДЁТ ЧЕРЕЗ ТЕ ЖЕ ЧАСЫ (v215). Здесь и ниже постура присваивалась в обход гистерезиса и БЕЗ
    // обновления `postureSince`: часы оставались от прошлой смены, срок POSTURE_HOLD оказывался уже вышедшим, и
    // на следующем же тике постуру можно было сменить обратно. То есть единственный гистерезис в файле ломался
    // именно там, где он нужнее всего — в бою. Замер по двадцати рейтинговым матчам: в худших матчах 352 смены
    // постуры за 1700 тиков, медиана удержания ОДИН тик, 71–81 % смен живут не дольше трёх тиков
    // ПАРА К КОМАНДИРУ (v221, см. warmNow): сколько тиков режима боя командир держит при тёплом контакте — на
    // этих тиках он вернёт ANNIHILATE сам, что бы ни решила постура
    init { if (strat.dec.decision.cmdMode == CmdMode.FIGHT) { warmCmdAll.n++; if (strat.contact.warmNow) warmCmd.n++ } }
    init { posture = strat.dec.decision.postureFinal }
    init { postureSince = strat.dec.decision.postureSinceFinal }
    // ПИЛА ПОСТУРЫ (v479, см. USE_ESCAPE_UNDER_FIRE): смены считаются у ЕДИНСТВЕННОГО писателя, строкой ниже записи.
    // Эпизод короче срока гистерезиса не может родиться иначе как изъятием — поэтому средняя часть и есть мера пилы
    init {
        if (posture != strat.inp.posturePrev) {
            postFlips.n++
            if (strat.inp.now - strat.inp.postureSincePrev < POSTURE_HOLD) postFlipShort.n++
            if (posture == Posture.RETREAT && !strat.inp.underTheirFire) postFlipDry.n++
        }
    }
    // ...и выйти из режима боя МАЛО: постура остаётся ANNIHILATE сама по себе (она липкая и решает по своим
    // признакам), а именно она держит армию в размене. В разгромах серии режим прыгал FIGHT/RACE, а постура все
    // эти сотни тиков стояла ANNIHILATE при нашей мощи вдвое ниже. Отход объявляет командир — по измеренной мощи
    init {
        if (meas.fight.breakOffNow && DEBUG_LOG && getTicks() % LOG_EVERY == 0)
            println("cmd t=${getTicks()}: outmatched for $outmatchedTicks ticks — the advance stops, the line holds")
    }
}

/** ПОДСТАДИЯ 5: приборы, снятые ПОСЛЕ окончательной постуры, — отход, пустая боевая постура, сбор, отход под огнём. */
internal class StanceGauges(private val meas: ArmyMeasures, private val strat: ArmyStrategy) {
    // ПРИБОРЫ ОТХОДА (v217). Считаются ЗДЕСЬ, после того как постура окончательна: командир перезаписывает
    // её на 600 строк позже, чем она решается, и прибор, снятый раньше, рассказал бы про другую постуру
    init {
        if (outmatchedTicks >= BREAK_OFF_TICKS) {
            outmTicks.n++
            if (posture == Posture.RETREAT) outmRetreat.n++
        }
    }
    // РАЗЛОЖЕНИЕ ПУСТОЙ БОЕВОЙ ПОСТУРЫ (v221, прибор). Живой A/B против MetalicaX#2: с USE_FIGHT_BY_LEDGER доля
    // тиков ANNIHILATE без размена НЕ упала (20 % против 16 % в контроле), хотя тёплый контакт в контакте упал с
    // 0,45 до 0,27. Значит, пустую постуру держит другой источник, и его надо назвать числом, а не гадать. Порядок
    // проверки — порядок власти над постурой: командир перезаписывает её последним
    init {
        if (posture == Posture.ANNIHILATE && !meas.exchange.exchangeLive) {
            annEmptyAll.n++
            val src = when {
                strat.dec.decision.cmdMode == CmdMode.FIGHT -> "cmd"
                pushing -> "push"
                strat.contact.holdingSpot -> "spot"
                meas.chase.meleeAdjacent -> "melee"
                meas.chase.cornered -> "corner"
                meas.fight.contact && strat.contact.hisStill -> "still"
                meas.fight.contact -> "warm"
                else -> "held"
            }
            annEmpty.bump(src)
        }
    }
    // ПАРА К СБОРУ (v218, см. gatherSpread). Сбор (rallyTo) и сплочение (cohesionHold) намеренно молчат в
    // HOLD, и основание записано рядом с ними: «в HOLD цель — точка, к ней сходятся и так». Прибор проверяет
    // именно это основание — растянут ли строй стрелков в HOLD шире того порога, каким сбор и включается.
    // Мал числитель — основание верно, трогать сбор незачем
    init {
        if (posture == Posture.HOLD) {
            gatherHold.n++
            val shooters = rangedOf(strat.inp.combatArmy)
            if (shooters.size > 1 && shooters.maxOf { a -> shooters.maxOf { b -> getRange(a, b) } } > RALLY_RANGE) gatherSpread.n++
        }
    }
    // ...И ТА ЖЕ ПАРА В ПОСТУРЕ БОЯ (v221, только прибор). Разбор v220, матч #11: ведём +1077, на 1400-м армия
    // растянута по флагам, его шестеро бьют ближайшую часть, и десять целых крипов ложатся за 80 тиков. Сбор
    // (rallyTo) в ANNIHILATE не работает по построению, а сплочение (cohesionHold) — ожидание, которое гасит
    // огонь по своим. Прибор отдельный, чтобы прежний `gather=` по HOLD остался сравним с логами v218–v220
    init {
        if (posture == Posture.ANNIHILATE) {
            gatherAnnAll.n++
            val sh = rangedOf(strat.inp.combatArmy)
            if (sh.size > 1 && sh.maxOf { a -> sh.maxOf { b -> getRange(a, b) } } > RALLY_RANGE) gatherAnn.n++
        }
    }
    init {
        if (posture.withdrawing) {
            retrTicks.n++
            // ...и точка спрашивается ПО СВОЕЙ постуре: у отхода — `retreatTo`, у уклонения — `evadeTo`.
            // Смешивать их нельзя ровно потому, что дефект живёт в отходе: `evadeTo` почти всегда есть, и
            // общий счётчик показал бы 98 % там, где у отхода ноль
            if (if (posture == Posture.RETREAT) strat.dec.retreatTo != null else strat.obj.evadeTo != null) retrWithPoint.n++
            if (strat.inp.underTheirFire) retrUnderFire.n++
            for (c in strat.inp.combatArmy) {
                if (!hasWeapon(c)) continue
                standTicks.n++
                if (meas.forces.combatEnemies.any { getRange(c, it) <= (if (hasRanged(c)) RANGED_RANGE else 1) }) standFire.n++
            }
        }
    }
}

/** Стрелковая масса списка: сумма дальнего урона по профилям (до v445 — локальная функция `armyStrategy`). */
private fun rangedMass(cs: List<Creep>) = cs.sumOf { InfluenceMap.profileOf(it).ranged }

/** Тиков до прихода его крипа к нашей массе по мере этого тика; неизвестный — «очень далеко» (до v445 — локальная функция). */
private fun arrivalOf(c: Creep) = WorldState.arrivalById[c.id] ?: Int.MAX_VALUE / 2

/** Один вопрос «идём ли в наступление»: то, что `armyStrategy` уже посчитал к этому месту. `pushing` и `pushSince` строки читают
 *  у `PainAndGain` — СТАРЫМИ: условия всех строк вычисляются до действия выигравшей; `fightOnNow` — тоже его член. */
// v456 (второй шаг архитектуры, этапы 3–4): величина, которая уже есть у носителя выше, ЧИТАЕТСЯ ИЗ НЕГО, а не копируется ещё
// одним объявлением — до v456 `stalled`, `breakOffNow`, `now`, `oursPush`, `theirsPush`, `pushRelease` переписывались сюда
// позиционными аргументами (`stalled` существовал в пакете четырежды). Своих полей у вопроса два — то, что считает сама
// подстадия наступления.
internal class PushCase(val meas: ArmyMeasures, val packs: StrategyPacks, val thr: StrategyThresholds, val pushRaw: Boolean, val toothless: Boolean)

/** Счётчики решения о наступлении (прибор `reach t=`, таблица `push`). */
internal val pushTally = Tally("push")

private var pushRuleRows: List<Row<PushCase, Boolean>>? = null

/**
 * РЕШЕНИЕ О НАСТУПЛЕНИИ (v445): пять строк, порядок списка = приоритет; действие отдаёт новое `pushing`. Прежде это был `if
 * (breakOffNow) { pushing = false } else pushing = when { … }` — `if` оборачивал присваивание, и счётчики ветвей (`pushSince`,
 * `pushToothless`, `pushHeldTicks`, `pushHeld`) при разрыве контакта не двигались; здесь то же самое: счётчик стоит в действии
 * своей строки и исполняется только у выигравшей.
 */
internal fun pushRules(): List<Row<PushCase, Boolean>> = pushRuleRows ?: listOf<Row<PushCase, Boolean>>(
    Row("breakOff", { meas.fight.breakOffNow }) { false },
    Row("raw", { pushRaw }) { if (!pushing) pushSince = meas.exchange.now; true },
    Row("toothless", { pushing && toothless && !meas.chase.stalled }) { pushToothless.n++; pushHeld = true; true },
    Row("dwell", { pushing && meas.fight.fightOnNow && meas.exchange.now - pushSince < PUSH_DWELL && !meas.chase.stalled && packs.oursPush >= packs.theirsPush * thr.pushRelease }) {
        pushHeld = true; pushHeldTicks.n++; true
    },
    Row("none", { true }) { false },
).also { pushRuleRows = it }


/** СТРАТЕГИЯ ТИКА (v256, этап 10; сегмент runArmy): стая боя и стая толчка по порядку прихода (fightPack, pushPack), толчок (pushing), отряд за флагами (detachedIds) и его отзыв, вето погони, поля выхода, цель-флаг, уклонение, отход и решение Strategist.decide с применением постуры до перезаписи режимом. Перенесено дословно. */
internal class ArmyStrategy(ctx: Ctx, meas: ArmyMeasures) {
    val packs = StrategyPacks(ctx, meas)
    val thr = StrategyThresholds(ctx, meas, packs)
    val detach = StrategyDetach(ctx, meas)
    val push = StrategyPush(ctx, meas, packs, thr)
    val contact = StrategyContact(ctx, meas, packs)
    val obj = StrategyObjective(ctx, meas, packs, thr, detach, push, contact)
    val inp = StrategyInputs(ctx, meas, thr, contact, obj)
    val dec = StrategyDecide(ctx, meas, packs, thr, obj, inp)
    val threats = StrategyThreats(ctx, meas)
}

/** ПОДСТАДИЯ 1 СТРАТЕГА: боевая и наступательная пачки, мощь сторон. */
internal class StrategyPacks(private val ctx: Ctx, private val meas: ArmyMeasures) {
    // мера боя (v120, см. USE_FIGHT_PACK_MEASURE): его боевые по порядку прихода к нашей массе — первый и все, кто придёт,
    // пока стая, набранная до него, умирает под нашим огнём (окно растёт вместе со стаей: колонна входит целиком)
    val fightPack = if (meas.forces.combatEnemies.size <= 1 || meas.forces.strikers.isEmpty()) meas.forces.combatEnemies else run {
        // центр массы в стене даёт пустое поле (см. passableNear): все приходы MAX, стая — вся армия, и уклонение в угол
        // на 199-м тике split m28 при его шестёрке в шести клетках
        val flow = flowTo(ctx, passableNear(meas.fight.massCentroid))
        val arrival = meas.forces.combatEnemies.map { e -> e to pathTicks(e, flow, e.key) }.sortedBy { it.second }
        val pack = ArrayList<Creep>()
        val t0 = arrival.first().second
        var limit = t0
        for ((e, t) in arrival) {
            if (pack.isNotEmpty() && t > limit) break
            pack.add(e)
            val kill = fightTicks(pack, meas.forces.strikers)
            limit = if (kill >= Int.MAX_VALUE / 4) Int.MAX_VALUE / 2 else t0 + kill
        }
        pack
    }
    init { fightPackIds = fightPack.mapTo(HashSet()) { it.id } }
    val fightAll = fightPack.size == meas.forces.combatEnemies.size
    val oursFight = if (fightAll) meas.fight.ours else ourPowerOf(ctx.army, fightPack)
    val theirsFight = if (fightAll) meas.fight.theirs else enemyPowerOf(fightPack, ctx.army)
    // стая НАСТУПЛЕНИЯ (v120, USE_PUSH_PACK_AT_HEAD): наступать — идти к его ближайшему, и бой будет у НЕГО, а не у нашей
    // массы: стая — те, кто дойдёт до головы не позже нас плюс время её смерти. Хвост колонны в десяти клетках за головой
    // приходит к ней через десять тиков после нас — он в стае; вторая группа фермера в другом углу — нет. Мера прихода
    // к нашей массе (fightPack) годится для «слабее» (стоим — кто дойдёт до нас), но для толчка она считала голову
    // колонны без хвоста: таблица входов v117 → v120g brawl 4 хуже / 1 лучше (m30 +50: 2536 → 6250 своих хитов, 13 → 9 живых)
    val pushPack = if (meas.forces.combatEnemies.size <= 1 || meas.forces.strikers.isEmpty()) fightPack else run {
        val head = meas.forces.combatEnemies.minByOrNull { e -> meas.fight.massArmy.minOf { getRange(e, it) } } ?: return@run fightPack
        val toHead = flowTo(ctx, head)
        val ourTravel = meas.forces.strikers.map { pathTicks(it, toHead, it.key) }.filter { it < Int.MAX_VALUE / 4 }.maxOrNull() ?: return@run fightPack
        val arrival = meas.forces.combatEnemies.map { e -> e to (if (e.id == head.id) 0 else pathTicks(e, toHead, e.key)) }.sortedBy { it.second }
        val pack = ArrayList<Creep>()
        var limit = ourTravel
        for ((e, t) in arrival) {
            if (pack.isNotEmpty() && t > limit) break
            pack.add(e)
            val kill = fightTicks(pack, meas.forces.strikers)
            limit = if (kill >= Int.MAX_VALUE / 4) Int.MAX_VALUE / 2 else ourTravel + kill
        }
        pack
    }
    val pushAll = pushPack.size == meas.forces.combatEnemies.size
    val oursPush = if (pushAll) meas.fight.ours else ourPowerOf(ctx.army, pushPack)
    val theirsPush = if (pushAll) meas.fight.theirs else enemyPowerOf(pushPack, ctx.army)
}

/** ПОДСТАДИЯ 2: «слабее», пороги наступления, липкий флаг перехвата. */
internal class StrategyThresholds(private val ctx: Ctx, private val meas: ArmyMeasures, private val packs: StrategyPacks) {
    val weaker = packs.theirsFight >= packs.oursFight * (if (posture == Posture.RETREAT) RETREAT_RELEASE_RATIO else RETREAT_RATIO)
    // из идущего боя (см. RETREAT_CONTACT_RATIO) — только при явном проигрыше
    // ⚠️ Здесь жил `weakerContact` — и после снятия вакуумной охраны в `contactFight` он остался
    // объявленным и никем не читаемым. Снят вместе с ней (v217); вместе с ним осиротела и константа
    // RETREAT_CONTACT_RATIO, которая существовала только ради него
    // ДОБИТЬ по перевесу — с гистерезисом; по контакту — пока контакт есть (без гистерезиса: см. PUSH_RELEASE_RATIO)
    private val stalemate = behindTicks >= BEHIND_PATIENCE
    private val holdingFlag =  !meas.chase.fightOn &&
        ctx.flags.any { it.ours && getRange(it.pos, ctx.ourCentroid) <= POST_STANDOFF }
    // (размен матча и размен за окно считаются выше, у историй хитов — перенесены в v221, см. exchangeLive)
    // нулевой ледж (обмена ещё не было) допуск не закрывает — иначе толчок в стоящий лагерь стенда не начинался
    // (v87b: spread m33 24314 → 7298, 14 хуже); закрывает только проигранный размен
    private val ledgerOk =  meas.exchange.exchangeLedger >= 0
    // в проигранной гонке свой флаг порога не поднимает (v99, USE_PUSH_KEEPS_FLAG_UNLESS_LOST)
    private val flagRaises = holdingFlag && !(lostRaceNow(meas.view))
    val pushRatio = if (flagRaises || !ledgerOk) PUSH_RATIO else if (stalemate) PUSH_RATIO_STALEMATE else if (WorldState.behindOnScore) PUSH_RATIO_BEHIND else PUSH_RATIO
    val pushRelease = if (stalemate) PUSH_RELEASE_RATIO_STALEMATE else if (WorldState.behindOnScore) PUSH_RELEASE_RATIO_BEHIND else PUSH_RELEASE_RATIO
    // зачистка: у врага не осталось никого с боем, а мы позади по очкам — аннигиляция единственная победа, и остаток
    // (скауты, обломки) добивается без оглядки на «ловимость» (матч 19: последний M1 с 28 хитами сидел у нашего R3
    // пятьсот тиков, армия ходила за флагами и проиграла по очкам)
    val sweep = meas.forces.combatEnemies.isEmpty() && meas.forces.enemyCreeps.isNotEmpty() && meas.forces.strikers.isNotEmpty() && WorldState.behindOnScore
    // и зачистка уступает простою: она стояла ВНЕ него, поэтому замерший на месте «дожим» не выключался ничем
    // перехват (см. USE_INTERCEPT): ближайший к центру фермера флаг не из его — туда; свой — пост (см. post ниже); считается до наступления — см. chaseVeto, иначе — цель
    val interceptFlag: FlagInfo? = if (!Signals.enemyNotFightingNow || meas.forces.armedEnemies.isEmpty()) null else ctx.enemyCentroid?.let { ec ->
        val group = meas.forces.strikers.ifEmpty { meas.chase.mobileArmy }
        // липкий: выбранный держим, пока он не его
        interceptFlagId?.let { id -> ctx.flags.firstOrNull { it.id == id && !it.theirs } }
            ?: ctx.flagsNotTheirs.sortedBy { getRange(it.pos, ec) }.firstOrNull { f ->
                val flow = flowTo(ctx, f.pos)
                val ours = group.maxOfOrNull { pathTicks(it, flow, it.key) } ?: 0
                ours < Int.MAX_VALUE / 4 && ours + INTERCEPT_MARGIN <= getRange(f.pos, ec)
            }
    }
    init { interceptFlagId = interceptFlag?.id }
}

/** ПОДСТАДИЯ 3: ярлыки фермера, разброс, гонка, отряд за флагами — отзыв и выпуск. Наружу не отдаёт ничего: все записи `Squads.detachedIds` — внутри. */
internal class StrategyDetach(private val ctx: Ctx, private val meas: ArmyMeasures) {
    // перехват снимает наступление, только пока есть ЧТО перехватывать — флаг не наш, за которым фермер придёт; когда все
    // флаги его, «перехват» — пост у своего флага, а за ним он не придёт при 22 очках в тик. Матч 70 (けろびー v5): он сел
    // на D5 двенадцатью при 0,6 мощи (2954 против наших 4179), и армия 1000 тиков дёргалась в 10–13 клетках от него между
    // ДОБИТЬ и постом в 35 клетках позади (noFire ≥ 20 и «в досягаемости броска» мигали на границе 8 клеток, поза
    // переключалась каждые 1–4 тика) — 3174:22934 без единого выстрела с обеих сторон. Позади по очкам против врага,
    // который не дерётся и которому нечего перехватывать, стоять — проиграть наверняка; впереди — стоять верно (v42)
    // отряды (см. USE_DETACH): решение на следующий тик — Ctx строится до мощей
    // фермер: сто тиков (PASSIVE_TICKS) без единого снятого с нас хита с тех пор, как он последний раз был в досягаемости
    // броска, и был в ней не дальше ста тиков назад. Счётчик «рядом и не стреляет» по тикам (noFireTicks) сбрасывался
    // всякий раз, когда блоб отходил дальше NEAR_RANGE, и за 1800 тиков матча 108 не дошёл до ста ни разу (0 detached)
    // погоня без сближения (v57, см. lastDistanceKeptTick) не дальше PASSIVE_TICKS назад — вход; собранный отряд держится,
    // пока его вооружённые есть. Фермер — тот, кто НИ РАЗУ не ударил (lastHurtTick == 0): «сто тиков без удара» пускало
    // отряд против кайтера стенда, который бил на 76–84-м приманкой и вернулся за разделённым ядром — m31 kite 1817:23890,
    // армия стёрта к 400-му (ядро без четырёх стрелков против пяти его стрелков с лекарями: урон мили, которых кайтер не
    // подпускает, в паритете по Ланчестеру считается полностью), m28 kite при наборе из мили — 4970:23907. Живой фермер
    // (матчи 119, 126) не ударил ни разу за 1300–1600 тиков. Первый удар отзывает отряд навсегда
    private val chaseDry = meas.exchange.now - lastDistanceKeptTick <= PASSIVE_TICKS
    private val quiet = lastHurtTick == 0 || meas.exchange.now - lastHurtTick >= FARMER_QUIET   // тишина (v65, см. FARMER_QUIET)
    // второй вход (v68): противник тих FARMER_QUIET с ПЕРВОЙ досягаемости (бросок на сотом тике — не фермер) и гонка проиграна
    // (отстаём с меньшим темпом) — отряд собирается без сухой погони. Матч 161 (шестнадцатый проигрыш фермеру 19771:23843):
    // отряд вышел на 806-м, когда толчок наконец не сближал, и с 900-го мы вели 14 против 11 в тик, а 800 тиков до того
    // были проиграны 3–13 против 12–22 — качели «взяли флаги → слабее → уклонение → потеряли → сильнее → толчок»
    private val quietSinceFirstReach = firstNearTick >= 0 && meas.exchange.now - firstNearTick >= FARMER_QUIET
    val farmerQuietNow = quiet && quietSinceFirstReach
    private val lostRaceNow = WorldState.behindOnScore && WorldState.ourRate <= WorldState.enemyRate
    // сухая охота (v75, см. USE_DRY_HUNT): мы уже стреляли, с тех пор PASSIVE_TICKS ни выстрела, ни удара, отстаём — и его
    // вооружённые РАССЫПАНЫ: крупнейшая группа в ENGAGE_RANGE не больше половины (бегуны матча 183: 2 по одному). «Сто
    // тиков без обмена» само по себе (v75 первая проба) выпускало отряд на сотом тике против целого лагеря (счёт от
    // начала матча, m30 camp 18891:23756) и против кайтера стенда, ходящего впятером (m31 kite 1979:23896 — 25 тиков
    // отряда на 183–214-м, и бой без четверых проигран): гейт 120/125
    private val largestGroup = meas.forces.armedEnemies.maxOfOrNull { e -> meas.forces.armedEnemies.count { getRange(e, it) <= ENGAGE_RANGE } } ?: 0
    private val scatteredRaw = meas.forces.armedEnemies.isNotEmpty() && largestGroup <= maxOf(1, meas.forces.armedEnemies.size / 2)
    // с гистерезисом (v115): включается по «≤ половины», гаснет по сбору «≥ SCATTER_OFF_SHARE»
    private val gathered = meas.forces.armedEnemies.isEmpty() || largestGroup * 4 >= meas.forces.armedEnemies.size * 3   // три четверти (SCATTER_OFF_SHARE)
    init { scatteredLatched = scatteredRaw }
    private val scattered = scatteredLatched
    private val groupSeed = meas.forces.armedEnemies.maxByOrNull { e -> meas.forces.armedEnemies.count { getRange(e, it) <= ENGAGE_RANGE } }
    private val largestMembers = if (groupSeed == null) meas.forces.armedEnemies else meas.forces.armedEnemies.filter { getRange(groupSeed, it) <= ENGAGE_RANGE }
    // дебют-гонка (v91, см. USE_SCATTER_RACE): россыпь его армии до первого обмена
    // «мы ближе» — всей силой, с уже выпущенными бегунами (v120): бегун, выпущенный к свободному флагу, и был тем, кем мы
    // были ближе; без него гонка кончалась, его отзывали, а следующим тиком выпускали снова — 1260 строк detach за
    // матч (split m31), постура мигала с ним ДОБИТЬ↔ДЕРЖАТЬ через тик (наша мощь 4179↔3502)
    private val raceForce = ctx.army + detachedRunners(ctx)
    // цель гонки — и флаг под стаей, которую бьёт ПАРА наших слабейших бегунов (v125, USE_RACE_PAIR_TARGETS): быстрый
    // гастролёр (けろびー#4, серия 327–346) оставляет на каждом взятом флаге одного хранителя, тот уходит от наших в шести и
    // возвращается — «свободных» флагов нет, гонка v91 молчала (targets=0), армия толкала его пятёрку 200 тиков, а его
    // хранители держали шесть флагов (стенд blitz: 4-4 при 1:6 к 200-му)
    private val raceFree = ctx.flags.filter { f -> !f.ours && meas.forces.armedEnemies.none { getRange(it, f.pos) <= ENGAGE_RANGE } &&
        (meas.forces.armedEnemies.minOfOrNull { getRange(it, f.pos) } ?: 999) > (raceForce.minOfOrNull { getRange(it, f.pos) } ?: 999) }
    // флаги под стаей для пары бегунов (USE_RUNNER_PAIRS, USE_RACE_PAIR_TARGETS) отвергнуты — список всегда был пуст, снят в v261
    private val raceTargets = raceFree.size
    private val raceSlots = raceFree.size
    private val raceNow =  (scattered) && meas.forces.armedEnemies.size >= 4 && raceTargets > 0
    init { if (posture == Posture.FLAG || posture == Posture.EVADE || posture == Posture.RETREAT) lastNonHuntTick = meas.exchange.now }
    // рассыпанный остаток — вход v75 как был; блоб-остаток — под стрелковой защитой пула и только после PASSIVE_TICKS
    // охоты без FLAG/EVADE/RETREAT («охота длилась» и для россыпи задерживала отряд: spread m33 24327:24222 → 19509:24315)
    private val dryHunt =  DRY_HUNT.c("behindOnScore", WorldState.behindOnScore) && DRY_HUNT.c("firedOnce", lastFireTick >= 0) &&
        DRY_HUNT.c("noFireLately", meas.exchange.now - lastFireTick >= PASSIVE_TICKS) && DRY_HUNT.c("noHurtLately", meas.exchange.now - lastHurtTick >= PASSIVE_TICKS) &&
        DRY_HUNT.c("scatteredOrHuntingLong", scattered || (meas.exchange.now - lastNonHuntTick >= PASSIVE_TICKS))
    private val theirRangedMass = rangedMass(meas.forces.combatEnemies)
    private val quietChain = QUIET_CHAIN.c("quiet", quiet) && QUIET_CHAIN.c("chaseDryOrDetachedOrLostRace", chaseDry || Squads.detachedIds.isNotEmpty() || (quietSinceFirstReach && lostRaceNow))
    // МИЛИ, КОТОРЫЙ НЕ ДОСТАЁТ, — НЕ АРМИЯ, А ОТРЯД (v194, USE_IDLE_MELEE_RUNS). Отряд набирается только против
    // соперника, помеченного `farmer`, а этот ярлык требует, чтобы он НЕ ДРАЛСЯ: `raceNow` хочет его россыпи,
    // `dryHunt` — тишины по огню и урону. Coldkimchi#2 дерётся и фармит флаги ОДНОВРЕМЕННО — держит плотный
    // блок в контакте (massed=true каждый тик) и отряжает 2–4 крипа за флагами, — и категории для такого у нас
    // нет. Замер по двенадцати играм v191: отряжённых бойцов ноль во ВСЕХ матчах, флаговый забег мы ведём
    // двумя разведчиками по сто хитов, и медиана скорости очков выходит 3 против его 15 к шестисотому тику;
    // пять поражений из двенадцати — это забег, проигранный в первой трети, при целой армии. Основание отряда
    // здесь не ярлык соперника, а НАША измеренная бесполезность: мили, за окно контакта ни разу не
    // дотянувшийся, в бою не участвует, и все пороги выпуска ниже считают его вклад по той же доле (v193)
    private val meleeIdle =  meas.forces.armedEnemies.isNotEmpty() && Prev.touchShare < TOUCH_MIN
    private val farmer = FARMER.c("armedFoes", meas.forces.armedEnemies.isNotEmpty()) && FARMER.c("quietOrDryOrRaceOrMeleeIdle", (firstNearTick >= 0 && (quietChain || dryHunt)) || raceNow || meleeIdle)
    private val viaDryHunt = dryHunt && !quietChain   // отряд держится только сухой охотой (v82b)
    private val viaRace = raceNow && !quietChain && !dryHunt   // отряд держится только дебют-гонкой (v91)
    private val detachedBefore = Squads.detachedIds.size
    // отряд без дела (v84): все отделённые DETACH_WINDOW тиков подряд без цели — отзыв, и набор ждёт то же окно
    init { if (Squads.detachedIds.isNotEmpty() && Squads.detachedIds.all { it in Memory.idleRunnerIds }) idleDetachTicks++ else idleDetachTicks = 0 }
    init {
        if (idleDetachTicks >= DETACH_WINDOW) {
            if (DEBUG_LOG) println("detach t=${meas.exchange.now}: ${Squads.detachedIds.size} recalled — nothing for a runner to take for $DETACH_WINDOW ticks")
            Squads.recallAll(Squads.Source.STRATEGIST); idleDetachTicks = 0; detachRecallTick = meas.exchange.now
        }
    }
    // поштучно (v85): отделённый DETACH_WINDOW тиков подряд без цели возвращается в ядро
    init { for (id in Squads.detachedIds) StrategistState.idleRunnerTicks[id] = if (id in Memory.idleRunnerIds) (StrategistState.idleRunnerTicks[id] ?: 0) + 1 else 0 }
    init { StrategistState.idleRunnerTicks.keys.retainAll { it in Squads.detachedIds } }
    private val idle = Squads.detachedIds.filter { (StrategistState.idleRunnerTicks[it] ?: 0) >= DETACH_WINDOW }
    init {
        if (idle.isNotEmpty()) {
            if (DEBUG_LOG) println("detach t=${meas.exchange.now}: ${idle.size} of ${Squads.detachedIds.size} recalled — without a target for $DETACH_WINDOW ticks")
            Squads.recall(idle.toSet()); idle.forEach { StrategistState.idleRunnerTicks.remove(it) }; detachRecallTick = meas.exchange.now
        }
    }
    // ОДНА ОПОРА (v95b): группа врага и порог одни для выпуска, проверки с дебаффом цели и отзыва — при гонке выпуск
    // мерился против его крупнейшей группы, а отзыв против всей армии при 1,3, и трое выходили и возвращались на
    // следующий тик (m31 camp: 173/174, 255/256). Порог ядра в проигранной гонке НЕ опускается до порога захватов
    // (PARITY_FLOOR_LOST, v88): это исключение про захват стороной, а не про раскол ядра — с 0,75 для ядра пул против
    // пар spread отпускал лишних, и spread из 6/6 стал 2/6 (m28 24322:19608 → 14960:24320); при паритете выпуска с
    // дебаффом цели просто нет, а скауты берут при 0,75 как раньше
    // ...И ОТЗЫВ — ПО ТЕМ, КТО ДОЙДЁТ ДО ЯДРА (v296). Опора отзыва бралась по всей армии, кроме россыпи на выпуске и гонки,
    // и против фермера, распознанного тишиной боя или неподвижным мили, ядро без бегунов сравнивалось с его армией,
    // разбросанной по пяти флагам. Разбор рейтинговой серии v295 (Opus): против けろびー#17/#18 и ricardo18informatica2020#12
    // — все четыре поражения по очкам, 6–9 тыс. против 24 тыс. при целых армиях; из 19 отзывов бегунов 14 сработали по
    // «ядро слабее 0,97/1,3 его армии», хотя в 8 клетках от ядра не было ни одного его крипа. Отзыв теперь меряется мерой
    // боя (`fightPackIds`, кто успевает прийти к нашей массе; та же локализация, что у ворот захвата с v214; пусто — значит
    // пусто, v216). Выпуск остаётся по всей армии: первая редакция перевела на меру боя и его, и у рассыпанного соперника
    // мера прыгала от тика к тику — выпуск и отзыв по кругу (123 строки detach против 19, match28/30:scatter по очкам).
    // Качель рождается, когда выпуск мягче отзыва; здесь наоборот — отряжаем по-прежнему осторожно, зовём назад только
    // при настоящей угрозе ядру
    private val packRef = meas.forces.combatEnemies.filter { it.id in fightPackIds }
    private val coreRef = if (viaRace) largestMembers else meas.forces.combatEnemies
    private val coreFloor = if (viaDryHunt || viaRace) PUSH_RATIO else PARITY_FLOOR
    // при россыпи (v97, USE_SCATTER_RECALL_REF) опора ОТЗЫВА — его крупнейшая группа при PUSH_RATIO; выпуск — как был
    init { if (Squads.detachedIds.isEmpty()) scatteredAtRelease = scattered }   // без отряда ярлык свежий; с отрядом — как при выпуске (v117)
    private val recallGroup = viaRace || ((scatteredAtRelease))
    private val recallRef = if (recallGroup) largestMembers else packRef
    private val recallFloor = if (viaDryHunt || recallGroup) PUSH_RATIO else PARITY_FLOOR
    // одна мера ядра (v94): порог держится каждый тик — просело, сильнейший отделённый возвращается
    init {
        if (farmer && Squads.detachedIds.isNotEmpty()) {
            val floorNow = recallFloor
            var core = notDetached(ctx.army)
            var pulledBack = 0
            val short = core.any { hasWeapon(it) } && ourPowerOf(core, recallRef) < enemyPowerOf(recallRef, core) * meas.fight.theirsDown * floorNow
            coreShortTicks = if (short) coreShortTicks + 1 else 0
            while (Squads.detachedIds.isNotEmpty() && core.any { hasWeapon(it) } && ourPowerOf(core, recallRef) < enemyPowerOf(recallRef, core) * meas.fight.theirsDown * floorNow) {
                // держатель флага (v297, см. HOLD_WATCH) возвращается последним
                val back = detachedRunners(ctx)
                    .maxWithOrNull(compareBy({ heldFlag(ctx, it) == null }, { ourPowerOf(listOf(it), emptyList()) })) ?: break
                Squads.recall(back.id); core = core + back; pulledBack++
            }
            if (pulledBack > 0) { detachRecallTick = meas.exchange.now; coreShortTicks = 0 }   // новый выпуск ждёт DETACH_WINDOW, как после отзыва «без цели» — иначе качели
            if (DEBUG_LOG && pulledBack > 0) println("detach t=${meas.exchange.now}: $pulledBack recalled — the core fell under ${floorNow} of him by the posture's measure")
        }
    }
    init { farmerOffTicks = if (farmer) 0 else farmerOffTicks + 1 }
    // сброс отряда по «не фермер» — только продержавшись FARMER_OFF_TICKS (v115): одноткового моргания признака не хватает
    init {
        if (!farmer && Squads.detachedIds.isNotEmpty()) farmOffHeld.n++
        if (!farmer) { if (farmerOffTicks >= FARMER_OFF_TICKS) {
            if (Squads.detachedIds.isNotEmpty()) farmOffRecall.n++
            Squads.recallAll(Squads.Source.STRATEGIST) } }
        // ...и ОТПУСКАЕТ ЛИ ОТРЯД — решает командир (v164): механика выпуска проверена годом замеров и остаётся, но
        // включает её его режим, а не собственные условия. Полная замена командирской раздачей отвергнута замером:
        // 133 из 135 (roost 7 615:24 325, camp 22 304:23 966) — прежняя логика знает и сухую охоту, и гонку, и
        // охрану стрелков, чего своя раздача не покрывает
        // ...и `meleeIdle` БОЛЬШЕ НЕ СНИМАЕТ ЗАЩИТУ (v215, см. USE_NO_SPLIT_IN_FIGHT). Он стоял оговоркой к
        // обеим половинам гейта — «командир в режиме боя» и «мы в контакте», — и снимал их обе. Смысл был
        // «мили всё равно не дерутся, пусть идут за флагами», а следствие — отряд набирался посреди рубки
        // ровно тогда, когда наши мили не дотягивались, то есть по первой же жалобе оператора
        else if ((!meas.fight.fightOnNow &&
                      ((!meas.fight.contact || (!meas.chase.exchangeRecent)) || meleeIdle)) &&
            (meas.exchange.now - detachRecallTick >= DETACH_WINDOW)) {
            val armed = raceCapable(ctx.army)
    
            val unmanned = ctx.flags.sumOf { f -> if (f.occupant?.my == true) 0 else if (meas.forces.armedEnemies.any { getRange(it, f.pos) <= ENGAGE_RANGE }) 2 else 1 }
            val pool = if ((viaDryHunt || viaRace || meleeIdle)) armed.sortedWith(compareBy({ InfluenceMap.profileOf(it).ranged }, { ourPowerOf(listOf(it), emptyList()) }))
                else armed.sortedBy { ourPowerOf(listOf(it), emptyList()) }
            var remaining = notDetached(ctx.army)
            // держатели (v297) стоят на своих флагах, которые в `unmanned` уже не считаются: выпуск меряется без них
            val holdingDet = ctx.runners.count { it.id in Squads.detachedIds && heldFlag(ctx, it) != null }
            for (c in pool) {
                if (Squads.detachedIds.size - holdingDet >= unmanned) break
                if (viaRace && Squads.detachedIds.size >= raceSlots) break
                val without = remaining.filter { it.id != c.id }
    
                // без тишины (сухая охота) ядро держит охотничий перевес, не паритет
                // его мощь — против ядра-кандидата, не против полной армии (v84: пары Ланчестера)
                val theirsVsCore = (enemyPowerOf(coreRef, without)) * meas.fight.theirsUp
                if (without.none { hasWeapon(it) } || ourPowerOf(without, coreRef) < theirsVsCore * coreFloor) break
    
                if (viaDryHunt && !scattered && rangedMass(without) < theirRangedMass * PUSH_RATIO) break
    
                if (raceNow && InfluenceMap.profileOf(c).ranged > 0.0 && rangedMass(without) < rangedMass(largestMembers) * PUSH_RATIO) break
    
                // компактная крупнейшая группа (см. USE_RANGED_GUARD_COMPACT): та же защита на любой цепочке
                // ...первый срез «крупнейшая группа не меньше COMPACT_MIN» ОТВЕРГНУТ: split 6-2 → 4-4 (m28 24 307:20 109 → 18 643:24 298, m32
                // 24 291:20 558 → 23 651:24 298) — расколотый фермер (6–7 из 13 в группе) обгоняет нас по флагам, когда стрелки не бегут;
                // второй срез: компактная = не меньше двух третей его вооружённых в одной группе (けろびー#4: 11 из 11)
                val target = ctx.flags.filter { !it.ours && it.occupant?.my != true }
                    .minByOrNull { f -> getRange(c, f.pos) + (if (meas.forces.armedEnemies.any { getRange(it, f.pos) <= ENGAGE_RANGE }) 100 else 0) }
                if (target != null) {
                    val (oursAfter, theirsAfter) = powerAfterFor(ctx, without, coreRef, target)
                    if (oursAfter < theirsAfter * coreFloor) break
                }
                Squads.detach(c.id)
                splitAll.n++; if (meas.fight.fightOnNow) splitFight.n++
                remaining = without
            }
        }
    }
    init {
        if (DEBUG_LOG && Squads.detachedIds.size != detachedBefore)
            println("detach t=${meas.exchange.now}: ${Squads.detachedIds.size} detached (was $detachedBefore) farmer=$farmer dryHunt=$dryHunt race=$raceNow targets=$raceTargets(0 paired) largest=$largestGroup/${meas.forces.armedEnemies.size} dry=${meas.exchange.now - lastDistanceKeptTick} hurt=${meas.exchange.now - lastHurtTick} fire=${meas.exchange.now - lastFireTick} reach=${meas.exchange.now - lastReachTick} contact=${meas.fight.contact} theirs=${meas.fight.theirs.toInt()}")
    }
}

/** ПОДСТАДИЯ 4: вето погони, предохранитель CPU, `leadHolds`, решение о наступлении. */
internal class StrategyPush(private val ctx: Ctx, private val meas: ArmyMeasures, private val packs: StrategyPacks, private val thr: StrategyThresholds) {
    private val interceptDenies = thr.interceptFlag != null && !thr.interceptFlag.ours
    // ЗАЧИСТКА ФЛАГОВ ВМЕСТО ПОГОНИ (v124, стенд blitz m28 на v122, 3961:23980): позади по очкам против врага, который не
    // дерётся, армия 200 тиков (42–239) толкала его пятёрку в 647 мощи при своих 3575 — та уходит в шести на той же
    // скорости, — пока его одиночные хранители держали шесть флагов; флаг под одним-двумя его крипами армия берёт, а
    // группу не догоняет. Есть цель-флаг, проходящая гейт стаи, — погоня снята, как при перехвате
    // ...и не по ярлыку «не дерётся» (он гаснет, пока его хранители мелькают в досягаемости), а по сухости самой погони: ни
    // нашего выстрела, ни удара по нам PASSIVE_TICKS подряд (blitz m28: с 83-го по 239-й ни того ни другого при ANNIHILATE)
    // страховка CPU на постуре (v131c): тик уже дороже CPU_GUARD_MS — цель-флаг оценивается только текущая, потоки выхода не
    // пересчитываются; тёплые тики (6–9 мс на стенде) сюда не доходят
    val cpuGuardArmy =  meas.exchange.now > 1 && cpuMs() > CPU_GUARD_MS
    init { if (cpuGuardArmy && DEBUG_LOG) println("cpu t=${meas.exchange.now} guard: posture keeps the objective (${(cpuMs() * 10).toInt() / 10.0}ms)") }
    init { cpuMark("a.sweep") }
    private val chaseVeto = Signals.enemyNotFightingNow && (interceptDenies || !WorldState.behindOnScore)   // USE_SWEEP_OVER_CHASE снят (v214), вместе с ним и слагаемое цели зачистки (v261)
    // ОТКРЫТАЯ НАХОДКА (матч 70): второй источник мигания — «ловимых нет»: блоб, шагнувший назад на две клетки, делает
    // «уходящими» всех двенадцать на восемь тиков (см. evasive), и наступление снимается на эти тики, армия к посту.
    // Два устранения ОТВЕРГНУТЫ стендом: гистерезис по ловимости (наступление снимается лишь после целого CHASE_WINDOW без
    // ловимых) — 51 хуже / 51 лучше, гейтовые m28 farm+weak и m30 camp красные, кайтеры добиваются позже на десятке карт,
    // m16 kite проигран; порог evasive «больше половины окна» — 66 хуже / 44 лучше. Быстрое снятие наступления, когда
    // ловимых нет, — то, чем армия не гонится за кайтером; цена — эти тики против блоба, который отступает и возвращается
    // отрыв с темпом (v127, USE_LEAD_HOLDS): впереди по счёту и по проекции — толчка и прижима нет, бой только его
    val leadHolds =  !WorldState.behindOnScore && ourScore > enemyScore && meas.forces.armedEnemies.isNotEmpty()
    init { if (DEBUG_LOG && leadHolds != leadHoldsWas) println("lead t=${meas.exchange.now}: holds ${if (leadHolds) "on" else "off"} score=$ourScore:$enemyScore rate=${WorldState.ourRate}:${WorldState.enemyRate} push=${packs.oursPush.toInt()}:${packs.theirsPush.toInt()}") }
    init { leadHoldsWas = leadHolds }
    // ...и В РЕЖИМЕ ПАР АРМИЯ НЕ ГОНИТСЯ (v304): он уходит от групп (646 шагов прочь против 67 навстречу), догнать его
    // нельзя, а наступление держит постуру ДОБИТЬ, и та снимает флаг-цель — 179 тиков из 322 «без цели» несут именно его
    private val pushRaw = PUSH_RAW.c("notStalled", !meas.chase.stalled) && PUSH_RAW.c("leadDoesNotHold", !leadHolds) && PUSH_RAW.c("notPairsMode", !Signals.groupSafe) && (PUSH_RAW.c("sweep", thr.sweep) || (PUSH_RAW.c("exchangePaying", meas.exchange.exchangePaying) && PUSH_RAW.c("noChaseVeto", !chaseVeto) && PUSH_RAW.c("huntable", meas.chase.huntable.isNotEmpty()) && PUSH_RAW.c("strikers", meas.forces.strikers.isNotEmpty()) && PUSH_RAW.c("pushPower", packs.oursPush >= packs.theirsPush * (if (pushing) thr.pushRelease else thr.pushRatio))))
    // ...и СРОК (v215, см. USE_PUSH_DWELL): начатое наступление живёт минимум PUSH_DWELL тиков, и снимают его
    // досрочно только затор и настоящая слабость — мощь ниже порога отпускания. Мигание любого из пяти прочих
    // множителей за этот срок армию не разворачивает.
    // ...ТОЛЬКО В ИДУЩЕМ БОЮ, и это не осторожность, а замер: без оговорки строка match28:scatter перестала
    // проходить гейт, а отрыв упал на 40 509. Причина по существу записана соседним комментарием: быстрое
    // снятие наступления, когда ловимых нет, — это ровно то, чем армия не гонится за кайтером и за рассыпавшимся
    // фермером. Разворот, на который жалуется оператор, случается В БОЮ; вне боя мигание `huntable` полезно.
    // ⚠️ Мерились три редакции, и средняя выбрана не по вкусу, а по числам:
    //   без оговорки вовсе — строка match28:scatter НЕ ПРОХОДИТ гейт, отрыв −40 509;
    //   `fightOnNow` (эта) — FAIL нет, отрыв −3 545, 90 строк не тронуты, срок срабатывает на 0,6 % тиков,
    //      смен постуры 5,12 -> 4,74 на сто тиков, и все пять «потерянных» уничтожений стали крупными
    //      победами по очкам (match31:camp: уничтожение при ПРОИГРЫШЕ 4 391:6 400 -> отрыв 23 245:18 056);
    //   `meleeAdjacent` (его мили вплотную) — стенд ровный (134 строки без изменений), но срок срабатывает
    //      ОДИН раз на 125 110 тиков. Это не осторожная редакция, а мёртвый код, и потому отвергнута.
    // Живое основание сильнее стендового: из 1 189 быстрых смен постуры в рейтинговой серии 852 несут смену
    // `pushing`, а стенд в режим боя почти не входит (cmdwhy fight:8 из 570 тиков). Судит живая серия
    // ЕГО ОСТАТОК БЕЗ МИЛИ НЕ ЗАБИРАЕТ НАСТУПЛЕНИЯ (v300). Разбор 18 реплеев блобов MetalicaX (Opus): в контакте всё решает,
    // уступаем ли мы землю. Тики, где центр армии идёт назад быстрее 0,15 клетки, дают размен 0,46 против #15 и 0,68 против
    // #9-типа; стоим — 0,93/1,19; идём вперёд — 1,50/0,90. В пяти поражениях от #15 из пяти окно размена переворачивается на
    // +27…+33 тике боя (в победах не раньше +51 или никогда, p ≈ 0,008), наступление снимается, командир уходит в FIGHT
    // (258 тиков в поражениях против 22 в победах), и там пятятся все роли по 0,2 клетки в тик при его 0,27 вперёд, размен
    // 0,34. При этом все четыре его мили к тому времени уже разоружены и мертвы (10 матчей из 10), то есть отдаём мы
    // наступление ровно тогда, когда его остаток бьёт только с трёх клеток. Условие узкое: けろびー#19 — 0 тиков за
    // 16 матчей, MetalicaX#3 — 1 тик, Coldkimchi#2 — 11 тиков за 8 матчей
    private val nearFoes = meas.forces.armedEnemies.filter { e -> ctx.army.any { getRange(e, it) <= RANGED_RANGE + 1 } }
    private val toothless = nearFoes.isNotEmpty() && nearFoes.none { InfluenceMap.profileOf(it).melee > 0.0 } &&
        ctx.army.count { hasWeapon(it) } >= nearFoes.size &&
        ctx.army.any { InfluenceMap.damageAt(it.x, it.y, meas.forces.combatEnemies) > 0.0 }
    init { pushHeld = false }
    // ...и РАЗМЕН НИЖЕ ПАРИТЕТА ГАСИТ НАСТУПЛЕНИЕ (v217, см. USE_BREAK_OFF_HOLDS_LINE), а не разворачивает
    // армию: толчка вперёд нет, строй и лекари остаются
    // ...и БЕЗ СВОЕГО pushTicks++ (v218, дефект прибора): счётчик увеличивается безусловно десятью строками
    // ниже, поэтому на тиках размена он рос ДВАЖДЫ — и `pushheld` занижался ровно на тех тиках, ради которых
    // прибор и ставился
    private val pushCase = PushCase(meas, packs, thr, pushRaw, toothless)
    init { pushing = walk(pushRules(), pushCase, pushTally).act(pushCase) }
    init { pushTicks.n++ }
}

/** ПОДСТАДИЯ 5: его неподвижность, тёплый контакт, местная точка, `annihilate`. */
internal class StrategyContact(private val ctx: Ctx, private val meas: ArmyMeasures, private val packs: StrategyPacks) {
    // бой по контакту — пока отход невозможен: мили врага вплотную. Решение ТИК ЗА ТИКОМ, и это не дрожание, а
    // кайт погони: слабее — отходим, стреляя и рубя на ходу (strike/shoot идут в любой постуре); догнал мили —
    // вся армия разворачивается на него (авангард погони один против всех), отстал — снова отход. На стенде
    // sleeper при 0.83 это 10:0; «поймали — деремся до конца контакта» дало 2:11, «слабее — только отход» 2:6
    // ...И КОНТАКТ БЕЗ РАЗМЕНА ХИТОВ С ИДУЩИМ ВРАГОМ — НЕ БОЙ (v221, см. USE_FIGHT_BY_LEDGER): стояние в трёх–пяти
    // клетках от его тычущей линии, за окно которого ни одна сторона не потеряла STALL_DAMAGE, держало армию без
    // флаг-цели 35 % проигранных забегов. Бой, как и прежде: его мили вплотную, загнанная неподвижная группа
    // (cornered) и — см. USE_WARM_NEEDS_HIS_MOVE — любой СТОЯЩИЙ враг в контакте: стоящего атакуем мы
    // `warmNow` считается здесь один раз и читается постурой, линией и приборами — одна величина на всех
    val hisStill = Memory.hisCentHist.size >= 2 && run {
        val a = Memory.hisCentHist.first(); val b = Memory.hisCentHist.last()
        maxOf(abs(a / 100 - b / 100), abs(a % 100 - b % 100)) < APPROACH_WINDOW / 4
    }
    val warmNow = meas.fight.contact && !meas.exchange.exchangeLive && !meas.chase.meleeAdjacent && !meas.chase.cornered 
    private val hotContact = meas.fight.contact 
    // ⚠️ Здесь стояла охрана `&& !(retreatFeasible && weakerContact)`, и она ВАКУУМНО ИСТИННА всегда, когда
    // остальная конъюнкция может быть истинной. Доказательство в две строки: `hotContact` требует `contact`,
    // сама конъюнкция требует `strikers.isNotEmpty()`, а `retreatFeasible = (!contact || strikers.isEmpty())
    // && !atRetreatPoint` — при обоих этих условиях он ЛОЖЕН по построению. Значит `weakerContact` —
    // тщательно настроенное тройное отношение (1.5 в ANNIHILATE, 1.05 в RETREAT, иначе 1.15) — не читался в
    // контакте НИ РАЗУ, то есть ровно там, где задумывался. Снято в v217; дифф отчёта пуст побайтово.
    // ⚠️ И оживлять его НЕ НАДО, это отдельное решение с двумя основаниями. Первое: живая редакция сделала бы
    // `contactFight` ложным при слабости в контакте, а с ним погасли бы `annihilate` и постура ANNIHILATE —
    // то есть отменилось бы решение оператора «держать линию» (см. USE_BREAK_OFF_HOLDS_LINE), принятое
    // строкой выше по той же причине. Второе: это и есть «слабее — только отход», замеренное 2:6 против 10:0.
    // Намерение охраны теперь исполняет гашение наступления, и исполняет его, не разворачивая армию
    private val contactFight = CONTACT_FIGHT.c("notStalled", !meas.chase.stalled) && CONTACT_FIGHT.c("armedFoes", meas.forces.armedEnemies.isNotEmpty()) && CONTACT_FIGHT.c("strikers", meas.forces.strikers.isNotEmpty()) && CONTACT_FIGHT.c("hotContact", hotContact)
    // СПЕРВА ТУШИМ МЕСТНЫЙ ОЧАГ (v214, оператор по записи: «загнали в угол 2-3 крипа, мы там значительно
    // сильнее, но армия разворачивается и убегает в другой конец карты; нужен механизм, который сперва тушит
    // местный очаг, если мы сильнее, и лишь затем бежит на помощь»).
    // Причина ухода: гаснет pushing — от leadHolds (мы ВПЕРЕДИ по счёту, значит толчок запрещён), от простоя,
    // от пустого huntable, от отношения мощи, — и строкой ниже флаг-цель выбирается ПО ВСЕЙ КАРТЕ. Вето поверх
    // результата ловит все четыре причины разом, а не каждую по отдельности.
    // Три сомножителя, все уже посчитаны: бой идёт у МАССЫ армии; среди его крипов в контакте есть тот, по
    // которому наш залп в его клетке сильнее его залпа в PUSH_RATIO раз и которого мы добиваем; и стая,
    // УСПЕВАЮЩАЯ прийти (fightPack), слабее нас с запасом наступления — то есть «выигрываем» здесь значит
    // выигрываем по Ланчестеру с лечением, а не «бьём сильнее».
    private val spotFoes = meas.forces.armedEnemies.filter { e ->
        ctx.army.any { getRange(e, it) <= RANGED_RANGE + 1 } && spotEdgeAt(e) >= PUSH_RATIO }
    // ⚠️ добиваемость (killTicks) здесь НЕ проверяется намеренно: она объявлена ниже постуры. Её роль тут
    // исполняет третий сомножитель — oursFight >= theirsFight * PUSH_RATIO считает лечение по Ланчестеру,
    // то есть очаг у трёх его лекарей этот порог не берёт. В цепочке целей (см. spotNow) killTicks на месте
    val holdingSpot =  meas.fight.contact && spotFoes.isNotEmpty() && packs.oursFight >= packs.theirsFight * PUSH_RATIO
    init {
        if (holdingSpot) {
            spotHoldAll.n++
            if (!(pushing || contactFight)) spotHoldNew.n++     // пара: сколько раз вето ИЗМЕНИЛО постуру
        }
    }
    // ...и в режиме выживания бой не объявляется, пока есть куда уходить (v223, см. USE_SURVIVAL). Точка уклонения
    // считается здесь, до постуры: вторая редакция — нет точки и он в контакте, значит бой строем, а не стояние в
    // отходе (первая редакция парковала армию у точки отхода, и он добивал её там, стоящую: 0-8 против Coldkimchi#1)
    // поля выхода — этим тиком, а не прошлым: на первом тике режима их ещё нет, и уходить было бы «некуда»
    val annihilate = (ANNIHILATE.c("pushing", pushing) || ANNIHILATE.c("contactFight", contactFight) || ANNIHILATE.c("holdingSpot", holdingSpot)) 
    // ПРИБОРЫ ТЁПЛОГО КОНТАКТА (v221, пары к USE_FIGHT_BY_LEDGER, `warmNow` — см. hotContact): ровно то, что правка
    // называет «не боем». `warm` — доля такого контакта во всём контакте; `warmann` — тики, где боевую постуру
    // держал только он (не толчок и не очаг; с правкой — ноль по построению); `warmhold` — из них тики, где
    // флаг-цель подхватила бы «держим линию»
    init { if (meas.fight.contact) { warmContact.n++; if (warmNow) warmTicks.n++ } }
    init {
        if (annihilate) {
            warmAnnAll.n++
            if (warmNow && !pushing && !holdingSpot) { warmAnn.n++; if (meas.fight.enemyNear && !meas.chase.stalled) warmHold.n++ }
        }
    }
}

/** ПОДСТАДИЯ 6: преследуемость, истории, поля бегства, раннее уклонение, цель-флаг, точка уклонения. НЕ РЕЗАТЬ внутри: порядок «темп сближения -> поля бегства -> цель-флаг -> точка уклонения» — поведение, три канала идут через члены внутри вызываемых. */
internal class StrategyObjective(private val ctx: Ctx, private val meas: ArmyMeasures, private val packs: StrategyPacks, private val thr: StrategyThresholds, private val detach: StrategyDetach, private val push: StrategyPush, private val contact: StrategyContact) {
    // непобедимая армия (см. EVADE_SAFE): с ней не деремся — флаг-цель только с выходом, иначе уклонение на любой
    // дистанции: держимся там, откуда есть выход, и уходим, когда она подходит
    // уклонение — только от ЯВНО сильнейшей армии (см. RETREAT_RATIO): при паритете флагов (v14) бой равный, и бежать
    // от него на равной скорости — быть пойманным с растянутым хвостом (матчи 11–12); прежнее «не сильнее для
    // добивания» уводило и от равной армии. Поля выхода нужны и бегунам без армии (см. runnerEscape)
    // от безфлагового броска (см. EVADE_EQUAL_RATIO) уклоняемся уже при равной силе
    // ПРЕСЛЕДУЕМЫ ТОЛЬКО ТЕМ, КТО МОЖЕТ ДОЙТИ (v129, USE_HUNTED_NEEDS_REACH): его вооружённый центроид дальше HUNTED_FAR и темп
    // сближения ниже APPROACH_RUSH — не hunted, и цель-флаг не снимается ради уклонения. Матч 407 (MetalicaX#4, тур): с 1269-го
    // FLAG на один тик (его H4 в (8,90), travel 43) и EVADE следующим — по одной силе 3559/3044 = 1,17 при его армии в 47 клетках
    // и approach=0; 530 тиков на R3, два бегуна без дела, отставание 1750–3250 досягаемо, проигрыш у предела. Повернёт к нам
    // из-за HUNTED_FAR — окно темпа увидит это, пока он ещё в EVADE_RANGE: тот запас, на который уклонение и построено
    // ...и только при ПРОИГРАННОЙ гонке (второй срез, lostRaceNow: проекция на конец матча проиграна, PASSIVE_TICKS без удара по нам):
    // без этого армия переставала уклоняться от далёкого фермера двух групп, которого обыгрывала по очкам, и шла за флагами
    // в его группы — split без одного мили 7-1 → 3-5 при tour 5-3 → 6-2; «далеко» — его БЛИЖАЙШИЙ вооружённый (третий срез):
    // центроид фермера двух групп лежит между ними, далеко от нас при группе рядом
    private val hunted = HUNTED.c("armedFoes", meas.forces.armedEnemies.isNotEmpty()) && HUNTED.c("strikers", meas.forces.strikers.isNotEmpty()) &&
        HUNTED.c("theyAreStronger", packs.theirsFight >= packs.oursFight * RETREAT_RATIO || (Signals.unflaggedRushNow && packs.theirsFight >= packs.oursFight * EVADE_EQUAL_RATIO))
    private val escapeNeeded = hunted || (meas.forces.armedEnemies.isNotEmpty() && meas.forces.strikers.isEmpty())
    /** Темп сближения его армии с нашей за окно (0 — стоит, 1 — идёт на нас): считает блок ниже, каждый тик; вчерашний — `Prev.approachRate`. */
    var approachRate = 0.0
    // темп сближения врага (0 — стоит, 1 — идёт на нас): запас выхода даёт ему фору только в этом темпе — фора «идёт
    // к выходу мгновенно» отвергала всякую цель при враге, стоящем дома, и армия весь матч сидела дома (стенд m1 scouts)
    init {
        run {
            val ec = centroidOf(meas.forces.armedEnemies)
            if (ec != null) { Memory.enemyDistHist.addLast(getRange(ec, ctx.ourCentroid)); while (Memory.enemyDistHist.size > APPROACH_WINDOW) Memory.enemyDistHist.removeFirst() } else Memory.enemyDistHist.clear()
            val ac = centroidOf(meas.forces.armedEnemies)
            if (ac != null) { Memory.hisCentHist.addLast(ac.key); while (Memory.hisCentHist.size > APPROACH_WINDOW) Memory.hisCentHist.removeFirst() } else Memory.hisCentHist.clear()
            if (ac != null) { Memory.ourCentHist.addLast(ctx.ourCentroid.key); while (Memory.ourCentHist.size > APPROACH_WINDOW) Memory.ourCentHist.removeFirst() } else Memory.ourCentHist.clear()
            approachRate = if (Memory.enemyDistHist.size >= 2) ((Memory.enemyDistHist.first() - Memory.enemyDistHist.last()).toDouble() / (Memory.enemyDistHist.size - 1)).coerceIn(0.0, 1.0) else 0.0
        }
    }
    init { if (escapeNeeded && !(push.cpuGuardArmy && StrategistState.escapeFlows.isNotEmpty())) refreshEscape(ctx, meas.forces.armedEnemies) else if (!escapeNeeded) { StrategistState.escapeFlows.clear(); StrategistState.escapeTheirs.clear(); StrategistState.escapeNearest.clear(); evadeLeft = null } }
    init { cpuMark("a.escape") }
    // враг близко (см. EVADE_RANGE) — уклонение раньше целей; далеко — цели с выходом, иначе безопасная точка
    private val enemyClose = (hunted) && meas.forces.armedEnemies.any { getRange(it, ctx.ourCentroid) <= EVADE_RANGE }
    private val evadeFirst = if (enemyClose && !contact.annihilate && !meas.fight.contact) evadePoint(ctx, meas.forces.armedEnemies, meas.forces.strikers, approachRate) else null
    // враг рядом (см. NEAR_RANGE) без нашего перевеса — не цель, а строй: армия, пошедшая за угловым флагом при
    // подходящем враге, была поймана колонной на марше (стенд m3 sleeper, t=529–540); флаги в это время — скаутам
    // ...и тёплый контакт линии не стоит (v221, см. USE_FIGHT_BY_LEDGER): иначе, погасив боевую постуру, правка
    // отдала бы флаг-цель этой же строке — и армия осталась бы у поста, как в v90
    // ...и НЕ ПРОТИВ ТОГО, КТО НЕ БЬЁТ НАШИХ В ГРУППЕ (v303, см. GROUP_SAFE_DMG): ярлык `farmerQuietNow` требует полной
    // тишины и гаснет от одного подстреленного скаута, а けろびー стреляет по одиночкам весь матч — линия против него
    // стоила армии флаг-цели 584 тика из 1400 (ещё 496 снимало «добить»), и матч кончался 10 тыс. против 23 тыс.
    private val holdLine = HOLD_LINE.c("enemyNear", meas.fight.enemyNear) && HOLD_LINE.c("notPushing", !pushing) && HOLD_LINE.c("notAnnihilate", !contact.annihilate) && HOLD_LINE.c("notStalled", !meas.chase.stalled) && HOLD_LINE.c("notFarmerNorPairs", !(detach.farmerQuietNow || Signals.groupSafe))
    private val interceptObjective: Objective? = thr.interceptFlag?.takeIf { !it.ours && captureAllowed(ctx, it, meas.view, CapAsker.ARMY) }?.let { f ->
        val group = meas.forces.strikers.ifEmpty { meas.chase.mobileArmy }
        val flow = flowTo(ctx, f.pos)
        Objective(f, emptyList(), 1.0, group.maxOfOrNull { pathTicks(it, flow, it.key) } ?: 0)
    }
    // ЦЕЛЬ-ФЛАГ ТРЕБУЕТ ВЫХОДА ВСЯКИЙ РАЗ, КОГДА ЕСТЬ АРМИЯ, КОТОРУЮ МЫ НЕ МОЖЕМ ТОЛКНУТЬ (v462, замысел v12): седьмой параметр —
    // `escapeNeeded`, а не `hunted`. `hunted` требует живых ударников, поэтому при его вооружённых и пустых strikers проверка
    // запаса выхода (exitMargin < ESCAPE_MARGIN) не применялась вовсе, и армия из лекарей и раздетых могла выбрать флаг без
    // выхода — при том что поля бегства строились по escapeNeeded (init выше). Осознанной смены аргумента в истории нет
    private val objectiveAsked = !(contact.annihilate || evadeFirst != null || (holdLine && interceptObjective == null)) && interceptObjective == null
    val objective = if (contact.annihilate || evadeFirst != null || (holdLine && interceptObjective == null)) null else interceptObjective ?: chooseFlagObjective(ctx, meas.view, approachRate, detach.farmerQuietNow, meas.forces.strikers.ifEmpty { meas.chase.mobileArmy }, thr.pushRatio, escapeNeeded, if (push.cpuGuardArmy) objectiveFlagId else null)
    // прибор `objns=спрошено/пусто` (v462): тики, когда выбор флаг-цели спрошен в состоянии «его вооружённые живы, наших ударников
    // нет» (escapeNeeded без hunted — ровно там, где правка действует), и из них — когда он вернул пусто. Гейт этого состояния не
    // знает (0 тиков в 140 сценариях, whynot hunted=strikers), живьём в серии v461 — 137 тиков в четырёх матчах
    init { if (objectiveAsked && escapeNeeded && !hunted) { objNs.n++; if (objective == null) objNsNull.n++ } }
    // ПОЧЕМУ У АРМИИ НЕТ ФЛАГ-ЦЕЛИ (v216). Постура HOLD занимает 43–58 % матча, и в ней армия стоит в точке,
    // которая не даёт очков, при 2,3–2,6 ничьих флагах на доске. Причин ровно четыре, и прежде чем менять
    // поведение, надо знать, которая из них держит: «пост на флаге» уже мерили дважды (v214: любой не его флаг
    // −23 570, суженный до разрешённых гейтом −6 905) и оба раза отвергли, поэтому третий заход вслепую
    // недопустим
    init {
        if (objective == null) {
            val why = when {
                contact.annihilate -> "annihilate"
                evadeFirst != null -> "evade"
                holdLine -> "holdLine"
                else -> "gate"
            }
            objNone.bump(why)
        }
    }
    init { objAll.n++ }
    init { cpuMark("a.obj") }
    // дебют без угла (v100, USE_OPENING_AT_POST): бросок далеко — не уклонение, а пост. И «далеко» значит ДАЛЕКО
    // (v135, см. USE_RUSH_FAR_NEEDS_RANGE): условие правила было только про силу, поэтому при паритете оно гасило
    // уклонение и вплотную — в разгромах от блоба `rush=true` с 10-го тика, HOLD на посту до 39-го, контакт, и наша
    // мощь ноль к 81-му. Пока его вооружённый центроид дальше EVADE_RANGE, бросок — повод занять пост; ближе — повод
    // уклоняться, как и говорит доктрина (EVADE_EQUAL_RATIO: от сомкнутой безфлаговой армии не слабее нас уклоняемся)
    private val rushFar =  Signals.unflaggedRushNow && packs.theirsFight < packs.oursFight * RETREAT_RATIO 
    // уклонение от БЛИЗКОГО сомкнутого броска не ждёт `hunted` и не мигает (v135, см. USE_EVADE_STICKY_RUSH): в первой
    // пробе EVADE появлялась на 57-м и отпускалась на 62-м, армия дёргалась и всё равно попадала в контакт
    // ⚠️ Здесь стоял `rushEvade`, тождественно ложный: `USE_EVADE_STICKY_RUSH` выключен своим замером
    // (1-5 и 1-5, ровно база), а операнд `hunted || rushEvade` из-за этого сводится к `hunted`. Снято в
    // v217; дифф отчёта пуст побайтово, как и обязан быть у мёртвого
    val evadeTo = evadeFirst ?: (if ((hunted) && !rushFar && !contact.annihilate && (!meas.fight.contact) && objective == null) evadePoint(ctx, meas.forces.armedEnemies, meas.forces.strikers, approachRate) else null)
    init { cpuMark("a.evade") }
}

/** ПОДСТАДИЯ 7: приборы прогноза и ВХОДЫ решения. Предмет таблиц `Strategist.decide` — эта подстадия: строка правила читает её поле,
 *  поэтому новый вход — поле здесь и строка правила. Поля входов стоят в порядке прежнего списка параметров (порядок вычисления тот же);
 *  `pushing` решение читает само — это величина верха пакета, записанная подстадией 4 в этом же тике. */
internal class StrategyInputs(private val ctx: Ctx, private val meas: ArmyMeasures, private val thr: StrategyThresholds, private val contact: StrategyContact, private val obj: StrategyObjective) {
    // ПРИБОР ДЕЛЬТЫ ПРОГНОЗА (v397). Решение «драться ли с кулаком» стоит на СТАТИЧЕСКОЙ мере мощи, потому что
    // прогноз `simulate` возвращает АБСОЛЮТНУЮ разность мощей (наша минус его) — при живой армии она положительна
    // всегда, и потому «не уходит в минус ни разу». Но величина, отвечающая на вопрос «выигрываем ли мы размен», —
    // не абсолют, а ДЕЛЬТА: насколько прокат на SIM_TICKS изменит эту разность. Дельта уже считается рядом (v166,
    // simPending: expected = predicted − was), но только для прибора ошибки, а в решение не входит. Здесь она
    // меряется на тех тиках, где решение и принимается: враг сомкнут и его вооружённый центроид близко. Поведение
    // НЕ меняется — прежде чем менять решение, надо знать, различает ли дельта исход (три пробы прогноза до этого
    // отвергнуты живьём именно потому, что их ставили в решение, не измерив)
    private val simdFoeCentroid = if (meas.forces.armedEnemies.isEmpty()) null else centroidOf(meas.forces.armedEnemies)
    /** Дельта прогноза за прокат на SIM_TICKS — на тиках, где решение принимается (враг сомкнут, его центроид близко); иначе null. */
    private val simdDelta: Double? =
        if (Signals.enemyMassedSignal && simdFoeCentroid != null && ctx.army.isNotEmpty() &&
            getRange(ctx.ourCentroid, simdFoeCentroid) <= ENGAGE_RANGE + RANGED_RANGE) {
            val base = Forecast.simulate(ctx.army, meas.forces.armedEnemies, emptyMap(), 0, null, null)
            val next = Forecast.simulate(ctx.army, meas.forces.armedEnemies, emptyMap(), Forecast.SIM_TICKS, null, null)
            next - base
        } else null
    // ВХОД РЕШЕНИЯ, А НЕ ПРИБОР (с v398): сглаженную долю тиков с положительной дельтой читает `enemyMassed` ниже. Запись стояла
    // внутри блока прибора `simd=` (находка 2.8 п. 6 плана второго шага архитектуры) — разведена с ним, значение и место в тике те же
    init { if (simdDelta != null) Memory.simdShare = (1.0 - SIMD_SMOOTH) * Memory.simdShare + SIMD_SMOOTH * (if (simdDelta > 0.0) 1.0 else 0.0) }
    // ...а это прибор `simd=`
    init {
        if (simdDelta != null) {
            simdSum += simdDelta; simdTicks++
            if (simdDelta > 0.0) simdPos++
            // расходится ли знак прогноза с действующим решением по мощи: «мощь говорит не драться, прогноз — драться»
            val powerSaysNo = ourPowerOf(ctx.army, ctx.combatEnemies) < enemyPowerOf(ctx.combatEnemies, ctx.army) * FIGHT_POWER_ROOM
            if (powerSaysNo != (simdDelta <= 0.0)) simdDisagree++
        }
    }
    // прибор разлёта (v409): радиус боевой части армии на тиках, где по нам стреляют
    init {
        if (meas.fight.contact && ctx.army.size >= 2) {
            val live = ctx.army.filter { it.hits > 0 }
            if (live.size >= 2) {
                val cx = live.sumOf { it.x } / live.size
                val cy = live.sumOf { it.y } / live.size
                val r = live.maxOf { maxOf(abs(it.x - cx), abs(it.y - cy)) }
                radSum += r.toDouble(); radTicks++
                if (r > radMax) radMax = r
                if (r > 5) radWide++
            }
        }
    }
    val evade = EVADE.c("evadePoint", obj.evadeTo != null)
    init { if (!evade) evadeTarget = null }
    // ...и в выживании отход к ТОЧКЕ не берётся: стоящую у точки армию он добивает (v223, вторая редакция)
    val retreat = RETREAT.c("armedFoes", meas.forces.armedEnemies.isNotEmpty()) && RETREAT.c("notAnnihilate", !contact.annihilate) && RETREAT.c("noObjective", obj.objective == null) && RETREAT.c("notEvade", !evade) && RETREAT.c("enemyNear", meas.fight.enemyNear) && RETREAT.c("weaker", thr.weaker) && RETREAT.c("feasible", meas.fight.retreatFeasible) 
    // ---- меры, которые читает решение о режиме командира (подняты сюда в v241: одно решение — одни входы) ----
    // боеспособные: вооружённые и лекари — фокус, контакт и местные группы считаются по ним, раненые не в счёт
    val combatArmy = ctx.army.filter { combatant(it) }
    // «их мили в бою» — только ВПЛОТНУЮ к нашему вооружённому: в 2–3 клетках это экран, не атака. Матч 43 (けろびー v2):
    // его мили сорок тиков стояли в двух-трёх от наших и не били (вплотную 7 % крип-тиков, 8 ударов за бой), его стрелки в
    // трёх снимали наших мили по одному (r→melee 132 из 180), а прижим считал «мили в трёх» атакой и молчал до 104-го
    val theirMeleeIn = meas.forces.combatEnemies.any { e -> InfluenceMap.profileOf(e).melee > 0.0 && combatArmy.any { hasWeapon(it) && getRange(e, it) <= 1 } }
    val underTheirFire = combatArmy.any { InfluenceMap.damageAt(it.x, it.y, meas.forces.combatEnemies) > 0.0 }
    private val retreatByDistance = Memory.enemyDistHist.size >= 2 && Memory.enemyDistHist.last() > Memory.enemyDistHist.first()
    // ...и по ЕГО шагу (v222, см. USE_RETREAT_BY_HIS_STEP): его центр сейчас против его центра в начале окна, оба — от
    // нашего центра в начале окна
    private val retreatByHisStep = Memory.hisCentHist.size >= 2 && Memory.ourCentHist.size >= 2 && run {
        val o = Memory.ourCentHist.first(); val a = Memory.hisCentHist.first(); val b = Memory.hisCentHist.last()
        val op = InfluenceMap.cell(o / 100, o % 100)
        getRange(InfluenceMap.cell(b / 100, b % 100), op) > getRange(InfluenceMap.cell(a / 100, a % 100), op)
    }
    init { if (retreatByDistance) { rtrOld.n++; if (!retreatByHisStep) rtrRemoved.n++ } else if (retreatByHisStep) rtrAdded.n++ }
    // ...и снимается только ЛОЖНЫЙ ярлык (v222, вторая редакция): «отходит», если расстояние выросло И сдвинулся он.
    // Первая редакция (одно «его шаг») ещё и ДОБАВЛЯЛА ярлык там, где мы теснили его быстрее, чем он пятился, — и
    // командир уходил из FIGHT посреди выигрываемой погони: доля FIGHT в тиках размена 18 % -> 11 % против
    // MetalicaX#4 и 68 % -> 45 % против Coldkimchi#1, разница очков +1208 -> +232 и +3290 -> −3538
    val enemyRetreating = ENEMY_RETREATING.c("byDistance", retreatByDistance) && ENEMY_RETREATING.c("byHisStep", (retreatByHisStep))
    // сколько его вооружённых стоит у нашей армии: группа — дело командира, одиночка — нет
    private val foesAtHand = meas.forces.armedEnemies.count { e -> meas.fight.massArmy.any { getRange(e, it) <= RANGED_RANGE + 1 } }

    // ---- входы решения: каждое поле читает строка таблицы `Strategist.decide` ----
    val annihilate = contact.annihilate
    val hasObjective = obj.objective != null
    /** нет контакта и ни одного его вооружённого в MARCH_SAFE от массы */
    val marchNow = !meas.fight.contact && meas.forces.armedEnemies.none { e -> meas.fight.massArmy.any { getRange(e, it) <= MARCH_SAFE } }
    val stalled = meas.chase.stalled
    /** он отходит, и это не рубка, в которой мы стоим: enemyRetreating && !(underTheirFire && theirMeleeIn) */
    val hisRetreat = enemyRetreating && !(underTheirFire && theirMeleeIn)
    val outmatched = outmatchedTicks >= BREAK_OFF_TICKS
    /** ни сомкнутой армии, ни COMMAND_MIN_FOES у руки */
    val fewFoes = !(meas.forces.enemyMassedNow || foesAtHand >= COMMAND_MIN_FOES)
    /** его вооружённые сомкнуты в кулак И мы уже позади по суммарным хитам (v352, см. fightNow) */
    // ...И «ПОЗАДИ» МЕРЯЕТСЯ МОЩЬЮ, А НЕ ХИТАМИ (v382). Гейт строевого боя (v352) читал сумму хитов, и разбор 5
    // реплеев против топ-3 показал, насколько это тонко: худшая ПОБЕДА отличается от лучшего ПОРАЖЕНИЯ на 282
    // хита — 1,8 % армии, — а решение по знаку запирает нас в гонке на весь бой. Хиты не знают ни оружия, ни
    // лечения: крип с выбитыми стволами весит столько же, сколько целый. Ланчестеровская мощь знает (powerOf
    // считает живые части и досягаемость за POWER_REACH_TICKS), и она уже служит мерой во всех прочих решениях
    // бота — от пары за флагом до бюджета погони. Прогноз `Forecast.simulate` для этого не годится: он не уходит
    // в минус НИ РАЗУ (см. комментарий у simPending), то есть на вопрос «выигрываем ли размен» всегда отвечает да
    // ...и у порога есть ЗАПАС (v383): по знаку решение запирает армию в гонке от любого минимального отставания,
    // а разбор показал, что дерутся как раз победы — строевой бой занимает 8–10 замеров из 13 в выигранных
    // матчах против 0–2 в проигранных, и худшая победа отстаёт от лучшего поражения всего на 1,8 % армии.
    // Отказ от строя стоит брать, когда мы отстаём ЗАМЕТНО, а не на волос
    // ...И ОТКАЗ ОТ СТРОЯ СНИМАЕТСЯ, КОГДА ПРОГНОЗ ОБЕЩАЕТ ВЫИГРАННЫЙ РАЗМЕН (v398). Мера мощи статична: она
    // знает состав армий, но не знает, чем кончится ТЕКУЩИЙ обмен, — а исход решают первые шесть-пятнадцать
    // тиков размена (разбор 55 матчей `MetalicaX#9` и 16 `#13`: различителя до контакта нет вовсе, 26 %
    // случайных перемешиваний меток разделяют данные лучше настоящего исхода). Прогноз это знает и меряет:
    // `simulate` возвращает АБСОЛЮТНУЮ разность мощей, которая при живой армии положительна всегда — оттого
    // «не уходит в минус ни разу», — но её ДЕЛЬТА за прокат отвечает ровно на вопрос «выигрываем ли мы
    // размен». Прибор v397 показал, что дельта различает исход: доля тиков с положительной дельтой 0,59 в
    // победах против 0,30 в поражениях, при том что действующее решение по мощи расходится с прогнозом в
    // 35-60 % тиков. ⚠️ И это НЕ четвёртая проба прогноза вслед за отвергнутыми: v244/v246 ставили прогноз в
    // опасность клетки, v394 в пробиваемость цели, v395 в сходимость стволов, и все три меняли ОЦЕНКУ, не
    // меняя того, кто и куда идёт, — оценка уходила вперёд исполнения. Здесь прогноз питает решение, которое
    // тот же командир и ИСПОЛНЯЕТ раздачей клеток в том же тике. Запрет остаётся, пока против нас обе меры
    val enemyMassed = Signals.enemyMassedSignal && Memory.simdShare <= SIMD_FIGHT_SHARE &&
        ourPowerOf(ctx.army, ctx.combatEnemies) < enemyPowerOf(ctx.combatEnemies, ctx.army) * FIGHT_POWER_ROOM
    // прежняя постура: применение решения (подстадия 8) перепишет `posture` и `postureSince`, решение читает их ДО записи
    val posturePrev = posture
    val postureSincePrev = postureSince
    val now = getTicks()
    /** кандидат прошлого тика и тик, с которого он предлагается без перерыва (v250) */
    val candidate = Memory.postureCandidate
    val candidateSince = Memory.candidateSince
    /** событие тика (прибор evt=, в гистерезис пока не входит — см. decide): наш крип погиб или флаг сменил владельца */
    // событие — прибор evt= (в гистерезис пока не входит, см. Strategist.decide): гибель своего (по числу живых,
    // не по составу армии — отряжённый в бегуны не потеря) и смена владельца флага. Замер v242 на стенде: события
    // в гистерезисе — любое изменение контакта 635 за матч, появившийся контакт 126, гибель + флаг 71 — и каждое
    // роняло scatter m34 (24 326:23 996 → 24 305:24 317 → 19 010:24 312) при 34/34/19 сценариях с иным счётом
    val event = ctx.myCreeps.size < Memory.armyPrev || WorldState.flagFlipNow
}

/** ПОДСТАДИЯ 8: `Strategist.decide`, память, точка отхода, пост, печать, применение постуры. */
internal class StrategyDecide(private val ctx: Ctx, private val meas: ArmyMeasures, private val packs: StrategyPacks, private val thr: StrategyThresholds, private val obj: StrategyObjective, private val inp: StrategyInputs) {
    // ОТВЕРГНУТО стендом (v58-опыт): снимать простой, когда паритет не пускает ни к одному флагу (матч 133: «марш не сдвинулся —
    // флаги до 479» при 1,32 к лагерю на D5, obj=- все 300 тиков, 18 против 7 в тик). На стенде m31 camp снятый на 536-м простой
    // дал 700 тиков ANNIHILATE pushing при 3679 против 1209 без единого убитого (центры армий в одной клетке, reach 0/5) —
    // 17133:23618 вместо «стёрт на 776-м», где к нему армию довела ЦЕЛЬ-ФЛАГ D5 (412–536). Толчок к стоящему блобу не
    // сближается — открытая находка матчей 70 и 133
    // РЕШЕНИЕ О СОСТОЯНИИ АРМИИ — ОДНО (v241, этап 6): постура, её гистерезис, режим командира и перезапись постуры
    // режимом боя считаются вместе в Strategist.decide; здесь применяется то, что относится к постуре, ниже — режим
    val decision = Strategist.decide(inp)
    init { Memory.contactPrev = meas.fight.contact }
    init { Memory.armyPrev = ctx.myCreeps.size }
    init { Memory.postureCandidate = decision.candidate }
    init { Memory.candidateSince = decision.candidateSince }
    init { if (decision.event) stateEventTicks.n++ }
    // ПРИНЯЛА ЛИ ПОСТУРА НОВОЕ ЗНАЧЕНИЕ — считается ЗДЕСЬ, до всех, кто от этого зависит (v215). Прежде
    // решение принималось на сорок строк ниже, а `objectiveFlagId` присваивался выше и безусловно
    private val newPosture = decision.newPosture
    private val postureTakes = decision.postureTakes
    // ЦЕЛЬ-ФЛАГ НЕ ОБНУЛЯЕТСЯ, ПОКА ПОСТУРА УДЕРЖАНА (v215). Дефект был в паре: гистерезис держит ANNIHILATE,
    // а `objective` при аннигиляции равен null — и `objectiveFlagId` обнулялся, хотя постура осталась прежней.
    // Следствие через тик: `chooseFlagObjective` теряет бонус ×1,25 за текущую цель (3545) и послабление
    // LOCAL_ENTER_RATIO (3535), и цель выбирается заново ПО ВСЕЙ КАРТЕ. Ровно это оператор и видел: «пару тиков
    // погоня, потом разворот». Новое значение берётся всегда; ОБНУЛЕНИЕ — только вместе с постурой
    private val newFlagId = obj.objective?.flag?.id
    init { if (newFlagId != null || postureTakes) objectiveFlagId = newFlagId }
    // ТОЧКА ОТХОДА БЕРЁТСЯ ИЗ ПОСТУРЫ, КОТОРАЯ БУДЕТ ПРИНЯТА (v217). Обе строки ниже спрашивали
    // `newPosture` — намерение, — а действует `posture`, и они расходятся всякий раз, когда гистерезис
    // удерживает прежнюю. Удержанный RETREAT при другом намерении обнулял `retreatTarget` и оставлял
    // `retreatTo` пустым, после чего ветка отхода в цепочке целей не срабатывала ни одного тика, и крипы
    // проваливались до последней ветки — `post`, в геометрическую точку своих флагов, спиной к врагу.
    // «Отступающая» армия при этом не отступала: у неё просто не было куда.
    private val postureNow = if (postureTakes) newPosture else posture
    init { if (postureNow != Posture.RETREAT) retreatTarget = null }
    val retreatTo = if (postureNow == Posture.RETREAT) retreatPoint(ctx) else null
    // ДЕРЖИ, ЧТО ДЕРЖИШЬ (v66): армия, стоящая на своём флаге при враге рядом, постом считает этот флаг, а не дальний пост.
    // Матч 159 (けろびー, пятнадцатый проигрыш фермеру 12009:24099): он кайтит вокруг D5 при 4075 против 3084, при huntable 0
    // армия уходила в HOLD к посту (11,68) за сорок клеток, он возвращался на D5; на 800-м и 1000-м мы стояли на D5 (хранитель
    // melee_1 на 1009-м снят на 1011-м — девять его вооружённых в десяти клетках не пикет) и снова уходили. «Держать линию,
    // где стоит» стенд отверг (v42: 58 хуже / 48 лучше — возврат к посту добивает отбитый рывок); здесь — только свой флаг под
    // ногами
    private val standingFlag = if (meas.fight.enemyNear) ctx.flags.firstOrNull { it.ours && getRange(it.pos, ctx.ourCentroid) <= POST_STANDOFF } else null
    // ПОСТ НА ФЛАГЕ, А НЕ В ГЕОМЕТРИЧЕСКОЙ ТОЧКЕ (v214, оператор: «после битвы очень долго стоим и не идём
    // перебивать флаги, даже если сильнее»). Когда флаг-цели нет, постура становится HOLD, и цепочка целей
    // отправляет крипа на `post` — центроид наших флагов, стояние на котором не даёт НИ ОДНОГО очка.
    // `standingFlag` уже делает половину работы: при враге рядом постом становится наш флаг под ногами.
    init { postAll.n++ }
    val post = standingFlag?.pos ?: if (thr.interceptFlag?.ours == true) thr.interceptFlag.pos else postPoint(ctx)
    private val postureKey = "$newPosture:${objectiveFlagId ?: ""}"
    init {
        if (DEBUG_LOG && (postureKey != postureLogged || getTicks() % (LOG_EVERY * 10) == 0)) {
            postureLogged = postureKey
            println("posture: $newPosture t=${getTicks()} our=${meas.fight.ours.toInt()} enemy=${meas.fight.theirs.toInt()} near=${meas.fight.enemyNear} contact=${meas.fight.contact} pushing=$pushing huntable=${meas.chase.huntable.size}/${meas.forces.combatEnemies.size} retreatFeasible=${meas.fight.retreatFeasible} strikers=${meas.forces.strikers.size}/${ctx.army.size} " +
                "obj=${obj.objective?.let { "(${it.flag.pos.x},${it.flag.pos.y})${typeChar(it.flag.type)}${it.flag.score} pack=${it.pack.size} travel=${it.travel} v=${(it.value * 100).toInt()}" } ?: "-"} " +
                "retreatTo=${retreatTo?.let { "(${it.x},${it.y})" } ?: "-"} evadeTo=${obj.evadeTo?.let { "(${it.x},${it.y})" } ?: "-"} approach=${(obj.approachRate * 100).toInt()} post=(${post.x},${post.y}) behind=${WorldState.behindOnScore}/$behindTicks hunt=$huntingThreat fight=${packs.theirsFight.toInt()}/${packs.fightPack.size}${if (packs.fightAll) "" else " ourFight=${packs.oursFight.toInt()}"} push=${packs.theirsPush.toInt()}/${packs.pushPack.size}${if (packs.pushAll) "" else " ourPush=${packs.oursPush.toInt()}"}")
        }
    }
    // ГИСТЕРЕЗИС ПОСТУРЫ (v181, разбор разгрома 3d94bf): одиннадцать переходов за сто тиков — армия пять раз подряд
    // дёргалась EVADE↔HOLD, теряя и темп, и строй; сам диагноз это и называл («a hysteresis is missing somewhere»).
    // Постура держится минимум POSTURE_HOLD тиков, и раньше срока меняется только в сторону спасения — на RETREAT
    // или EVADE, потому что решение бежать ждать нельзя
    // ...и ИЗЪЯТИЕ ДЛЯ EVADE САМО РОЖДАЛО ПИЛУ (v183). Уход в спасение не ждёт срока, но EVADE — не спасение, а
    // «отступи на шаг», и без срока получалась ровно пила с периодом POSTURE_HOLD: EVADE на 54-м, HOLD на 59-м
    // (срок вышел), EVADE на 62-м, HOLD на 67-м, EVADE на 74-м — семь переходов за сто тиков перед контактом в
    // тестовой игре 3d95b5, и каждый EVADE отодвигал армию назад, пока он шёл вперёд: к первому выстрелу наш центр
    // стоял в восьми клетках от края, и все 37 тиков боя прошли спиной к стене. Срока не ждёт только RETREAT
    // «ПОСТАНОВКА ПРИМЕНЯЕТСЯ ОДИН РАЗ» (v242) ОТВЕРГНУТА живым A/B: постура после перезаписи режимом боя, действующая
    // отсюда (её видели бы фокус, строй и blockOn), вместе с «одним составом для прогноза» дала против MetalicaX#15 0-8
    // при контроле v241 2-6 (армия в ноль 7 из 8 против 6, леджер −53 315 против −34 943), против Coldkimchi#2 3-5
    // против 2-6 при леджере −50 855 против −29 572. Здесь применяется постура после гистерезиса, перезапись режимом
    // боя — ниже, там, где стояла (v241)
    init { posture = decision.posturePre }
    init { postureSince = decision.postureSincePre }
    val cmdWhyNow = decision.cmdWhy
}

/** ПОДСТАДИЯ 9: угроза, рейдер, охота; чистка таблиц. Идёт ПОСЛЕ печати постуры: та читает `huntingThreat` прошлого тика. */
internal class StrategyThreats(private val ctx: Ctx, private val meas: ArmyMeasures) {
    // ---- общие цели ----
    val centroid = ctx.ourCentroid
    private val ourHalfCombat = meas.forces.armedEnemies.filter { DistanceMap.inOurHalf(it.x, it.y) }
    private val ourHalfSoft = meas.forces.enemyCreeps.filter { c -> meas.forces.combatEnemies.none { it.id == c.id } && DistanceMap.inOurHalf(c.x, c.y) }
    val threat = ourHalfCombat.filter { catchable(it, meas.chase.chasers) }.minWithOrNull(compareBy<Creep>({ arrivalOf(it) }, { getRange(it, centroid) }))
    // рейдер: чужой безоружный на нашей половине — тот, что ближе к нашему флагу (захватчик идёт к нему); гонимся,
    // только если стрелки бьют стаю вокруг него: без этой проверки армия гналась за безоружным остовом к
    // стоявшей за ним армии врага и вошла в бой при 0.77 (матч 3, t=100)
    val raider = ourHalfSoft.minByOrNull { r -> minOf(getRange(r, centroid), ctx.flagsNotTheirs.minOfOrNull { getRange(r, it.pos) } ?: 99) }
        ?.takeIf { r ->
            // стая рейдера — и те, кто дойдёт до него не позже нас: скаут в 12 клетках впереди своей армии был
            // «без охраны», и армия вышла из дома ему навстречу — прямо под удар всей армии врага (матч 6, t=50)
            val field = flowTo(ctx, r)
            val ourTravel = meas.chase.chasers.map { pathTicks(it, field, it.key) }.filter { it < Int.MAX_VALUE / 4 }.maxOrNull() ?: Int.MAX_VALUE / 4
            val pack = packAt(ctx, r, field, ourTravel)
            catchable(r, meas.chase.chasers) && (pack.isEmpty() || (meas.forces.strikers.isNotEmpty() && ourPowerOf(meas.forces.strikers, pack) >= enemyPowerOf(pack, meas.forces.strikers) * PUSH_RATIO))
        }
    // охота на угрозу на нашей половине — решение группы с гистерезисом: стрелки против всей стаи у угрозы
    init {
        huntingThreat = !posture.withdrawing && threat != null && meas.forces.strikers.isNotEmpty() && run {
            val threat = threat!!     // поле носителя внутри лямбды компилятор не сужает, как сужал локальную; непустота — левее, в этом же `&&`
            val field = flowTo(ctx, threat)
            val ourTravel = meas.forces.strikers.map { pathTicks(it, field, it.key) }.filter { it < Int.MAX_VALUE / 4 }.maxOrNull() ?: Int.MAX_VALUE / 4
            val pack = meas.forces.combatEnemies.filter { getRange(it, threat) <= ENGAGE_RANGE + RANGED_RANGE || (!stationary(it) && pathTicks(it, field, it.key) <= ourTravel) }
            val o = ourPowerOf(meas.forces.strikers, pack)
            val t = enemyPowerOf(pack, meas.forces.strikers)
            o >= t * (if (huntingThreat) PUSH_RELEASE_RATIO else PUSH_RATIO) &&
                (fightCost(pack, meas.forces.strikers) <= (meas.forces.strikers.maxOfOrNull { speedSlack(it) } ?: 0) || inContact(pack, meas.forces.strikers))
        }
    }
    init { Memory.prune(ctx.army) }
}

internal const val FLEE_EDGE_MIN = 8   // = EDGE_CORNER вскрытия: бой с центром ближе восьми к краю — «у стены»

/** Флаг перехвата — тот из не его флагов, к которому МЫ успеваем раньше (наш путь + запас ≤ его дистанция), первый в его
 *  порядке (по близости к нему), и он липкий: держится, пока он его не возьмёт. Первая форма («ближайший к его центру»)
 *  дребезжала с каждым его шагом — матч 59: армия ходила между (8,90), (31,67), (49,49) и (13,49), по 40–60 клеток, и никуда не
 *  приходила, пока он фармил 17 в тик (9359:24080). */
internal const val INTERCEPT_MARGIN = 2

internal const val PUSH_RELEASE_RATIO = 1.1

internal const val PUSH_RELEASE_RATIO_BEHIND = 1.05

/** Отставание, длящееся дольше стольких тиков, — уже не «скаут делает последний шаг к флагу», а пат: враг держит
 *  на флаг больше, его армия стоит, а наша при 1.01 полторы тысячи тиков ждёт перевеса 1.1 и проигрывает по
 *  очкам по единице в тик (стенд m3 army: 20252:20028 на 1992-м тике). Тогда в бой при равенстве. */
internal const val BEHIND_PATIENCE = 200

internal const val PUSH_RATIO_STALEMATE = 1.0

/** ОБЩАЯ ЦЕЛЬ ЧЕТЫРЁХ МИЛИ (v221, рычаг B пачки; разбор двух рейтинговых серий по реплеям обеих сторон).
 *  1. Замер. Во ВСЕХ шести блоб-поражениях v219–v220 через 40 тиков после контакта он не потерял ни одного
 *     стрелка и ни одного мили, мы — стрелков (первая смерть на 8–34-м тике) и мили; в четырёх из шести блоб-побед
 *     к этому сроку он потерял от двух до пяти стрелков. Хиты за первые 20 тиков при этом бывают ровными
 *     (8500 : 7493): его урон складывается в убийства — четыре мили по 240 на одном нашем стрелке в 1200, — наш
 *     размазан и залечен его тремя лекарями (216 в тик).
 *  2. Код. Удар мили (`strike`) сфокусирован — приказ командира, цель фокуса вплотную, — а НОГИ нет: вне командира
 *     каждый мили идёт к БЛИЖАЙШЕМУ допустимому врагу (`engage`, `minByOrNull { getRange }`), под командиром клетка
 *     мили считается по СУММЕ притяжения всех врагов (`attMeleeAt`), общая цель — только в замысле FOCUS. Прибор
 *     `conc` считает одних стрелков: сложены ли четыре удара в одну цель, не мерил никто (см. mconcAll).
 *  3. Правка ничего не ЗАПРЕЩАЕТ мили (этим она отличается от дважды отвергнутых ворот «мили входит парой»,
 *     USE_MELEE_PAIR_GATE) и не трогает ноги СТРЕЛКОВ (отвергнутые USE_PRESS_RING и USE_CELL_KNOWS_FOCUS): среди
 *     целей, которые мили и так разрешено брать, выбирается одна на всех — его стрелок или лекарь, ловимый и в
 *     ENGAGE_RANGE не меньше чем от двух наших мили; прежде всего липкая прежняя, затем цель фокуса. */
/** ...и та же цель — клетке мили под командиром (v221): притяжение `attractionTo(цель пачки)` во всех замыслах, а не
 *  только в FOCUS. Экспозиция на стенде мала (командир в бою 0,4–0,9 % тиков), живьём в блоб-разгромах режим
 *  FIGHT держится десятки тиков подряд — именно там ноги мили решает командир. */
internal const val PARITY_FLOOR_LOST = 0.75

internal const val PUSH_RELEASE_RATIO_STALEMATE = 0.95

/** Перевес врага, при котором армия отходит (и порог выхода из отхода). Решение об отходе принимается ДО
 *  контакта: при равной скорости из контакта не выйти, и 1.3 означало «в контакт при 0.77 и до конца» —
 *  матч 3, где 0.77 из четырёх дебаффов стоили всей армии. */
internal const val RETREAT_RATIO = 1.15

internal const val RETREAT_RELEASE_RATIO = 1.05

/** ТИШИНА фермера (v65): «ни разу не ударил» (v57) обнулял отряды и порог проигранной гонки (v63) на весь матч по одному
 *  размену — матч 158 (けろびー-фермер, четырнадцатый проигрыш 5402:23167): его блоб наткнулся на ядро у R3 на 254–259-м,
 *  потерял троих, и следующие 940 тиков фармил 18–22 в тик без единого удара, пока армия при 2:1 (4179 против 1981) гналась
 *  и не догоняла. Стендовый кайтер (m31 kite) возвращается за разделённым ядром через 160 тиков после укуса приманки
 *  (84 → 246). Порог между ними — три окна PASSIVE_TICKS. */
internal const val FARMER_QUIET = PASSIVE_TICKS * 3

/** В последних тиках матча проигрывающему по счёту флаги нужны любой ценой: бой уже не успеет. */
internal const val LAST_CALL_TICKS = 300

internal const val RUSH_VETO_TICKS = 10             // половина окна подхода: бросок на полной скорости держит темп всё окно

internal const val GOAL_GUARD_COST = 6

internal const val MARCH_SAFE = 12

internal const val RACE_PARTY = 3

// ...порог назван числом, а не на глаз: при трёх его вооружённых у нашей армии гейт даёт 132 из 135 (camp
// 2 970:21 279, scatter, kite) — раздача клеток берётся вести бой против того, кто строем не дерётся; при ШЕСТИ,
// то есть при настоящем блобе, гейт держит все 135. На стенде власти это не прибавило (cmd=12/4 как и было: там
// враг и так сомкнут), но снимает требование сомкнутости живьём, где пара «кулак + широкое окно» и дала 8-8
internal const val COMMAND_MIN_FOES = 6

internal const val COMPACT_MIN = 4

// SCATTER_OFF_SHARE = 3/4: сбор — крупнейшая группа не меньше трёх четвертей его вооружённых (целочисленно: ×4 ≥ ×3)
/** ОКНО СБРОСА ОТРЯДА ПО ПОГАСШЕМУ ЯРЛЫКУ (v533; до этого — 1, то есть окна не было вовсе).
 *
 *  Комментарий у правила (v115) говорит «одноткового моргания признака не хватает», а константа равнялась ЕДИНИЦЕ,
 *  и счётчик растёт ПЕРЕД проверкой: `farmerOffTicks = if (farmer) 0 else farmerOffTicks + 1`, затем
 *  `farmerOffTicks >= FARMER_OFF_TICKS`. То есть первого же тика без признака хватало, чтобы распустить ВЕСЬ отряд.
 *  Правило было записано и не действовало.
 *
 *  Замер (けろびー#19, матч 6ab13c63, 1700 тиков, проигран 17 244:23 857): признак «фермер» ложен 1 032 тика из
 *  1 700 (61 %), отряд выпускался и распускался ДЕВЯТЬ раз, медиана окна 60 тиков, минимум 11, четыре окна короче
 *  шестидесяти — при том что до дальних флагов идти 30-60 клеток. Темп очков: наш +12..+14 в тик, пока отряд
 *  держался (на 600-м тике вели 8 224:5 377), и +7 против его +18 в последней трети, где качель участилась.
 *  И отдельно: путь «ярлык погас» — ЕДИНСТВЕННЫЙ отзыв, который не ставит `detachRecallTick`, то есть не выжидает
 *  паузу перед новым выпуском; три остальных её ставят. Отсюда колебание с периодом 8-30 тиков: распустили,
 *  выпустили, распустили, — и бегуны разворачиваются, не дойдя.
 *
 *  Значение взято уже существующее — `DETACH_WINDOW`, то самое окно, которым код меряет «отряду нечего делать».
 *  Новых чисел правка не вводит. Настоящая опасность ядру снимается не этим правилом, а отзывом по мощи выше: он
 *  считается КАЖДЫЙ тик и возвращает по одному, начиная с сильнейшего. */
internal const val FARMER_OFF_TICKS = DETACH_WINDOW

internal const val TOUCH_MIN = 0.05        // ниже этой доли мили считается недостающим (замер дал 0,01)

internal const val EVADE_ARRIVED = 3

internal const val EVADE_EVAL_EVERY = 5

internal const val EVADE_HYSTERESIS = 4

internal const val EVADE_SAFE = 20

internal const val ESCAPE_MARGIN = 10

internal const val HUNTED_FAR = EVADE_RANGE + APPROACH_WINDOW

/** Запаздывание реакции на пошедшего врага: окно темпа плюс период пересчёта (см. exitMargin). */
internal const val REACTION_LAG = APPROACH_WINDOW + EVADE_EVAL_EVERY

/** СТРЕЛОК ДЕРЖИТСЯ ОТ ЕГО МИЛИ (v177, оператор): замер по записи разгрома — наши стрелки стояли вплотную к его
 *  вооружённому мили 33 крипо-тика и в двух клетках ещё 41 из 242 стрелковых. Клетка ближе MELEE_KEEP_RANGE к его
 *  мили стрелку не назначается. */
/** СМЕРТЕЛЬНАЯ КЛЕТКА (v178, оператор): клетка, где входящий за тик снимает крипу всю жизнь — шаг под двух его
 *  мили, — не должна предлагаться вовсе. Опасность была слагаемым, которое перевешивали другие члены. */
/** ...и только при ПЕРЕВЕСЕ (v181): без этого условия снятие вето вернуло очки, но стоило армии — разгромов в
 *  серии стало пять вместо двух. Флаг под его ударом берётся, когда мы сильнее, а не когда просто равны. */
/** ...и EVADE ТОЖЕ ЖДЁТ СРОКА (v183): изъятие для него делало пилу с периодом ровно POSTURE_HOLD и отодвигало
 *  армию к стене перед каждым боем. Без срока остаётся только RETREAT — настоящее спасение. */
internal const val POSTURE_HOLD = 5

internal const val CAPTURE_EDGE = 1.1

/** ЦЕЛАЯ АРМИЯ — ЭТО И ЕСТЬ ЗАПАС. НЕ ВКЛЮЧЕНО (v187): рассуждение выглядело безупречно — против соперника, чьё
 *  лечение перекрывает наш урон, порог по МОЩИ не берётся никогда, и матч кончается «обе армии целы, флаги 1:4,
 *  счёт 3 210:18 145»; вето, поставленное беречь армию, при целой армии берегло только ноль очков. Замер сказал
 *  обратное и без оговорок: двенадцать тестовых игр против Coldkimchi#2 дали **0:12 при девяти аннигиляциях**
 *  против 2:10 при двух у той же сборки без правила. Причина ровно та, что записана в v181 («снятие вето стоит
 *  АРМИИ»): флаг вешает дебафф на ВЛАДЕЛЬЦА, и армия, взявшая его при неравной мощи, перестаёт быть целой —
 *  условие само себя отменяет через десяток тиков. Пол паритета считает эту цену для ОДНОГО флага, а не для
 *  режима «берём, пока целы». Что осталось верным: счёт против него мы не набираем, и предмет открыт. */
/** ПАТ СНИМАЕТ ВЕТО ЗАХВАТА (v189): бой, где за целое окно ни одна сторона не потеряла заметной доли хитов, армии
 *  не угрожает, и дебафф флага в нём ничего не решает — решают очки. Отличие от отвергнутого v187 в том, что пат
 *  меряется ВРЕМЕНЕМ: «армия цела» истинно и на входе в размен, а пат — только после сотни тиков без потерь. */
/** НЕ ВКЛЮЧЕНО (v189, 0:5 при четырёх аннигиляциях, пачка остановлена досрочно). Посылка была та же, что у v187,
 *  только защищённая от его дефекта: пат меряется ВРЕМЕНЕМ, поэтому вето снимается не на входе в размен, а после
 *  сотни тиков, за которые ни одна сторона не потеряла пяти процентов хитов. Счёт при этом действительно вырос
 *  (2 458–4 910 против 700–900 у v186), то есть флаги мы брать начали, — и армия начала гибнуть ровно как в v187.
 *  Вывод, который стоит держать: **дебафф флага бесплатен ЕМУ, но не нам.** Его лечение 237 228 против нашего
 *  урона 156 100 — запас в полтора раза и четыре флага сверху; наш урон под дебаффом падает до ~125 000 против
 *  того же его лечения, и «убить нельзя» становится «нельзя даже давить», после чего размен доигрывается не в
 *  нашу пользу. Захват против этого соперника закрыт с обеих сторон: и по мощи, и по времени. Открытый предмет
 *  остаётся прежним и лежит НЕ здесь: сломать его лечение. */
internal const val STALEMATE_WINDOW = 100     // окно наблюдения за хитами обеих сторон

internal const val STALEMATE_LOSS = 0.05      // «заметная доля»: за окно упало меньше пяти процентов

internal const val STALEMATE_GAP = 20         // разрыв контакта короче этого окно пата не роняет (v198)         // и признак держится подряд, чтобы не мигал

internal const val BRACE_RANGE = 13

/** Больше двух за остовами не уходит: аннигиляция проигрывает матч при любом счёте. */
internal const val CHASE_MAX = 2

/** Тиков, которые преследователь обязан пережить в клетке остова, — иначе это не погоня, а подарок. */
internal const val CHASE_TTL = 4

/** Меньше этого в армии — не до погони. */
internal const val CHASE_MIN_ARMY = 6

/** Состав ИДУЩЕГО боя — его крипы, успевающие прийти к нашей массе (см. fightPack). Считается в runArmy,
 *  читается гейтом захвата на следующем тике: задержка в тик здесь законна, та же, что у stalledNow. */
internal var fightPackIds: Set<String> = emptySet()

internal var postureLogged = ""

internal var postureSince = 0                    // тик последней смены постуры (v181, гистерезис)

internal var pushSince = 0                       // тик начала наступления (v215, см. USE_PUSH_DWELL)

internal var pushHeld = false                    // наступление держится сроком, а не признаками

internal var leadHoldsWas = false   // трасса «отрыв держит» (см. USE_LEAD_HOLDS)

internal var evadeTarget: Position? = null

internal var evadeEvaluatedAt = -100

/** Точка, которую покинули (стояли, счёт места ниже EVADE_SAFE): не цель, пока не прибыли в другую — иначе маятник:
 *  через два шага от неё она уже «не здесь», её счёт (6) выше счёта дома (4), армия возвращается, снова «здесь» —
 *  три качания за 13 тиков съели восемь тиков запаса при погоне на равной скорости (стенд m11 sleeper). */
internal var evadeLeft: Position? = null

internal var escapeAt = -100

internal var idleDetachTicks = 0                       // подряд тиков, когда весь отряд без цели

internal var detachRecallTick = -1000                  // последний отзыв отряда без дела

internal var lastNonHuntTick = 0                       // последний тик в FLAG/EVADE/RETREAT (см. USE_DRY_HUNT_RANGED_GUARD: охота длится)

internal var interceptFlagId: String? = null           // флаг, который фермер обязан взять следующим (см. USE_INTERCEPT)

internal var coreShortTicks = 0                  // тиков подряд ядро без отряда ниже порога (см. USE_RECALL_PERSIST)

internal var scatteredLatched = false            // «рассыпан» с гистерезисом (см. USE_SCATTER_HYSTERESIS)

internal var scatteredAtRelease = false          // ярлык «рассыпан» на момент выпуска отряда (см. USE_RECALL_REF_LATCH)

internal var farmerOffTicks = 0                  // тиков подряд без признака фермера (см. FARMER_OFF_TICKS)

internal var standoffTicks = 0                         // тиков подряд стоящей линии врага (см. PRESS_PATIENCE)

internal var pressing = false                          // прижим включён (см. USE_PRESS)

internal var stalemateGap = 0                          // тиков подряд без контакта (см. STALEMATE_GAP)

internal var lastAim = ""

internal class Objective(val flag: FlagInfo, val pack: List<Creep>, val value: Double, val travel: Int)

// ==================== приборы стадии: счётчик живёт у того, кто считает (v447, план архитектуры, 4.7 и этап 6) ====================
// Объявления перенесены из Instruments.kt дословно; Instruments их читает и печатает, текст строк прежний.

/** Отряды командира, сохранившие задание на пути к флагу (v299, route=бегуно-тиков). */
internal val routeKept = Gauges.counter("route")

/** Свои пустые флаги, на которые командир посадил бойца (v316, man=). */
internal val manned = Gauges.counter("man")

/** Покрытие гарнизона лечением (v361, прибор): гарнизонных крипов, у которых наш лекарь в дальности лечения / всего.
 *  Разбор 12 смертей против けろびー#19 сказал, что помощь не приходит никогда — ближайший лекарь в медиане 24 клетках
 *  при агонии 5,8 тика, — но своего числа у этого не было. */
/** Тиков с назначенным курьером на дорогой флаг (v367, прибор courier=). */
internal val courierTicks = Gauges.counter("courier")

internal val garCovered = Gauges.counter("gcov")

internal val garAll = Gauges.counter("gcov", 1)

/** Загон (v331, hunt=тиков с целью/крипо-тиков в загоне). */
internal val huntTicks = Gauges.counter("hunt")

internal val huntCreepTicks = Gauges.counter("hunt", 1)

/** Тики, где наступление удержано «остатком без мили» (v300, toothless=). */
internal val pushToothless = Gauges.counter("toothless")

internal val keepOn = Gauges.counter("keep2")

internal val keepTicks = Gauges.counter("keep2", 1)

internal var keepOff = 0

internal val keepOffCore = Gauges.counter("keep2", 2)

internal val keepOffPack = Gauges.counter("keep2", 3, sep = ":")

internal val keepOffLeft = Gauges.counter("keep2", 4, sep = ":")

internal val keepOffGone = Gauges.counter("keep3")

internal val keepOffFlag = Gauges.counter("keep3", 1, sep = ":")

internal val keepOffMoved = Gauges.counter("keep3", 2, sep = ":")

internal val keepOffHurt = Gauges.counter("keep3", 3, sep = ":")

internal val holdKeptRace = Gauges.counter("hold", 2)

/** Пара: сколько оставлено в ядре против сколько было свободных. */
internal val symCore = Gauges.counter("sym")

internal val symFree = Gauges.counter("sym", 1)

/** Вето «сперва туши очаг»: тиков с очагом и из них тех, где вето ИЗМЕНИЛО решение о постуре. */
internal val spotHoldAll = Gauges.counter("spothold", 1)

internal val postAll = Gauges.counter("postc", 1)

internal val spotHoldNew = Gauges.counter("spothold")

internal var chaseTicks = 0

internal val chaseKills = Gauges.counter("kills")

/** Прибор локализации: |opp| против |combatEnemies| — если держится единицей, локализация ничего не меняет. */
internal val capOppSum = Gauges.counter("capopp")

internal val capAllSum = Gauges.counter("capopp", 1)

/** Прибор v534: из скольких спросов, где защёлка «он дрался сомкнутым» держала ворота, замер её снял. */
internal val unwipeOpen = Gauges.counter("unwipe")

internal val unwipeAll = Gauges.counter("unwipe", 1)

/** Прибор v535: крипо-тиков хранителя-лекаря из всех крипо-тиков хранителя. */
internal val hkeepHeal = Gauges.counter("hkeep")

internal val hkeepAll = Gauges.counter("hkeep", 1)

/** Пара «тиков, где наступление удержано сроком / тиков с решением» (v215). */
internal val pushHeldTicks = Gauges.counter("pushheld")

internal val pushTicks = Gauges.counter("pushheld", 1)

/** Прибор к USE_RETREAT_BY_HIS_STEP: тиков, где старый признак говорил «отходит», из них тех, где его шаг — нет, и
 *  тиков, где новый говорит «отходит», а старый — нет (мы наступали быстрее, чем он пятился). */
internal val rtrOld = Gauges.counter("rtr", 1)

internal val rtrRemoved = Gauges.counter("rtr")

internal val rtrAdded = Gauges.counter("rtr", 2)

/** Пара к USE_GUARD_IS_CATCHABLE: крипо-проверок, где враг «уходит», и из них тех, где он страж своего флага. */
/** Пара к USE_FLAG_MAJORITY: отказов по паритету при армиях на паритете и из них тех, где флаг давал перевес по флагам. */
internal val majOffers = Gauges.counter("maj", 1)

internal val majOpened = Gauges.counter("maj")

/** Пара к USE_FOCUS_ANY_HEALER (v224): тиков с его лекарем в досягаемости наших стволов и из них тех, где фокус — лекарь. */
/** Дельта прогноза в решении о бое с кулаком (v397, прибор): сумма×100, тиков, тиков с положительной дельтой,
 *  тиков, где знак дельты РАСХОДИТСЯ с действующим решением по мощи. Поведение не меняется — мера только меряется. */
internal var simdSum = 0.0

internal var simdTicks = 0

internal var simdPos = 0

internal var simdDisagree = 0

internal var radSum = 0.0

internal var radTicks = 0

internal var radMax = 0

internal var radWide = 0

/** Пара «тиков, где ланчестерова мощь и фактический размен расходятся / тиков с признаком» (v216). */
internal val breakOffSplit = Gauges.counter("breakoff")

internal val breakOffN = Gauges.counter("breakoff", 1)

/** Бюджет командирской гонки: сколько отпущено, каким ядром и из скольких свободных. */
internal val budgetSum = Gauges.counter("budget")

internal val budgetTicks = Gauges.counter("budget", 1)

internal val objAll = Gauges.counter("objnone", 1)

internal val objDropN = Gauges.counter("objdrop", 1)

/** Записи скаутов гарнизона на входе в тик гонки (v466, дефект 5): пересоздана на тот же флаг / на другой / не пересоздана. */
internal val garScoutSame = Gauges.counter("garscout")

internal val garScoutSwitch = Gauges.counter("garscout", 1)

internal val garScoutLost = Gauges.counter("garscout", 2)

/** Ранние выходы гонки при непустом прежнем составе командира (v465, дефект 4): бой блокирует и держателей нет / свободных нет /
 *  бой блокирует при держателях / бюджет исчерпан. Первые два оставляют `cmdDetach` пустым до следующего тика. */
internal val raceExitFight = Gauges.counter("raceexit")

internal val raceExitFree = Gauges.counter("raceexit", 1)

internal val raceExitBlocks = Gauges.counter("raceexit", 2)

internal val raceExitBudget = Gauges.counter("raceexit", 3)

/** Исключения из состава гонки (v464, дефект 3): крипо-тики подвижных вооружённых, которых состав не взял — отряжён стратегом в
 *  этом тике / хранитель / преследователь. Отвечает, где именно инварианты членства действуют. */
internal val raceExclDet = Gauges.counter("racex")

internal val raceExclKeep = Gauges.counter("racex", 1)

internal val raceExclChase = Gauges.counter("racex", 2)

/** Выбор флаг-цели в состоянии «его вооружённые живы, наших ударников нет» (v462): спрошен / вернул пусто. */
internal val objNs = Gauges.counter("objns")

internal val objNsNull = Gauges.counter("objns", 1)

internal val stateEventTicks = Gauges.counter("evt")

internal val cmdWhyN = Gauges.counter("cmdwhy", 1)

/** Пара «отпущено во время боя / отпущено всего» (v215, наблюдение оператора «отряд распадается»). */
internal val splitFight = Gauges.counter("split")

internal val splitAll = Gauges.counter("split", 1)

/** Пара «крипо-тиков боя без своего лекаря в дальности лечения / крипо-тиков боя» (v215). */
internal val healGap = Gauges.counter("healgap")

/** ...и крипо-тики боя, где своего лекаря нет и в MASS_RANGE — «в бою ни одного хиллера» (v215). */
internal val noMedic = Gauges.counter("nomedic")

internal val healGapN = Gauges.also(Gauges.counter("healgap", 1), "nomedic", 1)

/** Пара «смен направления армии / тиков» (v215, наблюдение «разворачиваемся много раз»). */
internal val aimFlips = Gauges.counter("flip")

internal val aimTicks = Gauges.counter("flip", 1)

internal val lostRaceOffers = Gauges.counter("lostrace", 1)

internal val gatherHold = Gauges.counter("gather", 1)

/** Тройка «тиков в отходе / из них с точкой отхода / из них под огнём» (v217). Средний числитель обязан
 *  быть нулём, пока `retreatTo` считается по `newPosture`, а постуру перезаписывает командир. */
internal val retrTicks = Gauges.counter("retr")

internal val retrWithPoint = Gauges.counter("retr", 1)

internal val retrUnderFire = Gauges.counter("retr", 2)

/** Тройка «смен применённой постуры / из них эпизод короче POSTURE_HOLD / входов в отход не под огнём» (v479, см.
 *  USE_ESCAPE_UNDER_FIRE). Средняя часть и есть пила: эпизод короче срока гистерезиса рождается только изъятием. */
internal val postFlips = Gauges.counter("pflip")

internal val postFlipShort = Gauges.counter("pflip", 1)

internal val postFlipDry = Gauges.counter("pflip", 2)

/** Пара «крипо-тиков в отходе, где крип стрелял или бил / всех крипо-тиков в отходе» (v217). */
internal val standFire = Gauges.counter("standfire")

internal val standTicks = Gauges.counter("standfire", 1)

/** Пара «тиков признака outmatched / из них с постурой отхода» (v217, решение оператора). */
internal val outmTicks = Gauges.counter("outmw")

internal val outmRetreat = Gauges.counter("outmw", 1)

/** ПРИБОРЫ ТЁПЛОГО КОНТАКТА (v221, пары к USE_FIGHT_BY_LEDGER, см. warmNow):
 *  `warm` — тиков контакта без размена / тиков контакта; `warmann` — тиков ANNIHILATE, державшихся только таким
 *  контактом / тиков ANNIHILATE; `warmhold` — из них тиков, где флаг-цель подхватила бы «держим линию»;
 *  `warmcmd` — тиков режима боя при тёплом контакте / тиков режима боя (там постуру вернёт командир);
 *  `warmfight` — тиков «бой идёт» без размена / тиков «бой идёт» (отзыв бегунов, USE_NO_SPLIT_IN_FIGHT);
 *  `warmcap` — отказов захвата `contact.mass` без размена / отказов `contact.mass`. */
internal val warmTicks = Gauges.counter("warm")

internal val warmContact = Gauges.counter("warm", 1)

internal val warmAnn = Gauges.also(Gauges.counter("warmann"), "warmhold", 1)

internal val warmAnnAll = Gauges.counter("warmann", 1)

internal val warmHold = Gauges.counter("warmhold")

internal val warmCmd = Gauges.counter("warmcmd")

internal val warmCmdAll = Gauges.counter("warmcmd", 1)

internal val warmCap = Gauges.counter("warmcap")

internal val warmCapAll = Gauges.counter("warmcap", 1)

internal val kvetoHit = Gauges.counter("kveto")

internal val kvetoAll = Gauges.counter("kveto", 1)

/** Пара «тиков ANNIHILATE со строем стрелков шире RALLY_RANGE / тиков ANNIHILATE» (v221, см. gatherSpread). */
internal val gatherAnn = Gauges.counter("gathera")

internal val gatherAnnAll = Gauges.counter("gathera", 1)

// ==================== межтиковое состояние и константы стадии (до v454 — члены object PainAndGain; второй шаг архитектуры, этап 1) ====================

internal var posture = Posture.HOLD

internal var objectiveFlagId: String? = null

internal var huntingThreat = false

internal val PUSH_DWELL = CHASE_WINDOW

// ==================== приборы стадии, бывшие членами object PainAndGain (v455, второй шаг архитектуры, этап 2) ====================

/** Пара «предъявлений, где послабление проигранной гонки решило исход / всех предъявлений с этим признаком»
 *  (v218, см. lostRaceNow). Числитель — флаг, прошедший по PARITY_FLOOR_LOST и НЕ прошедший бы по
 *  PARITY_FLOOR. До починки клапана он обязан быть около нуля в забегах: разбор v217 дал 582/1753/2407
 *  отказа по паритету в трёх проигранных забегах при 40 в среднем по победам. */
internal val lostRaceOpened = Gauges.counter("lostrace")

/** Пара «тиков в HOLD с растянутым строем стрелков / всех тиков в HOLD» (v218). Проверяет записанное в коде
 *  основание, по которому сбор (см. rallyTo) работает ТОЛЬКО в постуре FLAG: «в HOLD цель — точка, к ней
 *  сходятся и так». Если числитель мал — основание верно и трогать сбор незачем. Растяжка считается тем же
 *  порогом, каким сбор и включается (RALLY_RANGE). */
internal val gatherSpread = Gauges.counter("gather")

internal val stalemateTicks = Gauges.counter("pat")                        // сколько тиков подряд бой не двигается ни в чью пользу

internal val patMax = Gauges.counter("pat", 1)                                // самый длинный пат за матч — прибор, чтобы правило не мерили вслепую

internal val annEmptyAll = Gauges.counter("annempty", 1)

internal var touchMin = 1.0                            // минимум за матч — прибор

/** Почему у армии нет флаг-цели: пара по причинам против всех тиков (v216). */
internal val objNone = Gauges.labelled("objnone")

/** ...и разложение САМОГО выбора: какой фильтр снял флаг-кандидата (v216). */
internal val objDrop = Gauges.labelled("objdrop")

/** РАЗЛОЖЕНИЕ НЕВХОДА В РЕЖИМ БОЯ (v215). Прежний `cmdBlocked` НАЗЫВАЛ причину, не проверив её: он писал
 *  «posture», если постура не ANNIHILATE, — а условие боя постуры ANNIHILATE не требует вовсе, оно требует
 *  `!pushing && underTheirFire && (сомкнут || шесть рядом) && постура не отход`. По логам рейтинговой серии
 *  из-за этого выходило, будто виновата постура. Прибор, называющий не тот множитель, отправляет чинить не
 *  то место, поэтому причина берётся из ТОЙ ЖЕ цепочки веток, что и сам режим. */
internal val cmdWhy = Gauges.labelled("cmdwhy")

/** Разложение тиков ANNIHILATE без размена по источнику (v221, см. annEmptyAll): cmd — режим боя командира,
 *  push — толчок, spot — очаг, melee — его мили вплотную, corner — загнанная группа, still — контакт со стоящим
 *  (USE_WARM_NEEDS_HIS_MOVE), warm — тёплый контакт (с правкой обязан быть нулём), held — постура удержана
 *  гистерезисом без контакта. */
internal val annEmpty = Gauges.labelled("annempty")

/** СОСТОЯНИЕ СТРАТЕГА (v459, второй шаг архитектуры, этап 6): словари стратега, жившие членами `object PainAndGain`, — как есть, с чисткой на прежних местах. */
internal object StrategistState {
    internal val idleRunnerTicks = HashMap<String, Int>()  // бегун → подряд тиков без цели (v85: поштучный отзыв)
    internal val pressChase = HashMap<String, ArrayDeque<ChaseSample>>()  // погоня за целью прижима по тикам (см. PRESS_GIVEUP)
    internal val pressGiveUp = HashMap<String, Int>()      // цель прижима, от которой отказались, → тик, до которого
    internal val escapeFlows = HashMap<Int, IntArray>()
    internal val escapeTheirs = HashMap<Int, Int>()
    internal val escapeNearest = HashMap<Int, Int>()   // клетка врага, ближайшего к точке
    internal val capRunnerSaid = HashMap<String, String>()   // флаг → ответ ворот бегуну в этом тике (v467, прибор `capdis=`)
}
