package ai.rojan.backend.domain.auth

/** Port for [ReviewerAccessGrant] persistence — at most one row per phone number, mirroring the real schema's primary key. */
interface ReviewerAccessRepository {
    fun findByPhoneNumber(phoneNumber: PhoneNumber): ReviewerAccessGrant?
    fun save(grant: ReviewerAccessGrant): ReviewerAccessGrant
}
