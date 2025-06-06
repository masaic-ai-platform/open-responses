package ai.masaic.improved

import ai.masaic.improved.model.Conversation
import ai.masaic.improved.repository.ConversationRepository
import ai.masaic.openresponses.api.controller.CompletionController
import ai.masaic.openresponses.api.controller.EmbeddingsController
import ai.masaic.openresponses.api.model.CreateCompletionRequest
import ai.masaic.openresponses.api.model.CreateEmbeddingRequest
import ai.masaic.openresponses.api.model.EmbeddingData
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.core.JsonValue
import com.openai.models.chat.completions.ChatCompletion
import io.qdrant.client.QdrantClient
import org.apache.commons.math3.ml.clustering.DoublePoint
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer
import org.apache.commons.math3.ml.distance.EuclideanDistance
import org.apache.commons.math3.random.RandomGeneratorFactory
import org.apache.commons.math3.util.FastMath.min
import org.apache.commons.math3.util.FastMath.sqrt
import org.springframework.stereotype.Component
import org.springframework.util.MultiValueMap
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

@Component
class DomainLabelSuggester(
    private val completionController: CompletionController,
    private val embeddingsController: EmbeddingsController,
    private val qdrantClient: QdrantClient,
    private val conversationRepository: ConversationRepository,
    private val ruleRepository: RuleRepository,
) {
    //    suspend fun suggest(bucketPath: String, apiKey: String): List<SuggestedLabel> {
    suspend fun suggest(request: SuggestLabelsRequest): Map<Int, SuggestedLabelResponse> {
        val sample = conversationRepository.getConversations(request.bucketPath, 50)
        val embeddings = createEmbeddings(sample, request.apiKey)
        val k = min(10, sqrt(sample.size.toDouble() / 2).toInt().coerceAtLeast(2))
        val vectors = floatVectors(embeddings)
        val clusters = cluster(sample, vectors, k)
        val existingLabels = ruleRepository.getDistinctRules().map { it.label }.toMutableSet()
        val suggestedLabelsPrompts = buildPrompt("ecommerce", clusters, sample, existingLabels)
        return generateSuggestedLabels(request, suggestedLabelsPrompts, clusters, existingLabels)
    }

    private suspend fun generateSuggestedLabels(
        request: SuggestLabelsRequest,
        systemPrompts: List<String>,
        clusters: List<Cluster>,
        existingLabels: Set<String>,
    ): Map<Int, SuggestedLabelResponse> {
        val labelsMap = mutableMapOf<Int, SuggestedLabelResponse>()
        val labelsFromThisSession = mutableSetOf<String>()
        systemPrompts.forEachIndexed { index, prompt ->
            var updatedSystemPrompt = prompt
            if (labelsFromThisSession.isNotEmpty()) {
                updatedSystemPrompt =
                    prompt.replace(
                        "{{labelsFromThisSession}}",
                        labelsFromThisSession.joinToString(separator = "\n") { it },
                    )
            }

            val responseFormat =
                mapOf(
                    "type" to "json_schema",
                    "json_schema" to
                        mapOf(
                            "name" to "suggestedLabelsSchema",
                            "schema" to jacksonObjectMapper().readValue<Map<String, JsonValue>>(suggestedLabelSchema),
                        ),
                )
            val createCompletionRequest =
                CreateCompletionRequest(
                    messages = listOf(
                        mapOf("role" to "system", "content" to "You are a domain-expert assistant for labeling and categorizing customer conversations."),
                        mapOf("role" to "user", "content" to updatedSystemPrompt)
                    ),
                    model = request.model,
                    response_format = responseFormat,
                    stream = false,
                    store = false,
                )

            val response =
                completionController.createCompletion(
                    createCompletionRequest,
                    MultiValueMap.fromMultiValue(
                        mapOf("Authorization" to listOf(request.apiKey)),
                    ),
                    MultiValueMap.fromMultiValue(
                        mapOf("Authorization" to listOf(request.apiKey)),
                    ),
                )

            // Deserialize response into List<Message>
            val objectMapper =
                jacksonObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

            val suggestedLabelsResponse =
                objectMapper.readValue<SuggestedLabelResponse>(
                    (response.body as ChatCompletion)
                        .choices()[0]
                        .message()
                        .content()
                        .get(),
                )

            val exampleConversations =
                suggestedLabelsResponse.exampleIds.map { id ->
                    conversationRepository.getConversation(id)?.summary ?: ""
                }

            val suggestedLabels =
                suggestedLabelsResponse.suggestedLabels.map {
                    var suggestedLabel = it
                    if (it.path in existingLabels) {
                        suggestedLabel = it.copy(existing = true)
                    }
                    suggestedLabel
                }

            val updatedSuggestedLabelsResponse = suggestedLabelsResponse.copy(suggestedLabels = suggestedLabels, exampleIds = exampleConversations, cluster = clusters[index])
            labelsFromThisSession.addAll(updatedSuggestedLabelsResponse.suggestedLabels.map { it.path }.toSet())
            labelsMap[index] = updatedSuggestedLabelsResponse
        }
        return labelsMap
    }

    private fun buildPrompt(
        domainOfLabeller: String,
        clusters: List<Cluster>,
        samples: List<Conversation>,
        existingLabels: Set<String>,
    ): List<String> {
        val labelsFromThisSession = ""
        val clusterWisePrompt = mutableListOf<String>()
        clusters.forEachIndexed { idx, cluster ->
            val samplesFromCluster = StringBuilder()
            samplesFromCluster.append("Cluster #${idx + 1} (size ${cluster.indices.size}):\n")
            cluster.indices
                .take(10)
                .forEach { i -> samplesFromCluster.append("- \"${samples[i].summary}    (id: ${samples[i].id})\"\n") }

            val systemPrompt =
                """           
You are a $domainOfLabeller domain-expert assistant.
TASK:
For the cluster below, return **up to THREE** label candidates.  
• **If the cluster clearly fits an EXISTING label, list that path first.**  
• Otherwise fill the first slot with a NEW label in the form domain/<domain-name>/<failure>.  
• After the first slot, you may add 1–2 alternative NEW labels if they capture *different* failure facets of the same cluster.  
• Do NOT output more than three items.

CONSTRAINTS
• Each path ≤3 segments, lowercase, words separated by '_'  
• Do not duplicate any path in EXISTING or NEW-THIS-SESSION lists.
• A good label:
    • always starts with 'domain/'.
    • domain-name could be specific to business line or department.
    • captures the reason for customer inquiry or problem faced.

EXISTING domain labels: 
${existingLabels.joinToString(separator = "\n") { it }}

NEW labels already proposed in this session:
{{labelsFromThisSession}}

$samplesFromCluster
                """.trimIndent()

            clusterWisePrompt.add(systemPrompt)
        }
        return clusterWisePrompt
    }

    fun floatVectors(embeddings: List<EmbeddingData>): List<FloatArray> =
        embeddings
            .sortedBy { it.index }
            .map { ed ->
                // safe‐cast the embedding field
                val rawList =
                    ed.embedding as? List<*>
                        ?: error("Expected embedding to be a List, but was ${ed.embedding::class}")

                // convert each element to Float
                rawList
                    .map { elem ->
                        when (elem) {
                            is Number -> elem.toFloat()
                            else -> error("Expected Number in embedding list, but got $elem")
                        }
                    }.toFloatArray()
            }

// now `floatVectors` is exactly a List<FloatArray>

    suspend fun cluster(
        samples: List<Conversation>,
        vectors: List<FloatArray>,
        k: Int,
    ): List<Cluster> {
        val defaultK = 10
        val kUsed = if (k > 0) k else defaultK

        /* ------------------------------------------------------------------ *
         * 1) Convert each FloatArray -> DoublePoint and remember its position *
         * ------------------------------------------------------------------ */
        val points = ArrayList<DoublePoint>(vectors.size)
        val pointToInputIndex = HashMap<DoublePoint, Int>(vectors.size)

        vectors.forEachIndexed { idx, vec ->
            // widen each Float to Double without boxing
            val doubleVec = DoubleArray(vec.size) { vec[it].toDouble() }
            val point = DoublePoint(doubleVec)
            points += point
            pointToInputIndex[point] = idx // original index in `vectors`
        }

        /* ------------------------------------------------------------------ *
         * 2) Run K‑Means++                                                   *
         * ------------------------------------------------------------------ */
        val commonsClusters =
            KMeansPlusPlusClusterer<DoublePoint>(
                kUsed,
                1000, // max iterations
                EuclideanDistance(),
                RandomGeneratorFactory.createRandomGenerator(Random(42)),
            ).cluster(points) // Collection<…Cluster<DoublePoint>>

        /* ------------------------------------------------------------------ *
         * 3) Adapt commons‑math clusters -> your Cluster(id, indices)        *
         * ------------------------------------------------------------------ */
        return commonsClusters.mapIndexed { cid, commonsCluster ->
            val memberIndices =
                commonsCluster.points.map { pt ->
                    pointToInputIndex.getValue(pt) // O(1) lookup
                }
            val conversationsIds = memberIndices.map { samples[it].id }
            Cluster(cid, memberIndices, conversationsIds)
        }
    }

    suspend fun createEmbeddings(
        conversations: List<Conversation>,
        apiKey: String,
    ): List<EmbeddingData> {
        val request =
            CreateEmbeddingRequest(
                input = conversations.map { it.summary }.toList(),
                model = "default",
            )
        val response = embeddingsController.createEmbedding(request, apiKey)
        return (response.body?.data as List<EmbeddingData>)
    }
}

