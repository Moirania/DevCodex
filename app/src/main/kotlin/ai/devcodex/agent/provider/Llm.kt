package ai.devcodex.agent.provider

import ai.devcodex.agent.data.ProviderConfig
import ai.devcodex.agent.data.ProviderType
import ai.devcodex.agent.data.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ============ LLM 请求/回调统一模型 ============
data class ChatTurn(
    val role: String,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
    /** Anthropic thinking 块原文与签名，开启 thinking 的多轮工具调用必须原样回传 */
    val thinking: String? = null,
    val thinkingSignature: String? = null,
    /** 用户消息携带的图片（base64，不含 data: 前缀） */
    val images: List<ImagePart> = emptyList(),
)

data class ImagePart(val mime: String, val base64: String)

sealed interface StreamEvent {
    data class Usage(val inputTokens: Long, val outputTokens: Long, val cachedTokens: Long) : StreamEvent
    data class Delta(val text: String) : StreamEvent
    data class Reasoning(val text: String) : StreamEvent
    data class ThinkingSignature(val signature: String) : StreamEvent
    data class ToolCallDelta(val index: Int, val id: String?, val name: String?, val argsFragment: String) : StreamEvent
    data class Done(val finishReason: String?) : StreamEvent
    data class Error(val throwable: Throwable) : StreamEvent
}

val LlmJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

val LlmClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

/** 测试专用客户端：总超时 90s，避免慢模型/断网把测试挂死 */
val TestClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(75, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .build()

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** POST JSON 并逐行回调（SSE），onLine 收到每行原始文本 */
suspend fun streamPost(
    client: OkHttpClient,
    request: Request,
    onLine: (String) -> Unit,
): Response = withContext(Dispatchers.IO) {
    val response = client.newCall(request).await()
    if (!response.isSuccessful) {
        val body = response.body?.string()?.take(500) ?: ""
        throw IOException("HTTP ${response.code}: $body")
    }
    val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
    val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
    // 取消时关闭响应体，打断阻塞中的 readLine
    val cancelHandle = job?.invokeOnCompletion { cause ->
        if (cause != null) runCatching { response.close() }
    }
    try {
        while (true) {
            if (job?.isActive == false) throw java.util.concurrent.CancellationException("已停止")
            val line = reader.readLine() ?: break
            if (line.isEmpty()) continue
            onLine(line)
        }
    } finally {
        cancelHandle?.dispose()
        runCatching { response.close() }
    }
    response
}

fun jsonBody(element: JsonElement): okhttp3.RequestBody =
    element.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

fun jsonBody(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): okhttp3.RequestBody =
    jsonBody(buildJsonObject(block))
