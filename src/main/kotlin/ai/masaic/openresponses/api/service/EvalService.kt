package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.model.*
import ai.masaic.openresponses.api.repository.EvalRepository
import ai.masaic.openresponses.api.utils.SampleSchemaUtils
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.jsonSchema.JsonSchema
import com.fasterxml.jackson.module.jsonSchema.JsonSchemaGenerator
import com.fasterxml.jackson.module.jsonSchema.factories.SchemaFactoryWrapper
import com.openai.core.JsonValue
import org.springframework.stereotype.Service
import java.lang.UnsupportedOperationException
import java.time.Instant
import java.util.*

/**
 * Service for managing evaluations.
 */
@Service
class EvalService(private val evalRepository: EvalRepository, private val objectMapper: ObjectMapper) {
    // Initialize the schema generator
    private val schemaGenerator: JsonSchemaGenerator = JsonSchemaGenerator(objectMapper)

    /**
     * Create a new evaluation.
     *
     * @param request The evaluation creation request
     * @return The created evaluation
     */
    fun createEval(request: CreateEvalRequest): Eval {
        val dataSourceConfigRequest = request.dataSourceConfig

        val dataSourceConfig = when {
            dataSourceConfigRequest is CustomDataSourceConfigRequest -> {
                // Input schema
                val inputSchema = dataSourceConfigRequest.schema

                // Create properties map for our schema
                val propertiesMap = mutableMapOf<String, Any>(
                    "item" to mapOf(
                        "type" to "object",
                        "properties" to (inputSchema["properties"] ?: emptyMap<String, Any>()),
                        "required" to (inputSchema["required"] ?: emptyList<String>())
                    )
                )

                // Create required array for schema
                val requiredArray = mutableListOf("item")

                // Include sample schema if requested
                if (dataSourceConfigRequest.includeSampleSchema) {
                    propertiesMap["sample"] = SampleSchemaUtils.sampleSchemaForEvals()
                    requiredArray.add("sample") // Add sample to required array when included
                }

                // Build the final schema
                val schema = mapOf(
                    "type" to JsonValue.from("object"),
                    "properties" to JsonValue.from(propertiesMap),
                    "required" to JsonValue.from(requiredArray)
                )

                // Create the data source config
                CustomDataSourceConfig(schema)
            }
            dataSourceConfigRequest is StoredCompletionsDataSourceConfig -> {
                StoredCompletionsDataSourceConfig(dataSourceConfigRequest.metadata)
            }
            else -> {
                throw UnsupportedOperationException("Only CustomDataSourceConfigRequest and StoredCompletionsDataSourceConfig type of dataSourceConfig supported")
            }
        }

        // Generate IDs for testing criteria
        val testingCriteriaWithIds = request.testingCriteria.map { criterion ->
            when (criterion) {
                is LabelModelGrader -> criterion.copy(
                    id = "${criterion.name}-${UUID.randomUUID()}"
                )
                is StringCheckGrader -> criterion.copy(
                    id = "${criterion.name}-${UUID.randomUUID()}"
                )
                is TextSimilarityGrader -> criterion.copy(
                    id = "${criterion.name}-${UUID.randomUUID()}"
                )
                else -> throw UnsupportedOperationException("Unknown testing criterion type: ${criterion.javaClass.simpleName}")
            }
        }

        // Create the evaluation
        val eval = Eval(
            id = "",
            name = request.name,
            createdAt = Instant.now().epochSecond,
            dataSourceConfig = dataSourceConfig,
            testingCriteria = testingCriteriaWithIds,
            metadata = request.metadata ?: emptyMap()
        )

        return evalRepository.createEval(eval)
    }

    
    /**
     * Get an evaluation by ID.
     *
     * @param evalId The ID of the evaluation to retrieve
     * @return The evaluation, or null if not found
     */
    fun getEval(evalId: String): Eval? {
        return evalRepository.getEval(evalId)
    }
    
    /**
     * List all evaluations.
     *
     * @return A list of all evaluations
     */
    fun listEvals(): List<Eval> {
        return evalRepository.listEvals()
    }
    
    /**
     * List evaluations with pagination and filtering.
     *
     * @param params The parameters for listing evaluations
     * @return A paginated list of evaluations that match the criteria
     */
    fun listEvals(params: ListEvalsParams): EvalListResponse {
        val evals = evalRepository.listEvals(params)
        
        // Check if there are more results after the current page
        val hasMore = evals.size >= params.limit
        
        // Get the IDs of the first and last evaluations in the results
        val firstId = evals.firstOrNull()?.id
        val lastId = evals.lastOrNull()?.id
        
        return EvalListResponse(
            data = evals,
            hasMore = hasMore,
            firstId = firstId,
            lastId = lastId,
            limit = params.limit
        )
    }
    
    /**
     * Delete an evaluation.
     *
     * @param evalId The ID of the evaluation to delete
     * @return True if the evaluation was deleted, false otherwise
     */
    fun deleteEval(evalId: String): Boolean {
        return evalRepository.deleteEval(evalId)
    }
} 
