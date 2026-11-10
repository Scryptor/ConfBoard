package board.core

import java.security.SecureRandom

enum class ShapeKind { LINE, ARROW, RECT, ELLIPSE }

/** Элемент доски. Координаты — в «мировых» единицах доски, не в пикселях экрана. */
sealed class Element {
    abstract val id: String
    abstract val color: Int
    abstract val width: Float
}

/**
 * Рукописный штрих. Точки хранятся парами (x, y) в растущем буфере,
 * чтобы дописывать их в процессе рисования без копирования всего массива.
 */
class Stroke(
    override val id: String,
    override val color: Int,
    override val width: Float,
    initial: FloatArray = FloatArray(0),
) : Element() {
    var data: FloatArray = initial.copyOf(maxOf(16, initial.size))
        private set

    /** Количество float-значений (точек * 2). */
    var size: Int = initial.size
        private set

    val pointCount: Int get() = size / 2

    fun append(xy: FloatArray, offset: Int = 0, length: Int = xy.size) {
        if (size + length > data.size) {
            data = data.copyOf(maxOf(data.size * 2, size + length))
        }
        System.arraycopy(xy, offset, data, size, length)
        size += length
    }

    fun x(i: Int) = data[i * 2]
    fun y(i: Int) = data[i * 2 + 1]

    fun points(): FloatArray = data.copyOf(size)

    fun copy(): Stroke = Stroke(id, color, width, points())

    override fun equals(other: Any?): Boolean =
        other is Stroke && other.id == id && other.color == color && other.width == width &&
            other.size == size && (0 until size).all { other.data[it] == data[it] }

    override fun hashCode(): Int = id.hashCode()

    override fun toString() = "Stroke($id, points=$pointCount)"
}

data class Shape(
    override val id: String,
    override val color: Int,
    override val width: Float,
    val kind: ShapeKind,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
) : Element()

/** Текст. (x, y) — левый верхний угол, width — размер шрифта. */
data class TextEl(
    override val id: String,
    override val color: Int,
    override val width: Float,
    val x: Float,
    val y: Float,
    val text: String,
) : Element() {
    val fontSize: Float get() = width
}

/** Операция над доской. Все изменения проходят только через операции. */
sealed class Op {
    /** Добавить или заменить элемент целиком. */
    data class Upsert(val element: Element) : Op()

    /** Дописать точки в штрих (живое рисование). */
    class Append(val id: String, val xy: FloatArray) : Op()

    /** Удалить элементы. */
    data class Remove(val ids: List<String>) : Op()
}

data class Member(val deviceId: String, val name: String, val isTv: Boolean)

data class BoardInfo(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val elementCount: Int,
)

object Ids {
    private val rnd = SecureRandom()
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

    fun random(len: Int = 12): String {
        val sb = StringBuilder(len)
        repeat(len) { sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }
}

/** Простой лог ядра; на Android перенаправляется в logcat. */
object CoreLog {
    @Volatile var sink: (String) -> Unit = { println(it) }
    fun d(msg: String) = runCatching { sink(msg) }.let { }
    fun e(msg: String, t: Throwable) = d("$msg: ${t.javaClass.simpleName}: ${t.message}")
}
