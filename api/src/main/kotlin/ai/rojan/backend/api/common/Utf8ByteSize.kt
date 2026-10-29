package ai.rojan.backend.api.common

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * Bounds a `String` field's **UTF-8-encoded byte length**, not its character
 * (`String.length`/UTF-16-code-unit) count. Jakarta's own `@Size` only ever
 * checks character count, which is the wrong unit for anything downstream
 * that encodes to bytes with a byte-based limit (BCrypt's real limit, per
 * ROJAN_BACKEND_SECURITY_AUDIT_v1.md F5 / GHSA-mg83-c7gq-rv5c, is 72 *bytes*
 * - confirmed empirically against the actual resolved BCryptPasswordEncoder,
 * `BCryptPasswordEncoderAdapterTest`). A Persian/Farsi password - ROJAN is an
 * RTL-Persian-first platform - packs 2 UTF-8 bytes per character, so a
 * 72-*character* limit alone lets a 72-char Farsi password reach 144 bytes,
 * silently exceeding BCrypt's real boundary despite satisfying `@Size`.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [Utf8ByteSizeValidator::class])
annotation class Utf8ByteSize(
    val max: Int,
    val message: String = "must not exceed {max} bytes when UTF-8 encoded",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class Utf8ByteSizeValidator : ConstraintValidator<Utf8ByteSize, String> {

    private var max: Int = 0

    override fun initialize(constraintAnnotation: Utf8ByteSize) {
        max = constraintAnnotation.max
    }

    // Null/blank is a presence concern (@NotBlank's job), not this
    // constraint's - matches Jakarta Validation's own convention that a
    // null value passes every other constraint on the same field.
    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean =
        value == null || value.toByteArray(Charsets.UTF_8).size <= max
}
