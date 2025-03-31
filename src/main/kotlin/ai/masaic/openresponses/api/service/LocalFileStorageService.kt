package ai.masaic.openresponses.api.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.core.io.UrlResource
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.IOException
import java.net.MalformedURLException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.streams.asSequence

/**
 * Implementation of FileStorageService that stores files in the local filesystem.
 */
@Service
class LocalFileStorageService(
    @Value("\${app.file-storage.local.root-dir}") private val rootDir: String,
) : FileStorageService {
    private val log = LoggerFactory.getLogger(LocalFileStorageService::class.java)
    private val rootLocation: Path = Paths.get(rootDir)
    private val metadataCache = ConcurrentHashMap<String, MutableMap<String, Any>>()
    private val postProcessHooks = mutableListOf<suspend (String, String) -> Unit>()

    init {
        try {
            if (!Files.exists(rootLocation)) {
                Files.createDirectories(rootLocation)
                log.info("Created file storage directory at {}", rootLocation.toAbsolutePath())
            }

            // Create subdirectories for each purpose
            for (purpose in listOf("assistants", "batch", "fine-tune", "vision", "user_data", "evals")) {
                val purposeDir = rootLocation.resolve(purpose)
                if (!Files.exists(purposeDir)) {
                    Files.createDirectories(purposeDir)
                }
            }
        } catch (e: IOException) {
            throw FileStorageException("Could not initialize storage", e)
        }
    }

    override suspend fun store(
        file: MultipartFile,
        purpose: String,
    ): String =
        withContext(Dispatchers.IO) {
            try {
                if (file.isEmpty) {
                    throw FileStorageException("Failed to store empty file")
                }
            
                val fileId = "file-" + UUID.randomUUID().toString()
                val purposeDir = rootLocation.resolve(purpose)
            
                if (!Files.exists(purposeDir)) {
                    Files.createDirectories(purposeDir)
                }
            
                val filePath = purposeDir.resolve(fileId)
            
                Files.copy(file.inputStream, filePath, StandardCopyOption.REPLACE_EXISTING)
            
                val metadata =
                    mutableMapOf<String, Any>(
                        "id" to fileId,
                        "filename" to (file.originalFilename ?: "unknown"),
                        "purpose" to purpose,
                        "bytes" to file.size,
                        "created_at" to Instant.now().epochSecond,
                    )
                metadataCache[fileId] = metadata
            
                // Run post-process hooks
                postProcessHooks.forEach { hook ->
                    try {
                        hook(fileId, purpose)
                    } catch (e: Exception) {
                        log.error("Error executing post-process hook for file $fileId", e)
                    }
                }
            
                fileId
            } catch (e: IOException) {
                throw FileStorageException("Failed to store file", e)
            }
        }

    override fun loadAll(): Flow<Path> =
        flow {
            try {
                Files
                    .walk(rootLocation, 2)
                    .filter { path -> Files.isRegularFile(path) }
                    .asSequence()
                    .forEach { 
                        emit(it) 
                    }
            } catch (e: IOException) {
                throw FileStorageException("Failed to read stored files", e)
            }
        }.flowOn(Dispatchers.IO)

    override fun loadByPurpose(purpose: String): Flow<Path> =
        flow {
            try {
                val purposeDir = rootLocation.resolve(purpose)
                if (Files.exists(purposeDir)) {
                    Files
                        .walk(purposeDir, 1)
                        .filter { path -> Files.isRegularFile(path) }
                        .asSequence()
                        .forEach { 
                            emit(it) 
                        }
                }
            } catch (e: IOException) {
                throw FileStorageException("Failed to read stored files for purpose: $purpose", e)
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun load(fileId: String): Path =
        withContext(Dispatchers.IO) {
            // First, look for the file in all purpose directories
            for (purpose in listOf("assistants", "batch", "fine-tune", "vision", "user_data", "evals")) {
                val filePath = rootLocation.resolve(purpose).resolve(fileId)
                if (Files.exists(filePath)) {
                    return@withContext filePath
                }
            }
            throw FileNotFoundException("File not found: $fileId")
        }

    override suspend fun loadAsResource(fileId: String): Resource =
        withContext(Dispatchers.IO) {
            try {
                val file = load(fileId)
                val resource = UrlResource(file.toUri())
                if (resource.exists() || resource.isReadable) {
                    resource
                } else {
                    throw FileNotFoundException("Could not read file: $fileId")
                }
            } catch (e: MalformedURLException) {
                throw FileNotFoundException("Could not read file: $fileId", e)
            }
        }

    override suspend fun delete(fileId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val filePath = load(fileId)
                metadataCache.remove(fileId)
                Files.deleteIfExists(filePath)
            } catch (e: IOException) {
                log.error("Error deleting file $fileId", e)
                false
            } catch (e: FileNotFoundException) {
                false
            }
        }

    override suspend fun getFileMetadata(fileId: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            // Return cached metadata if available
            if (metadataCache.containsKey(fileId)) {
                return@withContext metadataCache[fileId]!!
            }
        
            // Otherwise, load from the file system
            try {
                val filePath = load(fileId)
                val attrs = Files.readAttributes(filePath, BasicFileAttributes::class.java)
                val fileName = filePath.fileName.toString()
                val purpose = filePath.parent.fileName.toString()
            
                val metadata =
                    mutableMapOf<String, Any>(
                        "id" to fileId,
                        "filename" to fileName,
                        "purpose" to purpose,
                        "bytes" to attrs.size(),
                        "created_at" to attrs.creationTime().toInstant().epochSecond,
                    )
            
                // Cache for future use
                metadataCache[fileId] = metadata
                metadata
            } catch (e: IOException) {
                throw FileNotFoundException("Could not read file metadata: $fileId", e)
            }
        }

    override suspend fun exists(fileId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val path = load(fileId)
                Files.exists(path)
            } catch (e: Exception) {
                false
            }
        }

    override fun registerPostProcessHook(hook: suspend (String, String) -> Unit) {
        postProcessHooks.add(hook)
    }
}

class FileStorageException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

class FileNotFoundException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
} 
