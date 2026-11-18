package ai.devcodex.agent

import android.app.Application
import kotlinx.coroutines.flow.first
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
        // v0.4.7 修"文件消失"：collect 是异步的，冷启动窗口期 WorkspaceConfig.dir 为空 →
        // 工作区漂移到私有目录。先 runBlocking 同步预热（DataStore 首读 ~10ms），再挂异步 collect
        runCatching {
            val first = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(1500) { settingsRepo.settings.first() }
            }
            first?.let { ai.devcodex.agent.data.WorkspaceConfig.dir = it.workspaceDir }
        }
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
        val marker = java.io.File(filesDir, ".builtin_plugins_v1")
        if (marker.exists()) return
        runCatching {
            assets.list("plugins")?.forEach { name ->
                runCatching {
                    val text = assets.open("plugins/" + name).bufferedReader().readText()
                    pluginRepo.installFromText(text)
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
