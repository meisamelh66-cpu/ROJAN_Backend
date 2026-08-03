package ai.rojan.backend.domain.salon

interface SpecialistRepository {
    fun save(specialist: Specialist): Specialist
    fun findById(id: SpecialistId): Specialist?
    fun findBySalonId(salonId: SalonId): List<Specialist>
}
