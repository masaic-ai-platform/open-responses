package ai.masaic.openresponses.api.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.reactive.config.WebFluxConfigurer

@Configuration
@EnableWebFlux
class WebFluxConfig(
    private val objectMapper: ObjectMapper,
    @Value("\${spring.codec.max-in-memory-size:32MB}") private val maxInMemorySize: String,
) : WebFluxConfigurer {
    override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
        // Register custom codec for NDJSON
        configurer.customCodecs().register(
            Jackson2JsonEncoder(objectMapper, MediaType.APPLICATION_NDJSON),
        )
        configurer.customCodecs().register(
            Jackson2JsonDecoder(objectMapper, MediaType.APPLICATION_NDJSON),
        )

        // Also update the default JSON codecs
        configurer.defaultCodecs().jackson2JsonDecoder(Jackson2JsonDecoder(objectMapper))
        configurer.defaultCodecs().jackson2JsonEncoder(Jackson2JsonEncoder(objectMapper))
        
        // Increase memory buffer limits for multipart uploads
        // Default is only 256KB which is far too small for file uploads
        val memorySizeInBytes = parseSize(maxInMemorySize)
        configurer.defaultCodecs().maxInMemorySize(memorySizeInBytes)
    }

    /**
     * Parse a string size representation like "50MB" into bytes
     */
    private fun parseSize(size: String): Int {
        val sizeRegex = "(\\d+)([kKmMgG][bB]?)".toRegex()
        val matchResult = sizeRegex.find(size)
        
        return if (matchResult != null) {
            val (value, unit) = matchResult.destructured
            val bytes = value.toLong()
            when (unit.lowercase()) {
                "kb", "k" -> (bytes * 1024).toInt()
                "mb", "m" -> (bytes * 1024 * 1024).toInt()
                "gb", "g" -> (bytes * 1024 * 1024 * 1024).toInt()
                else -> bytes.toInt()
            }
        } else {
            // Default to 50MB if format is not recognized
            50 * 1024 * 1024
        }
    }
}
