# Offline stub harness — Pain and Gain

Runs the compiled bot (`../../../build/js/.../season4/painandgain/PainAndGain.export.mjs`, i.e. the build of the
worktree this directory lives in) under Node against a stub of the Arena runtime: `game/` (constants, prototypes,
Dijkstra `searchPath`, simultaneous movement with swaps and chains, fatigue by part type, front-to-back part damage
and healing from the tail of the body, as in the engine), `arena/season_4/pain_and_gain/basic/prototypes.mjs`
(`ScoreFlag`), and `world.mjs` (flags, score, the global debuffs as `effects`, the early end on annihilation or an
unreachable lead). There are no spawns: both armies are placed at the start. What each scenario replays and why it
exists is in `docs/pain-and-gain.md`.

```shell
NODE=$(ls -d ~/.gradle/nodejs/node-*/bin/node | tail -1)                       # node is not on PATH here
$NODE --import ./register.mjs run.mjs 2000 rush                                 # synthetic point-symmetric map
MAP=map-match8.txt START=match2 $NODE --import ./register.mjs run.mjs 2000 hunter   # a live map; we are player 2
MAP=map-match6.txt $NODE --import ./register.mjs run.mjs 2000 sleeper           # a live map where we were player 1
SLEEP=300 MAP=map-match7.txt START=match2 $NODE --import ./register.mjs run.mjs 2000 sleeper   # the sleeper wakes at 300
zsh regress.sh v9                                                               # every scenario, one line each, logs in out/
```

Maps `map-match1..8.txt` are the `DEBUG_MAP` dumps of the live matches (`#` wall, `~` swamp, `m`/`e` the two armies'
start cells, `F` the flags). Enemy scripts: `none` (a dead bot), `scouts` (only the two scouts take flags), `grab`
(the army camps on our side), `rush` (everyone at the nearest of ours), `greedy`, `army` (the match-3 opponent: D5,
own A3, hover by its corner, engage within 20, healers adjacent, focus the lowest hits, sweep after we die), `hunter`
(match 4 and 8: D5 with the whole army, then straight at ours), `kite` (match 5: bait melee plus kiting ranged),
`sleeper` (idle until `SLEEP`, default 500, then rush). Modes combine with `+`.

`tools/land.sh` runs `regress.sh` as the landing gate: every line must say `PASS` with `errors: 0` — the enemy army
destroyed, or the match ended with our score ahead. A new arena's harness starts as a copy of a sibling directory
(`game/` is arena-agnostic; the runner and `world.mjs` are where the arena rules live).
