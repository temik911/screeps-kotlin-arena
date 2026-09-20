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
 * БОЙ (v255, этап 10 переработки; план — раздел 1, Tactician: «клетка = argmax по соседям суммы термов», «цели огня и
 * лечения — задача назначения отряда»). Здесь раздача клеток в бою командира `commandFight` (оценки `scoreMelee` /
 * `scoreRanged` / `scoreHeal`, кулак `Formation.fist`, проходы retreat → straggler → melee → ranged → healer → holders →
 * stripped → catchall), поле цели `ensureGoalField`, назначение огня и лечения (`commandFire`, `commandHeal`) и
 * исполнители удара, выстрела и лечения (`strike`, `shoot`, `healAndShoot`). Перенесено из объекта `PainAndGain`
 * дословно расширениями; покрипная лестница шага — в Tactician.kt.
 */

/** Удар мили: фокус-цель вплотную, иначе самый раненый сосед. */
internal fun PainAndGain.strike(creep: Creep, enemyCreeps: List<Creep>, focusTarget: Creep?, focusOrder: List<Creep>) {
    if (!hasMelee(creep)) return
    val adjacent = enemyCreeps.filter { creep.getRangeTo(it) <= 1 }
    val focusDying = focusTarget != null && creep.getRangeTo(focusTarget) <= 1 && focusTarget.hits <= InfluenceMap.profileOf(creep).melee
    val ordered = fireOf[creep.id]?.let { id -> adjacent.firstOrNull { it.id == id } }
    val target: Creep? = when {
        // приказ командира и для удара (v161): цель назначена по всей армии, а не по тому, кто оказался рядом.
        // Исключение одно — крип, которого мы ДОБИВАЕМ этим ударом: добить дороже, чем исполнить приказ
        focusDying -> focusTarget
        ordered != null -> ordered
        focusTarget != null && creep.getRangeTo(focusTarget) <= 1 -> focusTarget
        adjacent.isNotEmpty() -> focusOrder.firstOrNull { creep.getRangeTo(it) <= 1 } ?: adjacent.minByOrNull { it.hits }
        else -> null
    }
    target?.let { Executor.attack(creep, it); lastFireTick = getTicks(); strikesAt[it.id] = (strikesAt[it.id] ?: 0) + 1 }
}

internal fun PainAndGain.healAndShoot(active: List<Creep>, allies: List<Creep>, enemyCreeps: List<Creep>, focusTarget: Creep?, focusOrder: List<Creep>) {
    shotsAt.clear()
    strikesAt.clear()
    val healDone = HashMap<String, Int>()
    val incoming = HashMap<String, Int>()
    // подтверждённый входящий (v233, см. USE_HEAL_BY_DEFICIT): адресный огонь этого тика или потеря прошлого
    fun confirmed(target: Creep) = (addressedDmg[target.id] ?: 0.0) > 0.0 || (lostTick[target.id] ?: 0) > 0
    // ...И ОЖИДАЕМЫЙ УРОН — АДРЕСНЫЙ (v292). Нужда была «недостача + весь урон, который его стволы МОГУТ положить в клетку»
    // (damageAt), и полный крип у фронта выглядел нуждающимся, хотя его стволы бьют другого: аудит 112 игр (v289–v290,
    // Opus) — наше лечение полных и не битых в этот тик 16,7 хита за тик контакта против его 0,0, не той цели 9,9 против
    // 3,7 (p < 0,001); против ●ω<♥♪#6, который бьёт наименьшую долю хитов в досягаемости, полный крип — не его цель, пока
    // есть раненые. Ожидаемый урон берётся из предсказателя его выбора (rotateByFocus: две модели, сверка с фактом, 92 %
    // попаданий против ●ω) — той модели, что попадает чаще; пока сверок меньше окна — прежний damageAt
    fun expectedOn(target: Creep): Int = focusPredDmg?.let { (it[target.id] ?: 0.0).toInt() }
        ?: InfluenceMap.damageAt(target.x, target.y, enemyCreeps).toInt()
    fun need(target: Creep): Int {
        val deficit = target.hitsMax - target.hits
        val expected = incoming.getOrPut(target.id) { expectedOn(target) }
        return deficit + expected - (healDone[target.id] ?: 0)
    }
    // нужда по подтверждённому — для прибора и для переназначения соседу (не зависит от тумблера)
    fun needConfirmed(target: Creep): Int {
        val deficit = target.hitsMax - target.hits
        val expected = if (confirmed(target)) maxOf((addressedDmg[target.id] ?: 0.0).toInt(), lostTick[target.id] ?: 0) else 0
        return deficit + expected - (healDone[target.id] ?: 0)
    }
    fun book(target: Creep, amount: Int) {
        hfullAll.n++; if (target.hits >= target.hitsMax) hfullN.n++
        hoverSum.n += maxOf(0, amount - maxOf(0, needConfirmed(target))); hdelivSum.n += amount
        healDone[target.id] = (healDone[target.id] ?: 0) + amount
    }
    // под огнём (v109): терявший хиты в прошлый тик — впереди любого дефицита (см. USE_HEAL_UNDER_FIRE), но только пока не покрыт
    // его запас «дефицит + HEAL_FIRE_ROOM × потеря»: первый срез слал всех троих на потерявшего 60 (216 лечения в дефицит 60),
    // и призрак 273 терял на входе 2710 против 1688 у v108 — покрытый возвращается к обычному выбору по need
    // ...ранг под огнём (HEAL_FIRE_RANK / HEAL_FIRE_ROOM) выключен давно: функция возвращала дефицит первой строкой, всё
    // ниже было недостижимо — снято в v261, история правила выше
    fun rank(target: Creep): Int = need(target)
    for (creep in active) {
        strike(creep, enemyCreeps, focusTarget, focusOrder)
        val healParts = creep.body.count { it.type == HEAL && it.hits > 0 }
        if (healParts > 0) {
            val candidates = allies.filter { !it.spawning && need(it) > 0 && creep.getRangeTo(it) <= HEAL_RANGE }
            // приказ командира первым (v162): он назначил пациента, зная, кого враг добивает и кого лечение спасёт
            val ordered = healOf[creep.id]?.let { id -> candidates.firstOrNull { it.id == id } }
            // ...И ПРИКАЗ ДЕЙСТВУЕТ НА ВСЕЙ ЛЕЧЕБНОЙ ДАЛЬНОСТИ (v183, оператор: «не должно быть ничего, что идёт
            // мимо командира»). Прежде назначенный пациент брался, только если он ВПЛОТНУЮ; иначе выбор перехватывал
            // местный ранг соседей — и приказ отбрасывался тем, что рядом просто кто-то стоит
            // СТЕНА ЛЕЧЕНИЯ (v228, см. USE_HEAL_WALL): удержимую жертву лечит каждый лекарь в дальности, вплотную — полностью
            if (victimSaveable) hwallHealsAll.n++
            val wallTarget = if (victimSaveable) victimNow?.takeIf { v -> !v.spawning && creep.getRangeTo(v) <= HEAL_RANGE } else null
            if (wallTarget != null) {
                if (creep.getRangeTo(wallTarget) <= 1) {
                    hwallHeals.n++
                    Executor.heal(creep, wallTarget)
                    book(wallTarget, InfluenceMap.modified(creep, EFF_HEAL_MODIFIER, healParts * HEAL_POWER.toDouble()).toInt())
                    shoot(creep, enemyCreeps, focusTarget, focusOrder)
                    continue
                }
                // ДАЛЬНЕЕ ЛЕЧЕНИЕ ЖЕРТВЫ УСТУПАЕТ ЛЕЧЕНИЮ ВПЛОТНУЮ, КОТОРОЕ СТОИТ БОЛЬШЕ (v270, наблюдение оператора: «хиллеры
                // очень часто хилят рэнжхиллом вместо контактного»). Стена ставила жертву раньше всякого соседа, и лекарь,
                // стоящий вплотную к раненому своему, лечил её из трёх клеток: по реплеям серии v263 и рук v266–v267 в окнах
                // стены раненый сосед пропускается в 97–100 % дальних лечений, а жертва без этого лечения не умирала — в тот
                // же тик в 0,0–0,1 %, за пять тиков в 0,1–0,3 %. Потеря — 2,5–3,1 доставленного лечения на лекаре-тик, 9–11 %
                // всего нашего (оракул «вылечи соседа с наибольшим min(12·H, дефицит + урон тика), иначе дальним»).
                // Выбор теперь по одной мере — доставленному: min(лечение с дистанции, нужда цели), где нужда та же, что у
                // всего лечения (дефицит + ожидаемый урон − уже назначенное); жертва вплотную лечится как прежде. Сосед —
                // только раненый: снять фильтр v183 и лечить полного под огнём по оценке бота — ноль выигрыша при 10–16 %
                // лечений впустую (тот же разбор)
                val nearPower = InfluenceMap.modified(creep, EFF_HEAL_MODIFIER, healParts * HEAL_POWER.toDouble())
                val farPower = InfluenceMap.modified(creep, EFF_HEAL_MODIFIER, healParts * RANGED_HEAL_POWER.toDouble())
                val mate = candidates.filter { it.id != wallTarget.id && creep.getRangeTo(it) <= 1 && it.hitsMax - it.hits > 0 }
                    .maxByOrNull { minOf(nearPower, need(it).toDouble()) }
                hwallFar.n++
                if (mate != null && minOf(nearPower, need(mate).toDouble()) > minOf(farPower, need(wallTarget).toDouble())) {
                    hwallYield.n++
                    Executor.heal(creep, mate)
                    book(mate, nearPower.toInt())
                    shoot(creep, enemyCreeps, focusTarget, focusOrder)
                    continue
                }
                hwallHeals.n++
                Executor.rangedHeal(creep, wallTarget)
                book(wallTarget, farPower.toInt())
                continue
            }
            // ...и СОСЕДСТВО НЕ ВАЖНЕЕ РАНЫ (v183, оператор: «лекари лечат себя фулловыми, хотя могли бы лечить
            // того, кто под огнём»). Ближняя ветка бралась раньше дальней всегда, поэтому полный сосед — включая
            // самого лекаря — обходил раненого в двух клетках. Замер разгрома 3d9532: 8 лечений из 55 (14 %) ушли
            // в цель на полных хитах, не получившую в этот тик урона, — у него таких 0 из 172; в трёх из этих
            // случаев рядом стоял крип с потерей 900–1 060. Полный сосед лечится, только если раненых нет вовсе
            val anyWounded =  candidates.any { it.hitsMax - it.hits > 0 }
            val closeTarget0 = candidates.filter { creep.getRangeTo(it) <= 1 && (!anyWounded || it.hitsMax - it.hits > 0) }
                .maxByOrNull { rank(it) }
            val closeTarget = closeTarget0
            if (closeTarget != null) {
                Executor.heal(creep, closeTarget)
                book(closeTarget, InfluenceMap.modified(creep, EFF_HEAL_MODIFIER, healParts * HEAL_POWER.toDouble()).toInt())
                shoot(creep, enemyCreeps, focusTarget, focusOrder)
                continue
            }
            val farTarget = ordered?.takeIf { creep.getRangeTo(it) <= HEAL_RANGE }
                ?: candidates.filter { it.hitsMax - it.hits > 0  }.maxByOrNull { rank(it) }
            if (farTarget != null) {
                Executor.rangedHeal(creep, farTarget)
                book(farTarget, InfluenceMap.modified(creep, EFF_HEAL_MODIFIER, healParts * RANGED_HEAL_POWER.toDouble()).toInt())
                continue
            }
        }
        shoot(creep, enemyCreeps, focusTarget, focusOrder)
    }
    // лечение, назначенное за тик, — для истины предсказателя его фокуса на следующем тике (v276, см. rotateByFocus)
    Memory.healGiven.clear()
    Memory.healGiven.putAll(healDone)
    damageBooked.clear()
    val most = shotsAt.values.maxOrNull() ?: 0
    if (most > 0) {
        concSum.n += most; concTicks.n++
        concAll.n += most; concAllTicks.n++
        if (most > concMax.n) concMax.n = most
    }
    // ПЕРЕКРЫТИЕ (v391, прибор ovl=). `conc` считает, сколько выстрелов ЛЕГЛО в одну цель, то есть выбор; этот прибор
    // считает, сколько их МОГЛО лечь — сколько наших стрелков физически достаёт лучшую его цель. Разница и есть
    // предмет: восемь правок боя отвергнуты замером, и последняя (v389) показала, что выбор цели ничего не решает,
    // пока стволы не сходятся. Разбор давал 15–26 % тиков с тремя стволами на цели и 2–7 % с четырьмя — но то был
    // срез по реплеям пяти матчей; здесь величина меряется в каждом бою и попадает в строку тика
    run {
        val shooters = allies.filter { hasRanged(it) && !it.spawning }
        val best = enemyCreeps.filter { it.hits > 0 }.maxOfOrNull { e -> shooters.count { it.getRangeTo(e) <= RANGED_RANGE } } ?: 0
        if (shooters.isNotEmpty() && enemyCreeps.isNotEmpty()) {
            ovlSum.n += best; ovlTicks.n++
            if (best >= 3) ovlThree.n++
            if (best >= 4) ovlFour.n++
        }
    }
    // ...и то же ДЛЯ МИЛИ (v221, см. mconcAll): удар не кладёт ничего в `shotsAt`, поэтому `conc` про мили
    // слеп — сложены ли четыре удара в одну цель, не измерял ни один прибор
    val mostStrikes = strikesAt.values.maxOrNull() ?: 0
    if (mostStrikes > 0) {
        mconcAll.n += mostStrikes; mconcTicks.n++
        if (mostStrikes > mconcMax.n) mconcMax.n = mostStrikes
    }
}

internal fun PainAndGain.shoot(creep: Creep, enemyCreeps: List<Creep>, focusTarget: Creep?, focusOrder: List<Creep>) {
    if (!hasRanged(creep)) return
    val creepsInRange = enemyCreeps.filter { creep.getRangeTo(it) <= RANGED_RANGE }
    if (creepsInRange.isEmpty()) return
    val combatInRange = creepsInRange.filter { c -> val p = InfluenceMap.profileOf(c); p.melee + p.ranged + p.heal > 0.0 }
    val massPool = if (combatInRange.isNotEmpty()) combatInRange else creepsInRange
    val massValue = massPool.sumOf { InfluenceMap.rangedRate(creep.getRangeTo(it)) }
    // против армии с лекарями — только фокус: веер размазывает урон по трём-пяти целям, и три лекаря (216 в тик)
    // вылечивают его целиком, пока враг сосредоточенно снимает 540 в тик с одного нашего (стенд sleeper: наш чистый
    // урон 200 в тик против 540). Веер — когда врагу нечем лечить или он даёт не меньше двух с половиной выстрелов
    val enemyHeals = enemyCreeps.any { InfluenceMap.profileOf(it).heal > 0.0 }
    // ...и ВЕЕР ОТСТУПАЕТ ПЕРЕД СОШЕДШИМИСЯ СТВОЛАМИ (v218, см. focusBreakableNow). Три его крипа вплотную
    // дают massValue = 3.0, то есть порог 2.5 берётся сам собой — и ветка веера игнорирует `focusTarget`
    // ЦЕЛИКОМ. Пока стволы размазаны, это верно: веер бьёт по всем. Но ровно в тот момент, когда фокус стал
    // пробиваемым тем, что до него дотягивается (это и есть «четыре-пять стволов» из записанного порога),
    // веер развёл бы их обратно и отдал бы цель его лекарям. Побочно ветка чинит и ПРИБОР: веерный выстрел
    // не кладёт ничего в `shotsAt`, поэтому такие тики не входили в `conc` даже знаменателем (см. concfan)
    if (massValue > (if (enemyHeals) 2.5 else 1.0)) {
        Executor.rangedMassAttack(creep); lastFireTick = getTicks(); fanShots.n++; fireShots.n++
    } else {
        // ПЕРЕБОЙ (v140, приём из литературы по микроменеджменту RTS): выстрел в цель, которая и так умрёт от уже
        // назначенного в этом тике урона, пропадает целиком. `damageBooked` считает, сколько по ней уже расписано
        // нашими за тик; если этого хватает с учётом её лечения, стрелок переходит к следующей цели по ранжиру
        fun booked(t: Creep) = damageBooked[t.id] ?: 0.0
        // фокус-цель вне дальности — добиваем самого раненого боевого в дальности (безоружных — в последнюю очередь)
        val ordered = fireOf[creep.id]?.let { id -> enemyCreeps.firstOrNull { it.id == id } }
        val target = when {
            // приказ командира — первым: он назначал цель, зная всю армию и всё, что до цели дотягивается (v161)
            ordered != null && creep.getRangeTo(ordered) <= RANGED_RANGE  -> ordered
            focusTarget != null && creep.getRangeTo(focusTarget) <= RANGED_RANGE  -> focusTarget
            else -> focusOrder.firstOrNull { creep.getRangeTo(it) <= RANGED_RANGE  }
                ?: massPool.minByOrNull { it.hits }
        }
        target?.let {
            Executor.rangedAttack(creep, it); shotsAt[it.id] = (shotsAt[it.id] ?: 0) + 1; lastFireTick = getTicks(); fireShots.n++
            damageBooked[it.id] = booked(it) + InfluenceMap.profileOf(creep).ranged * InfluenceMap.takenOf(it)
        }
    }
}

