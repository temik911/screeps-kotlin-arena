#!/bin/zsh
# Full stub regression for the Pain and Gain bot against THIS worktree's build (run.mjs imports ../../../build/js/...).
# Usage: zsh tools/stub/painandgain/regress.sh [tag]
# One line per scenario: PASS/FAIL, the outcome, errors. Logs go to ./out/ (gitignored).
# Pass = the enemy army destroyed, or the match ended (unreachable lead / 2000 ticks) with our score ahead — with
# errors: 0. tools/land.sh checks for a line with PASS (or ENEMY SPAWN DESTROYED for arenas with spawns) and errors: 0.
# The synthetic map (guessed bodies from before the first live match) is not in the gate: run it by hand,
#   $NODE --import ./register.mjs run.mjs 2000 rush|greedy|grab
cd "$(dirname "$0")"
NODE=${NODE:-$(ls -d ~/.gradle/nodejs/node-*/bin/node 2>/dev/null | tail -1)}
if [[ ! -x "$NODE" ]]; then echo "regress: node not found under ~/.gradle/nodejs (run ./gradlew build once)"; exit 1; fi
TAG=${1:-cur}
mkdir -p out
# grind: the debuffed fight after passive captures (sleeper) is lost by construction — we hold R×0.6 H×0.75 against a
# full army — and its points outcome is decided by where the enemy wanders after our army is gone (two identical
# fights on map 4 ended 17229:12409 and 12127:19175). It is run and reported in full mode but not by the landing gate.
grind() { if [[ "$TAG" == land ]]; then return; fi; run "$@"; }
run() { # $1 = map file or -, $2 = START or -, $3 = scenario
  local map=$1 start=$2 sc=$3 label out line
  label="${map#map-}"; label="${label%.txt}:$sc"
  if [[ "$map" == - ]]; then
    line=$(LOGTAG="${TAG}-" "$NODE" --import ./register.mjs run.mjs 2000 "$sc" 2>&1 | grep '^done:' | tail -1)
  elif [[ "$start" == - ]]; then
    line=$(LOGTAG="${TAG}-$label-" MAP="$map" "$NODE" --import ./register.mjs run.mjs 2000 "$sc" 2>&1 | grep '^done:' | tail -1)
  else
    line=$(LOGTAG="${TAG}-$label-" MAP="$map" START="$start" "$NODE" --import ./register.mjs run.mjs 2000 "$sc" 2>&1 | grep '^done:' | tail -1)
  fi
  # done: <outcome> score=a/b alive=x/y errors=N time=..s log=...
  local outcome errors verdict a b
  outcome=$(print -r -- "$line" | sed -E 's/^done: (.*) score=.*/\1/')
  a=$(print -r -- "$line" | sed -E 's/.*score=([0-9]+)\/([0-9]+).*/\1/'); b=$(print -r -- "$line" | sed -E 's/.*score=([0-9]+)\/([0-9]+).*/\2/')
  errors=$(print -r -- "$line" | sed -E 's/.*errors=([0-9]+).*/\1/')
  if [[ -z "$line" ]]; then verdict=FAIL; outcome="no done line"; errors=?
  elif [[ "$outcome" == "enemy army destroyed"* ]]; then verdict=PASS
  elif [[ "$outcome" == "our army destroyed"* ]]; then verdict=FAIL
  elif (( a > b )); then verdict=PASS
  else verdict=FAIL; fi
  printf '%-4s %-22s %-40s score %s:%s | errors: %s \n' "$verdict" "$label" "$outcome" "$a" "$b" "$errors"
}
run map-match1.txt -      grab
run map-match1.txt -      rush
run map-match1.txt -      scouts
run map-match2.txt match2 scouts
run map-match2.txt match2 grab
run map-match2.txt match2 rush
run map-match3.txt match2 kite
run map-match3.txt match2 army
run map-match3.txt match2 rush
grind map-match3.txt match2 sleeper
run map-match3.txt match2 none
run map-match4.txt match2 hunter
run map-match4.txt match2 kite
run map-match4.txt match2 rush
grind map-match4.txt match2 sleeper
run map-match5.txt match2 kite
run map-match5.txt match2 hunter
run map-match5.txt match2 rush
run map-match5.txt match2 army
run map-match6.txt -      hunter
run map-match6.txt -      kite
run map-match6.txt -      rush
grind map-match6.txt -      sleeper
run map-match7.txt match2 hunter
run map-match7.txt match2 kite
run map-match7.txt match2 rush
grind map-match7.txt match2 sleeper
run map-match8.txt match2 hunter
run map-match8.txt match2 kite
run map-match8.txt match2 rush
grind map-match8.txt match2 sleeper
run map-match8.txt match2 army
run map-match8.txt match2 none
run map-match9.txt match2 nine
run map-match9.txt match2 hunter
run map-match9.txt match2 kite
run map-match9.txt match2 rush
grind map-match9.txt match2 sleeper
run map-match10.txt -      nine
run map-match10.txt -      hunter
run map-match10.txt -      kite
run map-match10.txt -      rush
grind map-match10.txt -      sleeper
run map-match11.txt -      nine
run map-match11.txt -      hunter
run map-match11.txt -      kite
run map-match11.txt -      rush
grind map-match11.txt -      sleeper
run map-match12.txt match2 twelve
run map-match12.txt match2 hunter
run map-match12.txt match2 kite
run map-match12.txt match2 rush
grind map-match12.txt match2 sleeper
run map-match13.txt -      nine
run map-match13.txt -      hunter
run map-match13.txt -      kite
run map-match13.txt -      rush
grind map-match13.txt -      sleeper
run map-match13.txt -      fourteen
run map-match14.txt -      fourteen
run map-match14.txt -      nine
run map-match14.txt -      hunter
run map-match14.txt -      kite
run map-match14.txt -      rush
grind map-match14.txt -      sleeper
run map-match9.txt match2 fourteen
run map-match15.txt -      nine
run map-match15.txt -      fourteen
run map-match15.txt -      hunter
run map-match15.txt -      kite
run map-match15.txt -      rush
grind map-match15.txt -      sleeper
run map-match15.txt -      block
run map-match14.txt -      block
run map-match16.txt -      block
run map-match16.txt -      nine
run map-match16.txt -      fourteen
run map-match16.txt -      hunter
run map-match16.txt -      kite
run map-match16.txt -      rush
grind map-match16.txt -      sleeper
run map-match16.txt -      wing
run map-match15.txt -      wing
run map-match17.txt -      wing
run map-match17.txt -      block
run map-match17.txt -      nine
run map-match17.txt -      fourteen
run map-match17.txt -      hunter
run map-match17.txt -      kite
run map-match17.txt -      rush
grind map-match17.txt -      sleeper
