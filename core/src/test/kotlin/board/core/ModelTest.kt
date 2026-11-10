package board.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {
    private fun sampleBoard(): Board {
        val b = Board.create("Тест")
        b.apply(Op.Upsert(Stroke("s1", 0xFF000000.toInt(), 4f, floatArrayOf(0f, 0f, 10f, 10f))))
        b.apply(Op.Append("s1", floatArrayOf(20f, 5f, 30f, 0f)))
        b.apply(Op.Upsert(Shape("r1", 0xFFFF0000.toInt(), 3f, ShapeKind.RECT, 100f, 100f, 200f, 150f)))
        b.apply(Op.Upsert(TextEl("t1", 0xFF0000FF.toInt(), 32f, 50f, 300f, "Привет\nмир")))
        b.apply(Op.Remove(listOf("gone")))
        return b
    }

    @Test
    fun boardRoundTrip() {
        val b = sampleBoard()
        val copy = Codec.decodeBoard(Codec.encodeBoard(b))
        assertEquals(b.id, copy.id)
        assertEquals(b.name, copy.name)
        assertEquals(b.elements.keys.toList(), copy.elements.keys.toList())
        assertEquals(b.elements["s1"], copy.elements["s1"])
        assertEquals(4, (copy.elements["s1"] as Stroke).pointCount)
        assertEquals(b.elements["r1"], copy.elements["r1"])
        assertEquals(b.elements["t1"], copy.elements["t1"])
        assertEquals(setOf("gone"), copy.removed)
    }

    @Test
    fun opRoundTrip() {
        val ops = listOf(
            Op.Upsert(Shape("a", 1, 2f, ShapeKind.ARROW, 1f, 2f, 3f, 4f)),
            Op.Remove(listOf("x", "y")),
        )
        for (op in ops) assertEquals(op, Codec.decodeOp(Codec.encodeOp(op)))
        val ap = Codec.decodeOp(Codec.encodeOp(Op.Append("s", floatArrayOf(1f, 2f)))) as Op.Append
        assertEquals("s", ap.id)
        assertTrue(ap.xy.contentEquals(floatArrayOf(1f, 2f)))
    }

    @Test
    fun strokeGrowsBeyondInitialCapacity() {
        val s = Stroke("s", 0, 1f)
        repeat(1000) { s.append(floatArrayOf(it.toFloat(), it.toFloat())) }
        assertEquals(1000, s.pointCount)
        assertEquals(999f, s.x(999))
    }

    @Test
    fun mergeRespectsTombstones() {
        val host = sampleBoard()
        val client = Codec.decodeBoard(Codec.encodeBoard(host))
        // Клиент без связи нарисовал новый штрих и стёр прямоугольник
        client.apply(Op.Upsert(Stroke("s2", 0, 2f, floatArrayOf(1f, 1f))))
        client.apply(Op.Remove(listOf("r1")))
        // Хозяин тем временем стёр текст
        host.apply(Op.Remove(listOf("t1")))

        val added = host.mergeFrom(client)
        assertEquals(listOf("s2"), added.map { it.id })
        assertNull(host["t1"], "стёртый у хозяина текст не должен вернуться")
        assertNull(host["r1"], "стёртый клиентом прямоугольник должен исчезнуть")
        assertTrue(host["s2"] != null)
    }

    @Test
    fun mergeIgnoresOtherBoards() {
        val a = sampleBoard()
        val b = Board.create()
        b.apply(Op.Upsert(Stroke("z", 0, 1f, floatArrayOf(0f, 0f))))
        assertTrue(a.mergeFrom(b).isEmpty())
        assertNull(a["z"])
    }

    @Test
    fun undoRedo() {
        val board = Board.create()
        val u = UndoStack()
        val s = Stroke("s", 0, 2f, floatArrayOf(0f, 0f, 5f, 5f))
        board.apply(Op.Upsert(s)); u.push(Action.Added(s))
        val t1 = TextEl("t", 0, 20f, 0f, 0f, "a")
        val t2 = t1.copy(text = "ab")
        board.apply(Op.Upsert(t1)); u.push(Action.Added(t1))
        board.apply(Op.Upsert(t2)); u.push(Action.Modified(t1, t2))
        board.apply(Op.Remove(listOf("s"))); u.push(Action.Removed(listOf(s)))

        u.undo().forEach { board.apply(it) } // вернуть штрих
        assertTrue(board["s"] != null)
        u.undo().forEach { board.apply(it) } // текст обратно в "a"
        assertEquals("a", (board["t"] as TextEl).text)
        u.redo().forEach { board.apply(it) }
        assertEquals("ab", (board["t"] as TextEl).text)
        u.undo().forEach { board.apply(it) }
        u.undo().forEach { board.apply(it) } // убрать текст
        assertNull(board["t"])
        u.undo().forEach { board.apply(it) } // убрать штрих
        assertNull(board["s"])
        assertFalse(u.canUndo)
    }

    @Test
    fun eraserHitTest() {
        val s = Stroke("s", 0, 4f, floatArrayOf(0f, 0f, 100f, 0f))
        assertTrue(Geometry.hits(s, 50f, 5f, 4f))
        assertFalse(Geometry.hits(s, 50f, 20f, 4f))
        val r = Shape("r", 0, 2f, ShapeKind.RECT, 0f, 0f, 100f, 100f)
        assertTrue(Geometry.hits(r, 100f, 50f, 3f))
        assertFalse(Geometry.hits(r, 50f, 50f, 3f), "внутри пустого прямоугольника — не попадание")
        val e = Shape("e", 0, 2f, ShapeKind.ELLIPSE, 0f, 0f, 100f, 50f)
        assertTrue(Geometry.hits(e, 100f, 25f, 3f))
        assertFalse(Geometry.hits(e, 50f, 25f, 3f))
    }

    @Test
    fun fileStore() {
        val dir = kotlin.io.path.createTempDirectory("boards").toFile()
        val store = FileBoardStore(dir)
        val b = sampleBoard()
        store.save(b.id, Codec.encodeBoard(b))
        store.lastBoardId = b.id
        val again = FileBoardStore(dir)
        assertEquals(b.id, again.lastBoardId)
        assertEquals(3, again.load(b.id)!!.elements.size)
        assertEquals(listOf(b.id), again.list().map { it.id })
        again.delete(b.id)
        assertTrue(again.list().isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun eraserCutsLineInTheMiddle() {
        // Горизонтальная линия 0..100, ластик радиусом 10 в точке (50, 0)
        val pieces = Eraser.cut(floatArrayOf(0f, 0f, 100f, 0f), 50f, 0f, 10f)!!
        assertEquals(2, pieces.size)
        fun near(a: FloatArray, b: FloatArray) = a.size == b.size && a.indices.all { kotlin.math.abs(a[it] - b[it]) < 0.01f }
        assertTrue(near(pieces[0], floatArrayOf(0f, 0f, 40f, 0f)), pieces[0].toList().toString())
        assertTrue(near(pieces[1], floatArrayOf(60f, 0f, 100f, 0f)), pieces[1].toList().toString())
    }

    @Test
    fun eraserTrimsEndAndMissesFarAway() {
        val end = Eraser.cut(floatArrayOf(0f, 0f, 50f, 0f, 100f, 0f), 100f, 0f, 20f)!!
        assertEquals(1, end.size)
        assertEquals(80f, end[0][end[0].size - 2], 0.01f)
        assertEquals(null, Eraser.cut(floatArrayOf(0f, 0f, 100f, 0f), 50f, 50f, 10f))
        // Стёрто всё
        assertEquals(0, Eraser.cut(floatArrayOf(0f, 0f, 5f, 0f), 2f, 0f, 10f)!!.size)
    }

    @Test
    fun eraserCutsRectangleIntoOpenPolyline() {
        val rect = Shape("r", 0, 2f, ShapeKind.RECT, 0f, 0f, 100f, 100f)
        val poly = Eraser.polylines(rect)!!
        assertEquals(1, poly.size)
        val pieces = Eraser.cut(poly[0], 100f, 50f, 10f)!!
        assertEquals(2, pieces.size)
        val total = pieces.sumOf { Eraser.length(it).toDouble() }
        assertEquals(380.0, total, 0.5) // периметр 400 минус вырезанные 20
        assertEquals(null, Eraser.polylines(TextEl("t", 0, 20f, 0f, 0f, "abc")))
    }

    @Test
    fun undoReplaced() {
        val board = Board.create()
        val u = UndoStack()
        val s = Stroke("s", 0, 2f, floatArrayOf(0f, 0f, 100f, 0f))
        board.apply(Op.Upsert(s))
        val p1 = Stroke("p1", 0, 2f, floatArrayOf(0f, 0f, 40f, 0f))
        val p2 = Stroke("p2", 0, 2f, floatArrayOf(60f, 0f, 100f, 0f))
        listOf(Op.Remove(listOf("s")), Op.Upsert(p1), Op.Upsert(p2)).forEach { board.apply(it) }
        u.push(Action.Replaced(listOf(s), listOf(p1, p2)))
        u.undo().forEach { board.apply(it) }
        assertEquals(setOf("s"), board.elements.keys)
        u.redo().forEach { board.apply(it) }
        assertEquals(setOf("p1", "p2"), board.elements.keys)
    }
}
