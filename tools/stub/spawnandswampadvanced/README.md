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
  wall) stands there in the stub; the tick a creep of his dies the record keeps no action of it (22 of 22 cases in
  6abc107c), so that tick's fire is read off its victims' entries; a recorded shot at an empty cell, or a creep that
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
    its slot, builds a rampart over the slot and a tower beside it, then leaves. Each spawn orders its `w5c1`, then three
    fighters to one `m4h2`, as its energy allows. The ball (INVENTED rule): rally at his newest base, defend a base under
    our fighters, go for our base without a tower (else our nearest spawn outside a vault) when its damage beats ours
    round the target with our fed towers counted (×1.2), back under 0.8. In the stub his spawns stand at 229–232 /
    780–843 / 1412–1436 (live #19 231/716/1199, #20 300/939/1579) and his first tower at 569–572 (live 517–658).
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

v58 (the frozen build of 29.09.2026) against four of its field matches, tick in the match / tick in the stub:

| event | 6abc0dc7 けろびー#20 | 6abc107c stachu3478#12 | 6abc21a1 けろびー#23 | 6abbba40 ricardo#1 |
|---|---|---|---|---|
| our first spawn (bot: `spawn up`) | 215 / 215 | 215 / 215 | 215 / 215 | 229 / 229 |
| founder leaves | 302 / 302 | 485 / 485 | 302 / 302 | 502 / 502 |
| second base (`expansion spawn up`) | 577 / 577 | 757 / 757 | 566 / 566 | 768 / 768 |
| first tower site / tower stands | 637 / 637; 838 / 838 | 698 / 698; 899 / 899 | 626 / 626; 827 / 827 | 668 / 668; — |
| second tower | 854 / 854 | 979 / 979 | — | — |
| twin spawn | 1023 / 1023 | 1089 / 1089 | 1028 / 1028 | — |
| third base | 2113 / — (the stub won at 2280) | 1589 / 1589 | — | — |
| vault breached / vault spawn | 1430 / 1435; 1492 / 1493 | 1535 / 1537; 1605 / 1612 | — | — |
| his spawns | 300, 939, 1579 / same | 213, 618, 1058 / same | 294, 933, 1690, 2457 / same | 241, 550, 883 / same |
| first hit on ours / on his | 733 / 733; 840 / 840 | 1072 / 1074; 751 / 751 | 714 / 714; 829 / 829 | 692 / 692; 698 / 698 |
| first death ours / his | 758 / 758; 873 / 873 | 1151 / 1202; 856 / 856 | 763 / 763; 1079 / 1079 | 701 / 701; 717 / 717 |
| first strike / first push | 1580 / 1580; 2885 / 2224 | 1540 / 1541; 2151 / 2254 | — | — |
| first base fallen | — | — | 1009 / 1009 | 858 / 858 |
| end | WIN 3601 / WIN 2280 | WIN 2334 / WIN 2332 | LOSS 2533 / LOSS 2532 | LOSS 952 / LOSS 951 |
| our creeps off their recorded cell | 1687 of 9622 creep-ticks, first at 1351 | 3202 of 11733, first at 860 | 0 of 7662 | 0 of 1976 |
| first line the logs part | 1359 | 860 | 850 (two sites listed in another order) | 800 |

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

**Open** (not found, or not worth the model yet):

- 6abc0dc7 parts at t=1314–1359: our M8R8's hits 1600 against the recorded 1550, then the live match loses an M10R10 at
  1359 that the stub keeps; from there the stub wins at 2280 (live 3601). Combat differences accumulate where our creeps
  stand elsewhere than recorded and his recorded shot at their cell hits nothing (the stub's rule then chooses).
- 6abc107c parts at t=858–860: his healer at 50 hits instead of 100 (our M5R5 shot it in the stub, not live); our first
  death +51 (1202 against 1151), the first push +103.
- 6abbba40 t=800: our creeps carry 56 against the live 45; every milestone is still exact.
- The record keeps no enemy site that was never built, so the ghost does not show it (ricardo's (2,32) at 794).

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

**Open lines** (any tag but `land`/`gate`; ≈3 min more), v58: `kerobii` left DRAW at 5000 / right WIN; `kerobii22` WIN
from both; `ricardo` LOSS at 876 (left) / 894 (right) — as live (lost at 952); `BOT2=self` a draw at 5000.

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
zsh regress.sh gate            # the gate (what land.sh runs), ≈28 s
zsh regress.sh v58             # the gate plus the open lines, ≈4 min
```

The runner prints `cpu t=N` and a status line every 100 ticks, and at the end `done:` (the outcome, survivors, both
sides' structures, tower shots, the bot's errors), `milestones:` (what calib.py reads), and with `REPLAY` the record's
outcome and `ourDev:`; `ghost:` says how many of his creeps were born, missed, stood off their record, and how often his
fire fell back to the stub's rule. The bot's console and the event list go to `out/run-<LOGTAG><scenario>.log`.

Probes (off by default): `GHOSTDBG=1` names his creeps off their recorded cell, `GHOSTACT=a-b` prints what each of his
creeps fired in those ticks, `MOVEDBG=a-b` prints every moveTo of ours (target, CostMatrix or not, cost, path head) to
stderr and `MOVEDUMP=<prefix>` saves each such search with its matrix for an offline re-run, `CPUCLOCK=1` makes
`getCpuTime` real, `PF=astar|dijkstra` and `PF_ROOM=50` switch the path search.
