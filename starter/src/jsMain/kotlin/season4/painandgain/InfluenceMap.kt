package season4.painandgain

import kotlinx.js.JsPlainObject
import screeps.api.ATTACK
import screeps.api.ATTACK_POWER
import screeps.api.CostMatrix
import screeps.api.Creep
import screeps.api.EFF_ATTACK_MODIFIER
import screeps.api.EFF_DAMAGE_TAKEN_MODIFIER
import screeps.api.EFF_HEAL_MODIFIER
import screeps.api.EFF_RANGED_ATTACK_MODIFIER
import screeps.api.Effect
import screeps.api.GameObject
import screeps.api.HEAL
import screeps.api.HEAL_POWER
import screeps.api.Position
import screeps.api.RANGED_ATTACK
import screeps.api.RANGED_ATTACK_POWER
import screeps.api.RANGED_HEAL_POWER
import screeps.api.RectVisualStyle
import screeps.api.TERRAIN_WALL
import screeps.api.TOWER_COOLDOWN
import screeps.api.TOWER_ENERGY_COST
import screeps.api.TOWER_FALLOFF
import screeps.api.TOWER_FALLOFF_RANGE
import screeps.api.TOWER_OPTIMAL_RANGE
import screeps.api.TOWER_POWER_ATTACK
import screeps.api.TextVisualStyle
import screeps.api.Visual
import screeps.api.getRange
import screeps.api.getTerrainAt
import screeps.api.getTicks
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Целочисленная позиция клетки (центр клетки). */
@JsPlainObject
external interface IntPos {
    var x: Int
    var y: Int
}

/** Дробная позиция — нужна для угла клетки при отрисовке rect. */
@JsPlainObject
external interface FloatPos {
    var x: Double
    var y: Double
}

/**
 * Матрица опасности (influence map). Каждый крип проецирует боевую мощь на ближайшие клетки:
 * свои дают «+», враги «−». Знак баланса в точке = кто доминирует, величина = насколько.
 * Расчёт ленивый (без матрицы 100x100): influenceAt считается только в нужных точках.
 */
object InfluenceMap {

    /** Максимальный радиус проекции любой мощи: дальняя reach 3 + запас на 1 шаг. */
    private const val RANGED_RADIUS = 4

    /** Баланс >= этого — мы не слабее, можно наступать. */
    private const val ADVANCE_THRESHOLD = 0.0

    /** Баланс < этого — явно слабее, отступаем (≈ одна лишняя вражеская RANGED-часть рядом). */
    private const val RETREAT_THRESHOLD = -10.0

    /** Поле де-факто 100x100, координаты 0..99 (в API размеры не заданы). */
    private const val FIELD_MAX = 99

    // --- параметры CostMatrix опасности ---
    /** Множитель перевода «вражеской мощи в клетке» в стоимость прохода (0..254). */
    private const val COST_SCALE = 6.0
    private const val MAX_COST = 254

    /** Стоимость непроходимой клетки (стена, чужой рампарт) — pathfinder её обходит. */
    private const val BLOCKED = 255

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

    /** Позиция центра клетки (для getRange и searchPath-целей). */
    fun cell(x: Int, y: Int): Position = IntPos(x = x, y = y).unsafeCast<Position>()

