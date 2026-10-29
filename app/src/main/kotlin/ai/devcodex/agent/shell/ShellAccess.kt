package ai.devcodex.agent.shell

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

import ai.devcodex.agent.MainActivity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import java.io.File
import java.util.concurrent.Executors

/** Shell 访问层：Shizuku(ADB 权限) 与 Root(su) 双通道 */
object ShellAccess {

    private const val SHIZUKU_PERM = 0xA001

    private val userServiceArgs by lazy {
        UserServiceArgs(ComponentName("ai.devcodex.agent", "ai.devcodex.agent.shell.ShellService"))
            .daemon(true)   // 常驻：避免每次 unbind 后 Shizuku 端销毁导致的重绑失败
            .processNameSuffix("shell")
            .debuggable(false)
            .version(3)     // 版本升级触发 Shizuku 端刷新旧绑定
    }

    @Volatile private var shizukuService: IShellService? = null
    @Volatile private var binding = false
    private val executor = Executors.newSingleThreadExecutor()

    var onPermissionResult: (() -> Unit)? = null

    fun shizukuAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun shizukuPermissionGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestShizukuPermission() {
        runCatching { Shizuku.requestPermission(SHIZUKU_PERM) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shizukuService = IShellService.Stub.asInterface(binder)
            binding = false
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuService = null
        }
    }

    fun bindShizukuService() {
        if (shizukuAvailable() && shizukuPermissionGranted() && shizukuService == null && !binding) {
            try {
                binding = true
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            } catch (e: Exception) {
                binding = false
                // 立即二次尝试（Shizuku 端旧绑定清理竞态）
                try { Shizuku.bindUserService(userServiceArgs, serviceConnection) } catch (_: Exception) {}
            }
        }
    }

    /** Shizuku 进程死亡：清引用（监听器回调） */
    fun onShizukuDead() {
        shizukuService = null
        binding = false
    }

