package ai.devcodex.agent.ui.screens

import androidx.compose.foundation.layout.*
import ai.devcodex.agent.AppViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

/** v0.4.15 记忆管理页（Claude 记忆架构）：文件式长期记忆的增删改查
 * 数据源 = filesDir/memory 下的 md 文件（memory_save 工具同源） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val dir = File(ctx.filesDir, "memory").apply { mkdirs() }
    var refresh by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }  // key to content
    var showNew by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }

    data class Mem(val key: String, val file: File, val content: String, val size: Long, val mtime: Long)
    val memories = remember(refresh) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.map { f -> Mem(f.name.removeSuffix(".md"), f, f.readText(), f.length(), f.lastModified()) }
            ?.sortedByDescending { it.mtime } ?: emptyList()
    }
    val filtered = if (query.isBlank()) memories else memories.filter {
        it.key.contains(query, true) || it.content.contains(query, true)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("记忆（${memories.size}）") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNew = true }) { Icon(Icons.Filled.Add, "新建记忆") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("搜索记忆内容") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            if (filtered.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Memory, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    Spacer(Modifier.padding(8.dp))
                    Text(if (query.isBlank()) "还没有记忆" else "无匹配", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "AI 通过 memory_save 工具自动沉淀重要信息；\n也可在此手动管理（查看/编辑/删除）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.key }) { m ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            onClick = { editing = m.key to m.content },
                        ) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Memory, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(m.key, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    // 删除按钮（用户核心诉求：记忆可删除）
                                    IconButton(onClick = { m.file.delete(); refresh++ }) {
                                        Icon(Icons.Filled.Delete, "删除记忆", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                                Text(
                                    m.content.take(120) + if (m.content.length > 120) "…" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                )
                                Text(
                                    "${m.size / 1024}KB · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(m.mtime))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 查看/编辑弹窗
    editing?.let { (key, content) ->
        var text by remember(key) { mutableStateOf(content) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(key) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    minLines = 6, maxLines = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    File(dir, "$key.md").writeText(text)
                    editing = null; refresh++
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { File(dir, "$key.md").delete(); editing = null; refresh++ }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { editing = null }) { Text("取消") }
                }
            },
        )
    }

    // 新建弹窗
    if (showNew) {
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("新建记忆") },
            text = {
                OutlinedTextField(
                    value = newKey, onValueChange = { newKey = it },
                    label = { Text("键名（英文/拼音，如 user-preferences）") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newKey.isNotBlank()) {
                            File(dir, "${newKey.trim()}.md").writeText("")
                            newKey = ""; showNew = false; refresh++
                        }
                    },
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("取消") } },
        )
    }
}
