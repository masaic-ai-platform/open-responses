package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.config.QdrantVectorProperties
import ai.masaic.openresponses.api.config.VectorSearchConfigProperties
import ai.masaic.openresponses.api.model.ChunkingStrategy
import ai.masaic.openresponses.api.model.ComparisonFilter
import ai.masaic.openresponses.api.model.StaticChunkingConfig
import ai.masaic.openresponses.api.service.embedding.EmbeddingService
import ai.masaic.openresponses.api.service.search.HybridSearchServiceHelper
import ai.masaic.openresponses.api.service.search.QdrantVectorSearchProvider
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dev.langchain4j.data.document.Metadata
import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.store.embedding.EmbeddingMatch
import dev.langchain4j.store.embedding.EmbeddingSearchResult
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore
import io.mockk.*
import io.qdrant.client.QdrantClient
import io.qdrant.client.grpc.Collections
import io.qdrant.client.grpc.Collections.CollectionOperationResponse
import io.qdrant.client.grpc.Points
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for QdrantVectorSearchProvider.
 */
class QdrantVectorSearchProviderTest {
    private lateinit var embeddingService: EmbeddingService
    private lateinit var qdrantProperties: QdrantVectorProperties
    private lateinit var vectorSearchProperties: VectorSearchConfigProperties
    private lateinit var qdrantClient: QdrantClient
    private lateinit var embeddingStore: QdrantEmbeddingStore
    private lateinit var vectorSearchProvider: QdrantVectorSearchProvider
    private lateinit var hybridSearchServiceHelper: HybridSearchServiceHelper

    @BeforeEach
    fun setup() {
        // Mock embedding service
        embeddingService = mockk()
        every { embeddingService.embedText(any()) } returns listOf(0.1f, 0.2f, 0.3f)
        every { embeddingService.embedTexts(any()) } returns
            listOf(
                listOf(0.1f, 0.2f, 0.3f),
                listOf(0.4f, 0.5f, 0.6f),
            )

        hybridSearchServiceHelper = mockk(relaxed = true)
        
        // Mock Qdrant client
        qdrantClient = mockk()
        
        // Mock collection operations
        val collectionList = mockk<Collections.CollectionDescription>()
        every { collectionList.name } returns "another-collection"
        
        val collectionResponse = mockk<Collections.ListCollectionsResponse>()
        every { collectionResponse.collectionsList } returns listOf(collectionList)

        val collectionsFuture: ListenableFuture<List<String>> =
            MoreExecutors.newDirectExecutorService().submit<List<String>> {
                collectionResponse.collectionsList.map { it.name }
            }

        every { qdrantClient.listCollectionsAsync() } returns collectionsFuture
        
        // Mock collection creation
        val createFuture = MoreExecutors.newDirectExecutorService().submit<CollectionOperationResponse> { mockk<CollectionOperationResponse>() }
        every { 
            qdrantClient.createCollectionAsync(any(), any<Collections.VectorParams>()) 
        } returns createFuture

        val createIndexFuture = MoreExecutors.newDirectExecutorService().submit<Points.UpdateResult> { mockk<Points.UpdateResult>() }
        every {
            qdrantClient.createPayloadIndexAsync(any(), any(), any(), null, true, null, Duration.ofSeconds(10))
        } returns createIndexFuture
        
        // Mock embedding store 
        embeddingStore = mockk<QdrantEmbeddingStore>()
        
        // Mock QdrantEmbeddingStore.builder() static method
        mockkStatic(QdrantEmbeddingStore::class)
        
        val embeddingStoreBuilder = mockk<QdrantEmbeddingStore.Builder>()
        every { QdrantEmbeddingStore.builder() } returns embeddingStoreBuilder
        every { embeddingStoreBuilder.host(any()) } returns embeddingStoreBuilder
        every { embeddingStoreBuilder.port(any()) } returns embeddingStoreBuilder
        every { embeddingStoreBuilder.useTls(any()) } returns embeddingStoreBuilder
        every { embeddingStoreBuilder.collectionName(any()) } returns embeddingStoreBuilder
        every { embeddingStoreBuilder.build() } returns embeddingStore
        
        // Mock embedding store operations
        every { embeddingStore.add(ofType<Embedding>(), ofType()) } returns UUID.randomUUID().toString()

        every { embeddingStore.search(any()) } returns
            EmbeddingSearchResult(
                listOf(EmbeddingMatch(0.95, UUID.randomUUID().toString(), Embedding.from(FloatArray(10)), TextSegment.from("test content", Metadata.from(mapOf("file_id" to "test-file-id"))))),
            )

        every { embeddingStore.findRelevant(ofType<Embedding>(), ofType<Int>(), any()) } returns
            listOf(
                EmbeddingMatch(0.95, UUID.randomUUID().toString(), Embedding.from(FloatArray(10)), TextSegment.from("test content", Metadata.from(mapOf("file_id" to "test-file-id")))),
            )

        every { embeddingStore.addAll(any(), any()) } returns listOf(UUID.randomUUID().toString())

        // Create configuration
        qdrantProperties =
            QdrantVectorProperties(
                host = "localhost",
                port = 6334,
                useTls = false,
            )
        
        vectorSearchProperties =
            VectorSearchConfigProperties(
                provider = "qdrant",
                chunkSize = 100,
                chunkOverlap = 20,
                collectionName = "test-collection",
                vectorDimension = 3,
            )
        
        // Create the provider
        vectorSearchProvider =
            QdrantVectorSearchProvider(
                embeddingService,
                qdrantProperties,
                vectorSearchProperties,
                hybridSearchServiceHelper,
                qdrantClient,
            )
        
        // Replace the embedding store with our mock
        val field = QdrantVectorSearchProvider::class.java.getDeclaredField("embeddingStore")
        field.isAccessible = true
        field.set(vectorSearchProvider, embeddingStore)
    }

