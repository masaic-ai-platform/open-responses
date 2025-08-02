package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.api.extensions.toChatCompletionMessageParam
import ai.masaic.openresponses.api.support.service.GenAIObsAttributes
import ai.masaic.openresponses.api.support.service.TelemetryService
import ai.masaic.openresponses.tool.CompletionToolRequestContext
import ai.masaic.openresponses.tool.ToolProtocol
import ai.masaic.openresponses.tool.ToolRequestContext
import ai.masaic.openresponses.tool.ToolService
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessage
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
import kotlin.jvm.optionals.getOrNull

private const val IMAGE_GENERATION_TOOL_NAME = "image_generation"

/**
 * Represents the outcome of handling tool calls in a non-streaming context.
 */
sealed class MasaicToolCallResult {
    /**
     * Indicates that tool processing should continue, potentially with more LLM calls.
     * @param items The list of input items, including original messages, tool calls, and tool outputs, for the next LLM call.
     */
    data class Continue(
        val items: List<ResponseInputItem>,
    ) : MasaicToolCallResult()

    /**
     * Indicates that tool processing should terminate, and a direct response should be sent to the user.
     * This is used when a terminal tool (like image_generation) is executed.
     * @param finalResponseInputItems The complete list of input items up to and including the terminal tool's call and output. Used for storing the interaction.
     * @param directResponse The Response object to be sent directly to the user, containing the terminal tool's output.
     */
    data class Terminate(
        val finalResponseInputItems: List<ResponseInputItem>,
        val directResponse: Response,
    ) : MasaicToolCallResult()
}

/**
 * Represents the outcome of handling tool calls in a streaming context.
 */
data class MasaicToolCallStreamingResult(
    /**
     * The list of response input items accumulated during tool processing, including tool calls and their outputs.
     */
    val toolResponseItems: List<ResponseInputItem>,
    /**
     * Flag indicating whether a terminal tool was executed and the streaming should terminate.
     */
    val shouldTerminate: Boolean = false,
    /**
     * The output item from the terminal tool (e.g., image_generation), if one was executed.
     * This item is intended to be the final piece of content sent to the user.
     */
    val terminalOutputItem: ResponseOutputItem? = null,
)

/**
 * Represents the outcome of handling tool calls in a non-streaming ChatCompletion context.
 */
sealed class CompletionToolCallOutcome {
    /**
     * Indicates that tool processing should continue, potentially with more LLM calls or client-side handling.
     * @param updatedMessages The list of messages for the next LLM call, or to inform client of parked tools.
     * @param hasUnresolvedClientTools True if non-native tools were encountered that the client needs to handle.
     */
    data class Continue(
        val updatedMessages: List<ChatCompletionMessageParam>,
        val hasUnresolvedClientTools: Boolean,
    ) : CompletionToolCallOutcome()

