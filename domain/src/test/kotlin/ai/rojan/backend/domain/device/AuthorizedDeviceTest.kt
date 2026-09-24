package ai.rojan.backend.domain.device

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class AuthorizedDeviceTest {

    private val userId = UserId.new()
    private val salonId = SalonId.new()

    @Test
    fun `register produces an unrevoked device with registeredAt and lastSeenAt both set`() {
        val device = AuthorizedDevice.register(userId, salonId, "device-x", "fp", "install")

        assertEquals(userId, device.userId)
        assertEquals(salonId, device.salonId)
        assertEquals("device-x", device.deviceId)
        assertEquals(device.registeredAt, device.lastSeenAt)
        assertNull(device.revokedAt)
        assertFalse(device.isRevoked)
    }

    @Test
    fun `register rejects a blank deviceId`() {
        assertThrows<IllegalArgumentException> {
            AuthorizedDevice.register(userId, salonId, "   ", null, null)
        }
    }

    @Test
    fun `register trims blank fingerprint and installationId to null`() {
        val device = AuthorizedDevice.register(userId, salonId, "device-y", "  ", "  ")

        assertNull(device.fingerprint)
        assertNull(device.installationId)
    }

    @Test
    fun `recordSeen advances lastSeenAt on a non-revoked device`() {
        val device = AuthorizedDevice.register(userId, salonId, "device-z", null, null)
        val before = device.lastSeenAt

        Thread.sleep(5)
        device.recordSeen()

        assertTrue(device.lastSeenAt!!.isAfter(before))
    }

    @Test
    fun `recordSeen throws on an already-revoked device - lastSeenAt must never advance`() {
        val revoked = AuthorizedDevice.reconstitute(
            id = AuthorizedDeviceId.new(),
            userId = userId,
            salonId = salonId,
            deviceId = "device-revoked",
            fingerprint = null,
            installationId = null,
            registeredAt = Instant.now(),
            lastSeenAt = Instant.now(),
            revokedAt = Instant.now(),
        )

        assertThrows<IllegalStateException> { revoked.recordSeen() }
        assertTrue(revoked.isRevoked)
    }
}
