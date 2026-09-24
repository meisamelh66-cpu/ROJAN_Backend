package ai.rojan.backend.application.device

import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.device.AuthorizedDevice
import ai.rojan.backend.domain.device.AuthorizedDeviceRepository
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** In-memory fake, mirroring this module's existing fake-repository style (see `ai.rojan.backend.application.salon.InMemorySalonRepository`). */
private class InMemoryAuthorizedDeviceRepository : AuthorizedDeviceRepository {
    private val store = mutableMapOf<Any, AuthorizedDevice>()
    override fun save(device: AuthorizedDevice): AuthorizedDevice = device.also { store[it.id] = it }
    override fun findByUserIdAndSalonIdAndDeviceId(userId: UserId, salonId: SalonId, deviceId: String): AuthorizedDevice? =
        store.values.find { it.userId == userId && it.salonId == salonId && it.deviceId == deviceId }
}

class RegisterDeviceUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val permissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val deviceRepository = InMemoryAuthorizedDeviceRepository()
    private val useCase = RegisterDeviceUseCase(permissionResolver, deviceRepository)

    private val owner = UserId.new()
    private val salon: Salon = Salon.create(
        ownerId = owner,
        name = "Test Salon",
        description = null,
        phone = "+989120000000",
        email = null,
        address = "Test Address",
    ).also { salonRepository.save(it) }

    @Test
    fun `authorized owner can register a device`() {
        val result = useCase.execute(RegisterDeviceCommand(owner, salon.id, "device-1", "fp-1", "install-1"))

        assertEquals(owner, result.userId)
        assertEquals(salon.id, result.salonId)
        assertEquals("device-1", result.deviceId)
        assertNotNull(result.registeredAt)
        assertNotNull(result.lastSeenAt)
        assertNull(result.revokedAt)
        assertFalse(result.isRevoked)
    }

    @Test
    fun `authorized manager member can register only if existing membership permissions allow it`() {
        val manager = UserId.new()
        membershipRepository.assign(salon.id, manager, SalonRole.MANAGER)

        val result = useCase.execute(RegisterDeviceCommand(manager, salon.id, "device-2", null, null))

        assertEquals(manager, result.userId)
        assertEquals(salon.id, result.salonId)
    }

    @Test
    fun `a receptionist member can also register - Reception is not owner-only`() {
        val receptionist = UserId.new()
        membershipRepository.assign(salon.id, receptionist, SalonRole.RECEPTIONIST)

        val result = useCase.execute(RegisterDeviceCommand(receptionist, salon.id, "device-3", null, null))

        assertEquals(receptionist, result.userId)
    }

    @Test
    fun `caller with no membership and no ownership at the salon is rejected`() {
        val stranger = UserId.new()

        assertThrows<SalonAccessDeniedException> {
            useCase.execute(RegisterDeviceCommand(stranger, salon.id, "device-4", null, null))
        }
    }

    @Test
    fun `registration for a nonexistent salon is rejected the same way as unauthorized access`() {
        val someone = UserId.new()

        assertThrows<SalonAccessDeniedException> {
            useCase.execute(RegisterDeviceCommand(someone, SalonId.new(), "device-5", null, null))
        }
    }

    @Test
    fun `blank deviceId is rejected`() {
        assertThrows<IllegalArgumentException> {
            useCase.execute(RegisterDeviceCommand(owner, salon.id, "   ", null, null))
        }
    }

    @Test
    fun `duplicate registration for the same user, salon and device is idempotent - no second record, lastSeenAt advances`() {
        val first = useCase.execute(RegisterDeviceCommand(owner, salon.id, "device-6", "fp", "install"))
        val firstSeenAt = first.lastSeenAt

        Thread.sleep(5)
        val second = useCase.execute(RegisterDeviceCommand(owner, salon.id, "device-6", "fp", "install"))

        assertEquals(first.id, second.id, "must be the same row, not a duplicate")
        assertEquals(first.registeredAt, second.registeredAt, "registeredAt must never change on a repeat registration")
        assertNotNull(second.lastSeenAt)
        assertTrue(second.lastSeenAt!!.isAfter(firstSeenAt), "lastSeenAt must advance on a heartbeat")
    }

    @Test
    fun `a revoked device is not silently revived by presenting the same identity again`() {
        val registered = useCase.execute(RegisterDeviceCommand(owner, salon.id, "device-7", null, null))
        val revokedAt = java.time.Instant.now()
        val revoked = AuthorizedDevice.reconstitute(
            id = registered.id,
            userId = registered.userId,
            salonId = registered.salonId,
            deviceId = registered.deviceId,
            fingerprint = registered.fingerprint,
            installationId = registered.installationId,
            registeredAt = registered.registeredAt,
            lastSeenAt = registered.lastSeenAt,
            revokedAt = revokedAt,
        )
        deviceRepository.save(revoked)

        val result = useCase.execute(RegisterDeviceCommand(owner, salon.id, "device-7", null, null))

        assertEquals(revokedAt, result.revokedAt, "revokedAt must be untouched, never cleared")
        assertTrue(result.isRevoked)
    }

    @Test
    fun `the same deviceId for a different salon by the same user creates a second, independent record - never a reassignment`() {
        val secondSalon = Salon.create(
            ownerId = owner,
            name = "Second Salon",
            description = null,
            phone = "+989120000001",
            email = null,
            address = "Second Address",
        ).also { salonRepository.save(it) }

        val first = useCase.execute(RegisterDeviceCommand(owner, salon.id, "shared-device", null, null))
        val second = useCase.execute(RegisterDeviceCommand(owner, secondSalon.id, "shared-device", null, null))

        assertNotEquals(first.id, second.id)
        assertEquals(salon.id, first.salonId)
        assertEquals(secondSalon.id, second.salonId)
        assertFalse(first.isRevoked)
        assertFalse(second.isRevoked)
    }

    @Test
    fun `two different users registering the same deviceId for the same salon never collide or leak into each other`() {
        val otherManager = UserId.new()
        membershipRepository.assign(salon.id, otherManager, SalonRole.MANAGER)

        val ownerDevice = useCase.execute(RegisterDeviceCommand(owner, salon.id, "shared-pc-device", null, null))
        val managerDevice = useCase.execute(RegisterDeviceCommand(otherManager, salon.id, "shared-pc-device", null, null))

        assertNotEquals(ownerDevice.id, managerDevice.id)
        assertEquals(owner, ownerDevice.userId)
        assertEquals(otherManager, managerDevice.userId)
    }

    @Test
    fun `a manager cannot attach a device to a salon they have no membership at`() {
        val otherSalon = Salon.create(
            ownerId = UserId.new(),
            name = "Someone Else's Salon",
            description = null,
            phone = "+989120000002",
            email = null,
            address = "Elsewhere",
        ).also { salonRepository.save(it) }

        val manager = UserId.new()
        membershipRepository.assign(salon.id, manager, SalonRole.MANAGER)

        assertThrows<SalonAccessDeniedException> {
            useCase.execute(RegisterDeviceCommand(manager, otherSalon.id, "device-8", null, null))
        }
    }
}
