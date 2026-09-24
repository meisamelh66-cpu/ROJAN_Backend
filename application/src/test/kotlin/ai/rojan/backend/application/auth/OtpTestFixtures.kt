package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.RateLimiterPort
import ai.rojan.backend.application.port.RefreshTokenStorePort
import ai.rojan.backend.application.port.SmsProviderPort
import ai.rojan.backend.domain.auth.OneTimePassword
import ai.rojan.backend.domain.auth.OtpRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.common.PageRequest
import ai.rojan.backend.domain.common.PageResult
import ai.rojan.backend.domain.common.SortDirection
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
import ai.rojan.backend.domain.user.UserRole
import java.time.Duration

internal class InMemoryOtpUserRepository : UserRepository {
    private val store = mutableMapOf<UserId, User>()

    fun register(user: User) {
        store[user.id] = user
    }

    override fun save(user: User): User = user.also { store[it.id] = it }
    override fun findById(id: UserId): User? = store[id]
    override fun findByEmail(email: Email): User? = store.values.find { it.email == email }
    override fun existsByEmail(email: Email): Boolean = store.values.any { it.email == email }
    override fun findByPhoneNumber(phoneNumber: PhoneNumber): User? = store.values.find { it.phoneNumber == phoneNumber }
    override fun existsByPhoneNumber(phoneNumber: PhoneNumber): Boolean = store.values.any { it.phoneNumber == phoneNumber }
    override fun findByRole(role: UserRole): List<User> = store.values.filter { it.role == role }

    /** Not exercised by this file's tests - a minimal, correct in-memory implementation only to satisfy the interface (Platform Management API Contract). */
    override fun findByRole(role: UserRole, pageRequest: PageRequest, search: String?, sortDirection: SortDirection): PageResult<User> {
        val matches = findByRole(role)
        return PageResult(content = matches.take(pageRequest.size), page = pageRequest.page, size = pageRequest.size, totalElements = matches.size.toLong())
    }
}

/** Shared in-memory fakes for the OTP use-case tests, mirroring the auth module's existing fake-repository style. */
internal class InMemoryOtpRepository : OtpRepository {
    private val store = mutableMapOf<PhoneNumber, OneTimePassword>()

    override fun save(otp: OneTimePassword) {
        store[otp.phoneNumber] = otp
    }

    override fun findByPhoneNumber(phoneNumber: PhoneNumber): OneTimePassword? = store[phoneNumber]

    override fun delete(phoneNumber: PhoneNumber) {
        store.remove(phoneNumber)
    }
}

internal data class SentSms(val phoneNumber: PhoneNumber, val message: String)

internal class RecordingSmsProvider : SmsProviderPort {
    val sent = mutableListOf<SentSms>()

    override fun send(phoneNumber: PhoneNumber, message: String) {
        sent += SentSms(phoneNumber, message)
    }

    /** Pulls the code back out of the last message sent to [phoneNumber] — the use case never returns the raw code, only its hash is persisted. Bounded 4-8 digits to match [OtpPolicy.codeLength]'s valid range, not a fixed count. */
    fun lastCodeSentTo(phoneNumber: PhoneNumber): String =
        sent.last { it.phoneNumber == phoneNumber }.message.let { CODE_REGEX.find(it)!!.value }

    private companion object {
        val CODE_REGEX = Regex("\\d{4,8}")
    }
}

/** Denies any key starting with one of [deniedKeyPrefixes], allows everything else — enough to simulate a specific rate limit tripping without reimplementing Redis' fixed-window counting. */
internal class RecordingRateLimiter(private val deniedKeyPrefixes: Set<String> = emptySet()) : RateLimiterPort {
    val consumedKeys = mutableListOf<String>()

    override fun tryConsume(key: String, limit: Int, window: Duration): Boolean {
        consumedKeys += key
        return deniedKeyPrefixes.none { key.startsWith(it) }
    }
}

/** Shared [AuthRateLimitPolicy] fixture for `AuthenticateUserUseCase`/`RegisterUserUseCase`/`RefreshTokenUseCase` tests (Phase 1.1) - arbitrary-but-realistic values, never asserted on directly, only exercised via [RecordingRateLimiter]. */
internal val testAuthRateLimitPolicy = AuthRateLimitPolicy(
    loginLimitPerEmailWindow = 5,
    loginLimitPerIpWindow = 20,
    loginWindowSeconds = 300,
    registerLimitPerIpWindow = 5,
    registerWindowSeconds = 3600,
    refreshLimitPerIpWindow = 30,
    refreshWindowSeconds = 300,
)

/**
 * Refresh Token Rotation (`BACKEND_REFRESH_TOKEN_SECURITY_PLAN.md`): a plain in-memory fake for
 * [RefreshTokenStorePort], shared by every auth use-case test that issues or rotates a refresh
 * token - `application` cannot depend on `infrastructure`'s own real
 * `RedisRefreshTokenStore`/`InMemoryRefreshTokenStore`, so this is a separate, minimal
 * implementation of the same one-key-per-family contract, with enough recorded history
 * ([activations]) for a test to assert exactly which family/jti pairs were activated and in what
 * order - the only way to observe rotation actually happening from outside the use case.
 */
internal class RecordingRefreshTokenStore : RefreshTokenStorePort {
    // ConcurrentHashMap/CopyOnWriteArrayList, not a plain mutableMapOf/mutableListOf - this fake
    // is exercised by a genuine multi-threaded race test (RefreshTokenUseCaseTest's "genuinely
    // simultaneous refresh" test), and a plain HashMap under real concurrent writes risks
    // corrupting its own internal structure (not just "the wrong value wins," which is the
    // intentional, tested race - an actual data-structure fault would be a test-infrastructure
    // bug, not evidence about the use case).
    private val families = java.util.concurrent.ConcurrentHashMap<String, String>()
    val activations: MutableList<Pair<String, String>> = java.util.concurrent.CopyOnWriteArrayList()

    override fun activate(familyId: String, jti: String, ttl: Duration) {
        families[familyId] = jti
        activations += familyId to jti
    }

    override fun currentJti(familyId: String): String? = families[familyId]

    override fun revokeFamily(familyId: String) {
        families.remove(familyId)
    }
}
