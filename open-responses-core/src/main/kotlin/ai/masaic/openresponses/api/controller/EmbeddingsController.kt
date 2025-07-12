package ai.masaic.openresponses.api.controller

import ai.masaic.openresponses.api.model.CreateEmbeddingRequest
import ai.masaic.openresponses.api.model.EmbeddingData
import ai.masaic.openresponses.api.model.EmbeddingResponse
import ai.masaic.openresponses.api.model.EmbeddingUsage
import ai.masaic.openresponses.api.service.embedding.OpenAIProxyEmbeddingService
import ai.masaic.openresponses.api.support.service.TelemetryService
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.nio.ByteBuffer
import java.util.*

/**
 * Controller for the OpenAI-compatible Embeddings API.
 *
 * This controller provides endpoints for generating embeddings from text
 * following the OpenAI API specification.
 */
@RestController
@RequestMapping("/v1")
@CrossOrigin("*")
class EmbeddingsController(
    private val embeddingService: OpenAIProxyEmbeddingService,
    private val encoding: Encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE),
    private val telemetryService: TelemetryService,
) {
    private val log = LoggerFactory.getLogger(EmbeddingsController::class.java)

    @PostMapping("/embeddings")
    suspend fun createEmbedding(
        @RequestBody request: CreateEmbeddingRequest,
        @RequestHeader("Authorization") authHeader: String,
    ): ResponseEntity<EmbeddingResponse> {
        // Start observation for embeddings operation with OpenTelemetry
        val observation = telemetryService.startObservation("gen_ai.embeddings", modelName = request.model)
        
        try {
            // Set required OpenTelemetry attributes for GenAI spans
            observation.lowCardinalityKeyValue("gen_ai.operation.name", "embeddings") // Required attribute
            
            // Extract API key from Authorization header (format: "Bearer API_KEY")
            val apiKey = authHeader.removePrefix("Bearer ").trim()
            if (apiKey.isBlank()) {
                observation.lowCardinalityKeyValue("error.type", "authentication_error")
                observation.error(ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key"))
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key")
            }

            // Validate encoding format
            if (request.encodingFormat !in listOf("float", "base64")) {
                observation.lowCardinalityKeyValue("error.type", "invalid_encoding_format")
                observation.error(IllegalArgumentException("encoding_format must be either 'float' or 'base64'"))
                throw IllegalArgumentException("encoding_format must be either 'float' or 'base64'")
            }

            // Record encoding formats as an attribute
            observation.lowCardinalityKeyValue("gen_ai.request.encoding_formats", request.encodingFormat)
            
            // Process input based on type
            val inputTexts =
                when (request.input) {
                    is String -> listOf(request.input)
                    is List<*> -> request.input.map { it.toString() }
                    else -> throw IllegalArgumentException("Input must be a string or an array of strings")
                }

            // Parse provider and model from the request.model (format: "provider@model")
            val (provider, _) = parseProviderAndModel(request.model)
            
            // Set model and server address attributes
            observation.lowCardinalityKeyValue("gen_ai.request.model", request.model)
            provider?.let {
                val baseUrl = embeddingService.providers[it] ?: it
                observation.lowCardinalityKeyValue("server.address", baseUrl)
            }

            // Calculate embeddings
            val embeddings = embeddingService.embedTexts(inputTexts, apiKey, request.model)

            // Create response with appropriate encoding format
            val embeddingDataList =
                embeddings.mapIndexed { index, embedding ->
                    val encodedEmbedding =
                        when (request.encodingFormat) {
                            "base64" -> encodeToBase64(embedding)
                            else -> embedding // "float" is the default
                        }
                    
                    EmbeddingData(
                        embedding = encodedEmbedding,
                        index = index,
                    )
                }

            // Calculate token usage and set as attribute
            val tokenEstimate = encoding.countTokens(request.input.toString())
            observation.lowCardinalityKeyValue("gen_ai.usage.input_tokens", tokenEstimate.toString())
            
            val response =
                EmbeddingResponse(
                    data = embeddingDataList,
                    model = request.model,
                    usage =
                        EmbeddingUsage(
                            promptTokens = tokenEstimate,
                            totalTokens = tokenEstimate,
                        ),
                )

            return ResponseEntity.ok(response)
        } catch (e: IllegalArgumentException) {
            observation.error(e)
            observation.lowCardinalityKeyValue("error.type", e.javaClass.simpleName)
            log.error("Error processing embedding request: ${e.message}")
            throw e
        } catch (e: Exception) {
            observation.error(e)
            observation.lowCardinalityKeyValue("error.type", e.javaClass.simpleName)
            log.error("Error generating embeddings", e)
            throw e
        } finally {
            observation.stop()
        }
    }

    /**
     * Encodes a list of floats to a base64 string.
     * OpenAI's base64 format represents vectors as 32-bit floating point numbers in binary, then base64 encodes the result.
     */
    private fun encodeToBase64(embedding: List<Float>): String {
        val byteBuffer = ByteBuffer.allocate(4 * embedding.size)
        embedding.forEach { byteBuffer.putFloat(it) }
        return Base64.getEncoder().encodeToString(byteBuffer.array())
    }

    /**
     * Parse provider and model from the model name string.
     * The format is expected to be "provider@model".
     */
    private fun parseProviderAndModel(modelString: String): Pair<String?, String> =
        if (modelString == "default") {
            Pair(null, "default")
        } else if (!modelString.contains("@")) {
            Pair(null, modelString)
        } else {
            val parts = modelString.split("@", limit = 2)
            Pair(parts[0], parts[1])
        }
}
