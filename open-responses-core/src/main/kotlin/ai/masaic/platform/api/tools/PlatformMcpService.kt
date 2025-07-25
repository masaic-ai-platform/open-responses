package ai.masaic.platform.api.tools

import ai.masaic.openresponses.api.model.CreateCompletionRequest
import ai.masaic.openresponses.tool.ToolDefinition
import ai.masaic.openresponses.tool.ToolParamsAccessor
import ai.masaic.openresponses.tool.mcp.*
import ai.masaic.platform.api.config.ModelSettings
import ai.masaic.platform.api.config.SystemSettingsType
import ai.masaic.platform.api.controller.FunctionBodyResponse
import ai.masaic.platform.api.controller.GetFunctionResponse
import ai.masaic.platform.api.repository.McpMockServerRepository
import ai.masaic.platform.api.repository.MockFunctionRepository
import ai.masaic.platform.api.repository.MocksRepository
import ai.masaic.platform.api.service.ModelService
import ai.masaic.platform.api.service.messages
import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.data.annotation.Id
import java.net.URI
import java.time.Instant

class PlatformMcpService(
    private val mcpMockServerRepository: McpMockServerRepository,
    private val mockFunRepository: MockFunctionRepository,
    private val mocksRepository: MocksRepository,
) {
    fun createMockServer(request: CreateMockMcpServerRequest): MockMcpServerResponse {
        // 1. create random Id of 9 chars long.
        val id =
            java.util.UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 9)

        // 2. frame url like https://{id}.mock.masaic.ai/api/mcp
        val url = "https://$id.mock.masaic.ai/api/mcp"
        val serverInfo = MCPServerInfo(id = id, url = url)

        // 3. persist McpMockServer in DB
        var mockServer = McpMockServer(id = id, url = url, serverLabel = request.serverLabel, toolIds = request.toolIds)
        mockServer = mcpMockServerRepository.upsert(mockServer)
        return MockMcpServerResponse(id = mockServer.id, url = url, serverLabel = mockServer.serverLabel)
    }

    fun getAllMockServers(): List<MockMcpServerResponse> {
        val servers = mcpMockServerRepository.findAll()
        val mockServers =
            servers.map { mockServer ->
                MockMcpServerResponse(id = mockServer.id, url = mockServer.url, serverLabel = mockServer.serverLabel)
            }
        return mockServers
    }

    fun getFunction(functionId: String): GetFunctionResponse {
        val functionDefinition = mockFunRepository.findById(functionId) ?: throw IllegalStateException("Function $functionId not found")
        val mocks = mocksRepository.findById(functionId) ?: throw IllegalStateException("Mocks for function $functionId not found")
        return GetFunctionResponse(FunctionBodyResponse.from(functionDefinition), mocks)
    }
}

class PlatformMcpClientFactory(
    private val mockServerRepository: McpMockServerRepository,
    private val mockFunRepository: MockFunctionRepository,
    private val mocksRepository: MocksRepository,
    private val modelSettings: ModelSettings,
    private val modelService: ModelService,
) : SimpleMcpClientFactory() {
    override fun init(
        serverName: String,
        url: String,
        headers: Map<String, String>,
    ): McpClient {
        val uri = URI(url)
        if (uri.host.endsWith("mock.masaic.ai")) {
            return MockMcpClient(mockServerRepository, mockFunRepository, mocksRepository, modelSettings, modelService)
        }
        return SimpleMcpClient().init(serverName, url, headers)
    }
}

