package season4.painandgain

import kotlin.math.abs
import screeps.api.ATTACK
import screeps.api.Creep
import screeps.api.EFF_DAMAGE_TAKEN_MODIFIER
import screeps.api.HEAL
import screeps.api.MOVE
import screeps.api.Position
import screeps.api.RANGED_ATTACK
import screeps.api.TOUGH
import screeps.api.get
import screeps.api.getRange

/**
 * ПРОГНОЗ (v239, этап 4 переработки): место для симуляции врага и оценки постановок. Пока сюда перенесено без изменений
 * то, что было: прокат `simulate` (глубина SIM_TICKS, одно число мощи, см. docs/pain-and-gain-verdicts.md по SIM_*),
 * прибор ошибки прогноза и проверенная модель его выбора цели `wallTargetOf` (лекарь в досягаемости → ближайший →
 * меньшие хиты; онлайн-A/B 85–91 %). Целевой интерфейс — EnemyModel.predict / threatAt / evaluate (план, раздел 3).
 */
internal object Forecast {
    // ГЛУБИНА ПРОГНОЗА (v166): прежде мерилась только гейтом (3 и 6 давали 129/131, 4 — 131/131), а теперь измерена
    // ОШИБКА самого прогноза — прибором USE_SIM_ERROR, который сверяет обещание с фактом через SIM_TICKS тиков.
    // Ошибка растёт с глубиной почти линейно: 854 при единице, 1 304 при двойке, 3 855 при четвёрке, — а неверный знак
    // (обещали прибыль, вышел убыток) 33 %, 38 % и 44 %. На четвёрке прогноз не отличает хороший замысел от плохого:
    // ошибка втрое больше самой оценки (та держится около 1 200–1 400). Единица и точнее, и вчетверо дешевле по CPU —
    // а таймаут тика живьём мы уже ловили. Гейт при ней 135/135
    internal const val SIM_TICKS = 1
    /** Накопленный размен в оценке ОТВЕРГНУТ: и как основа (делитель 4), и как поправка (делитель 16) он роняет гейт до
     *  129/131 — m32 army, армия уничтожена на 407–419-м тике. Сумма урона поощряет размен, а мощь через четыре тика
     *  учитывает, ЧЕМ мы останемся; для арены, где аннигиляция проигрывает при любом счёте, верно второе. */
    private const val SIM_EXCHANGE_DIV = 0.0
    /** Масштаб квадратичной оценки: произведение мощи на хиты растёт до сотен тысяч, и делитель держит число в тех же
     *  порядках, что прежняя линейная сумма, — чтобы пороги и печать оставались читаемыми. */
    private const val SIM_POWER_SCALE = 1000.0
    /** РАЗДЕТЫЙ УХОДИТ И В ПРОГНОЗЕ (v166): командир приказывает ему прочь из огня, а в прогоне он стоял и собирал
     *  урон. Замер: средняя ошибка 1 063 против 1 071, доля неверных знаков та же (24 %). Выигрыш мал, но модель
     *  перестала расходиться с приказом. */
    private const val SIM_STRIPPED_OUT = true
    /** ЛЕКАРЬ ЛЕЧИТ ДОСТИЖИМОГО (v166): в прогоне он выбирал самого раненого во ВСЕЙ армии, и если тот был вне
     *  HEAL_RANGE, лечение пропадало впустую — модель противоречила движку. Замер прибором по четырём картам: доля
     *  неверных знаков 24 % против 27 % (знак и решает выбор замысла), средняя ошибка 1 071 против 1 016. Взято ради
     *  знака и ради того, что правило физически верно. */
    private const val SIM_HEAL_IN_RANGE = true
    /** КЛЕТКА ЗАНЯТА (v166): в прогоне крипы проходили сквозь друг друга, в бою нет. Замерено прибором ошибки по
     *  четырём картам: средняя ошибка 1 016 против 1 302 без запрета — точнее на 22 %, доля неверных знаков та же
     *  (27 % против 26 %). Отдельно по картам разброс есть (match28 971 против 854, match35 1 165 против 1 971), и
     *  именно поэтому мерилось по нескольким, а не по одной. */
    private const val SIM_BLOCKED = true
    /** Надбавка его урону внутри симуляции: модель проще живого противника, чей ожидаемый урон за матч 20 000 против
     *  наших 7 400, и без надбавки прогноз выбирает напор чаще, чем следует. */
    private const val SIM_ENEMY_EDGE = 1.0   // 1.3 замерено: 2-6 и 2-6 против 9-23 без надбавки — тот же диапазон
    /** Цена уцелевшего тела ОТВЕРГНУТА замером: 240 (удар мили) роняло гейт до 128/131, 60 — до 129 (m32 army: армия
     *  уничтожена на 395-м). Мощь и так считается только по живым, а добавка за тело делает план трусливым: симуляция
     *  начинает беречь крипов, которых надо разменивать. */
    private const val SIM_ALIVE_WEIGHT = 0.0

