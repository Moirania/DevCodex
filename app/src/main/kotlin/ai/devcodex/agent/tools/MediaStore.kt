package ai.devcodex.agent.tools

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** 生成媒体的落盘：只写入工作区 generated 目录，文件名由时间戳与序号决定 */
object MediaStore {
    private val seq = AtomicInteger(0)

    fun dir(): File = File(BuiltinTools.workspaceRoot(), "generated").also { it.mkdirs() }

    /** 按文件头魔数判断扩展名，无法判断时使用 default */
    fun detectExt(b: ByteArray, default: String): String = when {
        b.size > 3 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> "png"
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "jpg"
        b.size > 11 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        b.size > 11 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "mp4"
        b.size > 3 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> "webm"
        else -> default
    }

    fun save(bytes: ByteArray, defaultExt: String): File {
        val d = dir()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val ext = detectExt(bytes, defaultExt)
        var f: File
        do {
            f = File(d, "$stamp-${seq.incrementAndGet()}.$ext")
        } while (f.exists())
        check(f.canonicalPath.startsWith(d.canonicalPath + File.separator)) { "路径越界" }
        f.writeBytes(bytes)
        return f
    }

    /** 相对工作区根的路径，用于工具结果中的 FILE: 行 */
    fun rel(f: File): String = f.canonicalFile.relativeTo(BuiltinTools.workspaceRoot().canonicalFile).path
}
