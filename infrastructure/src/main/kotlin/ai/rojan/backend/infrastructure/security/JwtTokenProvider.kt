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
private const val CLAIM_ROLE = "role"
private const val CLAIM_TOKEN_TYPE = "type"

@Component
class JwtTokenProvider(
    private val jwtProperties: JwtProperties,
) : TokenProviderPort {

    private val signingKey: SecretKey = Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray())

    override fun generateAccessToken(user: User): IssuedToken =
        issue(user, TokenType.ACCESS, jwtProperties.accessTokenTtlMinutes, ChronoUnit.MINUTES)

    override fun generateRefreshToken(user: User): IssuedToken =
        issue(user, TokenType.REFRESH, jwtProperties.refreshTokenTtlDays, ChronoUnit.DAYS)

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
            email = claims[CLAIM_EMAIL] as? String ?: throw InvalidTokenException(),
            role = claims[CLAIM_ROLE] as? String ?: throw InvalidTokenException(),
            type = type,
        )
    }

    private fun issue(user: User, type: TokenType, ttl: Long, unit: ChronoUnit): IssuedToken {
        val now = Instant.now()
        val expiresAt = now.plus(ttl, unit)
        val token = Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(user.id.value.toString())
            .claim(CLAIM_EMAIL, user.email.value)
            .claim(CLAIM_ROLE, user.role.name)
            .claim(CLAIM_TOKEN_TYPE, type.name)
            .issuer(jwtProperties.issuer)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(signingKey)
            .compact()
        return IssuedToken(token = token, expiresAt = expiresAt)
    }
}
