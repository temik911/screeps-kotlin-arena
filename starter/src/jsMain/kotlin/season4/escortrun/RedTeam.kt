package season4.escortrun

import screeps.api.BodyPartType
import screeps.api.CARRY
import screeps.api.ConstructionSite
import screeps.api.Creep
import screeps.api.MOVE
import screeps.api.Position
import screeps.api.RESOURCE_ENERGY
import screeps.api.WORK
import screeps.api.createConstructionSite
import screeps.api.get
import screeps.api.getObjectsByPrototype
import screeps.api.getRange
import screeps.api.structures.StructureContainer
import screeps.api.structures.StructureRampart

/**
 * Красная команда (docs/escort-run-redteam.md): приёмы, которыми наш же бот пытается обыграть свою прошлую версию.
 * Личность посылки — строка приёмов через «+» (`plug`, `squat+plug`); `main` — ни одного. Приём заказывается один раз,
 * раньше дебюта основной логики (его энергия — плата за приём, как у настоящего соперника), и его крипа ведёт только
 * RedTeam: основная логика исключает его из своих ролей (id берётся при рождении по телу заказа, как у разведчиков —
 * id из spawnCreep в Арене нет).
 *
 * Приёмы:
 *  - `squat` — M1 первым заказом идёт на чужой флаг и стоит на нём; если флаг занят — на соседней клетке кармана,
 *    ближайшей к их эскорту;
 *  - `plug` — строитель W1C4M4 (500) берёт 200 энергии из контейнера у правого края и ставит НАШ рампарт на клетку
 *    чужого флага: рампарт пропускает только владельца и строится под крипом, поэтому их хранитель на флаге стройке
 *    не мешает, а их эскорт на флаг больше не войдёт;
 *  - `blk` — тот же захватчик чужого флага, но ПОСЛЕ дебюта, первым заказом (так играл ShuP1#3: его M1 на 51-м сел на
 *    наш флаг раньше хранителя v23, 6ab8fcb0);
 *  - `chase` — M1 после дебюта всегда стоит на их пути в CHASE_AHEAD клетках впереди поезда, не выбирая узких мест:
 *    держит клетку, пока поезд её не прошёл, и перебегает (стендовый `chk` так обыгрывал v24 три раза из четырёх);
 *  - `rush` — то же, но ДО дебюта и вместо него (экономика без гонки, как けろびー#29, у которого эскорт весь матч дома);
 *  - `icpt` — перехватчик stachu3478 при СВОЕЙ гонке: M1A1 после дебюта идёт к их поезду и бьёт тягачей (без них
 *    эскорт — 4 тика на клетку), потом эскорт (стендовая строка match4:icpt открыта с v12);
 *  - `camp` — ricardo18informatica2020#23 (обыграл v28 0-6, 28.09.2026): M4A3 на первом тике — телохранитель своего
 *    эскорта (идёт рядом, бьёт подошедших на четыре клетки от эскорта); тягачей и разведчиков нет, наш эскорт идёт сам;
 *  - `guard` — тот же телохранитель, что `camp`, но с броском (реплеи ricardo#23/#25, 28.09.2026: 6abaac66, 6abaae9e,
 *    6abaaf41): когда их поезд (эскорт или тягач) подходит к нему на LUNGE_RANGE, M4A3 бросает свой эскорт и гонится
 *    за поездом до конца, охотясь на ТЯГАЧЕЙ (ближний тягач — цель, эскорт — когда тягачей нет). По 15 играм v30 он
 *    бросался в шести — везде поезд подходил на 5 (в одной на 6); где поезд держался в 6–8, броска не было, а наших
 *    разведчиков M1 он подпускал и на 1–2 клетки. В стенде их эскорт приходит к развилке на пару тиков позже живого,
 *    поезд проходит на 2–3 клетки дальше, и с 5 броска не бывает вовсе (с 7 — 2 руки из 30); LUNGE_RANGE 8 — худший
 *    случай, бросок в каждой руке;
 *  - `rush7` — рашер ricardo18informatica2020#7 (обыграл v37 в охоте 29.09.2026, 6abb805d): M4A3 на первом тике и
 *    больше ничего (наш эскорт идёт сам); с рождения идёт прямо на их эскорт — обгоняя свой, через болотистый центр
 *    (живьём там клетка в четыре тика, 60–87-й), — и бьёт только эскорт (тягач M4 рядом не тронут). Их эскорт вышел
 *    из дома на ~75-м, встреча на ~118-м, эскорт погиб на 192-м — поезд шёл дальше под ударами;
 *  - `wall` — therevilo2018#3 (ничья с v40 29.09.2026, 6abb9f19): R1M1 первой тратой и дальше R1M1 раз за разом (на
 *    доход ECON2 стенда); каждый встаёт на свободную клетку ИХ пути, ближайшую к центру, стоит и стреляет по слабейшему в
 *    трёх клетках. Живьём к ~600-му пять–девять R1M1 стояли поперёк развилки (52,45)…(48,50), наш поезд без пути стоял
 *    до 2000-го. Ни тягачей, ни разведчиков: их эскорт идёт сам;
 *  - `rsq` — Suruks#2 (обыграл v30 в подтверждающей серии, 28.09.2026, 6abac117): стрелок M2R2 после дебюта идёт к ИХ
 *    флагу, стреляет по всем в трёх клетках (хранитель — первым) и садится на флаг; их эскорт в трёх тиках от финиша
 *    упирается в занятую клетку. Как у Suruks: ни тягачей, ни разведчиков (эскорт идёт сам), стрелок — на RSQ_AT-м тике
 *    (живьём 107–109-й, на флаге к 238–256-му);
 *  - `kk` — убийца хранителя: M1A1 после дебюта идёт к их флагу, бьёт стоящих на нём и рядом и сам встаёт на флаг
 *    вооружённым захватчиком;
 *  - `army` — после остальных приёмов, раз за разом: стрелок M5R5 охотится на их эскорт, по дороге бьёт тягачей
 *    (вместе с ECON2 стенда — экономика, которой бот не играет: так выглядел бы けろびー#29 с блокировщиками);
 *  - `choke` — M1 ПОСЛЕ дебюта встаёт на клетку впереди их поезда, где обход дороже всего (болото вокруг узкого
 *    прохода), и туда, куда успевает раньше поезда; когда поезд обошёл — перебегает на следующую (M1 ходит клетку в
 *    тик, поезд — в два). Замер по 120 маршрутам: один такой крип стоит поезду медианно 18 тиков, два — 36.
 */
