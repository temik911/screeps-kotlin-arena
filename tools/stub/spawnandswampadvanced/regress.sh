#!/bin/zsh
# Stub regression for the Spawn and Swamp ADVANCED bot against THIS worktree's build (run.mjs imports ../../../build/js/...).
# A copy of tools/stub/escortrunadvanced/regress.sh (itself from tools/stub/escortrun/), fitted to this arena — copied, not
# shared, by the repository's rule. Usage: zsh tools/stub/spawnandswampadvanced/regress.sh [tag]
#   tags `land` (tools/land.sh) and `gate` run the gate lines only; any other tag runs the `open` lines too; under
#   tools/land.sh (STUB_LANDING_BASE set) a landing that cannot change this bot runs the SMOKE lines only.
#   BOT=<file url of a frozen copy of a build> runs the suite against that copy (run.mjs reads it).
# One line per scenario: PASS/FAIL/OPEN, the label <record>-<side>:<scenario>, the outcome, `| errors: N `. The bot's console
# goes to ./out/run-<tag>-<label>-<scenario>.log, the runner's own output to ./out/stdout-<tag>-<label>.txt (both gitignored).
# What PASS is, line by line, is in README.md ("The gate"). tools/land.sh checks every stdout line for PASS and errors: 0 —
# the summary goes to stderr for that reason.
SELF=${0:A}
cd "${SELF:h}"
NODE=${NODE:-$(ls -d ~/.gradle/nodejs/node-*/bin/node 2>/dev/null | tail -1)}
if [[ ! -x "$NODE" ]]; then echo "regress: node not found under ~/.gradle/nodejs (run ./gradlew build once)"; exit 1; fi
# V8 heap settings for every node process started from here (20.09.2026): a stub scenario holds a few tens of MB of live
# data, and left to V8's 4 GB default limit its process grows far past it. A 2 MB semi-space and an old-space limit of 192
# MB keep it small at about 14 % more run time; the logs do not change. The limit is hard — a bot whose live heap outgrows
# it dies with "heap out of memory", and its line says so. STUB_NODE_FLAGS= (empty) switches the settings off,
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
# deterministic: the SMOKE line runs — it still catches what a foreign landing can break (the bundle does not load, the
# runtime API it calls throws). Its own landing (package, stub, arena folder) and everything that goes into every bundle
# (types/, the build, shared starter code) run the full gate; so does a run without the variable.
SMOKE=(
  6abc0dc7-right:none   # load, greeting, the opening, the spawn, the first fighter and the kill of an idle worker (≈ 0.3 s)
)
landing_reaches_me() {   # 0 — the landing may change what this stub runs; when in doubt, yes
  local files f
  files=$(git diff --name-only "$STUB_LANDING_BASE"...HEAD 2>/dev/null) || return 0
  for f in ${(f)files}; do
    case "$f" in
      starter/src/jsMain/kotlin/season4/spawnandswampadvanced/*|tools/stub/spawnandswampadvanced/*|arenas/season4-spawn_and_swamp-advanced/*) return 0 ;;
      starter/src/jsMain/kotlin/season*/*/*|starter/src/jsTest/*|tools/*|arenas/*|docs/*|*.md) ;;
      *) return 0 ;;
    esac
  done
  return 1
}
SMOKE_ONLY=0
if [[ "$TAG" == land && -n "${STUB_LANDING_BASE:-}" ]]; then
  if landing_reaches_me; then print -u2 -r -- "spawnandswampadvanced: full gate — the landing touches this bot's build or its stub"
  else SMOKE_ONLY=1; print -u2 -r -- "spawnandswampadvanced: smoke, ${#SMOKE} line — the landing does not touch this bot (full gate: zsh regress.sh gate)"; fi
fi
typeset -A SMOKE_SET SMOKE_FOUND
for s in $SMOKE; do SMOKE_SET[$s]=1; done

# An open scenario is NOT a gate line: a measurement kept runnable (a persona of the field, bot against bot). It prints OPEN
# whatever it does, and the counters below keep it out of the verdict.
gate_pass=0; gate_fail=0; open_n=0
open() { if [[ "$TAG" == land || "$TAG" == gate ]]; then return; fi; OPEN=1; run "$@"; OPEN=0; }
run() { # $1 = record (id prefix: its terrain, and its opponent for ghost), $2 = side (left|right|rec), $3 = scenario, $4.. = env
  local id=$1 side=$2 sc=$3 label line
  shift 3
  label="$id-$side:$sc"
  (( ${+SMOKE_SET[$label]} )) && SMOKE_FOUND[$label]=1
  if (( SMOKE_ONLY )) && ! (( ${+SMOKE_SET[$label]} )); then return; fi
  local -a env=("LOGTAG=${TAG}-${id}-${side}-" "REPLAY=$id")
  [[ "$side" != rec ]] && env+=("START=$side")
  env+=("$@")
  local raw; raw=$(env "${env[@]}" "$NODE" --import ./register.mjs run.mjs $TICKS "${sc%%+*}" 2>&1)
  print -r -- "$raw" > "out/stdout-${TAG}-${label//:/-}.txt"   # the runner's own lines: cpu, status every 100 ticks, done
  line=$(print -r -- "$raw" | grep '^done:' | tail -1)
  local outcome errors verdict
  outcome=$(print -r -- "$line" | sed -E 's/^done: (.*) alive=.*/\1/')
  errors=$(print -r -- "$line" | sed -E 's/.*errors=([0-9]+).*/\1/')
  if [[ -z "$line" ]]; then verdict=FAIL; outcome="no done line (stub crash)"; errors=1
    [[ "$raw" == *"heap out of memory"* ]] && outcome="node heap limit hit (STUB_NODE_FLAGS)"   # the limit above is a hard one: name it
  elif (( ${OPEN:-0} )); then verdict=OPEN
  elif [[ "$errors" != 0 ]]; then verdict=FAIL
  elif [[ "$sc" == ghost ]]; then
    # a recorded match: not worse than it went live — a record we won must be won; a record we lost may be lost, but not
    # more than 100 ticks earlier than live (the bot collapsing sooner against the same opponent is a regression)
    local rec lostAt at
    rec=$(print -r -- "$raw" | grep '^record ' | head -1)
    if [[ "$rec" == *"temik911 won"* ]]; then [[ "$outcome" == WIN* ]] && verdict=PASS || verdict=FAIL
    elif [[ "$rec" == *" draw "* ]]; then [[ "$outcome" == WIN* || "$outcome" == DRAW* ]] && verdict=PASS || verdict=FAIL
    else
      lostAt=$(print -r -- "$rec" | sed -E 's/.* won in ([0-9]+) ticks.*/\1/')
      at=$(print -r -- "$outcome" | sed -nE 's/^LOSS:.* at t=([0-9]+).*/\1/p')
      if [[ -z "$at" || $at -ge $(( lostAt - 100 )) ]]; then verdict=PASS; outcome="$outcome (live: lost at $lostAt)"; else verdict=FAIL; outcome="$outcome (live: lost at $lostAt)"; fi
    fi
  elif [[ "$outcome" == WIN* ]]; then verdict=PASS
  else verdict=FAIL; fi
  case "$verdict" in
    PASS) (( gate_pass++ )) ;;
    FAIL) (( gate_fail++ )) ;;
    OPEN) (( open_n++ )) ;;
  esac
  printf '%-4s %-26s %-62s | errors: %s \n' "$verdict" "$label" "$outcome" "$errors"
}
summary() {
  print -u2 -r -- ""
  print -u2 -r -- "gate: $gate_pass PASS, $gate_fail FAIL — these and only these decide the landing (land.sh runs this suite with the \`land\` tag, which skips the open ones)."
  if (( open_n )); then
    print -u2 -r -- "open lines: $open_n printed as OPEN — measurements against the field (personas, bot against bot), NOT gate failures. See tools/stub/spawnandswampadvanced/README.md."
  fi
}
trap summary EXIT

