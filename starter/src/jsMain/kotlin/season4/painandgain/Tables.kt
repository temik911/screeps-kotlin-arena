package season4.painandgain

/**
 * ТАБЛИЦЫ РЕШЕНИЙ (v444, docs/pain-and-gain-architecture.md, раздел 4.3). Цепочка «первое сработавшее условие выигрывает»
 * записывается списком именованных строк вместо потока управления: у решения появляется ИМЯ, оно же адрес (тег в логе
 * находится `grep`-ом в одной строке, и условие с действием стоят в ней же), и СЧЁТЧИК, который приходит вместе с именем.
 * Файл нижнего уровня: таблицы есть у тактика, у боя и у стратега, а зависимость направлена вниз.
 */

/**
 * СТРОКА ТАБЛИЦЫ РЕШЕНИЙ (v444, план архитектуры, 4.3): имя, условие, действие. Имя — тег, который печатает лог: по нему
 * `grep` находит строку таблицы, и условие с действием стоят в ней же. Условие — ЧИСТОЕ чтение фактов [F]: его можно вычислить
 * у каждой строки, а не только до первой истинной, — так считаются перекрытые порядком. Действие исполняется только у
 * выигравшей строки; счётчик или запись, которые раньше стояли в теле ветки `when`, живут в нём.
 */
internal class Row<F, R>(val tag: String, val guard: F.() -> Boolean, val act: F.() -> R)

/**
 * Счётчики таблицы, накопительные за матч: `on[i]` — условие строки было истинно, `won[i]` — строка выиграла. Отсюда
 * `shadowed = on − won` — условие истинно, но выше стояла другая строка. Строка с `on > 0` и `won = 0` недостижима ИЗ-ЗА
 * ПОРЯДКА — тот дефект, что в v235 искали руками («назначение лекаря исполнялось 25–40 % тиков, потому что выше стояли order,
 * kite и wall»); `on = 0` на всём гейте и всех живых сериях — условие ни разу не было истинно. Массивы, а не таблицы:
 * чинить после оборванного тика нечего.
 */
internal class Tally(val name: String, val sequence: Boolean = false, register: Boolean = true) {
    // таблица регистрирует свой счётчик сама (v455): строку `reach t=` печатает раскладка имён в Instruments.kt, а не два ручных
    // списка счётчиков. Счётчик записи раздачи (`register = false`) — не таблица матча: он вливается в одноимённый ([absorb])
    init { if (register) tallies.add(this) }

    var tags: List<String> = emptyList()
    var on = IntArray(0)
    var won = IntArray(0)
    /** Условие строки НИЖЕ выигравшей бросило исключение: наблюдение не состоялось, на ход это не влияет. */
    var err = 0

    fun fit(table: List<Row<*, *>>) {
        if (on.size == table.size) return
        tags = table.map { it.tag }; on = IntArray(table.size); won = IntArray(table.size)
    }

    /** Только у последовательности ([sequence]): проход исполнен и не выдал ничего. */
    var idle = IntArray(0)

    fun fitTags(names: List<String>) {
        if (on.size == names.size) return
        tags = names; on = IntArray(names.size); won = IntArray(names.size); idle = IntArray(names.size)
    }

    /** Влить счётчики записи [rec] той же таблицы (выбранная раздача тика) в счётчики матча. */
    fun absorb(rec: Tally) {
        fitTags(rec.tags)
        for (i in rec.on.indices) { on[i] += rec.on[i]; won[i] += rec.won[i]; idle[i] += rec.idle[i] }
    }

    /** `имя=тег:won/true/shadowed,…` — в порядке таблицы (порядок и есть приоритет), нулевые строки тоже: они и нужны. */
    fun print(): String = "$name=" + tags.indices.joinToString(",") {
        "${tags[it]}:${won[it]}/${on[it]}/${if (sequence) idle[it] else on[it] - won[it]}"
    }
}

/** Счётчики таблиц матча в порядке появления (список, а не словарь: чинить после оборванного тика нечего). Порядок печати задаёт
 *  раскладка `REACH_LINE` в Instruments.kt; здесь — только «какие есть». */
