package ai.devcodex.agent.provider

import ai.devcodex.agent.data.ModelTestResult
import ai.devcodex.agent.data.ProviderConfig
import ai.devcodex.agent.data.ToolCall
import ai.devcodex.agent.util.describe
import kotlinx.serialization.json.*
import okhttp3.Request

/** OpenAI 兼容协议（GLM/DeepSeek/Kimi/OpenAI/OpenRouter/groq 等） */
object OpenAiCompatProvider {

    fun chatRequest(p: ProviderConfig, model: String, turns: List<ChatTurn>, tools: List<JsonObject>, stream: Boolean, reasoningEffort: String = ""): Request {
        val messages = turns.map { t ->
            buildJsonObject {
                put("role", t.role)
                when {
                    t.role == "assistant" && t.toolCalls.isNotEmpty() -> {
                        put("content", if (t.content.isBlank()) JsonNull else JsonPrimitive(t.content))
                        put("tool_calls", buildJsonArray {
                            t.toolCalls.forEach { tc ->
                                add(buildJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", tc.name)
                                        put("arguments", tc.argsJson)
                                    })
                                })
                            }
                        })
                    }
                    t.role == "tool" -> {
                        put("tool_call_id", t.toolCallId ?: "")
                        put("content", t.content)
                    }
                    else -> if (t.role == "user" && t.images.isNotEmpty()) {
                        put("content", buildJsonArray {
                            if (t.content.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", t.content) })
                            t.images.forEach { im ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", "data:${im.mime};base64,${im.base64}") })
                                })
                            }
                        })
                    } else {
                        put("content", t.content)
                    }
                }
            }
        }
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray { messages.forEach { add(it) } })
            put("stream", stream)
            if (reasoningEffort.isNotBlank()) put("reasoning_effort", reasoningEffort)
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { t -> add(buildJsonObject { put("type", "function"); put("function", t) }) }
                })
                put("tool_choice", "auto")
            }
        }
        return Request.Builder()
            .url("${p.endpoint()}/chat/completions")
            .addHeader("Authorization", "Bearer ${p.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody(body))
            .build()
    }

    /** SSE 行 → 事件流 */
    fun parseSseLine(line: String): List<StreamEvent> {
        if (!line.startsWith("data:")) return emptyList()
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]" || data == "null" || data.isBlank()) return emptyList()
        val obj = runCatching { LlmJson.parseToJsonElement(data) }.getOrNull()
            as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        val events = mutableListOf<StreamEvent>()
        // 网关在流中插入的 {"error":...}：明确报错，交给引擎重试，不再当作成功结束
        (obj["error"] as? kotlinx.serialization.json.JsonObject)?.let { er ->
            val msg = (er["message"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: er.toString().take(200)
            return listOf(StreamEvent.Error(RuntimeException("流内错误: $msg")))
        }
        // usage（部分服务在最后一个 chunk 带；字段可能为 null，逐个防御）
        (obj["usage"] as? kotlinx.serialization.json.JsonObject)?.let { u ->
            val it2 = (u["prompt_tokens"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: 0
            val ot = (u["completion_tokens"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: 0
            val ct = ((u["prompt_tokens_details"] as? kotlinx.serialization.json.JsonObject)
                ?.get("cached_tokens") as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
                ?: (u["cached_tokens"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: 0
            events.add(StreamEvent.Usage(it2, ot, ct))
        }
        val choices = obj["choices"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        if (choices.isEmpty()) return emptyList()
        val delta = (choices[0] as? kotlinx.serialization.json.JsonObject)?.get("delta")
            as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        delta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let { events.add(StreamEvent.Reasoning(it)) }
        delta["content"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) events.add(StreamEvent.Delta(it)) }
        delta["tool_calls"]?.jsonArray?.forEach { tcEl ->
            val tc = tcEl.jsonObject
            val idx = tc["index"]?.jsonPrimitive?.intOrNull ?: -1
            val fn = tc["function"]?.jsonObject
            events.add(StreamEvent.ToolCallDelta(
                index = idx,
                id = tc["id"]?.jsonPrimitive?.contentOrNull,
                name = fn?.get("name")?.jsonPrimitive?.contentOrNull,
                argsFragment = fn?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "",
            ))
        }
        choices[0].jsonObject["finish_reason"]?.jsonPrimitive?.contentOrNull?.let {
            if (it != "null" && it.isNotEmpty()) events.add(StreamEvent.Done(it))
        }
        return events
    }

    /** 模型自动识别：GET {base}/models */
    suspend fun listModels(p: ProviderConfig): List<String> {
        val req = Request.Builder()
            .url("${p.endpoint()}/models")
            .addHeader("Authorization", "Bearer ${p.apiKey}")
            .get().build()
        TestClient.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body?.string() ?: ""
            val obj = LlmJson.parseToJsonElement(body).jsonObject
            val data = obj["data"]?.jsonArray ?: return emptyList()
            return data.mapNotNull { m ->
                (m as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            }.sorted()
        }
    }

    /** 单模型三项测试：延迟 / 流式 / 工具支持（对标 rikkahub ProviderConnectionTester） */
    suspend fun testModel(p: ProviderConfig, model: String): ModelTestResult {
        var result = ModelTestResult()
        try {
            // 1. 非流式延迟
            val t0 = System.currentTimeMillis()
            val req = chatRequest(p, model, listOf(ChatTurn("user", "ping")), emptyList(), stream = false)
            TestClient.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                resp.body?.string()
            }
            result = result.copy(latencyMs = System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            return result.copy(error = "非流式失败: ${e.describe()}")
        }
        try {
            // 2. 流式
            val req = chatRequest(p, model, listOf(ChatTurn("user", "say hi")), emptyList(), stream = true)
            var gotDelta = false
            streamPost(TestClient, req) { line ->
                parseSseLine(line).forEach { ev ->
                    if (ev is StreamEvent.Delta && ev.text.isNotBlank()) gotDelta = true
                }
            }
            result = result.copy(streamOk = gotDelta)
            if (!gotDelta) result = result.copy(error = "流式无内容输出")
        } catch (e: Exception) {
            result = result.copy(error = "流式失败: ${e.describe()}")
        }
        try {
            // 3. 工具调用支持
            val testTool = buildJsonObject {
                put("name", "echo_test")
                put("description", "回显文本的测试工具")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject { put("text", buildJsonObject { put("type", "string") }) })
                    put("required", buildJsonArray { add("text") })
                })
            }
            val req = chatRequest(p, model, listOf(ChatTurn("user", "调用 echo_test 工具回显 hello")), listOf(testTool), stream = false)
            TestClient.newCall(req).await().use { resp ->
                val body = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    val obj = LlmJson.parseToJsonElement(body).jsonObject
                    val msg = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                    if (msg?.get("tool_calls")?.jsonArray?.isNotEmpty() == true) result = result.copy(toolsOk = true)
                }
            }
        } catch (e: Exception) {
            result = result.copy(error = result.error?.let { "$it; 工具测试失败: ${e.describe()}" } ?: "工具测试失败: ${e.describe()}")
        }
        return result
    }
}
