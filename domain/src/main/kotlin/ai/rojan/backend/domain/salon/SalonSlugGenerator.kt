package ai.rojan.backend.domain.salon

import kotlin.random.Random

/**
 * Pure slugify + collision-avoidance logic for [Salon.slug], used by
 * `CreateSalonUseCase`. A blank result after stripping non-`[a-z0-9]`
 * characters is the *common* case for this platform's Farsi-first audience
 * (a Farsi name transliterates to nothing under this simple ASCII rule),
 * not an edge case - hence the `"salon"` fallback base rather than an
 * exception, and why `PATCH /salons/{id}/slug` exists so an owner can set a
 * real, print-worthy value afterwards.
 */
object SalonSlugGenerator {

    private const val MAX_LENGTH = 60
    private const val MAX_COLLISION_ATTEMPTS = 50

    fun baseSlugFor(name: String): String {
        val stripped = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(MAX_LENGTH)
        return stripped.ifBlank { "salon" }
    }

    /**
     * Returns the first free candidate among `base`, `base-2`, `base-3`, ...
     * (checked via [isTaken]). Falls back to a random suffix past
     * [MAX_COLLISION_ATTEMPTS] to avoid a pathological loop when many
     * salons collapse to the same blank-name base (`"salon"`, `"salon-2"`,
     * ... all real Farsi salons at pilot scale).
     */
    fun generateUnique(name: String, isTaken: (String) -> Boolean): String {
        val base = baseSlugFor(name)
        if (!isTaken(base)) return base

        for (attempt in 2..MAX_COLLISION_ATTEMPTS) {
            val candidate = "$base-$attempt"
            if (!isTaken(candidate)) return candidate
        }

        var fallback: String
        do {
            fallback = "$base-${Random.nextInt(1000, 9999)}"
        } while (isTaken(fallback))
        return fallback
    }
}
