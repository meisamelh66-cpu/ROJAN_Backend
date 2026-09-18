package ai.rojan.backend.infrastructure.sms

import ai.rojan.backend.application.port.SmsProviderPort
import ai.rojan.backend.domain.auth.PhoneNumber
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Local-dev-only [SmsProviderPort] adapter — logs the OTP message instead of
 * calling a real vendor, the same non-bypassing shape as [LoggingSmsProvider]
 * (that one is test-profile's own placeholder): ROJAN still generates,
 * hashes, and verifies the code exactly as with any real provider
 * (`RequestOtpUseCase`/`VerifyOtpUseCase`, both untouched) - this adapter's
 * only job is standing in for the outbound SMS call so OTP login/register is
 * exercisable end-to-end on a machine with no real MeliPayamak credentials.
 * The code an E2E test needs is the same real one `VerifyOtpUseCase` will
 * actually accept - readable from this log line, not a separate/fake value.
 *
 * Opt-in only ([ConditionalOnProperty], no `matchIfMissing`) via
 * `rojan.sms.provider=local-dev` (`SMS_PROVIDER=local-dev`, the dev-only
 * root `docker-compose.yml`'s own `app` service) - never registered
 * otherwise, so this can never silently replace [RealSmsProviderAdapter]
 * anywhere it isn't explicitly asked for. `@Profile("!test & !prod")` is a
 * second, structural guard on top of that - stricter than every other
 * profile-gated class in this package (which only exclude `test`) precisely
 * because this one must never run in production: `docker-compose.prod.yml`
 * always sets `SPRING_PROFILES_ACTIVE=prod`, so this bean cannot be created
 * there even if `SMS_PROVIDER=local-dev` were ever mistakenly set in a
 * production `.env` - the profile mismatch alone excludes it, independent
 * of the property value.
 *
 * [Primary] mirrors [MeliPayamakSharedPatternProvider]'s own doc comment:
 * only matters the moment this and [RealSmsProviderAdapter] are
 * simultaneously registered - [RealSmsProviderAdapter] needs no
 * corresponding annotation for that tie-break to work, per Spring's own
 * `@Primary` semantics, and its own send() behavior is completely
 * unchanged by this class existing.
 */
@Component
@Primary
@Profile("!test & !prod")
@ConditionalOnProperty(prefix = "rojan.sms", name = ["provider"], havingValue = "local-dev")
class LocalDevSmsProvider : SmsProviderPort {

    private val log = LoggerFactory.getLogger(LocalDevSmsProvider::class.java)

    override fun send(phoneNumber: PhoneNumber, message: String) {
        log.warn("[LOCAL DEV SMS — no real vendor configured, code visible only in this log] to {}: {}", phoneNumber.value, message)
    }
}
