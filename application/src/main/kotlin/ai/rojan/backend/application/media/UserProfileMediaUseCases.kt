package ai.rojan.backend.application.media

import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.domain.common.MediaSizeExceededException
import ai.rojan.backend.domain.common.MediaTypeInvalidException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.media.MediaAsset
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import java.util.UUID

/**
 * Phase 5A.2 - User Profile Media, built on the canonical Media System
 * Evolution v2 foundation already live in production (through V22). A
 * user uploads / removes their own avatar or profile-cover image. The
 * acting user is always the JWT-resolved principal - every command here
 * carries exactly one [UserId] (`callerId`), never a separate target id a
 * caller could mismatch; `UserController` never accepts a user id from the
 * request path/body for these routes, so there is structurally nothing
 * else to validate "self" against.
 *
 * Reuses the salon media pipeline's building blocks - [MediaStoragePort]
 * for the bytes, [ImageContentSniffer] for magic-byte validation (made
 * `internal` in `MediaUseCases.kt` specifically for this reuse) - and
 * changes nothing about the salon flow (`UploadMediaUseCase`/
 * `DeleteMediaUseCase`/`ListMediaUseCase`/`ReorderMediaUseCase` are
 * untouched).
 *
 * Privacy deviation from the salon media pattern: replacing or deleting a
 * user's avatar/cover hard-deletes the previous asset - both the storage
 * bytes and the database row - never archives it, unlike a salon's
 * superseded logo/cover (`AssignIdentityMediaUseCase.archive`). A user who
 * replaces their photo expects the old one actually gone, not silently
 * retained under a different status.
 *
 * The existing pipeline stores bytes verbatim (no server-side re-encode),
 * so EXIF/GPS metadata stripping is not done here - a pre-existing, shared
 * gap across every media type on this platform, not introduced by this
 * phase (the Android client already strips it client-side before upload).
 */
private val USER_ALLOWED_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
private const val MAX_USER_IMAGE_BYTES = 8L * 1024 * 1024

private fun validateAndDetectExtension(content: ByteArray, declaredMimeType: String, mediaType: MediaType): String {
    if (declaredMimeType !in USER_ALLOWED_MIME_TYPES) {
        throw MediaTypeInvalidException(declaredMimeType, mediaType.name)
    }
    val fileSize = content.size.toLong()
    if (fileSize > MAX_USER_IMAGE_BYTES) {
        throw MediaSizeExceededException(fileSize, MAX_USER_IMAGE_BYTES)
    }
    // The declared Content-Type is client-controlled and independently
    // spoofable - only the real leading bytes of `content` decide what
    // this file really is and what extension the storage key gets.
    val detected = ImageContentSniffer.detect(content)
    if (detected == null || detected != declaredMimeType) {
        throw MediaTypeInvalidException(declaredMimeType, mediaType.name)
    }
    return ImageContentSniffer.EXTENSIONS_BY_MIME_TYPE.getValue(detected)
}

/** Hard-removes every prior asset of [mediaType] for [userId] except [keepId] (if any) - storage bytes and the DB row both. Never archives - see this file's own doc comment. */
private fun purgePreviousUserMedia(
    mediaAssetRepository: MediaAssetRepository,
    mediaStoragePort: MediaStoragePort,
    userId: UserId,
    mediaType: MediaType,
    keepId: MediaAssetId?,
) {
    mediaAssetRepository.findByUserIdAndMediaType(userId, mediaType)
        .filter { it.id != keepId }
        .forEach { stale ->
            mediaStoragePort.delete(stale.storageKey)
            mediaAssetRepository.delete(stale.id)
        }
}

// ---------------------------------------------------------------- Avatar --

data class UploadUserAvatarCommand(
    val callerId: UserId,
    val originalName: String,
    val mimeType: String,
    val content: ByteArray,
)

