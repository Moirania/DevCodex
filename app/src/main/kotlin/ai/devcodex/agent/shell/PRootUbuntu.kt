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
    @Volatile private var prootBroken: Boolean? = null
    @Volatile private var useNoSeccomp: Boolean = false
    @Volatile private var lastProbeError: String? = null
    @Volatile private var ensureFatal: String? = null

    fun rootfsDir(ctx: Context): File = File(ctx.filesDir, "ubuntu")

    /** 状态透出（v0.4.6）：就绪 / 未初始化 / 失败原因（含 ptrace 物理限制）——设置状态卡与工具报错共用 */
    fun status(ctx: Context): Pair<Boolean, String?> {
        val ready = rootfsDir(ctx).resolve(".devcodex_ready").isFile &&
            rootfsDir(ctx).resolve("bin").exists() &&
            runCatching { PRootEnv.prootReady(ctx) }.getOrDefault(false)
        return when {
            ready && prootBroken == false -> true to null
            prootBroken == true -> false to (lastProbeError ?: "PRoot 探测失败")
            ready && prootBroken == null -> false to "rootfs 已就绪（待首次运行验证）"
            else -> false to ensureFatal
        }
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
    /** v0.4.12：ensure 失败原因查询（供 linuxExec 降级时记录） */
    fun fatalReason(): String = ensureFatal ?: "unknown"

    /** 重试（v0.4.6）：清失败缓存 + readyMark，下次 ensure 重新解压 rootfs（约 1 分钟） */
    fun resetAndRetry(ctx: Context) {
        ensureFatal = null
        prootBroken = null
        lastProbeError = null
        useNoSeccomp = false
        initialized = false
        File(rootfsDir(ctx), ".devcodex_ready").delete()
    }

    @Synchronized
    fun ensure(ctx: Context, onProgress: (Int) -> Unit = {}): Boolean {
        if (initialized && rootfsDir(ctx).resolve("bin").exists()) return true
        ensureFatal?.let { return false }
        return runCatching {
            // v0.4.16：二进制已迁 jniLibs（nativeLibraryDir 全 ROM 执行豁免区）——不再拷贝 assets，
            // 启动前校验依赖完整（缺即 fail-fast 带明确原因）
            PRootEnv.libraryPath(ctx)
            PRootEnv.loaderBinary(ctx)

            val root = rootfsDir(ctx)
            val readyMark = File(root, ".devcodex_ready")
            if (!readyMark.exists() || !root.resolve("bin").exists()) {
                // v0.4.16：staging 原子解压（修并发/中断残缺目录）——解到临时目录，
                // 校验 bin/sh 完整后删旧目录并改名
                val staging = File(ctx.filesDir, "ubuntu.staging")
                staging.deleteRecursively(); staging.mkdirs()
                onProgress(5)
                val names = listOf("ubuntu-rootfs.bin", "ubuntu-rootfs.tar.gz", "ubuntu-rootfs.tar")
                var stream: java.io.InputStream? = null
                var used = ""
                for (n in names) {
                    try {
                        stream = ctx.assets.open(n).buffered(); used = n; break
                    } catch (e: java.io.FileNotFoundException) {
                        ai.devcodex.agent.util.DebugLog.w("PRoot", "asset '$n' not found, trying next…")
                    }
                }
                val raw = stream ?: error("APK 内找不到 Ubuntu rootfs 资产（$names）")
                ai.devcodex.agent.util.DebugLog.i("PRoot", "rootfs asset=$used, 解压到 ${staging.absolutePath}")
                val head = ByteArray(2)
                runCatching { raw.mark(10); raw.read(head); raw.reset() }
                val isGzip = head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
                val tarIn: java.io.InputStream = if (isGzip) java.util.zip.GZIPInputStream(raw, 1 shl 16) else raw
                var entries = 0
                tarIn.use { ti ->
                    extractTar(ti, staging) { done, total ->
                        entries = done
                        onProgress(5 + (90 * done / total.coerceAtLeast(1)))
                    }
                }
                ai.devcodex.agent.util.DebugLog.i("PRoot", "staging 解压完成 entries=$entries, bin/sh=${staging.resolve("bin/sh").exists()}")
                if (!staging.resolve("bin/sh").exists()) error("rootfs 解压后缺 /bin/sh（entries=$entries）")
                root.deleteRecursively()
                if (!staging.renameTo(root)) error("staging 改名失败")
                onProgress(95)
                readyMark.writeText("ok")
            }
            // 客体网络：动态同步系统 DNS（v0.4.16，修写死 8.8.8.8 在受限网络超时）
            syncDns(ctx, root)
            // 宿主附加 GID 注入（v0.4.16，修 "cannot find name for group ID" 噪音）
            syncHostGroups(root)
            initialized = true
            onProgress(100)
            // ensure 完成立即探测一次（状态机同步，修 status 误报）
            probeProot(ctx, root)
            true
        }.onFailure {
            ensureFatal = "初始化失败: ${it.message}"
            ai.devcodex.agent.util.DebugLog.e("PRoot", "ensure FAILED: ${it.message}", it)
        }.getOrDefault(false)
    }

    /** v0.4.16：动态同步 Android 活跃网络 DNS 到 /etc/resolv.conf（受限网络不再写死 8.8.8.8） */
    private fun syncDns(ctx: Context, root: File) = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val servers = cm?.activeNetwork
            ?.let { cm.getLinkProperties(it) }
            ?.dnsServers
            .orEmpty()
            .mapNotNull { it.hostAddress }
            .distinct()
        val content = if (servers.isNotEmpty()) {
            servers.joinToString("\n", postfix = "\n") { "nameserver $it" }
        } else {
            "nameserver 223.5.5.5\nnameserver 8.8.8.8\n"
        }
        val resolv = File(root, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        if (java.nio.file.Files.isSymbolicLink(resolv.toPath())) {
            java.nio.file.Files.delete(resolv.toPath())
        }
        if (!resolv.isFile || resolv.readText() != content) {
            resolv.writeText(content)
        }
    }

    /** v0.4.16：宿主 supplementary GID 注入 /etc/group（修 cannot find name for group ID） */
    private fun syncHostGroups(root: File) = runCatching {
        val gids = File("/proc/self/status").readText().lineSequence()
            .firstOrNull { it.startsWith("Groups:") }
            ?.substringAfter(':')
            ?.split(' ', '\t')
            ?.mapNotNull(String::toIntOrNull)
            ?.filter { it >= 0 }
            ?.toSortedSet()
            .orEmpty()
        if (gids.isEmpty()) return@runCatching
        val groupFile = File(root, "etc/group")
        if (!groupFile.isFile) return@runCatching
        val existing = groupFile.readText()
        val configured = existing.lineSequence()
            .mapNotNull { it.split(':').getOrNull(2)?.toIntOrNull() }
            .toSet()
        val missing = gids.filter { it !in configured }
        if (missing.isEmpty()) return@runCatching
        val suffix = buildString {
            if (existing.isNotEmpty() && !existing.endsWith('\n')) append('\n')
            missing.forEach { gid -> append("host_gid_$gid:x:$gid:\n") }
        }
        groupFile.appendText(suffix)
    }

    private fun runProbeOnce(ctx: Context, root: File, noSeccomp: Boolean): Pair<Int, String> {
        val cmd = buildList {
            add(PRootEnv.prootBinary(ctx).absolutePath)
            addAll(listOf("-r", root.absolutePath, "-w", "/", "-0", "--link2symlink"))
            listOf("/dev", "/proc", "/sys")
                .filter { File(it).exists() }
                .forEach { addAll(listOf("-b", "$it:$it")) }
            addAll(listOf("/usr/bin/env", "-i", "PATH=/usr/bin:/bin", "/bin/true"))
        }
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        PRootEnv.applyHostEnvironment(ctx, pb, noSeccomp = noSeccomp)
        val p = pb.start()
        val out = StringBuilder()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().forEachLine {
                    synchronized(out) { if (out.length < 4096) out.appendLine(it) }
                }
            }
        }.apply { isDaemon = true; start() }
        val done = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
        val code = if (done) p.exitValue() else { p.destroyForcibly(); -1 }
        reader.join(500)
        return code to synchronized(out) { out.toString().trim() }
    }

    private fun probeProot(ctx: Context, root: File): Boolean {
        prootBroken?.let { return !it }
        return try {
            var (code, out) = runProbeOnce(ctx, root, noSeccomp = false)
            ai.devcodex.agent.util.DebugLog.i("PRoot", "probe(default) exit=$code out=[${out.replace("\n", " | ").take(400)}]")
            // SIGSYS(159)/seccomp 冲突 → PROOT_NO_SECCOMP=1 自动回退重试（此前直接判死刑）
            if (code != 0 && (code == 159 || out.contains("seccomp", ignoreCase = true) || out.contains("SIGSYS", ignoreCase = true))) {
                val retry = runProbeOnce(ctx, root, noSeccomp = true)
                ai.devcodex.agent.util.DebugLog.i("PRoot", "probe(PROOT_NO_SECCOMP=1) exit=${retry.first} out=[${retry.second.replace("\n", " | ").take(400)}]")
                if (retry.first == 0) {
                    useNoSeccomp = true
                    prootBroken = false
                    lastProbeError = null
                    return true
                }
                code = retry.first
                out = retry.second
            }
            if (code == 0) {
                prootBroken = false
                lastProbeError = null
                true
            } else {
                prootBroken = true
                lastProbeError = "PRoot 探测失败 (exit=$code): ${out.take(240).ifBlank { "无输出" }}"
                ai.devcodex.agent.util.DebugLog.w("PRoot", "probe failed → $lastProbeError")
                false
            }
        } catch (t: Throwable) {
            ai.devcodex.agent.util.DebugLog.e("PRoot", "probe exception", t)
            prootBroken = true
            lastProbeError = "PRoot 无法启动: ${t.message}"
            false
        }
    }

    /** 在 Ubuntu 内执行命令（v0.4.16 重构：直接 ProcessBuilder + env -i 隔离宿主环境） */
    fun exec(ctx: Context, command: String, timeoutSec: Int = 150): String {
        val root = rootfsDir(ctx)
        if (!ensure(ctx)) return "ERROR: Ubuntu rootfs 初始化失败（${fatalReason()}）"
        if (!probeProot(ctx, root)) {
            return "ERROR: ${lastProbeError ?: "PRoot 探测失败"}。Termux 通道不受影响，命令可改用 linux_exec 执行"
        }

        val ws = ai.devcodex.agent.tools.BuiltinTools.workspaceRoot().apply { mkdirs() }
        File(root, "root/workspace").mkdirs()

        val prootCmd = buildList {
            add(PRootEnv.prootBinary(ctx).absolutePath)
            addAll(listOf("-r", root.absolutePath, "-w", "/root/workspace", "-0", "--link2symlink"))
            listOf("/dev", "/proc", "/sys")
                .filter { File(it).exists() }
                .forEach { addAll(listOf("-b", "$it:$it")) }
            addAll(listOf("-b", "${ws.canonicalPath}:/root/workspace"))
            addAll(listOf(
                "/usr/bin/env", "-i",
                "HOME=/root",
                "USER=root",
                "LOGNAME=root",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "/bin/sh", "-c", command,
            ))
        }

        return runCatching {
            val pb = ProcessBuilder(prootCmd)
                .directory(ctx.filesDir)
                .redirectErrorStream(true)
            PRootEnv.applyHostEnvironment(ctx, pb, noSeccomp = useNoSeccomp)
            val p = pb.start()
            val out = StringBuilder()
            val t = Thread {
                runCatching {
                    p.inputStream.bufferedReader().forEachLine {
                        synchronized(out) { if (out.length < 60_000) out.appendLine(it) }
                    }
                }
            }.apply { isDaemon = true; start() }
            val done = p.waitFor(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            if (!done) {
                p.destroy()
                if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                }
                return "ERROR: 命令超时（${timeoutSec}s，已终止）。长任务请让命令自身后台化（nohup … &）后轮询输出文件"
            }
            val code = p.exitValue()
            t.join(500)
            synchronized(out) { out.toString() }.ifBlank { "(exit=$code，无标准输出)" }
        }.getOrElse { "ERROR: ${it.message ?: "未知错误"}" }
    }

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
                return if (raw.isEmpty()) 0L else runCatching { java.lang.Long.parseLong(raw.trim(), 8) }.getOrDefault(0L)
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
            val pad = ((512 - (size % 512)) % 512).toInt()  // v0.4.13 修 off-by-one：511 使每 entry 少读 1 字节 → 全流错位
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
                // v0.4.16：PAX 扩展头（x/g）——解析 path=/linkpath=，其余键跳过
                'x'.code.toByte(), 'g'.code.toByte() -> {
                    val pax = String(data, Charsets.UTF_8)
                    Regex("(\\d+) ([^=]+)=([\\s\\S]*?)(?=\\d+ \\w+=|$)").findAll(pax).forEach { m ->
                        when (m.groupValues[2]) {
                            "path" -> pendingLongName = m.groupValues[3].trimEnd('\u0000')
                            "linkpath" -> pendingLongLink = m.groupValues[3].trimEnd('\u0000')
                        }
                    }
                    continue
                }
            }
            val realName = pendingLongName ?: name
            val linkTarget = pendingLongLink ?: str(157, 257)
            pendingLongName = null; pendingLongLink = null
            if (realName.contains("..")) continue
            val out = File(destRoot, realName.removePrefix("./"))
            // v0.4.16：真实 tar 权限位（不再全员 +x）
            val mode = runCatching { Integer.parseInt(String(header, 100, 7, Charsets.UTF_8).trimEnd('\u0000', ' ').trim(), 8) }.getOrDefault(0)
            when (type) {
                '5'.code.toByte() -> {
                    out.mkdirs()
                    runCatching { android.system.Os.chmod(out.absolutePath, (mode and 0xFFF) or 0x1C0) }  // 目录保属主 rwx
                }
                '2'.code.toByte() -> runCatching {
                    // v0.4.16：悬空软链也能替换 + 父目录先建
                    out.parentFile?.mkdirs()
                    java.nio.file.Files.deleteIfExists(out.toPath())
                    java.nio.file.Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(linkTarget))
                }
                // v0.4.16：硬链接（'1'）——Android 私有目录禁硬链，回退复制源文件
                '1'.code.toByte() -> {
                    val linked = File(destRoot, str(0, 100).removePrefix("./"))
                    runCatching {
                        out.parentFile?.mkdirs()
                        java.nio.file.Files.copy(linked.toPath(), out.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                '0'.code.toByte(), 0.toByte() -> {
                    out.parentFile?.mkdirs()
                    out.writeBytes(data)
                    // v0.4.16：真实 mode（至少属主 rw，x 位按原样）
                    runCatching { android.system.Os.chmod(out.absolutePath, ((mode and 0xFFF) or 0x180)) }
                }
                else -> { /* '1' hardlink/'3' char/'4' block/'6' fifo：跳过 */ }
            }
            entries++
            if (entries and 0x3F == 0) onProgress(entries, entries + 64)
        }
    }
}
