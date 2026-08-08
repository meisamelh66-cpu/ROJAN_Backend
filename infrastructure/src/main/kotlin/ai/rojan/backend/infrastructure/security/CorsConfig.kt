package ai.rojan.backend.infrastructure.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Explicit allow-list only - never a wildcard origin/method/header, and
 * never combined with allowCredentials(true) (auth here is a bearer token
 * in the Authorization header, not a cookie, so credentials mode isn't
 * needed for cross-origin calls to work).
 */
@Configuration
class CorsConfig(
    @Value("\${rojan.security.cors.allowed-origins}")
    private val allowedOriginsProperty: String,
) {

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val parsedOrigins = allowedOriginsProperty
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val configuration = CorsConfiguration().apply {
            allowedOrigins = parsedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "Idempotency-Key")
            allowCredentials = false
            maxAge = 3600
        }

        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }
}
