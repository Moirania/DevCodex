package ai.devcodex.agent.tools

import ai.devcodex.agent.agent.ToolDef
import ai.devcodex.agent.shell.ShellAccess
import ai.devcodex.agent.util.JsonArg
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 文件操作进阶工具集：cp/mv/mkdir/rename/grep/find/chmod/tail/head/stat/wc（走 Shell 通道，工作区相对路径自动解析） */
object AdvancedFsTools {

    private fun obj(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)
    private fun req(vararg names: String) = buildJsonArray { names.forEach { add(JsonPrimitive(it)) } }

    private fun resolve(path: String): String =
        if (path.startsWith("/")) path
        else java.io.File(ai.devcodex.agent.tools.BuiltinTools.workspaceRoot(), path.removePrefix("./")).canonicalPath

    /** 文件工具执行通道：命令涉及 app 可读写路径（工作区/应用目录）→ localExec（app uid，工作区 cwd，不绕 Shizuku——shell uid 进不了 app 私有目录）；纯系统路径 → Shizuku ADB */
    private suspend fun fsExec(command: String): String {
        val wsRoot = ai.devcodex.agent.tools.BuiltinTools.workspaceRoot().canonicalPath
        val filesRoot = ai.devcodex.agent.tools.BuiltinTools.appContext().filesDir.canonicalPath
        return if (wsRoot in command || filesRoot in command) ai.devcodex.agent.shell.ShellAccess.localExec(command, 30)
        else ShellAccess.exec(command)
    }

