package season4.painandgain

import screeps.api.ATTACK
import screeps.api.ATTACK_POWER
import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.CARRY_CAPACITY
import screeps.api.CostMatrix
import screeps.api.Creep
import screeps.api.EFF_ATTACK_MODIFIER
import screeps.api.EFF_DAMAGE_TAKEN_MODIFIER
import screeps.api.EFF_HEAL_MODIFIER
import screeps.api.EFF_RANGED_ATTACK_MODIFIER
import screeps.api.HEAL
import screeps.api.HEAL_POWER
import screeps.api.MOVE
import screeps.api.Position
import screeps.api.RANGED_ATTACK
import screeps.api.RANGED_ATTACK_POWER
import screeps.api.RANGED_HEAL_POWER
import screeps.api.RESOURCE_ENERGY
import screeps.api.SearchGoal
import screeps.api.SearchPathOptions
import screeps.api.TERRAIN_SWAMP
import screeps.api.TERRAIN_WALL
import screeps.api.TOUGH
import screeps.api.WORK
import screeps.api.arenaInfo
import screeps.api.get
import screeps.api.getObjectsByPrototype
import screeps.api.getRange
import screeps.api.getTerrainAt
import screeps.api.getTicks
import screeps.api.getCpuTime
import screeps.api.searchPath
import screeps.api.season4.FLAG_TYPES
import screeps.api.season4.MAX_SCORE_PER_TICK
import screeps.api.season4.ScoreFlag
import screeps.api.season4.TICKS_LIMIT
import screeps.api.structures.StructureRampart
import screeps.api.structures.StructureSpawn
import screeps.api.structures.StructureWall
import sourcemaps.runWithSourceMapSupport
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.reflect.*

internal class AddrShooter(val x: Int, val y: Int, val ranged: Double, val melee: Double)

/**
 * НОСИТЕЛЬ ОДНОЙ РАЗДАЧИ (v445, план архитектуры, 4.4 и этап 4): прежде это были ~30 общих локальных функции-замыкания и 22
 * локальные функции над ними. Теперь локальные — поля В ПРЕЖНЕМ ПОРЯДКЕ (порядок текста = порядок инициализации), локальные
 * функции — методы, блоки проходов — методы `pass*()`, собранные в таблицу [passes]. Класс локальный намеренно: имя в нём
 * разрешается «член носителя → параметр и локальная функции → член `PainAndGain` → верх пакета» — как раньше «локальная →
 * остальное»; у класса верхнего уровня с членами-расширениями приёмник `PainAndGain` шёл бы ПЕРВЫМ.
 * Раздачу зовут до шести раз за тик (перебор замыслов) — носитель живёт один вызов. Карта секций — docs/pain-and-gain-sections.md.
 *
 * КЛАСС ВЕРХНЕГО УРОВНЯ В СВОЁМ ФАЙЛЕ (v456, второй шаг архитектуры, этап 4.4). Локальным он оставался из-за порядка разрешения
 * имён: у класса верхнего уровня с методами-расширениями приёмник `PainAndGain` шёл бы первым. Теперь методы обычные, обёртки
 * фактов — функции верхнего уровня (этап 1), семь параметров `commandFight` и то, что она считает до раздачи (`fighters`,
 * `enemyAt`, `cells`), — параметры конструктора, а пять членов синглтона, которые читает раздача (`huntsWounded`, `lostTick`,
 * `victimNow`, `victimSaveable`, `wallCells` — величины одного тика, до этапа 6), — явно, через `pag`. Ранние выходы
 * (`fighters.isEmpty()`, `cells.isEmpty()`) остались в `commandFight`, до построения.
 */
