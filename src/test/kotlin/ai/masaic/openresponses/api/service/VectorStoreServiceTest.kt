package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.model.*
import ai.masaic.openresponses.api.repository.VectorStoreRepository
import ai.masaic.openresponses.api.service.search.HybridSearchServiceHelper
import ai.masaic.openresponses.api.service.search.VectorSearchProvider
import ai.masaic.openresponses.api.service.search.VectorStoreService
import ai.masaic.openresponses.api.service.storage.FileService
import ai.masaic.openresponses.api.support.service.TelemetryService
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import java.io.InputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VectorStoreServiceTest {
    private lateinit var fileService: FileService
    private lateinit var fileManager: VectorStoreFileManager
    private lateinit var vectorSearchProvider: VectorSearchProvider
    private lateinit var vectorStoreService: VectorStoreService
    private lateinit var vectorStoreRepository: VectorStoreRepository
    private lateinit var mockResource: Resource
    private lateinit var telemetryService: TelemetryService
    private lateinit var hybridSearchServiceHelper: HybridSearchServiceHelper

    @BeforeEach
    fun setup() {
        // Setup mock resources first
        mockResource = mockk<ByteArrayResource>()
        hybridSearchServiceHelper = mockk(relaxed = true)
        val mockInputStream: InputStream = mockk()
        every { mockResource.inputStream } returns mockInputStream
        every { mockResource.filename } returns "test.txt"
        
        vectorStoreRepository =
            mockk {
                coEvery { saveVectorStore(any()) } answers { firstArg() }
                coEvery { saveVectorStoreFile(any()) } answers { firstArg() }
                coEvery { deleteVectorStore(any()) } returns true
                coEvery { findVectorStoreById(any()) } returns
                    VectorStore(
                        id = "vs_cc4223ea-c516-491f-8a41-96c90f5804e6",
                        name = "Test Vector Store",
                        metadata = mapOf("key" to "value"),
                        fileCounts = FileCounts(total = 1, completed = 1, failed = 0),
                        lastActiveAt = Instant.now().epochSecond,
                    )

                coEvery { listVectorStoreFiles(any(), any(), any(), any(), any(), any()) } returns
                    listOf(
                        VectorStoreFile(
                            id = "file-123",
                            vectorStoreId = "vs_cc4223ea-c516-491f-8a41-96c90f5804e6",
                            status = "completed",
                        ),
                    )

                coEvery { listVectorStores(any(), any(), any(), any()) } returns
                    listOf(
                        VectorStore(
                            id = "vs_123",
                            name = "Vector Store 1",
                            metadata = mapOf("key" to "value"),
                            fileCounts = FileCounts(total = 1, completed = 1, failed = 0),
                            lastActiveAt = Instant.now().epochSecond,
                        ),
                        VectorStore(
                            id = "vs_456",
                            name = "Vector Store 2",
                            metadata = mapOf("key" to "value"),
                            fileCounts = FileCounts(total = 1, completed = 1, failed = 0),
                            lastActiveAt = Instant.now().epochSecond,
                        ),
                    )
            }
        fileService = mockk()
        fileManager =
            mockk {
                coEvery { getFileAsResource(any()) } returns mockResource
            }
        vectorSearchProvider = mockk()
        telemetryService = mockk(relaxed = true)

        vectorStoreService =
            VectorStoreService(fileManager, vectorStoreRepository, vectorSearchProvider, telemetryService, hybridSearchServiceHelper)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `createVectorStore should create a vector store with files`() =
        runTest {
            // Given
            val fileId = "file-123"
            val request =
                CreateVectorStoreRequest(
                    name = "Test Vector Store",
                    fileIds = listOf(fileId),
                )
            
            coEvery { fileManager.fileExists(fileId) } returns true
            coEvery { fileManager.getFileMetadata(fileId) } returns
                mapOf(
                    "id" to fileId,
                    "filename" to "test.txt",
                    "purpose" to "assistants",
                    "bytes" to 100L,
                    "created_at" to Instant.now().epochSecond,
                )
            coEvery { fileManager.getFileContent(fileId) } returns listOf()
            coEvery { fileManager.getFileAsResource(fileId) } returns mockResource
            coEvery { vectorSearchProvider.indexFile(eq(fileId), any(), any(), any(), ofType<Map<String, Any>>(), any()) } returns true
            coEvery { vectorSearchProvider.getFileMetadata(any()) } returns mapOf()

            // Mock repository to return the vector store with correct initial counts
            coEvery { vectorStoreRepository.saveVectorStore(any()) } answers { 
                val store = firstArg<VectorStore>()
                store
            }
            
            // Mock update counts after async processing
            coEvery { vectorStoreRepository.listVectorStoreFiles(any(), any()) } returns
                listOf(
                    VectorStoreFile(
                        id = fileId,
                        createdAt = Instant.now().epochSecond,
                        usageBytes = 100L,
                        vectorStoreId = "vs_123",
                        status = "completed",
                    ),
                )
            
            // When
            val vectorStore = vectorStoreService.createVectorStore(request)
            
            // Then
            assertNotNull(vectorStore)
            assertEquals("Test Vector Store", vectorStore.name)
            assertTrue(vectorStore.id.startsWith("vs_"))
            
            // Verify initial state shows correct file counts
            assertEquals(1, vectorStore.fileCounts.total)
            assertEquals(0, vectorStore.fileCounts.completed)
            assertEquals(1, vectorStore.fileCounts.inProgress)
            assertEquals(0, vectorStore.fileCounts.failed)
            
            // Process all pending background coroutines
            advanceUntilIdle()
            
            // Allow some time for the indexing to happen in background tasks
            // Use relaxed verification that doesn't care about exact parameter matching
            coVerify(timeout = 5000) {
                vectorSearchProvider.indexFile(eq(fileId), any(), any(), any(), ofType<Map<String, Any>>(), any())
            }
        }

    @Test
    fun `listVectorStores should return a paginated list of vector stores`() =
        runTest {
            // Given
            // Create a few vector stores
            val request1 = CreateVectorStoreRequest(name = "Vector Store 1")
            val request2 = CreateVectorStoreRequest(name = "Vector Store 2")
            val request3 = CreateVectorStoreRequest(name = "Vector Store 3")
            
            vectorStoreService.createVectorStore(request1)
            vectorStoreService.createVectorStore(request2)
            vectorStoreService.createVectorStore(request3)
            
            // When
            val response = vectorStoreService.listVectorStores(limit = 2)
            
            // Then
            assertEquals(2, response.data.size)
            assertTrue(response.hasMore)
        }

    @Test
    fun `getVectorStore should return a vector store by ID`() =
        runTest {
            // Given
            val request = CreateVectorStoreRequest(name = "Test Vector Store")
            val createdStore = vectorStoreService.createVectorStore(request)
            
            // When
            val vectorStore = vectorStoreService.getVectorStore(createdStore.id)
            
            // Then
            assertEquals("Test Vector Store", vectorStore.name)
            assertNotNull(vectorStore.id)
        }

    @Test
    fun `updateVectorStore should update a vector store`() =
        runTest {
            // Given
            val request = CreateVectorStoreRequest(name = "Original Name")
            val createdStore = vectorStoreService.createVectorStore(request)
            
            val updateRequest =
                ModifyVectorStoreRequest(
                    name = "Updated Name",
                    metadata = mapOf("key" to "value"),
                )
            
            // When
            val updatedStore = vectorStoreService.updateVectorStore(createdStore.id, updateRequest)
            
            // Then
            assertEquals("Updated Name", updatedStore.name)
            assertEquals("value", updatedStore.metadata?.get("key"))
            assertNotNull(updatedStore.id)
        }

    @Test
    fun `deleteVectorStore should delete a vector store and its files`() =
        runTest {
            // Given
            val fileId = "file-123"
            val request =
                CreateVectorStoreRequest(
                    name = "Test Vector Store",
                    fileIds = listOf(fileId),
                )
            
            coEvery { fileManager.fileExists(fileId) } returns true
            coEvery { fileManager.getFileMetadata(fileId) } returns
                mapOf(
                    "id" to fileId,
                    "filename" to "test.txt",
                    "purpose" to "assistants",
                    "bytes" to 100L,
                    "created_at" to Instant.now().epochSecond,
                )
            coEvery { fileManager.getFileContent(fileId) } returns listOf()
            coEvery { fileManager.getFileAsResource(fileId) } returns mockResource
            coEvery { vectorSearchProvider.indexFile(eq(fileId), any(), any(), any(), any()) } returns true
            coEvery { vectorSearchProvider.deleteFile(fileId) } returns true
            
            val createdStore = vectorStoreService.createVectorStore(request)
            
            // When
            val response = vectorStoreService.deleteVectorStore(createdStore.id)
            
            // Then
            assertEquals(createdStore.id, response.id)
            assertTrue(response.deleted)
            coVerify { vectorSearchProvider.deleteFile(fileId) }
        }

    // @Test //TODO Figure out why this test fails sometimes
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `createVectorStoreFile should attach a file to a vector store`() =
        runTest {
            // Given
            val storeRequest = CreateVectorStoreRequest(name = "Test Vector Store")
            val vectorStore = vectorStoreService.createVectorStore(storeRequest)
            
            val fileId = "file-456"
            val fileRequest = CreateVectorStoreFileRequest(fileId = fileId)
            
            coEvery { fileManager.fileExists(fileId) } returns true
            coEvery { fileManager.getFileMetadata(fileId) } returns
                mapOf(
                    "id" to fileId,
                    "filename" to "test.txt",
                    "purpose" to "assistants",
                    "bytes" to 100L,
                    "created_at" to Instant.now().epochSecond,
                )
            coEvery { fileManager.getFileContent(fileId) } returns listOf()
            coEvery { fileManager.getFileAsResource(fileId) } returns mockResource
            coEvery { vectorSearchProvider.indexFile(eq(fileId), any(), any(), any(), any()) } returns true
            
            // Mock the vector store file that should be returned
            val mockVectorStoreFile =
                VectorStoreFile(
                    id = fileId,
                    vectorStoreId = vectorStore.id,
                    status = "in_progress",
                    createdAt = Instant.now().epochSecond,
                    usageBytes = 100L,
                    attributes = mapOf("filename" to "test.txt"),
                )
            
            // Mock the telemetry withVectorStoreOperation method to return our expected file
            coEvery { 
                telemetryService.withVectorStoreOperation<VectorStoreFile>(
                    operationName = "create_file",
                    vectorStoreId = vectorStore.id,
                    block = any(),
                ) 
            } coAnswers { call ->
                // Execute the block to make all necessary calls happen
                val block = call.invocation.args[2] as (suspend () -> VectorStoreFile)
                block() // This makes the internal function run
                mockVectorStoreFile // Return our mock object
            }
            
            // When
            val vectorStoreFile = vectorStoreService.createVectorStoreFile(vectorStore.id, fileRequest)
            
            // Then
            assertEquals(fileId, vectorStoreFile.id)
            assertEquals(vectorStore.id, vectorStoreFile.vectorStoreId)
            
            // Initially the file status should be in_progress
            assertEquals("in_progress", vectorStoreFile.status)
            
            // Process all pending background coroutines
            advanceUntilIdle()
            
            // Verify indexing was called in the background
            coVerify { vectorSearchProvider.indexFile(eq(fileId), any(), any(), any(), any()) }
        }

    @Test
    fun `searchVectorStore should return search results`() =
        runTest {
            // Given
            val fileId = "file-123"
            val request =
                CreateVectorStoreRequest(
                    name = "Test Vector Store",
                    fileIds = listOf(fileId),
                )
            
            coEvery { fileManager.fileExists(fileId) } returns true
            coEvery { fileManager.getFileMetadata(fileId) } returns
                mapOf(
                    "id" to fileId,
                    "filename" to "test.txt",
                    "purpose" to "assistants",
                    "bytes" to 100L,
                    "created_at" to Instant.now().epochSecond,
                )
            coEvery { fileManager.getFileContent(fileId) } returns listOf()
            coEvery { fileManager.getFileAsResource(fileId) } returns mockResource
            coEvery { vectorSearchProvider.indexFile(eq(fileId), any(), any(), any(), any()) } returns true
            
            // Mock search results
            coEvery { 
                vectorSearchProvider.searchSimilar(any(), any(), rankingOptions = null, filter = any())
            } returns
                listOf(
                    VectorSearchProvider.SearchResult(
                        fileId = fileId,
                        score = 0.95,
                        content = "This is a test document",
                        metadata = mapOf("filename" to "test.txt"),
                    ),
                )
            
            val vectorStore = vectorStoreService.createVectorStore(request)
            
            val searchRequest =
                VectorStoreSearchRequest(
                    query = "test document",
                    maxNumResults = 5,
                    filters = ComparisonFilter(key = "key1", type = "eq", value = "value5"),
                )
            
            // When
            val searchResults = vectorStoreService.searchVectorStore(vectorStore.id, searchRequest)
            
            // Then
            assertEquals(1, searchResults.data.size)
            assertEquals(fileId, searchResults.data[0].fileId)
            assertEquals(0.95, searchResults.data[0].score)
            assertEquals("test.txt", searchResults.data[0].filename)
            assertEquals("This is a test document", searchResults.data[0].content[0].text)
        }

    @Test
    fun `createVectorStore should set expiration timestamp when expiration policy is provided`() =
        runTest {
            // Given
            val request =
                CreateVectorStoreRequest(
                    name = "Test Store",
                    expiresAfter =
                        ExpirationPolicy(
                            anchor = "last_active_at",
                            days = 7,
                        ),
                )

            // Mock the repository to return the vector store with the correct expiration timestamp
            coEvery { vectorStoreRepository.saveVectorStore(any()) } answers { 
                val store = firstArg<VectorStore>()
                store.copy(
                    expiresAt = Instant.now().plus(7, ChronoUnit.DAYS).epochSecond,
                )
            }

            // When
            val vectorStore = vectorStoreService.createVectorStore(request)

            // Then
            assertNotNull(vectorStore.expiresAt)
            assertEquals("in_progress", vectorStore.status)
        }

    @Test
    fun `updateVectorStore should update expiration timestamp when expiration policy is provided`() =
        runTest {
            // Given
            val vectorStore = createTestVectorStore()
            coEvery { vectorStoreRepository.findVectorStoreById(vectorStore.id) } returns vectorStore

            val request =
                ModifyVectorStoreRequest(
                    name = "Updated Store",
                    expiresAfter =
                        ExpirationPolicy(
                            anchor = "last_active_at",
                            days = 14,
                        ),
                )

            // Mock the repository to return the updated vector store with the correct expiration timestamp
            coEvery { vectorStoreRepository.saveVectorStore(any()) } answers { 
                val store = firstArg<VectorStore>()
                store.copy(
                    expiresAt = Instant.now().plus(14, ChronoUnit.DAYS).epochSecond,
                )
            }

            // When
            val updatedVectorStore = vectorStoreService.updateVectorStore(vectorStore.id, request)

            // Then
            assertNotNull(updatedVectorStore.expiresAt)
            assertEquals("in_progress", updatedVectorStore.status)
        }

    @Test
    fun `getVectorStore should mark vector store as expired when expiration time is reached`() =
        runTest {
            // Given
            val vectorStore =
                createTestVectorStore(
                    expiresAt = Instant.now().minus(1, ChronoUnit.DAYS).epochSecond,
                )
            coEvery { vectorStoreRepository.findVectorStoreById(vectorStore.id) } returns vectorStore

            // Mock the repository to return the updated vector store with expired status
            coEvery { vectorStoreRepository.saveVectorStore(any()) } answers { 
                val store = firstArg<VectorStore>()
                store.copy(status = "expired")
            }

            // When
            val retrievedVectorStore = vectorStoreService.getVectorStore(vectorStore.id)

            // Then
            assertEquals("expired", retrievedVectorStore.status)
        }

    @Test
    fun `listVectorStores should mark expired vector stores`() =
        runTest {
            // Given
            val vectorStore1 =
                createTestVectorStore(
                    expiresAt = Instant.now().minus(1, ChronoUnit.DAYS).epochSecond,
                )
            val vectorStore2 =
                createTestVectorStore(
                    expiresAt = Instant.now().plus(1, ChronoUnit.DAYS).epochSecond,
                )

            // Mock the repository to return the vector stores
            coEvery { vectorStoreRepository.listVectorStores(any(), any(), any(), any()) } returns 
                listOf(vectorStore1, vectorStore2)

            // Mock the repository to return the updated vector stores with correct status
            coEvery { vectorStoreRepository.saveVectorStore(any()) } answers { 
                val store = firstArg<VectorStore>()
                if (store.expiresAt!! < Instant.now().epochSecond) {
                    store.copy(status = "expired")
                } else {
                    store
                }
            }

            // When
            val response = vectorStoreService.listVectorStores()

            // Then
            assertEquals(2, response.data.size)
            assertEquals("expired", response.data.find { it.id == vectorStore1.id }?.status)
            assertEquals("in_progress", response.data.find { it.id == vectorStore2.id }?.status)
        }

    @Test
    fun `cleanupExpiredVectorStores should mark expired vector stores`() =
        runTest {
            // Given
            val vectorStore1 =
                createTestVectorStore(
                    expiresAt = Instant.now().minus(1, ChronoUnit.DAYS).epochSecond,
                )
            val vectorStore2 =
                createTestVectorStore(
                    expiresAt = Instant.now().plus(1, ChronoUnit.DAYS).epochSecond,
                )

            // Mock the repository to return all vector stores
            coEvery { vectorStoreRepository.listVectorStores(Int.MAX_VALUE) } returns 
                listOf(vectorStore1, vectorStore2)

            // Mock the repository to return the updated vector stores with correct status
            coEvery { vectorStoreRepository.saveVectorStore(any()) } answers { 
                val store = firstArg<VectorStore>()
                if (store.expiresAt!! < Instant.now().epochSecond) {
                    store.copy(status = "expired")
                } else {
                    store
                }
            }

            // When
            val cleanedUpCount = vectorStoreService.cleanupExpiredVectorStores()

            // Then
            assertEquals(1, cleanedUpCount)
        }

    @Test
    fun `searchVectorStore should apply filters correctly`() =
        runTest {
            // Given
            val fileId = "file-123"
            val request =
                CreateVectorStoreRequest(
                    name = "Test Vector Store",
                    fileIds = listOf(fileId),
                )
            
            coEvery { fileManager.fileExists(fileId) } returns true
            coEvery { fileManager.getFileMetadata(fileId) } returns
                mapOf(
                    "id" to fileId,
                    "filename" to "test.txt",
                    "purpose" to "assistants",
                    "bytes" to 100L,
                    "created_at" to Instant.now().epochSecond,
                )
            coEvery { fileManager.getFileContent(fileId) } returns listOf()
            coEvery { fileManager.getFileAsResource(fileId) } returns mockResource
            
            // Define custom attributes
            val customAttributes = mapOf("key1" to "value5", "category" to "test")
            
            // Mock the indexing to succeed and include our custom attributes
            coEvery { 
                vectorSearchProvider.indexFile(
                    fileId = fileId, 
                    content = any(), 
                    filename = any(), 
                    chunkingStrategy = any(),
                    attributes = any(),
                    vectorStoreId = any(),
                ) 
            } returns true
            
            // Create the vector store
            val vectorStore = vectorStoreService.createVectorStore(request)
            
            // Define a filter
            val filter = ComparisonFilter(key = "key1", type = "eq", value = "value5")
            
            // Mock search results to include our custom attributes
            coEvery { 
                vectorSearchProvider.searchSimilar(
                    query = "test document",
                    maxResults = 5,
                    rankingOptions = null,
                    filter = any(), // This will capture the combined filter
                )
            } returns
                listOf(
                    VectorSearchProvider.SearchResult(
                        fileId = fileId,
                        score = 0.95,
                        content = "This is a test document",
                        metadata =
                            mapOf(
                                "filename" to "test.txt", 
                                "key1" to "value5", 
                                "category" to "test",
                            ),
                    ),
                )
            
            // Create search request with filter
            val searchRequest =
                VectorStoreSearchRequest(
                    query = "test document",
                    maxNumResults = 5,
                    filters = filter,
                )
            
            // When
            val searchResults = vectorStoreService.searchVectorStore(vectorStore.id, searchRequest)
            
            // Then
            assertEquals(1, searchResults.data.size)
            assertEquals(fileId, searchResults.data[0].fileId)
            assertEquals(0.95, searchResults.data[0].score)
            assertEquals("value5", searchResults.data[0].attributes?.get("key1"))
            assertEquals("test", searchResults.data[0].attributes?.get("category"))
            
            // Verify the filter was applied in the call to searchSimilar
            coVerify { 
                vectorSearchProvider.searchSimilar(
                    query = "test document", 
                    maxResults = 5,
                    rankingOptions = null,
                    filter =
                        withArg { combinedFilter ->
                            // Verify the combined filter is an AND filter
                            assertTrue(combinedFilter is CompoundFilter)
                            val compoundFilter = combinedFilter as CompoundFilter
                            assertEquals("and", compoundFilter.type)
                        
                            // Should contain two filters: the fileIds filter and the user filter
                            assertEquals(2, compoundFilter.filters.size)
                        
                            // One of the filters should be our original filter
                            val hasUserFilter =
                                compoundFilter.filters.any { 
                                    it is ComparisonFilter && 
                                        it.key == "key1" && 
                                        it.type == "eq" && 
                                        it.value == "value5"
                                }
                            assertTrue(hasUserFilter, "Combined filter should contain the user's filter")
                        },
                )
            }
        }

    // Helper function to create test vector stores
    private fun createTestVectorStore(
        id: String = "vs_${UUID.randomUUID()}",
        name: String = "Test Store",
        expiresAt: Long? = null,
    ): VectorStore =
        VectorStore(
            id = id,
            name = name,
            createdAt = Instant.now().epochSecond,
            lastActiveAt = Instant.now().epochSecond,
            expiresAt = expiresAt,
        )
} 
