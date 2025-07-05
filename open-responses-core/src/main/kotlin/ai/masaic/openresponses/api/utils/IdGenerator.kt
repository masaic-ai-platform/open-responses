package ai.masaic.openresponses.api.utils

import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Utility class for generating compact unique IDs similar to MongoDB ObjectIds
 * but with different prefixes based on the entity type.
 */
object IdGenerator {
    private val counter = AtomicInteger(0)
    private val random = SecureRandom()
    private val machineId = generateMachineId()
    
    // Prefixes for different entity types
    const val VECTOR_STORE_PREFIX = "vs_"
    const val FILE_PREFIX = "open-responses-file_"
    const val CHUNK_PREFIX = "c_"

    /**
     * Generates a 3-byte machine identifier for uniqueness across instances
     */
    private fun generateMachineId(): String {
        val bytes = ByteArray(3)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generates a MongoDB-like ObjectId as a string
     * Format: timestamp (4 bytes) + machine id (3 bytes) + counter (3 bytes)
     */
    private fun generateObjectId(): String {
        val timestamp = (Instant.now().epochSecond).toString(16).padStart(8, '0')
        val count = counter.getAndIncrement().toString(16).padStart(6, '0')
        return timestamp + machineId + count
    }

    /**
     * Generates a unique ID for a vector store
     * @return A string in the format "vs_{objectId}"
     */
    fun generateVectorStoreId(): String = VECTOR_STORE_PREFIX + generateObjectId()

    /**
     * Generates a unique ID for a file
     * @return A string in the format "open-responses-file_{objectId}"
     */
    fun generateFileId(): String = FILE_PREFIX + generateObjectId()

    /**
     * Generates a unique ID for a chunk
     * @return A string in the format "c_{objectId}"
     */
    fun generateChunkId(): String = CHUNK_PREFIX + generateObjectId()
} 
