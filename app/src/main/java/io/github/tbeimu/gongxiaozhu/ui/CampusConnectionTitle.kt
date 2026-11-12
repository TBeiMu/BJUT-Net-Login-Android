package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
        headline = headline, restingTitle = weekTitle)
}

@Composable
private fun TitleText(text: String, notice: Boolean, modifier: Modifier = Modifier, headline: TextStyle? = null,
    restingTitle: String) {
    val palette = gxz
    // Editorial setting: the week number is the headline, “第” and “周” are its captions.
    // The text stays exactly “第 N 周”; only its spans are styled.
    val number = Regex("""^第 (\d+) 周$""").matchEntire(restingTitle)?.groupValues?.get(1)
    val resting = if (number == null) AnnotatedString(restingTitle) else buildAnnotatedString {
        val caption = SpanStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = palette.inkSoft)
        withStyle(caption) { append("第 ") }
        withStyle(SpanStyle(fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp,
            fontFeatureSettings = "tnum")) { append(number) }
        withStyle(caption) { append(" 周") }
    }
    val lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    val restingStyle = LocalTextStyle.current.merge(headline ?: MaterialTheme.typography.headlineMedium).copy(
        fontWeight = FontWeight.Bold, lineHeight = 44.sp, lineHeightStyle = lineHeightStyle)
    val noticeStyle = LocalTextStyle.current.merge(MaterialTheme.typography.titleLarge).copy(
        fontWeight = FontWeight.SemiBold, lineHeight = 44.sp, lineHeightStyle = lineHeightStyle)
    val measurer = rememberTextMeasurer()
    // Mixed 17/38sp spans can exceed the requested line height. Reserve the actual largest
    // text height in either state, including font scaling and CJK/Latin fallback metrics.
    val slotHeight = maxOf(
        measurer.measure(resting, restingStyle, maxLines = 1).size.height,
        measurer.measure("正在登录校园网", noticeStyle, maxLines = 1).size.height,
        measurer.measure("未连接WiFi", noticeStyle, maxLines = 1).size.height,
    )
    Box(Modifier.fillMaxWidth().height(with(LocalDensity.current) { slotHeight.toDp() }), Alignment.CenterStart) {
        Text(if (notice) AnnotatedString(text) else resting, modifier.fillMaxWidth(), textAlign = TextAlign.Start,
            color = MaterialTheme.colorScheme.onBackground, style = if (notice) noticeStyle else restingStyle,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
