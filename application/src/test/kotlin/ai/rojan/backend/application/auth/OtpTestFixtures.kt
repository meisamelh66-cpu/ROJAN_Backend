package ai.rojan.backend.application.auth

import ai.rojan.backend.application.port.RateLimiterPort
import ai.rojan.backend.application.port.SmsProviderPort
import ai.rojan.backend.domain.auth.OneTimePassword
import ai.rojan.backend.domain.auth.OtpRepository
import ai.rojan.backend.domain.auth.PhoneNumber
import ai.rojan.backend.domain.user.Email
import ai.rojan.backend.domain.user.User
import ai.rojan.backend.domain.user.UserId
import ai.rojan.backend.domain.user.UserRepository
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

    /** Pulls the 6-digit code back out of the last message sent to [phoneNumber] — the use case never returns the raw code, only its hash is persisted. */
    fun lastCodeSentTo(phoneNumber: PhoneNumber): String =
        sent.last { it.phoneNumber == phoneNumber }.message.let { CODE_REGEX.find(it)!!.value }

    private companion object {
        val CODE_REGEX = Regex("\\d{6}")
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
