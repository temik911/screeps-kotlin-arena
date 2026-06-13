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

## Notes

- Existing bot examples by season live under `starter/src/jsMain/kotlin/season1/`, `season2/`, and `tutorial/` — read these for API usage patterns before writing new bots.
- **`season3/spawnstrike/`** is the most developed bot (ranked #1 on its arena) — `InfluenceMap` (influence/damage/danger maps), `DistanceMap` (BFS/flow fields with swamp cost, base zones, entrances), `TrafficManager` (two-phase movement with chains/swaps). Read it for patterns, and **copy** from it per the cross-arena reuse rule above. **`season3/powersplit/`** is an early bot for the Power Split arena (RANGED bonus, WORK economy, central-corridor control).
- Season-specific runtime prototypes need their own binding pointing at the arena module, e.g. `types/.../screeps/api/season3/BonusFlag.kt` is `@file:JsModule("arena/season_3/power_split/basic/prototypes")` (there was only a `season_2` variant before).
- When updating type bindings to a new client version, diff the new client `.d.ts` against the committed `types/typings/**` files, then update the corresponding Kotlin under `types/.../screeps/api/`.
