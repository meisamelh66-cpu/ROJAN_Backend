package ai.rojan.backend.api.config

import ai.rojan.backend.application.banner.DeleteBannerUseCase
import ai.rojan.backend.application.banner.ListActiveBannersUseCase
import ai.rojan.backend.application.banner.ListBannersForAdminUseCase
import ai.rojan.backend.application.banner.ReorderBannersUseCase
import ai.rojan.backend.application.banner.ReplaceBannerImageUseCase
import ai.rojan.backend.application.banner.UpdateBannerMetadataUseCase
import ai.rojan.backend.application.banner.UploadBannerUseCase
import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.domain.banner.BannerRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires framework-free Banner Management application use cases as Spring beans, mirroring [MediaUseCaseConfig]/[PlatformAuthorityUseCaseConfig]. [platformAuthorizationResolver] resolves to [PlatformAuthorityUseCaseConfig]'s existing bean. */
@Configuration
class BannerUseCaseConfig {

    @Bean
    fun uploadBannerUseCase(
        bannerRepository: BannerRepository,
        mediaStoragePort: MediaStoragePort,
        platformAuthorizationResolver: PlatformAuthorizationResolver,
    ) = UploadBannerUseCase(bannerRepository, mediaStoragePort, platformAuthorizationResolver)

    @Bean
    fun listBannersForAdminUseCase(
        bannerRepository: BannerRepository,
        platformAuthorizationResolver: PlatformAuthorizationResolver,
    ) = ListBannersForAdminUseCase(bannerRepository, platformAuthorizationResolver)

    @Bean
    fun listActiveBannersUseCase(bannerRepository: BannerRepository) =
        ListActiveBannersUseCase(bannerRepository)

    @Bean
    fun updateBannerMetadataUseCase(
        bannerRepository: BannerRepository,
        platformAuthorizationResolver: PlatformAuthorizationResolver,
    ) = UpdateBannerMetadataUseCase(bannerRepository, platformAuthorizationResolver)

    @Bean
    fun replaceBannerImageUseCase(
        bannerRepository: BannerRepository,
        mediaStoragePort: MediaStoragePort,
        platformAuthorizationResolver: PlatformAuthorizationResolver,
    ) = ReplaceBannerImageUseCase(bannerRepository, mediaStoragePort, platformAuthorizationResolver)

    @Bean
    fun deleteBannerUseCase(
        bannerRepository: BannerRepository,
        mediaStoragePort: MediaStoragePort,
        platformAuthorizationResolver: PlatformAuthorizationResolver,
    ) = DeleteBannerUseCase(bannerRepository, mediaStoragePort, platformAuthorizationResolver)

    @Bean
    fun reorderBannersUseCase(
        bannerRepository: BannerRepository,
        platformAuthorizationResolver: PlatformAuthorizationResolver,
    ) = ReorderBannersUseCase(bannerRepository, platformAuthorizationResolver)
}
