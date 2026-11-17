package ai.devcodex.agent.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * 聊天记录导入（v0.4.6）：
 * 1. RikkaHub 完整备份 zip——解压找 Room 数据库（.db），读 ConversationEntity + message_node 两表，
 *    还原全部会话（分支消息取 select_index 选中项；Text→content / Reasoning→thinking / Tool→工具卡片）
 * 2. Chatbox 导出 JSON——sessions[].messages[] {role, content}
 * 3. 通用 JSON——{messages:[{role,content}]} 或顶层数组，导入为单个会话
 *
 * 表结构来源：rikkaparser db_reader.py（RikkaHub Room schema 实测解析）
 */
object ChatImporter {

    data class ImportResult(val sessions: Int, val messages: Int, val detail: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 统一入口：按内容嗅探格式 */
    fun import(context: Context, uri: Uri, repo: SessionRepo): ImportResult {
        val bytesHead = runCatching {
            context.contentResolver.openInputStream(uri)?.use { s ->
                val buf = ByteArray(8); var off = 0
                while (off < 8) { val n = s.read(buf, off, 8 - off); if (n <= 0) break; off += n }
                buf.copyOf(off)
            }
        }.getOrNull()
        // zip 魔数 PK\x03\x04
        if (bytesHead != null && bytesHead.size >= 4 && bytesHead[0] == 'P'.code.toByte() && bytesHead[1] == 'K'.code.toByte()) {
            return importRikkaHubZip(context, uri, repo)
        }
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return ImportResult(0, 0, "无法读取文件")
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("{") && "\"sessions\"" in text) importChatboxJson(text, repo)
        else importGenericJson(text, repo)
    }

    // ===== 1. RikkaHub 备份 zip =====