    @Test
    fun `indexFile should process and store vector embeddings`() {
        // Given
        val fileId = "test-file-id"
        val content = "This is a test document for vector indexing."
        val inputStream = ByteArrayInputStream(content.toByteArray())

        every { embeddingStore.removeAll(ofType<IsEqualTo>()) } just runs

        // When
        val result = vectorSearchProvider.indexFile(fileId, inputStream, "test.txt", null, "test")
        
        // Then
        assertTrue(result, "File should be successfully indexed")
        verify { embeddingService.embedTexts(any()) }
        verify { embeddingStore.addAll(any(), any()) }
    }

    @Test
    fun `searchSimilar should find relevant documents`() {
        // Given
        val query = "test query"
        
        // When
        val results = vectorSearchProvider.searchSimilar(query, rankingOptions = null)
        
        // Then
        assertEquals(1, results.size, "Should return expected number of results")
        assertEquals("test-file-id", results[0].fileId, "Should return result with correct file ID")
        assertEquals(0.95, results[0].score, "Should return result with correct score")
    }

    @Test
    fun `searchSimilar should apply filters`() {
        // Given
        val query = "test query"
        val fileId = "test-file-id"
        val filter = ComparisonFilter(key = "file_id", type = "eq", value = fileId)
        
        // When
        val results =
            vectorSearchProvider.searchSimilar(
                query = query,
                rankingOptions = null,
                filter = filter,
            )
        
        // Then
        assertEquals(1, results.size, "Should return filtered results")
        assertEquals("test-file-id", results[0].fileId, "Should return result with correct file ID")
    }

    @Test
    fun `deleteFile should remove documents from the vector store`() {
        // Given
        val fileId = "test-file-id"
        
        // Setup mock for querying documents to delete
        every { embeddingStore.findRelevant(ofType<Embedding>(), ofType<Int>(), any()) } returns
            listOf(
                EmbeddingMatch(0.95, UUID.randomUUID().toString(), Embedding.from(FloatArray(10)), TextSegment.from("test content", Metadata.from(mapOf("file_id" to "test-file-id")))),
            )
        every { embeddingStore.removeAll(ofType<IsEqualTo>()) } just runs
        
        // When
        val result = vectorSearchProvider.deleteFile(fileId)
        
        // Then
        assertTrue(result, "File should be successfully deleted")
        verify { embeddingStore.removeAll(ofType<IsEqualTo>()) }
    }

    @Test
    fun `indexFile should handle error from embedding service`() {
        // Given
        val fileId = "test-file-id"
        val content = "This is a test document for vector indexing."
        val inputStream = ByteArrayInputStream(content.toByteArray())
        
        // Mock embedding service to throw exception
        every { embeddingService.embedTexts(any()) } throws RuntimeException("Embedding service error")
        every { embeddingStore.removeAll(ofType<IsEqualTo>()) } just runs
        // When
        val result = vectorSearchProvider.indexFile(fileId, inputStream, "test.txt", null, "test")
        
        // Then
        assertFalse(result, "Indexing should fail when embedding service throws an error")
        verify { embeddingService.embedTexts(any()) }
    }

