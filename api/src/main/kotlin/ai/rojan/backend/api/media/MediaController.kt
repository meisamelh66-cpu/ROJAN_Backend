package ai.rojan.backend.api.media

import ai.rojan.backend.api.common.ApiError
import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.media.DeleteMediaCommand
import ai.rojan.backend.application.media.DeleteMediaUseCase
import ai.rojan.backend.application.media.ListMediaQuery
import ai.rojan.backend.application.media.ListMediaUseCase
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
import org.springframework.http.HttpStatus
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
            description = "Disallowed mime type for the declared media type",
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
            ),
        )
        return mediaAsset.toResponse()
    }

    @GetMapping
    @Operation(summary = "List a salon's media, optionally filtered by type")
    fun list(
        @PathVariable salonId: UUID,
        @RequestParam(required = false) mediaType: MediaType?,
    ): List<MediaAssetResponse> =
        listMediaUseCase.execute(ListMediaQuery(SalonId(salonId), mediaType)).map { it.toResponse() }

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
    )
}
