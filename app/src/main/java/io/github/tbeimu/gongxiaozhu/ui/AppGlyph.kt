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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal enum class AppGlyph { CALENDAR, PERSON, WIFI, IMAGE, DOWNLOAD, INFO, BACK, CHEVRON, WEEKS }

@Composable
internal fun LineGlyph(glyph: AppGlyph, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier.size(24.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(1.7f * u, cap = StrokeCap.Round)
        fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(tint, Offset(x*u, y*u), Offset(a*u,b*u), stroke.width, StrokeCap.Round)
        fun box(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(tint, Offset(x*u,y*u), Size(w*u,h*u), CornerRadius(2*u), style = stroke)
        when (glyph) {
            AppGlyph.BACK -> { line(15f,5f,8f,12f); line(8f,12f,15f,19f) }
            AppGlyph.CHEVRON -> { line(9f,5f,16f,12f); line(16f,12f,9f,19f) }
            AppGlyph.CALENDAR -> { box(3f,5f,18f,16f); line(3f,10f,21f,10f); line(8f,3f,8f,7f);line(16f,3f,16f,7f);drawCircle(tint,u,Offset(8*u,15*u)); drawCircle(tint,u,Offset(15*u,15*u)) }
            AppGlyph.PERSON -> { drawCircle(tint,4*u,Offset(12*u,7*u),style=stroke); drawPath(Path().apply { moveTo(4*u,21*u);cubicTo(4*u,11*u,20*u,11*u,20*u,21*u) },tint,style=stroke) }
            AppGlyph.WIFI -> { for (n in listOf(0f,4f,8f)) drawArc(tint,220f,100f,false,Offset((2+n/2)*u,(4+n)*u),Size((20-n)*u,(16-n)*u),style=stroke);drawCircle(tint,1.5f*u,Offset(12*u,20*u)) }
            AppGlyph.IMAGE -> { box(3f,3f,18f,18f); drawCircle(tint,2*u,Offset(8*u,8*u),style=stroke);line(4f,19f,11f,12f);line(11f,12f,15f,16f);line(15f,16f,20f,11f) }
            AppGlyph.DOWNLOAD -> { line(12f,3f,12f,15f);line(7f,10f,12f,15f);line(12f,15f,17f,10f);line(4f,16f,4f,21f);line(4f,21f,20f,21f);line(20f,21f,20f,16f) }
            AppGlyph.INFO -> { drawCircle(tint,9*u,Offset(12*u,12*u),style=stroke);drawCircle(tint,u,Offset(12*u,7*u));line(12f,11f,12f,17f) }
            AppGlyph.WEEKS -> { for (y in listOf(6f,12f,18f)) { drawCircle(tint,u,Offset(4*u,y*u));line(9f,y,20f,y) } }
        }
    }
}
