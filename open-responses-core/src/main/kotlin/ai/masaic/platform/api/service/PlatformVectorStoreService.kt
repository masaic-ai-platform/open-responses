package ai.masaic.platform.api.service

import ai.masaic.openresponses.api.config.QdrantVectorProperties
import ai.masaic.openresponses.api.config.VectorSearchConfigProperties
import ai.masaic.openresponses.api.model.ChunkingStrategy
import ai.masaic.openresponses.api.model.CompoundFilter
import ai.masaic.openresponses.api.model.ModelInfo
import ai.masaic.openresponses.api.model.VectorStoreSearchRequest
import ai.masaic.openresponses.api.repository.VectorStoreRepository
import ai.masaic.openresponses.api.service.VectorStoreFileManager
import ai.masaic.openresponses.api.service.embedding.EmbeddingService
import ai.masaic.openresponses.api.service.embedding.OpenAIProxyEmbeddingService
import ai.masaic.openresponses.api.service.rerank.RerankerService
import ai.masaic.openresponses.api.service.search.*
import ai.masaic.openresponses.api.support.service.TelemetryService
import ai.masaic.platform.api.config.ModelSettings
import io.qdrant.client.QdrantClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import java.io.InputStream

class PlatformVectorStoreService(
    private val vectorStoreFileManager: VectorStoreFileManager,
    private val vectorStoreRepository: VectorStoreRepository,
    private val vectorSearchProvider: PlatformQdrantVectorSearchProvider,
    private val telemetryService: TelemetryService,
    @Autowired(required = false) private val hybridSearchServiceHelper: HybridSearchServiceHelper,
) : VectorStoreService(
        vectorStoreFileManager,
        vectorStoreRepository,
        vectorSearchProvider,
        telemetryService,
        hybridSearchServiceHelper,
    ) {
    override fun indexFile(
        fileId: String,
        content: InputStream,
        filename: String,
        chunkingStrategy: ChunkingStrategy?,
        attributes: Map<String, Any>?,
        vectorStoreId: String,
        modelSettings: ModelSettings?,
    ): Boolean =
        vectorSearchProvider.indexWithModelInfo(
            fileId,
            content,
            filename,
            chunkingStrategy,
            true,
            attributes,
            vectorStoreId,
            modelSettings,
        )

    override fun searchSimilar(
        vectorStoreId: String,
        filter: CompoundFilter,
        request: VectorStoreSearchRequest,
    ): List<VectorSearchProvider.SearchResult> =
        vectorSearchProvider.searchSimilar(
            query = request.query,
            maxResults = request.maxNumResults ?: 10,
            rankingOptions = request.rankingOptions,
            filter = filter,
            ModelInfo.modelSettings(request.modelInfo),
        )
}

class PlatformQdrantVectorSearchProvider(
    embeddingService: EmbeddingService,
    qdrantProperties: QdrantVectorProperties,
    vectorSearchProperties: VectorSearchConfigProperties,
    hybridSearchServiceHelper: HybridSearchServiceHelper,
    client: QdrantClient,
    private val proxyEmbeddingService: OpenAIProxyEmbeddingService,
) : QdrantVectorSearchProvider(embeddingService, qdrantProperties, vectorSearchProperties, hybridSearchServiceHelper, client) {
    override fun embeddings(
        chunkTexts: List<String>,
        modelSettings: ModelSettings?,
    ): List<List<Float>> =
        if (modelSettings == null) {
            super.embeddings(chunkTexts, null)
        } else {
            proxyEmbeddingService.embedTexts(texts = chunkTexts, apiKey = modelSettings.apiKey, modelName = modelSettings.qualifiedModelName)
        }

    override fun embedding(
        query: String,
        modelSettings: ModelSettings?,
    ): List<Float> =
        if (modelSettings == null) {
            super.embedding(query, null)
        } else {
            proxyEmbeddingService.embedText(text = query, apiKey = modelSettings.apiKey, modelName = modelSettings.qualifiedModelName)
        }
}

class PlatformHybridSearchService(
    private val vectorSearchProvider: PlatformQdrantVectorSearchProvider,
    @Value("\${open-responses.store.vector.repository.type}")
    private val repositoryType: String,
    @Autowired(required = false)
    private val luceneIndexService: LuceneIndexService? = null,
    @Autowired(required = false)
    private val mongoTemplate: ReactiveMongoTemplate? = null,
    @Autowired(required = false)
    private val rerankerService: RerankerService? = null,
) : HybridSearchService(vectorSearchProvider, repositoryType, luceneIndexService, mongoTemplate, rerankerService) {
    override fun searchSimilar(
        query: String,
        maxResults: Int,
        filter: CompoundFilter,
        modelSettings: ModelSettings?,
    ) = vectorSearchProvider.searchSimilarWithModelInfo(
        query = query,
        maxResults = maxResults,
        rankingOptions = null,
        filter = filter,
        modelSettings = modelSettings,
    )
}
