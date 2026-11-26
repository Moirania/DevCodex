package ai.devcodex.agent

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.devcodex.agent.theme.ColorMode
import ai.devcodex.agent.theme.DcTheme
import ai.devcodex.agent.theme.ThemePrefs
import ai.devcodex.agent.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // SDK 29+ 关闭导航栏对比度强制（edge-to-edge skill 规范）
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            val vm: AppViewModel = viewModel(factory = AppViewModel.FACTORY)
            val settings by vm.settings.collectAsState(initial = ai.devcodex.agent.data.AppSettings())
            DcTheme(prefs = ThemePrefs(
                presetId2 = settings.theme.presetId2,
                colorMode = runCatching { ColorMode.valueOf(settings.theme.colorMode) }.getOrDefault(ColorMode.SYSTEM),
                dynamicColor = settings.theme.dynamicColor,
                amoled = settings.theme.amoled,
                appFont = settings.theme.appFont,
                chatFont = settings.theme.chatFont,
            )) {
                AppRoot(vm)
            }
        }
    }
}
