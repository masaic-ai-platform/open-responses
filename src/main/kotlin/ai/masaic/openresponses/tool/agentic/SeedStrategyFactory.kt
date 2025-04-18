package ai.masaic.openresponses.tool.agentic

import ai.masaic.openresponses.api.service.search.VectorStoreService

class SeedStrategyFactory(
    store: VectorStoreService,
) {
    private val default = TopKSimilaritySeed(store)

    fun byName(name: String?) =
        when (name?.lowercase()) {
            "topk" -> default
            else -> default // add more strategies here
        }
} 
