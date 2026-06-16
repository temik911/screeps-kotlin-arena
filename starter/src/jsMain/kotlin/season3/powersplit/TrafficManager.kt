package season3.powersplit

import screeps.api.Creep
import screeps.api.Position
import screeps.api.getDirection

/**
 * Двухфазное движение крипов (КОПИЯ из season3.spawnstrike — по правилу «код арены самодостаточен»,
 * тюнится здесь независимо). Сначала каждый боец регистрирует желаемую клетку (request), затем
 * resolve() разом разрешает все намерения: двигает по цепочке, толкает и меняет местами своих,
 * выбирая согласованное состояние без взаимных «дёрганий».
 *
 * Алгоритм — рекурсивный DFS: чтобы занять клетку блокера, мы пытаемся подвинуть самого блокера;
 * при этом блокеру разрешено шагнуть в клетку, которую освобождает вызывающий — это естественно
 * даёт и обмен местами (swap из 2 крипов), и проталкивание цепочкой.
 */
object TrafficManager {

    /** moverId -> упакованная желаемая клетка. */
    private val desired = HashMap<String, Int>()

    /** moverId -> приоритет (выше — обрабатывается раньше и может проталкивать менее приоритетных). */
    private val priorityOf = HashMap<String, Int>()

    private fun pack(x: Int, y: Int) = x * 100 + y

    /** Зарегистрировать желание бойца шагнуть на клетку target (соседнюю). priority — кто кого толкает.
     *  Уставший крип (fatigue > 0) в этот тик не сдвинется — его желание игнорируем, иначе цепочки
     *  resolve посчитают, что он «освободит клетку», и соседи пойдут в занятое место. */
    fun request(creep: Creep, target: Position, priority: Int = 0) {
        if (creep.fatigue > 0) return
        desired[creep.id] = pack(target.x, target.y)
        priorityOf[creep.id] = priority
    }

    /**
     * Разрешить все намерения и выдать команды move.
     * @param movers крипы, которые могут двигаться (их намерения разруливаем и толкаем друг друга)
     * @param obstacles все крипы на поле (как препятствия по текущим позициям; не-movers неподвижны)
     */
    fun resolve(movers: List<Creep>, obstacles: List<Creep>) {
        val occupant = HashMap<Int, Creep>()
        for (creep in obstacles) occupant[pack(creep.x, creep.y)] = creep

        val moverIds = HashSet<String>().apply { movers.forEach { add(it.id) } }
        val movement = HashMap<Int, Creep>()       // целевая клетка -> кто её займёт
        val assignedCoord = HashMap<String, Int>() // creepId -> назначенная клетка

        // обрабатываем по приоритету: сначала важные, они могут толкать менее приоритетных
        for (mover in movers.sortedByDescending { priorityOf[it.id] ?: 0 }) {
            if (mover.id in assignedCoord) continue
            dfs(mover, null, occupant, moverIds, movement, assignedCoord, HashSet())
        }

        for ((coord, creep) in movement) {
            val tx = coord / 100
            val ty = coord % 100
            if (tx != creep.x || ty != creep.y) creep.move(getDirection(tx - creep.x, ty - creep.y))
        }
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
                place(coord, creep, movement, assignedCoord)
                return true
            }

            val occ = occupant[coord]
            if (occ == null || occ.id == creep.id) {
                place(coord, creep, movement, assignedCoord)
                return true
            }

            // блокер уже решил, куда идёт: если он уходит с этой клетки — занимаем
            val occAssigned = assignedCoord[occ.id]
            if (occAssigned != null) {
                if (occAssigned != coord) {
                    place(coord, creep, movement, assignedCoord)
                    return true
                }
                continue // блокер остаётся здесь
            }

            // проталкиваем блокера, если он сам хочет двигаться (есть интент) ИЛИ мы приоритетнее его.
            // Равный/выше по приоритету стоящий — не сдвигается. Уставшего (fatigue > 0) не толкаем.
            val canPush = occ.fatigue <= 0 && (desired.containsKey(occ.id) ||
                (priorityOf[creep.id] ?: 0) > (priorityOf[occ.id] ?: 0))
            if (occ.id in moverIds && occ.id !in visited && canPush) {
                if (dfs(occ, pack(creep.x, creep.y), occupant, moverIds, movement, assignedCoord, visited)) {
                    // рекурсия могла отдать ЭТУ клетку кому-то в цепочке (свап в fromCoord) —
                    // без повторной проверки мы бы перезаписали его ход и оба move провалились
                    if (movement.containsKey(coord)) continue
                    place(coord, creep, movement, assignedCoord)
                    return true
                }
            }
        }
        return false
    }

    private fun place(coord: Int, creep: Creep, movement: HashMap<Int, Creep>, assignedCoord: HashMap<String, Int>) {
        movement[coord] = creep
        assignedCoord[creep.id] = coord
    }
}
