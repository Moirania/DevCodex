package ai.devcodex.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 可视化终端（OpenMinis 式 ANSI 模拟器 + Canvas 渲染；通道：Shizuku/Root/沙盒自动降级） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: ai.devcodex.agent.AppViewModel) {
    val emulator = remember { ai.devcodex.agent.term.emulator.TerminalEmulator(cols = 80, rows = 24) }
    var rev by remember { mutableStateOf(0) }   // 重绘触发
    var cmd by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(mutableListOf<String>()) }
    var histIdx by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()

    fun banner(ctx: android.content.Context) {
        val green = "\u001B[32m"; val yellow = "\u001B[33m"; val reset = "\u001B[0m"; val grey = "\u001B[90m"
        val nativeTermux = runCatching { ai.devcodex.agent.shell.TermuxRuntime.ensure(ctx) }.getOrDefault(false)
        val status = if (nativeTermux) {
            green + "Termux 原生引擎就绪（bash / apt 可用）" + reset
        } else {
            yellow + "Termux 引擎: " + ai.devcodex.agent.shell.TermuxRuntime.lastError.ifBlank { "初始化中…" } + reset
        }
        emulator.feed(("DevCodex VT 终端（OpenMinis 内核）\r\n" + status + "\r\n通道: Termux 原生 / Shizuku / Root\r\n" + grey + "↑↓ 历史 · 回车执行" + reset + "\r\n$ ").toByteArray())
        rev++
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { banner(ctx) }

    fun runCommand(c: String) {
        if (busy) return
        busy = true
        emulator.feed("\r\n".toByteArray()); rev++
        scope.launch(Dispatchers.IO) {
            val out = ai.devcodex.agent.shell.ShellAccess.exec(c)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                emulator.feed(out.toByteArray())
                emulator.feed("\r\n$ ".toByteArray())
                rev++
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("终端") },
                navigationIcon = {
                    IconButton(onClick = { vm.navRequest.value = "back" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // 清屏
                    TextButton(onClick = {
                        emulator.feed("\u001b[2J\u001b[H".toByteArray())
                        emulator.feed("$ ".toByteArray())
                        rev++
                    }) { Text("清屏", style = MaterialTheme.typography.labelMedium) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            // 通道指示
            val mode = ai.devcodex.agent.shell.ShellAccess.availableMode()
            val modeLabel = when (mode) {
                ai.devcodex.agent.shell.ShellAccess.Mode.SHIZUKU -> "Shizuku（特权）"
                ai.devcodex.agent.shell.ShellAccess.Mode.ROOT -> "Root（特权）"
                ai.devcodex.agent.shell.ShellAccess.Mode.AXS -> "AXS/Termux 服务（ADB 级）"
                ai.devcodex.agent.shell.ShellAccess.Mode.LINUX -> "Linux 沙盒（Alpine·PRoot，busybox 全套 + /workspace 挂载）"
                ai.devcodex.agent.shell.ShellAccess.Mode.SANDBOX -> "备用 toybox 沙盒"
            }
            Text(
                "通道: $modeLabel",
                style = MaterialTheme.typography.labelSmall,
                color = if (mode == ai.devcodex.agent.shell.ShellAccess.Mode.SANDBOX) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
            // ANSI 终端渲染区（颜色/光标/转义全支持）
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(rev) {
                    ai.devcodex.agent.term.canvas.TerminalCanvasView(
                        emulator = emulator,
                        modifier = Modifier.fillMaxSize(),
                        onResize = { _, _ -> },
                    )
                }
            }
            // 快捷键栏（终端专用键）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val keys = listOf(
                    "ESC" to "\u001b", "TAB" to "\t", "↑" to "\u001b[A", "↓" to "\u001b[B",
                    "←" to "\u001b[D", "→" to "\u001b[C", "HOME" to "\u001b[H", "END" to "\u001b[F",
                    "|" to "|", "/" to "/", "~" to "~", "-" to "-", "''" to "''", "&&" to "&&",
                )
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(keys.size) { i ->
                        val (label, seq) = keys[i]
                        OutlinedButton(
                            onClick = {
                                if (busy) return@OutlinedButton
                                if (label == "↑" || label == "↓") {
                                    // 历史导航
                                    if (history.isNotEmpty()) {
                                        histIdx = if (label == "↑")
                                            (if (histIdx < 0) history.size - 1 else (histIdx - 1).coerceAtLeast(0))
                                        else (if (histIdx < 0) -1 else (histIdx + 1).coerceAtMost(history.size - 1))
                                        cmd = if (histIdx >= 0) history[histIdx] else ""
                                    }
                                } else if (label == "ESC" || label == "TAB") {
                                    // 控制序列直接发到模拟器
                                    emulator.feed(seq.toByteArray())
                                    rev++
                                } else {
                                    cmd += seq
                                }
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp),
                        ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            // 输入行（等宽）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$ ", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = cmd, onValueChange = { cmd = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !busy,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    placeholder = { Text(if (busy) "执行中…" else "命令", fontSize = 13.sp) },
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                )
                IconButton(onClick = {
                    if (cmd.isNotBlank()) {
                        val c = cmd
                        history.add(c); histIdx = -1
                        cmd = ""
                        runCommand(c)
                    }
                }) { Icon(Icons.Filled.PlayArrow, "执行") }
            }
        }
    }
}
