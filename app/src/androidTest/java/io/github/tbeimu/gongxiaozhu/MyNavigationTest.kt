package io.github.tbeimu.gongxiaozhu

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.data.*
import io.github.tbeimu.gongxiaozhu.network.CampusLoginService
import io.github.tbeimu.gongxiaozhu.update.UpdateDownloadService
import io.github.tbeimu.gongxiaozhu.ui.AppViewModel
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuApp
import androidx.lifecycle.ViewModelProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class MyNavigationTest {
    private val compose=createAndroidComposeRule<MainActivity>()
    private val fixture=object:ExternalResource(){
        override fun before(){createAccountRepository(InstrumentationRegistry.getInstrumentation().targetContext).apply{reset();load();settings(AppSettings(autoLogin=false))}}
        override fun after(){createAccountRepository(InstrumentationRegistry.getInstrumentation().targetContext).reset()}
    }
    @get:Rule val rules:RuleChain=RuleChain.outerRule(fixture).around(compose)

    @Test fun sixDestinationsReturnAndBottomTabsStayAvailable(){
        compose.onNodeWithText("我的",substring=false).performClick()
        compose.onNodeWithText("检查更新").assertDoesNotExist()
        compose.onNodeWithContentDescription("打开 App 自动登录").assertDoesNotExist()
        listOf("SCHEDULE","ACCOUNTS","NETWORK","APPEARANCE","UPDATES","ABOUT").forEach { key ->
            compose.onNodeWithTag("my_$key").performScrollTo().performClick()
            compose.onNodeWithText("资费",substring=false).assertExists()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("my_$key").assertExists()
        }
        compose.onNodeWithTag("my_UPDATES").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("update_card").assertExists()
        compose.onNodeWithText("我的",substring=false).performClick()
        compose.onNodeWithTag("my_UPDATES").assertExists()
    }
    @Test fun notificationIntentsOpenTheirOwnDetailsAndReturnToMine(){
        val updateRequests=mutableIntStateOf(0)
        val loginRequests=mutableIntStateOf(0)
        compose.runOnUiThread {
            val model=ViewModelProvider(compose.activity).get(AppViewModel::class.java)
            compose.activity.setContent { GongXiaoZhuApp(model,updateRequests.intValue,loginRequests.intValue) }
        }
        compose.runOnUiThread { updateRequests.intValue++ }
        compose.onNodeWithTag("update_card").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("my_UPDATES").assertExists()
        compose.runOnUiThread { loginRequests.intValue++ }
        compose.onNodeWithTag("login_button").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("my_NETWORK").assertExists()
    }
}
