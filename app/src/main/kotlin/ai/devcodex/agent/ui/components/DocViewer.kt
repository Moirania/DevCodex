package ai.devcodex.agent.ui.components

import ai.devcodex.agent.m3l.LocalCodeBlockDark
import ai.devcodex.agent.m3l.LocalCodeTheme
import ai.devcodex.agent.m3l.LocalMarkdownRendererTheme
import ai.devcodex.agent.m3l.MarkdownRendererTheme
import ai.devcodex.agent.m3l.MixedMarkdownLatex
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 全屏 Markdown 文档查看器：关于页与插件页共用，主题与代码块配色跟随应用主题 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocViewerDialog(
    title: String,
    markdown: String,
    codeTheme: String = "github",
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = { Text(title) },
                        navigationIcon = {
                            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭") }
                        },
                    )
                },
            ) { padding ->
                CompositionLocalProvider(
                    LocalCodeTheme provides codeTheme,
                    LocalCodeBlockDark provides (MaterialTheme.colorScheme.background.luminance() < 0.5f),
                    LocalMarkdownRendererTheme provides MarkdownRendererTheme(
                        primary = MaterialTheme.colorScheme.primary,
                        surface = MaterialTheme.colorScheme.surfaceContainerLow,
                        outlineVariant = MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        MixedMarkdownLatex(text = markdown, textColor = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}
