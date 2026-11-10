package io.github.tbeimu.gongxiaozhu.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.time.LocalDate

/** Date is UI state: neither returning to the app nor midnight requires reimporting courses. */
@Composable
internal fun rememberScheduleDate(today: () -> LocalDate): MutableState<LocalDate> {
    val context = LocalContext.current
    val currentProvider by rememberUpdatedState(today)
    val date = remember { mutableStateOf(today()) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { date.value = currentProvider() }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { date.value = currentProvider() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            // Shanghai midnight may differ from the device's local midnight.
            addAction(Intent.ACTION_TIME_TICK)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return date
}
