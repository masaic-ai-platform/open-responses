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
) : NativeToolRegistry(objectMapper, responseStore) {
    private val log = KotlinLogging.logger {}

    init {
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

        log.debug { "toolResponse: $toolResponse" }
        return toolResponse
    }
}
