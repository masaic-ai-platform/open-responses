package ai.masaic.platform.api.repository

import ai.masaic.platform.api.tools.Mocks
import com.mongodb.client.result.DeleteResult
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import java.time.Instant

/**
 * Repository for persisting [Mocks] documents.
 */
interface MocksRepository {
    fun upsert(mocks: Mocks): Mocks

    fun findById(refFunctionId: String): Mocks?

    fun deleteById(refFunctionId: String): Boolean
}

class MongoMocksRepository(
    private val mongoTemplate: ReactiveMongoTemplate,
) : MocksRepository {
    companion object {
        private const val COLLECTION_NAME = "mocks"
    }

    override fun upsert(mocks: Mocks): Mocks {
        val mocksWithTimestamp =
            if (mocks.createdAt == null) {
                mocks.copy(createdAt = Instant.now())
            } else {
                mocks
            }
        return runBlocking { mongoTemplate.save(mocksWithTimestamp, COLLECTION_NAME).awaitSingle() }
    }

    override fun findById(refFunctionId: String): Mocks? = runBlocking { mongoTemplate.findById(refFunctionId, ai.masaic.platform.api.tools.Mocks::class.java, COLLECTION_NAME).awaitSingleOrNull() }

    override fun deleteById(refFunctionId: String): Boolean {
        val query = Query.query(Criteria.where("_id").`is`(refFunctionId))
        val result: DeleteResult = runBlocking { mongoTemplate.remove(query, COLLECTION_NAME).awaitSingle() }
        return result.deletedCount > 0
    }
} 
