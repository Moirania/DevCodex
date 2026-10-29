package ai.devcodex.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import ai.devcodex.agent.AppViewModel
import ai.devcodex.agent.tools.BuiltinTools

/**
 * 扩展页（cccodroid devkit 式）：在线扩展清单 → 下载安装到技能/插件目录 → AI 可调用
 * 扩展源 JSON 规范：[{ "name", "type": "skill|plugin", "desc", "url", "size" }]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(vm: AppViewModel) {
    var entries by remember { mutableStateOf<List<ExtEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var sourceUrl by remember { mutableStateOf("https://raw.githubusercontent.com/Moirania/DevCodex/main/extensions/index.json") }
    val scope = rememberCoroutineScope()
    fun scopeLaunch(block: suspend () -> Unit) { scope.launch { block() } }

    suspend fun load(src: String) {
        loading = true; message = null
        withContext(Dispatchers.IO) {
            runCatching {
                val req = okhttp3.Request.Builder().url(src).build()
                okhttp3.OkHttpClient.Builder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val body = resp.body?.string() ?: error("空响应")
                        val arr = kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonArray
                            ?: return@use
                        entries = arr.mapNotNull { e ->
                            val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                            ExtEntry(
                                name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?",
                                type = (o["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "skill",
                                desc = (o["desc"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                                url = (o["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                                size = (o["size"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0,
                            )
                        }
                    }
            }.getOrElse { message = "清单加载失败：${it.message?.take(120)}" }
        }
        loading = false
    }

    suspend fun install(entry: ExtEntry) {
        loading = true
        withContext(Dispatchers.IO) {
            runCatching {
                val req = okhttp3.Request.Builder().url(entry.url).build()
                okhttp3.OkHttpClient.Builder().callTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val bytes = resp.body?.bytes() ?: error("空内容")
                        // 路径穿越防护：远程清单的 name 只允许安全文件名字符，并限制内容大小
                        if (!entry.name.matches(Regex("[A-Za-z0-9._一-龥-]+")) || entry.name == "." || entry.name == "..") error("扩展名称含非法字符")
                        if (bytes.size > 2 * 1024 * 1024) error("扩展文件过大")
                        val ctx = BuiltinTools.appContext()
                        when (entry.type) {
                            "plugin" -> {
                                ai.devcodex.agent.data.PluginRepo(ctx).installFromText(String(bytes, Charsets.UTF_8)).getOrThrow()
                            }
                            else -> {
                                ai.devcodex.agent.data.SkillRepo(ctx).install(String(bytes, Charsets.UTF_8)).getOrThrow()
                            }
                        }
                        message = "✓ 已安装「${entry.name}」（可在${if (entry.type == "plugin") "插件" else "技能"}页管理）"
                    }
            }.getOrElse { message = "安装失败：${it.message?.take(120)}" }
        }
        loading = false
    }

    LaunchedEffect(Unit) { load(sourceUrl) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("扩展") },
            actions = {
                IconButton(onClick = { scopeLaunch { load(sourceUrl) } }) { Icon(Icons.Filled.Refresh, "刷新") }
            },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = sourceUrl, onValueChange = { sourceUrl = it },
                    label = { Text("扩展源 URL") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                FilledTonalButton(onClick = { scopeLaunch { load(sourceUrl) } }) { Text("加载") }
            }
            Spacer(Modifier.height(8.dp))
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(6.dp))

            if (loading) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text("加载中…", style = MaterialTheme.typography.bodySmall)
            }

            if (entries.isEmpty() && !loading) {
                Text(
                    "暂无扩展。规范：扩展源部署 index.json（[{name,type,desc,url,size}]），" +
                        "type=skill 装技能 Markdown（AI 技能），type=plugin 装插件 JSON（http 工具）。" +
                        "装好后 AI 对话即可调用，也可在「插件 / 技能」页管理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries) { e ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (e.type == "plugin") Icons.Filled.Extension else Icons.Filled.School,
                                        null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(e.name, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.width(6.dp))
                                    Text(e.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                }
                                if (e.desc.isNotBlank()) Text(
                                    e.desc, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                                )
                                if (e.size > 0) Text(
                                    "${e.size / 1024}KB · ${e.url.take(60)}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.outline, maxLines = 1,
                                )
                            }
                            FilledTonalButton(onClick = { scopeLaunch { install(e) } }, enabled = !loading) {
                                Text("安装")
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ExtEntry(
    val name: String,
    val type: String,
    val desc: String,
    val url: String,
    val size: Long,
)
