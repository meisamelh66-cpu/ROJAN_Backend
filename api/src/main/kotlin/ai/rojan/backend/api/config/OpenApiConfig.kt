package ai.rojan.backend.api.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private const val BEARER_SCHEME = "bearerAuth"

@Configuration
class OpenApiConfig {

    @Bean
    fun rojanOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("ROJAN AI Backend")
                .description("Production API for the ROJAN AI salon booking platform")
                .version("v1"),
        )
        .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))
        .components(
            Components().addSecuritySchemes(
                BEARER_SCHEME,
                SecurityScheme()
                    .name(BEARER_SCHEME)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT"),
            ),
        )
}