internal class Deal(
    private val army: List<Creep>, private val combatEnemies: List<Creep>, private val armedEnemies: List<Creep>,
    private val out: MutableMap<String, Position>, private val intent: Intent, private val ourFlagCells: Set<Int>, private val healersOnly: Boolean,
    private val fighters: List<Creep>, private val enemyAt: HashSet<Int>, private val cells: HashMap<Int, Position>,
) {
    // якорь кулака не читается нигде (мёртв), а сам вызов нужен: Formation.fist СУЖАЕТ `cells` на месте
    val fistAnchor = Formation.fist(fighters, cells)
    // ЗЕМЛЯ НЕ ОТДАЁТСЯ, ПОКА МЫ НЕ СЛАБЕЕ (v320). Разбор 18 реплеев блобов MetalicaX (Opus): тики, где центр армии идёт
    // назад быстрее 0,15 клетки, дают размен 0,46 против #15 и 0,68 против #9-типа; стоим — 0,93/1,19; идём вперёд —
    // 1,50/0,90. В поражениях от #15 армия проводит так 40 % тиков контакта (размен 0,32), в победах 7 %; в режиме FIGHT
    // пятятся все роли по 0,2 клетки в тик даже при замысле PRESS. Здесь это запрет на клетку ДАЛЬШЕ от его массы для
    // вооружённого, пока ядро в контакте и мы не слабее; раненые в ротации, лекари и замысел кайта — не под правилом
    // ⚠️ ОТВЕРГНУТО ЖИВЫМ ЗАМЕРОМ (v323): «земля не отдаётся, пока мы не слабее» (v320–v322) — запрет клетки дальше от его
    // массы для вооружённого в контакте. Основание было сильное по числам разбора (18 реплеев блобов MetalicaX: отход даёт
    // размен 0,46, стояние 0,93, движение вперёд 1,50; поражения проводят так 40 % тиков контакта против 7 % в победах), и
    // гейт дал +8 тыс. отрыва, но рука по 8 игр на бота этого не подтвердила: MetalicaX#15 2-6 против 0-8, зато
    // Coldkimchi#2 3-5 против 5-3 и MetalicaX#3 3-5 против 5-3 — всего 16-24 против 18-22. Причинность в разборе и
    // называлась средней: наступление включалось, пока состояние было лучше, а не наоборот
    // ОПАСНОСТЬ КЛЕТКИ ЧИТАЕТСЯ ИЗ ПОЛЯ (v204, этап 4). Прежде она строилась здесь, то есть ПЯТЬ раз за тик —
    // по разу на замысел, над одним и тем же множеством врагов, — и внутри цикла по клеткам звалась profileOf,
    // читающая тело крипа через границу изоляции. Поле строится один раз в прологе тика (см. buildFields), и
    // этап 3 доказал равенство: fldcmp = 0 на 304 950 клетках 135 сценариев.
    // Мёртвыми оказались incNow и hits: обе карты считались в том же цикле и не читались НИКЕМ — их удалила
    // перепись, а не чтение кода
    // АДРЕСНАЯ ОПАСНОСТЬ (v224, см. USE_ADDRESSED_DANGER): E складывает всех, кто достаёт клетку, а его ствол бьёт
    // ОДНОГО — лекаря в досягаемости, иначе ближайшего, при равенстве того, у кого меньше хитов (реплеи обеих сторон:
    // 89–94 % выстрелов в ближайшего, 82–97 % в лекаря при лекаре в досягаемости). T(c, p) записывает крипу c в
    // клетке p урон только тех стволов, для которых он был бы этой целью при остальных наших на назначенных клетках
    // (план сравнивается с планом, как у согласованности строя). Досягаемости те же, что у ядер E: стрелок 3, мили 2.
    // Лучший «другой» кандидат считается один раз на крипа и пересчитывается, когда раздача перешла к другому крипу
    val addrShooters = ArrayList<AddrShooter>()
    init {
        for (e in armedEnemies) {
            val q = InfluenceMap.profileOf(e)
            if (q.ranged > 0.0 || q.melee > 0.0) addrShooters.add(AddrShooter(e.x, e.y, q.ranged, q.melee))
        }
    }
    val addrLive = living(army)
    var addrFor: String? = null
    val addrRH = DoubleArray(addrShooters.size); val addrRA = DoubleArray(addrShooters.size)
    val addrMH = DoubleArray(addrShooters.size); val addrMA = DoubleArray(addrShooters.size)
    fun addrPrepare(c: Creep) {
        addrFor = c.id
        for (i in addrShooters.indices) { addrRH[i] = Double.MAX_VALUE; addrRA[i] = Double.MAX_VALUE; addrMH[i] = Double.MAX_VALUE; addrMA[i] = Double.MAX_VALUE }
        for (f in addrLive) {
            if (f.id == c.id) continue
            val p = out[f.id] ?: InfluenceMap.cell(f.x, f.y)
            val h = healerOnly(f)
            for (i in addrShooters.indices) {
                val s = addrShooters[i]
                val d = maxOf(abs(p.x - s.x), abs(p.y - s.y))
                if (d > RANGED_RANGE) continue
                val r = d * 100000.0 + f.hits
                if (s.ranged > 0.0) { if (r < addrRA[i]) addrRA[i] = r; if (h && r < addrRH[i]) addrRH[i] = r }
                if (d <= 2 && s.melee > 0.0) { if (r < addrMA[i]) addrMA[i] = r; if (h && r < addrMH[i]) addrMH[i] = r }
            }
        }
    }
    fun addressedAt(c: Creep, x: Int, y: Int): Double {
        if (addrFor != c.id) addrPrepare(c)
        val h = healerOnly(c)
        var sum = 0.0
        for (i in addrShooters.indices) {
            val s = addrShooters[i]
            val d = maxOf(abs(x - s.x), abs(y - s.y))
            if (d > RANGED_RANGE) continue
            val r = d * 100000.0 + c.hits
            if (s.ranged > 0.0 && (if (h) r <= addrRH[i] else (addrRH[i] == Double.MAX_VALUE && r <= addrRA[i]))) sum += s.ranged
            if (d <= 2 && s.melee > 0.0 && (if (h) r <= addrMH[i] else (addrMH[i] == Double.MAX_VALUE && r <= addrMA[i]))) sum += s.melee
        }
        return sum
    }
    /** Опасность клетки ДЛЯ ЭТОГО крипа: адресная при тумблере, иначе поле E — байт в байт прежняя раздача. */
    fun danOf(c: Creep, key: Int): Double =
        InfluenceMap.dangerAt(key)
    val goal = ensureGoalField(fighters, combatEnemies)
    /** Цена клетки по направлению: сколько тиков пути от неё до ближайшего очага. */
    fun goalCost(key: Int): Double {
        val g = goal ?: return 0.0
        val d = g[key]
        return if (d < 0) GOAL_UNREACHABLE else d.toDouble()
    }
    val taken = HashSet<Int>()
    /** ЗАПИСЬ ЭТОЙ РАЗДАЧИ (v449, пункт В оператора): поле нужды и пробы — свои у каждой пробы замысла; в мир и в приборы
     *  уходит запись ВЫБРАННОЙ (см. DealRecord и Commander.publishDeal). */
    val rec = DealRecord()
    // ПРИТЯЗАНИЯ СЧИТАЮТСЯ ИЗ ОКОНЧАТЕЛЬНЫХ ПРИКАЗОВ, А НЕ КОПЯТСЯ ПОЛЕМ (v450, пункт В оператора, вторая половина). До v449
    // `place` штамповал притязание на клетку и её соседей в поле `InfluenceMap.claim`, общее для всех проб тика; v449 дал
    // каждой раздаче своё поле с прежней семантикой — и притязание СНЯТОГО приказа (спасение в `place`, проход `pinned`)
    // оставалось стоять, «притязание без крипа»: вокруг клетки просителя отталкивание удваивалось. Теперь притязание клетки
    // — число приказов, поставленных `place` и стоящих в `out`, в её окрестности: снят приказ — снято и притязание, по
    // построению. Это поведенческая правка: спасение по `alone` — обычное дело первого контакта, и гейт разошёлся с v449 в
    // 114 логах из 139 (см. абзац v450). Прежний текст поля, дословно:
    // «Занятость клеток уже розданными приказами: раздача идёт пять раз за тик, по разу на замысел, и
    // притязания одного замысла не должны просачиваться в следующий (Hagelbäck: временное отталкивание
    // в выбранной клетке — то, что не даёт крипам слипаться в одну точку).
    // Ставит притязание на клетку и её соседей: следующий крип видит её как занятую.»
    val claimed = HashSet<String>()
    fun claimAt(key: Int): Double {
        val x = key / 100; val y = key % 100
        var n = 0
        for (id in claimed) { val p = out[id] ?: continue; if (maxOf(abs(p.x - x), abs(p.y - y)) <= 1) n++ }
        return n.toDouble()
    }
    // ПРОГНОЗ СЛЕДУЮЩЕГО ТИКА ДЛЯ ЛЕКАРЯ (v478, вопрос 7 оператора, см. USE_HEAL_THREAT_NEXT): его клетки после шага по модели
    // проката и адресная угроза «лекарь первым» — то, чего не хватало точной цене v439. Считается на раздачу (O(его × наших)),
    // читается только ценой клетки лекаря в режиме огня; у бойцов адресная угроза отвергнута дважды (v244, v246) и не читается
    val nextPred: Map<String, Position> = if (USE_HEAL_THREAT_NEXT) Forecast.predictCells(combatEnemies, living(army)) else emptyMap()
    val threatNext = Forecast.Threat(armedEnemies, nextPred)
    init {
        if (USE_HEAL_THREAT_NEXT) rec.need.next = InfluenceMap.NextTick(
            underFire = { a -> cellOf(a).let { threatNext.reaches(it.x, it.y) } },
            threatAt = { c, x, y -> threatNext.at(c, x, y) },
            wardCell = { a -> cellOf(a) })
    }
    // клетки, где стоят СВОИ: назначать их нельзя — приказ туда неисполним, пока сосед не ушёл, а прибор показал,
    // что до назначенной клетки доходят 7 % (v167). Своя собственная клетка при этом разрешена: это «стой»
    // (множество `allyAt`, которое описывал комментарий выше, наполнялось и НЕ ЧИТАЛОСЬ нигде — снято в v447 как мёртвое;
    // занятость своих клеток решает карта жильцов `allyOf` в `place`)
    // кто стоит в клетке (для цепочек): ключ клетки → крип
    val allyOf = HashMap<Int, Creep>()
    init { for (a in army) if (a.hits > 0) allyOf[a.key] = a }
    /** ЖИЛЕЦ КЛЕТКИ (v449, пункт В): свой, стоящий в ней с начала тика и не уведённый приказом — в `out` его нет или приказ
     *  «стой»; просителю сам он не жилец. До v449 карта `allyOf` ещё и ПРАВИЛАСЬ при назначении (жилец, уведённый в другую
     *  клетку, вычёркивался), а снятый приказ его не возвращал — та же несимметрия, что у притязаний; теперь карта неизменна,
     *  а «ушёл ли» читается из `out`. */
    fun tenantOf(key: Int, c: Creep): Creep? =
        allyOf[key]?.takeIf { t -> t.id != c.id && (t.id !in out || out[t.id]?.let { it.x == t.x && it.y == t.y } == true) }
    // ПЛАН СТРОИТСЯ ВГЛУБЬ (v168, оператор): «если крипу необходимо уйти на клетку, на которой сейчас стоит крип,
    // зачем этому крипу необходимо сдвинуться на другую, и если там стоит крип, то сдвинуть и его, и так далее».
    // Раздача рекурсивна: занятая клетка не отвергается и не просто дорожает — её жилец получает приказ уйти,
    // а если и его клетка занята, цепочка идёт дальше, до CHAIN_DEPTH звеньев. Обмен местами — вырожденная
    // цепочка длины два, и он разрешён отдельно: ротация состава (раненого назад, свежего вперёд) это ровно своп
    // ОПАСНОСТЬ ПУТИ, А НЕ ТОЛЬКО КЛЕТКИ (v169, оператор). Клетка в двух шагах достигается ЧЕРЕЗ промежуточную, и
    // если та под огнём, приказ либо не исполняется, либо исполняется ценой крипа: движение честно отказывается
    // идти сквозь огонь, и именно так приказ терялся. Стоимость пути — самая безопасная из промежуточных клеток
    fun pathDanger(c: Creep, p: Position): Double {
        if (getRange(c, p) <= 1) return 0.0
        var best = Double.MAX_VALUE
        for (dx in sym(1)) for (dy in sym(1)) {
            if (dx == 0 && dy == 0) continue
            val x = c.x + dx; val y = c.y + dy
            if (maxOf(abs(x - p.x), abs(y - p.y)) > 1) continue          // должна быть смежной с целью
            if (x < 0 || y < 0 || x > 99 || y > 99 || DistanceMap.isTerrainWall(x, y)) continue
            val d = danOf(c, key(x, y))
            if (d < best) best = d
        }
        return if (best == Double.MAX_VALUE) PATH_BLOCKED_COST else best * PATH_DANGER_W
    }
    // кандидаты вокруг крипа: приказ ровно на шаг, значит их девять — а цикл шёл по всей раздаче (около трёхсот
    // клеток) и отбрасывал лишние проверкой дальности. При рекурсии цепочек и переборе замыслов это и давало
    // `Script execution timed out` почти в каждом матче (v181)
    fun nearCells(c: Creep): List<Pair<Int, Position>> {
        val near = ArrayList<Pair<Int, Position>>(9)
        for (dx in sym(COMMAND_REACH)) for (dy in sym(COMMAND_REACH)) {
            val key = key(c.x + dx, c.y + dy)
            cells[key]?.let { near.add(key to it) }
        }
        return near
    }
    // ПЕРЕПИСЬ ПРОХОДОВ (v203): какой проход раздачи сколько клеток реально назначил. Пара «удалось/попыток» —
    // ноль в числителе при живом знаменателе значит «проход отказался», оба нуля значат «до прохода не дошли»
    var passTag = "-"
    fun place(c: Creep, wants: (Position) -> Boolean, rank: (Position) -> Double, depth: Int = 0,
              rescue: Boolean = false): Boolean {
        var best: Position? = null; var bestScore = Double.MAX_VALUE
        var bestTenant: Creep? = null
        // ПРИБОР СЛАГАЕМОГО — «сколько решений ИЗМЕНИЛОСЬ», а не «сколько раз код исполнился». Ровно этого не
        // хватало v196: счётчик доказывал, что код работает, и не доказывал, что он поменял хоть одну клетку
        var bare: Position? = null; var bareScore = Double.MAX_VALUE
        // свободные, прошедшие ворота клетки (v222, см. USE_MELEE_QUIET_CELL): из них мили выбирает тихую клетку удара
        // ПРИКАЗ ОБЯЗАН БЫТЬ ИСПОЛНИМ (v172, оператор): «командир должен быть уверен, что каждый крип на следующем
        // шагу сможет выполнить приказ». Уставший крип в этот тик не двинется вовсе — ему можно приказать только
        // стоять, и приказ «шагни» от него был бы ложью, которую потом считает прогноз
        val canStep = canMove(c) && c.fatigue == 0
        // ...дальность проверяется по координатам, а не вызовом getRange: порядок перебора остаётся прежним (его
        // смена меняла выбор при равных оценках и роняла строку гейта), но отбрасывание дальних клеток становится
        // дешёвым — а их около трёхсот на каждого крипа при девяти нужных (v181)
        for ((key, p) in cells) {
            if (abs(p.x - c.x) > COMMAND_REACH || abs(p.y - c.y) > COMMAND_REACH) continue
            if (!canStep && !(p.x == c.x && p.y == c.y)) continue
            if (key in taken) continue
            if (!wants(p)) continue
            val self = p.x == c.x && p.y == c.y
            // ...и жилец, которому приказано СТОЯТЬ, остаётся препятствием (v175): прежде всякий, кто уже получил
            // приказ, считался уходящим — а приказ «стой» (хранитель флага, крип на своём месте) никуда его не
            // уводит, и назначенная поверх него клетка оказывалась неисполнимой. Это и есть весь оставшийся
            // процент неисполнения: 17 случаев на 5 294 приказа, все вида «крип остался на месте»
            val tenant = if (self) null else tenantOf(key, c)

            // клетка под своим дороже: приказ туда исполним, только если жильца удастся сдвинуть
            // СМЕРТЕЛЬНАЯ КЛЕТКА НЕ ПРЕДЛАГАЕТСЯ (v178, оператор: «наш мили крип шагнул сразу под 2 мили крипов
            // соперника — для него это должно было быть смертельно, и эта точка никак не могла ему выдаваться»).
            // Опасность клетки входила слагаемым и её перевешивали другие члены; теперь клетка, где входящий за
            // тик снимает крипу всю жизнь, стоит запретительно дорого и берётся, только если других нет вовсе
            val lethal =  danOf(c, key) >= c.hits
            // БОЛОТО ДЕРЖИТ КРИПА НЕСКОЛЬКО ТИКОВ (v183, оператор: «часть нашей армии часто вязнет в болоте и
            // дальше не может двигаться»). Про болото знала только СТАРАЯ ветка движения — штраф в выборе слота и
            // расчёт периода шага; командир, забравший себе все приказы, о нём не знал вовсе и посылал крипов в
            // трясину. Замер по четырём разгромам: наши крипы несут усталость 489 крипо-тиков против его 36 (в
            // тринадцать раз), стоят на месте 22 % против его 7 %, самый долгий непрерывный простой у нас 47 тиков
            // против семи у него. Шаг В болото стоит четырёх тиков неподвижности; стоять НА болоте бесплатно,
            // поэтому дорожает только переход
            val bog =  !self && DistanceMap.isSwamp(p.x, p.y)
            val sc0 = rank(p) + (if (tenant != null) ALLY_CELL_COST else 0.0) + pathDanger(c, p) +
                (if (lethal) LETHAL_CELL_COST else 0.0) + (if (bog) SWAMP_CELL_COST else 0.0)
            // СПУСК К ОЧАГУ (v204, этап 5) — слагаемое в той же оценке, а не отдельная ветка: вблизи врага все
            // затравки на нуле и оно плоское, решает тактика; вдали оно единственное, что отличает клетки друг
            // от друга. Переход непрерывный, режима, который можно перепутать, нет
            val sc = sc0 + GOAL_STEP_COST * goalCost(key)
            // ...и при РАВНЫХ оценках выбор не должен зависеть от порядка перебора: раньше порядок задавала общая
            // раздача, теперь — обход соседей, и одна строка гейта поменяла исход именно из-за этого (v181)
            if (sc < bestScore) { bestScore = sc; best = p; bestTenant = tenant }
            if (sc0 < bareScore) { bareScore = sc0; bare = p }
        }
        val b = best ?: return false
        rec.goalDecisions.n++
        val bs = bare
        if (bs == null || bs.x != b.x || bs.y != b.y) rec.goalFlips.n++
        val tenant = bestTenant
        if (tenant != null && !evict(c, b, tenant, depth, rescue)) return false
        commit(c, b, depth)
        return true
    }

    /** СЕКЦИЯ `place`: жилец выбранной клетки уводится цепочкой (своп или любая другая клетка); `false` — увести не удалось, снятый спасением приказ возвращён. */
    private fun evict(c: Creep, b: Position, tenant: Creep, depth: Int, rescue: Boolean): Boolean {
        if (depth >= CHAIN_DEPTH) return false
        // СПАСЕНИЕ СТАРШЕ ЛЮБОГО ПРИКАЗА (v176, оператор): если самая безопасная клетка занята крипом, которому
        // велено стоять, приказ стоять снимается и жилец уводится цепочкой — беречь расстановку ценой крипа
        // армия из четырнадцати не может. Снимается он ТОЛЬКО у выбранного жильца: первая редакция снимала
        // приказы прямо в переборе кандидатов, у всех подряд, и прибор поймал это коллизией (clash=1)
        var undo: Position? = null
        if (rescue) { undo = out.remove(tenant.id); taken.remove(tenant.key) }
        // своп: жилец встаёт на клетку просителя — так делается ротация состава
        val swapCell = cells[c.key]
        val moved = (swapCell != null && place(tenant, { p -> p.x == c.x && p.y == c.y }, { 0.0 }, depth + 1)) ||
            place(tenant, { p -> p.x != b.x || p.y != b.y }, { p -> danOf(tenant, p.key) }, depth + 1)
        // ...и при неудаче цепочки снятый приказ ВОЗВРАЩАЕТСЯ: без отката жилец оставался без приказа, его
        // клетка свободной, и позже она доставалась двоим — прибор ловил это как clash=1 (v176)
        if (!moved) {
            // ...и вернуть приказ можно, только если его клетку за это время никто не занял: слепое
            // восстановление отдавало одну клетку двоим (clash в режиме боя, t=808)
            val back = undo
            if (back != null && back.key !in taken) { out[tenant.id] = back; taken.add(back.key) }
            return false
        }
        return true
    }

    /** СЕКЦИЯ `place`: приказ записан — клетка занята, прибор адресной опасности, притязание, перепись проходов. */
    private fun commit(c: Creep, b: Position, depth: Int) {
        taken.add(b.key); out[c.id] = b
        // прибор адресной опасности (v224): E и T выбранной клетки, в обеих сборках
        if (depth == 0) {
            val e = InfluenceMap.dangerAt(b.key)
            val t = addressedAt(c, b.x, b.y)
            rec.adrN.n++; rec.adrE.x += e; rec.adrT.x += t
            if (t >= e) rec.adrSame.n++
        }
        // временное отталкивание в занятой клетке (Hagelbäck & Johansson): следующий крип видит её как
        // тесную. Без этого двое выбирают одну клетку, третий загораживает четвёртого — записанная причина
        // провала USE_FORWARD_SEARCH: «каждый крип считает за себя»
        claimed.add(c.id)          // притязание — из приказа в `out`, снимается вместе с ним (v450, см. claimAt)
        if (depth == 0) { rec.passCount.bump(passTag); rec.tally.won[passIndex]++ }
    }
    // поля, которые пишет один проход, а читает другой (до v445 — локальные посреди тела функции)
    val rotatingMeet = HashMap<String, Position>()
    val medicked = HashSet<String>()
    var maxV = 0.0          // считает проход `fields`; читает `sagAt` — только из прохода `melee`, стоящего в списке НИЖЕ: порядок списка держит это, не область видимости
    var passIndex = 0
    val weakestMelee = armedEnemies.minByOrNull { it.hits }
    // МЁРТВЫЙ meleeCommit УДАЛЁН (v214). Правило v143 обещало: мили идёт вплотную, только когда цель
    // добивается этим тиком или у нас перевес по силе, — но переменная была ОБЪЯВЛЕНА И НИКЕМ НЕ ЧИТАЛАСЬ,
    // то есть обещанного не было вовсе, и тумблер USE_MELEE_COMMIT не гейтил ничего. Оба её слагаемых теперь
    // есть в лучшем виде: перевес — местный (см. spotEdgeAt, поле удара в клетке врага, а не мощь армии),
    // добиваемость — killTicks/killableNow, которыми уже пользуется фокус. Замеры из прежнего комментария
    // перенесены в docs/pain-and-gain.md.
    val hisMelee = armedEnemies.filter { InfluenceMap.profileOf(it).melee > 0.0 }
    // СОГЛАСОВАННОСТЬ СТРОЯ (v200, оператор: «все ходы должны быть согласованными... не должно быть такого, что
    // наш крип пошёл в наступление без прикрытия; командир не должен отправлять крипов в строй врага, если он там
    // может сильно пострадать и без возможности быть вылеченным»). Клетка крипа считается ОТНОСИТЕЛЬНО уже
    // назначенных клеток остальных, а не их нынешних позиций: иначе «согласованность» сравнивает план с прошлым
    fun cellOf(f: Creep): Position = out[f.id] ?: InfluenceMap.cell(f.x, f.y)
    fun foeDist(x: Int, y: Int) = armedEnemies.minOfOrNull { maxOf(abs(x - it.x), abs(y - it.y)) } ?: 99
    val melees = fighters.filter { meleeOnlyLive(it) }
    val rangeds = fighters.filter { hasRanged(it) }
    val healers = fighters.filter { healerOnly(it) }
    // РАЗДЕТЫЕ ТОЖЕ ПОД ПРИКАЗОМ (v144): крип, потерявший все боевые части, не попадал НИ В ОДНУ группу — ни
    // оружия, ни лечения, — и приказа не получал вовсе, оставаясь стоять под огнём. Диагноз называл это в каждом
    // разборе: наши раздетые в трёх клетках от его вооружённых 172 крипо-тика из 238, у него 7 из 11
    val stripped = fighters.filter { unitOf(it).stripped }
    // ...и замысел может быть СВОЙ у каждого крипа (v139, портфельный поиск): армия смешивает поведение
    fun intentOf(c: Creep) = intent
    // клетки лекарей — прибор согласованности `mheal` ниже: сколько мили осталось в дальности лечения
    val healerCells = healers.map { InfluenceMap.cell(it.x, it.y) }
    // ГДЕ ФРОНТ ПРОСЕДАЕТ: уязвимость (2·min(наши, его)) выделяет линию контакта, влияние (наши − его)
    // говорит, чья она. Проседание — участок линии, где влияние отрицательно, то есть где нас продавливают
    fun sagAt(key: Int): Double {
        if (maxV <= 0.0) return 0.0
        if (InfluenceMap.vulnerabilityOf(key) < FRONT_RIDGE * maxV) return 0.0
        return maxOf(0.0, -InfluenceMap.influenceOf(key))
    }
    // тела своих вокруг клетки — по УЖЕ НАЗНАЧЕННЫМ клеткам, а не по нынешним позициям: план сравнивается
    // с планом, иначе «согласованность» сравнивает будущее с прошлым
    fun screenAt(c: Creep, p: Position): Int = fighters.count { f ->
        f.id != c.id && cellOf(f).let { maxOf(abs(it.x - p.x), abs(it.y - p.y)) <= 1 } }
    /**
     * ВОРОТА — ВЫЖИВАЕМОСТЬ КРИПА, А НЕ СРАВНЕНИЕ СИЛ. Прежнее `netDamageAt·2 > hits` истинно для КАЖДОЙ
     * клетки фронта (наш мили 1600 хитов против его полного блока даёт 2520), и это ровно записанный в
     * доках провал «трое мили развернулись спиной в первый тик контакта». Здесь считается, сколько тиков
     * крип проживёт в клетке: экран своих тел входит ДЕЛИТЕЛЕМ входящего, лечение — вычитаемым, и на
     * собственное лечение уходящий крип не рассчитывает.
     */
    fun ttlAt(c: Creep, key: Int, p: Position): Double {
        val e = danOf(c, key)
        if (e <= 0.0) return 99.0
        val heal = maxOf(0.0, InfluenceMap.healReachAt(key) - InfluenceMap.healFromSelf(c, p.x, p.y))
        val net = maxOf(0.0, e - heal) / (1.0 + SCREEN_SHARE * screenAt(c, p))
        return if (net <= 1.0) 99.0 else c.hits / net
    }
    val weakestFoe = armedEnemies.minByOrNull { it.hits }
    fun stayBonus(c: Creep, p: Position) = if (p.x == c.x && p.y == c.y) STAY_BONUS else 0.0
    // МИЛИ: к тому, что достанет ногами; по гребню фронта и туда, где он проседает; не выходя из-под лечения.
    // «Не выходя из-под лечения» было ЗАПРЕТОМ (inHealReach) и потому либо не давало клеток вовсе, либо
    // отбрасывалось молча; здесь это слагаемое, и оно конкурирует с притяжением честно
    fun scoreMelee(c: Creep, key: Int, p: Position, att: Double, dan: Double, focus: Creep?): Double {
        val pull = if (focus != null) InfluenceMap.attractionTo(focus, p.x, p.y, true) else InfluenceMap.attMeleeAt(key)
        // УДАР ВХОДИТ В ЦЕНУ КЛЕТКИ ЯВНО И В ЕДИНИЦАХ УРОНА (v425). Ранг мили складывал притяжение `attMeleeAt` с
        // опасностью, и сам УДАР в нём выражен не был — а он решает размен: наш мили вплотную даёт 240 в тик одним
        // крипом, тогда как три стрелка дают 180 против его лечения 129–147 на цель, то есть ЧИСТЫМИ 33–51. Пробить
        // лечение стрелками нельзя в принципе; пробивает мили. Чистая пара 16 игр против MetalicaX#13 говорит это
        // прямо: в победах наш мили стоит в 1,35 ± 0,11 клетки от его ближайшего и теряет первую боевую часть на
        // контакте+8,8, в поражениях — 1,95 ± 0,57 и остаётся ЦЕЛЫМ (ноль выбитых частей к контакту+10 в 8 матчах
        // из 9), пока раздевают наших стрелков; его огонь переходит на стрелков уже на контакте+6,4. Разрез по
        // дистанции <= 1,45 даёт 6 побед из 6 против 0 из 9. Слагаемое считается так же, как у лекаря («доставленное
        // лечение минус полученный урон»): клетка, из которой мили достаёт вооружённого, приносит армии его удар с
        // поправкой на входящий модификатор цели, и этот удар конкурирует с опасностью честно, а не через вес
        val strike = if (!USE_MELEE_STRIKE_VALUE) 0.0 else {
            val hit = InfluenceMap.profileOf(c).melee
            if (hit <= 0.0) 0.0
            else armedEnemies.filter { getRange(p, it) <= 1 }.maxOfOrNull { hit * InfluenceMap.takenOf(it) } ?: 0.0
        }
        return -W_ATT * att * pull - strike + W_DAN * dan * danOf(c, key) -
            W_FRONT * InfluenceMap.vulnerabilityOf(key) - W_SAG * sagAt(key) -
            W_HEALCOVER * InfluenceMap.healReachAt(key) +
            CLAIM_COST * claimAt(key) - stayBonus(c, p)
    }
    // СТРЕЛОК: притяжение с пиком на дальности 3 (он останавливается сам, вместо запрета «не ближе мили»),
    // плюс влияние — стоять там, где сильнее мы. Это и есть «не быть первой линией», сказанное числом
    fun scoreRanged(c: Creep, key: Int, p: Position, att: Double, dan: Double, focus: Creep?): Double {
        val pull = if (focus != null) InfluenceMap.attractionTo(focus, p.x, p.y, false) else InfluenceMap.attRangedAt(key)
        // ВЫСТРЕЛ ВХОДИТ В ЦЕНУ КЛЕТКИ ЯВНО (v427) — тот же пропуск, что у мили в v425, и та же форма. В ранге
        // стрелка стояло поле притяжения `attRangedAt` с пиком на дальности три, опасность и влияние линии, но
        // САМ ВЫСТРЕЛ выражен не был: клетка, откуда стрелок достаёт вооружённого, и клетка, откуда не достаёт,
        // различались только полем, а не тем уроном, который армия из этой клетки получает. Поле — величина без
        // размерности, урон — в тех же единицах, что входящий, поэтому здесь они сравниваются честно, как у лекаря
        // («доставленное лечение минус полученный урон»). Числа v425 дают и цену вопроса: выстрел 60 против удара
        // мили 240, то есть слагаемое вчетверо меньше и не должно переворачивать расстановку — оно лишь перестаёт
        // отдавать выстрел даром. Прежняя мера этого не ловила: v421 вернула выстрел ЗАПАСНОМУ ходу, но основной
        // ранг по-прежнему не знал, что клетка вне дальности не стреляет вовсе
        val shot = if (!USE_RANGED_SHOT_VALUE) 0.0 else {
            val hit = InfluenceMap.profileOf(c).ranged
            if (hit <= 0.0) 0.0
            else armedEnemies.filter { getRange(p, it) <= RANGED_RANGE }.maxOfOrNull { hit * InfluenceMap.takenOf(it) } ?: 0.0
        }
        return -W_ATT * att * pull - shot + W_DAN * dan * danOf(c, key) -
            W_LINE * InfluenceMap.influenceOf(key) +
            CLAIM_COST * claimAt(key) - stayBonus(c, p)
    }
    // ЛЕКАРЬ: тянется туда, где помощь ВЕРОЯТНЕЕ ВСЕГО ПОНАДОБИТСЯ (нужда = опасность в клетке подопечного,
    // а не его нынешняя рана — прежнее правило брало раненых, то есть по определению тех, кто уже на фронте,
    // и тянуло лекаря вперёд). Смотрит на огонь ЭТИМ тиком, а не на E: иначе клетка рядом с подопечным,
    // которого уже рубят, читается как 720 опасности и выталкивает лекаря на три клетки
    fun scoreHeal(c: Creep, key: Int, p: Position, att: Double, dan: Double): Double {
        val scr = screenAt(c, p)
        // ОГОНЬ СЛЕДУЮЩЕГО ТИКА В РЕЖИМЕ ОГНЯ (v478, см. USE_HEAL_THREAT_NEXT): адресная угроза лекарю в клетке после его шага, и
        // экран тел ему не скидка (его стрелок бьёт лекаря в досягаемости первым — разбор v438); вне режима — поле этого тика
        val fireModeNow = USE_HEAL_THREAT_NEXT && rec.need.deliveryFireMode(c, army)
        val fire = if (fireModeNow) threatNext.at(c, p.x, p.y) else InfluenceMap.fireFieldAt(key)
        val shielded = if (fireModeNow) 0.0 else fire * (1.0 - 1.0 / (1.0 + SCREEN_SHARE * scr))   // урон, который снимут тела своих
        // ПРИТЯЖЕНИЕ ЛЕКАРЯ НАСЫЩАЕТСЯ ТЕМ, ЧТО ОН МОЖЕТ ДОСТАВИТЬ (v208, замер серии v206: лекарей за линией
        // 0.695 против 0.944 у v205 — они полезли в первую линию). Нужда складывается по всем подопечным в
        // радиусе и не ограничена ничем, поэтому доходила до тысяч против сотен опасности, и лекарь нырял в
        // рубку. Но доставить он может только своё лечение за тик: 72 у полного h6m6. Мягкое насыщение
        // deliver·att/(att + deliver) сохраняет ФОРМУ поля (порядок клеток тот же) и ставит потолок в тех же
        // единицах, что и урон. Тогда оценка лекаря честно читается как «доставленное лечение минус
        // полученный урон», и клетка под огнём в 500 ради 72 лечения проигрывает сама, без запрета
        val deliver = InfluenceMap.healOf(c)
        val raw = rec.need.attHealAt(key)
        // Две замены этого притяжения ОТВЕРГНУТЫ живьём (v224, по 0-4): «доставленное из клетки лечение лучшему
        // подопечному» (USE_HEAL_PULL_DELIVERED) и «без слагаемого влияния» (USE_HEALER_NO_LINE). Действуют насыщенная сумма
        // здесь и влияние W_LINE ниже; зонд `hpick` называет влияние решающим (разбор v270: −5…−7 в пользу выбранной клетки
        // против свободной клетки вплотную к бойцу первой линии). Комментарий до v270 описывал обе редакции как действующие
        // ЦЕНА КЛЕТКИ — ЛУЧШАЯ ОДНА ДОСТАВКА ТОМУ, КТО ТЕРЯЕТ ХИТЫ (v435, см. USE_HEAL_NEED_ACTUAL): 72 вплотную к бьющемуся,
        // 24 в двух-трёх клетках, 0 у целого — в тех же единицах, что входящий урон, как у стены v228, но для всех
        val pull = if (USE_HEAL_NEED_ACTUAL) rec.need.bestDeliveryAt(c, p.x, p.y, army)
            else if (deliver <= 0.0 || raw <= 0.0) 0.0 else deliver * raw / (raw + deliver)
        // ...И ПРИ УДЕРЖИМОЙ ЖЕРТВЕ ЦЕНА КЛЕТКИ — ДОСТАВЛЕННОЕ В НЕЁ ЛЕЧЕНИЕ (v228, см. USE_HEAL_WALL): вплотную полное, в трёх
        // треть, без насыщенной суммы и без слагаемого влияния — клетка вплотную к жертве получает положительную цену, которой
        // обе редакции v224 ей дать не смогли (72 против 24)
        val victim = Wall.victimNow
        if (Wall.victimSaveable && victim != null) {
            val d = getRange(p, victim)
            val wall = if (d <= 1 && Wall.wallCells.any { it.x == p.x && it.y == p.y }) deliver else if (d <= HEAL_RANGE) deliver / 3.0 else 0.0
            return -W_ATT * att * wall + W_DAN * dan * fire - W_SCREEN * shielded + CLAIM_COST * claimAt(key) - stayBonus(c, p)
        }
        // ...И ВЛИЯНИЕ ЛИНИИ НЕ ВЫТЕСНЯЕТ ДОСТАВКУ (v437, см. USE_HEAL_NO_LINE): у лекаря все прочие слагаемые — в хитах
        // (доставка, огонь, экран), а влияние безразмерно и ни на что в арене не опирается; зонд dh в трёх сборках подряд
        // называет его единственным, что уводит лекаря от безопасной клетки вплотную к теряющему хиты (−8…−38 против
        // +3…+10 доставки). Первая редакция снимала влияние целиком и уронила гейт на match29:camp (13 254 : 23 910):
        // когда рядом никто не теряет хитов, притяжение равно нулю везде, и без влияния лекарей ничто не держит при
        // армии — пятеро бойцов ушли в лагерь и погибли нелечеными (hadj=0/347, hlost=0). Влияние — клей строя, и оно
        // остаётся там, где доставки нет; там, где она есть, цена клетки считается в хитах
        // ...И В РЕЖИМЕ ОГНЯ ВЛИЯНИЯ НЕТ НИГДЕ (сужение v438 по гейту match4:kite 792 : 22 718): клетки под его огнём
        // получили доставку 0, клей включился в них — а влияние (наш залп − его опасность) у мили-кулака в сотни, и dh показал
        // −178 в пользу клетки в кулаке против безопасной вплотную. Якорь лекарю в этом режиме — доставка у безопасных клеток
        // при подопечном под огнём, он есть по построению режима; в клетках под огнём остаются опасность, притязание и стой
        val line = if (USE_HEAL_NO_LINE && (pull > 0.0 || rec.need.deliveryFireMode(c, army))) 0.0 else W_LINE * InfluenceMap.influenceOf(key)
        return -W_ATT * att * pull + W_DAN * dan * fire -
            line - W_SCREEN * shielded +
            CLAIM_COST * claimAt(key) - stayBonus(c, p)
    }
    /**
     * ЗАМЫСЕЛ = ВЕКТОР ВЕСОВ над одной оценкой: множитель притяжения, множитель опасности, порог выживания.
     * Пять веток `when` на роль превращаются в пять троек чисел — этап 8 проверит, окупается ли перебор.
     */
    fun weightsOf(i: Intent): Triple<Double, Double, Int> = when (i) {
        Intent.PRESS -> Triple(1.5, 0.7, 2)
        Intent.HOLD -> Triple(1.0, 1.0, 3)
        Intent.YIELD -> Triple(0.3, 2.0, 5)
        Intent.FOCUS -> Triple(1.5, 0.7, 2)
        Intent.KITE -> Triple(1.0, 1.3, 3)
    }
    /**
     * Раздача по оценке с МЯГКИМИ воротами: порог выживания снижается по лестнице, пока клетка не найдётся,
     * и уровень записывается счётчиком. Сегодняшний молчаливый провал требования в общий добор становится
     * печатаемым числом — `gate=...`. Ниже единицы порог не опускается: клетка, где крип умирает за ход,
     * не предлагается никогда.
     */
    fun placeScored(c: Creep, role: Int, i: Intent): Boolean {
        val (att, dan, ttlMin) = weightsOf(i)
        // ПРЕСЛЕДОВАТЕЛЬ СМОТРИТ НА СВОЙ ОСТОВ (v211) — тем же полем притяжения, что и фокус: у мили пик
        // вплотную, у стрелка на дальности три. Отдельной формулы у погони нет и не нужно
        val chased = Squads.chaseTarget[c.id]
        // ...и ЦЕЛЬ ПАЧКИ МИЛИ во всех замыслах (v221, см. USE_MELEE_PACK_CELLS): притяжение к одной его цели
        // вместо суммы по всем — четыре мили в одну клетку-соседа, а не каждый к своей
        // Две правки «ноги за фокусом» ОТВЕРГНУТЫ живьём (14.09.2026, по 16 игр против Coldkimchi#1 и 8 против ●ω<♥♪#6,
        // разбор реплеев — docs, абзацы v268–v271): притяжение стрелка к фокусу армии во всех замыслах (v268) и клетка,
        // держащая фокус у стрелка, который его достаёт (v269). Счёт в полосе шума (9-7 и 8-8 при 7-9 у v267), а убийства
        // провалились: его боевых погибло к контакту +400 четыре и пять за 16 игр против 24 у v267 и 16–17 у v263–v266,
        // стволов на фокусе 0,62 против 0,75 (p = 0,009). Фокус, липкий с v267, часто стоит в четырёх, и стрелок, которого
        // тянут к нему, уходит от целей, которые достаёт; удержание клетки на фокусе стоило стрелку 51 огня в тик против 32
        // (p = 0,010), а стволов на цели не прибавило — фокус уходит своим шагом
        val focus = chased ?: (null)
            ?: if (i == Intent.FOCUS) (if (role == 0) weakestMelee else weakestFoe) else null
        val kite = i == Intent.KITE
        val rank = { p: Position ->
            val key = p.key
            when (role) {
                0 -> scoreMelee(c, key, p, att, dan, focus)
                1 -> scoreRanged(c, key, p, att, dan, focus)
                else -> scoreHeal(c, key, p, att, dan)
            }
        }
        for (lvl in ttlMin downTo 1) {
            val ok = place(c, { p ->
                (!kite || hisMelee.isEmpty() || hisMelee.minOf { getRange(p, it) } >= MELEE_HOLD_RANGE) &&
                    ttlAt(c, p.key, p) >= lvl
            }, rank)
            if (ok) { rec.gateLevels[minOf(lvl, rec.gateLevels.size - 1)]++; return true }
        }
        rec.gateFell.n++
        return false
    }

    /** Проход `freeze`: при healersOnly бойцам — «стой», их клетки заняты (безымянная вставка до v445). */
    fun passFreeze() {
        // ОТХОД — ТОЖЕ ПРИКАЗ (v174, оператор: «не должно быть ничего, что идёт мимо него»): бегство было веткой ВЫШЕ
        // командира. Теперь он сам уводит того, кому грозит гибель, — потерявшего за тик больше половины остатка или
        // стоящего под огнём без лечения рядом
        // ТОЛЬКО ЛЕКАРИ (v436, см. USE_COMMANDER_HEALERS_IN_CONTACT): в тики контакта, когда режим боя молчит, командир
        // ставит одних лекарей — по той же цене клетки (доставленное лечение минус входящий), а бойцы стоят там, где
        // стоят, и идут по веткам тактика. Их нынешние клетки заняты как «стой»: это препятствия для раздачи и тела для
        // экрана. Разбор v435 (Opus, 32 реплея): 79,8 % лекаре-тиков окна боя лекаря ставили ветки healMate/wall, а не
        // приказ, потому что режим боя против Coldkimchi включён в 25–40 % тиков (outmatched, retreat, posture, nofire)
        if (healersOnly) for (f in fighters) if (!healerOnly(f)) {
            val key = f.key
            out[f.id] = InfluenceMap.cell(f.x, f.y); taken.add(key)
        }
    }

    fun passRetreat() {
        // уходящие по его фокусу и их клетки — для встречи с лекарём (v276, см. проход лекарей)
        for (c in fighters) {
            if (c.id in out) continue
            val hurtBadly = (Wall.lostTick[c.id] ?: 0) * 2 >= c.hits && c.hits * 3 < c.hitsMax
            val alone = InfluenceMap.damageAt(c.x, c.y, combatEnemies) > 0.0 &&
                army.none { it.id != c.id && hasHeal(it) && getRange(c, it) <= HEAL_RANGE }
            // ...и уходящий по его фокусу (v275, см. rotateByFocus): та же самая безопасная клетка, и среди равных — ближе к
            // нашему лекарю: встреча раненого с лекарём вне его досягаемости (у него — 3 → 1 клетка за пять тиков)
            val rotate = c.id in Memory.rotByFocus || c.id in Memory.stepOutIds
            if (!hurtBadly && !alone && !rotate) continue
            // УХОД ВЕДЁТ К ЛЕКАРЮ, А НЕ ПРОСТО ПРОЧЬ (v407, проект «армия не рассыпается», этап 1). Причина ухода
            // названа в условии прямо над этим: `alone` — «под уроном, и НЕТ ЛЕКАРЯ в досягаемости». Цель такого ухода
            // — лекарь; между тем клетка выбиралась по «подальше от врага», где о своих не сказано ничего, и слагаемое
            // медика стояло только у ветки ротации (v275/v276). Итог измерен по телам в реплеях (`replay.py bodies`):
            // в поражении против MetalicaX#13 на 60-м тике обе армии целы и стоят кучно (мы 11 крипов 13 466 хитов),
            // а к 80-му мы размазаны по четырнадцати клеткам с 4 973 хитами и ОДНИМ живым лекарем, тогда как он держит
            // строй в пяти клетках при 12 662 хитах и трёх лекарях; к сотому нас трое. В победе того же блока на 80-м
            // тике мы стоим в радиусе пяти со всеми тремя лекарями — и армия ОТРАСТАЕТ (10 301 -> 12 589 -> 14 531
            // хитов), потому что лечение возвращает части. То есть исход решает связность под уроном, а не размен:
            // сомкнутая армия лечится, рассыпавшуюся он ест по одному. Та же форма правки уже сработала на скаутах
            // (v373: третьим критерием бегства стал дом, матч с топ-1 перевернулся 3-5 -> 14-2)
            // ⚠️ ...И НЕ ПРОТИВ ТОГО, КТО ОХОТИТСЯ ЗА РАНЕНЫМИ (v408, по контролю). Против `MetalicaX#13` правка дала
            // 12-4 при базе 15-17, против фермера 8-0, но против `●ω<♥♪#5` — 4-12 за шестнадцать игр двумя блоками
            // подряд, то есть не шум. Причина названа замером v275: его ствол бьёт нашего крипа с НАИМЕНЬШЕЙ ДОЛЕЙ
            // ХИТОВ, тогда как MetalicaX бьёт ближайшего или лекаря. Сбор раненых у лекаря против добивающего раненых
            // — готовая мишень: мы сами сводим в одну точку тех, в кого он и целится, вместе с лекарем. Признак у бота
            // уже есть и проверен фактом (`huntsWounded`: две модели его выбора сверяются с правдой следующего тика)
            val medics = if (TacticianState.huntsWounded) (if (rotate) army.filter { it.id != c.id && healerOnly(it) } else emptyList())
                else army.filter { it.id != c.id && hasHeal(it) && (rotate || !hasWeapon(it)) }
            place(c, { true }, rescue = true, rank = { p -> danOf(c, p.key) * 100 -
                (armedEnemies.minOfOrNull { getRange(p, it) } ?: 0).toDouble() +
                (medics.minOfOrNull { getRange(p, it) } ?: 0).toDouble() })
            if (rotate) out[c.id]?.let { rotatingMeet[c.id] = it }
        }
    }

    fun passStraggler() {
        // ОТСТАВШИЙ И ВЫРВАВШИЙСЯ ПОДТЯГИВАЮТСЯ (v177, оператор: «в момент начала боя у нас всегда был 1 крип где-то
        // впереди, и его очень быстро убивали»). Кулак ограничивал КАНДИДАТНЫЕ клетки, но крипа, уже стоящего вне
        // кулака, никто не возвращал: замер по записи разгрома — боевой крип отрывался от своих на 10 клеток при
        // среднем 1,8. Такому назначается шаг К ЯКОРЮ, и раньше всех прочих назначений
        if (fighters.size >= 3) {
            val (ax, ay) = Formation.median(fighters)
            for (c in fighters.sortedByDescending { maxOf(abs(it.x - ax), abs(it.y - ay)) }) {
                if (c.id in out) continue
                if (maxOf(abs(c.x - ax), abs(c.y - ay)) <= FIST_RADIUS + STRAGGLER_SLACK) continue
                // ...но НЕ того, кто уже бьёт: увести мили из контакта — отдать разменную клетку даром (гейт 134/135)
                if (armedEnemies.any { getRange(c, it) <= 1 }) continue
                place(c, { p -> maxOf(abs(p.x - ax), abs(p.y - ay)) < maxOf(abs(c.x - ax), abs(c.y - ay)) },
                    { p -> danOf(c, p.key) + maxOf(abs(p.x - ax), abs(p.y - ay)) })
            }
        }
    }

    /** Проход `fields`: сброс притязаний, поле нужды в лечении, максимум уязвимости (безымянная вставка до v445). */
    fun passFields() {
        // ==================== ОЦЕНКА КЛЕТКИ ПОЛЕМ (v206, этап 6) ====================
        // Заменяет собой отборы `safeForRanged`, `behindMelee`, `behindLine`, `inHealReach` и россыпь ранговых
        // формул. Все они говорили ЗАПРЕТАМИ то, что является предпочтением, и потому запирали друг друга:
        // v200 нашёл круг, где «стрелок не впереди мили» и «мили стоит позади» вместе выталкивали стрелка за
        // дальность выстрела, отбор пустел, и крип падал в общий добор, который про дальность не знает вовсе.
        // Запрет здесь ровно один и он о жизни: клетка, где крип не переживёт хода.
        // ПРИТЯЗАНИЯ ПРОХОДОВ ДО ЭТОГО (retreat, straggler) СБРАСЫВАЮТСЯ — как сбрасывало общее поле `clearClaim` до v449: их
        // приказы и клетки остаются в `out` / `taken`, а отталкивания вокруг них раздача мили, стрелков и лекарей не видит.
        // Первая редакция v449 их считала, и гейт разошёлся в 115 логах из 139 с первого контакта — это часть меры, а не
        // утечки. Поле нужды — своё у этой раздачи (v449, см. DealRecord)
        claimed.clear()
        rec.need.stampHealNeed(living(army))
        for ((k, _) in cells) {
            val v = InfluenceMap.vulnerabilityOf(k)
            if (v > maxV) maxV = v
        }
    }

    fun passMelee() {
        // мили: по замыслу — вплотную к его вооружённому (напор), в самую безопасную клетку с целью (удержание) или
        // как можно дальше от его мили (уступка); среди равных всегда меньше входящего на следующий тик
        for (c in (if (healersOnly) emptyList() else melees).sortedBy { c -> armedEnemies.minOfOrNull { getRange(c, it) } ?: 99 }) {
            val ok = placeScored(c, 0, intentOf(c))
            // ДОБОР МИЛИ ПОМЕНЯЛ СМЫСЛ ВМЕСТЕ С ВОРОТАМИ (v208). Прежде `ok = false` значило «нет клетки вплотную
            // к его вооружённому, куда дотягивается лекарь», и шаг к врагу был верным ответом. С воротами
            // выживания `ok = false` значит «нет клетки, где я переживу ход», и тот же шаг посылает крипа
            // умирать: на стенде этот добор срабатывает в 3 % размещений. Ответ обратный — самая безопасная
            if (!ok) place(c, { true }, { p -> danOf(c, p.key) })
        }
    }

    fun passRanged() {
        // стрелки: цель в дальности, меньше всего входящего на следующий тик; при равенстве — дальше от его мили
        for (c in (if (healersOnly) emptyList() else rangeds).sortedBy { c -> cells.values.count { p -> getRange(c, p) <= 2 && armedEnemies.any { getRange(p, it) <= RANGED_RANGE } } }) {
            val ok = placeScored(c, 1, intentOf(c))
            // ...и КОГДА ВЫБОРА НЕТ, СТРЕЛОК ВЫХОДИТ ИЗ-ПОД МИЛИ, А НЕ ОСТАЁТСЯ СТРЕЛЯТЬ (v183, оператор: «рэнжи не
            // должны быть рядом с его мили»). Все замыслы требуют разом двух вещей — быть вне досягаемости его мили и
            // при этом доставать цель, — а такой клетки рядом с его строем часто нет вовсе, и общий фолбэк «любая
            // клетка, где меньше входящего» оставлял стрелка под ударом: замер тестовой игры 3d95be — наши стрелки в
            // одной клетке от его вооружённого 28 крипо-тиков и в двух ещё 31 из 117. Между «выстрелить» и «уцелеть»
            // выбирается уцелеть: выстрел стоит 60, стрелок — 1 200
            // ...НО САМАЯ БЕЗОПАСНАЯ — ЭТО САМАЯ БЕЗОПАСНАЯ ИЗ ТЕХ, ОТКУДА ДОСТАЁТ (v421). У ЛЕКАРЯ это правило стоит с
            // того же v183 двумя проходами ниже («самая безопасная из тех, откуда достаёт»), а у стрелка запасной ход
            // ранжируется ТОЛЬКО опасностью по всем клеткам подряд — и уводит из дальности, хотя среди достающих клеток
            // тоже есть самая безопасная. Чистая пара 16 тестовых игр против MetalicaX#13 (7-9, одна версия) называет
            // цену: доля тиков контакт+0…+30, где не меньше ТРЁХ наших стволов достают одного и того же его крипа, —
            // **1,00 во всех шести победах (31 тик из 31) против 0,73 ± 0,14 в поражениях**, пересечения нет. Порог
            // «три» не назначен, а посчитан: наш выстрел 60 (6 частей × 10), его лечение на одну цель 129–147 в тик,
            // значит два ствола (120) НИЖЕ лечения, три (180) выше. Измеренная сходимость 2,56 ± 0,27 в победах против
            // 2,03 ± 0,49 в поражениях — в поражениях наш огонь стоит ниже его лечения, и с него за весь бой не
            // снимается НИ ОДНОЙ части (0 из 15 матчей к контакту+20), при том что молчащих стволов нет ни у нас, ни у
            // него (0,0 % крип-тиков «цель в дальности, не выстрелил»). Выбор цели при этом работает одинаково (0,51
            // против 0,40, разделения нет) — не сходится ГЕОМЕТРИЯ, поэтому правка и стоит здесь, а не в ярусе цели
            if (c.id !in out) {
                val keptReach = USE_RANGED_FALLBACK_KEEPS_REACH &&
                    place(c, { p -> armedEnemies.any { getRange(p, it) <= RANGED_RANGE } }, { p -> danOf(c, p.key) })
                if (keptReach) rec.fallReach.n++ else { rec.fallAny.n++; place(c, { true }, { p -> danOf(c, p.key) }) }
            }
        }
    }

    /** Проход `advancing`: кто из бойцов идёт вперёд — лекарь их в точном режиме не считает (безымянная вставка до v445). */
    fun passAdvancing() {
        // лекари: в лечебной дальности от раненого бойца, вне огня следующего тика
        // ИДУЩИЙ ВПЕРЁД БОЕЦ В РЕЖИМ ТОЧНОЙ ЦЕНЫ НЕ ВХОДИТ (сужение v439, см. InfluenceMap.advancingWards): его назначенная клетка —
        // (с v449 множество живёт в записи раздачи — `rec.need.advancingWards`, класс `InfluenceMap.HealNeed`)
        // или, когда бойцов ведёт тактик (раздача одних лекарей), его ход прошлого тика — ближе к его стволам, чем нынешняя
        rec.need.advancingWards.clear()
        if (USE_HEAL_EXACT_IN_FIRE && armedEnemies.isNotEmpty()) for (f in fighters) {
            if (!hasWeapon(f)) continue
            val planned = out[f.id]
            val nowD = armedEnemies.minOf { getRange(f, it) }
            if (planned != null && !(planned.x == f.x && planned.y == f.y)) {
                if (armedEnemies.minOf { maxOf(abs(planned.x - it.x), abs(planned.y - it.y)) } < nowD) rec.need.advancingWards.add(f.id)
            } else {
                val prev = Memory.lastCell[f.id] ?: continue
                val px = prev / 100; val py = prev % 100
                if (px == f.x && py == f.y) continue
                if (armedEnemies.minOf { maxOf(abs(px - it.x), abs(py - it.y)) } > nowD) rec.need.advancingWards.add(f.id)
            }
        }
    }

    fun passHealer() {
        for (c in healers) {
            // ЛЕКАРЬ ПРИ БОЙЦЕ (v142): близость главная, опасность лишь тай-брейк — прежний порядок весил опасность
            // стократно, и лекарь уходил в безопасную клетку ВНЕ дальности лечения; фолбэк вёл его туда же
            // ...и САМАЯ БЕЗОПАСНАЯ ИЗ ТЕХ, ОТКУДА ДОСТАЁТ (v183). Ранг вёл лекаря к БЛИЖАЙШЕМУ раненому (дистанция
            // ×100, опасность — тай-брейк), а ближайший раненый — тот, кто в контакте, поэтому лекарь шёл в рубку.
            // Замер тестовой игры 3d95ad: наши лекари в трёх клетках от его вооружённого 84 крипо-тика из 107 (78 %),
            // его — 64 из 129 (49 %); вплотную наши 21 против его 4, выстрелов по нашим лекарям 52 против 18 по его.
            // Первым умирал лекарь (t=64), и с ним рассыпался весь бой. Условие «достаёт» уже задано отбором клеток
            // (HEAL_RANGE − 1 с запасом на его шаг), поэтому внутри отбора решать должна опасность, а не близость —
            // прежний вариант с опасностью ×100 (USE_HEALERS_CLOSE=false) уводил лекаря ВНЕ дальности, потому что
            // менял вместе с рангом и сам отбор
            // ...и «безопасная» НЕ ЗНАЧИТ «на краю дальности»: вплотную лекарь лечит вчетверо сильнее, чем издали, и
            // ранг по одной опасности выталкивал его на два шага, где лечение падает втрое, — сценарий brawl+heals
            // проседал 12 056:17 958. Порядок такой же, как у него: сперва клетка ВПЛОТНУЮ К СВОЕМУ (полное лечение),
            // и среди таких — самая безопасная. Его лекари стоят рядом со своими стрелками 60 % крипо-тиков и при
            // этом в трёх клетках от наших вооружённых лишь 49 % — быть при своих и быть под огнём это разные вещи
            // ЛЕКАРЬ СТОИТ ПРИКРЫТЫМ, А НЕ В ПУСТОЙ КЛЕТКЕ (v186, разбор Coldkimchi#2). Тело лекаря — `h6m6`, лечащие
            // части СПЕРЕДИ, поэтому урон уничтожает именно их и делает это первыми. Замер разгрома 3d97c4: у обеих
            // сторон одно и то же тело, его лекари сохраняют лечащие части 804 крипо-тика из 804 (100 %), наши — 72
            // из 804 (8 %). К семидесятому тику двое наших из трёх уже без лечения вовсе, и дальше армия тает: лечение
            // за бой 4 604 против его 14 256, он заканчивает матч с 16 000 из 16 000 хитов, не потеряв ничего.
            // Разница не в открытости, а в ПРИКРЫТИИ: его лекари стоят ВНУТРИ строя (рядом с мили 86 % крипо-тиков,
            // со стрелками 63 %), наши — на фланге (16 % и 15 %) при глубине −2,7 и разбросе 3,6. «Самая безопасная
            // клетка» (v183) как раз и уводила их на фланг: пустая клетка безопаснее по входящему, но её достаёт его
            // стрелок, а тела своих не заслоняют. Здесь считается число СВОИХ вплотную — тех, кто примет выстрел
            // ЛЕКАРЬ НЕ ВСТАЁТ ВПЕРЕДИ СТРОЯ (v200, оператор): «наши хилеры оказались в первой линии и моментально
            // получили несколько выстрелов и потеряли возможность лечения». Экран (v186) считает ТЕЛА вокруг клетки и
            // ничего не знает о глубине, а `mates` — это раненые, то есть ровно те, кто на фронте: ранг тянул лекаря
            // вперёд по построению. Замер реплея 3d9943: глубина по оси на врага у мили −1,35, у стрелков +0,68, у
            // ЛЕКАРЕЙ +0,32 — они впереди мили, и в 155 тиках из 335 лекари в среднем ближе к врагу, чем мили; на
            // t=63, через три тика после контакта, один лекарь уже без лечащих частей. Условие простое и жёсткое:
            // хотя бы один свой боец стоит к врагу БЛИЖЕ, чем клетка лекаря, — считая по уже назначенным клеткам
            // «другие» для адресной угрозы t+1 (v478) — остальные наши на назначенных клетках; считается раз на лекаря
            if (USE_HEAL_THREAT_NEXT) threatNext.prepare(c, living(army)) { f -> cellOf(f) }
            val met = meetWounded(c)
            // лекаря, поставленного проходом отхода, оценка по-прежнему переставляет — не встреча, не трогается (первая
            // редакция v276 это переразмещение снимала попутно, и гейт переменил 89 строк и уронил match30:camp)
            // ПРИБОР РЕЖИМА «В ЗОНЕ ОГНЯ» (v438, `hfire=`): лекарей, у которых доставка считалась по подопечным под огнём / всех /
            // из первых — поставленных вплотную к теряющему хиты; снимается ДО раздачи — насыщение меняет режим следующему
            val fireMode = !met && rec.need.deliveryFireMode(c, living(army))
            rec.hfireAll.n++; if (fireMode) rec.hfireN.n++
            if (!met) placeScored(c, 2, intentOf(c)).also { placed ->
                if (placed) out[c.id]?.let { rec.need.saturateHeal(c, it.x, it.y, living(army)) }
            }
            // ...и добор тоже вне досягаемости, пока такая клетка есть (v234)
            if (c.id !in out) place(c, { true }, { p -> danOf(c, p.key) })
            adjacencyGauge(c, fireMode)
            pickProbe(c)
        }
    }

    /** СЕКЦИЯ `passHealer`: встреча раненого с лекарём; `true` — лекарь поставлен вплотную к клетке, куда уходит раненый. */
    private fun meetWounded(c: Creep): Boolean {
        // ВСТРЕЧА РАНЕНОГО С ЛЕКАРЁМ (v276, разбор v275: наш уходящий раненый за пять тиков получает 198 урона при 144
        // лечения, его — 76 при 144; его лекарь сходится с раненым с трёх клеток до вплотную за пять тиков, а наш стоял там,
        // куда его поставила оценка поля нужды — по опасности у подопечного, которой у ушедшего из-под огня уже нет). Лекарь,
        // который за шаг встаёт вплотную к клетке, куда уходит раненый по его фокусу (Memory.rotByFocus, клетка — из
        // прохода отхода), встаёт туда, если переживёт её с порогом своего замысла; раненый — ближайший ещё без лекаря
        val medicFor = rotatingMeet.entries.filter { (rid, dest) -> rid !in medicked && getRange(c, dest) <= 2 }
            .minByOrNull { getRange(c, it.value) }
        var met = false
        if (medicFor != null) {
            val dest = medicFor.value
            val (_, _, ttlMin) = weightsOf(intentOf(c))
            if (place(c, { p -> getRange(p, dest) <= 1 && ttlAt(c, p.key, p) >= ttlMin }, { p -> danOf(c, p.key) })) {
                medicked.add(medicFor.key)
                rec.rotfMeet.n++
                met = true
                out[c.id]?.let { rec.need.saturateHeal(c, it.x, it.y, living(army)) }
            }
        }
        return met
    }

    /** СЕКЦИЯ `passHealer`: приборы прилегания назначенной клетки лекаря (`hadj=`, `hadjn=`, `hfire=` вплотную). */
    private fun adjacencyGauge(c: Creep, fireMode: Boolean) {
        // ПРИБОР ПРИЛЕГАНИЯ (v435, `hadj=`): назначенная клетка лекаря вплотную к своему, терявшему хиты за прошлый тик, /
        // все назначения лекарей — та величина, по которой разбор E делил стороны (26 % лечений вплотную против 75 %)
        out[c.id]?.let { b ->
            rec.hadjAll.n++
            val losing = army.filter { a -> a.id != c.id && a.hits > 0 && (Memory.lastHits[a.id] ?: a.hits) > a.hits }
            if (losing.any { a -> maxOf(abs(a.x - b.x), abs(a.y - b.y)) <= 1 }) { rec.hadjN.n++; if (fireMode) rec.hfireAdj.n++ }
            // ...и НОРМИРОВАННЫЙ прибор (`hadjn=`): среди назначений, при которых кто-то из своих в дальности шага и
            // лечения (HEAL_RANGE + 1) терял хиты, — доля клеток вплотную к такому; без него hadj делится и на тихие тики
            if (losing.any { a -> getRange(a, c) <= HEAL_RANGE + 1 }) {
                rec.hadjnAll.n++
                if (losing.any { a -> maxOf(abs(a.x - b.x), abs(a.y - b.y)) <= 1 }) rec.hadjnN.n++
            }
        }
    }

    /** СЕКЦИЯ `passHealer`: зонд раздачи лекарей (`hpick=`) — какое слагаемое оценки увело лекаря от клетки вплотную к бойцу первой линии. */
    private fun pickProbe(c: Creep) {
        // ЗОНД РАЗДАЧИ ЛЕКАРЕЙ (v224, `hpick=`): по реплеям обеих сторон его лекари стоят вплотную к крипу под нашим
        // огнём 37 % лекаре-тиков, наши — 10 %, и в FIGHT свободная клетка вплотную к бойцу не опаснее своей есть в
        // 30–51 % лекаре-тиков. Зонд отвечает, какое слагаемое оценки увело лекаря от такой клетки: считает те же
        // слагаемые, что scoreHeal и place, для выбранной клетки и для лучшего свободного кандидата вплотную к бойцу
        // вне его стрелкового огня, и копит разницу «выбранная минус кандидат» по слагаемым
        val b = out[c.id] ?: return
        val (att, dan, ttlMin) = weightsOf(intentOf(c))
        // кандидат — вплотную к бойцу ПЕРВОЙ ЛИНИИ (его клетка в его стрелковом огне) и сам вне огня: первое
        // чтение зонда (4 игры) показало, что «вплотную к любому бойцу вне огня» выбирается в 63 % раздач — строй
        // глубокий, боец рядом есть всегда, — а к тому, кого бьют, лекарь по реплеям стоит в 10 %
        fun adjSafe(p: Position): Boolean = foeDist(p.x, p.y) > RANGED_RANGE &&
            fighters.any { f -> f.id != c.id && hasWeapon(f) && cellOf(f).let { foeDist(it.x, it.y) <= RANGED_RANGE && maxOf(abs(it.x - p.x), abs(it.y - p.y)) <= 1 } }
        fun terms(p: Position): DoubleArray {
            val key = p.key
            val scr = screenAt(c, p)
            val fireModeNow = USE_HEAL_THREAT_NEXT && rec.need.deliveryFireMode(c, army)   // те же слагаемые, что у scoreHeal (v478)
            val fire = if (fireModeNow) threatNext.at(c, p.x, p.y) else InfluenceMap.fireFieldAt(key)
            val shielded = if (fireModeNow) 0.0 else fire * (1.0 - 1.0 / (1.0 + SCREEN_SHARE * scr))
            val deliver = InfluenceMap.healOf(c)
            val raw = rec.need.attHealAt(key)
            val pull = if (USE_HEAL_NEED_ACTUAL) rec.need.bestDeliveryAt(c, p.x, p.y, army)
                else if (deliver <= 0.0 || raw <= 0.0) 0.0 else deliver * raw / (raw + deliver)
            val self = p.x == c.x && p.y == c.y
            val tenant = if (self) null else tenantOf(key, c)
            return doubleArrayOf(-W_ATT * att * pull, W_DAN * dan * fire, if (USE_HEAL_NO_LINE && (pull > 0.0 || rec.need.deliveryFireMode(c, army))) 0.0 else -W_LINE * InfluenceMap.influenceOf(key),
                -W_SCREEN * shielded, CLAIM_COST * claimAt(key), -stayBonus(c, p),
                if (tenant != null) ALLY_CELL_COST else 0.0, GOAL_STEP_COST * goalCost(key))
        }
        rec.hpN.n++
        if (adjSafe(b)) { rec.hpAdj.n++; return }
        var best: Position? = null; var bestSc = Double.MAX_VALUE; var gated = 0
        for ((key, p) in nearCells(c)) {
            if (p.x == b.x && p.y == b.y) continue
            if (key in taken || !adjSafe(p)) continue
            if (!(p.x == c.x && p.y == c.y) && tenantOf(key, c) != null) continue
            if (ttlAt(c, key, p) < ttlMin) { gated++; continue }
            val sc = terms(p).sum()
            if (sc < bestSc) { bestSc = sc; best = p }
        }
        val a = best
        if (a == null) { if (gated > 0) rec.hpGate.n++; return }
        rec.hpAvail.n++
        val tb = terms(b); val ta = terms(a)
        for (i in tb.indices) rec.hpDelta[i] += tb[i] - ta[i]
    }

    /** Проход `keeper`: стоящий на нашем флаге без приказа получает «стой» (безымянная вставка до v445). */
    fun passKeeper() {
        // ХРАНИТЕЛЬ ФЛАГА — ПО ПРИКАЗУ (v174): крип на НАШЕМ флаге стоит по приказу командира, а не по отдельной ветке
        // удержания; снять его может только командир — решив собрать отряд — или опасность, которая приходит приказом
        for (c in fighters) {
            if (c.id in out) continue
            // ...и клетку, уже отданную кому-то приказом, хранитель не занимает повторно: без этой проверки одна
            // клетка доставалась двоим — прибор ловил это как clash=1 (v176)
            // ...и приказ пишется ТОЙ ЖЕ операцией, что у остальных проходов (v471, дефект 10 постановки): прямая запись в
            // `taken` и `out` обходила `commit` — проход не попадал в `pass=` (вопрос 5 смотрел на слепой счётчик), хранитель не
            // давал притязания (`claimAt`) и не входил в `adr=`
            val key = c.key
            if (ourFlagCells.contains(key) && key !in taken) commit(c, InfluenceMap.cell(c.x, c.y), 0)
        }
    }

    fun passStripped() {
        // раздетые: прочь из огня — в бою от них пользы нет, а его выстрелы они на себя собирают исправно
        for (c in (if (healersOnly) emptyList() else stripped))
            place(c, { true }, { p -> danOf(c, p.key) * 100 -
                (armedEnemies.minOfOrNull { getRange(p, it) } ?: 0).toDouble() })
    }

    fun passCatchall() {
        // ...и ВООРУЖЁННЫЙ не остаётся без места (v165): расстановка при командире молчит, и тот, кому клетки не
        // хватило, уходил по общим веткам — гейт ловил это как уничтоженную армию (match32:army). Лекарей и раздетых
        // этот добор не трогает: у них свои назначения выше, и перехват их портил (133 из 135)
        for (c in melees + rangeds) {
            if (c.id in out) continue
            place(c, { true }, { p -> danOf(c, p.key) * 10 +
                maxOf(abs(p.x - c.x), abs(p.y - c.y)).toDouble() })
        }
    }

    fun passPinned() {
        // ЗАЖАТОГО БЬЁМ (v264, разбор стены лечения серии v263). Против стоячей линии с лекарями наш урон возвращается
        // лечением на 81–99 %, и пересиливает его одно — удар нашего мили: 192–240 за удар против 71–91 его лечения на
        // цели в тик; из девяти его погибших боевых восемь умерли с уроном нашего мили. Но его линия отступает на нашей
        // скорости, и погоня касания не даёт: со двух клеток вплотную к следующему тику 3–20 %, а нырок к лекарю — 550
        // подходов без единой смерти и без просадки его лечения. Касание дают моменты, когда уйти ему НЕКУДА (см.
        // pinnedAt): тогда в следующем тике он в досягаемости клетки удара всегда — 71 из 71 по реплеям, — а мы вставали
        // туда в 16–57 %, потому что оценка у его строя весит опасность клетки выше удара. Проход идёт ПОСЛЕДНИМ: только
        // здесь план остальных окончателен, и «наш крип закрывает ему отход» значит его ИТОГОВУЮ клетку, а не нынешнюю,
        // которую он вот-вот освободит (первая редакция шла до стрелков и лекарей и ставила мили к «зажатому», которого
        // их же шаг отпускал). Мили, которого оценка уже поставила вплотную к зажатому, не трогается; остальной получает
        // клетку удара, если переживёт её с порогом своего замысла, иначе прежний приказ. Порог — тот же ttlMin, что у
        // оценки, новых чисел нет; кайт свой запрет сохраняет — к его мили ближе двух не подходит
        val pinFoes = combatEnemies.filter { e -> InfluenceMap.profileOf(e).let { it.melee + it.ranged + it.heal > 0.0 } }
        val hisStuck = HashSet<Int>()
        for (e in combatEnemies) if (e.fatigue > 0) hisStuck.add(e.key)
        for (c in (if (healersOnly) emptyList() else melees).sortedBy { c -> armedEnemies.minOfOrNull { getRange(c, it) } ?: 99 }) {
            val near = pinFoes.filter { getRange(c, it) <= COMMAND_REACH + 1 }
            if (near.isEmpty()) continue
            val ours = HashSet<Int>()
            for (f in army) if (f.hits > 0 && f.id != c.id) { val q = cellOf(f); ours.add(q.key) }
            fun strikes(p: Position) = near.any { e -> getRange(p, e) <= 1 && pinnedAt(p, e, ours, hisStuck) }
            val cur = out[c.id]
            if (cur != null && strikes(cur)) continue
            val ttlMin = weightsOf(intentOf(c)).third
            val kite = intentOf(c) == Intent.KITE
            if (cur != null) { out.remove(c.id); taken.remove(cur.key) }
            val ok = place(c, { p ->
                (!kite || hisMelee.isEmpty() || hisMelee.minOf { getRange(p, it) } >= MELEE_HOLD_RANGE) &&
                    strikes(p) && ttlAt(c, p.key, p) >= ttlMin
            }, { p -> danOf(c, p.key) })
            if (!ok && cur != null) { out[c.id] = cur; taken.add(cur.key) }
        }
    }

    /** Проход `audit`: три числа согласованности строя — приборы plan* (безымянный эпилог до v445). */
    fun passAudit() {
        // ПРИБОР СОГЛАСОВАННОСТИ (v200): меряется РЕЗУЛЬТАТ раздачи, а не факт вызова правила — сколько стрелков
        // получили клетку с целью в дальности, сколько мили остались в дальности лечения, сколько лекарей стоят за
        // линией. Три числа отвечают ровно на три замечания оператора и видны в строке `t=` каждым тиком
        rec.planGunsAll.n = rangeds.size
        rec.planGunsIn.n = rangeds.count { c -> out[c.id]?.let { p -> armedEnemies.any { e -> getRange(p, e) <= RANGED_RANGE } } == true }
        rec.planMeleeAll.n = melees.size
        rec.planMeleeHealed.n = melees.count { c -> out[c.id]?.let { p -> healerCells.any { h -> maxOf(abs(p.x - h.x), abs(p.y - h.y)) <= HEAL_RANGE } } == true }
        rec.planHealAll.n = healers.size
        rec.planHealBehind.n = healers.count { c ->
            val p = out[c.id] ?: return@count false
            val dp = foeDist(p.x, p.y)
            fighters.any { f -> f.id != c.id && hasWeapon(f) && cellOf(f).let { foeDist(it.x, it.y) } < dp }
        }
        // БЕЗ ПРИКАЗА (v476, вопрос 3): кто по концу проходов остался без клетки. `catchall` добирает строевых, но `place` может
        // не найти клетки, а спасение (`rescue`) и проход `pinned` снимают ЧУЖОЙ приказ уже после добора; лекарей и раздетых
        // добор не трогает вовсе. Считается здесь, в последнем проходе, где план окончателен. Строевой, у которого в шаге нет ни
        // одной клетки-кандидата (кулак отрезал — он вне FIST_RADIUS от медианы), приказа не получает по построению; вторая
        // часть считает тех, у кого кандидат в шаге был
        val noLineList = (melees + rangeds).filter { it.id !in out }
        val noReach = noLineList.count { c -> cells.values.any { p -> abs(p.x - c.x) <= COMMAND_REACH && abs(p.y - c.y) <= COMMAND_REACH } }
        val noHeal = healers.count { it.id !in out }
        val noStrip = stripped.count { it.id !in out }
        rec.unplacedLine.n += noLineList.size; rec.unplacedReach.n += noReach
        rec.unplacedHeal.n += noHeal; rec.unplacedStrip.n += noStrip
        if (noLineList.size + noHeal + noStrip > 0) rec.unplacedDeals.n++
        // ПРИБОР ПРОГНОЗА ЛЕКАРЯ (v478, `hnext=`): подопечных под огнём t+1 (в назначенной клетке) / из них не под огнём сейчас /
        // под огнём сейчас, но не t+1 — где прогноз расходится с настоящим, то есть где правка вообще действует
        if (USE_HEAL_THREAT_NEXT) for (a in living(army)) {
            val q = cellOf(a)
            val nextF = threatNext.reaches(q.x, q.y); val nowF = InfluenceMap.fireFieldAt(a.key) > 0.0
            if (nextF) rec.hnextWards.n++
            if (nextF && !nowF) rec.hnextOnlyNext.n++
            if (!nextF && nowF) rec.hnextOnlyNow.n++
        }
    }

    /** ПРОХОДЫ РАЗДАЧИ: порядок списка = порядок исполнения, другого описания порядка нет. Имя прохода — тег прибора `pass=`
     *  (его печатает `rung t=`): клетка, выданная `place` на глубине 0, записывается на проход, который сейчас идёт. */
    val passes = listOf(
        Pass("freeze") { passFreeze() },
        Pass("retreat") { passRetreat() },
        Pass("straggler") { passStraggler() },
        Pass("fields") { passFields() },
        Pass("melee") { passMelee() },
        Pass("ranged") { passRanged() },
        Pass("advancing") { passAdvancing() },
        Pass("healer") { passHealer() },
        Pass("keeper") { passKeeper() },
        Pass("stripped") { passStripped() },
        Pass("catchall") { passCatchall() },
        Pass("pinned") { passPinned() },
        Pass("audit") { passAudit() },
    )

    // не `run`: внутри носителя зовётся stdlib-`run { … }` (зонд hpick), и одноимённый член когда-нибудь перехватил бы его молча
    fun distribute() = runPasses(passes, rec.tally) { i, tag -> passIndex = i; passTag = tag }
}
