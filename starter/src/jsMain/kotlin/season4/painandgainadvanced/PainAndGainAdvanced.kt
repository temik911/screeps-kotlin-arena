package season4.painandgainadvanced

import kotlinx.js.JsPlainObject
import screeps.api.ATTACK
import screeps.api.ATTACK_POWER
import screeps.api.BODYPART_HITS
import screeps.api.CARRY
import screeps.api.CARRY_CAPACITY
import screeps.api.Creep
import screeps.api.HEAL
import screeps.api.HEAL_POWER
import screeps.api.MOVE
import screeps.api.Position
import screeps.api.RANGED_ATTACK
import screeps.api.RANGED_ATTACK_POWER
import screeps.api.RANGED_HEAL_POWER
import screeps.api.RESOURCE_ENERGY
import screeps.api.TOUGH
import screeps.api.TOWER_CAPACITY
import screeps.api.TOWER_COOLDOWN
import screeps.api.TOWER_ENERGY_COST
import screeps.api.TOWER_FALLOFF
import screeps.api.TOWER_FALLOFF_RANGE
import screeps.api.TOWER_HITS
import screeps.api.TOWER_OPTIMAL_RANGE
import screeps.api.TOWER_POWER_ATTACK
import screeps.api.TOWER_POWER_HEAL
import screeps.api.TOWER_RANGE
import screeps.api.WORK
import screeps.api.arenaInfo
import screeps.api.get
import screeps.api.getCpuTime
import screeps.api.getObjects
import screeps.api.getObjectsByPrototype
import screeps.api.getTicks
import screeps.api.season4.painandgainadvanced.FLAG_TYPES
import screeps.api.season4.painandgainadvanced.MAX_SCORE_PER_TICK
import screeps.api.season4.painandgainadvanced.ScoreFlag
import screeps.api.season4.painandgainadvanced.TICKS_LIMIT
import screeps.api.structures.StructureContainer
import screeps.api.structures.StructureTower
import sourcemaps.runWithSourceMapSupport

/** The bot's version, printed in the greeting — the only thing that ties a match log back to a commit. */
const val BOT_VERSION = 3

@OptIn(ExperimentalJsExport::class)
@JsExport
fun loop() {
    runWithSourceMapSupport { PainAndGainAdvanced.tick() }
}

@JsPlainObject
external interface Cell {
    var x: Int
    var y: Int
}

fun cell(x: Int, y: Int): Position = Cell(x = x, y = y).unsafeCast<Position>()

fun bodyOf(c: Creep): String {
    val sb = StringBuilder()
    var last = ""; var n = 0
    for (p in c.body) {
        val ch = when (p.type) { MOVE -> "m"; ATTACK -> "a"; RANGED_ATTACK -> "r"; HEAL -> "h"; CARRY -> "c"; WORK -> "w"; TOUGH -> "t"; else -> "?" }
        if (ch == last) n++ else { if (n > 0) sb.append(last).append(n); last = ch; n = 1 }
    }
    if (n > 0) sb.append(last).append(n)
    return sb.toString()
}

private fun own(my: Boolean?) = if (my == true) "o" else if (my == false) "e" else "n"   // a neutral owner is `undefined`, not null

/** Damage of a tower shot at a range: full up to TOWER_OPTIMAL_RANGE, falling linearly to (1 − FALLOFF) at FALLOFF_RANGE. */
fun towerPower(power: Int, r: Int): Double {
    if (r > TOWER_RANGE) return 0.0
    if (r <= TOWER_OPTIMAL_RANGE) return power.toDouble()
    val f = minOf(1.0, (r - TOWER_OPTIMAL_RANGE).toDouble() / (TOWER_FALLOFF_RANGE - TOWER_OPTIMAL_RANGE))
    return power * (1 - TOWER_FALLOFF * f)
}

enum class Mode { MARCH, HOLD, FIGHT, RETREAT, SWEEP }

