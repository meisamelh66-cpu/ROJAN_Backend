package ai.rojan.backend.api.user

import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.media.DeleteUserAvatarCommand
import ai.rojan.backend.application.media.DeleteUserAvatarUseCase
import ai.rojan.backend.application.media.DeleteUserCoverCommand
import ai.rojan.backend.application.media.DeleteUserCoverUseCase
import ai.rojan.backend.application.media.UploadUserAvatarCommand
import ai.rojan.backend.application.media.UploadUserAvatarUseCase
import ai.rojan.backend.application.media.UploadUserCoverCommand
import ai.rojan.backend.application.media.UploadUserCoverUseCase
import ai.rojan.backend.application.port.MediaStoragePort
import ai.rojan.backend.application.salon.MembershipAccess
import ai.rojan.backend.application.salon.OwnedSalonAccess
import ai.rojan.backend.application.salon.ResolveMySalonAccessCommand
import ai.rojan.backend.application.salon.ResolveMySalonAccessUseCase
import ai.rojan.backend.application.salon.SpecialistAccess
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.media.MediaAssetRepository
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users")
class UserController(
    private val userRepository: UserRepository,
    private val resolveMySalonAccessUseCase: ResolveMySalonAccessUseCase,
    private val currentUserResolver: CurrentUserResolver,
    private val mediaAssetRepository: MediaAssetRepository,
    private val mediaStoragePort: MediaStoragePort,
    private val uploadUserAvatarUseCase: UploadUserAvatarUseCase,
    private val uploadUserCoverUseCase: UploadUserCoverUseCase,
    private val deleteUserAvatarUseCase: DeleteUserAvatarUseCase,
    private val deleteUserCoverUseCase: DeleteUserCoverUseCase,
) {

    @GetMapping("/me")
    @Operation(summary = "Get the currently authenticated user")
    fun me(@AuthenticationPrincipal principal: UserDetails): UserResponse {
        val userId = UserId(UUID.fromString(principal.username))
        val user = userRepository.findById(userId) ?: throw UserNotFoundException(principal.username)
        return user.toResponse()
    }

    // --- Profile media (Phase 5A.2, User Profile Media) ---------------------
    // Self-only by construction: the user id is always the resolved JWT
    // principal (currentUserResolver), never a path/body parameter.

    @PostMapping("/me/media/avatar", consumes = [MULTIPART_FORM_DATA_VALUE])
    @Operation(summary = "Upload / replace the authenticated user's avatar image")
    fun uploadAvatar(
        @RequestParam file: MultipartFile,
        @AuthenticationPrincipal principal: UserDetails,
    ): UserResponse {
        val callerId = currentUserResolver.resolve(principal)
        val user = uploadUserAvatarUseCase.execute(
            UploadUserAvatarCommand(
                callerId = callerId,
                originalName = file.originalFilename ?: "upload",
                mimeType = file.contentType ?: "application/octet-stream",
                content = file.bytes,
            ),
        )
        return user.toResponse()
    }

    @PostMapping("/me/media/cover", consumes = [MULTIPART_FORM_DATA_VALUE])
    @Operation(summary = "Upload / replace the authenticated user's profile-cover image")
    fun uploadCover(
        @RequestParam file: MultipartFile,
        @AuthenticationPrincipal principal: UserDetails,
    ): UserResponse {
        val callerId = currentUserResolver.resolve(principal)
        val user = uploadUserCoverUseCase.execute(
            UploadUserCoverCommand(
                callerId = callerId,
                originalName = file.originalFilename ?: "upload",
                mimeType = file.contentType ?: "application/octet-stream",
                content = file.bytes,
            ),
        )
        return user.toResponse()
    }

    @DeleteMapping("/me/media/avatar")
    @Operation(summary = "Remove the authenticated user's avatar image (idempotent)")
    fun deleteAvatar(@AuthenticationPrincipal principal: UserDetails): UserResponse {
        val callerId = currentUserResolver.resolve(principal)
        val user = deleteUserAvatarUseCase.execute(DeleteUserAvatarCommand(callerId))
        return user.toResponse()
    }

    @DeleteMapping("/me/media/cover")
    @Operation(summary = "Remove the authenticated user's profile-cover image (idempotent)")
    fun deleteCover(@AuthenticationPrincipal principal: UserDetails): UserResponse {
        val callerId = currentUserResolver.resolve(principal)
        val user = deleteUserCoverUseCase.execute(DeleteUserCoverCommand(callerId))
        return user.toResponse()
    }

    @GetMapping("/me/salon-access")
    @Operation(
        summary = "List every salon the authenticated user has access to - owned, staff membership, or their own specialist link - with resolved permissions per salon",
        description = "Permissions are always server-resolved (ai.rojan.backend.application.salon.SalonPermissionResolver) - never re-derive them client-side from `role`.",
    )
    fun salonAccess(@AuthenticationPrincipal principal: UserDetails): SalonAccessResponseDto {
        val callerId = currentUserResolver.resolve(principal)
        val access = resolveMySalonAccessUseCase.execute(ResolveMySalonAccessCommand(callerId))
        return SalonAccessResponseDto(
            ownedSalons = access.ownedSalons.map { it.toDto() },
            memberships = access.memberships.map { it.toDto() },
            specialistLinks = access.specialistLinks.map { it.toDto() },
        )
    }

    /** Resolves the avatar / cover slot references into rendered URLs (or null) - tenant-scoped by construction via [MediaAssetRepository.findByIdAndUserId], never an unscoped lookup. */
    private fun User.toResponse(): UserResponse = UserResponse(
        id = id.value,
        email = email?.value,
        phoneNumber = phoneNumber?.value,
        fullName = fullName,
        role = role,
        avatarUrl = avatarMediaId?.let { mediaAssetRepository.findByIdAndUserId(it, id)?.let { asset -> mediaStoragePort.resolveUrl(asset.storageKey) } },
        coverUrl = coverMediaId?.let { mediaAssetRepository.findByIdAndUserId(it, id)?.let { asset -> mediaStoragePort.resolveUrl(asset.storageKey) } },
    )

    private fun OwnedSalonAccess.toDto() = OwnedSalonAccessDto(
        salonId = salon.id.value,
        salonName = salon.name,
        active = salon.active,
        permissions = permissions,
    )

    private fun MembershipAccess.toDto() = MembershipAccessDto(
        membershipId = membership.id.value,
        salonId = salon.id.value,
        salonName = salon.name,
        active = salon.active,
        role = membership.role,
        permissions = permissions,
    )

    private fun SpecialistAccess.toDto() = SpecialistAccessDto(
        specialistId = specialist.id.value,
        salonId = salon.id.value,
        salonName = salon.name,
        active = salon.active,
        permissions = permissions,
    )
}
