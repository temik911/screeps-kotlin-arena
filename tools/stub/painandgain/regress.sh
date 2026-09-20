#!/bin/zsh
# Full stub regression for the Pain and Gain bot against THIS worktree's build (run.mjs imports ../../../build/js/...).
# Usage: zsh tools/stub/painandgain/regress.sh [tag]        JOBS=<n> to change the parallel width (default 8)
#        tag `gate` (and `land`, used by tools/land.sh) runs the `run` lines only; any other tag runs `grind` lines too
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
# V8 heap settings for every node process started from here (20.09.2026). A scenario holds 23 MB of LIVE data at any tick
# count, yet its process grew to 260-360 MB: V8's default heap limit is 4 GB, so it is in no hurry to collect, and JOBS of
# them at once were the gate's three gigabytes on a machine that has none to spare. What the settings buy, measured on the
# gate itself (the 143 `run` lines, JOBS=8, the node processes' summed RSS sampled five times a second, two rounds each):
#   off                           mean 2 355 MB   p95 2 850   max 3 056   wall 102 s
#   semi-space 2, old-space 192   mean 1 616      p95 1 960   max 2 106   wall 116 s   (-31 % for +14 %)
#   semi-space 8, old-space 192   mean 1 775      p95 2 236   max 2 450   wall 105 s   (-22 % for  +3 %)
# The old-space limit is where the gain is, and 192 is where it stops: on the heaviest line (farm on map 28, median of
# three) the process peaks at 359 MB by default and at 172 under 192, 165 under 128, 171 under 96 — flat below 192, so a
# tighter limit buys only risk, and 192 is eight times the live set. The semi-space is the trade: the young generation is
# up to three semi-spaces per process, which is the 150 MB between the two rows; 2 MB is taken because memory is what runs
# out on this machine, and STUB_NODE_FLAGS="--max-semi-space-size=8 --max-old-space-size=192" gives the time back.
# All 324 logs of the full suite are byte-identical with the settings on and off (NOCLOCK=1) — a collector's schedule is
# not something the bot can see.
# The limit is HARD: a bot whose live heap outgrows it dies with "heap out of memory", and its line says so below rather
# than "no done line". STUB_NODE_FLAGS= (empty) switches the settings off, STUB_NODE_FLAGS="--max-old-space-size=512"
# widens them; a NODE_OPTIONS of the caller's is kept and comes last, so it wins. Set once: the workers below re-run this
# script from the top and inherit the exported value.
if [[ -z ${STUB_NODE_FLAGS_APPLIED-} ]]; then
  export NODE_OPTIONS="${STUB_NODE_FLAGS---max-semi-space-size=2 --max-old-space-size=192}${NODE_OPTIONS:+ $NODE_OPTIONS}"
  export STUB_NODE_FLAGS_APPLIED=1
fi

