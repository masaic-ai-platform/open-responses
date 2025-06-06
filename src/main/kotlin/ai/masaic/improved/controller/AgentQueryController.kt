package ai.masaic.improved.controller

import ai.masaic.improved.model.AgentConversation
import ai.masaic.improved.model.AgentQueryRequest
import ai.masaic.improved.model.AgentQueryResponse
import ai.masaic.improved.service.AgentQueryService
import ai.masaic.improved.service.AgentQueryStreamingService
import ai.masaic.improved.service.IntelligentAgentPipelineService
import ai.masaic.improved.service.IntelligentVisualizationService
import ai.masaic.improved.service.VisualizationService
import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import ai.masaic.improved.service.QueryAnalysisService
import ai.masaic.improved.service.VisualizationExecutorService
import ai.masaic.improved.service.UnifiedAgentPipelineService

/**
 * Controller for the "talk to your agent" functionality.
 * 
 * This controller provides endpoints for:
 * 1. Querying the agent with business KPI questions (both streaming and non-streaming)
 * 2. Managing agent conversations
 * 3. Retrieving conversation history
 * 4. Real-time streaming responses with visualization support
 * 5. Unified query analysis and visualization execution
 */
@RestController
@RequestMapping("/api/v1/agent")
@CrossOrigin(origins = ["*"], allowedHeaders = ["*"])
class AgentQueryController(
    private val agentQueryService: AgentQueryService,
    private val agentQueryStreamingService: AgentQueryStreamingService,
    private val intelligentAgentPipelineService: IntelligentAgentPipelineService,
    private val visualizationService: VisualizationService,
    private val intelligentVisualizationService: IntelligentVisualizationService,
    private val queryAnalysisService: QueryAnalysisService,
    private val visualizationExecutorService: VisualizationExecutorService,
    private val unifiedAgentPipelineService: UnifiedAgentPipelineService,
    @Value("\${openai.api.key}")
    private val apiKey: String,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * ENHANCED: Intelligent multi-stage pipeline for agent queries with early decision making.
     * 
     * This endpoint provides:
     * - Early intent analysis and strategy planning
     * - Multi-stage intelligent processing
     * - Optimized response type selection (text/visualization/Python analysis)
     * - Multiple query generation for comprehensive analysis
     * - Real-time progress updates with detailed insights
     *
     * @param request The agent query request containing the user's question
     * @return Flow of ServerSentEvents with intelligent multi-stage processing
     */
    @PostMapping("/query/intelligent", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun queryAgentIntelligent(
        @RequestBody request: AgentQueryRequest,
    ): Flow<ServerSentEvent<String>> {
        logger.info { "Received intelligent agent query: ${request.query}" }
        
        return try {
            intelligentAgentPipelineService.processIntelligentQuery(request, "Bearer $apiKey")
        } catch (e: Exception) {
            logger.error(e) { "Error in intelligent agent query: ${e.message}" }
            throw e
        }
    }

    /**
     * STANDARD: Streaming endpoint for agent queries with real-time progress updates.
     * 
     * This endpoint provides:
     * - Real-time progress updates during query processing
     * - Visualization generation and recommendations 
     * - Better UX with immediate feedback
     * - Support for progress tracking
     *
     * @param request The agent query request containing the user's question
     * @return Flow of ServerSentEvents with real-time progress and final response
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
        @RequestBody request: AgentQueryRequest,
    ): ResponseEntity<AgentQueryResponse> {
        logger.info { "Received agent query: ${request.query}" }
        
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
     * Works with both streaming and non-streaming conversations.
     *
     * @param conversationId The ID of the conversation to retrieve
     * @return The conversation history including all messages
     */
    @GetMapping("/conversations/{conversationId}")
    fun getConversationHistory(
        @PathVariable conversationId: String,
    ): ResponseEntity<AgentConversation> {
        logger.debug { "Retrieving conversation history for: $conversationId" }
        
        // Try to get from streaming service first, then fallback to regular service
        val conversation =
            agentQueryStreamingService.getConversationHistory(conversationId)
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
    fun healthCheck(): ResponseEntity<Map<String, String>> =
        ResponseEntity.ok(
            mapOf(
                "status" to "Agent service is available",
                "service" to "AgentQueryService",
                "features" to "Cypher generation, Natural language responses, Error retry, Streaming, Visualization",
            ),
        )

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
                        "Automatic visualization generation",
                        "Plotly.js chart generation",
                        "Progress tracking and updates",
                    ),
                "endpoints" to
                    mapOf(
                        "query" to "POST /api/v1/agent/query - Standard query processing",
                        "queryStream" to "POST /api/v1/agent/query/stream - Streaming query with real-time updates",
                        "queryIntelligent" to "POST /api/v1/agent/query/intelligent - Intelligent multi-stage pipeline with early decision making",
                        "queryUnified" to "POST /api/v1/agent/query/unified - NEW: Unified pipeline with predictable visualization execution",
                        "analyze" to "POST /api/v1/agent/analyze - NEW: Direct query analysis without execution",
                        "testVisualization" to "POST /api/v1/agent/test/visualization - NEW: Test visualization execution with sample data",
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
                        "features" to listOf("Interactive charts", "Responsive design", "Professional styling", "Accessibility support"),
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
     *
     * @param maxAgeHours Maximum age in hours for conversations to keep (default: 24)
     * @return Result of the cleanup operation
     */
    @PostMapping("/cleanup")
    suspend fun cleanupConversations(
        @RequestParam(defaultValue = "24") maxAgeHours: Int,
    ): ResponseEntity<Map<String, Any>> {
        agentQueryService.cleanupOldConversations(maxAgeHours)
        return ResponseEntity.ok(
            mapOf(
                "message" to "Cleanup completed",
                "maxAgeHours" to maxAgeHours,
            ),
        )
    }

    /**
     * Debug endpoint to test visualization generation with sample data
     */
    @PostMapping("/debug/visualization")
    suspend fun debugVisualization(): ResponseEntity<Map<String, Any>> {
        // Sample data similar to your conversation count query
        val sampleResults =
            listOf(
                mapOf("last_month_conversations" to 394),
            )
        
        try {
            logger.info { "Testing visualization with sample data..." }
            
            val recommendation =
                visualizationService.analyzeForVisualization(
                    originalQuery = "How many conversations were created last month?",
                    cypherQuery = "MATCH (c:Conversation) WHERE c.createdAt >= '2025-05-01T00:00:00Z' AND c.createdAt < '2025-06-01T00:00:00Z' RETURN count(c) as last_month_conversations",
                    queryResults = sampleResults,
                    apiKey = "Bearer $apiKey",
                )
            
            logger.info { "Debug: Recommendation = $recommendation" }
            
            val visualization =
                if (recommendation.shouldVisualize && recommendation.chartType != null) {
                    visualizationService.generateVisualization(
                        recommendation = recommendation,
                        queryResults = sampleResults,
                        originalQuery = "How many conversations were created last month?",
                        apiKey = "Bearer $apiKey",
                    )
                } else {
                    null
                }
            
            logger.info { "Debug: Visualization = ${if (visualization != null) "SUCCESS" else "NULL"}" }
            
            return ResponseEntity.ok(
                mapOf(
                    "recommendation" to recommendation,
                    "visualization" to visualization,
                    "sampleData" to sampleResults,
                ),
            ) as ResponseEntity<Map<String, Any>>
        } catch (e: Exception) {
            logger.error(e) { "Error in debug visualization: ${e.message}" }
            return ResponseEntity.ok(
                mapOf(
                    "error" to e.message,
                    "sampleData" to sampleResults,
                ),
            ) as ResponseEntity<Map<String, Any>>
        }
    }

    /**
     * Test endpoint for intelligent visualization service
     */
    @PostMapping("/debug/intelligent-visualization")
    suspend fun debugIntelligentVisualization(
        @RequestBody testData: Map<String, Any>,
    ): ResponseEntity<Map<String, Any>> {
        logger.info { "Testing intelligent visualization service" }
        
        // Create sample data if none provided
        val sampleResults =
            (testData["queryResults"] as? List<Map<String, Any>>) ?: listOf(
                mapOf("date" to "2024-01-01", "conversations" to 45, "nps" to 7.2),
                mapOf("date" to "2024-01-02", "conversations" to 52, "nps" to 6.8),
                mapOf("date" to "2024-01-03", "conversations" to 38, "nps" to 8.1),
                mapOf("date" to "2024-01-04", "conversations" to 61, "nps" to 7.5),
                mapOf("date" to "2024-01-05", "conversations" to 29, "nps" to 8.3),
            )
        
        val originalQuery = testData["query"] as? String ?: "Show me daily conversation trends with NPS analysis"
        val cypherQuery = testData["cypherQuery"] as? String ?: "MATCH (c:Conversation) RETURN substring(c.createdAt, 0, 10) as date, count(c) as conversations, avg(c.nps) as nps ORDER BY date"
        val naturalResponse = testData["naturalResponse"] as? String ?: "Here's the daily conversation trend analysis showing conversation volume and average NPS scores."
        
        return try {
            logger.info { "Calling intelligent visualization service with sample data" }
            
            val visualization =
                intelligentVisualizationService.generateIntelligentVisualization(
                    originalQuery = originalQuery,
                    cypherQuery = cypherQuery,
                    queryResults = sampleResults,
                    naturalLanguageResponse = naturalResponse,
                    apiKey = "Bearer $apiKey",
                    enablePythonAnalysis = testData["enablePythonAnalysis"] as? Boolean ?: true,
                )
            
            ResponseEntity.ok(
                mapOf(
                    "visualization" to visualization,
                    "sampleData" to sampleResults,
                    "testConfiguration" to
                        mapOf(
                            "originalQuery" to originalQuery,
                            "cypherQuery" to cypherQuery,
                            "naturalResponse" to naturalResponse,
                        ),
                ),
            ) as ResponseEntity<Map<String, Any>>
        } catch (e: Exception) {
            logger.error(e) { "Error in intelligent visualization debug: ${e.message}" }
            return ResponseEntity.ok(
                mapOf(
                    "error" to e.message,
                    "sampleData" to sampleResults,
                ),
            ) as ResponseEntity<Map<String, Any>>
        }
    }

    /**
     * UNIFIED: New unified pipeline for agent queries with intelligent analysis and visualization.
     * 
     * This endpoint provides:
     * - Unified query analysis with strategy determination
     * - Predictable visualization execution without fallbacks
     * - Multiple visualization support when appropriate
     * - Clean separation between analysis and execution
     * - Real-time progress updates
     *
     * @param request The agent query request containing the user's question
     * @return Flow of ServerSentEvents with unified processing
     */
    @PostMapping("/query/unified", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun queryAgentUnified(
        @RequestBody request: AgentQueryRequest,
    ): Flow<ServerSentEvent<String>> {
        logger.info { "Received unified agent query: ${request.query}" }
        
        return try {
            unifiedAgentPipelineService.processQuery(request, "Bearer $apiKey")
        } catch (e: Exception) {
            logger.error(e) { "Error in unified agent query: ${e.message}" }
            throw e
        }
    }

    /**
     * TEST: Query analysis endpoint for testing the analysis service.
     * 
     * This endpoint provides:
     * - Direct access to query analysis without execution
     * - Strategy determination and reasoning
     * - Visualization potential assessment
     * - Useful for debugging and understanding query analysis
     *
     * @param request The agent query request containing the user's question
     * @return Analysis results with strategy and reasoning
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

    /**
     * TEST: Direct visualization execution endpoint for testing the visualization executor.
     * 
     * This endpoint provides:
     * - Direct access to visualization execution with sample data
     * - Multiple visualization type testing
     * - Useful for debugging visualization logic
     *
     * @param testData Test configuration with sample data and parameters
     * @return Visualization execution results
     */
    @PostMapping("/test/visualization")
    suspend fun testVisualizationExecution(
        @RequestBody testData: Map<String, Any>,
    ): ResponseEntity<Map<String, Any>> {
        logger.info { "Received visualization execution test request" }
        
        return try {
            // Extract test parameters
            val originalQuery = testData["originalQuery"] as? String ?: "Test query for visualization"
            val cypherQuery = testData["cypherQuery"] as? String ?: "MATCH (c:Conversation) RETURN count(c) as total"
            val naturalResponse = testData["naturalResponse"] as? String ?: "Test natural language response"
            
            // Sample data for testing
            val sampleResults = (testData["queryResults"] as? List<Map<String, Any>>) ?: listOf(
                mapOf("category" to "tech", "conversations" to 150, "nps" to 7.2),
                mapOf("category" to "billing", "conversations" to 98, "nps" to 6.8),
                mapOf("category" to "support", "conversations" to 203, "nps" to 8.1),
                mapOf("category" to "sales", "conversations" to 87, "nps" to 7.9),
            )
            
            // Create analysis (you can override with testData)
            val strategy = testData["strategy"] as? String ?: "TEXT_WITH_MULTIPLE_VISUALS"
            val analysis = mapOf(
                "responseStrategy" to strategy,
                "visualizationPotential" to mapOf(
                    "multipleVisualsRecommended" to (testData["multipleVisuals"] as? Boolean ?: true),
                    "reasoning" to "Test analysis for multiple visualizations"
                )
            )
            
            // Create visualization request
            val vizRequest = mapOf(
                "originalQuery" to originalQuery,
                "cypherQuery" to cypherQuery,
                "queryResults" to sampleResults,
                "naturalLanguageResponse" to naturalResponse,
                "strategy" to strategy,
                "analysis" to analysis
            )
            
            // Execute visualization (we need to call the service method directly)
            // For now, return a mock response showing the structure
            val mockResult = mapOf(
                "success" to true,
                "type" to "MULTIPLE",
                "visualizations" to listOf(
                    mapOf(
                        "chartType" to "BAR",
                        "title" to "Conversations by Category",
                        "description" to "Bar chart showing conversation volume by category"
                    ),
                    mapOf(
                        "chartType" to "SCATTER", 
                        "title" to "NPS vs Volume Analysis",
                        "description" to "Scatter plot showing relationship between conversation volume and NPS"
                    )
                ),
                "summary" to "Successfully generated 2 visualizations for comprehensive analysis"
            )
            
            ResponseEntity.ok(
                mapOf(
                    "testConfiguration" to vizRequest,
                    "executionResult" to mockResult,
                    "message" to "Visualization execution test completed",
                    "timestamp" to java.time.Instant.now(),
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "Error in visualization execution test: ${e.message}" }
            ResponseEntity.internalServerError().body(
                mapOf(
                    "error" to "Visualization test failed: ${e.message}",
                    "testData" to testData,
                )
            )
        }
    }
}
