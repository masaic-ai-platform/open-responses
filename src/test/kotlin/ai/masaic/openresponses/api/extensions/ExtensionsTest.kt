package ai.masaic.openresponses.api.extensions

import ai.masaic.openresponses.api.client.ResponseStore
import ai.masaic.openresponses.api.model.InputMessageItem
import ai.masaic.openresponses.api.service.ResponseNotFoundException
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.ChatModel
import com.openai.models.Metadata
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseTextConfig
import com.openai.models.responses.ToolChoiceOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Optional

class ExtensionsTest {
    @Test
    fun `fromBody should copy all properties when no previousResponseId is present`() {
        // Setup
        val objectMapper = mockk<ObjectMapper>()
        val responseStore = mockk<ResponseStore>()
        
        val body =
            mockk<ResponseCreateParams.Body> {
                every { previousResponseId() } returns Optional.empty()
                every { input() } returns ResponseCreateParams.Input.ofText("Test input")
                every { model() } returns ChatModel.of("gpt-4o")
                every { instructions() } returns Optional.of("Test instructions")
                every { reasoning() } returns Optional.empty()
                every { include() } returns Optional.empty()
                every { parallelToolCalls() } returns Optional.of(true)
                every { maxOutputTokens() } returns Optional.of(100)
                every { metadata() } returns Optional.of(Metadata.builder().build())
                every { store() } returns Optional.of(true)
                every { temperature() } returns Optional.of(0.7)
                every { topP() } returns Optional.of(0.9)
                every { truncation() } returns
                    Optional.of(
                        ResponseCreateParams.Truncation.AUTO,
                    )
                every { _additionalProperties() } returns mapOf()

                // Optional parameters
                every { text() } returns Optional.of(ResponseTextConfig.builder().build())
                every { user() } returns Optional.of("testUser")
                every { toolChoice() } returns Optional.of(ResponseCreateParams.ToolChoice.ofOptions(ToolChoiceOptions.AUTO))
                every { tools() } returns Optional.of(listOf())
            }
        
        val builder = ResponseCreateParams.builder()
        
        // Act
        builder.fromBody(body, responseStore, objectMapper)
        val params = builder.build()
        
        // Assert
        assertEquals("Test input", params.input().asText())
        assertEquals("gpt-4o", params.model().asString())
        assertEquals("Test instructions", params.instructions().get())
        assertEquals(true, params.parallelToolCalls().get())
        assertEquals(100, params.maxOutputTokens().get())
        assertEquals(true, params.store().get())
        assertEquals(0.7, params.temperature().get())
        assertEquals(0.9, params.topP().get())
        assertEquals(ResponseCreateParams.Truncation.AUTO, params.truncation().get())
        assertEquals("testUser", params.user().get())
    }

    @Test
    fun `fromBody should throw ResponseNotFoundException when previousResponseId is present but response not found`() {
        // Setup
        val objectMapper = mockk<ObjectMapper>()
        val responseStore = mockk<ResponseStore>()
        val previousResponseId = "resp_123456"
        
        val body =
            mockk<ResponseCreateParams.Body> {
                every { previousResponseId() } returns Optional.of(previousResponseId)
                every { input() } returns ResponseCreateParams.Input.ofResponse(listOf())
            }
        
        every { responseStore.getResponse(previousResponseId) } returns null
        
        val builder = ResponseCreateParams.builder()
        
        // Act & Assert
        assertThrows(ResponseNotFoundException::class.java) {
            builder.fromBody(body, responseStore, objectMapper)
        }
    }