    /**
     * Indicates that tool processing should terminate, and a direct ChatCompletion (e.g., image) should be sent to the user.
     * @param finalChatCompletion The ChatCompletion to be sent directly to the user, containing the terminal tool's output.
     * @param messagesForStorage The complete list of messages up to and including the terminal tool's call and output. Used for storing the interaction.
     */
    data class Terminate(
        val finalChatCompletion: ChatCompletion,
        val messagesForStorage: List<ChatCompletionMessageParam>,
    ) : CompletionToolCallOutcome()
}

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
        openAIClient: OpenAIClient,
    ): CompletionToolCallOutcome {
        logger.debug { "Processing tool calls from ChatCompletion: ${chatCompletion.id()}" }

        val assistantMessage = chatCompletion.choices().firstOrNull()?.message()
        if (assistantMessage == null || !assistantMessage.toolCalls().isPresent || assistantMessage.toolCalls().get().isEmpty()) {
            logger.warn { "No tool calls found in ChatCompletion: ${chatCompletion.id()}. Returning original messages." }
            val messages = params.messages() + listOfNotNull(assistantMessage?.toChatCompletionMessageParam(objectMapper))
            // No unresolved tools here, and messages are for continuation (even if it's just to return to client)
            return CompletionToolCallOutcome.Continue(messages, false)
        }

        val aliasMap = toolService.buildAliasMap(params.tools().orElse(emptyList()))
        val context = CompletionToolRequestContext(aliasMap, params)

        val updatedMessages = params.messages().toMutableList()
        // Add assistant's message WITH the tool_calls request
        assistantMessage.toChatCompletionMessageParam(objectMapper).let { updatedMessages.add(it) }

        val toolCalls = assistantMessage.toolCalls().get()
        var nonNativeToolsFound = false
        var nativeToolsExecuted = 0

        logger.debug { "Processing ${toolCalls.size} tool calls from assistant message" }

        for (toolCall in toolCalls) { // Iterate to allow early exit for Terminate
            val function = toolCall.function()
            val toolName = function.name()
            val toolCallId = toolCall.id()

            if (toolService.getFunctionTool(toolName, context) != null) {
                // Native tool: Execute and add output
                logger.info { "Executing native tool: $toolName with ID: $toolCallId" }

                if (toolName == IMAGE_GENERATION_TOOL_NAME) {
                    logger.info { "Executing terminal tool (completion context): $toolName with ID: $toolCallId" }
                    var rawToolOutput: String? = null
                    try {
                        rawToolOutput =
                            runBlocking {
                                toolService.executeTool(
                                    toolName,
                                    arguments = function.arguments(),
                                    params = params, // ChatCompletionCreateParams
                                    openAIClient = openAIClient,
                                    eventEmitter = {}, // No SSE for non-streaming completion context tool call
                                    toolMetadata = mapOf("toolCallId" to toolCallId),
                                    context = context, // CompletionToolRequestContext
                                )
                            }
                    } catch (e: Exception) {
                        logger.error(e) { "Error executing terminal tool $toolName for completion: ${e.message}" }
                        rawToolOutput = "{\"error\": \"Error executing tool $toolName: ${e.message}\"}" // Encapsulate error in JSON-like string
                    }

                    var imageData: String? = null
                    var errorMessage: String? = null

                    if (rawToolOutput != null) {
                        try {
                            val typeRef = object : TypeReference<Map<String, Any>>() {}
                            val outputMap = objectMapper.readValue(rawToolOutput, typeRef)
                            imageData = outputMap["data"] as? String // Assuming data holds the base64 string or URL
                            if (imageData == null) {
                                errorMessage = outputMap["error"] as? String ?: "Tool $toolName executed but 'data' key is missing or not a string in output."
                                logger.warn { errorMessage }
                            }
                        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
                            errorMessage = "Tool $toolName executed but output was not valid JSON: $rawToolOutput"
                            logger.warn(e) { errorMessage }
                        }
                    } else {
                        errorMessage = "Tool $toolName execution resulted in null output."
                        logger.warn { errorMessage }
                    }

                    if (imageData != null) {
                        val toolOutputMessageForStorage =
                            ChatCompletionMessageParam.ofTool(
                                ChatCompletionToolMessageParam
                                    .builder()
                                    .toolCallId(toolCallId)
                                    .content(imageData) // Store the extracted image data
                                    .putAdditionalProperty("type", JsonValue.from("output_image"))
                                    .putAdditionalProperty("output_format", JsonValue.from("b64_json"))
                                    .build(),
                            )
                        updatedMessages.add(toolOutputMessageForStorage) // For storage log

                        val finalImageCompletionBuilder =
                            ChatCompletion
                                .builder()
                                .id(chatCompletion.id()) // Reuse original completion ID
                                .model(params.model().toString()) // Model from request
                                .created(System.currentTimeMillis() / 1000L) // Current time
                                .choices(
                                    listOf(
                                        ChatCompletion.Choice
                                            .builder()
                                            .index(0L)
                                            .message(
                                                ChatCompletionMessage
                                                    .builder()
                                                    .role(JsonValue.from("assistant"))
                                                    .content(imageData) // Use the extracted image data
                                                    .putAdditionalProperty("type", JsonValue.from("output_image"))
                                                    .putAdditionalProperty("output_format", JsonValue.from("b64_json"))
                                                    .refusal(null)
                                                    .build(),
                                            ).logprobs(null)
                                            .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                                            .build(),
                                    ),
                                )

                        if (chatCompletion.usage().isPresent) {
                            finalImageCompletionBuilder.usage(chatCompletion.usage().get())
                        }

                        val finalImageCompletion =
                            finalImageCompletionBuilder
                                .build()
                        
                        return CompletionToolCallOutcome.Terminate(finalImageCompletion, updatedMessages.toList())
                    } else {
                        val finalErrorMessage = errorMessage ?: "Tool $toolName failed or returned unexpected output."
                        logger.warn { "Terminal tool $toolName did not yield valid image data. Error: $finalErrorMessage" }
                        val errorToolOutputMessage =
                            ChatCompletionMessageParam.ofTool(
                                ChatCompletionToolMessageParam
                                    .builder()
                                    .toolCallId(toolCallId)
                                    .content(finalErrorMessage)
                                    .build(),
                            )
                        updatedMessages.add(errorToolOutputMessage)
                        nativeToolsExecuted++ // Count as executed, albeit with an error output
                    }
                } else {
                    // Regular native tool
                    val toolResult =
                        executeToolWithObservationForCompletion(
                            toolName = toolName,
                            toolDescription = toolService.getAvailableTool(toolName)?.description ?: "not_available",
                            arguments = function.arguments(),
                            toolId = toolCallId,
                            toolMetadata = mapOf("toolCallId" to toolCallId),
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
                }
            } else {
                // Non-native tool: Log, set flag, DO NOT add a message for LLM (it's for client)
                logger.info { "Non-native tool requested: $toolName with ID: $toolCallId. Parking for client." }
                nonNativeToolsFound = true
            }
        }

        // If loop completes without Terminate, it means either all tools were native (and not image_generation that succeeded),
        // or there were non-native tools, or a mix.
        return CompletionToolCallOutcome.Continue(
            updatedMessages = updatedMessages.toList(), 
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
        toolDescription: String,
        arguments: String,
        toolId: String,
        toolMetadata: Map<String, Any>,
        params: ChatCompletionCreateParams,
        openAIClient: OpenAIClient,
        context: CompletionToolRequestContext,
    ): String? =
        telemetryService.withClientObservation("execute_tool") { observation ->
            observation.lowCardinalityKeyValue(GenAIObsAttributes.OPERATION_NAME, "execute_tool")
            observation.lowCardinalityKeyValue(GenAIObsAttributes.TOOL_NAME, toolName)
            observation.highCardinalityKeyValue(GenAIObsAttributes.TOOL_DESCRIPTION, toolDescription)
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
    ): MasaicToolCallResult {
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

                for (tool in toolCalls) { // Changed from forEach to allow early exit for Terminate
                    val function = tool.function()
                    if (toolService.getFunctionTool(function.name(), context) != null) {
                        logger.info { "Executing tool: ${function.name()} with ID: ${tool.id()}" }

                        if (function.name() == IMAGE_GENERATION_TOOL_NAME) {
                            logger.info { "Executing terminal tool: ${function.name()} with ID: ${tool.id()}" }

                            var toolOutputString: Map<String, String>? = null
                            // Simplified execution for clarity; actual execution uses `toolService.executeTool`
                            try {
                                toolOutputString =
                                    runBlocking {
                                        objectMapper.readValue(
                                            toolService.executeTool(
                                                function.name(),
                                                function.arguments(),
                                                params,
                                                openAIClient,
                                                {},
                                                mapOf("toolId" to tool.id()),
                                                context,
                                            ),
                                            object : TypeReference<Map<String, String>>() {},
                                        )
                                    }
                            } catch (e: Exception) {
                                logger.error(e) { "Error executing terminal tool ${function.name()}: ${e.message}" }
                                toolOutputString = mapOf("error" to "Error executing terminal tool: ${e.message}")
                            }

                            if (toolOutputString != null && toolOutputString.isNotEmpty() && toolOutputString["data"]?.isNotBlank() == true) {
                                // Add the function call to response items
                                val functionCallInputItem =
                                    ResponseInputItem.ofFunctionCall(
                                        ResponseFunctionToolCall
                                            .builder()
                                            .callId(tool.id())
                                            .id(tool.id())
                                            .name(function.name())
                                            .arguments(function.arguments())
                                            .status(ResponseFunctionToolCall.Status.IN_PROGRESS)
                                            .build(),
                                    )
                                responseInputItems.add(functionCallInputItem)
                                val functionCallOutputItem =
                                    ResponseInputItem.ofFunctionCallOutput(
                                        ResponseInputItem.FunctionCallOutput
                                            .builder()
                                            .callId(tool.id())
                                            .id(tool.id())
                                            .output(toolOutputString["data"].toString())
                                            .build(),
                                    )
                                responseInputItems.add(functionCallOutputItem)

                                val imageResponseOutputItem =
                                    ResponseOutputItem.ofImageGenerationCall(
                                        ResponseOutputItem.ImageGenerationCall
                                            .builder()
                                            .id(tool.id())
                                            .status(ResponseOutputItem.ImageGenerationCall.Status.COMPLETED)
                                            .result(toolOutputString["data"].toString())
                                            .type(JsonValue.from("image_generation_call"))
                                            .build(),
                                    )

                                val directResponse =
                                    ChatCompletionConverter.buildFinalResponse(
                                        params,
                                        ResponseStatus.COMPLETED,
                                        chatCompletion.id(),
                                        listOf(imageResponseOutputItem),
                                    )
                                
                                return MasaicToolCallResult.Terminate(responseInputItems.toList() + parked, directResponse)
                            } else {
                                logger.warn { "Terminal tool ${function.name()} returned null output. Proceeding as regular tool call." }
                                // Add the function call to response items
                                val functionCallInputItem =
                                    ResponseInputItem.ofFunctionCall(
                                        ResponseFunctionToolCall
                                            .builder()
                                            .callId(tool.id())
                                            .id(tool.id())
                                            .name(function.name())
                                            .arguments(function.arguments())
                                            .status(ResponseFunctionToolCall.Status.IN_PROGRESS)
                                            .build(),
                                    )
                                responseInputItems.add(functionCallInputItem)
                                // Add null output so it's not lost
                                responseInputItems.add(
                                    ResponseInputItem.ofFunctionCallOutput(
                                        ResponseInputItem.FunctionCallOutput
                                            .builder()
                                            .callId(tool.id())
                                            .id(tool.id())
                                            .output("Tool ${function.name()} returned null output.")
                                            .build(),
                                    ),
                                )
                            }
                        } else {
                            // Regular native tool execution
                            executeToolWithObservation(
                                function.name(),
                                toolService.getAvailableTool(function.name())?.description ?: "not_available",
                                function.arguments(),
                                tool.id(),
                                mapOf("toolId" to tool.id()),
                                params,
                                openAIClient,
                                {}, // No event emitter for non-streaming context here
                                context,
                                parentObservation,
                            ) { toolResult ->
                                // This callback style is for the streaming version, adapt for non-streaming
                                var regularToolResult: String? = null
                                try {
                                    regularToolResult =
                                        runBlocking {
                                            toolService.executeTool(
                                                function.name(),
                                                function.arguments(),
                                                params,
                                                openAIClient,
                                                {},
                                                mapOf("toolId" to tool.id()),
                                                context,
                                            )
                                        }
                                } catch (e: Exception) {
                                    logger.error(e) { "Error executing tool ${function.name()}: ${e.message}" }
                                    regularToolResult = "Error executing tool ${function.name()}: ${e.message}"
                                }

                                if (regularToolResult != null) {
                                    // Add the function call to response items
                                    val functionCallInputItem =
                                        ResponseInputItem.ofFunctionCall(
                                            ResponseFunctionToolCall
                                                .builder()
                                                .callId(tool.id())
                                                .id(tool.id())
                                                .name(function.name())
                                                .arguments(function.arguments())
                                                .status(ResponseFunctionToolCall.Status.IN_PROGRESS)
                                                .build(),
                                        )
                                    responseInputItems.add(functionCallInputItem)
                                    logger.debug { "Tool execution successful for ${function.name()}" }
                                    responseInputItems.add(
                                        ResponseInputItem.ofFunctionCallOutput(
                                            ResponseInputItem.FunctionCallOutput
                                                .builder()
                                                .callId(tool.id())
                                                .id(tool.id())
                                                .output(regularToolResult)
                                                .build(),
                                        ),
                                    )
                                } else {
                                    // Add the function call to response items
                                    val functionCallInputItem =
                                        ResponseInputItem.ofFunctionCall(
                                            ResponseFunctionToolCall
                                                .builder()
                                                .callId(tool.id())
                                                .id(tool.id())
                                                .name(function.name())
                                                .arguments(function.arguments())
                                                .status(ResponseFunctionToolCall.Status.IN_PROGRESS)
                                                .build(),
                                        )
                                    responseInputItems.add(functionCallInputItem)
                                    logger.warn { "Tool execution returned null for ${function.name()}" }
                                    responseInputItems.add( // Add an item indicating null output
                                        ResponseInputItem.ofFunctionCallOutput(
                                            ResponseInputItem.FunctionCallOutput
                                                .builder()
                                                .callId(tool.id())
                                                .id(tool.id())
                                                .output("Tool ${function.name()} returned null output.")
                                                .build(),
                                        ),
                                    )
                                }
                            } // End of simulated direct call block
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
        return MasaicToolCallResult.Continue(responseInputItems.toList())
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
        toolDescription: String,
        arguments: String,
        toolId: String,
        toolMetadata: Map<String, Any>,
        params: ResponseCreateParams,
        openAIClient: OpenAIClient,
        eventEmitter: ((ServerSentEvent<String>) -> Unit),
        context: ToolRequestContext,
        parentObservation: Observation? = null,
        resultHandler: (String?) -> Unit,
    ) {
        telemetryService.withClientObservation("execute_tool", parentObservation) { observation ->
            observation.lowCardinalityKeyValue(GenAIObsAttributes.OPERATION_NAME, "execute_tool")
            observation.lowCardinalityKeyValue(GenAIObsAttributes.TOOL_NAME, toolName)
            observation.highCardinalityKeyValue(GenAIObsAttributes.TOOL_DESCRIPTION, toolDescription)
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
    ): MasaicToolCallStreamingResult {
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
        var shouldTerminate = false // Local flag for termination
        var terminalOutputItem: ResponseOutputItem? = null // To store image tool's output item

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

        for (indexedToolCall in functionCalls.withIndex()) { // Changed to for loop to allow break

            val index = indexedToolCall.index
            val tool = indexedToolCall.value
            val function = tool.asFunctionCall()

            if (toolService.getFunctionTool(function.name(), context) != null) {
                logger.info { "Executing tool: ${function.name()} with ID: ${function.id()}" }
                val toolDefinition = toolService.getAvailableTool(function.name())
                val eventPrefix = if (toolDefinition?.protocol == ToolProtocol.MCP) "response.mcp_call.${function.name().lowercase().replace("^\\W".toRegex(), "_")}" else "response.${function.name().lowercase().replace("^\\W".toRegex(), "_")}"
                eventEmitter.invoke(
                    ServerSentEvent
                        .builder<String>()
                        .event("$eventPrefix.in_progress")
                        .data(
                            " " +
                                objectMapper.writeValueAsString(
                                    mapOf(
                                        "item_id" to (function.id().getOrNull() ?: function.callId()),
                                        "output_index" to index.toString(),
                                        "type" to "$eventPrefix.in_progress",
                                    ),
                                ),
                        ).build(),
                )

                eventEmitter.invoke(
                    ServerSentEvent
                        .builder<String>()
                        .event("$eventPrefix.executing")
                        .data(
                            " " +
                                objectMapper.writeValueAsString(
                                    mapOf<String, String>(
                                        "item_id" to (function.id().getOrNull() ?: function.callId()),
                                        "output_index" to index.toString(),
                                        "type" to "$eventPrefix.executing",
                                    ),
                                ),
                        ).build(),
                )

//                runBlocking { delay(10*1000) }

                if (function.name() == IMAGE_GENERATION_TOOL_NAME) {
                    eventEmitter.invoke(
                        ServerSentEvent
                            .builder<String>()
                            .event("$eventPrefix.generating")
                            .data(
                                " " +
                                    objectMapper.writeValueAsString(
                                        mapOf<String, String>(
                                            "item_id" to (function.id().getOrNull() ?: function.callId()),
                                            "output_index" to index.toString(),
                                            "type" to "$eventPrefix.generating",
                                        ),
                                    ),
                            ).build(),
                    )

                    logger.info { "Executing terminal tool (streaming): ${function.name()} with ID: ${function.id()}" }
                    var imageToolOutputString: Map<String, String>? = null
                    executeToolWithObservation(
                        function.name(),
                        toolDefinition?.description ?: "not_available",
                        function.arguments(),
                        function.id().toString(),
                        mapOf("toolId" to function.id(), "eventIndex" to index),
                        params,
                        openAIClient,
                        eventEmitter, // Pass through the eventEmitter
                        context,
                        parentObservation,
                    ) { toolResult ->
                        // This is the callback
                        val typeReference = object : TypeReference<Map<String, String>>() {}
                        @Suppress("UNCHECKED_CAST")
                        imageToolOutputString =
                            objectMapper.readValue(toolResult, typeReference) // Capture the result
                    }

                    if (imageToolOutputString != null &&
                        (imageToolOutputString as Map<out String?, String?>).contains("data") &&
                        (imageToolOutputString as Map<out String?, String?>)["data"]?.isNotBlank() == true
                    ) {
                        // Add the function call to response items
                        responseInputItems.add(
                            ResponseInputItem.ofFunctionCall(
                                ResponseFunctionToolCall
                                    .builder()
                                    .callId(function.callId())
                                    .id(function.id().toString())
                                    .name(function.name())
                                    .arguments(function.arguments())
                                    .status(ResponseFunctionToolCall.Status.IN_PROGRESS)
                                    .build(),
                            ),
                        )
                        // Add the function call output to responseInputItems for storage/logging
                        responseInputItems.add(
                            ResponseInputItem.ofFunctionCallOutput(
                                ResponseInputItem.FunctionCallOutput
                                    .builder()
                                    .callId(function.callId())
                                    .id(function.id())
                                    .output((imageToolOutputString as Map<out String?, String?>)["data"].toString())
                                    .build(),
                            ),
                        )
                        // Create the ResponseOutputItem for the image tool (this will be the final message)

                        terminalOutputItem =
                            ResponseOutputItem.ofImageGenerationCall(
                                ResponseOutputItem.ImageGenerationCall
                                    .builder()
                                    .id(function.id().get())
                                    .status(ResponseOutputItem.ImageGenerationCall.Status.COMPLETED)
                                    .result(imageToolOutputString["data"].toString())
                                    .type(JsonValue.from("image_generation_call"))
                                    .build(),
                            )

                        shouldTerminate = true // Signal to terminate streaming after this tool
                        // Emit completed event for image_generation tool itself (optional, depends on desired events)
                        eventEmitter.invoke(
                            ServerSentEvent
                                .builder<String>()
                                .event("$eventPrefix.completed") // This was for the generic tool
                                .data(
                                    " " +
                                        objectMapper.writeValueAsString(
                                            mapOf(
                                                "item_id" to function.id(),
                                                "output_index" to index.toString(),
                                                "type" to "$eventPrefix.completed",
                                                "final_output_generated" to "true",
                                            ),
                                        ),
                                ).build(),
                        )
                        break // Exit loop, image_generation is terminal for this batch of tools
                    } else {
                        logger.warn { "Terminal tool ${function.name()} returned null output in streaming. Adding error/null output." }
                        // Add the function call to response items
                        responseInputItems.add(
                            ResponseInputItem.ofFunctionCall(
                                ResponseFunctionToolCall
                                    .builder()
                                    .callId(function.callId())
                                    .id(function.id().toString())
                                    .name(function.name())
                                    .arguments(function.arguments())
                                    .status(ResponseFunctionToolCall.Status.IN_PROGRESS)
                                    .build(),
                            ),
                        )
                        responseInputItems.add(
                            ResponseInputItem.ofFunctionCallOutput(
                                ResponseInputItem.FunctionCallOutput
                                    .builder()
                                    .callId(function.callId())
                                    .id(function.id())
                                    .output("Tool ${function.name()} returned null or failed.")
                                    .build(),
                            ),
                        )
                        // Emit completed event for image_generation tool, but indicating failure/null
                        eventEmitter.invoke(
                            ServerSentEvent
                                .builder<String>()
                                .event("$eventPrefix.completed")
                                .data(
                                    " " +
                                        objectMapper.writeValueAsString(
                                            mapOf(
                                                "item_id" to function.id(),
                                                "output_index" to index.toString(),
                                                "type" to "$eventPrefix.completed",
                                                "error" to "Tool returned null",
                                            ),
                                        ),
                                ).build(),
                        )
                    }
                } else { // Regular native tool
                    executeToolWithObservation(
                        function.name(),
                        toolService.getAvailableTool(function.name())?.description ?: "not_available",
                        function.arguments(),
                        function.id().toString(),
                        mapOf("toolId" to function.id(), "eventIndex" to index),
                        params,
                        openAIClient,
                        eventEmitter,
                        context,
                        parentObservation,
                    ) { toolResult ->
                        if (toolResult != null) {
                            logger.debug { "Tool execution successful for ${function.name()}" }
                            // Add the function call to response items
                            responseInputItems.add(
                                ResponseInputItem.ofFunctionCall(
                                    ResponseFunctionToolCall
                                        .builder()
                                        .callId(function.callId())
                                        .id(function.id().toString())
                                        .name(function.name())
                                        .arguments(function.arguments())
                                        .status(ResponseFunctionToolCall.Status.IN_PROGRESS)
                                        .build(),
                                ),
                            )
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

                            eventEmitter.invoke(
                                ServerSentEvent
                                    .builder<String>()
                                    .event("$eventPrefix.completed")
                                    .data(
                                        " " +
                                            objectMapper.writeValueAsString(
                                                mapOf<String, String>(
                                                    "item_id" to function.id().toString(),
                                                    "output_index" to index.toString(),
                                                    "type" to "$eventPrefix.completed",
                                                ),
                                            ),
                                    ).build(),
                            )
                        } else {
                            logger.warn { "Tool execution returned null for ${function.name()}" }
                            // Add the function call to response items
                            responseInputItems.add(
                                ResponseInputItem.ofFunctionCall(
                                    ResponseFunctionToolCall
                                        .builder()
                                        .callId(function.callId())
                                        .id(function.id().toString())
                                        .name(function.name())
                                        .arguments(function.arguments())
                                        .status(ResponseFunctionToolCall.Status.IN_PROGRESS)
                                        .build(),
                                ),
                            )
                            responseInputItems.add(
                                ResponseInputItem.ofFunctionCallOutput(
                                    ResponseInputItem.FunctionCallOutput
                                        .builder()
                                        .callId(function.callId())
                                        .id(function.id())
                                        .output("Tool ${function.name()} returned null output.")
                                        .build(),
                                ),
                            )

                            eventEmitter.invoke(
                                ServerSentEvent
                                    .builder<String>()
                                    .event("$eventPrefix.completed")
                                    .data(
                                        " " +
                                            objectMapper.writeValueAsString(
                                                mapOf<String, String>(
                                                    "item_id" to function.id().toString(),
                                                    "output_index" to index.toString(),
                                                    "type" to "$eventPrefix.completed",
                                                    "error" to "Tool returned error or null",
                                                ),
                                            ),
                                    ).build(),
                            )
                        }
                    }
                }
            } else {
                logger.info { "Unsupported tool requested: ${function.name()}, parking for client handling" }
                parked.add(
                    ResponseInputItem.ofFunctionCall(
                        ResponseFunctionToolCall
                            .builder()
                            .callId(function.callId())
                            .id(function.id().toString())
                            .name(function.name())
                            .arguments(function.arguments())
                            .build(),
                    ),
                )
            }
        }

        logger.debug { "Adding ${parked.size} parked items to response" }
        responseInputItems.addAll(parked)
        return MasaicToolCallStreamingResult(responseInputItems.toList(), shouldTerminate, terminalOutputItem)
    }
}
