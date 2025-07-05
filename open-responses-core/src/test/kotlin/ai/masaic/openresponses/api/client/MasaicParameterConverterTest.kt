package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.service.storage.FileService
import ai.masaic.openresponses.tool.NativeToolRegistry
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.core.JsonValue
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.ResponsesModel
import com.openai.models.responses.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import java.util.Optional

class MasaicParameterConverterTest {
    private val objectMapper = spyk(jacksonObjectMapper().registerModule(Jdk8Module()))

    /**
     * Test for text-based input.
     */
    @Test
    fun `test text-based input`() =
        runTest {
            val nativeToolRegistry = mockk<NativeToolRegistry>()
            val fileService = mockk<FileService>()
            val converter = MasaicParameterConverter(nativeToolRegistry, fileService, objectMapper)

            // Mock your parameter object
            val params = mockk<ResponseCreateParams>(relaxed = true)
            val input = mockk<ResponseCreateParams.Input>(relaxed = true)

            // Stub text-based input behavior
            every { input.isText() } returns true
            every { input.toString() } returns "Hello from user"
            every { input.asText() } returns "Hello from user"
            every { params.input() } returns input
            every { params.model() } returns ResponsesModel.ofString("gpt-4")
            every { params.temperature() } returns Optional.of(0.7)
            every { params.maxOutputTokens() } returns Optional.of(128)
            every { params.topP() } returns Optional.of(0.9)
            every { params.toolChoice() } returns Optional.empty()

            // Call the method under test
            val result = converter.prepareCompletion(params)

            // Assertions
            assertNotNull(result)
            assertEquals(ResponsesModel.ofString("gpt-4").asString(), result.model().asString())
            assertEquals(0.7, result.temperature().get())
            assertEquals(128, result.maxCompletionTokens().get())
            assertEquals(0.9, result.topP().get())

            // If messages exist, check the user message
            assertTrue(result.messages().isNotEmpty())
            assert(result.messages().first().isUser())
            assertEquals(
                "Hello from user",
                result
                    .messages()
                    .first()
                    .asUser()
                    .content()
                    .text()
                    .get(),
            )
        }

    /**
     * Test for message-based input with multiple user/assistant messages.
     */
    @Test
    fun `test multiple messages input`() =
        runTest {
            val nativeToolRegistry = mockk<NativeToolRegistry>()
            val fileService = mockk<FileService>()
            val converter = MasaicParameterConverter(nativeToolRegistry, fileService, objectMapper)

            val params = mockk<ResponseCreateParams>(relaxed = true)
            val input = mockk<ResponseCreateParams.Input>(relaxed = true)

            // Suppose we have message-based input
            val messageList =
                listOf<ResponseInputItem>(
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
                    },
                )

            every { input.isText() } returns false
            every { input.isResponse() } returns true
            every { input.asResponse() } returns messageList
            every { params.input() } returns input
            every { params.model() } returns ResponsesModel.ofString("gpt-4")
            every { params.temperature() } returns Optional.of(0.7)
            every { params.maxOutputTokens() } returns Optional.of(128)
            every { params.topP() } returns Optional.of(0.9)
            every { params.toolChoice() } returns Optional.empty()

            val result = converter.prepareCompletion(params)

            assertEquals(ResponsesModel.ofString("gpt-4").asString(), result.model().asString())
            assertEquals(2, result.messages().size)
            assertEquals(0.7, result.temperature().get())
            assertEquals(128, result.maxCompletionTokens().get())
            assertEquals(0.9, result.topP().get())

