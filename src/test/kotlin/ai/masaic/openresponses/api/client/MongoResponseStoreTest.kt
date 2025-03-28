package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.InputMessageItem
import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.client.result.DeleteResult
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseInputItem
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query

class MongoResponseStoreTest {
    private lateinit var responseStore: MongoResponseStore
    private lateinit var objectMapper: ObjectMapper
    private lateinit var mongoTemplate: MongoTemplate

    @BeforeEach
    fun setup() {
        objectMapper = mockk()
        mongoTemplate = mockk(relaxed = true)
        
        // Mock object mapper conversions
        every { objectMapper.convertValue(ofType<ResponseInputItem>(), InputMessageItem::class.java) } returns InputMessageItem()
        every { objectMapper.writeValueAsString(any<Response>()) } returns """{"id":"resp_123456"}"""
        every { objectMapper.readValue(any<String>(), Response::class.java) } returns mockk()
        
        responseStore = MongoResponseStore(mongoTemplate, objectMapper)
    }

    @Test
    fun `test storeResponse stores document in MongoDB`() {
        // Setup
        val responseId = "resp_123456"
        val mockResponse = mockk<Response>()
        every { mockResponse.id() } returns responseId
        every { mockResponse.output() } returns listOf()

        val inputItems = listOf(mockk<ResponseInputItem>())

        every {
            mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java)
        } returns null
        // Mock MongoDB save operation
        every { 
            mongoTemplate.save(any<MongoResponseStore.ResponseDocument>(), "responses")
        } returns
            MongoResponseStore.ResponseDocument(
                id = responseId,
                responseJson = """{"id":"resp_123456"}""",
                inputItems = listOf(InputMessageItem()),
                outputInputItems = listOf(),
            )

        // Act
        responseStore.storeResponse(mockResponse, inputItems)

        // Assert
        verify { mongoTemplate.save(any<MongoResponseStore.ResponseDocument>(), "responses") }
    }

    @Test
    fun `test getResponse retrieves document from MongoDB`() {
        // Setup
        val responseId = "resp_123456"
        val mockDocument =
            MongoResponseStore.ResponseDocument(
                id = responseId,
                responseJson = """{"id":"resp_123456"}""",
                inputItems = listOf(InputMessageItem()),
                outputInputItems = listOf(),
            )

        // Mock MongoDB findById operation
        every { 
            mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java)
        } returns mockDocument

        // Act
        val response = responseStore.getResponse(responseId)

        // Assert
        assertNotNull(response)
        verify { mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java) }
    }

    @Test
    fun `test getResponse returns null for nonexistent document`() {
        // Setup
        val responseId = "nonexistent_resp"

        // Mock MongoDB findById operation returning null
        every { 
            mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java)
        } returns null

        // Act
        val response = responseStore.getResponse(responseId)

        // Assert
        assertNull(response)
        verify { mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java) }
    }

    @Test
    fun `test getInputItems retrieves input items from MongoDB`() {
        // Setup
        val responseId = "resp_123456"
        val inputItems = listOf(InputMessageItem(), InputMessageItem())
        val mockDocument =
            MongoResponseStore.ResponseDocument(
                id = responseId,
                responseJson = """{"id":"resp_123456"}""",
                inputItems = inputItems,
                outputInputItems = listOf(),
            )

        // Mock MongoDB findById operation
        every { 
            mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java)
        } returns mockDocument

        // Act
        val retrievedItems = responseStore.getInputItems(responseId)

        // Assert
        assertEquals(2, retrievedItems.size)
        verify { mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java) }
    }

    @Test
    fun `test getInputItems returns empty list for nonexistent document`() {
        // Setup
        val responseId = "nonexistent_resp"

        // Mock MongoDB findById operation returning null
        every { 
            mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java)
        } returns null

        // Act
        val retrievedItems = responseStore.getInputItems(responseId)

        // Assert
        assertTrue(retrievedItems.isEmpty())
        verify { mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java) }
    }

    @Test
    fun `test deleteResponse removes document from MongoDB`() {
        // Setup
        val responseId = "resp_123456"
        
        // Mock MongoDB remove operation with positive result
        val result = mockk<DeleteResult>()
        every { result.deletedCount } returns 1
        every { 
            mongoTemplate.remove(any<Query>(), MongoResponseStore.ResponseDocument::class.java)
        } returns result

        // Act
        val deleted = responseStore.deleteResponse(responseId)

        // Assert
        assertTrue(deleted)
        verify { 
            mongoTemplate.remove(any<Query>(), MongoResponseStore.ResponseDocument::class.java)
        }
    }

    @Test
    fun `test deleteResponse returns false for nonexistent document`() {
        // Setup
        val responseId = "nonexistent_resp"
        
        // Mock MongoDB remove operation with negative result
        val result = mockk<DeleteResult>()
        every { result.deletedCount } returns 0
        every { 
            mongoTemplate.remove(any<Query>(), MongoResponseStore.ResponseDocument::class.java)
        } returns result

        // Act
        val deleted = responseStore.deleteResponse(responseId)

        // Assert
        assertFalse(deleted)
        verify { 
            mongoTemplate.remove(any<Query>(), MongoResponseStore.ResponseDocument::class.java)
        }
    }
} 
