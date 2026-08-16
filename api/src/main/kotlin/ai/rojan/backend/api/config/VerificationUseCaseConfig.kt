package ai.rojan.backend.api.config

import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.application.verification.GetVerificationUseCase
import ai.rojan.backend.application.verification.ListVerificationHistoryUseCase
import ai.rojan.backend.application.verification.SubmitVerificationUseCase
import ai.rojan.backend.domain.document.SalonDocumentRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.verification.SalonVerificationDocumentRepository
import ai.rojan.backend.domain.verification.SalonVerificationRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires framework-free Salon Verification Foundation (Phase 3) application use cases as Spring beans, mirroring [DocumentUseCaseConfig] for the document vertical. Submit/Get/History only - review/approve/reject are not built this phase. */
@Configuration
class VerificationUseCaseConfig {

    @Bean
    fun submitVerificationUseCase(
        salonRepository: SalonRepository,
        documentRepository: SalonDocumentRepository,
        verificationRepository: SalonVerificationRepository,
        verificationDocumentRepository: SalonVerificationDocumentRepository,
    ) = SubmitVerificationUseCase(salonRepository, documentRepository, verificationRepository, verificationDocumentRepository)

    @Bean
    fun getVerificationUseCase(
        salonRepository: SalonRepository,
        verificationRepository: SalonVerificationRepository,
        verificationDocumentRepository: SalonVerificationDocumentRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = GetVerificationUseCase(salonRepository, verificationRepository, verificationDocumentRepository, salonPermissionResolver)

    @Bean
    fun listVerificationHistoryUseCase(
        salonRepository: SalonRepository,
        verificationRepository: SalonVerificationRepository,
        verificationDocumentRepository: SalonVerificationDocumentRepository,
    ) = ListVerificationHistoryUseCase(salonRepository, verificationRepository, verificationDocumentRepository)
}
