#!/bin/zsh
# The gate for a candidate build: three opponents, not one.
#
#     tools/stub/spawnandswamp/snapshot.sh <name>      # once, on the build you would replace
#     tools/stub/spawnandswamp/league.sh <name>        # after changing the bot and rebuilding
#
# Why three. On 08.09.2026 six builds in a row passed the 26 scenarios, improved the recorded keroby
# fixture, and lost in the arena — five wins in eighty-four against the previous build's seven in
# twenty-eight. One instrument is not an instrument. So a candidate is judged by:
#
#   1. the 26 gate scenarios — REGRESSION only. Every line must still take the enemy spawn; the tick
#      numbers are printed next to the baseline's so a slowdown is visible rather than silent.
#   2. the recorded keroby#19 with hunting — one specific strong opponent, faithful map and queue.
#   3. self-play against the build being replaced — the only opponent here that ANSWERS.
#
# The rule: never regress (1); improve (2) or (3) without hurting the other. Nothing here predicts the
# arena on its own — (2) cannot, because a recording does not react, and (3) cannot, because it
# optimises the mirror. Together they are what this harness can honestly say.
cd "$(dirname "$0")"
OPP=$1
[[ -z "$OPP" ]] && { echo "usage: league.sh <opponent-snapshot-name>   (make one with snapshot.sh)"; exit 2; }
[[ -d "opponents/$OPP" ]] || { echo "league: no snapshot at opponents/$OPP — run snapshot.sh $OPP on the build you would replace"; exit 2; }
NODE=${NODE:-$(ls -d ~/.gradle/nodejs/node-*/bin/node 2>/dev/null | tail -1)}
mkdir -p out
strip() { sed -E 's/\x1b\[[0-9;]*m//g'; }

echo "=== 1. regression: the 26 gate scenarios"
zsh ./regress.sh league > out/league_regress.txt 2>&1
bad=$(strip < out/league_regress.txt | grep -cvE 'SPAWN DESTROYED|PASS')
strip < out/league_regress.txt | sed -E 's/ \| ghost.*//'
if [[ -f "opponents/$OPP/regress.txt" ]]; then
  echo "--- against the baseline saved with the snapshot:"
  diff <(strip < "opponents/$OPP/regress.txt" | sed -E 's/ \|.*//') \
       <(strip < out/league_regress.txt | sed -E 's/ \|.*//') || true
fi
echo "--- scenarios not passing: $bad"

echo
echo "=== 2. the recorded keroby#19, at three hunting settings"
for h in 0 4 8; do
  HUNT=$h "$NODE" --import ./register.mjs replayrun.mjs scenarios/kerobi19.json 2000 > out/league_k19_$h.txt 2>&1
  res=$(strip < out/league_k19_$h.txt | grep -E 'SPAWN DESTROYED' | head -1)
  spent=$(strip < out/league_k19_$h.txt | grep -oE 'spent=[0-9]+/[0-9]+' | tail -1)
  printf '  HUNT=%-2s %-32s %s\n' "$h" "${res:-SURVIVED (draw)}" "$spent"
done

echo
echo "=== 3. self-play against $OPP"
OPP=$OPP zsh ./selfplay.sh 8 1200 2>/dev/null > out/league_selfplay_raw.txt
# the per-game lines come from the match files, not from selfplay.sh's stdout: that already strips
# the prefix and pads the columns, so a pattern written against the raw line matches nothing there
cat out/selfplay_*.txt | strip | grep -E '^--- selfplay: ' | sed -E 's/^--- selfplay: //; s/ errors=[0-9]+//' | sort > out/league_selfplay.txt
strip < out/league_selfplay_raw.txt | tail -1
# THE TALLY ALONE CANNOT SAY "THE CANDIDATE CHANGED NOTHING HERE", and on 09.09.2026 that mattered:
# re-judging the whole shelf gave seven builds in a row the identical 9-6-1, and only a diff of the
# games showed why — every match was byte-identical to the null but for the greeting line. Mirror
# matches end at 416-607 ticks by a spawn kill, and every economic mechanism those builds changed
# fires later, so the instrument never executed them. Printed as a verdict, that is visible; printed
# as a tally, it reads like a tie.
if [[ -f "opponents/$OPP/selfplay.txt" ]]; then
  if diff -q "opponents/$OPP/selfplay.txt" out/league_selfplay.txt > /dev/null; then
    echo "  ⚠ every game identical to the null — this instrument did not execute anything the candidate changed"
  else
    echo "  games that moved against the null:"
    diff "opponents/$OPP/selfplay.txt" out/league_selfplay.txt | grep -E '^[<>]' | sed 's/^/    /'
  fi
else
  echo "  (no null vector saved with the snapshot — re-run snapshot.sh to get one)"
fi
