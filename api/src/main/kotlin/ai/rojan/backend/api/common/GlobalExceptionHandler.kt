package ai.rojan.backend.api.common

import ai.rojan.backend.domain.common.EmailAlreadyRegisteredException
import ai.rojan.backend.domain.common.InactiveUserException
import ai.rojan.backend.domain.common.InvalidCredentialsException
import ai.rojan.backend.domain.common.InvalidTokenException
import ai.rojan.backend.domain.common.UserNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import java.time.Instant

data class ApiError(
    val timestamp: Instant,
    val status: Int,
    val error: String,
    val message: String,
    val path: String,
)

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(EmailAlreadyRegisteredException::class)
    fun handleEmailAlreadyRegistered(ex: EmailAlreadyRegisteredException, request: WebRequest) =
        respond(HttpStatus.CONFLICT, ex.message.orEmpty(), request)

    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(ex: InvalidCredentialsException, request: WebRequest) =
        respond(HttpStatus.UNAUTHORIZED, ex.message.orEmpty(), request)

    @ExceptionHandler(InvalidTokenException::class)
    fun handleInvalidToken(ex: InvalidTokenException, request: WebRequest) =
        respond(HttpStatus.UNAUTHORIZED, ex.message.orEmpty(), request)

    @ExceptionHandler(InactiveUserException::class)
    fun handleInactiveUser(ex: InactiveUserException, request: WebRequest) =
        respond(HttpStatus.FORBIDDEN, ex.message.orEmpty(), request)

    @ExceptionHandler(UserNotFoundException::class, UsernameNotFoundException::class)
    fun handleUserNotFound(ex: Exception, request: WebRequest) =
        respond(HttpStatus.NOT_FOUND, ex.message.orEmpty(), request)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException, request: WebRequest): ResponseEntity<ApiError> {
        val message = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return respond(HttpStatus.BAD_REQUEST, message, request)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException, request: WebRequest) =
        respond(HttpStatus.BAD_REQUEST, ex.message.orEmpty(), request)

    private fun respond(status: HttpStatus, message: String, request: WebRequest): ResponseEntity<ApiError> =
        ResponseEntity.status(status).body(
            ApiError(
                timestamp = Instant.now(),
                status = status.value(),
                error = status.reasonPhrase,
                message = message,
                path = request.getDescription(false).removePrefix("uri="),
            ),
        )
}
