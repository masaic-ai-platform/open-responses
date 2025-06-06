package ai.masaic.improved.service

import ai.masaic.improved.ModelService
import ai.masaic.improved.createCompletion
import ai.masaic.improved.model.*
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Unified service for analyzing queries to determine intent, complexity, and response strategy.
 * 
 * This service consolidates all query analysis logic that was previously scattered across
 * multiple services to provide a single source of truth for query understanding.
 */
@Service
class QueryAnalysisService(
    private val modelService: ModelService,
) {
    private val logger = KotlinLogging.logger {}
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    /**
     * Comprehensive query analysis that determines all aspects needed for response strategy.
     */
    suspend fun analyzeQuery(
        query: String,
        conversation: AgentConversation,
        queryResults: List<Map<String, Any>>? = null,
        apiKey: String,
    ): QueryAnalysis {
        logger.info { "Performing comprehensive query analysis for: $query" }
        
        val currentDate = LocalDate.now()
        val currentDateTime = LocalDateTime.now()
        val dateContext = buildEnhancedDateContext(currentDate, currentDateTime)

        val systemPrompt = """
            You are an expert query analyst specializing in understanding user intent and determining optimal response strategies.
            
            Your task is to analyze a user query (and optionally the resulting data) to determine:
            1. **Query Intent**: What the user is trying to understand
            2. **Complexity Level**: How sophisticated the analysis needs to be
            3. **Data Characteristics**: What type of data this query produces
            4. **Response Strategy**: What type of response would best serve the user
            5. **Visualization Potential**: Whether and what type of visualization would add value
            6. **Multi-Visual Opportunity**: Whether multiple visualizations would provide better insights
            
            ENHANCED DATE CONTEXT:
            $dateContext
            
            QUERY TYPES:
            - SIMPLE_COUNT: Basic counting queries
            - TIME_ANALYSIS: Time-based trends and patterns  
            - COMPARISON: Comparing categories or groups
            - DISTRIBUTION: Understanding data distribution
            - CORRELATION: Finding relationships between variables
            - COMPLEX_ANALYTICS: Multi-step analysis requiring advanced calculations
            - EXPLORATORY: Open-ended exploration of data patterns
            
            COMPLEXITY LEVELS:
            - LOW: Single query, basic aggregation, simple answer
            - MEDIUM: Multiple data points, some calculations, moderate insights
            - HIGH: Advanced analytics, statistical analysis, complex transformations
            
            RESPONSE STRATEGIES:
            - TEXT_ONLY: Simple textual response sufficient
            - TEXT_WITH_SIMPLE_VISUAL: Text + single basic chart
            - TEXT_WITH_PYTHON_VISUAL: Text + single chart requiring Python analysis
            - TEXT_WITH_MULTIPLE_VISUALS: Text + multiple complementary visualizations
            - COMPREHENSIVE_ANALYSIS: Full analytical report with multiple Python analyses and visualizations
            
            MULTI-VISUAL CRITERIA:
            Multiple visualizations are beneficial when:
            - Data has multiple meaningful dimensions (time + categories + metrics)
            - Different analytical perspectives would provide complementary insights
            - Time-series data benefits from multiple time windows or aggregations
            - Comparative analysis across different segments or periods
            - Complex business questions requiring multiple analytical approaches
            - Data can be meaningfully segmented for parallel analysis
            
            Be precise in your analysis - don't default to complex solutions for simple queries.
        """.trimIndent()

        val userPrompt = buildUserPrompt(query, conversation, queryResults, dateContext)

        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        )

        val request = CreateCompletionRequest(
            model = System.getenv("OPENAI_MODEL"),
            messages = messages,
            temperature = 0.1,
            response_format = mapOf(
                "type" to "json_schema",
                "json_schema" to mapOf(
                    "name" to "query_analysis",
                    "schema" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "queryType" to mapOf(
                                "type" to "string",
                                "enum" to listOf("SIMPLE_COUNT", "TIME_ANALYSIS", "COMPARISON", "DISTRIBUTION", "CORRELATION", "COMPLEX_ANALYTICS", "EXPLORATORY")
                            ),
                            "complexity" to mapOf(
                                "type" to "string", 
                                "enum" to listOf("LOW", "MEDIUM", "HIGH")
                            ),
                            "responseStrategy" to mapOf(
                                "type" to "string",
                                "enum" to listOf("TEXT_ONLY", "TEXT_WITH_SIMPLE_VISUAL", "TEXT_WITH_PYTHON_VISUAL", "TEXT_WITH_MULTIPLE_VISUALS", "COMPREHENSIVE_ANALYSIS")
                            ),
                            "dataCharacteristics" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "expectedSize" to mapOf("type" to "string", "enum" to listOf("SMALL", "MEDIUM", "LARGE")),
                                    "dimensionality" to mapOf("type" to "string", "enum" to listOf("SINGLE", "MULTIPLE", "HIERARCHICAL")),
                                    "temporalAspect" to mapOf("type" to "boolean"),
                                    "categoricalAspect" to mapOf("type" to "boolean"),
                                    "numericalAspect" to mapOf("type" to "boolean")
                                ),
                                "required" to listOf("expectedSize", "dimensionality")
                            ),
                            "visualizationPotential" to mapOf(
                                "type" to "object", 
                                "properties" to mapOf(
                                    "beneficial" to mapOf("type" to "boolean"),
                                    "primaryChartType" to mapOf("type" to "string", "enum" to ChartType.values().map { it.name }),
                                    "requiresPython" to mapOf("type" to "boolean"),
                                    "multipleVisualsRecommended" to mapOf("type" to "boolean"),
                                    "suggestedVisualCount" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 5)
                                ),
                                "required" to listOf("beneficial")
                            ),
                            "analysisRequirements" to mapOf(
                                "type" to "array",
                                "items" to mapOf("type" to "string", "enum" to listOf("STATISTICAL", "MATHEMATICAL", "TEMPORAL", "COMPARATIVE", "FORECASTING", "SEGMENTATION", "CORRELATION", "NONE"))
                            ),
                            "reasoning" to mapOf("type" to "string"),
                            "confidenceLevel" to mapOf("type" to "string", "enum" to listOf("HIGH", "MEDIUM", "LOW"))
                        ),
                        "required" to listOf("queryType", "complexity", "responseStrategy", "dataCharacteristics", "visualizationPotential", "analysisRequirements", "reasoning", "confidenceLevel")
                    )
                )
            )
        )

        return try {
            val response = modelService.createCompletion<Map<String, Any>>(request, apiKey)
            parseQueryAnalysisResponse(response)
        } catch (e: Exception) {
            logger.error(e) { "Error analyzing query: ${e.message}" }
            QueryAnalysis(
                queryType = "SIMPLE_COUNT",
                complexity = "LOW",
                responseStrategy = "TEXT_ONLY",
                dataCharacteristics = DataCharacteristics("SMALL", "SINGLE"),
                visualizationPotential = VisualizationPotential(beneficial = false),
                analysisRequirements = listOf("NONE"),
                reasoning = "Error during analysis: ${e.message}",
                confidenceLevel = "LOW"
            )
        }
    }

    private fun buildUserPrompt(
        query: String,
        conversation: AgentConversation,
        queryResults: List<Map<String, Any>>?,
        dateContext: String
    ): String {
        val prompt = StringBuilder()
        
        prompt.appendLine("QUERY TO ANALYZE: \"$query\"")
        prompt.appendLine()
        
        // Add conversation context if available
        if (conversation.messages.isNotEmpty()) {
            prompt.appendLine("CONVERSATION CONTEXT:")
            conversation.messages.takeLast(3).forEach { message ->
                prompt.appendLine("${message.role}: ${message.content.take(200)}")
            }
            prompt.appendLine()
        }
        
        // Add data context if available
        if (queryResults != null) {
            prompt.appendLine("ACTUAL DATA RESULTS:")
            prompt.appendLine("- Total Records: ${queryResults.size}")
            if (queryResults.isNotEmpty()) {
                val firstRow = queryResults.first()
                prompt.appendLine("- Available Fields: ${firstRow.keys.joinToString(", ")}")
                prompt.appendLine("- Sample Data: ${objectMapper.writeValueAsString(queryResults.take(2))}")
            }
            prompt.appendLine()
        }
        
        prompt.appendLine("DATE CONTEXT:")
        prompt.appendLine(dateContext)
        prompt.appendLine()
        
        prompt.appendLine("Provide a comprehensive analysis to determine the optimal response strategy.")
        
        return prompt.toString()
    }

    private fun parseQueryAnalysisResponse(response: Map<String, Any>): QueryAnalysis {
        val dataChar = response["dataCharacteristics"] as Map<String, Any>
        val vizPot = response["visualizationPotential"] as Map<String, Any>
        
        return QueryAnalysis(
            queryType = response["queryType"] as String,
            complexity = response["complexity"] as String,
            responseStrategy = response["responseStrategy"] as String,
            dataCharacteristics = DataCharacteristics(
                expectedSize = dataChar["expectedSize"] as String,
                dimensionality = dataChar["dimensionality"] as String,
                temporalAspect = dataChar["temporalAspect"] as? Boolean ?: false,
                categoricalAspect = dataChar["categoricalAspect"] as? Boolean ?: false,
                numericalAspect = dataChar["numericalAspect"] as? Boolean ?: false
            ),
            visualizationPotential = VisualizationPotential(
                beneficial = vizPot["beneficial"] as Boolean,
                primaryChartType = (vizPot["primaryChartType"] as? String)?.let { ChartType.valueOf(it) },
                requiresPython = vizPot["requiresPython"] as? Boolean ?: false,
                multipleVisualsRecommended = vizPot["multipleVisualsRecommended"] as? Boolean ?: false,
                suggestedVisualCount = (vizPot["suggestedVisualCount"] as? Number)?.toInt() ?: 1
            ),
            analysisRequirements = (response["analysisRequirements"] as List<String>),
            reasoning = response["reasoning"] as String,
            confidenceLevel = response["confidenceLevel"] as String
        )
    }

    private fun buildEnhancedDateContext(currentDate: LocalDate, currentDateTime: LocalDateTime): String {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE
        val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        
        return """
            Current Date: ${currentDate.format(formatter)} (YYYY-MM-DD)
            Current DateTime: ${currentDateTime.format(timeFormatter)}
            
            Key Time Periods:
            - Last 7 days: ${currentDate.minusDays(7).format(formatter)} to ${currentDate.format(formatter)}
            - Last 30 days: ${currentDate.minusDays(30).format(formatter)} to ${currentDate.format(formatter)}
            - This month: ${currentDate.withDayOfMonth(1).format(formatter)} to ${currentDate.format(formatter)}
            - Last month: ${currentDate.minusMonths(1).withDayOfMonth(1).format(formatter)} to ${currentDate.minusMonths(1).withDayOfMonth(currentDate.minusMonths(1).lengthOfMonth()).format(formatter)}
            
            Use this context for time-aware analysis decisions.
        """.trimIndent()
    }
}

// Data classes for query analysis
data class QueryAnalysis(
    val queryType: String,
    val complexity: String,
    val responseStrategy: String,
    val dataCharacteristics: DataCharacteristics,
    val visualizationPotential: VisualizationPotential,
    val analysisRequirements: List<String>,
    val reasoning: String,
    val confidenceLevel: String
)

data class DataCharacteristics(
    val expectedSize: String,
    val dimensionality: String,
    val temporalAspect: Boolean = false,
    val categoricalAspect: Boolean = false,
    val numericalAspect: Boolean = false
)

data class VisualizationPotential(
    val beneficial: Boolean,
    val primaryChartType: ChartType? = null,
    val requiresPython: Boolean = false,
    val multipleVisualsRecommended: Boolean = false,
    val suggestedVisualCount: Int = 1
) 