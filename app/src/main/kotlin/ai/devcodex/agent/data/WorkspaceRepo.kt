package ai.devcodex.agent.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工作区：应用专属目录 filesDir/workspace
 * - 用户上传的文件都存这里，Agent 工具可直接读写
 * - fs_read/fs_write/fs_list 的相对路径自动解析到此处
 */
class WorkspaceRepo(private val context: Context) {

    val root: File
        get() = if (ai.devcodex.agent.data.WorkspaceConfig.dir.isNotBlank())
            File(ai.devcodex.agent.data.WorkspaceConfig.dir).apply { mkdirs() }
        else File(context.filesDir, "workspace").apply { mkdirs() }

    /** 相对路径 → 工作区绝对路径（防目录穿越） */
    fun resolve(path: String): File {
        val p = path.removePrefix("./").trimStart('/')
        val f = File(root, p)
        return if (f.canonicalPath.startsWith(root.canonicalPath)) f else root
    }

    fun isWorkspacePath(path: String): Boolean {
        val f = File(path)
        return f.canonicalPath.startsWith(root.canonicalPath)
    }

    data class Entry(
        val file: File,
        val name: String,
        val size: Long,
        val modified: Long,
        val isDir: Boolean,
        val relativePath: String,
    )

    fun list(relPath: String = ""): List<Entry> {
        val dir = resolve(relPath)
        return dir.listFiles()?.map { f ->
            Entry(
                file = f, name = f.name, size = f.length(), modified = f.lastModified(),
                isDir = f.isDirectory,
                relativePath = f.relativeTo(root).path,
            )
        }?.sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
            ?: emptyList()
    }

    /** 从 SAF URI 导入文件（保留原始文件名，重名自动加后缀） */
    fun import(uri: Uri): Result<Entry> = runCatching {
        val resolver = context.contentResolver
        val name = queryName(uri) ?: "file_${System.currentTimeMillis()}"
        var target = File(root, name)
        var n = 1
        while (target.exists()) {
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            target = File(root, "$stem($n)$ext")
            n++
        }
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法读取所选文件")
        Entry(target, target.name, target.length(), target.lastModified(), false, target.relativeTo(root).path)
    }

    private fun queryName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    /** 新建文件（自动跳过已存在） */
    fun createFile(relativePath: String): Boolean = runCatching {
        val f = resolve(relativePath)
        f.parentFile?.mkdirs()
        f.createNewFile()
    }.getOrDefault(false)

    /** 新建文件夹 */
    fun createDir(relativePath: String): Boolean = runCatching {
        resolve(relativePath).mkdirs()
    }.getOrDefault(false)

    fun readText(e: Entry): String = e.file.readText()

    fun readImage(e: Entry): android.graphics.Bitmap? =
        android.graphics.BitmapFactory.decodeFile(e.file.absolutePath)

    fun delete(relPath: String): Boolean = resolve(relPath).deleteRecursively()

    fun read(relPath: String): String = resolve(relPath).readText()

    fun write(relPath: String, content: String) {
        val f = resolve(relPath)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    fun isTextFile(entry: Entry): Boolean {
        val ext = entry.file.extension.lowercase()
        return ext in setOf("txt", "md", "json", "xml", "kt", "java", "py", "js", "ts", "csv", "log", "yml", "yaml", "toml", "html", "css", "sh", "gradle", "kts", "properties", "c", "cpp", "h", "go", "rs", "sql", "ini", "conf") ||
            entry.file.length() < 64 && runCatching { entry.file.readText().all { !it.isWhitespace() || it.code < 128 } }.getOrDefault(false)
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
        else -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
    }

    fun formatDate(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ts))

    companion object {
        fun textPreview(file: File, maxChars: Int = 200_000): String = runCatching {
            val text = file.readText()
            if (text.length > maxChars) text.take(maxChars) + "\n…(共 ${text.length} 字符，截断预览)" else text
        }.getOrElse { "（无法预览：${it.message}）" }
    }
}
