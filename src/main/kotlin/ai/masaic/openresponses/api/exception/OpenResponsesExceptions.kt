package ai.masaic.openresponses.api.exception

/**
 * Base exception class for all OpenResponses API exceptions.
 */
sealed class OpenResponsesException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Exceptions related to resource not found.
 */
sealed class ResourceNotFoundException(
    message: String,
    cause: Throwable? = null,
) : OpenResponsesException(message, cause)

/**
 * Exception thrown when a file is not found.
 */
class FileNotFoundException(
    message: String,
    cause: Throwable? = null,
) : ResourceNotFoundException(message, cause)

/**
 * Exception thrown when a vector store is not found.
 */
class VectorStoreNotFoundException(
    message: String,
    cause: Throwable? = null,
) : ResourceNotFoundException(message, cause)

/**
 * Exception thrown when a vector store file is not found.
 */
class VectorStoreFileNotFoundException(
    message: String,
    cause: Throwable? = null,
) : ResourceNotFoundException(message, cause)

/**
 * Exceptions related to file storage.
 */
sealed class FileStorageException(
    message: String,
    cause: Throwable? = null,
) : OpenResponsesException(message, cause)

/**
 * Exception thrown when there is an error storing a file.
 */
class FileStoreException(
    message: String,
    cause: Throwable? = null,
) : FileStorageException(message, cause)

/**
 * Exception thrown when there is an error reading a file.
 */
class FileReadException(
    message: String,
    cause: Throwable? = null,
) : FileStorageException(message, cause)

/**
 * Exception thrown when there is an error deleting a file.
 */
class FileDeleteException(
    message: String,
    cause: Throwable? = null,
) : FileStorageException(message, cause)

/**
 * Exceptions related to vector stores.
 */
sealed class VectorStoreException(
    message: String,
    cause: Throwable? = null,
) : OpenResponsesException(message, cause)

/**
 * Exception thrown when there's an error in vector search operations.
 */
class VectorSearchException(
    message: String,
    cause: Throwable? = null,
) : VectorStoreException(message, cause)

/**
 * Exception thrown when there's an indexing error in vector stores.
 */
class VectorIndexingException(
    message: String,
    cause: Throwable? = null,
) : VectorStoreException(message, cause)
