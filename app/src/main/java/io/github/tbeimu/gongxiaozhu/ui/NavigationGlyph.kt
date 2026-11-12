package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Dock icons share the pen of [LineGlyph]; the selected tab is drawn slightly heavier. */
@Composable
internal fun NavigationGlyph(index: Int, selected: Boolean = false) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        with(GlyphPen(this, color, if (selected) 1.95f else 1.75f)) {
            when (index) {
                0 -> calendar()
                1 -> wallet()
                else -> person()
            }
        }
    }
}
