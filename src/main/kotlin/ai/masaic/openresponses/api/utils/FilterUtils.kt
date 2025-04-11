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
        
        val result =
            when (filter) {
                is ComparisonFilter -> {
                    val matches = evaluateComparisonFilter(filter, attributes, fileId)
                    log.debug("Comparison filter $filter result: $matches")
                    matches
                }
                is CompoundFilter -> {
                    val matches = evaluateCompoundFilter(filter, attributes, fileId)
                    log.debug("Compound filter $filter result: $matches")
                    matches
                }
                else -> {
                    log.warn("Unknown filter type: ${filter.javaClass.name}")
                    false
                }
            }
        
        return result
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
            val matches = evaluateComparison(filter.type, fileId, value)
            log.debug("file_id filter $filter with value=$fileId, result: $matches")
            return matches
        }
        
        // Check if the attribute exists
        if (!attributes.containsKey(key)) {
            log.debug("Key $key not found in attributes: $attributes")
            return false
        }
        
        val attrValue = attributes[key]
        val matches = evaluateComparison(filter.type, attrValue, value)
        log.debug("Attribute filter $filter with attr value=$attrValue, result: $matches")
        return matches
    }

    /**
     * Evaluates a compound filter (AND/OR) against entity attributes.
     */
    private fun evaluateCompoundFilter(
        filter: CompoundFilter,
        attributes: Map<String, Any>,
        fileId: String?,
    ): Boolean {
        val results =
            when (filter.type.lowercase()) {
                "and" -> {
                    val matches =
                        filter.filters.all { 
                            val result = matchesFilter(it, attributes, fileId)
                            log.debug("AND filter component $it result: $result")
                            result
                        }
                    log.debug("AND filter overall result: $matches")
                    matches
                }
                "or" -> {
                    val matches =
                        filter.filters.any { 
                            val result = matchesFilter(it, attributes, fileId)
                            log.debug("OR filter component $it result: $result")
                            result
                        }
                    log.debug("OR filter overall result: $matches")
                    matches
                }
                else -> {
                    log.warn("Unknown compound filter type: ${filter.type}")
                    false
                }
            }
        
        return results
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
        
        log.debug("Converting filter to Qdrant filter: $filter")
        
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
        
        log.debug("Converting comparison filter: $filter with value type: ${value.javaClass.name}")
        
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
        
        log.debug("Converting compound filter: $filter with ${filter.filters.size} inner filters")
        
        // Convert all child filters
        val convertedFilters = filter.filters.mapNotNull { convertToQdrantFilter(it) }
        if (convertedFilters.isEmpty()) return null
        
        log.debug("After conversion, ${convertedFilters.size} valid filters remain")
        
        // Apply the compound operation
        var result = convertedFilters.first()
        for (i in 1 until convertedFilters.size) {
            result =
                when (filter.type.lowercase()) {
                    "and" -> {
                        log.debug("Applying AND filter at index $i")
                        result.and(convertedFilters[i])
                    }
                    "or" -> {
                        log.debug("Applying OR filter at index $i")
                        result.or(convertedFilters[i])
                    }
                    else -> {
                        log.warn("Unsupported compound filter type for Qdrant: ${filter.type}")
                        return null
                    }
                }
        }
        
        return result
    }
} 