/**
 * v2 — the first bot that plays. Rules and layout were measured by the v1 probe (27.09.2026, `docs/pain-and-gain-advanced.md`):
 * sixteen a side — two pullers `c6m6`, light `a4m4`/`r4m4`/`h4m4` two each, heavy `t4a8m6`×3, `t4r8m6`×3, `t4h8m6`×2 —
 * eleven flags on a fixed layout, four towers of capacity 10 (one 1000-damage shot at point blank, −50 a cell, every 10
 * ticks) each on a flag with a 2500 container beside it, four 2500 containers round the centre flag, terrain random.
 *
 * The plan: each puller takes one of OUR two tower flags (the two nearer us by path), sits on it and feeds the tower
 * from the container next to it, so the flag cannot be flipped without a fight under the tower. The fourteen fighters
 * walk as one group to the centre flag and hold it; they fight what comes when the duel says they win, and fall back to
 * the nearest fed tower of ours when it says they lose.
 */
object PainAndGainAdvanced {
    private var ourScore = 0
    private var theirScore = 0
    private var mode = Mode.MARCH
    private var modeSince = 0
    private val pullerPost = HashMap<String, String>()   // puller id -> flag id
    private var firstContact = 0
    private var ourDeaths = 0
    private var theirDeaths = 0
    private var lastOur = 16
    private var lastTheir = 16

    private lateinit var flags: List<ScoreFlag>
    private lateinit var towers: List<StructureTower>
    private lateinit var containers: List<StructureContainer>
    private lateinit var mine: List<Unit>
    private lateinit var theirs: List<Unit>
    private lateinit var ourFx: Effects
    private lateinit var theirFx: Effects
    private var t = 0

    fun tick() {
        t = getTicks()
        Grid.init()
        val creeps = getObjectsByPrototype(Creep::class).filter { it.exists }
        mine = creeps.filter { it.my }.map { Unit(it) }
        theirs = creeps.filter { !it.my }.map { Unit(it) }
        flags = getObjectsByPrototype(ScoreFlag::class).filter { it.exists }
        towers = getObjectsByPrototype(StructureTower::class).filter { it.exists }
        containers = getObjectsByPrototype(StructureContainer::class).filter { it.exists }
        if (t == 1) { blockTowers(); probe() }
        ourFx = Effects(flags, true)
        theirFx = Effects(flags, false)
        ourScore += ourFx.rate
        theirScore += theirFx.rate
        if (mine.size < lastOur) ourDeaths += lastOur - mine.size
        if (theirs.size < lastTheir) theirDeaths += lastTheir - theirs.size
        lastOur = mine.size; lastTheir = theirs.size

        held.clear()
        trackStuck()
        army()
        pullers()
        towersAct()
        Traffic.resolve(mine.map { it.c })

        if (t % 50 == 0 || t == 2) status()
    }

    private val towerCells = HashSet<Int>()
    /** Cells of our creeps that stand still on purpose this tick (a marcher waiting for the rear, a flag keeper):
     *  no one steps into them and the traffic does not push them — a waiting creep swapped BACK by the one behind
     *  it moves the group nowhere, and v2's first march deadlocked exactly so, 14 creeps for 1000 ticks. */
    private val held = HashSet<Int>()
    private val lastCell = HashMap<String, Int>()
    private val stillFor = HashMap<String, Int>()
    private fun trackStuck() {
        for (u in mine) {
            stillFor[u.id] = if (lastCell[u.id] == u.cell) (stillFor[u.id] ?: 0) + 1 else 0
            lastCell[u.id] = u.cell
        }
    }
    private fun hold(u: Unit) { held.add(u.cell); Traffic.pin(u.c) }
    private fun blockTowers() { for (tw in towers) towerCells.add(Grid.idx(tw.x, tw.y)) }

    // ------------------------------------------------------------------ probe and status

