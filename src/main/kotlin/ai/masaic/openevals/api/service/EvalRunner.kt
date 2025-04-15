package ai.masaic.openevals.api.service

import ai.masaic.openevals.api.model.EvalRun
import ai.masaic.openevals.api.model.EvalRunStatus
import ai.masaic.openevals.api.model.EvalRunError
import ai.masaic.openevals.api.repository.EvalRunRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Component responsible for processing evaluation runs.
 * This is a stub implementation that can be expanded later.
 */
@Component
class EvalRunner(
    private val evalRunRepository: EvalRunRepository
) {
    private val logger = LoggerFactory.getLogger(EvalRunner::class.java)
    
    /**
     * Process an evaluation run.
     * This is a stub implementation that just updates the status to COMPLETED.
     * 
     * @param evalRun The evaluation run to process
     */
    suspend fun processEvalRun(evalRun: EvalRun) {
        logger.info("Processing evaluation run with ID: ${evalRun.id}")
        
        try {
            // Update to running status
            val runningEvalRun = evalRun.copy(status = EvalRunStatus.RUNNING)
            evalRunRepository.updateEvalRun(runningEvalRun)
            
            // In a real implementation, this would execute the evaluation
            
            // Update to completed status
            val completedEvalRun = runningEvalRun.copy(status = EvalRunStatus.COMPLETED)
            evalRunRepository.updateEvalRun(completedEvalRun)
            
            logger.info("Completed processing evaluation run with ID: ${evalRun.id}")
        } catch (e: Exception) {
            logger.error("Error processing evaluation run with ID: ${evalRun.id}", e)
            
            // Update to errored status
            val erroredEvalRun = evalRun.copy(
                status = EvalRunStatus.ERRORED,
                error = EvalRunError(
                    code = "processing_error",
                    message = e.message ?: "Unknown error"
                )
            )
            evalRunRepository.updateEvalRun(erroredEvalRun)
        }
    }
} 