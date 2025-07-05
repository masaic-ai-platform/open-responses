package ai.masaic.openresponses.api.service.storage

import ai.masaic.openresponses.api.utils.IdGenerator
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.core.io.UrlResource
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.codec.multipart.FilePart
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.io.IOException
import java.net.MalformedURLException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.CompletionHandler
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CompletableFuture
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * Implementation of FileStorageService that stores files in the local filesystem.
 */
@Service
class LocalFileStorageService(
    @Value("\${open-responses.file-storage.local.root-dir}") private val rootDir: String,
    private val objectMapper: ObjectMapper,
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
                    .filter { path -> Files.isRegularFile(path) && !Files.isHidden(path) && path.fileName.name.startsWith("open-responses-file") } // Hidden files are ignored
                    .filter { !it.name.endsWith(".metadata") } // Exclude metadata files
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
                        .filter { path -> Files.isRegularFile(path) && !Files.isHidden(path) && path.fileName.name.startsWith("open-responses-file") }
                        .filter { !it.name.endsWith(".metadata") } // Exclude metadata files
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
                
                // Try to load filename from metadata file
                val metadataPath = filePath.resolveSibling("$fileName.metadata")
                val originalFilename =
                    if (Files.exists(metadataPath)) {
                        try {
                            val metadataJson = Files.readString(metadataPath)
                            val metadata: Map<String, String> = objectMapper.readValue(metadataJson)
                            metadata["filename"] ?: fileName
                        } catch (e: Exception) {
                            log.warn("Could not read filename from metadata: $e")
                            fileName
                        }
                    } else {
                        fileName
                    }
            
                val metadata =
                    mutableMapOf<String, Any>(
                        "id" to fileId,
                        "filename" to originalFilename,
                        "purpose" to purpose,
                        "bytes" to attrs.size(),
                        "created_at" to attrs.creationTime().toInstant().epochSecond,
                    )
            
                metadata
            } catch (e: IOException) {
                // We will not throw an exception here, as the file may not exist
                log.error("Error reading metadata for file $fileId", e)
                return@withContext emptyMap<String, Any>()
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
                val originalFilename = filePart.filename()
                val extension =
                    if (originalFilename.substringAfterLast('.', "").isNotEmpty()) {
                        "." + originalFilename.substringAfterLast('.', "")
                    } else {
                        log.error("File has no extension")
                        throw FileStorageException("Failed to recognize file extension")
                    }
                
                // Generate a file ID using the IdGenerator utility
                val fileId = IdGenerator.generateFileId() + extension

                val purposeDir = rootLocation.resolve(purpose)

                if (!Files.exists(purposeDir)) {
                    Files.createDirectories(purposeDir)
                }
            
                val filePath = purposeDir.resolve(fileId)

                // Optimize file writing for large files
                val channel =
                    AsynchronousFileChannel.open(
                        filePath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                    )
                
                try {
                    // Process content in memory-efficient chunks with sequential writing
                    var position = 0L
                    var bytesProcessed = 0L
                    val startTime = System.currentTimeMillis()
                    var lastLogTime = startTime
                    
                    filePart
                        .content()
                        .map { buffer ->
                            val bytes = ByteArray(buffer.readableByteCount())
                            buffer.read(bytes)
                            DataBufferUtils.release(buffer)
                            
                            // Update progress tracking
                            bytesProcessed += bytes.size
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastLogTime > 2000) { // Log every 2 seconds for large files
                                val mbProcessed = bytesProcessed / (1024.0 * 1024.0)
                                val elapsedSeconds = (currentTime - startTime) / 1000.0
                                val mbPerSecond = if (elapsedSeconds > 0) mbProcessed / elapsedSeconds else 0.0
                                log.info("File upload progress: ${mbProcessed.toInt()} MB, rate: ${String.format("%.2f", mbPerSecond)} MB/s")
                                lastLogTime = currentTime
                            }
                            
                            bytes
                        }.concatMap { bytes ->
                            val byteBuffer = ByteBuffer.wrap(bytes)
                            val currentPosition = position
                            position += bytes.size
                            
                            val completableFuture = CompletableFuture<Int>()
                            channel.write(
                                byteBuffer,
                                currentPosition,
                                null,
                                object : CompletionHandler<Int, Void?> {
                                    override fun completed(
                                        result: Int,
                                        attachment: Void?,
                                    ) {
                                        completableFuture.complete(result)
                                    }

                                    override fun failed(
                                        exc: Throwable,
                                        attachment: Void?,
                                    ) {
                                        completableFuture.completeExceptionally(exc)
                                    }
                                },
                            )
                            Mono.fromFuture(completableFuture)
                        }.doFinally { 
                            channel.close()
                            val totalTime = (System.currentTimeMillis() - startTime) / 1000.0
                            val totalSizeMB = bytesProcessed / (1024.0 * 1024.0)
                            val mbPerSecond = if (totalTime > 0) totalSizeMB / totalTime else 0.0
                            log.info("File $fileId upload complete: $totalSizeMB MB in ${String.format("%.2f", totalTime)} seconds, avg rate: ${String.format("%.2f", mbPerSecond)} MB/s")
                        }.then()
                        .awaitSingleOrNull()
                } catch (e: Exception) {
                    channel.close()
                    throw e
                }
                
                log.info("File $fileId stored successfully in purpose directory $purpose")

                // Store the original filename in metadata file asynchronously
                // This is done in a separate coroutine to not block the main file upload
                val metadataPath = purposeDir.resolve("$fileId.metadata")
                val metadata =
                    mapOf(
                        "filename" to originalFilename,
                    )
                
                // Write metadata in parallel
                launch {
                    try {
                        Files.write(metadataPath, objectMapper.writeValueAsString(metadata).toByteArray())
                    } catch (e: Exception) {
                        log.error("Error writing metadata for file $fileId", e)
                    }
                }

                log.info("Stored file $fileId in purpose directory $purpose")

                // Run post-process hooks in parallel if any exist
                if (postProcessHooks.isNotEmpty()) {
                    launch {
                        postProcessHooks.forEach { hook ->
                            try {
                                hook(fileId, purpose)
                            } catch (e: Exception) {
                                log.error("Error executing post-process hook for file $fileId", e)
                            }
                        }
                    }
                }
            
                fileId
            } catch (e: Exception) {
                log.error("Failed to store file part", e)
                throw FileStorageException("Failed to store file part", e)
            }
        }
}

class FileStorageException : IOException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

class FileNotFoundException : IOException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
} 
