# Offline stub harness — Spawn and Swamp ADVANCED

Runs the compiled bot (`../../../build/js/.../season4/spawnandswampadvanced/SpawnAndSwampAdvanced.export.mjs`, i.e. the
build of the worktree this directory lives in) under Node against a stub of the Arena runtime. It descends from
`tools/stub/spawnandswamp/` (the basic level of the same game) through `tools/stub/escortrunadvanced/`, whose intent
engine it copies — copied, not shared, by the repository's rule. The basic stub applies every action at once and has no
harvest; this level is an economy of harvest and build, so the engine is the escort-run one (every action an intent,
resolved after both sides ran), changed where this arena and the calibration below demanded. From the basic stub it keeps
the tower-site rule, the `none` idea of an idle opponent, and the self-play (here `BOT2`).

The arena's rules are in `docs/spawn-and-swamp-advanced.md`; the calibration against played matches is the section
"Calibration" below and the tool `calib.sh`.

## What is modelled

**The engine** (`world.mjs`, `game/`), each rule after World's engine source (the Screeps World Steam client's
`server/package/node_modules/@screeps/engine/src/processor/intents/*`, `.../driver/native/src/pf.cc`), the Arena's docs
(`arena-docs/index.html` of the Arena client) or a replay:

- **Actions are World's intents with World's priorities** (`creeps/intents.js`): harvest yields to attack, build, heal and
  rangedHeal; build to heal/rangedHeal; attack to build/heal/rangedHeal; rangedAttack to rangedMassAttack/build/rangedHeal;
  rangedMassAttack to build/rangedHeal; rangedHeal to heal — the arena-docs list the same pairs. A later call of an action
  in a tick replaces the earlier one. The API answers the documented codes (owner, spawning, part, range, energy).
- **Harvest before transfer**; a transfer moves the amount fixed at the call (the start-of-tick store) — the doc's measured
  rule. 2 a live WORK, the source 1000 +10 a tick; what a harvest brings over the capacity drops on the harvester's cell
  (World's `harvest.js`; the replay 6abc0dc7: a W5C1 building every fourth tick leaves +5 under itself each cycle), into a
  container on that cell if there is one (arena-docs). A pile decays `ceil(amount/1000)` a tick.
- **Build** `BUILD_POWER` 5 a live WORK, one energy a point; an obstacle's site is not raised under a creep or another
  obstacle (`build.js`; the API answers −7 then, as documented). A finished spawn starts empty and shows 1 the next tick
  (every spawn +1 a tick, the replay); ramparts and walls 10 000 hits.
- **`createConstructionSite` sees the cell as it was at the start of the tick**: live, v58 put a rampart site and a tower
  site on (48,20) in the same tick 757 (6abc107c) and both stood there — the tower's was built first (762–979), the
  rampart's after it. A rampart goes over anything but a rampart; one other structure a cell; 10 sites a side.
- **Spawning**: `spawnCreep` pays from the owner's spawns and extensions within `SPAWN_RANGE` 20 (arena-docs; World's
  `_charge-energy.js` order — itself first, then by distance); the body is on the spawn's cell at once and comes out
  `3 × parts − 1` ticks after the order's tick (the replay's `n` and first `u` of every creep), after the movement, on the
  first free cell in the order TOP..TOP_LEFT or the spawn's `directions` (World's `_born-creep.js`; the replay: our bodies
  at (67,3) come out TOP, then TOP_RIGHT when a harvester stands TOP); every cell taken and a hostile creep on one — it is
  crushed. A spawning creep takes no hit; a spawn that dies takes its creep with it.
- **Movement** (escort-run copy, unchanged): simultaneous, swaps and chains legal, the engine's ranks for a contested
  cell, pull; fatigue by part type (dead parts weigh), loaded CARRY; blocked by terrain walls, spawns, towers, extensions,
  `StructureWall`s and the other side's ramparts. A creep stepping onto the other side's TOWER site erases it (the basic
  level's observation, kept); spawn and rampart sites stay (this level's `probe` lines, v19–v58).
