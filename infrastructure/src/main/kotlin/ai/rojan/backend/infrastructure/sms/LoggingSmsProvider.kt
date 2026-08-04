package ai.rojan.backend.infrastructure.sms

import ai.rojan.backend.application.port.SmsProviderPort
import ai.rojan.backend.domain.auth.PhoneNumber
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Test-only placeholder — logs instead of sending. SMS Provider
 * Integration: [RealSmsProviderAdapter] (MelliPayamak) is now the real,
 * default implementation for every other profile; this one is scoped to
 * `@Profile("test")` specifically so the test suite (and CI) never needs
 * real `SMS_API_URL`/`SMS_API_KEY`/`SMS_SENDER` credentials just to boot
 * the Spring context - [RealSmsProviderAdapter]'s own required config has
 * no defaults and fails loudly if unset (mirrors `JwtProperties`'
 * `JWT_SECRET`), which is exactly why this can't simply be deleted without
 * breaking every test run. Logging a live OTP code here is a real, if
 * narrow, exposure (anyone with log access can read it) - acceptable only
 * because it never runs outside the `test` profile now.
 */
@Component
@Profile("test")
class LoggingSmsProvider : SmsProviderPort {
    private val log = LoggerFactory.getLogger(LoggingSmsProvider::class.java)

    override fun send(phoneNumber: PhoneNumber, message: String) {
        log.warn("[DEV-ONLY SMS PLACEHOLDER — no real vendor configured] to {}: {}", phoneNumber.value, message)
    }
}
