package ai.masaic.improved

import ai.masaic.improved.model.Conversation
import ai.masaic.improved.repository.ConversationRepository
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.core.JsonValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.bson.Document
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.BasicQuery
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class SamplingAgent(
    private val modelService: ModelService,
    private val conversationRepository: ConversationRepository,
    private val reactiveMongoTemplate: ReactiveMongoTemplate
) {
    private val logger = KotlinLogging.logger {}
    
    // Event flow for sending updates to the frontend
    private val _eventFlow = MutableSharedFlow<SamplingAgentEvent>(replay = 0)
    val eventFlow: Flow<SamplingAgentEvent> = _eventFlow.asSharedFlow()
    
    // Constants for limiting the agent's execution
    private val MAX_MODEL_CALLS = 10
    private val MAX_CONVERSATIONS_PER_BATCH = 10
    private val CHECK_INTERVAL_MS = 1000L // 1 second interval to check for stop command

    suspend fun run(apiKey: String, userInstructions: String = ""): String {
        logger.info { "Starting SamplingAgent with instructions: $userInstructions" }
        
        //Emit start event
        //E1 = Starting planning the task.
//        _eventFlow.emit(SamplingAgentEvent.Started(userInstructions))
        
        // State variables for the agentic loop
        var modelCallCount = 0
        var totalConversationsClassified = 0
        var shouldStop = false
        var targetSampleSize = 0
        
        // Create a plan based on user instructions
        //E1 = Starting planning the task.
        _eventFlow.emit(SamplingAgentEvent.Planning("Let me plan as per given instructions."))
        val plan = createPlan(apiKey, userInstructions)
        logger.info { "Created plan: $plan" }
        
        targetSampleSize = plan.targetSampleSize

        //E2 ... emit plan details.
        // Emit plan created event
        _eventFlow.emit(SamplingAgentEvent.PlanCreated(
            targetSampleSize = plan.targetSampleSize,
            mongoQueryMap = plan.mongoQueryMap,
            additionalInstructions = plan.additionalInstructions,

        ))

        //E3. Let me start work now...
        // Main agentic loop
        while (!shouldStop) {
            // Check termination conditions
            if (modelCallCount >= MAX_MODEL_CALLS) {
                logger.info { "Terminating: Maximum model call limit reached ($MAX_MODEL_CALLS)" }
                _eventFlow.emit(SamplingAgentEvent.Terminated(
                    reason = "Maximum model call limit of $MAX_MODEL_CALLS reached",
                    totalClassified = totalConversationsClassified,
                    targetSampleSize = targetSampleSize
                ))
                return "Termination: Maximum model call limit ($MAX_MODEL_CALLS) reached. Classified $totalConversationsClassified conversations."
            }
            
            if (totalConversationsClassified >= targetSampleSize) {
                logger.info { "Terminating: Target sample size reached ($targetSampleSize)" }
                _eventFlow.emit(SamplingAgentEvent.Terminated(
                    reason = "Target sample size of $targetSampleSize conversations reached",
                    totalClassified = totalConversationsClassified,
                    targetSampleSize = targetSampleSize
                ))
                return "Termination: Target sample size ($targetSampleSize) reached. Classified $totalConversationsClassified conversations."
            }
            
            // Check if the plan indicated we should stop based on user instructions
            if (plan.stopRequested) {
                logger.info { "Terminating: Stop requested in initial plan" }
                _eventFlow.emit(SamplingAgentEvent.Terminated(
                    reason = "Stopping the work.",
                    totalClassified = totalConversationsClassified,
                    targetSampleSize = targetSampleSize
                ))
                return "Termination: Stop requested. Classified $totalConversationsClassified conversations."
            }

            // Fetch conversations that don't have classification labels yet
            val batchSize = minOf(MAX_CONVERSATIONS_PER_BATCH, targetSampleSize - totalConversationsClassified)
            if (batchSize <= 0) {
                logger.info { "Terminating: No more conversations needed" }
                _eventFlow.emit(SamplingAgentEvent.Terminated(
                    reason = "No more conversations needed",
                    totalClassified = totalConversationsClassified,
                    targetSampleSize = targetSampleSize
                ))
                return "Termination: Target sample size reached. Classified $totalConversationsClassified conversations."
            }

            _eventFlow.emit(SamplingAgentEvent.FetchingConversations("Looking up the conversations from database"))
            val conversations = fetchUnclassifiedConversations(batchSize, plan.mongoQueryMap)
            
            if (conversations.isEmpty()) {
                logger.info { "No more unclassified conversations available matching the criteria" }
                _eventFlow.emit(SamplingAgentEvent.Terminated(
                    reason = "No conversations found for the given criteria",
                    totalClassified = totalConversationsClassified,
                    targetSampleSize = targetSampleSize
                ))
                return "Termination: No more conversations to classify. Classified $totalConversationsClassified conversations."
            }
            
            logger.info { "Fetched ${conversations.size} conversations for classification" }
            _eventFlow.emit(SamplingAgentEvent.ConversationsFetched(
                count = conversations.size,
                ids = conversations.map { it.id }
            ))
            
            // Classify conversations
            _eventFlow.emit(SamplingAgentEvent.Classifying("Classifying ${conversations.size} conversations"))
            val classifications = classifyConversations(apiKey, conversations, plan.additionalInstructions)
            modelCallCount++
            
            // Save classifications to the database
            _eventFlow.emit(SamplingAgentEvent.Saving("Saving ${classifications.size} classifications to database"))
            val savedCount = saveClassifications(classifications)
            totalConversationsClassified += savedCount
            
            // Send results of this batch 
            _eventFlow.emit(SamplingAgentEvent.BatchComplete(
                batchSize = savedCount,
                modelCallCount = modelCallCount,
                totalClassified = totalConversationsClassified,
                targetSampleSize = targetSampleSize,
                progress = if (targetSampleSize > 0) (totalConversationsClassified.toFloat() / targetSampleSize) * 100 else 0f,
                classifications = classifications.map { ClassificationResult(it.conversationId, it.classification.name) }
            ))
            
            logger.info { "Classified and saved $savedCount conversations. Total: $totalConversationsClassified / $targetSampleSize" }
            
            // Brief pause to check if we should stop and prevent hammering the database
            delay(CHECK_INTERVAL_MS)
        }
        
        _eventFlow.emit(SamplingAgentEvent.Terminated(
            reason = "Agent loop completed",
            totalClassified = totalConversationsClassified,
            targetSampleSize = targetSampleSize
        ))
        
        return "Terminated the sampling agent. Classified $totalConversationsClassified conversations."
    }
    
    /**
     * Force stop the agent loop
     */
    suspend fun stop() {
        _eventFlow.emit(SamplingAgentEvent.ForceStop("User requested to stop the agent"))
    }
    
    /**
     * Creates a plan based on user instructions
     */
    private suspend fun createPlan(apiKey: String, userInstructions: String): SamplingPlan {
        val prompt = """
You are a conversation sampling planner. Your task is to analyze user instructions and create a plan for 
sampling and classifying conversations. You need to determine:

1. Target sample size (maximum 100, default 20 if not specified)  
2. A Mongo query to pull the required conversations from MongoDB.  
   **Make sure any date‐time comparisons use the shell’s `ISODate("YYYY-MM-DDTHH:mm:ss.SSSZ")` syntax.**  
   **Make sure only conversations with classification == null are pulled.
3. Any additional instructions for the classification process.  
4. Whether the user has requested to stop the process.
5. Provide user readable plan details in markdown format. Details should clearly mention if any deviation is made from user instructions.

Json schema of conversation document in DB is:
$conversationJsonSchema

Analyze the following user instructions:
$userInstructions

Today's date is ${Instant.now()}
""".trimIndent()
        
        val responseFormat = mapOf(
            "type" to "json_schema",
            "json_schema" to mapOf(
                "name" to "planningSchema",
                "schema" to jacksonObjectMapper().readValue<Map<String, JsonValue>>(planningResponseFormat)
            )
        )
        
        val request = CreateCompletionRequest(
            messages = listOf(
                mapOf("role" to "system", "content" to prompt)
            ),
            model = "openai@gpt-4.1",
            response_format = responseFormat,
            stream = false,
            store = false
        )
        
        val response: SamplingPlan = modelService.createCompletion(request, apiKey)
        
        // Ensure the target sample size doesn't exceed our maximum
        val adjustedTargetSize = minOf(response.targetSampleSize, 200)
        return response.copy(targetSampleSize = adjustedTargetSize)
    }
    
    /**
     * Fetches unclassified conversations from the database
     */
    suspend fun fetchUnclassifiedConversations(limit: Int,
                                                       mongoQueryMap: Map<String, Any>): List<Conversation> {
        // 1. Serialize and parse into a BSON Document
        val json    = jacksonObjectMapper().writeValueAsString(mongoQueryMap)
        val queryDoc = Document.parse(json)

        // 2. Convert any ISO-8601 date strings under comparison operators into actual Dates
//        convertDateStrings(queryDoc)

        // 3. Build and execute the BasicQuery against the *plural* collection name
        val basicQuery = BasicQuery(queryDoc).limit(limit)
        return reactiveMongoTemplate
            .find(basicQuery, Conversation::class.java, "labelled_conversations")
            .collectList()
            .awaitSingle()
    }

    /**
     * Saves classification results to the database
     */
    private suspend fun saveClassifications(classifications: List<ClassificationOutput>): Int {
        var savedCount = 0
        
        for (classification in classifications) {
            try {
                // Get the conversation from the repository
                val conversation = conversationRepository.getConversation(classification.conversationId)
                    ?: continue

                // Update the conversation with the new meta field
                val updatedConversation = conversation.copy(classification = classification.classification)
                
                // Save the updated conversation
                conversationRepository.createConversation(updatedConversation)
                savedCount++
                
                logger.info { "Saved classification ${classification.classification} for conversation ${classification.conversationId}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to save classification for conversation ${classification.conversationId}" }
                _eventFlow.emit(SamplingAgentEvent.Error("Failed to save classification for conversation ${classification.conversationId}: ${e.message}"))
            }
        }
        
        return savedCount
    }

    suspend fun classifyConversations(apiKey: String, conversations: List<Conversation>, userInstructions: String): List<ClassificationOutput>{
        require(conversations.isNotEmpty()){"conversations can't be empty"}
        val prompt = """
            You are a customer service conversations sampling Agent. Your goal is to classify the given set of conversations into two categories RESOLVED / UNRESOLVED.
            You are provided with:
            - conversationId : unique identifier for conversation.
            - messages: array of conversation messages between user and assistant.
        """.trimIndent()

        val responseFormat = mapOf(
            "type" to "json_schema",
            "json_schema" to mapOf(
                "name" to "classificationSchema",
                "schema" to jacksonObjectMapper().readValue<Map<String, JsonValue>>(classificationResponseFormat)
            )
        )

        val userMessage = mapOf("role" to "user", "content" to conversations.joinToString("\n") { "conversationId: ${it.id}, messages: ${it.messages}" })
        val messages = mutableListOf(mapOf("role" to "system", "content" to prompt), userMessage)
        if(userInstructions.isNotEmpty()) {
            messages.add(mapOf("role" to "user", "content" to userInstructions))
        }
        val request = CreateCompletionRequest(
            messages = messages,
            model = "openai@gpt-4o-mini",
            response_format = responseFormat,
            stream = false,
            store = false
        )

        try {
        val response: ClassificationOutputResponse = modelService.createCompletion(request, apiKey)
        return response.outputs
        } catch (e: Exception) {
            logger.error(e) { "Error during classification" }
            _eventFlow.emit(SamplingAgentEvent.Error("Error during classification: ${e.message}"))
            throw e
        }
    }
}