    /** Верхний-левый угол клетки (для Visual.rect, координаты центрированы по клеткам). */
    private fun corner(x: Int, y: Int): Position =
        FloatPos(x = x - 0.5, y = y - 0.5).unsafeCast<Position>()

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
        // Без множителя hits/hitsMax: счёт ЖИВЫХ частей — уже точная мера боеспособности.
        // Урон сносит части спереди-назад (обычно MOVE-буфер), и дополнительный дисконт по HP
        // дважды занижал мощь раненого: damageAt недооценивал врага → mustFlee срабатывал поздно.
        // Pain and Gain: флаги очков вешают на владельца глобальные модификаторы действий (×0.8 за
        // один флаг типа, ×0.6 за два) — мощь считается по МОДИФИЦИРОВАННОМУ урону, иначе отряд с
        // двумя стрелковыми флагами «побеждал» бы по счёту и проигрывал в поле
        return CombatProfile(
            melee = if (attack == 0) 0.0 else modified(creep, EFF_ATTACK_MODIFIER, (attack * ATTACK_POWER).toDouble()),
            ranged = if (ranged == 0) 0.0 else modified(creep, EFF_RANGED_ATTACK_MODIFIER, (ranged * RANGED_ATTACK_POWER).toDouble()),
            heal = if (heal == 0) 0.0 else modified(creep, EFF_HEAL_MODIFIER, (heal * HEAL_POWER).toDouble()),
        )
    }

    /**
     * Мощь ВСЕХ боевых частей тела — живых и МЁРТВЫХ: то, чем крип станет, если его вылечат.
     * Лечение возвращает части — записанная механика этой арены и причина, по которой наш раздетый крип
     * остаётся в армии (матч 8: melee_1 из M5 с 416 хитами стал M8A8 к 199-му тику у одного лекаря).
     * Значит выбитая часть врага не убрана, а лишь ВЫКЛЮЧЕНА, пока у него жив хоть один лекарь.
     */
    fun potentialOf(creep: Creep): CombatProfile {
        var attack = 0
        var ranged = 0
        var heal = 0
        for (part in creep.body) {
            when {
                part.type == ATTACK -> attack++
                part.type == RANGED_ATTACK -> ranged++
                part.type == HEAL -> heal++
            }
        }
        return CombatProfile(
            melee = if (attack == 0) 0.0 else modified(creep, EFF_ATTACK_MODIFIER, (attack * ATTACK_POWER).toDouble()),
            ranged = if (ranged == 0) 0.0 else modified(creep, EFF_RANGED_ATTACK_MODIFIER, (ranged * RANGED_ATTACK_POWER).toDouble()),
            heal = if (heal == 0) 0.0 else modified(creep, EFF_HEAL_MODIFIER, (heal * HEAL_POWER).toDouble()),
        )
    }

    // ---------------- эффекты (Pain and Gain) ----------------

    /** Модификаторы СТОРОНЫ (глобальный дебафф владельца флагов очков) — запасной источник, когда API не
     *  отдаёт массив effects на объекте: считаются ботом по числу флагов каждого типа у стороны. */
    class SideMods(val attack: Double = 1.0, val ranged: Double = 1.0, val heal: Double = 1.0, val taken: Double = 1.0) {
        override fun toString() = "A×$attack R×$ranged H×$heal D×$taken"
    }

    private var ourMods = SideMods()
    private var enemyMods = SideMods()

    fun setSideMods(ours: SideMods, theirs: SideMods) {
        ourMods = ours
        enemyMods = theirs
    }

    /** Эффект типа type на объекте; null — эффекта нет (или API не отдаёт effects вовсе). */
    fun effectOf(obj: GameObject, type: String): Effect? {
        val effects = obj.effects ?: return null
        for (e in effects) if (e.effectType == type) return e
        return null
    }

    /** true, если объект несёт массив effects (пусть и пустой) — тогда ему верим, а не подсчёту флагов. */
    fun hasEffectsApi(obj: GameObject): Boolean = obj.effects != null

    /** base × (multiplier ?: 1) + (offset ?: 0) по эффекту объекта (документация: множитель, потом
     *  смещение); без массива effects — по модификаторам стороны; без эффекта такого типа — base. */
    fun modified(creep: Creep, type: String, base: Double): Double {
        if (creep.effects == null) return base * sideMul(creep.my, type)
        val e = effectOf(creep, type) ?: return base
        val mul = e.data.multiplier ?: 1.0
        val off = e.data.offset ?: 0.0
        return base * mul + off
    }

    private fun sideMul(my: Boolean, type: String): Double {
        val m = if (my) ourMods else enemyMods
        return when (type) {
            EFF_ATTACK_MODIFIER -> m.attack
            EFF_RANGED_ATTACK_MODIFIER -> m.ranged
            EFF_HEAL_MODIFIER -> m.heal
            EFF_DAMAGE_TAKEN_MODIFIER -> m.taken
            else -> 1.0
        }
    }

    /** Множитель ВХОДЯЩЕГО боевого урона по крипу (EFF_DAMAGE_TAKEN_MODIFIER; башни он не касается). */
    fun takenOf(creep: Creep): Double = modified(creep, EFF_DAMAGE_TAKEN_MODIFIER, 1.0)

    /** Множитель входящего урона по НАШИМ крипам — для damageAt/fireAt, где жертва ещё не известна
     *  (эффект глобален для стороны, так что любой наш крип его представляет). Ставит бот раз в тик. */
    private var ourTaken = 1.0

    fun setOurTaken(v: Double) {
        ourTaken = v
    }

    /**
     * Клетки, где наш крип защищён — стоит на СВОЁМ рампарте (право пользоваться рампартами
     * дают флаги): входящий урон уходит в рампарт, не в крипа. Обновляется каждый тик.
     */
    private var protectedCells: Set<Int> = emptySet()

    fun setProtectedCells(cells: Set<Int>) {
        protectedCells = cells
    }

    /** Непроходимое для ВРАГА (его шаг сближения): наши/нейтральные рампарты, спавны, экстеншены.
     *  Стены terrain проверяются отдельно. Обновляется раз за тик, сбрасывает кэш позиций врагов. */
    private var enemyBlocked: Set<Int> = emptySet()

    /** Кэш на тик: id врага -> клетки, откуда он может действовать в этот тик. */
    private val originsCache = HashMap<String, IntArray>()

    // ---------------- башни врага ----------------
    // Башня арены — не «мировая»: удар 1000 в упор и минус 50 за клетку (ноль на 21-й), выстрел раз в
    // кулдаун 10 за 10 энергии при ёмкости 10 — то есть ОДИН выстрел на заправку, кормят её носильщики.
    // Один выстрел на 850-1000 выключает целого M8R4 (части гибнут спереди: MOVE, затем RANGED).
    // Матч 11: четыре бойца двух волн легли у спавна врага при combat=false flee=false — бот видел
    // башню только как препятствие, урон ниоткуда не объяснял и не бежал.

    /** Башня врага как источник огня: клетка, кормится ли (выстрел в ней или носильщик с энергией в
     *  кулдауне хода — отдаёт вплотную мгновенно) и остаток кулдауна. Обновляется раз за тик. */
    class TowerThreat(val x: Int, val y: Int, val fed: Boolean, val cooldown: Int)

    private var enemyTowers: List<TowerThreat> = emptyList()

    fun setEnemyTowers(towers: List<TowerThreat>) {
        enemyTowers = towers
    }

    /** Числовая константа арены с запасным значением: константы объявлены снаружи, и отсутствующая
     *  дала бы NaN во всей арифметике урона (Int-умножение Kotlin/JS вдобавок усекло бы дробный falloff). */
    private fun num(v: dynamic, fallback: Double): Double =
        if (jsTypeOf(v) == "number") v.unsafeCast<Double>() else fallback

    private val towerPower: Double by lazy { num(TOWER_POWER_ATTACK.asDynamic(), 1000.0) }
    private val towerOptimal: Double by lazy { num(TOWER_OPTIMAL_RANGE.asDynamic(), 1.0) }
    val towerFalloffRange: Double by lazy { num(TOWER_FALLOFF_RANGE.asDynamic(), 21.0) }
    private val towerFalloff: Double by lazy { num(TOWER_FALLOFF.asDynamic(), 1.0) }
    /** Тиков между выстрелами (не меньше одного: без кулдауна башня бьёт каждый тик). */
    val towerCooldown: Int by lazy { maxOf(1.0, num(TOWER_COOLDOWN.asDynamic(), 10.0)).toInt() }
    /** Энергии на выстрел. */
    val towerCost: Int by lazy { num(TOWER_ENERGY_COST.asDynamic(), 10.0).toInt() }

    /** Урон одного выстрела башни по дистанции: линейно от towerPower в towerOptimal до нуля в
     *  towerFalloffRange (документация арены: 1000 в упор, −50 за клетку). */
    fun towerShot(range: Int): Double {
        if (range >= towerFalloffRange) return 0.0
        val over = maxOf(0.0, range - towerOptimal)
        val span = maxOf(1.0, towerFalloffRange - towerOptimal)
        return maxOf(0.0, towerPower * (1.0 - towerFalloff * over / span))
    }

    /** Средний урон кормленных башен по клетке за тик (выстрел раз в кулдаун). Стен выстрел не знает. */
    fun towerSustainedAt(x: Int, y: Int): Double {
        var sum = 0.0
        for (t in enemyTowers) {
            if (!t.fed) continue
            sum += towerShot(maxOf(abs(t.x - x), abs(t.y - y))) / towerCooldown
        }
        return sum
    }

    /** Самый тяжёлый ОДИН выстрел кормленной башни по клетке: «следующий выстрел меня убьёт».
     *  requireFed=false — любая башня: для объяснения ПРОШЛОГО урона (последний заряд она уже потратила,
     *  и к нашему тику кормленной не выглядит — стенд: ghost −1000 вплотную к башне). */
    fun towerBurstAt(x: Int, y: Int, requireFed: Boolean = true): Double {
        var best = 0.0
        for (t in enemyTowers) {
            if (requireFed && !t.fed) continue
            val s = towerShot(maxOf(abs(t.x - x), abs(t.y - y)))
            if (s > best) best = s
        }
        return best
    }

    fun setEnemyBlocked(cells: Set<Int>) {
        enemyBlocked = cells
        originsCache.clear()
    }

    /**
     * Клетки, откуда враг может действовать в ЭТОТ тик: текущая позиция + соседние, проходимые
     * ДЛЯ НЕГО. Шаг сближения через стену/рампарт/структуру невозможен — опасность не должна
     * «телепортироваться» за препятствие, до которого врагу идти долгим обходом.
     */
    private fun enemyOrigins(enemy: Creep): IntArray = originsCache.getOrPut(enemy.id) {
        val origins = ArrayList<Int>(9)
        origins.add(enemy.x * 100 + enemy.y)
        // с усталостью шага нет (v48): крип с fatigue > 0 в этот тик не двинется — мили достаёт только вплотную. Матч 90
        // (けろびー, фермер): четыре его мили и лекарь стояли в болоте с усталостью 16–48 в 4–7 клетках от нашей армии
        // пятьдесят тиков (t=1120–1170), а армия кралась на 1–2 клетки за 20 тиков — клетки в 2–3 от них считались под
        // ударом мили «с шагом сближения», которого у застрявшего нет
        if (enemy.fatigue > 0) return@getOrPut origins.toIntArray()
        for (dx in sym(1)) {
            for (dy in sym(1)) {
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
     * от достижимых им за тик позиций. Приблизиться сквозь препятствие враг не может — опасность
     * не «телепортируется» за стену, до которой ему идти долгим обходом.
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

    /** Мили бьёт только в упор (reach 1). */
    private fun meleeRate(distance: Int): Double = if (distance <= 1) 1.0 else 0.0

    /** RANGED_ATTACK: distance-rate {1:1, 2:0.4, 3:0.1} — на дистанции бьёт заметно слабее. */
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

    /**
     * Проекция мощи профиля на клетку с учётом реальной эффективности по дистанции.
     * Эффективная дистанция учитывает один шаг сближения, поэтому крип проецирует силу
     * на радиус «reach + 1». Ranged по одиночной цели бьёт ПОЛНЫМ уроном на всей дистанции 1-3
     * (falloff {1, 0.4, 0.1} — только у массовой атаки, для угрозы он занижал бы дальников).
     */
    private fun projected(profile: CombatProfile, distance: Int): Double {
        if (distance > RANGED_RADIUS) return 0.0
        val effective = effectiveDistance(distance)
        return profile.melee * meleeRate(effective) +
            profile.ranged * (if (effective <= 3) 1.0 else 0.0) +
            profile.heal * healRate(effective)
    }

    /**
     * Стена между ВРАЖЕСКИМ источником и клеткой: вражеское влияние/урон/опасность модель
     * распространяет только по достижимости, НЕ сквозь стены (решение пользователя) — без этого
     * враг за стенным поясом «красил» опасность сквозь стену и запирал больших на спавне.
     * Сами выстрелы в игре стены НЕ блокируют — НАША стрельба (shoot/canShoot/outgoingValue)
     * этим пользуется и стен не проверяет, а во вражеском уроне стена режет только шаг сближения
     * (damageAt), не сам выстрел. НЕ применять к нашим вкладам и НЕ удалять из вражеских.
     * true, если клетка-цель сама стена ИЛИ между источником и целью есть стена
     * (грубый LOS: сэмплируем целые клетки на прямой).
     */
    private fun wallBetween(srcX: Int, srcY: Int, x: Int, y: Int): Boolean {
        if (DistanceMap.isWall(x, y)) return true // на саму стену матрицы не действуют
        val steps = maxOf(abs(x - srcX), abs(y - srcY))
        for (i in 1 until steps) { // промежуточные клетки (концы исключены)
            val cx = srcX + ((x - srcX).toDouble() * i / steps).roundToInt()
            val cy = srcY + ((y - srcY).toDouble() * i / steps).roundToInt()
            if (DistanceMap.isWall(cx, cy)) return true
        }
        return false
    }

    /** Вклад одного крипа в клетку (x, y) с учётом дальности и затухания. */
    private fun contribution(creep: Creep, x: Int, y: Int): Double {
        if (DistanceMap.isWall(x, y)) return 0.0 // на самой стене стоять нельзя — вклад не нужен
        return projected(profileOf(creep), getRange(creep, cell(x, y)))
    }

    /** Баланс сил в клетке: сумма своих минус сумма вражеских вкладов.
     *  Свои вклады — сквозь стены (наша стрельба стен не знает), вражеские — только по LOS. */
    fun influenceAt(x: Int, y: Int, allies: List<Creep>, enemies: List<Creep>): Double {
        var sum = 0.0
        for (ally in allies) sum += contribution(ally, x, y)
        for (enemy in enemies) {
            if (wallBetween(enemy.x, enemy.y, x, y)) continue
            sum -= contribution(enemy, x, y)
        }
        sum -= towerSustainedAt(x, y)
        return sum
    }

    /** Чистое вражеское давление в клетке (>= 0): насколько эта позиция под огнём врага.
     *  На своём рампарте давления нет — урон уходит в рампарт. */
    fun enemyPressureAt(x: Int, y: Int, enemies: List<Creep>): Double {
        if (x * 100 + y in protectedCells) return 0.0
        var sum = 0.0
        for (enemy in enemies) {
            if (wallBetween(enemy.x, enemy.y, x, y)) continue
            sum += contribution(enemy, x, y)
        }
        sum += towerSustainedAt(x, y)
        return sum
    }

    /**
     * Абсолютный входящий урон по крипу на клетке (HP за тик). В отличие от influence/pressure
     * это не «контроль зоны», а реальный урон по одной цели: одиночные ATTACK/RANGED_ATTACK
     * бьют полной силой (ranged — на дистанции 1..3 без падения). Вражеский урон распространяется
     * только по достижимости (wallBetween — не сквозь стены) и учитывает шаг сближения врага.
     * На своём рампарте крип защищён — урон уходит в рампарт.
     */
    fun damageAt(x: Int, y: Int, enemies: List<Creep>): Double {
        if (x * 100 + y in protectedCells) return 0.0
        var damage = 0.0
        for (enemy in enemies) {
            val distance = getRange(enemy, cell(x, y))
            if (distance > RANGED_RADIUS) continue
            // Стена режет только ШАГ СБЛИЖЕНИЯ (за стену враг движением не «достаёт»), но не выстрел:
            // в игре стены выстрел не блокируют. Матч 7: боец на (67,7) десять тиков «держал строй»
            // под огнём M3R3 с (70,8) — между ними стена (68,7), и damageAt считал клетку безопасной
            val effective = if (wallBetween(enemy.x, enemy.y, x, y)) distance else effectiveRangeTo(enemy, x, y)
            val profile = profileOf(enemy)
            // урон крипов — с нашим множителем входящего (флаг EFF_DAMAGE_TAKEN_MODIFIER); башни он не касается
            if (effective <= 1) damage += profile.melee * ourTaken // ATTACK достаёт в упор
            if (effective <= 3) damage += profile.ranged * ourTaken // RANGED_ATTACK — полный урон по одиночной цели
        }
        damage += towerSustainedAt(x, y) // башня: средний урон за тик, залп — в towerBurstAt
        return damage
    }

    /**
     * Урон по клетке ЧЕРЕЗ [lead] тиков хода: то же, что [damageAt], но враг успевает подойти ещё на столько клеток
     * (v354). Нужно там, где решение об уходе принимается заранее: разбор 12 наших смертей против けろびー#19 показал,
     * что его мили стоит в четырёх клетках от жертвы в среднем 2,9 тика и в шести — 4,9 тика ДО первого удара, а
     * полную скорость крип теряет уже через 3,0 тика после первого удара (M6R6 замедляется при 500 хитах, M8A8 при
     * 700). Мера «кто достаёт сейчас» видит угрозу ровно тогда, когда уходить поздно.
     */
    fun damageSoonAt(x: Int, y: Int, enemies: List<Creep>, lead: Int): Double {
        if (x * 100 + y in protectedCells) return 0.0
        var damage = 0.0
        for (enemy in enemies) {
            val distance = getRange(enemy, cell(x, y))
            if (distance > RANGED_RADIUS + lead) continue
            val base = if (wallBetween(enemy.x, enemy.y, x, y)) distance else effectiveRangeTo(enemy, x, y)
            val effective = maxOf(0, base - lead)
            val profile = profileOf(enemy)
            if (effective <= 1) damage += profile.melee * ourTaken
            if (effective <= 3) damage += profile.ranged * ourTaken
        }
        damage += towerSustainedAt(x, y)
        return damage
    }

    /**
     * Фактический огонь по клетке в ЭТОТ тик: только то, что враг достаёт с текущей позиции, без
     * шага сближения и без стен (выстрелы стен не знают). Для оценки «сколько сниму, стоя тут K
     * тиков» — цены болотного шага под огнём; карты опасности и влияния остаются на damageAt.
     */
    fun fireAt(x: Int, y: Int, enemies: List<Creep>): Double {
        if (x * 100 + y in protectedCells) return 0.0
        var damage = 0.0
        for (enemy in enemies) {
            val distance = getRange(enemy, cell(x, y))
            if (distance > 3) continue
            val profile = profileOf(enemy)
            if (distance <= 1) damage += profile.melee * ourTaken
            damage += profile.ranged * ourTaken
        }
        damage += towerSustainedAt(x, y)
        return damage
    }

    /**
     * Сколько HP наши лекари могут восстановить крипу на клетке за тик: heal в упор (12/часть),
     * rangedHeal на дистанции 2..3 (4/часть). Используется, чтобы из входящего урона вычесть лечение.
     */
    fun healAt(x: Int, y: Int, allies: List<Creep>): Double {
        var heal = 0.0
        var raw = 0.0
        for (ally in allies) {
            // ДЕБАФФ ЛЕЧЕНИЯ (v204, этап 4). Здесь считалось healParts * HEAL_POWER — по НОМИНАЛУ части, мимо
            // EFF_HEAL_MODIFIER, который вешают на владельца флаги очков (H×0.8 за один флаг типа, H×0.6 за два).
            // Единственное место модели, где это было так: profileOf применяет модификатор с самого начала, и
            // потому урон врага мы считали дебаффнутым, а СВОЁ лечение — полным. Ошибка одностороння и всегда в
            // одну сторону: netDamageAt = damageAt − healAt занижал чистый входящий ровно на разницу, то есть
            // клетка под огнём выглядела тем безопаснее, чем больше лечащих флагов мы держим.
            val full = profileOf(ally).heal          // уже с EFF_HEAL_MODIFIER
            if (full <= 0.0) continue
            val distance = getRange(ally, cell(x, y)) // лечение, как и стрельба, работает сквозь стены
            val rate = when {
                distance <= 1 -> 1.0
                distance <= 3 -> HEAL_FALLOFF
                else -> 0.0
            }
            heal += full * rate
            raw += ally.body.count { it.type == HEAL && it.hits > 0 } * HEAL_POWER.toDouble() * rate
        }
        // прибор: доля вызовов, где дебафф вообще что-то изменил. Ноль на матче без лечащих флагов — правильный
        // ноль, а не мёртвый код; ненулевой числитель называет цену прежней ошибки числом
        healAtAll++
        if (abs(raw - heal) > 0.5) healAtDebuffed++
        return heal
    }

    /** Вызовов healAt и вызовов, где дебафф лечения изменил ответ. */
    private var healAtAll = 0
    private var healAtDebuffed = 0

    fun healDebuffStats(): String = "$healAtDebuffed/$healAtAll"

    /** Чистый входящий урон с учётом нашего лечения (>= 0): сколько HP крип реально потеряет. */
    fun netDamageAt(x: Int, y: Int, enemies: List<Creep>, allies: List<Creep>): Double =
        maxOf(0.0, damageAt(x, y, enemies) - healAt(x, y, allies))


    // ---------------- поля влияния (v204, этап 3) ----------------
    // Одиннадцать плотных массивов вместо ленивых поцелевых вызовов. Довод не в CPU (хотя и в нём:
    // profileOf зовётся сегодня ВНУТРИ поцелевых циклов, а incNext строится пять раз за тик над одним
    // множеством врагов), а в том, что ленивая точка не умеет ответить на вопросы «где линия фронта»,
    // «где фронт проседает» и «куда идти, если ни одна из восьми соседних клеток не годится». На них
    // отвечает только матрица целиком: у поля есть градиент, у точки — нет.
    //
    // ⚠️ E = eMelee + eRanged — ТОЧНЫЙ ПЕРЕНОС incNext из commandFight: сумма профилей врага без скидок,
    // без множителя входящего урона и без гейта стены. Так сделано намеренно. Этапы 3–4 обещают ПУСТОЙ
    // дифф отчёта стенда, и любое уточнение модели, внесённое заодно с переносом, этот дифф ломает — а
    // вместе с ним и единственное дешёвое доказательство, что перенос ничего не сдвинул.
    // Уточнения (скидка γ на шаг сближения, стрелковый радиус 4, измеренная доля касаний touchShare,
    // множитель входящего ourTaken, гейт стены на вражеский вклад) вводит ЭТАП 6 вместе с воротами: там
    // они меняют поведение открыто, и там их цена меряется серией. Гейт стены стоит отдельного замера:
    // модель командира — ЕДИНСТВЕННАЯ в боте, красящая опасность СКВОЗЬ стену (damageAt и dangerCostMatrix
    // её режут), и это кандидат в механизм жалобы «армия распалась на две половины».

    /** Клеток в поле: 100x100, индекс x*100+y. */
    private const val FIELD_CELLS = 10_000

    /** Фиксированная точка: поля целочисленные, ×16. Урон за тик у нас 30–300, ×16 не переполняет Int
     *  даже суммой по всем крипам, а деление на 16 — сдвиг. */
    private const val FP = 16

    // Радиусы штампов. Первые два ОБЯЗАНЫ совпадать с MELEE_KEEP_RANGE и RANGED_RANGE бота — на этом
    // держится точность переноса; менять их можно только вместе с проверкой fldcmp.
    private const val R_MELEE = 2
    private const val R_RANGED = 3
    private const val R_HEAL = 4
    private const val R_FIRE = 3

    /** Доля эффективности rangedHeal против heal вплотную (4/12). */
    private val HEAL_FALLOFF = RANGED_HEAL_POWER.toDouble() / HEAL_POWER

    /** Скидка на действие, требующее шага: оно придёт следующим тиком, а не этим. */
    private const val GAMMA = 0.7

    // Ядра: индекс = расстояние Чебышева от источника до клетки.
    /** Опасность мили: он бьёт вплотную и шагнёт-ударит с двойки. Плоское ядро — это сегодняшний incNext;
     *  внешнее кольцо со скидкой GAMMA вводит этап 6 (см. предупреждение выше). */
    private val K_MELEE = doubleArrayOf(1.0, 1.0, 1.0)
    /** Опасность стрелка: полный урон по одиночной цели на всей дистанции 1..3 (falloff — у массовой атаки). */
    private val K_RANGED = doubleArrayOf(1.0, 1.0, 1.0, 1.0)
    /** Лечение с шагом: healRate(max(0, d-1)) — вплотную и с двойки полное, с тройки и четвёрки rangedHeal. */
    private val K_HEAL = doubleArrayOf(1.0, 1.0, 1.0, HEAL_FALLOFF, HEAL_FALLOFF)
    /** Огонь ЭТИМ тиком, без шага: мили только вплотную. */
    private val K_FIRE_MELEE = doubleArrayOf(1.0, 1.0)
    private val K_FIRE_RANGED = doubleArrayOf(1.0, 1.0, 1.0, 1.0)
    /** Притяжение мили: «бью сейчас / через шаг / через два». Дальше трёх мили не тянет — до цели он не дойдёт
     *  раньше, чем цель уйдёт. */
    private val K_ATT_MELEE = doubleArrayOf(1.0, 1.0, GAMMA, GAMMA * GAMMA)
    /**
     * Притяжение стрелка: ПИК НА ДАЛЬНОСТИ 3, чтобы стрелок останавливался сам, а не влезал в мили-радиус
     * (Hagelbäck & Johansson: пик потенциала ставится на МАКСИМАЛЬНОЙ дальности оружия). Наклон внутри
     * пологий НАМЕРЕННО — десять процентов: он лишь разбивает ничьи среди клеток, которые уже отранжировала
     * опасность, и не должен перебивать её. Не «чинить» крутизну: круче — значит вернуть подгонянный вес.
     */
    private val K_ATT_RANGED = doubleArrayOf(0.90, 0.90, 0.95, 1.00, GAMMA, GAMMA * GAMMA)
    /** Притяжение лекаря: он сам стоит в клетке, поэтому шага нет — healRate(d), плюс кольцо со скидкой. */
    private val K_ATT_HEAL = doubleArrayOf(1.0, 1.0, HEAL_FALLOFF, HEAL_FALLOFF, GAMMA * HEAL_FALLOFF)

    /** Вес лечащей части врага в цене цели: минимум, при котором поле вообще выбирает лекаря целью.
     *  Его лечащие части стоят ПЕРВЫМИ в теле, один сосредоточенный залп снимает три части = 36 лечения
     *  навсегда, а замер говорит, что его лекари сохраняют 100 % частей против наших 8 %. */
    private const val HEAL_VALUE = 1.5

    /** Нижняя граница pressure: даже наглухо перелеченная цель не бесполезна — иначе армия бросит блок целиком. */
    private const val PRESSURE_MIN = 0.25

    /** Вес безоружного (уже разоружённого) своего в поле нужды: лечить его стоит, но не вперёд вооружённого. */
    private const val NEED_DISARMED = 0.35

    val eMelee = IntArray(FIELD_CELLS)
    val eRanged = IntArray(FIELD_CELLS)
    val eHeal = IntArray(FIELD_CELLS)
    val aMelee = IntArray(FIELD_CELLS)
    val aRanged = IntArray(FIELD_CELLS)
    val aHeal = IntArray(FIELD_CELLS)
    val eFire = IntArray(FIELD_CELLS)
    val attMelee = IntArray(FIELD_CELLS)
    val attRanged = IntArray(FIELD_CELLS)
    val attHeal = IntArray(FIELD_CELLS)
    val claim = IntArray(FIELD_CELLS)

    /** Его СТОЯЩИЕ стрелки (v439, см. USE_HEAL_EXACT_IN_FIRE): огонь тех, кто за APPROACH_WINDOW не сближается быстрее
     *  RANGED_ADVANCE_RATE, — угроза, равная себе и в следующий тик; только у подопечного под таким огнём клетка лекаря
     *  стоит ровно доставляемое сейчас. Радиус K_RANGED, вес — части. */
    val eRangedStill = IntArray(FIELD_CELLS)
    var wardsUnderRanged = 0; var wardsUnderStill = 0     // прибор hstill=: подопечных под его стрелками / из них под стоящими
    private val allFields = arrayOf(eMelee, eRanged, eHeal, aMelee, aRanged, aHeal, eFire, eRangedStill,
        attMelee, attRanged, attHeal, claim)

    /** Наступает ли его крип: темп сближения с нашим домом по истории enemyArrivalTicks (см. Memory.approachHistory) не
     *  ниже RANGED_ADVANCE_RATE клетки в тик за окно; короткая история — не наступает (первые тики — не бой). */
    private fun advancing(e: Creep): Boolean {
        val h = Memory.approachHistory[e.id] ?: return false
        if (h.size < 2) return false
        val (t0, a0) = h.first()
        val (t1, a1) = h.last()
        if (t1 - t0 < APPROACH_WINDOW / 2) return false
        return (a0 - a1).toDouble() / (t1 - t0) >= RANGED_ADVANCE_RATE
    }

    /** Тик, на котором поля построены: чтение с другого тика — баг, и он должен быть виден, а не тих. */
    private var fieldTick = -1

    fun fieldsBuiltAt(): Int = fieldTick

    /** Жив ли у врага хоть один лечащий крип: пока да, любая выбитая его часть возвращается. */
    private var enemyCanHeal = false

    /** Множитель ВХОДЯЩЕГО урона по ЕГО крипам — зеркало ourTaken, нужен нашим полям урона. */
    private var theirTaken = 1.0

    fun setTheirTaken(v: Double) {
        theirTaken = v
    }

    /** Опасность клетки (его урон в тик по нашему крипу здесь) — E = eMelee + eRanged. */
    fun dangerAt(key: Int): Double = (eMelee[key] + eRanged[key]).toDouble() / FP

    /** Наш урон в тик по его крипу в этой клетке — A. */
    fun ourBurstAt(key: Int): Double = (aMelee[key] + aRanged[key]).toDouble() / FP

    /** Влияние: наши минус его. Больше нуля — здесь мы сильнее (Dave Mark, Game AI Pro 2 гл. 30). */
    fun influenceOf(key: Int): Double = ourBurstAt(key) - dangerAt(key)

    /** Уязвимость = 2·min(наши, его): пик там, где силы равны, то есть ЛИНИЯ ФРОНТА. */
    fun vulnerabilityOf(key: Int): Double = 2.0 * minOf(ourBurstAt(key), dangerAt(key))

    private fun stamp(field: IntArray, cx: Int, cy: Int, kernel: DoubleArray, weight: Double, wallGate: Boolean) {
        if (weight == 0.0) return   // вес может быть ОТРИЦАТЕЛЬНЫМ: насыщение снимает покрытую нужду
        val radius = kernel.size - 1
        val x0 = maxOf(0, cx - radius)
        val x1 = minOf(FIELD_MAX, cx + radius)
        val y0 = maxOf(0, cy - radius)
        val y1 = minOf(FIELD_MAX, cy + radius)
        for (x in x0..x1) {
            val ax = abs(x - cx)
            val base = x * 100
            for (y in y0..y1) {
                val d = maxOf(ax, abs(y - cy))
                val k = kernel[d]
                if (k <= 0.0) continue
                if (wallGate && wallBetween(cx, cy, x, y)) continue
                field[base + y] += (weight * k * FP).roundToInt()
            }
        }
    }

    /**
     * Строит все поля за один проход по крипам. Порядок обязателен: сначала базовые (урон и лечение
     * обеих сторон), потом производные — цена убийства врага читает НАШЕ поле урона в ЕГО клетке,
     * а нужда своего читает ЕГО поле урона в клетке своего.
     */
    fun buildFields(allies: List<Creep>, enemies: List<Creep>) {
        fieldTick = getTicks()
        targetValueCache.clear()
        enemyCanHeal = enemies.any { profileOf(it).heal > 0.0 }
        for (f in allFields) f.fill(0)

        for (e in enemies) {
            val p = profileOf(e)
            stamp(eMelee, e.x, e.y, K_MELEE, p.melee, false)
            stamp(eRanged, e.x, e.y, K_RANGED, p.ranged, false)
            stamp(eHeal, e.x, e.y, K_HEAL, p.heal, false)
            stamp(eFire, e.x, e.y, K_FIRE_MELEE, p.melee, false)
            stamp(eFire, e.x, e.y, K_FIRE_RANGED, p.ranged, false)
            if (p.ranged > 0.0 && !advancing(e)) stamp(eRangedStill, e.x, e.y, K_RANGED, p.ranged, false)
        }
        for (a in allies) {
            val p = profileOf(a)
            // наш урон — с ЕГО множителем входящего (его флаги D×...): это урон, который получит он
            stamp(aMelee, a.x, a.y, K_MELEE, p.melee * theirTaken, false)
            stamp(aRanged, a.x, a.y, K_RANGED, p.ranged * theirTaken, false)
            stamp(aHeal, a.x, a.y, K_HEAL, p.heal, false)
        }

        // ЦЕНА УБИЙСТВА (оператор: «сосредоточиться на самых опасных частях, выбить их и перейти к
        // следующему очагу»). Ценность врага — его боевые части, УМНОЖЕННЫЕ на нашу способность их
        // выбить: хорошо прикрытый лечением враг — цель дешёвая, и армия сама сползает к неприкрытому
        // краю блока. Отдельного правила «бей того, кого не лечат» для этого не нужно.
        // ЦЕНА УБИЙСТВА ОТНОСИТЕЛЬНАЯ, А НЕ АБСОЛЮТНАЯ (v208, замер серии v206). Первая редакция умножала ценность
        // цели на pressure = наш залп / (залп + его лечение), и это оказалось САМОЗАПИРАЮЩИМ: пока никто из наших
        // не в досягаемости врага, залп равен нулю, pressure падает на пол 0.25, притяжение вчетверо слабее — а
        // опасность полная, и никто не сближается. Живой замер v206: стрелков в дальности 0.223 против 0.325 у
        // v205, план `guns` 0.635 против 0.731. Это ровно тот класс отказа, что уже дважды записан в доках: модель
        // читает нас слабее врага, и всякий гейт защёлкивается.
        // Верная форма — сравнение ЦЕЛЕЙ МЕЖДУ СОБОЙ: pressure делится на лучшую в поле, поэтому самая доступная
        // цель всегда стоит полной цены, а перелеченная — дешевле неё. Операторское «сосредоточиться на самых
        // опасных частях и переходить к следующему очагу» — это выбор СРЕДИ целей, а не решение, драться ли вообще.
        var bestPressure = 0.0
        for (e in enemies) {
            val key = e.x * 100 + e.y
            val burst = ourBurstAt(key)
            val q = burst / (burst + eHeal[key].toDouble() / FP + 1.0)
            if (q > bestPressure) bestPressure = q
        }
        lastBestPressure = bestPressure
        for (e in enemies) {
            val p = profileOf(e)
            val value = p.melee + p.ranged + HEAL_VALUE * p.heal
            if (value <= 0.0) continue
            val key = e.x * 100 + e.y
            val burst = ourBurstAt(key)
            val raw = burst / (burst + eHeal[key].toDouble() / FP + 1.0)
            val pressure = if (bestPressure <= 0.0) 1.0 else (raw / bestPressure).coerceIn(PRESSURE_MIN, 1.0)
            val w = value * pressure
            // мили тянет только туда, КУДА ОН ДОЙДЁТ: притяжение сквозь стену увело бы его в стену.
            // Стрелковое притяжение стены не знает намеренно — выстрелы в игре стены не блокируют.
            stamp(attMelee, e.x, e.y, K_ATT_MELEE, w, true)
            stamp(attRanged, e.x, e.y, K_ATT_RANGED, w, false)
        }

        // ПОЛЕ НУЖДЫ ЛЕКАРЕЙ: нужда — это ОПАСНОСТЬ, прочитанная в клетке подопечного, а не его нынешняя
        // рана. Полный мили, которому сейчас прилетит 560, важнее полураненого в чистом поле. Сегодняшнее
        // правило берёт раненых, то есть по определению тех, кто уже на фронте, и тянет лекарей вперёд —
        // это ровно механизм жалобы оператора «хилеры выбегают вперёд под прямой урон».
        stampHealNeed(allies)
    }

    /** Непокрытая нужда каждого своего по id — уменьшается по мере назначения лекарей (см. saturateHeal). */
    private val needLeft = HashMap<String, Double>()

    /**
     * Поле нужды строится заново перед каждой раздачей: она идёт ПЯТЬ раз за тик, по разу на замысел, и
     * насыщение одного замысла не должно просачиваться в следующий — ровно та же причина, что у clearClaim.
     */
    fun stampHealNeed(allies: List<Creep>) {
        attHeal.fill(0)
        needLeft.clear()
        inFire.clear()
        var total = 0.0
        for (a in allies) {
            val key = a.x * 100 + a.y
            // ПОДОПЕЧНЫЙ В РЕЖИМЕ — ТОТ, КОГО ДОСТАЁТ ЕГО СТОЯЩИЙ СТРЕЛОК (сужение v439 по гейту match20:brawl+heals 14 632 :
            // 15 192, затем 5 103 : 5 737): цена «ровно этот тик» верна против угрозы, равной себе и в следующий тик, — стрелок,
            // держащий три; мили ходит клетку в тик и бьёт лекаря первым на 240, наступающий стрелок через тик стреляет с трёх
            // туда, где сейчас безопасно. Боец под таким огнём лечится по-старому: с look-ahead шагового ядра и клеем
            if (eRanged[key] > 0) wardsUnderRanged++
            if (eRangedStill[key] > 0) wardsUnderStill++
            // при выключенном точном ядре (v440) зона огня — как у v438: его мили вплотную или стрелок в ≤ 3 (eFire)
            if (if (USE_HEAL_EXACT_IN_FIRE) eRangedStill[key] > 0 else eFire[key] > 0) inFire.add(a.id)
            val armed = a.body.any { it.hits > 0 && (it.type == ATTACK || it.type == RANGED_ATTACK) }
            // НУЖДА — ТО, ЧТО ЛЕЧЕНИЕ МОЖЕТ ВЕРНУТЬ (v435, см. USE_HEAL_NEED_ACTUAL): недобор хитов плюс потеря за прошлый
            // тик; целый крип во втором ряду нужды не имеет, как бы ни было опасно его поле
            val base = if (USE_HEAL_NEED_ACTUAL) {
                val lost = ((Memory.lastHits[a.id] ?: a.hits) - a.hits).coerceAtLeast(0)
                minOf((a.hitsMax - a.hits + lost).toDouble(), a.hitsMax.toDouble())
            } else minOf(dangerAt(key), a.hits.toDouble())
            val need = base * (if (armed) 1.0 else NEED_DISARMED)
            needLeft[a.id] = need
            total += need
            stamp(attHeal, a.x, a.y, K_ATT_HEAL, need, false)
        }
        totalNeed = total
    }

    /**
     * НАСЫЩЕНИЕ (этап 7): назначенный лекарь снимает с подопечных ту нужду, которую покроет собой, — иначе трое
     * лекарей встанут на одного раненого, а второй очаг останется без помощи вовсе. Снимается ровно покрытое:
     * подопечный, чья нужда больше одного лекаря, продолжает тянуть второго.
     */

    fun saturateHeal(healer: Creep, x: Int, y: Int, allies: List<Creep>) {
        val h = profileOf(healer).heal
        if (h <= 0.0) return
        for (a in allies) {
            if (a.id == healer.id) continue
            val d = maxOf(abs(a.x - x), abs(a.y - y))
            if (d >= K_ATT_HEAL.size) continue
            val left = needLeft[a.id] ?: continue
            if (left <= 0.0) continue
            val covered = minOf(left, h * K_ATT_HEAL[d])
            if (covered <= 0.0) continue
            needLeft[a.id] = left - covered
            stamp(attHeal, a.x, a.y, K_ATT_HEAL, -covered, false)
        }
    }

    /** ЛУЧШАЯ ОДНА ДОСТАВКА ИЗ КЛЕТКИ (v435, см. USE_HEAL_NEED_ACTUAL): лекарь лечит одного за тик, поэтому цена клетки —
     *  не сумма нужд вокруг, а наибольшее из min(непокрытая нужда подопечного, лечение × ядро по дистанции). Вплотную к
     *  теряющему хиты — полное лечение (72 у h6), в двух-трёх клетках — треть, у целого — ноль. Себя лекарь тоже считает. */
    fun bestDeliveryAt(healer: Creep, x: Int, y: Int, allies: List<Creep>): Double {
        val h = profileOf(healer).heal
        if (h <= 0.0) return 0.0
        val fireMode = deliveryFireMode(healer, allies)
        // У ПОДОПЕЧНОГО ПОД ОГНЁМ КЛЕТКА СТОИТ ТО, ЧТО ДОСТАВЛЯЕТСЯ В ЭТОТ ТИК (v439, см. USE_HEAL_EXACT_IN_FIRE): шаговое ядро
        // оценивало клетку в двух в γ·72 = 50 при настоящих 24, и последний шаг к бойцу под огнём стоил в цене 22 при 48 в
        // хитах — лекарь лечил того же бойца с двух клеток. Скидка «вылечу со следующего тика» верна для отложимого лечения
        // и ложна для скоропортящегося; вне огня она остаётся (иначе поле — ноль вдали от боя, match29:camp)
        val k = if (USE_HEAL_STEP_KERNEL && !(USE_HEAL_EXACT_IN_FIRE && fireMode)) K_HEAL_STEP else K_ATT_HEAL
        // ...И ТОЛЬКО ИЗ КЛЕТКИ ВНЕ ЕГО ОГНЯ (сужение v438 по гейту match4:kite 8 802 : 14 502 — hparts 18 → 0 к t=110 при его
        // ehparts 0/18, hfire=127/185/36): его стрелок в досягаемости лекаря бьёт ЛЕКАРЯ, а не тела перед ним (модель
        // wallTargetOf, 85–91 % совпадений; стуб-кайтер — наименьшие хиты, лекарь 1 200 против 1 600 у мили), поэтому экран
        // тел лекарю не скидка, и 72 доставки из клетки под двумя стволами покупали клетку, где он раздевался за пять тиков.
        // Клетка вплотную к подопечному под огнём, но вне огня сама (сзади него) есть в 63 % таких тиков (разбор v437) — она
        // и есть предмет правки; из клетки под огнём доставки нет, там действуют влияние и опасность, как у v437
        // ...И НЕ ИЗ КЛЕТКИ, КУДА ЕГО МИЛИ ДОСТАЁТ ШАГОМ (сужение v439 по гейту match20:brawl+heals 14 632 : 15 192): цена «ровно
        // этот тик» верна, пока угроза следующего тика равна нынешней — стрелок держит три и стоит, мили ходит клетку в тик и
        // бьёт лекаря первым на 240; клетка в двух от него вне огня сейчас и под топором через тик. Поле eMelee (K_MELEE,
        // радиус 2 — «шаг + удар») и есть эта досягаемость
        if (fireMode && (eFire[x * 100 + y] > 0 || (USE_HEAL_EXACT_IN_FIRE && eMelee[x * 100 + y] > 0))) return 0.0
        var best = 0.0
        for (a in allies) {
            if (fireMode && (a.id !in inFire || a.id in advancingWards)) continue
            val d = maxOf(abs(a.x - x), abs(a.y - y))
            if (d >= k.size) continue
            val left = needLeft[a.id] ?: continue
            if (left <= 0.0) continue
            val v = minOf(left, h * k[d])
            if (v > best) best = v
        }
        return best
    }
    /** Свои в зоне его огня этим тиком (v438, см. USE_HEAL_DELIVERY_IN_FIRE): поле eFire в клетке подопечного больше нуля —
     *  его мили вплотную или стрелок в ≤ 3, без шага сближения. Заполняется вместе с нуждой. */
    private val inFire = HashSet<String>()

    /** РЕЖИМ «В ЗОНЕ ОГНЯ» (v438, см. USE_HEAL_DELIVERY_IN_FIRE): у лекаря в досягаемости шага (d ≤ размер ядра) есть
     *  подопечный под его огнём с непокрытой нуждой — тогда доставка в цене клетки считается по одним таким: лечение ему
     *  скоропортящееся, остальным отложимо. Решается на лекаря за тик, а не на клетку — иначе соседние клетки считались бы
     *  в разных режимах, и лекарь уходил бы от огня к большему числу. Насыщение снимает нужду — покрытый подопечный режим
     *  не держит, и следующий лекарь считает по-старому. */
    /** Бойцы, идущие ВПЕРЁД этим тиком (v439, заполняет commandFight перед проходом лекарей): их назначенная клетка — или,
     *  когда план бойцов не наш (раздача одних лекарей), ход прошлого тика — ближе к его стволам, чем нынешняя. Такой боец в
     *  режим точной цены не входит: лекарь, приклеенный к нему вплотную, идёт за ним в огонь (match20:brawl+heals — к t=100
     *  все три лекаря внутри его группы стрелков); у него клетка ценится по-старому, с look-ahead шагового ядра. */
    val advancingWards = HashSet<String>()

    fun deliveryFireMode(healer: Creep, allies: List<Creep>): Boolean {
        if (!USE_HEAL_DELIVERY_IN_FIRE) return false
        val reach = (if (USE_HEAL_STEP_KERNEL) K_HEAL_STEP else K_ATT_HEAL).size
        for (a in allies) {
            // сам лекарь режим не включает (сужение v438 по гейту match33:camp 18 354 : 23 667): доставка себе одинакова
            // из любой клетки (d = 0), режим же — про перестановку к ДРУГОМУ; единственный раненый в танце перед боем
            // был лекарь с −52, режим гасил ему клей, и танец разошёлся в отряжение семерых бегунами и отход
            if (a.id == healer.id || a.id !in inFire || a.id in advancingWards) continue
            if ((needLeft[a.id] ?: 0.0) <= 0.0) continue
            if (maxOf(abs(a.x - healer.x), abs(a.y - healer.y)) <= reach) return true
        }
        return false
    }

    /** ШАГОВОЕ ЯДРО ЦЕНЫ ДОСТАВКИ (v437, см. USE_HEAL_STEP_KERNEL): клетка стоит лучшее из «лечу отсюда сейчас»
     *  (12/часть на ≤ 1, 4/часть на ≤ 3) и «шагну и вылечу со следующего тика» со скидкой GAMMA — той же, что у ядер
     *  мили и стрелка. Ядро healRate плоское на 2…3 (обе клетки — треть), и из трёх в два лекарь не шёл: платил
     *  только последний шаг. Насыщение (saturateHeal) считает по настоящему healRate — там нужна доставка, а не цена. */
    private val K_HEAL_STEP = doubleArrayOf(1.0, 1.0, maxOf(HEAL_FALLOFF, GAMMA), maxOf(HEAL_FALLOFF, GAMMA * HEAL_FALLOFF), GAMMA * HEAL_FALLOFF)

    /** Доля нужды, покрытая назначенными лекарями: 0 — никто никого не прикрывает, 1 — покрыты все. */
    fun healCoverage(): Pair<Double, Double> {
        var left = 0.0
        for (v in needLeft.values) left += v
        return left to totalNeed
    }

    private var totalNeed = 0.0

    /** Занятость клеток уже розданными приказами: раздача идёт пять раз за тик, по разу на замысел, и
     *  притязания одного замысла не должны просачиваться в следующий (Hagelbäck: временное отталкивание
     *  в выбранной клетке — то, что не даёт крипам слипаться в одну точку). */
    fun clearClaim() {
        claim.fill(0)
    }

    /** Ставит притязание на клетку и её соседей: следующий крип видит её как занятую. */
    fun addClaim(x: Int, y: Int) {
        for (dx in sym(1)) for (dy in sym(1)) {
            val nx = x + dx
            val ny = y + dy
            if (nx < 0 || ny < 0 || nx > FIELD_MAX || ny > FIELD_MAX) continue
            claim[nx * 100 + ny]++
        }
    }

    /** Цена цели — боевые части, умноженные на нашу способность их выбить (см. buildFields). */
    /** Цена цели за тик: поля строятся раз в тик, значит и она постоянна — а зовут её из внутреннего цикла
     *  по девяти клеткам на каждый замысел, и там она тянула за собой profileOf с обходом тела через границу
     *  изоляции. Кэш живёт ровно один тик и сбрасывается вместе с полями. */
    private val targetValueCache = HashMap<String, Double>()

    fun targetValue(e: Creep): Double {
        targetValueCache[e.id]?.let { return it }
        val v = targetValueOf(e)
        targetValueCache[e.id] = v
        return v
    }

    private fun targetValueOf(e: Creep): Double {
        // ПРИТЯЖЕНИЕ ПО ПОТЕНЦИАЛУ ТЕЛА (v209) — ОТВЕРГНУТО ЗАМЕРОМ. Замысел был такой: пока у врага жив лекарь,
        // выбитая часть не убрана, а выключена, поэтому цена цели считается по ВСЕМ боевым частям тела, живым и
        // мёртвым, — и остов перестаёт быть невидимым для ног. Свой прибор правка не сдвинула НИГДЕ: на стенде
        // восстановлений 137 -> 130 при 135 сценариях (шум), живьём 2,6 -> 3,0 на матч при пяти матчах.
        // Причина названа замером, который и надо было сделать первым: когда остов у нас в дальности, мы по нему
        // УЖЕ СТРЕЛЯЕМ. Из 209 стрелко-тиков с остовом в дальности 65 % это выстрел (живой цели рядом нет), 25 % —
        // правильный выстрел в живую цель, и лишь 9 % пустые. Огонь не виноват; остовы просто ВЫХОДЯТ из дальности,
        // и операторское «спокойно прошли мимо нас» надо читать буквально. Тянуть за ними всю армию — не ответ:
        // очаг у армии один, он держится гистерезисом на главном блоке, и уходящий остов им не станет.
        // Живой ответ — в focusPool: остов, умирающий от одного уже доступного залпа (см. USE_FINISH_HULKS).
        val p = profileOf(e)
        val value = p.melee + p.ranged + HEAL_VALUE * p.heal
        if (value <= 0.0) return 0.0
        val key = e.x * 100 + e.y
        val burst = ourBurstAt(key)
        val raw = burst / (burst + eHeal[key].toDouble() / FP + 1.0)
        val pressure = if (lastBestPressure <= 0.0) 1.0 else (raw / lastBestPressure).coerceIn(PRESSURE_MIN, 1.0)
        return value * pressure
    }

    /** Лучшая доступность цели в поле — знаменатель относительной цены убийства (см. buildFields). */
    private var lastBestPressure = 0.0

    /** Лечение крипа за тик — потолок того, что он может доставить, стоя где угодно. */
    fun healOf(c: Creep): Double = profileOf(c).heal

    /** Притяжение ОДНОЙ цели в клетку — для замысла концентрации, где поле по всем целям не годится. */
    fun attractionTo(e: Creep, x: Int, y: Int, melee: Boolean): Double {
        val k = if (melee) K_ATT_MELEE else K_ATT_RANGED
        val d = maxOf(abs(e.x - x), abs(e.y - y))
        if (d >= k.size) return 0.0
        if (melee && wallBetween(e.x, e.y, x, y)) return 0.0
        return targetValue(e) * k[d]
    }

    /** Лечение, которое ЭТОТ крип доставляет в клетку со своего нынешнего места: вычитается из поля лечения,
     *  когда считается, переживёт ли он сам эту клетку — на себя, уходя, он рассчитывать не вправе. */
    fun healFromSelf(c: Creep, x: Int, y: Int): Double {
        val h = profileOf(c).heal
        if (h <= 0.0) return 0.0
        val d = maxOf(abs(c.x - x), abs(c.y - y))
        return if (d < K_HEAL.size) h * K_HEAL[d] else 0.0
    }

    /** Наше лечение, доходящее в клетку (поле aHeal). */
    fun healReachAt(key: Int): Double = aHeal[key].toDouble() / FP

    /** Его огонь по клетке ЭТИМ тиком, без шага сближения (поле eFire). */
    fun fireFieldAt(key: Int): Double = eFire[key].toDouble() / FP

    /** Притяжение по роли: мили — к тому, что он достанет ногами; стрелок — с пиком на дальности 3. */
    fun attMeleeAt(key: Int): Double = attMelee[key].toDouble() / FP

    fun attRangedAt(key: Int): Double = attRanged[key].toDouble() / FP

    /** Нужда своих в лечении, покрываемая из клетки. */
    fun attHealAt(key: Int): Double = attHeal[key].toDouble() / FP

    fun claimAt(key: Int): Double = claim[key].toDouble()

    /** Максимум поля — «поле живое»: обнулившееся поле обязано быть видно, а не тихо давать нули. */
    fun fieldPeak(field: IntArray): Double {
        var best = 0
        for (v in field) if (v > best) best = v
        return best.toDouble() / FP
    }

    /**
     * CostMatrix, где опасные (под вражеским огнём) клетки дороги для прохода,
     * а непроходимые (`blocked`: стены, чужие рампарты) — заблокированы (255).
     * Передаётся в searchPath, чтобы путь обходил красные зоны и препятствия.
     * Стоимости опасности накапливаются от перекрывающихся врагов.
     */
    fun dangerCostMatrix(enemies: List<Creep>, blocked: List<Position>): CostMatrix {
        val matrix = CostMatrix()
        for (enemy in enemies) {
            val profile = profileOf(enemy)
            if (profile.melee + profile.ranged + profile.heal <= 0.0) continue
            for (dx in sym(RANGED_RADIUS)) {
                for (dy in sym(RANGED_RADIUS)) {
                    val x = enemy.x + dx
                    val y = enemy.y + dy
                    if (x < 0 || y < 0 || x > FIELD_MAX || y > FIELD_MAX) continue
                    if (wallBetween(enemy.x, enemy.y, x, y)) continue // опасность сквозь стену не красим
                    // дистанция — с РЕАЛЬНЫМ шагом сближения (по проходимости врага): опасность
                    // не «телепортируется» за препятствие, до которого врагу идти долгим обходом
                    val effective = effectiveRangeTo(enemy, x, y)
                    val danger = profile.melee * meleeRate(effective) +
                        profile.ranged * (if (effective <= 3) 1.0 else 0.0) +
                        profile.heal * healRate(effective)
                    if (danger <= 0.0) continue
                    val cost = min(MAX_COST.toDouble(), matrix.get(x, y) + danger * COST_SCALE).toInt()
                    matrix.set(x, y, cost)
                }
            }
        }
        // кормленная башня красит опасность на всю дальность выстрела (средний урон за тик)
        for (t in enemyTowers) {
            if (!t.fed) continue
            val radius = towerFalloffRange.toInt()
            for (dx in -radius..radius) {
                for (dy in -radius..radius) {
                    val x = t.x + dx
                    val y = t.y + dy
                    if (x < 0 || y < 0 || x > FIELD_MAX || y > FIELD_MAX) continue
                    val danger = towerShot(maxOf(abs(dx), abs(dy))) / towerCooldown
                    if (danger <= 0.0) continue
                    val cost = min(MAX_COST.toDouble(), matrix.get(x, y) + danger * COST_SCALE).toInt()
                    matrix.set(x, y, cost)
                }
            }
        }
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
     * Обновляет стойку крипа с гистерезисом: переключение только при выходе за пороги,
     * между порогами стойка сохраняется — крип не «дрожит» на границе.
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
     * Подсветка баланса сил вокруг наших бойцов: зелёный — мы сильнее, красный — враг.
     * Рисуем только окрестности rangers, чтобы не превысить лимит визуалов.
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
                    if (!drawn.add(x * 100 + y)) continue // клетка уже нарисована другим бойцом

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

    /** Цвета подсветки входов во вражескую базу (по входу на цвет, циклически). */
    private val ENTRANCE_COLORS = arrayOf("#00ffff", "#ff00ff", "#ffff00", "#00ff88", "#ff8800")

    private var entrancesDrawn = false

    /** Подсвечивает найденные входы во вражескую базу: каждая компонента своим цветом + номер
     *  в центре. Рисуется один раз за игру на persistent-слое. */
    fun drawEntrances(entrances: List<IntArray>) {
        if (entrancesDrawn) return
        entrancesDrawn = true
        val visual = Visual(layer = 2, persistent = true)
        for ((idx, component) in entrances.withIndex()) {
            val color = ENTRANCE_COLORS[idx % ENTRANCE_COLORS.size]
            for (packed in component) {
                visual.rect(corner(packed / 100, packed % 100), 1, 1, RectVisualStyle(fill = color, opacity = 0.5))
            }
            val cx = component.sumOf { it / 100 } / component.size
            val cy = component.sumOf { it % 100 } / component.size
            visual.text("${idx + 1}", cell(cx, cy), TextVisualStyle(color = "#ffffff", font = "0.8"))
        }
    }

    /**
     * Подсветка входящего урона вокруг наших бойцов: оранжевая клетка тем насыщеннее,
     * чем больше HP прилетит, плюс число урона. Отдельный слой — поверх матрицы влияния.
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
}
