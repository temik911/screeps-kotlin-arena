#!/bin/zsh
# Оракул тождества механической правки одной командой — docs/pain-and-gain-architecture.md, раздел 6.1:
#   сборка -> regress.sh land -> compare.py «без изменений» по всем строкам -> logdiff.py 0 расхождений
#   -> verdicts.py check 0 пропаж (по каждому изменённому файлу пакета, против HEAD) -> lint и graph PASS.
# Шаг, который тождество не проходит, содержит ошибку переноса — он не «почти готов».
#
#   zsh tools/stub/painandgain/identity.sh <эталон>          # эталон = runs/gate_<эталон>.txt + runs/gate_<эталон>/
#   NOCLOCK=1 zsh tools/stub/painandgain/identity.sh 440      # прогон без часов стенда — см. ниже
#
# У ДЕТЕРМИНИЗМА СТЕНДА ЕСТЬ УСЛОВИЕ: предохранители CPU молчат. Они читают настоящее время, и на загруженной машине
# тик может перевалить за CPU_GUARD_MS — тогда бот законно ведёт себя иначе, и расхождение логов НЕ ошибка переноса.
# Поэтому число строк `guard:` и обрезок перебора `srch=` проверяется в каждом прогоне ПЕРВЫМ, до сравнения. У третьей
# ветви, читающей время (ограничение поля потока в World.kt), прибора нет вовсе — если logdiff разошёлся, а guard и srch
# молчат, прогон повторяется с NOCLOCK=1: часы стенда выключены, ни одна из этих ветвей сработать не может, а логи
# такого прогона совпадают с эталоном байт в байт (кроме строк `cpu t=`, которые logdiff и так отбрасывает).
# Правится из ворктри арены; запускается из его корня или откуда угодно.
set -u
SELF=${0:A}; HERE=${SELF:h}; ROOT=${HERE:h:h:h}
cd "$ROOT"
base=${1:?эталон: имя N для runs/gate_N.txt и runs/gate_N/}
ref="runs/gate_$base.txt"; refdir="runs/gate_$base"
[[ -f "$ref" && -d "$refdir" ]] || { echo "identity: нет эталона $ref + $refdir/"; exit 2; }
pkg=starter/src/jsMain/kotlin/season4/painandgain
fail() { echo "identity: FAIL — $1"; exit 1; }

echo "identity: сборка"
./gradlew build -q > runs/identity.build.txt 2>&1 || { tail -30 runs/identity.build.txt; fail "сборка"; }

echo "identity: гейт (regress.sh land${NOCLOCK:+, NOCLOCK=1})"
# логи прежних прогонов с тем же тегом — долой: строка, ушедшая из гейта, оставляет лог, который никто не перепишет,
# и счёт guard / srch ниже читал бы чужой матч (out/ в .gitignore, каждый лог воспроизводим своим сценарием)
rm -f "$HERE"/out/run-land-*.log
zsh "$HERE/regress.sh" land > runs/gate_new.txt 2> runs/gate_new.stderr.txt
n_ref=$(grep -c ' at t=' "$ref"); n_new=$(grep -c ' at t=' runs/gate_new.txt)
bad=$(grep -vcE 'PASS.*errors: 0 ' runs/gate_new.txt)
(( bad == 0 )) || { grep -vE 'PASS.*errors: 0 ' runs/gate_new.txt; cat runs/gate_new.stderr.txt | grep -v '^exposure' | head -40; fail "$bad строк гейта не PASS (сценарии, lint или graph)"; }
grep -q '^PASS lint ' runs/gate_new.txt && grep -q '^PASS graph ' runs/gate_new.txt || fail "в отчёте гейта нет строк lint / graph"

# условие детерминизма — первым
guards=$(cat "$HERE"/out/run-land-*.log | grep -c 'guard:')
cuts=$(for f in "$HERE"/out/run-land-*.log; do grep -o ' srch=[0-9]*/' "$f" | tail -1; done | grep -vc ' srch=0/')
echo "identity: строк guard: $guards, логов с обрезкой перебора srch: $cuts"
(( guards == 0 && cuts == 0 )) || fail "предохранители CPU говорили — это не ошибка переноса, а загрузка машины или находка о CPU (раздел 6.1); повтори с NOCLOCK=1"

python3 "$HERE/compare.py" "$ref" runs/gate_new.txt > runs/identity.compare.txt; rc=$?
same=$(sed -nE 's/.*без изменений ([0-9]+).*/\1/p' runs/identity.compare.txt)
echo "identity: compare — без изменений ${same:-?} из $n_ref (строк в новом отчёте $n_new)"
(( rc == 0 )) && [[ "${same:-0}" == "$n_ref" && "$n_new" == "$n_ref" ]] || { cat runs/identity.compare.txt; fail "compare.py: не «без изменений» по всем строкам"; }

python3 "$HERE/logdiff.py" --old "$refdir" --new "$HERE/out" > runs/identity.logdiff.txt; rc=$?
head -1 runs/identity.logdiff.txt | sed 's/^/identity: logdiff — /'
(( rc == 0 )) || { head -40 runs/identity.logdiff.txt; fail "logdiff.py: логи разошлись с эталоном"; }

# вердикты: всё, что вычеркнуто из комментариев, обязано найтись в docs или в пакете (переезд — не удаление)
miss=0
for f in $(git diff --name-only HEAD -- "$pkg"; git ls-files --others --exclude-standard -- "$pkg"); do
  [[ -f "$f" ]] || continue
  git cat-file -e "HEAD:$f" 2>/dev/null || continue
  out=$(python3 "$HERE/verdicts.py" check --base HEAD --code "$f"); rc=$?
  (( rc == 0 )) || { echo "--- $f"; print -r -- "$out"; miss=$((miss + 1)); }
done
for f in $(git diff --name-only --diff-filter=D HEAD -- "$pkg"); do
  out=$(python3 "$HERE/verdicts.py" check --base HEAD --code "$f"); rc=$?
  (( rc == 0 )) || { echo "--- $f (удалён)"; print -r -- "$out"; miss=$((miss + 1)); }
done
echo "identity: verdicts — файлов с пропажами $miss"
(( miss == 0 )) || fail "verdicts.py check: вычеркнутое знание не найдено в docs"

python3 "$HERE/cputrace.py" "$refdir" --against "$HERE/out" | tail -1 | sed 's/^/identity: след CPU стенда — /'
echo "identity: ТОЖДЕСТВО — compare без изменений $same/$n_ref, logdiff 0, verdicts 0, guard 0, lint и graph PASS"
