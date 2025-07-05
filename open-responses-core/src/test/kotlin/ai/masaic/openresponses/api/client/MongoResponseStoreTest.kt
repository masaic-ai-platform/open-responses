package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.InputMessageItem
import ai.masaic.openresponses.tool.ToolService
import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.client.result.DeleteResult
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseInputItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Query
import reactor.core.publisher.Mono

class MongoResponseStoreTest {
    private lateinit var responseStore: MongoResponseStore
    private lateinit var objectMapper: ObjectMapper
    private lateinit var mongoTemplate: ReactiveMongoTemplate
    private lateinit var toolService: ToolService

    @BeforeEach
    fun setup() {
        objectMapper = mockk()
        mongoTemplate = mockk(relaxed = true)
        toolService = mockk(relaxed = true)
        
        // Mock object mapper conversions
        every { objectMapper.convertValue(ofType<ResponseInputItem>(), InputMessageItem::class.java) } returns InputMessageItem()
        every { objectMapper.writeValueAsString(any<Response>()) } returns """{"id":"resp_123456"}"""
        every { objectMapper.readValue(any<String>(), Response::class.java) } returns mockk()
        
        responseStore = MongoResponseStore(mongoTemplate, objectMapper)
    }

    @Test
    fun `test storeResponse stores document in MongoDB`() =
        runTest {
            // Setup
            val responseId = "resp_123456"
            val mockResponse = mockk<Response>()
            every { mockResponse.id() } returns responseId
            every { mockResponse.output() } returns listOf()

            val inputItems = listOf(mockk<ResponseInputItem>())

            coEvery {
                mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java)
            } returns Mono.empty()
        
            // Mock MongoDB save operation
            coEvery { 
                mongoTemplate.save(any<MongoResponseStore.ResponseDocument>(), "responses")
            } returns
                Mono.just(
                    MongoResponseStore.ResponseDocument(
                        id = responseId,
                        responseJson = """{"id":"resp_123456"}""",
                        inputItems = listOf(InputMessageItem()),
                        outputInputItems = listOf(),
                    ),
                )

            // Act
            responseStore.storeResponse(mockResponse, inputItems, mockk(relaxed = true))

            // Assert
            coVerify { mongoTemplate.save(any<MongoResponseStore.ResponseDocument>(), "responses") }
        }

    @Test
    fun `test getResponse retrieves document from MongoDB`() =
        runTest {
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
            coEvery { 
                mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java)
            } returns Mono.just(mockDocument)

            // Act
            val response = responseStore.getResponse(responseId)

            // Assert
            assertNotNull(response)
            coVerify { mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java) }
        }

    @Test
    fun `test getResponse returns null for nonexistent document`() =
        runTest {
            // Setup
            val responseId = "nonexistent_resp"

            // Mock MongoDB findById operation returning null
            coEvery { 
                mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java)
            } returns Mono.empty()

            // Act
            val response = responseStore.getResponse(responseId)

            // Assert
            assertNull(response)
            coVerify { mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java) }
        }

    @Test
    fun `test getInputItems retrieves input items from MongoDB`() =
        runTest {
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
            coEvery { 
                mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java)
            } returns Mono.just(mockDocument)

            // Act
            val retrievedItems = responseStore.getInputItems(responseId)

            // Assert
            assertEquals(2, retrievedItems.size)
            coVerify { mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java) }
        }

    @Test
    fun `test getInputItems returns empty list for nonexistent document`() =
        runTest {
            // Setup
            val responseId = "nonexistent_resp"

            // Mock MongoDB findById operation returning null
            coEvery { 
                mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java)
            } returns Mono.empty()

            // Act
            val retrievedItems = responseStore.getInputItems(responseId)

            // Assert
            assertTrue(retrievedItems.isEmpty())
            coVerify { mongoTemplate.findById(responseId, MongoResponseStore.ResponseDocument::class.java) }
        }

    @Test
    fun `test deleteResponse removes document from MongoDB`() =
        runTest {
            // Setup
            val responseId = "resp_123456"
        
            // Mock MongoDB remove operation with positive result
            val result = mockk<DeleteResult>()
            every { result.deletedCount } returns 1
            coEvery { 
                mongoTemplate.remove(any<Query>(), MongoResponseStore.ResponseDocument::class.java)
            } returns Mono.just(result)

            // Act
            val deleted = responseStore.deleteResponse(responseId)

            // Assert
            assertTrue(deleted)
            coVerify { 
                mongoTemplate.remove(any<Query>(), MongoResponseStore.ResponseDocument::class.java)
            }
        }

    @Test
    fun `test deleteResponse returns false for nonexistent document`() =
        runTest {
            // Setup
            val responseId = "nonexistent_resp"
        
            // Mock MongoDB remove operation with negative result
            val result = mockk<DeleteResult>()
            every { result.deletedCount } returns 0
            coEvery { 
                mongoTemplate.remove(any<Query>(), MongoResponseStore.ResponseDocument::class.java)
            } returns Mono.just(result)

            // Act
            val deleted = responseStore.deleteResponse(responseId)

            // Assert
            assertFalse(deleted)
            coVerify { 
                mongoTemplate.remove(any<Query>(), MongoResponseStore.ResponseDocument::class.java)
            }
        }
} 
