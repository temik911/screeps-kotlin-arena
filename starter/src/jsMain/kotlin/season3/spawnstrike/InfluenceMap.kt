package season3.spawnstrike

import kotlinx.js.JsPlainObject
import screeps.api.ATTACK
import screeps.api.ATTACK_POWER
import screeps.api.CostMatrix
import screeps.api.Creep
import screeps.api.HEAL
import screeps.api.HEAL_POWER
import screeps.api.Position
import screeps.api.RANGED_ATTACK
import screeps.api.RANGED_ATTACK_POWER
import screeps.api.RANGED_HEAL_POWER
import screeps.api.RectVisualStyle
import screeps.api.TERRAIN_WALL
import screeps.api.TextVisualStyle
import screeps.api.Visual
import screeps.api.getRange
import screeps.api.getTerrainAt
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
        return CombatProfile(
            melee = (attack * ATTACK_POWER).toDouble(),
            ranged = (ranged * RANGED_ATTACK_POWER).toDouble(),
            heal = (heal * HEAL_POWER).toDouble(),
        )
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
     * этим пользуется и стен не проверяет. НЕ применять к нашим вкладам и НЕ удалять из вражеских.
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
            if (wallBetween(enemy.x, enemy.y, x, y)) continue // вражеский урон — только по достижимости
            // шаг сближения — по реальной проходимости: за стену враг движением не «достаёт»
            val effective = effectiveRangeTo(enemy, x, y)
            val profile = profileOf(enemy)
            if (effective <= 1) damage += profile.melee // ATTACK достаёт в упор
            if (effective <= 3) damage += profile.ranged // RANGED_ATTACK — полный урон по одиночной цели
        }
        return damage
    }

    /**
     * Сколько HP наши лекари могут восстановить крипу на клетке за тик: heal в упор (12/часть),
     * rangedHeal на дистанции 2..3 (4/часть). Используется, чтобы из входящего урона вычесть лечение.
     */
    fun healAt(x: Int, y: Int, allies: List<Creep>): Double {
        var heal = 0.0
        for (ally in allies) {
            val healParts = ally.body.count { it.type == HEAL && it.hits > 0 }
            if (healParts == 0) continue
            val distance = getRange(ally, cell(x, y)) // лечение, как и стрельба, работает сквозь стены
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
            for (dx in -RANGED_RADIUS..RANGED_RADIUS) {
                for (dy in -RANGED_RADIUS..RANGED_RADIUS) {
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
