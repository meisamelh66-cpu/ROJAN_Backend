package ai.rojan.backend.application.media

import ai.rojan.backend.domain.common.MediaSizeExceededException
import ai.rojan.backend.domain.common.MediaTypeInvalidException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Phase 5A.2, User Profile Media - built on the canonical Media System Evolution v2 foundation already covered by [MediaUseCasesTest]. */
class UserProfileMediaUseCasesTest {

    private val userRepository = InMemoryMediaUserRepository()
    private val mediaAssetRepository = InMemoryMediaAssetRepository()
    private val mediaStoragePort = InMemoryMediaStoragePort()

    private val uploadAvatarUseCase = UploadUserAvatarUseCase(userRepository, mediaAssetRepository, mediaStoragePort)
    private val uploadCoverUseCase = UploadUserCoverUseCase(userRepository, mediaAssetRepository, mediaStoragePort)
    private val deleteAvatarUseCase = DeleteUserAvatarUseCase(userRepository, mediaAssetRepository, mediaStoragePort)
    private val deleteCoverUseCase = DeleteUserCoverUseCase(userRepository, mediaAssetRepository, mediaStoragePort)

    private val user = User.register(Email("cust.${System.nanoTime()}@example.com"), "hash", "Gita", UserRole.CUSTOMER)
        .also { userRepository.register(it) }

    /** Real PNG magic bytes (padded) - [ImageContentSniffer] validates actual content, not just the declared mimeType. */
    private val REAL_PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(92)

    private fun uploadAvatarCommand(callerId: UserId = user.id, mimeType: String = "image/png", content: ByteArray = REAL_PNG_BYTES) =
        UploadUserAvatarCommand(callerId = callerId, originalName = "avatar.png", mimeType = mimeType, content = content)

    private fun uploadCoverCommand(callerId: UserId = user.id, mimeType: String = "image/png", content: ByteArray = REAL_PNG_BYTES) =
        UploadUserCoverCommand(callerId = callerId, originalName = "cover.png", mimeType = mimeType, content = content)

    // ---------------------------------------------------------------- Avatar --

    @Test
    fun `user can upload their own avatar - success, slot assigned, USER-owned asset`() {
        val updated = uploadAvatarUseCase.execute(uploadAvatarCommand())

        val avatarId = updated.avatarMediaId
        assertTrue(avatarId != null)
        assertNull(updated.coverMediaId)

        val asset = mediaAssetRepository.findByIdAndUserId(avatarId!!, user.id)!!
        assertEquals(user.id, asset.userId)
        assertNull(asset.salonId)
        assertEquals(MediaType.AVATAR, asset.mediaType)
        assertEquals(user.id, asset.uploadedBy)
        assertTrue(asset.storageKey.startsWith("users/${user.id.value}/media/"))
        assertEquals(1, mediaStoragePort.uploaded.size)
    }

    @Test
    fun `re-uploading an avatar hard-removes the previous one - never archived`() {
        val first = uploadAvatarUseCase.execute(uploadAvatarCommand()).avatarMediaId!!
        val second = uploadAvatarUseCase.execute(uploadAvatarCommand()).avatarMediaId!!

        assertTrue(first != second)
        assertNull(mediaAssetRepository.findByIdAndUserId(first, user.id))
        assertEquals(1, mediaAssetRepository.findByUserIdAndMediaType(user.id, MediaType.AVATAR).size)
        assertEquals(1, mediaStoragePort.deleted.size)
    }

    @Test
    fun `unknown caller is rejected for avatar upload`() {
        assertThrows<UserNotFoundException> { uploadAvatarUseCase.execute(uploadAvatarCommand(callerId = UserId.new())) }
    }

    @Test
    fun `rejects an unsupported mime type for avatar upload`() {
        assertThrows<MediaTypeInvalidException> { uploadAvatarUseCase.execute(uploadAvatarCommand(mimeType = "application/pdf")) }
        assertTrue(mediaStoragePort.uploaded.isEmpty())
    }

