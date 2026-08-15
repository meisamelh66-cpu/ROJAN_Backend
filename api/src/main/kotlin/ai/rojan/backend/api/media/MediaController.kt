package ai.rojan.backend.api.media

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.media.DeleteMediaCommand
import ai.rojan.backend.application.media.DeleteMediaUseCase
import ai.rojan.backend.application.media.UploadMediaCommand
import ai.rojan.backend.application.media.UploadMediaUseCase
import ai.rojan.backend.application.salon.SalonPermissionResolver
import ai.rojan.backend.domain.common.MediaAssetNotFoundException
import ai.rojan.backend.domain.common.MediaAssetTenantMismatchException
import ai.rojan.backend.domain.media.MediaAsset
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.SalonId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

/**
 * Owner-only ([Permission.MANAGE_SALON], same gate every other salon-identity
 * write already uses) media CRUD for a salon — Salon Identity Foundation
 * Phase A. [list] deliberately re-checks the permission itself (unlike e.g.
 * `SpecialistController.list`, which is open to any authenticated caller) —
 * this phase's explicit tenant-security requirement is stricter than that
 * older sibling endpoint's existing behavior, not loosened to match it.
 * Public/gallery reads live on [ai.rojan.backend.api.publicsalon.PublicSalonController]
 * instead, scoped to `GALLERY`/`PORTFOLIO` on `ACTIVE` salons only.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/media")
@Tag(name = "Salon Media")
class MediaController(
    private val mediaAssetRepository: MediaAssetRepository,
    private val uploadMediaUseCase: UploadMediaUseCase,
    private val deleteMediaUseCase: DeleteMediaUseCase,
    private val salonPermissionResolver: SalonPermissionResolver,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PostMapping(consumes = [MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a media file for this salon (logo/cover/gallery/portfolio) - owner only")
    fun upload(
        @PathVariable salonId: UUID,
        @RequestParam mediaType: MediaType,
        @RequestParam file: MultipartFile,
        @AuthenticationPrincipal principal: UserDetails,
    ): MediaAssetResponse {
        val callerId = currentUserResolver.resolve(principal)
        val mediaAsset = uploadMediaUseCase.execute(
            UploadMediaCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                mediaType = mediaType,
                fileName = file.originalFilename ?: "upload",
                mimeType = file.contentType ?: "application/octet-stream",
                content = file.bytes,
            ),
        )
        return mediaAsset.toResponse()
    }

    @GetMapping
    @Operation(summary = "List this salon's media assets, optionally filtered by type - owner only")
    fun list(
        @PathVariable salonId: UUID,
        @RequestParam(required = false) mediaType: MediaType?,
        @AuthenticationPrincipal principal: UserDetails,
    ): List<MediaAssetResponse> {
        val callerId = currentUserResolver.resolve(principal)
        salonPermissionResolver.require(SalonId(salonId), callerId, Permission.MANAGE_SALON)
        val all = mediaAssetRepository.findBySalonId(SalonId(salonId))
        return all.filter { mediaType == null || it.mediaType == mediaType }.map { it.toResponse() }
    }

    @DeleteMapping("/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a media asset - owner only")
    fun delete(
        @PathVariable salonId: UUID,
        @PathVariable mediaId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        val mediaAsset = requireOwnedByRequestedSalon(salonId, mediaId)
        deleteMediaUseCase.execute(DeleteMediaCommand(mediaAsset.id, callerId))
    }

    /**
     * The path already carries [salonId], but a media asset's own
     * authority for "which salon do I belong to" is [MediaAsset.salonId],
     * never trusted from the URL alone — this is the check that turns "an
     * owner deletes media on a salon they own" into "an owner can only
     * touch media that actually belongs to *that* salon," closing the one
     * cross-salon path a same-owner-multiple-salons account could
     * otherwise exploit by passing a real `mediaId` under the wrong
     * `salonId` segment.
     */
    private fun requireOwnedByRequestedSalon(salonId: UUID, mediaId: UUID): MediaAsset {
        val mediaAsset = mediaAssetRepository.findById(MediaAssetId(mediaId))
            ?: throw MediaAssetNotFoundException(mediaId.toString())
        if (mediaAsset.salonId != SalonId(salonId)) {
            throw MediaAssetTenantMismatchException(mediaId.toString(), salonId.toString())
        }
        return mediaAsset
    }

    private fun MediaAsset.toResponse() = MediaAssetResponse(
        id = id.value,
        salonId = salonId.value,
        mediaType = mediaType,
        fileName = fileName,
        mimeType = mimeType,
        fileSize = fileSize,
        url = url,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
