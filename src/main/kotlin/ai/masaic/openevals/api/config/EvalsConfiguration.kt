package ai.masaic.openevals.api.config

import com.mitchellbosecke.pebble.PebbleEngine
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class EvalsConfiguration {
    @Bean
    fun pebbleEngine(): PebbleEngine {
        val engine: PebbleEngine =
            PebbleEngine
                .Builder()
                .autoEscaping(true)
                .strictVariables(true)
                .build()
        return engine
    }
} 
