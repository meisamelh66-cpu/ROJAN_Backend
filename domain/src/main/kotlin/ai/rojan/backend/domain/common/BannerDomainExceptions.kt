package ai.rojan.backend.domain.common

class BannerNotFoundException(identifier: String) :
    DomainException("Banner not found: $identifier")

class BannerReorderMismatchException(bannerId: String) :
    DomainException("Banner $bannerId does not belong to the target group being reordered")
