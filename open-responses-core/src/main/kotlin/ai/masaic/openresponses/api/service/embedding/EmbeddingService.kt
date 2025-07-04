package ai.masaic.openresponses.api.service.embedding

/**
 * Interface for embedding text content into vector representations.
 *
 * This interface defines methods for converting text into vector embeddings
 * that can be stored and searched in vector databases.
 */
interface EmbeddingService {
    /**
     * Embeds a single text string into a vector representation.
     *
     * @param text The text to embed
     * @return A list of floats representing the embedding vector
     */
    fun embedText(text: String): List<Float>

    /**
     * Embeds multiple text strings in a batch operation.
     *
     * @param texts The list of texts to embed
     * @return A list of embedding vectors, one for each input text
     */
    fun embedTexts(texts: List<String>): List<List<Float>>

    /**
     * Calculates the cosine similarity between two embedding vectors.
     *
     * @param embedding1 The first embedding vector
     * @param embedding2 The second embedding vector
     * @return The cosine similarity score (between -1 and 1)
     */
    fun calculateSimilarity(
        embedding1: List<Float>,
        embedding2: List<Float>,
    ): Float
}
