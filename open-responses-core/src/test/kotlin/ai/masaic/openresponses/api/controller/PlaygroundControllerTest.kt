package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.tool.ToolMetadata
import ai.masaic.openresponses.tool.ToolService
import ai.masaic.openresponses.tool.mcp.MCPToolExecutor
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.reactive.server.WebTestClient
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExtendWith(SpringExtension::class)
@WebFluxTest(PlaygroundController::class)
@Import(TestConfiguration::class)
class PlaygroundControllerTest {
    @MockkBean
    lateinit var toolService: ToolService

    @MockkBean
    lateinit var mcpToolExecutor: MCPToolExecutor

    @Autowired
    lateinit var webTestClient: WebTestClient

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `should return list of available tools`() {
        // Given
        val expectedTools: List<ToolMetadata> =
            listOf(
                ToolMetadata(
                    id = "tool1",
                    name = "Test Tool 1",
                    description = "A test tool",
                ),
                ToolMetadata(
                    id = "tool2",
                    name = "Test Tool 2",
                    description = "Another test tool",
                ),
            )

        every { toolService.listAvailableTools() } returns expectedTools

        // When/Then
        webTestClient
            .get()
            .uri("/v1/tools")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .consumeWith {
                val responseBody = it.responseBody
                assertNotNull(responseBody)
                val tools = json.decodeFromString<List<ToolMetadata>>(responseBody.decodeToString())
                assertEquals(expectedTools.size, tools.size)
                assertTrue { tools.containsAll(expectedTools) }
            }

        verify { toolService.listAvailableTools() }
    }
} 
