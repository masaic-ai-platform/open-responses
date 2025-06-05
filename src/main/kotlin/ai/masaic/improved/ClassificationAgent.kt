package ai.masaic.improved

import ai.masaic.improved.model.Conversation
import ai.masaic.improved.model.CLASSIFICATION
import ai.masaic.improved.repository.ConversationRepository
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.core.JsonValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.bson.Document
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.BasicQuery
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Configuration for the ClassificationAgent.
 * Can be customized via application.yml under 'classification-agent' prefix.
 */
@ConfigurationProperties(prefix = "classification-agent")
data class AgentConfig(
    val maxModelCalls: Int = 10,
    val maxPlans: Int = 5,
    val maxBatch: Int = 10,
    val checkIntervalMs: Long = 1000
)

/**
 * Sealed interface representing all possible states of the ClassificationAgent.
 */
sealed interface AgentState {
    data object Planning : AgentState
    data object Fetching : AgentState
    data object Classifying : AgentState
    data object Saving : AgentState
    data object Summarizing : AgentState
    data object Completed : AgentState
    data object Stopped : AgentState
    data class Error(val message: String) : AgentState
    data class AwaitingFetchApproval(val fetchedConversations: List<Conversation>, val plan: ConvClassificationPlan) : AgentState
    data class AwaitingBatchApproval(val classifications: List<ClassificationOutput>) : AgentState
}

/**
 * Sealed interface representing commands that can be sent to the agent for human-in-the-loop control.
 */
sealed interface AgentCommand {
    data object ApproveFetch : AgentCommand
    data object ApproveAllFetch : AgentCommand
    data class RejectFetch(val feedback: String) : AgentCommand
    data object ApproveBatch : AgentCommand
    data class RejectBatch(val feedback: String) : AgentCommand
    data object Stop : AgentCommand
    data object NoOpCommand: AgentCommand
    
    // Keep legacy commands for backward compatibility
//    data object ApprovePlan : AgentCommand
//    data class RejectPlan(val feedback: String) : AgentCommand
}

/**
 * Context object that holds the agent's execution state and progress.
 */
data class  AgentContext(
    @Id
    val runId: String,
    val apiKey: String,
    val userInstructions: String,
    var state: AgentState = AgentState.Planning,
    var stateName: String = AgentState.Planning.javaClass.simpleName,
    var modelCallCount: Int = 0,
    var totalConversationsClassified: Int = 0,
    var targetSampleSize: Int = 0,
    var plansCount: Int = 0,
    var currentPlan: ConvClassificationPlan? = null,
    var classifiedConversationIds: MutableList<String> = mutableListOf(),
    var failureLogs: String = "",
    var summary: String = "",
    var replanningReason: String = "",
    var fetchedConversations: List<Conversation> = emptyList(),
    var pendingClassifications: List<ClassificationOutput> = emptyList(),
    var allConversationIds: MutableList<String> = mutableListOf(),
    var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    var approvalFetchCommandExecuted: AgentCommand = AgentCommand.NoOpCommand,
    var approvalBatchCommandExecuted: AgentCommand = AgentCommand.NoOpCommand
)

data class AgentRunOutcome(
    val conversationIds: List<String>,
    val runId: String,
    val createdAt: Instant = Instant.now()
)

/**
 * Data class for batch data events
 */
data class BatchData(
    val conversationIds: List<String>,
    val classifications: List<Map<String, String>>,
    val progress: Int
)

/**
 * Data class for final data events
 */
data class FinalData(
    val allConversationIds: List<String>,
    val totalClassified: Int,
    val runId: String
)

/**
 * Utility extension function to stream text in chunks with start/delta/done events
 */
suspend fun FlowCollector<ServerSentEvent<String>>.streamText(
    baseType: String,
    text: String,
) {
    // Emit started event
    emit(BroadcastEvent("$baseType.started", "Generating $baseType...").toSSE())
    
    // Emit text in chunks
    text.chunked(5).forEach { chunk ->
        emit(BroadcastEvent("$baseType.delta", "", chunk).toSSE())
        delay(30L)
    }
    
    // Emit done event
    emit(BroadcastEvent("$baseType.done", "$baseType generation completed").toSSE())
}

/**
 * Utility function to emit structured data as SSE
 */
suspend fun FlowCollector<ServerSentEvent<String>>.emitDataEvent(
    eventType: String,
    data: Any
) {
    val eventData = BroadcastEvent(eventType, "", data = data)
    emit(eventData.toSSE())
}

