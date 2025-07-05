package ai.masaic.openresponses.api.service.search

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Scheduler for cleaning up vector stores.
 *
 * This component periodically runs a cleanup task to ensure consistency
 * between vector stores and file storage by removing references to files
 * that no longer exist in storage.
 */
@Component
class VectorStoreCleanupScheduler(
    @Autowired private val vectorStoreService: VectorStoreService,
    @Value("\${open-responses.store.vector.search.cleanup.enabled:true}") private val cleanupEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(VectorStoreCleanupScheduler::class.java)

    /**
     * Scheduled task to clean up vector stores.
     * Runs every hour by default, but can be configured with open-responses.store.vector.search.cleanup.cron property.
     */
    @Scheduled(cron = "\${open-responses.store.vector.search.cleanup.cron:0 0 * * * ?}")
    fun cleanupVectorStores() {
        if (!cleanupEnabled) {
            log.debug("Vector store cleanup is disabled")
            return
        }

        log.debug("Starting scheduled vector store cleanup")

        runBlocking {
            try {
                val removedCount = vectorStoreService.cleanupVectorStores()
                log.info("Scheduled vector store cleanup completed: removed $removedCount file references")
            } catch (e: Exception) {
                log.error("Error during scheduled vector store cleanup", e)
            }
        }
    }
}
