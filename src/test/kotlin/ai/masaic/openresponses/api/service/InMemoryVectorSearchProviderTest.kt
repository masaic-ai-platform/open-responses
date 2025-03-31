package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.config.VectorSearchProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryVectorSearchProviderTest {
    private lateinit var embeddingService: EmbeddingService
    private lateinit var vectorSearchProperties: VectorSearchProperties
    private lateinit var vectorSearchProvider: InMemoryVectorSearchProvider

    @BeforeEach
    fun setup() {
        // Mock the embedding service
        embeddingService = mockk()
        
        // Configure default behavior
        every { embeddingService.embedText(any()) } returns listOf(0.1f, 0.2f, 0.3f)
        every { embeddingService.calculateSimilarity(any(), any()) } returns 0.85f
        
        // Create vector search properties with default values
        vectorSearchProperties =
            VectorSearchProperties(
                provider = "in-memory",
                chunkSize = 1000,
                chunkOverlap = 200,
            )
        
        // Create the provider
        vectorSearchProvider = InMemoryVectorSearchProvider(embeddingService, vectorSearchProperties)
    }

    @Test
    fun `indexFile should process and index file content`() {
        // Given
        val fileId = "test-file-id"
        val filename = "test.txt"
        val content = "This is a test document for vector indexing."
        val inputStream = ByteArrayInputStream(content.toByteArray())
        
        // When
        val result = vectorSearchProvider.indexFile(fileId, inputStream, filename)
        
        // Then
        assertTrue(result, "File should be successfully indexed")
        verify { embeddingService.embedText(any()) }
    }

    @Test
    fun `searchSimilar should return matching results`() {
        // Given
        // Index a file first
        val fileId = "test-file-id"
        val filename = "test.txt"
        val content = "This is a test document for vector indexing."
        val inputStream = ByteArrayInputStream(content.toByteArray())
        vectorSearchProvider.indexFile(fileId, inputStream, filename)
        
        // Configure similarity score for the search query
        val query = "test document"
        
        // When
        val results = vectorSearchProvider.searchSimilar(query, 10, null)
        
        // Then
        assertEquals(1, results.size, "Should return one result")
        assertEquals(fileId, results[0].fileId, "Result should contain the indexed file ID")
        assertEquals(0.85f.toDouble(), results[0].score, "Score should match the mocked similarity")
    }

    @Test
    fun `searchSimilar should apply filters correctly`() {
        // Given
        // Index multiple files
        val fileId1 = "file-1"
        val fileId2 = "file-2"
        val content1 = "This is document one."
        val content2 = "This is document two."
        
        vectorSearchProvider.indexFile(fileId1, ByteArrayInputStream(content1.toByteArray()), "doc1.txt")
        vectorSearchProvider.indexFile(fileId2, ByteArrayInputStream(content2.toByteArray()), "doc2.txt")
        
        // When - search with a filter for fileId1
        val results = vectorSearchProvider.searchSimilar("document", 10, mapOf("fileId" to fileId1))
        
        // Then
        assertEquals(1, results.size, "Should return only one result")
        assertEquals(fileId1, results[0].fileId, "Result should be the filtered file ID")
    }

    @Test
    fun `deleteFile should remove file from index`() {
        // Given
        val fileId = "test-file-id"
        val content = "Test content"
        vectorSearchProvider.indexFile(fileId, ByteArrayInputStream(content.toByteArray()), "test.txt")
        
        // When
        val result = vectorSearchProvider.deleteFile(fileId)
        
        // Then
        assertTrue(result, "File should be successfully deleted")
        
        // Verify the file is no longer in the index by searching for it
        val searchResults = vectorSearchProvider.searchSimilar("Test", 10, mapOf("fileId" to fileId))
        assertEquals(0, searchResults.size, "No results should be found for the deleted file")
    }

    @Test
    fun `indexFile should handle empty files gracefully`() {
        // Given
        val fileId = "empty-file"
        val content = ""
        
        // When
        val result = vectorSearchProvider.indexFile(fileId, ByteArrayInputStream(content.toByteArray()), "empty.txt")
        
        // Then
        assertTrue(result, "Empty file should be indexed successfully")
    }
} 
