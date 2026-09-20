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
internal fun strike(creep: Creep, enemyCreeps: List<Creep>, focusTarget: Creep?, focusOrder: List<Creep>) {
    if (!hasMelee(creep)) return
    val adjacent = enemyCreeps.filter { creep.getRangeTo(it) <= 1 }
    val focusDying = focusTarget != null && creep.getRangeTo(focusTarget) <= 1 && focusTarget.hits <= InfluenceMap.profileOf(creep).melee
    val ordered = FireBook.fireOf[creep.id]?.let { id -> adjacent.firstOrNull { it.id == id } }
    val target: Creep? = when {
        // приказ командира и для удара (v161): цель назначена по всей армии, а не по тому, кто оказался рядом.
        // Исключение одно — крип, которого мы ДОБИВАЕМ этим ударом: добить дороже, чем исполнить приказ
        focusDying -> focusTarget
        ordered != null -> ordered
        focusTarget != null && creep.getRangeTo(focusTarget) <= 1 -> focusTarget
        adjacent.isNotEmpty() -> focusOrder.firstOrNull { creep.getRangeTo(it) <= 1 } ?: adjacent.minByOrNull { it.hits }
        else -> null
    }
    target?.let {
        bookDamage(it, InfluenceMap.profileOf(creep).melee * InfluenceMap.takenOf(it), enemyCreeps, ordered = it === ordered, melee = true)
        Executor.attack(creep, it); lastFireTick = getTicks(); FireBook.strikesAt[it.id] = (FireBook.strikesAt[it.id] ?: 0) + 1
    }
}

/** Лечение врага, дотягивающееся до цели за тик: вплотную — полное, в HEAL_RANGE — дальнее (треть). То же выражение, что у
 *  `killable` в `commandFire`; одно на оба места (v468). */
internal fun healCoverOn(enemies: List<Creep>, e: Creep): Double = enemies.sumOf { h ->
    val pr = InfluenceMap.profileOf(h)
    val d = h.getRangeTo(e)
    if (pr.heal <= 0.0 || d > HEAL_RANGE) 0.0 else if (d <= 1) pr.heal else pr.heal / 3.0
}

/** ПРИБОР ПЕРЕБОЯ (v468, дефект 7 постановки). `damageBooked` с v140 ПИСАЛСЯ и не читался: `booked(t)` объявлена в `shoot` и в
 *  выборе цели не участвует, так что правило «стрелок переходит к следующей цели, когда по этой уже расписано достаточно» не
 *  действовало ни разу. Здесь считается его цена, а не чинится: назначение урона по цели, которую УЖЕ добивает расписанное
 *  (с учётом лечения, дотягивающегося до неё), — лишнее целиком; из них по приказу командира и ударом мили; сумма урона сверх
 *  добивания (у частично лишнего — только хвост); всего назначений. Удар мили записывается тоже — без него книга неполна и
 *  перебой по цели, которую рубят четверо, невидим. Печать — `okill=`. */
internal fun bookDamage(target: Creep, dmg: Double, enemies: List<Creep>, ordered: Boolean, melee: Boolean) {
    val already = FireBook.damageBooked[target.id] ?: 0.0
    val need = target.hits + healCoverOn(enemies, target)
    okAll.n++; okBooked.x += dmg
    if (already >= need) { okDead.n++; if (ordered) okDeadOrdered.n++; if (melee) okDeadMelee.n++ }
    val over = minOf(dmg, maxOf(0.0, already + dmg - need))
    okOver.x += over
    // ...и ПРЕМИССА ПРАВИЛА (`okkill=`): «лишний» урон лишний, только если цель ПОГИБЛА, — в v140 правило отвергнуто со словами
    // «её держат три лекаря»; хвост сверх книги запоминается по цели и судится следующим тиком по её живости
    if (over > 0.0) FireBook.overByTarget[target.id] = (FireBook.overByTarget[target.id] ?: 0.0) + over
    FireBook.damageBooked[target.id] = already + dmg
}

/** Суд премиссы перебоя (v468): цели, расписанные насмерть в прошлом тике огня, — погибли ли; их хвост урона сверх книги —
 *  к погибшим (лишний взаправду) или к выжившим (лечение перекрыло книгу, лишнего не было). Зовётся в начале стадии огня;
 *  тик без армии откладывает суд на тик — цель, погибшая за это время, считается погибшей. */
