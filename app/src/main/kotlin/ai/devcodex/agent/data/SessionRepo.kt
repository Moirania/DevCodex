package ai.devcodex.agent.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** 会话持久化：filesDir/sessions/<id>.json，一个会话一个文件（tmp+rename 原子写，进程内互斥） */
class SessionRepo(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val dir: File get() = File(context.filesDir, "sessions").apply { mkdirs() }
    private val lock = Any()

    private fun fileOf(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9._-]+")) && id != "." && id != "..") { "非法会话 id" }
        return File(dir, "$id.json")
    }

    /** 解析失败时保留损坏文件（改名为 .corrupt），避免会话无声消失后又被覆盖 */
    private fun readOrQuarantine(f: File): ChatSession? = try {
        json.decodeFromString<ChatSession>(f.readText())
    } catch (e: Exception) {
        ai.devcodex.agent.util.DebugLog.e("SessionRepo", "会话文件损坏，已隔离: ${f.name}", e)
        runCatching { f.renameTo(File(f.parentFile, f.name + ".corrupt")) }
        null
    }

    fun list(): List<ChatSession> = synchronized(lock) {
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { readOrQuarantine(it) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    fun get(id: String): ChatSession? = synchronized(lock) {
        runCatching { fileOf(id) }.getOrNull()?.takeIf { it.exists() }?.let { readOrQuarantine(it) }
    }

    fun save(session: ChatSession) {
        val text = json.encodeToString(ChatSession.serializer(), session)
        synchronized(lock) {
            val target = fileOf(session.id)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    fun delete(id: String) {
        synchronized(lock) { runCatching { fileOf(id).delete() } }
    }

    fun newSession(): ChatSession {
        val s = ChatSession(id = UUID.randomUUID().toString())
        save(s)
        return s
    }
}
