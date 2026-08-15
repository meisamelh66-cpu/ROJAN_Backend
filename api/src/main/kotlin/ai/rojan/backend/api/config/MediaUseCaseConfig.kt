package ai.rojan.backend.api.config

import ai.rojan.backend.application.media.AssignSalonIdentityMediaUseCase
import ai.rojan.backend.application.media.DeleteMediaUseCase
import ai.rojan.backend.application.media.UploadMediaUseCase
import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.salon.SalonRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires framework-free media-management application use cases as Spring
 * beans — Salon Identity Foundation Phase A. Takes the raw
 * `rojan.media.*` values via `@Value` (not the infrastructure-module
 * `MediaProperties` class) — this `api` module deliberately never depends
 * on `infrastructure` (see `api/build.gradle.kts`; only `bootstrap` pulls
 * every module together), same reasoning
 * `generateSalonQrCodeUseCase`/`generateSalonInviteQrCodeUseCase` already
 * inject `rojan.public.base-url` this same way instead of depending on a
 * properties class.
 */
@Configuration
class MediaUseCaseConfig {

    @Bean
    fun uploadMediaUseCase(
        salonRepository: SalonRepository,
        mediaAssetRepository: MediaAssetRepository,
        mediaStoragePort: MediaStoragePort,
        salonPermissionResolver: SalonPermissionResolver,
        @Value("\${rojan.media.allowed-mime-types:image/jpeg,image/png,image/webp}") allowedMimeTypes: Set<String>,
        @Value("\${rojan.media.max-file-size-bytes:5242880}") maxFileSizeBytes: Long,
    ) = UploadMediaUseCase(
        salonRepository,
        mediaAssetRepository,
        mediaStoragePort,
        salonPermissionResolver,
        allowedMimeTypes = allowedMimeTypes,
        maxFileSizeBytes = maxFileSizeBytes,
    )

    @Bean
    fun deleteMediaUseCase(
        mediaAssetRepository: MediaAssetRepository,
        mediaStoragePort: MediaStoragePort,
        salonPermissionResolver: SalonPermissionResolver,
    ) = DeleteMediaUseCase(mediaAssetRepository, mediaStoragePort, salonPermissionResolver)

    @Bean
    fun assignSalonIdentityMediaUseCase(
        salonRepository: SalonRepository,
        mediaAssetRepository: MediaAssetRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = AssignSalonIdentityMediaUseCase(salonRepository, mediaAssetRepository, salonPermissionResolver)
}
