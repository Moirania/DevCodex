package ai.devcodex.agent.provider

/**
 * 内置模型知识库（移植自 rikkahub ModelRegistry 思路，Apache-2.0）
 *
 * 按 modelId 关键词匹配推断模型能力：
 * - tools:    支持 function calling（决定请求是否携带 tools 参数）
 * - vision:   支持图片输入
 * - reasoning: 推理模型（思考过程）
 * - contextLength: 已知上下文长度
 *
 * 未匹配的模型返回 [ModelMeta.UNKNOWN]（保守传 tools，由 400 降级重试兜底）。
 */
data class ModelMeta(
    val tools: Boolean?,
    val vision: Boolean = false,
    val reasoning: Boolean = false,
    val contextLength: Int? = null,
    val kind: ModelKind = ModelKind.CHAT,
) {
    companion object {
        val UNKNOWN = ModelMeta(tools = null)
    }
}

/** 模型用途：聊天模型走 /chat/completions，生图与视频模型走媒体工具 */
enum class ModelKind { CHAT, IMAGE, VIDEO }

private data class ModelRule(
    val keywords: List<String>,       // 命中任一关键词（lowercase contains）
    val excludes: List<String> = emptyList(),
    val meta: ModelMeta,
)

object ModelRegistry {

    private val T = true
    private val F = false

    private val rules = listOf(
        // ===== 媒体模型（必须排在聊天规则之前，否则 qwen/glm/doubao 等规则会先命中）=====
        ModelRule(keywords = listOf("cogvideo", "seedance", "veo", "kling", "sora", "hailuo", "video", "t2v", "i2v"), meta = ModelMeta(F, kind = ModelKind.VIDEO)),
        ModelRule(keywords = listOf("gpt-image", "dall-e", "cogview", "wanx", "seedream", "flux", "stable-diffusion", "sdxl", "imagen", "text-to-image", "-image"), meta = ModelMeta(F, kind = ModelKind.IMAGE)),
        // ===== OpenAI =====
        ModelRule(keywords = listOf("gpt-4o", "gpt-4.1", "gpt-4-turbo"), meta = ModelMeta(T, vision = T)),
        ModelRule(keywords = listOf("gpt-5", "gpt-6"), meta = ModelMeta(T, vision = T, reasoning = T)),
        ModelRule(keywords = listOf("o1", "o3", "o4"), meta = ModelMeta(T, vision = T, reasoning = T)),
        ModelRule(keywords = listOf("gpt-oss"), meta = ModelMeta(T, reasoning = T)),
        ModelRule(keywords = listOf("gpt-3.5"), meta = ModelMeta(T)),
        // ===== Anthropic =====
        // claude-3-opus/sonnet 与 4/5/6 系带思考；haiku 轻量不标推理
        ModelRule(keywords = listOf("claude-3-opus", "claude-3-7", "claude-4", "claude-5", "claude-6", "claude-7", "claude-8"), meta = ModelMeta(T, vision = T, reasoning = T, contextLength = 200_000)),
        ModelRule(keywords = listOf("claude"), meta = ModelMeta(T, vision = T, contextLength = 200_000)),

        // ===== Google =====
        ModelRule(keywords = listOf("gemini-2.5", "gemini-3"), meta = ModelMeta(T, vision = T, reasoning = T)),
        ModelRule(keywords = listOf("gemini"), meta = ModelMeta(T, vision = T)),

        // ===== DeepSeek =====
        ModelRule(keywords = listOf("deepseek-chat", "deepseek-v3"), meta = ModelMeta(T)),
        ModelRule(keywords = listOf("deepseek-reasoner", "deepseek-r1"), meta = ModelMeta(T, reasoning = T)),

        // ===== 智谱 GLM =====
        ModelRule(keywords = listOf("glm-4v", "glm-5v", "glm-6v"), meta = ModelMeta(T, vision = T)),
        ModelRule(keywords = listOf("glm-4", "glm-5", "glm-6"), meta = ModelMeta(T, reasoning = T)),

        // ===== 月之暗面 Kimi =====
        ModelRule(keywords = listOf("kimi", "moonshot"), meta = ModelMeta(T, contextLength = 128_000)),

        // ===== 通义 Qwen =====
        ModelRule(keywords = listOf("qwen"), meta = ModelMeta(T, vision = T)),

        // ===== 阶跃 Step =====
        ModelRule(keywords = listOf("step-"), meta = ModelMeta(T, reasoning = T)),

        // ===== 字节豆包 =====
        ModelRule(keywords = listOf("doubao"), meta = ModelMeta(T, vision = T)),

        // ===== Meta Llama（3.1+ 支持工具）=====
        ModelRule(keywords = listOf("llama"), meta = ModelMeta(T)),

        // ===== Mistral =====
        ModelRule(keywords = listOf("mistral", "mixtral"), meta = ModelMeta(T)),

        // ===== 明确不支持工具的 =====
        ModelRule(keywords = listOf("gemma"), meta = ModelMeta(F)),          // Gemma 无 function calling
        ModelRule(keywords = listOf("deepseek-coder"), meta = ModelMeta(F)), // 旧 coder 不支持
        ModelRule(keywords = listOf("llama-2"), meta = ModelMeta(F)),
        ModelRule(keywords = listOf("bge", "embedding", "whisper", "tts", "rerank"), meta = ModelMeta(F)), // 非 chat 模型
    )

    /** 查询模型元数据；多条命中时取第一条（规则按特异度排序） */
    fun lookup(modelId: String): ModelMeta {
        val id = modelId.lowercase()
        for (r in rules) {
            if (r.keywords.any { id.contains(it) } && r.excludes.none { id.contains(it) }) return r.meta
        }
        return ModelMeta.UNKNOWN
    }

    /** 该模型请求是否应携带 tools */
    fun supportsTools(modelId: String): Boolean = lookup(modelId).tools ?: true

    /** 模型用途分类 */
    fun kindOf(modelId: String): ModelKind = lookup(modelId).kind

    /** 可作为聊天主模型（排除生图与视频模型） */
    fun isChatModel(modelId: String): Boolean = kindOf(modelId) == ModelKind.CHAT
}
