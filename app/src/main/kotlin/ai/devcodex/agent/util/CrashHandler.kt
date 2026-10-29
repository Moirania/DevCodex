package ai.devcodex.agent.util

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 全局崩溃捕获：堆栈落盘 filesDir/crash/，下次启动提示用户复制上报 */
object CrashHandler {

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DebugLog.e("Crash", "uncaught exception in thread ${thread.name}", throwable)
            runCatching {
                val dir = File(app.filesDir, "crash").apply { mkdirs() }
                val name = "crash_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
                File(dir, name).writeText(buildString {
                    appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.Sss", Locale.US).format(Date())}")
                    appendLine("thread: ${thread.name}")
                    appendLine("version: v${ai.devcodex.agent.BuildConfig.VERSION_NAME} (${ai.devcodex.agent.BuildConfig.VERSION_CODE})")
                    appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} Android ${android.os.Build.VERSION.RELEASE}")
                    appendLine()
                    appendLine(Log.getStackTraceString(throwable))
                })
                // 只保留最近 5 份
                dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 最近一次崩溃报告（用于启动提示） */
    fun latestReport(context: Context): String? {
        val dir = File(context.filesDir, "crash")
        return dir.listFiles()?.maxByOrNull { it.name }?.takeIf { it.length() > 0 }?.readText()
    }

    fun clear(context: Context) {
        File(context.filesDir, "crash").deleteRecursively()
    }
}
