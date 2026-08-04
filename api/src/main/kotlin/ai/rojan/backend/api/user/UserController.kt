package ai.rojan.backend.api.user

import ai.rojan.backend.api.auth.UserResponse
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
}
