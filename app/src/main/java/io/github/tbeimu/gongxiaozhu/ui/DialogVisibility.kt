package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** Local dialogs defer a global update prompt; disposing a page releases its blocker. */
@Composable
internal fun ReportDialogVisibility(visible: Boolean, onChanged: (Boolean) -> Unit) {
    val currentCallback by rememberUpdatedState(onChanged)
    DisposableEffect(visible) {
        currentCallback(visible)
        onDispose { currentCallback(false) }
    }
}
