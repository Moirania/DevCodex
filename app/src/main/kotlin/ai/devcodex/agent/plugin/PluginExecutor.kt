package ai.devcodex.agent.plugin

import ai.devcodex.agent.agent.ToolDef
import ai.devcodex.agent.data.PluginTool
import ai.devcodex.agent.provider.LlmClient
import ai.devcodex.agent.provider.await
import ai.devcodex.agent.util.JsonArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 插件工具执行器：HTTP 模板 + {{param}} 替换 */
object PluginExecutor {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 把插件工具定义转成 ToolDef */
    fun toToolDef(t: PluginTool): ToolDef {
        val schema = runCatching { json.parseToJsonElement(t.parameters) as? JsonObject }
            .getOrNull() ?: kotlinx.serialization.json.buildJsonObject {
            put("type", JsonPrimitive("object")); put("properties", kotlinx.serialization.json.buildJsonObject {})
        }
        return ToolDef(
            name = t.name,
            description = t.description.ifBlank { "插件工具 ${t.name}" },
            parameters = schema,
            risk = ToolDef.Risk.LOW,
        ) { args -> executeTool(t, args) }
    }

    /** 执行插件工具 */
    private suspend fun executeTool(t: PluginTool, argsJson: String): String = withContext(Dispatchers.IO) {
        try {
            val req = buildRequest(t, argsJson)
            LlmClient.newCall(req).await().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext "HTTP ${resp.code}: ${body.take(500)}"
                extractResult(t.endpoint.resultPath, body)
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun buildRequest(t: PluginTool, argsJson: String): Request {
        val url = substitute(t.endpoint.url, argsJson)
            .replace(" ", "%20")
        val builder = Request.Builder().url(url)
        t.endpoint.headers.forEach { (k, v) -> builder.addHeader(k, substitute(v, argsJson)) }
        val method = t.endpoint.method.uppercase()
        if (method == "GET" || method == "DELETE") {
            builder.method(method, null)
        } else {
            val bodyText = t.endpoint.bodyTemplate?.let { substitute(it, argsJson) } ?: argsJson
            builder.method(method, bodyText.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        return builder.build()
    }

    /** {{param}} 占位替换（URL 编码） */
    private fun substitute(template: String, argsJson: String): String {
        var result = template
        // 提取全部参数键值
        val obj = runCatching { json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
        obj?.forEach { (k, v) ->
            val raw = v.jsonPrimitive.contentOrNull ?: v.toString()
            result = result.replace("{{$k}}", raw)
        }
        return result
    }

    /** 简单路径提取 a.b.c；null=返回原文 */
    private fun extractResult(path: String?, body: String): String {
        if (path.isNullOrBlank()) return body.take(8000)
        return try {
            var el: kotlinx.serialization.json.JsonElement = json.parseToJsonElement(body)
            path.trim('.').split('.').forEach { seg ->
                el = (el as? JsonObject)?.get(seg) ?: return body.take(8000)
            }
            el.toString().take(8000)
        } catch (e: Exception) { body.take(8000) }
    }

    /** 内置 http_request 工具的原始执行 */
    suspend fun executeRaw(argsJson: String): String = withContext(Dispatchers.IO) {
        try {
            val url = JsonArg.string(argsJson, "url") ?: return@withContext "ERROR: 缺少 url"
            val method = (JsonArg.string(argsJson, "method") ?: "GET").uppercase()
            val builder = Request.Builder().url(url)
            // v0.4.15 修 Issue#4：headers 模型可能传 JSON 对象（崩溃根源：按 string 取 JsonObject 必炸）
            // 两种都支持：对象直接展开；字符串则再 parse（旧逻辑）
            val headersEl = JsonArg.parse(argsJson)
                ?.let { it as? kotlinx.serialization.json.JsonObject }?.get("headers")
            val headersObj: kotlinx.serialization.json.JsonObject? = when {
                headersEl is kotlinx.serialization.json.JsonObject -> headersEl
                headersEl?.jsonPrimitive?.contentOrNull?.isNotBlank() == true ->
                    runCatching { json.parseToJsonElement(headersEl.jsonPrimitive.content).jsonObject }.getOrNull()
                else -> null
            }
            headersObj?.forEach { (k, v) ->
                builder.addHeader(k, v.jsonPrimitive.contentOrNull ?: v.toString())
            }
            val body = JsonArg.string(argsJson, "body")
            if (method == "GET" || (method == "DELETE" && body == null)) {
                builder.method(method, null)
            } else {
                val contentType = if (url.contains("json") || body?.trimStart()?.startsWith("{") == true || body?.trimStart()?.startsWith("[") == true)
                    "application/json; charset=utf-8" else "text/plain; charset=utf-8"
                builder.method(method, (body ?: "").toRequestBody(contentType.toMediaType()))
            }
            LlmClient.newCall(builder.build()).await().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) "HTTP ${resp.code}: ${respBody.take(500)}" else respBody.take(20000)
            }
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }
}
