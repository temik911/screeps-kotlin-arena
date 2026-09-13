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
import season4.painandgain.PainAndGain.Posture
import season4.painandgain.PainAndGain.Intent
import season4.painandgain.PainAndGain.CmdMode
import season4.painandgain.PainAndGain.Shooter
import season4.painandgain.PainAndGain.Objective
import season4.painandgain.PainAndGain.ChaseSample
import season4.painandgain.PainAndGain.FightCell
import season4.painandgain.PainAndGain.HypoMods

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
    /** Что видно армии к моменту решения; имена — как у мер в runArmy. */
    class Inputs(
        val annihilate: Boolean, val hasObjective: Boolean, val evade: Boolean, val retreat: Boolean,
        /** нет контакта и ни одного его вооружённого в MARCH_SAFE от массы */
        val marchNow: Boolean,
        val stalled: Boolean,
        /** он отходит, и это не рубка, в которой мы стоим: enemyRetreating && !(underTheirFire && theirMeleeIn) */
        val hisRetreat: Boolean,
        val outmatched: Boolean, val pushing: Boolean, val underFire: Boolean,
        /** ни сомкнутой армии, ни COMMAND_MIN_FOES у руки */
        val fewFoes: Boolean,
        val posture: PainAndGain.Posture, val postureSince: Int, val now: Int,
        /** кандидат прошлого тика и тик, с которого он предлагается без перерыва (v250) */
        val candidate: PainAndGain.Posture?, val candidateSince: Int,
        /** событие тика (прибор evt=, в гистерезис пока не входит — см. decide): наш крип погиб или флаг сменил владельца */
        val event: Boolean,
    )

    class Decision(
        val newPosture: PainAndGain.Posture, val postureTakes: Boolean,
        /** постура после гистерезиса, до перезаписи режимом боя — применяется у решения (v241, v243) */
        val posturePre: PainAndGain.Posture, val postureSincePre: Int,
        val cmdMode: PainAndGain.CmdMode, val cmdWhy: String,
        /** постура после перезаписи режимом боя — применяется там, где стояла перезапись (v241; «один раз» v242 отвергнуто) */
        val postureFinal: PainAndGain.Posture, val postureSinceFinal: Int,
        val event: Boolean,
        /** кандидат этого тика и начало его непрерывного ряда — в Memory до следующего тика (v250) */
        val candidate: PainAndGain.Posture, val candidateSince: Int,
    )

    fun decide(i: Inputs): Decision {
        val newPosture = when {
            i.annihilate -> PainAndGain.Posture.ANNIHILATE
            i.hasObjective -> PainAndGain.Posture.FLAG
            i.evade -> PainAndGain.Posture.EVADE
            i.retreat -> PainAndGain.Posture.RETREAT
            else -> PainAndGain.Posture.HOLD
        }
        // ГИСТЕРЕЗИС ПОСТУРЫ (v181): держится не меньше POSTURE_HOLD тиков; раньше срока меняется только на RETREAT —
        // спасение не ждёт; EVADE срока ждёт (v183: изъятие для EVADE само рождало пилу с периодом POSTURE_HOLD)
        val escape = newPosture == PainAndGain.Posture.RETREAT
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
        val takes = newPosture == i.posture || escape || i.now - candSince >= PainAndGain.POSTURE_HOLD
        val pre = if (takes) newPosture else i.posture
        val sincePre = if (takes && newPosture != i.posture) i.now else i.postureSince
        // РЕЖИМ КОМАНДИРА (v160): поход — врага рядом нет; гонка — затор или его отход вне рубки; бой — под его огнём,
        // его группа у руки, мы не наступаем и не бежим (v217: наступление режим боя не исключает — кулак нужен там,
        // где лечение не даёт добить, а признак «мы позади по размену» и есть !pushing … underFire)
        val fightNow = !i.pushing && i.underFire && !i.fewFoes && pre != PainAndGain.Posture.RETREAT && pre != PainAndGain.Posture.EVADE
        val mode = when {
            i.marchNow -> PainAndGain.CmdMode.MARCH
            i.stalled || i.hisRetreat -> PainAndGain.CmdMode.RACE
            fightNow -> PainAndGain.CmdMode.FIGHT
            else -> PainAndGain.CmdMode.RACE
        }
        // ...и причина берётся из той же цепочки (v215): прибор, повторяющий решение своим порядком, врёт ровно тогда,
        // когда бот меняется
        val why = when {
            mode == PainAndGain.CmdMode.FIGHT -> "fight"
            mode == PainAndGain.CmdMode.MARCH -> "march"
            i.outmatched -> "outmatched"
            i.stalled -> "stall"
            i.hisRetreat -> "retreat"
            i.pushing -> "push"
            !i.underFire -> "nofire"
            i.fewFoes -> "few"
            else -> "posture"
        }
        // РЕЖИМ НАЗНАЧАЕТ ПОСТУРУ (v162): командир решил драться — армия уничтожает, а не держит и не бежит; запись
        // через те же часы (v215)
        val overrideFight = mode == PainAndGain.CmdMode.FIGHT && pre != PainAndGain.Posture.ANNIHILATE
        val final = if (overrideFight) PainAndGain.Posture.ANNIHILATE else pre
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
                 posture: PainAndGain.Posture, cmdMode: PainAndGain.CmdMode, objectiveFlagId: String?, hisArmed: List<Creep>): Disposition {
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
            cmdMode == PainAndGain.CmdMode.FIGHT || posture == PainAndGain.Posture.ANNIHILATE -> Mission.Fight(hisArmed.map { it.id })
            posture == PainAndGain.Posture.FLAG && objectiveFlagId != null -> Mission.Take(objectiveFlagId)
            cmdMode == PainAndGain.CmdMode.MARCH -> Mission.Goto("goal")
            posture == PainAndGain.Posture.RETREAT -> Mission.Goto("retreat")
            posture == PainAndGain.Posture.EVADE -> Mission.Goto("evade")
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
internal fun PainAndGain.captureAllowed(ctx: Ctx, f: FlagInfo): Boolean = captureBlock(ctx, f) == null

/** Какие ворота держат захват — null, если разрешено (v135, прибор к разрезу `tools/flagcut.py`): пять ворот отказывали
 *  молча, и в логе стояло только POISED, поэтому нельзя было сказать, ЧТО именно держит бегуна в клетке от свободного
 *  флага. Условия и их порядок те же, что были в captureAllowed. */
internal fun PainAndGain.captureBlock(ctx: Ctx, f: FlagInfo): String? {
    if (f.ours) return null
    if (capTick != getTicks()) { capTick = getTicks(); capSeen.clear() }
    if (f.id !in capSeen) capOffered++
    if (ctx.combatEnemies.isEmpty()) return null
    // седьмой флаг — никогда при живой его армии (v127, USE_NO_SEVENTH_FLAG): все дебаффы наши, ни одного его
    if (ctx.flags.count { it.ours } + 1 >= ctx.flags.size) return "seventh"
    // последний зов и при РАВНОМ счёте: ничья 0:0 после уклонения (см. EVADE_EQUAL_RATIO) отдана не будет
    val ticksLeft = arenaInfo.ticksLimit - getTicks()
    val losingAtTheEnd = (ourScore - enemyScore) + (ourRate - enemyRate) * ticksLeft <= 0
    if ((behindOnScore || losingAtTheEnd) && ticksLeft <= LAST_CALL_TICKS) return null
    // во время броска безфлаговой армии (см. unflaggedRushNow — тот же сигнал, что уводит армию в уклонение) флаг не берёт
    // НИКТО: бой через двадцать тиков, и дебафф ложится на него. Скаут брал R3 на 42–43-м тике во всех четырёх боях с
    // けろびー (матчи 38, 43, 44, 45) — −20 % стрелкам в решающем размене, — проходя порог паритета с запасом три очка мощи
    // (3967 против 3964: модель мощи считает мили полными, а их обезоруживают за первые двадцать тиков). Три очка в тик
    // за тридцать тиков против пятой части огня на весь бой
    // флаг перехвата (см. USE_INTERCEPT) открыт и в контакте с фермером, и при «бое близко»; остальные — нет
    val intercept = enemyNotFightingNow && f.id == interceptFlagId
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
    val rushStale =  !unflaggedRushNow && fightImminentTicks > rushStartDist
    if (fightImminentNow && !rushStale && !intercept && !(stalledNow)) {
        // пара к погоне за кайтером (v221, см. kiteChaseSeen): сколько отказов доктрины безфлагового броска
        // выдано, пока мы гонимся за отходящим, который бьёт нас сильнее, чем мы его
        if (unflaggedRushNow) { kvetoAll++; if (kiteChaseSeen) kvetoHit++ }
        return capCount(f, if (unflaggedRushNow) "rush.unflagged" else "rush.approach")
    }
    if (fightImminentNow && rushStale) capCount(f, "rush.approach.expired")
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
    val losingRace =  behindOnScore && enemyRate > ourRate &&
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
    val foes = ctx.combatEnemies.filter { threatening(it, ctx.enemyCreeps) }
    val mass = centroidOf(ctx.army)
    val contactArmy = if (mass == null) ctx.army else ctx.army.filter { getRange(it, mass) <= MASS_RANGE }
    if (!losingRace && !stalledNow && !intercept && contactArmy.any { fullSpeed(it) && hasWeapon(it) } && inContact(foes, contactArmy)) {
        // пара к вето контакта по размену (v221, только прибор): сколько отказов выдано контактом, в котором за
        // окно ни одна сторона не потеряла STALL_DAMAGE. Окно — прошлого тика: runRunners идёт раньше runArmy
        warmCapAll++; if (!exchangeLiveNow) warmCap++
        return capCount(f, "contact.mass")
    }
    // ...и отдельно считаем то, что этой правкой снято: стычка одиночки вне массы
    if (!losingRace && !stalledNow && !intercept && ctx.army.any { fullSpeed(it) && hasWeapon(it) } && inContact(foes, ctx.army))
        capCount(f, "contact.edge.lifted")
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
    val opp = oppLocal
    capOppSum += opp.size
    capAllSum += ctx.combatEnemies.size
    val (ours, theirs) = powerAfterFor(ctx,
        ctx.army + ctx.runners.filter { hasWeapon(it) || hasHeal(it) },
        opp, f)
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
    val lostRace = lostRaceNow()
    val floor = if (lostRace) PARITY_FLOOR_LOST else if (stalledNow) PARITY_FLOOR_STALLED else PARITY_FLOOR
    // ПАРА К КЛАПАНУ (v218, см. lostRaceOpened): «послабление решило исход» — флаг прошёл по PARITY_FLOOR_LOST
    // и НЕ прошёл бы по PARITY_FLOOR. Считается ЗДЕСЬ, а не у признака, потому что вопрос прибора не «был ли
    // признак истинен», а «изменил ли он хоть один отказ»
    if (lostRace) {
        lostRaceOffers++
        if (ours >= theirs * floor && ours < theirs * PARITY_FLOOR) lostRaceOpened++
    }
    // ...и в ПАТУ паритетный пол тоже молчит: он сравнивает мощь, а в бою, где никто никого не убивает, мощь
    // обеих сторон ланчестером считается около нуля, и сравнивать нечего (v189)
    if (ours >= theirs * floor) return null
    // ФЛАГОВ БОЛЬШЕ ЦЕНОЙ НЕБОЛЬШОГО МИНУСА (v222, решение оператора, см. USE_FLAG_MAJORITY): армии СЕЙЧАС на паритете,
    // перевеса по флагам у нас нет, а этот флаг его даёт — минус ровно дебафф этого флага
    run {
        val side = ctx.army + ctx.runners.filter { hasWeapon(it) || hasHeal(it) }
        val oursNow = ourPowerOf(side, opp)
        val theirsNow = enemyPowerOf(opp, side)
        if (oursNow >= theirsNow * floor) {
            majOffers++
            val ourFlags = ctx.flags.count { it.ours }
            val hisFlags = ctx.flags.count { it.theirs }
            val ourAfter = ourFlags + 1
            val hisAfter = hisFlags - (if (f.theirs) 1 else 0)
            if (ourFlags <= hisFlags && ourAfter > hisAfter) {
                majOpened++
                return null
            }
        }
    }
    capCount(f, "parity")
    return "parity(${ours.toInt()}/${(theirs * floor).toInt()})"
}

/** Считает отказ один раз на пару «тик × флаг» и возвращает причину как есть. */
internal fun PainAndGain.capCount(f: FlagInfo, why: String): String {
    if (capTick != getTicks()) { capTick = getTicks(); capSeen.clear() }
    if (capSeen.add(f.id)) { capBlocked[why] = (capBlocked[why] ?: 0) + 1 }
    return why
}

/** Проигранная гонка (v63/v88): проигрыш по проекции на конец матча при PASSIVE_TICKS без удара по нам (v99: одна и та же
 *  для порога захвата и для стаи у свободного флага, см. USE_LOST_RACE_PACK_PARITY). */
internal fun PainAndGain.lostRaceNow(): Boolean {
    val ticksLeft = arenaInfo.ticksLimit - getTicks()
    val losingAtTheEnd = (ourScore - enemyScore) + (ourRate - enemyRate) * ticksLeft <= 0
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
    val quietShort = ourLostWindow < STALL_DAMAGE || getTicks() - lastHurtTick >= PASSIVE_TICKS
    return losingAtTheEnd && quietShort
}

internal fun PainAndGain.planCapture(ctx: Ctx, step: Position?) {
    if (step == null) return
    ctx.flags.firstOrNull { !it.ours && it.pos.x == step.x && it.pos.y == step.y }?.let { plannedCaptures.add(it.id) }
}

/** Мощь сторон, если мы возьмём ещё этот флаг (и те, на которые уже шагаем в этот тик): наша — с их дебаффами;
 *  вражья — без них, если флаги были его. */
internal fun PainAndGain.powerAfter(ctx: Ctx, f: FlagInfo): Pair<Double, Double> =
    powerAfterFor(ctx, ctx.army + ctx.runners.filter { hasWeapon(it) || hasHeal(it) }, ctx.combatEnemies, f)

/** То же для заданной стороны и группы врага (v95: пул проверяет ядро без крипа с дебаффом его флага-цели). */
internal fun PainAndGain.powerAfterFor(ctx: Ctx, side: List<Creep>, opp: List<Creep>, f: FlagInfo): Pair<Double, Double> {
    val taking = HashSet(plannedCaptures); taking.add(f.id)
    // и флаг-цель армии (v121): захват, который уже идёт, — часть состояния «после»
    fun mods(mine: Boolean): HypoMods {
        fun k(type: String): Double {
            val now = ctx.flags.count { it.mine == mine && it.type == type }
            // его флаги В ПОЛЁТЕ (v128, USE_HIS_FLAGS_IN_FLIGHT): свободный флаг с его вооружённым вплотную — его в состоянии «после»,
            // и пол паритета видит симметричный размен, а не наш дебафф против его чистой армии (гастролёр берёт D5 на 39–41-м)
            val after = ctx.flags.count { it.type == type && (if (mine) (it.ours || it.id in taking) else ((it.theirs && it.id !in taking))) }
            return stackMul(type, after) / stackMul(type, now).coerceAtLeast(0.01)
        }
        return HypoMods(ranged = k(EFF_RANGED_ATTACK_MODIFIER), melee = k(EFF_ATTACK_MODIFIER), heal = k(EFF_HEAL_MODIFIER), hits = 1.0 / k(EFF_DAMAGE_TAKEN_MODIFIER))
    }
    val ourMods = mods(true)
    val theirMods = mods(false)
    // для captureAllowed сторона — армия с вооружёнными и лечащими бегунами (v59): паритет захвата — страховка от
    // аннигиляции стороны, а отряженные в бегуны (см. USE_DETACH) живы и вооружены. Ядро без пяти отряжённых стояло в
    // паритете (2918 против 2954), захват был запрещён ВСЕМ, и четверо отряжённых 800 тиков стояли POISED в клетке от
    // свободных D5, A3, R3 и H4 — 3174:22931, двенадцатый проигрыш фермеру (матч 139); до отряда, при 1,41, захваты были
    // разрешены и скаутам
    return powerOf(side, opp, ourMods, theirMods) to powerOf(opp, side, theirMods, ourMods)
}

/** Цена флага в силе — доля нашей мощи, которая останется после захвата (1 — бесплатно): флаг лечения
 *  для армии почти без лекарей дёшев, флаг уязвимости стоит всем; дорогие берутся последними. */
internal fun PainAndGain.captureCost(ctx: Ctx, f: FlagInfo): Double {
    if (f.ours || ctx.combatEnemies.isEmpty()) return 1.0
    // ОДИН СОСТАВ ПО ОБЕ СТОРОНЫ ДРОБИ (v216, см. USE_CAPTURE_COST_ONE_SIDE): знаменатель обязан считаться по
    // той же стороне, что и числитель в powerAfter, иначе отношение выходит больше единицы и обрезается в 1,0
    val side = ctx.army + ctx.runners.filter { hasWeapon(it) || hasHeal(it) }
    val now = ourPowerOf(side, ctx.combatEnemies)
    if (now <= 0.0) return 1.0
    return (powerAfter(ctx, f).first / now).coerceIn(0.0, 1.0)
}

internal fun PainAndGain.chooseFlagObjective(ctx: Ctx, group: List<Creep>, pushRatio: Double, escapeNeeded: Boolean = false, onlyFlagId: String? = null): Objective? {
    if (group.isEmpty()) return null
    var best: Objective? = null
    for (f in ctx.flags) {
        objDropN++
        if (f.ours) { objDrop["ours"] = (objDrop["ours"] ?: 0) + 1; continue }
        if (onlyFlagId != null && f.id != onlyFlagId) { objDrop["cpu"] = (objDrop["cpu"] ?: 0) + 1; continue }   // страховка CPU (v131c)
        if (!captureAllowed(ctx, f)) { objDrop["gate"] = (objDrop["gate"] ?: 0) + 1; continue }
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
        val travel = group.maxOf { pathTicks(it, flow, it.x * 100 + it.y) }
        if (travel >= Int.MAX_VALUE / 4) { objDrop["nopath"] = (objDrop["nopath"] ?: 0) + 1; continue }
        if (escapeNeeded) {
            // покинутую точку уклонения армия не идёт «захватывать»: у R3 (13,49) счёт места упал, точка покинута — и
            // тут же выбрана целью в шести тиках, навстречу врагу (матч 12, t=188)
            val left = evadeLeft
            if (left != null && left.x == f.pos.x && left.y == f.pos.y) { objDrop["evadeLeft"] = (objDrop["evadeLeft"] ?: 0) + 1; continue }
            // только флаг с выходом (см. ESCAPE_MARGIN, REACTION_LAG): за H4 в угол (8,90) армия шла 52 тика, пока
            // враг шёл на неё с 80 клеток (матч 11); за R3 (13,49) — 41 тик вдоль западного края при спящем враге,
            // и тот пошёл на полпути (матч 12)
            if (exitMargin(ctx, f.pos, travel) < ESCAPE_MARGIN) { objDrop["exit"] = (objDrop["exit"] ?: 0) + 1; continue }
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
        val ok = pack.isEmpty() || (farmerQuietNow) ||
            (ourPowerOf(group, pack) >= enemyPowerOf(pack, group) * ratio && fightCost(pack, group) <= group.maxOf { speedSlack(it) })
        if (!ok) { objDrop["pack"] = (objDrop["pack"] ?: 0) + 1; continue }
        objDrop["taken"] = (objDrop["taken"] ?: 0) + 1
        // гистерезис: текущая цель ценнее на четверть, чтобы не прыгать между равными; дорогой по силе — позже
        val value = f.swing * captureCost(ctx, f) / (travel + 10) * (if (current) 1.25 else 1.0)
        if (best == null || value > best.value) best = Objective(f, pack, value, travel)
    }
    return best
}

internal fun PainAndGain.retreatPoint(ctx: Ctx): Position {
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

/** Точки выхода: семь флагов и оба дома (углы — карманы, их нет; см. EVADE_SAFE). */
/** Точки выхода: флаги, оба дома и ЧЕТЫРЕ угла карты. Флаги стоят между домами — на оси подхода врага, и с одними
 *  флагами «прочь» не существовало: уклонение от броска шло к R3 навстречу врагу 30 клеток и было поймано у флага
 *  (матч 34, t=3–57). Углы — то, что всегда лежит в стороне от оси. */
internal fun PainAndGain.escapeCandidates(ctx: Ctx): List<Position> =
    ctx.flags.map { it.pos } + listOf(ctx.home, ctx.enemyHome) +
        // углы — только при броске: как постоянные кандидаты они меняли всякое уклонение (102 сценария в одну сторону,
        // 105 в другую, потеря армии m9 sleeper), а нужны они там, где флаги лежат на оси подхода
        (if (unflaggedRushNow) listOf(InfluenceMap.cell(3, 3), InfluenceMap.cell(3, 96), InfluenceMap.cell(96, 3), InfluenceMap.cell(96, 96)).map { passableNear(it) } else emptyList())

/** Поля потока к точкам выхода и ход врага до каждой — раз в EVADE_EVAL_EVERY тиков (девять полей). */
internal fun PainAndGain.refreshEscape(ctx: Ctx, armed: List<Creep>) {
    val now = getTicks()
    if (now - escapeAt < EVADE_EVAL_EVERY && escapeFlows.isNotEmpty()) return
    escapeAt = now
    escapeFlows.clear(); escapeTheirs.clear(); escapeNearest.clear()
    for (c in escapeCandidates(ctx)) {
        val key = c.x * 100 + c.y
        val flow = flowTo(ctx, c)
        escapeFlows[key] = flow
        var bestTicks = Int.MAX_VALUE / 4
        var bestCell = -1
        for (e in armed) {
            val t = pathTicks(e, flow, e.x * 100 + e.y)
            if (t < bestTicks) { bestTicks = t; bestCell = e.x * 100 + e.y }
        }
        escapeTheirs[key] = bestTicks
        escapeNearest[key] = bestCell
    }
}

/** Запас выхода из точки c при нашем прибытии туда через arrive тиков. Преследователь идёт ЗА НАМИ, к c, а не к
 *  выходам: ближайший к c враг проецируется по полю к c на approachRate × arrive шагов, и от этой клетки считается
 *  его путь к каждому другому выходу минус наш путь от c туда; лучший из них — запас. Фора «идёт к выходу
 *  мгновенно» браковала всякую цель при идущем на нас враге, и армия сидела дома до боя в кармане (стенд m10
 *  hunter, m8 army); без выхода точка — карман (матч 11). */
internal fun PainAndGain.exitMargin(ctx: Ctx, c: Position, arrive: Int): Int {
    val ckey = c.x * 100 + c.y
    val flowC = escapeFlows[ckey] ?: return Int.MIN_VALUE / 2
    val start = escapeNearest[ckey] ?: return Int.MIN_VALUE / 2
    val theirsC = escapeTheirs[ckey] ?: return Int.MIN_VALUE / 2
    if (theirsC >= Int.MAX_VALUE / 4) return Int.MIN_VALUE / 2   // его путь неизвестен — выход не подтверждён (v54)
    // при броске (см. EVADE_EQUAL_RATIO) преследователь идёт за нами на ПОЛНОЙ скорости: проекция по замеренному
    // темпу (0.42 в паузе колонны) считала дом безопасным выходом, и армия ушла в свой угол под удар (матч 32)
    val rate = if (unflaggedRushNow) 1.0 else approachRate
    val pursuer = if (start < 0) -1 else projectAlong(flowC, start, minOf(theirsC, (rate * arrive).toInt() + REACTION_LAG))
    var best = Int.MIN_VALUE / 2
    for (d in escapeCandidates(ctx)) {
        if (d.x == c.x && d.y == c.y) continue
        val flow = escapeFlows[d.x * 100 + d.y] ?: continue
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
internal fun PainAndGain.runnerEscape(ctx: Ctx, s: Creep): Position? {
    var best: Position? = null
    var bestScore = Int.MIN_VALUE
    for (c in escapeCandidates(ctx)) {
        if (getRange(c, s) <= 2) continue
        val key = c.x * 100 + c.y
        val flow = escapeFlows[key] ?: continue
        val theirs = escapeTheirs[key] ?: continue
        val ticks = pathTicks(s, flow, s.x * 100 + s.y)
        if (ticks >= Int.MAX_VALUE / 4) continue
        val score = minOf(theirs - ticks, exitMargin(ctx, c, ticks))
        if (best == null || score > bestScore) { bestScore = score; best = c }
    }
    return best
}

internal fun PainAndGain.evadePoint(ctx: Ctx, armed: List<Creep>, strikers: List<Creep>): Position? {
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
    val enemyCentre = if (unflaggedRushNow) centroidOf(armed) else null
    for (c in escapeCandidates(ctx)) {
        if (left != null && c.x == left.x && c.y == left.y) continue
        if (enemyCentre != null && getRange(c, enemyCentre) <= getRange(ctx.ourCentroid, enemyCentre)) continue
        val key = c.x * 100 + c.y
        val flow = escapeFlows[key] ?: continue
        val theirs = escapeTheirs[key] ?: continue
        // «ему не дойти» — это НЕИЗВЕСТНОСТЬ, не безопасность (v54): поле за бюджетом BFS даёт Int.MAX_VALUE / 4, и точка
        // получала запас 536870903 — армия ушла в угол (96,3) при его армии в 75 клетках к югу и была там стёрта (матч 114,
        // Coldkimchi, t=333); бегство вбок (v53) не включилось, потому что «лучшая точка» была положительной
        if (theirs >= Int.MAX_VALUE / 4) continue
        val ourTicks = strikers.maxOfOrNull { pathTicks(it, flow, it.x * 100 + it.y) } ?: continue
        if (ourTicks >= Int.MAX_VALUE / 4) continue
        val exit = exitMargin(ctx, c, ourTicks)
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
internal fun PainAndGain.postPoint(ctx: Ctx): Position {
    // пост под безфлаговым броском — флаг нашей половины, ближний к ЦЕНТРУ (v129, USE_POST_TOWARD_CENTRE): армия ждёт конца
    // удержания в 25 от D5 у A3, а не в углу у R3; первый флаг по времени тот же, второй на тридцать тиков раньше (матчи 5, 8,
    // 19 серий 367–406: его шесть флагов к 80–91-му, наш второй на 83–140-м)
    val centreCell = InfluenceMap.cell(49, 49)
    val ourHalfFlag = ctx.flags.filter { DistanceMap.inOurHalf(it.pos.x, it.pos.y) }
        .minByOrNull { getRange(it.pos, ctx.ourCentroid) }?.pos
    // центральный флаг наш — пост на нём (v102, USE_POST_ON_CENTRE)
    val centre = ctx.flags.firstOrNull { it.ours && it.type == EFF_DAMAGE_TAKEN_MODIFIER }?.pos
    // ...И ПОКА У НАС НЕТ НИ ОДНОГО ФЛАГА — ПОСТ НА ЦЕНТРАЛЬНОМ ФЛАГЕ С ОБЕИХ СТОРОН (v230, см. USE_POST_CONTEST_CENTRE)
    // ...кроме центра, на котором сидит его ОДИНОЧКА при армии вдали (стенд m1 grab: его скаут на D5, армия на его R3; колонна
    // марша к занятой клетке цели пляшет в 4–10 клетках от неё 1500 тиков и не убивает скаута — затор марша у занятой
    // цели, открытая находка): такой центр не оспаривается, пост прежний
    val c = centre ?: centroidOf(ctx.flags.filter { it.ours }.map { it.pos }) ?: ourHalfFlag ?: ctx.home
    return passableNear(c)
}

/** Бегство направлением (v46): когда ни одна точка выхода не даёт запаса (см. evadePoint), цель — клетка в EVADE_RANGE от
 *  нашего центра в том из восьми направлений, где после шага центр вооружённого врага дальше всего, среди направлений,
 *  чья клетка не ближе EVADE_RANGE к краю (у края направлений вдвое меньше, в углу — вчетверо: матчи 78, 80). Точки
 *  выхода (флаги, дом, углы) при равной скорости преследователя все «за ним» или без выхода, и армия шла в наименее
 *  плохую — в угол. */
internal fun PainAndGain.fleePoint(ctx: Ctx, armed: List<Creep>): Position? {
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
        for ((dx, dy) in DIRECTIONS) {
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

internal fun PainAndGain.updateKeepers(ctx: Ctx, army: List<Creep>) {
    val armedEnemies = ctx.combatEnemies.filter { threatening(it, ctx.enemyCreeps) }
    val iter = Memory.keeperIds.entries.iterator()
    while (iter.hasNext()) {
        val e = iter.next()
        val c = army.firstOrNull { it.id == e.key }
        val f = ctx.flags.firstOrNull { it.id == e.value }
        // враг с боем в KEEP_RANGE от флага — хранителя нет: стрелок стоял на R3 весь бой, пока в десяти клетках
        // висели скауты врага, и не стрелял (матч 18)
        val stay = c != null && f != null && f.ours && c.x == f.pos.x && c.y == f.pos.y && c.hits * 2 >= c.hitsMax &&
            enemyCreeps(ctx).any { it.id != c.id && getRange(f.pos, it) <= KEEP_RELEASE } &&
            armedEnemies.count { getRange(f.pos, it) <= KEEP_RANGE } <= KEEP_PICKET
        if (!stay) {
            if (DEBUG_LOG) println("keeper t=${getTicks()}: ${e.key} released from ${e.value}")
            iter.remove()
        }
    }
    for (f in ctx.flags) {
        if (!f.ours) continue
        val occ = f.occupant ?: continue
        if (occ.my != true || army.none { it.id == occ.id } || occ.id in Memory.keeperIds) continue
        // ...и хранителем не становится лекарь (v215, см. USE_HEALER_NEVER_PINNED): ветка `keeper` первая в
        // цепочке целей, и пришпиленный к флагу лекарь выключается из боя целиком
        if (army.any { it.id == occ.id && !hasWeapon(it) && hasHeal(it) }) continue
        if (Memory.runnerFlag.values.contains(f.id)) continue
        if (enemyCreeps(ctx).none { getRange(f.pos, it) <= KEEP_RANGE }) continue
        if (armedEnemies.count { getRange(f.pos, it) <= KEEP_RANGE } > KEEP_PICKET) continue
        Memory.keeperIds[occ.id] = f.id
        if (DEBUG_LOG) println("keeper t=${getTicks()}: ${occ.id} keeps ${f.id} at (${f.pos.x},${f.pos.y})")
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
internal fun PainAndGain.commandGoal(ctx: Ctx, army: List<Creep>, armedEnemies: List<Creep>): Position? {
    if (army.isEmpty()) return null
    // ...и оценку флага командир не изобретает заново, а ВЫЗЫВАЕТ: chooseFlagObjective считает ценность, путь,
    // пачку у флага и возможность отхода — всё, чего не знает «ближайший разрешённый». Своя формула была написана
    // и отвергнута замером: по близости 131 из 135 (camp дважды, screen, brawl+heals), по ценности на шаг 132,
    // с квадратичным штрафом расстояния снова 131. Решение остаётся командирским — он спрашивает и решает
    return chooseFlagObjective(ctx, army, PUSH_RATIO)?.flag?.pos
    val best = ctx.flags.filter { !it.ours && it.occupant?.my != true && captureAllowed(ctx, it) }
        .maxByOrNull { f ->
            val near = army.minOf { getRange(it, f.pos) }
            val guards = armedEnemies.count { getRange(it, f.pos) <= ENGAGE_RANGE }
            f.score.toDouble() / (near + 1 + guards * GOAL_GUARD_COST)
        }
    return best?.pos
}

/**
 * Назначает погоню за остовами: крипами врага, у которых выбито всё оружие, но тело его помнит, — пока у него
 * жив лекарь, такой крип это полная его мощь на таймере. Условие на цель одно и оно из той же модели, что всё
 * остальное: ОПАСНОСТЬ В ЕГО КЛЕТКЕ должна быть настолько мала, чтобы преследователь прожил там CHASE_TTL
 * тиков. Этим одним условием сказано и «он оторвался от своего блока», и «нашему там не смертельно», и
 * отзыв погони: как только остов вернулся под прикрытие, опасность растёт и назначение само перестаёт
 * выдаваться. Отдельного правила отзыва не нужно.
 */
internal fun PainAndGain.assignChase(army: List<Creep>, enemyCreeps: List<Creep>, armedEnemies: List<Creep>) {
    Memory.chaseOf.clear()
    Memory.chaseTarget.clear()
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
        val key = h.x * 100 + h.y
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
        Memory.chaseOf[chaser.id] = h.id
        Memory.chaseTarget[chaser.id] = h
        free.remove(chaser)
        budget--
    }
    if (Memory.chaseOf.isNotEmpty()) chaseTicks++
    // прибор: остов, за которым была погоня и который перестал существовать, — это её результат
    for (id in Memory.chasedIds.toList()) if (enemyCreeps.none { it.id == id }) { chaseKills++; Memory.chasedIds.remove(id) }
    Memory.chasedIds.addAll(Memory.chaseOf.values)
}

internal fun PainAndGain.commandRace(ctx: Ctx, army: List<Creep>, armedEnemies: List<Creep>, flags: List<FlagInfo>,
                        out: MutableMap<String, Position>) {
    out.clear()
    // ...и состав берётся ДО очистки (v215, см. USE_RACE_COUNTS_RELEASED): очистка стояла строкой выше чтения
    val alreadyOut = ctx.runners.filter { it.id in Memory.cmdDetach }
    Memory.cmdDetach.clear()
    // В БОЮ НЕ ОТПУСКАЕМ НИКОГО (v215, см. USE_NO_SPLIT_IN_FIGHT). Проверки «мы в контакте» здесь не было вовсе,
    // а RACE — ветка `else` в выборе режима, то есть значение по умолчанию: достаточно, чтобы по нам на тик
    // перестали стрелять, и командир раздавал задания на захват посреди рубки
    if (fightOnNow) return
    // ...и состав считается ЦЕЛИКОМ, вместе с уже отпущенными командиром: иначе он каждый тик берёт половину
    // ОСТАВШИХСЯ и отпускает ещё, а ушедшие ему не видны — армия распадалась экспоненциально, до двух крипов к
    // концу матча (match29:kite, cmd=0/1090, army=2, 0 очков). Задание раздаётся заново на всех, а не поверх
    val mine = army + (alreadyOut)
    val free = mine.filter { canMove(it) && !it.spawning && hasWeapon(it) }.toMutableList()
    if (free.isEmpty()) return
    // СИММЕТРИЧНАЯ АРМИЯ (v214, решение оператора): «держать в основной армии столько же крипов, сколько у
    // врага, симметрично по типам боевых; лекари всегда остаются в основной армии; остальных отпустить».
    // Считается по ВСЕМ его живым боевым крипам, как он и просил. Лекари сюда не попадают вовсе: `free`
    // отбирается по hasWeapon, а лекарь без оружия в него не входит.
    // ⚠️ Арифметика, о которой надо помнить: при целых армиях 14 на 14 у нас 4 мили и 5 стрелков, у него
    // столько же — симметрия оставляет в ядре все девять и не отпускает НИКОГО. Это ровно то, чего просит
    // оператор, и это безопаснее прежней половины (match29:kite: армия таяла до двух крипов при нуле очков).
    // Флаговый забег при этом ложится на двух наших скаутов и на флаг-цель армии, открытую локализацией вето.
    val core = run {
        // ...и «сколько у врага» значит «сколько у врага ЗДЕСЬ» (v216, см. USE_SYMMETRY_BY_NEAR)
        val near = ctx.combatEnemies.filter { e ->
            e.id in fightPackIds || getRange(e, ctx.ourCentroid) <= MARCH_SAFE
        }
        val hisMelee = near.count { hasMelee(it) }
        val hisRanged = near.count { hasRanged(it) }
        minOf(free.count { hasMelee(it) && !hasRanged(it) }, hisMelee) + minOf(free.count { hasRanged(it) }, hisRanged)
    }
    symCore += core
    symFree += free.size
    var budget = free.size - core
    budgetSum += maxOf(0, budget)
    budgetTicks++
    if (budget <= 0) return
    // флаги — от ближайшего к армии; занятые нами пропускаем
    // ...и только те, которые БРАТЬ МОЖНО: флаг вешает дебафф на ВЛАДЕЛЬЦА (−20 % удару, −25 % лечению, +10 %
    // получаемому урону за штуку), поэтому доктрина паритета держит захват в узде через captureAllowed, и гонка
    // мимо неё — не гонка, а разоружение. Первая редакция это правило игнорировала, и гейт поймал: camp
    // 7 911:17 364, scatter 15 273:24 303 против 22 810:19 091 и 24 268:17 098 без режима
    val wanted = flags.filter { !it.ours && it.occupant?.my != true && captureAllowed(ctx, it) }

        .sortedBy { f -> free.minOf { getRange(it, f.pos) } }
    for (f in wanted) {
        if (budget <= 0) break
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
        if (ctx.runners.any { r -> Memory.runnerFlag[r.id] == f.id }) continue
        val need = if (guarded) 2 else if (loose) RACE_PARTY else 1
        if (budget < need) continue
        val party = free.sortedBy { getRange(it, f.pos) }.take(need)
        if (party.size < need) continue
        // ...и ЯДРО ОБЯЗАНО ОСТАТЬСЯ СИЛЬНЕЕ ЕГО АРМИИ — та же проверка, которой держится отряд (см. USE_DETACH):
        // аннигиляция проигрывает матч при любом счёте, поэтому отпускать можно лишь до тех пор, пока оставшиеся
        // держат паритет. Без неё командир растаскивал армию грубее прежней логики и ронял army-сценарии (гейт 127)
        val without = free.filter { c -> party.none { it.id == c.id } }
        if (without.none { hasWeapon(it) }) break
        if (ourPowerOf(without, armedEnemies) < enemyPowerOf(armedEnemies, without) * PARITY_FLOOR) break
        // ...и ЗАДАНИЕ — это зачисление в захватчики с целью, а не клетка: вооружённый крип, приведённый к флагу
        // как боец, флага НЕ БЕРЁТ (захват делают бегуны), и первая редакция на сценарии kite набрала 0 очков.
        // Командир решает КТО и КУДА, а ведёт и берёт существующий механизм захвата (v160)
        for (c in party) { Memory.cmdDetach.add(c.id); Memory.runnerFlag[c.id] = f.id; free.remove(c); splitAll++; if (fightOnNow) splitFight++ }
        budget -= need
    }
}
