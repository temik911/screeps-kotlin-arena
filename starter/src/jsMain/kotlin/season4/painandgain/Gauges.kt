package season4.painandgain

/**
 * РЕЕСТР ПРИБОРОВ (v455, docs/pain-and-gain-architecture-2.md, раздел 4.6 и этап 2). Счётчик объявляется и регистрируется
 * ОДНОЙ строкой у того, кто считает:
 *
 *     internal val rotOut = Gauges.counter("rot")                 // поле `rot=N` строки `t=`
 *     internal val holdPinned = Gauges.counter("hold", 0)         // `hold=a/b/c/d` — часть 0 …
 *     internal val holdKeptFight = Gauges.counter("hold", 3)      // … и часть 3
 *     internal val cmdWhy = Gauges.labelled("cmdwhy")             // словарь «метка:число» по убыванию
 *
 * и считает `rotOut.n++`. Форма печати — при объявлении (разделитель перед частью, метка перед числом, «за этот тик», «за
 * окно печати»); ПОРЯДОК ПОЛЕЙ в строке — один список имён в `Instruments.kt`. До v455 новый счётчик писался в четырёх
 * местах трёх файлов (объявление, `++`, конкатенация печати на 1 810 символов, маска `logdiff.py`), счётчик прохода раздачи — в
 * шести местах четырёх файлов; теперь — объявление и имя в раскладке.
 *
 * ПОЧЕМУ ОБЪЕКТ С ПОЛЕМ `n`, А НЕ `var x by counter(…)`. Делегированное свойство в Kotlin/JS на КАЖДОЕ чтение и КАЖДУЮ запись
 * строит объект-ссылку на свойство и два замыкания (`getPropertyCallableRef`; замерено на скомпилированном модуле, `inline`
 * операторы этого не снимают), а счётчики стоят на самых горячих путях (`scoreCell`), и CPU у этого бота — вход решения. `x.n++`
 * — чтение ссылки и запись поля.
 *
 * Файл нижнего уровня: приборы есть у всех стадий, зависимость направлена вниз. Объект — в списке починки
 * `repairAfterAbort`: словари-счётчики — значения его прямого поля [hist], поэтому оборванный посреди вставки тик чинит их
 * ПО ПОСТРОЕНИЮ (до v455 словари верхнего уровня файла — `capquWhy` и родня — не чинились вовсе: `AbortRepair` обходит только
 * поля объектов-владельцев).
 */
internal class Counter(private val perTick: Boolean = false, internal val perWindow: Boolean = false, internal val last: Boolean = false) {
    var n = 0
    /** Для `perTick`: значение на конец прошлого тика — печатается разница. */
    internal var seen = 0

    /** То, что печатает поле: всё накопленное или, у `perTick`, набранное с конца прошлого тика. */
    fun shown(): Int = if (perTick) n - seen else n
    internal fun endTick() { if (perTick) seen = n }

    /** Счётчик в шаблоне строки лога (`"fled=$orderFled"`) пишется числом, как писался. */
    override fun toString(): String = n.toString()
}

/** Накопитель с дробной суммой (`adrE`, `radSum`…): печатает его вычисляемая часть поля, вливается он как счётчик. */
internal class Real { var x = 0.0; override fun toString(): String = x.toString() }

/** Словарь «метка → число»: печатается по убыванию числа, при равенстве — в порядке первого появления метки. Сам словарь —
 *  значение [Gauges.hist] (см. заголовок файла про починку); у записи раздачи ([GaugeSet]) — собственный, короткоживущий. */
internal class Labelled(val map: HashMap<String, Int>) {
    fun bump(key: String, by: Int = 1) { map[key] = (map[key] ?: 0) + by }
    operator fun get(key: String): Int? = map[key]
    val size: Int get() = map.size
    fun sum(): Int = map.values.sum()
    fun shown(): String = map.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" }
    override fun toString(): String = shown()
}

/** Часть поля: разделитель ПЕРЕД ней (у первой не печатается), метка перед значением, значение. */
internal class GaugePart(val sep: String, val label: String, val text: () -> String)

