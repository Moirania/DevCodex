package ai.devcodex.agent.shell

import android.content.Context
import java.io.File

/**
 * Termux 原生沙盒（双保险架构）：
 * - 主 shell：静态 busybox（musl 三件套：busybox+ld-musl+libc.musl，零外部依赖）
 *   → 任何设备都能跑（不依赖 bootstrap 的动态库完整性）
 * - 完整环境：Termux 官方 bootstrap（bash/apt 工具链）
 *   bash 若缺库（如 readline），提示用户联网 apt 补包，不影响 busybox sh
 * - exec 走 ld-musl 显式加载 busybox（设备无 /lib/ld-musl，必须 loader 起步）
 */
object TermuxRuntime {

    @Volatile
    private var ready = false

    @Volatile
    var lastError: String = ""

    fun prefix(context: Context): File = File(context.filesDir, "termux/usr")

    fun busybox(context: Context): File = File(prefix(context), "bin/busybox_static")

    fun muslDir(context: Context): File = File(prefix(context), "musl-lib")

    fun bash(context: Context): File = File(prefix(context), "bin/bash")

    fun isReady(context: Context): Boolean =
        busybox(context).isFile && busybox(context).canExecute()

    @Synchronized
    fun ensure(context: Context): Boolean {
        if (ready) return true
        val usr = prefix(context)
        val marker = File(usr.parentFile, ".installed")
        try {
            // ---- 1. busybox 三件套（零依赖主 shell） ----
            val bb = busybox(context)
            val mDir = muslDir(context)
            mDir.mkdirs()
            File(usr, "bin").mkdirs()
            if (!bb.isFile) {
                context.assets.open("busybox").use { i -> bb.outputStream().use { o -> i.copyTo(o) } }
            }
            bb.setExecutable(true, false)
            for (n in listOf("ld-musl-aarch64.so.1", "libc.musl-aarch64.so.1")) {
                val f = File(mDir, n)
                if (!f.isFile) {
                    context.assets.open("busybox_lib/$n").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                }
                f.setExecutable(true, false)
            }
            if (!isReady(context)) {
                lastError = "busybox 解压失败"
                return false
            }
            // ---- 2. bootstrap 完整环境（可失败，不阻塞 busybox） ----
            if (!marker.exists() && !bash(context).isFile) {
                try {
                    val tmp = File(context.cacheDir, "bootstrap.zip")
                    context.assets.open("termux-bootstrap.zip").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    java.util.zip.ZipFile(tmp).use { zf ->
                        val entries = zf.entries()
                        while (entries.hasMoreElements()) {
                            val e = entries.nextElement()
                            val out = File(usr, e.name)
                            if (e.isDirectory) out.mkdirs()
                            else {
                                out.parentFile?.mkdirs()
                                if (!out.exists()) {
                                    zf.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                                }
                            }
                        }
                    }
                    // bin 全可执行 + bin/sh 指向 busybox（稳妥 shell）
                    File(usr, "bin").listFiles()?.forEach { if (it.isFile) it.setExecutable(true, false) }
                    tmp.delete()
                    marker.writeText("bootstrap-1")
                } catch (e: Exception) {
                    // bash 工具链失败不致命（busybox 已可用）
                    if (bash(context).isFile) bash(context).setExecutable(true, false)
                }
            }
            repairSymlinks(context)
            ready = true
            lastError = ""
            return true
        } catch (e: Exception) {
            lastError = "初始化异常: ${e.message}"
            return false
        }
    }

