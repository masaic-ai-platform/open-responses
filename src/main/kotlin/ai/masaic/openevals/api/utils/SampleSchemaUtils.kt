package ai.masaic.openevals.api.utils

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.core.JsonValue

class SampleSchemaUtils {
    companion object {
        private val mapper = jacksonObjectMapper()

        /**
         * Generates a JSON schema for the ChatCompletion class structure
         * to exactly match the expected format
         */
        fun schemaForEvalConfig() =
            mapper.readValue<Map<String, Any>>(
                """
                {
                         "type": "object",
                         "properties": {
                             "model": {
                                 "type": "string"
                             },
                             "choices": {
                                 "type": "array",
                                 "items": {
                                     "type": "object",
                                     "properties": {
                                         "message": {
                                             "type": "object",
                                             "properties": {
                                                 "role": {
                                                     "type": "string",
                                                     "enum": [
                                                         "assistant"
                                                     ]
                                                 },
                                                 "content": {
                                                     "type": [
                                                         "string",
                                                         "null"
                                                     ]
                                                 },
                                                 "refusal": {
                                                     "type": [
                                                         "boolean",
                                                         "null"
                                                     ]
                                                 },
                                                 "tool_calls": {
                                                     "type": [
                                                         "array",
                                                         "null"
                                                     ],
                                                     "items": {
                                                         "type": "object",
                                                         "properties": {
                                                             "type": {
                                                                 "type": "string",
                                                                 "enum": [
                                                                     "function"
                                                                 ]
                                                             },
                                                             "function": {
                                                                 "type": "object",
                                                                 "properties": {
                                                                     "name": {
                                                                         "type": "string"
                                                                     },
                                                                     "arguments": {
                                                                         "type": "string"
                                                                     }
                                                                 },
                                                                 "required": [
                                                                     "name",
                                                                     "arguments"
                                                                 ]
                                                             },
                                                             "id": {
                                                                 "type": "string"
                                                             }
                                                         },
                                                         "required": [
                                                             "type",
                                                             "function",
                                                             "id"
                                                         ]
                                                     }
                                                 },
                                                 "function_call": {
                                                     "type": [
                                                         "object",
                                                         "null"
                                                     ],
                                                     "properties": {
                                                         "name": {
                                                             "type": "string"
                                                         },
                                                         "arguments": {
                                                             "type": "string"
                                                         }
                                                     },
                                                     "required": [
                                                         "name",
                                                         "arguments"
                                                     ]
                                                 }
                                             },
                                             "required": [
                                                 "role"
                                             ]
                                         },
                                         "finish_reason": {
                                             "type": "string"
                                         }
                                     },
                                     "required": [
                                         "index",
                                         "message",
                                         "finish_reason"
                                     ]
                                 }
                             },
                             "output_text": {
                                 "type": "string"
                             },
                             "output_json": {
                                 "type": "object"
                             },
                             "output_tools": {
                                 "type": "array",
                                 "items": {
                                     "type": "object"
                                 }
                             }
                         },
                         "required": [
                             "model",
                             "choices"
                         ]
                     } 
                """.trimIndent(),
            )

        fun schemaForModelLabeler(): Map<String, JsonValue> {
            val labelSchema =
                mapper.readValue<Map<String, Any>>(
                    """
                    {
                             "type": "object",
                             "properties": {
                                 "label": {
                                     "title": "Label",
                                     "type": "string"
                                 }
                             },
                             "required": [
                                "label"
                             ]
                         } 
                    """.trimIndent(),
                )

            // Create properties map for our schema
            val propertiesMap =
                mutableMapOf<String, Any>(
                    "item" to labelSchema,
                )

            // Build the final schema
            return mapOf(
                "type" to JsonValue.from("object"),
                "properties" to JsonValue.from(propertiesMap),
                "required" to JsonValue.from(listOf("item")),
            )
        }
    }
} 
