package ai.masaic.openresponses.tool.agentic

import ai.masaic.openresponses.api.model.*
import ai.masaic.openresponses.api.service.search.VectorStoreService
import ai.masaic.platform.api.config.ModelSettings
import kotlinx.coroutines.*

class TopKSimilaritySeed(
    private val svc: VectorStoreService,
) : SeedStrategy {
    override suspend fun seed(
        query: String,
        maxResults: Int,
        userFilter: Filter?,
        vectorStoreIds: List<String>,
        additionalParams: Map<String, Any>,
        modelSettings: ModelSettings?,
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