internal val tallies = ArrayList<Tally>()

/** Счётчик таблицы по её имени в приборе `reach`. */
internal fun tallyOf(name: String): Tally = tallies.first { it.name == name }

/** Обходчик, один на все таблицы: первая строка с истинным условием выигрывает — семантика прежнего `when`. Порядок списка —
 *  приоритет, другого описания приоритета нет; последняя строка таблицы замыкающая (`{ true }`).
 *  ПОЛНЫЙ ОБХОД ([FULL_WALK]) вычисляет условия и ниже выигравшей строки — только ради счёта `on`: недостижимость, ради
 *  которой всё затеяно, видна лишь живьём, стенд командира почти не экспонирует (решение оператора 8). На интенты он не
 *  влияет: условия чистые, а исключение в условии ниже выигравшей строки (до полного обхода оно там не вычислялось вовсе)
 *  ловится и считается в `err`, ход идёт дальше. */
internal fun <F, R> walk(table: List<Row<F, R>>, facts: F, tally: Tally): Row<F, R> {
    tally.fit(table)
    var winner = -1
    for (i in table.indices) {
        if (winner < 0) {
            if (table[i].guard(facts)) { winner = i; tally.on[i]++; tally.won[i]++; if (!FULL_WALK) break }
        } else {
            try { if (table[i].guard(facts)) tally.on[i]++ } catch (e: Throwable) { tally.err++ }
        }
    }
    if (winner < 0) throw IllegalStateException("decision table ${tally.name} without a closing row")
    return table[winner]
}

/** Вердикт ворот: разрешить, запретить с причиной, идти к следующим воротам. */
internal sealed class Verdict {
    object Allow : Verdict()
    object Next : Verdict()
    class Veto(val reason: String) : Verdict()
}

/**
 * ВОРОТА — строка ПОСЛЕДОВАТЕЛЬНОЙ таблицы (v445, план архитектуры, 4.3 и этап 4): цепочка ранних `return`, где между выходами
 * стоят вычисления и счётчики. В отличие от [Row], ворота вычисляются строго по очереди и только пока решения нет: их величины
 * дороги (мощь сторон) и зависят от момента вызова, а счётчик между воротами — часть своей строки и исполняется, только если до
 * неё дошли. Полного обхода у такой таблицы нет по построению.
 */
internal class Gate<F>(val tag: String, val verdict: F.() -> Verdict)

/** Проход по воротам: первые ворота, сказавшие не `Next`, решают. В счётчиках `on[i]` — до ворот ДОШЛИ, `won[i]` — они решили;
 *  `on − won` — прошли насквозь. Последние ворота обязаны решать. */
internal fun <F> pass(gates: List<Gate<F>>, facts: F, tally: Tally): Verdict {
    if (tally.on.size != gates.size) tally.fitTags(gates.map { it.tag })     // горячий путь: список имён строится один раз
    for (i in gates.indices) {
        tally.on[i]++
        val v = gates[i].verdict(facts)
        if (v !== Verdict.Next) { tally.won[i]++; return v }
    }
    throw IllegalStateException("gate table ${tally.name} without a closing gate")
}

/** ПРОХОД — строка таблицы-ПОСЛЕДОВАТЕЛЬНОСТИ (v445): исполняются все, по порядку списка; имя — адрес и тег прибора. */
internal class Pass(val tag: String, val run: () -> Unit)

/** Исполняет проходы по порядку; [before] получает номер и имя прохода до его запуска. В счётчиках `on[i]` — проход исполнен,
 *  `won[i]` ведёт сам владелец таблицы (у раздачи — клеток выдано), `idle[i]` — исполнен и не выдал ничего: у последовательности
 *  третье число прибора — оно, а не `on − won`. */
internal inline fun runPasses(passes: List<Pass>, tally: Tally, before: (Int, String) -> Unit) {
    if (tally.on.size != passes.size) tally.fitTags(passes.map { it.tag })
    for (i in passes.indices) {
        before(i, passes[i].tag); tally.on[i]++
        val had = tally.won[i]
        passes[i].run()
        if (tally.won[i] == had) tally.idle[i]++
    }
}