    /** Мини-крип симуляции (v138). Хранит ПОЛНЫЙ профиль и число боевых частей: в арене урон снимает части СПЕРЕДИ, а
     *  боевые стоят в начале тела, поэтому мощь падает вместе с хитами — крип с десятью хитами не бьёт как целый.
     *  `front` — сколько хитов приходится на боевые части (по 100 за часть); ниже этого порога оружия уже нет. */
    private class SimC(var x: Int, var y: Int, var hits: Int, val fullMelee: Double, val fullRanged: Double,
                       val fullHeal: Double, val front: Int, val mine: Boolean) {
        val melee: Double get() = fullMelee * armedShare()
        val ranged: Double get() = fullRanged * armedShare()
        val heal: Double get() = fullHeal * armedShare()
        /** Доля уцелевшего оружия: боевые части стоят первыми, значит они гибнут ПОСЛЕДНИМИ по счёту хитов —
         *  пока хитов больше, чем весит хвост, оружие цело; ниже — тает пропорционально. */
        private fun armedShare(): Double {
            val tail = hitsMaxTail
            if (hits <= tail) return 0.0
            return ((hits - tail).toDouble() / front).coerceIn(0.0, 1.0)
        }
        var hitsMaxTail: Int = 0
    }

    /** СИМУЛЯЦИЯ РАЗМЕНА НА НЕСКОЛЬКО ТИКОВ (v138, см. USE_SIMULATION): обе стороны ходят — мы по предложенному плану,
     *  он по модели, снятой с его же записей и с формы `brawl` стенда (мили идут к нашему ближайшему МЯГКОМУ крипу,
     *  стрелки держат три, лекари при самом раненом), — после чего считается урон и лечение по арифметике арены.
     *  Возвращает нашу уцелевшую боевую мощь минус его: аннигиляция проигрывает матч при любом счёте, поэтому
     *  максимизируется мощь, а не размен «крип за крипа». */
    internal fun simulate(mine: List<Creep>, his: List<Creep>, plan: Map<String, Position>, ticks: Int,
                         focus: Creep? = null, intent: PainAndGain.Intent? = null): Double {
        fun mk(c: Creep, ours: Boolean): SimC {
            val pr = InfluenceMap.profileOf(c)
            // боевые части (ATTACK / RANGED_ATTACK / HEAL) стоят в начале тела, MOVE и TOUGH — хвост; урон идёт спереди,
            // так что оружие держится, пока хитов больше веса хвоста, и тает вместе с остатком
            val armedParts = c.body.count { it.type == ATTACK || it.type == RANGED_ATTACK || it.type == HEAL }
            val tailParts = c.body.size - armedParts
            val sc = SimC(c.x, c.y, c.hits, pr.melee, pr.ranged, pr.heal, armedParts * 100, ours)
            sc.hitsMaxTail = tailParts * 100
            return sc
        }
        val us = mine.map { mk(it, true) }
        val them = his.map { mk(it, false) }
        // цель фокуса внутри симуляции: наши бьют её, пока достают, — так план и цель выбираются вместе (v138)
        val focusIdx = focus?.let { f -> his.indexOfFirst { it.id == f.id } } ?: -1
        var strikeAll: () -> Unit = {}
        var healAll: () -> Unit = {}
        val goal = HashMap<Int, Position>()
        mine.forEachIndexed { i, c -> plan[c.id]?.let { goal[i] = it } }
        fun d(a: SimC, b: SimC) = maxOf(abs(a.x - b.x), abs(a.y - b.y))
        // КЛЕТКА ЗАНЯТА (v166): в прогоне крипы проходили сквозь друг друга, а в бою нет — и это вторая по величине
        // причина расхождения после глубины. Шаг разрешается, только если клетка свободна от живых обеих сторон
        val all = us + them
        fun free(x: Int, y: Int, self: SimC) = !SIM_BLOCKED || all.none { it !== self && it.hits > 0 && it.x == x && it.y == y }
        fun step(c: SimC, nx: Int, ny: Int) { if (free(nx, ny, c)) { c.x = nx; c.y = ny } }
        for (t in 0 until ticks) {
            // ПОРЯДОК ИНТЕНТОВ, КАК В ДВИЖКЕ (v139, сверено с документацией Screeps): движение применяется ПОСЛЕДНИМ, а
            // атака считается по позиции ДО него — «the attack still runs from the old coordinates». Значит ударить и
            // отойти в один тик МОЖНО, а подойти и сразу ударить НЕЛЬЗЯ. Прежде симуляция сначала двигала, потом била,
            // то есть считала бой, которого в игре не бывает — и на этой картине выбирала планы
            if (t > 0) { strikeAll(); healAll() }
            // наш ход: ПЕРВЫЙ тик — по плану, дальше раскатка по той же политике, что и у него. Фиксированный план на
            // четыре тика оценивал несуществующий бой: враг маневрирует, а мы шли в клетку, которая уже ничего не значит
            val liveThem0 = them.filter { it.hits > 0 }
            // якорь прогноза — та же медиана живых наших, что и у командира в бою (v159, см. USE_SIM_FIST)
            val liveUs0 = us.filter { it.hits > 0 }
            us.forEachIndexed { i, c ->
                if (c.hits <= 0) return@forEachIndexed
                val g = goal[i]
                // РАСКАТКА ПО ЗАМЫСЛУ (v141): прежняя раскатка провалилась (1-5 и 1-5), потому что после первого тика
                // наши в ней играли по модели ВРАГА — мили на мягкую цель, стрелки на три, — а это не наш замысел.
                // Теперь после первого тика крип продолжает СВОЙ замысел: напор идёт к ближайшему, кайт держит две
                // клетки от его мили, уступка пятится. План в реальности пересчитывается каждый тик, и это ближе к нему,
                // чем стоять четыре тика в одной клетке
                // РАЗДЕТЫЙ УХОДИТ (v166): командир приказывает ему прочь из огня (см. USE_COMMAND_STRIPPED_OUT), а в
                // прогоне он стоял и собирал урон — модель расходилась с поведением
                if (SIM_STRIPPED_OUT && c.melee == 0.0 && c.ranged == 0.0 && c.heal == 0.0 && liveThem0.isNotEmpty()) {
                    val near = liveThem0.minByOrNull { d(c, it) }!!
                    step(c, c.x - (near.x - c.x).coerceIn(-1, 1), c.y - (near.y - c.y).coerceIn(-1, 1))
                    return@forEachIndexed
                }
                if (intent != null && t > 0 && liveThem0.isNotEmpty()) {
                    val near = liveThem0.minByOrNull { d(c, it) }!!
                    val dist = d(c, near)
                    val want = when (intent) {
                        PainAndGain.Intent.PRESS, PainAndGain.Intent.FOCUS -> if (c.melee > 0.0) 1 else RANGED_RANGE
                        PainAndGain.Intent.HOLD, PainAndGain.Intent.KITE -> if (c.melee > 0.0) PainAndGain.MELEE_HOLD_RANGE else RANGED_RANGE
                        PainAndGain.Intent.YIELD -> RANGED_RANGE + 1
                    }
                    // КУЛАК ДЕЙСТВУЕТ И В ПРОГНОЗЕ (v159): в бою крипу нельзя выйти за PainAndGain.FIST_RADIUS от якоря, а в
                    // раскатке было можно — прогноз считал бой, которого не будет, и хвалил замыслы, растаскивающие
                    // армию. Сближение прогноза с настоящим боем — единственный приём, который командира и двигал
                    val nx: Int; val ny: Int
                    if (dist > want) { nx = c.x + (near.x - c.x).coerceIn(-1, 1); ny = c.y + (near.y - c.y).coerceIn(-1, 1) }
                    else if (dist < want) { nx = c.x - (near.x - c.x).coerceIn(-1, 1); ny = c.y - (near.y - c.y).coerceIn(-1, 1) }
                    else { nx = c.x; ny = c.y }
                    c.x = nx; c.y = ny
                    return@forEachIndexed
                }
                if (g != null) {
                    if (c.x != g.x || c.y != g.y) step(c, c.x + (g.x - c.x).coerceIn(-1, 1), c.y + (g.y - c.y).coerceIn(-1, 1))
                    return@forEachIndexed
                }
                if (liveThem0.isEmpty()) return@forEachIndexed
                if (c.melee > 0.0) {
                    val soft = liveThem0.filter { it.melee <= 0.0 }.minByOrNull { d(c, it) } ?: liveThem0.minByOrNull { d(c, it) }!!
                    if (d(c, soft) > 1) { c.x += (soft.x - c.x).coerceIn(-1, 1); c.y += (soft.y - c.y).coerceIn(-1, 1) }
                } else if (c.ranged > 0.0) {
                    val near = liveThem0.minByOrNull { d(c, it) }!!
                    val dist = d(c, near)
                    if (dist < RANGED_RANGE) { c.x -= (near.x - c.x).coerceIn(-1, 1); c.y -= (near.y - c.y).coerceIn(-1, 1) }
                    else if (dist > RANGED_RANGE) { c.x += (near.x - c.x).coerceIn(-1, 1); c.y += (near.y - c.y).coerceIn(-1, 1) }
                } else {
                    val hurt = us.filter { it.hits > 0 && it !== c }.minByOrNull { it.hits } ?: return@forEachIndexed
                    if (d(c, hurt) > 1) { c.x += (hurt.x - c.x).coerceIn(-1, 1); c.y += (hurt.y - c.y).coerceIn(-1, 1) }
                }
            }
            // его ход: мили к нашему ближайшему мягкому, стрелки держат три, лекари к самому раненому своему
            val liveUs = us.filter { it.hits > 0 }
            if (liveUs.isEmpty()) break
            for (e in them) {
                if (e.hits <= 0) continue
                if (e.melee > 0.0) {
                    // проба модели (v166): «к ближайшему МЯГКОМУ» или «к ближайшему вообще» — что ближе к его настоящему
                    // поведению, теперь решает не догадка, а ошибка прогноза (см. USE_SIM_ERROR)
                    val soft = liveUs.filter { it.melee <= 0.0 }.minByOrNull { d(e, it) } ?: liveUs.minByOrNull { d(e, it) }!!
                    if (d(e, soft) > 1) step(e, e.x + (soft.x - e.x).coerceIn(-1, 1), e.y + (soft.y - e.y).coerceIn(-1, 1))
                } else if (e.ranged > 0.0) {
                    val near = liveUs.minByOrNull { d(e, it) }!!
                    val dist = d(e, near)
                    // проба (v166): пятится ли его стрелок, когда мы ближе трёх. В записях он чаще СТОИТ и стреляет —
                    // прибор ошибки прогноза и рассудит
                    if (dist < RANGED_RANGE) step(e, e.x - (near.x - e.x).coerceIn(-1, 1), e.y - (near.y - e.y).coerceIn(-1, 1))
                    else if (dist > RANGED_RANGE) step(e, e.x + (near.x - e.x).coerceIn(-1, 1), e.y + (near.y - e.y).coerceIn(-1, 1))
                } else {
                    val hurt = them.filter { it.hits > 0 && it !== e }.minByOrNull { it.hits } ?: continue
                    if (d(e, hurt) > 1) step(e, e.x + (hurt.x - e.x).coerceIn(-1, 1), e.y + (hurt.y - e.y).coerceIn(-1, 1))
                }
            }
            // урон: мили по смежному, стрелок — ВЕЕРОМ, когда целей много, иначе одиночным. Веер бьёт всех в трёх с
            // убыванием 10/4/1 за часть, и без него симуляция недооценивала как раз того противника, который им живёт
            // (MetalicaX#11 — 63 веера за матч против 37 у #10 и наших 12), и потому охотно сбивала армию в кучу
            // ПОЛУЧАЕМЫЙ УРОН ТОЖЕ ПО МОДИФИКАТОРУ (v159): profileOf уже множит НАШ удар и лечение на эффекты флагов,
            // а входящий урон симуляция вычитала как есть. Между тем флаг вешает на владельца +10 % получаемого урона
            // (EFF_DAMAGE_TAKEN_MODIFIER, замерено в живых effects), и при двух-трёх флагах прогноз ошибался в
            // выживаемости на десятки процентов — в ту сторону, которая как раз и решает, вступать в размен или нет
            val takenUs = InfluenceMap.takenOf(mine.first())
            val takenThem = if (his.isNotEmpty()) InfluenceMap.takenOf(his.first()) else 1.0
            fun strikeSide(from: List<SimC>, to: List<SimC>) {
                val k = if (to === us) takenUs else takenThem
                for (a in from) {
                    if (a.hits <= 0) continue
                    val adj = to.filter { it.hits > 0 && d(a, it) <= 1 }
                    val focusT = if (from === us && focusIdx >= 0 && focusIdx < them.size) them[focusIdx].takeIf { it.hits > 0 } else null
                    if (a.melee > 0.0 && adj.isNotEmpty())
                        (adj.firstOrNull { it === focusT } ?: adj.minByOrNull { it.hits }!!).let { it.hits -= (a.melee * k).toInt() }
                    if (a.ranged > 0.0) {
                        val inRange = to.filter { it.hits > 0 && d(a, it) <= RANGED_RANGE }
                        if (inRange.isEmpty()) continue
                        // веер выгоден с двух целей: доля 1.0 / 0.4 / 0.1 по дистанции, как считает stackMul арены
                        val massValue = inRange.sumOf { t -> when (d(a, t)) { 0, 1 -> 1.0; 2 -> 0.4; else -> 0.1 } }
                        if (massValue > 1.0) for (t in inRange) {
                            val share = when (d(a, t)) { 0, 1 -> 1.0; 2 -> 0.4; else -> 0.1 }
                            t.hits -= (a.ranged * share * k).toInt()
                        } else (inRange.firstOrNull { it === focusT } ?: inRange.minByOrNull { it.hits }!!)
                            .let { it.hits -= (a.ranged * k).toInt() }
                    }
                }
            }
            strikeAll = { strikeSide(us, them); strikeSide(them, us) }
            healAll = {
                for (side in listOf(us, them)) for (h in side) {
                    if (h.hits > 0 && h.heal > 0.0) {
                        // ...и цель выбирается среди ДОСТИЖИМЫХ (v166): прежде брался самый раненый во всей армии, и
                        // если он был вне HEAL_RANGE, лечение в прогнозе пропадало впустую — настоящий лекарь лечит
                        // того, до кого дотягивается
                        val hurt = if (SIM_HEAL_IN_RANGE) side.filter { it.hits > 0 && it !== h && d(h, it) <= HEAL_RANGE }.minByOrNull { it.hits }
                            else side.filter { it.hits > 0 && it !== h }.minByOrNull { it.hits }
                        if (hurt != null) {
                            val dd = d(h, hurt)
                            if (dd <= 1) hurt.hits += h.heal.toInt() else if (dd <= HEAL_RANGE) hurt.hits += (h.heal / 3).toInt()
                        }
                    }
                }
            }
            // последний тик: удары после последнего движения, иначе ход впустую
            if (t == ticks - 1) { strikeAll(); healAll() }
        }
        // оценка: НАКОПЛЕННЫЙ размен, а не только конечная мощь. При равных армиях разница мощей через четыре тика мала
        // и тонет в шуме — планы получались неразличимы; сумма нанесённого и полученного за все тики устойчивее и
        // отвечает на тот вопрос, который задаётся: чей размен лучше, если пойти этим путём (v138)
        // ОЦЕНКА ПО КВАДРАТИЧНОМУ ЗАКОНУ (v140, Ланчестер): сила армии растёт не как сумма, а как ПРОИЗВЕДЕНИЕ огневой
        // мощи на живучесть — n тел, стреляющих d уроном, стоят n·d·n·hp, потому что каждое лишнее тело и бьёт, и
        // принимает. Прежняя линейная сумма профилей давала почти одинаковые числа для планов, расходящихся на четыре
        // тика, и выбор тонул в шуме; произведение разводит их, потому что маленький перевес в размене возводится в
        // квадрат — ровно то, чем блоб нас и бьёт
        fun dps(side: List<SimC>) = side.filter { it.hits > 0 }.sumOf { it.melee + it.ranged + it.heal / 3.0 }
        fun body(side: List<SimC>) = side.filter { it.hits > 0 }.sumOf { it.hits.toDouble() }
        fun power(side: List<SimC>) = dps(side) * body(side) / SIM_POWER_SCALE
        return (power(us) - power(them))
    }

