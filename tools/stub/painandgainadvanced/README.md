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
zsh regress.sh gate                                                                         # the gate, one line per scenario
```

Env: `MAP` (default `map-live1.txt`), `START=p1|p2` (the side the bot drives; the other side is the script),
`LOGTAG`, `BOT=<bundle url>`, `NOCLOCK=1` (`getCpuTime()` answers 0 — deterministic on any machine; two runs give
byte-identical logs), `TRACE=t0-t1`, `STATUS=n` (a `stub t=` line to stdout every n ticks; the log has one every 100).
The first argument caps the ticks (at most 5000). Logs go to `out/` — the bot's console, the `stub t=` lines, a
`=== STUB ===` block (tower shots/damage/heals/energy fed per side, the strongest effect of each type and how many
ticks a side held it, hits lost to `eff_hits_loss`, moves made under a fatigue multiplier and the fatigue it added)
and `=== EVENTS ===` (captures, tower changes of hands, tower shots, deaths).

## Scenarios (the enemy)

- `none` — the enemy does nothing.
- `rush` — every fighter walks down a flow field at the nearest creep of ours: melee to one, ranged to three, healers
  to the most wounded mate within six or to their own armed creeps; melee hit the adjacent lowest hits, ranged single
  shots at the lowest hits in three, healers heal the most wounded in three.
- `farm` — the fighters take, as one group, the nearest flag not theirs with no armed creep of ours within six of it;
  a creep with one of our armed creeps within six steps away; they fire at what comes within three.
- `mirror` — a second, fully separate instance of the same bundle drives the enemy (`hooks.mjs` hands `?mirror` down
  every relative import, so it has its own module graph); its console goes to the log as `[mirror] ...`.

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
farm — 5000 ticks, ≈ 5 s serial on v3); any other tag adds the `grind` lines (the other side of each map, `mirror`).
Under `tools/land.sh` a landing that touches neither this bot's package, this folder, its arena folder nor anything
shared (`types/`, the build, shared starter code) runs only the SMOKE lines cut to 300 ticks (≈ 0.5 s). `JOBS=n` runs
lines in parallel (default 1). V8's heap is capped as in the basic stub (`STUB_NODE_FLAGS`).

## Not modelled / assumed

The target's damage-taken modifier scales a tower's shot; a tower's heal is not scaled by its owner's heal modifier;
hits loss is not scaled by damage taken. Towers can be damaged (3000 hits, mass attack reaches them); containers
cannot. No melee counter-damage (as in the basic stub). The enemy scripts are rules, not a live opponent.
