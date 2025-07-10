package ai.masaic.platform.api.config

import ai.masaic.platform.api.controller.PlatformUserInterceptor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Profile("platform")
@Configuration
class PlatformWebConfig(
    private val platformUserInterceptor: PlatformUserInterceptor
) {
    
    @Bean
    fun platformUserWebFilter() = platformUserInterceptor
} 