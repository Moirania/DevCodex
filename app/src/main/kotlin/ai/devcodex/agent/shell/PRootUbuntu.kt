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
            // proot 二进制 + 依赖库（talloc/shmem/termux-exec + rootfs loader，全部自带，不依赖 Termux）
            val prootBin = File(prootDir, "proot")
            if (!prootBin.exists()) {
                ctx.assets.open("proot/proot").use { input -> prootBin.outputStream().use { input.copyTo(it) } }
                prootBin.setExecutable(true)
            }
            // proot 动态依赖：libtalloc.so.2 / libandroid-shmem.so / libtermux-exec-ld-preload.so（Termux 官方 aarch64）
            listOf("libtalloc.so.2", "libandroid-shmem.so", "libtermux-exec-ld-preload.so").forEach { lib ->
                val f = File(prootDir, lib)
                if (!f.exists()) runCatching {
                    ctx.assets.open("proot/$lib").use { input -> f.outputStream().use { input.copyTo(it) } }
                }
            }
            // proot rootfs exec loader（放 usr/libexec/proot/，proot 默认查找路径）
            runCatching {
                val lexDir = File(File(ctx.filesDir, "usr"), "libexec/proot").apply { mkdirs() }
                val loader = File(lexDir, "loader")
                if (!loader.exists()) ctx.assets.open("proot/loader").use { input -> loader.outputStream().use { input.copyTo(it) } }
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
        val termuxLib = File(ctx.filesDir, "usr/lib").absolutePath
        val ws = ai.devcodex.agent.tools.BuiltinTools.workspaceRoot().absolutePath
        File(root, "root/workspace").mkdirs()
        val quoted = "'" + command.replace("'", "'\\''") + "'"
        val full = "$proot -r ${root.absolutePath} " +
            "-b /dev -b /proc -b /sys -b /sdcard -b /data/local/tmp -b '$ws:/root/workspace' -b '${ctx.cacheDir.absolutePath}' " +
            "-w /root/workspace -0 /usr/bin/env HOME=/root PATH=/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin " +
            "/bin/sh -c $quoted"
        return runCatching {
            val bash = File(ctx.filesDir, "usr/bin/bash")
            val pb = if (bash.exists()) ProcessBuilder(bash.absolutePath, "-c", full) else ProcessBuilder("sh", "-c", full)
            val prootLibDir = File(ctx.filesDir, "proot-bin").absolutePath
            pb.redirectErrorStream(true).apply {
                // proot-bin 优先：proot 自身动态依赖 talloc/shmem/termux-exec 全部自带
                environment()["LD_LIBRARY_PATH"] = prootLibDir + ":" + termuxLib + ":" + (System.getenv("LD_LIBRARY_PATH") ?: "")
                environment()["PATH"] = File(ctx.filesDir, "usr/bin").absolutePath + ":/system/bin:/system/xbin"
            }
            val p = pb.start()
            val out = StringBuilder()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
            val t = Thread { reader.forEachLine { if (out.length < 60_000) out.append(it).append('\n') } }
            t.isDaemon = true; t.start()
            val done = p.waitFor(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            if (!done) {
                // 超时强杀：destroy 对 sh→proot→命令 链不彻底，destroyForcibly + 确认退出
                p.destroyForcibly()
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
                return "ERROR: 命令超时（${timeoutSec}s，已终止）。长任务请让命令自身后台化（nohup … &）后轮询输出文件"
            }
            val code = p.exitValue()
            t.join(500)
            // 空输出带 exit code：明确告知命令已执行完，避免模型误判失败而重复执行
            out.toString().ifBlank { "(exit=$code，无标准输出)" }
        }.getOrElse { "ERROR: " + (it.message ?: "未知") }
    }
}
