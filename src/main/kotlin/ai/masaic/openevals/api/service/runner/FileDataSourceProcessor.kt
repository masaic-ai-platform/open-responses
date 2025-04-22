package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.*
import ai.masaic.openresponses.api.service.storage.FileService
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.mitchellbosecke.pebble.PebbleEngine
import com.mitchellbosecke.pebble.template.PebbleTemplate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.StringWriter

/**
 * Implementation of DataSourceProcessor for file-based data sources.
 */
@Component
class FileDataSourceProcessor(
    private val fileService: FileService,
    private val objectMapper: ObjectMapper,
    private val pebbleEngine: PebbleEngine,
) : DataSourceProcessor {
    private val logger = LoggerFactory.getLogger(FileDataSourceProcessor::class.java)

    /**x
     * Checks if this processor can handle the given data source type.
     *
     * @param dataSource The data source to check
     * @return True if this processor can handle the data source
     */
    override fun canProcess(dataSource: RunDataSource): Boolean = dataSource.source is FileDataSource

    /**
     * Process the data source and prepare the appropriate data structure based on the source type.
     *
     * @param dataSource The data source to process
     * @return Result of processing the data source
     */
    override suspend fun processDataSource(dataSource: RunDataSource): DataSourceProcessingResult {
        logger.info("Processing file data source")
        val fileDataSource = dataSource.source as FileDataSource
        
        // Handle CompletionsRunDataSource
        if (dataSource is CompletionsRunDataSource) {
            return processCompletionsDataSource(dataSource)
        }

        // Add support for other data source types here as needed
        // Example: if (dataSource is JsonlRunDataSource) { ... }

        // Default fallback for unsupported types
        return EmptyProcessingResult("Unsupported data source type: ${dataSource.javaClass.simpleName}")
    }

    /**
     * Process a CompletionsRunDataSource.
     *
     * @param dataSource The completions data source
     * @return Completion messages result
     */
    private suspend fun processCompletionsDataSource(dataSource: CompletionsRunDataSource): DataSourceProcessingResult {
        // Read and validate file content
        val jsonLines = getRawDataLines(dataSource)

        // Process based on input message type
        val messagesMap =
            when (val inputMessages = dataSource.inputMessages) {
                is TemplateInputMessages -> {
                    // Prepare template once
                    val templateStr = objectMapper.writeValueAsString(inputMessages.template)
                    val compiledTemplate = pebbleEngine.getLiteralTemplate(templateStr)

                    // Process each line with the template
                    jsonLines
                        .asSequence()
                        .mapIndexed { index, jsonLine ->
                            index to processJsonLineWithTemplate(jsonLine, compiledTemplate)
                        }.toMap()
                }
                is ItemReferenceInputMessages -> {
                    logger.info("Processing item reference: ${inputMessages.itemReference}")
                    // Item reference handling will be implemented later
                    emptyMap()
                }
                else -> {
                    logger.warn("Unsupported input message type: ${inputMessages.javaClass.simpleName}")
                    emptyMap()
                }
            }

        return if (messagesMap.isNotEmpty()) {
            CompletionMessagesResult(messagesMap)
        } else {
            EmptyProcessingResult("No messages could be processed from the data source")
        }
    }

    /**
     * Process JsonlRunDataSource (for future implementation).
     *
     * @param dataSource The JSONL data source
     * @return JSONL data result
     */
    private suspend fun processJsonlDataSource(dataSource: RunDataSource): DataSourceProcessingResult {
        // Read and validate file content
        val jsonLines = getRawDataLines(dataSource)
        
        // Parse each line as a JsonNode
        val itemsMap =
            jsonLines
                .asSequence()
                .mapIndexed { index, jsonLine ->
                    index to objectMapper.readTree(jsonLine)
                }.toMap()

        return if (itemsMap.isNotEmpty()) {
            JsonlDataResult(itemsMap)
        } else {
            EmptyProcessingResult("No JSON items could be processed from the data source")
        }
    }

    /**
     * Get the raw data lines from the file data source.
     *
     * @param dataSource The data source to get lines from
     * @return List of raw data lines as strings
     */
    override suspend fun getRawDataLines(dataSource: RunDataSource): List<String> {
        if (dataSource.source !is FileDataSource) {
            throw IllegalArgumentException("Data source is not a FileDataSource")
        }
        
        val fileDataSource = dataSource.source as FileDataSource
        val content =
            fileService
                .getFileContent(fileDataSource.id)
                .inputStream
                .bufferedReader()
                .use { it.readText() }
        return validateJsonl(content)
    }

    /**
     * Process a single JSON line with the given template.
     *
     * @param jsonLine The JSON line as a string
     * @param template The compiled Pebble template
     * @return List of ChatMessage objects after template processing
     */
    private fun processJsonLineWithTemplate(
        jsonLine: String,
        template: PebbleTemplate,
    ): List<ChatMessage> {
        // Parse the JSON context
        val context: Map<String, Any> = objectMapper.readValue(jsonLine)

        // Evaluate template with context
        val writer = StringWriter()
        template.evaluate(writer, context)

        // Parse result back to ChatMessage list
        return objectMapper.readValue(writer.toString())
    }

    companion object {
        val objectMapper = jacksonObjectMapper()

        /**
         * Validate that each line of the JSONL file is valid JSON.
         *
         * @param content The JSONL file content
         * @return List of validated JSON strings
         */
        fun validateJsonl(content: String): List<String> {
            val jsonLines = mutableListOf<String>()
            val lines = content.trim().split("\n")

            lines.forEachIndexed { index, line ->
                if (line.isNotBlank()) {
                    try {
                        // Validate by attempting to parse, but don't store the JsonNode
                        objectMapper.readTree(line)
                        // If no exception was thrown, add the original line to the result
                        jsonLines.add(line)
                    } catch (e: JsonParseException) {
                        throw IllegalArgumentException("Invalid JSON at line ${index + 1}: ${e.message}")
                    }
                }
            }

            return jsonLines
        }
    }
} 
