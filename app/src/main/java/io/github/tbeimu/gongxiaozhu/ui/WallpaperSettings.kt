package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun WallpaperSettingsPanel(wallpaper: WallpaperState) {
    val preferences = wallpaper.preferences
    val shape = RoundedCornerShape(12.dp)
    Surface(shape = shape, color = MaterialTheme.colorScheme.surface.copy(alpha = .58f),
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().height(170.dp).clip(shape).testTag("wallpaper_preview")) {
                WallpaperBackground(wallpaper, Modifier.fillMaxSize())
                Text("背景预览", Modifier.align(Alignment.BottomStart).padding(12.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            Column(Modifier.selectableGroup()) {
                WallpaperMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("wallpaper_mode_${mode.name}")
                        .selectable(selected = preferences.mode == mode, enabled = !wallpaper.busy,
                            role = Role.RadioButton, onClick = { wallpaper.selectMode(mode) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = preferences.mode == mode, onClick = null, enabled = !wallpaper.busy)
                        Text(mode.title, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            when (preferences.mode) {
                WallpaperMode.PRESET -> BoxWithConstraints {
                    val columns = if (LocalDensity.current.fontScale > 1.3f || maxWidth < 240.dp) 2 else 3
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WallpaperPreset.entries.chunked(columns).forEach { row ->
                            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { preset ->
                                    val selected = preferences.preset == preset
                                    Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 64.dp).clip(shape)
                                        .background(Brush.verticalGradient(wallpaperPresetColors(preset, isSystemInDarkTheme())))
                                        .border(if (selected) 2.dp else 0.dp,
                                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .12f), shape)
                                        .testTag("wallpaper_preset_${preset.name}")
                                        .selectable(selected, enabled = !wallpaper.busy, role = Role.RadioButton,
                                            onClick = { wallpaper.selectPreset(preset) }).padding(10.dp), contentAlignment = Alignment.Center) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(preset.title, style = MaterialTheme.typography.labelLarge,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                            if (selected) Text("✓", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                WallpaperMode.SYSTEM_COLORS -> Text(
                    if (wallpaper.systemColorsUnavailable) "暂未取得壁纸颜色，${if (wallpaper.systemColors == null) "显示蓝青背景" else "保留上次颜色"}"
                    else "根据桌面壁纸颜色生成背景", style = MaterialTheme.typography.bodySmall)
                WallpaperMode.PHOTO -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("高斯模糊", style = MaterialTheme.typography.bodyMedium)
                        Text("${preferences.photoBlurDp}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(value = preferences.photoBlurDp.toFloat(), onValueChange = wallpaper.previewBlur,
                        onValueChangeFinished = wallpaper.saveBlur, valueRange = 0f..24f, steps = 23,
                        enabled = !wallpaper.busy && wallpaper.image != null, modifier = Modifier.testTag("wallpaper_blur"))
                    Button(onClick = wallpaper.choose, enabled = !wallpaper.busy) { Text("更换壁纸") }
                }
            }
            if (wallpaper.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = wallpaper.clear, enabled = !wallpaper.busy, modifier = Modifier.testTag("wallpaper_restore")) { Text("恢复默认背景") }
            wallpaper.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
