package io.github.tbeimu.gongxiaozhu.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.tbeimu.gongxiaozhu.billing.BillingMetric
import io.github.tbeimu.gongxiaozhu.billing.BillingSnapshot
import io.github.tbeimu.gongxiaozhu.billing.BillingUiState
import io.github.tbeimu.gongxiaozhu.billing.OnlineDevice
import io.github.tbeimu.gongxiaozhu.data.AccountState
import io.github.tbeimu.gongxiaozhu.data.AccountSummary
import io.github.tbeimu.gongxiaozhu.network.CampusLoginRuntime
import io.github.tbeimu.gongxiaozhu.network.LoginUiState
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleCourse
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleData
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleIdentity
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleNotice
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleTerm
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleUiState
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleWeek
import io.github.tbeimu.gongxiaozhu.schedule.SemesterSchedule
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-only design review: the real screens with fictional data, so the layout can be checked
 * without a school account or campus WiFi. Nothing here is read from or written to the vaults,
 * and buttons that would contact the school do nothing. Not part of the release build.
 *
 * `adb shell am start -n io.github.tbeimu.gongxiaozhu.debug/io.github.tbeimu.gongxiaozhu.ui.ShowcaseActivity`
 * Optional extras: `--es tab BILLING|MY`, `--es page NETWORK|ACCOUNTS|…`.
 */
class ShowcaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The view model only supplies callbacks for detail pages; its state is never shown here.
        val model by lazy { ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(CampusLoginRuntime.get(applicationContext)) as T
        })[AppViewModel::class.java] }
        val firstTab = Tab.entries.firstOrNull { it.name == intent.getStringExtra("tab") } ?: Tab.SCHEDULE
        val firstPage = MyDestination.entries.firstOrNull { it.name == intent.getStringExtra("page") }
        val today = LocalDate.of(2026, 11, 11)
        // `--ez empty true` shows the first-run screens instead of the invented term.
        val state = if (intent.getBooleanExtra("empty", false)) AppUiState(loading = false, schedule = ScheduleUiState(loading = false))
            else showcaseState(today)
        // `--ez brand true` shows the mark and its animations on their own, for review and recording.
        if (intent.getBooleanExtra("brand", false)) {
            setContent { GongXiaoZhuTheme { ShowcaseFont { BrandDemo() } } }
            return
        }
        setContent {
            GongXiaoZhuTheme { ShowcaseFont {
                var tab by rememberSaveable { mutableStateOf(if (firstPage != null) Tab.MY else firstTab) }
                var destination by rememberSaveable { mutableStateOf(firstPage) }
                val snackbar = remember { SnackbarHostState() }
                val wallpaper = rememberWallpaper()
                val updateActions = rememberUpdateActions()
                BackHandler(enabled = destination != null) { destination = null }
                // 刷新资费 pretends to ask the school: shimmer for a moment, then the figures roll to new values.
                var demo by remember { mutableStateOf(state) }
                val scope = rememberCoroutineScope()
                fun refresh() = scope.launch {
                    demo = demo.copy(billing = demo.billing.copy(running = true, message = "正在查询资费…"))
                    delay(1600)
                    val metrics = demo.billing.snapshot?.metrics.orEmpty().mapValues { (key, metric) ->
                        when (key) { "已用流量" -> BillingMetric("39.07", "GB"); "可用流量" -> BillingMetric("80.93", "GB")
                            "账户余额" -> BillingMetric("21.85", "元"); else -> metric }
                    }
                    demo = demo.copy(billing = demo.billing.copy(running = false, message = "",
                        snapshot = demo.billing.snapshot?.copy(metrics = metrics)))
                }
                AppShell(tab, { tab = it; destination = null }, wallpaper, snackbar) { padding ->
                  ScreenHost(Screen(tab, destination)) { screen ->
                    val page = screen.destination
                    if (page != null) MyDetailScreen(page, demo, Modifier.padding(padding), model, wallpaper,
                        onBack = { destination = null }, onResetAccounts = {}, onResetSchedule = {}, onDelete = {},
                        updateActions = updateActions)
                    else when (screen.tab) {
                        Tab.SCHEDULE -> Box(Modifier.padding(padding).fillMaxSize()) {
                            ScheduleHome(demo.schedule, Modifier, onImport = {}, onPause = {}, onRetry = {},
                                onMine = { tab = Tab.MY; destination = MyDestination.SCHEDULE }, todayProvider = { today },
                                nowProvider = { java.time.LocalTime.of(14, 32) })
                        }
                        Tab.BILLING -> BillingScreen(demo, Modifier.padding(padding),
                            onAccounts = { tab = Tab.MY; destination = MyDestination.ACCOUNTS }, onQuery = { refresh() }, onOffline = {})
                        Tab.MY -> MyHomeScreen(demo, Modifier.padding(padding)) { destination = it }
                    }
                  }
                }
            } }
        }
    }

    companion object {
        /** JVM previews have no CJK system font; the preview test may supply one. Null on devices. */
        var fontFamily: FontFamily? = null
    }
}