            val userMsg = result.messages()[0]
            val assistantMsg = result.messages()[1]
            assert(userMsg.isUser())
            assert(assistantMsg.isAssistant())
            assertEquals(
                "User says hello",
                userMsg
                    .asUser()
                    .content()
                    .text()
                    .get(),
            )
            assertEquals(
                "Assistant replies",
                assistantMsg
                    .asAssistant()
                    .content()
                    .get()
                    .text()
                    .get(),
            )
        }

    /**
     * Test for text-based input with instructions (ensures system message is inserted first).
     */
    @Test
    fun `test text-based input with instructions`() =
        runTest {
            val nativeToolRegistry = mockk<NativeToolRegistry>()
            val fileService = mockk<FileService>()
            val converter = MasaicParameterConverter(nativeToolRegistry, fileService, objectMapper)

            val params = mockk<ResponseCreateParams>(relaxed = true)
            val input = mockk<ResponseCreateParams.Input>(relaxed = true)

            every { input.isText() } returns true
            every { input.asText() } returns "User text here"
            every { params.input() } returns input
            every { params.instructions() } returns Optional.of("System instructions")
            every { params.model() } returns ResponsesModel.ofString("gpt-4")
            every { params.temperature() } returns Optional.empty()
            every { params.maxOutputTokens() } returns Optional.empty()
            every { params.topP() } returns Optional.empty()
            every { params.toolChoice() } returns Optional.empty()

            val result = converter.prepareCompletion(params)

            assertNotNull(result)
            assertEquals(ResponsesModel.ofString("gpt-4").asString(), result.model().asString())
            assertEquals(2, result.messages().size)

            // system message first
            assertTrue(result.messages().first().isSystem())
            assertEquals(
                "System instructions",
                result
                    .messages()
                    .first()
                    .asSystem()
                    .content()
                    .text()
                    .get(),
            )
            // user message second
            assertTrue(result.messages()[1].isUser())
            assertEquals(
                "User text here",
                result
                    .messages()[1]
                    .asUser()
                    .content()
                    .text()
                    .get(),
            )
        }

    /**
     * Test scenario where system or developer message is not at index 0 -> should throw an exception.
     */
    @Test
    fun `test system message at non-zero index throws`() {
        val nativeToolRegistry = mockk<NativeToolRegistry>()
        val fileService = mockk<FileService>()
        val converter = MasaicParameterConverter(nativeToolRegistry, fileService, objectMapper)

        val params = mockk<ResponseCreateParams>(relaxed = true)
        val input = mockk<ResponseCreateParams.Input>(relaxed = true)

        // The first item is user, second is system -> should break validation
        val messageList =
            listOf<ResponseInputItem>(
                mockk {
                    every { isEasyInputMessage() } returns true
                    every { asEasyInputMessage().role() } returns EasyInputMessage.Role.USER
                    every { asEasyInputMessage().content().isTextInput() } returns true
                    every { asEasyInputMessage().content().asTextInput() } returns "Hello"
                },
                mockk {
                    every { isEasyInputMessage() } returns true
                    every { asEasyInputMessage().role() } returns EasyInputMessage.Role.SYSTEM
                    every { asEasyInputMessage().content().isTextInput() } returns true
                    every { asEasyInputMessage().content().asTextInput() } returns "System instructions"
                },
            )

        every { input.isText() } returns false
        every { input.isResponse() } returns true
        every { input.asResponse() } returns messageList
        every { params.input() } returns input
        every { params.model() } returns ResponsesModel.ofString("gpt-4")
        every { params.toolChoice() } returns Optional.empty()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                converter.prepareCompletion(params)
            }
        }
    }

    /**
     * Test function call message scenario.
     */
    @Test
    fun `test function call message`() =
        runTest {
            val nativeToolRegistry = mockk<NativeToolRegistry>()
            val fileService = mockk<FileService>()
            val converter = MasaicParameterConverter(nativeToolRegistry, fileService, objectMapper)

            val params = mockk<ResponseCreateParams>(relaxed = true)
            val input = mockk<ResponseCreateParams.Input>(relaxed = true)

            val functionCallItem = mockk<ResponseInputItem>(relaxed = true)
            every { functionCallItem.isFunctionCall() } returns true
            every { functionCallItem.asFunctionCall().callId() } returns "functionCall123"
            every { functionCallItem.asFunctionCall().name() } returns "myFunction"
            every { functionCallItem.asFunctionCall().arguments() } returns "{\"key\":\"value\"}"

            val messageList = listOf(functionCallItem)
            every { input.isText() } returns false
            every { input.isResponse() } returns true
            every { input.asResponse() } returns messageList
            every { params.input() } returns input
            every { params.model() } returns ResponsesModel.ofString("gpt-4")
            every { params.toolChoice() } returns Optional.empty()

            val result = converter.prepareCompletion(params)
            assertEquals(1, result.messages().size)

            val msg = result.messages().first()
            assertTrue(msg.isAssistant())

            val assistantMsg = msg.asAssistant()
            assertEquals(1, assistantMsg.toolCalls().get().size)
            assertEquals(
                "functionCall123",
                assistantMsg
                    .toolCalls()
                    .get()
                    .first()
                    .id(),
            )
            assertEquals(
                "myFunction",
                assistantMsg
                    .toolCalls()
                    .get()
                    .first()
                    .function()
                    .name(),
            )
            assertEquals(
                "{\"key\":\"value\"}",
                assistantMsg
                    .toolCalls()
                    .get()
                    .first()
                    .function()
                    .arguments(),
            )
        }

    /**
     * Test function call output scenario.
     */
    @Test
    fun `test function call output message`() =
        runTest {
            val nativeToolRegistry = mockk<NativeToolRegistry>()
            val fileService = mockk<FileService>()
            val converter = MasaicParameterConverter(nativeToolRegistry, fileService, objectMapper)

            val params = mockk<ResponseCreateParams>(relaxed = true)
            val input = mockk<ResponseCreateParams.Input>(relaxed = true)

            val functionCallOutputItem = mockk<ResponseInputItem>(relaxed = true)
            every { functionCallOutputItem.isFunctionCallOutput() } returns true
            every { functionCallOutputItem.asFunctionCallOutput().callId() } returns "functionCallOutput123"
            every { functionCallOutputItem.asFunctionCallOutput().output() } returns "Some output"

            val messageList = listOf(functionCallOutputItem)
            every { input.isText() } returns false
            every { input.isResponse() } returns true
            every { input.asResponse() } returns messageList
            every { params.input() } returns input
            every { params.model() } returns ResponsesModel.ofString("gpt-4")
            every { params.toolChoice() } returns Optional.empty()

            val result = converter.prepareCompletion(params)
            assertEquals(1, result.messages().size)

            val msg = result.messages().first()
            assertTrue(msg.isTool())

            val toolMsg = msg.asTool()
            assertEquals("Some output", toolMsg.content().text().get())
            assertEquals("functionCallOutput123", toolMsg.toolCallId())
        }

    /**
     * Test tool configuration with a function tool.
     */
    @Test
    fun `test applyToolConfiguration with function tool`() =
        runTest {
            val nativeToolRegistry = mockk<NativeToolRegistry>()
            val fileService = mockk<FileService>()
            val converter = MasaicParameterConverter(nativeToolRegistry, fileService, objectMapper)
            val params = mockk<ResponseCreateParams>(relaxed = true)
            val input = mockk<ResponseCreateParams.Input>(relaxed = true)

            // Provide at least one message in the list, so that the converter can process it
            val messageList =
                listOf(
                    mockk<ResponseInputItem>(relaxed = true).apply {
                        every { isEasyInputMessage() } returns true
                        every { asEasyInputMessage().role() } returns EasyInputMessage.Role.USER
                        every { asEasyInputMessage().content().isTextInput() } returns true
                        every { asEasyInputMessage().content().asTextInput() } returns "Hello from user"
                    },
                )

            every { input.isText() } returns false
            every { input.isResponse() } returns true
            every { input.asResponse() } returns messageList
            every { params.input() } returns input

            // Mock the tool and its function
            val tool = mockk<Tool>(relaxed = true)
            val functionTool = mockk<FunctionTool>(relaxed = true)
            every { functionTool.parameters() } returns Optional.empty()
            every { tool.isFunction() } returns true
            every { functionTool.name() } returns "someFunction"
            every { tool.asFunction() } returns functionTool
            every { objectMapper.writeValueAsString(any()) } returns "{}"

            // Provide the tool
            every { params.tools() } returns Optional.of(listOf(tool))
            every { params.toolChoice() } returns Optional.empty()
            every { params.model() } returns ResponsesModel.ofString("gpt-4")

            val result = converter.prepareCompletion(params)
            assertTrue(result.tools().isPresent)
            assertEquals(1, result.tools().get().size)
            val firstTool = result.tools().get().first()
            assertEquals(JsonValue.from("function"), firstTool._type())
            // verify the function definition name
            assertEquals(
                "someFunction",
                firstTool
                    .function()
                    ._name()
                    .asString()
                    .get(),
            )
        }

    /**
     * Test applying response formatting with JSON schema.
     */
    @Test
    fun `test applyResponseFormatting with JSON schema`() =
        runTest {
            val nativeToolRegistry = mockk<NativeToolRegistry>()
            val fileService = mockk<FileService>()
            val converter = MasaicParameterConverter(nativeToolRegistry, fileService, objectMapper)

            val schemaObject = mockk<ResponseFormatTextJsonSchemaConfig.Schema>(relaxed = true)
            val params = mockk<ResponseCreateParams>(relaxed = true)
            val input = mockk<ResponseCreateParams.Input>(relaxed = true)

            val jsonSchemaFormat = mockk<ResponseFormatTextJsonSchemaConfig>(relaxed = true)

            every { jsonSchemaFormat.schema() } returns schemaObject
            every { jsonSchemaFormat._type() } returns JsonValue.from("object")
            every { jsonSchemaFormat._type().asString().get() } returns JsonValue.from("object").asString().get()
            every { jsonSchemaFormat._name() } returns JsonValue.from("MyJSONSchema")
            every { objectMapper.writeValueAsString(schemaObject) } returns "{}"

            val text = mockk<ResponseTextConfig>(relaxed = true)
            val format =
                mockk<ResponseFormatTextConfig>(relaxed = true) {
                    every { isJsonSchema() } returns true
                    every { asJsonSchema() } returns jsonSchemaFormat
                    every { asJsonSchema().schema() } returns schemaObject
                }

            every { input.isText() } returns true
            every { input.asText() } returns "Test input text"
            every { params.input() } returns input

            every { params.text() } returns Optional.of(text)
            every { text.format() } returns Optional.of(format)

            every { params.model() } returns ResponsesModel.ofString("gpt-4")
            every { params.toolChoice() } returns Optional.empty()

            val result = converter.prepareCompletion(params)

            val rf = result.responseFormat().get()
            assertTrue(rf.isJsonSchema())
            // verify the JSON schema
            rf.asJsonSchema()
        }

    /**
     * Test applying reasoning effort.
     */
    @Test
    fun `test applyReasoningEffort`() =
        runTest {
            val nativeToolRegistry = mockk<NativeToolRegistry>()
            val fileService = mockk<FileService>()
            val converter = MasaicParameterConverter(nativeToolRegistry, fileService, objectMapper)

            val params = mockk<ResponseCreateParams>(relaxed = true)
            val input = mockk<ResponseCreateParams.Input>(relaxed = true)
            val reasoning = mockk<Reasoning>(relaxed = true)

            every { input.isText() } returns true
            every { input.asText() } returns "Just some text"
            every { params.input() } returns input

            every { params.reasoning() } returns Optional.of(reasoning)
            every { reasoning.effort() } returns Optional.of(ReasoningEffort.MEDIUM)

            every { params.model() } returns ResponsesModel.ofString("gpt-4")
            every { params.toolChoice() } returns Optional.empty()

            val result = converter.prepareCompletion(params)
            assertTrue(result.reasoningEffort().isPresent)
            assertEquals(ReasoningEffort.of("medium"), result.reasoningEffort().get())
        }

    /**
     * Test scenario with an unsupported input type, expecting an exception.
     */
    @Test
    fun `test prepareCompletion with unsupported input type`() =
        runTest {
            val nativeToolRegistry = mockk<NativeToolRegistry>()
            val fileService = mockk<FileService>()
            val converter = MasaicParameterConverter(nativeToolRegistry, fileService, objectMapper)

            val params = mockk<ResponseCreateParams>(relaxed = true)
            val input = mockk<ResponseCreateParams.Input>(relaxed = true)

            every { input.isText() } returns false
            every { input.isResponse() } returns false
            every { params.input() } returns input
            every { params.model() } returns ResponsesModel.ofString("gpt-4")

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { converter.prepareCompletion(params) }
            }
        }

    /**
     * Test the new file input handling with DocumentTextExtractor
     */
    @Test
    fun `test input file is extracted to text using DocumentTextExtractor`() =
        runTest {
            val nativeToolRegistry = mockk<NativeToolRegistry>()
            val fileService = mockk<FileService>()
            val converter = MasaicParameterConverter(nativeToolRegistry, fileService, objectMapper)
        
            val params = mockk<ResponseCreateParams>(relaxed = true)
            val input = mockk<ResponseCreateParams.Input>(relaxed = true)
        
            // Create a message with file content
            val fileContent = mockk<ResponseInputContent>(relaxed = true)
            val inputFile = mockk<ResponseInputFile>(relaxed = true)
            val messageItem = mockk<ResponseInputItem>(relaxed = true)
            val message = mockk<EasyInputMessage>(relaxed = true)
        
            // File information
            val fileId = "file_123456"
            val extractedText = "This is the extracted text content from the file."
            val fileResource = ByteArrayResource(extractedText.toByteArray())
        
            // Setup mocks
            every { fileContent.isInputFile() } returns true
            every { fileContent.isInputText() } returns false
            every { fileContent.isInputImage() } returns false
            every { fileContent.asInputFile() } returns inputFile
            every { inputFile._fileId() } returns JsonValue.from(fileId)
        
            every { messageItem.isMessage() } returns false
            every { messageItem.isEasyInputMessage() } returns true
            every { messageItem.asEasyInputMessage() } returns message
            every { message.role() } returns EasyInputMessage.Role.USER
            every { message.content().isResponseInputMessageContentList() } returns true
            every { message.content().asResponseInputMessageContentList() } returns listOf(fileContent)
        
            // Mock file service to return a file with the extracted content
            coEvery { fileService.getFileContent(fileId) } returns fileResource
        
            // Create a list with our message
            val messageList = listOf(messageItem)
        
            // Setup the parameter input
            every { input.isText() } returns false
            every { input.isResponse() } returns true
            every { input.asResponse() } returns messageList
            every { params.input() } returns input
            every { params.model() } returns ResponsesModel.ofString("gpt-4o")
            every { params.toolChoice() } returns Optional.empty()
        
            // Execute the test
            val result = converter.prepareCompletion(params)
        
            // Verify the results
            assertEquals(1, result.messages().size)
            val userMsg = result.messages().first()
            assertTrue(userMsg.isUser())
        
            // Verify content parts - we expect the user message to have a text part with the extracted content
            // The exact structure depends on how your content parts are implemented in practice
            assertTrue(
                userMsg
                    .user()
                    .get()
                    .content()
                    .arrayOfContentParts()
                    .get()
                    .any { it.isText() },
            )
        
            // or, if your implementation uses content parts:
            val contentPartsList = userMsg.asUser().content().arrayOfContentParts()
            val contentParts = contentPartsList.get()
            assertTrue(contentParts.isNotEmpty())
            val firstPart = contentParts.first()
            assertTrue(firstPart.isText())
            assertEquals(extractedText.trim(), firstPart.asText().text().trim())
        }
}
