package ai.masaic.openresponses.api.service.rerank

import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import dev.langchain4j.model.output.Response
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    name = ["open-responses.reranker.type"],
    havingValue = "onnx",
    matchIfMissing = false,
)
class OnnxReranker(
    @Value("\${open-responses.reranker.onnx.path-to-model}")
    private val pathToModel: String,
    @Value("\${open-responses.reranker.onnx.path-to-tokenizer}")
    private val pathToTokenizer: String,
    private val model: OnnxScoringModel = OnnxScoringModel(pathToModel, pathToTokenizer),
) : RerankerService {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        log.info("Using ONNX reranker with model at $pathToModel and tokenizer at $pathToTokenizer")
    }

    override suspend fun rerank(
        query: String,
        docs: List<VectorStoreSearchResult>,
        k: Int,
    ): List<VectorStoreSearchResult> =
        coroutineScope {
            log.debug("Reranking ${docs.size} documents for query: $query")
            val rerankedDocs =
                docs
                    .sortedByDescending { doc ->
                        val response: Response<Double> = model.score(query, doc.content[0].text)
                        val score = response.content()
                        log.debug("Score for document ${doc.fileId}: $score")
                        score
                    }.take(k)
            return@coroutineScope rerankedDocs
        }
}
