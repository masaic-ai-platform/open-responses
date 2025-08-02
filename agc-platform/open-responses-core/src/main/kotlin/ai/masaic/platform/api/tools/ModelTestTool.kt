package ai.masaic.platform.api.tools

import ai.masaic.openresponses.tool.NativeToolDefinition
import ai.masaic.openresponses.tool.ToolParamsAccessor
import ai.masaic.openresponses.tool.UnifiedToolContext
import ai.masaic.openresponses.tool.mcp.nativeToolDefinition
import com.openai.client.OpenAIClient
import kotlinx.coroutines.delay
import org.springframework.http.codec.ServerSentEvent

class ModelTestTool : PlatformNativeTool(PlatformToolsNames.MODEL_TEST_TOOL) {
    override fun provideToolDef(): NativeToolDefinition =
        nativeToolDefinition {
            name(toolName)
            description("Retrieves weather information based on a city name.")
            parameters {
                property(
                    name = "city_name",
                    type = "string",
                    description = "Name of the city to get weather for",
                    required = true,
                )
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
        val jsonTree = mapper.readTree(arguments)
        val cityNameNode = jsonTree["city_name"]
        if (cityNameNode.isNull) return "Parameter city_name is not received. It is mandatory"
        val cityName = cityNameNode.asText()
        delay(3 * 1000)
        return when (cityName.lowercase()) {
            "new delhi" -> newDelhiMockResponse
            else -> newYorkMockResponse
        }
    }

    private val newDelhiMockResponse =
        """
{
  "temperature": "32",
  "unit": "Celsius",
  "feelsLike": "hot and humid"
}
        """.trimIndent()

    private val newYorkMockResponse =
        """
{
  "temperature": "24",
  "unit": "Celsius",
  "feelsLike": "pleasant with light breeze"
}
        """.trimIndent()
}
