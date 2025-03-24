package ai.masaic.openresponses.api.utils

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import kotlin.coroutines.CoroutineContext

/**
 * Utility class to store and retrieve context values for logging
 * across coroutine boundaries, particularly useful for async operations.
 * This complements MDC by providing proper context propagation in coroutines.
 */
object MDCContext {
    /**
     * Creates a coroutine context element that carries MDC data.
     * Use this when creating a new coroutine scope to ensure MDC values are properly propagated.
     *
     * @return CoroutineContext with MDC values
     */
    fun asCoroutineContext(): CoroutineContext = MDCContext()
    
    /**
     * Stores a value in MDC
     * 
     * @param key The context key
     * @param value The value to store
     */
    fun put(key: String, value: String) {
        MDC.put(key, value)
    }
    
    /**
     * Retrieves a value from MDC
     * 
     * @param key The context key
     * @return The stored value or null if not found
     */
    fun get(key: String): String? = MDC.get(key)
    
    /**
     * Clears all MDC values
     */
    fun clear() {
        MDC.clear()
    }

    /**
     * Executes the given suspending block with MDC context and ensures cleanup.
     * This is the preferred way to handle MDC in coroutines as it ensures proper cleanup.
     *
     * @param context Additional coroutine context elements (optional)
     * @param block The suspending block to execute
     */
    suspend fun <T> withMDC(
        vararg context: CoroutineContext,
        block: suspend () -> T
    ): T {
        val mdcContext = asCoroutineContext()
        val combinedContext = context.fold(mdcContext) { acc, ctx -> acc + ctx }
        
        return try {
            withContext(combinedContext) {
                block()
            }
        } finally {
            clear()
        }
    }

    /**
     * Executes a non-suspending block with MDC context and ensures cleanup.
     * Use this version for regular callbacks and non-coroutine code.
     *
     * @param block The block to execute
     */
    fun <T> withMDCSync(block: () -> T): T {
        // Store the current MDC context to restore it later
        val previousContext = MDC.getCopyOfContextMap()
        
        return try {
            block()
        } finally {
            // Restore the previous context or clear if there wasn't one
            if (previousContext != null) {
                MDC.setContextMap(previousContext)
            } else {
                MDC.clear()
            }
        }
    }
} 