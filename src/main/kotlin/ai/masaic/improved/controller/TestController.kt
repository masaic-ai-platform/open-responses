package ai.masaic.improved.controller

import ai.masaic.improved.SamplingAgent
import ai.masaic.improved.model.Conversation
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/test")
@CrossOrigin(origins = ["*"], allowedHeaders = ["*"])
class TestController(private val samplingAgent: SamplingAgent) {

    @PostMapping("/mongo/query")
    suspend fun executeQuery(@RequestBody request: Map<String, Any>) : List<Conversation> {
        return samplingAgent.fetchUnclassifiedConversations(request["limit"] as Int, request["queryMap"] as Map<String, Any>)
    }
}
