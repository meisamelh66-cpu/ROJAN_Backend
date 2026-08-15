package ai.rojan.backend.domain.common

class MediaAssetNotFoundException(identifier: String) :
    DomainException("Media asset not found: $identifier")

/** Thrown when a media asset (or a `logoMediaId`/`coverMediaId` reference to one) belongs to a different salon than the one being operated on - the core tenant-isolation guard for the whole media subsystem. */
class MediaAssetTenantMismatchException(mediaAssetId: String, salonId: String) :
    DomainException("Media asset $mediaAssetId does not belong to salon $salonId")

class UnsupportedMediaTypeException(mimeType: String) :
    DomainException("Unsupported media file type: $mimeType")

class MediaFileTooLargeException(fileSize: Long, maxAllowed: Long) :
    DomainException("Media file size $fileSize bytes exceeds the maximum allowed $maxAllowed bytes")
