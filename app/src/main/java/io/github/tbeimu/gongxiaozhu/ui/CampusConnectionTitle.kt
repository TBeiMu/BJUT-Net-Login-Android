package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tbeimu.gongxiaozhu.network.CampusConnectionNotice
import io.github.tbeimu.gongxiaozhu.network.CampusTitlePhase

@Composable
internal fun CampusConnectionTitle(weekTitle: String, notice: CampusConnectionNotice?, unobstructed: Boolean) {
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
    // Status replaces the old text in the same frame. A fading old CONNECTING/TIMED_OUT label
    // must not remain visible after the runtime has already confirmed success.
    Text(when (phase) {
        CampusTitlePhase.CONNECTING -> "正在登录校园网"
        CampusTitlePhase.CONNECTED -> "校园网已连接"
        CampusTitlePhase.TIMED_OUT -> "登录超时"
        CampusTitlePhase.NO_WIFI -> "未连接WiFi"
        null -> weekTitle
    }, Modifier.fillMaxWidth().then(if (phase != null) Modifier.testTag("campus_connection_title") else Modifier),
        textAlign = TextAlign.Start, color = MaterialTheme.colorScheme.onBackground,
        style = if (phase == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
