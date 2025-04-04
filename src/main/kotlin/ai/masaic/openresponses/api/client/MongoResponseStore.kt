package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.model.InputMessageItem
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseInputItem
import mu.KotlinLogging
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

/**
 * MongoDB implementation of ResponseStore.
 * Stores responses and their input items in MongoDB collections.
 */
class MongoResponseStore(
    private val mongoTemplate: MongoTemplate,
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

    override fun storeResponse(
        response: Response,
        inputItems: List<ResponseInputItem>,
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
                .mapNotNull {
                    it.message().orElse(null)
                }.map {
                    objectMapper.convertValue(it, InputMessageItem::class.java)
                }

        // Serialize Response to JSON string for MongoDB storage
        val responseJson = objectMapper.writeValueAsString(response)

        val existingDoc = mongoTemplate.findById(responseId, ResponseDocument::class.java)

        if (existingDoc != null) {
            logger.debug { "Response with ID: $responseId already exists in MongoDB. Updating existing document" }
            mongoTemplate.save(
                existingDoc.copy(
                    responseJson = responseJson,
                    inputItems = existingDoc.inputItems.plus(inputMessageItems),
                    outputInputItems = existingDoc.outputInputItems.plus(outputMessageItems),
                ),
                "responses",
            )
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
            mongoTemplate.save(document, "responses")
        }
    }

    override fun getResponse(responseId: String): Response? {
        logger.debug { "Retrieving response with ID: $responseId from MongoDB" }
        
        val document = mongoTemplate.findById(responseId, ResponseDocument::class.java)
        return document?.let {
            objectMapper.readValue(it.responseJson, Response::class.java)
        }
    }

    override fun getInputItems(responseId: String): List<InputMessageItem> {
        logger.debug { "Retrieving input items for response with ID: $responseId from MongoDB" }
        
        val document = mongoTemplate.findById(responseId, ResponseDocument::class.java)
        return document?.inputItems ?: emptyList()
    }

    override fun deleteResponse(responseId: String): Boolean {
        logger.debug { "Deleting response with ID: $responseId from MongoDB" }
        
        val query = Query(Criteria.where("_id").`is`(responseId))
        val result = mongoTemplate.remove(query, ResponseDocument::class.java)
        
        return result.deletedCount > 0
    }

    override fun getOutputItems(responseId: String): List<InputMessageItem> {
        val document = mongoTemplate.findById(responseId, ResponseDocument::class.java)
        return document?.outputInputItems ?: emptyList()
    }
}
