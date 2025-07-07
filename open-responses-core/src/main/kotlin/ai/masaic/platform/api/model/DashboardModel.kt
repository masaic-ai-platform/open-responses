package ai.masaic.platform.api.model

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
)

data class SchemaGenerationRequest(
    val description: String,
)

data class SchemaGenerationResponse(
    val generatedSchema: String,
)

data class FunctionGenerationRequest(
    val description: String,
)

data class FunctionGenerationResponse(
    val generatedFunction: String,
)

data class SystemSettings(
    val modelApiKey: String,
    val model: String,
)

data class McpListToolsRequest(
    val serverLabel: String,
    val serverUrl: String,
    val headers: Map<String, String> = emptyMap(),
)
