#!/bin/zsh
# Calibrate the stub against a played match: our bot against the recorded opponent (`ghost`) on the match's own terrain,
# then the table "tick in the match / tick in the stub" (calib.py) and the first line where the bot's own log parts from
# its live log (firstdiff.py). Usage:
#   zsh tools/stub/spawnandswampadvanced/calib.sh <game id or prefix> [ticks=5000]
# The bot must be the build that played the match — BOT=<file url of a frozen copy> when this worktree has moved on (the
# greeting's version is checked against the live log's). The replay comes from ./replays/ or ~/ScreepsArena/replays/, the
# live log from runs/**/<...><last six of the id>.txt or `tools/match-log.py dump <id>`.
SELF=${0:A}
cd "${SELF:h}"
NODE=${NODE:-$(ls -d ~/.gradle/nodejs/node-*/bin/node 2>/dev/null | tail -1)}
if [[ ! -x "$NODE" ]]; then echo "calib: node not found under ~/.gradle/nodejs (run ./gradlew build once)"; exit 1; fi
if [[ -z ${STUB_NODE_FLAGS_APPLIED-} ]]; then   # the V8 heap cap of regress.sh
  export NODE_OPTIONS="${STUB_NODE_FLAGS---max-semi-space-size=2 --max-old-space-size=192}${NODE_OPTIONS:+ $NODE_OPTIONS}"
  export STUB_NODE_FLAGS_APPLIED=1
fi
[[ -z "$1" ]] && { echo "usage: calib.sh <game id or prefix> [ticks]"; exit 2; }
ID=$1
TICKS=${2:-5000}
mkdir -p out
REPLAY=$ID LOGTAG="calib-${ID[1,8]}-" "$NODE" --import ./register.mjs run.mjs $TICKS ghost > "out/calib-${ID[1,8]}.txt" 2>&1
grep -E '^(done|ourDev|ghost):' "out/calib-${ID[1,8]}.txt"
python3 calib.py "$ID" "out/calib-${ID[1,8]}.txt" "out/run-calib-${ID[1,8]}-ghost.log"
full=$ID
if (( ${#ID} < 24 )); then
  hits=(replays/${ID}*.replay.json.gz(N) ~/ScreepsArena/replays/${ID}*.replay.json.gz(N))
  [[ -n "${hits[1]}" ]] && full=${${hits[1]:t}%%.*}
fi
logs=(../../../runs/**/*${full[-6,-1]}.txt(N))
live=${logs[1]}
if [[ -z "$live" ]]; then python3 ../../match-log.py dump "$full" > "out/live-${ID[1,8]}.txt" 2>/dev/null && live="out/live-${ID[1,8]}.txt"; fi
if [[ -n "$live" ]]; then
  python3 firstdiff.py "$live" "out/run-calib-${ID[1,8]}-ghost.log" -n 2 --ignore '^enemy objects'
fi
