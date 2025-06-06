package ai.masaic.improved.service

import ai.masaic.improved.ModelService
import ai.masaic.improved.createCompletion
import ai.masaic.improved.model.*
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Service for generating multiple parallel visualization units.
 * 
 * This service:
 * 1. Analyzes data to determine if multiple visualizations would be beneficial
 * 2. Breaks down complex analysis into parallel units (Python analysis + visualization)
 * 3. Executes multiple visualization units in parallel
 * 4. Provides enhanced date context for time-aware analysis
 * 5. Combines results into a cohesive visualization response
 */
@Service
class ParallelVisualizationService(
    private val modelService: ModelService,
    private val intelligentVisualizationService: IntelligentVisualizationService,
) {
    private val logger = KotlinLogging.logger {}
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    companion object {
        private const val MAX_PARALLEL_UNITS = 5
        private const val PARALLEL_TIMEOUT_SECONDS = 60L
    }

    /**
     * Analyze if parallel visualization units would be beneficial and generate them.
     */
    suspend fun generateParallelVisualizations(
        originalQuery: String,
        cypherQuery: String,
        queryResults: List<Map<String, Any>>,
        naturalLanguageResponse: String,
        apiKey: String,
        enablePythonAnalysis: Boolean = true,
        maxParallelUnits: Int = 3,
    ): ParallelVisualizationData? {
        logger.info { "Analyzing for parallel visualization opportunities with ${queryResults.size} results" }

        if (queryResults.isEmpty()) {
            logger.info { "No results to visualize" }
            return null
        }

        // Step 1: Get enhanced analysis recommendation with parallel unit suggestions
        val recommendation = getParallelVisualizationRecommendation(
            originalQuery, cypherQuery, queryResults, naturalLanguageResponse, apiKey
        )

        if (!recommendation.recommendsParallelUnits || recommendation.suggestedUnits.isEmpty()) {
            logger.info { "Parallel visualization not recommended. Reason: ${recommendation.reasoning}" }
            return null
        }

        logger.info { "Parallel visualization recommended with ${recommendation.suggestedUnits.size} units" }

        // Step 2: Execute parallel visualization units
        val parallelResults = executeParallelVisualizationUnits(
            ParallelVisualizationRequest(
                originalQuery = originalQuery,
                cypherQuery = cypherQuery,
                queryResults = queryResults,
                naturalLanguageResponse = naturalLanguageResponse,
                visualizationUnits = recommendation.suggestedUnits.take(maxParallelUnits),
                enablePythonAnalysis = enablePythonAnalysis,
                maxParallelUnits = maxParallelUnits
            ),
            apiKey
        )

        if (parallelResults.isEmpty()) {
            logger.warn { "No parallel visualization units succeeded" }
            return null
        }

        // Step 3: Generate execution summary
        val executionSummary = generateExecutionSummary(recommendation.suggestedUnits, parallelResults)

        // Step 4: Organize results (primary + additional)
        val primaryVisualization = parallelResults.minByOrNull { it.unitIndex ?: Int.MAX_VALUE } ?: parallelResults.first()
        val additionalVisualizations = parallelResults.filter { it != primaryVisualization }

        return ParallelVisualizationData(
            primaryVisualization = primaryVisualization,
            additionalVisualizations = additionalVisualizations,
            totalUnits = recommendation.suggestedUnits.size,
            executionSummary = executionSummary
        )
    }

    /**
     * Get enhanced recommendation for parallel visualization including current date context.
     */
    private suspend fun getParallelVisualizationRecommendation(
        originalQuery: String,
        cypherQuery: String,
        queryResults: List<Map<String, Any>>,
        naturalLanguageResponse: String,
        apiKey: String,
    ): IntelligentAnalysisRecommendation {
        val currentDate = LocalDate.now()
        val currentDateTime = LocalDateTime.now()
        val dateContext = buildEnhancedDateContext(currentDate, currentDateTime)

        val systemPrompt = """
            You are an expert data visualization strategist with deep understanding of parallel analysis patterns.
            
            Your task is to determine if the data and query would benefit from MULTIPLE PARALLEL VISUALIZATION UNITS.
            
            PARALLEL VISUALIZATION is beneficial when:
            1. Data has multiple dimensions that deserve separate focused analysis
            2. Different analytical approaches would provide complementary insights  
            3. Time-series data that benefits from multiple time windows or aggregations
            4. Complex business data requiring different visualization approaches
            5. Data that can be meaningfully segmented for comparative analysis
            
            ENHANCED DATE CONTEXT:
            $dateContext
            
            Use this date context to make time-aware recommendations for parallel analysis.
            
            PARALLEL UNIT TYPES:
            1. **Time-based Units**: Different time windows (daily, weekly, monthly trends)
            2. **Segmentation Units**: Different data cuts (by category, status, etc.)
            3. **Analytical Units**: Different analysis approaches (distribution, trends, correlations)
            4. **Comparative Units**: Before/after, this period vs last period
            5. **Drill-down Units**: High-level overview + detailed breakdowns
            
            For each suggested unit, specify:
            - name: Clear descriptive name
            - description: What insights this unit provides
            - analysisType: DIRECT_PLOTLY or PYTHON_ANALYSIS
            - chartType: Best chart type for this analysis
            - requiresPythonAnalysis: true if complex calculations needed
            - pythonRequirements: List of specific analysis needs
            - dataFilters: How to filter/transform data for this unit
            - priority: 1=highest (primary), 2=secondary, 3=supplementary
            
            DECISION CRITERIA:
            - Single data point or very simple data → NO parallel units
            - Multi-dimensional data with 2+ meaningful analysis angles → YES parallel units
            - Time-series data → Often benefits from parallel time window analysis
            - Categorical data with multiple segments → Consider segmentation units
            - Complex business questions → Multiple analytical approaches
            
            Limit to 3-5 parallel units maximum to avoid overwhelming users.
        """.trimIndent()

        val userPrompt = """
            Original Query: $originalQuery
            
            Cypher Query: $cypherQuery
            
            Natural Language Response: $naturalLanguageResponse
            
            Data Structure Analysis:
            - Total Records: ${queryResults.size}
            - Sample Data: ${objectMapper.writeValueAsString(queryResults.take(3))}
            - Available Fields: ${queryResults.firstOrNull()?.keys?.joinToString(", ") ?: "No data"}
            
            Determine if this data would benefit from parallel visualization units and suggest specific units.
        """.trimIndent()

        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        )

        val request = CreateCompletionRequest(
            model = "gpt-4.1",
            messages = messages,
            temperature = 0.2,
            response_format = mapOf(
                "type" to "json_schema",
                "json_schema" to mapOf(
                    "name" to "parallel_analysis_recommendation",
                    "schema" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "analysisType" to mapOf(
                                "type" to "string",
                                "enum" to AnalysisType.values().map { it.name }
                            ),
                            "reasoning" to mapOf("type" to "string"),
                            "chartType" to mapOf(
                                "type" to "string",
                                "enum" to ChartType.values().map { it.name }
                            ),
                            "pythonRequirements" to mapOf(
                                "type" to "array",
                                "items" to mapOf("type" to "string")
                            ),
                            "complexity" to mapOf(
                                "type" to "string",
                                "enum" to listOf("low", "medium", "high")
                            ),
                            "recommendsParallelUnits" to mapOf("type" to "boolean"),
                            "suggestedUnits" to mapOf(
                                "type" to "array",
                                "items" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "name" to mapOf("type" to "string"),
                                        "description" to mapOf("type" to "string"),
                                        "analysisType" to mapOf(
                                            "type" to "string",
                                            "enum" to AnalysisType.values().map { it.name }
                                        ),
                                        "chartType" to mapOf(
                                            "type" to "string",
                                            "enum" to ChartType.values().map { it.name }
                                        ),
                                        "requiresPythonAnalysis" to mapOf("type" to "boolean"),
                                        "pythonRequirements" to mapOf(
                                            "type" to "array",
                                            "items" to mapOf("type" to "string")
                                        ),
                                        "dataFilters" to mapOf("type" to "object"),
                                        "priority" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 5)
                                    ),
                                    "required" to listOf("name", "description", "analysisType", "chartType", "requiresPythonAnalysis", "priority")
                                )
                            ),
                            "currentDateContext" to mapOf("type" to "string")
                        ),
                        "required" to listOf("analysisType", "reasoning", "complexity", "recommendsParallelUnits")
                    )
                )
            )
        )

        return try {
            val response = modelService.createCompletion<Map<String, Any>>(request, apiKey)
            
            // Convert the response to IntelligentAnalysisRecommendation
            val suggestedUnits = (response["suggestedUnits"] as? List<Map<String, Any>> ?: emptyList()).map { unitMap ->
                VisualizationUnit(
                    name = unitMap["name"] as String,
                    description = unitMap["description"] as String,
                    analysisType = AnalysisType.valueOf(unitMap["analysisType"] as String),
                    chartType = ChartType.valueOf(unitMap["chartType"] as String),
                    requiresPythonAnalysis = unitMap["requiresPythonAnalysis"] as Boolean,
                    pythonRequirements = (unitMap["pythonRequirements"] as? List<String>) ?: emptyList(),
                    dataFilters = (unitMap["dataFilters"] as? Map<String, Any>) ?: emptyMap(),
                    priority = (unitMap["priority"] as? Number)?.toInt() ?: 3
                )
            }
            
            IntelligentAnalysisRecommendation(
                analysisType = AnalysisType.valueOf(response["analysisType"] as String),
                reasoning = response["reasoning"] as String,
                chartType = (response["chartType"] as? String)?.let { ChartType.valueOf(it) },
                pythonRequirements = (response["pythonRequirements"] as? List<String>) ?: emptyList(),
                complexity = response["complexity"] as String,
                recommendsParallelUnits = response["recommendsParallelUnits"] as Boolean,
                suggestedUnits = suggestedUnits,
                currentDateContext = response["currentDateContext"] as? String ?: dateContext
            )
        } catch (e: Exception) {
            logger.error(e) { "Error getting parallel visualization recommendation: ${e.message}" }
            IntelligentAnalysisRecommendation(
                analysisType = AnalysisType.NO_VISUALIZATION,
                reasoning = "Error occurred during parallel analysis recommendation: ${e.message}",
                recommendsParallelUnits = false
            )
        }
    }

    /**
     * Execute multiple visualization units in parallel.
     */
    private suspend fun executeParallelVisualizationUnits(
        request: ParallelVisualizationRequest,
        apiKey: String,
    ): List<VisualizationData> = withContext(Dispatchers.Default) {
        logger.info { "Executing ${request.visualizationUnits.size} parallel visualization units" }

        val results = mutableListOf<VisualizationData>()
        val startTime = System.currentTimeMillis()

        try {
            // Execute units in parallel with timeout
            val deferredResults = request.visualizationUnits.mapIndexed { index, unit ->
                async {
                    try {
                        logger.info { "Starting visualization unit: ${unit.name}" }
                        val unitStartTime = System.currentTimeMillis()
                        
                        // Filter data for this unit if filters are specified
                        val filteredData = filterDataForUnit(request.queryResults, unit.dataFilters)
                        
                        // Generate visualization for this unit
                        val visualization = intelligentVisualizationService.generateIntelligentVisualization(
                            originalQuery = "${request.originalQuery} - ${unit.description}",
                            cypherQuery = request.cypherQuery,
                            queryResults = filteredData,
                            naturalLanguageResponse = request.naturalLanguageResponse,
                            apiKey = apiKey,
                            enablePythonAnalysis = request.enablePythonAnalysis && unit.requiresPythonAnalysis
                        )

                        val executionTime = System.currentTimeMillis() - unitStartTime
                        logger.info { "Completed visualization unit: ${unit.name} in ${executionTime}ms" }

                        visualization?.copy(
                            unitName = unit.name,
                            unitIndex = index,
                            title = "${visualization.title} - ${unit.name}",
                            description = "${unit.description}: ${visualization.description}"
                        )
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to generate visualization unit: ${unit.name} - ${e.message}" }
                        null
                    }
                }
            }

            // Wait for all units to complete with timeout
            val completedResults = withTimeoutOrNull(PARALLEL_TIMEOUT_SECONDS * 1000) {
                deferredResults.awaitAll()
            }

            if (completedResults != null) {
                results.addAll(completedResults.filterNotNull())
            } else {
                logger.warn { "Parallel visualization execution timed out after ${PARALLEL_TIMEOUT_SECONDS}s" }
                // Cancel any still-running jobs
                deferredResults.forEach { it.cancel() }
            }

        } catch (e: Exception) {
            logger.error(e) { "Error in parallel visualization execution: ${e.message}" }
        }

        val totalTime = System.currentTimeMillis() - startTime
        logger.info { "Parallel visualization completed: ${results.size}/${request.visualizationUnits.size} units successful in ${totalTime}ms" }

        results
    }

    /**
     * Filter data for a specific visualization unit based on its filters.
     */
    private fun filterDataForUnit(data: List<Map<String, Any>>, filters: Map<String, Any>): List<Map<String, Any>> {
        if (filters.isEmpty()) return data

        return data.filter { record ->
            filters.all { (filterKey, filterValue) ->
                val recordValue = record[filterKey]
                when (filterValue) {
                    is String -> recordValue?.toString() == filterValue
                    is Number -> recordValue?.toString()?.toDoubleOrNull() == filterValue.toDouble()
                    is List<*> -> filterValue.contains(recordValue)
                    else -> true // Skip unknown filter types
                }
            }
        }
    }

    /**
     * Build enhanced date context for time-aware analysis.
     */
    private fun buildEnhancedDateContext(currentDate: LocalDate, currentDateTime: LocalDateTime): String {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE
        val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        
        return """
            CURRENT DATE & TIME CONTEXT (use for time-aware analysis):
            - Current Date: ${currentDate.format(formatter)} (YYYY-MM-DD)
            - Current DateTime: ${currentDateTime.format(timeFormatter)}
            - Current Year: ${currentDate.year}
            - Current Month: ${currentDate.monthValue} (${currentDate.month.name})
            - Current Day of Month: ${currentDate.dayOfMonth}
            - Current Day of Week: ${currentDate.dayOfWeek.name}
            
            TIME PERIODS FOR ANALYSIS:
            - Last 7 days: ${currentDate.minusDays(7).format(formatter)} to ${currentDate.format(formatter)}
            - Last 30 days: ${currentDate.minusDays(30).format(formatter)} to ${currentDate.format(formatter)}
            - This month: ${currentDate.withDayOfMonth(1).format(formatter)} to ${currentDate.format(formatter)}
            - Last month: ${currentDate.minusMonths(1).withDayOfMonth(1).format(formatter)} to ${currentDate.minusMonths(1).withDayOfMonth(currentDate.minusMonths(1).lengthOfMonth()).format(formatter)}
            - This year: ${currentDate.withDayOfYear(1).format(formatter)} to ${currentDate.format(formatter)}
            - Last year: ${currentDate.minusYears(1).withDayOfYear(1).format(formatter)} to ${currentDate.minusYears(1).withDayOfYear(currentDate.minusYears(1).lengthOfYear()).format(formatter)}
            
            Use this context to create time-aware parallel analysis units when dealing with temporal data.
        """.trimIndent()
    }

    /**
     * Generate a summary of the parallel execution results.
     */
    private fun generateExecutionSummary(
        plannedUnits: List<VisualizationUnit>,
        completedVisualizations: List<VisualizationData>,
    ): String {
        val successful = completedVisualizations.size
        val total = plannedUnits.size
        val failed = total - successful

        val summary = StringBuilder()
        summary.appendLine("**Parallel Visualization Analysis Summary**")
        summary.appendLine("- **Total Analysis Units**: $total")
        summary.appendLine("- **Successfully Generated**: $successful")
        if (failed > 0) {
            summary.appendLine("- **Failed Units**: $failed")
        }
        summary.appendLine()

        if (completedVisualizations.isNotEmpty()) {
            summary.appendLine("**Generated Visualizations**:")
            completedVisualizations.sortedBy { it.unitIndex ?: 0 }.forEach { viz ->
                summary.appendLine("- **${viz.unitName}**: ${viz.chartType} chart - ${viz.title}")
            }
        }

        return summary.toString()
    }
} 