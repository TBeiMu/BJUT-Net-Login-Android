package io.github.tbeimu.gongxiaozhu.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Debug-only fixture: system wallpaper changes can recreate the Activity on newer Android. */
class WallpaperTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { scene?.invoke() }
    }

    companion object {
        var scene: (@Composable () -> Unit)? by mutableStateOf(null)
    }
}
