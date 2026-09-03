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
import kotlin.math.sqrt

@OptIn(ExperimentalJsExport::class)
@JsExport
fun loop() {
    try {
        runWithSourceMapSupport {
            PainAndGain.tick()
        }
    } catch (t: Throwable) {
        // страховка: даже если source-map-обработчик упадёт, логируем ошибку и не роняем тик
        println("loop error: ${t.message}")
        println(t.stackTraceToString())
    }
}

/**
 * Season 4 «Pain and Gain» (basic). Первая версия — до первого живого матча.
 *
 * Правила (описание арены в клиенте, 04.09.2026): у каждого игрока ЗАРАНЕЕ ВЫДАННАЯ армия из 14
 * крипов — ни спавна, ни стройки, ни энергии, ни замены погибшим. По карте семь нейтральных
 * [ScoreFlag]: крип захватывает флаг, ВСТАВ на его клетку; захваченный флаг каждый тик приносит
 * владельцу очки и вешает глобальный дебафф на ВСЮ его армию (флаги одного типа складываются):
 *
 *  | флаг              | шт | очки/тик | эффект                      | один  | два  |
 *  |-------------------|----|----------|-----------------------------|-------|------|
 *  | Vulnerability     | 1  | 5        | входящий боевой урон        | ×1.1  | —    |
 *  | Heal reduction    | 2  | 4        | лечение                     | ×0.75 | ×0.5 |
 *  | Attack reduction  | 2  | 3        | удар ATTACK                 | ×0.8  | ×0.6 |
 *  | Ranged reduction  | 2  | 3        | выстрел RANGED_ATTACK       | ×0.8  | ×0.6 |
 *
 * Итого 25 очков/тик (MAX_SCORE_PER_TICK). 2000 тиков; побеждает больший счёт, равный — ничья;
 * досрочно — уничтожение армии противника (победа независимо от счёта) или недосягаемый отрыв.
 *
 * Оси решения, все из состояния, не из подгонки под карту:
 *  - счёт: безоружные крипы — захватчики, идут по флагам по выигрышу очков за тик хода; армия
 *    берёт флаги по пути и рейдом. Флаг берётся, только если армия С ЕГО дебаффом не слабее
 *    армии врага (маргинальная цена по Ланчестеру с эффектами обеих сторон) — или если по
 *    прогнозу счёта мы проигрываем: тогда очки важнее силы;
 *  - бой: армия одной группой; постура — ДОБИТЬ (уничтожение армии врага выигрывает матч:
 *    перевес PUSH_RATIO по мощи с эффектами), РЕЙД/ЗАЧИСТКА флага, ПОСТ у своих флагов, ОТХОД
 *    (враг сильнее в RETREAT_RATIO и рядом — армию без замены не разменивают). Внутри — локальный
 *    перевес, цена боя в запасе хода, сплочение, бегство от смертельного урона, фокус, лечение;
 *  - мощь считается по МОДИФИЦИРОВАННЫМ эффектами урону, лечению и хитам (InfluenceMap).
 */
object PainAndGain {

    // ---------- боевые константы ----------
    private const val RANGED_RANGE = 3
    private const val HEAL_RANGE = 3
    private const val MELEE_KEEP_RANGE = 2
    private const val MELEE_KITE_DISCOUNT = 0.1

    /** Перевес, при котором армия идёт добивать / в рейд, и порог продолжения (гистерезис). */
    private const val PUSH_RATIO = 1.3
    private const val PUSH_RELEASE_RATIO = 0.9

    /** Перевес врага, при котором армия отходит (и порог выхода из отхода). */
    private const val RETREAT_RATIO = 1.3
    private const val RETREAT_RELEASE_RATIO = 1.0

    /** Захват флага допустим, пока армия с его дебаффом не ниже этой доли мощи врага: «не слабее» при
     *  равных армиях запрещало бы самый первый флаг обеим сторонам (стенд: 0/0 за 2000 тиков), а 0.9 —
     *  ниже порога, с которого враг пойдёт добивать (1/PUSH_RATIO ≈ 0.77), с запасом на ошибку оценки. */
    private const val CAPTURE_FLOOR = 0.9

    /** Внутри стольких клеток от цели (Чебышев, сверх standoff) строй не держат: группа уже на месте, а
     *  ожидание «отставших» у самого флага запирало захватчика на тысячу тиков (стенд greedy/grab). */
    private const val ARRIVED_SLACK = 2

    /** Охрана флага: боевые враги в этом радиусе от флага (дальность сближения + выстрел). */
    private const val FLAG_GUARD_RANGE = 11

    /** Враг «рядом» с армией (для отхода): в этих клетках от кого-то из наших; из отхода выходим, когда он
     *  дальше NEAR_RANGE + NEAR_RELEASE (иначе постура прыгала каждый тик на кромке радиуса). */
    private const val NEAR_RANGE = 14
    private const val NEAR_RELEASE = 6

    private const val COHESION_GAP = 8
    private const val COHESION_GAP_MAX = 100
    private const val ENGAGE_COHESION_TICKS = 2
    private const val POST_STANDOFF = 2
    private const val ENGAGE_RANGE = 8
    private const val CLOSE_STANDOFF = 2
    private const val APPROACH_WINDOW = 20

    // веса оценки клетки (скопированы из spawn-and-swamp, где обкатаны)
    private const val PAIR_W_DIST = 10.0
    private const val PAIR_W_DAMAGE = 0.3
    private const val PAIR_W_INFLUENCE = 0.1
    private const val PAIR_W_OUTGOING = 30.0
    private const val PAIR_W_MELEE = 50.0
    private const val AGGRO_MELEE_FACTOR = 0.3
    private const val PAIR_W_SPREAD = 4.0
    private const val PAIR_W_SWAMP = 40.0
    private const val SEPARATION_RADIUS = 1
    private const val CROWD_COST = 3

    private const val FIGHTER_PRIORITY = 3
    private const val RUNNER_PRIORITY = 2

    // ---------- отладка ----------
    private const val DEBUG_LOG = true
    private const val DEBUG_MAP = true
    private const val DEBUG_VISUALS = true
    private const val LOG_EVERY = 10

    private val DIRECTIONS = listOf(
        0 to 0, -1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1,
    )

    private enum class Posture { HOLD, RETREAT, ANNIHILATE, FLAG }

    private var greeted = false
    private var mapLogged = false
    private var posture = Posture.HOLD
    private var objectiveFlagId: String? = null
    private var postureLogged = ""

    /** Стартовые центры армий — «дома» сторон (спавнов нет): половины карты и точка поста. */
    private var homePos: Position? = null
    private var enemyHomePos: Position? = null

    private val approachingIds = HashSet<String>()
    private val arrivalById = HashMap<String, Int>()
    private val approachHistory = HashMap<String, ArrayDeque<Pair<Int, Int>>>()
    private val enemyPrevCell = HashMap<String, Int>()
    private var huntingThreat = false
    private val aggressiveIds = HashSet<String>()
    private val lastHits = HashMap<String, Int>()
    private val lastCell = HashMap<String, Int>()
    private val ghostLogged = HashMap<String, Int>()
    private class Shooter(val cell: Int, val ranged: Double, val melee: Double)
    private var prevShooters: List<Shooter> = emptyList()

    /** id захватчика -> id флага (липкое назначение). */
    private val runnerFlag = HashMap<String, String>()

    // ---------- счёт ----------
    private var ourScore = 0.0
    private var enemyScore = 0.0
    private var ourRate = 0
    private var enemyRate = 0
    /** По прогнозу (счёт + темп × остаток) мы проигрываем: очки важнее силы (см. captureAllowed). */
    private var behindOnScore = false
    private val lastFlagOwner = HashMap<String, Int>()
    private var lastEffectsKey = ""
    private var lastBodiesKey = ""

    // ---------- кэши на тик ----------
    private val flowCache = HashMap<Int, IntArray>()

    // ---------- модель ----------

    /** Флаг очков в этом тике: владелец, тип дебаффа, очки, кто стоит на клетке и чья охрана рядом. */
    private class FlagInfo(val flag: ScoreFlag, val mine: Boolean?, val type: String, val score: Int, val occupant: Creep?, val guards: List<Creep>) {
        val id: String get() = flag.id
        val pos: Position get() = flag
        val ours: Boolean get() = mine == true
        val theirs: Boolean get() = mine == false
        /** Очки в тик, которые даёт захват: чужой флаг — двойной размен (нам плюс, врагу минус). */
        val swing: Double get() = if (theirs) 2.0 * score else score.toDouble()
    }

    private class Ctx(
        val home: Position,
        val enemyHome: Position,
        val myCreeps: List<Creep>,
        val active: List<Creep>,
        val army: List<Creep>,      // с оружием или лечением
        val runners: List<Creep>,   // безоружные и без лечения: захватчики
        val enemyCreeps: List<Creep>,
        val combatEnemies: List<Creep>,
        val blocked: List<Position>,
        val dangerMatrix: CostMatrix,
        val flags: List<FlagInfo>,
        val ourCentroid: Position,
        val enemyCentroid: Position?,
    )

