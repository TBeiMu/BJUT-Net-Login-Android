package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun WallpaperSettingsPanel(wallpaper: WallpaperState) {
    val preferences = wallpaper.preferences
    val palette = gxz
    val dark = isSystemInDarkTheme()
    val largeText = LocalDensity.current.fontScale > 1.3f
    GlassCard(padding = PaddingValues(16.dp), spacing = 14.dp) {
        // The preview is a small page: backdrop, a line of title and a few course tiles.
        Box(Modifier.fillMaxWidth().height(190.dp).clip(TileShape).testTag("wallpaper_preview")) {
            WallpaperBackground(wallpaper, Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("背景预览", style = MaterialTheme.typography.labelMedium, color = palette.inkSoft)
                TimetableMotif(Modifier.fillMaxWidth().height(118.dp), plain = true)
            }
        }
        // A segmented control at normal sizes; a plain list when text is enlarged.
        if (largeText) Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WallpaperMode.entries.forEach { mode -> ModeSegment(wallpaper, mode, Modifier.fillMaxWidth()) }
        } else BoxWithConstraints(Modifier.fillMaxWidth().clip(CircleShape).background(palette.well).padding(4.dp)) {
            // One thumb slides between the three choices instead of three backgrounds blinking.
            val count = WallpaperMode.entries.size
            val segment = (maxWidth - 2.dp * (count - 1)) / count
            val x by animateDpAsState((segment + 2.dp) * preferences.mode.ordinal, Motion.snappy(), label = "外观模式滑块")
            Box(Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(segment).height(44.dp).align(Alignment.CenterStart)
                .clip(CircleShape).background(palette.glassStrong))
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                WallpaperMode.entries.forEach { mode -> ModeSegment(wallpaper, mode, Modifier.weight(1f), thumb = false) }
            }
        }
        AnimatedContent(preferences.mode, transitionSpec = {
            (fadeIn(tween(Motion.MEDIUM, 80)) + slideInVertically(tween(Motion.LONG, easing = Motion.Decelerate)) { 24 }) togetherWith
                fadeOut(tween(Motion.SHORT)) using SizeTransform { _, _ -> tween(Motion.LONG, easing = Motion.Emphasized) }
        }, label = "外观模式内容") { mode -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { when (mode) {
            WallpaperMode.PRESET -> BoxWithConstraints {
                val columns = if (largeText || maxWidth < 240.dp) 2 else 3
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WallpaperPreset.entries.chunked(columns).forEach { row ->
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { preset ->
                                val selected = preferences.preset == preset
                                // Dark backgrounds are nearly black; the swatch keeps enough of the hue to choose by.
                                val colors = if (dark) wallpaperPresetSwatch(preset).map { androidx.compose.ui.graphics.lerp(it, Color(0xFF0B1522), .6f) }
                                    else wallpaperPresetSwatch(preset)
                                val shape = RoundedCornerShape(16.dp)
                                Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 76.dp).clip(shape)
                                    .background(Brush.linearGradient(listOf(colors[0], colors[1],
                                        androidx.compose.ui.graphics.lerp(colors[2], wallpaperPresetBloom(preset), if (dark) .08f else .55f))))
                                    .border(borderWidth(selected), borderColor(selected, palette), shape)
                                    .testTag("wallpaper_preset_${preset.name}")
                                    .selectable(selected, enabled = !wallpaper.busy, role = Role.RadioButton,
                                        onClick = { wallpaper.selectPreset(preset) }).padding(12.dp)) {
                                    Text(preset.title, Modifier.align(Alignment.BottomStart), style = MaterialTheme.typography.labelLarge,
                                        color = palette.ink, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                                    // The tick pops in with a small overshoot: a confirmation, not a decoration.
                                    if (selected) Box(Modifier.align(Alignment.TopEnd).size(20.dp).popIn().clip(CircleShape).background(palette.ink),
                                        contentAlignment = Alignment.Center) {
                                        LineGlyph(AppGlyph.CHECK, Modifier.size(12.dp), palette.onInk)
                                    }
                                }
                            }
                            // Keep the last row's tiles the same width as the rows above.
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            WallpaperMode.SYSTEM_COLORS -> Text(
                if (wallpaper.systemColorsUnavailable) "暂未取得壁纸颜色，${if (wallpaper.systemColors == null) "显示蓝青背景" else "保留上次颜色"}"
                else "根据桌面壁纸颜色生成背景", Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
            WallpaperMode.PHOTO -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("高斯模糊", style = MaterialTheme.typography.bodyMedium)
                    Text("${preferences.photoBlurDp}", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = palette.inkSoft)
                }
                Slider(value = preferences.photoBlurDp.toFloat(), onValueChange = wallpaper.previewBlur,
                    onValueChangeFinished = wallpaper.saveBlur, valueRange = 0f..24f, steps = 23,
                    enabled = !wallpaper.busy && wallpaper.image != null, modifier = Modifier.testTag("wallpaper_blur"),
                    colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                        inactiveTrackColor = palette.well))
                CtaButton("更换壁纸", wallpaper.choose, enabled = !wallpaper.busy)
            }
        } } }
        if (wallpaper.busy) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), trackColor = palette.well)
        TextButton(onClick = wallpaper.clear, enabled = !wallpaper.busy,
            modifier = Modifier.align(Alignment.CenterHorizontally).testTag("wallpaper_restore")) { Text("恢复默认背景") }
        wallpaper.message?.let { Text(it, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = palette.inkSoft) }
    }
}

@Composable
private fun borderWidth(selected: Boolean) =
    animateDpAsState(if (selected) 2.dp else .8.dp, Motion.snappy(), label = "色卡描边").value

@Composable
private fun borderColor(selected: Boolean, palette: GxzPalette) =
    animateColorAsState(if (selected) palette.ink else palette.glassEdge, tween(Motion.MEDIUM), label = "色卡描边颜色").value

@Composable
private fun ModeSegment(wallpaper: WallpaperState, mode: WallpaperMode, modifier: Modifier, thumb: Boolean = true) {
    val palette = gxz
    val selected = wallpaper.preferences.mode == mode
    val ink by animateColorAsState(if (selected) palette.ink else palette.inkSoft, tween(Motion.MEDIUM), label = "外观模式文字")
    Box(modifier.heightIn(min = 44.dp).clip(CircleShape)
        .background(if (selected && thumb) palette.glassStrong else Color.Transparent)
        .testTag("wallpaper_mode_${mode.name}")
        .selectable(selected = selected, enabled = !wallpaper.busy, role = Role.RadioButton, onClick = { wallpaper.selectMode(mode) })
        .padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(mode.title, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = ink)
    }
}
