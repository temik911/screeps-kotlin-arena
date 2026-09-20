package season4.painandgain

import screeps.api.Creep

/**
 * ОТРЯДЫ: КТО В КАКОМ РЕЕСТРЕ (v460, второй шаг архитектуры, этап 6.5). До v460 членство держали восемь полей `Memory`, а писали их
 * четыре файла — только у `cmdDetach` было семь мест записи, и правило «`cmdDetach` всегда пишется парой с `runnerFlag`» держалось на
 * внимательности. Реестры — прямые поля этого объекта (он в списке починки после оборванного тика); записи трёх реестров, у которых
 * было несколько файлов-писателей (`cmdDetach`, `detachedIds`, `runnerFlag`), — ТОЛЬКО операциями ниже: те же записи в том же порядке,
 * но пара «отряжён командиром — флаг» теперь одна операция, и разойтись не может. Остальные пять пишет один стратег, напрямую.
 *
 * ДВА ИСТОЧНИКА ОСТАЮТСЯ ДВУМЯ НАБОРАМИ. `detachedIds` — отряд за флагами, который выпускает СТРАТЕГ (`StrategyDetach`), `cmdDetach` —
 * кого отправил КОМАНДИР (`commandRace`). Свести их к одному владельцу на крипа значит изменить поведение: отзыв стратега считает крипа
 * вернувшимся, а `cmdDetach` его ещё держит (находка 2.8 п. 9 плана), `alreadyOut` читает только командирский набор.
 *
 * ИНВАРИАНТЫ, НА КОТОРЫЕ КОД ОПИРАЕТСЯ МОЛЧА (сняты с кода и с абзацев версий; часть — дефекты, они перенесены как есть):
 *  1. Бегуном крип становится ОДИН раз за тик — в `Ctx` (раскладка `army` / `runners`). Запись реестра действует со СЛЕДУЮЩЕГО тика.
 *  2. `cmdDetach` всегда пишется парой с `runnerFlag` — теперь это одна операция [detach] с флагом (структурно).
 *  3. `alreadyOut` (кого командир уже отпустил) обязан читаться ДО `recallAll(COMMANDER)` — дефект v215 был ровно здесь (`RaceRoster`).
 *  4. Порядок стадий `runRunners → updateKeepers → отзыв боем → отряд и отзыв стратега → assignChase → commandRace` — несущий.
 *  5. `cmdDetach` командир каждый тик очищает и строит заново; ранние выходы `commandRace` оставляют его НЕДОСТРОЕННЫМ, и гарнизон
 *     (`garrisonOf.retainAll`) читает это промежуточное состояние.
 *  6. Отряжённый стратегом, хранитель и преследователь командиром не отряжаются (v464, дефект 3): состав гонки их исключает, а
 *     [detach] с флагом отказывает и считает `sqref=`; пару «гарнизон + хранитель» считают `dbl=` и `dblf=` (тот же флаг / разные).
 *  7. Чистка `detachedIds` — решение, а не уборка: остаётся только живой, ВООРУЖЁННЫЙ и ПОДВИЖНЫЙ ([keepDetached], зовёт мир).
 *  8. Чистка `runnerFlag` — только нынешние бегуны ([keepAssigned]); между `commandRace` и паросочетанием бегунов следующего тика у
 *     `runnerFlag` «последний писатель побеждает».
 *  9. Отзыв боем ([recallAllBut]) — решение стратега, которое исполняет стадия мер мира (уровень 2): так сложилось, перенесено как есть.
 * 10. `cmdDetach` НЕ чистится от мёртвых: только `recallAll`; в режиме боя без `fightOnNow` набор не трогает никто (находка 2.8 п. 10).
 * 11. Скаут — бегун ПО ТЕЛУ: он не состоит ни в одном реестре, пока его не закрепит гарнизон или курьер; `chaseOf` / `chaseTarget`
 *     живут один тик (их строит заново `assignChase`), но читают их строка лестницы, огонь и строй — поэтому они здесь, а не в носителе.
 */
internal object Squads {
    /** Кто отрядил: стратег (отряд за флагами, `detachedIds`) или командир (гонка и марш, `cmdDetach` + флаг). */
    enum class Source { STRATEGIST, COMMANDER }

