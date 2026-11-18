package ai.devcodex.agent.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Agent 终端活动流（v0.4.7，OpenManus 虚拟屏风格）：
 * 工具执行的命令/输出实时推送，TerminalScreen 的「Agent 活动」面板消费。
 * 环形缓冲最近 200 行，进程级单例（不随会话销毁）。
 */
object TerminalFeed {
    private const val MAX_LINES = 200
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun push(line: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _lines.value = (_lines.value + "[$t] $line").takeLast(MAX_LINES)
    }

    fun pushBlock(text: String, maxLines: Int = 12) {
        text.lines().filter { it.isNotBlank() }.take(maxLines).forEach { push(it) }
    }

    fun clear() { _lines.value = emptyList() }
}
