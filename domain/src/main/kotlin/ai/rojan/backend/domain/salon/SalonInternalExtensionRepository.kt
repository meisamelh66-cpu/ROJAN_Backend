package ai.rojan.backend.domain.salon

/**
 * Output port for [SalonInternalExtension] persistence. Only the minimum
 * operations a future completion-profile use case needs: add one (owner
 * managing the repeatable list one entry at a time), list a salon's current
 * entries, and remove one - no bulk "replace the whole list" abstraction,
 * which would be speculative ahead of that use case actually being built.
 */
interface SalonInternalExtensionRepository {
    fun save(extension: SalonInternalExtension): SalonInternalExtension

    fun findBySalonId(salonId: SalonId): List<SalonInternalExtension>

    /** Tenant-scoped by construction, same discipline as [ai.rojan.backend.domain.document.SalonDocumentRepository.findByIdAndSalonId]. */
    fun findByIdAndSalonId(id: SalonInternalExtensionId, salonId: SalonId): SalonInternalExtension?

    fun deleteByIdAndSalonId(id: SalonInternalExtensionId, salonId: SalonId)
}
