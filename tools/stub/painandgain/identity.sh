#!/bin/zsh
# Оракул тождества механической правки одной командой — docs/pain-and-gain-architecture.md, раздел 6.1:
#   сборка -> regress.sh land -> compare.py «без изменений» по всем строкам -> logdiff.py 0 расхождений
#   -> verdicts.py check 0 пропаж (по каждому изменённому файлу пакета, против HEAD) -> lint и graph PASS.
# Шаг, который тождество не проходит, содержит ошибку переноса — он не «почти готов».
#
#   zsh tools/stub/painandgain/identity.sh <эталон>          # эталон = runs/gate_<эталон>.txt + runs/gate_<эталон>/
#   NOCLOCK=1 zsh tools/stub/painandgain/identity.sh 440      # прогон без часов стенда — см. ниже
#   zsh tools/stub/painandgain/identity.sh <эталон> --rows rung.kite,step.flee   # БЫСТРЫЙ ЦИКЛ, см. ниже
#
# БЫСТРЫЙ ЦИКЛ (20.09.2026, docs/pain-and-gain-architecture-2.md, этап 0). `--rows <строки таблиц>` гоняет только те
# сценарии гейта, где названные строки ВЫИГРЫВАЮТ (по прибору `reach` в логах эталона: `gategap.py --rows … --list`), и
# сверяет их с теми же логами эталона — секунды вместо полутора минут. Это цикл МЕЖДУ коммитами: то, что сажается, и
# последний коммит этапа проходят полный прогон. Вердикт быстрого цикла — «тождество на N сценариях из M», и он так и
# называется; посадку такой прогон не пройдёт (regress.sh печатает строку PART, в которой нет PASS).
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
rows=""; [[ "${2:-}" == --rows ]] && rows=${3:?--rows: строки таблиц через запятую, например rung.kite,step.flee}
if [[ -n "$rows" ]]; then
  # сценарии — по reach ЭТАЛОНА (у нового прогона его ещё нет); эталон сужается до них же: отчёт — по меткам, логи — копией
  python3 "$HERE/gategap.py" --rows "$rows" --list --logs "$refdir" > runs/identity.only.txt || { cat runs/identity.only.txt; exit 2; }
  n_only=$(grep -c . runs/identity.only.txt)
  (( n_only > 0 )) || { echo "identity: строки $rows не выигрывают ни в одном сценарии эталона — быстрый цикл нечем гонять, нужен полный прогон"; exit 2; }
  rm -rf runs/identity.rows.ref; mkdir -p runs/identity.rows.ref
  : > runs/identity.rows.ref.txt
  while IFS= read -r lb; do
    grep -F -- " $lb " "$ref" | awk -v lb="$lb" '$2 == lb' >> runs/identity.rows.ref.txt
    cp "$refdir"/run-land-"$lb"-* runs/identity.rows.ref/
  done < runs/identity.only.txt
  n_all=$(grep -c ' at t=' "$ref")
  ref=runs/identity.rows.ref.txt; refdir=runs/identity.rows.ref
  export ONLY="$ROOT/runs/identity.only.txt"
  echo "identity: БЫСТРЫЙ ЦИКЛ — строки $rows выигрывают в $n_only сценариях эталона из $n_all"
fi
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
bad=$(grep -v '^PART ' runs/gate_new.txt | grep -vcE 'PASS.*errors: 0 ')
(( bad == 0 )) || { grep -v '^PART ' runs/gate_new.txt | grep -vE 'PASS.*errors: 0 '; cat runs/gate_new.stderr.txt | grep -v '^exposure' | head -40; fail "$bad строк гейта не PASS (сценарии, lint или graph)"; }
grep -q '^PASS lint ' runs/gate_new.txt && grep -q '^PASS graph ' runs/gate_new.txt || fail "в отчёте гейта нет строк lint / graph"

# условие детерминизма — первым
# строка предохранителя — `cpu t=<тик> guard: …`, и ищется она ПО НАЧАЛУ строки: простой `grep guard:` однажды насчитал 12 706
# «срабатываний» на имени конъюнкта `guard` в строке `whynot` (v457) при молчащих предохранителях
guards=$(cat "$HERE"/out/run-land-*.log | grep -cE '^cpu t=[0-9]+ guard:')
cuts=$(for f in "$HERE"/out/run-land-*.log; do grep -o ' srch=[0-9]*/' "$f" | tail -1; done | grep -vc ' srch=0/')
echo "identity: строк guard: $guards, логов с обрезкой перебора srch: $cuts"
(( guards == 0 && cuts == 0 )) || fail "предохранители CPU говорили — это не ошибка переноса, а загрузка машины или находка о CPU (раздел 6.1); повтори с NOCLOCK=1"

python3 "$HERE/compare.py" "$ref" runs/gate_new.txt > runs/identity.compare.txt; rc=$?
same=$(sed -nE 's/.*без изменений ([0-9]+).*/\1/p' runs/identity.compare.txt)
echo "identity: compare — без изменений ${same:-?} из $n_ref (строк в новом отчёте $n_new)"
(( rc == 0 )) && [[ "${same:-0}" == "$n_ref" && "$n_new" == "$n_ref" ]] || { cat runs/identity.compare.txt; fail "compare.py: не «без изменений» по всем строкам"; }

python3 "$HERE/logdiff.py" --old "$refdir" --new "$HERE/out" > runs/identity.logdiff.txt; rc=$?
head -1 runs/identity.logdiff.txt | sed 's/^/identity: logdiff — /'
# новое поле и новая строка прибора маскируются сами (logdiff.py) — и НАЗЫВАЮТСЯ здесь: молчаливой маски нет
grep -E '^новые (поля|строки)' runs/identity.logdiff.txt | sed 's/^/identity: logdiff — /'
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
if [[ -n "$rows" ]]; then
  echo "identity: ТОЖДЕСТВО НА $n_ref СЦЕНАРИЯХ ИЗ $n_all (быстрый цикл по строкам $rows) — compare без изменений $same/$n_ref, logdiff 0, verdicts 0, guard 0, lint и graph PASS; посадке нужен полный прогон"
else
  echo "identity: ТОЖДЕСТВО — compare без изменений $same/$n_ref, logdiff 0, verdicts 0, guard 0, lint и graph PASS"
fi
