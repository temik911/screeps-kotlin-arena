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
TRACE=510-600 MAP=map-match11.txt $NODE --import ./register.mjs run.mjs 2000 sleeper   # per-tick positions of every creep in that tick range (reading a chase)
zsh regress.sh v9                                                               # every scenario, one line each, logs in out/
```

Maps `map-match1..25.txt` and `map-match28.txt` are the `DEBUG_MAP` dumps of the live matches, numbered by match — the gaps are matches whose map was one already here (`#` wall, `~` swamp, `m`/`e` the two armies'
start cells, `F` the flags). Enemy scripts: `none` (a dead bot), `scouts` (only the two scouts take flags), `grab`
(the army camps on our side), `rush` (everyone at the nearest of ours), `greedy`, `army` (the match-3 opponent: D5,
own A3, hover by its corner, engage within 20, healers adjacent, focus the lowest hits, sweep after we die), `hunter`
(match 4 and 8: D5 with the whole army, then straight at ours), `kite` (match 5: bait melee plus kiting ranged),
`sleeper` (idle until `SLEEP`, default 500, then rush), `nine` (match 9: straight at our army from tick 1, our
healers shot first, its own healers two cells behind its line and stepping away from our armed creeps), `twelve`
(match 12: D5, a loop through our half up to its H4 corner, then the match-9 hunt at full speed), `fourteen` (match
14: `nine` plus rotation — a fighter below half its hits walks to its healers, stays out of our armed creeps' reach and
returns above 0.9; its healers stand adjacent to the most damaged), `block` (matches 14–16: once hunting, the army
moves as one block — melee in the front row, ranged two cells behind the melee anchor, healers three behind — fires at
whatever is in range and rotates its damaged fighters back to the healers), `wing` (match 17: the block with its
ranged in the front row — the line stops three cells from our nearest creep and shoots, its melee only step to what
comes within two, a ranged with one of our armed creeps within two backs off two rows), `spread` (match 19: every enemy
creep takes a flag of its own, two per flag, sits on it, steps away from our armed creeps within six and comes back —
it never fights as an army and farms the flags' points), `roost` (match 25: the same dispersal, but the creep never
leaves its flag — it does not step away from ours either; the live opponent of match 25 held all seven flags by t=80 and
never moved again, and our own log reported every combat enemy stationary), `farm` (live match 26: the army moves as
one blob to the flag nearest the blob that it does not own, and never engages — a creep with one of our armed creeps
within six steps away and comes back after; its two runners each sit on a flag. That match ended with both armies at
full strength, 902 hits of damage in 1500 ticks and not one death, and it won on points 23408:12721). Modes combine with `+`. The runner prints
a `cpu t=N: max=..ms at t=M slow(>50ms)=K` line every 100 ticks — the bot's `loop()` wall time in Node, a relative
measure of the arena's 100 ms tick budget (match 16 timed out three ticks in the thick of the fight). The bot itself
prints `bfs t=N max=K` every ten ticks — the most flow-field BFS runs in one tick since the previous line (the live
timeouts of matches 16–18 all fell inside flow-field BFS).

`regress.sh` runs its scenarios **in parallel**, `JOBS` at a time (default 8, `JOBS=14 zsh regress.sh tag` to widen):
each scenario is its own node process with its own map, log and result file, and the report is put back in order at the
end. Serially the suite took over three minutes; parallel it is under a minute, and the report is byte-identical. This
is not only about impatience — `tools/land.sh` runs every arena's suite one after another, so a slow suite here delays
the landing of every other arena. Logs still land in `out/`, one file per scenario per tag; a full run writes a few
hundred megabytes there, so `out/` is worth emptying between investigations (it is gitignored and every log is
reproducible by re-running its scenario).

`tools/land.sh` runs `regress.sh` as the landing gate: every line must say `PASS` with `errors: 0` — the enemy army
destroyed, or the match ended with our score ahead. A new arena's harness starts as a copy of a sibling directory
(`game/` is arena-agnostic; the runner and `world.mjs` are where the arena rules live).
