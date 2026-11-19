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

    /** 状态透出（v0.4.6）：就绪 / 未初始化 / 失败原因（含 ptrace 物理限制）——设置状态卡与工具报错共用 */
    fun status(ctx: Context): Pair<Boolean, String?> {
        val ready = rootfsDir(ctx).resolve("bin").exists() && File(ctx.filesDir, "proot-bin/proot").exists()
        return when {
            ready && prootBroken == false -> true to null
            prootBroken == true -> false to "此设备 ROM 禁用 ptrace（seccomp），PRoot 无法运行——Ubuntu 沙箱在此设备上不可用，Termux 通道不受影响"
            else -> false to ensureFatal
        }
    }

    /** 重试（v0.4.6）：清失败缓存 + readyMark，下次 ensure 重新解压 rootfs（约 1 分钟） */
    fun resetAndRetry(ctx: Context) {
        ensureFatal = null
        prootBroken = null
        initialized = false
        File(rootfsDir(ctx), ".devcodex_ready").delete()
    }

    /**
     * chroot 原生执行（v0.4.8，Root 专用）：无 proot 翻译层开销，内核原生执行。
     * 依赖：ShellAccess ROOT 模式（su 可用）+ ensure 已解压 rootfs。
     * /dev /proc 挂载失败不阻断（多数命令无 proc 也能跑）。
     */
    fun chrootExec(ctx: Context, command: String, timeoutSec: Long = 60): String? {
        val root = rootfsDir(ctx)
        if (!root.resolve("bin").exists()) return null  // rootfs 未就绪交给上层降级
        if (!ensure(ctx)) return null
        val b = root.absolutePath
        // 工作区挂进 guest（同名路径，容器内相对路径直接可见）
        val ws = ai.devcodex.agent.tools.BuiltinTools.workspaceRoot().absolutePath
        val setup = "mkdir -p $b/root/workspace $b/proc $b/dev $b/sys 2>/dev/null; " +
            "mountpoint -q $b/proc || mount -t proc proc $b/proc 2>/dev/null; " +
            "mountpoint -q $b/dev || mount --rbind /dev $b/dev 2>/dev/null; " +
            "mountpoint -q $b/sys || mount --rbind /sys $b/sys 2>/dev/null; " +
            "mountpoint -q $b/root/workspace || mount --bind $ws $b/root/workspace 2>/dev/null; true"
        val inner = "cd /root/workspace 2>/dev/null || cd /root; " + command
        val full = "$setup; chroot $b /usr/bin/env -i HOME=/root TERM=xterm-256color " +
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/sh -c " +
            "'" + inner.replace("'", "'\\''") + "'"
        // rootExec 同步接口（exec 是 suspend，chrootExec 是普通函数不能调）
        return runCatching { rootExecViaSu(full) }.getOrNull()?.take(30000)
    }

    /** su -c 同步执行（chroot 专用，不走 ShellAccess.exec 的挂起链） */
    private fun rootExecViaSu(cmd: String): String {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val t = Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(out) { if (out.length < 100_000) out.appendLine(line) }
                }
            } catch (_: Throwable) {}
        }
        t.isDaemon = true; t.start()
        val done = p.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) { p.destroyForcibly(); return "[chroot] (超时，已终止)" }
        t.join(500)
        return out.toString()
    }

    /** 首次解压 proot 二进制 + rootfs（~80MB 解压后），进度回调 0..100 */
    fun ensure(ctx: Context, onProgress: (Int) -> Unit = {}): Boolean {
        if (initialized && rootfsDir(ctx).resolve("bin").exists()) return true
        // 失败缓存（进程级）：初始化/解压失败后不再重试——否则每次调用都重解 80MB rootfs
        ensureFatal?.let { return false }
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
            // rootfs（大文件解压一次）：纯 Java 流式 GZIP+tar，不依赖系统 toybox tar
            // （装机实测 vivo Android 11 上 toybox tar 解压失败 → rootfs 目录空、沙箱从未就绪）
            val root = rootfsDir(ctx)
            val readyMark = File(root, ".devcodex_ready")
            if (!readyMark.exists() || !root.resolve("bin").exists()) {
                root.mkdirs()
                onProgress(5)
                ctx.assets.open("ubuntu-rootfs.tar.gz").buffered().use { raw ->
                    java.util.zip.GZIPInputStream(raw, 1 shl 16).use { gz ->
                        extractTar(gz, root) { done, total -> onProgress(5 + (90 * done / total.coerceAtLeast(1))) }
                    }
                }
                onProgress(95)
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
        }.onFailure { ensureFatal = "初始化失败: ${it.message}" }.getOrDefault(false)
    }

    /** proot 可用性缓存：null=未测，true=可用，false=设备 ROM seccomp 禁 ptrace 等 */
    @Volatile
    private var prootBroken: Boolean? = null

    /** ensure 失败原因缓存（进程级）：失败后不再重试，避免每次调用重复解压 80MB rootfs */
    @Volatile
    private var ensureFatal: String? = null

    /** 探测 proot 真实可用性：需 rootfs 就绪后跑 /bin/true（--version 不触发 ptrace，测不出 seccomp） */
    private fun probeProot(ctx: Context, proot: String, root: File): Boolean {
        prootBroken?.let { return !it }
        val prootLibDir = File(ctx.filesDir, "proot-bin").absolutePath
        val termuxLib = File(File(ctx.filesDir, "usr"), "lib").absolutePath
        val ok = runCatching {
            val p = ProcessBuilder(proot, "-r", root.absolutePath, "-b", "/dev", "-b", "/proc", "/bin/true")
                .redirectErrorStream(true).apply {
                    environment()["LD_LIBRARY_PATH"] = prootLibDir + ":" + termuxLib
                }.start()
            val done = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) { p.destroyForcibly(); false } else p.exitValue() == 0
        }.getOrDefault(false)
        prootBroken = ok.not()
        if (!ok) ai.devcodex.agent.util.DebugLog.w("PRoot", "probe failed: device ROM blocks ptrace (seccomp) — Ubuntu sandbox unavailable on this device")
        return ok
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
            // 前置探测：seccomp 设备直接给明确死因，不再让模型反复撞 execve Permission denied
            if (!probeProot(ctx, proot, root)) {
                return "ERROR: 此设备 ROM 的 seccomp 过滤器禁止 proot 所需的 ptrace 调用，Ubuntu 沙箱在本机不可用（Termux 沙箱不受影响，命令可改用 linux_exec 执行）"
            }
            val bash = File(ctx.filesDir, "usr/bin/bash")
            val pb = if (bash.exists()) ProcessBuilder(bash.absolutePath, "-c", full) else ProcessBuilder("sh", "-c", full)
            val prootLibDir = File(ctx.filesDir, "proot-bin").absolutePath
            pb.redirectErrorStream(true).apply {
                // proot-bin 优先：proot 自身动态依赖 talloc/shmem/termux-exec 全部自带
                environment()["LD_LIBRARY_PATH"] = prootLibDir + ":" + termuxLib + ":" + (System.getenv("LD_LIBRARY_PATH") ?: "")
                environment()["PATH"] = File(ctx.filesDir, "usr/bin").absolutePath + ":/system/bin:/system/xbin"
                // proot 临时目录默认 /tmp（不存在），指向 cache
                environment()["PROOT_TMP_DIR"] = ctx.cacheDir.absolutePath
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

    /**
     * 纯 Java tar 解压（ustar + GNU 长名 'L'/'K' 扩展）：
     * - 跳过设备文件/fifo（rootfs 里 /dev 条目在非 root 下 mknod 必失败）
     * - symlink 用 Os.symlink；目录与普通文件按 mode 设权
     * - 条目名含 ".." 跳过（防路径逃逸）
     */
    private fun extractTar(input: java.io.InputStream, destRoot: File, onProgress: (Int, Int) -> Unit) {
        val header = ByteArray(512)
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        var entries = 0
        while (true) {
            var off = 0
            while (off < 512) {
                val n = input.read(header, off, 512 - off)
                if (n < 0) { if (off == 0) return; error("tar 头截断") }
                off += n
            }
            if (header.all { it == 0.toByte() }) return  // 结束块
            fun str(a: Int, b: Int) = String(header, a, b - a, Charsets.UTF_8).trimEnd('\u0000', ' ')
            fun oct(a: Int, b: Int): Long {
                val raw = String(header, a, b - a, Charsets.UTF_8).trimEnd('\u0000', ' ')
                return if (raw.isEmpty()) 0L else java.lang.Long.parseLong(raw.trim(), 8)
            }
            val type = header[156]
            val size = oct(124, 136)
            var name = str(0, 100)
            val prefix = str(345, 500)
            if (prefix.isNotEmpty()) name = "$prefix/$name"
            val data = ByteArray(size.toInt())
            var rd = 0
            while (rd < size) {
                val n = input.read(data, rd, (size - rd).toInt())
                if (n < 0) error("tar 数据截断: $name")
                rd += n
            }
            val pad = ((511 - (size % 512)) % 512).toInt()
            if (pad > 0) {
                val skip = ByteArray(pad)
                var sk = 0
                while (sk < pad) {
                    val n = input.read(skip, sk, pad - sk)
                    if (n < 0) break
                    sk += n
                }
            }
            when (type) {
                'L'.code.toByte() -> { pendingLongName = String(data, Charsets.UTF_8).trimEnd('\u0000', ' '); continue }
                'K'.code.toByte() -> { pendingLongLink = String(data, Charsets.UTF_8).trimEnd('\u0000', ' '); continue }
            }
            val realName = pendingLongName ?: name
            val linkTarget = pendingLongLink ?: str(157, 257)
            pendingLongName = null; pendingLongLink = null
            if (realName.contains("..")) continue
            val out = File(destRoot, realName.removePrefix("./"))
            when (type) {
                '5'.code.toByte() -> out.mkdirs()
                '2'.code.toByte() -> runCatching {
                    if (out.exists()) out.delete()
                    java.nio.file.Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(linkTarget))
                }
                '0'.code.toByte(), 0.toByte() -> {
                    out.parentFile?.mkdirs()
                    out.writeBytes(data)
                    runCatching { out.setExecutable(true, false) }
                }
                else -> { /* '1' hardlink/'3' char/'4' block/'6' fifo：跳过 */ }
            }
            entries++
            if (entries and 0x3F == 0) onProgress(entries, entries + 64)
        }
    }
}
