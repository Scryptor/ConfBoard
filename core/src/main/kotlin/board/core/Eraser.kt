package board.core

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Частичное стирание: вырезает круг ластика из линий, разрезая их на куски. */
object Eraser {
    /**
     * Элемент как набор ломаных [x0, y0, x1, y1, ...]. Текст разрезать нельзя — null.
     * Фигуры при частичном стирании превращаются в обычные линии.
     */
    fun polylines(e: Element): List<FloatArray>? = when (e) {
        is Stroke -> listOf(e.points())
        is TextEl -> null
        is Shape -> when (e.kind) {
            ShapeKind.LINE -> listOf(floatArrayOf(e.x1, e.y1, e.x2, e.y2))
            ShapeKind.ARROW -> {
                val h = Geometry.arrowHead(e)
                listOf(
                    floatArrayOf(e.x1, e.y1, e.x2, e.y2),
                    floatArrayOf(e.x2, e.y2, h[0], h[1]),
                    floatArrayOf(e.x2, e.y2, h[2], h[3]),
                )
            }
            ShapeKind.RECT -> listOf(
                floatArrayOf(e.x1, e.y1, e.x2, e.y1, e.x2, e.y2, e.x1, e.y2, e.x1, e.y1),
            )
            ShapeKind.ELLIPSE -> {
                val cx = (e.x1 + e.x2) / 2; val cy = (e.y1 + e.y2) / 2
                val rx = kotlin.math.abs(e.x2 - e.x1) / 2; val ry = kotlin.math.abs(e.y2 - e.y1) / 2
                val n = 72
                listOf(FloatArray((n + 1) * 2) { k ->
                    val a = 2 * Math.PI * (k / 2) / n
                    if (k % 2 == 0) (cx + rx * cos(a)).toFloat() else (cy + ry * sin(a)).toFloat()
                })
            }
        }
    }

    /**
     * Вырезает круг (cx, cy, r) из ломаной. null — круг ломаную не задел;
     * иначе — оставшиеся куски (может быть пустой список, если стёрто всё).
     */
    fun cut(pts: FloatArray, cx: Float, cy: Float, r: Float, minLength: Float = 0.5f): List<FloatArray>? {
        val n = pts.size / 2
        if (n == 0) return null
        if (n == 1) return if (hypot(pts[0] - cx, pts[1] - cy) <= r) emptyList() else null

        var touched = false
        val pieces = ArrayList<FloatArray>()
        val cur = ArrayList<Float>()

        fun finish() {
            if (cur.size >= 4) {
                val arr = cur.toFloatArray()
                if (length(arr) >= minLength) pieces.add(arr)
            }
            cur.clear()
        }

        for (i in 0 until n - 1) {
            val ax = pts[i * 2]; val ay = pts[i * 2 + 1]
            val bx = pts[i * 2 + 2]; val by = pts[i * 2 + 3]
            val dx = bx - ax; val dy = by - ay
            val fx = ax - cx; val fy = ay - cy
            val a = dx * dx + dy * dy
            val c = fx * fx + fy * fy - r * r
            var t0 = 1f; var t1 = 0f // пустой интервал
            if (a == 0f) {
                if (c <= 0f) { t0 = 0f; t1 = 1f }
            } else {
                val b = 2 * (fx * dx + fy * dy)
                val disc = b * b - 4 * a * c
                if (disc >= 0) {
                    val sq = sqrt(disc)
                    t0 = ((-b - sq) / (2 * a)).coerceIn(0f, 1f)
                    t1 = ((-b + sq) / (2 * a)).coerceIn(0f, 1f)
                }
            }
            if (t1 <= t0) {
                // Отрезок не задет
                if (cur.isEmpty()) { cur.add(ax); cur.add(ay) }
                cur.add(bx); cur.add(by)
                continue
            }
            touched = true
            if (t0 > 0f) {
                if (cur.isEmpty()) { cur.add(ax); cur.add(ay) }
                cur.add(ax + dx * t0); cur.add(ay + dy * t0)
            }
            finish()
            if (t1 < 1f) {
                cur.add(ax + dx * t1); cur.add(ay + dy * t1)
                cur.add(bx); cur.add(by)
            }
        }
        finish()
        return if (touched) pieces else null
    }

    fun length(p: FloatArray): Float {
        var len = 0f
        for (i in 0 until p.size / 2 - 1) len += hypot(p[i * 2 + 2] - p[i * 2], p[i * 2 + 3] - p[i * 2 + 1])
        return len
    }
}
