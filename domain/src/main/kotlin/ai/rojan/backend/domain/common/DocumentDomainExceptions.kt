package ai.rojan.backend.domain.common

class SalonDocumentNotFoundException(identifier: String) :
    DomainException("Salon document not found: $identifier")

class DocumentAlreadyAttachedException(mediaAssetId: String) :
    DomainException("Media asset $mediaAssetId is already attached to a document")
