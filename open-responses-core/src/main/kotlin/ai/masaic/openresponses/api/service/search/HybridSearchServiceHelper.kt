package ai.masaic.openresponses.api.service.search

import ai.masaic.openresponses.api.service.search.HybridSearchService.ChunkForIndexing
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.index.ReactiveIndexOperations
import org.springframework.data.mongodb.core.index.TextIndexDefinition
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.stereotype.Service

/**
 * Hybrid search service combining vector similarity with Lucene and Mongo full-text search.
 */
@Service
class HybridSearchServiceHelper
    @Autowired
    constructor(
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
         * Main indexing method for chunks - this abstracts away the details of text indexing
         * It will handle indexing into appropriate stores based on repository type
         *
         * @param chunks List of chunks to index
         */
        suspend fun indexChunks(chunks: List<ChunkForIndexing>) {
            if (chunks.isEmpty()) return

            // Log indexing action
            log.info("Indexing ${chunks.size} chunks for hybrid search, repository type: $repositoryType")

            // If using file repository, index in Lucene
            if (repositoryType == "file" && luceneIndexService != null) {
                try {
                    // Convert to Lucene chunks
                    val luceneChunks =
                        chunks.map { chunk ->
                            LuceneChunk(
                                chunkId = chunk.chunkId,
                                fileId = chunk.fileId,
                                filename = chunk.filename,
                                vectorStoreId = chunk.vectorStoreId,
                                chunkIndex = chunk.chunkIndex,
                                content = chunk.content,
                            )
                        }

                    // Index in Lucene
                    luceneIndexService.indexChunks(luceneChunks)
                    log.info("Indexed ${chunks.size} chunks in Lucene")
                } catch (e: Exception) {
                    log.error("Error indexing chunks in Lucene: ${e.message}", e)
                    throw e
                }
            }

            // If using MongoDB repository, index in MongoDB
            if (repositoryType == "mongodb" && mongoTemplate != null) {
                try {
                    // Convert to MongoChunk documents
                    val mongoChunks =
                        chunks.map { chunk ->
                            MongoChunk(
                                id = chunk.chunkId,
                                vectorStoreId = chunk.vectorStoreId,
                                fileId = chunk.fileId,
                                filename = chunk.filename,
                                chunkIndex = chunk.chunkIndex,
                                content = chunk.content,
                            )
                        }

                    // Save to MongoDB
                    mongoTemplate.insertAll(mongoChunks).collectList().awaitSingle()
                    log.info("Indexed ${chunks.size} chunks in MongoDB")
                } catch (e: Exception) {
                    log.error("Error indexing chunks in MongoDB: ${e.message}", e)
                    throw e
                }
            }
        }

        /**
         * Delete chunks for a file from all text search stores
         */
        suspend fun deleteFileChunks(
            fileId: String,
            vectorStoreId: String? = null,
        ) {
            // If using MongoDB repository, delete from MongoDB
            if (repositoryType == "mongodb" && mongoTemplate != null) {
                try {
                    val criteria = Criteria.where("fileId").`is`(fileId)

                    // Add vectorStoreId filter if provided
                    if (vectorStoreId != null) {
                        criteria.and("vectorStoreId").`is`(vectorStoreId)
                    }

                    val query =
                        org.springframework.data.mongodb.core.query
                            .Query(criteria)
                    mongoTemplate.remove(query, MongoChunk::class.java).awaitSingle()
                    log.info("Deleted chunks for file $fileId from MongoDB")
                } catch (e: Exception) {
                    log.error("Error deleting chunks for file $fileId from MongoDB: ${e.message}", e)
                }
            }
        }
    }
