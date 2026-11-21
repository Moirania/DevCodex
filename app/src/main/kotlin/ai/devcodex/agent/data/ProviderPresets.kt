package ai.devcodex.agent.data

/** 提供商预设模板：一键填充类型与 Base URL（用户只填 Key） */
data class ProviderPreset(
    val name: String,
    val baseUrl: String,
    val type: ProviderType,
    val desc: String,
)

val ProviderPresets = listOf(
    ProviderPreset("OpenAI", "https://api.openai.com/v1", ProviderType.OPENAI_COMPAT, "GPT/o 系官方"),
    ProviderPreset("Anthropic", "https://api.anthropic.com", ProviderType.ANTHROPIC, "Claude 官方"),
    ProviderPreset("DeepSeek", "https://api.deepseek.com/v1", ProviderType.OPENAI_COMPAT, "深度求索"),
    ProviderPreset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", ProviderType.OPENAI_COMPAT, "GLM 系列"),
    ProviderPreset("月之暗面 Kimi", "https://api.moonshot.cn/v1", ProviderType.OPENAI_COMPAT, "长上下文"),
    ProviderPreset("阿里通义", "https://dashscope.aliyuncs.com/compatible-mode/v1", ProviderType.OPENAI_COMPAT, "Qwen 系列"),
    ProviderPreset("字节豆包", "https://ark.cn-beijing.volces.com/api/v3", ProviderType.OPENAI_COMPAT, "火山方舟"),
    ProviderPreset("阶跃 Step", "https://api.stepfun.com/v1", ProviderType.OPENAI_COMPAT, "Step 系列"),
    ProviderPreset("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", ProviderType.OPENAI_COMPAT, "OpenAI 兼容端点"),
    ProviderPreset("Groq", "https://api.groq.com/openai/v1", ProviderType.OPENAI_COMPAT, "极速推理"),
    ProviderPreset("OpenRouter", "https://openrouter.ai/api/v1", ProviderType.OPENAI_COMPAT, "聚合 400+ 模型"),
    ProviderPreset("硅基流动", "https://api.siliconflow.cn/v1", ProviderType.OPENAI_COMPAT, "国内聚合"),
    ProviderPreset("MiniMax", "https://api.minimaxi.com/v1", ProviderType.OPENAI_COMPAT, "稀宇科技 MiniMax"),
    ProviderPreset("小米 MIMO", "https://api.xiaomi.com/v1", ProviderType.OPENAI_COMPAT, "Xiaomi MiMo 系列"),
    ProviderPreset("腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1", ProviderType.OPENAI_COMPAT, "Hunyuan 系列"),
    ProviderPreset("xAI Grok", "https://api.x.ai/v1", ProviderType.OPENAI_COMPAT, "Grok 系列"),
    ProviderPreset("美团 LongCat", "https://api.longcat.chat/openai", ProviderType.OPENAI_COMPAT, "LongCat 系列"),
    ProviderPreset("agnes-ai", "https://apihub.agnes-ai.com/v1", ProviderType.OPENAI_COMPAT, "Agnes 系列"),
    ProviderPreset("百度千帆", "https://qianfan.baidubce.com/v2", ProviderType.OPENAI_COMPAT, "文心/千帆 v2"),
    ProviderPreset("Ollama 本地", "http://127.0.0.1:11434", ProviderType.OLLAMA, "本机/局域网"),
    ProviderPreset("LM Studio 本地", "http://127.0.0.1:1234/v1", ProviderType.OPENAI_COMPAT, "桌面端本地模型"),
    ProviderPreset("自定义", "", ProviderType.OPENAI_COMPAT, "任意 OpenAI 兼容端点"),
)
