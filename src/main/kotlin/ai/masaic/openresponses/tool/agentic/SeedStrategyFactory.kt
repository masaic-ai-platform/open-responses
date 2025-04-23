package ai.masaic.openresponses.tool.agentic

import ai.masaic.openresponses.api.service.search.HybridSearchService
import ai.masaic.openresponses.api.service.search.VectorStoreService

class SeedStrategyFactory(
    store: VectorStoreService,
    hybrid: HybridSearchService,
) {
    private val default = HybridSimilaritySeed(hybrid)
    private val hybridSeed = HybridSimilaritySeed(hybrid)
    private val topKSimilaritySeed = TopKSimilaritySeed(store)

    fun byName(name: String?) =
        when (name?.lowercase()) {
            "topk" -> topKSimilaritySeed
            "hybrid" -> hybridSeed
            else -> default // add more strategies here
        }
} 
