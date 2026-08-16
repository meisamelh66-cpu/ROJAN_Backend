package ai.rojan.backend.application.document

import ai.rojan.backend.application.media.DeleteMediaCommand
import ai.rojan.backend.application.media.DeleteMediaUseCase
import ai.rojan.backend.application.media.InMemoryMediaAssetRepository
import ai.rojan.backend.application.media.InMemoryMediaStoragePort
import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.DocumentAlreadyAttachedException
import ai.rojan.backend.domain.common.MediaAssetNotFoundException
import ai.rojan.backend.domain.common.MediaTypeMismatchException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonDocumentNotFoundException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.document.DocumentType
import ai.rojan.backend.domain.document.DocumentVerificationStatus
import ai.rojan.backend.domain.document.SalonDocumentId
import ai.rojan.backend.domain.media.MediaAsset
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRole
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private fun newSalon(ownerId: UserId = UserId.new()) = Salon.create(
    ownerId = ownerId,
    name = "Glow Salon",
    description = null,
    phone = "+1 555 0100",
    email = null,
    address = "1 Main St",
)

class DocumentUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val mediaAssetRepository = InMemoryMediaAssetRepository()
    private val mediaStoragePort = InMemoryMediaStoragePort()
    private val documentRepository = InMemorySalonDocumentRepository()

    private val deleteMediaUseCase = DeleteMediaUseCase(salonRepository, mediaAssetRepository, salonPermissionResolver, mediaStoragePort)
    private val attachDocumentUseCase = AttachDocumentUseCase(salonRepository, mediaAssetRepository, documentRepository, salonPermissionResolver)
    private val listDocumentsUseCase = ListDocumentsUseCase(salonRepository, documentRepository, salonPermissionResolver)
    private val getDocumentUseCase = GetDocumentUseCase(salonRepository, documentRepository, salonPermissionResolver)
    private val getDocumentAccessUrlUseCase = GetDocumentAccessUrlUseCase(salonRepository, documentRepository, mediaAssetRepository, salonPermissionResolver, mediaStoragePort)
    private val deleteDocumentUseCase = DeleteDocumentUseCase(salonRepository, documentRepository, salonPermissionResolver, deleteMediaUseCase)

    private val ownerId = UserId.new()
    private val salon = newSalon(ownerId).also { salonRepository.save(it) }

    /** A Manager has MANAGE_MEDIA but neither VIEW_DOCUMENTS nor MANAGE_DOCUMENTS by default (approved permission matrix) - the exact "unauthorized" case worth exercising, not just a total stranger. */
    private val managerId = UserId.new().also { membershipRepository.assign(salon.id, it, SalonRole.MANAGER) }

    private val strangerId = UserId.new()

    private fun documentTypedAsset(): MediaAsset {
        val asset = MediaAsset.create(salon.id, MediaType.DOCUMENT, "salons/${salon.id.value}/documents/x", "license.pdf", "application/pdf", 1024, ownerId)
        return mediaAssetRepository.save(asset)
    }

    private fun galleryTypedAsset(): MediaAsset {
        val asset = MediaAsset.create(salon.id, MediaType.GALLERY, "salons/${salon.id.value}/media/x", "photo.jpg", "image/jpeg", 1024, ownerId)
        return mediaAssetRepository.save(asset)
    }

    // ---------- attach ----------

    @Test
    fun `owner can attach a document to a DOCUMENT-typed media asset`() {
        val asset = documentTypedAsset()

        val document = attachDocumentUseCase.execute(AttachDocumentCommand(salon.id, ownerId, asset.id, DocumentType.LICENSE, null))

        assertEquals(DocumentVerificationStatus.PENDING, document.verificationStatus)
        assertEquals(asset.id, document.mediaAssetId)
    }

    @Test
    fun `attach for a nonexistent salon throws`() {
        val asset = documentTypedAsset()
        assertThrows<SalonNotFoundException> {
            attachDocumentUseCase.execute(AttachDocumentCommand(SalonId.new(), ownerId, asset.id, DocumentType.LICENSE, null))
        }
    }

    @Test
    fun `manager cannot attach a document - MANAGE_MEDIA does not imply MANAGE_DOCUMENTS`() {
        val asset = documentTypedAsset()
        assertThrows<SalonAccessDeniedException> {
            attachDocumentUseCase.execute(AttachDocumentCommand(salon.id, managerId, asset.id, DocumentType.LICENSE, null))
        }
    }

    @Test
    fun `a stranger with no membership cannot attach a document`() {
        val asset = documentTypedAsset()
        assertThrows<SalonAccessDeniedException> {
            attachDocumentUseCase.execute(AttachDocumentCommand(salon.id, strangerId, asset.id, DocumentType.LICENSE, null))
        }
    }

    @Test
    fun `attach rejects an unknown media asset id`() {
        assertThrows<MediaAssetNotFoundException> {
            attachDocumentUseCase.execute(AttachDocumentCommand(salon.id, ownerId, MediaAssetId.new(), DocumentType.LICENSE, null))
        }
    }

    @Test
    fun `attach rejects a media asset that isn't DOCUMENT-typed`() {
        val asset = galleryTypedAsset()
        assertThrows<MediaTypeMismatchException> {
            attachDocumentUseCase.execute(AttachDocumentCommand(salon.id, ownerId, asset.id, DocumentType.LICENSE, null))
        }
    }

    @Test
    fun `attach rejects a media asset already attached to another document`() {
        val asset = documentTypedAsset()
        attachDocumentUseCase.execute(AttachDocumentCommand(salon.id, ownerId, asset.id, DocumentType.LICENSE, null))

        assertThrows<DocumentAlreadyAttachedException> {
            attachDocumentUseCase.execute(AttachDocumentCommand(salon.id, ownerId, asset.id, DocumentType.CERTIFICATE, null))
        }
    }

    // ---------- list / get ----------

    @Test
    fun `owner can list documents`() {
        val asset = documentTypedAsset()
        attachDocumentUseCase.execute(AttachDocumentCommand(salon.id, ownerId, asset.id, DocumentType.LICENSE, null))

        val listed = listDocumentsUseCase.execute(ListDocumentsQuery(salon.id, ownerId, null, null))

        assertEquals(1, listed.size)
    }

    @Test
    fun `manager cannot list documents`() {
        assertThrows<SalonAccessDeniedException> {
            listDocumentsUseCase.execute(ListDocumentsQuery(salon.id, managerId, null, null))
        }
    }

    @Test
    fun `owner can get one document's metadata`() {
        val asset = documentTypedAsset()
        val document = attachDocumentUseCase.execute(AttachDocumentCommand(salon.id, ownerId, asset.id, DocumentType.LICENSE, null))

        val fetched = getDocumentUseCase.execute(salon.id, ownerId, document.id)

        assertEquals(document.id, fetched.id)
    }

    @Test
    fun `getting an unknown document throws`() {
        assertThrows<SalonDocumentNotFoundException> {
            getDocumentUseCase.execute(salon.id, ownerId, SalonDocumentId.new())
        }
    }

    @Test
    fun `a stranger cannot get a document's metadata`() {
        val asset = documentTypedAsset()
        val document = attachDocumentUseCase.execute(AttachDocumentCommand(salon.id, ownerId, asset.id, DocumentType.LICENSE, null))

        assertThrows<SalonAccessDeniedException> {
            getDocumentUseCase.execute(salon.id, strangerId, document.id)
        }
    }

    // ---------- access-url ----------

    @Test
    fun `owner can request a signed access url - never a permanent public url`() {
        val asset = documentTypedAsset()
        val document = attachDocumentUseCase.execute(AttachDocumentCommand(salon.id, ownerId, asset.id, DocumentType.LICENSE, null))

        val access = getDocumentAccessUrlUseCase.execute(salon.id, ownerId, document.id)

        assertTrue(access.url.contains("signed"))
        assertTrue(access.expiresAt.isAfter(java.time.Instant.now()))
    }

    @Test
    fun `manager cannot request a document access url`() {
        val asset = documentTypedAsset()
        val document = attachDocumentUseCase.execute(AttachDocumentCommand(salon.id, ownerId, asset.id, DocumentType.LICENSE, null))

        assertThrows<SalonAccessDeniedException> {
            getDocumentAccessUrlUseCase.execute(salon.id, managerId, document.id)
        }
    }

    // ---------- delete ----------

    @Test
    fun `owner can delete a document - the underlying media asset is soft-deleted, the row is removed`() {
        val asset = documentTypedAsset()
        val document = attachDocumentUseCase.execute(AttachDocumentCommand(salon.id, ownerId, asset.id, DocumentType.LICENSE, null))

        deleteDocumentUseCase.execute(DeleteDocumentCommand(salon.id, ownerId, document.id))

        assertNull(documentRepository.findByIdAndSalonId(document.id, salon.id))
        assertEquals(
            ai.rojan.backend.domain.media.MediaAssetStatus.DELETED,
            mediaAssetRepository.findByIdAndSalonId(asset.id, salon.id)!!.status,
        )
    }

    @Test
    fun `manager cannot delete a document`() {
        val asset = documentTypedAsset()
        val document = attachDocumentUseCase.execute(AttachDocumentCommand(salon.id, ownerId, asset.id, DocumentType.LICENSE, null))

        assertThrows<SalonAccessDeniedException> {
            deleteDocumentUseCase.execute(DeleteDocumentCommand(salon.id, managerId, document.id))
        }
        // Unauthorized attempt must not have touched anything.
        assertEquals(document.id, documentRepository.findByIdAndSalonId(document.id, salon.id)!!.id)
    }

    @Test
    fun `deleting an unknown document throws`() {
        assertThrows<SalonDocumentNotFoundException> {
            deleteDocumentUseCase.execute(DeleteDocumentCommand(salon.id, ownerId, SalonDocumentId.new()))
        }
    }
}
