package ai.devcodex.agent.shell

import android.content.Context
import java.io.File

/**
 * 内置 Ubuntu（Operit 同款方案）：PRoot 静态二进制 + Ubuntu rootfs
 * - 无需 root：PRoot 在用户空间做 syscall 翻译
 * - 首次启动解压 rootfs（assets/ubuntu-rootfs.tar.gz → filesDir/ubuntu）
 * - exec(cmd)：proot -r ubuntu 运行任意 Linux 命令（apt/python/gcc…）
 */
object PRootUbuntu {
    private var initialized = false

    fun rootfsDir(ctx: Context): File = File(ctx.filesDir, "ubuntu")

    /** 首次解压 proot 二进制 + rootfs（~80MB 解压后），进度回调 0..100 */
    fun ensure(ctx: Context, onProgress: (Int) -> Unit = {}): Boolean {
        if (initialized && rootfsDir(ctx).resolve("bin").exists()) return true
        return runCatching {
            val prootDir = File(ctx.filesDir, "proot-bin").apply { mkdirs() }
            // proot 二进制 + 依赖库（talloc/shmem/loader 全套，LD_LIBRARY_PATH 指 Termux usr/lib）
            val prootBin = File(prootDir, "proot")
            if (!prootBin.exists()) {
                ctx.assets.open("proot/proot").use { input -> prootBin.outputStream().use { input.copyTo(it) } }
                prootBin.setExecutable(true)
            }
            // rootfs（大文件解压一次）
            val root = rootfsDir(ctx)
            val readyMark = File(root, ".devcodex_ready")
            if (!readyMark.exists() || !root.resolve("bin").exists()) {
                root.mkdirs()
                onProgress(5)
                val tmp = File(ctx.cacheDir, "ubuntu-rootfs.tar.gz")
                ctx.assets.open("ubuntu-rootfs.tar.gz").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                onProgress(40)
                // tar 解压（用系统 toybox tar，Android 8+ 自带）
                val p = ProcessBuilder("tar", "-xzf", tmp.absolutePath, "-C", root.absolutePath)
                    .redirectErrorStream(true).start()
                val code = p.waitFor()
                onProgress(95)
                tmp.delete()
                if (code != 0) error("rootfs 解压失败 exit=$code")
                readyMark.writeText("ok")
            }
            // resolv.conf（联网）
            runCatching {
                File(root, "etc").mkdirs()
                File(root, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
            }
            initialized = true
            onProgress(100)
            root.resolve("bin").exists()
        }.getOrDefault(false)
    }

    /** 在 Ubuntu 内执行命令（PRoot 用户空间） */
    fun exec(ctx: Context, command: String, timeoutSec: Int = 150): String {
        val root = rootfsDir(ctx)
        val proot = File(ctx.filesDir, "proot-bin/proot").absolutePath
        val termuxUsr = ai.devcodex.agent.shell.TermuxRuntime.prefix(ctx)
        val termuxLib = File(termuxUsr, "lib").absolutePath
        val ws = ai.devcodex.agent.tools.BuiltinTools.workspaceRoot().absolutePath
        File(root, "root/workspace").mkdirs()
        val quoted = "'" + command.replace("'", "'\\''") + "'"
        val full = "$proot -r ${root.absolutePath} " +
            "-b /dev -b /proc -b /sys -b /sdcard -b /data/local/tmp -b '$ws:/root/workspace' -b '${ctx.cacheDir.absolutePath}' " +
            "-w /root/workspace -0 /usr/bin/env HOME=/root PATH=/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin " +
            "/bin/sh -c $quoted"
        return runCatching {
            val bash = ai.devcodex.agent.shell.TermuxRuntime.bash(ctx)
            val bashUsable = bash.isFile && File(termuxLib, "libreadline.so.8").exists()
            val prootTmp = File(ctx.cacheDir, "proot-tmp").apply { mkdirs() }
            val pb = if (bashUsable) ProcessBuilder(bash.absolutePath, "-c", full) else ProcessBuilder("sh", "-c", full)
            pb.redirectErrorStream(true).apply {
                environment()["LD_LIBRARY_PATH"] = termuxLib + ":" + (System.getenv("LD_LIBRARY_PATH") ?: "")
                environment()["PATH"] = File(termuxUsr, "bin").absolutePath + ":/system/bin:/system/xbin"
                environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
                environment()["PROOT_NO_SECCOMP"] = "1"
            }
            val p = pb.start()
            val out = StringBuilder()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
            val t = Thread { reader.forEachLine { if (out.length < 60_000) out.append(it).append('\n') } }
            t.isDaemon = true; t.start()
            val done = p.waitFor(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            if (!done) { p.destroy(); return "ERROR: 超时 " + timeoutSec + "s" }
            t.join(500)
            out.toString().ifBlank { "(无输出)" }
        }.getOrElse { "ERROR: " + (it.message ?: "未知") }
    }
}
