package ai.masaic.openresponses.api.service.rerank

import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component

/**
 * Re‑orders a candidate list so that docs most relevant to the query come first.
 * Implementations **must** preserve the list size and return a new list.
 */
interface RerankerService {
    suspend fun rerank(
        query: String,
        docs: List<VectorStoreSearchResult>,
        k: Int = docs.size, // rerank only top‑k if desired
    ): List<VectorStoreSearchResult>
}

@Component
@ConditionalOnMissingBean(RerankerService::class)
class NoopRerankerService : RerankerService {
    private val logger = KotlinLogging.logger {}

    init {
        logger.info { "NoopRerankerService enabled..." }
    }

    override suspend fun rerank(
        query: String,
        docs: List<VectorStoreSearchResult>,
        k: Int,
    ): List<VectorStoreSearchResult> {
        // Nothing to do
        return docs
    }
}
