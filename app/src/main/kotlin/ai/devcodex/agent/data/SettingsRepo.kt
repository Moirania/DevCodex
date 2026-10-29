package ai.devcodex.agent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "devcodex_settings")

class SettingsRepo(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("settings_json")

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        p[key]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()
    }

    /** 一次性迁移：旧审批模式（SharedPreferences）与 toolPerms 转为 permissionDefault / toolPermissions */
    suspend fun migratePermissionsIfNeeded() {
        update { cur ->
            if (cur.permissionsMigrated) return@update cur
            val old = context.getSharedPreferences("devcodex_prefs", Context.MODE_PRIVATE)
                .getString("approval_mode", null)
                ?: context.getSharedPreferences("devcodex_stats", Context.MODE_PRIVATE)
                    .getString("approval_mode", null)
            val default = if (old == "FULL") "ALLOW" else "ASK"
            val perTool = cur.toolPerms.filterValues { it == "ALLOW" }
            cur.copy(
                permissionDefault = default,
                toolPermissions = perTool + cur.toolPermissions,
                permissionsMigrated = true,
            )
        }
    }

    /** 一次性迁移：把生图/视频工具名并入旧用户已持久化的 enabledTools（仅执行一次，用户之后手动关闭的选择会被保留） */
    suspend fun migrateMediaToolsIfNeeded() {
        update { cur ->
            if (cur.mediaToolsMigrated) return@update cur
            cur.copy(
                enabledTools = cur.enabledTools + setOf("image_generate", "video_generate", "video_status"),
                mediaToolsMigrated = true,
            )
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val cur = p[key]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()
            p[key] = json.encodeToString(AppSettings.serializer(), transform(cur))
        }
    }
}
