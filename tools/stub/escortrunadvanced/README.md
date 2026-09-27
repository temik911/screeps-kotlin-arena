# Offline stub harness — Escort Run ADVANCED

Runs the compiled bot (`../../../build/js/.../season4/escortrunadvanced/EscortRunAdvanced.export.mjs`, i.e. the build of
the worktree this directory lives in) under Node against a stub of the Arena runtime. It is a **copy** of
`tools/stub/escortrun/` (the basic level) — copied, not shared, by the repository's rule — adapted to the advanced level.
The rules it models are the ones measured in live matches (27.09.2026, `docs/escort-run-advanced.md`).

## What is modelled

**The engine** (`world.mjs`, `game/`) is the basic stub's: simultaneous movement with swaps, chains and the engine's
ranks for a contested cell; pull after the World source (`PULL_MODEL=off` switches it off); fatigue by part type with
dead parts still weighing; damage front to back, healing from the tail; a hit on a creep on a rampart goes into the
rampart; spawns (3 ticks a part, the creep appears on the first free cell round the spawn — none free, it waits),
sources (`SOURCE_ENERGY_REGEN` 10), pickup/transfer/withdraw/drop, construction sites and `build`. Four changes, each
after World's engine source (`processor/intents/*` of the Steam client's server package):

- **harvest without room drops the rest on the harvester's cell** (`harvest.js:52`) — the live pile under a `W3M1`, on
  which the bot's economy is built; the basic stub harvested nothing into a creep without CARRY;
- **a hit on anything under a rampart goes into the rampart**, not only a hit on a creep — the spawn has a rampart on its
  cell here;
- **a mass attack skips whatever stands under a rampart and hits the rampart once** (`rangedMassAttack.js`); the basic
  stub hit a rampart twice when a creep stood on it, and a mass attack at a flag (an owner, no hits) wrote NaN into it;
