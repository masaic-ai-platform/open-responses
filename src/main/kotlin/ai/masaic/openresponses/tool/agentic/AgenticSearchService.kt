package ai.masaic.openresponses.tool.agentic

import ai.masaic.openresponses.api.model.ComparisonFilter
import ai.masaic.openresponses.api.model.CompoundFilter
import ai.masaic.openresponses.api.model.Filter
import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import ai.masaic.openresponses.api.service.search.HybridSearchService
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
import kotlin.jvm.optionals.getOrElse
import kotlin.math.min
import kotlin.random.Random

@Component
class AgenticSearchService(
    store: VectorStoreService,
    private val mapper: ObjectMapper,
    private val hybridSearchService: HybridSearchService,
) {
    private val log = LoggerFactory.getLogger(AgenticSearchService::class.java)
    private val seeds = SeedStrategyFactory(store, hybridSearchService)
    private val parser = DecisionParser(mapper)

    companion object {
        private const val DEFAULT_MAX_SCORE = 1.0
    }

    suspend fun run(
        params: AgenticSearchParams,
        vectorStoreIds: List<String>,
        userFilter: Filter?,
        maxResults: Int,
        maxIterations: Int,
        seedName: String?,
        openAIClient: OpenAIClient,
        requestParams: ResponseCreateParams,
        initialSeedMultiplier: Int = 5,
    ): AgenticSearchResponse {
        require(params.question.isNotBlank()) { "Question must not be blank" }
        require(maxResults > 0) { "maxResults must be positive" }
        require(maxIterations > 0) { "maxIterations must be positive" }

        return withContext(Dispatchers.IO) {
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
            var repeatCount = 0
        
            // Pre-populate search buffer with initial results based on the original question
            log.info("Pre-populating search buffer with initial query: '$currentQuery'")
            val initialSearchFilter = FilterBuilder.createSearchFilter(userFilter, currentFilters, mapper)

            val seedSize = min(maxResults * initialSeedMultiplier, 100)
            // Perform initial seed search without recording it as an iteration
            val searchBuffer = strategy.seed(currentQuery, seedSize, initialSearchFilter, vectorStoreIds).take(maxResults).toMutableList()
            
            // Track initial results in the allRelevantChunks set
            allRelevantChunks.addAll(searchBuffer)

            // …later, right before you compute avgRel in your tuning routine:
            val maxPossibleScore =
                if (allRelevantChunks.isNotEmpty()) {
                    // Use the highest score we’ve seen so far across all iterations
                    allRelevantChunks.maxOf { it.score }
                } else {
                    // Fall back to the default
                    DEFAULT_MAX_SCORE
                }

            val hyperParams =
                LlHyperParams(
                    temperature = requestParams.temperature().getOrElse { 0.6 },
                    topP = requestParams.topP().getOrElse { 0.85 },
                )

            // Now normalize your average relevance:
            val avgRel =
                searchBuffer
                    .take(10)
                    .map { it.score }
                    .average()
                    .let { it / maxPossibleScore } // now in [0.0, 1.0] relative to the best‐so‐far

            // If we already have good results, check if LLM wants to refine further
            if (searchBuffer.isEmpty()) {
                // Handle empty initial results: terminate immediately
                log.info("Initial search returned no results. Terminating early.")
                val terminationRecord =
                    AgenticSearchIteration(
                        query = params.question, // Use original question for context
                        is_final = true,
                        applied_filters = emptyMap(),
                        termination_reason = "No initial results found.",
                    )
                iterations.add(terminationRecord)
                conclusion = "No initial results found."
                shouldTerminate = true // Ensure the main loop doesn't run
            } else {
                // Original logic: Initial results exist, proceed with LLM decision
                log.info("Initial search found ${searchBuffer.size} results, asking LLM for next steps")
            
                // Extract available attributes for filtering
                val availableAttributes = extractAttributes(searchBuffer)

                log.debug("New hyperparameters: temperature=${"%.2f".format(hyperParams.temperature)}, topP=${"%.2f".format(hyperParams.topP)}, presence=${"%.2f".format(hyperParams.presence)}, frequency=${"%.2f".format(hyperParams.frequency)}")

                tune(hyperParams, avgRel)
            
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
                        hyperParams = hyperParams,
                    )
            
                log.info("LLM initial decision: $initialDecision")
            
                if (initialDecision.startsWith("TERMINATE")) {
                    log.info("LLM decided to terminate with initial results")
                    
                    // Only record a termination iteration when LLM explicitly decides to terminate
                    val terminationRecord = AgenticSearchIteration(currentQuery, true, currentFilters, "Terminated after initial results.")
                    terminationRecord.results.addAll(searchBuffer)
                    iterations.add(terminationRecord)
                    
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

                        // Store the entire LLM response, including memory annotations, for downstream knowledge extraction
                        val llmDrivenIteration = AgenticSearchIteration(initialDecision.trim(), false, currentFilters)

                        iterations.add(llmDrivenIteration)
                    } catch (e: Exception) {
                        log.error("Failed to parse initial decision: ${e.message}", e)
                    }
                }
            }
        
            // Main agentic search loop
            while (!shouldTerminate && iterationCount < maxIterations) {
                iterationCount++
            
                // We already recorded the LLM-driven query in the iterations list
                // from the previous iteration or initial decision
                log.debug("==== Iteration $iterationCount: Starting search with query: '$currentQuery' and filters: $currentFilters ====")
                
                // Get the current iteration record
                val currentIterationRecord = if (iterations.isNotEmpty()) iterations.last() else null
            
                // Check for repeated queries with same or similar filters
                if (iterationCount > 1) {
                    // Find any exact matches
                    val exactMatch =
                        iterations.dropLast(1).find { iteration ->
                            // Parse the query part from the stored iteration string for comparison
                            val storedQueryPart =
                                iteration.query
                                    .substringAfter("NEXT_QUERY:")
                                    .substringBefore("{")
                                    .trim()
                            storedQueryPart == currentQuery && iteration.applied_filters == currentFilters
                        }

                    if (exactMatch != null) {
                        repeatCount++
                        log.warn("Detected repeated query with identical filters: '$currentQuery' (repeat #$repeatCount). Discouraging further repeats.")
                        if (repeatCount >= 2) {
                            log.warn("Repeated query threshold reached ($repeatCount). Forcing termination.")
                            shouldTerminate = true
                            // Only add termination record for repeat threshold if needed
                            val terminationRecord = AgenticSearchIteration(currentQuery, true, currentFilters, "Terminated after $repeatCount repeated queries.")
                            iterations.add(terminationRecord)
                            break
                        }
                        // Otherwise, allow one repeat but do not terminate yet
                    }
                }
            
                // Build base filter from user security and LLM filters
                val baseFilter = FilterBuilder.createSearchFilter(userFilter, currentFilters, mapper)
                // Exclude previously seen chunks using chunk_id attribute
                val chunkIds = allRelevantChunks.mapNotNull { it.attributes?.get("chunk_id") as? String }.distinct()
                val exclusionFilter =
                    when {
                        chunkIds.isEmpty() -> null
                        chunkIds.size == 1 -> ComparisonFilter("chunk_id", "ne", chunkIds.first())
                        else -> CompoundFilter("and", chunkIds.map { ComparisonFilter("chunk_id", "ne", it) })
                    }
                // Combine base filter and exclusion filter
                val searchFilter: Filter? =
                    when {
                        baseFilter != null && exclusionFilter != null -> CompoundFilter("and", listOf(baseFilter, exclusionFilter))
                        baseFilter != null -> baseFilter
                        else -> exclusionFilter
                    }
            
                // Perform vector search with current query and filters
                val newResults = strategy.seed(currentQuery, maxResults, searchFilter, vectorStoreIds)
            
                // Add results to current iteration record if available
                currentIterationRecord?.results?.addAll(newResults)
            
                // Add unique results to buffer
                var newResultCount = 0
                newResults.forEach { result ->
                    if (!searchBuffer.any { it.fileId == result.fileId && it.content == result.content }) {
                        searchBuffer.add(result)
                        newResultCount++
                    }
                }
                log.debug("Iteration $iterationCount: Added $newResultCount new unique results to buffer")

                // Track all relevant chunks across all iterations
                allRelevantChunks.addAll(newResults)

                // …later, right before you compute avgRel in your tuning routine:
                val maxPossibleScore =
                    if (allRelevantChunks.isNotEmpty()) {
                        // Use the highest score we’ve seen so far across all iterations
                        allRelevantChunks.maxOf { it.score }
                    } else {
                        // Fall back to the default
                        DEFAULT_MAX_SCORE
                    }

                // Now normalize your average relevance:
                val avgRel =
                    newResults
                        .take(10)
                        .map { it.score }
                        .average()
                        .let { it / maxPossibleScore } // now in [0.0, 1.0] relative to the best‐so‐far

                // And feed that into your tune() function:
                tune(hyperParams, avgRel)

                log.debug("New hyperparameters: temperature=${"%.2f".format(hyperParams.temperature)}, topP=${"%.2f".format(hyperParams.topP)}, presence=${"%.2f".format(hyperParams.presence)}, frequency=${"%.2f".format(hyperParams.frequency)}")

                // Limit buffer size to keep token count manageable
                if (searchBuffer.size > maxResults) {
                    log.debug("Iteration $iterationCount: Trimming search buffer from ${searchBuffer.size} to $maxResults results")
                    searchBuffer.sortByDescending { it.score }
                    while (searchBuffer.size > maxResults) {
                        searchBuffer.removeAt(searchBuffer.size - 1)
                    }
                }

                // Extract available attributes for filtering just the new results
                val availableAttributes = extractAttributes(newResults)
            
                // Call LLM to decide next action based on new results
                var llmDecision = ""
                var retryCount = 0
                var decisionParsed = false
                var decision: LlmDecision? = null

                // Retry loop for getting a valid decision with properly formatted JSON
                while (!decisionParsed && retryCount < 3) {
                    llmDecision =
                        callLlmForDecision(
                            question = params.question,
                            buffer = newResults,
                            iterations = iterations,
                            attrs = availableAttributes,
                            iterationNumber = iterationCount,
                            maxIter = maxIterations,
                            maxResults = maxResults,
                            openAIClient = openAIClient,
                            params = requestParams,
                            hyperParams = hyperParams,
                        )
                
                    log.debug("Iteration $iterationCount" + (if (retryCount > 0) ", retry $retryCount" else "") + ": LLM decision: $llmDecision")
                
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
                                log.debug("Iteration {}: LLM suggested filters: {}", iterationCount, currentFilters)
                            
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
                        
                            log.debug("Iteration $iterationCount: LLM suggested next query: '$currentQuery'")
                            
                            // Only add a new iteration when LLM suggests a new query
                            // Store the *entire* NEXT_QUERY decision string in the query field
                            // so that buildKnowledgeMemory can find the ##MEMORY## marker.
                            val queryToStore = llmDecision.lines().firstOrNull { it.startsWith("NEXT_QUERY:") } ?: llmDecision
                            val llmDrivenIteration = AgenticSearchIteration(queryToStore.trim(), false, currentFilters)
                            iterations.add(llmDrivenIteration)
                            
                            decisionParsed = true
                        } catch (e: Exception) {
                            log.warn("Iteration $iterationCount: Failed to parse LLM decision: ${e.message}")
                            retryCount++
                        }
                    } else if (hasTerminateDecision) {
                        decision = LlmDecision("", null)
                        decisionParsed = true
                        shouldTerminate = true
                        
                        // Only add termination iteration when LLM explicitly decides to terminate
                        val terminationRecord = AgenticSearchIteration("TERMINATE", true, currentFilters, "LLM decided to TERMINATE.")
                        iterations.add(terminationRecord)
                        
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
                    
                    // Add termination record for parse failure
                    val terminationRecord = AgenticSearchIteration("TERMINATE", true, currentFilters, "Default termination after LLM decision parse failures.")
                    iterations.add(terminationRecord)
                }
            }

            // If we reached max iterations without terminating, add a final termination iteration record
            if (iterationCount >= maxIterations && !shouldTerminate) {
                log.info("Reached max iterations ($iterationCount) without LLM explicitly terminating. Forcing termination.")
                
                // Add max iterations termination record
                val terminationRecord = AgenticSearchIteration("TERMINATE", true, currentFilters, "Reached max iterations ($maxIterations).")
                iterations.add(terminationRecord)
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
        
            log.debug("After deduplication: ${uniqueRelevantChunks.size} unique chunks to include in response")
        
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
    suspend fun callLlmForDecision(
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
        hyperParams: LlHyperParams = LlHyperParams(),
    ): String {
        // For current iteration, we should use the iterations up to but not including the current one
        // to avoid showing the same results twice in the prompt
        val iterationsToUse = if (iterationNumber > 0) iterations.dropLast(1) else iterations
        
        // Prepare prompt for LLM using the PromptBuilder
        val promptContent =
            PromptBuilder.build(
                question = question,
                buffer = buffer,
                iterations = iterationsToUse,
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
                .temperature(hyperParams.temperature) // Adjusted temperature for more creative responses
                .topP(hyperParams.topP) // Adjusted top_p for more diverse sampling
                .presencePenalty(hyperParams.presence) // Adjusted presence penalty to encourage new topics
                .frequencyPenalty(hyperParams.frequency) // Adjusted frequency penalty to reduce repetition
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

    private fun tune(
        h: LlHyperParams,
        avgRel: Double,
    ) {
        // avgRel is 0‑1 from your reranker or (score / maxScore)
        val explore = 1.0 - avgRel // 0 = perfect, 1 = bad
        // Wider dynamic ranges for stronger variation
        val baseTemp = 0.3 + 0.7 * explore // [0.3 - 1.0]
        val baseTopP = 0.6 + 0.35 * explore // [0.6 - 0.95]
        val baseFreq = 0.1 + 0.8 * explore // [0.1 - 0.9]
        val basePres = 0.2 + 0.6 * explore // [0.2 - 0.8]
        // Small random jitter to avoid exact repeats
        val jitter = { maxDelta: Double -> Random.nextDouble(-maxDelta, maxDelta) }
        h.temperature = (baseTemp + jitter(0.1)).coerceIn(0.2, 1.0)
        h.topP = (baseTopP + jitter(0.1)).coerceIn(0.5, 1.0)
        h.frequency = (baseFreq + jitter(0.1)).coerceIn(0.0, 1.0)
        h.presence = (basePres + jitter(0.1)).coerceIn(0.0, 1.0)
    }
}

data class LlHyperParams(
    var temperature: Double = 0.6,
    var topP: Double = 0.85,
    var presence: Double = 0.5,
    var frequency: Double = 0.3,
)