/** Поле `имя=часть/часть…` строки приборов: части — по номерам, объявлять их можно в любом порядке и в разных файлах. */
internal class GaugeField(val name: String) {
    val parts = ArrayList<GaugePart?>()
    fun put(at: Int, part: GaugePart) {
        while (parts.size <= at) parts.add(null)
        if (parts[at] != null) throw IllegalStateException("gauge $name: part $at is declared twice")
        parts[at] = part
    }
    fun print(): String {
        val sb = StringBuilder(name.substringBefore('@')).append('=')     // `hunt@2` печатается как `hunt=`: в строке два поля с этим именем
        for (i in parts.indices) {
            val p = parts[i] ?: throw IllegalStateException("gauge $name: part $i of ${parts.size} is not declared")
            if (i > 0) sb.append(p.sep)
            sb.append(p.label).append(p.text())
        }
        return sb.toString()
    }
}

internal object Gauges {
    /** Поля по имени. Порядок регистрации НЕ значит ничего: файл стадии инициализируется при первом вызове любой его функции,
     *  то есть в порядке исполнения первого тика; порядок печати задаёт раскладка. */
    val fields = HashMap<String, GaugeField>()
    /** Словари-счётчики по имени поля — прямое поле объекта из списка починки (см. заголовок файла). */
    val hist = HashMap<String, HashMap<String, Int>>()
    /** Счётчики и накопители матча по ключу `поле#часть` — близнецы одноимённых счётчиков записи ([GaugeSet]). */
    val twins = HashMap<String, Any>()
    private val counters = ArrayList<Counter>()

    private fun field(name: String) = fields.getOrPut(name) { GaugeField(name) }

    /** Счётчик — часть [at] поля [field]. [sep] — разделитель перед частью, [label] — метка перед числом (`lost=stay3/stuck1`);
     *  [perTick] — печатать набранное за тик, [perWindow] — обнулять после печати строки; [last] — при вливании записи
     *  раздачи значение ЗАМЕНЯЕТСЯ последним, а не складывается. */
    fun counter(field: String, at: Int = 0, sep: String = "/", label: String = "", perTick: Boolean = false,
                perWindow: Boolean = false, last: Boolean = false): Counter {
        val c = Counter(perTick, perWindow, last)
        counters.add(c)
        twins["$field#$at"] = c
        field(field).put(at, GaugePart(sep, label) { c.shown().toString() })
        return c
    }

    /** Тот же счётчик — ещё и часть другого поля (`healGapN` печатают и `healgap=`, и `nomedic=`). */
    fun also(c: Counter, field: String, at: Int, sep: String = "/", label: String = ""): Counter {
        field(field).put(at, GaugePart(sep, label) { c.shown().toString() })
        return c
    }

    /** Дробный накопитель: в строке его печатает вычисляемая часть ([computed]), здесь — только близнец для вливания. */
    fun real(key: String): Real = Real().also { twins[key] = it }

    /** Массив-накопитель (`gateLevels`, `hpDelta`): печатает вычисляемая часть, здесь — близнец для вливания. */
    fun ints(key: String, size: Int): IntArray = IntArray(size).also { twins[key] = it }
    fun reals(key: String, size: Int): DoubleArray = DoubleArray(size).also { twins[key] = it }

    /** Словарь-счётчик — часть [at] поля [field] (обычно нулевая, следом — счётчик-знаменатель: `objnone=a:3,b:1/40`). */
    fun labelled(field: String, at: Int = 0, sep: String = "/"): Labelled {
        val l = Labelled(hist.getOrPut(field) { HashMap() })
        twins["$field#$at"] = l
        field(field).put(at, GaugePart(sep, "") { l.shown() })
        return l
    }

    /** Словарь-счётчик, который печатает НЕ строка `t=` (перепись `rung t=`, `tac t=`): только владение и вливание. */
    fun labelledOnly(key: String): Labelled = Labelled(hist.getOrPut(key) { HashMap() }).also { twins["$key#0"] = it }

    /** Часть поля, которую считают на месте печати: величина состояния, снимок мира, отношение двух накопителей. */
    fun computed(field: String, at: Int = 0, sep: String = "/", label: String = "", text: () -> String) {
        field(field).put(at, GaugePart(sep, label, text))
    }