    /** 按 SYMLINKS.txt（目标←相对路径）补全符号链接，Java 层实现，不依赖 shell；返回新建数量 */
    fun repairSymlinks(context: Context): Int {
        val usr = prefix(context)
        if (File(usr, "lib/libreadline.so.8").exists()) return 0
        if (!bash(context).isFile) return 0
        var created = 0
        runCatching {
            java.util.zip.ZipInputStream(context.assets.open("termux-bootstrap.zip").buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (entry.name == "SYMLINKS.txt") {
                        String(zin.readBytes(), Charsets.UTF_8).lineSequence().forEach { line ->
                            val idx = line.indexOf('←')
                            if (idx <= 0) return@forEach
                            val target = line.substring(0, idx)
                            val link = File(usr, line.substring(idx + 1))
                            if (java.nio.file.Files.isSymbolicLink(link.toPath())) return@forEach
                            if (link.isFile && link.length() == 0L) link.delete()
                            if (link.exists()) return@forEach
                            link.parentFile?.mkdirs()
                            runCatching { android.system.Os.symlink(target, link.path); created++ }
                        }
                        break
                    }
                    entry = zin.nextEntry
                }
            }
        }.onFailure { ai.devcodex.agent.util.DebugLog.e("Termux", "repairSymlinks failed", it) }
        ai.devcodex.agent.util.DebugLog.i("Termux", "repairSymlinks created=$created readline=${File(usr, "lib/libreadline.so.8").exists()}")
        return created
    }

    /** bash 可跑则提示（可选） */
    fun bashReady(context: Context): Boolean = runCatching {
        val p = exec(context, "command -v bash >/dev/null && bash -c 'echo ok'", 8)
        p.contains("ok")
    }.getOrDefault(false)

    /** 执行（busybox ash 为主 shell） */
    fun exec(context: Context, command: String, timeoutSec: Long = 30, cwd: String? = null): String {
        if (!ensure(context)) return "ERROR(termux): 引擎未就绪（$lastError）"
        val usr = prefix(context)
        val loader = File(muslDir(context), "ld-musl-aarch64.so.1").absolutePath
        val bb = busybox(context).absolutePath
        val ws = ai.devcodex.agent.tools.BuiltinTools.workspaceRoot().absolutePath
        val workDir = if (cwd != null && File(cwd).isDirectory()) cwd else ws
        val wrapped = "cd '$workDir' 2>/dev/null; $command"
        return try {
            // bash 优先（bionic：系统 linker + env；Operit 同款方式）
            val bash = File(usr, "bin/bash")
            val p = if (bash.exists() && File(usr, "lib/libreadline.so.8").exists()) {
                ProcessBuilder(bash.absolutePath, "-c", wrapped)
            } else {
                ai.devcodex.agent.util.DebugLog.w("Termux", "bash skipped (exists=${bash.exists()}, readline=${File(usr, "lib/libreadline.so.8").exists()}), fallback to busybox sh")
                ProcessBuilder(loader, bb, "sh", "-c", wrapped)
            }
                .redirectErrorStream(true)
                .apply {
                    val env = environment()
                    env["PATH"] = File(usr, "bin").absolutePath + ":/system/bin:/system/xbin"
                    env["LD_LIBRARY_PATH"] = File(usr, "lib").absolutePath
                    env["HOME"] = File(usr, "home").absolutePath
                    File(usr, "home").mkdirs()
                    env["TMPDIR"] = File(usr, "tmp").absolutePath
                    File(usr, "tmp").mkdirs()
                    env["PREFIX"] = usr.absolutePath
                    env["LANG"] = "en_US.UTF-8"
                    env["TERM"] = "xterm-256color"
                }
                .start()
            // 看门狗线程：阻塞读无法被协程超时打断，到点直接杀进程
            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
            val watchdog = Thread {
                try {
                    Thread.sleep(timeoutSec * 1000)
                    timedOut.set(true)
                    p.destroyForcibly()
                } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }
            val sb = StringBuilder()
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    val chunk = CharArray(4096)
                    while (true) {
                        val n = r.read(chunk)
                        if (n < 0) break
                        sb.append(chunk, 0, n)
                        if (sb.length > 100_000) {
                            p.destroyForcibly()
                            break
                        }
                    }
                }
            }
            watchdog.interrupt()
            val out: String? = if (timedOut.get()) null else sb.toString()
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            runCatching { p.destroyForcibly() }
            val result = (out ?: "(Termux 超时 ${timeoutSec}s)")
            if (result.isBlank()) {
                // busybox 输出空（真机异常兜底）：降级系统 toybox 并带诊断标记
                val tb = ShellAccess.localExec(command, 15)
                if (tb.isNotBlank() && !tb.startsWith("ERROR")) "[busybox空输出·exit=$code→toybox] $tb"
                else "[busybox exit=$code 无输出；stderr 请导调试包]"
            } else result
        } catch (e: Exception) { "ERROR(termux): ${e.message}" }
    }
}
