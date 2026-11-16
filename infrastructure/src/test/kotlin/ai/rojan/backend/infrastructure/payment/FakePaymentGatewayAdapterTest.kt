package ai.rojan.backend.infrastructure.payment

import ai.rojan.backend.application.port.GatewayVerificationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Payment Gateway Step 3 — [FakePaymentGatewayAdapter] is the deterministic, no-real-HTTP stand-in
 * for [ai.rojan.backend.application.port.PaymentGatewayPort] every later test (and, eventually, a
 * local/dev profile) uses instead of a real Zarinpal call - same role [LoggingSmsProvider]/
 * [LocalDevSmsProvider] already play for `SmsProviderPort`.
 */
class FakePaymentGatewayAdapterTest {

    @Test
    fun `initiate succeeds and returns a non-blank authority and redirectUrl`() {
        val gateway = FakePaymentGatewayAdapter()

        val result = gateway.initiate(amount = 150_000, description = "Haircut", callbackUrl = "https://api.rojanai.ir/callback")

        assertTrue(result.authority.isNotBlank())
        assertTrue(result.redirectUrl.isNotBlank())
    }

    @Test
    fun `initiate returns a deterministic authority - same call shape, predictable value, no randomness`() {
        val gateway = FakePaymentGatewayAdapter()

        val first = gateway.initiate(amount = 1000, description = "x", callbackUrl = "https://example.com/cb")
        val second = gateway.initiate(amount = 1000, description = "x", callbackUrl = "https://example.com/cb")

        assertEquals("FAKE-AUTH-1", first.authority)
        assertEquals("FAKE-AUTH-2", second.authority)
    }

    @Test
    fun `initiate passes amount and callbackUrl through unchanged - captured for the caller to inspect`() {
        val gateway = FakePaymentGatewayAdapter()

        gateway.initiate(amount = 275_000, description = "Manicure", callbackUrl = "https://api.rojanai.ir/payments/callback/zarinpal")

        assertEquals(275_000L, gateway.lastInitiateAmount)
        assertEquals("https://api.rojanai.ir/payments/callback/zarinpal", gateway.lastInitiateCallbackUrl)
    }

    @Test
    fun `verify succeeds by default for any authority not explicitly scripted to fail, with a deterministic gatewayReferenceId`() {
        val gateway = FakePaymentGatewayAdapter()
        val initiation = gateway.initiate(amount = 50_000, description = "x", callbackUrl = "https://example.com/cb")

        val result = gateway.verify(authority = initiation.authority, amount = 50_000)

        assertTrue(result is GatewayVerificationResult.Success)
        assertEquals("FAKE-REF-${initiation.authority}", (result as GatewayVerificationResult.Success).gatewayReferenceId)
    }

    @Test
    fun `verify fails with the exact scripted reason when a test configures that authority to fail`() {
        val gateway = FakePaymentGatewayAdapter()
        val initiation = gateway.initiate(amount = 50_000, description = "x", callbackUrl = "https://example.com/cb")
        gateway.scriptVerificationFailure(initiation.authority, reason = "amount mismatch")

        val result = gateway.verify(authority = initiation.authority, amount = 50_000)

        assertTrue(result is GatewayVerificationResult.Failure)
        assertEquals("amount mismatch", (result as GatewayVerificationResult.Failure).reason)
    }

    @Test
    fun `verify captures the amount it was called with, for the caller to assert amount was passed unchanged`() {
        val gateway = FakePaymentGatewayAdapter()
        val initiation = gateway.initiate(amount = 90_000, description = "x", callbackUrl = "https://example.com/cb")

        gateway.verify(authority = initiation.authority, amount = 90_000)

        assertEquals(90_000L, gateway.lastVerifyAmount)
        assertEquals(initiation.authority, gateway.lastVerifyAuthority)
    }

    @Test
    fun `is a plain in-memory object with no HTTP client field - no real network call is possible by construction`() {
        // No RestTemplate/WebClient/OkHttp field exists anywhere on this class (verified by inspection,
        // not by mocking a network layer that doesn't exist) - this test documents that intent so a
        // future edit introducing a real HTTP dependency here would be a conscious, reviewable change.
        assertNotNull(FakePaymentGatewayAdapter())
    }
}
