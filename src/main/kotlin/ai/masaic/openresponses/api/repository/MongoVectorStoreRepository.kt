package ai.masaic.openresponses.api.repository

import ai.masaic.openresponses.api.model.VectorStore
import ai.masaic.openresponses.api.model.VectorStoreFile
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
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
 * It is only enabled when open-responses.store.vector.repository.type=mongodb
 */
@Repository
@ConditionalOnProperty(name = ["open-responses.store.vector.repository.type"], havingValue = "mongodb")
class MongoVectorStoreRepository(
    private val reactiveMongoTemplate: ReactiveMongoTemplate,
) : AbstractVectorStoreRepository() {
    companion object {
        const val VECTOR_STORE_COLLECTION = "vector_stores"
        const val VECTOR_STORE_FILE_COLLECTION = "vector_store_files"
    }

    override suspend fun saveVectorStore(vectorStore: VectorStore): VectorStore =
        try {
            reactiveMongoTemplate.save(vectorStore, VECTOR_STORE_COLLECTION).awaitFirst().also {
                log.info("Saved vector store metadata ${vectorStore.id}")
            }
        } catch (e: Exception) {
            log.error("Error saving vector store metadata ${vectorStore.id}", e)
            throw e
        }

    override suspend fun findVectorStoreById(vectorStoreId: String): VectorStore? =
        try {
            reactiveMongoTemplate.findById<VectorStore>(vectorStoreId, VECTOR_STORE_COLLECTION).awaitFirstOrNull()
        } catch (e: Exception) {
            log.error("Error reading vector store metadata $vectorStoreId", e)
            null
        }

    override suspend fun deleteVectorStore(vectorStoreId: String): Boolean =
        try {
            // Delete the vector store
            val vectorStoreQuery = Query.query(Criteria.where("_id").`is`(vectorStoreId))
            val deletedVectorStore = reactiveMongoTemplate.remove<VectorStore>(vectorStoreQuery, VECTOR_STORE_COLLECTION).awaitFirst()
            
            if (deletedVectorStore.deletedCount > 0) {
                // Delete all associated vector store files
                val vectorStoreFilesQuery = Query.query(Criteria.where("vectorStoreId").`is`(vectorStoreId))
                reactiveMongoTemplate.remove<VectorStoreFile>(vectorStoreFilesQuery, VECTOR_STORE_FILE_COLLECTION).awaitFirst()
                
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
            reactiveMongoTemplate.save(vectorStoreFile, VECTOR_STORE_FILE_COLLECTION).awaitFirst().also {
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
            reactiveMongoTemplate.findOne(query, VectorStoreFile::class.java, VECTOR_STORE_FILE_COLLECTION).awaitFirstOrNull()
        } catch (e: Exception) {
            log.error("Error reading vector store file metadata $fileId", e)
            null
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
            val result = reactiveMongoTemplate.remove(query, VECTOR_STORE_FILE_COLLECTION).awaitFirst()
            val deleted = result.deletedCount > 0
            
            if (deleted) {
                log.info("Deleted vector store file metadata $fileId from vector store $vectorStoreId")
            }
            
            deleted
        } catch (e: Exception) {
            log.error("Error deleting vector store file metadata $fileId", e)
            false
        }

    /**
     * Fetch all vector stores from MongoDB.
     */
    override suspend fun fetchAllVectorStores(): List<VectorStore> =
        try {
            val query = Query().with(Sort.by(Sort.Direction.DESC, "createdAt"))
            reactiveMongoTemplate.find<VectorStore>(query, VECTOR_STORE_COLLECTION).collectList().awaitSingle()
        } catch (e: Exception) {
            log.error("Error fetching vector stores", e)
            emptyList()
        }

    /**
     * Fetch all files for a vector store from MongoDB.
     */
    override suspend fun fetchAllVectorStoreFiles(vectorStoreId: String): List<VectorStoreFile> =
        try {
            val query =
                Query
                    .query(Criteria.where("vectorStoreId").`is`(vectorStoreId))
                    .with(Sort.by(Sort.Direction.DESC, "createdAt"))
            reactiveMongoTemplate.find<VectorStoreFile>(query, VECTOR_STORE_FILE_COLLECTION).collectList().awaitSingle()
        } catch (e: Exception) {
            log.error("Error fetching vector store files for $vectorStoreId", e)
            emptyList()
        }
} 
