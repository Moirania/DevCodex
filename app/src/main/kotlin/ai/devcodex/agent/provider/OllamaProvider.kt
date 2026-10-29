package ai.devcodex.agent.provider

import ai.devcodex.agent.data.ModelTestResult
import ai.devcodex.agent.data.ProviderConfig
import ai.devcodex.agent.util.describe
import kotlinx.serialization.json.*
import okhttp3.Request

/** Ollama 本地服务（/api/chat） */
object OllamaProvider {

    fun chatRequest(p: ProviderConfig, model: String, turns: List<ChatTurn>, tools: List<JsonObject>, stream: Boolean): Request {
        val messages = buildJsonArray {
            turns.forEach { t ->
                add(buildJsonObject {
                    put("role", t.role)
                    put("content", t.content)
                    if (t.images.isNotEmpty()) put("images", buildJsonArray { t.images.forEach { add(it.base64) } })
                })
            }
        }
        val body = buildJsonObject {
            put("model", model)
            put("messages", messages)
            put("stream", stream)
        }
        return Request.Builder()
            .url("${p.endpoint()}/api/chat")
            .post(jsonBody(body))
            .build()
    }

    fun parseLine(line: String): List<StreamEvent> {
        val obj = runCatching { LlmJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return emptyList()
        val events = mutableListOf<StreamEvent>()
        obj["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull?.let {
            if (it.isNotEmpty()) events.add(StreamEvent.Delta(it))
        }
        if (obj["done"]?.jsonPrimitive?.booleanOrNull == true) events.add(StreamEvent.Done("stop"))
        return events
    }

    suspend fun listModels(p: ProviderConfig): List<String> {
        val req = Request.Builder().url("${p.endpoint()}/api/tags").get().build()
        TestClient.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val obj = LlmJson.parseToJsonElement(resp.body?.string() ?: "").jsonObject
            return obj["models"]?.jsonArray?.mapNotNull { m ->
                (m as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            }?.sorted() ?: emptyList()
        }
    }

    suspend fun testModel(p: ProviderConfig, model: String): ModelTestResult {
        var result = ModelTestResult()
        try {
            val t0 = System.currentTimeMillis()
            val req = chatRequest(p, model, listOf(ChatTurn("user", "ping")), emptyList(), stream = false)
            TestClient.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                resp.body?.string()
            }
            result = result.copy(latencyMs = System.currentTimeMillis() - t0, streamOk = true)
        } catch (e: Exception) {
            return result.copy(error = "连接失败: ${e.describe()}")
        }
        return result
    }
}
