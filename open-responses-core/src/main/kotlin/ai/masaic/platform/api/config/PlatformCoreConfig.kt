package ai.masaic.platform.api.config

import ai.masaic.openresponses.api.client.ResponseStore
import ai.masaic.openresponses.api.config.QdrantVectorProperties
import ai.masaic.openresponses.api.config.VectorSearchConfigProperties
import ai.masaic.openresponses.api.model.ModelInfo
import ai.masaic.openresponses.api.repository.VectorStoreRepository
import ai.masaic.openresponses.api.service.VectorStoreFileManager
import ai.masaic.openresponses.api.service.embedding.EmbeddingService
import ai.masaic.openresponses.api.service.embedding.OpenAIProxyEmbeddingService
import ai.masaic.openresponses.api.service.rerank.RerankerService
import ai.masaic.openresponses.api.service.search.*
import ai.masaic.openresponses.api.support.service.TelemetryService
import ai.masaic.platform.api.repository.*
import ai.masaic.platform.api.service.ModelService
import ai.masaic.platform.api.service.PlatformHybridSearchService
import ai.masaic.platform.api.service.PlatformQdrantVectorSearchProvider
import ai.masaic.platform.api.service.PlatformVectorStoreService
import ai.masaic.platform.api.tools.*
import com.fasterxml.jackson.databind.ObjectMapper
import io.qdrant.client.QdrantClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Profile
import org.springframework.data.mongodb.core.ReactiveMongoTemplate

@Profile("platform")
@Configuration
class PlatformCoreConfig {
    @Value("\${platform.deployment.apiKey:na}")
    private val modelApiKey = ""

    @Value("\${platform.deployment.model:openai@gpt-4.1-mini}")
    private val model = "openai@gpt-4.1-mini"

    @Bean
    fun systemSettings(): ModelSettings = if (modelApiKey == "na") ModelSettings() else ModelSettings(SystemSettingsType.DEPLOYMENT_TIME, modelApiKey, model)

    @Bean
    fun funDefGeneratorTool(
        @Lazy modelService: ModelService,
        modelSettings: ModelSettings,
    ) = FunDefGenerationTool(modelService, modelSettings)

    @Bean
    fun funReqGatheringTool(
        @Lazy modelService: ModelService,
        modelSettings: ModelSettings,
    ) = FunReqGatheringTool(modelService, modelSettings)

    @Bean
    fun mockFunSaveTool(mockFunctionRepository: MockFunctionRepository) = MockFunSaveTool(mockFunctionRepository)

    @Bean
    fun mockGenerationTool(
        @Lazy modelService: ModelService,
        modelSettings: ModelSettings,
    ) = MockGenerationTool(modelService, modelSettings)

    @Bean
    fun mockSaveTool(mocksRepository: MocksRepository) = MockSaveTool(mocksRepository)

    @Bean
    fun modelTestTool() = ModelTestTool()

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
    fun mcpClientFactory(
        mcpMockServerRepository: McpMockServerRepository,
        mockFunctionRepository: MockFunctionRepository,
        mocksRepository: MocksRepository,
        modelSettings: ModelSettings,
        @Lazy modelService: ModelService,
    ) = PlatformMcpClientFactory(mcpMockServerRepository, mockFunctionRepository, mocksRepository, modelSettings, modelService)

    @Bean
    fun platformMcpService(
        mcpMockServerRepository: McpMockServerRepository,
        mockFunctionRepository: MockFunctionRepository,
        mocksRepository: MocksRepository,
    ) = PlatformMcpService(mcpMockServerRepository, mockFunctionRepository, mocksRepository)

    @Configuration
    @Profile("platform")
    @ConditionalOnProperty(name = ["open-responses.store.type"], havingValue = "mongodb")
    class MongoRepositoryConfiguration {
        @Bean
        fun mcpMockServerRepository(mongoTemplate: ReactiveMongoTemplate) = MongoMcpMockServerRepository(mongoTemplate)

        @Bean
        fun mockFunctionRepository(mongoTemplate: ReactiveMongoTemplate) = MongoMockFunctionRepository(mongoTemplate)

        @Bean
        fun mocksRepository(mongoTemplate: ReactiveMongoTemplate) = MongoMocksRepository(mongoTemplate)
    }

    @Configuration
    @Profile("platform")
    @ConditionalOnProperty(name = ["open-responses.store.type"], havingValue = "in-memory", matchIfMissing = true)
    class InMemoryRepositoryConfiguration {
        @Bean
        fun mcpMockServerRepository() = InMemoryMcpMockServerRepository()

        @Bean
        fun mockFunctionRepository() = InMemoryMockFunctionRepository()

        @Bean
        fun mocksRepository() = InMemoryMocksRepository()
    }

