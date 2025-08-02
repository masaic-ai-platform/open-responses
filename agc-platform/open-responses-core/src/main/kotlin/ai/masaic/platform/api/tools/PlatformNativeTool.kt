package ai.masaic.platform.api.tools

import ai.masaic.openresponses.tool.NativeToolDefinition
import ai.masaic.openresponses.tool.ToolParamsAccessor
import ai.masaic.openresponses.tool.UnifiedToolContext
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openai.client.OpenAIClient
import org.springframework.http.codec.ServerSentEvent

abstract class PlatformNativeTool(
    protected val toolName: String,
) {
    protected val mapper = jacksonObjectMapper()

    fun toolName() = toolName

    abstract fun provideToolDef(): NativeToolDefinition

    abstract suspend fun executeTool(
        resolvedName: String,
        arguments: String,
        paramsAccessor: ToolParamsAccessor,
        client: OpenAIClient,
        eventEmitter: (ServerSentEvent<String>) -> Unit,
        toolMetadata: Map<String, Any>,
        context: UnifiedToolContext,
    ): String?
}

object PlatformToolsNames {
    const val FUN_DEF_GEN_TOOL = "fun_def_generation_tool"
    const val FUN_REQ_GATH_TOOL = "fun_req_gathering_tool"
    const val MOCK_FUN_SAVE_TOOL = "mock_fun_save_tool"
    const val MOCK_GEN_TOOL = "mock_generation_tool"
    const val MOCK_SAVE_TOOL = "mock_save_tool"
    const val MODEL_TEST_TOOL = "get_weather_by_city"
}
