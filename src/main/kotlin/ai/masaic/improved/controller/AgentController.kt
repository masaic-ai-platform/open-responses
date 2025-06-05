package ai.masaic.improved.controller

import ai.masaic.improved.ClassificationAgent
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@RestController
@RequestMapping("/v1")
@CrossOrigin(origins = ["*"], allowedHeaders = ["*"])
class AgentController(
    private val convClassificationAgent: ClassificationAgent,
) {
    private val logger = KotlinLogging.logger {}
    private val objectMapper = jacksonObjectMapper()
    
    // Store active agent runs
    private val activeRuns = ConcurrentHashMap<String, AgentRun>()
    
    // Store event channels for each run
    private val runChannels = ConcurrentHashMap<String, Channel<ServerSentEvent<String>>>()
    
    // Keep track of events for each run for replay capability
    private val runEvents = ConcurrentHashMap<String, MutableList<SseEvent>>()
    
    // Terminal event types that should close the stream
    private val terminalEventTypes =
        setOf(
            "RUN_COMPLETED", 
            "RUN_ABORTED", 
            "ERROR",
        )

    @PostMapping("/agents/{agentId}/ask", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun ask(
        @PathVariable agentId: String,
        @RequestBody request: RunRequest,
        @RequestHeader(name = "Authorization", required = true) apiKey: String,
    ): Flow<ServerSentEvent<String>> = convClassificationAgent.run(apiKey, request.instruction)
    
    /**
     * Start a new agent run
     */
//    @PostMapping("/agents/{agentId}/ask", produces = [MediaType.APPLICATION_JSON_VALUE])
//    suspend fun startRun(
//        @PathVariable agentId: String,
//        @RequestBody request: RunRequest,
//        @RequestHeader(name = "Authorization", required = true) apiKey: String
//    ): RunCreated {
//        logger.info { "Starting agent $agentId with instruction: ${request.instruction}" }
//
//        // Generate a unique run ID
//        val runId = "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10)
//
//        // Create a channel for this run's events
//        val channel = Channel<ServerSentEvent<String>>(Channel.BUFFERED)
//        runChannels[runId] = channel
//
//        // Create a new agent run
//        val agentRun = AgentRun(
//            agentId = agentId,
//            runId = runId,
//            instruction = request.instruction,
//            context = request.context ?: emptyMap(),
//            apiKey = apiKey,
//            status = RunStatus.QUEUED,
//            startTime = Instant.now(),
//            jobContext = CoroutineScope(Dispatchers.Default)
//        )
//
//        // Store the run
//        activeRuns[runId] = agentRun
//        runEvents[runId] = Collections.synchronizedList(mutableListOf())
//
//        // Launch the agent in a background coroutine
//        agentRun.jobContext.launch {
//            try {
//                // Update status to running
//                agentRun.status = RunStatus.RUNNING
//                sendEvent(runId, SseEvent(
//                    type = "STATUS_CHANGE",
//                    data = StatusChangeData(status = "RUNNING")
//                ))
//
//                // Set up event collection
//                val eventCollectionJob = launch {
//                    samplingAgent.eventFlow.collect { event ->
//                        // Map the SamplingAgentEvent to our SSE event format
//                        val sseEvent = mapAgentEventToSseEvent(event)
//                        sendEvent(runId, sseEvent)
//                    }
//                }
//
//                // Run the agent
//                val result = samplingAgent.run(apiKey, request.instruction)
//
//                // Update status to complete
//                agentRun.status = RunStatus.COMPLETED
//                sendEvent(runId, SseEvent(
//                    type = "STATUS_CHANGE",
//                    data = StatusChangeData(status = "COMPLETED")
//                ))
//
//                // Send final result
//                sendEvent(runId, SseEvent(
//                    type = "RUN_COMPLETED",
//                    data = RunCompletedData(result = result)
//                ))
//
//                // Cancel event collection
//                eventCollectionJob.cancel()
//
//            } catch (e: Exception) {
//                logger.error(e) { "Error in agent run $runId" }
//
//                // Update status to error
//                agentRun.status = RunStatus.ERROR
//                sendEvent(runId, SseEvent(
//                    type = "STATUS_CHANGE",
//                    data = StatusChangeData(status = "ERROR")
//                ))
//
//                // Send error event
//                sendEvent(runId, SseEvent(
//                    type = "ERROR",
//                    data = ErrorData(message = e.message ?: "Unknown error")
//                ))
//            } finally {
//                // Close the channel immediately for terminal events
//                runChannels[runId]?.close()
//            }
//        }
//
//        return RunCreated(runId = runId, status = agentRun.status.name)
//    }
    
    /**
     * Stream run events via SSE using Kotlin Flow
     */
//    @GetMapping("/agents/{agentId}/{runId}/sse", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
//    suspend fun streamRun(
//        @PathVariable agentId: String,
//        @PathVariable runId: String,
//        @RequestHeader(name = "Last-Event-ID", required = false) lastEventId: String?
//    ): Flow<ServerSentEvent<String>> {
//        logger.info { "Client connected to event stream for run $runId" }
//
//        // Check if the run exists
//        if (!activeRuns.containsKey(runId)) {
//            throw NotFoundException("Run not found: $runId")
//        }
//
//        // Create a callback flow that will emit events from the channel
//        return callbackFlow {
//            // If there's a Last-Event-ID header, first replay missed events
//            if (lastEventId != null) {
//                val events = runEvents[runId] ?: emptyList()
//                var replaySeen = false
//
//                events.forEach { event ->
//                    if (replaySeen) {
//                        trySend(createServerSentEvent(event))
//                    } else if (event.id == lastEventId) {
//                        replaySeen = true
//                    }
//                }
//            }
//
//            // Get or create a channel for this run
//            val channel = runChannels[runId] ?: Channel<ServerSentEvent<String>>(Channel.BUFFERED).also {
//                runChannels[runId] = it
//            }
//
//            // Launch a coroutine to forward events from the channel to the flow
//            val job = launch {
//                for (event in channel) {
//                    send(event)
//                }
//            }
//
//            // Clean up when the flow is cancelled
//            awaitClose {
//                logger.info { "Client disconnected from event stream for run $runId" }
//                job.cancel()
//            }
//        }.catch { error ->
//            logger.error(error as? Exception) { "Error in SSE stream for run $runId" }
//            throw error
//        }
//    }
    
    /**
//     * Handle replies to agent requests or abort a run
//     */
//    @PostMapping("/agents/{agentId}/{runId}/reply")
//    suspend fun replyOrAbort(
//        @PathVariable agentId: String,
//        @PathVariable runId: String,
//        @RequestBody request: ReplyRequest
//    ): ResponseEntity<Void> {
//        logger.info { "Received reply for run $runId: $request" }
//
//        // Check if the run exists
//        val run = activeRuns[runId] ?: throw NotFoundException("Run not found: $runId")
//
//        // Check if the run is in progress
//        if (run.status != RunStatus.RUNNING && run.status != RunStatus.WAITING_FOR_INPUT) {
//            throw ConflictException("Run is not in progress: ${run.status}")
//        }
//
//        when (request) {
//            is Answer -> {
//                // Process the answer
//                logger.info { "Processing answer for request ${request.requestId}" }
//
//                // Check if this is the current request
//                if (run.currentRequestId != request.requestId) {
//                    throw ConflictException("Request ID mismatch: expected ${run.currentRequestId}, got ${request.requestId}")
//                }
//
//                // Send an event indicating the clarification was received
//                sendEvent(runId, SseEvent(
//                    type = "CLARIFICATION_RECEIVED",
//                    data = ClarificationReceivedData(requestId = request.requestId, text = request.text)
//                ))
//
//                // Update the run status
//                run.status = RunStatus.RUNNING
//
//                // Let the agent continue with the provided answer
//                // In a real implementation, you would have a way to inject this into the agent's flow
//                // This is a placeholder for that logic
//
//                return ResponseEntity.status(HttpStatus.ACCEPTED).build()
//            }
//
//            is Abort -> {
//                // Abort the run
//                logger.info { "Aborting run $runId: ${request.reason ?: "No reason provided"}" }
//
//                // Cancel the job
//                run.jobContext.cancel("User requested abort: ${request.reason ?: "No reason provided"}")
//
//                // Update the run status
//                run.status = RunStatus.ABORTED
//
//                // Send an event indicating the run was aborted
//                sendEvent(runId, SseEvent(
//                    type = "STATUS_CHANGE",
//                    data = StatusChangeData(status = "ABORTED")
//                ))
//
//                sendEvent(runId, SseEvent(
//                    type = "RUN_ABORTED",
//                    data = RunAbortedData(reason = request.reason ?: "User requested abort")
//                ))
//
//                // Close the channel immediately for abort
//                runChannels[runId]?.close()
//
//                return ResponseEntity.status(HttpStatus.ACCEPTED).build()
//            }
//        }
//    }
//
//    /**
//     * Send an event to all clients listening to a run
//     */
//    private fun sendEvent(runId: String, event: SseEvent) {
//        // Generate a unique ID for this event if not already set
//        if (event.id == null) {
//            event.id = UUID.randomUUID().toString()
//        }
//
//        // Store the event for replay capability
//        runEvents[runId]?.add(event)
//
//        // Get the channel for this run
//        val channel = runChannels[runId] ?: return
//
//        // Create a ServerSentEvent
//        val serverSentEvent = createServerSentEvent(event)
//
//        // Send the event to all connected clients
//        channel.trySendBlocking(serverSentEvent)
//
//        // Close the channel if this is a terminal event
//        if (terminalEventTypes.contains(event.type)) {
//            logger.info { "Closing SSE stream for run $runId after terminal event: ${event.type}" }
//            channel.close()
//        }
//    }

    /**
     * Create a ServerSentEvent from an SseEvent
     */
    private fun createServerSentEvent(event: SseEvent): ServerSentEvent<String> =
        ServerSentEvent
            .builder<String>()
            .id(event.id) // Safe to use non-null assertion after the check above
            .event(event.type)
            .data(objectMapper.writeValueAsString(event))
            .build()
    
//    /**
//     * Map a SamplingAgentEvent to our SSE event format
//     */
//    private fun mapAgentEventToSseEvent(event: SamplingAgentEvent): SseEvent {
//        return when (event) {
//            is SamplingAgentEvent.Started -> SseEvent(
//                type = "STEP_STARTED",
//                data = StepStartedData(step = "initialization", details = "Starting agent with instructions")
//            )
//            is SamplingAgentEvent.Planning -> SseEvent(
//                type = "STEP_STARTED",
//                data = StepStartedData(step = "planning", details = event.message)
//            )
//            is SamplingAgentEvent.PlanCreated -> SseEvent(
//                type = "STEP_COMPLETED",
//                data = StepCompletedData(
//                    step = "planning",
//                    details = "Created plan with target of ${event.targetSampleSize} samples",
//                    data = mapOf(
//                        "targetSampleSize" to event.targetSampleSize,
//                        "mongoQueryMap" to event.mongoQueryMap,
//                        "additionalInstructions" to event.additionalInstructions
//                    )
//                )
//            )
//            is SamplingAgentEvent.FetchingConversations -> SseEvent(
//                type = "STEP_STARTED",
//                data = StepStartedData(step = "fetch_conversations", details = event.message)
//            )
//            is SamplingAgentEvent.ConversationsFetched -> SseEvent(
//                type = "STEP_COMPLETED",
//                data = StepCompletedData(
//                    step = "fetch_conversations",
//                    details = "Fetched ${event.count} conversations",
//                    data = mapOf("count" to event.count, "ids" to event.ids)
//                )
//            )
//            is SamplingAgentEvent.Classifying -> SseEvent(
//                type = "STEP_STARTED",
//                data = StepStartedData(step = "classify", details = event.message)
//            )
//            is SamplingAgentEvent.Saving -> SseEvent(
//                type = "STEP_STARTED",
//                data = StepStartedData(step = "save", details = event.message)
//            )
//            is SamplingAgentEvent.BatchComplete -> SseEvent(
//                type = "PROGRESS_UPDATE",
//                data = ProgressUpdateData(
//                    progress = event.progress,
//                    currentCount = event.totalClassified,
//                    targetCount = event.targetSampleSize,
//                    details = "Processed ${event.batchSize} items, total: ${event.totalClassified}/${event.targetSampleSize}"
//                )
//            )
//            is SamplingAgentEvent.Terminated -> SseEvent(
//                type = "RUN_COMPLETED",
//                data = RunCompletedData(
//                    result = "Agent terminated: ${event.reason}. " +
//                            "Classified ${event.totalClassified}/${event.targetSampleSize} conversations."
//                )
//            )
//            is SamplingAgentEvent.ForceStop -> SseEvent(
//                type = "RUN_ABORTED",
//                data = RunAbortedData(reason = event.reason)
//            )
//            is SamplingAgentEvent.Error -> SseEvent(
//                type = "ERROR",
//                data = ErrorData(message = event.message)
//            )
//        }
//    }
//
//    /**
//     * Request a clarification from the user
//     * This would be called from within the agent when it needs user input
//     */
//    fun requestClarification(runId: String, question: String): String {
//        // Get the run
//        val run = activeRuns[runId] ?: throw NotFoundException("Run not found: $runId")
//
//        // Generate a request ID
//        val requestId = UUID.randomUUID().toString()
//
//        // Update the run status
//        run.status = RunStatus.WAITING_FOR_INPUT
//        run.currentRequestId = requestId
//
//        // Send an event requesting clarification
//        sendEvent(runId, SseEvent(
//            type = "CLARIFICATION_REQUEST",
//            data = ClarificationRequestData(requestId = requestId, question = question)
//        ))
//
//        return requestId
//    }
}

// Data models for the API

/**
 * Request to start a new agent run
 */
data class RunRequest(
    val instruction: String,
    val context: Map<String, Any>? = null,
)

/**
 * Response when a run is created
 */
data class RunCreated(
    val runId: String,
    val status: String,
)

/**
 * Base class for reply requests (used for polymorphic deserialization)
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "mode",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = Answer::class, name = "ANSWER"),
    JsonSubTypes.Type(value = Abort::class, name = "ABORT"),
)
sealed class ReplyRequest

/**
 * User answer to a clarification request
 */
data class Answer(
    val mode: String = "ANSWER",
    val requestId: String,
    val text: String,
) : ReplyRequest()

/**
 * Request to abort a run
 */
data class Abort(
    val mode: String = "ABORT",
    val reason: String? = null,
) : ReplyRequest()

/**
 * Server-sent event
 */
data class SseEvent(
    val type: String,
    val data: Any,
    var id: String = UUID.randomUUID().toString(), // Default to a new UUID instead of allowing null
    val timestamp: String = Instant.now().toString(),
    val v: Int = 1,
)

/**
 * Status of an agent run
 */
enum class RunStatus {
    QUEUED,
    RUNNING,
    WAITING_FOR_INPUT,
    COMPLETED,
    ABORTED,
    ERROR,
}

/**
 * Agent run instance
 */
data class AgentRun(
    val agentId: String,
    val runId: String,
    val instruction: String,
    val context: Map<String, Any>,
    val apiKey: String,
    var status: RunStatus,
    val startTime: Instant,
    val jobContext: CoroutineScope,
    var currentRequestId: String? = null,
)

// Event data types
data class StatusChangeData(
    val status: String,
)

data class StepStartedData(
    val step: String,
    val details: String,
)

data class StepCompletedData(
    val step: String,
    val details: String,
    val data: Map<String, Any> = emptyMap(),
)

data class ProgressUpdateData(
    val progress: Float,
    val currentCount: Int,
    val targetCount: Int,
    val details: String,
)

data class RunCompletedData(
    val result: String,
)

data class RunAbortedData(
    val reason: String,
)

data class ErrorData(
    val message: String,
)

data class ClarificationRequestData(
    val requestId: String,
    val question: String,
)

data class ClarificationReceivedData(
    val requestId: String,
    val text: String,
)

// Custom exceptions
class NotFoundException(
    message: String,
) : RuntimeException(message)

class ConflictException(
    message: String,
) : RuntimeException(message)

// Exception handlers
@ControllerAdvice
class AgentControllerExceptionHandler {
    @ExceptionHandler(NotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ResponseBody
    suspend fun handleNotFoundException(ex: NotFoundException): Map<String, String> = mapOf("message" to ex.message!!)

    @ExceptionHandler(ConflictException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    @ResponseBody
    suspend fun handleConflictException(ex: ConflictException): Map<String, String> = mapOf("message" to ex.message!!)

    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ResponseBody
    suspend fun handleException(ex: Exception): Map<String, String> = mapOf("message" to (ex.message ?: "Internal server error"))
}
