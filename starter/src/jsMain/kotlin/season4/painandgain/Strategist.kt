package season4.painandgain

import screeps.api.Creep

/**
 * СТРАТЕГ (этап 6 переработки; план — docs/pain-and-gain-rework.md, разделы 2 и 8).
 *
 * Одно место, где армия решает, в каком она состоянии. До v241 это были три решения через шестьсот строк друг от друга:
 * постура (`when` из annihilate / objective / evade / retreat) с гистерезисом POSTURE_HOLD и спасением без срока, режим
 * командира (MARCH / RACE / FIGHT) и перезапись постуры режимом боя. Здесь они сведены в [decide]: входы — меры,
 * посчитанные до решения, выход — одно [Decision]. v241 (тождество) сохранил прежние значения и точки применения;
 * v242 применяет решение один раз — постура после перезаписи действует с точки решения — и оценивает прогнозом тот же
 * состав, что планирует
 * командир. Типы постановки [Disposition] / [Squad] / [Mission] объявлены и печатаются прибором `disp=` по нынешним
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
        /** событие тика (прибор evt=, в гистерезис пока не входит — см. decide): наш крип погиб или флаг сменил владельца */
        val event: Boolean,
    )

    class Decision(
        val newPosture: PainAndGain.Posture, val postureTakes: Boolean,
        /** постура после гистерезиса, до перезаписи режимом боя (с v242 не применяется — прибор и история) */
        val posturePre: PainAndGain.Posture, val postureSincePre: Int,
        val cmdMode: PainAndGain.CmdMode, val cmdWhy: String,
        /** постура после перезаписи режимом боя — применяется один раз, в точке решения (v242) */
        val postureFinal: PainAndGain.Posture, val postureSinceFinal: Int,
        val event: Boolean,
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
        val takes = newPosture == i.posture || escape || i.now - i.postureSince >= PainAndGain.POSTURE_HOLD
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
        return Decision(newPosture, takes, pre, sincePre, mode, why, final, sinceFinal, i.event)
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
