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
REPLAY=6ab9349f $NODE --import ./register.mjs run.mjs 5000 chase                            # Hardy#3's model on v17's lost match
zsh regress.sh gate                                                                         # the gate, one line per scenario
zsh regress.sh rival                                                                        # the stachu3478#5 set, never in the gate
zsh ab.sh file://<old build>/.../PainAndGainAdvanced.export.mjs -                              # two builds, 11 records x both sides x chase/line
```

Env: `MAP` (default `map-live1.txt`), `START=p1|p2` (the side the bot drives; the other side is the script),
`REPLAY=<game id | id prefix | path>` (a played match: its terrain, bodies and start cells, and our side is the one
we played — `US=<name>`, default temik911; MAP and START are then ignored; looked up in `./replays/`, then in
`~/ScreepsArena/replays/`, fetched by `tools/match-log.py replay <id>`), `LOGTAG`, `BOT=<bundle url>` (any build, e.g.
an older commit's `build/js/packages/screeps-kotlin-arena-starter` copied aside), `NOCLOCK=1` (`getCpuTime()` answers 0
— deterministic on any machine; two runs give byte-identical logs), `TRACE=t0-t1`, `LINETRACE=t0-t1` (the `line` or `chase`
script's own state per tick: its head, contact, cohesion, each creep's distance and slot), `STATUS=n` (a `stub t=`
line to stdout every n ticks; the log has one every 100).
The first argument caps the ticks (at most 5000). Logs go to `out/` — the bot's console, the `stub t=` lines, a
`=== STUB ===` block (tower shots/damage/heals/energy fed per side, the strongest effect of each type and how many
ticks a side held it, hits lost to `eff_hits_loss`, moves made under a fatigue multiplier and the fatigue it added)
and `=== EVENTS ===` (captures, tower changes of hands, tower shots, deaths). Every run also prints `entry:` — the
first tick a fighter of each side stands within three of the other's and the hits each side lost 20/50/100 ticks later
—, `fight from contact +100:` — what each role of each side did, counted like `tools/pga-replay.py fight <id> <t0> <t1>`
counts a record (a attack, r ranged, R mass, h heal beside, H heal from range) — and the roles each side's single
attacks and shots went to in the first 50 ticks; with `REPLAY` the record's own outcome and `record entry:` beside it.

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
- `chase` — a model of **Hardy#3**, who routed v17 and v18 in 200-300 ticks (`run.mjs` has the numbers above
  `CHASE`): his pullers stay home; the other fourteen walk as one clump at the heavy pace to the centre flag, send the
  nearest light onto it from three cells and walk on toward our nearest fighter round the swamps, the heavy melee in
  front with the lights beside them, heavy healers one rank and heavy ranged two behind, never waiting for the rear;
  healers escort (heavy healers the nearest heavy melee, light healers the nearest melee) and heal what is hurt beside
  them; five cells out the ranks collapse into a brawl — melee and healers at one, ranged at two, mass attack when it
  out-damages one shot; with our army dead the clump goes for our pullers. The same machinery as `line` (`formation()`
  with a profile), so a third opponent is a third profile.
- `chase+sit` — the same clump takes the centre and holds it, turning on our fighters only when one comes within ten of
  the flag: a fighter who outscores a fortress (5 a tick to its 3) without ever walking to it. It is what the fatigue
  flag's score rule (v20) is measured on — the rule has to take the flag in time to win on the score.
- `chase+sit+wide` — as `chase+sit`, and from t=150 (after the bot has read him as a fighter) his light melee stands on
  the hits-loss flag nearer his clump: 9 a tick to the 8 a fortress can hold, and nobody comes. It is what v23's exit
  from a fortress that loses on the score is measured on (v22: 16 of 26 lost on the score, v23: 6).
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
records of stachu3478#5's live matches, `line` on the two gate maps, `chase` and `ghost` on three records of Hardy#3's
(≈ 45 s; a line whose record is missing is skipped with a warning on stderr); any other tag runs all three kinds
together with the `grind` lines (the other side of each map, `mirror`). The rival lines are never in the gate: they are the target, not a guard.
Under `tools/land.sh` a landing that touches neither this bot's package, this folder, its arena folder nor anything
shared (`types/`, the build, shared starter code) runs only the SMOKE lines cut to 300 ticks (≈ 0.5 s). `JOBS=n` runs
lines in parallel (default 1). V8's heap is capped as in the basic stub (`STUB_NODE_FLAGS`).

## The engine against the records (27.09.2026)

- **Combat.** Every recorded action of Hardy's match 6ab9349f (contact +70 ticks: attacks, shots, mass attacks, heals
  beside and from range, with both sides' flag modifiers and parts dying front to back) run through the stand's
  formulas gives the recorded hits in 536 cases of 542; the six others are the ticks our tower fired, which the check
  left out. No melee counter-damage shows up in the records either.
- **Flags change hands a tick after the step.** Our puller stood on the fatigue flag after tick 64 of 6ab9349f, the
  flag and its tower changed owner in tick 65, our heavies still moved at ×1 fatigue in 65 and at ×2 from 66. The stand
  took the flag in the tick of the step until this was measured — two ticks early, and every `ghost` run left our
  recorded cells before contact. Now (`world.mjs` step 0) a `ghost` run with the version that played the record walks
  our recorded cells tick for tick until 7-16 ticks AFTER contact: v6 on 91924 (contact 314, first off at 325), v9 on
  91b4b (212 / 225) and 91b62 (209 / 223), v17 on 9349f (128 / 135), v18 on 93d04 (121 / 134).

## How close the rival models are to the live matches (27.09.2026, on the corrected engine)

Each version against the records (one bundle per commit, built in a detached worktree and passed with `BOT=`,
`NOCLOCK=1`).

**stachu3478#5.** Live (the match store): v5 1-1, v6 1-3, v7 1-3, v9 2-2 — 5 wins of 13; per record 91898 v5 lost,
91924 v6 lost, 919a7 v6 won, 91a90 v7 lost, 91b4b v9 won, 91b62 v9 lost.

- `line`: our army won the fight 2 times of 30 for v5, v6, v7, v9 and v11 (v6 twice) against the live 5 of 13 — the
  model is his good fight, stronger than he was on average. The version that played a record reproduces its live
  outcome on 4 of 6 — every loss, neither win (919a7, 91b4b); v6 on 91924 lost 20 204 hits to his 10 200 in the 100
  ticks after contact against the record's 20 025 / 4 400. Contact at t=155-185 (records: 102-314). v19 wins 5 of 6.
- `line+lag`: v6 won 1 of 6 and outlasted him on score in 1 more, v9 won 4 and held one more to 7:7, v19 won 6.
- `ghost`: see the engine section — the approach is the live one; after contact we win nearly every exchange, his
  targets and heals being by rule, not his.

**Hardy#3.** Live: v4 lost (916f5, 294 ticks), v17 lost (9349f, 298), v18 lost (93d04, 311) — three of three.
(Hardy#1 is another bot: in 91564 it took the centre and SAT on it, and v3 won on score; v18 beat it too, 6ab93bca.
`chase` is his #3.)

- `chase` against the version that played the record: v17 on 9349f and v18 on 93d04 — centre taken at t=75 (live 75),
  our FIGHT at t=116 (115), contact t=128 (128 and 121), our army destroyed at t=331 and 336 (298 and 311) with eight
  of his left (nine and eight). Hits lost ours/his after contact: +20 3 704 / 4 978 (record 5 071 / 6 099 and
  2 888 / 3 190), +50 9 800 / 8 822 (13 438 / 8 962 and 15 037 / 6 914), +100 19 200 / 9 877 (20 400 / 6 600 and
  20 400 / 5 548). v17 and v18 play identically against it (v18's change — leave a fight that kills nobody for 300
  ticks — never fires).
- **v19 destroys it**: on 9349f, 93d04 and 916f5 his army is dead at t=333-359 with 14-15 of ours alive. v19's army
  does not walk out to meet him — its FIGHT comes at t=136 instead of 116 — and the fight is under our tower, which it
  can feed; the brawl his clump wins in the open it loses there.
- `ghost`: our side walks its recorded cells until contact + 7-13, then the ghost loses the exchange.

## Not modelled / assumed

The target's damage-taken modifier scales a tower's shot; a tower's heal is not scaled by its owner's heal modifier;
hits loss is not scaled by damage taken. Towers can be damaged (3000 hits, mass attack reaches them); containers
cannot. No melee counter-damage (as in the basic stub). The enemy scripts are rules, not a live opponent — `line`
included: it has no randomness and none of his lapses except the one `+lag` models.
