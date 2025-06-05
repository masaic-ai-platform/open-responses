package ai.masaic.improved

import io.qdrant.client.ConditionFactory.matchKeyword
import io.qdrant.client.PointIdFactory.id
import io.qdrant.client.QdrantClient
import io.qdrant.client.ValueFactory.value
import io.qdrant.client.VectorsFactory.vectors
import io.qdrant.client.grpc.Points
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.serialization.Serializable
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.aggregation.Aggregation.*
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.*

@Component
class LabelApprovalService(
    private val qdrantClient: QdrantClient,
    private val ruleRepository: RuleRepository,
) {
    suspend fun acceptAndApplyLabels(request: AcceptLabelRequest) {
        val ruleExists = ruleRepository.getDistinctRules().any { it.label == request.suggestedLabel.path }
        if (ruleExists) throw IllegalStateException("Rule already exists")

        val filter =
            Points.Filter
                .newBuilder()
                .addAllShould(
                    request.cluster.conversationIds.map { convId ->
                        matchKeyword("conversationId", convId)
                    },
                ).build()

        // 2) Build the scroll request asking for vectors
        val scrollReq =
            Points.ScrollPoints
                .newBuilder()
                .setCollectionName(QDRANTCOLLECTIONS.CONVERSATIONS)
                .setFilter(filter)
                .setWithVectors(Points.WithVectorsSelector.newBuilder().setEnable(true))
                .setLimit(request.cluster.conversationIds.size)
                .build()

        // 3) Execute and block until Qdrant replies
        val scrollResp =
            qdrantClient
                .scrollAsync(scrollReq)
                .get()

        val floatArray: List<FloatArray> =
            scrollResp.resultList.map { result ->
                result.vectors.vector.dataList
                    .map { it }
                    .toFloatArray()
            }

        val centroidAndThreshold = centroidAndThreshold(floatArray)
        val paths = request.suggestedLabel.path.split("/")
        val version = (if (paths.size == 3) "${paths[0]}_${paths[1]}_" else "${paths[0]}_") + Instant.now()

        val rule =
            ruleRepository.save(
                Rule(
                    bucketPath = request.bucketPath,
                    label = request.suggestedLabel.path,
                    definition = request.suggestedLabel.definition,
                    reason = request.suggestedLabel.reason,
                    centroid = centroidAndThreshold.first,
                    threshold = centroidAndThreshold.second,
                    version = version,
                    author = request.author,
                ),
            )
        createConversationVectorPoint(rule)
    }

    suspend fun createConversationVectorPoint(rule: Rule) {
        val point =
            Points.PointStruct
                .newBuilder()
                // Use the embedding’s index as the point ID (or UUID if you prefer)
                .setId(id(UUID.randomUUID()))
                // Attach the vector
                .setVectors(vectors(*rule.centroid))
                // Add any metadata you like; here, we store the original index and model name
                .putAllPayload(
                    mapOf(
                        "ruleId" to value(rule.id),
                        "threshold" to value(rule.threshold.toString()),
                        "label" to value(rule.label),
                        "definition" to value(rule.definition),
                        "reason" to value(rule.reason),
                        "bucketPath" to value(rule.bucketPath),
                        "version" to value(rule.version),
                        "author" to value(rule.author),
                        "createdAt" to value(rule.createdAt.toString()),
                    ),
                ).build()

        qdrantClient
            .upsertAsync(QDRANTCOLLECTIONS.LABEL_RULES, listOf(point))
    }

    private fun norm(v: FloatArray): Float {
        var sum = 0f
        for (x in v) sum += x * x
        return kotlin.math.sqrt(sum)
    }

    private fun dot(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        var acc = 0f
        for (i in a.indices) acc += a[i] * b[i]
        return acc
    }

    fun centroidAndThreshold(
        vectors: List<FloatArray>,
        margin: Float = 0.05f,
    ): Pair<FloatArray, Float> {
        require(vectors.isNotEmpty()) { "Cluster vector list is empty" }
        val dim = vectors[0].size
        val centroid = FloatArray(dim)

        // 1. element-wise mean
        vectors.forEach { vec ->
            require(vec.size == dim) { "Vector dimension mismatch" }
            for (i in 0 until dim) centroid[i] += vec[i]
        }
        for (i in 0 until dim) {
            centroid[i] = centroid[i] / vectors.size
        }

        // 2. compute min cosine similarity to centroid
        val centroidNorm = norm(centroid)
        var minSim = Float.MAX_VALUE
        vectors.forEach { v ->
            val sim = dot(v, centroid) / (centroidNorm * norm(v))
            if (sim < minSim) minSim = sim
        }

        val tau = (minSim - margin).coerceIn(0f, 1f)
        return centroid to tau
    }
}

@Component
class RuleRepository(
    private val reactiveMongoTemplate: ReactiveMongoTemplate,
) {
    companion object {
        const val RULE_COLLECTION = "label_rules"
    }

    suspend fun save(rule: Rule): Rule {
        val ruleWithId =
            if (rule.id.isBlank()) {
                val uuid =
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                rule.copy(id = "rule_$uuid")
            } else {
                rule
            }

        return reactiveMongoTemplate.save(ruleWithId, RULE_COLLECTION).awaitFirst()
    }

    suspend fun getDistinctRules(): List<Rule> {
        val sortStage = sort(Sort.by(Sort.Direction.DESC, "createdAt"))
        // 2) Group by `label`, keep the first (i.e. newest) document as "rule"
        val groupStage =
            group("label")
                .first(Aggregation.ROOT)
                .`as`("rule")
        // 3) Replace the root with that sub-document
        val replaceStage = replaceRoot("rule")
        val pipeline = newAggregation(sortStage, groupStage, replaceStage)

        return reactiveMongoTemplate
            .aggregate(pipeline, RULE_COLLECTION, Rule::class.java)
            .collectList()
            .awaitFirst()
    }
}

// ── api/dto/AcceptLabelRequest.kt ─────────────────────────────
data class AcceptLabelRequest(
    val bucketPath: String, // e.g. "generic/user_escalation/explicit_escalate"
    val suggestedLabel: SuggestedLabel,
    val cluster: Cluster,
    val author: String, // email of the domain expert
    val apiKey: String = "",
)

@Serializable
data class AcceptLabelResponse(
    val ruleId: String,
    val conversationsUpdated: Long,
)

@Serializable
data class RejectLabelRequest(
    val bucketPath: String,
    val labelPath: String,
    val remarks: String, // path the expert rejected
)

data class Rule(
    val id: String = "",
    val label: String,
    val bucketPath: String = "NA",
    val definition: String,
    val reason: String,
    val centroid: FloatArray,
    val threshold: Float,
    val version: String,
    val author: String,
    val createdAt: Instant = Instant.now(),
)
