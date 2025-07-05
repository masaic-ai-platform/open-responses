package ai.masaic.openevals.api.utils

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.mitchellbosecke.pebble.PebbleEngine
import org.slf4j.LoggerFactory
import java.io.StringWriter

/**
 * Utility class for template resolution operations.
 */
object TemplateUtils {
    private val logger = LoggerFactory.getLogger(TemplateUtils::class.java)
    private val objectMapper = jacksonObjectMapper()

    /**
     * Resolve a template value using Pebble.
     *
     * @param template The template string (e.g., "{{item.correct_label}}")
     * @param jsonStr The JSON string containing the context
     * @param pebbleEngine The PebbleEngine instance to use for template resolution
     * @return The resolved value
     */
    fun resolveTemplateValue(
        template: String,
        jsonStr: String,
        pebbleEngine: PebbleEngine,
    ): String {
        try {
            // If the JSON is an empty string, return an empty result
            if (jsonStr.isBlank()) {
                return ""
            }
            
            // Parse the JSON into a Map
            val contextMap = mutableMapOf<String, Any>()
            try {
                // Try to parse as JSON object first
                val jsonMap: Map<String, Any> = objectMapper.readValue(jsonStr)
                contextMap.putAll(jsonMap)
            } catch (e: Exception) {
                logger.warn("JSON parsing failed, using as plain text: ${e.message}")
                // If JSON parsing fails, treat the entire string as a value
                contextMap["content"] = jsonStr
            }

            // Compile and evaluate the template
            val compiledTemplate = pebbleEngine.getLiteralTemplate(template)
            val writer = StringWriter()
            compiledTemplate.evaluate(writer, contextMap)

            return writer.toString().trim()
        } catch (e: Exception) {
            logger.warn("Error resolving template '$template': ${e.message}")
            return ""
        }
    }
} 
