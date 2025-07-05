package ai.masaic.openevals.api.service

import ai.masaic.openevals.api.model.*
import ai.masaic.openevals.api.repository.EvalRepository
import ai.masaic.openevals.api.validation.DataSourceConfigValidator
import com.openai.core.JsonValue
import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap
import java.time.Instant
import java.util.*

/**
 * Service for mana ging evaluations.
 * Uses suspend functions for non-blocking operations.
 */
@Service
class EvalService(
    private val evalRepository: EvalRepository,
) {
    private val dataSourceConfigValidator = DataSourceConfigValidator()

    /**
     * Create a new evaluation.
     *
     * @param request The evaluation creation request
     * @return The created evaluation
     */
    suspend fun createEval(
        request: CreateEvalRequest,
        headers: MultiValueMap<String, String>,
    ): Eval {
        val dataSourceConfigRequest = request.dataSourceConfig
        
        // Validate the data source config
        dataSourceConfigValidator.validate(dataSourceConfigRequest)

        val dataSourceConfig =
            when {
                dataSourceConfigRequest is CustomDataSourceConfigRequest -> {
                    // Input schema
                    val inputSchema = dataSourceConfigRequest.schema

                    // Create properties map for our schema
                    val propertiesMap =
                        mutableMapOf<String, Any>(
                            "item" to
                                mapOf(
                                    "type" to "object",
                                    "properties" to (inputSchema["properties"] ?: emptyMap<String, Any>()),
                                    "required" to (inputSchema["required"] ?: emptyList<String>()),
                                ),
                        )

                    // Create required array for schema
                    val requiredArray = mutableListOf("item")

                    // Build the final schema
                    val schema =
                        mapOf(
                            "type" to JsonValue.from("object"),
                            "properties" to JsonValue.from(propertiesMap),
                            "required" to JsonValue.from(requiredArray),
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
        val testingCriteriaWithIds =
            request.testingCriteria.map { criterion ->
                when (criterion) {
                    is LabelModelGrader ->
                        criterion.copy(
                            id = "${criterion.name}-${UUID.randomUUID()}",
                            apiKey = EvalRunService.extractApiKey(headers),
                        )
                    is StringCheckGrader ->
                        criterion.copy(
                            id = "${criterion.name}-${UUID.randomUUID()}",
                        )
                    is TextSimilarityGrader ->
                        criterion.copy(
                            id = "${criterion.name}-${UUID.randomUUID()}",
                        )
                    else -> throw UnsupportedOperationException("Unknown testing criterion type: ${criterion.javaClass.simpleName}")
                }
            }

        // Create the evaluation
        val eval =
            Eval(
                id = "",
                name = request.name,
                createdAt = Instant.now().epochSecond,
                dataSourceConfig = dataSourceConfig,
                testingCriteria = testingCriteriaWithIds,
                metadata = request.metadata ?: emptyMap(),
            )

        return evalRepository.createEval(eval)
    }

    /**
     * Get an evaluation by ID.
     *
     * @param evalId The ID of the evaluation to retrieve
     * @return The evaluation, or null if not found
     */
    suspend fun getEval(evalId: String): Eval? = evalRepository.getEval(evalId)

    /**
     * List all evaluations.
     *
     * @return A list of all evaluations
     */
    suspend fun listEvals(): List<Eval> = evalRepository.listEvals()

    /**
     * List evaluations with pagination and filtering.
     *
     * @param params The parameters for listing evaluations
     * @return A paginated list of evaluations that match the criteria
     */
    suspend fun listEvals(params: ListEvalsParams): EvalListResponse {
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
            limit = params.limit,
        )
    }

    /**
     * Delete an evaluation.
     *
     * @param evalId The ID of the evaluation to delete
     * @return True if the evaluation was deleted, false otherwise
     */
    suspend fun deleteEval(evalId: String): Boolean = evalRepository.deleteEval(evalId)

    /**
     * Update an evaluation.
     *
     * @param evalId The ID of the evaluation to update
     * @param request The update request containing fields to update
     * @return The updated evaluation, or null if not found
     */
    suspend fun updateEval(
        evalId: String,
        request: UpdateEvalRequest,
    ): Eval? {
        // Get the existing eval
        val existingEval = evalRepository.getEval(evalId) ?: return null
        
        // Update only the fields that are provided in the request
        val updatedEval =
            existingEval.copy(
                name = request.name ?: existingEval.name,
                metadata = request.metadata ?: existingEval.metadata,
            )
        
        // Save the updated eval
        return evalRepository.updateEval(updatedEval)
    }
} 
