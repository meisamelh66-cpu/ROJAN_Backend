package ai.rojan.backend.api.media

import ai.rojan.backend.api.common.ApiError
import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.media.DeleteMediaCommand
import ai.rojan.backend.application.media.DeleteMediaUseCase
import ai.rojan.backend.application.media.ListMediaQuery
import ai.rojan.backend.application.media.ListMediaUseCase
import ai.rojan.backend.application.media.ReorderMediaCommand
import ai.rojan.backend.application.media.ReorderMediaUseCase
import ai.rojan.backend.application.media.UploadMediaCommand
import ai.rojan.backend.application.media.UploadMediaUseCase
import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.domain.media.MediaAsset
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaType
import ai.rojan.backend.domain.salon.SalonId
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

/**
 * Media Foundation (Phase 1). Direct multipart upload rather than a
 * signed-URL two-phase flow - see `MediaStoragePort`'s own doc comment for
 * why, and why [ai.rojan.backend.domain.media.MediaAssetStatus.PENDING]
 * exists even though nothing here currently leaves a row parked there.
 * Not paginated: a salon's media count (logo/cover + a handful of
 * gallery/portfolio images) is small enough that a plain list is honest to
 * actual scale - a disclosed simplification, not an oversight.
 */
@RestController
@RequestMapping("/api/v1/salons/{salonId}/media")
@Tag(name = "Media")
class MediaController(
    private val uploadMediaUseCase: UploadMediaUseCase,
    private val listMediaUseCase: ListMediaUseCase,
    private val deleteMediaUseCase: DeleteMediaUseCase,
    private val reorderMediaUseCase: ReorderMediaUseCase,
    private val mediaStoragePort: MediaStoragePort,
    private val currentUserResolver: CurrentUserResolver,
) {

    @PostMapping(consumes = ["multipart/form-data"])
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a media asset (owner or MANAGE_MEDIA member)")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Media uploaded"),
        ApiResponse(
            responseCode = "400",
            description = "Disallowed mime type for the declared media type, or a PORTFOLIO/SERVICE_IMAGE upload with no targetId",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "targetId doesn't name a real specialist/service belonging to this salon",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "413",
            description = "File exceeds the size ceiling for its category",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun upload(
        @PathVariable salonId: UUID,
        @RequestParam file: MultipartFile,
        @RequestParam mediaType: MediaType,
        @RequestParam(required = false) targetId: UUID?,
        @AuthenticationPrincipal principal: UserDetails,
    ): MediaAssetResponse {
        val callerId = currentUserResolver.resolve(principal)
        val mediaAsset = uploadMediaUseCase.execute(
            UploadMediaCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                mediaType = mediaType,
                content = file.bytes,
                originalName = file.originalFilename ?: file.name,
                mimeType = file.contentType ?: "application/octet-stream",
                targetId = targetId,
            ),
        )
        return mediaAsset.toResponse()
    }

    @GetMapping
    @Operation(
        summary = "List a salon's media, optionally filtered by type and/or target",
        description = "targetId narrows to one specialist's PORTFOLIO or one service's SERVICE_IMAGE set - omit it for salon-flat types (LOGO/COVER/GALLERY). Results are pre-sorted by display order.",
    )
    fun list(
        @PathVariable salonId: UUID,
        @RequestParam(required = false) mediaType: MediaType?,
        @RequestParam(required = false) targetId: UUID?,
    ): List<MediaAssetResponse> =
        listMediaUseCase.execute(ListMediaQuery(SalonId(salonId), mediaType, targetId)).map { it.toResponse() }

    @DeleteMapping("/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a media asset (owner or MANAGE_MEDIA member)")
    fun delete(
        @PathVariable salonId: UUID,
        @PathVariable mediaId: UUID,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        deleteMediaUseCase.execute(DeleteMediaCommand(SalonId(salonId), callerId, MediaAssetId(mediaId)))
    }

    @PatchMapping("/reorder")
    @Operation(
        summary = "Reorder every media asset within one (mediaType, targetId) group (owner or MANAGE_MEDIA member)",
        description = "mediaIds must be exactly that group's current active members, just permuted - a partial or foreign list is rejected outright, never partially applied.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Reordered"),
        ApiResponse(
            responseCode = "400",
            description = "mediaIds includes an id outside this exact (mediaType, targetId) group",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reorder(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: ReorderMediaRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ) {
        val callerId = currentUserResolver.resolve(principal)
        reorderMediaUseCase.execute(
            ReorderMediaCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                mediaType = request.mediaType,
                targetId = request.targetId,
                orderedMediaIds = request.mediaIds.map { MediaAssetId(it) },
            ),
        )
    }

    private fun MediaAsset.toResponse() = MediaAssetResponse(
        id = id.value,
        salonId = salonId.value,
        mediaType = mediaType,
        originalName = originalName,
        mimeType = mimeType,
        fileSize = fileSize,
        status = status,
        url = mediaStoragePort.resolveUrl(storageKey),
        createdAt = createdAt,
        targetId = targetId,
        displayOrder = displayOrder,
    )
}
