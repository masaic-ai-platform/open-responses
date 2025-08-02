package ai.masaic.platform.api.tools

import ai.masaic.openresponses.tool.NativeToolDefinition
import ai.masaic.openresponses.tool.ToolParamsAccessor
import ai.masaic.openresponses.tool.UnifiedToolContext
import ai.masaic.openresponses.tool.mcp.nativeToolDefinition
import ai.masaic.platform.api.repository.MocksRepository
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.client.OpenAIClient
import mu.KotlinLogging
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.http.codec.ServerSentEvent
import java.time.Instant

class MockSaveTool(
    private val mocksRepository: MocksRepository,
) : PlatformNativeTool(PlatformToolsNames.MOCK_SAVE_TOOL) {
    private val logger = KotlinLogging.logger { }

    override fun provideToolDef(): NativeToolDefinition =
        nativeToolDefinition {
            name(toolName)
            description("Saves mock requests and responses for a function in the database.")
            parameters {
                property(
                    name = "mockFunctionId",
                    type = "string",
                    description = "Unique ID of the function definition saved in the database.",
                    required = true,
                )
                property(
                    name = "mockReqResDefinitions",
                    type = "string",
                    description = "JSON string of the mock definitions to save into the database.",
                    required = true,
                )
                // default is false; include for clarity if you like
                additionalProperties = false
            }
        }

    override suspend fun executeTool(
        resolvedName: String,
        arguments: String,
        paramsAccessor: ToolParamsAccessor,
        client: OpenAIClient,
        eventEmitter: (ServerSentEvent<String>) -> Unit,
        toolMetadata: Map<String, Any>,
        context: UnifiedToolContext,
    ): String {
        val request: SaveMocksRequest = mapper.readValue(arguments)

        logger.info { "mocks to save: ${request.mockReqResDefinitions}" }

        // Fetch existing mocks if present
        val existing = mocksRepository.findById(request.mockFunctionId)

        val combinedMocks: Mocks =
            if (existing != null) {
                val mergedJsons = existing.mockJsons + request.mockReqResDefinitions
                existing.copy(mockJsons = mergedJsons)
            } else {
                Mocks(refFunctionId = request.mockFunctionId, mockJsons = listOf(request.mockReqResDefinitions))
            }

        // Persist (upsert)
        mocksRepository.upsert(combinedMocks)
        return "Mocks saved against reference function: ${combinedMocks.refFunctionId}"
    }
}

data class SaveMocksRequest(
    val mockFunctionId: String,
    val mockReqResDefinitions: String,
)

@Document(collection = "mocks")
data class Mocks(
    @Id val refFunctionId: String,
    val mockJsons: List<String>,
    val createdAt: Instant = Instant.now(),
)
