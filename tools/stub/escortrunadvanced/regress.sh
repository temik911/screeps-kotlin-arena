#!/bin/zsh
# Stub regression for the Escort Run ADVANCED bot against THIS worktree's build (run.mjs imports ../../../build/js/...).
# A copy of tools/stub/escortrun/regress.sh adapted to the advanced level (copied, not shared, by the repository's rule).
# Usage: zsh tools/stub/escortrunadvanced/regress.sh [tag]
#        tags `land` (tools/land.sh) and `gate` run the gate lines only; any other tag runs the `open` lines too; under
#        tools/land.sh (STUB_LANDING_BASE set) a landing that cannot change this bot runs the SMOKE lines only.
#        BOT=<file url of a frozen copy of a build> runs the suite against that copy (run.mjs reads it).
# One line per scenario: PASS/FAIL/OPEN, the label <map>-<side>:<scenario>, the outcome, `| errors: N `. The bot's console
# goes to ./out/run-<tag>-<map>-<side>-<scenario>.log, the runner's own output to ./out/stdout-<tag>-<map>-<side>-<scenario>.txt
# (both gitignored). PASS = WIN (one enemy escort dead, or all three of ours on our flags) with errors: 0. tools/land.sh checks
# every stdout line for PASS and errors: 0 — the summary goes to stderr for that reason.
SELF=${0:A}
cd "${SELF:h}"
NODE=${NODE:-$(ls -d ~/.gradle/nodejs/node-*/bin/node 2>/dev/null | tail -1)}
if [[ ! -x "$NODE" ]]; then echo "regress: node not found under ~/.gradle/nodejs (run ./gradlew build once)"; exit 1; fi
# V8 heap settings for every node process started from here (20.09.2026): a stub scenario holds 12 MB of live data, and
# left to V8's 4 GB default limit its process grows far past it. A 2 MB semi-space and an old-space limit of 192 MB keep
# it small at about 14 % more run time; the logs do not change. The limit is hard — a bot whose live heap outgrows it dies
# with "heap out of memory", and its line says so. STUB_NODE_FLAGS= (empty) switches the settings off,
# STUB_NODE_FLAGS="--max-old-space-size=512" widens them. The measurement and the choice of 192 are in
# tools/stub/painandgain/regress.sh.
if [[ -z ${STUB_NODE_FLAGS_APPLIED-} ]]; then   # once: these scripts call one another and the value is inherited
  export NODE_OPTIONS="${STUB_NODE_FLAGS---max-semi-space-size=2 --max-old-space-size=192}${NODE_OPTIONS:+ $NODE_OPTIONS}"
  export STUB_NODE_FLAGS_APPLIED=1
fi
TAG=${1:-cur}
TICKS=5000
mkdir -p out

