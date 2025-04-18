package ai.masaic.openresponses.tool.agentic

import ai.masaic.openresponses.api.model.*
import ai.masaic.openresponses.api.service.search.VectorStoreService
import kotlinx.coroutines.*

class TopKSimilaritySeed(
    private val svc: VectorStoreService,
) : SeedStrategy {
    override suspend fun seed(
        query: String,
        maxResults: Int,
        userFilter: Filter?,
        vectorStoreIds: List<String>,
    ) = coroutineScope {
        vectorStoreIds
            .map { id ->
                async {
                    val request =
                        VectorStoreSearchRequest(
                            query = query,
                            maxNumResults = maxResults.toInt(),
                            filters = userFilter,
                        )
                    svc.searchVectorStore(id, request).data
                }
            }.awaitAll()
            .flatten()
            .sortedByDescending { it.score }
            .take(maxResults)
    }
} 
