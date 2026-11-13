package io.github.tbeimu.gongxiaozhu

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.AppSettings
import io.github.tbeimu.gongxiaozhu.data.createAccountRepository
import io.github.tbeimu.gongxiaozhu.network.CampusLoginRuntime
import io.github.tbeimu.gongxiaozhu.schedule.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Only the isolated debug package, with fictional identities and no live credential submission. */
class AccountNameUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var runtime: CampusLoginRuntime
    private var scenario: ActivityScenario<MainActivity>? = null
    private val identity = ScheduleIdentity("fictional-name-user", "测试姓名")
    private val term = ScheduleTerm("2026-2027", "1", 1, 1)

    @Before fun prepare() {
        check(context.packageName.endsWith(".debug"))
        instrumentation.runOnMainSync {
            runtime = CampusLoginRuntime.get(context)
            runtime.accountNames.storageAvailable(false)
            runtime.scheduleImport.controller.pause()
        }
        waitUntil { !runtime.scheduleImport.controller.state.value.busy }
        instrumentation.runOnMainSync { runtime.scheduleImport.controller.clear() }
        waitUntil { !runtime.scheduleImport.controller.state.value.busy }
        runBlocking { withContext(Dispatchers.IO) {
            runtime.accounts.reset(); runtime.accounts.load(); runtime.accounts.settings(AppSettings(autoLogin = false))
        } }
    }

    @After fun cleanup() {
        scenario?.close()
        instrumentation.runOnMainSync {
            runtime.accountNames.storageAvailable(false)
            runtime.scheduleImport.controller.pause()
        }
        waitUntil { !runtime.scheduleImport.controller.state.value.busy }
        instrumentation.runOnMainSync { runtime.scheduleImport.controller.clear() }
        waitUntil { !runtime.scheduleImport.controller.state.value.busy }
        runBlocking { withContext(Dispatchers.IO) { runtime.accounts.reset() } }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 10_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(20)
        assertTrue("等待姓名补全状态超时", condition())
    }

    @Test fun firstSaveUsesImportedNameAndSurvivesRecreation() {
        createScheduleRepository(context).apply { load(); begin(identity, term) }
        reloadScheduleFixture(context)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithTag("my_ACCOUNTS").performClick()
        compose.onNodeWithText("添加账号", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("account_username").performTextInput(identity.studentId)
        compose.onNodeWithTag("account_password").performTextInput("fictional-password")
        compose.onNodeWithText("保存", substring = false).performClick()
        waitUntil { runtime.accounts.state.value?.defaultAccount?.label == identity.name }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(identity.name, substring = false)).fetchSemanticsNodes().isNotEmpty() }
        scenario!!.recreate()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(identity.name, substring = false)).fetchSemanticsNodes().isNotEmpty() }
        val restored = createAccountRepository(context).load()
        assertEquals(identity.name, restored.defaultAccount!!.label)
        assertFalse(restored.defaultAccount!!.nameLookupPending)
    }

    @Test fun laterImportUpdatesAccountLabelWithoutChangingCredentialsOrDefault() {
        val account = runBlocking { withContext(Dispatchers.IO) {
            val account = runtime.accounts.save(AccountDraft(label = "", username = identity.studentId,
                password = "fictional-password")).defaultAccount!!
            runtime.accounts.beginNameLookup(account.id) // Simulate an already consumed, unsuccessful first attempt.
            account
        } }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitUntil { !runtime.scheduleImport.controller.state.value.loading }
        instrumentation.runOnMainSync {
            runtime.scheduleImport.controller.networkChanged(ScheduleConnection("fictional-import", ScheduleNetworkKind.WIFI) {
                object : ScheduleSession {
                    override suspend fun open(): ScheduleGate = ScheduleGate.Ready(identity, term)
                    override suspend fun submit(token: String, values: Map<String, String>): ScheduleGate = error("Unused")
                    override suspend fun week(term: ScheduleTerm, number: Int) = ScheduleWeek(1,
                        (0L..6L).map { java.time.LocalDate.of(2026, 11, 2).plusDays(it).toString() }, emptyList())
                    override suspend fun resource(token: String, url: String): ScheduleResource? = null
                    override fun close() {}
                }
            })
            runtime.scheduleImport.controller.start()
        }
        waitUntil { runtime.accounts.state.value?.defaultAccount?.label == identity.name }
        assertEquals(account.id, runtime.accounts.state.value!!.defaultAccountId)
        assertEquals("fictional-password", runtime.accounts.edit(account.id).password)
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithTag("my_ACCOUNTS").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(identity.name, substring = false)).fetchSemanticsNodes().isNotEmpty() }
    }
}
