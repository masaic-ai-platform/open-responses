package ai.masaic.openresponses.api.utils

import kotlinx.coroutines.ThreadContextElement
import org.slf4j.MDC
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A coroutine context element that manages MDC context across coroutine boundaries.
 * This ensures MDC values are properly propagated when coroutines switch threads.
 *
 * Usage:
 * ```
 * withContext(CoroutineMDCContext(mapOf("traceId" to traceId))) {
 *   // Your coroutine code here
 * }
 * ```
 */
class CoroutineMDCContext(
    private val contextMap: Map<String, String>,
) : AbstractCoroutineContextElement(Key),
    ThreadContextElement<Map<String, String>> {
    companion object Key : CoroutineContext.Key<CoroutineMDCContext>

    /**
     * Store the current MDC state before switching context
     */
    override fun updateThreadContext(context: CoroutineContext): Map<String, String> {
        val oldState = MDC.getCopyOfContextMap() ?: emptyMap()

        // Update MDC with our values
        contextMap.forEach { (key, value) ->
            MDC.put(key, value)
        }

        return oldState
    }

    /**
     * Restore the previous MDC state after switching context
     */
    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Map<String, String>,
    ) {
        // Clear all values first
        MDC.clear()

        // Restore previous values
        oldState.forEach { (key, value) ->
            MDC.put(key, value)
        }
    }
} 
