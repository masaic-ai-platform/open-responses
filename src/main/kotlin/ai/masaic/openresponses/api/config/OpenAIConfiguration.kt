package ai.masaic.openresponses.api.config

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.credential.BearerTokenCredential
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration for OpenAI client.
 *
 * This class provides the OpenAI client bean to be used across the application.
 */
@Configuration
class OpenAIConfiguration {
    
    @Value("\${open-responses.openai.api-key:}")
    private val apiKey: String = ""
    
    @Value("\${open-responses.openai.base-url:https://api.openai.com/v1}")
    private val baseUrl: String = "https://api.openai.com/v1"
    
    /**
     * Creates an OpenAI client bean.
     *
     * @return The OpenAI client
     */
    @Bean
    fun openAIClient(): OpenAIClient {
        val credential = if (apiKey.isNotBlank()) {
            BearerTokenCredential.create { apiKey }
        } else {
            // Fallback to environment variable if property not set
            BearerTokenCredential.create { System.getenv("OPENAI_API_KEY") ?: "" }
        }
        
        return OpenAIOkHttpClient
            .builder()
            .credential(credential)
            .baseUrl(baseUrl)
            .build()
    }
} 