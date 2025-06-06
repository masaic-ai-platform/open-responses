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
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-stage intelligent pipeline service for agent queries.
 * 
 * This service implements a sophisticated pipeline that:
 * 1. Analyzes query intent and complexity upfront (Stage 1: Intent Analysis)
 * 2. Makes early decisions about response strategy (Stage 2: Strategy Planning)
 * 3. Generates multiple query variations for comprehensive analysis (Stage 3: Query Generation)
 * 4. Executes and analyzes results intelligently (Stage 4: Execution & Analysis)
 * 5. Provides tailored response based on early decisions (Stage 5: Response Generation)
 * 
 * This approach solves the "single datapoint" problem by using multiple analysis stages
 * and making intelligent decisions early in the pipeline.
 */
@Service
class IntelligentAgentPipelineService(
    private val modelService: ModelService,
    private val graphService: ConversationGraphService,
    private val memgraphDriver: Driver,
    private val intelligentVisualizationService: IntelligentVisualizationService,
) {
    private val logger = KotlinLogging.logger {}
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    
    // Store active agent conversations in memory
    private val activeConversations = ConcurrentHashMap<String, AgentConversation>()

    /**
     * Process an agent query using the intelligent multi-stage pipeline.
     */
    suspend fun processIntelligentQuery(
        request: AgentQueryRequest,
        apiKey: String,
    ): Flow<ServerSentEvent<String>> =
        flow {
            logger.info { "Starting intelligent multi-stage pipeline for: ${request.query}" }
            
            try {
                // Get or create conversation
                val conversation = getOrCreateConversation(request.conversationId)
                emit(createEvent(AgentResponseEvent.QueryStarted(request.query, conversation.id)))
                
                // Add user message to conversation
                conversation.addMessage(AgentMessage(AgentRole.USER, request.query))
                
                // STAGE 1: INTENT ANALYSIS & COMPLEXITY ASSESSMENT
                emit(createEvent(AgentResponseEvent.Progress("Analyzing Intent", 0.1f, "Understanding query complexity and intent")))
                val intentAnalysis = analyzeQueryIntent(request.query, conversation, apiKey)
                
                emit(createEvent(AgentResponseEvent.IntentAnalyzed(
                    queryType = intentAnalysis.queryType,
                    complexity = intentAnalysis.complexity,
                    expectedDataSize = intentAnalysis.expectedDataSize,
                    analysisRequirements = intentAnalysis.analysisRequirements,
                    reasoning = intentAnalysis.reasoning
                )))
                
                // STAGE 2: STRATEGY PLANNING
                emit(createEvent(AgentResponseEvent.Progress("Planning Strategy", 0.2f, "Determining optimal response strategy")))
                val strategy = planResponseStrategy(intentAnalysis, request, apiKey)
                
                emit(createEvent(AgentResponseEvent.StrategyPlanned(
                    responseType = strategy.primaryResponseType,
                    requiresVisualization = strategy.requiresVisualization,
                    visualizationType = strategy.visualizationType,
                    requiresPythonAnalysis = strategy.requiresPythonAnalysis,
                    multiQueryStrategy = strategy.multiQueryStrategy,
                    reasoning = strategy.reasoning
                )))
                
                // STAGE 3: QUERY GENERATION (Multiple queries if needed)
                emit(createEvent(AgentResponseEvent.Progress("Generating Queries", 0.3f, "Creating optimized database queries")))
                val querySet = generateIntelligentQuerySet(request.query, conversation, intentAnalysis, strategy, apiKey)
                
                emit(createEvent(AgentResponseEvent.QueriesGenerated(
                    primaryQuery = querySet.primaryQuery,
                    supportingQueries = querySet.supportingQueries,
                    queryCount = querySet.supportingQueries.size + 1
                )))
                
                // STAGE 4: EXECUTION & ANALYSIS
                emit(createEvent(AgentResponseEvent.Progress("Executing Analysis", 0.4f, "Running queries and analyzing results")))
                val analysisResults = executeAndAnalyzeQuerySet(querySet, apiKey)
                
                if (!analysisResults.success) {
                    // Handle retry logic here if needed
                    val errorResponse = AgentQueryResponse(
                        conversationId = conversation.id,
                        naturalLanguageResponse = "I apologize, but I encountered issues executing the analysis. Please try rephrasing your question.",
                        error = analysisResults.error
                    )
                    emit(createEvent(AgentResponseEvent.Complete(errorResponse)))
                    return@flow
                }
                
                emit(createEvent(AgentResponseEvent.AnalysisCompleted(
                    primaryResults = analysisResults.primaryResults,
                    supportingResults = analysisResults.supportingResults,
                    insights = analysisResults.insights
                )))
                
                // STAGE 5A: NATURAL LANGUAGE RESPONSE GENERATION
                emit(createEvent(AgentResponseEvent.Progress("Generating Response", 0.6f, "Creating comprehensive natural language response")))
                val naturalResponse = generateIntelligentNaturalResponse(
                    request.query, querySet, analysisResults, strategy, intentAnalysis, conversation, apiKey
                )
                
                emit(createEvent(AgentResponseEvent.NaturalLanguageGenerated(naturalResponse)))
                
                // STAGE 5B: VISUALIZATION GENERATION (If planned)
                var visualization: VisualizationData? = null
                if (strategy.requiresVisualization && analysisResults.primaryResults.isNotEmpty()) {
                    emit(createEvent(AgentResponseEvent.Progress("Creating Visualization", 0.8f, "Generating intelligent visualization")))
                    
                    visualization = if (strategy.requiresPythonAnalysis) {
                        intelligentVisualizationService.generateIntelligentVisualization(
                            request.query,
                            querySet.primaryQuery.cypherQuery,
                            analysisResults.primaryResults,
                            naturalResponse,
                            apiKey,
                            enablePythonAnalysis = true
                        )
                    } else {
                        intelligentVisualizationService.generateIntelligentVisualization(
                            request.query,
                            querySet.primaryQuery.cypherQuery,
                            analysisResults.primaryResults,
                            naturalResponse,
                            apiKey,
                            enablePythonAnalysis = false
                        )
                    }
                    
                    if (visualization != null) {
                        emit(createEvent(AgentResponseEvent.VisualizationGenerated(
                            chartType = visualization.chartType,
                            title = visualization.title,
                            description = visualization.description
                        )))
                        
                        // Also emit the full visualization data for frontend compatibility
                        emit(createEvent(AgentResponseEvent.VisualizationGeneratedWithData(visualization)))
                    }
                }
                
                // STAGE 6: FINALIZATION
                emit(createEvent(AgentResponseEvent.Progress("Finalizing", 0.95f, "Preparing final response")))
                
                val finalResponse = AgentQueryResponse(
                    conversationId = conversation.id,
                    naturalLanguageResponse = naturalResponse,
                    cypherQuery = querySet.primaryQuery.cypherQuery,
                    queryResults = analysisResults.primaryResults,
                    visualization = visualization,
                    metadata = mapOf(
                        "intentAnalysis" to intentAnalysis,
                        "strategy" to strategy,
                        "querySet" to querySet,
                        "analysisResults" to analysisResults
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
                
                emit(createEvent(AgentResponseEvent.Progress("Complete", 1.0f, "Intelligent analysis completed successfully")))
                emit(createEvent(AgentResponseEvent.Complete(finalResponse)))
                
            } catch (e: Exception) {
                logger.error(e) { "Error in intelligent pipeline: ${e.message}" }
                
                val errorResponse = AgentQueryResponse(
                    conversationId = request.conversationId ?: UUID.randomUUID().toString(),
                    naturalLanguageResponse = "I encountered an error during the intelligent analysis. Please try again.",
                    error = "Pipeline error: ${e.message}"
                )
                
                emit(createEvent(AgentResponseEvent.Error("Pipeline error: ${e.message}", retryable = true)))
                emit(createEvent(AgentResponseEvent.Complete(errorResponse)))
            }
        }

    /**
     * STAGE 1: Analyze query intent and complexity to make early pipeline decisions.
     */
    private suspend fun analyzeQueryIntent(
        query: String,
        conversation: AgentConversation,
        apiKey: String
    ): QueryIntentAnalysis {
        val systemPrompt = """
            You are an expert query analyst. Analyze the user's query to understand:
            1. Query type and business intent
            2. Expected complexity of analysis required
            3. Likely data size and structure needs
            4. Whether advanced analysis (Python) would be beneficial
            5. Visualization potential
            
            QUERY TYPES:
            - SIMPLE_COUNT: Basic counting queries
            - TIME_ANALYSIS: Time-based trends and patterns
            - COMPARISON: Comparing categories or groups
            - DISTRIBUTION: Understanding data distribution
            - CORRELATION: Finding relationships between variables
            - COMPLEX_ANALYTICS: Multi-step analysis requiring advanced calculations
            
            COMPLEXITY LEVELS:
            - LOW: Single query, basic aggregation
            - MEDIUM: Multiple data points, some calculations
            - HIGH: Advanced analytics, statistical analysis, complex transformations
            
            ANALYSIS REQUIREMENTS:
            - STATISTICAL: Requires statistical analysis (correlation, regression, etc.)
            - MATHEMATICAL: Requires mathematical calculations (percentages, growth rates, etc.)
            - TEMPORAL: Requires time-based analysis (trends, seasonality, etc.)
            - COMPARATIVE: Requires comparison analysis
            - FORECASTING: Requires predictive analysis
            - NONE: Basic data retrieval only
        """.trimIndent()
        
        val userPrompt = """
            User Query: $query
            
            Conversation History: ${buildConversationHistory(conversation).takeLast(3)}
            
            Analyze this query and provide a comprehensive assessment.
        """.trimIndent()
        
        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        )
        
        val request = CreateCompletionRequest(
            model = "gpt-4.1",
            messages = messages,
            temperature = 0.1,
            response_format = mapOf(
                "type" to "json_schema",
                "json_schema" to mapOf(
                    "name" to "query_intent_analysis",
                    "schema" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "queryType" to mapOf(
                                "type" to "string",
                                "enum" to listOf("SIMPLE_COUNT", "TIME_ANALYSIS", "COMPARISON", "DISTRIBUTION", "CORRELATION", "COMPLEX_ANALYTICS")
                            ),
                            "complexity" to mapOf(
                                "type" to "string", 
                                "enum" to listOf("LOW", "MEDIUM", "HIGH")
                            ),
                            "expectedDataSize" to mapOf(
                                "type" to "string",
                                "enum" to listOf("SMALL", "MEDIUM", "LARGE")
                            ),
                            "analysisRequirements" to mapOf(
                                "type" to "array",
                                "items" to mapOf("type" to "string"),
                                "enum" to listOf("STATISTICAL", "MATHEMATICAL", "TEMPORAL", "COMPARATIVE", "FORECASTING", "NONE")
                            ),
                            "visualizationPotential" to mapOf("type" to "boolean"),
                            "reasoning" to mapOf("type" to "string")
                        ),
                        "required" to listOf("queryType", "complexity", "expectedDataSize", "analysisRequirements", "visualizationPotential", "reasoning")
                    )
                )
            )
        )
        
        return try {
            modelService.createCompletion(request, apiKey)
        } catch (e: Exception) {
            logger.error(e) { "Error analyzing query intent: ${e.message}" }
            QueryIntentAnalysis(
                queryType = "SIMPLE_COUNT",
                complexity = "LOW",
                expectedDataSize = "SMALL",
                analysisRequirements = listOf("NONE"),
                visualizationPotential = false,
                reasoning = "Error during analysis: ${e.message}"
            )
        }
    }

    /**
     * STAGE 2: Plan the response strategy based on intent analysis.
     */
    private suspend fun planResponseStrategy(
        intentAnalysis: QueryIntentAnalysis,
        request: AgentQueryRequest,
        apiKey: String
    ): ResponseStrategy {
        val systemPrompt = """
            You are a response strategy planner. Based on the query intent analysis, determine:
            1. Primary response type needed
            2. Whether visualization is required and what type
            3. Whether Python analysis would add significant value
            4. Whether multiple queries are needed for comprehensive analysis
            
            RESPONSE TYPES:
            - TEXT_ONLY: Simple textual response sufficient
            - TEXT_WITH_SIMPLE_VIZ: Text + basic charts
            - TEXT_WITH_ADVANCED_VIZ: Text + advanced visualizations
            - ANALYTICAL_REPORT: Comprehensive analysis with multiple components
            
            VISUALIZATION TYPES:
            - NONE: No visualization needed
            - SIMPLE_CHART: Basic bar/line/pie charts
            - ADVANCED_CHART: Complex charts (heatmaps, treemaps, etc.)
            - PYTHON_GENERATED: Charts requiring Python analysis
            
            MULTI_QUERY_STRATEGIES:
            - SINGLE: One primary query sufficient
            - SUPPORTING: Primary + supporting queries for context
            - COMPARATIVE: Multiple queries for comparison analysis
            - COMPREHENSIVE: Multiple queries for thorough analysis
        """.trimIndent()
        
        val userPrompt = """
            Intent Analysis Results:
            - Query Type: ${intentAnalysis.queryType}
            - Complexity: ${intentAnalysis.complexity}
            - Expected Data Size: ${intentAnalysis.expectedDataSize}
            - Analysis Requirements: ${intentAnalysis.analysisRequirements.joinToString(", ")}
            - Visualization Potential: ${intentAnalysis.visualizationPotential}
            - Reasoning: ${intentAnalysis.reasoning}
            
            User Preferences:
            - Visualization Enabled: ${request.enableVisualization}
            - Python Analysis Enabled: ${request.enablePythonAnalysis}
            
            Plan the optimal response strategy.
        """.trimIndent()
        
        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        )
        
        val requestObj = CreateCompletionRequest(
            model = "gpt-4.1",
            messages = messages,
            temperature = 0.1,
            response_format = mapOf(
                "type" to "json_schema",
                "json_schema" to mapOf(
                    "name" to "response_strategy",
                    "schema" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "primaryResponseType" to mapOf(
                                "type" to "string",
                                "enum" to listOf("TEXT_ONLY", "TEXT_WITH_SIMPLE_VIZ", "TEXT_WITH_ADVANCED_VIZ", "ANALYTICAL_REPORT")
                            ),
                            "requiresVisualization" to mapOf("type" to "boolean"),
                            "visualizationType" to mapOf(
                                "type" to "string",
                                "enum" to listOf("NONE", "SIMPLE_CHART", "ADVANCED_CHART", "PYTHON_GENERATED")
                            ),
                            "requiresPythonAnalysis" to mapOf("type" to "boolean"),
                            "multiQueryStrategy" to mapOf(
                                "type" to "string",
                                "enum" to listOf("SINGLE", "SUPPORTING", "COMPARATIVE", "COMPREHENSIVE")
                            ),
                            "reasoning" to mapOf("type" to "string")
                        ),
                        "required" to listOf("primaryResponseType", "requiresVisualization", "visualizationType", "requiresPythonAnalysis", "multiQueryStrategy", "reasoning")
                    )
                )
            )
        )
        
        return try {
            modelService.createCompletion(requestObj, apiKey)
        } catch (e: Exception) {
            logger.error(e) { "Error planning response strategy: ${e.message}" }
            ResponseStrategy(
                primaryResponseType = "TEXT_ONLY",
                requiresVisualization = false,
                visualizationType = "NONE",
                requiresPythonAnalysis = false,
                multiQueryStrategy = "SINGLE",
                reasoning = "Error during planning: ${e.message}"
            )
        }
    }

    // Helper methods (simplified versions of existing methods)
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

    private fun buildConversationHistory(conversation: AgentConversation): List<Map<String, Any>> =
        conversation.messages.takeLast(10).map { message ->
            mapOf(
                "role" to message.role.name.lowercase(),
                "content" to message.content
            )
        }

    /**
     * STAGE 3: Generate intelligent query set based on strategy.
     */
    private suspend fun generateIntelligentQuerySet(
        query: String,
        conversation: AgentConversation,
        intentAnalysis: QueryIntentAnalysis,
        strategy: ResponseStrategy,
        apiKey: String
    ): QuerySet {
        // For complex analysis, generate multiple focused queries instead of one complex query
        if (intentAnalysis.complexity == "high" || strategy.multiQueryStrategy == "COMPREHENSIVE") {
            return generateParallelQuerySet(query, conversation, intentAnalysis, apiKey)
        }
        
        when (strategy.multiQueryStrategy) {
            "SINGLE" -> {
                // Generate just the primary query
                val primaryQuery = generatePrimaryQuery(query, conversation, intentAnalysis, apiKey)
                return QuerySet(
                    primaryQuery = primaryQuery,
                    supportingQueries = emptyList()
                )
            }
            "SUPPORTING" -> {
                // Generate primary query + supporting context queries
                val primaryQuery = generatePrimaryQuery(query, conversation, intentAnalysis, apiKey)
                val supportingQueries = generateSupportingQueries(query, intentAnalysis, apiKey)
                return QuerySet(
                    primaryQuery = primaryQuery,
                    supportingQueries = supportingQueries
                )
            }
            "COMPARATIVE" -> {
                // Generate multiple queries for comparison analysis
                val primaryQuery = generatePrimaryQuery(query, conversation, intentAnalysis, apiKey)
                val comparativeQueries = generateComparativeQueries(query, intentAnalysis, apiKey)
                return QuerySet(
                    primaryQuery = primaryQuery,
                    supportingQueries = comparativeQueries
                )
            }
            "COMPREHENSIVE" -> {
                // Use parallel query approach for comprehensive analysis
                return generateParallelQuerySet(query, conversation, intentAnalysis, apiKey)
            }
            else -> {
                // Fallback to single query
                val primaryQuery = generatePrimaryQuery(query, conversation, intentAnalysis, apiKey)
                return QuerySet(
                    primaryQuery = primaryQuery,
                    supportingQueries = emptyList()
                )
            }
        }
    }

    /**
     * Generate multiple focused queries for parallel execution.
     * This approach breaks down complex analysis into focused, independent queries.
     */
    private suspend fun generateParallelQuerySet(
        query: String,
        conversation: AgentConversation,
        intentAnalysis: QueryIntentAnalysis,
        apiKey: String
    ): QuerySet {
        val systemPrompt = """
            You are a Cypher query expert specializing in breaking down complex analytics into focused, parallel queries.
            
            Your task is to analyze a complex analytical request and break it down into multiple independent Cypher queries 
            that can be executed in parallel. Each query should focus on ONE specific metric or KPI.
            
            CRITICAL REQUIREMENTS:
            - Each query MUST be completely independent (no shared variables)
            - Use hardcoded date strings, NOT variables like 'startDate'
            - Each query should return ONE focused metric or dataset
            - Avoid complex multi-part queries with multiple WITH clauses
            - DO NOT use APOC functions or datetime() functions
            - Use string-based date operations only
            
            QUERY BREAKDOWN STRATEGY:
            For performance analysis requests, generate separate queries for:
            1. Overall volume metrics (total conversations, counts)
            2. Resolution rate metrics (resolved vs unresolved)
            3. NPS metrics (average, distribution, promoters/detractors)
            4. Category analysis (top categories, distribution)
            5. Time-based trends (daily/weekly patterns)
            6. Message count analysis (average, patterns)
            
            EXAMPLE BREAKDOWN:
            Instead of one complex query, generate:
            - Query 1: "Total conversations in date range"
            - Query 2: "Resolution rate breakdown"  
            - Query 3: "NPS distribution and averages"
            - Query 4: "Top categories by volume"
            - Query 5: "Average message count"
            
            Each query should be simple, focused, and executable independently.
        """.trimIndent()

        val currentDate = java.time.LocalDate.now()
        val currentDateTime = java.time.Instant.now()
        val last30Days = currentDate.minusDays(30)
        val last7Days = currentDate.minusDays(7)
        val thisMonth = currentDate.withDayOfMonth(1)
        val lastMonth = thisMonth.minusMonths(1)

        val userPrompt = """
            CURRENT DATE CONTEXT:
            - Today's date: $currentDate (YYYY-MM-DD format)
            - Current datetime: $currentDateTime (ISO format)
            - Last 30 days start: ${last30Days}T00:00:00Z
            - Last 7 days start: ${last7Days}T00:00:00Z
            - This month start: ${thisMonth}T00:00:00Z
            - Last month start: ${lastMonth}T00:00:00Z
            - Last month end: ${thisMonth.minusDays(1)}T23:59:59Z
            
            Use these anchored dates for any relative time queries in your analysis.
            
            Original complex request: "$query"
            
            Query type: ${intentAnalysis.queryType}
            Complexity: ${intentAnalysis.complexity}
            Requirements: ${intentAnalysis.analysisRequirements.joinToString(", ")}
            
            Please break this down into 3-6 focused ANALYSIS UNITS that can be executed in parallel.
            Each unit should be a complete analytical component that can include:
            1. A focused Cypher query for specific data
            2. Potential for Python analysis (if the data needs statistical processing)
            3. Potential for visualization (if the results are suitable for charts)
            
            Think of each unit as a self-contained analysis that answers one specific business question.
            
            Return your response as a JSON array of analysis unit objects, each with:
            - "name": Brief description of this analysis unit
            - "cypherQuery": The complete, independent Cypher query
            - "explanation": What business insight this unit provides
            - "requiresPythonAnalysis": Boolean - whether this data needs statistical processing
            - "visualizationType": String - suggested chart type ("bar", "line", "pie", "scatter", "heatmap", "none")
            - "visualizationReason": String - why this visualization type is appropriate
            
            Example format:
            [
              {
                "name": "Monthly conversation volume trends",
                "cypherQuery": "MATCH (c:Conversation) WHERE c.createdAt >= '${last30Days}T00:00:00Z' RETURN substring(c.createdAt, 0, 10) as date, count(c) as conversations ORDER BY date",
                "explanation": "Shows daily conversation patterns over the last 30 days to identify trends and peaks",
                "requiresPythonAnalysis": true,
                "visualizationType": "line",
                "visualizationReason": "Line chart best shows trends and patterns over time"
              },
              {
                "name": "NPS distribution analysis", 
                "cypherQuery": "MATCH (c:Conversation) WHERE c.nps IS NOT NULL RETURN c.nps, count(c) as count ORDER BY c.nps",
                "explanation": "Analyzes customer satisfaction distribution to identify satisfaction patterns",
                "requiresPythonAnalysis": false,
                "visualizationType": "bar",
                "visualizationReason": "Bar chart clearly shows distribution across NPS scores"
              }
            ]
        """.trimIndent()

        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        )

        val request = CreateCompletionRequest(
            model = "gpt-4.1",
            messages = messages,
            temperature = 0.1,
            response_format = mapOf(
                "type" to "json_schema",
                "json_schema" to mapOf(
                    "name" to "parallel_queries",
                    "schema" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "queries" to mapOf(
                                "type" to "array",
                                "items" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "name" to mapOf("type" to "string"),
                                        "cypherQuery" to mapOf("type" to "string"),
                                        "explanation" to mapOf("type" to "string")
                                    ),
                                    "required" to listOf("name", "cypherQuery", "explanation")
                                )
                            )
                        ),
                        "required" to listOf("queries")
                    )
                )
            )
        )

        return try {
            data class AnalysisUnit(
                val name: String, 
                val cypherQuery: String, 
                val explanation: String,
                val requiresPythonAnalysis: Boolean = false,
                val visualizationType: String = "none",
                val visualizationReason: String = ""
            )
            data class ParallelAnalysisResponse(val queries: List<AnalysisUnit>)
            
            val response: ParallelAnalysisResponse = modelService.createCompletion(request, apiKey)
            
            if (response.queries.isNotEmpty()) {
                // Convert analysis units to enhanced query responses
                val analysisUnits = response.queries
                
                // Use first unit as primary, rest as supporting
                val primaryUnit = analysisUnits.first()
                val supportingUnits = analysisUnits.drop(1)
                
                QuerySet(
                    primaryQuery = CypherGenerationResponse(
                        cypherQuery = primaryUnit.cypherQuery,
                        explanation = primaryUnit.explanation,
                        confidence = "high"
                    ),
                    supportingQueries = supportingUnits.map { unit ->
                        CypherGenerationResponse(
                            cypherQuery = unit.cypherQuery,
                            explanation = "${unit.name}: ${unit.explanation}",
                            confidence = "high"
                        )
                    },
                    analysisUnits = analysisUnits  // Store the full analysis units for later use
                )
            } else {
                // Fallback to single query approach
                val primaryQuery = generatePrimaryQuery(query, conversation, intentAnalysis, apiKey)
                QuerySet(primaryQuery = primaryQuery, supportingQueries = emptyList())
            }
        } catch (e: Exception) {
            logger.error(e) { "Error generating parallel analysis units: ${e.message}" }
            // Fallback to single query approach
            val primaryQuery = generatePrimaryQuery(query, conversation, intentAnalysis, apiKey)
            QuerySet(primaryQuery = primaryQuery, supportingQueries = emptyList())
        }
    }

    /**
     * STAGE 4: Execute and analyze query set with intelligent insights.
     * Uses parallel execution with individual retry logic for each query.
     */
    private suspend fun executeAndAnalyzeQuerySet(querySet: QuerySet, apiKey: String): AnalysisResults {
        try {
            // Execute primary query with retry logic
            val primaryResult = executeQueryWithRetry(
                query = querySet.primaryQuery.cypherQuery,
                originalQuery = "Primary query for intelligent analysis",
                conversation = AgentConversation("temp"),
                maxRetries = 2,
                apiKey = apiKey
            )

            // Execute analysis units in parallel with individual retry and visualization
            val supportingResults = mutableMapOf<String, List<Map<String, Any>>>()
            val analysisVisualizations = mutableMapOf<String, VisualizationData>()
            val successfulQueries = mutableListOf<String>()
            val failedQueries = mutableListOf<String>()

            // Check if we have enhanced analysis units
            val hasAnalysisUnits = querySet.analysisUnits.isNotEmpty()
            
            for ((index, supportingQuery) in querySet.supportingQueries.withIndex()) {
                val queryName = "analysis_unit_$index"
                val result = executeQueryWithRetry(
                    query = supportingQuery.cypherQuery,
                    originalQuery = supportingQuery.explanation,
                    conversation = AgentConversation("temp"),
                    maxRetries = 2,
                    apiKey = apiKey
                )
                
                if (result.success) {
                    val resultData = result.results ?: emptyList()
                    supportingResults[queryName] = resultData
                    successfulQueries.add(supportingQuery.explanation)
                    logger.info { "Successfully executed analysis unit: ${supportingQuery.explanation}" }
                    
                    // Generate visualization for this analysis unit if it has metadata
                    if (hasAnalysisUnits && index < querySet.analysisUnits.size && resultData.isNotEmpty()) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val analysisUnit = querySet.analysisUnits[index] as? Map<String, Any>
                            val visualizationType = analysisUnit?.get("visualizationType") as? String
                            val requiresPython = analysisUnit?.get("requiresPythonAnalysis") as? Boolean ?: false
                            
                            if (visualizationType != null && visualizationType != "none") {
                                val visualization = generateAnalysisUnitVisualization(
                                    unitName = analysisUnit?.get("name") as? String ?: "Analysis Unit $index",
                                    query = supportingQuery.cypherQuery,
                                    results = resultData,
                                    suggestedType = visualizationType,
                                    requiresPythonAnalysis = requiresPython,
                                    apiKey = apiKey
                                )
                                
                                if (visualization != null) {
                                    analysisVisualizations[queryName] = visualization
                                    logger.info { "Generated visualization for analysis unit: ${analysisUnit?.get("name")}" }
                                }
                            }
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to generate visualization for analysis unit $index: ${e.message}" }
                        }
                    }
                } else {
                    failedQueries.add("${supportingQuery.explanation}: ${result.error}")
                    logger.warn { "Failed to execute analysis unit: ${supportingQuery.explanation} - ${result.error}" }
                }
            }

            // Continue with analysis even if some supporting queries failed
            val analysisSuccess = primaryResult.success || successfulQueries.isNotEmpty()
            
            if (analysisSuccess) {
                // Generate intelligent insights from available data
                val insights = generateIntelligentInsights(
                    primaryResults = primaryResult.results ?: emptyList(),
                    supportingResults = supportingResults,
                    querySet = querySet,
                    apiKey = apiKey
                )

                // Add execution summary to insights
                val executionInsights = mutableListOf<String>()
                if (primaryResult.success) {
                    executionInsights.add("Primary analysis completed successfully with ${primaryResult.results?.size ?: 0} results")
                }
                executionInsights.add("Successfully executed ${successfulQueries.size} out of ${querySet.supportingQueries.size} supporting queries")
                if (failedQueries.isNotEmpty()) {
                    executionInsights.add("Note: ${failedQueries.size} queries had issues but analysis continued with available data")
                }

                return AnalysisResults(
                    success = true,
                    primaryResults = primaryResult.results ?: emptyList(),
                    supportingResults = supportingResults,
                    analysisVisualizations = analysisVisualizations,
                    insights = executionInsights + insights,
                    error = if (failedQueries.isNotEmpty()) "Some queries failed: ${failedQueries.joinToString("; ")}" else null
                )
            } else {
                return AnalysisResults(
                    success = false,
                    primaryResults = emptyList(),
                    supportingResults = supportingResults,
                    analysisVisualizations = analysisVisualizations,
                    insights = emptyList(),
                    error = "Primary query failed: ${primaryResult.error}. Supporting queries: ${failedQueries.size} failed, ${successfulQueries.size} succeeded."
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Error executing query set: ${e.message}" }
            return AnalysisResults(
                success = false,
                primaryResults = emptyList(),
                supportingResults = emptyMap(),
                analysisVisualizations = emptyMap(),
                insights = emptyList(),
                error = "Query execution failed: ${e.message}"
            )
        }
    }

    /**
     * STAGE 5: Generate intelligent natural language response.
     */
    private suspend fun generateIntelligentNaturalResponse(
        query: String,
        querySet: QuerySet,
        analysisResults: AnalysisResults,
        strategy: ResponseStrategy,
        intentAnalysis: QueryIntentAnalysis,
        conversation: AgentConversation,
        apiKey: String
    ): String {
        val systemPrompt = """
            You are an expert business analyst specializing in data-driven insights and strategic recommendations.
            
            You have completed a sophisticated multi-stage analysis pipeline that included:
            1. Intent analysis and complexity assessment
            2. Strategic response planning
            3. Multi-query data gathering
            4. Intelligent insights generation
            
            Your task is to synthesize all this analysis into a comprehensive, executive-level response that:
            1. Directly answers the user's question with confidence
            2. Provides strategic context and business implications
            3. Highlights the most important insights from the multi-stage analysis
            4. Offers actionable recommendations based on the data
            5. Suggests logical next steps or follow-up analyses
            
            FORMAT YOUR RESPONSE USING PROFESSIONAL MARKDOWN:
            - Use **bold** for key metrics and critical insights
            - Use ## headers for main sections (Key Findings, Strategic Implications, etc.)
            - Use bullet points for clear, scannable insights
            - Use tables for structured data when relevant
            - Include line breaks for excellent readability
            - Use `code formatting` for specific technical terms or values
            
            RESPONSE STRUCTURE:
            1. Executive Summary (2-3 sentences answering the main question)
            2. Key Findings (data-driven insights)
            3. Strategic Analysis (business implications and context)
            4. Recommendations (actionable next steps)
            5. Additional Insights (if supporting queries provided extra value)
        """.trimIndent()

        val userPrompt = """
            ORIGINAL QUESTION: $query
            
            ANALYSIS PIPELINE RESULTS:
            
            Intent Analysis:
            - Query Type: ${intentAnalysis.queryType}
            - Complexity: ${intentAnalysis.complexity}
            - Analysis Requirements: ${intentAnalysis.analysisRequirements.joinToString(", ")}
            - Reasoning: ${intentAnalysis.reasoning}
            
            Response Strategy:
            - Response Type: ${strategy.primaryResponseType}
            - Multi-Query Strategy: ${strategy.multiQueryStrategy}
            - Strategy Reasoning: ${strategy.reasoning}
            
            Primary Query Results:
            Query: ${querySet.primaryQuery.cypherQuery}
            Explanation: ${querySet.primaryQuery.explanation}
            Results: ${objectMapper.writeValueAsString(analysisResults.primaryResults.take(10))}
            Total Results: ${analysisResults.primaryResults.size}
            
            Supporting Analysis:
            ${analysisResults.supportingResults.map { (key, value) -> 
                "$key: ${value.size} results"
            }.joinToString("\n")}
            
            Intelligent Insights:
            ${analysisResults.insights.joinToString("\n- ", "- ")}
            
            Conversation Context:
            ${buildConversationHistory(conversation).takeLast(3).joinToString("\n") { "${it["role"]}: ${it["content"]}" }}
            
            Generate a comprehensive, executive-level response that synthesizes all this analysis.
        """.trimIndent()

        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        )

        val request = CreateCompletionRequest(
            model = "gpt-4.1",
            messages = messages,
            temperature = 0.2
        )

        return try {
            val response = modelService.fetchCompletionPayload(request, apiKey)
            response
        } catch (e: Exception) {
            logger.error(e) { "Error generating intelligent natural response: ${e.message}" }
            // Fallback to a structured response based on available data
            generateFallbackResponse(query, analysisResults, intentAnalysis, strategy)
        }
    }

    // Helper methods for query generation
    private suspend fun generatePrimaryQuery(
        query: String,
        conversation: AgentConversation,
        intentAnalysis: QueryIntentAnalysis,
        apiKey: String
    ): CypherGenerationResponse {
        // Reuse the existing Cypher generation logic but enhanced with intent analysis
        val graphContext = buildGraphContext()
        return generateCypherQueryWithContext(query, conversation, graphContext, null, intentAnalysis, apiKey)
    }

    private suspend fun generateSupportingQueries(
        query: String,
        intentAnalysis: QueryIntentAnalysis,
        apiKey: String
    ): List<CypherGenerationResponse> {
        // Generate queries that provide supporting context
        val supportingPrompts = when (intentAnalysis.queryType) {
            "TIME_ANALYSIS" -> listOf(
                "What is the overall volume trend?",
                "What are the peak activity periods?"
            )
            "COMPARISON" -> listOf(
                "What are the totals for each category?",
                "What is the relative distribution?"
            )
            "DISTRIBUTION" -> listOf(
                "What are the summary statistics?",
                "What are the outliers or edge cases?"
            )
            else -> listOf("What is the overall context?")
        }

        return supportingPrompts.mapNotNull { prompt ->
            try {
                generateCypherQueryWithContext(prompt, AgentConversation("temp"), buildGraphContext(), null, intentAnalysis, apiKey)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to generate supporting query for: $prompt" }
                null
            }
        }
    }

    private suspend fun generateComparativeQueries(
        query: String,
        intentAnalysis: QueryIntentAnalysis,
        apiKey: String
    ): List<CypherGenerationResponse> {
        // Generate queries for comparative analysis
        val comparativePrompts = listOf(
            "Compare current period to previous period",
            "Show breakdown by key categories",
            "Identify top performers vs. bottom performers"
        )

        return comparativePrompts.mapNotNull { prompt ->
            try {
                generateCypherQueryWithContext(prompt, AgentConversation("temp"), buildGraphContext(), null, intentAnalysis, apiKey)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to generate comparative query for: $prompt" }
                null
            }
        }
    }

    private suspend fun generateIntelligentInsights(
        primaryResults: List<Map<String, Any>>,
        supportingResults: Map<String, List<Map<String, Any>>>,
        querySet: QuerySet,
        apiKey: String
    ): List<String> {
        val systemPrompt = """
            You are a data insights analyst. Analyze the provided query results and generate 3-5 key insights.
            
            Focus on:
            1. Patterns and trends in the data
            2. Notable outliers or anomalies
            3. Business implications
            4. Comparative insights across different data sets
            5. Statistical significance
            
            Each insight should be 1-2 sentences and actionable.
        """.trimIndent()

        val userPrompt = """
            Primary Results: ${objectMapper.writeValueAsString(primaryResults.take(5))}
            Total Primary Results: ${primaryResults.size}
            
            Supporting Results: ${supportingResults.map { (key, value) -> 
                "$key: ${value.size} results, sample: ${objectMapper.writeValueAsString(value.take(2))}"
            }.joinToString("\n")}
            
            Generate key insights from this data.
        """.trimIndent()

        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        )

        val request = CreateCompletionRequest(
            model = "gpt-4.1",
            messages = messages,
            temperature = 0.3,
            response_format = mapOf(
                "type" to "json_schema",
                "json_schema" to mapOf(
                    "name" to "insights_response",
                    "schema" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "insights" to mapOf(
                                "type" to "array",
                                "items" to mapOf("type" to "string")
                            )
                        ),
                        "required" to listOf("insights")
                    )
                )
            )
        )

        return try {
            data class InsightsResponse(val insights: List<String>)
            val response: InsightsResponse = modelService.createCompletion(request, apiKey)
            response.insights
        } catch (e: Exception) {
            logger.error(e) { "Error generating insights: ${e.message}" }
            listOf("Analysis completed with ${primaryResults.size} primary results and ${supportingResults.size} supporting analyses.")
        }
    }

    // Enhanced Cypher generation with intent analysis context
    private suspend fun generateCypherQueryWithContext(
        userQuery: String,
        conversation: AgentConversation,
        graphContext: GraphContext?,
        previousError: String?,
        intentAnalysis: QueryIntentAnalysis,
        apiKey: String
    ): CypherGenerationResponse {
        val enhancedPrompt = buildEnhancedCypherGenerationPrompt(graphContext, previousError, intentAnalysis)
        val conversationHistory = buildConversationHistory(conversation)
        
        val messages = mutableListOf<Map<String, Any>>().apply {
            add(mapOf("role" to "system", "content" to enhancedPrompt))
            if (conversationHistory.isNotEmpty()) {
                addAll(conversationHistory)
            }
            add(mapOf("role" to "user", "content" to userQuery))
        }
        
        val request = CreateCompletionRequest(
            model = "gpt-4.1",
            messages = messages,
            temperature = 0.1,
            response_format = mapOf(
                "type" to "json_schema",
                "json_schema" to mapOf(
                    "name" to "cypher_response",
                    "schema" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "cypherQuery" to mapOf("type" to "string"),
                            "explanation" to mapOf("type" to "string"),
                            "confidence" to mapOf("type" to "string")
                        ),
                        "required" to listOf("cypherQuery", "explanation")
                    )
                )
            )
        )
        
        val response: CypherGenerationResponse = modelService.createCompletion(request, apiKey)
        logger.debug { "Generated enhanced Cypher query: ${response.cypherQuery}" }
        
        return response
    }

    private fun buildEnhancedCypherGenerationPrompt(
        graphContext: GraphContext?,
        previousError: String?,
        intentAnalysis: QueryIntentAnalysis
    ): String {
        val prompt = StringBuilder()
        
        // Base prompt (reuse existing logic)
        prompt.appendLine(buildBaseCypherPrompt())
        
        // Add intent-specific optimizations
        prompt.appendLine("\nINTENT-SPECIFIC OPTIMIZATIONS:")
        prompt.appendLine("Query Type: ${intentAnalysis.queryType}")
        prompt.appendLine("Complexity Level: ${intentAnalysis.complexity}")
        prompt.appendLine("Expected Data Size: ${intentAnalysis.expectedDataSize}")
        
        when (intentAnalysis.queryType) {
            "TIME_ANALYSIS" -> {
                prompt.appendLine("- Focus on temporal patterns and trends")
                prompt.appendLine("- Include date grouping and time-based aggregations")
                prompt.appendLine("- Consider ordering by time for trend analysis")
            }
            "COMPARISON" -> {
                prompt.appendLine("- Generate queries that enable comparison between categories")
                prompt.appendLine("- Include grouping and aggregation for comparative analysis")
                prompt.appendLine("- Consider percentage calculations and relative metrics")
            }
            "DISTRIBUTION" -> {
                prompt.appendLine("- Focus on distribution patterns and statistics")
                prompt.appendLine("- Include aggregations that show data spread")
                prompt.appendLine("- Consider percentiles and frequency distributions")
            }
            "CORRELATION" -> {
                prompt.appendLine("- Generate queries that can reveal relationships between variables")
                prompt.appendLine("- Include multiple dimensions for correlation analysis")
                prompt.appendLine("- Consider co-occurrence patterns")
            }
            "COMPLEX_ANALYTICS" -> {
                prompt.appendLine("- Generate sophisticated queries with multiple aggregation levels")
                prompt.appendLine("- Include subqueries or complex calculations where needed")
                prompt.appendLine("- Focus on advanced analytical patterns")
            }
        }
        
        if (graphContext != null) {
            prompt.appendLine("\nGRAPH CONTEXT:")
            prompt.appendLine("- Total conversations: ${graphContext.totalConversations}")
            prompt.appendLine("- Total path nodes: ${graphContext.totalPathNodes}")
            prompt.appendLine("- Available root paths: ${graphContext.rootPaths.joinToString(", ")}")
        }
        
        if (previousError != null) {
            prompt.appendLine("\nPREVIOUS ERROR (fix this):")
            prompt.appendLine(previousError)
        }
        
        return prompt.toString()
    }

    private fun buildBaseCypherPrompt(): String {
        // Return the base Cypher generation prompt (reuse existing logic)
        val currentDate = java.time.LocalDate.now().toString()
        val currentDateTime = java.time.Instant.now().toString()
        
        return """
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
            
            INSTRUCTIONS:
            1. Generate a valid Cypher query that answers the user's question
            2. Focus on business KPIs like counts, averages, trends, distributions
            3. Use proper Cypher syntax and avoid common errors
            4. Return results in a meaningful format with clear column names
            5. Limit results to reasonable numbers (use LIMIT when appropriate)
            6. Provide an explanation of what the query does
        """.trimIndent()
    }

    /**
     * Generate visualization for a specific analysis unit.
     */
    private suspend fun generateAnalysisUnitVisualization(
        unitName: String,
        query: String,
        results: List<Map<String, Any>>,
        suggestedType: String,
        requiresPythonAnalysis: Boolean,
        apiKey: String
    ): VisualizationData? {
        return try {
            intelligentVisualizationService.generateIntelligentVisualization(
                originalQuery = "Analysis unit: $unitName",
                cypherQuery = query,
                queryResults = results,
                naturalLanguageResponse = "Generated visualization for analysis unit: $unitName",
                apiKey = apiKey,
                enablePythonAnalysis = requiresPythonAnalysis
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to generate visualization for analysis unit: $unitName - ${e.message}" }
            null
        }
    }

    /**
     * Execute a Cypher query with comprehensive retry logic for various error types.
     */
    private suspend fun executeQueryWithRetry(
        query: String,
        originalQuery: String,
        conversation: AgentConversation,
        maxRetries: Int,
        apiKey: String
    ): CypherExecutionResult {
        var retryCount = 0
        var lastError: String? = null
        var currentQuery = query
        
        while (retryCount <= maxRetries) {
            try {
                val result = executeCypherQuery(currentQuery)
                if (result.success) {
                    return result
                } else {
                    val errorMsg = result.error ?: ""
                    lastError = errorMsg
                    retryCount++
                    
                    logger.warn { "Query failed (attempt $retryCount/$maxRetries): $errorMsg" }
                    logger.warn { "Failed query: $currentQuery" }
                    
                    if (retryCount <= maxRetries) {
                        // Determine error type and fix strategy
                        val fixedQuery = when {
                            // Syntax errors (multiple RETURN, malformed queries)
                            errorMsg.contains("can only be one RETURN", ignoreCase = true) ||
                            errorMsg.contains("syntax error", ignoreCase = true) ||
                            errorMsg.contains("invalid syntax", ignoreCase = true) ||
                            errorMsg.contains("unexpected token", ignoreCase = true) -> {
                                logger.info { "Detected syntax error, regenerating query with syntax focus..." }
                                regenerateQueryWithErrorFix(originalQuery, conversation, errorMsg, "SYNTAX_ERROR", apiKey)
                            }
                            
                            // Function/APOC errors
                            errorMsg.contains("apoc", ignoreCase = true) || 
                            errorMsg.contains("doesn't exist", ignoreCase = true) || 
                            errorMsg.contains("function", ignoreCase = true) ||
                            errorMsg.contains("datetime", ignoreCase = true) -> {
                                logger.info { "Detected function error, regenerating without APOC/datetime functions..." }
                                regenerateQueryWithErrorFix(originalQuery, conversation, errorMsg, "FUNCTION_ERROR", apiKey)
                            }
                            
                            // Schema/property errors
                            errorMsg.contains("property does not exist", ignoreCase = true) ||
                            errorMsg.contains("variable not defined", ignoreCase = true) ||
                            errorMsg.contains("unknown property", ignoreCase = true) -> {
                                logger.info { "Detected schema error, regenerating with schema awareness..." }
                                regenerateQueryWithErrorFix(originalQuery, conversation, errorMsg, "SCHEMA_ERROR", apiKey)
                            }
                            
                            // Type errors
                            errorMsg.contains("type mismatch", ignoreCase = true) ||
                            errorMsg.contains("cannot compare", ignoreCase = true) ||
                            errorMsg.contains("expected number", ignoreCase = true) -> {
                                logger.info { "Detected type error, regenerating with type safety..." }
                                regenerateQueryWithErrorFix(originalQuery, conversation, errorMsg, "TYPE_ERROR", apiKey)
                            }
                            
                            // Generic retry with error feedback
                            else -> {
                                logger.info { "Generic error, regenerating with error feedback..." }
                                regenerateQueryWithErrorFix(originalQuery, conversation, errorMsg, "GENERIC_ERROR", apiKey)
                            }
                        }
                        
                        currentQuery = fixedQuery.cypherQuery
                        logger.info { "Retry query generated: $currentQuery" }
                    }
                }
            } catch (e: Exception) {
                lastError = "Execution error: ${e.message}"
                retryCount++
                logger.error(e) { "Error executing query (attempt $retryCount/$maxRetries)" }
                
                if (retryCount <= maxRetries) {
                    try {
                        // Try to fix execution errors too
                        val fixedQuery = regenerateQueryWithErrorFix(
                            originalQuery, 
                            conversation, 
                            e.message ?: "Unknown execution error", 
                            "EXECUTION_ERROR", 
                            apiKey
                        )
                        currentQuery = fixedQuery.cypherQuery
                    } catch (fixError: Exception) {
                        logger.error(fixError) { "Failed to regenerate query for execution error" }
                        // Continue with original query for next iteration
                    }
                }
            }
        }
        
        return CypherExecutionResult(
            success = false,
            error = "Failed after $maxRetries retries. Last error: $lastError",
            queryExecuted = currentQuery
        )
    }

    /**
     * Regenerate a query with specific error fixes based on error type.
     */
    private suspend fun regenerateQueryWithErrorFix(
        originalQuery: String,
        conversation: AgentConversation,
        previousError: String,
        errorType: String,
        apiKey: String
    ): CypherGenerationResponse {
        val enhancedError = when (errorType) {
            "SYNTAX_ERROR" -> """
                CYPHER SYNTAX ERROR: $previousError
                
                CRITICAL SYNTAX FIXES REQUIRED:
                - There can only be ONE RETURN statement per query clause
                - If you need multiple aggregations, use WITH clauses to pipe results
                - Correct format: MATCH ... WITH ... RETURN ... (not MATCH ... RETURN ... RETURN ...)
                - Fix syntax: Remove duplicate RETURN statements
                - Use proper ORDER BY placement (after final RETURN)
                - Ensure proper parentheses and bracket matching
                - Check variable scoping between WITH clauses
                
                EXAMPLE FIX for "double RETURN" error:
                WRONG: MATCH (c) RETURN x, y RETURN x, y ORDER BY x
                RIGHT: MATCH (c) RETURN x, y ORDER BY x
                
                REWRITE THE QUERY WITH VALID CYPHER SYNTAX - ONLY ONE RETURN STATEMENT!
            """.trimIndent()
            
            "FUNCTION_ERROR" -> """
                FUNCTION ERROR: $previousError
                
                FUNCTION FIX REQUIRED:
                - Replace ALL apoc.date.* functions with string-based date operations
                - Use substring() and string concatenation for date calculations
                - Replace datetime() with string comparisons like: c.createdAt >= '2024-01-01T00:00:00Z'
                - Use hardcoded date strings for relative date calculations
                - Do NOT use CALL subqueries with APOC functions
                - For "last 30 days" use: c.createdAt >= '${java.time.LocalDate.now().minusDays(30)}T00:00:00Z'
                - For date formatting use substring(c.createdAt, 0, 10) to get YYYY-MM-DD
                
                REWRITE THE QUERY WITHOUT ANY APOC OR DATETIME FUNCTIONS.
            """.trimIndent()
            
            "SCHEMA_ERROR" -> """
                SCHEMA ERROR: $previousError
                
                SCHEMA FIX REQUIRED:
                - Check property names match the schema exactly
                - Available properties on Conversation: id, createdAt, summary, resolved, nps, version, classification, messageCount
                - Available properties on PathNode: name (unique identifier)
                - Use correct relationship types: [:HAS_CHILD] and [:CONTAINS]
                - Ensure all variables are properly defined before use
                - Check node labels are correct: Conversation, PathNode
                
                REWRITE THE QUERY WITH CORRECT SCHEMA PROPERTIES.
            """.trimIndent()
            
            "TYPE_ERROR" -> """
                TYPE ERROR: $previousError
                
                TYPE FIX REQUIRED:
                - Ensure numeric comparisons use proper type conversion
                - Use toInteger() or toFloat() for string-to-number conversion
                - Check date string formats are consistent (ISO format: YYYY-MM-DDTHH:MM:SSZ)
                - Ensure boolean comparisons use true/false values
                - Use proper null checks with IS NULL or IS NOT NULL
                
                REWRITE THE QUERY WITH PROPER TYPE HANDLING.
            """.trimIndent()
            
            "EXECUTION_ERROR" -> """
                EXECUTION ERROR: $previousError
                
                EXECUTION FIX REQUIRED:
                - Simplify complex query logic
                - Add proper null checks and error handling
                - Ensure all paths in the query are valid
                - Check for potential division by zero
                - Verify all aggregation functions are used correctly
                
                REWRITE THE QUERY WITH SAFER EXECUTION LOGIC.
            """.trimIndent()
            
            else -> """
                QUERY ERROR: $previousError
                
                GENERAL FIX REQUIRED:
                - Review the error message carefully and fix the specific issue
                - Ensure query follows Neo4j Cypher syntax rules
                - Test logic step by step
                - Use simpler approach if current query is too complex
                
                REWRITE THE QUERY TO FIX THE REPORTED ERROR.
            """.trimIndent()
        }
        
        return generateCypherQueryWithContext(
            originalQuery, 
            conversation, 
            buildGraphContext(), 
            enhancedError, 
            QueryIntentAnalysis("RETRY", "low", "small", emptyList(), false, "Error retry: $errorType"),
            apiKey
        )
    }

    private fun generateFallbackResponse(
        query: String,
        analysisResults: AnalysisResults,
        intentAnalysis: QueryIntentAnalysis,
        strategy: ResponseStrategy
    ): String {
        return """
            ## Analysis Results for: "$query"
            
            **Query Type:** ${intentAnalysis.queryType}  
            **Complexity:** ${intentAnalysis.complexity}  
            **Strategy:** ${strategy.primaryResponseType}
            
            ### Key Findings
            - Found **${analysisResults.primaryResults.size}** primary results
            - Executed **${analysisResults.supportingResults.size}** supporting analyses
            - Generated **${analysisResults.insights.size}** intelligent insights
            
            ### Insights
            ${analysisResults.insights.joinToString("\n") { "- $it" }}
            
            ### Next Steps
            Based on this analysis, you may want to explore related patterns or drill down into specific categories for deeper insights.
        """.trimIndent()
    }

    // Helper method to build graph context (reuse existing logic)
    private suspend fun buildGraphContext(): GraphContext {
        return try {
            val statistics = graphService.getMigrationStatistics()
            GraphContext(
                totalConversations = statistics.conversationsInGraph,
                totalPathNodes = statistics.pathNodesInGraph,
                totalRelationships = statistics.totalRelationships,
                rootPaths = emptyList(), // Simplified for now
                samplePaths = emptyList(), // Simplified for now
                pathTreeSummary = "Graph context available"
            )
        } catch (e: Exception) {
            logger.error(e) { "Error building graph context: ${e.message}" }
            GraphContext(0, 0, 0, emptyList(), emptyList(), "Error retrieving graph context")
        }
    }

    // Reuse existing Cypher execution logic
    private suspend fun executeCypherQuery(cypherQuery: String): CypherExecutionResult =
        try {
            memgraphDriver.session(org.neo4j.driver.SessionConfig.forDatabase("memgraph")).use { session ->
                logger.debug { "Executing Cypher query: $cypherQuery" }
                
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
                
                logger.debug { "Query executed successfully, returned ${records.size} records" }
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
}

// Data classes for the new pipeline stages
data class QueryIntentAnalysis(
    val queryType: String,
    val complexity: String,
    val expectedDataSize: String,
    val analysisRequirements: List<String>,
    val visualizationPotential: Boolean,
    val reasoning: String
)

data class ResponseStrategy(
    val primaryResponseType: String,
    val requiresVisualization: Boolean,
    val visualizationType: String,
    val requiresPythonAnalysis: Boolean,
    val multiQueryStrategy: String,
    val reasoning: String
)

data class QuerySet(
    val primaryQuery: CypherGenerationResponse,
    val supportingQueries: List<CypherGenerationResponse>,
    val analysisUnits: List<Any> = emptyList()  // Stores full analysis unit metadata
)

data class AnalysisResults(
    val success: Boolean,
    val primaryResults: List<Map<String, Any>>,
    val supportingResults: Map<String, List<Map<String, Any>>>,
    val analysisVisualizations: Map<String, VisualizationData> = emptyMap(),
    val insights: List<String>,
    val error: String?
)
