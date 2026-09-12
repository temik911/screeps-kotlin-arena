package season4.painandgain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Починка таблиц после оборванного тика (см. AbortRepair). Оборвать `compact` изнутри нельзя — пользовательского кода
 * он не зовёт, — зато `rehash` зовёт `hashCode()` каждого ключа, и ключ, бросающий исключение на N-м вызове, оставляет
 * таблицу ровно в том классе состояний, что и таймаут движка: хеш-массив новый и заполнен наполовину, записи целы.
 */
class AbortRepairTest {

    private class Key(val v: Int) {
        override fun hashCode(): Int {
            if (bomb > 0) { bomb--; if (bomb == 0) throw IllegalStateException("tick aborted inside the table") }
            return v * 7919
        }
        override fun equals(other: Any?) = other is Key && other.v == v
        companion object { var bomb = 0 }
    }

    @Test
    fun an_interrupted_rehash_is_repaired_in_place() {
        var corrupted = 0
        for (bombAt in 2..40) {
            val m = HashMap<Key, Int>()
            for (i in 0 until 8) m[Key(i)] = i
            Key.bomb = bombAt
            try { for (i in 8 until 40) m[Key(i)] = i } catch (e: IllegalStateException) { }
            Key.bomb = 0
            val inserted = (0 until 40).filter { i -> m.entries.any { it.key.v == i } }
            // посылка теста: таблица действительно испорчена — ключ, лежащий в записях, не находится поиском
            if (inserted.all { m[Key(it)] == it }) continue
            corrupted++
            AbortRepair.repairMap(m)
            assertEquals(inserted.size, m.size, "each key once after the repair (bomb at $bombAt)")
            for (i in inserted) assertEquals(i, m[Key(i)], "key $i found after the repair (bomb at $bombAt)")
            // и таблица снова растёт: вставка, которая после обрыва могла крутиться вечно, завершается
            for (i in 100 until 1100) m[Key(i)] = i
            assertEquals(inserted.size + 1000, m.size)
            for (i in 100 until 1100) assertEquals(i, m[Key(i)])
        }
        assertTrue(corrupted > 0, "no bomb position left the table corrupted — the test no longer reaches a broken table")
    }

    @Test
    fun an_interrupted_set_is_repaired_in_place() {
        var corrupted = 0
        for (bombAt in 2..40) {
            val s = HashSet<Key>()
            for (i in 0 until 8) s.add(Key(i))
            Key.bomb = bombAt
            try { for (i in 8 until 40) s.add(Key(i)) } catch (e: IllegalStateException) { }
            Key.bomb = 0
            val inserted = (0 until 40).filter { i -> s.any { it.v == i } }
            if (inserted.all { Key(it) in s }) continue
            corrupted++
            AbortRepair.repairSet(s)
            assertEquals(inserted.size, s.size)
            for (i in inserted) assertTrue(Key(i) in s)
        }
        assertTrue(corrupted > 0)
    }

    private object Holder {
        val table = HashMap<String, Int>().apply { put("a", 1); put("b", 2) }
        val set = HashSet<Int>().apply { add(3) }
        val nested = HashMap<String, HashSet<Int>>().apply { put("n", hashSetOf(4, 5)) }
        val list = arrayListOf(6, 7)
    }

    @Test
    fun every_table_field_of_an_object_is_found_and_kept() {
        val r = AbortRepair.repairFields(Holder)
        assertEquals(2, r.maps)
        assertEquals(1, r.sets)
        assertEquals(1, Holder.table["a"]); assertEquals(2, Holder.table["b"])
        assertTrue(3 in Holder.set)
        assertEquals<Set<Int>?>(setOf(4, 5), Holder.nested["n"])
        assertEquals(listOf(6, 7), Holder.list)
    }
}
