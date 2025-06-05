package ai.masaic.improved.service

import ai.masaic.improved.model.PythonAnalysisResult
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

/**
 * Service for executing Python code using E2B Code Interpreter.
 * 
 * This service provides a secure, cloud-based Python execution environment
 * that eliminates local Python setup issues and provides consistent execution.
 */
@Service
class E2BCodeExecutorService(
    @Value("\${e2b.api.key:}")
    private val e2bApiKey: String,
) {
    private val logger = KotlinLogging.logger {}
    private val objectMapper = ObjectMapper()
    
    private val httpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    companion object {
        private const val E2B_API_URL = "https://api.e2b.dev/v2/sandboxes"
        private const val TEMPLATE_ID = "base" // Use base template with Python
    }

    /**
     * Execute Python code using E2B and return analysis results
     */
    suspend fun executePythonCode(
        pythonCode: String,
        queryResults: List<Map<String, Any>>,
    ): PythonAnalysisResult {
        val startTime = System.currentTimeMillis()
        
        if (e2bApiKey.isBlank()) {
            logger.warn { "E2B API key not configured, falling back to mock execution" }
            throw RuntimeException("E2B API key not configured")
        }
        
        return try {
            logger.info { "Creating E2B sandbox for Python execution" }
            
            // Create sandbox
            val sandboxId = createSandbox()
            
            try {
                // Prepare data and execute code
                val result = executeInSandbox(sandboxId, pythonCode, queryResults)
                
                PythonAnalysisResult(
                    code = pythonCode,
                    output = result,
                    executionTime = System.currentTimeMillis() - startTime,
                    success = true,
                )
            } finally {
                // Clean up sandbox
                deleteSandbox(sandboxId)
            }
        } catch (e: Exception) {
            logger.error(e) { "Error executing Python code in E2B: ${e.message}" }
            PythonAnalysisResult(
                code = pythonCode,
                output = "Error executing Python code: ${e.message}",
                executionTime = System.currentTimeMillis() - startTime,
                success = false,
                error = e.message,
            )
        }
    }

    /**
     * Create a new E2B sandbox
     */
    private fun createSandbox(): String {
        val requestBody =
            mapOf(
                "templateId" to TEMPLATE_ID,
                "timeoutMs" to 60000,
            )
        
        val request =
            Request
                .Builder()
                .url(E2B_API_URL)
                .header("Authorization", "Bearer $e2bApiKey")
                .header("Content-Type", "application/json")
                .post(objectMapper.writeValueAsString(requestBody).toRequestBody("application/json".toMediaType()))
                .build()
        
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Failed to create E2B sandbox: ${response.code} ${response.message}")
            }
            
            val responseBody = response.body?.string() ?: throw RuntimeException("Empty response from E2B")
            val sandboxResponse = objectMapper.readValue(responseBody, CreateSandboxResponse::class.java)
            
            logger.info { "Created E2B sandbox: ${sandboxResponse.sandboxId}" }
            return sandboxResponse.sandboxId
        }
    }

    /**
     * Execute code in the sandbox
     */
    private fun executeInSandbox(
        sandboxId: String,
        pythonCode: String,
        queryResults: List<Map<String, Any>>,
    ): String {
        // First, upload the data
        val dataJson = objectMapper.writeValueAsString(queryResults)
        val dataUploadCode =
            """
import json
data = json.loads('''$dataJson''')
print("Data loaded successfully. Shape:", len(data))
print("Sample data:", data[:2] if data else "No data")
print("-" * 50)
            """.trimIndent()
        
        // Execute data loading
        executeCodeInSandbox(sandboxId, dataUploadCode)
        
        // Execute the main analysis code
        val fullCode =
            """
$pythonCode

print("-" * 50)
print("Analysis completed successfully")
            """.trimIndent()
        
        return executeCodeInSandbox(sandboxId, fullCode)
    }

    /**
     * Execute code in sandbox and return output
     */
    private fun executeCodeInSandbox(
        sandboxId: String,
        code: String,
    ): String {
        val requestBody =
            mapOf(
                "language" to "python",
                "code" to code,
            )
        
        val request =
            Request
                .Builder()
                .url("$E2B_API_URL/$sandboxId/executions")
                .header("Authorization", "Bearer $e2bApiKey")
                .header("Content-Type", "application/json")
                .post(objectMapper.writeValueAsString(requestBody).toRequestBody("application/json".toMediaType()))
                .build()
        
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Failed to execute code in E2B sandbox: ${response.code} ${response.message}")
            }
            
            val responseBody = response.body?.string() ?: throw RuntimeException("Empty response from E2B")
            val executionResponse = objectMapper.readValue(responseBody, ExecutionResponse::class.java)
            
            // Combine stdout and stderr
            val output = StringBuilder()
            if (executionResponse.stdout.isNotEmpty()) {
                output.append(executionResponse.stdout)
            }
            if (executionResponse.stderr.isNotEmpty()) {
                if (output.isNotEmpty()) output.append("\n")
                output.append("STDERR: ${executionResponse.stderr}")
            }
            
            return output.toString()
        }
    }

    /**
     * Delete the sandbox
     */
    private fun deleteSandbox(sandboxId: String) {
        try {
            val request =
                Request
                    .Builder()
                    .url("$E2B_API_URL/$sandboxId")
                    .header("Authorization", "Bearer $e2bApiKey")
                    .delete()
                    .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    logger.info { "Successfully deleted E2B sandbox: $sandboxId" }
                } else {
                    logger.warn { "Failed to delete E2B sandbox $sandboxId: ${response.code} ${response.message}" }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Error deleting E2B sandbox $sandboxId: ${e.message}" }
        }
    }

    /**
     * Check if E2B service is available and configured
     */
    fun isAvailable(): Boolean = e2bApiKey.isNotBlank()

    // Data classes for E2B API responses
    private data class CreateSandboxResponse(
        @JsonProperty("sandboxId") val sandboxId: String,
    )

    private data class ExecutionResponse(
        @JsonProperty("stdout") val stdout: String = "",
        @JsonProperty("stderr") val stderr: String = "",
        @JsonProperty("exitCode") val exitCode: Int = 0,
    )
}
