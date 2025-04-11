package ai.masaic.openresponses.api.utils

import ai.masaic.openresponses.api.model.ComparisonFilter
import ai.masaic.openresponses.api.model.CompoundFilter
import ai.masaic.openresponses.api.model.Filter
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThan
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThanOrEqualTo
import dev.langchain4j.store.embedding.filter.comparison.IsLessThan
import dev.langchain4j.store.embedding.filter.comparison.IsLessThanOrEqualTo
import dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo
import org.slf4j.LoggerFactory

/**
 * Utility class for working with filter objects.
 */
object FilterUtils {
    private val log = LoggerFactory.getLogger(FilterUtils::class.java)

    /**
     * Evaluates whether an entity with the given attributes matches a filter.
     * 
     * @param filter The filter to evaluate
     * @param attributes Map of attributes to check against the filter
     * @param fileId Optional file ID for file_id-specific filters
     * @return true if the attributes match the filter, false otherwise
     */
    fun matchesFilter(
        filter: Filter?,
        attributes: Map<String, Any>,
        fileId: String? = null,
    ): Boolean {
        if (filter == null) return true
        
        return when (filter) {
            is ComparisonFilter -> evaluateComparisonFilter(filter, attributes, fileId)
            is CompoundFilter -> evaluateCompoundFilter(filter, attributes, fileId)
            else -> {
                log.warn("Unknown filter type: ${filter.javaClass.name}")
                false
            }
        }
    }

    /**
     * Evaluates a comparison filter against entity attributes.
     */
    private fun evaluateComparisonFilter(
        filter: ComparisonFilter,
        attributes: Map<String, Any>,
        fileId: String?,
    ): Boolean {
        val key = filter.key
        val value = filter.value
        
        // Special handling for file_id
        if (key == "file_id" && fileId != null) {
            return evaluateComparison(filter.type, fileId, value)
        }
        
        // Check if the attribute exists
        if (!attributes.containsKey(key)) {
            return false
        }
        
        return evaluateComparison(filter.type, attributes[key], value)
    }

    /**
     * Evaluates a compound filter (AND/OR) against entity attributes.
     */
    private fun evaluateCompoundFilter(
        filter: CompoundFilter,
        attributes: Map<String, Any>,
        fileId: String?,
    ): Boolean =
        when (filter.type.lowercase()) {
            "and" -> filter.filters.all { matchesFilter(it, attributes, fileId) }
            "or" -> filter.filters.any { matchesFilter(it, attributes, fileId) }
            else -> {
                log.warn("Unknown compound filter type: ${filter.type}")
                false
            }
        }

    /**
     * Evaluates a comparison operation between two values.
     */
    private fun evaluateComparison(
        type: String,
        attributeValue: Any?,
        filterValue: Any,
    ): Boolean {
        if (attributeValue == null) return false
        
        return when (type.lowercase()) {
            "eq" -> attributeValue == filterValue
            "ne" -> attributeValue != filterValue
            "gt" -> compareValues(attributeValue, filterValue) > 0
            "gte" -> compareValues(attributeValue, filterValue) >= 0
            "lt" -> compareValues(attributeValue, filterValue) < 0
            "lte" -> compareValues(attributeValue, filterValue) <= 0
            else -> {
                log.warn("Unknown comparison type: $type")
                false
            }
        }
    }

    /**
     * Compares two values, handling different types appropriately.
     * Returns a negative number if v1 < v2, zero if v1 = v2, positive if v1 > v2.
     */
    @Suppress("UNCHECKED_CAST")
    private fun compareValues(
        v1: Any,
        v2: Any,
    ): Int =
        when {
            v1 is Number && v2 is Number -> {
                val d1 = v1.toDouble()
                val d2 = v2.toDouble()
                d1.compareTo(d2)
            }
            v1 is String && v2 is String -> v1.compareTo(v2)
            v1 is Boolean && v2 is Boolean -> v1.compareTo(v2)
            else -> throw IllegalArgumentException("Cannot compare ${v1.javaClass} with ${v2.javaClass}")
        }

    /**
     * Converts a Filter to a Qdrant filter for langchain4j.
     */
    fun convertToQdrantFilter(filter: Filter?): dev.langchain4j.store.embedding.filter.Filter? {
        if (filter == null) return null
        
        return when (filter) {
            is ComparisonFilter -> convertComparisonToQdrantFilter(filter)
            is CompoundFilter -> convertCompoundToQdrantFilter(filter)
            else -> {
                log.warn("Unknown filter type for Qdrant conversion: ${filter.javaClass.name}")
                null
            }
        }
    }

    /**
     * Converts a ComparisonFilter to a Qdrant filter.
     */
    private fun convertComparisonToQdrantFilter(filter: ComparisonFilter): dev.langchain4j.store.embedding.filter.Filter? {
        val key = filter.key
        val value = filter.value
        
        return when (filter.type.lowercase()) {
            "eq" -> IsEqualTo(key, value)
            "ne" -> IsNotEqualTo(key, value)
            "gt" -> {
                if (value is Comparable<*>) {
                    @Suppress("UNCHECKED_CAST")
                    IsGreaterThan(key, value as Comparable<Any>)
                } else {
                    log.warn("Value for 'gt' filter must be Comparable: ${value.javaClass}")
                    null
                }
            }
            "gte" -> {
                if (value is Comparable<*>) {
                    @Suppress("UNCHECKED_CAST")
                    IsGreaterThanOrEqualTo(key, value as Comparable<Any>)
                } else {
                    log.warn("Value for 'gte' filter must be Comparable: ${value.javaClass}")
                    null
                }
            }
            "lt" -> {
                if (value is Comparable<*>) {
                    @Suppress("UNCHECKED_CAST")
                    IsLessThan(key, value as Comparable<Any>)
                } else {
                    log.warn("Value for 'lt' filter must be Comparable: ${value.javaClass}")
                    null
                }
            }
            "lte" -> {
                if (value is Comparable<*>) {
                    @Suppress("UNCHECKED_CAST")
                    IsLessThanOrEqualTo(key, value as Comparable<Any>)
                } else {
                    log.warn("Value for 'lte' filter must be Comparable: ${value.javaClass}")
                    null
                }
            }
            else -> {
                log.warn("Unsupported comparison type for Qdrant: ${filter.type}")
                null
            }
        }
    }

    /**
     * Converts a CompoundFilter to a Qdrant filter.
     */
    private fun convertCompoundToQdrantFilter(filter: CompoundFilter): dev.langchain4j.store.embedding.filter.Filter? {
        if (filter.filters.isEmpty()) return null
        
        // Convert all child filters
        val convertedFilters = filter.filters.mapNotNull { convertToQdrantFilter(it) }
        if (convertedFilters.isEmpty()) return null
        
        // Apply the compound operation
        var result = convertedFilters.first()
        for (i in 1 until convertedFilters.size) {
            result =
                when (filter.type.lowercase()) {
                    "and" -> result.and(convertedFilters[i])
                    "or" -> result.or(convertedFilters[i])
                    else -> {
                        log.warn("Unsupported compound filter type for Qdrant: ${filter.type}")
                        return null
                    }
                }
        }
        
        return result
    }
} 
