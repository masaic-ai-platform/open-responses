package ai.masaic.improved.controller

// import ai.masaic.improved.ConvClassificationAgent
import ai.masaic.improved.repository.MongoConversationRepository
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/test")
@CrossOrigin(origins = ["*"], allowedHeaders = ["*"])
class TestController(
    private val mongoConversationRepository: MongoConversationRepository,
) {
    //    @PostMapping("/mongo/query")
//    suspend fun executeQuery(@RequestBody request: List<String>) {
//        request.forEach {
//            val conversation = mongoConversationRepository.getConversation(it)
//            mongoConversationRepository.createConversation(conversation?.copy(classification = null) ?: throw IllegalStateException("Conversation not found."))
//        }
//    }

    @PostMapping("/remove/classifications")
    suspend fun executeQuery(
        @RequestBody request: List<String>,
    ) {
        request.forEach {
            val conversation = mongoConversationRepository.getConversation(it)
            mongoConversationRepository.createConversation(conversation?.copy(classification = null) ?: throw IllegalStateException("Conversation not found."))
        }
    }
}
