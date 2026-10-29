package ai.devcodex.agent.agent

import ai.devcodex.agent.data.AppSettings

/** 工具权限三档：ALLOW 直接执行，ASK 执行前询问，FORBID 禁止执行 */
enum class PermissionLevel {
    ALLOW, ASK, FORBID;

    companion object {
        /** 兼容旧值：FULL 视为 ALLOW，CAUTION 视为 ASK，未知值回退 ASK */
        fun parse(raw: String?): PermissionLevel = when (raw?.trim()?.uppercase()) {
            "ALLOW", "FULL" -> ALLOW
            "FORBID" -> FORBID
            else -> ASK
        }
    }
}

data class PermissionDecision(val level: PermissionLevel, val reason: String)

/**
 * 权限解析顺序：
 * 1. 单工具例外为 FORBID 或全局为 FORBID：禁止，会话授权也不能覆盖
 * 2. 本会话已授权：放行
 * 3. 单工具例外：ALLOW 放行，ASK 询问
 * 4. 全局默认：ALLOW 放行；ASK 时低风险且未越界的工具放行，其余询问
 */
object PermissionResolver {
    fun resolve(
        def: ToolDef,
        settings: AppSettings,
        sessionAllows: Set<String>,
        outsideWorkspaceWrite: Boolean,
    ): PermissionDecision {
        val override = settings.toolPermissions[def.name]?.let { PermissionLevel.parse(it) }
        val global = PermissionLevel.parse(settings.permissionDefault)
        if (override == PermissionLevel.FORBID || (override == null && global == PermissionLevel.FORBID)) {
            return PermissionDecision(PermissionLevel.FORBID, "该工具已被设置为禁止执行")
        }
        if (sessionAllows.contains(def.name)) return PermissionDecision(PermissionLevel.ALLOW, "")
        val level = override ?: when {
            global == PermissionLevel.ALLOW -> PermissionLevel.ALLOW
            def.risk == ToolDef.Risk.LOW && !outsideWorkspaceWrite -> PermissionLevel.ALLOW
            else -> PermissionLevel.ASK
        }
        if (level != PermissionLevel.ASK) return PermissionDecision(level, "")
        val hint = def.description.take(50)
        val reason = when {
            outsideWorkspaceWrite -> "写入工作区之外的路径，需确认"
            def.risk == ToolDef.Risk.HIGH -> "高危操作，需确认：$hint"
            def.risk == ToolDef.Risk.MEDIUM -> "该工具会改变设备或外部状态，需确认：$hint"
            else -> "该工具已设为每次询问：$hint"
        }
        return PermissionDecision(PermissionLevel.ASK, reason)
    }
}
