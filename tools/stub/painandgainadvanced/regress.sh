#!/bin/zsh
# Stub regression for the Pain and Gain ADVANCED bot against THIS worktree's build (run.mjs imports ../../../build/js/...).
# Usage: zsh tools/stub/painandgainadvanced/regress.sh [tag]      JOBS=<n> to run lines in parallel (default 1)
#        tag `gate` (and `land`, used by tools/land.sh) runs the `run` lines only; any other tag runs the `grind` lines too;
#        under tools/land.sh (STUB_LANDING_BASE set) a landing that cannot change this bot runs the SMOKE lines only.
# One line per scenario: PASS/FAIL, the label <map>-<side>:<scenario>, the outcome, the final score, the alive counts, the
# tick it ended and the tower shots (ours:theirs), then `| errors: N `. Logs go to ./out/ (gitignored).
# PASS = we won — the enemy army destroyed, or our score ahead when the match ended (unreachable lead, the 5000-tick limit,
# or the line's tick cap) — AND errors: 0 (a tick whose loop() printed a stack trace, or the stub itself crashing: then
# there is no done line and the line FAILs). tools/land.sh checks every line for PASS and errors: 0.
# Wall time, measured 27.09.2026 on v3 (one line at a time): none 0.4 s, rush 1.6 s, farm 1 s — the full gate ≈ 8 s serial,
# so every line plays the whole 5000 ticks and JOBS defaults to 1 (memory is what runs out on this machine, not time).
SELF=${0:A}
cd "${SELF:h}"
NODE=${NODE:-$(ls -d ~/.gradle/nodejs/node-*/bin/node 2>/dev/null | tail -1)}
if [[ ! -x "$NODE" ]]; then echo "regress: node not found under ~/.gradle/nodejs (run ./gradlew build once)"; exit 1; fi
# V8 heap settings for every node process started from here — the block of tools/stub/painandgain/regress.sh (20.09.2026),
# where the measurement is: a scenario's live set is tens of MB, but under V8's 4 GB default limit a process grows to
# 260-360 MB before it collects; old-space 192 is flat in memory below it, semi-space 2 trades 14 % of time for 31 % of
# memory. The limit is HARD: a bot whose live heap outgrows it dies with "heap out of memory", and its line says so below.
# STUB_NODE_FLAGS= (empty) switches the settings off, STUB_NODE_FLAGS="--max-old-space-size=512" widens them; a NODE_OPTIONS
# of the caller's is kept and comes last, so it wins. Set once: the workers below re-run this script and inherit it.
if [[ -z ${STUB_NODE_FLAGS_APPLIED-} ]]; then
  export NODE_OPTIONS="${STUB_NODE_FLAGS---max-semi-space-size=2 --max-old-space-size=192}${NODE_OPTIONS:+ $NODE_OPTIONS}"
  export STUB_NODE_FLAGS_APPLIED=1
fi

# one scenario, in a worker process: writes its report line into <dir>/<n> (see the xargs call at the end)
if [[ "$1" == --one ]]; then
  local_n=$2; map=$3; start=$4; sc=$5; ticks=$6; TAG=$7; dir=$8
  label="${map#map-}"; label="${label%.txt}-$start:$sc"
  raw=$(LOGTAG="${TAG}-${label%%:*}-" MAP="$map" START="$start" "$NODE" --import ./register.mjs run.mjs "$ticks" "$sc" 2>&1)
  line=$(print -r -- "$raw" | grep '^done:' | tail -1)
  # done: <outcome> score=a/b alive=x/y errors=N end=T tw=s/s time=..s log=...
  outcome=$(print -r -- "$line" | sed -E 's/^done: (.*) score=.*/\1/')
  a=$(print -r -- "$line" | sed -E 's/.*score=([0-9]+)\/([0-9]+).*/\1/'); b=$(print -r -- "$line" | sed -E 's/.*score=([0-9]+)\/([0-9]+).*/\2/')
  alive=$(print -r -- "$line" | sed -E 's/.*alive=([0-9]+)\/([0-9]+).*/\1:\2/')
  end=$(print -r -- "$line" | sed -E 's/.* end=([0-9]+).*/\1/')
  tw=$(print -r -- "$line" | sed -E 's/.* tw=([0-9]+)\/([0-9]+).*/\1:\2/')
  errors=$(print -r -- "$line" | sed -E 's/.*errors=([0-9]+).*/\1/')
  if [[ -z "$line" ]]; then verdict=FAIL; outcome="no done line (stub crash)"; errors=1; a=0; b=0; alive=?; end=?; tw=?
    # the heap limit set at the top is a hard one: name it, so the line is not read as a crash of the bot
    [[ "$raw" == *"heap out of memory"* ]] && outcome="node heap limit hit (STUB_NODE_FLAGS)"
  elif (( errors > 0 )); then verdict=FAIL
  elif [[ "$outcome" == "enemy army destroyed"* ]]; then verdict=PASS
  elif [[ "$outcome" == "our army destroyed"* ]]; then verdict=FAIL
  elif (( a > b )); then verdict=PASS
  else verdict=FAIL; fi
  printf '%-4s %-16s %-32s score %s:%s alive %s t=%s tw %s | errors: %s \n' "$verdict" "$label" "$outcome" "$a" "$b" "$alive" "$end" "$tw" "$errors" > "$dir/$local_n"
  exit 0
