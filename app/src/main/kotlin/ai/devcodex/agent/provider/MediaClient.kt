package ai.devcodex.agent.provider

import ai.devcodex.agent.data.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request

data class VideoTask(val status: VideoStatus, val videoUrl: String?, val error: String?)

enum class VideoStatus { PENDING, RUNNING, SUCCEEDED, FAILED }

/** 生图与视频的厂商接口调用；密钥不会出现在任何返回文本中 */
object MediaClient {
    private const val MAX_DOWNLOAD = 200L * 1024 * 1024

    private fun redact(p: ProviderConfig, s: String): String =
        if (p.apiKey.isBlank()) s else s.replace(p.apiKey, "***")

    private fun authed(p: ProviderConfig, url: String): Request.Builder =
        Request.Builder().url(url).addHeader("Authorization", "Bearer ${p.apiKey}")

    private suspend fun call(p: ProviderConfig, req: Request): String = withContext(Dispatchers.IO) {
        LlmClient.newCall(req).await().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw java.io.IOException(redact(p, "HTTP ${resp.code}: ${body.take(500)}"))
            body
        }
    }

    /** 点路径取值 a.b.c，数组用数字下标；取不到返回 null */
    internal fun pick(root: JsonElement, path: String): JsonElement? {
        var cur: JsonElement = root
        for (seg in path.trim('.').split('.').filter { it.isNotEmpty() }) {
            val node = cur
            cur = when (node) {
                is JsonObject -> node[seg]
                is JsonArray -> seg.toIntOrNull()?.let { node.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return cur
    }

    private fun text(root: JsonElement, path: String): String? =
        (pick(root, path) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    suspend fun generateImages(p: ProviderConfig, prompt: String, size: String, n: Int): Result<List<ByteArray>> = runCatching {
        val url = p.imageEndpoint.ifBlank { "${p.endpoint()}/images/generations" }
        val body = buildJsonObject {
            put("model", p.imageModel)
            put("prompt", prompt)
            put("size", size)
            put("n", n)
        }
        val resp = call(p, authed(p, url).post(jsonBody(body)).build())
        val data = (LlmJson.parseToJsonElement(resp) as? JsonObject)?.get("data") as? JsonArray
            ?: throw java.io.IOException("响应中没有图片数据: " + redact(p, resp.take(300)))
        val out = data.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val b64 = (o["b64_json"] as? JsonPrimitive)?.contentOrNull
            val u = (o["url"] as? JsonPrimitive)?.contentOrNull
            when {
                !b64.isNullOrBlank() -> java.util.Base64.getMimeDecoder().decode(b64)
                !u.isNullOrBlank() -> download(u).getOrThrow()
                else -> null
            }
        }
        if (out.isEmpty()) throw java.io.IOException("响应中没有图片数据: " + redact(p, resp.take(300)))
        out
    }

    suspend fun submitVideo(p: ProviderConfig, prompt: String, imageBase64: String?, duration: Int?): Result<String> = runCatching {
        if (p.videoSubmitUrl.isBlank()) throw java.io.IOException("未配置视频提交地址")
        val body = buildJsonObject {
            put("model", p.videoModel)
            put("prompt", prompt)
            if (!imageBase64.isNullOrBlank()) put("image", imageBase64)
            if (duration != null) put("duration", duration)
        }
        val resp = call(p, authed(p, p.videoSubmitUrl).post(jsonBody(body)).build())
        val root = LlmJson.parseToJsonElement(resp)
        text(root, p.videoTaskIdPath) ?: throw java.io.IOException("响应中没有任务 ID: " + redact(p, resp.take(300)))
    }

    suspend fun queryVideo(p: ProviderConfig, taskId: String): Result<VideoTask> = runCatching {
        if (p.videoStatusUrl.isBlank()) throw java.io.IOException("未配置视频查询地址")
        val url = p.videoStatusUrl.replace("{{task_id}}", java.net.URLEncoder.encode(taskId, "UTF-8"))
        val resp = call(p, authed(p, url).get().build())
        val root = LlmJson.parseToJsonElement(resp)
        val st = text(root, p.videoStatusPath)?.lowercase().orEmpty()
        val videoUrl = text(root, p.videoResultPath)
        when {
            p.videoFailValues.any { it.equals(st, true) } -> VideoTask(VideoStatus.FAILED, null, failReason(p, root, resp))
            p.videoSuccessValues.any { it.equals(st, true) } ->
                if (videoUrl != null) VideoTask(VideoStatus.SUCCEEDED, videoUrl, null)
                else VideoTask(VideoStatus.FAILED, null, "任务已成功但响应中没有视频地址: " + redact(p, resp.take(300)))
            st in PENDING_WORDS -> VideoTask(VideoStatus.PENDING, null, null)
            else -> VideoTask(VideoStatus.RUNNING, null, null)
        }
    }

    private val PENDING_WORDS = setOf("pending", "queued", "submitted", "waiting")

    private fun failReason(p: ProviderConfig, root: JsonElement, raw: String): String {
        val r = listOf("error.message", "error", "message", "fail_reason", "reason").firstNotNullOfOrNull { text(root, it) }
        return redact(p, r ?: "任务失败: " + raw.take(300))
    }

    /** 下载远程文件（厂商返回的 URL 通常带签名且有时效，不附加密钥头） */
    suspend fun download(url: String): Result<ByteArray> = runCatching {
        withContext(Dispatchers.IO) {
            LlmClient.newCall(Request.Builder().url(url).get().build()).await().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("下载失败 HTTP ${resp.code}")
                val body = resp.body ?: throw java.io.IOException("下载内容为空")
                if (body.contentLength() > MAX_DOWNLOAD) throw java.io.IOException("文件超过 200MB 上限")
                // 分块传输时长度未知，边读边计数，超过上限立即中止
                val src = body.source()
                val buf = okio.Buffer()
                var total = 0L
                while (true) {
                    val n = src.read(buf, 8192L)
                    if (n == -1L) break
                    total += n
                    if (total > MAX_DOWNLOAD) throw java.io.IOException("文件超过 200MB 上限")
                }
                buf.readByteArray()
            }
        }
    }
}
