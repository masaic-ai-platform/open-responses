package com.masaic.openai.api.client

import com.openai.models.ChatModel
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Optional

class MasaicParameterConverterTest {

    @Test
    fun `test text-based input`() {
        // Create an instance of the class under test
        val converter = MasaicParameterConverter()

        // Mock your parameter object
        val params = mockk<ResponseCreateParams>(relaxed = true)
        val input = mockk<ResponseCreateParams.Input>(relaxed = true)

        // Stub text-based input behavior
        every { input.isText() } returns true
        every { input.toString() } returns "Hello from user"
        every { params.input() } returns input
        every { params.model() } returns ChatModel.of("gpt-3.5-turbo")
        every { params.temperature() } returns Optional.of(0.7)
        every { params.maxOutputTokens() } returns Optional.of(128)
        every { params.topP() } returns Optional.of(0.9)
        every { params.toolChoice() } returns Optional.empty()
        // Stub any other optional fields if needed

        // Call the method under test
        val result = converter.prepareCompletion(params)

        // Assertions
        assertNotNull(result)
        assertEquals(ChatModel.of("gpt-3.5-turbo"), result.model())
        assertEquals(0.7, result.temperature().get())
        assertEquals(128, result.maxCompletionTokens().get())
        assertEquals(0.9, result.topP().get())

        // If messages exist, check the user message
        assertTrue(result.messages().isNotEmpty())
        assert(result.messages().first().isUser())
        assertEquals("Hello from user", result.messages().first().asUser().content().text().get())
    }

    @Test
    fun `test multiple messages input`() {
        val converter = MasaicParameterConverter()

        val params = mockk<ResponseCreateParams>(relaxed = true)
        val input = mockk<ResponseCreateParams.Input>(relaxed = true)

        // Suppose we have message-based input. Mock it:
        val messageList = listOf<ResponseInputItem>(
            mockk {
                every { isEasyInputMessage() } returns true
                every { asEasyInputMessage().role() } returns EasyInputMessage.Role.USER
                every { asEasyInputMessage().content().isTextInput() } returns true
                every { asEasyInputMessage().content().asTextInput() } returns "User says hello"
            },
            mockk {
                every { isEasyInputMessage() } returns true
                every { asEasyInputMessage().role() } returns EasyInputMessage.Role.ASSISTANT
                every { asEasyInputMessage().content().isTextInput() } returns true
                every { asEasyInputMessage().content().asTextInput() } returns "Assistant replies"
            }
        )

        every { input.isText() } returns false
        every { input.asResponse() } returns messageList
        every { params.input() } returns input
        every { params.model() } returns ChatModel.of("gpt-3.5-turbo")
        every { params.temperature() } returns Optional.of(0.7)
        every { params.maxOutputTokens() } returns Optional.of(128)
        every { params.topP() } returns Optional.of(0.9)
        every { params.toolChoice() } returns Optional.empty()

        val result = converter.prepareCompletion(params)

        assertEquals(ChatModel.of("gpt-3.5-turbo"), result.model())
        assertEquals(2, result.messages().size)
        assertEquals(0.7, result.temperature().get())
        assertEquals(128, result.maxCompletionTokens().get())
        assertEquals(0.9, result.topP().get())

        val userMsg = result.messages()[0]
        val assistantMsg = result.messages()[1]
        assert(userMsg.isUser())
        assert(assistantMsg.isAssistant())
        assertEquals("User says hello", userMsg.asUser().content().text().get())
        assertEquals("Assistant replies", assistantMsg.asAssistant().content().get().text().get())
    }
}