package ai.rojan.backend.infrastructure.security

import ai.rojan.backend.application.port.IssuedToken
import ai.rojan.backend.application.port.TokenProviderPort
import ai.rojan.backend.application.port.TokenSubject
import ai.rojan.backend.application.port.TokenType
import ai.rojan.backend.domain.common.InvalidTokenException
import ai.rojan.backend.domain.user.User
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

private const val CLAIM_EMAIL = "email"
private const val CLAIM_PHONE = "phone"
private const val CLAIM_ROLE = "role"
private const val CLAIM_TOKEN_TYPE = "type"

/** Refresh Token Rotation (`BACKEND_REFRESH_TOKEN_SECURITY_PLAN.md`): carried unchanged through every rotation of the same refresh-token family - never present on an access token. */
private const val CLAIM_FAMILY_ID = "fid"

@Component
class JwtTokenProvider(
    private val jwtProperties: JwtProperties,
) : TokenProviderPort {

    private val signingKey: SecretKey = Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray())

    override fun generateAccessToken(user: User): IssuedToken =
        issue(user, TokenType.ACCESS, jwtProperties.accessTokenTtlMinutes, ChronoUnit.MINUTES, familyId = null)

    override fun generateRefreshToken(user: User, familyId: String): IssuedToken =
        issue(user, TokenType.REFRESH, jwtProperties.refreshTokenTtlDays, ChronoUnit.DAYS, familyId = familyId)

    override fun validateAndExtractSubject(token: String): TokenSubject {
        val claims = try {
            Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(jwtProperties.issuer)
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (ex: ExpiredJwtException) {
            throw InvalidTokenException()
        } catch (ex: JwtException) {
            throw InvalidTokenException()
        } catch (ex: IllegalArgumentException) {
            throw InvalidTokenException()
        }

        val type = (claims[CLAIM_TOKEN_TYPE] as? String)?.uppercase()?.let {
            runCatching { TokenType.valueOf(it) }.getOrNull()
        } ?: throw InvalidTokenException()

        return TokenSubject(
            userId = claims.subject,
            // Mobile-First Authentication Phase 1: no longer required — a
            // phone-only account's tokens carry no email claim at all. `sub`
            // (userId) is the one identity anchor every token guarantees.
            email = claims[CLAIM_EMAIL] as? String,
            role = claims[CLAIM_ROLE] as? String ?: throw InvalidTokenException(),
            type = type,
            // The JWT library's own `id` claim (RFC 7519 `jti`) - every token this
            // provider ever issues carries one (see `issue` below), so a missing one
            // here would mean a token signed by something other than this provider,
            // which the signature check above already would have rejected.
            jti = claims.id ?: throw InvalidTokenException(),
            // Absent on every access token, and on a legacy refresh token issued
            // before this claim existed - both are valid, expected states, not errors.
            familyId = claims[CLAIM_FAMILY_ID] as? String,
        )
    }

    private fun issue(user: User, type: TokenType, ttl: Long, unit: ChronoUnit, familyId: String?): IssuedToken {
        val now = Instant.now()
        val expiresAt = now.plus(ttl, unit)
        val jti = UUID.randomUUID().toString()
        val builder = Jwts.builder()
            .id(jti)
            .subject(user.id.value.toString())
            .claim(CLAIM_ROLE, user.role.name)
            .claim(CLAIM_TOKEN_TYPE, type.name)
            .issuer(jwtProperties.issuer)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
        // Mobile-First Authentication Phase 1: each claim is only present when
        // the account actually has that identifier — a phone-only account
        // carries no "email" claim, an email-only account carries no "phone"
        // claim. Neither is ever required by anything downstream; `sub` is.
        user.email?.let { builder.claim(CLAIM_EMAIL, it.value) }
        user.phoneNumber?.let { builder.claim(CLAIM_PHONE, it.value) }
        familyId?.let { builder.claim(CLAIM_FAMILY_ID, it) }
        val token = builder.signWith(signingKey).compact()
        return IssuedToken(token = token, expiresAt = expiresAt, jti = jti)
    }
}
