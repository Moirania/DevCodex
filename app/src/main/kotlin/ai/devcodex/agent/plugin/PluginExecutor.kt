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
            risk = ToolDef.Risk.MEDIUM,
        ) { args -> executeTool(t, args) }
    }

    /** 执行插件工具 */
    private suspend fun executeTool(t: PluginTool, argsJson: String): String = withContext(Dispatchers.IO) {
        try {
            val req = buildRequest(t, argsJson)
            SafeHttp.wrap(LlmClient).newCall(req).await().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@withContext "HTTP ${resp.code}: ${body.take(500)}"
                extractResult(t.endpoint.resultPath, body)
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun buildRequest(t: PluginTool, argsJson: String): Request {
        val url = substitute(t.endpoint.url, argsJson, encode = true)
        val builder = Request.Builder().url(url)
        t.endpoint.headers.forEach { (k, v) -> builder.addHeader(k, substitute(v, argsJson)) }
        val method = t.endpoint.method.uppercase()
        if (method == "GET" || method == "DELETE") {
            builder.method(method, null)
        } else {
            val bodyText = t.endpoint.bodyTemplate?.let { substituteJson(it, argsJson) } ?: argsJson
            builder.method(method, bodyText.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        return builder.build()
    }

    /** {{param}} 占位替换（URL 编码） */
    private fun substitute(template: String, argsJson: String, encode: Boolean = false): String {
        var result = template
        // 提取全部参数键值；encode=true 时按 UTF-8 做 URL 编码（空格转 %20），防止参数改写查询串或路径
        val obj = runCatching { json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
        obj?.forEach { (k, v) ->
            val raw = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            val value = if (encode) java.net.URLEncoder.encode(raw, "UTF-8").replace("+", "%20") else raw
            result = result.replace("{{$k}}", value)
        }
        return result
    }

    /** 请求体占位替换：参数值按 JSON 字符串转义（去掉外层引号），防止引号与换行破坏 JSON 结构 */
    private fun substituteJson(template: String, argsJson: String): String {
        var result = template
        val obj = runCatching { json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
        obj?.forEach { (k, v) ->
            val raw = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            val escaped = JsonPrimitive(raw).toString().removeSurrounding("\"")
            result = result.replace("{{$k}}", escaped)
        }
        return result
    }

    /** 简单路径提取 a.b.c；null=返回原文 */
    private fun extractResult(path: String?, body: String): String {
        if (path.isNullOrBlank()) return body.take(8000)
        return try {
            var el: kotlinx.serialization.json.JsonElement = json.parseToJsonElement(body)
            path.trim('.').split('.').forEach { seg ->
                el = when (val cur = el) {
                    is JsonObject -> cur[seg]
                    is kotlinx.serialization.json.JsonArray -> seg.toIntOrNull()?.let { cur.getOrNull(it) }
                    else -> null
                } ?: return body.take(8000)
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
            JsonArg.string(argsJson, "headers")?.let { h ->
                runCatching { json.parseToJsonElement(h).jsonObject }.getOrNull()?.forEach { (k, v) ->
                    builder.addHeader(k, (v as? JsonPrimitive)?.contentOrNull ?: v.toString())
                }
            }
            val body = JsonArg.string(argsJson, "body")
            if (method == "GET" || (method == "DELETE" && body == null)) {
                builder.method(method, null)
            } else {
                val contentType = if (url.contains("json") || body?.trimStart()?.startsWith("{") == true || body?.trimStart()?.startsWith("[") == true)
                    "application/json; charset=utf-8" else "text/plain; charset=utf-8"
                builder.method(method, (body ?: "").toRequestBody(contentType.toMediaType()))
            }
            SafeHttp.wrap(LlmClient).newCall(builder.build()).await().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) "HTTP ${resp.code}: ${respBody.take(500)}" else respBody.take(20000)
            }
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }
}
