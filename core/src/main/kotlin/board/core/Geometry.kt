package board.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object Geometry {
    /** Приблизительная ширина символа относительно размера шрифта (для попаданий и габаритов). */
    const val CHAR_WIDTH = 0.58f
    const val LINE_HEIGHT = 1.25f

    fun textLines(t: TextEl): List<String> = t.text.split('\n')

    /** Записывает габариты элемента в out = [minX, minY, maxX, maxY]. */
    fun bounds(e: Element, out: FloatArray): Boolean {
        val pad = e.width / 2
        when (e) {
            is Stroke -> {
                if (e.pointCount == 0) return false
                var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE
                var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                for (i in 0 until e.pointCount) {
                    val x = e.x(i); val y = e.y(i)
                    if (x < x0) x0 = x; if (x > x1) x1 = x
                    if (y < y0) y0 = y; if (y > y1) y1 = y
                }
                out[0] = x0 - pad; out[1] = y0 - pad; out[2] = x1 + pad; out[3] = y1 + pad
            }
            is Shape -> {
                val head = if (e.kind == ShapeKind.ARROW) arrowHeadLength(e) else 0f
                val p = pad + head
                out[0] = min(e.x1, e.x2) - p; out[1] = min(e.y1, e.y2) - p
                out[2] = max(e.x1, e.x2) + p; out[3] = max(e.y1, e.y2) + p
            }
            is TextEl -> {
                if (e.text.isEmpty()) return false
                val lines = textLines(e)
                val w = lines.maxOf { it.length } * e.fontSize * CHAR_WIDTH
                val h = lines.size * e.fontSize * LINE_HEIGHT
                out[0] = e.x; out[1] = e.y; out[2] = e.x + w; out[3] = e.y + h
            }
        }
        return true
    }

    fun arrowHeadLength(s: Shape): Float = max(12f, s.width * 4f)

    /** Две точки «усиков» стрелки: [ax, ay, bx, by]. */
    fun arrowHead(s: Shape): FloatArray {
        val len = arrowHeadLength(s)
        val ang = kotlin.math.atan2(s.y2 - s.y1, s.x2 - s.x1)
        val spread = Math.toRadians(28.0).toFloat()
        return floatArrayOf(
            s.x2 - len * cos(ang - spread), s.y2 - len * sin(ang - spread),
            s.x2 - len * cos(ang + spread), s.y2 - len * sin(ang + spread),
        )
    }

    /** Попадает ли круг (x, y, r) в элемент. Используется ластиком. */
    fun hits(e: Element, x: Float, y: Float, r: Float): Boolean {
        val tol = r + e.width / 2
        return when (e) {
            is Stroke -> {
                val n = e.pointCount
                if (n == 0) false
                else if (n == 1) hypot(e.x(0) - x, e.y(0) - y) <= tol
                else (1 until n).any { i -> segDist(x, y, e.x(i - 1), e.y(i - 1), e.x(i), e.y(i)) <= tol }
            }
            is Shape -> when (e.kind) {
                ShapeKind.LINE -> segDist(x, y, e.x1, e.y1, e.x2, e.y2) <= tol
                ShapeKind.ARROW -> {
                    val h = arrowHead(e)
                    segDist(x, y, e.x1, e.y1, e.x2, e.y2) <= tol ||
                        segDist(x, y, e.x2, e.y2, h[0], h[1]) <= tol ||
                        segDist(x, y, e.x2, e.y2, h[2], h[3]) <= tol
                }
                ShapeKind.RECT -> {
                    val l = min(e.x1, e.x2); val rr = max(e.x1, e.x2)
                    val t = min(e.y1, e.y2); val b = max(e.y1, e.y2)
                    segDist(x, y, l, t, rr, t) <= tol || segDist(x, y, rr, t, rr, b) <= tol ||
                        segDist(x, y, rr, b, l, b) <= tol || segDist(x, y, l, b, l, t) <= tol
                }
                ShapeKind.ELLIPSE -> {
                    val cx = (e.x1 + e.x2) / 2; val cy = (e.y1 + e.y2) / 2
                    val rx = abs(e.x2 - e.x1) / 2; val ry = abs(e.y2 - e.y1) / 2
                    val steps = 48
                    var px = cx + rx; var py = cy
                    var hit = false
                    for (i in 1..steps) {
                        val a = (2 * Math.PI * i / steps).toFloat()
                        val nx = cx + rx * cos(a); val ny = cy + ry * sin(a)
                        if (segDist(x, y, px, py, nx, ny) <= tol) { hit = true; break }
                        px = nx; py = ny
                    }
                    hit
                }
            }
            is TextEl -> {
                val b = FloatArray(4)
                bounds(e, b) && x >= b[0] - r && x <= b[2] + r && y >= b[1] - r && y <= b[3] + r
            }
        }
    }

    /** Расстояние от точки до отрезка. */
    fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