- **Damage and healing as World's creep tick** (`creeps/tick.js`): a hit on a structure lands at once, a creep's damage
  and heal of the tick are summed and applied after the movement and after the fatigue is shed — by the MOVEs alive
  BEFORE this tick's damage — then the parts are recomputed from the total, full from the tail (`_recalc-body.js`). A hit
  on anything under a rampart goes into the rampart; a mass attack skips what stands under a rampart and hits the rampart
  once; unowned objects (the vault walls) take no mass attack.
- **A creep's store follows its live CARRY parts** (`_recalc-body.js`, `_drop-resources-without-space.js`): when its hits
  fall and its capacity with them, what it holds over the new capacity drops on its cell (the replay 6abc0dc7 t=857: our
  founder's CARRY dies under his fire, 550 -> 500 hits, and a pile of 7 appears under it). A pile made in the creeps' own
  tick (that drop, a dead creep's store) does not decay on that tick; a pile made by an intent (a harvest's excess, a drop)
  does (the same replay: the harvest excess of 5 at t=310 shows 4, the founder's 7 at t=857 shows 7).
- **Towers** (escort-run copy): 1000 at range 1, −50 a cell (`TOWER_FALLOFF_RANGE` 21 of the live runtime), cooldown 10,
  10 energy a shot, capacity 10; `heal` 600 by the same rule.
- **Paths are World's native pathfinder, ported** (`game/path-finder.mjs`): jump-point A* with heuristic weight 1.2, the
  pf.h heap and its tie order, one 100x100 room. v58 prints its searches' costs, and this port reproduces all 27 numbers of
  the opening lines of 6abc0dc7 (`opening: sources by second base (67,1)t=38+33 … (1,32)t=126+75`,
  `expansion: candidates (1,32)us=112/them=37 …`, `arrival=38`, `reach us=68 them=60`); Dijkstra misses 9 of them, plain
  A* 9, World's 50-cell rooms (`PF_ROOM=50`) 8.
- **`findPath` / `moveTo` are World's `Room.findPath`** (`game/rooms.js` `_findPath2`): obstacles avoided unless a
  CostMatrix is given (arena-docs), the search to RANGE 1 of the target, the target cell appended when the path ends
  beside it. Measured on our starting worker's recorded walk to its slot in 45 stored matches: 1632 of 1632 steps agree;
  a search to the target cell itself agrees on 1584 (the last steps differ — live `(66,4) -> (65,3) -> (66,2)`). A fresh
  search every tick: World's 5-tick path reuse made our creeps leave their recorded cells earlier (t=667 against 751).
- A creep without CARRY answers `null` for its store capacity (live: his M5R5 prints `e=0/null`).

**The arena** (`run.mjs`): the fixed layout on a live terrain. Two workers `W4C1M5` (the replay's part order: WORK first)
on (10,70) `left` and (89,29) `right`; six sources (1,32) (32,98) (49,22) (50,77) (67,1) (98,67) on terrain walls; two
vaults of four containers of 2500 — (42..43,31..32) and (56..57,67..68) — in frames of 52 `StructureWall` of 10 000 hits
(x=38 and y=27/36 round the first, x=61 and y=63/72 round the second) closed by the terrain's centre wall. Ids as live:
every start object numbered in the order of x, then y (1 the source (1,32), 2 the left worker, …, 67 the right worker),
objects made in a match from 10 000. The win: a side with no creep and no structure left loses (sites do not count,
ramparts do — 6abc0dc7 ran on until his last rampart and tower fell); both — a draw; 5000 ticks — a draw.
`arenaInfo` is the live one (`Spawn and Swamp`, season 4, level 2, 5000, cpu 100 000 000 / 1 000 000 000 ns).

**The terrain**: `REPLAY=<id>` (the replay's terrain and the side we played; `./replays/` first, then
`~/ScreepsArena/replays/`), `GAME=<game.json>` or `MAP=<100-row dump>`. Four replays of v58 are kept in `./replays/`
(6abc0dc7, 6abc107c, 6abc21a1, 6abbba40 — the calibration matches and the gate's terrains).

**Not modelled**: roads, effects, tower `repair`, creep aging (Arena creeps do not age), the enemy's sites that the record
never shows built (the ghost places a site on its first recorded progress — ricardo's (2,32) site, seen live at 794 with
0/1000, is not there).

## Opponents

`run.mjs <ticks> <scenario>`:

- `none` — the System bot: his worker never moves. v58 kills it at 538–568.
- `ghost` — **the recorded opponent of `REPLAY`**, on that match's terrain and side. His creeps are born on their
  recorded tick at their recorded spawn onto their recorded exit cell (his MOVE-less W5C1 must land on its slot; the
  energy is granted — his economy is the record's) and walk toward the cell they stood on after each tick. His fatigue is
  the record's (his squads pull one another and the record writes no pulls: stachu3478's M4R5M1 dropped from 40 to 0
  without moving 78 times in 6abc107c; a creep that the record moves while tired is pulled by his creep leaving its next
  cell). His fire is the record's own, by target cell — a recorded shot at a cell hits what of ours (or a neutral vault
  wall) stands there in the stub. **The tick a creep of his dies the record keeps neither its fire nor its last step**
  (22 of 22 hits whose attacker has no action were dealt by a creep dying that tick, 6abc107c): its fire is read off the
  hits (`inferDeathFire`: a creep or structure of ours that lost more that tick than the tick's recorded actions deal — the
  engine's rule on the record's bodies and hits at the start of the tick — took the rest from the dying creep in reach, a
  single shot, a mass attack or a swing by which the losses fit; a creep of his that gained more than the recorded heals
  give was healed by a dying healer), then off its victims' `A` entries for what that leaves (World's actionLog keeps ONE
  `attacked` cell a creep, the last hit, so a creep hit by two names one of them); and when another creep enters its cell
  that tick, it stepped out first (`lastStep`: the engine moves before it applies the damage — over the 146 stored replays
  of this arena 182 creeps entered the cell of a creep dying that tick, every one of those could step, and of the 14 353
  that died unable to step not one cell was entered). A recorded shot at an empty cell, or a creep that
  outlived its record, fights by the stub's rule (`fight`: melee adjacent, a mass attack at three adjacent, a ranged
  shot at the weakest armed in three, heal the most hurt). His building follows the record while it can: a site of his
  appears the tick before its first recorded build if the creep that builds it lives here, gains the recorded progress
  while a recorded builder of it lives, and freezes for good when none does — kill his founder and the base is never
  raised; a structure of his appears on its recorded tick only from a site kept in step. His spawns' and towers' energy
  and his harvesters' harvest are the record's (the source drains as live). It is the calibration's opponent and the
  gate's.
- **Personas** — responsive opponents built from replays (the comment above each in `run.mjs` names the replays and says
  what is measured and what is INVENTED); each plays a real economy with real intents, so a raid that kills its builder
  or harvester cuts it, and its army picks targets from this tick's state:
  - `kerobii` / `kerobii22` — けろびー#19/#20 (fighter `m5r5`) and #22/#23 (`t4m8r3a1`), replays 6abc1c61, 6abc0dc7,
    6abc0b76, 6abc21a1. One founder, his starting worker, raises every base: the central source of his half first, his
    home corner second, then the nearest free one; on each new spawn he fills it by hand to a MOVE-less `w5c1` born onto
    its slot, builds a rampart over the slot and a tower beside it, then leaves. His spawn is built at #19's rhythm
    (harvest two or three ticks, build 20) in `kerobii` and at #20/#22/#23's (harvest a tick, build 8 the next: 125
    harvests and 124 builds to the spawn in 6abc0dc7, 6abc0b76, 6abc21a1) in `kerobii22`. Each spawn orders its `w5c1`,
    then a fighter, a medic `m4h2`, then three fighters to a medic, as its energy allows (the replays' order in all four).
    **His army — measured, `python3 kerobii.py`** (every stored replay of #19-#23 against us, 68 on 30.09.2026): no ball and
    no rally. Every fighter walks out as it is born (birth to 12 cells off his spawns: median 33 ticks over 7 167 fighters;
    half leave with no other armed creep of his within 6); it goes for **our base nearest to the centroid of his spawns**
    (78 % of 5 008 samples of his largest group standing within 25 of one of our bases; our youngest base 74 %, our oldest
    25 %); a base with no fed tower of ours within 20 it walks onto, beside our nearest structure (the first fighter always
    alone on our rampart: #20 637-866, #23 626-841); a base under one it stands off at **12-17 from our nearest
    structure** (with a fed tower of ours within 20, his out creeps stood at 12-14: 26 %, 15-17: 24 %, 18-20: 20 %, closer
    than 9: 13 % of 651 764 creep-ticks). **The one strength test is local**: with our armed creep 4-7 off, he steps closer
    by a slope in R = his damage within 6 of him over ours within 6 of it (closer minus away a tick: R up to 1 -6 % to
    +2 %, 1-1.5 +7 % / +15 % with a fed tower of ours near, 1.5-2 +20 % / +19 %, above 3 +39 % to +42 % / +25 % to +31 %) —
    the persona closes in at R >= 1.5 and holds 4-5 off below it (6abc0b76: his first fighter and medic held 5 off our
    M5R5 from 588 to 940 and closed when the fourth fighter came, 240 against 100); a worker of ours within 5 he closes on
    to adjacent (59 % of the m5r5's creep-ticks with our worker under a rampart the nearest). **His fire**: a mass attack
    whenever anything of ours is adjacent (99 % of 83 267 creep-ticks), else a single shot (95 % of 79 262) at the one of
    ours in three with the lowest share of its hits (91 % of 17 746 shots with a choice), else at our nearest structure.
    No push rule against our towers and no retreat rule, because the replays show none: his largest group coming within
    14 of our fed tower (155 times) does not differ from it standing at 15-26 (3 067 samples) in its size, its damage, our
    damage within 15 or our armed in all; with 3+ of ours at a spawn of his, his creeps farther than 20 step toward it 20 %
    of the ticks and away 9 % (without: 16 % / 22 %) — a drift, not a recall.
    Key ticks, persona in the stub / the replay (our v58 on our recorded side; his first fighter out of its spawn, 12 cells
    off his spawns, at our base; with whom; which base):

    | replay | persona | his first spawn | first fighter out / off home / at our base | alone | our base |
    |---|---|---|---|---|---|
    | 6abc1c61 #19 | `kerobii` | 235 / 231 | 422 / 468 / 521 — 434 / 446 / 541 | yes / yes | (50,79) / (50,79) |
    | 6abc21a1 #23 | `kerobii22` | 296 / 294 | 501 / 547 / 615 — 512 / 558 / 626 | yes / yes | (49,20) / (49,20) |
    | 6abc0b76 #22 | `kerobii22` | 298 / 296 | 503 / 542 / 580 — 514 / 553 / 589 | yes / yes | (50,79) / (50,79) |

    His second spawn and first tower: 777 and 579 (#19 live 716, 517); 914 and 639 (#23 live 933, 652); 909 and 641 (#22
    live 936, 654). The first fight on #23's terrain: the first hit on ours 712, our first loss 778 (live 714, 763). The
    whole game on the same three terrains, v58 against the persona / live: 6abc1c61 WIN at 2137 / DRAW; 6abc21a1 WIN at
    2427 / LOSS at 2533; 6abc0b76 DRAW / DRAW — his long game is still weaker than live (see "Open"). 6abc1c61 and
    6abc0b76 are read from `~/ScreepsArena/replays/` (not kept in `./replays/`).
  - `ricardo` — ricardo18informatica2020#1, replay 6abbba40: a spawn at the source nearest his start by path, the founder
    stays delivering; orders `m5a5`, `w4c2m6` (the vault builder), `m5a5`…; his first `m5a5`s break the frame of the
    vault nearest him (live: the wall (56,72) fell at 463), the builder raises a spawn inside out of the containers
    (live 550) and that spawn orders `m5a5` off the vault; the raiders come for our nearest creep within eight, else our
    nearest spawn, in threes (INVENTED: the group size from `tools/replay.py track`'s largest group, 3). In the stub his
    vault spawn stands at 500–556 and v58 is dead at 876–894 (live 952).
- **Bot against bot**: `BOT2=<file url of a SEPARATE copy of a build's package>` or `BOT2=self` (the run copies this
  build's package into `out/bot2-<pid>/` and removes it at the end — a Kotlin object is module state, one module graph a
  side); the enemy bot's console goes to `out/run-…-bot2.enemy.log`. v58 against itself on 6abc0dc7: a draw at 5000, ~95 s.

## Calibration

`zsh calib.sh <game id> [ticks]` runs our bot against the `ghost` of that match on its terrain (use `BOT=<file url>` of a
frozen copy of the build that played it) and prints two tables: **milestones** off the replay against the stub's
(`calib.py`: when each side's spawns, towers and ramparts appeared, the first hit on a creep and the first creep death of
each side) and **the bot's own log lines** of the match against the stub's (`spawn up`, `founder … leaves`,
`expansion spawn up`, `twin spawn up`, `tower site`, `posture …->defend`, `vault N: breached`, `vault N: spawn up`,
`push`, `strike`, `my creeps lost`, `base fallen`); then `firstdiff.py` — the first line where the bot's stub log parts
from its live log. The runner's `ourDev:` line says how many creep-ticks our creeps stood off their recorded cell (our
starting worker keeps its id; every creep the bot orders is matched to the recorded one of ours born on the same tick at
the same spawn with the same body) and the first tick our or his creeps' hits part from the record.

v58 (the frozen build of 29.09.2026) against four of its field matches, tick in the match / tick in the stub (30.09.2026,
after items 10-12 below):

| event | 6abc0dc7 けろびー#20 | 6abc107c stachu3478#12 | 6abc21a1 けろびー#23 | 6abbba40 ricardo#1 |
|---|---|---|---|---|
| our first spawn (bot: `spawn up`) | 215 / 215 | 215 / 215 | 215 / 215 | 229 / 229 |
| founder leaves | 302 / 302 | 485 / 485 | 302 / 302 | 502 / 502 |
| second base (`expansion spawn up`) | 577 / 577 | 757 / 757 | 566 / 566 | 768 / 768 |
| first tower site / tower stands | 637 / 637; 838 / 838 | 698 / 698; 899 / 899 | 626 / 626; 827 / 827 | 668 / 668; — |
| second tower | 854 / 854 | 979 / 979 | — | — |
| twin spawn | 1023 / 1023 | 1089 / 1089 | 1028 / 1028 | — |
| third base | 2113 / — (the stub won at 2565) | 1589 / 1589 | — | — |
| vault breached / vault spawn | 1430 / 1430; 1492 / 1492 | 1535 / 1536; 1605 / 1606 | — | — |
| his spawns | 300, 939, 1579 / same | 213, 618, 1058 / same | 294, 933, 1690, 2457 / same | 241, 550, 883 / same |
| first hit on ours / on his | 733 / 733; 840 / 840 | 1072 / 1072; 751 / 751 | 714 / 714; 829 / 829 | 692 / 692; 698 / 698 |
| first death ours / his | 758 / 758; 873 / 873 | 1151 / 1151; 856 / 856 | 763 / 763; 1079 / 1079 | 701 / 701; 717 / 717 |
| first strike / first push | 1580 / 1580; 2885 / 2411 | 1540 / 1421; 2151 / 1928 | — | — |
| first base fallen | — | — | 1009 / 1009 | 858 / 858 |
| end | WIN 3601 / WIN 2565 | WIN 2334 / WIN 2105 | LOSS 2533 / LOSS 2532 | LOSS 952 / LOSS 951 |
| our creeps off their recorded cell | 3263 of 13795 creep-ticks, first at 1621 | 2941 of 11068, first at 1258 | 0 of 7662 | 0 of 1976 |
| first hits apart, ours / his | 1712 / 1759 (were 1314 / 1320) | 1273 / 1273 (were — / 858) | — | — |
| first state difference (`STATEDIFF`) | 1621: our M5R5's step | 1258: our M10R10's step | 1654: our new rampart 24 hits | 491: a vault container's energy |
| first line the logs part | 1650 (was 1359) | 1282 (was 860) | 850 (two sites listed in another order) | none — 201 of 201 lines (was 800) |

The end tick differs by one by convention: the replay's last tick is the one after the last object fell.

**What each discrepancy over 10 ticks was, and what fixed it** (in the order found; each is in the code with its evidence):

1. Path costs 1–33 ticks off the live ones (37 for 38, 71 for 75, 106 for 112) — the escort-run Dijkstra; World's JPS
   ported (above).
2. Our worker off its recorded cell from t=33 (6abc107c; the calibration's first deviation in every match) — the
   findPath goal; World's range-1 goal with the target appended (above).
3. His MOVE-less W5C1 born on the wrong cell and never on its slot — the birth order and the spawning's directions.
4. His creeps a tick behind their record from t=590 (6abc107c) — his squad's pulls; the record's fatigue copied.
5. Our second tower (48,20) at 1409 instead of 854 (6abc0dc7): a breacher shed 12 fatigue where the record shed 14 —
   the MOVEs counted after the tick's damage; World's creep tick (damage after movement and fatigue). Then the same tower
   still late: his M5R5 shot our founder where the record shows mass attacks at our ramparts (the rampart over the founder
   fell 5 ticks early) — his fire replayed by target cell.
6. His towers idle at 0 energy while the record's stood at 10 — energy mirrored every tick, not only when the record
   writes a change.
7. Our tower site refused at (48,20) (−7) and built at (50,20) (6abc107c t=757) — sites of the same tick.
8. Our first strike 186 ticks early and his vault spawn never born (6abc107c) — his `r` at the vault wall (59,72) found
   no target (neutral walls), and his fire on the tick he dies was missing (not in the record).
9. His site visible a tick late (t=55 for the live 54) — placed the tick before its first build.
10. 6abc0dc7 t=1314 (30.09.2026): our M8R8 at 1600 against the recorded 1550, and from those 50 hits a different retreat
    at 1351, its death at 1358 missing, the logs apart at 1359, a win at 2280 against the live 3601. Two of his M5R5 shot
    it; one died that tick, and the record kept neither that one's shot nor its name (the victim's single `A` named the
    other). Also 6abc0dc7 t=950: our ramparts (49,20) and (48,20) 4 and 10 hits too high — a dying R1's mass attack. The
    fire of a creep dying that tick is read off the hits (`inferDeathFire`, "Opponents").
11. 6abc107c t=857-860: his healer at 50 hits instead of 100 (our first death +51, the first push +103): his m4r5m1 on
    (65,7) dies at 857 and his healer steps onto that cell in the same tick; the ghost kept the dying one in place, the
    healer stood a cell short on (66,7) and our M5R5 shot it there. The dying creep's last step (`lastStep`).
12. 6abc0dc7 t=857: a pile of 7 under our founder the stub did not have — the store shrinks with the CARRY parts and drops
    the excess; a pile made in the creeps' tick does not decay that tick ("What is modelled").

**Open** (not found, or not worth the model yet):

- **Where 6abc0dc7 and 6abc107c part now is our own bot's step, and it is a tie in the path search.** The whole state
  (`STATEDIFF`: every creep's cell, hits, fatigue, spawning; every structure, pile and site) is the record's up to 1620 and
  1257; then our M5R5 at (39,25) going to (2,31) (6abc0dc7 t=1621) and our M10R10 at (62,9) going to (97,67) (6abc107c
  t=1258) step elsewhere than live. Both searches (the bot's danger CostMatrix, dumped with `MOVEDUMP`) have tied first
  steps: at 1621 (39,24) and (40,24) both 134 (the stub takes (39,24); live the creep did not move at 1621-1622), at 1258
  (61,10), (63,8) and (63,9) all 305 (the stub takes (63,8), live (61,10)). Every step of ours before them agreed with
  live (how many of those were ties is not counted); which rule of the Arena's search breaks these two is not found. Hits
  part only after (1712, 1273), downstream.
- 6abc107c: with the stub now following to 1258, the first strike comes 119 ticks early (1421) and the first push 223
  early — the consequence of the step above, not a separate rule.
- 6abc21a1 t=1654: our rampart (96,67) finished by our W5C1's build that tick took 24 the same tick in the record (two of
  his t4m8r3a1 mass-attacking at range 2: 2 x 3 x 10 x 0.4) and none in the stub, which resolves the tick's fire before
  its builds. Whether the Arena processes builds before attacks or in some object order is one case, not a rule; it
  changes nothing before the loss (2532 against 2533).
- 6abbba40 t=491: a vault container 100 fuller than recorded — ricardo's vault builder withdraws from it and the record
  does not write withdrawals; our bot's log is identical to the live one to its end.
- The record keeps no enemy site that was never built, so the ghost does not show it (ricardo's (2,32) at 794).
- The kerobii personas' long game: on their own terrains v58 beats `kerobii` at 2137 (6abc1c61, live a draw) and
  `kerobii22` at 2427 (6abc21a1, live a LOSS at 2533) and draws `kerobii22` on 6abc0b76 (live a draw). Candidates, none
  measured yet: his founder's choice of later sources is still INVENTED (`nextSource`: on 6abc1c61 it rules out (1,32),
  where #19 raised his third spawn at 1199), and #23's rampart and tower are built by his W5C1, not by the founder.

## The gate

`tools/land.sh` runs `regress.sh land`: every stdout line must say `PASS` with `errors: 0`.

- `none` from both starts on the terrains of 6abc0dc7 and 6abc21a1: PASS = a WIN (v58: 538–568).
- `ghost` against the four recorded matches of v58: PASS = not worse than live — a record we won must be won (6abc0dc7,
  6abc107c), a record we lost may be lost, but not more than 100 ticks earlier than live (6abc21a1: live 2533, v58 in the
  stub 2532; 6abbba40: live 952, stub 951). A draw record needs a win or a draw.

Why these: they are what the bot does for certain now — beat an idle opponent from both corners, and replay its own field
matches no worse than they went; a regression in the opening, the economy, the vault or the defence shows up as a lost
record or an earlier loss. The personas and the self-play are not gate lines: they measure the bot against the field.

Under `tools/land.sh` (`STUB_LANDING_BASE` set) a landing that touches nothing this bot is built from or run by (other
arenas' packages, stubs and folders, `tools/`, `docs/`, prose) runs only the SMOKE line (`6abc0dc7-right:none`, ≈0.3 s);
this bot's package, this stub, its arena folder, `types/` or the build run the full gate (8 lines, ≈28 s). Every script
caps V8's heap (`NODE_OPTIONS`, `STUB_NODE_FLAGS`), as the other stubs do.

**Open lines** (any tag but `land`/`gate`; ≈3-5 min more), v58, 30.09.2026 (the kerobii personas' army measured, above):
`kerobii` left WIN at 2162 / right DRAW at 5000; `kerobii22` left WIN at 2016 / right WIN at 4063; `ricardo` LOSS at 876
(left) / 894 (right) — as live (lost at 952); `BOT2=self` a draw at 5000.

## Commands

```shell
NODE=$(ls -d ~/.gradle/nodejs/node-*/bin/node | tail -1)             # node is not on PATH here
export NODE_OPTIONS="--max-semi-space-size=2 --max-old-space-size=192" # what regress.sh sets for every run
cd tools/stub/spawnandswampadvanced
REPLAY=6abc0dc7 $NODE --import ./register.mjs run.mjs 5000 none            # the match's terrain, our side of it
REPLAY=6abc0dc7 START=left $NODE --import ./register.mjs run.mjs 5000 none # the other start
REPLAY=6abc21a1 $NODE --import ./register.mjs run.mjs 5000 ghost           # the recorded opponent
REPLAY=6abc0dc7 START=left $NODE --import ./register.mjs run.mjs 5000 kerobii   # a persona: kerobii, kerobii22, ricardo
REPLAY=6abc0dc7 BOT2=self $NODE --import ./register.mjs run.mjs 5000 none  # bot against bot
TRACE=700-760 REPLAY=6abc0dc7 $NODE --import ./register.mjs run.mjs 760 ghost  # per-tick cells, fatigue, energy
zsh calib.sh 6abc107c          # the calibration tables and the first differing log line (BOT=<frozen copy> if needed)
STATEDIFF=40 REPLAY=6abc107c $NODE --import ./register.mjs run.mjs 1300 ghost | grep statediff   # where the stub parts
HITS=1300-1360 REPLAY=6abc0dc7 $NODE --import ./register.mjs run.mjs 1360 ghost > out/h.txt && python3 hitcmp.py 6abc0dc7 out/h.txt
python3 kerobii.py             # the measurement behind the kerobii personas' army, over his stored replays #19-#23
zsh regress.sh gate            # the gate (what land.sh runs), ≈28 s
zsh regress.sh v58             # the gate plus the open lines, ≈4 min
```

The runner prints `cpu t=N` and a status line every 100 ticks, and at the end `done:` (the outcome, survivors, both
sides' structures, tower shots, the bot's errors), `milestones:` (what calib.py reads), and with `REPLAY` the record's
outcome and `ourDev:`; `ghost:` says how many of his creeps were born, missed, stood off their record, and how often his
fire fell back to the stub's rule. The bot's console and the event list go to `out/run-<LOGTAG><scenario>.log`.

Probes (off by default): `STATEDIFF=N` prints the first N differences between the stub and the record after each tick —
every creep's cell, hits, fatigue and spawning state, every structure's hits and energy, every pile and our sites'
progress, each item once — the first line is where the stub stopped being the match (read it before any table);
`HITS=a-b` prints every creep's hits and every hit and heal the engine dealt in those ticks, and `python3 hitcmp.py <id>
<stdout>` sets them beside the replay's hits and recorded actions with the engine's damage of each (a tick where both lists
agree and the hits do not is an engine rule; where the lists differ, a fire choice); `GHOSTDBG=1` names his creeps off their recorded cell, `GHOSTACT=a-b` prints what each of his
creeps fired in those ticks, `MOVEDBG=a-b` prints every moveTo of ours (target, CostMatrix or not, cost, path head) to
stderr and `MOVEDUMP=<prefix>` saves each such search with its matrix for an offline re-run, `CPUCLOCK=1` makes
`getCpuTime` real, `PF=astar|dijkstra` and `PF_ROOM=50` switch the path search.
