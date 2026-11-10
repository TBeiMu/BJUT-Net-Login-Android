package io.github.tbeimu.gongxiaozhu.schedule

import io.github.tbeimu.gongxiaozhu.network.WifiConnection
import io.github.tbeimu.gongxiaozhu.network.WifiObservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class ScheduleUiState(val data: ScheduleData = ScheduleData(), val loading: Boolean = true,
    val busy: Boolean = false, val blocked: Boolean = false, val page: ScheduleLoginPage? = null,
    val message: String = "导入研究生课表，离线也能查看整个学期", val imported: Int = 0, val total: Int = 0)

class ScheduleController(private val scope: CoroutineScope, private val repository: ScheduleRepository,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO) {
    private val mutable = MutableStateFlow(ScheduleUiState())
    val state = mutable.asStateFlow()
    private var foreground = false
    private var wifi: WifiConnection? = null
    private var observed = false
    private var session: ScheduleSession? = null
    private var job: Job? = null
    private var generation = 0L
    private var networkTask = false

    init { reload() }
    fun enterForeground() { foreground = true }
    fun leaveForeground() { foreground = false; pause() }
    fun wifiChanged(observation: WifiObservation) {
        val value = (observation as? WifiObservation.Current)?.connection
        observed = observation is WifiObservation.Current
        if (wifi?.key != value?.key || !observed) pause("WiFi 已变化，导入暂停；连接后可重新登录继续")
        wifi = value
    }

    fun reload() {
        if (job != null) return
        runTask {
            try { val data = io { repository.load() }; mutable.value = ScheduleUiState(data, loading = false,
                imported = data.pending?.weeks?.size ?: 0, total = data.pending?.term?.weekCount ?: 0,
                message = if (data.pending != null) "上次导入尚未完成，可重新登录继续" else "课表仅保存于本机") }
            catch (_: ScheduleException) { mutable.value = ScheduleUiState(loading = false, blocked = true, message = ScheduleProblem.STORAGE.hint) }
        }
    }

    fun start() {
        if (!foreground || job != null || state.value.blocked) return
        val selected = wifi?.takeIf { observed } ?: run { message("请先连接可上网的 WiFi，再导入课表"); return }
        val factory = selected.scheduleSession ?: run { message("当前 WiFi 无法提供课表连接"); return }
        session?.close()
        val active = factory().also { session = it }
        networkTask = true
        mutable.value = state.value.copy(page = null, message = "正在打开学校统一身份认证…")
        runTask { handle(active.open(), active) }
    }

    fun submit(token: String, values: Map<String, String>) {
        val active = session ?: return
        if (!foreground || !observed || job != null || state.value.page?.token != token) return
        mutable.value = state.value.copy(message = "正在由学校验证登录…")
        runTask { handle(active.submit(token, values), active) }
    }

    suspend fun resource(token: String, url: String): ScheduleResource? {
        val active = session ?: return null
        if (!foreground || state.value.page?.token != token) return null
        val result = active.resource(token, url)
        if (result == null) {
            val path = runCatching { java.net.URI(url).path }.getOrDefault("")
            if (path.endsWith(".js") || path.endsWith(".html")) scope.launch {
                if (session === active && state.value.page?.token == token) pause("学校登录页面或校验脚本加载失败，请重新打开后再试")
            }
        }
        return result
    }

    private suspend fun handle(gate: ScheduleGate, active: ScheduleSession) {
        currentCoroutineContext().ensureActive()
        when (gate) {
            is ScheduleGate.Login -> mutable.value = state.value.copy(page = gate.page,
                message = "请在学校页面登录；密码不保存，导入结束后清除登录态")
            is ScheduleGate.Ready -> {
                currentCoroutineContext().ensureActive()
                var data = io { repository.begin(gate.identity, gate.term) }
                mutable.value = state.value.copy(data = data, page = null, imported = data.pending!!.weeks.size,
                    total = gate.term.weekCount, message = "正在导入当前学期，请保持 App 在前台")
                for (number in 1..gate.term.weekCount) {
                    if (data.pending!!.weeks.any { it.number == number }) continue
                    currentCoroutineContext().ensureActive()
                    val week = active.week(gate.term, number)
                    currentCoroutineContext().ensureActive()
                    data = io { repository.append(week) }
                    mutable.value = state.value.copy(data = data, imported = data.pending!!.weeks.size,
                        message = "已导入 ${data.pending!!.weeks.size}/${gate.term.weekCount} 周")
                }
                data = io { repository.finish(System.currentTimeMillis()) }
                mutable.value = state.value.copy(data = data, imported = gate.term.weekCount,
                    message = "整学期导入完成，可离线查看；课表来自学校")
                active.close(); if (session === active) session = null
                networkTask = false
            }
        }
    }

    fun pause(text: String = "导入已暂停；已取得的进度保留，继续时需重新登录") {
        if (session == null && !networkTask) return
        generation++
        session?.close(); session = null
        networkTask = false
        job?.cancel()
        mutable.value = state.value.copy(page = null, message = text)
    }

    /** Only invoked after the UI confirms replacement/unbinding or damaged-cache recovery. */
    fun clear() {
        val old = job
        pause()
        runTask(replace = true) {
            old?.join()
            val data = io { repository.clear() }
            mutable.value = ScheduleUiState(data, loading = false, message = "课表绑定及缓存已清除，可以重新导入")
        }
    }
    fun unsupportedWebView() { pause(ScheduleProblem.WEBVIEW.hint) }
    fun message(text: String) { mutable.value = state.value.copy(message = text) }

    private fun runTask(replace: Boolean = false, action: suspend () -> Unit) {
        if (!replace && job != null) return
        val token = ++generation
        mutable.value = state.value.copy(busy = true)
        job = scope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: ScheduleException) {
                if (generation == token) {
                    session?.close(); session = null
                    networkTask = false
                    mutable.value = state.value.copy(page = null, message = error.problem.hint,
                        blocked = error.problem == ScheduleProblem.STORAGE)
                }
            } catch (_: Exception) {
                if (generation == token) { session?.close(); session = null; networkTask = false
                    mutable.value = state.value.copy(page = null, message = ScheduleProblem.CONNECTION.hint) }
            }
        }.also { owned -> owned.invokeOnCompletion {
            scope.launch {
                if (job === owned) {
                    job = null
                    val data = if (state.value.blocked) state.value.data else runCatching { repository.snapshot() }.getOrDefault(state.value.data)
                    mutable.value = state.value.copy(busy = false, loading = false, data = data)
                }
            }
        } }
        job?.start()
    }
    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { block() }
}

fun currentScheduleWeek(schedule: SemesterSchedule, today: LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))): Int? =
    schedule.weeks.firstOrNull { today.toString() in it.dates }?.number
