package ai.masaic.openresponses.api.service.embedding

import org.springframework.stereotype.Service

/**
 * Implementation of EmbeddingService using OpenAI's embedding API.
 *
 * This service uses the OpenAI API to generate embeddings through Langchain4j's OpenAiEmbeddingModel.
 */
@Service
class OpenAIProxyEmbeddingService(
    private val embeddingService: EmbeddingService,
) {
    val providers =
        mapOf<String, String>(
            "openai" to "https://api.openai.com/v1",
            "cohere" to "https://api.cohere.ai/compatibility/v1",
            "togetherai" to "https://api.together.xyz/v1",
        )

    /**
     * Embeds a single text string into a vector representation using OpenAI.
     *
     * @param text The text to embed
     * @return A list of floats representing the embedding vector
     */
    fun embedText(
        text: String,
        apiKey: String,
        modelName: String,
    ): List<Float> =
        if (modelName == "default") {
            embeddingService.embedText(text)
        } else {
            val (model, baseUrl) = modelAndProviderUrl(modelName)
            OpenAIEmbeddingService(
                baseUrl,
                apiKey,
                model,
            ).embedText(text)
        }

    /**
     * Embeds multiple text strings in a batch operation using OpenAI.
     *
     * @param texts The list of texts to embed
     * @return A list of embedding vectors, one for each input text
     */
    fun embedTexts(
        texts: List<String>,
        apiKey: String,
        modelName: String,
    ): List<List<Float>> =
        if (modelName == "default") {
            embeddingService.embedTexts(texts)
        } else {
            val (model, baseUrl) = modelAndProviderUrl(modelName)
            OpenAIEmbeddingService(
                baseUrl,
                apiKey,
                model,
            ).embedTexts(texts)
        }

    private fun modelAndProviderUrl(modelName: String): Pair<String, String> {
        // the model must be in format "provider@model"
        if (!modelName.contains("@")) {
            throw IllegalArgumentException("Model name must be in the format 'provider@model'")
        }
        // split by first "@"
        val (provider, model) = modelName.split("@", limit = 2)
        val baseUrl =
            providers[provider]
                ?: if (provider.startsWith("http")) {
                    provider
                } else {
                    throw IllegalArgumentException("Unknown provider: $provider")
                }
        return Pair(model, baseUrl)
    }
}
