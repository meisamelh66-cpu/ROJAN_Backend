package ai.rojan.backend.api.config

import ai.rojan.backend.application.audit.RecordAuditEventUseCase
import ai.rojan.backend.domain.audit.AuditEventRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the framework-free Salon Audit History Foundation (Phase 4)
 * application use case as a Spring bean. No controller, no DTOs, no route
 * - Phase 4 restriction: no public audit API. Nothing calls
 * [RecordAuditEventUseCase] yet; retrofitting the existing salon/media
 * /document/verification use cases to do so is explicitly out of scope
 * this phase.
 */
@Configuration
class AuditUseCaseConfig {

    @Bean
    fun recordAuditEventUseCase(
        auditEventRepository: AuditEventRepository,
    ) = RecordAuditEventUseCase(auditEventRepository)
}
