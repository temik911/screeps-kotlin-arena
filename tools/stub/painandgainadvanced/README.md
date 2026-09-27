# Offline stub harness — Pain and Gain ADVANCED

Runs the compiled bot (`../../../build/js/.../season4/painandgainadvanced/PainAndGainAdvanced.export.mjs`, the build of
the worktree this folder lives in) under Node against a stub of the Arena runtime. Copied from the basic stub
(`tools/stub/painandgain/`) and adapted; nothing is imported across the two folders.

- `game/` — constants (with the live `TOWER_FALLOFF_RANGE=21`), prototypes, Dijkstra `searchPath`; `StructureTower`
  checks and returns the engine's codes (`ERR_NOT_OWNER`, `ERR_NOT_ENOUGH_ENERGY`, `ERR_TIRED`, `ERR_NOT_IN_RANGE`),
  `StructureContainer` is neutral and indestructible (`hits` undefined, as the live probe printed).
- `arena/season_4/pain_and_gain/advanced/` — `ScoreFlag`, `TICKS_LIMIT=5000`, `MAX_SCORE_PER_TICK=43`, `FLAG_TYPES`.
- `world.mjs` — the engine: simultaneous movement with swaps and chains, fatigue by part type (dead parts weigh; a CARRY
  weighs only while energy fills it, in body order, 50 each), damage front to back and healing from the tail; flags
  captured by standing on them and kept after the creep leaves; score capped at 43 a tick; effects on every creep of
  the owner with `endTime` 5010 as live — weapons ×0.8/×0.6, heal ×0.75/×0.5, damage taken ×1.1, fatigue ×2/×4 (on the
  fatigue a move adds), hits loss `offset` −1/−2 a tick (taken like damage, the same tick's healing compensates);
  towers owned with their flag, store 10, 10 energy and 10 ticks of cooldown per action, 1000/600 at range 1 and −5 %
  a cell to 50/30 at range 20.
- `run.mjs` — the fixed layout (11 flags, 4 towers, 8 containers of 2500, 16 creeps a side with the live ids
  `pg_player{1,2}_<role>`), a map, the enemy script, the report.

```shell
NODE=$(ls -d ~/.gradle/nodejs/node-*/bin/node | tail -1)                                    # node is not on PATH
$NODE --import ./register.mjs run.mjs 5000 none                                             # map-live1, we are player 1
MAP=map-live2.txt START=p2 $NODE --import ./register.mjs run.mjs 5000 rush                  # map 2, we are player 2
TRACE=300-310 $NODE --import ./register.mjs run.mjs 400 farm                                # our cells/fatigue/energy per tick
REPLAY=6ab91b62 $NODE --import ./register.mjs run.mjs 5000 line                             # stachu3478#5's model on a live match's map
REPLAY=6ab91b62 $NODE --import ./register.mjs run.mjs 5000 ghost                            # his recorded track on the same map
zsh regress.sh gate                                                                         # the gate, one line per scenario
zsh regress.sh rival                                                                        # the stachu3478#5 set, never in the gate
```

Env: `MAP` (default `map-live1.txt`), `START=p1|p2` (the side the bot drives; the other side is the script),
`REPLAY=<game id | id prefix | path>` (a played match: its terrain, bodies and start cells, and our side is the one
we played — `US=<name>`, default temik911; MAP and START are then ignored; looked up in `./replays/`, then in
`~/ScreepsArena/replays/`, fetched by `tools/match-log.py replay <id>`), `LOGTAG`, `BOT=<bundle url>` (any build, e.g.
an older commit's `build/js/packages/screeps-kotlin-arena-starter` copied aside), `NOCLOCK=1` (`getCpuTime()` answers 0
— deterministic on any machine; two runs give byte-identical logs), `TRACE=t0-t1`, `LINETRACE=t0-t1` (the `line`
script's own state per tick: its head, contact, cohesion, each creep's distance and slot), `STATUS=n` (a `stub t=`
line to stdout every n ticks; the log has one every 100).
The first argument caps the ticks (at most 5000). Logs go to `out/` — the bot's console, the `stub t=` lines, a
`=== STUB ===` block (tower shots/damage/heals/energy fed per side, the strongest effect of each type and how many
ticks a side held it, hits lost to `eff_hits_loss`, moves made under a fatigue multiplier and the fatigue it added)
and `=== EVENTS ===` (captures, tower changes of hands, tower shots, deaths). Every run also prints `entry:` — the
first tick a fighter of each side stands within three of the other's and the hits each side lost 20/50/100 ticks later
— and with `REPLAY` the record's own outcome and `record entry:` beside it.

## Scenarios (the enemy)

- `none` — the enemy does nothing.
- `rush` — every fighter walks down a flow field at the nearest creep of ours: melee to one, ranged to three, healers
  to the most wounded mate within six or to their own armed creeps; melee hit the adjacent lowest hits, ranged single
  shots at the lowest hits in three, healers heal the most wounded in three.
- `farm` — the fighters take, as one group, the nearest flag not theirs with no armed creep of ours within six of it;
  a creep with one of our armed creeps within six steps away; they fire at what comes within three.
- `mirror` — a second, fully separate instance of the same bundle drives the enemy (`hooks.mjs` hands `?mirror` down
  every relative import, so it has its own module graph); its console goes to the log as `[mirror] ...`.
- `line` — a model of **stachu3478#5**, the bot that beat v5-v7 live, measured on six records of his matches (the
  comment above `LINE_GOAL` in `run.mjs` has the numbers): all sixteen come at our army as one body and take no flag;
  the heavy melee are the front (kept in front however stripped), the other roles hold ranks behind it (heavy ranged and
  heavy healers 2, light ranged and light healers 3, pullers 4) and the head steps only while nobody is more than two
  behind its slot — it swaps with a mate that belongs further back, sidesteps round its own members, and creeps one
  step in five ticks until ten cells from us, then pushes (his records: contact at t=102-314). In contact the melee
  hold 2 from our nearest fighter, ranged and healers 3, pullers 5; a ranged or healer with one of ours adjacent steps
  back. Melee swing at the adjacent lowest hits; ranged shoot the lowest hits in reach, a mass attack when it
  out-damages one shot; healers walk to the most wounded mate and heal it adjacent, two for one that lost 50+ hits the
  tick before. `line+lag`: the same, but the healers stay three cells behind their rank and never walk to the wounded —
  his two LOST records had his heavy healers 7-11 behind a front being stripped.
