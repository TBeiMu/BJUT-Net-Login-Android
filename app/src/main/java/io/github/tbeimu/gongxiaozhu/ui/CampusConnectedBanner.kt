package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.tbeimu.gongxiaozhu.network.CampusConnectionNotice
import io.github.tbeimu.gongxiaozhu.network.LoginUiState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

@Composable
internal fun BoxScope.CampusConnectedBanner(notice: CampusConnectionNotice, login: LoginUiState, unobstructed: Boolean) {
    val owner = LocalLifecycleOwner.current
    var displayed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(owner, unobstructed) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (unobstructed) notice.pending.collect { id ->
                if (id != null && notice.consume(id)) displayed = id
            } else awaitCancellation()
        }
    }
    LaunchedEffect(displayed) { if (displayed != null) { delay(2000); displayed = null } }
    LaunchedEffect(login.taskId, login.running, login.wifiConnected) {
        if (login.taskId != displayed || login.running || !login.wifiConnected) displayed = null
    }
    // A consumed result is not replayed after the destination or Activity is recreated.
    AnimatedVisibility(visible = displayed != null && unobstructed, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 4.dp) {
            Row(Modifier.testTag("campus_connected_banner").padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LineGlyph(AppGlyph.WIFI, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("校园网已连接", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
