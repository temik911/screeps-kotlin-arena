package season4.escortrun

import screeps.api.Creep
import screeps.api.Position
import screeps.api.getDirection

/**
 * Двухфазное движение крипов. Сначала каждый боец регистрирует желаемую клетку (request),
 * затем resolve() разом разрешает все намерения: двигает по цепочке, толкает и меняет местами
 * своих, выбирая согласованное состояние без взаимных «дёрганий».
 *
 * Алгоритм — рекурсивный DFS: чтобы занять клетку блокера, мы пытаемся подвинуть самого блокера;
 * при этом блокеру разрешено шагнуть в клетку, которую освобождает вызывающий — это естественно
 * даёт и обмен местами (swap из 2 крипов), и проталкивание цепочкой. Движок (World, Арена тот же
 * код: processor/intents/movement.js) обмен и цепочки разрешает: клетка считается занятой только
 * крипом, который сам в этот тик не ходит; пара «лоб в лоб» получает высший рейт.
 *
 * Аудит: каждый выданный move помечен видом (свободная клетка / цепочка / обмен), на следующем
 * тике проверяется по положению крипа. Счётчики печатает бот. Застревание (тот же запрос без
 * движения STUCK_TICKS тиков подряд) считается на крипа — бот по нему обходит блокера.
 */
object TrafficManager {

    const val KIND_FREE = 0
    const val KIND_CHAIN = 1
    const val KIND_SWAP = 2

    /** Сколько тиков подряд крип просит шаг и не двигается, чтобы считаться застрявшим:
     *  больше цикла усталости гружёного хаулера на болоте (5 тиков), иначе ложные срабатывания. */
    const val STUCK_TICKS = 8

    /** moverId -> упакованная желаемая клетка. */
    private val desired = HashMap<String, Int>()

    /** moverId -> приоритет (выше — обрабатывается раньше и может проталкивать менее приоритетных). */
    private val priorityOf = HashMap<String, Int>()

    /** Кто просил шаг на прошлом тике (и куда): для детектора застревания и признака «стоит». */
    private val lastDesired = HashMap<String, Int>()

    /** id -> сколько тиков подряд просит ту же клетку, не сдвинувшись. */
    private val stuckTicks = HashMap<String, Int>()
    private val lastPos = HashMap<String, Int>()

    private class Issued(val coord: Int, val kind: Int)
    private val issued = HashMap<String, Issued>()
    private val okCount = IntArray(3)
    private val failCount = IntArray(3)

    private fun pack(x: Int, y: Int) = x * 100 + y

    /** Зарегистрировать желание бойца шагнуть на клетку target (соседнюю). priority — кто кого толкает.
     *  Уставший крип (fatigue > 0) в этот тик не сдвинется — его желание игнорируем, иначе цепочки
     *  resolve посчитают, что он «освободит клетку», и соседи пойдут в занятое место. */
    fun request(creep: Creep, target: Position, priority: Int = 0) {
        if (creep.fatigue > 0) return
        desired[creep.id] = pack(target.x, target.y)
        priorityOf[creep.id] = priority
    }

    /** Куда крип просится в ЭТОМ тике (упакованная клетка); null — не просил. */
    fun desiredOf(id: String): Int? = desired[id]

    /** Крип на прошлом тике шага не просил (стоял по своей воле или обездвижен). */
    fun wasStatic(id: String): Boolean = id !in lastDesired

    /** Крип застрял: просит один и тот же шаг STUCK_TICKS тиков и не двигается. */
    fun isStuck(id: String): Boolean = (stuckTicks[id] ?: 0) >= STUCK_TICKS

    fun stuckFor(id: String): Int = stuckTicks[id] ?: 0

    /** Куда крип просился на прошлом тике (упакованная клетка) — для диагностики. */
    fun lastDesiredOf(id: String): Int? = lastDesired[id]

    /** Строка аудита «вид ok/fail» с обнулением счётчиков. */
    fun audit(): String {
        val s = "moves: free ${okCount[0]}/${failCount[0]} chain ${okCount[1]}/${failCount[1]} swap ${okCount[2]}/${failCount[2]}"
        for (i in 0..2) { okCount[i] = 0; failCount[i] = 0 }
        return s
    }

