package ai.devcodex.agent.shell

import java.io.File

/** Shizuku UserService 实现（运行于 Shizuku 进程，shell 权限） */
class ShellService : IShellService.Stub() {
    override fun exec(command: String): String = try {
        val p = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        // 看门狗：阻塞读无法超时，60 秒后强杀进程
        val watchdog = Thread {
            try { Thread.sleep(60_000); p.destroyForcibly() } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
        val sb = StringBuilder()
        p.inputStream.bufferedReader().use { r ->
            val chunk = CharArray(4096)
            while (true) {
                val n = r.read(chunk)
                if (n < 0) break
                sb.append(chunk, 0, n)
                if (sb.length > 200_000) { p.destroyForcibly(); break }
            }
        }
        watchdog.interrupt()
        runCatching { p.waitFor() }
        sb.toString().ifBlank { "(exit ${runCatching { p.exitValue() }.getOrDefault(-1)})" }
    } catch (e: Exception) { "ERROR: ${e.message}" }

    override fun read(path: String): String =
        File(path).takeIf { it.isFile }?.readText() ?: "ERROR: not a file: $path"

    override fun write(path: String, data: String) {
        File(path).parentFile?.mkdirs()
        File(path).writeText(data)
    }

    override fun list(path: String): String =
        File(path).takeIf { it.isDirectory }?.list()?.joinToString("\n")
            ?: "ERROR: not a dir: $path"

    override fun delete(path: String): Boolean = File(path).deleteRecursively()
}
