package ai.rojan.backend.api.banner

import ai.rojan.backend.application.banner.ListActiveBannersQuery
import ai.rojan.backend.application.banner.ListActiveBannersUseCase
import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.domain.banner.Banner
import ai.rojan.backend.domain.banner.BannerTarget
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The real, unauthenticated banner read surface - every client (this website's own homepage
 * today, eventually the Customer/Desktop apps) fetches active banners for its own target from
 * here. Deliberately separate from [BannerController] (PLATFORM_ADMIN-only management), mirroring
 * the platform's existing public/admin split (e.g. `PublicSalonController`/`SalonController`).
 * Inactive banners are never returned here - only the target's own creation/management view
 * ([BannerController.list]) ever sees those.
 */
@RestController
@RequestMapping("/api/v1/public/banners")
@Tag(name = "Public - Banners")
class PublicBannerController(
    private val listActiveBannersUseCase: ListActiveBannersUseCase,
    private val mediaStoragePort: MediaStoragePort,
) {

    @GetMapping
    @Operation(summary = "List active banners for one target, pre-sorted by display order")
    fun listActive(@RequestParam target: BannerTarget): List<BannerResponse> =
        listActiveBannersUseCase.execute(ListActiveBannersQuery(target)).map { it.toResponse() }

    private fun Banner.toResponse() = BannerResponse(
        id = id.value,
        target = target,
        title = title,
        subtitle = subtitle,
        href = href,
        imageUrl = mediaStoragePort.resolveUrl(storageKey),
        isActive = isActive,
        displayOrder = displayOrder,
        createdAt = createdAt,
    )
}
