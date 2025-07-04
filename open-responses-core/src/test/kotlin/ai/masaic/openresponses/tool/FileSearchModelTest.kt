package ai.masaic.openresponses.tool

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FileSearchModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `FileSearchParams should serialize and deserialize correctly`() {
        // Given
        val params = FileSearchParams(query = "test document")
        
        // When
        val serialized = json.encodeToString(params)
        val deserialized = json.decodeFromString<FileSearchParams>(serialized)
        
        // Then
        assertEquals(params.query, deserialized.query)
        assertEquals("""{"query":"test document"}""", serialized)
    }

    @Test
    fun `FileSearchResponse should serialize and deserialize correctly`() {
        // Given
        val fileId = "file-123"
        val filename = "test.txt"
        
        val result =
            FileSearchResult(
                file_id = fileId,
                filename = filename,
                score = 0.95,
                content = "This is test content",
                annotations =
                    listOf(
                        FileCitation(
                            type = "file_citation",
                            index = 1,
                            file_id = fileId,
                            filename = filename,
                        ),
                    ),
            )
        
        val response = FileSearchResponse(data = listOf(result))
        
        // When
        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<FileSearchResponse>(serialized)
        
        // Then
        assertEquals(1, deserialized.data.size)
        assertEquals(fileId, deserialized.data[0].file_id)
        assertEquals(filename, deserialized.data[0].filename)
        assertEquals(0.95, deserialized.data[0].score)
        assertEquals("This is test content", deserialized.data[0].content)
        
        assertEquals(1, deserialized.data[0].annotations.size)
        assertEquals("file_citation", deserialized.data[0].annotations[0].type)
        assertEquals(1, deserialized.data[0].annotations[0].index)
        assertEquals(fileId, deserialized.data[0].annotations[0].file_id)
        assertEquals(filename, deserialized.data[0].annotations[0].filename)
    }

    @Test
    fun `FileCitation should serialize and deserialize correctly`() {
        // Given
        val fileId = "file-123"
        val filename = "test.txt"
        
        val citation =
            FileCitation(
                type = "file_citation",
                index = 1,
                file_id = fileId,
                filename = filename,
            )
        
        // When
        val serialized = json.encodeToString(citation)
        val deserialized = json.decodeFromString<FileCitation>(serialized)
        
        // Then
        assertEquals("file_citation", deserialized.type)
        assertEquals(1, deserialized.index)
        assertEquals(fileId, deserialized.file_id)
        assertEquals(filename, deserialized.filename)
    }

    @Test
    fun `FileSearchResponse should handle empty result list`() {
        // Given
        val response = FileSearchResponse(data = emptyList())
        
        // When
        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<FileSearchResponse>(serialized)
        
        // Then
        assertEquals(0, deserialized.data.size)
        assertEquals("""{"data":[]}""", serialized)
    }
} 
