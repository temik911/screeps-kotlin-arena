#!/bin/zsh
# ab.sh <bundle A | -> <bundle B | -> ["scenarios"] — two builds of the bot on the eleven records of the rival suite
# (seven of stachu3478#5, four of Hardy#3), BOTH sides of each map (US= flips the side we drive), each scenario
# (default "chase line"); one line per run and a TOTAL per build: wins by annihilation (W), losses (L), wins on the
# score (S), and the hits each side lost 100 ticks after contact, summed.
#
# A bundle is the `.../season4/painandgainadvanced/PainAndGainAdvanced.export.mjs` of any build as a file:// url (an
# older commit's `build/js/packages/screeps-kotlin-arena-starter` copied aside); `-` is this worktree's build.
#
# Why both sides and both models (27.09.2026, v20): one scenario on one side gave 9 of 9 identical fights at the
# fortress — the terrain around it hardly differs between records — so nine maps were one sample; the fatigue flag's
# first cut looked better on `line` and worse on `chase`, and only the 44 runs together told the cuts apart.
cd ${0:A:h}
NODE=${NODE:-$(ls -d ~/.gradle/nodejs/node-*/bin/node 2>/dev/null | tail -1)}
if [[ ! -x "$NODE" ]]; then echo "ab: node not found under ~/.gradle/nodejs (run ./gradlew build once)"; exit 1; fi
export NODE_OPTIONS="${STUB_NODE_FLAGS---max-semi-space-size=2 --max-old-space-size=192}"
A=$1; B=$2; SC=(${=${3:-chase line}})
typeset -A opp
for r in 6ab91898 6ab91924 6ab919a7 6ab91a90 6ab91b4b 6ab91b62 6ab930ac; do opp[$r]=stachu3478; done
for r in 6ab9349f 6ab93d04 6ab916f5 6ab9465b; do opp[$r]=Hardy; done
for lab in A B; do
  bun=${(P)lab}; w=0; l=0; d=0; o=0; h=0
  for s in $SC; do for r in ${(ok)opp}; do for us in temik911 ${opp[$r]}; do
    out=$(env NOCLOCK=1 LOGTAG=ab-$lab-$r-$us- ${${bun:#-}:+BOT=$bun} US=$us REPLAY=$r $NODE --import ./register.mjs run.mjs 5000 $s 2>&1)
    res=$(print -r -- "$out" | grep -E "^done:" | head -1); e=$(print -r -- "$out" | grep -E "^entry:" | head -1)
    if print -r -- "$res" | grep -q "enemy army destroyed"; then w=$((w+1)); tag=W
    elif print -r -- "$res" | grep -q "our army destroyed"; then l=$((l+1)); tag=L
    else d=$((d+1)); tag=S; fi
    a=$(print -r -- "$e" | sed -n 's/.*+100 \([0-9]*\)\/\([0-9]*\).*/\1 \2/p'); set -- ${=a}
    o=$((o+${1:-0})); h=$((h+${2:-0}))
    print -r -- "$lab $tag $s $r us=$us ${res[7,70]} | +100 ${1:-?}/${2:-?}"
  done; done; done
  echo "TOTAL $lab: W=$w L=$l S=$d  +100 ours/his $o/$h"
done
