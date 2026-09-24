package ai.rojan.backend.api.banner

import ai.rojan.backend.domain.banner.BannerTarget
import java.time.Instant
import java.util.UUID

data class BannerResponse(
    val id: UUID,
    val target: BannerTarget,
    val title: String?,
    val subtitle: String?,
    val href: String?,
    val imageUrl: String,
    val isActive: Boolean,
    val displayOrder: Int,
    val createdAt: Instant,
)

data class UpdateBannerMetadataRequest(
    val title: String?,
    val subtitle: String?,
    val href: String?,
    val isActive: Boolean,
)

/** `ReorderBannersCommand` - reorders every banner in one target group at once; `bannerIds` must be exactly that target's current members, just permuted. */
data class ReorderBannersRequest(
    val bannerIds: List<UUID>,
)
