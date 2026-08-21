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
    val targetId: UUID? = null,
    val displayOrder: Int = 0,
)

/** `ReorderMediaCommand` (Media System Evolution v2) - reorders every media asset in one (mediaType, targetId) group at once; `mediaIds` must be exactly that group's current members, just permuted. */
data class ReorderMediaRequest(
    val mediaType: MediaType,
    val targetId: UUID? = null,
    val mediaIds: List<UUID>,
)
