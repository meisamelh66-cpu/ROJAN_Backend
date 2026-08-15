package ai.rojan.backend.api.user

import ai.rojan.backend.api.auth.UserResponse
import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.application.salon.MembershipAccess
import ai.rojan.backend.application.salon.OwnedSalonAccess
import ai.rojan.backend.application.salon.ResolveMySalonAccessCommand
import ai.rojan.backend.application.salon.ResolveMySalonAccessUseCase
import ai.rojan.backend.application.salon.SpecialistAccess
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users")
class UserController(
    private val userRepository: UserRepository,
    private val resolveMySalonAccessUseCase: ResolveMySalonAccessUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @GetMapping("/me")
    @Operation(summary = "Get the currently authenticated user")
    fun me(@AuthenticationPrincipal principal: UserDetails): UserResponse {
        val userId = UserId(UUID.fromString(principal.username))
        val user = userRepository.findById(userId) ?: throw UserNotFoundException(principal.username)
        return UserResponse(
            id = user.id.value,
            email = user.email?.value,
            phoneNumber = user.phoneNumber?.value,
            fullName = user.fullName,
            role = user.role,
        )
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
