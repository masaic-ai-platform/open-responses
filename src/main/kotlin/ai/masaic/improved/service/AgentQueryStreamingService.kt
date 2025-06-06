package ai.masaic.improved.service

import ai.masaic.improved.ModelService
import ai.masaic.improved.createCompletion
import ai.masaic.improved.model.*
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.neo4j.driver.Driver
import org.neo4j.driver.SessionConfig
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Streaming service for handling agent queries with real-time progress updates and visualization.
 * 
 * This service extends the functionality of AgentQueryService by:
 * 1. Providing real-time streaming updates during query processing
 * 2. Generating visualization recommendations and Plotly.js charts
 * 3. Supporting progress tracking for better UX
 * 4. Maintaining backward compatibility with the original service
 */
@Service
class AgentQueryStreamingService(
    private val modelService: ModelService,
    private val graphService: ConversationGraphService,
    private val memgraphDriver: Driver,
    private val queryAnalysisService: QueryAnalysisService,
    private val visualizationExecutorService: VisualizationExecutorService,
) {
    private val logger = KotlinLogging.logger {}
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    
    // Store active agent conversations in memory
    private val activeConversations = ConcurrentHashMap<String, AgentConversation>()

    /**
     * Process an agent query with streaming responses and optional visualization.
     *
     * @param request The agent query request
     * @param apiKey The API key for LLM calls
     * @return Flow of ServerSentEvents with real-time progress
     */
    suspend fun processQueryStream(
        request: AgentQueryRequest,
        apiKey: String,
    ): Flow<ServerSentEvent<String>> =
        flow {
            logger.info { "Processing streaming agent query: ${request.query}" }
        
            try {
                // Emit initial event
                val conversation = getOrCreateConversation(request.conversationId)
                emit(createEvent(AgentResponseEvent.QueryStarted(request.query, conversation.id)))
            
                // Add user message to conversation
                conversation.addMessage(AgentMessage(AgentRole.USER, request.query))
            
                // Build graph context
                emit(createEvent(AgentResponseEvent.ContextBuilding("Building graph context...")))
                emit(createEvent(AgentResponseEvent.Progress("Building Context", 0.1f, "Analyzing graph structure")))
            
                val graphContext =
                    if (request.includeContext) {
                        buildGraphContext()
                    } else {
                        null
                    }
            
                // Generate and execute query with retry logic
                var retryCount = 0
                var lastError: String? = null
                var finalResponse: AgentQueryResponse? = null
            
                while (retryCount <= request.maxRetries) {
                    try {
                        // Emit generation status
                        emit(
                            createEvent(
                                AgentResponseEvent.CypherGeneration(
                                    status = if (retryCount == 0) "Generating Cypher query..." else "Retrying Cypher generation...",
                                    attempt = retryCount + 1,
                                    maxRetries = request.maxRetries + 1,
                                ),
                            ),
                        )
                    
                        emit(createEvent(AgentResponseEvent.Progress("Generating Query", 0.3f, "Creating Cypher query from natural language")))
                    
                        // Generate Cypher query
                        val cypherResponse =
                            generateCypherQuery(
                                request.query, 
                                conversation, 
                                graphContext, 
                                lastError,
                                apiKey,
                            )
                    
                        emit(
                            createEvent(
                                AgentResponseEvent.CypherGenerated(
                                    cypherQuery = cypherResponse.cypherQuery,
                                    explanation = cypherResponse.explanation,
                                    confidence = cypherResponse.confidence,
                                ),
                            ),
                        )
                    
                        // Execute Cypher query with enhanced error details
                        emit(createEvent(AgentResponseEvent.QueryExecution(cypherResponse.cypherQuery)))
                        emit(createEvent(AgentResponseEvent.Progress("Executing Query", 0.5f, "Running query against graph database")))
                    
                        val startTime = System.currentTimeMillis()
                        val executionResult = executeCypherQuery(cypherResponse.cypherQuery)
                        val executionTime = System.currentTimeMillis() - startTime
                    
                        emit(
                            createEvent(
                                AgentResponseEvent.QueryExecuted(
                                    success = executionResult.success,
                                    resultCount = executionResult.results?.size ?: 0,
                                    executionTime = executionTime,
                                ),
                            ),
                        )
                    
                        if (executionResult.success) {
                            val results = executionResult.results ?: emptyList()
                        
                            // Generate natural language response
                            emit(createEvent(AgentResponseEvent.NaturalLanguageGeneration("Generating natural language response...")))
                            emit(createEvent(AgentResponseEvent.Progress("Generating Response", 0.7f, "Creating natural language explanation")))
                        
                            val naturalResponse =
                                generateNaturalLanguageResponse(
                                    request.query,
                                    cypherResponse.cypherQuery,
                                    results,
                                    graphContext,
                                    conversation,
                                    apiKey,
                                )
                        
                            // Emit the natural language response immediately when ready
                            emit(createEvent(AgentResponseEvent.NaturalLanguageGenerated(naturalResponse)))
                            emit(createEvent(AgentResponseEvent.Progress("Response Ready", 0.75f, "Natural language response generated")))
                        
                            // Execute unified visualization strategy
                            var visualization: VisualizationData? = null
                            var parallelVisualizations: ParallelVisualizationData? = null
                            
                            if (request.enableVisualization && results.isNotEmpty()) {
                                logger.info { "Visualization enabled. Starting unified analysis..." }
                                emit(createEvent(AgentResponseEvent.VisualizationGeneration("Analyzing data for optimal visualization strategy...")))
                                emit(createEvent(AgentResponseEvent.Progress("Creating Visualization", 0.9f, "Determining optimal visualization approach")))
                            
                                try {
                                    // Step 1: Analyze the query and data comprehensively
                                    val analysis = queryAnalysisService.analyzeQuery(
                                        query = request.query,
                                        conversation = conversation,
                                        queryResults = results,
                                        apiKey = apiKey
                                    )
                                    
                                    logger.info { "Query analysis complete: strategy=${analysis.responseStrategy}, multipleVisuals=${analysis.visualizationPotential.multipleVisualsRecommended}" }
                                    
                                    // Step 2: Execute visualization based on analysis
                                    val vizRequest = VisualizationExecutionRequest(
                                        originalQuery = request.query,
                                        cypherQuery = cypherResponse.cypherQuery,
                                        queryResults = results,
                                        naturalLanguageResponse = naturalResponse,
                                        strategy = analysis.responseStrategy,
                                        analysis = analysis
                                    )
                                    
                                    val vizResult = visualizationExecutorService.executeVisualization(vizRequest, apiKey)
                                    
                                    if (vizResult.success) {
                                        when (vizResult.type) {
                                            VisualizationResultType.SINGLE -> {
                                                visualization = vizResult.visualizations.first()
                                                
                                                // Emit Python analysis events if used
                                                visualization.pythonAnalysis?.let { pythonResult ->
                                                    emit(createEvent(AgentResponseEvent.PythonAnalysisStarted(
                                                        code = pythonResult.code,
                                                        description = "Performing advanced data analysis with Python"
                                                    )))
                                                    
                                                    emit(createEvent(AgentResponseEvent.PythonAnalysisCompleted(
                                                        output = pythonResult.output,
                                                        executionTime = pythonResult.executionTime,
                                                        success = pythonResult.success
                                                    )))
                                                }
                                                
                                                emit(createEvent(AgentResponseEvent.VisualizationGenerated(
                                                    chartType = visualization.chartType,
                                                    title = visualization.title,
                                                    description = visualization.description
                                                )))
                                                
                                                // Also emit the full visualization data for frontend compatibility
                                                emit(createEvent(AgentResponseEvent.VisualizationGeneratedWithData(visualization)))
                                            }
                                            
                                            VisualizationResultType.MULTIPLE, VisualizationResultType.COMPREHENSIVE -> {
                                                val primary = vizResult.visualizations.first()
                                                val additional = vizResult.visualizations.drop(1)
                                                
                                                // Create parallel visualization data structure for backward compatibility
                                                parallelVisualizations = ParallelVisualizationData(
                                                    primaryVisualization = primary,
                                                    additionalVisualizations = additional,
                                                    totalUnits = vizResult.visualizations.size,
                                                    executionSummary = vizResult.summary
                                                )
                                                
                                                visualization = primary // Set primary for backward compatibility
                                                
                                                // Emit parallel visualization events
                                                emit(createEvent(AgentResponseEvent.ParallelVisualizationStarted(
                                                    totalUnits = parallelVisualizations.totalUnits,
                                                    units = vizResult.visualizations.map { it.unitName ?: it.title }
                                                )))
                                                
                                                // Emit events for each visualization unit
                                                vizResult.visualizations.forEach { viz ->
                                                    viz.pythonAnalysis?.let { pythonResult ->
                                                        emit(createEvent(AgentResponseEvent.PythonAnalysisStarted(
                                                            code = pythonResult.code,
                                                            description = "Performing advanced data analysis for ${viz.unitName ?: "visualization unit"}"
                                                        )))
                                                        
                                                        emit(createEvent(AgentResponseEvent.PythonAnalysisCompleted(
                                                            output = pythonResult.output,
                                                            executionTime = pythonResult.executionTime,
                                                            success = pythonResult.success
                                                        )))
                                                    }
                                                    
                                                    emit(createEvent(AgentResponseEvent.VisualizationUnitCompleted(
                                                        unitName = viz.unitName ?: viz.title,
                                                        unitIndex = viz.unitIndex ?: 0,
                                                        totalUnits = parallelVisualizations.totalUnits,
                                                        chartType = viz.chartType,
                                                        executionTime = viz.pythonAnalysis?.executionTime ?: 0L,
                                                        hasPythonAnalysis = viz.pythonAnalysis != null
                                                    )))
                                                    
                                                    // Also emit the individual visualization data
                                                    emit(createEvent(AgentResponseEvent.VisualizationUnitCompletedWithData(
                                                        unitName = viz.unitName ?: viz.title,
                                                        unitIndex = viz.unitIndex ?: 0,
                                                        totalUnits = parallelVisualizations.totalUnits,
                                                        visualization = viz
                                                    )))
                                                }
                                                
                                                emit(createEvent(AgentResponseEvent.ParallelVisualizationCompleted(
                                                    totalUnits = parallelVisualizations.totalUnits,
                                                    successfulUnits = vizResult.visualizations.size,
                                                    failedUnits = 0,
                                                    totalExecutionTime = vizResult.pythonAnalyses.sumOf { it.executionTime }
                                                )))
                                                
                                                // Also emit the full parallel visualization data for frontend compatibility
                                                emit(createEvent(AgentResponseEvent.ParallelVisualizationCompletedWithData(parallelVisualizations)))
                                            }
                                            
                                            VisualizationResultType.NONE -> {
                                                logger.info { "No visualization generated: ${vizResult.summary}" }
                                            }
                                        }
                                    } else {
                                        logger.info { "Visualization execution failed: ${vizResult.summary}" }
                                    }
                                } catch (e: Exception) {
                                    logger.warn(e) { "Error in unified visualization execution: ${e.message}" }
                                }
                            } else {
                                logger.info { "Visualization skipped. enableVisualization=${request.enableVisualization}, results.isEmpty()=${results.isEmpty()}" }
                            }
                        
                            // Create final response
                            finalResponse =
                                AgentQueryResponse(
                                    conversationId = conversation.id,
                                    naturalLanguageResponse = naturalResponse,
                                    cypherQuery = cypherResponse.cypherQuery,
                                    queryResults = results,
                                    retryCount = retryCount,
                                    visualization = visualization,
                                    parallelVisualizations = parallelVisualizations,
                                )
                        
                            // Add assistant message to conversation
                            conversation.addMessage(
                                AgentMessage(
                                    AgentRole.ASSISTANT, 
                                    naturalResponse,
                                    metadata =
                                        mapOf(
                                            "cypherQuery" to cypherResponse.cypherQuery,
                                            "queryResults" to results,
                                            "visualization" to visualization,
                                            "parallelVisualizations" to parallelVisualizations,
                                        ) as Map<String, Any>,
                                ),
                            )
                        
                            emit(createEvent(AgentResponseEvent.Progress("Complete", 1.0f, "Query processing completed successfully")))
                            emit(createEvent(AgentResponseEvent.Complete(finalResponse)))
                        
                            return@flow // Success, exit the flow
                        } else {
                            // Query failed, prepare for retry with enhanced error context
                            lastError = buildEnhancedErrorMessage(executionResult.error, cypherResponse.cypherQuery)
                            retryCount++
                        
                            emit(
                                createEvent(
                                    AgentResponseEvent.QueryRetry(
                                        attempt = retryCount,
                                        maxRetries = request.maxRetries + 1,
                                        error = lastError ?: "Unknown query error",
                                    ),
                                ),
                            )
                        
                            logger.warn { "Cypher query failed (attempt $retryCount): ${executionResult.error}" }
                            logger.warn { "Failed query: ${cypherResponse.cypherQuery}" }
                        }
                    } catch (e: Exception) {
                        lastError = "LLM generation error: ${e.message}"
                        retryCount++
                    
                        emit(
                            createEvent(
                                AgentResponseEvent.QueryRetry(
                                    attempt = retryCount,
                                    maxRetries = request.maxRetries + 1,
                                    error = lastError ?: "Unknown error",
                                ),
                            ),
                        )
                    
                        logger.error(e) { "Error generating Cypher query (attempt $retryCount)" }
                    }
                }
            
                // All retries exhausted
                val errorMessage = "Failed to generate valid Cypher query after ${request.maxRetries + 1} attempts. Last error: $lastError"
                logger.error { errorMessage }
            
                finalResponse =
                    AgentQueryResponse(
                        conversationId = conversation.id,
                        naturalLanguageResponse = "I apologize, but I'm having trouble generating a valid query for your request. Please try rephrasing your question or asking something more specific about the conversation data.",
                        retryCount = retryCount - 1,
                        error = errorMessage,
                    )
            
                emit(createEvent(AgentResponseEvent.Error(errorMessage, retryable = true)))
                emit(createEvent(AgentResponseEvent.Complete(finalResponse)))
            } catch (e: Exception) {
                logger.error(e) { "Unexpected error processing agent query: ${e.message}" }
            
                val errorResponse =
                    AgentQueryResponse(
                        conversationId = request.conversationId ?: UUID.randomUUID().toString(),
                        naturalLanguageResponse = "I encountered an unexpected error while processing your request. Please try again.",
                        error = "Unexpected error: ${e.message}",
                    )
            
                emit(createEvent(AgentResponseEvent.Error("Unexpected error: ${e.message}", retryable = true)))
                emit(createEvent(AgentResponseEvent.Complete(errorResponse)))
            }
        }

    /**
     * Create a ServerSentEvent from an AgentResponseEvent
     */
    private fun createEvent(event: AgentResponseEvent): ServerSentEvent<String> =
        ServerSentEvent
            .builder<String>()
            .id(UUID.randomUUID().toString())
            .event(event.type)
            .data(objectMapper.writeValueAsString(event))
            .build()

    // Reuse methods from AgentQueryService (delegation pattern)
    private fun getOrCreateConversation(conversationId: String?): AgentConversation =
        if (conversationId != null && activeConversations.containsKey(conversationId)) {
            activeConversations[conversationId]!!.copy(lastUpdated = Instant.now())
        } else {
            val newId = conversationId ?: "agent_conv_${UUID.randomUUID().toString().replace("-", "").substring(0, 10)}"
            val conversation = AgentConversation(id = newId)
            activeConversations[newId] = conversation
            conversation
        }

    private suspend fun buildGraphContext(): GraphContext {
        try {
            val statistics = graphService.getMigrationStatistics()
            
            // Try to get tree structure, but fall back to flat list if it fails
            val (rootNodes, sampleNodes, summary) =
                try {
                    val nodeTree = graphService.getFullNodeTree()
                    val roots = nodeTree.map { it.name }
                    val samples = nodeTree.flatMap { getAllNodes(it) }.take(20)
                    val treeSummary = buildNodeTreeSummary(nodeTree)
                    Triple(roots, samples, treeSummary)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to build tree structure, using flat node list" }
                    val allNodes = graphService.getAllNodes()
                    val roots = allNodes.map { it.name }
                    val samples = allNodes.map { it.name }.take(20)
                    val flatSummary = buildFlatNodeSummary(allNodes)
                    Triple(roots, samples, flatSummary)
                }
            
            return GraphContext(
                totalConversations = statistics.conversationsInGraph,
                totalPathNodes = statistics.pathNodesInGraph,
                totalRelationships = statistics.totalRelationships,
                rootPaths = rootNodes,
                samplePaths = sampleNodes,
                pathTreeSummary = summary,
            )
        } catch (e: Exception) {
            logger.error(e) { "Error building graph context: ${e.message}" }
            return GraphContext(0, 0, 0, emptyList(), emptyList(), "Error retrieving graph context")
        }
    }

    private fun getAllNodes(node: NodeTreeNode): List<String> {
        val nodes = mutableListOf(node.name)
        node.children.forEach { child ->
            nodes.addAll(getAllNodes(child))
        }
        return nodes
    }

    private fun buildNodeTreeSummary(nodeTree: List<NodeTreeNode>): String {
        val summary = StringBuilder()
        summary.appendLine("Graph Structure Overview:")
        summary.appendLine("Root Categories: ${nodeTree.joinToString(", ") { "${it.name} (${it.conversationCount} conversations)" }}")
        
        nodeTree.take(3).forEach { root ->
            summary.appendLine("\n${root.name}:")
            root.children.take(5).forEach { child ->
                summary.appendLine("  - ${child.name} (${child.conversationCount} conversations)")
                child.children.take(3).forEach { subChild ->
                    summary.appendLine("    - ${subChild.name} (${subChild.conversationCount} conversations)")
                }
            }
        }
        
        return summary.toString()
    }

    private fun buildFlatNodeSummary(nodes: List<NodeInfo>): String {
        val summary = StringBuilder()
        summary.appendLine("Graph Structure Overview (Flat):")
        summary.appendLine("Available Nodes: ${nodes.size} total")
        summary.appendLine("Top Nodes by Conversation Count:")
        
        nodes.sortedByDescending { it.conversationCount }.take(10).forEach { node ->
            summary.appendLine("  - ${node.name} (${node.conversationCount} conversations)")
        }
        
        return summary.toString()
    }

    private suspend fun generateCypherQuery(
        userQuery: String,
        conversation: AgentConversation,
        graphContext: GraphContext?,
        previousError: String?,
        apiKey: String,
    ): CypherGenerationResponse {
        val systemPrompt = buildCypherGenerationPrompt(graphContext, previousError)
        val conversationHistory = buildConversationHistory(conversation)
        
        val messages =
            mutableListOf<Map<String, Any>>().apply {
                add(mapOf("role" to "system", "content" to systemPrompt))
                if (conversationHistory.isNotEmpty()) {
                    addAll(conversationHistory)
                }
                add(mapOf("role" to "user", "content" to userQuery))
            }
        
        val request =
            CreateCompletionRequest(
                model = System.getenv("OPENAI_MODEL"),
                messages = messages,
                temperature = 0.1,
                response_format =
                    mapOf(
                        "type" to "json_schema",
                        "json_schema" to
                            mapOf(
                                "name" to "cypher_response",
                                "schema" to
                                    mapOf(
                                        "type" to "object",
                                        "properties" to
                                            mapOf(
                                                "cypherQuery" to mapOf("type" to "string"),
                                                "explanation" to mapOf("type" to "string"),
                                                "confidence" to mapOf("type" to "string"),
                                            ),
                                        "required" to listOf("cypherQuery", "explanation"),
                                    ),
                            ),
                    ),
            )
        
        val response: CypherGenerationResponse = modelService.createCompletion(request, apiKey)
        logger.debug { "Generated Cypher query: ${response.cypherQuery}" }
        
        return response
    }

    private fun buildCypherGenerationPrompt(
        graphContext: GraphContext?,
        previousError: String?,
    ): String {
        val prompt = StringBuilder()
        val currentDate = java.time.LocalDate.now()
        val currentDateTime = java.time.Instant.now()
        val last30Days = currentDate.minusDays(30)
        val last7Days = currentDate.minusDays(7)
        val thisMonth = currentDate.withDayOfMonth(1)
        val lastMonth = thisMonth.minusMonths(1)
        
        prompt.appendLine(
            """
            You are a Neo4j Cypher query expert specializing in analyzing conversation data stored in a graph database.
            
            CURRENT DATE CONTEXT:
            - Today's date: $currentDate (YYYY-MM-DD format)
            - Current datetime: $currentDateTime (ISO format)
            - Use this for relative time queries like "this week", "last month", "recent", "today", etc.
            
            GRAPH SCHEMA (IMPROVED - PURE GRAPH HIERARCHY):
            - Conversation nodes have properties: 
              * id (string): unique identifier
              * createdAt (string): ISO datetime string like "2024-01-15T10:30:00Z"
              * summary (string): conversation summary
              * resolved (boolean): true/false resolution status
              * nps (integer): Net Promoter Score (0-10)
              * version (integer): conversation version
              * classification (string): RESOLVED or UNRESOLVED
              * messageCount (integer): number of messages
            - PathNode nodes represent hierarchical categories with properties: name (unique identifier)
              * Each node has a unique name like "domain", "tech", "api", "billing", etc.
              * NO path property - this is a pure graph hierarchy
            - Relationships: 
              * PathNode-[:HAS_CHILD]->PathNode (hierarchical tree structure)
              * PathNode-[:CONTAINS]->Conversation (leaf nodes contain conversations)
            
            IMPORTANT DATA TYPE NOTES:
            - createdAt is stored as ISO datetime string like "2024-01-15T10:30:00Z"
            - Date extraction: substring(c.createdAt, 0, 10) gives YYYY-MM-DD
            - Time extraction: substring(c.createdAt, 11, 8) gives HH:MM:SS  
            - Hour extraction: toInteger(substring(c.createdAt, 11, 2)) gives hour (0-23)
            - For date comparisons, use STRING comparisons since ISO format sorts correctly: c.createdAt >= '2024-01-15T00:00:00Z'
            - Avoid datetime() function - use string comparisons instead for compatibility
            - Boolean fields (resolved) should be compared directly: c.resolved = true or c.resolved = false
            - NPS scores are integers from 0-10, use standard integer comparisons
            
            CRITICAL RESTRICTIONS:
            - DO NOT use APOC functions (apoc.date.*, apoc.convert.*, etc.) - they are not available
            - DO NOT use datetime(), date(), time() functions - use string operations instead
            - For date calculations, use string concatenation and hardcoded date strings
            - For relative dates like "last 30 days", calculate the target date string manually
            - Use toInteger(), toString(), substring() for data conversion
            - Avoid CALL subqueries with APOC functions
            - For "last 30 days" use: c.createdAt >= '${java.time.LocalDate.now().minusDays(30)}T00:00:00Z'
            
            CYPHER SYNTAX REQUIREMENTS:
            - Use ONLY ONE RETURN statement per query - multiple RETURN statements are invalid
            - If you need multiple aggregations, use WITH clauses to pipe results between steps
            - Proper clause ordering: MATCH, WHERE, WITH, RETURN, ORDER BY
            - Ensure all variables are defined before use
            - Use proper parentheses and bracket matching
            
            SAMPLE QUERIES (IMPROVED - PURE GRAPH TRAVERSAL):
            1. Count conversations: MATCH (c:Conversation) RETURN count(c) as total
            2. Count by specific node: MATCH (p:PathNode {name: 'domain'})-[:CONTAINS]->(c:Conversation) RETURN count(c) as total
            3. Count all under hierarchy: MATCH (root:PathNode {name: 'domain'})-[:HAS_CHILD*0..]->(descendant:PathNode)-[:CONTAINS]->(c:Conversation) RETURN count(c) as total
            4. Resolved vs unresolved: MATCH (c:Conversation) RETURN c.resolved, count(c) ORDER BY c.resolved
            5. NPS distribution: MATCH (c:Conversation) WHERE c.nps IS NOT NULL RETURN c.nps, count(c) ORDER BY c.nps
            6. Conversations by classification: MATCH (c:Conversation) RETURN c.classification, count(c)
            7. Top categories with hierarchy: MATCH (p:PathNode)-[:CONTAINS]->(c:Conversation) RETURN p.name, count(c) as conversations ORDER BY conversations DESC LIMIT 10
            8. Daily conversation counts: MATCH (c:Conversation) RETURN substring(c.createdAt, 0, 10) as date, count(c) as conversations ORDER BY date
            9. Daily counts for specific category: MATCH (root:PathNode {name: 'domain'})-[:HAS_CHILD*0..]->(descendant:PathNode)-[:CONTAINS]->(c:Conversation) RETURN substring(c.createdAt, 0, 10) as date, count(c) as conversations ORDER BY date
            10. Recent conversations: MATCH (c:Conversation) WHERE c.createdAt >= '$currentDate' + 'T00:00:00Z' RETURN count(c) as total
            11. Hourly patterns: MATCH (c:Conversation) RETURN toInteger(substring(c.createdAt, 11, 2)) as hour, count(c) as conversations ORDER BY hour
            12. Business hours analysis: MATCH (c:Conversation) WHERE toInteger(substring(c.createdAt, 11, 2)) BETWEEN 9 AND 17 RETURN count(c) as business_hours_conversations
            13. Today's conversations: MATCH (c:Conversation) WHERE substring(c.createdAt, 0, 10) = '$currentDate' RETURN count(c) as today_conversations
            14. Last 7 days: MATCH (c:Conversation) WHERE c.createdAt >= '${java.time.LocalDate.now().minusDays(7)}T00:00:00Z' RETURN count(c) as week_conversations
            15. Last 30 days trends: MATCH (c:Conversation) WHERE c.createdAt >= '${java.time.LocalDate.now().minusDays(30)}T00:00:00Z' RETURN substring(c.createdAt, 0, 10) as date, count(c) as conversations ORDER BY date
            16. Hierarchy structure: MATCH (root:PathNode)-[:HAS_CHILD]->(child:PathNode) RETURN root.name as parent, child.name as child ORDER BY parent, child
            17. All nodes under domain: MATCH (root:PathNode {name: 'domain'})-[:HAS_CHILD*0..]->(descendant:PathNode) RETURN descendant.name as node_name ORDER BY node_name
            """.trimIndent(),
        )
        
        if (graphContext != null) {
            prompt.appendLine("\nCURRENT GRAPH STATE:")
            prompt.appendLine("- Total conversations: ${graphContext.totalConversations}")
            prompt.appendLine("- Total path nodes: ${graphContext.totalPathNodes}")
            prompt.appendLine("- Available root paths: ${graphContext.rootPaths.joinToString(", ")}")
            prompt.appendLine("\n${graphContext.pathTreeSummary}")
        }
        
        if (previousError != null) {
            prompt.appendLine("\nPREVIOUS ERROR (fix this):")
            prompt.appendLine(previousError)
            prompt.appendLine("\nPlease correct the query to avoid this error.")
        }
        
        prompt.appendLine(
            """
            
            INSTRUCTIONS:
            1. Generate a valid Cypher query that answers the user's question
            2. Focus on business KPIs like counts, averages, trends, distributions
            3. Use proper Cypher syntax and avoid common errors
            4. Return results in a meaningful format with clear column names
            5. Limit results to reasonable numbers (use LIMIT when appropriate)
            6. Provide an explanation of what the query does
            
            Respond with JSON containing:
            - cypherQuery: The Cypher query string
            - explanation: What the query does and what insights it provides
            - confidence: Your confidence level (high/medium/low)
            """.trimIndent(),
        )
        
        return prompt.toString()
    }

    private fun buildConversationHistory(conversation: AgentConversation): List<Map<String, Any>> =
        conversation.messages.takeLast(10).map { message ->
            mapOf(
                "role" to message.role.name.lowercase(),
                "content" to message.content,
            )
        }

    private suspend fun executeCypherQuery(cypherQuery: String): CypherExecutionResult =
        try {
            memgraphDriver.session(SessionConfig.forDatabase("memgraph")).use { session ->
                logger.debug { "Executing Cypher query: $cypherQuery" }
                
                val result = session.run(cypherQuery)
                val records =
                    result.list { record ->
                        val map = mutableMapOf<String, Any>()
                        record.keys().forEach { key ->
                            val value = record.get(key)
                            map[key] = when {
                                value.isNull() -> null
                                else -> {
                                    try {
                                        when (value.type().name()) {
                                            "INTEGER" -> value.asLong()
                                            "FLOAT" -> value.asDouble()
                                            "STRING" -> value.asString()
                                            "BOOLEAN" -> value.asBoolean()
                                            "LIST" -> value.asList()
                                            "MAP" -> value.asMap()
                                            else -> value.toString()
                                        }
                                    } catch (e: Exception) {
                                        value.toString()
                                    }
                                }
                            } ?: "null"
                        }
                        map
                    }
                
                logger.debug { "Query executed successfully, returned ${records.size} records" }
                CypherExecutionResult(success = true, results = records, queryExecuted = cypherQuery)
            }
        } catch (e: Exception) {
            logger.error(e) { "Error executing Cypher query: $cypherQuery" }
            CypherExecutionResult(
                success = false,
                error = e.message ?: "Unknown error executing query",
                queryExecuted = cypherQuery,
            )
        }

    private suspend fun generateNaturalLanguageResponse(
        originalQuery: String,
        cypherQuery: String,
        queryResults: List<Map<String, Any>>,
        graphContext: GraphContext?,
        conversation: AgentConversation,
        apiKey: String,
    ): String {
        // Enhanced date context for natural language generation
        val currentDate = java.time.LocalDate.now()
        val currentDateTime = java.time.Instant.now()
        val dateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy")
        val enhancedDateContext = buildEnhancedDateContextForNLG(currentDate, currentDateTime)
        
        val systemPrompt =
            """
            You are a business analyst expert at interpreting data query results and providing clear, actionable insights.
            
            ENHANCED DATE & TIME AWARENESS:
            $enhancedDateContext
            
            When discussing time-related data, use this current date context to:
            - Make relative time references more meaningful ("as of today", "in the last week", "this month so far")
            - Provide context for trends and patterns
            - Suggest time-aware follow-up questions
            - Identify seasonality or time-based patterns
            
            Your task is to analyze the query results and provide a natural language response that:
            1. Directly answers the user's question with enhanced time awareness
            2. Highlights key insights and patterns (especially time-based trends)
            3. Provides business context and implications
            4. Suggests follow-up questions or actions when relevant
            5. Uses clear, non-technical language
            6. Incorporates current date context when relevant to the analysis
            
            FORMAT YOUR RESPONSE USING MARKDOWN:
            - Use **bold** for key metrics and important numbers
            - Use headers (## and ###) to organize different sections
            - Use bullet points for lists of insights
            - Use tables when presenting structured data
            - Use `code formatting` for technical terms or specific values
            - Include line breaks between paragraphs for better readability
            - Use date context to make time-based insights more meaningful
            
            Make your response visually appealing and easy to scan with proper markdown formatting.
            Include time-aware insights when dealing with temporal data.
            """.trimIndent()
        
        val userPrompt =
            """
            Original Question: $originalQuery
            
            Cypher Query Used: $cypherQuery
            
            Query Results: ${objectMapper.writeValueAsString(queryResults)}
            
            Please provide a comprehensive analysis of these results in natural language.
            """.trimIndent()
        
        val messages =
            listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt),
            )
        
        val request =
            CreateCompletionRequest(
                model = System.getenv("OPENAI_MODEL"),
                messages = messages,
                temperature = 0.3,
            )
        
        return try {
            val response = modelService.fetchCompletionPayload(request, apiKey)
            response
        } catch (e: Exception) {
            logger.error(e) { "Error generating natural language response" }
            "I found the data you requested, but I'm having trouble summarizing it clearly. Here are the raw results: ${objectMapper.writeValueAsString(queryResults)}"
        }
    }

    /**
     * Get conversation history for a specific conversation ID.
     */
    fun getConversationHistory(conversationId: String): AgentConversation? = activeConversations[conversationId]

    /**
     * Build enhanced date context specifically for natural language generation.
     */
    private fun buildEnhancedDateContextForNLG(
        currentDate: java.time.LocalDate,
        currentDateTime: java.time.Instant,
    ): String {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy")
        val dateOnlyFormatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
        
        return """
            CURRENT DATE CONTEXT (Today is ${currentDate.format(formatter)}):
            - Today: ${currentDate.format(dateOnlyFormatter)}
            - This week: ${currentDate.with(java.time.DayOfWeek.MONDAY).format(dateOnlyFormatter)} to ${currentDate.with(java.time.DayOfWeek.SUNDAY).format(dateOnlyFormatter)}
            - This month: ${currentDate.withDayOfMonth(1).format(dateOnlyFormatter)} to ${currentDate.withDayOfMonth(currentDate.lengthOfMonth()).format(dateOnlyFormatter)}
            - Last 7 days: ${currentDate.minusDays(6).format(dateOnlyFormatter)} to ${currentDate.format(dateOnlyFormatter)}
            - Last 30 days: ${currentDate.minusDays(29).format(dateOnlyFormatter)} to ${currentDate.format(dateOnlyFormatter)}
            - Year to date: ${currentDate.withDayOfYear(1).format(dateOnlyFormatter)} to ${currentDate.format(dateOnlyFormatter)}
            
            Use this context to make time-based insights more relevant and actionable for business users.
            When you see dates in the data, relate them to these current time periods for better context.
        """.trimIndent()
    }

    /**
     * Build enhanced error message with context for better LLM understanding.
     */
    private fun buildEnhancedErrorMessage(originalError: String?, failedQuery: String): String {
        val errorMsg = originalError ?: "Unknown error"
        
        return when {
            errorMsg.contains("can only be one RETURN", ignoreCase = true) -> """
                CYPHER SYNTAX ERROR: $errorMsg
                
                SPECIFIC ISSUE: The query contains multiple RETURN statements, which is invalid Cypher syntax.
                FAILED QUERY: $failedQuery
                
                FIX REQUIRED: Use only ONE RETURN statement per query. If you need multiple aggregations, use WITH clauses to pipe results between steps.
                
                Example fix:
                WRONG: MATCH (n) RETURN count(n) RETURN count(n) ORDER BY count(n)
                RIGHT: MATCH (n) RETURN count(n) ORDER BY count(n)
            """.trimIndent()
            
            errorMsg.contains("syntax error", ignoreCase = true) ||
            errorMsg.contains("invalid syntax", ignoreCase = true) ||
            errorMsg.contains("unexpected token", ignoreCase = true) -> """
                CYPHER SYNTAX ERROR: $errorMsg
                
                FAILED QUERY: $failedQuery
                
                FIX REQUIRED: Review the Cypher query syntax. Common issues:
                - Check parentheses and bracket matching
                - Ensure proper clause ordering (MATCH, WHERE, WITH, RETURN)
                - Verify all variables are properly defined
                - Check for typos in keywords and function names
            """.trimIndent()
            
            errorMsg.contains("apoc", ignoreCase = true) ||
            errorMsg.contains("doesn't exist", ignoreCase = true) ||
            errorMsg.contains("datetime", ignoreCase = true) -> """
                FUNCTION ERROR: $errorMsg
                
                FAILED QUERY: $failedQuery
                
                FIX REQUIRED: Replace unsupported functions with string-based operations:
                - Replace apoc.date.* with substring() and string operations
                - Replace datetime() with string comparisons
                - Use hardcoded date strings for calculations
            """.trimIndent()
            
            else -> """
                QUERY ERROR: $errorMsg
                
                FAILED QUERY: $failedQuery
                
                FIX REQUIRED: Review the error message and fix the specific issue reported.
            """.trimIndent()
        }
    }

    /**
     * Clear old conversations to prevent memory leaks.
     */
    fun cleanupOldConversations(maxAgeHours: Int = 24) {
        val cutoffTime = Instant.now().minusSeconds(maxAgeHours * 3600L)
        val toRemove =
            activeConversations
                .filter { (_, conversation) ->
                    conversation.lastUpdated.isBefore(cutoffTime)
                }.keys
        
        toRemove.forEach { key ->
            activeConversations.remove(key)
        }
        
        if (toRemove.isNotEmpty()) {
            logger.info { "Cleaned up ${toRemove.size} old agent conversations" }
        }
    }
} 
