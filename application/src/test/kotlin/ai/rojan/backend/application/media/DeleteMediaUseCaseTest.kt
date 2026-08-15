package ai.rojan.backend.application.media

import ai.rojan.backend.application.salon.CreateSalonCommand
import ai.rojan.backend.application.salon.CreateSalonUseCase
import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.MediaAssetNotFoundException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeleteMediaUseCaseTest {

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
        salonRepository, mediaAssetRepository, mediaStoragePort, salonPermissionResolver,
        allowedMimeTypes = setOf("image/jpeg"), maxFileSizeBytes = 1024,
    )
    private val deleteUseCase = DeleteMediaUseCase(mediaAssetRepository, mediaStoragePort, salonPermissionResolver)

    @Test
    fun `owner can delete their salon's media - success`() {
        val mediaAsset = uploadUseCase.execute(
            UploadMediaCommand(salon.id, owner, MediaType.GALLERY, "a.jpg", "image/jpeg", ByteArray(10)),
        )

        deleteUseCase.execute(DeleteMediaCommand(mediaAsset.id, owner))

        assertNull(mediaAssetRepository.findById(mediaAsset.id))
        assert(mediaStoragePort.deleted.contains(mediaAsset.storageKey))
    }

    @Test
    fun `rejects deletion from a caller who does not own the salon - permission rejection`() {
        val mediaAsset = uploadUseCase.execute(
            UploadMediaCommand(salon.id, owner, MediaType.GALLERY, "a.jpg", "image/jpeg", ByteArray(10)),
        )

        assertThrows<SalonAccessDeniedException> {
            deleteUseCase.execute(DeleteMediaCommand(mediaAsset.id, stranger))
        }
        assert(mediaAssetRepository.findById(mediaAsset.id) != null)
    }

    @Test
    fun `delete fails for an unknown media asset`() {
        assertThrows<MediaAssetNotFoundException> {
            deleteUseCase.execute(DeleteMediaCommand(MediaAssetId.new(), owner))
        }
    }
}
