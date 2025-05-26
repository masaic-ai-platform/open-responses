package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.InputMessageItem
import ai.masaic.openresponses.tool.ToolService
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseInputItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InMemoryResponseStoreTest {
    private lateinit var responseStore: InMemoryResponseStore
    private lateinit var objectMapper: ObjectMapper
    private lateinit var toolService: ToolService

    @BeforeEach
    fun setup() {
        objectMapper = mockk()
        toolService = mockk(relaxed = true)
        every { objectMapper.convertValue(ofType<ResponseInputItem>(), InputMessageItem::class.java) } returns InputMessageItem()
        responseStore = InMemoryResponseStore(objectMapper, 500)
    }

    @Test
    fun `test storeResponse and getResponse`() =
        runTest {
            // Setup
            val responseId = "resp_123456"
            val mockResponse = mockk<Response>()
            every { mockResponse.id() } returns responseId
            every { mockResponse.output() } returns listOf()

            val inputItems = listOf(mockk<ResponseInputItem>())

            // Act
            responseStore.storeResponse(mockResponse, inputItems, mockk(relaxed = true))
            val retrievedResponse = responseStore.getResponse(responseId)

            // Assert
            assertNotNull(retrievedResponse)
            assertEquals(mockResponse, retrievedResponse)
        }

    @Test
    fun `test getResponse returns null for nonexistent response`() =
        runTest {
            // Act
            val retrievedResponse = responseStore.getResponse("nonexistent_resp")

            // Assert
            assertNull(retrievedResponse)
        }

    @Test
    fun `test getInputItems returns correct items`() =
        runTest {
            // Setup
            val responseId = "resp_123456"
            val mockResponse = mockk<Response>()
            every { mockResponse.id() } returns responseId
            every { mockResponse.output() } returns listOf()

            val inputItems =
                listOf(
                    mockk<ResponseInputItem>(),
                    mockk<ResponseInputItem>(),
                )

            // Act
            responseStore.storeResponse(mockResponse, inputItems, mockk(relaxed = true))
            val retrievedItems = responseStore.getInputItems(responseId)

            // Assert
            assertEquals(2, retrievedItems.size)
        }

    @Test
    fun `test getInputItems returns empty list for nonexistent response`() =
        runTest {
            // Act
            val retrievedItems = responseStore.getInputItems("nonexistent_resp")

            // Assert
            assertTrue(retrievedItems.isEmpty())
        }

    @Test
    fun `test deleteResponse removes response and returns true`() =
        runTest {
            // Setup
            val responseId = "resp_123456"
            val mockResponse = mockk<Response>()
            every { mockResponse.id() } returns responseId
            every { mockResponse.output() } returns listOf()

            val inputItems = listOf(mockk<ResponseInputItem>())

            responseStore.storeResponse(mockResponse, inputItems, mockk(relaxed = true))

            // Act
            val deleted = responseStore.deleteResponse(responseId)

            // Assert
            assertTrue(deleted)
            assertNull(responseStore.getResponse(responseId))
            assertTrue(responseStore.getInputItems(responseId).isEmpty())
        }

    @Test
    fun `test deleteResponse returns false for nonexistent response`() =
        runTest {
            // Act
            val deleted = responseStore.deleteResponse("nonexistent_resp")

            // Assert
            assertFalse(deleted)
        }
}
