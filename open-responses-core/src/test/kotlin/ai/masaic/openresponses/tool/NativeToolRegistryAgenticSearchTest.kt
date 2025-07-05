package ai.masaic.openresponses.tool

import ai.masaic.openresponses.api.service.search.VectorStoreService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.client.OpenAIClient
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.Tool
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.util.*
import kotlin.jvm.optionals.getOrNull
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// TODO : Add more tests for agentic_search tool
class NativeToolRegistryAgenticSearchTest {
    private lateinit var nativeToolRegistry: NativeToolRegistry
    private lateinit var vectorStoreService: VectorStoreService
    private lateinit var openAIClient: OpenAIClient
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        vectorStoreService = mockk()
        openAIClient = mockk()
        objectMapper = jacksonObjectMapper()
        nativeToolRegistry = NativeToolRegistry(objectMapper, mockk(relaxed = true))

        // Set vectorStoreService via reflection since it's private
        ReflectionTestUtils.setField(nativeToolRegistry, "vectorStoreService", vectorStoreService)
    }

    @Test
    fun `agentic_search tool is registered and can be found`() {
        // When
        val tool = nativeToolRegistry.findByName("agentic_search")

        // Then
        assertNotNull(tool)
        assertEquals("agentic_search", tool.name)
        assertEquals(ToolProtocol.NATIVE, tool.protocol)
    }

    @Test
    fun `executeAgenticSearch handles empty vector store IDs`() =
        runTest {
            // Given
            val arguments = """{"question": "What is the purpose of this document?"}"""
            val params = mockk<ResponseCreateParams>()
            val toolsOptional = mockk<Optional<List<Tool>>>()

            // Mock params.tools() chain
            every { params.tools() } returns toolsOptional
            every { toolsOptional.getOrNull() } returns null

            // When
            val result =
                nativeToolRegistry.executeTool(
                    "agentic_search",
                    arguments,
                    mockk(relaxed = true),
                    openAIClient,
                    {},
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                )

            // Then
            val response = objectMapper.readValue(result, AgenticSearchResponse::class.java)
            assertEquals(0, response.data.size)
            assertEquals(0, response.search_iterations.size)
        }
} 
