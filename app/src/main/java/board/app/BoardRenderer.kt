package board.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import board.core.Board
import board.core.Element
import board.core.Geometry
import board.core.Shape
import board.core.ShapeKind
import board.core.Stroke
import board.core.TextEl
import kotlin.math.ceil
import kotlin.math.floor

/** Рисует доску на android.graphics.Canvas. Кэширует пути штрихов. */
class BoardRenderer {
    private class Cached(val ref: Element, val count: Int, val path: Path)

    private val cache = HashMap<String, Cached>()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(205, 210, 218)
        strokeCap = Paint.Cap.ROUND
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(30, 136, 229)
    }
    private val rect = RectF()
    private var gridPoints = FloatArray(0)

    fun draw(c: Canvas, board: Board, scale: Float, ox: Float, oy: Float, density: Float, editing: TextEl?) {
        c.drawColor(Color.WHITE)
        drawGrid(c, scale, ox, oy, density)
        c.save()
        c.translate(ox, oy)
        c.scale(scale, scale)
        for (e in board.elements.values) drawElement(c, e)
        if (editing != null) {
            if (board[editing.id] == null) drawElement(c, editing)
            val b = FloatArray(4)
            val fs = editing.fontSize
            if (!Geometry.bounds(editing, b)) { b[0] = editing.x; b[1] = editing.y; b[2] = editing.x + fs * 0.6f; b[3] = editing.y + fs * 1.25f }
            outlinePaint.strokeWidth = 1.5f * density / scale
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(6 * density / scale, 4 * density / scale), 0f)
            val pad = 4 * density / scale
            c.drawRect(b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad, outlinePaint)
        }
        c.restore()
        if (cache.size > board.elements.size + 64) cache.keys.retainAll(board.elements.keys)
    }

    private fun drawGrid(c: Canvas, scale: Float, ox: Float, oy: Float, density: Float) {
        var step = 40f
        while (step * scale < 18 * density) step *= 2
        while (step * scale > 72 * density) step /= 2
        val w = c.width; val h = c.height
        val x0 = floor(-ox / scale / step) * step
        val y0 = floor(-oy / scale / step) * step
        val nx = ceil(w / (step * scale)).toInt() + 2
        val ny = ceil(h / (step * scale)).toInt() + 2
        val n = nx * ny * 2
        if (n > 40_000) return
        if (gridPoints.size < n) gridPoints = FloatArray(n)
        var k = 0
        for (i in 0 until nx) for (j in 0 until ny) {
            gridPoints[k++] = (x0 + i * step) * scale + ox
            gridPoints[k++] = (y0 + j * step) * scale + oy
        }
        gridPaint.strokeWidth = 2.2f * density
        c.drawPoints(gridPoints, 0, k, gridPaint)
    }

    private fun drawElement(c: Canvas, e: Element) {
        when (e) {
            is Stroke -> drawStroke(c, e)
            is Shape -> drawShape(c, e)
            is TextEl -> drawText(c, e)
        }
    }

    private fun drawStroke(c: Canvas, s: Stroke) {
        val n = s.pointCount
        if (n == 0) return
        if (n == 1) {
            fillPaint.color = s.color
            c.drawCircle(s.x(0), s.y(0), s.width / 2, fillPaint)
            return
        }
        val cached = cache[s.id]
        val path = if (cached != null && cached.ref === s && cached.count == n) cached.path else {
            val p = cached?.path ?: Path()
            buildSmoothPath(p, s)
            cache[s.id] = Cached(s, n, p)
            p
        }
        strokePaint.color = s.color
        strokePaint.strokeWidth = s.width
        c.drawPath(path, strokePaint)
    }

    /** Сглаживание: квадратичные кривые через середины соседних точек. */
    private fun buildSmoothPath(p: Path, s: Stroke) {
        p.reset()
        val n = s.pointCount
        p.moveTo(s.x(0), s.y(0))
        if (n == 2) {
            p.lineTo(s.x(1), s.y(1)); return
        }
        for (i in 1 until n - 1) {
            val mx = (s.x(i) + s.x(i + 1)) / 2
            val my = (s.y(i) + s.y(i + 1)) / 2
            p.quadTo(s.x(i), s.y(i), mx, my)
        }
        p.lineTo(s.x(n - 1), s.y(n - 1))
    }

    private fun drawShape(c: Canvas, s: Shape) {
        strokePaint.color = s.color
        strokePaint.strokeWidth = s.width
        when (s.kind) {
            ShapeKind.LINE -> c.drawLine(s.x1, s.y1, s.x2, s.y2, strokePaint)
            ShapeKind.ARROW -> {
                c.drawLine(s.x1, s.y1, s.x2, s.y2, strokePaint)
                val h = Geometry.arrowHead(s)
                c.drawLine(s.x2, s.y2, h[0], h[1], strokePaint)
                c.drawLine(s.x2, s.y2, h[2], h[3], strokePaint)
            }
            ShapeKind.RECT -> {
                rect.set(minOf(s.x1, s.x2), minOf(s.y1, s.y2), maxOf(s.x1, s.x2), maxOf(s.y1, s.y2))
                c.drawRect(rect, strokePaint)
            }
            ShapeKind.ELLIPSE -> {
                rect.set(minOf(s.x1, s.x2), minOf(s.y1, s.y2), maxOf(s.x1, s.x2), maxOf(s.y1, s.y2))
                c.drawOval(rect, strokePaint)
            }
        }
    }

    private fun drawText(c: Canvas, t: TextEl) {
        if (t.text.isEmpty()) return
        textPaint.color = t.color
        textPaint.textSize = t.fontSize
        val lines = Geometry.textLines(t)
        for ((i, line) in lines.withIndex()) {
            val baseline = t.y + t.fontSize * (0.95f + i * Geometry.LINE_HEIGHT)
            c.drawText(line, t.x, baseline, textPaint)
        }
    }
}
