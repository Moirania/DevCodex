package ai.devcodex.agent.agent

import ai.devcodex.agent.data.AppSettings
import ai.devcodex.agent.data.ChatSession
import ai.devcodex.agent.data.Message
import ai.devcodex.agent.data.ToolCall
import ai.devcodex.agent.data.ToolStatus
import ai.devcodex.agent.provider.ChatTurn
import ai.devcodex.agent.provider.LlmClient
import ai.devcodex.agent.provider.ModelRegistry
import ai.devcodex.agent.provider.ProviderGateway
import ai.devcodex.agent.provider.StreamEvent
import ai.devcodex.agent.provider.streamPost
import ai.devcodex.agent.tools.BuiltinTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Agent 引擎：LLM → 工具调用 → 结果回填 → 循环，直到最终回答（Claude Code 式） */
class AgentEngine(
    private val settings: () -> AppSettings,
    private val plugins: () -> List<ai.devcodex.agent.data.PluginManifest>,
    /** 审批回调：UI 弹出卡片，用户决定后回调（挂起等待） */
    private val requestApproval: suspend (ApprovalRequest) -> Boolean,
) {
    val engineSessionAllows: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    sealed interface UiEvent {
        data class Error(val message: String) : UiEvent
        data class Finished(val sessionId: String) : UiEvent
    }

    val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private fun ChatSession.patch(id: String, transform: (Message) -> Message): ChatSession =
        copy(messages = messages.map { if (it.id == id) transform(it) else it }, updatedAt = System.currentTimeMillis())

    /** 流式消费：处理一行 SSE，更新聚合缓冲 */
    private fun onLine(
        provider: ai.devcodex.agent.data.ProviderConfig,
        line: String,
        text: StringBuilder,
        think: StringBuilder,
        toolAgg: LinkedHashMap<Int, Triple<String, String, StringBuilder>>,
        assistantId: String,
        currentRef: ChatSession,
        onUpdate: (ChatSession) -> Unit,
        onUsage: (Long, Long, Long) -> Unit = { _, _, _ -> },
        onFirstDelta: () -> Unit = {},
    ): ChatSession {
        var current = currentRef
        var thinkStart = 0L
        ProviderGateway.parseSseLine(provider, line).forEach { ev ->
            when (ev) {
                is StreamEvent.Usage -> onUsage(ev.inputTokens, ev.outputTokens, ev.cachedTokens)
                is StreamEvent.Delta -> {
                    onFirstDelta()
                    val prevMsg = current.messages.firstOrNull { it.id == assistantId }
                    val startAt = prevMsg?.thinkStartAt ?: 0L
                    if (startAt > 0 && text.isEmpty()) {
                        var settleMs = System.currentTimeMillis() - startAt
                        if (settleMs < 0 || settleMs > 3_600_000L) settleMs = 0
                        current = current.patch(assistantId) { it.copy(thinkingMs = settleMs) }
                    } else if (think.isNotEmpty() && thinkStart > 0) {
                        var settleMs = System.currentTimeMillis() - thinkStart
                        if (settleMs in 1..3_600_000L) current = current.patch(assistantId) { it.copy(thinkingMs = settleMs) }
                    }
                    text.append(ev.text)
                    current = current.patch(assistantId) { it.copy(content = text.toString()) }
                    onUpdate(current)
                }
                is StreamEvent.Reasoning -> {
                    val now = System.currentTimeMillis()
                    if (thinkStart == 0L) thinkStart = now
                    think.append(ev.text)
                    val prevStart = current.messages.firstOrNull { it.id == assistantId }?.thinkStartAt ?: 0L
                    val startAt = if (prevStart > 0) prevStart else thinkStart
                    var ms = now - startAt
                    if (ms < 0 || ms > 3_600_000L) ms = 0
                    current = current.patch(assistantId) { it.copy(thinking = think.toString(), thinkingMs = ms, thinkStartAt = startAt) }
                    onUpdate(current)
                }
                is StreamEvent.ToolCallDelta -> {
                    // index 缺失时（负值）按 id 变化划分不同的工具调用
                    val key = if (ev.index >= 0) ev.index else {
                        val lastKey = toolAgg.keys.lastOrNull()
                        val lastId = lastKey?.let { toolAgg[it]?.first }
                        if (lastKey == null) 0 else if (ev.id != null && ev.id != lastId) lastKey + 1 else lastKey
                    }
                    val ex = toolAgg[key]
                    toolAgg[key] = Triple(
                        ev.id ?: ex?.first ?: "call_${assistantId.take(8)}_$key",
                        ev.name ?: ex?.second ?: "",
                        StringBuilder(ex?.third ?: "").append(ev.argsFragment),
                    )
                    // 工具调用增量到达即写入消息，界面立刻显示工具卡片（不再等整条流结束）
                    val liveCalls = toolAgg.values.filter { it.second.isNotBlank() }.map {
                        ToolCall(id = it.first, name = it.second, argsJson = it.third.toString().ifBlank { "{}" })
                    }
                    if (liveCalls.isNotEmpty()) {
                        current = current.patch(assistantId) { it.copy(toolCalls = liveCalls) }
                        onUpdate(current)
                    }
                }
                is StreamEvent.ThinkingSignature -> {
                    current = current.patch(assistantId) { it.copy(thinkingSignature = (it.thinkingSignature ?: "") + ev.signature) }
                }
                is StreamEvent.Done -> Unit
                is StreamEvent.Error -> throw RuntimeException(ev.throwable.message ?: "stream error")
            }
        }
        return current
    }

    suspend fun run(session: ChatSession, onUpdate: (ChatSession) -> Unit) {
        val s = settings()
        val provider = s.providers.find { it.id == session.providerId && it.enabled }
            ?: s.providers.firstOrNull { it.enabled && it.enabledModels.isNotEmpty() }
            ?: run { events.emit(UiEvent.Error("未配置 API 提供商，请到「提供商」页添加并拉取模型")); return }
        val chatModels = provider.enabledModels.filter { ModelRegistry.isChatModel(it) }
        val model = session.modelId?.takeIf { chatModels.contains(it) }
            ?: chatModels.firstOrNull()
            ?: run { events.emit(UiEvent.Error("该提供商没有可用的聊天模型（生图与视频模型不能作为聊天主模型）")); return }

        // MCP 工具拉取（启用中的服务器，单服务器 8s 超时保护）
        val mcpTools = s.mcpServers.filter { it.enabled }.flatMap { cfg ->
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(8000) {
                    val sid = ai.devcodex.agent.provider.McpClient.initialize(cfg).getOrNull()
                    ai.devcodex.agent.provider.McpClient.listTools(cfg, sid).getOrDefault(emptyList())
                } ?: emptyList()
            }.getOrDefault(emptyList())
        }
        val toolDefs = BuiltinTools.all.filter { s.enabledTools.contains(it.name) } +
            ai.devcodex.agent.tools.AdvancedFsTools.all.filter { s.enabledTools.contains(it.name) } +
            ai.devcodex.agent.tools.ExtraTools.all.filter { s.enabledTools.contains(it.name) } +
            ai.devcodex.agent.tools.MediaTools.all.filter { s.enabledTools.contains(it.name) } +
            customToolDefs(s.customTools) +
            mcpTools.map { mt ->
                ToolDef(
                    name = "mcp_${mt.serverId.take(8)}_${mt.name}",
                    description = "[MCP:${mt.serverName}] ${mt.description.ifBlank { mt.name }}",
                    parameters = runCatching {
                        kotlinx.serialization.json.Json.parseToJsonElement(mt.inputSchema) as? JsonObject
                    }.getOrNull() ?: buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) },
                ) { argsJson ->
                    val cfg = s.mcpServers.firstOrNull { it.id == mt.serverId }
                        ?: return@ToolDef "ERROR: MCP 服务器已移除"
                    val sid = runCatching {
                        kotlinx.coroutines.withTimeoutOrNull(8000) {
                            ai.devcodex.agent.provider.McpClient.initialize(cfg).getOrNull()
                        }
                    }.getOrNull()
                    ai.devcodex.agent.provider.McpClient.callTool(cfg, mt.name, argsJson, sid)
                }
            } +
            BuiltinTools.pluginTools(plugins())
        val toolSchemas: List<JsonObject> = toolDefs.map { t ->
            buildJsonObject {
                put("name", t.name)
                put("description", t.description)
                put("parameters", t.parameters)
            }
        }
        val basePrompt = session.systemPrompt ?: DEFAULT_SYSTEM_PROMPT
        val skillSection = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val repo = ai.devcodex.agent.data.SkillRepo(ai.devcodex.agent.tools.BuiltinTools.appContext())
                val list = repo.list().filter { it.enabled }
                if (list.isEmpty()) "" else "\n\n已安装技能（调用 skill_read 工具获取某个技能的详细用法）：\n" +
                    list.joinToString("\n") { "- ${it.name}: ${it.description.take(120)}" }
            }.getOrDefault("")
        }
        var systemPrompt = basePrompt + skillSection
        // 角色卡/会话内 system 消息（如角色设定）拼进系统提示（修复角色卡提示词无法注入）
        val extraSys = session.messages.filter { it.role == "system" && it.content.isNotBlank() }.joinToString("\n\n") { it.content }
        if (extraSys.isNotBlank()) systemPrompt += "\n\n" + extraSys
        if (session.webSearch) {
            systemPrompt += "\n\n联网搜索模式已开启：对时效性问题，先用 http_request 工具搜索（可用 https://www.bing.com/search?q=... 或 https://duckduckgo.com/html/?q=... 抓取结果页并引用来源），综合搜索结果回答。"
        }
        val effort = if (session.deepThink) "high" else s.reasoningEffort

        var current = session
        try {
            var steps = 0
            // maxAgentSteps <= 0 表示不限步数
            while (s.maxAgentSteps <= 0 || steps < s.maxAgentSteps) {
                steps++

                // ---- 1. 流式调 LLM ----
                var turns = buildList {
                    add(ChatTurn("system", systemPrompt))
                    current.messages.forEach { m ->
                        when (m.role) {
                            "user" -> {
                                val c = (m.llmContent ?: m.content)
                                // 视觉模型：把随消息发送的图片读成 base64 附带；非视觉模型仅保留文本中的图片提示
                                val imgs = if (m.imagePaths.isNotEmpty() && ai.devcodex.agent.provider.ModelRegistry.lookup(model).vision) {
                                    m.imagePaths.mapNotNull { rel ->
                                        runCatching {
                                            val root = BuiltinTools.workspaceRoot().canonicalFile
                                            val f = java.io.File(root, rel).canonicalFile
                                            if (!f.path.startsWith(root.path + java.io.File.separator) || !f.isFile || f.length() > 8L * 1024 * 1024) return@runCatching null
                                            val mime = when (f.extension.lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
                                            ai.devcodex.agent.provider.ImagePart(mime, java.util.Base64.getEncoder().encodeToString(f.readBytes()))
                                        }.getOrNull()
                                    }
                                } else emptyList()
                                if (c.isNotBlank() || imgs.isNotEmpty()) add(ChatTurn("user", c, images = imgs))
                            }
                            "assistant" -> if (m.content.isNotBlank() || m.toolCalls.isNotEmpty()) add(ChatTurn("assistant", m.content, m.toolCalls, thinking = m.thinking, thinkingSignature = m.thinkingSignature))
                            "tool" -> if (m.toolCallId != null) add(ChatTurn("tool", m.toolResult ?: "(空)", toolCallId = m.toolCallId, name = m.toolName))
                        }
                    }
                }
                // 进程被杀或崩溃后历史里可能有工具调用缺少结果，发送前补占位结果，避免网关以 400 拒绝
                turns = buildList {
                    val answered = turns.filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet()
                    turns.forEach { t ->
                        add(t)
                        if (t.role == "assistant") {
                            t.toolCalls.filter { it.id !in answered }.forEach { tc ->
                                add(ChatTurn("tool", "ERROR: 工具未执行（会话曾中断）", toolCallId = tc.id, name = tc.name))
                            }
                        }
                    }
                }
                // 自动压缩：按会话上下文档位（覆盖或模型上限）的 20% 触发（提前压缩防溢出）
                if (s.autoCompact) {
                    val ctxLimit = (session.contextOverride.takeIf { it > 0 }
                        ?: ai.devcodex.agent.provider.ModelRegistry.lookup(model).contextLength
                        ?: 128_000)
                    val estTokens = turns.sumOf { (it.content.length / 2.5 + it.toolCalls.size * 50).toLong() }
                    if (estTokens > (ctxLimit * 0.2).toLong() && turns.size > 8) {
                        val keepHead = 2
                        val keepTail = 6
                        val dropped = turns.subList(keepHead, turns.size - keepTail)
                        // taixu 式失败记忆：ERROR 工具结果全量保留（agent 不重复踩坑）
                        val failures = dropped.filter { it.role == "tool" && it.content.startsWith("ERROR") }
                        val summary = buildString {
                            append("（前文摘要：以下为较早对话的压缩记录）\n")
                            dropped.forEach { t ->
                                when (t.role) {
                                    "user" -> append("[用户] ").append(t.content.take(120)).append("\n")
                                    "assistant" -> append("[AI] ").append(t.content.take(150)).append("\n")
                                    "tool" -> append("[工具 ").append(t.name ?: "").append("] ").append((t.content).take(80)).append("\n")
                                }
                            }
                            if (failures.isNotEmpty()) {
                                append("\n（失败记录——注意规避同类错误）\n")
                                failures.forEach { t ->
                                    append("[工具 ").append(t.name ?: "").append("] ")
                                        .append(t.content.take(400)).append("\n")
                                }
                            }
                        }
                        turns = turns.subList(0, keepHead) +
                            listOf(ChatTurn("user", summary), ChatTurn("assistant", "（已了解前文摘要，继续）")) +
                            turns.subList(turns.size - keepTail, turns.size)
                    }
                }

                // rikkahub 式能力判定：无 TOOL 能力的已知模型不携带 tools（防 400）
                val supportsTools = ModelRegistry.supportsTools(model)
                val effectiveSchemas = if (supportsTools) toolSchemas else emptyList()

                val assistantId = java.util.UUID.randomUUID().toString()
                current = current.copy(messages = current.messages + Message(
                    id = assistantId, role = "assistant", model = model, timestamp = System.currentTimeMillis(),
                ))
                onUpdate(current)

                val text = StringBuilder()
                val think = StringBuilder()
                val toolAgg = LinkedHashMap<Int, Triple<String, String, StringBuilder>>()
                var inTok = 0L; var outTok = 0L; var cacheTok = 0L
                var firstTokMs = 0L
                val t0 = System.currentTimeMillis()

                var request = ProviderGateway.chatRequest(provider, model, systemPrompt, turns, effectiveSchemas, stream = true, reasoningEffort = effort)

                fun consume(line: String): ChatSession {
                    val t = System.currentTimeMillis()
                    if (firstTokMs == 0L && text.isNotEmpty()) firstTokMs = t - t0
                    return onLine(provider, line, text, think, toolAgg, assistantId, current, onUpdate,
                        onUsage = { i, o, c -> inTok += i; outTok += o; cacheTok += c },
                        onFirstDelta = { if (firstTokMs == 0L) firstTokMs = System.currentTimeMillis() - t0 })
                }

                // 请求：HTTP 400 且带 tools 时降级去掉 tools 重试一次；其他失败最多重试 2 次，仍失败则明确报错终止
                var attempt = 0
                var toolsDropped = effectiveSchemas.isEmpty()
                while (true) {
                    val r = runCatching { streamPost(LlmClient, request) { line -> current = consume(line) } }
                    if (r.isSuccess) break
                    val err = r.exceptionOrNull()!!
                    if (err is CancellationException) throw err
                    val msg = err.message ?: err.toString()
                    ai.devcodex.agent.util.DebugLog.w("Agent", "request failed attempt=${attempt + 1} model=$model toolsDropped=$toolsDropped: $msg", err)
                    // 丢弃半截流式缓冲，避免重试后内容重复
                    text.clear(); think.clear(); toolAgg.clear()
                    current = current.patch(assistantId) { it.copy(content = "", thinking = null, thinkingSignature = null, toolCalls = emptyList()) }
                    onUpdate(current)
                    if (!toolsDropped && msg.contains("HTTP 400")) {
                        toolsDropped = true
                        request = ProviderGateway.chatRequest(provider, model, systemPrompt, turns, emptyList(), stream = true, reasoningEffort = effort)
                        continue
                    }
                    attempt++
                    if (attempt > 2) throw RuntimeException("请求失败，已重试 2 次：$msg")
                    kotlinx.coroutines.delay(1000L * attempt)
                }

                val toolCalls = toolAgg.values.filter { it.second.isNotBlank() }.map {
                    ToolCall(id = it.first, name = it.second, argsJson = it.third.toString().ifBlank { "{}" })
                }

                if (toolCalls.isEmpty()) {
                    // 最终回答
                    current = current.patch(assistantId) { it.copy(
                        content = text.toString().ifBlank { "(空回复)" },
                        inputTokens = inTok, outputTokens = outTok, cachedTokens = cacheTok,
                        firstTokenMs = firstTokMs, totalMs = System.currentTimeMillis() - t0,
                    ) }
                    onUpdate(current)
                    events.emit(UiEvent.Finished(current.id))
                    return
                }

                current = current.patch(assistantId) { it.copy(
                    content = text.toString(), toolCalls = toolCalls,
                    inputTokens = inTok, outputTokens = outTok, cachedTokens = cacheTok,
                    firstTokenMs = firstTokMs, totalMs = System.currentTimeMillis() - t0,
                ) }
                onUpdate(current)

                // ---- 2. 执行工具 ----
                for (tc in toolCalls) {
                    val def = toolDefs.find { it.name == tc.name }
                    val toolMsgId = java.util.UUID.randomUUID().toString()
                    // 参数完整性闸门：流被截断（如命中 max_tokens）时 JSON 不完整，丢弃并回填错误，不执行
                    if (def != null && runCatching { ai.devcodex.agent.provider.LlmJson.parseToJsonElement(tc.argsJson) }.getOrNull() !is kotlinx.serialization.json.JsonObject) {
                        current = current.copy(messages = current.messages + Message(
                            id = toolMsgId, role = "tool", toolCallId = tc.id, toolName = tc.name,
                            toolResult = "ERROR: 工具参数 JSON 不完整（参数流被截断，可能触及输出上限），已放弃执行，请缩小单次参数后重试",
                            toolStatus = ToolStatus.ERROR, timestamp = System.currentTimeMillis(),
                        ))
                        onUpdate(current)
                        continue
                    }
                    if (def == null) {
                        current = current.copy(messages = current.messages + Message(
                            id = toolMsgId, role = "tool", toolCallId = tc.id, toolName = tc.name,
                            toolResult = "ERROR: 未知工具 ${tc.name}", toolStatus = ToolStatus.ERROR,
                            timestamp = System.currentTimeMillis(),
                        ))
                        onUpdate(current)
                        continue
                    }
                    // 审批判定：用户已"总是允许"的直接放行；否则高危必审、fs_write 沙箱外必审
                    // P0 审计修复：fs_write 沙箱判定改为解析 path 后 canonical 包含判断（防 content 绕过 / 相对路径误报）
                    // 写入类工具的越界判定：以当前工作区根为界，覆盖所有会修改文件的 fs_* 工具；无法解析的路径一律按越界处理
                    val fsWriteOutside = run {
                        val root = runCatching { ai.devcodex.agent.tools.BuiltinTools.workspaceRoot().canonicalFile }.getOrNull()
                        val keys = when (tc.name) {
                            "fs_write", "fs_delete", "fs_mkdir", "fs_rename", "fs_chmod" -> listOf("path")
                            "fs_copy" -> listOf("dst")
                            "fs_move" -> listOf("src", "dst")
                            else -> emptyList()
                        }
                        keys.any { k ->
                            val p = ai.devcodex.agent.util.JsonArg.string(tc.argsJson, k) ?: ""
                            if (root == null) return@any true
                            val f = if (p.startsWith("/")) java.io.File(p) else java.io.File(root, p.removePrefix("./"))
                            val canon = runCatching { f.canonicalPath }.getOrNull() ?: return@any true
                            !canon.startsWith(root.path + java.io.File.separator)
                        }
                    }
                    val decision = PermissionResolver.resolve(def, s, engineSessionAllows, fsWriteOutside)
                    if (decision.level == PermissionLevel.FORBID) {
                        current = current.copy(messages = current.messages + Message(
                            id = toolMsgId, role = "tool", toolCallId = tc.id, toolName = tc.name,
                            toolResult = "ERROR: ${decision.reason}", toolStatus = ToolStatus.DENIED,
                            timestamp = System.currentTimeMillis(),
                        ))
                        onUpdate(current)
                        continue
                    }
                    val needApproval = decision.level == PermissionLevel.ASK
                    // 先落一条工具消息：需审批时为 AWAITING_APPROVAL，否则直接 RUNNING
                    current = current.copy(messages = current.messages + Message(
                        id = toolMsgId, role = "tool", toolCallId = tc.id, toolName = tc.name,
                        toolStatus = if (needApproval) ToolStatus.AWAITING_APPROVAL else ToolStatus.RUNNING,
                        timestamp = System.currentTimeMillis(),
                    ))
                    onUpdate(current)
                    if (needApproval) {
                        val reason = decision.reason
                        val granted = requestApproval(ApprovalRequest(tc, reason) { true })
                        if (!granted) {
                            current = current.patch(toolMsgId) {
                                it.copy(toolResult = "用户拒绝执行该操作", toolStatus = ToolStatus.DENIED)
                            }
                            onUpdate(current)
                            continue
                        }
                        current = current.patch(toolMsgId) { it.copy(toolStatus = ToolStatus.RUNNING) }
                        onUpdate(current)
                    }
                    val toolT0 = System.currentTimeMillis()
                    ai.devcodex.agent.util.DebugLog.i("Tool", "start ${tc.name} id=${tc.id} args=${tc.argsJson.take(300)}")
                    // 工具执行超时兜底：独立 scope 不受父级等待子协程的约束，阻塞型工具不响应取消时也能按时返回
                    val toolJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).async {
                        runCatching { def.execute(tc.argsJson) }
                    }
                    val outcome: Result<String> = try {
                        kotlinx.coroutines.withTimeoutOrNull(300_000L) { toolJob.await() }
                            ?: Result.success("ERROR: 工具执行超时（300s），已放弃等待")
                    } finally {
                        if (!toolJob.isCompleted) toolJob.cancel()
                    }
                    val result: String = outcome.getOrElse {
                        ai.devcodex.agent.util.DebugLog.e("Tool", "exception in ${tc.name} id=${tc.id}", it)
                        "ERROR: ${it.message}"
                    }
                    ai.devcodex.agent.util.DebugLog.i("Tool", "end ${tc.name} id=${tc.id} cost=${System.currentTimeMillis() - toolT0}ms error=${result.startsWith("ERROR")} resultLen=${result.length} head=${result.take(200)}")
                    current = current.patch(toolMsgId) {
                        it.copy(toolResult = result, toolStatus = if (result.startsWith("ERROR")) ToolStatus.ERROR else ToolStatus.DONE)
                    }
                    onUpdate(current)
                }
                // ---- 3. 循环：工具结果已在消息里，下一轮带给 LLM ----
            }
            events.emit(UiEvent.Error("已达到最大步数 ${s.maxAgentSteps}，执行已停止，可在设置中调整"))
        } catch (e: CancellationException) {
            // 中断收尾：运行中/待审批的工具消息标记已中断，并给没有结果的工具调用补写结果，保证下一轮请求结构合法
            val answered = current.messages.filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet()
            val patched = current.messages.map { m ->
                if (m.role == "tool" && (m.toolStatus == ToolStatus.RUNNING || m.toolStatus == ToolStatus.AWAITING_APPROVAL))
                    m.copy(toolStatus = ToolStatus.ERROR, toolResult = "已中断")
                else m
            }
            val missing = patched.filter { it.role == "assistant" }.flatMap { it.toolCalls }
                .filter { it.id !in answered }
                .map { tc ->
                    Message(
                        id = java.util.UUID.randomUUID().toString(), role = "tool", toolCallId = tc.id, toolName = tc.name,
                        toolResult = "已中断", toolStatus = ToolStatus.ERROR, timestamp = System.currentTimeMillis(),
                    )
                }
            current = current.copy(messages = patched + missing)
            onUpdate(current)
        } catch (e: Exception) {
            ai.devcodex.agent.util.DebugLog.e("Agent", "run aborted: ${e.message}", e)
            events.emit(UiEvent.Error(e.message ?: e.toString()))
            onUpdate(current)
        }
    }

    companion object {
        private const val SANDBOX_MARK = "/data/user/0/ai.devcodex.agent"
    }
}

