package ai.masaic.openresponses.api.config

import ai.masaic.openresponses.api.service.search.VectorStoreService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled

/**
 * Configuration for vector store expiration.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "open-responses.store.vector.expiration",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class VectorStoreExpirationConfiguration(
    private val vectorStoreService: VectorStoreService,
) {
    private val log = LoggerFactory.getLogger(VectorStoreExpirationConfiguration::class.java)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Scheduled task to check for and clean up expired vector stores.
     */
    @Scheduled(fixedDelayString = "\${open-responses.store.vector.expiration.check-interval:3600000}")
    fun checkExpiredVectorStores() {
        log.info("Starting vector store expiration check")
        backgroundScope.launch {
            try {
                val cleanedUpCount = vectorStoreService.cleanupExpiredVectorStores()
                log.info("Vector store expiration check completed: cleaned up $cleanedUpCount vector stores")
            } catch (e: Exception) {
                log.error("Error during vector store expiration check", e)
            }
        }
    }
} 
