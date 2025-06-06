package ai.masaic.improved.model

import java.time.Instant

/**
 * Request model for agent queries about business KPIs and graph insights.
 */
data class AgentQueryRequest(
    val query: String,
    val conversationId: String? = null,
    val includeContext: Boolean = true,
    val maxRetries: Int = 2,
    val enableVisualization: Boolean = true,
    val enablePythonAnalysis: Boolean = true,
)

/**
 * Response model for agent queries.
 */
data class AgentQueryResponse(
    val conversationId: String,
    val naturalLanguageResponse: String,
    val cypherQuery: String? = null,
    val queryResults: List<Map<String, Any>>? = null,
    val retryCount: Int = 0,
    val timestamp: Instant = Instant.now(),
    val error: String? = null,
    val visualization: VisualizationData? = null,
    val parallelVisualizations: ParallelVisualizationData? = null,
    val metadata: Map<String, Any> = emptyMap(),
)

/**
 * Enhanced visualization data for Plotly.js charts with optional Python analysis
 */
data class VisualizationData(
    val chartType: ChartType,
    val data: List<Map<String, Any>>,
    val layout: Map<String, Any>,
    val config: Map<String, Any> = mapOf("responsive" to true),
    val title: String,
    val description: String,
    val pythonAnalysis: PythonAnalysisResult? = null,
    val unitName: String? = null,
    val unitIndex: Int? = null,
)

/**
 * Result of Python-based data analysis
 */
data class PythonAnalysisResult(
    val code: String,
    val output: String,
    val variables: Map<String, Any> = emptyMap(),
    val executionTime: Long,
    val success: Boolean,
    val error: String? = null,
)

/**
 * Supported chart types for visualization
 */
enum class ChartType {
    BAR,
    LINE,
    PIE,
    SCATTER,
    HISTOGRAM,
    HEATMAP,
    TREEMAP,
    FUNNEL,
    AREA,
}

/**
 * Sealed class for different types of agent response events during streaming
 */
sealed class AgentResponseEvent(
    val type: String,
    val timestamp: Instant = Instant.now(),
) {
    data class QueryStarted(
        val query: String,
        val conversationId: String,
    ) : AgentResponseEvent("query_started")

    data class ContextBuilding(
        val message: String,
    ) : AgentResponseEvent("context_building")

    data class CypherGeneration(
        val status: String,
        val attempt: Int,
        val maxRetries: Int,
    ) : AgentResponseEvent("cypher_generation")

    data class CypherGenerated(
        val cypherQuery: String,
        val explanation: String,
        val confidence: String?,
    ) : AgentResponseEvent("cypher_generated")

    data class QueryExecution(
        val cypherQuery: String,
    ) : AgentResponseEvent("query_execution")

    data class QueryExecuted(
        val success: Boolean,
        val resultCount: Int,
        val executionTime: Long? = null,
    ) : AgentResponseEvent("query_executed")

    data class QueryRetry(
        val attempt: Int,
        val maxRetries: Int,
        val error: String,
    ) : AgentResponseEvent("query_retry")

    data class NaturalLanguageGeneration(
        val status: String,
    ) : AgentResponseEvent("natural_language_generation")

    data class NaturalLanguageGenerated(
        val naturalLanguageResponse: String,
    ) : AgentResponseEvent("natural_language_generated")

    data class VisualizationGeneration(
        val status: String,
    ) : AgentResponseEvent("visualization_generation")

    data class VisualizationGenerated(
        val chartType: ChartType,
        val title: String,
        val description: String,
    ) : AgentResponseEvent("visualization_generated")

    data class VisualizationGeneratedWithData(
        val visualization: VisualizationData,
    ) : AgentResponseEvent("visualization_generated_with_data")

    data class PythonAnalysisStarted(
        val code: String,
        val description: String,
    ) : AgentResponseEvent("python_analysis_started")

    data class PythonAnalysisCompleted(
        val output: String,
        val executionTime: Long,
        val success: Boolean,
    ) : AgentResponseEvent("python_analysis_completed")

    data class Complete(
        val response: AgentQueryResponse,
    ) : AgentResponseEvent("complete")

    data class Error(
        val error: String,
        val retryable: Boolean = false,
    ) : AgentResponseEvent("error")

    data class Progress(
        val currentStep: String,
        val progress: Float,
        val details: String,
    ) : AgentResponseEvent("progress")

    // New events for intelligent pipeline
    data class IntentAnalyzed(
        val queryType: String,
        val complexity: String,
        val expectedDataSize: String,
        val analysisRequirements: List<String>,
        val reasoning: String,
    ) : AgentResponseEvent("intent_analyzed")

    data class StrategyPlanned(
        val responseType: String,
        val requiresVisualization: Boolean,
        val visualizationType: String,
        val requiresPythonAnalysis: Boolean,
        val multiQueryStrategy: String,
        val reasoning: String,
    ) : AgentResponseEvent("strategy_planned")

    data class QueriesGenerated(
        val primaryQuery: CypherGenerationResponse,
        val supportingQueries: List<CypherGenerationResponse>,
        val queryCount: Int,
    ) : AgentResponseEvent("queries_generated")

    data class AnalysisCompleted(
        val primaryResults: List<Map<String, Any>>,
        val supportingResults: Map<String, List<Map<String, Any>>>,
        val insights: List<String>,
    ) : AgentResponseEvent("analysis_completed")

    // Enhanced events for parallel visualization processing
    data class ParallelVisualizationStarted(
        val totalUnits: Int,
        val units: List<String>,
    ) : AgentResponseEvent("parallel_visualization_started")

    data class VisualizationUnitCompleted(
        val unitName: String,
        val unitIndex: Int,
        val totalUnits: Int,
        val chartType: ChartType,
        val executionTime: Long,
        val hasPythonAnalysis: Boolean,
    ) : AgentResponseEvent("visualization_unit_completed")

    data class VisualizationUnitCompletedWithData(
        val unitName: String,
        val unitIndex: Int,
        val totalUnits: Int,
        val visualization: VisualizationData,
    ) : AgentResponseEvent("visualization_unit_completed_with_data")

    data class ParallelVisualizationCompleted(
        val totalUnits: Int,
        val successfulUnits: Int,
        val failedUnits: Int,
        val totalExecutionTime: Long,
    ) : AgentResponseEvent("parallel_visualization_completed")

    data class ParallelVisualizationCompletedWithData(
        val parallelVisualizations: ParallelVisualizationData,
    ) : AgentResponseEvent("parallel_visualization_completed_with_data")
}

