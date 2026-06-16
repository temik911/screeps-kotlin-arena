package season3.powersplit

import screeps.api.ATTACK
import screeps.api.ATTACK_POWER
import screeps.api.BodyPartType
import screeps.api.CostMatrix
import screeps.api.Creep
import screeps.api.HEAL
import screeps.api.HEAL_POWER
import screeps.api.Position
import screeps.api.RANGED_ATTACK
import screeps.api.RANGED_ATTACK_POWER
import screeps.api.RANGED_HEAL_POWER
import screeps.api.RESOURCE_ENERGY
import screeps.api.RectVisualStyle
import screeps.api.TERRAIN_WALL
import screeps.api.TOWER_ENERGY_COST
import screeps.api.TOWER_FALLOFF
import screeps.api.TOWER_FALLOFF_RANGE
import screeps.api.TOWER_OPTIMAL_RANGE
import screeps.api.TOWER_POWER_ATTACK
import screeps.api.TOWER_RANGE
import screeps.api.TextVisualStyle
import screeps.api.Visual
import screeps.api.get
import screeps.api.getObjectsByPrototype
import screeps.api.getRange
import screeps.api.getTerrainAt
import screeps.api.structures.StructureTower
import screeps.api.structures.StructureWall
import kotlin.math.abs
import kotlin.math.min

/**
 * Матрица влияния (influence map) — КОПИЯ из season3.spawnstrike (по правилу «код арены самодостаточен»,
 * тюнится здесь независимо), адаптированная под Power Split: учитывает захваченный бонус (×2 к
 * соответствующей атаке у той стороны, что владеет флагом) и УРОН БАШЕН (наши — плюс к балансу,
 * чужие — опасность/урон). Вместо общего DistanceMap держит локальный кэш стен (isWall).
 *
 * Каждый крип проецирует боевую мощь на ближайшие клетки: свои дают «+», враги «−». Знак баланса в
 * точке = кто доминирует, величина = насколько. Расчёт ленивый (без матрицы 100x100): influenceAt
 * считается только в нужных точках; dangerCostMatrix — единая матрица для pathfinder.
 */
object InfluenceMap {

    /** Максимальный радиус проекции любой мощи: дальняя reach 3 + запас на 1 шаг. */
    private const val RANGED_RADIUS = 4

    /** Дальность атаки для «разлива» опасности с огневой позиции (Чебышев, стрельба сквозь стены). */
    private const val ATTACK_RANGE = 3

    /** Эталон живучести (HP) для взвешивания боевой мощи = HP нашего бойца (8 частей × 100). Крип с
     *  бОльшим HP (дешёвый TOUGH-буфер, MOVE) переживает размен и дольше держит DPS → реально сильнее.
     *  Влияет на influenceAt/стойку (локальные решения бойца) и на Economy.power (армия в целом). */
    const val EHP_REF = 800.0

    /** Горизонт проекции опасности: на сколько тиков вперёд считаем ДОСТИЖИМОСТЬ врага (по пути, со
     *  swamp-cost). Враг за этот срок может дойти до огневой позиции и выстрелить → клетка опасна. */
    private const val DANGER_HORIZON = 4

    /** Цена шага на болото в тиках (как в DistanceMap) — на горизонте 4 болото фактически непроходимо
     *  для проекции опасности (5 > 4), т.е. угроза НЕ переходит через болото за горизонт. */
    private const val SWAMP_COST = 5

    /** Баланс >= этого — мы не слабее, можно наступать. */
    const val ADVANCE_THRESHOLD = 0.0

    /** Баланс < этого — мы слабее, отступаем. Себя в баланс ВКЛЮЧАЕМ, мощь взвешена по живучести и
     *  LOS не режет — поэтому −10 (как в spawn-strike): одиночка против танка (≈−33) отступает, а двое
     *  против одного (≈+39) наступают. Слишком мягкий порог (−60) держал одиночку в проигрышном размене. */
    const val RETREAT_THRESHOLD = -10.0

    /** Поле де-факто 100x100, координаты 0..99 (в API размеры не заданы). */
    private const val FIELD_MAX = 99

