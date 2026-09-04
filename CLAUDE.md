# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A starter for writing [Screeps Arena](https://arena.screeps.com/) bots in Kotlin. Kotlin/JS compiles to ES2015 ESM, which the Arena client loads. Each "arena" is a game mode with its own `loop()` entrypoint; the Arena client calls `loop()` once per tick.

## Commands

```shell
./gradlew build                  # compile Kotlin/JS for all modules
./gradlew setup-screeps-arenas   # build, then npm-install each arenas/* folder (symlinks node_modules -> build output)
./gradlew allTests               # run all tests
./gradlew :starter:jsTest        # run the starter module's tests
```

Run a single test by class/method:

```shell
./gradlew :starter:jsTest --tests "sourcemaps.SourceMapResolverTest.maps_kotlin_frames_and_leaves_game_frames"
```

After `setup-screeps-arenas`, point the Arena client at an `arenas/<mode>` folder and hit play. Plain `./gradlew build` is enough to push code changes to an already-set-up arena (node_modules is symlinked to the build dir); re-run `setup-screeps-arenas` only after adding a new arena folder.

`setup-screeps-arenas` is **incompatible with the configuration cache** (it calls `Task.project` at execution time) — run it as `./gradlew setup-screeps-arenas --no-configuration-cache`. Plain `build` works with the cache.

Verify build success by the `BUILD SUCCESSFUL` / `BUILD FAILED` line, not by piping to `tail` (a `... | tail -N && echo OK` reports the exit code of `tail`, always 0, so "OK" prints even on failure). Note: `:starter:jsNodeTest` can fail transiently the first build after adding a new `@JsExport fun loop()` module (an `ERR_MODULE_NOT_FOUND` on `SourceMapRegistry.mjs` even though the file exists) — a clean re-run (`./gradlew :starter:jsTest --rerun-tasks` or building again) clears it; it does not indicate a code error.

Requires JDK; Gradle (9.0.0 via wrapper) and Node are managed by the wrapper/Kotlin plugin — no global Node install needed.

## Module layout

Two Gradle modules (see `settings.gradle.kts`), both Kotlin Multiplatform targeting JS only:

- **`types`** — Kotlin bindings for the Screeps Arena JS API, built as a `binaries.library()`. This is the dependency, not the game; the real game objects come from the Arena runtime. Bindings live in `types/src/jsMain/kotlin/screeps/api/`. The hand-maintained `types/typings/**/*.d.ts` are the original TypeScript definitions from the Arena client, kept in-repo only as a reference to diff against when the client updates — they are **not** compiled.
- **`starter`** — your bot code, built as `binaries.executable()`, depends on `:types`. Bot logic lives in `starter/src/jsMain/kotlin/`.

## How an arena entrypoint works

Each game mode is a Kotlin source set with a top-level `loop()` function, e.g. `starter/src/jsMain/kotlin/season2/ctf/CaptureTheFlag.kt`:

```kotlin
@OptIn(ExperimentalJsExport::class)
@JsExport
fun loop() = runWithSourceMapSupport { CaptureTheFlag.tick() }
```

The wiring from client to Kotlin is a chain of ESM re-exports:
`arenas/<mode>/main.mjs` → `node_modules/screeps-kotlin-arena-starter/.../<Mode>.export.mjs` (the `@JsExport`ed compiled file). `node_modules` is symlinked into the Gradle build output by `setup-screeps-arenas`.

**Adding a new arena:** add a `loop()` in a new starter source file, copy an existing `arenas/<mode>` folder, edit its `main.mjs` to import the matching `<Mode>.export.mjs` path, then run `./gradlew setup-screeps-arenas` once.

## Cross-arena code reuse — COPY, never share (IMPORTANT)

Each arena's bot lives in its own package (e.g. `season3/spawnstrike/`, `season3/powersplit/`) and must be **self-contained**. To reuse logic from another arena (movement, influence maps, traffic management, pathing, combat scoring), **COPY the code into the target arena's package** — do **not** extract a shared component that multiple arenas import.

