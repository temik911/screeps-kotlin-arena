package season4.painandgain

import screeps.api.Creep
import screeps.api.getRange

/**
 * ИНТЕНТЫ ВМЕСТО ВЫЗОВОВ (v238, этап 3 переработки; план — docs/pain-and-gain-rework.md).
 *
 * До v238 удар, выстрел и лечение вызывались прямо из кода решений (`strike`, `shoot`, `healAndShoot`, бегун в
 * `runRunners`), и одному крипу за тик могли достаться два `attack` с разными целями — движок брал последний, а код об
 * этом не знал. Теперь решения записывают намерение в три слота крипа: контактный (attack | heal), дальний
 * (rangedAttack | rangedMassAttack | rangedHeal) и ход (его по-прежнему держит TrafficManager), — а вызовы игрового
 * API делаются один раз в конце тика, из [run]. Семейства исключают друг друга по построению: второй интент того же
 * семейства перезаписывает первый и считается (`ovw=` в строке `t=`). Движок и стенд делают ровно то же — последний
 * вызов побеждает, — поэтому поведение тождественно v237, а перезапись стала видимой. Правила совместимости пока
 * только считают (см. [Arbiter]); снятие проигравшего предложения — этап 5.
 */
internal object Executor {
    private class Contact(val creep: Creep, val heal: Boolean, val target: Creep)
    private class Ranged(val creep: Creep, val kind: Char, val target: Creep?)   // 'a' rangedAttack, 'm' mass, 'h' rangedHeal
    private val contact = LinkedHashMap<String, Contact>()
    private val ranged = LinkedHashMap<String, Ranged>()
    /** Крипы в порядке ПЕРВОГО интента любого семейства: в этом порядке вызовы уходят в API. Стенд копит интенты по
     *  крипу в порядке первого вызова и в том же порядке разрешает конфликты движения, поэтому порядок — часть
     *  тождества с эталоном v235; движку он безразличен. */
    private val order = ArrayList<Creep>()

    /** Пара прибора: перезаписей контактного / дальнего слота за матч (второй интент того же семейства одному крипу). */
    var ovwContact = 0
    var ovwRanged = 0

    fun clear() { contact.clear(); ranged.clear(); order.clear() }
    private fun seen(c: Creep) { if (c.id !in contact && c.id !in ranged) order.add(c) }

    fun attack(c: Creep, t: Creep) { seen(c); if (contact.put(c.id, Contact(c, false, t)) != null) ovwContact++ }
    fun heal(c: Creep, t: Creep) { seen(c); if (contact.put(c.id, Contact(c, true, t)) != null) ovwContact++ }
    fun rangedAttack(c: Creep, t: Creep) { seen(c); if (ranged.put(c.id, Ranged(c, 'a', t)) != null) ovwRanged++ }
    fun rangedMassAttack(c: Creep) { seen(c); if (ranged.put(c.id, Ranged(c, 'm', null)) != null) ovwRanged++ }
    fun rangedHeal(c: Creep, t: Creep) { seen(c); if (ranged.put(c.id, Ranged(c, 'h', t)) != null) ovwRanged++ }

    /** Интентов, чья цель вне досягаемости с ТЕКУЩЕЙ клетки (движок такой отвергнет): контакт дальше 1, дальний дальше [rangedReach]. */
    fun unreachable(rangedReach: Int): Int {
        var n = 0
        for (i in contact.values) if (getRange(i.creep, i.target) > 1) n++
        for (i in ranged.values) if (i.target != null && getRange(i.creep, i.target) > rangedReach) n++
        return n
    }

    /** Единственное место, где боевой API вызывается: по крипам в порядке первого интента, контактный слот, затем дальний. */
    fun run() {
        for (c in order) {
            contact[c.id]?.let { if (it.heal) c.heal(it.target) else c.attack(it.target) }
            ranged[c.id]?.let {
                when (it.kind) {
                    'a' -> c.rangedAttack(it.target!!)
                    'm' -> c.rangedMassAttack()
                    else -> c.rangedHeal(it.target!!)
                }
            }
        }
    }
}
