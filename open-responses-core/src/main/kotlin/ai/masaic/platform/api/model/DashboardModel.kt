package ai.masaic.platform.api.model

import ai.masaic.openresponses.api.model.ModelInfo
import kotlinx.serialization.Serializable

@Serializable
data class ModelProvider(
    val name: String,
    val description: String,
    val supportedModels: Set<ProvidedModel>,
)

@Serializable
data class ProvidedModel(
    val name: String,
    val modelSyntax: String,
    val isEmbeddingModel: Boolean = false,
)

data class SchemaGenerationRequest(
    val description: String,
    val modelInfo: ModelInfo?,
)

data class SchemaGenerationResponse(
    val generatedSchema: String,
)

data class FunctionGenerationRequest(
    val description: String,
    val modelInfo: ModelInfo? = null,
)

data class FunctionGenerationResponse(
    val generatedFunction: String,
)

data class PromptGenerationRequest(
    val description: String,
    val existingPrompt: String = "",
    val modelInfo: ModelInfo?,
)

data class PromptGenerationResponse(
    val generatedPrompt: String,
)

data class McpListToolsRequest(
    val serverLabel: String,
    val serverUrl: String,
    val headers: Map<String, String> = emptyMap(),
)
