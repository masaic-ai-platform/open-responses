package ai.masaic.openresponses.tool.agentic.llm

import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import ai.masaic.openresponses.tool.AgenticSearchIteration
import org.slf4j.LoggerFactory

object PromptBuilder {
    private val log = LoggerFactory.getLogger(PromptBuilder::class.java)

    fun build(
        question: String,
        buffer: List<VectorStoreSearchResult>,
        iterations: List<AgenticSearchIteration>,
        attrs: Set<String>,
        iteration: Int,
        maxIter: Int,
        maxResults: Int,
        isInitial: Boolean = false,
    ): String {
        log.debug("Building prompt for question: '$question', iteration: $iteration/$maxIter with ${buffer.size} results")
    
        // Prepare prompt for LLM
        val promptBuilder = StringBuilder()
        promptBuilder.append("Original question: $question\n\n")

        // Add buffer contents with attributes for filtering
        promptBuilder.append("Current search results:\n")
    
        // Extract available attributes from results to help LLM understand filterable fields
        log.debug("Adding ${buffer.size} results to prompt with attributes: $attrs")
    
        // Add search results with content and attributes
        buffer.forEachIndexed { index, result ->
            promptBuilder.append("${index + 1}. ${result.filename}: ${result.content.firstOrNull()?.text}\n")
            // Include attributes that can be used for filtering
            if (result.attributes != null && result.attributes.isNotEmpty()) {
                promptBuilder.append("   Attributes: ")
                result.attributes.entries.joinTo(promptBuilder, ", ") { "${it.key}=${it.value}" }
                promptBuilder.append("\n")
            }
        }
    
        // List all available attributes for filtering
        if (attrs.isNotEmpty()) {
            promptBuilder.append("\nAvailable attributes for filtering: ")
            attrs.joinTo(promptBuilder, ", ")
            promptBuilder.append("\n")
        }

        // Add previous queries and filters with their results
        // Only show previous iterations if not the initial search and there are previous iterations
        if (!isInitial && iterations.isNotEmpty()) {
            promptBuilder.append("\nPrevious search iterations:\n")
            
            log.debug("Adding ${iterations.size} previous iterations to prompt")
            iterations.forEachIndexed { index, iteration ->
                val filterInfo =
                    if (iteration.applied_filters != null && iteration.applied_filters.isNotEmpty()) {
                        " with filters: ${iteration.applied_filters}"
                    } else {
                        ""
                    }
            
                // Check if this query was used before
                val isDuplicate =
                    iterations.take(index).any { 
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
        }

        // Add instruction
        promptBuilder.append("\nBased on the original question and current search results, decide whether to:")
        promptBuilder.append("\n1. TERMINATE:[your final conclusion] - if you have sufficient information to answer the question completely. Always verify that the information is complete before terminating, as you may be returning only partial answers otherwise.")
        promptBuilder.append("\n2. NEXT_QUERY:[your next search query] {optional filter JSON} - if you need more specific information")
    
        // Add iteration countdown information
        val iterationsLeft = maxIter - iteration
        promptBuilder.append("\n\nYou are on search attempt $iteration/$maxIter ($iterationsLeft search attempts left). Use your remaining search attempts wisely to maximize information discovery.")
    
        // Inform LLM about the results limit per search
        promptBuilder.append("\n\nIMPORTANT: Each search can only return a maximum of $maxResults chunks at a time. Plan your search strategy accordingly.")
    
        // Additional context if these are initial results
        if (isInitial) {
            promptBuilder.append("\n\nThese are the initial results based on the original question. ")
            promptBuilder.append("If you think they contain sufficient information to completely answer the question, respond with TERMINATE. ")
            promptBuilder.append("Always double-check that the information is complete before terminating. When in doubt, it's better to continue searching. ")
            promptBuilder.append("Otherwise, suggest a more specific query to refine the search.")
        }
    
        // Add filter examples and guidance, specifically mentioning the available attributes
        promptBuilder.append("\n\nYou should apply filters based on the document attributes visible in the search results.")
        promptBuilder.append("\nFilter examples:")
    
        // Generate examples using actual attributes where possible
        if (attrs.isNotEmpty()) {
            val exampleAttrs = attrs.toList()
        
            // Prioritize chunk_index if available
            if (attrs.contains("chunk_index")) {
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
        promptBuilder.append("\n   - If the document follows a specific structure, then you can use that structure to search for the information. For example, if the document is a csv or a json then you can try to search for the specific keys in the document.")
        promptBuilder.append("\n3. Recognize when you have sufficient information and TERMINATE rather than continuing with marginally different queries")

        // Add query novelty checklist to discourage duplicate re‑phrasing
        promptBuilder.append("\n\n## Query Novelty Checklist")
        promptBuilder.append("\nBefore proposing NEXT_QUERY, confirm that:")
        promptBuilder.append("\n- It introduces at least one NEW domain‑specific term discovered in the search results that did not appear in any previous query.")
        promptBuilder.append("\n- It targets a different attribute value (e.g., different filename or chunk_index range) than prior queries, and excludes chunk_index values already searched.")
        promptBuilder.append("\n- Filters must exclude any 'chunk_index' for same 'filename' values that appeared in earlier iterations.")
        promptBuilder.append("\n- Fewer than 70% of its words overlap with the ORIGINAL question or any earlier query.")
        promptBuilder.append("\nIf these conditions are not met, craft a different query that satisfies them.")

        // Add guidance for chunk-by-chunk searching and memory interface
        promptBuilder.append("\n\n## Search Strategy and Memory")
        promptBuilder.append("\nSome questions require methodical chunk-by-chunk exploration. If you see 'chunk_index' in the attributes, you can:")
        promptBuilder.append("\n1. Search sequentially through chunks using filters like {\"filename\": \"document.pdf\", \"chunk_index\": 1}, then {\"filename\": \"document.pdf\", \"chunk_index\": 2}, etc.")
        promptBuilder.append("\n2. Use filters to target specific document sections with {\"filename\": \"document.pdf\", \"chunk_index\": [3, 4, 5]} for multiple chunks.")
        promptBuilder.append("\n3. If you think you've a chunk has partial information then you can add filter of trailing chunk indexes to the query and same for the leading chunks.")
        promptBuilder.append("\n4. Do not query the chunks already received with different wording. They will be filtered out by the vector database. Once you receive a chunk, save your learning in memory and avoid filtering the same chunk again.")
        promptBuilder.append("\n\n⚠️ IMPORTANT: When filtering by 'chunk_index', you MUST ALWAYS include a 'filename' filter as well, since different files can have the same chunk indices. Failure to include a filename filter with chunk_index will result in errors.")

        // Add memory interface
        promptBuilder.append("\n\n## Your Knowledge Memory:")
    
        // Generate memory summary from previous iterations
        val knowledgeGained = buildKnowledgeMemory(iterations)
        if (knowledgeGained.isNotEmpty()) {
            promptBuilder.append("\n$knowledgeGained")
        } else {
            promptBuilder.append("\n[No knowledge summary yet. You must provide a comprehensive summary of information found.]")
        }
    
        // Add structured memory format guidance
        promptBuilder.append("\n\nEach time you suggest a new query, provide a complete knowledge summary in this format:")
        promptBuilder.append("\nNEXT_QUERY:your query {filters} ##MEMORY## 1) First key point 2) Second key point...")
    
        // Add guidance for memory updates
        promptBuilder.append("\n\nYour knowledge summary should:")
        promptBuilder.append("\n- BUILD ON previous knowledge, not replace it entirely")
        promptBuilder.append("\n- Include both existing information AND new findings from the latest search")
        promptBuilder.append("\n- Synthesize and organize the information in a clear, structured way")
        promptBuilder.append("\n- Prioritize facts most relevant to the original question")
        promptBuilder.append("\n- When adding new information, preserve the valuable insights from previous iterations")
        promptBuilder.append("\n- Use numbered or bulleted lists to organize key points")

        // Add strict formatting guidelines for JSON
        promptBuilder.append("\n\n## IMPORTANT: Response Format")
        promptBuilder.append("\nYour response MUST contain exactly one of these prefixes on its own line:")
        promptBuilder.append("\n- TERMINATE: your final conclusion")
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
        promptBuilder.append("\n- When using chunk_index filter, you MUST include a filename filter in the same query")

        promptBuilder.append("\n\n- IMPORTANT: Avoid querying same chunk_index with different wording. This won't yield new results. Once you receive a chunk, save your learning in memory and avoid filtering the same chunk again.")

        log.debug("Completed prompt building, length: ${promptBuilder.length} chars")
        return promptBuilder.toString()
    }

    /**
     * Builds a knowledge memory from previous iterations and search results.
     * This extracts any memory updates the LLM has provided and uses the latest one.
     *
     * @param previousIterations List of previous search iterations
     * @return A string containing the most recent knowledge summary
     */
    fun buildKnowledgeMemory(
        previousIterations: List<AgenticSearchIteration>,
    ): String {
        val memoryBuilder = StringBuilder()
      
        log.debug("Building knowledge memory from ${previousIterations.size} iterations")
      
        // Build a comprehensive knowledge history by iteration
        memoryBuilder.append("Knowledge gathered across iterations:\n")
      
        // Track iterations with memory updates
        var hasAnyMemoryUpdates = false
        var memoryUpdateCount = 0
      
        // Process iterations in chronological order (oldest first)
        previousIterations.forEachIndexed { index, iteration ->
            val query = iteration.query
            log.debug("Examining iteration ${index + 1} query: '${query.take(50)}${if (query.length > 50) "..." else ""}'")
          
            // More strict pattern matching for memory updates - must follow NEXT_QUERY format
            val memoryPattern = "NEXT_QUERY:.*##MEMORY##".toRegex(RegexOption.IGNORE_CASE)
            val memoryMatch = memoryPattern.find(query)
            
            if (memoryMatch != null) {
                val memoryIndex = query.indexOf("##MEMORY##")
                val memoryContent = query.substring(memoryIndex + 10).trim()
                log.debug("Found proper ##MEMORY## marker in iteration ${index + 1}, content length: ${memoryContent.length}")
              
                if (memoryContent.isNotEmpty()) {
                    memoryUpdateCount++
                    hasAnyMemoryUpdates = true
                    // Use actual iteration number (index+1) rather than an arbitrary counter
                    memoryBuilder.append("\n## Iteration ${index + 1}:\n")
                    memoryBuilder.append(memoryContent)
                    memoryBuilder.append("\n")
                } else {
                    log.debug("Skipping empty memory content in iteration ${index + 1}")
                }
            } else if (query.contains("##MEMORY##")) {
                log.debug("Found ##MEMORY## marker but not in proper NEXT_QUERY format in iteration ${index + 1}, skipping")
            } else {
                log.debug("No ##MEMORY## marker found in iteration ${index + 1}")
                
                // Even if there's no explicit memory marker, include a summary of this iteration's results
                // This ensures we capture knowledge from all iterations even if no memory marker is present
                if (iteration.results.isNotEmpty()) {
                    memoryUpdateCount++
                    hasAnyMemoryUpdates = true
                    
                    memoryBuilder.append("\n## Iteration ${index + 1}:\n")
                    memoryBuilder.append("Query: ${iteration.query}\n")
                    
                    if (iteration.applied_filters != null && iteration.applied_filters.isNotEmpty()) {
                        memoryBuilder.append("Filters: ${iteration.applied_filters}\n")
                    }
                    
                    // Include summary of top results (limit to 3 to prevent memory explosion)
                    memoryBuilder.append("Key findings from ${iteration.results.size} results:\n")
                    iteration.results.take(3).forEachIndexed { resultIndex, result ->
                        val content = result.content.firstOrNull()?.text ?: ""
                        val truncatedContent = if (content.length > 200) content.substring(0, 200) + "..." else content
                        memoryBuilder.append("- ${result.filename}: $truncatedContent\n")
                    }
                    
                    // If this is a termination iteration, include the termination reason
                    if (iteration.is_final && iteration.termination_reason != null) {
                        memoryBuilder.append("Termination reason: ${iteration.termination_reason}\n")
                    }
                    
                    memoryBuilder.append("\n")
                }
            }
        }
      
        log.debug("Found $memoryUpdateCount memory updates across ${previousIterations.size} iterations")
      
        // If no iterations had memory updates, return empty string
        if (!hasAnyMemoryUpdates) {
            return ""
        }
      
        return memoryBuilder.toString().trim()
    }
} 