internal fun judgeOverkill(enemyCreeps: List<Creep>) {
    for ((id, over) in FireBook.overByTarget) {
        okBookedDead.n++
        if (enemyCreeps.none { it.id == id && it.hits > 0 }) { okDied.n++; okOverDied.x += over } else okOverHeld.x += over
    }
    FireBook.overByTarget.clear()
}

internal fun healAndShoot(active: List<Creep>, allies: List<Creep>, enemyCreeps: List<Creep>, focusTarget: Creep?, focusOrder: List<Creep>) {
    FireBook.shotsAt.clear()
    FireBook.strikesAt.clear()
    judgeOverkill(enemyCreeps)
    val healDone = HashMap<String, Int>()
    val incoming = HashMap<String, Int>()
    // подтверждённый входящий (v233, см. USE_HEAL_BY_DEFICIT): адресный огонь этого тика или потеря прошлого
    fun confirmed(target: Creep) = (Wall.addressedDmg[target.id] ?: 0.0) > 0.0 || (Wall.lostTick[target.id] ?: 0) > 0
    // ...И ОЖИДАЕМЫЙ УРОН — АДРЕСНЫЙ (v292). Нужда была «недостача + весь урон, который его стволы МОГУТ положить в клетку»
    // (damageAt), и полный крип у фронта выглядел нуждающимся, хотя его стволы бьют другого: аудит 112 игр (v289–v290,
    // Opus) — наше лечение полных и не битых в этот тик 16,7 хита за тик контакта против его 0,0, не той цели 9,9 против
    // 3,7 (p < 0,001); против ●ω<♥♪#6, который бьёт наименьшую долю хитов в досягаемости, полный крип — не его цель, пока
    // есть раненые. Ожидаемый урон берётся из предсказателя его выбора (rotateByFocus: две модели, сверка с фактом, 92 %
    // попаданий против ●ω) — той модели, что попадает чаще; пока сверок меньше окна — прежний damageAt
    fun expectedOn(target: Creep): Int = TacticianState.focusPredDmg?.let { (it[target.id] ?: 0.0).toInt() }
        ?: InfluenceMap.damageAt(target.x, target.y, enemyCreeps).toInt()
    fun need(target: Creep): Int {
        val deficit = target.hitsMax - target.hits
        val expected = incoming.getOrPut(target.id) { expectedOn(target) }
        return deficit + expected - (healDone[target.id] ?: 0)
    }
    // нужда по подтверждённому — для прибора и для переназначения соседу (не зависит от тумблера)
    fun needConfirmed(target: Creep): Int {
        val deficit = target.hitsMax - target.hits
        val expected = if (confirmed(target)) maxOf((Wall.addressedDmg[target.id] ?: 0.0).toInt(), Wall.lostTick[target.id] ?: 0) else 0
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
            val ordered = FireBook.healOf[creep.id]?.let { id -> candidates.firstOrNull { it.id == id } }
            // ...И ПРИКАЗ ДЕЙСТВУЕТ НА ВСЕЙ ЛЕЧЕБНОЙ ДАЛЬНОСТИ (v183, оператор: «не должно быть ничего, что идёт
            // мимо командира»). Прежде назначенный пациент брался, только если он ВПЛОТНУЮ; иначе выбор перехватывал
            // местный ранг соседей — и приказ отбрасывался тем, что рядом просто кто-то стоит
            // СТЕНА ЛЕЧЕНИЯ (v228, см. USE_HEAL_WALL): удержимую жертву лечит каждый лекарь в дальности, вплотную — полностью
            if (Wall.victimSaveable) hwallHealsAll.n++
            val wallTarget = if (Wall.victimSaveable) Wall.victimNow?.takeIf { v -> !v.spawning && creep.getRangeTo(v) <= HEAL_RANGE } else null
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
    FireBook.damageBooked.clear()
    val most = FireBook.shotsAt.values.maxOrNull() ?: 0
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
    val mostStrikes = FireBook.strikesAt.values.maxOrNull() ?: 0
    if (mostStrikes > 0) {
        mconcAll.n += mostStrikes; mconcTicks.n++
        if (mostStrikes > mconcMax.n) mconcMax.n = mostStrikes
    }
}

internal fun shoot(creep: Creep, enemyCreeps: List<Creep>, focusTarget: Creep?, focusOrder: List<Creep>) {
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
        // ⚠️ ...не переходит (v468, дефект 7 постановки): книга здесь только ПИСАЛАСЬ — `booked(t)` объявлялась и в выборе
        // цели ниже не читалась, правило не действовало с v140. Цена считается прибором `okill=` (см. bookDamage)
        // фокус-цель вне дальности — добиваем самого раненого боевого в дальности (безоружных — в последнюю очередь)
        val ordered = FireBook.fireOf[creep.id]?.let { id -> enemyCreeps.firstOrNull { it.id == id } }
        val target = when {
            // приказ командира — первым: он назначал цель, зная всю армию и всё, что до цели дотягивается (v161)
            ordered != null && creep.getRangeTo(ordered) <= RANGED_RANGE  -> ordered
            focusTarget != null && creep.getRangeTo(focusTarget) <= RANGED_RANGE  -> focusTarget
            else -> focusOrder.firstOrNull { creep.getRangeTo(it) <= RANGED_RANGE  }
                ?: massPool.minByOrNull { it.hits }
        }
        target?.let {
            bookDamage(it, InfluenceMap.profileOf(creep).ranged * InfluenceMap.takenOf(it), enemyCreeps, ordered = it === ordered, melee = false)
            Executor.rangedAttack(creep, it); FireBook.shotsAt[it.id] = (FireBook.shotsAt[it.id] ?: 0) + 1; lastFireTick = getTicks(); fireShots.n++
        }
    }
}