internal fun PainAndGain.commandFire(army: List<Creep>, enemies: List<Creep>, focus: Creep?, order: List<Creep>,
                        out: MutableMap<String, String>) {
    out.clear()
    val shooters = army.filter { hasWeapon(it) && !it.spawning }
    if (shooters.isEmpty() || enemies.isEmpty()) return
    val live = enemies.filter { it.hits > 0 }
    fun reach(c: Creep, e: Creep) = if (hasRanged(c)) c.getRangeTo(e) <= RANGED_RANGE else c.getRangeTo(e) <= 1
    // ...кого добиваем ЭТИМ тиком: залп достающих минус лечение, дотягивающееся до цели
    // РАЗОРУЖЁННЫЙ — НЕ ЦЕЛЬ (v178, оператор: «наши крипы во время боя зачем-то начали добивать разоружённых,
    // которые в текущий момент нам никак не вредят, вместо того чтобы снимать хиты тем, кто наносит урон прямо
    // сейчас»). Добиваемость считалась по ХИТАМ, а у разоружённого их мало — он и выходил лучшей целью, тогда как
    // не бьёт вовсе. Огонь идёт по тем, кто ещё вооружён; безоружные оставляются на потом
    // ...И СКАУТ У ФЛАГА (v214): без этого приказ командира расходится с фокусом — `order.firstOrNull { ... in
    // dangerous }` ниже отфильтровал бы его обратно, и стрелок, которому фокус назначил скаута, молчал бы.
    // Распоряжение v178 не тронуто: остов сюда по-прежнему не попадает (у него потенциал есть, у скаута нет)
    val scoutsHere = live.filter { e ->
        scoutFoe(e) && flagsNow.any { !it.ours && getRange(e, it.pos) <= 1 } }
    val dangerous = live.filter { e -> InfluenceMap.profileOf(e).let { it.melee + it.ranged + it.heal > 0.0 } } + scoutsHere
    val pool = if (dangerous.isNotEmpty()) dangerous else live
    val killable = pool.filter { e ->
        val burst = shooters.filter { reach(it, e) }.sumOf { c ->
            val pr = InfluenceMap.profileOf(c)
            (if (hasRanged(c)) pr.ranged else pr.melee) * InfluenceMap.takenOf(e)
        }
        val cover = enemies.sumOf { h ->
            val pr = InfluenceMap.profileOf(h)
            val d = h.getRangeTo(e)
            if (pr.heal <= 0.0 || d > HEAL_RANGE) 0.0 else if (d <= 1) pr.heal else pr.heal / 3.0
        }
        burst >= e.hits + cover
    }.minByOrNull { it.hits }
    for (c in shooters) {
        // ПРЕСЛЕДОВАТЕЛЬ СТРЕЛЯЕТ В СВОЙ ОСТОВ (v211). Общее правило «разоружённый — не цель» поставил оператор
        // в v178 и оно остаётся в силе для ВСЕЙ армии: пока идёт бой, огонь идёт по тем, кто бьёт сейчас.
        // Но преследователь для того и отделён, чтобы добить одного конкретного, — и назначение ему делается
        // ЗДЕСЬ, потому что commandFire начинается с out.clear() и всякий приказ, поставленный раньше, стирает.
        // Первая редакция ставила приказ в assignChase, и он не доживал до выстрела: крип догонял и молчал
        val chased = Memory.chaseTarget[c.id]
        if (chased != null && chased.hits > 0 && reach(c, chased)) { out[c.id] = chased.id; continue }
        val t = when {
            killable != null && reach(c, killable) -> killable
            focus != null && focus.hits > 0 && reach(c, focus) -> focus
            else -> order.firstOrNull { reach(c, it) && it.hits > 0 && (it in dangerous) }
                ?: pool.filter { reach(c, it) }.minByOrNull { it.hits }
                ?: live.filter { reach(c, it) }.minByOrNull { it.hits }
        }
        if (t != null) out[c.id] = t.id
    }
}

/** ЛЕЧЕНИЕ ПО ПРИКАЗУ (v162, оператор: перевести всё на командира). Лекарь выбирал пациента сам — по дефициту
 *  хитов плюс ожидаемый входящий урон, — и не знал того, что командир уже посчитал: кого враг ДОБИВАЕТ этим тиком.
 *  Здесь пациент назначается поимённо: сперва тот, кого убивают сейчас и кого лечение ещё СПАСАЕТ (иначе это
 *  лечение в труп), и на него ставится ровно столько лекарей, сколько нужно, чтобы перекрыть входящий; остальные
 *  идут по наибольшей нужде. Порядок лекарей — от дальнего к ближнему, чтобы ближний добирал остаток. */
internal fun PainAndGain.commandHeal(army: List<Creep>, enemies: List<Creep>, out: MutableMap<String, String>) {
    out.clear()
    val healers = army.filter { hasHeal(it) && !it.spawning }
    if (healers.isEmpty()) return
    // ...ожидаемый урон — адресный, по той же модели его выбора, что у исполнителя (v292, см. healAndShoot.need)
    val pred = focusPredDmg
    fun expected(m: Creep) = pred?.let { it[m.id] ?: 0.0 } ?: (InfluenceMap.damageAt(m.x, m.y, enemies) * InfluenceMap.takenOf(m))
    val mates = army.filter { it.hits < it.hitsMax || expected(it) > 0.0 }
    if (mates.isEmpty()) return
    val incoming = HashMap<String, Double>()
    for (m in mates) incoming[m.id] = expected(m)
    // сколько лечения дотянется до цели от ещё не занятых лекарей
    fun healPool(t: Creep, free: List<Creep>) = free.sumOf { h ->
        val d = h.getRangeTo(t); val pr = InfluenceMap.profileOf(h)
        if (d <= 1) pr.heal else if (d <= HEAL_RANGE) pr.heal / 3.0 else 0.0
    }
    val free = healers.toMutableList()
    // ...сперва те, кого убивают ЭТИМ тиком и кого лечение ещё спасает
    val dying = mates.filter { (incoming[it.id] ?: 0.0) >= it.hits }
        .sortedByDescending { it.hits }
    for (t in dying) {
        if (free.isEmpty()) break
        if (healPool(t, free) + t.hits < (incoming[t.id] ?: 0.0)) continue   // не спасаем — не тратим лечение в труп
        var got = 0.0
        for (h in free.sortedByDescending { it.getRangeTo(t) }.toList()) {
            if (got + t.hits >= (incoming[t.id] ?: 0.0)) break
            val d = h.getRangeTo(t); val pr = InfluenceMap.profileOf(h)
            if (d > HEAL_RANGE) continue
            got += if (d <= 1) pr.heal else pr.heal / 3.0
            out[h.id] = t.id; free.remove(h)
        }
    }
    // ...остальные — по ДОШЕДШЕМУ лечению, а не по наибольшей нужде (v183). Ранг по нужде не знает, сколько этот
    // лекарь реально снимет: вплотную он лечит вчетверо сильнее, чем издали (HEAL_POWER против RANGED_HEAL_POWER),
    // и приказ уводил его лечить издали чуть более нуждающегося вместо соседа. Пока приказ отбрасывался ближней
    // веткой исполнителя, это было незаметно; едва приказ стал действовать на всей дальности (USE_HEAL_ORDER_WINS),
    // гейт потерял обе строки kite. Ценность цели — min(нужда, сколько дойдёт), нужда остаётся тай-брейком
    for (h in free) {
        val pr = InfluenceMap.profileOf(h)
        val t = mates.filter { h.getRangeTo(it) <= HEAL_RANGE && it.id != h.id }
            .maxByOrNull { m ->
                val need = (m.hitsMax - m.hits) + (incoming[m.id] ?: 0.0)
                need
            }
        if (t != null) out[h.id] = t.id
    }
}

/**
 * Поле расстояний до ОЧАГА — клеток, откуда наш строй достаёт самое ценное у врага. Спуск по нему и есть
 * многотиковое направление: `COMMAND_REACH = 1` предлагает только соседнюю клетку, и требование роли,
 * которому ни одна из восьми не удовлетворяет, прежде молча отбрасывалось в общий добор.
 * Очаг ОДИН на армию — это и есть починка «армия развалилась на две половины»: покриповая цель уводила
 * соседей по строю в разные бои. Затравок при этом много, и армия растекается по ФРОНТУ, приходя к
 * ближайшему его участку, а не толпясь в одной точке.
 */
internal fun ensureGoalField(fighters: List<Creep>, combatEnemies: List<Creep>): IntArray? {
    if (goalTick == getTicks()) return goalField
    goalTick = getTicks()
    if (fighters.isEmpty() || combatEnemies.isEmpty()) { goalField = null; goalSeeds = IntArray(0); return null }
    val xs = fighters.map { it.x }.sorted(); val ys = fighters.map { it.y }.sorted()
    val ax = xs[xs.size / 2]; val ay = ys[ys.size / 2]
    var peak = 0
    for (x in maxOf(0, ax - SEED_BOX)..minOf(99, ax + SEED_BOX))
        for (y in maxOf(0, ay - SEED_BOX)..minOf(99, ay + SEED_BOX)) {
            val v = InfluenceMap.attRanged[key(x, y)]
            if (v > peak) peak = v
        }
    if (peak <= 0) { goalField = null; goalSeeds = IntArray(0); return null }
    val held = goalSeeds.toHashSet()
    val enter = (peak * SEED_ENTER).toInt()
    val hold = (peak * SEED_HOLD).toInt()
    val seeds = ArrayList<Int>(64)
    for (x in maxOf(0, ax - SEED_BOX)..minOf(99, ax + SEED_BOX))
        for (y in maxOf(0, ay - SEED_BOX)..minOf(99, ay + SEED_BOX)) {
            val key = key(x, y)
            if (DistanceMap.isTerrainWall(x, y)) continue
            val v = InfluenceMap.attRanged[key]
            if (v >= enter || (key in held && v >= hold)) seeds.add(key)
        }
    if (seeds.isEmpty()) { goalField = null; goalSeeds = IntArray(0); return null }
    val cx = seeds.sumOf { it / 100 } / seeds.size
    val cy = seeds.sumOf { it % 100 } / seeds.size
    // очаг держится, пока его центр не уехал: без этого армия ходит между двумя равными очагами и не доходит
    // ни до одного. Геометрия при этом СВЕЖАЯ каждый тик — держится решение, а не карта: враг ходит, и поле,
    // сохранённое на десять тиков, показывало бы на то место, где он был
    if (goalCx >= 0 && maxOf(abs(cx - goalCx), abs(cy - goalCy)) < SEED_MOVE && goalSeeds.isNotEmpty()) {
        goalHolds++
    } else {
        goalCx = cx; goalCy = cy; goalSeeds = seeds.toIntArray(); goalRebuilds++
    }
    goalField = DistanceMap.goalField(goalSeeds, combatEnemies.map { InfluenceMap.cell(it.x, it.y) })
    return goalField
}

/** Счётчики проходов раздачи (прибор `reach t=`, таблица `pass`): клеток выдано на глубине 0 / проход исполнен / исполнен впустую. */
internal val fightTally = Tally("pass", sequence = true)

/**
 * ЗАЖАТ ЛИ ОН (v264): враг [foe] не может за ход уйти от клетки [p] дальше чем на одну — устал, или каждая его
 * соседняя клетка в двух и дальше от p закрыта: край карты, стена, наш крип по плану ([ours]) или его уставший крип
 * ([hisStuck]). Его ПОДВИЖНЫЙ крип клетку не закрывает: его линия отступает целиком, задний шаг освобождает клетку
 * переднему, и счёт «занятое — закрыто» записал бы зажатым того, кто уходит. Наш мили, вставший в p, бьёт его
 * следующим тиком наверняка: удар сверяется с позицией на начало тика.
 */
internal fun pinnedAt(p: Position, foe: Creep, ours: Set<Int>, hisStuck: Set<Int>): Boolean {
    if (foe.fatigue > 0) return true
    for (dx in sym(1)) for (dy in sym(1)) {
        if (dx == 0 && dy == 0) continue
        val x = foe.x + dx
        val y = foe.y + dy
        if (maxOf(abs(x - p.x), abs(y - p.y)) <= 1) continue
        if (x < 0 || y < 0 || x > 99 || y > 99 || DistanceMap.isTerrainWall(x, y)) continue
        val key = key(x, y)
        if (key in ours || key in hisStuck) continue
        return false
    }
    return true
}

/**
 * ЗАПИСЬ ОДНОЙ РАЗДАЧИ (v449, пункт В оператора). Раздача идёт до шести раз за тик — по разу на замысел перебора командира и раз
 * для одних лекарей, — а её поле нужды, притязания и пробы были ОБЩИМИ: после перебора в мире оставалось поле ПОСЛЕДНЕГО
 * оценённого замысла, не выбранного (выбранный ≠ последний в 97 % выборок гейта и 89 % живых, v447), а приборы `pass=`,
 * `gate=`, `hpick=`, `hadj=`, `hfire=`, `adr=`, `fall=` считали каждую пробу. Теперь носитель считает в СВОЮ запись, а в мир
 * (`InfluenceMap.published`) и в приборы командир вливает запись выбранной раздачи ([mergeInto]); пробы считаются отдельно —
 * `deals=выбрано/сыграно`. Имена полей те же, что у приборов, в которые они вливаются.
 */