    private fun probe() {
        println("hello season4 pain-and-gain-advanced v$BOT_VERSION: ${arenaInfo.season} - ${arenaInfo.name} level=${arenaInfo.level} " +
            "ticksLimit=${arenaInfo.ticksLimit} TICKS_LIMIT=$TICKS_LIMIT MAX_SCORE_PER_TICK=$MAX_SCORE_PER_TICK")
        println("tuning: engage=$ENGAGE_RANGE fightRatio=$FIGHT_RATIO retreatRatio=$RETREAT_RATIO lead=$ESCORT_LEAD link=$GROUP_LINK sweep=$SWEEP_RATIO pair=$HUNT_PAIR towerMin=$TOWER_MIN_DAMAGE")
        println("flagtypes: ${JSON.stringify(FLAG_TYPES)}")
        println("consts: TOWER_RANGE=$TOWER_RANGE TOWER_POWER_ATTACK=$TOWER_POWER_ATTACK TOWER_POWER_HEAL=$TOWER_POWER_HEAL " +
            "TOWER_OPTIMAL_RANGE=$TOWER_OPTIMAL_RANGE TOWER_FALLOFF_RANGE=$TOWER_FALLOFF_RANGE TOWER_FALLOFF=$TOWER_FALLOFF " +
            "TOWER_COOLDOWN=$TOWER_COOLDOWN TOWER_CAPACITY=$TOWER_CAPACITY TOWER_ENERGY_COST=$TOWER_ENERGY_COST TOWER_HITS=$TOWER_HITS " +
            "ATTACK_POWER=$ATTACK_POWER RANGED_ATTACK_POWER=$RANGED_ATTACK_POWER HEAL_POWER=$HEAL_POWER RANGED_HEAL_POWER=$RANGED_HEAL_POWER " +
            "BODYPART_HITS=$BODYPART_HITS CARRY_CAPACITY=$CARRY_CAPACITY")
        for (f in flags) println("flag: (${f.x},${f.y}) ${f.effectType} ${f.scorePerTick} ${own(f.my)} id=${f.id}")
        for (tw in towers) println("tower: (${tw.x},${tw.y}) ${own(tw.my)} e=${tw.store[RESOURCE_ENERGY] ?: 0} id=${tw.id}")
        for (c in containers) println("container: (${c.x},${c.y}) e=${c.store[RESOURCE_ENERGY] ?: 0} id=${c.id}")
        for (u in mine) println("mine: (${u.x},${u.y}) ${bodyOf(u.c)} hits=${u.hits} id=${u.id}")
        for (u in theirs) println("theirs: (${u.x},${u.y}) ${bodyOf(u.c)} hits=${u.hits} id=${u.id}")
        val kinds = HashMap<String, Int>()
        for (o in getObjects()) { val name = o.asDynamic().constructor.name as String; kinds[name] = (kinds[name] ?: 0) + 1 }
        println("objects: ${kinds.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }}")
        val sb = StringBuilder()
        for (y in 0 until Grid.N) {
            sb.clear()
            for (x in 0 until Grid.N) sb.append(when (Grid.at(x, y)) { Grid.WALL -> '#'; Grid.SWAMP -> '~'; else -> '.' })
            println("map ${y.toString().padStart(2, '0')} $sb")
        }
    }

    private fun status() {
        val ours = flags.filter { it.my == true }.joinToString(",") { "${it.x}:${it.y}" }
        val his = flags.filter { it.my == false }.joinToString(",") { "${it.x}:${it.y}" }
        val tw = towers.joinToString(" ") { "${it.x}:${it.y}${own(it.my)}${it.store[RESOURCE_ENERGY] ?: 0}" }
        val army = mine.filter { it.role != Role.PULLER }
        val d = Duel(army, theirs.filter { it.role != Role.PULLER }, ourFx, theirFx)
        println("t=$t score=$ourScore:$theirScore rate=${ourFx.rate}:${theirFx.rate} alive=${mine.size}:${theirs.size} " +
            "deaths=$ourDeaths:$theirDeaths hits=${mine.sumOf { it.hits }}:${theirs.sumOf { it.hits }} mode=$mode since=$modeSince " +
            "duel=${d.ratio.asDynamic().toFixed(2)} contact=$firstContact flags=[$ours]|[$his] towers=$tw fx=$ourFx|$theirFx " +
            "api=${mine.firstOrNull()?.c?.effects?.let { e -> e.joinToString(",") { "${it.effectType}:${it.data.multiplier ?: ""}${it.data.offset ?: ""}" } } ?: "-"} " +
            "moves=${Traffic.moves} denied=${Traffic.denied} cpu=${getCpuTime() / 1_000_000}")
        Traffic.resetCounters()
    }

