package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.*
import ai.masaic.openevals.api.repository.EvalRepository
import ai.masaic.openevals.api.repository.EvalRunRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Component responsible for orchestrating evaluation runs.
 * This class coordinates the different steps of the evaluation process.
 */
@Component
class EvalRunner(
    private val evalRunRepository: EvalRunRepository,
    private val evalRepository: EvalRepository,
    private val dataSourceProcessors: List<DataSourceProcessor>,
    private val generationServices: List<GenerationService>,
    private val criterionEvaluatorFactory: CriterionEvaluatorFactory,
    private val resultProcessor: ResultProcessor
) {
    private val logger = LoggerFactory.getLogger(EvalRunner::class.java)

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

            // Find the appropriate data source processor
            val dataSourceProcessor = dataSourceProcessors.find { it.canProcess(evalRun.dataSource) }
                ?: throw IllegalStateException("No processor found for data source type: ${evalRun.dataSource.source.javaClass.simpleName}")

            // Read the original data
            val jsonLines = dataSourceProcessor.getRawDataLines(evalRun.dataSource)
            logger.info("Read ${jsonLines.size} data lines from source")

            // Process the data source
            val processingResult = dataSourceProcessor.processDataSource(evalRun.dataSource)
            logger.info("Processed data source with result type: ${processingResult.javaClass.simpleName}")

            // Handle different types of processing results
            when (processingResult) {
                is CompletionMessagesResult -> {
                    // Handle completion messages for evaluation
                    processCompletionMessages(processingResult, evalRun, eval, jsonLines)?.let { 
                        updatedEvalRun = it
                    }
                }
                
                is JsonlDataResult -> {
                    // Handle JSONL data result (future implementation)
                    logger.info("JSONL data processing not yet implemented")
                    updatedEvalRun = updatedEvalRun.copy(
                        status = EvalRunStatus.FAILED,
                        error = EvalRunError(
                            code = "unsupported_operation",
                            message = "JSONL data processing not yet implemented"
                        )
                    )
                }
                
                is EmptyProcessingResult -> {
                    // Handle empty result
                    logger.warn("Empty processing result: ${processingResult.reason}")
                    updatedEvalRun = updatedEvalRun.copy(
                        status = EvalRunStatus.FAILED,
                        error = EvalRunError(
                            code = "processing_error",
                            message = processingResult.reason
                        )
                    )
                }
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
     * Process completion messages for evaluation.
     *
     * @param completionResult The completion messages result
     * @param evalRun The evaluation run
     * @param eval The evaluation definition
     * @param jsonLines The original JSON lines
     * @return Updated evaluation run, or null if processing failed
     */
    private suspend fun processCompletionMessages(
        completionResult: CompletionMessagesResult,
        evalRun: EvalRun,
        eval: Eval,
        jsonLines: List<String>
    ): EvalRun? {
        // Validate data source type
        if (evalRun.dataSource !is CompletionsRunDataSource) {
            logger.error("CompletionMessagesResult received but evalRun.dataSource is not CompletionsRunDataSource")
            return null
        }

        logger.info("Generated ${completionResult.messages.size} completion messages")

        // Find appropriate generation service
        val generationService = generationServices.find { it.canGenerate(evalRun.dataSource) }
            ?: throw IllegalStateException("No generation service found for data source type: ${evalRun.dataSource.javaClass.simpleName}")

        // Generate completions
        val resultMap = generationService.generateCompletions(
            completionResult.messages,
            evalRun.dataSource,
            evalRun.apiKey,
            eval.dataSourceConfig as CustomDataSourceConfig
        )

        // Evaluate testing criteria
        val testingResults = evaluateTestingCriteria(
            resultMap,
            jsonLines,
            eval.testingCriteria
        )

        // Calculate result counts
        val resultCounts = resultProcessor.calculateResultCounts(testingResults)
        val perCriteriaResults = resultProcessor.calculatePerCriteriaResults(testingResults, eval.testingCriteria)

        // Return updated eval run
        return evalRun.copy(
            status = EvalRunStatus.COMPLETED,
            resultCounts = resultCounts,
            perTestingCriteriaResults = perCriteriaResults
        )
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
        resultMap: Map<Int, GenerationService.CompletionResult>,
        jsonLines: List<String>,
        testingCriteria: List<TestingCriterion>
    ): Map<Int, Map<String, CriterionEvaluator.CriterionResult>> {
        val results = mutableMapOf<Int, Map<String, CriterionEvaluator.CriterionResult>>()

        resultMap.forEach { (index, completionResult) ->
            // Get the corresponding JSON line for this index
            val jsonLine = if (index < jsonLines.size) jsonLines[index] else null
            if (jsonLine == null) {
                logger.warn("No JSON data found for index $index")
                return@forEach
            }

            val criteriaResults = mutableMapOf<String, CriterionEvaluator.CriterionResult>()

            // Evaluate each testing criterion
            testingCriteria.forEach { criterion ->
                // Pass completion result as actual and original data as reference
                val result = criterionEvaluatorFactory.evaluate(
                    criterion, 
                    actualJson = completionResult.contentJson, 
                    referenceJson = jsonLine
                )
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
} 