internal class DealRecord {
    val need = InfluenceMap.HealNeed()
    val tally = Tally("pass", sequence = true, register = false)
    /** Приборы записи (Gauges.kt): объявление ОДНО — здесь; близнец матча заводится сам, строка `t=` печатает его по имени поля. */
    val g = GaugeSet()
    /** ...и какой проход раздачи командира сколько клеток назначил (перепись `rung t=`, поле `pass=`). */
    val passCount = g.labelled("pass", printed = false)
    /** ЗОНД РАЗДАЧИ ЛЕКАРЕЙ (v224): раздач / выбрана клетка вплотную к бойцу вне его огня / такая свободная клетка была
     *  рядом, а выбрана другая / из них кандидат не прошёл ворота выживания; и средняя разница слагаемых оценки
     *  «выбранная минус кандидат» (положительная — слагаемое тянуло ОТ кандидата): притяжение, огонь, линия, экран,
     *  занятость, стоять, жилец, очаг. */
    val hpN = g.counter("hpick"); val hpAdj = g.counter("hpick", 1); val hpAvail = g.counter("hpick", 2); val hpGate = g.counter("hpick", 3)
    val hpDelta = g.reals("hpDelta", 8)
    val hadjN = g.counter("hadj"); val hadjAll = g.counter("hadj", 1)        // прилегание лекаря к теряющему хиты (v435, прибор hadj=)
    val hadjnN = g.counter("hadjn"); val hadjnAll = g.counter("hadjn", 1)      // ...то же, нормированное на назначения при теряющем хиты рядом (hadjn=)
    val hfireN = g.counter("hfire"); val hfireAll = g.counter("hfire", 1); val hfireAdj = g.counter("hfire", 2)   // режим «в зоне огня» у доставки (v438, hfire=)
    /** Крипов, вставших на каждом уровне ворот (индекс = порог выживания в тиках), и добор мимо ворот. */
    val gateLevels = g.ints("gateLevels", 8)
    val gateFell = g.counter("fell")
    /** Решений раздачи, где слагаемое цели изменило выбранную клетку, и решений всего. */
    val goalFlips = g.counter("flips"); val goalDecisions = g.counter("flips", 1)
    /** Пара к USE_ADDRESSED_DANGER (v224): раздач командира, сумма E и сумма T в выбранных клетках, раздач с T >= E. */
    val adrN = g.counter("adr"); val adrE = g.real("adrE"); val adrT = g.real("adrT"); val adrSame = g.counter("adr", 3)
    /** fall= (v421): запасных ходов стрелка, сохранивших цель в дальности, и ушедших из дальности. */
    val fallReach = g.counter("fall"); val fallAny = g.counter("fall", 1)
    val rotfMeet = g.counter("rotfm")
    // прибор согласованности строя (v200): у выбранной раздачи — её значение, а не сумма (при вливании ЗАМЕНЯЕТСЯ, `last`)
    val planGunsIn = g.counter("guns", 0, last = true); val planGunsAll = g.counter("guns", 1, last = true)
    val planMeleeHealed = g.counter("mheal", 0, last = true); val planMeleeAll = g.counter("mheal", 1, last = true)
    val planHealBehind = g.counter("hline", 0, last = true); val planHealAll = g.counter("hline", 1, last = true)
}

/** Поля записи раздачи обязаны быть объявлены до первой печати строки `t=`, а первая настоящая запись появляется только с первым
 *  боем: одна запись строится при инициализации файла. Здесь же — части полей, которые считаются из накопителей на месте печати. */
private val dealGaugesDeclared = DealRecord().also {
    Gauges.computed("adr", 1) { (Gauges.realAt("adrE").x / maxOf(Gauges.counterAt("adr").n, 1)).toInt().toString() }
    Gauges.computed("adr", 2) { (Gauges.realAt("adrT").x / maxOf(Gauges.counterAt("adr").n, 1)).toInt().toString() }
    Gauges.computed("dh") { Gauges.realsAt("hpDelta").joinToString(",") { (it / maxOf(Gauges.counterAt("hpick", 2).n, 1)).toInt().toString() } }
}

/** Вливает пробы раздачи [rec] в приборы матча — зовётся для ВЫБРАННОЙ раздачи тика (Commander.publishDeal). До v455 влив
 *  перечислял все поля записи руками — четвёртый список тех же имён (запись, влив, члены `PainAndGain`, печать). */
internal fun mergeDeal(rec: DealRecord) {
    Gauges.absorb(rec.g)
    fightTally.absorb(rec.tally)
}

