package ai.rojan.backend.application.media

import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.domain.media.MediaAsset
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.SalonId

/** Shared in-memory fake for the media use case tests, mirroring `SalonTestFixtures.kt`'s style. */
internal class InMemoryMediaAssetRepository : MediaAssetRepository {
    private val store = mutableMapOf<MediaAssetId, MediaAsset>()
    override fun save(mediaAsset: MediaAsset): MediaAsset = mediaAsset.also { store[it.id] = it }
    override fun findById(id: MediaAssetId): MediaAsset? = store[id]
    override fun findBySalonId(salonId: SalonId): List<MediaAsset> = store.values.filter { it.salonId == salonId }
    override fun findBySalonIdAndMediaType(salonId: SalonId, mediaType: MediaType): List<MediaAsset> =
        store.values.filter { it.salonId == salonId && it.mediaType == mediaType }
    override fun delete(id: MediaAssetId) {
        store.remove(id)
    }
}

/** Records every store()/delete() call so tests can assert on them, without touching a real filesystem. */
internal class FakeMediaStoragePort : MediaStoragePort {
    val stored = mutableListOf<String>()
    val deleted = mutableListOf<String>()

    override fun store(storageKey: String, content: ByteArray, mimeType: String): String {
        stored += storageKey
        return "https://rojanai.ir/media/$storageKey"
    }

    override fun delete(storageKey: String) {
        deleted += storageKey
    }
}
