package ai.masaic.improved

import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.findById
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class AgentRunRepository(private val reactiveMongoTemplate: ReactiveMongoTemplate) {

    private val logger = KotlinLogging.logger {}

    companion object {
        const val AGENT_RUNS_COLLECTION = "agent_runs"
        const val AGENT_RUNS_OUTCOME_COLLECTION = "agent_runs_outcome"
    }

    suspend fun loadCheckpoint(runId: String): AgentContext? {
        return reactiveMongoTemplate.findById<AgentContext>(runId, AGENT_RUNS_COLLECTION).awaitSingleOrNull()
    }

    suspend fun saveCheckpoint(context: AgentContext) {
        val updatedContext = context.copy(updatedAt = java.time.Instant.now(), stateName = context.state.javaClass.simpleName)

        reactiveMongoTemplate.save(updatedContext, AGENT_RUNS_COLLECTION).awaitFirst()
    }

    suspend fun saveAgentRunOutcome(agentRunOutcome: AgentRunOutcome) {
        reactiveMongoTemplate.save(agentRunOutcome, AGENT_RUNS_OUTCOME_COLLECTION).awaitFirst()
    }

    suspend fun getAgentRunOutcomeByRunId(runId: String): AgentRunOutcome {
        val query = Query(Criteria.where("runId").`is`(runId))
        val result = reactiveMongoTemplate
            .findOne(query, AgentRunOutcome::class.java, AGENT_RUNS_OUTCOME_COLLECTION)
            .awaitSingleOrNull()

        return result
            ?: throw NoSuchElementException("No AgentRunOutcome found for runId: $runId")
    }

    suspend fun listRuns(
        limit: Int = 20,
        after: String? = null
    ): List<AgentContext> {
        try {
            val query = Query()
            
            // Add pagination filtering
            if (after != null) {
                val afterRun = loadCheckpoint(after)
                if (afterRun != null) {
                    query.addCriteria(Criteria.where("createdAt").lt(afterRun.createdAt))
                }
            }
            
            // Sort by createdAt in descending order
            query.with(Sort.by(Sort.Direction.DESC, "createdAt"))
            
            // Set limit
            query.limit(limit)
            
            return reactiveMongoTemplate.find(query, AgentContext::class.java, AGENT_RUNS_COLLECTION)
                .collectList()
                .awaitFirst()
        } catch (e: Exception) {
            logger.error(e) { "Error listing agent runs" }
            return emptyList()
        }
    }

}
