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
        client: OpenAIClient
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
        openAIClient: OpenAIClient
    ): String {
        val params = objectMapper.readValue(arguments, AgenticSearchParams::class.java)
        log.info("Starting agentic search for question: '${params.question}' with max_iterations: ${params.max_iterations}")

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
        
        log.info("Using vector store IDs: $vectorStoreIds")
        
        // Extract user-provided security filters - these must be preserved
        val userSecurityFilter = function.first()._additionalProperties()["filters"]?.convert(Filter::class.java)
        log.debug("User security filters: $userSecurityFilter")
        
        val maxResults =
            function
                .first()
                ._additionalProperties()["max_num_results"].toString()
                .toInt()
        log.debug("Max results per search: $maxResults")

        // Initialize our search state
        val searchBuffer = mutableListOf<VectorStoreSearchResult>()
        val searchIterations = mutableListOf<AgenticSearchIteration>()
        var currentQuery = params.question
        var shouldTerminate = false
        var iterationCount = 0

        // Initial filters - start with empty map, the LLM will generate filters
        var currentFilters = emptyMap<String, Any>()

        // Pre-populate search buffer with initial results based on the original question
        log.info("Pre-populating search buffer with initial query: '$currentQuery'")
        val initialSearchFilter = createSearchFilter(userSecurityFilter, currentFilters)
        log.debug("Initial search filter: $initialSearchFilter")
        val typeReference = object : TypeReference<List<String>>() {}

        // Record the initial search *before* executing it
        val initialQueryRecord = AgenticSearchIteration(currentQuery, false, currentFilters)
        searchIterations.add(initialQueryRecord)
        log.debug("Recorded initial pre-population search iteration for query: '${initialQueryRecord.query}'")

        // Perform initial search across all vector stores
        for (vectorStoreId in vectorStoreIds.convert(typeReference)!!) {
            try {
                log.debug("Searching vector store: $vectorStoreId with initial query")
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
                if (initialResults.isNotEmpty()) {
                    log.debug("Top initial result: ${initialResults.first().filename} with score ${initialResults.first().score}")
                }
                searchBuffer.addAll(initialResults)
                
                // Store results with the iteration record
                initialQueryRecord.results.addAll(initialResults)
            } catch (e: Exception) {
                log.error("Error searching vector store $vectorStoreId for initial results", e)
            }
        }
        
        // Limit initial buffer size if needed
        if (searchBuffer.size > maxResults) {
            log.debug("Trimming initial search buffer from ${searchBuffer.size} to $maxResults results")
            searchBuffer.sortByDescending { it.score }
            searchBuffer.subList(maxResults, searchBuffer.size).clear()
        }
        
        // If we already have good results, check if LLM wants to refine further
        if (searchBuffer.isNotEmpty()) {
            log.info("Initial search found ${searchBuffer.size} results, asking LLM for next steps")
            
            val initialDecision = callLlmForDecision(
                originalQuestion = params.question,
                searchBuffer = searchBuffer,
                previousIterations = searchIterations,
                isInitialResults = true,
                openAIClient,
                requestParams = requestParams,
            )
            
            log.info("LLM initial decision: $initialDecision")
            
            if (initialDecision.startsWith("TERMINATE")) {
                log.info("LLM decided to terminate with initial results")
                initialQueryRecord.is_final = true
                initialQueryRecord.termination_reason = "Terminated after initial results."
                log.debug("Updated initial iteration record to mark termination.")

                val knowledgeAcquired = buildKnowledgeMemory(searchIterations)
                log.info("Knowledge acquired from initial results: $knowledgeAcquired")
                
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
                    search_iterations = searchIterations,
                    knowledge_acquired = knowledgeAcquired + initialDecision.substringAfter("TERMINATE").trim(),
                )
                log.info("Returning response with ${searchBuffer.size} results after LLM decided to terminate early")
                return objectMapper.writeValueAsString(response)
            }
            
            if (initialDecision.startsWith("NEXT_QUERY:")) {
                try {
                    log.debug("Parsing initial decision for NEXT_QUERY format: $initialDecision")
                    val decision = parseLlmDecision(initialDecision)
                    currentQuery = decision.query
                    log.info("LLM suggested refined query: '$currentQuery'")
                    
                    if (decision.filters != null) {
                        log.debug("LLM provided initial filters: ${decision.filters}")
                        @Suppress("UNCHECKED_CAST") 
                        currentFilters = decision.filters as Map<String, Any>
                        log.info("LLM suggested filters for initial iteration: $currentFilters")
                    } else {
                        log.debug("No filters provided in initial LLM decision")
                    }
                } catch (e: Exception) {
                    log.error("Failed to parse initial decision: ${e.message}", e)
                }
            }
        }

        // Main agentic search loop
        while (!shouldTerminate && iterationCount < params.max_iterations) {
            iterationCount++
            
            // Record the query/filters *before* executing the search for this iteration
            val currentIterationRecord = AgenticSearchIteration(currentQuery, false, currentFilters)
            searchIterations.add(currentIterationRecord)
            log.info("==== Iteration $iterationCount: Starting search with query: '$currentQuery' and filters: $currentFilters ====")
            log.debug("Added iteration record ${searchIterations.size} for current search.")
            
            // Check for repeated queries with same or similar filters
            if (iterationCount > 1) {
                // Detailed debugging of query comparison
                log.debug("Checking for duplicates. Current query: '$currentQuery', filters: $currentFilters")
                
                // Log each previous iteration for comparison
                searchIterations.forEachIndexed { index, iteration ->
                    log.debug("Previous iteration $index - Query: '${iteration.query}', Filters: ${iteration.applied_filters}")
                }
                
                // Find any exact matches
                val exactMatch = searchIterations.dropLast(1).find { it.query == currentQuery && it.applied_filters == currentFilters }

                if (exactMatch != null) {
                    log.warn("Detected repeated query with identical filters: '$currentQuery' with $currentFilters. Forcing termination to prevent redundant search.")
                    shouldTerminate = true
                    searchIterations.add(AgenticSearchIteration(currentQuery, true, currentFilters, "Terminated due to exact query repetition."))
                    break
                } else {
                    log.debug("No duplicate found, proceeding with search.")
                }
            } else {
                log.debug("Skipping duplicate check for initial iteration as it's already recorded")
            }
            
            // Apply search filter
            val searchFilter = createSearchFilter(userSecurityFilter, currentFilters)
            log.debug("Iteration $iterationCount: Combined search filter: $searchFilter")
            
            // Perform vector search with current query and filters
            val allResults = mutableListOf<VectorStoreSearchResult>()
            for (vectorStoreId in vectorStoreIds.convert(typeReference)!!) {
                try {
                    log.debug("Iteration $iterationCount: Searching vector store: $vectorStoreId")
                    val searchRequest =
                        VectorStoreSearchRequest(
                            query = currentQuery,
                            maxNumResults = maxResults,
                            filters = searchFilter
                        )
                    val results = vectorStoreService.searchVectorStore(vectorStoreId, searchRequest)
                    log.debug("Iteration $iterationCount: Found ${results.data.size} results from $vectorStoreId")
                    allResults.addAll(results.data)
                } catch (e: Exception) {
                    log.error("Iteration $iterationCount: Error searching vector store $vectorStoreId", e)
                }
            }

            // Sort results by score and add to buffer
            val sortedResults =
                allResults
                    .sortedByDescending { it.score }
                    .take(maxResults)
            
            log.info("Iteration $iterationCount: Found ${sortedResults.size} unique results across all vector stores")
            if (sortedResults.isNotEmpty()) {
                log.debug("Iteration $iterationCount: Top result: ${sortedResults.first().filename} with score ${sortedResults.first().score}")
            }

            // Add results to current iteration record
            currentIterationRecord.results.addAll(sortedResults)
            
            // Add unique results to buffer
            var newResultCount = 0
            sortedResults.forEach { result ->
                if (!searchBuffer.any { it.fileId == result.fileId && it.content == result.content }) {
                    searchBuffer.add(result)
                    newResultCount++
                }
            }
            log.info("Iteration $iterationCount: Added $newResultCount new unique results to buffer")

            // Limit buffer size to keep token count manageable
            if (searchBuffer.size > maxResults) {
                log.debug("Iteration $iterationCount: Trimming search buffer from ${searchBuffer.size} to $maxResults results")
                searchBuffer.sortByDescending { it.score }
                while (searchBuffer.size > maxResults) {
                    searchBuffer.removeAt(searchBuffer.size - 1)
                }
            }

            // Call LLM to decide next action
            log.info("Iteration $iterationCount: Calling LLM to decide next action based on ${searchBuffer.size} results in buffer")
            var llmDecision = ""
            var retryCount = 0
            var decisionParsed = false
            var decision: LlmDecision? = null
            
            // Retry loop for getting a valid decision with properly formatted JSON
            while (!decisionParsed && retryCount < 3) {
                llmDecision = callLlmForDecision(
                    originalQuestion = params.question, 
                    searchBuffer = searchBuffer, 
                    previousIterations = searchIterations,
                    client = openAIClient,
                    requestParams = requestParams,
                )
                
                log.info("Iteration $iterationCount" + (if (retryCount > 0) ", retry $retryCount" else "") + ": LLM decision: $llmDecision")
                
                // Extract decision part from the LLM response
                val terminatePattern = "(?m)^TERMINATE.*$".toRegex()
                val nextQueryPattern = "(?m)^NEXT_QUERY:.*$".toRegex()
                
                val hasTerminateDecision = terminatePattern.find(llmDecision) != null || llmDecision.contains("TERMINATE", ignoreCase = true)
                val nextQueryMatch = nextQueryPattern.find(llmDecision)
                
                // Parse the LLM decision
                if (nextQueryMatch != null) {
                    try {
                        // Extract query and filter information from LLM response
                        decision = parseLlmDecision(llmDecision)
                        currentQuery = decision.query
                        
                        // Update filters from LLM suggestion
                        if (decision.filters != null) {
                            @Suppress("UNCHECKED_CAST")
                            currentFilters = decision.filters as Map<String, Any>
                            log.info("Iteration $iterationCount: LLM suggested filters: $currentFilters")
                        } else {
                            // If no filters were found but the nextQueryMatch exists, we can still use the query
                            log.debug("Iteration $iterationCount: No filters found in LLM response, but query parsed successfully")
                            currentFilters = emptyMap() // Reset filters if none provided
                        }
                        
                        log.info("Iteration $iterationCount: LLM suggested next query: '$currentQuery'")
                        decisionParsed = true
                    } catch (e: Exception) {
                        log.warn("Iteration $iterationCount: Failed to parse LLM decision: ${e.message}")
                        retryCount++
                        
                        // Add more specific instructions on the retry
                        if (retryCount < 3) {
                            log.info("Iteration $iterationCount: Retrying LLM decision with more specific instructions (attempt $retryCount/3)")
                        }
                    }
                } else if (hasTerminateDecision) {
                    decision = LlmDecision("", null)
                    decisionParsed = true
                    shouldTerminate = true
                    log.info("Iteration $iterationCount: LLM decided to TERMINATE search")
                } else {
                    // If no valid decision pattern was found, increment retry count
                    log.warn("Iteration $iterationCount: Unrecognized LLM decision format. Retrying.")
                    retryCount++
                }
            }
            
            // After retries, determine the action
            if (!decisionParsed) {
                // Default to termination if we couldn't parse a valid decision after retries
                log.warn("Iteration $iterationCount: Failed to parse a valid LLM decision after $retryCount retries. Defaulting to TERMINATE.")
                shouldTerminate = true
                searchIterations.add(AgenticSearchIteration(currentQuery, true, currentFilters, "Default termination after LLM decision parse failures."))
                log.info("Iteration $iterationCount: Recorded termination iteration due to parse failure.")
            } else if (shouldTerminate) {
                searchIterations.add(AgenticSearchIteration(currentQuery, true, currentFilters, "LLM decided to TERMINATE."))
                log.info("Iteration $iterationCount: Recorded termination iteration based on LLM decision.")
            }
        }

        // If we reached max iterations without terminating, add a final termination iteration record
        if (iterationCount >= params.max_iterations && !shouldTerminate) {
            log.info("Reached max iterations ($iterationCount) without LLM explicitly terminating. Forcing termination.")
            searchIterations.add(AgenticSearchIteration(currentQuery, true, currentFilters, "Reached max iterations (${params.max_iterations})."))
            log.info("Recorded termination iteration due to max iterations.")
        }

        // Generate knowledge summary from search iterations
        val knowledgeAcquired = buildKnowledgeMemory(searchIterations)
        log.info("Knowledge acquired: $knowledgeAcquired")

        // Convert results to response format
        log.info("Search completed after $iterationCount iterations. Returning ${searchBuffer.size} results and ${searchIterations.size} iterations")
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
                search_iterations = searchIterations,
                knowledge_acquired = knowledgeAcquired
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
        val originalDecisionString = llmDecision.trim()
        log.debug("Parsing LLM decision: $originalDecisionString")
        
        // Extract just the decision part - look for TERMINATE or NEXT_QUERY: at the start of any line
        val terminatePattern = "(?m)^TERMINATE.*$".toRegex()
        val nextQueryPattern = "(?m)^NEXT_QUERY:.*$".toRegex()
        
        // Try to find decision line
        val terminateMatch = terminatePattern.find(originalDecisionString)
        val nextQueryMatch = nextQueryPattern.find(originalDecisionString)
        
        // Get the actual decision line
        val decisionString = when {
            terminateMatch != null -> terminateMatch.value
            nextQueryMatch != null -> nextQueryMatch.value
            originalDecisionString.contains("TERMINATE", ignoreCase = true) -> "TERMINATE"
            originalDecisionString.contains("NEXT_QUERY:", ignoreCase = true) -> {
                // Extract whatever follows NEXT_QUERY: if found anywhere in text
                val nextQueryText = "NEXT_QUERY:"
                val nextQueryIndex = originalDecisionString.indexOf(nextQueryText, ignoreCase = true)
                if (nextQueryIndex >= 0) {
                    originalDecisionString.substring(nextQueryIndex)
                } else {
                    // Fallback for case-insensitive search
                    val lowerCaseDecision = originalDecisionString.lowercase()
                    val lowerCaseText = nextQueryText.lowercase()
                    val lowerCaseIndex = lowerCaseDecision.indexOf(lowerCaseText)
                    if (lowerCaseIndex >= 0) {
                        originalDecisionString.substring(lowerCaseIndex)
                    } else {
                        originalDecisionString
                    }
                }
            }
            else -> originalDecisionString // Use original as fallback
        }
        
        log.debug("Extracted decision line: $decisionString")
        
        // Check for TERMINATE in a case-insensitive way
        if (decisionString.lowercase().startsWith("terminate")) {
            log.debug("Decision is TERMINATE - no further parsing needed")
            return LlmDecision("", null)
        }
        
        // Check for NEXT_QUERY: in a case-insensitive way
        val nextQueryLC = "next_query:"
        if (decisionString.lowercase().startsWith(nextQueryLC)) {
            // Find the colon by getting the length of the prefix
            val queryIndex = decisionString.lowercase().indexOf(":")
            if (queryIndex == -1) {
                throw IllegalArgumentException("Missing colon in NEXT_QUERY format")
            }
            val content = decisionString.substring(queryIndex + 1).trim()
            log.debug("Found NEXT_QUERY with content: $content")
            
            // Check if there are filters specified in JSON format
            val filterStart = content.indexOf("{")
            val filterEnd = content.lastIndexOf("}")
            
            log.debug("Checking for filters in content: '$content', filterStart=$filterStart, filterEnd=$filterEnd")
            
            if (filterStart > 0 && filterEnd > filterStart) {
                // Extract query and filter parts
                var query = content.substring(0, filterStart).trim()
                val filterJson = content.substring(filterStart, filterEnd + 1)
                log.debug("Found potential filter JSON: $filterJson")
                
                // Handle any content after the JSON filters (could include memory updates)
                if (filterEnd + 1 < content.length) {
                    val afterFilterContent = content.substring(filterEnd + 1).trim()
                    if (afterFilterContent.isNotEmpty()) {
                        // Append the remaining content to the query
                        log.debug("Found additional content after filter: $afterFilterContent")
                        query = "$query $afterFilterContent"
                    }
                }
                
                // Use Jackson to parse the JSON directly to Map<String, Any>
                try {
                    val filters = objectMapper.readValue(filterJson, 
                        object : TypeReference<Map<String, Any>>() {})
                    log.debug("Successfully parsed filters: $filters")
                    
                    return LlmDecision(query, filters)
                } catch (e: Exception) {
                    // If JSON parsing fails, throw an exception to trigger retry
                    log.warn("Failed to parse JSON filters: $filterJson", e)
                    throw IllegalArgumentException("Invalid JSON filter format: ${e.message}")
                }
            } else {
                log.debug("No filter JSON found in content - using as plain query")
            }
            
            // If no valid filters were found or parsing failed, return just the query
            log.debug("Returning query without filters: $content")
            return LlmDecision(content, null)
        }
        
        log.warn("Unrecognized decision format: $decisionString")
        throw IllegalArgumentException("Unrecognized decision format")
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
     * @param isInitialResults Whether these are the initial results from the pre-population step
     * @return LLM decision string (TERMINATE or NEXT_QUERY:[query])
     */
    private suspend fun callLlmForDecision(
        originalQuestion: String,
        searchBuffer: List<VectorStoreSearchResult>,
        previousIterations: List<AgenticSearchIteration>,
        isInitialResults: Boolean = false,
        client: OpenAIClient,
        requestParams: ResponseCreateParams
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

        // Add previous queries and filters with their results
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
            val iterationStatus = if (iteration.is_final) " (FINAL)" else ""
            
            promptBuilder.append("${index + 1}. Query: \"${iteration.query}\"$filterInfo$duplicateWarning$iterationStatus\n")
            
            // Add the actual results for this iteration
            if (iteration.results.isNotEmpty()) {
                promptBuilder.append("   Results (${iteration.results.size}):\n")
                iteration.results.forEachIndexed { resultIndex, result ->
                    promptBuilder.append("   ${resultIndex + 1}. ${result.filename}: ${result.content.firstOrNull()?.text}\n")
                    
                    // Include key attributes
                    if (result.attributes != null && result.attributes.isNotEmpty()) {
                        promptBuilder.append("      Attributes: ")
                        result.attributes.entries.joinTo(promptBuilder, ", ") { "${it.key}=${it.value}" }
                        promptBuilder.append("\n")
                    }
                }
            } else {
                promptBuilder.append("   No results found for this query.\n")
            }
        }

        // Add instruction
        promptBuilder.append("\nBased on the original question and current search results, decide whether to:")
        promptBuilder.append("\n1. TERMINATE - if you have sufficient information to answer the question completely. Always verify that the information is complete before terminating, as you may be returning only partial answers otherwise.")
        promptBuilder.append("\n2. NEXT_QUERY:[your next search query] {optional filter JSON} - if you need more specific information")
        
        // Additional context if these are initial results
        if (isInitialResults) {
            promptBuilder.append("\n\nThese are the initial results based on the original question. ")
            promptBuilder.append("If you think they contain sufficient information to completely answer the question, respond with TERMINATE. ")
            promptBuilder.append("Always double-check that the information is complete before terminating. When in doubt, it's better to continue searching. ")
            promptBuilder.append("Otherwise, suggest a more specific query to refine the search.")
        }
        
        // Add filter examples and guidance, specifically mentioning the available attributes
        promptBuilder.append("\n\nYou should apply filters based on the document attributes visible in the search results.")
        promptBuilder.append("\nFilter examples:")
        
        // Generate examples using actual attributes where possible
        if (availableAttributes.isNotEmpty()) {
            val exampleAttrs = availableAttributes.toList()
            
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
        }

        promptBuilder.append("\n\nRespond with EXACTLY ONE of these formats. Nothing else.")
        promptBuilder.append("\nYou can refine the query based on what you've learned, and add filters to focus on specific document attributes. Do not repeat the same query with same filter. They will most likely return the same results.\n")

        // Add guidance to rephrase generic queries into domain-specific ones
        promptBuilder.append("\nIMPORTANT: The user's original query may be too generic. As you encounter domain-specific terminology and concepts in the documents, rephrase generic queries into more precise, domain-specific queries using the terminology found in the documents. This improves search relevance significantly.\n")

        // Add guidance for vector database search strategies
        promptBuilder.append("\n## Vector Database Search Strategies")
        promptBuilder.append("\nThis is a vector database search system. Important things to understand:")
        promptBuilder.append("\n1. Searching the same chunk with slightly different wording won't yield new results - vector similarity remains the same. Once you receive a check, then save your learning in memory and avoid filtering the same chunk again.")
        promptBuilder.append("\n2. If you want different results, try these effective strategies:")
        promptBuilder.append("\n   - Use completely different terminology/concepts (not just rewording)")
        promptBuilder.append("\n   - Apply different attribute filters (target different document regions)")
        promptBuilder.append("\n   - Search for adjacent concepts or prerequisite information")
        promptBuilder.append("\n   - Use more specific technical terms found in previous results")
        promptBuilder.append("\n   - Explore chronologically (older/newer documents if date attributes exist)")
        promptBuilder.append("\n   - Use context of previous iterations in your query(for example domain specific keywords)")
        promptBuilder.append("\n3. Recognize when you have sufficient information and TERMINATE rather than continuing with marginally different queries")

        // Add guidance for chunk-by-chunk searching and memory interface
        promptBuilder.append("\n\n## Search Strategy and Memory")
        promptBuilder.append("\nSome questions require methodical chunk-by-chunk exploration. If you see 'chunk_index' in the attributes, you can:")
        promptBuilder.append("\n1. Search sequentially through chunks using filters like {\"chunk_index\": 1}, then {\"chunk_index\": 2}, etc.")
        promptBuilder.append("\n2. Use filters to target specific document sections with {\"chunk_index\": [3, 4, 5]} for multiple chunks.")
        promptBuilder.append("\n3. If you think you've a chunk has partial information then you can add filter of previous chunk indexes to the query and same for the next chunks.")

        // Add memory interface
        promptBuilder.append("\n\n## Your Knowledge Memory:")
        
        // Generate memory summary from previous iterations
        val knowledgeGained = buildKnowledgeMemory(previousIterations)
        if (knowledgeGained.isNotEmpty()) {
            promptBuilder.append("\n$knowledgeGained")
        } else {
            promptBuilder.append("\n[No knowledge accumulated yet. Use this space to build your understanding across iterations.]")
        }
        
        // Add structured memory format guidance
        promptBuilder.append("\n\nWhen suggesting a new query, you can include a knowledge update in this format:")
        promptBuilder.append("\nNEXT_QUERY:your query {filters} ##MEMORY## New facts learned: 1) First fact 2) Second fact")

        promptBuilder.append("\n\nMake sure you only capture knowledge that is relevant to the current search context. Avoid including irrelevant or unrelated information.")

        // Add strict formatting guidelines for JSON
        promptBuilder.append("\n\n## IMPORTANT: Response Format")
        promptBuilder.append("\nYour response MUST contain exactly one of these prefixes on its own line:")
        promptBuilder.append("\n- TERMINATE")
        promptBuilder.append("\n- NEXT_QUERY:your search query")
        promptBuilder.append("\nYou can include explanatory text before or after this decision line.")
        
        // Add specific JSON formatting requirements
        promptBuilder.append("\n\n## CRITICAL: JSON Filter Format")
        promptBuilder.append("\nWhen specifying filters, you MUST use valid JSON syntax:")
        promptBuilder.append("\n- ALL property names MUST be in double quotes")
        promptBuilder.append("\n- String values MUST be in double quotes")
        promptBuilder.append("\n- Arrays and objects must use proper JSON syntax")
        promptBuilder.append("\n- Example: NEXT_QUERY:search query {\"filename\": \"document.pdf\", \"chunk_index\": [1, 2, 3]}")
        promptBuilder.append("\n- DO NOT use: {filename: \"document.pdf\"} or {chunk_index: [1, 2, 3]}")

        promptBuilder.append("\n\n- IMPORTANT: Avoid querying same chunk_index with different wording. This won't yield new results. Once you receive a chunk, save your learning in memory and avoid filtering the same chunk again.")

        // Call LLM with this prompt
        val messageWithContent = ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder().content(promptBuilder.toString()).build()
        )

        val chatCompletionRequest = ChatCompletionCreateParams.builder()
            .messages(listOf(messageWithContent))
            .model(requestParams.model().toString())
            .build()

        try {
            val response = client.chat().completions().create(chatCompletionRequest)
            val content = response.choices().firstOrNull()?.message()?.content()?.get() ?: "TERMINATE"
            
            // Log full LLM response for debugging
            log.debug("Full LLM response: $content")
            
            return content
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
     * @return A string containing the accumulated knowledge
     */
    private fun buildKnowledgeMemory(
        previousIterations: List<AgenticSearchIteration>
    ): String {
        val memoryBuilder = StringBuilder()

        log.debug("Building knowledge memory from previous iterations and search buffer")

        // Extract memory updates from previous iterations
        val memoryUpdates = mutableListOf<String>()
        for (iteration in previousIterations) {
            // Look for memory updates in the query string
            val query = iteration.query
            if (query.contains("##MEMORY##")) {
                val memoryIndex = query.indexOf("##MEMORY##")
                if (memoryIndex != -1) {
                    val memoryPart = query.substring(memoryIndex + 10).trim()
                    if (memoryPart.isNotEmpty() && !memoryUpdates.contains(memoryPart)) {
                        memoryUpdates.add(memoryPart)
                    }
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
                        ),
                "required" to listOf("question"),
                "additionalProperties" to false,
            )

        return NativeToolDefinition(
            name = "agentic_search",
            description = """
                Perform an AI-guided iterative search through vector stores that refines queries and filters until finding the best results for your questions.
            """.trimIndent(),
            parameters = parameters,
        )
    }
}
