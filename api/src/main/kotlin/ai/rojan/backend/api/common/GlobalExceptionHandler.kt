package ai.rojan.backend.api.common

import ai.rojan.backend.domain.common.BookingAccessDeniedException
import ai.rojan.backend.domain.common.BookingConflictException
import ai.rojan.backend.domain.common.BookingNotFoundException
import ai.rojan.backend.domain.common.BranchNotFoundException
import ai.rojan.backend.domain.common.CustomerAccessDeniedException
import ai.rojan.backend.domain.common.CustomerAlreadyExistsException
import ai.rojan.backend.domain.common.CustomerNotFoundException
import ai.rojan.backend.domain.common.CustomerNotLinkedToAccountException
import ai.rojan.backend.domain.common.CustomerTagNotFoundException
import ai.rojan.backend.domain.common.EmailAlreadyRegisteredException
import ai.rojan.backend.domain.common.InactiveUserException
import ai.rojan.backend.domain.common.InvalidBookingStateException
import ai.rojan.backend.domain.common.InvalidCredentialsException
import ai.rojan.backend.domain.common.InvalidCustomerStateException
import ai.rojan.backend.domain.common.InvalidTokenException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.ScheduleOverrideNotFoundException
import ai.rojan.backend.domain.common.ServiceCategoryNotFoundException
import ai.rojan.backend.domain.common.ServiceNotFoundException
import ai.rojan.backend.domain.common.SpecialistBlockNotFoundException
import ai.rojan.backend.domain.common.SpecialistLeaveNotFoundException
import ai.rojan.backend.domain.common.SpecialistNotFoundException
import ai.rojan.backend.domain.common.UserNotFoundException
import ai.rojan.backend.domain.common.WeeklyAvailabilityNotFoundException
import ai.rojan.backend.domain.common.WorkingHoursNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.Instant
import java.util.UUID

/**
 * A single, consistent error shape for every endpoint. Kept as-is (rather
 * than migrating to RFC 7807 `application/problem+json`) so the already
 *-shipped Android client isn't broken; [traceId] is the one additive field,
 * safe for existing clients to ignore, that lets a client-reported failure
 * be correlated with the matching server log line below.
 */
data class ApiError(
    val timestamp: Instant,
    val status: Int,
    val error: String,
    val message: String,
    val path: String,
    val traceId: String,
)

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

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

    @ExceptionHandler(
        UserNotFoundException::class,
        UsernameNotFoundException::class,
        SalonNotFoundException::class,
        BranchNotFoundException::class,
        ServiceCategoryNotFoundException::class,
        ServiceNotFoundException::class,
        SpecialistNotFoundException::class,
        WorkingHoursNotFoundException::class,
        WeeklyAvailabilityNotFoundException::class,
        ScheduleOverrideNotFoundException::class,
        SpecialistLeaveNotFoundException::class,
        SpecialistBlockNotFoundException::class,
        BookingNotFoundException::class,
        CustomerNotFoundException::class,
        CustomerTagNotFoundException::class,
        NoResourceFoundException::class,
    )
    fun handleNotFound(ex: Exception, request: WebRequest) =
        respond(HttpStatus.NOT_FOUND, ex.message.orEmpty(), request)

    @ExceptionHandler(SalonAccessDeniedException::class, BookingAccessDeniedException::class, CustomerAccessDeniedException::class)
    fun handleAccessDenied(ex: Exception, request: WebRequest) =
        respond(HttpStatus.FORBIDDEN, ex.message.orEmpty(), request)

    @ExceptionHandler(
        BookingConflictException::class,
        InvalidBookingStateException::class,
        IdempotencyKeyConflictException::class,
        InvalidCustomerStateException::class,
        CustomerAlreadyExistsException::class,
        CustomerNotLinkedToAccountException::class,
    )
    fun handleConflict(ex: Exception, request: WebRequest) =
        respond(HttpStatus.CONFLICT, ex.message.orEmpty(), request)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException, request: WebRequest): ResponseEntity<ApiError> {
        val message = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return respond(HttpStatus.BAD_REQUEST, message, request)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException, request: WebRequest) =
        respond(HttpStatus.BAD_REQUEST, ex.message.orEmpty(), request)

    @ExceptionHandler(
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
        HttpMessageNotReadableException::class,
    )
    fun handleMalformedRequest(ex: Exception, request: WebRequest) =
        respond(HttpStatus.BAD_REQUEST, "Malformed request: ${ex.message.orEmpty()}", request)

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(ex: HttpRequestMethodNotSupportedException, request: WebRequest) =
        respond(HttpStatus.METHOD_NOT_ALLOWED, ex.message.orEmpty(), request)

    /**
     * Last resort: never let an unmapped exception leak a stack trace or
     * internal detail to the client (OWASP API9 / information exposure).
     * The full exception is logged server-side against the same [ApiError.traceId]
     * returned to the caller, so support can still correlate the report.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception, request: WebRequest): ResponseEntity<ApiError> {
        val traceId = UUID.randomUUID().toString()
        log.error("Unhandled exception [traceId={}] while processing {}", traceId, request.getDescription(false), ex)
        return respondWithTraceId(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, traceId)
    }

    private fun respond(status: HttpStatus, message: String, request: WebRequest): ResponseEntity<ApiError> {
        val traceId = UUID.randomUUID().toString()
        if (status.is5xxServerError) {
            log.error("[traceId={}] {} {}", traceId, status, message)
        } else {
            log.debug("[traceId={}] {} {}", traceId, status, message)
        }
        return respondWithTraceId(status, message, request, traceId)
    }

    private fun respondWithTraceId(status: HttpStatus, message: String, request: WebRequest, traceId: String): ResponseEntity<ApiError> =
        ResponseEntity.status(status).body(
            ApiError(
                timestamp = Instant.now(),
                status = status.value(),
                error = status.reasonPhrase,
                message = message,
                path = request.getDescription(false).removePrefix("uri="),
                traceId = traceId,
            ),
        )
}
