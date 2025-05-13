package ai.masaic.improved.model

import java.time.Instant

/**
 * Represents a conversation with messages between user and assistant.
 */
data class Conversation(
    val id: String,
    val createdAt: Instant,
    val messages: List<Message>,
    val labels: List<Label> = emptyList(),
    val resolved: Boolean? = null,
    val nps: Int? = null,
    val meta: Map<String, Any> = emptyMap(),
    val version: Int = 1
) {
    init {
        require(id.matches(Regex("^conv_[a-f0-9]{32}$"))) { "Invalid conversation ID format" }
        require(messages.isNotEmpty()) { "Conversation must contain at least one message" }
        require(nps == null || (nps in -100..100)) { "NPS score must be between -100 and 100" }
    }
}

/**
 * Represents a single message in a conversation.
 */
data class Message(
    val role: Role,
    val text: String
)

/**
 * Enum representing the possible roles for a message.
 */
enum class Role {
    USER, ASSISTANT, SYSTEM;
    
    companion object {
        fun fromString(value: String): Role {
            return valueOf(value.uppercase())
        }
    }
}

/**
 * Represents a label attached to a conversation.
 */
data class Label(
    val path: String,
    val source: LabelSource,
    val ruleVer: String? = null,
    val user: String? = null,
    val createdAt: Instant? = null
) {
    init {
        require(path.matches(Regex("^[a-zA-Z0-9_\\-/]+$"))) { "Invalid label path format" }
        require(user == null || user.matches(Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))) { "Invalid email format" }
        require(ruleVer == null || ruleVer.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$"))) { "Invalid rule version format" }
    }
}

/**
 * Enum representing the source of a label.
 */
enum class LabelSource {
    AUTO, MANUAL;
    
    companion object {
        fun fromString(value: String): LabelSource {
            return valueOf(value.uppercase())
        }
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
    val meta: Map<String, Any>? = null
) 