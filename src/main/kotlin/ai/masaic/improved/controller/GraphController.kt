package ai.masaic.improved.controller

import ai.masaic.improved.repository.ConversationRepository
import ai.masaic.improved.service.BatchMigrationResult
import ai.masaic.improved.service.ConversationGraphService
import ai.masaic.improved.service.MigrationStatistics
import ai.masaic.improved.service.NodeInfo
import ai.masaic.improved.service.NodeTreeNode
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Controller for testing and managing conversation graph operations.
 */
@RestController
@RequestMapping("/api/v1/graph")
class GraphController(
    private val graphService: ConversationGraphService,
    @Qualifier("baseConversationRepository") private val conversationRepository: ConversationRepository,
) {
    /**
     * Get the full tree structure of all nodes using graph hierarchy.
     *
     * @return Complete hierarchical tree structure of all nodes
     */
    @GetMapping("/tree")
    suspend fun getFullNodeTree(): ResponseEntity<List<NodeTreeNode>> {
        val tree = graphService.getFullNodeTree()
        return ResponseEntity.ok(tree)
    }

    /**
     * Batch migrate existing conversations to the graph database.
     *
     * @param batchSize Number of conversations to process in each batch (default: 100)
     * @param maxConversations Maximum number of conversations to migrate (optional)
     * @return Migration result with statistics
     */
    @PostMapping("/migrate")
    suspend fun batchMigrateConversations(
        @RequestParam(defaultValue = "100") batchSize: Int,
        @RequestParam(required = false) maxConversations: Int?,
    ): ResponseEntity<BatchMigrationResult> {
        val result =
            graphService.batchMigrateConversations(
                conversationRepository = conversationRepository,
                batchSize = batchSize,
                maxConversations = maxConversations,
            )
        return ResponseEntity.ok(result)
    }

    /**
     * Get migration statistics from the graph database.
     *
     * @return Current statistics about conversations and paths in the graph
     */
    @GetMapping("/statistics")
    suspend fun getMigrationStatistics(): ResponseEntity<MigrationStatistics> {
        val statistics = graphService.getMigrationStatistics()
        return ResponseEntity.ok(statistics)
    }

    /**
     * NEW: Get all conversations under a specific node using proper graph traversal.
     * This replaces the path-based approach with true graph hierarchy.
     *
     * @param nodeName The node name to search under (e.g., "domain")
     * @return List of conversation IDs under the node and its descendants
     */
    @GetMapping("/conversations/by-node")
    suspend fun getConversationsUnderNode(
        @RequestParam nodeName: String,
    ): ResponseEntity<List<String>> {
        val conversationIds = graphService.getConversationsUnderNode(nodeName)
        return ResponseEntity.ok(conversationIds)
    }

    /**
     * NEW: Get all nodes under a specific node using proper graph traversal.
     *
     * @param nodeName The node name to search under
     * @return List of node information including descendants
     */
    @GetMapping("/nodes")
    suspend fun getNodesUnderNode(
        @RequestParam nodeName: String,
    ): ResponseEntity<List<NodeInfo>> {
        val nodeInfos = graphService.getNodesUnderNode(nodeName)
        return ResponseEntity.ok(nodeInfos)
    }

    /**
     * Health check endpoint to verify graph database connectivity.
     *
     * @return Simple status message
     */
    @GetMapping("/health")
    fun healthCheck(): ResponseEntity<Map<String, String>> = ResponseEntity.ok(mapOf("status" to "Graph service is available"))
} 
