package ai.masaic.openresponses.api.repository

import ai.masaic.openresponses.api.model.VectorStore
import ai.masaic.openresponses.api.model.VectorStoreFile
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * File-based implementation of VectorStoreRepository.
 *
 * This implementation stores vector store metadata and vector store file metadata as JSON files on the filesystem.
 * It does not handle the actual file content, which is managed by FileStorageService.
 * 
 * This is the default implementation when MongoDB is not explicitly enabled.
 */
@Repository
@Primary
@ConditionalOnProperty(name = ["open-responses.store.vector.repository.type"], havingValue = "file", matchIfMissing = true)
class FileBasedVectorStoreRepository(
    @Value("\${open-responses.file-storage.local.root-dir}") private val rootDir: String,
    private val objectMapper: ObjectMapper,
) : AbstractVectorStoreRepository() {
    override val log: Logger = LoggerFactory.getLogger(FileBasedVectorStoreRepository::class.java)
    
    private val vectorStoresDir: String
        get() = "$rootDir/vector_stores"

    private val vectorStoreFilesDir: String
        get() = "$rootDir/vector_store_files"

    init {
        // Ensure the directories exist
        Files.createDirectories(Paths.get(vectorStoresDir))
        Files.createDirectories(Paths.get(vectorStoreFilesDir))
    }

    override suspend fun saveVectorStore(vectorStore: VectorStore): VectorStore =
        withContext(Dispatchers.IO) {
            val filePath = Paths.get(vectorStoresDir, "${vectorStore.id}.json")
        
            try {
                // Ensure parent directory exists
                val parentDir = filePath.parent
                if (!Files.exists(parentDir)) {
                    Files.createDirectories(parentDir)
                    log.info("Created directory: {}", parentDir)
                }
                
                val json = objectMapper.writeValueAsString(vectorStore)
                Files.write(filePath, json.toByteArray())
                log.debug("Saved vector store metadata ${vectorStore.id} with status ${vectorStore.status}")
                vectorStore
            } catch (e: Exception) {
                log.error("Error saving vector store metadata ${vectorStore.id}", e)
                throw e
            }
        }

    override suspend fun findVectorStoreById(vectorStoreId: String): VectorStore? =
        withContext(Dispatchers.IO) {
            val filePath = Paths.get(vectorStoresDir, "$vectorStoreId.json")
        
            if (!Files.exists(filePath)) {
                return@withContext null
            }
        
            try {
                val json = Files.readAllBytes(filePath)
                objectMapper.readValue<VectorStore>(json)
            } catch (e: Exception) {
                log.error("Error reading vector store metadata $vectorStoreId", e)
                null
            }
        }

    override suspend fun deleteVectorStore(vectorStoreId: String): Boolean =
        withContext(Dispatchers.IO) {
            val filePath = Paths.get(vectorStoresDir, "$vectorStoreId.json")
        
            if (!Files.exists(filePath)) {
                return@withContext false
            }
        
            try {
                // Delete the vector store metadata file
                Files.delete(filePath)
            
                // Delete all associated vector store file metadata
                val storeFilesDir = File(vectorStoreFilesDir)
                if (storeFilesDir.exists() && storeFilesDir.isDirectory) {
                    storeFilesDir
                        .listFiles { file -> 
                            file.isFile && file.nameWithoutExtension.startsWith("$vectorStoreId-") 
                        }?.forEach { file ->
                            file.delete()
                        }
                }
            
                log.info("Deleted vector store metadata $vectorStoreId")
                true
            } catch (e: Exception) {
                log.error("Error deleting vector store metadata $vectorStoreId", e)
                false
            }
        }

    override suspend fun saveVectorStoreFile(vectorStoreFile: VectorStoreFile): VectorStoreFile =
        withContext(Dispatchers.IO) {
            val filePath = Paths.get(vectorStoreFilesDir, "${vectorStoreFile.vectorStoreId}-${vectorStoreFile.id}.json")
        
            try {
                // Ensure parent directory exists
                val parentDir = filePath.parent
                if (!Files.exists(parentDir)) {
                    Files.createDirectories(parentDir)
                    log.info("Created directory: {}", parentDir)
                }
                
                val json = objectMapper.writeValueAsString(vectorStoreFile)
                Files.write(filePath, json.toByteArray())
                log.info("Saved vector store file metadata ${vectorStoreFile.id} for vector store ${vectorStoreFile.vectorStoreId}")
                vectorStoreFile
            } catch (e: Exception) {
                log.error("Error saving vector store file metadata ${vectorStoreFile.id}", e)
                throw e
            }
        }

    override suspend fun findVectorStoreFileById(
        vectorStoreId: String,
        fileId: String,
    ): VectorStoreFile? =
        withContext(Dispatchers.IO) {
            val filePath = Paths.get(vectorStoreFilesDir, "$vectorStoreId-$fileId.json")
        
            if (!Files.exists(filePath)) {
                return@withContext null
            }
        
            try {
                val json = Files.readAllBytes(filePath)
                objectMapper.readValue<VectorStoreFile>(json)
            } catch (e: Exception) {
                log.error("Error reading vector store file metadata $fileId", e)
                null
            }
        }

    override suspend fun deleteVectorStoreFile(
        vectorStoreId: String,
        fileId: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val filePath = Paths.get(vectorStoreFilesDir, "$vectorStoreId-$fileId.json")
        
            if (!Files.exists(filePath)) {
                return@withContext false
            }
        
            try {
                Files.delete(filePath)
                log.info("Deleted vector store file metadata $fileId from vector store $vectorStoreId")
                true
            } catch (e: Exception) {
                log.error("Error deleting vector store file metadata $fileId", e)
                false
            }
        }

    /**
     * Fetch all vector stores from the file system.
     */
    override suspend fun fetchAllVectorStores(): List<VectorStore> =
        withContext(Dispatchers.IO) {
            val directory = File(vectorStoresDir)
            if (!directory.exists() || !directory.isDirectory) {
                return@withContext emptyList()
            }
            
            try {
                directory
                    .listFiles { file -> file.isFile && file.extension == "json" }
                    ?.mapNotNull { file ->
                        try {
                            val json = Files.readAllBytes(file.toPath())
                            objectMapper.readValue<VectorStore>(json)
                        } catch (e: Exception) {
                            log.error("Error reading vector store from file ${file.name}", e)
                            null
                        }
                    }?.sortedByDescending { it.createdAt }
                    ?: emptyList()
            } catch (e: Exception) {
                log.error("Error listing vector stores", e)
                emptyList()
            }
        }

    /**
     * Fetch all files for a vector store from the file system.
     */
    override suspend fun fetchAllVectorStoreFiles(vectorStoreId: String): List<VectorStoreFile> =
        withContext(Dispatchers.IO) {
            val directory = File(vectorStoreFilesDir)
            if (!directory.exists() || !directory.isDirectory) {
                return@withContext emptyList()
            }
            
            try {
                directory
                    .listFiles { file -> 
                        file.isFile && 
                            file.extension == "json" && 
                            file.nameWithoutExtension.startsWith("$vectorStoreId-")
                    }?.mapNotNull { file ->
                        try {
                            val json = Files.readAllBytes(file.toPath())
                            objectMapper.readValue<VectorStoreFile>(json)
                        } catch (e: Exception) {
                            log.error("Error reading vector store file from file ${file.name}", e)
                            null
                        }
                    }?.sortedByDescending { it.createdAt }
                    ?: emptyList()
            } catch (e: Exception) {
                log.error("Error listing vector store files for $vectorStoreId", e)
                emptyList()
            }
        }
} 
