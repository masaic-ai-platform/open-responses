package ai.masaic.openresponses.api.validation

import ai.masaic.openresponses.api.client.ResponseStore
import ai.masaic.openresponses.api.exception.VectorStoreNotFoundException
import ai.masaic.openresponses.api.model.*
import ai.masaic.openresponses.api.service.search.VectorStoreService
import org.springframework.stereotype.Component

/**
 * Validator for completion and response requests.
 */
@Component
class RequestValidator(
    private val vectorStoreService: VectorStoreService,
    private val responseStore: ResponseStore,
) {
    private val modelPattern = Regex(".+@.+")
    private val imageApiKey = System.getenv("OPEN_RESPONSES_IMAGE_GENERATION_API_KEY")
    private val imageBaseUrl = System.getenv("OPEN_RESPONSES_IMAGE_GENERATION_BASE_URL")

    fun validateCompletionRequest(request: CreateCompletionRequest) {
        if (!modelPattern.matches(request.model)) {
            throw IllegalArgumentException("model must be in provider@model format")
        }
        if (request.messages.isEmpty()) {
            throw IllegalArgumentException("messages field is required")
        }
        if (request.metadata != null && !request.store) {
            throw IllegalArgumentException("metadata is only allowed when store=true")
        }
    }

    suspend fun validateResponseRequest(request: CreateResponseRequest) {
        if (!modelPattern.matches(request.model)) {
            throw IllegalArgumentException("model must be in provider@model format")
        }

        val input = request.input
        if (input is String && input.isBlank()) {
            throw IllegalArgumentException("input field is required")
        }

        if (input is List<*> && input.isEmpty()) {
            throw IllegalArgumentException("input field is required")
        }

        if (request.metadata != null && !request.store) {
            throw IllegalArgumentException("metadata is only allowed when store=true")
        }
        if (request.previousResponseId != null) {
            responseStore.getResponse(request.previousResponseId)
                ?: throw IllegalArgumentException("previous_response_id not found")
        }
        request.tools?.forEach { tool ->
            when (tool) {
                is ImageGenerationTool -> {
                    if (imageBaseUrl.isNullOrBlank()) {
                        if (!modelPattern.matches(tool.model)) {
                            throw IllegalArgumentException("Image model must be in provider@model format")
                        }
                    }
                    if (imageApiKey.isNullOrBlank() && tool.modelProviderKey.isNullOrBlank()) {
                        throw IllegalArgumentException("model_provider_key is required for image generation")
                    }
                }
                is FileSearchTool -> {
                    tool.vectorStoreIds?.forEach { id ->
                        ensureVectorStoreExists(id)
                    }
                }
                is AgenticSeachTool -> {
                    tool.vectorStoreIds?.forEach { id ->
                        ensureVectorStoreExists(id)
                    }
                }
            }
        }
    }

    private suspend fun ensureVectorStoreExists(id: String) {
        try {
            vectorStoreService.getVectorStore(id)
        } catch (e: VectorStoreNotFoundException) {
            throw IllegalArgumentException("Vector store not found: $id")
        }
    }
}
