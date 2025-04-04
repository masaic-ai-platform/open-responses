package ai.masaic.openresponses.api.utils

/**
 * Utility class for text chunking operations.
 *
 * Provides common text chunking functionality that can be shared
 * between different vector search provider implementations.
 */
object TextChunkingUtil {
    /**
     * Splits text into overlapping chunks.
     *
     * @param text The text to split
     * @param chunkSize The size of each chunk
     * @param overlap The overlap between chunks
     * @return List of text chunks
     */
    fun chunkText(
        text: String,
        chunkSize: Int,
        overlap: Int,
    ): List<String> {
        // Return the text as a single chunk if it's shorter than chunk size
        if (text.length <= chunkSize) {
            return listOf(text)
        }

        val chunks = mutableListOf<String>()
        var start = 0

        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)
            chunks.add(text.substring(start, end))
            start += (chunkSize - overlap)

            // Prevent infinite loop if overlap >= chunkSize
            if (start <= 0) {
                start = end
            }
        }

        return chunks
    }
}
