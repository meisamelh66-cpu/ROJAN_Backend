package ai.rojan.backend.domain.common

class MediaAssetNotFoundException(identifier: String) :
    DomainException("Media asset not found: $identifier")

class MediaTypeInvalidException(mimeType: String, mediaType: String) :
    DomainException("Mime type '$mimeType' is not allowed for media type $mediaType")

class MediaSizeExceededException(actualBytes: Long, maxBytes: Long) :
    DomainException("Media file size $actualBytes bytes exceeds the maximum of $maxBytes bytes")

class MediaTypeMismatchException(mediaId: String, expectedType: String) :
    DomainException("Media asset $mediaId is not of type $expectedType")

/** Media System Evolution v2: raised when uploading a [ai.rojan.backend.domain.media.TARGET_REQUIRED_MEDIA_TYPES] type (`PORTFOLIO`/`SERVICE_IMAGE`) with no `targetId`. */
class MediaTargetRequiredException(mediaType: String) :
    DomainException("Media type $mediaType requires a targetId (specialistId or serviceId)")

/** Media System Evolution v2: a reorder request named a media id that isn't in the (salonId, mediaType, targetId) group being reordered. */
class MediaReorderMismatchException(mediaId: String) :
    DomainException("Media asset $mediaId does not belong to the group being reordered")
