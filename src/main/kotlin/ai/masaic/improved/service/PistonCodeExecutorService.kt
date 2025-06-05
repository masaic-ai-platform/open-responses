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
 * Service for executing Python code using Piston API.
 * 
 * This service provides a local Python execution environment using Piston,
 * which eliminates the need for external API keys and provides fast, reliable execution.
 */
@Service
class PistonCodeExecutorService(
    @Value("\${piston.api.url:http://localhost:2000}")
    private val pistonUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}

    private val httpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    /**
     * Check if Piston service is available
     */
    fun isAvailable(): Boolean =
        try {
            val request =
                Request
                    .Builder()
                    .url("$pistonUrl/api/v2/runtimes")
                    .get()
                    .build()

            httpClient.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                if (success) {
                    logger.info { "Piston service is available at $pistonUrl" }
                } else {
                    logger.warn { "Piston service unavailable: ${response.code} ${response.message}" }
                }
                success
            }
        } catch (e: Exception) {
            logger.warn { "Piston service check failed: ${e.message}" }
            false
        }

    /**
     * Execute Python code using Piston API
     */
    fun executePythonCode(
        pythonCode: String,
        queryResults: List<Map<String, Any>>,
    ): PythonAnalysisResult {
        val startTime = System.currentTimeMillis()

        try {
            if (!isAvailable()) {
                throw RuntimeException("Piston service is not available")
            }

                        // Prepare the complete Python script with data loading
            // Properly indent the user's Python code for the try block
            val indentedPythonCode = formatAndIndentPythonCode(pythonCode)
            
            val fullScript =
                """
import json

# Load the query results data
data_json = '''${objectMapper.writeValueAsString(queryResults)}'''
data = json.loads(data_json)

try:
    # Execute the user's analysis code
$indentedPythonCode
    
except Exception as e:
    print(f"ANALYSIS_ERROR: {str(e)}")
    import traceback
    traceback.print_exc()
""".trimIndent()

            val pistonRequest =
                PistonExecuteRequest(
                    language = "python3",
                    version = "3.12.0",
                    files =
                        listOf(
                            PistonFile(content = fullScript),
                        ),
                )

            val requestBody =
                objectMapper
                    .writeValueAsString(pistonRequest)
                    .toRequestBody("application/json".toMediaType())

            val request =
                Request
                    .Builder()
                    .url("$pistonUrl/api/v2/execute")
                    .post(requestBody)
                    .build()

            httpClient.newCall(request).execute().use { response ->
                val executionTime = System.currentTimeMillis() - startTime

                if (!response.isSuccessful) {
                    logger.error { "Piston execution failed: ${response.code} ${response.message}" }
                    return createErrorResult(startTime, pythonCode, "Piston API error: ${response.code}")
                }

                val responseBody =
                    response.body?.string()
                        ?: return createErrorResult(startTime, pythonCode, "Empty response from Piston")

                val pistonResponse = objectMapper.readValue(responseBody, PistonExecuteResponse::class.java)

                return if (pistonResponse.run.stderr.isNotEmpty() || pistonResponse.run.code != 0) {
                    logger.error { "Python execution failed with stderr: ${pistonResponse.run.stderr}" }
                    logger.debug { "Failed Python code:\n$pythonCode" }
                    PythonAnalysisResult(
                        output = "Error: ${pistonResponse.run.stderr}",
                        code = pythonCode,
                        executionTime = executionTime,
                        success = false,
                    )
                } else {
                    val output = pistonResponse.run.stdout.ifEmpty { "Analysis completed successfully" }
                    logger.info { "Python execution successful in ${executionTime}ms" }
                    PythonAnalysisResult(
                        output = cleanOutput(output),
                        code = pythonCode,
                        executionTime = executionTime,
                        success = true,
                    )
                }
            }
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            logger.error(e) { "Error executing Python code with Piston" }
            return createErrorResult(startTime, pythonCode, e.message ?: "Unknown error")
        }
    }

    /**
     * Create an error result
     */
    private fun createErrorResult(
        startTime: Long,
        pythonCode: String,
        errorMessage: String,
    ): PythonAnalysisResult =
        PythonAnalysisResult(
            output = "Error: $errorMessage",
            code = pythonCode,
            executionTime = System.currentTimeMillis() - startTime,
            success = false,
        )

    /**
     * Clean the output from Python execution
     */
    private fun cleanOutput(output: String): String {
        return output
            .lines()
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(10000) // Limit output length
    }

    /**
     * Format and indent Python code using a simpler, more reliable approach
     */
    private fun formatAndIndentPythonCode(pythonCode: String): String {
        if (pythonCode.isBlank()) return "    pass"
        
        // Use a simpler approach: format directly in Kotlin to avoid string escaping issues
        try {
            val lines = pythonCode.lines()
            val result = mutableListOf<String>()
            var indentLevel = 0
            
            for (line in lines) {
                if (line.isBlank()) {
                    result.add("")
                    continue
                }
                
                val stripped = line.trim()
                
                // Handle dedent keywords first
                if (stripped.startsWith("except") || 
                    stripped.startsWith("elif") || 
                    stripped.startsWith("else:") || 
                    stripped.startsWith("finally:")) {
                    indentLevel = maxOf(0, indentLevel - 1)
                }
                
                // Apply current indentation level (4 spaces base + current level * 4)
                val totalIndent = 4 + (indentLevel * 4)
                val formattedLine = " ".repeat(totalIndent) + stripped
                result.add(formattedLine)
                
                // Increase indent level after colon (new block)
                if (stripped.endsWith(":") && !stripped.endsWith("\\:")) {
                    indentLevel++
                }
            }
            
            return result.joinToString("\n")
            
        } catch (e: Exception) {
            logger.warn { "Failed to format Python code, using simple indentation: ${e.message}" }
            
            // Fallback: simple indentation
            return pythonCode.lines().joinToString("\n") { line ->
                if (line.isBlank()) line else "    $line"
            }
        }
    }
        


    /**
     * Data classes for Piston API
     */
    data class PistonExecuteRequest(
        @JsonProperty("language") val language: String,
        @JsonProperty("version") val version: String,
        @JsonProperty("files") val files: List<PistonFile>,
    )

    data class PistonFile(
        @JsonProperty("content") val content: String,
    )

    data class PistonExecuteResponse(
        @JsonProperty("language") val language: String,
        @JsonProperty("version") val version: String,
        @JsonProperty("run") val run: PistonRunResult,
    )

    data class PistonRunResult(
        @JsonProperty("stdout") val stdout: String,
        @JsonProperty("stderr") val stderr: String,
        @JsonProperty("code") val code: Int,
        @JsonProperty("signal") val signal: String?,
    )
} 
