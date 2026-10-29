package ai.devcodex.agent.provider

import ai.devcodex.agent.data.ModelTestResult
import ai.devcodex.agent.data.ProviderConfig
import ai.devcodex.agent.data.ProviderType
import ai.devcodex.agent.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 提供商网关：统一入口，按类型路由；网络读取统一在 IO 线程执行 */
object ProviderGateway {

    suspend fun listModels(p: ProviderConfig): List<String> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        DebugLog.i("Provider", "listModels start name=${p.name} type=${p.type} url=${p.baseUrl}")
        runCatching {
            when (p.type) {
                ProviderType.OPENAI_COMPAT -> OpenAiCompatProvider.listModels(p)
                ProviderType.ANTHROPIC -> AnthropicProvider.listModels(p)
                ProviderType.OLLAMA -> OllamaProvider.listModels(p)
            }
        }.onSuccess {
            DebugLog.i("Provider", "listModels ok name=${p.name} count=${it.size} cost=${System.currentTimeMillis() - t0}ms")
        }.onFailure {
            DebugLog.e("Provider", "listModels failed name=${p.name} cost=${System.currentTimeMillis() - t0}ms", it)
        }.getOrThrow()
    }

    suspend fun testModel(p: ProviderConfig, model: String): ModelTestResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        DebugLog.i("Provider", "testModel start name=${p.name} type=${p.type} model=$model")
        runCatching {
            when (p.type) {
                ProviderType.OPENAI_COMPAT -> OpenAiCompatProvider.testModel(p, model)
                ProviderType.ANTHROPIC -> AnthropicProvider.testModel(p, model)
                ProviderType.OLLAMA -> OllamaProvider.testModel(p, model)
            }
        }.onSuccess {
            DebugLog.i("Provider", "testModel done name=${p.name} model=$model result=$it cost=${System.currentTimeMillis() - t0}ms")
        }.onFailure {
            DebugLog.e("Provider", "testModel failed name=${p.name} model=$model cost=${System.currentTimeMillis() - t0}ms", it)
        }.getOrThrow()
    }

    fun chatRequest(p: ProviderConfig, model: String, systemPrompt: String, turns: List<ChatTurn>, tools: List<JsonObject>, stream: Boolean, reasoningEffort: String = "") = when (p.type) {
        ProviderType.OPENAI_COMPAT -> OpenAiCompatProvider.chatRequest(p, model, turns, tools, stream, reasoningEffort)
        ProviderType.ANTHROPIC -> AnthropicProvider.chatRequest(p, model, systemPrompt, turns, tools, stream, reasoningEffort)
        ProviderType.OLLAMA -> OllamaProvider.chatRequest(p, model, turns, tools, stream)
    }

    fun parseSseLine(p: ProviderConfig, line: String): List<StreamEvent> = when (p.type) {
        ProviderType.OPENAI_COMPAT -> OpenAiCompatProvider.parseSseLine(line)
        ProviderType.ANTHROPIC -> AnthropicProvider.parseSseLine(line)
        ProviderType.OLLAMA -> OllamaProvider.parseLine(line)
    }
}
