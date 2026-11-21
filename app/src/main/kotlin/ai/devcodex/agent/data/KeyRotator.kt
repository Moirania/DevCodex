package ai.devcodex.agent.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * v0.4.10 Key 轮转器：同一供应商多 key 自动轮转。
 * - 选 key：优先"未限速且未冷却"的（计数最小优先，负载均衡）
 * - rpm 限速：滑动窗口内请求数达上限 → 该 key 本分钟不再选
 * - 冷却：调用方报告 429/超限时 cooldown(key, 60s)，期间跳过
 * - 全部不可用 → 退回主 apiKey（兜底行为与旧版一致）
 */
object KeyRotator {
    private data class KeyState(val windowStart: AtomicLong = AtomicLong(0), val count: AtomicLong = AtomicLong(0), val cooldownUntil: AtomicLong = AtomicLong(0))

    private val states = ConcurrentHashMap<String, KeyState>()  // providerId:key -> state

    fun reportSuccess(p: ProviderConfig, key: String) {
        // 预留：成功路径统计（当前计数在 acquire 内完成）
    }

    fun reportLimit(p: ProviderConfig, key: String, coolMs: Long = 60_000) {
        states.getOrPut("${p.id}:$key") { KeyState() }.cooldownUntil.set(System.currentTimeMillis() + coolMs)
    }

    /** 最近一次 acquire 选中的 key（429 上报用；进程级） */
    @Volatile private var lastPicked: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    fun lastKey(p: ProviderConfig): String = lastPicked[p.id] ?: p.apiKey

    /** 记录一次使用并返回应该用的 key（轮转核心） */
    fun acquire(p: ProviderConfig): String {
        val pool = p.apiKeys.filter { it.key.isNotBlank() }
        if (pool.isEmpty()) return p.apiKey
        val now = System.currentTimeMillis()
        // 初始化/滑动窗口：每分钟重置计数
        val candidates = pool.map { ke ->
            val id = "${p.id}:${ke.key}"
            val st = states.getOrPut(id) { KeyState() }
            if (now - st.windowStart.get() > 60_000) {
                st.windowStart.set(now); st.count.set(0)
            }
            Triple(ke, st, st.cooldownUntil.get() <= now)
        }
        // 可用 = 未冷却 && (不限速 || 窗口内计数 < rpm)
        val usable = candidates.filter { (ke, st, ok) -> ok && (ke.rpm <= 0 || st.count.get() < ke.rpm) }
        val picked = when {
            usable.isNotEmpty() -> {
                // 负载均衡：窗口计数最小优先（并列取先到）
                val (ke, st, _) = usable.minByOrNull { (_, st, _) -> st.count.get() }!!
                st.count.incrementAndGet()
                ke.key
            }
            // 全限速/冷却：选冷却结束最早的硬上（比 429 好一点），或退主 key
            else -> candidates.minByOrNull { (_, st, _) -> st.cooldownUntil.get() }?.first?.key ?: p.apiKey
        }
        lastPicked[p.id] = picked
        return picked
    }

    /** 并发测试：全部 key 的即时状态（每分钟剩余次数/冷却剩余秒） */
    fun snapshot(p: ProviderConfig): List<Pair<KeyEntry, String>> {
        val now = System.currentTimeMillis()
        return p.apiKeys.map { ke ->
            val st = states["${p.id}:${ke.key}"]
            val status = when {
                st == null -> "空闲"
                st.cooldownUntil.get() > now -> "冷却 ${((st.cooldownUntil.get() - now) / 1000 + 1)}s"
                ke.rpm > 0 -> "已用 ${st.count.get()}/${ke.rpm} 每分"
                else -> "活跃"
            }
            ke to status
        }
    }
}
