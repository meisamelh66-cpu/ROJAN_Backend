package ai.rojan.backend.api.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private const val BEARER_SCHEME = "bearerAuth"

private val API_DESCRIPTION = """
    Production API for the ROJAN AI salon booking platform.

    ### Versioning
    All endpoints are namespaced under `/api/v1/`. Breaking changes will ship under a new version prefix
    rather than mutating `/api/v1/` in place.

    ### Errors
    Every error response — validation failures, not-found, access-denied, conflicts, and unexpected server
    errors alike — uses the same `ApiError` shape: `timestamp`, `status`, `error`, `message`, `path`, and
    `traceId`. Quote the `traceId` when reporting an issue; it is also written to the server log next to the
    full exception.

    ### Pagination
    Endpoints that return a list which can grow unbounded (browsing salons, a salon's bookings, a customer's
    bookings) return a `PagedResponse` envelope (`content`, `page`, `size`, `totalElements`, `totalPages`)
    and accept `page`/`size` (max 100) query parameters, plus a resource-specific filter and `sortDirection`.
    Small, inherently-bounded collections (a salon's branches, categories, specialists; a specialist's weekly
    schedule) are returned as plain arrays.

    ### Idempotency
    `POST /api/v1/bookings` accepts an optional `Idempotency-Key` header. Replaying the same key with an
    identical request body returns the original 201 response instead of creating a duplicate booking;
    replaying it with a different body returns 409.
""".trimIndent()

@Configuration
class OpenApiConfig {

    @Bean
    fun rojanOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("ROJAN AI Backend")
                .description(API_DESCRIPTION)
                .version("v1")
                .contact(Contact().name("ROJAN AI").email("support@rojan.ai"))
                .license(License().name("Proprietary")),
        )
        .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))
        .components(
            Components().addSecuritySchemes(
                BEARER_SCHEME,
                SecurityScheme()
                    .name(BEARER_SCHEME)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Access token returned by POST /api/v1/auth/login or /api/v1/auth/refresh"),
            ),
        )
}