# the gate, part 1: an idle opponent (the System bot: it never moves or builds) from both starts on two live terrains —
# the opening, the spawn, the first fighter, the kill of his worker (v58: 538-568)
run  6abc0dc7 right none
run  6abc0dc7 left  none
run  6abc21a1 right none
run  6abc21a1 left  none
# the gate, part 2: four recorded matches of v58 (29.09.2026) against the recorded opponent on its own terrain (`ghost`,
# README "The recorded opponent"): けろびー#20 (won live at 3601), stachu3478#12 (won live at 2334), けろびー#23 (lost live
# at 2533), ricardo18informatica2020#1 (lost live at 952)
run  6abc0dc7 rec   ghost
run  6abc107c rec   ghost
run  6abc21a1 rec   ghost
run  6abbba40 rec   ghost
# open: the field's personas (run.mjs, "the personas": what is measured, what is invented) from both starts, and the bot
# against itself (BOT2=self, ~95 s)
open 6abc0dc7 left  kerobii
open 6abc0dc7 right kerobii
open 6abc0dc7 left  kerobii22
open 6abc0dc7 right kerobii22
open 6abc0dc7 left  ricardo
open 6abc0dc7 right ricardo
open 6abc0dc7 right none+bot2 BOT2=self

# a SMOKE label without a gate line would shrink the smoke silently: checked on every gate run, so its own landing catches it
if [[ "$TAG" == land || "$TAG" == gate ]] && (( ! SMOKE_ONLY )); then
  for s in $SMOKE; do
    (( ${+SMOKE_FOUND[$s]} )) || { printf '%-4s %-26s %-62s | errors: %s \n' FAIL smoke "no gate line $s" 1; (( gate_fail++ )); }
  done
fi
