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

/**
 * Unified service for executing all types of visualizations.
 * 
 * This service handles:
 * - Single simple visualizations (direct Plotly)
 * - Single complex visualizations (Python + Plotly)  
 * - Multiple parallel visualizations
 * - Python analysis when required
 * 
 * Unified service that handles all visualization execution strategies in one place.
 * Replaced the previous scattered visualization logic across multiple services.
 */
@Service
class VisualizationExecutorService(
    private val modelService: ModelService,
    private val pistonCodeExecutorService: PistonCodeExecutorService,
) {
    private val logger = KotlinLogging.logger {}
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    companion object {
        private const val PARALLEL_TIMEOUT_SECONDS = 60L
    }

    /**
     * Execute visualization based on query analysis results.
     */
    suspend fun executeVisualization(
        request: VisualizationExecutionRequest,
        apiKey: String,
    ): VisualizationExecutionResult {
        logger.info { "Executing visualization strategy: ${request.strategy} for query: ${request.originalQuery}" }

        if (request.queryResults.isEmpty()) {
            logger.info { "No results to visualize" }
            return VisualizationExecutionResult.noVisualization("No data available for visualization")
        }

        return when (request.strategy) {
            "TEXT_ONLY" -> VisualizationExecutionResult.noVisualization("Text response sufficient")
            
            "TEXT_WITH_SIMPLE_VISUAL" -> executeSingleSimpleVisualization(request, apiKey)
            
            "TEXT_WITH_PYTHON_VISUAL" -> executeSinglePythonVisualization(request, apiKey)
            
            "TEXT_WITH_MULTIPLE_VISUALS" -> executeMultipleVisualizations(request, apiKey)
            
            "COMPREHENSIVE_ANALYSIS" -> executeComprehensiveAnalysis(request, apiKey)
            
            else -> {
                logger.warn { "Unknown visualization strategy: ${request.strategy}" }
                VisualizationExecutionResult.noVisualization("Unknown strategy: ${request.strategy}")
            }
        }
    }

    /**
     * Execute a single simple visualization (direct Plotly).
     */
    private suspend fun executeSingleSimpleVisualization(
        request: VisualizationExecutionRequest,
        apiKey: String,
    ): VisualizationExecutionResult {
        logger.info { "Executing single simple visualization" }

        val chartType = request.analysis.visualizationPotential.primaryChartType 
            ?: return VisualizationExecutionResult.noVisualization("No chart type determined")

        val visualization = generateDirectPlotlyVisualization(
            chartType = chartType,
            queryResults = request.queryResults,
            originalQuery = request.originalQuery,
            title = request.title,
            apiKey = apiKey
        )

        return if (visualization != null) {
            VisualizationExecutionResult.singleVisualization(visualization)
        } else {
            VisualizationExecutionResult.noVisualization("Failed to generate simple visualization")
        }
    }

    /**
     * Execute a single visualization with Python analysis.
     */
    private suspend fun executeSinglePythonVisualization(
        request: VisualizationExecutionRequest,
        apiKey: String,
    ): VisualizationExecutionResult {
        logger.info { "Executing single Python-enhanced visualization" }

        val chartType = request.analysis.visualizationPotential.primaryChartType 
            ?: return VisualizationExecutionResult.noVisualization("No chart type determined")

        // Generate Python analysis code
        val pythonCode = generatePythonAnalysisCode(
            originalQuery = request.originalQuery,
            queryResults = request.queryResults,
            analysisRequirements = request.analysis.analysisRequirements,
            apiKey = apiKey
        ) ?: return VisualizationExecutionResult.noVisualization("Failed to generate Python code")

        // Execute Python analysis
        val pythonResult = pistonCodeExecutorService.executePythonCode(pythonCode, request.queryResults)

        // Generate visualization from Python results
        val visualization = generatePlotlyFromPythonAnalysis(
            pythonResult = pythonResult,
            chartType = chartType,
            originalQuery = request.originalQuery,
            title = request.title,
            apiKey = apiKey
        )

        return if (visualization != null) {
            VisualizationExecutionResult.singleVisualization(visualization)
        } else {
            VisualizationExecutionResult.noVisualization("Failed to generate Python-enhanced visualization")
        }
    }

    /**
     * Execute multiple parallel visualizations.
     */
    private suspend fun executeMultipleVisualizations(
        request: VisualizationExecutionRequest,
        apiKey: String,
    ): VisualizationExecutionResult {
        logger.info { "Executing multiple parallel visualizations" }

        // Generate visualization units
        val visualizationUnits = generateVisualizationUnits(
            originalQuery = request.originalQuery,
            queryResults = request.queryResults,
            analysis = request.analysis,
            suggestedCount = request.analysis.visualizationPotential.suggestedVisualCount,
            apiKey = apiKey
        )

        if (visualizationUnits.isEmpty()) {
            return VisualizationExecutionResult.noVisualization("No visualization units could be generated")
        }

        // Execute units in parallel
        val visualizations = executeUnitsInParallel(visualizationUnits, request, apiKey)

        return if (visualizations.isNotEmpty()) {
            val primary = visualizations.minByOrNull { it.unitIndex ?: Int.MAX_VALUE } ?: visualizations.first()
            val additional = visualizations.filter { it != primary }
            
            VisualizationExecutionResult.multipleVisualizations(
                primary = primary,
                additional = additional,
                totalUnits = visualizationUnits.size,
                successfulUnits = visualizations.size
            )
        } else {
            VisualizationExecutionResult.noVisualization("All visualization units failed")
        }
    }

    /**
     * Execute comprehensive analysis with multiple Python analyses and visualizations.
     */
    private suspend fun executeComprehensiveAnalysis(
        request: VisualizationExecutionRequest,
        apiKey: String,
    ): VisualizationExecutionResult {
        logger.info { "Executing comprehensive analysis" }

        // For comprehensive analysis, generate more sophisticated units
        val analysisUnits = generateComprehensiveAnalysisUnits(
            originalQuery = request.originalQuery,
            queryResults = request.queryResults,
            analysis = request.analysis,
            apiKey = apiKey
        )

        if (analysisUnits.isEmpty()) {
            return VisualizationExecutionResult.noVisualization("No comprehensive analysis units could be generated")
        }

        // Execute with Python analysis for each unit
        val visualizations = executeUnitsInParallel(analysisUnits, request, apiKey, forcePython = true)

        return if (visualizations.isNotEmpty()) {
            val primary = visualizations.minByOrNull { it.unitIndex ?: Int.MAX_VALUE } ?: visualizations.first()
            val additional = visualizations.filter { it != primary }
            
            VisualizationExecutionResult.comprehensiveAnalysis(
                primary = primary,
                additional = additional,
                totalUnits = analysisUnits.size,
                successfulUnits = visualizations.size,
                pythonAnalyses = visualizations.mapNotNull { it.pythonAnalysis }
            )
        } else {
            VisualizationExecutionResult.noVisualization("All comprehensive analysis units failed")
        }
    }

    /**
     * Generate visualization units for parallel execution.
     */
    private suspend fun generateVisualizationUnits(
        originalQuery: String,
        queryResults: List<Map<String, Any>>,
        analysis: QueryAnalysis,
        suggestedCount: Int,
        apiKey: String,
    ): List<VisualizationExecutionUnit> {
        val systemPrompt = """
            You are a visualization strategy expert. Based on the query analysis, generate $suggestedCount complementary visualization units.
            
            Each unit should focus on a different analytical perspective:
            - Temporal analysis (trends over time)
            - Categorical analysis (comparisons between groups)
            - Distribution analysis (patterns in data)
            - Performance analysis (KPIs and metrics)
            - Segmentation analysis (breaking down by key dimensions)
            
            Query Type: ${analysis.queryType}
            Data Dimensionality: ${analysis.dataCharacteristics.dimensionality}
            Temporal Aspect: ${analysis.dataCharacteristics.temporalAspect}
            Categorical Aspect: ${analysis.dataCharacteristics.categoricalAspect}
            
            For each unit, specify:
            - name: Clear descriptive name
            - description: What insights this unit provides
            - chartType: Best chart type for this analysis
            - requiresPython: Whether Python analysis would add value
            - dataFilters: ONLY add if you need to filter data (e.g., specific date ranges, categories). LEAVE EMPTY if all data should be used.
            - priority: 1=primary, 2=secondary, 3=supplementary
            
            IMPORTANT: Most units should have EMPTY dataFilters to use all available data. Only filter if you need a specific subset.
        """.trimIndent()

        val userPrompt = """
            Original Query: $originalQuery
            
            Data Sample: ${objectMapper.writeValueAsString(queryResults.take(2))}
            Total Records: ${queryResults.size}
            Available Fields: ${queryResults.firstOrNull()?.keys?.joinToString(", ") ?: "None"}
            
            Generate $suggestedCount complementary visualization units for this data.
            Remember: Use empty dataFilters {} for most units unless specific filtering is needed.
        """.trimIndent()

        return try {
            val response = callLlmForVisualizationUnits(systemPrompt, userPrompt, apiKey)
            val units = parseVisualizationUnits(response)
            
            // Log the generated units for debugging
            units.forEach { unit ->
                logger.info { "Generated visualization unit: ${unit.name}, chartType: ${unit.chartType}, dataFilters: ${unit.dataFilters}" }
            }
            
            if (units.isNotEmpty()) {
                units
            } else {
                logger.warn { "LLM returned no visualization units, creating fallback units" }
                createFallbackVisualizationUnits(queryResults, suggestedCount, analysis)
            }
        } catch (e: Exception) {
            logger.error(e) { "Error generating visualization units: ${e.message}" }
            logger.info { "Creating fallback visualization units due to error" }
            createFallbackVisualizationUnits(queryResults, suggestedCount, analysis)
        }
    }

    /**
     * Generate comprehensive analysis units with more sophisticated analysis.
     */
    private suspend fun generateComprehensiveAnalysisUnits(
        originalQuery: String,
        queryResults: List<Map<String, Any>>,
        analysis: QueryAnalysis,
        apiKey: String,
    ): List<VisualizationExecutionUnit> {
        val systemPrompt = """
            You are an advanced analytics strategist. Generate comprehensive analysis units that provide deep business insights.
            
            Focus on sophisticated analytical approaches:
            - Statistical analysis (correlations, distributions, outliers)
            - Trend analysis (forecasting, seasonality, growth patterns)
            - Comparative analysis (benchmarking, performance comparisons)
            - Segmentation analysis (clustering, cohort analysis)
            - Business intelligence (KPI dashboards, executive summaries)
            
            Each unit should require Python analysis for meaningful insights.
            
            Query Complexity: ${analysis.complexity}
            Analysis Requirements: ${analysis.analysisRequirements.joinToString(", ")}
        """.trimIndent()

        val userPrompt = """
            Original Query: $originalQuery
            
            Data Sample: ${objectMapper.writeValueAsString(queryResults.take(3))}
            Total Records: ${queryResults.size}
            Available Fields: ${queryResults.firstOrNull()?.keys?.joinToString(", ") ?: "None"}
            
            Generate 3-4 comprehensive analysis units that provide deep business insights.
        """.trimIndent()

        return try {
            val response = callLlmForVisualizationUnits(systemPrompt, userPrompt, apiKey)
            parseVisualizationUnits(response).map { unit ->
                // Force Python analysis for comprehensive units
                unit.copy(requiresPython = true)
            }
        } catch (e: Exception) {
            logger.error(e) { "Error generating comprehensive analysis units: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Execute visualization units in parallel.
     */
    private suspend fun executeUnitsInParallel(
        units: List<VisualizationExecutionUnit>,
        request: VisualizationExecutionRequest,
        apiKey: String,
        forcePython: Boolean = false,
    ): List<VisualizationData> = withContext(Dispatchers.Default) {
        logger.info { "Executing ${units.size} visualization units in parallel" }

        try {
            val deferredResults = units.mapIndexed { index, unit ->
                async {
                    try {
                        logger.info { "Starting visualization unit: ${unit.name}" }
                        val startTime = System.currentTimeMillis()

                        // Filter data if filters specified
                        val filteredData = filterDataForUnit(request.queryResults, unit.dataFilters)
                        
                        // Safety check: if filtering results in no data, use all data instead
                        val dataToUse = if (filteredData.isEmpty() && request.queryResults.isNotEmpty()) {
                            logger.warn { "Visualization unit '${unit.name}' has no data after filtering, using all data instead" }
                            request.queryResults
                        } else {
                            filteredData
                        }

                        // Execute visualization unit
                        val visualization = executeVisualizationUnit(
                            unit = unit,
                            filteredData = dataToUse,
                            originalQuery = request.originalQuery,
                            unitIndex = index,
                            enablePython = forcePython || unit.requiresPython,
                            apiKey = apiKey
                        )

                        val executionTime = System.currentTimeMillis() - startTime
                        logger.info { "Completed visualization unit: ${unit.name} in ${executionTime}ms" }

                        visualization
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to execute visualization unit: ${unit.name} - ${e.message}" }
                        null
                    }
                }
            }

            // Wait for all units with timeout
            val results = withTimeoutOrNull(PARALLEL_TIMEOUT_SECONDS * 1000) {
                deferredResults.awaitAll()
            }

            if (results != null) {
                results.filterNotNull()
            } else {
                logger.warn { "Visualization execution timed out after ${PARALLEL_TIMEOUT_SECONDS}s" }
                deferredResults.forEach { it.cancel() }
                emptyList()
            }
        } catch (e: Exception) {
            logger.error(e) { "Error in parallel visualization execution: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Execute a single visualization unit.
     */
    private suspend fun executeVisualizationUnit(
        unit: VisualizationExecutionUnit,
        filteredData: List<Map<String, Any>>,
        originalQuery: String,
        unitIndex: Int,
        enablePython: Boolean,
        apiKey: String,
    ): VisualizationData? {
        return if (enablePython) {
            // Execute with Python analysis
            val pythonCode = generatePythonAnalysisCode(
                originalQuery = "$originalQuery - ${unit.description}",
                queryResults = filteredData,
                analysisRequirements = listOf("STATISTICAL", "MATHEMATICAL"),
                apiKey = apiKey
            ) ?: return null

            val pythonResult = pistonCodeExecutorService.executePythonCode(pythonCode, filteredData)

            generatePlotlyFromPythonAnalysis(
                pythonResult = pythonResult,
                chartType = unit.chartType,
                originalQuery = originalQuery,
                title = unit.name,
                apiKey = apiKey
            )?.copy(
                unitName = unit.name,
                unitIndex = unitIndex,
                description = "${unit.description}: ${unit.name}"
            )
        } else {
            // Direct Plotly generation
            generateDirectPlotlyVisualization(
                chartType = unit.chartType,
                queryResults = filteredData,
                originalQuery = originalQuery,
                title = unit.name,
                apiKey = apiKey
            )?.copy(
                unitName = unit.name,
                unitIndex = unitIndex,
                description = "${unit.description}: ${unit.name}"
            )
        }
    }

    // Helper methods for generating visualizations (consolidated from existing services)
    private suspend fun generateDirectPlotlyVisualization(
        chartType: ChartType,
        queryResults: List<Map<String, Any>>,
        originalQuery: String,
        title: String?,
        apiKey: String,
    ): VisualizationData? {
        logger.info { "Generating direct Plotly visualization for $chartType with ${queryResults.size} data points" }
        
        if (queryResults.isEmpty()) {
            logger.warn { "No data available for visualization" }
            return null
        }
        
        val systemPrompt = """
            You are a Plotly.js expert. Generate a complete Plotly.js configuration for a $chartType chart.
            
            CRITICAL REQUIREMENTS:
            1. "data": Array of trace objects with PROPERLY POPULATED x and y arrays (never empty!)
            2. "layout": Layout configuration object with titles, axes, colors, etc.
            3. "title": Chart title
            4. "description": Brief description of what the chart shows
            
            DATA TRANSFORMATION RULES:
            - Extract actual values from the query results for x and y arrays
            - For LINE charts: x should be dates/categories, y should be numeric values
            - For BAR charts: x should be categories, y should be counts/values
            - For PIE charts: use "labels" and "values" (not x/y)
            - Ensure numeric values are numbers, not strings
            - Sort data appropriately (dates chronologically, bars by value, etc.)
            
            EXAMPLE TRANSFORMATIONS:
            For time series data like [{"date": "2025-01-01", "count": 10}, {"date": "2025-01-02", "count": 15}]:
            - x: ["2025-01-01", "2025-01-02"]
            - y: [10, 15]
            
            For categorical data like [{"category": "A", "total": 100}, {"category": "B", "total": 200}]:
            - x: ["A", "B"] 
            - y: [100, 200]
            
            Make the visualization:
            - Professional and business-appropriate
            - Accessible (good color contrast, clear labels)
            - Interactive where appropriate
            - Responsive to different screen sizes
            - NEVER return empty x or y arrays!
        """.trimIndent()

        val userPrompt = """
            Chart Type: $chartType
            Title: ${title ?: "Data Visualization"}
            Original Query: $originalQuery
            
            Query Results to Transform:
            ${objectMapper.writeValueAsString(queryResults)}
            
            TASK: Analyze the query results and generate a complete Plotly.js configuration.
            - Identify the appropriate columns for x and y axes
            - Transform the data into proper Plotly format
            - Ensure x and y arrays are populated with actual data values
            - Create meaningful axis labels based on the data structure
            
            The query results contain ${queryResults.size} records. Extract the relevant data and create an appropriate $chartType visualization.
        """.trimIndent()

        return try {
            val response = generatePlotlyConfig(systemPrompt, userPrompt, apiKey)
            
            // Validate that the response has proper data
            if (response.data.isEmpty() || 
                response.data.any { trace -> 
                    val hasEmptyData = when (chartType) {
                        ChartType.PIE -> {
                            val labels = trace["labels"] as? List<*>
                            val values = trace["values"] as? List<*>
                            labels.isNullOrEmpty() || values.isNullOrEmpty()
                        }
                        else -> {
                            val x = trace["x"] as? List<*>
                            val y = trace["y"] as? List<*>
                            x.isNullOrEmpty() || y.isNullOrEmpty()
                        }
                    }
                    hasEmptyData
                }) {
                
                logger.warn { "Generated visualization has empty data arrays, creating fallback visualization" }
                
                // Create a fallback visualization from the query results
                return generateFallbackVisualization(chartType, queryResults, title ?: "Data Visualization")
            }
            
            logger.info { "Successfully generated direct Plotly visualization with ${response.data.size} traces" }
            VisualizationData(
                chartType = chartType,
                data = response.data,
                layout = response.layout,
                title = response.title,
                description = response.description
            )
        } catch (e: Exception) {
            logger.error(e) { "Error generating direct Plotly visualization: ${e.message}" }
            
            // Try fallback visualization
            logger.info { "Attempting fallback visualization generation" }
            val fallback = generateFallbackVisualization(chartType, queryResults, title ?: "Data Visualization")
            if (fallback != null) {
                logger.info { "Successfully generated fallback visualization" }
            } else {
                logger.error { "Both LLM and fallback visualization generation failed" }
            }
            fallback
        }
    }

    private suspend fun generatePythonAnalysisCode(
        originalQuery: String,
        queryResults: List<Map<String, Any>>,
        analysisRequirements: List<String>,
        apiKey: String,
    ): String? {
        val systemPrompt = """
            Generate Python code for sophisticated data analysis.
            Requirements: ${analysisRequirements.joinToString(", ")}
            
            Use pandas, numpy, scipy as needed. Focus on extracting meaningful insights.
            The data will be available as 'data' variable (list of dictionaries).
        """.trimIndent()

        val userPrompt = """
            Original Query: $originalQuery
            Data Sample: ${objectMapper.writeValueAsString(queryResults.take(2))}
            Total Records: ${queryResults.size}
            
            Generate Python analysis code for this data.
        """.trimIndent()

        return try {
            val response = modelService.fetchCompletionPayload(
                CreateCompletionRequest(
                    model = System.getenv("OPENAI_MODEL"),
                    messages = listOf(
                        mapOf("role" to "system", "content" to systemPrompt),
                        mapOf("role" to "user", "content" to userPrompt)
                    ),
                    temperature = 0.1
                ),
                apiKey
            )
            
            // Extract code from markdown
            val codePattern = Regex("```python\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
            codePattern.find(response)?.groups?.get(1)?.value?.trim() ?: response.trim()
        } catch (e: Exception) {
            logger.error(e) { "Error generating Python code: ${e.message}" }
            null
        }
    }

    private suspend fun generatePlotlyFromPythonAnalysis(
        pythonResult: PythonAnalysisResult,
        chartType: ChartType,
        originalQuery: String,
        title: String?,
        apiKey: String,
    ): VisualizationData? {
        if (!pythonResult.success) {
            logger.warn { "Python analysis failed: ${pythonResult.error}" }
            return null
        }

        val systemPrompt = """
            You are a Plotly.js expert. Generate Plotly.js configuration based on Python analysis results.
            
            CRITICAL REQUIREMENTS:
            1. "data": Array of trace objects with PROPERLY POPULATED x and y arrays (never empty!)
            2. "layout": Layout configuration object with titles, axes, colors, etc.
            3. "title": Chart title  
            4. "description": Brief description of what the chart shows
            
            Chart type: $chartType
            
            DATA EXTRACTION RULES:
            - Parse the Python output to extract actual data values
            - Look for numeric results, calculated metrics, or data summaries
            - Transform extracted data into proper Plotly format with populated arrays
            - For LINE charts: x should be categories/dates, y should be numeric values
            - For BAR charts: x should be categories, y should be counts/values
            - For PIE charts: use "labels" and "values" arrays
            - NEVER return empty x or y arrays!
            
            Make the visualization professional, accessible, and clearly communicate insights from the Python analysis.
        """.trimIndent()

        val userPrompt = """
            Original Query: $originalQuery
            Chart Type: $chartType
            Title: ${title ?: "Analysis Results"}
            
            Python Analysis Output:
            ${pythonResult.output}
            
            Python Code Executed:
            ${pythonResult.code}
            
            TASK: Extract meaningful data from the Python analysis output and create a complete Plotly.js configuration.
            - Parse the analysis results for numeric data, trends, or insights
            - Transform this data into proper Plotly format with populated x and y arrays
            - Create meaningful labels and titles based on the analysis context
            - Ensure the visualization effectively communicates the Python analysis insights
        """.trimIndent()

        return try {
            val response = generatePlotlyConfig(systemPrompt, userPrompt, apiKey)
            
            // Validate that the response has proper data
            if (response.data.isEmpty() || 
                response.data.any { trace -> 
                    val hasEmptyData = when (chartType) {
                        ChartType.PIE -> {
                            val labels = trace["labels"] as? List<*>
                            val values = trace["values"] as? List<*>
                            labels.isNullOrEmpty() || values.isNullOrEmpty()
                        }
                        else -> {
                            val x = trace["x"] as? List<*>
                            val y = trace["y"] as? List<*>
                            x.isNullOrEmpty() || y.isNullOrEmpty()
                        }
                    }
                    hasEmptyData
                }) {
                
                logger.warn { "Generated visualization from Python analysis has empty data arrays" }
                return null
            }
            
            logger.info { "Successfully generated Plotly visualization from Python analysis" }
            VisualizationData(
                chartType = chartType,
                data = response.data,
                layout = response.layout,
                title = response.title,
                description = response.description,
                pythonAnalysis = pythonResult
            )
        } catch (e: Exception) {
            logger.error(e) { "Error generating Plotly from Python analysis: ${e.message}" }
            null
        }
    }

    /**
     * Create fallback visualization units when LLM generation fails.
     */
    private fun createFallbackVisualizationUnits(
        queryResults: List<Map<String, Any>>,
        suggestedCount: Int,
        analysis: QueryAnalysis
    ): List<VisualizationExecutionUnit> {
        if (queryResults.isEmpty()) return emptyList()
        
        logger.info { "Creating $suggestedCount fallback visualization units" }
        
        val units = mutableListOf<VisualizationExecutionUnit>()
        val firstRecord = queryResults.first()
        val keys = firstRecord.keys.toList()
        
        // Identify column types
        val dateColumns = keys.filter { key -> 
            key.contains("date", ignoreCase = true) || 
            key.contains("time", ignoreCase = true) ||
            key.contains("created", ignoreCase = true)
        }
        
        val numericColumns = keys.filter { key ->
            val value = firstRecord[key]
            value is Number || key.contains("count", ignoreCase = true) || 
            key.contains("total", ignoreCase = true) || key.contains("avg", ignoreCase = true)
        }
        
        val categoryColumns = keys.filter { key ->
            !dateColumns.contains(key) && !numericColumns.contains(key)
        }
        
        // Unit 1: Primary visualization based on data characteristics
        if (analysis.dataCharacteristics.temporalAspect && dateColumns.isNotEmpty() && numericColumns.isNotEmpty()) {
            units.add(VisualizationExecutionUnit(
                name = "Daily Conversation and Resolution Trends",
                description = "Temporal analysis showing trends over time",
                chartType = ChartType.LINE,
                requiresPython = false,
                dataFilters = emptyMap(),
                priority = 1
            ))
        } else if (categoryColumns.isNotEmpty() && numericColumns.isNotEmpty()) {
            units.add(VisualizationExecutionUnit(
                name = "Conversation Classification Breakdown",
                description = "Categorical analysis comparing different groups",
                chartType = ChartType.BAR,
                requiresPython = false,
                dataFilters = emptyMap(),
                priority = 1
            ))
        } else {
            // Generic fallback
            units.add(VisualizationExecutionUnit(
                name = "Data Overview",
                description = "Overview of the dataset",
                chartType = ChartType.BAR,
                requiresPython = false,
                dataFilters = emptyMap(),
                priority = 1
            ))
        }
        
        // Unit 2: Secondary visualization if we want multiple
        if (suggestedCount > 1 && units.size < suggestedCount) {
            if (categoryColumns.isNotEmpty() && numericColumns.isNotEmpty()) {
                units.add(VisualizationExecutionUnit(
                    name = "Distribution Analysis",
                    description = "Distribution of key metrics across categories",
                    chartType = if (categoryColumns.size <= 7) ChartType.PIE else ChartType.BAR,
                    requiresPython = false,
                    dataFilters = emptyMap(),
                    priority = 2
                ))
            }
        }
        
        logger.info { "Created ${units.size} fallback visualization units" }
        return units
    }

    // Helper methods
    private suspend fun callLlmForVisualizationUnits(systemPrompt: String, userPrompt: String, apiKey: String): List<Map<String, Any>> {
        val request = CreateCompletionRequest(
            model = System.getenv("OPENAI_MODEL"),
            messages = listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
            ),
            temperature = 0.2,
            response_format = mapOf(
                "type" to "json_schema",
                "json_schema" to mapOf(
                    "name" to "visualization_units",
                    "schema" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "units" to mapOf(
                                "type" to "array",
                                "items" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "name" to mapOf("type" to "string"),
                                        "description" to mapOf("type" to "string"),
                                        "chartType" to mapOf("type" to "string", "enum" to ChartType.values().map { it.name }),
                                        "requiresPython" to mapOf("type" to "boolean"),
                                        "dataFilters" to mapOf("type" to "object"),
                                        "priority" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 5)
                                    ),
                                    "required" to listOf("name", "description", "chartType", "requiresPython", "priority")
                                )
                            )
                        ),
                        "required" to listOf("units")
                    )
                )
            )
        )

        val response = modelService.createCompletion<Map<String, Any>>(request, apiKey)
        return (response["units"] as? List<Map<String, Any>>) ?: emptyList()
    }

    private fun parseVisualizationUnits(units: List<Map<String, Any>>): List<VisualizationExecutionUnit> {
        return units.map { unitMap ->
            VisualizationExecutionUnit(
                name = unitMap["name"] as String,
                description = unitMap["description"] as String,
                chartType = ChartType.valueOf(unitMap["chartType"] as String),
                requiresPython = unitMap["requiresPython"] as Boolean,
                dataFilters = (unitMap["dataFilters"] as? Map<String, Any>) ?: emptyMap(),
                priority = (unitMap["priority"] as? Number)?.toInt() ?: 3
            )
        }
    }

    private suspend fun generatePlotlyConfig(systemPrompt: String, userPrompt: String, apiKey: String): PlotlyConfig {
        val request = CreateCompletionRequest(
            model = System.getenv("OPENAI_MODEL"),
            messages = listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt)
            ),
            temperature = 0.1,
            response_format = mapOf(
                "type" to "json_schema",
                "json_schema" to mapOf(
                    "name" to "plotly_config",
                    "schema" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "data" to mapOf("type" to "array", "items" to mapOf("type" to "object")),
                            "layout" to mapOf("type" to "object"),
                            "title" to mapOf("type" to "string"),
                            "description" to mapOf("type" to "string")
                        ),
                        "required" to listOf("data", "layout", "title", "description")
                    )
                )
            )
        )

        return modelService.createCompletion(request, apiKey)
    }

    private fun filterDataForUnit(data: List<Map<String, Any>>, filters: Map<String, Any>): List<Map<String, Any>> {
        if (filters.isEmpty()) {
            logger.debug { "No filters applied, returning all ${data.size} records" }
            return data
        }

        logger.info { "Applying filters to ${data.size} records: $filters" }

        val filteredData = data.filter { record ->
            filters.all { (filterKey, filterValue) ->
                val recordValue = record[filterKey]
                val matches = when (filterValue) {
                    is String -> recordValue?.toString() == filterValue
                    is Number -> recordValue?.toString()?.toDoubleOrNull() == filterValue.toDouble()
                    is List<*> -> filterValue.contains(recordValue)
                    else -> {
                        logger.warn { "Unknown filter value type: ${filterValue?.javaClass?.simpleName} for key $filterKey" }
                        true
                    }
                }
                
                if (!matches) {
                    logger.debug { "Record filtered out: $filterKey = $recordValue (expected: $filterValue)" }
                }
                
                matches
            }
        }

        logger.info { "Data filtering result: ${filteredData.size}/${data.size} records match filters" }
        
        if (filteredData.isEmpty() && data.isNotEmpty()) {
            logger.warn { "All data filtered out! Original data sample: ${data.firstOrNull()?.keys}" }
            logger.warn { "Filters applied: $filters" }
            logger.warn { "Available values for filter keys: ${filters.keys.map { key -> "$key: ${data.map { it[key] }.distinct().take(5)}" }}" }
        }

        return filteredData
    }

    private fun generateFallbackVisualization(chartType: ChartType, queryResults: List<Map<String, Any>>, title: String): VisualizationData? {
        logger.info { "Generating fallback visualization for $chartType with ${queryResults.size} records" }
        
        if (queryResults.isEmpty()) {
            return null
        }
        
        try {
            val firstRecord = queryResults.first()
            val keys = firstRecord.keys.toList()
            
            // Identify suitable columns for visualization
            val dateColumns = keys.filter { key -> 
                key.contains("date", ignoreCase = true) || 
                key.contains("time", ignoreCase = true) ||
                key.contains("created", ignoreCase = true)
            }
            
            val numericColumns = keys.filter { key ->
                val value = firstRecord[key]
                value is Number || key.contains("count", ignoreCase = true) || 
                key.contains("total", ignoreCase = true) || key.contains("avg", ignoreCase = true)
            }
            
            val categoryColumns = keys.filter { key ->
                !dateColumns.contains(key) && !numericColumns.contains(key)
            }
            
            return when (chartType) {
                ChartType.LINE -> generateLineChartFallback(queryResults, dateColumns, numericColumns, title)
                ChartType.BAR -> generateBarChartFallback(queryResults, categoryColumns, numericColumns, title)
                ChartType.PIE -> generatePieChartFallback(queryResults, categoryColumns, numericColumns, title)
                ChartType.SCATTER -> generateScatterChartFallback(queryResults, numericColumns, title)
                else -> generateBarChartFallback(queryResults, categoryColumns, numericColumns, title)
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Error generating fallback visualization: ${e.message}" }
            return null
        }
    }
    
    private fun generateLineChartFallback(
        queryResults: List<Map<String, Any>>,
        dateColumns: List<String>,
        numericColumns: List<String>,
        title: String
    ): VisualizationData? {
        val xColumn = dateColumns.firstOrNull() ?: queryResults.first().keys.first()
        val yColumn = numericColumns.firstOrNull() ?: queryResults.first().keys.last()
        
        val xData = queryResults.map { it[xColumn]?.toString() ?: "" }
        val yData = queryResults.map { 
            val value = it[yColumn]
            when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
        }
        
        val data = listOf(mapOf(
            "x" to xData,
            "y" to yData,
            "type" to "scatter",
            "mode" to "lines+markers",
            "name" to yColumn,
            "line" to mapOf("color" to "#1f77b4", "width" to 3),
            "marker" to mapOf("size" to 7, "color" to "#1f77b4")
        ))
        
        val layout = mapOf(
            "title" to mapOf("text" to title, "font" to mapOf("size" to 20)),
            "xaxis" to mapOf("title" to xColumn, "type" to "category"),
            "yaxis" to mapOf("title" to yColumn),
            "autosize" to true,
            "margin" to mapOf("l" to 60, "r" to 30, "t" to 80, "b" to 80)
        )
        
        return VisualizationData(
            chartType = ChartType.LINE,
            data = data,
            layout = layout,
            title = title,
            description = "Line chart showing $yColumn over $xColumn"
        )
    }
    
    private fun generateBarChartFallback(
        queryResults: List<Map<String, Any>>,
        categoryColumns: List<String>,
        numericColumns: List<String>,
        title: String
    ): VisualizationData? {
        val xColumn = categoryColumns.firstOrNull() ?: queryResults.first().keys.first()
        val yColumn = numericColumns.firstOrNull() ?: queryResults.first().keys.last()
        
        val xData = queryResults.map { it[xColumn]?.toString() ?: "" }
        val yData = queryResults.map { 
            val value = it[yColumn]
            when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
        }
        
        val data = listOf(mapOf(
            "x" to xData,
            "y" to yData,
            "type" to "bar",
            "marker" to mapOf("color" to "#2ca02c")
        ))
        
        val layout = mapOf(
            "title" to mapOf("text" to title, "font" to mapOf("size" to 20)),
            "xaxis" to mapOf("title" to xColumn),
            "yaxis" to mapOf("title" to yColumn),
            "autosize" to true,
            "margin" to mapOf("l" to 60, "r" to 30, "t" to 80, "b" to 80)
        )
        
        return VisualizationData(
            chartType = ChartType.BAR,
            data = data,
            layout = layout,
            title = title,
            description = "Bar chart showing $yColumn by $xColumn"
        )
    }
    
    private fun generatePieChartFallback(
        queryResults: List<Map<String, Any>>,
        categoryColumns: List<String>,
        numericColumns: List<String>,
        title: String
    ): VisualizationData? {
        val labelColumn = categoryColumns.firstOrNull() ?: queryResults.first().keys.first()
        val valueColumn = numericColumns.firstOrNull() ?: queryResults.first().keys.last()
        
        val labels = queryResults.map { it[labelColumn]?.toString() ?: "" }
        val values = queryResults.map { 
            val value = it[valueColumn]
            when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
        }
        
        val data = listOf(mapOf(
            "labels" to labels,
            "values" to values,
            "type" to "pie",
            "hole" to 0.3
        ))
        
        val layout = mapOf(
            "title" to mapOf("text" to title, "font" to mapOf("size" to 20)),
            "autosize" to true,
            "margin" to mapOf("l" to 60, "r" to 30, "t" to 80, "b" to 80)
        )
        
        return VisualizationData(
            chartType = ChartType.PIE,
            data = data,
            layout = layout,
            title = title,
            description = "Pie chart showing distribution of $valueColumn by $labelColumn"
        )
    }
    
    private fun generateScatterChartFallback(
        queryResults: List<Map<String, Any>>,
        numericColumns: List<String>,
        title: String
    ): VisualizationData? {
        if (numericColumns.size < 2) {
            // Fall back to bar chart if we don't have two numeric columns
            return generateBarChartFallback(queryResults, emptyList(), numericColumns, title)
        }
        
        val xColumn = numericColumns[0]
        val yColumn = numericColumns[1]
        
        val xData = queryResults.map { 
            val value = it[xColumn]
            when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
        }
        
        val yData = queryResults.map { 
            val value = it[yColumn]
            when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
        }
        
        val data = listOf(mapOf(
            "x" to xData,
            "y" to yData,
            "type" to "scatter",
            "mode" to "markers",
            "marker" to mapOf("size" to 8, "color" to "#ff7f0e")
        ))
        
        val layout = mapOf(
            "title" to mapOf("text" to title, "font" to mapOf("size" to 20)),
            "xaxis" to mapOf("title" to xColumn),
            "yaxis" to mapOf("title" to yColumn),
            "autosize" to true,
            "margin" to mapOf("l" to 60, "r" to 30, "t" to 80, "b" to 80)
        )
        
        return VisualizationData(
            chartType = ChartType.SCATTER,
            data = data,
            layout = layout,
            title = title,
            description = "Scatter plot showing relationship between $xColumn and $yColumn"
        )
    }
}

