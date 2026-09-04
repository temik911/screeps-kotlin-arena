# Offline stub harness — Escort Run

Runs the compiled bot (`../../../build/js/.../season4/escortrun/EscortRun.export.mjs`, i.e. the build of the worktree
this directory lives in) under Node against a stub of the Arena runtime: `game/` (constants, prototypes, Dijkstra
`searchPath`, simultaneous movement with swaps and chains, fatigue by part type, front-to-back part damage and healing
from the tail of the body, spawns, sources, harvest/transfer), `arena/season_4/escort_run/basic/prototypes.mjs`
(`EscortCreep`), and `world.mjs` (the engine step). The arena rules — flags, the win, the enemy scripts — live in
`run.mjs`. What each scenario is for is in `docs/escort-run.md`.

**Pull is modelled after the World engine source** (`processor/intents/movement.js`, `creeps/pull.js`,
`creeps/_add-fatigue.js`, verified 04.09.2026): the puller issues `pull(target)` and its own move, the pulled creep
issues a move into the puller's cell; the pulled creep then moves regardless of its own fatigue, its movement fatigue
is charged to the head of the chain, and its live MOVEs shed the head's fatigue on every tick the link exists. The
API refuses a `move` of a tired creep (`ERR_TIRED`) exactly as the Arena typings say. `PULL_MODEL=off` runs the
pessimistic world where `pull` does nothing — the bot must notice (it does: three failed rolls and it stops buying
pullers).

```shell
NODE=$(ls -d ~/.gradle/nodejs/node-*/bin/node | tail -1)                       # node is not on PATH here
$NODE --import ./register.mjs run.mjs 2000 none                                  # synthetic map, a dead opponent
$NODE --import ./register.mjs run.mjs 2000 melee+harvest                         # M7A7 stream fed by a W5 economy
PULL_MODEL=off $NODE --import ./register.mjs run.mjs 2000 race                   # pull does not work in this world
TRACE=140-200 $NODE --import ./register.mjs run.mjs 200 melee                    # per-tick positions of every creep
MAP=map-match1.txt START=match2 $NODE --import ./register.mjs run.mjs 2000 rush  # a live map dump; we are player 2
zsh regress.sh v1                                                                # every scenario, one line each, logs in out/
```

`map-match1.txt` is the map of the first live match (04.09.2026) and comes with its measured layout: spawns at (9,90)
and (9,9) starting at **500** energy, a source in each base corner plus two on the far edge, two 2500-containers, the
flags in the far corners, and the real escort body `MTTTT`×10 (weight 40, ten MOVE — four ticks a cell on plain,
twenty on swamp). Running with `MAP=` switches to that layout; without it the synthetic map is used (a guess at the
lobby picture: an X of open ground, bases on the left edge, flags on the right, edge passes, a swamp ring round the
centre, spawns at 1000). The replay of match 1 lands the escort on the flag at t=259 against the live ~258.

Enemy scripts (combine with `+`): `none` (nothing moves), `race` (its escort walks to its flag), `rush` (a `M5R5` per
1000 energy sent at our escort, kiting our armed creeps at two cells), `melee` (`M7A7` per 910), `guard` (fighters
stay within two of their escort), `hunt` (fighters go for our escort, their escort walks), `train` (a 10-MOVE puller
first, pulled by the World scheme), `harvest` (the enemy spawn gets +10 a tick as if it had a W5 harvester from tick 1).
The runner prints a `cpu t=N: max=..ms` line every 100 ticks — the bot's `loop()` wall time in Node — and a
`done:` line with the outcome, survivors, escort hits and error count.

`tools/land.sh` runs `regress.sh land` as the landing gate: every line must say `PASS` with `errors: 0`. Scenarios the
first version loses by construction (`open` lines in `regress.sh`) are run in full mode only — see the open findings in
`docs/escort-run.md`.
