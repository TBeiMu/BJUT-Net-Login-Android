package io.github.tbeimu.gongxiaozhu.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import java.time.LocalDateTime
import java.time.ZoneId

private val MyGreetings = listOf(
    listOf("世界安静下来，梦还在赶路。", "愿你熬过的夜，都有星光回应。", "夜很深了，先把心事放一放，好好休息。"),
    listOf("新的一天，从第一缕光开始。", "把昨天归零，把今天点亮。", "早起的人，先拥抱今天的风。"),
    listOf("把热爱放进日程，把行动交给现在。", "专注当下，答案会在路上。", "上午适合出发，也适合把小事做好。"),
    listOf("忙里偷闲，也要好好吃饭。", "太阳正好，给自己一点松弛。", "暂停一下，是为了下午走得更稳。"),
    listOf("保持热爱，奔赴山河。", "一半清醒，一半温柔。", "慢慢来，下午的光也在替你加油。"),
    listOf("落日归山海，烟火向星辰。", "今天辛苦了，晚风会替你松绑。", "把疲惫留在黄昏，把温柔带回家。"),
    listOf("把烦恼交给夜色，把期待留给明天。", "灯亮着，生活就有回声。", "夜晚适合收心，也适合悄悄积蓄力量。"),
    listOf("别急，月亮也在慢慢变圆。", "好好休息，明天继续发光。", "今天到此为止，明天重新开始。"),
)

/** One phrase per Shanghai date and time period, stable across recomposition and recreation. */
internal fun myGreeting(moment: LocalDateTime): String {
    val period = when (moment.hour) {
        in 0..4 -> 0
        in 5..7 -> 1
        in 8..10 -> 2
        in 11..12 -> 3
        in 13..16 -> 4
        in 17..18 -> 5
        in 19..22 -> 6
        else -> 7
    }
    val phrases = MyGreetings[period]
    return phrases[Math.floorMod(moment.toLocalDate().toEpochDay(), phrases.size.toLong()).toInt()]
}

internal fun myProfileTitle(state: AppUiState): String {
    val account = state.data.defaultAccount?.takeUnless { state.loading || state.storageBlocked }
    if (account != null) return account.label.ifBlank { account.username }
    val identity = state.schedule.data.identity?.takeUnless { state.schedule.loading || state.schedule.blocked }
    return identity?.name?.ifBlank { identity.studentId } ?: "工小助"
}

internal fun myGreetingNow(): LocalDateTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))

/** Read a single date/time snapshot on entry and system clock events, only while visible. */
@Composable
internal fun rememberMyGreetingMoment(now: () -> LocalDateTime): State<LocalDateTime> {
    val context = LocalContext.current
    val provider by rememberUpdatedState(now)
    val moment = remember { mutableStateOf(now()) }
    LifecycleStartEffect(context) {
        moment.value = provider()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { moment.value = provider() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onStopOrDispose { context.unregisterReceiver(receiver) }
    }
    return moment
}
