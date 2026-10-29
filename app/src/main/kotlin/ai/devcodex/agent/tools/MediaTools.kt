package ai.devcodex.agent.tools

import ai.devcodex.agent.DevCodexApp
import ai.devcodex.agent.agent.ToolDef
import ai.devcodex.agent.data.ProviderConfig
import ai.devcodex.agent.provider.MediaClient
import ai.devcodex.agent.provider.VideoStatus
import ai.devcodex.agent.util.JsonArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 生图与视频工具：结果保存到工作区 generated 目录，工具结果以 FILE: 行携带相对路径 */
object MediaTools {
    private const val VIDEO_TIMEOUT_MS = 10 * 60 * 1000L
    private const val POLL_WINDOW_MS = 120_000L
    private val submitted = ConcurrentHashMap<String, Long>()
    private val finished = ConcurrentHashMap<String, List<String>>()

    private fun prop(type: String, desc: String): JsonObject = buildJsonObject { put("type", type); put("description", desc) }

    private fun schema(required: List<String>, props: Map<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }

    private suspend fun pickProvider(video: Boolean): ProviderConfig? {
        val s = DevCodexApp.instance.settingsRepo.settings.first()
        val c = s.providers.filter { it.enabled && (if (video) it.videoModel else it.imageModel).isNotBlank() }
        return c.firstOrNull { it.isDefault } ?: c.firstOrNull()
    }

    private fun fileLines(paths: List<String>): String = paths.joinToString("\n") { "FILE:$it" }

    private fun fail(p: ProviderConfig?, e: Throwable): String {
        var m = e.message ?: e.toString()
        if (p != null && p.apiKey.isNotBlank()) m = m.replace(p.apiKey, "***")
        return "ERROR: $m"
    }

    val imageGenerate = ToolDef(
        name = "image_generate",
        description = "调用提供商配置的生图模型，根据文字描述生成图片，保存到工作区 generated 目录并返回路径。会产生 API 费用",
        parameters = schema(
            listOf("prompt"),
            mapOf(
                "prompt" to prop("string", "图片描述"),
                "size" to prop("string", "尺寸，如 1024x1024，默认 1024x1024"),
                "n" to prop("integer", "张数，1 到 4，默认 1"),
            ),
        ),
        risk = ToolDef.Risk.MEDIUM,
    ) { args ->
        val prompt = JsonArg.string(args, "prompt")?.takeIf { it.isNotBlank() } ?: return@ToolDef "ERROR: 缺少 prompt"
        val p = pickProvider(false) ?: return@ToolDef "ERROR: 当前提供商未配置生图模型，请到提供商设置中填写"
        val size = JsonArg.string(args, "size")?.takeIf { Regex("\\d{3,4}x\\d{3,4}").matches(it) } ?: "1024x1024"
        val n = (JsonArg.int(args, "n") ?: 1).coerceIn(1, 4)
        try {
            MediaClient.generateImages(p, prompt, size, n).fold(
                onSuccess = { list ->
                    val paths = withContext(Dispatchers.IO) { list.map { MediaStore.rel(MediaStore.save(it, "png")) } }
                    "已生成 ${paths.size} 张图片，保存在工作区 generated 目录：\n" + fileLines(paths)
                },
                onFailure = { fail(p, it) },
            )
        } catch (e: Exception) { fail(p, e) }
    }

