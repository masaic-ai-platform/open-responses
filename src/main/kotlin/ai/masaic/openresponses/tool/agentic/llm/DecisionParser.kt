package ai.masaic.openresponses.tool.agentic.llm

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

data class LlmDecision(
    val query: String,
    val filters: Map<String, Any>?,
    val isTerminate: Boolean = false,
    val terminateReason: String? = null,
)

class DecisionParser(
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(DecisionParser::class.java)

    fun parse(decisionText: String): LlmDecision {
        // 1) If it’s a TERMINATE decision, capture any reason text
        if (decisionText.startsWith("TERMINATE", ignoreCase = true)) {
            val reason =
                decisionText
                    .substringAfter("TERMINATE", "")
                    .removePrefix(":")
                    .trim()
            return LlmDecision(
                query = "",
                filters = null,
                isTerminate = true,
                terminateReason = if (reason.isNotEmpty()) reason else null,
            )
        }

        // 2) Otherwise must be NEXT_QUERY:
        if (!decisionText.startsWith("NEXT_QUERY:")) {
            throw IllegalArgumentException("Unsupported decision format: $decisionText")
        }

        // 3) Split off everything after the label
        val afterNext = decisionText.substringAfter("NEXT_QUERY:")
        // Extract the free‐text query up to the JSON
        val queryPart = afterNext.substringBefore("{").trim()
        // Extract JSON between the first `{` and the last `}`
        val jsonBody =
            afterNext
                .substringAfter("{")
                .substringBeforeLast("}")
                .let { body -> "{$body}" }

        // 4) Parse filters into a Map, or null if it fails
        val filtersMap: Map<String, Any>? =
            try {
                mapper.readValue(jsonBody, Map::class.java) as Map<String, Any>
            } catch (e: Exception) {
                log.warn("Failed to parse NEXT_QUERY filters JSON: ${e.message}")
                null
            }

        return LlmDecision(
            query = queryPart,
            filters = filtersMap,
            isTerminate = false,
            terminateReason = null,
        )
    }
} 
