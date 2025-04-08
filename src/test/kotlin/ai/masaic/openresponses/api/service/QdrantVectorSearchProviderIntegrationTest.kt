package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.config.QdrantProperties
import ai.masaic.openresponses.api.config.VectorSearchConfiguration
import ai.masaic.openresponses.api.config.VectorSearchProperties
import ai.masaic.openresponses.api.model.ChunkingStrategy
import ai.masaic.openresponses.api.model.StaticChunkingConfig
import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.data.segment.TextSegment
import io.mockk.every
import io.mockk.mockk
import io.qdrant.client.QdrantClient
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = ["open-responses.vector-store.provider=qdrant"])
@Disabled("Enable this test to run the complete workflow") // Enable this line to run the test
class QdrantVectorSearchProviderIntegrationTest {
    companion object {
        @Container
        private val qdrantContainer =
            GenericContainer(DockerImageName.parse("qdrant/qdrant:latest"))
                .withExposedPorts(6333, 6334)

        @JvmStatic
        @DynamicPropertySource
        fun setProperties(registry: DynamicPropertyRegistry) {
            registry.add("open-responses.vector-store.qdrant.host") {
                qdrantContainer.host
            }
            registry.add("open-responses.vector-store.qdrant.port") {
                qdrantContainer.getMappedPort(6334)
            }
        }
    }

    private lateinit var embeddingService: EmbeddingService
    private lateinit var qdrantProperties: QdrantProperties
    private lateinit var vectorSearchProperties: VectorSearchProperties
    private lateinit var vectorSearchProvider: QdrantVectorSearchProvider
    private lateinit var qdrantClient: QdrantClient

    @BeforeEach
    fun setup() {
        // Mock embedding service to return predictable embeddings
        embeddingService = mockk()
        every { embeddingService.embedText(any()) } answers { 
            // Create simple embeddings based on the input text's hashcode
            // This ensures different inputs give different but deterministic embeddings
            val text = firstArg<String>()
            val hashCode = text.hashCode().toFloat()
            val baseValue = (hashCode % 100) / 100f
            List(384) { i -> (baseValue + (i.toFloat() / 1000f)) % 1f }
        }
        
        // Configure vector search properties
        qdrantProperties = QdrantProperties(
            host = qdrantContainer.host,
            port = qdrantContainer.getMappedPort(6334),
            useTls = false,
            collectionName = "test-collection-${UUID.randomUUID()}",
            vectorDimension = 384
        )
        
        vectorSearchProperties = VectorSearchProperties(
            provider = "qdrant",
            chunkSize = 300,
            chunkOverlap = 50
        )
        
        // Create Qdrant client
        qdrantClient = QdrantClient(
            io.qdrant.client.QdrantGrpcClient.newBuilder(
                qdrantProperties.host,
                qdrantProperties.port,
                qdrantProperties.useTls
            ).build()
        )
        
        // Create the vector search provider
        vectorSearchProvider = QdrantVectorSearchProvider(
            embeddingService = embeddingService,
            qdrantProperties = qdrantProperties,
            vectorSearchProperties = vectorSearchProperties,
            client = qdrantClient
        )
    }

    @AfterEach
    fun cleanup() {
        // Clean up the collection after test
        try {
            qdrantClient.deleteCollectionAsync(qdrantProperties.collectionName!!).get()
        } catch (e: Exception) {
            // Ignore errors during cleanup
        }
    }

    @Test
    fun `complete workflow test`() = runTest {
        // 1. Prepare test data
        val fileId1 = "test-file-" + UUID.randomUUID().toString()
        val fileId2 = "test-file-" + UUID.randomUUID().toString()
        
        val content1 = "This is the first test document for Qdrant vector search."
        val content2 = "This is the second test document containing different information about machine learning and AI."
        
        // 2. Index files
        val result1 = vectorSearchProvider.indexFile(
            fileId = fileId1,
            inputStream = ByteArrayInputStream(content1.toByteArray()),
            filename = "test1.txt"
        )
        
        val result2 = vectorSearchProvider.indexFile(
            fileId = fileId2,
            inputStream = ByteArrayInputStream(content2.toByteArray()),
            filename = "test2.txt",
            chunkingStrategy = ChunkingStrategy(
                type = "static",
                static = StaticChunkingConfig(
                    maxChunkSizeTokens = 150,
                    chunkOverlapTokens = 20
                )
            )
        )
        
        assertTrue(result1, "First file should be indexed successfully")
        assertTrue(result2, "Second file should be indexed successfully")
        
        // 3. Search for content
        val searchResults1 = vectorSearchProvider.searchSimilar(
            query = "test document vector",
            maxResults = 5
        )
        
        assertTrue(searchResults1.isNotEmpty(), "Search should return results")
        assertTrue(searchResults1.any { it.fileId == fileId1 }, "Results should include the first file")
        
        // 4. Search with filters
        val filteredResults = vectorSearchProvider.searchSimilar(
            query = "test document",
            maxResults = 5,
            filters = mapOf("fileIds" to listOf(fileId2))
        )
        
        assertTrue(filteredResults.isNotEmpty(), "Filtered search should return results")
        assertTrue(filteredResults.all { it.fileId == fileId2 }, "Filtered results should only include the second file")
        
        // 5. Get file metadata
        val metadata1 = vectorSearchProvider.getFileMetadata(fileId1)
        assertNotNull(metadata1, "Metadata should be found for file 1")
        assertEquals("test1.txt", metadata1["filename"], "Metadata should contain correct filename")
        
        // 6. Delete a file
        val deleteResult = vectorSearchProvider.deleteFile(fileId1)
        assertTrue(deleteResult, "File deletion should succeed")
        
        // 7. Verify file is deleted
        val searchAfterDeletion = vectorSearchProvider.searchSimilar(
            query = "test document",
            filters = mapOf("fileIds" to listOf(fileId1))
        )
        
        assertTrue(searchAfterDeletion.isEmpty(), "Search for deleted file should return no results")
        
        // 8. Verify other file still exists
        val remainingFile = vectorSearchProvider.getFileMetadata(fileId2)
        assertNotNull(remainingFile, "Metadata for non-deleted file should still exist")
    }
    
    @Test
    fun `indexFile should handle invalid content gracefully`() = runTest {
        // Test with empty content
        val emptyResult = vectorSearchProvider.indexFile(
            fileId = "empty-file",
            inputStream = ByteArrayInputStream("".toByteArray()),
            filename = "empty.txt"
        )
        
        assertFalse(emptyResult, "Indexing empty file should fail gracefully")
    }
    
    @Test
    fun `searchSimilar should handle empty query gracefully`() = runTest {
        // Index a test file first
        val fileId = "test-file-" + UUID.randomUUID().toString()
        val content = "This is a test document."
        
        vectorSearchProvider.indexFile(
            fileId = fileId,
            inputStream = ByteArrayInputStream(content.toByteArray()),
            filename = "test.txt"
        )
        
        // Search with empty query
        val emptyResults = vectorSearchProvider.searchSimilar(query = "", maxResults = 5)
        
        assertTrue(emptyResults.isEmpty(), "Empty query should return no results")
    }
} 