    // ------------------------------------------------------------------ pullers and towers

    /** Our two tower flags: of the four, the two nearest our pullers' start by path. */
    private fun homeTowerFlags(): List<ScoreFlag> {
        val start = mine.filter { it.role == Role.PULLER }.ifEmpty { mine }
        if (start.isEmpty()) return emptyList()
        val sx = start.sumOf { it.x } / start.size; val sy = start.sumOf { it.y } / start.size
        return flags.filter { f -> towers.any { Grid.range(it.x, it.y, f.x, f.y) <= 1 } }
            .sortedBy { Grid.to(it.x, it.y, 1)[Grid.idx(sx, sy)] }.take(2)
    }

    private var homeFlags: List<String> = emptyList()

    private fun pullers() {
        if (homeFlags.isEmpty()) homeFlags = homeTowerFlags().map { it.id }
        val ps = mine.filter { it.role == Role.PULLER }
        for (p in ps) {
            if (p.id !in pullerPost) {
                val taken = pullerPost.values.toSet()
                val free = homeFlags.mapNotNull { id -> flags.firstOrNull { it.id == id } }.filter { it.id !in taken }
                val pick = free.minByOrNull { Grid.to(it.x, it.y, 1)[p.cell] } ?: continue
                pullerPost[p.id] = pick.id
            }
            val post = flags.firstOrNull { it.id == pullerPost[p.id] } ?: continue
            val tower = towers.firstOrNull { Grid.range(it.x, it.y, post.x, post.y) <= 1 }
            val box = containers.filter { Grid.range(it.x, it.y, post.x, post.y) <= 1 }.maxByOrNull { it.store[RESOURCE_ENERGY] ?: 0 }
            if (p.x == post.x && p.y == post.y) {
                hold(p)
                val tE = tower?.store?.get(RESOURCE_ENERGY) ?: 0
                if (tower != null && tower.my == true && tE < TOWER_CAPACITY && p.energy > 0) {
                    p.c.transfer(tower, RESOURCE_ENERGY)
                } else if (box != null && (box.store[RESOURCE_ENERGY] ?: 0) > 0 && p.energy <= p.carry * CARRY_CAPACITY - 50) {
                    p.c.withdraw(box, RESOURCE_ENERGY)
                }
            } else {
                stepToward(p, Grid.to(post.x, post.y, if (p.weight == 0) 1 else 5), 100)
            }
        }
    }

    private fun towersAct() {
        for (tw in towers) {
            if (tw.my != true || (tw.store[RESOURCE_ENERGY] ?: 0) < TOWER_ENERGY_COST || tw.cooldown > 0) continue
            var best: Unit? = null; var bestScore = 0.0
            for (e in theirs) {
                val r = Grid.range(tw.x, tw.y, e.x, e.y)
                val dmg = towerPower(TOWER_POWER_ATTACK, r) * theirFx.damageTaken
                if (dmg < TOWER_MIN_DAMAGE) continue
                val kill = if (dmg >= e.hits) 3.0 else 1.0
                val value = (e.melee * 30 + e.ranged * 10 + e.heal * 12 + 10).toDouble()
                val s = kill * dmg * value / e.hits
                if (s > bestScore) { bestScore = s; best = e }
            }
            if (best != null) { tw.attack(best.c); continue }
            val hurt = mine.filter { Grid.range(tw.x, tw.y, it.x, it.y) <= TOWER_RANGE }
                .maxByOrNull { minOf(it.deficit.toDouble(), towerPower(TOWER_POWER_HEAL, Grid.range(tw.x, tw.y, it.x, it.y)) * ourFx.heal) }
            if (hurt != null && hurt.deficit >= 150) tw.heal(hurt.c)
        }
    }

