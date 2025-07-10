package ai.masaic.platform.api.controller

import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.util.context.Context
import java.util.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Custom coroutine context element to store user ID
 */
data class UserIdContext(val userId: String) : AbstractCoroutineContextElement(UserIdContext) {
    companion object Key : CoroutineContext.Key<UserIdContext>
}

@Component
class PlatformUserInterceptor : WebFilter {
    private val logger = KotlinLogging.logger {}

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        // Generate a random UUID for the user ID
        val userId = UUID.randomUUID().toString()
        
        logger.info("PlatformUserInterceptor: Set user ID {} for request {}", userId, exchange.request.uri.path)
        
        // Add user ID to the Reactor context so it can be accessed by coroutines
        return chain.filter(exchange)
            .contextWrite { context ->
                context.put("USER_ID", userId)
            }
    }
} 