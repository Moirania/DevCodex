package ai.devcodex.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.devcodex.agent.AppViewModel
import ai.devcodex.agent.data.ModelTestResult
import ai.devcodex.agent.data.ProviderConfig
import ai.devcodex.agent.data.ProviderType
import ai.devcodex.agent.provider.ProviderGateway
import ai.devcodex.agent.util.describe
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState(initial = ai.devcodex.agent.data.AppSettings())
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("API 提供商") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, "添加提供商")
            }
        },
    ) { padding ->
        if (settings.providers.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("还没有配置提供商", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(8.dp))
                Text(
                    "支持 OpenAI 兼容协议（GLM/DeepSeek/Kimi/OpenAI/OpenRouter）、Anthropic、Ollama。\n点击右下角 + 添加，填入 Base URL 和 API Key 后可自动识别模型。",
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
                items(settings.providers, key = { it.id }) { p ->
                    // rikkahub 式提供商卡：LOGO+名称+模型数徽章+底部徽章行
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (p.isDefault) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { editing = p },
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ai.devcodex.agent.ui.components.ModelLogo(p.name.ifBlank { "?" }, 42.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(p.name.ifBlank { "未命名" }, style = MaterialTheme.typography.titleMedium)
                                        if (p.isDefault) {
                                            Spacer(Modifier.width(6.dp))
                                            androidx.compose.material3.AssistChip(
                                                onClick = {},
                                                label = { Text("默认", style = MaterialTheme.typography.labelSmall) },
                                                modifier = Modifier.height(22.dp),
                                            )
                                        }
                                    }
                                    Text(p.baseUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${p.enabledModels.size}/${p.models.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                    Text("模型", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row {
                                        if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                                        IconButton(onClick = { vm.setDefaultProvider(p.id) }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Filled.CheckCircle, "设为默认", Modifier.size(18.dp))
                                        }
                                        IconButton(onClick = { vm.deleteProvider(p.id) }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        // 先选预设 → 带入配置编辑
        var presetChosen by remember { mutableStateOf<ai.devcodex.agent.data.ProviderPreset?>(null) }
        if (presetChosen == null) {
            AlertDialog(
                onDismissRequest = { creating = false },
                title = { Text("选择提供商预设") },
                text = {
                    Column {
                        Text("选一个快速开始（Base URL 已填好，只需填 Key）；或拉到底选「自定义」", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // 双列卡片（Operit 式）
                        LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(ai.devcodex.agent.data.ProviderPresets.size) { i ->
                                val preset = ai.devcodex.agent.data.ProviderPresets[i]
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { presetChosen = preset },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                ) {
                                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        ai.devcodex.agent.ui.components.ModelLogo(preset.name, 44.dp)
                                        Spacer(Modifier.height(6.dp))
                                        Text(preset.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            preset.desc,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
            )
        } else {
            ProviderEditDialog(
                initial = ProviderConfig(
                    id = java.util.UUID.randomUUID().toString(),
                    name = presetChosen!!.name,
                    type = presetChosen!!.type,
                    baseUrl = presetChosen!!.baseUrl,
                ),
                onDismiss = { creating = false },
                onSave = { vm.upsertProvider(it); creating = false },
            )
        }
    }
    editing?.let { p ->
        ProviderEditDialog(
            initial = p,
            onDismiss = { editing = null },
            onSave = { vm.upsertProvider(it); editing = null },
        )
    }
}

@Composable
private fun ProviderEditDialog(
    initial: ProviderConfig,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var type by remember { mutableStateOf(initial.type) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var models by remember { mutableStateOf(initial.models) }
    var enabledModels by remember { mutableStateOf(initial.enabledModels) }
    var imageModel by remember { mutableStateOf(initial.imageModel) }
    var videoModel by remember { mutableStateOf(initial.videoModel) }
    var imageEndpoint by remember { mutableStateOf(initial.imageEndpoint) }
    var videoSubmitUrl by remember { mutableStateOf(initial.videoSubmitUrl) }
    var videoStatusUrl by remember { mutableStateOf(initial.videoStatusUrl) }
    var videoTaskIdPath by remember { mutableStateOf(initial.videoTaskIdPath) }
    var videoStatusPath by remember { mutableStateOf(initial.videoStatusPath) }
    var videoResultPath by remember { mutableStateOf(initial.videoResultPath) }
    var videoSuccess by remember { mutableStateOf(initial.videoSuccessValues.joinToString(",")) }
    var videoFail by remember { mutableStateOf(initial.videoFailValues.joinToString(",")) }
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<Pair<String, ModelTestResult>?>(null) }
    var manualModel by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).heightIn(max = 640.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(if (initial.name.isBlank()) "添加提供商" else "编辑提供商", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == ProviderType.OPENAI_COMPAT, onClick = { type = ProviderType.OPENAI_COMPAT; baseUrl = baseUrl.ifBlank { "https://api.openai.com/v1" } }, label = { Text("OpenAI 兼容") })
                    FilterChip(selected = type == ProviderType.ANTHROPIC, onClick = { type = ProviderType.ANTHROPIC; baseUrl = baseUrl.ifBlank { "https://api.anthropic.com" } }, label = { Text("Anthropic") })
                    FilterChip(selected = type == ProviderType.OLLAMA, onClick = { type = ProviderType.OLLAMA; baseUrl = baseUrl.ifBlank { "http://127.0.0.1:11434" } }, label = { Text("Ollama") })
                }
                // 本地模型连接提示
                Text(
                    "本地模型：LM Studio / PocketPal / Layla 选 OpenAI 兼容，填 http://127.0.0.1:1234/v1；Ollama（含 Termux 版）选 Ollama，填 http://127.0.0.1:11434；局域网电脑主机填 http://192.168.x.x:端口",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, placeholder = { Text("名称（如 GLM / DeepSeek）") }, singleLine = true, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                // 分区卡片：接入信息
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ai.devcodex.agent.ui.components.ModelLogo(name, 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("接入信息", style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        var keyVisible by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = apiKey, onValueChange = { apiKey = it },
                            placeholder = { Text("API Key（sk-…）") },
                            maxLines = if (keyVisible) 3 else 1,
                            visualTransformation = if (keyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = {
                                androidx.compose.material3.IconButton(onClick = { keyVisible = !keyVisible }) {
                                    Icon(
                                        if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        if (keyVisible) "隐藏" else "显示",
                                        Modifier.size(18.dp),
                                    )
                                }
                            },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = baseUrl, onValueChange = { baseUrl = it },
                            placeholder = { Text("Base URL（如 https://api.xxx.com/v1）") },
                            singleLine = true,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        // 连接测试（rikkahub ProviderConnectionTester 式）
                        var testResult by remember { mutableStateOf<String?>(null) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = {
                                    testing = "__conn__"; testResult = null
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        testResult = runCatching {
                                            val url = baseUrl.trim().trimEnd('/') + "/models"
                                            val req = okhttp3.Request.Builder().url(url)
                                                .header("Authorization", "Bearer ${apiKey.trim()}")
                                                .build()
                                            okhttp3.OkHttpClient.Builder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
                                                .newCall(req).execute().use { resp ->
                                                    if (resp.isSuccessful) "✓ 连接成功（HTTP ${resp.code}）" else "✗ HTTP ${resp.code}"
                                                }
                                        }.getOrElse { "✗ ${it.message}" }
                                        testing = null
                                    }
                                },
                                enabled = baseUrl.isNotBlank() && testing == null,
                            ) { Text(if (testing != null) "测试中…" else "测试连接") }
                            Spacer(Modifier.width(8.dp))
                            testResult?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            fetching = true; fetchError = null
                            scope.launch {
                                try {
                                    val cleaned = normalizeBaseUrl(baseUrl)
                                    baseUrl = cleaned
                                    val list = ProviderGateway.listModels(initial.copy(name = name, type = type, baseUrl = cleaned, apiKey = apiKey))
                                    if (list.isEmpty()) {
                                        fetchError = "接口返回空列表，可手动添加模型"
                                    } else {
                                        models = list
                                        enabledModels = list  // 全选，用户自行取消
                                    }
                                } catch (e: Exception) {
                                    fetchError = "识别失败: ${e.describe().take(200)}（URL: ${normalizeBaseUrl(baseUrl)}/models）——可手动在下方添加"
                                }
                                fetching = false
                            }
                        },
                        enabled = !fetching && baseUrl.isNotBlank(),
                    ) {
                        if (fetching) CircularProgressIndicator(Modifier.padding(end = 6.dp))
                        Icon(Icons.Filled.Refresh, null, Modifier.padding(end = 6.dp))
                        Text("自动识别模型")
                    }
                }
                fetchError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                // 媒体模型：生图与视频（由 Agent 工具调用，不作为聊天主模型）
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("媒体模型（生图与视频）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "生图默认请求 Base URL 加 /images/generations（OpenAI 兼容）；视频按「提交、轮询、下载」流程，地址与字段路径按厂商文档填写，查询地址可用 {{task_id}} 占位。留空表示不启用。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MediaField("生图模型 ID", imageModel, { imageModel = it }, "如 gpt-image-1 / cogview-4")
                        MediaField("图片接口地址（可选）", imageEndpoint, { imageEndpoint = it }, "留空则用 Base URL/images/generations")
                        MediaField("视频模型 ID", videoModel, { videoModel = it }, "如 cogvideox-3")
                        MediaField("视频提交地址", videoSubmitUrl, { videoSubmitUrl = it }, "POST，完整 URL")
                        MediaField("视频查询地址", videoStatusUrl, { videoStatusUrl = it }, "GET，含 {{task_id}}")
                        MediaField("任务 ID 路径", videoTaskIdPath, { videoTaskIdPath = it }, "默认 id，嵌套用 data.id")
                        MediaField("状态字段路径", videoStatusPath, { videoStatusPath = it }, "默认 status")
                        MediaField("视频地址路径", videoResultPath, { videoResultPath = it }, "默认 video_url，数组用下标如 data.0.url")
                        MediaField("成功状态值（逗号分隔）", videoSuccess, { videoSuccess = it }, "succeeded,success,completed")
                        MediaField("失败状态值（逗号分隔）", videoFail, { videoFail = it }, "failed,error,cancelled")
                    }
                }

                Text("模型（勾选启用，可手动添加）—— 当前 ${models.size} 个，已启用 ${enabledModels.size} 个：", style = MaterialTheme.typography.labelLarge)
                // 手动添加模型行
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = manualModel, onValueChange = { manualModel = it },
                        placeholder = { Text("手动输入模型名，点 + 添加") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    IconButton(onClick = {
                        val m = manualModel.trim()
                        if (m.isNotBlank() && models.none { it.equals(m, ignoreCase = true) }) {
                            models = models + m
                            enabledModels = enabledModels + m
                        }
                        manualModel = ""
                    }) { Icon(Icons.Filled.Add, "添加") }
                }
                if (models.isEmpty()) {
                    Text(
                        "尚未识别到模型 —— 点上方「自动识别」拉取，或直接手动输入模型名添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(bottom = 4.dp),
                ) {
                    items(models) { m ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = enabledModels.contains(m),
                                onCheckedChange = { on ->
                                    enabledModels = if (on) enabledModels + m else enabledModels - m
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(m, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                // 能力标签（内置知识库推断）
                                val meta = ai.devcodex.agent.provider.ModelRegistry.lookup(m)
                                val tags = buildList {
                                    when (meta.kind) {
                                        ai.devcodex.agent.provider.ModelKind.IMAGE -> add("生图")
                                        ai.devcodex.agent.provider.ModelKind.VIDEO -> add("视频")
                                        else -> meta.tools?.let { add(if (it) "工具" else "无工具") }
                                    }
                                    if (meta.vision) add("视觉")
                                    if (meta.reasoning) add("推理")
                                    meta.contextLength?.let { add("${it / 1000}k上下文") }
                                }
                                if (tags.isNotEmpty()) {
                                    Text(
                                        tags.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = {
                                testing = m
                                scope.launch {
                                    val r = runCatching { ProviderGateway.testModel(initial.copy(type = type, baseUrl = baseUrl, apiKey = apiKey), m) }
                                        .getOrElse { ModelTestResult(error = it.describe()) }
                                    testResult = m to r
                                    testing = null
                                }
                            }) {
                                if (testing == m) CircularProgressIndicator(Modifier.padding(4.dp))
                                else Icon(Icons.Filled.Speed, "测试")
                            }
                            IconButton(onClick = {
                                models = models - m
                                enabledModels = enabledModels - m
                            }) { Icon(Icons.Filled.Close, "移除", Modifier.size(16.dp)) }
                        }
                    }
                }
                testResult?.let { (m, r) ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(Modifier.padding(8.dp)) {
                            Text(m, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            Text(
                                buildString {
                                    append(if (r.latencyMs >= 0) "延迟 ${r.latencyMs}ms " else "未连通 ")
                                    append(if (r.streamOk) "· 流式✓ " else "· 流式✗ ")
                                    append(if (r.toolsOk) "· 工具✓" else "· 工具✗")
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            r.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                        }
                    }
                }
            }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val cleaned = normalizeBaseUrl(baseUrl)
                        onSave(initial.copy(
                            name = name.ifBlank { hostOf(cleaned) }, type = type, baseUrl = cleaned,
                            apiKey = apiKey, models = models, enabledModels = enabledModels,
                            imageModel = imageModel.trim(), videoModel = videoModel.trim(),
                            imageEndpoint = imageEndpoint.trim(), videoSubmitUrl = videoSubmitUrl.trim(), videoStatusUrl = videoStatusUrl.trim(),
                            videoTaskIdPath = videoTaskIdPath.trim().ifBlank { "id" },
                            videoStatusPath = videoStatusPath.trim().ifBlank { "status" },
                            videoResultPath = videoResultPath.trim().ifBlank { "video_url" },
                            videoSuccessValues = videoSuccess.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { initial.videoSuccessValues },
                            videoFailValues = videoFail.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { initial.videoFailValues },
                            enabled = true,
                        ))
                    }) { Text("保存") }
            }
        }
    }
}

@Composable
private fun MediaField(label: String, value: String, onChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(hint, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun typeName(t: ProviderType): String = when (t) {
    ProviderType.OPENAI_COMPAT -> "OpenAI 兼容"
    ProviderType.ANTHROPIC -> "Anthropic"
    ProviderType.OLLAMA -> "Ollama"
}


/** URL 清洗：补协议、去尾斜杠、去掉误粘贴的端点后缀 */
private fun normalizeBaseUrl(raw: String): String {
    var u = raw.trim()
    if (u.isBlank()) return u
    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
    u = u.trimEnd('/')
    u = u.removeSuffix("/chat/completions").removeSuffix("/messages").removeSuffix("/models")
    return u
}

/** 从 URL 提取可读的提供商名 */
private fun hostOf(url: String): String = runCatching {
    java.net.URI(url).host?.removePrefix("www.")?.replaceFirstChar { it.uppercase() } ?: url
}.getOrDefault(url.ifBlank { "提供商" })