    /**
     * Разрешить все намерения и выдать команды move.
     * @param movers крипы, которые могут двигаться (их намерения разруливаем и толкаем друг друга)
     * @param obstacles все крипы на поле (как препятствия по текущим позициям; не-movers неподвижны)
     */
    fun resolve(movers: List<Creep>, obstacles: List<Creep>) {
        // аудит прошлого тика: дошёл ли крип туда, куда ему выдали move
        if (issued.isNotEmpty()) {
            val byId = HashMap<String, Creep>()
            for (c in obstacles) byId[c.id] = c
            for ((id, iss) in issued) {
                val c = byId[id] ?: continue
                if (pack(c.x, c.y) == iss.coord) okCount[iss.kind]++ else failCount[iss.kind]++
            }
            issued.clear()
        }
        // застревание: тот же запрос, та же клетка — считаем; иначе сбрасываем
        for (c in movers) {
            val here = pack(c.x, c.y)
            val want = desired[c.id]
            val same = want != null && want == lastDesired[c.id] && lastPos[c.id] == here
            stuckTicks[c.id] = if (same) (stuckTicks[c.id] ?: 0) + 1 else 0
            lastPos[c.id] = here
        }
        stuckTicks.keys.retainAll { id -> movers.any { it.id == id } }
        lastPos.keys.retainAll { id -> movers.any { it.id == id } }

        val occupant = HashMap<Int, Creep>()
        for (creep in obstacles) occupant[pack(creep.x, creep.y)] = creep

        val moverIds = HashSet<String>().apply { movers.forEach { add(it.id) } }
        val movement = HashMap<Int, Creep>()       // целевая клетка -> кто её займёт
        val assignedCoord = HashMap<String, Int>() // creepId -> назначенная клетка
        val kindOf = HashMap<String, Int>()

        // обрабатываем по приоритету: сначала важные (большие), они могут толкать менее приоритетных
        for (mover in movers.sortedByDescending { priorityOf[it.id] ?: 0 }) {
            if (mover.id in assignedCoord) continue
            dfs(mover, null, occupant, moverIds, movement, assignedCoord, kindOf, HashSet())
        }

        for ((coord, creep) in movement) {
            val tx = coord / 100
            val ty = coord % 100
            if (tx != creep.x || ty != creep.y) {
                creep.move(getDirection(tx - creep.x, ty - creep.y))
                issued[creep.id] = Issued(coord, kindOf[creep.id] ?: KIND_FREE)
            }
        }
        lastDesired.clear()
        lastDesired.putAll(desired)
        desired.clear()
        priorityOf.clear()
    }

    /**
     * Пытается назначить крипу клетку (желаемую, либо клетку, освобождаемую вызывающим — swap).
     * @param fromCoord клетка, которую освобождает вызвавший нас крип (null для корня цепочки)
     * @return true, если крип получил клетку (двинется или останется на месте по цепочке)
     */
    private fun dfs(
        creep: Creep,
        fromCoord: Int?,
        occupant: Map<Int, Creep>,
        moverIds: Set<String>,
        movement: HashMap<Int, Creep>,
        assignedCoord: HashMap<String, Int>,
        kindOf: HashMap<String, Int>,
        visited: HashSet<String>,
    ): Boolean {
        visited.add(creep.id)

        val candidates = ArrayList<Int>(2)
        desired[creep.id]?.let { candidates.add(it) }
        if (fromCoord != null) candidates.add(fromCoord) // можем занять клетку, которую освобождает вызывающий (swap)

        for (coord in candidates) {
            if (movement.containsKey(coord)) continue // клетка уже занята чьим-то ходом

            // клетку освобождает вызывающий — занимаем (swap/цепочка)
            if (coord == fromCoord) {
                place(coord, creep, KIND_SWAP, movement, assignedCoord, kindOf)
                return true
            }

            val occ = occupant[coord]
            if (occ == null || occ.id == creep.id) {
                place(coord, creep, KIND_FREE, movement, assignedCoord, kindOf)
                return true
            }

            // блокер уже решил, куда идёт: если он уходит с этой клетки — занимаем
            val occAssigned = assignedCoord[occ.id]
            if (occAssigned != null) {
                if (occAssigned != coord) {
                    place(coord, creep, KIND_CHAIN, movement, assignedCoord, kindOf)
                    return true
                }
                continue // блокер остаётся здесь
            }

            // проталкиваем блокера, если он сам хочет двигаться (есть интент) ИЛИ мы приоритетнее его
            // (большой толкает стоящего маленького). Равный/выше по приоритету стоящий — не сдвигается.
            // Уставшего (fatigue > 0) не толкаем вовсе — он физически не может шагнуть.
            val canPush = occ.fatigue <= 0 && (desired.containsKey(occ.id) ||
                (priorityOf[creep.id] ?: 0) > (priorityOf[occ.id] ?: 0))
            if (occ.id in moverIds && occ.id !in visited && canPush) {
                if (dfs(occ, pack(creep.x, creep.y), occupant, moverIds, movement, assignedCoord, kindOf, visited)) {
                    // рекурсия могла отдать ЭТУ клетку кому-то в цепочке (свап в fromCoord) —
                    // без повторной проверки мы бы перезаписали его ход и оба move провалились
                    if (movement.containsKey(coord)) continue
                    place(coord, creep, KIND_CHAIN, movement, assignedCoord, kindOf)
                    return true
                }
            }
        }
        return false
    }

    private fun place(coord: Int, creep: Creep, kind: Int, movement: HashMap<Int, Creep>, assignedCoord: HashMap<String, Int>, kindOf: HashMap<String, Int>) {
        movement[coord] = creep
        assignedCoord[creep.id] = coord
        kindOf[creep.id] = kind
    }
}
