package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.ChatMessage
import ai.masaic.openevals.api.model.CompletionsRunDataSource
import ai.masaic.openevals.api.model.CustomDataSourceConfig
import ai.masaic.openevals.api.model.RunDataSource
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.credential.BearerTokenCredential
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
class ChatCompletionsGenerationService : GenerationService {
    private val logger = LoggerFactory.getLogger(ChatCompletionsGenerationService::class.java)
    private val BASE_URL = "https://api.openai.com/v1"

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
        dataSourceConfig: CustomDataSourceConfig
    ): Map<Int, GenerationService.CompletionResult> = runBlocking {
        logger.info("Processing ${completionMessagesSet.size} completions with model: ${dataSource.model}")
        
        // Create OpenAI client
        val openAIClient = createOpenAIClient(apiKey)

        // Process each message set in parallel
        val resultMap = mutableMapOf<Int, GenerationService.CompletionResult>()
        completionMessagesSet.map { (index, messages) ->
            async {
                try {
                    // Create the completion params
                    val completionParams = createCompletionParams(messages, dataSource, dataSourceConfig)

                    // Call the OpenAI API
                    val completion = openAIClient.chat().completions().create(completionParams)

                    // Extract the result
                    val content = completion.choices().firstOrNull()?.message()?.content()?.orElse("")

                    resultMap[index] = GenerationService.CompletionResult(
                        contentJson = content ?: ""
                    )
                } catch (e: Exception) {
                    logger.error("Error processing completion for index $index: ${e.message}", e)
                    resultMap[index] = GenerationService.CompletionResult(
                        contentJson = "",
                        error = e.message ?: "Unknown error"
                    )
                }
            }
        }.awaitAll()

        logger.info("Completed processing ${resultMap.size} completions")
        return@runBlocking resultMap
    }

    /**
     * Create OpenAI client with authentication.
     *
     * @param apiKey The API key for authentication
     * @return OpenAI client instance
     */
    private fun createOpenAIClient(apiKey: String): OpenAIClient {
        return OpenAIOkHttpClient.builder()
            .credential(BearerTokenCredential.create { apiKey })
            .baseUrl(BASE_URL)
            .build()
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
        dataSourceConfig: CustomDataSourceConfig
    ): ChatCompletionCreateParams {
        val schema = ResponseFormatJsonSchema.JsonSchema.Schema.builder().additionalProperties(dataSourceConfig.schema).build()
        val format = ResponseFormatJsonSchema.JsonSchema.builder().schema(schema).name("evalSchema").build()
        val builder = ChatCompletionCreateParams.builder()
            .model(dataSource.model)
            .responseFormat(ChatCompletionCreateParams.ResponseFormat.ofJsonSchema(
                ResponseFormatJsonSchema.builder().type(
                    JsonValue.from("json_schema"))
                    .jsonSchema(format)
                    .build()))

        // Add messages
        messages.forEach { message ->
            when (message.role) {
                "system" -> {
                    builder.addMessage(
                        ChatCompletionSystemMessageParam.builder()
                            .content(message.content)
                            .build()
                    )
                }
                "user" -> {
                    builder.addMessage(
                        ChatCompletionUserMessageParam.builder()
                            .content(message.content)
                            .build()
                    )
                }
                "assistant" -> {
                    builder.addMessage(
                        ChatCompletionAssistantMessageParam.builder()
                            .content(message.content)
                            .build()
                    )
                }
                else -> {
                    // Default to user role for unknown roles
                    builder.addMessage(
                        ChatCompletionUserMessageParam.builder()
                            .content(message.content)
                            .build()
                    )
                }
            }
        }

        // Add sampling parameters if available
        dataSource.samplingParams?.let { params ->
            builder.temperature(params.temperature)
            builder.topP(params.topP)
        }

        return builder.build()
    }
} 
