package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF205F91), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F2FF).copy(alpha = .88f), onPrimaryContainer = Color(0xFF13384E),
    secondary = Color(0xFF326779), secondaryContainer = Color(0xFFE3F4F8).copy(alpha = .88f),
    background = Color(0xFFDAECF4), surface = Color(0xFFF4F9FC),
    surfaceContainer = Color(0xFFE5F1F9).copy(alpha = .88f), surfaceContainerLow = Color.White.copy(alpha = .84f),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9BCEF1), onPrimary = Color(0xFF093149),
    secondary = Color(0xFFA5D7E5), secondaryContainer = Color(0xFF244857), onSecondaryContainer = Color(0xFFD6F1FA),
    primaryContainer = Color(0xFF183D53).copy(alpha = .9f), onPrimaryContainer = Color(0xFFD8EDF9),
    background = Color(0xFF101E2A), surface = Color(0xFF132738),
    surfaceContainer = Color(0xFF1C3446).copy(alpha = .92f), surfaceContainerLow = Color(0xFF193040).copy(alpha = .92f),
)

@Composable
fun GongXiaoZhuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
