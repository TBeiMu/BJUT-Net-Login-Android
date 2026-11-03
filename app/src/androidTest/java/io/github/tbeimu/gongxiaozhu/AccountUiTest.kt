package io.github.tbeimu.gongxiaozhu

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.data.createAccountRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

@RunWith(AndroidJUnit4::class)
class AccountUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val cleanStorage = object : ExternalResource() {
        override fun before() { createAccountRepository(InstrumentationRegistry.getInstrumentation().targetContext).reset() }
        override fun after() { createAccountRepository(InstrumentationRegistry.getInstrumentation().targetContext).reset() }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(cleanStorage).around(compose)

    @Test fun addingEditingAndRestoringDefaultAccount() {
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("添加校园网账号")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("添加校园网账号").performClick()
        compose.onNodeWithTag("account_label").performTextInput("模拟测试账号")
        compose.onNodeWithTag("account_username").performTextInput("ui-demo-only")
        compose.onNodeWithTag("account_password").performTextInput("demo-only-password")
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        waitForEditorClosed()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("模拟测试账号")).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("模拟测试账号")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("模拟测试账号").assertExists()
        compose.onNodeWithText("账号", substring = false).performClick()
        compose.onNodeWithText("默认").assertExists()
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithTag("account_username").assertTextContains("ui-demo-only")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("确认删除", substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("还没有账号")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun selectingAnotherDefaultAndPersistingSettings() {
        waitFor("添加校园网账号")
        add("第一测试账号", "ui-first-demo")
        compose.onNodeWithText("账号", substring = false).performClick()
        compose.onNodeWithText("添加账号").performClick()
        fillAndSave("第二测试账号", "ui-second-demo")
        waitFor("第二测试账号")
        compose.onNode(hasText("设为默认") and isEnabled()).performClick()
        compose.waitUntil(10_000) {
            createAccountRepository(compose.activity).load().defaultAccount?.label == "第二测试账号"
        }
        compose.onNodeWithText("设置", substring = false).performClick()
        compose.onNodeWithContentDescription("打开 App 自动登录").assertIsOn().performClick()
        compose.waitUntil(10_000) { !createAccountRepository(compose.activity).load().settings.autoLogin }
        compose.onNodeWithContentDescription("网络模式：宿舍 WiFi").performClick()
        compose.waitUntil(10_000) {
            createAccountRepository(compose.activity).load().settings.networkMode == io.github.tbeimu.gongxiaozhu.data.NetworkMode.DORM_WIFI
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("打开 App 自动登录").assertIsOff()
        compose.onNodeWithContentDescription("网络模式：宿舍 WiFi").assertIsSelected()
        compose.onNodeWithText("首页", substring = false).performClick()
        compose.onNodeWithText("第二测试账号").assertExists()
    }

    private fun waitFor(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun add(label: String, username: String) {
        compose.onNodeWithText("添加校园网账号").performClick()
        fillAndSave(label, username)
        waitFor(label)
    }

    private fun fillAndSave(label: String, username: String) {
        compose.onNodeWithTag("account_label").performTextInput(label)
        compose.onNodeWithTag("account_username").performTextInput(username)
        compose.onNodeWithTag("account_password").performTextInput("demo-only-password")
        compose.onNodeWithText("保存").performClick()
        waitForEditorClosed()
    }

    private fun waitForEditorClosed() = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag("account_password")).fetchSemanticsNodes().isEmpty()
    }
}
