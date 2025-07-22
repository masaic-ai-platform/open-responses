package ai.masaic.platform.api.repository

import ai.masaic.platform.api.tools.McpMockServer
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine

/**
 * In-memory implementation of [McpMockServerRepository] using Caffeine cache.
 * Stores data in a cache with LRU eviction and a maximum size of 100.
 */
class InMemoryMcpMockServerRepository : McpMockServerRepository {
    private val cache: Cache<String, McpMockServer> =
        Caffeine.newBuilder().maximumSize(100).build()

    override fun upsert(server: McpMockServer): McpMockServer {
        cache.put(server.id, server)
        return server
    }

    override fun findById(id: String): McpMockServer? = cache.getIfPresent(id)

    override fun deleteById(id: String): Boolean {
        val existed = cache.getIfPresent(id) != null
        cache.invalidate(id)
        return existed
    }

    override fun findAll(): List<McpMockServer> = cache.asMap().values.sortedByDescending { it.createdAt }

    fun clear() {
        cache.invalidateAll()
    }

    fun size(): Int = cache.asMap().size
} 
