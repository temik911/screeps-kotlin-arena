package season4.painandgain

/**
 * ОБОРВАННЫЙ ТИК НЕ ОТРАВЛЯЕТ СЛЕДУЮЩИЙ (v222, разбор рейтинговой серии v221, матч 14).
 *
 * Движок обрывает тик по лимиту CPU в ПРОИЗВОЛЬНОЙ точке, и `finally` при этом не выполняется. Если точка пришлась на
 * перестройку хеш-таблицы (`InternalHashMap.compact` переписывает записи на месте и ставит длину только в конце), таблица
 * остаётся с дублями живых записей: места не прибавляется, «дыр» по счётчику всё ещё четверть ёмкости, и следующий же
 * `addKey` крутится между `ensureExtraCapacity` и `compact` вечно. Так и было: второй тик матча 14 оборвался внутри кеша
 * полей `flowTo`, и все 1590 следующих тиков упали в том же месте — армия весь матч без приказов.
 *
 * Починка на месте, а не выброс: обход записей таблицы конечен (индекс идёт по массиву записей до длины, хеш-массив не
 * читается) и отдаёт каждую пару ключ–значение хотя бы раз; `clear()` сбрасывает ровно те ячейки хеша, на которые
 * ссылаются живые записи, и обнуляет длину; повторная вставка схлопывает дубли. Содержимое сохраняется — кеш не надо
 * пересчитывать, и починка не тяжелит тот самый тик, который идёт за оборванным.
 *
 * Коллекции берутся не списком, а обходом собственных полей объекта (`Object.keys`): поле, добавленное завтра, будет
 * починено так же, как сегодняшние. Списки и массивы не чинятся — у них нет пробинга, и зависнуть им не на чем.
 */
internal object AbortRepair {
    /** Итог одной починки: таблиц, множеств и записей, положенных обратно. */
    class Result(val maps: Int, val sets: Int, val entries: Int)

    /** Чинит все таблицы и множества среди собственных полей [owner] (и таблицы/множества, лежащие в них значениями). */
    fun repairFields(owner: Any): Result {
        val o: dynamic = owner
        val keys = js("Object.keys")(o).unsafeCast<Array<String>>()
        var maps = 0; var sets = 0; var entries = 0
        for (k in keys) {
            val v: Any? = o[k]
            when (v) {
                is MutableMap<*, *> -> { entries += repairMap(v); maps++ }
                is MutableSet<*> -> { entries += repairSet(v); sets++ }
            }
        }
        return Result(maps, sets, entries)
    }

    /** Снимает пары, очищает и кладёт обратно. Пустой ключ — след оборванной вставки, он не кладётся. */
    fun repairMap(m: MutableMap<*, *>): Int {
        @Suppress("UNCHECKED_CAST")
        val mm = m as MutableMap<Any?, Any?>
        val keys = ArrayList<Any?>()
        val values = ArrayList<Any?>()
        for (e in mm.entries) {
            if (e.key == null) continue
            keys.add(e.key); values.add(e.value)
        }
        mm.clear()
        for (i in keys.indices) {
            val v = values[i]
            if (v is MutableMap<*, *>) repairMap(v) else if (v is MutableSet<*>) repairSet(v)
            mm[keys[i]] = v
        }
        return keys.size
    }

    fun repairSet(s: MutableSet<*>): Int {
        @Suppress("UNCHECKED_CAST")
        val ss = s as MutableSet<Any?>
        val items = ArrayList<Any?>()
        for (x in ss) if (x != null) items.add(x)
        ss.clear()
        ss.addAll(items)
        return items.size
    }
}
