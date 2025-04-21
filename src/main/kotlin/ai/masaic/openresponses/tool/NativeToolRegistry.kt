package ai.masaic.openresponses.tool

import ai.masaic.openresponses.api.model.Filter
import ai.masaic.openresponses.api.model.VectorStoreSearchRequest
import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import ai.masaic.openresponses.api.service.search.VectorStoreService
import ai.masaic.openresponses.tool.agentic.AgenticSearchService
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.models.responses.ResponseCreateParams
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import kotlin.jvm.optionals.getOrDefault
import kotlin.jvm.optionals.getOrNull

@Component
class NativeToolRegistry(
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(NativeToolRegistry::class.java)
    private val toolRepository = mutableMapOf<String, ToolDefinition>()

    @Autowired
    private lateinit var vectorStoreService: VectorStoreService
    
    @Autowired
    private lateinit var agenticSearchService: AgenticSearchService

    init {
        toolRepository["think"] = loadExtendedThinkTool()
        toolRepository["file_search"] = loadFileSearchTool()
        toolRepository["agentic_search"] = loadAgenticSearchTool()
    }

    fun findByName(name: String): ToolDefinition? = toolRepository[name]

    fun findAll(): List<ToolDefinition> = toolRepository.values.toList()

    suspend fun executeTool(
        name: String,
        arguments: String,
        params: ResponseCreateParams,
        client: OpenAIClient,
    ): String? {
        toolRepository[name] ?: return null
        log.debug("Executing tool $name with arguments: $arguments")

        return when (name) {
            "think" -> "Your thought has been logged."
            "file_search" -> executeFileSearch(arguments, params)
            "agentic_search" -> executeAgenticSearch(arguments, params, client)
            else -> null
        }
    }

    /**
     * Executes a file search operation using the vector store service.
     *
     * @param arguments JSON string containing the search parameters
     * @param requestParams Optional metadata from the request
     * @return JSON string containing the search results
     */
    private suspend fun executeFileSearch(
        arguments: String,
        requestParams: ResponseCreateParams,
    ): String {
        val params = objectMapper.readValue(arguments, FileSearchParams::class.java)

        val function =
            requestParams
                .tools()
                .getOrNull()
                ?.filter { it.isFileSearch() }
                ?.map { it.asFileSearch() } ?: return objectMapper.writeValueAsString(FileSearchResponse(emptyList()))

        val vectorStoreIds = function.first().vectorStoreIds()
        val filters =
            if (function.first().filters().isPresent) {
                val filterJson = function.first().filters().get()
                filterJson._json().get().convert(Filter::class.java)
            } else {
                null
            }

        val maxResults =
            function
                .first()
                .maxNumResults()
                .getOrDefault(20)
                .toInt()

        // Search each vector store and combine results
        val allResults = mutableListOf<VectorStoreSearchResult>()
        for (vectorStoreId in vectorStoreIds) {
            try {
                val searchRequest =
                    VectorStoreSearchRequest(
                        query = params.query,
                        maxNumResults = maxResults,
                        filters = filters,
                    )
                val results = vectorStoreService.searchVectorStore(vectorStoreId, searchRequest)
                allResults.addAll(results.data)
            } catch (e: Exception) {
                log.error("Error searching vector store $vectorStoreId", e)
            }
        }

        // Sort results by score and limit to max_num_results
        val sortedResults =
            allResults
                .sortedByDescending { it.score }
                .take(maxResults)

        // Convert results to JSON with file citations
        val response =
            FileSearchResponse(
                data =
                    sortedResults.map { result ->
                        // Get chunk index from metadata
                        val chunkIndex = result.attributes?.get("chunk_index")

                        FileSearchResult(
                            file_id = result.fileId,
                            filename = result.filename,
                            score = result.score,
                            content = result.content.firstOrNull()?.text ?: "",
                            annotations =
                                listOf(
                                    FileCitation(
                                        type = "file_citation",
                                        index = chunkIndex?.toString()?.toInt() ?: 0,
                                        file_id = result.fileId,
                                        filename = result.filename,
                                    ),
                                ),
                        )
                    },
            )

        return objectMapper.writeValueAsString(response)
    }

    /**
     * Executes an agentic search operation.
     *
     * @param arguments JSON string containing the search parameters
     * @param requestParams Optional metadata from the request
     * @return JSON string containing the search results
     */
    private suspend fun executeAgenticSearch(
        arguments: String,
        requestParams: ResponseCreateParams,
        openAIClient: OpenAIClient,
    ): String {
        try {
            val paramsObj = objectMapper.readValue(arguments, AgenticSearchParams::class.java)
            log.info("Starting agentic search for question: '${paramsObj.question}'")
            
            val functions = requestParams.tools().orElseThrow().filter { it.isWebSearch() }
            if (functions.isEmpty()) {
                log.error("No web search function available in request parameters")
                return objectMapper.writeValueAsString(AgenticSearchResponse(emptyList(), emptyList(), "Error: No web search function available"))
            }
            
            val function = functions.first().asWebSearch()
            
            // Debug log to help identify what's in the additional properties
            log.debug("WebSearch function additional properties: ${function._additionalProperties().keys}")
            
            // Try to extract vector_store_ids with improved debugging and fallbacks
            val typeReference =
                object : TypeReference<List<String>>() {}
            val vectorStoreIds = function._additionalProperties()["vector_store_ids"]?.convert(typeReference) ?: throw IllegalArgumentException("No vector store IDs found in properties")

            if (vectorStoreIds.isEmpty()) {
                log.error("No vector store IDs found in properties: ${function._additionalProperties()}")
                return objectMapper.writeValueAsString(
                    AgenticSearchResponse(
                        emptyList(), 
                        emptyList(), 
                        "Error: No vector store IDs provided. Available properties: ${function._additionalProperties().keys}",
                    ),
                )
            }
            
            log.info("Using vector store IDs: $vectorStoreIds")
            
            val maxResults = function._additionalProperties()["max_num_results"]?.toString()?.toInt() ?: 5
            val maxIterations = function._additionalProperties()["max_iterations"]?.toString()?.toInt() ?: 5
            val seedStrategy = function._additionalProperties()["seed_strategy"] as? String ?: "default"
            val alpha = function._additionalProperties()["alpha"]?.toString()?.toDouble() ?: 0.5
            val initialSeedMultiplier = function._additionalProperties()["initial_seed_multiplier"]?.toString()?.toInt() ?: 5
            val userFilter = function._additionalProperties()["filters"]?.convert(Filter::class.java)
            
            log.info("AgenticSearch parameters: maxResults=$maxResults, maxIterations=$maxIterations, seedStrategy=$seedStrategy")
            
            val response =
                agenticSearchService.run(
                    params = paramsObj,
                    vectorStoreIds = vectorStoreIds,
                    userFilter = userFilter,
                    maxResults = maxResults,
                    maxIterations = maxIterations,
                    seedName = seedStrategy,
                    openAIClient = openAIClient,
                    requestParams = requestParams,
                    alpha = alpha,
                    initialSeedMultiplier = initialSeedMultiplier,
                )
            
            log.info("AgenticSearch completed with ${response.data.size} results and ${response.search_iterations.size} iterations")
            return objectMapper.writeValueAsString(response)
        } catch (e: Exception) {
            log.error("Error executing agentic search: ${e.message}", e)
            return objectMapper.writeValueAsString(
                AgenticSearchResponse(
                    data = emptyList(),
                    search_iterations = emptyList(),
                    knowledge_acquired = "Error executing agentic search: ${e.message}",
                ),
            )
        }
    }

    /**
     * Helper method to extract IDs from various possible types
     */
    private fun extractIds(value: Any?): List<String> =
        when (value) {
            is List<*> -> value.filterIsInstance<String>()
            is String -> {
                try {
                    // Try to parse as JSON
                    objectMapper.readValue(value, object : TypeReference<List<String>>() {})
                } catch (e: Exception) {
                    listOf(value)
                }
            }
            is Map<*, *> -> {
                // If it's a map, look for values that might be IDs
                value.values.filterIsInstance<String>()
            }
            null -> emptyList()
            else -> listOf(value.toString())
        }

    /**
     * Loads the extended "think" tool definition.
     *
     * This function creates a `NativeToolDefinition` for the "think" tool with predefined parameters.
     * The tool is designed to append a thought to the log without obtaining new information or changing the database.
     *
     * @return A `NativeToolDefinition` instance representing the "think" tool.
     */
    private fun loadExtendedThinkTool(): NativeToolDefinition {
        val parameters =
            mutableMapOf(
                "type" to "object",
                "properties" to mapOf("thought" to mapOf("type" to "string", "description" to "A thought to think about")),
                "required" to listOf("thought"),
                "additionalProperties" to false,
            )

        return NativeToolDefinition(
            name = "think",
            description = "Use the tool to think about something. It will not obtain new information or change the database, but just append the thought to the log.",
            parameters = parameters,
        )
    }

    /**
     * Loads the file search tool definition.
     *
     * This function creates a `NativeToolDefinition` for the "file_search" tool with predefined parameters.
     * The tool is designed to search through vector stores for relevant file content.
     *
     * @return A `NativeToolDefinition` instance representing the "file_search" tool.
     */
    private fun loadFileSearchTool(): NativeToolDefinition {
        val parameters =
            mutableMapOf(
                "type" to "object",
                "properties" to
                    mapOf(
                        "query" to
                            mapOf(
                                "type" to "string",
                                "description" to "The search query",
                            ),
                    ),
                "required" to listOf("query"),
                "additionalProperties" to false,
            )

        return NativeToolDefinition(
            name = "file_search",
            description = "Search through vector stores for relevant file content based on a query. If the user's query sounds ambiguous, it's likely that the user is looking for information from a file.",
            parameters = parameters,
        )
    }

    /**
     * Loads the agentic search tool definition.
     *
     * This function creates a `NativeToolDefinition` for the "agentic_search" tool with predefined parameters.
     * The tool uses an LLM to guide multi-step vector searches to find the most relevant content.
     *
     * @return A `NativeToolDefinition` instance representing the "agentic_search" tool.
     */
    private fun loadAgenticSearchTool(): NativeToolDefinition {
        val parameters =
            mutableMapOf(
                "type" to "object",
                "properties" to
                    mapOf(
                        "question" to
                            mapOf(
                                "type" to "string",
                                "description" to "The question to find information for",
                            ),
                    ),
                "required" to listOf("question"),
                "additionalProperties" to false,
            )

        return NativeToolDefinition(
            name = "agentic_search",
            description =
                """
                Perform an AI-guided iterative search through vector stores that refines queries and filters until finding the best results for your questions.
                """.trimIndent(),
            parameters = parameters,
        )
    }
}
