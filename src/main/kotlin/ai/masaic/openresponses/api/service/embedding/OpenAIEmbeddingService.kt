package ai.masaic.openresponses.api.service.embedding

import dev.langchain4j.data.embedding.Embedding
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.openai.OpenAiEmbeddingModel
import dev.langchain4j.store.embedding.CosineSimilarity
import org.springframework.beans.factory.annotation.Value

/**
 * Implementation of EmbeddingService using OpenAI's embedding API.
 *
 * This service uses the OpenAI API to generate embeddings through Langchain4j's OpenAiEmbeddingModel.
 */
class OpenAIEmbeddingService(
    @Value("\${open-responses.embedding.url}")
    private val baseUrl: String,
    @Value("\${open-responses.embedding.api.key}")
    private val apiKey: String,
    @Value("\${open-responses.embedding.model}")
    private val modelName: String,
) : EmbeddingService {
    private val embeddingModel =
        lazy {
            OpenAiEmbeddingModel
                .builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .build()
        }

    /**
     * Embeds a single text string into a vector representation using OpenAI.
     *
     * @param text The text to embed
     * @return A list of floats representing the embedding vector
     */
    override fun embedText(text: String): List<Float> {
        val embedding = embeddingModel.value.embed(text).content()
        return embedding.vectorAsList()
    }

    /**
     * Embeds multiple text strings in a batch operation using OpenAI.
     *
     * @param texts The list of texts to embed
     * @return A list of embedding vectors, one for each input text
     */
    override fun embedTexts(texts: List<String>): List<List<Float>> {
        val embeddings =
            embeddingModel.value
                .embedAll(
                    texts.map {
                        TextSegment.from(it)
                    },
                ).content()
        return embeddings.map {
            it.vectorAsList()
        }
    }

    /**
     * Calculates the cosine similarity between two embedding vectors.
     *
     * @param embedding1 The first embedding vector
     * @param embedding2 The second embedding vector
     * @return The cosine similarity score (between -1 and 1)
     */
    override fun calculateSimilarity(
        embedding1: List<Float>,
        embedding2: List<Float>,
    ): Float {
        val e1 = Embedding.from(embedding1)
        val e2 = Embedding.from(embedding2)

        return CosineSimilarity
            .between(e1, e2)
            .toFloat()
    }
}
