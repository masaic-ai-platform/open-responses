package ai.masaic.platform.api.repository

import ai.masaic.platform.api.tools.Mocks
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine

/**
 * In-memory implementation of [MocksRepository] using Caffeine cache.
 * Stores data in a cache with LRU eviction and a maximum size of 100.
 */
class InMemoryMocksRepository : MocksRepository {
    private val cache: Cache<String, Mocks> =
        Caffeine.newBuilder().maximumSize(100).build()

    override fun upsert(mocks: Mocks): Mocks {
        cache.put(mocks.refFunctionId, mocks)
        return mocks
    }

    override fun findById(refFunctionId: String): Mocks? = cache.getIfPresent(refFunctionId)

    override fun deleteById(refFunctionId: String): Boolean {
        val existed = cache.getIfPresent(refFunctionId) != null
        cache.invalidate(refFunctionId)
        return existed
    }

    fun clear() {
        cache.invalidateAll()
    }

    fun size(): Int = cache.asMap().size
} 
