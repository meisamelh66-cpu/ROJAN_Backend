package ai.rojan.backend.infrastructure.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
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
    private val environment: Environment,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val isProd = environment.acceptsProfiles(Profiles.of("prod"))

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
                    .requestMatchers(*ALWAYS_PUBLIC_ENDPOINTS).permitAll()
                    // Interactive API docs stay open in dev/test for convenience, but never
                    // under the "prod" profile - even if springdoc is ever re-enabled there
                    // (application-prod.yml defaults it off), these paths still require a
                    // valid bearer token rather than being unauthenticated by construction.
                    .apply {
                        if (!isProd) {
                            requestMatchers(*DOCS_ENDPOINTS_NON_PROD_ONLY).permitAll()
                        }
                    }
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

        val ALWAYS_PUBLIC_ENDPOINTS = arrayOf(
            "/api/v1/auth/**",

            // Public website API (ROJAN Web)
            "/api/v1/public/**",

            "/actuator/health",
            "/actuator/health/**",
        )

        // Interactive API docs - permitAll only outside the "prod" profile, see above.
        val DOCS_ENDPOINTS_NON_PROD_ONLY = arrayOf(
            "/v3/api-docs/**",
            "/v3/api-docs.yaml",
            "/swagger-ui/**",
            "/swagger-ui.html",
        )
    }
}
