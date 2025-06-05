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
 * Service for generating visualization recommendations and Plotly.js chart data.
 * 
 * This service:
 * 1. Analyzes query results to determine if visualization would be beneficial
 * 2. Recommends appropriate chart types based on data characteristics
 * 3. Generates Plotly.js compatible data and layout configurations
 * 4. Transforms data into optimal format for visualization
 */
@Service
class VisualizationService(
    private val modelService: ModelService
) {
    private val logger = KotlinLogging.logger {}
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    
    /**
     * Analyze query results and generate visualization recommendation
     */
    suspend fun analyzeForVisualization(
        originalQuery: String,
        cypherQuery: String,
        queryResults: List<Map<String, Any>>,
        apiKey: String
    ): VisualizationRecommendation {
        logger.debug { "Analyzing query for visualization potential: $originalQuery" }
        logger.info { "VisualizationService.analyzeForVisualization called with ${queryResults.size} results" }
        
        val systemPrompt = """
            You are a data visualization expert. Analyze the provided query and results to determine if visualization would be beneficial and recommend the best chart type.
            
            Consider these factors:
            1. Data type and structure (numerical, categorical, time-series, etc.)
            2. Number of data points and dimensions
            3. The nature of the business question being asked
            4. Whether visualization adds value over textual representation
            
            Chart types available: BAR, LINE, PIE, SCATTER, HISTOGRAM, HEATMAP, TREEMAP, FUNNEL, AREA
            
            Guidelines:
            - BAR: Good for comparing categories, counts, or aggregated values
            - LINE: Best for time-series data or trends over continuous variables
            - PIE: Use sparingly, only for parts-of-whole with <7 categories
            - SCATTER: For showing relationships between two continuous variables
            - HISTOGRAM: For distribution of a single continuous variable
            - HEATMAP: For showing patterns in 2D data or correlation matrices
            - TREEMAP: For hierarchical data with clear parent-child relationships where you can calculate parent values as sum of children. ONLY use if data contains explicit "parent" and "child" columns or clear hierarchical structure.
            - FUNNEL: For sequential processes or conversion rates
            - AREA: For showing cumulative values or stacked categories over time
            
            TREEMAP SPECIFIC REQUIREMENTS:
            - Data must have clear parent-child hierarchy (like "parent" and "child" columns)
            - Values must be summable (parent = sum of children)
            - Use BAR charts instead if data is just categorical without hierarchy
        """.trimIndent()
        
        val userPrompt = """
            Original Question: $originalQuery
            
            Cypher Query: $cypherQuery
            
            Query Results: ${objectMapper.writeValueAsString(queryResults)}
            
            Number of result rows: ${queryResults.size}
            
            Please analyze this data and provide a visualization recommendation.
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
                    "name" to "visualization_recommendation",
                    "schema" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "shouldVisualize" to mapOf("type" to "boolean"),
                            "chartType" to mapOf(
                                "type" to "string",
                                "enum" to ChartType.values().map { it.name }
                            ),
                            "title" to mapOf("type" to "string"),
                            "description" to mapOf("type" to "string"),
                            "reasoning" to mapOf("type" to "string"),
                            "dataTransformation" to mapOf("type" to "string")
                        ),
                        "required" to listOf("shouldVisualize", "reasoning")
                    )
                )
            )
        )
        
        try {
            logger.info { "Making LLM call for visualization recommendation..." }
            val recommendation: VisualizationRecommendation = modelService.createCompletion(request, apiKey)
            logger.info { "LLM recommendation received: shouldVisualize=${recommendation.shouldVisualize}, chartType=${recommendation.chartType}, reasoning=${recommendation.reasoning}" }
            return recommendation
        } catch (e: Exception) {
            logger.error(e) { "Error analyzing data for visualization: ${e.message}" }
            return VisualizationRecommendation(
                shouldVisualize = false,
                chartType = null,
                title = null,
                description = null,
                reasoning = "Error occurred during analysis: ${e.message}"
            )
        }
    }
    
    /**
     * Generate Plotly.js visualization data based on recommendation
     */
    suspend fun generateVisualization(
        recommendation: VisualizationRecommendation,
        queryResults: List<Map<String, Any>>,
        originalQuery: String,
        apiKey: String
    ): VisualizationData? {
        if (!recommendation.shouldVisualize || recommendation.chartType == null) {
            logger.info { "Skipping visualization generation: shouldVisualize=${recommendation.shouldVisualize}, chartType=${recommendation.chartType}" }
            return null
        }
        
        logger.debug { "Generating ${recommendation.chartType} visualization" }
        logger.info { "VisualizationService.generateVisualization called for ${recommendation.chartType} chart" }
        
        val systemPrompt = """
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
            
            Chart type: ${recommendation.chartType}
            
            CRITICAL TREEMAP REQUIREMENTS (if chart type is TREEMAP):
            For hierarchical treemap data, you MUST ensure:
            1. Parent node values EQUAL the sum of their children's values
            2. If a parent has both its own data AND children, its value = own data + sum of children
            3. Use these arrays in your treemap trace:
               - "labels": All node names (parents and children)
               - "parents": Parent name for each node (empty string "" for root)
               - "values": Numeric values where parent = sum of children
               - "type": "treemap"
            4. Example structure:
               labels: ["Total", "Category A", "Category B", "Sub A1", "Sub A2", "Sub B1"]
               parents: ["", "Total", "Total", "Category A", "Category A", "Category B"]
               values: [100, 60, 40, 30, 30, 40]
               (where 100 = 60+40, 60 = 30+30, 40 = 40)
            
            For data transformation, ensure:
            - Proper data types (numbers as numbers, not strings)
            - Clear axis labels and titles
            - Appropriate colors and styling
            - Proper sorting/ordering when relevant
            - TREEMAP: Parent values must equal sum of children to avoid Plotly errors
        """.trimIndent()
        
        val userPrompt = """
            Chart Type: ${recommendation.chartType}
            Title: ${recommendation.title ?: "Data Visualization"}
            Description: ${recommendation.description ?: ""}
            
            Data to visualize: ${objectMapper.writeValueAsString(queryResults)}
            
            Original query context: $originalQuery
            
            Data transformation instructions: ${recommendation.dataTransformation ?: "Use data as-is"}
            
            Generate a complete Plotly.js configuration.
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
                    "name" to "plotly_config",
                    "schema" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "data" to mapOf(
                                "type" to "array",
                                "items" to mapOf("type" to "object")
                            ),
                            "layout" to mapOf("type" to "object"),
                            "title" to mapOf("type" to "string"),
                            "description" to mapOf("type" to "string")
                        ),
                        "required" to listOf("data", "layout", "title", "description")
                    )
                )
            )
        )
        
        return try {
            data class PlotlyConfig(
                val data: List<Map<String, Any>>,
                val layout: Map<String, Any>,
                val title: String,
                val description: String
            )
            
            logger.info { "Making LLM call for Plotly.js configuration..." }
            val response: PlotlyConfig = modelService.createCompletion(request, apiKey)
            logger.info { "LLM Plotly config received: title='${response.title}', data.size=${response.data.size}" }
            
            val visualizationData = VisualizationData(
                chartType = recommendation.chartType,
                data = response.data,
                layout = response.layout,
                title = response.title,
                description = response.description
            )
            
            logger.info { "Successfully created VisualizationData: ${visualizationData.chartType} chart with title '${visualizationData.title}'" }
            visualizationData
        } catch (e: Exception) {
            logger.error(e) { "Error generating Plotly.js configuration: ${e.message}" }
            null
        }
    }
    
    /**
     * Quick heuristics-based chart type detection (fallback)
     */
    fun detectChartTypeHeuristics(queryResults: List<Map<String, Any>>): ChartType? {
        if (queryResults.isEmpty()) return null
        
        val firstRow = queryResults.first()
        val keys = firstRow.keys.toList()
        
        return when {
            // Time series detection
            keys.any { it.contains("date", ignoreCase = true) || it.contains("time", ignoreCase = true) } -> ChartType.LINE
            
            // Count/aggregate detection  
            keys.any { it.contains("count", ignoreCase = true) || it.contains("total", ignoreCase = true) } -> {
                if (queryResults.size <= 10) ChartType.PIE else ChartType.BAR
            }
            
            // Single numerical column -> histogram
            keys.size == 1 && firstRow.values.first() is Number -> ChartType.HISTOGRAM
            
            // Two numerical columns -> scatter
            keys.size == 2 && firstRow.values.all { it is Number } -> ChartType.SCATTER
            
            // Default to bar chart for categorical data
            else -> ChartType.BAR
        }
    }
} 
