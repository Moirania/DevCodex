package ai.devcodex.agent.provider

import ai.devcodex.agent.data.ModelTestResult
import ai.devcodex.agent.data.ProviderConfig
import ai.devcodex.agent.util.describe
import kotlinx.serialization.json.*
import okhttp3.Request

/** Anthropic Messages API */
object AnthropicProvider {

    /**
     * ChatTurn -> Anthropic messages。
     * 1. 相邻同角色消息合并：同一轮的多个 tool_result 必须在同一条 user 消息里。
     * 2. 带工具调用的 assistant 轮先回传 thinking 块（含 signature），否则开启 thinking 时续轮会 400。
     * 3. 空文本块会被 API 拒绝，一律跳过。
     */
    private fun turnsToBlocks(turns: List<ChatTurn>): JsonArray {
        val msgs = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        fun push(role: String, block: JsonObject) {
            val last = msgs.lastOrNull()
            if (last != null && last.first == role) last.second.add(block) else msgs.add(role to mutableListOf(block))
        }
        turns.filter { it.role != "system" }.forEach { t ->
            when {
                t.role == "assistant" -> {
                    if (t.toolCalls.isNotEmpty() && !t.thinking.isNullOrBlank() && !t.thinkingSignature.isNullOrBlank()) {
                        push("assistant", buildJsonObject {
                            put("type", "thinking")
                            put("thinking", t.thinking)
                            put("signature", t.thinkingSignature)
                        })
                    }
                    if (t.content.isNotBlank()) push("assistant", buildJsonObject { put("type", "text"); put("text", t.content) })
                    t.toolCalls.forEach { tc ->
                        push("assistant", buildJsonObject {
                            put("type", "tool_use")
                            put("id", tc.id)
                            put("name", tc.name)
                            put("input", runCatching { LlmJson.parseToJsonElement(tc.argsJson) }.getOrDefault(buildJsonObject {}))
                        })
                    }
                }
                t.role == "tool" -> push("user", buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", t.toolCallId ?: "")
                    put("content", t.content.take(4000))
                })
                else -> {
                    t.images.forEach { im ->
                        push("user", buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", im.mime)
                                put("data", im.base64)
                            })
                        })
                    }
                    if (t.content.isNotBlank()) push("user", buildJsonObject { put("type", "text"); put("text", t.content) })
                }
            }
        }
        return buildJsonArray {
            msgs.forEach { (role, blocks) ->
                add(buildJsonObject {
                    put("role", role)
                    put("content", JsonArray(blocks))
                })
            }
        }
    }

    /** thinking 预算；max_tokens 必须大于该值，调用处按 budget + 8192 计算 */
    private fun thinkingBudget(effort: String): Int = when (effort) {
        "low" -> 2048
        "medium" -> 8192
        "high" -> 16384
        "xhigh" -> 24576
        "max" -> 32768
        else -> 65536
    }

    /** 仍接受 budget_tokens 的旧模型（3.x 与 4.0-4.5 系列）；其余一律走 adaptive */
    private val legacyThinkingModel = Regex("""claude-3|(opus|sonnet|haiku)-4(-[0-5])?(-\d{8})?$""")
    private fun usesAdaptiveThinking(model: String): Boolean = !legacyThinkingModel.containsMatchIn(model)
    private fun adaptiveEffort(effort: String): String = when (effort) {
        "low" -> "low"
        "medium" -> "medium"
        else -> "high"
    }

    fun chatRequest(p: ProviderConfig, model: String, systemPrompt: String, turns: List<ChatTurn>, tools: List<JsonObject>, stream: Boolean, reasoningEffort: String = ""): Request {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", if (reasoningEffort.isNotBlank()) thinkingBudget(reasoningEffort) + 8192 else 8192)
            put("stream", stream)
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            if (reasoningEffort.isNotBlank()) {
                if (usesAdaptiveThinking(model)) {
                    // 新模型不再接受 budget_tokens（会 400），改用 adaptive thinking + effort
                    put("thinking", buildJsonObject { put("type", "adaptive") })
                    put("output_config", buildJsonObject { put("effort", adaptiveEffort(reasoningEffort)) })
                } else {
                    // 旧模型：thinking budget 映射 low=2048 / medium=8192 / high=16384
                    val budget = thinkingBudget(reasoningEffort)
                    put("thinking", buildJsonObject { put("type", "enabled"); put("budget_tokens", budget) })
                }
            }
            put("messages", turnsToBlocks(turns))
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { t -> add(buildJsonObject { put("name", t["name"] ?: JsonNull); put("description", t["description"] ?: JsonNull); put("input_schema", t["parameters"] ?: buildJsonObject { put("type", "object") }) }) }
                })
            }
        }
        return Request.Builder()
            .url("${p.endpoint()}/v1/messages")
            .addHeader("x-api-key", p.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody(body))
            .build()
    }

    fun parseSseLine(line: String): List<StreamEvent> {
        if (!line.startsWith("data:")) return emptyList()
        val data = line.removePrefix("data:").trim()
        val obj = runCatching { LlmJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "content_block_delta" -> {
                val delta = obj["delta"]?.jsonObject ?: return emptyList()
                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> listOf(StreamEvent.Delta(delta["text"]?.jsonPrimitive?.contentOrNull ?: ""))
                    "thinking_delta" -> listOf(StreamEvent.Reasoning(delta["thinking"]?.jsonPrimitive?.contentOrNull ?: ""))
                    "signature_delta" -> listOf(StreamEvent.ThinkingSignature(delta["signature"]?.jsonPrimitive?.contentOrNull ?: ""))
                    "input_json_delta" -> listOf(StreamEvent.ToolCallDelta(obj["index"]?.jsonPrimitive?.intOrNull ?: 0, null, null, delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""))
                    else -> emptyList()
                }
            }
            "content_block_start" -> {
                val cb = obj["content_block"]?.jsonObject ?: return emptyList()
                if (cb["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                    listOf(StreamEvent.ToolCallDelta(obj["index"]?.jsonPrimitive?.intOrNull ?: 0, cb["id"]?.jsonPrimitive?.contentOrNull, cb["name"]?.jsonPrimitive?.contentOrNull, ""))
                } else emptyList()
            }
            "message_start" -> {
                val u = obj["message"]?.jsonObject?.get("usage")?.jsonObject
                if (u != null) listOf(StreamEvent.Usage(
                    u["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0, 0,
                    u["cache_read_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0,
                )) else emptyList()
            }
            "message_delta" -> {
                val u = obj["usage"]?.jsonObject
                if (u != null) listOf(StreamEvent.Usage(0, u["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0, 0)) else emptyList()
            }
            "error" -> {
                val msg = obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: data.take(200)
                listOf(StreamEvent.Error(RuntimeException("流内错误: $msg")))
            }
            "message_stop" -> listOf(StreamEvent.Done("stop"))
            else -> emptyList()
        }
    }

    suspend fun listModels(p: ProviderConfig): List<String> {
        val req = Request.Builder()
            .url("${p.endpoint()}/v1/models?limit=200")
            .addHeader("x-api-key", p.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .get().build()
        TestClient.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body?.string() ?: ""
            val obj = LlmJson.parseToJsonElement(body).jsonObject
            return obj["data"]?.jsonArray?.mapNotNull { m ->
                (m as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            }?.sorted() ?: emptyList()
        }
    }

    suspend fun testModel(p: ProviderConfig, model: String): ModelTestResult {
        var result = ModelTestResult()
        try {
            val t0 = System.currentTimeMillis()
            val req = chatRequest(p, model, "", listOf(ChatTurn("user", "ping")), emptyList(), stream = false)
            TestClient.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                resp.body?.string()
            }
            result = result.copy(latencyMs = System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            return result.copy(error = "非流式失败: ${e.describe()}")
        }
        try {
            val req = chatRequest(p, model, "", listOf(ChatTurn("user", "say hi")), emptyList(), stream = true)
            var gotDelta = false
            streamPost(TestClient, req) { line ->
                parseSseLine(line).forEach { ev -> if (ev is StreamEvent.Delta && ev.text.isNotBlank()) gotDelta = true }
            }
            result = result.copy(streamOk = gotDelta)
            if (!gotDelta) result = result.copy(error = "流式无内容输出")
        } catch (e: Exception) {
            result = result.copy(error = "流式失败: ${e.describe()}")
        }
        return result
    }
}
