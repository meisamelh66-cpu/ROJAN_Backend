package ai.rojan.backend.infrastructure.sms

import ai.rojan.backend.domain.auth.PhoneNumber
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Stubs MeliPayamak's Shared Pattern endpoint with the JDK's own [HttpServer]
 * (bound to an ephemeral local port), same technique as
 * [RealSmsProviderAdapterTest] - no new test dependency, exercises the real
 * `java.net.http.HttpClient` call end-to-end.
 */
class MeliPayamakSharedPatternProviderTest {

    private var server: HttpServer? = null
    private var requestCount = 0
    private var capturedPath: String? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `posts JSON body with bodyId, local-format recipient, and the extracted code as the first arg`() {
        var capturedBody: String? = null
        var capturedContentType: String? = null
        val provider = startStubServerAndCreateProvider(status = 200, responseBody = """{"recId":"123456789","status":"1"}""") { exchange ->
            capturedContentType = exchange.requestHeaders.getFirst("Content-Type")
            capturedBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        }

        provider.send(PhoneNumber("+989123456789"), "Your ROJAN verification code is 482913. It expires in 2 minutes.")

        assertEquals("application/json", capturedContentType)
        assertEquals("/api/send/shared/test-api-key", capturedPath)
        assertTrue(capturedBody!!.contains(""""bodyId":254"""), "expected bodyId in body, got: $capturedBody")
        assertTrue(capturedBody!!.contains(""""to":"09123456789""""), "expected local-format recipient in body, got: $capturedBody")
        assertTrue(capturedBody!!.contains(""""args":["482913"]"""), "expected the extracted code as args[0], got: $capturedBody")
    }

    @Test
    fun `succeeds when the response contains a non-blank recId`() {
        val provider = startStubServerAndCreateProvider(status = 200, responseBody = """{"recId":"1","status":"ok"}""") { }

        provider.send(PhoneNumber("+989123456789"), "Your ROJAN verification code is 482913. It expires in 2 minutes.")
    }

    @Test
    fun `throws SmsDeliveryException when the response has no recId (provider failure)`() {
        val provider = startStubServerAndCreateProvider(status = 200, responseBody = """{"status":"rejected"}""") { }

        val exception = assertThrows<SmsDeliveryException> {
            provider.send(PhoneNumber("+989123456789"), "Your ROJAN verification code is 482913. It expires in 2 minutes.")
        }
        assertTrue(exception.message!!.contains("did not confirm delivery"))
    }

    @Test
    fun `throws SmsDeliveryException when the response body is not valid JSON (invalid response)`() {
        val provider = startStubServerAndCreateProvider(status = 200, responseBody = "not-json") { }

        val exception = assertThrows<SmsDeliveryException> {
            provider.send(PhoneNumber("+989123456789"), "Your ROJAN verification code is 482913. It expires in 2 minutes.")
        }
        assertTrue(exception.message!!.contains("unparseable"))
    }

    @Test
    fun `throws SmsDeliveryException when the provider responds with a non-2xx status (HTTP error)`() {
        val provider = startStubServerAndCreateProvider(status = 500, responseBody = "internal error") { }

        val exception = assertThrows<SmsDeliveryException> {
            provider.send(PhoneNumber("+989123456789"), "Your ROJAN verification code is 482913. It expires in 2 minutes.")
        }
        assertTrue(exception.message!!.contains("HTTP 500"))
    }

    @Test
    fun `throws SmsDeliveryException when the provider is unreachable (network failure)`() {
        // Port 1 is never listening - simulates a network-level failure without a real network dependency.
        val provider = MeliPayamakSharedPatternProvider(
            MeliPayamakSharedPatternProperties(apiKey = "test-api-key", bodyId = 254, apiUrl = "http://127.0.0.1:1/api/send/shared"),
        )

        assertThrows<SmsDeliveryException> {
            provider.send(PhoneNumber("+989123456789"), "Your ROJAN verification code is 482913. It expires in 2 minutes.")
        }
    }

    @Test
    fun `throws SmsDeliveryException and never calls the network when the message does not match the expected format`() {
        val provider = startStubServerAndCreateProvider(status = 200, responseBody = """{"recId":"1"}""") { }

        val exception = assertThrows<SmsDeliveryException> {
            provider.send(PhoneNumber("+989123456789"), "This message has no code in the expected shape")
        }

        assertTrue(exception.message!!.contains("Could not extract"))
        assertEquals(0, requestCount)
    }

    @Test
    fun `never includes the OTP code or the api key in a thrown exception's type, only via the message the caller already controls`() {
        // The api key lives in the URL path (asserted above), never in the JSON body, so a
        // provider-failure exception (built from the response body only) cannot echo it back.
        val provider = startStubServerAndCreateProvider(status = 200, responseBody = """{"status":"rejected"}""") { }

        val exception = assertThrows<SmsDeliveryException> {
            provider.send(PhoneNumber("+989123456789"), "Your ROJAN verification code is 482913. It expires in 2 minutes.")
        }
        assertTrue(!exception.message!!.contains("test-api-key"))
    }

    private fun startStubServerAndCreateProvider(
        status: Int,
        responseBody: String,
        onRequest: (com.sun.net.httpserver.HttpExchange) -> Unit,
    ): MeliPayamakSharedPatternProvider {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/api/send/shared/test-api-key") { exchange ->
            requestCount++
            capturedPath = exchange.requestURI.path
            onRequest(exchange)
            val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()
        server = httpServer

        val properties = MeliPayamakSharedPatternProperties(
            apiKey = "test-api-key",
            bodyId = 254,
            apiUrl = "http://127.0.0.1:${httpServer.address.port}/api/send/shared",
        )
        return MeliPayamakSharedPatternProvider(properties)
    }
}
