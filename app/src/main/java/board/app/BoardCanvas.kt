package board.app

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch

/** Холст планшета: рисование одним пальцем, масштаб и сдвиг двумя. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun BoardCanvas(c: BoardController, modifier: Modifier = Modifier) {
    val renderer = remember { BoardRenderer() }
    val density = LocalDensity.current.density
    Canvas(
        modifier
            .onSizeChanged { c.viewSize = it }
            .pointerInput(c) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var transforming = false
                    var drawing = true
                    c.begin(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2 || transforming) {
                            if (!transforming) {
                                transforming = true
                                if (drawing) { c.cancel(); drawing = false }
                            }
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (centroid != Offset.Unspecified) {
                                c.transform(centroid, event.calculatePan(), event.calculateZoom())
                            }
                        } else if (drawing) {
                            val ch = event.changes.firstOrNull { it.id == down.id }
                            if (ch != null && ch.pressed) {
                                c.move(ch.historical.map { it.position } + ch.position)
                            }
                        }
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                    if (drawing) c.end()
                }
            },
    ) {
        c.revision // перерисовка при любом изменении доски
        val editing = c.editingText
        drawIntoCanvas {
            renderer.draw(it.nativeCanvas, c.board, c.scale, c.offset.x, c.offset.y, density, editing)
        }
        c.eraserAt?.let {
            val r = c.eraserRadiusDp * density
            drawCircle(Color(0x3390A4AE), r, it)
            drawCircle(Color(0xFF607D8B), r, it, style = DrawStroke(1.5f * density))
        }
    }
}

/** Холст телевизора: только показ, всё содержимое плавно вписывается в экран. */
@Composable
fun TvCanvas(c: BoardController, modifier: Modifier = Modifier) {
    val renderer = remember { BoardRenderer() }
    val density = LocalDensity.current.density
    val scale = remember { Animatable(density) }
    val ox = remember { Animatable(0f) }
    val oy = remember { Animatable(0f) }
    val size = remember { arrayOf(IntSize.Zero) }
    val sizeTick = remember { androidx.compose.runtime.mutableIntStateOf(0) }

    val vp = c.presenterViewport
    LaunchedEffect(if (vp == null) c.revision else -1, sizeTick.intValue, vp) {
        // Есть ведущий — повторяем его вид, иначе вписываем всю доску.
        val (s, o) = (if (vp != null) BoardController.fitRect(vp, size[0]) else null)
            ?: BoardController.fitFor(c.board, size[0], density, maxZoom = 2.5f)
            ?: return@LaunchedEffect
        // Пружина сохраняет скорость при смене цели — плавно догоняет даже во время рисования.
        val spec = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = if (vp != null) 400f else 120f)
        launch { scale.animateTo(s, spec) }
        launch { ox.animateTo(o.x, spec) }
        launch { oy.animateTo(o.y, spec) }
    }

    Canvas(modifier.onSizeChanged { size[0] = it; sizeTick.intValue++ }) {
        c.revision
        drawIntoCanvas {
            renderer.draw(it.nativeCanvas, c.board, scale.value, ox.value, oy.value, density, null)
        }
    }
}