# A LANDING THAT CANNOT CHANGE THIS BOT RUNS THE SMOKE, NOT THE GATE (the rule of tools/stub/painandgain/regress.sh,
# 27.09.2026: every landing of every session runs every arena's suite). tools/land.sh sets STUB_LANDING_BASE — main at the
# landing. If the landing touches nothing this bot is built from or run by (only other arenas' packages, stubs and
# folders, docs, top-level tools/), the bundle is the one that passed the full gate at its own landing and the stub is
# deterministic: the SMOKE lines run — they still catch what a foreign landing can break (the bundle does not load, the
# runtime API it calls throws). Its own landing (package, stub, arena folder) and everything that goes into every bundle
# (types/, the build, shared starter code) run the full gate; so does a run without the variable.
SMOKE=(
  6ab9267a-bottom:none   # the bottom player: load, greeting, economy, the outpost, siege and the kill (≈ 1 s)
  6ab9267a-top:none      # the top player: the mirrored cells and ids
)
landing_reaches_me() {   # 0 — the landing may change what this stub runs; when in doubt, yes
  local files f
  files=$(git diff --name-only "$STUB_LANDING_BASE"...HEAD 2>/dev/null) || return 0
  for f in ${(f)files}; do
    case "$f" in
      starter/src/jsMain/kotlin/season4/escortrunadvanced/*|tools/stub/escortrunadvanced/*|arenas/season4-escort_run-advanced/*) return 0 ;;
      starter/src/jsMain/kotlin/season*/*/*|starter/src/jsTest/*|tools/*|arenas/*|docs/*|*.md) ;;
      *) return 0 ;;
    esac
  done
  return 1
}
SMOKE_ONLY=0
if [[ "$TAG" == land && -n "${STUB_LANDING_BASE:-}" ]]; then
  if landing_reaches_me; then print -u2 -r -- "escortrunadvanced: full gate — the landing touches this bot's build or its stub"
  else SMOKE_ONLY=1; print -u2 -r -- "escortrunadvanced: smoke, ${#SMOKE} lines — the landing does not touch this bot (full gate: zsh regress.sh gate)"; fi
fi
typeset -A SMOKE_SET SMOKE_FOUND
for s in $SMOKE; do SMOKE_SET[$s]=1; done

# An open scenario is NOT a gate line: it is a known finding kept runnable, one the current bot loses. It prints OPEN
# whatever it does (the basic stub's lesson of 07.09.2026: a FAIL there was read as "the arena's gate is broken"), and the
# counters below keep it out of the verdict.
gate_pass=0; gate_fail=0; open_n=0
open() { if [[ "$TAG" == land || "$TAG" == gate ]]; then return; fi; OPEN=1; run "$@"; OPEN=0; }
run() { # $1 = map file, $2 = side (bottom|top), $3 = scenario, $4.. = extra env (KEY=VALUE)
  local map=$1 side=$2 sc=$3 label line
  shift 3
  label="${map#map-}"; label="${label%.txt}-$side:$sc"
  for kv in "$@"; do [[ "$kv" == PULL_MODEL=off ]] && label="$label:nopull"; done
  (( ${+SMOKE_SET[$label]} )) && SMOKE_FOUND[$label]=1
  if (( SMOKE_ONLY )) && ! (( ${+SMOKE_SET[$label]} )); then return; fi
  local -a env=("LOGTAG=${TAG}-${label%%:*}-" "MAP=$map" "START=$side")
  env+=("$@")
  local raw; raw=$(env "${env[@]}" "$NODE" --import ./register.mjs run.mjs $TICKS "$sc" 2>&1)
  print -r -- "$raw" > "out/stdout-${TAG}-${label//:/-}.txt"   # the runner's own lines: cpu, status every 100 ticks, done
  line=$(print -r -- "$raw" | grep '^done:' | tail -1)
  # done: <outcome> alive=x/y escorts=a+b+c/d+e+f errors=N time=..s log=...
  local outcome errors verdict
  outcome=$(print -r -- "$line" | sed -E 's/^done: (.*) alive=.*/\1/')
  errors=$(print -r -- "$line" | sed -E 's/.*errors=([0-9]+).*/\1/')
  if [[ -z "$line" ]]; then verdict=FAIL; outcome="no done line (stub crash)"; errors=1
    [[ "$raw" == *"heap out of memory"* ]] && outcome="node heap limit hit (STUB_NODE_FLAGS)"   # the limit above is a hard one: name it
  elif (( ${OPEN:-0} )); then verdict=OPEN
  elif [[ "$outcome" == WIN* && "$errors" == 0 ]]; then verdict=PASS
  else verdict=FAIL; fi
  case "$verdict" in
    PASS) (( gate_pass++ )) ;;
    FAIL) (( gate_fail++ )) ;;
    OPEN) (( open_n++ )) ;;
  esac
  printf '%-4s %-30s %-52s | errors: %s \n' "$verdict" "$label" "$outcome" "$errors"
}
summary() {
  print -u2 -r -- ""
  print -u2 -r -- "gate: $gate_pass PASS, $gate_fail FAIL — these and only these decide the landing (land.sh runs this suite with the \`land\` tag, which skips the open ones)."
  if (( open_n )); then
    print -u2 -r -- "open findings: $open_n printed as OPEN — known, runnable, and lost by the current bot; they are NOT gate failures. See the open lines' comment below and tools/stub/escortrunadvanced/README.md."
  fi
}
trap summary EXIT

# the gate: the terrain of 6ab9267a (v3 against けろびー#11, 27.09.2026 — we were the bottom player) from both sides, against
# the scripted opponents of the field (run.mjs says who each one is)
run  map-6ab9267a.txt bottom none
run  map-6ab9267a.txt top    none
run  map-6ab9267a.txt bottom race
run  map-6ab9267a.txt top    race
run  map-6ab9267a.txt bottom hunt
run  map-6ab9267a.txt top    hunt
run  map-6ab9267a.txt bottom blob
run  map-6ab9267a.txt top    blob
run  map-6ab9267a.txt bottom siege
run  map-6ab9267a.txt top    siege
# the same with the enemy's income at +11 a tick (a W5 economy from tick 1, like stachu3478's and けろびー's)
run  map-6ab9267a.txt bottom hunt+harvest
run  map-6ab9267a.txt top    hunt+harvest
run  map-6ab9267a.txt bottom siege+harvest
run  map-6ab9267a.txt top    siege+harvest
# the other stored terrains, each from the side we played it on (6ab92799 stachu3478 top, 6ab9255d Hardy bottom,
# 6ab92172 76561198870429455#3 top)
run  map-6ab92799.txt top    none
run  map-6ab92799.txt top    race
run  map-6ab92799.txt top    siege+harvest
run  map-6ab9255d.txt bottom none
run  map-6ab9255d.txt bottom race
run  map-6ab92172.txt top    none
run  map-6ab92172.txt top    hunt
# the centre held by a growing blob and the enemy's income at +11: v9 drew these at 5000 (the home army's mode flipped
# SIEGE/HOLD every tick and never left home); v10 stopped the flicker and v11's convoy delivers round the blob (~885)
run  map-6ab9267a.txt bottom blob+harvest
run  map-6ab9267a.txt top    blob+harvest

# a SMOKE label without a gate line would shrink the smoke silently: checked on every gate run, so its own landing catches it
if [[ "$TAG" == land || "$TAG" == gate ]] && (( ! SMOKE_ONLY )); then
  for s in $SMOKE; do
    (( ${+SMOKE_FOUND[$s]} )) || { printf '%-4s %-30s %-52s | errors: %s \n' FAIL smoke "no gate line $s" 1; (( gate_fail++ )); }
  done
fi
