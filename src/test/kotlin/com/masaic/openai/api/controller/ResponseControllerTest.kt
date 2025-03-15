package com.masaic.openai.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.masaic.openai.api.model.CreateResponseRequest
import com.masaic.openai.api.model.ResponseObject
import com.masaic.openai.api.model.ResponseStatus
import com.masaic.openai.api.model.StreamingEvent
import com.masaic.openai.api.model.ResponseCreatedEvent
import com.masaic.openai.api.model.ResponseInProgressEvent
import com.masaic.openai.api.model.ResponseOutputItemAddedEvent
import com.masaic.openai.api.model.ResponseContentPartAddedEvent
import com.masaic.openai.api.model.ResponseOutputTextDeltaEvent
import com.masaic.openai.api.model.ResponseContentPartDoneEvent
import com.masaic.openai.api.model.ResponseOutputItemDoneEvent
import com.masaic.openai.api.model.ResponseCompletedEvent
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResponseControllerTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `test create response - non streaming`() {
        val request = CreateResponseRequest(
            model = "gpt-4o",
            input = "Test input",
            stream = false
        )

        val response = webTestClient.post()
            .uri("http://localhost:$port/v1/responses")
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody(ResponseObject::class.java)
            .returnResult()
            .responseBody

        assertNotNull(response)
        assertEquals("response", response.`object`)
        assertEquals("gpt-4o", response.model)
        assertNotNull(response.id)
        assertEquals(ResponseStatus.completed, response.status)
    }

    @Test
    fun `test create response - streaming`() {
        val request = CreateResponseRequest(
            model = "gpt-4o",
            input = "Test input",
            stream = true
        )

        val responseFlux = webTestClient.post()
            .uri("http://localhost:$port/v1/responses")
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk
            .returnResult(String::class.java)
            .responseBody

        // Collect all emitted events
        val events = responseFlux.collectList().block()

        assertNotNull(events)
        assertTrue(events.isNotEmpty())

        // Verify we have the expected event types using string contains checks
        assertTrue(events.any { it.contains("event: response.created") })
        assertTrue(events.any { it.contains("event: response.in_progress") })
        assertTrue(events.any { it.contains("event: response.output_item.added") })
        assertTrue(events.any { it.contains("event: response.content_part.added") })
        assertTrue(events.any { it.contains("event: response.output_text.delta") })
        assertTrue(events.any { it.contains("event: response.output_text.done") })
        assertTrue(events.any { it.contains("event: response.content_part.done") })
        assertTrue(events.any { it.contains("event: response.output_item.done") })
        assertTrue(events.any { it.contains("event: response.completed") })

        // First event should be response.created
        assertTrue(events.first().contains("event: response.created"))

        // Last event should be response.completed
        assertTrue(events.last().contains("event: response.completed"))

        // Parse the last event to verify the completed status
        val lastEventData = events.last().substringAfter("data: ").trim()
        val objectMapper = ObjectMapper()
        val completedEvent = objectMapper.readValue(lastEventData, Map::class.java)
        val response = completedEvent["response"] as Map<*, *>
        assertEquals("completed", response["status"])
    }

    @Test
    fun `test get response - not found`() {
        webTestClient.get()
            .uri("http://localhost:$port/v1/responses/non_existent_id")
            .exchange()
            .expectStatus().isNotFound
    }
} 