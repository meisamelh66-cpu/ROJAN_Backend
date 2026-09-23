package ai.rojan.backend.api.platformauthority

import ai.rojan.backend.api.common.CurrentUserResolver
import ai.rojan.backend.api.common.PagedResponse
import ai.rojan.backend.api.common.toPagedResponse
import ai.rojan.backend.application.platformauthority.DeactivatePlatformCustomerAccountCommand
import ai.rojan.backend.application.platformauthority.DeactivatePlatformCustomerAccountUseCase
import ai.rojan.backend.application.platformauthority.ListPlatformCustomerAccountsQuery
import ai.rojan.backend.application.platformauthority.ListPlatformCustomerAccountsUseCase
import ai.rojan.backend.application.platformauthority.ReactivatePlatformCustomerAccountCommand
import ai.rojan.backend.application.platformauthority.ReactivatePlatformCustomerAccountUseCase
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Platform Management API Contract (Customer) - PLATFORM_ADMIN or PLATFORM_REVIEWER may list,
 * PLATFORM_ADMIN only may deactivate/reactivate, same split as
 * [PlatformAuthorityManagerController]. Deliberately named "Customer**Account**" throughout (route
 * segment excepted, which mirrors the already-agreed `/platform-authority/managers` shape) - this
 * controller resolves [ai.rojan.backend.domain.user.User] rows only, via
 * [ListPlatformCustomerAccountsUseCase], and never touches
 * [ai.rojan.backend.domain.customer.Customer] (the salon-private CRM aggregate) or
 * [ai.rojan.backend.domain.booking.Booking] in any way - there is no salon-association field on
 * [PlatformCustomerAccountResponse] because none is safe to expose here (see this contract's own
 * design report for the full boundary).
 */
@RestController
@RequestMapping("/api/v1/platform-authority/customers")
@Tag(name = "Platform Authority - Customers")
class PlatformAuthorityCustomerController(
    private val listPlatformCustomerAccountsUseCase: ListPlatformCustomerAccountsUseCase,
    private val deactivatePlatformCustomerAccountUseCase: DeactivatePlatformCustomerAccountUseCase,
    private val reactivatePlatformCustomerAccountUseCase: ReactivatePlatformCustomerAccountUseCase,
    private val currentUserResolver: CurrentUserResolver,
) {

    @GetMapping
    @Operation(summary = "List CUSTOMER accounts, paginated and optionally searched by name/phone - identity only, never salon-private CRM data (PLATFORM_ADMIN or PLATFORM_REVIEWER)")
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "ASC") sortDirection: String,
        @AuthenticationPrincipal principal: UserDetails,
    ): PagedResponse<PlatformCustomerAccountResponse> {
        val callerId = currentUserResolver.resolve(principal)
        val result = listPlatformCustomerAccountsUseCase.execute(
            ListPlatformCustomerAccountsQuery(callerId, page, size, search, SortDirection.valueOf(sortDirection.uppercase())),
        )
        return result.toPagedResponse { it.toResponse() }
    }

    @PostMapping("/{customerAccountId}/deactivate")
    @Operation(summary = "Deactivate a CUSTOMER account - blocks login/refresh only, never touches any salon's private CRM record for this person (PLATFORM_ADMIN only)")
    fun deactivate(@PathVariable customerAccountId: UUID, @AuthenticationPrincipal principal: UserDetails): PlatformCustomerAccountResponse {
        val callerId = currentUserResolver.resolve(principal)
        return deactivatePlatformCustomerAccountUseCase.execute(
            DeactivatePlatformCustomerAccountCommand(callerId, UserId(customerAccountId)),
        ).toResponse()
    }

    @PostMapping("/{customerAccountId}/reactivate")
    @Operation(summary = "Reactivate a previously deactivated CUSTOMER account (PLATFORM_ADMIN only)")
    fun reactivate(@PathVariable customerAccountId: UUID, @AuthenticationPrincipal principal: UserDetails): PlatformCustomerAccountResponse {
        val callerId = currentUserResolver.resolve(principal)
        return reactivatePlatformCustomerAccountUseCase.execute(
            ReactivatePlatformCustomerAccountCommand(callerId, UserId(customerAccountId)),
        ).toResponse()
    }

    private fun User.toResponse() = PlatformCustomerAccountResponse(
        id = id.value,
        phoneNumber = phoneNumber?.value,
        fullName = fullName,
        active = active,
        createdAt = createdAt,
    )
}
