package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.config.VectorSearchProperties
import ai.masaic.openresponses.api.utils.DocumentTextExtractor
import ai.masaic.openresponses.api.utils.TextChunkingUtil
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * File-based implementation of VectorSearchProvider.
 * 
 * This class provides a vector search implementation that persists embeddings to disk.
 * It uses the EmbeddingService to generate vector embeddings and stores them in JSON files.
 * This ensures persistence across application restarts.
 */
@Service
@ConditionalOnProperty(name = ["open-responses.vector-store.provider"], havingValue = "file", matchIfMissing = true)
class FileBasedVectorSearchProvider(
    private val embeddingService: EmbeddingService,
    private val vectorSearchProperties: VectorSearchProperties,
    private val objectMapper: ObjectMapper,
    @Value("\${open-responses.file-storage.local.root-dir}") private val rootDir: String,
) : VectorSearchProvider {
    private val log = LoggerFactory.getLogger(FileBasedVectorSearchProvider::class.java)
    
    // Directory for storing embeddings
    private val embeddingsDir = "$rootDir/embeddings"
    
    // Cache to avoid reading from disk for every search
    private val fileChunksCache = ConcurrentHashMap<String, List<ChunkWithEmbedding>>()
    private val fileMetadataCache = ConcurrentHashMap<String, Map<String, Any>>()

    init {
        try {
            // Create embeddings directory if it doesn't exist
            val embeddingsDirPath = Paths.get(embeddingsDir)
            if (!Files.exists(embeddingsDirPath)) {
                Files.createDirectories(embeddingsDirPath)
                log.info("Created embeddings directory at {}", embeddingsDirPath.toAbsolutePath())
            }
            
            // Load existing embeddings into cache on startup
            loadAllEmbeddings()
        } catch (e: Exception) {
            log.error("Error initializing FileBasedVectorSearchProvider", e)
        }
    }

    /**
     * Data class representing a chunk of text with its vector embedding.
     */
    private data class ChunkWithEmbedding(
        val fileId: String,
        val content: String,
        val embedding: List<Float>,
        val chunkMetadata: Map<String, Any> = emptyMap(),
    )

    /**
     * Data class for storing file chunks and metadata in JSON.
     */
    private data class FileEmbeddings(
        val fileId: String,
        val metadata: Map<String, Any>,
        val chunks: List<ChunkWithEmbedding>,
    )

    /**
     * Loads all existing embeddings from disk into memory cache on startup.
     */
    private fun loadAllEmbeddings() {
        val embeddingsPath = Paths.get(embeddingsDir)
        if (!Files.exists(embeddingsPath)) return
        
        try {
            Files.list(embeddingsPath).forEach { path ->
                if (Files.isRegularFile(path) && path.toString().endsWith(".json")) {
                    try {
                        val json = Files.readAllBytes(path)
                        val fileEmbeddings = objectMapper.readValue<FileEmbeddings>(json)
                        
                        // Update caches
                        fileChunksCache[fileEmbeddings.fileId] = fileEmbeddings.chunks
                        fileMetadataCache[fileEmbeddings.fileId] = fileEmbeddings.metadata
                        
                        log.info("Loaded embeddings for file: ${fileEmbeddings.fileId}")
                    } catch (e: Exception) {
                        log.error("Error loading embeddings from ${path.fileName}", e)
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Error loading embeddings from disk", e)
        }
    }

    /**
     * Saves embeddings for a file to disk.
     */
    private fun saveEmbeddings(fileId: String) {
        val chunks = fileChunksCache[fileId] ?: return
        val metadata = fileMetadataCache[fileId] ?: emptyMap()
        
        try {
            val fileEmbeddings =
                FileEmbeddings(
                    fileId = fileId,
                    metadata = metadata,
                    chunks = chunks,
                )
            
            val json = objectMapper.writeValueAsString(fileEmbeddings)
            Files.write(Paths.get(embeddingsDir, "$fileId.json"), json.toByteArray())
            log.info("Saved embeddings for file: $fileId")
        } catch (e: Exception) {
            log.error("Error saving embeddings for file: $fileId", e)
        }
    }

    /**
     * Indexes a file in the vector store by chunking its content and computing embeddings.
     *
     * @param fileId The ID of the file
     * @param content The file content as an InputStream
     * @param filename The name of the file
     * @return True if indexing was successful, false otherwise
     */
    override fun indexFile(
        fileId: String,
        content: InputStream,
        filename: String,
    ): Boolean {
        try {
            // Extract text from the document using Apache Tika
            val text = DocumentTextExtractor.extractAndCleanText(content, filename)
            if (text.isBlank()) {
                log.warn("Extracted text is empty for file: $filename")
                return false
            }
            
            // Split the content into chunks using the common utility
            val chunks = TextChunkingUtil.chunkText(text, vectorSearchProperties.chunkSize, vectorSearchProperties.chunkOverlap)
            
            // Generate embeddings for each chunk
            val chunksWithEmbeddings =
                chunks.map { chunk ->
                    ChunkWithEmbedding(
                        fileId = fileId,
                        content = chunk,
                        embedding = embeddingService.embedText(chunk),
                        chunkMetadata = mapOf("original_filename" to filename),
                    )
                }
            
            // Store in memory cache
            fileChunksCache[fileId] = chunksWithEmbeddings
            fileMetadataCache[fileId] = mapOf("original_filename" to filename)
            
            // Persist to disk
            saveEmbeddings(fileId)
            
            return true
        } catch (e: Exception) {
            log.error("Error indexing file: ${e.message}", e)
            return false
        }
    }

    /**
     * Searches for similar content in the vector store.
     *
     * @param query The search query
     * @param maxResults Maximum number of results to return
     * @param filters Optional filters to apply to the search
     * @return List of search results
     */
    override fun searchSimilar(
        query: String,
        maxResults: Int,
        filters: Map<String, Any>?,
    ): List<VectorSearchProvider.SearchResult> {
        // Generate embedding for the query
        val queryEmbedding = embeddingService.embedText(query)
        
        // Collect all chunks from cache
        val allChunks = fileChunksCache.values.flatten()
        
        // Apply filters if provided
        val filteredChunks =
            if (filters != null) {
                allChunks.filter { chunk ->
                    filters.all { (key, value) ->
                        when {
                            key == "fileIds" && value is List<*> -> (value as List<*>).contains(chunk.fileId)
                            key == "fileId" -> chunk.fileId == value
                            chunk.chunkMetadata.containsKey(key) -> chunk.chunkMetadata[key] == value
                            fileMetadataCache[chunk.fileId]?.containsKey(key) == true -> 
                                fileMetadataCache[chunk.fileId]?.get(key) == value
                            else -> false
                        }
                    }
                }
            } else {
                allChunks
            }
        
        // Calculate similarity scores
        val scoredChunks =
            filteredChunks.map { chunk ->
                val score = embeddingService.calculateSimilarity(queryEmbedding, chunk.embedding)
                chunk to score
            }
        
        // Sort by score (descending) and take top results
        return scoredChunks
            .sortedByDescending { (_, score) -> score }
            .take(maxResults)
            .map { (chunk, score) ->
                VectorSearchProvider.SearchResult(
                    fileId = chunk.fileId,
                    score = score.toDouble(),
                    content = chunk.content,
                    metadata = chunk.chunkMetadata + (fileMetadataCache[chunk.fileId] ?: emptyMap()),
                )
            }
    }

    /**
     * Deletes a file from the vector store.
     *
     * @param fileId The ID of the file to delete
     * @return True if deletion was successful, false otherwise
     */
    override fun deleteFile(fileId: String): Boolean {
        try {
            // Remove from memory cache
            fileChunksCache.remove(fileId)
            fileMetadataCache.remove(fileId)
            
            // Delete the file from disk
            val embeddingsFile = Paths.get(embeddingsDir, "$fileId.json")
            if (Files.exists(embeddingsFile)) {
                Files.delete(embeddingsFile)
                log.info("Deleted embeddings for file: $fileId")
            }
            
            return true
        } catch (e: Exception) {
            log.error("Error deleting file embeddings: $fileId", e)
            return false
        }
    }

    /**
     * Gets metadata for a file from the vector store.
     *
     * @param fileId The ID of the file
     * @return Map of metadata, or null if the file doesn't exist
     */
    override fun getFileMetadata(fileId: String): Map<String, Any>? = fileMetadataCache[fileId]
} 
