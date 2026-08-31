package ai.rojan.backend.api.config

import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.application.website.GetPublicWebsiteUseCase
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.SpecialistRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires framework-free public-website application use cases as Spring beans. */
@Configuration
class WebsiteUseCaseConfig {

    @Bean
    fun getPublicWebsiteUseCase(
        salonRepository: SalonRepository,
        mediaAssetRepository: MediaAssetRepository,
        serviceRepository: ServiceRepository,
        specialistRepository: SpecialistRepository,
        mediaStoragePort: MediaStoragePort,
    ) = GetPublicWebsiteUseCase(salonRepository, mediaAssetRepository, serviceRepository, specialistRepository, mediaStoragePort)
}
