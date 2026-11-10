package board.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import board.core.Action
import board.core.Board
import board.core.BoardInfo
import board.core.Element
import board.core.Eraser
import board.core.Geometry
import board.core.Ids
import board.core.Op
import board.core.Session
import board.core.Shape
import board.core.ShapeKind
import board.core.Stroke
import board.core.TextEl
import board.core.UndoStack
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Tool { PEN, LINE, ARROW, RECT, ELLIPSE, TEXT, ERASER }

val PALETTE = listOf(
    0xFF1F1F1F.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
    0xFF00897B.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFF6D4C41.toInt(), 0xFFFFFFFF.toInt(),
)
val WIDTHS = listOf(2f, 4f, 8f, 16f)
val FONT_SIZES = listOf(20f, 32f, 48f, 72f)
/** Радиусы ластика, dp. */
val ERASER_SIZES = listOf(8f, 14f, 24f, 40f)

/**
 * Состояние экрана и логика инструментов. Все вызовы — из главного потока,
 * там же работает Session, поэтому синхронизация не нужна.
 */
class BoardController(
    val session: Session,
    /** Настоящий телевизор: режим не переключается. */
    val realTv: Boolean,
    startAsTv: Boolean,
    private val density: Float,
    private val onModeChanged: (Boolean) -> Unit = {},
) : Session.Listener {

    /** Показывать ли экран «как ТВ» (на планшете включается вручную). */
    var isTv by mutableStateOf(realTv || startAsTv)
        private set

    /** Ведущий: его вид повторяют все ТВ. null — ТВ показывают всю доску. */
    var presenterId by mutableStateOf(session.presenterId)
        private set
    var presenterViewport by mutableStateOf(session.presenterViewport)
        private set
    val isPresenter get() = presenterId != null && presenterId == session.me.deviceId
    val presenterName: String?
        get() = presenterId?.let { id -> status.members.firstOrNull { it.deviceId == id }?.name }

    // --- то, что наблюдает Compose
    var revision by mutableIntStateOf(0)
        private set
    var status by mutableStateOf(session.status())
        private set
    var boardList by mutableStateOf<List<BoardInfo>?>(null)
    var tool by mutableStateOf(Tool.PEN)
    var color by mutableIntStateOf(PALETTE[0])
    var widthIndex by mutableIntStateOf(1)
    var eraserSizeIndex by mutableIntStateOf(1)
    /** true — ластик удаляет объект целиком, false — стирает только задетую часть. */
    var eraserWhole by mutableStateOf(false)
    val eraserRadiusDp get() = ERASER_SIZES[eraserSizeIndex]

    /** Список досок открыт (при запуске планшета — сразу). */
    var boardsOpen by mutableStateOf(!realTv && !startAsTv)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var editingText by mutableStateOf<TextEl?>(null)
        private set
    var eraserAt by mutableStateOf<Offset?>(null)
        private set

    // --- вид (у каждого устройства свой)
    var scale by mutableFloatStateOf(density)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var viewSize = IntSize.Zero
        set(v) {
            val first = field == IntSize.Zero && v != IntSize.Zero
            field = v
            if (first) fitToContent()
        }

    val board: Board get() = session.board
    private val undo = UndoStack()

    // ------------------------------------------------------------ Session.Listener

    override fun onBoardReplaced(sameBoard: Boolean) {
        if (!sameBoard) {
            undo.clear()
            editingText = null
            textBefore = null
            fitToContent()
        }
        if (editingText != null && board[editingText!!.id] == null && sameBoard) {
            // текст ещё редактируется — вернём его на доску
            session.submit(Op.Upsert(editingText!!))
        }
        updateUndo()
        revision++
    }

    override fun onBoardChanged() {
        revision++
    }

    override fun onStatus(status: Session.Status) {
        this.status = status
    }

    override fun onBoardList(list: List<BoardInfo>) {
        boardList = list
    }

    override fun onPresenter(presenterId: String?) {
        this.presenterId = presenterId
        presenterViewport = null
    }

    override fun onViewport(rect: FloatArray) {
        presenterViewport = rect
    }

    // ------------------------------------------------------------ ведущий и режим ТВ

    /** «ТВ показывает мой экран» — включить или выключить. */
    fun togglePresenting() {
        session.setPresenting(!isPresenter)
        presenterId = session.presenterId
    }

    /** Видимая на экране область доски: [left, top, right, bottom]. */
    fun viewRect(): FloatArray? {
        if (viewSize == IntSize.Zero) return null
        val l = -offset.x / scale
        val t = -offset.y / scale
        return floatArrayOf(l, t, l + viewSize.width / scale, t + viewSize.height / scale)
    }

    fun setTvMode(on: Boolean) {
        if (realTv || on == isTv) return
        finishText()
        if (on && isPresenter) togglePresenting()
        isTv = on
        session.updateMe(session.me.copy(isTv = on))
        onModeChanged(on)
        if (!on) fitToContent()
    }

    // ------------------------------------------------------------ вид

    fun toWorld(p: Offset) = Offset((p.x - offset.x) / scale, (p.y - offset.y) / scale)

    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val newScale = (scale * zoom).coerceIn(density * 0.05f, density * 12f)
        val k = newScale / scale
        offset = Offset(
            centroid.x - (centroid.x - offset.x) * k + pan.x,
            centroid.y - (centroid.y - offset.y) * k + pan.y,
        )
        scale = newScale
    }

    /** Вписать всё содержимое в экран. */
    fun fitToContent() {
        val (s, o) = fitFor(board, viewSize, density, maxZoom = 2f) ?: return
        scale = s
        offset = o
    }

    // ------------------------------------------------------------ инструменты

    private val strokeWidth get() = WIDTHS[widthIndex]
    private var activeId: String? = null
    private var activeStarted = false
    private var shapeStart = Offset.Zero
    private var lastPoint = Offset.Zero
    /** Исходные элементы, задетые ластиком за жест (для отмены). */
    private val erased = ArrayList<Element>()
    /** Куски, созданные ластиком за жест и ещё живые. */
    private val erasePieces = LinkedHashMap<String, Element>()
    private var lastErase: Offset? = null
    private var downScreen = Offset.Zero
    private var textBefore: TextEl? = null

    fun begin(screen: Offset) {
        if (isTv) return
        if (tool != Tool.TEXT) finishText()
        downScreen = screen
        val w = toWorld(screen)
        lastPoint = w
        activeStarted = false
        when (tool) {
            Tool.PEN -> activeId = Ids.random()
            Tool.LINE, Tool.ARROW, Tool.RECT, Tool.ELLIPSE -> {
                activeId = Ids.random()
                shapeStart = w
            }
            Tool.ERASER -> {
                erased.clear()
                erasePieces.clear()
                lastErase = null
                eraseAt(screen)
            }
            Tool.TEXT -> {}
        }
    }

    fun move(points: List<Offset>) {
        if (isTv || points.isEmpty()) return
        when (tool) {
            Tool.PEN -> {
                val id = activeId ?: return
                val minDist = 1.2f / scale
                val buf = ArrayList<Float>()
                if (!activeStarted) { buf.add(lastPoint.x); buf.add(lastPoint.y) }
                for (p in points) {
                    val w = toWorld(p)
                    if (hypot(w.x - lastPoint.x, w.y - lastPoint.y) < minDist) continue
                    buf.add(w.x); buf.add(w.y)
                    lastPoint = w
                }
                if (!activeStarted) {
                    if (buf.size < 4) return
                    session.submit(Op.Upsert(Stroke(id, color, strokeWidth, buf.toFloatArray())))
                    activeStarted = true
                } else if (buf.isNotEmpty()) {
                    session.submit(Op.Append(id, buf.toFloatArray()))
                }
            }
            Tool.LINE, Tool.ARROW, Tool.RECT, Tool.ELLIPSE -> {
                val id = activeId ?: return
                val w = toWorld(points.last())
                session.submit(Op.Upsert(Shape(id, color, strokeWidth, shapeKind(tool), shapeStart.x, shapeStart.y, w.x, w.y)))
                activeStarted = true
            }
            Tool.ERASER -> points.forEach { eraseAt(it) }
            Tool.TEXT -> {}
        }
    }

    fun end() {
        if (isTv) return
        when (tool) {
            Tool.PEN -> {
                val id = activeId ?: return
                if (!activeStarted) {
                    // Тап — точка
                    session.submit(Op.Upsert(Stroke(id, color, strokeWidth, floatArrayOf(lastPoint.x, lastPoint.y))))
                } else {
                    // Финальная полная версия штриха — гарантирует одинаковый результат у всех.
                    board[id]?.let { session.submit(Op.Upsert(it)) }
                }
                board[id]?.let { pushUndo(Action.Added(it)) }
            }
            Tool.LINE, Tool.ARROW, Tool.RECT, Tool.ELLIPSE -> {
                val id = activeId ?: return
                if (activeStarted) board[id]?.let { pushUndo(Action.Added(it)) }
            }
            Tool.ERASER -> finishErase()
            Tool.TEXT -> {
                val w = toWorld(downScreen)
                val hit = board.elements.values.lastOrNull { it is TextEl && Geometry.hits(it, w.x, w.y, 4f / scale) } as TextEl?
                finishText()
                if (hit != null) {
                    textBefore = hit
                    editingText = hit
                } else {
                    val size = FONT_SIZES[widthIndex]
                    textBefore = null
                    editingText = TextEl(Ids.random(), color, size, w.x, w.y - size * 0.6f, "")
                }
                keepTextAboveKeyboard()
            }
        }
        activeId = null
        activeStarted = false
    }

    /** Второй палец лёг на экран — это масштабирование, а не рисование. */
    fun cancel() {
        when (tool) {
            Tool.PEN, Tool.LINE, Tool.ARROW, Tool.RECT, Tool.ELLIPSE ->
                if (activeStarted) activeId?.let { session.submit(Op.Remove(listOf(it))) }
            Tool.ERASER -> finishErase()
            Tool.TEXT -> {}
        }
        activeId = null
        activeStarted = false
    }

    private fun finishErase() {
        if (erased.isNotEmpty() || erasePieces.isNotEmpty()) {
            pushUndo(
                if (erasePieces.isEmpty()) Action.Removed(erased.toList())
                else Action.Replaced(erased.toList(), erasePieces.values.toList()),
            )
        }
        erased.clear()
        erasePieces.clear()
        eraserAt = null
        lastErase = null
    }

    /** Ластик проходит путь от прошлой точки до новой, чтобы не было пропусков при быстром движении. */
    private fun eraseAt(screen: Offset) {
        eraserAt = screen
        val w = toWorld(screen)
        val r = eraserRadiusDp * density / scale
        val from = lastErase ?: w
        val dist = kotlin.math.hypot(w.x - from.x, w.y - from.y)
        val steps = maxOf(1, kotlin.math.ceil(dist / (r * 0.5f)).toInt())
        for (k in 1..steps) {
            val t = k.toFloat() / steps
            eraseCircle(from.x + (w.x - from.x) * t, from.y + (w.y - from.y) * t, r)
        }
        lastErase = w
    }

    private fun eraseCircle(x: Float, y: Float, r: Float) {
        val hits = board.elements.values.filter { Geometry.hits(it, x, y, r) }
        if (hits.isEmpty()) return
        val removeIds = ArrayList<String>()
        val newPieces = ArrayList<Element>()
        for (e in hits) {
            val polys = if (eraserWhole) null else Eraser.polylines(e)
            if (polys == null) {
                removeIds.add(e.id)
                continue
            }
            var changed = false
            val rest = ArrayList<FloatArray>()
            for (p in polys) {
                val cut = Eraser.cut(p, x, y, r + e.width / 2, minLength = e.width * 0.3f)
                if (cut == null) rest.add(p) else { changed = true; rest.addAll(cut) }
            }
            if (!changed) continue
            removeIds.add(e.id)
            for (p in rest) newPieces.add(Stroke(Ids.random(), e.color, e.width, p))
        }
        if (removeIds.isEmpty()) return
        for (id in removeIds) {
            // Кусок, созданный в этом же жесте, просто исчезает; исходный элемент запоминаем для отмены.
            val gone = erasePieces.remove(id) ?: board[id]?.also { erased.add(it) }
            if (gone == null) continue
        }
        session.submit(Op.Remove(removeIds))
        for (p in newPieces) {
            erasePieces[p.id] = p
            session.submit(Op.Upsert(p))
        }
    }

    // ------------------------------------------------------------ текст

    fun updateText(text: String) {
        val cur = editingText ?: return
        val next = cur.copy(text = text)
        editingText = next
        if (text.isEmpty() && textBefore == null) {
            if (board[next.id] != null) session.submit(Op.Remove(listOf(next.id)))
        } else {
            session.submit(Op.Upsert(next))
        }
    }

    /** Клавиатура закроет нижнюю половину экрана — сдвигаем доску, чтобы текст был виден. */
    private fun keepTextAboveKeyboard() {
        val t = editingText ?: return
        val screenY = t.y * scale + offset.y
        val limit = viewSize.height * 0.3f
        if (screenY > limit) offset = Offset(offset.x, offset.y - (screenY - limit))
    }

    fun restyleText(newColor: Int) {
        val cur = editingText ?: return
        val next = cur.copy(color = newColor)
        editingText = next
        if (board[next.id] != null) session.submit(Op.Upsert(next))
    }

    fun finishText() {
        val cur = editingText ?: return
        editingText = null
        val before = textBefore
        textBefore = null
        when {
            cur.text.isBlank() -> {
                if (board[cur.id] != null) session.submit(Op.Remove(listOf(cur.id)))
                if (before != null) pushUndo(Action.Removed(listOf(before)))
            }
            before == null -> pushUndo(Action.Added(cur))
            before != cur -> pushUndo(Action.Modified(before, cur))
        }
    }

    // ------------------------------------------------------------ отмена, очистка, доски

    fun undo() {
        finishText()
        undo.undo().forEach { session.submit(it) }
        updateUndo()
    }

    fun redo() {
        finishText()
        undo.redo().forEach { session.submit(it) }
        updateUndo()
    }

    fun clearBoard() {
        finishText()
        val all = board.elements.values.toList()
        if (all.isEmpty()) return
        session.submit(Op.Remove(all.map { it.id }))
        pushUndo(Action.Removed(all))
    }

    fun newBoard(name: String? = null) {
        finishText()
        session.newBoard(name)
    }

    fun renameBoard(id: String, name: String) = session.renameBoard(id, name)

    fun openBoard(id: String) {
        finishText()
        if (id != board.id) session.openBoard(id)
    }

    fun deleteBoard(id: String) = session.deleteBoard(id)

    fun requestBoards() {
        boardList = null
        session.requestBoardList()
    }

    private fun pushUndo(a: Action) {
        undo.push(a)
        updateUndo()
    }

    private fun updateUndo() {
        canUndo = undo.canUndo
        canRedo = undo.canRedo
    }

    companion object {

        fun shapeKind(t: Tool) = when (t) {
            Tool.LINE -> ShapeKind.LINE
            Tool.ARROW -> ShapeKind.ARROW
            Tool.RECT -> ShapeKind.RECT
            else -> ShapeKind.ELLIPSE
        }

        /** Масштаб и сдвиг, чтобы область [l, t, r, b] целиком поместилась в экран (по центру). */
        fun fitRect(r: FloatArray, view: IntSize): Pair<Float, Offset>? {
            if (view.width == 0 || view.height == 0) return null
            val w = max(r[2] - r[0], 1f)
            val h = max(r[3] - r[1], 1f)
            val s = min(view.width / w, view.height / h)
            val cx = (r[0] + r[2]) / 2
            val cy = (r[1] + r[3]) / 2
            return s to Offset(view.width / 2f - cx * s, view.height / 2f - cy * s)
        }

        /** Масштаб и сдвиг, при которых всё содержимое доски помещается в экран. */
        fun fitFor(board: Board, view: IntSize, density: Float, maxZoom: Float): Pair<Float, Offset>? {
            if (view.width == 0 || view.height == 0) return null
            val b = board.bounds() ?: return density to Offset(view.width / 2f, view.height / 2f)
            val margin = 0.08f
            val bw = max(b[2] - b[0], 1f)
            val bh = max(b[3] - b[1], 1f)
            val s = min(view.width * (1 - 2 * margin) / bw, view.height * (1 - 2 * margin) / bh)
                .coerceIn(density * 0.05f, density * maxZoom)
            val cx = (b[0] + b[2]) / 2
            val cy = (b[1] + b[3]) / 2
            return s to Offset(view.width / 2f - cx * s, view.height / 2f - cy * s)
        }
    }
}