    @Test
    fun `rejects an oversized avatar upload`() {
        val oversized = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(9 * 1024 * 1024)
        assertThrows<MediaSizeExceededException> { uploadAvatarUseCase.execute(uploadAvatarCommand(content = oversized)) }
        assertTrue(mediaStoragePort.uploaded.isEmpty())
    }

    @Test
    fun `rejects avatar content whose real bytes don't match the declared mime type - spoofed Content-Type`() {
        val htmlBytes = "<script>alert(1)</script>".toByteArray()
        assertThrows<MediaTypeInvalidException> { uploadAvatarUseCase.execute(uploadAvatarCommand(content = htmlBytes)) }
        assertTrue(mediaStoragePort.uploaded.isEmpty())
    }

    @Test
    fun `deleting the avatar clears the slot and hard-removes the asset and file - never archived`() {
        val assetId = uploadAvatarUseCase.execute(uploadAvatarCommand()).avatarMediaId!!

        val updated = deleteAvatarUseCase.execute(DeleteUserAvatarCommand(user.id))

        assertNull(updated.avatarMediaId)
        assertNull(mediaAssetRepository.findByIdAndUserId(assetId, user.id))
        assertTrue(mediaAssetRepository.findByUserIdAndMediaType(user.id, MediaType.AVATAR).isEmpty())
        assertEquals(1, mediaStoragePort.deleted.size)
    }

    @Test
    fun `deleting an already-empty avatar slot is a successful no-op`() {
        val updated = deleteAvatarUseCase.execute(DeleteUserAvatarCommand(user.id))
        assertNull(updated.avatarMediaId)
        assertTrue(mediaStoragePort.deleted.isEmpty())
    }

    // ----------------------------------------------------------------- Cover --

    @Test
    fun `user can upload their own profile cover - success, independent from the avatar slot`() {
        uploadAvatarUseCase.execute(uploadAvatarCommand())
        val updated = uploadCoverUseCase.execute(uploadCoverCommand())

        assertTrue(updated.avatarMediaId != null, "uploading a cover must not disturb the avatar")
        val coverId = updated.coverMediaId
        assertTrue(coverId != null)

        val asset = mediaAssetRepository.findByIdAndUserId(coverId!!, user.id)!!
        assertEquals(user.id, asset.userId)
        assertEquals(MediaType.PROFILE_COVER, asset.mediaType)
    }

    @Test
    fun `re-uploading a cover hard-removes the previous one - never archived`() {
        val first = uploadCoverUseCase.execute(uploadCoverCommand()).coverMediaId!!
        val second = uploadCoverUseCase.execute(uploadCoverCommand()).coverMediaId!!

        assertTrue(first != second)
        assertNull(mediaAssetRepository.findByIdAndUserId(first, user.id))
        assertEquals(1, mediaAssetRepository.findByUserIdAndMediaType(user.id, MediaType.PROFILE_COVER).size)
    }

    @Test
    fun `deleting the cover clears the slot and hard-removes the asset and file - never archived`() {
        val assetId = uploadCoverUseCase.execute(uploadCoverCommand()).coverMediaId!!

        val updated = deleteCoverUseCase.execute(DeleteUserCoverCommand(user.id))

        assertNull(updated.coverMediaId)
        assertNull(mediaAssetRepository.findByIdAndUserId(assetId, user.id))
        assertTrue(mediaAssetRepository.findByUserIdAndMediaType(user.id, MediaType.PROFILE_COVER).isEmpty())
    }

    @Test
    fun `deleting an already-empty cover slot is a successful no-op`() {
        val updated = deleteCoverUseCase.execute(DeleteUserCoverCommand(user.id))
        assertNull(updated.coverMediaId)
    }

    @Test
    fun `deleting the cover leaves the avatar untouched`() {
        val avatarId = uploadAvatarUseCase.execute(uploadAvatarCommand()).avatarMediaId!!
        uploadCoverUseCase.execute(uploadCoverCommand())

        val updated = deleteCoverUseCase.execute(DeleteUserCoverCommand(user.id))

        assertNull(updated.coverMediaId)
        assertEquals(avatarId, updated.avatarMediaId)
        assertEquals(avatarId, mediaAssetRepository.findByIdAndUserId(avatarId, user.id)!!.id)
    }
}
