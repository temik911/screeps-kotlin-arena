# Offline stub harness — Spawn and Swamp

Runs the compiled bot (`../../../build/js/.../season4/spawnandswamp/SpawnAndSwamp.export.mjs`, i.e. the build of the
worktree this directory lives in) under Node against a stub of the Arena runtime in `game/`: constants, prototypes,
Dijkstra `searchPath`, simultaneous movement with swaps and chains, fatigue by part type (dead parts weigh), and
front-to-back part damage — the parts of the engine that decide whether a bot freezes in a swamp or feeds itself
to a tower. What it is, what each scenario replays and why it exists is in `docs/spawn-and-swamp.md`.

```shell
NODE=$(ls -d ~/.gradle/nodejs/node-*/bin/node | tail -1)          # node is not on PATH here
$NODE --import ./register.mjs run2.mjs 2000 tower+stream           # random map; modes combine with '+'
$NODE --import ./register.mjs run3.mjs 2000 stream17               # live map from a match log (map5.txt)
zsh regress.sh v20                                                 # every scenario, one line each, logs in out/
```

`tools/land.sh` runs `regress.sh` as the landing gate: every scenario must end with the enemy spawn destroyed and
zero errors. A new arena's harness starts as a copy of this directory (`game/` is arena-agnostic; the runners
build the world and the opponent AI, which is where arena rules live).
