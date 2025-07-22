package ai.masaic.platform.api.service

import ai.masaic.openresponses.api.config.QdrantVectorProperties
import ai.masaic.openresponses.api.config.VectorSearchConfigProperties
import ai.masaic.openresponses.api.model.ChunkingStrategy
import ai.masaic.openresponses.api.repository.VectorStoreRepository
import ai.masaic.openresponses.api.service.VectorStoreFileManager
import ai.masaic.openresponses.api.service.embedding.EmbeddingService
import ai.masaic.openresponses.api.service.embedding.OpenAIProxyEmbeddingService
import ai.masaic.openresponses.api.service.search.HybridSearchServiceHelper
import ai.masaic.openresponses.api.service.search.QdrantVectorSearchProvider
import ai.masaic.openresponses.api.service.search.VectorStoreService
import ai.masaic.openresponses.api.support.service.TelemetryService
import ai.masaic.platform.api.config.ModelSettings
import io.qdrant.client.QdrantClient
import org.springframework.beans.factory.annotation.Autowired
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
}
