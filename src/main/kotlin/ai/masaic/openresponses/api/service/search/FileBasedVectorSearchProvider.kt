package ai.masaic.openresponses.api.service.search

import ai.masaic.openresponses.api.config.VectorSearchConfigProperties
import ai.masaic.openresponses.api.model.ChunkingStrategy
import ai.masaic.openresponses.api.model.Filter
import ai.masaic.openresponses.api.model.RankingOptions
import ai.masaic.openresponses.api.service.embedding.EmbeddingService
import ai.masaic.openresponses.api.utils.DocumentTextExtractor
import ai.masaic.openresponses.api.utils.FilterUtils
import ai.masaic.openresponses.api.utils.IdGenerator
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
@ConditionalOnProperty(name = ["open-responses.store.vector.search.provider"], havingValue = "file", matchIfMissing = true)
class FileBasedVectorSearchProvider(
    private val embeddingService: EmbeddingService,
    private val vectorSearchProperties: VectorSearchConfigProperties,
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
        val chunkId: String,
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
     * @param chunkingStrategy Optional chunking strategy to use when splitting the text
     * @return True if indexing was successful, false otherwise
     */
    override fun indexFile(
        fileId: String,
        content: InputStream,
        filename: String,
        chunkingStrategy: ChunkingStrategy?,
    ): Boolean {
        try {
            // Extract text from the document using Apache Tika
            val text = DocumentTextExtractor.Companion.extractAndCleanText(content, filename)
            if (text.isBlank()) {
                log.warn("Extracted text is empty for file: $filename")
                return false
            }

            // Determine chunking parameters based on the strategy or fallback to properties
            log.debug("Chunking text for file: $filename")
            val textChunks = TextChunkingUtil.chunkText(text, chunkingStrategy)
            val chunks = textChunks.map { it.text }

            // Generate embeddings for each chunk
            val chunksWithEmbeddings =
                chunks.mapIndexed { index, chunk ->
                    // Generate a short unique ID for each chunk
                    val chunkId = IdGenerator.generateChunkId()

                    ChunkWithEmbedding(
                        fileId = fileId,
                        chunkId = chunkId,
                        content = chunk,
                        embedding = embeddingService.embedText(chunk),
                        chunkMetadata =
                            mapOf(
                                "file_id" to fileId,
                                "filename" to filename,
                                "chunk_id" to chunkId,
                                "chunk_index" to index,
                                "total_chunks" to chunks.size,
                            ),
                    )
                }

            // Store in memory cache
            fileChunksCache[fileId] = chunksWithEmbeddings
            fileMetadataCache[fileId] = mapOf("filename" to filename)

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
     * @param rankingOptions Optional ranking options for search
     * @param filter Optional structured filter object (new format)
     * @return List of search results
     */
    override fun searchSimilar(
        query: String,
        maxResults: Int,
        rankingOptions: RankingOptions?,
        filter: Filter?,
    ): List<VectorSearchProvider.SearchResult> {
        if (query.isBlank()) {
            log.warn("Query is empty or blank")
            return emptyList()
        }

        // Generate embedding for the query
        val queryEmbedding = embeddingService.embedText(query)

        // Collect all chunks from cache
        val allChunks = fileChunksCache.values.flatten()

        // Apply filters to chunks
        val filteredChunks = applyFilters(allChunks, filter)

        // Calculate similarity scores
        val scoredChunks =
            filteredChunks.map { chunk ->
                val score = embeddingService.calculateSimilarity(queryEmbedding, chunk.embedding)
                chunk to score
            }

        // Sort by score (descending) and take top results
        return scoredChunks
            .filter { it.second > (rankingOptions?.scoreThreshold ?: 0.07) }
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
     * Apply filters to chunks.
     */
    private fun applyFilters(
        chunks: List<ChunkWithEmbedding>,
        filter: Filter?,
    ): List<ChunkWithEmbedding> {
        // If no filter, return all chunks
        if (filter == null) {
            return chunks
        }

        // Apply the filter to each chunk
        return chunks.filter { chunk ->
            // Combine chunk metadata with file metadata
            val combinedMetadata = chunk.chunkMetadata + (fileMetadataCache[chunk.fileId] ?: emptyMap())
            FilterUtils.matchesFilter(filter, combinedMetadata, chunk.fileId)
        }
    }

    /**
     * Searches for similar content in the vector store using the base interface.
     */
    override fun searchSimilar(
        query: String,
        maxResults: Int,
        rankingOptions: RankingOptions?,
    ): List<VectorSearchProvider.SearchResult> = searchSimilar(query, maxResults, rankingOptions, null)

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

    /**
     * Updates metadata for a file in the vector store.
     * This is used to sync vectorstorefile attributes with the search provider.
     *
     * @param fileId The ID of the file
     * @param metadata The metadata to update
     * @return True if the update was successful, false otherwise
     */
    fun updateFileMetadata(
        fileId: String,
        metadata: Map<String, Any>,
    ): Boolean {
        try {
            if (!fileMetadataCache.containsKey(fileId)) {
                log.warn("Cannot update metadata for non-existent file: $fileId")
                return false
            }
            
            // Update the metadata cache with the new values
            fileMetadataCache[fileId] = metadata
            
            // If the file chunks exist, update the disk storage too
            if (fileChunksCache.containsKey(fileId)) {
                saveEmbeddings(fileId)
            }
            
            return true
        } catch (e: Exception) {
            log.error("Error updating metadata for file: $fileId", e)
            return false
        }
    }
}
