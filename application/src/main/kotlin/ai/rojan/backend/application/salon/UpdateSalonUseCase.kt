package ai.rojan.backend.application.salon

import ai.rojan.backend.domain.common.SalonNotFoundException
import ai.rojan.backend.domain.salon.Permission
import ai.rojan.backend.domain.salon.Salon
import ai.rojan.backend.domain.salon.SalonId
import ai.rojan.backend.domain.salon.SalonRepository
import ai.rojan.backend.domain.user.UserId

/** [latitude]/[longitude]/[city] follow the same "null means leave unchanged" merge semantics as [ai.rojan.backend.application.customer.UpdateCustomerCommand] - there is no way to explicitly clear a previously-set value back to null via this command, the same disclosed tradeoff that one already makes for `company`. Logo/cover are no longer part of this command - see `AssignIdentityMediaUseCase`. */
data class UpdateSalonCommand(
    val salonId: SalonId,
    val callerId: UserId,
    val name: String,
    val description: String?,
    val phone: String,
    val email: String?,
    val address: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val city: String? = null,
)

class UpdateSalonUseCase(
    private val salonRepository: SalonRepository,
    private val salonPermissionResolver: SalonPermissionResolver,
) {
    fun execute(command: UpdateSalonCommand): Salon {
        val salon = salonRepository.findById(command.salonId)
            ?: throw SalonNotFoundException(command.salonId.value.toString())
        salonPermissionResolver.require(salon.id, command.callerId, Permission.MANAGE_SALON)
        salon.update(
            name = command.name,
            description = command.description,
            phone = command.phone,
            email = command.email,
            address = command.address,
        )
        salon.updateProfile(
            latitude = command.latitude ?: salon.latitude,
            longitude = command.longitude ?: salon.longitude,
            city = command.city ?: salon.city,
        )
        return salonRepository.save(salon)
    }
}
