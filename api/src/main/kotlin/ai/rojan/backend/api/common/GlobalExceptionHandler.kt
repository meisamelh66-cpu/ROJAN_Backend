package ai.rojan.backend.api.common

import ai.rojan.backend.domain.common.AmbiguousSalonContextException
import ai.rojan.backend.domain.common.BookingAccessDeniedException
import ai.rojan.backend.domain.common.BookingConflictException
import ai.rojan.backend.domain.common.BookingNotFoundException
import ai.rojan.backend.domain.common.BookingRoleNotAllowedException
import ai.rojan.backend.domain.common.BranchNotFoundException
import ai.rojan.backend.domain.common.CustomerAccessDeniedException
import ai.rojan.backend.domain.common.CustomerAlreadyExistsException
import ai.rojan.backend.domain.common.CustomerNotFoundException
import ai.rojan.backend.domain.common.CustomerNotLinkedToAccountException
import ai.rojan.backend.domain.common.CustomerTagNotFoundException
import ai.rojan.backend.domain.common.DocumentAlreadyAttachedException
import ai.rojan.backend.domain.common.EmailAlreadyRegisteredException
import ai.rojan.backend.domain.common.InactiveUserException
import ai.rojan.backend.domain.common.InvalidBookingStateException
import ai.rojan.backend.domain.common.InvalidCredentialsException
import ai.rojan.backend.domain.common.InvalidCustomerStateException
import ai.rojan.backend.domain.common.InvalidOtpException
import ai.rojan.backend.domain.common.InvalidTokenException
import ai.rojan.backend.domain.common.LoginRateLimitExceededException
import ai.rojan.backend.domain.common.MediaAssetNotFoundException
import ai.rojan.backend.domain.common.MediaSizeExceededException
import ai.rojan.backend.domain.common.MediaTypeInvalidException
import ai.rojan.backend.domain.common.MediaTypeMismatchException
import ai.rojan.backend.domain.common.OtpRateLimitExceededException
import ai.rojan.backend.domain.common.OtpVerifyRateLimitExceededException
import ai.rojan.backend.domain.common.RefreshRateLimitExceededException
import ai.rojan.backend.domain.common.RegisterRateLimitExceededException
import ai.rojan.backend.domain.common.SalonAccessDeniedException
import ai.rojan.backend.domain.common.InvalidMembershipAssignmentException
import ai.rojan.backend.domain.common.SalonDocumentNotFoundException
import ai.rojan.backend.domain.common.SalonInviteAcceptRateLimitExceededException
import ai.rojan.backend.domain.common.SalonInviteNotFoundException
import ai.rojan.backend.domain.common.SalonNotActiveException
import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.common.SalonNotReadyForActivationException
import ai.rojan.backend.domain.common.SalonSlugAlreadyTakenException
import ai.rojan.backend.domain.common.ScheduleOverrideNotFoundException
import ai.rojan.backend.domain.common.ServiceCategoryNotFoundException
import ai.rojan.backend.domain.common.ServiceNotFoundException
import ai.rojan.backend.domain.common.SpecialistBlockNotFoundException
import ai.rojan.backend.domain.common.SpecialistLeaveNotFoundException
import ai.rojan.backend.domain.common.SpecialistNotEligibleForServiceException
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
 * -shipped Android client isn't broken; [traceId] and [errorCode] are
 * additive fields, safe for existing clients to ignore. [traceId] lets a
 * client-reported failure be correlated with the matching server log line
 * below; [errorCode] is a stable, machine-readable identifier for clients
 * that want to branch on error type without parsing [message] or relying on
 * [status] alone (multiple error types can share one HTTP status).
 */
