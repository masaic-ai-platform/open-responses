package ai.masaic.openevals.api.validation

import ai.masaic.openevals.api.model.*
import ai.masaic.openresponses.api.service.storage.FileService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mitchellbosecke.pebble.PebbleEngine
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.ByteArrayResource

class EvalRunValidatorTest {
    private lateinit var validator: EvalRunValidator
    private lateinit var fileService: FileService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var pebbleEngine: PebbleEngine

    @BeforeEach
    fun setUp() {
        fileService = mockk()
        objectMapper = jacksonObjectMapper()
        pebbleEngine = PebbleEngine.Builder().build()
        validator = EvalRunValidator(fileService, objectMapper, pebbleEngine)
    }

    @Test
    fun `validate does not throw for valid template expression`() {
        runBlocking {
            // Arrange
            val validJsonl =
                """
                {"notifications": ["You have a new message", "Meeting in 5 minutes"]}
                {"notifications": ["Battery low", "Update available"]}
                """.trimIndent()

            val fileId = "file-W9tGvLrqzF4Ra6gxgz32Fg"
            val fileResource = ByteArrayResource(validJsonl.toByteArray())
            coEvery { fileService.getFileContent(fileId) } returns fileResource

            val request =
                CreateEvalRunRequest(
                    name = "Valid Run",
                    dataSource =
                        CompletionsRunDataSource(
                            inputMessages =
                                TemplateInputMessages(
                                    template =
                                        listOf(
                                            ChatMessage("developer", "You are a helpful assistant that summarizes push notifications."),
                                            ChatMessage("user", "{{ item.notifications }}"),
                                        ),
                                ),
                            model = "gpt-4o-mini",
                            source = FileDataSource(id = fileId),
                        ),
                )

            // Act & Assert
            assertDoesNotThrow {
                validator.validate(request)
            }
        }
    }

    @Test
    fun `validate handles empty file content`() {
        runBlocking {
            // Arrange
            val emptyContent = ""

            val fileId = "file-W9tGvLrqzF4Ra6gxgz32Fg"
            val fileResource = ByteArrayResource(emptyContent.toByteArray())
            coEvery { fileService.getFileContent(fileId) } returns fileResource

            val request =
                CreateEvalRunRequest(
                    name = "Empty File Run",
                    dataSource =
                        CompletionsRunDataSource(
                            inputMessages =
                                TemplateInputMessages(
                                    template =
                                        listOf(
                                            ChatMessage("developer", "You are a helpful assistant."),
                                            ChatMessage("user", "{{ item.notifications }}"),
                                        ),
                                ),
                            model = "gpt-4o-mini",
                            source = FileDataSource(id = fileId),
                        ),
                )

            // Act & Assert
            assertThrows<IllegalArgumentException> {
                validator.validate(request)
            }
        }
    }

    @Test
    fun `validate handles invalid JSON in file`() {
        runBlocking {
            // Arrange
            val invalidJsonl =
                """
                {"notifications": ["Notification 1", "Notification 2"]}
                {invalid json content}
                """.trimIndent()

            val fileId = "file-W9tGvLrqzF4Ra6gxgz32Fg"
            val fileResource = ByteArrayResource(invalidJsonl.toByteArray())
            coEvery { fileService.getFileContent(fileId) } returns fileResource

            val request =
                CreateEvalRunRequest(
                    name = "Invalid JSON Run",
                    dataSource =
                        CompletionsRunDataSource(
                            inputMessages =
                                TemplateInputMessages(
                                    template =
                                        listOf(
                                            ChatMessage("developer", "You are a helpful assistant."),
                                            ChatMessage("user", "{{ item.notifications }}"),
                                        ),
                                ),
                            model = "gpt-4o-mini",
                            source = FileDataSource(id = fileId),
                        ),
                )

            // Act & Assert
            assertThrows<IllegalArgumentException> {
                validator.validate(request)
            }
        }
    }

    @Test
    fun `validate does not throw for non-CompletionsRunDataSource`() {
        runBlocking {
            // Arrange
            val request =
                CreateEvalRunRequest(
                    name = "Other Data Source",
                    dataSource = mockk<RunDataSource>(), // Some other data source type
                )

            // Act & Assert
            assertDoesNotThrow {
                validator.validate(request)
            }
        }
    }

    @Test
    fun `validate does not throw when no template expressions are found`() {
        runBlocking {
            // Arrange
            val validJsonl = """{"data": "value"}"""

            val fileId = "file-simple"
            val fileResource = ByteArrayResource(validJsonl.toByteArray())
            coEvery { fileService.getFileContent(fileId) } returns fileResource

            val request =
                CreateEvalRunRequest(
                    name = "No Templates Run",
                    dataSource =
                        CompletionsRunDataSource(
                            inputMessages =
                                TemplateInputMessages(
                                    template =
                                        listOf(
                                            ChatMessage("developer", "Regular message with no templates"),
                                            ChatMessage("user", "Another regular message"),
                                        ),
                                ),
                            model = "gpt-4o-mini",
                            source = FileDataSource(id = fileId),
                        ),
                )

            // Act & Assert
            assertDoesNotThrow {
                validator.validate(request)
            }
        }
    }
} 