    // --- параметры CostMatrix опасности ---
    /** Множитель «мощь врага в клетке» → стоимость прохода (0..254). 3.0 (а не 6): сильный враг
     *  (3R×бонус=60) даёт ~180, а не упирается в 254 ВЕЗДЕ — остаётся ГРАДИЕНТ (вплотную дороже,
     *  на краю досягаемости дешевле), pathfinder выбирает менее опасный маршрут, а не сплошной блок. */
    private const val COST_SCALE = 3.0
    private const val MAX_COST = 254

    /** Стоимость непроходимой клетки (стена, чужой рампарт) — pathfinder её обходит. */
    private const val BLOCKED = 255

    /** Стоимость клетки СВОЕГО крипа (мягко, не блок): пути огибают своих → бойцы веером, без свопов. */
    private const val SELF_CROWD = 8

    // --- параметры визуализации ---
    private const val DEBUG_RADIUS = 6
    private const val OPACITY_SCALE = 30.0
    private const val DAMAGE_OPACITY_SCALE = 60.0
    private const val MAX_OPACITY = 0.7
    private const val MIN_DRAW = 1.0
    private const val VISUAL_BYTE_LIMIT = 400_000

    enum class Stance { ADVANCE, HOLD, RETREAT }

    /** Текущая стойка каждого крипа по id — для гистерезиса (анти-дёрганье). */
    private val stance = mutableMapOf<String, Stance>()

    /** Выбрасывает стойки погибших крипов (живые id — снаружи, раз за тик). */
    fun pruneStances(liveIds: Set<String>) {
        stance.keys.retainAll { it in liveIds }
    }

    /** Боевая мощь крипа, разложенная по типам (мили/дальняя/лечение). */
    class CombatProfile(val melee: Double, val ranged: Double, val heal: Double)

    /** Позиция центра клетки (для getRange). IntPos объявлен в PowerSplit.kt (тот же пакет). */
    fun cell(x: Int, y: Int): Position = IntPos(x = x, y = y).unsafeCast<Position>()

    /** Верхний-левый угол клетки (для Visual.rect). */
    private fun corner(x: Int, y: Int): Position = FloatPos(x = x - 0.5, y = y - 0.5).unsafeCast<Position>()

    // --- БОНУС: захваченный флаг удваивает соответствующую атаку у стороны-владельца. Множители
    // устанавливаются раз за тик (наш/вражеский бонус), применяются к проекции мощи своих/врагов. ---
    private var ourRangedMult = 1.0; private var ourMeleeMult = 1.0
    private var enemyRangedMult = 1.0; private var enemyMeleeMult = 1.0

    fun setBonuses(ourBonus: BodyPartType?, enemyBonus: BodyPartType?) {
        ourRangedMult = if (ourBonus == RANGED_ATTACK) 2.0 else 1.0
        ourMeleeMult = if (ourBonus == ATTACK) 2.0 else 1.0
        enemyRangedMult = if (enemyBonus == RANGED_ATTACK) 2.0 else 1.0
        enemyMeleeMult = if (enemyBonus == ATTACK) 2.0 else 1.0
    }

    // Стены спрашиваем у DistanceMap (единый источник: terrain + ломаемые W, обновляется его refresh()).

    /**
     * Клетки, где наш крип защищён — стоит на СВОЁМ рампарте: входящий урон уходит в рампарт, не в
     * крипа. Обновляется каждый тик.
     */
    private var protectedCells: Set<Int> = emptySet()

    fun setProtectedCells(cells: Set<Int>) {
        protectedCells = cells
    }

    /** Непроходимое для ВРАГА (его шаг сближения): наши рампарты, спавны, экстеншены, контейнеры.
     *  Стены terrain проверяются отдельно. Обновляется раз за тик, сбрасывает кэш позиций врагов. */
    private var enemyBlocked: Set<Int> = emptySet()

    /** Кэш на тик: id врага -> клетки, откуда он может действовать в этот тик. */
    private val originsCache = HashMap<String, IntArray>()

    fun setEnemyBlocked(cells: Set<Int>) {
        enemyBlocked = cells
        originsCache.clear()
    }

