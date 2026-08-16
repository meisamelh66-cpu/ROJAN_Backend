package ai.rojan.backend.domain.verification

import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

private val owner = UserId.new()
private val reviewer = UserId.new()

private fun newVerification() = SalonVerification.create(SalonId.new(), owner)

class SalonVerificationTest {

    @Test
    fun `create defaults a new case to pending`() {
        val verification = newVerification()
        assertEquals(SalonVerificationStatus.PENDING, verification.status)
        assertEquals(owner, verification.submittedBy)
    }

    @Test
    fun `startReview transitions a pending case to under review`() {
        val verification = newVerification()
        verification.startReview(reviewer)
        assertEquals(SalonVerificationStatus.UNDER_REVIEW, verification.status)
        assertEquals(reviewer, verification.reviewedBy)
        assertNotNull(verification.reviewedAt)
    }

    @Test
    fun `startReview rejects a case that is not pending`() {
        val verification = newVerification()
        verification.startReview(reviewer)
        assertThrows(IllegalArgumentException::class.java) { verification.startReview(UserId.new()) }
    }

    @Test
    fun `startReview rejects a reviewer reviewing their own submission`() {
        val verification = newVerification()
        assertThrows(IllegalArgumentException::class.java) { verification.startReview(owner) }
    }

    @Test
    fun `approve transitions an under-review case to approved when all documents are approved`() {
        val verification = newVerification()
        verification.startReview(reviewer)
        verification.approve(reviewer, allDocumentsApproved = true)
        assertEquals(SalonVerificationStatus.APPROVED, verification.status)
    }

    @Test
    fun `approve rejects when not every document is approved`() {
        val verification = newVerification()
        verification.startReview(reviewer)
        assertThrows(IllegalArgumentException::class.java) { verification.approve(reviewer, allDocumentsApproved = false) }
    }

    @Test
    fun `approve rejects a case that is not under review`() {
        val verification = newVerification()
        assertThrows(IllegalArgumentException::class.java) { verification.approve(reviewer, allDocumentsApproved = true) }
    }

    @Test
    fun `approve rejects a reviewer who did not claim this case`() {
        val verification = newVerification()
        verification.startReview(reviewer)
        assertThrows(IllegalArgumentException::class.java) { verification.approve(UserId.new(), allDocumentsApproved = true) }
    }

    @Test
    fun `reject transitions an under-review case to rejected with a reason`() {
        val verification = newVerification()
        verification.startReview(reviewer)
        verification.reject(reviewer, "Missing ownership document")
        assertEquals(SalonVerificationStatus.REJECTED, verification.status)
        assertEquals("Missing ownership document", verification.rejectionReason)
    }

    @Test
    fun `reject requires a non-blank reason`() {
        val verification = newVerification()
        verification.startReview(reviewer)
        assertThrows(IllegalArgumentException::class.java) { verification.reject(reviewer, "  ") }
    }

    @Test
    fun `reject requires a reason of at most 1000 characters`() {
        val verification = newVerification()
        verification.startReview(reviewer)
        assertThrows(IllegalArgumentException::class.java) { verification.reject(reviewer, "x".repeat(1001)) }
    }

    @Test
    fun `reject rejects a case that is not under review`() {
        val verification = newVerification()
        assertThrows(IllegalArgumentException::class.java) { verification.reject(reviewer, "too late") }
    }

    @Test
    fun `reject rejects a reviewer who did not claim this case`() {
        val verification = newVerification()
        verification.startReview(reviewer)
        assertThrows(IllegalArgumentException::class.java) { verification.reject(UserId.new(), "not yours to decide") }
    }

    @Test
    fun `expire transitions an approved case to expired`() {
        val verification = newVerification()
        verification.startReview(reviewer)
        verification.approve(reviewer, allDocumentsApproved = true)
        verification.expire()
        assertEquals(SalonVerificationStatus.EXPIRED, verification.status)
    }

    @Test
    fun `expire rejects a case that is not approved`() {
        val verification = newVerification()
        assertThrows(IllegalArgumentException::class.java) { verification.expire() }
    }

    @Test
    fun `expire rejects an already-rejected case`() {
        val verification = newVerification()
        verification.startReview(reviewer)
        verification.reject(reviewer, "bad docs")
        assertThrows(IllegalArgumentException::class.java) { verification.expire() }
    }
}
