#!/bin/zsh
# Full stub regression for the Spawn and Swamp bot against THIS worktree's build (the runners import
# ../../../build/js/...). Usage: zsh tools/stub/spawnandswamp/regress.sh [tag]
# Prints one line per scenario: outcome tick, errors, ghost-damage lines. Logs go to ./out/ (gitignored).
# Pass = every line says "ENEMY SPAWN DESTROYED" with "errors: 0"; tools/land.sh checks exactly that.
cd "$(dirname "$0")"
NODE=${NODE:-$(ls -d ~/.gradle/nodejs/node-*/bin/node 2>/dev/null | tail -1)}
if [[ ! -x "$NODE" ]]; then echo "regress: node not found under ~/.gradle/nodejs (run ./gradlew build once)"; exit 1; fi
TAG=${1:-cur}
mkdir -p out
report() { # $1 = label, $2 = log file
  local res tr ghost site
  res=$(grep -E 'SPAWN DESTROYED' "$2" | sed -E 's/\x1b\[[0-9;]*m//g' | head -1)
  tr=$(grep -E '^--- ticks run' "$2" | sed -E 's/\x1b\[[0-9;]*m//g' | sed -E 's/ my creeps:.*//')
  ghost=$(grep -c 'ghost' "$2")
  # чем кончилась домашняя башня: 'T' — стоит, 'site P/T' — брошенный недострой (энергия в никуда),
  # '-' — не начинали. Правило v44 (часы у площадки) читается именно здесь
  site=$(grep -oE 'mine=[^ ]*' "$2" | tail -1 | sed -E 's/^mine=//; s/^-\+site\([0-9,]+\)/site /; s/^T\(.*/T/')
  printf '%-18s %-32s | %s | ghost=%s | tower=%s\n' "$1" "${res:-survived (no spawn destroyed)}" "${tr:-(no summary line)}" "$ghost" "${site:--}"
}
# siege в этом списке НЕТ намеренно: его ИСХОД хаотичен — при том же боте смена периода подкрепления
# на один тик даёт 938, 1631 и 1685 (замерено 06.09.2026). Гейт на таком исходе — монетка; сам
# сценарий остаётся для ручных прогонов, а проверяется по нему РЕШЕНИЕ (siege6 ниже)
for m in none enemy swarm raider harass tower tower+enemy ball tower+healball towersite healball tower+hover rush camp stream tower+stream fortress pairs tower+pairs; do
  f=out/${TAG}_r2_${m//+/_}.txt
  "$NODE" --import ./register.mjs run2.mjs 2000 "$m" > "$f" 2>&1
  report "run2:$m" "$f"
done
for s in freeze rush stream17; do
  f=out/${TAG}_r3_${s}.txt
  "$NODE" --import ./register.mjs run3.mjs 2000 "$s" > "$f" 2>&1
  report "run3:$s" "$f"
done
# camped19 — фикстура правила лагеря: враг уже вплотную, притока нет, спавн живёт ~50 тиков. Проход —
# спавн СТРОИТ бойца из наличных 900, а не копит на полное тело до самой смерти (матч 19)
# siege6 — фикстура правила часов у башни (v44). Шестеро охотников у ворот роняют приток в ноль, и
# площадка становится недостроем; исход проигрывают ОБЕ версии, поэтому проверяется решение, а не
# исход: сколько энергии ушло в площадку, которую уже не достроить. Порог — не по вкусу: это ёмкость
# смотрителя (2 CARRY = 100), то есть груз, который был в пути в момент вердикта. v43 оставлял 649
f=out/${TAG}_r2_siege6.txt
SIEGE_HUNTERS=6 SIEGE_EVERY=60 "$NODE" --import ./register.mjs run2.mjs 2000 siege > "$f" 2>&1
last=$(grep -oE 'mine=[^ ]*' "$f" | tail -1)
errs=$(grep -cE 'loop error|exception' "$f")
lost=$(echo "$last" | sed -nE 's/^mine=-\+site\([0-9,]+\)([0-9]+)\/[0-9]+.*/\1/p')
if [[ "$last" == mine=T* || ( ${lost:-0} -le 100 && $errs -eq 0 ) ]]; then
  printf '%-18s %-32s | %s | ghost=0 | tower=%s\n' "run2:siege6" "PASS abandoned ${lost:-0} in the site" "--- ticks run: fixture errors: $errs " "${last#mine=}"
else
  printf '%-18s %-32s | %s | ghost=0 | tower=%s\n' "run2:siege6" "FAIL ${lost:-0} energy left in a dead site" "--- ticks run: fixture errors: $errs " "${last#mine=}"
fi
f=out/${TAG}_r3_camped19.txt
"$NODE" --import ./register.mjs run3.mjs 200 camped19 > "$f" 2>&1
built=$(grep -cE '^spawn: (fighter|guard) parts' "$f")
errs=$(grep -cE 'loop error|exception' "$f")
if [[ $built -ge 1 && $errs -eq 0 ]]; then
  printf '%-18s %-32s | %s | ghost=0\n' "run3:camped19" "PASS built $built body under fire" "--- ticks run: fixture errors: 0 "
else
  printf '%-18s %-32s | %s | ghost=0\n' "run3:camped19" "FAIL built nothing while dying" "--- ticks run: fixture errors: $errs "
fi
