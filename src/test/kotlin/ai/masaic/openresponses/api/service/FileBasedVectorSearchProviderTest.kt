package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.config.VectorSearchProperties
import ai.masaic.openresponses.api.utils.DocumentTextExtractor
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileBasedVectorSearchProviderTest {
    @TempDir
    lateinit var tempDir: Path
    
    private lateinit var embeddingService: EmbeddingService
    private lateinit var vectorSearchProperties: VectorSearchProperties
    private lateinit var objectMapper: ObjectMapper
    private lateinit var vectorSearchProvider: FileBasedVectorSearchProvider

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
                provider = "file-based",
                chunkSize = 1000,
                chunkOverlap = 200,
            )
        
        // Use a real ObjectMapper
        objectMapper = jacksonObjectMapper()
        
        // Create the provider with a temp directory
        vectorSearchProvider =
            FileBasedVectorSearchProvider(
                embeddingService,
                vectorSearchProperties,
                objectMapper,
                tempDir.toString(),
            )
    }

    @AfterEach
    fun cleanup() {
        // Clean up any files created during tests
        if (Files.exists(tempDir.resolve("embeddings"))) {
            Files.list(tempDir.resolve("embeddings")).forEach { Files.deleteIfExists(it) }
        }
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
        
        // Check that the embeddings file was created
        val embeddingsFile = tempDir.resolve("embeddings").resolve("$fileId.json")
        assertTrue(Files.exists(embeddingsFile), "Embeddings file should exist")
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
    fun `deleteFile should remove file from index and filesystem`() {
        // Given
        val fileId = "test-file-id"
        val content = "Test content"
        vectorSearchProvider.indexFile(fileId, ByteArrayInputStream(content.toByteArray()), "test.txt")
        
        // Verify file exists before deletion
        val embeddingsFile = tempDir.resolve("embeddings").resolve("$fileId.json")
        assertTrue(Files.exists(embeddingsFile), "Embeddings file should exist before deletion")
        
        // When
        val result = vectorSearchProvider.deleteFile(fileId)
        
        // Then
        assertTrue(result, "File should be successfully deleted")
        assertTrue(!Files.exists(embeddingsFile), "Embeddings file should be deleted from filesystem")
        
        // Verify the file is no longer in the index by searching for it
        val searchResults = vectorSearchProvider.searchSimilar("Test", 10, mapOf("fileId" to fileId))
        assertEquals(0, searchResults.size, "No results should be found for the deleted file")
    }

    @Test
    fun `persistence should work across provider instances`() {
        // Given
        val fileId = "persistence-test-file"
        val content = "This is a document that should persist across provider instances."
        val filename = "persistence.txt"
        
        // First instance creates and indexes a file
        vectorSearchProvider.indexFile(fileId, ByteArrayInputStream(content.toByteArray()), filename)
        
        // When - create a new instance
        val secondProvider =
            FileBasedVectorSearchProvider(
                embeddingService,
                vectorSearchProperties,
                objectMapper,
                tempDir.toString(),
            )
        
        // Then - the new instance should load the existing embeddings
        val results = secondProvider.searchSimilar("document persist", 10, null)
        
        assertEquals(1, results.size, "Should return one result from the persisted data")
        assertEquals(fileId, results[0].fileId, "Result should contain the persisted file ID")
    }

    @Test
    fun `multiple files should be indexed and searchable`() {
        // Given - index multiple files with similar content
        val fileIds = listOf("file1", "file2", "file3")
        val contents =
            listOf(
                "This document is about artificial intelligence.",
                "This document concerns machine learning topics.",
                "This document discusses neural networks and deep learning.",
            )
        val filenames = listOf("ai.txt", "ml.txt", "nn.txt")
        
        // Index all files
        fileIds.forEachIndexed { index, fileId ->
            vectorSearchProvider.indexFile(
                fileId,
                ByteArrayInputStream(contents[index].toByteArray()),
                filenames[index],
            )
        }
        
        // When - search with a query relevant to all documents
        val results = vectorSearchProvider.searchSimilar("learning", 10, null)
        
        // Then - should find all documents (since we're mocking similarity)
        assertEquals(3, results.size, "Should return all indexed documents")
        
        // All embeddings files should exist
        fileIds.forEach { fileId ->
            val embeddingsFile = tempDir.resolve("embeddings").resolve("$fileId.json")
            assertTrue(Files.exists(embeddingsFile), "Embeddings file should exist for $fileId")
        }
    }
} 
