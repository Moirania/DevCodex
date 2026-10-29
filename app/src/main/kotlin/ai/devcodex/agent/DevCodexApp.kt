package ai.devcodex.agent

import android.app.Application
import kotlinx.coroutines.launch
import ai.devcodex.agent.data.PluginRepo
import ai.devcodex.agent.data.SessionRepo
import ai.devcodex.agent.data.SettingsRepo
import ai.devcodex.agent.data.WorkspaceRepo
import ai.devcodex.agent.shell.ShellAccess
import ai.devcodex.agent.shell.SshClient
import ai.devcodex.agent.tools.BuiltinTools
import ai.devcodex.agent.util.CrashHandler
import rikka.shizuku.Shizuku

class DevCodexApp : Application() {

    companion object { lateinit var instance: DevCodexApp }

    lateinit var settingsRepo: SettingsRepo
    lateinit var sessionRepo: SessionRepo
    lateinit var pluginRepo: PluginRepo
    lateinit var workspaceRepo: WorkspaceRepo

    override fun onCreate() {
        super.onCreate()
        ai.devcodex.agent.util.DebugLog.init(this)
        CrashHandler.install(this)
        instance = this
        settingsRepo = SettingsRepo(this)
        sessionRepo = SessionRepo(this)
        pluginRepo = PluginRepo(this)
        workspaceRepo = WorkspaceRepo(this)
        BuiltinTools.init(this)
        seedPlugins()
        seedSkills()
        // 工作区目录 + AXS 服务同步
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            settingsRepo.settings.collect { s ->
                ai.devcodex.agent.data.WorkspaceConfig.dir = s.workspaceDir
                ShellAccess.axsUrl = s.axsUrl
                SshClient.config = if (s.sshHost.isNotBlank()) SshClient.SshConfig(s.sshHost, s.sshPort, s.sshUser, s.sshPassword, s.sshKeyPath) else null
            }
        }

        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ShellAccess.bindShizukuService()
            }
        }
        // Shizuku 服务生死监听：死了清空引用，复活自动重绑
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky { ShellAccess.bindShizukuService() }
        rikka.shizuku.Shizuku.addBinderDeadListener { ShellAccess.onShizukuDead() }
        ShellAccess.bindShizukuService()
    }

    // 首启导入内置插件（assets/plugins/*.json，已存在则跳过）
    private fun seedPlugins() {
        val marker = java.io.File(filesDir, ".builtin_plugins_v2")
        if (marker.exists()) return
        runCatching {
            val installed = pluginRepo.list().map { it.id }.toSet()
            assets.list("plugins")?.forEach { name ->
                runCatching {
                    val text = assets.open("plugins/" + name).bufferedReader().readText()
                    val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                    // 已安装的内置插件保留用户的启用状态，只补装新增的
                    if (id == null || id !in installed) pluginRepo.installFromText(text)
                }
            }
            marker.writeText("ok")
        }
    }

    // 首启导入内置技能（assets/skills/*.md，独立 marker 可重复导入新技能）
    private fun seedSkills() {
        runCatching {
            val repo = ai.devcodex.agent.data.SkillRepo(this)
            assets.list("skills")?.forEach { name ->
                runCatching {
                    val text = assets.open("skills/" + name).bufferedReader().readText()
                    // 同名已装则跳过（用户可能改过）
                    val skillName = Regex("^name:\\s*(.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim() ?: return@runCatching
                    if (repo.list().none { it.name == skillName }) repo.install(text)
                }
            }
        }
    }
}
