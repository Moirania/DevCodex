package ai.devcodex.agent

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ai.devcodex.agent.agent.AgentEngine
import ai.devcodex.agent.agent.ApprovalRequest
import ai.devcodex.agent.data.AppSettings
import ai.devcodex.agent.data.ChatSession
import ai.devcodex.agent.data.PluginManifest
import ai.devcodex.agent.data.ProviderConfig
import ai.devcodex.agent.data.SessionRepo
import ai.devcodex.agent.data.WorkspaceRepo
import ai.devcodex.agent.data.ToolStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = app as DevCodexApp
    val settingsRepo get() = appCtx.settingsRepo
    val sessionRepo get() = appCtx.sessionRepo
    val pluginRepo get() = appCtx.pluginRepo
    val workspaceRepo get() = appCtx.workspaceRepo

    val settings = appCtx.settingsRepo.settings

    private val _plugins = MutableStateFlow<List<PluginManifest>>(emptyList())
    val plugins: StateFlow<List<PluginManifest>> = _plugins
    fun reloadPlugins() { _plugins.value = pluginRepo.list() }

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions
    fun reloadSessions() { _sessions.value = sessionRepo.list() }

    private val _activeSession = MutableStateFlow<ChatSession?>(null)
    val activeSession: StateFlow<ChatSession?> = _activeSession

    var runningJob: Job? = null
    val isRunning = MutableStateFlow(false)

    // 会话写盘代数：用户对会话的主动修改 +1；引擎写盘前校验，代数不匹配则丢弃。
    // 根因修复：停止/中断后的引擎旧消息集异步落盘，会覆盖用户已删除或回退的状态（消息复活）
    private val sessionGen = ConcurrentHashMap<String, Long>()
    private fun bumpGen(id: String) { sessionGen.merge(id, 1L, Long::plus) }

    // 引擎只拥有 messages；元数据（模型/上下文/开关/标题）以 UI 当前值优先，防运行中修改被回滚
    private fun mergeMeta(base: ChatSession, from: ChatSession?): ChatSession {
        val cur = from ?: return base
        return base.copy(
            contextOverride = cur.contextOverride,
            deepThink = cur.deepThink,
            webSearch = cur.webSearch,
            providerId = cur.providerId,
            modelId = cur.modelId,
            title = cur.title,
        )
    }

    val toast = MutableStateFlow<String?>(null)
    val navRequest = MutableStateFlow<String?>(null)   // "settings" 等页面跳转请求
    val settingsPageRequest = MutableStateFlow<String?>(null)  // 设置内子页跳转（stats 等）

    // 审批：挂起等待 UI 回调
    private val approvalWaiters = ConcurrentHashMap<String, (Boolean) -> Unit>()
    private val _pendingApproval = MutableStateFlow<Pair<ApprovalRequest, String>?>(null) // request + message about
    val pendingApproval: StateFlow<Pair<ApprovalRequest, String>?> = _pendingApproval

    val engine = AgentEngine(
        settings = { latestSettings },
        plugins = { _plugins.value },
        requestApproval = { req -> awaitApproval(req) },
    )

    // 最新设置快照（供非挂起上下文读取）
    private var latestSettings = AppSettings()

    init {
        runCatching { ai.devcodex.agent.data.CharacterRepo.init(getApplication()) }
        viewModelScope.launch {
            settings.collect { latestSettings = it }
        }
        // 旧审批模式与 toolPerms 一次性迁移到新权限字段（须先于设置读取生效）
        viewModelScope.launch {
            runCatching { settingsRepo.migratePermissionsIfNeeded() }
            // 生图/视频工具名并入旧用户已持久化的 enabledTools（一次性）
            runCatching { settingsRepo.migrateMediaToolsIfNeeded() }
        }
        reloadPlugins()
        reloadSessions()
        viewModelScope.launch {
            engine.events.collect { ev ->
                when (ev) {
                    is AgentEngine.UiEvent.Error -> toast.value = ev.message
                    is AgentEngine.UiEvent.Finished -> Unit
                }
            }
        }
    }

    fun openSession(id: String?) {
        val s = id?.let { sessionRepo.get(it) } ?: sessionRepo.newSession()
        if (id == null && pendingContext > 0) {
            val withCtx = s.copy(contextOverride = pendingContext)
            sessionRepo.save(withCtx)
            pendingContext = 0
            _activeSession.value = withCtx
        } else {
            _activeSession.value = s
        }
        reloadSessions()
    }

    // 流式节流：80ms 合并 UI 更新（防 token 级全列表重组 → OOM/卡死闪退）
    @Volatile private var lastUiEmit = 0L
    private fun throttledUpdate(session: ai.devcodex.agent.data.ChatSession, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastUiEmit > 80) {
            lastUiEmit = now
            _activeSession.value = session
        }
    }

    fun send(text: String) {
        val session = _activeSession.value ?: sessionRepo.newSession().also { _activeSession.value = it }
        if (text.isBlank() || isRunning.value) return
        // @引用 → 附件内容注入（LLM 侧带文件正文，UI 展示保持原文）
        val display = text.trim()
        var llmContent: String? = null
        val imagePaths = mutableListOf<String>()
        val mentions = Regex("@([\\w\\u4e00-\\u9fa5.\\-]+)").findAll(display).map { it.groupValues[1] }.toList()
        if (mentions.isNotEmpty()) {
            val sb = StringBuilder(display)
            for (name in mentions.distinct()) {
                val entry = workspaceRepo.list().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: continue
                if (!entry.isDir && entry.file.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp")) {
                    imagePaths += entry.relativePath
                    sb.append("\n\n[图片 ").append(entry.name).append("]")
                } else if (workspaceRepo.isTextFile(entry)) {
                    sb.append("\n\n[附件 ").append(entry.name).append("]\n")
                        .append(WorkspaceRepo.textPreview(entry.file, 15000))
                } else {
                    sb.append("\n\n[附件 ").append(entry.name).append("]（二进制文件，大小 ")
                        .append(workspaceRepo.formatSize(entry.size)).append("，可用工具按相对路径访问）")
                }
            }
            llmContent = sb.toString()
        }
        val userMsg = ai.devcodex.agent.data.Message(
            id = java.util.UUID.randomUUID().toString(),
            role = "user", content = display, llmContent = llmContent, imagePaths = imagePaths.toList(),
            timestamp = System.currentTimeMillis(),
        )
        val updated = session.copy(
            messages = session.messages + userMsg,
            title = if (session.title == "新会话") display.take(20) else session.title,
            updatedAt = System.currentTimeMillis(),
        )
        _activeSession.value = updated
        bumpGen(updated.id)
        sessionRepo.save(updated)
        startAgent(updated)
    }

    /** 重发：截断该提问之后的所有消息（含回答），保留提问本身重新生成——不再追加重复提问 */
    fun resendFrom(msgId: String) {
        val s = _activeSession.value ?: return
        if (isRunning.value) { toast.value = "回复进行中，稍后重发"; return }
        val idx = s.messages.indexOfFirst { it.id == msgId && it.role == "user" }
        if (idx < 0) return
        val rewound = s.copy(messages = s.messages.take(idx + 1), updatedAt = System.currentTimeMillis())
        bumpGen(rewound.id)
        _activeSession.value = rewound
        sessionRepo.save(rewound)
        startAgent(rewound)
    }

    private fun startAgent(session: ChatSession) {
        isRunning.value = true
        val startGen = sessionGen[session.id] ?: 0L
        runningJob = viewModelScope.launch {
            var lastSave = 0L
            var finalSession = session
            try {
                engine.run(session) { s ->
                    val merged = mergeMeta(s, _activeSession.value?.takeIf { it.id == s.id })
                    finalSession = merged
                    // 代数守卫：用户已删除/回退/重发过本会话时，引擎的旧消息集不再覆盖 UI 与磁盘
                    val genClean = sessionGen[session.id] == startGen
                    // 会话守卫：用户已切走时不覆写当前会话（引擎后台继续跑并存盘）
                    val stillActive = _activeSession.value?.id == session.id
                    // UI 节流 80ms：防 token 级全列表重组（聊久卡死/闪退根因）
                    if (stillActive && genClean) throttledUpdate(merged)
                    // 节流存盘：流式期间最多 1 次/秒，防每 token 写盘卡顿
                    val now = System.currentTimeMillis()
                    if (now - lastSave > 1000 && genClean) {
                        lastSave = now
                        saveSessionBg(merged)
                    }
                }
            } catch (e: Exception) {
                // 请求失败明示（不再静默——模型收到失败结果会自主重试造成连环请求）
                toast.value = "请求失败: " + (e.message ?: "未知错误").take(80)
            } finally {
                isRunning.value = false
                _pendingApproval.value = null
                // 停止后旧任务写回复活根修：finally 写盘前校验代数——用户若已删除/回退/重发，
                // 引擎持有的旧消息集直接丢弃，不覆盖用户状态
                val genClean = sessionGen[session.id] == startGen
                if (genClean) {
                    finalSession = mergeMeta(finalSession, _activeSession.value?.takeIf { it.id == finalSession.id })
                    // 空回复兜底：末条 assistant 无正文/无工具/无思考时补提示（修"空回复"）
                    runCatching {
                        val last = finalSession.messages.lastOrNull { it.role == "assistant" }
                        if (last != null && last.content.isBlank() && last.toolCalls.isEmpty() && last.thinking.isNullOrBlank() && last.toolResult.isNullOrBlank()) {
                            val fixed = last.copy(content = "（模型未返回正文：可能被截断或接口异常，请重试或换模型）")
                            finalSession = finalSession.copy(messages = finalSession.messages.map { if (it.id == last.id) fixed else it })
                        }
                    }
                    // 回退/截断根修：用引擎返回的最终会话直接刷新 UI（不再从磁盘读滞后版本覆盖内存）
                    if (_activeSession.value?.id == finalSession.id) {
                        _activeSession.value = finalSession
                        lastUiEmit = 0
                    }
                    // 结束必存最终态（先存盘，UI 已是最新）
                    saveSessionBg(finalSession)
                }
            }
        }
    }

    fun stop() {
        runningJob?.cancel()
        runningJob = null
        _pendingApproval.value = null
        isRunning.value = false
        // 会话结束：清工具会话授权（下次新会话重新审批）
        engine.engineSessionAllows.clear()
    }

    private fun saveSessionBg(s: ChatSession) {
        viewModelScope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { sessionRepo.save(s) } }
    }

    fun clearActiveSession() {
        val cur = _activeSession.value ?: return
        if (isRunning.value) stop()
        val cleared = cur.copy(messages = emptyList())
        bumpGen(cleared.id)
        _activeSession.value = cleared
        sessionRepo.save(cleared)
    }

    @Volatile var pendingContext: Int = 0
    val chatScrollMap = java.util.concurrent.ConcurrentHashMap<String, Int>()


    fun clearMessages() {
        val cur = _activeSession.value ?: return
        if (isRunning.value) stop()
        val cleared = cur.copy(messages = emptyList(), summary = null)
        bumpGen(cleared.id)
        _activeSession.value = cleared
        sessionRepo.save(cleared)
    }

    fun compactNow() {
        val cur = _activeSession.value ?: return
        val msgs = cur.messages
        if (msgs.size < 6) return
        val keepHead = 2
        val keepTail = 4
        val dropped = msgs.subList(keepHead, msgs.size - keepTail)
        val summary = buildString {
            append("（前文摘要：较早对话已压缩）\n")
            dropped.forEach { m ->
                when (m.role) {
                    "user" -> append("[用户] ").append(m.content.take(120)).append("\n")
                    "assistant" -> append("[AI] ").append(m.content.take(150)).append("\n")
                    "tool" -> append("[工具 ").append(m.toolName ?: "").append("] ").append((m.toolResult ?: "").take(80)).append("\n")
                }
            }
        }
        val kept = msgs.subList(0, keepHead) + msgs.subList(msgs.size - keepTail, msgs.size)
        val compacted = cur.copy(messages = kept, summary = summary)
        bumpGen(compacted.id)
        _activeSession.value = compacted
        sessionRepo.save(compacted)
    }

    fun exportSession() {
        val cur = _activeSession.value ?: return
        val md = buildString {
            append("# ${cur.title.ifBlank { "会话" }}\n\n")
            cur.messages.filter { it.role != "tool" }.forEach { m ->
                append(if (m.role == "user") "**我：**\n" else "**AI：**\n")
                append(m.content).append("\n\n")
            }
        }
        val ws = ai.devcodex.agent.tools.BuiltinTools.workspaceRoot()
        java.io.File(ws, "session-${cur.id.take(6)}.md").writeText(md)
    }

    fun appendLocalNotice(text: String) {
        val cur = _activeSession.value ?: return
        val withNotice = cur.copy(messages = cur.messages + ai.devcodex.agent.data.Message(
            id = java.util.UUID.randomUUID().toString(),
            role = "assistant",
            content = text,
            model = "local",
            timestamp = System.currentTimeMillis(),
        ))
        bumpGen(withNotice.id)
        _activeSession.value = withNotice
        sessionRepo.save(withNotice)
    }

    fun setSessionContext(ctx: Int) {
        val cur = _activeSession.value
        if (cur == null) {
            // 会话未创建：挂起，新会话继承
            pendingContext = ctx
            return
        }
        val updated = cur.copy(contextOverride = ctx)
        _activeSession.value = updated
        sessionRepo.save(updated)
    }

    fun toggleSessionFlag(flag: String) {
        val cur = _activeSession.value ?: return
        val updated = when (flag) {
            "deepThink" -> cur.copy(deepThink = !cur.deepThink)
            "webSearch" -> cur.copy(webSearch = !cur.webSearch)
            else -> return
        }
        _activeSession.value = updated
        sessionRepo.save(updated)
    }

    fun updateSessionModel(providerId: String, modelId: String) {
        val cur = _activeSession.value ?: return
        val updated = cur.copy(providerId = providerId, modelId = modelId)
        _activeSession.value = updated
        sessionRepo.save(updated)
    }

    fun getPreferences(): android.content.SharedPreferences =
        getApplication<android.app.Application>().getSharedPreferences("devcodex_stats", android.content.Context.MODE_PRIVATE)

    fun compactSessionNow() {
        val s = _activeSession.value ?: return
        val msgs = s.messages
        if (msgs.size <= 8) { toast.value = "会话太短，无需压缩"; return }
        val keepHead = 2
        val keepTail = 6
        val sb = StringBuilder("（前文摘要：手动压缩于 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()) + "）\n")
        msgs.drop(keepHead).dropLast(keepTail).forEach { m ->
            when (m.role) {
                "user" -> sb.append("[用户] ").append(m.content.take(120)).append("\n")
                "assistant" -> sb.append("[AI] ").append(m.content.take(150)).append("\n")
                "tool" -> sb.append("[工具 ").append(m.toolName ?: "").append("] ").append((m.toolResult ?: "").take(80)).append("\n")
            }
        }
        val compacted = s.copy(messages = msgs.take(keepHead) + listOf(
            ai.devcodex.agent.data.Message(id = java.util.UUID.randomUUID().toString(), role = "user", content = sb.toString(), timestamp = System.currentTimeMillis()),
            ai.devcodex.agent.data.Message(id = java.util.UUID.randomUUID().toString(), role = "assistant", content = "（已了解前文摘要，继续）", timestamp = System.currentTimeMillis()),
        ) + msgs.takeLast(keepTail), updatedAt = System.currentTimeMillis())
        bumpGen(compacted.id)
        _activeSession.value = compacted
        viewModelScope.launch { sessionRepo.save(compacted); reloadSessions() }
        toast.value = "已压缩：保留头 2 尾 6 + 摘要"
    }

    // 角色卡开聊（Operit 式：设定→system，开场白→assistant 首条）
    fun startWithCharacter(c: ai.devcodex.agent.data.CharacterCard) {
        val sys = buildString {
            append("你将扮演角色：").append(c.name).append("\n")
            if (c.description.isNotBlank()) append("【角色设定】\n").append(c.description).append("\n")
            if (c.personality.isNotBlank()) append("【性格】\n").append(c.personality).append("\n")
            if (c.scenario.isNotBlank()) append("【场景】\n").append(c.scenario).append("\n")
            if (c.systemPrompt.isNotBlank()) append("【系统指令】\n").append(c.systemPrompt).append("\n")
            if (c.postHistoryInstructions.isNotBlank()) append("【持续指令】\n").append(c.postHistoryInstructions).append("\n")
        }
        val s = sessionRepo.newSession()
        var msgs = buildList {
            add(ai.devcodex.agent.data.Message(
                id = java.util.UUID.randomUUID().toString(), role = "system", content = sys,
                timestamp = System.currentTimeMillis()))
            if (c.firstMes.isNotBlank()) add(ai.devcodex.agent.data.Message(
                id = java.util.UUID.randomUUID().toString(), role = "assistant", content = c.firstMes,
                timestamp = System.currentTimeMillis()))
        }
        // 世界书：触发关键词（keys 命中用户输入时注入）——开场即注入全部 enabled 条目（Operit 式常驻）
        val book = c.characterBook
        if (book != null && book.entries.isNotEmpty()) {
            val lore = book.entries.filter { it.enabled }.joinToString("\n") { "【lore】" + it.content }
            if (lore.isNotBlank()) msgs = msgs + listOf(ai.devcodex.agent.data.Message(
                id = java.util.UUID.randomUUID().toString(), role = "system", content = lore,
                timestamp = System.currentTimeMillis()))
        }
        val withChar = s.copy(title = "角色 · " + c.name, messages = msgs)
        sessionRepo.save(withChar)
        _activeSession.value = withChar
        reloadSessions()
    }

    fun updateCharacter(old: ai.devcodex.agent.data.CharacterCard, new: ai.devcodex.agent.data.CharacterCard): Boolean {
        ai.devcodex.agent.data.CharacterRepo.delete(old.name)
        return ai.devcodex.agent.data.CharacterRepo.save(new)
    }

    fun importCharacter(jsonText: String): Boolean {
        val c = ai.devcodex.agent.data.CharacterRepo.importFromJson(jsonText) ?: return false
        return ai.devcodex.agent.data.CharacterRepo.save(c)
    }

    fun rewindTo(msgId: String) {
        val s = _activeSession.value ?: return
        if (isRunning.value) stop()   // 用户接管会话：终止引擎，防停止后旧消息集异步写回复活
        val idx = s.messages.indexOfFirst { it.id == msgId }
        if (idx < 0) return
        val kept = s.messages.take(idx)  // 回退到这里：删该消息及之后全部
        val rewound = s.copy(messages = kept, updatedAt = System.currentTimeMillis())
        bumpGen(rewound.id)
        _activeSession.value = rewound
        viewModelScope.launch { sessionRepo.save(rewound); reloadSessions() }
    }

    fun deleteMessage(msgId: String) {
        val cur = _activeSession.value ?: return
        if (isRunning.value) stop()
        val updated = cur.copy(messages = cur.messages.filter { it.id != msgId })
        bumpGen(updated.id)
        _activeSession.value = updated
        sessionRepo.save(updated)
    }

    fun deleteSession(id: String) {
        sessionRepo.delete(id)
        if (_activeSession.value?.id == id) _activeSession.value = null
        reloadSessions()
    }

    // ============ 审批 ============
    private suspend fun awaitApproval(req: ApprovalRequest): Boolean =
        suspendCancellableCoroutine { cont ->
            approvalWaiters[req.toolCall.id] = { granted ->
                cont.resume(granted)
            }
            _pendingApproval.value = req to (latestSettings.providers.find { p -> p.id == _activeSession.value?.providerId }?.name ?: "")
            cont.invokeOnCancellation { approvalWaiters.remove(req.toolCall.id); _pendingApproval.value = null }
        }

    fun respondApproval(granted: Boolean, always: Boolean = false) {
        val pending = _pendingApproval.value ?: return
        if (granted && always) {
            val toolName = pending.first.toolCall.name
            // P0 审计修复：仅本会话内记住（引擎内存集合，重启/新会话失效），不再永久写入 DataStore
            engine.engineSessionAllows.add(toolName)
        }
        approvalWaiters.remove(pending.first.toolCall.id)?.invoke(granted)
        _pendingApproval.value = null
    }

    // ============ 提供商操作 ============
    fun upsertProvider(p: ProviderConfig) {
        viewModelScope.launch {
            settingsRepo.update { s ->
                val exists = s.providers.any { it.id == p.id }
                val list = if (exists) s.providers.map { if (it.id == p.id) p else it } else s.providers + p
                // 默认提供商唯一
                val fixed = if (p.isDefault) list.map { it.copy(isDefault = it.id == p.id) } else list
                s.copy(providers = fixed)
            }
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            settingsRepo.update { s -> s.copy(providers = s.providers.filter { it.id != id }) }
        }
    }

    fun setDefaultProvider(id: String) {
        viewModelScope.launch {
            settingsRepo.update { s -> s.copy(providers = s.providers.map { it.copy(isDefault = it.id == id) }) }
        }
    }

    fun saveSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    companion object {
        val FACTORY: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(DevCodexApp.instance) as T
        }
    }
}
