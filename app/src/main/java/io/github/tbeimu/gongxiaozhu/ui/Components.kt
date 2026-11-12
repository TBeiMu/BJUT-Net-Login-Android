package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val CardShape = RoundedCornerShape(22.dp)
internal val TileShape = RoundedCornerShape(18.dp)

/** Frosted paper: a translucent fill with a lit top edge. No shadow, so it stays clean on any wallpaper. */
internal fun Modifier.glass(palette: GxzPalette, shape: Shape = CardShape, strong: Boolean = false): Modifier =
    // Solid paper lifted a hair off the page: a soft, wide shadow in light mode, a hairline in both.
    (if (palette.dark) this else shadow(if (strong) 10.dp else 6.dp, shape, clip = false,
        ambientColor = Color(0x14000000), spotColor = Color(0x1A000000)))
        .clip(shape).background(if (strong) palette.glassStrong else palette.glass)
        .border(.7.dp, palette.glassEdge, shape)

@Composable
internal fun GlassCard(modifier: Modifier = Modifier, shape: Shape = CardShape,
    padding: PaddingValues = PaddingValues(20.dp), spacing: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    val palette = gxz
    Column(modifier.fillMaxWidth().glass(palette, shape).padding(padding), verticalArrangement = Arrangement.spacedBy(spacing)) {
        CompositionLocalProvider(LocalContentColor provides palette.ink) { content() }
    }
}

/** Large title of a root tab. */
@Composable
internal fun PageHeader(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = gxz.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = gxz.inkSoft,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = gxz.ink)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = gxz.inkFaint)
    }
}

@Composable
internal fun RoundIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable () -> Unit) {
    val palette = gxz
    val interaction = remember { MutableInteractionSource() }
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier, interactionSource = interaction) {
        // A quiet tinted disc rather than a floating white bubble: icons should not compete with content.
        Box(Modifier.size(40.dp).pressScale(interaction, .88f).clip(CircleShape).background(palette.well), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides
                if (enabled) palette.ink else palette.inkFaint.copy(alpha = .55f)) { content() }
        }
    }
}

@Composable
internal fun GlyphChip(glyph: AppGlyph, modifier: Modifier = Modifier, tint: Color = gxz.ink, container: Color = gxz.well) {
    Box(modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(container), contentAlignment = Alignment.Center) {
        LineGlyph(glyph, Modifier.size(20.dp), tint)
    }
}

@Composable
internal fun Pill(text: String, modifier: Modifier = Modifier, color: Color = gxz.onInk, container: Color = gxz.ink) {
    Text(text, modifier.clip(CircleShape).background(container).padding(horizontal = 9.dp, vertical = 3.dp),
        color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
}

/** Label on the left, value on the right; technical values may use a monospaced face. */
@Composable
internal fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false, labelWidth: Dp = 72.dp) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(labelWidth), style = MaterialTheme.typography.bodySmall, color = gxz.inkFaint)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = gxz.ink,
            fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

/** The one filled, full-width action of a card or page. */
@Composable
internal fun CtaButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 50.dp).pressScale(interaction, .97f),
        colors = ButtonDefaults.buttonColors(containerColor = gxz.ink, contentColor = gxz.onInk), interactionSource = interaction) {
        // The label cross-fades when it changes, e.g. 登录查询 → 刷新资费.
        androidx.compose.animation.AnimatedContent(text, transitionSpec = {
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.MEDIUM)) togetherWith
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.SHORT))
        }, label = "按钮文字") { Text(it, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}
