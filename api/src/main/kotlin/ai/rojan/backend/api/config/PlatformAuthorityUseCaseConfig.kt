package ai.rojan.backend.api.config

import ai.rojan.backend.application.platformauthority.CreatePlatformReviewerUseCase
import ai.rojan.backend.application.platformauthority.DeactivatePlatformCustomerAccountUseCase
import ai.rojan.backend.application.platformauthority.DeactivatePlatformManagerUseCase
import ai.rojan.backend.application.platformauthority.DeactivatePlatformReviewerUseCase
import ai.rojan.backend.application.platformauthority.ListPlatformCustomerAccountsUseCase
import ai.rojan.backend.application.platformauthority.ListPlatformManagersUseCase
import ai.rojan.backend.application.platformauthority.ListPlatformReviewersUseCase
import ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver
import ai.rojan.backend.application.platformauthority.ReactivatePlatformCustomerAccountUseCase
import ai.rojan.backend.application.platformauthority.ReactivatePlatformManagerUseCase
import ai.rojan.backend.application.platformauthority.ReactivatePlatformReviewerUseCase
import ai.rojan.backend.domain.salon.SalonMembershipRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires framework-free Platform Authority (Phase 4/5, joined by the Platform Management API
 * Contract's Manager/Customer beans) application use cases as Spring beans, mirroring
 * [SalonUseCaseConfig] for the salon vertical. [platformAuthorizationResolver] is also consumed by
 * [VerificationUseCaseConfig]'s and [DocumentUseCaseConfig]'s Phase 5 beans - defined once here
 * rather than duplicated, same as [SalonUseCaseConfig.salonPermissionResolver]'s own cross-config
 * reuse.
 */
@Configuration
class PlatformAuthorityUseCaseConfig {

    @Bean
    fun platformAuthorizationResolver(userRepository: UserRepository) =
        PlatformAuthorizationResolver(userRepository)

    @Bean
    fun createPlatformReviewerUseCase(
        userRepository: UserRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = CreatePlatformReviewerUseCase(userRepository, platformAuthorization)

    @Bean
    fun listPlatformReviewersUseCase(
        userRepository: UserRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = ListPlatformReviewersUseCase(userRepository, platformAuthorization)

    @Bean
    fun deactivatePlatformReviewerUseCase(
        userRepository: UserRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = DeactivatePlatformReviewerUseCase(userRepository, platformAuthorization)

    @Bean
    fun reactivatePlatformReviewerUseCase(
        userRepository: UserRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = ReactivatePlatformReviewerUseCase(userRepository, platformAuthorization)

    @Bean
    fun listPlatformManagersUseCase(
        userRepository: UserRepository,
        salonRepository: SalonRepository,
        salonMembershipRepository: SalonMembershipRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = ListPlatformManagersUseCase(userRepository, salonRepository, salonMembershipRepository, platformAuthorization)

    @Bean
    fun deactivatePlatformManagerUseCase(
        userRepository: UserRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = DeactivatePlatformManagerUseCase(userRepository, platformAuthorization)

    @Bean
    fun reactivatePlatformManagerUseCase(
        userRepository: UserRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = ReactivatePlatformManagerUseCase(userRepository, platformAuthorization)

    @Bean
    fun listPlatformCustomerAccountsUseCase(
        userRepository: UserRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = ListPlatformCustomerAccountsUseCase(userRepository, platformAuthorization)

    @Bean
    fun deactivatePlatformCustomerAccountUseCase(
        userRepository: UserRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = DeactivatePlatformCustomerAccountUseCase(userRepository, platformAuthorization)

    @Bean
    fun reactivatePlatformCustomerAccountUseCase(
        userRepository: UserRepository,
        platformAuthorization: PlatformAuthorizationResolver,
    ) = ReactivatePlatformCustomerAccountUseCase(userRepository, platformAuthorization)
}
