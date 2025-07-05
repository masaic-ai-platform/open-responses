package ai.masaic.openresponses.api.repository

import ai.masaic.openresponses.api.model.VectorStore
import ai.masaic.openresponses.api.model.VectorStoreFile
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Abstract base class for VectorStoreRepository implementations.
 * This provides common functionality and template methods for concrete implementations.
 */
abstract class AbstractVectorStoreRepository : VectorStoreRepository {
    protected open val log: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Template method for listing vector stores with common validation and filtering logic.
     */
    override suspend fun listVectorStores(
        limit: Int,
        order: String,
        after: String?,
        before: String?,
    ): List<VectorStore> {
        // Validate parameters
        val validatedLimit = validateLimit(limit)
        val validatedOrder = validateOrder(order)

        // Fetch the raw list from the implementation
        val allVectorStores = fetchAllVectorStores()

        // Apply pagination and ordering
        return applyPaginationAndOrder(allVectorStores, validatedLimit, validatedOrder, after, before)
    }

    /**
     * Template method for listing vector store files with common validation and filtering logic.
     */
    override suspend fun listVectorStoreFiles(
        vectorStoreId: String,
        limit: Int,
        order: String,
        after: String?,
        before: String?,
        filter: String?,
    ): List<VectorStoreFile> {
        // Validate parameters
        val validatedLimit = validateLimit(limit)
        val validatedOrder = validateOrder(order)

        // Fetch the raw list from the implementation
        val allFiles = fetchAllVectorStoreFiles(vectorStoreId)

        // Apply status filter if provided
        val filteredFiles =
            if (filter != null) {
                allFiles.filter { it.status == filter }
            } else {
                allFiles
            }

        // Apply pagination and ordering
        return applyPaginationAndOrder(filteredFiles, validatedLimit, validatedOrder, after, before)
    }

    /**
     * Fetch all vector stores from the storage.
     * Implemented by concrete subclasses.
     */
    protected abstract suspend fun fetchAllVectorStores(): List<VectorStore>

    /**
     * Fetch all files for a vector store from the storage.
     * Implemented by concrete subclasses.
     */
    protected abstract suspend fun fetchAllVectorStoreFiles(vectorStoreId: String): List<VectorStoreFile>

    /**
     * Validates the limit parameter.
     */
    private fun validateLimit(limit: Int): Int =
        when {
            limit <= 0 -> 20 // Default
            limit > 100 -> 100 // Max
            else -> limit
        }

    /**
     * Validates the order parameter.
     */
    private fun validateOrder(order: String): String =
        if (order.equals("asc", ignoreCase = true) || order.equals("desc", ignoreCase = true)) {
            order.lowercase()
        } else {
            "desc" // Default
        }

    /**
     * Helper method to apply pagination and ordering to a list.
     */
    private fun <T> applyPaginationAndOrder(
        items: List<T>,
        limit: Int,
        order: String,
        after: String?,
        before: String?,
    ): List<T> where T : Any {
        // First sort the items based on the order parameter
        val sortedItems =
            when {
                items.isEmpty() -> items
                items.first() is VectorStore -> {
                    if (order == "asc") {
                        @Suppress("UNCHECKED_CAST")
                        (items as List<VectorStore>).sortedBy { it.createdAt } as List<T>
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        (items as List<VectorStore>).sortedByDescending { it.createdAt } as List<T>
                    }
                }
                items.first() is VectorStoreFile -> {
                    if (order == "asc") {
                        @Suppress("UNCHECKED_CAST")
                        (items as List<VectorStoreFile>).sortedBy { it.createdAt } as List<T>
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        (items as List<VectorStoreFile>).sortedByDescending { it.createdAt } as List<T>
                    }
                }
                else -> items
            }

        // Skip items based on pagination
        val filteredItems =
            when {
                after != null && getIdFromItem(sortedItems.firstOrNull()) != null -> {
                    val afterIndex = sortedItems.indexOfFirst { getIdFromItem(it) == after }
                    if (afterIndex >= 0 && afterIndex < sortedItems.size - 1) {
                        sortedItems.subList(afterIndex + 1, sortedItems.size)
                    } else {
                        emptyList()
                    }
                }
                before != null && getIdFromItem(sortedItems.firstOrNull()) != null -> {
                    val beforeIndex = sortedItems.indexOfFirst { getIdFromItem(it) == before }
                    if (beforeIndex > 0) {
                        sortedItems.subList(0, beforeIndex)
                    } else {
                        emptyList()
                    }
                }
                else -> sortedItems
            }

        // Apply limit
        return if (filteredItems.size > limit) {
            filteredItems.subList(0, limit)
        } else {
            filteredItems
        }
    }

    /**
     * Helper method to get the ID from a VectorStore or VectorStoreFile.
     */
    private fun getIdFromItem(item: Any?): String? =
        when (item) {
            is VectorStore -> item.id
            is VectorStoreFile -> item.id
            else -> null
        }
} 
