package ai.rojan.backend.domain.salon

import java.time.Instant
import java.util.UUID

@JvmInline
value class SalonInternalExtensionId(val value: UUID) {
    companion object {
        fun new(): SalonInternalExtensionId = SalonInternalExtensionId(UUID.randomUUID())
    }
}

/**
 * Salon Completeness (V26): the standard extension titles a salon can pick
 * from, plus [CUSTOM] for anything else. Plain application-layer enum, not a
 * database ENUM/CHECK - matches this codebase's existing convention for every
 * other classifier column ([ai.rojan.backend.domain.document.DocumentType],
 * [ai.rojan.backend.domain.verification.SalonVerificationStatus],
 * [ai.rojan.backend.domain.user.UserRole]), none of which are DB-constrained
 * either.
 */
enum class ExtensionTitleType {
    RECEPTION,
    ACCOUNTING,
    MANAGEMENT,
    RESERVATION,
    CUSTOM,
}

/**
 * One internal telephone extension for a salon - only meaningful while
 * [Salon.hasInternalExtensions] is true, but a row is never deleted just
 * because that toggle is switched off (the toggle is presentational; rows are
 * kept). Unlimited entries per salon, per the approved design - no upper bound
 * enforced here or at the schema level.
 *
 * [title] is the owner's own free-text label and is only ever meaningful for
 * [ExtensionTitleType.CUSTOM] - a standard title type's display name is
 * derived entirely from [titleType] itself. [create] forcibly nulls [title]
 * for every non-[ExtensionTitleType.CUSTOM] type, so a RECEPTION/ACCOUNTING/
 * MANAGEMENT/RESERVATION row can never carry a stray label that could drift
 * from, or contradict, its own type - [title] can never become a second,
 * uncontrolled title source.
 */
class SalonInternalExtension private constructor(
    val id: SalonInternalExtensionId,
    val salonId: SalonId,
    titleType: ExtensionTitleType,
    title: String?,
    extensionNumber: String,
    val createdAt: Instant,
) {
    var titleType: ExtensionTitleType = titleType
        private set

    var title: String? = title
        private set

    var extensionNumber: String = extensionNumber
        private set

    companion object {
        fun create(
            salonId: SalonId,
            titleType: ExtensionTitleType,
            title: String?,
            extensionNumber: String,
        ): SalonInternalExtension {
            require(extensionNumber.isNotBlank()) { "Extension number must not be blank" }
            if (titleType == ExtensionTitleType.CUSTOM) {
                require(!title.isNullOrBlank()) { "A custom extension must have a title" }
            }
            return SalonInternalExtension(
                id = SalonInternalExtensionId.new(),
                salonId = salonId,
                titleType = titleType,
                title = if (titleType == ExtensionTitleType.CUSTOM) title?.trim() else null,
                extensionNumber = extensionNumber.trim(),
                createdAt = Instant.now(),
            )
        }

        fun reconstitute(
            id: SalonInternalExtensionId,
            salonId: SalonId,
            titleType: ExtensionTitleType,
            title: String?,
            extensionNumber: String,
            createdAt: Instant,
        ): SalonInternalExtension = SalonInternalExtension(id, salonId, titleType, title, extensionNumber, createdAt)
    }
}
