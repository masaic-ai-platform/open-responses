package ai.masaic.platform.api.repository

import ai.masaic.platform.api.tools.McpMockServer
import com.mongodb.client.result.DeleteResult
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import java.time.Instant

interface McpMockServerRepository {
    suspend fun upsert(server: McpMockServer): McpMockServer

    suspend fun findById(id: String): McpMockServer?

    suspend fun deleteById(id: String): Boolean

    suspend fun findAll(): List<McpMockServer>
}

class MongoMcpMockServerRepository(
    private val mongoTemplate: ReactiveMongoTemplate,
) : McpMockServerRepository {
    companion object {
        private const val COLLECTION_NAME = "mcp_mock_servers"
    }

    override suspend fun upsert(server: McpMockServer): McpMockServer {
        val serverWithTimestamp =
            if (server.createdAt == null) {
                server.copy(createdAt = Instant.now())
            } else {
                server
            }
        return mongoTemplate.save(serverWithTimestamp, COLLECTION_NAME).awaitSingle()
    }

    override suspend fun findById(id: String): McpMockServer? = mongoTemplate.findById(id, McpMockServer::class.java, COLLECTION_NAME).awaitSingleOrNull()

    override suspend fun deleteById(id: String): Boolean {
        val query = Query.query(Criteria.where("_id").`is`(id))
        val result: DeleteResult = mongoTemplate.remove(query, COLLECTION_NAME).awaitSingle()
        return result.deletedCount > 0
    }

    override suspend fun findAll(): List<McpMockServer> {
        val query = Query().with(Sort.by(Sort.Direction.DESC, "createdAt"))
        return mongoTemplate.find(query, McpMockServer::class.java, COLLECTION_NAME).collectList().awaitSingle()
    }
} 
