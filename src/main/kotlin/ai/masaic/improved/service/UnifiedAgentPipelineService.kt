package ai.masaic.improved.service

import ai.masaic.improved.ModelService
import ai.masaic.improved.createResponse
import ai.masaic.improved.model.*
import ai.masaic.openresponses.api.model.CreateResponseRequest
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.models.responses.ResponseTextConfig
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
 * Unified Agent Pipeline Service with clean, predictable flow.
 * 
 * This service implements a simplified, unified pipeline:
 * 1. Query Analysis - Determines response strategy upfront
 * 2. Cypher Generation & Execution - Gets the data
 * 3. Natural Language Response - Generates text response
 * 4. Visualization Execution - Handles all visualization types based on strategy
 * 5. Response Assembly - Puts it all together
 * 
 * No more scattered logic, complex fallbacks, or redundant analysis.
 * Clear decision points ensure multiple visuals are generated when appropriate.
 */
@Service
class UnifiedAgentPipelineService(
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
     * Process an agent query using the unified pipeline.
     */
    suspend fun processQuery(
        request: AgentQueryRequest,
        apiKey: String,
    ): Flow<ServerSentEvent<String>> =
        flow {
            logger.info { "Starting unified pipeline for: ${request.query}" }
            
            try {
                // Initialize conversation
                val conversation = getOrCreateConversation(request.conversationId)
                emit(createEvent(AgentResponseEvent.QueryStarted(request.query, conversation.id)))
                conversation.addMessage(AgentMessage(AgentRole.USER, request.query))
                
                // STAGE 1: COMPREHENSIVE QUERY ANALYSIS
                emit(createEvent(AgentResponseEvent.Progress("Analyzing Query", 0.1f, "Understanding query intent and determining optimal response strategy")))
                
                val analysis = queryAnalysisService.analyzeQuery(
                    query = request.query,
                    conversation = conversation,
                    queryResults = null, // We don't have data yet
                    apiKey = apiKey
                )
                
                logger.info { "Query Analysis Complete: ${analysis.responseStrategy}, confidence: ${analysis.confidenceLevel}" }
                logger.info { "Visualization: beneficial=${analysis.visualizationPotential.beneficial}, multiple=${analysis.visualizationPotential.multipleVisualsRecommended}, count=${analysis.visualizationPotential.suggestedVisualCount}" }
                
                // Analysis complete - strategy determined
                logger.info { "Analysis complete: ${analysis.responseStrategy}" }
                
                // STAGE 2: CYPHER GENERATION & EXECUTION
                emit(createEvent(AgentResponseEvent.Progress("Generating Query", 0.3f, "Creating optimized database query")))
                
                var retryCount = 0
                var lastError: String? = null
                var cypherResponse: CypherGenerationResponse? = null
                var executionResult: CypherExecutionResult? = null
                
                while (retryCount <= request.maxRetries) {
                    try {
                        // Generate Cypher query
                        cypherResponse = generateCypherQuery(
                            query = request.query,
                            conversation = conversation,
                            previousError = lastError,
                            analysis = analysis,
                            apiKey = apiKey
                        )
                        
                        emit(createEvent(AgentResponseEvent.CypherGenerated(
                            cypherQuery = cypherResponse.cypherQuery,
                            explanation = cypherResponse.explanation,
                            confidence = cypherResponse.confidence
                        )))
                        
                        // Execute Cypher query
                        emit(createEvent(AgentResponseEvent.Progress("Executing Query", 0.5f, "Running query against graph database")))
                        
                        val startTime = System.currentTimeMillis()
                        executionResult = executeCypherQuery(cypherResponse.cypherQuery)
                        val executionTime = System.currentTimeMillis() - startTime
                        
                        emit(createEvent(AgentResponseEvent.QueryExecuted(
                            success = executionResult.success,
                            resultCount = executionResult.results?.size ?: 0,
                            executionTime = executionTime
                        )))
                        
                        if (executionResult.success) {
                            break // Success, exit retry loop
                        } else {
                            lastError = buildEnhancedErrorMessage(executionResult.error, cypherResponse.cypherQuery)
                            retryCount++
                            
                            if (retryCount <= request.maxRetries) {
                                emit(createEvent(AgentResponseEvent.QueryRetry(
                                    attempt = retryCount + 1,
                                    maxRetries = request.maxRetries + 1,
                                    error = lastError ?: "Unknown query error"
                                )))
                            }
                        }
                    } catch (e: Exception) {
                        lastError = "Generation error: ${e.message}"
                        retryCount++
                        
                        if (retryCount <= request.maxRetries) {
                            emit(createEvent(AgentResponseEvent.QueryRetry(
                                attempt = retryCount + 1,
                                maxRetries = request.maxRetries + 1,
                                error = lastError ?: "Unknown error"
                            )))
                        }
                        
                        logger.error(e) { "Error generating/executing Cypher query (attempt $retryCount)" }
                    }
                }
                
                // Check if query execution succeeded
                if (executionResult?.success != true || cypherResponse == null) {
                    val errorMessage = "Failed to execute query after ${request.maxRetries + 1} attempts. Last error: $lastError"
                    logger.error { errorMessage }
                    
                    val errorResponse = AgentQueryResponse(
                        conversationId = conversation.id,
                        naturalLanguageResponse = "I apologize, but I'm having trouble executing the query for your request. Please try rephrasing your question or asking something more specific about the conversation data.",
                        retryCount = retryCount - 1,
                        error = errorMessage
                    )
                    
                    emit(createEvent(AgentResponseEvent.Error(errorMessage, retryable = true)))
                    emit(createEvent(AgentResponseEvent.Complete(errorResponse)))
                    return@flow
                }
                
                val results = executionResult.results!!
                
                // STAGE 3: NATURAL LANGUAGE RESPONSE GENERATION
                emit(createEvent(AgentResponseEvent.Progress("Generating Response", 0.7f, "Creating comprehensive natural language response")))
                
                val naturalResponse = generateNaturalLanguageResponse(
                    originalQuery = request.query,
                    cypherQuery = cypherResponse.cypherQuery,
                    queryResults = results,
                    analysis = analysis,
                    conversation = conversation,
                    apiKey = apiKey
                )
                
                emit(createEvent(AgentResponseEvent.NaturalLanguageGenerated(naturalResponse)))
                
                // STAGE 4: VISUALIZATION EXECUTION (Based on Analysis Strategy)
                var visualization: VisualizationData? = null
                var parallelVisualizations: ParallelVisualizationData? = null
                
                if (request.enableVisualization && results.isNotEmpty() && analysis.visualizationPotential.beneficial) {
                    emit(createEvent(AgentResponseEvent.Progress("Creating Visualizations", 0.9f, "Executing visualization strategy: ${analysis.responseStrategy}")))
                    
                    try {
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
                                    logger.info { "Generated single visualization: ${visualization.title}" }
                                    
                                    // Emit Python analysis events if used
                                    visualization.pythonAnalysis?.let { pythonResult ->
                                        emit(createEvent(AgentResponseEvent.PythonAnalysisStarted(
                                            code = pythonResult.code,
                                            description = "Performing advanced data analysis"
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
                                    
                                    parallelVisualizations = ParallelVisualizationData(
                                        primaryVisualization = primary,
                                        additionalVisualizations = additional,
                                        totalUnits = vizResult.visualizations.size,
                                        executionSummary = vizResult.summary
                                    )
                                    
                                    visualization = primary // Backward compatibility
                                    
                                    logger.info { "Generated ${vizResult.visualizations.size} visualizations" }
                                    
                                    // Emit parallel visualization events
                                    emit(createEvent(AgentResponseEvent.ParallelVisualizationStarted(
                                        totalUnits = parallelVisualizations.totalUnits,
                                        units = vizResult.visualizations.map { it.unitName ?: it.title }
                                    )))
                                    
                                    // Emit events for each visualization
                                    vizResult.visualizations.forEach { viz ->
                                        viz.pythonAnalysis?.let { pythonResult ->
                                            emit(createEvent(AgentResponseEvent.PythonAnalysisStarted(
                                                code = pythonResult.code,
                                                description = "Advanced analysis for ${viz.unitName ?: viz.title}"
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
                            logger.warn { "Visualization execution failed: ${vizResult.summary}" }
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Error in visualization execution: ${e.message}" }
                    }
                } else {
                    logger.info { "Visualization skipped: enabled=${request.enableVisualization}, hasResults=${results.isNotEmpty()}, beneficial=${analysis.visualizationPotential.beneficial}" }
                }
                
                // STAGE 5: RESPONSE ASSEMBLY
                emit(createEvent(AgentResponseEvent.Progress("Finalizing", 0.95f, "Assembling final response")))
                
                val finalResponse = AgentQueryResponse(
                    conversationId = conversation.id,
                    naturalLanguageResponse = naturalResponse,
                    cypherQuery = cypherResponse.cypherQuery,
                    queryResults = results,
                    retryCount = retryCount,
                    visualization = visualization,
                    parallelVisualizations = parallelVisualizations,
                    metadata = mapOf(
                        "queryAnalysis" to analysis,
                        "visualizationStrategy" to analysis.responseStrategy
                    )
                )
                
                // Add assistant message to conversation
                conversation.addMessage(
                    AgentMessage(
                        AgentRole.ASSISTANT,
                        naturalResponse,
                        metadata = finalResponse.metadata
                    )
                )
                
                emit(createEvent(AgentResponseEvent.Progress("Complete", 1.0f, "Query processing completed successfully")))
                emit(createEvent(AgentResponseEvent.Complete(finalResponse)))
                
            } catch (e: Exception) {
                logger.error(e) { "Unexpected error in unified pipeline: ${e.message}" }
                
                val errorResponse = AgentQueryResponse(
                    conversationId = request.conversationId ?: UUID.randomUUID().toString(),
                    naturalLanguageResponse = "I encountered an unexpected error while processing your request. Please try again.",
                    error = "Pipeline error: ${e.message}"
                )
                
                emit(createEvent(AgentResponseEvent.Error("Pipeline error: ${e.message}", retryable = true)))
                emit(createEvent(AgentResponseEvent.Complete(errorResponse)))
            }
        }

    // Helper methods (consolidated and simplified)
    
    /**
     * Ensures messages list has at least one user message.
     * Chat completion APIs require at least one user message and won't accept only system messages.
     */
    private fun ensureUserMessage(messages: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val hasUserMessage = messages.any { it["role"] == "user" }
        return if (!hasUserMessage && messages.isNotEmpty()) {
            // Convert the last system message to user message, or add a minimal user message
            val lastMessage = messages.last()
            if (lastMessage["role"] == "system") {
                messages.dropLast(1) + mapOf("role" to "user", "content" to lastMessage["content"])
            } else {
                messages + mapOf("role" to "user", "content" to "Please analyze and respond.")
            }
        } else {
            messages
        }
    }
    
    private fun getOrCreateConversation(conversationId: String?): AgentConversation =
        if (conversationId != null && activeConversations.containsKey(conversationId)) {
            activeConversations[conversationId]!!.copy(lastUpdated = Instant.now())
        } else {
            val newId = conversationId ?: "agent_conv_${UUID.randomUUID().toString().replace("-", "").substring(0, 10)}"
            val conversation = AgentConversation(id = newId)
            activeConversations[newId] = conversation
            conversation
        }

    private fun createEvent(event: AgentResponseEvent): ServerSentEvent<String> =
        ServerSentEvent.builder<String>()
            .id(UUID.randomUUID().toString())
            .event(event.type)
            .data(objectMapper.writeValueAsString(event))
            .build()

    private suspend fun generateCypherQuery(
        query: String,
        conversation: AgentConversation,
        previousError: String?,
        analysis: QueryAnalysis,
        apiKey: String,
    ): CypherGenerationResponse {
        val graphContext = buildGraphContext()
        val systemPrompt = buildCypherGenerationPrompt(graphContext, previousError, analysis)
        val conversationHistory = buildConversationHistory(conversation)
        
        val messages = mutableListOf<Map<String, Any>>().apply {
            add(mapOf("role" to "system", "content" to systemPrompt))
            if (conversationHistory.isNotEmpty()) {
                addAll(conversationHistory)
            }
            add(mapOf("role" to "user", "content" to query))
        }
        
        val request = CreateResponseRequest(
            model = "openai@gpt-4.1",
            input = messages,
            temperature = 0.1,
            text = objectMapper.readValue(objectMapper.writeValueAsString(mapOf(
                "format" to mapOf(
                    "type" to "json_schema",
                    "name" to "cypher_response",
                    "schema" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "cypherQuery" to mapOf("type" to "string"),
                            "explanation" to mapOf("type" to "string"),
                            "confidence" to mapOf("type" to "string")
                        ),
                        "required" to listOf("cypherQuery", "explanation"),
                        "additionalProperties" to false
                    ),
                    "strict" to true
                )
            )), ResponseTextConfig::class.java)
        )
        
        return modelService.createResponse(request, apiKey)
    }

    private suspend fun generateNaturalLanguageResponse(
        originalQuery: String,
        cypherQuery: String,
        queryResults: List<Map<String, Any>>,
        analysis: QueryAnalysis,
        conversation: AgentConversation,
        apiKey: String,
    ): String {
        val systemPrompt = """
            You are an expert business analyst providing insights based on data query results.
            
            Query Context:
            - Query Type: ${analysis.queryType}
            - Complexity: ${analysis.complexity}
            - Response Strategy: ${analysis.responseStrategy}
            
            Your task is to provide a comprehensive response that:
            1. Directly answers the user's question
            2. Highlights key insights and patterns
            3. Provides business context and implications
            4. Uses clear, professional markdown formatting
            5. Suggests follow-up questions when relevant
            
            Format your response using markdown with:
            - **bold** for key metrics
            - ## headers for main sections
            - Bullet points for insights
            - Tables for structured data when appropriate
        """.trimIndent()

        val userPrompt = """
            Original Question: $originalQuery
            
            Cypher Query: $cypherQuery
            
            Query Results: ${objectMapper.writeValueAsString(queryResults)}
            
            Provide a comprehensive analysis of these results.
        """.trimIndent()

        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        )

        val request = CreateResponseRequest(
            model = "openai@gpt-4.1",
            input = messages,
            temperature = 0.3
        )

        return try {
            modelService.fetchResponsePayload(request, apiKey)
        } catch (e: Exception) {
            logger.error(e) { "Error generating natural language response" }
            "I found the data you requested, but I'm having trouble summarizing it clearly. Here are the raw results: ${objectMapper.writeValueAsString(queryResults)}"
        }
    }

    // Reuse existing helper methods from AgentQueryStreamingService
    private suspend fun buildGraphContext(): GraphContext? {
        return try {
            val statistics = graphService.getMigrationStatistics()
            GraphContext(
                totalConversations = statistics.conversationsInGraph,
                totalPathNodes = statistics.pathNodesInGraph,
                totalRelationships = statistics.totalRelationships,
                rootPaths = emptyList(),
                samplePaths = emptyList(),
                pathTreeSummary = "Graph context available"
            )
        } catch (e: Exception) {
            logger.error(e) { "Error building graph context: ${e.message}" }
            null
        }
    }

    private fun buildCypherGenerationPrompt(
        graphContext: GraphContext?,
        previousError: String?,
        analysis: QueryAnalysis,
    ): String {
        val prompt = StringBuilder()
        val currentDate = java.time.LocalDate.now()
        
        prompt.appendLine("""
            You are a Neo4j Cypher query expert specializing in analyzing conversation data.
            
            CURRENT DATE CONTEXT:
            - Today's date: $currentDate (YYYY-MM-DD format)
            
            GRAPH SCHEMA:
            - Conversation nodes: id, createdAt (ISO string), summary, resolved (boolean), nps (0-10), classification, messageCount
            - PathNode nodes: name (unique identifier for categories)
            - Relationships: PathNode-[:HAS_CHILD]->PathNode, PathNode-[:CONTAINS]->Conversation
            
            QUERY CONTEXT:
            - Query Type: ${analysis.queryType}
            - Complexity: ${analysis.complexity}
            - Data Characteristics: ${analysis.dataCharacteristics}
            
            CRITICAL MEMGRAPH RESTRICTIONS:
            - NO APOC functions (apoc.date.*, etc.) - use string operations
            - NO datetime functions: date(), duration(), time() - use string comparisons
            - NO EXISTS subqueries - use exists(pattern) with WHERE clause instead
            - NO COUNT subqueries - use count() aggregation function instead  
            - NO COLLECT subqueries - use collect() aggregation function instead
            - NO shortestPath() - use [*BFS] traversal instead
            - NO NOT label expressions (!ACTED_IN) - use WHERE type(r) != "ACTED_IN"
            - NO fixed length patterns (--{2}) - use variable length [*2] instead
            - NO patterns in expressions except exists(pattern)
            - NO multiple values after WHEN in CASE - use separate WHEN clauses with OR
            - NO unsupported functions: toBooleanList(), isEmpty(), elementId(), percentileCont(), isNan(), normalize()
            - EXACTLY ONE query per response - no multiple queries separated by semicolons
            - EXACTLY ONE RETURN statement per query
            - For dates: c.createdAt >= '2024-01-01T00:00:00Z' format
            - NO CALL subqueries - they are not supported
            - NO UNION queries - use single query with aggregation
            - NO comments in the query - only pure Cypher code
            - NO multiple MATCH statements - combine into single query flow
            
            MANDATORY QUERY STRUCTURE:
            - Start with MATCH to find all needed nodes
            - Use WHERE for filtering
            - Use WITH for data transformation and grouping
            - Use aggregation functions (count, avg, sum, collect)
            - End with single RETURN statement
            - Example: MATCH (c:Conversation) WHERE ... WITH ... RETURN ...
            
            Generate a valid Cypher query that answers the user's question effectively.
        """.trimIndent())
        
        if (graphContext != null) {
            prompt.appendLine("\nGRAPH STATE:")
            prompt.appendLine("- Conversations: ${graphContext.totalConversations}")
            prompt.appendLine("- Path nodes: ${graphContext.totalPathNodes}")
        }
        
        if (previousError != null) {
            prompt.appendLine("\nPREVIOUS ERROR (fix this):")
            prompt.appendLine(previousError)
        }
        
        return prompt.toString()
    }

    private fun buildConversationHistory(conversation: AgentConversation): List<Map<String, Any>> =
        conversation.messages.takeLast(10).map { message ->
            mapOf(
                "role" to message.role.name.lowercase(),
                "content" to message.content
            )
        }

    private suspend fun executeCypherQuery(cypherQuery: String): CypherExecutionResult =
        try {
            memgraphDriver.session(SessionConfig.forDatabase("memgraph")).use { session ->
                val result = session.run(cypherQuery)
                val records = result.list { record ->
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
                
                CypherExecutionResult(success = true, results = records, queryExecuted = cypherQuery)
            }
        } catch (e: Exception) {
            logger.error(e) { "Error executing Cypher query: $cypherQuery" }
            CypherExecutionResult(
                success = false,
                error = e.message ?: "Unknown error executing query",
                queryExecuted = cypherQuery
            )
        }

    private fun buildEnhancedErrorMessage(originalError: String?, failedQuery: String): String {
        val errorMsg = originalError ?: "Unknown error"
        
        return when {
            errorMsg.contains("can only be one RETURN", ignoreCase = true) -> 
                "SYNTAX ERROR: Multiple RETURN statements found. Use only ONE RETURN statement per query. Failed query: $failedQuery"
            
            errorMsg.contains("apoc", ignoreCase = true) || errorMsg.contains("datetime", ignoreCase = true) -> 
                "FUNCTION ERROR: APOC/datetime functions not available. Use string operations instead. Failed query: $failedQuery"
            
            errorMsg.contains("Unknown key 'days'", ignoreCase = true) || errorMsg.contains("duration(", ignoreCase = true) || errorMsg.contains("date()", ignoreCase = true) -> 
                "FUNCTION ERROR: date() and duration() functions not supported. Use string date comparisons instead (e.g., c.createdAt >= '2024-01-01T00:00:00Z'). Failed query: $failedQuery"
            
            errorMsg.contains("mismatched input 'AS'", ignoreCase = true) || errorMsg.contains("CALL", ignoreCase = true) -> 
                "SYNTAX ERROR: Invalid CALL subquery syntax. Use standard MATCH/WITH/RETURN flow instead of CALL subqueries. Avoid 'CALL { ... } AS alias' patterns. Failed query: $failedQuery"
            
            errorMsg.contains("UNION", ignoreCase = true) -> 
                "SYNTAX ERROR: UNION queries not supported. Use single query with COLLECT/aggregation instead. Failed query: $failedQuery"
            
            errorMsg.contains("mismatched input 'MATCH' expecting <EOF>", ignoreCase = true) -> 
                "SYNTAX ERROR: Multiple queries detected in single request. Generate EXACTLY ONE query without semicolons or multiple MATCH statements. Failed query: $failedQuery"
            
            errorMsg.contains("Variable in subquery already declared", ignoreCase = true) -> 
                "SYNTAX ERROR: CALL subqueries not supported. Use single MATCH/WITH/RETURN flow instead. Failed query: $failedQuery"
            
            errorMsg.contains("An assistant message must be preceded by a user message", ignoreCase = true) || 
            errorMsg.contains("conversation must include at least one user message", ignoreCase = true) ||
            errorMsg.contains("messages must contain at least one user message", ignoreCase = true) -> 
                "API ERROR: Chat completion requires at least one user message. Cannot send only system messages. Failed query: $failedQuery"
            
            else -> "QUERY ERROR: $errorMsg. Failed query: $failedQuery"
        }
    }

    // Conversation management
    fun getConversationHistory(conversationId: String): AgentConversation? = activeConversations[conversationId]

    fun cleanupOldConversations(maxAgeHours: Int = 24) {
        val cutoffTime = Instant.now().minusSeconds(maxAgeHours * 3600L)
        val toRemove = activeConversations
            .filter { (_, conversation) -> conversation.lastUpdated.isBefore(cutoffTime) }
            .keys
        
        toRemove.forEach { key -> activeConversations.remove(key) }
        
        if (toRemove.isNotEmpty()) {
            logger.info { "Cleaned up ${toRemove.size} old conversations" }
        }
    }
} 
