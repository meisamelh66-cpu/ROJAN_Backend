package ai.rojan.backend.api.config

import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.application.verification.ApproveSalonVerificationUseCase
import ai.rojan.backend.application.verification.GetGeoClassificationForPlatformUseCase
import ai.rojan.backend.application.verification.GetVerificationForPlatformUseCase
import ai.rojan.backend.application.verification.GetVerificationUseCase
import ai.rojan.backend.application.verification.InitiateRojanReviewUseCase
import ai.rojan.backend.application.verification.ListPendingVerificationsUseCase
import ai.rojan.backend.application.verification.ListVerificationHistoryForPlatformUseCase
import ai.rojan.backend.application.verification.ListVerificationHistoryUseCase
import ai.rojan.backend.application.verification.RejectSalonVerificationUseCase
import ai.rojan.backend.application.verification.StartVerificationReviewUseCase
import ai.rojan.backend.application.verification.SubmitVerificationUseCase
import ai.rojan.backend.domain.document.SalonDocumentRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.verification.SalonGeoClassificationReviewRepository
import ai.rojan.backend.domain.verification.SalonVerificationDocumentRepository
import ai.rojan.backend.domain.verification.SalonVerificationRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires framework-free Salon Verification Foundation (Phase 3) application use cases as Spring beans,
 * mirroring [DocumentUseCaseConfig] for the document vertical. Submit/Get/History are manager-scoped
 * ([SalonPermissionResolver]); the Platform Authority review use cases below (Phase 4 application
 * layer, Phase 5 wiring) are authorized purely via [PlatformAuthorizationResolver] instead - see
 * [ai.rojan.backend.api.platformauthority.PlatformAuthorityVerificationController]'s own doc comment
 * for why they're wired here rather than a separate config.
 */
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

    @Bean
    fun initiateRojanReviewUseCase(
        salonRepository: SalonRepository,
        verificationRepository: SalonVerificationRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = InitiateRojanReviewUseCase(salonRepository, verificationRepository, platformAuthorization)

    @Bean
    fun listPendingVerificationsUseCase(
        verificationRepository: SalonVerificationRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = ListPendingVerificationsUseCase(verificationRepository, platformAuthorization)

    @Bean
    fun startVerificationReviewUseCase(
        verificationRepository: SalonVerificationRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = StartVerificationReviewUseCase(verificationRepository, platformAuthorization)

    @Bean
    fun approveSalonVerificationUseCase(
        salonRepository: SalonRepository,
        verificationRepository: SalonVerificationRepository,
        verificationDocumentRepository: SalonVerificationDocumentRepository,
        documentRepository: SalonDocumentRepository,
        geoClassificationReviewRepository: SalonGeoClassificationReviewRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = ApproveSalonVerificationUseCase(
        salonRepository,
        verificationRepository,
        verificationDocumentRepository,
        documentRepository,
        geoClassificationReviewRepository,
        platformAuthorization,
    )

    @Bean
    fun rejectSalonVerificationUseCase(
        salonRepository: SalonRepository,
        verificationRepository: SalonVerificationRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = RejectSalonVerificationUseCase(salonRepository, verificationRepository, platformAuthorization)

    @Bean
    fun getVerificationForPlatformUseCase(
        salonRepository: SalonRepository,
        verificationRepository: SalonVerificationRepository,
        verificationDocumentRepository: SalonVerificationDocumentRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = GetVerificationForPlatformUseCase(salonRepository, verificationRepository, verificationDocumentRepository, platformAuthorization)

    @Bean
    fun listVerificationHistoryForPlatformUseCase(
        salonRepository: SalonRepository,
        verificationRepository: SalonVerificationRepository,
        verificationDocumentRepository: SalonVerificationDocumentRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = ListVerificationHistoryForPlatformUseCase(salonRepository, verificationRepository, verificationDocumentRepository, platformAuthorization)

    @Bean
    fun getGeoClassificationForPlatformUseCase(
        salonRepository: SalonRepository,
        verificationRepository: SalonVerificationRepository,
        geoClassificationReviewRepository: SalonGeoClassificationReviewRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = GetGeoClassificationForPlatformUseCase(salonRepository, verificationRepository, geoClassificationReviewRepository, platformAuthorization)
}
