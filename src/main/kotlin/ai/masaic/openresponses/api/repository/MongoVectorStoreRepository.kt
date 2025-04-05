package ai.masaic.openresponses.api.repository

import ai.masaic.openresponses.api.model.VectorStore
import ai.masaic.openresponses.api.model.VectorStoreFile
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.find
import org.springframework.data.mongodb.core.findById
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.remove
import org.springframework.stereotype.Repository

/**
 * MongoDB implementation of VectorStoreRepository.
 *
 * This implementation stores vector store metadata and vector store file metadata in MongoDB.
 * It does not handle the actual file content, which is managed by FileStorageService.
 * 
 * It is only enabled when open-responses.vector-store.repository.type=mongodb
 */
@Repository
@ConditionalOnProperty(name = ["open-responses.vector-store.repository.type"], havingValue = "mongodb")
class MongoVectorStoreRepository(
    private val mongoTemplate: ReactiveMongoTemplate,
) : VectorStoreRepository {
    private val log = LoggerFactory.getLogger(MongoVectorStoreRepository::class.java)

    companion object {
        const val VECTOR_STORE_COLLECTION = "vector_stores"
        const val VECTOR_STORE_FILE_COLLECTION = "vector_store_files"
    }

    override suspend fun saveVectorStore(vectorStore: VectorStore): VectorStore =
        try {
            mongoTemplate.save(vectorStore, VECTOR_STORE_COLLECTION).awaitFirst().also {
                log.info("Saved vector store metadata ${vectorStore.id}")
            }
        } catch (e: Exception) {
            log.error("Error saving vector store metadata ${vectorStore.id}", e)
            throw e
        }

    override suspend fun findVectorStoreById(vectorStoreId: String): VectorStore? =
        try {
            mongoTemplate.findById<VectorStore>(vectorStoreId, VECTOR_STORE_COLLECTION).awaitFirstOrNull()
        } catch (e: Exception) {
            log.error("Error reading vector store metadata $vectorStoreId", e)
            null
        }

    override suspend fun listVectorStores(
        limit: Int,
        order: String,
        after: String?,
        before: String?,
    ): List<VectorStore> =
        try {
            val query = Query()
            
            // Apply pagination
            if (after != null) {
                val afterStore = mongoTemplate.findById<VectorStore>(after, VECTOR_STORE_COLLECTION).awaitFirstOrNull()
                afterStore?.let {
                    if (order.equals("asc", ignoreCase = true)) {
                        query.addCriteria(Criteria.where("createdAt").gt(it.createdAt))
                    } else {
                        query.addCriteria(Criteria.where("createdAt").lt(it.createdAt))
                    }
                }
            } else if (before != null) {
                val beforeStore = mongoTemplate.findById<VectorStore>(before, VECTOR_STORE_COLLECTION).awaitFirstOrNull()
                beforeStore?.let {
                    if (order.equals("asc", ignoreCase = true)) {
                        query.addCriteria(Criteria.where("createdAt").lt(it.createdAt))
                    } else {
                        query.addCriteria(Criteria.where("createdAt").gt(it.createdAt))
                    }
                }
            }
            
            // Apply sorting
            val direction = if (order.equals("asc", ignoreCase = true)) Sort.Direction.ASC else Sort.Direction.DESC
            query.with(Sort.by(direction, "createdAt"))
            
            // Apply limit
            query.with(PageRequest.of(0, limit))
            
            mongoTemplate.find<VectorStore>(query, VECTOR_STORE_COLLECTION).collectList().awaitFirst()
        } catch (e: Exception) {
            log.error("Error listing vector stores", e)
            emptyList()
        }

    override suspend fun deleteVectorStore(vectorStoreId: String): Boolean =
        try {
            // Delete the vector store
            val vectorStoreQuery = Query.query(Criteria.where("_id").`is`(vectorStoreId))
            val deletedVectorStore = mongoTemplate.remove<VectorStore>(vectorStoreQuery, VECTOR_STORE_COLLECTION).awaitFirst()
            
            if (deletedVectorStore.deletedCount > 0) {
                // Delete all associated vector store files
                val vectorStoreFilesQuery = Query.query(Criteria.where("vectorStoreId").`is`(vectorStoreId))
                mongoTemplate.remove<VectorStoreFile>(vectorStoreFilesQuery, VECTOR_STORE_FILE_COLLECTION).awaitFirst()
                
                log.info("Deleted vector store metadata $vectorStoreId")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            log.error("Error deleting vector store metadata $vectorStoreId", e)
            false
        }

    override suspend fun saveVectorStoreFile(vectorStoreFile: VectorStoreFile): VectorStoreFile =
        try {
            mongoTemplate.save(vectorStoreFile, VECTOR_STORE_FILE_COLLECTION).awaitFirst().also {
                log.info("Saved vector store file metadata ${vectorStoreFile.id} for vector store ${vectorStoreFile.vectorStoreId}")
            }
        } catch (e: Exception) {
            log.error("Error saving vector store file metadata ${vectorStoreFile.id}", e)
            throw e
        }

    override suspend fun findVectorStoreFileById(
        vectorStoreId: String,
        fileId: String,
    ): VectorStoreFile? =
        try {
            val query =
                Query.query(
                    Criteria
                        .where("_id")
                        .`is`(fileId)
                        .and("vectorStoreId")
                        .`is`(vectorStoreId),
                )
            mongoTemplate.findOne(query, VectorStoreFile::class.java, VECTOR_STORE_FILE_COLLECTION).awaitFirstOrNull()
        } catch (e: Exception) {
            log.error("Error reading vector store file metadata $fileId for vector store $vectorStoreId", e)
            null
        }

    override suspend fun listVectorStoreFiles(
        vectorStoreId: String,
        limit: Int,
        order: String,
        after: String?,
        before: String?,
        filter: String?,
    ): List<VectorStoreFile> =
        try {
            val query = Query.query(Criteria.where("vectorStoreId").`is`(vectorStoreId))
            
            // Apply filter
            if (filter != null) {
                query.addCriteria(Criteria.where("status").`is`(filter))
            }
            
            // Apply pagination
            if (after != null) {
                val afterFile = findVectorStoreFileById(vectorStoreId, after)
                afterFile?.let {
                    if (order.equals("asc", ignoreCase = true)) {
                        query.addCriteria(Criteria.where("createdAt").gt(it.createdAt))
                    } else {
                        query.addCriteria(Criteria.where("createdAt").lt(it.createdAt))
                    }
                }
            } else if (before != null) {
                val beforeFile = findVectorStoreFileById(vectorStoreId, before)
                beforeFile?.let {
                    query.addCriteria(Criteria.where("createdAt").lt(it.createdAt))
                }
            }
            
            // Apply sorting
            val direction = if (order.equals("asc", ignoreCase = true)) Sort.Direction.ASC else Sort.Direction.DESC
            query.with(Sort.by(direction, "createdAt"))
            
            // Apply limit
            query.with(PageRequest.of(0, limit))
            
            mongoTemplate.find<VectorStoreFile>(query, VECTOR_STORE_FILE_COLLECTION).collectList().awaitFirst()
        } catch (e: Exception) {
            log.error("Error listing vector store files for vector store $vectorStoreId", e)
            emptyList()
        }

    override suspend fun deleteVectorStoreFile(
        vectorStoreId: String,
        fileId: String,
    ): Boolean =
        try {
            val query =
                Query.query(
                    Criteria
                        .where("_id")
                        .`is`(fileId)
                        .and("vectorStoreId")
                        .`is`(vectorStoreId),
                )
            val result = mongoTemplate.remove<VectorStoreFile>(query, VECTOR_STORE_FILE_COLLECTION).awaitFirst()
            val deleted = result.deletedCount > 0
            
            if (deleted) {
                log.info("Deleted vector store file metadata $fileId from vector store $vectorStoreId")
            }
            
            deleted
        } catch (e: Exception) {
            log.error("Error deleting vector store file metadata $fileId from vector store $vectorStoreId", e)
            false
        }
} 