@Composable
private fun ShowcaseFont(content: @Composable () -> Unit) {
    val family = ShowcaseActivity.fontFamily ?: return content()
    val t = MaterialTheme.typography
    fun TextStyle.f() = copy(fontFamily = family)
    MaterialTheme(typography = Typography(t.displayLarge.f(), t.displayMedium.f(), t.displaySmall.f(), t.headlineLarge.f(),
        t.headlineMedium.f(), t.headlineSmall.f(), t.titleLarge.f(), t.titleMedium.f(), t.titleSmall.f(), t.bodyLarge.f(),
        t.bodyMedium.f(), t.bodySmall.f(), t.labelLarge.f(), t.labelMedium.f(), t.labelSmall.f())) {
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.f(), content = content)
    }
}

/** The three motions of the mark, side by side, on one finite clock so they can be recorded frame by frame. */
@Composable
private fun BrandDemo() {
    val total = 7200f
    val clock = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        clock.animateTo(total, androidx.compose.animation.core.tween(total.toInt(), easing = androidx.compose.animation.core.LinearEasing))
    }
    val t = clock.value
    val paper = androidx.compose.ui.graphics.Color(0xFFFBFAF8); val ink = androidx.compose.ui.graphics.Color(0xFF1B1D22)
    val night = androidx.compose.ui.graphics.Color(0xFF16181C); val snow = androidx.compose.ui.graphics.Color(0xFFECEDEF)
    val red = androidx.compose.ui.graphics.Color(0xFFE5482E); val redNight = androidx.compose.ui.graphics.Color(0xFFFF6E52)
    @Composable fun Panel(label: String, motion: MarkMotion, time: Float, dark: Boolean, size: Float, modifier: Modifier) {
        Box(modifier.background(if (dark) night else paper), contentAlignment = androidx.compose.ui.Alignment.Center) {
            BrandMark(Modifier.size(androidx.compose.ui.unit.Dp(size)), motion, time, if (dark) snow else ink, if (dark) redNight else red)
            androidx.compose.material3.Text(label, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = androidx.compose.ui.unit.Dp(14f)),
                color = if (dark) androidx.compose.ui.graphics.Color(0xFF8D919A) else androidx.compose.ui.graphics.Color(0xFF8D919A),
                fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp))
        }
    }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        // The opening replays every 2.4 s so it can be watched more than once.
        Panel("开屏 · 一周凝成一个字", MarkMotion.WEEK, t % 2400f, false, 230f, Modifier.weight(1.3f).fillMaxSize())
        androidx.compose.foundation.layout.Row(Modifier.weight(1f).fillMaxSize()) {
            Panel("登录校园网 · 信号变成工", MarkMotion.SIGNAL, t, false, 150f, Modifier.weight(1f).fillMaxSize())
            Panel("加载 · 此刻弹跳", MarkMotion.BOUNCE, t, true, 150f, Modifier.weight(1f).fillMaxSize())
        }
    }
}