// Data classes
data class VisualizationExecutionRequest(
    val originalQuery: String,
    val cypherQuery: String,
    val queryResults: List<Map<String, Any>>,
    val naturalLanguageResponse: String,
    val strategy: String,
    val analysis: QueryAnalysis,
    val title: String? = null
)

data class VisualizationExecutionResult(
    val success: Boolean,
    val visualizations: List<VisualizationData>,
    val type: VisualizationResultType,
    val summary: String,
    val pythonAnalyses: List<PythonAnalysisResult> = emptyList()
) {
    companion object {
        fun noVisualization(reason: String) = VisualizationExecutionResult(
            success = false,
            visualizations = emptyList(),
            type = VisualizationResultType.NONE,
            summary = reason
        )

        fun singleVisualization(visualization: VisualizationData) = VisualizationExecutionResult(
            success = true,
            visualizations = listOf(visualization),
            type = VisualizationResultType.SINGLE,
            summary = "Generated single visualization: ${visualization.title}"
        )

        fun multipleVisualizations(primary: VisualizationData, additional: List<VisualizationData>, totalUnits: Int, successfulUnits: Int) = VisualizationExecutionResult(
            success = true,
            visualizations = listOf(primary) + additional,
            type = VisualizationResultType.MULTIPLE,
            summary = "Generated $successfulUnits out of $totalUnits visualizations"
        )

        fun comprehensiveAnalysis(primary: VisualizationData, additional: List<VisualizationData>, totalUnits: Int, successfulUnits: Int, pythonAnalyses: List<PythonAnalysisResult>) = VisualizationExecutionResult(
            success = true,
            visualizations = listOf(primary) + additional,
            type = VisualizationResultType.COMPREHENSIVE,
            summary = "Generated comprehensive analysis with $successfulUnits visualizations and ${pythonAnalyses.size} Python analyses",
            pythonAnalyses = pythonAnalyses
        )
    }
}

enum class VisualizationResultType {
    NONE, SINGLE, MULTIPLE, COMPREHENSIVE
}

data class VisualizationExecutionUnit(
    val name: String,
    val description: String,
    val chartType: ChartType,
    val requiresPython: Boolean,
    val dataFilters: Map<String, Any> = emptyMap(),
    val priority: Int = 3
)

data class PlotlyConfig(
    val data: List<Map<String, Any>>,
    val layout: Map<String, Any>,
    val title: String,
    val description: String
) 