/**
 * Advanced Classification Agent with state machine pattern for autonomous conversation classification.
 *
 * Features:
 * - State machine driven execution
 * - Real-time progress streaming via SSE
 * - Configurable limits and behavior
 * - LLM-driven planning and re-planning
 * - User-friendly progress reporting
 * - Graceful error handling and recovery
 */
@Component
@EnableConfigurationProperties(AgentConfig::class)
class ClassificationAgent(
    private val modelService: ModelService,
    private val conversationRepository: ConversationRepository,
    private val reactiveMongoTemplate: ReactiveMongoTemplate,
    private val agentRunRepository: AgentRunRepository,
    private val config: AgentConfig
) {
    private val logger = KotlinLogging.logger {}
    private val mapper = jacksonObjectMapper()
    
    /**
     * Start a new classification run.
     * Returns a Flow of Server-Sent Events for real-time progress updates.
     */
    suspend fun run(runId: String, apiKey: String, userInstructions: String = ""): Flow<ServerSentEvent<String>> {
        val context = AgentContext(runId, apiKey, userInstructions)
        return executeAgent(context, isResume = false)
    }

    /**
     * Resume agent from checkpoint.
     */
    suspend fun resumeFromCheckpoint(runId: String): Flow<ServerSentEvent<String>> {
        val context = agentRunRepository.loadCheckpoint(runId)
            ?: return flowOf(BroadcastEvent("agent.run.error", "No checkpoint found for runId: $runId").toSSE())
        
        return executeAgent(context, isResume = true)
    }
    
    /**
     * Submit a command to handle HITL interactions for a specific run.
     * Handles both plan and batch approval workflows.
     */
    suspend fun handleCommand(runId: String, command: AgentCommand): Flow<ServerSentEvent<String>> {
        val context = agentRunRepository.loadCheckpoint(runId)
            ?: return flowOf(BroadcastEvent("agent.run.error", "No checkpoint found for runId: $runId").toSSE())
        
        return flow {
            val result = when (context.state) {
                is AgentState.AwaitingFetchApproval -> handleFetchApproval(context, command)
                is AgentState.AwaitingBatchApproval -> handleBatchApproval(context, command)
                else -> {
                    event("agent.run.error", "Agent is not awaiting user input. Current state: ${context.state::class.simpleName}")
                    return@flow
                }
            }
            
            if (result.shouldContinue) {
                agentRunRepository.saveCheckpoint(context)
                executeAgent(context, isResume = true).collect { emit(it) }
            } else {
                agentRunRepository.saveCheckpoint(context)
            }
        }
    }
    
    /**
     * Handle fetch approval commands
     */
    private suspend fun FlowCollector<ServerSentEvent<String>>.handleFetchApproval(
        context: AgentContext, 
        command: AgentCommand
    ): CommandResult {
        val fetchState = context.state as AgentState.AwaitingFetchApproval
        
        return when (command) {
            is AgentCommand.ApproveFetch -> {
                event("agent.run.fetch_approved", "Fetched data approved by user")
                // Use the fetched conversations from the state
                context.fetchedConversations = fetchState.fetchedConversations
                context.state = AgentState.Classifying
                CommandResult(shouldContinue = true)
            }
            is AgentCommand.RejectFetch -> {
                event("agent.run.fetch_rejected", "Fetched data rejected: ${command.feedback}")
                context.failureLogs += "Fetch data rejected: ${command.feedback}\n"
                context.replanningReason = "fetch_rejected"
                context.state = AgentState.Planning
                CommandResult(shouldContinue = true)
            }
            is AgentCommand.ApproveAllFetch -> {
                event("agent.run.fetch_approved", "Fetched data approved by user for all upcoming fetches")
                // Use the fetched conversations from the state
                context.fetchedConversations = fetchState.fetchedConversations
                context.approvalFetchCommandExecuted = AgentCommand.ApproveAllFetch
                context.state = AgentState.Classifying
                CommandResult(shouldContinue = true)
            }

//            is AgentCommand.RejectPlan -> {
//                event("agent.run.fetch_rejected", "Fetched data rejected: ${command.feedback}")
//                context.failureLogs += "Fetch data rejected: ${command.feedback}\n"
//                context.replanningReason = "fetch_rejected"
//                context.state = AgentState.Planning
//                CommandResult(shouldContinue = true)
//            }
            is AgentCommand.Stop -> {
                event("agent.run.stopped", "Agent stopped by user command")
                context.state = AgentState.Stopped
                CommandResult(shouldContinue = false)
            }
            else -> {
                event("agent.run.error", "Invalid command for fetch approval: ${command::class.simpleName}")
                CommandResult(shouldContinue = false)
            }
        }
    }
    
    /**
     * Handle batch approval commands
     */
    private suspend fun FlowCollector<ServerSentEvent<String>>. handleBatchApproval(
        context: AgentContext, 
        command: AgentCommand
    ): CommandResult {
        val batchState = context.state as AgentState.AwaitingBatchApproval
        
        return when (command) {
            is AgentCommand.ApproveBatch -> {
                event("agent.run.batch_approved", "Batch approved by user")
                
                // Save the approved classifications
                val savedCount = saveClassifications(batchState.classifications)
                context.totalConversationsClassified += savedCount
                context.allConversationIds.addAll(batchState.classifications.map { it.conversationId })
                
                // Emit batch data
                val batchData = BatchData(
                    conversationIds = batchState.classifications.map { it.conversationId },
                    classifications = batchState.classifications.map { 
                        mapOf("id" to it.conversationId, "label" to it.classification.name)
                    },
                    progress = progressPercent(context.totalConversationsClassified, context.targetSampleSize)
                )

                // Generate and stream batch summary
                val batchSummary = generateBatchSummary(context, batchState.classifications)
                streamText("agent.run.batch_summary", batchSummary)
                emitDataEvent("agent.run.batch_completed", batchData)
                context.approvalBatchCommandExecuted = AgentCommand.ApproveBatch
                
                // Continue to next state
                context.state = when {
                    context.totalConversationsClassified >= context.targetSampleSize -> AgentState.Summarizing
                    else -> AgentState.Fetching
                }
                
                CommandResult(shouldContinue = true)
            }
            is AgentCommand.RejectBatch -> {
                event("agent.run.batch_rejected", "Batch rejected: ${command.feedback}")
                context.failureLogs += "Batch rejected: ${command.feedback}\n"
                context.replanningReason = "batch_rejected"
                context.state = AgentState.Classifying
                CommandResult(shouldContinue = true)
            }
            is AgentCommand.Stop -> {
                event("agent.run.stopped", "Agent stopped by user command")
                context.state = AgentState.Stopped
                CommandResult(shouldContinue = false)
            }
            else -> {
                event("agent.run.error", "Invalid command for batch approval: ${command::class.simpleName}")
                CommandResult(shouldContinue = false)
            }
        }
    }
    
    /**
     * Result of command processing
     */
    private data class CommandResult(val shouldContinue: Boolean)

    /**
     * Core execution engine shared by run() and resumeFromCheckpoint()
     */
    private suspend fun executeAgent(context: AgentContext, isResume: Boolean): Flow<ServerSentEvent<String>> {
        return flow {
            if (!isResume) {
                event("agent.run.started", "Classification agent started successfully", runId = context.runId)
                logger.info { "Starting ClassificationAgent with runId: ${context.runId}, instructions: ${context.userInstructions}" }
            } else {
                event("agent.run.resumed", "Agent resumed from checkpoint")
                logger.info { "Resuming ClassificationAgent from checkpoint for runId: ${context.runId}" }
            }

            try {
                while (shouldContinueExecution(context)) {
                    runCatching {
                        when (context.state) {
                            is AgentState.Planning -> handlePlanningState(context)
                            is AgentState.Fetching -> handleFetchingState(context)
                            is AgentState.Classifying -> handleClassifyingState(context)
                            is AgentState.Saving -> handleSavingState(context)
                            is AgentState.Summarizing -> handleSummarizingState(context)
                            is AgentState.AwaitingFetchApproval -> {
                                // Save state and exit flow - user will submit command separately
                                handleAwaitingFetchApprovalState(context)
                                return@flow
                            }
                            is AgentState.AwaitingBatchApproval -> {
                                // Save state and exit flow - user will submit command separately
                                handleAwaitingBatchApprovalState(context)
                                return@flow
                            }
                            is AgentState.Completed -> return@flow
                            is AgentState.Stopped -> return@flow
                            is AgentState.Error -> return@flow
                        }
                    }.onFailure { e ->
                        logger.error(e) { "Error in state ${context.state}: ${e.message}" }
                        context.state = AgentState.Error(e.message ?: "Unknown error")
                        event("agent.run.error", "Error occurred: ${e.message}")
                    }

                    // Save checkpoint after each state transition
                    agentRunRepository.saveCheckpoint(context)

                    // Emit state transition event
                    when (context.state) {
                        is AgentState.Error -> {
                            context.state = AgentState.Stopped
                            event("agent.run.stopped", "Agent stopped due to error")
                        }
                        is AgentState.Completed -> {
                            // Emit final data
                            val finalData = FinalData(
                                allConversationIds = context.allConversationIds,
                                totalClassified = context.totalConversationsClassified,
                                runId = context.runId
                            )

                            agentRunRepository.saveAgentRunOutcome(AgentRunOutcome(runId = context.runId, conversationIds = context.allConversationIds))
                            event("agent.run.completed", 
                                  "Classification completed successfully! ${context.totalConversationsClassified} conversations classified.", runId = context.runId)
                        }
                        is AgentState.Stopped -> {
                            event("agent.run.stopped", "Agent execution stopped")
                        }
                        else -> { /* Continue execution */ }
                    }
                }
            } catch (e: Exception) {
                emit(BroadcastEvent("agent.run.error", "Fatal error: ${e.message}").toSSE())
                logger.error(e) { "Fatal error in ClassificationAgent: ${e.message}" }
            }
        }.catch { e ->
            emit(BroadcastEvent("agent.run.error", "Fatal error: ${e.message}").toSSE())
            logger.error(e) { "Fatal error in ClassificationAgent: ${e.message}" }
        }.onCompletion {
            logger.info { "ClassificationAgent execution completed for runId: ${context.runId}" }
        }
    }

    /**
     * Check if the agent should continue executing based on current state.
     */
    private fun shouldContinueExecution(context: AgentContext): Boolean {
        return when (context.state) {
            is AgentState.Completed, 
            is AgentState.Stopped, 
            is AgentState.Error -> false
            else -> true
        }
    }

    /**
     * Handle the Planning state - create or update the classification plan.
     */
    private suspend fun FlowCollector<ServerSentEvent<String>>.handlePlanningState(context: AgentContext) {
        event("agent.run.planning.started", "Creating classification plan...")
        
        val plan = createPlan(context)
        context.currentPlan = plan
        context.plansCount++
        
        // Stream the plan using the new streaming utility
        streamText("agent.run.plan_summary", plan.planDetails)
        event("agent.run.planning.completed", "Plan created successfully", runId = context.runId)
        
        // Automatically transition to fetching (no plan approval needed)
        context.state = AgentState.Fetching
    }

    /**
     * Handle the Fetching state - retrieve unclassified conversations.
     */
    private suspend fun FlowCollector<ServerSentEvent<String>>.handleFetchingState(context: AgentContext) {
        val remainingConversations = context.targetSampleSize - context.totalConversationsClassified
        val batchSize = minOf(config.maxBatch, remainingConversations)
        val isAllFetchNotApproved = context.approvalFetchCommandExecuted != AgentCommand.ApproveAllFetch

        if (batchSize <= 0) {
            context.state = AgentState.Summarizing
            return
        }

        if(isAllFetchNotApproved) {
            event("agent.run.fetching.started", "Fetching up to $batchSize conversations for classification...")
        }

        val result = fetchUnclassifiedConversations(batchSize, context.currentPlan?.mongoQueryMapJson ?: "")
        
        if (!result.isSuccess) {
            context.failureLogs += "Fetch error: ${result.failureLog}\n"
            
            // Auto-retry with new plan (no user intervention needed)
            if (context.plansCount < config.maxPlans) {
                context.state = AgentState.Planning
                context.replanningReason = "fetch_failure"
                event("agent.run.fetching.error", "Fetch failed due to error: ${result.failureLog}. Require new plan.")
                event("agent.run.replanning", "Fetch failed - creating new plan automatically: ${result.failureLog}")
                streamText("agent.run.replanning", "Failed to fetch conversations due to error: ${result.failureLog}. Creating new plan.")
                return
            } else {
                // Only error out after exhausting all replanning attempts
                context.state = AgentState.Error("Max plans reached after fetch failures: ${result.failureLog}")
                return
            }
        }

        val conversations = result.data as List<Conversation>
        
        if (conversations.isEmpty()) {
            // Auto-retry with new plan (no user intervention needed)
            if (context.plansCount < config.maxPlans) {
                context.failureLogs += "No conversations found with current query criteria\n"
                context.state = AgentState.Planning
                context.replanningReason = "no_conversations_found"
                event("agent.run.fetching.stopped", "No conversations found - require new plan")
                event("agent.run.replanning", "No conversations found with old plamn - creating new plan automatically")
                streamText("agent.run.replanning", "No conversations found with old plan. Creating new plan.")
                return
            } else {
                context.state = AgentState.Summarizing
                event("agent.run.fetching.stopped", "max limit of number of plans exhausted.")
                return
            }
        }

        event("agent.run.fetching.completed", "Fetched ${conversations.size} conversations successfully")
        
        // Transition to awaiting fetch approval with the fetched data and current plan
        when(context.approvalFetchCommandExecuted) {
            is AgentCommand.ApproveAllFetch -> context.state = AgentState.Classifying
            else -> context.state = AgentState.AwaitingFetchApproval(conversations, context.currentPlan!!)
        }
    }

    /**
     * Handle the Classifying state - classify the fetched conversations.
     */
    private suspend fun FlowCollector<ServerSentEvent<String>>.handleClassifyingState(context: AgentContext) {
        // Check model call limits
        if (context.modelCallCount >= config.maxModelCalls) {
            context.state = AgentState.Stopped
            event("agent.run.stopped", "Maximum model calls (${config.maxModelCalls}) reached")
            return
        }

        // Use conversations from context that were fetched in the previous state
        val conversations = context.fetchedConversations
        
        if (conversations.isEmpty()) {
            context.state = AgentState.Summarizing
            return
        }

        if(context.approvalBatchCommandExecuted == AgentCommand.ApproveBatch) {
            event("agent.run.classifying_next_batch", "Starting with next batch of size: ${conversations.size}")
            streamText("agent.run.classifying_next_batch", "Starting with next batch for classification of ${conversations.size} conversations.")
        }

        event("agent.run.classifying.started", 
              "Classifying ${conversations.size} conversations using AI model...")
        
        event("agent.run.output_text.started", "Generating classifications...")
        
        val classifications = classifyConversations(
            context.apiKey, 
            conversations, 
            context.currentPlan?.additionalInstructions ?: ""
        )
        
        event("agent.run.output_text.done")
        context.modelCallCount++

        // Store classifications in pendingClassifications for batch approval
        context.pendingClassifications = classifications

        val progressPercent = progressPercent(context.totalConversationsClassified + classifications.size, context.targetSampleSize)
        
        event("agent.run.classifying.completed", 
              "Successfully classified ${classifications.size} conversations ($progressPercent% complete)", runId = context.runId)

        // Transition to awaiting batch approval
        context.state = AgentState.AwaitingBatchApproval(classifications)
    }

    /**
     * Handle the Saving state - persist classification results.
     */
    private suspend fun FlowCollector<ServerSentEvent<String>>.handleSavingState(context: AgentContext) {
        // Use the approved classifications from the batch approval workflow
        val classifications = context.pendingClassifications
        
        if (classifications.isEmpty()) {
            context.state = AgentState.Summarizing
            return
        }

        event("agent.run.saving.started", "Saving classification results to database...")

        val savedCount = saveClassifications(classifications)
        context.totalConversationsClassified += savedCount

        val progressPercent = progressPercent(context.totalConversationsClassified, context.targetSampleSize)
        
        event("agent.run.saving.completed", 
              "Saved $savedCount classifications. Total progress: ${context.totalConversationsClassified}/${context.targetSampleSize} ($progressPercent%)", runId = context.runId)

        // Clear the processed data from context
        context.fetchedConversations = emptyList()
        context.pendingClassifications = emptyList()

        // Check if we've reached the target or should continue
        if (context.totalConversationsClassified >= context.targetSampleSize) {
            context.state = AgentState.Summarizing
        } else {
            // Normal flow: continue with same plan for next batch
            context.state = AgentState.Fetching
        }
    }

    /**
     * Handle the Summarizing state - generate a summary of the classification run.
     */
    private suspend fun FlowCollector<ServerSentEvent<String>>.handleSummarizingState(context: AgentContext) {
        event("agent.run.summarizing.started", "Generating run summary...")
        
        val summary = generateSummary(context)
        context.summary = summary
        
        // Stream the summary using the new streaming utility
        streamText("agent.run.summary", summary)
        event("agent.run.summarizing.completed", "Summary generated successfully")

        if(context.totalConversationsClassified == 0) {
            context.state = AgentState.Stopped
        } else {
            context.state = AgentState.Completed
        }
        agentRunRepository.saveCheckpoint(context)
    }

    /**
     * Handle the AwaitingFetchApproval state - wait for human approval of the fetched data and plan.
     */
    private suspend fun FlowCollector<ServerSentEvent<String>>.handleAwaitingFetchApprovalState(context: AgentContext) {
        val fetchState = context.state as AgentState.AwaitingFetchApproval
        
        // Prepare approval data with both plan and fetched conversations
        val approvalData = mapOf(
            "plan" to mapOf(
                "targetSampleSize" to fetchState.plan.targetSampleSize,
                "planDetails" to fetchState.plan.planDetails,
                "additionalInstructions" to fetchState.plan.additionalInstructions
            ),
            "fetchedData" to mapOf(
                "conversationCount" to fetchState.fetchedConversations.size,
                "conversationIds" to fetchState.fetchedConversations.map { it.id }, // Show first 10 IDs
            )
        )
        
        event("agent.run.awaiting_fetch_approval", 
              "Waiting for approval of fetched data (${fetchState.fetchedConversations.size} conversations)", 
              approvalData, 
              context.runId)
        
        // Save state and exit flow - user will submit command separately
        agentRunRepository.saveCheckpoint(context)
    }

    /**
     * Handle the AwaitingBatchApproval state - wait for human approval of the batch.
     */
    private suspend fun FlowCollector<ServerSentEvent<String>>.handleAwaitingBatchApprovalState(context: AgentContext) {
        // Show first 5 classifications for approval
        val previewClassifications = context.pendingClassifications.map {
            mapOf("id" to it.conversationId, "label" to it.classification.name)
        }
        
        event("agent.run.awaiting_batch_approval", "Waiting for batch approval", previewClassifications)
        
        // Save state and exit flow - user will submit command separately
        agentRunRepository.saveCheckpoint(context)
        return@handleAwaitingBatchApprovalState
    }

    /**
     * Central event emission helper for consistent progress reporting.
     */
    private suspend fun FlowCollector<ServerSentEvent<String>>.event(
        type: String,
        msg: String = "",
        details: Any? = null,
        runId: String ?= null
    ) {
        val eventData = BroadcastEvent(type, msg, details, runId)
        emit(eventData.toSSE())
    }

    /**
     * Calculate progress percentage rounded to nearest integer.
     */
    private fun progressPercent(current: Int, target: Int): Int {
        return if (target > 0) ((current.toDouble() / target) * 100).roundToInt() else 0
    }

    /**
     * Create a classification plan using LLM with structured output.
     */
    private suspend fun createPlan(context: AgentContext): ConvClassificationPlan {
        // Check plan limits
        if (context.plansCount >= config.maxPlans) {
            throw IllegalStateException("Maximum number of plans (${config.maxPlans}) reached")
        }
        
        val progressInfo = if (context.totalConversationsClassified > 0) {
            "Progress so far: ${context.totalConversationsClassified} conversations already classified."
        } else ""
        
        val replanningContext = if (context.replanningReason.isNotEmpty()) {
            "REPLANNING CONTEXT: This is a replanning attempt triggered by: ${context.replanningReason}. Please analyze failures and adjust strategy accordingly."
        } else ""

        val prompt = """
You are a conversation classification planner. Your task is to analyze user instructions and create a plan for 
sampling and classifying conversations. You need to determine:

1. Target sample size (maximum 100, default 20 if not specified)  
2. A Mongo query to pull the required conversations from MongoDB.  
   **Make sure any date-time comparisons use the shell's `ISODate("YYYY-MM-DDTHH:mm:ss.SSSZ")` syntax.**  
   **Make sure only conversations with classification == null are pulled.**
3. Any additional instructions for classification. Do not include conversation filtering instructions as additional instructions.  
4. Whether the user has requested to stop the process.
5. Provide user readable plan details in markdown format in short can concise bullet points.
6. If there are failures in the previous run then pay attention to those failures while generating new plan.

**IMPORTANT FOR REPLANNING:**
- If this is a replanning attempt (failures exist), analyze what went wrong and adapt your strategy
- For fetch failures, consider: broader date ranges, different metadata filters, simpler queries
- For "no conversations found" failures, try: removing restrictive filters, expanding time windows, different collection criteria
- For database errors, try: simpler queries, smaller date ranges, avoiding complex nested conditions
- Always explain in planDetails what you changed from the previous attempt and why

Json schema of conversation document in DB is:
$conversationJsonSchema

Today's date is ${Instant.now()}

$progressInfo

$replanningContext
""".trimIndent()

        val responseFormat = mapOf(
            "type" to "json_schema",
            "json_schema" to mapOf(
                "name" to "planningSchema",
                "schema" to jacksonObjectMapper().readValue<Map<String, JsonValue>>(planningResponseFormat)
            )
        )

        val messages = mutableListOf(mapOf("role" to "system", "content" to prompt))
        if (context.userInstructions.isNotEmpty()) {
            messages.add(mapOf("role" to "user", "content" to "Analyze the following user instructions: ${context.userInstructions}"))
        }
        if (context.failureLogs.isNotEmpty()) {
            messages.add(mapOf("role" to "user", "content" to "Failures in the past runs: ${context.failureLogs}"))
        }

        val request = CreateCompletionRequest(
            messages = messages,
            model = "openai@gpt-4.1",
            response_format = responseFormat,
            stream = false,
            store = false
        )

        val response = modelService.fetchCompletionPayload(request, context.apiKey)
        val plan = mapper.readValue<ConvClassificationPlan>(response)

        //TEMP hack to deal with mongo plan persistence issue with key like map.category etc
        val finalPlanForPersistence = plan.copy(mongoQueryMapJson = mapper.writeValueAsString(plan.mongoQueryMap), mongoQueryMap = emptyMap())

        // Update context with plan details
        context.targetSampleSize = finalPlanForPersistence.targetSampleSize
        context.replanningReason = ""
        
        // Check if model requested to stop
        if (finalPlanForPersistence.stopRequested) {
            throw IllegalStateException("Classification stopped as requested by the planning model")
        }
        
        // Check if target already reached
        if (context.totalConversationsClassified >= context.targetSampleSize) {
            throw IllegalStateException("Target sample size already achieved")
        }
        
        return finalPlanForPersistence
    }

    /**
     * Fetch unclassified conversations from MongoDB based on the plan's query.
     */
    private suspend fun fetchUnclassifiedConversations(
        limit: Int,
        mongoQueryMapJson: String
    ): FunctionResult<List<Conversation>> {
        return try {
            val queryDoc = Document.parse(mongoQueryMapJson)
            val basicQuery = BasicQuery(queryDoc).limit(limit)

            FunctionResult(data = reactiveMongoTemplate
                .find(basicQuery, Conversation::class.java, "labelled_conversations")
                .collectList()
                .awaitSingle())

        } catch (ex: Exception) {
            FunctionResult(isSuccess = false, failureLog = ex.message ?: "Unknown error during fetch")
        }
    }

    /**
     * Classify conversations using LLM with structured output.
     */
    private suspend fun classifyConversations(
        apiKey: String, 
        conversations: List<Conversation>, 
        userInstructions: String
    ): List<ClassificationOutput> {
        require(conversations.isNotEmpty()) { "conversations can't be empty" }
        
        val prompt = """
You are a customer service conversations Classification Agent. Your goal is to classify the given set of conversations into two categories RESOLVED / UNRESOLVED.
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

        val userMessage = mapOf("role" to "user", "content" to conversations.joinToString("\n") { 
            "conversationId: ${it.id}, messages: ${it.messages}" 
        })
        
        val messages = mutableListOf(mapOf("role" to "system", "content" to prompt), userMessage)
        if (userInstructions.isNotEmpty()) {
            messages.add(mapOf("role" to "user", "content" to userInstructions))
        }

        val request = CreateCompletionRequest(
            messages = messages,
            model = "openai@gpt-4o-mini",
            response_format = responseFormat,
            stream = false,
            store = false
        )

        return try {
            val response: ClassificationOutputResponse = modelService.createCompletion(request, apiKey)
            response.outputs
        } catch (e: Exception) {
            logger.error(e) { "Error during classification" }
            throw e
        }
    }

    /**
     * Save classification results to the database.
     */
    private suspend fun saveClassifications(classifications: List<ClassificationOutput>): Int {
        var savedCount = 0
        
        for (classification in classifications) {
            try {
                val conversation = conversationRepository.getConversation(classification.conversationId)
                    ?: continue

                val updatedConversation = conversation.copy(classification = classification.classification)
                conversationRepository.createConversation(updatedConversation)
                savedCount++

                logger.info { "Saved classification ${classification.classification} for conversation ${classification.conversationId}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to save classification for conversation ${classification.conversationId}" }
            }
        }

        return savedCount
    }

    /**
     * Generate a summary of the classification run using LLM.
     */
    private suspend fun generateSummary(context: AgentContext): String {
        val prompt = """
Summarize today's conversation classification run for the user in 3 bullet points.

Run details:
- Total conversations classified: ${context.totalConversationsClassified}
- Target sample size: ${context.targetSampleSize}
- Number of plans created: ${context.plansCount}
- Model calls made: ${context.modelCallCount}
- User instructions: ${context.userInstructions}

Keep it concise and user-friendly.
""".trimIndent()

        val request = CreateCompletionRequest(
            messages = listOf(mapOf("role" to "system", "content" to prompt)),
            model = "openai@gpt-4o-mini",
            stream = false,
            store = false
        )

        return try {
            modelService.fetchCompletionPayload(request, context.apiKey)
        } catch (e: Exception) {
            logger.error(e) { "Error generating summary" }
            "Classification run completed with ${context.totalConversationsClassified} conversations processed."
        }
    }

    /**
     * Generate a summary of the current batch classification using LLM.
     */
    private suspend fun generateBatchSummary(context: AgentContext, classifications: List<ClassificationOutput>): String {
        val resolvedCount = classifications.count { it.classification.name == "RESOLVED" }
        val unresolvedCount = classifications.count { it.classification.name == "UNRESOLVED" }
        
        val prompt = """
Summarize this batch of conversation classifications in 2-3 sentences for the user.

Batch details:
- Total conversations in batch: ${classifications.size}
- Resolved conversations: $resolvedCount
- Unresolved conversations: $unresolvedCount
- Overall progress: ${context.totalConversationsClassified}/${context.targetSampleSize}

Keep it concise and informative.
""".trimIndent()

        val request = CreateCompletionRequest(
            messages = listOf(mapOf("role" to "system", "content" to prompt)),
            model = "openai@gpt-4o-mini",
            stream = false,
            store = false
        )

        return try {
            modelService.fetchCompletionPayload(request, context.apiKey)
        } catch (e: Exception) {
            logger.error(e) { "Error generating batch summary" }
            "Batch completed: $resolvedCount resolved, $unresolvedCount unresolved conversations."
        }
    }
}

// Reuse existing data classes and constants from ConvClassificationAgent
data class BroadcastEvent(val type: String, val logMessage: String, val data: Any ?= null, val runId: String ?= null) {
    fun toSSE(): ServerSentEvent<String> {
        val eventData = jacksonObjectMapper().writeValueAsString(this)
        return ServerSentEvent
            .builder(eventData)
            .event(" $type")
            .build()
    }
}

data class ClassificationOutput(val conversationId: String, val classification: CLASSIFICATION)
data class ClassificationOutputResponse(val outputs: List<ClassificationOutput>)

data class ConvClassificationPlan(
    val targetSampleSize: Int,
    val stopRequested: Boolean,
    val additionalInstructions: String,
    val mongoQueryMap: Map<String, Any>,
    val mongoQueryMapJson: String = "",
    val planDetails: String
)

data class FunctionResult<T>(
    val isSuccess: Boolean = true, 
    val failureLog: String = "", 
    val data: T? = null
)



const val planningResponseFormat = """
{
  "type": "object",
  "properties": {
    "targetSampleSize": {
      "type": "integer",
      "description": "Number of conversations to classify (maximum 100, default 20)"
    },
    "stopRequested": {
      "type": "boolean",
      "description": "Whether the user has requested to stop the process"
    },
    "additionalInstructions": {
      "type": "string",
      "description": "Apart from filtering, any additional instructions for the classification process"
    },
    "mongoQueryMap": {
      "type": "object",
      "description": "A MongoDB filter document to apply (e.g. { \"createdAt\": { \"${'$'}gte\": \"2025-04-01T00:00:00Z\" }, ... })"
    },
    "planDetails": {
      "type": "string",
      "description": "Overall plan in user readable markdown format."
    }
  },
  "required": ["targetSampleSize", "stopRequested", "additionalInstructions", "mongoQueryMap", "planDetails"],
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

