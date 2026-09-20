#!/bin/zsh
# Play this worktree's build against a recorded opponent on the map he was recorded on.
# Usage: tools/stub/spawnandswamp/replay.sh <scenario.json> [ticks]
# Make a scenario with: tools/replay.py scenario <replay.json.gz> --out scenarios/<name>.json
# Committed ones live in tools/stub/spawnandswamp/scenarios/. What is faithful and what is not:
# see the header of replayrun.mjs.
cd "$(dirname "$0")"
NODE=${NODE:-$(ls -d ~/.gradle/nodejs/node-*/bin/node 2>/dev/null | tail -1)}
if [[ ! -x "$NODE" ]]; then echo "replay: node not found under ~/.gradle/nodejs (run ./gradlew build once)"; exit 1; fi
# V8 heap settings for every node process started from here (20.09.2026): a stub scenario holds 13 MB of live data, and
# left to V8's 4 GB default limit its process grows to 290 MB. A 2 MB semi-space and an old-space limit of 192 MB keep it
# near 170 at about 14 % more run time; the logs do not change. The limit is hard — a bot whose live heap outgrows it dies with
# "heap out of memory". STUB_NODE_FLAGS= (empty) switches the settings off, STUB_NODE_FLAGS="--max-old-space-size=512" widens
# them. The measurement and the choice of 192 are in tools/stub/painandgain/regress.sh.
if [[ -z ${STUB_NODE_FLAGS_APPLIED-} ]]; then   # once: these scripts call one another and the value is inherited
  export NODE_OPTIONS="${STUB_NODE_FLAGS---max-semi-space-size=2 --max-old-space-size=192}${NODE_OPTIONS:+ $NODE_OPTIONS}"
  export STUB_NODE_FLAGS_APPLIED=1
fi
if [[ -z "$1" ]]; then echo "usage: replay.sh <scenario.json> [ticks]"; exit 2; fi
SCEN=$1; [[ -f "$SCEN" ]] || SCEN="scenarios/$1"
[[ -f "$SCEN" ]] || { echo "replay: no scenario at $1"; exit 2; }
mkdir -p out
f=out/replay_$(basename "$SCEN" .json).txt
"$NODE" --import ./register.mjs replayrun.mjs "$SCEN" "$2" > "$f" 2>&1
res=$(grep -E 'SPAWN DESTROYED' "$f" | sed -E 's/\x1b\[[0-9;]*m//g' | head -1)
printf '%-28s %-32s | %s\n' "$(basename "$SCEN" .json)" "${res:-survived (no spawn destroyed)}" \
  "$(grep -E '^--- ticks run' "$f" | sed -E 's/\x1b\[[0-9;]*m//g')"
grep -E '^--- scenario' "$f" | sed -E 's/\x1b\[[0-9;]*m//g'
echo "log: tools/stub/spawnandswamp/$f"