    val fsCopy = ToolDef(
        name = "fs_copy",
        description = "复制文件或目录（递归）。相对路径=工作区",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("src", obj { put("type", "string") }); put("dst", obj { put("type", "string") })
            })
            put("required", req("src", "dst"))
        },
    ) { args ->
        val src = JsonArg.string(args, "src") ?: return@ToolDef "ERROR: 缺少 src"
        val dst = JsonArg.string(args, "dst") ?: return@ToolDef "ERROR: 缺少 dst"
        fsExec("cp -r \"${resolve(src)}\" \"${resolve(dst)}\"").ifBlank { "OK" }
    }

    val fsMove = ToolDef(
        name = "fs_move",
        description = "移动/剪切文件或目录。相对路径=工作区",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("src", obj { put("type", "string") }); put("dst", obj { put("type", "string") })
            })
            put("required", req("src", "dst"))
        },
    ) { args ->
        val src = JsonArg.string(args, "src") ?: return@ToolDef "ERROR: 缺少 src"
        val dst = JsonArg.string(args, "dst") ?: return@ToolDef "ERROR: 缺少 dst"
        fsExec("mv \"${resolve(src)}\" \"${resolve(dst)}\"").ifBlank { "OK" }
    }

    val fsMkdir = ToolDef(
        name = "fs_mkdir",
        description = "创建目录（递归）。相对路径=工作区",
        parameters = obj {
            put("type", "object"); put("properties", obj { put("path", obj { put("type", "string") }) })
            put("required", req("path"))
        },
    ) { args ->
        val path = JsonArg.string(args, "path") ?: return@ToolDef "ERROR: 缺少 path"
        fsExec("mkdir -p \"${resolve(path)}\"").ifBlank { "OK" }
    }

    val fsRename = ToolDef(
        name = "fs_rename",
        description = "重命名文件或目录",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("path", obj { put("type", "string") }); put("newName", obj { put("type", "string") }) })
            put("required", req("path", "newName"))
        },
    ) { args ->
        val path = JsonArg.string(args, "path") ?: return@ToolDef "ERROR: 缺少 path"
        val name = JsonArg.string(args, "newName") ?: return@ToolDef "ERROR: 缺少 newName"
        val dir = java.io.File(resolve(path)).parentFile?.absolutePath ?: "/sdcard"
        fsExec("mv \"${resolve(path)}\" \"$dir/$name\"").ifBlank { "OK" }
    }

    val fsGrep = ToolDef(
        name = "fs_grep",
        description = "在文件/目录中搜索文本内容（递归，含行号）。find 搜文件名，grep 搜内容",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("pattern", obj { put("type", "string"); put("description", "搜索文本（支持正则）") })
                put("path", obj { put("type", "string"); put("description", "文件或目录，默认工作区") })
            })
            put("required", req("pattern"))
        },
    ) { args ->
        val pattern = JsonArg.string(args, "pattern") ?: return@ToolDef "ERROR: 缺少 pattern"
        val path = JsonArg.string(args, "path")?.let { resolve(it) } ?: resolve("")
        fsExec("grep -rn --include='*' -e \"$pattern\" \"$path\" 2>/dev/null | head -100").ifBlank { "(无匹配)" }
    }

    val fsFind = ToolDef(
        name = "fs_find",
        description = "按名称查找文件（递归）。默认工作区，可指定起始目录",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("name", obj { put("type", "string"); put("description", "文件名关键字或通配符如 *.txt") })
                put("path", obj { put("type", "string"); put("description", "起始目录，默认工作区；/sdcard 搜全存储") })
            })
            put("required", req("name"))
        },
    ) { args ->
        val name = JsonArg.string(args, "name") ?: return@ToolDef "ERROR: 缺少 name"
        val path = JsonArg.string(args, "path")?.let { resolve(it) } ?: resolve("")
        fsExec("find \"$path\" -name \"$name\" 2>/dev/null | head -80").ifBlank { "(无匹配)" }
    }

    val fsChmod = ToolDef(
        name = "fs_chmod",
        description = "修改文件权限（如 644/755）。需 Shizuku/Root",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("path", obj { put("type", "string") }); put("mode", obj { put("type", "string"); put("description", "八进制权限，如 755") })
            })
            put("required", req("path", "mode"))
        },
        risk = ToolDef.Risk.MEDIUM,
    ) { args ->
        val path = JsonArg.string(args, "path") ?: return@ToolDef "ERROR: 缺少 path"
        val mode = JsonArg.string(args, "mode") ?: "644"
        fsExec("chmod $mode \"${resolve(path)}\"").ifBlank { "OK" }
    }

    val fsTail = ToolDef(
        name = "fs_tail",
        description = "读取文件末尾 N 行（日志常用）",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("path", obj { put("type", "string") }); put("lines", obj { put("type", "integer"); put("description", "行数，默认 50") })
            })
            put("required", req("path"))
        },
    ) { args ->
        val path = JsonArg.string(args, "path") ?: return@ToolDef "ERROR: 缺少 path"
        val n = JsonArg.int(args, "lines") ?: 50
        fsExec("tail -n $n \"${resolve(path)}\"").take(12000)
    }

    val fsHead = ToolDef(
        name = "fs_head",
        description = "读取文件开头 N 行",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("path", obj { put("type", "string") }); put("lines", obj { put("type", "integer") })
            })
            put("required", req("path"))
        },
    ) { args ->
        val path = JsonArg.string(args, "path") ?: return@ToolDef "ERROR: 缺少 path"
        val n = JsonArg.int(args, "lines") ?: 50
        fsExec("head -n $n \"${resolve(path)}\"").take(12000)
    }

    val fsStat = ToolDef(
        name = "fs_stat",
        description = "查看文件详细信息（大小/权限/修改时间）",
        parameters = obj {
            put("type", "object"); put("properties", obj { put("path", obj { put("type", "string") }) })
            put("required", req("path"))
        },
    ) { args ->
        val path = JsonArg.string(args, "path") ?: return@ToolDef "ERROR: 缺少 path"
        fsExec("ls -la \"${resolve(path)}\" 2>/dev/null || stat \"${resolve(path)}\"")
    }

    val fsWc = ToolDef(
        name = "fs_wc",
        description = "统计文件行数/字数/字节数",
        parameters = obj {
            put("type", "object"); put("properties", obj { put("path", obj { put("type", "string") }) })
            put("required", req("path"))
        },
    ) { args ->
        val path = JsonArg.string(args, "path") ?: return@ToolDef "ERROR: 缺少 path"
        fsExec("wc \"${resolve(path)}\"")
    }

    val all = listOf(fsCopy, fsMove, fsMkdir, fsRename, fsGrep, fsFind, fsChmod, fsTail, fsHead, fsStat, fsWc)
}
