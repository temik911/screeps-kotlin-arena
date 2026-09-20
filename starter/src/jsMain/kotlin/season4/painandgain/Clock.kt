package season4.painandgain

import screeps.api.getCpuTime

// ЧАСЫ ТИКА (v446, план архитектуры, этапы 5–6) — уровень 1. `cpuMs` читают предохранители CPU всех стадий, `cpuMark` отмечает фазы
// для прибора `cpu t=`; пока они жили в Instruments.kt, каждая стадия импортировала файл приборов ради часов. Печать фаз
// (`cpuSummary`) осталась в Instruments. getCpuTime() — наносекунды с начала тика (на стенде — по hrtime, при NOCLOCK=1 — ноль).

internal fun cpuMs(): Double = try { getCpuTime() / 1_000_000.0 } catch (e: Throwable) { 0.0 }

internal fun cpuMark(phase: String) { cpuPhases.add(phase to cpuMs()) }

/** Отметки фаз тика сняты прибором `cpu t=` — список пуст к следующему тику. Чистит владелец: у `cpuPhases` один файл-писатель. */
internal fun cpuPhasesDone() { cpuPhases.clear() }

// ==================== приборы стадии, бывшие членами object PainAndGain (v455, второй шаг архитектуры, этап 2) ====================

internal val cpuPhases = ArrayList<Pair<String, Double>>()
