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
 * Intelligent analysis recommendation
 */
data class IntelligentAnalysisRecommendation(
    val analysisType: AnalysisType,
    val reasoning: String,
    val chartType: ChartType? = null,
    val pythonRequirements: List<String> = emptyList(),
    val complexity: String = "low", // low, medium, high
) 
