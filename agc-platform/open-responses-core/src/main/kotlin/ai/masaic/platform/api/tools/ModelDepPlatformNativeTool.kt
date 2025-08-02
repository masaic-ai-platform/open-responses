package ai.masaic.platform.api.tools

import ai.masaic.openresponses.api.model.CreateCompletionRequest
import ai.masaic.openresponses.tool.ChatCompletionParamsAdapter
import ai.masaic.openresponses.tool.ResponseParamsAdapter
import ai.masaic.openresponses.tool.ToolParamsAccessor
import ai.masaic.platform.api.config.ModelSettings
import ai.masaic.platform.api.config.SystemSettingsType
import ai.masaic.platform.api.service.ModelService
import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import org.springframework.context.annotation.Lazy

abstract class ModelDepPlatformNativeTool(
    private val tooName: String,
    @Lazy private val modelService: ModelService,
    private val modelSettings: ModelSettings,
) : PlatformNativeTool(tooName) {
    suspend fun callModel(
        paramsAccessor: ToolParamsAccessor,
        openAIClient: OpenAIClient,
        messages: List<Map<String, String>>,
    ): String {
        val finalSystemSettings = resolveSystemSettings(paramsAccessor, modelSettings)
        if (modelSettings.settingsType == SystemSettingsType.RUNTIME) {
            return call(paramsAccessor, openAIClient, messages)
        }
        return callModel(finalSystemSettings, messages)
    }

    suspend fun callModel(
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

        val response: String = modelService.fetchCompletionPayload(createCompletionRequest, modelSettings.apiKey)
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

    private fun resolveSystemSettings(
        paramsAccessor: ToolParamsAccessor,
        defaultSettings: ModelSettings,
    ): ModelSettings {
        return if (defaultSettings.settingsType == SystemSettingsType.DEPLOYMENT_TIME) {
            defaultSettings
        } else {
            if (paramsAccessor is ResponseParamsAdapter) {
                return ModelSettings(paramsAccessor.getModel(), paramsAccessor.params._headers().values("Authorization")[0])
            }

            val completionParamsAccessor = (paramsAccessor as ChatCompletionParamsAdapter)
            return ModelSettings(completionParamsAccessor.getModel(), completionParamsAccessor.params._headers().values("Authorization")[0])
        }
    }
}
