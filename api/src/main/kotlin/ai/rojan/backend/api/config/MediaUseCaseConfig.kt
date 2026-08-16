package ai.rojan.backend.api.config

import ai.rojan.backend.application.media.AssignIdentityMediaUseCase
import ai.rojan.backend.application.media.DeleteMediaUseCase
import ai.rojan.backend.application.media.ListMediaUseCase
import ai.rojan.backend.application.media.UploadMediaUseCase
import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.salon.SalonRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires framework-free Media Foundation (Phase 1) application use cases as
 * Spring beans, mirroring [SalonUseCaseConfig] for the salon-management
 * vertical. [salonPermissionResolver] here resolves to the same bean
 * [SalonUseCaseConfig] already defines - Spring wires by type, not a
 * second instance.
 */
@Configuration
class MediaUseCaseConfig {

    @Bean
    fun uploadMediaUseCase(
        salonRepository: SalonRepository,
        mediaAssetRepository: MediaAssetRepository,
        salonPermissionResolver: SalonPermissionResolver,
        mediaStoragePort: MediaStoragePort,
    ) = UploadMediaUseCase(salonRepository, mediaAssetRepository, salonPermissionResolver, mediaStoragePort)

    @Bean
    fun listMediaUseCase(salonRepository: SalonRepository, mediaAssetRepository: MediaAssetRepository) =
        ListMediaUseCase(salonRepository, mediaAssetRepository)

    @Bean
    fun deleteMediaUseCase(
        salonRepository: SalonRepository,
        mediaAssetRepository: MediaAssetRepository,
        salonPermissionResolver: SalonPermissionResolver,
        mediaStoragePort: MediaStoragePort,
    ) = DeleteMediaUseCase(salonRepository, mediaAssetRepository, salonPermissionResolver, mediaStoragePort)

    @Bean
    fun assignIdentityMediaUseCase(
        salonRepository: SalonRepository,
        mediaAssetRepository: MediaAssetRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = AssignIdentityMediaUseCase(salonRepository, mediaAssetRepository, salonPermissionResolver)
}
