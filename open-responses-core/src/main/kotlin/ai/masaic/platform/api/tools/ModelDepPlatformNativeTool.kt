package ai.masaic.platform.api.tools

import ai.masaic.openresponses.api.model.CreateCompletionRequest
import ai.masaic.platform.api.model.SystemSettings
import ai.masaic.platform.api.service.ModelService
import org.springframework.context.annotation.Lazy

abstract class ModelDepPlatformNativeTool(
    protected val tooName: String,
    @Lazy private val modelService: ModelService,
    private val systemSettings: SystemSettings,
) : PlatformNativeTool(tooName) {
    suspend fun callModel(messages: List<Map<String, String>>): String {
        val createCompletionRequest =
            CreateCompletionRequest(
                messages = messages,
                model = systemSettings.model,
                stream = false,
                store = false,
            )

        val response: String = modelService.fetchCompletionPayload(createCompletionRequest, systemSettings.modelApiKey)
        return response
    }
}