/**
 * Events that can be emitted by the SamplingAgent to provide progress updates
 */
sealed class SamplingAgentEvent {


    data class Started(val instructions: String) : SamplingAgentEvent()
    data class Planning(val message: String) : SamplingAgentEvent()
    data class PlanCreated(
        val targetSampleSize: Int,
        val mongoQueryMap: Map<String, Any>,
        val additionalInstructions: String
    ) : SamplingAgentEvent()
    data class FetchingConversations(val message: String) : SamplingAgentEvent()
    data class ConversationsFetched(val count: Int, val ids: List<String>) : SamplingAgentEvent()
    data class Classifying(val message: String) : SamplingAgentEvent()
    data class Saving(val message: String) : SamplingAgentEvent()
    data class BatchComplete(
        val batchSize: Int,
        val modelCallCount: Int,
        val totalClassified: Int,
        val targetSampleSize: Int,
        val progress: Float, // percentage from 0-100
        val classifications: List<ClassificationResult>
    ) : SamplingAgentEvent()
    data class Terminated(val reason: String, val totalClassified: Int, val targetSampleSize: Int) : SamplingAgentEvent()
    data class ForceStop(val reason: String) : SamplingAgentEvent()
    data class Error(val message: String) : SamplingAgentEvent()
}

data class ClassificationResult(val conversationId: String, val classification: String)

