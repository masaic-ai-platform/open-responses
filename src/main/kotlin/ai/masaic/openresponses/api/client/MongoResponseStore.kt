package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.InputMessageItem
import ai.masaic.openresponses.tool.ToolRequestContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import mu.KotlinLogging
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import kotlin.jvm.optionals.getOrNull

/**
 * MongoDB implementation of ResponseStore.
 * Stores responses and their input items in MongoDB collections.
 * Uses reactive MongoDB with Kotlin coroutines for non-blocking operations.
 */
class MongoResponseStore(
    private val mongoTemplate: ReactiveMongoTemplate,
    private val objectMapper: ObjectMapper,
) : ResponseStore {
    private val logger = KotlinLogging.logger {}

    /**
     * Document class for storing responses and their input items in MongoDB.
     */
    @Document(collection = "responses")
    data class ResponseDocument(
        @Id val id: String,
        val responseJson: String,
        val inputItems: List<InputMessageItem>,
        val outputInputItems: List<InputMessageItem>,
    )

    override suspend fun storeResponse(
        response: Response,
        inputItems: List<ResponseInputItem>,
        context: ToolRequestContext,
    ) {
        val responseId = response.id()
        logger.debug { "Storing response with ID: $responseId in MongoDB" }

        val inputMessageItems: List<InputMessageItem> =
            inputItems.map {
                objectMapper.convertValue(it, InputMessageItem::class.java)
            }

        val outputMessageItems: List<InputMessageItem> =
            response
                .output()
                .mapNotNull { outputItem ->
                    when {
                        // Handle regular messages
                        outputItem.isMessage() && outputItem.message().orElse(null) != null -> {
                            objectMapper.convertValue(outputItem.message().get(), InputMessageItem::class.java)
                        }
                        // Handle function calls
                        outputItem.isFunctionCall() -> {
                            val functionCall = outputItem.asFunctionCall()
                            InputMessageItem(
                                id = (functionCall.id().getOrNull() ?: functionCall.id()).toString(),
                                role = "assistant",
                                type = "function_call",
                                call_id = functionCall.callId(),
                                name = functionCall.name(),
                                arguments = functionCall.arguments(),
                            )
                        }
                        else -> null
                    }
                }

        // Serialize Response to JSON string for MongoDB storage
        val responseJson = objectMapper.writeValueAsString(response)

        val existingDoc = mongoTemplate.findById(responseId, ResponseDocument::class.java).awaitFirstOrNull()

        if (existingDoc != null) {
            logger.debug { "Response with ID: $responseId already exists in MongoDB. Updating existing document" }
            mongoTemplate
                .save(
                    existingDoc.copy(
                        responseJson = responseJson,
                        inputItems = existingDoc.inputItems.plus(inputMessageItems.filter { it !in existingDoc.inputItems }),
                        outputInputItems = existingDoc.outputInputItems.plus(outputMessageItems.filter { it !in existingDoc.outputInputItems }),
                    ),
                    "responses",
                ).awaitFirst()
        } else {
            logger.debug { "Response with ID: $responseId does not exist in MongoDB. Creating new document" }

            // Create document for MongoDB
            val document =
                ResponseDocument(
                    id = responseId,
                    responseJson = responseJson,
                    inputItems = inputMessageItems,
                    outputInputItems = outputMessageItems,
                )
            // Save to MongoDB
            mongoTemplate.save(document, "responses").awaitFirst()
        }
    }

    override suspend fun getResponse(responseId: String): Response? {
        logger.debug { "Retrieving response with ID: $responseId from MongoDB" }
        
        val document = mongoTemplate.findById(responseId, ResponseDocument::class.java).awaitFirstOrNull()
        return document?.let {
            objectMapper.readValue(it.responseJson, Response::class.java)
        }
    }

    override suspend fun getInputItems(responseId: String): List<InputMessageItem> {
        logger.debug { "Retrieving input items for response with ID: $responseId from MongoDB" }
        
        val document = mongoTemplate.findById(responseId, ResponseDocument::class.java).awaitFirstOrNull()
        return document?.inputItems ?: emptyList()
    }

    override suspend fun deleteResponse(responseId: String): Boolean {
        logger.debug { "Deleting response with ID: $responseId from MongoDB" }
        
        val query = Query(Criteria.where("_id").`is`(responseId))
        val result = mongoTemplate.remove(query, ResponseDocument::class.java).awaitFirst()
        
        return result.deletedCount > 0
    }

    override suspend fun getOutputItems(responseId: String): List<InputMessageItem> {
        val document = mongoTemplate.findById(responseId, ResponseDocument::class.java).awaitFirstOrNull()
        return document?.outputInputItems ?: emptyList()
    }
}
