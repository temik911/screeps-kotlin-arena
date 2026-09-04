#!/bin/zsh
# Full stub regression for the Spawn and Swamp bot against THIS worktree's build (the runners import
# ../../../build/js/...). Usage: zsh tools/stub/spawnandswamp/regress.sh [tag]
# Prints one line per scenario: outcome tick, errors, ghost-damage lines. Logs go to ./out/ (gitignored).
# Pass = every line says "ENEMY SPAWN DESTROYED" with "errors: 0"; tools/land.sh checks exactly that.
cd "$(dirname "$0")"
NODE=${NODE:-$(ls -d ~/.gradle/nodejs/node-*/bin/node 2>/dev/null | tail -1)}
if [[ ! -x "$NODE" ]]; then echo "regress: node not found under ~/.gradle/nodejs (run ./gradlew build once)"; exit 1; fi
TAG=${1:-cur}
mkdir -p out
report() { # $1 = label, $2 = log file
  local res tr ghost
  res=$(grep -E 'SPAWN DESTROYED' "$2" | sed -E 's/\x1b\[[0-9;]*m//g' | head -1)
  tr=$(grep -E '^--- ticks run' "$2" | sed -E 's/\x1b\[[0-9;]*m//g' | sed -E 's/ my creeps:.*//')
  ghost=$(grep -c 'ghost' "$2")
  printf '%-18s %-32s | %s | ghost=%s\n' "$1" "${res:-survived (no spawn destroyed)}" "${tr:-(no summary line)}" "$ghost"
}
for m in none enemy swarm raider harass tower tower+enemy ball tower+healball towersite healball tower+hover rush camp stream tower+stream; do
  f=out/${TAG}_r2_${m//+/_}.txt
  "$NODE" --import ./register.mjs run2.mjs 2000 "$m" > "$f" 2>&1
  report "run2:$m" "$f"
done
for s in freeze rush stream17; do
  f=out/${TAG}_r3_${s}.txt
  "$NODE" --import ./register.mjs run3.mjs 2000 "$s" > "$f" 2>&1
  report "run3:$s" "$f"
done
