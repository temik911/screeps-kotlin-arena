#!/bin/zsh
# The league: this worktree's build against frozen builds of the same bot and against the field's personas, on the stub's
# live terrains from both starts, every run with the combat trace (FIGHTS) — then fights.py over the lot.
#   zsh league.sh <tag> [ticks=5000]
#   env: OPPS  — opponents (default "v52 v56 v58 kerobii kerobii22"): vNN = out/frozen-vNN/ as BOT2, self = BOT2=self (a
#                copy of the snapshot below), <name>=<file url of the export .mjs of a SEPARATE copy of any build> = that
#                build as BOT2 under that name, any other word = a run.mjs scenario (a persona, none);
#        MAPS  — record ids whose terrain is played (default: the four in ./replays/);
#        SIDES — our starts (default "left right");
#        JOBS  — node processes at once (default 4; the machine's memory, see README "Commands");
#        BOT   — file url of the build playing as us (default: a snapshot of THIS worktree's build copied into the league's
#                folder at the start, so a rebuild during the league does not change who plays half-way).
# Writes out/league-<tag>/: results.tsv (map, side, opponent, outcome, wall seconds), the traces fights-*.jsonl.gz, and
# report.txt (fights.py). The bots' consoles go to out/run-lg-<tag>-<map>-<side>-<opp>-*.log as every run's do.
SELF=${0:A}
cd "${SELF:h}"
NODE=${NODE:-$(ls -d ~/.gradle/nodejs/node-*/bin/node 2>/dev/null | tail -1)}
if [[ ! -x "$NODE" ]]; then echo "league: node not found under ~/.gradle/nodejs"; exit 1; fi
if [[ -z ${STUB_NODE_FLAGS_APPLIED-} ]]; then   # the heap cap of regress.sh, same values, same switch
  export NODE_OPTIONS="${STUB_NODE_FLAGS---max-semi-space-size=2 --max-old-space-size=192}${NODE_OPTIONS:+ $NODE_OPTIONS}"
  export STUB_NODE_FLAGS_APPLIED=1
fi
TAG=${1:?usage: zsh league.sh <tag> [ticks]}
TICKS=${2:-5000}
OPPS=(${=${OPPS:-v52 v56 v58 kerobii kerobii22}})
MAPS=(${=${MAPS:-6abc0dc7 6abc107c 6abc21a1 6abbba40}})
SIDES=(${=${SIDES:-left right}})
JOBS=${JOBS:-4}
DIR=out/league-$TAG
mkdir -p $DIR
BUNDLE=kotlin/screeps-kotlin-arena-starter/season4/spawnandswampadvanced/SpawnAndSwampAdvanced.export.mjs
if [[ -z ${BOT-} ]]; then
  rm -rf $DIR/cur
  cp -R ../../../build/js/packages/screeps-kotlin-arena-starter $DIR/cur
  BOT="file://$PWD/$DIR/cur/$BUNDLE"
fi
export BOT
print -r -- "league $TAG: ticks=$TICKS opps=(${OPPS}) maps=(${MAPS}) sides=(${SIDES}) jobs=$JOBS bot=$BOT"
: > $DIR/results.tsv
one() { # map side opp
  local map=$1 side=$2 opp=$3 sc=none raw line t0 t1 url=
  [[ $opp == *=* ]] && { url=${opp#*=}; opp=${opp%%=*}; }
  local -a env=("LOGTAG=lg-$TAG-$map-$side-$opp-" "REPLAY=$map" "START=$side" "FIGHTS=$PWD/$DIR/fights-$map-$side-$opp.jsonl")
  if [[ -n $url ]]; then env+=("BOT2=$url")
  elif [[ $opp == self ]]; then env+=("BOT2=self")
  elif [[ $opp == v<-> ]]; then
    [[ -d out/frozen-$opp ]] || { print -r -- "league: no out/frozen-$opp"; return; }
    env+=("BOT2=file://$PWD/out/frozen-$opp/$BUNDLE")
  else sc=$opp; fi
  t0=$EPOCHSECONDS
  raw=$(env "${env[@]}" "$NODE" --import ./register.mjs run.mjs $TICKS $sc 2>&1)
  t1=$EPOCHSECONDS
  print -r -- "$raw" > $DIR/stdout-$map-$side-$opp.txt   # the runner's own lines: status every 100 ticks, done, milestones
  line=$(print -r -- "$raw" | grep '^done:' | tail -1 | sed -E 's/^done: (.*) alive=.*/\1/')
  [[ -z $line ]] && line="CRASH: $(print -r -- "$raw" | grep -m1 -iE 'error|heap' | cut -c1-120)"
  print -r -- "$map	$side	$opp	$line	$((t1 - t0))" >> $DIR/results.tsv
  printf '%-9s %-5s %-9s %-58s %4ss\n' $map $side $opp "$line" $((t1 - t0))
}
zmodload zsh/datetime zsh/parameter
T0=$EPOCHSECONDS
for opp in $OPPS; do for map in $MAPS; do for side in $SIDES; do
  while (( ${#jobstates} >= JOBS )); do sleep 1; done
  one $map $side $opp &
done; done; done
wait
print -r -- "league $TAG: $(wc -l < $DIR/results.tsv | tr -d ' ') runs in $((EPOCHSECONDS - T0)) s"
python3 fights.py $DIR/fights-*.jsonl.gz --tsv $DIR/fights.tsv > $DIR/report.txt && print -r -- "report: $DIR/report.txt, one line per fight and side: $DIR/fights.tsv"
