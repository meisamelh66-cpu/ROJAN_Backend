package ai.rojan.backend.domain.banner

interface BannerRepository {
    fun findById(id: BannerId): Banner?

    /** Every banner for one target, admin view - includes inactive rows, pre-sorted by displayOrder. */
    fun findByTarget(target: BannerTarget): List<Banner>

    /** Public read surface - active rows only for one target, pre-sorted by displayOrder. */
    fun findActiveByTarget(target: BannerTarget): List<Banner>

    fun save(banner: Banner): Banner

    fun delete(id: BannerId)
}
