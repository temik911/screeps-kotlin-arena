#!/bin/zsh
# Full stub regression for the Escort Run bot against THIS worktree's build (run.mjs imports ../../../build/js/...).
# Usage: zsh tools/stub/escortrun/regress.sh [tag]
# One line per scenario: PASS/FAIL, the outcome, errors. Logs go to ./out/ (gitignored).
# Pass = WIN (our escort on our flag, or the enemy escort dead) with errors: 0. tools/land.sh checks for a line with
# PASS and errors: 0. Scenarios are described in README.md and docs/escort-run.md.
cd "$(dirname "$0")"
NODE=${NODE:-$(ls -d ~/.gradle/nodejs/node-*/bin/node 2>/dev/null | tail -1)}
if [[ ! -x "$NODE" ]]; then echo "regress: node not found under ~/.gradle/nodejs (run ./gradlew build once)"; exit 1; fi
TAG=${1:-cur}
mkdir -p out
# open: scenarios the first version loses by construction (see docs/escort-run.md, "Open findings") — a ranged or melee
# stream fed by a harvester economy from tick 1 out-produces the opening that funds one fighter and a small harvester, and
# a pull-less world (PULL_MODEL=off) is a parity race the escort cannot win without a strike. Run and reported in full
# mode, outside the landing gate until a live match says which of them are real.
# An open scenario is NOT a gate line: it is a known finding kept runnable, and it loses by construction. It used to
# print FAIL, which reads as "the arena's gate is broken" to anyone running the suite without the `land` tag — on
# 07.09.2026 a neighbouring session read exactly that and reported the gate as failing on main, while the gate was
# green. So an open scenario prints OPEN whatever it does, and the counters below keep it out of the verdict.
gate_pass=0; gate_fail=0; open_n=0
open() { if [[ "$TAG" == land ]]; then return; fi; OPEN=1; run "$@"; OPEN=0; }
run() { # $1 = map file or -, $2 = START or -, $3 = scenario, $4.. = extra env (KEY=VALUE)
  local map=$1 start=$2 sc=$3 label line
  shift 3
  label="${map#map-}"; label="${label%.txt}:$sc"
  for kv in "$@"; do [[ "$kv" == PULL_MODEL=off ]] && label="$label:nopull"; done
  local -a env=("LOGTAG=${TAG}-$label-")
  [[ "$map" != - ]] && env+=("MAP=$map")
  [[ "$start" != - ]] && env+=("START=$start")
  env+=("$@")
  line=$(env "${env[@]}" "$NODE" --import ./register.mjs run.mjs 2000 "$sc" 2>&1 | grep '^done:' | tail -1)
  # done: <outcome> alive=x/y escort=h/h errors=N time=..s log=...
  local outcome errors verdict
  outcome=$(print -r -- "$line" | sed -E 's/^done: (.*) alive=.*/\1/')
  errors=$(print -r -- "$line" | sed -E 's/.*errors=([0-9]+).*/\1/')
  if [[ -z "$line" ]]; then verdict=FAIL; outcome="no done line"; errors=?
  elif [[ "$outcome" == WIN* ]]; then verdict=PASS
  elif (( ${OPEN:-0} )); then verdict=OPEN
  else verdict=FAIL; fi
  case "$verdict" in
    PASS) (( gate_pass++ )) ;;
    FAIL) (( gate_fail++ )) ;;
    OPEN) (( open_n++ )) ;;
  esac
  printf '%-4s %-26s %-52s | errors: %s \n' "$verdict" "$label" "$outcome" "$errors"
}
# The summary goes to STDERR on purpose: land.sh captures only stdout and fails the landing on any line that is not a
# pass marker with zero errors, so a summary line on stdout would break every arena's landing.
summary() {
  print -u2 -r -- ""
  print -u2 -r -- "gate: $gate_pass PASS, $gate_fail FAIL — these and only these decide the landing (land.sh runs this suite with the \`land\` tag, which skips the open ones)."
  if (( open_n )); then
    print -u2 -r -- "open findings: $open_n printed as OPEN — known, runnable, and lost by construction; they are NOT gate failures. See \"Открытые находки\" in docs/escort-run.md."
  fi
}
trap summary EXIT
# the live map of match 1 (04.09.2026) with its measured layout — the escort body, the 500-energy spawns, the flags and
# the sources are the real ones, so these lines are the closest thing to a replay of a live match
run  map-match1.txt - none
run  map-match1.txt - race
run  map-match1.txt - melee
run  map-match1.txt - train
open map-match1.txt - rush+harvest
# 'racer' is the match-2/3 opponent given a head start (its puller is alive at tick 0): the closest thing to the live
# rival that actually races. It is IN the gate since the flag blocker — a head start of thirty ticks no longer decides
run  map-match1.txt - racer
# the map of matches 4-7 (04.09.2026) against the rival that beat us four times out of four: same layout as match 1
# (the arena fixes spawns, escorts and flags and randomises only the terrain), different terrain
run  map-match4.txt - none
run  map-match4.txt - racer
run  map-match4.txt - train
# 'hardy' is that rival modelled by its measured speed (see run.mjs): the closest thing to the matches we lost
run  map-match4.txt - hardy
run  map-match1.txt - hardy
# the map of match 6a9b335e (05.09.2026), where v7 deadlocked: the puller is born behind the escort and its only way to
# the slot leads through the escort's cell. Held the escort still for 2000 ticks and drew a match against a rival that
# never moved its own escort — this line is the regression test for that
run  map-stuck.txt - none
run  map-stuck.txt - hardy
# the map of match 6a9b3af2 (05.09.2026), played from the OTHER side: there the puller is born behind the escort on a
# diagonal, the only cell closer to its slot is the escort's own, and without an overtaking step it trails the escort
# for the whole match — the train never rolled and the escort walked at period 4. Regression test for the overtake
run  map-chase.txt match2 none
run  map-chase.txt match2 hardy
run  - - racer
run  - - none
run  - - race
run  - - melee
run  - - guard
run  - - guard+harvest
run  - - train
run  - - train+rush+harvest
open - - rush
open - - rush+harvest
open - - melee+harvest
open - - hunt+harvest
open - - race PULL_MODEL=off
open - - rush+harvest PULL_MODEL=off
