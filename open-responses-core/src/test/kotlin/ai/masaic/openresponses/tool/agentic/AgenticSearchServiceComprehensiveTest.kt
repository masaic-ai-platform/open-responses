package ai.masaic.openresponses.tool.agentic

import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import ai.masaic.openresponses.api.model.VectorStoreSearchResultContent
import ai.masaic.openresponses.api.service.search.HybridSearchService
import ai.masaic.openresponses.api.service.search.VectorStoreService
import ai.masaic.openresponses.tool.AgenticSearchIteration
import ai.masaic.openresponses.tool.AgenticSearchParams
import ai.masaic.openresponses.tool.ResponseParamsAdapter
import ai.masaic.openresponses.tool.ToolParamsAccessor
import ai.masaic.platform.api.config.ModelSettings
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.client.OpenAIClient
import com.openai.models.responses.ResponseCreateParams
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AgenticSearchServiceComprehensiveTest {
    private val vectorStoreService = mockk<VectorStoreService>(relaxed = true)
    private val hybridSearchService = mockk<HybridSearchService>()
    private val mapper = ObjectMapper()
    private val openAIClient = mockk<OpenAIClient>(relaxed = true)
    private val responseParams = mockk<ResponseCreateParams>(relaxed = true)
    private val modelSettings = ModelSettings("12345", "abc")

    private fun dummyResult(
        id: String,
        score: Double,
    ): VectorStoreSearchResult =
        VectorStoreSearchResult(
            fileId = id,
            filename = id,
            score = score,
            attributes = mapOf("chunk_index" to 1),
            content = listOf(VectorStoreSearchResultContent(type = "text", text = "text-$id")),
        )

    /**
     * A subclass that allows controlling LLM decisions via a queue.
     */
    private class TestableService(
        store: VectorStoreService,
        mapper: ObjectMapper,
        hybrid: HybridSearchService,
        private val decisions: MutableList<String>,
    ) : AgenticSearchService(store, mapper, hybrid) {
        override suspend fun callLlmForDecision(
            question: String,
            buffer: List<VectorStoreSearchResult>,
            iterations: List<AgenticSearchIteration>,
            attrs: Set<String>,
            iterationNumber: Int,
            maxIter: Int,
            maxResults: Int,
            isInitial: Boolean,
            paramsAccessor: ToolParamsAccessor,
            openAIClient: OpenAIClient,
            hyperParams: LlHyperParams,
        ): String = if (decisions.isNotEmpty()) decisions.removeAt(0) else "TERMINATE"
    }

    @Test
    fun `initial empty buffer returns early`() =
        runBlocking {
            coEvery { hybridSearchService.hybridSearch(any(), any(), any(), any(), modelSettings = modelSettings) } returns emptyList()
            val service = TestableService(vectorStoreService, mapper, hybridSearchService, decisions = mutableListOf("TERMINATE: no data"))
            val response =
                service.run(
                    params = AgenticSearchParams("q"),
                    vectorStoreIds = listOf("s1"),
                    userFilter = null,
                    maxResults = 5,
                    maxIterations = 3,
                    seedName = null,
                    openAIClient = openAIClient,
                    paramsAccessor = ResponseParamsAdapter(responseParams, jacksonObjectMapper()),
                    eventEmitter = {},
                    toolMetadata = mapOf(),
                    modelSettings = modelSettings,
                )
            assertTrue(response.data.isEmpty(), "Expected no data when initial buffer empty")
            assertEquals(1, response.search_iterations.size)
            assertTrue(response.search_iterations.first().is_final)
        }

    @Test
    fun `deduplication keeps highest scoring chunk`() =
        runBlocking {
            val low = dummyResult("f1", 0.2)
            val high = dummyResult("f1", 0.8)
            coEvery { hybridSearchService.hybridSearch(any(), any(), any(), any(), modelSettings = modelSettings) } returns listOf(low, high)
            val service = TestableService(vectorStoreService, mapper, hybridSearchService, decisions = mutableListOf("TERMINATE"))
            val response =
                service.run(
                    params = AgenticSearchParams("q"),
                    vectorStoreIds = listOf("s1"),
                    userFilter = null,
                    maxResults = 2,
                    maxIterations = 1,
                    seedName = null,
                    openAIClient = openAIClient,
                    paramsAccessor = ResponseParamsAdapter(responseParams, jacksonObjectMapper()),
                    eventEmitter = {},
                    toolMetadata = mapOf(),
                    modelSettings = modelSettings,
                )
            assertEquals(1, response.data.size)
            assertEquals(0.8, response.data.first().score)
        }

    @Test
    fun `retries invalid LLM decisions then terminates`() =
        runBlocking {
            coEvery { hybridSearchService.hybridSearch(any(), any(), any(), any(), modelSettings = modelSettings) } returns listOf(dummyResult("f2", 1.0))
            val decisions = mutableListOf("BAD", "WORSE", "TERMINATE: ok")
            val service = TestableService(vectorStoreService, mapper, hybridSearchService, decisions)
            val response =
                service.run(
                    params = AgenticSearchParams("q"),
                    vectorStoreIds = listOf("s1"),
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
            // After two invalid, third TERMINATE
            assertTrue(response.knowledge_acquired?.contains("ok") == true)
            assertTrue(response.search_iterations.any { it.is_final })
        }

    @Test
    fun `terminates after repeated identical queries`() =
        runBlocking {
            coEvery { hybridSearchService.hybridSearch(any(), any(), any(), any(), modelSettings = modelSettings) } returns listOf(dummyResult("f3", 0.5))
            // Always instruct same next query
            val decisions = mutableListOf("NEXT_QUERY: q {}", "NEXT_QUERY: q {}", "NEXT_QUERY: q {}", "TERMINATE: done")
            val service = TestableService(vectorStoreService, mapper, hybridSearchService, decisions)
            val response =
                service.run(
                    params = AgenticSearchParams("q"),
                    vectorStoreIds = listOf("s1"),
                    userFilter = null,
                    maxResults = 1,
                    maxIterations = 5,
                    seedName = null,
                    openAIClient = openAIClient,
                    paramsAccessor = ResponseParamsAdapter(responseParams, jacksonObjectMapper()),
                    eventEmitter = {},
                    toolMetadata = mapOf(),
                    modelSettings = modelSettings,
                )
            // Should terminate due to repeat threshold
            assertTrue(response.search_iterations.find { it.termination_reason?.contains("repeated queries") == true } != null)
        }

    @Test
    fun `force termination at max iterations if never terminating`() =
        runBlocking {
            coEvery { hybridSearchService.hybridSearch(any(), any(), any(), any(), modelSettings = modelSettings) } returns listOf(dummyResult("f4", 0.7))
            // Provide unique next queries but never TERMINATE
            val decs = mutableListOf("NEXT_QUERY: a {}", "NEXT_QUERY: b {}", "NEXT_QUERY: c {}")
            val service = TestableService(vectorStoreService, mapper, hybridSearchService, decs)
            val response =
                service.run(
                    params = AgenticSearchParams("q"),
                    vectorStoreIds = listOf("s1"),
                    userFilter = null,
                    maxResults = 1,
                    maxIterations = 2,
                    seedName = null,
                    openAIClient = openAIClient,
                    paramsAccessor = ResponseParamsAdapter(responseParams, jacksonObjectMapper()),
                    eventEmitter = {},
                    toolMetadata = mapOf(),
                    modelSettings = modelSettings,
                )
            // Last iteration should be forced termination
            val last = response.search_iterations.last()
            assertTrue(last.is_final)
            assertEquals("Reached max iterations (2).", last.termination_reason)
        }

    @Test
    fun `memory summary is built from ##MEMORY## markers`() =
        runBlocking {
            coEvery { hybridSearchService.hybridSearch(any(), any(), any(), any(), modelSettings = modelSettings) } returns listOf(dummyResult("f5", 0.9))
            val decs =
                mutableListOf(
                    // initial pass: provide memory marker
                    "NEXT_QUERY: x {} ##MEMORY## Key1; Key2",
                    // then terminate
                    "TERMINATE: final",
                )
            val service = TestableService(vectorStoreService, mapper, hybridSearchService, decs)
            val response =
                service.run(
                    params = AgenticSearchParams("q"),
                    vectorStoreIds = listOf("s1"),
                    userFilter = null,
                    maxResults = 1,
                    maxIterations = 2,
                    seedName = null,
                    openAIClient = openAIClient,
                    paramsAccessor = ResponseParamsAdapter(responseParams, jacksonObjectMapper()),
                    eventEmitter = {},
                    toolMetadata = mapOf(),
                    modelSettings = modelSettings,
                )
            // Summary should include iteration with memory content
            assertTrue(response.knowledge_acquired?.contains("Iteration 1:") == true)
            assertTrue(response.knowledge_acquired?.contains("Key1; Key2") == true)
        }
} 
