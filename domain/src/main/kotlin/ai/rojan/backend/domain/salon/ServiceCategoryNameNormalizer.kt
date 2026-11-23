package ai.rojan.backend.domain.salon

/**
 * Derives a comparison key for [ServiceCategory.name] duplicate detection
 * (Default Beauty Salon Category Catalog) - never used to alter what is
 * actually stored, only to decide whether two names are "the same category"
 * for a salon. Persian text commonly mixes the Arabic-script presentation
 * forms of ك/ي with the Persian ک/ی, and half-space (ZWNJ) with a plain
 * space or none at all, for what a human reads as an identical word (e.g.
 * "رنگ و مش" vs "رنگ  و مش" vs a stray ZWNJ) - collapsing those variants
 * before comparing is what makes "same salon, same category" actually
 * catch real duplicates instead of only exact byte-for-byte repeats.
 */
object ServiceCategoryNameNormalizer {
    private const val ZERO_WIDTH_NON_JOINER = '‌'

    fun normalize(name: String): String =
        name
            .replace(ZERO_WIDTH_NON_JOINER, ' ')
            .replace('ك', 'ک')
            .replace('ي', 'ی')
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
}