    @Configuration
    @Profile("platform")
    @ConditionalOnProperty(name = ["open-responses.store.vector.search.provider"], havingValue = "qdrant")
    class PlatformQdrantSetupConfiguration {
        @Bean
        fun platformVectorStoreService(
            vectorStoreFileManager: VectorStoreFileManager,
            vectorStoreRepository: VectorStoreRepository,
            vectorSearchProvider: PlatformQdrantVectorSearchProvider,
            telemetryService: TelemetryService,
            hybridSearchServiceHelper: HybridSearchServiceHelper,
        ) = PlatformVectorStoreService(
            vectorStoreFileManager,
            vectorStoreRepository,
            vectorSearchProvider,
            telemetryService,
            hybridSearchServiceHelper,
        )

        @Bean
        fun platformHybridSearchService(
            vectorSearchProvider: PlatformQdrantVectorSearchProvider,
            @Value("\${open-responses.store.vector.repository.type}")
            repositoryType: String,
            luceneIndexService: LuceneIndexService? = null,
            mongoTemplate: ReactiveMongoTemplate? = null,
            rerankerService: RerankerService? = null,
        ) = PlatformHybridSearchService(
            vectorSearchProvider,
            repositoryType,
            luceneIndexService,
            mongoTemplate,
            rerankerService,
        )

        @Bean
        fun platformQdrantVectorSearchProvider(
            embeddingService: EmbeddingService,
            qdrantProperties: QdrantVectorProperties,
            vectorSearchProperties: VectorSearchConfigProperties,
            hybridSearchServiceHelper: HybridSearchServiceHelper,
            client: QdrantClient,
            proxyEmbeddingService: OpenAIProxyEmbeddingService,
        ) = PlatformQdrantVectorSearchProvider(embeddingService, qdrantProperties, vectorSearchProperties, hybridSearchServiceHelper, client, proxyEmbeddingService)
    }

    @Configuration
    @Profile("platform")
    @ConditionalOnProperty(name = ["open-responses.store.vector.search.provider"], havingValue = "file", matchIfMissing = true)
    class PlatformWithoutQdrantConfiguration {
        @Bean
        fun platformVectorStoreService(
            vectorStoreFileManager: VectorStoreFileManager,
            vectorStoreRepository: VectorStoreRepository,
            vectorSearchProvider: VectorSearchProvider,
            telemetryService: TelemetryService,
            hybridSearchServiceHelper: HybridSearchServiceHelper,
        ) = VectorStoreService(
            vectorStoreFileManager,
            vectorStoreRepository,
            vectorSearchProvider,
            telemetryService,
            hybridSearchServiceHelper,
        )

        @Bean
        fun platformHybridSearchService(
            vectorSearchProvider: VectorSearchProvider,
            @Value("\${open-responses.store.vector.repository.type}")
            repositoryType: String,
            luceneIndexService: LuceneIndexService? = null,
            mongoTemplate: ReactiveMongoTemplate? = null,
            rerankerService: RerankerService? = null,
        ) = HybridSearchService(
            vectorSearchProvider,
            repositoryType,
            luceneIndexService,
            mongoTemplate,
            rerankerService,
        )
    }
}

data class ModelSettings(
    val settingsType: SystemSettingsType,
    var apiKey: String,
    var model: String,
) {
    var bearerToken: String
    var qualifiedModelName: String = model

    init {
        if (model.contains("@")) model = model.split("@")[1]
        if (apiKey.startsWith("Bearer ")) apiKey = apiKey.removePrefix("Bearer ").trim()

        bearerToken = "Bearer $apiKey"
    }

    constructor() : this(SystemSettingsType.RUNTIME, "", "")
    constructor(modelApiKey: String, model: String) : this(SystemSettingsType.RUNTIME, modelApiKey, model)

    fun resolveSystemSettings(modelInfo: ModelInfo?): ModelSettings =
        if (this.settingsType == SystemSettingsType.DEPLOYMENT_TIME) {
            this
        } else {
            requireNotNull(modelInfo) { "apiKey and model is required" }
            requireNotNull(modelInfo.bearerToken) { "apiKey required, can't be null or blank" }
            requireNotNull(modelInfo.model) { "model required, can't be null or blank" }
            ModelSettings(modelInfo.bearerToken, modelInfo.model)
        }

    fun resolveSystemSettings(modelSettings: ModelSettings?): ModelSettings =
        if (this.settingsType == SystemSettingsType.DEPLOYMENT_TIME) {
            this
        } else {
            requireNotNull(modelSettings) { "apiKey and model is required" }
            requireNotNull(modelSettings.apiKey) { "apiKey required, can't be null or blank" }
            requireNotNull(modelSettings.model) { "model required, can't be null or blank" }
            ModelSettings(modelSettings.apiKey, modelSettings.model)
        }
}

enum class SystemSettingsType {
    RUNTIME,
    DEPLOYMENT_TIME,
}
