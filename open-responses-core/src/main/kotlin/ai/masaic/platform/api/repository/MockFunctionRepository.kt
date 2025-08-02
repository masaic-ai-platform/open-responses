package ai.masaic.platform.api.repository

import ai.masaic.platform.api.tools.MockFunctionDefinition
import com.mongodb.client.result.DeleteResult
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import java.time.Instant

/**
 * Repository for persisting [MockFunctionDefinition] objects in MongoDB.
 */
interface MockFunctionRepository {
    suspend fun upsert(definition: MockFunctionDefinition): MockFunctionDefinition

    suspend fun findById(id: String): MockFunctionDefinition?

    suspend fun deleteById(id: String): Boolean

    suspend fun findAll(): List<MockFunctionDefinition>
}

class MongoMockFunctionRepository(
    private val mongoTemplate: ReactiveMongoTemplate,
) : MockFunctionRepository {
    companion object {
        private const val COLLECTION_NAME = "mock_function_definitions"
    }

    override suspend fun upsert(definition: MockFunctionDefinition): MockFunctionDefinition {
        val definitionWithTimestamp =
            if (definition.createdAt == null) {
                definition.copy(createdAt = Instant.now())
            } else {
                definition
            }
        return mongoTemplate.save(definitionWithTimestamp, COLLECTION_NAME).awaitSingle()
    }

    override suspend fun findById(id: String): MockFunctionDefinition? = mongoTemplate.findById(id, MockFunctionDefinition::class.java, COLLECTION_NAME).awaitSingleOrNull()

    override suspend fun deleteById(id: String): Boolean {
        val query = Query.query(Criteria.where("_id").`is`(id))
        val result: DeleteResult = mongoTemplate.remove(query, COLLECTION_NAME).awaitSingle()
        return result.deletedCount > 0
    }

    override suspend fun findAll(): List<MockFunctionDefinition> {
        val query = Query().with(Sort.by(Sort.Direction.DESC, "createdAt"))
        return mongoTemplate.find(query, MockFunctionDefinition::class.java, COLLECTION_NAME).collectList().awaitSingle()
    }
} 