enum class CLASSIFICATION {
    RESOLVED,
    UNRESOLVED
}
data class ClassificationOutput(val conversationId: String, val classification: CLASSIFICATION)

data class ClassificationOutputResponse(val outputs: List<ClassificationOutput>)

data class SamplingPlan(
    val targetSampleSize: Int,
    val stopRequested: Boolean,
    val additionalInstructions: String,
    val mongoQueryMap: Map<String, Any>,
    val planDetails: String
)

const val planningResponseFormat = """
{
  "type": "object",
  "properties": {
    "targetSampleSize": {
      "type": "integer",
      "description": "Number of conversations to classify (maximum 500, default 100)"
    },
    "stopRequested": {
      "type": "boolean",
      "description": "Whether the user has requested to stop the process"
    },
    "additionalInstructions": {
      "type": "string",
      "description": "Any additional instructions for the classification process"
    },
    "mongoQueryMap": {
      "type": "object",
      "description": "A MongoDB filter document to apply (e.g. { \"createdAt\": { \"${'$'}gte\": \"2025-04-01T00:00:00Z\" }, ... })"
    }
    "planDetails": {
      "type": "string",
      "description": "Overall plan in user readable markdown format."
    }
  },
  "required": ["targetSampleSize", "filters", "stopRequested", "additionalInstructions"],
  "additionalProperties": false
}
"""

