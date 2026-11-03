package io.github.tbeimu.gongxiaozhu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.tbeimu.gongxiaozhu.data.createAccountRepository
import io.github.tbeimu.gongxiaozhu.network.WifiMonitor
import io.github.tbeimu.gongxiaozhu.ui.AppViewModel
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuApp

class MainActivity : ComponentActivity() {
    private lateinit var model: AppViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(AppViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return AppViewModel(createAccountRepository(applicationContext), WifiMonitor(applicationContext)) as T
            }
        }
        model = ViewModelProvider(this, factory)[AppViewModel::class.java]
        setContent { GongXiaoZhuApp(model) }
    }

    override fun onStart() {
        super.onStart()
        model.enterForeground()
    }

    override fun onStop() {
        // Rotation retains the same foreground entry and ViewModel, so it cannot auto-retry.
        if (!isChangingConfigurations) model.leaveForeground()
        super.onStop()
    }
}
