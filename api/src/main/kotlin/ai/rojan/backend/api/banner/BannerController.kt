package ai.rojan.backend.api.banner

import ai.rojan.backend.api.common.ApiError
import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.banner.DeleteBannerCommand
import ai.rojan.backend.application.banner.DeleteBannerUseCase
import ai.rojan.backend.application.banner.ListBannersForAdminQuery
import ai.rojan.backend.application.banner.ListBannersForAdminUseCase
import ai.rojan.backend.application.banner.ReorderBannersCommand
import ai.rojan.backend.application.banner.ReorderBannersUseCase
import ai.rojan.backend.application.banner.ReplaceBannerImageCommand
import ai.rojan.backend.application.banner.ReplaceBannerImageUseCase
import ai.rojan.backend.application.banner.UpdateBannerMetadataCommand
import ai.rojan.backend.application.banner.UpdateBannerMetadataUseCase
import ai.rojan.backend.application.banner.UploadBannerCommand
import ai.rojan.backend.application.banner.UploadBannerUseCase
import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.domain.banner.Banner
import ai.rojan.backend.domain.banner.BannerId
import ai.rojan.backend.domain.banner.BannerTarget
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

/**
 * Banner Management (Web Phase - Super Admin) - PLATFORM_ADMIN only, every operation. A
 * PLATFORM_REVIEWER or ordinary CUSTOMER/MANAGER/SPECIALIST caller reaches
 * [ai.rojan.backend.application.platformauthority.PlatformAuthorizationResolver.requirePlatformAdmin]'s
 * `throw` inside each use case the same as [ai.rojan.backend.api.platformauthority.PlatformAuthorityReviewerController]
 * - no separate check here, this controller never inspects the caller's role itself. `target` binds
 * as [BannerTarget], which today only declares SITE/CUSTOMER/DESKTOP - an unknown value (e.g.
 * "MANAGER") fails Spring's own enum conversion before ever reaching a use case, mapped to `400` by
 * the existing `MethodArgumentTypeMismatchException` handler.
 *
 * The public, unauthenticated read surface every client (marketing website, eventually the
 * Customer/Desktop apps) actually consumes lives separately at [PublicBannerController] -
 * `/api/v1/public/banners`, mirroring the platform's existing public/admin split (e.g.
 * `PublicSalonController` vs `SalonController`).
 */
@RestController
@RequestMapping("/api/v1/platform-authority/banners")
@Tag(name = "Platform Authority - Banners")
class BannerController(
    private val uploadBannerUseCase: UploadBannerUseCase,
    private val listBannersForAdminUseCase: ListBannersForAdminUseCase,
    private val updateBannerMetadataUseCase: UpdateBannerMetadataUseCase,
    private val replaceBannerImageUseCase: ReplaceBannerImageUseCase,
    private val deleteBannerUseCase: DeleteBannerUseCase,
    private val reorderBannersUseCase: ReorderBannersUseCase,
    private val mediaStoragePort: MediaStoragePort,
    private val currentUserResolver: CurrentUserResolver,
) {

    @GetMapping
    @Operation(summary = "List every banner for one target, active and inactive (PLATFORM_ADMIN only)")
    fun list(
        @RequestParam target: BannerTarget,
        @AuthenticationPrincipal principal: UserDetails,
    ): List<BannerResponse> {
        val callerId = currentUserResolver.resolve(principal)
        return listBannersForAdminUseCase.execute(ListBannersForAdminQuery(callerId, target)).map { it.toResponse() }
    }

    @PostMapping(consumes = ["multipart/form-data"])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a new banner (PLATFORM_ADMIN only)")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Banner created"),
        ApiResponse(
            responseCode = "400",
            description = "Disallowed mime type or oversized file",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Caller is not PLATFORM_ADMIN",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun upload(
        @RequestParam target: BannerTarget,
        @RequestParam file: MultipartFile,
        @RequestParam(required = false) title: String?,
        @RequestParam(required = false) subtitle: String?,
        @RequestParam(required = false) href: String?,
        @RequestParam(defaultValue = "true") isActive: Boolean,
        @AuthenticationPrincipal principal: UserDetails,
    ): BannerResponse {
        val callerId = currentUserResolver.resolve(principal)
        val banner = uploadBannerUseCase.execute(
            UploadBannerCommand(
                callerId = callerId,
                target = target,
                title = title,
                subtitle = subtitle,
                href = href,
                isActive = isActive,
                content = file.bytes,
                mimeType = file.contentType ?: "application/octet-stream",
            ),
        )
        return banner.toResponse()
    }

    @PutMapping("/{bannerId}")
    @Operation(summary = "Edit a banner's metadata - never its image (PLATFORM_ADMIN only)")
    fun updateMetadata(
        @PathVariable bannerId: UUID,
        @Valid @RequestBody request: UpdateBannerMetadataRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): BannerResponse {
        val callerId = currentUserResolver.resolve(principal)
        return updateBannerMetadataUseCase.execute(
            UpdateBannerMetadataCommand(
                callerId = callerId,
                bannerId = BannerId(bannerId),
                title = request.title,
                subtitle = request.subtitle,
                href = request.href,
                isActive = request.isActive,
            ),
        ).toResponse()
    }

    @PostMapping("/{bannerId}/image", consumes = ["multipart/form-data"])
    @Operation(summary = "Replace a banner's image, preserving its id/metadata/URL slot (PLATFORM_ADMIN only)")
    fun replaceImage(
        @PathVariable bannerId: UUID,
        @RequestParam file: MultipartFile,
        @AuthenticationPrincipal principal: UserDetails,
    ): BannerResponse {
        val callerId = currentUserResolver.resolve(principal)
        return replaceBannerImageUseCase.execute(
            ReplaceBannerImageCommand(
                callerId = callerId,
                bannerId = BannerId(bannerId),
                content = file.bytes,
                mimeType = file.contentType ?: "application/octet-stream",
            ),
        ).toResponse()
    }

    @DeleteMapping("/{bannerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a banner - explicit, single-banner action only (PLATFORM_ADMIN only)")
    fun delete(
        @PathVariable bannerId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        deleteBannerUseCase.execute(DeleteBannerCommand(callerId, BannerId(bannerId)))
    }

    @PatchMapping("/reorder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Reorder every banner within one target (PLATFORM_ADMIN only)",
        description = "bannerIds must be exactly that target's current members, just permuted.",
    )
    fun reorder(
        @RequestParam target: BannerTarget,
        @Valid @RequestBody request: ReorderBannersRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        reorderBannersUseCase.execute(
            ReorderBannersCommand(callerId, target, request.bannerIds.map { BannerId(it) }),
        )
    }

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
