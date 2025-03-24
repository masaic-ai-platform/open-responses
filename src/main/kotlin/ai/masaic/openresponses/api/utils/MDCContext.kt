package ai.masaic.openresponses.api.utils

import org.slf4j.MDC
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility class to store and retrieve context values for logging
 * across thread boundaries, particularly useful for async operations.
 * This complements MDC by providing access to context values when
 * working with different thread pools.
 */
object MDCContext {
    private val threadLocalContext = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    
    /**
     * Stores a value in the context for the current thread
     * 
     * @param key The context key
     * @param value The value to store
     */
    fun put(key: String, value: String) {
        val threadId = Thread.currentThread().threadId().toString()
        val contextMap = threadLocalContext.computeIfAbsent(threadId) { ConcurrentHashMap() }
        contextMap[key] = value
    }
    
    /**
     * Retrieves a value from the context for the current thread
     * 
     * @param key The context key
     * @return The stored value or null if not found
     */
    fun get(key: String): String? {
        val threadId = Thread.currentThread().threadId().toString()
        val value = threadLocalContext[threadId]?.get(key)
        
        // If not in our cache, try MDC
        return value ?: MDC.get(key)
    }
    
    /**
     * Copies all context values from this thread to MDC.
     * Call this at the beginning of new thread execution.
     */
    fun copyToMDC() {
        val threadId = Thread.currentThread().threadId().toString()
        threadLocalContext[threadId]?.forEach { (key, value) ->
            MDC.put(key, value)
        }
    }
    
    /**
     * Clears all context values for the current thread
     */
    fun clear() {
        val threadId = Thread.currentThread().threadId().toString()
        threadLocalContext.remove(threadId)
        MDC.clear()
    }
} 