package ai.masaic.improved.controller

import ai.masaic.improved.model.AgentConversation
import ai.masaic.improved.model.AgentQueryRequest
import ai.masaic.improved.model.AgentQueryResponse
import ai.masaic.improved.service.AgentQueryService
import ai.masaic.improved.service.AgentQueryStreamingService
import ai.masaic.improved.service.QueryAnalysisService
import ai.masaic.improved.service.VisualizationExecutorService
import ai.masaic.improved.service.UnifiedAgentPipelineService
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
 * 1. Unified query processing with intelligent analysis and visualization
 * 2. Legacy streaming and standard query support for backward compatibility
 * 3. Managing agent conversations and retrieving conversation history
 * 4. Service health checks and information
 */
@RestController
@RequestMapping("/api/v1/agent")
@CrossOrigin(origins = ["*"], allowedHeaders = ["*"])
class AgentQueryController(
    private val agentQueryService: AgentQueryService,
    private val agentQueryStreamingService: AgentQueryStreamingService,
    private val queryAnalysisService: QueryAnalysisService,
    private val visualizationExecutorService: VisualizationExecutorService,
    private val unifiedAgentPipelineService: UnifiedAgentPipelineService,
    @Value("\${openai.api.key}")
    private val apiKey: String,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * PRIMARY: Unified pipeline for agent queries with intelligent analysis and visualization.
     * 
     * This is the main endpoint that provides:
     * - Comprehensive query analysis with strategy determination
     * - Predictable visualization execution without fallbacks
     * - Multiple visualization support when appropriate
     * - Clean separation between analysis and execution
     * - Real-time progress updates
     *
     * @param request The agent query request containing the user's question
     * @return Flow of ServerSentEvents with unified processing
     */
    @PostMapping("/query", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun queryAgent(
        @RequestBody request: AgentQueryRequest,
    ): Flow<ServerSentEvent<String>> {
        logger.info { "Received agent query: ${request.query}" }
        
        return try {
            unifiedAgentPipelineService.processQuery(request, "Bearer $apiKey")
        } catch (e: Exception) {
            logger.error(e) { "Error in agent query: ${e.message}" }
            throw e
        }
    }

    /**
     * LEGACY: Streaming endpoint for agent queries with real-time progress updates.
     * Maintained for backward compatibility.
     */
    @PostMapping("/query/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun queryAgentStream(
        @RequestBody request: AgentQueryRequest,
    ): Flow<ServerSentEvent<String>> {
        logger.info { "Received streaming agent query: ${request.query}" }
        
        return try {
            agentQueryStreamingService.processQueryStream(request, "Bearer $apiKey")
        } catch (e: Exception) {
            logger.error(e) { "Error in streaming agent query: ${e.message}" }
            throw e
        }
    }

    /**
     * LEGACY: Standard endpoint for agent queries (backward compatibility).
     * Returns a single response instead of streaming.
     */
    @PostMapping("/query/standard")
    suspend fun queryAgentStandard(
        @RequestBody request: AgentQueryRequest,
    ): ResponseEntity<AgentQueryResponse> {
        logger.info { "Received standard agent query: ${request.query}" }
        
        return try {
            val response = agentQueryService.processQuery(request, "Bearer $apiKey")
            
            if (response.error != null) {
                logger.warn { "Agent query completed with error: ${response.error}" }
            } else {
                logger.info { "Agent query completed successfully for conversation: ${response.conversationId}" }
            }
            
            ResponseEntity.ok(response)
        } catch (e: Exception) {
            logger.error(e) { "Error processing agent query: ${e.message}" }
            
            val errorResponse =
                AgentQueryResponse(
                    conversationId = request.conversationId ?: "error",
                    naturalLanguageResponse = "I encountered an unexpected error while processing your request. Please try again later.",
                    error = "Internal server error: ${e.message}",
                )
            
            ResponseEntity.internalServerError().body(errorResponse)
        }
    }

    /**
     * Get conversation history for a specific agent conversation.
     * Works with both unified and legacy conversations.
     */
    @GetMapping("/conversations/{conversationId}")
    fun getConversationHistory(
        @PathVariable conversationId: String,
    ): ResponseEntity<AgentConversation> {
        logger.debug { "Retrieving conversation history for: $conversationId" }
        
        // Try to get from unified service first, then fallback to other services
        val conversation =
            unifiedAgentPipelineService.getConversationHistory(conversationId)
                ?: agentQueryStreamingService.getConversationHistory(conversationId)
                ?: agentQueryService.getConversationHistory(conversationId)
        
        return if (conversation != null) {
            ResponseEntity.ok(conversation)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Health check endpoint for the agent service.
     */
    @GetMapping("/health")
    fun healthCheck(): ResponseEntity<Map<String, String>> =
        ResponseEntity.ok(
            mapOf(
                "status" to "Agent service is available",
                "service" to "UnifiedAgentPipelineService",
                "features" to "Unified pipeline, Intelligent analysis, Multiple visualizations, Streaming responses",
            ),
        )

    /**
     * Get agent service statistics and information.
     */
    @GetMapping("/info")
    fun getAgentInfo(): ResponseEntity<Map<String, Any>> {
        // Trigger cleanup of old conversations in services
        agentQueryService.cleanupOldConversations()
        agentQueryStreamingService.cleanupOldConversations()
        unifiedAgentPipelineService.cleanupOldConversations()
        
        return ResponseEntity.ok(
            mapOf(
                "name" to "Business Intelligence Agent",
                "description" to "AI agent for analyzing conversation data using natural language queries",
                "capabilities" to
                    listOf(
                        "Natural language to Cypher query translation",
                        "Business KPI analysis and insights",
                        "Conversation data exploration", 
                        "Error handling and query retry logic",
                        "Contextual conversation memory",
                        "Real-time streaming responses",
                        "Intelligent visualization generation",
                        "Multiple visualization support",
                        "Plotly.js chart generation",
                        "Progress tracking and updates",
                        "Unified pipeline architecture",
                    ),
                "endpoints" to
                    mapOf(
                        "query" to "POST /api/v1/agent/query - PRIMARY: Unified pipeline with intelligent analysis and visualization",
                        "queryStream" to "POST /api/v1/agent/query/stream - LEGACY: Streaming query with real-time updates",
                        "queryStandard" to "POST /api/v1/agent/query/standard - LEGACY: Standard query processing",
                        "analyze" to "POST /api/v1/agent/analyze - Direct query analysis without execution",
                        "conversations" to "GET /api/v1/agent/conversations/{id} - Get conversation history",
                        "health" to "GET /api/v1/agent/health - Service health check",
                        "info" to "GET /api/v1/agent/info - Service information",
                        "cleanup" to "POST /api/v1/agent/cleanup - Manual conversation cleanup",
                    ),
                "supportedQuestions" to
                    listOf(
                        "How many conversations do we have in total?",
                        "What are the top conversation categories?", 
                        "Show me the NPS distribution",
                        "How many resolved vs unresolved conversations?",
                        "Which domains have the most conversations?",
                        "What's the average NPS score by category?",
                        "Show me conversation trends over time",
                        "What are the busiest hours for conversations?",
                    ),
                "visualizationSupport" to
                    mapOf(
                        "enabled" to true,
                        "chartTypes" to listOf("BAR", "LINE", "PIE", "SCATTER", "HISTOGRAM", "HEATMAP", "TREEMAP", "FUNNEL", "AREA"),
                        "framework" to "Plotly.js",
                        "features" to listOf("Interactive charts", "Responsive design", "Professional styling", "Multiple visualizations", "Python analysis integration"),
                        "strategies" to listOf("TEXT_ONLY", "TEXT_WITH_SIMPLE_VISUAL", "TEXT_WITH_PYTHON_VISUAL", "TEXT_WITH_MULTIPLE_VISUALS", "COMPREHENSIVE_ANALYSIS"),
                    ),
                "graphSchema" to
                    mapOf(
                        "nodes" to
                            listOf(
                                mapOf(
                                    "label" to "Conversation",
                                    "properties" to listOf("id", "createdAt", "summary", "resolved", "nps", "version", "classification", "messageCount"),
                                ),
                                mapOf(
                                    "label" to "PathNode", 
                                    "properties" to listOf("path", "name"),
                                ),
                            ),
                        "relationships" to
                            listOf(
                                mapOf("type" to "HAS_CHILD", "description" to "PathNode to PathNode hierarchy"),
                                mapOf("type" to "CONTAINS", "description" to "PathNode contains Conversation"),
                            ),
                    ),
            ),
        )
    }

    /**
     * Manual cleanup endpoint for old conversations.
     * Useful for administration and memory management.
     */
    @PostMapping("/cleanup")
    suspend fun cleanupConversations(
        @RequestParam(defaultValue = "24") maxAgeHours: Int,
    ): ResponseEntity<Map<String, Any>> {
        agentQueryService.cleanupOldConversations(maxAgeHours)
        agentQueryStreamingService.cleanupOldConversations(maxAgeHours)
        unifiedAgentPipelineService.cleanupOldConversations(maxAgeHours)
        
        return ResponseEntity.ok(
            mapOf(
                "message" to "Cleanup completed for all services",
                "maxAgeHours" to maxAgeHours,
            ),
        )
    }

    /**
     * Direct query analysis endpoint for testing and debugging.
     * 
     * This endpoint provides:
     * - Direct access to query analysis without execution
     * - Strategy determination and reasoning
     * - Visualization potential assessment
     * - Useful for debugging and understanding query analysis
     */
    @PostMapping("/analyze")
    suspend fun analyzeQuery(
        @RequestBody request: AgentQueryRequest,
    ): ResponseEntity<Map<String, Any>> {
        logger.info { "Received query analysis request: ${request.query}" }
        
        return try {
            // Create a temporary conversation for analysis
            val conversation = AgentConversation(id = "temp_analysis")
            
            val analysis = queryAnalysisService.analyzeQuery(
                query = request.query,
                conversation = conversation,
                queryResults = emptyList(), // No prior results for analysis
                apiKey = "Bearer $apiKey"
            )
            
            ResponseEntity.ok(
                mapOf(
                    "query" to request.query,
                    "analysis" to analysis,
                    "timestamp" to java.time.Instant.now(),
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "Error analyzing query: ${e.message}" }
            ResponseEntity.internalServerError().body(
                mapOf(
                    "error" to "Analysis failed: ${e.message}",
                    "query" to request.query,
                )
            )
        }
    }
}
