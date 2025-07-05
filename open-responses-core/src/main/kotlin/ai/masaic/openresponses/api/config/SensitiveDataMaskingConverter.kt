package ai.masaic.openresponses.api.config

import ch.qos.logback.classic.pattern.MessageConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import java.util.regex.Pattern

/**
 * Logback converter that masks sensitive data in log messages.
 *
 * This converter identifies patterns that likely contain sensitive information
 * like API keys, tokens, and credentials, then replaces them with masked values.
 */
class SensitiveDataMaskingConverter : MessageConverter() {
    companion object {
        // Patterns to mask out sensitive information
        private val PATTERNS =
            listOf(
                // API Keys, Tokens, and Authorization headers
                Pattern.compile("(api[_-]?key|token|auth|credential|password|secret)\\s*[=:]\\s*['\"](.*?)['\"]", Pattern.CASE_INSENSITIVE),
                Pattern.compile("Authorization\\s*:\\s*Bearer\\s*(.*?)(?=[\"'\\s]|$)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("Authorization\\s*:\\s*Basic\\s*(.*?)(?=[\"'\\s]|$)", Pattern.CASE_INSENSITIVE),
                // JSON patterns
                Pattern.compile("\"(api[_-]?key|token|auth|password|secret)\"\\s*:\\s*\"(.*?)\"", Pattern.CASE_INSENSITIVE),
                // URLs with credentials
                Pattern.compile("(https?://)(.*?):(.*)@", Pattern.CASE_INSENSITIVE),
                // Model provider API key sensitive headers
                Pattern.compile("x-api-key\\s*:\\s*(.*?)(?=[\"'\\s]|$)", Pattern.CASE_INSENSITIVE),
            )
    }

    override fun convert(event: ILoggingEvent): String {
        var message = event.formattedMessage

        // Apply each pattern and mask the sensitive data
        PATTERNS.forEach { pattern ->
            val matcher = pattern.matcher(message)
            val buffer = StringBuffer()

            while (matcher.find()) {
                val replacement =
                    when (matcher.groupCount()) {
                        // If there are capturing groups, preserve the first group (the key/identifier)
                        // and mask the second group (the actual sensitive value)
                        2 -> "${matcher.group(1)}=********"
                        // For simple patterns with just one group, mask the entire match
                        else -> "********"
                    }

                matcher.appendReplacement(buffer, replacement)
            }

            matcher.appendTail(buffer)
            message = buffer.toString()
        }

        return message
    }
}