    fun tick() {
        flowCache.clear()

        val myCreeps = getObjectsByPrototype(Creep::class).filter { it.my && it.exists }
        val enemyCreeps = getObjectsByPrototype(Creep::class).filter { !it.my && it.exists && !it.spawning }
        val active = myCreeps.filter { !it.spawning }
        val combatEnemies = enemyCreeps.filter { val p = InfluenceMap.profileOf(it); p.melee + p.ranged + p.heal > 0.0 }

        // дома сторон — стартовые центры армий: спавнов на карте нет, половины и пост считаются от них
        if (homePos == null && active.isNotEmpty()) homePos = centroidOf(active)
        if (enemyHomePos == null && enemyCreeps.isNotEmpty()) enemyHomePos = centroidOf(enemyCreeps)
        val home = homePos ?: centroidOf(active) ?: InfluenceMap.cell(50, 50)
        val enemyHome = enemyHomePos ?: InfluenceMap.cell(99 - home.x, 99 - home.y)

        val flags = collectFlags(myCreeps, enemyCreeps, combatEnemies)
        applyEffects(flags, myCreeps, enemyCreeps)
        accountScore(flags)

        if (!greeted) {
            greeted = true
            probe(flags, myCreeps, enemyCreeps, home, enemyHome)
        }
        if (DEBUG_MAP && !mapLogged) {
            mapLogged = true
            logMap(flags, myCreeps, enemyCreeps)
        }
        logBodies(myCreeps, enemyCreeps)

        val army = active.filter { hasWeapon(it) || hasHeal(it) }
        val runners = active.filter { !hasWeapon(it) && !hasHeal(it) }
        val immobile = active.filter { !canMove(it) }

        val walls = getObjectsByPrototype(StructureWall::class).filter { it.exists }
        val ramparts = getObjectsByPrototype(StructureRampart::class).filter { it.exists }
        val spawns = getObjectsByPrototype(StructureSpawn::class).filter { it.exists }
        val blocked: List<Position> = walls + ramparts.filter { it.my != true } + spawns + immobile
        val blockedForEnemy: List<Position> = walls + ramparts.filter { it.my != false } + spawns

        InfluenceMap.setProtectedCells(ramparts.filter { it.my == true }.mapTo(HashSet()) { it.x * 100 + it.y })
        InfluenceMap.setEnemyBlocked(blockedForEnemy.mapTo(HashSet()) { it.x * 100 + it.y })
        val dangerMatrix = InfluenceMap.dangerCostMatrix(enemyCreeps, blocked)

        DistanceMap.syncWalls(walls.size)
        DistanceMap.ensureBuilt(home, enemyHome)

        val ourCentroid = centroidOf(army.ifEmpty { active }) ?: home
        val enemyCentroid = centroidOf(combatEnemies.ifEmpty { enemyCreeps })
        val ctx = Ctx(home, enemyHome, myCreeps, active, army, runners, enemyCreeps, combatEnemies, blocked, dangerMatrix, flags, ourCentroid, enemyCentroid)

        enemyArrivalTicks(ctx)
        runRunners(ctx)
        runArmy(ctx)

        TrafficManager.resolve(active.filter { canMove(it) }, myCreeps + enemyCreeps)
        InfluenceMap.pruneStances(myCreeps.mapTo(HashSet()) { it.id })
        enemyPrevCell.clear()
        for (e in enemyCreeps) enemyPrevCell[e.id] = e.x * 100 + e.y
        if (DEBUG_LOG) logStuck(active, enemyCreeps)
        if (DEBUG_VISUALS) InfluenceMap.drawDebug(army, myCreeps, enemyCreeps)

        if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
            val ours = ourPowerOf(army, combatEnemies)
            val theirs = enemyPowerOf(combatEnemies, army)
            println(
                "t=${getTicks()} army=${army.size} runners=${runners.size} enemies=${enemyCreeps.size}/${combatEnemies.size} " +
                    "score=${ourScore.toInt()}/${enemyScore.toInt()} rate=$ourRate/$enemyRate behind=$behindOnScore flags=${flagsSummary(flags)} " +
                    "posture=$posture obj=${objectiveFlagId?.let { id -> flags.firstOrNull { it.id == id }?.let { "(${it.pos.x},${it.pos.y})" } } ?: "-"} hunt=$huntingThreat " +
                    "our=${ours.toInt()} enemy=${theirs.toInt()} hits=${army.sumOf { it.hits }}/${army.sumOf { it.hitsMax }} enemyHits=${combatEnemies.sumOf { it.hits }}/${combatEnemies.sumOf { it.hitsMax }} " +
                    "centroid=(${ourCentroid.x},${ourCentroid.y}) enemyCentroid=${enemyCentroid?.let { "(${it.x},${it.y})" } ?: "-"}"
            )
            if (getTicks() % (LOG_EVERY * 10) == 0) println(TrafficManager.audit())
        }
    }

    // ==================== зонд ====================

    /** Число из константы арены (внешнее объявление может оказаться undefined — тогда запасное). */
    private fun num(v: dynamic, fallback: Double): Double = if (jsTypeOf(v) == "number") v.unsafeCast<Double>() else fallback

    private fun probe(flags: List<FlagInfo>, myCreeps: List<Creep>, enemyCreeps: List<Creep>, home: Position, enemyHome: Position) {
        println(
            "hello season4 pain-and-gain: ${arenaInfo.season} - ${arenaInfo.name} level=${arenaInfo.level} " +
                "ticksLimit=${arenaInfo.ticksLimit} cpu=${arenaInfo.cpuTimeLimit}/${arenaInfo.cpuTimeLimitFirstTick}"
        )
        println(
            "pain-and-gain: TICKS_LIMIT=${num(TICKS_LIMIT.asDynamic(), -1.0)} MAX_SCORE_PER_TICK=${num(MAX_SCORE_PER_TICK.asDynamic(), -1.0)} " +
                "FLAG_TYPES=${try { JSON.stringify(FLAG_TYPES) } catch (t: Throwable) { "?" }} " +
                "EFF: attack=$EFF_ATTACK_MODIFIER ranged=$EFF_RANGED_ATTACK_MODIFIER heal=$EFF_HEAL_MODIFIER taken=$EFF_DAMAGE_TAKEN_MODIFIER " +
                "power: R=$RANGED_ATTACK_POWER A=$ATTACK_POWER H=$HEAL_POWER/$RANGED_HEAL_POWER"
        )
        println("flags: " + flags.joinToString(" ") { "(${it.pos.x},${it.pos.y})${typeChar(it.type)}${it.score}my=${it.mine}" })
        println("home=(${home.x},${home.y}) enemyHome=(${enemyHome.x},${enemyHome.y})")
        println("my creeps (${myCreeps.size}): " + myCreeps.joinToString(" ") { "${it.id}(${it.x},${it.y})${bodySummary(it)}${if (it.spawning) "S" else ""}" })
        println("enemy creeps (${enemyCreeps.size}): " + enemyCreeps.joinToString(" ") { "${it.id}(${it.x},${it.y})${bodySummary(it)}" })
        val walls = getObjectsByPrototype(StructureWall::class).size
        val ramparts = getObjectsByPrototype(StructureRampart::class).size
        val spawns = getObjectsByPrototype(StructureSpawn::class).size
        println("structures: walls=$walls ramparts=$ramparts spawns=$spawns")
        // тела целиком (с порядком частей) — по ним видно, что выдано, и что умрёт первым
        for (c in myCreeps) println("body ${c.id}: " + c.body.joinToString("") { partChar(it.type).toString() })
        for (c in enemyCreeps) println("enemy body ${c.id}: " + c.body.joinToString("") { partChar(it.type).toString() })
    }

    private fun partChar(type: BodyPartType): Char = when (type) {
        TOUGH -> 'T'; MOVE -> 'M'; RANGED_ATTACK -> 'R'; ATTACK -> 'A'; HEAL -> 'H'; CARRY -> 'C'; WORK -> 'W'
    }

    private fun typeChar(type: String): Char = when (type) {
        EFF_ATTACK_MODIFIER -> 'A'
        EFF_RANGED_ATTACK_MODIFIER -> 'R'
        EFF_HEAL_MODIFIER -> 'H'
        EFF_DAMAGE_TAKEN_MODIFIER -> 'D'
        else -> '?'
    }

    private fun flagsSummary(flags: List<FlagInfo>): String =
        flags.joinToString(",") { "${typeChar(it.type)}${it.score}${if (it.ours) "+" else if (it.theirs) "-" else "0"}${if (it.occupant != null) (if (it.occupant.my) "s" else "e") else ""}${if (it.guards.isNotEmpty()) "g${it.guards.size}" else ""}" }

    /** Состав армий (живые части) — при каждом изменении: видно потери и покалеченных. */
    private fun logBodies(myCreeps: List<Creep>, enemyCreeps: List<Creep>) {
        if (!DEBUG_LOG) return
        val key = myCreeps.joinToString(",") { bodySummary(it) } + "|" + enemyCreeps.joinToString(",") { bodySummary(it) }
        if (key == lastBodiesKey) return
        lastBodiesKey = key
        println("armies t=${getTicks()}: ours(${myCreeps.size}) " + myCreeps.joinToString(" ") { "${bodySummary(it)}h=${it.hits}" } +
            " | enemy(${enemyCreeps.size}) " + enemyCreeps.joinToString(" ") { "(${it.x},${it.y})${bodySummary(it)}h=${it.hits}" })
    }

    // ==================== флаги и эффекты ====================

    private fun collectFlags(myCreeps: List<Creep>, enemyCreeps: List<Creep>, combatEnemies: List<Creep>): List<FlagInfo> {
        val result = ArrayList<FlagInfo>()
        val all = getObjectsByPrototype(ScoreFlag::class).filter { it.exists }
        for (f in all) {
            val occupant = (myCreeps + enemyCreeps).firstOrNull { !it.spawning && it.x == f.x && it.y == f.y }
            val guards = combatEnemies.filter { getRange(it, f) <= FLAG_GUARD_RANGE }
            result.add(FlagInfo(f, f.my, f.effectType, f.scorePerTick, occupant, guards))
        }
        // журнал захватов: смена владельца и кто стоит на клетке — проверка механики «встал — захватил»
        for (fi in result) {
            val owner = if (fi.ours) 1 else if (fi.theirs) -1 else 0
            val prev = lastFlagOwner[fi.id]
            if (prev != null && prev != owner && DEBUG_LOG) {
                println("flag t=${getTicks()}: (${fi.pos.x},${fi.pos.y})${typeChar(fi.type)}${fi.score} owner ${ownerName(prev)} -> ${ownerName(owner)} occupant=${fi.occupant?.let { "${if (it.my) "my" else "enemy"} ${bodySummary(it)}" } ?: "none"}")
            }
            lastFlagOwner[fi.id] = owner
        }
        return result
    }

    private fun ownerName(code: Int) = when (code) { 1 -> "us"; -1 -> "enemy"; else -> "none" }

    /** Множитель стека по таблице арены: удар/стрельба 0.8 → 0.6, лечение 0.75 → 0.5, входящий 1.1 (флаг один);
     *  дальше — тем же шагом (в матче сверяется с effects крипов). */
    private fun stackMul(type: String, count: Int): Double {
        if (count <= 0) return 1.0
        return when (type) {
            EFF_ATTACK_MODIFIER, EFF_RANGED_ATTACK_MODIFIER -> 1.0 - 0.2 * count
            EFF_HEAL_MODIFIER -> 1.0 - 0.25 * count
            EFF_DAMAGE_TAKEN_MODIFIER -> 1.0 + 0.1 * count
            else -> 1.0
        }.coerceAtLeast(0.0)
    }

    private fun sideModsOf(flags: List<FlagInfo>, mine: Boolean): InfluenceMap.SideMods {
        fun n(type: String) = flags.count { it.mine == mine && it.type == type }
        return InfluenceMap.SideMods(
            attack = stackMul(EFF_ATTACK_MODIFIER, n(EFF_ATTACK_MODIFIER)),
            ranged = stackMul(EFF_RANGED_ATTACK_MODIFIER, n(EFF_RANGED_ATTACK_MODIFIER)),
            heal = stackMul(EFF_HEAL_MODIFIER, n(EFF_HEAL_MODIFIER)),
            taken = stackMul(EFF_DAMAGE_TAKEN_MODIFIER, n(EFF_DAMAGE_TAKEN_MODIFIER)),
        )
    }

    /** Эффекты сторон: по массиву effects крипов, если API его отдаёт, иначе по подсчёту флагов (таблица
     *  арены). Печатает то и другое при изменении — в матче они обязаны совпасть. */
    private fun applyEffects(flags: List<FlagInfo>, myCreeps: List<Creep>, enemyCreeps: List<Creep>) {
        val ours = sideModsOf(flags, true)
        val theirs = sideModsOf(flags, false)
        InfluenceMap.setSideMods(ours, theirs)
        val sample = myCreeps.firstOrNull { InfluenceMap.hasEffectsApi(it) }
        InfluenceMap.setOurTaken(if (sample != null) InfluenceMap.takenOf(sample) else ours.taken)
        if (DEBUG_LOG) {
            val enemySample = enemyCreeps.firstOrNull { InfluenceMap.hasEffectsApi(it) }
            val key = "$ours|$theirs|${sample?.let { JSON.stringify(it.effects) }}|${enemySample?.let { JSON.stringify(it.effects) }}"
            if (key != lastEffectsKey) {
                lastEffectsKey = key
                println("effects t=${getTicks()}: byFlags ours=[$ours] theirs=[$theirs] api ours=${sample?.let { JSON.stringify(it.effects) } ?: "n/a"} theirs=${enemySample?.let { JSON.stringify(it.effects) } ?: "n/a"}")
            }
        }
    }

    private fun accountScore(flags: List<FlagInfo>) {
        val cap = num(MAX_SCORE_PER_TICK.asDynamic(), Double.MAX_VALUE)
        ourRate = minOf(cap, flags.filter { it.ours }.sumOf { it.score }.toDouble()).toInt()
        enemyRate = minOf(cap, flags.filter { it.theirs }.sumOf { it.score }.toDouble()).toInt()
        ourScore += ourRate
        enemyScore += enemyRate
        val remaining = maxOf(0, arenaInfo.ticksLimit - getTicks())
        behindOnScore = ourScore + ourRate * remaining < enemyScore + enemyRate * remaining
        if (DEBUG_LOG && getTicks() % 100 == 0) println("score t=${getTicks()}: our=${ourScore.toInt()} (+$ourRate/t) enemy=${enemyScore.toInt()} (+$enemyRate/t) behind=$behindOnScore lead=${(ourScore - enemyScore).toInt()} maxSwing=${(cap * remaining).toInt()}")
    }

    /**
     * Брать ли этот (не наш) флаг сейчас. Свой — да. Проигрываем по прогнозу счёта — да: очки и есть
     * цель, и слабость без очков хуже слабости с ними. Врага в поле нет — да. Иначе — только если
     * армия С ЭТИМ дебаффом всё ещё не слабее армии врага (CAPTURE_MARGIN): маргинальная цена по
     * Ланчестеру с эффектами обеих сторон — у флага стрельбы дешевеет только стрелковая часть урона,
     * у флага лечения растёт чистый урон врага по нам, у флага уязвимости тают хиты.
     */
    private fun captureAllowed(ctx: Ctx, f: FlagInfo): Boolean {
        if (f.ours) return true
        if (behindOnScore) return true
        if (ctx.combatEnemies.isEmpty()) return true
        val (ours, theirs) = powerAfter(ctx, f)
        return ours >= theirs * CAPTURE_FLOOR
    }

    /** Мощь сторон, если мы возьмём ещё этот флаг: (наша с его дебаффом, вражья при нашем ослабленном лечении). */
    private fun powerAfter(ctx: Ctx, f: FlagInfo): Pair<Double, Double> {
        val n = ctx.flags.count { it.ours && it.type == f.type }
        val k = stackMul(f.type, n + 1) / stackMul(f.type, n).coerceAtLeast(0.01)
        val ours = ourPowerOf(
            ctx.army, ctx.combatEnemies,
            rangedK = if (f.type == EFF_RANGED_ATTACK_MODIFIER) k else 1.0,
            meleeK = if (f.type == EFF_ATTACK_MODIFIER) k else 1.0,
            hitsK = if (f.type == EFF_DAMAGE_TAKEN_MODIFIER) 1.0 / k else 1.0,
        )
        val theirs = enemyPowerOf(ctx.combatEnemies, ctx.army, healK = if (f.type == EFF_HEAL_MODIFIER) k else 1.0)
        return ours to theirs
    }

    /** Цена флага в силе — доля нашей мощи, которая останется после захвата (1 — бесплатно): флаг лечения
     *  для армии почти без лекарей дёшев, флаг уязвимости стоит всем; дорогие берутся последними. */
    private fun captureCost(ctx: Ctx, f: FlagInfo): Double {
        if (f.ours || ctx.combatEnemies.isEmpty()) return 1.0
        val now = ourPowerOf(ctx.army, ctx.combatEnemies)
        if (now <= 0.0) return 1.0
        return (powerAfter(ctx, f).first / now).coerceIn(0.0, 1.0)
    }

    // ==================== захватчики ====================

    /** Флаг, за которым стоит идти захватчику: не наш и без врага на клетке, или наш пустой (охрана клеткой). */
    private fun wantsRunner(f: FlagInfo): Boolean {
        val occ = f.occupant
        if (occ != null && !occ.my) return false
        return !f.ours || occ == null
    }

    private fun runRunners(ctx: Ctx) {
        val runners = ctx.runners
        if (runners.isEmpty()) { runnerFlag.clear(); return }
        runnerFlag.keys.retainAll { id -> runners.any { it.id == id } }
        val flagById = ctx.flags.associateBy { it.id }
        val crowdMatrix = crowdMatrixOf(ctx)
        fun dbg(s: Creep, mode: String, f: FlagInfo?, step: Position? = null) {
            if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
                println("  r${s.id} (${s.x},${s.y}) ${bodySummary(s)} hits=${s.hits} $mode flag=${f?.let { "(${it.pos.x},${it.pos.y})${typeChar(it.type)}${it.score}my=${it.mine}" } ?: "-"} step=${step?.let { "(${it.x},${it.y})" } ?: "stay"}${if (TrafficManager.isStuck(s.id)) " STUCK" else ""}")
            }
        }
        // назначение — каждый тик заново по ценности с премией текущему (липкость без зависания): захватчик,
        // взявший флаг, идёт за следующим НЕ НАШИМ, а не сидит на взятом (сидел, пока свободный флаг брала
        // армия за сорок тиков — стенд grab); сидеть на своём — когда чужих свободных нет
        val taken = HashSet<String>()
        val order = runners.sortedBy { runnerFlag[it.id] ?: "" }
        for (s in order) {
            val currentId = runnerFlag[s.id]
            var best: FlagInfo? = null
            var bestValue = 0.0
            for (f in ctx.flags) {
                if (f.id in taken || f.guards.isNotEmpty()) continue
                val occ = f.occupant
                if (occ != null && occ.id != s.id) continue // занято (чужим — ждём отряд; своим — тот и держит)
                if (f.ours && ctx.flags.any { o -> !o.ours && o.guards.isEmpty() && o.id !in taken && (o.occupant == null || o.occupant.id == s.id) }) continue
                val flow = flowTo(ctx, f.pos)
                val ticks = pathTicks(s, flow, s.x * 100 + s.y)
                if (ticks >= Int.MAX_VALUE / 4) continue
                // свой пустой флаг стоит половину: враг за ним ещё должен прийти; чужой — двойной размен;
                // дорогой по силе флаг — позже дешёвого (см. captureCost); текущий — с премией
                val value = (if (f.ours) 0.5 * f.score else f.swing) * captureCost(ctx, f) / (ticks + 5) * (if (f.id == currentId) 1.25 else 1.0)
                if (value > bestValue) { bestValue = value; best = f }
            }
            if (best != null) { runnerFlag[s.id] = best.id; taken.add(best.id) } else runnerFlag.remove(s.id)
        }

        for (s in runners) {
            val f = runnerFlag[s.id]?.let { flagById[it] }
            val onIt = f != null && s.x == f.pos.x && s.y == f.pos.y
            val nearby = ctx.combatEnemies.filter { getRange(s, it) <= RANGED_RANGE + 2 }
            val underFire = InfluenceMap.damageAt(s.x, s.y, ctx.combatEnemies) > 0.0
            // захватчик без замены: от врага рядом — прочь (пустой MOVE ходит клетку за тик и по болоту, где
            // стрелок вязнет), даже с флага: флаг останется нашим, пока враг сам на него не встанет
            if (canMove(s) && (underFire || nearby.any { getRange(s, it) <= RANGED_RANGE + 1 })) {
                val step = fleeStep(s, nearby.ifEmpty { ctx.combatEnemies }, ctx.dangerMatrix)
                if (step != null) TrafficManager.request(s, step, RUNNER_PRIORITY)
                dbg(s, "FLEE", f, step)
                continue
            }
            if (f == null) {
                // все флаги при деле: к армии, за её спиной
                val step = if (s.getRangeTo(ctx.ourCentroid) > POST_STANDOFF + 2) pathStep(s, ctx.ourCentroid, POST_STANDOFF + 2, crowdMatrix) else null
                if (step != null) TrafficManager.request(s, step, RUNNER_PRIORITY)
                dbg(s, "RESERVE", null, step)
                continue
            }
            if (onIt) {
                dbg(s, if (f.ours) "HOLD" else "HOLD_WAIT", f)
                continue
            }
            // брать ли флаг сейчас (дебафф): нельзя — ждём рядом, шаг на клетку сделаем, когда станет можно
            val allowed = captureAllowed(ctx, f)
            val range = if (allowed) 0 else 1
            val step = if (s.getRangeTo(f.pos) > range) pathStep(s, f.pos, range, crowdMatrix) else null
            if (step != null) TrafficManager.request(s, step, RUNNER_PRIORITY)
            dbg(s, if (allowed) "TO_FLAG" else "POISED", f, step)
        }
    }

    // ==================== армия ====================

    private fun flowTo(ctx: Ctx, target: Position): IntArray =
        flowCache.getOrPut(target.x * 100 + target.y) { DistanceMap.flowFieldTo(target, ctx.blocked) }

    /** Стая у цели: боевые враги рядом с ней и те, кто дойдёт до неё (своим телом по полю) не позже нас. */
    private fun packAt(ctx: Ctx, pos: Position, flow: IntArray, ourTravel: Int): List<Creep> =
        ctx.combatEnemies.filter { getRange(it, pos) <= FLAG_GUARD_RANGE || pathTicks(it, flow, it.x * 100 + it.y) <= ourTravel }

    private class Objective(val flag: FlagInfo, val pack: List<Creep>, val value: Double, val travel: Int)

    /**
     * Флаг-цель армии: не наш, разрешённый к захвату; со стаей — по перевесу Ланчестера (ratio) и цене боя в
     * запасе хода группы (или в контакте); без стаи — просто идём. Ценность — очки размена за тик пути.
     * Текущая цель держится, пока проходит по мягкому порогу (гистерезис).
     */
    private fun chooseFlagObjective(ctx: Ctx, group: List<Creep>): Objective? {
        if (group.isEmpty()) return null
        var best: Objective? = null
        for (f in ctx.flags) {
            if (f.ours) continue
            if (!captureAllowed(ctx, f)) continue
            val flow = flowTo(ctx, f.pos)
            val travel = group.maxOf { pathTicks(it, flow, it.x * 100 + it.y) }
            if (travel >= Int.MAX_VALUE / 4) continue
            val pack = packAt(ctx, f.pos, flow, travel)
            val current = f.id == objectiveFlagId
            val ratio = if (current) PUSH_RELEASE_RATIO else if (behindOnScore) 1.0 else PUSH_RATIO
            // цена боя — гейт на ВХОД к охраняемому флагу (лазейка «уже в контакте» отправила армию к дальнему
            // флагу с девятью охранниками сквозь наступающую армию — стенд rush, t=61)
            val ok = pack.isEmpty() || (ourPowerOf(group, pack) >= enemyPowerOf(pack, group) * ratio &&
                fightCost(pack, group) <= group.maxOf { speedSlack(it) })
            if (!ok) continue
            // гистерезис: текущая цель ценнее на четверть, чтобы не прыгать между равными; дорогой по силе — позже
            val value = f.swing * captureCost(ctx, f) / (travel + 10) * (if (current) 1.25 else 1.0)
            if (best == null || value > best.value) best = Objective(f, pack, value, travel)
        }
        return best
    }

    /** Точка отхода: дом и углы НАШЕЙ половины — достижимая и самая дальняя от центра армии врага (отход в
     *  дальний угол через всю карту вёл сквозь врага, и армию добивали по одному — стенд rush). */
    private fun retreatPoint(ctx: Ctx): Position {
        val enemy = ctx.enemyCentroid ?: return ctx.home
        val corners = listOf(InfluenceMap.cell(3, 3), InfluenceMap.cell(3, 96), InfluenceMap.cell(96, 3), InfluenceMap.cell(96, 96))
        val candidates = listOf(ctx.home) + corners.filter { DistanceMap.inOurHalf(it.x, it.y) }
        var best = ctx.home
        var bestScore = -1.0
        for (c in candidates) {
            val flow = flowTo(ctx, c)
            val reach = ctx.army.count { flow[it.x * 100 + it.y] >= 0 }
            if (reach == 0) continue
            val score = getRange(c, enemy) - 0.5 * getRange(c, ctx.ourCentroid)
            if (score > bestScore) { bestScore = score; best = c }
        }
        return best
    }

    /** Пост: центр наших флагов (их и держим), без флагов — дом. */
    private fun postPoint(ctx: Ctx): Position =
        centroidOf(ctx.flags.filter { it.ours }.map { it.pos }) ?: ctx.home

    private fun runArmy(ctx: Ctx) {
        val army = ctx.army
        if (army.isEmpty()) return
        val allies = ctx.myCreeps
        val enemyCreeps = ctx.enemyCreeps
        val combatEnemies = ctx.combatEnemies

        val strikers = army.filter { fullSpeed(it) && hasWeapon(it) }
        val mobileArmy = army.filter { canMove(it) }

        // ---- постура ----
        val ours = ourPowerOf(army, combatEnemies)
        val theirs = enemyPowerOf(combatEnemies, army)
        val nearRange = if (posture == Posture.RETREAT) NEAR_RANGE + NEAR_RELEASE else NEAR_RANGE
        val enemyNear = combatEnemies.any { e -> army.any { getRange(e, it) <= nearRange } }
        // контакт решает сам: пассивного поста в контакте нет — он отдаёт армию по одному (стенд rush: десять
        // за двоих). Отход из контакта возможен, только если он не бегство: никто из врагов-мили не вплотную
        // и наш строй не медленнее их самого быстрого — при равной скорости преследователь стреляет в спину
        // каждый тик, а обездвиженные остаются врагу (стенд rush: отход при 1250 против 1619 отдал ещё
        // шестерых). Иначе в контакте — бой всем составом, даже слабее: рубка с фокусом лучше разгрома
        val contact = inContact(combatEnemies, army)
        val meleeAdjacent = combatEnemies.any { e -> hasMelee(e) && army.any { getRange(e, it) <= 1 } }
        val ourPeriod = mobileArmy.maxOfOrNull { plainPeriod(it) } ?: 1
        val theirPeriod = combatEnemies.filter { canMove(it) }.minOfOrNull { plainPeriod(it) } ?: Int.MAX_VALUE / 4
        val retreatFeasible = !contact || (!meleeAdjacent && ourPeriod < theirPeriod)
        val weaker = theirs >= ours * (if (posture == Posture.RETREAT) RETREAT_RELEASE_RATIO else RETREAT_RATIO)
        val annihilate = combatEnemies.isNotEmpty() && strikers.isNotEmpty() &&
            (ours >= theirs * (if (posture == Posture.ANNIHILATE) PUSH_RELEASE_RATIO else PUSH_RATIO) ||
                (contact && !(retreatFeasible && weaker)))
        val retreat = combatEnemies.isNotEmpty() && !annihilate && enemyNear && weaker && retreatFeasible
        val objective = if (annihilate || retreat) null else chooseFlagObjective(ctx, strikers.ifEmpty { mobileArmy })
        val newPosture = when {
            annihilate -> Posture.ANNIHILATE
            retreat -> Posture.RETREAT
            objective != null -> Posture.FLAG
            else -> Posture.HOLD
        }
        objectiveFlagId = objective?.flag?.id
        val retreatTo = if (newPosture == Posture.RETREAT) retreatPoint(ctx) else null
        val post = postPoint(ctx)
        val postureKey = "$newPosture:${objectiveFlagId ?: ""}"
        if (DEBUG_LOG && (postureKey != postureLogged || getTicks() % (LOG_EVERY * 10) == 0)) {
            postureLogged = postureKey
            println("posture: $newPosture t=${getTicks()} our=${ours.toInt()} enemy=${theirs.toInt()} near=$enemyNear contact=$contact retreatFeasible=$retreatFeasible strikers=${strikers.size}/${army.size} " +
                "obj=${objective?.let { "(${it.flag.pos.x},${it.flag.pos.y})${typeChar(it.flag.type)}${it.flag.score} pack=${it.pack.size} travel=${it.travel} v=${(it.value * 100).toInt()}" } ?: "-"} " +
                "retreatTo=${retreatTo?.let { "(${it.x},${it.y})" } ?: "-"} post=(${post.x},${post.y}) behind=$behindOnScore hunt=$huntingThreat")
        }
        posture = newPosture

        // ---- общие цели ----
        val centroid = ctx.ourCentroid
        val ourHalfCombat = combatEnemies.filter { DistanceMap.inOurHalf(it.x, it.y) }
        val ourHalfSoft = enemyCreeps.filter { c -> combatEnemies.none { it.id == c.id } && DistanceMap.inOurHalf(c.x, c.y) }
        fun arrivalOf(c: Creep) = arrivalById[c.id] ?: Int.MAX_VALUE / 2
        val threat = ourHalfCombat.minWithOrNull(compareBy<Creep>({ arrivalOf(it) }, { getRange(it, centroid) }))
        // рейдер: чужой безоружный на нашей половине — тот, что ближе к нашему флагу (захватчик идёт к нему)
        val raider = ourHalfSoft.minByOrNull { r -> minOf(getRange(r, centroid), ctx.flags.filter { !it.theirs }.minOfOrNull { getRange(r, it.pos) } ?: 99) }
        // охота на угрозу на нашей половине — решение группы с гистерезисом: стрелки против всей стаи у угрозы
        huntingThreat = posture != Posture.RETREAT && threat != null && strikers.isNotEmpty() && run {
            val field = flowTo(ctx, threat)
            val ourTravel = strikers.map { pathTicks(it, field, it.x * 100 + it.y) }.filter { it < Int.MAX_VALUE / 4 }.maxOrNull() ?: Int.MAX_VALUE / 4
            val pack = combatEnemies.filter { getRange(it, threat) <= ENGAGE_RANGE + RANGED_RANGE || pathTicks(it, field, it.x * 100 + it.y) <= ourTravel }
            val o = ourPowerOf(strikers, pack)
            val t = enemyPowerOf(pack, strikers)
            o >= t * (if (huntingThreat) PUSH_RELEASE_RATIO else PUSH_RATIO) &&
                (fightCost(pack, strikers) <= strikers.maxOf { speedSlack(it) } || inContact(pack, strikers))
        }
        aggressiveIds.retainAll { id -> army.any { it.id == id } }
        lastHits.keys.retainAll { id -> army.any { it.id == id } }
        lastCell.keys.retainAll { id -> army.any { it.id == id } }

        val enemyPositions = enemyCreeps.mapTo(HashSet()) { it.x * 100 + it.y }
        val blockedSet = ctx.blocked.mapTo(HashSet()) { it.x * 100 + it.y }
        val meleeEnemies = enemyCreeps.filter { InfluenceMap.profileOf(it).melee > 0.0 }

        // фокус-файр: добиваемые за тик -> наибольшая угроза на хит (урон, который враг СЕЙЧАС наносит нам, плюс
        // его лечение, делённые на его хиты: мили вплотную за 1000 хитов снимает 90, стрелок за 800 — 40, лекарь
        // за 600 — 36; «лекари первыми» без учёта хитов вело огонь мимо мили, который резал наш строй)
        val inFireRange = enemyCreeps.filter { e -> army.any { it.getRangeTo(e) <= RANGED_RANGE } }
        val focusPool = inFireRange.filter { e -> combatEnemies.any { it.id == e.id } }.ifEmpty { inFireRange }
        fun fireAvailableAt(e: Creep) = army.filter { it.getRangeTo(e) <= RANGED_RANGE }.sumOf { InfluenceMap.profileOf(it).ranged }
        fun threatPerHit(e: Creep): Double {
            val p = InfluenceMap.profileOf(e)
            val meleeLive = p.melee > 0.0 && army.any { getRange(e, it) <= MELEE_KEEP_RANGE }
            val rangedLive = p.ranged > 0.0 && army.any { getRange(e, it) <= RANGED_RANGE }
            return ((if (meleeLive) p.melee else p.melee * MELEE_KITE_DISCOUNT) + (if (rangedLive) p.ranged else p.ranged * 0.5) + p.heal) / e.hits.coerceAtLeast(1)
        }
        val focusTarget = focusPool.maxWithOrNull(
            compareBy<Creep> { if (it.hits <= fireAvailableAt(it) * InfluenceMap.takenOf(it)) 1 else 0 }
                .thenBy { threatPerHit(it) }
                .thenByDescending { it.hits }
                .thenByDescending { getRange(it, centroid) }
        )
        val occupantAt = HashMap<Int, Creep>()
        for (c in ctx.active) occupantAt[c.x * 100 + c.y] = c
        // добить: цель армии — ближайший к центру армии боевой враг (по пути)
        val prey = if (posture == Posture.ANNIHILATE) combatEnemies.minByOrNull { pathTicksFrom(ctx, centroid, it) } else null
        // захватчик флага-цели — ближайший к флагу ВООРУЖЁННЫЙ член группы (одной клетки на всех не хватит; лекарь
        // ходит за подопечным, и назначенный захватчиком лекарь тысячу тиков стоял рядом с флагом — стенд greedy)
        val objectiveCapturer = objective?.let { o -> mobileArmy.filter { hasWeapon(it) }.ifEmpty { mobileArmy }.minByOrNull { getRange(it, o.flag.pos) }?.id }
        // флаг рядом (не наш, свободный, без врага в дальности, разрешён) — на него шагает ближайший из наших
        val grabberOf = HashMap<String, String>()
        for (f in ctx.flags) {
            if (f.ours || f.occupant != null || f.id == objectiveFlagId) continue
            if (combatEnemies.any { getRange(it, f.pos) <= RANGED_RANGE + 1 }) continue
            if (!captureAllowed(ctx, f)) continue
            val near = mobileArmy.filter { getRange(it, f.pos) <= 3 }.minByOrNull { getRange(it, f.pos) } ?: continue
            grabberOf[near.id] = f.id
        }

        for (creep in army) {
            val mobile = strikers.any { it.id == creep.id }
            val healer = !hasWeapon(creep) && hasHeal(creep)
            val localAllies = army.filter { getRange(creep, it) <= (if (posture == Posture.ANNIHILATE || posture == Posture.FLAG) ENGAGE_RANGE else RANGED_RANGE + 1) }
            val localEnemies = combatEnemies.filter { getRange(creep, it) <= ENGAGE_RANGE + RANGED_RANGE }
            val ratio = if (creep.id in aggressiveIds) PUSH_RELEASE_RATIO else PUSH_RATIO
            val ghost = run {
                val prev = lastHits[creep.id]
                val cell = lastCell[creep.id]
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
                posture == Posture.RETREAT -> inContact(localEnemies, localAllies) && ourPowerOf(localAllies, localEnemies) >= enemyPowerOf(localEnemies, localAllies) * ratio
                posture == Posture.ANNIHILATE -> true
                localEnemies.isEmpty() -> true
                else -> ourPowerOf(localAllies, localEnemies) >= enemyPowerOf(localEnemies, localAllies) * ratio &&
                    (fightCost(localEnemies, localAllies) <= localAllies.maxOf { speedSlack(it) } || inContact(localEnemies, localAllies))
            }
            if (localAggressive) aggressiveIds.add(creep.id) else aggressiveIds.remove(creep.id)
            val engage = if (localAggressive && !healer) combatEnemies.filter { getRange(creep, it) <= ENGAGE_RANGE }.minByOrNull { getRange(creep, it) } else null
            val closeIn = if (localAggressive) CLOSE_STANDOFF else RANGED_RANGE
            val melee = isMelee(creep) && !hasRanged(creep)
            val grab = grabberOf[creep.id]?.let { id -> ctx.flags.firstOrNull { it.id == id } }
            // лекарь держится вплотную к самому раненому бойцу РЯДОМ (в дальности лечения плюс шаг), иначе идёт к
            // ближайшему ходячему бойцу — не к самому раненому через полкарты: два лекаря шли к обездвиженному
            // остову за стеной, а строй ждал их у флага (стенд greedy)
            val healMate = if (healer) {
                val fighters = army.filter { it.id != creep.id && hasWeapon(it) }
                fighters.filter { getRange(creep, it) <= HEAL_RANGE + 1 }.maxByOrNull { it.hitsMax - it.hits }
                    ?: fighters.filter { canMove(it) }.minByOrNull { getRange(creep, it) }
                    ?: fighters.minByOrNull { getRange(creep, it) }
            } else null
            val target: Position
            val standoff: Int
            when {
                posture == Posture.RETREAT && retreatTo != null -> { target = retreatTo; standoff = 1 }
                healer && healMate != null -> { target = healMate; standoff = 1 }
                engage != null -> { target = engage; standoff = if (melee) 1 else closeIn }
                grab != null -> { target = grab.pos; standoff = 0 }
                prey != null -> { target = prey; standoff = if (melee) 1 else closeIn }
                objective != null -> {
                    val capturer = objectiveCapturer == creep.id
                    target = objective.flag.pos
                    standoff = if (capturer) 0 else CLOSE_STANDOFF
                }
                threat != null && huntingThreat && mobile -> { target = threat; standoff = if (melee) 1 else closeIn }
                raider != null && mobile && !healer -> { target = raider; standoff = if (melee) 1 else RANGED_RANGE }
                else -> { target = post; standoff = POST_STANDOFF }
            }
            val flow = flowTo(ctx, target)

            val nearbyEnemies = combatEnemies.filter { getRange(creep, it) <= 12 }
            val inCombat = combatEnemies.any { creep.getRangeTo(it) <= RANGED_RANGE + 2 }
            val underFire = InfluenceMap.damageAt(creep.x, creep.y, combatEnemies) > 0.0 || ghost > 0
            // бегство: смертельный урон за два тика; безоружный лекарь — от любого врага рядом; невидимый урон
            val mustFlee = (healer && nearbyEnemies.any { getRange(creep, it) <= RANGED_RANGE + 1 } && !localAggressive) ||
                creep.hits < InfluenceMap.netDamageAt(creep.x, creep.y, nearbyEnemies, allies) * 2 ||
                (ghost > 0 && creep.hits <= ghost)

            // сплочение: авангард ждёт отставших группы (в тиках ИХ хода), пока сам не под огнём и напарник
            // не в бою; при враге в досягаемости зазор тесный — собираемся ДО входа под огонь
            val myFlow = flow[creep.x * 100 + creep.y]
            val grouped = !healer && (posture == Posture.ANNIHILATE || posture == Posture.FLAG || (huntingThreat && threat != null && target === threat))
            // напарники строя — ходячие ВООРУЖЁННЫЕ: у лекаря своя цель (подопечный), и взаимное ожидание «лекарь
            // отстал от флага — боец отстал от подопечного лекаря» запирало группу навсегда (стенд greedy)
            val mates = if (grouped) mobileArmy.filter { it.id != creep.id && hasWeapon(it) } else emptyList()
            val mateFighting = mates.any { m -> combatEnemies.any { m.getRangeTo(it) <= RANGED_RANGE + 2 } }
            val gap = if (localEnemies.isEmpty()) COHESION_GAP else ENGAGE_COHESION_TICKS
            val hold = grouped && !underFire && !mateFighting && myFlow >= 0 && creep.getRangeTo(target) > standoff + ARRIVED_SLACK && run {
                var lagging = false
                for (m in mates) {
                    if (getRange(creep, m) <= RANGED_RANGE) continue
                    val d = flow[m.x * 100 + m.y]
                    if (d < 0) continue
                    val lag = (d - myFlow) * plainPeriod(m)
                    if (lag in (gap + 1)..COHESION_GAP_MAX) { lagging = true; break }
                }
                lagging
            }

            val step: Position? = when {
                !canMove(creep) -> null
                mustFlee -> fleeStep(creep, nearbyEnemies, ctx.dangerMatrix) ?: pathStep(creep, retreatTo ?: post, 1, ctx.dangerMatrix)
                hold -> null
                else -> bestSingleMove(creep, target, flow, standoff, localAggressive, inCombat, enemyCreeps, allies, meleeEnemies, blockedSet, enemyPositions, occupantAt)
            }
            if (DEBUG_LOG && getTicks() % LOG_EVERY == 0) {
                println("  f${creep.id} (${creep.x},${creep.y}) ${bodySummary(creep)} hits=${creep.hits}/${creep.hitsMax} tgt=(${target.x},${target.y}) so=$standoff flow=$myFlow flee=$mustFlee combat=$inCombat aggr=$localAggressive hold=$hold spd=${plainPeriod(creep)} fatigue=${creep.fatigue} step=${step?.let { "(${it.x},${it.y})" } ?: "stay"}${if (TrafficManager.isStuck(creep.id)) " STUCK" else ""}")
            }
            if (step != null) TrafficManager.request(creep, step, FIGHTER_PRIORITY)
            lastHits[creep.id] = creep.hits
            lastCell[creep.id] = creep.x * 100 + creep.y
        }

        prevShooters = combatEnemies.map { val p = InfluenceMap.profileOf(it); Shooter(it.x * 100 + it.y, p.ranged, p.melee) }
        healAndShoot(army, allies, enemyCreeps, focusTarget)
    }

    /** Удар мили: фокус-цель вплотную, иначе самый раненый сосед. */
    private fun strike(creep: Creep, enemyCreeps: List<Creep>, focusTarget: Creep?) {
        if (!hasMelee(creep)) return
        val adjacent = enemyCreeps.filter { creep.getRangeTo(it) <= 1 }
        val target: Creep? = when {
            focusTarget != null && creep.getRangeTo(focusTarget) <= 1 -> focusTarget
            adjacent.isNotEmpty() -> adjacent.minByOrNull { it.hits }
            else -> null
        }
        target?.let { creep.attack(it) }
    }

    /** Через сколько тиков боевые враги дойдут до нашего дома — по темпу сближения за APPROACH_WINDOW;
     *  новый враг — по ходу его тела вдоль поля. Заполняет approachingIds/arrivalById (для приоритета угроз). */
    private fun enemyArrivalTicks(ctx: Ctx) {
        val now = getTicks()
        approachHistory.keys.retainAll { id -> ctx.combatEnemies.any { it.id == id } }
        approachingIds.clear()
        arrivalById.clear()
        val enemyApproach = flowTo(ctx, ctx.home)
        for (e in ctx.combatEnemies) {
            val approach = enemyApproach[e.x * 100 + e.y]
            if (approach < 0) continue
            val h = approachHistory.getOrPut(e.id) { ArrayDeque() }
            h.addLast(now to approach)
            while (h.isNotEmpty() && h.first().first < now - APPROACH_WINDOW) h.removeFirst()
            val (t0, a0) = h.first()
            val arrival = if (now - t0 < APPROACH_WINDOW / 2) pathTicks(e, enemyApproach, e.x * 100 + e.y) else {
                val rate = (a0 - approach).toDouble() / (now - t0)
                if (rate > 0.0) (approach / rate).toInt() else Int.MAX_VALUE / 2
            }
            arrivalById[e.id] = arrival
            if (arrival < Int.MAX_VALUE / 2) approachingIds.add(e.id)
        }
    }

    private fun healAndShoot(active: List<Creep>, allies: List<Creep>, enemyCreeps: List<Creep>, focusTarget: Creep?) {
        val healDone = HashMap<String, Int>()
        val incoming = HashMap<String, Int>()
        fun need(target: Creep): Int {
            val deficit = target.hitsMax - target.hits
            val expected = incoming.getOrPut(target.id) { InfluenceMap.damageAt(target.x, target.y, enemyCreeps).toInt() }
            return deficit + expected - (healDone[target.id] ?: 0)
        }
        for (creep in active) {
            strike(creep, enemyCreeps, focusTarget)
            val healParts = creep.body.count { it.type == HEAL && it.hits > 0 }
            if (healParts > 0) {
                val candidates = allies.filter { !it.spawning && need(it) > 0 && creep.getRangeTo(it) <= HEAL_RANGE }
                val closeTarget = candidates.filter { creep.getRangeTo(it) <= 1 }.maxByOrNull { need(it) }
                if (closeTarget != null) {
                    creep.heal(closeTarget)
                    healDone[closeTarget.id] = (healDone[closeTarget.id] ?: 0) + InfluenceMap.modified(creep, EFF_HEAL_MODIFIER, healParts * HEAL_POWER.toDouble()).toInt()
                    shoot(creep, enemyCreeps, focusTarget)
                    continue
                }
                val farTarget = candidates.filter { it.hitsMax - it.hits > 0 }.maxByOrNull { need(it) }
                if (farTarget != null) {
                    creep.rangedHeal(farTarget)
                    healDone[farTarget.id] = (healDone[farTarget.id] ?: 0) + InfluenceMap.modified(creep, EFF_HEAL_MODIFIER, healParts * RANGED_HEAL_POWER.toDouble()).toInt()
                    continue
                }
            }
            shoot(creep, enemyCreeps, focusTarget)
        }
    }

    private fun shoot(creep: Creep, enemyCreeps: List<Creep>, focusTarget: Creep?) {
        if (!hasRanged(creep)) return
        val creepsInRange = enemyCreeps.filter { creep.getRangeTo(it) <= RANGED_RANGE }
        if (creepsInRange.isEmpty()) return
        val combatInRange = creepsInRange.filter { c -> val p = InfluenceMap.profileOf(c); p.melee + p.ranged + p.heal > 0.0 }
        val massPool = if (combatInRange.isNotEmpty()) combatInRange else creepsInRange
        val massValue = massPool.sumOf { InfluenceMap.rangedRate(creep.getRangeTo(it)) }
        if (massValue > 1.0) {
            creep.rangedMassAttack()
        } else {
            // фокус-цель вне дальности — добиваем самого раненого боевого в дальности (безоружных — в последнюю очередь)
            val target = when {
                focusTarget != null && creep.getRangeTo(focusTarget) <= RANGED_RANGE -> focusTarget
                else -> massPool.minByOrNull { it.hits }
            }
            target?.let { creep.rangedAttack(it) }
        }
    }

    private fun bestSingleMove(
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
    ): Position? {
        var bestScore = scoreCell(creep, creep.x, creep.y, target, flow, standoff, aggressive, inCombat, enemyCreeps, allies, meleeEnemies)
        var bx = creep.x; var by = creep.y
        val hereDist = flow[creep.x * 100 + creep.y]
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
                if (fd in 0 until (if (hereDist >= 0) hereDist else Int.MAX_VALUE) && (static || stuck)) blockedByStatic = true
                else if (!inCombat && fd in 0 until pushDist) { pushDist = fd; pushX = x; pushY = y }
                continue
            }
            val s = scoreCell(creep, x, y, target, flow, standoff, aggressive, inCombat, enemyCreeps, allies, meleeEnemies)
            if (s > bestScore) { bestScore = s; bx = x; by = y }
        }
        if (bx != creep.x || by != creep.y) return InfluenceMap.cell(bx, by)
        if (pushX >= 0) return InfluenceMap.cell(pushX, pushY)
        if (blockedByStatic && hereDist >= 0) {
            var dx0 = 0; var dy0 = 0; var best = hereDist + DistanceMap.SWAMP_COST
            for ((dx, dy) in DIRECTIONS) {
                if (dx == 0 && dy == 0) continue
                val x = creep.x + dx; val y = creep.y + dy
                if (!passable(x, y, blockedSet, enemyPositions) || occupantAt.containsKey(x * 100 + y)) continue
                val fd = flow[x * 100 + y]
                if (fd in 0..best && (dx0 == 0 && dy0 == 0 || fd < best)) { best = fd; dx0 = dx; dy0 = dy }
            }
            if (dx0 != 0 || dy0 != 0) return InfluenceMap.cell(creep.x + dx0, creep.y + dy0)
        }
        return null
    }

    /** Оценка клетки: приблизиться на standoff к цели по реальному пути; в бою — исходящий урон, чистый
     *  входящий (с хилом), влияние, штраф за зону мили, за болото (без перевеса) и цена прижатия. */
    private fun scoreCell(creep: Creep, x: Int, y: Int, target: Position, flow: IntArray, standoff: Int, aggressive: Boolean, inCombat: Boolean, enemyCreeps: List<Creep>, allies: List<Creep>, meleeEnemies: List<Creep>): Double {
        val flowDist = flow[x * 100 + y]
        val cheb = getRange(InfluenceMap.cell(x, y), target)
        val firePenalty = when {
            cheb <= standoff -> (standoff - cheb) * 0.5
            flowDist < 0 -> 1000.0
            flowDist > standoff -> (flowDist - standoff).toDouble()
            else -> (standoff - flowDist) * 0.5
        }
        val separation = allies.count { !it.spawning && (it.x != x || it.y != y) && getRange(InfluenceMap.cell(x, y), it) <= SEPARATION_RADIUS } * PAIR_W_SPREAD
        if (!inCombat) return -firePenalty * PAIR_W_DIST - separation

        val damage = InfluenceMap.netDamageAt(x, y, enemyCreeps, allies)
        val meleeSelf = isMelee(creep) && !hasRanged(creep)
        val meleeWeight = if (aggressive) PAIR_W_MELEE * AGGRO_MELEE_FACTOR else PAIR_W_MELEE
        val meleeThreat = if (meleeSelf) 0.0 else meleeEnemies.count { getRange(InfluenceMap.cell(x, y), it) <= MELEE_KEEP_RANGE } * meleeWeight
        val swampPenalty = if (!aggressive && DistanceMap.isSwamp(x, y)) PAIR_W_SWAMP else 0.0
        val influence = if (meleeSelf && aggressive) 0.0 else InfluenceMap.influenceAt(x, y, allies, enemyCreeps)
        val outgoing = if (!aggressive && damage > 0.0) 0.0 else if (meleeSelf) (if (enemyCreeps.any { getRange(InfluenceMap.cell(x, y), it) <= 1 }) 1.0 else 0.0) else if (hasRanged(creep)) outgoingValue(x, y, enemyCreeps) else 0.0
        val damageTerm = if (aggressive) 0.0 else damage * PAIR_W_DAMAGE
        val pinned = (periodAt(creep, x, y) - 1) * InfluenceMap.fireAt(x, y, enemyCreeps) * PAIR_W_DAMAGE
        return -firePenalty * PAIR_W_DIST - damageTerm + influence * PAIR_W_INFLUENCE +
            outgoing * PAIR_W_OUTGOING - meleeThreat - separation - swampPenalty - pinned
    }

    private fun outgoingValue(x: Int, y: Int, enemyCreeps: List<Creep>): Double {
        var massValue = 0.0
        var anyInRange = false
        for (enemy in enemyCreeps) {
            val d = getRange(InfluenceMap.cell(x, y), enemy)
            if (d <= RANGED_RANGE) { anyInRange = true; massValue += InfluenceMap.rangedRate(d) }
        }
        if (!anyInRange) return 0.0
        return maxOf(massValue, 1.0)
    }

    private fun passable(x: Int, y: Int, blockedSet: Set<Int>, enemyPositions: Set<Int>): Boolean {
        if (x < 0 || y < 0 || x > 99 || y > 99) return false
        val key = x * 100 + y
        if (key in blockedSet || key in enemyPositions) return false
        return getTerrainAt(InfluenceMap.cell(x, y)) != TERRAIN_WALL
    }

    // ==================== тело, скорость, мощь ====================

    private fun canMove(creep: Creep) = creep.body.any { it.type == MOVE && it.hits > 0 }
    private fun hasMelee(creep: Creep) = creep.body.any { it.type == ATTACK && it.hits > 0 }
    private fun isMelee(creep: Creep) = creep.body.any { it.type == ATTACK }
    private fun hasRanged(creep: Creep) = creep.body.any { it.type == RANGED_ATTACK && it.hits > 0 }
    private fun hasHeal(creep: Creep) = creep.body.any { it.type == HEAL && it.hits > 0 }
    private fun hasWeapon(creep: Creep) = hasRanged(creep) || hasMelee(creep)

    /** Сводка тела: T10M4R3H1 (только живые части). */
    private fun bodySummary(creep: Creep): String {
        val order = listOf(TOUGH to 'T', MOVE to 'M', RANGED_ATTACK to 'R', ATTACK to 'A', HEAL to 'H', CARRY to 'C', WORK to 'W')
        val sb = StringBuilder()
        for ((type, ch) in order) {
            val n = creep.body.count { it.type == type && it.hits > 0 }
            if (n > 0) sb.append(ch).append(n)
        }
        return sb.toString()
    }

    /** Вес тела для усталости: части не-MOVE и не-CARRY ПО ТИПУ (мёртвые весят — movement.js:237)
     *  плюс гружёные CARRY. */
    private fun bodyWeight(creep: Creep): Int {
        val parts = creep.body.count { it.type != MOVE && it.type != CARRY }
        val carried = creep.store[RESOURCE_ENERGY] ?: 0
        return parts + (carried + CARRY_CAPACITY - 1) / CARRY_CAPACITY
    }

    private fun liveMoves(creep: Creep) = creep.body.count { it.type == MOVE && it.hits > 0 }

    /** Период хода (тиков на клетку): после шага fatigue = вес × цена местности − 2 × живые MOVE, дальше
     *  −2×MOVE в тик, следующий ход при нуле (tick.js:105, movement.js:237). */
    private fun periodOn(weight: Int, moves: Int, rate: Int): Int {
        if (moves <= 0) return Int.MAX_VALUE / 4
        val left = weight * rate - 2 * moves
        return if (left <= 0) 1 else 1 + (left + 2 * moves - 1) / (2 * moves)
    }

    private fun plainPeriod(creep: Creep) = periodOn(bodyWeight(creep), liveMoves(creep), 2)
    private fun periodAt(creep: Creep, x: Int, y: Int) =
        periodOn(bodyWeight(creep), liveMoves(creep), if (DistanceMap.isSwamp(x, y)) 10 else 2)
    private fun swampPeriod(creep: Creep) = periodOn(bodyWeight(creep), liveMoves(creep), 10)
    private fun fullSpeed(creep: Creep) = plainPeriod(creep) == 1

    /** Сколько урона крип ещё выдержит, не теряя скорости (части умирают спереди). */
    private fun speedSlack(creep: Creep): Int {
        val weight = bodyWeight(creep)
        if (weight == 0) return creep.hits // тела без веса (чистый MOVE) скорости не теряют
        var moves = liveMoves(creep)
        var slack = 0
        for (part in creep.body) {
            if (part.hits <= 0) continue
            if (moves < weight) break
            slack += part.hits
            if (part.type == MOVE) moves--
        }
        return slack
    }

    /** Цена боя: хиты, которые снимут с нас, пока враги умирают по одному под нашим огнём (лекари первыми,
     *  их лечение вычитается); урон врага — с НАШИМ множителем входящего, наш — с ЕГО. */
    private fun fightCost(enemies: List<Creep>, ours: List<Creep>): Double {
        val ourDps = ours.sumOf { effectiveDps(it, enemies) }
        if (ourDps <= 0.0) return Double.MAX_VALUE
        val order = enemies.sortedWith(compareByDescending<Creep> { InfluenceMap.profileOf(it).heal }.thenBy { it.hits })
        val ourTaken = ours.maxOfOrNull { InfluenceMap.takenOf(it) } ?: 1.0
        var remaining = enemies.sumOf { effectiveDps(it, ours) } * ourTaken
        var heal = enemies.sumOf { InfluenceMap.profileOf(it).heal }
        var damage = 0.0
        for (e in order) {
            val net = ourDps * InfluenceMap.takenOf(e) - heal
            if (net <= 0.0) return Double.MAX_VALUE
            damage += remaining * e.hits / net
            remaining -= effectiveDps(e, ours) * ourTaken
            heal -= InfluenceMap.profileOf(e).heal
        }
        return damage
    }

    private fun lanchester(dps: Double, enemyHeal: Double, hits: Double): Double =
        sqrt(maxOf(0.0, dps - enemyHeal) * maxOf(0.0, hits))

    /** Доля удара мили, которая ДОЙДЁТ: кайт-дисконт, только если противники сплошь стрелки, никто не
     *  прижат вплотную и мили медленнее каждого из них на болоте. */
    private fun meleeFactor(unit: Creep, opponents: List<Creep>): Double {
        if (opponents.any { hasMelee(it) || getRange(unit, it) <= MELEE_KEEP_RANGE }) return 1.0
        val ranged = opponents.filter { hasRanged(it) }
        if (ranged.isEmpty()) return 1.0
        val mine = swampPeriod(unit)
        return if (ranged.any { swampPeriod(it) > mine }) 1.0 else MELEE_KITE_DISCOUNT
    }

    /** Действенный урон крипа в тик против группы (с его эффектами): стрельба целиком, мили — по meleeFactor. */
    private fun effectiveDps(unit: Creep, opponents: List<Creep>, rangedK: Double = 1.0, meleeK: Double = 1.0): Double {
        val p = InfluenceMap.profileOf(unit)
        return p.ranged * rangedK + p.melee * meleeK * meleeFactor(unit, opponents)
    }

    /** Хиты в счёте мощи: по доле удара, которая дойдёт (кайтимая мили в бою не участвует; лекарь и
     *  безоружный — полностью), с поправкой на множитель входящего урона (флаг уязвимости). */
    private fun weightedHits(unit: Creep, opponents: List<Creep>, hitsK: Double = 1.0): Double {
        val p = InfluenceMap.profileOf(unit)
        val raw = p.ranged + p.melee
        val taken = InfluenceMap.takenOf(unit).coerceAtLeast(0.01)
        val share = if (raw <= 0.0) 1.0 else effectiveDps(unit, opponents) / raw
        return unit.hits * share * hitsK / taken
    }

    /** НАША мощь по Ланчестеру против группы врага: √(наш урон − его лечение) × наши хиты; K — гипотетические
     *  множители (маргинальная цена флага, см. captureAllowed). */
    private fun ourPowerOf(ours: List<Creep>, theirs: List<Creep>, rangedK: Double = 1.0, meleeK: Double = 1.0, hitsK: Double = 1.0): Double {
        val dps = ours.sumOf { effectiveDps(it, theirs, rangedK, meleeK) }
        val heal = theirs.sumOf { InfluenceMap.profileOf(it).heal }
        return lanchester(dps, heal, ours.sumOf { weightedHits(it, theirs, hitsK) })
    }

    /** Мощь врага против нашей группы; healK — гипотетический множитель НАШЕГО лечения. */
    private fun enemyPowerOf(theirs: List<Creep>, ours: List<Creep>, healK: Double = 1.0): Double {
        val dps = theirs.sumOf { effectiveDps(it, ours) }
        val heal = ours.sumOf { InfluenceMap.profileOf(it).heal } * healK
        return lanchester(dps, heal, theirs.sumOf { weightedHits(it, ours) })
    }

    /** Тики хода крипа по спуску вдоль поля потока от клетки до цели — по его телу и местности (periodAt). */
    private fun pathTicks(creep: Creep, flow: IntArray, startCell: Int): Int {
        var cell = startCell
        if (cell < 0 || flow[cell] < 0) return Int.MAX_VALUE / 2
        var ticks = 0
        var steps = 0
        while (flow[cell] > 0 && steps < 400) {
            val cx = cell / 100
            val cy = cell % 100
            var best = -1
            var bestFlow = flow[cell]
            for (dx in -1..1) for (dy in -1..1) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx < 0 || ny < 0 || nx > 99 || ny > 99) continue
                val f = flow[nx * 100 + ny]
                if (f in 0 until bestFlow) { bestFlow = f; best = nx * 100 + ny }
            }
            if (best < 0) break
            cell = best
            steps++
            ticks += periodAt(creep, cell / 100, cell % 100)
        }
        return ticks
    }

    /** Тики пути от точки до крипа по полю к нему (болото ×5, тело не учитывается) — для выбора жертвы. */
    private fun pathTicksFrom(ctx: Ctx, from: Position, to: Creep): Int {
        val flow = flowTo(ctx, to)
        val d = flow[from.x * 100 + from.y]
        return if (d < 0) Int.MAX_VALUE / 2 else d
    }

    private fun inContact(enemies: List<Creep>, ours: List<Creep>): Boolean =
        enemies.any { e -> ours.any { getRange(e, it) <= RANGED_RANGE + 1 } }

    private fun crowdMatrixOf(ctx: Ctx): CostMatrix {
        val crowdMatrix = ctx.dangerMatrix.clone()
        for (ally in ctx.active) {
            val current = crowdMatrix.get(ally.x, ally.y)
            if (current < 255) crowdMatrix.set(ally.x, ally.y, minOf(254, current + CROWD_COST))
        }
        return crowdMatrix
    }

    private fun pathStep(creep: Creep, target: Position, range: Int, dangerMatrix: CostMatrix): Position? {
        val goal = SearchGoal(pos = target, range = range)
        val result = searchPath(creep, goal, SearchPathOptions(costMatrix = dangerMatrix))
        return result.path.firstOrNull()
    }

    private fun fleeStep(creep: Creep, enemies: List<Creep>, dangerMatrix: CostMatrix): Position? {
        if (enemies.isEmpty()) return null
        val goals = enemies.map { e -> SearchGoal(pos = InfluenceMap.cell(e.x, e.y), range = RANGED_RANGE) }.toTypedArray()
        val result = searchPath(creep, goals, SearchPathOptions(flee = true, costMatrix = dangerMatrix))
        return result.path.firstOrNull()
    }

    private fun centroidOf(points: List<Position>): Position? {
        if (points.isEmpty()) return null
        return InfluenceMap.cell(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)
    }

    // ==================== диагностика ====================

    private fun logStuck(active: List<Creep>, enemyCreeps: List<Creep>) {
        for (c in active) {
            if (TrafficManager.stuckFor(c.id) != TrafficManager.STUCK_TICKS) continue
            val want = TrafficManager.lastDesiredOf(c.id)
            val occ = want?.let { w -> (active + enemyCreeps).firstOrNull { it.x * 100 + it.y == w } }
            val occWant = occ?.let { TrafficManager.lastDesiredOf(it.id) }
            println("stuck ${c.id} at (${c.x},${c.y}) fatigue=${c.fatigue} wants=${want?.let { "(${it / 100},${it % 100})" }} " +
                "occ=${occ?.let { "${it.id} my=${it.my} fatigue=${it.fatigue} ${bodySummary(it)} wants=${occWant?.let { w -> "(${w / 100},${w % 100})" } ?: "-"}" } ?: "free"}")
        }
    }

    /** ASCII-карта один раз: '#' стена, '~' болото, '.' равнина, 'F' флаг, 'm' наш крип, 'e' вражеский. */
    private fun logMap(flags: List<FlagInfo>, myCreeps: List<Creep>, enemyCreeps: List<Creep>) {
        val marks = HashMap<Int, Char>()
        fun mark(x: Int, y: Int, c: Char) { marks[x * 100 + y] = c }
        getObjectsByPrototype(StructureWall::class).forEach { mark(it.x, it.y, '#') }
        getObjectsByPrototype(StructureRampart::class).forEach { mark(it.x, it.y, 'R') }
        getObjectsByPrototype(StructureSpawn::class).forEach { mark(it.x, it.y, if (it.my == true) 'M' else 'E') }
        enemyCreeps.forEach { mark(it.x, it.y, 'e') }
        myCreeps.forEach { mark(it.x, it.y, 'm') }
        flags.forEach { mark(it.pos.x, it.pos.y, 'F') }

        var swamp = 0
        var wall = 0
        println("=== MAP (rows y=0..99, cols x=0..99) ===")
        for (y in 0..99) {
            val row = StringBuilder()
            for (x in 0..99) {
                val terrain = getTerrainAt(InfluenceMap.cell(x, y))
                if (terrain == TERRAIN_SWAMP) swamp++
                if (terrain == TERRAIN_WALL) wall++
                val structure = marks[x * 100 + y]
                row.append(
                    when {
                        structure != null -> structure
                        terrain == TERRAIN_WALL -> '#'
                        terrain == TERRAIN_SWAMP -> '~'
                        else -> '.'
                    }
                )
            }
            println("${y.toString().padStart(2, '0')}:$row")
        }
        println("=== END MAP swamp=$swamp wall=$wall plain=${10000 - swamp - wall} ===")
    }
}
