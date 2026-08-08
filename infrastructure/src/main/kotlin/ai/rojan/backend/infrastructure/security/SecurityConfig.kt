package ai.rojan.backend.infrastructure.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfigurationSource

@Configuration
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val corsConfigurationSource: CorsConfigurationSource,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource) }
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .exceptionHandling {
                // A minimal, fixed body (not the full ApiError shape GlobalExceptionHandler
                // uses elsewhere) — this fires at the security-filter level, before a
                // request ever reaches a controller/GlobalExceptionHandler, for every
                // missing/invalid/expired bearer token across the whole API.
                it.authenticationEntryPoint { _, response, _ ->
                    response.status = HttpStatus.UNAUTHORIZED.value()
                    response.contentType = "application/json"
                    response.writer.write(
                        """{"errorCode":"AUTH_UNAUTHORIZED","message":"Authentication required"}""",
                    )
                }
            }
            .authorizeHttpRequests { authorize ->
                authorize
                    .requestMatchers(*PUBLIC_ENDPOINTS).permitAll()
                    // GET-only, single path segment: matches /api/v1/invites/{token} (the
                    // unauthenticated confirmation-screen lookup) but never
                    // /api/v1/invites/{token}/accept, which stays authenticated below.
                    .requestMatchers(HttpMethod.GET, "/api/v1/invites/*").permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(
                jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter::class.java
            )

        return http.build()
    }

    private companion object {

        val PUBLIC_ENDPOINTS = arrayOf(
            "/api/v1/auth/**",

            // Public website API (ROJAN Web)
            "/api/v1/public/**",

            "/actuator/health",
            "/actuator/health/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
        )
    }
}
