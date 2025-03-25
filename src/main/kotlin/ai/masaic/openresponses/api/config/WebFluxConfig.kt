package ai.masaic.openresponses.api.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.reactive.config.WebFluxConfigurer

@Configuration
@EnableWebFlux
class WebFluxConfig : WebFluxConfigurer {
    override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
        // Create a custom ObjectMapper
        val objectMapper =
            Jackson2ObjectMapperBuilder
                .json()
                .failOnUnknownProperties(false)
                .build<ObjectMapper>()
                .apply {
                    // Configure Jackson to be more lenient with types
                    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
                }

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
    }
}
