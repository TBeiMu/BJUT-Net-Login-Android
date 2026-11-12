package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tbeimu.gongxiaozhu.network.CampusConnectionNotice
import io.github.tbeimu.gongxiaozhu.network.CampusTitlePhase

@Composable
internal fun CampusConnectionTitle(weekTitle: String, notice: CampusConnectionNotice?, unobstructed: Boolean,
    headline: TextStyle? = null) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, notice, unobstructed) {
        fun updateVisibility() { notice?.setVisible(unobstructed && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val observer = LifecycleEventObserver { _, _ -> updateVisibility() }
        owner.lifecycle.addObserver(observer)
        updateVisibility()
        onDispose { owner.lifecycle.removeObserver(observer); notice?.setVisible(false) }
    }
    val phase = notice?.state?.collectAsStateWithLifecycle()?.value?.phase?.takeIf { unobstructed }
    // Observe only the runtime's title state; a lagging AppUiState cannot erase a new result.
    // Replace every title in the same frame, including accepted week changes and late success.
    // Old connecting/timeout text must never fade over the confirmed result.
    TitleText(when (phase) {
        CampusTitlePhase.CONNECTING -> "正在登录校园网"
        CampusTitlePhase.CONNECTED -> "校园网已连接"
        CampusTitlePhase.TIMED_OUT -> "登录超时"
        CampusTitlePhase.NO_WIFI -> "未连接WiFi"
        null -> weekTitle
    }, notice = phase != null,
        modifier = if (phase != null) Modifier.testTag("campus_connection_title") else Modifier,
        headline = headline)
}

@Composable
private fun TitleText(text: String, notice: Boolean, modifier: Modifier = Modifier, headline: TextStyle? = null) {
    val palette = gxz
    // Editorial setting: the week number is the headline, “第” and “周” are its captions.
    // The text stays exactly “第 N 周”; only its spans are styled.
    val number = if (notice) null else Regex("""^第 (\d+) 周$""").matchEntire(text)?.groupValues?.get(1)
    val shown = if (number == null) AnnotatedString(text) else buildAnnotatedString {
        val caption = SpanStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = palette.inkSoft)
        withStyle(caption) { append("第 ") }
        withStyle(SpanStyle(fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp,
            fontFeatureSettings = "tnum")) { append(number) }
        withStyle(caption) { append(" 周") }
    }
    Text(shown, modifier.fillMaxWidth(), textAlign = TextAlign.Start, color = MaterialTheme.colorScheme.onBackground,
        // Both states share one line height so the grid below never moves.
        style = (if (notice) MaterialTheme.typography.titleLarge else headline ?: MaterialTheme.typography.headlineMedium).copy(
            lineHeight = 44.sp, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)),
        fontWeight = if (notice) FontWeight.SemiBold else FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
