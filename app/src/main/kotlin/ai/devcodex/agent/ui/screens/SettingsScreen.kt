package ai.devcodex.agent.ui.screens
import androidx.compose.material3.CardDefaults

import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.activity.compose.rememberLauncherForActivityResult
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.School
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.vector.path
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.devcodex.agent.AppViewModel
import ai.devcodex.agent.data.AppSettings
import ai.devcodex.agent.data.ThemePrefsData
import ai.devcodex.agent.shell.ShellAccess
import ai.devcodex.agent.theme.PresetThemes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState(initial = ai.devcodex.agent.data.AppSettings())
    var page by remember { mutableStateOf("root") }

    Scaffold(topBar = {
        TopAppBar(title = {
            Text(when (page) {
                "theme" -> "主题与字体"
                "tools" -> "工具与权限"
                "stats" -> "使用统计"
                "skills" -> "技能"
                "about" -> "关于"
                else -> "设置"
            })
        }, navigationIcon = {
            if (page != "root") IconButton(onClick = { page = "root" }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
        })
    }) { padding ->
        // 页面跳转请求（如 /stats → stats 子页）
        LaunchedEffect(Unit) {
            vm.settingsPageRequest.collect { req ->
                if (req != null) {
                    page = req
                    vm.settingsPageRequest.value = null
                }
            }
        }
        when (page) {
            "theme" -> ThemePage(vm, settings, Modifier.padding(padding))
            "tools" -> ToolsPermPage(vm, settings, Modifier.padding(padding))
            "stats" -> ai.devcodex.agent.ui.screens.StatsScreen(vm) { page = "root" }
            "skills" -> SkillsPage(vm) { page = "root" }
            "about" -> AboutPage(Modifier.padding(padding)) { page = "root" }
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            ) {
                ListItem(
                    headlineContent = { Text("技能 Skills") },
                    supportingContent = { Text("SKILL.md 技能包 · 安装/开关/删除") },
                    leadingContent = { Icon(Icons.Filled.School, null) },
                    modifier = Modifier.clickable { page = "skills" },
                )
                ListItem(
                    headlineContent = { Text("使用统计") },
                    supportingContent = { Text("消息热力图 · 今日/累计数据") },
                    leadingContent = { Icon(Icons.Filled.BarChart, null) },
                    modifier = Modifier.clickable { page = "stats" },
                )
                ListItem(
                    headlineContent = { Text("主题与字体") },
                    supportingContent = { Text("预设主题 · 深色模式 · AMOLED · 字体") },
                    leadingContent = { Icon(Icons.Filled.Palette, null) },
                    modifier = Modifier.clickable { page = "theme" },
                )
                ListItem(
                    headlineContent = { Text("工具与权限") },
                    supportingContent = { Text("内置工具开关 · Shizuku · Root · 无障碍") },
                    leadingContent = { Icon(Icons.Filled.Construction, null) },
                    modifier = Modifier.clickable { page = "tools" },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("关于与赞助") },
                    supportingContent = { Text("作者 · 反馈 · 喝杯奶茶 · 隐私政策") },
                    leadingContent = { Icon(Icons.Filled.Favorite, null) },
                    modifier = Modifier.clickable { page = "about" },
                )
            }
        }
    }
}