# one scenario, in a worker process: writes its report line into <dir>/<n> (see the xargs call at the end)
if [[ "$1" == --one ]]; then
  local_n=$2; map=$3; start=$4; sc=$5; TAG=$6; dir=$7
  label="${map#map-}"; label="${label%.txt}:$sc"
  # GHOST-СТРОКА: `replay:<файл>` вместо карты — врагом правит запись живого матча (см. run.mjs, сценарий ghost).
  # Зачем это в воротах: без них у гейта НЕТ НИ ОДНОЙ проигрышной строки — 135 из 135 PASS, — а значит он не может
  # ответить на вопрос «от какой правки мы начнём проигрывать», только на «насколько быстро мы выигрываем».
  # ЗАПИСЬ, ВОШЕДШАЯ В ВОРОТА, ЛЕЖИТ В РЕПОЗИТОРИИ (19.09.2026, оператор): гейт гоняет каждая посадка каждой сессии и не
  # должен зависеть от файлов вне репозитория — ./replays/ (190–300 КБ на запись). $REPLAY_DIR (по умолчанию
  # ~/ScreepsArena/replays) остаётся для строк исследования, которых в воротах нет; такая запись на чужой машине может
  # отсутствовать, и пропуск делается ГРОМКИМ — предупреждением в stderr и счётом в сводке exposure, — а не тихим:
  # молчащая строка гейта это ровно тот лгущий прибор, которого здесь быть не должно.
  if [[ "$map" == replay:* ]]; then
    rp="${map#replay:}"; label="ghost:${rp%%.*}:$sc"
    if [[ -f "./replays/$rp" ]]; then rpdir=./replays; else rpdir=${REPLAY_DIR:-$HOME/ScreepsArena/replays}; fi
    if [[ ! -f "$rpdir/$rp" ]]; then
      print -r -- "ghost: ПРОПУЩЕНА строка $label — нет записи $rpdir/$rp" >&2
      # маркер, а не пустой файл: пустой главный цикл читает как «воркер ничего не выдал» и печатает FAIL
      print -r -- "#SKIP $label нет записи" > "$dir/$local_n"
      return 0 2>/dev/null || exit 0
    fi
    raw=$(LOGTAG="${TAG}-$label-" REPLAY="$rpdir/$rp" "$NODE" --import ./register.mjs run.mjs 2000 ghost 2>&1)
  elif [[ "$map" == - ]]; then
    raw=$(LOGTAG="${TAG}-" "$NODE" --import ./register.mjs run.mjs 2000 "$sc" 2>&1)
  elif [[ "$start" == - ]]; then
    raw=$(LOGTAG="${TAG}-$label-" MAP="$map" "$NODE" --import ./register.mjs run.mjs 2000 "$sc" 2>&1)
  else
    raw=$(LOGTAG="${TAG}-$label-" MAP="$map" START="$start" "$NODE" --import ./register.mjs run.mjs 2000 "$sc" 2>&1)
  fi
  line=$(print -r -- "$raw" | grep '^done:' | tail -1)
  # done: <outcome> score=a/b alive=x/y errors=N time=..s log=...
  outcome=$(print -r -- "$line" | sed -E 's/^done: (.*) score=.*/\1/')
  a=$(print -r -- "$line" | sed -E 's/.*score=([0-9]+)\/([0-9]+).*/\1/'); b=$(print -r -- "$line" | sed -E 's/.*score=([0-9]+)\/([0-9]+).*/\2/')
  errors=$(print -r -- "$line" | sed -E 's/.*errors=([0-9]+).*/\1/')
  if [[ -z "$line" ]]; then verdict=FAIL; outcome="no done line"; errors=?
    # the heap limit set at the top is a hard one: name it, so the line is not read as a crash of the bot
    [[ "$raw" == *"heap out of memory"* ]] && outcome="node heap limit hit (STUB_NODE_FLAGS)"
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
grind() { if [[ "$TAG" == land || "$TAG" == gate ]]; then return; fi; run "$@"; }
# v29 (05.09.2026, the operator's decision): six lines that v28 passed and v29 loses are in the grind as the known cost of
# the doctrine "the first flag is theirs" — the dispersed and farming opponents (m12/m18 spread, m18/m19/m31 farm) pay for
# its caution in the first forty ticks, and m28 army is the brawl at 1.0 that flips on a tick either way. The gate keeps
# the live opponent instead: screen/nine/block+flagless and farm+weak, twelve lines v28 lost four armies to.
# 05.09.2026, second batch (evade away from the enemy, healers accept ranged fire): m17 fourteen and m28 hunter are brawls at
# 1.0 that flip with any change to where the army stands (won in one build, lost in the next), m24 and m32 farm are the
# farm family's points races — all four in the grind; the twelve live-opponent lines stay green.
# v30 (05.09.2026, the press): m20 farm and m29 farm join the farm family in the grind — their races against the farmer run
# byte-identical to v29 until t=890 and t=970 and flip on a one-cell difference of the army's centroid at t=900 and t=980,
# a thousand ticks before the end; the fight logic is not involved (the farmer never fights, and the press first fired at
# t=1570 in m29). The open finding behind every farm line is the same: a blob that never engages and is never caught
# out-farms an army that holds parity — see docs/pain-and-gain.md.
# 05.09.2026 (the operator's question after fifteen full runs in a day): the gate keeps the lines that carry signal — every
# line that has ever failed in 86 recorded runs, every live-opponent script (screen, nine/block/screen+flagless, farm+weak,
# twelve, grab, scouts, none) and every line of the recent maps 28–35; the generic scripts (rush, kite, hunter, sleeper,
# nine, fourteen, block, wing, army, roost) on maps 1–25 that never failed once are in the grind — still run in full
# v42 (05.09.2026, match 70): `camp` — the farmer that takes what it can and then parks the whole blob on the centre flag —
# on the five maps where it actually parks (29, 30, 31, 33, 34); on 28, 32 and 35 our army leaves flags unoccupied, the
# blob keeps walking back onto them and the line is the farm race again, so those three are not listed twice
# mode (`zsh regress.sh <tag>`), never by the landing gate. `zsh regress.sh gate` runs the gate only (~1.5 min).
# БЫСТРЫЙ ЦИКЛ (20.09.2026, docs/pain-and-gain-architecture-2.md, этап 0): ONLY=<файл с метками сценариев, по одной на строку>
# оставляет в прогоне только их — метка та же, что во втором столбце отчёта (`match5:rush`, `ghost:<id>:ghost`). Список даёт
# `gategap.py --rows <строки таблиц> --list`, зовёт это `identity.sh --rows`. Полный гейт остаётся обязательным перед посадкой:
# tools/land.sh переменную ONLY не ставит.
typeset -A ONLY_SET
if [[ -n "${ONLY:-}" ]]; then while IFS= read -r l; do [[ -n "$l" ]] && ONLY_SET[$l]=1; done < "$ONLY"; fi
run() { # $1 = map file or -, $2 = START or -, $3 = scenario — collected here, executed in parallel below
  if [[ -n "${ONLY:-}" ]]; then
    local lb
    if [[ "$1" == replay:* ]]; then lb="${1#replay:}"; lb="ghost:${lb%%.*}:$3"; else lb="${1#map-}"; lb="${lb%.txt}:$3"; fi
    [[ -n "${ONLY_SET[$lb]:-}" ]] || return 0
  fi
  N=$((N + 1)); print -r -- "$N $1 $2 $3 $TAG $PLANDIR" >> "$PLANDIR/plan"
}
run map-match1.txt -      grab
grind map-match1.txt -      rush
run map-match1.txt -      scouts
run map-match2.txt match2 scouts
run map-match2.txt match2 grab
grind map-match2.txt match2 rush
grind map-match3.txt match2 kite
run map-match3.txt match2 army
grind map-match3.txt match2 rush
grind map-match3.txt match2 sleeper
run map-match3.txt match2 none
grind map-match4.txt match2 hunter
run map-match4.txt match2 kite
grind map-match4.txt match2 rush
grind map-match4.txt match2 sleeper
grind map-match5.txt match2 kite
run map-match5.txt match2 hunter
run map-match5.txt match2 rush
run map-match5.txt match2 army
grind map-match6.txt -      hunter
grind map-match6.txt -      kite
grind map-match6.txt -      rush
grind map-match6.txt -      sleeper
grind map-match7.txt match2 hunter
grind map-match7.txt match2 kite
grind map-match7.txt match2 rush
grind map-match7.txt match2 sleeper
grind map-match8.txt match2 hunter
run map-match8.txt match2 kite
grind map-match8.txt match2 rush
grind map-match8.txt match2 sleeper
grind map-match8.txt match2 army
run map-match8.txt match2 none
grind map-match9.txt match2 nine
run map-match9.txt match2 hunter
grind map-match9.txt match2 kite
grind map-match9.txt match2 rush
grind map-match9.txt match2 sleeper
grind map-match10.txt -      nine
grind map-match10.txt -      hunter
grind map-match10.txt -      kite
grind map-match10.txt -      rush
grind map-match10.txt -      sleeper
grind map-match11.txt -      nine
grind map-match11.txt -      hunter
grind map-match11.txt -      kite
grind map-match11.txt -      rush
grind map-match11.txt -      sleeper
run map-match12.txt match2 twelve
grind map-match12.txt match2 hunter
grind map-match12.txt match2 kite
grind map-match12.txt match2 rush
grind map-match12.txt match2 sleeper
grind map-match13.txt -      nine
grind map-match13.txt -      hunter
run map-match13.txt -      kite
run map-match13.txt -      rush
grind map-match13.txt -      sleeper
grind map-match13.txt -      fourteen
grind map-match14.txt -      fourteen
grind map-match14.txt -      nine
run map-match14.txt -      hunter
grind map-match14.txt -      kite
grind map-match14.txt -      rush
grind map-match14.txt -      sleeper
grind map-match9.txt match2 fourteen
grind map-match15.txt -      nine
grind map-match15.txt -      fourteen
grind map-match15.txt -      hunter
grind map-match15.txt -      kite
grind map-match15.txt -      rush
grind map-match15.txt -      sleeper
grind map-match15.txt -      block
grind map-match14.txt -      block
grind map-match16.txt -      block
grind map-match16.txt -      nine
grind map-match16.txt -      fourteen
grind map-match16.txt -      hunter
grind map-match16.txt -      kite
grind map-match16.txt -      rush
grind map-match16.txt -      sleeper
grind map-match16.txt -      wing
grind map-match15.txt -      wing
run map-match17.txt -      wing
grind map-match17.txt -      block
grind map-match17.txt -      nine
grind map-match17.txt -      fourteen
grind map-match17.txt -      hunter
grind map-match17.txt -      kite
grind map-match17.txt -      rush
grind map-match17.txt -      sleeper
grind map-match18.txt match2 wing
grind map-match18.txt match2 block
grind map-match18.txt match2 nine
grind map-match18.txt match2 fourteen
grind map-match18.txt match2 hunter
grind map-match18.txt match2 kite
run map-match18.txt match2 rush
grind map-match18.txt match2 sleeper
grind map-match18.txt match2 spread
grind map-match12.txt match2 spread
grind map-match19.txt match2 spread
grind map-match19.txt match2 block
run map-match19.txt match2 nine
grind map-match19.txt match2 wing
grind map-match19.txt match2 hunter
grind map-match19.txt match2 kite
grind map-match19.txt match2 rush
grind map-match19.txt match2 sleeper
grind map-match20.txt -      hunter
grind map-match20.txt -      army
grind map-match20.txt -      nine
grind map-match20.txt -      wing
grind map-match20.txt -      block
grind map-match20.txt -      rush
run map-match20.txt -      kite
grind map-match20.txt -      sleeper
grind map-match20.txt -      spread
grind map-match21.txt match2 wing
grind map-match21.txt match2 hunter
grind map-match21.txt match2 nine
grind map-match21.txt match2 block
run map-match21.txt match2 army
grind map-match21.txt match2 rush
grind map-match21.txt match2 kite
grind map-match21.txt match2 fourteen
grind map-match21.txt match2 sleeper
grind map-match21.txt match2 spread
grind map-match22.txt -      hunter
grind map-match22.txt -      wing
grind map-match22.txt -      block
grind map-match22.txt -      nine
grind map-match22.txt -      army
grind map-match22.txt -      rush
grind map-match22.txt -      kite
grind map-match22.txt -      fourteen
grind map-match22.txt -      sleeper
grind map-match22.txt -      spread
grind map-match23.txt -      hunter
grind map-match23.txt -      wing
grind map-match23.txt -      block
grind map-match23.txt -      nine
run map-match23.txt -      army
grind map-match23.txt -      rush
grind map-match23.txt -      kite
grind map-match23.txt -      fourteen
grind map-match23.txt -      sleeper
grind map-match23.txt -      spread
grind map-match24.txt match2 wing
grind map-match24.txt match2 hunter
grind map-match24.txt match2 block
grind map-match24.txt match2 nine
run map-match24.txt match2 army
grind map-match24.txt match2 rush
grind map-match24.txt match2 kite
grind map-match24.txt match2 fourteen
grind map-match24.txt match2 sleeper
grind map-match24.txt match2 spread
grind map-match24.txt match2 roost
grind map-match25.txt match2 wing
grind map-match25.txt match2 hunter
grind map-match25.txt match2 block
grind map-match25.txt match2 nine
run map-match25.txt match2 army
grind map-match25.txt match2 rush
grind map-match25.txt match2 kite
grind map-match25.txt match2 fourteen
grind map-match25.txt match2 roost
grind map-match25.txt match2 sleeper
grind map-match25.txt match2 spread
run map-match35.txt -      wing
run map-match35.txt -      hunter
run map-match35.txt -      block
run map-match35.txt -      nine
run map-match35.txt -      army
run map-match35.txt -      rush
run map-match35.txt -      kite
run map-match35.txt -      fourteen
run map-match35.txt -      roost
run map-match35.txt -      screen
grind map-match35.txt -      farm
grind map-match35.txt -      sleeper
grind map-match35.txt -      spread
run map-match34.txt match2 wing
run map-match34.txt match2 hunter
run map-match34.txt match2 block
run map-match34.txt match2 nine
run map-match34.txt match2 army
run map-match34.txt match2 rush
run map-match34.txt match2 kite
run map-match34.txt match2 fourteen
run map-match34.txt match2 roost
run map-match34.txt match2 camp
run map-match34.txt match2 screen
grind map-match34.txt match2 farm
grind map-match34.txt match2 sleeper
grind map-match34.txt match2 spread
run map-match33.txt match2 wing
run map-match33.txt match2 hunter
run map-match33.txt match2 block
run map-match33.txt match2 nine
grind map-match33.txt match2 army
run map-match33.txt match2 rush
run map-match33.txt match2 kite
run map-match33.txt match2 fourteen
run map-match33.txt match2 roost
run map-match33.txt match2 camp
grind map-match33.txt match2 farm
run map-match33.txt match2 screen
grind map-match33.txt match2 sleeper
grind map-match33.txt match2 spread
run map-match32.txt -      wing
run map-match32.txt -      hunter
run map-match32.txt -      block
run map-match32.txt -      nine
run map-match32.txt -      army
run map-match32.txt -      rush
run map-match32.txt -      kite
run map-match32.txt -      fourteen
run map-match32.txt -      roost
grind map-match32.txt -      farm
run map-match32.txt -      screen
grind map-match32.txt -      sleeper
grind map-match32.txt -      spread
run map-match31.txt -      wing
run map-match31.txt -      hunter
run map-match31.txt -      block
run map-match31.txt -      nine
run map-match31.txt -      army
run map-match31.txt -      rush
run map-match31.txt -      kite
run map-match31.txt -      fourteen
run map-match31.txt -      roost
run map-match31.txt -      camp
grind map-match31.txt -      farm
run map-match31.txt -      screen
grind map-match31.txt -      sleeper
grind map-match31.txt -      spread
run map-match30.txt match2 screen+flagless
run map-match31.txt -      screen+flagless
run map-match32.txt -      screen+flagless
run map-match29.txt match2 screen+flagless
run map-match25.txt match2 screen+flagless
run map-match32.txt -      nine+flagless
run map-match30.txt match2 nine+flagless
run map-match31.txt -      nine+flagless
run map-match32.txt -      block+flagless
run map-match34.txt match2 screen+flagless
run map-match34.txt match2 nine+flagless
run map-match34.txt match2 block+flagless
run map-match35.txt -      screen+flagless
run map-match35.txt -      nine+flagless
run map-match33.txt match2 farm+weak
run map-match28.txt -      farm+weak
# v100 (06.09.2026, the operator's "the scatter on the stand and the opening without the corner"): `scatter` — the live
# match-240 scatterer (ricardo18informatica2020, 17355:23708 with both armies whole) on the six maps of the spread family;
# v99 won all six narrowly (m31 by 217, m34 by 1456), v100 (the opening at the post instead of the corner) by 3700–8300
run map-match19.txt -      scatter
run map-match28.txt -      scatter
run map-match30.txt -      scatter
run map-match31.txt -      scatter
run map-match33.txt -      scatter
run map-match34.txt -      scatter
run map-match32.txt -      farm+weak
run map-match30.txt match2 wing
run map-match30.txt match2 hunter
run map-match30.txt match2 block
run map-match30.txt match2 nine
run map-match30.txt match2 army
run map-match30.txt match2 rush
run map-match30.txt match2 kite
run map-match30.txt match2 fourteen
run map-match30.txt match2 roost
run map-match30.txt match2 camp
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
run map-match29.txt match2 camp
grind map-match29.txt match2 farm
grind map-match29.txt match2 sleeper
grind map-match29.txt match2 spread
run map-match29.txt match2 screen
run map-match28.txt -      screen
run map-match25.txt match2 screen
run map-match28.txt -      wing
grind map-match28.txt -      hunter
run map-match28.txt -      block
run map-match28.txt -      nine
grind map-match28.txt -      army
run map-match28.txt -      rush
run map-match28.txt -      kite
run map-match28.txt -      fourteen
run map-match28.txt -      roost
grind map-match28.txt -      farm
grind map-match28.txt -      sleeper
grind map-match28.txt -      spread
grind map-match22.txt -      roost
grind map-match20.txt -      roost
grind map-match19.txt match2 roost
grind map-match12.txt match2 roost
run map-match18.txt match2 roost
grind map-match24.txt match2 farm
grind map-match20.txt -      farm
grind map-match19.txt match2 farm
grind map-match18.txt match2 farm
grind map-match25.txt match2 farm
grind map-match21.txt match2 farm
grind map-match12.txt match2 farm