/**
 * Model for LLM-generated visualization recommendations
 */
data class VisualizationRecommendation(
    val shouldVisualize: Boolean = true,
    val chartType: ChartType?,
    val title: String?,
    val description: String?,
    val reasoning: String,
    val dataTransformation: String? = null,
)

/**
 * Internal model for tracking agent conversation state.
 */
data class AgentConversation(
    val id: String,
    val messages: MutableList<AgentMessage> = mutableListOf(),
    val createdAt: Instant = Instant.now(),
    val lastUpdated: Instant = Instant.now(),
) {
    fun addMessage(message: AgentMessage) {
        messages.add(message)
    }
}

/**
 * Represents a message in an agent conversation.
 */
data class AgentMessage(
    val role: AgentRole,
    val content: String,
    val timestamp: Instant = Instant.now(),
    val metadata: Map<String, Any> = emptyMap(),
)

/**
 * Roles for agent messages.
 */
enum class AgentRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

/**
 * Context information about the conversation tree for the LLM.
 */
data class GraphContext(
    val totalConversations: Int,
    val totalPathNodes: Int,
    val totalRelationships: Int,
    val rootPaths: List<String>,
    val samplePaths: List<String>,
    val pathTreeSummary: String,
)

/**
 * Internal model for Cypher query execution result.
 */
data class CypherExecutionResult(
    val success: Boolean,
    val results: List<Map<String, Any>>? = null,
    val error: String? = null,
    val queryExecuted: String,
)

/**
 * Internal model for LLM responses that contain generated Cypher.
 */
data class CypherGenerationResponse(
    val cypherQuery: String,
    val explanation: String,
    val confidence: String? = null,
)

/**
 * Enhanced analysis type for intelligent decision making
 */
enum class AnalysisType {
    DIRECT_PLOTLY, // Simple data, direct Plotly generation
    PYTHON_ANALYSIS, // Complex analysis needed, use Python
    NO_VISUALIZATION, // No visualization beneficial
}

/**
 * Container for multiple parallel visualization units
 */
data class ParallelVisualizationData(
    val primaryVisualization: VisualizationData,
    val additionalVisualizations: List<VisualizationData> = emptyList(),
    val totalUnits: Int,
    val executionSummary: String,
)

/**
 * Specification for a parallel visualization unit
 */
data class VisualizationUnit(
    val name: String,
    val description: String,
    val analysisType: AnalysisType,
    val chartType: ChartType,
    val requiresPythonAnalysis: Boolean,
    val pythonRequirements: List<String> = emptyList(),
    val dataFilters: Map<String, Any> = emptyMap(),
    val priority: Int = 1,
)

/**
 * Request for parallel visualization analysis
 */
data class ParallelVisualizationRequest(
    val originalQuery: String,
    val cypherQuery: String,
    val queryResults: List<Map<String, Any>>,
    val naturalLanguageResponse: String,
    val visualizationUnits: List<VisualizationUnit>,
    val enablePythonAnalysis: Boolean = true,
    val maxParallelUnits: Int = 3,
)

/**
 * Enhanced intelligent analysis recommendation for parallel processing
 */
data class IntelligentAnalysisRecommendation(
    val analysisType: AnalysisType,
    val reasoning: String,
    val chartType: ChartType? = null,
    val pythonRequirements: List<String> = emptyList(),
    val complexity: String = "low",
    val recommendsParallelUnits: Boolean = false,
    val suggestedUnits: List<VisualizationUnit> = emptyList(),
    val currentDateContext: String? = null,
) 
