package ai.masaic.openresponses.api.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration class for metrics and observability.
 * Sets up Micrometer registry and observation infrastructure.
 */
@Configuration
class MetricsConfig {
    /**
     * Creates an ObservationRegistry bean for use with @Observed annotations
     * and programmatic observations.
     */
    @Bean
    fun observationRegistry(): ObservationRegistry = ObservationRegistry.create()

    /**
     * Creates a MeterRegistry for collecting and publishing metrics.
     */
    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}
