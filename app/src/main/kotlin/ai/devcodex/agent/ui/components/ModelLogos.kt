package ai.devcodex.agent.ui.components

import ai.devcodex.agent.R

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** AI 品牌 Logo（@lobehub/icons MIT 光栅化位图——渲染零兼容问题） */
object ModelLogos {

    private fun String.hasWord(w: String): Boolean =
        Regex("(?i)(^|[^a-z])" + Regex.escape(w) + "([^a-z]|$)").containsMatchIn(this)

    private fun String.hasBrand(b: String): Boolean =
        contains(b, ignoreCase = true) || hasWord(b.replace(" ", "").replace("-", ""))

    fun resFor(model: String): Int = when {
        model.hasWord("xiaomimimo") || model.hasWord("mimo") -> R.drawable.nm_xiaomimimo
        model.hasWord("gpt") || model.hasWord("openai") || Regex("(?i)\\bo[1345]\\b").containsMatchIn(model) -> R.drawable.nm_openai
        model.hasWord("claude") -> R.drawable.nm_claude
        model.hasWord("anthropic") -> R.drawable.nm_anthropic
        model.hasWord("deepseek") || model.contains("深度求索") || model.contains("深度") -> R.drawable.nm_deepseek
        model.hasWord("gemini") || model.contains("google") -> R.drawable.nm_gemini
        model.hasWord("glm") || model.hasWord("zhipu") || model.hasWord("chatglm") || model.contains("智谱") -> R.drawable.nm_zhipu
        model.hasWord("qwen") || model.hasWord("qwq") || model.hasWord("alibaba") || model.contains("通义") || model.contains("千问") || model.hasWord("qvq") || model.hasBrand("dash scope") -> R.drawable.nm_qwen
        model.hasWord("siliconcloud") || model.hasWord("siliconflow") || model.hasBrand("silicon cloud") -> R.drawable.nm_siliconcloud
        model.hasBrand("lm studio") || model.hasWord("lmstudio") -> R.drawable.nm_lmstudio
        model.hasWord("kimi") || model.hasWord("moonshot") || model.contains("月之暗面") -> R.drawable.nm_kimi
        model.hasWord("doubao") || model.hasWord("bytedance") || model.contains("豆包") -> R.drawable.nm_doubao
        model.hasWord("step") -> R.drawable.nm_stepfun
        model.hasWord("minimax") || model.hasWord("abab") -> R.drawable.nm_minimax
        model.hasWord("grok") -> R.drawable.nm_grok
        model.hasWord("xai") -> R.drawable.nm_xai
        model.hasWord("nvidia") || model.hasWord("nemotron") -> R.drawable.nm_nvidia
        model.hasWord("ollama") || model.hasWord("llama") -> R.drawable.nm_ollama
        model.hasWord("openrouter") -> R.drawable.nm_openrouter
        model.hasWord("huggingface") || model.hasWord("hf") -> R.drawable.nm_huggingface
        model.hasWord("mistral") || model.hasWord("mixtral") -> R.drawable.nm_mistral
        model.hasWord("baichuan") -> R.drawable.nm_baichuan
        model.hasWord("agnes") -> R.drawable.nm_agnes
        model.hasWord("perplexity") -> R.drawable.nm_perplexity
        model.hasWord("cohere") || model.hasWord("command") -> R.drawable.nm_cohere
        model.hasWord("yi") || model.hasWord("01ai") -> R.drawable.nm_yi
        model.hasWord("hunyuan") || model.hasWord("tencent") || model.contains("混元") || model.contains("腾讯") -> R.drawable.nm_hunyuan
        model.hasWord("spark") || model.contains("星火") -> R.drawable.nm_spark
        model.hasWord("wenxin") || model.hasWord("ernie") || model.hasWord("baidu") || model.contains("文心") || model.contains("千帆") || model.contains("百度") -> R.drawable.nm_baidu
        model.hasWord("copilot") -> R.drawable.nm_copilot
        model.hasWord("groq") -> R.drawable.nm_groq
        model.hasWord("together") -> R.drawable.nm_together
        model.hasWord("fireworks") -> R.drawable.nm_fireworks
        model.hasWord("midjourney") || model.hasWord("mj") -> R.drawable.nm_midjourney
        model.hasWord("suno") -> R.drawable.nm_suno
        model.hasWord("kling") || model.contains("可灵") -> R.drawable.nm_kling
        model.hasWord("luma") -> R.drawable.nm_luma
        model.hasWord("microsoft") || model.hasWord("phi") || model.hasWord("wizardlm") -> R.drawable.nm_microsoft
        model.hasWord("codex") -> R.drawable.nm_codex
        else -> R.drawable.nm_openai
    }
}

@Composable
fun ModelLogo(model: String, size: androidx.compose.ui.unit.Dp = 28.dp) {
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(ModelLogos.resFor(model)),
            contentDescription = model,
            modifier = Modifier.size(size * 0.68f),
        )
    }
}
