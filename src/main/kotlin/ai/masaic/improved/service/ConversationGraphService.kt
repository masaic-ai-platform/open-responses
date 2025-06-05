package ai.masaic.improved.service

import ai.masaic.improved.model.Conversation
import ai.masaic.improved.repository.ConversationRepository
import mu.KotlinLogging
import org.neo4j.driver.Driver
import org.neo4j.driver.SessionConfig
import org.springframework.stereotype.Service

/**
 * Service for managing conversation nodes in the graph database.
 * 
 * This service handles the creation of hierarchical tree structures based on
 * conversation labels and adds conversations as leaf nodes in the graph.
 */
@Service
class ConversationGraphService(
    private val memgraphDriver: Driver,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Adds a conversation to the graph database.
     * Creates a hierarchical tree structure from label paths and adds the conversation as a leaf node.
     *
     * @param conversation The conversation to add to the graph
     */
    suspend fun addConversationToGraph(conversation: Conversation) {
        try {
            memgraphDriver.session(SessionConfig.forDatabase("memgraph")).use { session ->
                // First, create or update the conversation node
                createConversationNode(session, conversation)
                
                // Process each label path to create the tree structure
                conversation.labels.forEach { label ->
                    createPathAndConnectConversation(session, label.path, conversation.id)
                }
            }
            logger.info { "Successfully added conversation ${conversation.id} to graph" }
        } catch (e: Exception) {
            logger.error(e) { "Error adding conversation ${conversation.id} to graph: ${e.message}" }
            throw e
        }
    }

    /**
     * Batch migrate existing conversations to the graph database.
     *
     * @param conversationRepository The repository to fetch conversations from
     * @param batchSize Number of conversations to process in each batch
     * @param maxConversations Maximum number of conversations to migrate (null for all)
     * @return Migration result with statistics
     */
    suspend fun batchMigrateConversations(
        conversationRepository: ConversationRepository,
        batchSize: Int = 100,
        maxConversations: Int? = null,
    ): BatchMigrationResult {
        logger.info { "Starting batch migration of conversations to graph database" }
        
        val result = BatchMigrationResult()
        var processedCount = 0
        var offset = 0
        
        try {
            while (maxConversations == null || processedCount < maxConversations) {
                // Fetch conversations in batches
                val conversations = fetchConversationBatch(conversationRepository, offset, batchSize)
                
                if (conversations.isEmpty()) {
                    logger.info { "No more conversations to process" }
                    break
                }
                
                logger.info { "Processing batch of ${conversations.size} conversations (offset: $offset)" }
                
                // Process each conversation in the batch
                for (conversation in conversations) {
                    try {
                        if (conversation.labels.isNotEmpty()) {
                            // Check if conversation already exists in graph
                            if (!conversationExistsInGraph(conversation.id)) {
                                addConversationToGraph(conversation)
                                result.successCount++
                            } else {
                                result.skippedCount++
                                logger.debug { "Conversation ${conversation.id} already exists in graph, skipping" }
                            }
                        } else {
                            result.skippedCount++
                            logger.debug { "Conversation ${conversation.id} has no labels, skipping" }
                        }
                    } catch (e: Exception) {
                        result.errorCount++
                        result.errors.add("Error migrating conversation ${conversation.id}: ${e.message}")
                        logger.error(e) { "Error migrating conversation ${conversation.id}" }
                    }
                    
                    processedCount++
                    if (maxConversations != null && processedCount >= maxConversations) {
                        break
                    }
                }
                
                offset += conversations.size
                result.totalProcessed = processedCount
                
                // Log progress
                logger.info { "Migration progress: $processedCount conversations processed, ${result.successCount} successful, ${result.errorCount} errors, ${result.skippedCount} skipped" }
            }
            
            logger.info { "Batch migration completed. Total: $processedCount, Success: ${result.successCount}, Errors: ${result.errorCount}, Skipped: ${result.skippedCount}" }
        } catch (e: Exception) {
            logger.error(e) { "Fatal error during batch migration: ${e.message}" }
            result.errors.add("Fatal migration error: ${e.message}")
        }
        
        return result
    }

    /**
     * Check if a conversation already exists in the graph.
     */
    private suspend fun conversationExistsInGraph(conversationId: String): Boolean =
        try {
            memgraphDriver.session(SessionConfig.forDatabase("memgraph")).use { session ->
                val query = "MATCH (c:Conversation {id: \$conversationId}) RETURN count(c) as count"
                val result = session.run(query, mapOf("conversationId" to conversationId))
                val count = result.single().get("count").asInt()
                count > 0
            }
        } catch (e: Exception) {
            logger.error(e) { "Error checking if conversation $conversationId exists in graph" }
            false
        }

    /**
     * Fetch a batch of conversations from the repository.
     */
    private suspend fun fetchConversationBatch(
        conversationRepository: ConversationRepository,
        offset: Int,
        batchSize: Int,
    ): List<Conversation> =
        try {
            // Get all conversations and apply manual pagination
            val allConversations = conversationRepository.listConversations()
            allConversations.drop(offset).take(batchSize)
        } catch (e: Exception) {
            logger.error(e) { "Error fetching conversation batch at offset $offset" }
            emptyList()
        }

    /**
     * Get all nodes as a flat list (simpler alternative to tree structure).
     *
     * @return List of all nodes with their conversation counts
     */
    suspend fun getAllNodes(): List<NodeInfo> =
        try {
            memgraphDriver.session(SessionConfig.forDatabase("memgraph")).use { session ->
                val query =
                    """
                    MATCH (p:PathNode)
                    OPTIONAL MATCH (p)-[:CONTAINS]->(c:Conversation)
                    RETURN p.name as nodeName, count(c) as conversationCount
                    ORDER BY nodeName
                    """.trimIndent()
                
                val result = session.run(query)
                result.list { record ->
                    NodeInfo(
                        name = record.get("nodeName").asString(),
                        conversationCount = record.get("conversationCount").asInt(),
                    )
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error getting all nodes: ${e.message}" }
            emptyList()
        }

    /**
     * Get migration statistics for monitoring.
     */
    suspend fun getMigrationStatistics(): MigrationStatistics =
        try {
            memgraphDriver.session(SessionConfig.forDatabase("memgraph")).use { session ->
                val conversationCountQuery = "MATCH (c:Conversation) RETURN count(c) as conversationCount"
                val pathNodeCountQuery = "MATCH (p:PathNode) RETURN count(p) as pathNodeCount"
                val relationshipCountQuery = "MATCH ()-[r]->() RETURN count(r) as relationshipCount"
                
                val conversationCount =
                    session
                        .run(conversationCountQuery)
                        .single()
                        .get("conversationCount")
                        .asInt()
                val pathNodeCount =
                    session
                        .run(pathNodeCountQuery)
                        .single()
                        .get("pathNodeCount")
                        .asInt()
                val relationshipCount =
                    session
                        .run(relationshipCountQuery)
                        .single()
                        .get("relationshipCount")
                        .asInt()
                
                MigrationStatistics(
                    conversationsInGraph = conversationCount,
                    pathNodesInGraph = pathNodeCount,
                    totalRelationships = relationshipCount,
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Error getting migration statistics: ${e.message}" }
            MigrationStatistics()
        }

    /**
     * Creates or updates a conversation node in the graph.
     */
    private fun createConversationNode(
        session: org.neo4j.driver.Session,
        conversation: Conversation,
    ) {
        val query =
            """
            MERGE (c:Conversation {id: ${'$'}conversationId})
            SET c.createdAt = ${'$'}createdAt,
                c.summary = ${'$'}summary,
                c.resolved = ${'$'}resolved,
                c.nps = ${'$'}nps,
                c.version = ${'$'}version,
                c.classification = ${'$'}classification,
                c.messageCount = ${'$'}messageCount
            """.trimIndent()

        session.run(
            query,
            mapOf(
                "conversationId" to conversation.id,
                "createdAt" to conversation.createdAt.toString(),
                "summary" to conversation.summary,
                "resolved" to conversation.resolved,
                "nps" to conversation.nps,
                "version" to conversation.version,
                "classification" to conversation.classification?.name,
                "messageCount" to conversation.messages.size,
            ),
        )
    }

    /**
     * Creates a hierarchical path structure and connects the conversation as a leaf node.
     * IMPROVED: Uses proper graph hierarchy instead of path-based approach.
     * For example, path "domain/tech/api" creates individual nodes: domain -[:HAS_CHILD]-> tech -[:HAS_CHILD]-> api
     */
    private fun createPathAndConnectConversation(
        session: org.neo4j.driver.Session,
        path: String,
        conversationId: String,
    ) {
        val pathSegments = path.split("/").filter { it.isNotBlank() }
        
        if (pathSegments.isEmpty()) {
            logger.warn { "Empty path segments for conversation $conversationId" }
            return
        }

        // Create individual nodes for each segment (not path-based)
        pathSegments.forEach { segment ->
            val createNodeQuery =
                """
                MERGE (p:PathNode {name: ${'$'}segmentName})
                """.trimIndent()
            
            session.run(createNodeQuery, mapOf("segmentName" to segment))
        }
        
        // Create parent-child relationships between segments
        for (i in 1 until pathSegments.size) {
            val parentSegment = pathSegments[i - 1]
            val childSegment = pathSegments[i]
            
            val relationshipQuery =
                """
                MATCH (parent:PathNode {name: ${'$'}parentName})
                MATCH (child:PathNode {name: ${'$'}childName})
                MERGE (parent)-[:HAS_CHILD]->(child)
                """.trimIndent()
            
            session.run(
                relationshipQuery,
                mapOf(
                    "parentName" to parentSegment,
                    "childName" to childSegment,
                ),
            )
        }

        // Connect conversation to the leaf node (not path-based)
        val leafSegment = pathSegments.last()
        val connectConversationQuery =
            """
            MATCH (p:PathNode {name: ${'$'}leafName})
            MATCH (c:Conversation {id: ${'$'}conversationId})
            MERGE (p)-[:CONTAINS]->(c)
            """.trimIndent()
        
        session.run(
            connectConversationQuery,
            mapOf(
                "leafName" to leafSegment,
                "conversationId" to conversationId,
            ),
        )
    }

    /**
     * Removes a conversation from the graph database.
     *
     * @param conversationId The ID of the conversation to remove
     */
    suspend fun removeConversationFromGraph(conversationId: String) {
        try {
            memgraphDriver.session(SessionConfig.forDatabase("memgraph")).use { session ->
                val query =
                    """
                    MATCH (c:Conversation {id: ${'$'}conversationId})
                    DETACH DELETE c
                    """.trimIndent()
                
                session.run(query, mapOf("conversationId" to conversationId))
            }
            logger.info { "Successfully removed conversation $conversationId from graph" }
        } catch (e: Exception) {
            logger.error(e) { "Error removing conversation $conversationId from graph: ${e.message}" }
            throw e
        }
    }

    /**
     * Updates a conversation in the graph database.
     *
     * @param conversation The updated conversation
     */
    suspend fun updateConversationInGraph(conversation: Conversation) {
        try {
            // Remove the old conversation and add the updated one
            removeConversationFromGraph(conversation.id)
            addConversationToGraph(conversation)
            logger.info { "Successfully updated conversation ${conversation.id} in graph" }
        } catch (e: Exception) {
            logger.error(e) { "Error updating conversation ${conversation.id} in graph: ${e.message}" }
            throw e
        }
    }

    /**
     * Gets all conversations under a specific node using proper graph traversal.
     * IMPROVED: Uses graph traversal instead of string prefix matching.
     *
     * @param nodeName The node name to search under (e.g., "domain")
     * @return List of conversation IDs under the node
     */
    suspend fun getConversationsUnderNode(nodeName: String): List<String> =
        try {
            memgraphDriver.session(SessionConfig.forDatabase("memgraph")).use { session ->
                val query =
                    """
                    MATCH (root:PathNode {name: ${'$'}nodeName})
                    MATCH (root)-[:HAS_CHILD*0..]->(descendant:PathNode)
                    MATCH (descendant)-[:CONTAINS]->(c:Conversation)
                    RETURN DISTINCT c.id as conversationId
                    """.trimIndent()
                
                val result = session.run(query, mapOf("nodeName" to nodeName))
                result.list { record -> record.get("conversationId").asString() }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error getting conversations under node $nodeName: ${e.message}" }
            emptyList()
        }

    /**
     * Get all nodes and their descendants using proper graph traversal.
     *
     * @param nodeName The node name to search under
     * @return List of node information including descendants
     */
    suspend fun getNodesUnderNode(nodeName: String): List<NodeInfo> =
        try {
            memgraphDriver.session(SessionConfig.forDatabase("memgraph")).use { session ->
                val query =
                    """
                    MATCH (root:PathNode {name: ${'$'}nodeName})
                    MATCH (root)-[:HAS_CHILD*0..]->(descendant:PathNode)
                    OPTIONAL MATCH (descendant)-[:CONTAINS]->(c:Conversation)
                    RETURN descendant.name as nodeName, count(c) as conversationCount
                    ORDER BY descendant.name
                    """.trimIndent()
                
                val result = session.run(query, mapOf("nodeName" to nodeName))
                result.list { record -> 
                    NodeInfo(
                        name = record.get("nodeName").asString(),
                        conversationCount = record.get("conversationCount").asInt(),
                    )
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error getting nodes under node $nodeName: ${e.message}" }
            emptyList()
        }

    /**
     * Get the full tree structure of all nodes using graph hierarchy.
     *
     * @return List of root nodes with their complete hierarchy
     */
    suspend fun getFullNodeTree(): List<NodeTreeNode> =
        try {
            memgraphDriver.session(SessionConfig.forDatabase("memgraph")).use { session ->
                // Get all nodes with their conversation counts and relationships
                val nodeQuery =
                    """
                    MATCH (p:PathNode)
                    OPTIONAL MATCH (p)-[:CONTAINS]->(c:Conversation)
                    OPTIONAL MATCH (parent:PathNode)-[:HAS_CHILD]->(p)
                    WITH p.name as nodeName, count(DISTINCT c) as directConversationCount, collect(DISTINCT parent.name) as parents
                    RETURN nodeName, directConversationCount, parents
                    ORDER BY nodeName
                    """.trimIndent()
                
                val nodeResult = session.run(nodeQuery)
                val allNodes =
                    nodeResult.list { record ->
                        val parentsList = record.get("parents").asList { it.asString() }
                        NodeInfo(
                            name = record.get("nodeName").asString(),
                            conversationCount = record.get("directConversationCount").asInt(),
                        ) to parentsList.filter { it.isNotEmpty() } // Filter out empty parent names
                    }
                
                // Log node information for debugging
                logger.debug { "Retrieved ${allNodes.size} nodes from graph" }
                allNodes.forEach { (nodeInfo, parents) ->
                    logger.debug { "Node: ${nodeInfo.name}, conversations: ${nodeInfo.conversationCount}, parents: $parents" }
                }
                
                // Build the tree structure with error handling
                try {
                    buildNodeTree(allNodes)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to build node tree, falling back to flat structure" }
                    // Fallback: return flat structure as individual root nodes
                    allNodes
                        .map { (nodeInfo, _) ->
                            NodeTreeNode(
                                name = nodeInfo.name,
                                conversationCount = nodeInfo.conversationCount,
                            )
                        }.sortedBy { it.name }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error getting full node tree: ${e.message}" }
            emptyList()
        }

    /**
     * Build a tree structure from nodes and their parent relationships.
     */
    private suspend fun buildNodeTree(nodesWithParents: List<Pair<NodeInfo, List<String>>>): List<NodeTreeNode> {
        val nodeMap = mutableMapOf<String, NodeTreeNode>()
        val rootNodes = mutableListOf<NodeTreeNode>()
        
        // Create all nodes first
        nodesWithParents.forEach { (nodeInfo, parents) ->
            val node =
                NodeTreeNode(
                    name = nodeInfo.name,
                    conversationCount = nodeInfo.conversationCount,
                )
            nodeMap[nodeInfo.name] = node
        }
        
        // Build parent-child relationships with cycle detection
        nodesWithParents.forEach { (nodeInfo, parents) ->
            val currentNode = nodeMap[nodeInfo.name]!!
            
            if (parents.isEmpty()) {
                // This is a root node
                rootNodes.add(currentNode)
            } else {
                // Add to parent nodes, but avoid self-references and cycles
                parents.forEach { parentName ->
                    if (parentName != nodeInfo.name) { // Avoid self-reference
                        val parentNode = nodeMap[parentName]
                        parentNode?.children?.add(currentNode)
                    }
                }
            }
        }

        // Calculate cumulative conversation counts and sort with cycle detection
        fun calculateCumulativeAndSort(
            node: NodeTreeNode,
            visited: MutableSet<String> = mutableSetOf(),
        ): Int {
            // Cycle detection - if we've already visited this node, return its current count
            if (visited.contains(node.name)) {
                logger.warn { "Detected cycle in graph at node: ${node.name}, skipping recursive calculation" }
                return node.conversationCount
            }
            
            // Mark this node as visited
            visited.add(node.name)
            
            try {
                // Sort children first
                node.children.sortBy { it.name }
                
                // Process children recursively and get their cumulative counts
                val childrenTotal =
                    node.children.sumOf { child ->
                        calculateCumulativeAndSort(child, visited.toMutableSet()) // Pass a copy of visited set
                    }
                
                // Update this node's count to include its own conversations plus all children
                val originalCount = node.conversationCount
                node.conversationCount = originalCount + childrenTotal
                
                return node.conversationCount
            } finally {
                // Remove from visited set when we're done with this branch
                visited.remove(node.name)
            }
        }
        
        // Calculate cumulative counts for all root nodes
        rootNodes.forEach { rootNode ->
            try {
                calculateCumulativeAndSort(rootNode)
            } catch (e: Exception) {
                logger.error(e) { "Error calculating cumulative counts for root node: ${rootNode.name}" }
                // Continue with other root nodes even if one fails
            }
        }
        
        return rootNodes.sortedBy { it.name }
    }
}

/**
 * Result of a batch migration operation.
 */
data class BatchMigrationResult(
    var totalProcessed: Int = 0,
    var successCount: Int = 0,
    var errorCount: Int = 0,
    var skippedCount: Int = 0,
    val errors: MutableList<String> = mutableListOf(),
) {
    val hasErrors: Boolean get() = errorCount > 0
    val successRate: Double get() = if (totalProcessed > 0) (successCount.toDouble() / totalProcessed) * 100 else 0.0
}

/**
 * Migration statistics for monitoring.
 */
data class MigrationStatistics(
    val conversationsInGraph: Int = 0,
    val pathNodesInGraph: Int = 0,
    val totalRelationships: Int = 0,
)

/**
 * Information about a graph node.
 */
data class NodeInfo(
    val name: String,
    val conversationCount: Int,
)

/**
 * Represents a node in the graph tree structure.
 */
data class NodeTreeNode(
    val name: String,
    var conversationCount: Int = 0,
    val children: MutableList<NodeTreeNode> = mutableListOf(),
) 
