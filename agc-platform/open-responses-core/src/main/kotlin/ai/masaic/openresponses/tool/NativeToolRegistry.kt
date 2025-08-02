package ai.masaic.openresponses.tool

import ai.masaic.openresponses.api.client.ResponseStore
import ai.masaic.openresponses.api.extensions.isImageContent
import ai.masaic.openresponses.api.model.*
import ai.masaic.openresponses.api.service.search.VectorStoreService
import ai.masaic.openresponses.tool.agentic.AgenticSearchService
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.credential.BearerTokenCredential
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.images.ImageGenerateParams
import com.openai.models.responses.ResponseOutputItem
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.codec.ServerSentEvent
import java.util.UUID
import kotlin.jvm.optionals.getOrElse
import kotlin.jvm.optionals.getOrNull

open class NativeToolRegistry(
    private val objectMapper: ObjectMapper,
    private val responseStore: ResponseStore,
) {
    private val log = LoggerFactory.getLogger(NativeToolRegistry::class.java)
    protected final val toolRepository = mutableMapOf<String, ToolDefinition>()

    @Autowired
    private lateinit var vectorStoreService: VectorStoreService
    
    @Autowired
    private lateinit var agenticSearchService: AgenticSearchService

    init {
        toolRepository["think"] = loadExtendedThinkTool()
        toolRepository["file_search"] = loadFileSearchTool()
        toolRepository["agentic_search"] = loadAgenticSearchTool()
        toolRepository["image_generation"] = loadImageGenerationTool()
    }

    companion object {
        val PROVIDER_BASE_URLS =
            mapOf(
                "openai" to "https://api.openai.com/v1",
                "togetherai" to "https://api.together.xyz/v1",
                "gemini" to "https://generativelanguage.googleapis.com/v1beta/openai",
                "google" to "https://generativelanguage.googleapis.com/v1beta/openai",
                "xai" to "https://api.x.ai/v1",
            )
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
    open suspend fun executeTool(
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
            "image_generation" -> executeImageGeneration(arguments, paramsAccessor, resolvedName, toolMetadata, context)
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
                        modelInfo = fileSearchToolConfig.modelInfo,
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
                        " " +
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
                    modelSettings = ModelInfo.modelSettings(agenticToolConfig.modelInfo),
                )

            log.info("AgenticSearch completed with ${response.data.size} results and ${response.search_iterations.size} iterations")
            eventEmitter.invoke(
                ServerSentEvent
                    .builder<String>()
                    .event("response.agentic_search.query_phase.completed")
                    .data(
                        " " +
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

    private fun loadImageGenerationTool(): NativeToolDefinition {
        val parameters =
            mutableMapOf<String, Any>(
                "type" to "object",
                "properties" to
                    mutableMapOf<String, Any>(
                        "prompt" to mutableMapOf("type" to "string", "description" to "A text description of the desired image(s). Max 32000 chars. In a multi-turn scenario this should contain previous prompt instructions."),
                        // TODO after image editing is implemented
                        // "is_edit" to mutableMapOf("type" to "boolean", "description" to "Type of image generation: 'false' for new image, 'true' for editing an existing image. Choose 'true' if there is already an <image> in previous context and user's intent is to edit it. ", "default" to "false"),
                    ),
                "required" to listOf("prompt"),
            )
        return NativeToolDefinition(
            name = "image_generation",
            description = "Generates images from text prompts.",
            parameters = parameters,
        )
    }

    private suspend fun executeImageGeneration(
        arguments: String,
        paramsAccessor: ToolParamsAccessor,
        resolvedToolName: String,
        toolMetadata: Map<String, Any>,
        context: UnifiedToolContext,
    ): String {
        try {
            log.info("Executing image_generation tool with LLM arguments: $arguments")
            val llmArgs =
                try {
                    objectMapper.readValue(arguments, ImageGenerationToolArguments::class.java)
                } catch (e: Exception) {
                    log.error("Error parsing LLM arguments for image_generation: ${e.message}", e)
                    return objectMapper.writeValueAsString(mapOf("error" to "Invalid LLM arguments: ${e.message}"))
                }

            val imageData =
                if (paramsAccessor is ResponseParamsAdapter && llmArgs.isEdit && paramsAccessor.params.previousResponseId().isPresent) {
                    val contentNode =
                        responseStore
                            .getResponse(paramsAccessor.params.previousResponseId().get())
                            ?.output()
                            ?.filterIsInstance<ResponseOutputItem.ImageGenerationCall>()
                            ?.first()
                    if (contentNode?.result()?.isPresent == true) {
                        if (isImageContent(contentNode.result().get()).isImage) contentNode.result().get() else null
                    } else {
                        null
                    }
                } else if (paramsAccessor is ChatCompletionParamsAdapter && llmArgs.isEdit) {
                    val imageData =
                        paramsAccessor.params
                            .messages()
                            .filterIsInstance<ChatCompletionAssistantMessageParam>()
                            .last()
                            .content()
                            .getOrElse { null }
                    if (imageData?.isText() == true) {
                        if (isImageContent(imageData.asText()).isImage) imageData.asText() else null
                    } else if (imageData?.isArrayOfContentParts() == true) {
                        imageData.asArrayOfContentParts().firstOrNull()?.text()?.getOrNull()?.let { content ->
                            if (isImageContent(content.text()).isImage) content.text() else null
                        }
                    } else {
                        null
                    }
                } else {
                    null
                }

            val rawToolConfig = paramsAccessor.getSpecificToolConfig("image_generation", ImageGenerationTool::class.java) ?: ImageGenerationTool()
            val config = getApiConfig(rawToolConfig)
            val client =
                OpenAIOkHttpClient
                    .builder()
                    .baseUrl(config.third)
                    .credential(BearerTokenCredential.create(config.second))
                    .build()

            val output =
                if (!llmArgs.isEdit || imageData?.isBlank() == true) {
                    val builder =
                        ImageGenerateParams
                            .builder()
                            .model(config.first)
                            .prompt(llmArgs.prompt)
                            .n(rawToolConfig.n?.toLong() ?: 1)

                    if (config.third.contains("api.openai.com")) {
                        builder.outputFormat(ImageGenerateParams.OutputFormat.of((rawToolConfig.outputFormat?.lowercase() ?: "png")))
                    } else {
                        builder.responseFormat(ImageGenerateParams.ResponseFormat.of((rawToolConfig.responseFormat?.lowercase() ?: "b64_json")))
                    }
                    client.images().generate(builder.build())
                } else {
                    // TODO Add support for image editing
                    val builder =
                        ImageGenerateParams
                            .builder()
                            .model(config.first)
                            .prompt(llmArgs.prompt)
                            .n(rawToolConfig.n?.toLong() ?: 1)

                    if (config.third.contains("api.openai.com")) {
                        builder.outputFormat(ImageGenerateParams.OutputFormat.of((rawToolConfig.outputFormat?.lowercase() ?: "png")))
                    } else {
                        builder.responseFormat(ImageGenerateParams.ResponseFormat.of((rawToolConfig.responseFormat?.lowercase() ?: "b64_json")))
                    }
                    client.images().generate(builder.build())
                }

            // Simulate a response based on prompt content for testing
            return if (output.data().isPresent && output.data().get().size > 1) {
                objectMapper.writeValueAsString(
                    mapOf(
                        "data" to
                            objectMapper.writeValueAsString(
                                output.data().get().mapNotNull { it.b64Json().getOrNull() }.mapIndexed { index, image ->
                                    mapOf(
                                        "data" to image,
                                        "image_id" to UUID.randomUUID().toString(),
                                    )
                                },
                            ),
                    ),
                )
            } else if (output.data().isPresent && output.data().get().size == 1) {
                objectMapper.writeValueAsString(
                    mapOf(
                        "data" to
                            output
                                .data()
                                .get()[0]
                                .b64Json()
                                .getOrNull(),
                        "image_id" to UUID.randomUUID().toString(),
                    ),
                )
            } else {
                objectMapper.writeValueAsString(
                    mapOf(
                        "data" to "",
                        "image_id" to UUID.randomUUID().toString(),
                    ),
                )
            }
        } catch (e: Exception) {
            log.error("Error executing image_generation tool: ${e.message}", e)
            return objectMapper.writeValueAsString(mapOf("data" to "", "image_id" to UUID.randomUUID().toString()))
        }
    }

    private fun getApiConfig(tool: ImageGenerationTool): Triple<String, String, String> {
        var imageModel = tool.model
        val apiKey = tool.modelProviderKey ?: System.getenv("OPEN_RESPONSES_IMAGE_GENERATION_API_KEY")
        val url =
            if (tool.model.contains("@") == true) {
                val parts = tool.model.split("@", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    imageModel = parts[1]
                    // Check if the first part is a URL
                    if (parts[0].startsWith("http://") || parts[0].startsWith("https://")) {
                        parts[0]
                    } else {
                        // Check if it's a known provider name
                        val providerUrl = PROVIDER_BASE_URLS[parts[0].lowercase()]
                        providerUrl ?: throw IllegalArgumentException("Model provider not recognized: ${parts[0]}")
                    }
                } else {
                    throw IllegalArgumentException("Model provider not recognized: ${parts[0]}")
                }
            } else {
                System.getenv("OPEN_RESPONSES_IMAGE_GENERATION_BASE_URL")
                    ?: System.getenv("OPENAI_BASE_URL")
                    ?: throw IllegalArgumentException("Image model provider not recognized: ${tool.model}")
            }

        return Triple(imageModel, apiKey, url)
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
