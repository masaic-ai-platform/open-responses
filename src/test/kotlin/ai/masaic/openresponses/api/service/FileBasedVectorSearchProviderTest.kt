package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.config.VectorSearchConfigProperties
import ai.masaic.openresponses.api.model.ChunkingStrategy
import ai.masaic.openresponses.api.model.ComparisonFilter
import ai.masaic.openresponses.api.model.StaticChunkingConfig
import ai.masaic.openresponses.api.service.embedding.EmbeddingService
import ai.masaic.openresponses.api.service.search.FileBasedVectorSearchProvider
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileBasedVectorSearchProviderTest {
    @TempDir
    lateinit var tempDir: Path
    
    private lateinit var embeddingService: EmbeddingService
    private lateinit var vectorSearchProperties: VectorSearchConfigProperties
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
            VectorSearchConfigProperties(
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
        val results = vectorSearchProvider.searchSimilar(query, rankingOptions = null)
        
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
        val filter = ComparisonFilter(key = "file_id", type = "eq", value = fileId1)
        val results =
            vectorSearchProvider.searchSimilar(
                query = "document",
                rankingOptions = null,
                filter = filter,
            )
        
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
        val fileIdFilter = ComparisonFilter(key = "file_id", type = "eq", value = fileId)
        val searchResults =
            vectorSearchProvider.searchSimilar(
                query = "Test",
                rankingOptions = null,
                filter = fileIdFilter,
            )
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
                objectMapper,
                tempDir.toString(),
            )
        
        // Then - the new instance should load the existing embeddings
        val results = secondProvider.searchSimilar("document persist", rankingOptions = null)
        
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
        val results = vectorSearchProvider.searchSimilar("learning", rankingOptions = null)
        
        // Then - should find all documents (since we're mocking similarity)
        assertEquals(3, results.size, "Should return all indexed documents")
        
        // All embeddings files should exist
        fileIds.forEach { fileId ->
            val embeddingsFile = tempDir.resolve("embeddings").resolve("$fileId.json")
            assertTrue(Files.exists(embeddingsFile), "Embeddings file should exist for $fileId")
        }
    }

    @Test
    fun `indexFile should handle errors from embedding service`() {
        // Given
        val fileId = "error-test-file"
        val content = "This is a test document for error handling."
        val inputStream = ByteArrayInputStream(content.toByteArray())
        
        // Mock embedding service to throw exception
        every { embeddingService.embedText(any()) } throws RuntimeException("Embedding service error")
        
        // When
        val result = vectorSearchProvider.indexFile(fileId, inputStream, "error-test.txt")
        
        // Then
        assertFalse(result, "Indexing should fail when embedding service throws an error")
    }

    @Test
    fun `indexFile should handle empty text content`() {
        // Given
        val fileId = "empty-content-file"
        val content = ""
        val inputStream = ByteArrayInputStream(content.toByteArray())
        
        // When
        val result = vectorSearchProvider.indexFile(fileId, inputStream, "empty.txt")
        
        // Then
        assertFalse(result, "Indexing should fail for empty content")
    }

    @Test
    fun `indexFile should use custom chunking strategy when provided`() {
        // Given
        val fileId = "chunking-test-file"
        val content = "This is a test document for custom chunking strategy."
        val inputStream = ByteArrayInputStream(content.toByteArray())
        
        // Create custom chunking strategy
        val staticChunkingStrategy = mockk<StaticChunkingConfig>()
        every { staticChunkingStrategy.maxChunkSizeTokens } returns 50
        every { staticChunkingStrategy.chunkOverlapTokens } returns 10
        
        val chunkingStrategy = mockk<ChunkingStrategy>()
        every { chunkingStrategy.type } returns "static"
        every { chunkingStrategy.static } returns staticChunkingStrategy
        
        // When
        val result = vectorSearchProvider.indexFile(fileId, inputStream, "chunking-test.txt", chunkingStrategy)
        
        // Then
        assertTrue(result, "File should be successfully indexed with custom chunking strategy")
    }

    @Test
    fun `getFileMetadata should return metadata for existing file`() {
        // Given
        val fileId = "metadata-test-file"
        val filename = "metadata-test.txt"
        val content = "This is a test document for metadata retrieval."
        val inputStream = ByteArrayInputStream(content.toByteArray())
        
        // Index the file
        vectorSearchProvider.indexFile(fileId, inputStream, filename)
        
        // When
        val metadata = vectorSearchProvider.getFileMetadata(fileId)
        
        // Then
        assertNotNull(metadata, "Metadata should not be null for existing file")
        assertEquals(filename, metadata["filename"], "Metadata should contain correct filename")
    }

    @Test
    fun `getFileMetadata should return null for non-existent file`() {
        // Given
        val fileId = "non-existent-file"
        
        // When
        val metadata = vectorSearchProvider.getFileMetadata(fileId)
        
        // Then
        assertNull(metadata, "Metadata should be null for non-existent file")
    }

    @Test
    fun `searchSimilar should handle empty query`() {
        // Given
        val fileId = "test-file-id"
        val content = "This is a test document."
        val inputStream = ByteArrayInputStream(content.toByteArray())
        
        // Index a file
        vectorSearchProvider.indexFile(fileId, inputStream, "test.txt")
        
        // When
        val results = vectorSearchProvider.searchSimilar("", rankingOptions = null)
        
        // Then
        assertTrue(results.isEmpty(), "Should return empty results for empty query")
    }

    @Test
    fun `performance test with large number of documents`() {
        // Skip in CI environments
        if (System.getenv("CI") != null) {
            return
        }
        
        // Given - a large number of documents (but still reasonable for a unit test)
        val numDocuments = 100
        val documentsAndIds =
            (1..numDocuments).map { 
                val fileId = "perf-file-$it"
                val content = "This is performance test document number $it with some unique content: ${java.util.UUID.randomUUID()}"
                fileId to content
            }
        
        // Index all documents
        documentsAndIds.forEach { (fileId, content) ->
            vectorSearchProvider.indexFile(fileId, ByteArrayInputStream(content.toByteArray()), "$fileId.txt")
        }
        
        // When - perform a search
        val startTime = System.currentTimeMillis()
        val results =
            vectorSearchProvider.searchSimilar(
                "performance test document",
                rankingOptions = null,
            )
        val duration = System.currentTimeMillis() - startTime
        
        // Then
        // Just verify we got results and log the time - no hard assertions on time as it's environment-dependent
        assertTrue(results.isNotEmpty(), "Should return results for the query")
        println("Search across $numDocuments documents completed in $duration ms")
    }
} 
