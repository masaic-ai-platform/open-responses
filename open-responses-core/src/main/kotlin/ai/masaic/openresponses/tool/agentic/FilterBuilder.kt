package ai.masaic.openresponses.tool.agentic

import ai.masaic.openresponses.api.model.ComparisonFilter
import ai.masaic.openresponses.api.model.CompoundFilter
import ai.masaic.openresponses.api.model.Filter
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

object FilterBuilder {
    private val log = LoggerFactory.getLogger(FilterBuilder::class.java)

    fun fromMap(
        filterMap: Map<String, Any>,
        mapper: ObjectMapper,
    ): Filter? {
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
                            filters.add(
                                ComparisonFilter(key, typeStr, filterValue),
                            )
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
                    val orFilters =
                        value.mapNotNull { listItem -> 
                            if (listItem != null) {
                                ComparisonFilter(key, "eq", listItem)
                            } else {
                                null
                            } 
                        }
                    if (orFilters.isNotEmpty()) {
                        filters.add(
                            CompoundFilter("or", orFilters),
                        )
                    }
                }
                else -> {
                    // Handle simple key-value pairs
                    filters.add(
                        ComparisonFilter(key, "eq", value),
                    )
                }
            }
        }
    
        return if (filters.size == 1) {
            filters.first()
        } else if (filters.size > 1) {
            CompoundFilter("and", filters)
        } else {
            null
        }
    }

    /**
     * Creates nested filters from a nested map structure
     */
    private fun createNestedFilters(
        parentKey: String,
        nestedMap: Map<String, Any>,
    ): Filter? {
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
                    val orFilters =
                        value.mapNotNull { listItem -> 
                            if (listItem != null) {
                                ComparisonFilter(fullKey, "eq", listItem)
                            } else {
                                null
                            } 
                        }
                    if (orFilters.isNotEmpty()) {
                        filters.add(
                            CompoundFilter("or", orFilters),
                        )
                    }
                }
                else -> {
                    filters.add(
                        ComparisonFilter(fullKey, "eq", value),
                    )
                }
            }
        }
    
        return if (filters.size == 1) {
            filters.first()
        } else if (filters.size > 1) {
            CompoundFilter("and", filters)
        } else {
            null
        }
    }

    /**
     * Always apply the user security filter with LLM-generated filters
     */
    fun createSearchFilter(
        userSecurityFilter: Filter?,
        currentFilters: Map<String, Any>,
        mapper: ObjectMapper,
    ): Filter? =
        if (userSecurityFilter != null) {
            if (currentFilters.isEmpty()) {
                // Just use user security filter
                userSecurityFilter
            } else {
                // Create compound filter combining user security filters AND LLM filters
                val llmFilter = fromMap(currentFilters, mapper)
                if (llmFilter != null) {
                    CompoundFilter("and", listOf(userSecurityFilter, llmFilter))
                } else {
                    userSecurityFilter
                }
            }
        } else if (currentFilters.isNotEmpty()) {
            // No user filter, just use LLM filter
            fromMap(currentFilters, mapper)
        } else {
            null
        }
} 