/** 自定义工具（用户 shell 模板）→ ToolDef */
private fun customToolDefs(defs: List<ai.devcodex.agent.data.CustomToolDef>): List<ToolDef> =
    defs.filter { it.enabled }.map { ct ->
        val schema = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(ct.parameters) as? JsonObject
        }.getOrNull() ?: kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("object"))
            put("properties", kotlinx.serialization.json.buildJsonObject {})
        }
        ToolDef(
            name = ct.name,
            description = ct.description.ifBlank { "自定义工具 ${ct.name}（shell 模板）" },
            parameters = schema,
            risk = ToolDef.Risk.MEDIUM,
        ) { argsJson ->
            val cmd = ct.command.replace(Regex("\\{\\{(\\w+)\\}\\}")) { m ->
                ai.devcodex.agent.util.JsonArg.string(argsJson, m.groupValues[1]) ?: ""
            }
            ai.devcodex.agent.shell.ShellAccess.exec(cmd).take(15000)
        }
    }

val DEFAULT_SYSTEM_PROMPT = """你是 DevCodex，一个运行在 Android 手机上的 AI 智能体（Agent）。
你可以通过工具读写手机本地文件、执行 shell 命令、发 HTTP 请求、读取屏幕内容、做界面自动化。

工作区：用户上传的文件都在工作区，工具用相对路径即可访问（如 fs_read "报告.md"）。
用户消息中出现 [附件 文件名] 标记时，表示该工作区文件已随消息附上内容，直接使用即可，不必重复读取。
在非明确要求时，产出文件也写到工作区（相对路径），方便用户在「工作区」页查看。

行为准则：
1. 需要信息时主动调用工具获取，不要凭空编造手机上的内容。
2. 文件操作给出明确路径；危险操作前先说明意图。
3. 工具返回 ERROR 时分析原因，换路径或方案重试，同一方案不要重试超过 2 次。
4. 回答简洁、直接、面向行动。用中文交流。
5. 超出工具能力的请求如实说明，不要假装已执行。"""
