package ai.rojan.backend.application.media

import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.MediaAssetNotFoundException
import ai.rojan.backend.domain.common.MediaSizeExceededException
import ai.rojan.backend.domain.common.MediaTypeInvalidException
import ai.rojan.backend.domain.common.MediaTypeMismatchException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetStatus
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.IdentitySlot
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

class MediaUseCasesTest {

    private val salonRepository = InMemorySalonRepository()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val mediaAssetRepository = InMemoryMediaAssetRepository()
    private val mediaStoragePort = InMemoryMediaStoragePort()

    private val uploadMediaUseCase = UploadMediaUseCase(salonRepository, mediaAssetRepository, salonPermissionResolver, mediaStoragePort)
    private val listMediaUseCase = ListMediaUseCase(salonRepository, mediaAssetRepository)
    private val deleteMediaUseCase = DeleteMediaUseCase(salonRepository, mediaAssetRepository, salonPermissionResolver, mediaStoragePort)
    private val assignIdentityMediaUseCase = AssignIdentityMediaUseCase(salonRepository, mediaAssetRepository, salonPermissionResolver)

    private val ownerId = UserId.new()
    private val salon = newSalon(ownerId).also { salonRepository.save(it) }

    /** Real PNG magic bytes (padded with zeros) - [ImageContentSniffer] validates actual content now, not just the declared [UploadMediaCommand.mimeType]. */
    private val REAL_PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(92)

    private fun uploadCommand(mediaType: MediaType = MediaType.LOGO, caller: UserId = ownerId) = UploadMediaCommand(
        salonId = salon.id,
        callerId = caller,
        mediaType = mediaType,
        content = REAL_PNG_BYTES,
        originalName = "logo.png",
        mimeType = "image/png",
    )

    @Test
    fun `owner can upload media`() {
        val asset = uploadMediaUseCase.execute(uploadCommand())

        assertEquals(MediaAssetStatus.ACTIVE, asset.status)
        assertEquals(salon.id, asset.salonId)
        assertTrue(mediaStoragePort.uploaded.containsKey(asset.storageKey))
    }

    @Test
    fun `upload for a nonexistent salon throws`() {
        assertThrows<SalonNotFoundException> {
            uploadMediaUseCase.execute(uploadCommand().copy(salonId = SalonId.new()))
        }
    }

    @Test
    fun `upload by a caller with no permission throws`() {
        assertThrows<SalonAccessDeniedException> {
            uploadMediaUseCase.execute(uploadCommand(caller = UserId.new()))
        }
    }

    @Test
    fun `upload rejects a disallowed mime type for a public image slot`() {
        assertThrows<MediaTypeInvalidException> {
            uploadMediaUseCase.execute(uploadCommand().copy(mimeType = "application/zip"))
        }
    }

    @Test
    fun `upload rejects a file over the size ceiling`() {
        assertThrows<MediaSizeExceededException> {
            uploadMediaUseCase.execute(uploadCommand().copy(content = ByteArray(9 * 1024 * 1024)))
        }
    }

    @Test
    fun `upload rejects content whose real bytes don't match the declared mime type - spoofed Content-Type`() {
        val htmlBytes = "<script>alert(1)</script>".toByteArray()

        assertThrows<MediaTypeInvalidException> {
            uploadMediaUseCase.execute(uploadCommand().copy(content = htmlBytes, mimeType = "image/png"))
        }
    }

    @Test
    fun `upload derives the storage key extension from the detected content, never the client filename`() {
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(97)

        val asset = uploadMediaUseCase.execute(
            uploadCommand().copy(content = jpegBytes, mimeType = "image/jpeg", originalName = "evil.html"),
        )

        assertTrue(asset.storageKey.endsWith(".jpg"))
    }

    @Test
    fun `list excludes deleted assets`() {
        val asset = uploadMediaUseCase.execute(uploadCommand())
        deleteMediaUseCase.execute(DeleteMediaCommand(salon.id, ownerId, asset.id))

        val listed = listMediaUseCase.execute(ListMediaQuery(salon.id, mediaType = null))

        assertTrue(listed.isEmpty())
    }

    @Test
    fun `delete clears a live identity-slot assignment in the same call`() {
        val asset = uploadMediaUseCase.execute(uploadCommand())
        assignIdentityMediaUseCase.execute(AssignIdentityMediaCommand(salon.id, ownerId, IdentitySlot.LOGO, asset.id))

        deleteMediaUseCase.execute(DeleteMediaCommand(salon.id, ownerId, asset.id))

        val updatedSalon = salonRepository.findById(salon.id)!!
        assertNull(updatedSalon.logoMediaId)
        assertEquals(MediaAssetStatus.DELETED, mediaAssetRepository.findByIdAndSalonId(asset.id, salon.id)!!.status)
        assertTrue(mediaStoragePort.deleted.contains(asset.storageKey))
    }

    @Test
    fun `delete of an unknown media id throws`() {
        assertThrows<MediaAssetNotFoundException> {
            deleteMediaUseCase.execute(DeleteMediaCommand(salon.id, ownerId, MediaAssetId.new()))
        }
    }