    internal val simPending = HashMap<Int, Pair<Double, Double>>()  // тик сверки → (обещано, разность на момент прогноза)
    internal var simErrSum = 0.0                    // сумма модулей ошибки прогноза (v166)
    internal var simErrN = 0
    internal var simErrWrongSign = 0                // сколько раз прогноз обещал прибыль, а вышел убыток

    /** Кого возьмёт его ствол по измеренному правилу (v229, см. USE_HEAL_WALL_ADDRESSED): среди наших в досягаемости reach —
     *  чистый лекарь первым, иначе ближайший, при равной дальности — с меньшими хитами. */
    internal fun wallTargetOf(shooter: Creep, live: List<Creep>, reach: Int): Creep? {
        var best: Creep? = null; var bestKey = Double.MAX_VALUE; var bestHealer = false
        for (f in live) {
            val d = getRange(shooter, f)
            if (d > reach) continue
            val healer = !PainAndGain.hasWeapon(f) && PainAndGain.hasHeal(f)
            if (bestHealer && !healer) continue
            val key = d * 100000.0 + f.hits
            if ((healer && !bestHealer) || key < bestKey) { best = f; bestKey = key; bestHealer = healer }
        }
        return best
    }

    /** ПОЗИЦИИ ВРАГА НА t+1 (v244, план: Forecast.predict): та же модель шага, что в прокате [simulate] — мили к ближайшему
     *  мягкому нашему, стрелок держит RANGED_RANGE, лекарь к самому раненому своему; занятая клетка и стена — стоит. */
    fun predictCells(his: List<Creep>, ours: List<Creep>): HashMap<String, Position> {
        val out = HashMap<String, Position>()
        val occupied = HashSet<Int>()
        for (c in ours) occupied.add(c.x * 100 + c.y)
        for (e in his) occupied.add(e.x * 100 + e.y)
        val liveUs = ours.filter { it.hits > 0 }
        for (e in his) {
            if (e.hits <= 0) continue
            val q = InfluenceMap.profileOf(e)
            var tx = e.x; var ty = e.y
            if (liveUs.isNotEmpty()) {
                if (q.melee > 0.0) {
                    val soft = liveUs.filter { InfluenceMap.profileOf(it).melee <= 0.0 }.minByOrNull { getRange(e, it) } ?: liveUs.minByOrNull { getRange(e, it) }!!
                    if (getRange(e, soft) > 1) { tx = e.x + (soft.x - e.x).coerceIn(-1, 1); ty = e.y + (soft.y - e.y).coerceIn(-1, 1) }
                } else if (q.ranged > 0.0) {
                    val near = liveUs.minByOrNull { getRange(e, it) }!!
                    val dist = getRange(e, near)
                    if (dist < RANGED_RANGE) { tx = e.x - (near.x - e.x).coerceIn(-1, 1); ty = e.y - (near.y - e.y).coerceIn(-1, 1) }
                    else if (dist > RANGED_RANGE) { tx = e.x + (near.x - e.x).coerceIn(-1, 1); ty = e.y + (near.y - e.y).coerceIn(-1, 1) }
                } else {
                    val hurt = his.filter { it.hits > 0 && it.id != e.id }.minByOrNull { it.hits }
                    if (hurt != null && getRange(e, hurt) > 1) { tx = e.x + (hurt.x - e.x).coerceIn(-1, 1); ty = e.y + (hurt.y - e.y).coerceIn(-1, 1) }
                }
            }
            tx = tx.coerceIn(0, 99); ty = ty.coerceIn(0, 99)
            val key = tx * 100 + ty
            if (tx != e.x || ty != e.y) {
                if (key in occupied || DistanceMap.isTerrainWall(tx, ty)) { tx = e.x; ty = e.y }
                else { occupied.remove(e.x * 100 + e.y); occupied.add(key) }
            }
            out[e.id] = InfluenceMap.cell(tx, ty)
        }
        return out
    }

