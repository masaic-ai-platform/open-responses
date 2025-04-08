package ai.masaic.openresponses.api.repository

import ai.masaic.openresponses.api.model.VectorStore
import ai.masaic.openresponses.api.model.VectorStoreFile
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
) : VectorStoreRepository {
    private val log = LoggerFactory.getLogger(FileBasedVectorStoreRepository::class.java)
    
    private val vectorStoresDir = "$rootDir/vector-stores"
    private val vectorStoreFilesDir = "$rootDir/vector-store-files"

    init {
        try {
            // Create directories if they don't exist
            val vectorStoresDirPath = Paths.get(vectorStoresDir)
            val vectorStoreFilesDirPath = Paths.get(vectorStoreFilesDir)
            
            if (!Files.exists(vectorStoresDirPath)) {
                Files.createDirectories(vectorStoresDirPath)
                log.info("Created vector stores directory at {}", vectorStoresDirPath.toAbsolutePath())
            }
            
            if (!Files.exists(vectorStoreFilesDirPath)) {
                Files.createDirectories(vectorStoreFilesDirPath)
                log.info("Created vector store files directory at {}", vectorStoreFilesDirPath.toAbsolutePath())
            }
        } catch (e: Exception) {
            log.error("Error creating vector store directories", e)
            throw RuntimeException("Failed to initialize vector store directories: ${e.message}", e)
        }
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
                log.info("Saved vector store metadata ${vectorStore.id} with status ${vectorStore.status}")
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

    override suspend fun listVectorStores(
        limit: Int,
        order: String,
        after: String?,
        before: String?,
    ): List<VectorStore> =
        withContext(Dispatchers.IO) {
            val vectorStoreDir = File(vectorStoresDir)
            if (!vectorStoreDir.exists() || !vectorStoreDir.isDirectory) {
                return@withContext emptyList()
            }
        
            // Get all vector store metadata files
            val vectorStoreFiles = vectorStoreDir.listFiles { file -> file.isFile && file.extension == "json" } ?: return@withContext emptyList()
        
            // Parse vector stores from files
            val allVectorStores =
                vectorStoreFiles.mapNotNull { file ->
                    try {
                        val json = Files.readAllBytes(file.toPath())
                        objectMapper.readValue<VectorStore>(json)
                    } catch (e: Exception) {
                        log.error("Error reading vector store metadata from ${file.name}", e)
                        null
                    }
                }
        
            // Apply sorting
            val sortedVectorStores =
                if (order.equals("asc", ignoreCase = true)) {
                    allVectorStores.sortedBy { it.createdAt }
                } else {
                    allVectorStores.sortedByDescending { it.createdAt }
                }
        
            // Apply pagination
            val filteredVectorStores =
                when {
                    after != null -> {
                        val afterIndex = sortedVectorStores.indexOfFirst { it.id == after }
                        if (afterIndex >= 0 && afterIndex < sortedVectorStores.size - 1) {
                            sortedVectorStores.subList(afterIndex + 1, sortedVectorStores.size)
                        } else {
                            emptyList()
                        }
                    }
                    before != null -> {
                        val beforeIndex = sortedVectorStores.indexOfFirst { it.id == before }
                        if (beforeIndex > 0) {
                            sortedVectorStores.subList(0, beforeIndex)
                        } else {
                            emptyList()
                        }
                    }
                    else -> sortedVectorStores
                }
        
            // Apply limit
            filteredVectorStores.take(limit)
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

    override suspend fun listVectorStoreFiles(
        vectorStoreId: String,
        limit: Int,
        order: String,
        after: String?,
        before: String?,
        filter: String?,
    ): List<VectorStoreFile> =
        withContext(Dispatchers.IO) {
            val storeFilesDir = File(vectorStoreFilesDir)
            if (!storeFilesDir.exists() || !storeFilesDir.isDirectory) {
                return@withContext emptyList()
            }
        
            // Get all vector store file metadata files for this vector store
            val vectorStoreFilesList =
                storeFilesDir.listFiles { file -> 
                    file.isFile && file.extension == "json" && file.nameWithoutExtension.startsWith("$vectorStoreId-") 
                } ?: return@withContext emptyList()
        
            // Parse vector store files from metadata files
            val allVectorStoreFiles =
                vectorStoreFilesList.mapNotNull { file ->
                    try {
                        val json = Files.readAllBytes(file.toPath())
                        objectMapper.readValue<VectorStoreFile>(json)
                    } catch (e: Exception) {
                        log.error("Error reading vector store file metadata from ${file.name}", e)
                        null
                    }
                }
        
            // Apply filter
            val filteredByStatus =
                if (filter != null) {
                    allVectorStoreFiles.filter { it.status == filter }
                } else {
                    allVectorStoreFiles
                }
        
            // Apply sorting
            val sortedVectorStoreFiles =
                if (order.equals("asc", ignoreCase = true)) {
                    filteredByStatus.sortedBy { it.createdAt }
                } else {
                    filteredByStatus.sortedByDescending { it.createdAt }
                }
        
            // Apply pagination
            val filteredVectorStoreFiles =
                when {
                    after != null -> {
                        val afterIndex = sortedVectorStoreFiles.indexOfFirst { it.id == after }
                        if (afterIndex >= 0 && afterIndex < sortedVectorStoreFiles.size - 1) {
                            sortedVectorStoreFiles.subList(afterIndex + 1, sortedVectorStoreFiles.size)
                        } else {
                            emptyList()
                        }
                    }
                    before != null -> {
                        val beforeIndex = sortedVectorStoreFiles.indexOfFirst { it.id == before }
                        if (beforeIndex > 0) {
                            sortedVectorStoreFiles.subList(0, beforeIndex)
                        } else {
                            emptyList()
                        }
                    }
                    else -> sortedVectorStoreFiles
                }
        
            // Apply limit
            filteredVectorStoreFiles.take(limit)
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
                // Delete the vector store file metadata
                Files.delete(filePath)
            
                log.info("Deleted vector store file metadata $fileId from vector store $vectorStoreId")
                true
            } catch (e: Exception) {
                log.error("Error deleting vector store file metadata $fileId from vector store $vectorStoreId", e)
                false
            }
        }
} 
