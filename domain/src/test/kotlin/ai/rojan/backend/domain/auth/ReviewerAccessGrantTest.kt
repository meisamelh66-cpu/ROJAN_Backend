package ai.rojan.backend.domain.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ReviewerAccessGrantTest {

    private fun grant(active: Boolean, expiresAt: Instant) = ReviewerAccessGrant(
        phoneNumber = PhoneNumber("+989164987585"),
        codeHash = "irrelevant-for-this-test",
        activatedAt = expiresAt.minusSeconds(604_800), // 7 days before expiry
        expiresAt = expiresAt,
        active = active,
    )

    @Test
    fun `is usable when active and before its expiry`() {
        val now = Instant.now()
        assertTrue(grant(active = true, expiresAt = now.plusSeconds(60)).isUsableAt(now))
    }

    @Test
    fun `is not usable once past its expiry, even while still flagged active`() {
        val now = Instant.now()
        assertFalse(grant(active = true, expiresAt = now.minusSeconds(1)).isUsableAt(now))
    }

    @Test
    fun `is not usable once deactivated, even before its expiry`() {
        val now = Instant.now()
        assertFalse(grant(active = false, expiresAt = now.plusSeconds(60)).isUsableAt(now))
    }

    @Test
    fun `is not usable at the exact expiry instant`() {
        val now = Instant.now()
        assertFalse(grant(active = true, expiresAt = now).isUsableAt(now))
    }
}
