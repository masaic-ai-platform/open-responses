package ai.masaic.openresponses.api.model

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Vector store object representing a collection of processed files.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Document(collection = "vector_stores")
data class VectorStore(
    /**
     * The identifier, which can be referenced in API endpoints.
     */
    @Id
    val id: String,
    /**
     * The object type, which is always vector_store.
     */
    val `object`: String = "vector_store",
    /**
     * The Unix timestamp (in seconds) for when the vector store was created.
     */
    @JsonProperty("created_at")
    @Indexed
    val createdAt: Long = Instant.now().epochSecond,
    /**
     * The name of the vector store.
     */
    val name: String?,
    /**
     * The total number of bytes used by the files in the vector store.
     */
    @JsonProperty("bytes")
    val bytes: Long = 0,
    /**
     * File counts in the vector store.
     */
    @JsonProperty("file_counts")
    val fileCounts: FileCounts = FileCounts(),
    /**
     * The Unix timestamp (in seconds) for when the vector store was last active.
     */
    @JsonProperty("last_active_at")
    val lastActiveAt: Long? = null,
    /**
     * The status of the vector store, which can be either expired, in_progress, or completed.
     */
    val status: String = "in_progress",
    /**
     * Set of key-value pairs that can be attached to an object.
     */
    val metadata: Map<String, String>? = null,
    /**
     * The Unix timestamp (in seconds) for when the vector store will expire.
     */
    @JsonProperty("expires_at")
    val expiresAt: Long? = null,
)

/**
 * Extension function to check if a vector store is expired.
 *
 * @return true if the vector store is expired, false otherwise
 */
fun VectorStore.isExpired(): Boolean {
    if (expiresAt == null) return false
    return Instant.now().epochSecond >= expiresAt
}

/**
 * File counts in a vector store.
 */
data class FileCounts(
    @JsonProperty("in_progress")
    val inProgress: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val cancelled: Int = 0,
    val total: Int = 0,
)

/**
 * Response for listing vector stores.
 */
data class VectorStoreListResponse(
    val data: List<VectorStore>,
    val `object`: String = "list",
    @JsonProperty("first_id")
    val firstId: String? = null,
    @JsonProperty("last_id")
    val lastId: String? = null,
    @JsonProperty("has_more")
    val hasMore: Boolean = false,
)

/**
 * Response for deleting a vector store.
 */
data class VectorStoreDeleteResponse(
    val id: String,
    val `object`: String = "vector_store.deleted",
    val deleted: Boolean = true,
)

/**
 * Vector store file object representing a file in a vector store.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Document(collection = "vector_store_files")
data class VectorStoreFile(
    /**
     * The identifier, which can be referenced in API endpoints.
     */
    @Id
    val id: String,
    /**
     * The object type, which is always vector_store.file.
     */
    val `object`: String = "vector_store.file",
    /**
     * The Unix timestamp (in seconds) for when the vector store file was created.
     */
    @JsonProperty("created_at")
    @Indexed
    val createdAt: Long = Instant.now().epochSecond,
    /**
     * The total vector store usage in bytes.
     */
    @JsonProperty("usage_bytes")
    val usageBytes: Long = 0,
    /**
     * The ID of the vector store that the file is attached to.
     */
    @JsonProperty("vector_store_id")
    @Indexed
    val vectorStoreId: String,
    /**
     * The status of the vector store file.
     */
    @Indexed
    val status: String = "completed",
    /**
     * The last error associated with this vector store file.
     */
    @JsonProperty("last_error")
    val lastError: String? = null,
    /**
     * The strategy used to chunk the file.
     */
    @JsonProperty("chunking_strategy")
    val chunkingStrategy: ChunkingStrategy? = null,
    /**
     * Attributes for the file.
     */
    val attributes: Map<String, Any>? = null,
)

/**
 * Chunking strategy used for vector store files.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChunkingStrategy(
    /**
     * The type of chunking strategy.
     */
    val type: String,
    /**
     * Static chunking configuration.
     */
    val static: StaticChunkingConfig? = null,
)

/**
 * Static chunking configuration.
 */
data class StaticChunkingConfig(
    @JsonProperty("max_chunk_size_tokens")
    val maxChunkSizeTokens: Int,
    @JsonProperty("chunk_overlap_tokens")
    val chunkOverlapTokens: Int,
)

/**
 * Request to create a vector store.
 */
data class CreateVectorStoreRequest(
    /**
     * The name of the vector store.
     */
    val name: String? = null,
    /**
     * A list of File IDs that the vector store should use.
     */
    @JsonProperty("file_ids")
    val fileIds: List<String>? = null,
    /**
     * The chunking strategy used to chunk the file(s).
     */
    @JsonProperty("chunking_strategy")
    val chunkingStrategy: ChunkingStrategy? = null,
    /**
     * Set of key-value pairs that can be attached to an object.
     */
    val metadata: Map<String, String>? = null,
    /**
     * The expiration policy for a vector store.
     */
    @JsonProperty("expires_after")
    val expiresAfter: ExpirationPolicy? = null,
    val modelInfo: ModelInfo? = null,
)

/**
 * Request to modify a vector store.
 */
data class ModifyVectorStoreRequest(
    /**
     * The name of the vector store.
     */
    val name: String? = null,
    /**
     * Set of key-value pairs that can be attached to an object.
     */
    val metadata: Map<String, String>? = null,
    /**
     * The expiration policy for a vector store.
     */
    @JsonProperty("expires_after")
    val expiresAfter: ExpirationPolicy? = null,
)

/**
 * Expiration policy for a vector store.
 */
