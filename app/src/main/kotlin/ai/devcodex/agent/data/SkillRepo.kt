package ai.devcodex.agent.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 技能库：agentskills.io 标准（SKILL.md = frontmatter(name/description) + markdown 正文）
 * 安装来源：本地文件 / URL / 内置示例
 * 注入方式：description 常驻系统提示词，正文按需由 AI 调 skill_read 读取（渐进披露）
 */
class SkillRepo(private val context: Context) {

    data class Skill(
        val name: String,
        val description: String,
        val content: String,
        val enabled: Boolean = true,
        val dir: File,
    )

    val root: File get() = File(context.filesDir, "skills").apply { mkdirs() }

    fun list(): List<Skill> =
        root.listFiles { f -> f.isDirectory }?.mapNotNull { dir ->
            val f = File(dir, "SKILL.md")
            if (!f.exists()) return@mapNotNull null
            parse(f.readText(), dir)
        }?.sortedBy { it.name } ?: emptyList()

    private fun parse(text: String, dir: File): Skill? {
        val normalized = text.replace("\r\n", "\n").replace("\uFEFF", "")
        val m = Regex("^---\\s*\\n(.*?)\\n---\\s*\\n?(.*)$", RegexOption.DOT_MATCHES_ALL).find(normalized) ?: return null
        val fm = m.groupValues[1]
        val name = Regex("^name:\\s*(.+)$", RegexOption.MULTILINE).find(fm)?.groupValues?.get(1)?.trim() ?: dir.name
        val desc = Regex("^description:\\s*(.+)$", RegexOption.MULTILINE).find(fm)?.groupValues?.get(1)?.trim() ?: ""
        val flag = File(dir, ".disabled")
        return Skill(name, desc, m.groupValues[2], !flag.exists(), dir)
    }

    /** 从 SKILL.md 全文安装（name 冲突覆盖） */
    fun install(text: String): Result<Skill> = runCatching {
        val m = Regex("^---\\s*\\n(.*?)\\n---", RegexOption.DOT_MATCHES_ALL).find(text.trim())
            ?: throw IllegalArgumentException("缺少 frontmatter（需要 --- name/description --- 开头）")
        val fm = m.groupValues[1]
        val name = Regex("^name:\\s*(.+)$", RegexOption.MULTILINE).find(fm)?.groupValues?.get(1)?.trim()
            ?: throw IllegalArgumentException("frontmatter 缺少 name")
        val safeName = name.replace(Regex("[^\\w\\u4e00-\\u9fa5.-]"), "_")
        // 路径穿越防护：拒绝纯点号名称，并确认目录直接位于技能根目录下
        if (safeName.isBlank() || safeName.all { it == '.' }) throw IllegalArgumentException("技能名称非法")
        val dir = File(root, safeName)
        if (dir.canonicalFile.parentFile != root.canonicalFile) throw IllegalArgumentException("技能名称非法")
        dir.mkdirs()
        File(dir, "SKILL.md").writeText(text.trim())
        parse(File(dir, "SKILL.md").readText(), dir) ?: throw IllegalStateException("解析失败")
    }

    /** 从 GitHub 仓库导入：支持 /owner/repo、/owner/repo/tree/branch/subdir
     *  下载仓库 zip（codeload）→ 找根目录或指定子目录的 SKILL.md + 附属文件 */
    fun installFromGithub(url: String): Result<Skill> = runCatching {
        val clean = url.trim().removePrefix("https://").removePrefix("http://")
            .removePrefix("github.com/").removeSuffix("/")
        val parts = clean.split("/")
        if (parts.size < 2) throw IllegalArgumentException("URL 需为 owner/repo 格式")
        val owner = parts[0]; val repo = parts[1].removeSuffix(".git")
        val (branch, subPath) = if (parts.size >= 4 && parts[2] == "tree")
            (parts.getOrNull(3) ?: "main") to parts.drop(4).joinToString("/")
        else "main" to ""
        val zipUrl = "https://codeload.github.com/$owner/$repo/zip/refs/heads/$branch"
        val bytes = java.net.URL(zipUrl).openStream().use { it.readBytes() }
        // 解析 zip 内存流
        val zin = java.util.zip.ZipInputStream(bytes.inputStream())
        val prefix = "$repo-$branch/"
        val targetDirPrefix = prefix + (if (subPath.isBlank()) "" else subPath.trim('/') + "/")
        var skillText: String? = null
        val extras = mutableListOf<Pair<String, ByteArray>>()
        var e = zin.nextEntry
        val buf = ByteArray(65536)
        while (e != null) {
            val n = e.name.removePrefix(prefix)
            val inSkillDir = e.name.startsWith(targetDirPrefix) && !n.endsWith("/")
            if (inSkillDir) {
                val rel = n.removePrefix(if (subPath.isBlank()) "" else subPath.trim('/') + "/")
                val data = ByteArrayOutputStream().use { o ->
                    var r = zin.read(buf)
                    while (r >= 0) { o.write(buf, 0, r); r = zin.read(buf) }
                    o.toByteArray()
                }
                if (rel.equals("SKILL.md", true) || rel.equals("skill.md", true)) skillText = String(data)
                else if (!rel.startsWith(".") && rel.isNotBlank()) extras.add(rel to data)
            }
            e = zin.nextEntry
        }
        val text = skillText ?: throw IllegalArgumentException("仓库${if (subPath.isBlank()) "根目录" else "路径 $subPath"}未找到 SKILL.md")
        val sk = install(text).getOrThrow()
        // 附属文件写入技能目录
        val skRoot = sk.dir.canonicalFile
        extras.filter { it.first.isNotBlank() }.forEach { (rel, data) ->
            val out = File(sk.dir, rel).canonicalFile
            // zip slip 防护：附属文件必须落在技能目录内
            if (!out.path.startsWith(skRoot.path + File.separator)) return@forEach
            out.parentFile?.mkdirs()
            out.writeBytes(data)
        }
        sk
    }

    /** 从 .skill 压缩包导入（zip：根含 SKILL.md + 附属文件） */
    fun installFromZipFile(zipFile: File): Result<Skill> = runCatching {
        java.util.zip.ZipFile(zipFile).use { zf ->
            val entry = zf.entries().toList().firstOrNull {
                it.name.equals("SKILL.md", true) || it.name.equals("skill.md", true)
            } ?: throw IllegalArgumentException("压缩包根目录缺少 SKILL.md")
            val text = zf.getInputStream(entry).readBytes().decodeToString()
            val sk = install(text).getOrThrow()
            val skRoot = sk.dir.canonicalFile
            zf.entries().toList().filter { !it.isDirectory && it.name != entry.name && !it.name.contains("..") }.forEach { ex ->
                val out = File(sk.dir, ex.name).canonicalFile
                // zip slip 防护：附属文件必须落在技能目录内
                if (!out.path.startsWith(skRoot.path + File.separator)) return@forEach
                out.parentFile?.mkdirs()
                zf.getInputStream(ex).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
            sk
        }
    }

    fun toggle(name: String) {
        list().find { it.name == name }?.let { sk ->
            val flag = File(sk.dir, ".disabled")
            if (sk.enabled) flag.writeText("off") else flag.delete()
        }
    }

    fun delete(name: String) {
        list().find { it.name == name }?.dir?.deleteRecursively()
    }
}
