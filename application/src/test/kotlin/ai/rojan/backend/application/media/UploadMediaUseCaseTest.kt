package ai.rojan.backend.application.media

import ai.rojan.backend.application.salon.CreateSalonCommand
import ai.rojan.backend.application.salon.CreateSalonUseCase
import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.MediaFileTooLargeException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.UnsupportedMediaTypeException
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UploadMediaUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val mediaAssetRepository = InMemoryMediaAssetRepository()
    private val mediaStoragePort = FakeMediaStoragePort()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val owner = UserId.new()
    private val stranger = UserId.new()

    private val salon = CreateSalonUseCase(salonRepository).execute(
        CreateSalonCommand(owner, "Glow Salon", null, "+1 555 0100", null, "1 Main St"),
    )

    private val uploadUseCase = UploadMediaUseCase(
        salonRepository,
        mediaAssetRepository,
        mediaStoragePort,
        salonPermissionResolver,
        allowedMimeTypes = setOf("image/jpeg", "image/png"),
        maxFileSizeBytes = 1024,
    )

    private fun command(
        callerId: UserId = owner,
        mimeType: String = "image/jpeg",
        sizeBytes: Int = 100,
        content: ByteArray = jpegBytes(sizeBytes),
        fileName: String = "photo.jpg",
    ) = UploadMediaCommand(
        salonId = salon.id,
        callerId = callerId,
        mediaType = MediaType.GALLERY,
        fileName = fileName,
        mimeType = mimeType,
        content = content,
    )

    @Test
    fun `owner can upload media - success`() {
        val mediaAsset = uploadUseCase.execute(command())

        assertEquals(salon.id, mediaAsset.salonId)
        assertEquals(MediaType.GALLERY, mediaAsset.mediaType)
        assertTrue(mediaAsset.url.startsWith("https://rojanai.ir/media/"))
        assertEquals(1, mediaStoragePort.stored.size)
        assertEquals(mediaAsset, mediaAssetRepository.findById(mediaAsset.id))
    }

    @Test
    fun `rejects upload from a caller who does not own the salon - permission rejection`() {
        assertThrows<SalonAccessDeniedException> {
            uploadUseCase.execute(command(callerId = stranger))
        }
        assertTrue(mediaStoragePort.stored.isEmpty())
    }

    @Test
    fun `rejects an unsupported mime type`() {
        assertThrows<UnsupportedMediaTypeException> {
            uploadUseCase.execute(command(mimeType = "application/pdf"))
        }
        assertTrue(mediaStoragePort.stored.isEmpty())
    }

    @Test
    fun `rejects a file over the configured size limit`() {
        assertThrows<MediaFileTooLargeException> {
            uploadUseCase.execute(command(sizeBytes = 2048))
        }
        assertTrue(mediaStoragePort.stored.isEmpty())
    }

    @Test
    fun `rejects content whose actual bytes do not match the declared mime type - spoofed content-type`() {
        val notActuallyAnImage = "<script>alert(1)</script>".toByteArray()
        assertThrows<UnsupportedMediaTypeException> {
            uploadUseCase.execute(command(mimeType = "image/jpeg", content = notActuallyAnImage))
        }
        assertTrue(mediaStoragePort.stored.isEmpty())
    }

    @Test
    fun `storage extension is derived from validated content, not the client-supplied filename`() {
        uploadUseCase.execute(command(fileName = "evil.html"))

        val storageKey = mediaStoragePort.stored.single()
        assertTrue(storageKey.endsWith(".jpg"), "expected a .jpg storage key derived from the real JPEG bytes, got: $storageKey")
    }
}
