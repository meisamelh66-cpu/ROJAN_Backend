package ai.rojan.backend.domain.salon

import java.security.SecureRandom
import java.util.Base64

/**
 * Pure token generation for [SalonInvite.token], mirroring [SalonSlugGenerator]'s
 * "pure domain utility, no port needed" shape. 256 bits of [SecureRandom]
 * entropy, URL-safe Base64 (no `+`/`/`/`=`) so the raw token drops directly
 * into a QR-encoded deep link without escaping.
 */
object SalonInviteTokenGenerator {

    private const val TOKEN_BYTES = 32
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
