package ai.masaic.openresponses.api.service.search

import ai.masaic.openresponses.api.model.ComparisonFilter
import ai.masaic.openresponses.api.model.CompoundFilter
import ai.masaic.openresponses.api.model.Filter
import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import ai.masaic.openresponses.api.model.VectorStoreSearchResultContent
import ai.masaic.openresponses.api.service.rerank.RerankerService
import ai.masaic.openresponses.api.utils.FilterUtils
import ai.masaic.platform.api.config.ModelSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.index.ReactiveIndexOperations
import org.springframework.data.mongodb.core.index.TextIndexDefinition
import org.springframework.data.mongodb.core.index.TextIndexed
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field
import org.springframework.data.mongodb.core.mapping.TextScore
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.TextCriteria
import org.springframework.data.mongodb.core.query.TextQuery
import org.springframework.stereotype.Service

/**
 * Hybrid search service combining vector similarity with Lucene and Mongo full-text search.
 */
@Service
@Profile("!platform")
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
        @Autowired(required = false)
        private val rerankerService: RerankerService? = null,
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
            modelSettings: ModelSettings?,
        ): List<VectorStoreSearchResult> =
            coroutineScope {
                if (query.isBlank()) {
                    return@coroutineScope emptyList()
                }

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
                        searchSimilar(
                            query = query,
                            maxResults = maxResults,
                            CompoundFilter(type = "and", filters = listOfNotNull(userFilter, vectorStoreFilter)),
                            modelSettings = modelSettings,
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
                        // Create text criteria
                        val textCriteria = TextCriteria.forDefaultLanguage().matching(query)
                        
                        // Build a list of additional criteria
                        val additionalCriteria = mutableListOf<Criteria>()
                        
                        // Add vectorStoreId criteria if needed
                        if (vectorStoreIds.isNotEmpty()) {
                            additionalCriteria.add(Criteria.where("vector_store_id").`in`(vectorStoreIds))
                        }
                        
                        // Apply userFilter if provided
                        userFilter?.let {
                            val filterCriteria = FilterUtils.buildCriteriaFromFilter(it)
                            if (filterCriteria != null) {
                                additionalCriteria.add(filterCriteria)
                            } else {
                                log.warn("Failed to parse user filter: $it. This may impact security filters.")
                            }
                        }
                        
                        // Build the text query
                        val textQuery = TextQuery(textCriteria).sortByScore().includeScore().limit(maxResults)
                        
                        // Add combined criteria if we have any
                        if (additionalCriteria.isNotEmpty()) {
                            val combinedCriteria =
                                if (additionalCriteria.size == 1) {
                                    additionalCriteria.first()
                                } else {
                                    // Use a flat andOperator to avoid nested $and in the query
                                    Criteria().andOperator(*additionalCriteria.toTypedArray())
                                }
                            textQuery.addCriteria(combinedCriteria)
                        }
                        
                        // Work with raw Document instead of MongoChunk to properly handle the score
                        val documents = mongoTemplate.find(textQuery, org.bson.Document::class.java, "mongo_chunks").collectList().awaitSingle()
                        
                        // Log for debugging
                        log.debug("MongoDB text search returned ${documents.size} results")
                        
                        documents.map { doc ->
                            VectorStoreSearchResult(
                                fileId = doc.getString("file_id"),
                                filename = doc.getString("filename"),
                                score = doc.getDouble("score") ?: 0.0,
                                content = listOf(VectorStoreSearchResultContent("text", doc.getString("content"))),
                                attributes =
                                    mapOf(
                                        "chunk_id" to doc.getString("chunk_id"),
                                        "chunk_index" to (doc.getInteger("chunk_index") ?: 0),
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
                val maxVec =
                    allResults.values.takeIf { it.isNotEmpty() }?.maxOf { it.vectorScore }
                        ?: 0.0
                val maxTxt =
                    allResults.values.takeIf { it.isNotEmpty() }?.maxOf { it.textScore }
                        ?: 0.0

                // Dynamic α: if a reranker is configured, ignore the caller‑provided alpha and
                // fall back to a constant 0.5 that’s only used for the pre‑rerank pruning step.
                val blendAlpha = if (rerankerService != null) 0.5 else alpha

                val prelimRanked =
                    allResults.values
                        .map { m ->
                            val v = if (maxVec > 0) m.vectorScore / maxVec else 0.0
                            val t = if (maxTxt > 0) m.textScore / maxTxt else 0.0
                            m.result.copy(score = blendAlpha * v + (1 - blendAlpha) * t)
                        }.sortedByDescending { it.score }

                return@coroutineScope withContext(Dispatchers.IO) {
                    rerankerService
                        ?.rerank(query, prelimRanked)
                }?.take(maxResults) ?: prelimRanked.take(maxResults)
            }

        protected fun searchSimilar(
            query: String,
            maxResults: Int,
            filter: CompoundFilter,
            modelSettings: ModelSettings?,
        ) = vectorSearchProvider
            .searchSimilar(
                query = query,
                maxResults = maxResults,
                rankingOptions = null,
                filter,
            )

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
    @Field("vector_store_id")
    val vectorStoreId: String,
    @Field("file_id")
    val fileId: String,
    val filename: String,
    @Field("chunk_index")
    val chunkIndex: Int,
    @Field("chunk_id")
    val chunkId: String = id,
    @TextIndexed val content: String,
) {
    @TextScore
    var score: Double = 0.0
}
