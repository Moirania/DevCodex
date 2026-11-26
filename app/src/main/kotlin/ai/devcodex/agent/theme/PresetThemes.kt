package ai.devcodex.agent.theme

import androidx.compose.material3.ColorScheme
import ai.devcodex.agent.theme.presets.AutumnThemePreset
import ai.devcodex.agent.theme.presets.OperitPaperTheme
import ai.devcodex.agent.theme.presets.BlackThemePreset
import ai.devcodex.agent.theme.presets.ClaudeThemePreset
import ai.devcodex.agent.theme.presets.MinimalThemePreset
import ai.devcodex.agent.theme.presets.OceanThemePreset
import ai.devcodex.agent.theme.presets.SakuraThemePreset
import ai.devcodex.agent.theme.presets.SpringThemePreset
import ai.devcodex.agent.theme.presets.MiyouOrangeTheme
import ai.devcodex.agent.theme.presets.PureWhiteTheme
import ai.devcodex.agent.theme.presets.ClassicGreenTheme
import ai.devcodex.agent.theme.presets.ClassicPurpleTheme
import ai.devcodex.agent.theme.presets.MikuGreenTheme
import ai.devcodex.agent.theme.presets.TetoRedTheme
import ai.devcodex.agent.theme.presets.DeepBlueTheme
import ai.devcodex.agent.theme.presets.SilenceGreenTheme

/** 主题预设（色值移植自 rikkahub，遵循其 Apache-2.0 许可） */
data class PresetTheme(
    val id: String,
    val name: String,
    val light: ColorScheme,
    val dark: ColorScheme,
) {
    fun scheme(useDark: Boolean): ColorScheme = if (useDark) dark else light
}

val PresetThemes = listOf(
    OperitPaperTheme,
    SakuraThemePreset,
    OceanThemePreset,
    SpringThemePreset,
    AutumnThemePreset,
    BlackThemePreset,
    MinimalThemePreset,
    ClaudeThemePreset,
    // MChat 移植主题
    MiyouOrangeTheme,
    PureWhiteTheme,
    ClassicGreenTheme,
    ClassicPurpleTheme,
    MikuGreenTheme,
    TetoRedTheme,
    DeepBlueTheme,
    SilenceGreenTheme,
)

fun findPresetTheme(id: String): PresetTheme =
    PresetThemes.find { it.id == id } ?: ClaudeThemePreset
