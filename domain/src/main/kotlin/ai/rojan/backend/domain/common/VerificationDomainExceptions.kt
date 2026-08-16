package ai.rojan.backend.domain.common

class SalonVerificationNotFoundException(identifier: String) :
    DomainException("Salon verification not found: $identifier")

class VerificationAlreadyPendingException(salonId: String) :
    DomainException("Salon $salonId already has an active verification submission")

class InvalidVerificationDocumentException(documentId: String) :
    DomainException("Document $documentId does not belong to this salon and cannot be included in a verification submission")
