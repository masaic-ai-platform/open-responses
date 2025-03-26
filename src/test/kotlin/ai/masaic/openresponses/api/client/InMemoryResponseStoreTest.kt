package ai.masaic.openresponses.api.client

import com.openai.models.responses.Response
import com.openai.models.responses.ResponseInputItem
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InMemoryResponseStoreTest {
    private lateinit var responseStore: InMemoryResponseStore

    @BeforeEach
    fun setup() {
        responseStore = InMemoryResponseStore()
    }

    @Test
    fun `test storeResponse and getResponse`() {
        // Setup
        val responseId = "resp_123456"
        val mockResponse = mockk<Response>()
        every { mockResponse.id() } returns responseId
        
        val inputItems = listOf(mockk<ResponseInputItem>())
        
        // Act
        responseStore.storeResponse(mockResponse, inputItems)
        val retrievedResponse = responseStore.getResponse(responseId)
        
        // Assert
        assertNotNull(retrievedResponse)
        assertEquals(mockResponse, retrievedResponse)
    }

    @Test
    fun `test getResponse returns null for nonexistent response`() {
        // Act
        val retrievedResponse = responseStore.getResponse("nonexistent_resp")
        
        // Assert
        assertNull(retrievedResponse)
    }

    @Test
    fun `test getInputItems returns correct items`() {
        // Setup
        val responseId = "resp_123456"
        val mockResponse = mockk<Response>()
        every { mockResponse.id() } returns responseId
        
        val inputItems =
            listOf(
                mockk<ResponseInputItem>(),
                mockk<ResponseInputItem>(),
            )
        
        // Act
        responseStore.storeResponse(mockResponse, inputItems)
        val retrievedItems = responseStore.getInputItems(responseId)
        
        // Assert
        assertEquals(2, retrievedItems.size)
        assertEquals(inputItems, retrievedItems)
    }

    @Test
    fun `test getInputItems returns empty list for nonexistent response`() {
        // Act
        val retrievedItems = responseStore.getInputItems("nonexistent_resp")
        
        // Assert
        assertTrue(retrievedItems.isEmpty())
    }

    @Test
    fun `test deleteResponse removes response and returns true`() {
        // Setup
        val responseId = "resp_123456"
        val mockResponse = mockk<Response>()
        every { mockResponse.id() } returns responseId
        
        val inputItems = listOf(mockk<ResponseInputItem>())
        
        responseStore.storeResponse(mockResponse, inputItems)
        
        // Act
        val deleted = responseStore.deleteResponse(responseId)
        
        // Assert
        assertTrue(deleted)
        assertNull(responseStore.getResponse(responseId))
        assertTrue(responseStore.getInputItems(responseId).isEmpty())
    }

    @Test
    fun `test deleteResponse returns false for nonexistent response`() {
        // Act
        val deleted = responseStore.deleteResponse("nonexistent_resp")
        
        // Assert
        assertFalse(deleted)
    }
} 