private class Slot(val id: String, val name: String, val day: Int, val lessons: IntRange, val room: String,
    val teacher: String, val weeks: IntRange)

/** An invented graduate term: eighteen weeks from 31 August 2026, people and rooms made up. */
private fun showcaseState(today: LocalDate): AppUiState {
    val slots = listOf(
        Slot("ml", "机器学习", 0, 1..2, "四教 301", "周明远", 1..16),
        Slot("matrix", "矩阵分析", 0, 5..6, "三教 208", "韩立群", 1..12),
        Slot("algo", "高级算法设计与分析", 1, 3..4, "四教 105", "沈知行", 1..16),
        Slot("english", "学术英语写作", 1, 7..8, "一教 412", "Emily Carter", 1..14),
        Slot("vision", "计算机视觉", 2, 1..2, "信息楼 B203", "陆景行", 3..18),
        Slot("theory", "中国特色社会主义理论与实践", 2, 5..7, "三教 101", "郑文博", 1..12),
        Slot("matrix", "矩阵分析", 3, 3..4, "三教 208", "韩立群", 1..12),
        Slot("nlp", "自然语言处理", 3, 9..11, "四教 410", "宋清和", 2..17),
        Slot("ml", "机器学习", 4, 3..4, "四教 301", "周明远", 1..16),
        Slot("seminar", "课题组学术研讨", 4, 7..8, "信息楼 B506", "导师组", 1..18),
        Slot("badminton", "羽毛球", 5, 3..4, "奥林匹克体育馆", "体育部", 5..14),
        // One clash in week 12 to show how overlapping courses are presented.
        Slot("optimize", "最优化方法", 1, 7..8, "三教 305", "顾北辰", 12..12),
    )
    val first = LocalDate.of(2026, 8, 31)
    val weeks = (1..18).map { number ->
        val dates = (0..6).map { first.plusWeeks(number - 1L).plusDays(it.toLong()).toString() }
        ScheduleWeek(number, dates, slots.filter { number in it.weeks }.map {
            ScheduleCourse(it.id, it.name, dates[it.day], it.lessons.toList(), it.room, it.teacher,
                weeksText = "${it.weeks.first}-${it.weeks.last} 周")
        })
    }
    val identity = ScheduleIdentity("S202673042", "林知夏")
    val term = ScheduleTerm("2026-2027", "1", 18, 11)
    val updated = today.atTime(8, 12).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
    val schedule = SemesterSchedule(identity, term, weeks, updated)
    val accounts = listOf(AccountSummary("demo-1", "宿舍宽带", "S202673042"), AccountSummary("demo-2", "实验室", "B202611007"))
    val billing = BillingSnapshot(
        metrics = mapOf("已用流量" to BillingMetric("38.62", "GB"), "可用流量" to BillingMetric("81.38", "GB"),
            "消费保护" to BillingMetric("50.00", "元"), "账户余额" to BillingMetric("23.60", "元")),
        devices = listOf(
            OnlineDevice("demo-a", "10.21.137.64", "", "3C:22:FB:8A:10:5E", "2026-11-11 07:42:18", 512, BillingMetric("1286.420", "MB")),
            OnlineDevice("demo-b", "10.21.140.9", "2001:da8:216:21a4::5c7", "A4:83:E7:2F:9B:C1", "2026-11-10 22:05:51", 1089, BillingMetric("4312.905", "MB")),
        ))
    return AppUiState(
        data = AccountState(accounts, "demo-1"), loading = false,
        login = LoginUiState(message = "校园网已连接，可以正常上网", wifiConnected = true, completed = true),
        billing = BillingUiState(snapshot = billing, loadedAt = updated),
        schedule = ScheduleUiState(ScheduleData(identity = identity, complete = schedule), loading = false,
            message = "整学期导入完成", notice = ScheduleNotice.SUCCESS),
    )
}
