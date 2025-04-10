package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.model.ChunkingStrategy
import ai.masaic.openresponses.api.model.RankingOptions
import ai.masaic.openresponses.api.service.search.VectorSearchProvider
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Mock implementation of VectorSearchProvider for testing.
 * This stores indexed content in memory and provides basic search functionality.
 */
@Component
@Profile("test")
@Primary
class MockVectorSearchProvider : VectorSearchProvider {
    private val indexedFiles = ConcurrentHashMap<String, IndexedFile>()

    data class IndexedFile(
        val fileId: String,
        val content: String,
        val filename: String,
        val metadata: Map<String, Any> = emptyMap(),
    )

    override fun indexFile(
        fileId: String,
        content: InputStream,
        filename: String,
        chunkingStrategy: ChunkingStrategy?,
    ): Boolean {
        try {
            val contentString = content.readAllBytes().toString(Charsets.UTF_8)
            indexedFiles[fileId] = IndexedFile(fileId, contentString, filename)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    override fun searchSimilar(
        query: String,
        maxResults: Int,
        filters: Map<String, Any>?,
        rankingOptions: RankingOptions?,
    ): List<VectorSearchProvider.SearchResult> {
        // Very simplistic search - just checks if query exists in content
        val results =
            indexedFiles.values
                .filter { it.content.contains(query, ignoreCase = true) }
                .map { indexedFile ->
                    VectorSearchProvider.SearchResult(
                        fileId = indexedFile.fileId,
                        score = 0.9, // Mock score
                        content = indexedFile.content,
                        metadata = mapOf("filename" to indexedFile.filename),
                    )
                }.take(maxResults)
        
        return results
    }

    override fun deleteFile(fileId: String): Boolean = indexedFiles.remove(fileId) != null

    override fun getFileMetadata(fileId: String): Map<String, Any>? {
        val file = indexedFiles[fileId] ?: return null
        return mapOf(
            "filename" to file.filename,
        ) + file.metadata
    }

    /**
     * Test helper method to get all indexed files.
     */
    fun getIndexedFiles(): Map<String, IndexedFile> = indexedFiles.toMap()

    /**
     * Test helper method to clear all indexed files.
     */
    fun clearAll() {
        indexedFiles.clear()
    }
} 