internal fun PainAndGain.commandFight(army: List<Creep>, combatEnemies: List<Creep>, armedEnemies: List<Creep>,
                         out: MutableMap<String, Position>, intent: Intent = Intent.PRESS,
                         ourFlagCells: Set<Int> = emptySet(), healersOnly: Boolean = false): DealRecord? {
    out.clear()
    val fighters = mobileOf(army)
    if (fighters.isEmpty() || armedEnemies.isEmpty()) return null
    val enemyAt = HashSet<Int>()
    for (e in combatEnemies) enemyAt.add(e.key)
    // кандидаты: всё проходимое в двух клетках от любого нашего бойца
    val cells = HashMap<Int, Position>()
    for (c in fighters) for (dx in sym(2)) for (dy in sym(2)) {
        val x = c.x + dx; val y = c.y + dy
        val key = key(x, y)
        if (key in cells || x < 0 || y < 0 || x > 99 || y > 99) continue
        if (DistanceMap.isTerrainWall(x, y) || key in enemyAt) continue
        cells[key] = InfluenceMap.cell(x, y)
    }
    if (cells.isEmpty()) return null
    class AddrShooter(val x: Int, val y: Int, val ranged: Double, val melee: Double)
    /**
     * НОСИТЕЛЬ ОДНОЙ РАЗДАЧИ (v445, план архитектуры, 4.4 и этап 4): прежде это были ~30 общих локальных функции-замыкания и 22
     * локальные функции над ними. Теперь локальные — поля В ПРЕЖНЕМ ПОРЯДКЕ (порядок текста = порядок инициализации), локальные
     * функции — методы, блоки проходов — методы `pass*()`, собранные в таблицу [passes]. Класс локальный намеренно: имя в нём
     * разрешается «член носителя → параметр и локальная функции → член `PainAndGain` → верх пакета» — как раньше «локальная →
     * остальное»; у класса верхнего уровня с членами-расширениями приёмник `PainAndGain` шёл бы ПЕРВЫМ.
     * Раздачу зовут до шести раз за тик (перебор замыслов) — носитель живёт один вызов. Карта секций — docs/pain-and-gain-sections.md.
     */
    class Deal {
        // якорь кулака не читается нигде (мёртв), а сам вызов нужен: Formation.fist СУЖАЕТ `cells` на месте
        val fistAnchor = Formation.fist(fighters, cells)
        // ЗЕМЛЯ НЕ ОТДАЁТСЯ, ПОКА МЫ НЕ СЛАБЕЕ (v320). Разбор 18 реплеев блобов MetalicaX (Opus): тики, где центр армии идёт
        // назад быстрее 0,15 клетки, дают размен 0,46 против #15 и 0,68 против #9-типа; стоим — 0,93/1,19; идём вперёд —
        // 1,50/0,90. В поражениях от #15 армия проводит так 40 % тиков контакта (размен 0,32), в победах 7 %; в режиме FIGHT
        // пятятся все роли по 0,2 клетки в тик даже при замысле PRESS. Здесь это запрет на клетку ДАЛЬШЕ от его массы для
        // вооружённого, пока ядро в контакте и мы не слабее; раненые в ротации, лекари и замысел кайта — не под правилом
        // ⚠️ ОТВЕРГНУТО ЖИВЫМ ЗАМЕРОМ (v323): «земля не отдаётся, пока мы не слабее» (v320–v322) — запрет клетки дальше от его
        // массы для вооружённого в контакте. Основание было сильное по числам разбора (18 реплеев блобов MetalicaX: отход даёт
        // размен 0,46, стояние 0,93, движение вперёд 1,50; поражения проводят так 40 % тиков контакта против 7 % в победах), и
        // гейт дал +8 тыс. отрыва, но рука по 8 игр на бота этого не подтвердила: MetalicaX#15 2-6 против 0-8, зато
        // Coldkimchi#2 3-5 против 5-3 и MetalicaX#3 3-5 против 5-3 — всего 16-24 против 18-22. Причинность в разборе и
        // называлась средней: наступление включалось, пока состояние было лучше, а не наоборот
        // ОПАСНОСТЬ КЛЕТКИ ЧИТАЕТСЯ ИЗ ПОЛЯ (v204, этап 4). Прежде она строилась здесь, то есть ПЯТЬ раз за тик —
        // по разу на замысел, над одним и тем же множеством врагов, — и внутри цикла по клеткам звалась profileOf,
        // читающая тело крипа через границу изоляции. Поле строится один раз в прологе тика (см. buildFields), и
        // этап 3 доказал равенство: fldcmp = 0 на 304 950 клетках 135 сценариев.
        // Мёртвыми оказались incNow и hits: обе карты считались в том же цикле и не читались НИКЕМ — их удалила
        // перепись, а не чтение кода
        // АДРЕСНАЯ ОПАСНОСТЬ (v224, см. USE_ADDRESSED_DANGER): E складывает всех, кто достаёт клетку, а его ствол бьёт
        // ОДНОГО — лекаря в досягаемости, иначе ближайшего, при равенстве того, у кого меньше хитов (реплеи обеих сторон:
        // 89–94 % выстрелов в ближайшего, 82–97 % в лекаря при лекаре в досягаемости). T(c, p) записывает крипу c в
        // клетке p урон только тех стволов, для которых он был бы этой целью при остальных наших на назначенных клетках
        // (план сравнивается с планом, как у согласованности строя). Досягаемости те же, что у ядер E: стрелок 3, мили 2.
        // Лучший «другой» кандидат считается один раз на крипа и пересчитывается, когда раздача перешла к другому крипу
        val addrShooters = ArrayList<AddrShooter>()
        init {
            for (e in armedEnemies) {
                val q = InfluenceMap.profileOf(e)
                if (q.ranged > 0.0 || q.melee > 0.0) addrShooters.add(AddrShooter(e.x, e.y, q.ranged, q.melee))
            }
        }
        val addrLive = living(army)
        var addrFor: String? = null
        val addrRH = DoubleArray(addrShooters.size); val addrRA = DoubleArray(addrShooters.size)
        val addrMH = DoubleArray(addrShooters.size); val addrMA = DoubleArray(addrShooters.size)
        fun addrPrepare(c: Creep) {
            addrFor = c.id
            for (i in addrShooters.indices) { addrRH[i] = Double.MAX_VALUE; addrRA[i] = Double.MAX_VALUE; addrMH[i] = Double.MAX_VALUE; addrMA[i] = Double.MAX_VALUE }
            for (f in addrLive) {
                if (f.id == c.id) continue
                val p = out[f.id] ?: InfluenceMap.cell(f.x, f.y)
                val h = healerOnly(f)
                for (i in addrShooters.indices) {
                    val s = addrShooters[i]
                    val d = maxOf(abs(p.x - s.x), abs(p.y - s.y))
                    if (d > RANGED_RANGE) continue
                    val r = d * 100000.0 + f.hits
                    if (s.ranged > 0.0) { if (r < addrRA[i]) addrRA[i] = r; if (h && r < addrRH[i]) addrRH[i] = r }
                    if (d <= 2 && s.melee > 0.0) { if (r < addrMA[i]) addrMA[i] = r; if (h && r < addrMH[i]) addrMH[i] = r }
                }
            }
        }
        fun addressedAt(c: Creep, x: Int, y: Int): Double {
            if (addrFor != c.id) addrPrepare(c)
            val h = healerOnly(c)
            var sum = 0.0
            for (i in addrShooters.indices) {
                val s = addrShooters[i]
                val d = maxOf(abs(x - s.x), abs(y - s.y))
                if (d > RANGED_RANGE) continue
                val r = d * 100000.0 + c.hits
                if (s.ranged > 0.0 && (if (h) r <= addrRH[i] else (addrRH[i] == Double.MAX_VALUE && r <= addrRA[i]))) sum += s.ranged
                if (d <= 2 && s.melee > 0.0 && (if (h) r <= addrMH[i] else (addrMH[i] == Double.MAX_VALUE && r <= addrMA[i]))) sum += s.melee
            }
            return sum
        }
        /** Опасность клетки ДЛЯ ЭТОГО крипа: адресная при тумблере, иначе поле E — байт в байт прежняя раздача. */
        fun danOf(c: Creep, key: Int): Double =
            InfluenceMap.dangerAt(key)
        val goal = ensureGoalField(fighters, combatEnemies)
        /** Цена клетки по направлению: сколько тиков пути от неё до ближайшего очага. */
        fun goalCost(key: Int): Double {
            val g = goal ?: return 0.0
            val d = g[key]
            return if (d < 0) GOAL_UNREACHABLE else d.toDouble()
        }
        val taken = HashSet<Int>()
        /** ЗАПИСЬ ЭТОЙ РАЗДАЧИ (v449, пункт В оператора): поле нужды и пробы — свои у каждой пробы замысла; в мир и в приборы
         *  уходит запись ВЫБРАННОЙ (см. DealRecord и Commander.publishDeal). */
        val rec = DealRecord()
        // ПРИТЯЗАНИЯ СЧИТАЮТСЯ ИЗ ОКОНЧАТЕЛЬНЫХ ПРИКАЗОВ, А НЕ КОПЯТСЯ ПОЛЕМ (v450, пункт В оператора, вторая половина). До v449
        // `place` штамповал притязание на клетку и её соседей в поле `InfluenceMap.claim`, общее для всех проб тика; v449 дал
        // каждой раздаче своё поле с прежней семантикой — и притязание СНЯТОГО приказа (спасение в `place`, проход `pinned`)
        // оставалось стоять, «притязание без крипа»: вокруг клетки просителя отталкивание удваивалось. Теперь притязание клетки
        // — число приказов, поставленных `place` и стоящих в `out`, в её окрестности: снят приказ — снято и притязание, по
        // построению. Это поведенческая правка: спасение по `alone` — обычное дело первого контакта, и гейт разошёлся с v449 в
        // 114 логах из 139 (см. абзац v450). Прежний текст поля, дословно:
        // «Занятость клеток уже розданными приказами: раздача идёт пять раз за тик, по разу на замысел, и
        // притязания одного замысла не должны просачиваться в следующий (Hagelbäck: временное отталкивание
        // в выбранной клетке — то, что не даёт крипам слипаться в одну точку).
        // Ставит притязание на клетку и её соседей: следующий крип видит её как занятую.»
        val claimed = HashSet<String>()
        fun claimAt(key: Int): Double {
            val x = key / 100; val y = key % 100
            var n = 0
            for (id in claimed) { val p = out[id] ?: continue; if (maxOf(abs(p.x - x), abs(p.y - y)) <= 1) n++ }
            return n.toDouble()
        }
        // клетки, где стоят СВОИ: назначать их нельзя — приказ туда неисполним, пока сосед не ушёл, а прибор показал,
        // что до назначенной клетки доходят 7 % (v167). Своя собственная клетка при этом разрешена: это «стой»
        // (множество `allyAt`, которое описывал комментарий выше, наполнялось и НЕ ЧИТАЛОСЬ нигде — снято в v447 как мёртвое;
        // занятость своих клеток решает карта жильцов `allyOf` в `place`)
        // кто стоит в клетке (для цепочек): ключ клетки → крип
        val allyOf = HashMap<Int, Creep>()
        init { for (a in army) if (a.hits > 0) allyOf[a.key] = a }
        /** ЖИЛЕЦ КЛЕТКИ (v449, пункт В): свой, стоящий в ней с начала тика и не уведённый приказом — в `out` его нет или приказ
         *  «стой»; просителю сам он не жилец. До v449 карта `allyOf` ещё и ПРАВИЛАСЬ при назначении (жилец, уведённый в другую
         *  клетку, вычёркивался), а снятый приказ его не возвращал — та же несимметрия, что у притязаний; теперь карта неизменна,
         *  а «ушёл ли» читается из `out`. */
        fun tenantOf(key: Int, c: Creep): Creep? =
            allyOf[key]?.takeIf { t -> t.id != c.id && (t.id !in out || out[t.id]?.let { it.x == t.x && it.y == t.y } == true) }
        // ПЛАН СТРОИТСЯ ВГЛУБЬ (v168, оператор): «если крипу необходимо уйти на клетку, на которой сейчас стоит крип,
        // зачем этому крипу необходимо сдвинуться на другую, и если там стоит крип, то сдвинуть и его, и так далее».
        // Раздача рекурсивна: занятая клетка не отвергается и не просто дорожает — её жилец получает приказ уйти,
        // а если и его клетка занята, цепочка идёт дальше, до CHAIN_DEPTH звеньев. Обмен местами — вырожденная
        // цепочка длины два, и он разрешён отдельно: ротация состава (раненого назад, свежего вперёд) это ровно своп
        // ОПАСНОСТЬ ПУТИ, А НЕ ТОЛЬКО КЛЕТКИ (v169, оператор). Клетка в двух шагах достигается ЧЕРЕЗ промежуточную, и
        // если та под огнём, приказ либо не исполняется, либо исполняется ценой крипа: движение честно отказывается
        // идти сквозь огонь, и именно так приказ терялся. Стоимость пути — самая безопасная из промежуточных клеток
        fun pathDanger(c: Creep, p: Position): Double {
            if (getRange(c, p) <= 1) return 0.0
            var best = Double.MAX_VALUE
            for (dx in sym(1)) for (dy in sym(1)) {
                if (dx == 0 && dy == 0) continue
                val x = c.x + dx; val y = c.y + dy
                if (maxOf(abs(x - p.x), abs(y - p.y)) > 1) continue          // должна быть смежной с целью
                if (x < 0 || y < 0 || x > 99 || y > 99 || DistanceMap.isTerrainWall(x, y)) continue
                val d = danOf(c, key(x, y))
                if (d < best) best = d
            }
            return if (best == Double.MAX_VALUE) PATH_BLOCKED_COST else best * PATH_DANGER_W
        }
        // кандидаты вокруг крипа: приказ ровно на шаг, значит их девять — а цикл шёл по всей раздаче (около трёхсот
        // клеток) и отбрасывал лишние проверкой дальности. При рекурсии цепочек и переборе замыслов это и давало
        // `Script execution timed out` почти в каждом матче (v181)
        fun nearCells(c: Creep): List<Pair<Int, Position>> {
            val near = ArrayList<Pair<Int, Position>>(9)
            for (dx in sym(COMMAND_REACH)) for (dy in sym(COMMAND_REACH)) {
                val key = key(c.x + dx, c.y + dy)
                cells[key]?.let { near.add(key to it) }
            }
            return near
        }
        // ПЕРЕПИСЬ ПРОХОДОВ (v203): какой проход раздачи сколько клеток реально назначил. Пара «удалось/попыток» —
        // ноль в числителе при живом знаменателе значит «проход отказался», оба нуля значат «до прохода не дошли»
        var passTag = "-"
        fun place(c: Creep, wants: (Position) -> Boolean, rank: (Position) -> Double, depth: Int = 0,
                  rescue: Boolean = false): Boolean {
            var best: Position? = null; var bestScore = Double.MAX_VALUE
            var bestTenant: Creep? = null
            // ПРИБОР СЛАГАЕМОГО — «сколько решений ИЗМЕНИЛОСЬ», а не «сколько раз код исполнился». Ровно этого не
            // хватало v196: счётчик доказывал, что код работает, и не доказывал, что он поменял хоть одну клетку
            var bare: Position? = null; var bareScore = Double.MAX_VALUE
            // свободные, прошедшие ворота клетки (v222, см. USE_MELEE_QUIET_CELL): из них мили выбирает тихую клетку удара
            // ПРИКАЗ ОБЯЗАН БЫТЬ ИСПОЛНИМ (v172, оператор): «командир должен быть уверен, что каждый крип на следующем
            // шагу сможет выполнить приказ». Уставший крип в этот тик не двинется вовсе — ему можно приказать только
            // стоять, и приказ «шагни» от него был бы ложью, которую потом считает прогноз
            val canStep = canMove(c) && c.fatigue == 0
            // ...дальность проверяется по координатам, а не вызовом getRange: порядок перебора остаётся прежним (его
            // смена меняла выбор при равных оценках и роняла строку гейта), но отбрасывание дальних клеток становится
            // дешёвым — а их около трёхсот на каждого крипа при девяти нужных (v181)
            for ((key, p) in cells) {
                if (abs(p.x - c.x) > COMMAND_REACH || abs(p.y - c.y) > COMMAND_REACH) continue
                if (!canStep && !(p.x == c.x && p.y == c.y)) continue
                if (key in taken) continue
                if (!wants(p)) continue
                val self = p.x == c.x && p.y == c.y
                // ...и жилец, которому приказано СТОЯТЬ, остаётся препятствием (v175): прежде всякий, кто уже получил
                // приказ, считался уходящим — а приказ «стой» (хранитель флага, крип на своём месте) никуда его не
                // уводит, и назначенная поверх него клетка оказывалась неисполнимой. Это и есть весь оставшийся
                // процент неисполнения: 17 случаев на 5 294 приказа, все вида «крип остался на месте»
                val tenant = if (self) null else tenantOf(key, c)

                // клетка под своим дороже: приказ туда исполним, только если жильца удастся сдвинуть
                // СМЕРТЕЛЬНАЯ КЛЕТКА НЕ ПРЕДЛАГАЕТСЯ (v178, оператор: «наш мили крип шагнул сразу под 2 мили крипов
                // соперника — для него это должно было быть смертельно, и эта точка никак не могла ему выдаваться»).
                // Опасность клетки входила слагаемым и её перевешивали другие члены; теперь клетка, где входящий за
                // тик снимает крипу всю жизнь, стоит запретительно дорого и берётся, только если других нет вовсе
                val lethal =  danOf(c, key) >= c.hits
                // БОЛОТО ДЕРЖИТ КРИПА НЕСКОЛЬКО ТИКОВ (v183, оператор: «часть нашей армии часто вязнет в болоте и
                // дальше не может двигаться»). Про болото знала только СТАРАЯ ветка движения — штраф в выборе слота и
                // расчёт периода шага; командир, забравший себе все приказы, о нём не знал вовсе и посылал крипов в
                // трясину. Замер по четырём разгромам: наши крипы несут усталость 489 крипо-тиков против его 36 (в
                // тринадцать раз), стоят на месте 22 % против его 7 %, самый долгий непрерывный простой у нас 47 тиков
                // против семи у него. Шаг В болото стоит четырёх тиков неподвижности; стоять НА болоте бесплатно,
                // поэтому дорожает только переход
                val bog =  !self && DistanceMap.isSwamp(p.x, p.y)
                val sc0 = rank(p) + (if (tenant != null) ALLY_CELL_COST else 0.0) + pathDanger(c, p) +
                    (if (lethal) LETHAL_CELL_COST else 0.0) + (if (bog) SWAMP_CELL_COST else 0.0)
                // СПУСК К ОЧАГУ (v204, этап 5) — слагаемое в той же оценке, а не отдельная ветка: вблизи врага все
                // затравки на нуле и оно плоское, решает тактика; вдали оно единственное, что отличает клетки друг
                // от друга. Переход непрерывный, режима, который можно перепутать, нет
                val sc = sc0 + GOAL_STEP_COST * goalCost(key)
                // ...и при РАВНЫХ оценках выбор не должен зависеть от порядка перебора: раньше порядок задавала общая
                // раздача, теперь — обход соседей, и одна строка гейта поменяла исход именно из-за этого (v181)
                if (sc < bestScore) { bestScore = sc; best = p; bestTenant = tenant }
                if (sc0 < bareScore) { bareScore = sc0; bare = p }
            }
            val b = best ?: return false
            rec.goalDecisions.n++
            val bs = bare
            if (bs == null || bs.x != b.x || bs.y != b.y) rec.goalFlips.n++
            val tenant = bestTenant
            if (tenant != null) {
                if (depth >= CHAIN_DEPTH) return false
                // СПАСЕНИЕ СТАРШЕ ЛЮБОГО ПРИКАЗА (v176, оператор): если самая безопасная клетка занята крипом, которому
                // велено стоять, приказ стоять снимается и жилец уводится цепочкой — беречь расстановку ценой крипа
                // армия из четырнадцати не может. Снимается он ТОЛЬКО у выбранного жильца: первая редакция снимала
                // приказы прямо в переборе кандидатов, у всех подряд, и прибор поймал это коллизией (clash=1)
                var undo: Position? = null
                if (rescue) { undo = out.remove(tenant.id); taken.remove(tenant.key) }
                // своп: жилец встаёт на клетку просителя — так делается ротация состава
                val swapCell = cells[c.key]
                val moved = (swapCell != null && place(tenant, { p -> p.x == c.x && p.y == c.y }, { 0.0 }, depth + 1)) ||
                    place(tenant, { p -> p.x != b.x || p.y != b.y }, { p -> danOf(tenant, p.key) }, depth + 1)
                // ...и при неудаче цепочки снятый приказ ВОЗВРАЩАЕТСЯ: без отката жилец оставался без приказа, его
                // клетка свободной, и позже она доставалась двоим — прибор ловил это как clash=1 (v176)
                if (!moved) {
                    // ...и вернуть приказ можно, только если его клетку за это время никто не занял: слепое
                    // восстановление отдавало одну клетку двоим (clash в режиме боя, t=808)
                    val back = undo
                    if (back != null && back.key !in taken) { out[tenant.id] = back; taken.add(back.key) }
                    return false
                }
            }
            taken.add(b.key); out[c.id] = b
            // прибор адресной опасности (v224): E и T выбранной клетки, в обеих сборках
            if (depth == 0) {
                val e = InfluenceMap.dangerAt(b.key)
                val t = addressedAt(c, b.x, b.y)
                rec.adrN.n++; rec.adrE.x += e; rec.adrT.x += t
                if (t >= e) rec.adrSame.n++
            }
            // временное отталкивание в занятой клетке (Hagelbäck & Johansson): следующий крип видит её как
            // тесную. Без этого двое выбирают одну клетку, третий загораживает четвёртого — записанная причина
            // провала USE_FORWARD_SEARCH: «каждый крип считает за себя»
            claimed.add(c.id)          // притязание — из приказа в `out`, снимается вместе с ним (v450, см. claimAt)
            if (depth == 0) { rec.passCount.bump(passTag); rec.tally.won[passIndex]++ }
            return true
        }
        // поля, которые пишет один проход, а читает другой (до v445 — локальные посреди тела функции)
        val rotatingMeet = HashMap<String, Position>()
        val medicked = HashSet<String>()
        var maxV = 0.0          // считает проход `fields`; читает `sagAt` — только из прохода `melee`, стоящего в списке НИЖЕ: порядок списка держит это, не область видимости
        var passIndex = 0
        val weakestMelee = armedEnemies.minByOrNull { it.hits }
        // МЁРТВЫЙ meleeCommit УДАЛЁН (v214). Правило v143 обещало: мили идёт вплотную, только когда цель
        // добивается этим тиком или у нас перевес по силе, — но переменная была ОБЪЯВЛЕНА И НИКЕМ НЕ ЧИТАЛАСЬ,
        // то есть обещанного не было вовсе, и тумблер USE_MELEE_COMMIT не гейтил ничего. Оба её слагаемых теперь
        // есть в лучшем виде: перевес — местный (см. spotEdgeAt, поле удара в клетке врага, а не мощь армии),
        // добиваемость — killTicks/killableNow, которыми уже пользуется фокус. Замеры из прежнего комментария
        // перенесены в docs/pain-and-gain.md.
        val hisMelee = armedEnemies.filter { InfluenceMap.profileOf(it).melee > 0.0 }
        // СОГЛАСОВАННОСТЬ СТРОЯ (v200, оператор: «все ходы должны быть согласованными... не должно быть такого, что
        // наш крип пошёл в наступление без прикрытия; командир не должен отправлять крипов в строй врага, если он там
        // может сильно пострадать и без возможности быть вылеченным»). Клетка крипа считается ОТНОСИТЕЛЬНО уже
        // назначенных клеток остальных, а не их нынешних позиций: иначе «согласованность» сравнивает план с прошлым
        fun cellOf(f: Creep): Position = out[f.id] ?: InfluenceMap.cell(f.x, f.y)
        fun foeDist(x: Int, y: Int) = armedEnemies.minOfOrNull { maxOf(abs(x - it.x), abs(y - it.y)) } ?: 99
        val melees = fighters.filter { meleeOnlyLive(it) }
        val rangeds = fighters.filter { hasRanged(it) }
        val healers = fighters.filter { healerOnly(it) }
        // РАЗДЕТЫЕ ТОЖЕ ПОД ПРИКАЗОМ (v144): крип, потерявший все боевые части, не попадал НИ В ОДНУ группу — ни
        // оружия, ни лечения, — и приказа не получал вовсе, оставаясь стоять под огнём. Диагноз называл это в каждом
        // разборе: наши раздетые в трёх клетках от его вооружённых 172 крипо-тика из 238, у него 7 из 11
        val stripped = fighters.filter { unitOf(it).stripped }
        // ...и замысел может быть СВОЙ у каждого крипа (v139, портфельный поиск): армия смешивает поведение
        fun intentOf(c: Creep) = intent
        // клетки лекарей — прибор согласованности `mheal` ниже: сколько мили осталось в дальности лечения
        val healerCells = healers.map { InfluenceMap.cell(it.x, it.y) }
        // ГДЕ ФРОНТ ПРОСЕДАЕТ: уязвимость (2·min(наши, его)) выделяет линию контакта, влияние (наши − его)
        // говорит, чья она. Проседание — участок линии, где влияние отрицательно, то есть где нас продавливают
        fun sagAt(key: Int): Double {
            if (maxV <= 0.0) return 0.0
            if (InfluenceMap.vulnerabilityOf(key) < FRONT_RIDGE * maxV) return 0.0
            return maxOf(0.0, -InfluenceMap.influenceOf(key))
        }
        // тела своих вокруг клетки — по УЖЕ НАЗНАЧЕННЫМ клеткам, а не по нынешним позициям: план сравнивается
        // с планом, иначе «согласованность» сравнивает будущее с прошлым
        fun screenAt(c: Creep, p: Position): Int = fighters.count { f ->
            f.id != c.id && cellOf(f).let { maxOf(abs(it.x - p.x), abs(it.y - p.y)) <= 1 } }
        /**
         * ВОРОТА — ВЫЖИВАЕМОСТЬ КРИПА, А НЕ СРАВНЕНИЕ СИЛ. Прежнее `netDamageAt·2 > hits` истинно для КАЖДОЙ
         * клетки фронта (наш мили 1600 хитов против его полного блока даёт 2520), и это ровно записанный в
         * доках провал «трое мили развернулись спиной в первый тик контакта». Здесь считается, сколько тиков
         * крип проживёт в клетке: экран своих тел входит ДЕЛИТЕЛЕМ входящего, лечение — вычитаемым, и на
         * собственное лечение уходящий крип не рассчитывает.
         */
        fun ttlAt(c: Creep, key: Int, p: Position): Double {
            val e = danOf(c, key)
            if (e <= 0.0) return 99.0
            val heal = maxOf(0.0, InfluenceMap.healReachAt(key) - InfluenceMap.healFromSelf(c, p.x, p.y))
            val net = maxOf(0.0, e - heal) / (1.0 + SCREEN_SHARE * screenAt(c, p))
            return if (net <= 1.0) 99.0 else c.hits / net
        }
        val weakestFoe = armedEnemies.minByOrNull { it.hits }
        fun stayBonus(c: Creep, p: Position) = if (p.x == c.x && p.y == c.y) STAY_BONUS else 0.0
        // МИЛИ: к тому, что достанет ногами; по гребню фронта и туда, где он проседает; не выходя из-под лечения.
        // «Не выходя из-под лечения» было ЗАПРЕТОМ (inHealReach) и потому либо не давало клеток вовсе, либо
        // отбрасывалось молча; здесь это слагаемое, и оно конкурирует с притяжением честно
        fun scoreMelee(c: Creep, key: Int, p: Position, att: Double, dan: Double, focus: Creep?): Double {
            val pull = if (focus != null) InfluenceMap.attractionTo(focus, p.x, p.y, true) else InfluenceMap.attMeleeAt(key)
            // УДАР ВХОДИТ В ЦЕНУ КЛЕТКИ ЯВНО И В ЕДИНИЦАХ УРОНА (v425). Ранг мили складывал притяжение `attMeleeAt` с
            // опасностью, и сам УДАР в нём выражен не был — а он решает размен: наш мили вплотную даёт 240 в тик одним
            // крипом, тогда как три стрелка дают 180 против его лечения 129–147 на цель, то есть ЧИСТЫМИ 33–51. Пробить
            // лечение стрелками нельзя в принципе; пробивает мили. Чистая пара 16 игр против MetalicaX#13 говорит это
            // прямо: в победах наш мили стоит в 1,35 ± 0,11 клетки от его ближайшего и теряет первую боевую часть на
            // контакте+8,8, в поражениях — 1,95 ± 0,57 и остаётся ЦЕЛЫМ (ноль выбитых частей к контакту+10 в 8 матчах
            // из 9), пока раздевают наших стрелков; его огонь переходит на стрелков уже на контакте+6,4. Разрез по
            // дистанции <= 1,45 даёт 6 побед из 6 против 0 из 9. Слагаемое считается так же, как у лекаря («доставленное
            // лечение минус полученный урон»): клетка, из которой мили достаёт вооружённого, приносит армии его удар с
            // поправкой на входящий модификатор цели, и этот удар конкурирует с опасностью честно, а не через вес
            val strike = if (!USE_MELEE_STRIKE_VALUE) 0.0 else {
                val hit = InfluenceMap.profileOf(c).melee
                if (hit <= 0.0) 0.0
                else armedEnemies.filter { getRange(p, it) <= 1 }.maxOfOrNull { hit * InfluenceMap.takenOf(it) } ?: 0.0
            }
            return -W_ATT * att * pull - strike + W_DAN * dan * danOf(c, key) -
                W_FRONT * InfluenceMap.vulnerabilityOf(key) - W_SAG * sagAt(key) -
                W_HEALCOVER * InfluenceMap.healReachAt(key) +
                CLAIM_COST * claimAt(key) - stayBonus(c, p)
        }
        // СТРЕЛОК: притяжение с пиком на дальности 3 (он останавливается сам, вместо запрета «не ближе мили»),
        // плюс влияние — стоять там, где сильнее мы. Это и есть «не быть первой линией», сказанное числом
        fun scoreRanged(c: Creep, key: Int, p: Position, att: Double, dan: Double, focus: Creep?): Double {
            val pull = if (focus != null) InfluenceMap.attractionTo(focus, p.x, p.y, false) else InfluenceMap.attRangedAt(key)
            // ВЫСТРЕЛ ВХОДИТ В ЦЕНУ КЛЕТКИ ЯВНО (v427) — тот же пропуск, что у мили в v425, и та же форма. В ранге
            // стрелка стояло поле притяжения `attRangedAt` с пиком на дальности три, опасность и влияние линии, но
            // САМ ВЫСТРЕЛ выражен не был: клетка, откуда стрелок достаёт вооружённого, и клетка, откуда не достаёт,
            // различались только полем, а не тем уроном, который армия из этой клетки получает. Поле — величина без
            // размерности, урон — в тех же единицах, что входящий, поэтому здесь они сравниваются честно, как у лекаря
            // («доставленное лечение минус полученный урон»). Числа v425 дают и цену вопроса: выстрел 60 против удара
            // мили 240, то есть слагаемое вчетверо меньше и не должно переворачивать расстановку — оно лишь перестаёт
            // отдавать выстрел даром. Прежняя мера этого не ловила: v421 вернула выстрел ЗАПАСНОМУ ходу, но основной
            // ранг по-прежнему не знал, что клетка вне дальности не стреляет вовсе
            val shot = if (!USE_RANGED_SHOT_VALUE) 0.0 else {
                val hit = InfluenceMap.profileOf(c).ranged
                if (hit <= 0.0) 0.0
                else armedEnemies.filter { getRange(p, it) <= RANGED_RANGE }.maxOfOrNull { hit * InfluenceMap.takenOf(it) } ?: 0.0
            }
            return -W_ATT * att * pull - shot + W_DAN * dan * danOf(c, key) -
                W_LINE * InfluenceMap.influenceOf(key) +
                CLAIM_COST * claimAt(key) - stayBonus(c, p)
        }
        // ЛЕКАРЬ: тянется туда, где помощь ВЕРОЯТНЕЕ ВСЕГО ПОНАДОБИТСЯ (нужда = опасность в клетке подопечного,
        // а не его нынешняя рана — прежнее правило брало раненых, то есть по определению тех, кто уже на фронте,
        // и тянуло лекаря вперёд). Смотрит на огонь ЭТИМ тиком, а не на E: иначе клетка рядом с подопечным,
        // которого уже рубят, читается как 720 опасности и выталкивает лекаря на три клетки
        fun scoreHeal(c: Creep, key: Int, p: Position, att: Double, dan: Double): Double {
            val scr = screenAt(c, p)
            val fire = InfluenceMap.fireFieldAt(key)
            val shielded = fire * (1.0 - 1.0 / (1.0 + SCREEN_SHARE * scr))   // урон, который снимут тела своих
            // ПРИТЯЖЕНИЕ ЛЕКАРЯ НАСЫЩАЕТСЯ ТЕМ, ЧТО ОН МОЖЕТ ДОСТАВИТЬ (v208, замер серии v206: лекарей за линией
            // 0.695 против 0.944 у v205 — они полезли в первую линию). Нужда складывается по всем подопечным в
            // радиусе и не ограничена ничем, поэтому доходила до тысяч против сотен опасности, и лекарь нырял в
            // рубку. Но доставить он может только своё лечение за тик: 72 у полного h6m6. Мягкое насыщение
            // deliver·att/(att + deliver) сохраняет ФОРМУ поля (порядок клеток тот же) и ставит потолок в тех же
            // единицах, что и урон. Тогда оценка лекаря честно читается как «доставленное лечение минус
            // полученный урон», и клетка под огнём в 500 ради 72 лечения проигрывает сама, без запрета
            val deliver = InfluenceMap.healOf(c)
            val raw = rec.need.attHealAt(key)
            // Две замены этого притяжения ОТВЕРГНУТЫ живьём (v224, по 0-4): «доставленное из клетки лечение лучшему
            // подопечному» (USE_HEAL_PULL_DELIVERED) и «без слагаемого влияния» (USE_HEALER_NO_LINE). Действуют насыщенная сумма
            // здесь и влияние W_LINE ниже; зонд `hpick` называет влияние решающим (разбор v270: −5…−7 в пользу выбранной клетки
            // против свободной клетки вплотную к бойцу первой линии). Комментарий до v270 описывал обе редакции как действующие
            // ЦЕНА КЛЕТКИ — ЛУЧШАЯ ОДНА ДОСТАВКА ТОМУ, КТО ТЕРЯЕТ ХИТЫ (v435, см. USE_HEAL_NEED_ACTUAL): 72 вплотную к бьющемуся,
            // 24 в двух-трёх клетках, 0 у целого — в тех же единицах, что входящий урон, как у стены v228, но для всех
            val pull = if (USE_HEAL_NEED_ACTUAL) rec.need.bestDeliveryAt(c, p.x, p.y, army)
                else if (deliver <= 0.0 || raw <= 0.0) 0.0 else deliver * raw / (raw + deliver)
            // ...И ПРИ УДЕРЖИМОЙ ЖЕРТВЕ ЦЕНА КЛЕТКИ — ДОСТАВЛЕННОЕ В НЕЁ ЛЕЧЕНИЕ (v228, см. USE_HEAL_WALL): вплотную полное, в трёх
            // треть, без насыщенной суммы и без слагаемого влияния — клетка вплотную к жертве получает положительную цену, которой
            // обе редакции v224 ей дать не смогли (72 против 24)
            val victim = victimNow
            if (victimSaveable && victim != null) {
                val d = getRange(p, victim)
                val wall = if (d <= 1 && wallCells.any { it.x == p.x && it.y == p.y }) deliver else if (d <= HEAL_RANGE) deliver / 3.0 else 0.0
                return -W_ATT * att * wall + W_DAN * dan * fire - W_SCREEN * shielded + CLAIM_COST * claimAt(key) - stayBonus(c, p)
            }
            // ...И ВЛИЯНИЕ ЛИНИИ НЕ ВЫТЕСНЯЕТ ДОСТАВКУ (v437, см. USE_HEAL_NO_LINE): у лекаря все прочие слагаемые — в хитах
            // (доставка, огонь, экран), а влияние безразмерно и ни на что в арене не опирается; зонд dh в трёх сборках подряд
            // называет его единственным, что уводит лекаря от безопасной клетки вплотную к теряющему хиты (−8…−38 против
            // +3…+10 доставки). Первая редакция снимала влияние целиком и уронила гейт на match29:camp (13 254 : 23 910):
            // когда рядом никто не теряет хитов, притяжение равно нулю везде, и без влияния лекарей ничто не держит при
            // армии — пятеро бойцов ушли в лагерь и погибли нелечеными (hadj=0/347, hlost=0). Влияние — клей строя, и оно
            // остаётся там, где доставки нет; там, где она есть, цена клетки считается в хитах
            // ...И В РЕЖИМЕ ОГНЯ ВЛИЯНИЯ НЕТ НИГДЕ (сужение v438 по гейту match4:kite 792 : 22 718): клетки под его огнём
            // получили доставку 0, клей включился в них — а влияние (наш залп − его опасность) у мили-кулака в сотни, и dh показал
            // −178 в пользу клетки в кулаке против безопасной вплотную. Якорь лекарю в этом режиме — доставка у безопасных клеток
            // при подопечном под огнём, он есть по построению режима; в клетках под огнём остаются опасность, притязание и стой
            val line = if (USE_HEAL_NO_LINE && (pull > 0.0 || rec.need.deliveryFireMode(c, army))) 0.0 else W_LINE * InfluenceMap.influenceOf(key)
            return -W_ATT * att * pull + W_DAN * dan * fire -
                line - W_SCREEN * shielded +
                CLAIM_COST * claimAt(key) - stayBonus(c, p)
        }
        /**
         * ЗАМЫСЕЛ = ВЕКТОР ВЕСОВ над одной оценкой: множитель притяжения, множитель опасности, порог выживания.
         * Пять веток `when` на роль превращаются в пять троек чисел — этап 8 проверит, окупается ли перебор.
         */
        fun weightsOf(i: Intent): Triple<Double, Double, Int> = when (i) {
            Intent.PRESS -> Triple(1.5, 0.7, 2)
            Intent.HOLD -> Triple(1.0, 1.0, 3)
            Intent.YIELD -> Triple(0.3, 2.0, 5)
            Intent.FOCUS -> Triple(1.5, 0.7, 2)
            Intent.KITE -> Triple(1.0, 1.3, 3)
        }
        /**
         * Раздача по оценке с МЯГКИМИ воротами: порог выживания снижается по лестнице, пока клетка не найдётся,
         * и уровень записывается счётчиком. Сегодняшний молчаливый провал требования в общий добор становится
         * печатаемым числом — `gate=...`. Ниже единицы порог не опускается: клетка, где крип умирает за ход,
         * не предлагается никогда.
         */
        fun placeScored(c: Creep, role: Int, i: Intent): Boolean {
            val (att, dan, ttlMin) = weightsOf(i)
            // ПРЕСЛЕДОВАТЕЛЬ СМОТРИТ НА СВОЙ ОСТОВ (v211) — тем же полем притяжения, что и фокус: у мили пик
            // вплотную, у стрелка на дальности три. Отдельной формулы у погони нет и не нужно
            val chased = Memory.chaseTarget[c.id]
            // ...и ЦЕЛЬ ПАЧКИ МИЛИ во всех замыслах (v221, см. USE_MELEE_PACK_CELLS): притяжение к одной его цели
            // вместо суммы по всем — четыре мили в одну клетку-соседа, а не каждый к своей
            // Две правки «ноги за фокусом» ОТВЕРГНУТЫ живьём (14.09.2026, по 16 игр против Coldkimchi#1 и 8 против ●ω<♥♪#6,
            // разбор реплеев — docs, абзацы v268–v271): притяжение стрелка к фокусу армии во всех замыслах (v268) и клетка,
            // держащая фокус у стрелка, который его достаёт (v269). Счёт в полосе шума (9-7 и 8-8 при 7-9 у v267), а убийства
            // провалились: его боевых погибло к контакту +400 четыре и пять за 16 игр против 24 у v267 и 16–17 у v263–v266,
            // стволов на фокусе 0,62 против 0,75 (p = 0,009). Фокус, липкий с v267, часто стоит в четырёх, и стрелок, которого
            // тянут к нему, уходит от целей, которые достаёт; удержание клетки на фокусе стоило стрелку 51 огня в тик против 32
            // (p = 0,010), а стволов на цели не прибавило — фокус уходит своим шагом
            val focus = chased ?: (null)
                ?: if (i == Intent.FOCUS) (if (role == 0) weakestMelee else weakestFoe) else null
            val kite = i == Intent.KITE
            val rank = { p: Position ->
                val key = p.key
                when (role) {
                    0 -> scoreMelee(c, key, p, att, dan, focus)
                    1 -> scoreRanged(c, key, p, att, dan, focus)
                    else -> scoreHeal(c, key, p, att, dan)
                }
            }
            for (lvl in ttlMin downTo 1) {
                val ok = place(c, { p ->
                    (!kite || hisMelee.isEmpty() || hisMelee.minOf { getRange(p, it) } >= MELEE_HOLD_RANGE) &&
                        ttlAt(c, p.key, p) >= lvl
                }, rank)
                if (ok) { rec.gateLevels[minOf(lvl, rec.gateLevels.size - 1)]++; return true }
            }
            rec.gateFell.n++
            return false
        }

        /** Проход `freeze`: при healersOnly бойцам — «стой», их клетки заняты (безымянная вставка до v445). */
        fun passFreeze() {
            // ОТХОД — ТОЖЕ ПРИКАЗ (v174, оператор: «не должно быть ничего, что идёт мимо него»): бегство было веткой ВЫШЕ
            // командира. Теперь он сам уводит того, кому грозит гибель, — потерявшего за тик больше половины остатка или
            // стоящего под огнём без лечения рядом
            // ТОЛЬКО ЛЕКАРИ (v436, см. USE_COMMANDER_HEALERS_IN_CONTACT): в тики контакта, когда режим боя молчит, командир
            // ставит одних лекарей — по той же цене клетки (доставленное лечение минус входящий), а бойцы стоят там, где
            // стоят, и идут по веткам тактика. Их нынешние клетки заняты как «стой»: это препятствия для раздачи и тела для
            // экрана. Разбор v435 (Opus, 32 реплея): 79,8 % лекаре-тиков окна боя лекаря ставили ветки healMate/wall, а не
            // приказ, потому что режим боя против Coldkimchi включён в 25–40 % тиков (outmatched, retreat, posture, nofire)
            if (healersOnly) for (f in fighters) if (!healerOnly(f)) {
                val key = f.key
                out[f.id] = InfluenceMap.cell(f.x, f.y); taken.add(key)
            }
        }

        fun passRetreat() {
            // уходящие по его фокусу и их клетки — для встречи с лекарём (v276, см. проход лекарей)
            for (c in fighters) {
                if (c.id in out) continue
                val hurtBadly = (lostTick[c.id] ?: 0) * 2 >= c.hits && c.hits * 3 < c.hitsMax
                val alone = InfluenceMap.damageAt(c.x, c.y, combatEnemies) > 0.0 &&
                    army.none { it.id != c.id && hasHeal(it) && getRange(c, it) <= HEAL_RANGE }
                // ...и уходящий по его фокусу (v275, см. rotateByFocus): та же самая безопасная клетка, и среди равных — ближе к
                // нашему лекарю: встреча раненого с лекарём вне его досягаемости (у него — 3 → 1 клетка за пять тиков)
                val rotate = c.id in Memory.rotByFocus || c.id in Memory.stepOutIds
                if (!hurtBadly && !alone && !rotate) continue
                // УХОД ВЕДЁТ К ЛЕКАРЮ, А НЕ ПРОСТО ПРОЧЬ (v407, проект «армия не рассыпается», этап 1). Причина ухода
                // названа в условии прямо над этим: `alone` — «под уроном, и НЕТ ЛЕКАРЯ в досягаемости». Цель такого ухода
                // — лекарь; между тем клетка выбиралась по «подальше от врага», где о своих не сказано ничего, и слагаемое
                // медика стояло только у ветки ротации (v275/v276). Итог измерен по телам в реплеях (`replay.py bodies`):
                // в поражении против MetalicaX#13 на 60-м тике обе армии целы и стоят кучно (мы 11 крипов 13 466 хитов),
                // а к 80-му мы размазаны по четырнадцати клеткам с 4 973 хитами и ОДНИМ живым лекарем, тогда как он держит
                // строй в пяти клетках при 12 662 хитах и трёх лекарях; к сотому нас трое. В победе того же блока на 80-м
                // тике мы стоим в радиусе пяти со всеми тремя лекарями — и армия ОТРАСТАЕТ (10 301 -> 12 589 -> 14 531
                // хитов), потому что лечение возвращает части. То есть исход решает связность под уроном, а не размен:
                // сомкнутая армия лечится, рассыпавшуюся он ест по одному. Та же форма правки уже сработала на скаутах
                // (v373: третьим критерием бегства стал дом, матч с топ-1 перевернулся 3-5 -> 14-2)
                // ⚠️ ...И НЕ ПРОТИВ ТОГО, КТО ОХОТИТСЯ ЗА РАНЕНЫМИ (v408, по контролю). Против `MetalicaX#13` правка дала
                // 12-4 при базе 15-17, против фермера 8-0, но против `●ω<♥♪#5` — 4-12 за шестнадцать игр двумя блоками
                // подряд, то есть не шум. Причина названа замером v275: его ствол бьёт нашего крипа с НАИМЕНЬШЕЙ ДОЛЕЙ
                // ХИТОВ, тогда как MetalicaX бьёт ближайшего или лекаря. Сбор раненых у лекаря против добивающего раненых
                // — готовая мишень: мы сами сводим в одну точку тех, в кого он и целится, вместе с лекарем. Признак у бота
                // уже есть и проверен фактом (`huntsWounded`: две модели его выбора сверяются с правдой следующего тика)
                val medics = if (huntsWounded) (if (rotate) army.filter { it.id != c.id && healerOnly(it) } else emptyList())
                    else army.filter { it.id != c.id && hasHeal(it) && (rotate || !hasWeapon(it)) }
                place(c, { true }, rescue = true, rank = { p -> danOf(c, p.key) * 100 -
                    (armedEnemies.minOfOrNull { getRange(p, it) } ?: 0).toDouble() +
                    (medics.minOfOrNull { getRange(p, it) } ?: 0).toDouble() })
                if (rotate) out[c.id]?.let { rotatingMeet[c.id] = it }
            }
        }

        fun passStraggler() {
            // ОТСТАВШИЙ И ВЫРВАВШИЙСЯ ПОДТЯГИВАЮТСЯ (v177, оператор: «в момент начала боя у нас всегда был 1 крип где-то
            // впереди, и его очень быстро убивали»). Кулак ограничивал КАНДИДАТНЫЕ клетки, но крипа, уже стоящего вне
            // кулака, никто не возвращал: замер по записи разгрома — боевой крип отрывался от своих на 10 клеток при
            // среднем 1,8. Такому назначается шаг К ЯКОРЮ, и раньше всех прочих назначений
            if (fighters.size >= 3) {
                val (ax, ay) = Formation.median(fighters)
                for (c in fighters.sortedByDescending { maxOf(abs(it.x - ax), abs(it.y - ay)) }) {
                    if (c.id in out) continue
                    if (maxOf(abs(c.x - ax), abs(c.y - ay)) <= FIST_RADIUS + STRAGGLER_SLACK) continue
                    // ...но НЕ того, кто уже бьёт: увести мили из контакта — отдать разменную клетку даром (гейт 134/135)
                    if (armedEnemies.any { getRange(c, it) <= 1 }) continue
                    place(c, { p -> maxOf(abs(p.x - ax), abs(p.y - ay)) < maxOf(abs(c.x - ax), abs(c.y - ay)) },
                        { p -> danOf(c, p.key) + maxOf(abs(p.x - ax), abs(p.y - ay)) })
                }
            }
        }

        /** Проход `fields`: сброс притязаний, поле нужды в лечении, максимум уязвимости (безымянная вставка до v445). */
        fun passFields() {
            // ==================== ОЦЕНКА КЛЕТКИ ПОЛЕМ (v206, этап 6) ====================
            // Заменяет собой отборы `safeForRanged`, `behindMelee`, `behindLine`, `inHealReach` и россыпь ранговых
            // формул. Все они говорили ЗАПРЕТАМИ то, что является предпочтением, и потому запирали друг друга:
            // v200 нашёл круг, где «стрелок не впереди мили» и «мили стоит позади» вместе выталкивали стрелка за
            // дальность выстрела, отбор пустел, и крип падал в общий добор, который про дальность не знает вовсе.
            // Запрет здесь ровно один и он о жизни: клетка, где крип не переживёт хода.
            // ПРИТЯЗАНИЯ ПРОХОДОВ ДО ЭТОГО (retreat, straggler) СБРАСЫВАЮТСЯ — как сбрасывало общее поле `clearClaim` до v449: их
            // приказы и клетки остаются в `out` / `taken`, а отталкивания вокруг них раздача мили, стрелков и лекарей не видит.
            // Первая редакция v449 их считала, и гейт разошёлся в 115 логах из 139 с первого контакта — это часть меры, а не
            // утечки. Поле нужды — своё у этой раздачи (v449, см. DealRecord)
            claimed.clear()
            rec.need.stampHealNeed(living(army))
            for ((k, _) in cells) {
                val v = InfluenceMap.vulnerabilityOf(k)
                if (v > maxV) maxV = v
            }
        }

        fun passMelee() {
            // мили: по замыслу — вплотную к его вооружённому (напор), в самую безопасную клетку с целью (удержание) или
            // как можно дальше от его мили (уступка); среди равных всегда меньше входящего на следующий тик
            for (c in (if (healersOnly) emptyList() else melees).sortedBy { c -> armedEnemies.minOfOrNull { getRange(c, it) } ?: 99 }) {
                val ok = placeScored(c, 0, intentOf(c))
                // ДОБОР МИЛИ ПОМЕНЯЛ СМЫСЛ ВМЕСТЕ С ВОРОТАМИ (v208). Прежде `ok = false` значило «нет клетки вплотную
                // к его вооружённому, куда дотягивается лекарь», и шаг к врагу был верным ответом. С воротами
                // выживания `ok = false` значит «нет клетки, где я переживу ход», и тот же шаг посылает крипа
                // умирать: на стенде этот добор срабатывает в 3 % размещений. Ответ обратный — самая безопасная
                if (!ok) place(c, { true }, { p -> danOf(c, p.key) })
            }
        }

        fun passRanged() {
            // стрелки: цель в дальности, меньше всего входящего на следующий тик; при равенстве — дальше от его мили
            for (c in (if (healersOnly) emptyList() else rangeds).sortedBy { c -> cells.values.count { p -> getRange(c, p) <= 2 && armedEnemies.any { getRange(p, it) <= RANGED_RANGE } } }) {
                val ok = placeScored(c, 1, intentOf(c))
                // ...и КОГДА ВЫБОРА НЕТ, СТРЕЛОК ВЫХОДИТ ИЗ-ПОД МИЛИ, А НЕ ОСТАЁТСЯ СТРЕЛЯТЬ (v183, оператор: «рэнжи не
                // должны быть рядом с его мили»). Все замыслы требуют разом двух вещей — быть вне досягаемости его мили и
                // при этом доставать цель, — а такой клетки рядом с его строем часто нет вовсе, и общий фолбэк «любая
                // клетка, где меньше входящего» оставлял стрелка под ударом: замер тестовой игры 3d95be — наши стрелки в
                // одной клетке от его вооружённого 28 крипо-тиков и в двух ещё 31 из 117. Между «выстрелить» и «уцелеть»
                // выбирается уцелеть: выстрел стоит 60, стрелок — 1 200
                // ...НО САМАЯ БЕЗОПАСНАЯ — ЭТО САМАЯ БЕЗОПАСНАЯ ИЗ ТЕХ, ОТКУДА ДОСТАЁТ (v421). У ЛЕКАРЯ это правило стоит с
                // того же v183 двумя проходами ниже («самая безопасная из тех, откуда достаёт»), а у стрелка запасной ход
                // ранжируется ТОЛЬКО опасностью по всем клеткам подряд — и уводит из дальности, хотя среди достающих клеток
                // тоже есть самая безопасная. Чистая пара 16 тестовых игр против MetalicaX#13 (7-9, одна версия) называет
                // цену: доля тиков контакт+0…+30, где не меньше ТРЁХ наших стволов достают одного и того же его крипа, —
                // **1,00 во всех шести победах (31 тик из 31) против 0,73 ± 0,14 в поражениях**, пересечения нет. Порог
                // «три» не назначен, а посчитан: наш выстрел 60 (6 частей × 10), его лечение на одну цель 129–147 в тик,
                // значит два ствола (120) НИЖЕ лечения, три (180) выше. Измеренная сходимость 2,56 ± 0,27 в победах против
                // 2,03 ± 0,49 в поражениях — в поражениях наш огонь стоит ниже его лечения, и с него за весь бой не
                // снимается НИ ОДНОЙ части (0 из 15 матчей к контакту+20), при том что молчащих стволов нет ни у нас, ни у
                // него (0,0 % крип-тиков «цель в дальности, не выстрелил»). Выбор цели при этом работает одинаково (0,51
                // против 0,40, разделения нет) — не сходится ГЕОМЕТРИЯ, поэтому правка и стоит здесь, а не в ярусе цели
                if (c.id !in out) {
                    val keptReach = USE_RANGED_FALLBACK_KEEPS_REACH &&
                        place(c, { p -> armedEnemies.any { getRange(p, it) <= RANGED_RANGE } }, { p -> danOf(c, p.key) })
                    if (keptReach) rec.fallReach.n++ else { rec.fallAny.n++; place(c, { true }, { p -> danOf(c, p.key) }) }
                }
            }
        }

        /** Проход `advancing`: кто из бойцов идёт вперёд — лекарь их в точном режиме не считает (безымянная вставка до v445). */
        fun passAdvancing() {
            // лекари: в лечебной дальности от раненого бойца, вне огня следующего тика
            // ИДУЩИЙ ВПЕРЁД БОЕЦ В РЕЖИМ ТОЧНОЙ ЦЕНЫ НЕ ВХОДИТ (сужение v439, см. InfluenceMap.advancingWards): его назначенная клетка —
            // (с v449 множество живёт в записи раздачи — `rec.need.advancingWards`, класс `InfluenceMap.HealNeed`)
            // или, когда бойцов ведёт тактик (раздача одних лекарей), его ход прошлого тика — ближе к его стволам, чем нынешняя
            rec.need.advancingWards.clear()
            if (USE_HEAL_EXACT_IN_FIRE && armedEnemies.isNotEmpty()) for (f in fighters) {
                if (!hasWeapon(f)) continue
                val planned = out[f.id]
                val nowD = armedEnemies.minOf { getRange(f, it) }
                if (planned != null && !(planned.x == f.x && planned.y == f.y)) {
                    if (armedEnemies.minOf { maxOf(abs(planned.x - it.x), abs(planned.y - it.y)) } < nowD) rec.need.advancingWards.add(f.id)
                } else {
                    val prev = Memory.lastCell[f.id] ?: continue
                    val px = prev / 100; val py = prev % 100
                    if (px == f.x && py == f.y) continue
                    if (armedEnemies.minOf { maxOf(abs(px - it.x), abs(py - it.y)) } > nowD) rec.need.advancingWards.add(f.id)
                }
            }
        }

        fun passHealer() {
            for (c in healers) {
                // ЛЕКАРЬ ПРИ БОЙЦЕ (v142): близость главная, опасность лишь тай-брейк — прежний порядок весил опасность
                // стократно, и лекарь уходил в безопасную клетку ВНЕ дальности лечения; фолбэк вёл его туда же
                // ...и САМАЯ БЕЗОПАСНАЯ ИЗ ТЕХ, ОТКУДА ДОСТАЁТ (v183). Ранг вёл лекаря к БЛИЖАЙШЕМУ раненому (дистанция
                // ×100, опасность — тай-брейк), а ближайший раненый — тот, кто в контакте, поэтому лекарь шёл в рубку.
                // Замер тестовой игры 3d95ad: наши лекари в трёх клетках от его вооружённого 84 крипо-тика из 107 (78 %),
                // его — 64 из 129 (49 %); вплотную наши 21 против его 4, выстрелов по нашим лекарям 52 против 18 по его.
                // Первым умирал лекарь (t=64), и с ним рассыпался весь бой. Условие «достаёт» уже задано отбором клеток
                // (HEAL_RANGE − 1 с запасом на его шаг), поэтому внутри отбора решать должна опасность, а не близость —
                // прежний вариант с опасностью ×100 (USE_HEALERS_CLOSE=false) уводил лекаря ВНЕ дальности, потому что
                // менял вместе с рангом и сам отбор
                // ...и «безопасная» НЕ ЗНАЧИТ «на краю дальности»: вплотную лекарь лечит вчетверо сильнее, чем издали, и
                // ранг по одной опасности выталкивал его на два шага, где лечение падает втрое, — сценарий brawl+heals
                // проседал 12 056:17 958. Порядок такой же, как у него: сперва клетка ВПЛОТНУЮ К СВОЕМУ (полное лечение),
                // и среди таких — самая безопасная. Его лекари стоят рядом со своими стрелками 60 % крипо-тиков и при
                // этом в трёх клетках от наших вооружённых лишь 49 % — быть при своих и быть под огнём это разные вещи
                // ЛЕКАРЬ СТОИТ ПРИКРЫТЫМ, А НЕ В ПУСТОЙ КЛЕТКЕ (v186, разбор Coldkimchi#2). Тело лекаря — `h6m6`, лечащие
                // части СПЕРЕДИ, поэтому урон уничтожает именно их и делает это первыми. Замер разгрома 3d97c4: у обеих
                // сторон одно и то же тело, его лекари сохраняют лечащие части 804 крипо-тика из 804 (100 %), наши — 72
                // из 804 (8 %). К семидесятому тику двое наших из трёх уже без лечения вовсе, и дальше армия тает: лечение
                // за бой 4 604 против его 14 256, он заканчивает матч с 16 000 из 16 000 хитов, не потеряв ничего.
                // Разница не в открытости, а в ПРИКРЫТИИ: его лекари стоят ВНУТРИ строя (рядом с мили 86 % крипо-тиков,
                // со стрелками 63 %), наши — на фланге (16 % и 15 %) при глубине −2,7 и разбросе 3,6. «Самая безопасная
                // клетка» (v183) как раз и уводила их на фланг: пустая клетка безопаснее по входящему, но её достаёт его
                // стрелок, а тела своих не заслоняют. Здесь считается число СВОИХ вплотную — тех, кто примет выстрел
                // ЛЕКАРЬ НЕ ВСТАЁТ ВПЕРЕДИ СТРОЯ (v200, оператор): «наши хилеры оказались в первой линии и моментально
                // получили несколько выстрелов и потеряли возможность лечения». Экран (v186) считает ТЕЛА вокруг клетки и
                // ничего не знает о глубине, а `mates` — это раненые, то есть ровно те, кто на фронте: ранг тянул лекаря
                // вперёд по построению. Замер реплея 3d9943: глубина по оси на врага у мили −1,35, у стрелков +0,68, у
                // ЛЕКАРЕЙ +0,32 — они впереди мили, и в 155 тиках из 335 лекари в среднем ближе к врагу, чем мили; на
                // t=63, через три тика после контакта, один лекарь уже без лечащих частей. Условие простое и жёсткое:
                // хотя бы один свой боец стоит к врагу БЛИЖЕ, чем клетка лекаря, — считая по уже назначенным клеткам
                // ВСТРЕЧА РАНЕНОГО С ЛЕКАРЁМ (v276, разбор v275: наш уходящий раненый за пять тиков получает 198 урона при 144
                // лечения, его — 76 при 144; его лекарь сходится с раненым с трёх клеток до вплотную за пять тиков, а наш стоял там,
                // куда его поставила оценка поля нужды — по опасности у подопечного, которой у ушедшего из-под огня уже нет). Лекарь,
                // который за шаг встаёт вплотную к клетке, куда уходит раненый по его фокусу (Memory.rotByFocus, клетка — из
                // прохода отхода), встаёт туда, если переживёт её с порогом своего замысла; раненый — ближайший ещё без лекаря
                val medicFor = rotatingMeet.entries.filter { (rid, dest) -> rid !in medicked && getRange(c, dest) <= 2 }
                    .minByOrNull { getRange(c, it.value) }
                var met = false
                if (medicFor != null) {
                    val dest = medicFor.value
                    val (_, _, ttlMin) = weightsOf(intentOf(c))
                    if (place(c, { p -> getRange(p, dest) <= 1 && ttlAt(c, p.key, p) >= ttlMin }, { p -> danOf(c, p.key) })) {
                        medicked.add(medicFor.key)
                        rec.rotfMeet.n++
                        met = true
                        out[c.id]?.let { rec.need.saturateHeal(c, it.x, it.y, living(army)) }
                    }
                }
                // лекаря, поставленного проходом отхода, оценка по-прежнему переставляет — не встреча, не трогается (первая
                // редакция v276 это переразмещение снимала попутно, и гейт переменил 89 строк и уронил match30:camp)
                // ПРИБОР РЕЖИМА «В ЗОНЕ ОГНЯ» (v438, `hfire=`): лекарей, у которых доставка считалась по подопечным под огнём / всех /
                // из первых — поставленных вплотную к теряющему хиты; снимается ДО раздачи — насыщение меняет режим следующему
                val fireMode = !met && rec.need.deliveryFireMode(c, living(army))
                rec.hfireAll.n++; if (fireMode) rec.hfireN.n++
                val ok = met || placeScored(c, 2, intentOf(c)).also { placed ->
                    if (placed) out[c.id]?.let { rec.need.saturateHeal(c, it.x, it.y, living(army)) }
                }
                // ...и добор тоже вне досягаемости, пока такая клетка есть (v234)
                if (c.id !in out) place(c, { true }, { p -> danOf(c, p.key) })
                // ПРИБОР ПРИЛЕГАНИЯ (v435, `hadj=`): назначенная клетка лекаря вплотную к своему, терявшему хиты за прошлый тик, /
                // все назначения лекарей — та величина, по которой разбор E делил стороны (26 % лечений вплотную против 75 %)
                out[c.id]?.let { b ->
                    rec.hadjAll.n++
                    val losing = army.filter { a -> a.id != c.id && a.hits > 0 && (Memory.lastHits[a.id] ?: a.hits) > a.hits }
                    if (losing.any { a -> maxOf(abs(a.x - b.x), abs(a.y - b.y)) <= 1 }) { rec.hadjN.n++; if (fireMode) rec.hfireAdj.n++ }
                    // ...и НОРМИРОВАННЫЙ прибор (`hadjn=`): среди назначений, при которых кто-то из своих в дальности шага и
                    // лечения (HEAL_RANGE + 1) терял хиты, — доля клеток вплотную к такому; без него hadj делится и на тихие тики
                    if (losing.any { a -> getRange(a, c) <= HEAL_RANGE + 1 }) {
                        rec.hadjnAll.n++
                        if (losing.any { a -> maxOf(abs(a.x - b.x), abs(a.y - b.y)) <= 1 }) rec.hadjnN.n++
                    }
                }
                // ЗОНД РАЗДАЧИ ЛЕКАРЕЙ (v224, `hpick=`): по реплеям обеих сторон его лекари стоят вплотную к крипу под нашим
                // огнём 37 % лекаре-тиков, наши — 10 %, и в FIGHT свободная клетка вплотную к бойцу не опаснее своей есть в
                // 30–51 % лекаре-тиков. Зонд отвечает, какое слагаемое оценки увело лекаря от такой клетки: считает те же
                // слагаемые, что scoreHeal и place, для выбранной клетки и для лучшего свободного кандидата вплотную к бойцу
                // вне его стрелкового огня, и копит разницу «выбранная минус кандидат» по слагаемым
                run {
                    val b = out[c.id] ?: return@run
                    val (att, dan, ttlMin) = weightsOf(intentOf(c))
                    // кандидат — вплотную к бойцу ПЕРВОЙ ЛИНИИ (его клетка в его стрелковом огне) и сам вне огня: первое
                    // чтение зонда (4 игры) показало, что «вплотную к любому бойцу вне огня» выбирается в 63 % раздач — строй
                    // глубокий, боец рядом есть всегда, — а к тому, кого бьют, лекарь по реплеям стоит в 10 %
                    fun adjSafe(p: Position): Boolean = foeDist(p.x, p.y) > RANGED_RANGE &&
                        fighters.any { f -> f.id != c.id && hasWeapon(f) && cellOf(f).let { foeDist(it.x, it.y) <= RANGED_RANGE && maxOf(abs(it.x - p.x), abs(it.y - p.y)) <= 1 } }
                    fun terms(p: Position): DoubleArray {
                        val key = p.key
                        val scr = screenAt(c, p)
                        val fire = InfluenceMap.fireFieldAt(key)
                        val shielded = fire * (1.0 - 1.0 / (1.0 + SCREEN_SHARE * scr))
                        val deliver = InfluenceMap.healOf(c)
                        val raw = rec.need.attHealAt(key)
                        val pull = if (USE_HEAL_NEED_ACTUAL) rec.need.bestDeliveryAt(c, p.x, p.y, army)
                            else if (deliver <= 0.0 || raw <= 0.0) 0.0 else deliver * raw / (raw + deliver)
                        val self = p.x == c.x && p.y == c.y
                        val tenant = if (self) null else tenantOf(key, c)
                        return doubleArrayOf(-W_ATT * att * pull, W_DAN * dan * fire, if (USE_HEAL_NO_LINE && (pull > 0.0 || rec.need.deliveryFireMode(c, army))) 0.0 else -W_LINE * InfluenceMap.influenceOf(key),
                            -W_SCREEN * shielded, CLAIM_COST * claimAt(key), -stayBonus(c, p),
                            if (tenant != null) ALLY_CELL_COST else 0.0, GOAL_STEP_COST * goalCost(key))
                    }
                    rec.hpN.n++
                    if (adjSafe(b)) { rec.hpAdj.n++; return@run }
                    var best: Position? = null; var bestSc = Double.MAX_VALUE; var gated = 0
                    for ((key, p) in nearCells(c)) {
                        if (p.x == b.x && p.y == b.y) continue
                        if (key in taken || !adjSafe(p)) continue
                        if (!(p.x == c.x && p.y == c.y) && tenantOf(key, c) != null) continue
                        if (ttlAt(c, key, p) < ttlMin) { gated++; continue }
                        val sc = terms(p).sum()
                        if (sc < bestSc) { bestSc = sc; best = p }
                    }
                    val a = best
                    if (a == null) { if (gated > 0) rec.hpGate.n++; return@run }
                    rec.hpAvail.n++
                    val tb = terms(b); val ta = terms(a)
                    for (i in tb.indices) rec.hpDelta[i] += tb[i] - ta[i]
                }
            }
        }

        /** Проход `keeper`: стоящий на нашем флаге без приказа получает «стой» (безымянная вставка до v445). */
        fun passKeeper() {
            // ХРАНИТЕЛЬ ФЛАГА — ПО ПРИКАЗУ (v174): крип на НАШЕМ флаге стоит по приказу командира, а не по отдельной ветке
            // удержания; снять его может только командир — решив собрать отряд — или опасность, которая приходит приказом
            for (c in fighters) {
                if (c.id in out) continue
                // ...и клетку, уже отданную кому-то приказом, хранитель не занимает повторно: без этой проверки одна
                // клетка доставалась двоим — прибор ловил это как clash=1 (v176)
                val key = c.key
                if (ourFlagCells.contains(key) && key !in taken) { taken.add(key); out[c.id] = InfluenceMap.cell(c.x, c.y) }
            }
        }

        fun passStripped() {
            // раздетые: прочь из огня — в бою от них пользы нет, а его выстрелы они на себя собирают исправно
            for (c in (if (healersOnly) emptyList() else stripped))
                place(c, { true }, { p -> danOf(c, p.key) * 100 -
                    (armedEnemies.minOfOrNull { getRange(p, it) } ?: 0).toDouble() })
        }

        fun passCatchall() {
            // ...и ВООРУЖЁННЫЙ не остаётся без места (v165): расстановка при командире молчит, и тот, кому клетки не
            // хватило, уходил по общим веткам — гейт ловил это как уничтоженную армию (match32:army). Лекарей и раздетых
            // этот добор не трогает: у них свои назначения выше, и перехват их портил (133 из 135)
            for (c in melees + rangeds) {
                if (c.id in out) continue
                place(c, { true }, { p -> danOf(c, p.key) * 10 +
                    maxOf(abs(p.x - c.x), abs(p.y - c.y)).toDouble() })
            }
        }

        fun passPinned() {
            // ЗАЖАТОГО БЬЁМ (v264, разбор стены лечения серии v263). Против стоячей линии с лекарями наш урон возвращается
            // лечением на 81–99 %, и пересиливает его одно — удар нашего мили: 192–240 за удар против 71–91 его лечения на
            // цели в тик; из девяти его погибших боевых восемь умерли с уроном нашего мили. Но его линия отступает на нашей
            // скорости, и погоня касания не даёт: со двух клеток вплотную к следующему тику 3–20 %, а нырок к лекарю — 550
            // подходов без единой смерти и без просадки его лечения. Касание дают моменты, когда уйти ему НЕКУДА (см.
            // pinnedAt): тогда в следующем тике он в досягаемости клетки удара всегда — 71 из 71 по реплеям, — а мы вставали
            // туда в 16–57 %, потому что оценка у его строя весит опасность клетки выше удара. Проход идёт ПОСЛЕДНИМ: только
            // здесь план остальных окончателен, и «наш крип закрывает ему отход» значит его ИТОГОВУЮ клетку, а не нынешнюю,
            // которую он вот-вот освободит (первая редакция шла до стрелков и лекарей и ставила мили к «зажатому», которого
            // их же шаг отпускал). Мили, которого оценка уже поставила вплотную к зажатому, не трогается; остальной получает
            // клетку удара, если переживёт её с порогом своего замысла, иначе прежний приказ. Порог — тот же ttlMin, что у
            // оценки, новых чисел нет; кайт свой запрет сохраняет — к его мили ближе двух не подходит
            val pinFoes = combatEnemies.filter { e -> InfluenceMap.profileOf(e).let { it.melee + it.ranged + it.heal > 0.0 } }
            val hisStuck = HashSet<Int>()
            for (e in combatEnemies) if (e.fatigue > 0) hisStuck.add(e.key)
            for (c in (if (healersOnly) emptyList() else melees).sortedBy { c -> armedEnemies.minOfOrNull { getRange(c, it) } ?: 99 }) {
                val near = pinFoes.filter { getRange(c, it) <= COMMAND_REACH + 1 }
                if (near.isEmpty()) continue
                val ours = HashSet<Int>()
                for (f in army) if (f.hits > 0 && f.id != c.id) { val q = cellOf(f); ours.add(q.key) }
                fun strikes(p: Position) = near.any { e -> getRange(p, e) <= 1 && pinnedAt(p, e, ours, hisStuck) }
                val cur = out[c.id]
                if (cur != null && strikes(cur)) continue
                val ttlMin = weightsOf(intentOf(c)).third
                val kite = intentOf(c) == Intent.KITE
                if (cur != null) { out.remove(c.id); taken.remove(cur.key) }
                val ok = place(c, { p ->
                    (!kite || hisMelee.isEmpty() || hisMelee.minOf { getRange(p, it) } >= MELEE_HOLD_RANGE) &&
                        strikes(p) && ttlAt(c, p.key, p) >= ttlMin
                }, { p -> danOf(c, p.key) })
                if (!ok && cur != null) { out[c.id] = cur; taken.add(cur.key) }
            }
        }

        /** Проход `audit`: три числа согласованности строя — приборы plan* (безымянный эпилог до v445). */
        fun passAudit() {
            // ПРИБОР СОГЛАСОВАННОСТИ (v200): меряется РЕЗУЛЬТАТ раздачи, а не факт вызова правила — сколько стрелков
            // получили клетку с целью в дальности, сколько мили остались в дальности лечения, сколько лекарей стоят за
            // линией. Три числа отвечают ровно на три замечания оператора и видны в строке `t=` каждым тиком
            rec.planGunsAll.n = rangeds.size
            rec.planGunsIn.n = rangeds.count { c -> out[c.id]?.let { p -> armedEnemies.any { e -> getRange(p, e) <= RANGED_RANGE } } == true }
            rec.planMeleeAll.n = melees.size
            rec.planMeleeHealed.n = melees.count { c -> out[c.id]?.let { p -> healerCells.any { h -> maxOf(abs(p.x - h.x), abs(p.y - h.y)) <= HEAL_RANGE } } == true }
            rec.planHealAll.n = healers.size
            rec.planHealBehind.n = healers.count { c ->
                val p = out[c.id] ?: return@count false
                val dp = foeDist(p.x, p.y)
                fighters.any { f -> f.id != c.id && hasWeapon(f) && cellOf(f).let { foeDist(it.x, it.y) } < dp }
            }
        }

        /** ПРОХОДЫ РАЗДАЧИ: порядок списка = порядок исполнения, другого описания порядка нет. Имя прохода — тег прибора `pass=`
         *  (его печатает `rung t=`): клетка, выданная `place` на глубине 0, записывается на проход, который сейчас идёт. */
        val passes = listOf(
            Pass("freeze") { passFreeze() },
            Pass("retreat") { passRetreat() },
            Pass("straggler") { passStraggler() },
            Pass("fields") { passFields() },
            Pass("melee") { passMelee() },
            Pass("ranged") { passRanged() },
            Pass("advancing") { passAdvancing() },
            Pass("healer") { passHealer() },
            Pass("keeper") { passKeeper() },
            Pass("stripped") { passStripped() },
            Pass("catchall") { passCatchall() },
            Pass("pinned") { passPinned() },
            Pass("audit") { passAudit() },
        )

        // не `run`: внутри носителя зовётся stdlib-`run { … }` (зонд hpick), и одноимённый член когда-нибудь перехватил бы его молча
        fun distribute() = runPasses(passes, rec.tally) { i, tag -> passIndex = i; passTag = tag }
    }
    val deal = Deal()
    deal.distribute()
    return deal.rec
}

