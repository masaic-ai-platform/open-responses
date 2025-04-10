package ai.masaic.openresponses.api.utils

import org.apache.tika.Tika
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.slf4j.LoggerFactory
import java.io.InputStream

/**
 * Service for extracting text content from various document formats.
 *
 * Uses Apache Tika to parse different file types including PDF, Word, Excel,
 * PowerPoint, and other document formats into plain text.
 */
class DocumentTextExtractor {
    companion object {
        private val log = LoggerFactory.getLogger(DocumentTextExtractor::class.java)
        private val tika = Tika()

        /**
         * Extracts text content from an input stream.
         *
         * @param inputStream The input stream of the document
         * @param filename The name of the file, used for logging and metadata
         * @return Extracted text content as a string
         */
        fun extractText(
            inputStream: InputStream,
            filename: String,
        ): String {
            try {
                log.debug("Extracting text from file: {}", filename)
                val metadata = Metadata()
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename)

                // Parse the document with Tika
                return tika.parseToString(inputStream, metadata)
            } catch (e: Exception) {
                log.error("Error extracting text from file {}: {}", filename, e.message, e)
                return "" // Return empty string on error
            }
        }

        /**
         * Extracts text and performs basic text cleanup.
         *
         * @param inputStream The input stream of the document
         * @param filename The name of the file
         * @return Cleaned text content as a string
         */
        fun extractAndCleanText(
            inputStream: InputStream,
            filename: String,
        ): String {
            val text = extractText(inputStream, filename)
            return cleanText(text)
        }

        /**
         * Performs basic text cleanup on extracted content.
         *
         * @param text The text to clean
         * @return Cleaned text
         */
        private fun cleanText(text: String): String {
            return text
                .replace("\\s+".toRegex(), " ") // Replace multiple whitespaces with a single space
                .trim() // Trim leading and trailing whitespace
        }
    }
}
