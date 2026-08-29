package ai.rojan.backend.api.salon

import ai.rojan.backend.api.common.ApiError
import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.common.toPagedResponse
import ai.rojan.backend.application.media.AssignIdentityMediaCommand
import ai.rojan.backend.application.media.AssignIdentityMediaUseCase
import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.application.salon.ActivateSalonCommand
import ai.rojan.backend.application.salon.ActivateSalonUseCase
import ai.rojan.backend.application.salon.ChangeSalonSlugCommand
import ai.rojan.backend.application.salon.ChangeSalonSlugUseCase
import ai.rojan.backend.application.salon.CreateSalonCommand
import ai.rojan.backend.application.salon.CreateSalonUseCase
import ai.rojan.backend.application.salon.DeactivateSalonCommand
import ai.rojan.backend.application.salon.DeactivateSalonUseCase
import ai.rojan.backend.application.salon.GenerateSalonQrCodeCommand
import ai.rojan.backend.application.salon.GenerateSalonQrCodeUseCase
import ai.rojan.backend.application.salon.UpdateSalonCommand
import ai.rojan.backend.application.salon.UpdateSalonUseCase
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.media.MediaAssetId
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
import java.util.UUID

@RestController
@RequestMapping("/api/v1/salons")
@Tag(name = "Salons")
class SalonController(
    private val salonRepository: SalonRepository,
    private val createSalonUseCase: CreateSalonUseCase,
    private val updateSalonUseCase: UpdateSalonUseCase,
    private val deactivateSalonUseCase: DeactivateSalonUseCase,
    private val changeSalonSlugUseCase: ChangeSalonSlugUseCase,
    private val activateSalonUseCase: ActivateSalonUseCase,
    private val generateSalonQrCodeUseCase: GenerateSalonQrCodeUseCase,
    private val currentUserResolver: CurrentUserResolver,
    private val mediaAssetRepository: MediaAssetRepository,
    private val mediaStoragePort: MediaStoragePort,
    private val assignIdentityMediaUseCase: AssignIdentityMediaUseCase,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new salon owned by the authenticated user")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Salon created"),
        ApiResponse(
            responseCode = "400",
            description = "Validation failed",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(responseCode = "401", description = "Missing or invalid bearer token"),
    )
    fun create(
        @Valid @RequestBody request: CreateSalonRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonResponse {
        val ownerId = currentUserResolver.resolve(principal)
        val salon = createSalonUseCase.execute(
            CreateSalonCommand(
                ownerId = ownerId,
                name = request.name,
                description = request.description,
                phone = request.phone,
                email = request.email,
                address = request.address,
            ),
        )
        return salon.toResponse()
    }

    @GetMapping("/mine")
    @Operation(summary = "List salons owned by the authenticated user")
    fun mine(@AuthenticationPrincipal principal: UserDetails): List<SalonResponse> {
        val ownerId = currentUserResolver.resolve(principal)
        return salonRepository.findByOwnerId(ownerId).map { it.toResponse() }
    }

    @GetMapping
    @Operation(summary = "Browse active salons, paginated and optionally filtered by name")
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) name: String?,
        @RequestParam(defaultValue = "ASC") sortDirection: String,
    ): PagedResponse<SalonResponse> {
        val result = salonRepository.findAllActive(PageRequest(page, size), name, SortDirection.valueOf(sortDirection.uppercase()))
        return result.toPagedResponse { it.toResponse() }
    }

    @GetMapping("/{salonId}")
    @Operation(summary = "Get a salon by id")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Salon found"),
        ApiResponse(
            responseCode = "404",
            description = "No salon with this id",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun get(@PathVariable salonId: UUID): SalonResponse =
        findSalonOrThrow(salonId).toResponse()

    @PutMapping("/{salonId}")
    @Operation(summary = "Update a salon (owner only)")
    fun update(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: UpdateSalonRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonResponse {
        val callerId = currentUserResolver.resolve(principal)
        val salon = updateSalonUseCase.execute(
            UpdateSalonCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                name = request.name,
                description = request.description,
                phone = request.phone,
                email = request.email,
                address = request.address,
                latitude = request.latitude,
                longitude = request.longitude,
                city = request.city,
            ),
        )
        return salon.toResponse()
    }

    @DeleteMapping("/{salonId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deactivate a salon (owner only)")
    fun deactivate(@PathVariable salonId: UUID, @AuthenticationPrincipal principal: UserDetails) {
        val callerId = currentUserResolver.resolve(principal)
        deactivateSalonUseCase.execute(DeactivateSalonCommand(SalonId(salonId), callerId))
    }

    @PatchMapping("/{salonId}/slug")
    @Operation(summary = "Change a salon's public slug, e.g. before printing QR material (owner only)")
    fun changeSlug(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: ChangeSalonSlugRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonResponse {
        val callerId = currentUserResolver.resolve(principal)
        val salon = changeSalonSlugUseCase.execute(ChangeSalonSlugCommand(SalonId(salonId), callerId, request.slug))
        return salon.toResponse()
    }

    @PostMapping("/{salonId}/activate")
    @Operation(
        summary = "Activate a salon so it becomes publicly discoverable (owner only)",
        description = "Requires at least one active service, one active specialist, and one configured working-hours day - a Salon can be fully managed while DRAFT, it just won't appear via GET /api/v1/public/salons/{slug} until activated.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Salon is now ACTIVE"),
        ApiResponse(
            responseCode = "409",
            description = "The salon is missing one or more activation requirements",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun activate(@PathVariable salonId: UUID, @AuthenticationPrincipal principal: UserDetails): SalonResponse {
        val callerId = currentUserResolver.resolve(principal)
        val salon = activateSalonUseCase.execute(ActivateSalonCommand(SalonId(salonId), callerId))
        return salon.toResponse()
    }

    @GetMapping("/{salonId}/qr-code", produces = [MediaType.IMAGE_PNG_VALUE])
    @Operation(summary = "Generate a printable QR code (PNG) encoding this salon's public URL (owner only)")
    fun qrCode(
        @PathVariable salonId: UUID,
        @RequestParam(defaultValue = "512") size: Int,
        @AuthenticationPrincipal principal: UserDetails,
    ): ResponseEntity<ByteArray> {
        val callerId = currentUserResolver.resolve(principal)
        val png = generateSalonQrCodeUseCase.execute(GenerateSalonQrCodeCommand(SalonId(salonId), callerId, size))
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png)
    }

    @PutMapping("/{salonId}/identity-media")
    @Operation(summary = "Assign or clear a salon's logo/cover media (owner or MANAGE_MEDIA member)")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Identity media updated"),
        ApiResponse(
            responseCode = "409",
            description = "The referenced media asset's type doesn't match the requested slot",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "No such media asset for this salon",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    fun assignIdentityMedia(
        @PathVariable salonId: UUID,
        @Valid @RequestBody request: AssignIdentityMediaRequest,
        @AuthenticationPrincipal principal: UserDetails,
    ): SalonResponse {
        val callerId = currentUserResolver.resolve(principal)
        val salon = assignIdentityMediaUseCase.execute(
            AssignIdentityMediaCommand(
                salonId = SalonId(salonId),
                callerId = callerId,
                slot = request.slot,
                mediaId = request.mediaId?.let { MediaAssetId(it) },
            ),
        )
        return salon.toResponse()
    }

    private fun findSalonOrThrow(salonId: UUID): Salon =
        salonRepository.findById(SalonId(salonId)) ?: throw SalonNotFoundException(salonId.toString())

    /** [logoMediaId]/[coverMediaId] are resolved to a servable URL here, at the response boundary - `Salon` itself never stores one (media referenced by id, not URL). */
    private fun Salon.toResponse() = SalonResponse(
        id = id.value,
        ownerId = ownerId.value,
        name = name,
        description = description,
        phone = phone,
        email = email,
        address = address,
        slug = slug,
        onboardingStatus = onboardingStatus,
        logoMediaId = logoMediaId?.value,
        coverMediaId = coverMediaId?.value,
        logoUrl = logoMediaId?.let { resolveMediaUrl(it) },
        coverImageUrl = coverMediaId?.let { resolveMediaUrl(it) },
        latitude = latitude,
        longitude = longitude,
        city = city,
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun Salon.resolveMediaUrl(mediaId: MediaAssetId): String? =
        mediaAssetRepository.findByIdAndSalonId(mediaId, id)?.let { mediaStoragePort.resolveUrl(it.storageKey) }
}
