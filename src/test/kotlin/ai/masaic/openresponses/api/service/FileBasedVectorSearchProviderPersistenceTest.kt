package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.config.VectorSearchConfigProperties
import ai.masaic.openresponses.api.model.ComparisonFilter
import ai.masaic.openresponses.api.service.embedding.EmbeddingService
import ai.masaic.openresponses.api.service.search.FileBasedVectorSearchProvider
import ai.masaic.openresponses.api.service.search.HybridSearchServiceHelper
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests persistence of the FileBasedVectorSearchProvider across application restarts.
 * 
 * This test class uses method ordering to simulate an application lifecycle:
 * 1. First test adds data
 * 2. Second test simulates a restart and verifies data is still available
 * 3. Third test deletes data
 * 4. Fourth test verifies deletion persisted after restart
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FileBasedVectorSearchProviderPersistenceTest {
    // Use a shared directory for all tests instead of per-test directories
    private lateinit var tempDirPath: Path
    
    private lateinit var embeddingService: EmbeddingService
    private lateinit var vectorSearchProperties: VectorSearchConfigProperties
    private lateinit var objectMapper: ObjectMapper
    private lateinit var hybridSearchServiceHelper: HybridSearchServiceHelper
    
    // File IDs used across tests to simulate persistence
    private val fileId1 = "persist-test-1"
    private val fileId2 = "persist-test-2"

    @BeforeAll
    fun setup(
        @TempDir tempDir: Path,
    ) {
        // Save the tempDir for use across all tests
        this.tempDirPath = tempDir
        
        // Create mocks and configuration that will be used for all tests
        embeddingService = mockk()
        hybridSearchServiceHelper = mockk(relaxed = true)
        every { embeddingService.embedText(any<String>()) } returns listOf(0.1f, 0.2f, 0.3f)
        every { embeddingService.embedTexts(any()) } returns
            listOf(
                listOf(0.1f, 0.2f, 0.3f),
                listOf(0.4f, 0.5f, 0.6f),
            )
        every { embeddingService.calculateSimilarity(any<List<Float>>(), any<List<Float>>()) } returns 0.85f
        
        vectorSearchProperties =
            VectorSearchConfigProperties(
                provider = "file-based",
                chunkSize = 500, 
                chunkOverlap = 100,
            )
        
        objectMapper = jacksonObjectMapper()
        
        // Create the embeddings directory
        Files.createDirectories(tempDirPath)
    }

    @AfterAll
    fun cleanup() {
        // Final cleanup after all tests
        if (Files.exists(tempDirPath.resolve("embeddings"))) {
            Files.list(tempDirPath.resolve("embeddings")).forEach { Files.deleteIfExists(it) }
            Files.deleteIfExists(tempDirPath.resolve("embeddings"))
        }
    }

    /**
     * First "application run" - adds data to the vector store
     */
    @Test
    @Order(1)
    fun `first run - add documents to vector store`() {
        // Create a fresh provider instance (first application run)
        val provider =
            FileBasedVectorSearchProvider(
                embeddingService,
                objectMapper,
                tempDirPath.toString(),
                hybridSearchServiceHelper,
            )
        
        // Add first document
        val content1 = "This is the first test document that should persist across application restarts."
        provider.indexFile(fileId1, ByteArrayInputStream(content1.toByteArray()), "file1.txt", null, "test")
        
        // Add second document
        val content2 = "This is the second test document with different content."
        provider.indexFile(fileId2, ByteArrayInputStream(content2.toByteArray()), "file2.txt", null, "test")
        
        // Verify indexing worked in this session - using a general query should find both
        val results = provider.searchSimilar("test document", rankingOptions = null)
        assertEquals(2, results.size, "Should find both indexed documents")
        
        // Verify files were created on disk
        val embeddingsDir = tempDirPath.resolve("embeddings")
        assertTrue(Files.exists(embeddingsDir.resolve("embeddings-$fileId1.json")), "First embeddings file should exist")
        assertTrue(Files.exists(embeddingsDir.resolve("embeddings-$fileId2.json")), "Second embeddings file should exist")
    }

    /**
     * Second "application run" - verifies data persisted
     */
    @Test
    @Order(2)
    fun `second run - verify documents persisted from first run`() {
        // Create a new provider instance (simulating application restart)
        val provider =
            FileBasedVectorSearchProvider(
                embeddingService,
                objectMapper,
                tempDirPath.toString(),
                hybridSearchServiceHelper,
            )
        
        // Verify documents can be found after "restart"
        val results = provider.searchSimilar("test document", rankingOptions = null)
        assertEquals(2, results.size, "Should find both documents after restart")
        
        // Verify specific documents by filtering with fileId
        val filter1 = ComparisonFilter(key = "file_id", type = "eq", value = fileId1)
        val firstResults =
            provider.searchSimilar(
                query = "document",
                rankingOptions = null,
                filter = filter1,
            )
        assertEquals(1, firstResults.size, "Should find only first document when filtered")
        assertEquals(fileId1, firstResults[0].fileId, "Should return the first file ID")
        
        val filter2 = ComparisonFilter(key = "file_id", type = "eq", value = fileId2)
        val secondResults =
            provider.searchSimilar(
                query = "document",
                rankingOptions = null,
                filter = filter2,
            )
        assertEquals(1, secondResults.size, "Should find only second document when filtered")
        assertEquals(fileId2, secondResults[0].fileId, "Should return the second file ID")
    }

    /**
     * Third "application run" - deletes data
     */
    @Test
    @Order(3)
    fun `third run - delete first document`() {
        // Create a new provider instance (third application run)
        val provider =
            FileBasedVectorSearchProvider(
                embeddingService,
                objectMapper,
                tempDirPath.toString(),
                hybridSearchServiceHelper,
            )
        
        // Delete the first document
        val deleted = provider.deleteFile(fileId1)
        assertTrue(deleted, "Should successfully delete the file")
        
        // Verify document is gone from this instance
        val results = provider.searchSimilar("test document", rankingOptions = null)
        assertEquals(1, results.size, "Should only find the second document")
        assertEquals(fileId2, results[0].fileId, "Should only return the second file ID")
        
        // Verify first file was deleted from disk
        val embeddingsDir = tempDirPath.resolve("embeddings")
        assertTrue(!Files.exists(embeddingsDir.resolve("embeddings-$fileId1.json")), "First embeddings file should be deleted")
        assertTrue(Files.exists(embeddingsDir.resolve("embeddings-$fileId2.json")), "Second embeddings file should still exist")
    }

    /**
     * Fourth "application run" - verifies deletion persisted
     */
    @Test
    @Order(4)
    fun `fourth run - verify deletion persisted`() {
        // Create a new provider instance (fourth application run)
        val provider =
            FileBasedVectorSearchProvider(
                embeddingService,
                objectMapper,
                tempDirPath.toString(),
                hybridSearchServiceHelper,
            )
        
        // Verify only the second document is still available
        val results = provider.searchSimilar("test document", rankingOptions = null)
        assertEquals(1, results.size, "Should only find the second document after restart")
        assertEquals(fileId2, results[0].fileId, "Should only return the second file ID")
        
        // Verify there's no trace of the first document after restart by explicitly searching for it
        val filter1 = ComparisonFilter(key = "file_id", type = "eq", value = fileId1)
        val firstResults =
            provider.searchSimilar(
                query = "document",
                rankingOptions = null,
                filter = filter1,
            )
        assertEquals(0, firstResults.size, "Should not find the deleted first document")
    }
} 
