package ai.masaic.openresponses.tool.agentic.llm

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

data class LlmDecision(
    val query: String,
    val filters: Map<String, Any>?,
)

class DecisionParser(
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(DecisionParser::class.java)

    fun parse(llmDecision: String): LlmDecision {
        val originalDecisionString = llmDecision.trim()
        log.debug("Parsing LLM decision: $originalDecisionString")
    
        // Extract just the decision part - look for TERMINATE or NEXT_QUERY: at the start of any line
        val terminatePattern = "(?m)^TERMINATE.*$".toRegex()
        val nextQueryPattern = "(?m)^NEXT_QUERY:.*$".toRegex()
    
        // Try to find decision line
        val terminateMatch = terminatePattern.find(originalDecisionString)
        val nextQueryMatch = nextQueryPattern.find(originalDecisionString)
    
        // Get the actual decision line
        val decisionString =
            when {
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
        
            var content = decisionString.substring(queryIndex + 1).trim()
            log.debug("Found NEXT_QUERY with content: $content")
        
            // Remove any memory section from the query
            val memoryIndex = content.indexOf("##MEMORY##")
            if (memoryIndex != -1) {
                content = content.substring(0, memoryIndex).trim()
                log.debug("Removed ##MEMORY## section from query, updated content: $content")
            }
        
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
                // but before memory section
                if (filterEnd + 1 < content.length) {
                    val afterFilterContent = content.substring(filterEnd + 1).trim()
                    if (afterFilterContent.isNotEmpty()) {
                        // Check if there's a memory marker in the remaining content
                        val remainingMemoryIndex = afterFilterContent.indexOf("##MEMORY##")
                        if (remainingMemoryIndex != -1 && remainingMemoryIndex > 0) {
                            // Only append content before the memory marker
                            val additionalContent = afterFilterContent.substring(0, remainingMemoryIndex).trim()
                            if (additionalContent.isNotEmpty()) {
                                query = "$query $additionalContent"
                                log.debug("Found additional content before memory marker: $additionalContent")
                            }
                        } else if (remainingMemoryIndex == -1) {
                            // No memory marker, append everything
                            query = "$query $afterFilterContent"
                            log.debug("Found additional content after filter: $afterFilterContent")
                        }
                    }
                }
            
                // Use Jackson to parse the JSON directly to Map<String, Any>
                try {
                    val filters =
                        mapper.readValue(
                            filterJson, 
                            object : TypeReference<Map<String, Any>>() {},
                        )
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
} 
