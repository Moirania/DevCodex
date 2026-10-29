package ai.devcodex.agent.util

import ai.devcodex.agent.BuildConfig
import ai.devcodex.agent.DevCodexApp
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 调试日志：filesDir/logs 下的环形文件日志（两个文件，各最多 512KB），写入前脱敏，可导出 zip */
object DebugLog {

    private const val MAX_BYTES = 512L * 1024
    private const val MAX_MSG = 4000

    @Volatile private var dir: File? = null
    private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }

    private val keyValue = Regex("(?i)(api[_-]?key|authorization|token|password|secret)(\\s*[\"':=]+\\s*)[^\\s,\"'}]+")
    private val skKey = Regex("sk-[A-Za-z0-9_-]{8,}")
    private val bearer = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}")

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
        i("App", "start v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} Android ${android.os.Build.VERSION.RELEASE}")
    }

    fun redact(text: String): String = text
        .replace(bearer, "Bearer ***")
        .replace(skKey, "sk-***")
        .replace(keyValue) { m -> m.groupValues[1] + m.groupValues[2] + "***" }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    @Synchronized
    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        when (level) {
            "E" -> Log.e(tag, msg, t)
            "W" -> Log.w(tag, msg, t)
            else -> Log.i(tag, msg)
        }
        val d = dir ?: return
        runCatching {
            val f = File(d, "debug.log")
            if (f.length() > MAX_BYTES) {
                val old = File(d, "debug.1.log")
                old.delete()
                f.renameTo(old)
            }
            val line = buildString {
                append(timeFmt.get()!!.format(Date())).append(' ').append(level).append('/').append(tag).append(": ")
                append(redact(msg.take(MAX_MSG)))
                if (t != null) append('\n').append(redact(Log.getStackTraceString(t)))
                append('\n')
            }
            f.appendText(line)
        }
    }

    private suspend fun settingsSummary(): String = runCatching {
        val s = DevCodexApp.instance.settingsRepo.settings.first()
        buildString {
            appendLine("providers: ${s.providers.size}")
            s.providers.forEach { p ->
                appendLine("- ${p.name} type=${p.type} baseUrl=${p.baseUrl} apiKey=${if (p.apiKey.isBlank()) "empty" else "set(len=${p.apiKey.length})"}")
            }
            appendLine("sshHost set: ${s.sshHost.isNotBlank()}")
            appendLine("axsUrl set: ${s.axsUrl.isNotBlank()}")
            appendLine("workspaceDir: ${s.workspaceDir}")
        }
    }.getOrElse { "settings summary unavailable: ${it.message}" }

    /** 导出 zip：logs/、crash/、info.txt、settings_summary.txt（均已脱敏，不含密钥原文） */
    suspend fun exportZip(context: Context, out: OutputStream) = withContext(Dispatchers.IO) {
        val summary = settingsSummary()
        ZipOutputStream(out.buffered()).use { zip ->
            fun addText(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(redact(text).toByteArray())
                zip.closeEntry()
            }
            addText("info.txt", buildString {
                appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("version: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                appendLine("abi: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            })
            addText("settings_summary.txt", summary)
            listOf("logs", "crash").forEach { sub ->
                File(context.filesDir, sub).listFiles()?.sortedBy { it.name }?.forEach { f ->
                    if (f.isFile) runCatching { addText("$sub/${f.name}", f.readText()) }
                }
            }
        }
    }
}
