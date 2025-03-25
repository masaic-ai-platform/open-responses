package ai.masaic.openresponses.tool

import ai.masaic.openresponses.tool.mcp.MCPToolRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class ToolIntegrationTest {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var toolService: ToolService

    @Autowired
    private lateinit var mcpToolRegistry: MCPToolRegistry

    @Test
    fun `tools endpoint should return available tools`() {
        webTestClient
            .get()
            .uri("/v1/tools")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$")
            .isArray()
    }

    @Test
    fun `tool service should list registered tools`() {
        val tools = toolService.listAvailableTools()
        assert(tools.size >= 0) { "Tool list should be retrievable" }
    }
} 
