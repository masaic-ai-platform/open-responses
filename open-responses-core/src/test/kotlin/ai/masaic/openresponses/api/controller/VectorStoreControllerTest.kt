package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.exception.VectorStoreNotFoundException
import ai.masaic.openresponses.api.model.*
import ai.masaic.openresponses.api.service.search.VectorStoreService
import ai.masaic.openresponses.api.service.storage.FileNotFoundException
import com.ninjasquad.springmockk.MockkBean
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant

@WebFluxTest(VectorStoreController::class)
class VectorStoreControllerTest {
    @MockkBean
    lateinit var vectorStoreService: VectorStoreService

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Test
    fun `createVectorStore should return a vector store`() =
        runTest {
            // Given
            val request =
                CreateVectorStoreRequest(
                    name = "Test Vector Store",
                    fileIds = listOf("file-123"),
                )
            
            val vectorStore =
                VectorStore(
                    id = "vs_abc123",
                    name = "Test Vector Store",
                    createdAt = Instant.now().epochSecond,
                    bytes = 1000,
                    fileCounts =
                        FileCounts(
                            inProgress = 0,
                            completed = 1,
                            failed = 0,
                            total = 1,
                        ),
                )
            
            coEvery { vectorStoreService.createVectorStore(request) } returns vectorStore
            
            // When/Then
            webTestClient
                .post()
                .uri("/v1/vector_stores")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo("vs_abc123")
                .jsonPath("$.name")
                .isEqualTo("Test Vector Store")
            
            coVerify { vectorStoreService.createVectorStore(request) }
        }

    @Test
    fun `createVectorStore should return 400 when vector search provider is not configured`() =
        runTest {
            // Given
            val request =
                CreateVectorStoreRequest(
                    name = "Test Vector Store",
                    fileIds = listOf("file-123"),
                )
            
            coEvery { 
                vectorStoreService.createVectorStore(request) 
            } throws IllegalStateException("No vector search provider is configured")
            
            // When/Then
            webTestClient
                .post()
                .uri("/v1/vector_stores")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isBadRequest
            
            coVerify { vectorStoreService.createVectorStore(request) }
        }

    @Test
    fun `createVectorStore should return 404 when file is not found`() =
        runTest {
            // Given
            val request =
                CreateVectorStoreRequest(
                    name = "Test Vector Store",
                    fileIds = listOf("file-123"),
                )
            
            coEvery { 
                vectorStoreService.createVectorStore(request) 
            } throws FileNotFoundException("File not found: file-123")
            
            // When/Then
            webTestClient
                .post()
                .uri("/v1/vector_stores")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isNotFound
            
            coVerify { vectorStoreService.createVectorStore(request) }
        }

    @Test
    fun `listVectorStores should return a list of vector stores`() =
        runTest {
            // Given
            val vectorStore1 =
                VectorStore(
                    id = "vs_abc123",
                    name = "Vector Store 1",
                    createdAt = Instant.now().epochSecond,
                )
            
            val vectorStore2 =
                VectorStore(
                    id = "vs_def456",
                    name = "Vector Store 2",
                    createdAt = Instant.now().epochSecond,
                )
            
            val response =
                VectorStoreListResponse(
                    data = listOf(vectorStore1, vectorStore2),
                    firstId = "vs_abc123",
                    lastId = "vs_def456",
                    hasMore = false,
                )
            
            coEvery { 
                vectorStoreService.listVectorStores(20, "desc", null, null) 
            } returns response
            
            // When/Then
            webTestClient
                .get()
                .uri("/v1/vector_stores")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.data.length()")
                .isEqualTo(2)
                .jsonPath("$.data[0].id")
                .isEqualTo("vs_abc123")
                .jsonPath("$.data[1].id")
                .isEqualTo("vs_def456")
            
            coVerify { vectorStoreService.listVectorStores(20, "desc", null, null) }
        }

    @Test
    fun `getVectorStore should return a vector store`() =
        runTest {
            // Given
            val vectorStoreId = "vs_abc123"
            val vectorStore =
                VectorStore(
                    id = vectorStoreId,
                    name = "Test Vector Store",
                    createdAt = Instant.now().epochSecond,
                )
            
            coEvery { vectorStoreService.getVectorStore(vectorStoreId) } returns vectorStore
            
            // When/Then
            webTestClient
                .get()
                .uri("/v1/vector_stores/$vectorStoreId")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(vectorStoreId)
                .jsonPath("$.name")
                .isEqualTo("Test Vector Store")
            
            coVerify { vectorStoreService.getVectorStore(vectorStoreId) }
        }

