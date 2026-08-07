package ai.rojan.backend.infrastructure.otp

import ai.rojan.backend.domain.auth.OneTimePassword
import ai.rojan.backend.domain.auth.OtpRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Test-only placeholder — an in-memory [OtpRepository], functionally
 * equivalent to [RedisOtpRepository] but with no external Redis dependency.
 * Same "test needs a dependency-free stand-in for an external adapter"
 * reasoning as [ai.rojan.backend.infrastructure.ratelimit.InMemoryRateLimiter]/
 * [ai.rojan.backend.infrastructure.sms.LoggingSmsProvider]'s own doc
 * comments, and the same `@Profile("test")`/`@Profile("!test")` split they
 * already establish — without this, any bootstrap-level integration test
 * exercising `/otp/request` or `/otp/verify` needs a real Redis just to
 * pass, which none of the other auth flows require.
 */
@Component
@Profile("test")
class InMemoryOtpRepository : OtpRepository {

    private val store = ConcurrentHashMap<PhoneNumber, OneTimePassword>()

    override fun save(otp: OneTimePassword) {
        store[otp.phoneNumber] = otp
    }

    override fun findByPhoneNumber(phoneNumber: PhoneNumber): OneTimePassword? = store[phoneNumber]

    override fun delete(phoneNumber: PhoneNumber) {
        store.remove(phoneNumber)
    }
}
