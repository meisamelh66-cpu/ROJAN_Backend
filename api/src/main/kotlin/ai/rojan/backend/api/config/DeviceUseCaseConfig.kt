package ai.rojan.backend.api.config

import ai.rojan.backend.application.device.RegisterDeviceUseCase
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.device.AuthorizedDeviceRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires the Desktop Device Authorization Foundation (Phase A) application use case as a Spring bean, mirroring [SalonUseCaseConfig]'s own shape. */
@Configuration
class DeviceUseCaseConfig {

    @Bean
    fun registerDeviceUseCase(
        salonPermissionResolver: SalonPermissionResolver,
        deviceRepository: AuthorizedDeviceRepository,
    ) = RegisterDeviceUseCase(salonPermissionResolver, deviceRepository)
}