    fun unbindShizukuService() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
        shizukuService = null
    }

    /** root 可用性检测 */
    fun rootAvailable(): Boolean = try {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.contains("uid=0")
    } catch (e: Exception) { false }

    // ============ 统一操作接口（优先 Shizuku，其次 Root） ============
    enum class Mode { SHIZUKU, ROOT, AXS, LINUX, SANDBOX }

    @Volatile
    var axsUrl: String = ""   // AXS/Termux HTTP shell 服务地址（设置页配置）

    fun availableMode(): Mode = when {
        shizukuService != null || (shizukuAvailable() && shizukuPermissionGranted()) -> Mode.SHIZUKU
        rootAvailable() -> Mode.ROOT
        axsUrl.isNotBlank() -> Mode.AXS
        else -> Mode.LINUX   // 完整 Linux 沙盒（PRoot+Alpine）
    }

    /** AXS/Termux 服务执行：POST {url} body=命令，响应纯文本即输出 */
    fun axsExec(command: String, timeoutSec: Long = 15): String {
        val url = axsUrl.takeIf { it.isNotBlank() } ?: return "ERROR: AXS 未配置（设置→工具与权限）"
        return runCatching {
            val body = command.toRequestBody("text/plain; charset=utf-8".toMediaType())
            val req = okhttp3.Request.Builder().url(url).post(body).build()
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .readTimeout(java.time.Duration.ofSeconds(timeoutSec))
                .build().newCall(req).execute().use { resp ->
                    if (resp.code !in 200..299) "ERROR(axs): HTTP ${resp.code}"
                    else resp.body?.string()?.take(30000) ?: "(空响应)"
                }
        }.getOrElse { "ERROR(axs): ${it.message}" }
    }

    /** Termux 原生环境执行（RUN_COMMAND intent，需 Termux 开启 allow-external-apps）
     *  输出经 /sdcard/DevCodex/termux_out.txt 中转（Termux uid 可写、app 可读） */
    fun termuxExec(command: String, timeoutSec: Long = 20): String {
        val out = java.io.File("/sdcard/DevCodex/termux_out.txt")
        return try {
            out.parentFile?.mkdirs()
            out.delete()
            val esc = command.replace("'", "'\\''")
            val args = "-c '$esc > /sdcard/DevCodex/termux_out.txt 2>&1'"
            val p = ProcessBuilder(
                "am", "startservice",
                "--user", "0",
                "-n", "com.termux/com.termux.app.RunCommandService",
                "-a", "com.termux.RUN_COMMAND",
                "--es", "com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh",
                "--es", "com.termux.RUN_COMMAND_ARGUMENTS", args,
                "--es", "com.termux.RUN_COMMAND_WORKDIR", "/sdcard",
            ).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor()
            // 轮询输出文件（Termux 异步执行）
            val deadline = System.currentTimeMillis() + timeoutSec * 1000
            while (!out.exists() && System.currentTimeMillis() < deadline) Thread.sleep(150)
            var content: String
            while (true) {
                Thread.sleep(300)
                content = out.readText()
                if (System.currentTimeMillis() > deadline) break
                // 文件稳定（300ms 无增长）即认为完成
                val len1 = out.length()
                Thread.sleep(300)
                if (out.length() == len1 || System.currentTimeMillis() > deadline) { content = out.readText(); break }
            }
            content.take(30000).ifBlank { "(Termux: 无输出/超时，请确认 Termux 已装且 ~/.termux/termux.properties 开启 allow-external-apps=true)" }
        } catch (e: Exception) {
            "ERROR(termux): ${e.message}（未安装 Termux 或未授权 RUN_COMMAND）"
        }
    }

    /** Termux 是否可用（安装检测） */
    fun termuxAvailable(): Boolean = try {
        val pm = ai.devcodex.agent.tools.BuiltinTools.appContext().packageManager
        pm.getPackageInfo("com.termux", 0)
        true
    } catch (e: Exception) { false }

    /** Linux 沙盒执行（内嵌 Termux 原生 bootstrap，bash/apt 全套） */
    fun linuxExec(command: String, cwd: String = "/root"): String {
        val c = ai.devcodex.agent.tools.BuiltinTools.appContext()
        if (!ai.devcodex.agent.shell.TermuxRuntime.ensure(c)) return "ERROR: Termux 原生引擎未就绪（" + ai.devcodex.agent.shell.TermuxRuntime.lastError + ")"
        return ai.devcodex.agent.shell.TermuxRuntime.exec(c, command, 30, cwd)
    }

    /** 本地沙盒执行（app uid，工作目录=工作区）：toybox 提供常规命令，可访问应用私有目录与自定义工作区 */
    fun localExec(command: String, timeoutSec: Long = 15): String = try {
        val ws = ai.devcodex.agent.tools.BuiltinTools.workspaceRoot()
        val p = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .apply { directory(ws) }
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
            p.inputStream.bufferedReader().use { reader ->
                val chunk = CharArray(4096)
                while (true) {
                    val n = reader.read(chunk)
                    if (n < 0) break
                    sb.append(chunk, 0, n)
                    if (sb.length > 60_000) break
                }
            }
        }
        watchdog.interrupt()
        runCatching { p.destroyForcibly() }
        val out: String? = if (timedOut.get()) null else sb.toString()
        val result = (out ?: "(超时 ${timeoutSec}s，已终止)").ifBlank { "(exit ok)" }
        "[sandbox|app权限] $result"
    } catch (e: Exception) { "ERROR(sandbox): ${e.message}" }

    private fun shizuku(): IShellService? {
        bindShizukuService()
        return shizukuService
    }

    /** 挂起等待 Shizuku 服务绑定（修复授权后首次调用竞态） */
    private suspend fun shizukuAwait(timeoutMs: Long = 4000): IShellService? {
        bindShizukuService()
        val t0 = System.currentTimeMillis()
        while (shizukuService == null && System.currentTimeMillis() - t0 < timeoutMs) {
            if (!shizukuAvailable() || !shizukuPermissionGranted()) return null
            kotlinx.coroutines.delay(120)
        }
        return shizukuService
    }

    private fun rootExec(command: String): String = try {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.ifBlank { "(exit ${p.exitValue()})" }
    } catch (e: Exception) { "ERROR(root): ${e.message}" }

    private fun rootWrite(path: String, data: String): String = try {
        val p = ProcessBuilder("su", "-c", "mkdir -p \"$(dirname \"$path\")\" && cat > \"$path\"").redirectErrorStream(true).start()
        p.outputStream.write(data.toByteArray())
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        if (p.exitValue() == 0) "OK" else "ERROR(root write): $out"
    } catch (e: Exception) { "ERROR(root write): ${e.message}" }

    /** 旧 toybox 沙盒（保留备用：Linux rootfs 损坏时兜底） */
    @Volatile private var curlEnsured = false
    suspend fun execSandbox(command: String): String {
        // 真 Termux 沙盒（bootstrap bash/apt），失败回退 busybox
        return runCatching {
            val ctx = ai.devcodex.agent.tools.BuiltinTools.appContext()
            // Ubuntu PRoot（Operit 式）：rootfs 就绪则优先完整 Linux 环境
            if (ai.devcodex.agent.shell.PRootUbuntu.ensure(ctx)) {
                return ai.devcodex.agent.shell.PRootUbuntu.exec(ctx, command)
            }
            // 次选 Termux 沙盒：首次自动装 curl/wget
            if (!curlEnsured) {
                curlEnsured = true
                runCatching { ai.devcodex.agent.shell.TermuxRuntime.exec(ctx, "command -v curl >/dev/null 2>&1 || (command -v apt >/dev/null 2>&1 && apt install -y curl wget 2>/dev/null) || true", timeoutSec = 120) }
            }
            ai.devcodex.agent.shell.TermuxRuntime.exec(ctx, command, timeoutSec = 150)
        }.getOrElse { localExec(command) }
    }

    suspend fun exec(command: String): String {
        // 1. Shizuku（ADB 权限，已授权则优先——修复"授权了却走 app 权限"）
        shizukuAwait()?.let { svc -> return runCatching { svc.exec(command) }.getOrElse { "ERROR(shizuku): ${it.message}" } }
        if (rootAvailable()) return rootExec(command)
        // 2. 内嵌 Termux 沙盒（app uid）
        val ctx = ai.devcodex.agent.tools.BuiltinTools.appContext()
        if (runCatching { ai.devcodex.agent.shell.TermuxRuntime.ensure(ctx) }.getOrDefault(false)) {
            return ai.devcodex.agent.shell.TermuxRuntime.exec(ctx, command)
        }
        // 3. 外部 Termux
        if (termuxAvailable()) return termuxExec(command)
        if (axsUrl.isNotBlank()) return axsExec(command)
        // 4. 本地 toybox 兜底（app uid 工作区）
        return localExec(command)
    }

    suspend fun readFile(path: String): String {
        shizukuAwait()?.let { svc -> return runCatching { svc.read(path) }.getOrElse { "ERROR: ${it.message}" } }
        return runCatching { File(path).readText() }.getOrElse { rootExec("cat \"$path\"") }
    }

    suspend fun writeFile(path: String, data: String): String {
        shizukuAwait()?.let { svc -> return runCatching { svc.write(path, data); "OK" }.getOrElse { "ERROR: ${it.message}" } }
        val ok = runCatching {
            File(path).parentFile?.mkdirs()
            File(path).writeText(data)
            true
        }.getOrDefault(false)
        return if (ok) "OK" else rootWrite(path, data)
    }

    suspend fun listDir(path: String): String {
        shizukuAwait()?.let { svc -> return runCatching { svc.list(path) }.getOrElse { "ERROR: ${it.message}" } }
        val f = File(path)
        return if (f.isDirectory) (f.list()?.joinToString("\n") ?: "(empty)")
        else rootExec("ls -la \"$path\"")
    }

    suspend fun delete(path: String): String {
        shizukuAwait()?.let { svc -> return runCatching { if (svc.delete(path)) "OK" else "ERROR: 删除失败" }.getOrElse { "ERROR: ${it.message}" } }
        return if (File(path).deleteRecursively()) "OK" else rootExec("rm -rf \"$path\"").ifBlank { "OK" }
    }
}
