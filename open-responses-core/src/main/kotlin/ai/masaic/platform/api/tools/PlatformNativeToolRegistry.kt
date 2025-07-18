package ai.masaic.platform.api.tools

import ai.masaic.openresponses.api.client.ResponseStore
import ai.masaic.openresponses.tool.NativeToolRegistry
import ai.masaic.openresponses.tool.ToolParamsAccessor
import ai.masaic.openresponses.tool.UnifiedToolContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import mu.KotlinLogging
import org.springframework.http.codec.ServerSentEvent

class PlatformNativeToolRegistry(
    objectMapper: ObjectMapper,
    responseStore: ResponseStore,
    private val platformNativeTools: List<PlatformNativeTool>,
//    private val funReqGatheringTool: FunReqGatheringTool,
//    private val funDefGenerationTool: FunDefGenerationTool,
//    private val mockFunSaveTool: MockFunSaveTool,
//    private val mockGenerationTool: MockGenerationTool,
//    private val mockSaveTool: MockSaveTool,
) : NativeToolRegistry(objectMapper, responseStore) {
    private val log = KotlinLogging.logger {}

    init {
//        toolRepository[FunReqGatheringTool.TOOL_NAME] = funReqGatheringTool.loadTool()
//        toolRepository[FunDefGenerationTool.TOOL_NAME] = funDefGenerationTool.loadTool()
//        toolRepository[MockFunSaveTool.TOOL_NAME] = mockFunSaveTool.loadTool()
//        toolRepository[MockGenerationTool.TOOL_NAME] = mockGenerationTool.loadTool()
//        toolRepository[MockSaveTool.TOOL_NAME] = mockSaveTool.loadTool()

        platformNativeTools.forEach {
            toolRepository[it.toolName()] = it.provideToolDef()
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
    ): String? {
        toolRepository[resolvedName] ?: return null
        val originalName = toolMetadata["originalName"] as? String ?: resolvedName
        log.debug("Executing native tool '$originalName' (resolved to '$resolvedName') with arguments: $arguments")

        val platformNativeTool = platformNativeTools.find { it.toolName() == resolvedName }

        val toolResponse =
            platformNativeTool?.executeTool(
                resolvedName,
                arguments,
                paramsAccessor,
                client,
                eventEmitter,
                toolMetadata,
                context,
            ) ?: super.executeTool(resolvedName, arguments, paramsAccessor, client, eventEmitter, toolMetadata, context)
//        }

//        val toolResponse =
//            when (resolvedName) {
//                FunReqGatheringTool.TOOL_NAME ->
//                    funReqGatheringTool.executeTool(
//                        resolvedName,
//                        arguments,
//                        paramsAccessor,
//                        client,
//                        eventEmitter,
//                        toolMetadata,
//                        context,
//                    )
//
//                FunDefGenerationTool.TOOL_NAME ->
//                    funDefGenerationTool.executeTool(
//                        resolvedName,
//                        arguments,
//                        paramsAccessor,
//                        client,
//                        eventEmitter,
//                        toolMetadata,
//                        context,
//                    )
//
//                MockFunSaveTool.TOOL_NAME ->
//                    mockFunSaveTool.executeTool(
//                        resolvedName,
//                        arguments,
//                        paramsAccessor,
//                        client,
//                        eventEmitter,
//                        toolMetadata,
//                        context,
//                    )
//
//                MockGenerationTool.TOOL_NAME ->
//                    mockGenerationTool.executeTool(
//                        resolvedName,
//                        arguments,
//                        paramsAccessor,
//                        client,
//                        eventEmitter,
//                        toolMetadata,
//                        context,
//                    )
//
//                MockSaveTool.TOOL_NAME ->
//                    mockSaveTool.executeTool(
//                        resolvedName,
//                        arguments,
//                        paramsAccessor,
//                        client,
//                        eventEmitter,
//                        toolMetadata,
//                        context,
//                    )
//                else -> {
//                    super.executeTool(resolvedName, arguments, paramsAccessor, client, eventEmitter, toolMetadata, context)
//                }
//            }

        log.debug { "toolResponse: $toolResponse" }
        return toolResponse
    }
}
