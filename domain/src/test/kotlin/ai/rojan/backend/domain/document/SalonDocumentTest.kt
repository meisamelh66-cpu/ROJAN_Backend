package ai.rojan.backend.domain.document

import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

private fun newDocument(documentType: DocumentType = DocumentType.LICENSE) = SalonDocument.create(
    salonId = SalonId.new(),
    mediaAssetId = MediaAssetId.new(),
    documentType = documentType,
    expiryDate = null,
    uploadedBy = UserId.new(),
)

class SalonDocumentTest {

    @Test
    fun `create defaults a new document to pending`() {
        val document = newDocument()
        assertEquals(DocumentVerificationStatus.PENDING, document.verificationStatus)
    }

    @Test
    fun `approve transitions a pending document to approved`() {
        val document = newDocument()
        document.approve()
        assertEquals(DocumentVerificationStatus.APPROVED, document.verificationStatus)
    }

    @Test
    fun `reject transitions a pending document to rejected and records nothing without a reason`() {
        val document = newDocument()
        document.reject("Illegible scan")
        assertEquals(DocumentVerificationStatus.REJECTED, document.verificationStatus)
    }

    @Test
    fun `reject requires a non-blank reason`() {
        val document = newDocument()
        assertThrows(IllegalArgumentException::class.java) { document.reject("  ") }
    }

    @Test
    fun `expire transitions an approved document to expired`() {
        val document = newDocument()
        document.approve()
        document.expire()
        assertEquals(DocumentVerificationStatus.EXPIRED, document.verificationStatus)
    }

    @Test
    fun `approve rejects a document that is not pending`() {
        val document = newDocument()
        document.approve()
        assertThrows(IllegalArgumentException::class.java) { document.approve() }
    }

    @Test
    fun `reject rejects a document that is not pending`() {
        val document = newDocument()
        document.approve()
        assertThrows(IllegalArgumentException::class.java) { document.reject("too late") }
    }

    @Test
    fun `expire rejects a document that is not approved`() {
        val document = newDocument()
        assertThrows(IllegalArgumentException::class.java) { document.expire() }
    }

    @Test
    fun `expire rejects an already-rejected document`() {
        val document = newDocument()
        document.reject("bad scan")
        assertThrows(IllegalArgumentException::class.java) { document.expire() }
    }
}
