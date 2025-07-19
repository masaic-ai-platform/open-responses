package ai.masaic.platform.api.repository

import ai.masaic.platform.api.tools.McpMockServer
import com.mongodb.client.result.DeleteResult
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import java.time.Instant

interface McpMockServerRepository {
    fun upsert(server: McpMockServer): McpMockServer

    fun findById(id: String): McpMockServer?

    fun deleteById(id: String): Boolean

    fun findAll(): List<McpMockServer>
}

class MongoMcpMockServerRepository(
    private val mongoTemplate: ReactiveMongoTemplate,
) : McpMockServerRepository {
    companion object {
        private const val COLLECTION_NAME = "mcp_mock_servers"
    }

    override fun upsert(server: McpMockServer): McpMockServer {
        val serverWithTimestamp =
            if (server.createdAt == null) {
                server.copy(createdAt = Instant.now())
            } else {
                server
            }
        return runBlocking { mongoTemplate.save(serverWithTimestamp, COLLECTION_NAME).awaitSingle() }
    }

    override fun findById(id: String): McpMockServer? = runBlocking { mongoTemplate.findById(id, McpMockServer::class.java, COLLECTION_NAME).awaitSingleOrNull() }

    override fun deleteById(id: String): Boolean {
        val query = Query.query(Criteria.where("_id").`is`(id))
        val result: DeleteResult = runBlocking { mongoTemplate.remove(query, COLLECTION_NAME).awaitSingle() }
        return result.deletedCount > 0
    }

    override fun findAll(): List<McpMockServer> {
        val query = Query().with(Sort.by(Sort.Direction.DESC, "createdAt"))
        return runBlocking { mongoTemplate.find(query, McpMockServer::class.java, COLLECTION_NAME).collectList().awaitSingle() }
    }
} 
