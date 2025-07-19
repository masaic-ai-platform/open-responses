package ai.masaic.platform.api.config

import ai.masaic.openresponses.api.client.ResponseStore
import ai.masaic.platform.api.repository.*
import ai.masaic.platform.api.service.ModelService
import ai.masaic.platform.api.tools.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Profile
import org.springframework.data.mongodb.core.ReactiveMongoTemplate

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
    fun mockFunSaveTool(mockFunctionRepository: MockFunctionRepository) = MockFunSaveTool(mockFunctionRepository)

    @Bean
    fun mockGenerationTool(
        @Lazy modelService: ModelService,
        systemSettings: SystemSettings,
    ) = MockGenerationTool(modelService, systemSettings)

    @Bean
    fun mockSaveTool(mocksRepository: MocksRepository) = MockSaveTool(mocksRepository)

    @Bean
    fun platformNativeToolRegistry(
        objectMapper: ObjectMapper,
        responseStore: ResponseStore,
        platformNativeTools: List<PlatformNativeTool>,
    ) = PlatformNativeToolRegistry(
        objectMapper,
        responseStore,
        platformNativeTools,
    )

    @Bean
    fun mcpMockServerRepository(mongoTemplate: ReactiveMongoTemplate) = MongoMcpMockServerRepository(mongoTemplate)

    @Bean
    fun mockFunctionRepository(mongoTemplate: ReactiveMongoTemplate) = MongoMockFunctionRepository(mongoTemplate)

    @Bean
    fun mocksRepository(mongoTemplate: ReactiveMongoTemplate) = MongoMocksRepository(mongoTemplate)

    @Bean
    fun mcpClientFactory(
        mcpMockServerRepository: McpMockServerRepository,
        mockFunctionRepository: MockFunctionRepository,
        mocksRepository: MocksRepository,
        systemSettings: SystemSettings,
        @Lazy modelService: ModelService,
    ) = PlatformMcpClientFactory(mcpMockServerRepository, mockFunctionRepository, mocksRepository, systemSettings, modelService)

    @Bean
    fun platformMcpService(
        mcpMockServerRepository: McpMockServerRepository,
        mockFunctionRepository: MockFunctionRepository,
        mocksRepository: MocksRepository,
    ) = PlatformMcpService(mcpMockServerRepository, mockFunctionRepository, mocksRepository)
}

data class SystemSettings(
    val modelApiKey: String,
    val model: String,
)
