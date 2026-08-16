package ai.rojan.backend.application.media

import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.domain.media.MediaAsset
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.SalonId

/** Mirrors [ai.rojan.backend.application.salon.InMemorySalonRepository]'s style. */
internal class InMemoryMediaAssetRepository : MediaAssetRepository {
    private val store = mutableMapOf<MediaAssetId, MediaAsset>()

    override fun save(mediaAsset: MediaAsset): MediaAsset = mediaAsset.also { store[it.id] = it }

    override fun findByIdAndSalonId(id: MediaAssetId, salonId: SalonId): MediaAsset? =
        store[id]?.takeIf { it.salonId == salonId }

    override fun findBySalonId(salonId: SalonId, mediaType: MediaType?): List<MediaAsset> =
        store.values.filter { it.salonId == salonId && (mediaType == null || it.mediaType == mediaType) }
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