internal class ArmyFireAndHealOut(
)

/** ОГОНЬ И ЛЕЧЕНИЕ АРМИИ ЗА ТИК (v256, этап 10): хвост runArmy после покрипного цикла — перепись «почему» (why t=, why-sum), стрелки врага на прошлом тике для прогноза (prevShooters), назначение огня и лечения и исполнение. Перенесено дословно. */
internal fun PainAndGain.armyFireAndHeal(ctx: Ctx, meas: ArmyMeasuresOut, targ: ArmyTargetsOut): ArmyFireAndHealOut {
    cpuMark("moves")
    if (TRACE_WHY && DEBUG_LOG && whyLines.isNotEmpty()) { println("why t=${getTicks()}: " + whyLines.joinToString(" ")); whyLines.clear() }
    if (TRACE_WHY && DEBUG_LOG && getTicks() % (LOG_EVERY * 10) == 0 && whySum.isNotEmpty()) {
        println("why-sum t=${getTicks()}: " + whySum.entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key}=${it.value}" })
        whySum.clear()
    }
    prevShooters = meas.combatEnemies.map { val p = InfluenceMap.profileOf(it); Shooter(it.key, p.ranged, p.melee) }
    // ...командирская цель НЕ подменяет цель стрельбы (v138): проведённая сюда, она уронила гейт до 129/131 и
    // дала m33:kite 0:21 135 — армия бросала всё ради назначенной цели. Она влияет мягко, через порядок focusOrder
    // огонь тоже по приказу командира (v161): назначения считаются на всю силу, включая захватчиков с оружием
    commandFire(ctx.army + ctx.runners.filter { hasWeapon(it) }, meas.enemyCreeps, targ.focusTarget, targ.focusOrder, fireOf)
    commandHeal(ctx.army, meas.enemyCreeps, healOf)
    // ...и отряжённый лекарь без оружия лечит (v240): до этого healAndShoot получал бегунов только с оружием
    healAndShoot(ctx.army + ctx.combatRunners, meas.allies, meas.enemyCreeps, targ.focusTarget, targ.focusOrder)
    cpuMark("shoot")
    return ArmyFireAndHealOut(
    )
}