    @Test
    fun `fromBody should combine previous and current input items when previousResponseId is present`() {
        // Setup
        val objectMapper = mockk<ObjectMapper>()
        val responseStore = mockk<ResponseStore>()
        val previousResponseId = "resp_123456"
        
        // Mock previous response
        val mockResponse = mockk<Response>()
        every { responseStore.getResponse(previousResponseId) } returns mockResponse
        
        // Mock previous input items
        val previousInputItem1 = mockk<InputMessageItem>()
        val previousInputItem2 = mockk<InputMessageItem>()
        val previousInputItems = listOf(previousInputItem1, previousInputItem2)
        every { responseStore.getInputItems(previousResponseId) } returns previousInputItems
        
        // Mock previous output items
        val previousOutputItem = mockk<InputMessageItem>()
        val previousOutputItems = listOf(previousOutputItem)
        every { responseStore.getOutputItems(previousResponseId) } returns previousOutputItems
        
        // Mock converted input items
        val convertedInputItem1 = mockk<ResponseInputItem>()
        val convertedInputItem2 = mockk<ResponseInputItem>()
        val convertedOutputItem = mockk<ResponseInputItem>()
        every { objectMapper.convertValue(previousInputItem1, ResponseInputItem::class.java) } returns convertedInputItem1
        every { objectMapper.convertValue(previousInputItem2, ResponseInputItem::class.java) } returns convertedInputItem2
        every { objectMapper.convertValue(previousOutputItem, ResponseInputItem::class.java) } returns convertedOutputItem
        
        // Mock current input
        val currentInputItem = mockk<ResponseInputItem>()
        val currentInputItems = listOf(currentInputItem)
        
        val body =
            mockk<ResponseCreateParams.Body> {
                every { previousResponseId() } returns Optional.of(previousResponseId)
                every { input() } returns ResponseCreateParams.Input.ofResponse(currentInputItems)
                every { model() } returns ChatModel.of("gpt-4o")
                every { instructions() } returns Optional.empty()
                every { reasoning() } returns Optional.empty()
                every { parallelToolCalls() } returns Optional.empty()
                every { maxOutputTokens() } returns Optional.empty()
                every { include() } returns Optional.empty()
                every { metadata() } returns Optional.empty()
                every { store() } returns Optional.of(true)
                every { temperature() } returns Optional.of(1.0)
                every { topP() } returns Optional.of(1.0)
                every { truncation() } returns Optional.empty()
                every { _additionalProperties() } returns emptyMap()
                every { text() } returns Optional.empty()
                every { user() } returns Optional.empty()
                every { toolChoice() } returns Optional.empty()
                every { tools() } returns Optional.empty()
            }
        
        // Capture the combined input items list for verification
        val inputSlot = slot<ResponseCreateParams.Input>()
        val builder =
            mockk<ResponseCreateParams.Builder> {
                every { input(capture(inputSlot)) } returns this
                every { model(ofType<ChatModel>()) } returns this
                every { instructions(ofType<Optional<String>>()) } returns this
                every { reasoning(ofType<Optional<com.openai.models.Reasoning>>()) } returns this
                every { parallelToolCalls(ofType<Optional<Boolean>>()) } returns this
                every { maxOutputTokens(ofType<Optional<Long>>()) } returns this
                every { metadata(Optional.empty()) } returns this
                every { include(Optional.empty()) } returns this
                every { store(ofType<Optional<Boolean>>()) } returns this
                every { temperature(ofType<Optional<Double>>()) } returns this
                every { topP(ofType<Optional<Double>>()) } returns this
                every { truncation(Optional.empty()) } returns this
                every { additionalBodyProperties(any()) } returns this
            }
        
        // Act
        builder.fromBody(body, responseStore, objectMapper)
        
        // Assert
        verify { responseStore.getResponse(previousResponseId) }
        verify { responseStore.getInputItems(previousResponseId) }
        verify { responseStore.getOutputItems(previousResponseId) }
        
        // Verify all items were combined in the correct order
        val combinedItems = inputSlot.captured.asResponse()
        assertEquals(4, combinedItems.size)
        assertEquals(convertedInputItem1, combinedItems[0])
        assertEquals(convertedInputItem2, combinedItems[1])
        assertEquals(convertedOutputItem, combinedItems[2])
        assertEquals(currentInputItem, combinedItems[3])
    }
} 