# ВРАГ, КОТОРЫЙ БЬЁТ ПО ЛЕКАРЯМ (+heals, в гейте с 09.09.2026). Прибор молчал об этом до сих пор, и молчание стоило
# правила: USE_FOCUS_HEALER_FIRST дважды отвергался стендом, а в комментарии рядом с ним была записана и причина —
# «СТЕНД ПО ЛЕКАРЯМ НЕ БЬЁТ и своих держит вплотную, поэтому огонь по его лекарям на стенде — чистая потеря темпа, а
# живьём это 17–35 % его залпа. Предмет открыт, и он не в боте, а в приборе». Живьём цена молчания видна прямо: наш
# ожидаемый урон за бой 5 060 против его лечения 5 544 — пока его лекари живы, мы не убиваем никого в принципе.
# Кат `+heals` в run.mjs существовал, но НИ ОДНА строка гейта его не гоняла; теперь гоняют четыре. Сутки они простояли
# в grind, потому что бот их не держал: match28 терял армию (723:4865). Держит с v157 — признаком боя для командира
# стал ОГОНЬ, а не «его мили вплотную», и та же строка кончается `enemy army destroyed at t=428, alive=12/0`. Раз бот
# проходит, место строк в воротах: ворота 131 -> 135.
run map-match13.txt -      brawl+heals
run map-match20.txt -      brawl+heals
run map-match28.txt -      brawl+heals
run map-match35.txt -      screen+focus+blob+heals