    /** Накопитель матча по ключу — тому, кто печатает отношение накопителей или строку НЕ из раскладки (`fld t=`, `rung t=`). */
    fun counterAt(field: String, at: Int = 0): Counter = twins["$field#$at"] as Counter
    fun realAt(key: String): Real = twins[key] as Real
    fun intsAt(key: String): IntArray = twins[key] as IntArray
    fun realsAt(key: String): DoubleArray = twins[key] as DoubleArray
    fun labelledAt(field: String): Labelled = twins["$field#0"] as Labelled

    /** Строка приборов по раскладке: имена полей через пробел; имя с ведущим `<` печатается БЕЗ пробела перед собой. */
    fun line(layout: List<String>): String {
        val sb = StringBuilder()
        for (i in layout.indices) {
            val glued = layout[i].startsWith("<")
            val name = if (glued) layout[i].substring(1) else layout[i]
            if (i > 0 && !glued) sb.append(' ')
            sb.append((fields[name] ?: throw IllegalStateException("gauge $name is in the layout and is declared nowhere")).print())
        }
        return sb.toString()
    }

    /** Конец тика: счётчики «за тик» запоминают, докуда досчитали. */
    fun endTick() { for (c in counters) c.endTick() }

    /** Строка напечатана: счётчики «за окно» начинают заново. */
    fun endWindow() { for (c in counters) if (c.perWindow) c.n = 0 }

    /** Влить запись (выбранную раздачу) в приборы матча: одноимённые части складываются, помеченные `last` — заменяются. */
    fun absorb(set: GaugeSet) {
        for (i in set.keys.indices) {
            val mine = set.items[i]
            val twin = twins[set.keys[i]] ?: throw IllegalStateException("gauge ${set.keys[i]} of a record has no match-level twin")
            when (mine) {
                is Counter -> { twin as Counter; if (mine.last) twin.n = mine.n else twin.n += mine.n }
                is Real -> (twin as Real).x += mine.x
                is IntArray -> { twin as IntArray; for (k in mine.indices) twin[k] += mine[k] }
                is DoubleArray -> { twin as DoubleArray; for (k in mine.indices) twin[k] += mine[k] }
                is Labelled -> { twin as Labelled; for ((k, v) in mine.map) twin.bump(k, v) }
            }
        }
    }
}

/**
 * ПРИБОРЫ ОДНОЙ ЗАПИСИ (раздача командира играется до шести раз за тик, в мир уходит выбранная — v449): те же имена, что у
 * приборов матча, считаются в запись, и [Gauges.absorb] вливает выбранную. Объявление ОДНО — в классе записи: близнец матча
 * заводится сам при первом объявлении одноимённой части. Поэтому владелец записи один раз строит её при инициализации своего
 * файла (`private val declared = DealRecord()`): поле обязано быть объявлено до первой печати строки, а первая настоящая запись
 * появляется только с первым боем.
 */
internal class GaugeSet {
    val keys = ArrayList<String>()
    val items = ArrayList<Any>()
    private fun <T : Any> add(key: String, item: T): T { keys.add(key); items.add(item); return item }

    fun counter(field: String, at: Int = 0, sep: String = "/", label: String = "", last: Boolean = false): Counter {
        if ("$field#$at" !in Gauges.twins) Gauges.counter(field, at, sep, label, false, false, last)
        return add("$field#$at", Counter(false, false, last))
    }
    fun real(key: String): Real { if (key !in Gauges.twins) Gauges.real(key); return add(key, Real()) }
    fun ints(key: String, size: Int): IntArray { if (key !in Gauges.twins) Gauges.ints(key, size); return add(key, IntArray(size)) }
    fun reals(key: String, size: Int): DoubleArray { if (key !in Gauges.twins) Gauges.reals(key, size); return add(key, DoubleArray(size)) }
    /** Словарь записи; близнец матча — словарь поля [field] строки `t=` либо, при [printed] = false, словарь другой строки. */
    fun labelled(field: String, printed: Boolean = true): Labelled {
        if ("$field#0" !in Gauges.twins) { if (printed) Gauges.labelled(field) else Gauges.labelledOnly(field) }
        return add("$field#0", Labelled(HashMap()))
    }
}
