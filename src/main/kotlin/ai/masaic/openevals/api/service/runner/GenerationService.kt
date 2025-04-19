package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.ChatMessage
import ai.masaic.openevals.api.model.CompletionsRunDataSource
import ai.masaic.openevals.api.model.CustomDataSourceConfig
import ai.masaic.openevals.api.model.RunDataSource

/**
 * Interface for generation services that produce completions for evaluation.
 */
interface GenerationService {
    /**
     * Checks if this service can handle the generation based on the provided data source.
     *
     * @param dataSource The data source with model and parameter information
     * @return True if this service can handle the generation
     */
    fun canGenerate(dataSource: RunDataSource): Boolean

    /**
     * Generate completions for the provided message sets.
     *
     * @param completionMessagesSet Map of completion message sets indexed by identifier
     * @param dataSource The data source with model and parameter information
     * @param apiKey The API key for the generation service
     * @param dataSourceConfig Configuration for the data source
     * @return Map of completion results indexed by identifier
     */
    suspend fun generateCompletions(
        completionMessagesSet: Map<Int, List<ChatMessage>>,
        dataSource: CompletionsRunDataSource,
        apiKey: String,
        dataSourceConfig: CustomDataSourceConfig,
    ): Map<Int, CompletionResult>
}

/**
 * Data class to store completion results.
 */
data class CompletionResult(
    val contentJson: String,
    val error: String? = null,
)
