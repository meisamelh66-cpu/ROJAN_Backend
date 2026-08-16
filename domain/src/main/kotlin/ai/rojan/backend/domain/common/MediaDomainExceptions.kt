package ai.rojan.backend.domain.common

class MediaAssetNotFoundException(identifier: String) :
    DomainException("Media asset not found: $identifier")

class MediaTypeInvalidException(mimeType: String, mediaType: String) :
    DomainException("Mime type '$mimeType' is not allowed for media type $mediaType")

class MediaSizeExceededException(actualBytes: Long, maxBytes: Long) :
    DomainException("Media file size $actualBytes bytes exceeds the maximum of $maxBytes bytes")

class MediaTypeMismatchException(mediaId: String, expectedType: String) :
    DomainException("Media asset $mediaId is not of type $expectedType")
