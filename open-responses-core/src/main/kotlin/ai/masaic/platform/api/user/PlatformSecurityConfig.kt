package ai.masaic.platform.api.user

import ai.masaic.platform.api.user.auth.google.GoogleAuthentication
import ai.masaic.platform.api.user.auth.google.GooglePreAuthToken
import ai.masaic.platform.api.user.auth.google.GoogleTokenVerifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.authentication.ReactiveAuthenticationManager
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.AuthenticationWebFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource
import reactor.core.publisher.Mono

@Profile("platform")
@Configuration
@EnableWebFluxSecurity
class PlatformSecurityConfig() {

    @Bean
    fun filterChain(authConfigProperties: AuthConfigProperties, googleTokenVerifier: GoogleTokenVerifier, http: ServerHttpSecurity): SecurityWebFilterChain {
        return if (authConfigProperties.enabled) {
            http
                .csrf { it.disable() }
                .cors { it.configurationSource(corsConfigurationSource()) }
                .authorizeExchange { authorizeExchange ->
                    authorizeExchange
                        .pathMatchers("/v1/dashboard/platform/info").permitAll()
                        .pathMatchers("/v1/dashboard/platform/auth/verify").permitAll()
                        .pathMatchers("/v1/dashboard/**").authenticated()
                        .anyExchange().permitAll()
                }
                .addFilterAt(googleAuthFilter(googleTokenVerifier), SecurityWebFiltersOrder.AUTHENTICATION)
                .build()
        } else {
            http
                .csrf { it.disable() }
                .cors { it.configurationSource(corsConfigurationSource()) }
                .authorizeExchange { authorizeExchange ->
                    authorizeExchange.anyExchange().permitAll()
                }
                .build()
        }
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOriginPatterns = listOf("*")
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }

    @Bean
    fun googleAuthenticationManager(googleTokenVerifier: GoogleTokenVerifier): ReactiveAuthenticationManager {
        return ReactiveAuthenticationManager { token ->
            when (token) {
                is GooglePreAuthToken -> {
                    googleTokenVerifier.verifyAsync(token.credentials)
                        .map { GoogleAuthentication(it) }
                }
                else -> Mono.empty()
            }
        }
    }

    @Bean
    fun googleTokenVerifier(authConfigProperties: AuthConfigProperties)=GoogleTokenVerifier(authConfigProperties.google)

    private fun googleAuthFilter(googleTokenVerifier: GoogleTokenVerifier): AuthenticationWebFilter {
        val manager = ReactiveAuthenticationManager { token ->
            Mono.just(token)
                .cast(GooglePreAuthToken::class.java)
                .flatMap { googleTokenVerifier.verifyAsync(it.credentials) }
                .map { GoogleAuthentication(it) }
        }

        val filter = AuthenticationWebFilter(manager)
        filter.setServerAuthenticationConverter { ex ->
            Mono.justOrEmpty(ex.request.headers.getFirst("X-Google-Token"))
                .map(::GooglePreAuthToken)
        }
        return filter
    }

    @Bean
    fun currentUserProvider() = CurrentLoggedInUserProvider()
}


/**
 * Google OAuth configuration
 */
@ConfigurationProperties("platform.deployment.auth")
data class AuthConfigProperties(
    val enabled: Boolean = false,
    val google: GoogleAuthConfig
)


data class GoogleAuthConfig(
    val issuer: String = "https://accounts.google.com",
    val audience: String,
    val jwksUri: String = "https://www.googleapis.com/oauth2/v3/certs"
)

data class AuthConfig(val enabled: Boolean)
