package ai.masaic.openresponses.tool

import ai.masaic.openresponses.api.model.AgenticSeachTool
import ai.masaic.openresponses.api.model.FileSearchTool
import ai.masaic.openresponses.api.model.Filter
import ai.masaic.openresponses.api.model.VectorStoreSearchRequest
import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import ai.masaic.openresponses.api.service.search.VectorStoreService
import ai.masaic.openresponses.tool.agentic.AgenticSearchService
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import java.util.UUID

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

    /**
     * Executes a native tool using unified context and parameters.
     *
     * @param resolvedName The resolved (non-alias) name of the tool
     * @param arguments JSON string arguments
     * @param paramsAccessor Accessor for unified tool configuration and basic params
     * @param client OpenAIClient instance
     * @param eventEmitter Server-Sent Event emitter
     * @param toolMetadata Additional metadata
     * @param context UnifiedToolContext
     * @return Result string or null if tool not found/fails
     */
    suspend fun executeTool(
        resolvedName: String,
        arguments: String,
        paramsAccessor: ToolParamsAccessor,
        client: OpenAIClient,
        eventEmitter: (ServerSentEvent<String>) -> Unit,
        toolMetadata: Map<String, Any>,
        context: UnifiedToolContext,
    ): String? {
        toolRepository[resolvedName] ?: return null
        val originalName = toolMetadata["originalName"] as? String ?: resolvedName
        log.debug("Executing native tool '$originalName' (resolved to '$resolvedName') with arguments: $arguments")

        return when (resolvedName) {
            "think" -> "Your thought has been logged."
            "file_search" -> executeFileSearch(arguments, paramsAccessor, resolvedName, toolMetadata, context)
            "agentic_search" -> executeAgenticSearch(arguments, paramsAccessor, client, resolvedName, eventEmitter, toolMetadata, context)
            else -> {
                log.warn("Attempted to execute unknown native tool: $resolvedName")
                null
            }
        }
    }

    /**
     * Executes a file search operation using the vector store service.
     *
     * @param arguments JSON string containing the search parameters
     * @param paramsAccessor Accessor for unified tool configuration
     * @param resolvedToolName The resolved name of the tool being executed
     * @param toolMetadata Additional metadata
     * @param context UnifiedToolContext
     * @return JSON string containing the search results
     */
    private suspend fun executeFileSearch(
        arguments: String,
        paramsAccessor: ToolParamsAccessor,
        resolvedToolName: String,
        toolMetadata: Map<String, Any>,
        context: UnifiedToolContext,
    ): String {
        val params = objectMapper.readValue(arguments, FileSearchParams::class.java)

        val fileSearchToolConfig = paramsAccessor.getSpecificToolConfig(resolvedToolName, FileSearchTool::class.java)
        
        if (fileSearchToolConfig == null) {
            log.error("Could not find or parse FileSearchTool configuration in parameters for tool '$resolvedToolName'")
            return objectMapper.writeValueAsString(FileSearchResponse(emptyList()))
        }

        val vectorStoreIds = fileSearchToolConfig.vectorStoreIds ?: emptyList()
        val filters = fileSearchToolConfig.filters?.let { objectMapper.convertValue(it, Filter::class.java) }
        val maxResults = fileSearchToolConfig.maxNumResults

        if (vectorStoreIds.isEmpty()) {
            log.error("No vector store IDs provided for file_search tool '$resolvedToolName'")
            return objectMapper.writeValueAsString(FileSearchResponse(emptyList()))
        }

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

        val sortedResults =
            allResults
                .sortedByDescending { it.score }
                .take(maxResults)

        val response =
            FileSearchResponse(
                data =
                    sortedResults.map { result ->
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
     * @param paramsAccessor Accessor for unified tool configuration and basic params
     * @param client OpenAIClient instance
     * @param resolvedToolName The resolved name of the tool being executed
     * @param eventEmitter Server-Sent Event emitter
     * @param toolMetadata Additional metadata
     * @param context UnifiedToolContext
     * @return JSON string containing the search results
     */
    private suspend fun executeAgenticSearch(
        arguments: String,
        paramsAccessor: ToolParamsAccessor,
        client: OpenAIClient,
        resolvedToolName: String,
        eventEmitter: (ServerSentEvent<String>) -> Unit,
        toolMetadata: Map<String, Any>,
        context: UnifiedToolContext,
    ): String {
        try {
            val paramsObj = objectMapper.readValue(arguments, AgenticSearchParams::class.java)
            log.info("Starting agentic search for question: '${paramsObj.query}'")

            eventEmitter.invoke(
                ServerSentEvent
                    .builder<String>()
                    .event("response.agentic_search.query_phase.started")
                    .data(
                        objectMapper.writeValueAsString(
                            mapOf<String, Any>(
                                "item_id" to (toolMetadata["toolId"] as? String ?: UUID.randomUUID().toString()),
                                "output_index" to (toolMetadata["eventIndex"]?.toString()?.toInt() ?: 0),
                                "type" to "response.agentic_search.query_phase.started",
                                "query" to paramsObj.query,
                            ),
                        ),
                    ).build(),
            )

            val agenticToolConfig = paramsAccessor.getSpecificToolConfig(resolvedToolName, AgenticSeachTool::class.java)

            if (agenticToolConfig == null) {
                log.error("Could not find or parse AgenticSeachTool configuration in parameters for tool '$resolvedToolName'")
                return objectMapper.writeValueAsString(AgenticSearchResponse(emptyList(), emptyList(), "Error: No agentic_search configuration available"))
            }

            val vectorStoreIds = agenticToolConfig.vectorStoreIds ?: emptyList()
            val userFilter = agenticToolConfig.filters?.convert(Filter::class.java, objectMapper)
            val maxResults = agenticToolConfig.maxNumResults
            val maxIterations = agenticToolConfig.maxIterations
            val seedStrategy = "default"
            val alpha = 0.5
            val initialSeedMultiplier = 3
            
            if (vectorStoreIds.isEmpty()) {
                log.error("No vector store IDs provided for agentic_search tool '$resolvedToolName'")
                return objectMapper.writeValueAsString(AgenticSearchResponse(emptyList(), emptyList(), "Error: No vector store IDs provided"))
            }

            log.info("Using vector store IDs: $vectorStoreIds")
            log.info("AgenticSearch parameters: maxResults=$maxResults, maxIterations=$maxIterations, seedStrategy=$seedStrategy")
            
            val response = 
                agenticSearchService.run(
                    params = paramsObj,
                    vectorStoreIds = vectorStoreIds,
                    userFilter = userFilter,
                    maxResults = maxResults,
                    maxIterations = maxIterations,
                    seedName = seedStrategy,
                    paramsAccessor = paramsAccessor,
                    openAIClient = client,
                    alpha = alpha,
                    initialSeedMultiplier = initialSeedMultiplier,
                    eventEmitter = eventEmitter,
                    toolMetadata = toolMetadata,
                )

            log.info("AgenticSearch completed with ${response.data.size} results and ${response.search_iterations.size} iterations")
            eventEmitter.invoke(
                ServerSentEvent
                    .builder<String>()
                    .event("response.agentic_search.query_phase.completed")
                    .data(
                        objectMapper.writeValueAsString(
                            mapOf<String, Any>(
                                "item_id" to (toolMetadata["toolId"] as? String ?: UUID.randomUUID().toString()),
                                "output_index" to (toolMetadata["eventIndex"]?.toString()?.toInt() ?: 0),
                                "type" to "response.agentic_search.query_phase.completed",
                                "conclusion" to (response.knowledge_acquired ?: ""),
                            ),
                        ),
                    ).build(),
            )
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
                        "query" to
                            mapOf(
                                "type" to "string",
                                "description" to "The query to find information for",
                            ),
                    ),
                "required" to listOf("query"),
                "additionalProperties" to false,
            )

        return NativeToolDefinition(
            name = "agentic_search",
            description =
                """
                Perform an AI-guided iterative search through vector stores that refines queries and filters until finding the best results for your queries.
                """.trimIndent(),
            parameters = parameters,
        )
    }

    private fun Any?.convert(
        klass: Class<Filter>,
        objectMapper: ObjectMapper,
    ): Filter? =
        this?.let {
            try {
                objectMapper.convertValue(it, klass)
            } catch (e: Exception) {
                log.error("Failed to convert filter object: $it", e)
                null
            }
        }
}
