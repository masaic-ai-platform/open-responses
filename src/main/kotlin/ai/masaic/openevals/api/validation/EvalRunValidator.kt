package ai.masaic.openevals.api.validation

import ai.masaic.openevals.api.model.*
import ai.masaic.openevals.api.service.runner.FileDataSourceProcessor
import ai.masaic.openresponses.api.service.storage.FileService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.mitchellbosecke.pebble.PebbleEngine
import com.mitchellbosecke.pebble.error.RootAttributeNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.StringWriter

/**
 * Validator for EvalRun requests.
 * Handles validation of template expressions against file content.
 */
@Component
class EvalRunValidator(
    private val fileService: FileService,
    private val objectMapper: ObjectMapper,
    private val pebbleEngine: PebbleEngine,
) {
    private val logger = LoggerFactory.getLogger(EvalRunValidator::class.java)

    /**
     * Validates an EvalRun creation request.
     *
     * @param request The request to validate
     * @throws IllegalArgumentException if validation fails
     */
    suspend fun validate(request: CreateEvalRunRequest) {
        val dataSource = request.dataSource
        
        // Only validate CompletionsRunDataSource with TemplateInputMessages
        if (dataSource is CompletionsRunDataSource && dataSource.inputMessages is TemplateInputMessages) {
            validateCompletionsDataSource(dataSource)
        }
    }

    /**
     * Validates a CompletionsRunDataSource with template expressions.
     *
     * @param dataSource The data source to validate
     * @throws IllegalArgumentException if validation fails
     */
    private suspend fun validateCompletionsDataSource(dataSource: CompletionsRunDataSource) {
        // Check if using template messages
        if (dataSource.inputMessages !is TemplateInputMessages) {
            return
        }

        // Check if this is a file data source
        if (dataSource.source !is FileDataSource) {
            return
        }
        
        // Get file content to validate template expressions against
        val fileContent = getFileContent(dataSource.source.id)
        if (fileContent.isEmpty()) {
            throw IllegalArgumentException("File content is empty")
        }
        
        try {
            // Get a sample JSON line
            val sampleLine = fileContent.first()

            // Parse the JSON context
            val context: Map<String, Any> = objectMapper.readValue(sampleLine)
            val compiledTemplate = pebbleEngine.getLiteralTemplate(objectMapper.writeValueAsString(dataSource.inputMessages.template))

            // Evaluate template with context
            val writer = StringWriter()
            compiledTemplate.evaluate(writer, context)
        } catch (e: Exception) {
            logger.error("Error validating template: ${e.message}")
            if (e is RootAttributeNotFoundException) {
                throw IllegalArgumentException("File source data content is not matching with the template used in input_messages")
            }
            throw e
        }
    }

    /**
     * Gets and validates the content of a file.
     *
     * @param fileId ID of the file to retrieve
     * @return List of validated JSON lines from the file
     * @throws IllegalArgumentException if file content is invalid
     */
    private suspend fun getFileContent(fileId: String): List<String> {
        val content =
            fileService
                .getFileContent(fileId)
                .inputStream
                .bufferedReader()
                .use { it.readText() }

        return FileDataSourceProcessor.validateJsonl(content)
    }
} 
