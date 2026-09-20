package season4.painandgain

import screeps.api.getCpuTime

// ЧАСЫ ТИКА (v446, план архитектуры, этапы 5–6) — уровень 1. `cpuMs` читают предохранители CPU всех стадий, `cpuMark` отмечает фазы
// для прибора `cpu t=`; пока они жили в Instruments.kt, каждая стадия импортировала файл приборов ради часов. Печать фаз
// (`cpuSummary`) осталась в Instruments. getCpuTime() — наносекунды с начала тика (на стенде — по hrtime, при NOCLOCK=1 — ноль).

internal fun cpuMs(): Double = try { getCpuTime() / 1_000_000.0 } catch (e: Throwable) { 0.0 }

internal fun cpuMark(phase: String) { cpuPhases.add(phase to cpuMs()) }

/** ОКНО ОБЩЕЙ ТРАССЫ РЕШЕНИЯ (v458, второй шаг архитектуры, этап 5): строка `trace t=` на каждого бойца печатается только в окне
 *  тиков. На стенде окно задаёт `TRACE=t0-t1` (`run.mjs` кладёт границы в `globalThis.PG_TRACE`), живьём — две константы ниже
 *  (пустое окно: строка на бойца в тик — это четырнадцать строк лога, весь матч ими не пишут). */
internal const val TRACE_FROM = 0
internal const val TRACE_TO = -1
private val traceWindow: IntArray by lazy {
    val w: dynamic = js("(typeof globalThis !== 'undefined' && globalThis.PG_TRACE) ? globalThis.PG_TRACE : null")
    if (w == null) intArrayOf(TRACE_FROM, TRACE_TO) else intArrayOf(w[0] as Int, w[1] as Int)
}
internal fun traceNow(tick: Int): Boolean = TRACE_WHY && DEBUG_LOG && tick >= traceWindow[0] && tick <= traceWindow[1]

/** Отметки фаз тика сняты прибором `cpu t=` — список пуст к следующему тику. Чистит владелец: у `cpuPhases` один файл-писатель. */
internal fun cpuPhasesDone() { cpuPhases.clear() }

// ==================== приборы стадии, бывшие членами object PainAndGain (v455, второй шаг архитектуры, этап 2) ====================

internal val cpuPhases = ArrayList<Pair<String, Double>>()
