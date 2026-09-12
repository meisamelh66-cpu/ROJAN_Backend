package ai.rojan.backend.application.media

import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.media.MediaAsset
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import java.util.UUID

/** Mirrors [ai.rojan.backend.application.salon.InMemorySalonRepository]'s style. */
internal class InMemoryMediaAssetRepository : MediaAssetRepository {
    private val store = mutableMapOf<MediaAssetId, MediaAsset>()

    override fun save(mediaAsset: MediaAsset): MediaAsset = mediaAsset.also { store[it.id] = it }

    override fun findByIdAndSalonId(id: MediaAssetId, salonId: SalonId): MediaAsset? =
        store[id]?.takeIf { it.salonId == salonId }

    override fun findBySalonId(salonId: SalonId, mediaType: MediaType?, targetId: UUID?): List<MediaAsset> =
        store.values
            .filter { it.salonId == salonId && (mediaType == null || it.mediaType == mediaType) && (targetId == null || it.targetId == targetId) }
            .sortedWith(compareBy({ it.displayOrder }, { it.createdAt }))

    override fun findByIdAndUserId(id: MediaAssetId, userId: UserId): MediaAsset? =
        store[id]?.takeIf { it.userId == userId }

    override fun findByUserIdAndMediaType(userId: UserId, mediaType: MediaType): List<MediaAsset> =
        store.values.filter { it.userId == userId && it.mediaType == mediaType }

    override fun delete(id: MediaAssetId) {
        store.remove(id)
    }

    fun all(): List<MediaAsset> = store.values.toList()
}

/** In-memory [UserRepository] for the Phase 5A.2 user-media use-case tests. */
internal class InMemoryMediaUserRepository : UserRepository {
    private val store = mutableMapOf<UserId, User>()
    fun register(user: User) {
        store[user.id] = user
    }
    override fun save(user: User): User = user.also { store[it.id] = it }
    override fun findById(id: UserId): User? = store[id]
    override fun findByEmail(email: Email): User? = store.values.find { it.email == email }
    override fun existsByEmail(email: Email): Boolean = store.values.any { it.email == email }
    override fun findByPhoneNumber(phoneNumber: PhoneNumber): User? = store.values.find { it.phoneNumber == phoneNumber }
    override fun existsByPhoneNumber(phoneNumber: PhoneNumber): Boolean = store.values.any { it.phoneNumber == phoneNumber }
}

/** In-memory - no real bytes stored, just tracks what was uploaded/deleted for assertions. */
internal class InMemoryMediaStoragePort : MediaStoragePort {
    val uploaded = mutableMapOf<String, ByteArray>()
    val deleted = mutableListOf<String>()

    override fun upload(storageKey: String, content: ByteArray, contentType: String) {
        uploaded[storageKey] = content
    }

    override fun delete(storageKey: String) {
        deleted.add(storageKey)
        uploaded.remove(storageKey)
    }

    override fun resolveUrl(storageKey: String): String = "https://cdn.test/$storageKey"

    override fun resolveSignedUrl(storageKey: String, expirySeconds: Long): String =
        "https://cdn.test/signed/$storageKey?expires=$expirySeconds"
}
