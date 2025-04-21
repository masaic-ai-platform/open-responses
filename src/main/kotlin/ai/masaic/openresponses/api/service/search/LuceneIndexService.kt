package ai.masaic.openresponses.api.service.search

import ai.masaic.openresponses.api.model.VectorStoreSearchResult
import ai.masaic.openresponses.api.model.VectorStoreSearchResultContent
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.IntPoint
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.FSDirectory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Paths
import kotlin.math.pow

/**
 * Service for full-text indexing and search using Lucene.
 */
@Service
class LuceneIndexService(
    @Value("\${open-responses.file-storage.local.root-dir}")
    private val rootDir: String,
) {
    private val indexPath = "$rootDir/lucene-index"
    private val analyzer = StandardAnalyzer()
    private val log = org.slf4j.LoggerFactory.getLogger(LuceneIndexService::class.java)

    /**
     * Indexes a batch of text chunks in Lucene.
     */
    fun indexChunks(chunks: List<LuceneChunk>) {
        val dir = FSDirectory.open(Paths.get(indexPath))
        val maxRetries = 5
        var attempt = 0
        while (true) {
            try {
                val writerConfig = IndexWriterConfig(analyzer)
                IndexWriter(dir, writerConfig).use { writer ->
                    chunks.forEach { chunk ->
                        val doc =
                            Document().apply {
                                add(StringField("chunk_id", chunk.chunkId, Field.Store.YES))
                                add(StringField("file_id", chunk.fileId, Field.Store.YES))
                                add(StringField("filename", chunk.filename, Field.Store.YES))
                                add(StringField("vector_store_id", chunk.vectorStoreId, Field.Store.YES))
                                add(IntPoint("chunk_index", chunk.chunkIndex))
                                add(StoredField("chunk_index", chunk.chunkIndex))
                                add(TextField("content", chunk.content, Field.Store.YES))
                            }
                        writer.addDocument(doc)
                    }
                    writer.commit()
                }
                break
            } catch (e: org.apache.lucene.store.LockObtainFailedException) {
                attempt++
                if (attempt > maxRetries) {
                    log.error("Failed to obtain Lucene index lock after $attempt attempts", e)
                    throw e
                }
                log.warn("Lucene index locked, retrying attempt $attempt/$maxRetries", e)
                val backOff = 2.0.pow(attempt).toLong()
                val jitter = (0..1000).random()
                Thread.sleep(backOff * 1000 + jitter) // Exponential backoff
            }
        }
    }

    /**
     * Performs a full-text search over the Lucene index.
     * @param queryText The search text
     * @param topK Maximum number of results
     * @return List of search results
     */
    fun search(
        queryText: String,
        topK: Int,
        vectorStoreIds: List<String>? = null,
    ): List<VectorStoreSearchResult> {
        if (vectorStoreIds != null && vectorStoreIds.isEmpty()) {
            return emptyList()
        }

        val dir = FSDirectory.open(Paths.get(indexPath))
        DirectoryReader.open(dir).use { reader ->
            val searcher = IndexSearcher(reader)
            val parser = QueryParser("content", analyzer)

            // Build query combining text search with vector store filter if needed
            // Escape special characters in the query text to avoid parse exceptions
            val escapedQuery = QueryParser.escape(queryText)
            val contentQuery = parser.parse(escapedQuery)
            val finalQuery =
                if (vectorStoreIds != null) {
                    val builder = BooleanQuery.Builder()
                    builder.add(contentQuery, BooleanClause.Occur.MUST)

                    // Add a clause to filter by vectorStoreId
                    val vectorStoreFilter = BooleanQuery.Builder()
                    vectorStoreIds.forEach { vectorStoreId ->
                        vectorStoreFilter.add(
                            TermQuery(Term("vector_store_id", vectorStoreId)),
                            BooleanClause.Occur.SHOULD,
                        )
                    }
                    builder.add(vectorStoreFilter.build(), BooleanClause.Occur.MUST)
                    builder.build()
                } else {
                    contentQuery
                }

            val hits = searcher.search(finalQuery, topK).scoreDocs
            return hits.map { hit ->
                val doc = searcher.doc(hit.doc)
                VectorStoreSearchResult(
                    fileId = doc.get("file_id"),
                    filename = doc.get("filename"),
                    score = hit.score.toDouble(),
                    content = listOf(VectorStoreSearchResultContent("text", doc.get("content"))),
                    attributes =
                        mapOf(
                            "chunk_id" to doc.get("chunk_id"),
                            "chunk_index" to doc.get("chunk_index").toInt(),
                            "vector_store_id" to doc.get("vector_store_id"),
                        ),
                )
            }
        }
    }
}

/**
 * Simple data model representing a text chunk to index in Lucene.
 */
data class LuceneChunk(
    val chunkId: String,
    val fileId: String,
    val filename: String,
    val vectorStoreId: String,
    val chunkIndex: Int,
    val content: String,
) 
