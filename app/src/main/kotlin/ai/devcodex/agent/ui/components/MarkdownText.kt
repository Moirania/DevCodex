package ai.devcodex.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Mono = FontFamily.Monospace

/** 轻量 Markdown 渲染：代码块（暗底+语言标签+复制）、标题、列表、引用、粗斜体、行内代码、链接样式 */
@Composable
fun MarkdownText(content: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val codeBlockColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val inlineCodeBg = MaterialTheme.colorScheme.secondaryContainer

    Column(modifier = modifier.fillMaxWidth()) {
        blocks(content).forEachIndexed { bi, block ->
            when (block) {
                is MdBlock.Code -> Surface(
                    color = codeBlockColor,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp)) {
                            Text(
                                block.lang.ifBlank { "code" },
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = Mono,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f).padding(top = 4.dp),
                            )
                            TextButton(onClick = { clipboard.setText(AnnotatedString(block.code)) }) {
                                Text("复制", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text(
                            block.code,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = Mono,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(remember { androidx.compose.foundation.ScrollState(0) })
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .padding(bottom = 8.dp),
                        )
                    }
                }
                is MdBlock.Table -> MdTable(block)
                is MdBlock.Heading -> Text(
                    inline(block.text, inlineCodeBg, MaterialTheme.colorScheme.primary),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                is MdBlock.Bullet -> Row(Modifier.padding(start = 4.dp, bottom = 2.dp)) {
                    Text(if (block.ordered) "${block.num}. " else "•  ", style = MaterialTheme.typography.bodyLarge)
                    Text(inline(block.text, inlineCodeBg, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodyLarge)
                }
                is MdBlock.Quote -> Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Text(
                        inline(block.text, inlineCodeBg, MaterialTheme.colorScheme.primary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                is MdBlock.Rule -> Spacer(
                    Modifier.fillMaxWidth().height(1.dp).padding(vertical = 6.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                is MdBlock.Paragraph -> Text(
                    inline(block.text, inlineCodeBg, MaterialTheme.colorScheme.primary),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String, val ordered: Boolean, val num: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    object Rule : MdBlock
}

private fun blocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.replace("\r\n", "\n").split("\n")
    var i = 0
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out.add(MdBlock.Paragraph(para.toString().trim()))
        para.clear()
    }
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                flush()
                val lang = line.trimStart().removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i]); i++
                }
                out.add(MdBlock.Code(lang, code.toString().trimEnd()))
            }
            Regex("^#{1,6} ").containsMatchIn(line) -> {
                flush()
                val level = line.takeWhile { it == '#' }.length
                out.add(MdBlock.Heading(level, line.dropWhile { it == '#' }.trim()))
            }
            line.trimStart().startsWith("|") -> {
                flush()
                // 收集整个表格块
                val tableLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    tableLines.add(lines[i]); i++
                }
                i-- // 抵消外层 i++
                val parsed = parseTable(tableLines)
                if (parsed != null) out.add(parsed)
            }
            Regex("^\\s*[-*] ").containsMatchIn(line) -> {
                flush()
                out.add(MdBlock.Bullet(line.trimStart().drop(2).trim(), ordered = false, num = 0))
            }
            Regex("^\\s*\\d+\\. ").containsMatchIn(line) -> {
                flush()
                val num = line.trimStart().substringBefore('.').toIntOrNull() ?: 0
                out.add(MdBlock.Bullet(line.trimStart().substringAfter(". ").trim(), ordered = true, num = num))
            }
            line.trimStart().startsWith("> ") -> {
                flush()
                out.add(MdBlock.Quote(line.trimStart().drop(2).trim()))
            }
            Regex("^\\s*(-{3,}|\\*{3,})\\s*$").matches(line) -> {
                flush()
                out.add(MdBlock.Rule)
            }
            line.isBlank() -> flush()
            else -> { para.appendLine(line) }
        }
        i++
    }
    flush()
    return out
}

/** 行内样式：`code` **bold** *italic* [text](url) ~~del~~ */
private fun inline(text: String, codeBg: Color, linkColor: Color = Color(0xFF1A73E8)): AnnotatedString = buildAnnotatedString {
    var i = 0
    val t = text
    while (i < t.length) {
        when {
            t.startsWith("`", i) -> {
                val end = t.indexOf('`', i + 1)
                if (end > i) {
                    pushStyle(SpanStyle(fontFamily = Mono, fontSize = 13.sp, background = codeBg))
                    append(t.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else { append(t[i]); i++ }
            }
            t.startsWith("**", i) -> {
                val end = t.indexOf("**", i + 2)
                if (end > i) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(t.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else { append(t[i]); i++ }
            }
            t.startsWith("~~", i) -> {
                val end = t.indexOf("~~", i + 2)
                if (end > i) {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    append(t.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else { append(t[i]); i++ }
            }
            t[i] == '*' && !t.startsWith("**", i) -> {
                val end = t.indexOf('*', i + 1)
                if (end > i && end > i + 1) {
                    pushStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                    append(t.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else { append(t[i]); i++ }
            }
            t[i] == '[' -> {
                val close = t.indexOf(']', i + 1)
                if (close > i && close + 1 < t.length && t[close + 1] == '(') {
                    val urlEnd = t.indexOf(')', close + 2)
                    if (urlEnd > close) {
                        pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                        append(t.substring(i + 1, close))
                        pop()
                        i = urlEnd + 1
                    } else { append(t[i]); i++ }
                } else { append(t[i]); i++ }
            }
            t[i] == '#' && i == 0 -> { append(t[i]); i++ }
            else -> { append(t[i]); i++ }
        }
    }
}


/** 解析 GFM 表格：| a | b | + |---|---| + 数据行 */
private fun parseTable(lines: List<String>): MdBlock.Table? {
    if (lines.size < 2) return null
    fun cells(l: String): List<String> = l.trim().trim('|').split('|').map { it.trim() }
    val headers = cells(lines[0])
    // 第二行必须是分隔行
    val sep = cells(lines[1])
    if (sep.isEmpty() || sep.any { !Regex("^:?-{2,}:?$").matches(it) }) return null
    val rows = lines.drop(2).map { cells(it) }.filter { it.isNotEmpty() }
    return MdBlock.Table(headers, rows)
}

/** 表格渲染：表头主色底 + 横向滚动 + 斑马纹 */
@Composable
private fun MdTable(table: MdBlock.Table) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(1.dp)) {
            // 表头
            Row(Modifier.fillMaxWidth().horizontalScroll(remember { androidx.compose.foundation.ScrollState(0) })) {
                table.headers.forEach { h ->
                    Text(
                        inline(h, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.primary),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                            .widthIn(min = 84.dp),
                    )
                }
            }
            // 数据行（斑马纹）
            table.rows.forEachIndexed { ri, row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (ri % 2 == 1) MaterialTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent)
                        .horizontalScroll(remember { androidx.compose.foundation.ScrollState(0) })
                ) {
                    row.forEachIndexed { ci, cell ->
                        Text(
                            inline(cell, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.primary),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .widthIn(min = 84.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