# ПРОИГРЫШНЫЕ СТРОКИ (11.09.2026). До этого дня у гейта не было НИ ОДНОЙ: 135 из 135 PASS, и ответить на вопрос
# «от какой правки мы начнём проигрывать» он не мог — только на «насколько быстро мы выигрываем». За одну сессию
# это стоило дважды: `USE_COMMAND_BREAKS_OFF` получил +3 699 и дал живьём четыре аннигиляции из четырёх
# срабатываний; `USE_COMMAND_FIGHT_WHILE_PUSHING` получил +7 строк в уничтожение и уронил живой контроль с 4-0 до
# 2-4 и 1-5. Жёсткий критерий (FAIL) за ту же сессию не ошибся ни разу.
# Здесь врагом правит ЗАПИСЬ живого матча (сценарий ghost). Строка нашего разгрома обязана сперва ПАДАТЬ — иначе
# она ничего не проверяет, — и зеленеть по мере починки; записей наших разгромов в воротах пока нет (те, что от
# Coldkimchi, не скачаны — клиент играл рейтинговую серию). Формат строки: run replay:<id>.replay.json.gz - ghost
#
# СТЕНД НЕ УМЕЛ ЗАСТАВИТЬ АРМИЮ ОТСТУПАТЬ (19.09.2026, итог плана архитектуры, оператор). Живьём постура retreat
# держалась 1 745 тиков за 20 матчей серии v447 — на весь гейт 93; breakOff 114 против 1; cmdwhy.outmatched на гейте
# не выиграла ни разу, живьём — 104 тика. Всё, что стоит за отступлением, гейт проверял слабо, а правка командира в
# бою (перебор пишет в общее состояние) судится ровно там. Четыре записи серии, на которых СТЕНД отступает — отбор
# по строке reach ghost-лога, а не живого: призрак идёт по записи, и наш отход на стенде мог не случиться, — лежат в
# ./replays/ (984 КБ) и стоят 4–6 с каждая при четырёх параллельных; счётчики на v447 (posture.retreat / rung.retreat):
#   6aaed35b ricardo18informatica2020#13 — 109 / 251;  6aaed288 ricardo18informatica2020#13 — outmatched 7, breakOff 7, 40
#   6aaed1d2 けろびー#17 — 48 / 145;                    6aaed110 けろびー#19 — 39 / 92
run replay:6aaed35be761baf61d030b80.replay.json.gz - ghost
run replay:6aaed288e761baee71030b6b.replay.json.gz - ghost
run replay:6aaed1d2e761baded0030b64.replay.json.gz - ghost
run replay:6aaed110e761baf25a030b5c.replay.json.gz - ghost
# ДВЕ СТРОКИ РАЗДАЧИ, КОТОРЫЕ РАБОТАЛИ ТОЛЬКО ЖИВЬЁМ (20.09.2026, docs/pain-and-gain-architecture-2.md, этап 0). По прибору
# `reach` (gategap.py) проходы `catchall` и `pinned` выдавали клетки в 4 и 6 живых матчах из 52 (v447–v452) и НИ В ОДНОМ из
# 139 сценариев гейта — перенос их кода тождеством не проверялся вовсе. Запись выбрана по reach своего GHOST-лога, а не
# живого: …030b6e, которую называл план (живьём catchall 3, pinned 8), на стенде не даёт ни одной — призрак гибнет на t=93;
# из 600 новейших записей хранилища обе строки разом закрывает одна — v282 против ●ω<♥♪#6 (catchall 1, pinned 2; 1,7 с, 302 КБ).
run replay:6aa857af41cd282325e3224f.replay.json.gz - ghost