    // ------------------------------------------------------------------ the army

    private fun ourFedTowers() = towers.filter { it.my == true && ((it.store[RESOURCE_ENERGY] ?: 0) > 0 || mine.any { p -> p.role == Role.PULLER && Grid.range(p.x, p.y, it.x, it.y) <= 1 && p.energy > 0 }) }
    private fun theirTowers() = towers.filter { it.my == false }

    private fun towerDpsAt(list: List<StructureTower>, x: Int, y: Int): Double =
        list.sumOf { towerPower(TOWER_POWER_ATTACK, Grid.range(it.x, it.y, x, y)) / TOWER_COOLDOWN }

    /** The largest cluster of our fighters, creeps linked when within GROUP_LINK of each other. Decisions are the main
     *  group's: v2 let any straggler "see" an enemy and put the whole army in a fight it was forty cells away from. */
    private fun mainGroup(army: List<Unit>): List<Unit> {
        val seen = HashSet<String>()
        var best: List<Unit> = emptyList()
        for (s in army) {
            if (s.id in seen) continue
            val comp = ArrayList<Unit>(); val queue = ArrayList<Unit>(); queue.add(s); seen.add(s.id)
            while (queue.isNotEmpty()) {
                val u = queue.removeAt(queue.size - 1); comp.add(u)
                for (v in army) if (v.id !in seen && Grid.range(u.x, u.y, v.x, v.y) <= GROUP_LINK) { seen.add(v.id); queue.add(v) }
            }
            if (comp.size > best.size) best = comp
        }
        return best
    }

    private val hunterOf = HashMap<String, String>()   // our hunter id -> enemy id

    private fun army() {
        val army = mine.filter { it.role != Role.PULLER }
        if (army.isEmpty()) return
        val group = mainGroup(army)
        val cx = group.sumOf { it.x } / group.size; val cy = group.sumOf { it.y } / group.size
        val foes = theirs.filter { it.armed || it.heal > 0 }
        val near = foes.filter { e -> group.any { Grid.range(it.x, it.y, e.x, e.y) <= ENGAGE_RANGE } }
        if (near.isNotEmpty() && firstContact == 0) firstContact = t
        val duel = Duel(group, near.ifEmpty { foes }, ourFx, theirFx, towerDpsAt(ourFedTowers(), cx, cy), towerDpsAt(theirTowers(), cx, cy))
        val whole = Duel(army, foes, ourFx, theirFx)
        // the enemy's fighting strength is gone when what is left of it loses to our army many times over, or when no
        // more than a pair of its armed creeps is left: then its flags and its survivors are what the score is
        val swept = foes.isEmpty() || whole.ratio >= SWEEP_RATIO || foes.count { it.armed } <= 2
        val want = when {
            near.isEmpty() -> null
            swept && near.all { e -> group.count { Grid.range(it.x, it.y, e.x, e.y) <= ENGAGE_RANGE } > 0 } && duel.ratio >= RETREAT_RATIO -> Mode.FIGHT
            duel.ratio >= FIGHT_RATIO -> Mode.FIGHT
            mode == Mode.FIGHT && duel.ratio >= RETREAT_RATIO -> Mode.FIGHT
            else -> Mode.RETREAT
        }
        val centre = flags.firstOrNull { it.effectType == EFF_CENTRE } ?: flags.minByOrNull { Grid.range(it.x, it.y, 49, 49) }!!
        val sweepFlag = if (swept) flags.filter { it.my != true }.minByOrNull { Grid.to(it.x, it.y)[Grid.idx(cx, cy)] } else null
        val objective = sweepFlag ?: centre
        val next = want ?: when {
            swept -> Mode.SWEEP
            army.count { Grid.range(it.x, it.y, objective.x, objective.y) <= 3 } * 2 >= army.size -> Mode.HOLD
            else -> Mode.MARCH
        }
        if (next != mode) { mode = next; modeSince = t; println("mode t=$t: $mode duel=${duel.ratio.asDynamic().toFixed(2)} whole=${whole.ratio.asDynamic().toFixed(2)} near=${near.size} group=${group.size}/${army.size} obj=${objective.x},${objective.y}") }

        fire(army)
        // hunters: in a sweep, light armed creeps go in pairs after the enemy's survivors — a lone runner sits on a flag
        // or walks between them, and an `h4m4` heals itself 48 a tick, more than one `r4m4` does to it
        val hunters = if (mode == Mode.SWEEP || (swept && mode != Mode.RETREAT)) assignHunters(army) else { hunterOf.clear(); emptyList() }
        val rest = army.filter { it !in hunters }
        val restGroup = group.filter { it !in hunters }
        for (u in rest) if (u !in restGroup && u.moves > 0) rejoin(u, restGroup.ifEmpty { group })
        when (mode) {
            Mode.FIGHT -> fight(restGroup)
            Mode.RETREAT -> {
                val home = ourFedTowers().minByOrNull { Grid.to(it.x, it.y)[Grid.idx(cx, cy)] }
                val goal = home?.let { h -> flags.firstOrNull { Grid.range(it.x, it.y, h.x, h.y) <= 1 } } ?: centre
                march(restGroup, goal.x, goal.y)
            }
            Mode.MARCH, Mode.SWEEP -> if (restGroup.isNotEmpty()) march(restGroup, objective.x, objective.y)
            Mode.HOLD -> hold(restGroup, objective.x, objective.y)
        }
        for (h in hunters) hunt(h)
    }

