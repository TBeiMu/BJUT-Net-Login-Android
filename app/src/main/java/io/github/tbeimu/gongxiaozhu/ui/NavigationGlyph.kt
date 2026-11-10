package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
internal fun NavigationGlyph(index: Int) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24
        val stroke = Stroke(1.8f * u)
        when (index) {
            0 -> {
                drawRoundRect(color, Offset(3 * u, 5 * u), Size(18 * u, 16 * u), CornerRadius(3 * u), style = stroke)
                drawLine(color, Offset(3 * u, 10 * u), Offset(21 * u, 10 * u), strokeWidth = stroke.width)
                for (x in listOf(8f, 16f)) drawLine(color, Offset(x * u, 2 * u), Offset(x * u, 7 * u), strokeWidth = stroke.width)
                for (x in listOf(8f, 12f, 16f)) drawCircle(color, u, Offset(x * u, 15 * u))
            }
            1 -> {
                drawRoundRect(color, Offset(3 * u, 4 * u), Size(18 * u, 17 * u), CornerRadius(3 * u), style = stroke)
                drawRoundRect(color, Offset(13 * u, 10 * u), Size(8 * u, 6 * u), CornerRadius(2 * u), style = stroke)
                drawCircle(color, u, Offset(16 * u, 13 * u))
            }
            else -> {
                drawCircle(color, 4 * u, Offset(12 * u, 6 * u), style = stroke)
                drawPath(Path().apply { moveTo(4 * u, 21 * u); cubicTo(4 * u, 10 * u, 20 * u, 10 * u, 20 * u, 21 * u); close() }, color, style = stroke)
            }
        }
    }
}
