package ai.masaic.platform.api.controller

import ai.masaic.platform.api.repository.MockFunctionRepository
import ai.masaic.platform.api.tools.*
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Profile("platform")
@RestController
@RequestMapping("/v1/dashboard")
@CrossOrigin("*")
class McpMocksController(
    private val platformMcpService: PlatformMcpService,
    private val mockFunctionRepository: MockFunctionRepository,
) {
    @PostMapping("/mcp/mock/servers", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun createMockServer(
        @RequestBody request: CreateMockMcpServerRequest,
    ): ResponseEntity<MockMcpServerResponse> {
        val mockServer = platformMcpService.createMockServer(request)
        return ResponseEntity.ok(mockServer)
    }

    @GetMapping("/mcp/mock/servers", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getMockServers(): ResponseEntity<List<MockMcpServerResponse>> {
        val mockServers = platformMcpService.getAllMockServers()
        return ResponseEntity.ok(mockServers)
    }

    @GetMapping("/mcp/mock/functions", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getMockFunctions(): ResponseEntity<List<FunctionBodyResponse>> {
        val mockDefinitions = mockFunctionRepository.findAll()
        val functions = mockDefinitions.map { FunctionBodyResponse.from(it) }
        return ResponseEntity.ok(functions)
    }

    @GetMapping("/mcp/mock/functions/{functionId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getFunction(
        @PathVariable functionId: String,
    ): ResponseEntity<GetFunctionResponse> {
        val functionResponse = platformMcpService.getFunction(functionId)
        return ResponseEntity.ok(functionResponse)
    }
}

data class GetFunctionResponse(
    val functionDefinition: FunctionBodyResponse,
    val mocks: Mocks? = null,
)

data class FunctionBodyResponse(
    val id: String,
    val name: String,
    val description: String,
    val parameters: MutableMap<String, Any> = mutableMapOf(),
    val strict: Boolean = true,
) {
    companion object {
        fun from(def: MockFunctionDefinition): FunctionBodyResponse {
            val functionBody = def.functionBody
            return FunctionBodyResponse(
                def.id,
                functionBody.name,
                functionBody.description,
                functionBody.parameters,
                functionBody.strict,
            )
        }
    }
}
