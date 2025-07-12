package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.config.VectorSearchConfigProperties
import ai.masaic.openresponses.api.service.embedding.EmbeddingService
import ai.masaic.openresponses.api.service.search.FileBasedVectorSearchProvider
import ai.masaic.openresponses.api.service.search.HybridSearchServiceHelper
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileBasedVectorSearchProviderIntegrationTest {
    @TempDir
    lateinit var tempDir: Path
    
    private lateinit var embeddingService: EmbeddingService
    private lateinit var vectorSearchProperties: VectorSearchConfigProperties
    private lateinit var objectMapper: ObjectMapper
    private lateinit var vectorSearchProvider: FileBasedVectorSearchProvider
    private lateinit var hybridSearchServiceHelper: HybridSearchServiceHelper

    @BeforeEach
    fun setup() {
        // Mock the embedding service - always return the same similarity so we 
        // can predict search behavior regardless of query
        hybridSearchServiceHelper = mockk(relaxed = true)
        embeddingService = mockk()
        
        // Configure the embedding service to always return the same embedding and similarity score
        // This ensures searches are predictable based on mocked similarity rather than actual text matching
        every { embeddingService.embedText(any<String>()) } returns listOf(0.1f, 0.2f, 0.3f)
        every { embeddingService.embedTexts(any()) } returns
            listOf(
                listOf(0.1f, 0.2f, 0.3f),
                listOf(0.4f, 0.5f, 0.6f),
            )
        every { embeddingService.calculateSimilarity(any<List<Float>>(), any<List<Float>>()) } returns 0.85f
        
        // Create vector search properties with default values
        vectorSearchProperties =
            VectorSearchConfigProperties(
                provider = "file-based",
                chunkSize = 500,
                chunkOverlap = 100,
            )
        
        // Use a real ObjectMapper
        objectMapper = jacksonObjectMapper()
        
        // Create test directory if it doesn't exist
        Files.createDirectories(tempDir)
        
        // Initialize provider with temp directory
        vectorSearchProvider =
            FileBasedVectorSearchProvider(
                embeddingService,
                objectMapper,
                tempDir.toString(),
                hybridSearchServiceHelper,
            )
    }

    @AfterEach
    fun cleanup() {
        // Clean up test files
        if (Files.exists(tempDir.resolve("embeddings"))) {
            Files.list(tempDir.resolve("embeddings")).forEach { Files.deleteIfExists(it) }
            Files.deleteIfExists(tempDir.resolve("embeddings"))
        }
    }

    @Test
    fun `simulating application restart should preserve embeddings`() {
        // Given - index some files
        val fileId = "persistence-test"
        val content = "This is a test document that should persist across application restarts."
        val filename = "persistence.txt"
        
        // First "application instance" indexes a file
        vectorSearchProvider.indexFile(fileId, ByteArrayInputStream(content.toByteArray()), filename, null, "test")
        
        // Verify the file was properly indexed
        val results1 = vectorSearchProvider.searchSimilar("test document", rankingOptions = null)
        assertEquals(1, results1.size, "Should find the indexed document")
        
        // When - simulate application restart by creating a new provider instance
        val restartedProvider =
            FileBasedVectorSearchProvider(
                embeddingService,
                objectMapper,
                tempDir.toString(),
                hybridSearchServiceHelper,
            )
        
        // Then - the new instance should still have access to the previously indexed data
        val results2 = restartedProvider.searchSimilar("test document", rankingOptions = null)
        assertEquals(1, results2.size, "Should still find the document after restart")
        assertEquals(fileId, results2[0].fileId, "Should return the same file ID")
        
        // Verify the embeddings file exists on disk
        val embeddingsFile = tempDir.resolve("embeddings").resolve("embeddings-$fileId.json")
        assertTrue(Files.exists(embeddingsFile), "Embeddings file should exist on disk")
    }

    @Test
    fun `multiple providers should access same persisted data`() {
        // Given - create and index with first provider
        val fileId1 = "file1"
        val content1 = "First test document with specific content."
        
        vectorSearchProvider.indexFile(fileId1, ByteArrayInputStream(content1.toByteArray()), "file1.txt", null, "test")
        
        // When - create a second provider instance (simulating a different service/component)
        val secondProvider =
            FileBasedVectorSearchProvider(
                embeddingService,
                objectMapper,
                tempDir.toString(),
                hybridSearchServiceHelper,
            )
        
        // Then - second provider should see the first provider's data
        val resultsFromSecond =
            secondProvider.searchSimilar(
                "specific content",
                rankingOptions = null,
            )
        assertEquals(1, resultsFromSecond.size, "Second provider should find document from first provider")
        
        // When - second provider adds a document
        val fileId2 = "file2"
        val content2 = "Second test document with different content."
        secondProvider.indexFile(fileId2, ByteArrayInputStream(content2.toByteArray()), "file2.txt", null, "test")
        
        // Then - first provider should see the second provider's data
        val resultsFromFirst =
            vectorSearchProvider.searchSimilar(
                "different content",
                rankingOptions = null,
            )
        assertEquals(2, resultsFromFirst.size, "First provider should find document from second provider")
        
        assertEquals(
            2,
            secondProvider.searchSimilar("test document", rankingOptions = null).size,
            "Should find both documents in second provider",
        )
    }

    @Test
    fun `deleting file should remove from all provider instances`() {
        // Given - create and index with first provider
        val fileId = "delete-test"
        val content = "This is a document that will be deleted."
        
        vectorSearchProvider.indexFile(fileId, ByteArrayInputStream(content.toByteArray()), "delete.txt", null, "test")
        
        // Create a second provider instance
        val secondProvider =
            FileBasedVectorSearchProvider(
                embeddingService,
                objectMapper,
                tempDir.toString(),
                hybridSearchServiceHelper,
            )
        
        // Verify both providers can see the document
        assertTrue(
            vectorSearchProvider.searchSimilar("document", rankingOptions = null).isNotEmpty(),
            "First provider should find the document",
        )
        assertTrue(
            secondProvider.searchSimilar("document", rankingOptions = null).isNotEmpty(),
            "Second provider should find the document",
        )
        
        // When - delete using the first provider
        vectorSearchProvider.deleteFile(fileId)
        
        // Then - document should be gone from first provider immediately
        assertTrue(
            vectorSearchProvider.searchSimilar("document", rankingOptions = null).isEmpty(),
            "First provider should not find deleted document",
        )
        
        // Create a new second provider to force reload from disk
        val refreshedSecondProvider =
            FileBasedVectorSearchProvider(
                embeddingService,
                objectMapper,
                tempDir.toString(),
                hybridSearchServiceHelper,
            )
        
        // Document should be gone from refreshed second provider
        assertTrue(
            refreshedSecondProvider.searchSimilar("document", rankingOptions = null).isEmpty(),
            "Refreshed second provider should not find deleted document",
        )
        
        // Verify the file is gone from disk
        val embeddingsFile = tempDir.resolve("embeddings").resolve("embeddings-$fileId.json")
        assertTrue(!Files.exists(embeddingsFile), "Embeddings file should be deleted from disk")
    }
} 
