package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal enum class AppGlyph {
    CALENDAR, PERSON, WIFI, IMAGE, DOWNLOAD, INFO, BACK, CHEVRON, WEEKS,
    REFRESH, TRAFFIC, GAUGE, SHIELD, COIN, DEVICE, CHECK, EXTERNAL, PLUS, WALLET, GRID
}

/** One 24-unit pen for every icon: round caps, round joins, the same weight. */
internal class GlyphPen(private val scope: DrawScope, private val tint: Color, weight: Float) {
    val u = scope.size.minDimension / 24f
    private val stroke = Stroke(weight * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun poly(vararg p: Float) {
        val path = Path().apply {
            moveTo(p[0] * u, p[1] * u)
            for (i in 2 until p.size step 2) lineTo(p[i] * u, p[i + 1] * u)
        }
        scope.drawPath(path, tint, style = stroke)
    }
    fun path(build: Path.(Float) -> Unit) = scope.drawPath(Path().apply { build(u) }, tint, style = stroke)
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float = 3f) =
        scope.drawRoundRect(tint, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u), style = stroke)
    fun ring(x: Float, y: Float, r: Float) = scope.drawCircle(tint, r * u, Offset(x * u, y * u), style = stroke)
    fun dot(x: Float, y: Float, r: Float = 1.15f) = scope.drawCircle(tint, r * u, Offset(x * u, y * u))
    fun arc(x: Float, y: Float, r: Float, start: Float, sweep: Float) = scope.drawArc(tint, start, sweep, false,
        Offset((x - r) * u, (y - r) * u), Size(2 * r * u, 2 * r * u), style = stroke)

    fun calendar() {
        box(3.5f, 5f, 17f, 15.5f, 3.5f); poly(3.5f, 10f, 20.5f, 10f)
        poly(8f, 3f, 8f, 6.5f); poly(16f, 3f, 16f, 6.5f)
        dot(8.2f, 14.6f); dot(12f, 14.6f); dot(15.8f, 14.6f)
    }
    fun person() {
        ring(12f, 8f, 3.7f)
        path { u -> moveTo(5f * u, 20f * u); cubicTo(5f * u, 15.6f * u, 8f * u, 14.2f * u, 12f * u, 14.2f * u)
            cubicTo(16f * u, 14.2f * u, 19f * u, 15.6f * u, 19f * u, 20f * u) }
    }
    fun wallet() {
        box(3f, 5.5f, 18f, 14f, 3.5f); box(13.6f, 10.2f, 7.4f, 4.6f, 2.2f); dot(16.7f, 12.5f, .95f)
    }
}

@Composable
internal fun LineGlyph(glyph: AppGlyph, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier.size(24.dp)) {
        with(GlyphPen(this, tint, 1.7f)) {
            when (glyph) {
                AppGlyph.BACK -> poly(14.5f, 5f, 7.5f, 12f, 14.5f, 19f)
                AppGlyph.CHEVRON -> poly(9.5f, 5.5f, 16f, 12f, 9.5f, 18.5f)
                AppGlyph.CALENDAR -> calendar()
                AppGlyph.PERSON -> person()
                AppGlyph.WALLET -> wallet()
                AppGlyph.WIFI -> { arc(12f, 18.5f, 12f, 228f, 84f); arc(12f, 18.5f, 8f, 228f, 84f)
                    arc(12f, 18.5f, 4f, 228f, 84f); dot(12f, 18.5f, 1.25f) }
                AppGlyph.IMAGE -> { box(3.5f, 3.5f, 17f, 17f, 4f); ring(8.7f, 8.7f, 1.7f)
                    poly(4.5f, 17.5f, 10f, 12f, 13.5f, 15.5f, 16f, 13f, 19.5f, 16.5f) }
                AppGlyph.DOWNLOAD -> { poly(12f, 3.5f, 12f, 14.5f); poly(7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f)
                    poly(4.5f, 16.5f, 4.5f, 20.5f, 19.5f, 20.5f, 19.5f, 16.5f) }
                AppGlyph.INFO -> { ring(12f, 12f, 8.8f); dot(12f, 8f); poly(12f, 11.5f, 12f, 16.5f) }
                AppGlyph.WEEKS -> for (y in listOf(6.5f, 12f, 17.5f)) { dot(4.5f, y); poly(9f, y, 20f, y) }
                AppGlyph.REFRESH -> { arc(12f, 12f, 8.5f, 0f, 312f); poly(17.7f, 5.7f, 20.5f, 8.2f)
                    poly(20.5f, 3.5f, 20.5f, 8.2f, 15.8f, 8.2f) }
                AppGlyph.TRAFFIC -> { poly(8f, 19f, 8f, 5f); poly(4.5f, 8.5f, 8f, 5f, 11.5f, 8.5f)
                    poly(16f, 5f, 16f, 19f); poly(12.5f, 15.5f, 16f, 19f, 19.5f, 15.5f) }
                AppGlyph.GAUGE -> { arc(12f, 13.5f, 8.8f, 150f, 240f); poly(12f, 13.5f, 15.6f, 8.8f); dot(12f, 13.5f, 1.3f) }
                AppGlyph.SHIELD -> { path { u -> moveTo(12f * u, 3f * u); lineTo(19f * u, 5.8f * u); lineTo(19f * u, 11.5f * u)
                    cubicTo(19f * u, 16f * u, 16f * u, 19.4f * u, 12f * u, 21f * u)
                    cubicTo(8f * u, 19.4f * u, 5f * u, 16f * u, 5f * u, 11.5f * u); lineTo(5f * u, 5.8f * u); close() }
                    poly(9f, 12f, 11.3f, 14.3f, 15.2f, 10f) }
                AppGlyph.COIN -> { ring(12f, 12f, 8.8f); poly(8.8f, 7.6f, 12f, 12f, 15.2f, 7.6f); poly(12f, 12f, 12f, 16.8f)
                    poly(9.2f, 12.4f, 14.8f, 12.4f); poly(9.2f, 14.8f, 14.8f, 14.8f) }
                AppGlyph.DEVICE -> { box(7f, 3f, 10f, 18f, 2.8f); poly(11f, 17.8f, 13f, 17.8f) }
                AppGlyph.CHECK -> poly(5f, 12.5f, 10f, 17.5f, 19f, 7.5f)
                AppGlyph.EXTERNAL -> { poly(9f, 7f, 17f, 7f, 17f, 15f); poly(17f, 7f, 7f, 17f) }
                AppGlyph.PLUS -> { poly(12f, 5f, 12f, 19f); poly(5f, 12f, 19f, 12f) }
                AppGlyph.GRID -> { box(4f, 4f, 7f, 7f, 2f); box(13f, 4f, 7f, 7f, 2f); box(4f, 13f, 7f, 7f, 2f); box(13f, 13f, 7f, 7f, 2f) }
            }
        }
    }
}