data class ApiError(
    val timestamp: Instant,
    val status: Int,
    val error: String,
    val errorCode: String,
    val message: String,
    val path: String,
    val traceId: String,
)

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(EmailAlreadyRegisteredException::class)
    fun handleEmailAlreadyRegistered(ex: EmailAlreadyRegisteredException, request: WebRequest) =
        respond(HttpStatus.CONFLICT, errorCodeFor(ex), ex.message.orEmpty(), request)

    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(ex: InvalidCredentialsException, request: WebRequest) =
        respond(HttpStatus.UNAUTHORIZED, errorCodeFor(ex), ex.message.orEmpty(), request)

    @ExceptionHandler(InvalidTokenException::class)
    fun handleInvalidToken(ex: InvalidTokenException, request: WebRequest) =
        respond(HttpStatus.UNAUTHORIZED, errorCodeFor(ex), ex.message.orEmpty(), request)

    @ExceptionHandler(InvalidOtpException::class)
    fun handleInvalidOtp(ex: InvalidOtpException, request: WebRequest) =
        respond(HttpStatus.UNAUTHORIZED, errorCodeFor(ex), ex.message.orEmpty(), request)

    @ExceptionHandler(OtpRateLimitExceededException::class, OtpVerifyRateLimitExceededException::class, SalonInviteAcceptRateLimitExceededException::class)
    fun handleOtpRateLimitExceeded(ex: Exception, request: WebRequest) =
        respond(HttpStatus.TOO_MANY_REQUESTS, errorCodeFor(ex), ex.message.orEmpty(), request)

    @ExceptionHandler(LoginRateLimitExceededException::class, RegisterRateLimitExceededException::class, RefreshRateLimitExceededException::class)
    fun handleAuthRateLimitExceeded(ex: Exception, request: WebRequest) =
        respond(HttpStatus.TOO_MANY_REQUESTS, errorCodeFor(ex), ex.message.orEmpty(), request)

    @ExceptionHandler(InactiveUserException::class)
    fun handleInactiveUser(ex: InactiveUserException, request: WebRequest) =
        respond(HttpStatus.FORBIDDEN, errorCodeFor(ex), ex.message.orEmpty(), request)

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
        SalonInviteNotFoundException::class,
        MediaAssetNotFoundException::class,
        SalonDocumentNotFoundException::class,
        NoResourceFoundException::class,
    )
    fun handleNotFound(ex: Exception, request: WebRequest) =
        respond(HttpStatus.NOT_FOUND, errorCodeFor(ex), ex.message.orEmpty(), request)

    @ExceptionHandler(MediaTypeInvalidException::class)
    fun handleMediaTypeInvalid(ex: MediaTypeInvalidException, request: WebRequest) =
        respond(HttpStatus.BAD_REQUEST, errorCodeFor(ex), ex.message.orEmpty(), request)

    @ExceptionHandler(MediaSizeExceededException::class)
    fun handleMediaSizeExceeded(ex: MediaSizeExceededException, request: WebRequest) =
        respond(HttpStatus.PAYLOAD_TOO_LARGE, errorCodeFor(ex), ex.message.orEmpty(), request)

    @ExceptionHandler(SalonAccessDeniedException::class, BookingAccessDeniedException::class, CustomerAccessDeniedException::class, BookingRoleNotAllowedException::class)
    fun handleAccessDenied(ex: Exception, request: WebRequest) =
        respond(HttpStatus.FORBIDDEN, errorCodeFor(ex), ex.message.orEmpty(), request)

    @ExceptionHandler(
        BookingConflictException::class,
        InvalidBookingStateException::class,
        IdempotencyKeyConflictException::class,
        AmbiguousSalonContextException::class,
        InvalidCustomerStateException::class,
        CustomerAlreadyExistsException::class,
        CustomerNotLinkedToAccountException::class,
        SpecialistNotEligibleForServiceException::class,
        SalonSlugAlreadyTakenException::class,
        SalonNotReadyForActivationException::class,
        InvalidMembershipAssignmentException::class,
        SalonNotActiveException::class,
        MediaTypeMismatchException::class,
        DocumentAlreadyAttachedException::class,
    )
    fun handleConflict(ex: Exception, request: WebRequest) =
        respond(HttpStatus.CONFLICT, errorCodeFor(ex), ex.message.orEmpty(), request)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException, request: WebRequest): ResponseEntity<ApiError> {
        val message = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return respond(HttpStatus.BAD_REQUEST, errorCodeFor(ex), message, request)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException, request: WebRequest) =
        respond(HttpStatus.BAD_REQUEST, errorCodeFor(ex), ex.message.orEmpty(), request)

    @ExceptionHandler(
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
        HttpMessageNotReadableException::class,
    )
    fun handleMalformedRequest(ex: Exception, request: WebRequest) =
        respond(HttpStatus.BAD_REQUEST, errorCodeFor(ex), "Malformed request: ${ex.message.orEmpty()}", request)

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(ex: HttpRequestMethodNotSupportedException, request: WebRequest) =
        respond(HttpStatus.METHOD_NOT_ALLOWED, errorCodeFor(ex), ex.message.orEmpty(), request)

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
        return respondWithTraceId(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", request, traceId)
    }

    /**
     * Stable, machine-readable identifier per exception type — deliberately
     * separate from [HttpStatus], since several exception types share one
     * status (e.g. every *NotFoundException is 404) but a client needs to
     * distinguish them without parsing the free-text [ApiError.message].
     */
    private fun errorCodeFor(ex: Throwable): String = when (ex) {
        is EmailAlreadyRegisteredException -> "EMAIL_ALREADY_REGISTERED"
        is InvalidCredentialsException -> "INVALID_CREDENTIALS"
        is InvalidTokenException -> "INVALID_TOKEN"
        is InvalidOtpException -> "INVALID_OTP"
        is OtpRateLimitExceededException -> "OTP_REQUEST_RATE_LIMITED"
        is OtpVerifyRateLimitExceededException -> "OTP_VERIFY_RATE_LIMITED"
        is SalonInviteAcceptRateLimitExceededException -> "SALON_INVITE_ACCEPT_RATE_LIMITED"
        is LoginRateLimitExceededException -> "LOGIN_RATE_LIMITED"
        is RegisterRateLimitExceededException -> "REGISTER_RATE_LIMITED"
        is RefreshRateLimitExceededException -> "REFRESH_RATE_LIMITED"
        is InactiveUserException -> "INACTIVE_USER"
        is UserNotFoundException, is UsernameNotFoundException -> "USER_NOT_FOUND"
        is SalonNotFoundException -> "SALON_NOT_FOUND"
        is BranchNotFoundException -> "BRANCH_NOT_FOUND"
        is ServiceCategoryNotFoundException -> "SERVICE_CATEGORY_NOT_FOUND"
        is ServiceNotFoundException -> "SERVICE_NOT_FOUND"
        is SpecialistNotFoundException -> "SPECIALIST_NOT_FOUND"
        is WorkingHoursNotFoundException -> "WORKING_HOURS_NOT_FOUND"
        is WeeklyAvailabilityNotFoundException -> "WEEKLY_AVAILABILITY_NOT_FOUND"
        is ScheduleOverrideNotFoundException -> "SCHEDULE_OVERRIDE_NOT_FOUND"
        is SpecialistLeaveNotFoundException -> "SPECIALIST_LEAVE_NOT_FOUND"
        is SpecialistBlockNotFoundException -> "SPECIALIST_BLOCK_NOT_FOUND"
        is BookingNotFoundException -> "BOOKING_NOT_FOUND"
        is CustomerNotFoundException -> "CUSTOMER_NOT_FOUND"
        is CustomerTagNotFoundException -> "CUSTOMER_TAG_NOT_FOUND"
        is SalonInviteNotFoundException -> "SALON_INVITE_NOT_FOUND"
        is MediaAssetNotFoundException -> "MEDIA_ASSET_NOT_FOUND"
        is MediaTypeInvalidException -> "MEDIA_TYPE_INVALID"
        is MediaSizeExceededException -> "MEDIA_SIZE_EXCEEDED"
        is MediaTypeMismatchException -> "MEDIA_TYPE_MISMATCH"
        is SalonDocumentNotFoundException -> "SALON_DOCUMENT_NOT_FOUND"
        is DocumentAlreadyAttachedException -> "DOCUMENT_ALREADY_ATTACHED"
        is NoResourceFoundException -> "RESOURCE_NOT_FOUND"
        is SalonAccessDeniedException, is BookingAccessDeniedException, is CustomerAccessDeniedException -> "ACCESS_DENIED"
        is BookingRoleNotAllowedException -> "BOOKING_ROLE_NOT_ALLOWED"
        is BookingConflictException -> "BOOKING_CONFLICT"
        is InvalidBookingStateException -> "INVALID_BOOKING_STATE"
        is InvalidCustomerStateException -> "INVALID_CUSTOMER_STATE"
        is CustomerAlreadyExistsException -> "CUSTOMER_ALREADY_EXISTS"
        is CustomerNotLinkedToAccountException -> "CUSTOMER_NOT_LINKED_TO_ACCOUNT"
        is SpecialistNotEligibleForServiceException -> "SPECIALIST_NOT_ELIGIBLE_FOR_SERVICE"
        is SalonSlugAlreadyTakenException -> "SALON_SLUG_ALREADY_TAKEN"
        is SalonNotReadyForActivationException -> "SALON_NOT_READY_FOR_ACTIVATION"
        is InvalidMembershipAssignmentException -> "INVALID_MEMBERSHIP_ASSIGNMENT"
        is SalonNotActiveException -> "SALON_NOT_ACTIVE"
        is IdempotencyKeyConflictException -> "IDEMPOTENCY_KEY_CONFLICT"
        is AmbiguousSalonContextException -> "SALON_CONTEXT_REQUIRED"
        is MethodArgumentNotValidException -> "VALIDATION_FAILED"
        is IllegalArgumentException -> "INVALID_ARGUMENT"
        is MissingServletRequestParameterException, is MethodArgumentTypeMismatchException, is HttpMessageNotReadableException -> "MALFORMED_REQUEST"
        is HttpRequestMethodNotSupportedException -> "METHOD_NOT_ALLOWED"
        else -> "INTERNAL_ERROR"
    }

    private fun respond(status: HttpStatus, code: String, message: String, request: WebRequest): ResponseEntity<ApiError> {
        val traceId = UUID.randomUUID().toString()
        if (status.is5xxServerError) {
            log.error("[traceId={}] {} {}", traceId, status, message)
        } else {
            log.debug("[traceId={}] {} {}", traceId, status, message)
        }
        return respondWithTraceId(status, code, message, request, traceId)
    }

    private fun respondWithTraceId(status: HttpStatus, code: String, message: String, request: WebRequest, traceId: String): ResponseEntity<ApiError> =
        ResponseEntity.status(status).body(
            ApiError(
                timestamp = Instant.now(),
                status = status.value(),
                error = status.reasonPhrase,
                errorCode = code,
                message = message,
                path = request.getDescription(false).removePrefix("uri="),
                traceId = traceId,
            ),
        )
}