Rationale: arenas have different maps, rules, and win conditions. A "shared" component tuned for one arena will be tweaked while tuning that arena, silently changing behavior on the others. Copying keeps each bot's tuning independent: improving one arena can never regress another. Duplication is the intended trade-off here — favor it over premature abstraction.

Concretely: `season3.spawnstrike.InfluenceMap` / `DistanceMap` / `TrafficManager` are good starting points to **copy** into a new arena package and then tune locally; never import them across packages.

## Key conventions

- **Always wrap `loop()` bodies in `runWithSourceMapSupport { ... }`** (`starter/.../sourcemaps/`). Kotlin/JS stack traces point at compiled `.mjs` line numbers; this maps them back to Kotlin source. The mapping data is generated at build time by the `generateSourceMapRegistry` Gradle task into `SourceMapRegistry.mjs`. The starter sets `kotlin.js.ir.output.granularity=per-file` so each Kotlin file compiles to its own `.mjs` (required for the export/source-map scheme).
- **Game API access** goes through `screeps.api.*`. Use `getObjectsByPrototype(Creep::class)` (the KClass overload in `Aliases.kt`) to query game objects.
- **Constants** (`types/.../Constants.kt`) are modeled as `external object`s implementing sealed `Constant<T>` interfaces (e.g. `MOVE : BodyPartConstant`), not enums. Get the underlying JS value with the `.value` extension.
- The Arena API uses JS plain objects: bindings use `@JsPlainObject external interface` and `@JsModule`/`@JsNonModule` file annotations pointing at the runtime modules (`game/utils`, `game/constants`, etc.).
- `Aliases.kt` adds null-safe helpers, e.g. `operator fun Int?.compareTo(other: Int?)` so `store[RESOURCE_ENERGY] < store.getCapacity()` works without manual null handling.

## Parallel sessions — one arena, one worktree (IMPORTANT)

Several Claude sessions work on this repository at the same time, one per arena (Spawn and Swamp, Pain and Gain, and more to come). Every session lives by the same rules; the operator set them on 04.09.2026 after two sessions collided on one branch.