internal object RedTeam {

    private val ORDER = listOf("camp", "guard", "rush7", "wall", "rsq", "rush", "squat", "plug", "icpt", "kk", "choke", "blk", "chase", "army")
    /** Приёмы, заказываемые после дебюта основной логики (остальные — раньше него). */
    private val LATE = setOf("icpt", "kk", "choke", "blk", "chase", "army")
    private const val RAMPART_COST = 200
    private const val RSQ_AT = 107
    private val RSQ_ECO = listOf(arrayOf<BodyPartType>(CARRY, MOVE), arrayOf<BodyPartType>(WORK, WORK, WORK))
    private var rsqEco = 0
    private const val BUILD_RANGE = 3

    private var tricks: Set<String> = emptySet()
    private val ordered = HashSet<String>()
    private val owned = HashMap<String, String>() // id -> приём
    private var pendingBody: String? = null
    private var pendingTrick: String? = null
    private var plugDoneAt = -1

    fun configure(persona: String) {
        tricks = persona.split('+').map { it.trim() }.filter { it.isNotEmpty() && it != "main" }.toSet()
    }

    fun describe(): String = if (tricks.isEmpty()) "main" else ORDER.filter { it in tricks }.joinToString("+")

    private fun idOf(c: Creep) = "${c.asDynamic().id}"

    fun owns(c: Creep) = idOf(c) in owned

    /** Рождающийся крип с телом последнего заказа — наш. */
    fun claim(mine: List<Creep>) {
        val body = pendingBody ?: return
        val c = mine.firstOrNull { it.spawning && idOf(it) !in owned && Bodies.summaryOf(it) == body } ?: return
        owned[idOf(c)] = pendingTrick ?: "?"
        pendingBody = null
        pendingTrick = null
    }

