package ai.masaic.openevals.api.service

import ai.masaic.openevals.api.model.*
import ai.masaic.openevals.api.repository.EvalRepository
import ai.masaic.openevals.api.repository.EvalRunRepository
import ai.masaic.openresponses.api.service.storage.FileService
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.mitchellbosecke.pebble.PebbleEngine
import com.mitchellbosecke.pebble.template.PebbleTemplate
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.credential.BearerTokenCredential
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.chat.completions.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.StringWriter
import java.util.regex.Pattern

/**
 * Component responsible for processing evaluation runs.
 * This class handles the actual evaluation logic independently from the service layer.
 */
@Component
class EvalRunner(
    private val evalRunRepository: EvalRunRepository,
    private val evalRepository: EvalRepository,
    private val fileService: FileService,
    private val objectMapper: ObjectMapper,
    private val pebbleEngine: PebbleEngine
) {
    private val logger = LoggerFactory.getLogger(EvalRunner::class.java)
    private val mapper = jacksonObjectMapper()

    /**
     * Process an evaluation run.
     *
     * @param evalRun The evaluation run to process
     */
    suspend fun processEvalRun(evalRun: EvalRun) {
        try {
            // Update status to running
            var updatedEvalRun = evalRun.copy(status = EvalRunStatus.IN_PROGRESS)
            evalRunRepository.updateEvalRun(updatedEvalRun)

            val eval = evalRepository.getEval(evalRun.evalId) ?: throw IllegalStateException("Eval with evalId: ${evalRun.evalId} not found")

            // Process based on data source type
            when (val source = evalRun.dataSource.source) {
                is FileDataSource -> {
                    // Read the original JSONL file
                    val jsonLines = readJsonlFile(source.fileId)

                    // Process the completions
                    val completionsMessagesSet = processJsonlFile(source.fileId, evalRun.dataSource)
                    logger.info("Generated ${completionsMessagesSet.size} completion messages")

                    // Call the completion API with the prepared messages
                    if (evalRun.dataSource is CompletionsRunDataSource) {
                        val resultMap = processCompletions(
                            completionsMessagesSet,
                            evalRun,
                            eval
                        )

                        // Evaluate testing criteria
                        val testingResults = evaluateTestingCriteria(
                            resultMap,
                            jsonLines,
                            eval.testingCriteria
                        )

                        // Calculate result counts
                        val resultCounts = calculateResultCounts(testingResults)
                        val perCriteriaResults = calculatePerCriteriaResults(testingResults, eval.testingCriteria)

                        // Update eval run with results
                        updatedEvalRun = updatedEvalRun.copy(
                            status = EvalRunStatus.COMPLETED,
                            resultCounts = resultCounts,
                            perTestingCriteriaResults = perCriteriaResults
                        )
                    }
                }

                else -> throw UnsupportedOperationException("Unsupported data source type: ${source.javaClass.simpleName}")
            }

            evalRunRepository.updateEvalRun(updatedEvalRun)
        } catch (e: Exception) {
            logger.error("Error processing eval run: ${e.message}", e)

            // Handle errors
            val errorRun = evalRun.copy(
                status = EvalRunStatus.FAILED,
                error = EvalRunError(
                    code = "processing_error",
                    message = e.message ?: "Unknown error occurred during evaluation run processing"
                )
            )
            evalRunRepository.updateEvalRun(errorRun)
        }
    }

    /**
     * Read the JSONL file, returning the raw lines.
     *
     * @param fileId The file ID
     * @return List of JSON string lines
     */
    private suspend fun readJsonlFile(fileId: String): List<String> {
        val content = fileService.getFileContent(fileId).inputStream.bufferedReader().use { it.readText() }
        return content.trim().split("\n").filter { it.isNotBlank() }
    }

    /**
     * Evaluate testing criteria for each completion result.
     *
     * @param resultMap Map of completion results by index
     * @param jsonLines Original JSON lines from the file
     * @param testingCriteria List of testing criteria to evaluate
     * @return Map of testing criteria results by index and criteria
     */
    private fun evaluateTestingCriteria(
        resultMap: Map<Int, CompletionResult>,
        jsonLines: List<String>,
        testingCriteria: List<TestingCriterion>
    ): Map<Int, Map<String, CriterionResult>> {
        val results = mutableMapOf<Int, Map<String, CriterionResult>>()

        resultMap.forEach { (index, completionResult) ->
            // Get the corresponding JSON line for this index
            val jsonLine = if (index < jsonLines.size) jsonLines[index] else null
            if (jsonLine == null) {
                logger.warn("No JSON data found for index $index")
                return@forEach
            }

            val criteriaResults = mutableMapOf<String, CriterionResult>()

            // Evaluate each testing criterion
            testingCriteria.forEach { criterion ->
                val result = when (criterion) {
                    is StringCheckGrader -> evaluateStringCheck(criterion, completionResult.contentJson, jsonLine)
                    is TextSimilarityGrader -> evaluateTextSimilarity(criterion, completionResult.contentJson, jsonLine)
                    is LabelModelGrader -> {
                        logger.warn("LabelModelGrader not implemented yet")
                        CriterionResult(false, "LabelModelGrader not implemented")
                    }
                    else -> {
                        logger.warn("Unknown criterion type: ${criterion.javaClass.simpleName}")
                        CriterionResult(false, "Unknown criterion type")
                    }
                }

                criteriaResults[criterion.name] = result
                logger.debug("Criterion '${criterion.name}' result: ${result.passed} - ${result.message}")
            }

            results[index] = criteriaResults
        }

        // Log summary
        val totalItems = results.size
        val totalCriteria = testingCriteria.size
        val totalTests = totalItems * totalCriteria
        val passedTests = results.values.sumOf { criteriaMap -> criteriaMap.values.count { it.passed } }

        logger.info("Testing criteria evaluation: $passedTests/$totalTests tests passed across $totalItems items")

        return results
    }

    /**
     * Evaluate a string check criterion.
     *
     * @param criterion The string check criterion
     * @param completionJson The completion result JSON string
     * @param jsonLine The original JSON line string
     * @return Result of the evaluation
     */
    private fun evaluateStringCheck(
        criterion: StringCheckGrader,
        completionJson: String,
        jsonLine: String
    ): CriterionResult {
        try {
            // Use Pebble to resolve the values directly from the JSON strings
            val inputValue = resolveTemplateValue(criterion.input, completionJson)
            val referenceValue = resolveTemplateValue(criterion.reference, jsonLine)

            logger.debug("String check: comparing '$inputValue' to '$referenceValue' with operation ${criterion.operation}")

            if (inputValue.isBlank()) {
                return CriterionResult(false, "Input value not found or empty: '${criterion.input}' in completion result")
            }

            if (referenceValue.isBlank()) {
                return CriterionResult(false, "Reference value not found or empty: '${criterion.reference}' in JSON data")
            }

            // Perform the comparison based on the operation
            val passed = when (criterion.operation) {
                StringCheckGrader.Operation.EQUAL -> inputValue == referenceValue
                StringCheckGrader.Operation.NOT_EQUAL -> inputValue != referenceValue
                StringCheckGrader.Operation.LIKE -> inputValue.contains(referenceValue)
                StringCheckGrader.Operation.ILIKE -> inputValue.lowercase().contains(referenceValue.lowercase())
            }

            return CriterionResult(
                passed,
                if (passed) "Check passed: '$inputValue' ${criterion.operation} '$referenceValue'"
                else "Check failed: '$inputValue' ${criterion.operation} '$referenceValue'"
            )
        } catch (e: Exception) {
            logger.error("Error evaluating string check: ${e.message}", e)
            return CriterionResult(false, "Error: ${e.message}")
        }
    }

    /**
     * Evaluate a text similarity criterion.
     *
     * @param criterion The text similarity criterion
     * @param completionJson The completion result JSON string
     * @param jsonLine The original JSON line string
     * @return Result of the evaluation
     */
    private fun evaluateTextSimilarity(
        criterion: TextSimilarityGrader,
        completionJson: String,
        jsonLine: String
    ): CriterionResult {
        try {
            // Use Pebble to resolve the values directly from the JSON strings
            val inputValue = resolveTemplateValue(criterion.input, completionJson)
            val referenceValue = resolveTemplateValue(criterion.reference, jsonLine)

            if (inputValue.isBlank()) {
                return CriterionResult(false, "Input value not found or empty: '${criterion.input}' in completion result")
            }

            if (referenceValue.isBlank()) {
                return CriterionResult(false, "Reference value not found or empty: '${criterion.reference}' in JSON data")
            }

            // Calculate similarity
            val similarity = calculateSimilarity(inputValue, referenceValue)
            val passed = similarity >= criterion.passThreshold

            return CriterionResult(
                passed,
                if (passed) "Similarity check passed with score $similarity"
                else "Similarity check failed with score $similarity"
            )
        } catch (e: Exception) {
            logger.error("Error evaluating text similarity: ${e.message}", e)
            return CriterionResult(false, "Error: ${e.message}")
        }
    }

    /**
     * Resolve a template value using Pebble.
     *
     * @param template The template string (e.g., "{{item.correct_label}}")
     * @param jsonStr The JSON string containing the context
     * @return The resolved value
     */
    private fun resolveTemplateValue(template: String, jsonStr: String): String {
        try {
            // Parse the JSON into a Map
            val context: Map<String, Any> = mapper.readValue(jsonStr)

            // Compile and evaluate the template
            val compiledTemplate = pebbleEngine.getLiteralTemplate(template)
            val writer = StringWriter()
            compiledTemplate.evaluate(writer, context)

            return writer.toString().trim()
        } catch (e: Exception) {
            logger.warn("Error resolving template '$template': ${e.message}")
            return ""
        }
    }
    /**
     * Calculate a simple similarity score between two strings.
     * This is a placeholder for more sophisticated similarity measures.
     *
     * @param str1 First string
     * @param str2 Second string
     * @return Similarity score between 0.0 and 1.0
     */
    private fun calculateSimilarity(str1: String, str2: String): Double {
        // Simple implementation using Jaccard similarity of words
        val words1 = str1.lowercase().split(Regex("\\W+")).toSet()
        val words2 = str2.lowercase().split(Regex("\\W+")).toSet()

        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size

        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    /**
     * Resolve a JSON path expression to get a value from a JSON node.
     * Handles variables in the format {{item.property}}.
     *
     * @param path The JSON path expression (e.g., "{{item.ticket_text}}")
     * @param jsonNode The JSON node to extract the value from
     * @return The extracted value as a string, or null if not found
     */
    private fun resolveJsonPathValue(path: String, jsonNode: JsonNode?): String? {
        if (jsonNode == null) return null

        // Extract the actual path from {{...}} format
        val matcher = Pattern.compile("\\{\\{(.*?)\\}\\}").matcher(path)

        if (matcher.find()) {
            val jsonPath = matcher.group(1).trim()
            val pathElements = jsonPath.split(".")

            var currentNode = jsonNode

            // Navigate the JSON path
            for (element in pathElements) {
                currentNode = currentNode?.get(element) ?: return null
            }

            return currentNode?.asText()
        } else {
            // If not in {{...}} format, use the path directly
            return path
        }
    }

    /**
     * Calculate overall result counts from testing criteria results.
     *
     * @param results Map of testing criteria results
     * @return Overall result counts
     */
    private fun calculateResultCounts(
        results: Map<Int, Map<String, CriterionResult>>
    ): ResultCounts {
        var passed = 0
        var failed = 0
        var errored = 0

        results.forEach { (_, criteriaResults) ->
            // If all criteria for this item passed, count the item as passed
            val allPassed = criteriaResults.values.all { it.passed }
            val anyError = criteriaResults.values.any { it.message?.startsWith("Error:") == true }

            when {
                anyError -> errored++
                allPassed -> passed++
                else -> failed++
            }
        }

        return ResultCounts(
            passed = passed,
            failed = failed,
            errored = errored,
            total = results.size
        )
    }

    /**
     * Calculate per-criteria results from testing criteria results.
     *
     * @param results Map of testing criteria results
     * @param testingCriteria List of testing criteria
     * @return List of per-criteria results
     */
    private fun calculatePerCriteriaResults(
        results: Map<Int, Map<String, CriterionResult>>,
        testingCriteria: List<TestingCriterion>
    ): List<TestingCriteriaResult> {
        return testingCriteria.map { criterion ->
            var passed = 0
            var failed = 0

            results.forEach { (_, criteriaResults) ->
                criteriaResults[criterion.name]?.let { result ->
                    if (result.passed) passed++ else failed++
                }
            }

            TestingCriteriaResult(
                testingCriteria = criterion.name,
                passed = passed,
                failed = failed
            )
        }
    }

    /**
     * Data class to store the result of a single criterion evaluation.
     */
    private data class CriterionResult(
        val passed: Boolean,
        val message: String? = null
    )

    /**
     * Process a JSONL file and prepare the data for evaluation.
     *
     * @param fileId The ID of the JSONL file
     * @param dataSource The data source configuration
     * @return Map of completion messages indexed by the line number
     */
    private suspend fun processJsonlFile(fileId: String, dataSource: RunDataSource): Map<Int, List<ChatMessage>> {
        logger.info("Processing JSONL file with ID: $fileId")

        // Read and validate file content
        val content = fileService.getFileContent(fileId).inputStream.bufferedReader().use { it.readText() }
        val jsonLines = validateJsonl(content)

        // Early return if not a completions data source
        if (dataSource !is CompletionsRunDataSource) { //TODO: JB to revisit
            logger.warn("Data source is not a CompletionsRunDataSource, no messages to process")
            return emptyMap()
        }

        // Process based on input message type
        return when (val inputMessages = dataSource.inputMessages) {
            is TemplateInputMessages -> {
                // Prepare template once
                val templateStr = mapper.writeValueAsString(inputMessages.template)
                val compiledTemplate = pebbleEngine.getLiteralTemplate(templateStr)

                // Process each line in parallel with the template
                jsonLines.asSequence()
                    .mapIndexed { index, jsonLine ->
                        index to processJsonLineWithTemplate(jsonLine.toString(), compiledTemplate)
                    }
                    .toMap()
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
    }

    /**
     * Process the completions for each message set.
     *
     * @param completionMessagesSet Map of completion message sets
     * @param dataSource The completions data source with model and parameters
     * @param evalRun The evaluation run
     * @return List of completion results
     */
    private suspend fun  processCompletions(
        completionMessagesSet: Map<Int, List<ChatMessage>>,
        evalRun: EvalRun,
        eval: Eval
    ): Map<Int,CompletionResult> = runBlocking {
        val evalRunDataSource = evalRun.dataSource as CompletionsRunDataSource
        logger.info("Processing ${completionMessagesSet.size} completions with model: ${evalRunDataSource.model}")
        // Create OpenAI client
        val openAIClient = createOpenAIClient(evalRun.apiKey)

        // Process each message set in parallel
        val resultMap = mutableMapOf<Int, CompletionResult>()
        completionMessagesSet.map { (index, messages) ->
            async {
                try {
                    // Create the completion params
                    val completionParams = createCompletionParams(messages, evalRunDataSource, eval.dataSourceConfig as CustomDataSourceConfig)

                    // Call the OpenAI API
                    val completion = openAIClient.chat().completions().create(completionParams)

                    // Extract the result
                    val content = completion.choices().firstOrNull()?.message()?.content()?.orElse("")

                    resultMap[index] = CompletionResult(
                        contentJson = content ?: ""
                    )
                } catch (e: Exception) {
                    logger.error("Error processing completion for index $index: ${e.message}", e)
                    resultMap[index] = CompletionResult(
                        contentJson = "",
                        error = e.message ?: "Unknown error"
                    )
                }
            }
        }.awaitAll()

        logger.info("Completed processing ${resultMap.size} completions")
        return@runBlocking resultMap
    }

    /**
     * Create OpenAI client with authentication.
     *
     * @return OpenAI client instance
     */
    private fun createOpenAIClient(apiKey: String): OpenAIClient {
        val baseUrl = "https://api.openai.com/v1"

        return OpenAIOkHttpClient.builder()
            .credential(BearerTokenCredential.create { apiKey })
            .baseUrl(baseUrl)
            .build()
    }

    /**
     * Create completion parameters for the OpenAI API.
     *
     * @param messages The chat messages
     * @param dataSource The data source configuration
     * @return Completion parameters
     */
    private fun  createCompletionParams(
        messages: List<ChatMessage>,
        dataSource: CompletionsRunDataSource,
        dataSourceConfig: CustomDataSourceConfig
    ): ChatCompletionCreateParams {

        val schema = ResponseFormatJsonSchema.JsonSchema.Schema.builder().additionalProperties(dataSourceConfig.schema).build()
        val format = ResponseFormatJsonSchema.JsonSchema.builder().schema(schema) .name("evalSchema").build()
        val builder = ChatCompletionCreateParams.builder()
            .model(dataSource.model)
            .responseFormat(ChatCompletionCreateParams.ResponseFormat.ofJsonSchema(
                ResponseFormatJsonSchema.builder().type(
                    JsonValue.from("json_schema"))
                    .jsonSchema(format)
                    .build()))

        // Add messages
        messages.forEach { message ->
            when (message.role) {
                "system" -> {
                    builder.addMessage(
                        ChatCompletionSystemMessageParam.builder()
                            .content(message.content)
                            .build()
                    )
                }
                "user" -> {
                    builder.addMessage(
                        ChatCompletionUserMessageParam.builder()
                            .content(message.content)
                            .build()
                    )
                }
                "assistant" -> {
                    builder.addMessage(
                        ChatCompletionAssistantMessageParam.builder()
                            .content(message.content)
                            .build()
                    )
                }
                else -> {
                    // Default to user role for unknown roles
                    builder.addMessage(
                        ChatCompletionUserMessageParam.builder()
                            .content(message.content)
                            .build()
                    )
                }
            }
        }

        // Add sampling parameters if available
        dataSource.samplingParams?.let { params ->
            builder.temperature(params.temperature)
            builder.topP(params.topP)
        }

        return builder.build()
    }

//    /**
//     * Calculate result counts from completion results.
//     *
//     * @param results The map of completion results
//     * @return Result counts statistics
//     */
//    private fun calculateResultCounts(results: Map<Int, CompletionResult>): ResultCounts {
//        val total = results.size
//        val errored = results.count { (_, result) -> result.error != null }
//        val passed = results.count { (_, result) -> result.error == null }
//
//        return ResultCounts(
//            passed = passed,
//            failed = 0, // No explicit failure condition in current implementation
//            errored = errored,
//            total = total
//        )
//    }

    /**
     * Data class to store completion results.
     */
    private data class CompletionResult(
        val contentJson: String,
        val error: String? = null
    )

    /**
     * Process a single JSON line with the given template.
     *
     * @param jsonLine The JSON line as a string
     * @param template The compiled Pebble template
     * @return List of ChatMessage objects after template processing
     */
    private fun processJsonLineWithTemplate(jsonLine: String, template: PebbleTemplate): List<ChatMessage> {
        // Parse the JSON context
        val context: Map<String, Any> = mapper.readValue(jsonLine)

        // Evaluate template with context
        val writer = StringWriter()
        template.evaluate(writer, context)

        // Parse result back to ChatMessage list
        return mapper.readValue(writer.toString())
    }

    /**
     * Validate that each line of the JSONL file is valid JSON.
     *
     * @param content The JSONL file content
     * @return List of validated JSON strings
     */
    private fun validateJsonl(content: String): List<String> {
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
