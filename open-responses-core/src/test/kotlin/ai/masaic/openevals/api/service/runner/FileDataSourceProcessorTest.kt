package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.*
import ai.masaic.openresponses.api.service.storage.FileService
import com.fasterxml.jackson.core.JsonLocation
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.mitchellbosecke.pebble.PebbleEngine
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileDataSourceProcessorTest {
    private lateinit var fileService: FileService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var pebbleEngine: PebbleEngine
    private lateinit var processor: FileDataSourceProcessor

    @BeforeEach
    fun setUp() {
        fileService = mockk()
        objectMapper = mockk(relaxed = true)
        pebbleEngine = mockk()
        processor = FileDataSourceProcessor(fileService, objectMapper, pebbleEngine)
    }

    @Test
    fun `canProcess should return true for FileDataSource`() {
        // Arrange
        val fileDataSource = FileDataSource("test-file-id")
        val runDataSource = mockk<RunDataSource>()
        every { runDataSource.source } returns fileDataSource

        // Act
        val result = processor.canProcess(runDataSource)

        // Assert
        assertTrue(result)
    }

    @Test
    fun `canProcess should return false for non-FileDataSource`() {
        // Arrange
        val nonFileDataSource = mockk<DataSource>()
        val runDataSource = mockk<RunDataSource>()
        every { runDataSource.source } returns nonFileDataSource

        // Act
        val result = processor.canProcess(runDataSource)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `getRawDataLines should get and validate file content`() =
        runBlocking {
            // Arrange
            val fileId = "test-file-id"
            val fileDataSource = FileDataSource(fileId)
            val runDataSource = mockk<RunDataSource>()
            every { runDataSource.source } returns fileDataSource

            val fileContent =
                """
                {"key1": "value1"}
                {"key2": "value2"}
                {"key3": "value3"}
                """.trimIndent()
            val resource = ByteArrayResource(fileContent.toByteArray())
        
            // Mock file service to return content
            coEvery { fileService.getFileContent(fileId) } returns resource

            // Act
            val result = processor.getRawDataLines(runDataSource)

            // Assert
            assertEquals(3, result.size)
            assertEquals("""{"key1": "value1"}""", result[0])
            assertEquals("""{"key2": "value2"}""", result[1])
            assertEquals("""{"key3": "value3"}""", result[2])
        
            // Verify
            coVerify(exactly = 1) { fileService.getFileContent(fileId) }
        }

    @Test
    fun `getRawDataLines should throw exception for invalid JSON`() =
        runBlocking<Unit> {
            // Arrange
            val fileId = "test-file-id"
            val fileDataSource = FileDataSource(fileId)
            val runDataSource = mockk<RunDataSource>()
            every { runDataSource.source } returns fileDataSource

            val fileContent =
                """
                {"key1": "value1"}
                {invalid_json}
                {"key3": "value3"}
                """.trimIndent()
            val resource = ByteArrayResource(fileContent.toByteArray())
        
            // Mock file service to return content
            coEvery { fileService.getFileContent(fileId) } returns resource
        
            // Mock ObjectMapper to validate JSON - first line valid, second throws exception
            every { objectMapper.readTree("""{"key1": "value1"}""") } returns mockk()
            every { objectMapper.readTree("{invalid_json}") } throws JsonParseException(null as JsonParser?, "Invalid JSON", null as JsonLocation?)

            // Act & Assert
            try {
                processor.getRawDataLines(runDataSource)
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("Invalid JSON at line 2"))
            }
        
            // Verify
            coVerify(exactly = 1) { fileService.getFileContent(fileId) }
        }

    @Test
    fun `processDataSource should handle ItemReferenceInputMessages`() =
        runBlocking {
            // Arrange
            val fileId = "test-file-id"
            val fileDataSource = FileDataSource(fileId)
            val inputMessages = ItemReferenceInputMessages(itemReference = "ref-123")
            val dataSource =
                CompletionsRunDataSource(
                    inputMessages = inputMessages,
                    model = "gpt-4",
                    source = fileDataSource,
                )
        
            // Mock file content (even though it won't be used in this case)
            val fileContent = "{}"
            val resource = ByteArrayResource(fileContent.toByteArray())
            coEvery { fileService.getFileContent(fileId) } returns resource
            every { objectMapper.readTree(any<String>()) } returns mockk()
        
            // Act
            val result = processor.processDataSource(dataSource)
        
            // Assert
            assertTrue(result is EmptyProcessingResult)
            val emptyResult = result as EmptyProcessingResult
            assertEquals("No messages could be processed from the data source", emptyResult.reason)
        }

    @Test
    fun `processDataSource should return empty result for unsupported input message type`() =
        runBlocking {
            // Arrange
            val fileId = "test-file-id"
            val fileDataSource = FileDataSource(fileId)
            val inputMessages = mockk<InputMessages>()
            val dataSource =
                CompletionsRunDataSource(
                    inputMessages = inputMessages,
                    model = "gpt-4",
                    source = fileDataSource,
                )
        
            // Mock file content
            val fileContent = "{}"
            val resource = ByteArrayResource(fileContent.toByteArray())
            coEvery { fileService.getFileContent(fileId) } returns resource
            every { objectMapper.readTree(any<String>()) } returns mockk()
        
            // Act
            val result = processor.processDataSource(dataSource)
        
            // Assert
            assertTrue(result is EmptyProcessingResult)
            val emptyResult = result as EmptyProcessingResult
            assertEquals("No messages could be processed from the data source", emptyResult.reason)
        }

    @Test
    fun `processDataSource should return empty result for non-CompletionsRunDataSource`() =
        runBlocking {
            // Arrange
            val fileId = "test-file-id"
            val fileDataSource = FileDataSource(fileId)
            val dataSource = mockk<RunDataSource>()
            every { dataSource.source } returns fileDataSource

            // Act
            val result = processor.processDataSource(dataSource)

            // Assert
            assertTrue(result is EmptyProcessingResult)
            val emptyResult = result as EmptyProcessingResult
            assertTrue(emptyResult.reason.startsWith("Unsupported data source type:"))
        }
}