    private fun importRikkaHubZip(context: Context, uri: Uri, repo: SessionRepo): ImportResult {
        // 解压找 .db（Room 数据库）
        val dbFile = File(context.cacheDir, "import_${System.currentTimeMillis()}.db")
        var found = false
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && (e.name.endsWith(".db") || e.name.endsWith(".sqlite")) && !found) {
                            dbFile.outputStream().use { out -> zis.copyTo(out) }
                            found = true
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
        }
        if (!found || !dbFile.exists()) return ImportResult(0, 0, "zip 中未找到数据库文件（.db）")
        return try {
            readRikkaHubDb(dbFile, repo)
        } finally {
            dbFile.delete()
        }
    }

    /** 读 Room 库两张表，转 ChatSession 落库 */
    private fun readRikkaHubDb(dbFile: File, repo: SessionRepo): ImportResult {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        var totalMsgs = 0
        val sessions = mutableListOf<ChatSession>()
        try {
            // RikkaHub 时间戳为 epoch 毫秒
            val convCursor = db.rawQuery(
                "SELECT id, title, create_at, update_at FROM ConversationEntity ORDER BY update_at DESC", null
            )
            val convs = mutableListOf<Triple<String, String, Long>>()
            while (convCursor.moveToNext()) {
                convs.add(Triple(convCursor.getString(0), convCursor.getString(1) ?: "导入会话", convCursor.getLong(3)))
            }
            convCursor.close()

            for ((convId, title, updatedAt) in convs) {
                val messages = mutableListOf<Message>()
                val nodeCursor = db.rawQuery(
                    "SELECT messages, select_index FROM message_node WHERE conversation_id = ? ORDER BY node_index ASC",
                    arrayOf(convId)
                )
                while (nodeCursor.moveToNext()) {
                    val messagesJson = nodeCursor.getString(0) ?: continue
                    val selectIndex = nodeCursor.getInt(1)
                    val variants = runCatching {
                        json.parseToJsonElement(messagesJson).jsonArray.map { it.jsonObject }
                    }.getOrNull() ?: continue
                    val selected = variants.getOrNull(selectIndex) ?: variants.firstOrNull() ?: continue
                    parseUiMessage(selected)?.let { messages.add(it) }
                }
                nodeCursor.close()
                if (messages.isEmpty()) continue
                totalMsgs += messages.size
                sessions.add(ChatSession(
                    id = "imp_${UUID.randomUUID().toString().take(12)}",
                    title = title,
                    messages = messages,
                    createdAt = updatedAt,
                    updatedAt = updatedAt,
                ))
            }
        } finally {
            db.close()
        }
        sessions.forEach { repo.save(it) }
        return ImportResult(sessions.size, totalMsgs, "RikkaHub 备份：${sessions.size} 个会话 / ${totalMsgs} 条消息")
    }

    /** RikkaHub UIMessage → DevCodex Message（parts 多态：Text/Reasoning/Tool） */
    private fun parseUiMessage(msg: JsonObject): Message? {
        val role = msg["role"]?.jsonPrimitive?.contentOrNull ?: return null
        val parts = msg["parts"]?.jsonArray ?: return null
        var content = StringBuilder()
        var thinking: String? = null
        var toolName: String? = null
        var toolResult: String? = null
        var ts = 0L
        for (partEl in parts) {
            val part = partEl.jsonObject
            val type = part["type"]?.jsonPrimitive?.contentOrNull ?: ""
            when {
                type.endsWith("UIMessagePart.Text") || (part.containsKey("text") && !type.endsWith("Reasoning") && !part.containsKey("toolCallId")) ->
                    content.append(part["text"]?.jsonPrimitive?.contentOrNull ?: "")
                type.endsWith("UIMessagePart.Reasoning") || part.containsKey("reasoning") -> {
                    val r = part["reasoning"]?.jsonPrimitive?.contentOrNull
                    if (!r.isNullOrBlank()) thinking = (thinking ?: "") + r
                }
                type.endsWith("UIMessagePart.Tool") || part.containsKey("toolCallId") -> {
                    if (toolName == null) toolName = part["toolName"]?.jsonPrimitive?.contentOrNull
                    val output = part["output"]?.jsonArray
                        ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                        ?.joinToString("\n")
                    if (!output.isNullOrBlank()) toolResult = output
                }
            }
        }
        msg["createdAt"]?.jsonPrimitive?.longOrNull?.let { ts = it }
        if (content.isBlank() && thinking.isNullOrBlank() && toolName == null) return null
        return Message(
            id = "im_${UUID.randomUUID().toString().take(12)}",
            role = if (role == "user") "user" else "assistant",
            content = content.toString(),
            thinking = thinking,
            toolName = toolName,
            toolResult = toolResult,
            model = msg["modelId"]?.jsonPrimitive?.contentOrNull,
            timestamp = ts,
        )
    }

    // ===== 2. Chatbox JSON =====

    private fun importChatboxJson(text: String, repo: SessionRepo): ImportResult {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ImportResult(0, 0, "JSON 解析失败")
        val sessionsArr = root["sessions"]?.jsonArray ?: return ImportResult(0, 0, "缺少 sessions 数组")
        var totalMsgs = 0
        var count = 0
        for (sEl in sessionsArr) {
            val s = sEl.jsonObject
            val msgsArr = s["messages"]?.jsonArray ?: continue
            val messages = mutableListOf<Message>()
            for (mEl in msgsArr) {
                val m = mEl.jsonObject
                val role = m["role"]?.jsonPrimitive?.contentOrNull ?: continue
                val content = m["content"]?.jsonPrimitive?.contentOrNull ?: ""
                if (content.isBlank()) continue
                messages.add(Message(
                    id = "im_${UUID.randomUUID().toString().take(12)}",
                    role = if (role == "assistant") "assistant" else "user",
                    content = content,
                ))
            }
            if (messages.isEmpty()) continue
            totalMsgs += messages.size
            repo.save(ChatSession(
                id = "imp_${UUID.randomUUID().toString().take(12)}",
                title = s["name"]?.jsonPrimitive?.contentOrNull ?: "Chatbox 导入会话",
                messages = messages,
            ))
            count++
        }
        return ImportResult(count, totalMsgs, "Chatbox：${count} 个会话 / ${totalMsgs} 条消息")
    }

    // ===== 3. 通用 messages JSON =====

    private fun importGenericJson(text: String, repo: SessionRepo): ImportResult {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return ImportResult(0, 0, "JSON 解析失败")
        val msgsArr = when {
            root is JsonObject && root.containsKey("messages") -> root["messages"]?.jsonArray
            root is kotlinx.serialization.json.JsonArray -> root as? kotlinx.serialization.json.JsonArray
            else -> null
        } ?: return ImportResult(0, 0, "未识别的格式（需要 messages 数组或顶层数组）")
        val messages = mutableListOf<Message>()
        for (mEl in msgsArr) {
            runCatching {
                val m = mEl.jsonObject
                val role = m["role"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                val content = m["content"]?.jsonPrimitive?.contentOrNull ?: ""
                if (content.isBlank()) return@runCatching
                messages.add(Message(
                    id = "im_${UUID.randomUUID().toString().take(12)}",
                    role = if (role == "assistant") "assistant" else "user",
                    content = content,
                ))
            }
        }
        if (messages.isEmpty()) return ImportResult(0, 0, "没有可导入的消息")
        repo.save(ChatSession(
            id = "imp_${UUID.randomUUID().toString().take(12)}",
            title = messages.firstOrNull()?.content?.take(20)?.ifBlank { "导入会话" } ?: "导入会话",
            messages = messages,
        ))
        return ImportResult(1, messages.size, "通用 JSON：1 个会话 / ${messages.size} 条消息")
    }
}
