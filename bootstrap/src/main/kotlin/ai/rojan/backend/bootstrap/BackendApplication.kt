package ai.rojan.backend.bootstrap

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

private const val BASE_PACKAGE = "ai.rojan.backend"
private const val PERSISTENCE_PACKAGE = "ai.rojan.backend.infrastructure.persistence"

/**
 * Composition root. Component scanning must be pointed explicitly at
 * [BASE_PACKAGE] because this class lives under `ai.rojan.backend.bootstrap`,
 * a sibling of — not an ancestor of — the domain/application/infrastructure/api
 * packages the other modules contribute.
 */
@SpringBootApplication(scanBasePackages = [BASE_PACKAGE])
@EntityScan(basePackages = [PERSISTENCE_PACKAGE])
@EnableJpaRepositories(basePackages = [PERSISTENCE_PACKAGE])
@EnableJpaAuditing
class BackendApplication

fun main(args: Array<String>) {
    runApplication<BackendApplication>(*args)
}