    /** АДРЕСНАЯ ОПАСНОСТЬ t+1 (v244, план: Forecast.threatAt): урон тех его стволов, которые, шагнув по модели ([predictCells]),
     *  выберут именно крипа c, стоящего в клетке p, — по проверенной модели его выбора цели (лекарь в досягаемости первым,
     *  иначе ближайший, при равенстве меньшие хиты — та же, что [wallTargetOf]). Остальные наши — на своих клетках.
     *  Дешёвая замена «симуляции по клетке»: 14 крипов × 9 клеток × его стволы. */
    fun threatAt(pred: Map<String, Position>, c: Creep, p: Position, ours: List<Creep>, his: List<Creep>): Double {
        var sum = 0.0
        for (e in his) {
            if (e.hits <= 0) continue
            val q = InfluenceMap.profileOf(e)
            if (q.melee <= 0.0 && q.ranged <= 0.0) continue
            val pe = pred[e.id] ?: continue
            val reach = if (q.ranged > 0.0) RANGED_RANGE else 1
            if (maxOf(abs(p.x - pe.x), abs(p.y - pe.y)) > reach) continue
            var bestKey = Double.MAX_VALUE; var chosenMe = false; var healerSeen = false
            for (f in ours) {
                if (f.hits <= 0) continue
                val fx = if (f.id == c.id) p.x else f.x
                val fy = if (f.id == c.id) p.y else f.y
                val d = maxOf(abs(fx - pe.x), abs(fy - pe.y))
                if (d > reach) continue
                val healer = PainAndGain.hasHeal(f) && !PainAndGain.hasWeapon(f)
                if (healer && !healerSeen) { healerSeen = true; bestKey = Double.MAX_VALUE; chosenMe = false }
                if (healerSeen && !healer) continue
                val key = d * 100000.0 + f.hits
                if (key < bestKey) { bestKey = key; chosenMe = f.id == c.id }
            }
            if (chosenMe) sum += (if (q.ranged > 0.0) q.ranged else q.melee) * InfluenceMap.takenOf(c)
        }
        return sum
    }
}
