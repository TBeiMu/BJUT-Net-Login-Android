package io.github.tbeimu.gongxiaozhu

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsEnabled
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.data.AndroidVaultStore
import io.github.tbeimu.gongxiaozhu.data.createAccountRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageBlockedUpdateTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val corrupt = "fictional corrupt vault".toByteArray()
    private val fixture = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName.endsWith(".debug"))
            createAccountRepository(context).reset()
            AndroidVaultStore(context).write(corrupt)
        }
        override fun after() { createAccountRepository(InstrumentationRegistry.getInstrumentation().targetContext).reset() }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(compose)

    @Test fun corruptedVaultKeepsUpdaterAccessibleWithoutExposingAccountSettings() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("账号存储暂不可用")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("update_card").assertExists()
        compose.onNodeWithText("打开发布页").assertIsEnabled()
        compose.onNodeWithContentDescription("打开 App 自动登录").assertDoesNotExist()
        compose.onNodeWithText("网络模式", substring = false).assertDoesNotExist()
        compose.onNodeWithText("首页").performClick()
        compose.onNodeWithText("账号存储暂不可用").assertExists()
        assertArrayEquals(corrupt, AndroidVaultStore(compose.activity).read())
    }
}