- **towers fire and heal** (the bot builds one from v7; the basic stub's `attack`/`heal` answered 0 and did nothing): the
  API checks owner, energy, cooldown and range (`game/prototypes/tower.mjs`, the Pain and Gain advanced stub's model
  without its flag ownership), one action per `TOWER_COOLDOWN` 10 ticks for `TOWER_ENERGY_COST` 10, power 1000 at range
  1 falling 50 a cell (World's falloff formula with the runtime's `TOWER_FALLOFF_RANGE` 21 — the typings say 20, the live
  runtime printed 21 in Pain and Gain advanced), heal 600 by the same rule; every shot is an event in the log and the
  `done:` line counts them (`tw=ours/his`).

**The arena** (`run.mjs`): the live layout on a live terrain.

- Spawns (9,9) top / (9,90) bottom, 500 energy, capacity 1000, +1 a tick; 25 ramparts of the owner round each (5×5,
  x 7..11 × y 7..11 / 88..92, the spawn cell included), 10 000 hits. A spawn the bot builds (v8's outpost) costs 1000
  (the live `CONSTRUCTION_COST`; the basic stub's table said 3000), starts empty and restores +1 a tick too; the
  `harvest`/`ECON2` income goes to the starting spawn only.
- Three `EscortCreep` a side with the live bodies — MOVE first in every segment: `M10T40` (`M TTTT`×10, 5000, period 4/20),
  `M5T40` (`M T8`×5, 4500, 8/40), `M3T42` (`M T14`×3, 4500, 14/70). Bottom: M3T42 (7,91), M10T40 (7,92), M5T40 (8,92);
  top: M10T40 (7,7), M3T42 (7,8), M5T40 (8,7). Their ids are the live ones (top 4, 6, 18; bottom 14, 16, 28), every other
  object's id counts from 100, so a stub log reads like a live one.
- Six plain `Flag`s with `my`: bottom (95,95) (94,72) (62,94), top (95,4) (94,27) (62,5).
- Sources (2,2) (2,97) (96,24) (96,75), 1000/1000 — the live terrain has a wall under each; containers (92,49) (92,50),
  2500 each.
- `StructureWall`s: y=9 and y=90 for x 43..61 (3000 each), x=6 for y 45..54 (5000 each); a broken wall's cell is open.
- The win: all three escorts of a side on its own flags, or one enemy escort dead; both at once — a draw; 5000 ticks — a
  draw. `arenaInfo`: `Escort Run`, season `4`, level 2, ticksLimit 5000, cpu 100 000 000 / 1 000 000 000 (ns, as live).

**The terrain** is a live one: `map-<game id>.txt`, 100 rows of `#` wall / `~` swamp / `.` plain, written from the
`game.game.terrain` of four stored matches (`~/ScreepsArena/games/<id>/game.json`, all arena `6a86d8c454a3948a1e35f911`):
`map-6ab9267a.txt` (v3 vs けろびー#11, we bottom — the default), `map-6ab92799.txt` (v4 vs stachu3478, we top),
`map-6ab9255d.txt` (v2 vs Hardy, we bottom), `map-6ab92172.txt` (v1 vs 76561198870429455#3, we top). `GAME=<game.json>`
reads any stored match directly. There is no synthetic map: the advanced layout on the basic stub's guessed map means
nothing.

**How close it is to live** (v6 in the stub against the stored logs of v3/v4, same economy code): every order of the
opening lands on the live tick (W3M1 t=1, C2M1 13, W2M1 92, C2M1 111, M5A5 181, M5R5 290) and the spawn's energy is the
live one to within ten (t=150: 368 vs 363; t=300: 10 vs 10). Hardy's escorts in `race` are where the replay has them to
within three cells (t=100 stub (29,29) (19,19) (26,26), live (30,30) (17,17) (24,24)).

**Against a live v8 match** — v8 against System on the terrain of its own live match
(`GAME=~/ScreepsArena/games/6ab9324f22f11288f018f033/game.json`, we bottom): the home economy runs 3-5 ticks ahead of
live from t≈130 (C2M1 130/133, M5A5 210/213, W5C1M6 351/356, M5R5 462/467) and the outpost's sites land on the live
ticks (rampart 500/504, 534/538, spawn 566/570). After that the two part, and not through the stub: the live match
(created 18:12:15) ran v8's first commit (the outpost), whose first two fighters walked to the outpost and stood on its
spawn cell (94,75) and the pioneer's slot (95,74) from t≈700 to the end (the live log's `fighters=` at 700-1100), so the
pioneer stood two cells from the source and the spawn was never raised; the stub runs v8's second commit (18:13:11,
"each army rallies and holds the ramparts of its own base, not the outpost's"), raises the spawn at 725 and kills at 984
(live: 1175).

**Not modelled** (as in the basic stub): extensions' energy in `spawnCreep` (only the spawn's own store is spent — a bot
that builds extensions needs that first), tower `repair`, effects.

## Enemy scenarios

`run.mjs <ticks> <scenario>`, combined with `+` (who each one stands for is in the comment above the scripts):

- `none` — nothing moves (the System bot);
- `race` — Hardy#1: the escorts walk to the three flags from tick 1, each towed by an `M5` puller (ordered while fewer
  pullers than escorts still walk: born 1, 16, 251, as his were), the basic stub's train; flags go to escorts by the
  soonest slowest arrival;
- `hunt` — 76561198870429455#3: `M2A1` hunters whenever 180 energy is there, for our nearest escort, hitting whatever of
  ours stands beside them; his escorts stay home;
- `blob` — けろびー#11's centre: `M5R5` (his `T3M8R5` is 1180 energy, one spawn holds 1000) hold (50,52), fire at
  anything within three, chase what comes within five of them or of the point (leashed at eight), kite our armed creeps
  at two;
- `siege` — `M5A5` gather five cells out of his block, and every five go as a wave for our nearest escort through our
  ramparts (the escort when beside it, else our armed creep off a rampart beside them, else the rampart on their way);
- `harvest` — the enemy spawn gets +11 a tick (a W5 economy from tick 1).

The scripted enemy moves by distance fields (terrain and structures, cached until one dies), not by a search per creep
per tick; a creep with no live MOVE counts as a structure — without that the first crippled sieger in the one-cell pass
at x=6 held every later wave behind it for four thousand ticks.

**Bot against bot**: `BOT2=<file url of a SEPARATE copy of a build's package>` — the enemy runs that bundle with its own
module graph and view (`world.perspective`), its console goes to `out/run-…-bot2.enemy.log`; `ECON2` / `ECON2_FROM` /
`ECON2_COST` give it an economy it does not play, as in the basic stub.

## Commands

```shell
NODE=$(ls -d ~/.gradle/nodejs/node-*/bin/node | tail -1)             # node is not on PATH here
export NODE_OPTIONS="--max-semi-space-size=2 --max-old-space-size=192" # what regress.sh sets for every run
cd tools/stub/escortrunadvanced
$NODE --import ./register.mjs run.mjs 5000 none                        # default map, we are the bottom player
START=top $NODE --import ./register.mjs run.mjs 5000 siege+harvest     # we are the top player
MAP=map-6ab92799.txt $NODE --import ./register.mjs run.mjs 5000 race
GAME=~/ScreepsArena/games/6ab92172064dc9ae13aaad9c/game.json $NODE --import ./register.mjs run.mjs 5000 hunt
TRACE=250-260 $NODE --import ./register.mjs run.mjs 260 race           # per-tick positions of every creep
cp -R ../../../build/js/packages/screeps-kotlin-arena-starter /tmp/er-adv-b   # a separate copy for the second side
BOT2=file:///tmp/er-adv-b/kotlin/screeps-kotlin-arena-starter/season4/escortrunadvanced/EscortRunAdvanced.export.mjs \
  $NODE --import ./register.mjs run.mjs 5000
zsh regress.sh gate    # the gate lines only (what tools/land.sh runs as `land`), ~20 s
zsh regress.sh v9      # the gate plus the open lines, ~40 s; logs in out/run-v9-…, runner output in out/stdout-v9-…
cp -R ../../../build/js/packages/screeps-kotlin-arena-starter /tmp/er-adv-a   # freeze a build: the session that owns
BOT=file:///tmp/er-adv-a/kotlin/screeps-kotlin-arena-starter/season4/escortrunadvanced/EscortRunAdvanced.export.mjs \
  zsh regress.sh v9    # this worktree rebuilds while you run, and a suite then mixes two builds
```

The runner prints `cpu t=N` every 100 ticks — the bot's `loop()` wall time in Node (max, average, ticks over 50 and over
100 ms; the live limit is 100 ms) — a status line with both sides' escorts (`F` — on a flag), and at the end a `cpu:` and
a `done:` line with the outcome, survivors, escort hits, tower shots and the bot's error count. The bot's console and the event list
(orders, deaths, broken walls and ramparts, the siege waves, the race's flag choice) go to `out/run-<tag><scenario>.log`.

## The gate

`tools/land.sh` runs `regress.sh land`: every stdout line must say `PASS` with `errors: 0`; a PASS is a WIN. It sets
`STUB_LANDING_BASE` (main at the landing): when the landing changes nothing this bot is built from or run by — only other
arenas' packages, stubs and folders, `tools/`, `docs/`, prose — only the SMOKE lines run (`none` from both sides, ~1 s);
this bot's package, this stub, its arena folder, `types/` or the build run the full gate.

## Open findings

Lines the current bot loses are `open`: run and printed as `OPEN` outside the `land`/`gate` tags, never in the gate. The
split below is v9's (27.09.2026); each new version reruns the full suite (`zsh regress.sh <tag>`) and moves lines between
the two — so far the `+harvest` lines are the ones that move (v7 drew siege+harvest on 6ab92799, v8 and v9 win it).

- `6ab9267a-bottom:blob+harvest`, `6ab9267a-top:blob+harvest` — nobody dies on either side in 5000 ticks while his blob
  of M5R5 grows in the centre (55 by the end). From t=1909 (bottom) the home army's mode flips every tick — `SIEGE
  M3T42(7,8) … sim=win/1t … group=16/16` on one tick, `HOLD (was siege)` on the next, 1045 times (top: 1658) — so the
  army never leaves home. Under v8 the same lines had no operation line at all, and the home spawn stood at 1000 energy
  with all eight cells round it held by our creeps.