- `ghost` (needs `REPLAY`) — his recorded track: every creep of his steps each tick toward the cell the record has it on
  (a path step when it fell behind; a creep of ours on the cell refuses it), pays no fatigue (the record did), fires and
  heals by the `rush` rules, and stands where the record ends if it outlives it. The report adds how often his creeps
  were off their recorded cell and the first tick OUR creeps left ours — the stand's engine against the live one. Its
  opening and approach are the real ones; its exchange is not.

In `rush` and `farm` the enemy's two pullers sit on its two tower flags (nearest its start), withdraw from the
container beside them and keep the tower full once it is theirs; its towers shoot the nearest creep of ours in range,
else heal the most wounded of theirs.

## Maps

`map-live1.txt` — replay `6ab90e68064dc97e8daaaa4b` (we were player 1), `map-live2.txt` — the v1 console of
`6ab90ed9064dc9b264aaaa5b` (we were player 2). The layout is fixed, the terrain is random per match and NOT point
symmetric (≈ 1 600 cells differ from their mirror), so each map's other side is another match. More maps:

```shell
python3 mapfrom.py <replay.json.gz | console log with `map NN` lines | ~/ScreepsArena/games/<id>/ | game id> -o map-live3.txt
```

It prints `START=p1|p2` — the side we played — to stderr. Every stored match has its terrain in `game.json`, so a
match without a replay or a map dump works too.

## regress.sh

One line per scenario: `PASS|FAIL <map>-<side>:<scenario> <outcome> score a:b alive x:y t=<end> tw <our:their
tower shots> | errors: N `. PASS = we won (enemy army destroyed, or our score ahead when the match ended — unreachable
lead, the time limit or the line's cap) and zero errors (a tick whose `loop()` printed a stack trace; a stub crash
gives no done line and FAILs). `gate`/`land` run the `run` lines (each live map from its live side against none, rush,
farm — 5000 ticks, ≈ 5 s serial on v3); `rival` runs only the `rival` lines — `line`, `line+lag` and `ghost` on six
records of stachu3478#5's live matches plus `line` on the two gate maps (≈ 40 s; a line whose record is missing is
skipped with a warning on stderr); any other tag runs all three kinds together with the `grind` lines (the other side
of each map, `mirror`). The rival lines are never in the gate: they are the target, not a guard.
Under `tools/land.sh` a landing that touches neither this bot's package, this folder, its arena folder nor anything
shared (`types/`, the build, shared starter code) runs only the SMOKE lines cut to 300 ticks (≈ 0.5 s). `JOBS=n` runs
lines in parallel (default 1). V8's heap is capped as in the basic stub (`STUB_NODE_FLAGS`).

## How close `line` and `ghost` are to the live matches (27.09.2026)

Each version against the six records (one bundle per commit, built in a detached worktree and passed with `BOT=`,
`NOCLOCK=1`). Live against him (the match store): v5 1-1, v6 1-3, v7 1-3, v9 2-2 — 5 wins of 13; per record 91898
v5 lost, 91924 v6 lost, 919a7 v6 won, 91a90 v7 lost, 91b4b v9 won, 91b62 v9 lost.

- `line`: our army won the fight 2 times of 6 for v5 and 0 of 6 for each of v6, v7, v9 and v11 — 2 of 30 against the
  live 5 of 13: the model is his good fight, stronger than he was on average. The version that played a record
  reproduces its live outcome on 4 of 6 — every loss, neither win (919a7, 91b4b). The losses come out in their live
  shape: v6 on 91924 lost 20 206 hits to his 5 577 in the 100 ticks after contact against the record's 20 025 / 4 400;
  v9 on 91b62 13 839 / 7 674 against 10 642 / 7 960. Contact comes at t=151-185 (records: 102-314).
- `line+lag`: v6 won 1 fight of 6 and outlasted him on score in 1 more (alive 5:6); v9 won 1 and outlasted him in 2
  (5:7, 4:7) — his weaker form, the one he played in the two records he lost.
- `ghost`: with the version that played the record, our side walks its recorded cells tick for tick until 4-12 ticks
  before contact (v6 on 91924: first off at t=297, contact 314; v9 on 91b4b at 208 / 212 and on 91b62 at 202 / 209)
  — the stand's movement, fatigue, flags and effects are the live engine's up to the fight. After contact we win
  nearly every exchange: his targets and heals are by rule, not his.

## Not modelled / assumed

The target's damage-taken modifier scales a tower's shot; a tower's heal is not scaled by its owner's heal modifier;
hits loss is not scaled by damage taken. Towers can be damaged (3000 hits, mass attack reaches them); containers
cannot. No melee counter-damage (as in the basic stub). The enemy scripts are rules, not a live opponent — `line`
included: it has no randomness and none of his lapses except the one `+lag` models.
