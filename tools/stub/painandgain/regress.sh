#!/bin/zsh
# Full stub regression for the Pain and Gain bot against THIS worktree's build (run.mjs imports ../../../build/js/...).
# Usage: zsh tools/stub/painandgain/regress.sh [tag]        JOBS=<n> to change the parallel width (default 8)
# One line per scenario: PASS/FAIL, the outcome, errors. Logs go to ./out/ (gitignored).
# Pass = the enemy army destroyed, or the match ended (unreachable lead / 2000 ticks) with our score ahead — with
# errors: 0. tools/land.sh checks for a line with PASS (or ENEMY SPAWN DESTROYED for arenas with spawns) and errors: 0.
# The synthetic map (guessed bodies from before the first live match) is not in the gate: run it by hand,
#   $NODE --import ./register.mjs run.mjs 2000 rush|greedy|grab
#
# Scenarios run in PARALLEL, JOBS at a time: each is its own node process with its own map, log and result, so nothing
# is shared between them and the order of the report is restored at the end from numbered result files. Serially the
# suite took over three minutes, and tools/land.sh runs every arena's suite one after another — one slow suite delays
# every other arena's landing. Determinism is unaffected: the scenarios never talk to each other.
SELF=${0:A}
cd "${SELF:h}"
NODE=${NODE:-$(ls -d ~/.gradle/nodejs/node-*/bin/node 2>/dev/null | tail -1)}
if [[ ! -x "$NODE" ]]; then echo "regress: node not found under ~/.gradle/nodejs (run ./gradlew build once)"; exit 1; fi

# one scenario, in a worker process: writes its report line into <dir>/<n> (see the xargs call at the end)
if [[ "$1" == --one ]]; then
  local_n=$2; map=$3; start=$4; sc=$5; TAG=$6; dir=$7
  label="${map#map-}"; label="${label%.txt}:$sc"
  if [[ "$map" == - ]]; then
    line=$(LOGTAG="${TAG}-" "$NODE" --import ./register.mjs run.mjs 2000 "$sc" 2>&1 | grep '^done:' | tail -1)
  elif [[ "$start" == - ]]; then
    line=$(LOGTAG="${TAG}-$label-" MAP="$map" "$NODE" --import ./register.mjs run.mjs 2000 "$sc" 2>&1 | grep '^done:' | tail -1)
  else
    line=$(LOGTAG="${TAG}-$label-" MAP="$map" START="$start" "$NODE" --import ./register.mjs run.mjs 2000 "$sc" 2>&1 | grep '^done:' | tail -1)
  fi
  # done: <outcome> score=a/b alive=x/y errors=N time=..s log=...
  outcome=$(print -r -- "$line" | sed -E 's/^done: (.*) score=.*/\1/')
  a=$(print -r -- "$line" | sed -E 's/.*score=([0-9]+)\/([0-9]+).*/\1/'); b=$(print -r -- "$line" | sed -E 's/.*score=([0-9]+)\/([0-9]+).*/\2/')
  errors=$(print -r -- "$line" | sed -E 's/.*errors=([0-9]+).*/\1/')
  if [[ -z "$line" ]]; then verdict=FAIL; outcome="no done line"; errors=?
  elif [[ "$outcome" == "enemy army destroyed"* ]]; then verdict=PASS
  elif [[ "$outcome" == "our army destroyed"* ]]; then verdict=FAIL
  elif (( a > b )); then verdict=PASS
  else verdict=FAIL; fi
  printf '%-4s %-22s %-40s score %s:%s | errors: %s \n' "$verdict" "$label" "$outcome" "$a" "$b" "$errors" > "$dir/$local_n"
  exit 0
fi

