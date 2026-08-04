package ai.rojan.backend.application.port

import ai.rojan.backend.domain.auth.PhoneNumber

/**
 * Output port for SMS delivery — deliberately vendor-agnostic (Mobile-First
 * Authentication Phase 1 instruction: "do not hardcode SMS vendor"). No real
 * vendor is integrated in this phase; the infrastructure implementation
 * (`LoggingSmsProvider`) logs instead of sending, clearly marked as a
 * development-only placeholder. Swapping in a real provider (e.g. a local
 * Iranian SMS gateway, given this product's Persian-first audience) means
 * adding one new adapter class and one DI registration — nothing above this
 * port changes.
 */
interface SmsProviderPort {
    fun send(phoneNumber: PhoneNumber, message: String)
}