SKIPPED=0
xargs -P "$JOBS" -n 6 zsh "$SELF" --one < "$PLANDIR/plan"
for ((i = 1; i <= N; i++)); do
  if [[ -s "$PLANDIR/$i" ]]; then
    # пропущенная ghost-строка в stdout не попадает: tools/land.sh требует PASS в КАЖДОЙ строке. Громкость
    # обеспечена предупреждением в stderr и счётом в сводке exposure
    if [[ "$(head -c 5 "$PLANDIR/$i")" == "#SKIP" ]]; then SKIPPED=$((SKIPPED + 1)); else cat "$PLANDIR/$i"; fi
  else printf '%-4s %-22s %-40s score %s:%s | errors: %s \n' FAIL "scenario-$i" "worker produced nothing" 0 0 '?'; fi
done

# СТРУКТУРНЫЕ ПРОВЕРКИ ПАКЕТА (19.09.2026, docs/pain-and-gain-architecture.md, этап 0) — две строки того же вида, что
# у сценариев, потому что tools/land.sh читает каждую строку stdout как «PASS … errors: 0», и проверки БЛОКИРУЮТ
# посадку (решение оператора 7: советующая проверка через двадцать версий не соблюдается):
#   lint  — запрещённые написания (lint.py): сведённое к одному определению написание не возвращается инлайном;
#   graph — рёбра вопреки уровням (tools/depgraph.py по СКОМПИЛИРОВАННЫМ модулям этого ворктри, levels.txt): нового
#           ребра нет, список известных только убывает.
# Подробности нарушений идут в stderr. Чужие арены это не задевает: нарушение может внести только сессия этого
# пакета, а посадить его в main она сама не сможет. compare.py эти строки не читает — в них нет ` at t=`.
if [[ -x "$(command -v python3)" ]]; then
  python3 ./lint.py --gate
  python3 ../../depgraph.py ../../../build/js/packages/screeps-kotlin-arena-starter/kotlin/screeps-kotlin-arena-starter/season4/painandgain --levels ./levels.txt --gate
  # impure — ПЕРЕБОР ЗАМЫСЛОВ КОМАНДИРА ЧИСТ (20.09.2026, docs/pain-and-gain-architecture-2.md, этап 6.7): проба замысла считает в свою
  # запись, в мир попадает только выбранная раздача (v449). Бот считает записи в общее состояние за время перебора (поле `impure=`
  # строки `t=`, накопительно); в последней строке `t=` КАЖДОГО лога прогона оно обязано быть нулём. Лог без поля (сборка до v460) — не ошибка.
  python3 - "$TAG" <<'PY'
