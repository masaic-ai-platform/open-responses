package ai.masaic.openresponses.api.client

import ai.masaic.openresponses.tool.ToolService
import com.openai.core.JsonValue
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.responses.*
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Handles tool-related operations for the Masaic OpenAI API integration.
 * Encapsulates the logic for processing tool calls and tool outputs.
 */
@Component
class MasaicToolHandler(
    private val toolService: ToolService,
    private val openTelemetry: OpenTelemetry,
) {
    private val logger = KotlinLogging.logger {}
    private val tracer: Tracer = openTelemetry.getTracer("ai.masaic.openresponses.api.client")

    // Constants for GenAI tool span attributes
    private object GenAiAttributes {
        val OPERATION_NAME = AttributeKey.stringKey("gen_ai.operation.name")
        val TOOL_NAME = AttributeKey.stringKey("gen_ai.tool.name")
        val TOOL_CALL_ID = AttributeKey.stringKey("gen_ai.tool.call.id")
        val ERROR_TYPE = AttributeKey.stringKey("error.type")
    }

    /**
     * Processes tool calls from a chat completion and executes relevant tools.
     * Each tool execution is recorded as a span following GenAI semantics.
     *
     * @param chatCompletion The ChatCompletion containing potential tool calls
     * @param params The original request parameters
     * @return List of ResponseInputItems with both tool calls and their outputs
     */
    fun handleMasaicToolCall(
        chatCompletion: ChatCompletion,
        params: ResponseCreateParams,
    ): List<ResponseInputItem> {
        logger.debug { "Processing tool calls from ChatCompletion: ${chatCompletion.id()}" }
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
                                            .annotations(listOf())
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

        // Process tool calls
        chatCompletion.choices().forEach { chatChoice ->
            if (ChatCompletion.Choice.FinishReason.TOOL_CALLS == chatChoice.finishReason()) {
                val message = chatChoice.message()
                logger.debug { "Processing ${message.toolCalls().get().size} tool calls" }

                message.toolCalls().get().forEach { tool ->
                    val function = tool.function()
                    if (toolService.getFunctionTool(function.name()) != null) {
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

                        // Execute the tool with span tracking
                        executeToolWithSpan(function.name(), function.arguments(), tool.id()) { toolResult ->
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
                        // For unsupported tools, park them for client handling
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

        // Add all parked items to the end
        logger.debug { "Adding ${parked.size} parked items to response" }
        responseInputItems.addAll(parked)

        return responseInputItems
    }

    /**
     * Executes a tool within a span context and handles the result with the provided callback.
     * 
     * @param toolName The name of the tool to execute
     * @param arguments The arguments to pass to the tool
     * @param toolId The ID of the tool call
     * @param resultHandler A function that processes the tool execution result
     */
    private fun executeToolWithSpan(
        toolName: String,
        arguments: String,
        toolId: String,
        resultHandler: (String?) -> Unit,
    ) {
        // Create a span for tool execution following GenAI semantics
        val span =
            tracer
                .spanBuilder("builtin_execute_tool")
                .setParent(Context.current())
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan()
        
        try {
            // Set required span attributes according to GenAI span semantics
            span.setAttribute(GenAiAttributes.OPERATION_NAME, "execute_tool")
            span.setAttribute(GenAiAttributes.TOOL_NAME, toolName)
            span.setAttribute(GenAiAttributes.TOOL_CALL_ID, toolId)
            
            // Execute the tool
            val toolResult = toolService.executeTool(toolName, arguments)
            
            // Set span status based on result
            if (toolResult != null) {
                span.setStatus(StatusCode.OK)
            } else {
                span.setStatus(StatusCode.ERROR)
                span.setAttribute(GenAiAttributes.ERROR_TYPE, "tool_execution_null_result")
            }
            
            // Process the result with the provided handler
            resultHandler(toolResult)
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR)
            span.setAttribute(GenAiAttributes.ERROR_TYPE, "${e.javaClass.simpleName}; ${e.message}")
            logger.error(e) { "Error executing tool $toolName: ${e.message}" }
            throw e
        } finally {
            span.end()
        }
    }

    /**
     * Processes tool calls from a response and executes relevant tools.
     *
     * @param params The original request parameters
     * @param response The Response object containing potential tool calls
     * @return List of ResponseInputItems with both tool calls and their outputs
     */
    fun handleMasaicToolCall(
        params: ResponseCreateParams,
        response: Response,
    ): List<ResponseInputItem> {
        logger.debug { "Processing tool calls from Response ID: ${response.id()}" }
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

        // Process function calls
        val functionCalls = response.output().filter { it.isFunctionCall() }
        logger.debug { "Processing ${functionCalls.size} function calls" }

        functionCalls.forEach { tool ->
            val function = tool.asFunctionCall()

            if (toolService.getFunctionTool(function.name()) != null) {
                logger.info { "Executing tool: ${function.name()} with ID: ${function.id()}" }
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

                // Execute the tool with span tracking
                executeToolWithSpan(function.name(), function.arguments(), function.id()) { toolResult ->
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
            } else {
                logger.info { "Unsupported tool requested: ${function.name()}, parking for client handling" }
                // For unsupported tools, park them for client handling
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

        // Add all parked items to the end
        logger.debug { "Adding ${parked.size} parked items to response" }
        responseInputItems.addAll(parked)

        return responseInputItems
    }
} 
