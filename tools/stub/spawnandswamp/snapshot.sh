#!/bin/zsh
# Save the current compiled bot under a name, so selfplay.sh can play a candidate against it.
# Usage: tools/stub/spawnandswamp/snapshot.sh <name>
# The snapshot is a copy of the compiled package; selfplay.mjs copies it into a sibling slot at the
# right depth at run time, so its `../../../kotlin-kotlin-stdlib/...` imports still resolve.
cd "$(dirname "$0")"
[[ -z "$1" ]] && { echo "usage: snapshot.sh <name>"; exit 2; }
SRC=../../../build/js/packages/screeps-kotlin-arena-starter/kotlin/screeps-kotlin-arena-starter/season4/spawnandswamp
[[ -d "$SRC" ]] || { echo "snapshot: no compiled bot at $SRC (run ./gradlew build)"; exit 1; }
DST=opponents/$1
rm -rf "$DST"; mkdir -p "$DST"; cp "$SRC"/*.mjs "$DST"/
ver=$(grep -oE 'BOT_VERSION = [0-9]+' ../../../starter/src/jsMain/kotlin/season4/spawnandswamp/SpawnAndSwamp.kt | head -1)
# the snapshot carries this build's gate table too, so league.sh can show what a candidate changed
zsh ./regress.sh snap > "$DST/regress.txt" 2>&1
# and its NULL self-play vector: at snapshot time the current build IS the snapshot, so this run is
# the null by construction. league.sh diffs a candidate's games against it, which is the only way to
# tell "the candidate tied" from "the candidate never entered the instrument" — see league.sh.
OPP=$1 zsh ./selfplay.sh 8 1200 > /dev/null 2>&1
cat out/selfplay_*.txt | sed -E 's/\x1b\[[0-9;]*m//g' | grep -E '^--- selfplay: ' \
  | sed -E 's/^--- selfplay: //; s/ errors=[0-9]+//' | sort > "$DST/selfplay.txt"
echo "snapshot: opponents/$1 <- $ver ($(ls "$DST" | wc -l | tr -d ' ') files, gate table and null vector saved)"
