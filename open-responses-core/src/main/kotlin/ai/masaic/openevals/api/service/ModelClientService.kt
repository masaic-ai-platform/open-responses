package ai.masaic.openevals.api.service

import ai.masaic.openevals.api.model.CompletionResult
import ai.masaic.openresponses.api.service.MasaicResponseService
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

    // Client cache to avoid recreating clients
    private val clientCache = ConcurrentHashMap<String, OpenAIClient>()

    /**
     * Get or create an authenticated OpenAI client.
     * Clients are cached by API key to avoid recreating them.
     *
     * @param apiKey The API key for authentication
     * @param model The model name, which may include provider@model or url@model format
     * @return OpenAI client instance
     */
    fun getOpenAIClient(
        apiKey: String,
        model: String,
    ): OpenAIClient =
        clientCache.computeIfAbsent(apiKey) { key ->
            createOpenAIClient(key, model)
        }

    /**
     * Create an authenticated OpenAI client.
     * This is now a private method as external code should use getOpenAIClient.
     *
     * @param apiKey The API key for authentication
     * @param model The model name, which may include provider@model or url@model format
     * @return OpenAI client instance
     */
    private fun createOpenAIClient(
        apiKey: String,
        model: String,
    ): OpenAIClient {
        val baseUrl = MasaicResponseService.getApiBaseUri(model).toURL().toString()
        logger.debug("Creating OpenAI client with base URL: {}", baseUrl)
        
        return OpenAIOkHttpClient
            .builder()
            .credential(BearerTokenCredential.create { apiKey })
            .baseUrl(baseUrl)
            .build()
    }

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
        // Extract model name from @ convention if needed
        val updatedParams = extractActualModelName(params)
        val completion = client.chat().completions().create(updatedParams)
        return extractCompletionContent(completion)
    }

    /**
     * Extracts the actual model name from URL@model or provider@model format.
     * If the model contains @ symbol, it will extract just the model part.
     *
     * @param params The original completion parameters
     * @return Updated completion parameters with proper model name
     */
    private fun extractActualModelName(params: ChatCompletionCreateParams): ChatCompletionCreateParams {
        val modelName = params.model().toString()
        
        // If model doesn't contain @, return the original params
        if (!modelName.contains("@")) {
            return params
        }
        
        // Split by @ and get the actual model name (second part)
        val parts = modelName.split("@", limit = 2)
        if (parts.size == 2) {
            val actualModelName = parts[1]
            logger.debug("Extracted model name '{}' from '{}'", actualModelName, modelName)
            
            // Create new params with the extracted model name
            return ChatCompletionCreateParams
                .builder()
                .model(actualModelName)
                .messages(params.messages())
                .apply {
                    // Copy other fields if present
                    params.temperature().ifPresent { temperature(it) }
                    params.topP().ifPresent { topP(it) }
                    params.n().ifPresent { n(it) }
                    params.stop().ifPresent { stop(it) }
                    params.presencePenalty().ifPresent { presencePenalty(it) }
                    params.frequencyPenalty().ifPresent { frequencyPenalty(it) }
                    params.logitBias().ifPresent { logitBias(it) }
                    params.user().ifPresent { user(it) }
                    params.responseFormat().ifPresent { responseFormat(it) }
                    params.seed().ifPresent { seed(it) }
                }.build()
        }
        
        // If for some reason we can't extract the model name, return original params
        return params
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
        // Extract model information for proper client and API call
        val originalModelName = params.model().asString()
        val modelForUrl = originalModelName // Use full model with provider for URL
        val updatedParams = extractActualModelName(params) // Use clean model name for API call
        
        val client = getOpenAIClient(apiKey, modelForUrl)
        return try {
            val content =
                client.chat().completions().create(updatedParams).let { completion ->
                    extractCompletionContent(completion)
                }
            resultBuilder(content, null)
        } catch (e: Exception) {
            logger.error("Error processing completion${identifier?.let { " for $it" } ?: ""}: ${e.message}", e)
            resultBuilder("", e.message ?: "Unknown error")
        }
    }
} 
