package board.core

/** Действие пользователя, которое можно отменить. Хранится только на своём устройстве. */
sealed class Action {
    data class Added(val element: Element) : Action()
    data class Removed(val elements: List<Element>) : Action()
    data class Modified(val before: Element, val after: Element) : Action()
    /** Частичное стирание: элементы заменены кусками. */
    data class Replaced(val removed: List<Element>, val added: List<Element>) : Action()
}

/** Стек отмены/повтора своих действий. Возвращает операции, которые нужно отправить. */
class UndoStack(private val limit: Int = 200) {
    private val undo = ArrayDeque<Action>()
    private val redo = ArrayDeque<Action>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun push(a: Action) {
        undo.addLast(a)
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    fun clear() {
        undo.clear(); redo.clear()
    }

    fun undo(): List<Op> {
        val a = undo.removeLastOrNull() ?: return emptyList()
        redo.addLast(a)
        return inverse(a)
    }

    fun redo(): List<Op> {
        val a = redo.removeLastOrNull() ?: return emptyList()
        undo.addLast(a)
        return forward(a)
    }

    private fun inverse(a: Action): List<Op> = when (a) {
        is Action.Added -> listOf(Op.Remove(listOf(a.element.id)))
        is Action.Removed -> a.elements.map { Op.Upsert(it) }
        is Action.Modified -> listOf(Op.Upsert(a.before))
        is Action.Replaced -> listOf(Op.Remove(a.added.map { it.id })) + a.removed.map { Op.Upsert(it) }
    }

    private fun forward(a: Action): List<Op> = when (a) {
        is Action.Added -> listOf(Op.Upsert(a.element))
        is Action.Removed -> listOf(Op.Remove(a.elements.map { it.id }))
        is Action.Modified -> listOf(Op.Upsert(a.after))
        is Action.Replaced -> listOf(Op.Remove(a.removed.map { it.id })) + a.added.map { Op.Upsert(it) }
    }
}