    private fun bodyOf(trick: String): Array<BodyPartType> = when (trick) {
        "squat", "choke", "blk", "chase" -> arrayOf(MOVE)
        // армия: стрелок M5R5 (1000) — достаёт эскорт с трёх клеток, 50 урона в тик; повторяется, пока идёт матч
        "army", "rush" -> Array(5) { MOVE } + Array(5) { screeps.api.RANGED_ATTACK }
        "icpt", "kk" -> arrayOf(MOVE, screeps.api.ATTACK)
        "rsq" -> arrayOf(MOVE, MOVE, screeps.api.RANGED_ATTACK, screeps.api.RANGED_ATTACK)
        "wall" -> arrayOf(screeps.api.RANGED_ATTACK, MOVE)
        "camp", "guard", "rush7" -> arrayOf(MOVE, MOVE, MOVE, MOVE, screeps.api.ATTACK, screeps.api.ATTACK, screeps.api.ATTACK)
        "plug" -> arrayOf(WORK, CARRY, CARRY, CARRY, CARRY, MOVE, MOVE, MOVE, MOVE)
        else -> emptyArray()
    }

    /** true — спавн занят приёмом в этот тик (заказал или копит на него): основная логика ждёт. [late] — после дебюта. */
    fun spawn(w: EscortRun.World, energy: Int, late: Boolean): Boolean {
        if (tricks.isEmpty()) return false
        // camp (ricardo18informatica2020#23): один M4A3 и больше НИЧЕГО — ни тягачей, ни разведчиков, спавн копит
        if (listOf("camp", "guard", "rush7", "rsq").any { it in tricks && it in ordered }) return true
        if ("wall" in tricks) {
            if (pendingBody != null) return true
            val body = bodyOf("wall")
            if (energy >= Bodies.cost(body) && EscortRun.order(w, body, "red:wall", "persona ${describe()}")) {
                pendingBody = Bodies.summary(body); pendingTrick = "wall"
            }
            return true
        }
        if ("rsq" in tricks && w.now < RSQ_AT) {
            // Suruks#2 открывается экономикой — C1M1 и W3 первой тратой (живой лог: «their first C1M1 W3»); тела только для
            // вида, доход даёт ECON2 стенда. Без них наш дебют против этой личности решался по пустой первой трате
            val next = RSQ_ECO.getOrNull(rsqEco) ?: return true
            if (pendingBody != null) return true
            if (energy >= Bodies.cost(next) && EscortRun.order(w, next, "red:rsq-eco", "persona ${describe()}")) {
                rsqEco++; pendingBody = Bodies.summary(next); pendingTrick = "rsq-eco"
            }
            return true
        }
        if (pendingBody != null) return false
        for (t in ORDER) {
            // при затычке гонки нет — поздние приёмы идут до дебюта, иначе они ждали тягачей до 200-го тика
            val isLate = t in LATE && "plug" !in tricks
            if (t !in tricks || t in ordered || isLate != late) continue
            val body = bodyOf(t)
            if (energy < Bodies.cost(body)) return true
            if (EscortRun.order(w, body, "red:$t", "persona ${describe()}")) {
                if (t != "army" && t != "rush") ordered.add(t)
                pendingBody = Bodies.summary(body)
                pendingTrick = t
            }
            return true
        }
        return false
    }

    fun act(w: EscortRun.World) {
        if (owned.isEmpty()) return
        val alive = w.active.associateBy { idOf(it) }
        for ((id, trick) in owned) {
            val c = alive[id] ?: continue
            when (trick) {
                "squat", "blk" -> squat(w, c)
                "plug" -> plug(w, c)
                "choke" -> choke(w, c)
                "chase" -> chase(w, c)
                "army", "rush" -> army(w, c)
                "icpt" -> icpt(w, c)
                "camp" -> camp(w, c)
                "guard" -> guard(w, c)
                "rush7" -> rush7(w, c)
                "wall" -> wall(w, c)
                "kk" -> keeperKiller(w, c)
                "rsq" -> rangedSquatter(w, c)
            }
        }
    }

