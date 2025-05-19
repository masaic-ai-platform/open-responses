package ai.masaic.improved.controller

import ai.masaic.improved.*
import ai.masaic.improved.repository.ConversationRepository
import ai.masaic.improved.repository.GenericLabel
import ai.masaic.improved.repository.MongoConversationRepository
import ai.masaic.openresponses.api.model.EmbeddingData
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@CrossOrigin(origins = ["*"], allowedHeaders = ["*"])
@RestController
@RequestMapping("/v1")
class DomainLabellingController(
    private val domainLabelSuggester: DomainLabelSuggester,
    private val labelApprovalService: LabelApprovalService,
    private val conversationRepository: ConversationRepository,
    private val ruleRepository: RuleRepository
) {

    @PostMapping("/conversations/{conversationId}/embed", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun embed(@PathVariable("conversationId") conversationId: String, @RequestHeader("Authorization") authHeader: String): List<EmbeddingData> {
        return domainLabelSuggester.createEmbeddings(
            listOf(
                conversationRepository.getConversation(conversationId)
                    ?: throw IllegalStateException("conversation not found with id: $conversationId")
            ), authHeader
        )
    }

    @GetMapping("/buckets/labels/generic")
    suspend fun getGenericLabels(): List<GenericLabel> {
        return (conversationRepository as MongoConversationRepository).getGenericLabels("generic")
    }

    @GetMapping("/buckets/labels/domain")
    suspend fun getDomainLabels(): List<Rule> {
        return ruleRepository.getDistinctRules()
    }

    @PostMapping("/buckets/labels/suggest", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun suggest(@RequestBody request: SuggestLabelsRequest, @RequestHeader("Authorization") authHeader: String): Map<Int, SuggestedLabelResponse> {
        return domainLabelSuggester.suggest(request.copy(apiKey = authHeader))
    }

    @PostMapping("/labels/accept", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun acceptLabel(@RequestBody request: AcceptLabelRequest, @RequestHeader("Authorization") authHeader: String): ResponseEntity<HttpStatusCode>{
        labelApprovalService.acceptAndApplyLabels(request.copy(apiKey = authHeader))
        return ResponseEntity.ok().build()
    }

    @PostMapping("/labels/reject", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun rejectLabel(request: RejectLabelRequest): ResponseEntity<HttpStatusCode>{
        TODO("To be implemented soon")
    }
}