@Composable
private fun ThemePage(vm: AppViewModel, settings: AppSettings, modifier: Modifier) {
    val t = settings.theme
    fun save(transform: (ThemePrefsData) -> ThemePrefsData) =
        vm.saveSettings { s -> s.copy(theme = transform(s.theme)) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // ===== 个性化：昵称与头像 =====
        Text("个性化", style = MaterialTheme.typography.titleMedium)
        ListItem(
            headlineContent = { Text("显示昵称") },
            supportingContent = { Text("在头像上方显示 AI 与用户昵称") },
            trailingContent = {
                Switch(checked = t.showNick, onCheckedChange = { on -> save { it.copy(showNick = on) } })
            },
        )
        ListItem(
            headlineContent = { Text("显示头像") },
            supportingContent = { Text("关闭后聊天两侧不显示头像（昵称开关独立）") },
            trailingContent = {
                Switch(checked = t.showAvatar, onCheckedChange = { on -> save { it.copy(showAvatar = on) } })
            },
        )
        var userNick by remember(settings) { mutableStateOf(t.userNick) }
        var aiNick by remember(settings) { mutableStateOf(t.aiNick) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            ai.devcodex.agent.ui.components.Avatar(name = t.userNick, avatarFile = t.userAvatar, defaultColor = androidx.compose.ui.graphics.Color(0xFF059669), size = 44.dp)
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(value = userNick, onValueChange = { userNick = it }, label = { Text("你的昵称") }, singleLine = true, modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { save { it.copy(userNick = userNick) } }) { Text("保存") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            ai.devcodex.agent.ui.components.Avatar(name = t.aiNick, avatarFile = t.aiAvatar, defaultColor = androidx.compose.ui.graphics.Color(0xFF4F46E5), size = 44.dp)
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(value = aiNick, onValueChange = { aiNick = it }, label = { Text("AI 昵称") }, singleLine = true, modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { save { it.copy(aiNick = aiNick) } }) { Text("保存") }
        }
        // 头像从图库选择（复制进 filesDir/avatars）
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val userImgPicker = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            runCatching {
                val dir = java.io.File(ctx.filesDir, "avatars").apply { mkdirs() }
                val name = "user_" + java.util.UUID.randomUUID().toString().take(6) + ".png"
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.File(dir, name).outputStream().use { input.copyTo(it) }
                }
                save { it.copy(userAvatar = name) }
            }
        }
        val aiImgPicker = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            runCatching {
                val dir = java.io.File(ctx.filesDir, "avatars").apply { mkdirs() }
                val name = "ai_" + java.util.UUID.randomUUID().toString().take(6) + ".png"
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.File(dir, name).outputStream().use { input.copyTo(it) }
                }
                save { it.copy(aiAvatar = name) }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { userImgPicker.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("更换你的头像") }
            OutlinedButton(onClick = { aiImgPicker.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("更换 AI 头像") }
        }
        Spacer(Modifier.padding(10.dp))

        // ===== 界面偏好 =====
        Text("界面偏好", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.padding(6.dp))
        Text("导航位置", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            listOf("bottom" to "底部", "rail" to "左侧", "immersive" to "沉浸").forEach { (v, label) ->
                FilterChip(selected = t.navPosition == v, onClick = { save { it.copy(navPosition = v) } }, label = { Text(label) })
            }
        }
        if (t.navPosition == "immersive") {
            Text(
                "沉浸模式：仅对话页全屏无导航，通过会话抽屉右上角齿轮回到设置",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ListItem(
            headlineContent = { Text("沉浸模式（对话页隐藏导航）") },
            supportingContent = { Text("底部/左侧导航时也可单独开启") },
            trailingContent = {
                Switch(checked = t.immersive, onCheckedChange = { on -> save { it.copy(immersive = on) } })
            },
        )
        Spacer(Modifier.padding(12.dp))
        HorizontalDivider()
        Spacer(Modifier.padding(12.dp))

        Text("主题预设", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.padding(6.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth().height(320.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(PresetThemes) { preset ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { save { it.copy(presetId = preset.id) } },
                ) {
                    // MChat 式迷你界面预览卡（真实 preset 配色）
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(preset.light.surface)
                            .border(
                                width = if (t.presetId == preset.id) 2.dp else 0.5.dp,
                                color = if (t.presetId == preset.id) MaterialTheme.colorScheme.primary else preset.light.outlineVariant,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(preset.light.primary))
                        Box(
                            Modifier.align(Alignment.End).clip(RoundedCornerShape(6.dp, 6.dp, 2.dp, 6.dp))
                                .background(preset.light.primary).padding(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("Hi", style = MaterialTheme.typography.labelSmall, color = preset.light.onPrimary) }
                        Row {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(preset.light.secondary))
                            Spacer(Modifier.width(4.dp))
                            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(preset.light.surfaceVariant).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                Text("AI 回复", style = MaterialTheme.typography.labelSmall, color = preset.light.onSurface)
                            }
                        }
                        Column(
                            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(4.dp))
                                .background(androidx.compose.ui.graphics.Color(0xFF161B22)).padding(4.dp)
                        ) {
                            Text("fun main()", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = androidx.compose.ui.graphics.Color(0xFFC9D1D9))
                            Text("  println(ok)", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = androidx.compose.ui.graphics.Color(0xFFC3E88D))
                        }
                    }
                    // 旧色圈
                    Box(
                        Modifier.size(56.dp)
                            .clip(CircleShape)
                            .background(preset.light.primary)
                            .border(
                                width = if (t.presetId == preset.id) 3.dp else 0.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(preset.light.onPrimary))
                    }
                    Spacer(Modifier.padding(4.dp))
                    Text(preset.name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.padding(12.dp))

        // 代码块配色预览（MChat 式）
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("代码块配色预览", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                val pv = ai.devcodex.agent.m3l.darkPaletteForTheme(t.codeTheme)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(pv.background).padding(10.dp)
                ) {
                    Text("fun bubble(msg: Message) {", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = pv.text)
                    Text("    val color = Color(0xFF4285F4)", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = pv.string)
                    Text("    // 主题注释色", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = pv.comment)
                    Text("    return shape(color)", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = pv.keyword)
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    listOf("github" to "GitHub", "dracula" to "Dracula", "onedark" to "One Dark", "solar" to "Solarized", "monokai" to "Monokai", "nord" to "Nord", "gruvbox" to "Gruvbox", "vscode" to "VSCode", "highcontrast" to "高对比").forEach { (v, label) ->
                        FilterChip(selected = t.codeTheme == v, onClick = {
                            save { it.copy(codeTheme = v) }
                            ai.devcodex.agent.m3l.CodeThemeHolder.current = v
                        }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                    }
                }
            }
        }
        Text("外观模式", style = MaterialTheme.typography.titleMedium)
        ListItem(
            headlineContent = { Text("思考内容自动折叠") },
            supportingContent = { Text("开启后，思考结束将自动折叠思考内容") },
            trailingContent = {
                Switch(checked = t.autoCollapseThinking, onCheckedChange = { on -> save { it.copy(autoCollapseThinking = on) } })
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            listOf("SYSTEM" to "跟随系统", "LIGHT" to "浅色", "DARK" to "深色").forEach { (v, label) ->
                FilterChip(selected = t.colorMode == v, onClick = { save { it.copy(colorMode = v) } }, label = { Text(label) })
            }
        }
        Spacer(Modifier.padding(8.dp))

        Text("字体", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.padding(6.dp))
        // MChat 式：两行各带预览文字的字体选择
        val appFonts = listOf("gsf" to "Sans Flex", "serif" to "衬线", "anthropic" to "Anthropic", "oplus" to "Oplus Serif", "mono" to "JetBrains Mono", "system" to "系统默认")
        val chatFonts = listOf("gsf" to "Sans Flex", "serif" to "衬线", "anthropic" to "Anthropic Serif", "oplus" to "Oplus Serif", "mono" to "JetBrains Mono", "system" to "系统默认")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            appFonts.forEach { (v, label) ->
                FilterChip(
                    selected = t.appFont == v,
                    onClick = { save { it.copy(appFont = v) } },
                    label = {
                        Text(
                            "Aa " + label,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = ai.devcodex.agent.theme.FlexFonts.byId(v),
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.height(4.dp))
        // 自定义字体导入（rikkahub 式：选 ttf/otf → filesDir/fonts → 芯片列表可选）
        val fontPicker = rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent()
        ) { uri: android.net.Uri? ->
            if (uri != null) {
                runCatching {
                    val fCtx = ai.devcodex.agent.tools.BuiltinTools.appContext()
                    val tmp = java.io.File(fCtx.cacheDir, "font_import_" + System.currentTimeMillis())
                    fCtx.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    val name = ai.devcodex.agent.theme.CustomFonts.import(fCtx, tmp)
                    if (name != null) { save { it.copy(chatFont = "custom:" + name) }; vm.toast.value = "字体已导入: " + name } else vm.toast.value = "导入失败"
                    tmp.delete()
                }
            }
        }
        OutlinedButton(onClick = { fontPicker.launch("*/*") }) {
            Icon(Icons.Filled.Add, null, Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("导入自定义字体（ttf/otf）", style = MaterialTheme.typography.labelSmall)
        }
        val fCtx2 = ai.devcodex.agent.tools.BuiltinTools.appContext()
        val customFontNames = androidx.compose.runtime.remember { ai.devcodex.agent.theme.CustomFonts.list(fCtx2) }
        if (customFontNames.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                customFontNames.forEach { name ->
                    FilterChip(
                        selected = t.chatFont == "custom:" + name,
                        onClick = { save { it.copy(chatFont = "custom:" + name) } },
                        label = { Text("Aa " + name.take(8), style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("对话字体（聊天气泡）", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chatFonts.forEach { (v, label) ->
                FilterChip(
                    selected = t.chatFont == v,
                    onClick = { save { it.copy(chatFont = v) } },
                    label = {
                        Text(
                            "Aa " + label,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = ai.devcodex.agent.theme.FlexFonts.byId(v),
                        )
                    },
                )
            }
        }
        Spacer(Modifier.padding(12.dp))

        ListItem(
            headlineContent = { Text("AMOLED 纯黑") },
            supportingContent = { Text("深色模式下使用纯黑背景（省电）") },
            trailingContent = {
                Switch(checked = t.amoled, onCheckedChange = { on -> save { it.copy(amoled = on) } })
            },
        )
        ListItem(
            headlineContent = { Text("动态取色") },
            supportingContent = { Text("Android 12+ 跟随壁纸取色（覆盖预设）") },
            trailingContent = {
                Switch(checked = t.dynamicColor, onCheckedChange = { on -> save { it.copy(dynamicColor = on) } })
            },
        )
    }
}

@Composable
private fun ToolsPermPage(vm: AppViewModel, settings: AppSettings, modifier: Modifier) {
    val context = LocalContext.current
    var rootChecked by remember { mutableStateOf<Boolean?>(null) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {

        // ============ AXS / Termux 服务 ============
        Text("AXS / Termux 服务", style = MaterialTheme.typography.titleMedium)
        Text("在 Termux 里启动 AXS 等 HTTP shell 服务后填地址，本应用获得 ADB 级特权通道（优先于沙盒）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var axsInput by remember { mutableStateOf(settings.axsUrl) }
        OutlinedTextField(
            value = axsInput, onValueChange = { axsInput = it },
            label = { Text("服务地址（如 http://127.0.0.1:8912）") },
            singleLine = true, textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        val axsScope = androidx.compose.runtime.rememberCoroutineScope()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                vm.saveSettings { it.copy(axsUrl = axsInput.trim()) }
                ai.devcodex.agent.shell.ShellAccess.axsUrl = axsInput.trim()
            }) { Text("应用") }
            TextButton(onClick = {
                axsScope.launch {
                    ai.devcodex.agent.shell.ShellAccess.axsUrl = axsInput.trim()
                    val test = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ai.devcodex.agent.shell.ShellAccess.axsExec("id") }
                    vm.toast.value = "AXS 测试: " + test.take(200)
                }
            }) { Text("测试(id)") }
        }
        Spacer(Modifier.padding(10.dp))

        // ============ SSH 隧道 ============
        Text("SSH 隧道", style = MaterialTheme.typography.titleMedium)
        Text("连接远程服务器：AI 可 ssh_exec 远程执行、ssh_forward 建本地端口转发隧道（映射内网 LLM API 等）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var sshHost by remember { mutableStateOf(settings.sshHost) }
        var sshPort by remember { mutableStateOf(settings.sshPort.toString()) }
        var sshUser by remember { mutableStateOf(settings.sshUser) }
        var sshPass by remember { mutableStateOf(settings.sshPassword) }
        var sshKey by remember { mutableStateOf(settings.sshKeyPath) }
        OutlinedTextField(value = sshHost, onValueChange = { sshHost = it }, label = { Text("主机") }, singleLine = true, textStyle = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = sshPort, onValueChange = { sshPort = it }, label = { Text("端口") }, singleLine = true, textStyle = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.3f))
            OutlinedTextField(value = sshUser, onValueChange = { sshUser = it }, label = { Text("用户名") }, singleLine = true, textStyle = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.7f))
        }
        OutlinedTextField(value = sshPass, onValueChange = { sshPass = it }, label = { Text("密码（或留空用密钥）") }, singleLine = true, textStyle = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        OutlinedTextField(value = sshKey, onValueChange = { sshKey = it }, label = { Text("私钥路径（可选，如 /sdcard/id_rsa）") }, singleLine = true, textStyle = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                vm.saveSettings { it.copy(sshHost = sshHost.trim(), sshPort = sshPort.toIntOrNull() ?: 22, sshUser = sshUser.trim(), sshPassword = sshPass, sshKeyPath = sshKey.trim()) }
                ai.devcodex.agent.shell.SshClient.config = if (sshHost.isNotBlank()) ai.devcodex.agent.shell.SshClient.SshConfig(sshHost.trim(), sshPort.toIntOrNull() ?: 22, sshUser.trim(), sshPass, sshKey.trim()) else null
            }) { Text("保存") }
            TextButton(onClick = {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    ai.devcodex.agent.shell.SshClient.config = ai.devcodex.agent.shell.SshClient.SshConfig(sshHost.trim(), sshPort.toIntOrNull() ?: 22, sshUser.trim(), sshPass, sshKey.trim())
                    val r = ai.devcodex.agent.shell.SshClient.exec("echo ok && uname -n")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { sshHost = sshHost }
                }
            }) { Text("测试连接") }
        }
        Spacer(Modifier.padding(10.dp))

        // ============ 后台保护 ============
        Text("后台保护", style = MaterialTheme.typography.titleMedium)
        ListItem(
            headlineContent = { Text("电池优化豁免") },
            supportingContent = { Text("长对话/后台任务不被系统杀（跳系统设置，需手动允许）") },
            modifier = Modifier.clickable {
                runCatching {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:ai.devcodex.agent")))
                }
            },
        )
        Spacer(Modifier.padding(10.dp))

        // ============ 工具权限（全局默认） ============
        Text("工具权限", style = MaterialTheme.typography.titleMedium)
        Text("允许：全部直接执行 · 询问（默认）：低风险直接执行，其余执行前询问 · 禁止：全部不执行。单工具的设置优先于全局默认。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val curLevel = ai.devcodex.agent.agent.PermissionLevel.parse(settings.permissionDefault).name
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("ALLOW" to "允许", "ASK" to "询问", "FORBID" to "禁止").forEach { (v, label) ->
                        FilterChip(
                            selected = curLevel == v,
                            onClick = { vm.saveSettings { s -> s.copy(permissionDefault = v) } },
                            label = { Text(label, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.padding(4.dp))
                Text(
                    when (curLevel) {
                        "ALLOW" -> "当前：所有工具直接执行，不再弹出审批。"
                        "FORBID" -> "当前：所有工具一律不执行，除非单工具另行允许。"
                        else -> "当前：低风险工具直接执行，其余在执行前询问。"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // ============ 工作区（Operit 式自定义） ============
        Text("工作区", style = MaterialTheme.typography.titleMedium)
        val curWs = settings.workspaceDir.ifBlank {
            "应用私有目录（${context.filesDir.absolutePath}/workspace）"
        }
        Text("当前: $curWs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        var wsInput by remember { mutableStateOf(settings.workspaceDir) }
        OutlinedTextField(
            value = wsInput, onValueChange = { wsInput = it },
            label = { Text("自定义工作区绝对路径（留空=应用私有）") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
            listOf("" to "私有目录", "/sdcard/DevCodex" to "DevCodex", "/sdcard/Documents" to "Documents", "/sdcard/Download" to "Download").forEach { (v, label) ->
                FilterChip(selected = wsInput == v, onClick = { wsInput = v }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
            }
        }
        var wsMsg by remember { mutableStateOf<String?>(null) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val dir = wsInput.trim()
                val f = java.io.File(dir)
                val ok = f.exists() && f.isDirectory && f.canWrite() || (f.mkdirs() && f.canWrite())
                wsMsg = if (dir.isBlank()) "已恢复应用私有工作区"
                        else if (ok) "目录可写，已应用"
                        else "目录不可写（需存储权限或改用已存在目录）"
                if (dir.isBlank() || ok) {
                    vm.saveSettings { it.copy(workspaceDir = dir) }
                    ai.devcodex.agent.data.WorkspaceConfig.dir = dir
                }
            }) { Text("应用") }
            if (wsMsg != null) Text(wsMsg!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.padding(10.dp))

        // ============ 权限通道 ============
        Text("权限通道", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.padding(6.dp))

        // Shizuku
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AdminPanelSettings, null)
                    Spacer(Modifier.padding(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Shizuku（ADB 权限）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (ShellAccess.shizukuAvailable()) "服务运行中" else "未检测到 Shizuku 服务",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ShellAccess.shizukuAvailable()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.padding(4.dp))
                Text(
                    "通过 Shizuku 获得 ADB 级权限执行 shell 与文件操作。需要先安装 Shizuku App 并按其指引启动服务。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        if (!ShellAccess.shizukuAvailable()) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app"))) }
                        } else if (!ShellAccess.shizukuPermissionGranted()) {
                            ShellAccess.requestShizukuPermission()
                        } else ShellAccess.bindShizukuService()
                    }) {
                        Text(
                            when {
                                !ShellAccess.shizukuAvailable() -> "获取 Shizuku"
                                !ShellAccess.shizukuPermissionGranted() -> "授权"
                                else -> "重新绑定服务"
                            }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.padding(8.dp))

        // Root
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AdminPanelSettings, null)
                    Spacer(Modifier.padding(6.dp))
                    Column {
                        Text("Root", style = MaterialTheme.typography.titleSmall)
                        Text(
                            rootChecked?.let { if (it) "已授权" else "不可用" } ?: "未检测",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (rootChecked == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.padding(4.dp))
                Text("通过 su 执行特权命令（无 Shizuku 时的备选通道）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { rootChecked = ShellAccess.rootAvailable() }) { Text("检测 Root") }
            }
        }
        Spacer(Modifier.padding(8.dp))

        // Accessibility
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Accessibility, null)
                    Spacer(Modifier.padding(6.dp))
                    Column {
                        Text("无障碍服务（界面自动化）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (ai.devcodex.agent.service.DcAccessibilityService.isEnabled()) "已启用" else "未启用",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ai.devcodex.agent.service.DcAccessibilityService.isEnabled()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.padding(4.dp))
                Text("启用后智能体可读取屏幕内容、点击、滑动（ui_snapshot / ui_tap 等工具）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }) { Text("去系统设置开启") }
            }
        }
        Spacer(Modifier.padding(16.dp))

        // ============ 思考推理等级 ============
        Text("思考推理等级", style = MaterialTheme.typography.titleMedium)
        Text("控制模型的思考深度（支持 OpenAI o/gpt-5 系与 Claude 3.7+）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val efforts = listOf("", "low", "medium", "high", "xhigh", "max", "ultra")
        val eLabels = listOf("Off", "Low", "Medium", "High", "xHigh", "Max", "Ultra")
        val eCur = efforts.indexOf(settings.reasoningEffort).coerceAtLeast(0)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Faster", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.Slider(
                value = eCur.toFloat(),
                onValueChange = { v ->
                    val idx = (v + 0.5f).toInt().coerceIn(0, 6)
                    if (idx != eCur) vm.saveSettings { it.copy(reasoningEffort = efforts[idx]) }
                },
                valueRange = 0f..6f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text("Smarter", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "当前：${eLabels[eCur]}（Faster 响应快 · Smarter 思考深）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.padding(8.dp))

        // ============ MCP 服务器 ============
        Text("MCP 服务器", style = MaterialTheme.typography.titleMedium)
        Text("连接 Streamable HTTP 协议的 MCP 服务，其工具自动注入本应用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.padding(6.dp))
        settings.mcpServers.forEach { m ->
            ListItem(
                overlineContent = { Text("MCP", style = MaterialTheme.typography.labelSmall) },
                headlineContent = { Text(m.name) },
                supportingContent = { Text((if (m.protocol == "sse") "[SSE] " else "") + m.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    Row {
                        Switch(checked = m.enabled, onCheckedChange = { on ->
                            vm.saveSettings { s -> s.copy(mcpServers = s.mcpServers.map { if (it.id == m.id) it.copy(enabled = on) else it }) }
                        })
                        IconButton(onClick = {
                            vm.saveSettings { s -> s.copy(mcpServers = s.mcpServers.filter { it.id != m.id }) }
                        }) { Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp)) }
                    }
                },
            )
        }
        var newMcpName by remember { mutableStateOf("") }
        var newMcpUrl by remember { mutableStateOf("") }
        var newMcpProto by remember { mutableStateOf("auto") }
        var newMcpHeaders by remember { mutableStateOf("") }
        OutlinedTextField(value = newMcpName, onValueChange = { newMcpName = it }, label = { Text("名称") }, singleLine = true, textStyle = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        OutlinedTextField(value = newMcpUrl, onValueChange = { newMcpUrl = it }, label = { Text("MCP URL（streamable 填完整端点 / SSE 填 /sse 端点）") }, singleLine = true, textStyle = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Text("协议", style = MaterialTheme.typography.labelSmall)
            listOf("auto" to "自动", "streamable" to "Streamable", "sse" to "SSE").forEach { (v, label) ->
                FilterChip(selected = newMcpProto == v, onClick = { newMcpProto = v }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
            }
        }
        OutlinedTextField(value = newMcpHeaders, onValueChange = { newMcpHeaders = it }, label = { Text("自定义头（可选，每行 Key: Value，如 Authorization: Bearer xxx）") }, textStyle = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        TextButton(onClick = {
            if (newMcpUrl.isNotBlank()) {
                val id = java.util.UUID.randomUUID().toString().take(8)
                val hdrs = newMcpHeaders.lines().mapNotNull { l ->
                    val i = l.indexOf(':')
                    if (i > 0) l.take(i).trim() to l.substring(i + 1).trim() else null
                }.toMap()
                vm.saveSettings { s -> s.copy(mcpServers = s.mcpServers + ai.devcodex.agent.provider.McpClient.McpServerConfig(id, newMcpName.ifBlank { "MCP-" + id }, newMcpUrl.trim(), protocol = newMcpProto, headers = hdrs)) }
                newMcpName = ""; newMcpUrl = ""; newMcpHeaders = ""
            }
        }) { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Spacer(Modifier.padding(4.dp)); Text("添加 MCP 服务器") }
        Spacer(Modifier.padding(10.dp))

        // ============ 自定义工具 ============
        Text("自定义工具", style = MaterialTheme.typography.titleMedium)
        Text("shell 命令模板，{{参数名}} 会被 AI 填的参数替换，走 Shizuku/Root 执行", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.padding(6.dp))
        settings.customTools.forEach { ct ->
            ListItem(
                overlineContent = { Text("自定义", style = MaterialTheme.typography.labelSmall) },
                headlineContent = { Text(ct.name, fontFamily = FontFamily.Monospace) },
                supportingContent = { Text(ct.command, maxLines = 2, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                trailingContent = {
                    Row {
                        Switch(checked = ct.enabled, onCheckedChange = { on ->
                            vm.saveSettings { s -> s.copy(customTools = s.customTools.map { if (it.name == ct.name) it.copy(enabled = on) else it }) }
                        })
                        IconButton(onClick = {
                            vm.saveSettings { s -> s.copy(customTools = s.customTools.filter { it.name != ct.name }) }
                        }) { Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp)) }
                    }
                },
            )
        }
        var ctName by remember { mutableStateOf("") }
        var ctDesc by remember { mutableStateOf("") }
        var ctCmd by remember { mutableStateOf("") }
        OutlinedTextField(value = ctName, onValueChange = { ctName = it }, label = { Text("工具名（英文）") }, singleLine = true, textStyle = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        OutlinedTextField(value = ctDesc, onValueChange = { ctDesc = it }, label = { Text("描述（AI 靠它判断何时用）") }, singleLine = true, textStyle = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        OutlinedTextField(value = ctCmd, onValueChange = { ctCmd = it }, label = { Text("命令模板，如 pm list packages | grep {{keyword}}") }, textStyle = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        TextButton(onClick = {
            if (ctName.isNotBlank() && ctCmd.isNotBlank()) {
                vm.saveSettings { s -> s.copy(customTools = s.customTools + ai.devcodex.agent.data.CustomToolDef(ctName.trim(), ctDesc.trim(), "{}", ctCmd.trim())) }
                ctName = ""; ctDesc = ""; ctCmd = ""
            }
        }) { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Spacer(Modifier.padding(4.dp)); Text("添加自定义工具") }
        Spacer(Modifier.padding(12.dp))

        // ============ 内置工具开关 ============
        Text("内置工具", style = MaterialTheme.typography.titleMedium)
        Text("关闭后智能体将看不到对应能力", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.padding(6.dp))
        (ai.devcodex.agent.tools.BuiltinTools.all + ai.devcodex.agent.tools.AdvancedFsTools.all + ai.devcodex.agent.tools.ExtraTools.all + ai.devcodex.agent.tools.MediaTools.all).forEach { tool ->
            val enabled = settings.enabledTools.contains(tool.name)
            val toolLevel = settings.toolPermissions[tool.name]?.let { ai.devcodex.agent.agent.PermissionLevel.parse(it).name }
            ListItem(
                headlineContent = { Text(tool.name, style = MaterialTheme.typography.labelLarge) },
                supportingContent = { Text(tool.description, maxLines = 2, style = MaterialTheme.typography.bodySmall) },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        Switch(
                            checked = enabled,
                            onCheckedChange = { on ->
                                vm.saveSettings { s ->
                                    s.copy(enabledTools = if (on) s.enabledTools + tool.name else s.enabledTools - tool.name)
                                }
                            },
                        )
                    }
                },
            )
            if (enabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                ) {
                    Text("权限：", style = MaterialTheme.typography.labelSmall)
                    var menuOpen by remember { mutableStateOf(false) }
                    val options = listOf<Pair<String?, String>>(null to "跟随全局", "ALLOW" to "允许", "ASK" to "询问", "FORBID" to "禁止")
                    Box {
                        OutlinedButton(
                            onClick = { menuOpen = true },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) {
                            Text(options.firstOrNull { it.first == toolLevel }?.second ?: "跟随全局", style = MaterialTheme.typography.labelSmall)
                            Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            options.forEach { (v, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        menuOpen = false
                                        vm.saveSettings { s ->
                                            s.copy(toolPermissions = if (v == null) s.toolPermissions - tool.name else s.toolPermissions + (tool.name to v))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.padding(32.dp))
    }
}

/** 技能管理页：列表/开关/删除/从文件安装 */
@Composable
private fun SkillsPage(vm: AppViewModel, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun openUri(u: String) = runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)))
    }
    var showPrivacyDoc by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showPluginDoc by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val privacyDocText = """
# DevCodex 隐私政策

## 数据收集
DevCodex **不收集任何用户数据**。所有会话、角色卡、设置、工作区文件均存储在设备本地（应用私有目录）。

## 网络传输
仅当你配置了 AI 提供商（如 DeepSeek/OpenAI）时，聊天内容会直接发送到**你配置的** API 端点。DevCodex 不经过任何第三方中转服务器。

## 权限使用
- 存储权限：仅用于读写你指定的工作区文件
- 网络权限：仅用于调用你配置的 AI API

## 删除数据
卸载应用即删除全部本地数据。
""".trim()
    if (showPrivacyDoc) {
        ai.devcodex.agent.ui.components.DocViewerDialog(title = "隐私政策", markdown = privacyDocText) { showPrivacyDoc = false }
    }
    if (showPluginDoc) {
        ai.devcodex.agent.ui.components.DocViewerDialog(title = "插件开发文档", markdown = PLUGIN_DOC_MD) { showPluginDoc = false }
    }
    var skills by remember { mutableStateOf(listOf<ai.devcodex.agent.data.SkillRepo.Skill>()) }
    var msg by remember { mutableStateOf<String?>(null) }
    fun reload() {
        skills = ai.devcodex.agent.data.SkillRepo(context).list()
    }
    LaunchedEffect(Unit) { reload() }

    val repo = ai.devcodex.agent.data.SkillRepo(context)
    var ghUrl by remember { mutableStateOf("") }
    var ghBusy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val name = (uri.lastPathSegment ?: "").lowercase()
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("读取失败")
                    if (name.endsWith(".zip") || name.endsWith(".skill")) {
                        val tmp = java.io.File(context.cacheDir, "import_skill.zip")
                        tmp.writeBytes(bytes)
                        repo.installFromZipFile(tmp)
                            .onSuccess { reload(); msg = "已安装技能（含附属文件）：${it.name}" }
                            .onFailure { msg = "安装失败：${it.message}" }
                        tmp.delete()
                    } else {
                        repo.install(String(bytes))
                            .onSuccess { reload(); msg = "已安装技能：${it.name}" }
                            .onFailure { msg = "安装失败：${it.message}" }
                    }
                }.onFailure { msg = "读取失败：${it.message}" }
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { picker.launch("*/*") },
                icon = { Icon(Icons.Filled.School, null) },
                text = { Text("导入技能包/文件") },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            // 说明卡
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("技能 = AI 的说明书", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        "SKILL.md 格式：简介常驻提示词，正文由 AI 按需读取（skill_read）。\n兼容 agentskills.io 标准，Claude Code / android-skills 生态的技能直接装。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                    )
                }
            }
            // GitHub 导入卡
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("从 GitHub 导入", style = MaterialTheme.typography.titleSmall)
                    Text("支持 owner/repo、/tree/分支、子目录路径（取该目录 SKILL.md + 附属文件）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedTextField(
                            value = ghUrl, onValueChange = { ghUrl = it },
                            placeholder = { Text("github.com/user/repo/tree/main/skills/foo") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                ghBusy = true
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    repo.installFromGithub(ghUrl)
                                        .onSuccess { reload(); msg = "已从 GitHub 安装：${it.name}" }
                                        .onFailure { msg = "GitHub 导入失败：${it.message}" }
                                    ghBusy = false
                                }
                            },
                            enabled = ghUrl.isNotBlank() && !ghBusy,
                        ) { Text("导入") }
                    }
                    if (ghBusy) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            msg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp))
            }
            if (skills.isEmpty()) {
                // 空态：居中图形 + 引导
                Column(
                    Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.School, null,
                        Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.padding(10.dp))
                    Text("还没有技能", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "点右下角安装 SKILL.md，或去 GitHub 搜\nagentskills / android-skills 下载技能文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                Spacer(Modifier.padding(8.dp))
                Text("已安装 ${skills.size} 个", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(4.dp))
                skills.forEach { sk ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (sk.enabled) MaterialTheme.colorScheme.surfaceContainerLow
                            else MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(sk.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text(
                                    if (sk.enabled) "已启用" else "已停用",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (sk.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clickable {
                                        ai.devcodex.agent.data.SkillRepo(context).toggle(sk.name); reload()
                                    },
                                )
                                Switch(checked = sk.enabled, onCheckedChange = {
                                    ai.devcodex.agent.data.SkillRepo(context).toggle(sk.name); reload()
                                })
                                IconButton(onClick = {
                                    ai.devcodex.agent.data.SkillRepo(context).delete(sk.name); reload()
                                }) { Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp)) }
                            }
                            Text(
                                sk.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 关于页：作者/反馈/赞助/隐私政策 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutPage(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val scroll = rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current
    fun openUri(u: String) = runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)))
    }
    var showPrivacyDoc by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showPluginDoc by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val privacyDocText = """
# DevCodex 隐私政策

## 数据收集
DevCodex **不收集任何用户数据**。所有会话、角色卡、设置、工作区文件均存储在设备本地（应用私有目录）。

## 网络传输
仅当你配置了 AI 提供商（如 DeepSeek/OpenAI）时，聊天内容会直接发送到**你配置的** API 端点。DevCodex 不经过任何第三方中转服务器。

## 权限使用
- 存储权限：仅用于读写你指定的工作区文件
- 网络权限：仅用于调用你配置的 AI API

## 删除数据
卸载应用即删除全部本地数据。
""".trim()
    if (showPrivacyDoc) {
        ai.devcodex.agent.ui.components.DocViewerDialog(title = "隐私政策", markdown = privacyDocText) { showPrivacyDoc = false }
    }
    if (showPluginDoc) {
        ai.devcodex.agent.ui.components.DocViewerDialog(title = "插件开发文档", markdown = PLUGIN_DOC_MD) { showPluginDoc = false }
    }

    Column(modifier.fillMaxSize()) {
        // ===== rikkahub SettingAboutPage 完整复刻（136dp logo+两组 CardGroup）=====
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 软件自己的图标（取自应用启动图标 mipmap/ic_launcher）
                androidx.compose.foundation.layout.Box(
                    Modifier.size(136.dp).background(
                        MaterialTheme.colorScheme.primaryContainer,
                        androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    val logoBmp = androidx.compose.runtime.remember {
                        runCatching {
                            context.packageManager.getApplicationIcon(context.packageName).let { d -> val s = 256; val b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(b); d.setBounds(0, 0, s, s); d.draw(c); b }
                        }.getOrNull()
                    }
                    if (logoBmp != null) Image(
                        bitmap = logoBmp.asImageBitmap(),
                        contentDescription = "DevCodex",
                        modifier = Modifier.size(120.dp).clip(MaterialTheme.shapes.extraLarge),
                    ) else Icon(Icons.Filled.Build, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text(
                    "DevCodex",
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Black),
                )
                Text("手机上的 AI 编程助手", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("v" + ai.devcodex.agent.BuildConfig.VERSION_NAME, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Text("应用信息", Modifier.fillMaxWidth().padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            val c1 = MaterialTheme.colorScheme.primaryContainer
            val t1 = MaterialTheme.colorScheme.onPrimaryContainer
            ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(c1, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Code, null, Modifier.size(20.dp), tint = t1) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("版本", style = MaterialTheme.typography.bodyLarge)
                            Text(ai.devcodex.agent.BuildConfig.VERSION_NAME + " / " + ai.devcodex.agent.BuildConfig.VERSION_CODE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(c1, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Smartphone, null, Modifier.size(20.dp), tint = t1) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("系统", style = MaterialTheme.typography.bodyLarge)
                            Text(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " / Android " + android.os.Build.VERSION.RELEASE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text("社区与文档", Modifier.fillMaxWidth().padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            val c2 = MaterialTheme.colorScheme.tertiaryContainer
            val t2 = MaterialTheme.colorScheme.onTertiaryContainer
            ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Column {
                    Row(Modifier.fillMaxWidth().clickable { }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(c2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Public, null, Modifier.size(20.dp), tint = t2) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("官网", style = MaterialTheme.typography.bodyLarge)
                            Text("github.com/Moirania/DevCodex", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable { }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(c2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(GitHubIcon, null, Modifier.size(22.dp), tint = t2) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("GitHub", style = MaterialTheme.typography.bodyLarge)
                            Text("github.com/Moirania/DevCodex", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable { openUri("https://qun.qq.com/universal-share/share?ac=1&authKey=Y0p5CQQcAr6zABChA7uCRctrZqMAJ9XomRgVVRlE1Zt5W9qha%2FqGOkIjqf%2FkMlDf&busi_data=eyJncm91cENvZGUiOiIyMDgxODE2MDQiLCJ0b2tlbiI6IkI0VGE3a0VaL1Q1a3lWYkJYNm5zMU1SeHZnMUozSGxyV0JiVHN5QkQvNXZNUkZnb0taelB0TXpVMEJyQzVoN24iLCJ1aW4iOiIzNjMzMTkwMjY5In0%3D&data=sLRegEFQPFXCtZOVjO6-UPMgaZVw_71eem7w0ii5tcwH8kihxzyXmFDF9twUHtaSUhc43RKwOvcBU4Xa0LRFXQ&svctype=4&tempid=h5_group_info") }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(c2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Chat, null, Modifier.size(20.dp), tint = t2) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("QQ 群 · DeepSeek", style = MaterialTheme.typography.bodyLarge)
                            Text("208181604（点击加入群聊）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable { showPluginDoc = true }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(c2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Extension, null, Modifier.size(20.dp), tint = t2) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("插件开发文档", style = MaterialTheme.typography.bodyLarge)
                            Text("点击查看（应用内展示）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable { openUri("https://github.com/Moirania/DevCodex#readme") }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(c2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.MenuBook, null, Modifier.size(20.dp), tint = t2) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("开发文档", style = MaterialTheme.typography.bodyLarge)
                            Text("github.com/Moirania/DevCodex#readme", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable { showPrivacyDoc = true }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(c2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.PrivacyTip, null, Modifier.size(20.dp), tint = t2) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("隐私政策", style = MaterialTheme.typography.bodyLarge)
                            Text("点击查看（应用内展示）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            val exportScope = androidx.compose.runtime.rememberCoroutineScope()
            val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
            ) { uri ->
                if (uri != null) exportScope.launch {
                    val r = runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { ai.devcodex.agent.util.DebugLog.exportZip(context, it) }
                    }
                    android.widget.Toast.makeText(
                        context,
                        if (r.isSuccess) "调试日志已导出" else "导出失败：" + r.exceptionOrNull()?.message,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
            OutlinedButton(onClick = { exportLauncher.launch("devcodex-debug-" + System.currentTimeMillis() + ".zip") }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Icon(Icons.Filled.Feedback, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("导出调试日志（zip）", style = MaterialTheme.typography.labelLarge) }
            OutlinedButton(onClick = { openUri("https://github.com/Moirania/DevCodex/issues") }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Icon(Icons.Filled.Feedback, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("反馈意见", style = MaterialTheme.typography.labelLarge) }
            var showReward by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            OutlinedButton(onClick = { showReward = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Icon(Icons.Filled.Favorite, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("微信赞助 / 请喝奶茶", style = MaterialTheme.typography.labelLarge) }
            if (showReward) {
                androidx.compose.ui.window.Dialog(onDismissRequest = { showReward = false }) {
                    androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("微信赞赏", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            val bmp = androidx.compose.runtime.remember {
                                runCatching {
                                    android.graphics.BitmapFactory.decodeStream(ctx.assets.open("wechat-reward.png"))
                                }.getOrNull()
                            }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "微信收款码",
                                    modifier = Modifier.size(260.dp),
                                )
                            } else Text("（收款码加载失败）", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Text("感谢支持 DevCodex 开发", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}



// GitHub octicon mark（rikkahub HugeIcons.Github 同款视觉）
private val GitHubIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = "GitHubMark", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 16f, viewportHeight = 16f,
    ).apply {
        path(
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
            pathFillType = androidx.compose.ui.graphics.PathFillType.NonZero,
        ) {
            // octicon mark-github v16 官方路径
            moveTo(8f, 0.0f)
            curveTo(3.58f, 0.0f, 0.0f, 3.58f, 0.0f, 8.0f)
            curveTo(0.0f, 11.54f, 2.29f, 14.53f, 5.47f, 15.59f)
            curveTo(5.87f, 15.67f, 6.02f, 15.42f, 6.02f, 15.21f)
            curveTo(6.02f, 15.02f, 6.01f, 14.42f, 6.01f, 13.79f)
            curveTo(4.0f, 14.23f, 3.52f, 12.9f, 3.52f, 12.9f)
            curveTo(3.15f, 11.96f, 2.61f, 11.72f, 2.61f, 11.72f)
            curveTo(1.86f, 11.21f, 2.67f, 11.22f, 2.67f, 11.22f)
            curveTo(3.5f, 11.28f, 3.94f, 12.07f, 3.94f, 12.07f)
            curveTo(4.68f, 13.33f, 5.89f, 12.97f, 6.04f, 12.76f)
            curveTo(6.07f, 12.25f, 6.28f, 11.9f, 6.51f, 11.71f)
            curveTo(4.74f, 11.51f, 2.87f, 10.83f, 2.87f, 7.94f)
            curveTo(2.87f, 7.12f, 3.16f, 6.45f, 3.95f, 5.92f)
            curveTo(3.9f, 5.73f, 3.65f, 4.96f, 4.01f, 3.94f)
            curveTo(4.01f, 3.94f, 4.65f, 3.74f, 6.0f, 4.69f)
            curveTo(6.6f, 4.52f, 7.24f, 4.44f, 7.88f, 4.43f)
            curveTo(8.52f, 4.44f, 9.16f, 4.52f, 9.76f, 4.69f)
            curveTo(11.11f, 3.74f, 11.75f, 3.94f, 11.75f, 3.94f)
            curveTo(12.11f, 4.96f, 11.86f, 5.73f, 11.81f, 5.92f)
            curveTo(12.6f, 6.45f, 12.89f, 7.12f, 12.89f, 7.94f)
            curveTo(12.89f, 10.84f, 11.02f, 11.51f, 9.24f, 11.7f)
            curveTo(9.53f, 11.95f, 9.79f, 12.45f, 9.79f, 13.22f)
            curveTo(9.79f, 14.31f, 9.78f, 15.19f, 9.78f, 15.47f)
            curveTo(9.78f, 15.68f, 9.93f, 15.93f, 10.33f, 15.85f)
            curveTo(13.51f, 14.53f, 15.8f, 11.54f, 15.8f, 8.0f)
            curveTo(15.8f, 3.58f, 12.42f, 0.0f, 8.0f, 0.0f)
            close()
        }
    }.build()
}
