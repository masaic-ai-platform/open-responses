package ai.masaic.openresponses.tool

import ai.masaic.openresponses.api.model.Filter
import ai.masaic.openresponses.api.model.VectorStoreSearchRequest
import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import ai.masaic.openresponses.api.service.search.VectorStoreService
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.openai.models.responses.ResponseCreateParams
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import kotlin.jvm.optionals.getOrDefault
import kotlin.jvm.optionals.getOrNull

@Component
class NativeToolRegistry(
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(NativeToolRegistry::class.java)
    private val toolRepository = mutableMapOf<String, ToolDefinition>()

    @Autowired
    private lateinit var vectorStoreService: VectorStoreService

    @Autowired
    private lateinit var client: OpenAIClient

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
    ): String? {
        val tool = toolRepository[name] ?: return null
        log.debug("Executing tool $name with arguments: $arguments")

        return when (name) {
            "think" -> "Your thought has been logged."
            "file_search" -> executeFileSearch(arguments, params)
            "agentic_search" -> executeAgenticSearch(arguments, params)
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
                        val chunkIndex = result.attributes?.get("chunk_index") as? Long ?: 0

                        FileSearchResult(
                            file_id = result.fileId,
                            filename = result.filename,
                            score = result.score,
                            content = result.content.firstOrNull()?.text ?: "",
                            annotations =
                                listOf(
                                    FileCitation(
                                        type = "file_citation",
                                        index = chunkIndex.toInt(),
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
     * Always apply the user security filter with LLM-generated filters
     */
    private fun createSearchFilter(
        userSecurityFilter: Filter?,
        currentFilters: Map<String, Any>
    ): Filter? {
        return if (userSecurityFilter != null) {
            if (currentFilters.isEmpty()) {
                // Just use user security filter
                userSecurityFilter
            } else {
                // Create compound filter combining user security filters AND LLM filters
                val llmFilter = createFilterFromMap(currentFilters)
                if (llmFilter != null) {
                    ai.masaic.openresponses.api.model.CompoundFilter("and", listOf(userSecurityFilter, llmFilter))
                } else {
                    userSecurityFilter
                }
            }
        } else if (currentFilters.isNotEmpty()) {
            // No user filter, just use LLM filter
            createFilterFromMap(currentFilters)
        } else {
            null
        }
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
    ): String {
        val params = objectMapper.readValue(arguments, AgenticSearchParams::class.java)

        // Get vector store IDs and filters from the function parameters
        val function =
            requestParams
                .tools()
                .getOrNull()
                ?.filter { it.isWebSearch() }
                ?.map {
                    it.asWebSearch()
                } ?: return objectMapper.writeValueAsString(AgenticSearchResponse(emptyList(), emptyList()))

        val vectorStoreIds = function.first()._additionalProperties()["vector_store_ids"]
            ?: return objectMapper.writeValueAsString(AgenticSearchResponse(emptyList(), emptyList()))
        
        // Extract user-provided security filters - these must be preserved
        val userSecurityFilter = function.first()._additionalProperties()["filters"]?.convert(Filter::class.java)
        
        val maxResults =
            function
                .first()
                ._additionalProperties()["max_num_results"].toString()
                .toInt()

        // Initialize our search state
        val searchBuffer = mutableListOf<VectorStoreSearchResult>()
        val searchIterations = mutableListOf<AgenticSearchIteration>()
        var currentQuery = params.question
        var shouldTerminate = false
        var iterationCount = 0
        
        // Initial filters - start with empty map, the LLM will generate filters
        var currentFilters = emptyMap<String, Any>()
        
        // Add the initial query to iterations
        searchIterations.add(AgenticSearchIteration(currentQuery, false, currentFilters))
        
        // Pre-populate search buffer with initial results based on the original question
        log.info("Pre-populating search buffer with initial query: $currentQuery")
        val initialSearchFilter = createSearchFilter(userSecurityFilter, currentFilters)
        val typeReference = object : TypeReference<List<String>>() {}
        
        // Perform initial search across all vector stores
        for (vectorStoreId in vectorStoreIds.convert(typeReference)!!) {
            try {
                val searchRequest =
                    VectorStoreSearchRequest(
                        query = currentQuery,
                        maxNumResults = maxResults,
                        filters = initialSearchFilter
                    )
                val results = vectorStoreService.searchVectorStore(vectorStoreId, searchRequest)
                
                // Add initial results to buffer
                val initialResults = results.data
                    .sortedByDescending { it.score }
                    .take(maxResults)
                
                log.info("Found ${initialResults.size} initial results from vectorStoreId: $vectorStoreId")
                searchBuffer.addAll(initialResults)
            } catch (e: Exception) {
                log.error("Error searching vector store $vectorStoreId for initial results", e)
            }
        }
        
        // Limit initial buffer size if needed
        if (searchBuffer.size > maxResults) {
            searchBuffer.sortByDescending { it.score }
            searchBuffer.subList(maxResults, searchBuffer.size).clear()
        }
        
        // If we already have good results, check if LLM wants to refine further
        if (searchBuffer.isNotEmpty()) {
            log.info("Initial search found ${searchBuffer.size} results, asking LLM for next steps")
            
            // Call LLM to decide next action based on initial results
            val initialDecision = callLlmForDecision(
                originalQuestion = params.question,
                searchBuffer = searchBuffer,
                previousIterations = searchIterations,
                isInitialResults = true
            )
            
            // Check if LLM wants to terminate immediately with initial results
            if (initialDecision.startsWith("TERMINATE")) {
                log.info("LLM decided to terminate with initial results")
                searchIterations.add(AgenticSearchIteration(currentQuery, true, currentFilters))
                
                // Return initial results
                val response = AgenticSearchResponse(
                    data = searchBuffer.map { result ->
                        val chunkIndex = result.attributes?.get("chunk_index") as? Long ?: 0
                        AgenticSearchResult(
                            file_id = result.fileId,
                            filename = result.filename,
                            score = result.score,
                            content = result.content.firstOrNull()?.text ?: "",
                            annotations = listOf(
                                FileCitation(
                                    type = "file_citation",
                                    index = chunkIndex.toInt(),
                                    file_id = result.fileId,
                                    filename = result.filename,
                                )
                            )
                        )
                    },
                    search_iterations = searchIterations
                )
                return objectMapper.writeValueAsString(response)
            }
            
            // If LLM suggested a query refinement, use it for the first iteration
            if (initialDecision.startsWith("NEXT_QUERY:")) {
                val decision = parseLlmDecision(initialDecision)
                currentQuery = decision.query
                
                if (decision.filters != null) {
                    currentFilters = decision.filters
                }
                
                // Record this as the first iteration
                searchIterations.add(AgenticSearchIteration(currentQuery, false, currentFilters))
            }
        }

        // Main agentic search loop
        while (!shouldTerminate && iterationCount < params.max_iterations) {
            iterationCount++
            
            log.info("Iteration $iterationCount: Searching with query: $currentQuery and filters: $currentFilters")
            
            // Check for repeated queries with same filters - new code
            if (iterationCount > 1 && searchIterations.count { it.query == currentQuery && it.applied_filters == currentFilters } > 0) {
                log.warn("Detected repeated query with same filters: $currentQuery with $currentFilters. Forcing termination to prevent redundant search.")
                shouldTerminate = true
                searchIterations.add(AgenticSearchIteration(currentQuery, true, currentFilters, "Terminated due to exact query repetition. Multiple different queries are still encouraged."))
                break
            }
            
            // Apply search filter
            val searchFilter = createSearchFilter(userSecurityFilter, currentFilters)
            
            // Perform vector search with current query and filters
            val allResults = mutableListOf<VectorStoreSearchResult>()
            for (vectorStoreId in vectorStoreIds.convert(typeReference)!!) {
                try {
                    val searchRequest =
                        VectorStoreSearchRequest(
                            query = currentQuery,
                            maxNumResults = maxResults,
                            filters = searchFilter
                        )
                    val results = vectorStoreService.searchVectorStore(vectorStoreId, searchRequest)
                    allResults.addAll(results.data)
                } catch (e: Exception) {
                    log.error("Error searching vector store $vectorStoreId", e)
                }
            }

            // Sort results by score and add to buffer
            val sortedResults =
                allResults
                    .sortedByDescending { it.score }
                    .take(maxResults)

            // Add unique results to buffer
            sortedResults.forEach { result ->
                if (!searchBuffer.any { it.fileId == result.fileId && it.content == result.content }) {
                    searchBuffer.add(result)
                }
            }

            // Limit buffer size to keep token count manageable
            if (searchBuffer.size > maxResults) {
                searchBuffer.sortByDescending { it.score }
                while (searchBuffer.size > maxResults) {
                    searchBuffer.removeAt(searchBuffer.size - 1)
                }
            }

            // Call LLM to decide next action
            val llmDecision = callLlmForDecision(
                originalQuestion = params.question, 
                searchBuffer = searchBuffer, 
                previousIterations = searchIterations,
                filterSuggestions = if (iterationCount == 1) currentFilters else null
            )

            // Parse the LLM decision
            if (llmDecision.startsWith("TERMINATE")) {
                shouldTerminate = true
                searchIterations.add(AgenticSearchIteration(currentQuery, true, currentFilters))
            } else if (llmDecision.startsWith("NEXT_QUERY:")) {
                // Extract query and filter information from LLM response
                val decision = parseLlmDecision(llmDecision)
                currentQuery = decision.query
                
                // Update filters from LLM suggestion
                if (decision.filters != null) {
                    currentFilters = decision.filters
                }
                
                searchIterations.add(AgenticSearchIteration(currentQuery, false, currentFilters))
            } else {
                // Default to termination if LLM response is unexpected
                log.warn("Unexpected LLM decision format: $llmDecision. Defaulting to TERMINATE.")
                shouldTerminate = true
                searchIterations.add(AgenticSearchIteration(currentQuery, true, currentFilters))
            }
        }

        // If we reached max iterations without terminating, add a final iteration
        if (iterationCount >= params.max_iterations && !shouldTerminate) {
            searchIterations.add(AgenticSearchIteration(currentQuery, true, currentFilters))
        }

        // Convert results to response format
        val response =
            AgenticSearchResponse(
                data =
                    searchBuffer.map { result ->
                        // Get chunk index from metadata
                        val chunkIndex = result.attributes?.get("chunk_index") as? Long ?: 0

                        AgenticSearchResult(
                            file_id = result.fileId,
                            filename = result.filename,
                            score = result.score,
                            content = result.content.firstOrNull()?.text ?: "",
                            annotations =
                                listOf(
                                    FileCitation(
                                        type = "file_citation",
                                        index = chunkIndex.toInt(),
                                        file_id = result.fileId,
                                        filename = result.filename,
                                    ),
                                ),
                        )
                    },
                search_iterations = searchIterations
            )

        return objectMapper.writeValueAsString(response)
    }
    
    /**
     * Parse the LLM decision string into query and filters.
     * 
     * @param llmDecision The decision string from the LLM
     * @return A pair containing the next query and optional filters
     */
    private fun parseLlmDecision(llmDecision: String): LlmDecision {
        val decisionString = llmDecision.trim()
        
        if (decisionString.startsWith("TERMINATE")) {
            return LlmDecision("", null)
        }
        
        if (decisionString.startsWith("NEXT_QUERY:")) {
            val content = decisionString.substringAfter("NEXT_QUERY:").trim()
            
            // Check if there are filters specified in JSON format
            val filterStart = content.indexOf("{")
            val filterEnd = content.lastIndexOf("}")
            
            if (filterStart > 0 && filterEnd > filterStart) {
                try {
                    // Extract query and filter parts
                    var query = content.substring(0, filterStart).trim()
                    val filterJson = content.substring(filterStart, filterEnd + 1)
                    
                    // Handle any content after the JSON filters (could include memory updates)
                    if (filterEnd + 1 < content.length) {
                        val afterFilterContent = content.substring(filterEnd + 1).trim()
                        if (afterFilterContent.isNotEmpty()) {
                            // Append the remaining content to the query
                            query = "$query $afterFilterContent"
                        }
                    }
                    
                    // Use Jackson to parse the JSON directly to Map<String, Any>
                    val filters = objectMapper.readValue(filterJson, 
                        object : TypeReference<Map<String, Any>>() {})
                    
                    return LlmDecision(query, filters)
                } catch (e: Exception) {
                    log.warn("Failed to parse filters from LLM decision: $content", e)
                }
            }
            
            // If no valid filters were found or parsing failed, return just the query
            return LlmDecision(content, null)
        }
        
        return LlmDecision("", null)
    }
    
    /**
     * Data class to hold LLM decision components
     */
    private data class LlmDecision(
        val query: String,
        val filters: Map<String, Any>?
    )
    
    private fun createFilterFromMap(filterMap: Map<String, Any>): Filter? {
        if (filterMap.isEmpty()) {
            return null
        }
        
        // For simple filter maps, convert directly to comparison filters
        val filters = mutableListOf<Filter>()
        
        filterMap.forEach { entry ->
            val key = entry.key
            val value = entry.value
            
            when {
                value is Map<*, *> -> {
                    // Handle some special map formats
                    if (value.containsKey("type") && value.containsKey("value")) {
                        val typeStr = value["type"] as? String ?: "eq"
                        val filterValue = value["value"]
                        if (filterValue != null) {
                            filters.add(ai.masaic.openresponses.api.model.ComparisonFilter(key, typeStr, filterValue))
                        }
                    } else {
                        // Convert nested map to a set of AND conditions
                        @Suppress("UNCHECKED_CAST")
                        val nestedFilters = createNestedFilters(key, value as Map<String, Any>)
                        if (nestedFilters != null) {
                            filters.add(nestedFilters)
                        }
                    }
                }
                value is List<*> -> {
                    // Handle list values as OR conditions
                    val orFilters = value.mapNotNull { listItem -> 
                        if (listItem != null) ai.masaic.openresponses.api.model.ComparisonFilter(key, "eq", listItem) else null 
                    }
                    if (orFilters.isNotEmpty()) {
                        filters.add(ai.masaic.openresponses.api.model.CompoundFilter("or", orFilters))
                    }
                }
                else -> {
                    // Handle simple key-value pairs
                    filters.add(ai.masaic.openresponses.api.model.ComparisonFilter(key, "eq", value))
                }
            }
        }
        
        return if (filters.size == 1) {
            filters.first()
        } else if (filters.size > 1) {
            ai.masaic.openresponses.api.model.CompoundFilter("and", filters)
        } else {
            null
        }
    }
    
    /**
     * Creates nested filters from a nested map structure
     */
    private fun createNestedFilters(parentKey: String, nestedMap: Map<String, Any>): Filter? {
        val filters = mutableListOf<Filter>()
        
        nestedMap.forEach { entry ->
            val key = entry.key
            val value = entry.value
            val fullKey = "$parentKey.$key"
            
            when (value) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val nestedFilter = createNestedFilters(fullKey, value as Map<String, Any>)
                    if (nestedFilter != null) {
                        filters.add(nestedFilter)
                    }
                }
                is List<*> -> {
                    val orFilters = value.mapNotNull { listItem -> 
                        if (listItem != null) ai.masaic.openresponses.api.model.ComparisonFilter(fullKey, "eq", listItem) else null 
                    }
                    if (orFilters.isNotEmpty()) {
                        filters.add(ai.masaic.openresponses.api.model.CompoundFilter("or", orFilters))
                    }
                }
                else -> {
                    filters.add(ai.masaic.openresponses.api.model.ComparisonFilter(fullKey, "eq", value))
                }
            }
        }
        
        return if (filters.size == 1) {
            filters.first()
        } else if (filters.size > 1) {
            ai.masaic.openresponses.api.model.CompoundFilter("and", filters)
        } else {
            null
        }
    }

    /**
     * Calls the LLM to make a decision on the next search action.
     *
     * @param originalQuestion The original user question
     * @param searchBuffer Current search results buffer
     * @param previousIterations Previous search iterations
     * @param filterSuggestions Initial filter suggestions (only used in first iteration)
     * @param isInitialResults Whether these are the initial results from the pre-population step
     * @return LLM decision string (TERMINATE or NEXT_QUERY:[query])
     */
    private suspend fun callLlmForDecision(
        originalQuestion: String,
        searchBuffer: List<VectorStoreSearchResult>,
        previousIterations: List<AgenticSearchIteration>,
        filterSuggestions: Map<String, Any>? = null,
        isInitialResults: Boolean = false
    ): String {
        // Prepare prompt for LLM
        val promptBuilder = StringBuilder()
        promptBuilder.append("Original question: $originalQuestion\n\n")

        // Add buffer contents with attributes for filtering
        promptBuilder.append("Current search results:\n")
        
        // Extract available attributes from results to help LLM understand filterable fields
        val availableAttributes = mutableSetOf<String>()
        searchBuffer.forEach { result ->
            result.attributes?.keys?.forEach { key ->
                availableAttributes.add(key)
            }
        }
        
        // Add search results with content and attributes
        searchBuffer.forEachIndexed { index, result ->
            promptBuilder.append("${index + 1}. ${result.filename}: ${result.content.firstOrNull()?.text}\n")
            // Include attributes that can be used for filtering
            if (result.attributes != null && result.attributes.isNotEmpty()) {
                promptBuilder.append("   Attributes: ")
                result.attributes.entries.joinTo(promptBuilder, ", ") { "${it.key}=${it.value}" }
                promptBuilder.append("\n")
            }
        }
        
        // List all available attributes for filtering
        if (availableAttributes.isNotEmpty()) {
            promptBuilder.append("\nAvailable attributes for filtering: ")
            availableAttributes.joinTo(promptBuilder, ", ")
            promptBuilder.append("\n")
        }

        // Add previous queries and filters
        promptBuilder.append("\nPrevious search iterations:\n")
        previousIterations.forEachIndexed { index, iteration ->
            val filterInfo = if (iteration.applied_filters != null && iteration.applied_filters.isNotEmpty()) {
                " with filters: ${iteration.applied_filters}"
            } else {
                ""
            }
            
            // Check if this query was used before
            val isDuplicate = previousIterations.take(index).any { 
                it.query == iteration.query && it.applied_filters == iteration.applied_filters 
            }
            
            val duplicateWarning = if (isDuplicate) " ⚠️ DUPLICATE" else ""
            val resultCount = if (index < previousIterations.size - 1) {
                " → yielded ${if (searchBuffer.isNotEmpty()) "some" else "no"} results"
            } else {
                ""
            }
            
            promptBuilder.append("${index + 1}. Query: \"${iteration.query}\"$filterInfo$resultCount$duplicateWarning\n")
        }
        
        // If there are filter suggestions and this is the first iteration, add them to the prompt
        filterSuggestions?.let {
            if (it.isNotEmpty()) {
                promptBuilder.append("\nFilter suggestions (consider using these):\n")
                promptBuilder.append("$filterSuggestions\n")
            }
        }

        // Add instruction
        promptBuilder.append("\nBased on the original question and current search results, decide whether to:")
        promptBuilder.append("\n1. TERMINATE - if you have sufficient information to answer the question")
        promptBuilder.append("\n2. NEXT_QUERY:[your next search query] {optional filter JSON} - if you need more specific information")
        
        // Additional context if these are initial results
        if (isInitialResults) {
            promptBuilder.append("\n\nThese are the initial results based on the original question. ")
            promptBuilder.append("If you think they contain sufficient information, respond with TERMINATE. ")
            promptBuilder.append("Otherwise, suggest a more specific query to refine the search.")
        }
        
        // Add filter examples and guidance, specifically mentioning the available attributes
        promptBuilder.append("\n\nYou should apply filters based on the document attributes visible in the search results.")
        promptBuilder.append("\nFilter examples:")
        
        // Generate examples using actual attributes where possible
        if (availableAttributes.isNotEmpty()) {
            val exampleAttrs = availableAttributes.take(3).toList()
            
            // Prioritize chunk_index if available
            if (availableAttributes.contains("chunk_index")) {
                promptBuilder.append("\n- NEXT_QUERY:search in first chunk {\"chunk_index\": 1}")
                promptBuilder.append("\n- NEXT_QUERY:search in specific chunks {\"chunk_index\": [2, 3, 4]}")
                promptBuilder.append("\n- NEXT_QUERY:search with multiple filters {\"filename\": \"document.pdf\", \"chunk_index\": 5}")
            } else {
                for (i in exampleAttrs.indices) {
                    val attr = exampleAttrs[i]
                    when (i) {
                        0 -> promptBuilder.append("\n- NEXT_QUERY:more specific query {\"$attr\": \"specific_value\"}")
                        1 -> promptBuilder.append("\n- NEXT_QUERY:another query {\"${exampleAttrs[0]}\": \"value1\", \"$attr\": \"value2\"}")
                        2 -> promptBuilder.append("\n- NEXT_QUERY:third query {\"$attr\": [\"value1\", \"value2\"]}")
                    }
                }
            }
        } else {
            // Default examples if no attributes found
            promptBuilder.append("\n- NEXT_QUERY:kotlin file structure {\"language\": \"kotlin\"}")
            promptBuilder.append("\n- NEXT_QUERY:openai authentication {\"document_type\": \"technical\", \"department\": \"engineering\"}")
            promptBuilder.append("\n- NEXT_QUERY:financial report 2023 {\"category\": [\"finance\", \"reporting\"], \"created_after\": \"2023-01-01\"}")
            promptBuilder.append("\n- NEXT_QUERY:sequential document exploration {\"chunk_index\": 1} ##MEMORY## Found introduction in chunk 1")
        }
        
        promptBuilder.append("\n\nRespond with EXACTLY ONE of these formats. Nothing else.")
        promptBuilder.append("\nYou can refine the query based on what you've learned, and add filters to focus on specific document attributes. Do not repeat the same query with same filter. They will most likely return the same results.\n")

        // Add guidance for chunk-by-chunk searching and memory interface
        promptBuilder.append("\n\n## Search Strategy and Memory")
        promptBuilder.append("\nSome questions require methodical chunk-by-chunk exploration. If you see 'chunk_index' in the attributes, you can:")
        promptBuilder.append("\n1. Search sequentially through chunks using filters like {\"chunk_index\": 1}, then {\"chunk_index\": 2}, etc.")
        promptBuilder.append("\n2. Use filters to target specific document sections with {\"chunk_index\": [3, 4, 5]} for multiple chunks.")
        promptBuilder.append("\n3. If you think you've a chunk has partial information then you can add filter of previous chunk indexes to the query and same for the next chunks.")

        // Add memory interface
        promptBuilder.append("\n\n## Your Knowledge Memory:")
        
        // Generate memory summary from previous iterations
        val knowledgeGained = buildKnowledgeMemory(previousIterations, searchBuffer)
        if (knowledgeGained.isNotEmpty()) {
            promptBuilder.append("\n$knowledgeGained")
        } else {
            promptBuilder.append("\n[No knowledge accumulated yet. Use this space to build your understanding across iterations.]")
        }
        
        // Add structured memory format guidance
        promptBuilder.append("\n\nWhen suggesting a new query, you can include a knowledge update in this format:")
        promptBuilder.append("\nNEXT_QUERY:your query {filters} ##MEMORY## New facts learned: 1) First fact 2) Second fact")
        
        // Call LLM with this prompt
        val messageWithContent = ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder().content(promptBuilder.toString()).build()
        )

        val chatCompletionRequest = ChatCompletionCreateParams.builder()
            .messages(listOf(messageWithContent))
            .model("gpt-4o")
            .build()

        try {
            val response = client.chat().completions().create(chatCompletionRequest)
            return response.choices().firstOrNull()?.message()?.content()?.get() ?: "TERMINATE"
        } catch (e: Exception) {
            log.error("Error calling LLM for decision: ${e.message}", e)
            return "TERMINATE"
        }
    }
    
    /**
     * Builds a knowledge memory from previous iterations and search results.
     * This extracts any memory updates the LLM has provided and summarizes knowledge gained.
     *
     * @param previousIterations List of previous search iterations
     * @param searchBuffer Current search results buffer
     * @return A string containing the accumulated knowledge
     */
    private fun buildKnowledgeMemory(
        previousIterations: List<AgenticSearchIteration>,
        searchBuffer: List<VectorStoreSearchResult>
    ): String {
        val memoryBuilder = StringBuilder()
        
        // Extract memory updates from previous iterations
        val memoryUpdates = mutableListOf<String>()
        for (iteration in previousIterations) {
            // Look for memory updates in the query string
            val query = iteration.query
            if (query.contains("##MEMORY##")) {
                val memoryPart = query.substringAfter("##MEMORY##").trim()
                if (memoryPart.isNotEmpty()) {
                    memoryUpdates.add(memoryPart)
                }
            }
        }
        
        // Add all memory updates to the memory builder
        if (memoryUpdates.isNotEmpty()) {
            memoryBuilder.append("Knowledge accumulated so far:\n")
            memoryUpdates.forEachIndexed { index, update ->
                memoryBuilder.append("${index + 1}. $update\n")
            }
        }
        
        // Summarize key facts from the current search buffer if no memory updates
        if (memoryUpdates.isEmpty() && searchBuffer.isNotEmpty()) {
            memoryBuilder.append("Key facts from search results so far:\n")
            
            // Get up to 3 most relevant results based on score
            val topResults = searchBuffer.sortedByDescending { it.score }.take(3)
            topResults.forEachIndexed { index, result ->
                val content = result.content.firstOrNull()?.text ?: ""
                val shortContent = if (content.length > 150) content.take(150) + "..." else content
                memoryBuilder.append("${index + 1}. From ${result.filename}: $shortContent\n")
            }
        }
        
        return memoryBuilder.toString().trim()
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
                            "max_iterations" to
                                    mapOf(
                                        "type" to "integer",
                                        "description" to "Maximum number of search iterations to perform",
                                        "default" to 5,
                                    ),
                            "confidence_threshold" to
                                    mapOf(
                                        "type" to "number",
                                        "description" to "Confidence threshold for early termination (0.0-1.0)",
                                        "default" to 0.8,
                                    )
                        ),
                "required" to listOf("question"),
                "additionalProperties" to false,
            )

        return NativeToolDefinition(
            name = "agentic_search",
            description = """
                Perform an AI-guided iterative search through vector stores that refines queries and filters until finding the best results for your questions.
                This search is performed ONLY on local document repositories and does NOT access the internet.
                Multiple iterations with different queries are encouraged to find the most relevant information.
            """.trimIndent(),
            parameters = parameters,
        )
    }
}