    private fun assignHunters(army: List<Unit>): List<Unit> {
        val prey = theirs.sortedBy { e -> army.minOf { Grid.range(it.x, it.y, e.x, e.y) } }
        val pool = army.filter { !it.heavy && it.armed && it.moves > 0 }.toMutableList()
        hunterOf.keys.retainAll { id -> pool.any { it.id == id } && theirs.any { e -> e.id == hunterOf[id] } }
        for (e in prey) {
            val on = hunterOf.count { it.value == e.id }
            repeat(maxOf(0, HUNT_PAIR - on)) {
                val free = pool.filter { it.id !in hunterOf }.minByOrNull { Grid.range(it.x, it.y, e.x, e.y) } ?: return@repeat
                hunterOf[free.id] = e.id
            }
        }
        return pool.filter { it.id in hunterOf }
    }

    private fun hunt(h: Unit) {
        val prey = theirs.firstOrNull { it.id == hunterOf[h.id] } ?: return
        val stop = if (h.melee > 0) 1 else 2
        if (Grid.range(h.x, h.y, prey.x, prey.y) <= stop) return
        stepToward(h, preyField(prey), 60)
    }

    private val preyFields = HashMap<Int, IntArray>()
    private var preyTick = -1
    private fun preyField(e: Unit): IntArray {
        if (preyTick != t) { preyFields.clear(); preyTick = t }
        return preyFields.getOrPut(e.cell) { Grid.fresh(intArrayOf(e.cell)) }
    }

    private fun rejoin(u: Unit, group: List<Unit>) {
        if (group.isEmpty()) return
        val gx = group.sumOf { it.x } / group.size; val gy = group.sumOf { it.y } / group.size
        val anchor = group.minByOrNull { Grid.range(it.x, it.y, gx, gy) }!!
        if (Grid.range(u.x, u.y, anchor.x, anchor.y) <= 2) return
        stepToward(u, Grid.to(anchor.x, anchor.y), 20)
    }

