package ai.devcodex.agent.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/** 插件持久化：filesDir/plugins/<id>.json */
class PluginRepo(private val context: Context) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir: File get() = File(context.filesDir, "plugins").apply { mkdirs() }

    private fun fileOf(id: String): File {
        require(id.matches(SAFE_ID)) { "插件 id 只能包含字母、数字、点、下划线和连字符" }
        return File(dir, "$id.json")
    }

    fun list(): List<PluginManifest> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { json.decodeFromString<PluginManifest>(it.readText()) }.getOrNull() }
            ?: emptyList()

    fun save(m: PluginManifest) {
        val target = fileOf(m.id)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(json.encodeToString(PluginManifest.serializer(), m))
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    fun delete(id: String) { runCatching { fileOf(id).delete() } }

    fun installFromText(text: String): Result<PluginManifest> = runCatching {
        val m = json.decodeFromString(PluginManifest.serializer(), text.trim())
        require(m.id.isNotBlank() && m.name.isNotBlank()) { "插件缺少 id 或 name" }
        require(m.id.matches(SAFE_ID) && m.id != "." && m.id != "..") { "插件 id 只能包含字母、数字、点、下划线和连字符" }
        require(m.tools.isNotEmpty()) { "插件至少要声明一个工具" }
        require(m.tools.all { it.name.isNotBlank() && it.endpoint.url.isNotBlank() }) { "工具缺少 name 或 url" }
        require(m.tools.all { it.endpoint.url.trim().let { u -> u.startsWith("https://") || u.startsWith("http://") } }) { "工具 url 只允许 http 或 https" }
        require(m.tools.none { it.name.contains(" ") }) { "工具名不能含空格" }
        save(m)
        m
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9._-]+")
    }
}