class MockMcpClient(
    private val mockServerRepository: McpMockServerRepository,
    private val mockFunRepository: MockFunctionRepository,
    private val mocksRepository: MocksRepository,
    private val modelSettings: ModelSettings,
    private val modelService: ModelService,
) : McpClient {
    private val log = KotlinLogging.logger { }

    override fun listTools(mcpServerInfo: MCPServerInfo): List<McpToolDefinition> {
        val mockServerId = URI(mcpServerInfo.url).host.split(".")[0]
        // find server in DB. If not found then throw IllegalStateException that server not found.
        val server = mockServerRepository.findById(mockServerId) ?: throw IllegalStateException("Mock MCP server with id $mockServerId not found")
        val tools =
            server.toolIds.map {
                val tool = mockFunRepository.findById(it) ?: throw IllegalStateException("tool $it not found")
                tool.toMcpToolDefinition(mcpServerInfo)
            }
        // return the MCPToolDefinition
        return tools
    }

    override fun executeTool(
        tool: ToolDefinition,
        arguments: String,
        paramsAccessor: ToolParamsAccessor?,
        openAIClient: OpenAIClient?,
        headers: Map<String, String>,
    ): String {
        log.debug("Executing native tool ${tool.name} with arguments: $arguments")
        val functionDefinition = mockFunRepository.findById(tool.id) ?: return "No function definition with id=${tool.id} is present."
        val mocks = mocksRepository.findById(tool.id) ?: return "No mocks available for tool: ${tool.name}. Unable to return any mock response"

        val mockSelectionPrompt =
            """
ROLE  
You are a mock-response selector agent.  
Your job is to take an incoming request and pick the single best matching mock response from a list of available mocks.

INPUT (provided to you)  
• `functionDefinition` – the JSON object with name, description, and parameters.  
• `availableMocks` – an array of objects, each with:  
  - `request` (sample JSON matching the schema)  
  - `response` (the mock response JSON)  
  - `selection_rule` (plain-text description of when to use this mock)  
• `incomingRequest` – the JSON object you must match against the mocks.

BEHAVIOR  
1. Validate that `incomingRequest` conforms at least to the function’s schema.  
2. For each mock in `availableMocks`, determine if its `request` pattern and/or `selection_rule` apply to `incomingRequest`.  
   - A mock “matches” if every key in the mock’s `request` is present in `incomingRequest` with an equal value (or falls within any constraints described in its `selection_rule`).  
3. If exactly one mock matches, output **only** its `response` JSON.  
4. If multiple mocks match, choose the one whose `request` has the most fields in common with `incomingRequest` (i.e. the most specific) and output its `response`.  
5. If no mocks match, output first mock response.

OUTPUT FORMAT  
- **Successful match:** the raw JSON object from `response`.  
- **No match:** the single plain-text clarification question.  
- Do not include any other text, metadata, or markdown.  

FUNCTION DEFINITION
${functionDefinition.functionBody}

AVAILABLE MOCKS
${mocks.mockJsons}

INCOMING REQUEST
$arguments
            """.trimIndent()

        val messages =
            messages {
                systemMessage(mockSelectionPrompt)
                userMessage("Select mock responses.")
            }

        val toolResponse =
            if (modelSettings.settingsType == SystemSettingsType.RUNTIME) {
                if (paramsAccessor == null || openAIClient == null) throw IllegalArgumentException("can't execute tool without model configured at deployment time")
                return call(paramsAccessor, openAIClient, messages)
            } else {
                callModel(modelSettings, messages)
            }
        log.debug { "toolResponse: $toolResponse" }
        return toolResponse
    }

    override fun close() {
        // Nothing to do here....
    }

    private fun callModel(
        modelSettings: ModelSettings,
        messages: List<Map<String, String>>,
    ): String {
        val createCompletionRequest =
            CreateCompletionRequest(
                messages = messages,
                model = modelSettings.qualifiedModelName,
                stream = false,
                store = false,
            )

        val response: String = runBlocking { modelService.fetchCompletionPayload(createCompletionRequest, modelSettings.apiKey) }
        return response
    }

    private fun call(
        paramsAccessor: ToolParamsAccessor,
        openAIClient: OpenAIClient,
        messages: List<Map<String, String>>,
    ): String {
        val completionMessages =
            messages.map {
                val role = it["role"]
                val content = it["content"] ?: throw IllegalStateException("content cannot be empty")
                when (role) {
                    "system" -> {
                        ChatCompletionMessageParam.ofSystem(
                            ChatCompletionSystemMessageParam.builder().content(content).build(),
                        )
                    }

                    "user" -> {
                        ChatCompletionMessageParam.ofUser(
                            ChatCompletionUserMessageParam.builder().content(content).build(),
                        )
                    }
                    else -> throw IllegalStateException("role for message can be only system or user cannot be empty")
                }
            }

        val chatCompletionRequestBuilder =
            ChatCompletionCreateParams
                .builder()
                .messages(completionMessages)
                .model(paramsAccessor.getModel())
                .temperature(paramsAccessor.getDefaultTemperature())

        val response = openAIClient.chat().completions().create(chatCompletionRequestBuilder.build())
        return response
            .choices()
            .firstOrNull()
            ?.message()
            ?.content()
            ?.get() ?: "TERMINATE"
    }
}

data class CreateMockMcpServerRequest(
    val serverLabel: String,
    val toolIds: List<String>,
)

data class MockMcpServerResponse(
    val id: String,
    val url: String,
    val serverLabel: String,
)

data class McpMockServer(
    @Id val id: String,
    val url: String,
    val serverLabel: String,
    val toolIds: List<String>,
    val createdAt: Instant = Instant.now(),
)
