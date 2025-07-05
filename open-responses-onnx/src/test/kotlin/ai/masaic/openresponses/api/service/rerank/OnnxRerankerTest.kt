package ai.masaic.openresponses.api.service.rerank

import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import ai.masaic.openresponses.api.model.VectorStoreSearchResultContent
import dev.langchain4j.model.output.Response
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

internal class OnnxRerankerTest {
    private lateinit var mockModel: OnnxScoringModel
    private lateinit var reranker: OnnxReranker

    @BeforeEach
    fun setUp() {
        mockModel = mockk(relaxed = true)
        reranker = spyk(OnnxReranker(pathToModel = "", pathToTokenizer = "", mockModel))
    }

    private fun makeDoc(
        id: String,
        text: String,
    ): VectorStoreSearchResult =
        VectorStoreSearchResult(
            fileId = id,
            content = listOf(VectorStoreSearchResultContent(text = text, type = "text")),
            filename = id,
            score = 0.5,
            attributes = mapOf(),
        )

    @Test
    fun `rerank returns top K docs by descending ONNX score`() =
        runBlocking {
            val docs =
                listOf(
                    makeDoc("A", "Doc A"),
                    makeDoc("B", "Doc B"),
                    makeDoc("C", "Doc C"),
                )

            every { mockModel.score(ofType<String>(), "Doc A") } returns Response.from<Double>(0.3)
            every { mockModel.score(ofType<String>(), "Doc B") } returns Response.from<Double>(0.9)
            every { mockModel.score(ofType<String>(), "Doc C") } returns Response.from<Double>(0.5)

            val result = reranker.rerank(query = "q", docs = docs, k = 2)

            assertEquals(listOf("B", "C"), result.map { it.fileId })
        }

    @Test
    fun `rerank with k larger than docs returns all sorted`() =
        runBlocking {
            val docs =
                listOf(
                    makeDoc("X", "Doc X"),
                    makeDoc("Y", "Doc Y"),
                )

            every { mockModel.score(ofType<String>(), "Doc X") } returns Response.from<Double>(0.1)
            every { mockModel.score(ofType<String>(), "Doc Y") } returns Response.from<Double>(0.7)

            val result = reranker.rerank(query = "anything", docs = docs, k = 10)

            assertEquals(listOf("Y", "X"), result.map { it.fileId })
        }
}
