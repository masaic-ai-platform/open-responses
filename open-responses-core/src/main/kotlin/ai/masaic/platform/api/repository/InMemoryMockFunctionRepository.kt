package ai.masaic.platform.api.repository

import ai.masaic.platform.api.tools.MockFunctionDefinition
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine

/**
 * In-memory implementation of [MockFunctionRepository] using Caffeine cache.
 * Stores data in a cache with LRU eviction and a maximum size of 100.
 */
class InMemoryMockFunctionRepository : MockFunctionRepository {
    private val cache: Cache<String, MockFunctionDefinition> =
        Caffeine.newBuilder().maximumSize(100).build()

    override suspend fun upsert(definition: MockFunctionDefinition): MockFunctionDefinition {
        cache.put(definition.id, definition)
        return definition
    }

    override fun findById(id: String): MockFunctionDefinition? = cache.getIfPresent(id)

    override fun deleteById(id: String): Boolean {
        val existed = cache.getIfPresent(id) != null
        cache.invalidate(id)
        return existed
    }

    override fun findAll(): List<MockFunctionDefinition> = cache.asMap().values.sortedByDescending { it.createdAt }

    fun clear() {
        cache.invalidateAll()
    }

    fun size(): Int = cache.asMap().size
} 
