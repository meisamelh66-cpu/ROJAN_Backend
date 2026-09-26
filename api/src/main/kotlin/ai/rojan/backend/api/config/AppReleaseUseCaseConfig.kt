package ai.rojan.backend.api.config

import ai.rojan.backend.application.apprelease.ActivateAppReleaseUseCase
import ai.rojan.backend.application.apprelease.CreateAppReleaseUseCase
import ai.rojan.backend.application.apprelease.DeactivateAppReleaseUseCase
import ai.rojan.backend.application.apprelease.GetLatestAppReleaseUseCase
import ai.rojan.backend.application.apprelease.ListAppReleasesForAdminUseCase
import ai.rojan.backend.application.apprelease.UpdateAppReleaseUseCase
import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.domain.apprelease.AppReleaseRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires framework-free App Release Management application use cases as Spring beans, mirroring [BannerUseCaseConfig]. [platformAuthorizationResolver] resolves to [PlatformAuthorityUseCaseConfig]'s existing bean. */
@Configuration
class AppReleaseUseCaseConfig {

    @Bean
    fun createAppReleaseUseCase(
        appReleaseRepository: AppReleaseRepository,
        platformAuthorizationResolver: PlatformAuthorizationResolver,
    ) = CreateAppReleaseUseCase(appReleaseRepository, platformAuthorizationResolver)

    @Bean
    fun updateAppReleaseUseCase(
        appReleaseRepository: AppReleaseRepository,
        platformAuthorizationResolver: PlatformAuthorizationResolver,
    ) = UpdateAppReleaseUseCase(appReleaseRepository, platformAuthorizationResolver)

    @Bean
    fun activateAppReleaseUseCase(
        appReleaseRepository: AppReleaseRepository,
        platformAuthorizationResolver: PlatformAuthorizationResolver,
    ) = ActivateAppReleaseUseCase(appReleaseRepository, platformAuthorizationResolver)

    @Bean
    fun deactivateAppReleaseUseCase(
        appReleaseRepository: AppReleaseRepository,
        platformAuthorizationResolver: PlatformAuthorizationResolver,
    ) = DeactivateAppReleaseUseCase(appReleaseRepository, platformAuthorizationResolver)

    @Bean
    fun listAppReleasesForAdminUseCase(
        appReleaseRepository: AppReleaseRepository,
        platformAuthorizationResolver: PlatformAuthorizationResolver,
    ) = ListAppReleasesForAdminUseCase(appReleaseRepository, platformAuthorizationResolver)

    @Bean
    fun getLatestAppReleaseUseCase(appReleaseRepository: AppReleaseRepository) =
        GetLatestAppReleaseUseCase(appReleaseRepository)
}
