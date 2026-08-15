package ai.rojan.backend.api.media

import ai.rojan.backend.domain.media.MediaType
import java.time.Instant
import java.util.UUID

/** [storageKey] is deliberately never exposed - an internal storage-adapter detail, not a client concern. */
data class MediaAssetResponse(
    val id: UUID,
    val salonId: UUID,
    val mediaType: MediaType,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val url: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
