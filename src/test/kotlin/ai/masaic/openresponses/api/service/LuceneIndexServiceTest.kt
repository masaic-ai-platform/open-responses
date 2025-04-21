package ai.masaic.openresponses.api.service

import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import ai.masaic.openresponses.api.service.search.LuceneChunk
import ai.masaic.openresponses.api.service.search.LuceneIndexService
import org.junit.jupiter.api.*
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LuceneIndexServiceTest {
    private lateinit var tempDir: File
    private lateinit var service: LuceneIndexService

    @BeforeEach
    fun setUp() {
        tempDir = createTempDir(prefix = "lucene_test_")
        service = LuceneIndexService(tempDir.absolutePath)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testIndexAndSearchSingleChunk() {
        val chunk =
            LuceneChunk(
                chunkId = "c1",
                fileId = "f1",
                filename = "file.txt",
                vectorStoreId = "vs1",
                chunkIndex = 0,
                content = "Quantum physics is fascinating",
            )
        service.indexChunks(listOf(chunk))

        val results: List<VectorStoreSearchResult> = service.search("quantum", 10)

        assertEquals(1, results.size)
        assertEquals("f1", results.first().fileId)
        assertTrue(
            results
                .first()
                .content
                .first()
                .text
                .contains("Quantum", ignoreCase = true),
        )
    }

    @Test
    fun testSearchWithVectorStoreFilter() {
        val chunks =
            listOf(
                LuceneChunk("c1", "f1", "file1.txt", "vs1", 0, "Alpha beta gamma"),
                LuceneChunk("c2", "f2", "file2.txt", "vs2", 1, "Beta gamma delta"),
            )
        service.indexChunks(chunks)

        val resultsVs1 = service.search("gamma", 10, listOf("vs1"))
        val resultsVs2 = service.search("gamma", 10, listOf("vs2"))

        assertEquals(1, resultsVs1.size)
        assertEquals("vs1", resultsVs1.first().attributes?.get("vector_store_id"))

        assertEquals(1, resultsVs2.size)
        assertEquals("vs2", resultsVs2.first().attributes?.get("vector_store_id"))
    }
}