/** КУЛАК И В ПРОГНОЗЕ (v159): командир не пускает крипа дальше FIST_RADIUS от якоря, а раскатка симуляции пускала —
 *  прогноз считал бой, которого не будет, и мог хвалить замысел, растаскивающий армию. Сближение прогноза с
 *  настоящим боем — единственный приём, который командира и двигал: согласие с целью фокуса подняло его с 25 % до
 *  31 %, раскатка по своему замыслу — до 50 % против けろびー#1. Якорь тот же — медиана живых.
 *  ОТВЕРГНУТО ГЕЙТОМ: 134 из 135 — camp match31 из 20 265:14 589 в 15 891:23 063, причинность проверена прямым
 *  отключением тумблера. Командир правит там ВСЕГО 20 тиков (cmd=12/20, остальное блокирует отход), и этих
 *  двадцати хватает, чтобы проиграть гонку очков: согласованный с кулаком прогноз выбирает замысел, который держит
 *  армию вместе, а в гонке нужен темп. Вторая редакция — разрешить шаг, если он ПРИБЛИЖАЕТ к якорю, как и в бою, —
 *  дала те же числа: дело не в заморозке отставшего, а в самом ограничении. Стоит перепроверить живьём против
 *  MetalicaX#10, где кулак и дал 8-8: гейт судит формы, где командиру почти нет места. Третья проверка, уже с
 *  вернувшимися условиями отхода и затора: camp 14 937:19 509 — падает всё равно, значит дело в самом ограничении
 *  прогноза, а не в том, когда командир включён. */
