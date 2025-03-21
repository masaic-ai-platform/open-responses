package com.masaic.openai.tool

import com.masaic.openai.tool.mcp.MCPToolExecutor
import com.masaic.openai.tool.mcp.MCPToolRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.*
import org.springframework.core.io.DefaultResourceLoader
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
    private val toolService = ToolService(MCPToolRegistry(), MCPToolExecutor(), DefaultResourceLoader())

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

    /**
     * Tests that available tools can be listed.
     * 
     * Verifies that the list of tools returned by the service is not empty.
     */
    @Test
    fun `test list available tools`() {
        val tools = toolService.listAvailableTools()
        assert(tools.isNotEmpty()) { "Tool list should not be empty" }
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
        val execResult = toolService.executeTool(
            "browser_use",
            Json.encodeToString(
                mapOf(
                    "url" to "https://preview--telco-service-portal.lovable.app/",
                    "action" to "navigate to link and search the bill details like due date, bill amount of customer CUS10001"
                )
            )
        )

        // Assert result is not null and not empty
        assert(execResult != null) { "Tool execution result should not be null" }
        assert(execResult!!.isNotEmpty()) { "Tool execution result should not be empty" }
    }
}