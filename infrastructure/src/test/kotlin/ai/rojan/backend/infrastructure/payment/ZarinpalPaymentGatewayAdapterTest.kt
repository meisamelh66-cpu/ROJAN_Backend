package ai.rojan.backend.infrastructure.payment

import ai.rojan.backend.application.port.GatewayVerificationResult
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Payment Gateway Step 6. Stubs Zarinpal's own `request.json`/`verify.json` endpoints with the JDK's
 * own [HttpServer] (ephemeral local port) - same convention as `infrastructure.sms.RealSmsProviderAdapterTest`:
 * no new mocking-library dependency, and it exercises the real `java.net.http.HttpClient` call
 * end-to-end (path, headers, body, response parsing), never the real Zarinpal network.
 */
class ZarinpalPaymentGatewayAdapterTest {

    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    // ---------------- initiate ----------------

    @Test
    fun `initiate success returns the real authority and a redirect url built from it`() {
        var capturedPath: String? = null
        var capturedBody: String? = null
        val adapter = stub(
            path = "/request.json", status = 200,
            responseBody = """{"data":{"code":100,"message":"Success","authority":"A0000000000000000000000000000wwOGYpd","fee_type":"Merchant","fee":100},"errors":[]}""",
        ) { exchange -> capturedPath = exchange.requestURI.path; capturedBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8) }

        val result = adapter.initiate(150_000L, "Haircut booking", "https://api.rojanai.ir/api/v1/payments/callback/zarinpal")

