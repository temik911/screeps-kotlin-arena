#!/bin/zsh
# Play the current build against itself over several mirrored maps, each seed BOTH ways round.
# Usage: tools/stub/spawnandswamp/selfplay.sh [seeds] [ticks]
# One line per match, then the tally. With one build on both sides a pair should split 1-1, so the
# tally is the null: a change that beats the previous build shows up as a skew, and nothing else does.
cd "$(dirname "$0")"
NODE=${NODE:-$(ls -d ~/.gradle/nodejs/node-*/bin/node 2>/dev/null | tail -1)}
if [[ ! -x "$NODE" ]]; then echo "selfplay: node not found under ~/.gradle/nodejs (run ./gradlew build once)"; exit 1; fi
# V8 heap settings for every node process started from here (20.09.2026): a stub scenario holds 13 MB of live data, and
# left to V8's 4 GB default limit its process grows to 290 MB. A 2 MB semi-space and an old-space limit of 192 MB keep it
# near 170 at about 14 % more run time; the logs do not change. The limit is hard — a bot whose live heap outgrows it dies with
# "heap out of memory". STUB_NODE_FLAGS= (empty) switches the settings off, STUB_NODE_FLAGS="--max-old-space-size=512" widens
# them. The measurement and the choice of 192 are in tools/stub/painandgain/regress.sh.
if [[ -z ${STUB_NODE_FLAGS_APPLIED-} ]]; then   # once: these scripts call one another and the value is inherited
  export NODE_OPTIONS="${STUB_NODE_FLAGS---max-semi-space-size=2 --max-old-space-size=192}${NODE_OPTIONS:+ $NODE_OPTIONS}"
  export STUB_NODE_FLAGS_APPLIED=1
fi
SEEDS=${1:-6}
TICKS=${2:-2000}
# OPP=<name> makes side B a saved build (see snapshot.sh); side A is always the current one, so the
# tally reads "how the candidate does against what it would replace". Unset, both sides are current.
OPP=${OPP:-}
mkdir -p out
a=0; b=0; d=0; e=0
for ((s=1; s<=SEEDS; s++)); do
  for f in A B; do
    out=out/selfplay_${s}_${f}.txt
    FIRST=$f OPP=$OPP "$NODE" --import ./register.mjs selfplay.mjs "$TICKS" "$s" > "$out" 2>&1
    line=$(grep -E '^--- selfplay' "$out" | sed -E 's/\x1b\[[0-9;]*m//g')
    tail2=$(grep -E '^--- A:' "$out" | sed -E 's/\x1b\[[0-9;]*m//g')
    w=$(echo "$line" | sed -nE 's/.*winner=([A-Za-z]+).*/\1/p')
    err=$(echo "$line" | sed -nE 's/.*errors=([0-9]+).*/\1/p')
    case "$w" in A) a=$((a+1));; B) b=$((b+1));; *) d=$((d+1));; esac
    e=$((e+${err:-0}))
    printf '%-28s | %s\n' "${line#--- selfplay: }" "${tail2#--- }"
  done
done
echo "--- selfplay tally: A(current) won $a, B(${OPP:-current}) won $b, drawn $d, errors $e (over $SEEDS seeds x 2 orders)"
