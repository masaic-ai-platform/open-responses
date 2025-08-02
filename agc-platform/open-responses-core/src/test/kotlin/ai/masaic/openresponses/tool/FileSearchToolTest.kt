package ai.masaic.openresponses.tool

import ai.masaic.openresponses.api.service.search.VectorStoreService
import ai.masaic.openresponses.tool.mcp.MCPToolExecutor
import ai.masaic.openresponses.tool.mcp.MCPToolRegistry
import ai.masaic.openresponses.tool.mcp.SimpleMcpClientFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.models.responses.ResponseCreateParams
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ResourceLoader
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FileSearchToolTest {
    private lateinit var toolService: ToolService
    private lateinit var mcpToolRegistry: MCPToolRegistry
    private lateinit var mcpToolExecutor: MCPToolExecutor
    private lateinit var resourceLoader: ResourceLoader
    private lateinit var nativeToolRegistry: NativeToolRegistry
    private lateinit var vectorStoreService: VectorStoreService
    private lateinit var mcpClientFactory: SimpleMcpClientFactory
    private val openAIClient = mockk<OpenAIClient>()

    @BeforeEach
    fun setUp() {
        mcpToolRegistry = mockk()
        mcpToolExecutor = mockk()
        resourceLoader = mockk()
        nativeToolRegistry = mockk()
        vectorStoreService = mockk()
        mcpClientFactory = mockk()
        
        toolService = ToolService(mcpToolRegistry, mcpToolExecutor, resourceLoader, nativeToolRegistry, ObjectMapper(), mcpClientFactory)
    }

    @Test
    fun `executeTool should delegate to NativeToolRegistry when executing file search tool`() =
        runTest {
            // Given
            val toolName = "file_search"
            val arguments = """{"query": "test document"}"""
            val fileId = "file_12345"
            val filename = "test.txt"
            val content = "This is a test document"
        
            // Mock ResponseCreateParams with file search tool
            val params = mockk<ResponseCreateParams>()
            every { params.tools() } returns Optional.empty()

            // Create a mock tool definition
            val toolDefinition = mockk<NativeToolDefinition>()
            every { toolDefinition.name } returns toolName
            every { toolDefinition.protocol } returns ToolProtocol.NATIVE

            // Setup mock for findByName to return the tool definition
            every { nativeToolRegistry.findByName(toolName) } returns toolDefinition

            // Setup for NativeToolRegistry
            val responseJson = """{
            "data": [{
                "file_id": "$fileId",
                "filename": "$filename",
                "score": 0.95,
                "content": "$content",
                "annotations": [{
                    "type": "file_citation",
                    "index": 1,
                    "file_id": "$fileId",
                    "filename": "$filename"
                }]
            }]
        }"""

            coEvery {
                nativeToolRegistry.executeTool(toolName, arguments, any(), any(), ofType(), any(), any())
            } returns responseJson
        
            // When
            val result =
                toolService.executeTool(
                    toolName,
                    arguments,
                    params,
                    openAIClient,
                    {},
                    emptyMap(),
                    ToolRequestContext(emptyMap(), params),
                )
        
            // Then
            assertNotNull(result)
            assertEquals(responseJson, result)
            coVerify {
                nativeToolRegistry.executeTool(
                    toolName,
                    arguments,
                    any(),
                    openAIClient,
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `getFileSearchTool should return proper FunctionTool`() {
        // Given
        val toolName = "file_search"
        val toolDefinition =
            NativeToolDefinition(
                name = toolName,
                description = "Search through vector stores for relevant file content",
                parameters =
                    mutableMapOf(
                        "type" to "object",
                        "properties" to mapOf("query" to mapOf("type" to "string")),
                        "required" to listOf("query"),
                    ),
            )
        
        every { nativeToolRegistry.findByName(toolName) } returns toolDefinition
        
        // When
        val result = toolService.getFunctionTool(toolName)
        
        // Then
        assertNotNull(result)
        assertEquals(toolName, result.name)
        assertEquals("Search through vector stores for relevant file content", result.description)
        
        // Parameters should be preserved
        @Suppress("UNCHECKED_CAST")
        val properties = result.parameters["properties"] as? Map<String, Any>
        assertNotNull(properties)
        assertTrue(properties.containsKey("query"))
    }
} 
