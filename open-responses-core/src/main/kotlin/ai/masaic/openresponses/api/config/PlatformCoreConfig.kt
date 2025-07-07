package ai.masaic.openresponses.api.config

import ai.masaic.openresponses.api.model.StaticChunkingConfig
import ai.masaic.platform.api.model.SystemSettings
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Profile("platform")
@Configuration
class PlatformCoreConfig {
    @Value("\${open-responses.embeddings.max_chunk_size_tokens:1000}")
    private val maxChunkSizeTokens: Int = 1000

    @Value("\${open-responses.embeddings.chunk_overlap_tokens:200}")
    private val chunkOverlapTokens: Int = 200

    @Value("\${platform.deployment.apiKey}")
    private val modelApiKey = ""

    @Value("\${platform.deployment.model:openai@gpt-4.1-mini}")
    private val model = "openai@gpt-4.1-mini"

    @Bean
    fun staticChunkingConfig() = StaticChunkingConfig(maxChunkSizeTokens, chunkOverlapTokens)

    @Bean
    fun systemSettings() = SystemSettings(modelApiKey = modelApiKey, model = model)
}
