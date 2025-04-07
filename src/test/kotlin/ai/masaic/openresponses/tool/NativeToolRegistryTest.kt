package ai.masaic.openresponses.tool

import ai.masaic.openresponses.api.service.VectorStoreService
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.Tool
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.test.util.ReflectionTestUtils
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class NativeToolRegistryTest {
    private lateinit var nativeToolRegistry: NativeToolRegistry
    private lateinit var vectorStoreService: VectorStoreService
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp() {
        vectorStoreService = mockk()
        nativeToolRegistry = NativeToolRegistry()
        // Set vectorStoreService via reflection since it's private
        ReflectionTestUtils.setField(nativeToolRegistry, "vectorStoreService", vectorStoreService)
    }

    @Test
    fun `findByName should return think tool when name is think`() {
        // When
        val tool = nativeToolRegistry.findByName("think")

        // Then
        assertNotNull(tool)
        assertEquals("think", tool.name)
        assertEquals(ToolProtocol.NATIVE, tool.protocol)
    }

    @Test
    fun `findByName should return file_search tool when name is file_search`() {
        // When
        val tool = nativeToolRegistry.findByName("file_search")

        // Then
        assertNotNull(tool)
        assertEquals("file_search", tool.name)
        assertEquals(ToolProtocol.NATIVE, tool.protocol)
    }

    @Test
    fun `findAll should return all registered tools`() {
        // When
        val tools = nativeToolRegistry.findAll()

        // Then
        assertEquals(2, tools.size)
        assertTrue(tools.any { it.name == "think" })
        assertTrue(tools.any { it.name == "file_search" })
    }

    @Test
    fun `executeTool should return thought log message for think tool`() =
        runTest {
            // Given
            val arguments = """{"thought": "I am thinking about vectors"}"""
            val params = mockk<ResponseCreateParams>()

            // When
            val result = nativeToolRegistry.executeTool("think", arguments, params)

            // Then
            assertEquals("Your thought has been logged.", result)
        }

    @Test
    fun `executeTool should return null for unknown tool`() =
        runTest {
            // Given
            val arguments = "{}"
            val params = mockk<ResponseCreateParams>()

            // When
            val result = nativeToolRegistry.executeTool("unknown_tool", arguments, params)

            // Then
            assertEquals(null, result)
        }

    /**
     * Simple test for file search with minimal mocking
     */
    @Test
    fun `file_search tool mock test`() =
        runTest {
            // Given
            val arguments = """{"query": "test document"}"""
            val params = mockk<ResponseCreateParams>()
        
            // we're just verifying the code doesn't throw exceptions when tools aren't found
            val toolsOptional = mockk<Optional<List<Tool>>>()
            every { params.tools() } returns toolsOptional
            every { toolsOptional.orElse(null) } returns null
        
            // Just check that no error is thrown
            nativeToolRegistry.executeTool("file_search", arguments, params)
        }
} 
