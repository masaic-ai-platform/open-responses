package ai.masaic.openresponses.api.service.search

import ai.masaic.openresponses.api.model.ComparisonFilter
import ai.masaic.openresponses.api.model.CompoundFilter
import ai.masaic.openresponses.api.model.Filter
import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import ai.masaic.openresponses.api.model.VectorStoreSearchResultContent
import ai.masaic.openresponses.api.utils.FilterUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.index.ReactiveIndexOperations
import org.springframework.data.mongodb.core.index.TextIndexDefinition
import org.springframework.data.mongodb.core.index.TextIndexed
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.TextCriteria
import org.springframework.data.mongodb.core.query.TextQuery
import org.springframework.stereotype.Service

/**
 * Hybrid search service combining vector similarity with Lucene and Mongo full-text search.
 */
@Service
class HybridSearchService
    @Autowired
    constructor(
        private val vectorSearchProvider: VectorSearchProvider,
        @Value("\${open-responses.store.vector.repository.type}")
        private val repositoryType: String,
        @Autowired(required = false)
        private val luceneIndexService: LuceneIndexService? = null,
        @Autowired(required = false)
        private val mongoTemplate: ReactiveMongoTemplate? = null,
    ) {
        private val log = LoggerFactory.getLogger(HybridSearchService::class.java)

        init {
            // Only initialize MongoDB text index if MongoDB is active
            if (repositoryType == "mongodb" && mongoTemplate != null) {
                val indexOps: ReactiveIndexOperations = mongoTemplate.indexOps(MongoChunk::class.java)
                val textIndex =
                    TextIndexDefinition
                        .builder()
                        .onField("content")
                        .build()
                indexOps.ensureIndex(textIndex).subscribe()
            }
        }

        /**
         * Perform a hybrid search.
         * @param query the user query text
         * @param alpha weighting factor between [0,1] for vector vs text scores
         * @param vectorStoreIds optional list of vector store IDs to limit search to
         */
        suspend fun hybridSearch(
            query: String,
            maxResults: Int,
            userFilter: Filter?,
            vectorStoreIds: List<String>,
            alpha: Double = 0.5,
        ): List<VectorStoreSearchResult> =
            coroutineScope {
                // Create filter for vector store IDs if provided
                val vectorStoreFilter: Filter? =
                    vectorStoreIds.takeIf { it.isNotEmpty() }?.let { ids ->
                        if (ids.size == 1) {
                            // For a single ID, use a simple comparison filter
                            ComparisonFilter(
                                key = "vector_store_id",
                                type = "eq",
                                value = ids.first(),
                            )
                        } else {
                            // For multiple IDs, create an OR compound filter
                            CompoundFilter(
                                type = "or",
                                filters =
                                    ids.map { id ->
                                        ComparisonFilter(
                                            key = "vector_store_id",
                                            type = "eq",
                                            value = id,
                                        )
                                    },
                            )
                        }
                    }

                val vectorDeferred =
                    async { 
                        // Convert VectorSearchProvider.SearchResult to VectorStoreSearchResult
                        vectorSearchProvider
                            .searchSimilar(
                                query = query,
                                maxResults = maxResults,
                                rankingOptions = null,
                                filter = CompoundFilter(type = "and", filters = listOfNotNull(userFilter, vectorStoreFilter)),
                            ).map { result ->
                                VectorStoreSearchResult(
                                    fileId = result.fileId,
                                    filename = result.metadata["filename"] as? String ?: "",
                                    score = result.score,
                                    content = listOf(VectorStoreSearchResultContent("text", result.content)),
                                    attributes = result.metadata,
                                )
                            }
                    }
                // Only run Lucene search if using file repository and luceneIndexService is available
                val luceneResults =
                    if (repositoryType == "file" && luceneIndexService != null) {
                        async { luceneIndexService.search(query, maxResults, vectorStoreIds) }.await()
                    } else {
                        emptyList()
                    }
        
                // Only run MongoDB search if using mongodb repository and mongoTemplate is available
                val mongoResults =
                    if (repositoryType == "mongodb" && mongoTemplate != null) {
                        val criteria = TextCriteria.forDefaultLanguage().matching(query)
                        val textQuery = TextQuery(criteria).limit(maxResults)
            
                        // Apply vectorStoreId filter if specified
                        if (vectorStoreIds.isNotEmpty()) {
                            textQuery.addCriteria(Criteria.where("vectorStoreId").`in`(vectorStoreIds))
                        }

                        // Apply userFilter if provided
                        if (userFilter != null) {
                            val criteria = FilterUtils.buildCriteriaFromFilter(userFilter)
                                ?: throw IllegalArgumentException("Failed to parse user filter: $userFilter. This may impact security filters.")
                            textQuery.addCriteria(criteria)
                        }

                        val chunks = mongoTemplate.find(textQuery, MongoChunk::class.java).collectList().awaitSingle()
                        chunks.map { chunk ->
                            VectorStoreSearchResult(
                                fileId = chunk.fileId,
                                filename = chunk.filename,
                                score = 0.0,
                                content = listOf(VectorStoreSearchResultContent("text", chunk.content)),
                                attributes =
                                    mapOf(
                                        "chunk_id" to chunk.id,
                                        "chunk_index" to chunk.chunkIndex,
                                    ),
                            )
                        }
                    } else {
                        emptyList()
                    }

                val vectorResults = vectorDeferred.await()

                // Merge results and normalize scores
                val allResults = mutableMapOf<String, MergedResult>()
        
                // Add vector results
                vectorResults.forEach { result ->
                    val key = getResultKey(result)
                    allResults[key] =
                        MergedResult(
                            result = result,
                            vectorScore = result.score,
                            textScore = 0.0,
                        )
                }
        
                // Add Lucene results 
                luceneResults.forEach { result ->
                    val key = getResultKey(result)
                    val existing = allResults[key]
                    if (existing != null) {
                        allResults[key] = existing.copy(textScore = result.score)
                    } else {
                        allResults[key] =
                            MergedResult(
                                result = result,
                                vectorScore = 0.0,
                                textScore = result.score,
                            )
                    }
                }
        
                // Add MongoDB results
                mongoResults.forEach { result ->
                    val key = getResultKey(result)
                    val existing = allResults[key]
                    if (existing != null && existing.textScore == 0.0) {
                        allResults[key] = existing.copy(textScore = result.score)
                    } else if (existing == null) {
                        allResults[key] =
                            MergedResult(
                                result = result,
                                vectorScore = 0.0,
                                textScore = result.score,
                            )
                    }
                }
        
                // Calculate combined scores and sort results
                allResults.values
                    .map { merged ->
                        merged.result.copy(
                            score = alpha * merged.vectorScore + (1 - alpha) * merged.textScore,
                        )
                    }.sortedByDescending { it.score }
            }

        /**
         * Get a unique key to identify a result across different sources
         */
        private fun getResultKey(result: VectorStoreSearchResult): String {
            val fileId = result.fileId
            val chunkId =
                result.attributes?.get("chunk_id") as? String 
                    ?: (result.attributes?.get("chunk_index") as? Int?)?.toString()
                    ?: result.content
                        .firstOrNull()
                        ?.text
                        ?.hashCode()
                        ?.toString()
                    ?: fileId
            return "$fileId-$chunkId"
        }

        /**
         * Helper class for merging results from different sources
         */
        private data class MergedResult(
            val result: VectorStoreSearchResult,
            val vectorScore: Double,
            val textScore: Double,
        )

        /**
         * Simple data model for chunks to be indexed into MongoDB
         */
        data class ChunkForIndexing(
            val chunkId: String,
            val vectorStoreId: String,
            val fileId: String,
            val filename: String,
            val chunkIndex: Int,
            val content: String,
        )
    }

/**
 * MongoDB document for storing text chunks for full-text search.
 */
@Document(collection = "mongo_chunks")
data class MongoChunk(
    @Id val id: String,
    val vectorStoreId: String,
    val fileId: String,
    val filename: String,
    val chunkIndex: Int,
    @TextIndexed val content: String,
) 
