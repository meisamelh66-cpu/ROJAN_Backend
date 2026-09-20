package ai.rojan.backend.api.config

import ai.rojan.backend.application.port.QrCodeGeneratorPort
import ai.rojan.backend.application.port.RateLimiterPort
import ai.rojan.backend.application.salon.AcceptSalonInviteUseCase
import ai.rojan.backend.application.salon.ActivateSalonUseCase
import ai.rojan.backend.application.salon.AssignMembershipUseCase
import ai.rojan.backend.application.salon.AssignServiceToSpecialistUseCase
import ai.rojan.backend.application.salon.ChangeSalonSlugUseCase
import ai.rojan.backend.application.salon.CreateBranchUseCase
import ai.rojan.backend.application.salon.CreateSalonUseCase
import ai.rojan.backend.application.salon.CreateSalonInviteUseCase
import ai.rojan.backend.application.salon.CreateServiceCategoryUseCase
import ai.rojan.backend.application.salon.CreateServiceUseCase
import ai.rojan.backend.application.salon.CreateSpecialistUseCase
import ai.rojan.backend.application.salon.DeactivateBranchUseCase
import ai.rojan.backend.application.salon.DeactivateSalonUseCase
import ai.rojan.backend.application.salon.DeactivateServiceCategoryUseCase
import ai.rojan.backend.application.salon.DeactivateServiceUseCase
import ai.rojan.backend.application.salon.DeactivateSpecialistUseCase
import ai.rojan.backend.application.salon.GenerateSalonInviteQrCodeUseCase
import ai.rojan.backend.application.salon.GenerateSalonQrCodeUseCase
import ai.rojan.backend.application.salon.GetSalonCompletenessUseCase
import ai.rojan.backend.application.salon.GetSalonInviteUseCase
import ai.rojan.backend.application.salon.ListSalonInvitesUseCase
import ai.rojan.backend.application.salon.RemoveMembershipUseCase
import ai.rojan.backend.application.salon.ResolveMySalonAccessUseCase
import ai.rojan.backend.application.salon.RevokeSalonInviteUseCase
import ai.rojan.backend.application.salon.RemoveServiceFromSpecialistUseCase
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.application.salon.UpdateBranchUseCase
import ai.rojan.backend.application.salon.UpdateSalonCompletionProfileUseCase
import ai.rojan.backend.application.salon.UpdateSalonUseCase
import ai.rojan.backend.application.salon.UpdateServiceCategoryUseCase
import ai.rojan.backend.application.salon.UpdateServiceUseCase
import ai.rojan.backend.application.salon.UpdateSpecialistUseCase
import ai.rojan.backend.domain.salon.BranchRepository
import ai.rojan.backend.domain.salon.SalonInviteRepository
import ai.rojan.backend.domain.salon.SalonMembershipRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceCategoryRepository
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.salon.SpecialistServiceRepository
import ai.rojan.backend.domain.schedule.WorkingHoursRepository
import ai.rojan.backend.domain.user.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires framework-free salon-management application use cases as Spring
 * beans, mirroring [UseCaseConfig] for the auth vertical.
 */
@Configuration
class SalonUseCaseConfig {