    val cmdDetach = HashSet<String>()      // кого командир отправил за флагами (v160, режимы RACE и MARCH)
    val detachedIds = HashSet<String>()           // отряды: вооружённые, зачисленные в бегуны (см. USE_DETACH)
    /** id захватчика -> id флага (липкое назначение). */
    val runnerFlag = HashMap<String, String>()
    val keeperIds = HashMap<String, String>()   // хранитель флага → id флага (см. KEEP_RANGE)
    /** Постоянный гарнизон (v337): id крипа -> id флага, который он держит до конца матча (см. commandRace). */
    val garrisonOf = HashMap<String, String>()
    /** Курьер (v369): id скаута -> id дорогого флага, за которым он закреплён, пока жив и флаг не стал нашим. */
    val courierOf = HashMap<String, String>()
    /** Кто из наших назначен добить какой остов: id нашего -> id остова. Считается РАЗ в тик, до перебора
     *  замыслов, иначе пять прогонов раздачи дали бы пять разных отрядов. */
    val chaseOf = HashMap<String, String>()
    /** ...и сам объект цели. Искать остов в combatEnemies/armedEnemies НЕЛЬЗЯ: он по определению не входит ни в
     *  один из них — это ровно та невидимость, из-за которой предмет и возник. Первая редакция погони искала там,
     *  фокус не назначался никогда, и стенд показал 13 назначений при нуле добитых. */
    val chaseTarget = HashMap<String, Creep>()

    /** Командир отряжает крипа к флагу: член `cmdDetach` И его флаг — одной операцией, в прежнем порядке записей.
     *  ИНВАРИАНТЫ ЧЛЕНСТВА (v464, дефект 3): отряжённого стратегом в этом тике, хранителя и преследователя командир не отряжает —
     *  состав гонки их не содержит (`commandRace`), а здесь стоит предохранитель со счётчиком `sqref=`: отказ считается, не молчит. */
    fun detach(id: String, flagId: String): Boolean {
        if (id in detachedIds) { refDetached.n++; return false }
        if (id in keeperIds) { refKeeper.n++; return false }
        if (id in chaseOf) { refChaser.n++; return false }
        cmdDetach.add(id); runnerFlag[id] = flagId
        return true
    }

    /** Стратег выпускает крипа в отряд за флагами; флаг ему назначит паросочетание бегунов со следующего тика. */
    fun detach(id: String) { detachedIds.add(id) }

    /** Отозвать всех, кого отрядил [source]. Флаги бегунов не трогаются — их чистит [keepAssigned]. */
    fun recallAll(source: Source) { if (source == Source.COMMANDER) cmdDetach.clear() else detachedIds.clear() }

    /** Отзыв боем: из ОБОИХ наборов остаются только [keep]; возвращает, сколько отозвано (для счётчика `recalled`). */
    fun recallAllBut(keep: Set<String>): Int {
        val before = cmdDetach.size + detachedIds.size
        cmdDetach.retainAll(keep)
        detachedIds.retainAll(keep)
        return before - (cmdDetach.size + detachedIds.size)
    }

    /** Стратег возвращает в ядро одного / нескольких из своего отряда. */
    fun recall(id: String) { detachedIds.remove(id) }
    fun recall(ids: Set<String>) { detachedIds.removeAll(ids) }

    /** В отряде стратега остаются только годные (живой, вооружённый, подвижный — решает зовущий). */
    fun keepDetached(fit: (String) -> Boolean) { detachedIds.retainAll(fit) }

    /** Назначение бегуна на флаг паросочетанием (липкое); [unassign] — бегун остался без флага. */
    fun assign(id: String, flagId: String) { runnerFlag[id] = flagId }
    fun unassign(id: String) { runnerFlag.remove(id) }
    fun unassignAll() { runnerFlag.clear() }

    /** Назначения остаются только у нынешних бегунов. */
    fun keepAssigned(runner: (String) -> Boolean) { runnerFlag.keys.retainAll(runner) }

    // приборы владельца — ЧЛЕНЫ объекта, не верх файла: у Squads.kt нет функций верхнего уровня, и верх файла в Kotlin/JS
    // инициализируется только при первом обращении к нему, а раскладка строки `t=` спрашивает поле раньше первого отказа
    /** Отказы командирского [detach] (v464, дефект 3): крип уже отряжён стратегом / хранитель / преследователь. Состав гонки их
     *  не содержит, поэтому ненулевое значение — новое место записи в обход состава. */
    val refDetached = Gauges.counter("sqref")
    val refKeeper = Gauges.counter("sqref", 1)
    val refChaser = Gauges.counter("sqref", 2)
}