    @Test
    fun `assign sets the salon's logo and resolves via the stored id, not a url`() {
        val asset = uploadMediaUseCase.execute(uploadCommand(MediaType.LOGO))

        val updated = assignIdentityMediaUseCase.execute(AssignIdentityMediaCommand(salon.id, ownerId, IdentitySlot.LOGO, asset.id))

        assertEquals(asset.id, updated.logoMediaId)
    }

    @Test
    fun `assign rejects a media type that doesn't match the slot`() {
        val galleryAsset = uploadMediaUseCase.execute(uploadCommand(MediaType.GALLERY))

        assertThrows<MediaTypeMismatchException> {
            assignIdentityMediaUseCase.execute(AssignIdentityMediaCommand(salon.id, ownerId, IdentitySlot.LOGO, galleryAsset.id))
        }
    }

    @Test
    fun `re-assigning a slot archives the previous asset instead of deleting it`() {
        val first = uploadMediaUseCase.execute(uploadCommand(MediaType.LOGO))
        assignIdentityMediaUseCase.execute(AssignIdentityMediaCommand(salon.id, ownerId, IdentitySlot.LOGO, first.id))
        val second = uploadMediaUseCase.execute(uploadCommand(MediaType.LOGO))

        assignIdentityMediaUseCase.execute(AssignIdentityMediaCommand(salon.id, ownerId, IdentitySlot.LOGO, second.id))

        val previous = mediaAssetRepository.findByIdAndSalonId(first.id, salon.id)!!
        assertEquals(MediaAssetStatus.ARCHIVED, previous.status)
    }

    @Test
    fun `assigning null clears the slot`() {
        val asset = uploadMediaUseCase.execute(uploadCommand(MediaType.COVER))
        assignIdentityMediaUseCase.execute(AssignIdentityMediaCommand(salon.id, ownerId, IdentitySlot.COVER, asset.id))

        val cleared = assignIdentityMediaUseCase.execute(AssignIdentityMediaCommand(salon.id, ownerId, IdentitySlot.COVER, null))

        assertNull(cleared.coverMediaId)
    }

    @Test
    fun `assigning an unknown media id throws`() {
        assertThrows<MediaAssetNotFoundException> {
            assignIdentityMediaUseCase.execute(AssignIdentityMediaCommand(salon.id, ownerId, IdentitySlot.LOGO, MediaAssetId.new()))
        }
    }

    // ---------- Document Archive (Phase 2) retrofits to this Phase 1 code ----------

    private val managerId = UserId.new().also { membershipRepository.assign(salon.id, it, SalonRole.MANAGER) }

    @Test
    fun `owner can upload a DOCUMENT with an allowed mime type`() {
        val asset = uploadMediaUseCase.execute(
            uploadCommand(MediaType.DOCUMENT).copy(mimeType = "application/pdf", originalName = "license.pdf"),
        )
        assertEquals(MediaType.DOCUMENT, asset.mediaType)
    }

    @Test
    fun `DOCUMENT uploads are now mime-validated - previously accepted anything`() {
        assertThrows<MediaTypeInvalidException> {
            uploadMediaUseCase.execute(uploadCommand(MediaType.DOCUMENT).copy(mimeType = "application/zip"))
        }
    }

    @Test
    fun `a manager cannot upload a DOCUMENT - MANAGE_MEDIA does not imply MANAGE_DOCUMENTS`() {
        assertThrows<SalonAccessDeniedException> {
            uploadMediaUseCase.execute(uploadCommand(MediaType.DOCUMENT, caller = managerId).copy(mimeType = "application/pdf"))
        }
    }

    @Test
    fun `a manager can still upload non-document media - MANAGE_MEDIA is unaffected for public types`() {
        val asset = uploadMediaUseCase.execute(uploadCommand(MediaType.GALLERY, caller = managerId))
        assertEquals(MediaType.GALLERY, asset.mediaType)
    }

    @Test
    fun `DOCUMENT uploads route into a distinct, non-public storage prefix`() {
        val document = uploadMediaUseCase.execute(uploadCommand(MediaType.DOCUMENT).copy(mimeType = "application/pdf"))
        val publicAsset = uploadMediaUseCase.execute(uploadCommand(MediaType.LOGO))

        assertTrue(document.storageKey.contains("/documents/"))
        assertTrue(publicAsset.storageKey.contains("/media/"))
    }

    @Test
    fun `list unconditionally excludes DOCUMENT-typed assets, even when explicitly requested`() {
        uploadMediaUseCase.execute(uploadCommand(MediaType.DOCUMENT).copy(mimeType = "application/pdf"))
        uploadMediaUseCase.execute(uploadCommand(MediaType.LOGO))

        val all = listMediaUseCase.execute(ListMediaQuery(salon.id, mediaType = null))
        val documentsOnly = listMediaUseCase.execute(ListMediaQuery(salon.id, mediaType = MediaType.DOCUMENT))

        assertEquals(1, all.size)
        assertEquals(MediaType.LOGO, all.single().mediaType)
        assertTrue(documentsOnly.isEmpty())
    }
}
