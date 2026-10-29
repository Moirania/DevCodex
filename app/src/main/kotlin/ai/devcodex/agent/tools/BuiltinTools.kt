package ai.devcodex.agent.tools

import ai.devcodex.agent.agent.ToolDef
import ai.devcodex.agent.data.PluginManifest
import ai.devcodex.agent.data.PluginTool
import ai.devcodex.agent.plugin.PluginExecutor
import ai.devcodex.agent.service.DcAccessibilityService
import ai.devcodex.agent.shell.ShellAccess
import ai.devcodex.agent.shell.SshClient
import android.content.Context
import android.os.Build
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** 内置工具集 + 插件工具装配 */
object BuiltinTools {

    private lateinit var appContext: Context
    fun init(ctx: Context) { appContext = ctx.applicationContext }
    fun appContext(): Context = appContext

    /** 应用内页面导航回调（ChatScreen 注册：AI 可打开终端/设置等页面） */
    @Volatile
    var navigateCallback: ((String) -> Unit)? = null

    private fun obj(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): kotlinx.serialization.json.JsonObject = buildJsonObject(block)

    // ============ 内存文件读写工具 ============
    val fsRead = ToolDef(
        name = "fs_read",
        description = "读取手机本地文本文件。相对路径（如 \"notes.txt\" 或 \"src/Main.kt\"）= 工作区文件（用户上传的文件都在工作区）；绝对路径 = 系统路径（沙箱外需 Shizuku/Root 授权，如 /sdcard/xxx.txt）",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("path", obj { put("type", "string"); put("description", "文件绝对路径") })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("path")) })
        },
    ) { args ->
        val path = ai.devcodex.agent.util.JsonArg.string(args, "path") ?: return@ToolDef "ERROR: 缺少 path 参数"
        val resolved = resolvePath(path)
        ShellAccess.readFile(resolved).take(20000)
    }

    val fsWrite = ToolDef(
        name = "fs_write",
        description = "写入文本文件（覆盖）。相对路径 = 工作区（免确认直接写）；绝对路径 = 系统路径（沙箱外需 Shizuku/Root + 用户确认）",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("path", obj { put("type", "string"); put("description", "目标文件绝对路径") })
                put("content", obj { put("type", "string"); put("description", "写入的文本内容") })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("path")); add(kotlinx.serialization.json.JsonPrimitive("content"))
            })
        },
        risk = ToolDef.Risk.MEDIUM,
    ) { args ->
        val path = ai.devcodex.agent.util.JsonArg.string(args, "path") ?: return@ToolDef "ERROR: 缺少 path"
        val content = ai.devcodex.agent.util.JsonArg.string(args, "content") ?: ""
        val resolved = resolvePath(path)
        // 工作区内或沙箱内 → 免审批直写；其他路径走 ShellAccess（需授权/审批）
        if (resolved.startsWith(appContext.filesDir.canonicalPath)) {
            runCatching {
                java.io.File(resolved).parentFile?.mkdirs()
                java.io.File(resolved).writeText(content)
            }.fold(onSuccess = { "OK（工作区: ${java.io.File(resolved).relativeTo(java.io.File(appContext.filesDir, "workspace")).path}）" },
                   onFailure = { "ERROR: ${it.message}" })
        } else ShellAccess.writeFile(resolved, content)
    }

    val fsList = ToolDef(
        name = "fs_list",
        description = "列目录。相对路径或空 = 工作区；绝对路径 = 系统目录（沙箱外需授权）",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("path", obj { put("type", "string"); put("description", "目录绝对路径") }) })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("path")) })
        },
    ) { args ->
        val path = ai.devcodex.agent.util.JsonArg.string(args, "path") ?: return@ToolDef "ERROR: 缺少 path"
        ShellAccess.listDir(resolvePath(path)).take(8000)
    }

    val fsDelete = ToolDef(
        name = "fs_delete",
        description = "删除文件或目录（递归）。危险操作，每次需用户确认",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("path", obj { put("type", "string"); put("description", "要删除的绝对路径") }) })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("path")) })
        },
        risk = ToolDef.Risk.HIGH,
    ) { args ->
        val path = ai.devcodex.agent.util.JsonArg.string(args, "path") ?: return@ToolDef "ERROR: 缺少 path"
        ShellAccess.delete(path)
    }

    // ============ Shell ============
    val appOpen = ToolDef(
        name = "app_open",
        description = "打开应用内页面。当用户要求打开终端/设置/统计/插件时用这个（不要用 shell 启动 Termux 等外部应用）。page 可选: terminal(应用内终端), settings(设置), stats(使用统计), plugins(插件), providers(提供商)",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("page", obj { put("type", "string"); put("enum", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("terminal"))
                    add(kotlinx.serialization.json.JsonPrimitive("settings"))
                    add(kotlinx.serialization.json.JsonPrimitive("stats"))
                    add(kotlinx.serialization.json.JsonPrimitive("plugins"))
                    add(kotlinx.serialization.json.JsonPrimitive("providers"))
                }) })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("page")) })
        },
        risk = ToolDef.Risk.LOW,
    ) { args ->
        val page = ai.devcodex.agent.util.JsonArg.string(args, "page") ?: return@ToolDef "ERROR: 缺少 page"
        val valid = listOf("terminal", "settings", "stats", "plugins", "providers")
        if (page !in valid) return@ToolDef "ERROR: page 须为 ${valid.joinToString("/")}"
        navigateCallback?.invoke(page) ?: return@ToolDef "ERROR: 界面未就绪"
        "OK: 已打开 $page"
    }

    val sshExec = ToolDef(
        name = "ssh_exec",
        description = "通过 SSH 在远程服务器上执行命令（需在设置→SSH 隧道配置）。适合运维、远程文件、跑服务端脚本",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("command", obj { put("type", "string"); put("description", "远程 shell 命令") })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("command")) })
        },
        risk = ToolDef.Risk.HIGH,
    ) { args ->
        val cmd = ai.devcodex.agent.util.JsonArg.string(args, "command") ?: return@ToolDef "ERROR: 缺少 command"
        SshClient.exec(cmd)
    }

    val sshForward = ToolDef(
        name = "ssh_forward",
        description = "建立 SSH 本地端口转发隧道（把远程内网服务映射到 127.0.0.1）。local_port=本地端口, remote_host/remote_port=远端目标（如内网 LLM API）",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("local_port", obj { put("type", "integer") })
                put("remote_host", obj { put("type", "string") })
                put("remote_port", obj { put("type", "integer") })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("local_port")); add(kotlinx.serialization.json.JsonPrimitive("remote_host")); add(kotlinx.serialization.json.JsonPrimitive("remote_port")) })
        },
        risk = ToolDef.Risk.LOW,
    ) { args ->
        val lp = ai.devcodex.agent.util.JsonArg.int(args, "local_port") ?: return@ToolDef "ERROR: 缺少 local_port"
        val rh = ai.devcodex.agent.util.JsonArg.string(args, "remote_host") ?: return@ToolDef "ERROR: 缺少 remote_host"
        val rp = ai.devcodex.agent.util.JsonArg.int(args, "remote_port") ?: return@ToolDef "ERROR: 缺少 remote_port"
        SshClient.forward(lp, rh, rp)
    }

    val linuxExec = ToolDef(
        name = "linux_exec", risk = ToolDef.Risk.HIGH,
        description = "Termux 原生沙盒执行（busybox sh 完整工具链）。默认工作目录=应用工作区（相对路径直接读写），支持 cwd 指定工作目录",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("command", obj { put("type", "string"); put("description", "要执行的 shell 命令") })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("command")) })
        },
    ) { args ->
        val cmd = ai.devcodex.agent.util.JsonArg.string(args, "command") ?: return@ToolDef "ERROR: 缺少 command"
        ShellAccess.linuxExec(cmd).take(30000)
    }

    val shellExec = ToolDef(
        name = "shell_exec",
        description = "执行 shell 命令（Shizuku 提供ADB权限，或Root）。可执行 pm/am/ls/cat 等任意命令。危险操作，每次需用户确认（可选择本会话内记住）",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("command", obj { put("type", "string"); put("description", "要执行的命令") })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("command")) })
        },
        risk = ToolDef.Risk.HIGH,
    ) { args ->
        val cmd = ai.devcodex.agent.util.JsonArg.string(args, "command") ?: return@ToolDef "ERROR: 缺少 command"
        ShellAccess.exec(cmd).take(20000)
    }

    // ============ HTTP ============
    val httpRequest = ToolDef(
        name = "http_request",
        risk = ToolDef.Risk.MEDIUM,
        description = "发起 HTTP 请求（GET/POST/PUT/DELETE），可请求任意 URL，用于查资料、调用 web API",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("method", obj { put("type", "string"); put("enum", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("GET")); add(kotlinx.serialization.json.JsonPrimitive("POST")); add(kotlinx.serialization.json.JsonPrimitive("PUT")); add(kotlinx.serialization.json.JsonPrimitive("DELETE"))
                }) })
                put("url", obj { put("type", "string") })
                put("headers", obj { put("type", "object"); put("description", "可选请求头") })
                put("body", obj { put("type", "string"); put("description", "可选请求体") })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("url")) })
        },
    ) { args ->
        ai.devcodex.agent.plugin.PluginExecutor.executeRaw(args)
    }

    // ============ 记忆 ============
    private val MEMORY_KEY_REGEX = Regex("[A-Za-z0-9._一-龥-]{1,64}")
    private fun isSafeMemoryKey(key: String): Boolean = key.matches(MEMORY_KEY_REGEX) && key != "." && key != ".."

    val memorySave = ToolDef(
        name = "memory_save",
        description = "把重要信息保存到长期记忆（跨会话保留），键值对形式",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("key", obj { put("type", "string") })
                put("value", obj { put("type", "string") })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("key")); add(kotlinx.serialization.json.JsonPrimitive("value")) })
        },
    ) { args ->
        val key = ai.devcodex.agent.util.JsonArg.string(args, "key") ?: "general"
        val value = ai.devcodex.agent.util.JsonArg.string(args, "value") ?: ""
        // 路径穿越防护：键只允许安全文件名字符
        if (!isSafeMemoryKey(key)) return@ToolDef "ERROR: 记忆键只能包含字母、数字、中文、点、下划线和连字符，长度不超过 64"
        if (value.length > 200_000) return@ToolDef "ERROR: 记忆内容过长"
        val dir = File(appContext.filesDir, "memory").apply { mkdirs() }
        File(dir, "$key.md").writeText(value)
        "已保存到记忆: $key"
    }

    val memoryRead = ToolDef(
        name = "memory_read",
        description = "读取长期记忆（列出全部或读指定键）",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("key", obj { put("type", "string"); put("description", "要读的键，留空则列出全部键") })
            })
        },
    ) { args ->
        val dir = File(appContext.filesDir, "memory")
        val key = ai.devcodex.agent.util.JsonArg.string(args, "key")
        if (key.isNullOrBlank()) {
            dir.listFiles()?.joinToString("\n") { it.nameWithoutExtension } ?: "(记忆为空)"
        } else if (!isSafeMemoryKey(key)) {
            "ERROR: 记忆键只能包含字母、数字、中文、点、下划线和连字符，长度不超过 64"
        } else {
            File(dir, "$key.md").takeIf { it.exists() }?.readText()?.take(8000) ?: "(无此记忆: $key)"
        }
    }

    // ============ 设备 ============
    val appList = ToolDef(
        name = "app_list",
        description = "列出手机上已安装的应用（包名+名称）",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("keyword", obj { put("type", "string"); put("description", "可选过滤关键字") })
            })
        },
    ) { args ->
        val kw = ai.devcodex.agent.util.JsonArg.string(args, "keyword")?.lowercase()
        val pm = appContext.packageManager
        pm.getInstalledPackages(0)
            .mapNotNull { pi -> (pi.applicationInfo?.loadLabel(pm)?.toString() ?: "") to pi.packageName }
            .filter { kw.isNullOrBlank() || it.second.contains(kw, true) || it.first.lowercase().contains(kw) }
            .take(300)
            .joinToString("\n") { "${it.second} (${it.first})" }
    }

    val deviceInfo = ToolDef(
        name = "device_info",
        description = "获取设备信息（型号/系统/屏幕/权限状态等）",
        parameters = obj { put("type", "object"); put("properties", obj {}) },
    ) { _ ->
        buildString {
            appendLine("型号: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Shell通道: ${ShellAccess.availableMode()}")
            appendLine("无障碍: ${if (DcAccessibilityService.isEnabled()) "已启用" else "未启用"}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        }
    }

    // ============ UI 自动化（无障碍） ============
    val uiSnapshot = ToolDef(
        name = "ui_snapshot",
        description = "读取当前屏幕的文本内容快照（需要无障碍服务），返回界面元素及其坐标",
        parameters = obj { put("type", "object"); put("properties", obj {}) },
    ) { _ -> DcAccessibilityService.snapshot() }

    val uiTap = ToolDef(
        name = "ui_tap",
        description = "点击屏幕指定坐标（需要无障碍服务）",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("x", obj { put("type", "integer") })
                put("y", obj { put("type", "integer") })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("x")); add(kotlinx.serialization.json.JsonPrimitive("y")) })
        },
        risk = ToolDef.Risk.MEDIUM,
    ) { args ->
        val x = ai.devcodex.agent.util.JsonArg.int(args, "x") ?: return@ToolDef "ERROR: 缺少 x"
        val y = ai.devcodex.agent.util.JsonArg.int(args, "y") ?: return@ToolDef "ERROR: 缺少 y"
        if (DcAccessibilityService.tap(x.toFloat(), y.toFloat())) "已点击 ($x,$y)" else "ERROR: 无障碍服务未启用"
    }

    val uiBack = ToolDef(
        name = "ui_back", description = "按返回键",
        parameters = obj { put("type", "object"); put("properties", obj {}) },
    ) { _ -> if (DcAccessibilityService.back()) "OK" else "ERROR: 未启用" }

    val uiHome = ToolDef(
        name = "ui_home", description = "回到桌面",
        parameters = obj { put("type", "object"); put("properties", obj {}) },
    ) { _ -> if (DcAccessibilityService.home()) "OK" else "ERROR: 未启用" }

    val uiScroll = ToolDef(
        name = "ui_scroll", description = "滑动屏幕",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("down", obj { put("type", "boolean"); put("description", "true=下滑 false=上滑") }) })
        },
    ) { args ->
        val down = ai.devcodex.agent.util.JsonArg.bool(args, "down") ?: true
        if (DcAccessibilityService.scroll(down)) "OK" else "ERROR: 未启用"
    }

    val notify = ToolDef(
        name = "notify",
        description = "发送系统通知（用于任务完成提醒等）",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("title", obj { put("type", "string") })
                put("text", obj { put("type", "string") })
            })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("title")) })
        },
    ) { args ->
        val title = ai.devcodex.agent.util.JsonArg.string(args, "title") ?: "DevCodex"
        val text = ai.devcodex.agent.util.JsonArg.string(args, "text") ?: ""
        ai.devcodex.agent.util.Notify.post(appContext, title, text)
        "已发送通知"
    }

    /** 相对路径 → 工作区绝对路径（尊重自定义工作区）；绝对路径原样返回 */
    private fun resolvePath(path: String): String {
        if (path.startsWith("/")) return path
        val ws = workspaceRoot()
        return java.io.File(ws, path.removePrefix("./")).canonicalPath
    }

    /** 当前工作区根（自定义目录或应用私有） */
    fun workspaceRoot(): java.io.File {
        val custom = ai.devcodex.agent.data.WorkspaceConfig.dir
        val ws = if (custom.isNotBlank()) java.io.File(custom) else java.io.File(appContext.filesDir, "workspace")
        ws.mkdirs()
        return ws
    }

    // ============ 技能读取 ============
    val skillRead = ToolDef(
        name = "skill_read",
        description = "读取已安装技能（Skill）的完整说明文档。先用 skill_list 看有哪些技能，需要某技能的详细用法时调用此工具",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("name", obj { put("type", "string"); put("description", "技能名") }) })
            put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("name")) })
        },
    ) { args ->
        val name = ai.devcodex.agent.util.JsonArg.string(args, "name") ?: return@ToolDef "ERROR: 缺少 name"
        val repo = ai.devcodex.agent.data.SkillRepo(appContext)
        val sk = repo.list().find { it.name.equals(name, true) && it.enabled }
            ?: return@ToolDef "ERROR: 未安装或未启用的技能: $name（用 skill_list 查看）"
        "技能 ${sk.name}：\n${sk.content.take(20000)}"
    }

    val skillList = ToolDef(
        name = "skill_list",
        description = "列出已安装的技能（名称+简介）",
        parameters = obj { put("type", "object"); put("properties", obj {}) },
    ) { _ ->
        val repo = ai.devcodex.agent.data.SkillRepo(appContext)
        val all = repo.list().filter { it.enabled }
        if (all.isEmpty()) "(无已安装技能)" else all.joinToString("\n") { "${it.name}: ${it.description.take(100)}" }
    }

    val all: List<ToolDef> = listOf(
        fsRead, fsWrite, fsList, fsDelete, shellExec, httpRequest, appOpen, linuxExec, sshExec, sshForward,
        memorySave, memoryRead, appList, deviceInfo,
        uiSnapshot, uiTap, uiBack, uiHome, uiScroll, notify,
        skillRead, skillList,
    )

    /** 装配插件工具 */
    fun pluginTools(manifests: List<PluginManifest>): List<ToolDef> =
        manifests.filter { it.enabled }.flatMap { m ->
            m.tools.map { t: PluginTool -> PluginExecutor.toToolDef(t) }
        }
}
