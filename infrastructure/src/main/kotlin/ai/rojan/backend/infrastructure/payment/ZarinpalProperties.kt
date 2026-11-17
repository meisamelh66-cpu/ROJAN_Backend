package ai.rojan.backend.infrastructure.payment

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Payment Gateway Step 6: Zarinpal's v4 REST API (https://www.zarinpal.com/docs/paymentGateway/connectToGateway).
 *
 * P1 Per-Salon Payment Gateway Configuration: `merchantId` was removed from this class entirely -
 * the global `ZARINPAL_MERCHANT_ID` dependency is gone from the real production payment path.
 * [ZarinpalPaymentGatewayAdapter] now resolves each real call's merchant id per-salon, from that
 * salon's own [ai.rojan.backend.domain.payment.PaymentGatewayConfig] (decrypted via
 * [CredentialCipher]), never from a value bound here. [apiUrl]/[startPayUrl]/[timeoutSeconds] remain
 * global - Zarinpal's own public, stable, non-secret endpoints, identical for every salon - safe to
 * default, still overridable (e.g. for Zarinpal's sandbox).
 */
@ConfigurationProperties(prefix = "rojan.zarinpal")
data class ZarinpalProperties(
    val apiUrl: String = "https://payment.zarinpal.com/pg/v4/payment",
    val startPayUrl: String = "https://payment.zarinpal.com/pg/StartPay",
    val timeoutSeconds: Long = 15,
)