    val videoGenerate = ToolDef(
        name = "video_generate",
        description = "提交视频生成任务并返回 task_id。生成需要数分钟，随后用 video_status 查询并取回视频。会产生 API 费用",
        parameters = schema(
            listOf("prompt"),
            mapOf(
                "prompt" to prop("string", "视频描述"),
                "image_path" to prop("string", "可选，工作区内的参考图片路径（图生视频）"),
                "duration" to prop("integer", "可选，时长（秒）"),
            ),
        ),
        risk = ToolDef.Risk.MEDIUM,
    ) { args ->
        val prompt = JsonArg.string(args, "prompt")?.takeIf { it.isNotBlank() } ?: return@ToolDef "ERROR: 缺少 prompt"
        val p = pickProvider(true) ?: return@ToolDef "ERROR: 当前提供商未配置视频模型，请到提供商设置中填写"
        val imgPath = JsonArg.string(args, "image_path")?.takeIf { it.isNotBlank() }
        try {
            val imgB64 = if (imgPath != null) {
                val root = BuiltinTools.workspaceRoot().canonicalFile
                val f = File(root, imgPath.removePrefix("./")).canonicalFile
                if (!f.path.startsWith(root.path + File.separator) || !f.isFile) return@ToolDef "ERROR: image_path 必须是工作区内的图片文件"
                if (f.length() > 10L * 1024 * 1024) return@ToolDef "ERROR: 参考图片超过 10MB"
                withContext(Dispatchers.IO) { java.util.Base64.getEncoder().encodeToString(f.readBytes()) }
            } else null
            MediaClient.submitVideo(p, prompt, imgB64, JsonArg.int(args, "duration")).fold(
                onSuccess = { id ->
                    submitted[id] = System.currentTimeMillis()
                    "视频任务已提交，task_id=$id。请调用 video_status 查询进度，成功后会自动保存视频"
                },
                onFailure = { fail(p, it) },
            )
        } catch (e: Exception) { fail(p, e) }
    }

    val videoStatus = ToolDef(
        name = "video_status",
        description = "查询视频任务状态；任务成功时下载视频保存到工作区 generated 目录并返回路径。单次调用最多等待 2 分钟，未完成可稍后再调用",
        parameters = schema(listOf("task_id"), mapOf("task_id" to prop("string", "video_generate 返回的任务 ID"))),
        risk = ToolDef.Risk.MEDIUM,
    ) { args ->
        val id = JsonArg.string(args, "task_id")?.takeIf { it.isNotBlank() } ?: return@ToolDef "ERROR: 缺少 task_id"
        finished[id]?.let { return@ToolDef "视频已保存（task_id=$id）：\n" + fileLines(it) }
        val p = pickProvider(true) ?: return@ToolDef "ERROR: 当前提供商未配置视频模型，请到提供商设置中填写"
        val start = submitted.getOrPut(id) { System.currentTimeMillis() }
        try {
            val deadline = System.currentTimeMillis() + POLL_WINDOW_MS
            var last = VideoStatus.PENDING
            while (true) {
                if (System.currentTimeMillis() - start > VIDEO_TIMEOUT_MS) return@ToolDef "ERROR: 视频任务超过 10 分钟仍未完成，已超时。task_id=$id，可稍后再次调用 video_status"
                val t = MediaClient.queryVideo(p, id).getOrElse { return@ToolDef fail(p, it) }
                last = t.status
                when (t.status) {
                    VideoStatus.SUCCEEDED -> {
                        val bytes = MediaClient.download(t.videoUrl!!).getOrElse { return@ToolDef "ERROR: 视频下载失败：${it.message}；远程地址：${t.videoUrl}" }
                        val path = withContext(Dispatchers.IO) { MediaStore.rel(MediaStore.save(bytes, "mp4")) }
                        finished[id] = listOf(path)
                        return@ToolDef "视频已保存：\n" + fileLines(listOf(path))
                    }
                    VideoStatus.FAILED -> return@ToolDef "ERROR: 视频任务失败（task_id=$id）：${t.error ?: "未知原因"}"
                    else -> {}
                }
                if (System.currentTimeMillis() + 5000 > deadline) break
                delay(5000)
            }
            "任务仍在处理中（状态 $last，task_id=$id），请稍后再次调用 video_status"
        } catch (e: Exception) { fail(p, e) }
    }

    val all = listOf(imageGenerate, videoGenerate, videoStatus)
}
