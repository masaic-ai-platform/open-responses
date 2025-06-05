package ai.masaic.improved.service

import ai.masaic.improved.ModelService
import ai.masaic.improved.createCompletion
import ai.masaic.improved.model.*
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Intelligent service for generating data analysis and visualizations.
 * 
 * This service intelligently decides between:
 * 1. Direct Plotly.js generation for simple data
 * 2. Python-based analysis with Plotly generation for complex scenarios
 * 
 * It can execute Python code safely in a sandboxed environment and generate
 * both the analysis results and corresponding visualizations.
 */
@Service
class IntelligentVisualizationService(
    private val modelService: ModelService,
    private val pistonCodeExecutorService: PistonCodeExecutorService,
) {
    private val logger = KotlinLogging.logger {}
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    companion object {
        // Constants if needed can be added here
    }

    /**
     * Check if Python execution is available (via Piston)
     */
    private fun checkPythonAvailability(): Boolean {
        val available = pistonCodeExecutorService.isAvailable()
        if (available) {
            logger.info { "Piston service is available for Python analysis" }
        } else {
            logger.warn { "Piston service not available. Python analysis will use mock execution or fall back to direct Plotly generation." }
        }
        return true // Always return true since we have mock fallback
    }

    /**
     * Intelligently analyze data and generate appropriate visualization
     */
    suspend fun generateIntelligentVisualization(
        originalQuery: String,
        cypherQuery: String,
        queryResults: List<Map<String, Any>>,
        naturalLanguageResponse: String,
        apiKey: String,
        enablePythonAnalysis: Boolean = true,
    ): VisualizationData? {
        if (queryResults.isEmpty()) {
            logger.info { "No results to visualize" }
            return null
        }
        
        logger.info { "Starting intelligent visualization analysis for ${queryResults.size} results" }
        
        // Step 1: Get intelligent analysis recommendation
        val recommendation =
            getAnalysisRecommendation(
                originalQuery,
                cypherQuery,
                queryResults,
                naturalLanguageResponse,
                apiKey,
            )
        
        logger.info { "Analysis recommendation: ${recommendation.analysisType}, reasoning: ${recommendation.reasoning}" }
        
        return when (recommendation.analysisType) {
            AnalysisType.NO_VISUALIZATION -> {
                logger.info { "No visualization recommended" }
                null
            }
            AnalysisType.DIRECT_PLOTLY -> {
                logger.info { "Generating direct Plotly visualization" }
                generateDirectPlotlyVisualization(recommendation, queryResults, originalQuery, apiKey)
            }
            AnalysisType.PYTHON_ANALYSIS -> {
                if (enablePythonAnalysis) {
                    logger.info { "Generating Python-based analysis and visualization" }
                    generatePythonAnalysisVisualization(recommendation, queryResults, originalQuery, apiKey)
                } else {
                    logger.info { "Python analysis recommended but disabled by flag, falling back to direct Plotly" }
                    generateDirectPlotlyVisualization(
                        recommendation.copy(analysisType = AnalysisType.DIRECT_PLOTLY),
                        queryResults,
                        originalQuery,
                        apiKey
                    )
                }
            }
        }
    }

    /**
     * Get intelligent analysis recommendation from LLM
     */
    private suspend fun getAnalysisRecommendation(
        originalQuery: String,
        cypherQuery: String,
        queryResults: List<Map<String, Any>>,
        naturalLanguageResponse: String,
        apiKey: String,
    ): IntelligentAnalysisRecommendation {
        val systemPrompt =
            """
            You are an expert data analyst and visualization consultant. Your task is to intelligently decide 
            the best approach for analyzing and visualizing data based on the query context and data characteristics.
            
            ANALYSIS OPTIONS:
            
            1. DIRECT_PLOTLY: Use when data is simple and can be directly plotted
               - Simple aggregations (counts, sums, averages)
               - Basic time series data
               - Categorical comparisons
               - Data is already in the right format for visualization
            
            2. PYTHON_ANALYSIS: Use when sophisticated data analysis is needed
               - Statistical analysis (correlation, regression, clustering)
               - Advanced calculations (forecasting, anomaly detection, percentiles)
               - Data transformations (pivot tables, complex aggregations)
               - Mathematical operations (moving averages, growth rates, trend analysis)
               - Complex business calculations
               - Multi-step analysis requiring intermediate calculations
            
            3. NO_VISUALIZATION: Use when visualization doesn't add value
               - Single data points
               - Very simple text-based answers
               - Error responses or data issues
            
            CHART TYPES: BAR, LINE, PIE, SCATTER, HISTOGRAM, HEATMAP, TREEMAP, FUNNEL, AREA
            
            COMPLEXITY LEVELS:
            - low: Basic charts, simple data
            - medium: Multiple data series, some calculations
            - high: Advanced analytics, complex transformations
            
            Consider the user's question intent, data structure, and whether Python analysis would provide 
            meaningful insights beyond simple charting.
            """.trimIndent()
        
        val userPrompt =
            """
            Original Question: $originalQuery
            
            Cypher Query: $cypherQuery
            
            Data Sample: ${objectMapper.writeValueAsString(queryResults.take(5))}
            Total Results: ${queryResults.size}
            
            Natural Language Response: $naturalLanguageResponse
            
            Analyze this scenario and recommend the best approach for visualization/analysis.
            """.trimIndent()
        
        val messages =
            listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt),
            )
        
        val request =
            CreateCompletionRequest(
                model = "gpt-4.1",
                messages = messages,
                temperature = 0.2,
                response_format =
                    mapOf(
                        "type" to "json_schema",
                        "json_schema" to
                            mapOf(
                                "name" to "analysis_recommendation",
                                "schema" to
                                    mapOf(
                                        "type" to "object",
                                        "properties" to
                                            mapOf(
                                                "analysisType" to
                                                    mapOf(
                                                        "type" to "string",
                                                        "enum" to AnalysisType.values().map { it.name },
                                                    ),
                                                "reasoning" to mapOf("type" to "string"),
                                                "chartType" to
                                                    mapOf(
                                                        "type" to "string",
                                                        "enum" to ChartType.values().map { it.name },
                                                    ),
                                                "pythonRequirements" to
                                                    mapOf(
                                                        "type" to "array",
                                                        "items" to mapOf("type" to "string"),
                                                    ),
                                                "complexity" to
                                                    mapOf(
                                                        "type" to "string",
                                                        "enum" to listOf("low", "medium", "high"),
                                                    ),
                                            ),
                                        "required" to listOf("analysisType", "reasoning", "complexity"),
                                    ),
                            ),
                    ),
            )
        
        return try {
            val recommendation: IntelligentAnalysisRecommendation = modelService.createCompletion(request, apiKey)
            
            // If Python analysis was recommended but Python is not available, fall back to direct Plotly
            if (recommendation.analysisType == AnalysisType.PYTHON_ANALYSIS && !checkPythonAvailability()) {
                logger.info { "Python analysis recommended but Python not available, falling back to direct Plotly" }
                return recommendation.copy(
                    analysisType = AnalysisType.DIRECT_PLOTLY,
                    reasoning = "Python analysis was recommended but Python libraries are not available. Falling back to direct Plotly generation. Original reasoning: ${recommendation.reasoning}",
                )
            }
            
            recommendation
        } catch (e: Exception) {
            logger.error(e) { "Error getting analysis recommendation: ${e.message}" }
            IntelligentAnalysisRecommendation(
                analysisType = AnalysisType.NO_VISUALIZATION,
                reasoning = "Error occurred during analysis: ${e.message}",
            )
        }
    }

    /**
     * Generate direct Plotly visualization for simple cases
     */
    private suspend fun generateDirectPlotlyVisualization(
        recommendation: IntelligentAnalysisRecommendation,
        queryResults: List<Map<String, Any>>,
        originalQuery: String,
        apiKey: String,
    ): VisualizationData? {
        val chartType = recommendation.chartType ?: return null
        
        val systemPrompt =
            """
            You are a Plotly.js expert. Generate a complete Plotly.js configuration for the specified chart type.
            
            Your response must include:
            1. "data": Array of trace objects with proper structure for the chart type
            2. "layout": Layout configuration object with titles, axes, colors, etc.
            3. "title": Chart title
            4. "description": Brief description of what the chart shows
            
            Make the visualization:
            - Professional and business-appropriate
            - Accessible (good color contrast, clear labels)
            - Interactive where appropriate
            - Responsive to different screen sizes
            
            Chart type: $chartType
            
            For data transformation, ensure:
            - Proper data types (numbers as numbers, not strings)
            - Clear axis labels and titles
            - Appropriate colors and styling
            - Proper sorting/ordering when relevant
            """.trimIndent()
        
        val userPrompt =
            """
            Chart Type: $chartType
            Original Query: $originalQuery
            
            Data to visualize: ${objectMapper.writeValueAsString(queryResults)}
            
            Generate a complete Plotly.js configuration for this data.
            """.trimIndent()
        
        val messages =
            listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt),
            )
        
        val request =
            CreateCompletionRequest(
                model = "gpt-4.1",
                messages = messages,
                temperature = 0.1,
                response_format =
                    mapOf(
                        "type" to "json_schema",
                        "json_schema" to
                            mapOf(
                                "name" to "plotly_config",
                                "schema" to
                                    mapOf(
                                        "type" to "object",
                                        "properties" to
                                            mapOf(
                                                "data" to
                                                    mapOf(
                                                        "type" to "array",
                                                        "items" to mapOf("type" to "object"),
                                                    ),
                                                "layout" to mapOf("type" to "object"),
                                                "title" to mapOf("type" to "string"),
                                                "description" to mapOf("type" to "string"),
                                            ),
                                        "required" to listOf("data", "layout", "title", "description"),
                                    ),
                            ),
                    ),
            )
        
        return try {
            data class PlotlyConfig(
                val data: List<Map<String, Any>>,
                val layout: Map<String, Any>,
                val title: String,
                val description: String,
            )
            
            val response: PlotlyConfig = modelService.createCompletion(request, apiKey)
            
            VisualizationData(
                chartType = chartType,
                data = response.data,
                layout = response.layout,
                title = response.title,
                description = response.description,
            )
        } catch (e: Exception) {
            logger.error(e) { "Error generating direct Plotly visualization: ${e.message}" }
            null
        }
    }

    /**
     * Generate Python-based analysis and visualization
     */
    private suspend fun generatePythonAnalysisVisualization(
        recommendation: IntelligentAnalysisRecommendation,
        queryResults: List<Map<String, Any>>,
        originalQuery: String,
        apiKey: String,
    ): VisualizationData? {
        // Step 1: Generate Python analysis code
        val pythonCode =
            generatePythonAnalysisCode(recommendation, queryResults, originalQuery, apiKey)
                ?: return null
        
        // Step 2: Execute Python code
        val pythonResult = executePythonCode(pythonCode, queryResults)
        
        // Step 3: Generate Plotly visualization from analysis results
        return generatePlotlyFromPythonAnalysis(pythonResult, recommendation, originalQuery, apiKey)
    }

    /**
     * Generate Python analysis code using LLM
     */
    private suspend fun generatePythonAnalysisCode(
        recommendation: IntelligentAnalysisRecommendation,
        queryResults: List<Map<String, Any>>,
        originalQuery: String,
        apiKey: String,
    ): String? {
        val systemPrompt =
            """
            You are a Python data analysis expert. Generate Python code that performs sophisticated data analysis
            and prepares data for visualization.
            
            REQUIREMENTS:
            1. Use pandas, numpy, and other scientific libraries as needed
            2. The input data will be available as 'data' variable (list of dictionaries)
            3. Perform the analysis based on the recommendation and query context
            4. Store final results in variables that can be easily converted to Plotly
            5. Include print statements to show key insights
            6. Keep execution time reasonable (< 30 seconds)
            
            AVAILABLE LIBRARIES:
            - pandas (as pd)
            - numpy (as np)
            - scipy for statistical analysis
            - datetime for date handling
            - json for data manipulation
            
            ANALYSIS REQUIREMENTS: ${recommendation.pythonRequirements.joinToString(", ")}
            COMPLEXITY: ${recommendation.complexity}
            
            The code should be production-ready and handle edge cases gracefully.
            Do not include any plotly code - focus only on data analysis and preparation.
            """.trimIndent()
        
        val userPrompt =
            """
            Original Query: $originalQuery
            
            Data Structure: ${objectMapper.writeValueAsString(queryResults.take(3))}
            Total Records: ${queryResults.size}
            
            Recommendation: ${recommendation.reasoning}
            
            Generate Python code that analyzes this data and prepares it for visualization.
            Focus on extracting insights that would be valuable for the user's question.
            """.trimIndent()
        
        val messages =
            listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt),
            )
        
        val request =
            CreateCompletionRequest(
                model = "gpt-4.1",
                messages = messages,
                temperature = 0.1,
            )
        
        return try {
            val response = modelService.fetchCompletionPayload(request, apiKey)
            // Extract code from markdown if present
            val codePattern = Regex("```python\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
            val match = codePattern.find(response)
            match
                ?.groups
                ?.get(1)
                ?.value
                ?.trim() ?: response.trim()
        } catch (e: Exception) {
            logger.error(e) { "Error generating Python analysis code: ${e.message}" }
            null
        }
    }

    /**
     * Execute Python code using Piston service
     */
    private suspend fun executePythonCode(
        pythonCode: String,
        queryResults: List<Map<String, Any>>,
    ): PythonAnalysisResult {
        logger.info { "Executing Python code using Piston service" }
        return pistonCodeExecutorService.executePythonCode(pythonCode, queryResults)
    }

    /**
     * Generate Plotly visualization from Python analysis results
     */
    private suspend fun generatePlotlyFromPythonAnalysis(
        pythonResult: PythonAnalysisResult,
        recommendation: IntelligentAnalysisRecommendation,
        originalQuery: String,
        apiKey: String,
    ): VisualizationData? {
        if (!pythonResult.success) {
            logger.warn { "Python analysis failed, cannot generate visualization: ${pythonResult.error}" }
            return null
        }
        
        val chartType = recommendation.chartType ?: ChartType.BAR
        
        val systemPrompt =
            """
            You are a Plotly.js expert. Based on the Python analysis results, generate a complete Plotly.js 
            configuration that visualizes the insights from the analysis.
            
            Your response must include:
            1. "data": Array of trace objects with proper structure for the chart type
            2. "layout": Layout configuration object with titles, axes, colors, etc.
            3. "title": Chart title
            4. "description": Brief description of what the chart shows
            
            Chart type to use: $chartType
            
            Make the visualization:
            - Professional and business-appropriate
            - Accessible (good color contrast, clear labels)
            - Interactive where appropriate
            - Responsive to different screen sizes
            - Clearly communicate the insights from the Python analysis
            """.trimIndent()
        
        val userPrompt =
            """
            Original Query: $originalQuery
            
            Python Analysis Results:
            ${pythonResult.output}
            
            Python Code That Was Executed:
            ${pythonResult.code}
            
            Based on this analysis, generate a Plotly.js configuration that effectively visualizes the key insights.
            Extract the relevant data points and metrics from the analysis output to create the visualization.
            """.trimIndent()
        
        val messages =
            listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt),
            )
        
        val request =
            CreateCompletionRequest(
                model = "gpt-4.1",
                messages = messages,
                temperature = 0.1,
                response_format =
                    mapOf(
                        "type" to "json_schema",
                        "json_schema" to
                            mapOf(
                                "name" to "plotly_config",
                                "schema" to
                                    mapOf(
                                        "type" to "object",
                                        "properties" to
                                            mapOf(
                                                "data" to
                                                    mapOf(
                                                        "type" to "array",
                                                        "items" to mapOf("type" to "object"),
                                                    ),
                                                "layout" to mapOf("type" to "object"),
                                                "title" to mapOf("type" to "string"),
                                                "description" to mapOf("type" to "string"),
                                            ),
                                        "required" to listOf("data", "layout", "title", "description"),
                                    ),
                            ),
                    ),
            )
        
        return try {
            data class PlotlyConfig(
                val data: List<Map<String, Any>>,
                val layout: Map<String, Any>,
                val title: String,
                val description: String,
            )
            
            val response: PlotlyConfig = modelService.createCompletion(request, apiKey)
            
            VisualizationData(
                chartType = chartType,
                data = response.data,
                layout = response.layout,
                title = response.title,
                description = response.description,
                pythonAnalysis = pythonResult,
            )
        } catch (e: Exception) {
            logger.error(e) { "Error generating Plotly from Python analysis: ${e.message}" }
            null
        }
    }
} 
