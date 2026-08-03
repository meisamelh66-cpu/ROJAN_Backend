package ai.rojan.backend.api.config

import ai.rojan.backend.application.salon.CreateBranchUseCase
import ai.rojan.backend.application.salon.CreateSalonUseCase
import ai.rojan.backend.application.salon.CreateServiceCategoryUseCase
import ai.rojan.backend.application.salon.CreateServiceUseCase
import ai.rojan.backend.application.salon.CreateSpecialistUseCase
import ai.rojan.backend.application.salon.DeactivateBranchUseCase
import ai.rojan.backend.application.salon.DeactivateSalonUseCase
import ai.rojan.backend.application.salon.DeactivateServiceCategoryUseCase
import ai.rojan.backend.application.salon.DeactivateServiceUseCase
import ai.rojan.backend.application.salon.DeactivateSpecialistUseCase
import ai.rojan.backend.application.salon.UpdateBranchUseCase
import ai.rojan.backend.application.salon.UpdateSalonUseCase
import ai.rojan.backend.application.salon.UpdateServiceCategoryUseCase
import ai.rojan.backend.application.salon.UpdateServiceUseCase
import ai.rojan.backend.application.salon.UpdateSpecialistUseCase
import ai.rojan.backend.domain.salon.BranchRepository
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.salon.ServiceCategoryRepository
import ai.rojan.backend.domain.salon.ServiceRepository
import ai.rojan.backend.domain.salon.SpecialistRepository
import ai.rojan.backend.domain.user.UserRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires framework-free salon-management application use cases as Spring
 * beans, mirroring [UseCaseConfig] for the auth vertical.
 */
@Configuration
class SalonUseCaseConfig {

    @Bean
    fun createSalonUseCase(salonRepository: SalonRepository) =
        CreateSalonUseCase(salonRepository)

    @Bean
    fun updateSalonUseCase(salonRepository: SalonRepository) =
        UpdateSalonUseCase(salonRepository)

    @Bean
    fun deactivateSalonUseCase(salonRepository: SalonRepository) =
        DeactivateSalonUseCase(salonRepository)

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
    ) = CreateServiceCategoryUseCase(salonRepository, serviceCategoryRepository)

    @Bean
    fun updateServiceCategoryUseCase(
        salonRepository: SalonRepository,
        serviceCategoryRepository: ServiceCategoryRepository,
    ) = UpdateServiceCategoryUseCase(salonRepository, serviceCategoryRepository)

    @Bean
    fun deactivateServiceCategoryUseCase(
        salonRepository: SalonRepository,
        serviceCategoryRepository: ServiceCategoryRepository,
    ) = DeactivateServiceCategoryUseCase(salonRepository, serviceCategoryRepository)

    @Bean
    fun createServiceUseCase(
        salonRepository: SalonRepository,
        serviceCategoryRepository: ServiceCategoryRepository,
        serviceRepository: ServiceRepository,
    ) = CreateServiceUseCase(salonRepository, serviceCategoryRepository, serviceRepository)

    @Bean
    fun updateServiceUseCase(salonRepository: SalonRepository, serviceRepository: ServiceRepository) =
        UpdateServiceUseCase(salonRepository, serviceRepository)

    @Bean
    fun deactivateServiceUseCase(salonRepository: SalonRepository, serviceRepository: ServiceRepository) =
        DeactivateServiceUseCase(salonRepository, serviceRepository)

    @Bean
    fun createSpecialistUseCase(
        salonRepository: SalonRepository,
        specialistRepository: SpecialistRepository,
        userRepository: UserRepository,
    ) = CreateSpecialistUseCase(salonRepository, specialistRepository, userRepository)

    @Bean
    fun updateSpecialistUseCase(salonRepository: SalonRepository, specialistRepository: SpecialistRepository) =
        UpdateSpecialistUseCase(salonRepository, specialistRepository)

    @Bean
    fun deactivateSpecialistUseCase(salonRepository: SalonRepository, specialistRepository: SpecialistRepository) =
        DeactivateSpecialistUseCase(salonRepository, specialistRepository)
}
