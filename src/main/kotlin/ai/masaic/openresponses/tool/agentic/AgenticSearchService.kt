package ai.masaic.openresponses.tool.agentic

import ai.masaic.openresponses.api.model.Filter
import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import ai.masaic.openresponses.api.service.search.VectorStoreService
import ai.masaic.openresponses.tool.*
import ai.masaic.openresponses.tool.agentic.llm.DecisionParser
import ai.masaic.openresponses.tool.agentic.llm.LlmDecision
import ai.masaic.openresponses.tool.agentic.llm.PromptBuilder
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.openai.models.responses.ResponseCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class AgenticSearchService(
    store: VectorStoreService,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AgenticSearchService::class.java)
    private val seeds = SeedStrategyFactory(store)
    private val parser = DecisionParser(mapper)

    suspend fun run(
        params: AgenticSearchParams,
        vectorStoreIds: List<String>,
        userFilter: Filter?,
        maxResults: Int,
        maxIterations: Int,
        seedName: String?,
        openAIClient: OpenAIClient,
        requestParams: ResponseCreateParams,
    ): AgenticSearchResponse =
        withContext(Dispatchers.IO) {
            log.info("Starting agentic search for question: '${params.question}'")
            val strategy = seeds.byName(seedName)
            val iterations = mutableListOf<AgenticSearchIteration>()
            val allRelevantChunks = mutableSetOf<VectorStoreSearchResult>()
            var conclusion: String? = null
        
            // Initial query and filters
            var currentQuery = params.question
            var currentFilters = emptyMap<String, Any>()
            var shouldTerminate = false
            var iterationCount = 0
        
            // Pre-populate search buffer with initial results based on the original question
            log.info("Pre-populating search buffer with initial query: '$currentQuery'")
            val initialSearchFilter = FilterBuilder.createSearchFilter(userFilter, currentFilters, mapper)
        
            // Record the initial search before executing it
            val initialQueryRecord = AgenticSearchIteration(currentQuery, false, currentFilters)
            iterations.add(initialQueryRecord)
            log.debug("Recorded initial pre-population search iteration for query: '${initialQueryRecord.query}'")
        
            // Perform initial seed search
            val searchBuffer = strategy.seed(currentQuery, maxResults, initialSearchFilter, vectorStoreIds).toMutableList()
        
            // Store results with the iteration record
            initialQueryRecord.results.addAll(searchBuffer)
        
            // Track initial results in the allRelevantChunks set
            allRelevantChunks.addAll(searchBuffer)
        
            // If we already have good results, check if LLM wants to refine further
            if (searchBuffer.isNotEmpty()) {
                log.info("Initial search found ${searchBuffer.size} results, asking LLM for next steps")
            
                // Extract available attributes for filtering
                val availableAttributes = extractAttributes(searchBuffer)
            
                // Call LLM to decide next action
                val initialDecision =
                    callLlmForDecision(
                        question = params.question,
                        buffer = searchBuffer,
                        iterations = iterations,
                        attrs = availableAttributes,
                        iterationNumber = 0,
                        maxIter = maxIterations,
                        maxResults = maxResults,
                        isInitial = true,
                        openAIClient = openAIClient,
                        params = requestParams,
                    )
            
                log.info("LLM initial decision: $initialDecision")
            
                if (initialDecision.startsWith("TERMINATE")) {
                    log.info("LLM decided to terminate with initial results")
                    initialQueryRecord.is_final = true
                    initialQueryRecord.termination_reason = "Terminated after initial results."
                
                    conclusion = initialDecision.substringAfter("TERMINATE").trim()
                    shouldTerminate = true
                } else if (initialDecision.startsWith("NEXT_QUERY:")) {
                    try {
                        log.debug("Parsing initial decision for NEXT_QUERY format: $initialDecision")
                        val decision = parser.parse(initialDecision)
                        currentQuery = decision.query
                        log.info("LLM suggested refined query: '$currentQuery'")
                    
                        if (decision.filters != null) {
                            log.debug("LLM provided initial filters: ${decision.filters}")
                            currentFilters = decision.filters
                            log.info("LLM suggested filters for initial iteration: $currentFilters")
                        }
                    } catch (e: Exception) {
                        log.error("Failed to parse initial decision: ${e.message}", e)
                    }
                }
            }
        
            // Main agentic search loop
            while (!shouldTerminate && iterationCount < maxIterations) {
                iterationCount++
            
                // Record the query/filters before executing the search for this iteration
                val currentIterationRecord = AgenticSearchIteration(currentQuery, false, currentFilters)
                iterations.add(currentIterationRecord)
                log.info("==== Iteration $iterationCount: Starting search with query: '$currentQuery' and filters: $currentFilters ====")
            
                // Check for repeated queries with same or similar filters
                if (iterationCount > 1) {
                    // Find any exact matches
                    val exactMatch =
                        iterations.dropLast(1).find { 
                            it.query == currentQuery && it.applied_filters == currentFilters 
                        }

                    if (exactMatch != null) {
                        log.warn("Detected repeated query with identical filters: '$currentQuery' with $currentFilters. Forcing termination to prevent redundant search.")
                        shouldTerminate = true
                        iterations.add(AgenticSearchIteration(currentQuery, true, currentFilters, "Terminated due to exact query repetition."))
                        break
                    }
                }
            
                // Apply search filter
                val searchFilter = FilterBuilder.createSearchFilter(userFilter, currentFilters, mapper)
            
                // Perform vector search with current query and filters
                val newResults = strategy.seed(currentQuery, maxResults, searchFilter, vectorStoreIds)
            
                // Add results to current iteration record
                currentIterationRecord.results.addAll(newResults)
            
                // Add unique results to buffer
                var newResultCount = 0
                newResults.forEach { result ->
                    if (!searchBuffer.any { it.fileId == result.fileId && it.content == result.content }) {
                        searchBuffer.add(result)
                        newResultCount++
                    }
                }
                log.info("Iteration $iterationCount: Added $newResultCount new unique results to buffer")

                // Track all relevant chunks across all iterations
                allRelevantChunks.addAll(newResults)

                // Limit buffer size to keep token count manageable
                if (searchBuffer.size > maxResults) {
                    log.debug("Iteration $iterationCount: Trimming search buffer from ${searchBuffer.size} to $maxResults results")
                    searchBuffer.sortByDescending { it.score }
                    while (searchBuffer.size > maxResults) {
                        searchBuffer.removeAt(searchBuffer.size - 1)
                    }
                }

                // Extract available attributes for filtering
                val availableAttributes = extractAttributes(searchBuffer)
            
                // Call LLM to decide next action
                var llmDecision = ""
                var retryCount = 0
                var decisionParsed = false
                var decision: LlmDecision? = null

                // Retry loop for getting a valid decision with properly formatted JSON
                while (!decisionParsed && retryCount < 3) {
                    llmDecision =
                        callLlmForDecision(
                            question = params.question, 
                            buffer = searchBuffer, 
                            iterations = iterations,
                            attrs = availableAttributes,
                            iterationNumber = iterationCount,
                            maxIter = maxIterations,
                            maxResults = maxResults,
                            openAIClient = openAIClient,
                            params = requestParams,
                        )
                
                    log.info("Iteration $iterationCount" + (if (retryCount > 0) ", retry $retryCount" else "") + ": LLM decision: $llmDecision")
                
                    // Extract decision part from the LLM response
                    val terminatePattern = "(?m)^TERMINATE.*$".toRegex()
                    val nextQueryPattern = "(?m)^NEXT_QUERY:.*$".toRegex()
                
                    val hasTerminateDecision = terminatePattern.find(llmDecision) != null || llmDecision.contains("TERMINATE", ignoreCase = true)

                    if (hasTerminateDecision) {
                        conclusion = llmDecision.substringAfter("TERMINATE").trim()
                    }

                    val nextQueryMatch = nextQueryPattern.find(llmDecision)
                
                    // Parse the LLM decision
                    if (nextQueryMatch != null) {
                        try {
                            // Extract query and filter information from LLM response
                            decision = parser.parse(llmDecision)
                            currentQuery = decision.query
                        
                            // Update filters from LLM suggestion
                            decision.filters?.let { filters ->
                                currentFilters = filters
                                log.info("Iteration $iterationCount: LLM suggested filters: $currentFilters")
                            
                                // Validate that filename is present when chunk_index is used
                                if (currentFilters.containsKey("chunk_index") && !currentFilters.containsKey("filename")) {
                                    log.warn("Iteration $iterationCount: LLM provided chunk_index filter without filename filter. This is invalid. Retrying.")
                                    throw IllegalArgumentException("When using chunk_index filter, a filename filter must also be provided")
                                }
                            } ?: run {
                                // If no filters were found but the nextQueryMatch exists, we can still use the query
                                log.debug("Iteration $iterationCount: No filters found in LLM response, but query parsed successfully")
                                currentFilters = emptyMap() // Reset filters if none provided
                            }
                        
                            log.info("Iteration $iterationCount: LLM suggested next query: '$currentQuery'")
                            decisionParsed = true
                        } catch (e: Exception) {
                            log.warn("Iteration $iterationCount: Failed to parse LLM decision: ${e.message}")
                            retryCount++
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
                    iterations.add(AgenticSearchIteration("TERMINATE", true, currentFilters, "Default termination after LLM decision parse failures."))
                } else if (shouldTerminate) {
                    iterations.add(AgenticSearchIteration("TERMINATE", true, currentFilters, "LLM decided to TERMINATE."))
                }
            }

            // If we reached max iterations without terminating, add a final termination iteration record
            if (iterationCount >= maxIterations && !shouldTerminate) {
                log.info("Reached max iterations ($iterationCount) without LLM explicitly terminating. Forcing termination.")
                iterations.add(AgenticSearchIteration("TERMINATE", true, currentFilters, "Reached max iterations ($maxIterations)."))
            }

            // Generate knowledge summary from search iterations
            val knowledgeAcquired =
                PromptBuilder.buildKnowledgeMemory(iterations) +
                    conclusion.let {
                        if (it != null) {
                            "\n\n## Final Conclusion:\n$it"
                        } else {
                            ""
                        }
                    }
            log.info("Knowledge acquired: $knowledgeAcquired")

            // Deduplicate by content and file ID, then sort by score
            val uniqueRelevantChunks =
                allRelevantChunks
                    .groupBy { "${it.fileId}:${it.content.firstOrNull()?.text}" }
                    .map { it.value.maxByOrNull { chunk -> chunk.score } }
                    .filterNotNull()
                    .sortedByDescending { it.score }
        
            log.info("After deduplication: ${uniqueRelevantChunks.size} unique chunks to include in response")
        
            // Create response with relevant chunks, search iterations, and acquired knowledge
            AgenticSearchResponse(
                data =
                    uniqueRelevantChunks.map { result ->
                        // Get chunk index from metadata
                        val chunkIndex = result.attributes?.get("chunk_index")

                        AgenticSearchResult(
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
                search_iterations = iterations,
                knowledge_acquired = knowledgeAcquired,
            )
        }

    /**
     * Extract unique attributes from search results for filtering
     */
    private fun extractAttributes(results: List<VectorStoreSearchResult>): Set<String> {
        val attributes = mutableSetOf<String>()
        results.forEach { result ->
            result.attributes?.keys?.forEach { key ->
                attributes.add(key)
            }
        }
        return attributes
    }

    /**
     * Call LLM to make a decision on the next search action
     */
    private suspend fun callLlmForDecision(
        question: String,
        buffer: List<VectorStoreSearchResult>,
        iterations: List<AgenticSearchIteration>,
        attrs: Set<String>,
        iterationNumber: Int = 0,
        maxIter: Int = 5,
        maxResults: Int = 20,
        isInitial: Boolean = false,
        params: ResponseCreateParams,
        openAIClient: OpenAIClient,
    ): String {
        // Prepare prompt for LLM using the PromptBuilder
        val promptContent =
            PromptBuilder.build(
                question = question,
                buffer = buffer,
                iterations = iterations,
                attrs = attrs,
                iteration = iterationNumber,
                maxIter = maxIter,
                maxResults = maxResults,
                isInitial = isInitial,
            )

        // Log the prompt for debugging purposes
        log.debug("Generated prompt: $promptContent")
        
        // Call LLM with this prompt
        val messageWithContent =
            ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder().content(promptContent).build(),
            )

        val chatCompletionRequest =
            ChatCompletionCreateParams
                .builder()
                .messages(listOf(messageWithContent))
                .model(params.model().toString())
                .build()

        try {
            val response = openAIClient.chat().completions().create(chatCompletionRequest)
            val content =
                response
                    .choices()
                    .firstOrNull()
                    ?.message()
                    ?.content()
                    ?.get() ?: "TERMINATE"
                
            // Log the response for debugging
            log.debug("LLM response: $content")
            return content
        } catch (e: Exception) {
            log.error("Error calling LLM for decision: ${e.message}", e)
            return "TERMINATE"
        }
    }

    /**
     * Extract model from params or use default
     */
    private fun getModelFromParams(params: ResponseCreateParams?): String = params?.model()?.toString() ?: "gpt-4-0125-preview"
} 
