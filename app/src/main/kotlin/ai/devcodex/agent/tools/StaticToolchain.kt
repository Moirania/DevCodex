package ai.devcodex.agent.tools

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v0.4.9 静态工具链兜底（vivo 类 ROM 禁 ptrace 场景的终极方案）：
 * apt 三路全灭（PRoot Ubuntu 被 seccomp 杀 / Termux proot 包装被杀 / Termux dpkg 编译期路径错）
 * 时，下载 musl 静态二进制——完全静态无动态依赖，直接 syscall，Android 内核原生执行零翻译。
 *
 * python：python-build-standalone（astral-sh）cpython-3.13 aarch64-unknown-linux-musl
 * install_only_stripped（26MB tar.gz），解压即用 files/static/python3/bin/python3
 */
object StaticToolchain {
    private const val PY_URL = "https://github.com/astral-sh/python-build-standalone/releases/download/20261110/cpython-3.13.18%2B20261110-aarch64-unknown-linux-musl-install_only_stripped.tar.gz"
    // v0.4.12 国内镜像 fallback（GitHub release 直连慢 → 10 分钟读超时实锤）
    private val PY_URL_FALLBACKS = listOf(
        PY_URL,
        "https://ghfast.top/https://github.com/astral-sh/python-build-standalone/releases/download/20261110/cpython-3.13.18%2B20261110-aarch64-unknown-linux-musl-install_only_stripped.tar.gz",
        "https://gh-proxy.com/https://github.com/astral-sh/python-build-standalone/releases/download/20261110/cpython-3.13.18%2B20261110-aarch64-unknown-linux-musl-install_only_stripped.tar.gz",
    )

    fun pythonDir(ctx: Context): File = File(ctx.filesDir, "static/python3")
    fun pythonBin(ctx: Context): File = File(pythonDir(ctx), "bin/python3")

    fun isPythonReady(ctx: Context): Boolean = pythonBin(ctx).isFile

    /** 下载并解压静态 python（阻塞，跑在 IO 协程）。成功返回 true */
    fun ensurePython(ctx: Context, log: (String) -> Unit = {}): Boolean {
        if (isPythonReady(ctx)) return true
        val dir = pythonDir(ctx)
        return runCatching {
            val tmp = File(ctx.cacheDir, "py_static.tar.gz")
            // 下载（HttpURLConnection，30s 连接 / 10 分钟读超时）
            log("下载静态 Python（约 26MB）…")
            var copied = 0L
            var lastErr: Exception? = null
            for (u in PY_URL_FALLBACKS) {
                try {
                    val conn = URL(u).openConnection() as HttpURLConnection
                    conn.connectTimeout = 20000
                    conn.readTimeout = 900000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "DevCodex")
                    ai.devcodex.agent.util.DebugLog.i("StaticToolchain", "downloading: code=${conn.responseCode} len=${conn.contentLength} src=${u.take(60)}")
                    if (conn.responseCode !in 200..299) error("下载失败 HTTP ${conn.responseCode}")
                    conn.inputStream.use { input -> tmp.outputStream().use { os ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n); copied += n
                        }
                    } }
                    lastErr = null; break
                } catch (e: Exception) {
                    lastErr = e
                    ai.devcodex.agent.util.DebugLog.w("StaticToolchain", "source failed (${u.take(40)}): ${e.message}")
                }
            }
            lastErr?.let { throw it }
            if (copied < 10_000_000) error("下载不完整：仅 $copied 字节（26MB 预期）")
            ai.devcodex.agent.util.DebugLog.i("StaticToolchain", "downloaded ${copied / 1048576}MB -> ${tmp.absolutePath}")
            // tar.gz 流式解压（纯 Java GZIP + tar，复用 PRootUbuntu 同款逻辑）
            log("解压中…")
            dir.deleteRecursively(); dir.parentFile?.mkdirs(); dir.mkdirs()
            java.util.zip.GZIPInputStream(tmp.inputStream().buffered(), 1 shl 16).use { gz ->
                extractTar(gz, dir)
            }
            tmp.delete()
            // bin 全部可执行
            File(dir, "bin").listFiles()?.forEach { runCatching { it.setExecutable(true, false) } }
            // 验证：直接 exec --version（静态 musl 二进制在 Android bionic 上原生可执行）
            val p = ProcessBuilder(pythonBin(ctx).absolutePath, "--version")
                .redirectErrorStream(true).start()
            val pOut = p.inputStream.bufferedReader().use { it.readText() }
            ai.devcodex.agent.util.DebugLog.i("StaticToolchain", "verify: exit=${p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)} out=[$pOut] bin=${pythonBin(ctx).absolutePath} exists=${pythonBin(ctx).isFile}")
            val ok = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
            if (!ok) { dir.deleteRecursively(); error("python --version 验证失败") }
            log("静态 Python 就绪")
            ai.devcodex.agent.util.DebugLog.i("StaticToolchain", "python ready at ${pythonBin(ctx)}")
            true
        }.onFailure {
            ai.devcodex.agent.util.DebugLog.w("StaticToolchain", "install failed: ${it.message}")
            log("安装失败：${it.message}")
        }.getOrDefault(false)
    }

    /** ustar/gnu tar 流式解压（支持普通文件/目录/软链） */
    private fun extractTar(input: java.io.InputStream, destRoot: File) {
        val header = ByteArray(512)
        while (true) {
            var off = 0
            while (off < 512) {
                val n = input.read(header, off, 512 - off)
                if (n < 0) return
                off += n
            }
            if (header.all { it == 0.toByte() }) return
            fun str(a: Int, b: Int) = String(header, a, b - a, Charsets.UTF_8).trimEnd('\u0000', ' ')
            fun oct(a: Int, b: Int): Long {
                val raw = String(header, a, b - a, Charsets.UTF_8).trimEnd('\u0000', ' ')
                return if (raw.isEmpty()) 0L else java.lang.Long.parseLong(raw.trim(), 8)
            }
            val type = header[156]
            val size = oct(124, 136)
            var name = str(0, 100)
            val prefix = str(345, 500)
            if (prefix.isNotBlank()) name = "$prefix/$name"
            val out = File(destRoot, name.removePrefix("./"))
            val data = ByteArray(size.toInt())
            var rd = 0
            while (rd < size.toInt()) {
                val n = input.read(data, rd, size.toInt() - rd)
                if (n < 0) error("tar 数据截断")
                rd += n
            }
            val pad = ((512 - (size % 512)) % 512).toInt()
            if (pad > 0) input.read(ByteArray(pad))
            if (name.contains("..")) continue
            when (type) {
                '5'.code.toByte() -> out.mkdirs()
                '2'.code.toByte() -> runCatching {
                    if (out.exists()) out.delete()
                    java.nio.file.Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(str(157, 257)))
                }
                '0'.code.toByte(), 0.toByte() -> {
                    out.parentFile?.mkdirs()
                    out.writeBytes(data)
                }
            }
        }
    }
}
