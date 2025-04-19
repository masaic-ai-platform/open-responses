package ai.masaic.openresponses.tool.agentic

import ai.masaic.openresponses.api.model.Filter
import ai.masaic.openresponses.api.service.search.HybridSearchService
import kotlinx.coroutines.coroutineScope

class HybridSimilaritySeed(
    private val svc: HybridSearchService,
) : SeedStrategy {
    override suspend fun seed(
        query: String,
        maxResults: Int,
        userFilter: Filter?,
        vectorStoreIds: List<String>,
    ) = coroutineScope {
        svc.hybridSearch(
            query = query,
            maxResults = maxResults.toInt(),
            userFilter = userFilter,
            vectorStoreIds = vectorStoreIds,
        )
    }
}