/** ПОЛУЧАЕМЫЙ УРОН В ПРОГНОЗЕ (v159): profileOf множит на эффекты флагов НАШ удар и лечение, а входящий урон
 *  симуляция вычитала как есть — при том что флаг вешает на ВЛАДЕЛЬЦА ещё и +10 % получаемого урона
 *  (EFF_DAMAGE_TAKEN_MODIFIER, замерено в живых effects). При двух-трёх флагах прогноз ошибался в выживаемости на
 *  десятки процентов, и ровно эта величина решает, стоит ли вступать в размен. */
internal const val ALLY_CELL_COST = 25.0

internal const val CHAIN_DEPTH = 4

// вес замерен: тройка гейт роняет (134 из 135) и исполнения не добавляет (те же 14 из 141), единица держит 135
internal const val PATH_DANGER_W = 1.0

internal const val PATH_BLOCKED_COST = 1000.0

internal const val LETHAL_CELL_COST = 10000.0

/** БОЛОТО — ЧЕТЫРЕ ТИКА НЕПОДВИЖНОСТИ (v183, оператор: «часть армии вязнет в болоте и дальше не может
 *  двигаться»). Знала о нём только старая ветка движения; командир, забравший все приказы, — нет. */
internal const val SWAMP_CELL_COST = 400.0

