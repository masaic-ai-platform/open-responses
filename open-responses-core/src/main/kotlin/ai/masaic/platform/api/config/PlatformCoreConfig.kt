package ai.masaic.platform.api.config

import ai.masaic.openresponses.api.client.ResponseStore
import ai.masaic.platform.api.model.SystemSettings
import ai.masaic.platform.api.service.ModelService
import ai.masaic.platform.api.tools.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
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
    fun systemSettings() = SystemSettings(modelApiKey = modelApiKey, model = model)

    @Bean
    fun funDefGeneratorTool(
        @Lazy modelService: ModelService,
        systemSettings: SystemSettings,
    ) = FunDefGenerationTool(modelService, systemSettings)

    @Bean
    fun funReqGatheringTool(
        @Lazy modelService: ModelService,
        systemSettings: SystemSettings,
    ) = FunReqGatheringTool(modelService, systemSettings)

    @Bean
    fun mockFunSaveTool() = MockFunSaveTool()

    @Bean
    fun mockGenerationTool(
        @Lazy modelService: ModelService,
        systemSettings: SystemSettings,
    ) = MockGenerationTool(modelService, systemSettings)

    @Bean
    fun mockSaveTool() = MockSaveTool()

    @Bean
    fun platformNativeToolRegistry(
        objectMapper: ObjectMapper,
        responseStore: ResponseStore,
        funReqGatheringTool: FunReqGatheringTool,
        funDefGenerationTool: FunDefGenerationTool,
        mockFunSaveTool: MockFunSaveTool,
        mockGenerationTool: MockGenerationTool,
        mockSaveTool: MockSaveTool,
    ) = PlatformNativeToolRegistry(
        objectMapper,
        responseStore,
        funReqGatheringTool,
        funDefGenerationTool,
        mockFunSaveTool,
        mockGenerationTool,
        mockSaveTool,
    )
}