1. **`main` is shared and belongs to nobody.** Nothing is edited or committed on `main` directly, and `main` only ever moves by fast-forward to a session's finished branch.
2. **Each session works in its own worktree on its own branch.** At session start: `EnterWorktree` (or `git worktree add .claude/worktrees/<arena> -b <arena> main`); the worktree lives under `.claude/worktrees/<arena>/`, the branch carries the arena's name (`spawn-and-swamp`, `pain-and-gain`, …; `EnterWorktree` prefixes it with `worktree-`, which is fine) and starts from `main`. All edits, builds, stub runs and commits happen there; `git worktree list` shows who is who. **Before touching the bot — at session start and again before every new change — bring `main` into your branch first:** `git rebase main` in your worktree — always rebase, never merge `main` into the branch: session branches are private, nobody builds on them, so a rewritten branch is pushed with `git push --force-with-lease`; rebuild, and only then edit. This is mandatory, not a courtesy: the later a branch diverges from `main`, the more of the shared files (`CLAUDE.md`, `types/`) it has to merge at the end, and every other session pays for that merge too.
3. **The repo root is the operator's integration checkout, kept on `main`, and no session touches it:** no edits there, no `git merge` / `reset` / `checkout` there, and never move another session's branch. The one exception is the landing script (rule 5), which may fast-forward the root's `main` when the root tree is clean — a fast-forward cannot clobber anything. (What this rule comes from: one session fast-forwarded and then reset another session's branch in the root checkout after each of its own commits; the other session's commit landed on top of eight foreign commits, and the next reset would have dropped it.)
4. **Commit by explicit paths** — `git commit -m "..." -- <paths>`, never `git add -A` or `commit -a`; check `git branch --show-current` before committing; afterwards `git status --short` shows nothing but `.claude/`. Commit messages: English, lowercase, no trailing period, explain *why*.
5. **Every version that is ready for trials goes into `main` — mandatory, and before the first live match with it.** `main` is where the current version of every bot lives, and it must never lag behind what is being played: as soon as a bot is ready for a live match (built, stub-tested, documented), fast-forward `main` to your branch, then play; the match log then refers to a commit that is in `main`. Half-done work stays on the branch; ready work does not. Land with `tools/land.sh [--tag <arena>-vN] [--no-push]` from your worktree: it refuses a branch that is not rebased on `main` or has uncommitted changes, runs `./gradlew build`, runs every `tools/stub/*/regress.sh` (every scenario line must carry the arena's pass marker — `PASS`, or `ENEMY SPAWN DESTROYED` where there are spawns — and zero errors), fast-forwards `main` (`git fetch . <branch>:main`, or `git -C <root> merge --ff-only` when the root is on `main` and clean), tags the landed version if asked, and pushes `main` and the tag to `origin` — pushing `main` is part of landing, so the current bots are never only on one disk; a session branch is pushed only when the operator asks. If `main` moved meanwhile, `land.sh` stops: `git rebase main`, rebuild, rerun, land again. Never land a tree that does not build: every session's `./gradlew build` compiles every package, so a broken package in `main` stops all of them. `origin`'s push URL is SSH.
6. **Shared files are the only places sessions collide** — treat them as such:
   - `CLAUDE.md` holds one line per bot in **Notes**; a bot's rules, version history and tooling notes live in `docs/<arena>.md`, which only its own session edits (this is what keeps `CLAUDE.md` out of every commit — before the split both sessions changed it in every commit). Touch `CLAUDE.md` only for your bot's one line, the rules, or the layout; a rebase or cherry-pick conflict here is resolved by taking `main`'s block and re-applying your own lines, never by carrying another arena's text along.
   - `types/`: a binding another arena will also need goes into its own small commit and into `main` first, so the other session rebases onto it instead of writing the same hunk (the season 4 effect modifiers were committed twice, identically, by two sessions).
   - Gradle files and `arenas/`: touch only your own `arenas/<season>-<mode>/`; a build-script change is announced to the operator before it lands in `main`.
   - Bot code never collides: one package per arena under `starter/src/jsMain/kotlin/<season>/<arena>/`, copied rather than shared (see the cross-arena rule above). Read another arena's package freely, copy from it, but do not edit it — a defect found there goes to the operator, not into a commit.
7. **Tooling is per session.** The offline stub harness lives in `tools/stub/<arena>/` and imports the compiled bundle by a path relative to itself (`../../../build/js/...`), so run it from **your worktree** and it tests what that worktree built; and the Arena client folder for your mode (`~/ScreepsArena/<season>-<mode>/node_modules/screeps-kotlin-arena-starter`) is symlinked to **your worktree's** `build/js/packages/screeps-kotlin-arena-starter` — otherwise a live match runs whatever the root checkout last built, not what you tested.

## Notes

- Existing bot examples by season live under `starter/src/jsMain/kotlin/season1/`, `season2/`, and `tutorial/` — read these for API usage patterns before writing new bots.
- **`season3/spawnstrike/`** is the most developed bot (ranked #1 on its arena) — `InfluenceMap` (influence/damage/danger maps), `DistanceMap` (BFS/flow fields with swamp cost, base zones, entrances), `TrafficManager` (two-phase movement with chains/swaps). Read it for patterns, and **copy** from it per the cross-arena reuse rule above. **`season3/powersplit/`** is an early bot for the Power Split arena (RANGED bonus, WORK economy, central-corridor control).
- **`season4/spawnandswamp/`** — Season 4 "Spawn and Swamp" (basic) bot. Its measured rules, design, version history (one paragraph per live match) and stub harness are in `docs/spawn-and-swamp.md`; the harness itself is `tools/stub/spawnandswamp/`.
- **`season4/painandgain/`** — Season 4 "Pain and Gain" (basic) bot: a fixed army of fourteen, seven score flags with global debuffs, no spawning. Its measured rules, design, version history (one paragraph per live match) and stub harness are in `docs/pain-and-gain.md`; the harness itself is `tools/stub/painandgain/`.
- **`season4/escortrun/`** — Season 4 "Escort Run" (basic) bot: bring a slow 5000-hit `EscortCreep` to the flag on the far side, or kill the opponent's; the escort is towed by pure-MOVE pullers (`pull`), the opening is planned by a spawn simulation. Its rules, design, version history and stub harness are in `docs/escort-run.md`; the harness itself is `tools/stub/escortrun/`.
- **Season 4 API changes** (already in `types`): six `EFF_*_MODIFIER`/`EFF_HITS_LOSS` effect constants; `EffectData.multiplier` is optional, `offset` and `Effect.endTime` added. Season 4's "Pain and Gain" `ScoreFlag` prototype (`arena/season_4/pain_and_gain/basic/prototypes`) and its constants are bound in `types/.../season4/` (`ScoreFlag.kt`, `PainAndGainConstants.kt`). The client's bundled definitions live under `screeps_arena.app/Contents/Resources/app/arena-definitions/` (a folder per arena, `typings/season_N/...`), handy for diffing before the season is documented online.
- Season-specific runtime prototypes need their own binding pointing at the arena module, e.g. `types/.../screeps/api/season3/BonusFlag.kt` is `@file:JsModule("arena/season_3/power_split/basic/prototypes")` (there was only a `season_2` variant before).
- **Client wiring on this machine:** the Arena client keeps per-arena script folders in `~/ScreepsArena/<season>-<mode>/`. `setup-screeps-arenas` only prepares the repo's `arenas/*` folders; to make the client run the Kotlin bot, in the client folder back up its `main.mjs` to `main.mjs.bak`, copy the matching `arenas/<mode>/main.mjs` re-export over it, and symlink `node_modules/screeps-kotlin-arena-starter` → `<your worktree>/build/js/packages/screeps-kotlin-arena-starter` (done for season3-* against the repo root, for season4-spawn_and_swamp against the `spawn-and-swamp` worktree, for season4-pain_and_gain against the `pain-and-gain` worktree and for season4-escort_run against the `escort-run` worktree since 04.09.2026 — see the parallel-sessions rules). After that a plain `./gradlew build` in that worktree updates what the client runs.
- **Offline stub harness**, one per arena under `tools/stub/<arena>/` (Spawn and Swamp: `tools/stub/spawnandswamp/`, described in `docs/spawn-and-swamp.md`; Pain and Gain: `tools/stub/painandgain/`, described in `docs/pain-and-gain.md`; Escort Run: `tools/stub/escortrun/`, described in `docs/escort-run.md`, and the only one that models `pull`): a stub `game` package driven through a Node loader hook, with the engine's movement, fatigue and front-to-back part damage — a stub without fatigue never shows swamp problems, so a new arena's stub starts as a copy of this one. `node` is not on PATH here — the runners use the Gradle-downloaded one under `~/.gradle/nodejs/`.
- **Live-match console logs are on this machine — read them with `tools/match-log.py`, don't ask for a copy-paste.** The client fetches each match's console from `arena.screeps.com/api/game/<id>/log/<tick>` in 100-tick chunks and Chromium keeps those responses gzipped in its disk cache (`~/Library/Application Support/screeps_arena/Cache/Cache_Data`, ~1 GB, months of matches), next to `/api/game/<id>` with the players, the result and the rating change. `tools/match-log.py list [--arena spawn-and-swamp]` prints time, id, ticks, won/lost and rating per match; `tools/match-log.py dump <id-prefix> [--out f.txt]` rebuilds one match's full console in tick order — including the tail the client window scrolled past, which is where a match usually explains itself. Nothing else stores logs locally: the client writes no log files (its Electron main only writes `jsconfig.json` into the script folders), Local Storage holds just `<match>_viewed` flags, and the blobs in `blob_storage` are sprites. It is a cache, so treat it as one: eviction drops old matches, a chunk can be missing from the middle (`dump` marks the gap), and a match watched on another machine was never here.
- When updating type bindings to a new client version, diff the new client `.d.ts` against the committed `types/typings/**` files, then update the corresponding Kotlin under `types/.../screeps/api/`.
