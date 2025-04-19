package ai.masaic.openevals.api.service

import ai.masaic.openevals.api.model.CompletionResult
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.credential.BearerTokenCredential
import com.openai.models.chat.completions.ChatCompletionCreateParams
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared service for OpenAI API interactions.
 * Provides common functionality for client creation and chat completions.
 */
@Service
class ModelClientService {
    private val logger = LoggerFactory.getLogger(ModelClientService::class.java)
    private val baseURL = "https://api.openai.com/v1"
    
    // Client cache to avoid recreating clients
    private val clientCache = ConcurrentHashMap<String, OpenAIClient>()

    /**
     * Get or create an authenticated OpenAI client.
     * Clients are cached by API key to avoid recreating them.
     *
     * @param apiKey The API key for authentication
     * @return OpenAI client instance
     */
    fun getOpenAIClient(apiKey: String): OpenAIClient =
        clientCache.computeIfAbsent(apiKey) { key ->
            createOpenAIClient(key)
        }

    /**
     * Create an authenticated OpenAI client.
     * This is now a private method as external code should use getOpenAIClient.
     *
     * @param apiKey The API key for authentication
     * @return OpenAI client instance
     */
    private fun createOpenAIClient(apiKey: String): OpenAIClient =
        OpenAIOkHttpClient
            .builder()
            .credential(BearerTokenCredential.create { apiKey })
            .baseUrl(baseURL)
            .build()

    /**
     * Create basic chat completion parameters with the specified model.
     *
     * @param model The model to use for completion
     * @return Builder for completion parameters
     */
    fun createBasicCompletionParams(model: String): ChatCompletionCreateParams.Builder = ChatCompletionCreateParams.builder().model(model)

    /**
     * Extract content from a chat completion response.
     *
     * @param completion The completion response
     * @return The extracted content or empty string
     */
    fun extractCompletionContent(completion: com.openai.models.chat.completions.ChatCompletion): String =
        completion
            .choices()
            .firstOrNull()
            ?.message()
            ?.content()
            ?.orElse("")
            ?.trim() ?: ""

    /**
     * Execute a chat completion call and return the content.
     * Handles the common pattern of creating parameters, calling the API, and extracting content.
     *
     * @param client The OpenAI client
     * @param params The completion parameters
     * @return The extracted completion content
     */
    fun executeCompletionCall(
        client: OpenAIClient,
        params: ChatCompletionCreateParams,
    ): String {
        val completion = client.chat().completions().create(params)
        return extractCompletionContent(completion)
    }

    /**
     * Execute a chat completion call with error handling.
     * Returns a result object with either content or error information.
     *
     * @param client The OpenAI client
     * @param params The completion parameters
     * @param identifier Optional identifier for logging
     * @return Result object with content or error
     */
    fun <T, R> executeCompletionWithErrorHandling(
        client: OpenAIClient,
        params: ChatCompletionCreateParams,
        identifier: T? = null,
        resultBuilder: (content: String, error: String?) -> R,
    ): R =
        try {
            val content = executeCompletionCall(client, params)
            resultBuilder(content, null)
        } catch (e: Exception) {
            logger.error("Error processing completion${identifier?.let { " for $it" } ?: ""}: ${e.message}", e)
            resultBuilder("", e.message ?: "Unknown error")
        }

    /**
     * Simplified method that handles client creation, API call, and error handling in one step.
     * This is useful for one-off API calls.
     *
     * @param apiKey The API key to use
     * @param params The completion parameters
     * @param identifier Optional identifier for logging
     * @param resultBuilder Function to build the result
     * @return The result object
     */
    fun executeWithClientAndErrorHandling(
        apiKey: String,
        params: ChatCompletionCreateParams,
        identifier: String? = null,
        resultBuilder: (content: String, error: String?) -> CompletionResult,
    ): CompletionResult {
        val client = getOpenAIClient(apiKey)
        return try {
            val content = executeCompletionCall(client, params)
            resultBuilder(content, null)
        } catch (e: Exception) {
            logger.error("Error processing completion${identifier?.let { " for $it" } ?: ""}: ${e.message}", e)
            resultBuilder("", e.message ?: "Unknown error")
        }
    }
} 
