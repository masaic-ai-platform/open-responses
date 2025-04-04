package ai.masaic.openresponses.api.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.core.io.UrlResource
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.codec.multipart.FilePart
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.MalformedURLException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.*
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * Implementation of FileStorageService that stores files in the local filesystem.
 */
@Service
class LocalFileStorageService(
    @Value("\${open-responses.file-storage.local.root-dir}") private val rootDir: String,
) : FileStorageService {
    private val log = LoggerFactory.getLogger(LocalFileStorageService::class.java)
    private val rootLocation: Path = Paths.get(rootDir)
    private val postProcessHooks = mutableListOf<suspend (String, String) -> Unit>()

    init {
        try {
            if (!Files.exists(rootLocation)) {
                Files.createDirectories(rootLocation)
                log.info("Created file storage directory at {}", rootLocation.toAbsolutePath())
            }

            // Create subdirectories for each purpose
            for (purpose in listOf("assistants", "batch", "fine_tune", "vision", "user_data", "evals")) {
                val purposeDir = rootLocation.resolve(purpose)
                if (!Files.exists(purposeDir)) {
                    Files.createDirectories(purposeDir)
                }
            }
        } catch (e: IOException) {
            throw FileStorageException("Could not initialize storage", e)
        }
    }

    override fun loadAll(): Flow<Path> =
        flow {
            try {
                Files
                    .walk(rootLocation, 2)
                    .filter { path -> Files.isRegularFile(path) && !Files.isHidden(path) && path.fileName.name.contains("open-responses-file") } // Hidden files are ignored
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
                        .filter { path -> Files.isRegularFile(path) && !Files.isHidden(path) && path.fileName.name.contains("open-responses-file") }
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
            for (purpose in listOf("assistants", "batch", "fine_tune", "vision", "user_data", "evals")) {
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
                Files.deleteIfExists(filePath)
            } catch (e: IOException) {
                log.error("Error deleting file $fileId", e)
                false
            } catch (e: FileNotFoundException) {
                log.error("File not found for deletion: $fileId", e)
                false
            }
        }

    override suspend fun getFileMetadata(fileId: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
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
                log.error("Error checking existence of file $fileId", e)
                false
            }
        }

    override fun registerPostProcessHook(hook: suspend (String, String) -> Unit) {
        postProcessHooks.add(hook)
    }

    override suspend fun store(
        filePart: FilePart,
        purpose: String,
    ): String =
        withContext(Dispatchers.IO) {
            try {
                val fileId =
                    "open-responses-file-" + UUID.randomUUID().toString() + "." +
                        if (filePart.filename().substringAfterLast('.', "").isNotEmpty()) {
                            filePart.filename().substringAfterLast('.', "")
                        } else {
                            log.error("File has no extension")
                            throw FileStorageException("Failed to recognize file extension")
                        }

                val purposeDir = rootLocation.resolve(purpose)

                if (!Files.exists(purposeDir)) {
                    Files.createDirectories(purposeDir)
                }
            
                val filePath = purposeDir.resolve(fileId)

                // Store the file content from reactive stream
                DataBufferUtils
                    .write(filePart.content(), filePath)
                    .doOnSuccess {
                        log.info("File $fileId stored successfully in purpose directory $purpose")
                    }.awaitSingleOrNull()

                log.info("Stored file $fileId in purpose directory $purpose")

                // Run post-process hooks
                postProcessHooks.forEach { hook ->
                    try {
                        hook(fileId, purpose)
                    } catch (e: Exception) {
                        log.error("Error executing post-process hook for file $fileId", e)
                    }
                }
            
                fileId
            } catch (e: Exception) {
                log.error("Failed to store file part", e)
                throw FileStorageException("Failed to store file part", e)
            }
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
