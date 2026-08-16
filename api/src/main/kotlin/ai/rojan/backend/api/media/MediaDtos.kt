package ai.rojan.backend.api.media

import ai.rojan.backend.domain.media.MediaAssetStatus
import ai.rojan.backend.domain.media.MediaType
import java.time.Instant
import java.util.UUID

data class MediaAssetResponse(
    val id: UUID,
    val salonId: UUID,
    val mediaType: MediaType,
    val originalName: String,
    val mimeType: String,
    val fileSize: Long,
    val status: MediaAssetStatus,
    val url: String,
    val createdAt: Instant,
)
