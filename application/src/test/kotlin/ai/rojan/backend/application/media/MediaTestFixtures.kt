package ai.rojan.backend.application.media

import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.media.MediaAsset
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole
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
    override fun findByRole(role: UserRole): List<User> = store.values.filter { it.role == role }

    /** Not exercised by this file's tests - a minimal, correct in-memory implementation only to satisfy the interface (Platform Management API Contract). */
    override fun findByRole(role: UserRole, pageRequest: PageRequest, search: String?, sortDirection: SortDirection): PageResult<User> {
        val matches = findByRole(role)
        return PageResult(content = matches.take(pageRequest.size), page = pageRequest.page, size = pageRequest.size, totalElements = matches.size.toLong())
    }
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