internal fun commandFire(army: List<Creep>, enemies: List<Creep>, focus: Creep?, order: List<Creep>,
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
        scoutFoe(e) && WorldState.flagsNow.any { !it.ours && getRange(e, it.pos) <= 1 } }
    val dangerous = live.filter { e -> InfluenceMap.profileOf(e).let { it.melee + it.ranged + it.heal > 0.0 } } + scoutsHere
    val pool = if (dangerous.isNotEmpty()) dangerous else live
    val killable = pool.filter { e ->
        val burst = shooters.filter { reach(it, e) }.sumOf { c ->
            val pr = InfluenceMap.profileOf(c)
            (if (hasRanged(c)) pr.ranged else pr.melee) * InfluenceMap.takenOf(e)
        }
        val cover = healCoverOn(enemies, e)
        burst >= e.hits + cover
    }.minByOrNull { it.hits }
    for (c in shooters) {
        // ПРЕСЛЕДОВАТЕЛЬ СТРЕЛЯЕТ В СВОЙ ОСТОВ (v211). Общее правило «разоружённый — не цель» поставил оператор
        // в v178 и оно остаётся в силе для ВСЕЙ армии: пока идёт бой, огонь идёт по тем, кто бьёт сейчас.
        // Но преследователь для того и отделён, чтобы добить одного конкретного, — и назначение ему делается
        // ЗДЕСЬ, потому что commandFire начинается с out.clear() и всякий приказ, поставленный раньше, стирает.
        // Первая редакция ставила приказ в assignChase, и он не доживал до выстрела: крип догонял и молчал
        val chased = Squads.chaseTarget[c.id]
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
internal fun commandHeal(army: List<Creep>, enemies: List<Creep>, out: MutableMap<String, String>) {
    out.clear()
    val healers = army.filter { hasHeal(it) && !it.spawning }
    if (healers.isEmpty()) return
    // ...ожидаемый урон — адресный, по той же модели его выбора, что у исполнителя (v292, см. healAndShoot.need)
    val pred = TacticianState.focusPredDmg
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
    /** БОЙЦЫ БЕЗ ПРИКАЗА ПОСЛЕ ВЫБРАННОЙ РАЗДАЧИ (v476, вопрос 3 оператора): строевых (мили и стрелки) без клетки в `out` по концу
     *  проходов / из них тех, у кого клетка-кандидат в шаге БЫЛА (кулак её не отрезал — такой обязан был получить приказ) /
     *  лекарей / раздетых / раздач, где кто-то без приказа; считает проход `audit`, в приборы матча попадает запись выбранной
     *  раздачи. Повод — match13:brawl+heals на стенде v450: на t=66 у мили пропал приказ (`order/(46,43)` → `kite/stay`) и первый
     *  бой развалился; прибор говорит, единичен ли случай, прежде чем искать причину. Боец вне кулака (`Formation.fist` оставляет
     *  клетки в FIST_RADIUS от медианы) кандидатов не имеет по построению и идёт ветками тактика — вторая часть его не считает. */
    val unplacedLine = g.counter("unplaced"); val unplacedReach = g.counter("unplaced", 1); val unplacedHeal = g.counter("unplaced", 2)
    val unplacedStrip = g.counter("unplaced", 3); val unplacedDeals = g.counter("unplaced", 4)
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

internal fun commandFight(army: List<Creep>, combatEnemies: List<Creep>, armedEnemies: List<Creep>,
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
    val deal = Deal(army, combatEnemies, armedEnemies, out, intent, ourFlagCells, healersOnly, fighters, enemyAt, cells)
    deal.distribute()
    return deal.rec
}

/** ОГОНЬ И ЛЕЧЕНИЕ АРМИИ ЗА ТИК (v256, этап 10): хвост runArmy после покрипного цикла — перепись «почему» (why t=, why-sum), стрелки врага на прошлом тике для прогноза (prevShooters), назначение огня и лечения и исполнение. Перенесено дословно. */
internal fun armyFireAndHeal(ctx: Ctx, meas: ArmyMeasures, targ: ArmyTargets) {
    cpuMark("moves")
    FireBook.prevShooters = meas.forces.combatEnemies.map { val p = InfluenceMap.profileOf(it); Shooter(it.key, p.ranged, p.melee) }
    // ...командирская цель НЕ подменяет цель стрельбы (v138): проведённая сюда, она уронила гейт до 129/131 и
    // дала m33:kite 0:21 135 — армия бросала всё ради назначенной цели. Она влияет мягко, через порядок focusOrder
    // огонь тоже по приказу командира (v161): назначения считаются на всю силу, включая захватчиков с оружием
    commandFire(ctx.army + ctx.runners.filter { hasWeapon(it) }, meas.forces.enemyCreeps, targ.focus.focusTarget, targ.focus.focusOrder, FireBook.fireOf)
    commandHeal(ctx.army, meas.forces.enemyCreeps, FireBook.healOf)
    // ...и отряжённый лекарь без оружия лечит (v240): до этого healAndShoot получал бегунов только с оружием
    healAndShoot(ctx.army + ctx.combatRunners, meas.forces.allies, meas.forces.enemyCreeps, targ.focus.focusTarget, targ.focus.focusOrder)
    cpuMark("shoot")
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

/** Перебой (v468, см. bookDamage): `okill=целиком лишних/из них по приказу/из них мили/урон сверх добивания/назначений/урон
 *  расписан всего`; суд премиссы `okkill=погибло/расписано насмерть/урон сверх у погибших/у выживших` (см. judgeOverkill). */
internal val okDead = Gauges.counter("okill")
internal val okDeadOrdered = Gauges.counter("okill", 1)
internal val okDeadMelee = Gauges.counter("okill", 2)
internal val okOver = Gauges.real("okillOver")
private val okOverDeclared = Gauges.computed("okill", 3) { okOver.x.toInt().toString() }
internal val okAll = Gauges.counter("okill", 4)
internal val okBooked = Gauges.real("okillBooked")
private val okBookedDeclared = Gauges.computed("okill", 5) { okBooked.x.toInt().toString() }
internal val okDied = Gauges.counter("okkill")
internal val okBookedDead = Gauges.counter("okkill", 1)
internal val okOverDied = Gauges.real("okkillDied")
private val okOverDiedDeclared = Gauges.computed("okkill", 2) { okOverDied.x.toInt().toString() }
internal val okOverHeld = Gauges.real("okkillHeld")
private val okOverHeldDeclared = Gauges.computed("okkill", 3) { okOverHeld.x.toInt().toString() }

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

/** КНИГА ОГНЯ И ЛЕЧЕНИЯ (v459, второй шаг архитектуры, этап 6): назначения командира и счёт выстрелов тика — словари живут весь матч и чистятся на своих местах, перенесены из `object PainAndGain` как есть. Пишет только стадия огня и лечения; владелец — в списке починки. */
internal object FireBook {
    internal val fireOf = HashMap<String, String>() // крип → цель, назначенная командиром (v161)
    internal val healOf = HashMap<String, String>() // лекарь → пациент, назначенный командиром (v162)
    internal val shotsAt = HashMap<String, Int>()     // выстрелы по цели за тик (см. conc в строке t=)
    /** Удары мили по цели за тик (v221) — как `shotsAt`, но из `strike`; чистится там же. */
    internal val strikesAt = HashMap<String, Int>()
    /** Урон, уже расписанный по цели в этом тике (v140, отказ от перебоя): чистится вместе с shotsAt. */
    internal val damageBooked = HashMap<String, Double>()
    /** Хвост урона сверх книги по цели за тик огня — судится следующим тиком (v468, см. judgeOverkill). */
    internal val overByTarget = HashMap<String, Double>()
    internal var prevShooters: List<Shooter> = emptyList()
}
