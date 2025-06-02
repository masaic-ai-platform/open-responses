//package ai.masaic.improved
//
//import ai.masaic.improved.model.Conversation
//import ai.masaic.improved.repository.ConversationRepository
//import ai.masaic.openresponses.api.model.CreateCompletionRequest
//import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
//import com.fasterxml.jackson.module.kotlin.readValue
//import com.openai.core.JsonValue
//import kotlinx.coroutines.flow.*
//import kotlinx.coroutines.reactive.awaitSingle
//import mu.KotlinLogging
//import org.bson.Document
//import org.springframework.data.mongodb.core.ReactiveMongoTemplate
//import org.springframework.data.mongodb.core.query.BasicQuery
//import org.springframework.http.codec.ServerSentEvent
//import org.springframework.stereotype.Component
//import java.time.Instant
//
//@Component
//class ConvClassificationAgent(
//    private val modelService: ModelService,
//    private val conversationRepository: ConversationRepository,
//    private val reactiveMongoTemplate: ReactiveMongoTemplate
//) {
//    private val logger = KotlinLogging.logger {}
//
//    // Event flow for sending updates to the frontend
////    private val _eventFlow = MutableSharedFlow<SamplingAgentEvent>(replay = 0)
////    val eventFlow: Flow<SamplingAgentEvent> = _eventFlow.asSharedFlow()
//
//    // Constants for limiting the agent's execution
//    private val MAX_MODEL_CALLS = 10
//    private val MAX_PLANS = 5
//    private val MAX_CONVERSATIONS_PER_BATCH = 10
//    private val CHECK_INTERVAL_MS = 1000L // 1 second interval to check for stop command
//
//    /**
//     * Possible events:
//     * 1. agent.planning.started
//     * 2. agent.planning.completed
//     * 3. agent.stopped
//     */
//
//    suspend fun run(apiKey: String, userInstructions: String = ""): Flow<ServerSentEvent<String>> {
//        flow {emit(BroadcastEvent("agent.run.started", "Classification agent started").toSSE()) }
//        logger.info { "Starting ConvClassificationAgent with instructions: $userInstructions" }
//
//        return flow {
//            // State variables for the agentic loop
//            var modelCallCount = 0
//            var totalConversationsClassified = 0
//            var targetSampleSize = 0
//            var plansCount = 0
//            var shouldStop = false
//
//            while (!shouldStop) {  //TODO: Termination decision should be on Model...
//                var results = mutableListOf<FunctionResult<*>>()
//
//                if(plansCount >= MAX_PLANS) {
//                    emit(BroadcastEvent("agent.run.stopped", "Can not create further plans. Max number of plans ($plansCount) limit reached").toSSE())
//                    break
//                }
//
//                emit(BroadcastEvent("agent.run.planning.started", "Planning started").toSSE())
//                val plan = createPlan(
//                    apiKey,
//                    userInstructions,
//                    mapper.writeValueAsString(results.mapIndexed({ index, result -> if (result.failureLog.isNotEmpty()) "$index. Failure details: ${result.failureLog}\n" })),
//                ) { emit(it.toSSE()) }
//                plansCount++
//                logger.info { "Created plan: $plan" }
//
//                emit(BroadcastEvent("agent.run.output_text.delta", "ConvClassificationAgent plan", plan.planDetails).toSSE())
//                //agent.run.output_text.done......
//                emit(BroadcastEvent("agent.run.planning.completed", "Planning completed").toSSE())
//
//                if (plan.stopRequested) {
//                    logger.info { "Terminating: Stop requested in initial plan" }
//                    emit(BroadcastEvent("agent.run.stopped", "ConvClassificationAgent stopped after signal from plan").toSSE())
//                    shouldStop = true
//                }
//                targetSampleSize = plan.targetSampleSize
//
//                var batchSize = minOf(MAX_CONVERSATIONS_PER_BATCH, targetSampleSize - totalConversationsClassified)
//                val classifiedConversationIds = mutableListOf<String>()
//
//                while(batchSize >= 0 ) {
//                    if (modelCallCount >= MAX_MODEL_CALLS) {
//                        logger.info { "Terminating: Maximum model call limit reached ($MAX_MODEL_CALLS)" }
//                        emit(
//                            BroadcastEvent(
//                                "agent.run.stopped",
//                                "ConvClassificationAgent stopped after $modelCallCount model call counts"
//                            ).toSSE()
//                        )
//                        shouldStop = true
//                        break
//                    }
//
//                    if (totalConversationsClassified >= targetSampleSize) {
//                        logger.info { "Terminating: Target sample size reached ($targetSampleSize)" }
//                        emit(
//                            BroadcastEvent(
//                                "agent.run.completed",
//                                "ConvClassificationAgent run completed after classification of $totalConversationsClassified  conversations"
//                            ).toSSE()
//                        )
//                        //TODO::: add output event
//                        shouldStop = true
//                        break
//                    }
//
//                    val result = fetchUnclassifiedConversations(batchSize, plan.mongoQueryMap)
//                    results.add(result)
//                    if(!result.isSuccess) {
//                        logger.info { "Unable to fetch conversations due to error: ${result.failureLog}" }
//                        emit(
//                            BroadcastEvent(
//                                "agent.run.error",
//                                "Unable to fetch conversations due to error: ${result.failureLog}"
//                            ).toSSE()
//                        )
//                        break
//                    }
//
//                    val conversations = result.data as List<Conversation>
//                    if (conversations.isEmpty()) {
//                        logger.info { "No more unclassified conversations available matching the criteria" }
//                        emit(
//                            BroadcastEvent(
//                                "agent.run.completed",
//                                "Termination: No more conversations to classify. Classified $totalConversationsClassified conversations."
//                            ).toSSE()
//                        )
//                        shouldStop = true
//                        break
//                    }
//
//                    //agent.run.output_text.done .....
//                    val classifications = classifyConversations(apiKey, conversations, plan.additionalInstructions)
//                    modelCallCount++
//
//                    val savedCount = saveClassifications(classifications)
//                    totalConversationsClassified += savedCount
//
//                    val conversationIds = classifications.map { it.conversationId }
//
//                    logger.info { "Fetched ${conversationIds.size} conversations for classification" }
//                    emit(
//                        BroadcastEvent(
//                            "agent.run.output_text.delta",
//                            "Found ${conversationIds.size} conversations for classification"
//                        ).toSSE()
//                    )
//
//                    //TODO add streaming
//                    emit(
//                        ClassifiedConvEvent(
//                            type = "agent.run.classification_item.done",
//                            logMessage = "Classification of a batch with $savedCount conversations completed.",
//                            conversationIds = conversationIds).toSSE()
//                    )
//                    classifiedConversationIds.addAll(conversationIds)
//                }
//
//                if (batchSize <= 0 || shouldStop) {
//                    logger.info { "Terminating: No more conversations needed" }
//                    emit(
//                        ClassifiedConvEvent(
//                            type = "agent.run.classification_item.done",
//                            logMessage = "Classification of ${classifiedConversationIds.size} conversations which is >= target sample size of $targetSampleSize completed.",
//                            conversationIds = classifiedConversationIds).toSSE()
//                    )
//                    shouldStop = true
//                }
//            }
//        }.catch {
//            emit(BroadcastEvent("agent.run.error", "Error while running ConvClassificationAgent agent, ${it.message}").toSSE())
//            logger.error { "Error while running agent: ${it.printStackTrace()}" }
//        }.onCompletion {
//            logger.info { "Completed Agent run...." }
//        }
//    }
//
//    /**
//     * Force stop the agent loop
//     */
//    suspend fun stop() {
////        _eventFlow.emit(SamplingAgentEvent.ForceStop("User requested to stop the agent"))
//    }
//
//    /**
//     * Creates a plan based on user instructions
//     */
//    private suspend fun createPlan(apiKey: String, userInstructions: String, failureLogs: String, onChunk: suspend (TextChunkEvent) -> Unit): ConvClassificationPlan {
//        val prompt = """
//You are a conversation classification planner. Your task is to analyze user instructions and create a plan for
//sampling and classifying conversations. You need to determine:
//
//1. Target sample size (maximum 100, default 20 if not specified)
//2. A Mongo query to pull the required conversations from MongoDB.
//   **Make sure any date‐time comparisons use the shell’s `ISODate("YYYY-MM-DDTHH:mm:ss.SSSZ")` syntax.**
//   **Make sure only conversations with classification == null are pulled.
//3. Any additional instructions for classification. Do not include conversation filtering instructions as additional instructions.
//4. Whether the user has requested to stop the process.
//5. Provide user readable plan details in markdown format. Details should clearly mention if any deviation is made from user instructions.
//6. If there are failures in the previous run then pay attention to those failures while generating new plan.
//
//Json schema of conversation document in DB is:
//$conversationJsonSchema
//
//Today's date is ${Instant.now()}
//""".trimIndent()
//
//        val responseFormat = mapOf(
//            "type" to "json_schema",
//            "json_schema" to mapOf(
//                "name" to "planningSchema",
//                "schema" to jacksonObjectMapper().readValue<Map<String, JsonValue>>(planningResponseFormat)
//            )
//        )
//
//        val messages = mutableListOf(mapOf("role" to "system", "content" to prompt))
//        if(userInstructions.isNotEmpty()) messages.add(mapOf("role" to "user", "content" to "Analyze the following user instructions: $userInstructions"))
//        if(failureLogs.isNotEmpty()) messages.add(mapOf("role" to "user", "content" to "Failures in the past runs: $failureLogs"))
//        val request = CreateCompletionRequest(
//            messages = messages,
//            model = "openai@gpt-4.1",
//            response_format = responseFormat,
//            stream = false,
//            store = false
//        )
//
//        val response = modelService.fetchCompletionPayload(request, apiKey)
//        return mapper.readValue<ConvClassificationPlan>(response)
//    }
//
////    private fun generateTextOutput(): String {
////        val prompt = """
////
////        """.trimIndent()
////    }
//
//
//
//    /**
//     * Fetches unclassified conversations from the database
//     */
//    suspend fun fetchUnclassifiedConversations(limit: Int,
//                                                       mongoQueryMap: Map<String, Any>): FunctionResult<List<Conversation>> {
//        try {
//            // 1. Serialize and parse into a BSON Document
//            val json    = jacksonObjectMapper().writeValueAsString(mongoQueryMap)
//            val queryDoc = Document.parse(json)
//
//            // 2. Convert any ISO-8601 date strings under comparison operators into actual Dates
////        convertDateStrings(queryDoc)
//
//            // 3. Build and execute the BasicQuery against the *plural* collection name
//            val basicQuery = BasicQuery(queryDoc).limit(limit)
//            return FunctionResult(data = reactiveMongoTemplate
//                .find(basicQuery, Conversation::class.java, "labelled_conversations")
//                .collectList()
//                .awaitSingle())
//        }catch (ex: Exception) {
//            return FunctionResult(isSuccess = false, failureLog = ex.message ?: "Failure log is not available")
//        }
//    }
//
//    /**
//     * Saves classification results to the database
//     */
//    private suspend fun saveClassifications(classifications: List<ClassificationOutput>): Int {
//        var savedCount = 0
//        logger.info { "====================> Start ======>" }
//        logger.info { "total classifications: ${classifications.size}" }
//        for (classification in classifications) {
//            try {
//                // Get the conversation from the repository
//                val conversation = conversationRepository.getConversation(classification.conversationId)
//                    ?: continue
//
//                logger.info { "conversationId: ${conversation.id}" }
//
//                logger.info { "Convers" }
//
//                // Update the conversation with the new meta field
//                val updatedConversation = conversation.copy(classification = classification.classification)
//
//                // Save the updated conversation
//                conversationRepository.createConversation(updatedConversation)
//                savedCount++
//
//                logger.info { "Saved classification ${classification.classification} for conversation ${classification.conversationId}" }
//            } catch (e: Exception) {
//                logger.error(e) { "Failed to save classification for conversation ${classification.conversationId}" }
////                _eventFlow.emit(SamplingAgentEvent.Error("Failed to save classification for conversation ${classification.conversationId}: ${e.message}"))
//            }
//        }
//
//        logger.info { "====================> end:::: savedCount: $savedCount ======>" }
//        return savedCount
//    }
//
//    suspend fun classifyConversations(apiKey: String, conversations: List<Conversation>, userInstructions: String): List<ClassificationOutput>{
//        require(conversations.isNotEmpty()){"conversations can't be empty"}
//        val prompt = """
//            You are a customer service conversations Classification Agent. Your goal is to classify the given set of conversations into two categories RESOLVED / UNRESOLVED.
//            You are provided with:
//            - conversationId : unique identifier for conversation.
//            - messages: array of conversation messages between user and assistant.
//        """.trimIndent()
//
//        val responseFormat = mapOf(
//            "type" to "json_schema",
//            "json_schema" to mapOf(
//                "name" to "classificationSchema",
//                "schema" to jacksonObjectMapper().readValue<Map<String, JsonValue>>(classificationResponseFormat)
//            )
//        )
//
//        val userMessage = mapOf("role" to "user", "content" to conversations.joinToString("\n") { "conversationId: ${it.id}, messages: ${it.messages}" })
//        val messages = mutableListOf(mapOf("role" to "system", "content" to prompt), userMessage)
//        if(userInstructions.isNotEmpty()) {
//            messages.add(mapOf("role" to "user", "content" to userInstructions))
//        }
//        val request = CreateCompletionRequest(
//            messages = messages,
//            model = "openai@gpt-4o-mini",
//            response_format = responseFormat,
//            stream = false,
//            store = false
//        )
//
//        try {
//        val response: ClassificationOutputResponse = modelService.createCompletion(request, apiKey)
//        return response.outputs
//        } catch (e: Exception) {
//            logger.error(e) { "Error during classification" }
////            _eventFlow.emit(SamplingAgentEvent.Error("Error during classification: ${e.message}"))
//            throw e
//        }
//    }
//}
//
///**
// * Possible events:
// * 1. sampling.started
// * 2. sampling.completed
// * 3. sampling.failed
// * 4.
// */
//val mapper = jacksonObjectMapper()
//data class BroadcastEvent(val type: String, val logMessage: String, val message: String = "") {
//    fun toSSE(): ServerSentEvent<String> {
//        val eventData = mapper.writeValueAsString(this)
//        return ServerSentEvent
//            .builder(eventData)
//            .event(" $type")
//            .build()
//    }
//}
//
//data class TextChunkEvent(val type: String, val text: String = "") {
//    fun toSSE(): ServerSentEvent<String> {
//        val eventData = mapper.writeValueAsString(this)
//        return ServerSentEvent
//            .builder(eventData)
//            .event(" $type")
//            .build()
//    }
//}
//
//data class ClassifiedConvEvent(val type: String, val logMessage: String, val message: String = "", val conversationIds: List<String>) {
//    fun toSSE(): ServerSentEvent<String> {
//        val eventData = mapper.writeValueAsString(this)
//        return ServerSentEvent
//            .builder(eventData)
//            .event(" $type")
//            .build()
//    }
//}
//
///**
// * Events that can be emitted by the SamplingAgent to provide progress updates
// */
//sealed class SamplingAgentEvent {
//
//
//    data class Started(val instructions: String) : SamplingAgentEvent()
//    data class Planning(val message: String) : SamplingAgentEvent()
//    data class PlanCreated(
//        val targetSampleSize: Int,
//        val mongoQueryMap: Map<String, Any>,
//        val additionalInstructions: String
//    ) : SamplingAgentEvent()
//    data class FetchingConversations(val message: String) : SamplingAgentEvent()
//    data class ConversationsFetched(val count: Int, val ids: List<String>) : SamplingAgentEvent()
//    data class Classifying(val message: String) : SamplingAgentEvent()
//    data class Saving(val message: String) : SamplingAgentEvent()
//    data class BatchComplete(
//        val batchSize: Int,
//        val modelCallCount: Int,
//        val totalClassified: Int,
//        val targetSampleSize: Int,
//        val progress: Float, // percentage from 0-100
//        val classifications: List<ClassificationResult>
//    ) : SamplingAgentEvent()
//    data class Terminated(val reason: String, val totalClassified: Int, val targetSampleSize: Int) : SamplingAgentEvent()
//    data class ForceStop(val reason: String) : SamplingAgentEvent()
//    data class Error(val message: String) : SamplingAgentEvent()
//}
//
//data class ClassificationResult(val conversationId: String, val classification: String)
//
//enum class CLASSIFICATION {
//    RESOLVED,
//    UNRESOLVED
//}
//data class ClassificationOutput(val conversationId: String, val classification: CLASSIFICATION)
//
//data class ClassificationOutputResponse(val outputs: List<ClassificationOutput>)
//
//data class ConvClassificationPlan(
//    val targetSampleSize: Int,
//    val stopRequested: Boolean,
//    val additionalInstructions: String,
//    val mongoQueryMap: Map<String, Any>,
//    val planDetails: String
//)
//
//data class FunctionResult<T>(val isSuccess: Boolean = true, val failureLog: String = "", val data: T ? = null) //TODO: need success and failure result
//
//const val planningResponseFormat = """
//{
//  "type": "object",
//  "properties": {
//    "targetSampleSize": {
//      "type": "integer",
//      "description": "Number of conversations to classify (maximum 500, default 100)"
//    },
//    "stopRequested": {
//      "type": "boolean",
//      "description": "Whether the user has requested to stop the process"
//    },
//    "additionalInstructions": {
//      "type": "string",
//      "description": "Apart from filtering, any additional instructions for the classification process"
//    },
//    "mongoQueryMap": {
//      "type": "object",
//      "description": "A MongoDB filter document to apply (e.g. { \"createdAt\": { \"${'$'}gte\": \"2025-04-01T00:00:00Z\" }, ... })"
//    },
//    "planDetails": {
//      "type": "string",
//      "description": "Overall plan in user readable markdown format."
//    }
//  },
//  "required": ["targetSampleSize", "filters", "stopRequested", "additionalInstructions"],
//  "additionalProperties": false
//}
//"""
//
//const val classificationResponseFormat = """
//{
//  "type": "object",
//  "properties": {
//    "outputs": {
//      "type": "array",
//      "items": {
//        "type": "object",
//        "properties": {
//          "conversationId": {
//            "type": "string"
//          },
//          "classification": {
//            "type": "string",
//            "enum": ["RESOLVED", "UNRESOLVED"]
//          }
//        },
//        "required": ["conversationId", "classification"],
//        "additionalProperties": false
//      }
//    }
//  },
//  "required": ["outputs"],
//  "additionalProperties": false
//}
//"""
//
//const val conversationJsonSchema = """
//    {
//  "type": "object",
//  "properties": {
//    "_id": {
//      "type": "string"
//    },
//    "createdAt": {
//      "type": "string",
//      "format": "date-time"
//    },
//    "messages": {
//      "type": "array",
//      "items": {
//        "type": "object",
//        "properties": {
//          "role": {
//            "type": "string",
//            "enum": ["USER", "ASSISTANT"]
//          },
//          "text": {
//            "type": "string"
//          }
//        },
//        "required": ["role", "text"],
//        "additionalProperties": false
//      }
//    },
//    "summary": {
//      "type": "string"
//    },
//    "labels": {
//      "type": "array",
//      "items": {
//        "type": "object",
//        "properties": {
//          "path": {
//            "type": "string"
//          },
//          "source": {
//            "type": "string"
//          },
//          "status": {
//            "type": "string",
//            "enum": ["final"]
//          },
//          "reason": {
//            "type": "string"
//          },
//          "createdAt": {
//            "type": "string",
//            "format": "date-time"
//          }
//        },
//        "required": ["path", "source", "status", "createdAt"],
//        "additionalProperties": false
//      }
//    },
//    "resolved": {
//      "type": "boolean"
//    },
//    "classification": {
//      "type": "string",
//      "enum": ["RESOLVED", "UNRESOLVED"]
//    },
//    "meta": {
//      "type": "object",
//      "properties": {
//        "userState": {
//          "type": "string",
//          "enum": ["unsatisfied"]
//        },
//        "numberOfTurns": {
//          "type": "integer"
//        },
//        "category": {
//          "type": "string",
//          "enum": ["REFUND", "ORDER", "SHIPPING", "INVOICE"]
//        },
//        "intent": {
//          "type": "string"
//        },
//        "flags": {
//          "type": "string"
//        }
//      },
//      "required": ["userState", "numberOfTurns", "category", "intent", "flags"],
//      "additionalProperties": false
//    },
//    "version": {
//      "type": "integer"
//    },
//    "_class": {
//      "type": "string"
//    }
//  },
//  "required": [
//    "_id",
//    "createdAt",
//    "messages",
//    "summary",
//    "labels",
//    "resolved",
//    "meta",
//    "version",
//    "_class"
//  ],
//  "additionalProperties": false
//}
//"""
