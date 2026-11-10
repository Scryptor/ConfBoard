package board.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Иконка ластика (в стандартном наборе Material её нет). */
val EraserIcon: ImageVector = ImageVector.Builder("Eraser", 24.dp, 24.dp, 24f, 24f).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(14.5f, 4f)
        lineTo(20.5f, 10f)
        lineTo(12.5f, 18f)
        lineTo(7.5f, 18f)
        lineTo(3.5f, 14f)
        close()
        moveTo(8.5f, 10f)
        lineTo(14.5f, 16f)
        moveTo(12.5f, 20.5f)
        lineTo(20.5f, 20.5f)
    }
}.build()
