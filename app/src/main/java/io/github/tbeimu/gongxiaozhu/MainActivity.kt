package io.github.tbeimu.gongxiaozhu

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import io.github.tbeimu.gongxiaozhu.update.UpdateDownloadService
import io.github.tbeimu.gongxiaozhu.update.UpdateRuntime
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.tbeimu.gongxiaozhu.network.CampusLoginRuntime
import io.github.tbeimu.gongxiaozhu.network.CampusLoginService
import io.github.tbeimu.gongxiaozhu.ui.AppViewModel
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuApp

class MainActivity : ComponentActivity() {
    private lateinit var model: AppViewModel
    private var openUpdatesRequest by mutableIntStateOf(0)
    private var openLoginRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(AppViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return AppViewModel(CampusLoginRuntime.get(applicationContext)) as T
            }
        }
        model = ViewModelProvider(this, factory)[AppViewModel::class.java]
        handleUpdateIntent(intent)
        setContent { GongXiaoZhuApp(model, openUpdatesRequest, openLoginRequest) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(CampusLoginService.OPEN_LOGIN, false) == true) {
            openLoginRequest++
            intent.removeExtra(CampusLoginService.OPEN_LOGIN)
        }
        if (intent?.getBooleanExtra(UpdateDownloadService.OPEN_UPDATES, false) == true) {
            openUpdatesRequest++
            intent.removeExtra(UpdateDownloadService.OPEN_UPDATES)
        }
    }

    override fun onStart() {
        super.onStart()
        model.enterForeground()
    }

    override fun onStop() {
        // Rotation retains the same foreground entry and ViewModel, so it cannot auto-retry.
        if (!isChangingConfigurations) {
            model.leaveForeground()
            UpdateRuntime.get(this).repository.leaveForeground()
        }
        super.onStop()
    }
}
