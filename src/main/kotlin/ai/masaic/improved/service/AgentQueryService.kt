package ai.masaic.improved.service

import ai.masaic.improved.ModelService
import ai.masaic.improved.createCompletion
import ai.masaic.improved.model.*
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import org.neo4j.driver.Driver
import org.neo4j.driver.SessionConfig
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for handling agent queries that generate Cypher queries for business KPI insights.
 * 
 * This service:
 * 1. Takes user questions about business KPIs
 * 2. Provides conversation tree context to LLM
 * 3. Generates Cypher queries using LLM
 * 4. Executes the Cypher queries against the graph database
 * 5. Feeds results back to LLM for natural language response
 * 6. Handles query errors with retry logic
 */
@Service
class AgentQueryService(
    private val modelService: ModelService,
    private val graphService: ConversationGraphService,
    private val memgraphDriver: Driver,
) {
    private val logger = KotlinLogging.logger {}
    private val objectMapper = jacksonObjectMapper()
    
    // Store active agent conversations in memory
    private val activeConversations = ConcurrentHashMap<String, AgentConversation>()

    /**
     * Process an agent query and return a natural language response.
     *
     * @param request The agent query request
     * @param apiKey The API key for LLM calls
     * @return The agent query response with natural language answer
     */
    suspend fun processQuery(
        request: AgentQueryRequest,
        apiKey: String,
    ): AgentQueryResponse {
        logger.info { "Processing agent query: ${request.query}" }
        
        try {
            // Get or create conversation
            val conversation = getOrCreateConversation(request.conversationId)
            
            // Add user message to conversation
            conversation.addMessage(AgentMessage(AgentRole.USER, request.query))
            
            // Get graph context if requested
            val graphContext =
                if (request.includeContext) {
                    buildGraphContext()
                } else {
                    null
                }
            
            // Generate and execute query with retry logic
            var retryCount = 0
            var lastError: String? = null
            
            while (retryCount <= request.maxRetries) {
                try {
                    // Generate Cypher query
                    val cypherResponse =
                        generateCypherQuery(
                            request.query, 
                            conversation, 
                            graphContext, 
                            lastError,
                            apiKey,
                        )
                    
                    // Execute Cypher query
                    val executionResult = executeCypherQuery(cypherResponse.cypherQuery)
                    
                    if (executionResult.success) {
                        // Generate natural language response
                        val naturalResponse =
                            generateNaturalLanguageResponse(
                                request.query,
                                cypherResponse.cypherQuery,
                                executionResult.results ?: emptyList(),
                                graphContext,
                                conversation,
                                apiKey,
                            )
                        
                        // Add assistant message to conversation
                        conversation.addMessage(
                            AgentMessage(
                                AgentRole.ASSISTANT, 
                                naturalResponse,
                                metadata =
                                    mapOf(
                                        "cypherQuery" to cypherResponse.cypherQuery,
                                        "queryResults" to (executionResult.results ?: emptyList()),
                                    ),
                            ),
                        )
                        
                        return AgentQueryResponse(
                            conversationId = conversation.id,
                            naturalLanguageResponse = naturalResponse,
                            cypherQuery = cypherResponse.cypherQuery,
                            queryResults = executionResult.results,
                            retryCount = retryCount,
                        )
                    } else {
                        // Query failed, prepare for retry
                        lastError = executionResult.error
                        retryCount++
                        logger.warn { "Cypher query failed (attempt $retryCount): ${executionResult.error}" }
                    }
                } catch (e: Exception) {
                    lastError = "LLM generation error: ${e.message}"
                    retryCount++
                    logger.error(e) { "Error generating Cypher query (attempt $retryCount)" }
                }
            }
            
            // All retries exhausted
            val errorMessage = "Failed to generate valid Cypher query after ${request.maxRetries + 1} attempts. Last error: $lastError"
            logger.error { errorMessage }
            
            return AgentQueryResponse(
                conversationId = conversation.id,
                naturalLanguageResponse = "I apologize, but I'm having trouble generating a valid query for your request. Please try rephrasing your question or asking something more specific about the conversation data.",
                retryCount = retryCount - 1,
                error = errorMessage,
            )
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error processing agent query: ${e.message}" }
            
            return AgentQueryResponse(
                conversationId = request.conversationId ?: UUID.randomUUID().toString(),
                naturalLanguageResponse = "I encountered an unexpected error while processing your request. Please try again.",
                error = "Unexpected error: ${e.message}",
            )
        }
    }

    /**
     * Get or create an agent conversation.
     */
    private fun getOrCreateConversation(conversationId: String?): AgentConversation =
        if (conversationId != null && activeConversations.containsKey(conversationId)) {
            activeConversations[conversationId]!!.copy(lastUpdated = Instant.now())
        } else {
            val newId = conversationId ?: "agent_conv_${UUID.randomUUID().toString().replace("-", "").substring(0, 10)}"
            val conversation = AgentConversation(id = newId)
            activeConversations[newId] = conversation
            conversation
        }

    /**
     * Build graph context for the LLM.
     */
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

    /**
     * Recursively get all nodes from a node tree.
     */
    private fun getAllNodes(node: NodeTreeNode): List<String> {
        val nodes = mutableListOf(node.name)
        node.children.forEach { child ->
            nodes.addAll(getAllNodes(child))
        }
        return nodes
    }

    /**
     * Build a summary of the node tree structure.
     */
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

    /**
     * Generate a Cypher query using the LLM.
     */
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
                model = "gpt-4.1",
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

    /**
     * Build the system prompt for Cypher generation.
     */
    private fun buildCypherGenerationPrompt(
        graphContext: GraphContext?,
        previousError: String?,
    ): String {
        val prompt = StringBuilder()
        val currentDate =
            java.time.LocalDate
                .now()
                .toString() // YYYY-MM-DD format
        val currentDateTime =
            java.time.Instant
                .now()
                .toString() // ISO format
        
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

    /**
     * Build conversation history for context.
     */
    private fun buildConversationHistory(conversation: AgentConversation): List<Map<String, Any>> =
        conversation.messages.takeLast(10).map { message ->
            mapOf(
                "role" to message.role.name.lowercase(),
                "content" to message.content,
            )
        }

    /**
     * Execute a Cypher query against the graph database.
     */
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

    /**
     * Generate a natural language response based on query results.
     */
    private suspend fun generateNaturalLanguageResponse(
        originalQuery: String,
        cypherQuery: String,
        queryResults: List<Map<String, Any>>,
        graphContext: GraphContext?,
        conversation: AgentConversation,
        apiKey: String,
    ): String {
        val systemPrompt =
            """
            You are a business analyst expert at interpreting data query results and providing clear, actionable insights.
            
            Your task is to analyze the query results and provide a natural language response that:
            1. Directly answers the user's question
            2. Highlights key insights and patterns
            3. Provides business context and implications
            4. Suggests follow-up questions or actions when relevant
            5. Uses clear, non-technical language
            
            Format your response as a conversational analysis, not just raw data presentation.
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
                model = "gpt-4.1",
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
