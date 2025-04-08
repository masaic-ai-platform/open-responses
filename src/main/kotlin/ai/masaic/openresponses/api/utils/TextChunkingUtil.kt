package ai.masaic.openresponses.api.utils

import ai.masaic.openresponses.api.model.ChunkingStrategy

/**
 * Utility class for text chunking operations.
 *
 * Provides text chunking functionality that can be shared
 * between different vector search provider implementations.
 * Supports multiple chunking strategies and maintains backward compatibility.
 */
object TextChunkingUtil {
    // Default chunking parameters
    private const val DEFAULT_CHUNK_SIZE = 1000
    private const val DEFAULT_CHUNK_OVERLAP = 200

    /**
     * Splits text into overlapping chunks (legacy method).
     *
     * @param text The text to split
     * @param chunkSize The size of each chunk
     * @param overlap The overlap between chunks
     * @return List of text chunks as strings
     */
    @JvmStatic
    fun chunkText(
        text: String,
        chunkSize: Int,
        overlap: Int,
    ): List<String> {
        // Convert new TextChunk format to legacy String format for backward compatibility
        return chunkByFixedSize(text, chunkSize, overlap).map { it.text }
    }

    /**
     * Splits text into overlapping chunks using the specified chunking strategy.
     *
     * @param text The text to split
     * @param strategy The chunking strategy to use, or null for default strategy
     * @return List of text chunks with metadata
     */
    @JvmStatic
    fun chunkText(
        text: String,
        strategy: ChunkingStrategy?,
    ): List<TextChunk> {
        // Extract chunking parameters from strategy or use defaults
        val chunkSize: Int
        val chunkOverlap: Int
        val chunkingMethod: String
        
        if (strategy != null && strategy.type == "static" && strategy.static != null) {
            chunkSize = strategy.static.maxChunkSizeTokens
            chunkOverlap = strategy.static.chunkOverlapTokens
            chunkingMethod = strategy.type
        } else {
            chunkSize = DEFAULT_CHUNK_SIZE
            chunkOverlap = DEFAULT_CHUNK_OVERLAP
            chunkingMethod = "fixed" // Default method
        }
        
        return when (chunkingMethod.lowercase()) {
            "sentence" -> chunkBySentences(text, chunkSize, chunkOverlap)
            "paragraph" -> chunkByParagraphs(text, chunkSize, chunkOverlap)
            else -> chunkByFixedSize(text, chunkSize, chunkOverlap) // Default to fixed size
        }
    }

    /**
     * Chunks text by fixed size windows.
     */
    @JvmStatic
    fun chunkByFixedSize(
        text: String,
        chunkSize: Int,
        chunkOverlap: Int,
    ): List<TextChunk> {
        val result = mutableListOf<TextChunk>()
        
        // Handle empty or very small text
        if (text.length <= chunkSize) {
            result.add(TextChunk(text, 0))
            return result
        }
        
        var startIndex = 0
        var chunkIndex = 0
        
        while (startIndex < text.length) {
            val endIndex = minOf(startIndex + chunkSize, text.length)
            
            // Try to find a good breaking point (whitespace) if not at the end of text
            var adjustedEndIndex = endIndex
            if (endIndex < text.length) {
                // Look for the last space within the last 20% of the chunk
                val lookBackAmount = minOf((chunkSize * 0.2).toInt(), 100)
                val lastSpaceIndex = text.substring(endIndex - lookBackAmount, endIndex).lastIndexOf(' ')
                
                if (lastSpaceIndex != -1) {
                    adjustedEndIndex = endIndex - lookBackAmount + lastSpaceIndex
                }
            }
            
            val chunk = text.substring(startIndex, adjustedEndIndex)
            result.add(TextChunk(chunk, chunkIndex++))
            
            // Move the start index, accounting for overlap
            startIndex = adjustedEndIndex - (if (adjustedEndIndex < text.length) chunkOverlap else 0)
        }
        
        return result
    }

