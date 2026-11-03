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
                            if (e.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                if (!out.exists()) {
                                    zf.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                                    // 权限：SDK ZipEntry 不暴露 unix mode；统一可读，可执行由 repairBootstrap 规则化 chmod
                                    out.setReadable(true, false)
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
            // 搬迁修复：shebang/路径硬编码、apt 配置与骨架、GPG keyring、断链（幂等，含从 Termux app 目录搬迁来的旧环境）
            runCatching { repairBootstrap(context, usr) }
                .onFailure { ai.devcodex.agent.util.DebugLog.e("Termux", "repairBootstrap failed", it) }
            ready = true
            lastError = ""
            return true
        } catch (e: Exception) {
            lastError = "初始化异常: ${e.message}"
            return false
        }
    }

    /**
     * 搬迁修复（装机诊断 33 项问题的 app 侧固化，幂等）：
     * - shebang/正文硬编码 /data/data/com.termux → 实际 prefix（bad interpreter 根因）
     * - 权限保底：bin、lib/apt/methods、libexec 全可执行（apt methods 600 根因）
     * - apt.conf 全 Dir 覆盖（官方二进制默认前缀指向 com.termux）+ sources.list
     * - var/lib/apt、var/lib/dpkg、var/cache/apt 骨架（status/arch 等）
     * - GPG keyring 从 assets/termux-extra 落地（apt update NO_PUBKEY 根因）
     * - 断链修复 bzcmp/bzless/zipinfo
     */
    private fun repairBootstrap(context: Context, usr: File) {
        val fixedMark = File(usr.parentFile, ".bootstrap-fixed")
        val oldPrefix = "/data/data/com.termux/files/usr"
        val p = usr.absolutePath
        var fixedScripts = 0

        // 1) 文本路径迁移：bin/ etc/ share/ libexec/ 下非 ELF、非符号链接、<2MB 的文本文件
        if (!fixedMark.exists()) {
            listOf("bin", "etc", "share", "libexec").forEach { sub ->
                val dir = File(usr, sub)
                if (!dir.isDirectory) return@forEach
                dir.walkTopDown().forEach { f ->
                    if (!f.isFile || f.length() > 2_000_000) return@forEach
                    if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
                    runCatching {
                        val head = ByteArray(4)
                        f.inputStream().use { ins ->
                            val n = ins.read(head)
                            if (n >= 4 && head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte()) return@use
                        }
                        val text = f.readText()
                        if (text.contains(oldPrefix)) {
                            f.writeText(text.replace(oldPrefix, p))
                            fixedScripts++
                        }
                    }
                }
            }
        }

        // 2) 权限保底：bin、lib/apt/methods、libexec（及 applets）全可执行
        listOf("bin", "lib/apt/methods", "libexec", "bin/applets").forEach { sub ->
            val dir = File(usr, sub)
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true, false) }
        }

        // 3) apt.conf（官方 apt/dpkg 二进制编译前缀=com.termux，必须全量覆盖 Dir）
        val aptEtc = File(usr, "etc/apt").apply { mkdirs() }
        File(aptEtc, "apt.conf").writeText(
            "Acquire::Languages \"none\";\n" +
            "APT::Get::Install-Recommends \"false\";\n" +
            "APT::Sandbox::User \"root\";\n" +
            "Dir::State \"$p/var/lib/apt\";\n" +
            "Dir::State::status \"$p/var/lib/dpkg/status\";\n" +
            "Dir::Cache \"$p/var/cache/apt\";\n" +
            "Dir::Etc \"$p/etc/apt\";\n" +
            "Dir::Bin::methods \"$p/lib/apt/methods\";\n" +
            "Dir::Bin::dpkg \"$p/bin/dpkg\";\n" +
            "Dir::Bin::gzip \"$p/bin/gzip\";\n" +
            "Dir::Bin::tar \"$p/bin/tar\";\n" +
            "DPkg::Options { \"--root=$p\"; \"--admindir=$p/var/lib/dpkg\"; \"--log=$p/var/log/dpkg.log\"; };\n"
        )
        File(aptEtc, "sources.list").writeText("deb https://packages.termux.dev/apt/termux-main stable main\n")

        // 4) dpkg / apt 目录骨架
        listOf(
            "var/lib/apt/lists/partial", "var/cache/apt/archives/partial",
            "var/lib/dpkg/updates", "var/lib/dpkg/info", "var/lib/dpkg/triggers",
            "var/log", "var/tmp",
        ).forEach { File(usr, it).mkdirs() }
        listOf("var/lib/apt/lists/lock", "var/cache/apt/archives/lock").forEach {
            val f = File(usr, it); if (!f.exists()) f.writeText("")
        }
        File(usr, "var/lib/dpkg/status").let { if (!it.exists()) it.writeText("") }
        File(usr, "var/lib/dpkg/available").let { if (!it.exists()) it.writeText("") }
        File(usr, "var/lib/dpkg/arch").let { if (!it.exists()) it.writeText("aarch64\n") }

        // 5) GPG keyring（assets/termux-extra 真身文件，不走软链）
        runCatching {
            val kgDir = File(aptEtc, "trusted.gpg.d").apply { mkdirs() }
            context.assets.list("termux-extra/etc/apt/trusted.gpg.d")?.forEach { name ->
                val f = File(kgDir, name)
                if (!f.exists()) context.assets.open("termux-extra/etc/apt/trusted.gpg.d/$name").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            }
        }

        // 6) 断链修复：bzcmp→bzdiff、bzless→bzmore、zipinfo→unzip
        listOf("bin/bzdiff" to "bin/bzcmp", "bin/bzmore" to "bin/bzless", "bin/unzip" to "bin/zipinfo").forEach { (src, ln) ->
            val target = File(usr, src); val link = File(usr, ln)
            if (target.isFile && !java.nio.file.Files.isSymbolicLink(link.toPath())) {
                runCatching { if (link.exists()) link.delete(); android.system.Os.symlink(src, ln) }
            }
        }

        fixedMark.writeText("fixed-1 scripts=$fixedScripts")
        ai.devcodex.agent.util.DebugLog.i("Termux", "repairBootstrap done scripts=$fixedScripts")
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
                    env["LANG"] = "en_US.UTF-8"
                    env["TERM"] = "xterm-256color"
                    // HTTPS 证书（curl/wget/git 在 Termux 前缀下必须显式指定 CA）
                    val cert = File(usr, "etc/tls/cert.pem")
                    if (cert.isFile) {
                        env["CURL_CA_BUNDLE"] = cert.absolutePath
                        env["GIT_SSL_CAINFO"] = cert.absolutePath
                    }
                }
                .start()
            // 独立读线程 + 带超时 waitFor：withTimeoutOrNull 无法中断阻塞的 readLine，协程超时在这里是假的
            val out = StringBuilder()
            val t = Thread {
                p.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(out) { if (out.length < 100_000) out.appendLine(line) }
                }
            }
            t.isDaemon = true; t.start()
            val done = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) {
                p.destroyForcibly()
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
                return "ERROR: 命令超时（${timeoutSec}s，已终止）。长任务请让命令自身后台化（nohup … &）后轮询输出文件"
            }
            val code = p.exitValue()
            t.join(500)
            // 空输出不重跑命令（重复副作用），带 exit code 让模型明确命令已完成
            synchronized(out) { out.toString() }.ifBlank { "(exit=$code，无标准输出)" }
        } catch (e: Exception) { "ERROR(termux): ${e.message}" }
    }
}