const val classificationResponseFormat = """
{
  "type": "object",
  "properties": {
    "outputs": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "conversationId": {
            "type": "string"
          },
          "classification": {
            "type": "string",
            "enum": ["RESOLVED", "UNRESOLVED"]
          }
        },
        "required": ["conversationId", "classification"],
        "additionalProperties": false
      }
    }
  },
  "required": ["outputs"],
  "additionalProperties": false
}
"""

const val conversationJsonSchema = """
    {
  "type": "object",
  "properties": {
    "_id": {
      "type": "string"
    },
    "createdAt": {
      "type": "string",
      "format": "date-time"
    },
    "messages": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "role": {
            "type": "string",
            "enum": ["USER", "ASSISTANT"]
          },
          "text": {
            "type": "string"
          }
        },
        "required": ["role", "text"],
        "additionalProperties": false
      }
    },
    "summary": {
      "type": "string"
    },
    "labels": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "path": {
            "type": "string"
          },
          "source": {
            "type": "string"
          },
          "status": {
            "type": "string",
            "enum": ["final"]
          },
          "reason": {
            "type": "string"
          },
          "createdAt": {
            "type": "string",
            "format": "date-time"
          }
        },
        "required": ["path", "source", "status", "createdAt"],
        "additionalProperties": false
      }
    },
    "resolved": {
      "type": "boolean"
    },
    "classification": {
      "type": "string",
      "enum": ["RESOLVED", "UNRESOLVED"]
    },
    "meta": {
      "type": "object",
      "properties": {
        "userState": {
          "type": "string",
          "enum": ["unsatisfied"]
        },
        "numberOfTurns": {
          "type": "integer"
        },
        "category": {
          "type": "string",
          "enum": ["REFUND", "ORDER", "SHIPPING", "INVOICE"]
        },
        "intent": {
          "type": "string"
        },
        "flags": {
          "type": "string"
        }
      },
      "required": ["userState", "numberOfTurns", "category", "intent", "flags"],
      "additionalProperties": false
    },
    "version": {
      "type": "integer"
    },
    "_class": {
      "type": "string"
    }
  },
  "required": [
    "_id",
    "createdAt",
    "messages",
    "summary",
    "labels",
    "resolved",
    "meta",
    "version",
    "_class"
  ],
  "additionalProperties": false
}
"""
