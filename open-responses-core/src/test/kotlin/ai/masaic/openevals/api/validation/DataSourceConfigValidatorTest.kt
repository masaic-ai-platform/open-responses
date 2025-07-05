package ai.masaic.openevals.api.validation

import ai.masaic.openevals.api.model.CustomDataSourceConfigRequest
import ai.masaic.openevals.api.model.StoredCompletionsDataSourceConfig
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DataSourceConfigValidatorTest {
    private val validator = DataSourceConfigValidator()

    @Test
    fun `validate should accept valid CustomDataSourceConfigRequest`() {
        val validSchema =
            mapOf(
                "type" to "object",
                "properties" to
                    mapOf(
                        "notifications" to mapOf("type" to "string"),
                        "notificationSummary" to mapOf("type" to "string"),
                    ),
                "required" to listOf("notifications"),
            )
        
        val config =
            CustomDataSourceConfigRequest(
                schema = validSchema,
                includeSampleSchema = true,
            )
        
        assertDoesNotThrow { validator.validate(config) }
    }

    @Test
    fun `validate should throw when type is missing`() {
        val invalidSchema =
            mapOf(
                "properties" to
                    mapOf(
                        "notifications" to mapOf("type" to "string"),
                    ),
                "required" to listOf("notifications"),
            )
        
        val config =
            CustomDataSourceConfigRequest(
                schema = invalidSchema,
                includeSampleSchema = true,
            )
        
        val exception =
            assertThrows(IllegalArgumentException::class.java) { 
                validator.validate(config) 
            }
        
        assert(exception.message!!.contains("type"))
    }

    @Test
    fun `validate should throw when type is not object`() {
        val invalidSchema =
            mapOf(
                "type" to "array",
                "properties" to
                    mapOf(
                        "notifications" to mapOf("type" to "string"),
                    ),
                "required" to listOf("notifications"),
            )
        
        val config =
            CustomDataSourceConfigRequest(
                schema = invalidSchema,
                includeSampleSchema = true,
            )
        
        val exception =
            assertThrows(IllegalArgumentException::class.java) { 
                validator.validate(config) 
            }
        
        assert(exception.message!!.contains("type must be 'object'"))
    }

    @Test
    fun `validate should throw when properties is missing`() {
        val invalidSchema =
            mapOf(
                "type" to "object",
                "required" to listOf("notifications"),
            )
        
        val config =
            CustomDataSourceConfigRequest(
                schema = invalidSchema,
                includeSampleSchema = true,
            )
        
        val exception =
            assertThrows(IllegalArgumentException::class.java) { 
                validator.validate(config) 
            }
        
        assert(exception.message!!.contains("properties"))
    }

    @Test
    fun `validate should throw when properties is empty`() {
        val invalidSchema =
            mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>(),
                "required" to listOf("notifications"),
            )
        
        val config =
            CustomDataSourceConfigRequest(
                schema = invalidSchema,
                includeSampleSchema = true,
            )
        
        val exception =
            assertThrows(IllegalArgumentException::class.java) { 
                validator.validate(config) 
            }
        
        assert(exception.message!!.contains("properties must contain at least one property"))
    }

    @Test
    fun `validate should throw when required is missing`() {
        val invalidSchema =
            mapOf(
                "type" to "object",
                "properties" to
                    mapOf(
                        "notifications" to mapOf("type" to "string"),
                    ),
            )
        
        val config =
            CustomDataSourceConfigRequest(
                schema = invalidSchema,
                includeSampleSchema = true,
            )
        
        val exception =
            assertThrows(IllegalArgumentException::class.java) { 
                validator.validate(config) 
            }
        
        assert(exception.message!!.contains("required"))
    }

    @Test
    fun `validate should throw when required is empty`() {
        val invalidSchema =
            mapOf(
                "type" to "object",
                "properties" to
                    mapOf(
                        "notifications" to mapOf("type" to "string"),
                    ),
                "required" to emptyList<String>(),
            )
        
        val config =
            CustomDataSourceConfigRequest(
                schema = invalidSchema,
                includeSampleSchema = true,
            )
        
        val exception =
            assertThrows(IllegalArgumentException::class.java) { 
                validator.validate(config) 
            }
        
        assert(exception.message!!.contains("required must contain at least one value"))
    }

    @Test
    fun `validate should throw when no required field matches any property`() {
        val invalidSchema =
            mapOf(
                "type" to "object",
                "properties" to
                    mapOf(
                        "notifications" to mapOf("type" to "string"),
                    ),
                "required" to listOf("nonExistentField"),
            )
        
        val config =
            CustomDataSourceConfigRequest(
                schema = invalidSchema,
                includeSampleSchema = true,
            )
        
        val exception =
            assertThrows(IllegalArgumentException::class.java) { 
                validator.validate(config) 
            }
        
        assert(exception.message!!.contains("must match a key in item_schema.properties"))
    }

    @Test
    fun `validate should accept StoredCompletionsDataSourceConfig`() {
        val config =
            StoredCompletionsDataSourceConfig(
                metadata = mapOf("key" to "value"),
            )
        
        assertDoesNotThrow { validator.validate(config) }
    }
} 
