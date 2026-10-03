package ai.rojan.backend.api.config

import ai.rojan.backend.application.audit.RecordAuditEventUseCase
import ai.rojan.backend.domain.audit.AuditEventRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the framework-free Salon Audit History Foundation (Phase 4)
 * application use case as a Spring bean. Still no controller, no DTOs, no public route - Phase 4's
 * own restriction holds (no public audit API; `GET platform-authority/audit-log` remains a
 * documented-but-unbuilt capability, see `docs/backend-requirements/platform-admin-scalability.md`
 * §4/§8's `auditLog` flag). [RecordAuditEventUseCase] now has its first real callers (Admin Salon
 * Suspend/Reinstate/Edit/Media - `ai.rojan.backend.application.platformauthority.
 * PlatformSalonUseCases`) - retrofitting the pre-existing owner-facing salon/media/document/
 * verification use cases to also call it remains out of scope here, unchanged from before.
 */
@Configuration
class AuditUseCaseConfig {

    @Bean
    fun recordAuditEventUseCase(
        auditEventRepository: AuditEventRepository,
    ) = RecordAuditEventUseCase(auditEventRepository)
}
