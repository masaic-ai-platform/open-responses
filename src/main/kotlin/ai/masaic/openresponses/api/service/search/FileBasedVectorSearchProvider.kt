package ai.masaic.openresponses.api.service.search

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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths

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
    private val objectMapper: ObjectMapper,
    @Value("\${open-responses.file-storage.local.root-dir}") private val rootDir: String,
    @Autowired private val hybridSearchServiceHelper: HybridSearchServiceHelper,
) : VectorSearchProvider {
    private val log = LoggerFactory.getLogger(FileBasedVectorSearchProvider::class.java)

    // Directory for storing embeddings
    private val embeddingsDir = "$rootDir/embeddings"

    init {
        try {
            // Create embeddings directory if it doesn't exist
            val embeddingsDirPath = Paths.get(embeddingsDir)
            if (!Files.exists(embeddingsDirPath)) {
                Files.createDirectories(embeddingsDirPath)
                log.info("Created embeddings directory at {}", embeddingsDirPath.toAbsolutePath())
            }
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
     * Load embeddings for a file directly from disk.
     * Returns null if the file doesn't exist or can't be read.
     */
    private fun loadEmbeddingsForFile(fileId: String): FileEmbeddings? {
        val path = Paths.get(embeddingsDir, "embeddings-$fileId.json")
        return try {
            if (!Files.exists(path)) {
                null
            } else {
                val json = Files.readAllBytes(path)
                objectMapper.readValue<FileEmbeddings>(json)
            }
        } catch (e: Exception) {
            log.error("Error loading embeddings for file {}", fileId, e)
            null
        }
    }

    /**
     * Saves embeddings for a file to disk.
     */
    private fun saveEmbeddings(
        fileId: String,
        chunks: List<ChunkWithEmbedding>,
        metadata: Map<String, Any>,
    ) {
        try {
            val fileEmbeddings =
                FileEmbeddings(
                    fileId = fileId,
                    metadata = metadata,
                    chunks = chunks,
                )

            val json = objectMapper.writeValueAsString(fileEmbeddings)
            Files.write(Paths.get(embeddingsDir, "embeddings-$fileId.json"), json.toByteArray())
            log.info("Saved embeddings for file: $fileId")
        } catch (e: Exception) {
            log.error("Error saving embeddings for file: $fileId", e)
        }
    }

    /**
     * Indexes a file in the vector store.
     *
     * @param fileId The ID of the file
     * @param inputStream The file content as an InputStream
     * @param filename The name of the file
     * @param chunkingStrategy Optional chunking strategy for the file
     * @param preDeleteIfExists Whether to check and delete existing vectors for this file before indexing
     * @param attributes Additional metadata attributes to include with each vector
     * @return True if indexing was successful, false otherwise
     */
    @OptIn(DelicateCoroutinesApi::class, DelicateCoroutinesApi::class)
    override fun indexFile(
        fileId: String,
        inputStream: InputStream,
        filename: String,
        chunkingStrategy: ChunkingStrategy?,
        preDeleteIfExists: Boolean,
        attributes: Map<String, Any>?,
        vectorStoreId: String,
    ): Boolean {
        try {
            if (preDeleteIfExists && Files.exists(Paths.get(embeddingsDir, "embeddings-$fileId.json"))) {
                // Delete existing embeddings for this file
                deleteFile(fileId)
            }

            // Extract text content from the file
            val text = DocumentTextExtractor.extractAndCleanText(inputStream, filename)

            if (text.isBlank()) {
                log.warn("Extracted text is empty for file: $filename")
                return false
            }

            // Use the TextChunkingUtil to chunk the text
            log.debug("Chunking text for file: $filename")
            val textChunks = TextChunkingUtil.chunkText(text, chunkingStrategy)
            
            if (textChunks.isEmpty()) {
                log.warn("No chunks created for file: $filename")
                return false
            }

            log.info("Created ${textChunks.size} chunks for file: $filename")

            // Prepare for batch embedding
            val chunkTexts = textChunks.map { it.text }
            val chunkMetadataList = mutableListOf<Map<String, Any>>()
            
            // Prepare metadata for each chunk
            textChunks.forEachIndexed { index, chunk ->
                // Generate a short unique ID for each chunk
                val chunkId = IdGenerator.generateChunkId()
                
                // Create base metadata
                val chunkMetadata =
                    mutableMapOf<String, Any>(
                        "file_id" to fileId,
                        "filename" to filename,
                        "chunk_id" to chunkId,
                        "chunk_index" to index,
                        "vector_store_id" to vectorStoreId,
                        "total_chunks" to textChunks.size,
                    )
                
                // Add any additional attributes
                if (attributes != null) {
                    chunkMetadata.putAll(attributes)
                }
                
                chunkMetadataList.add(chunkMetadata)
            }
            
            // Generate embeddings for all chunks in batch
            log.debug("Generating batch embeddings for {} chunks", chunkTexts.size)
            val embeddings = embeddingService.embedTexts(chunkTexts)
            
            // Create chunks with embeddings
            val chunksWithEmbeddings =
                textChunks.mapIndexed { index, chunk ->
                    ChunkWithEmbedding(
                        fileId = fileId,
                        chunkId = chunkMetadataList[index]["chunk_id"] as String,
                        content = chunk.text,
                        embedding = embeddings[index],
                        chunkMetadata = chunkMetadataList[index],
                    )
                }
            
            // Initialize metadata
            val initialMetadata = mutableMapOf<String, Any>("filename" to filename)
            
            // Add any additional attributes to the file metadata
            if (attributes != null) {
                initialMetadata.putAll(attributes)
            }

            // Persist to disk
            saveEmbeddings(fileId, chunksWithEmbeddings, initialMetadata)

            // Asynchronously index chunks for text search via hybrid service
            val chunksForIndexing =
                chunksWithEmbeddings.map { cw ->
                    HybridSearchService.ChunkForIndexing(
                        chunkId = cw.chunkId,
                        vectorStoreId = vectorStoreId,
                        fileId = cw.fileId,
                        filename = cw.chunkMetadata["filename"] as String,
                        chunkIndex = cw.chunkMetadata["chunk_index"] as Int,
                        content = cw.content,
                    )
                }
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    hybridSearchServiceHelper.indexChunks(chunksForIndexing)
                    log.info("Indexed ${chunksForIndexing.size} chunks for hybrid search")
                } catch (e: Exception) {
                    log.error("Error indexing chunks for hybrid search: ${e.message}", e)
                    deleteFile(fileId) // Rollback if indexing fails
                    throw e
                }
            }

            return true
        } catch (e: Exception) {
            log.error("Error indexing file: ${e.message}", e)
            return false
        }
    }

    /**
     * Indexes a file with attributes.
     */
    override fun indexFile(
        fileId: String,
        inputStream: InputStream,
        filename: String,
        chunkingStrategy: ChunkingStrategy?,
        attributes: Map<String, Any>?,
        vectorStoreId: String,
    ): Boolean = indexFile(fileId, inputStream, filename, chunkingStrategy, true, attributes, vectorStoreId)

    /**
     * Indexes a file without attributes.
     */
    override fun indexFile(
        fileId: String,
        inputStream: InputStream,
        filename: String,
        chunkingStrategy: ChunkingStrategy?,
        vectorStoreId: String,
    ): Boolean = indexFile(fileId, inputStream, filename, chunkingStrategy, true, null, vectorStoreId)

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

        // Collect all chunks from disk
        val embeddingsPath = Paths.get(embeddingsDir)
        val allChunks = mutableListOf<ChunkWithEmbedding>()
        
        if (Files.exists(embeddingsPath)) {
            Files.list(embeddingsPath).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.fileName.toString().startsWith("embeddings-") }
                    .forEach { path ->
                        val id =
                            path.fileName
                                .toString()
                                .removePrefix("embeddings-")
                                .removeSuffix(".json")
                        
                        val fileEmbeddings = loadEmbeddingsForFile(id)
                        fileEmbeddings?.let { allChunks.addAll(it.chunks) }
                    }
            }
        }

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
                // Get file metadata for each result
                val fileEmbeddings = loadEmbeddingsForFile(chunk.fileId)
                val fileMetadata = fileEmbeddings?.metadata ?: emptyMap()
                
                VectorSearchProvider.SearchResult(
                    fileId = chunk.fileId,
                    score = score.toDouble(),
                    content = chunk.content,
                    metadata = chunk.chunkMetadata + fileMetadata,
                )
            }
    }

    /**
     * Apply filters to chunks.
     * @throws IllegalArgumentException if the filter cannot be properly applied
     */
    private fun applyFilters(
        chunks: List<ChunkWithEmbedding>,
        filter: Filter?,
    ): List<ChunkWithEmbedding> {
        // If no filter, return all chunks
        if (filter == null) {
            return chunks
        }

        log.debug("Applying filter: {} to {} chunks", filter, chunks.size)
        
        try {
            // Apply the filter to each chunk
            val filteredChunks =
                chunks.filter { chunk ->
                    // Load file metadata for each chunk being filtered
                    val fileEmbeddings = loadEmbeddingsForFile(chunk.fileId)
                    val fileMetadata = fileEmbeddings?.metadata ?: emptyMap()
                    
                    // Combine chunk metadata with file metadata
                    val combinedMetadata = chunk.chunkMetadata + fileMetadata
                    val matches = FilterUtils.matchesFilter(filter, combinedMetadata, chunk.fileId)
                    matches
                }
            
            log.debug("After filtering: ${filteredChunks.size} chunks remain")
            
            return filteredChunks
        } catch (e: Exception) {
            // Re-throw with more context about security implications
            throw IllegalArgumentException("Failed to apply filter: $filter. This may impact security filters.", e)
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
            // Get vector store ID from file before deleting
            val fileEmbeddings = loadEmbeddingsForFile(fileId)
            val vectorStoreId =
                fileEmbeddings
                    ?.chunks
                    ?.firstOrNull()
                    ?.chunkMetadata
                    ?.get("vector_store_id") as? String

            // Delete the file from disk
            val embeddingsFile = Paths.get(embeddingsDir, "embeddings-$fileId.json")
            if (Files.exists(embeddingsFile)) {
                Files.delete(embeddingsFile)
                log.info("Deleted embeddings for file: $fileId")
            }

            // Delete from text search indexes via hybrid service
            if (vectorStoreId != null) {
                hybridSearchServiceHelper.let {
                    kotlinx.coroutines.runBlocking {
                        try {
                            it.deleteFileChunks(fileId, vectorStoreId)
                            log.info("Deleted chunks for file $fileId from hybrid search indexes")
                        } catch (e: Exception) {
                            log.error("Error deleting file $fileId chunks from hybrid search indexes: ${e.message}", e)
                        }
                    }
                }
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
    override fun getFileMetadata(fileId: String): Map<String, Any>? = loadEmbeddingsForFile(fileId)?.metadata
}