import glob, re, sys
tag = sys.argv[1]; total = 0; seen = 0; bad = []
for f in sorted(glob.glob('out/run-%s-*.log' % tag)):
    last = None
    for line in open(f, encoding='utf-8', errors='replace'):
        if line.startswith('t='):
            m = re.search(r' impure=(\d+)', line)
            if m: last = int(m.group(1))
    if last is None: continue
    seen += 1; total += last
    if last: bad.append(f)
for f in bad[:10]: sys.stderr.write('impure: %s\n' % f)
print('%-4s %-22s %-40s | errors: %d ' % ('PASS' if not total else 'FAIL', 'impure', 'writes during the commander search: %d over %d logs' % (total, seen), total))
PY
else
  printf '%-4s %-22s %-40s | errors: %s \n' FAIL "lint+graph" "python3 not found" 1
fi

# прогон быстрого цикла — не посадочный: строка без PASS останавливает tools/land.sh, если ONLY утёк в его окружение
[[ -n "${ONLY:-}" ]] && printf '%-4s %-22s %-40s | errors: %s \n' PART "only" "$N scenarios of the gate (ONLY) - not a landing run" 0

# ЭКСПОНИРОВАННОСТЬ ПРОГОНА (11.09.2026). Пустой дифф отчёта значит одно из двух — «правка мертва» или «стенд не
# экспонирован к тому, что она трогает», — и по самому отчёту их не различить. Цена этого различия измерена: командир
# правит боем 0,4 % тиков на стенде против 2,3 % живьём, постура отхода — 1 % против 17 %, и обе правки, которые
# стенд за ту сессию одобрил ошибочно, меняли ровно эти подсистемы.
# Печатается в STDERR намеренно: tools/land.sh требует, чтобы КАЖДАЯ строка stdout несла PASS и errors: 0, поэтому
# сводка в stdout сломала бы посадку всем аренам.
(( SKIPPED > 0 )) && print -r -- "exposure: ПРОПУЩЕНО ghost-строк: $SKIPPED (нет записей матчей — см. предупреждения выше)" >&2
[[ -x "$(command -v python3)" ]] && python3 ./exposure.py "$TAG" >&2

rm -rf "$PLANDIR"
