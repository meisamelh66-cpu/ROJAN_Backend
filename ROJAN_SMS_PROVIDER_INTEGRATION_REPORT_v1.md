# ROJAN Backend — SMS Provider Integration Report v1

**Scope:** Replace `LoggingSmsProvider` with a real MelliPayamak-backed `SmsProviderPort` implementation, per "ROJAN Backend — SMS Provider Integration Task v1.0".
**Status:** Complete. `./gradlew clean build` — **BUILD SUCCESSFUL**, 192/192 tests passing (4 new), 0 failures, 0 errors.

---

## 1. What was built

`RealSmsProviderAdapter` now implements `SmsProviderPort` and talks to MelliPayamak's API-key console REST endpoint:

```
POST {SMS_API_URL}/{SMS_API_KEY}
Content-Type: application/json

{"from": "<SMS_SENDER>", "to": "<phoneNumber>", "text": "<message>"}
```

The exact wire format (API key as a URL path segment, not a header; `from`/`to`/`text` field names; `recId`/`status` response shape) was researched against MelliPayamak's own official client libraries before writing any code - not assumed. See §5 for sources.

**OTP flow is unchanged**, exactly as required: `RequestOtpUseCase`/`VerifyOtpUseCase` were not touched at all - both only ever depend on `SmsProviderPort`, and `RequestOtpUseCaseTest`/`VerifyOtpUseCaseTest` (application module, using the existing `RecordingSmsProvider` fake) needed zero changes and still pass unmodified. This is the concrete proof that swapping the adapter didn't touch the flow.

## 2. Files changed

