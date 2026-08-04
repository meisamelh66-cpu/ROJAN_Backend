package ai.rojan.backend.infrastructure.security

import ai.rojan.backend.application.port.TokenProviderPort
import ai.rojan.backend.application.port.TokenType
import ai.rojan.backend.domain.common.DomainException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private const val AUTHORIZATION_HEADER = "Authorization"
private const val BEARER_PREFIX = "Bearer "

@Component
class JwtAuthenticationFilter(
    private val tokenProvider: TokenProviderPort,
    private val userDetailsService: UserDetailsService,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader(AUTHORIZATION_HEADER)
        val alreadyAuthenticated = SecurityContextHolder.getContext().authentication != null

        if (header != null && header.startsWith(BEARER_PREFIX) && !alreadyAuthenticated) {
            try {
                val subject = tokenProvider.validateAndExtractSubject(header.removePrefix(BEARER_PREFIX))
                if (subject.type != TokenType.ACCESS) {
                    // A refresh token is signed the same way — reject it here rather than
                    // letting it double as an API credential.
                    filterChain.doFilter(request, response)
                    return
                }
                // Mobile-First Authentication Phase 1: resolve by userId (sub), not
                // email — a phone-only account has no email claim at all. See
                // RojanUserDetailsService's own doc comment for the full picture.
                val userDetails = userDetailsService.loadUserByUsername(subject.userId)
                val authentication = UsernamePasswordAuthenticationToken(
                    userDetails,
                    null,
                    userDetails.authorities,
                ).apply {
                    details = WebAuthenticationDetailsSource().buildDetails(request)
                }
                SecurityContextHolder.getContext().authentication = authentication
            } catch (ex: DomainException) {
                // Malformed/expired/invalid token — treat the request as anonymous.
                SecurityContextHolder.clearContext()
            } catch (ex: UsernameNotFoundException) {
                // Token was valid but the account no longer exists — treat as anonymous.
                SecurityContextHolder.clearContext()
            }
        }

        filterChain.doFilter(request, response)
    }
}
