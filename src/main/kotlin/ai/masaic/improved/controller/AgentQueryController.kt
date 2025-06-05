package ai.masaic.improved.controller

import ai.masaic.improved.model.AgentQueryRequest
import ai.masaic.improved.model.AgentQueryResponse
import ai.masaic.improved.model.AgentConversation
import ai.masaic.improved.service.AgentQueryService
import ai.masaic.improved.service.AgentQueryStreamingService
import ai.masaic.improved.service.VisualizationService
import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*

/**
 * Controller for the "talk to your agent" functionality.
 * 
 * This controller provides endpoints for:
 * 1. Querying the agent with business KPI questions (both streaming and non-streaming)
 * 2. Managing agent conversations
 * 3. Retrieving conversation history
 * 4. Real-time streaming responses with visualization support
 */
@RestController
@RequestMapping("/api/v1/agent")
@CrossOrigin(origins = ["*"], allowedHeaders = ["*"])
class AgentQueryController(
    private val agentQueryService: AgentQueryService,
    private val agentQueryStreamingService: AgentQueryStreamingService,
    private val visualizationService: VisualizationService,
    @Value("\${openai.api.key}")
    private val apiKey: String
) {
    private val logger = KotlinLogging.logger {}

    /**
     * NEW: Streaming endpoint for agent queries with real-time progress updates.
     * 
     * This endpoint provides:
     * - Real-time progress updates during query processing
     * - Visualization generation and recommendations  
     * - Better UX with immediate feedback
     * - Support for progress tracking
     *
     * @param request The agent query request containing the user's question
     * @param apiKey Authorization header containing the API key for LLM calls
     * @return Flow of ServerSentEvents with real-time progress and final response
     */
    @PostMapping("/query/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun queryAgentStream(
        @RequestBody request: AgentQueryRequest
    ): Flow<ServerSentEvent<String>> {
        logger.info { "Received streaming agent query: ${request.query}" }
        
        return try {
            agentQueryStreamingService.processQueryStream(request,"Bearer $apiKey")
        } catch (e: Exception) {
            logger.error(e) { "Error in streaming agent query: ${e.message}" }
            throw e
        }
    }

    /**
     * EXISTING: Main endpoint for agent queries (backward compatibility).
     * 
     * Users can ask business KPI related questions about the conversation data.
     * The agent will generate Cypher queries, execute them against the graph database,
     * and return natural language responses.
     *
     * @param request The agent query request containing the user's question
     * @param apiKey Authorization header containing the API key for LLM calls
     * @return Agent response with natural language answer and optional debug info
     */
    @PostMapping("/query")
    suspend fun queryAgent(
        @RequestBody request: AgentQueryRequest
    ): ResponseEntity<AgentQueryResponse> {
        logger.info { "Received agent query: ${request.query}" }
        
        return try {
            val response = agentQueryService.processQuery(request, apiKey)
            
            if (response.error != null) {
                logger.warn { "Agent query completed with error: ${response.error}" }
            } else {
                logger.info { "Agent query completed successfully for conversation: ${response.conversationId}" }
            }
            
            ResponseEntity.ok(response)
            
        } catch (e: Exception) {
            logger.error(e) { "Error processing agent query: ${e.message}" }
            
            val errorResponse = AgentQueryResponse(
                conversationId = request.conversationId ?: "error",
                naturalLanguageResponse = "I encountered an unexpected error while processing your request. Please try again later.",
                error = "Internal server error: ${e.message}"
            )
            
            ResponseEntity.internalServerError().body(errorResponse)
        }
    }

    /**
     * Get conversation history for a specific agent conversation.
     * Works with both streaming and non-streaming conversations.
     *
     * @param conversationId The ID of the conversation to retrieve
     * @return The conversation history including all messages
     */
    @GetMapping("/conversations/{conversationId}")
    fun getConversationHistory(
        @PathVariable conversationId: String
    ): ResponseEntity<AgentConversation> {
        logger.debug { "Retrieving conversation history for: $conversationId" }
        
        // Try to get from streaming service first, then fallback to regular service
        val conversation = agentQueryStreamingService.getConversationHistory(conversationId)
            ?: agentQueryService.getConversationHistory(conversationId)
        
        return if (conversation != null) {
            ResponseEntity.ok(conversation)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Health check endpoint for the agent service.
     *
     * @return Simple status message indicating the agent is available
     */
    @GetMapping("/health")
    fun healthCheck(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf(
            "status" to "Agent service is available",
            "service" to "AgentQueryService",
            "features" to "Cypher generation, Natural language responses, Error retry, Streaming, Visualization"
        ))
    }

    /**
     * Get agent service statistics and information.
     *
     * @return Information about the agent's capabilities and current state
     */
    @GetMapping("/info")
    fun getAgentInfo(): ResponseEntity<Map<String, Any>> {
        // Trigger cleanup of old conversations in both services
        agentQueryService.cleanupOldConversations()
        agentQueryStreamingService.cleanupOldConversations()
        
        return ResponseEntity.ok(mapOf(
            "name" to "Business Intelligence Agent",
            "description" to "AI agent for analyzing conversation data using natural language queries",
            "capabilities" to listOf(
                "Natural language to Cypher query translation",
                "Business KPI analysis and insights",
                "Conversation data exploration", 
                "Error handling and query retry logic",
                "Contextual conversation memory",
                "Real-time streaming responses",
                "Automatic visualization generation",
                "Plotly.js chart generation",
                "Progress tracking and updates"
            ),
            "endpoints" to mapOf(
                "query" to "POST /api/v1/agent/query - Standard query processing",
                "queryStream" to "POST /api/v1/agent/query/stream - Streaming query with real-time updates",
                "conversations" to "GET /api/v1/agent/conversations/{id} - Get conversation history",
                "health" to "GET /api/v1/agent/health - Service health check",
                "info" to "GET /api/v1/agent/info - Service information",
                "cleanup" to "POST /api/v1/agent/cleanup - Manual conversation cleanup"
            ),
            "supportedQuestions" to listOf(
                "How many conversations do we have in total?",
                "What are the top conversation categories?", 
                "Show me the NPS distribution",
                "How many resolved vs unresolved conversations?",
                "Which domains have the most conversations?",
                "What's the average NPS score by category?",
                "Show me conversation trends over time",
                "What are the busiest hours for conversations?"
            ),
            "visualizationSupport" to mapOf(
                "enabled" to true,
                "chartTypes" to listOf("BAR", "LINE", "PIE", "SCATTER", "HISTOGRAM", "HEATMAP", "TREEMAP", "FUNNEL", "AREA"),
                "framework" to "Plotly.js",
                "features" to listOf("Interactive charts", "Responsive design", "Professional styling", "Accessibility support")
            ),
            "graphSchema" to mapOf(
                "nodes" to listOf(
                    mapOf(
                        "label" to "Conversation",
                        "properties" to listOf("id", "createdAt", "summary", "resolved", "nps", "version", "classification", "messageCount")
                    ),
                    mapOf(
                        "label" to "PathNode", 
                        "properties" to listOf("path", "name")
                    )
                ),
                "relationships" to listOf(
                    mapOf("type" to "HAS_CHILD", "description" to "PathNode to PathNode hierarchy"),
                    mapOf("type" to "CONTAINS", "description" to "PathNode contains Conversation")
                )
            )
        ))
    }

    /**
     * Manual cleanup endpoint for old conversations.
     * Useful for administration and memory management.
     *
     * @param maxAgeHours Maximum age in hours for conversations to keep (default: 24)
     * @return Result of the cleanup operation
     */
    @PostMapping("/cleanup")
    suspend fun cleanupConversations(
        @RequestParam(defaultValue = "24") maxAgeHours: Int
    ): ResponseEntity<Map<String, Any>> {
        agentQueryService.cleanupOldConversations(maxAgeHours)
        return ResponseEntity.ok(mapOf(
            "message" to "Cleanup completed",
            "maxAgeHours" to maxAgeHours
        ))
    }
    
    /**
     * Debug endpoint to test visualization generation with sample data
     */
    @PostMapping("/debug/visualization")
    suspend fun debugVisualization(): ResponseEntity<Map<String, Any>> {

        // Sample data similar to your conversation count query
        val sampleResults = listOf(
            mapOf("last_month_conversations" to 394)
        )
        
        try {
            logger.info { "Testing visualization with sample data..." }
            
            val recommendation = visualizationService.analyzeForVisualization(
                originalQuery = "How many conversations were created last month?",
                cypherQuery = "MATCH (c:Conversation) WHERE c.createdAt >= '2025-05-01T00:00:00Z' AND c.createdAt < '2025-06-01T00:00:00Z' RETURN count(c) as last_month_conversations",
                queryResults = sampleResults,
                apiKey = apiKey
            )
            
            logger.info { "Debug: Recommendation = $recommendation" }
            
            val visualization = if (recommendation.shouldVisualize && recommendation.chartType != null) {
                visualizationService.generateVisualization(
                    recommendation = recommendation,
                    queryResults = sampleResults,
                    originalQuery = "How many conversations were created last month?",
                    apiKey = apiKey
                )
            } else null
            
            logger.info { "Debug: Visualization = ${if (visualization != null) "SUCCESS" else "NULL"}" }
            
            return ResponseEntity.ok(mapOf(
                "recommendation" to recommendation,
                "visualization" to visualization,
                "sampleData" to sampleResults
            )) as ResponseEntity<Map<String, Any>>
            
        } catch (e: Exception) {
            logger.error(e) { "Error in debug visualization: ${e.message}" }
            return ResponseEntity.ok(mapOf(
                "error" to e.message,
                "sampleData" to sampleResults
            )) as ResponseEntity<Map<String, Any>>
        }
    }
}