### Infrastructure (new)
- `infrastructure/sms/SmsProperties.kt` - `@ConfigurationProperties(prefix = "rojan.sms")`: `apiUrl`, `apiKey`, `sender`. No defaults, by design (see §3).
- `infrastructure/sms/SmsPropertiesConfig.kt` - registers `SmsProperties`, itself `@Profile("!test")`.
- `infrastructure/sms/RealSmsProviderAdapter.kt` - the real adapter (`@Component`, `@Profile("!test")`). Uses the JDK's own `java.net.http.HttpClient` - no new Gradle dependency added to `infrastructure` for one outbound call. Throws a new `SmsDeliveryException` (plain `RuntimeException`) on a non-2xx response, an unreachable host, an unparseable body, or a 200 with no `recId` - deliberately uncaught by `RequestOtpUseCase`, so it surfaces through `GlobalExceptionHandler`'s existing catch-all as a 500 with a traceId, same as any other unexpected failure (no new exception-handling wiring needed to keep the OTP flow's contract unchanged).

### Infrastructure (modified)
- `infrastructure/sms/LoggingSmsProvider.kt` - gained `@Profile("test")`. It isn't deleted: it's now the test-only fallback so the test suite and CI never need real SMS credentials just to boot the Spring context (see §3).
- `bootstrap/application.yml` - new `rojan.sms.*` section bound to `SMS_API_URL`/`SMS_API_KEY`/`SMS_SENDER`, no defaults (`${SMS_API_URL}` not `${SMS_API_URL:}`) - unset means Spring's property binding itself throws during context refresh, not a silent empty string.

### Tests (new)
- `infrastructure/sms/RealSmsProviderAdapterTest.kt` - 4 tests, stubbing MelliPayamak's endpoint with the JDK's own `com.sun.net.httpserver.HttpServer` (no mocking library needed): correct path/body sent + success on a non-blank `recId`; `SmsDeliveryException` on a non-2xx response; `SmsDeliveryException` on 200-with-no-`recId`; `SmsDeliveryException` when the host is unreachable. Exercises the real `HttpClient` call end-to-end rather than mocking it away.

## 3. The provider-swap design, and why it isn't a straight delete-and-replace

Two decisions were confirmed with you before writing code (see the plan message and your answers):

1. **Fail loudly at startup if `SMS_API_URL`/`SMS_API_KEY`/`SMS_SENDER` are unset** - same posture this codebase already takes for `JWT_SECRET`. A misconfigured production deployment refuses to boot rather than booting successfully and failing silently on the first real OTP request.
2. **`@Profile`-gate rather than delete `LoggingSmsProvider`** - `LoggingSmsProvider` is `@Profile("test")`, `RealSmsProviderAdapter`/`SmsPropertiesConfig` are `@Profile("!test")`. Spring only ever sees one `SmsProviderPort` bean candidate in any given run, so there's no ambiguous-bean startup error, and the test suite/CI never needs real vendor credentials to run.

The two decisions are linked: fail-loud-if-unset is only safe to apply broadly because the `test` profile is fully carved out from ever needing those variables in the first place. Confirmed by the full `bootstrap:test` run in this same pass, which boots the real Spring context under `@ActiveProfiles("test")` and passed - proving `LoggingSmsProvider` is correctly selected there with no `SMS_*` env vars present at all.

## 4. Production configuration required

| Env var | Example value | Notes |
|---|---|---|
| `SMS_API_URL` | `https://console.melipayamak.com/api/send/simple` | Base endpoint **without** the API key - the adapter appends `/{apiKey}` itself |
| `SMS_API_KEY` | *(from MelliPayamak console)* | Never logged, never hardcoded - read only from this env var |
| `SMS_SENDER` | *(your approved MelliPayamak line number)* | Becomes the JSON body's `from` field |

Without all three set, the application **will not start** outside the `test` profile.

## 5. Sources for the API contract

MelliPayamak's own public docs don't spell out the raw wire format for the API-key-based endpoint directly; the exact contract was reconstructed from their official client libraries' source code:
- [HRashidi/melipayamak (Node.js)](https://github.com/HRashidi/melipayamak) - confirms the API key is appended as a URL path segment (`` `${SEND.SIMPLE}/${this.SMS_TOKEN}` ``) and the base URL (`https://console.melipayamak.com/api`, `send/simple` endpoint)
- [Melipayamak/melipayamak-python (rest.py)](https://github.com/Melipayamak/melipayamak-python/blob/master/melipayamak/sms/rest.py) - confirms the `to`/`from`/`text` field naming convention used consistently across every MelliPayamak client
- [melipayamak.com blog - "ارسال پیامک با API Key"](https://www.melipayamak.com/blog/posts/send-sms-by-api-key/) - confirms the API-key console is the intended REST integration path (as opposed to the legacy username/password SOAP API)

## 6. Tests

| Suite | Tests | Result |
|---|---|---|
| `RealSmsProviderAdapterTest` (new) | 4 | ✅ correct path/body + success, non-2xx failure, no-recId failure, unreachable-host failure |
| `RequestOtpUseCaseTest`/`VerifyOtpUseCaseTest` (unchanged) | 12 | ✅ still pass without modification - proof the OTP flow itself is untouched |
| Full repo suite | 192 | ✅ 0 failures, 0 errors - includes `bootstrap:test`, which boots the real Spring context under the `test` profile and confirms `LoggingSmsProvider` (not `RealSmsProviderAdapter`) is selected there with zero `SMS_*` env vars present |

## 7. Remaining blockers / gaps (explicit, not hidden)

1. **Not tested against MelliPayamak's real, live endpoint.** The contract was reconstructed from their official client libraries (§5), not confirmed by a real API call with real credentials (none were available in this environment). Recommend one manual smoke test against a real `SMS_API_KEY` before the first production OTP request - if MelliPayamak's actual response shape differs even slightly (e.g. a different casing on `recId`, or an HTTP 200 wrapping an error in a way not covered here), `RealSmsProviderAdapterTest`'s stub won't have caught it.
2. **`RequestOtpUseCase` still saves the OTP to Redis *before* calling `SmsProviderPort.send()`** (this predates this task and was explicitly out of scope - "keep OTP flow unchanged"). If `RealSmsProviderAdapter` now throws (e.g. MelliPayamak is down, out of credit, or misconfigured), the OTP is already persisted but the user never receives it - they'd see a 500 and have no valid code to enter. This was already true with `LoggingSmsProvider` in a narrower sense (it never truly "failed" to log) and is a pre-existing design gap, not one this task introduced, but replacing the adapter with something that can now genuinely fail (rate limits, network errors, vendor outages) makes it a real, live failure mode for the first time. Worth a follow-up ticket if you want retry/rollback behavior here.
3. **No delivery-status tracking.** MelliPayamak's REST API exposes a delivery-status lookup (`isDelivered`/`GetDeliveries2`) that this adapter doesn't use - `send()` only confirms the vendor *accepted* the message (got a `recId` back), not that the SMS was actually delivered to the handset. Out of scope for this task's "keep OTP flow unchanged" instruction, but worth knowing if OTP delivery issues get reported later.
4. **Sender line (`SMS_SENDER`) must be a number MelliPayamak has actually approved for your account** - an unapproved sender is a vendor-side rejection this adapter would surface as a generic `SmsDeliveryException`, not a distinct error type, since MelliPayamak's response shape doesn't appear to expose a machine-readable error code (only the free-text `status` field, per §5's sources).
