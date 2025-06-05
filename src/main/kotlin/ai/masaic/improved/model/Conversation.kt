package ai.masaic.improved.model

import java.time.Instant

/**
 * Represents a conversation with messages between user and assistant.
 */
data class Conversation(
    val id: String,
    val createdAt: Instant,
    val messages: List<Message> = emptyList(),
    val summary: String = "NA",
    val labels: List<Label> = emptyList(),
    val resolved: Boolean? = null,
    val nps: Int? = null,
    val meta: Map<String, Any> = emptyMap(),
    val version: Int = 1,
    val classification: CLASSIFICATION? = null,
) {
//    init {
//        require(messages.isNotEmpty()) { "Conversation must contain at least one message" }
//    }
}

/**
 * Represents a single message in a conversation.
 */
data class Message(
    val role: Role,
    val text: String,
)

/**
 * Enum representing the possible roles for a message.
 */
enum class Role {
    USER,
    ASSISTANT,
    SYSTEM,
    ;

    companion object {
        fun fromString(value: String): Role = valueOf(value.uppercase())
    }
}

/**
 * Represents a label attached to a conversation.
 */
data class Label(
    val path: String,
    val source: LabelSource,
    val status: String, // final | suggested | rejected
    val reason: String = "NA",
    val createdAt: Instant? = null,
)

/**
 * Enum representing the source of a label.
 */
enum class LabelSource {
    AUTO,
    AUTO_ALGO,
    MANUAL,
    ;

    companion object {
        fun fromString(value: String): LabelSource = valueOf(value.uppercase())
    }
}

/**
 * Parameters for listing conversations with filtering options.
 */
data class ListConversationsParams(
    val limit: Int = 100,
    val after: String? = null,
    val before: String? = null,
    val order: String = "desc",
    val resolved: Boolean? = null,
    val labels: List<String>? = null,
    val meta: Map<String, Any>? = null,
)

/**
 * Enum representing the classification status of a conversation.
 */
enum class CLASSIFICATION {
    RESOLVED,
    UNRESOLVED,
}
