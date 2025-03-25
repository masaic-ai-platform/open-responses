package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.tool.ToolMetadata
import ai.masaic.openresponses.tool.ToolService
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.reactive.server.WebTestClient

@ExtendWith(SpringExtension::class)
@WebFluxTest(PlaygroundController::class)
@Import(TestConfiguration::class)
class PlaygroundControllerTest {
    @MockkBean
    lateinit var toolService: ToolService

    @Autowired
    lateinit var webTestClient: WebTestClient

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
            .expectBodyList(ToolMetadata::class.java)
            .hasSize(2)

        verify { toolService.listAvailableTools() }
    }
} 
