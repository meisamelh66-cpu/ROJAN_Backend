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
                    // PASS MEDIA-PUBLIC-01: real, publicly-servable salon-identity media only
                    // (logo/cover/gallery/portfolio/specialist-photo/service-image -
                    // UploadMediaUseCase's own PUBLIC_IMAGE_TYPES, confirmed by direct source
                    // read). The middle "media" segment is the load-bearing part of this
                    // pattern, not decorative - UploadMediaUseCase writes every DOCUMENT-typed
                    // asset under a sibling "documents" segment instead
                    // (salons/{id}/documents/{uuid}, "so a bucket policy can grant public read
                    // on media/ while denying it entirely on documents/" per that use case's own
                    // comment), which this pattern never matches and which therefore stays
                    // behind anyRequest().authenticated() below, completely unchanged. In real
                    // production this route is never reached at all - Nginx serves /media/**
                    // directly from the shared uploads volume (LocalDiskMediaStorageAdapter's own
                    // doc comment, docker-compose.prod.yml's nginx service) - this only matters
                    // for a local/dev topology (no Nginx in front) where the JVM is hit directly.
                    .requestMatchers(HttpMethod.GET, "/media/salons/*/media/**").permitAll()
                    // Customer Profile Personalization Phase 5A.2: same public-read policy as
                    // salon media above, mirrored for user avatar/cover images - real shape is
                    // "users/{userId}/media/{uuid}.{ext}" (UploadUserAvatarUseCase /
                    // UploadUserCoverUseCase, both in UserProfileMediaUseCases.kt). Users have no
                    // "documents"-equivalent private media segment to accidentally expose, so this
                    // is a direct analog of the salon rule, not a broader pattern. Without this,
                    // UserController's own resolved avatarUrl/coverUrl (returned to every
                    // authenticated client) 401s on GET despite being intended as public,
                    // semi-public-by-nature URLs - confirmed live: salon media GETs 200/404,
                    // user media GETs 401 AUTH_UNAUTHORIZED for the identical request shape.
                    .requestMatchers(HttpMethod.GET, "/media/users/*/media/**").permitAll()
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