    /**
     * The group walks the field to the goal at the pace of its slowest creeps, the heavies (two ticks a plain step,
     * four with one fatigue flag of ours): they never wait. The faster ones keep within ESCORT_LEAD of the heavies'
     * front and, when ahead, stand where they are without holding the cell, so a heavy behind pushes past them. v2
     * made the front wait for the rear and held the waiting cells — the rear then had nowhere to step, and on two maps
     * of five the army stood a thousand ticks and more.
     */
    private fun march(army: List<Unit>, gx: Int, gy: Int) {
        if (army.isEmpty()) return
        val f = Grid.to(gx, gy)
        val slowest = army.filter { it.moves > 0 }.maxOfOrNull { it.ticksPerStep(ourFx.fatigue) } ?: return
        val pacers = army.filter { it.moves > 0 && it.ticksPerStep(ourFx.fatigue) >= slowest }
        val live = pacers.filter { (stillFor[it.id] ?: 0) < STUCK_TICKS }.ifEmpty { pacers }
        val front = live.minOf { f[it.cell] }
        for (u in army.sortedByDescending { f[it.cell] }) {
            if (f[u.cell] <= 1) continue
            if (u !in pacers && f[u.cell] < front - ESCORT_LEAD) continue
            stepToward(u, f, if (u in pacers) 200 + f[u.cell] else f[u.cell])
        }
    }

    private fun hold(army: List<Unit>, gx: Int, gy: Int) {
        if (army.isEmpty()) return
        val f = Grid.to(gx, gy)
        val keeper = army.firstOrNull { it.x == gx && it.y == gy }
            ?: army.filter { it.role == Role.HEALER }.minByOrNull { f[it.cell] }
            ?: army.minByOrNull { f[it.cell] }
        for (u in army) {
            if (u === keeper) {
                if (u.x == gx && u.y == gy) hold(u) else stepToward(u, f, 1000)
                continue
            }
            if (Grid.range(u.x, u.y, gx, gy) <= 2) continue
            stepToward(u, f, f[u.cell])
        }
    }

    /** In a fight: melee close on the nearest enemy, ranged stand at three from their target and out of the enemy's
     *  melee reach, healers go to the most hurt of ours and keep out of the enemy's melee. */
    private fun fight(army: List<Unit>) {
        if (army.isEmpty()) return
        val toFoe = Grid.fresh(theirs.map { it.cell }.toIntArray())
        val enemyMelee = theirs.filter { it.melee > 0 }
        for (u in army) {
            when (u.role) {
                Role.MELEE -> stepToward(u, toFoe, 50, stopAt = 1)
                Role.RANGED -> {
                    val threat = enemyMelee.minOfOrNull { Grid.range(it.x, it.y, u.x, u.y) } ?: 99
                    val nearest = theirs.minOfOrNull { Grid.range(it.x, it.y, u.x, u.y) } ?: 99
                    if (threat <= 2) stepAway(u, enemyMelee, 30)
                    else if (nearest > 3) stepToward(u, toFoe, 40, stopAt = 3)
                }
                Role.HEALER -> {
                    val threat = enemyMelee.minOfOrNull { Grid.range(it.x, it.y, u.x, u.y) } ?: 99
                    val patient = army.filter { it !== u && it.deficit > 0 }.maxByOrNull { it.deficit }
                    if (threat <= 1) stepAway(u, enemyMelee, 30)
                    else if (patient != null && Grid.range(patient.x, patient.y, u.x, u.y) > 1) stepToward(u, Grid.fresh(intArrayOf(patient.cell)), 20, stopAt = 1)
                    else if (nearestAllyArmed(u, army) > 2) stepToward(u, Grid.fresh(army.filter { it.armed }.map { it.cell }.toIntArray()), 10, stopAt = 1)
                }
                Role.PULLER -> {}
            }
        }
    }

    private fun nearestAllyArmed(u: Unit, army: List<Unit>) = army.filter { it !== u && it.armed }.minOfOrNull { Grid.range(it.x, it.y, u.x, u.y) } ?: 99

    // ------------------------------------------------------------------ fire and heal