data class ExpirationPolicy(
    /**
     * Anchor timestamp after which the expiration policy applies.
     * Supported anchors: last_active_at
     */
    val anchor: String,
    /**
     * The number of days after the anchor time that the vector store will expire.
     */
    val days: Int,
) {
    /**
     * Calculates the expiration timestamp based on the anchor time.
     *
     * @param anchorTime The anchor timestamp in seconds
     * @return The expiration timestamp in seconds, or null if calculation fails
     */
    fun calculateExpiresAt(anchorTime: Long?): Long? {
        if (anchorTime == null) return null
        
        return when (anchor.lowercase()) {
            "last_active_at" -> {
                val expirationTime =
                    Instant
                        .ofEpochSecond(anchorTime)
                        .plus(days.toLong(), ChronoUnit.DAYS)
                expirationTime.epochSecond
            }
            else -> null
        }
    }
}

/**
 * Request to create a vector store file.
 */
data class CreateVectorStoreFileRequest(
    /**
     * The ID of the file to attach to the vector store.
     */
    @JsonProperty("file_id")
    val fileId: String,
    /**
     * The chunking strategy used to chunk the file.
     */
    @JsonProperty("chunking_strategy")
    val chunkingStrategy: ChunkingStrategy? = null,
    /**
     * Set of key-value pairs that can be attached to the file.
     */
    val attributes: Map<String, Any>? = null,
    val modelInfo: ModelInfo? = null,
)

/**
 * Request to update a vector store file's attributes.
 */
data class UpdateVectorStoreFileAttributesRequest(
    /**
     * Set of key-value pairs that can be attached to the file.
     */
    val attributes: Map<String, Any>,
)

/**
 * Response for listing vector store files.
 */
data class VectorStoreFileListResponse(
    val data: List<VectorStoreFile>,
    val `object`: String = "list",
    @JsonProperty("first_id")
    val firstId: String? = null,
    @JsonProperty("last_id")
    val lastId: String? = null,
    @JsonProperty("has_more")
    val hasMore: Boolean = false,
)

/**
 * Response for deleting a vector store file.
 */
data class VectorStoreFileDeleteResponse(
    val id: String,
    val `object`: String = "vector_store.file.deleted",
    val deleted: Boolean = true,
)

/**
 * Vector store search request.
 */
data class VectorStoreSearchRequest(
    /**
     * A query string for a search
     */
    val query: String,
    /**
     * Filter to apply to search results.
     * This should be a direct Filter object, not wrapped in any additional container.
     */
    val filters: Filter? = null,
    /**
     * The maximum number of results to return.
     */
    @JsonProperty("max_num_results")
    val maxNumResults: Int? = 10,
    /**
     * Whether to rewrite the natural language query for vector search.
     */
    @JsonProperty("rewrite_query")
    val rewriteQuery: Boolean? = false,
    /**
     * Ranking options for search.
     */
    @JsonProperty("ranking_options")
    val rankingOptions: RankingOptions? = null,
    val modelInfo: ModelInfo? = null,
)

/**
 * Base interface for filter objects
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
    visible = true,
)
@JsonSubTypes(
    JsonSubTypes.Type(value = ComparisonFilter::class, names = ["eq", "ne", "gt", "gte", "lt", "lte"]),
    JsonSubTypes.Type(value = CompoundFilter::class, names = ["and", "or"]),
)
interface Filter

/**
 * A filter used to compare a specified attribute key to a given value using a defined comparison operation.
 *
 * @property key The key to compare against the value.
 * @property type The comparison operator: eq, ne, gt, gte, lt, lte.
 * @property value The value to compare against the attribute key; supports string, number, or boolean types.
 */
data class ComparisonFilter(
    @JsonAlias("property")
    val key: String,
    val type: String,
    val value: Any,
) : Filter {
    override fun toString(): String = "{$type: $key=$value}"
}

/**
 * Combine multiple filters using 'and' or 'or'.
 *
 * @property type Type of operation: and or or.
 * @property filters Array of filters to combine. Items can be ComparisonFilter or CompoundFilter.
 */
data class CompoundFilter(
    val type: String,
    val filters: List<Filter>,
) : Filter {
    override fun toString(): String = "{$type: [${filters.joinToString(", ")}]}"
}

/**
 * Vector store search results.
 */
data class VectorStoreSearchResults(
    /**
     * The object type.
     */
    val `object`: String = "vector_store.search_results.page",
    /**
     * The search query.
     */
    @JsonProperty("search_query")
    val searchQuery: String,
    /**
     * The search results.
     */
    val data: List<VectorStoreSearchResult>,
    /**
     * Whether there are more results.
     */
    @JsonProperty("has_more")
    val hasMore: Boolean = false,
    /**
     * The next page token.
     */
    @JsonProperty("next_page")
    val nextPage: String? = null,
)

/**
 * Individual search result from a vector store.
 */
data class VectorStoreSearchResult(
    /**
     * The ID of the file.
     */
    @JsonProperty("file_id")
    val fileId: String,
    /**
     * The filename.
     */
    val filename: String,
    /**
     * The similarity score.
     */
    val score: Double,
    /**
     * File attributes.
     */
    val attributes: Map<String, Any>? = null,
    /**
     * The content of the search result.
     */
    val content: List<VectorStoreSearchResultContent>,
)

/**
 * Content of a vector store search result.
 */
data class VectorStoreSearchResultContent(
    /**
     * The type of content.
     */
    val type: String,
    /**
     * The text content.
     */
    val text: String,
)

/**
 * Vector store file content response.
 */
data class VectorStoreFileContent(
    /**
     * The ID of the file.
     */
    @JsonProperty("file_id")
    val fileId: String,
    /**
     * The filename.
     */
    val filename: String,
    /**
     * File attributes.
     */
    val attributes: Map<String, Any>? = null,
    /**
     * The content of the file.
     */
    val content: List<VectorStoreSearchResultContent>,
) 
