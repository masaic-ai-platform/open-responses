package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.config.QdrantProperties
import ai.masaic.openresponses.api.config.VectorSearchProperties
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for QdrantVectorSearchProvider.
 */
class QdrantVectorSearchProviderTest {
    private lateinit var embeddingService: EmbeddingService
    private lateinit var qdrantProperties: QdrantProperties
    private lateinit var vectorSearchProperties: VectorSearchProperties
    private lateinit var qdrantClient: QdrantClient
    private lateinit var embeddingStore: QdrantEmbeddingStore
    private lateinit var vectorSearchProvider: QdrantVectorSearchProvider

    @BeforeEach
    fun setup() {
        // Mock embedding service
        embeddingService = mockk()
        every { embeddingService.embedText(any()) } returns listOf(0.1f, 0.2f, 0.3f)
        
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
                listOf(EmbeddingMatch(0.95, UUID.randomUUID().toString(), Embedding.from(FloatArray(10)), TextSegment.from("test content", Metadata.from(mapOf("fileId" to "test-file-id"))))),
            )

        every { embeddingStore.findRelevant(ofType<Embedding>(), ofType<Int>(), any()) } returns
            listOf(
                EmbeddingMatch(0.95, UUID.randomUUID().toString(), Embedding.from(FloatArray(10)), TextSegment.from("test content", Metadata.from(mapOf("fileId" to "test-file-id")))),
            )
        
        // Create configuration
        qdrantProperties =
            QdrantProperties(
                host = "localhost",
                port = 6334,
                useTls = false,
                collectionName = "test-collection",
                vectorDimension = 3,
            )
        
        vectorSearchProperties =
            VectorSearchProperties(
                provider = "qdrant",
                chunkSize = 100,
                chunkOverlap = 20,
            )
        
        // Create the provider
        vectorSearchProvider =
            QdrantVectorSearchProvider(
                embeddingService,
                qdrantProperties,
                vectorSearchProperties,
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
        
        // When
        val result = vectorSearchProvider.indexFile(fileId, inputStream, "test.txt")
        
        // Then
        assertTrue(result, "File should be successfully indexed")
        verify { embeddingService.embedText(any()) }
        verify { embeddingStore.add(ofType<Embedding>(), any()) }
    }

    @Test
    fun `searchSimilar should find relevant documents`() {
        // Given
        val query = "test query"
        
        // When
        val results = vectorSearchProvider.searchSimilar(query, 10, null)
        
        // Then
        assertEquals(1, results.size, "Should return expected number of results")
        assertEquals("test-file-id", results[0].fileId, "Should return result with correct file ID")
        assertEquals(0.95, results[0].score, "Should return result with correct score")
    }

    @Test
    fun `searchSimilar should apply filters`() {
        // Given
        val query = "test query"
        val filters = mapOf("fileId" to "test-file-id")
        
        // When
        val results = vectorSearchProvider.searchSimilar(query, 10, filters)
        
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
                EmbeddingMatch(0.95, UUID.randomUUID().toString(), Embedding.from(FloatArray(10)), TextSegment.from("test content", Metadata.from(mapOf("fileId" to "test-file-id")))),
            )
        every { embeddingStore.removeAll(ofType<IsEqualTo>()) } just runs
        
        // When
        val result = vectorSearchProvider.deleteFile(fileId)
        
        // Then
        assertTrue(result, "File should be successfully deleted")
        verify { embeddingStore.removeAll(ofType<IsEqualTo>()) }
    }
}
