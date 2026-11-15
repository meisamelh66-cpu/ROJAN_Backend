package ai.rojan.backend.infrastructure.payment

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Payment Gateway Step 6: Zarinpal's v4 REST API (https://www.zarinpal.com/docs/paymentGateway/connectToGateway).
 * [merchantId] has no default on purpose, same reasoning as `infrastructure.sms.SmsProperties`' own
 * doc comment: startup must fail loudly if it's unset in any profile other than `test` (see
 * [ZarinpalPaymentGatewayAdapter], the only consumer, itself `@Profile("!test")`) rather than silently
 * booting with an unusable payment integration. [apiUrl]/[startPayUrl] are Zarinpal's own public,
 * stable, non-secret endpoints - safe to default, still overridable (e.g. for Zarinpal's sandbox).
 */
@ConfigurationProperties(prefix = "rojan.zarinpal")
data class ZarinpalProperties(
    val merchantId: String,
    val apiUrl: String = "https://payment.zarinpal.com/pg/v4/payment",
    val startPayUrl: String = "https://payment.zarinpal.com/pg/StartPay",
    val timeoutSeconds: Long = 15,
)
