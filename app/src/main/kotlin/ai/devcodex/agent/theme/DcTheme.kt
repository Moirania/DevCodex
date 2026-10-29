package ai.devcodex.agent.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class ColorMode { SYSTEM, LIGHT, DARK }

data class ThemePrefs(
    val presetId: String = "claude",
    val colorMode: ColorMode = ColorMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val amoled: Boolean = false,
    val appFont: String = "gsf",      // gsf | system
    val chatFont: String = "gsf",     // gsf | mono | system
)

private val AMOLED_BG = Color(0xFF000000)

/** 统一圆角体系：卡片、输入框、对话框、芯片共用同一组半径 */
val DcShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun DcTheme(
    prefs: ThemePrefs,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (prefs.colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val base: ColorScheme = if (prefs.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        findPresetTheme(prefs.presetId).scheme(darkTheme)
    }
    val scheme = if (darkTheme && prefs.amoled) {
        base.copy(
            background = AMOLED_BG,
            surface = AMOLED_BG,
            surfaceContainerLowest = AMOLED_BG,
            surfaceContainerLow = Color(0xFF0A0A0A),
        )
    } else base

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = dcTypography(appFont = prefs.appFont, chatFont = prefs.chatFont),
        shapes = DcShapes,
        content = content,
    )
}