    /**
     * Chunks text by paragraphs while respecting maximum chunk size.
     */
    @JvmStatic
    fun chunkByParagraphs(
        text: String,
        maxChunkSize: Int,
        chunkOverlap: Int,
    ): List<TextChunk> {
        val result = mutableListOf<TextChunk>()
        
        // Split by paragraph breaks
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
        
        var currentChunk = StringBuilder()
        var chunkIndex = 0
        
        for (paragraph in paragraphs) {
            val trimmedParagraph = paragraph.trim()
            
            // Skip empty paragraphs
            if (trimmedParagraph.isEmpty()) continue
            
            // If this paragraph would make the chunk too big, store the current chunk and start a new one
            if (currentChunk.isNotEmpty() && currentChunk.length + trimmedParagraph.length > maxChunkSize) {
                result.add(TextChunk(currentChunk.toString(), chunkIndex++))
                
                // Start a new chunk with overlap if possible
                if (currentChunk.length > chunkOverlap) {
                    val overlapText = currentChunk.substring(currentChunk.length - chunkOverlap)
                    currentChunk = StringBuilder(overlapText)
                } else {
                    currentChunk = StringBuilder()
                }
            }
            
            // Add a newline before the paragraph if this isn't the start of a chunk
            if (currentChunk.isNotEmpty()) {
                currentChunk.append("\n\n")
            }
            
            currentChunk.append(trimmedParagraph)
        }
        
        // Don't forget the last chunk
        if (currentChunk.isNotEmpty()) {
            result.add(TextChunk(currentChunk.toString(), chunkIndex))
        }
        
        return result
    }

    /**
     * Chunks text by sentences while respecting maximum chunk size.
     */
    @JvmStatic
    fun chunkBySentences(
        text: String,
        maxChunkSize: Int,
        chunkOverlap: Int,
    ): List<TextChunk> {
        val result = mutableListOf<TextChunk>()
        
        // Split by sentence-ending punctuation followed by space or newline
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        
        var currentChunk = StringBuilder()
        var lastChunkText = ""
        var chunkIndex = 0
        
        for (sentence in sentences) {
            val trimmedSentence = sentence.trim()
            
            // Skip empty sentences
            if (trimmedSentence.isEmpty()) continue
            
            // If this sentence would make the chunk too big, store the current chunk and start a new one
            if (currentChunk.isNotEmpty() && currentChunk.length + trimmedSentence.length > maxChunkSize) {
                val chunkText = currentChunk.toString()
                result.add(TextChunk(chunkText, chunkIndex++))
                lastChunkText = chunkText
                
                // Start a new chunk with overlap if possible
                if (lastChunkText.length > chunkOverlap) {
                    // Try to find a sentence boundary for the overlap
                    val overlapText = findOverlapAtSentenceBoundary(lastChunkText, chunkOverlap)
                    currentChunk = StringBuilder(overlapText)
                } else {
                    currentChunk = StringBuilder()
                }
            }
            
            // Add a space before the sentence if this isn't the start of a chunk
            if (currentChunk.isNotEmpty() && !currentChunk.endsWith(" ")) {
                currentChunk.append(" ")
            }
            
            currentChunk.append(trimmedSentence)
        }
        
        // Don't forget the last chunk
        if (currentChunk.isNotEmpty()) {
            result.add(TextChunk(currentChunk.toString(), chunkIndex))
        }
        
        return result
    }

    /**
     * Helper method to find a clean sentence boundary for overlap.
     */
    @JvmStatic
    private fun findOverlapAtSentenceBoundary(
        text: String,
        desiredOverlap: Int,
    ): String {
        val startIdx = maxOf(0, text.length - desiredOverlap)
        
        // Look for sentence boundary after the start index
        val sentenceBoundaryPattern = Regex("(?<=[.!?])\\s+")
        val matches = sentenceBoundaryPattern.findAll(text.substring(startIdx))
        
        return if (matches.count() > 0) {
            // Get the text from the first sentence boundary after start index
            val firstMatchEnd = startIdx + matches.first().range.last + 1
            text.substring(firstMatchEnd)
        } else {
            // No sentence boundary found, return from the start index
            text.substring(startIdx)
        }
    }
}

/**
 * Represents a chunk of text with metadata.
 * Now included in the utils package for broader use.
 */
data class TextChunk(
    val text: String,
    val index: Int,
    val metadata: Map<String, Any> = emptyMap(),
)