/** @return the user, reloaded, with the avatar slot pointed at the new asset. */
class UploadUserAvatarUseCase(
    private val userRepository: UserRepository,
    private val mediaAssetRepository: MediaAssetRepository,
    private val mediaStoragePort: MediaStoragePort,
) {
    fun execute(command: UploadUserAvatarCommand): User {
        val user = userRepository.findById(command.callerId)
            ?: throw UserNotFoundException(command.callerId.value.toString())

        val extension = validateAndDetectExtension(command.content, command.mimeType, MediaType.AVATAR)
        val storageKey = "users/${user.id.value}/media/${UUID.randomUUID()}.$extension"
        mediaStoragePort.upload(storageKey, command.content, command.mimeType)

        val newAsset = mediaAssetRepository.save(
            MediaAsset.createForUser(
                userId = user.id,
                mediaType = MediaType.AVATAR,
                storageKey = storageKey,
                originalName = command.originalName,
                mimeType = command.mimeType,
                fileSize = command.content.size.toLong(),
            ),
        )

        // Replace previous safely: repoint the slot at the new asset and
        // save first, so no window exists where the user references a
        // removed row, then hard-delete every prior avatar.
        user.assignAvatarMedia(newAsset.id)
        userRepository.save(user)
        purgePreviousUserMedia(mediaAssetRepository, mediaStoragePort, user.id, MediaType.AVATAR, keepId = newAsset.id)

        return userRepository.findById(user.id) ?: throw UserNotFoundException(user.id.value.toString())
    }
}

data class DeleteUserAvatarCommand(val callerId: UserId)

/** Idempotent: removing an already-empty avatar slot is a successful no-op. @return the user, reloaded. */
class DeleteUserAvatarUseCase(
    private val userRepository: UserRepository,
    private val mediaAssetRepository: MediaAssetRepository,
    private val mediaStoragePort: MediaStoragePort,
) {
    fun execute(command: DeleteUserAvatarCommand): User {
        val user = userRepository.findById(command.callerId)
            ?: throw UserNotFoundException(command.callerId.value.toString())

        user.assignAvatarMedia(null)
        userRepository.save(user)
        purgePreviousUserMedia(mediaAssetRepository, mediaStoragePort, user.id, MediaType.AVATAR, keepId = null)

        return userRepository.findById(user.id) ?: throw UserNotFoundException(user.id.value.toString())
    }
}

// ----------------------------------------------------------------- Cover --

data class UploadUserCoverCommand(
    val callerId: UserId,
    val originalName: String,
    val mimeType: String,
    val content: ByteArray,
)

/** @return the user, reloaded, with the profile-cover slot pointed at the new asset. */
class UploadUserCoverUseCase(
    private val userRepository: UserRepository,
    private val mediaAssetRepository: MediaAssetRepository,
    private val mediaStoragePort: MediaStoragePort,
) {
    fun execute(command: UploadUserCoverCommand): User {
        val user = userRepository.findById(command.callerId)
            ?: throw UserNotFoundException(command.callerId.value.toString())

        val extension = validateAndDetectExtension(command.content, command.mimeType, MediaType.PROFILE_COVER)
        val storageKey = "users/${user.id.value}/media/${UUID.randomUUID()}.$extension"
        mediaStoragePort.upload(storageKey, command.content, command.mimeType)

        val newAsset = mediaAssetRepository.save(
            MediaAsset.createForUser(
                userId = user.id,
                mediaType = MediaType.PROFILE_COVER,
                storageKey = storageKey,
                originalName = command.originalName,
                mimeType = command.mimeType,
                fileSize = command.content.size.toLong(),
            ),
        )

        user.assignCoverMedia(newAsset.id)
        userRepository.save(user)
        purgePreviousUserMedia(mediaAssetRepository, mediaStoragePort, user.id, MediaType.PROFILE_COVER, keepId = newAsset.id)

        return userRepository.findById(user.id) ?: throw UserNotFoundException(user.id.value.toString())
    }
}

data class DeleteUserCoverCommand(val callerId: UserId)

/** Idempotent: removing an already-empty cover slot is a successful no-op. @return the user, reloaded. */
class DeleteUserCoverUseCase(
    private val userRepository: UserRepository,
    private val mediaAssetRepository: MediaAssetRepository,
    private val mediaStoragePort: MediaStoragePort,
) {
    fun execute(command: DeleteUserCoverCommand): User {
        val user = userRepository.findById(command.callerId)
            ?: throw UserNotFoundException(command.callerId.value.toString())

        user.assignCoverMedia(null)
        userRepository.save(user)
        purgePreviousUserMedia(mediaAssetRepository, mediaStoragePort, user.id, MediaType.PROFILE_COVER, keepId = null)

        return userRepository.findById(user.id) ?: throw UserNotFoundException(user.id.value.toString())
    }
}
