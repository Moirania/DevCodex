package ai.devcodex.agent.tools

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.CalendarContract
import ai.devcodex.agent.agent.ToolDef
import ai.devcodex.agent.util.JsonArg
import ai.devcodex.agent.shell.ShellAccess
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import java.net.URL
import java.net.URLEncoder

/**
 * 拓展工具集（11 个）：
 * web_search / browser_render / image_info / image_convert / pipeline_run /
 * bg_list / bg_kill / calendar_list / calendar_add / clipboard_write / notify_post
 * 日历与通知需要运行时权限（WRITE_CALENDAR / POST_NOTIFICATIONS）
 */
object ExtraTools {

    private fun ctx(): Context = BuiltinTools.appContext()

    private fun obj(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject(block)

    private fun req(vararg names: String) = kotlinx.serialization.json.buildJsonArray {
        names.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
    }

    private fun resolveW(path: String): String {
        val p = path.removePrefix("./")
        return if (p.startsWith("/")) p else java.io.File(BuiltinTools.workspaceRoot(), p).absolutePath
    }

    // ============ 1. Web 搜索（DuckDuckGo HTML 免 key） ============
    val webSearch = ToolDef(
        name = "web_search",
        description = "联网搜索。参数 query(必填)。返回标题/URL/摘要（DuckDuckGo）。回答时事、新知识、版本号等需要联网信息时的首选工具",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("query", obj { put("type", "string") }) })
            put("required", req("query"))
        },
    ) { args ->
        val q = JsonArg.string(args, "query") ?: return@ToolDef "ERROR: 缺少 query"
        runCatching {
            val enc = URLEncoder.encode(q, "UTF-8")
            val html = URL("https://html.duckduckgo.com/html/?q=$enc").openStream()
                .bufferedReader().use { it.readText() }.take(400_000)
            val results = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
                .findAll(html).take(8).mapIndexed { i, m ->
                    val href = m.groupValues[1].replace(Regex("^//duckduckgo\\.com/l/\\?uddg="), "")
                        .let { java.net.URLDecoder.decode(it.substringBefore("&rut="), "UTF-8") }
                    "【${i + 1}】${m.groupValues[2].replace(Regex("<[^>]+>"), "")}\n  $href"
                }.joinToString("\n")
            results.ifBlank { "（无结果，换关键词试试）" }
        }.getOrElse { "ERROR: ${it.message}" }
    }

    // ============ APK 构建（Termux 工具链：aapt2+javac+d8+apksigner） ============
    private val apkBuild: ToolDef = ToolDef(
        name = "apk_build",
        risk = ToolDef.Risk.MEDIUM,
        description = "在工作区内构建 Android APK（Termux 工具链，缺件自动 pkg 安装）。参数 project_dir(必填，含 AndroidManifest.xml 与 java/res)。产出签名 output.apk。适合 Xposed/LSP 模块、小工具 app。",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("project_dir", obj { put("type", "string"); put("description", "工程目录（工作区相对路径）") })
                put("keystore", obj { put("type", "string"); put("description", "可选签名密钥库，默认自动生成 debug 签名") })
            })
            put("required", req("project_dir"))
        },
    ) { args ->
        val dir = ai.devcodex.agent.util.JsonArg.string(args, "project_dir") ?: return@ToolDef "ERROR: 缺少 project_dir"
        val ks = ai.devcodex.agent.util.JsonArg.string(args, "keystore") ?: ""
        val cmd = buildString {
            append("cd \"\$HOME/workspace/").append(dir.trim('/')).append("\" && ")
            append("(command -v aapt2 >/dev/null 2>&1 || pkg install -y aapt openjdk-17 d8-android-tools apksigner >/dev/null 2>&1 || true) && ")
            append("mkdir -p build && aapt2 compile --dir res -o build/res.zip 2>/dev/null; ")
            append("aapt2 link -o build/base.apk --manifest AndroidManifest.xml build/res.zip 2>/dev/null || aapt2 link -o build/base.apk --manifest AndroidManifest.xml; ")
            append("find java -name '*.java' > build/sources.txt 2>/dev/null || find . -name '*.java' > build/sources.txt; ")
            append("javac -classpath build/base.apk -d build/classes @build/sources.txt 2>&1 | head -30; ")
            append("(cd build/classes && d8 \$(find . -name '*.class') --output ../dexout 2>/dev/null) && ")
            append("cp build/dexout/*.dex build/ 2>/dev/null; (cd build && zip -j base.apk classes*.dex dexout/*.dex -q 2>/dev/null || zip -j base.apk *.dex -q); ")
            if (ks.isNotBlank()) append("apksigner sign --ks \"").append(ks).append("\" --out output.apk base.apk && echo APK_SIGNED; ")
            else append("(keytool -genkeypair -keystore build/debug.ks -storepass debugpass -keypass debugpass -alias debug -dname CN=DevCodex -keyalg RSA -validity 10000 2>/dev/null; apksigner sign --ks build/debug.ks --pass pass:debugpass --key-pass pass:debugpass --out output.apk base.apk) && echo APK_SIGNED; ")
            append("ls -la output.apk 2>/dev/null || echo BUILD_FAILED_CHECK_LOG")
        }
        ai.devcodex.agent.shell.ShellAccess.execSandbox(cmd).take(2500)
    }

    // ============ native 构建（Termux clang：.so/.ko） ============
    private val nativeBuild: ToolDef = ToolDef(
        name = "native_build",
        risk = ToolDef.Risk.MEDIUM,
        description = "用 Termux clang 编译 native 产物。参数 src_dir(必填)、out(默认 out)、type(so|ko|o，默认 so)、kernel_dir(编 .ko 必填：内核源码树路径)。.so 直接可编；.ko 需提供匹配设备内核的源码树（无则返回说明）。",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("src_dir", obj { put("type", "string") })
                put("out", obj { put("type", "string") })
                put("type", obj { put("type", "string") })
                put("kernel_dir", obj { put("type", "string") })
            })
            put("required", req("src_dir"))
        },
    ) { args ->
        val dir = ai.devcodex.agent.util.JsonArg.string(args, "src_dir") ?: return@ToolDef "ERROR: 缺少 src_dir"
        val out = ai.devcodex.agent.util.JsonArg.string(args, "out") ?: "out"
        val type = ai.devcodex.agent.util.JsonArg.string(args, "type") ?: "so"
        val kdir = ai.devcodex.agent.util.JsonArg.string(args, "kernel_dir") ?: ""
        val cmd = when (type) {
            "ko" -> if (kdir.isBlank()) "echo 'ERROR: 编 .ko 需要 kernel_dir（设备内核源码树）。'"
            else "cd \"\$HOME/workspace/" + dir.trim('/') + "\" && clang -target aarch64-linux-android -I\"" + kdir + "/include\" -I\"" + kdir + "/arch/arm64/include\" -DKERNEL -c *.c -o ../" + out + ".o && ld -r -o ../" + out + ".ko ../" + out + ".o && echo KO_OK"
            else -> "cd \"\$HOME/workspace/" + dir.trim('/') + "\" && clang -target aarch64-linux-android21 -shared -fPIC *.c -o ../" + out + ".so && echo SO_OK"
        }
        ai.devcodex.agent.shell.ShellAccess.execSandbox(cmd).take(2000)
    }

    // ============ JVM 跨平台构建（javac+jar） ============
    private val jarBuild: ToolDef = ToolDef(
        name = "jar_build",
        risk = ToolDef.Risk.LOW,
        description = "跨平台 JVM 构建：javac 编译 + jar 打包（产物可在任意 JVM 运行）。参数 src_dir(必填)、out(默认 app.jar)、main(主类全名，可选)。",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("src_dir", obj { put("type", "string") })
                put("out", obj { put("type", "string") })
                put("main", obj { put("type", "string") })
            })
            put("required", req("src_dir"))
        },
    ) { args ->
        val dir = ai.devcodex.agent.util.JsonArg.string(args, "src_dir") ?: return@ToolDef "ERROR: 缺少 src_dir"
        val out = ai.devcodex.agent.util.JsonArg.string(args, "out") ?: "app.jar"
        val main = ai.devcodex.agent.util.JsonArg.string(args, "main") ?: ""
        val fixed = if (main.isBlank())
            "cd \"\$HOME/workspace/" + dir.trim('/') + "\" && find . -name '*.java' > srcs.txt && javac -d classes @srcs.txt && (cd classes && jar cf ../../" + out + " .) && ls -la ../../" + out + " && echo JAR_OK"
        else
            "cd \"\$HOME/workspace/" + dir.trim('/') + "\" && find . -name '*.java' > srcs.txt && javac -d classes @srcs.txt && jar cfe ../../" + out + " " + main + " -C classes . && ls -la ../../" + out + " && echo JAR_OK"
        ai.devcodex.agent.shell.ShellAccess.execSandbox(fixed).take(2000)
    }


    // ============ 2. 浏览器渲染（WebView 离屏执行 JS 取文本） ============
    val browserRender = ToolDef(
        name = "browser_render",
        description = "无头浏览器渲染网页（执行 JS 后提取正文文本，可截图）。参数 url(必填)、timeout_sec(默认12)、screenshot(可选，true 时保存整页截图到工作区并返回路径)。",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("url", obj { put("type", "string") })
                put("timeout_sec", obj { put("type", "integer") })
                put("screenshot", obj { put("type", "boolean") })
            })
            put("required", req("url"))
        },
        risk = ToolDef.Risk.LOW,
    ) { args ->
        val url = JsonArg.string(args, "url") ?: return@ToolDef "ERROR: 缺少 url"
        val timeout = (JsonArg.int(args, "timeout_sec") ?: 12) * 1000L
        val wantShot = args.contains("screenshot") && args.contains("true")
        val latch = CountDownLatch(1)
        var text = ""
        var title = ""
        var shotPath = ""
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                val wv = android.webkit.WebView(ctx())
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, t: String?) {
                        view?.evaluateJavascript(
                            "(function(){return document.title+'\\n\\n'+document.body.innerText.substring(0,20000)})()"
                        ) { r ->
                            text = r?.trim('"')?.replace("\\n", "\n") ?: ""
                            title = t ?: ""
                            if (wantShot) {
                                runCatching {
                                    view?.measure(
                                        android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                                        android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
                                    )
                                    view?.layout(0, 0, view?.measuredWidth ?: 1080, view?.measuredHeight ?: 1000)
                                    val b = android.graphics.Bitmap.createBitmap(view?.measuredWidth ?: 1080, view?.measuredHeight ?: 1000, android.graphics.Bitmap.Config.ARGB_8888)
                                    view?.draw(android.graphics.Canvas(b))
                                    val f = java.io.File(ai.devcodex.agent.tools.BuiltinTools.appContext().filesDir, "workspace/shot_" + System.currentTimeMillis() + ".png")
                                    f.parentFile?.mkdirs()
                                    f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                                    shotPath = f.absolutePath
                                }
                            }
                            latch.countDown()
                            view?.destroy()
                        }
                    }
                }
                wv.loadUrl(url)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (latch.count > 0L) latch.countDown()
                }, timeout)
            }
        }
        latch.await(timeout + 3000L, TimeUnit.MILLISECONDS)
        val base = text.ifBlank { "ERROR: 页面加载超时或无文本" }.let { "[$title]\n$it" }
        if (shotPath.isNotBlank()) base + "\n[截图已保存: $shotPath]" else base
    }

    // ============ 3. 图像信息 ============
    val imageInfo = ToolDef(
        name = "image_info",
        description = "读取图片信息（尺寸/格式/大小）。参数 path(必填，工作区或沙箱外绝对路径)",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("path", obj { put("type", "string") }) })
            put("required", req("path"))
        },
    ) { args ->
        val p = JsonArg.string(args, "path") ?: return@ToolDef "ERROR: 缺少 path"
        val f = File(resolveW(p))
        if (!f.isFile) return@ToolDef "ERROR: 文件不存在 $p"
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        "文件: ${f.name}\n尺寸: ${opts.outWidth}x${opts.outHeight}\n格式: ${opts.outMimeType ?: "?"}\n大小: ${f.length() / 1024} KB\n路径: ${f.absolutePath}"
    }

    // ============ 4. 图像转换（缩放/压缩/webp/png/jpg） ============
    val imageConvert = ToolDef(
        name = "image_convert",
        description = "图像处理：缩放/压缩/转格式。参数 path(必填)、max_width(可选)、quality(可选1-100)、format(可选 webp/png/jpeg，默认 webp)。输出到工作区 converted/",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("path", obj { put("type", "string") })
                put("max_width", obj { put("type", "integer") })
                put("quality", obj { put("type", "integer") })
                put("format", obj { put("type", "string") })
            })
            put("required", req("path"))
        },
    ) { args ->
        val p = JsonArg.string(args, "path") ?: return@ToolDef "ERROR: 缺少 path"
        val src = File(resolveW(p))
        if (!src.isFile) return@ToolDef "ERROR: 文件不存在 $p"
        val maxW = JsonArg.int(args, "max_width") ?: 0
        val quality = JsonArg.int(args, "quality") ?: 85
        val fmt = JsonArg.string(args, "format")?.lowercase() ?: "webp"
        runCatching {
            val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: error("解码失败")
            val scaled = if (maxW > 0 && bmp.width > maxW) {
                Bitmap.createScaledBitmap(bmp, maxW, maxW * bmp.height / bmp.width, true)
            } else bmp
            val outDir = File(BuiltinTools.workspaceRoot(), "converted").apply { mkdirs() }
            val outFile = File(outDir, src.nameWithoutExtension + "." + fmt)
            val compressFmt = when (fmt) {
                "png" -> Bitmap.CompressFormat.PNG
                "jpeg", "jpg" -> Bitmap.CompressFormat.JPEG
                else -> Bitmap.CompressFormat.WEBP
            }
            outFile.outputStream().use { scaled.compress(compressFmt, quality, it) }
            "OK: ${outFile.absolutePath}（${scaled.width}x${scaled.height}，${outFile.length() / 1024} KB）"
        }.getOrElse { "ERROR: ${it.message}" }
    }

    // ============ 5. 管道命令（Termux 原生，支持 | 管道链） ============
    val pipelineRun = ToolDef(
        name = "pipeline_run",
        description = "在 Termux 原生环境跑管道链式命令（如 ls -la | grep .kt | wc -l）。参数 command(必填)、timeout_sec(默认30)",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("command", obj { put("type", "string") })
                put("timeout_sec", obj { put("type", "integer") })
            })
            put("required", req("command"))
        },
        risk = ToolDef.Risk.MEDIUM,
    ) { args ->
        val cmd = JsonArg.string(args, "command") ?: return@ToolDef "ERROR: 缺少 command"
        val to = (JsonArg.int(args, "timeout_sec") ?: 30).toLong()
        ai.devcodex.agent.shell.ShellAccess.linuxExec(cmd).take(30000)
    }

    // ============ 6. 后台进程列表（同 uid） ============
    val bgList = ToolDef(
        name = "bg_list",
        description = "列出本应用后台子进程（pid/名称/命令），无参数",
        parameters = obj { put("type", "object"); put("properties", obj { }) },
    ) {
        runCatching {
            File("/proc").listFiles()?.filter { it.name.toIntOrNull() != null }
                ?.mapNotNull { pidDir ->
                    val comm = runCatching { File(pidDir, "comm").readText().trim() }.getOrNull()
                    val cmd = runCatching { File(pidDir, "cmdline").readText().replace('\u0000', ' ').trim() }.getOrNull()
                    if (comm != null) "$pidDir.name $comm ${cmd?.take(80) ?: ""}" else null
                }?.joinToString("\n") ?: "(空)"
        }.getOrElse { "ERROR: ${it.message}" }.take(10000)
    }

    // ============ 7. 后台进程终止 ============
    val bgKill = ToolDef(
        name = "bg_kill",
        description = "终止本应用的后台进程。参数 pid(必填)。先 bg_list 查看",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("pid", obj { put("type", "integer") }) })
            put("required", req("pid"))
        },
        risk = ToolDef.Risk.MEDIUM,
    ) { args ->
        val pid = JsonArg.int(args, "pid") ?: return@ToolDef "ERROR: 缺少 pid"
        runCatching {
            val p = ProcessBuilder("kill", "-9", pid.toString()).start()
            p.waitFor()
            "OK: kill $pid"
        }.getOrElse { "ERROR: ${it.message}（toybox 通道用 pipeline_run kill）" }
    }

    // ============ 8. 日历事件读取 ============
    val calendarList = ToolDef(
        name = "calendar_list",
        description = "读取系统日历最近/未来日程。参数 days(可选默认14)。需要日历读取权限",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("days", obj { put("type", "integer") }) })
        },
    ) { args ->
        val days = JsonArg.int(args, "days") ?: 14
        runCatching {
            val start = System.currentTimeMillis()
            val end = start + days * 86400_000L
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(start.toString()).appendPath(end.toString()).build()
            val out = StringBuilder()
            ctx().contentResolver.query(uri, arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            ), null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && out.length < 15000) {
                    out.append("[${java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(c.getLong(2)))}] ")
                        .append(c.getString(0) ?: "(无标题)")
                        .append("（${c.getString(3) ?: "?"}）")
                        .append(c.getString(1)?.let { "\n  $it" } ?: "").append("\n")
                }
            }
            out.toString().ifBlank { "（该时段无日程）" }
        }.getOrElse { "ERROR: ${it.message}（需日历权限：设置→应用→权限）" }
    }

    // ============ 9. 日历事件创建 ============
    val calendarAdd = ToolDef(
        name = "calendar_add",
        description = "添加日历日程。参数 title(必填)、begin_ms(必填,毫秒时间戳)或 begin_rfc(ISO时间)、duration_min(默认60)、description(可选)。需要日历写入权限",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("title", obj { put("type", "string") })
                put("begin_ms", obj { put("type", "integer") })
                put("begin_rfc", obj { put("type", "string"); put("description", "如 2026-10-22T09:00") })
                put("duration_min", obj { put("type", "integer") })
                put("description", obj { put("type", "string") })
            })
            put("required", req("title"))
        },
        risk = ToolDef.Risk.MEDIUM,
    ) { args ->
        val title = JsonArg.string(args, "title") ?: return@ToolDef "ERROR: 缺少 title"
        val begin = JsonArg.int(args, "begin_ms")?.let { it.toLong() }
            ?: JsonArg.string(args, "begin_rfc")?.let { s ->
                runCatching { java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
            }
            ?: return@ToolDef "ERROR: 缺少 begin_ms 或 begin_rfc"
        val dur = JsonArg.int(args, "duration_min") ?: 60
        runCatching {
            val cal = ctx().contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID), null, null, null
            )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: error("无可用日历账户（先在系统日历 app 登录账户）")
            val cv = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, cal)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, JsonArg.string(args, "description") ?: "DevCodex AI")
                put(CalendarContract.Events.DTSTART, begin)
                put(CalendarContract.Events.DTEND, begin + dur * 60_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
            ctx().contentResolver.insert(CalendarContract.Events.CONTENT_URI, cv)
            "OK: 日程已添加「$title」"
        }.getOrElse { "ERROR: ${it.message}（需日历写入权限）" }
    }

    // ============ 10. 剪贴板写入 ============
    val clipboardWrite = ToolDef(
        name = "clipboard_write",
        description = "写剪贴板。参数 text(必填)。给用户准备可直接粘贴的内容时用",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("text", obj { put("type", "string") }) })
            put("required", req("text"))
        },
    ) { args ->
        val text = JsonArg.string(args, "text") ?: return@ToolDef "ERROR: 缺少 text"
        runCatching {
            val cm = ctx().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("DevCodex", text))
            "OK: 已复制 ${text.length} 字符"
        }.getOrElse { "ERROR: ${it.message}" }
    }

    // ============ 11. 发通知 ============
    val notifyPost = ToolDef(
        name = "notify_post",
        description = "发送系统通知。参数 title(必填)、text(可选)。任务完成提醒",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("title", obj { put("type", "string") })
                put("text", obj { put("type", "string") })
            })
            put("required", req("title"))
        },
    ) { args ->
        val title = JsonArg.string(args, "title") ?: return@ToolDef "ERROR: 缺少 title"
        val text = JsonArg.string(args, "text") ?: ""
        runCatching {
            val nm = ctx().getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = android.app.NotificationChannel("devcodex_tools", "AI 工具通知", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(ch)
            val n = android.app.Notification.Builder(ctx(), "devcodex_tools")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(text)
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
            "OK: 通知已发送"
        }.getOrElse { "ERROR: ${it.message}（Android 13+ 需通知权限）" }
    }

    // ============ ccco 式设备工具 ============
    val alarmSet = ToolDef(
        name = "alarm_set",
        description = "设置系统闹钟（跳系统闹钟预填）。参数 hour(0-23 必填) minute(0-59 必填) label(可选)",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("hour", obj { put("type", "integer") })
                put("minute", obj { put("type", "integer") })
                put("label", obj { put("type", "string") })
            })
            put("required", req("hour", "minute"))
        },
    ) { args ->
        val hour = JsonArg.int(args, "hour") ?: return@ToolDef "ERROR: 缺少 hour"
        val minute = JsonArg.int(args, "minute") ?: return@ToolDef "ERROR: 缺少 minute"
        val label = JsonArg.string(args, "label") ?: "DevCodex"
        runCatching {
            val i = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx().startActivity(i)
            "OK: 闹钟 $hour:$minute $label 已预填，请在系统界面保存"
        }.getOrElse { "ERROR: ${it.message}（需系统闹钟应用）" }
    }

    val shareContent = ToolDef(
        name = "share_content",
        description = "系统分享文本（弹分享面板）。参数 text(必填) title(可选)",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("text", obj { put("type", "string") })
                put("title", obj { put("type", "string") })
            })
            put("required", req("text"))
        },
    ) { args ->
        val text = JsonArg.string(args, "text") ?: return@ToolDef "ERROR: 缺少 text"
        runCatching {
            val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx().startActivity(android.content.Intent.createChooser(i, JsonArg.string(args, "title") ?: "分享"))
            "OK: 分享面板已弹出"
        }.getOrElse { "ERROR: ${it.message}" }
    }

    val calendarOpen = ToolDef(
        name = "calendar_open",
        description = "打开系统日历应用（ccco 式设备工具）",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("date", obj { put("type", "string") }) })
        },
    ) { _ ->
        runCatching {
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("content://com.android.calendar/time/")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx().startActivity(i)
            "OK: 日历已打开"
        }.getOrElse { "ERROR: ${it.message}" }
    }

    // ============ 沙盒扩展工具（自动装包） ============
    val sandboxPkgInstall = ToolDef(
        name = "sandbox_pkg_install",
        description = "在 Linux 沙盒（优先 PRoot Ubuntu，失败回退 Termux）联网安装软件包（apt）。如 python nodejs git ffmpeg rust golang openssh zip unzip 等。装完即可在 shell_exec 中使用。首次约 1-2 分钟",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("packages", obj { put("type", "string"); put("description", "空格分隔包名，如 \"python nodejs git\"") })
            })
            put("required", req("packages"))
        },
        risk = ToolDef.Risk.MEDIUM,
    ) { args ->
        val pkgs = JsonArg.string(args, "packages") ?: return@ToolDef "ERROR: 缺少 packages"
        ShellAccess.execSandbox("(command -v apt-get >/dev/null 2>&1 && DEBIAN_FRONTEND=noninteractive apt-get install -y $pkgs || pkg install -y $pkgs) 2>&1 | tail -20").take(8000)
    }

    val pythonRun = ToolDef(
        name = "python_run",
        description = "在 Linux 沙盒运行 Python 脚本（首次自动安装 python）。适合数据处理/爬虫/算法",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("code", obj { put("type", "string") })
                put("stdin", obj { put("type", "string") })
            })
            put("required", req("code"))
        },
        risk = ToolDef.Risk.MEDIUM,
    ) { args ->
        val code = JsonArg.string(args, "code") ?: return@ToolDef "ERROR: 缺少 code"
        val script = java.io.File(ctx().cacheDir, "devcodex_py.py")
        script.writeText(code)
        val hasPy = ShellAccess.execSandbox("command -v python3")
        if (!hasPy.contains("python3")) {
            ShellAccess.execSandbox("(command -v apt-get >/dev/null 2>&1 && DEBIAN_FRONTEND=noninteractive apt-get install -y python3 || pkg install -y python) 2>&1 | tail -3")
        }
        ShellAccess.execSandbox("python3 ${script.absolutePath} 2>&1").take(20000)
    }

    val nodeRun = ToolDef(
        name = "node_run",
        description = "在 Linux 沙盒运行 Node.js 脚本（首次自动安装 nodejs）。适合 JSON 处理/工具脚本",
        parameters = obj {
            put("type", "object")
            put("properties", obj {
                put("code", obj { put("type", "string") })
            })
            put("required", req("code"))
        },
        risk = ToolDef.Risk.MEDIUM,
    ) { args ->
        val code = JsonArg.string(args, "code") ?: return@ToolDef "ERROR: 缺少 code"
        val script = java.io.File(ctx().cacheDir, "devcodex_js.js")
        script.writeText(code)
        val hasNode = ShellAccess.execSandbox("command -v node")
        if (!hasNode.contains("node")) {
            ShellAccess.execSandbox("(command -v apt-get >/dev/null 2>&1 && DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs || pkg install -y nodejs) 2>&1 | tail -3")
        }
        ShellAccess.execSandbox("node ${script.absolutePath} 2>&1").take(20000)
    }

    // ============ Zafiro 式设备工具 ============
    val deviceInfo = ToolDef(
        name = "device_info",
        description = "读取设备信息（型号/系统/API/屏幕/内存/电量/存储）",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("detail", obj { put("type", "string") }) })
        },
    ) { _ ->
        val am = ctx().getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val bat = ctx().registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra("level", -1) ?: -1
        val scale = bat?.getIntExtra("scale", 100) ?: 100
        val dm = ctx().resources.displayMetrics
        buildString {
            appendLine("型号: ${android.os.Build.MODEL}（${android.os.Build.BRAND}）")
            appendLine("系统: Android ${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）")
            appendLine("屏幕: ${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi")
            appendLine("内存: 总 ${mi.totalMem / 1048576}MB / 可用 ${mi.availMem / 1048576}MB")
            appendLine("电量: ${if (level >= 0) "${level * 100 / scale}%" else "未知"}")
            append("存储: 可用 ${android.os.Environment.getDataDirectory().usableSpace / 1048576}MB")
        }
    }

    val vibrate = ToolDef(
        name = "vibrate",
        description = "振动设备（ms 毫秒）",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("ms", obj { put("type", "integer") }) })
        },
    ) { args ->
        val ms = ai.devcodex.agent.util.JsonArg.int(args, "ms") ?: 300
        runCatching {
            val v = ctx().getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(ms.coerceIn(50, 5000).toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else @Suppress("DEPRECATION") v.vibrate(ms.coerceIn(50, 5000).toLong())
            "OK: 已振动 ${ms}ms"
        }.getOrElse { "ERROR: ${it.message}" }
    }

    val openUrl = ToolDef(
        name = "open_url",
        description = "用系统浏览器打开链接",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("url", obj { put("type", "string") }) })
        },
    ) { args ->
        val url = ai.devcodex.agent.util.JsonArg.string(args, "url") ?: return@ToolDef "ERROR: 缺少 url"
        runCatching {
            ctx().startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            "OK: 已打开 $url"
        }.getOrElse { "ERROR: ${it.message}" }
    }

    // ============ Zafiro 精华设备工具（二批） ============
    val screenshotTake = ToolDef(
        name = "screenshot_take",
        description = "截取当前屏幕（需 Shizuku/ADB 权限），保存到工作区并返回文件名",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("name", obj { put("type", "string") }) })
        },
        risk = ToolDef.Risk.MEDIUM,
    ) { args ->
        val name = ai.devcodex.agent.util.JsonArg.string(args, "name") ?: ("shot_" + System.currentTimeMillis())
        val out = java.io.File(ctx().filesDir, "workspace/" + name + ".png")
        out.parentFile.mkdirs()
        val r = ShellAccess.exec("screencap -p " + out.absolutePath + " && echo OK || echo FAIL")
        if (out.exists() && out.length() > 1000) "OK: 截屏已保存 workspace/" + name + ".png（" + (out.length() / 1024) + "KB）"
        else "ERROR: 截屏失败（需 Shizuku/ADB 授权）——" + r.take(100)
    }

    val clipboardGet = ToolDef(
        name = "clipboard_get",
        description = "读取系统剪贴板文本（Android 10+ 前台可用）",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("dummy", obj { put("type", "string") }) })
        },
    ) { _ ->
        runCatching {
            val cm = ctx().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (t.isBlank()) "剪贴板为空" else t.take(5000)
        }.getOrElse { "ERROR: " + it.message }
    }

    val wifiState = ToolDef(
        name = "wifi_state",
        description = "读取 WiFi 连接状态（SSID/信号/IP）",
        parameters = obj {
            put("type", "object")
            put("properties", obj { put("dummy", obj { put("type", "string") }) })
        },
    ) { _ ->
        runCatching {
            val wm = ctx().applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val info = wm.connectionInfo
            val ssid = info.ssid.removePrefix("\"").removeSuffix("\"")
            buildString {
                append("WiFi: ")
                append(if (ssid.isBlank() || ssid == "<unknown ssid>") "未连接或读取受限" else ssid)
                append(" / 信号 ")
                append(android.net.wifi.WifiManager.calculateSignalLevel(info.rssi, 100))
                append("/100 / IP ")
                append(if (info.ipAddress != 0) android.text.format.Formatter.formatIpAddress(info.ipAddress) else "无")
            }
        }.getOrElse { "ERROR: " + it.message }
    }

    val all = listOf(
        webSearch, browserRender, imageInfo, imageConvert,
        pipelineRun, bgList, bgKill, calendarList, calendarAdd,
        clipboardWrite, notifyPost, alarmSet, shareContent, calendarOpen,
        sandboxPkgInstall, pythonRun, nodeRun, apkBuild, nativeBuild, jarBuild, vibrate, openUrl,
        screenshotTake, clipboardGet, wifiState,
    )
}