    /**
     * Клетки, откуда враг может действовать в ЭТОТ тик: текущая позиция + соседние, проходимые ДЛЯ
     * НЕГО. Шаг сближения через стену/рампарт/структуру невозможен — опасность не «телепортируется»
     * за препятствие, до которого врагу идти долгим обходом.
     */
    private fun enemyOrigins(enemy: Creep): IntArray = originsCache.getOrPut(enemy.id) {
        val origins = ArrayList<Int>(9)
        origins.add(enemy.x * 100 + enemy.y)
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = enemy.x + dx
                val ny = enemy.y + dy
                if (nx < 0 || ny < 0 || nx > FIELD_MAX || ny > FIELD_MAX) continue
                val key = nx * 100 + ny
                if (key in enemyBlocked) continue
                if (getTerrainAt(cell(nx, ny)) == TERRAIN_WALL) continue
                origins.add(key)
            }
        }
        origins.toIntArray()
    }

    /**
     * Эффективная дистанция атаки врага до клетки с РЕАЛЬНЫМ шагом сближения: минимальный Чебышев
     * от достижимых им за тик позиций.
     */
    private fun effectiveRangeTo(enemy: Creep, x: Int, y: Int): Int {
        var best = Int.MAX_VALUE
        for (origin in enemyOrigins(enemy)) {
            val d = maxOf(abs(origin / 100 - x), abs(origin % 100 - y))
            if (d < best) best = d
        }
        return best
    }

    /** Эффективная дистанция атаки с учётом одного шага сближения (крип двигается ~1 клетка/тик). */
    private fun effectiveDistance(distance: Int): Int = maxOf(0, distance - 1)

    /** Переиспользуемый буфер «макс. опасность врага в клетке» (без аллокаций на тик). */
    private val scratch = DoubleArray(10000)

    /**
     * Поле ДОСТИЖИМОСТИ врага: за сколько тиков враг дойдёт до каждой клетки (≤ maxTicks), по пути,
     * со swamp-cost, по проходимым ДЛЯ НЕГО клеткам (не стена/StructureWall, не наши структуры). Это
     * честная замена «1 шаг + LOS»: угроза проецируется только туда, КУДА враг реально может дойти.
     * Dial-корзины по тикам; цены {равнина 1, болото SWAMP_COST}.
     */
    private fun enemyReachField(enemy: Creep, maxTicks: Int): HashMap<Int, Int> {
        val dist = HashMap<Int, Int>()
        fun passable(x: Int, y: Int) =
            x in 0..FIELD_MAX && y in 0..FIELD_MAX && !DistanceMap.isWall(x, y) && (x * 100 + y) !in enemyBlocked
        val start = enemy.x * 100 + enemy.y
        dist[start] = 0
        val buckets = Array(maxTicks + 1) { ArrayDeque<Int>() }
        buckets[0].addLast(start)
        for (t in 0..maxTicks) {
            val b = buckets[t]
            while (b.isNotEmpty()) {
                val cur = b.removeFirst()
                if ((dist[cur] ?: Int.MAX_VALUE) != t) continue // устаревшая запись
                val cx = cur / 100; val cy = cur % 100
                for (dx in -1..1) for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx; val ny = cy + dy
                    if (!passable(nx, ny)) continue
                    val nt = t + if (DistanceMap.isSwamp(nx, ny)) SWAMP_COST else 1
                    if (nt > maxTicks) continue
                    val nk = nx * 100 + ny
                    if (nt < (dist[nk] ?: Int.MAX_VALUE)) { dist[nk] = nt; buckets[nt].addLast(nk) }
                }
            }
        }
        return dist
    }

    /** Мили бьёт только в упор (reach 1). */
    private fun meleeRate(distance: Int): Double = if (distance <= 1) 1.0 else 0.0

    /** RANGED_ATTACK: distance-rate {1:1, 2:0.4, 3:0.1} — массовая атака на дистанции слабее. */
    fun rangedRate(distance: Int): Double = when {
        distance <= 1 -> 1.0
        distance == 2 -> 0.4
        distance == 3 -> 0.1
        else -> 0.0
    }

    /** HEAL: в упор 12 (heal), на дистанции 4 (rangedHeal) — эффективность падает в 3 раза. */
    private fun healRate(distance: Int): Double = when {
        distance <= 1 -> 1.0
        distance <= 3 -> RANGED_HEAL_POWER.toDouble() / HEAL_POWER
        else -> 0.0
    }

    fun profileOf(creep: Creep): CombatProfile {
        var attack = 0
        var ranged = 0
        var heal = 0
        for (part in creep.body) {
            if (part.hits <= 0) continue // повреждённые части не работают
            when {
                part.type == ATTACK -> attack++
                part.type == RANGED_ATTACK -> ranged++
                part.type == HEAL -> heal++
            }
        }
        return CombatProfile(
            melee = (attack * ATTACK_POWER).toDouble(),
            ranged = (ranged * RANGED_ATTACK_POWER).toDouble(),
            heal = (heal * HEAL_POWER).toDouble(),
        )
    }

    /**
     * Проекция мощи профиля на клетку с учётом реальной эффективности по дистанции. Эффективная
     * дистанция учитывает один шаг сближения, поэтому крип проецирует силу на радиус «reach + 1».
     * Ranged по одиночной цели бьёт ПОЛНЫМ уроном на всей дистанции 1-3.
     */
    private fun projected(profile: CombatProfile, distance: Int): Double {
        if (distance > RANGED_RADIUS) return 0.0
        val effective = effectiveDistance(distance)
        return profile.melee * meleeRate(effective) +
            profile.ranged * (if (effective <= 3) 1.0 else 0.0) +
            profile.heal * healRate(effective)
    }


    /** Вклад одного крипа в клетку (x, y): дальность × бонус × затухание × ЖИВУЧЕСТЬ. Взвешивание по
     *  HP (creep.hits/EHP_REF, TOUGH/MOVE дают HP дёшево) делает баланс influenceAt честным: танк-враг
     *  с равным DPS, но бОльшим HP, тянет баланс в минус → боец у него отступает, а не гибнет в размене. */
    private fun contribution(creep: Creep, x: Int, y: Int, isEnemy: Boolean): Double {
        if (DistanceMap.isWall(x, y)) return 0.0
        val p = profileOf(creep)
        val rm = if (isEnemy) enemyRangedMult else ourRangedMult
        val mm = if (isEnemy) enemyMeleeMult else ourMeleeMult
        val surv = creep.hits.toDouble() / EHP_REF
        return projected(CombatProfile(p.melee * mm, p.ranged * rm, p.heal), getRange(creep, cell(x, y))) * surv
    }

    /** Урон башни по цели на дистанции r (Чебышев): полный в оптимальном радиусе, линейно падает до
     *  full*(1-FALLOFF) к FALLOFF_RANGE, дальше минимум, за TOWER_RANGE — ноль. TOWER_FALLOFF в
     *  рантайме дробь (~0.75), хотя биндинг типизирует Int — читаем через unsafeCast. */
    private fun towerDamage(r: Int): Double {
        if (r > TOWER_RANGE) return 0.0
        val full = TOWER_POWER_ATTACK.toDouble()
        val falloff = TOWER_FALLOFF.unsafeCast<Double>()
        return when {
            r <= TOWER_OPTIMAL_RANGE -> full
            r >= TOWER_FALLOFF_RANGE -> full * (1.0 - falloff)
            else -> full * (1.0 - falloff * (r - TOWER_OPTIMAL_RANGE).toDouble() / (TOWER_FALLOFF_RANGE - TOWER_OPTIMAL_RANGE))
        }
    }

    /** Суммарный урон башен стороны (my) по клетке (x,y) — заряженные, в радиусе. */
    private fun towerDamageAt(x: Int, y: Int, mine: Boolean): Double {
        var d = 0.0
        for (t in getObjectsByPrototype(StructureTower::class)) {
            if ((t.my == true) != mine) continue
            if (t.cooldown > 0) continue // на кулдауне стрелять не может — урона/угрозы нет
            if ((t.store[RESOURCE_ENERGY] ?: 0) < TOWER_ENERGY_COST) continue
            d += towerDamage(maxOf(abs(t.x - x), abs(t.y - y)))
        }
        return d
    }

    /** Урон ВРАЖЕСКИХ башен по клетке за тик (с учётом кулдауна/энергии и falloff). Публичная — для
     *  правила «не заходить под башню, если она снесёт больше, чем вылечат». */
    fun enemyTowerDamageAt(x: Int, y: Int): Double = towerDamageAt(x, y, mine = false)

    /** Баланс сил в клетке: (свои крипы + наши башни) − (вражеские крипы + чужие башни). ВЫСТРЕЛ ИДЁТ
     *  СКВОЗЬ СТЕНЫ — враг в радиусе стрельбы угрожает НЕЗАВИСИМО от стены (LOS НЕ проверяем). Боец
     *  считает СЕБЯ частью силы (иначе двое перед врагом каждый видит «нас один» и оба отступают). */
    fun influenceAt(x: Int, y: Int, allies: List<Creep>, enemies: List<Creep>): Double {
        var sum = 0.0
        for (ally in allies) sum += contribution(ally, x, y, isEnemy = false)
        for (enemy in enemies) sum -= contribution(enemy, x, y, isEnemy = true)
        sum += towerDamageAt(x, y, mine = true)
        sum -= towerDamageAt(x, y, mine = false)
        return sum
    }

    /** Чистое вражеское давление в клетке (>= 0): под огнём врага (+ чужих башен). Стрельба сквозь стены
     *  — LOS не проверяем. На своём рампарте давления нет — урон уходит в рампарт. */
    fun enemyPressureAt(x: Int, y: Int, enemies: List<Creep>): Double {
        if (x * 100 + y in protectedCells) return 0.0
        var sum = 0.0
        for (enemy in enemies) sum += contribution(enemy, x, y, isEnemy = true)
        sum += towerDamageAt(x, y, mine = false)
        return sum
    }

    /**
     * Абсолютный входящий урон по крипу на клетке (HP за тик): одиночные ATTACK/RANGED_ATTACK бьют
     * полной силой (с учётом вражеского бонуса) + чужие башни. Выстрел СКВОЗЬ СТЕНЫ (LOS не проверяем),
     * с шагом сближения (effectiveRangeTo). На своём рампарте крип защищён.
     */
    fun damageAt(x: Int, y: Int, enemies: List<Creep>): Double {
        if (x * 100 + y in protectedCells) return 0.0
        var damage = 0.0
        for (enemy in enemies) {
            val distance = getRange(enemy, cell(x, y))
            if (distance > RANGED_RADIUS) continue
            val effective = effectiveRangeTo(enemy, x, y)
            val profile = profileOf(enemy)
            if (effective <= 1) damage += profile.melee * enemyMeleeMult
            if (effective <= 3) damage += profile.ranged * enemyRangedMult
        }
        damage += towerDamageAt(x, y, mine = false)
        return damage
    }

    /**
     * Сколько HP наши лекари могут восстановить крипу на клетке за тик: heal в упор (12/часть),
     * rangedHeal на дистанции 2..3 (4/часть). Лечение работает сквозь стены (как стрельба).
     */
    fun healAt(x: Int, y: Int, allies: List<Creep>): Double {
        var heal = 0.0
        for (ally in allies) {
            val healParts = ally.body.count { it.type == HEAL && it.hits > 0 }
            if (healParts == 0) continue
            val distance = getRange(ally, cell(x, y))
            heal += when {
                distance <= 1 -> healParts * HEAL_POWER.toDouble()
                distance <= 3 -> healParts * RANGED_HEAL_POWER.toDouble()
                else -> 0.0
            }
        }
        return heal
    }

    /** Чистый входящий урон с учётом нашего лечения (>= 0): сколько HP крип реально потеряет. */
    fun netDamageAt(x: Int, y: Int, enemies: List<Creep>, allies: List<Creep>): Double =
        maxOf(0.0, damageAt(x, y, enemies) - healAt(x, y, allies))

    /**
     * CostMatrix, где опасные (под вражеским огнём + чужими башнями) клетки дороги для прохода,
     * непроходимые (`blocked`: стены, чужие рампарты) — заблокированы (255), клетки своих — мягко
     * дороже (веер). Передаётся в searchPath, чтобы путь обходил красные зоны и препятствия.
     */
    fun dangerCostMatrix(enemies: List<Creep>, allies: List<Creep>, blocked: List<Position>): CostMatrix {
        val matrix = CostMatrix()
        for (enemy in enemies) {
            val profile = profileOf(enemy)
            val ranged = profile.ranged * enemyRangedMult
            val melee = profile.melee * enemyMeleeMult
            if (ranged + melee <= 0.0) continue
            // ДОСТИЖИМОСТЬ: куда враг дойдёт за ≤DANGER_HORIZON тиков (по пути, болото дорого).
            val reach = enemyReachField(enemy, DANGER_HORIZON)
            // С каждой огневой позиции F разливаем урон на ±ATTACK_RANGE (Чебышев, СКВОЗЬ стены — выстрел
            // стен не знает). Враг бьёт из ОДНОЙ точки → берём МАКС по F (не сумму). Вес по «скоро ли
            // дойдёт»: t=0 → 1.0, t=HORIZON → 0.5. Болото на пути врага само режет проекцию (cost 5>4).
            val touched = ArrayList<Int>()
            for ((f, t) in reach) {
                val w = 1.0 - 0.5 * t / DANGER_HORIZON
                val fx = f / 100; val fy = f % 100
                for (dx in -ATTACK_RANGE..ATTACK_RANGE) for (dy in -ATTACK_RANGE..ATTACK_RANGE) {
                    val cx = fx + dx; val cy = fy + dy
                    if (cx < 0 || cy < 0 || cx > FIELD_MAX || cy > FIELD_MAX) continue
                    val cheb = maxOf(abs(dx), abs(dy))
                    val d = w * (ranged + if (cheb <= 1) melee else 0.0) // мили достаёт только в упор
                    if (d <= 0.0) continue
                    val key = cx * 100 + cy
                    if (d > scratch[key]) { if (scratch[key] == 0.0) touched.add(key); scratch[key] = d }
                }
            }
            for (key in touched) {
                val x = key / 100; val y = key % 100
                matrix.set(x, y, min(MAX_COST.toDouble(), matrix.get(x, y) + scratch[key] * COST_SCALE).toInt())
                scratch[key] = 0.0 // сброс для следующего врага
            }
        }
        // чужие башни — добавляем их урон по дальности (с falloff) в радиусе обстрела
        for (t in getObjectsByPrototype(StructureTower::class)) {
            if (t.my != false) continue
            if (t.cooldown > 0) continue // на кулдауне стрелять не может — опасности не создаёт
            if ((t.store[RESOURCE_ENERGY] ?: 0) < TOWER_ENERGY_COST) continue
            for (dx in -TOWER_RANGE..TOWER_RANGE) {
                for (dy in -TOWER_RANGE..TOWER_RANGE) {
                    val x = t.x + dx
                    val y = t.y + dy
                    if (x < 0 || y < 0 || x > FIELD_MAX || y > FIELD_MAX) continue
                    val dmg = towerDamage(maxOf(abs(dx), abs(dy)))
                    if (dmg <= 0.0) continue
                    val cost = min(MAX_COST.toDouble(), matrix.get(x, y) + dmg * COST_SCALE).toInt()
                    matrix.set(x, y, cost)
                }
            }
        }
        // клетки своих — мягко дороже: бойцы веером, без свопов в одну точку
        for (a in allies) matrix.set(a.x, a.y, min(MAX_COST, matrix.get(a.x, a.y) + SELF_CROWD))
        // свой рампарт безопасен даже под огнём (урон уходит в рампарт) — пути охотно идут через него
        for (cell in protectedCells) matrix.set(cell / 100, cell % 100, 1)
        // клетки вражеских крипов непроходимы: searchPath сам крипов не знает и прокладывает первый
        // шаг СКВОЗЬ врага — TrafficManager такой ход отклоняет, и крип вечно бодает занятую клетку
        for (enemy in enemies) {
            if (enemy.x in 0..FIELD_MAX && enemy.y in 0..FIELD_MAX) matrix.set(enemy.x, enemy.y, BLOCKED)
        }
        // непроходимые клетки ставим последними — блок перекрывает любую стоимость опасности
        for (cell in blocked) {
            if (cell.x in 0..FIELD_MAX && cell.y in 0..FIELD_MAX) matrix.set(cell.x, cell.y, BLOCKED)
        }
        return matrix
    }

    /**
     * Обновляет стойку крипа с гистерезисом: переключение только при выходе за пороги, между порогами
     * стойка сохраняется — крип не «дрожит» на границе.
     */
    fun updateStance(id: String, balance: Double): Stance {
        val current = stance[id] ?: Stance.HOLD
        val next = when {
            balance >= ADVANCE_THRESHOLD -> Stance.ADVANCE
            balance < RETREAT_THRESHOLD -> Stance.RETREAT
            else -> current
        }
        stance[id] = next
        return next
    }

    /**
     * Подсветка баланса сил вокруг наших бойцов: зелёный — мы сильнее, красный — враг. Рисуем только
     * окрестности бойцов, чтобы не превысить лимит визуалов.
     */
    fun drawDebug(rangers: List<Creep>, allies: List<Creep>, enemies: List<Creep>) {
        val visual = Visual()
        val drawn = HashSet<Int>()
        for (ranger in rangers) {
            for (dx in -DEBUG_RADIUS..DEBUG_RADIUS) {
                for (dy in -DEBUG_RADIUS..DEBUG_RADIUS) {
                    val x = ranger.x + dx
                    val y = ranger.y + dy
                    if (x < 0 || y < 0 || x > FIELD_MAX || y > FIELD_MAX) continue
                    if (!drawn.add(x * 100 + y)) continue

                    val balance = influenceAt(x, y, allies, enemies)
                    if (abs(balance) < MIN_DRAW) continue

                    val color = if (balance > 0) "#00ff00" else "#ff0000"
                    val opacity = min(MAX_OPACITY, abs(balance) / OPACITY_SCALE)
                    visual.rect(corner(x, y), 1, 1, RectVisualStyle(fill = color, opacity = opacity))

                    if (visual.size() > VISUAL_BYTE_LIMIT) return
                }
            }
        }
    }

    /**
     * Подсветка входящего урона вокруг наших бойцов: оранжевая клетка тем насыщеннее, чем больше HP
     * прилетит, плюс число урона. Отдельный слой — поверх матрицы влияния.
     */
    fun drawDamage(rangers: List<Creep>, enemies: List<Creep>) {
        val visual = Visual(layer = 1)
        val drawn = HashSet<Int>()
        for (ranger in rangers) {
            for (dx in -DEBUG_RADIUS..DEBUG_RADIUS) {
                for (dy in -DEBUG_RADIUS..DEBUG_RADIUS) {
                    val x = ranger.x + dx
                    val y = ranger.y + dy
                    if (x < 0 || y < 0 || x > FIELD_MAX || y > FIELD_MAX) continue
                    if (!drawn.add(x * 100 + y)) continue

                    val damage = damageAt(x, y, enemies)
                    if (damage < MIN_DRAW) continue

                    val opacity = min(MAX_OPACITY, damage / DAMAGE_OPACITY_SCALE)
                    visual.rect(corner(x, y), 1, 1, RectVisualStyle(fill = "#ff6600", opacity = opacity))
                    visual.text(damage.toInt().toString(), cell(x, y), TextVisualStyle(color = "#ffffff", font = "0.4"))

                    if (visual.size() > VISUAL_BYTE_LIMIT) return
                }
            }
        }
    }

    /**
     * Подсветка ГОТОВОЙ danger-матрицы (то, что реально видит pathfinder/скоринг): для каждой клетки
     * с ненулевой стоимостью — оранжевый фон (насыщенность по величине) и ЧИСЛО стоимости; 255
     * (непроходимо: стены/чужие рампарты/клетки врагов) — красным. Так видно, почему «безопасная на
     * вид» клетка дорогая (проекция достижимости врага + дилатация стрельбы сквозь стены).
     */
    fun drawDangerMatrix(matrix: CostMatrix) {
        val visual = Visual(layer = 1)
        for (x in 0..FIELD_MAX) {
            for (y in 0..FIELD_MAX) {
                val c = matrix.get(x, y)
                if (c <= 0) continue
                if (c >= BLOCKED) {
                    visual.rect(corner(x, y), 1, 1, RectVisualStyle(fill = "#ff0000", opacity = 0.35))
                } else {
                    visual.rect(corner(x, y), 1, 1, RectVisualStyle(fill = "#ff6600", opacity = min(MAX_OPACITY, c / 120.0)))
                    visual.text(c.toString(), cell(x, y), TextVisualStyle(color = "#ffffff", font = "0.4"))
                }
                if (visual.size() > VISUAL_BYTE_LIMIT) return
            }
        }
    }

    /**
     * ДИАГНОСТИКА ГЕОМЕТРИИ: рисует две стратегические точки по ГОТОВЫМ данным (считает PowerSplit, где
     * есть searchPath). Клетки — пакеты x*100+y.
     *  path1  — путь наш доп.источник → база врага (бледный, контекст);
     *  corridor — клетки 1-ширинного коридора НА этом пути (циан) — ТОЧКА 1;
     *  path2  — путь доп.источник врага → база врага (бледный);
     *  swamp  — ВСЁ связное (8-связность) болото, через которое проходит path2 (зелёный) — ТОЧКА 2.
     * Источники — синяя рамка + подпись. Только отладка (DEBUG_STRATEGY); на поведение бота не влияет.
     */
    fun drawStrategicPoints(
        ourAddX: Int, ourAddY: Int, enemyAddX: Int, enemyAddY: Int,
        path1: IntArray, corridor: IntArray, path2: IntArray, swamp: IntArray,
        guardCell: Int, swampCtrlCell: Int,
    ) {
        val visual = Visual(layer = 1)
        // маршруты — бледные точки (видно, где пролегает путь)
        for (c in path1) visual.rect(corner(c / 100, c % 100), 1, 1, RectVisualStyle(fill = "#ffffff", opacity = 0.12))
        for (c in path2) visual.rect(corner(c / 100, c % 100), 1, 1, RectVisualStyle(fill = "#ffffff", opacity = 0.12))
        // ТОЧКА 1: узкий коридор на пути наш источник → база врага
        for (c in corridor) {
            visual.rect(corner(c / 100, c % 100), 1, 1, RectVisualStyle(fill = "#00e5ff", opacity = 0.75))
            if (visual.size() > VISUAL_BYTE_LIMIT) return
        }
        // ТОЧКА 2: всё связное болото на пути источник врага → база врага
        for (c in swamp) {
            visual.rect(corner(c / 100, c % 100), 1, 1, RectVisualStyle(fill = "#33cc33", opacity = 0.5))
            if (visual.size() > VISUAL_BYTE_LIMIT) return
        }
        // источники: синяя рамка + подпись
        visual.rect(corner(ourAddX, ourAddY), 1, 1, RectVisualStyle(fill = "#0066ff", opacity = 0.9))
        visual.text("our S2 ($ourAddX,$ourAddY)", cell(ourAddX, ourAddY - 1), TextVisualStyle(color = "#00e5ff", font = "0.6"))
        visual.rect(corner(enemyAddX, enemyAddY), 1, 1, RectVisualStyle(fill = "#0066ff", opacity = 0.9))
        visual.text("enemy S2 ($enemyAddX,$enemyAddY)", cell(enemyAddX, enemyAddY - 1), TextVisualStyle(color = "#33cc33", font = "0.6"))
        // КЛЕТКИ КОНТРОЛЯ: страж коридора (жёлтый, обводка) и обстрел болота (оранжевый, обводка)
        if (guardCell >= 0) {
            visual.rect(corner(guardCell / 100, guardCell % 100), 1, 1, RectVisualStyle(fill = "#ffe000", opacity = 0.85, stroke = "#000000", strokeWidth = 0.12))
            visual.text("GUARD", cell(guardCell / 100, guardCell % 100 + 1), TextVisualStyle(color = "#ffe000", font = "0.5"))
        }
        if (swampCtrlCell >= 0) {
            visual.rect(corner(swampCtrlCell / 100, swampCtrlCell % 100), 1, 1, RectVisualStyle(fill = "#ff8800", opacity = 0.85, stroke = "#000000", strokeWidth = 0.12))
            visual.text("SWAMP", cell(swampCtrlCell / 100, swampCtrlCell % 100 + 1), TextVisualStyle(color = "#ff8800", font = "0.5"))
        }
    }
}
