package ai.masaic.openresponses.api.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * The File object represents a document that has been uploaded to OpenAI.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class File(
    /**
     * The file identifier, which can be referenced in the API endpoints.
     */
    val id: String,
    /**
     * The object type, which is always file.
     */
    val `object`: String = "file",
    /**
     * The size of the file, in bytes.
     */
    val bytes: Long,
    /**
     * The Unix timestamp (in seconds) for when the file was created.
     */
    @JsonProperty("created_at")
    val createdAt: Long = Instant.now().epochSecond,
    /**
     * The name of the file.
     */
    val filename: String,
    /**
     * The intended purpose of the file.
     * Supported values are: assistants, batch, fine_tune, vision, user_data, evals
     */
    val purpose: String,
    /**
     * The Unix timestamp (in seconds) for when the file will expire.
     */
    @JsonProperty("expires_at")
    val expiresAt: Long? = null,
    /**
     * The current status of the file (deprecated).
     */
    @Deprecated("This field is deprecated")
    val status: String? = "processed",
    /**
     * Status details for fine-tuning files (deprecated).
     */
    @Deprecated("This field is deprecated")
    @JsonProperty("status_details")
    val statusDetails: String? = null,
)

/**
 * Response for listing files.
 */
data class FileListResponse(
    val data: List<File>,
    val `object`: String = "list",
)

/**
 * Response for file deletion.
 */
data class FileDeleteResponse(
    val id: String,
    val `object`: String = "file",
    val deleted: Boolean = true,
)

/**
 * Enum representing supported file purposes.
 */
enum class FilePurpose {
    assistants,
    assistants_output,
    batch,
    batch_output,
    fine_tune,
    fine_tune_results,
    vision,
    user_data,
    evals,
    ;

    companion object {
        fun isValid(purpose: String): Boolean =
            try {
                valueOf(purpose)
                true
            } catch (e: IllegalArgumentException) {
                false
            }
    }
} 
