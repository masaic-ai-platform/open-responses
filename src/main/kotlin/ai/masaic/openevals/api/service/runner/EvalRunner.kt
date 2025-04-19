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
    private val resultProcessor: ResultProcessor,
) {
    private val logger = LoggerFactory.getLogger(EvalRunner::class.java)

    /**
     * Process an evaluation run.
     *
     * @param evalRun The evaluation run to process
     */
    suspend fun processEvalRun(evalRun: EvalRun) {
        var updatedEvalRun = evalRun.copy(status = EvalRunStatus.IN_PROGRESS)
        logger.info("Starting evaluation run processing [evalRunId=${evalRun.id}, evalId=${evalRun.evalId}]")
        
        try {
            // Update status to running - do this outside the main try block to ensure
            // we don't fail silently if there's a database issue
            evalRunRepository.updateEvalRun(updatedEvalRun)
            logger.debug("Updated eval run status to IN_PROGRESS [evalRunId=${evalRun.id}]")

            // Load evaluation definition and find appropriate processor
            val (eval, dataSourceProcessor) = loadEvaluationResources(evalRun)
            
            // Load and process the data
            val (jsonLines, processingResult) = loadAndProcessData(dataSourceProcessor, evalRun)
            
            // Process the result based on its type
            updatedEvalRun = handleProcessingResult(processingResult, updatedEvalRun, eval, jsonLines)
        } catch (e: Exception) {
            logger.error("Error processing eval run [evalRunId=${evalRun.id}]: ${e.message}", e)

            // Handle errors
            updatedEvalRun =
                updatedEvalRun.copy(
                    status = EvalRunStatus.FAILED,
                    error =
                        EvalRunError(
                            code = "processing_error",
                            message = e.message ?: "Unknown error occurred during evaluation run processing",
                        ),
                )
        } finally {
            // Always update the run status in the database, even if processing fails
            try {
                evalRunRepository.updateEvalRun(updatedEvalRun)
                logger.info("Completed evaluation run [evalRunId=${evalRun.id}] with status ${updatedEvalRun.status}")
            } catch (e: Exception) {
                // If we can't even update the database, just log the error
                logger.error("Failed to update eval run status in database [evalRunId=${evalRun.id}]: ${e.message}", e)
            }
        }
    }

    /**
     * Load evaluation resources (eval definition and data source processor).
     *
     * @param evalRun The evaluation run
     * @return Pair of evaluation definition and data source processor
     */
    private suspend fun loadEvaluationResources(evalRun: EvalRun): Pair<Eval, DataSourceProcessor> {
        // Get the evaluation definition
        val eval =
            evalRepository.getEval(evalRun.evalId)
                ?: throw IllegalStateException("Eval with evalId: ${evalRun.evalId} not found")
        logger.debug("Loaded evaluation definition [evalId=${evalRun.evalId}, name=${eval.name}]")

        // Find the appropriate data source processor
        val dataSourceProcessor =
            dataSourceProcessors.find { it.canProcess(evalRun.dataSource) }
                ?: throw IllegalStateException("No processor found for data source type: ${evalRun.dataSource.source.javaClass.simpleName} [evalRunId=${evalRun.id}]")
        logger.debug("Found appropriate data source processor: ${dataSourceProcessor.javaClass.simpleName}")
        
        return Pair(eval, dataSourceProcessor)
    }

    /**
     * Load and process data from the data source.
     *
     * @param dataSourceProcessor The data source processor
     * @param evalRun The evaluation run
     * @return Pair of raw JSON lines and processing result
     */
    private suspend fun loadAndProcessData(
        dataSourceProcessor: DataSourceProcessor,
        evalRun: EvalRun,
    ): Pair<List<String>, DataSourceProcessingResult> {
        // Read the original data
        val jsonLines = loadDataLines(dataSourceProcessor, evalRun)
        
        // Process the data source
        val processingResult = processDataSource(dataSourceProcessor, evalRun)
        
        return Pair(jsonLines, processingResult)
    }

    /**
     * Load raw data lines from the data source.
     *
     * @param dataSourceProcessor The data source processor
     * @param evalRun The evaluation run
     * @return List of raw data lines
     */
    private suspend fun loadDataLines(
        dataSourceProcessor: DataSourceProcessor,
        evalRun: EvalRun,
    ): List<String> {
        try {
            val lines = dataSourceProcessor.getRawDataLines(evalRun.dataSource)
            logger.info("Read ${lines.size} data lines from source [evalRunId=${evalRun.id}]")
            return lines
        } catch (e: Exception) {
            logger.error("Error reading data lines [evalRunId=${evalRun.id}, dataSourceType=${evalRun.dataSource.source.javaClass.simpleName}]: ${e.message}", e)
            throw IllegalStateException("Failed to read data from source for evaluation run ${evalRun.id}: ${e.message}")
        }
    }

    /**
     * Process the data source to prepare for evaluation.
     *
     * @param dataSourceProcessor The data source processor
     * @param evalRun The evaluation run
     * @return Data source processing result
     */
    private suspend fun processDataSource(
        dataSourceProcessor: DataSourceProcessor,
        evalRun: EvalRun,
    ): DataSourceProcessingResult {
        try {
            val result = dataSourceProcessor.processDataSource(evalRun.dataSource)
            logger.info("Processed data source with result type: ${result.javaClass.simpleName} [evalRunId=${evalRun.id}]")
            return result
        } catch (e: Exception) {
            logger.error("Error processing data source [evalRunId=${evalRun.id}]: ${e.message}", e)
            throw IllegalStateException("Failed to process data source for evaluation run ${evalRun.id}: ${e.message}")
        }
    }

    /**
     * Handle the processing result based on its type.
     *
     * @param processingResult The processing result
     * @param evalRun The current evaluation run state
     * @param eval The evaluation definition
     * @param jsonLines The raw data lines
     * @return Updated evaluation run
     */
    private suspend fun handleProcessingResult(
        processingResult: DataSourceProcessingResult,
        evalRun: EvalRun,
        eval: Eval,
        jsonLines: List<String>,
    ): EvalRun {
        logger.debug("Handling processing result of type: ${processingResult.javaClass.simpleName} [evalRunId=${evalRun.id}]")
        
        return when (processingResult) {
            is CompletionMessagesResult -> {
                logger.info("Processing completion messages [evalRunId=${evalRun.id}, messageCount=${processingResult.messages.size}]")
                // Handle completion messages for evaluation
                processCompletionMessages(processingResult, evalRun, eval, jsonLines)
                    ?: evalRun.copy(
                        status = EvalRunStatus.FAILED,
                        error =
                            EvalRunError(
                                code = "processing_error",
                                message = "Failed to process completion messages for evaluation run ${evalRun.id}",
                            ),
                    )
            }
            
            is JsonlDataResult -> {
                // Handle JSONL data result (future implementation)
                logger.info("JSONL data processing not yet implemented [evalRunId=${evalRun.id}]")
                evalRun.copy(
                    status = EvalRunStatus.FAILED,
                    error =
                        EvalRunError(
                            code = "unsupported_operation",
                            message = "JSONL data processing not yet implemented for evaluation run ${evalRun.id}",
                        ),
                )
            }
            
            is EmptyProcessingResult -> {
                // Handle empty result
                logger.warn("Empty processing result [evalRunId=${evalRun.id}]: ${processingResult.reason}")
                evalRun.copy(
                    status = EvalRunStatus.FAILED,
                    error =
                        EvalRunError(
                            code = "processing_error",
                            message = "Empty processing result for evaluation run ${evalRun.id}: ${processingResult.reason}",
                        ),
                )
            }
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
        jsonLines: List<String>,
    ): EvalRun? {
        try {
            // Validate data source type
            if (evalRun.dataSource !is CompletionsRunDataSource) {
                logger.error("CompletionMessagesResult received but evalRun.dataSource is not CompletionsRunDataSource [evalRunId=${evalRun.id}]")
                return evalRun.copy(
                    status = EvalRunStatus.FAILED,
                    error =
                        EvalRunError(
                            code = "invalid_configuration",
                            message = "Invalid data source type for completion messages in evaluation run ${evalRun.id}",
                        ),
                )
            }

            logger.info("Generated ${completionResult.messages.size} completion messages [evalRunId=${evalRun.id}]")

            // Find appropriate generation service
            val generationService =
                generationServices.find { it.canGenerate(evalRun.dataSource) }
                    ?: throw IllegalStateException("No generation service found for data source type: ${evalRun.dataSource.javaClass.simpleName} [evalRunId=${evalRun.id}]")
            logger.debug("Using generation service: ${generationService.javaClass.simpleName} [evalRunId=${evalRun.id}]")

            // Generate completions with better error handling
            val resultMap =
                try {
                    logger.info("Starting completion generation [evalRunId=${evalRun.id}, modelName=${evalRun.dataSource.model}]")
                
                    val results =
                        generationService.generateCompletions(
                            completionResult.messages,
                            evalRun.dataSource,
                            evalRun.apiKey,
                            eval.dataSourceConfig as CustomDataSourceConfig,
                        )
                
                    logger.info("Completions generated [evalRunId=${evalRun.id}, count=${results.size}]")
                    results
                } catch (e: Exception) {
                    logger.error("Error generating completions [evalRunId=${evalRun.id}]: ${e.message}", e)
                    // Return failure instead of rethrowing
                    return evalRun.copy(
                        status = EvalRunStatus.FAILED,
                        error =
                            EvalRunError(
                                code = "generation_error",
                                message = "Error generating completions for evaluation run ${evalRun.id}: ${e.message ?: "Unknown error"}",
                            ),
                    )
                }

            // Evaluate testing criteria
            logger.info("Evaluating testing criteria [evalRunId=${evalRun.id}, criteriaCount=${eval.testingCriteria.size}]")
            
            val testingResults =
                evaluateTestingCriteria(
                    resultMap,
                    jsonLines,
                    eval.testingCriteria,
                    evalRun.id,
                )
            
            logger.info("Testing criteria evaluation completed [evalRunId=${evalRun.id}]")

            // Calculate result counts
            val resultCounts = resultProcessor.calculateResultCounts(testingResults)
            val perCriteriaResults = resultProcessor.calculatePerCriteriaResults(testingResults, eval.testingCriteria)
            logger.info("Results calculated [evalRunId=${evalRun.id}, passed=${resultCounts.passed}, failed=${resultCounts.failed}, errored=${resultCounts.errored}]")

            // Check if we have any results at all
            if (testingResults.isEmpty()) {
                logger.warn("No testing results were produced [evalRunId=${evalRun.id}]")
                return evalRun.copy(
                    status = EvalRunStatus.COMPLETED,
                    resultCounts = resultCounts,
                    perTestingCriteriaResults = perCriteriaResults,
                    error =
                        EvalRunError(
                            code = "no_results",
                            message = "Evaluation completed but no results were produced for evaluation run ${evalRun.id}",
                        ),
                )
            }

            // Return updated eval run
            return evalRun.copy(
                status = EvalRunStatus.COMPLETED,
                resultCounts = resultCounts,
                perTestingCriteriaResults = perCriteriaResults,
            )
        } catch (e: Exception) {
            logger.error("Error processing completion messages [evalRunId=${evalRun.id}]: ${e.message}", e)
            return evalRun.copy(
                status = EvalRunStatus.FAILED,
                error =
                    EvalRunError(
                        code = "processing_error",
                        message = "Error during completion message processing for evaluation run ${evalRun.id}: ${e.message ?: "Unknown error"}",
                    ),
            )
        }
    }

    /**
     * Evaluate testing criteria for each completion result.
     *
     * @param resultMap Map of completion results by index
     * @param jsonLines Original JSON lines from the file
     * @param testingCriteria List of testing criteria to evaluate
     * @param evalRunId The evaluation run ID for logging
     * @return Map of testing criteria results by index and criteria
     */
    private fun evaluateTestingCriteria(
        resultMap: Map<Int, CompletionResult>,
        jsonLines: List<String>,
        testingCriteria: List<TestingCriterion>,
        evalRunId: String,
    ): Map<Int, Map<String, CriterionEvaluator.CriterionResult>> {
        val results = mutableMapOf<Int, Map<String, CriterionEvaluator.CriterionResult>>()
        logger.debug("Starting evaluation of ${resultMap.size} completions against ${testingCriteria.size} criteria [evalRunId=$evalRunId]")

        resultMap.forEach { (index, completionResult) ->
            // Get the corresponding JSON line for this index
            val jsonLine = if (index < jsonLines.size) jsonLines[index] else null
            if (jsonLine == null) {
                logger.warn("No JSON data found for index $index [evalRunId=$evalRunId]")
                // Still record the failure instead of skipping
                results[index] =
                    testingCriteria.associate { criterion ->
                        criterion.name to
                            CriterionEvaluator.CriterionResult(
                                id = criterion.id,
                                passed = false,
                                message = "Error: Missing reference data for index $index",
                            )
                    }
                return@forEach
            }

            val criteriaResults = mutableMapOf<String, CriterionEvaluator.CriterionResult>()
            logger.debug("Evaluating completion at index $index against ${testingCriteria.size} criteria [evalRunId=$evalRunId]")

            // Evaluate each testing criterion with individual error handling
            testingCriteria.forEach { criterion ->
                try {
                    // Pass completion result as actual and original data as reference
                    val result =
                        criterionEvaluatorFactory.evaluate(
                            criterion, 
                            actualJson = completionResult.contentJson, 
                            referenceJson = jsonLine,
                        )
                    
                    criteriaResults[criterion.name] = result
                    logger.debug("Criterion '${criterion.name}' evaluated, result=${result.passed} [evalRunId=$evalRunId, index=$index]")
                } catch (e: Exception) {
                    // Record the error but continue with other criteria
                    logger.error("Error evaluating criterion '${criterion.name}' for index $index [evalRunId=$evalRunId]: ${e.message}", e)
                    criteriaResults[criterion.name] =
                        CriterionEvaluator.CriterionResult(
                            id = criterion.id,
                            passed = false,
                            message = "Error: ${e.message ?: "Unknown error during evaluation"}",
                        )
                }
            }

            results[index] = criteriaResults
        }

        // Log summary
        val totalItems = results.size
        val totalCriteria = testingCriteria.size
        val totalTests = totalItems * totalCriteria
        val passedTests = results.values.sumOf { criteriaMap -> criteriaMap.values.count { it.passed } }

        logger.info("Testing criteria evaluation: $passedTests/$totalTests tests passed across $totalItems items [evalRunId=$evalRunId]")

        return results
    }
} 