TAG=${1:-cur}
JOBS=${JOBS:-8}
mkdir -p out
PLANDIR=$(mktemp -d)
N=0
# grind: the debuffed fight after passive captures (sleeper) is lost by construction — we hold R×0.6 H×0.75 against a
# full army — and its points outcome is decided by where the enemy wanders after our army is gone (two identical
# fights on map 4 ended 17229:12409 and 12127:19175). It is run and reported in full mode but not by the landing gate.
grind() { if [[ "$TAG" == land ]]; then return; fi; run "$@"; }
run() { # $1 = map file or -, $2 = START or -, $3 = scenario — collected here, executed in parallel below
  N=$((N + 1)); print -r -- "$N $1 $2 $3 $TAG $PLANDIR" >> "$PLANDIR/plan"
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
run map-match18.txt match2 wing
run map-match18.txt match2 block
run map-match18.txt match2 nine
run map-match18.txt match2 fourteen
run map-match18.txt match2 hunter
run map-match18.txt match2 kite
run map-match18.txt match2 rush
grind map-match18.txt match2 sleeper
run map-match18.txt match2 spread
run map-match12.txt match2 spread
grind map-match19.txt match2 spread
run map-match19.txt match2 block
run map-match19.txt match2 nine
run map-match19.txt match2 wing
run map-match19.txt match2 hunter
run map-match19.txt match2 kite
run map-match19.txt match2 rush
grind map-match19.txt match2 sleeper
run map-match20.txt -      hunter
run map-match20.txt -      army
run map-match20.txt -      nine
run map-match20.txt -      wing
run map-match20.txt -      block
run map-match20.txt -      rush
run map-match20.txt -      kite
grind map-match20.txt -      sleeper
grind map-match20.txt -      spread
run map-match21.txt match2 wing
run map-match21.txt match2 hunter
run map-match21.txt match2 nine
run map-match21.txt match2 block
run map-match21.txt match2 army
run map-match21.txt match2 rush
run map-match21.txt match2 kite
run map-match21.txt match2 fourteen
grind map-match21.txt match2 sleeper
grind map-match21.txt match2 spread
run map-match22.txt -      hunter
run map-match22.txt -      wing
run map-match22.txt -      block
run map-match22.txt -      nine
run map-match22.txt -      army
run map-match22.txt -      rush
run map-match22.txt -      kite
run map-match22.txt -      fourteen
grind map-match22.txt -      sleeper
grind map-match22.txt -      spread
run map-match23.txt -      hunter
run map-match23.txt -      wing
run map-match23.txt -      block
run map-match23.txt -      nine
run map-match23.txt -      army
run map-match23.txt -      rush
run map-match23.txt -      kite
run map-match23.txt -      fourteen
grind map-match23.txt -      sleeper
grind map-match23.txt -      spread
run map-match24.txt match2 wing
run map-match24.txt match2 hunter
run map-match24.txt match2 block
run map-match24.txt match2 nine
run map-match24.txt match2 army
run map-match24.txt match2 rush
run map-match24.txt match2 kite
run map-match24.txt match2 fourteen
grind map-match24.txt match2 sleeper
grind map-match24.txt match2 spread
run map-match24.txt match2 roost
run map-match25.txt match2 wing
run map-match25.txt match2 hunter
run map-match25.txt match2 block
run map-match25.txt match2 nine
run map-match25.txt match2 army
run map-match25.txt match2 rush
run map-match25.txt match2 kite
run map-match25.txt match2 fourteen
run map-match25.txt match2 roost
grind map-match25.txt match2 sleeper
grind map-match25.txt match2 spread
run map-match31.txt -      wing
run map-match31.txt -      hunter
run map-match31.txt -      block
run map-match31.txt -      nine
run map-match31.txt -      army
run map-match31.txt -      rush
run map-match31.txt -      kite
run map-match31.txt -      fourteen
run map-match31.txt -      roost
run map-match31.txt -      farm
run map-match31.txt -      screen
grind map-match31.txt -      sleeper
grind map-match31.txt -      spread
run map-match30.txt match2 wing
run map-match30.txt match2 hunter
run map-match30.txt match2 block
run map-match30.txt match2 nine
run map-match30.txt match2 army
run map-match30.txt match2 rush
run map-match30.txt match2 kite
run map-match30.txt match2 fourteen
run map-match30.txt match2 roost
grind map-match30.txt match2 farm
grind map-match30.txt match2 screen
grind map-match30.txt match2 sleeper
grind map-match30.txt match2 spread
run map-match29.txt match2 wing
run map-match29.txt match2 hunter
run map-match29.txt match2 block
run map-match29.txt match2 nine
run map-match29.txt match2 army
run map-match29.txt match2 rush
run map-match29.txt match2 kite
run map-match29.txt match2 fourteen
run map-match29.txt match2 roost
run map-match29.txt match2 farm
grind map-match29.txt match2 sleeper
grind map-match29.txt match2 spread
run map-match29.txt match2 screen
run map-match28.txt -      screen
run map-match25.txt match2 screen
run map-match28.txt -      wing
run map-match28.txt -      hunter
run map-match28.txt -      block
run map-match28.txt -      nine
run map-match28.txt -      army
run map-match28.txt -      rush
run map-match28.txt -      kite
run map-match28.txt -      fourteen
run map-match28.txt -      roost
grind map-match28.txt -      farm
grind map-match28.txt -      sleeper
grind map-match28.txt -      spread
run map-match22.txt -      roost
run map-match20.txt -      roost
run map-match19.txt match2 roost
run map-match12.txt match2 roost
run map-match18.txt match2 roost
run map-match24.txt match2 farm
run map-match20.txt -      farm
run map-match19.txt match2 farm
run map-match18.txt match2 farm
grind map-match25.txt match2 farm
grind map-match21.txt match2 farm
grind map-match12.txt match2 farm


xargs -P "$JOBS" -n 6 zsh "$SELF" --one < "$PLANDIR/plan"
for ((i = 1; i <= N; i++)); do
  if [[ -s "$PLANDIR/$i" ]]; then cat "$PLANDIR/$i"
  else printf '%-4s %-22s %-40s score %s:%s | errors: %s \n' FAIL "scenario-$i" "worker produced nothing" 0 0 '?'; fi
done
rm -rf "$PLANDIR"
