package ai.masaic.openresponses.api.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Request model for creating embeddings.
 * Based on OpenAI API spec: https://platform.openai.com/docs/api-reference/embeddings/create
 */
data class CreateEmbeddingRequest(
    /** Input text to embed */
    val input: Any,
    /** ID of the model to use */
    val model: String,
    /** The format to return the embeddings in */
    @JsonProperty("encoding_format")
    val encodingFormat: String = "float",
    /** The number of dimensions the resulting output embeddings should have */
    val dimensions: Int? = null,
    /** A unique identifier for the request */
    val user: String? = null,
)

/**
 * Response model for embeddings, following OpenAI API format.
 * Based on: https://platform.openai.com/docs/api-reference/embeddings/object
 */
data class EmbeddingResponse(
    val `object`: String = "list",
    val data: List<EmbeddingData>,
    val model: String,
    val usage: EmbeddingUsage,
)

/**
 * Individual embedding data object.
 * Supports both float and base64 encoding formats.
 */
data class EmbeddingData(
    val `object`: String = "embedding",
    val embedding: Any, // Can be List<Float> or String (base64)
    val index: Int,
)

/**
 * Token usage information for embedding requests.
 */
data class EmbeddingUsage(
    @JsonProperty("prompt_tokens")
    val promptTokens: Int,
    @JsonProperty("total_tokens")
    val totalTokens: Int,
) 
