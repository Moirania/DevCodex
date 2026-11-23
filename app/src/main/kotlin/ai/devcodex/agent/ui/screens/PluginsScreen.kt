package ai.devcodex.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.devcodex.agent.AppViewModel
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(vm: AppViewModel) {
    val plugins by vm.plugins.collectAsState()
    var showInstall by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ai.devcodex.agent.data.PluginManifest?>(null) }
    // v0.4.9 插件市场：GitHub marketplace.json 索引 + 一键安装
    var tab by remember { mutableStateOf(0) }
    data class MarketItem(val id: String, val name: String, val desc: String, val author: String, val version: String, val manifestText: String)
    var marketItems by remember { mutableStateOf<List<MarketItem>>(emptyList()) }
    var marketLoading by remember { mutableStateOf(false) }
    var marketErr by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun loadMarket() {
        marketLoading = true; marketErr = null
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val conn = java.net.URL("https://raw.githubusercontent.com/Moirania/DevCodex/main/marketplace.json")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000; conn.readTimeout = 15000
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val arr = kotlinx.serialization.json.Json.parseToJsonElement(txt)
                    .let { it as kotlinx.serialization.json.JsonObject }["items"] as kotlinx.serialization.json.JsonArray
                arr.map { e ->
                    val o = e as kotlinx.serialization.json.JsonObject
                    fun s(k: String) = o[k]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: ""
                    MarketItem(s("id"), s("name"), s("desc"), s("author"), s("version"), s("manifestText"))
                }
            }.fold(onSuccess = { marketItems = it }, onFailure = { marketErr = it.message })
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { marketLoading = false }
        }
    }
    androidx.compose.runtime.LaunchedEffect(tab) { if (tab == 1 && marketItems.isEmpty() && !marketLoading) loadMarket() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("插件") }) },
        floatingActionButton = {
            if (tab == 0) FloatingActionButton(onClick = { showInstall = true }) {
                Icon(Icons.Filled.Extension, "安装插件")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("已安装 (${plugins.size})") })
                androidx.compose.material3.FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("插件市场") })
            }
            if (tab == 1) {
                when {
                    marketLoading -> Box(Modifier.fillMaxSize()) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    marketErr != null -> Column(Modifier.fillMaxSize().padding(24.dp)) {
                        Text("市场加载失败：${marketErr?.take(80)}", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.padding(8.dp))
                        Button(onClick = { loadMarket() }) { Text("重试") }
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(marketItems, key = { it.id }) { m ->
                            val installed = plugins.any { it.id == m.id }
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (installed) MaterialTheme.colorScheme.surfaceContainerLowest
                                    else MaterialTheme.colorScheme.secondaryContainer,
                                ),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Extension, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.padding(8.dp))
                                        Text(m.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                        Text("v${m.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (m.desc.isNotBlank()) Text(m.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                                    Text(m.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                    if (!installed) Button(
                                        onClick = {
                                            vm.pluginRepo.installFromText(m.manifestText).fold(
                                                onSuccess = { vm.toast.value = "已安装 ${it.name}" },
                                                onFailure = { vm.toast.value = "安装失败：${it.message?.take(60)}" },
                                            )
                                        },
                                        modifier = Modifier.padding(top = 8.dp),
                                    ) { Text("安装") }
                                    else Text("✓ 已安装", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                        item { Text("市场源：Moirania/DevCodex marketplace.json · 欢迎提 PR 上架插件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            } else Box(Modifier.fillMaxSize()) {
        // 开发文档大入口（首屏第一项）
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            onClick = { showGuide = true },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp),
            ) {
                Icon(
                    Icons.Filled.MenuBook, null,
                    Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.padding(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("插件开发文档", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "是什么 · JSON 字段说明 · 参数写法 · 模板语法 · 五条铁律 · 最小示例 · 调试流程",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                }
                Text("→", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        if (plugins.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                Text("暂无插件", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(8.dp))
                Text(
                    "插件 = JSON 工具包：声明工具名、参数 Schema 和 HTTP 端点模板，" +
                        "智能体即可调用你自己的服务（Home Assistant、n8n、自建 API 等）。\n" +
                        "支持从本地文件 / URL / 粘贴 JSON 三种方式安装。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(plugins, key = { it.id }) { p ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (p.enabled) MaterialTheme.colorScheme.surfaceContainerLow
                            else MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { detail = p },
                    ) {
                        ListItem(
                            overlineContent = { Text("v${p.version} · ${p.author.ifBlank { "未知作者" }}") },
                            headlineContent = { Text(p.name) },
                            supportingContent = { Text("工具 ×${p.tools.size} · ${p.description}", maxLines = 2) },
                            leadingContent = {
                                Icon(Icons.Filled.Extension, null, Modifier.size(36.dp))
                            },
                            trailingContent = {
                                Row {
                                    Switch(
                                        checked = p.enabled,
                                        onCheckedChange = { on ->
                                            vm.pluginRepo.save(p.copy(enabled = on))
                                            vm.reloadPlugins()
                                        },
                                    )
                                }
                            },
                        )
                    }
                    }
                }   // /else 空态/列表
            }   // /Box 本地内容
        }       // /Column Tab容器
    }           // /Scaffold

    if (showInstall) {
        InstallPluginDialog(vm) { showInstall = false }
    }
    if (showGuide) {
        ai.devcodex.agent.ui.components.DocViewerDialog(title = "插件开发指南", markdown = PLUGIN_DOC_MD) { showGuide = false }
    }

    detail?.let { p ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("${p.name} v${p.version}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.description)
                    p.tools.forEach { t ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Column(Modifier.padding(8.dp)) {
                                Text(t.name, style = MaterialTheme.typography.labelLarge)
                                Text("${t.endpoint.method} ${t.endpoint.url}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.pluginRepo.delete(p.id)
                    vm.reloadPlugins()
                    detail = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
        )
    }
}
}

@Composable
private fun InstallPluginDialog(vm: AppViewModel, onDone: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val content = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                }.getOrNull()
                if (content == null) error = "读取文件失败"
                else install(vm, content) { error = it }
                busy = false
                if (error == null) onDone()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("安装插件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { filePicker.launch("*/*") }, enabled = !busy) {
                    Icon(Icons.Filled.FileOpen, null, Modifier.padding(end = 6.dp)); Text("从文件安装")
                }
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("从 URL 安装") }, singleLine = true,
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val body = ai.devcodex.agent.plugin.PluginExecutor.executeRaw("""{"url":"$url","method":"GET"}""")
                                if (body.startsWith("ERROR") || body.startsWith("HTTP 4") || body.startsWith("HTTP 5")) {
                                    error = body.take(150)
                                } else {
                                    install(vm, body) { error = it }
                                    if (error == null) onDone()
                                }
                            } catch (e: Exception) { error = e.message }
                            busy = false
                        }
                    },
                    enabled = !busy && url.isNotBlank(),
                ) { Icon(Icons.Filled.Link, null, Modifier.padding(end = 6.dp)); Text("下载") }

                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("或粘贴插件 JSON") },
                    minLines = 3, maxLines = 6,
                )
                TextButton(
                    onClick = {
                        install(vm, text) { error = it }
                        if (error == null) onDone()
                    },
                    enabled = text.isNotBlank(),
                ) { Icon(Icons.Filled.EditNote, null, Modifier.padding(end = 6.dp)); Text("安装") }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("关闭") } },
    )
}

private fun install(vm: AppViewModel, content: String, onError: (String) -> Unit) {
    vm.pluginRepo.installFromText(content)
        .onSuccess { vm.reloadPlugins() }
        .onFailure { onError("安装失败: ${it.message}") }
}

