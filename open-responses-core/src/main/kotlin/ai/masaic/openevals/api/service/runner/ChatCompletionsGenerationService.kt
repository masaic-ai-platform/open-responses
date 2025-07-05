package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.ChatMessage
import ai.masaic.openevals.api.model.CompletionsRunDataSource
import ai.masaic.openevals.api.model.CustomDataSourceConfig
import ai.masaic.openevals.api.model.RunDataSource
import ai.masaic.openevals.api.service.ModelClientService
import com.openai.core.JsonValue
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.chat.completions.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Implementation of GenerationService for OpenAI Chat Completions API.
 */
@Component
class ChatCompletionsGenerationService(
    private val modelClientService: ModelClientService,
) : GenerationService {
    private val logger = LoggerFactory.getLogger(ChatCompletionsGenerationService::class.java)

    /**
     * Checks if this service can handle the generation based on the provided data source.
     * Handles CompletionsRunDataSource with OpenAI models.
     *
     * @param dataSource The data source with model and parameter information
     * @return True if this service can handle the generation
     */
    override fun canGenerate(dataSource: RunDataSource): Boolean {
        // Check if it's a CompletionsRunDataSource
        return dataSource is CompletionsRunDataSource
    }

    /**
     * Generate completions for the provided message sets using OpenAI API.
     *
     * @param completionMessagesSet Map of completion message sets indexed by identifier
     * @param dataSource The data source with model and parameter information
     * @param apiKey The API key for the OpenAI API
     * @param dataSourceConfig Configuration for the data source
     * @return Map of completion results indexed by identifier
     */
    override suspend fun generateCompletions(
        completionMessagesSet: Map<Int, List<ChatMessage>>,
        dataSource: CompletionsRunDataSource,
        apiKey: String,
        dataSourceConfig: CustomDataSourceConfig,
    ): Map<Int, CompletionResult> =
        runBlocking {
            logger.info("Processing ${completionMessagesSet.size} completions with model: ${dataSource.model}")

            // Process each message set in parallel
            val resultMap = mutableMapOf<Int, CompletionResult>()
            completionMessagesSet
                .map { (index, messages) ->
                    async {
                        // Create the completion params
                        val completionParams = createCompletionParams(messages, dataSource, dataSourceConfig)
                        
                        // Execute with error handling using the cached client
                        val result =
                            modelClientService.executeWithClientAndErrorHandling(
                                apiKey = apiKey,
                                params = completionParams,
                                identifier = index.toString(),
                            ) { content, er ->
                                ai.masaic.openevals.api.model.CompletionResult(
                                    contentJson = content,
                                    error = er,
                                )
                            }
                        
                        resultMap[index] =
                            CompletionResult(
                                contentJson = result.contentJson,
                                error = result.error,
                            )

                        logger.debug("Input for completions: $messages")
                        logger.debug("Output for completions: content=${result.contentJson} ?: error=${result.error}")
                    }
                }.awaitAll()

            logger.info("Completed processing ${resultMap.size} completions")
            return@runBlocking resultMap
        }

    /**
     * Add messages to a completion params builder based on their roles.
     *
     * @param builder The builder to add messages to
     * @param messages The list of chat messages
     * @return The updated builder
     */
    private fun addMessagesToBuilder(
        builder: ChatCompletionCreateParams.Builder,
        messages: List<ChatMessage>,
    ): ChatCompletionCreateParams.Builder {
        messages.forEach { message ->
            when (message.role.lowercase()) {
                "system" -> {
                    builder.addMessage(
                        ChatCompletionSystemMessageParam
                            .builder()
                            .content(message.content)
                            .build(),
                    )
                }
                "developer" -> {
                    builder.addMessage(
                        ChatCompletionDeveloperMessageParam
                            .builder()
                            .content(message.content)
                            .build(),
                    )
                }
                "user" -> {
                    builder.addMessage(
                        ChatCompletionUserMessageParam
                            .builder()
                            .content(message.content)
                            .build(),
                    )
                }
                "assistant" -> {
                    builder.addMessage(
                        ChatCompletionAssistantMessageParam
                            .builder()
                            .content(message.content)
                            .build(),
                    )
                }
                else -> {
                    // Default to user role for unknown roles
                    builder.addMessage(
                        ChatCompletionUserMessageParam
                            .builder()
                            .content(message.content)
                            .build(),
                    )
                }
            }
        }
        return builder
    }

    /**
     * Create completion parameters for the OpenAI API.
     *
     * @param messages The chat messages
     * @param dataSource The data source configuration
     * @param dataSourceConfig Configuration for the data source
     * @return Completion parameters
     */
    private fun createCompletionParams(
        messages: List<ChatMessage>,
        dataSource: CompletionsRunDataSource,
        dataSourceConfig: CustomDataSourceConfig,
    ): ChatCompletionCreateParams {
        // Create JSON schema for response format
        val jsonSchema =
            ResponseFormatJsonSchema.JsonSchema.Schema
                .builder()
                .additionalProperties(dataSourceConfig.schema)
                .build()
                
        val format =
            ResponseFormatJsonSchema.JsonSchema
                .builder()
                .schema(jsonSchema)
                .name("evalSchema")
                .build()
                
        // Create the builder with basic properties
        val builder =
            ChatCompletionCreateParams
                .builder()
                .model(dataSource.model)
                .responseFormat(
                    ResponseFormatJsonSchema
                        .builder()
                        .type(JsonValue.from("json_schema"))
                        .jsonSchema(format)
                        .build(),
                )
        
        // Add temperature and topP if available
        dataSource.samplingParams?.let { params ->
            params.temperature.let { builder.temperature(it) }
            params.topP.let { builder.topP(it) }
        }
        
        // Add messages to the builder
        addMessagesToBuilder(builder, messages)
        
        return builder.build()
    }
} 
