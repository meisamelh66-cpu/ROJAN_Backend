package ai.rojan.backend.domain.salon

import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

private fun newSalon(onboardingStatus: SalonOnboardingStatus = SalonOnboardingStatus.DRAFT): Salon =
    Salon.create(
        ownerId = UserId.new(),
        name = "Glow Salon",
        description = null,
        phone = "+1 555 0100",
        email = null,
        address = "1 Main St",
        onboardingStatus = onboardingStatus,
    )

class SalonTest {

    @Test
    fun `create defaults onboarding status to active for backward compatibility`() {
        val salon = Salon.create(UserId.new(), "Glow Salon", null, "+1 555 0100", null, "1 Main St")
        assertEquals(SalonOnboardingStatus.ACTIVE, salon.onboardingStatus)
    }

    @Test
    fun `activate transitions a draft salon to active`() {
        val salon = newSalon(SalonOnboardingStatus.DRAFT)

        salon.activate()

        assertEquals(SalonOnboardingStatus.ACTIVE, salon.onboardingStatus)
    }

    @Test
    fun `activate is idempotent for an already active salon`() {
        val salon = newSalon(SalonOnboardingStatus.ACTIVE)

        salon.activate()

        assertEquals(SalonOnboardingStatus.ACTIVE, salon.onboardingStatus)
    }

    @Test
    fun `updateProfile sets logo and coordinates`() {
        val salon = newSalon()

        salon.updateProfile("https://example.com/logo.png", 35.6892, 51.3890)

        assertEquals("https://example.com/logo.png", salon.logoUrl)
        assertEquals(35.6892, salon.latitude)
        assertEquals(51.3890, salon.longitude)
    }

    @Test
    fun `updateProfile rejects an out-of-range latitude`() {
        val salon = newSalon()
        assertThrows(IllegalArgumentException::class.java) {
            salon.updateProfile(null, 90.1, null)
        }
    }

    @Test
    fun `updateProfile rejects an out-of-range longitude`() {
        val salon = newSalon()
        assertThrows(IllegalArgumentException::class.java) {
            salon.updateProfile(null, null, -180.1)
        }
    }

    @Test
    fun `create rejects an out-of-range latitude`() {
        assertThrows(IllegalArgumentException::class.java) {
            Salon.create(UserId.new(), "Glow Salon", null, "+1 555 0100", null, "1 Main St", latitude = 91.0)
        }
    }
}