    private fun fire(army: List<Unit>) {
        // what each enemy would take this tick from everything of ours that reaches it
        val incoming = HashMap<String, Double>()
        for (e in theirs) {
            var dmg = 0.0
            for (u in army) {
                val r = Grid.range(u.x, u.y, e.x, e.y)
                if (r <= 1) dmg += u.melee * 30 * ourFx.attack
                if (r <= 3) dmg += u.ranged * 10 * ourFx.ranged
            }
            incoming[e.id] = dmg
        }
        for (u in army) {
            if (u.melee > 0) {
                val tgt = theirs.filter { Grid.range(it.x, it.y, u.x, u.y) <= 1 }.maxByOrNull { focusScore(it, incoming) }
                if (tgt != null) u.c.attack(tgt.c)
            }
            if (u.ranged > 0) {
                val inReach = theirs.filter { Grid.range(it.x, it.y, u.x, u.y) <= 3 }
                if (inReach.isNotEmpty()) {
                    val mass = inReach.sumOf { when (Grid.range(it.x, it.y, u.x, u.y)) { 0, 1 -> 10; 2 -> 4; else -> 1 }.toInt() }
                    if (mass > 10) u.c.rangedMassAttack()
                    else u.c.rangedAttack(inReach.maxByOrNull { focusScore(it, incoming) }!!.c)
                }
            }
            if (u.heal > 0) healOne(u)
        }
    }

    private fun focusScore(e: Unit, incoming: Map<String, Double>): Double {
        val dmg = (incoming[e.id] ?: 0.0) * theirFx.damageTaken
        val kill = if (dmg >= e.hits) 2.0 else 1.0
        return kill * (e.melee * 30 + e.ranged * 10 + e.heal * 12 + 5) * minOf(1.0, dmg / e.hits.toDouble() + 0.05)
    }

    private fun healOne(u: Unit) {
        val adj = mine.filter { it.deficit > 0 && Grid.range(it.x, it.y, u.x, u.y) <= 1 }.maxByOrNull { it.deficit }
        if (adj != null) { u.c.heal(adj.c); return }
        val far = mine.filter { it.deficit > 0 && Grid.range(it.x, it.y, u.x, u.y) <= 3 }.maxByOrNull { it.deficit }
        if (far != null) u.c.rangedHeal(far.c)
    }

    // ------------------------------------------------------------------ steps

    private val dxs = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    private val dys = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

    private fun occupiedByEnemy(i: Int) = theirs.any { it.cell == i }

    /** One step down the field; `stopAt` is the field value at which the creep is where it wants to be. */
    private fun stepToward(u: Unit, f: IntArray, priority: Int, stopAt: Int = 0) {
        val here = f[u.cell]
        if (here <= stopAt) return
        var best = -1; var bestV = here
        for (k in 0 until 8) {
            val nx = u.x + dxs[k]; val ny = u.y + dys[k]
            if (!Grid.inside(nx, ny) || Grid.wall(nx, ny)) continue
            val n = Grid.idx(nx, ny)
            if (n in towerCells || n in held || occupiedByEnemy(n)) continue
            val v = f[n]
            if (v < bestV) { bestV = v; best = n }
        }
        if (best >= 0) Traffic.want(u.c, best, priority)
    }

    private fun stepAway(u: Unit, from: List<Unit>, priority: Int) {
        var best = -1; var bestV = from.minOfOrNull { Grid.range(it.x, it.y, u.x, u.y) } ?: return
        for (k in 0 until 8) {
            val nx = u.x + dxs[k]; val ny = u.y + dys[k]
            if (!Grid.inside(nx, ny) || Grid.wall(nx, ny)) continue
            val n = Grid.idx(nx, ny)
            if (n in towerCells || n in held || occupiedByEnemy(n) || Grid.swamp(nx, ny)) continue
            val v = from.minOf { Grid.range(it.x, it.y, nx, ny) }
            if (v > bestV) { bestV = v; best = n }
        }
        if (best >= 0) Traffic.want(u.c, best, priority)
    }

    private const val EFF_CENTRE = "eff_damage_taken_modifier"
    const val ENGAGE_RANGE = 8
    const val FIGHT_RATIO = 1.1
    const val RETREAT_RATIO = 0.8
    const val ESCORT_LEAD = 2
    const val STUCK_TICKS = 6
    const val GROUP_LINK = 4
    const val SWEEP_RATIO = 4.0
    const val HUNT_PAIR = 2
    const val TOWER_MIN_DAMAGE = 200.0
}
