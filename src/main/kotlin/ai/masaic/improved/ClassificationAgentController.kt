package ai.masaic.improved

import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import java.util.*

/**
 * DTOs for the Classification Agent API
 */
data class RunRequest(
    val instruction: String,
)

data class CommandRequest(
    val type: String,
    val feedback: String? = null
)

data class ErrorResponse(
    val error: String,
    val message: String
)

/**
 * REST Controller for Classification Agent operations.
 * Provides HTTP endpoints for starting runs, resuming from checkpoints,
 * sending commands, and checking status.
 */
@CrossOrigin(origins = ["*"], allowedHeaders = ["*"])
@RestController
@RequestMapping("/agents/classificationAgent")
class ClassificationAgentController(
    private val classificationAgent: ClassificationAgent,
    private val agentRunRepository: AgentRunRepository
) {
    private val logger = KotlinLogging.logger {}
    
    /**
     * Start a new classification run.
     * 
     * @param agentId Agent identifier (currently ignored, reserved for future routing)
     * @param body Request containing instruction and optional runId
     * @param authorization Authorization header (Bearer token)
     * @return SSE stream of run events
     */
    @PostMapping(
        "/runs",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    suspend fun startRun(
        @RequestBody body: RunRequest,
        @RequestHeader("Authorization") apiKey: String
    ): Flow<ServerSentEvent<String>> {
        val runId = UUID.randomUUID().toString()
        logger.info { "Starting new classification run: runId=$runId" }
        return classificationAgent.run(runId, apiKey, body.instruction)
    }

    /**
     * Resume a classification run from its last checkpoint.
     * 
     * @param agentId Agent identifier (currently ignored)
     * @param runId Run identifier to resume
     * @return SSE stream of resumed run events
     */
    @PostMapping(
        "/runs/{runId}/resume",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    suspend fun resumeRun(
        @PathVariable runId: String
    ): Flow<ServerSentEvent<String>> {
        logger.info { "Resuming classification run: runId=$runId" }
        return classificationAgent.resumeFromCheckpoint(runId)
    }

    /**
     * Send a command to a running classification agent.
     * 
     * @param agentId Agent identifier (currently ignored)
     * @param runId Run identifier to send command to
     * @param req Command request containing type and optional feedback
     * @return SSE stream of command processing events
     */
    @PostMapping(
        "/runs/{runId}/command",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE]
    )
    suspend fun command(
        @PathVariable runId: String,
        @RequestBody req: CommandRequest
    ): Flow<ServerSentEvent<String>> {
        validateCommand(req)
        val cmd = toAgentCommand(req)

        logger.info { "Processing command: runId=$runId, type=${req.type}" }

        return classificationAgent.handleCommand(runId, cmd)
    }

    /**
     * Get the current status/checkpoint of a classification run.
     * 
     * @param agentId Agent identifier (currently ignored)
     * @param runId Run identifier to check status for
     * @return Agent context JSON or 404 if not found
     */
    @GetMapping("/runs/{runId}")
    suspend fun status(
        @PathVariable runId: String
    ): ResponseEntity<AgentContext> {
        val context = agentRunRepository.loadCheckpoint(runId)
        return if (context != null) {
            logger.debug { "Status retrieved for runId=$runId" }
            ResponseEntity.ok(context)
        } else {
            logger.warn { "No checkpoint found for runId=$runId" }
            ResponseEntity.notFound().build()
        }
    }

    /**
     * List all classification runs with pagination support.
     * Returns runs in descending order by createdAt date.
     * 
     * @param limit Maximum number of runs to return (default: 20)
     * @param after Cursor for pagination - runId to start listing after
     * @return List of agent contexts (runs)
     */
    @GetMapping("/runs", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun listRuns(
        @RequestParam(required = false, defaultValue = "20") limit: Int,
        @RequestParam(required = false) after: String?
    ): ResponseEntity<List<AgentContext>> {
        try {
            logger.debug { "Listing runs with limit=$limit, after=$after" }
            val runs = agentRunRepository.listRuns(limit, after)
            return ResponseEntity.ok(runs)
        } catch (e: Exception) {
            logger.error(e) { "Error listing runs" }
            return ResponseEntity.internalServerError().build()
        }
    }
    
    /**
     * Global exception handler for non-SSE endpoints.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleValidationException(e: IllegalArgumentException): ErrorResponse {
        return ErrorResponse("BadRequest", e.message ?: "Invalid request")
    }

    /**
     * Validate command request according to business rules.
     */
    private fun validateCommand(req: CommandRequest) {
        val validTypes = setOf("ApproveFetch", "ApproveAllFetch", "RejectFetch", "ApproveBatch", "RejectBatch", "Stop")
        val needsFeedback = setOf("RejectFetch", "RejectBatch")
        
        require(req.type in validTypes) { 
            "Invalid command type: ${req.type}. Must be one of: ${validTypes.joinToString()}" 
        }
        
        if (req.type in needsFeedback) {
            require(!req.feedback.isNullOrBlank()) { 
                "Feedback must be provided for ${req.type}" 
            }
        }
    }
    
    /**
     * Convert CommandRequest to AgentCommand domain object.
     */
    private fun toAgentCommand(req: CommandRequest): AgentCommand {
        return when (req.type) {
            "ApproveFetch" -> AgentCommand.ApproveFetch
            "ApproveAllFetch" -> AgentCommand.ApproveAllFetch
            "RejectFetch" -> AgentCommand.RejectFetch(req.feedback!!)
            "ApproveBatch" -> AgentCommand.ApproveBatch
            "RejectBatch" -> AgentCommand.RejectBatch(req.feedback!!)
            "Stop" -> AgentCommand.Stop
            else -> error("Command type validation should have caught this: ${req.type}")
        }
    }
} 
