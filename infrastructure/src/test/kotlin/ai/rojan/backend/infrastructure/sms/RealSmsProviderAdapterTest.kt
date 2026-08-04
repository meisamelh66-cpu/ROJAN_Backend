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
 * Stubs MelliPayamak's endpoint with the JDK's own [HttpServer] (bound to
 * an ephemeral local port) rather than a mocking library - no new test
 * dependency needed, and it exercises the real `java.net.http.HttpClient`
 * call end-to-end (path, headers, body, response parsing) rather than
 * mocking that client away.
 */
class RealSmsProviderAdapterTest {

    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `sends the API key as a URL path segment and the sender-to-text body, and succeeds on a non-blank recId`() {
        var capturedPath: String? = null
        var capturedBody: String? = null
        val adapter = startStubServerAndCreateAdapter(status = 200, responseBody = """{"recId":"12345","status":null}""") { exchange ->
            capturedPath = exchange.requestURI.path
            capturedBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        }

        adapter.send(PhoneNumber("+989123456789"), "Your ROJAN verification code is 482913.")

        assertEquals("/send/simple/test-api-key", capturedPath)
        assertTrue(capturedBody!!.contains("\"from\":\"ROJANAI\""))
        assertTrue(capturedBody!!.contains("\"to\":\"+989123456789\""))
        assertTrue(capturedBody!!.contains("482913"))
    }

    @Test
    fun `throws SmsDeliveryException when the provider responds with a non-2xx status`() {
        val adapter = startStubServerAndCreateAdapter(status = 401, responseBody = """{"status":"Invalid API key"}""") { }

        assertThrows<SmsDeliveryException> {
            adapter.send(PhoneNumber("+989123456789"), "code")
        }
    }

    @Test
    fun `throws SmsDeliveryException when the provider returns 200 but no recId`() {
        val adapter = startStubServerAndCreateAdapter(status = 200, responseBody = """{"recId":null,"status":"Insufficient credit"}""") { }

        val exception = assertThrows<SmsDeliveryException> {
            adapter.send(PhoneNumber("+989123456789"), "code")
        }
        assertTrue(exception.message!!.contains("Insufficient credit"))
    }

    @Test
    fun `throws SmsDeliveryException when the provider is unreachable`() {
        // Port 1 is never listening - simulates a network-level failure without a real network dependency.
        val adapter = RealSmsProviderAdapter(SmsProperties(apiUrl = "http://127.0.0.1:1", apiKey = "test-api-key", sender = "ROJANAI"))

        assertThrows<SmsDeliveryException> {
            adapter.send(PhoneNumber("+989123456789"), "code")
        }
    }

    private fun startStubServerAndCreateAdapter(
        status: Int,
        responseBody: String,
        onRequest: (com.sun.net.httpserver.HttpExchange) -> Unit,
    ): RealSmsProviderAdapter {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/send/simple/test-api-key") { exchange ->
            onRequest(exchange)
            val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()
        server = httpServer

        val properties = SmsProperties(
            apiUrl = "http://127.0.0.1:${httpServer.address.port}/send/simple",
            apiKey = "test-api-key",
            sender = "ROJANAI",
        )
        return RealSmsProviderAdapter(properties)
    }
}
