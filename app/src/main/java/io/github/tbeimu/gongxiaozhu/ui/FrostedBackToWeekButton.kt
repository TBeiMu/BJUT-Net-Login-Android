package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

/** A display list and its origin, retained only while its page is composed. No pixel readback. */
internal class ScheduleBackdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}

internal val LocalWallpaperBackdrop = staticCompositionLocalOf<ScheduleBackdrop?> { null }

@Composable
internal fun rememberScheduleBackdrop(): ScheduleBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { ScheduleBackdrop(layer) }
}

/** Record just the source: the floating button is drawn separately and never captures itself. */
internal fun Modifier.recordScheduleBackdrop(source: ScheduleBackdrop): Modifier =
    onGloballyPositioned { source.origin = it.positionInRoot() }.drawWithContent {
        source.layer.record { this@drawWithContent.drawContent() }
        drawLayer(source.layer)
    }

@Composable
internal fun FrostedBackToWeekButton(grid: ScheduleBackdrop, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: () -> Unit) {
    val wallpaper = LocalWallpaperBackdrop.current
    val blurred = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(topStart = 50.dp, bottomStart = 50.dp)
    val colors = MaterialTheme.colorScheme
    val tint = colors.surface.copy(alpha = if (pressed) .56f else .32f)
    val fallback = colors.background
    val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp)
    val measured = rememberTextMeasurer().measure("回到本周", textStyle, maxLines = 1)
    // Keep a small guard for fractional glyph widths when the user enlarges system text.
    val textWidth = with(LocalDensity.current) { (measured.size.width + 2).toDp() }
    // A 28 dp visible pill within a 48 dp touch target: compact without making it hard to tap.
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("back_current_week")
        .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.heightIn(min = 28.dp).testTag("back_current_week_surface")
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(shape).border(.7.dp, colors.onSurface.copy(alpha = .22f), shape)
            .drawWithContent {
                val radius = 8.dp.toPx()
                // Sample beyond the visible pill before blurring, so its edges have real neighbors.
                val inset = ceil(radius * 3).toInt()
                blurred.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                blurred.record(IntSize(ceil(size.width).toInt() + inset * 2, ceil(size.height).toInt() + inset * 2)) {
                    drawRect(fallback)
                    listOfNotNull(wallpaper, grid).forEach { source ->
                        translate(inset + source.origin.x - origin.x, inset + source.origin.y - origin.y) {
                            drawLayer(source.layer)
                        }
                    }
                }
                translate(-inset.toFloat(), -inset.toFloat()) { drawLayer(blurred) }
                drawRect(tint)
                drawContent()
            }.padding(start = 12.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center) {
            Text("回到本周", Modifier.widthIn(min = textWidth), style = textStyle,
                maxLines = 1, color = colors.onSurface)
        }
    }
}