        assertEquals("/request.json", capturedPath)
        assertTrue(capturedBody!!.contains("\"merchant_id\":\"test-merchant-id\""))
        assertTrue(capturedBody!!.contains("\"amount\":150000"))
        assertTrue(capturedBody!!.contains("\"currency\":\"IRR\""))
        assertTrue(capturedBody!!.contains("\"callback_url\":\"https://api.rojanai.ir/api/v1/payments/callback/zarinpal\""))
        assertEquals("A0000000000000000000000000000wwOGYpd", result.authority)
        assertEquals("ZARINPAL", adapter.providerName)
        assertTrue(result.redirectUrl.endsWith("/StartPay/A0000000000000000000000000000wwOGYpd"))
    }

    @Test
    fun `initiate throws a wrapped exception, never a raw provider type, when Zarinpal rejects the request`() {
        val adapter = stub(path = "/request.json", status = 200, responseBody = """{"data":[],"errors":{"code":-9,"message":"Validation error","validations":["amount"]}}""") {}

        val ex = assertThrows<PaymentGatewayException> {
            adapter.initiate(150_000L, "Haircut booking", "https://cb")
        }
        assertTrue(ex.message!!.contains("-9"))
        assertTrue(ex.message!!.contains("Validation error"))
    }

    @Test
    fun `initiate throws PaymentGatewayException on a malformed, unparseable response body`() {
        val adapter = stub(path = "/request.json", status = 200, responseBody = "not json at all") {}

        assertThrows<PaymentGatewayException> {
            adapter.initiate(150_000L, "Haircut booking", "https://cb")
        }
    }

    @Test
    fun `initiate throws PaymentGatewayException on an HTTP-level failure`() {
        val adapter = stub(path = "/request.json", status = 500, responseBody = """{"errors":{"message":"internal error"}}""") {}

        assertThrows<PaymentGatewayException> {
            adapter.initiate(150_000L, "Haircut booking", "https://cb")
        }
    }

    @Test
    fun `initiate throws PaymentGatewayException when Zarinpal does not respond within the configured timeout`() {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/request.json") { exchange ->
            Thread.sleep(2000)
            val bytes = """{"data":{"code":100,"authority":"A1"},"errors":[]}""".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()
        server = httpServer
        val adapter = ZarinpalPaymentGatewayAdapter(
            ZarinpalProperties(merchantId = "test-merchant-id", apiUrl = "http://127.0.0.1:${httpServer.address.port}", timeoutSeconds = 1),
        )

        assertThrows<PaymentGatewayException> {
            adapter.initiate(150_000L, "Haircut booking", "https://cb")
        }
    }

    // ---------------- verify ----------------

    @Test
    fun `verify success returns a Success result carrying Zarinpal's own ref_id, treating code 101 as success too`() {
        val adapter = stub(path = "/verify.json", status = 200, responseBody = """{"data":{"code":100,"message":"Verified","ref_id":201,"card_hash":"abc","card_pan":"502229******5995"},"errors":[]}""") {}

        val result = adapter.verify("A0000000000000000000000000000wwOGYpd", 150_000L)

        assertTrue(result is GatewayVerificationResult.Success)
        assertEquals("201", (result as GatewayVerificationResult.Success).gatewayReferenceId)
    }

    @Test
    fun `verify code 101 (already verified) is also treated as a real success`() {
        val adapter = stub(path = "/verify.json", status = 200, responseBody = """{"data":{"code":101,"message":"Already verified","ref_id":201},"errors":[]}""") {}

        val result = adapter.verify("A0000000000000000000000000000wwOGYpd", 150_000L)

        assertTrue(result is GatewayVerificationResult.Success)
    }

    @Test
    fun `verify failure returns a Failure result, never throws, when Zarinpal rejects verification`() {
        val adapter = stub(path = "/verify.json", status = 200, responseBody = """{"data":[],"errors":{"code":-11,"message":"IP or Merchant ID is incorrect"}}""") {}

        val result = adapter.verify("A0000000000000000000000000000wwOGYpd", 150_000L)

        assertTrue(result is GatewayVerificationResult.Failure)
        assertTrue((result as GatewayVerificationResult.Failure).reason.contains("IP or Merchant ID is incorrect"))
    }

    @Test
    fun `verify with a mismatched amount returns Failure, carrying Zarinpal's own rejection reason`() {
        var capturedBody: String? = null
        val adapter = stub(path = "/verify.json", status = 200, responseBody = """{"data":[],"errors":{"code":-4,"message":"Amount does not match the original transaction"}}""") { exchange ->
            capturedBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        }

        val result = adapter.verify("A0000000000000000000000000000wwOGYpd", 999_999L)

        assertTrue(capturedBody!!.contains("\"amount\":999999"))
        assertTrue(result is GatewayVerificationResult.Failure)
        assertTrue((result as GatewayVerificationResult.Failure).reason.contains("Amount does not match"))
    }

    @Test
    fun `verify throws PaymentGatewayException, never returns Failure, on a malformed response - we genuinely don't know the outcome`() {
        val adapter = stub(path = "/verify.json", status = 200, responseBody = "<<not json>>") {}

        assertThrows<PaymentGatewayException> {
            adapter.verify("A0000000000000000000000000000wwOGYpd", 150_000L)
        }
    }

    @Test
    fun `verify throws PaymentGatewayException on an HTTP-level failure, never returns Failure`() {
        val adapter = stub(path = "/verify.json", status = 503, responseBody = """{"errors":{"message":"service unavailable"}}""") {}

        assertThrows<PaymentGatewayException> {
            adapter.verify("A0000000000000000000000000000wwOGYpd", 150_000L)
        }
    }

    // ---------------- redirectUrlFor ----------------

    @Test
    fun `redirectUrlFor is deterministic and makes no HTTP call at all`() {
        // No stub server started at all - a real HTTP attempt here would fail/hang, proving purity.
        val adapter = ZarinpalPaymentGatewayAdapter(ZarinpalProperties(merchantId = "test-merchant-id", apiUrl = "http://127.0.0.1:1"))

        val first = adapter.redirectUrlFor("A0000000000000000000000000000wwOGYpd")
        val second = adapter.redirectUrlFor("A0000000000000000000000000000wwOGYpd")

        assertEquals(first, second)
        assertTrue(first.endsWith("/StartPay/A0000000000000000000000000000wwOGYpd"))
        assertFalse(first.contains("v4/payment"))
    }

    private fun stub(path: String, status: Int, responseBody: String, onRequest: (HttpExchange) -> Unit): ZarinpalPaymentGatewayAdapter {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext(path) { exchange ->
            onRequest(exchange)
            val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()
        server = httpServer
        return ZarinpalPaymentGatewayAdapter(
            ZarinpalProperties(
                merchantId = "test-merchant-id",
                apiUrl = "http://127.0.0.1:${httpServer.address.port}",
                startPayUrl = "http://127.0.0.1:${httpServer.address.port}/StartPay",
            ),
        )
    }
}
