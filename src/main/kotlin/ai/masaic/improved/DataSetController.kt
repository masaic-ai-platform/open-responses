package ai.masaic.improved

import ai.masaic.improved.model.Conversation
import ai.masaic.improved.model.Label
import ai.masaic.improved.model.Message
import ai.masaic.improved.model.Role
import ai.masaic.improved.repository.ConversationRepository
import ai.masaic.openevals.api.model.EvalRun
import ai.masaic.openevals.api.model.ModelAnnotator
import ai.masaic.openevals.api.model.SimpleInputMessage
import ai.masaic.openevals.api.service.ModelClientService
import ai.masaic.openevals.api.service.runner.JSON_SCHEMA
import ai.masaic.openresponses.api.controller.CompletionController
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.readValues
import com.openai.core.JsonValue
import com.openai.core.jsonMapper
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import mu.KotlinLogging
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.io.File
import java.io.FileReader
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import com.opencsv.CSVReader
import java.lang.Exception
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource

@RestController
@RequestMapping("/v1")
class DataSetController(
    private val modelClientService: ModelClientService, 
    private val conversationRepository: ConversationRepository
) {
    private val logger = KotlinLogging.logger {}
    
    @Value("\${dataset.csv.path:/Users/jasbirsingh/Downloads/Shortlisted-DataSet.csv}")
    private lateinit var csvFilePath: String
    
    // Data class to deserialize the model response
    data class ConversationResponse(
        val messages: List<MessageResponse>
    )
    
    data class MessageResponse(
        val role: String,
        val content: String
    )
    
    // Data class to represent a row in the CSV
    data class DataSetEntry(
        val flags: String,
        val instruction: String,
        val category: String,
        val intent: String,
        val response: String
    )
    
    @PostMapping("/data/generation", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun createDataSet(
        @RequestHeader headers: MultiValueMap<String, String>,
        @RequestParam queryParams: MultiValueMap<String, String>,
        @RequestParam(required = false) index: Int? = null,
        @RequestParam(required = false, defaultValue = "false") processAll: Boolean = false,
        @RequestBody(required = false) requestBody: ProcessRangeRequest? = null
    ): ResponseEntity<*> {
        try {
            // Load entries from CSV file
            val entries = loadEntriesFromCsv()
            if (entries.isEmpty()) {
                return ResponseEntity.badRequest().body("No entries found in CSV file")
            }
            
            // Process a range of entries, all entries, or a single entry based on the parameters
            if (requestBody != null && requestBody.startIndex != null && requestBody.endIndex != null) {
                // Process a range of entries
                val startIndex = requestBody.startIndex.coerceAtLeast(0)
                val endIndex = requestBody.endIndex.coerceAtMost(entries.size - 1)
                
                if (startIndex > endIndex) {
                    return ResponseEntity.badRequest().body("startIndex must be less than or equal to endIndex")
                }
                
                logger.info { "Processing entries from index $startIndex to $endIndex" }
                val results = mutableListOf<String>()
                
                // Process each entry in the specified range
                for (entryIndex in startIndex..endIndex) {
                    val entry = entries[entryIndex]
                    try {
                        logger.info { "Processing entry $entryIndex/${entries.size}: Category=${entry.category}, Intent=${entry.intent}" }
                        
                        // Generate and save conversation for this entry
                        val conversation = generateConversationForEntry(entry)
                        val savedConversation = conversationRepository.createConversation(conversation)
                        
                        results.add("Successfully processed entry $entryIndex: ${savedConversation.id}")
                    } catch (e: Exception) {
                        logger.error(e) { "Error processing entry $entryIndex: ${entry.instruction}" }
                        results.add("Failed to process entry $entryIndex: ${e.message}")
                    }
                }
                
                logger.info { "Completed processing entries from index $startIndex to $endIndex" }
                return ResponseEntity.ok().body(mapOf(
                    "message" to "Processed entries from index $startIndex to $endIndex",
                    "totalProcessed" to (endIndex - startIndex + 1),
                    "results" to results
                ))
            } else if (processAll) {
                logger.info { "Processing all ${entries.size} entries from dataset" }
                val results = mutableListOf<String>()
                
                // Process each entry in the dataset
                for ((entryIndex, entry) in entries.withIndex()) {
                    try {
                        logger.info { "Processing entry $entryIndex/${entries.size}: Category=${entry.category}, Intent=${entry.intent}" }
                        
                        // Generate and save conversation for this entry
                        val conversation = generateConversationForEntry(entry)
                        val savedConversation = conversationRepository.createConversation(conversation)
                        
                        results.add("Successfully processed entry $entryIndex: ${savedConversation.id}")
                    } catch (e: Exception) {
                        logger.error(e) { "Error processing entry $entryIndex: ${entry.instruction}" }
                        results.add("Failed to process entry $entryIndex: ${e.message}")
                    }
                }
                
                logger.info { "Completed processing all entries" }
                return ResponseEntity.ok().body(mapOf(
                    "message" to "Processed ${entries.size} entries",
                    "results" to results
                ))
            } else {
                // Process a single entry (either random or specified by index)
                val entry = if (index != null && index < entries.size) {
                    entries[index]
                } else {
                    entries.random()
                }
                
                logger.info { "Selected entry - Category: ${entry.category}, Intent: ${entry.intent}" }
                
                // Generate and save conversation for this entry
                val conversation = generateConversationForEntry(entry)
                val savedConversation = conversationRepository.createConversation(conversation)
                
                return ResponseEntity.ok().body("processed")
            }
        } catch (e: Exception) {
            logger.error(e) { "Error generating conversation from dataset" }
            return ResponseEntity.internalServerError().body("Error: ${e.message}")
        }
    }
    
    private fun loadEntriesFromCsv(): List<DataSetEntry> {
        val entries = mutableListOf<DataSetEntry>()
        
        try {
            logger.info { "Loading dataset from: $csvFilePath" }
            val reader = CSVReader(FileReader(csvFilePath, StandardCharsets.UTF_8))
            
            // Skip header
            val header = reader.readNext()
            if (header == null || header.size < 5) {
                logger.error { "Invalid CSV header" }
                return entries
            }
            
            var line: Array<String>?
            while (reader.readNext().also { line = it } != null) {
                if (line!!.size >= 5 && line!![2].isNotBlank() && line!![3].isNotBlank()) {
                    val entry = DataSetEntry(
                        flags = line!![0],
                        instruction = line!![1],
                        category = line!![2],
                        intent = line!![3],
                        response = line!![4]
                    )
                    entries.add(entry)
                }
            }
            
            reader.close()
            logger.info { "Loaded ${entries.size} entries from CSV" }
        } catch (e: Exception) {
            logger.error(e) { "Error loading CSV file" }
        }
        
        return entries
    }

    private fun callModel(
        prompt: String,
    ): String {
        // Create completion params and execute with cached client
        val builder = modelClientService.createBasicCompletionParams("openai@gpt-4o-mini")
        .responseFormat(addJsonSchema())
        addSimpleInputMessagesToBuilder(builder, prompt)

        val completionResult =
            modelClientService.executeWithClientAndErrorHandling(
                apiKey = System.getenv("OPENAI_API_KEY"),
                params = builder.build(),
                identifier = "abcd",
            ) { content, error ->
                ai.masaic.openevals.api.model.CompletionResult(
                    contentJson = content,
                    error = error,
                )
            }

        // Check for errors
        if (completionResult.error != null) {
            throw RuntimeException("Error calling label model: ${completionResult.error}")
        }

        val response = completionResult.contentJson
        return response
    }

    private fun addSimpleInputMessagesToBuilder(
        builder: ChatCompletionCreateParams.Builder,
       prompt: String
    ): ChatCompletionCreateParams.Builder {

        builder.addMessage(
        ChatCompletionSystemMessageParam
            .builder()
            .content(prompt)
            .build())
        return builder
    }

    private fun addJsonSchema(): ResponseFormatJsonSchema {
        val jsonSchema =
            ResponseFormatJsonSchema.JsonSchema.Schema
                .builder()
                .additionalProperties(jacksonObjectMapper().readValue<Map<String, JsonValue>>(responseFormat))
                .build()

        val format =
            ResponseFormatJsonSchema.JsonSchema
                .builder()
                .schema(jsonSchema)
                .name("conversationSchema")
                .build()

        return ResponseFormatJsonSchema
            .builder()
            .type(JsonValue.from("json_schema"))
            .jsonSchema(format)
            .build()
    }
    
    /**
     * Generates a random timestamp between two Instant values.
     *
     * @param startInclusive The starting Instant (inclusive)
     * @param endInclusive The ending Instant (inclusive)
     * @return A random Instant between the two provided Instants
     */
    private fun generateRandomTimestamp(startInclusive: Instant, endInclusive: Instant): Instant {
        val startSeconds = startInclusive.epochSecond
        val endSeconds = endInclusive.epochSecond
        val randomSeconds = startSeconds + (Math.random() * (endSeconds - startSeconds)).toLong()
        
        // Add random nanoseconds for more randomness
        val randomNanos = (Math.random() * 999_999_999).toInt()
        
        return Instant.ofEpochSecond(randomSeconds, randomNanos.toLong())
    }

    /**
     * Generates a conversation for a specific dataset entry
     */
    private suspend fun generateConversationForEntry(entry: DataSetEntry): Conversation {
        val random = Math.random()
        val (numberOfTurns, userState) = if(random > 0.7) {
            Pair(5, "satisfied")
        } else if (random > 0.3 && random <= 0.6) {
            Pair(4, "unsatisfied")
        } else {
            Pair(3, "negative with sentiments")
        }
        
        val pointsToRemember = if(Math.random() > 0.4) {
            """
            1. most of the reply form user are abstract and few words rather than complete sentences.
            2. sometime reply from user is ambiguous and assistant can ask fro clarification. 
            3. End when the user is $userState.
            4. Some of the times user never explicitly state that he is not satisfied.
            """
        } else {
            """
            1. End when the user is $userState. 
            2. Some of the times user never explicitly state that he is not satisfied.                          
            """
        }

        val question = entry.instruction
        val answer = entry.response
        val systemPrompt = """
        System: You are a CS bot designer. 
        Combine the following Q‑A into a realistic multi‑turn chat with at least $numberOfTurns turns. 
        Points to remember:
        $pointsToRemember

        User: $question
        Assistant: $answer
        """

        val response = callModel(systemPrompt)
        
        // Deserialize response into List<Message>
        val objectMapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        
        val conversationResponse = objectMapper.readValue<ConversationResponse>(response)
        
        // Instantiate Conversation without labels, nps
        val messages = conversationResponse.messages.map { messageResponse ->
            Message(
                role = Role.fromString(messageResponse.role),
                text = messageResponse.content
            )
        }
        
        // Set resolved = true if userState = satisfied
        val resolved = userState == "satisfied"
        
        // Generate a random timestamp between May 1, 2025 and May 12, 2025
        val startDate = Instant.parse("2025-05-01T00:00:00Z")
        val endDate = Instant.parse("2025-05-12T23:59:59Z")
        val randomCreatedAt = generateRandomTimestamp(startDate, endDate)
        
        return Conversation(
            id = "conv_" + UUID.randomUUID().toString().replace("-", ""),
            createdAt = randomCreatedAt,
            messages = messages,
            labels = emptyList(),
            resolved = resolved,
            nps = null,
            meta = mapOf(
                "userState" to userState,
                "numberOfTurns" to numberOfTurns,
                "category" to entry.category,
                "intent" to entry.intent,
                "flags" to entry.flags
            ),
            version = 1
        )
    }
}

const val responseFormat = """
{
        "type": "object",
        "properties": {
          "messages": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "content": {
                  "type": "string"
                },
                "role": {
                  "type": "string",
                  "enum": [
                    "assistant",
                    "user"
                  ]
                }
              },
              "required": [
                "content",
                "role"
              ],
              "additionalProperties": false
            }
          }
        },
        "required": [
          "messages"
        ],
        "additionalProperties": false
      }
"""

/**
 * Request body for processing a range of entries
 */
data class ProcessRangeRequest(
    val startIndex: Int? = null,
    val endIndex: Int? = null
)
