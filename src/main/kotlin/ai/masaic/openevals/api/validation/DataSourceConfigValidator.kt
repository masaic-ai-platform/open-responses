package ai.masaic.openevals.api.validation

import ai.masaic.openevals.api.model.CustomDataSourceConfigRequest
import ai.masaic.openevals.api.model.DataSourceConfig
import ai.masaic.openevals.api.model.StoredCompletionsDataSourceConfig

/**
 * Validator for DataSourceConfig objects.
 * Handles validation of custom data source configurations.
 */
class DataSourceConfigValidator {
    /**
     * Validates the data source configuration.
     *
     * @param dataSourceConfig The data source configuration to validate
     * @throws IllegalArgumentException if validation fails
     */
    fun validate(dataSourceConfig: DataSourceConfig) {
        when (dataSourceConfig) {
            is CustomDataSourceConfigRequest -> validateCustomDataSourceConfig(dataSourceConfig)
            is StoredCompletionsDataSourceConfig -> {
                // No additional validation for StoredCompletionsDataSourceConfig at this time
            }
            else -> throw IllegalArgumentException("Unsupported data source config type: ${dataSourceConfig.javaClass.simpleName}")
        }
    }

    /**
     * Validates a custom data source configuration.
     *
     * @param config The custom data source configuration to validate
     * @throws IllegalArgumentException if validation fails
     */
    private fun validateCustomDataSourceConfig(config: CustomDataSourceConfigRequest) {
        val schema = config.schema
        
        // Validate that item_schema.type is "object"
        val type = schema["type"] ?: throw IllegalArgumentException("Missing required field: item_schema.type")
        if (type !is String || type != "object") {
            throw IllegalArgumentException("item_schema.type must be 'object'")
        }
        
        // Validate that item_schema.properties has at least one property
        val properties = schema["properties"] ?: throw IllegalArgumentException("Missing required field: item_schema.properties")
        if (properties !is Map<*, *> || properties.isEmpty()) {
            throw IllegalArgumentException("item_schema.properties must contain at least one property")
        }
        
        // Validate that item_schema.required has at least one value matching a key in item_schema.properties
        val required = schema["required"] ?: throw IllegalArgumentException("Missing required field: item_schema.required")
        if (required !is List<*> || required.isEmpty()) {
            throw IllegalArgumentException("item_schema.required must contain at least one value")
        }
        
        // Check if at least one required field is in properties
        val propertyKeys = properties.keys.map { it.toString() }
        val requiredList = required.map { it.toString() }
        
        val matchingKeys = propertyKeys.intersect(requiredList.toSet())
        if (matchingKeys.isEmpty()) {
            throw IllegalArgumentException("At least one value in item_schema.required must match a key in item_schema.properties")
        }
    }
} 
