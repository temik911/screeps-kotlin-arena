@file:JsModule("arena/season_4/escort_run/basic/prototypes")
@file:JsNonModule

package screeps.api.season4

import screeps.api.Creep

/**
 * Эскорт-крип арены season4 Escort Run (basic): «крип, присутствующий на карте с начала матча, которого
 * нужно довести до цели» (client typings, `arena/season_4/escort_run/basic/prototypes`). Обычный [Creep]
 * без собственных полей: тело и хиты читаются как у любого крипа. Побеждает тот, чей эскорт первым встаёт на
 * свой флаг, — или тот, кто убьёт эскорт противника; лимит 2000 тиков, потом ничья.
 *
 * Бот `season4/escortrun` этот биндинг НЕ импортирует: несуществующий в рантайме модуль валит загрузку всего
 * бандла ещё до первого `loop()`, а прототип надёжно узнаётся по имени конструктора (`constructor.name ==
 * "EscortCreep"`) плюс по факту «крип, живой на первом тике» (родить кого-то за один тик нельзя). Биндинг
 * оставлен как документация API и на случай, когда прототип понадобится как тип.
 */
external class EscortCreep : Creep
