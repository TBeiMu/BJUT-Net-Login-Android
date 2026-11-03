package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF176C52), onPrimary = Color.White,
    primaryContainer = Color(0xFFD4EFDF), onPrimaryContainer = Color(0xFF123C2D),
    secondary = Color(0xFF506459), secondaryContainer = Color(0xFFE0ECE3),
    background = Color(0xFFF5F8F6), surface = Color(0xFFF5F8F6),
    surfaceContainer = Color(0xFFEAF0EC), surfaceContainerLow = Color.White,
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8BD4B0), onPrimary = Color(0xFF003825),
    primaryContainer = Color(0xFF13523D), onPrimaryContainer = Color(0xFFB7F1D1),
    background = Color(0xFF101713), surface = Color(0xFF101713),
    surfaceContainer = Color(0xFF202B24), surfaceContainerLow = Color(0xFF19221C),
)

@Composable
fun GongXiaoZhuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
