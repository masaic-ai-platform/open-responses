package ai.masaic.platform.api.utils

import dev.langchain4j.model.chat.request.json.*

/** Utility functions for converting between Kotlin Maps (following JSON Schema) and LangChain4j JsonSchemaElement types. */
object JsonSchemaMapper {
    /**
     * Convert a `Map<String, Any>` representation of a JSON-Schema (same structure produced in ToolService)
     * into a LangChain4j [JsonSchemaElement] tree.
     */
    @Suppress("UNCHECKED_CAST")
    fun mapToJsonSchemaElement(schemaMap: Map<String, Any>): JsonSchemaElement =
        when (schemaMap["type"]) {
            "object" -> mapToObjectSchema(schemaMap)
            "array" -> mapToArraySchema(schemaMap)
            "string" -> JsonStringSchema.builder().description(schemaMap["description"] as? String).build()
            "integer" -> JsonIntegerSchema.builder().description(schemaMap["description"] as? String).build()
            "number" -> JsonNumberSchema.builder().description(schemaMap["description"] as? String).build()
            "boolean" -> JsonBooleanSchema.builder().description(schemaMap["description"] as? String).build()
            else -> JsonStringSchema.builder().build()
        }

    private fun mapToObjectSchema(map: Map<String, Any>): JsonObjectSchema {
        val builder = JsonObjectSchema.builder()
        (map["description"] as? String)?.let { builder.description(it) }
        (map["additionalProperties"] as? Boolean)?.let { builder.additionalProperties(it) }

        // properties
        val propertiesMap = mutableMapOf<String, JsonSchemaElement>()
        val props = map["properties"] as? Map<*, *> ?: emptyMap<Any, Any>()
        props.forEach { (key, value) ->
            if (key != null && value is Map<*, *>) {
                propertiesMap[key as String] = mapToJsonSchemaElement(value as Map<String, Any>)
            }
        }
        if (propertiesMap.isNotEmpty()) builder.properties(propertiesMap)

        // required
        val reqList = (map["required"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        if (reqList.isNotEmpty()) builder.required(reqList)

        return builder.build()
    }

    private fun mapToArraySchema(map: Map<String, Any>): JsonArraySchema {
        val builder = JsonArraySchema.builder()
        (map["description"] as? String)?.let { builder.description(it) }
        val items = map["items"] as? Map<*, *>
        if (items != null) {
            builder.items(mapToJsonSchemaElement(items as Map<String, Any>))
        }
        return builder.build()
    }
}