internal const val STRAGGLER_SLACK = 2

// ---------- поле цели (v204, этап 5) ----------
/**
 * Один тик пути стоит одной единицы урона в тик. Это не подобранный вес, а решение о том, ГДЕ цель имеет
 * право решать: опасность клетки в бою — десятки и сотни, поэтому направление разбивает ничьи там, где
 * опасность плоская, и не перебивает её нигде. Ровно в плоской области и стоял прежний провал: когда ни
 * одна из восьми соседних клеток не удовлетворяла требованию роли, требование молча отбрасывалось, и крип
 * оставался на месте с оценкой «везде одинаково безопасно».
 */
// ---------- оценка клетки полем (v206, этап 6) ----------
// ДИСЦИПЛИНА ЕДИНИЦ — главная защита от повторения `PAIR_W_INFLUENCE = 0.1` рядом с 30 и 50, и от
// `RANGED_BEHIND_COST = 40` рядом с incNext × 100. Каждое слагаемое оценки — в единицах УРОНА ЗА ТИК
// (то, что возвращает profileOf), каждый вес безразмерный в диапазоне [0.1, 2.0]. Слагаемое больше не
// может случайно оказаться в тысячу раз крупнее соседнего. То, что обязано быть ЗАПРЕТОМ (клетка, где
// крип умрёт за тик), — не предпочтение, а ограничение, и уезжает в ворота, где ему и место.
internal const val W_ATT = 1.0        // притяжение к цели

internal const val W_DAN = 1.0        // опасность клетки

internal const val W_FRONT = 0.5      // гребень контакта (уязвимость = 2·min(наши, его)) — мили идёт по фронту

internal const val W_SAG = 0.5        // ...и туда, где фронт проседает

internal const val W_HEALCOVER = 1.0  // мили держится там, куда доходит наше лечение

internal const val W_LINE = 0.5       // влияние (наши − его): стрелок и лекарь стоят там, где сильнее мы

internal const val W_SCREEN = 1.0     // тела своих вокруг лекаря — те, кто примет выстрел

/** Клетка рядом с уже назначенной стоит одного шага задержки — те же единицы, что у GOAL_STEP_COST. */
internal const val CLAIM_COST = 1.0

/** Остаться на месте стоит на шаг дешевле: против дёрганья на одну клетку и лишней усталости. */
internal const val STAY_BONUS = 1.0

/** Гребень фронта — там, где уязвимость не ниже этой доли своего максимума (доля, не абсолют). */
internal const val FRONT_RIDGE = 0.15

/** Экран своих входит ДЕЛИТЕЛЕМ входящего, а не слагаемым в тысячу: ровно та ошибка, что записана в
 *  коде про HEALER_SCREEN = 1000. Каждое тело рядом снимает четверть доли входящего. */
internal const val SCREEN_SHARE = 0.25

internal const val GOAL_STEP_COST = 1.0

/** Недостижимая клетка: дороже любого достижимого пути по полю 100x100, но не запрет. */
internal const val GOAL_UNREACHABLE = 300.0

/** Затравка — клетка, откуда строй достаёт не меньше этой доли лучшего в округе. */
internal const val SEED_ENTER = 0.60

/** ...и удерживается, пока не упала ниже этой: гистерезис против мигания очага (доля, а не абсолют —
 *  масштаб поля меняется втрое за матч, и абсолютный порог однажды перестанет совпадать). */
internal const val SEED_HOLD = 0.45

/** Затравки ищутся в коробке вокруг медианы армии: кулак плюс запас на подход. */
internal const val SEED_BOX = FIST_RADIUS + 8

/** Очаг сменился, только если его центр уехал дальше этого — иначе держим прежний. */
internal const val SEED_MOVE = 6

internal var goalTick = -1

/** План строя (см. USE_BLOCK): фронт — наши мили (без слотов, дерутся по своим правилам), стрелки — в ряду за центром
 *  мили на RANGED_RANGE − d клеток, где d — дистанция фронта до ближайшей угрозы (вплотную — в 2, достают на клетку
 *  за фронт, как стрелки врага за его мили; враг в 3 — в одном ряду с мили, иначе не достают вовсе: матч 17 — линия
 *  врага встала в 3 от наших мили, его мили не лезли, наши стрелки «в 2 за мили» стояли в 5–6 от целей и молчали, а
 *  наш фронт били бесплатно), тыл на клетку дальше. Первая правка делила случаи «есть их мили в 3 / нет» и меняла
 *  местами ряды стрелков и мили — при подходе толпы случай менялся через пару тиков, и строй переворачивался под
 *  ударами (стенд m6 sleeper, армия потеряна). Ряды поперёк оси центр → ближайшая группа врагов с боем; слоты по
 *  порядку SLOT_ORDER от середины ряда, стены и клетки мили пропускаются; крип берёт ближайший свободный слот
 *  своего ряда. */
/** КОМАНДИР (v137, см. USE_COMMANDER): одно решение на всю армию в бою с сомкнутым блобом. Раздаёт КЛЕТКИ — по одной
 *  на крипа, — считая для каждой опасность СЕЙЧАС и опасность НА СЛЕДУЮЩИЙ ТИК (его мили в MELEE_KEEP_RANGE шагнёт и
 *  ударит). Мили ставятся первыми — туда, где они достают его вооружённых; стрелки вторыми — где есть цель в
 *  RANGED_RANGE и меньше всего входящего на следующий тик; лекари последними — в HEAL_RANGE от раненого и вне огня.
 *  Клетка занимается один раз, крипы обслуживаются от самого стеснённого, поэтому свои друг друга не загораживают.
 *  Атаки идут своим проходом и назначением не затрагиваются: крип, уже стоящий где надо, просто бьёт. */

// ==================== приборы стадии: счётчик живёт у того, кто считает (v447, план архитектуры, 4.7 и этап 6) ====================
// Объявления перенесены из Instruments.kt дословно; Instruments их читает и печатает, текст строк прежний.

/** Лечение по дефициту (v233): лечений в полного / всех, лечения сверх подтверждённой нужды / доставлено, переназначений. */
internal val hfullN = Gauges.counter("hfull")

internal val hfullAll = Gauges.counter("hfull", 1)

internal val hoverSum = Gauges.counter("hover")

internal val hdelivSum = Gauges.counter("hover", 1)

internal var goalRebuilds = 0

internal var goalHolds = 0

/** Стена лечения (v270, hwallx=уступлено/дальних): дальних лечений жертвы стены, и сколько из них уступило лечению
 *  вплотную раненого соседа, которое доставляет больше. */
internal val hwallFar = Gauges.counter("hwallx", 1)

internal val hwallYield = Gauges.counter("hwallx")

internal val hwallHeals = Gauges.counter("hwallh")

internal val hwallHealsAll = Gauges.counter("hwallh", 1)

/** Перекрытие (v391, прибор ovl=): сколько наших стрелков ДОСТАЁТ лучшую его цель — против `conc`, который считает,
 *  сколько выстрелов в неё легло. Разница между «могло» и «легло» и есть предмет боя с кулаком. */
internal val ovlSum = Gauges.counter("ovl")

internal val ovlTicks = Gauges.counter("ovl", 1)

internal val ovlThree = Gauges.counter("ovl", 2)

internal val ovlFour = Gauges.counter("ovl", 3)

internal val concSum = Gauges.counter("conc", perWindow = true)                          // сумма «наибольшее число выстрелов в одну цель за тик» с прошлой строки t=

internal val concTicks = Gauges.counter("conc", 1, perWindow = true)                        // тиков с выстрелами с прошлой строки t=

/** ...и то же НАКОПЛЕННОЕ за матч (v217). Прежняя пара чистится после каждой строки `t=` (см. LOG_EVERY),
 *  поэтому в разгроме, где последнее окно прошло без единого выстрела, прибор показывал ноль замеров —
 *  и по серии его было не сложить. Порог, ради которого он существует, записан в файле пятикратно:
 *  при 216 лечения в тик цель пробивают четыре-пять стволов. */
internal val concAll = Gauges.counter("concall")

internal val concAllTicks = Gauges.counter("concall", 1)

/** Пара «крипо-тиков веером / всех крипо-тиков огня» (v218). Веер (`rangedMassAttack`) не кладёт ничего в
 *  `shotsAt`, поэтому тик, где все стрелки ушли в веер, НЕ ПОПАДАЕТ ДАЖЕ В ЗНАМЕНАТЕЛЬ `conc` — измеренные
 *  1,67–1,94 ствола на цель сняты по подмножеству тиков, и без этой пары их нельзя читать. */
internal val fanShots = Gauges.counter("concfan")

internal val fireShots = Gauges.counter("concfan", 1)

internal val mconcTicks = Gauges.counter("mconc", 1)

// ==================== межтиковое состояние и константы стадии (до v454 — члены object PainAndGain; второй шаг архитектуры, этап 1) ====================

internal var goalCx = -1

internal var goalCy = -1

internal var goalField: IntArray? = null

internal var goalSeeds: IntArray = IntArray(0)

// ==================== приборы стадии, бывшие членами object PainAndGain (v455, второй шаг архитектуры, этап 2) ====================

/** Пара «сумма наибольшего числа ударов мили в одну цель за тик / тиков с ударами» и максимум (v221).
 *  Разбор блоб-поражений двух серий: во всех через 40 тиков после контакта он не потерял ни одного стрелка
 *  и ни одного мили, мы — стрелков и мили; его четыре мили кладут 960 в одну нашу цель в 1200 хитов. */
internal val mconcAll = Gauges.counter("mconc")

internal val mconcMax = Gauges.counter("mconcmax")

internal val concMax = Gauges.counter("concmax")