const val suggestedLabelSchema = """
    {
        "type": "object",
        "properties": {
          "suggestedLabels": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Hierarchical path for the label"
                },
                "definition": {
                  "type": "string",
                  "maxLength": 120,
                  "description": "What the failure means (≤120 characters)"
                },
                "confidence": {
                  "type": "string",
                  "enum": [
                    "HIGH",
                    "MEDIUM",
                    "LOW"
                  ],
                  "description": "Confidence level for the label"
                },
                "reason": {
                  "type": "string",
                  "maxLength": 200,
                  "description": "Why this label fits this cluster (≤200 characters)"
                }
              },
              "required": [
                "path",
                "definition",
                "confidence",
                "reason"
              ],
              "additionalProperties": false
            }
          }
        },
        "exampleIds": {
          "type": "array",
          "items": {
            "type": "string"
          },
          "minItems": 3,
          "maxItems": 3,
          "description": "Exactly three example conversation IDs illustrating this label"
        },
        "required": [
          "suggestedLabels",
          "exampleIds"
        ],
        "additionalProperties": false
      }
"""

enum class LabelConfidence {
    HIGH,
    LOW,
    MEDIUM,
}

data class SuggestedLabel(
    val path: String,
    val definition: String,
    val confidence: LabelConfidence,
    val reason: String,
    val existing: Boolean = false,
)

data class Cluster(
    val id: Int,
    val indices: List<Int>,
    val conversationIds: List<String>,
)

// Data class to deserialize the model response
data class SuggestedLabelResponse(
    val suggestedLabels: List<SuggestedLabel>,
    val exampleIds: List<String>,
    val cluster: Cluster? = null,
)

data class SuggestLabelsRequest(
    val bucketPath: String,
    val model: String,
    val apiKey: String = "",
)
