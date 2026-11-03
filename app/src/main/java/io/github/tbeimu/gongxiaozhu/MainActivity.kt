package io.github.tbeimu.gongxiaozhu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tbeimu.gongxiaozhu.data.createAccountRepository
import io.github.tbeimu.gongxiaozhu.ui.AppViewModel
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(AppViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return AppViewModel(createAccountRepository(applicationContext)) as T
            }
        }
        setContent {
            val model: AppViewModel = viewModel(factory = factory)
            GongXiaoZhuApp(model)
        }
    }
}