    private fun squat(w: EscortRun.World, c: Creep) {
        val flag = w.enemyFlag ?: return
        val onFlag = c.x == flag.x && c.y == flag.y
        if (onFlag) { log(w, c, "squat", "on flag"); return }
        val occupied = w.occupant[flag.x * 100 + flag.y]
        if (occupied == null) { EscortRun.stepRed(w, c, flag, 0); log(w, c, "squat", "to flag"); return }
        // флаг занят — клетка кармана, ближайшая к их эскорту (через неё он придёт)
        val esc = w.enemyEscort
        val cells = ArrayList<Pair<Int, Int>>()
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            val x = flag.x + dx; val y = flag.y + dy
            if (!DistanceMap.inBounds(x, y) || DistanceMap.isWall(x, y)) continue
            val o = w.occupant[x * 100 + y]
            if (o != null && idOf(o) != idOf(c)) continue
            cells.add(x to y)
        }
        val best = cells.minByOrNull { (x, y) -> if (esc != null) maxOf(kotlin.math.abs(esc.x - x), kotlin.math.abs(esc.y - y)) else 0 }
        if (best == null) { log(w, c, "squat", "pocket full"); return }
        if (c.x == best.first && c.y == best.second) { log(w, c, "squat", "on pocket (${best.first},${best.second})"); return }
        EscortRun.stepRed(w, c, pos(best.first, best.second), 0)
        log(w, c, "squat", "to pocket (${best.first},${best.second})")
    }

    private fun plug(w: EscortRun.World, c: Creep) {
        val flag = w.enemyFlag ?: return
        val ours = getObjectsByPrototype(StructureRampart::class).firstOrNull { it.exists && it.my == true && it.x == flag.x && it.y == flag.y }
        if (ours != null) {
            if (plugDoneAt < 0) { plugDoneAt = w.now; println("red t=${w.now} plug: RAMPART on their flag (${flag.x},${flag.y}) hits=${ours.hits}") }
            log(w, c, "plug", "done at $plugDoneAt")
            return
        }
        val site = getObjectsByPrototype(ConstructionSite::class).firstOrNull { it.exists && it.my == true && it.x == flag.x && it.y == flag.y }
        val carried = c.store[RESOURCE_ENERGY] ?: 0
        val need = site?.let { (it.progressTotal ?: RAMPART_COST) - (it.progress ?: 0) } ?: RAMPART_COST
        if (carried == 0 || (carried < need && getRange(c, flag) > BUILD_RANGE)) {
            val box = getObjectsByPrototype(StructureContainer::class)
                .filter { it.exists && (it.store[RESOURCE_ENERGY] ?: 0) > 0 }
                .minByOrNull { getRange(c, it) }
            if (box == null) { log(w, c, "plug", "no container"); return }
            if (getRange(c, box) <= 1) {
                val r = c.withdraw(box, RESOURCE_ENERGY)
                log(w, c, "plug", "withdraw r=$r")
            } else {
                EscortRun.stepRed(w, c, box, 1)
                log(w, c, "plug", "to container (${box.x},${box.y})")
            }
            return
        }
        if (getRange(c, flag) > BUILD_RANGE) { EscortRun.stepRed(w, c, flag, BUILD_RANGE - 1); log(w, c, "plug", "to flag e=$carried"); return }
        if (site == null) {
            val r = createConstructionSite(flag.x, flag.y, StructureRampart::class.js)
            println("red t=${w.now} plug: site on (${flag.x},${flag.y}) err=${r.error} ok=${r.`object` != null}")
            return
        }
        val r = c.build(site)
        log(w, c, "plug", "build ${site.progress}/${site.progressTotal} r=$r e=$carried")
    }

    // ---------- choke ----------

    private fun army(w: EscortRun.World, c: Creep) {
        val esc = w.enemyEscort ?: return
        val near = w.enemies.filter { getRange(c, it) <= 3 }
        val shot = if (getRange(c, esc) <= 3) esc else near.minByOrNull { it.hits }
        if (shot != null) c.rangedAttack(shot)
        if (getRange(c, esc) > 2) EscortRun.stepRed(w, c, esc, 2)
        log(w, c, "army", "hunting escort h=${esc.hits}")
    }

    private fun icpt(w: EscortRun.World, c: Creep) {
        val esc = w.enemyEscort ?: return
        val pullers = w.enemies.filter { it !== esc && Bodies.isPuller(it, 3) }
        val adj = pullers.firstOrNull { getRange(c, it) <= 1 } ?: esc.takeIf { getRange(c, it) <= 1 }
        if (adj != null) c.attack(adj)
        val goal: Creep = pullers.minByOrNull { getRange(c, it) } ?: esc
        if (getRange(c, goal) > 1) EscortRun.stepRed(w, c, goal, 1)
        log(w, c, "icpt", "on ${Bodies.summaryOf(goal)}@(${goal.x},${goal.y})")
    }

    private fun camp(w: EscortRun.World, c: Creep) {
        // телохранитель (ricardo#23, реплеи 28.09.2026): с рождения идёт рядом со своим эскортом, бьёт вплотную и
        // отходит от эскорта не дальше четырёх клеток за тем, кто подошёл
        val near = w.enemies.filter { getRange(c, it) <= 1 }
        (near.firstOrNull { Bodies.isArmed(it) } ?: near.minByOrNull { it.hits })?.let { c.attack(it) }
        val own = w.escort ?: return
        val prey = w.enemies.filter { getRange(own, it) <= 4 }.minByOrNull { getRange(c, it) }
        if (prey != null) { if (getRange(c, prey) > 1) EscortRun.stepRed(w, c, prey, 1); log(w, c, "camp", "on ${Bodies.summaryOf(prey)}"); return }
        if (getRange(c, own) > 1) EscortRun.stepRed(w, c, own, 1)
        log(w, c, "camp", "guarding own escort")
    }

    private const val LUNGE_RANGE = 8
    private const val PREY_RANGE = 3
    private var lunged = false

    private fun guard(w: EscortRun.World, c: Creep) {
        val theirs = w.enemyEscort
        if (!lunged) w.enemies.filter { getRange(c, it) <= 1 }.minByOrNull { it.hits }?.let { c.attack(it) }
        val close = w.enemies.filter { it === theirs || Bodies.isPuller(it, 3) }.minByOrNull { getRange(c, it) }
        if (!lunged && theirs != null && close != null && getRange(c, close) <= LUNGE_RANGE) {
            lunged = true
            println("red t=${w.now} guard: LUNGE — ${Bodies.summaryOf(close)} at ${getRange(c, close)}, their escort at ${getRange(c, theirs)}")
        }
        if (lunged && theirs != null) {
            // охотник на тягачей (реплей v31 6abab492: семь тиков стоял вплотную к эскорту и не бил его, бил только
            // тягачей; v33 6abab9f4 и 6ababb3d: тягачи ушли на 4–6 клеток — и он бил эскорт): цель — ближний их тягач
            // в PREY_RANGE, иначе эскорт
            val prey = w.enemies.filter { it !== theirs && Bodies.isPuller(it, 3) && getRange(c, it) <= PREY_RANGE }.minByOrNull { getRange(c, it) } ?: theirs
            if (getRange(c, prey) <= 1) c.attack(prey) else EscortRun.stepRed(w, c, prey, 1)
            log(w, c, "guard", "hunting ${Bodies.summaryOf(prey)} at ${getRange(c, prey)}, escort h=${theirs.hits}")
            return
        }
        camp(w, c)
    }

    private val wallCells = HashMap<String, Int>()

    private fun wall(w: EscortRun.World, c: Creep) {
        w.enemies.filter { getRange(c, it) <= 3 }.minByOrNull { it.hits }?.let { c.rangedAttack(it) }
        val id = idOf(c)
        if (id !in wallCells) {
            val flow = w.enemyEscortFlow ?: return
            val theirs = w.enemyEscort ?: return
            val taken = wallCells.values.toHashSet()
            val cell = Chokes.route(flow, theirs).filter { it !in taken }
                .minByOrNull { maxOf(kotlin.math.abs(it / 100 - 50), kotlin.math.abs(it % 100 - 50)) } ?: return
            wallCells[id] = cell
            println("red t=${w.now} wall: ${Bodies.summaryOf(c)} takes (${cell / 100},${cell % 100})")
        }
        val k = wallCells[id] ?: return
        if (c.x != k / 100 || c.y != k % 100) EscortRun.stepRed(w, c, pos(k / 100, k % 100), 0, avoidOwn = true)
        log(w, c, "wall", "at (${k / 100},${k % 100})")
    }

    private fun rush7(w: EscortRun.World, c: Creep) {
        val theirs = w.enemyEscort ?: return
        if (getRange(c, theirs) <= 1) c.attack(theirs) else EscortRun.stepRed(w, c, theirs, 1, avoidOwn = true)
        log(w, c, "rush7", "to their escort at ${getRange(c, theirs)}, h=${theirs.hits}")
    }

    private fun rangedSquatter(w: EscortRun.World, c: Creep) {
        val flag = w.enemyFlag ?: return
        val near = w.enemies.filter { getRange(c, it) <= 3 }
        val onFlag = near.firstOrNull { it.x == flag.x && it.y == flag.y }
        (onFlag ?: near.minByOrNull { it.hits })?.let { c.rangedAttack(it) }
        if (c.x == flag.x && c.y == flag.y) { log(w, c, "rsq", "on flag"); return }
        val occupied = w.occupant[flag.x * 100 + flag.y]
        EscortRun.stepRed(w, c, flag, if (occupied == null) 0 else 1, avoidOwn = true, avoidEnemies = true)
        log(w, c, "rsq", "to flag")
    }

    private fun keeperKiller(w: EscortRun.World, c: Creep) {
        val flag = w.enemyFlag ?: return
        val near = w.enemies.filter { getRange(c, it) <= 1 && it !== w.enemyEscort }
        val onFlag = near.firstOrNull { it.x == flag.x && it.y == flag.y }
        (onFlag ?: near.minByOrNull { it.hits })?.let { c.attack(it) }
        if (c.x == flag.x && c.y == flag.y) { log(w, c, "kk", "on flag"); return }
        val occupied = w.occupant[flag.x * 100 + flag.y]
        EscortRun.stepRed(w, c, flag, if (occupied == null) 0 else 1)
        log(w, c, "kk", "to flag")
    }

    private const val CHASE_AHEAD = 10
    private var chaseTarget = -1

    private fun chase(w: EscortRun.World, c: Creep) {
        val esc = w.enemyEscort ?: return
        val flow = w.enemyEscortFlow ?: return
        val route = Chokes.route(flow, esc)
        if (route.size < 3) return
        val t = chaseTarget
        val holding = t >= 0 && c.x == t / 100 && c.y == t % 100 && flow[t] >= 0 && flow[esc.x * 100 + esc.y] > flow[t]
        if (!holding && (t < 0 || t !in route.toHashSet() || flow[esc.x * 100 + esc.y] <= flow[t])) {
            chaseTarget = route[minOf(CHASE_AHEAD, route.size - 2)]
            println("red t=${w.now} chase: park (${chaseTarget / 100},${chaseTarget % 100})")
        }
        val g = chaseTarget
        if (c.x == g / 100 && c.y == g % 100) { log(w, c, "chase", "holding"); return }
        EscortRun.stepRed(w, c, pos(g / 100, g % 100), 0)
    }

    /** Тот же блокировщик, что у основной логики (EscortRun.runChoke): взломщик отличается только тем, когда он куплен. */
    private fun choke(w: EscortRun.World, c: Creep) {
        EscortRun.runChoke(w, c)
        log(w, c, "choke", "")
    }

    private fun pos(x: Int, y: Int): Position = js("({})").unsafeCast<Position>().also { it.asDynamic().x = x; it.asDynamic().y = y }

    private fun log(w: EscortRun.World, c: Creep, trick: String, what: String) {
        if (w.now % 10 == 0) println("red t=${w.now} $trick: (${c.x},${c.y}) $what")
    }
}
