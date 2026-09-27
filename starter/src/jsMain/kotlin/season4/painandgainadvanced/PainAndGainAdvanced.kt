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
const val BOT_VERSION = 14

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

enum class Mode { MARCH, HOLD, FIGHT, RETREAT, SWEEP, STAND }

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
        ourAt.clear(); for (u in mine) ourAt[u.cell] = u
        trackStuck()
        classify()
        // a fighter that starts farming is no longer played from a fortress
        if (fighter && theirFx.rate >= FARMER_RATE) { fighter = false; println("style t=$t: farmer now, his rate=${theirFx.rate}") }
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
    private val ourAt = HashMap<Int, Unit>()
    private val lastCell = HashMap<String, Int>()
    private val prevCell = HashMap<String, Int>()
    private val stillFor = HashMap<String, Int>()
    private fun trackStuck() {
        for (u in mine) {
            stillFor[u.id] = if (lastCell[u.id] == u.cell) (stillFor[u.id] ?: 0) + 1 else 0
            if (lastCell[u.id] != u.cell) lastCell[u.id]?.let { prevCell[u.id] = it }
            lastCell[u.id] = u.cell
        }
    }
    private fun hold(u: Unit) { held.add(u.cell); Traffic.pin(u.c) }
    private fun blockTowers() { for (tw in towers) towerCells.add(Grid.idx(tw.x, tw.y)) }

    // ------------------------------------------------------------------ probe and status

    private fun probe() {
        println("hello season4 pain-and-gain-advanced v$BOT_VERSION: ${arenaInfo.season} - ${arenaInfo.name} level=${arenaInfo.level} " +
            "ticksLimit=${arenaInfo.ticksLimit} TICKS_LIMIT=$TICKS_LIMIT MAX_SCORE_PER_TICK=$MAX_SCORE_PER_TICK")
        println("tuning: engage=$ENGAGE_RANGE engaged=$ENGAGED_R local=$LOCAL_R initiative=$INITIATIVE_RATIO fightRatio=$FIGHT_RATIO retreatRatio=$RETREAT_RATIO lead=$ESCORT_LEAD link=$GROUP_LINK zone=$ZONE_R guard=$GUARD_R sweep=$SWEEP_RATIO pair=$HUNT_PAIR towerMin=$TOWER_MIN_DAMAGE classify=$CLASSIFY_T spread=$SPREAD_R/$FIGHTER_MAX_OUT fighterRate=$FIGHTER_MAX_RATE fortressR=$FORTRESS_R")
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

    /**
     * The pullers' posts: one sits on our tower flag that does not slow us (the RANGED one nearer our start: its tower,
     * fed from the container beside the flag, keeps the flag), the other on the hits-loss flag nearer us, the cheapest
     * debuff there is (−1 a tick a creep, which idle healers give back). Neither takes our FATIGUE flag: it doubles the
     * fatigue of every creep of ours that weighs — the heavies walk four ticks a plain step, the light hunters two —
     * for the rest of the match, and no opponent of the field takes his (27.09.2026: v3 held it from t≈35 and its
     * hunters could not catch two survivors in two thousand ticks).
     */
    private fun homePosts(): List<ScoreFlag> {
        val start = mine.filter { it.role == Role.PULLER }.ifEmpty { mine }
        if (start.isEmpty()) return emptyList()
        val sx = start.sumOf { it.x } / start.size; val sy = start.sumOf { it.y } / start.size
        val here = Grid.idx(sx, sy)
        val tower = flags.filter { f -> f.effectType != EFF_FATIGUE && towers.any { Grid.range(it.x, it.y, f.x, f.y) <= 1 } }
            .minByOrNull { Grid.to(it.x, it.y, 1)[here] }
        val loss = flags.filter { it.effectType == EFF_HITS_LOSS_T }.minByOrNull { Grid.to(it.x, it.y, 1)[here] }
        return listOfNotNull(tower, loss)
    }

    /** Our tower flag of a type: of the two such flags, the one nearer our start. */
    private fun homeTowerOf(type: String): ScoreFlag? {
        val here = startCell
        return flags.filter { f -> f.effectType == type && towers.any { Grid.range(it.x, it.y, f.x, f.y) <= 1 } }
            .minByOrNull { Grid.to(it.x, it.y, 1)[here] }
    }
    private var startCell = 0

    private var homeFlags: List<String> = emptyList()
    private val mending = HashSet<String>()

    private fun pullers() {
        if (homeFlags.isEmpty()) {
            homeFlags = homePosts().map { it.id }
            val start = mine.filter { it.role == Role.PULLER }.ifEmpty { mine }
            if (start.isNotEmpty()) startCell = Grid.idx(start.sumOf { it.x } / start.size, start.sumOf { it.y } / start.size)
        }
        val ps = mine.filter { it.role == Role.PULLER }
        for (p in ps) {
            if (p.id !in pullerPost) {
                val taken = pullerPost.values.toSet()
                val free = homeFlags.mapNotNull { id -> flags.firstOrNull { it.id == id } }.filter { it.id !in taken }
                val pick = free.minByOrNull { Grid.to(it.x, it.y, 1)[p.cell] } ?: continue
                pullerPost[p.id] = pick.id
            }
            if (fighter) {
                // the second puller moves from the hits-loss flag to our fatigue tower: against a fighter the army
                // stands at its fortress, where fatigue costs it nothing, and a second fed tower holds 5 a tick more
                val cur = flags.firstOrNull { it.id == pullerPost[p.id] }
                if (cur != null && cur.effectType == EFF_HITS_LOSS_T) {
                    val other = homeTowerOf(EFF_FATIGUE)
                    if (other != null && pullerPost.values.none { it == other.id }) pullerPost[p.id] = other.id
                }
            }
            val post = flags.firstOrNull { it.id == pullerPost[p.id] } ?: continue
            val tower = towers.firstOrNull { Grid.range(it.x, it.y, post.x, post.y) <= 1 }
            // a post without a tower is kept by standing on it only while no armed enemy is about to kill the puller:
            // then the puller falls back to the army and comes back when the way is clear
            val danger = theirs.any { it.armed && Grid.range(it.x, it.y, p.x, p.y) <= PULLER_DANGER }
            // and a puller on the hits-loss flag loses a hit a tick with no healer in reach: below PULLER_MEND of its
            // hits it walks to the army to be healed, and goes back full
            if (tower == null && p.hits < p.hitsMax * PULLER_MEND) mending.add(p.id)
            if (p.hits >= p.hitsMax - 50) mending.remove(p.id)
            if (tower == null && (danger || p.id in mending)) {
                // to the healers if we have any, else under our fed tower, which heals it: the last creep of ours in a
                // lost fight died of our own hits-loss flag at t=2600 while we led 28 077 to 0, with 1300 ticks to go
                // before the lead would have ended the match
                val healers = mine.filter { it.heal > 0 }
                val tw = ourFedTowers().minByOrNull { Grid.range(it.x, it.y, p.x, p.y) }
                when {
                    healers.isNotEmpty() -> { val a = healers.minByOrNull { Grid.range(it.x, it.y, p.x, p.y) }!!; stepToward(p, Grid.to(a.x, a.y, 1), 100, stopAt = 1) }
                    tw != null -> stepToward(p, Grid.to(tw.x, tw.y, 1), 100, stopAt = 1)
                }
                continue
            }
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
                // the group's focus first: a 1000-shot on top of the volley strips what his healing would undo if the two
                // were spread over two creeps
                val focus = if (e.id == Fire.focusId) TOWER_FOCUS_BONUS else 1.0
                val s = kill * dmg * value / e.hits * focus
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
    private var surviving = false
    /** His style, latched at CLASSIFY_T: a FIGHTER keeps his army together and takes at most one flag (stachu3478#5
     *  none, Hardy#1 the centre), a farmer spreads runners over five to seven flags by t=100 (kerobii#6/#12,
     *  76561198870429455). Against a fighter the centre is a coin toss — his formed line against ours at parity, lost
     *  in two tests of four — while our flags already lead him by 12 a tick: v12 plays him from a fortress. */
    private var fighter = false
    private var classified = false
    private fun classify() {
        if (classified || t < CLASSIFY_T) return
        classified = true
        // read early, by how his army walks: a farmer's runners are out on their flags from the first ticks (kerobii's
        // melee took the centre at t=39), a fighter's sixteen walk as one. Read at t=150 (v12's first cut) the stand's
        // line of stachu3478#5 caught our army on its way to the fortress at t=152 on three maps of eight
        val cx = theirs.sumOf { it.x } / maxOf(1, theirs.size); val cy = theirs.sumOf { it.y } / maxOf(1, theirs.size)
        val out = theirs.count { Grid.range(it.x, it.y, cx, cy) > SPREAD_R }
        fighter = out <= FIGHTER_MAX_OUT && theirFx.rate <= FIGHTER_MAX_RATE
        println("style t=$t: ${if (fighter) "fighter" else "farmer"} his rate=${theirFx.rate} his flags=${flags.count { it.my == false }} spread=$out")
    }

    private fun army() {
        val army = mine.filter { it.role != Role.PULLER }
        if (army.isEmpty()) return
        val group = mainGroup(army)
        val cx = group.sumOf { it.x } / group.size; val cy = group.sumOf { it.y } / group.size
        val foes = theirs.filter { it.armed || it.heal > 0 }
        val near = foes.filter { e -> group.any { Grid.range(it.x, it.y, e.x, e.y) <= ENGAGE_RANGE } }
        if (near.isNotEmpty() && firstContact == 0) firstContact = t
        // the fight is weighed against everything of his that can join it, not the few already within ENGAGE_RANGE:
        // against Hardy#1 the duel read 3.4 with seven of his near and 0.38 with thirteen, and the army swung between
        // fight and retreat every eight ticks for 4500 ticks, six cells from his standing blob
        val zone = foes.filter { e -> Grid.range(cx, cy, e.x, e.y) <= ZONE_R || e in near }
        val duel = Duel(group, zone.ifEmpty { foes }, ourFx, theirFx, towerDpsAt(ourFedTowers(), cx, cy), towerDpsAt(theirTowers(), cx, cy))
        val whole = Duel(army, foes, ourFx, theirFx)
        // the enemy's fighting strength is gone when what is left of it loses to our army many times over, or when no
        // more than a pair of its armed creeps is left: then its flags and its survivors are what the score is
        val swept = foes.isEmpty() || whole.ratio >= SWEEP_RATIO || foes.count { it.armed } <= 2
        // engaged: his armed within ENGAGED_R of our group. A group in contact does not retreat — at equal speed a
        // retreat only turns backs to his guns: v4 against Hardy#1 went from 9 against 9 at t=92 to 1 against 8 at
        // t=200 walking home (the basic arena's `no-escape-equal-speed`)
        val engaged = foes.any { e -> e.armed && group.any { Grid.range(it.x, it.y, e.x, e.y) <= ENGAGED_R } }
        // the head of his approach: what of his is within LOCAL_R of our group. Walking in a column he brings its head
        // first, and the head alone loses — v6's one win over stachu3478#5 in four was the one where it closed first
        // (t=219, killing his heavy melee by t=250); in the three losses it stood and he arrived formed
        val head = foes.filter { e -> group.any { Grid.range(it.x, it.y, e.x, e.y) <= LOCAL_R } }
        val local = Duel(group, head.ifEmpty { foes }, ourFx, theirFx, towerDpsAt(ourFedTowers(), cx, cy), towerDpsAt(theirTowers(), cx, cy))
        val want = when {
            near.isEmpty() -> null
            engaged -> Mode.FIGHT
            swept && duel.ratio >= RETREAT_RATIO -> Mode.FIGHT
            head.size < zone.size && local.ratio >= INITIATIVE_RATIO && (!fighter || fortressNear(cx, cy)) -> Mode.FIGHT
            duel.ratio >= FIGHT_RATIO -> Mode.FIGHT
            mode == Mode.FIGHT && duel.ratio >= RETREAT_RATIO -> Mode.FIGHT
            duel.ratio < RETREAT_RATIO -> Mode.RETREAT
            // between the two the group neither closes nor walks away: it stands where it is, formed, and the fight
            // comes to it (contact makes it a fight). v5 retreated here from stachu3478#5's approach, swung between
            // retreat, march and hold for forty ticks, and met his army strung out: 13 lost against 6
            else -> Mode.STAND
        }
        val centre = flags.firstOrNull { it.effectType == EFF_CENTRE } ?: flags.minByOrNull { Grid.range(it.x, it.y, 49, 49) }!!
        val sweepFlag = if (swept) sweepTarget(cx, cy) else null
        val fortress = if (fighter && !swept) fortressFlag() else null
        val objective = sweepFlag ?: fortress ?: (if (guarded(centre, group)) safeFlag(cx, cy, group) else null) ?: centre
        // survival: with the army broken and the score ours, what is left lives under our fed tower — it heals them and
        // shoots what comes; one creep of ours alive when the lead outgrows 43 a tick for the ticks left ends the match
        // latched: a healed-back part must not end it — a v11 test left the shelter at t=301 when a heal gave one of our
        // stripped ranged its RANGED part again, and the three walked out to die by t=1640 while we led 3308 to 805
        val ourArmed = army.count { it.armed }; val hisArmed = foes.count { it.armed }
        if (ourArmed <= SURVIVE_ARMED && hisArmed > ourArmed * 2) surviving = true
        if (ourArmed >= hisArmed || ourScore < theirScore) surviving = false
        val shelter = if (surviving) ourFedTowers().minByOrNull { Grid.range(it.x, it.y, cx, cy) } else null
        if (shelter != null) {
            if (mode != Mode.RETREAT) { mode = Mode.RETREAT; modeSince = t; println("mode t=$t: RETREAT survive under (${shelter.x},${shelter.y}) armed=${army.count { it.armed }}:${foes.count { it.armed }} score=$ourScore:$theirScore") }
            fire(army)
            for (u in army) if (Grid.range(u.x, u.y, shelter.x, shelter.y) > 1) stepToward(u, Grid.to(shelter.x, shelter.y), 80, stopAt = 1)
            return
        }
        val next = want ?: when {
            swept -> Mode.SWEEP
            group.count { Grid.range(it.x, it.y, objective.x, objective.y) <= ARRIVE_R + 2 } * 2 >= group.size -> Mode.HOLD
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
            Mode.MARCH -> if (restGroup.isNotEmpty()) {
                march(restGroup, objective.x, objective.y)
                // on the march the flag is stepped on only when the group is there: the nearest creep of a group still
                // on its way is its most forward one, and it walked alone into Hardy#1's army on the centre
                if (restGroup.count { Grid.range(it.x, it.y, objective.x, objective.y) <= ARRIVE_R + 2 } * 2 >= restGroup.size) capture(restGroup, objective)
            }
            Mode.SWEEP -> if (restGroup.isNotEmpty()) { march(restGroup, objective.x, objective.y); capture(restGroup, objective) }
            Mode.HOLD -> hold(restGroup, objective.x, objective.y)
            Mode.STAND -> stand(restGroup)
        }
        for (h in hunters) hunt(h)
    }

    /** The fortress: our fed tower flag nearer our start that a puller of ours holds — the army stands round its tower. */
    private fun fortressNear(cx: Int, cy: Int): Boolean = fortressFlag()?.let { Grid.range(it.x, it.y, cx, cy) <= FORTRESS_R } ?: true

    private fun fortressFlag(): ScoreFlag? {
        // latched once chosen: the second tower gets fed later, and a fortress that moved to it would march the army
        // across the map for nothing
        fortressId?.let { id -> flags.firstOrNull { it.id == id && it.my == true }?.let { return it } }
        val fed = ourFedTowers()
        val pick = flags.filter { f -> f.my == true && fed.any { Grid.range(it.x, it.y, f.x, f.y) <= 1 } }
            .minByOrNull { Grid.to(it.x, it.y, 1)[startCell] }
        fortressId = pick?.id
        return pick
    }
    private var fortressId: String? = null

    /** A flag his army stands at with more than ours can take: the duel of our group against his fighters within
     *  GUARD_R of the flag, at the flag, is below FIGHT_RATIO. */
    private fun guarded(f: ScoreFlag, group: List<Unit>): Boolean {
        val there = theirs.filter { (it.armed || it.heal > 0) && Grid.range(it.x, it.y, f.x, f.y) <= GUARD_R }
        if (there.isEmpty()) return false
        return Duel(group, there, ourFx, theirFx, towerDpsAt(ourFedTowers(), f.x, f.y), towerDpsAt(theirTowers(), f.x, f.y)).ratio < FIGHT_RATIO
    }

    /** Where the army goes when the centre is his and too strong to take: the flag of the most score per tick of walk
     *  that is not guarded and that our group reaches before his main force does. Our fatigue flag is not among them. */
    private fun safeFlag(cx: Int, cy: Int, group: List<Unit>): ScoreFlag? {
        val here = Grid.idx(cx, cy)
        val foes = theirs.filter { it.armed || it.heal > 0 }
        val foeField = if (foes.isEmpty()) null else Grid.fresh(foes.map { it.cell }.toIntArray())
        return flags.filter { f -> f.effectType != EFF_FATIGUE && !guarded(f, group) &&
            (foeField == null || Grid.to(f.x, f.y)[here] <= foeField[Grid.idx(f.x, f.y)]) }
            .maxByOrNull { f ->
                val swing = f.scorePerTick * (if (f.my == false) 2 else if (f.my == true) 0 else 1)
                (swing + 1.0) / (10.0 + Grid.to(f.x, f.y)[here])
            }
    }

    /** The flag the sweep takes next: the most score it swings (a flag of his counts twice — he loses it and we gain
     *  it) per tick of the group's walk; our own fatigue flag only when nothing else is left, since holding it halves
     *  the speed of the hunters the sweep needs. */
    private fun sweepTarget(cx: Int, cy: Int): ScoreFlag? {
        val here = Grid.idx(cx, cy)
        return flags.filter { it.my != true }.maxByOrNull { f ->
            val swing = f.scorePerTick * (if (f.my == false) 2 else 1) * (if (f.effectType == EFF_FATIGUE) 0.05 else 1.0)
            swing / (10.0 + Grid.to(f.x, f.y)[here])
        }
    }

    /** Someone has to stand ON the flag: the march goes to the area round it, and v3's sweep stood two cells from a
     *  flag of his for 1500 ticks without taking it (lost to kerobii#6 on points with 12 against his 2). The nearest
     *  creep of the group walks onto the cell. */
    private fun capture(group: List<Unit>, f: ScoreFlag) {
        if (f.my == true) return
        val fc = Grid.idx(f.x, f.y)
        if (group.any { it.cell == fc }) return
        val field = Grid.to(f.x, f.y)
        val who = group.filter { it.moves > 0 }.minByOrNull { field[it.cell] * 10 + (if (it.heavy) 5 else 0) } ?: return
        stepToward(who, field, 900)
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
        val f = Grid.area(gx, gy, ARRIVE_R)
        val slowest = army.filter { it.moves > 0 }.maxOfOrNull { it.ticksPerStep(ourFx.fatigue) } ?: return
        val pacers = army.filter { it.moves > 0 && it.ticksPerStep(ourFx.fatigue) >= slowest }
        val live = pacers.filter { (stillFor[it.id] ?: 0) < STUCK_TICKS }.ifEmpty { pacers }
        val front = live.minOf { f[it.cell] }
        // front first: a creep deciding its step knows whether the one ahead of it is stepping away this tick
        for (u in army.sortedBy { f[it.cell] }) {
            if (f[u.cell] == 0) continue
            if (u !in pacers && f[u.cell] < front - ESCORT_LEAD) continue
            stepToward(u, f, if (u in pacers) 200 + f[u.cell] else f[u.cell])
        }
    }

    private fun hold(army: List<Unit>, gx: Int, gy: Int) {
        if (army.isEmpty()) return
        val f = Grid.to(gx, gy)
        // the hits-loss flags drain every creep a hit or two a tick, and at hold the healers stand: a creep that has
        // lost MEND_AT with no healer beside it walks to the nearest one (the stand lost a heavy ranged at t=1875 of a
        // quiet match, four cells from the centre and out of every healer's reach)
        val healers = army.filter { it.heal > 0 }
        val mending = army.filter { u -> u.deficit >= MEND_AT && u.heal == 0 && healers.none { Grid.range(it.x, it.y, u.x, u.y) <= 1 } }
        for (u in mending) {
            val h = healers.minByOrNull { Grid.range(it.x, it.y, u.x, u.y) } ?: continue
            stepToward(u, Grid.to(h.x, h.y), 15, stopAt = 1)
        }
        val flagHeld = mine.any { it.role == Role.PULLER && it.x == gx && it.y == gy }
        val keeper = if (flagHeld) null else army.firstOrNull { it.x == gx && it.y == gy }
            ?: army.filter { it.role == Role.HEALER }.minByOrNull { f[it.cell] }
            ?: army.minByOrNull { f[it.cell] }
        for (u in army) {
            if (u in mending) continue
            if (u === keeper) {
                if (u.x == gx && u.y == gy) hold(u) else stepToward(u, f, 1000)
                continue
            }
            if (Grid.range(u.x, u.y, gx, gy) <= ARRIVE_R + 2) continue
            stepToward(u, Grid.area(gx, gy, ARRIVE_R), 10)
        }
    }

    /** In a fight: every creep takes the cell `Formation` scores best for its role — melee onto his line (a light one
     *  only where a heavy of ours also reaches, or onto one standing alone), ranged at three and out of his melee,
     *  healers beside the mate they heal on the side away from him. */
    private fun fight(army: List<Unit>) {
        if (army.isEmpty()) return
        place(army, engaged = true)
    }

    private fun place(army: List<Unit>, engaged: Boolean) {
        // a cell of ours whose creep is not stepping away this tick is not taken: taking it is a swap that moves the pair
        // nowhere and breaks the line
        val ctx = Formation.ctx(army, theirs) { n ->
            n in towerCells || n in held || occupiedByEnemy(n) || ourAt[n]?.let { !Traffic.wants(it.c) } == true
        }
        // what each of ours needs this tick: its deficit and what his weapons in reach can put on it. Healers share a
        // need by their power — under focused fire the one being focused needs several of them, and v7's first cut
        // gave each patient one healer and lost the stand's rush, 10 for 10, which v6 won with 7 to 9 alive
        val need = HashMap<String, Double>()
        for (a in army) {
            var d = 0.0
            for (e in theirs) {
                val r = Grid.range(e.x, e.y, a.x, a.y)
                if (r <= 2 && e.melee > 0) d += e.melee * 30 * theirFx.attack
                if (r <= 4 && e.ranged > 0) d += e.ranged * 10 * theirFx.ranged
            }
            need[a.id] = a.deficit + d * ourFx.damageTaken
        }
        // front first: the one nearest him decides first, so the ones behind see where the line will be
        for (u in army.sortedBy { ctx.foe(it.cell) }) {
            val role = if (u.melee > 0) Role.MELEE else if (u.ranged > 0) Role.RANGED else if (u.heal > 0) Role.HEALER else continue
            if (role == Role.MELEE && !u.heavy && engaged && !supported(u, army)) {
                // an unsupported light melee keeps to the heavies instead of walking onto his line alone
                val heavies = army.filter { it.heavy }.map { it.cell }
                if (heavies.isNotEmpty()) stepToward(u, Grid.fresh(heavies.toIntArray()), 40, stopAt = 1)
                continue
            }
            val power = u.heal * 12 * ourFx.heal
            val patient = if (role == Role.HEALER) army.filter { it !== u && (need[it.id] ?: 0.0) > 0 }
                .maxByOrNull { minOf(need[it.id] ?: 0.0, power) - 8.0 * Grid.range(it.x, it.y, u.x, u.y) } else null
            if (patient != null) need[patient.id] = (need[patient.id] ?: 0.0) - power
            val want = Formation.step(u, role, ctx, patient, engaged)
            if (want != u.cell) Traffic.want(u.c, want, if (role == Role.MELEE) 60 else 30)
        }
    }

    /** Stand formed: nobody walks toward him or away from him but to take its place in the line — melee to the front,
     *  ranged a cell behind it, healers two behind and beside the hurt. */
    private fun stand(army: List<Unit>) {
        if (army.isEmpty()) return
        place(army, engaged = false)
    }

    private fun supported(u: Unit, army: List<Unit>): Boolean {
        val target = theirs.minByOrNull { Grid.range(it.x, it.y, u.x, u.y) } ?: return false
        val alone = theirs.none { it !== target && (it.armed) && Grid.range(it.x, it.y, target.x, target.y) <= 3 }
        return alone || army.any { it.heavy && it.armed && Grid.range(it.x, it.y, target.x, target.y) <= 2 }
    }

    private fun nearestAllyArmed(u: Unit, army: List<Unit>) = army.filter { it !== u && it.armed }.minOfOrNull { Grid.range(it.x, it.y, u.x, u.y) } ?: 99

    // ------------------------------------------------------------------ fire and heal

    private fun fire(army: List<Unit>) {
        for (shot in Fire.assign(army, theirs, ourFx, theirFx)) {
            if (shot.mass) { shot.shooter.c.rangedMassAttack(); continue }
            val tgt = shot.target!!
            val r = Grid.range(shot.shooter.x, shot.shooter.y, tgt.x, tgt.y)
            if (shot.shooter.melee > 0 && r <= 1) shot.shooter.c.attack(tgt.c)
            if (shot.shooter.ranged > 0 && r <= 3) shot.shooter.c.rangedAttack(tgt.c)
        }
        // a melee creep with RANGED parts too is not in this army; a heavy melee swings, and a ranged one within one
        // of its target still shoots — the engine runs attack and rangedAttack in separate pipelines
        for (h in Fire.heals(army, mine, theirs, ourFx, theirFx)) {
            if (h.ranged) h.healer.c.rangedHeal(h.target.c) else h.healer.c.heal(h.target.c)
        }
    }

    // ------------------------------------------------------------------ steps

    private val dxs = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    private val dys = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

    private fun occupiedByEnemy(i: Int) = theirs.any { it.cell == i }

    /**
     * One step down the field; `stopAt` is the field value at which the creep is where it wants to be. A nearer cell
     * held by one of ours that is not stepping away this tick is not taken by a swap (a swap moves the pair nowhere):
     * the creep takes another nearer cell, or steps aside to a free cell as near as its own, and only when neither
     * exists asks for the occupied one.
     */
    private fun stepToward(u: Unit, f: IntArray, priority: Int, stopAt: Int = 0) {
        val here = f[u.cell]
        if (here <= stopAt) return
        var bestFree = -1; var bestFreeV = here
        var bestAny = -1; var bestAnyV = here
        var side = -1; var sideScore = Int.MAX_VALUE
        for (k in 0 until 8) {
            val nx = u.x + dxs[k]; val ny = u.y + dys[k]
            if (!Grid.inside(nx, ny) || Grid.wall(nx, ny)) continue
            val n = Grid.idx(nx, ny)
            if (n in towerCells || n in held || occupiedByEnemy(n)) continue
            val v = f[n]
            val occ = ourAt[n]
            val free = occ == null || occ === u || Traffic.wants(occ.c)
            if (v < bestAnyV) { bestAnyV = v; bestAny = n }
            if (free && v < bestFreeV) { bestFreeV = v; bestFree = n }
            // an aside step never goes back to the cell the creep just left: the stand saw three creeps at HOLD trade
            // two cells 250 times per 100 ticks
            if (free && occ == null && v == here && n != prevCell[u.id]) {
                // aside: the free cell of equal cost whose own nearer neighbours are least crowded
                val crowd = Grid.neighbours(nx, ny).count { m -> f[m] < v && ourAt[m] != null }
                if (crowd < sideScore) { sideScore = crowd; side = n }
            }
        }
        val pick = when {
            bestFree >= 0 -> bestFree
            side >= 0 -> side
            else -> bestAny
        }
        if (pick >= 0) Traffic.want(u.c, pick, priority)
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
    private const val EFF_FATIGUE = "eff_fatigue_modifier"
    private const val EFF_HITS_LOSS_T = "eff_hits_loss"
    const val PULLER_DANGER = 5
    const val ZONE_R = 14
    const val GUARD_R = 8
    const val PULLER_MEND = 0.6
    const val ENGAGE_RANGE = 8
    const val FIGHT_RATIO = 1.15
    const val RETREAT_RATIO = 0.8
    const val ENGAGED_R = 4
    const val LOCAL_R = 10
    const val INITIATIVE_RATIO = 1.3
    const val MEND_AT = 250
    const val SURVIVE_ARMED = 2
    const val CLASSIFY_T = 45
    const val SPREAD_R = 10
    const val FIGHTER_MAX_OUT = 2
    const val FIGHTER_MAX_RATE = 5
    const val FARMER_RATE = 12
    const val FORTRESS_R = 8
    const val ESCORT_LEAD = -1
    const val ARRIVE_R = 2
    const val STUCK_TICKS = 6
    const val GROUP_LINK = 4
    const val SWEEP_RATIO = 4.0
    const val HUNT_PAIR = 2
    const val TOWER_MIN_DAMAGE = 200.0
    const val TOWER_FOCUS_BONUS = 3.0
}
