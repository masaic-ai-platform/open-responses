package ai.masaic.openresponses.tool.agentic

import ai.masaic.openresponses.api.model.Filter
import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import ai.masaic.platform.api.config.ModelSettings

interface SeedStrategy {
    suspend fun seed(
        query: String,
        maxResults: Int,
        userFilter: Filter?,
        vectorStoreIds: List<String>,
        additionalParams: Map<String, Any>,
        modelSettings: ModelSettings?,
    ): List<VectorStoreSearchResult>
} 
