package ai.masaic.openresponses.tool.agentic

import ai.masaic.openresponses.api.service.search.HybridSearchService
import ai.masaic.openresponses.api.service.search.VectorStoreService
import ai.masaic.openresponses.tool.AgenticSearchParams
import ai.masaic.openresponses.tool.ResponseParamsAdapter
import ai.masaic.platform.api.config.ModelSettings
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.client.OpenAIClient
import com.openai.models.responses.ResponseCreateParams
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Optional

class AgenticSearchServiceTest {
    private val vectorStoreService = mockk<VectorStoreService>()
    private val hybridSearchService = mockk<HybridSearchService>()
    private val mapper = ObjectMapper()
    private val agenticSearchService = AgenticSearchService(vectorStoreService, mapper, hybridSearchService)
    private val openAIClient = mockk<OpenAIClient>()
    private val modelSettings = ModelSettings("1234", "abd")
    private val responseParams =
        mockk<ResponseCreateParams> {
            every { temperature() } returns Optional.of(0.9)
            every { topP() } returns Optional.of(0.9)
        }

    @Test
    fun `run throws exception when question is blank`() {
        val params = AgenticSearchParams("")
        val vectorStores = listOf("store1")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                agenticSearchService.run(
                    params = params,
                    vectorStoreIds = vectorStores,
                    userFilter = null,
                    maxResults = 5,
                    maxIterations = 5,
                    seedName = null,
                    openAIClient = openAIClient,
                    paramsAccessor = ResponseParamsAdapter(responseParams, jacksonObjectMapper()),
                    eventEmitter = {},
                    toolMetadata = mapOf(),
                    modelSettings = modelSettings,
                )
            }
        }
    }

    @Test
    fun `run throws exception when maxResults is non-positive`() {
        val params = AgenticSearchParams("valid question")
        val vectorStores = listOf("store1")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                agenticSearchService.run(
                    params = params,
                    vectorStoreIds = vectorStores,
                    userFilter = null,
                    maxResults = 0,
                    maxIterations = 5,
                    seedName = null,
                    openAIClient = openAIClient,
                    paramsAccessor = ResponseParamsAdapter(responseParams, jacksonObjectMapper()),
                    eventEmitter = {},
                    toolMetadata = mapOf(),
                    modelSettings = modelSettings,
                )
            }
        }
    }

    @Test
    fun `run throws exception when maxIterations is non-positive`() {
        val params = AgenticSearchParams("valid question")
        val vectorStores = listOf("store1")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                agenticSearchService.run(
                    params = params,
                    vectorStoreIds = vectorStores,
                    userFilter = null,
                    maxResults = 5,
                    maxIterations = 0,
                    seedName = null,
                    openAIClient = openAIClient,
                    paramsAccessor = ResponseParamsAdapter(responseParams, jacksonObjectMapper()),
                    eventEmitter = {},
                    toolMetadata = mapOf(),
                    modelSettings = modelSettings,
                )
            }
        }
    }

    @Test
    fun `run returns early empty response when initial buffer is empty`() =
        runBlocking {
            // stub hybridSearchService to return no results
            coEvery { hybridSearchService.hybridSearch(any(), any(), any(), any(), modelSettings = modelSettings) } returns emptyList()

            val params = AgenticSearchParams("test query")
            val vectorStores = listOf("store1")

            val response =
                agenticSearchService.run(
                    params = params,
                    vectorStoreIds = vectorStores,
                    userFilter = null,
                    maxResults = 3,
                    maxIterations = 5,
                    seedName = null,
                    openAIClient = openAIClient,
                    paramsAccessor = ResponseParamsAdapter(responseParams, jacksonObjectMapper()),
                    eventEmitter = {},
                    toolMetadata = mapOf(),
                    modelSettings = modelSettings,
                )

            assertTrue(response.data.isEmpty(), "Expected no data in response when initial buffer is empty")
            assertEquals(1, response.search_iterations.size)
            val iteration = response.search_iterations.first()
            assertTrue(iteration.is_final, "Expected iteration to be marked final")
            assertEquals("No initial results found.", iteration.termination_reason)
            assertEquals(response.knowledge_acquired?.contains("No initial results found."), true)
        }
} 
