package board.core

/**
 * Состояние одной доски. Не потокобезопасно: все обращения — из одного потока
 * (на Android это главный поток, см. Session).
 */
class Board(
    val id: String,
    var name: String,
    val createdAt: Long,
    var updatedAt: Long = createdAt,
) {
    /** Порядок вставки = порядок отрисовки (z-order). */
    val elements = LinkedHashMap<String, Element>()

    /** id удалённых элементов, чтобы при слиянии копий они не «воскресали». */
    val removed = HashSet<String>()

    /** Растёт при каждом изменении — удобно для перерисовки и автосохранения. */
    var revision: Long = 0
        private set

    operator fun get(id: String): Element? = elements[id]

    /** Применяет операцию. Возвращает true, если доска изменилась. */
    fun apply(op: Op): Boolean {
        val changed = when (op) {
            is Op.Upsert -> {
                val e = op.element
                removed.remove(e.id)
                // Тот же экземпляр штриха уже дописан через Append — заменять не нужно.
                if (elements[e.id] !== e) elements[e.id] = e
                true
            }
            is Op.Append -> {
                val s = elements[op.id] as? Stroke
                if (s != null && op.xy.isNotEmpty()) {
                    s.append(op.xy)
                    true
                } else false
            }
            is Op.Remove -> {
                var any = false
                for (id in op.ids) {
                    removed.add(id)
                    if (elements.remove(id) != null) any = true
                }
                any
            }
        }
        if (changed) touch()
        return changed
    }

    /**
     * Добавляет элементы другой копии этой же доски, которых здесь нет и которые
     * здесь не удалялись. Возвращает добавленные элементы.
     */
    fun mergeFrom(other: Board): List<Element> {
        if (other.id != id) return emptyList()
        removed.addAll(other.removed)
        for (rid in other.removed) elements.remove(rid)
        val added = ArrayList<Element>()
        for ((eid, e) in other.elements) {
            if (eid !in elements && eid !in removed) {
                elements[eid] = e
                added.add(e)
            }
        }
        if (added.isNotEmpty() || other.removed.isNotEmpty()) touch()
        return added
    }

    fun info() = BoardInfo(id, name, createdAt, updatedAt, elements.size)

    /** Габариты всего содержимого: [minX, minY, maxX, maxY] или null, если пусто. */
    fun bounds(): FloatArray? {
        if (elements.isEmpty()) return null
        val r = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        val tmp = FloatArray(4)
        for (e in elements.values) {
            if (!Geometry.bounds(e, tmp)) continue
            r[0] = minOf(r[0], tmp[0]); r[1] = minOf(r[1], tmp[1])
            r[2] = maxOf(r[2], tmp[2]); r[3] = maxOf(r[3], tmp[3])
        }
        return if (r[0] > r[2]) null else r
    }

    private fun touch() {
        revision++
        updatedAt = System.currentTimeMillis()
    }

    companion object {
        fun create(name: String = defaultName()): Board {
            val now = System.currentTimeMillis()
            return Board(Ids.random(), name, now, now)
        }

        fun defaultName(time: Long = System.currentTimeMillis()): String {
            val fmt = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("ru"))
            return "Доска " + fmt.format(java.util.Date(time))
        }
    }
}
