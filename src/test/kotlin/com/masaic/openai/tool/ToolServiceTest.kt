package com.masaic.openai.tool

import com.masaic.openai.tool.mcp.MCPToolExecutor
import com.masaic.openai.tool.mcp.MCPToolRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.*

@Disabled("Tests temporarily disabled")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolServiceTest {
    private val toolService = ToolService(MCPToolRegistry(), MCPToolExecutor())

    @BeforeAll
    fun loadMCPTools() {
        toolService.loadTools()
    }

    @AfterAll
    fun shutdown() {
        toolService.cleanup()
    }

    @Test
    fun `test list available tools`() {
        val tools = toolService.listAvailableTools()
        assert(tools.isNotEmpty())
    }

    @Test
    fun `get available tool`() {
        val tool = toolService.getAvailableTool("search_repositories")
        assert(tool.name == "search_repositories")
    }

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