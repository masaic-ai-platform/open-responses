package ai.masaic.openevals.api.service.runner

import ai.masaic.openevals.api.model.*
import ai.masaic.openevals.api.repository.EvalRepository
import ai.masaic.openevals.api.repository.EvalRunRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EvalRunnerTest {
    private lateinit var evalRunRepository: EvalRunRepository
    private lateinit var evalRepository: EvalRepository
    private lateinit var dataSourceProcessor: DataSourceProcessor
    private lateinit var generationService: GenerationService
    private lateinit var criterionEvaluatorFactory: CriterionEvaluatorFactory
    private lateinit var resultProcessor: ResultProcessor
    private lateinit var evalRunner: EvalRunner

    @BeforeEach
    fun setup() {
        // Create and configure mocks
        evalRunRepository = mockk(relaxed = true)
        evalRepository = mockk()
        dataSourceProcessor = mockk()
        generationService = mockk()
        criterionEvaluatorFactory = mockk()
        resultProcessor = mockk()
        
        // Create the service with mocked dependencies
        evalRunner =
            EvalRunner(
                evalRunRepository,
                evalRepository,
                listOf(dataSourceProcessor),
                listOf(generationService),
                criterionEvaluatorFactory,
                resultProcessor,
            )
    }

    @Test
    fun `processEvalRun should update status to IN_PROGRESS when started`() =
        runTest {
            // Arrange
            val evalRun = createSampleEvalRun()
            val expectedUpdatedRun = evalRun.copy(status = EvalRunStatus.IN_PROGRESS)
        
            // Setup mocks to throw exception to test only the status update
            coEvery { evalRunRepository.updateEvalRun(any()) } answers { firstArg() }
            coEvery { evalRepository.getEval(any()) } throws Exception("Test exception")
        
            // Act
            evalRunner.processEvalRun(evalRun)
        
            // Assert
            coVerify(exactly = 1) {
                evalRunRepository.updateEvalRun(
                    match { 
                        it.id == evalRun.id && it.status == EvalRunStatus.IN_PROGRESS 
                    },
                )
            }
        }

    @Test
    fun `processEvalRun should handle missing evaluation and set FAILED status`() =
        runTest {
            // Arrange
            val evalRun = createSampleEvalRun()
        
            coEvery { evalRunRepository.updateEvalRun(any()) } answers { firstArg() }
            coEvery { evalRepository.getEval(evalRun.evalId) } returns null
        
            // Act
            evalRunner.processEvalRun(evalRun)
        
            // Assert - should try to update with FAILED status
            coVerify { 
                evalRunRepository.updateEvalRun(
                    match { 
                        it.id == evalRun.id && 
                            it.status == EvalRunStatus.FAILED && 
                            it.error?.code == "processing_error" 
                    },
                )
            }
        }

    @Test
    fun `processEvalRun should handle missing data source processor and set FAILED status`() =
        runTest {
            // Arrange
            val evalRun = createSampleEvalRun()
            val eval = createSampleEval(evalRun.evalId)
        
            // Existing eval but no matching processor
            coEvery { evalRunRepository.updateEvalRun(any()) } answers { firstArg() }
            coEvery { evalRepository.getEval(evalRun.evalId) } returns eval
            coEvery { dataSourceProcessor.canProcess(any()) } returns false
        
            // Act
            evalRunner.processEvalRun(evalRun)
        
            // Assert - should try to update with FAILED status
            coVerify { 
                evalRunRepository.updateEvalRun(
                    match { 
                        it.id == evalRun.id && 
                            it.status == EvalRunStatus.FAILED && 
                            it.error?.code == "processing_error" 
                    },
                )
            }
        }

    @Test
    fun `processEvalRun should handle error in loadDataLines and set FAILED status`() =
        runTest {
            // Arrange
            val evalRun = createSampleEvalRun()
            val eval = createSampleEval(evalRun.evalId)
        
            coEvery { evalRunRepository.updateEvalRun(any()) } answers { firstArg() }
            coEvery { evalRepository.getEval(evalRun.evalId) } returns eval
            coEvery { dataSourceProcessor.canProcess(any()) } returns true
            coEvery { dataSourceProcessor.getRawDataLines(any()) } throws Exception("Error reading data")
        
            // Act
            evalRunner.processEvalRun(evalRun)
        
            // Assert - should try to update with FAILED status
            coVerify { 
                evalRunRepository.updateEvalRun(
                    match { 
                        it.id == evalRun.id && 
                            it.status == EvalRunStatus.FAILED && 
                            it.error?.code == "processing_error" 
                    },
                )
            }
        }

    @Test
    fun `processEvalRun should handle error in processDataSource and set FAILED status`() =
        runTest {
            // Arrange
            val evalRun = createSampleEvalRun()
            val eval = createSampleEval(evalRun.evalId)
        
            coEvery { evalRunRepository.updateEvalRun(any()) } answers { firstArg() }
            coEvery { evalRepository.getEval(evalRun.evalId) } returns eval
            coEvery { dataSourceProcessor.canProcess(any()) } returns true
            coEvery { dataSourceProcessor.getRawDataLines(any()) } returns listOf("{\"test\":\"data\"}")
            coEvery { dataSourceProcessor.processDataSource(any()) } throws Exception("Error processing data")
        
            // Act
            evalRunner.processEvalRun(evalRun)
        
            // Assert - should try to update with FAILED status
            coVerify { 
                evalRunRepository.updateEvalRun(
                    match { 
                        it.id == evalRun.id && 
                            it.status == EvalRunStatus.FAILED && 
                            it.error?.code == "processing_error" 
                    },
                )
            }
        }

    @Test
    fun `processEvalRun should handle EmptyProcessingResult and set FAILED status`() =
        runTest {
            // Arrange
            val evalRun = createSampleEvalRun()
            val eval = createSampleEval(evalRun.evalId)
            val emptyResult = EmptyProcessingResult("No data available")
        
            coEvery { evalRunRepository.updateEvalRun(any()) } answers { firstArg() }
            coEvery { evalRepository.getEval(evalRun.evalId) } returns eval
            coEvery { dataSourceProcessor.canProcess(any()) } returns true
            coEvery { dataSourceProcessor.getRawDataLines(any()) } returns listOf("{\"test\":\"data\"}")
            coEvery { dataSourceProcessor.processDataSource(any()) } returns emptyResult
        
            // Act
            evalRunner.processEvalRun(evalRun)
        
            // Assert - should try to update with FAILED status with specific error
            coVerify { 
                evalRunRepository.updateEvalRun(
                    match { 
                        it.id == evalRun.id && 
                            it.status == EvalRunStatus.FAILED && 
                            it.error?.code == "processing_error" &&
                            it.error?.message?.contains("No data available") == true
                    },
                )
            }
        }

    @Test
    fun `processEvalRun should handle JsonlDataResult and set FAILED status as not implemented`() =
        runTest {
            // Arrange
            val evalRun = createSampleEvalRun()
            val eval = createSampleEval(evalRun.evalId)
            val jsonlResult = JsonlDataResult(emptyMap()) // Empty JSONL result
        
            coEvery { evalRunRepository.updateEvalRun(any()) } answers { firstArg() }
            coEvery { evalRepository.getEval(evalRun.evalId) } returns eval
            coEvery { dataSourceProcessor.canProcess(any()) } returns true
            coEvery { dataSourceProcessor.getRawDataLines(any()) } returns listOf("{\"test\":\"data\"}")
            coEvery { dataSourceProcessor.processDataSource(any()) } returns jsonlResult
        
            // Act
            evalRunner.processEvalRun(evalRun)
        
            // Assert - should try to update with FAILED status with "unsupported_operation" code
            coVerify { 
                evalRunRepository.updateEvalRun(
                    match { 
                        it.id == evalRun.id && 
                            it.status == EvalRunStatus.FAILED && 
                            it.error?.code == "unsupported_operation"
                    },
                )
            }
        }

    @Test
    fun `processEvalRun should handle CompletionMessagesResult with invalid data source type`() =
        runTest {
            // Arrange
            // Create a custom RunDataSource that is not a CompletionsRunDataSource
            val customDataSource =
                object : RunDataSource {
                    override val source: DataSource = FileDataSource("test.jsonl")
                }
        
            val evalRun = createSampleEvalRun(dataSource = customDataSource)
            val eval = createSampleEval(evalRun.evalId)
            val completionMessages = listOf(ChatMessage("user", "text"))
            val completionResult = CompletionMessagesResult(mapOf(0 to completionMessages))
        
            coEvery { evalRunRepository.updateEvalRun(any()) } answers { firstArg() }
            coEvery { evalRepository.getEval(evalRun.evalId) } returns eval
            coEvery { dataSourceProcessor.canProcess(any()) } returns true
            coEvery { dataSourceProcessor.getRawDataLines(any()) } returns listOf("{\"test\":\"data\"}")
            coEvery { dataSourceProcessor.processDataSource(any()) } returns completionResult
        
            // Act
            evalRunner.processEvalRun(evalRun)
        
            // Assert - should try to update with FAILED status with "invalid_configuration" code
            coVerify { 
                evalRunRepository.updateEvalRun(
                    match { 
                        it.id == evalRun.id && 
                            it.status == EvalRunStatus.FAILED && 
                            it.error?.code == "invalid_configuration"
                    },
                )
            }
        }

    @Test
    fun `processEvalRun should handle successful completion message processing`() =
        runTest {
            // Arrange
            val evalRun = createSampleEvalRun()
            val eval = createSampleEval(evalRun.evalId)
            val completionMessages = listOf(ChatMessage("user", "text"))
            val completionResult = CompletionMessagesResult(mapOf(0 to completionMessages))
        
            val jsonLines = listOf("{\"test\":\"data\"}")
            val generationResults = mapOf(0 to CompletionResult("response", "{\"response\":\"value\"}"))
            val criterionResult = CriterionEvaluator.CriterionResult(id = "test-1", passed = true, message = "Test passed")
            val testingResults = mapOf(0 to mapOf("criterion1" to criterionResult))
            val resultCounts = ResultCounts(passed = 1, failed = 0, errored = 0)
            val perCriteriaResults = listOf(TestingCriteriaResult(testingCriteria = "criterion1", passed = 1, failed = 0))
        
            // Setup mocks
            coEvery { evalRunRepository.updateEvalRun(any()) } answers { firstArg() }
            coEvery { evalRepository.getEval(evalRun.evalId) } returns eval
            coEvery { dataSourceProcessor.canProcess(any()) } returns true
            coEvery { dataSourceProcessor.getRawDataLines(any()) } returns jsonLines
            coEvery { dataSourceProcessor.processDataSource(any()) } returns completionResult
            coEvery { generationService.canGenerate(any()) } returns true
            coEvery { 
                generationService.generateCompletions(
                    any(), 
                    any(), 
                    any(), 
                    any(),
                ) 
            } returns generationResults
            every { 
                criterionEvaluatorFactory.evaluate(
                    any(),
                    any(),
                    any(),
                ) 
            } returns criterionResult
            every { resultProcessor.calculateResultCounts(any()) } returns resultCounts
            every { resultProcessor.calculatePerCriteriaResults(any(), any()) } returns perCriteriaResults
        
            // Act
            evalRunner.processEvalRun(evalRun)
        
            // Assert - should update with COMPLETED status and results
            coVerify { 
                evalRunRepository.updateEvalRun(
                    match { 
                        it.id == evalRun.id && 
                            it.status == EvalRunStatus.COMPLETED &&
                            it.resultCounts == resultCounts &&
                            it.perTestingCriteriaResults == perCriteriaResults
                    },
                )
            }
        }

    @Test
    fun `processEvalRun should handle generation service errors`() =
        runTest {
            // Arrange
            val evalRun = createSampleEvalRun()
            val eval = createSampleEval(evalRun.evalId)
            val completionMessages = listOf(ChatMessage("user", "text"))
            val completionResult = CompletionMessagesResult(mapOf(0 to completionMessages))
        
            // Setup mocks
            coEvery { evalRunRepository.updateEvalRun(any()) } answers { firstArg() }
            coEvery { evalRepository.getEval(evalRun.evalId) } returns eval
            coEvery { dataSourceProcessor.canProcess(any()) } returns true
            coEvery { dataSourceProcessor.getRawDataLines(any()) } returns listOf("{\"test\":\"data\"}")
            coEvery { dataSourceProcessor.processDataSource(any()) } returns completionResult
            coEvery { generationService.canGenerate(any()) } returns true
            coEvery { 
                generationService.generateCompletions(
                    any(), 
                    any(), 
                    any(), 
                    any(),
                ) 
            } throws Exception("API error")
        
            // Act
            evalRunner.processEvalRun(evalRun)
        
            // Assert - should try to update with FAILED status with "generation_error" code
            coVerify { 
                evalRunRepository.updateEvalRun(
                    match { 
                        it.id == evalRun.id && 
                            it.status == EvalRunStatus.FAILED && 
                            it.error?.code == "generation_error" &&
                            it.error?.message?.contains("API error") == true
                    },
                )
            }
        }

    @Test
    fun `processEvalRun should handle empty testing results`() =
        runTest {
            // Arrange
            val evalRun = createSampleEvalRun()
            val eval = createSampleEval(evalRun.evalId)
            val completionMessages = listOf(ChatMessage("user", "text"))
            val completionResult = CompletionMessagesResult(mapOf(0 to completionMessages))
        
            val jsonLines = listOf("{\"test\":\"data\"}")
            val generationResults = mapOf<Int, CompletionResult>() // Empty results
            val resultCounts = ResultCounts(passed = 0, failed = 0, errored = 0)
            val perCriteriaResults = listOf(TestingCriteriaResult(testingCriteria = "test1"))
        
            // Setup mocks
            coEvery { evalRunRepository.updateEvalRun(any()) } answers { firstArg() }
            coEvery { evalRepository.getEval(evalRun.evalId) } returns eval
            coEvery { dataSourceProcessor.canProcess(any()) } returns true
            coEvery { dataSourceProcessor.getRawDataLines(any()) } returns jsonLines
            coEvery { dataSourceProcessor.processDataSource(any()) } returns completionResult
            coEvery { generationService.canGenerate(any()) } returns true
            coEvery { 
                generationService.generateCompletions(
                    any(), 
                    any(), 
                    any(), 
                    any(),
                ) 
            } returns generationResults
            every { resultProcessor.calculateResultCounts(any()) } returns resultCounts
            every { resultProcessor.calculatePerCriteriaResults(any(), any()) } returns perCriteriaResults
        
            // Act
            evalRunner.processEvalRun(evalRun)
        
            // Assert - should still complete but with "no_results" error code
            coVerify { 
                evalRunRepository.updateEvalRun(
                    match { 
                        it.id == evalRun.id && 
                            it.status == EvalRunStatus.COMPLETED &&
                            it.resultCounts == resultCounts &&
                            it.perTestingCriteriaResults == perCriteriaResults &&
                            it.error?.code == "no_results"
                    },
                )
            }
        }

    @Test
    fun `processEvalRun should handle missing JSON line for an index during criteria evaluation`() =
        runTest {
            // Arrange
            val evalRun = createSampleEvalRun()
            val eval = createSampleEval(evalRun.evalId)
            val completionMessages = listOf(ChatMessage("user", "text"))
            val completionResult = CompletionMessagesResult(mapOf(0 to completionMessages))
        
            // Empty JSON lines but we have an index in the generation results
            val jsonLines = emptyList<String>()
            val generationResults = mapOf(0 to CompletionResult("response", "{\"response\":\"value\"}"))
            val criterionResult = CriterionEvaluator.CriterionResult(id = "test-1", passed = false, message = "Error: Missing reference data for index 0")
            val testingResults = mapOf(0 to mapOf("criterion1" to criterionResult))
            val resultCounts = ResultCounts(passed = 0, failed = 1, errored = 0)
            val perCriteriaResults = listOf(TestingCriteriaResult(testingCriteria = "criterion1", passed = 0, failed = 1))
        
            // Setup mocks
            coEvery { evalRunRepository.updateEvalRun(any()) } answers { firstArg() }
            coEvery { evalRepository.getEval(evalRun.evalId) } returns eval
            coEvery { dataSourceProcessor.canProcess(any()) } returns true
            coEvery { dataSourceProcessor.getRawDataLines(any()) } returns jsonLines
            coEvery { dataSourceProcessor.processDataSource(any()) } returns completionResult
            coEvery { generationService.canGenerate(any()) } returns true
            coEvery { 
                generationService.generateCompletions(
                    any(), 
                    any(), 
                    any(), 
                    any(),
                ) 
            } returns generationResults
        
            // Since we're testing missing JSON lines, the evaluateTestingCriteria should handle this internally
            // and produce a failing result for the criterion
            every { resultProcessor.calculateResultCounts(any()) } returns resultCounts
            every { resultProcessor.calculatePerCriteriaResults(any(), any()) } returns perCriteriaResults
        
            // Act
            evalRunner.processEvalRun(evalRun)
        
            // Assert - should complete with results
            coVerify { 
                evalRunRepository.updateEvalRun(
                    match { 
                        it.id == evalRun.id && 
                            it.status == EvalRunStatus.COMPLETED &&
                            it.resultCounts == resultCounts &&
                            it.perTestingCriteriaResults == perCriteriaResults
                    },
                )
            }
        }

    @Test
    fun `processEvalRun should handle exception in final update and log error`() =
        runTest {
            // Arrange
            val evalRun = createSampleEvalRun()
            val eval = createSampleEval(evalRun.evalId)

            // Setup to succeed until the final update
            var updateCallCount = 0
            coEvery { evalRunRepository.updateEvalRun(any()) } answers {
                updateCallCount++
                val evalRunArg = firstArg<EvalRun>()

                // On second call, throw an exception
                if (updateCallCount > 1) {
                    throw Exception("Database error")
                }

                // Return the input on first call
                evalRunArg
            }

            coEvery { evalRepository.getEval(evalRun.evalId) } returns eval
            coEvery { dataSourceProcessor.canProcess(any()) } returns true
            coEvery { dataSourceProcessor.getRawDataLines(any()) } returns listOf("{\"test\":\"data\"}")
            coEvery { dataSourceProcessor.processDataSource(any()) } throws Exception("Test error")

            // Act - no exception should bubble up even though final update fails
            evalRunner.processEvalRun(evalRun)

            // Assert - should try to update twice (once at start, once at end)
            coVerify(exactly = 2) { evalRunRepository.updateEvalRun(any()) }
        }

    // Helper method to create a sample EvalRun for testing
    private fun createSampleEvalRun(
        id: String = "run-123",
        evalId: String = "eval-123",
        status: EvalRunStatus = EvalRunStatus.QUEUED,
        dataSource: RunDataSource =
            CompletionsRunDataSource(
                source = FileDataSource("test.jsonl"),
                model = "gpt-4",
                inputMessages =
                    TemplateInputMessages(
                        template = listOf(ChatMessage("user", "Test prompt")),
                    ),
                samplingParams =
                    SamplingParams(
                        temperature = 0.7,
                        maxCompletionTokens = 100, // Changed from maxTokens
                    ),
            ),
    ): EvalRun {
        // Extract model from data source if it's a CompletionsRunDataSource
        val model = (dataSource as? CompletionsRunDataSource)?.model

        return EvalRun(
            id = id,
            evalId = evalId,
            name = "Test Run", // Added missing parameter
            status = status,
            dataSource = dataSource,
            model = model, // Added missing parameter
            apiKey = "test-api-key",
        )
    }

    // Helper method to create a sample Eval for testing
    private fun createSampleEval(
        id: String = "eval-123",
        name: String = "Test Eval",
    ): Eval {
        val criterion =
            StringCheckGrader(
                name = "criterion1",
                input = "{{input}}",
                reference = "expected",
                operation = StringCheckGrader.Operation.EQUAL,
            )
        
        return Eval(
            id = id,
            name = name,
            dataSourceConfig =
                CustomDataSourceConfig(
                    schema = emptyMap(),
                ),
            testingCriteria = listOf(criterion),
        )
    }
} 
