package ai.rojan.backend.application.media

import ai.rojan.backend.application.salon.CreateSalonCommand
import ai.rojan.backend.application.salon.CreateSalonUseCase
import ai.rojan.backend.application.salon.InMemorySalonMembershipRepository
import ai.rojan.backend.application.salon.InMemorySalonRepository
import ai.rojan.backend.application.salon.InMemorySpecialistRepository
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.MediaAssetTenantMismatchException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AssignSalonIdentityMediaUseCaseTest {

    private val salonRepository = InMemorySalonRepository()
    private val mediaAssetRepository = InMemoryMediaAssetRepository()
    private val mediaStoragePort = FakeMediaStoragePort()
    private val membershipRepository = InMemorySalonMembershipRepository()
    private val specialistRepository = InMemorySpecialistRepository()
    private val salonPermissionResolver = SalonPermissionResolver(salonRepository, membershipRepository, specialistRepository)
    private val owner = UserId.new()
    private val stranger = UserId.new()

    private val salonA = CreateSalonUseCase(salonRepository).execute(
        CreateSalonCommand(owner, "Glow Salon A", null, "+1 555 0100", null, "1 Main St"),
    )
    private val salonB = CreateSalonUseCase(salonRepository).execute(
        CreateSalonCommand(owner, "Glow Salon B", null, "+1 555 0200", null, "2 Main St"),
    )

    private val uploadUseCase = UploadMediaUseCase(
        salonRepository, mediaAssetRepository, mediaStoragePort, salonPermissionResolver,
        allowedMimeTypes = setOf("image/jpeg"), maxFileSizeBytes = 1024,
    )
    private val assignUseCase = AssignSalonIdentityMediaUseCase(salonRepository, mediaAssetRepository, salonPermissionResolver)

    private fun uploadFor(salonId: SalonId, mediaType: MediaType = MediaType.LOGO) =
        uploadUseCase.execute(
            UploadMediaCommand(salonId, owner, mediaType, "logo.jpg", "image/jpeg", ByteArray(10)),
        )

    @Test
    fun `owner can assign an already-uploaded media asset as the salon's logo - success`() {
        val logo = uploadFor(salonA.id)

        val updated = assignUseCase.execute(
            AssignSalonIdentityMediaCommand(salonA.id, owner, logoMediaId = logo.id, coverMediaId = null),
        )

        assertEquals(logo.id, updated.logoMediaId)
        assertNull(updated.coverMediaId)
    }

    @Test
    fun `rejects assigning media uploaded for a different salon - tenant isolation, invalid ownership`() {
        val logoForSalonB = uploadFor(salonB.id)

        val ex = assertThrows<MediaAssetTenantMismatchException> {
            assignUseCase.execute(
                AssignSalonIdentityMediaCommand(salonA.id, owner, logoMediaId = logoForSalonB.id, coverMediaId = null),
            )
        }
        assertEquals(true, ex.message?.contains(salonA.id.value.toString()))
        // Salon A's reference must stay untouched by the rejected attempt.
        assertNull(salonRepository.findById(salonA.id)?.logoMediaId)
    }

    @Test
    fun `rejects assigning a non-existent media asset id - invalid ownership`() {
        assertThrows<MediaAssetTenantMismatchException> {
            assignUseCase.execute(
                AssignSalonIdentityMediaCommand(salonA.id, owner, logoMediaId = MediaAssetId.new(), coverMediaId = null),
            )
        }
    }

    @Test
    fun `rejects assignment from a caller who does not own the salon - permission rejection`() {
        val logo = uploadFor(salonA.id)

        assertThrows<SalonAccessDeniedException> {
            assignUseCase.execute(
                AssignSalonIdentityMediaCommand(salonA.id, stranger, logoMediaId = logo.id, coverMediaId = null),
            )
        }
    }

    @Test
    fun `explicit null clears a previously assigned logo`() {
        val logo = uploadFor(salonA.id)
        assignUseCase.execute(AssignSalonIdentityMediaCommand(salonA.id, owner, logoMediaId = logo.id, coverMediaId = null))

        val cleared = assignUseCase.execute(AssignSalonIdentityMediaCommand(salonA.id, owner, logoMediaId = null, coverMediaId = null))

        assertNull(cleared.logoMediaId)
    }
}
