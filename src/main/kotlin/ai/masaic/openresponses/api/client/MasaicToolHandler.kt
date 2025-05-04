package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.extensions.toChatCompletionMessageParam
import ai.masaic.openresponses.api.support.service.GenAIObsAttributes
import ai.masaic.openresponses.api.support.service.TelemetryService
import ai.masaic.openresponses.tool.CompletionToolRequestContext
import ai.masaic.openresponses.tool.ToolRequestContext
import ai.masaic.openresponses.tool.ToolService
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.responses.*
import io.micrometer.observation.Observation
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import java.util.*
import kotlin.String

/**
 * Handles tool-related operations for the Masaic OpenAI API integration.
 * Encapsulates the logic for processing tool calls and tool outputs.
 */
@Component
class MasaicToolHandler(
    private val toolService: ToolService,
    private val objectMapper: ObjectMapper,
    private val telemetryService: TelemetryService,
) {
    private val logger = KotlinLogging.logger {}

    data class CompletionToolHandlingResult(
        val updatedMessages: List<ChatCompletionMessageParam>,
        val hasUnresolvedClientTools: Boolean,
    )

    /**
     * Processes tool calls from a chat completion and executes relevant tools.
     * Each tool execution is recorded using the telemetry observation API.
     * Handles both native tools (executed server-side) and non-native tools (parked for client).
     *
     * @param chatCompletion The ChatCompletion containing potential tool calls
     * @param params The original request parameters for the chat completion
     * @param parentObservation Optional parent observation for telemetry.
     * @param openAIClient The OpenAI client instance.
     * @return List of ChatCompletionMessageParam including original messages, assistant message with tool calls, and tool output messages.
     */
    fun handleCompletionToolCall(
        chatCompletion: ChatCompletion,
        params: ChatCompletionCreateParams,
        parentObservation: Observation? = null,
        openAIClient: OpenAIClient,
    ): CompletionToolHandlingResult {
        logger.debug { "Processing tool calls from ChatCompletion: ${chatCompletion.id()}" }

        val assistantMessage = chatCompletion.choices().firstOrNull()?.message()
        if (assistantMessage == null || !assistantMessage.toolCalls().isPresent || assistantMessage.toolCalls().get().isEmpty()) {
            logger.warn { "No tool calls found in ChatCompletion: ${chatCompletion.id()}. Returning original messages." }
            // Return original messages plus the assistant message if it exists
            val messages = params.messages() + listOfNotNull(assistantMessage?.toChatCompletionMessageParam(objectMapper))
            return CompletionToolHandlingResult(messages, false) // No unresolved tools here
        }

        val aliasMap = toolService.buildAliasMap(params.tools().orElse(emptyList()))
        val context = CompletionToolRequestContext(aliasMap, params)

        val updatedMessages = params.messages().toMutableList()
        // Add assistant's message WITH the tool_calls request
        assistantMessage.toChatCompletionMessageParam(objectMapper)?.let { updatedMessages.add(it) }

        val toolCalls = assistantMessage.toolCalls().get()
        var nonNativeToolsFound = false
        var nativeToolsExecuted = 0

        logger.debug { "Processing ${toolCalls.size} tool calls from assistant message" }

        toolCalls.forEach { toolCall ->
            val function = toolCall.function()
            val toolName = function.name()
            val toolCallId = toolCall.id()

            if (toolService.getFunctionTool(toolName, context) != null) {
                // Native tool: Execute and add output
                logger.info { "Executing native tool: $toolName with ID: $toolCallId" }
                val toolResult =
                    executeToolWithObservationForCompletion(
                        toolName = toolName,
                        arguments = function.arguments(),
                        toolId = toolCallId,
                        toolMetadata = mapOf("toolCallId" to toolCallId),
                        parentObservation = parentObservation,
                        params = params,
                        openAIClient = openAIClient,
                        context = context,
                    )

                val toolOutputMessage =
                    ChatCompletionMessageParam.ofTool(
                        ChatCompletionToolMessageParam
                            .builder()
                            .toolCallId(toolCallId)
                            .content(toolResult ?: "Tool execution resulted in null.")
                            .build(),
                    )
                updatedMessages.add(toolOutputMessage)
                nativeToolsExecuted++
                logger.debug { "Added tool output message for native tool: $toolName" }
            } else {
                // Non-native tool: Log, set flag, DO NOT add a message
                logger.info { "Non-native tool requested: $toolName with ID: $toolCallId. Parking for client." }
                nonNativeToolsFound = true
            }
        }

        // Sanity check (optional): Compare counts explicitly
        if (nativeToolsExecuted < toolCalls.size && !nonNativeToolsFound) {
            logger.warn("Tool count mismatch: ${toolCalls.size} calls requested, but only $nativeToolsExecuted native outputs generated, yet no non-native tools were flagged.")
            // Decide how critical this is - perhaps force nonNativeToolsFound = true?
        }
        if (nativeToolsExecuted == toolCalls.size && nonNativeToolsFound) {
            logger.warn("Tool count mismatch: All ${toolCalls.size} calls generated native outputs, but non-native tools were somehow flagged.")
            // Decide how critical this is - perhaps force nonNativeToolsFound = false?
        }

        return CompletionToolHandlingResult(
            updatedMessages = updatedMessages,
            hasUnresolvedClientTools = nonNativeToolsFound,
        )
    }

    /**
     * Executes a tool using the telemetry observation API specifically for the Completion flow.
     * Does not involve SSE emitters.
     *
     * @param toolName The name of the tool to execute
     * @param arguments The arguments to pass to the tool
     * @param toolId The ID of the tool call
     * @param toolMetadata Additional metadata for the tool execution.
     * @param parentObservation Optional parent observation for telemetry.
     * @param params The original request parameters for the chat completion.
     * @param openAIClient The OpenAI client instance.
     * @param context The context for tool execution, containing alias maps and original params.
     * @return The string result of the tool execution, or null if execution fails or returns null.
     */
    private fun executeToolWithObservationForCompletion(
        toolName: String,
        arguments: String,
        toolId: String,
        toolMetadata: Map<String, Any>,
        parentObservation: Observation? = null,
        params: ChatCompletionCreateParams,
        openAIClient: OpenAIClient,
        context: CompletionToolRequestContext,
    ): String? =
        telemetryService.withClientObservation("builtin.tool.execute", parentObservation) { observation ->
            observation.lowCardinalityKeyValue(GenAIObsAttributes.OPERATION_NAME, "execute_tool_completion")
            observation.lowCardinalityKeyValue(GenAIObsAttributes.TOOL_NAME, toolName)
            observation.highCardinalityKeyValue(GenAIObsAttributes.TOOL_CALL_ID, toolId)
            try {
                // Use runBlocking to call the suspending function from a synchronous context
                runBlocking {
                    toolService.executeTool(toolName, arguments, params, openAIClient, {}, toolMetadata, context)
                }
            } catch (e: Exception) {
                observation.lowCardinalityKeyValue(GenAIObsAttributes.ERROR_TYPE, "${e.javaClass}")
                logger.error(e) { "Error executing tool $toolName for completion: ${e.message}" }
                throw e
            }
        }

    /**
     * Processes tool calls from a chat completion and executes relevant tools.
     * Each tool execution is recorded using the telemetry observation API.
     *
     * @param chatCompletion The ChatCompletion containing potential tool calls
     * @param params The original request parameters
     * @return List of ResponseInputItems with both tool calls and their outputs
     */
    fun handleMasaicToolCall(
        chatCompletion: ChatCompletion,
        params: ResponseCreateParams,
        parentObservation: Observation? = null,
        openAIClient: OpenAIClient,
    ): List<ResponseInputItem> {
        logger.debug { "Processing tool calls from ChatCompletion: ${chatCompletion.id()}" }
        
        // Create context with alias mappings
        val aliasMap = toolService.buildAliasMap(params.tools().orElse(emptyList()))
        val context = ToolRequestContext(aliasMap, params)
        
        val responseInputItems =
            if (params.input().isResponse()) {
                params.input().asResponse().toMutableList()
            } else {
                mutableListOf(
                    ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage
                            .builder()
                            .content(params.input().asText())
                            .role(EasyInputMessage.Role.USER)
                            .build(),
                    ),
                )
            }
        val parked = mutableListOf<ResponseInputItem>()

        // Add text content from the completion to parked items
        chatCompletion
            .choices()
            .filter {
                it.message().content().isPresent &&
                    it
                        .message()
                        .content()
                        .get()
                        .isNotBlank()
            }.forEach {
                logger.trace { "Adding text content to parked items" }
                parked.add(
                    ResponseInputItem.ofResponseOutputMessage(
                        ResponseOutputMessage
                            .builder()
                            .content(
                                listOf(
                                    ResponseOutputMessage.Content.ofOutputText(
                                        ResponseOutputText
                                            .builder()
                                            .text(it.message().content().get())
                                            .annotations(emptyList())
                                            .build(),
                                    ),
                                ),
                            ).id(UUID.randomUUID().toString())
                            .role(JsonValue.from("assistant"))
                            .status(ResponseOutputMessage.Status.COMPLETED)
                            .build(),
                    ),
                )
            }

        // Process tool calls from the ChatCompletion
        chatCompletion.choices().forEach { chatChoice ->
            if (ChatCompletion.Choice.FinishReason.TOOL_CALLS == chatChoice.finishReason()) {
                val message = chatChoice.message()
                val toolCalls = message.toolCalls().get()
                logger.debug { "Processing ${toolCalls.size} tool calls" }

                toolCalls.forEach { tool ->
                    val function = tool.function()
                    if (toolService.getFunctionTool(function.name(), context) != null) {
                        logger.info { "Executing tool: ${function.name()} with ID: ${tool.id()}" }

                        // Add the function call to response items
                        responseInputItems.add(
                            ResponseInputItem.ofFunctionCall(
                                ResponseFunctionToolCall
                                    .builder()
                                    .callId(tool.id())
                                    .id(tool.id())
                                    .name(function.name())
                                    .arguments(function.arguments())
                                    .build(),
                            ),
                        )

                        // Execute the tool using the observation API
                        executeToolWithObservation(
                            function.name(),
                            function.arguments(),
                            tool.id(),
                            mapOf("toolId" to tool.id()),
                            parentObservation,
                            params,
                            openAIClient,
                            {},
                            context,
                        ) { toolResult ->
                            if (toolResult != null) {
                                logger.debug { "Tool execution successful for ${function.name()}" }
                                responseInputItems.add(
                                    ResponseInputItem.ofFunctionCallOutput(
                                        ResponseInputItem.FunctionCallOutput
                                            .builder()
                                            .callId(tool.id())
                                            .id(tool.id())
                                            .output(toolResult)
                                            .build(),
                                    ),
                                )
                            } else {
                                logger.warn { "Tool execution returned null for ${function.name()}" }
                            }
                        }
                    } else {
                        logger.info { "Unsupported tool requested: ${function.name()}, parking for client handling" }
                        parked.add(
                            ResponseInputItem.ofFunctionCall(
                                ResponseFunctionToolCall
                                    .builder()
                                    .callId(tool.id())
                                    .id(tool.id())
                                    .name(function.name())
                                    .arguments(function.arguments())
                                    .build(),
                            ),
                        )
                    }
                }
            }
        }

        logger.debug { "Adding ${parked.size} parked items to response" }
        responseInputItems.addAll(parked)
        return responseInputItems
    }

    /**
     * Executes a tool using the telemetry observation API and processes the result with the provided callback.
     *
     * @param toolName The name of the tool to execute
     * @param arguments The arguments to pass to the tool
     * @param toolId The ID of the tool call
     * @param resultHandler A function that processes the tool execution result
     */
    private fun executeToolWithObservation(
        toolName: String,
        arguments: String,
        toolId: String,
        toolMetadata: Map<String, Any>,
        parentObservation: Observation? = null,
        params: ResponseCreateParams,
        openAIClient: OpenAIClient,
        eventEmitter: ((ServerSentEvent<String>) -> Unit),
        context: ToolRequestContext,
        resultHandler: (String?) -> Unit,
    ) {
        telemetryService.withClientObservation("builtin.tool.execute", parentObservation) { observation ->
            observation.lowCardinalityKeyValue(GenAIObsAttributes.OPERATION_NAME, "execute_tool")
            observation.lowCardinalityKeyValue(GenAIObsAttributes.TOOL_NAME, toolName)
            observation.highCardinalityKeyValue(GenAIObsAttributes.TOOL_CALL_ID, toolId)
            try {
                val toolResult =
                    runBlocking { 
                        toolService.executeTool(toolName, arguments, params, openAIClient, eventEmitter, toolMetadata, context) 
                    }
                resultHandler(toolResult)
            } catch (e: Exception) {
                observation.lowCardinalityKeyValue(GenAIObsAttributes.ERROR_TYPE, "${e.javaClass}")
                logger.error(e) { "Error executing tool $toolName: ${e.message}" }
                throw e
            }
        }
    }

    /**
     * Processes tool calls from a Response and executes relevant tools.
     *
     * @param params The original request parameters
     * @param response The Response object containing potential tool calls
     * @param eventEmitter Optional callback function to emit tool execution events
     * @return List of ResponseInputItems with both tool calls and their outputs
     */
    fun handleMasaicToolCall(
        params: ResponseCreateParams,
        response: Response,
        eventEmitter: ((ServerSentEvent<String>) -> Unit),
        parentObservation: Observation? = null,
        openAIClient: OpenAIClient,
    ): List<ResponseInputItem> {
        logger.debug { "Processing tool calls from Response ID: ${response.id()}" }

        // Create context with alias mappings
        val aliasMap = toolService.buildAliasMap(params.tools().orElse(emptyList()))
        val context = ToolRequestContext(aliasMap, params)

        val responseInputItems =
            if (params.input().isResponse()) {
                params.input().asResponse().toMutableList()
            } else {
                mutableListOf(
                    ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage
                            .builder()
                            .content(params.input().asText())
                            .role(EasyInputMessage.Role.USER)
                            .build(),
                    ),
                )
            }

        val parked = mutableListOf<ResponseInputItem>()

        // Add message outputs to parked items
        val messageOutputs =
            response.output().filter {
                it.isMessage() &&
                    it
                        .message()
                        .get()
                        .content()
                        .isNotEmpty()
            }
        logger.trace { "Found ${messageOutputs.size} message outputs to park" }
        messageOutputs.forEach {
            parked.add(ResponseInputItem.ofResponseOutputMessage(it.asMessage()))
        }

        // Process function calls from the response
        val functionCalls = response.output().filter { it.isFunctionCall() }
        logger.debug { "Processing ${functionCalls.size} function calls" }

        functionCalls.forEachIndexed { index, tool ->
            val function = tool.asFunctionCall()

            if (toolService.getFunctionTool(function.name(), context) != null) {
                logger.info { "Executing tool: ${function.name()} with ID: ${function.id()}" }

                val eventPrefix = "response.${function.name().lowercase().replace("^\\W".toRegex(), "_")}"

                eventEmitter.invoke(
                    ServerSentEvent
                        .builder<String>()
                        .event("$eventPrefix.in_progress")
                        .data(
                            objectMapper.writeValueAsString(
                                mapOf<String, String>(
                                    "item_id" to function.id(),
                                    "output_index" to index.toString(),
                                    "type" to "$eventPrefix.in_progress",
                                ),
                            ),
                        ).build(),
                )

                // Add the function call to response items
                responseInputItems.add(
                    ResponseInputItem.ofFunctionCall(
                        ResponseFunctionToolCall
                            .builder()
                            .callId(function.callId())
                            .id(function.id())
                            .name(function.name())
                            .arguments(function.arguments())
                            .build(),
                    ),
                )

                eventEmitter.invoke(
                    ServerSentEvent
                        .builder<String>()
                        .event("$eventPrefix.executing")
                        .data(
                            objectMapper.writeValueAsString(
                                mapOf<String, String>(
                                    "item_id" to function.id(),
                                    "output_index" to index.toString(),
                                    "type" to "$eventPrefix.executing",
                                ),
                            ),
                        ).build(),
                )

                executeToolWithObservation(function.name(), function.arguments(), function.id(), mapOf("toolId" to function.id(), "eventIndex" to index), parentObservation, params, openAIClient, eventEmitter, context) { toolResult ->
                    if (toolResult != null) {
                        logger.debug { "Tool execution successful for ${function.name()}" }
                        responseInputItems.add(
                            ResponseInputItem.ofFunctionCallOutput(
                                ResponseInputItem.FunctionCallOutput
                                    .builder()
                                    .callId(function.callId())
                                    .id(function.id())
                                    .output(toolResult)
                                    .build(),
                            ),
                        )
                    } else {
                        logger.warn { "Tool execution returned null for ${function.name()}" }
                    }
                }

                eventEmitter.invoke(
                    ServerSentEvent
                        .builder<String>()
                        .event("$eventPrefix.completed")
                        .data(
                            objectMapper.writeValueAsString(
                                mapOf<String, String>(
                                    "item_id" to function.id(),
                                    "output_index" to index.toString(),
                                    "type" to "$eventPrefix.completed",
                                ),
                            ),
                        ).build(),
                )
            } else {
                logger.info { "Unsupported tool requested: ${function.name()}, parking for client handling" }
                parked.add(
                    ResponseInputItem.ofFunctionCall(
                        ResponseFunctionToolCall
                            .builder()
                            .callId(function.callId())
                            .id(function.id())
                            .name(function.name())
                            .arguments(function.arguments())
                            .build(),
                    ),
                )
            }
        }

        logger.debug { "Adding ${parked.size} parked items to response" }
        responseInputItems.addAll(parked)
        return responseInputItems
    }
}