    @Test
    fun `indexFile should handle empty text content`() {
        // Given
        val fileId = "test-file-id"
        val content = ""
        val inputStream = ByteArrayInputStream(content.toByteArray())
        
        // When
        val result = vectorSearchProvider.indexFile(fileId, inputStream, "empty.txt", null, "test")
        
        // Then
        assertFalse(result, "Indexing should fail for empty content")
    }

    @Test
    fun `indexFile should use custom chunking strategy when provided`() {
        // Given
        val fileId = "test-file-id"
        val content = "This is a test document for vector indexing with custom chunking strategy."
        val inputStream = ByteArrayInputStream(content.toByteArray())
        val filename = "test.txt"
        
        // Create custom chunking strategy
        val staticChunkingStrategy = mockk<StaticChunkingConfig>()
        every { staticChunkingStrategy.maxChunkSizeTokens } returns 50
        every { staticChunkingStrategy.chunkOverlapTokens } returns 10
        
        val chunkingStrategy = mockk<ChunkingStrategy>()
        every { chunkingStrategy.type } returns "static"
        every { chunkingStrategy.static } returns staticChunkingStrategy
        every { embeddingStore.removeAll(ofType<IsEqualTo>()) } just runs

        // When
        val result = vectorSearchProvider.indexFile(fileId, inputStream, filename, chunkingStrategy, "test")
        
        // Then
        assertTrue(result, "File should be successfully indexed with custom chunking strategy")
        verify { embeddingService.embedTexts(any()) }
        verify { embeddingStore.addAll(any(), any()) }
    }

    @Test
    fun `getFileMetadata should return metadata for existing file`() {
        // Given
        val fileId = "test-file-id"
        val metadata = mapOf("file_id" to fileId, "filename" to "test.txt")
        
        // Mock embedding store search result
        val segment = TextSegment.from("content", Metadata.from(metadata))
        val match = EmbeddingMatch(0.95, UUID.randomUUID().toString(), Embedding.from(FloatArray(10)), segment)
        
        // Setup mock for finding documents by file ID
        every { embeddingStore.search(any()) } returns EmbeddingSearchResult(listOf(match))
        
        // When
        val result = vectorSearchProvider.getFileMetadata(fileId)
        
        // Then
        assertNotNull(result, "Should return metadata for existing file")
        assertEquals(fileId, result["file_id"], "Metadata should contain correct file ID")
        assertEquals("test.txt", result["filename"], "Metadata should contain filename")
    }

    @Test
    fun `getFileMetadata should return null for non-existent file`() {
        // Given
        val fileId = "non-existent-file"
        
        // Mock empty search results
        every { embeddingStore.search(any()) } returns EmbeddingSearchResult(emptyList())
        
        // When
        val result = vectorSearchProvider.getFileMetadata(fileId)
        
        // Then
        assertNull(result, "Should return null for non-existent file")
    }

    @Test
    fun `searchSimilar should handle empty query`() {
        // Given
        val query = ""
        
        // When
        val results = vectorSearchProvider.searchSimilar(query, rankingOptions = null)
        
        // Then
        assertTrue(results.isEmpty(), "Should return empty results for empty query")
    }

    @Test
    fun `searchSimilar should throw errors`() {
        // Given
        val query = "test query"
        
        // Mock embedding store to throw exception
        every { embeddingService.embedText(any()) } throws RuntimeException("Search error")
        
        // When
        val exception =
            assertThrows<RuntimeException> {
                vectorSearchProvider.searchSimilar(query, rankingOptions = null)
            }

        // Then
        assertEquals("Search error", exception.message, "Should throw exception when search fails")
    }

    @Test
    fun `deleteFile should handle errors gracefully`() {
        // Given
        val fileId = "test-file-id"
        
        // Mock embedding store to throw exception
        every { embeddingStore.removeAll(ofType<IsEqualTo>()) } throws RuntimeException("Delete error")
        
        // When
        val result = vectorSearchProvider.deleteFile(fileId)
        
        // Then
        assertFalse(result, "Should return false when delete operation fails")
    }
}