    @Test
    fun `getVectorStore should return 404 when vector store is not found`() =
        runTest {
            // Given
            val vectorStoreId = "vs_nonexistent"
            
            coEvery { 
                vectorStoreService.getVectorStore(vectorStoreId) 
            } throws VectorStoreNotFoundException("Vector store not found: $vectorStoreId")
            
            // When/Then
            webTestClient
                .get()
                .uri("/v1/vector_stores/$vectorStoreId")
                .exchange()
                .expectStatus()
                .isNotFound
            
            coVerify { vectorStoreService.getVectorStore(vectorStoreId) }
        }

    @Test
    fun `updateVectorStore should update a vector store`() =
        runTest {
            // Given
            val vectorStoreId = "vs_abc123"
            val request =
                ModifyVectorStoreRequest(
                    name = "Updated Vector Store",
                    metadata = mapOf("key" to "value"),
                )
            
            val updatedVectorStore =
                VectorStore(
                    id = vectorStoreId,
                    name = "Updated Vector Store",
                    createdAt = Instant.now().epochSecond,
                    metadata = mapOf("key" to "value"),
                )
            
            coEvery { 
                vectorStoreService.updateVectorStore(vectorStoreId, request) 
            } returns updatedVectorStore
            
            // When/Then
            webTestClient
                .post()
                .uri("/v1/vector_stores/$vectorStoreId")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(vectorStoreId)
                .jsonPath("$.name")
                .isEqualTo("Updated Vector Store")
                .jsonPath("$.metadata.key")
                .isEqualTo("value")
            
            coVerify { vectorStoreService.updateVectorStore(vectorStoreId, request) }
        }

    @Test
    fun `deleteVectorStore should delete a vector store`() =
        runTest {
            // Given
            val vectorStoreId = "vs_abc123"
            val response =
                VectorStoreDeleteResponse(
                    id = vectorStoreId,
                    deleted = true,
                )
            
            coEvery { vectorStoreService.deleteVectorStore(vectorStoreId) } returns response
            
            // When/Then
            webTestClient
                .delete()
                .uri("/v1/vector_stores/$vectorStoreId")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(vectorStoreId)
                .jsonPath("$.deleted")
                .isEqualTo(true)
            
            coVerify { vectorStoreService.deleteVectorStore(vectorStoreId) }
        }

    @Test
    fun `searchVectorStore should return search results`() =
        runTest {
            // Given
            val vectorStoreId = "vs_abc123"
            val request =
                VectorStoreSearchRequest(
                    query = "test document",
                    maxNumResults = 5,
                    filters = ComparisonFilter(key = "key1", type = "eq", value = "value5"),
                )
            
            val searchResult =
                VectorStoreSearchResult(
                    fileId = "file-123",
                    filename = "test.txt",
                    score = 0.95,
                    content =
                        listOf(
                            VectorStoreSearchResultContent(
                                type = "text",
                                text = "This is a test document",
                            ),
                        ),
                )
            
            val searchResults =
                VectorStoreSearchResults(
                    searchQuery = "test document",
                    data = listOf(searchResult),
                )
            
            coEvery { 
                vectorStoreService.searchVectorStore(vectorStoreId, request) 
            } returns searchResults
            
            // When/Then
            webTestClient
                .post()
                .uri("/v1/vector_stores/$vectorStoreId/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.search_query")
                .isEqualTo("test document")
                .jsonPath("$.data.length()")
                .isEqualTo(1)
                .jsonPath("$.data[0].file_id")
                .isEqualTo("file-123")
                .jsonPath("$.data[0].score")
                .isEqualTo(0.95)
            
            coVerify { vectorStoreService.searchVectorStore(vectorStoreId, request) }
        }

    @Test
    fun `createVectorStoreFile should attach a file to a vector store`() =
        runTest {
            // Given
            val vectorStoreId = "vs_abc123"
            val request =
                CreateVectorStoreFileRequest(
                    fileId = "file-123",
                )
            
            val vectorStoreFile =
                VectorStoreFile(
                    id = "file-123",
                    vectorStoreId = vectorStoreId,
                    createdAt = Instant.now().epochSecond,
                    status = "in_progress",
                )
            
            coEvery { 
                vectorStoreService.createVectorStoreFile(vectorStoreId, request) 
            } returns vectorStoreFile
            
            // When/Then
            webTestClient
                .post()
                .uri("/v1/vector_stores/$vectorStoreId/files")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo("file-123")
                .jsonPath("$.vector_store_id")
                .isEqualTo(vectorStoreId)
                .jsonPath("$.status")
                .isEqualTo("in_progress")
            
            coVerify { vectorStoreService.createVectorStoreFile(vectorStoreId, request) }
        }
} 
