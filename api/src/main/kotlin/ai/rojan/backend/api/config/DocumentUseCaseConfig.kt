package ai.rojan.backend.api.config

import ai.rojan.backend.application.document.ApproveSalonDocumentUseCase
import ai.rojan.backend.application.document.AttachDocumentUseCase
import ai.rojan.backend.application.document.DeleteDocumentUseCase
import ai.rojan.backend.application.document.GetDocumentAccessUrlUseCase
import ai.rojan.backend.application.document.GetDocumentUseCase
import ai.rojan.backend.application.document.ListDocumentsUseCase
import ai.rojan.backend.application.document.ListSalonDocumentsForPlatformUseCase
import ai.rojan.backend.application.document.RejectSalonDocumentUseCase
import ai.rojan.backend.application.media.DeleteMediaUseCase
import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.document.SalonDocumentRepository
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.salon.SalonRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires framework-free Document Archive (Phase 2) application use cases as
 * Spring beans, mirroring [MediaUseCaseConfig] for the media vertical.
 * [deleteDocumentUseCase] takes the same [DeleteMediaUseCase] bean
 * [MediaUseCaseConfig] already defines - reused wholesale, not
 * reconstructed, so document deletion always gets that use case's full
 * validation for free.
 */
@Configuration
class DocumentUseCaseConfig {

    @Bean
    fun attachDocumentUseCase(
        salonRepository: SalonRepository,
        mediaAssetRepository: MediaAssetRepository,
        documentRepository: SalonDocumentRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = AttachDocumentUseCase(salonRepository, mediaAssetRepository, documentRepository, salonPermissionResolver)

    @Bean
    fun listDocumentsUseCase(
        salonRepository: SalonRepository,
        documentRepository: SalonDocumentRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = ListDocumentsUseCase(salonRepository, documentRepository, salonPermissionResolver)

    @Bean
    fun getDocumentUseCase(
        salonRepository: SalonRepository,
        documentRepository: SalonDocumentRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = GetDocumentUseCase(salonRepository, documentRepository, salonPermissionResolver)

    @Bean
    fun getDocumentAccessUrlUseCase(
        salonRepository: SalonRepository,
        documentRepository: SalonDocumentRepository,
        mediaAssetRepository: MediaAssetRepository,
        salonPermissionResolver: SalonPermissionResolver,
        mediaStoragePort: MediaStoragePort,
    ) = GetDocumentAccessUrlUseCase(salonRepository, documentRepository, mediaAssetRepository, salonPermissionResolver, mediaStoragePort)

    @Bean
    fun deleteDocumentUseCase(
        salonRepository: SalonRepository,
        documentRepository: SalonDocumentRepository,
        salonPermissionResolver: SalonPermissionResolver,
        deleteMediaUseCase: DeleteMediaUseCase,
    ) = DeleteDocumentUseCase(salonRepository, documentRepository, salonPermissionResolver, deleteMediaUseCase)

    @Bean
    fun approveSalonDocumentUseCase(
        documentRepository: SalonDocumentRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = ApproveSalonDocumentUseCase(documentRepository, platformAuthorization)

    @Bean
    fun rejectSalonDocumentUseCase(
        documentRepository: SalonDocumentRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = RejectSalonDocumentUseCase(documentRepository, platformAuthorization)

    @Bean
    fun listSalonDocumentsForPlatformUseCase(
        salonRepository: SalonRepository,
        documentRepository: SalonDocumentRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = ListSalonDocumentsForPlatformUseCase(salonRepository, documentRepository, platformAuthorization)
}
