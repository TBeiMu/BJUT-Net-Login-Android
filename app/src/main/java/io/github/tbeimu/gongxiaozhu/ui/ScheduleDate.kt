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
import java.time.LocalTime
import java.time.ZoneId

/** Shanghai wall-clock time, to the minute: the timetable's "now". */
internal fun scheduleNow(): LocalTime = LocalTime.now(ZoneId.of("Asia/Shanghai")).withSecond(0).withNano(0)

/** The current minute, refreshed by the system's minute tick and on return to the app; no polling loop. */
@Composable
internal fun rememberScheduleTime(now: () -> LocalTime): State<LocalTime> {
    val context = LocalContext.current
    val currentProvider by rememberUpdatedState(now)
    val time = remember { mutableStateOf(now()) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { time.value = currentProvider() }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { time.value = currentProvider() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return time
}

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