fi

TAG=${1:-cur}
JOBS=${JOBS:-1}
# A LANDING THAT CANNOT CHANGE THIS BOT RUNS THE SMOKE, NOT THE GATE (the rule of tools/stub/painandgain/regress.sh, 27.09.2026:
# every landing of every session runs every arena's suite). tools/land.sh sets STUB_LANDING_BASE — main at the landing. If the
# landing touches nothing this bot is built from or run by (only other arenas' packages, stubs and folders, docs, top-level
# tools/), the bundle is the one that passed the full gate at its own landing and the stub is deterministic: the SMOKE lines
# run, each cut to SMOKE_TICKS — they still catch what a foreign landing can break (the bundle does not load, the runtime
# API it calls throws). Its own landing (package, stub, arena folder) and everything that goes into every bundle (types/,
# the build, shared starter code) run the full gate; so does a run without the variable.
SMOKE=(
  live1-p1:none    # player 1: load, greeting, pullers to the tower flags, the march, a win by score
  live2-p2:none    # player 2: the mirrored ids and cells
)
SMOKE_TICKS=300
landing_reaches_me() {   # 0 — the landing may change what this stub runs; when in doubt, yes
  local files f
  files=$(git diff --name-only "$STUB_LANDING_BASE"...HEAD 2>/dev/null) || return 0
  for f in ${(f)files}; do
    case "$f" in
      starter/src/jsMain/kotlin/season4/painandgainadvanced/*|tools/stub/painandgainadvanced/*|arenas/season4-pain_and_gain-advanced/*) return 0 ;;
      starter/src/jsMain/kotlin/season*/*/*|starter/src/jsTest/*|tools/*|arenas/*|docs/*|*.md) ;;
      *) return 0 ;;
    esac
  done
  return 1
}
SMOKE_ONLY=0
if [[ "$TAG" == land && -n "${STUB_LANDING_BASE:-}" ]]; then
  if landing_reaches_me; then print -r -- "painandgainadvanced: full gate — the landing touches this bot's build or its stub" >&2
  else SMOKE_ONLY=1; print -r -- "painandgainadvanced: smoke, ${#SMOKE} lines of $SMOKE_TICKS ticks — the landing does not touch this bot (full gate: zsh regress.sh gate)" >&2; fi
fi
typeset -A SMOKE_SET SMOKE_FOUND
for s in $SMOKE; do SMOKE_SET[$s]=1; done
mkdir -p out
PLANDIR=$(mktemp -d)
N=0
run() { # $1 = map file, $2 = START (p1|p2), $3 = scenario, $4 = ticks — collected here, executed below
  local lb="${1#map-}" ticks=$4
  lb="${lb%.txt}-$2:$3"
  (( ${+SMOKE_SET[$lb]} )) && SMOKE_FOUND[$lb]=1
  if (( SMOKE_ONLY )); then (( ${+SMOKE_SET[$lb]} )) || return 0; ticks=$SMOKE_TICKS; fi
  N=$((N + 1)); print -r -- "$N $1 $2 $3 $ticks $TAG $PLANDIR" >> "$PLANDIR/plan"
}
# grind: run by `zsh regress.sh <tag>` for any tag but gate/land — the other side of each map (the terrain is NOT point
# symmetric: 1600 of 10 000 cells differ from their mirror, so p2 on map 1 is another match) and the mirror match
grind() { if [[ "$TAG" == land || "$TAG" == gate ]]; then return; fi; run "$@"; }

# the gate: each live map from the side we played it on live (map 1: replay 6ab90e68, we were player 1; map 2: the v1
# log of 6ab90ed9, we were player 2), against the three scripts, the whole 5000 ticks
run map-live1.txt p1 none 5000
run map-live1.txt p1 rush 5000
run map-live1.txt p1 farm 5000
run map-live2.txt p2 none 5000
run map-live2.txt p2 rush 5000
run map-live2.txt p2 farm 5000

grind map-live1.txt p2 none 5000
grind map-live1.txt p2 rush 5000
grind map-live1.txt p2 farm 5000
grind map-live2.txt p1 none 5000
grind map-live2.txt p1 rush 5000
grind map-live2.txt p1 farm 5000
grind map-live1.txt p1 mirror 5000
grind map-live2.txt p2 mirror 5000

# a SMOKE label without a gate line would shrink the smoke silently: checked on every gate run, so its own landing catches it
if [[ "$TAG" == land || "$TAG" == gate ]]; then
  for s in $SMOKE; do
    (( ${+SMOKE_FOUND[$s]} )) || printf '%-4s %-16s %-32s | errors: %s \n' FAIL smoke "no gate line $s" 1
  done
fi

if [[ -s "$PLANDIR/plan" ]]; then xargs -P "$JOBS" -n 7 zsh "$SELF" --one < "$PLANDIR/plan"; fi
for ((i = 1; i <= N; i++)); do
  if [[ -s "$PLANDIR/$i" ]]; then cat "$PLANDIR/$i"
  else printf '%-4s %-16s %-32s | errors: %s \n' FAIL "scenario-$i" "worker produced nothing" 1; fi
done
rm -rf "$PLANDIR"
