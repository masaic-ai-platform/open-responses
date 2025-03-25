package ai.masaic.openresponses.tool

import ai.masaic.openresponses.tool.mcp.MCPServerInfo
import ai.masaic.openresponses.tool.mcp.MCPToolExecutor
import ai.masaic.openresponses.tool.mcp.MCPToolRegistry
import ai.masaic.openresponses.tool.mcp.McpToolDefinition
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.ResourceLoader

/**
 * Tests for the [ToolService] class.
 *
 * These tests verify the functionality of the tool service, including listing tools,
 * retrieving specific tools, and executing tools with arguments.
 *
 * Note: Tests are currently disabled as they require specific MCP server setup.
 */
@Disabled("Tests temporarily disabled until MCP server configuration is available")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolServiceTest {
    private lateinit var toolService: ToolService
    private val mcpToolRegistry = mockk<MCPToolRegistry>()
    private val mcpToolExecutor = mockk<MCPToolExecutor>()
    private val resourceLoader = mockk<ResourceLoader>()
    private val nativeToolRegistry = mockk<NativeToolRegistry>()

    @BeforeEach
    fun setUp() {
        toolService = ToolService(mcpToolRegistry, mcpToolExecutor, resourceLoader, nativeToolRegistry)
    }

    /**
     * Sets up the test environment by loading MCP tools.
     *
     * This method is executed once before all tests in this class.
     */
    @BeforeAll
    fun loadMCPTools() {
        toolService.loadTools()
    }

    /**
     * Cleans up resources after all tests have completed.
     *
     * This method is executed once after all tests in this class.
     */
    @AfterAll
    fun shutdown() {
        toolService.cleanup()
    }

    @Test
    fun `listAvailableTools should return mapped tool metadata`() {
        // Given
        val mockTools: List<McpToolDefinition> =
            listOf(
                McpToolDefinition(
                    id = "tool1",
                    protocol = ToolProtocol.MCP,
                    hosting = ToolHosting.MASAIC_MANAGED,
                    name = "Test Tool 1",
                    description = "A test tool",
                    parameters = JsonObjectSchema.builder().build(),
                    serverInfo = MCPServerInfo("test"),
                ),
                McpToolDefinition(
                    id = "tool2",
                    protocol = ToolProtocol.MCP,
                    hosting = ToolHosting.MASAIC_MANAGED,
                    name = "Test Tool 2",
                    description = "A test tool",
                    parameters = JsonObjectSchema.builder().build(),
                    serverInfo = MCPServerInfo("test"),
                ),
            )

        every { mcpToolRegistry.findAll() } returns mockTools

        // When
        val result = toolService.listAvailableTools()

        // Then
        assertEquals(2, result.size)
        assertEquals("tool1", result[0].id)
        assertEquals("Test Tool 1", result[0].name)
        assertEquals("A test tool", result[0].description)
        assertEquals("tool2", result[1].id)
        assertEquals("Test Tool 2", result[1].name)
        assertEquals("Another test tool", result[1].description)

        verify { mcpToolRegistry.findAll() }
    }

    /**
     * Tests that a specific tool can be retrieved by name.
     *
     * Verifies that a tool with a specific name can be found in the repository.
     */
    @Test
    fun `get available tool`() {
        val tool = toolService.getAvailableTool("search_repositories")
        assert(tool?.name == "search_repositories") { "Tool with name 'search_repositories' should be available" }
    }

    /**
     * Tests that a tool can be retrieved as a function tool.
     *
     * Verifies that a tool can be converted to a function tool with valid properties.
     */
    @Test
    fun `get function tool`() {
        val tool = toolService.getFunctionTool("create_or_update_file")
        // Fail test if tool is null
        assert(tool != null) { "Tool should not be null" }

        // Now we can safely use non-null assertion
        assert(tool?.name?.isNotEmpty() == true) { "Tool name should not be empty" }
        assert(tool?.description?.isNotEmpty() == true) { "Tool description should not be empty" }
        assert(tool?.parameters?.isNotEmpty() == true) { "Tool parameters should not be empty" }
    }

    /**
     * Tests that a browser use tool can be executed with arguments.
     *
     * Verifies that the tool can be executed and returns a valid result.
     */
    @Test
    fun `execute browser use tool`() {
        val execResult =
            toolService.executeTool(
                "browser_use",
                Json.encodeToString(
                    serializer(),
                    mapOf(
                        "url" to "https://preview--telco-service-portal.lovable.app/",
                        "action" to "navigate to link and search the bill details like due date, bill amount of customer CUS10001",
                    ),
                ),
            )

        // Assert result is not null and not empty
        assert(execResult != null) { "Tool execution result should not be null" }
        assert(execResult!!.isNotEmpty()) { "Tool execution result should not be empty" }
    }
}