    @Bean
    fun salonPermissionResolver(
        salonRepository: SalonRepository,
        membershipRepository: SalonMembershipRepository,
        specialistRepository: SpecialistRepository,
    ) = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)

    @Bean
    fun createSalonUseCase(salonRepository: SalonRepository) =
        CreateSalonUseCase(salonRepository)

    @Bean
    fun updateSalonUseCase(salonRepository: SalonRepository, salonPermissionResolver: SalonPermissionResolver) =
        UpdateSalonUseCase(salonRepository, salonPermissionResolver)

    @Bean
    fun deactivateSalonUseCase(salonRepository: SalonRepository, salonPermissionResolver: SalonPermissionResolver) =
        DeactivateSalonUseCase(salonRepository, salonPermissionResolver)

    @Bean
    fun changeSalonSlugUseCase(salonRepository: SalonRepository, salonPermissionResolver: SalonPermissionResolver) =
        ChangeSalonSlugUseCase(salonRepository, salonPermissionResolver)

    @Bean
    fun assignMembershipUseCase(
        salonRepository: SalonRepository,
        userRepository: UserRepository,
        membershipRepository: SalonMembershipRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = AssignMembershipUseCase(salonRepository, userRepository, membershipRepository, salonPermissionResolver)

    @Bean
    fun removeMembershipUseCase(
        salonRepository: SalonRepository,
        membershipRepository: SalonMembershipRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = RemoveMembershipUseCase(salonRepository, membershipRepository, salonPermissionResolver)

    @Bean
    fun createBranchUseCase(salonRepository: SalonRepository, branchRepository: BranchRepository) =
        CreateBranchUseCase(salonRepository, branchRepository)

    @Bean
    fun updateBranchUseCase(salonRepository: SalonRepository, branchRepository: BranchRepository) =
        UpdateBranchUseCase(salonRepository, branchRepository)

    @Bean
    fun deactivateBranchUseCase(salonRepository: SalonRepository, branchRepository: BranchRepository) =
        DeactivateBranchUseCase(salonRepository, branchRepository)

    @Bean
    fun createServiceCategoryUseCase(
        salonRepository: SalonRepository,
        serviceCategoryRepository: ServiceCategoryRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = CreateServiceCategoryUseCase(salonRepository, serviceCategoryRepository, salonPermissionResolver)

    @Bean
    fun updateServiceCategoryUseCase(
        salonRepository: SalonRepository,
        serviceCategoryRepository: ServiceCategoryRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = UpdateServiceCategoryUseCase(salonRepository, serviceCategoryRepository, salonPermissionResolver)

    @Bean
    fun deactivateServiceCategoryUseCase(
        salonRepository: SalonRepository,
        serviceCategoryRepository: ServiceCategoryRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = DeactivateServiceCategoryUseCase(salonRepository, serviceCategoryRepository, salonPermissionResolver)

    @Bean
    fun createServiceUseCase(
        salonRepository: SalonRepository,
        serviceCategoryRepository: ServiceCategoryRepository,
        serviceRepository: ServiceRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = CreateServiceUseCase(salonRepository, serviceCategoryRepository, serviceRepository, salonPermissionResolver)

    @Bean
    fun updateServiceUseCase(salonRepository: SalonRepository, serviceRepository: ServiceRepository, salonPermissionResolver: SalonPermissionResolver) =
        UpdateServiceUseCase(salonRepository, serviceRepository, salonPermissionResolver)

    @Bean
    fun deactivateServiceUseCase(salonRepository: SalonRepository, serviceRepository: ServiceRepository, salonPermissionResolver: SalonPermissionResolver) =
        DeactivateServiceUseCase(salonRepository, serviceRepository, salonPermissionResolver)

    @Bean
    fun createSpecialistUseCase(
        salonRepository: SalonRepository,
        specialistRepository: SpecialistRepository,
        userRepository: UserRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = CreateSpecialistUseCase(salonRepository, specialistRepository, userRepository, salonPermissionResolver)

    @Bean
    fun updateSpecialistUseCase(salonRepository: SalonRepository, specialistRepository: SpecialistRepository, salonPermissionResolver: SalonPermissionResolver) =
        UpdateSpecialistUseCase(salonRepository, specialistRepository, salonPermissionResolver)

    @Bean
    fun deactivateSpecialistUseCase(salonRepository: SalonRepository, specialistRepository: SpecialistRepository, salonPermissionResolver: SalonPermissionResolver) =
        DeactivateSpecialistUseCase(salonRepository, specialistRepository, salonPermissionResolver)

    @Bean
    fun assignServiceToSpecialistUseCase(
        specialistRepository: SpecialistRepository,
        serviceRepository: ServiceRepository,
        specialistServiceRepository: SpecialistServiceRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = AssignServiceToSpecialistUseCase(specialistRepository, serviceRepository, specialistServiceRepository, salonPermissionResolver)

    @Bean
    fun removeServiceFromSpecialistUseCase(
        specialistRepository: SpecialistRepository,
        serviceRepository: ServiceRepository,
        specialistServiceRepository: SpecialistServiceRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = RemoveServiceFromSpecialistUseCase(specialistRepository, serviceRepository, specialistServiceRepository, salonPermissionResolver)

    @Bean
    fun activateSalonUseCase(
        salonRepository: SalonRepository,
        serviceRepository: ServiceRepository,
        specialistRepository: SpecialistRepository,
        workingHoursRepository: WorkingHoursRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = ActivateSalonUseCase(salonRepository, serviceRepository, specialistRepository, workingHoursRepository, salonPermissionResolver)

    @Bean
    fun generateSalonQrCodeUseCase(
        salonRepository: SalonRepository,
        qrCodeGeneratorPort: QrCodeGeneratorPort,
        salonPermissionResolver: SalonPermissionResolver,
        @Value("\${rojan.public.base-url:https://app.rojan.ai}") publicBaseUrl: String,
    ) = GenerateSalonQrCodeUseCase(salonRepository, qrCodeGeneratorPort, salonPermissionResolver, publicBaseUrl)

    @Bean
    fun createSalonInviteUseCase(
        salonRepository: SalonRepository,
        salonInviteRepository: SalonInviteRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = CreateSalonInviteUseCase(salonRepository, salonInviteRepository, salonPermissionResolver)

    @Bean
    fun listSalonInvitesUseCase(
        salonRepository: SalonRepository,
        salonInviteRepository: SalonInviteRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = ListSalonInvitesUseCase(salonRepository, salonInviteRepository, salonPermissionResolver)

    @Bean
    fun revokeSalonInviteUseCase(
        salonRepository: SalonRepository,
        salonInviteRepository: SalonInviteRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = RevokeSalonInviteUseCase(salonRepository, salonInviteRepository, salonPermissionResolver)

    @Bean
    fun generateSalonInviteQrCodeUseCase(
        salonRepository: SalonRepository,
        salonInviteRepository: SalonInviteRepository,
        qrCodeGeneratorPort: QrCodeGeneratorPort,
        salonPermissionResolver: SalonPermissionResolver,
        @Value("\${rojan.public.base-url:https://app.rojan.ai}") publicBaseUrl: String,
    ) = GenerateSalonInviteQrCodeUseCase(salonRepository, salonInviteRepository, qrCodeGeneratorPort, salonPermissionResolver, publicBaseUrl)

    @Bean
    fun getSalonInviteUseCase(salonRepository: SalonRepository, salonInviteRepository: SalonInviteRepository) =
        GetSalonInviteUseCase(salonRepository, salonInviteRepository)

    @Bean
    fun acceptSalonInviteUseCase(
        salonInviteRepository: SalonInviteRepository,
        membershipRepository: SalonMembershipRepository,
        rateLimiter: RateLimiterPort,
    ) = AcceptSalonInviteUseCase(salonInviteRepository, membershipRepository, rateLimiter)

    @Bean
    fun resolveMySalonAccessUseCase(
        salonRepository: SalonRepository,
        membershipRepository: SalonMembershipRepository,
        specialistRepository: SpecialistRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = ResolveMySalonAccessUseCase(salonRepository, membershipRepository, specialistRepository, salonPermissionResolver)

    @Bean
    fun getSalonCompletenessUseCase(
        salonRepository: SalonRepository,
        serviceRepository: ServiceRepository,
        specialistRepository: SpecialistRepository,
        workingHoursRepository: WorkingHoursRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = GetSalonCompletenessUseCase(salonRepository, serviceRepository, specialistRepository, workingHoursRepository, salonPermissionResolver)

    @Bean
    fun updateSalonCompletionProfileUseCase(
        salonRepository: SalonRepository,
        membershipRepository: SalonMembershipRepository,
        salonPermissionResolver: SalonPermissionResolver,
    ) = UpdateSalonCompletionProfileUseCase(salonRepository, membershipRepository, salonPermissionResolver)
}
