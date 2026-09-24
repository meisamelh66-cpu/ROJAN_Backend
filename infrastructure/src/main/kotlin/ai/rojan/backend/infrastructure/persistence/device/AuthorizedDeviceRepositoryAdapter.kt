package ai.rojan.backend.infrastructure.persistence.device

import ai.rojan.backend.domain.device.AuthorizedDevice
import ai.rojan.backend.domain.device.AuthorizedDeviceId
import ai.rojan.backend.domain.device.AuthorizedDeviceRepository
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.springframework.stereotype.Repository
import java.time.Instant

/** Repository-pattern adapter: implements the domain [AuthorizedDeviceRepository] port on top of Spring Data JPA. */
@Repository
class AuthorizedDeviceRepositoryAdapter(
    private val jpaRepository: AuthorizedDeviceSpringDataRepository,
) : AuthorizedDeviceRepository {

    override fun save(device: AuthorizedDevice): AuthorizedDevice {
        val entity = jpaRepository.findById(device.id.value).orElse(null)
            ?.apply {
                lastSeenAt = device.lastSeenAt
                revokedAt = device.revokedAt
            }
            ?: AuthorizedDeviceJpaEntity(
                id = device.id.value,
                userId = device.userId.value,
                salonId = device.salonId.value,
                deviceId = device.deviceId,
                fingerprint = device.fingerprint,
                installationId = device.installationId,
                lastSeenAt = device.lastSeenAt,
                revokedAt = device.revokedAt,
            )
        return jpaRepository.save(entity).toDomain()
    }

    override fun findByUserIdAndSalonIdAndDeviceId(userId: UserId, salonId: SalonId, deviceId: String): AuthorizedDevice? =
        jpaRepository.findByUserIdAndSalonIdAndDeviceId(userId.value, salonId.value, deviceId)?.toDomain()

    private fun AuthorizedDeviceJpaEntity.toDomain(): AuthorizedDevice = AuthorizedDevice.reconstitute(
        id = AuthorizedDeviceId(id),
        userId = UserId(userId),
        salonId = SalonId(salonId),
        deviceId = deviceId,
        fingerprint = fingerprint,
        installationId = installationId,
        registeredAt = registeredAt ?: Instant.EPOCH,
        lastSeenAt = lastSeenAt,
        revokedAt = revokedAt,
    )
}
